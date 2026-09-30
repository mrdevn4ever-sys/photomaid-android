package app.photomaid

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.MediaKind
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.feature.cleanup.CleanUpHubScreen
import app.photomaid.feature.today.TodayScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Tab „Aufräumen“: Zähler = noch zu prüfen (nicht Gesamtbestand), gleiche Quelle wie „Heute“.
 * Fake: 7 Fotos (davon 1 Screenshot) + 3 Videos.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HubPendingTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var lib: FakeMediaLibrary

    private suspend fun AppModel.act(ref: ItemReference, action: DecisionAction) =
        ledger.apply(DecisionCommand(item = ref, expectedRevision = ledger.record(ref).revision, action = action))

    private fun app(decide: suspend AppModel.(List<ItemReference>) -> Unit = {}) =
        makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 7, videos = 3).also { l -> lib = l } }) { a -> a.decide(lib.all.map { ItemReference.photo(it.id) }) }

    private fun refsOf(kind: (MediaKind) -> Boolean) = lib.all.filter { kind(it.kind) }.map { ItemReference.photo(it.id) }
    private val screen = androidx.compose.runtime.mutableStateOf("hub")
    private fun host(app: AppModel) = rule.setContent {
        TestHost(app) { if (screen.value == "hub") CleanUpHubScreen(RecordingNav()) else TodayScreen(RecordingNav()) }
    }
    private fun inRow(tag: String) = hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))
    private fun waitRow(tag: String, desc: String) = rule.waitUntil(10_000) {
        rule.onAllNodes(inRow(tag) and (hasContentDescription(desc, substring = true) or hasText(desc, substring = true))).fetchSemanticsNodes().isNotEmpty()
    }
    /** Alle Texte/Beschreibungen der Karte (für „zeigt NICHT …“). */
    private fun rowText(tag: String) = rule.onAllNodes(inRow(tag)).fetchSemanticsNodes().joinToString("|") { it.config.toString() }

    /** TEST 1/4/5/6: alles geprüft → Haken statt Gesamtzahl, Heute „Alles geprüft“. */
    @Test fun allReviewedShowsCheckNotTotals() {
        val app = app { refs -> refs.forEach { act(it, DecisionAction.Keep) } }
        host(app)
        for (t in listOf("hub.photos", "hub.videos", "hub.screenshots")) waitRow(t, "Nichts zu prüfen")
        assertTrue(!rowText("hub.photos").contains("Text = '[7]'") && !rowText("hub.videos").contains("Text = '[3]'") && !rowText("hub.screenshots").contains("Text = '[1]'"))
        screen.value = "today"
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Alles geprüft")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** TEST 2/3: offene Elemente je Bereich. */
    @Test fun pendingCountsPerArea() {
        val app = app { _ ->
            refsOf { it != MediaKind.VIDEO }.take(6).forEach { act(it, DecisionAction.Keep) } // 1 Foto offen (kein Screenshot)
            refsOf { it == MediaKind.VIDEO }.take(1).forEach { act(it, DecisionAction.Keep) } // 2 Videos offen
            refsOf { it == MediaKind.SCREENSHOT }.forEach { act(it, DecisionAction.Keep) }
        }
        rule.setContent { TestHost(app) { CleanUpHubScreen(RecordingNav()) } }
        waitRow("hub.photos", "1 zu prüfen")
        waitRow("hub.videos", "2 zu prüfen")
        waitRow("hub.screenshots", "Nichts zu prüfen")
    }

    /** TEST 7: neues Medium → Foto-Zähler steigt, Heute verlässt „Alles geprüft“. */
    @Test fun newMediumReopensBothScreens() {
        val app = app { refs -> refs.forEach { act(it, DecisionAction.Keep) } }
        host(app)
        waitRow("hub.photos", "Nichts zu prüfen")
        lib.addNew(0)
        waitRow("hub.photos", "1 zu prüfen")
        screen.value = "today"
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("1 Medium wartet auf dich")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** TEST 8/9: Behalten, Papierkorb, Später senken den Zähler; Undo stellt ihn wieder her. */
    @Test fun decisionsAndUndoUpdatePending() {
        val app = app()
        rule.setContent { TestHost(app) { CleanUpHubScreen(RecordingNav()) } }
        waitRow("hub.videos", "3 zu prüfen")
        val v = refsOf { it == MediaKind.VIDEO }
        runBlocking(Dispatchers.IO) { app.act(v[0], DecisionAction.Keep) }
        waitRow("hub.videos", "2 zu prüfen")
        runBlocking(Dispatchers.IO) { app.act(v[1], DecisionAction.MarkForCleanup) }
        waitRow("hub.videos", "1 zu prüfen")
        // Wie Wisch nach rechts: Später + Standardtermin (ohne Termin käme es beim nächsten Aufräumen wieder).
        runBlocking(Dispatchers.IO) { app.act(v[2], DecisionAction.Later(ReviewSessionID())); app.act(v[2], DecisionAction.Schedule(app.today.addingDays(2))) }
        waitRow("hub.videos", "Nichts zu prüfen")
        runBlocking(Dispatchers.IO) { app.undo() }
        waitRow("hub.videos", "1 zu prüfen")
    }

    /** TEST 10: Später steht mit echter Zahl auf „Heute“ – im Aufräumen-Tab nicht doppelt. */
    @Test fun laterLivesOnTodayNotTwiceInHub() {
        val app = app { refs -> refs.take(6).forEach { act(it, DecisionAction.Later(ReviewSessionID())) } }
        val screen = androidx.compose.runtime.mutableStateOf("today")
        rule.setContent { TestHost(app) { if (screen.value == "today") TodayScreen(RecordingNav()) else CleanUpHubScreen(RecordingNav()) } }
        waitRow("today.later", "6 Elemente")
        screen.value = "hub"
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("hub.photos").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodesWithTag("hub.later").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag("hub.basket").fetchSemanticsNodes().isEmpty())
    }

    /** TEST 11: keine Büro-/Entwicklerbegriffe in sichtbaren deutschen Texten. */
    @Test fun visibleCopyHasNoTechnicalTerms() {
        val xml = java.io.File("src/main/res/values/strings.xml").readText()
        val visible = Regex(">([^<]+)<").findAll(xml).map { it.groupValues[1] }.joinToString("\n")
        for (w in listOf("Wiedervorlage", "zurückgestellt", "Zurückstellung", "Snooze", "Review Queue", "Ähnliches prüfst", "unnötige Videos"))
            assertTrue("„$w“ ist noch sichtbar", !visible.contains(w, ignoreCase = true))
    }
}
