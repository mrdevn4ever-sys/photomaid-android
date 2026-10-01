package app.photomaid

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.ads.EntitlementCenter
import app.photomaid.ads.RewardOffer
import app.photomaid.ads.RewardedAdResult
import app.photomaid.core.capabilities.Capabilities.Tier
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.capabilities.RewardLedger
import app.photomaid.core.capabilities.RewardWindow
import app.photomaid.core.cleanup.CleanupError
import app.photomaid.core.cleanup.CleanupOperationKind
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.LocalDay
import app.photomaid.feature.cleanup.CleanupBasketScreen
import app.photomaid.feature.duplicates.DuplicatesScreen
import app.photomaid.feature.pro.ProScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneId

/** 30-Minuten-Pro-Test (Rewarded) mit Fake-Anbieter. Kein echtes Ad-SDK im Projekt. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RewardedProTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var lib: FakeMediaLibrary

    private fun waitText(t: String) = rule.waitUntil(5_000) { rule.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty() }

    private fun withReward(a: AppModel, grantedAt: Instant) {
        val ledger = RewardLedger(listOf(RewardWindow("r0", grantedAt, LocalDay.of(grantedAt, ZoneId.systemDefault()))))
        a.store.setValue("rewards.ledger", ledger)
        a.entitlements.load()
    }

    @Test fun rewardEventGrantsProToolsFor30MinutesButNotUnlimitedTrash() {
        val ads = FakeRewardedAdGateway(RewardedAdResult.Rewarded("evt-1"))
        val app = makeTestApp(ctx, purchase = FakePurchaseGateway(PurchaseState.NOT_PURCHASED), ads = ads)
        rule.setContent { TestHost(app) { ProScreen(RecordingNav()) } }
        rule.onNodeWithTag("pro.reward").performScrollTo()
        rule.onNodeWithText("Pro-Funktionen 30 Minuten testen").assertExists()
        rule.onNodeWithText("1× täglich").assertExists()
        rule.onNodeWithText("Dein Tageslimit zum Entfernen bleibt gleich.").assertExists()
        rule.onNodeWithText("Werbung ansehen").performClick()
        waitText("Pro-Funktionen sind für 30 Minuten freigeschaltet. Dein Tageslimit zum Entfernen bleibt gleich.")
        assertEquals(1, ads.shown)
        val caps = app.capabilities
        assertEquals(Tier.TEMPORARY_PRO, caps.tier)
        assertTrue(caps.hasProToolsAccess && caps.exactDuplicates && caps.customCollections)
        assertFalse(caps.hasUnlimitedTrashAccess)
        assertEquals(20, caps.dailyCleanupLimit)
        val w = app.entitlements.rewards.value.windows.single()
        assertEquals(w.grantedAt.plusSeconds(30 * 60), w.endsAt)
        rule.onNodeWithText("OK").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("pro.temporary").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Pro-Funktionen · noch 30 Min.").assertExists()
        assertTrue(rule.onAllNodesWithTag("pro.reward").fetchSemanticsNodes().isEmpty()) // kein zweites Angebot
    }

    @Test fun dismissedNoFillOrFailureGrantNothingAndDoNotUseUpTheDay() {
        for (r in listOf(RewardedAdResult.DismissedWithoutReward, RewardedAdResult.NoFill, RewardedAdResult.Failed("network"))) {
            val app = makeTestApp(ctx, purchase = FakePurchaseGateway(PurchaseState.NOT_PURCHASED), ads = FakeRewardedAdGateway(r))
            val result = runBlocking { app.entitlements.watchRewardedAd {} }
            assertEquals(r, result)
            assertEquals(Tier.FREE, app.capabilities.tier)
            assertEquals(RewardOffer.AVAILABLE, app.entitlements.rewardOffer)
            assertTrue(app.entitlements.rewards.value.windows.isEmpty())
        }
    }

    @Test fun usedTodayShowsCalmStateWithoutCountdown() {
        val app = makeTestApp(ctx, purchase = FakePurchaseGateway(PurchaseState.NOT_PURCHASED), ads = FakeRewardedAdGateway()) { a ->
            withReward(a, Instant.now().minusSeconds(40 * 60)) // heute genutzt, schon abgelaufen
        }
        assertEquals(RewardOffer.USED_TODAY, app.entitlements.rewardOffer)
        rule.setContent { TestHost(app) { ProScreen(RecordingNav()) } }
        rule.onNodeWithTag("pro.reward").performScrollTo()
        rule.onNodeWithText("Heute bereits genutzt").assertExists()
        rule.onNodeWithText("Morgen wieder verfügbar").assertExists()
        assertTrue(rule.onAllNodes(hasText("Werbung ansehen")).fetchSemanticsNodes().isEmpty())
        // Zweiter Versuch am selben Tag ist auch technisch ausgeschlossen.
        assertEquals(RewardedAdResult.NotConfigured, runBlocking { app.entitlements.watchRewardedAd {} })
    }

    /** App-Neustart / Process Kill: neu geladener Stand kennt den heutigen Reward. */
    @Test fun restartDoesNotResetDailyReward() {
        val app = makeTestApp(ctx, purchase = FakePurchaseGateway(PurchaseState.NOT_PURCHASED), ads = FakeRewardedAdGateway(RewardedAdResult.Rewarded("e1")))
        runBlocking { app.entitlements.watchRewardedAd {} }
        val restarted = EntitlementCenter(app.store, app.purchases, FakeRewardedAdGateway(RewardedAdResult.Rewarded("e2")))
        runBlocking(Dispatchers.IO) { restarted.load() }
        assertEquals(Tier.TEMPORARY_PRO, restarted.capabilities.tier)
        assertFalse(restarted.canOfferReward)
        assertEquals(RewardedAdResult.NotConfigured, runBlocking { restarted.watchRewardedAd {} })
    }

    @Test fun lifetimeSeesNoRewardAndBuyingDuringTestWins() {
        val purchase = FakePurchaseGateway(PurchaseState.NOT_PURCHASED)
        val app = makeTestApp(ctx, purchase = purchase, ads = FakeRewardedAdGateway(RewardedAdResult.Rewarded("e1")))
        runBlocking { app.entitlements.watchRewardedAd {} }
        assertEquals(Tier.TEMPORARY_PRO, app.capabilities.tier)
        purchase.state.value = PurchaseState.PURCHASED // Kauf oder Restore während des Tests
        assertEquals(Tier.LIFETIME_PRO, app.capabilities.tier)
        assertNull(app.capabilities.dailyCleanupLimit)
        assertEquals(RewardOffer.HIDDEN, app.entitlements.rewardOffer)
        rule.setContent { TestHost(app) { ProScreen(RecordingNav()) } }
        rule.onNodeWithTag("pro.lifetime").assertExists()
        assertTrue(rule.onAllNodesWithTag("pro.reward").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag("pro.temporary").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasText("Pro-Funktionen 30 Minuten testen")).fetchSemanticsNodes().isEmpty())
    }

    /** Ohne konfigurierten Werbeanbieter (Produktion heute): kein Angebot, keine tote CTA. */
    @Test fun withoutProviderNothingIsOffered() {
        val app = makeTestApp(ctx, purchase = FakePurchaseGateway(PurchaseState.NOT_PURCHASED))
        assertEquals(RewardOffer.HIDDEN, app.entitlements.rewardOffer)
        rule.setContent { TestHost(app) { ProScreen(RecordingNav()) } }
        assertTrue(rule.onAllNodesWithTag("pro.reward").fetchSemanticsNodes().isEmpty())
    }

    @Test fun expiredTestLocksProToolAgain() {
        val app = makeTestApp(ctx, purchase = FakePurchaseGateway(PurchaseState.NOT_PURCHASED), ads = FakeRewardedAdGateway()) { a ->
            withReward(a, Instant.now().minusSeconds(31 * 60))
        }
        rule.setContent { TestHost(app) { DuplicatesScreen(RecordingNav()) } }
        rule.onNodeWithText("PhotoMaid Pro ansehen").assertExists()
        assertFalse(app.capabilities.exactDuplicates)
    }

    // MARK: Kritischer Regressionstest (§48)

    private suspend fun AppModel.mark(ref: ItemReference) =
        ledger.apply(DecisionCommand(item = ref, expectedRevision = ledger.record(ref).revision, action = DecisionAction.MarkForCleanup))

    /**
     * TEMPORARY PRO HEBT DAS TÄGLICHE PAPIERKORB-LIMIT NICHT AUF:
     * Free → Limit teilweise verbraucht → echter Reward → Pro-Werkzeuge an → Limit weiter 20 → Überschreiten scheitert.
     */
    @Test fun temporaryProDoesNotLiftTrashLimit() {
        val ads = FakeRewardedAdGateway(RewardedAdResult.Rewarded("evt-limit"))
        val app = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 25, videos = 0).also { l -> lib = l } },
            purchase = FakePurchaseGateway(PurchaseState.NOT_PURCHASED), ads = ads) { a ->
            val refs = lib.all.map { ItemReference.photo(it.id) }
            refs.take(17).forEach { a.mark(it) }
            a.runCleanup(refs.take(17), CleanupOperationKind.PHOTO_LIBRARY_DELETE) // 17 von 20 verbraucht
            refs.drop(17).forEach { a.mark(it) } // 8 weitere im Papierkorb
        }
        assertEquals(RewardedAdResult.Rewarded("evt-limit"), runBlocking { app.entitlements.watchRewardedAd {} })
        runBlocking { app.reload() }
        val caps = app.capabilities
        assertEquals(Tier.TEMPORARY_PRO, caps.tier)
        assertTrue(caps.hasProToolsAccess)
        assertFalse(caps.hasUnlimitedTrashAccess)
        assertEquals(20, caps.dailyCleanupLimit)
        assertEquals(3, app.read.value.allowance?.available)
        // Überschreiten auf Modellebene: abgelehnt, nichts gelöscht.
        val rest = lib.all.drop(17).map { ItemReference.photo(it.id) }
        val r = runBlocking(Dispatchers.IO) { app.runCleanup(rest, CleanupOperationKind.PHOTO_LIBRARY_DELETE) }
        assertTrue(r is CleanupRunResult.Rejected && (r.error as CleanupError.AllowanceExceeded).available == 3)
        assertEquals(17, lib.removed.size)
        // Und in der UI greift die bestehende Limit-UX vor jeder Bestätigung.
        rule.setContent { TestHost(app) { CleanupBasketScreen(RecordingNav(), showsDone = true) } }
        rule.onNodeWithText("8 Fotos und Videos entfernen").performClick()
        waitText("Heute noch 3 frei")
    }

    @Test fun lifetimeKeepsUnlimitedTrash() {
        val app = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 25, videos = 0).also { l -> lib = l } },
            purchase = FakePurchaseGateway(PurchaseState.PURCHASED)) { a ->
            lib.all.forEach { a.mark(ItemReference.photo(it.id)) }
        }
        val r = runBlocking(Dispatchers.IO) { app.runCleanup(lib.all.map { ItemReference.photo(it.id) }, CleanupOperationKind.PHOTO_LIBRARY_DELETE) }
        assertTrue(r is CleanupRunResult.Finished)
        assertEquals(25, lib.removed.size) // mehr als das Free-Limit von 20
    }
}
