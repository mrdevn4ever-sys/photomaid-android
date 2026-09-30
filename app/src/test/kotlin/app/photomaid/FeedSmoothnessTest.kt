package app.photomaid

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.model.ItemReference
import app.photomaid.feature.cleanup.CleanupBasketScreen
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.media.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Flüssigkeit: nächstes Medium liegt vorbereitet darunter; lange Listen komponieren nur sichtbare Zeilen. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedSmoothnessTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var lib: FakeMediaLibrary

    private fun has(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun await(pos: String) = rule.waitUntil(10_000) {
        has("feed.media.ready") && rule.onAllNodes(hasText(pos)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun nextMediumWaitsUnderneathAndBecomesCurrentWithoutExtraDecision() {
        val app = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 3, videos = 0) })
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), RecordingNav(), onClose = {}, openBasket = {}) } }
        await("1 von 3")
        assertTrue(has("feed.media.next")) // vorbereitet, aber unsichtbar und ohne Bedienung
        assertEquals(1, rule.onAllNodesWithTag("feed.media.ready").fetchSemanticsNodes().size) // nur eine bedienbare Karte
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        await("2 von 3")
        assertEquals(1, app.ledger.stateFlow.value.events.size)
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        await("3 von 3")
        rule.waitForIdle()
        assertTrue(!has("feed.media.next")) // letztes Medium: nichts darunter
        assertEquals(2, app.ledger.stateFlow.value.events.size)
    }

    @Test fun largeBasketComposesOnlyVisibleRows() {
        val app = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 300, videos = 0).also { l -> lib = l } }) { a ->
            lib.all.forEach { m ->
                val ref = ItemReference.photo(m.id)
                a.ledger.apply(DecisionCommand(item = ref, expectedRevision = a.ledger.record(ref).revision, action = DecisionAction.MarkForCleanup))
            }
        }
        rule.setContent { TestHost(app) { CleanupBasketScreen(RecordingNav(), showsDone = true) } }
        rule.waitUntil(10_000) { has("basket.thumb") }
        val composed = rule.onAllNodesWithTag("basket.thumb").fetchSemanticsNodes().size
        assertEquals(300, app.read.value.basket.size)
        assertTrue("composed=$composed", composed in 1..40)
    }
}
