package app.photomaid.core

import app.photomaid.core.feed.FeedGestureMachine
import app.photomaid.core.feed.FeedGestureMachine.Phase
import app.photomaid.core.feed.FeedGestureMachine.Resolution
import app.photomaid.core.feed.FeedIntent
import app.photomaid.core.feed.SwipeDirection
import app.photomaid.core.feed.Vector2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Port von `GestureMachineTests.swift` (Gesten-State-Machine). */
class GestureMachineTest {
    private fun swipe(m: FeedGestureMachine, t: Vector2, velocity: Vector2 = Vector2.ZERO, steps: Int = 8): Resolution {
        for (i in 1..steps) {
            val f = i.toDouble() / steps
            m.update(Vector2(t.dx * f, t.dy * f))
        }
        return m.end(t, velocity)
    }

    @Test fun directionsMatchFingerMovement() {
        for ((t, expected) in listOf(
            Vector2(0.0, -200.0) to SwipeDirection.UP, Vector2(0.0, 200.0) to SwipeDirection.DOWN,
            Vector2(-200.0, 0.0) to SwipeDirection.LEFT, Vector2(200.0, 0.0) to SwipeDirection.RIGHT,
        )) {
            val m = FeedGestureMachine()
            assertEquals(Resolution.Commit(expected), swipe(m, t))
            assertEquals(Phase.CommitRequested(expected), m.phase)
        }
    }

    @Test fun fixedMeaning() {
        assertEquals(FeedIntent.KEEP, FeedIntent.of(SwipeDirection.UP))
        assertEquals(FeedIntent.MARK_FOR_CLEANUP, FeedIntent.of(SwipeDirection.LEFT))
        assertEquals(FeedIntent.LATER, FeedIntent.of(SwipeDirection.RIGHT))
        assertEquals(FeedIntent.PREVIOUS, FeedIntent.of(SwipeDirection.DOWN))
        assertFalse(FeedIntent.PREVIOUS.isDecision)
    }

    @Test fun diagonalLeftDoesNothing() {
        val m = FeedGestureMachine()
        assertEquals(Resolution.Cancelled, swipe(m, Vector2(-150.0, -150.0)))
        assertEquals(Resolution.Cancelled, swipe(m, Vector2(-150.0, 150.0)))
        assertEquals(Phase.Idle, m.phase)
    }

    @Test fun rightVariants() {
        for ((t, expected) in listOf(
            Vector2(180.0, -130.0) to SwipeDirection.RIGHT_UP, // ≈ 36°
            Vector2(200.0, -100.0) to SwipeDirection.RIGHT_UP, // ≈ 27°
            Vector2(200.0, -60.0) to SwipeDirection.RIGHT, // ≈ 17°
            Vector2(180.0, 120.0) to SwipeDirection.RIGHT, // ≈ 34° nach unten
            Vector2(200.0, 0.0) to SwipeDirection.RIGHT,
        )) {
            assertEquals("$t", Resolution.Commit(expected), swipe(FeedGestureMachine(), t))
        }
    }

    @Test fun rightDeadZones() {
        val m = FeedGestureMachine()
        assertEquals(Resolution.Cancelled, swipe(m, Vector2(120.0, -160.0))) // ≈ 53°
        assertEquals(Resolution.Cancelled, swipe(m, Vector2(120.0, 170.0))) // ≈ 55° nach unten
        assertEquals(FeedIntent.LATER_CUSTOM, FeedIntent.of(SwipeDirection.RIGHT_UP))
    }

    @Test fun diagonalOffset() {
        val m = FeedGestureMachine()
        m.update(Vector2(90.0, -60.0))
        assertEquals(SwipeDirection.RIGHT_UP, m.lockedDirection)
        assertEquals(Vector2(90.0, -60.0), m.displayOffset)
        m.cancel()
        m.update(Vector2(90.0, 30.0))
        assertEquals(SwipeDirection.RIGHT, m.lockedDirection)
        assertEquals(Vector2(90.0, 0.0), m.displayOffset)
    }

    @Test fun tooShortIsCancelled() {
        val m = FeedGestureMachine()
        assertEquals(Resolution.Cancelled, swipe(m, Vector2(0.0, -60.0)))
        assertEquals(Phase.Idle, m.phase)
    }

    @Test fun flickSufficesWithMinimumDistance() {
        assertEquals(Resolution.Commit(SwipeDirection.LEFT), swipe(FeedGestureMachine(), Vector2(-50.0, 0.0), Vector2(-900.0, 0.0)))
    }

    @Test fun flickAgainstDirectionDoesNotCommit() {
        assertEquals(Resolution.Cancelled, swipe(FeedGestureMachine(), Vector2(-50.0, 0.0), Vector2(900.0, 0.0)))
    }

    @Test fun reversalSwitchesDirectionNotTwoActions() {
        val m = FeedGestureMachine()
        m.update(Vector2(0.0, -80.0))
        assertEquals(SwipeDirection.UP, m.lockedDirection)
        m.update(Vector2(0.0, -10.0))
        assertEquals(SwipeDirection.UP, m.lockedDirection)
        m.update(Vector2(0.0, 40.0))
        assertEquals(SwipeDirection.DOWN, m.lockedDirection)
        assertEquals(Resolution.Commit(SwipeDirection.DOWN), m.end(Vector2(0.0, 160.0), Vector2.ZERO))
    }

    @Test fun axisOffsetAfterLock() {
        val m = FeedGestureMachine()
        m.update(Vector2(8.0, -90.0))
        assertEquals(Vector2(0.0, -90.0), m.displayOffset)
    }

    @Test fun suspendedIgnoresGestures() {
        val m = FeedGestureMachine()
        m.isSuspended = true
        assertEquals(Resolution.Ignored, swipe(m, Vector2(0.0, -300.0)))
        assertEquals(Phase.Idle, m.phase)
    }

    @Test fun noDoubleCommit() {
        val m = FeedGestureMachine()
        assertEquals(Resolution.Commit(SwipeDirection.UP), swipe(m, Vector2(0.0, -200.0)))
        assertEquals(Resolution.Ignored, swipe(m, Vector2(0.0, -200.0)))
        m.finishCommit()
        assertEquals(Phase.Idle, m.phase)
    }

    @Test fun systemCancelReturnsToIdle() {
        val m = FeedGestureMachine()
        m.update(Vector2(0.0, -200.0))
        m.cancel()
        assertEquals(Phase.Idle, m.phase)
        assertEquals(Resolution.Ignored, m.end(Vector2(0.0, -200.0), Vector2.ZERO))
    }

    /** Android-Zusatz: langsamer, langer Drag nach oben committet; kurzer Drag nicht. */
    @Test fun slowLongDragCommitsShortDoesNot() {
        assertEquals(Resolution.Commit(SwipeDirection.UP), swipe(FeedGestureMachine(), Vector2(3.0, -120.0), steps = 60))
        assertEquals(Resolution.Cancelled, swipe(FeedGestureMachine(), Vector2(0.0, -30.0), Vector2(0.0, -300.0)))
    }
}
