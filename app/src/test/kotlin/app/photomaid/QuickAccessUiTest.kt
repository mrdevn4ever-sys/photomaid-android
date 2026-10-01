package app.photomaid

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.MediaKind
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.core.quickaccess.QuickAccess
import app.photomaid.core.quickaccess.QuickAccessItem
import app.photomaid.core.quickaccess.QuickSourceKind
import app.photomaid.feature.quickaccess.assetsFor
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.feature.today.TodayScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Schnellzugriffe: Quellen sind Abfragen, der Zustand ist global je Medium.
 * Fake: 5 Fotos (Nr. 2 = Screenshot) + 1 Video, alle in DCIM/Camera.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+w393dp-h1400dp-xxhdpi")
class QuickAccessUiTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var lib: FakeMediaLibrary
    private val q = { k: QuickSourceKind -> QuickAccessItem(k) }

    private suspend fun AppModel.act(ref: ItemReference, action: DecisionAction) =
        ledger.apply(DecisionCommand(item = ref, expectedRevision = ledger.record(ref).revision, action = action))

    private fun app(items: List<QuickAccessItem>? = null) = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 5, videos = 1).also { l -> lib = l } }) { a ->
        if (items != null) a.quickAccess.update { items }
    }
    private fun ref(i: Int) = ItemReference.photo(lib.all[i].id)
    private fun tile(key: String, status: String) = rule.waitUntil(10_000) {
        rule.onAllNodes(hasTestTag("quick.$key") and hasContentDescription(status, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitText(t: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun defaultsArePhotosAndVideosWithOpenCounts() {
        val app = app()
        rule.setContent { TestHost(app) { TodayScreen(RecordingNav()) } }
        tile("PHOTOS", "5 offen")
        tile("VIDEOS", "1 offen")
        rule.onNodeWithTag("quick.add").assertExists()
        // Papierkorb und Später bleiben feste Karten, keine Schnellzugriffe.
        rule.onNodeWithTag("today.basket").assertExists(); rule.onNodeWithTag("today.later").assertExists()
    }

    /** EINE Entscheidung, alle Quellen: Screenshot ist in Fotos, Neueste Fotos und Screenshots. Hero zählt einmal. */
    @Test fun oneDecisionUpdatesEverySourceAndHeroCountsOnce() {
        val app = app(listOf(q(QuickSourceKind.PHOTOS), q(QuickSourceKind.LATEST_PHOTOS), q(QuickSourceKind.SCREENSHOTS), q(QuickSourceKind.VIDEOS)))
        rule.setContent { TestHost(app) { TodayScreen(RecordingNav()) } }
        tile("PHOTOS", "5 offen"); tile("LATEST_PHOTOS", "5 offen"); tile("SCREENSHOTS", "1 offen")
        waitText("Noch 6 Fotos und Videos durchsehen")
        val shot = lib.all.indexOfFirst { it.kind == MediaKind.SCREENSHOT }
        runBlocking(Dispatchers.IO) { app.act(ref(shot), DecisionAction.Keep) }
        tile("PHOTOS", "4 offen"); tile("LATEST_PHOTOS", "4 offen"); tile("SCREENSHOTS", "Alles geprüft")
        waitText("Noch 5 Fotos und Videos durchsehen") // 1 globales Medium weniger, nicht 3
        assertEquals(1, app.ledger.stateFlow.value.events.size) // ein einziger Write
    }

    /** Später (nicht fällig) verschwindet überall; Undo bringt es überall zurück. */
    @Test fun laterHidesEverywhereAndUndoRestoresEverywhere() {
        val app = app(listOf(q(QuickSourceKind.PHOTOS), q(QuickSourceKind.LATEST_PHOTOS), q(QuickSourceKind.SCREENSHOTS)))
        rule.setContent { TestHost(app) { TodayScreen(RecordingNav()) } }
        tile("SCREENSHOTS", "1 offen")
        val shot = ref(lib.all.indexOfFirst { it.kind == MediaKind.SCREENSHOT })
        runBlocking(Dispatchers.IO) { app.act(shot, DecisionAction.Later(ReviewSessionID())); app.act(shot, DecisionAction.Schedule(app.today.addingDays(2))) }
        tile("PHOTOS", "4 offen"); tile("LATEST_PHOTOS", "4 offen"); tile("SCREENSHOTS", "Alles geprüft")
        runBlocking(Dispatchers.IO) { app.undo() }
        tile("PHOTOS", "5 offen"); tile("LATEST_PHOTOS", "5 offen"); tile("SCREENSHOTS", "1 offen")
    }

    @Test fun addEditRemoveAndLimitPersist() {
        val app = app()
        rule.setContent { TestHost(app) { TodayScreen(RecordingNav()) } }
        rule.onNodeWithTag("quick.add").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("quick.option.SCREENSHOTS").fetchSemanticsNodes().isNotEmpty() }
        // Stabiler Katalog: schon vorhandene Quelle bleibt sichtbar, ist aber nicht antippbar („Auf Heute“).
        rule.onNodeWithTag("quick.option.PHOTOS").assertIsNotEnabled()
        rule.onNode(hasTestTag("quick.option.PHOTOS") and hasText("Auf der Startseite", substring = true)).assertExists()
        rule.onNode(hasTestTag("quick.option.SCREENSHOTS") and hasText("1")).assertExists() // echte offene Anzahl je Option
        rule.onNodeWithTag("quick.option.SCREENSHOTS").performClick()
        tile("SCREENSHOTS", "1 offen")
        for (k in listOf("LATEST_PHOTOS", "LATEST_VIDEOS", "THIS_WEEK")) {
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("quick.sheet").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag("quick.add").performScrollTo().performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("quick.option.$k").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("quick.option.$k").performClick()
        }
        rule.waitUntil(5_000) { app.quickAccess.items.value.size == QuickAccess.MAX_ITEMS }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("quick.add").fetchSemanticsNodes().isEmpty() } // Grenze erreicht
        // Gespeichert wird die Abfrage (Art), keine Medien-IDs.
        val stored = runBlocking(Dispatchers.IO) { app.store.value<List<QuickAccessItem>>("quickaccess.v1") }!!
        assertEquals(listOf("PHOTOS", "VIDEOS", "SCREENSHOTS", "LATEST_PHOTOS", "LATEST_VIDEOS", "THIS_WEEK"), stored.map { it.key })
        // Bearbeiten: Videos nach vorn, Fotos entfernen.
        rule.onNodeWithTag("quick.edit").performClick()
        // Sortieren ohne Ziehen (TalkBack/Schalter): Aktion der Zeile.
        val up = rule.onNodeWithTag("quick.edit.VIDEOS").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
            .single { it.label == "Nach vorn: Videos" }
        rule.runOnIdle { up.action() }
        rule.waitUntil(5_000) { app.quickAccess.items.value.first().kind == QuickSourceKind.VIDEOS }
        rule.onNode(hasContentDescription("Entfernen: Fotos")).performClick()
        rule.waitUntil(5_000) { app.quickAccess.items.value.none { it.kind == QuickSourceKind.PHOTOS } }
    }

    @Test fun folderSourceFromMediaStoreFolders() {
        val app = app()
        rule.setContent { TestHost(app) { TodayScreen(RecordingNav()) } }
        rule.onNodeWithTag("quick.add").performScrollTo().performClick()
        // Ordner stehen direkt im Sheet (die größten zuerst) – kein zweiter Schritt.
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("quick.folder.DCIM/Camera/").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("quick.folder.DCIM/Camera/").performScrollTo().performClick()
        tile("FOLDER:DCIM/Camera/", "6 offen")
        rule.onNode(hasTestTag("quick.FOLDER:DCIM/Camera/") and hasContentDescription("Camera", substring = true)).assertExists()
    }

    /** Quellenwechsel ohne Neustart: in „Neueste Fotos“ entschieden → „Fotos“ fragt es nicht erneut. */
    @Test fun switchingSourcesSeesDecisions() {
        val app = app()
        val src = mutableStateOf(QuickSourceKind.LATEST_PHOTOS)
        rule.setContent { TestHost(app) { androidx.compose.runtime.key(src.value) {
            ReviewFeedScreen(FeedRequest(FeedRequest.Source.Quick(q(src.value))), RecordingNav(), onClose = {}, openBasket = {})
        } } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("feed.media.ready").fetchSemanticsNodes().isNotEmpty() && rule.onAllNodes(hasText("1 von 5")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("2 von 5")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Disposition.KEPT, app.ledger.stateFlow.value.records[ref(0)]?.disposition)
        src.value = QuickSourceKind.PHOTOS
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("1 von 4")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun emptySourceShowsSourceSpecificDoneState() {
        val app = app().also { a -> runBlocking(Dispatchers.IO) { lib.all.filter { it.kind == MediaKind.SCREENSHOT }.forEach { a.act(ItemReference.photo(it.id), DecisionAction.Keep) } } }
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Quick(q(QuickSourceKind.SCREENSHOTS))), RecordingNav(), onClose = {}, openBasket = {}) } }
        waitText("Gerade gibt es keine weiteren Screenshots durchzusehen.")
        rule.onNodeWithText("Alles geprüft").assertExists()
    }

    /** Neues Medium dieser Woche wird Mitglied mehrerer Quellen (einmal als Medium); extern gelöschtes verschwindet überall. */
    @Test fun newAndDeletedMediaUpdateAllSources() {
        val app = app(listOf(q(QuickSourceKind.PHOTOS), q(QuickSourceKind.THIS_WEEK), q(QuickSourceKind.LATEST_PHOTOS)))
        rule.setContent { TestHost(app) { TodayScreen(RecordingNav()) } }
        tile("PHOTOS", "5 offen")
        val before = lib.assetsFor(q(QuickSourceKind.THIS_WEEK)).size
        val n = lib.addNew(0, date = Instant.now())
        tile("PHOTOS", "6 offen"); tile("THIS_WEEK", "${before + 1} offen"); tile("LATEST_PHOTOS", "6 offen")
        waitText("Noch 7 Fotos und Videos durchsehen")
        lib.removed += n.id; lib.bumpChangeToken()
        tile("PHOTOS", "5 offen"); tile("LATEST_PHOTOS", "5 offen")
    }
}
