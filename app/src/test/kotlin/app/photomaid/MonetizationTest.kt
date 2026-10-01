package app.photomaid

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.ads.RewardedAdResult
import app.photomaid.core.ads.FeedAdPolicy
import app.photomaid.core.ads.FeedAdState
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.capabilities.RewardLedger
import app.photomaid.core.capabilities.RewardWindow
import app.photomaid.core.cleanup.CleanupOperationKind
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.LocalDay
import app.photomaid.feature.cleanup.CleanupBasketScreen
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.media.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneId

/** Werbung mit wenig Nerven: Video-Bonus „+20 heute“ im Limit-Blatt, Anzeige unter der Zusammenfassung. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonetizationTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val nav = RecordingNav()
    private lateinit var lib: FakeMediaLibrary

    private suspend fun AppModel.mark(ref: ItemReference) =
        ledger.apply(DecisionCommand(item = ref, expectedRevision = ledger.record(ref).revision, action = DecisionAction.MarkForCleanup))

    private fun has(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun shown(t: String): Boolean = rule.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(tag: String) = rule.waitUntil(10_000) { has(tag) }

    /** 18 von 20 heute schon entfernt, 4 im Papierkorb → Limit-Blatt. */
    private fun limitApp(vararg results: RewardedAdResult) =
        makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 22, videos = 0).also { l -> lib = l } },
            purchase = FakePurchaseGateway(PurchaseState.NOT_PURCHASED), ads = FakeRewardedAdGateway(*results)) { a ->
            val refs = lib.all.map { ItemReference.photo(it.id) }
            refs.take(18).forEach { a.mark(it) }
            a.runCleanup(refs.take(18), CleanupOperationKind.PHOTO_LIBRARY_DELETE)
            refs.drop(18).forEach { a.mark(it) }
        }

    private fun openLimitSheet(app: AppModel) {
        rule.setContent { TestHost(app) { CleanupBasketScreen(nav, showsDone = true) } }
        rule.waitUntil(10_000) { shown("Papierkorb leeren: 4 Fotos/Videos") }
        rule.onNodeWithText("Papierkorb leeren: 4 Fotos/Videos").performClick()
        waitTag("basket.limit.bonus")
    }

    @Test fun bonusVideoAddsTwentyForTodayAndContinuesToConfirmation() {
        val app = limitApp(RewardedAdResult.Rewarded("bonus-1"))
        openLimitSheet(app)
        rule.onNodeWithText("Video ansehen: +20 für heute").assertExists()
        rule.onNodeWithTag("basket.limit.pro").assertExists() // Pro bleibt primär
        rule.onNodeWithTag("basket.limit.bonus").performClick()
        // Danach direkt die Android-Bestätigung für alle 4 – 22 von 40 frei.
        rule.waitUntil(10_000) { shown("4 über Android entfernen") }
        assertEquals(40, app.read.value.allowance?.limit)
        assertEquals(22, app.read.value.allowance?.available)
        assertFalse(app.entitlements.canOfferLimitBonus) // 1× täglich
        assertEquals(4, app.read.value.basket.size) // nichts ohne Bestätigung entfernt
    }

    @Test fun closedBonusVideoGrantsNothing() {
        val app = limitApp(RewardedAdResult.DismissedWithoutReward)
        openLimitSheet(app)
        rule.onNodeWithTag("basket.limit.bonus").performClick()
        rule.waitUntil(10_000) { shown("Die Werbung wurde vorzeitig geschlossen. Es wurde nichts freigeschaltet – du kannst es heute noch einmal versuchen.") }
        assertEquals(20, app.read.value.allowance?.limit)
        assertTrue(app.entitlements.canOfferLimitBonus) // Tag nicht verbraucht
    }

    @Test fun lifetimeProIsNeverOfferedTheBonus() {
        val app = makeTestApp(ctx, purchase = FakePurchaseGateway(PurchaseState.PURCHASED), ads = FakeRewardedAdGateway())
        assertFalse(app.entitlements.canOfferLimitBonus)
    }

    // Anzeige unter der Zusammenfassung

    private val feedAds = FakeFeedAds()

    private fun summaryApp(purchase: PurchaseState = PurchaseState.NOT_PURCHASED, offerShown: Boolean = true, decisions: Int = 40, reward: Boolean = false, ads: FakeFeedAds = feedAds) =
        makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 2, videos = 0) }, purchase = FakePurchaseGateway(purchase), feedAds = ads) { a ->
            a.store.setValue("feedads.v1", FeedAdState(decisionsTotal = decisions, sinceLastAd = 0)); a.feedAdCounter.load()
            a.updateSettings { it.copy(proOfferShown = offerShown) }
            if (reward) {
                val at = Instant.now().minusSeconds(60)
                a.store.setValue("rewards.ledger", RewardLedger(listOf(RewardWindow("r", at, LocalDay.of(at, ZoneId.systemDefault()))))); a.entitlements.load()
            }
        }

    private fun finishFeed(app: AppModel) {
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, onClose = {}, openBasket = {}) } }
        for (i in 1..2) {
            rule.waitUntil(10_000) { has("feed.media.ready") && shown("$i von 2") }
            rule.onNodeWithTag("feed.stage").performTouchInput { swipeLeft() }
        }
        rule.waitUntil(10_000) { shown("Durchgang abgeschlossen") }
        rule.waitForIdle()
    }

    @Test fun summaryShowsOneLabelledAdForFreeAfterWarmup() {
        val app = summaryApp()
        finishFeed(app)
        waitTag("summary.ad")
        assertTrue(shown("Anzeige")); assertTrue(shown("Testanzeige 1"))
        assertEquals(2, app.ledger.stateFlow.value.events.size) // Anzeige ist keine Entscheidung
        assertEquals(1, FeedAdPolicy.shownToday(app.feedAdCounter.state, app.today)) // zählt zur Tagesgrenze
        rule.onNodeWithTag("summary.ad.pro").assertExists()
    }

    @Test fun noSummaryAdDuringWarmup() {
        finishFeed(summaryApp(decisions = 0))
        assertFalse(has("summary.ad"))
    }

    @Test fun noSummaryAdNextToTheStrongProOffer() {
        finishFeed(summaryApp(offerShown = false))
        waitTag("summary.offer")
        assertFalse(has("summary.ad"))
    }

    @Test fun noSummaryAdForLifetimeOrProTest() {
        finishFeed(summaryApp(reward = true))
        assertFalse(has("summary.ad"))
        assertEquals(0, feedAds.taken)
    }

    /** Gerätebefund Lauf 6: kurzer Durchgang, Vorladen noch nicht fertig → die Anzeige erscheint, sobald sie geladen ist. */
    @Test fun summaryAdAppearsWhenItFinishesLoadingLate() {
        val late = FakeFeedAds(emptyTakes = 4)
        val app = summaryApp(ads = late)
        finishFeed(app)
        waitTag("summary.ad")
        assertEquals(1, late.taken)
        assertEquals(1, FeedAdPolicy.shownToday(app.feedAdCounter.state, app.today))
    }
}

