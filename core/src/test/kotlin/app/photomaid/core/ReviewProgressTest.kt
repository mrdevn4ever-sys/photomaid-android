package app.photomaid.core

import app.photomaid.core.decisions.DecisionEvent
import app.photomaid.core.decisions.DecisionSnapshot
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.HistoryKind
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.model.CommandID
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.LocalDay
import app.photomaid.core.progress.AchievementID
import app.photomaid.core.progress.AchievementInput
import app.photomaid.core.progress.AchievementState
import app.photomaid.core.progress.Achievements
import app.photomaid.core.progress.GameMedal
import app.photomaid.core.progress.ReviewLevel
import app.photomaid.core.progress.ReviewProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Port von iOS ReviewProgressTests. */
class ReviewProgressTest {
    private val tz = ZoneId.of("Europe/Zurich")
    private val today = LocalDay(2026, 10, 21) // Mittwoch

    private fun item(n: Int) = ItemReference.photo("id$n")
    private fun at(day: LocalDay, hour: Int = 10): Instant = day.startOfDay(tz).plusSeconds(hour * 3600L)
    private fun decision(n: Int, day: LocalDay, to: Disposition = Disposition.KEPT, hour: Int = 10) = DecisionEvent(
        commandID = CommandID(), item = item(n), kind = HistoryKind.DECISION, from = DecisionSnapshot(Disposition.UNDECIDED),
        to = DecisionSnapshot(to, dueDay = if (to == Disposition.LATER) day.addingDays(2) else null),
        revision = 1, at = at(day, hour), sessionID = null)
    private fun undo(n: Int, day: LocalDay) = DecisionEvent(commandID = null, item = item(n), kind = HistoryKind.UNDO,
        from = DecisionSnapshot(Disposition.KEPT), to = DecisionSnapshot(Disposition.UNDECIDED), revision = 2, at = at(day, 11), sessionID = null)

    private fun progress(events: List<DecisionEvent>): ReviewProgress {
        val records = mutableMapOf<ItemReference, ItemRecord>()
        events.sortedBy { it.at }.forEach { e ->
            records[e.item] = ItemRecord(item = e.item, revision = e.revision, disposition = e.to.disposition, dueDay = e.to.dueDay, bookmarked = e.to.bookmarked)
        }
        return ReviewProgress.compute(events, records.values, today, tz)
    }
    private fun days(offsets: List<Int>) = offsets.mapIndexed { i, o -> decision(i, today.addingDays(-o)) }

    @Test fun countsKindsEquallyAndSubtractsSameDayUndo() {
        val p = progress(listOf(decision(1, today), decision(2, today, Disposition.MARKED_FOR_CLEANUP), decision(3, today, Disposition.LATER),
            decision(4, today), undo(4, today)))
        assertEquals(3, p.reviewedToday); assertEquals(3, p.totalReviewed)
    }

    @Test fun streakAndPauseDay() {
        assertEquals(4, progress(days(listOf(0, 1, 2, 3))).streak)
        assertEquals(3, progress(days(listOf(1, 2, 3))).streak)
        val bridged = progress(days(listOf(0, 1, 3, 4, 5, 6)))
        assertEquals(6, bridged.streak)
        assertEquals(setOf(today.addingDays(-2)), bridged.pauseDays)
        assertEquals(1, progress(days(listOf(0, 3, 4))).streak)
    }

    @Test fun longestStreakAndReachedDays() {
        val p = progress(days(listOf(10, 11, 12, 13, 14, 15, 16, 0)))
        assertEquals(7, p.longestStreak)
        assertEquals(today.addingDays(-14), p.streakMilestones[3])
        assertEquals(today.addingDays(-10), p.streakMilestones[7])
    }

    @Test fun reviewedMilestoneDate() {
        val p = progress((0 until 120).map { decision(it, today.addingDays(if (it < 100) -1 else 0), hour = 9) })
        assertEquals(at(today.addingDays(-1), 9), p.reviewedMilestones[100])
        assertNull(p.reviewedMilestones[1_000])
    }

    @Test fun levelsAndWeeks() {
        assertEquals(1, ReviewLevel(49).number); assertEquals(2, ReviewLevel(50).number)
        assertEquals(5, ReviewLevel(4_230).number); assertEquals(770, ReviewLevel(4_230).remaining)
        assertNull(ReviewLevel(100_000).next)
        assertEquals(LocalDay(2026, 10, 19), ReviewProgress.monday(today))
        assertEquals(LocalDay(2026, 10, 19), ReviewProgress.monday(LocalDay(2026, 10, 25)))
        assertEquals(listOf(0, 1, 1, 0, 0, 0, 0), progress(days(listOf(0, 1))).currentWeek.map { it.reviewed })
    }

    @Test fun achievementsOnlyFromRealData() {
        val s = Achievements.evaluate(AchievementInput(progress(days(listOf(0, 1, 2))), tz, pairsTimes = listOf(6 to 25.7, 12 to 73.9),
            snakeBest = 78, blocksBest = 3_979))
        assertTrue(s.getValue(AchievementID.FIRST_DECISION).isEarned)
        assertTrue(s.getValue(AchievementID.ACTIVE_DAYS_3).isEarned)
        assertEquals(AchievementState.Open(4), s[AchievementID.ACTIVE_DAYS_7])
        assertTrue(s.getValue(AchievementID.PAIRS_UNDER_30).isEarned)
        assertTrue(s.getValue(AchievementID.SNAKE_50).isEarned)
        assertEquals(AchievementState.Open(1_021), s[AchievementID.BLOCKS_5000])
        assertTrue(s.getValue(AchievementID.ALL_GAMES).isEarned)
        assertFalse(s.getValue(AchievementID.LATER_CLEARED).isEarned)
        assertEquals(24, AchievementID.entries.size)
    }

    @Test fun medals() {
        assertEquals(GameMedal.SILVER, GameMedal.pairs(25.71, 6))
        assertEquals(GameMedal.GOLD, GameMedal.pairs(20.0, 6))
        assertEquals(GameMedal.GOLD, GameMedal.snake(78))
        assertEquals(GameMedal.SILVER, GameMedal.blocks(3_979))
        assertEquals(5_000L, GameMedal.next(3_979, GameMedal.BLOCKS_STEPS)?.second)
        assertNull(GameMedal.next(78, GameMedal.SNAKE_STEPS))
    }
}
