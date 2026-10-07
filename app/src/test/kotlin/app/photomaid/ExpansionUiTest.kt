package app.photomaid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.model.ItemReference
import app.photomaid.core.progress.MonthKey
import app.photomaid.core.feed.FeedOrder
import app.photomaid.feature.grid.MediaGridScreen
import app.photomaid.feature.later.LaterScreen
import app.photomaid.feature.months.MonthsScreen
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.feature.settings.ExcludedFoldersScreen
import app.photomaid.feature.today.TodayScreen
import app.photomaid.media.MediaCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** Ausbau 2026-10: Tasten-Option, Reihenfolge, Ordner ausnehmen, Monate, Später ohne Termin, Serien, Raster, Heute-Karten. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+w393dp-h852dp-xxhdpi")
class ExpansionUiTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()

    private fun build(count: Int = 5, purchase: PurchaseState? = null, prep: (FakeMediaLibrary) -> Unit = {}, configure: suspend (AppModel) -> Unit = {}) {
        app = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = count, videos = 0).also { l -> lib = l; prep(l) } },
            purchase = purchase?.let { FakePurchaseGateway(it) }, configure = configure)
    }
    private fun feed(source: FeedRequest.Source = FeedRequest.Source.Category(MediaCategory.PHOTOS)) =
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(source), nav, onClose = {}, openBasket = {}) } }
    private fun wait(t: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitTag(t: String) { rule.waitForIdle(); rule.waitUntil(20_000) { rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() } }
    private fun ready(position: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithTag("feed.media.ready").fetchSemanticsNodes().isNotEmpty() && rule.onAllNodes(hasText(position)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun ref(i: Int) = ItemReference.photo(lib.all[i].id)
    private fun rec(i: Int) = app.ledger.stateFlow.value.records[ref(i)] ?: ItemRecord(ref(i))

    // 3.4 Tasten zum Entscheiden (Bedienungshilfe): gleicher Weg wie die Geste.
    @Test fun decisionButtonsKeepExactlyOne() {
        build(configure = { a -> a.updateSettings { it.copy(decisionButtons = true) } })
        feed(); ready("1 von 5")
        rule.onNode(hasContentDescription("Behalten und weiter")).performClick()
        ready("2 von 5")
        assertEquals(Disposition.KEPT, rec(0).disposition)
        assertEquals(1, app.ledger.stateFlow.value.events.size)
    }

    @Test fun buttonsHiddenByDefault() {
        build(); feed(); ready("1 von 5")
        assertTrue(rule.onAllNodes(hasContentDescription("Behalten und weiter")).fetchSemanticsNodes().isEmpty())
    }

    // 3.3 Reihenfolge: nur die Abfolge ändert sich, nichts wird entschieden.
    @Test fun orderOldestFirstThenKeepHitsOldest() {
        build(); feed(); ready("1 von 5")
        rule.onNodeWithTag("feed.order").performClick()
        rule.onNodeWithTag("order.OLDEST").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.waitUntil(5_000) { app.settings.value.feedOrder == FeedOrder.OLDEST }
        assertEquals(0, app.ledger.stateFlow.value.events.size)
        rule.waitForIdle()
        ready("1 von 5")
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        ready("2 von 5")
        assertEquals(Disposition.KEPT, rec(4).disposition) // ältestes Foto
        assertEquals(Disposition.UNDECIDED, rec(0).disposition)
    }

    // 0.3 Ordner ausnehmen aus dem Feed: keine Entscheidung, Medien verschwinden aus der Warteschlange.
    @Test fun excludeFolderFromFeedDropsItsItems() {
        build(prep = { l -> l.paths[l.all[0].id] = "Pictures/Work/"; l.paths[l.all[3].id] = "Pictures/Work/Q3/" })
        feed(); ready("1 von 5")
        rule.onNodeWithTag("action.more").performClick()
        rule.onNodeWithTag("action.exclude").performClick()
        rule.onNodeWithTag("feed.exclude.ok").performClick()
        ready("1 von 3")
        assertEquals(listOf("Pictures/Work/"), app.settings.value.excludedFolders)
        assertEquals(0, app.ledger.stateFlow.value.events.size)
        // Auch Zahlen und neue Feeds ohne den Ordner (inkl. Unterordner).
        assertEquals(3, app.photos.assets(MediaCategory.PHOTOS).size)
        assertEquals(5, app.allPhotos.assets(MediaCategory.PHOTOS).size)
    }

    @Test fun excludedFoldersScreenShowsAgain() {
        build(configure = { a -> a.updateSettings { it.copy(excludedFolders = listOf("DCIM/Camera/")) } })
        assertEquals(0, app.photos.count(MediaCategory.PHOTOS))
        rule.setContent { TestHost(app) { ExcludedFoldersScreen(nav) } }
        wait("Camera")
        rule.onNodeWithTag("excl.restore").performClick()
        rule.waitUntil(5_000) { app.settings.value.excludedFolders.isEmpty() }
        assertEquals(5, app.photos.count(MediaCategory.PHOTOS))
    }

    // 1.1 / 1.4 Monate
    @Test fun monthsShowRealProgressAndOpenMonthFeed() {
        val sep = Instant.parse("2026-09-15T10:00:00Z"); val aug = Instant.parse("2026-08-10T10:00:00Z")
        build(prep = { l -> (0..2).forEach { l.takenAt[it] = sep.plusSeconds(it * 3600L) }; (3..4).forEach { l.takenAt[it] = aug } },
            configure = { a -> /* nach dem Bau gesetzt */ })
        runBlocking { app.decide(ref(0), DecisionAction.Keep) }
        rule.setContent { TestHost(app) { MonthsScreen(nav) } }
        wait("September 2026")
        rule.onNodeWithText("1 von 3 durchgesehen").assertExists()
        rule.onNodeWithText("Noch nicht begonnen · 2 Elemente").assertExists()
        rule.onNodeWithTag("month.2026-09").performClick()
        val r = nav.requests.last().source as FeedRequest.Source.Month
        assertEquals(MonthKey(2026, 9), r.month)
        assertTrue(!r.again)
    }

    private val aug = Instant.parse("2026-08-10T10:00:00Z")

    @Test fun monthFeedShowsOnlyOpenOfThatMonthAndRemembersIt() {
        val sep = Instant.parse("2026-09-15T10:00:00Z")
        build(prep = { l -> (0..2).forEach { l.takenAt[it] = sep.plusSeconds(it * 3600L) }; (3..4).forEach { l.takenAt[it] = aug } })
        runBlocking { app.decide(ref(1), DecisionAction.Keep) }
        feed(FeedRequest.Source.Month(MonthKey(2026, 9)))
        ready("1 von 2")
        rule.waitUntil(5_000) { app.settings.value.continueMonth == "2026-09" }
    }

    @Test fun monthAgainIncludesDecided() {
        val sep = Instant.parse("2026-09-15T10:00:00Z")
        build(prep = { l -> (0..2).forEach { l.takenAt[it] = sep.plusSeconds(it * 3600L) }; (3..4).forEach { l.takenAt[it] = aug } })
        runBlocking { app.decide(ref(1), DecisionAction.Keep) }
        feed(FeedRequest.Source.Month(MonthKey(2026, 9), again = true))
        ready("1 von 3")
    }

    // 1.5 Später ohne Termin
    @Test fun laterWithoutDateIsBookmarkedAndListed() {
        build()
        var showLater by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent { TestHost(app) { if (showLater) LaterScreen(nav) else ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, onClose = {}, openBasket = {}) } }
        ready("1 von 5")
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeRight() }
        ready("2 von 5")
        waitTag("later.edit")
        rule.onNodeWithTag("later.edit").performClick()
        waitTag("later.nodate")
        rule.onNodeWithTag("later.nodate").performClick()
        rule.waitUntil(5_000) { rec(0).bookmarked }
        assertEquals(Disposition.LATER, rec(0).disposition)
        assertEquals(null, rec(0).dueDay)
        showLater = true
        wait("Beiseitegelegt · 1")
        rule.onNodeWithText("Beiseitegelegt, ohne Termin").assertExists()
    }

    // 2 Serien
    private fun seriesLib(l: FakeMediaLibrary) {
        val t = Instant.parse("2026-09-30T09:00:00Z") // neueste Medien = Serie (steht im Feed vorne)
        (0..2).forEach { l.takenAt[it] = t.minusSeconds(it * 3L) }
    }

    @Test fun seriesButtonIsProForFree() {
        build(purchase = PurchaseState.NOT_PURCHASED, prep = ::seriesLib)
        feed(); ready("1 von 5")
        waitTag("action.series")
        rule.onNodeWithTag("action.series").performClick()
        rule.waitForIdle()
        assertTrue("pro" in nav.events)
        assertTrue(rule.onAllNodesWithTag("series").fetchSemanticsNodes().isEmpty())
    }

    @Test fun seriesCompareDecidesOnlyAfterConfirm() {
        build(purchase = PurchaseState.PURCHASED, prep = ::seriesLib)
        feed(); ready("1 von 5")
        waitTag("action.series")
        rule.onNodeWithTag("action.series").performClick()
        waitTag("series")
        // Haken bei einer Kachel entfernen: noch keine Entscheidung.
        rule.onAllNodesWithTag("series.check")[1].performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(0, app.ledger.stateFlow.value.events.size)
        rule.onNodeWithTag("series.confirm").performSemanticsAction(SemanticsActions.OnClick)
        // Drei Ledger-Befehle nacheinander (IO + Main): Hauptschleife aktiv abarbeiten lassen.
        repeat(100) { if (app.ledger.stateFlow.value.events.size < 3) { rule.waitForIdle(); Thread.sleep(50) } }
        assertEquals(3, app.ledger.stateFlow.value.events.size)
        val ds = (0..2).map { rec(it).disposition }
        assertEquals(2, ds.count { it == Disposition.KEPT })
        assertEquals(1, ds.count { it == Disposition.MARKED_FOR_CLEANUP })
        assertEquals(0, lib.removed.size) // nichts gelöscht
        ready("1 von 2") // Serie verlässt die Warteschlange
    }

    // 3.2 Raster: mehrere auswählen und verschieben – keine Entscheidung
    @Test fun gridMovesSelectionWithoutDecisions() {
        build()
        rule.setContent { TestHost(app) { MediaGridScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, onClose = {}) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("grid.tile").fetchSemanticsNodes().size >= 3 }
        rule.onAllNodesWithTag("grid.tile")[0].performClick()
        rule.onAllNodesWithTag("grid.tile")[1].performClick()
        wait("2 ausgewählt")
        rule.onNodeWithTag("grid.move").performClick()
        wait("Urlaub 2026")
        rule.onNodeWithText("Urlaub 2026").performClick()
        rule.waitUntil(5_000) { lib.paths.values.count { it == "Pictures/Urlaub 2026/" } == 2 }
        wait("2 Elemente nach „Urlaub 2026“ verschoben")
        assertEquals(0, app.ledger.stateFlow.value.events.size)
        assertEquals(listOf("Pictures/Urlaub 2026/"), app.settings.value.recentFolders)
    }

    // 3.1 Ordnerziel schneller
    @Test fun pinnedFolderAppearsFirst() {
        build(); feed(); ready("1 von 5")
        rule.onNodeWithTag("action.folder").performClick()
        wait("Urlaub 2026")
        rule.onAllNodesWithTag("folder.pin")[0].performClick()
        rule.waitUntil(5_000) { app.settings.value.pinnedFolders.isNotEmpty() }
        wait("Angepinnt")
    }

    // 1.2 / 1.3 Heute: Weitermachen + Neu seit dem letzten Aufräumen
    @Test fun todayShowsContinueAndNewSince() {
        val since = Instant.parse("2026-09-25T00:00:00Z")
        build(prep = { l -> l.takenAt[0] = Instant.parse("2026-09-20T10:00:00Z"); l.addedAt[0] = Instant.parse("2026-09-30T10:00:00Z") },
            configure = { a -> a.updateSettings { it.copy(continueMonth = "2026-09", lastSessionEndMs = since.toEpochMilli()) } })
        rule.setContent { TestHost(app) { TodayScreen(nav) } }
        waitTag("today.continue")
        rule.onNodeWithText("Weitermachen: September 2026").assertExists()
        waitTag("today.new")
        rule.onNodeWithTag("today.new").performClick()
        assertTrue(nav.requests.last().source is FeedRequest.Source.NewSince)
    }
}
