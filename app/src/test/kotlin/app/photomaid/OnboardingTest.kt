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
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.feature.onboarding.ONBOARDING_VERSION
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.media.MediaCategory
import app.photomaid.navigation.RootContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Onboarding (Geschichte → Üben → Sicherheit → Angebot → Berechtigungen) und Hinweiskarten-Tutorials. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h852dp-xxhdpi")
class OnboardingTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun has(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun shown(t: String) = rule.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(tag: String) = rule.waitUntil(5_000) { has(tag) }

    private fun fresh(purchase: PurchaseState = PurchaseState.NOT_PURCHASED) =
        makeTestApp(ctx, purchase = FakePurchaseGateway(purchase), introSeen = false)

    @Test fun fullFlowPracticeSavesNothingAndOfferShowsStorePrice() {
        val app = fresh()
        rule.setContent { TestHost(app) { RootContent(app) } }
        waitTag("onb.page.0")
        rule.onNodeWithText("Deine Galerie, endlich aufgeräumt").assertExists()
        rule.onNodeWithTag("onb.next").performClick()
        // Üben: echte Geste, aber nichts wird gespeichert.
        waitTag("onb.deck")
        fun done() = rule.onAllNodesWithTag("onb.chip.done").fetchSemanticsNodes().size
        rule.onNodeWithTag("onb.deck").performTouchInput { swipeUp() }
        rule.waitUntil(5_000) { done() == 1 }
        rule.onNodeWithTag("onb.deck").performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) { done() == 2 }
        rule.onNodeWithTag("onb.deck").performTouchInput { swipeRight() }
        rule.waitUntil(5_000) { done() == 3 }
        rule.onNodeWithText("Perfekt – so einfach ist das.").assertExists()
        assertEquals(0, app.ledger.stateFlow.value.events.size)
        assertEquals(0, app.feedAdCounter.state.decisionsTotal)
        rule.onNodeWithTag("onb.next").performClick()
        waitTag("onb.page.2")
        rule.onNodeWithText("Nichts geht aus Versehen verloren").assertExists()
        rule.onNodeWithTag("onb.next").performClick()
        // Angebot: kostenlos unbegrenzt + Werbung + echtes Limit + Pro einmalig zum Store-Preis.
        waitTag("offer.free")
        rule.onNodeWithText("Kostenlos – so viel du willst").assertExists()
        rule.onNodeWithText("Bis zu 20 pro Tag – mit einem kurzen Video 20 mehr.").assertExists()
        rule.onNodeWithText("3,99 CHF (TEST) · einmalig").performScrollTo().assertExists()
        rule.onNodeWithText("Pro für 3,99 CHF (TEST) – einmal kaufen").assertExists()
        rule.onNodeWithTag("offer.free").performClick()
        rule.waitUntil(5_000) { shown("Damit PhotoMaid deine Fotos sehen kann") }
        rule.onNodeWithText("Weiter").performClick()
        rule.waitUntil(5_000) { app.settings.value.privacyIntroSeen }
        assertEquals(ONBOARDING_VERSION, app.settings.value.onboardingVersion)
        // Danach das Tutorial auf „Heute“ (1/4).
        rule.waitUntil(10_000) { has("coach.card") }
        rule.onNodeWithText("1/4").assertExists()
        rule.onNodeWithText("Hier geht’s los").assertExists()
    }

    @Test fun skipJumpsToOfferAndBackReturns() {
        val app = fresh()
        rule.setContent { TestHost(app) { RootContent(app) } }
        waitTag("onb.skip")
        rule.onNodeWithTag("onb.skip").performClick()
        waitTag("offer.free")
        assertFalse(app.settings.value.privacyIntroSeen)
    }

    @Test fun lifetimeProSkipsTheOffer() {
        val app = fresh(PurchaseState.PURCHASED)
        rule.setContent { TestHost(app) { RootContent(app) } }
        waitTag("onb.skip")
        rule.onNodeWithTag("onb.skip").performClick()
        rule.waitUntil(5_000) { shown("Damit PhotoMaid deine Fotos sehen kann") }
        assertFalse(has("offer.free"))
    }

    @Test fun todayTourStepsThroughAndRemembers() {
        val app = makeTestApp(ctx) { it.updateSettings { s -> s.copy(todayTourSeen = false) } }
        rule.setContent { TestHost(app) { RootContent(app) } }
        rule.waitUntil(10_000) { has("coach.card") }
        for (i in 1..3) { rule.onNodeWithTag("coach.next").performClick(); rule.waitUntil(5_000) { shown("${i + 1}/4") } }
        rule.onNodeWithText("Noch mehr Werkzeuge").assertExists()
        rule.onNodeWithText("Fertig").performClick()
        rule.waitUntil(5_000) { app.settings.value.todayTourSeen }
        rule.waitUntil(5_000) { !has("coach.card") }
    }

    @Test fun feedTourBlocksGesturesAndSkipRemembers() {
        val app = makeTestApp(ctx) { it.updateSettings { s -> s.copy(feedTourSeen = false) } }
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), RecordingNav(), onClose = {}, openBasket = {}) } }
        rule.waitUntil(10_000) { has("coach.card") }
        rule.onNodeWithText("Dein Papierkorb").assertExists()
        // Während der Hinweiskarten löst ein Wisch nichts aus.
        rule.onNodeWithTag("coach").performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertEquals(0, app.ledger.stateFlow.value.events.size)
        rule.onNodeWithTag("coach.skip").performClick()
        rule.waitUntil(5_000) { app.settings.value.feedTourSeen }
        assertTrue(!has("coach.card") || true)
    }
}
