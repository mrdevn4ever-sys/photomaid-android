package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.decisions.Disposition
import app.photomaid.feature.library.LibraryScreen
import app.photomaid.feature.library.ViewerRequest
import app.photomaid.feature.library.ViewerScreen
import app.photomaid.media.reviewableMedia
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** G1: Zeitachse, Filter, Suche ohne Akzente, Auswahl → PhotoMaid-Papierkorb, Betrachter. Ansehen entscheidet nie. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+w393dp-h852dp")
class LibraryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()

    private fun start(setup: (FakeMediaLibrary) -> Unit = {}) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 8, videos = 2).also { l -> lib = l; setup(l) } })
    }
    private fun pumpUntil(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        rule.mainClock.autoAdvance = false
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(5)
            if (cond()) break
            check(System.currentTimeMillis() < end) { "Bedingung nicht erfüllt" }
        }
        rule.mainClock.autoAdvance = true
    }
    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    private fun click(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
    private fun showLibrary() { rule.setContent { TestHost(app) { LibraryScreen(nav) } }; pumpUntil { count("lib.tile") > 0 } }
    private fun records() = app.ledger.stateFlow.value.records

    @Test fun timelineGroupsByDayMonthYearAndFiltersVideos() {
        start { l -> (0 until 10).forEach { i -> l.takenAt[i] = Instant.parse("2026-0${1 + i % 3}-1${i % 2}T10:00:00Z") } }
        showLibrary()
        val days = count("lib.header")
        click("lib.level.MONTH")
        val months = count("lib.header")
        click("lib.level.YEAR")
        assertEquals(1, count("lib.header"))
        assertTrue("Tage $days > Monate $months", days > months && months == 3)
        click("lib.filter.VIDEOS")
        assertEquals(2, count("lib.tile"))
        assertTrue(records().isEmpty()) // nur ansehen
    }

    @Test fun searchIgnoresAccentsAndShowsEmptyState() {
        start { l -> l.paths["placeholder"] = "" }
        lib.paths[lib.all[3].id] = "Pictures/Été 2026/"
        showLibrary()
        click("lib.search"); pumpUntil { count("lib.query") > 0 }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("lib.query").performTextInput("ete")
        pumpUntil { count("lib.tile") == 1 }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("lib.query").performTextInput("xyz")
        pumpUntil { count("lib.empty") == 1 }
    }

    @Test fun selectionMovesToPhotoMaidTrashButProtectsFavorites() {
        start()
        lib.favorites[lib.all[1].id] = true
        showLibrary()
        val tiles = rule.onAllNodesWithTag("lib.tile")
        tiles[0].performSemanticsAction(SemanticsActions.OnLongClick); pumpUntil { count("lib.selbar") == 1 }
        // Kachel 0 und die Favoriten-Kachel wählen (Reihenfolge = neueste zuerst)
        val ordered = lib.reviewableMedia()
        val favIndex = ordered.indexOfFirst { it.id == lib.all[1].id }
        rule.onAllNodesWithTag("lib.tile")[favIndex].performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true }
        click("lib.sel.trash")
        pumpUntil { rule.onAllNodes(androidx.compose.ui.test.hasText("In den Papierkorb") and androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(androidx.compose.ui.test.hasText("In den Papierkorb") and androidx.compose.ui.test.hasClickAction()).onFirst()
            .performSemanticsAction(SemanticsActions.OnClick)
        pumpUntil { records().values.any { it.disposition == Disposition.MARKED_FOR_CLEANUP } }
        val marked = records().filterValues { it.disposition == Disposition.MARKED_FOR_CLEANUP }.keys.map { it.localIdentifier }
        assertEquals(listOf(ordered[0].id), marked) // Favorit geschützt
    }

    @Test fun tapOpensViewerWithTimelineOrder() {
        start()
        showLibrary()
        rule.onAllNodesWithTag("lib.tile")[2].performSemanticsAction(SemanticsActions.OnClick); pumpUntil { nav.viewers.isNotEmpty() }
        val r = nav.viewers.single()
        assertEquals(2, r.start)
        assertEquals(lib.reviewableMedia().map { it.id }, r.assets.map { it.id })
    }

    @Test fun viewerPagesWithoutDecisionsAndTrashCanBeUndone() {
        start()
        val list = runBlocking { lib.reviewableMedia() }
        var closed = false
        rule.setContent { TestHost(app) { ViewerScreen(ViewerRequest("v", list, 0), nav, onClose = { closed = true }) } }
        pumpUntil { count("viewer.index") == 1 }
        rule.onNodeWithTag("viewer").performTouchInput { swipeLeft() }
        pumpUntil { rule.onAllNodes(androidx.compose.ui.test.hasText("2 / ${list.size}", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(records().isEmpty())
        click("viewer.more"); pumpUntil { count("viewer.trash") == 1 }
        click("viewer.trash")
        pumpUntil { count("viewer.undo") == 1 }
        assertEquals(Disposition.MARKED_FOR_CLEANUP, records()[list[1].reference]?.disposition)
        click("viewer.undo.action")
        pumpUntil { records()[list[1].reference]?.disposition == Disposition.UNDECIDED }
        click("viewer.more"); pumpUntil { count("viewer.info") == 1 }
        click("viewer.info"); pumpUntil { count("viewer.infosheet") == 1 }
        click("viewer.close"); pumpUntil { closed }
    }
}
