package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.document.PdfRules
import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.MediaKind
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.media.reviewableMedia
import app.photomaid.media.MediaWriteResult
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.model.SizeTally
import app.photomaid.design.SizeText
import app.photomaid.feature.history.HistoryScreen
import app.photomaid.feature.later.LaterScreen
import app.photomaid.feature.library.LibraryScreen
import app.photomaid.feature.library.ViewerRequest
import app.photomaid.feature.library.ViewerScreen
import app.photomaid.feature.storage.LargeFilesScreen
import app.photomaid.feature.storage.StorageInsightsScreen
import app.photomaid.feature.studio.StudioScreen
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

/**
 * Funktionstest 2026-10: Lücken aus dem Prüfplan (Betrachter-Favorit, Verlauf-Filter, Speicherübersicht, Schwelle „Große Dateien“,
 * PDF- und Diashow-Einstiege). Nichts davon trifft eine Entscheidung.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w393dp-h2400dp")
class FunctionGapTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()

    private fun start(purchased: Boolean = false, setup: (FakeMediaLibrary) -> Unit = {}) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 6, videos = 2).also { l -> lib = l; setup(l) } },
            purchase = if (purchased) FakePurchaseGateway(PurchaseState.PURCHASED) else null)
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
    private fun texts(t: String, substring: Boolean = false) = rule.onAllNodes(hasText(t, substring = substring)).fetchSemanticsNodes().size
    private fun click(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
    private fun clickText(t: String) {
        rule.onAllNodes(hasText(t) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true }
    }
    private fun s(id: Int) = rule.activity.getString(id)
    private fun records() = app.ledger.stateFlow.value.records

    @Test fun viewerFavoriteWritesSystemFavoriteAndIsNoDecision() {
        start()
        val list = runBlocking { lib.reviewableMedia() }
        rule.setContent { TestHost(app) { ViewerScreen(ViewerRequest("v", list, 0), nav, onClose = {}) } }
        pumpUntil { count("viewer.fav") == 1 }
        click("viewer.fav"); pumpUntil { lib.favorites[list[0].id] == true }
        pumpUntil { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(s(R.string.fav_remove))).fetchSemanticsNodes().isNotEmpty() }
        click("viewer.fav"); pumpUntil { lib.favorites[list[0].id] == false }
        assertEquals(listOf("favorite:true", "favorite:false"), lib.writes)
        // Abgelehnt (Systemdialog) → bleibt unverändert, keine Entscheidung.
        lib.writeResult = MediaWriteResult.Failed("nein")
        click("viewer.fav"); pumpUntil { texts(s(R.string.fav_failed)) > 0 }
        assertTrue(records().isEmpty())
    }

    @Test fun historyFiltersShowOnlyMatchingEvents() {
        start()
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            app.decide(lib.all[0].reference, DecisionAction.Keep)
            app.decide(lib.all[1].reference, DecisionAction.Later(ReviewSessionID()))
        }
        rule.setContent { TestHost(app) { HistoryScreen(nav) } }
        val empty = s(R.string.history_filter_empty_title)
        pumpUntil { texts(s(R.string.history_kept)) > 0 }
        assertEquals(0, texts(empty))
        clickText(s(R.string.history_cleaned)); pumpUntil { texts(empty) > 0 }
        clickText(s(R.string.history_kept)); pumpUntil { texts(empty) == 0 }
        clickText(s(R.string.history_later)); pumpUntil { texts(empty) == 0 }
        clickText(s(R.string.history_all)); pumpUntil { texts(empty) == 0 }
        assertEquals(2, records().size)
    }

    @Test fun storageOverviewShowsRealCountsAndSizes() {
        start { it.sizes[it.all[7].id] = KnownSize.Unknown }
        rule.setContent { TestHost(app) { StorageInsightsScreen(nav) } }
        val videos = lib.all.filter { it.kind == MediaKind.VIDEO }
        val expected = rule.activity.resources.getQuantityString(R.plurals.storage_value_items, videos.size, videos.size,
            SizeText.tally(rule.activity, SizeTally.of(videos.map { it.size })))
        pumpUntil { texts(expected) > 0 }
        // Unbekannte Größe wird nie als 0 gezählt.
        assertFalse(expected.startsWith("2 · 0"))
    }

    @Test fun largeFilesThresholdFiltersAndIsRemembered() {
        start { l -> val v = l.all.filter { it.kind == MediaKind.VIDEO }
            l.sizes[v[0].id] = KnownSize.Known(600_000_000L); l.sizes[v[1].id] = KnownSize.Known(120_000_000L) }
        rule.setContent { TestHost(app) { LargeFilesScreen(nav) } }
        pumpUntil { count("large.detail") > 0 }
        clickText(SizeText.bytes(500_000_000L)); pumpUntil { app.settings.value.largeFileThresholdMB == 500 }
        pumpUntil { count("large.detail") == 1 }
        clickText(SizeText.bytes(1_000_000_000L)); pumpUntil { app.settings.value.largeFileThresholdMB == 1_000 }
        pumpUntil { texts(s(R.string.large_none)) > 0 }
        clickText(SizeText.bytes(100_000_000L)); pumpUntil { count("large.detail") == 2 }
        assertTrue(records().isEmpty())
    }

    @Test fun studioPdfAndSlideshowEntriesPickThenOpen() {
        start()
        rule.setContent { TestHost(app) { StudioScreen(nav) } }
        pumpUntil { count("studio.pdf") == 1 }
        rule.onNodeWithTag("studio.pdf").performScrollTo(); click("studio.pdf")
        val pdf = nav.picks.single()
        assertTrue(pdf.photos); assertFalse(pdf.videos); assertEquals(PdfRules.PRO_MAX, pdf.max)
        val photos = lib.all.filter { it.kind != MediaKind.VIDEO }.take(3).map { it.id }
        pdf.onDone(photos); pumpUntil { nav.pdfs.isNotEmpty() }
        assertEquals(photos, nav.pdfs.single().assetIds)

        rule.onNodeWithTag("studio.slideshow").performScrollTo(); click("studio.slideshow")
        val show = nav.picks.last()
        assertFalse(show.videos); assertEquals(2, show.min)
        show.onDone(photos); pumpUntil { nav.movies.isNotEmpty() }
        assertTrue(nav.movies.single().slideshow)
        assertEquals(3, nav.movies.single().project.clips.size)
        assertTrue(records().isEmpty())
    }

    @Test fun librarySelectionOpensPdfWithChosenPhotos() {
        start()
        rule.setContent { TestHost(app) { LibraryScreen(nav) } }
        pumpUntil { count("lib.tile") > 0 }
        val ordered = runBlocking { lib.reviewableMedia() }
        rule.onAllNodesWithTag("lib.tile")[0].performSemanticsAction(SemanticsActions.OnLongClick); pumpUntil { count("lib.selbar") == 1 }
        rule.onAllNodesWithTag("lib.tile")[1].performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true }
        click("lib.sel.more"); pumpUntil { count("lib.sel.pdf") == 1 }
        click("lib.sel.pdf"); pumpUntil { nav.pdfs.isNotEmpty() }
        assertEquals(ordered.take(2).map { it.id }.toSet(), nav.pdfs.single().assetIds.toSet())
        assertTrue(records().isEmpty())
    }

    @Test fun laterReviewNowOpensOnlyThatItemAndDueButtonOpensAllDue() {
        start()
        val a = lib.all[0].reference; val b = lib.all[1].reference
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            for (r in listOf(a, b)) { app.decide(r, DecisionAction.Later(ReviewSessionID())); app.decide(r, DecisionAction.Schedule(app.today)) }
        }
        val before = records()
        rule.setContent { TestHost(app) { LaterScreen(nav) } }
        val label = s(R.string.actions)
        pumpUntil { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(label)).fetchSemanticsNodes().size == 2 }
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(label)).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        pumpUntil { texts(s(R.string.later_review_now)) > 0 }
        clickText(s(R.string.later_review_now)); pumpUntil { nav.requests.isNotEmpty() }
        val one = nav.requests.single().source as FeedRequest.Source.Items
        assertEquals(1, one.items.size); assertTrue(one.items.single() in setOf(a, b))
        clickText(rule.activity.getString(R.string.later_review_now_count, 2)); pumpUntil { nav.requests.size == 2 }
        assertEquals(setOf(a, b), (nav.requests.last().source as FeedRequest.Source.Items).items.toSet())
        assertEquals("Öffnen ist keine Entscheidung", before, records())
    }
}
