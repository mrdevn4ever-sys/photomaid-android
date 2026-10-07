package app.photomaid

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.cleanup.CleanupOperationKind
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.model.ItemReference
import app.photomaid.feature.cleanup.CleanupBasketScreen
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.feature.today.TodayScreen
import app.photomaid.media.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Kaufmomente: Zusammenfassung, frühes Limit, Limit-Blatt mit Pro primär, leiser Hinweis auf „Heute“ ab Tag 2. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurchaseMomentsTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val nav = RecordingNav()
    private lateinit var lib: FakeMediaLibrary

    private suspend fun AppModel.mark(ref: ItemReference) =
        ledger.apply(DecisionCommand(item = ref, expectedRevision = ledger.record(ref).revision, action = DecisionAction.MarkForCleanup))

    private fun app(purchase: PurchaseState = PurchaseState.NOT_PURCHASED, count: Int = 2, configure: suspend (AppModel) -> Unit = {}) =
        makeTestApp(ctx, media = { FakeMediaLibrary(it, count = count, videos = 0).also { l -> lib = l } }, purchase = FakePurchaseGateway(purchase), configure = configure)

    private fun has(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(tag: String) = rule.waitUntil(10_000) { has(tag) }

    private fun finishFeed(app: AppModel) {
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, onClose = {}, openBasket = {}) } }
        for (i in 1..2) {
            rule.waitUntil(10_000) { has("feed.media.ready") && rule.onAllNodes(hasText("$i von 2")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("feed.stage").performTouchInput { swipeLeft() }
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Für jetzt fertig")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun firstSuccessfulSessionShowsStrongOfferOnce() {
        val app = app()
        finishFeed(app)
        waitTag("summary.offer")
        assertFalse(has("summary.upsell"))
        rule.waitUntil(5_000) { app.settings.value.proOfferShown }
        rule.onNodeWithTag("summary.pro").performScrollTo().performClick()
        assertEquals("pro", nav.events.last())
        assertEquals(2, app.read.value.basket.size) // nur Papierkorb, nichts gelöscht
    }

    @Test fun laterSessionsShowQuietLine() {
        val app = app { it.updateSettings { s -> s.copy(proOfferShown = true) } }
        finishFeed(app)
        waitTag("summary.upsell")
        rule.onNodeWithText("2 aussortiert. Mit Pro ohne Tageslimit und ohne Werbung.").assertExists()
        assertFalse(has("summary.offer"))
    }

    @Test fun lifetimeProSeesNoUpsell() {
        val app = app(purchase = PurchaseState.PURCHASED)
        finishFeed(app)
        rule.waitForIdle()
        assertFalse(has("summary.offer")); assertFalse(has("summary.upsell"))
    }

    @Test fun limitSheetMakesProPrimaryAndPartialSecondary() {
        val app = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 22, videos = 0).also { l -> lib = l } }, purchase = FakePurchaseGateway(PurchaseState.NOT_PURCHASED)) { a ->
            val refs = lib.all.map { ItemReference.photo(it.id) }
            refs.take(18).forEach { a.mark(it) }
            a.runCleanup(refs.take(18), CleanupOperationKind.PHOTO_LIBRARY_DELETE)
            refs.drop(18).forEach { a.mark(it) }
        }
        rule.setContent { TestHost(app) { CleanupBasketScreen(nav, showsDone = true) } }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("4 Fotos und Videos entfernen")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(has("basket.limit.soon")) // Limit früh sichtbar (2 von 20 frei)
        rule.onNodeWithText("4 Fotos und Videos entfernen").performClick()
        waitTag("basket.limit.pro")
        rule.onNodeWithText("Alle 4 entfernen – mit Pro").assertExists()
        rule.onNodeWithText("2 davon entfernen").assertExists()
        rule.onNodeWithTag("basket.limit.pro").performClick()
        assertEquals("pro", nav.events.last())
        assertTrue(app.read.value.basket.all { it.disposition == Disposition.MARKED_FOR_CLEANUP })
        assertEquals(4, app.read.value.basket.size)
    }

    @Test fun todayProHintOnlyFromDayTwoForFree() {
        val day1 = app { it.updateSettings { s -> s.copy(firstSeenDay = it.today) } }
        val day2 = app { it.updateSettings { s -> s.copy(firstSeenDay = it.today.addingDays(-1)) } }
        val pro = app(purchase = PurchaseState.PURCHASED) { it.updateSettings { s -> s.copy(firstSeenDay = it.today.addingDays(-3)) } }
        assertEquals(day1.today, day1.settings.value.firstSeenDay)
        val current = androidx.compose.runtime.mutableStateOf(day1)
        rule.setContent { androidx.compose.runtime.key(current.value) { TestHost(current.value) { TodayScreen(nav) } } }
        rule.waitForIdle(); assertFalse(has("today.pro"))
        current.value = day2
        waitTag("today.pro")
        current.value = pro
        rule.waitUntil(10_000) { !has("today.pro") }
    }
}
