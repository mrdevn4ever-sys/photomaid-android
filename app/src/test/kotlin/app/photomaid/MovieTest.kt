package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.model.ItemReference
import app.photomaid.core.edit.EditKind
import app.photomaid.core.model.MediaKind
import app.photomaid.core.studio.Transition
import app.photomaid.feature.studio.MovieRequest
import app.photomaid.feature.studio.MovieScreen
import app.photomaid.feature.studio.movieFrom
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

/** P6: Video zusammenfügen / Diashow als Video. Quellen bleiben ohne Entscheidung, Ergebnis gilt als „Behalten“. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+w393dp-h852dp")
class MovieTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()

    private fun start(pro: Boolean) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 5, videos = 2).also { l -> lib = l } },
            purchase = if (pro) FakePurchaseGateway(PurchaseState.PURCHASED) else null)
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
    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    private fun click(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }

    @Test fun projectFromSelectionKeepsOrderAndKinds() {
        start(pro = false)
        val all = lib.reviewableMedia()
        val p = movieFrom(all, slideshow = false)
        assertEquals(all.map { it.id }, p.clips.map { it.mediaId })
        assertEquals(all.map { it.kind != MediaKind.VIDEO }, p.clips.map { it.isPhoto })
        val s = movieFrom(all, slideshow = true)
        assertTrue(s.clips.all { it.isPhoto }); assertTrue(s.transitions.all { it == Transition.FADE_BLACK })
    }

    @Test fun editClipsAndSaveAsNewVideoWithPro() {
        start(pro = true)
        val all = lib.reviewableMedia().take(4)
        var closed = false
        rule.setContent { TestHost(app) { MovieScreen(MovieRequest("m", movieFrom(all, false), false), nav, onClose = { closed = true }) } }
        pumpUntil { count("movie.clip") == 4 }
        click("movie.right") // Clip 1 nach hinten
        click("movie.remove")
        pumpUntil { count("movie.clip") == 3 }
        click("movie.tab.TRANSITION"); click("movie.tr.FADE_BLACK")
        click("movie.tab.FORMAT"); click("movie.aspect.1.0"); click("movie.fill")
        click("movie.save")
        pumpUntil { (app.movieExporter as FakeMovieExporter).exported.isNotEmpty() }
        val p = (app.movieExporter as FakeMovieExporter).exported.single()
        assertEquals(3, p.clips.size)
        // Clip 1 nach hinten getauscht, dann der gewählte (jetzt Medium 2) entfernt.
        assertEquals(listOf(all[0].id, all[2].id, all[3].id), p.clips.map { it.mediaId })
        assertTrue(p.transitions.all { it == Transition.FADE_BLACK }); assertEquals(1f, p.aspect); assertTrue(p.fill)
        pumpUntil { app.edits.records.value.any { it.kind == EditKind.MERGE } }
        val rec = app.edits.records.value.first { it.kind == EditKind.MERGE }
        assertEquals(Disposition.KEPT, runBlocking { app.ledger.record(ItemReference.photo(rec.newId)) }.disposition)
        // Quellen ohne Entscheidung.
        assertTrue(all.all { runBlocking { app.ledger.record(it.reference) }.disposition == Disposition.UNDECIDED })
        pumpUntil { rule.onAllNodes(hasText("Movies/GaleriQ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun freeUsersCanTryButSavingOpensPro() {
        start(pro = false)
        val photos = lib.reviewableMedia().filter { it.kind != MediaKind.VIDEO }.take(3)
        rule.setContent { TestHost(app) { MovieScreen(MovieRequest("s", movieFrom(photos, true), true), nav, onClose = {}) } }
        pumpUntil { count("movie.clip") == 3 }
        assertTrue(count("editor.prohint") == 1)
        click("movie.dur.5")
        click("movie.save")
        pumpUntil { nav.events.contains("pro") }
        assertTrue((app.movieExporter as FakeMovieExporter).exported.isEmpty())
    }
}
