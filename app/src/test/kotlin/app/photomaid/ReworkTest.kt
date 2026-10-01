package app.photomaid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.collections.CollectionFilter
import app.photomaid.core.collections.CollectionSource
import app.photomaid.core.collections.SmartCollection
import app.photomaid.design.AppBackground
import app.photomaid.feature.collections.CollectionsContent
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.feature.review.feedGestures
import app.photomaid.navigation.RootContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Umbau 2026-09-29: Tipp-Steuerung Video, Sammlungen statt Regeln, Tabbar nur auf Tab-Startseiten. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReworkTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun waitText(t: String, ms: Long = 10_000) = rule.waitUntil(ms) { rule.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty() }

    private var taps = 0; private var doubleTaps = 0; private var ends = 0

    @Composable
    private fun Stage() {
        Box(Modifier.fillMaxSize().testTag("stage").feedGestures(true, LocalDensity.current,
            onDrag = {}, onEnd = { _, _ -> ends++ }, onCancel = {}, onZoom = {}, onDoubleTap = { doubleTaps++ }, onTap = { taps++ }))
    }

    @Test fun singleTapFiresOnceAfterDelay() {
        rule.setContent { Stage() }
        rule.onNodeWithTag("stage").performTouchInput { click(center) }
        rule.mainClock.advanceTimeBy(400); rule.waitForIdle()
        assertEquals(1, taps); assertEquals(0, doubleTaps)
    }

    @Test fun doubleTapIsNotATap() {
        rule.setContent { Stage() }
        rule.onNodeWithTag("stage").performTouchInput { doubleClick(center) }
        rule.mainClock.advanceTimeBy(400); rule.waitForIdle()
        assertEquals(0, taps); assertEquals(1, doubleTaps)
    }

    @Test fun swipeIsNotATap() {
        rule.setContent { Stage() }
        rule.onNodeWithTag("stage").performTouchInput { swipeUp() }
        rule.mainClock.advanceTimeBy(400); rule.waitForIdle()
        assertEquals(0, taps); assertEquals(1, ends)
    }

    @Test fun freeUserSeesBuiltInsAndCustomLeadsToPro() {
        val app = makeTestApp(ctx)
        val nav = RecordingNav()
        rule.setContent { TestHost(app) { AppBackground { CollectionsContent(nav) } } }
        waitText("Videos größer als 100 MB")
        rule.onNodeWithText("Screenshots älter als 30 Tage").assertExists()
        rule.onNodeWithTag("collections.add").performClick()
        assertEquals("pro", nav.events.last())
    }

    @Test fun proUserCreatesCustomCollection() {
        val app = makeTestApp(ctx, purchase = FakePurchaseGateway(PurchaseState.PURCHASED))
        rule.setContent { TestHost(app) { AppBackground { CollectionsContent(RecordingNav()) } } }
        rule.onNodeWithTag("collections.add").performClick()
        waitText("Sammlung erstellen")
        rule.onNodeWithText("Speichern").performClick()
        rule.waitUntil(5_000) { app.collections.custom.value.size == 1 }
        waitText("Deine Sammlungen")
    }

    /** Feed einer Sammlung = genau die passenden, noch offenen Medien (Fake: 2,6 / 2,7 / 2,8 MB > 2,5 MB). */
    @Test fun collectionFeedShowsMatchingItems() {
        val app = makeTestApp(ctx)
        val col = SmartCollection(source = CollectionSource.PHOTOS, filter = CollectionFilter.LargerThan(2_500_000))
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Collection(col)), RecordingNav(), {}, {}) } }
        waitText("1 von 3")
    }

    @Test fun tabBarOnlyOnTabRoots() {
        val app = makeTestApp(ctx)
        rule.setContent { TestHost(app) { RootContent(app) } }
        waitText("Aufräumen")
        rule.onNodeWithText("Aufräumen").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("hub.large")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("hub.large").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) // ohne Trefferprüfung (kleines Testfenster)
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Verlauf")).fetchSemanticsNodes().isEmpty() }
    }
}
