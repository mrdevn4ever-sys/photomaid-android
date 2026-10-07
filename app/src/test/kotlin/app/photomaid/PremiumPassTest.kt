package app.photomaid

import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.feature.history.HistoryScreen
import app.photomaid.feature.later.LaterScreen
import app.photomaid.feature.review.FeedHaptic
import app.photomaid.feature.review.FeedViewModel
import app.photomaid.feature.review.PreviewState
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.media.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Premium-Pass: Schwellen-Rückmeldung, Problemzustände, Erstnutzung der Gestenhilfe, leere Zustände. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PremiumPassTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun waitText(t: String, ms: Long = 30_000) = rule.waitUntil(ms) { rule.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty() }

    private fun loadedModel(app: AppModel): FeedViewModel {
        val vm = FeedViewModel(app, FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)))
        val end = System.currentTimeMillis() + 10_000
        while (!vm.isLoaded) { shadowOf(Looper.getMainLooper()).idle(); check(System.currentTimeMillis() < end); Thread.sleep(10) }
        return vm
    }

    /** Die Schwelle wird genau einmal fühlbar gemeldet; kurze Bewegungen melden nichts, „runter“ nie. */
    @Test fun readyHapticOncePerThreshold() {
        val app = makeTestApp(ctx)
        val vm = loadedModel(app)
        vm.dragChanged(Offset(0f, -40f))
        assertNull(vm.readyDirection); assertNull(vm.haptic)
        vm.dragChanged(Offset(0f, -130f))
        assertEquals(FeedHaptic.READY, vm.haptic?.second)
        val first = vm.haptic?.first
        vm.dragChanged(Offset(0f, -180f))
        assertEquals(first, vm.haptic?.first) // keine Vibration bei jedem Pixel
        vm.dragCancelled()
        assertNull(vm.readyDirection)
        vm.dragChanged(Offset(0f, 200f)) // runter = keine Entscheidung → kein Signal
        assertNull(vm.readyDirection)
    }

    @Test fun retryPreviewReloadsWithoutDecision() {
        val app = makeTestApp(ctx)
        val vm = loadedModel(app)
        val item = vm.session.items.first()
        vm.previewResult(item, false)
        assertEquals(PreviewState.FAILED, vm.previews[item])
        vm.retryPreview(item)
        assertEquals(PreviewState.LOADING, vm.previews[item])
        assertEquals(1, vm.retries[item])
        assertTrue(app.ledger.stateFlow.value.events.isEmpty())
    }

    /** Nicht ladbares Medium: ruhiger Problemzustand mit Ausweg, „Weiter“ entscheidet nichts. */
    @Test fun brokenMediaOffersRetryAndSkip() {
        lateinit var lib: FakeMediaLibrary
        val app = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 3, videos = 0).also { l -> lib = l } })
        File(lib.all[0].id.removePrefix("file://")).delete()
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), RecordingNav(), {}, {}) } }
        waitText("Dieses Foto oder Video lässt sich gerade nicht anzeigen.")
        rule.onNodeWithText("Erneut versuchen").assertExists()
        // Prüflog #3: beide Tasten haben echte Höhe (früher auf kleinen Bildschirmen auf 0 dp gequetscht).
        assertTrue(rule.onNodeWithText("Ohne Auswahl weiter").fetchSemanticsNode().boundsInRoot.height > 0f)
        rule.onNodeWithText("Ohne Auswahl weiter").performClick()
        waitText("2 von 3")
        assertTrue(app.ledger.stateFlow.value.events.isEmpty())
    }

    @Test fun thresholdShowsIntentFrameWhileDragging() {
        val app = makeTestApp(ctx)
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), RecordingNav(), {}, {}) } }
        rule.waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("feed.media.ready")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("feed.stage").performTouchInput { down(center); moveBy(Offset(0f, -30f)) }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("feed.media.intent")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("feed.stage").performTouchInput { moveBy(Offset(0f, -900f)) }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("feed.media.intent")).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("feed.stage").performTouchInput { up() }
    }

    /** Dezente Pille beim Wischen: links = „Papierkorb“; runter zeigt keine Pille. */
    @Test fun directionPillWhileDragging() {
        val app = makeTestApp(ctx)
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), RecordingNav(), {}, {}) } }
        rule.waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("feed.media.ready")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("feed.stage").performTouchInput { down(center); moveBy(Offset(-200f, 0f)) }
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("feed.direction")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("feed.stage").performTouchInput { moveBy(Offset(200f, 0f)); moveBy(Offset(0f, 200f)) }
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("feed.direction")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("feed.stage").performTouchInput { up() }
    }

    @Test fun gestureHintShownOnFirstFeedOnly() {
        val app = makeTestApp(ctx) { a -> a.updateSettings { it.copy(gestureHintSeen = false) } }
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), RecordingNav(), {}, {}) } }
        rule.waitUntil(60_000) { app.settings.value.gestureHintSeen } // unter Last der ganzen Suite einmal länger als 30 s
        assertEquals(false, app.settings.value.showGestureHintOnOpen)
    }

    @Test fun calmEmptyStates() {
        val app = makeTestApp(ctx)
        rule.setContent { TestHost(app) { LaterScreen(RecordingNav()) } }
        waitText("Nichts für später gespeichert")
        rule.onNodeWithText("Hier findest du Fotos, Videos und Dateien, die du für später gespeichert hast.").assertExists()
    }

    @Test fun historyEmptyState() {
        val app = makeTestApp(ctx)
        rule.setContent { TestHost(app) { HistoryScreen(RecordingNav()) } }
        waitText("Noch kein Verlauf")
    }
}
