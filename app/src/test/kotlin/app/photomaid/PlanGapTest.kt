package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.MediaKind
import app.photomaid.feature.editor.EditorScreen
import app.photomaid.feature.later.LaterScreen
import app.photomaid.feature.library.ViewerRequest
import app.photomaid.feature.library.ViewerScreen
import app.photomaid.feature.months.MonthsScreen
import app.photomaid.feature.storage.LargeFilesScreen
import app.photomaid.feature.studio.ToolKind
import app.photomaid.feature.studio.ToolRequest
import app.photomaid.feature.studio.ToolsScreen
import app.photomaid.feature.today.TodayScreen
import app.photomaid.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Prüflog R2 (Issue #3): Lücken der Funktionsliste, die noch keinen eigenen Test hatten (IDs aus FUNKTIONS_PRUEFPLAN). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w393dp-h852dp")
class PlanGapTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()
    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val gen = mutableIntStateOf(0)
    private var shown = false

    private fun start(pro: Boolean = false, setup: (FakeMediaLibrary) -> Unit = {}) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l; setup(l) } },
            purchase = if (pro) FakePurchaseGateway(PurchaseState.PURCHASED) else null)
        app.editEngine.io = Dispatchers.Main; app.editEngine.parallel = false
    }
    private fun pumpUntil(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        rule.mainClock.autoAdvance = false
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(5)
            if (cond()) break
            check(System.currentTimeMillis() < end) { "Bedingung nicht erfüllt" }
        }
        rule.mainClock.autoAdvance = true
    }
    private fun show(c: @Composable () -> Unit) {
        content.value = c; gen.intValue++
        if (!shown) { shown = true; rule.setContent { TestHost(app) { key(gen.intValue) { content.value?.invoke() } } } }
        pumpUntil { true }
    }
    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    private fun click(tag: String) { pumpUntil { count(tag) > 0 }; rule.onAllNodesWithTag(tag)[0].performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
    private fun has(t: String) = rule.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** H3/H5: Zeilen auf „Heute“ öffnen Papierkorb, Später und Speicherübersicht. */
    @Test fun todayRowsOpenTheirScreens() {
        start()
        runBlocking { app.decide(lib.all[0].reference, DecisionAction.MarkForCleanup); app.decide(lib.all[1].reference, DecisionAction.Later(ReviewSessionID())); app.decide(lib.all[1].reference, DecisionAction.Schedule(app.today.addingDays(3))) }
        show { TodayScreen(nav) }
        click("today.basket"); assertTrue(nav.events.toString(), nav.events.contains("push:${Routes.BASKET}"))
        click("today.later"); assertTrue(nav.events.toString(), nav.events.contains("push:${Routes.LATER}"))
        if (count("today.storage") > 0) { click("today.storage"); assertTrue(nav.events.toString(), nav.events.contains("push:${Routes.STORAGE}")) }
    }

    /** C6: große Dateien – bekannte Größen ab Grenze, unbekannte getrennt (nie als 0). */
    @Test fun largeFilesSeparateUnknownSizes() {
        start(setup = { l -> l.sizes[l.all.first { it.displayName == "video_portrait" }.id] = KnownSize.Unknown })
        runBlocking { app.updateSettings { it.copy(largeFileThresholdMB = 20) } }
        show { LargeFilesScreen(nav) }
        pumpUntil { has("Größe unbekannt (1)") }
        assertTrue(has("48,2 MB"))               // video_coast ≥ 20 MB, als „Video“ mit Dauer · Auflösung
        assertTrue(has("1920 × 1080"))
        assertFalse(has("0 B"))                  // unbekannte Größe nie als 0
    }

    /** L1: „Aus Später entfernen“ hebt nur die Wiedervorlage auf, die Datei bleibt. */
    @Test fun laterRemoveKeepsTheFile() {
        start()
        val a = lib.all[0]
        runBlocking { app.decide(a.reference, DecisionAction.Later(ReviewSessionID())); app.decide(a.reference, DecisionAction.Schedule(app.today.addingDays(3))) }
        show { LaterScreen(nav) }
        pumpUntil { rule.onAllNodes(hasContentDescription("Aktionen")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasContentDescription("Aktionen"))[0].performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true }
        click("later.remove")
        pumpUntil { runBlocking { app.ledger.record(a.reference) }.disposition != Disposition.LATER }
        assertTrue(File(a.id.removePrefix("file://")).exists())
        assertFalse(a.id in lib.trashed)
    }

    /** G7: Info im Betrachter zeigt echte Angaben (Ordner, Auflösung) und ist keine Entscheidung. */
    @Test fun viewerInfoShowsDetails() {
        start()
        val a = lib.all[0]
        show { ViewerScreen(ViewerRequest("v", listOf(a), 0), nav, onClose = {}) }
        click("viewer.more"); click("viewer.info")
        pumpUntil { has("Auflösung") }
        assertTrue(has("3024 × 4032")); assertTrue(has("Ordner"))
        assertEquals(Disposition.UNDECIDED, runBlocking { app.ledger.record(a.reference) }.disposition)
    }

    /** T13: GIF aus einem Video (Bilder aus dem gewählten Bereich, 10 je Sekunde), Ergebnis = Behalten, Quelle ohne Entscheidung. */
    @Test fun gifFromVideoUsesFramesOfTheChosenPart() {
        start(pro = true)
        val v = lib.all.first { it.kind == MediaKind.VIDEO && it.displayName == "video_portrait" }
        show { ToolsScreen(ToolRequest("g", ToolKind.GIF, listOf(v.id)), nav, onClose = {}) }
        pumpUntil { count("tools.gifrange") == 1 }
        val before = lib.saved.size
        click("tools.start")
        pumpUntil(60_000) { lib.saved.size == before + 1 }
        val fake = app.videoExporter as FakeVideoEditExporter
        assertTrue("Bilder aus dem Video: ${fake.frames.size}", fake.frames.size >= 20)
        assertTrue(fake.frames.all { it in 0..3_000 })
        assertEquals("GIF89a", String(File(lib.all.last().id.removePrefix("file://")).readBytes().copyOfRange(0, 6)))
        // Erst das „Behalten“ des neuen GIFs fertig werden lassen (sonst blockiert runBlocking den Hauptthread gegen die App).
        pumpUntil { app.edits.records.value.any { it.kind == app.photomaid.core.edit.EditKind.GIF } }
        repeat(30) { pumpUntil { true } }
        assertEquals(Disposition.UNDECIDED, runBlocking { app.ledger.record(v.reference) }.disposition)
    }

    /** S4: Coach-Marks im Editor und bei Monaten erscheinen einmal und kommen nach „Überspringen“ nicht wieder. */
    @Test fun coachMarksShowOnceAndAreRemembered() {
        start()
        runBlocking { app.updateSettings { it.copy(editorTourSeen = false, monthsTourSeen = false) } }
        show { EditorScreen(lib.all[0].id, nav, onClose = {}) }
        pumpUntil { count("coach.card") == 1 }
        click("coach.skip")
        pumpUntil { app.settings.value.editorTourSeen }
        show { EditorScreen(lib.all[1].id, nav, onClose = {}) }
        pumpUntil { count("editor.stage") > 0 }; repeat(20) { pumpUntil { true } }
        assertEquals(0, count("coach.card"))
        show { MonthsScreen(nav) }
        pumpUntil { count("coach.card") == 1 }
        click("coach.skip")
        pumpUntil { app.settings.value.monthsTourSeen }
    }
}
