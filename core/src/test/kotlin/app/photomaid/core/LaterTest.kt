package app.photomaid.core

import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.InMemoryLedgerPersistence
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.later.LaterGroup
import app.photomaid.core.later.LaterInterval
import app.photomaid.core.later.LaterPreset
import app.photomaid.core.later.LaterStatus
import app.photomaid.core.later.LaterUnit
import app.photomaid.core.later.ReviewQueues
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.ReviewSessionID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port von `LaterTests.swift` (Später 2.0). */
class LaterTest {
    @Test fun presets() {
        val today = LocalDay(2026, 1, 31)
        assertEquals(LocalDay(2026, 2, 1), LaterPreset.TOMORROW.dueDay(today))
        assertEquals(LocalDay(2026, 2, 7), LaterPreset.SEVEN_DAYS.dueDay(today))
        assertEquals(LocalDay(2026, 3, 2), LaterPreset.THIRTY_DAYS.dueDay(today))
        assertEquals(LocalDay(2028, 2, 29), LocalDay(2028, 2, 28).addingDays(1))
    }

    @Test fun intervals() {
        val d = LocalDay(2026, 1, 31)
        assertEquals(LocalDay(2026, 2, 2), LaterInterval(2, LaterUnit.DAYS).dueDay(d))
        assertEquals(LocalDay(2026, 2, 21), LaterInterval(3, LaterUnit.WEEKS).dueDay(d))
        assertEquals(LocalDay(2026, 2, 28), LaterInterval(1, LaterUnit.MONTHS).dueDay(d))
        assertEquals(LocalDay(2028, 2, 29), LaterInterval(1, LaterUnit.MONTHS).dueDay(LocalDay(2028, 1, 31)))
        assertEquals("mindestens 1", 1, LaterInterval(0, LaterUnit.DAYS).value)
        assertEquals(LaterInterval(2, LaterUnit.DAYS), LaterInterval.defaultQuick)
    }

    @Test fun daylightSaving() {
        val clock = TestClock("2026-03-28T23:30") // Europe/Zurich, Umstellung am 29.03.
        assertEquals(LocalDay(2026, 3, 29), LaterPreset.TOMORROW.dueDay(clock.today))
        // Naive 24-h-Arithmetik wäre falsch (landet am 30.03.):
        assertEquals(LocalDay(2026, 3, 30), LocalDay.of(clock.now.plusSeconds(24 * 3600), clock.zone))
        clock.set("2026-10-24T23:30")
        assertEquals(LocalDay(2026, 10, 31), LaterPreset.SEVEN_DAYS.dueDay(clock.today))
        val start = LocalDay(2026, 3, 29).startOfDay(clock.zone)
        assertEquals(LocalDay(2026, 3, 29), LocalDay.of(start, clock.zone))
    }

    private fun laterRecord(day: LocalDay?) = ItemRecord(photo(1), disposition = Disposition.LATER, dueDay = day)

    @Test fun midnight() {
        val r = laterRecord(LocalDay(2026, 6, 11))
        val clock = TestClock("2026-06-10T23:59")
        assertEquals(LaterStatus.Upcoming(r.dueDay!!), ReviewQueues.laterStatus(r, clock.today))
        clock.set("2026-06-11T00:00")
        assertEquals(LaterStatus.Due(r.dueDay!!), ReviewQueues.laterStatus(r, clock.today))
    }

    @Test fun timeZoneChange() {
        val r = laterRecord(LocalDay(2026, 6, 11))
        val clock = TestClock("2026-06-11T02:00")
        assertEquals(LaterStatus.Due(r.dueDay!!), ReviewQueues.laterStatus(r, clock.today))
        clock.setTimeZone("America/New_York")
        assertEquals(LaterStatus.Upcoming(r.dueDay!!), ReviewQueues.laterStatus(r, clock.today))
    }

    @Test fun queues() = runBlocking {
        val clock = TestClock("2026-06-10T10:00")
        val ledger = makeLedger(clock)
        val s1 = ReviewSessionID()
        ledger.decide(photo(1), DecisionAction.Later(s1), s1)
        ledger.decide(photo(1), DecisionAction.Schedule(LocalDay(2026, 6, 11)))
        ledger.decide(photo(2), DecisionAction.Later(s1), s1)
        val candidates = listOf(photo(1), photo(2), photo(3))
        var recs = ledger.records(candidates)
        assertEquals(listOf(photo(3)), ReviewQueues.normalQueue(candidates, recs, s1, clock.today))
        val s2 = ReviewSessionID()
        assertEquals(listOf(photo(2), photo(3)), ReviewQueues.normalQueue(candidates, recs, s2, clock.today))
        assertTrue(ReviewQueues.dueQueue(recs.values, s2, clock.today).isEmpty())
        clock.set("2026-06-11T08:00")
        assertEquals(listOf(photo(1)), ReviewQueues.dueQueue(recs.values, s2, clock.today))
        ledger.decide(photo(1), DecisionAction.Later(s2), s2)
        recs = ledger.records(candidates)
        assertEquals(LocalDay(2026, 6, 11), recs.getValue(photo(1)).dueDay)
        assertTrue(ReviewQueues.dueQueue(recs.values, s2, clock.today).isEmpty())
        assertEquals(listOf(photo(1)), ReviewQueues.dueQueue(recs.values, ReviewSessionID(), clock.today))
    }

    @Test fun restartKeepsSchedule() = runBlocking {
        val p = InMemoryLedgerPersistence()
        val clock = TestClock("2026-06-10T10:00")
        val ledger = makeLedger(clock, p)
        ledger.decide(photo(1), DecisionAction.Later(ReviewSessionID()))
        ledger.decide(photo(1), DecisionAction.Schedule(LocalDay(2026, 6, 17)))
        clock.set("2026-08-01T10:00")
        val reloaded = makeLedger(clock, p)
        val r = reloaded.record(photo(1))
        assertEquals(Disposition.LATER, r.disposition)
        assertEquals(LocalDay(2026, 6, 17), r.dueDay)
        assertTrue(reloaded.basket().isEmpty())
    }

    @Test fun groups() {
        val today = LocalDay(2026, 6, 10)
        fun rec(n: Int, d: LocalDay?) = ItemRecord(photo(n), disposition = Disposition.LATER, dueDay = d)
        val g = ReviewQueues.laterGroups(
            listOf(rec(1, LocalDay(2026, 6, 9)), rec(2, LocalDay(2026, 6, 11)), rec(3, LocalDay(2026, 6, 17)),
                rec(4, LocalDay(2026, 7, 10)), rec(5, null)),
            today,
        )
        assertEquals(
            listOf(LaterGroup.Due, LaterGroup.Tomorrow, LaterGroup.WithinSevenDays, LaterGroup.Later(LocalDay(2026, 7, 10)), LaterGroup.NoDate),
            g.map { it.first },
        )
    }
}
