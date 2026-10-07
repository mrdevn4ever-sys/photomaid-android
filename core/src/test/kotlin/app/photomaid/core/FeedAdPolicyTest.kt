package app.photomaid.core

import app.photomaid.core.ads.FeedAdPolicy
import app.photomaid.core.ads.FeedAdState
import app.photomaid.core.model.LocalDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedAdPolicyTest {
    private val d1 = LocalDay(2026, 9, 30)
    private val d2 = LocalDay(2026, 10, 1)

    private fun decide(s: FeedAdState, n: Int) = (1..n).fold(s) { acc, _ -> FeedAdPolicy.onDecision(acc) }

    @Test fun warmupThenEverySeventh() {
        var s = decide(FeedAdState(), 29)
        assertFalse(FeedAdPolicy.isDue(s, d1)) // Schonfrist
        s = decide(s, 1)
        assertTrue(FeedAdPolicy.isDue(s, d1)) // 30 erreicht, seit Start > 7
        s = FeedAdPolicy.onShown(s, d1)
        assertFalse(FeedAdPolicy.isDue(decide(s, 6), d1))
        assertTrue(FeedAdPolicy.isDue(decide(s, 7), d1))
    }

    @Test fun dailyCapResetsNextDay() {
        var s = decide(FeedAdState(), 30)
        repeat(FeedAdPolicy.DAILY_CAP) { s = FeedAdPolicy.onShown(decide(s, 7), d1) }
        s = decide(s, 7)
        assertFalse(FeedAdPolicy.isDue(s, d1))
        assertEquals(FeedAdPolicy.DAILY_CAP, FeedAdPolicy.shownToday(s, d1))
        assertTrue(FeedAdPolicy.isDue(s, d2))
        assertEquals(1, FeedAdPolicy.shownToday(FeedAdPolicy.onShown(s, d2), d2))
    }

    @Test fun constants() {
        assertEquals(7, FeedAdPolicy.INTERVAL); assertEquals(30, FeedAdPolicy.WARMUP_DECISIONS); assertEquals(30, FeedAdPolicy.DAILY_CAP)
    }
}
