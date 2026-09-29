package app.photomaid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.decisions.HistoryKind
import app.photomaid.core.model.ItemReference
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.media.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Gesten-Tests des Feeds (Masterprompt §42, iOS `PhotoMaidUITests` „Feed-Gesten mit QA-Medien“).
 * HOCH = Behalten + nächstes, LINKS = Papierkorb + nächstes, RECHTS = Später + nächstes, RUNTER = vorheriges.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedFlowTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        app = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 5, videos = 0).also { l -> lib = l } })
        rule.setContent {
            TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), RecordingNav(), onClose = {}, openBasket = {}) }
        }
        awaitReady("1 von 5")
    }

    private fun awaitReady(position: String) {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("feed.media.ready").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodes(androidx.compose.ui.test.hasText(position)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun item(i: Int) = ItemReference.photo(lib.all[i].id)
    // Nicht blockierend lesen (runBlocking auf dem Main-Thread würde mit dem Ledger-Mutex verklemmen).
    private fun rec(i: Int) = app.ledger.stateFlow.value.records[item(i)] ?: ItemRecord(item(i))
    private fun disposition(i: Int) = rec(i).disposition
    private fun events() = app.ledger.stateFlow.value.events

    @Test fun upKeepsAndAdvancesExactlyOne() {
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        awaitReady("2 von 5")
        assertEquals(Disposition.KEPT, disposition(0))
        assertEquals(1, events().size)
    }

    @Test fun leftMarksForCleanupNothingDeleted() {
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeLeft() }
        awaitReady("2 von 5")
        assertEquals(Disposition.MARKED_FOR_CLEANUP, disposition(0))
        assertEquals(0, lib.removed.size)
        rule.onNodeWithText("Papierkorb: 1").assertExists()
    }

    @Test fun rightLaterWithDefaultDateAndPill() {
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeRight() }
        awaitReady("2 von 5")
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("later.pill").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("In 2 Tagen wieder").assertExists()
        val r = rec(0)
        assertEquals(Disposition.LATER, r.disposition)
        assertEquals(app.today.addingDays(2), r.dueDay)
    }

    @Test fun downGoesBackWithoutDecision() {
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        awaitReady("2 von 5")
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeDown() }
        awaitReady("1 von 5")
        assertEquals(1, events().size)
        assertEquals(Disposition.UNDECIDED, disposition(1))
    }

    @Test fun downAtStartIsBumpOnly() {
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeDown() }
        awaitReady("1 von 5")
        assertEquals(0, events().size)
    }

    @Test fun diagonalLeftAndShortDragDoNothing() {
        rule.onNodeWithTag("feed.stage").performTouchInput { swipe(center, center + Offset(-300f, -300f), 300) }
        rule.onNodeWithTag("feed.stage").performTouchInput { swipe(center, center + Offset(0f, -40f), 200) }
        rule.waitForIdle()
        awaitReady("1 von 5")
        assertEquals(0, events().size)
    }

    @Test fun cancelledDragReturnsWithoutDecision() {
        rule.onNodeWithTag("feed.stage").performTouchInput {
            down(center); moveBy(Offset(0f, -400f)); moveBy(Offset(0f, 395f)); up()
        }
        rule.waitForIdle()
        awaitReady("1 von 5")
        assertEquals(0, events().size)
    }

    @Test fun rapidSwipesNeverDoubleDecide() {
        repeat(3) { rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp(durationMillis = 80) } }
        rule.waitForIdle()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("feed.media.ready").fetchSemanticsNodes().isNotEmpty() }
        val ev = events()
        // Jede Geste höchstens ein Schritt; niemals zwei Entscheidungen für dasselbe Item.
        assertEquals(ev.size, ev.map { it.item }.toSet().size)
        assertEquals(ev.size, ev.count { it.kind == HistoryKind.DECISION })
        rule.onNodeWithText("${ev.size + 1} von 5").assertExists()
    }

    @Test fun rightUpOpensWheelAndCancelKeepsDefaultDate() {
        rule.onNodeWithTag("feed.stage").performTouchInput { swipe(center, center + Offset(500f, -300f), 250) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("feed.media.ready").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Bestimmtes Datum …").assertExists()
        assertEquals(Disposition.LATER, disposition(0))
        assertNull(rec(0).dueDay)
        rule.onNodeWithText("Abbrechen").performClick()
        rule.waitUntil(5_000) { rec(0).dueDay != null }
        assertEquals(app.today.addingDays(2), rec(0).dueDay)
    }

    @Test fun undoRestoresPreviousState() {
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeLeft() }
        awaitReady("2 von 5")
        rule.onNodeWithText("Papierkorb: 1").assertExists()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Rückgängig")).performClick()

        awaitReady("1 von 5")
        assertEquals(Disposition.UNDECIDED, disposition(0))
    }

    @Test fun sessionEndShowsRealSummary() {
        repeat(5) { i ->
            rule.onNodeWithTag("feed.stage").performTouchInput { if (i % 2 == 0) swipeUp() else swipeLeft() }
            if (i < 4) awaitReady("${i + 2} von 5")
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Durchgang abgeschlossen").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Papierkorb ansehen (2)").assertExists()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithText(t: String) =
    onAllNodes(androidx.compose.ui.test.hasText(t))
