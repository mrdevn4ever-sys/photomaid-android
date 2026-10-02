package app.photomaid

import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.ads.FeedAdState
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.capabilities.RewardLedger
import app.photomaid.core.capabilities.RewardWindow
import app.photomaid.core.model.LocalDay
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.media.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneId

/** Werbekarte alle 7 Entscheidungen (nur Free), nie eine Entscheidung, Pro und Pro-Test werbefrei. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedAdsTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val ads = FakeFeedAds()
    private val nav = RecordingNav()

    /** Zähler direkt vor der Fälligkeit: nächste Entscheidung zeigt die Karte. */
    private fun app(purchase: PurchaseState = PurchaseState.NOT_PURCHASED, state: FeedAdState = FeedAdState(29, 29), reward: Boolean = false) =
        makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 20, videos = 0) }, purchase = FakePurchaseGateway(purchase), feedAds = ads) { a ->
            a.store.setValue("feedads.v1", state); a.feedAdCounter.load()
            if (reward) {
                val at = Instant.now().minusSeconds(60)
                a.store.setValue("rewards.ledger", RewardLedger(listOf(RewardWindow("r", at, LocalDay.of(at, ZoneId.systemDefault()))))); a.entitlements.load()
            }
        }

    private fun open(app: AppModel) {
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, onClose = {}, openBasket = {}) } }
        await("1 von 20")
    }
    private fun await(pos: String) = rule.waitUntil(30_000) {
        rule.onAllNodesWithTag("feed.media.ready").fetchSemanticsNodes().isNotEmpty() && rule.onAllNodes(hasText(pos)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun adVisible() = rule.onAllNodesWithTag("feed.ad").fetchSemanticsNodes().isNotEmpty()
    private fun waitAd() = rule.waitUntil(30_000) { adVisible() }
    private fun events(app: AppModel) = app.ledger.stateFlow.value.events.size

    @Test fun cardAfterDecisionIsNotADecisionAndNeedsMinimumTime() {
        val app = app()
        open(app)
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        waitAd()
        rule.onNodeWithTag("feed.ad.label").assertExists() // klar als „Anzeige“ gekennzeichnet
        // Sofort wegwischen geht nicht (Mindestdauer).
        rule.onNodeWithTag("feed.ad").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertTrue(adVisible())
        Thread.sleep(1_700)
        rule.onNodeWithTag("feed.ad").performTouchInput { swipeLeft() }
        rule.waitUntil(30_000) { !adVisible() }
        await("2 von 20")
        assertEquals(1, events(app)) // nur die eine echte Entscheidung, das Wegwischen zählt nicht
        assertTrue(app.read.value.basket.isEmpty()) // „links“ auf der Karte legt nichts in den Papierkorb
    }

    @Test fun nextCardAfterSevenMoreDecisions() {
        val app = app()
        open(app)
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        waitAd(); Thread.sleep(1_700)
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("feed.ad.skip").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("feed.ad.skip").performClick()
        rule.waitUntil(30_000) { !adVisible() }
        // Mindestabstand 60 s seit der letzten Karte (zusätzlich zum Takt): Uhr eine Minute weiter.
        app.wallClockMs = { System.currentTimeMillis() + 61_000 }
        for (i in 2..7) { await("$i von 20"); rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }; rule.waitForIdle(); assertTrue("$i", !adVisible()) }
        await("8 von 20")
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        waitAd()
        assertEquals(2, ads.taken)
    }

    /** Schnell gewischt: Takt erreicht, aber noch keine 60 s seit der letzten Karte → keine zweite Karte. */
    @Test fun noSecondCardWithinSixtySeconds() {
        val app = app()
        open(app)
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        waitAd(); Thread.sleep(1_700)
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("feed.ad.skip").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("feed.ad.skip").performClick()
        rule.waitUntil(30_000) { !adVisible() }
        for (i in 2..8) { await("$i von 20"); rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }; rule.waitForIdle() }
        await("9 von 20")
        assertTrue(!adVisible())
        assertEquals(1, ads.taken)
    }

    @Test fun warmupKeepsFirstThirtyDecisionsAdFree() {
        val app = app(state = FeedAdState(0, 0))
        open(app)
        for (i in 1..8) { await("$i von 20"); rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() } }
        await("9 von 20")
        assertTrue(!adVisible()); assertEquals(0, ads.taken)
    }

    private fun assertNoAds(a: AppModel) {
        open(a)
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        await("2 von 20")
        assertTrue(!adVisible())
        assertEquals(0, ads.taken)
    }
    @Test fun lifetimeProSeesNoAds() = assertNoAds(app(purchase = PurchaseState.PURCHASED))
    @Test fun temporaryProSeesNoAds() = assertNoAds(app(reward = true))

    @Test fun backAndUndoSkipTheCardImmediately() {
        val app = app()
        open(app)
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        waitAd()
        rule.onNodeWithTag("feed.ad").performTouchInput { swipeDown() } // „Zurück“ überspringt sofort
        rule.waitUntil(30_000) { !adVisible() }
        await("1 von 20")
        assertEquals(1, events(app))
    }

    /** Gerätebefund E5: Die echte Anzeige (NativeAdView) nimmt Berührungen an – Wischen ÜBER der Anzeige muss trotzdem wirken. */
    @Test fun swipesStartingOnTheAdContentStillWork() {
        val app = app()
        open(app)
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        waitAd()
        // Runter mitten auf der Anzeige: sofort zurück (auch vor der Mindestdauer).
        rule.onNodeWithTag("feed.ad").performTouchInput { swipe(center, Offset(centerX, bottom - 4f), 250) }
        rule.waitUntil(30_000) { !adVisible() }
        await("1 von 20")
        assertEquals(1, events(app))
    }

    @Test fun skipSwipeOnTheAdContentAfterMinimumTime() {
        val app = app()
        open(app)
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        waitAd(); Thread.sleep(1_700); rule.mainClock.advanceTimeBy(1_700)
        rule.onNodeWithTag("feed.ad").performTouchInput { swipe(center, Offset(left + 4f, centerY), 250) }
        rule.waitUntil(30_000) { !adVisible() }
        await("2 von 20")
        assertEquals(1, events(app)) // Wegwischen ist keine Entscheidung
    }

    @Test fun proLinkOpensPro() {
        val app = app()
        open(app)
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        waitAd()
        rule.onNodeWithTag("feed.ad.pro").performClick()
        assertEquals("pro", nav.events.last())
    }
}
