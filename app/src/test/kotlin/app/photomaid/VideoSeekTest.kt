package app.photomaid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.media.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Gerätebefund 2026-09-30 (HOCH): Ziehen auf der Fortschrittsleiste löste „Später“ aus.
 * Nachweis ohne Commit (Video-Commits hängen nur unter Robolectric an ExoPlayer): Während des Spulens darf die
 * Feed-Geste nie eine Richtung einrasten – keine Richtungspille, keine Entscheidung.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoSeekTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun open(): AppModel {
        val app = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 2, videos = 3) })
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.VIDEOS)), RecordingNav(), onClose = {}, openBasket = {}) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("feed.media.ready").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("video.seek").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        return app
    }
    private fun directionShown() = rule.onAllNodesWithTag("feed.direction").fetchSemanticsNodes().isNotEmpty()

    @Test fun draggingSeekBarNeverLocksADirection() {
        val app = open()
        for (toRight in listOf(true, false)) {
            rule.onNodeWithTag("video.seek").performTouchInput {
                val a = Offset(if (toRight) left + 8f else right - 8f, centerY)
                val b = Offset(if (toRight) right - 8f else left + 8f, centerY)
                down(a); repeat(10) { i -> moveTo(a + (b - a) * ((i + 1) / 10f)) }
            }
            rule.waitForIdle()
            assertTrue("Richtung beim Spulen eingerastet", !directionShown())
            rule.onNodeWithTag("video.seek").performTouchInput { up() }
            rule.waitForIdle()
        }
        assertEquals(0, app.ledger.stateFlow.value.events.size)
        assertEquals(1, rule.onAllNodes(hasText("1 von 3")).fetchSemanticsNodes().size)
    }

    /** Gegenprobe: dieselbe Bewegung auf dem Video selbst rastet als Richtung ein (Wisch bleibt möglich). */
    @Test fun sameDragOnVideoStillLocksDirection() {
        open()
        rule.onNodeWithTag("feed.stage").performTouchInput {
            val a = Offset(centerX - 150f, centerY); val b = Offset(centerX + 150f, centerY)
            down(a); repeat(10) { i -> moveTo(a + (b - a) * ((i + 1) / 10f)) }
        }
        rule.waitForIdle()
        assertTrue("Wisch auf dem Video muss eine Richtung zeigen", directionShown())
        rule.onNodeWithTag("feed.stage").performTouchInput { cancel() }
    }
}
