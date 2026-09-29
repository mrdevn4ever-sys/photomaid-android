package app.photomaid.core

import app.photomaid.core.decisions.DecisionLedger
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.HistoryKind
import app.photomaid.core.decisions.InMemoryLedgerPersistence
import app.photomaid.core.feed.BackNavigation
import app.photomaid.core.feed.FeedIntent
import app.photomaid.core.feed.FeedSession
import app.photomaid.core.feed.SessionSummary
import app.photomaid.core.later.LaterPreset
import app.photomaid.core.model.LocalDay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port von `FeedSessionTests.swift`. */
class FeedSessionTest {
    private fun session(n: Int) = FeedSession(items = (0 until n).map { photo(it) }).also { s ->
        for (i in 0 until n) s.markPreviewReady(photo(i))
    }

    /** Simuliert eine vollständige Geste bis zur Persistenz. */
    private suspend fun perform(intent: FeedIntent, s: FeedSession, ledger: DecisionLedger) {
        val item = s.current ?: return
        val rev = ledger.record(item).revision
        val p = s.requestDecision(intent, rev) ?: return
        try {
            val out = ledger.apply(p.command, s.id)
            s.commitSucceeded(p.command.id, out.record)
        } catch (e: Exception) {
            s.commitFailed(p.command.id)
        }
    }

    @Test fun oneKeepPerSwipe() = runBlocking {
        val ledger = makeLedger()
        val s = session(5)
        perform(FeedIntent.KEEP, s, ledger)
        assertEquals(1, s.index)
        assertEquals(1, ledger.events().size)
        assertEquals(1, s.summary.kept)
    }

    @Test fun downNoDecision() = runBlocking {
        val ledger = makeLedger()
        val s = session(3)
        assertEquals(BackNavigation.AtStart, s.goBack())
        perform(FeedIntent.KEEP, s, ledger)
        assertEquals(BackNavigation.Moved(0), s.goBack())
        assertEquals(1, ledger.events().size)
        assertEquals(Disposition.KEPT, ledger.record(photo(0)).disposition)
    }

    @Test fun passiveNavigation() = runBlocking {
        val ledger = makeLedger()
        val s = session(4)
        s.jump(3); s.jump(1); s.goBack()
        assertTrue(ledger.events().isEmpty())
        assertEquals(0, s.summary.reviewed)
    }

    @Test fun previewRequired() {
        val s = FeedSession(items = listOf(photo(0)))
        assertNull(s.requestDecision(FeedIntent.KEEP, 0))
        s.markPreviewReady(photo(0))
        assertNotNull(s.requestDecision(FeedIntent.KEEP, 0))
    }

    @Test fun staleCallbackNeverChangesOtherItem() = runBlocking {
        val ledger = makeLedger()
        val s = session(3)
        val pA = s.requestDecision(FeedIntent.KEEP, 0)!!
        val outA = ledger.apply(pA.command)
        s.commitSucceeded(pA.command.id, outA.record)
        val pB = s.requestDecision(FeedIntent.MARK_FOR_CLEANUP, 0)!!
        assertFalse(s.commitSucceeded(pA.command.id, outA.record))
        assertEquals(1, s.index)
        assertEquals(photo(1), pB.command.item)
    }

    @Test fun rapidSequenceSecondRequestDropped() {
        val s = session(3)
        val p1 = s.requestDecision(FeedIntent.KEEP, 0)
        val p2 = s.requestDecision(FeedIntent.KEEP, 0)
        assertTrue(p1 != null && p2 == null)
    }

    @Test fun persistenceFailureRollsBack() = runBlocking {
        val p = InMemoryLedgerPersistence()
        val ledger = makeLedger(persistence = p)
        val s = session(3)
        p.failNextCommits = 1
        perform(FeedIntent.MARK_FOR_CLEANUP, s, ledger)
        assertEquals(0, s.index)
        assertNull(s.pending)
        assertEquals(0, s.summary.reviewed)
    }

    @Test fun receiptBinding() = runBlocking {
        val clock = TestClock("2026-06-10T10:00")
        val ledger = makeLedger(clock)
        val s = session(3)
        perform(FeedIntent.LATER, s, ledger)
        val receipt = s.receipt!!
        assertEquals(photo(0), receipt.item)
        assertEquals(photo(1), s.current)
        val cmd = s.scheduleCommand(receipt.id, LaterPreset.TOMORROW.dueDay(clock.today))!!
        assertEquals(photo(0), cmd.item)
        assertEquals(receipt.laterCommandID, cmd.amends)
        ledger.apply(cmd)
        assertEquals(LocalDay(2026, 6, 11), ledger.record(photo(0)).dueDay)
        perform(FeedIntent.KEEP, s, ledger)
        assertNull(s.receipt)
        assertNull(s.scheduleCommand(receipt.id, LocalDay(2026, 7, 1)))
    }

    @Test fun laterCustom() = runBlocking {
        val ledger = makeLedger()
        val s = session(2)
        perform(FeedIntent.LATER_CUSTOM, s, ledger)
        val r = s.receipt!!
        assertTrue(r.wantsCustomDate)
        assertEquals(photo(0), r.item)
        assertEquals(1, s.summary.later)
        assertEquals(Disposition.LATER, ledger.record(photo(0)).disposition)
        perform(FeedIntent.LATER, s, ledger)
        assertEquals(false, s.receipt?.wantsCustomDate)
    }

    @Test fun correctionSummary() = runBlocking {
        val ledger = makeLedger()
        val s = session(3)
        perform(FeedIntent.KEEP, s, ledger)
        s.goBack()
        perform(FeedIntent.MARK_FOR_CLEANUP, s, ledger)
        assertEquals(0, s.summary.kept)
        assertEquals(1, s.summary.marked)
        assertEquals(1, s.index)
        assertEquals(listOf(HistoryKind.DECISION, HistoryKind.CORRECTION), ledger.events().map { it.kind })
    }

    @Test fun sessionEnd() = runBlocking {
        val ledger = makeLedger()
        val s = session(2)
        perform(FeedIntent.KEEP, s, ledger)
        perform(FeedIntent.LATER, s, ledger)
        assertTrue(s.isFinished)
        assertNull(s.current)
        assertNull(s.requestDecision(FeedIntent.KEEP, 0))
        assertEquals(SessionSummary(1, 0, 1), s.summary)
    }

    @Test fun hundredDecisions() = runBlocking {
        val ledger = makeLedger()
        val s = session(100)
        val rng = SeededRNG(42)
        while (!s.isFinished) perform(rng.pick(listOf(FeedIntent.KEEP, FeedIntent.MARK_FOR_CLEANUP, FeedIntent.LATER)), s, ledger)
        val records = ledger.allRecords()
        assertEquals(100, records.size)
        assertEquals(100, s.summary.reviewed)
        assertEquals(records.count { it.disposition == Disposition.MARKED_FOR_CLEANUP }, s.summary.marked)
        assertEquals(records.count { it.disposition == Disposition.KEPT }, s.summary.kept)
    }
}
