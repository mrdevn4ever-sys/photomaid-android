package app.photomaid.core

import app.photomaid.core.cleanup.CleanupOperationKind
import app.photomaid.core.cleanup.OperationOutcome
import app.photomaid.core.decisions.CommitOutcome
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.decisions.DecisionError
import app.photomaid.core.decisions.DecisionRules
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.HistoryKind
import app.photomaid.core.decisions.InMemoryLedgerPersistence
import app.photomaid.core.later.LaterPreset
import app.photomaid.core.later.ReviewQueues
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.ReviewSessionID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port von `DecisionLedgerTests.swift`. */
class DecisionLedgerTest {
    @Test fun basicDecisions() = runBlocking {
        val ledger = makeLedger()
        val s = ReviewSessionID()
        ledger.decide(photo(1), DecisionAction.Keep, s)
        ledger.decide(photo(2), DecisionAction.MarkForCleanup, s)
        ledger.decide(photo(3), DecisionAction.Later(s), s)
        assertEquals(Disposition.KEPT, ledger.record(photo(1)).disposition)
        assertEquals(Disposition.MARKED_FOR_CLEANUP, ledger.record(photo(2)).disposition)
        assertEquals(Disposition.LATER, ledger.record(photo(3)).disposition)
        assertEquals(3, ledger.events().size)
        assertEquals(listOf(photo(2)), ledger.basket().map { it.item })
    }

    @Test fun idempotent() = runBlocking {
        val ledger = makeLedger()
        ledger.decide(photo(1), DecisionAction.MarkForCleanup)
        assertTrue(ledger.decide(photo(1), DecisionAction.MarkForCleanup) is CommitOutcome.Unchanged)
        assertEquals(1, ledger.events().size)
        assertEquals(1, ledger.basket().size)
        assertEquals(1, ledger.record(photo(1)).revision)
    }

    @Test fun duplicateCommand() = runBlocking {
        val ledger = makeLedger()
        val cmd = DecisionCommand(item = photo(1), expectedRevision = 0, action = DecisionAction.Keep)
        ledger.apply(cmd)
        assertTrue(ledger.apply(cmd) is CommitOutcome.Duplicate)
        assertEquals(1, ledger.events().size)
    }

    @Test fun staleRevision() = runBlocking {
        val ledger = makeLedger()
        ledger.decide(photo(1), DecisionAction.Keep)
        val e = assertThrowsSuspend<DecisionError.StaleRevision> {
            runBlocking { ledger.apply(DecisionCommand(item = photo(1), expectedRevision = 0, action = DecisionAction.MarkForCleanup)) }
        }
        assertEquals(DecisionError.StaleRevision(0, 1), e)
    }

    @Test fun correction() = runBlocking {
        val ledger = makeLedger()
        ledger.decide(photo(1), DecisionAction.Keep)
        ledger.decide(photo(1), DecisionAction.MarkForCleanup)
        val r = ledger.record(photo(1))
        assertEquals(Disposition.MARKED_FOR_CLEANUP, r.disposition)
        assertEquals(2, r.revision)
        assertEquals(listOf(HistoryKind.DECISION, HistoryKind.CORRECTION), ledger.events().map { it.kind })
    }

    @Test fun undoRestores() = runBlocking {
        val ledger = makeLedger()
        ledger.decide(photo(1), DecisionAction.Keep)
        ledger.decide(photo(1), DecisionAction.MarkForCleanup)
        val r = ledger.undo()
        assertEquals(Disposition.KEPT, r.disposition)
        assertEquals(3, r.revision)
        assertEquals(Disposition.UNDECIDED, ledger.undo().disposition)
        assertThrowsSuspend<DecisionError.NothingToUndo> { runBlocking { ledger.undo() } }
        Unit
    }

    @Test fun undoConflict() = runBlocking {
        val ledger = makeLedger()
        ledger.decide(photo(1), DecisionAction.MarkForCleanup)
        val intent = ledger.prepareCleanup(listOf(photo(1)), CleanupOperationKind.PHOTO_LIBRARY_DELETE, 15)
        assertThrowsSuspend<DecisionError.ItemBusy> { runBlocking { ledger.undo() } }
        assertEquals(Disposition.MARKED_FOR_CLEANUP, ledger.record(photo(1)).disposition)
        ledger.markSubmitted(intent.id)
        ledger.reconcile(intent.id, OperationOutcome.Succeeded)
        assertEquals(Disposition.REMOVED, ledger.record(photo(1)).disposition)
        assertThrowsSuspend<DecisionError.UndoConflict> { runBlocking { ledger.undo() } }
        assertEquals(Disposition.REMOVED, ledger.record(photo(1)).disposition)
        assertThrowsSuspend<DecisionError.NothingToUndo> { runBlocking { ledger.undo() } }
        Unit
    }

    @Test fun undoInterleaved() = runBlocking {
        val ledger = makeLedger()
        ledger.decide(photo(1), DecisionAction.Keep)
        ledger.decide(photo(2), DecisionAction.MarkForCleanup)
        ledger.decide(photo(1), DecisionAction.MarkForCleanup)
        assertEquals(Disposition.KEPT, ledger.undo().disposition)
        assertEquals(photo(2), ledger.undo().item)
        assertEquals(Disposition.UNDECIDED, ledger.undo().disposition)
        assertEquals(Disposition.UNDECIDED, ledger.record(photo(2)).disposition)
    }

    @Test fun scheduleAmendsLater() = runBlocking {
        val clock = TestClock("2026-06-10T10:00")
        val ledger = makeLedger(clock)
        val s = ReviewSessionID()
        val later = DecisionCommand(item = photo(1), expectedRevision = 0, action = DecisionAction.Later(s))
        val r = ledger.apply(later, s).record
        val day = LaterPreset.SEVEN_DAYS.dueDay(clock.today)
        ledger.apply(DecisionCommand(item = photo(1), expectedRevision = r.revision, action = DecisionAction.Schedule(day), amends = later.id))
        assertEquals(LocalDay(2026, 6, 17), ledger.record(photo(1)).dueDay)
        val undone = ledger.undo()
        assertEquals(Disposition.UNDECIDED, undone.disposition)
        assertEquals(null, undone.dueDay)
        assertFalse(ledger.canUndo())
    }

    @Test fun persistenceFailure() = runBlocking {
        val p = InMemoryLedgerPersistence()
        val ledger = makeLedger(persistence = p)
        p.failNextCommits = 1
        assertThrowsSuspend<DecisionError.PersistenceFailed> { runBlocking { ledger.decide(photo(1), DecisionAction.Keep) } }
        assertEquals(Disposition.UNDECIDED, ledger.record(photo(1)).disposition)
        assertTrue(ledger.events().isEmpty())
        assertFalse(ledger.canUndo())
        ledger.decide(photo(1), DecisionAction.Keep)
        assertEquals(Disposition.KEPT, ledger.record(photo(1)).disposition)
    }

    @Test fun restart() = runBlocking {
        val p = InMemoryLedgerPersistence()
        makeLedger(persistence = p).decide(photo(1), DecisionAction.MarkForCleanup)
        val reloaded = makeLedger(persistence = p)
        assertEquals(Disposition.MARKED_FOR_CLEANUP, reloaded.record(photo(1)).disposition)
        assertTrue(reloaded.canUndo())
    }

    @Test fun keepResolvesSchedule() = runBlocking {
        val ledger = makeLedger()
        val s = ReviewSessionID()
        ledger.decide(photo(1), DecisionAction.Later(s))
        ledger.decide(photo(1), DecisionAction.Schedule(LocalDay(2026, 7, 1)))
        ledger.decide(photo(1), DecisionAction.Keep)
        val r = ledger.record(photo(1))
        assertTrue(r.dueDay == null && r.disposition == Disposition.KEPT && r.skippedInSession == null)
    }

    @Test fun removeDeferral() = runBlocking {
        val ledger = makeLedger()
        ledger.decide(photo(1), DecisionAction.Later(ReviewSessionID()))
        ledger.decide(photo(1), DecisionAction.RemoveDeferral)
        assertEquals(Disposition.UNDECIDED, ledger.record(photo(1)).disposition)
        assertThrowsSuspend<DecisionError> { runBlocking { ledger.decide(photo(1), DecisionAction.RemoveDeferral) } }
        Unit
    }

    @Test fun generatedSequencesKeepInvariants() = runBlocking {
        for (seed in 1L..5L) {
            val rng = SeededRNG(seed)
            val ledger = makeLedger()
            val items = (0 until 6).map { photo(it) }
            val s = ReviewSessionID()
            val lastRevision = mutableMapOf<ItemReference, Int>()
            repeat(300) {
                val item = rng.pick(items)
                try {
                    when (rng.nextInt(7)) {
                        0 -> ledger.decide(item, DecisionAction.Keep, s)
                        1 -> ledger.decide(item, DecisionAction.MarkForCleanup, s)
                        2 -> ledger.decide(item, DecisionAction.Later(s), s)
                        3 -> ledger.decide(item, DecisionAction.Schedule(LocalDay(2026, 7, 1)))
                        4 -> ledger.decide(item, DecisionAction.Unmark)
                        5 -> ledger.decide(item, DecisionAction.RemoveDeferral)
                        else -> ledger.undo()
                    }
                } catch (_: DecisionError) {
                }
                for (i in items) {
                    val r = ledger.record(i)
                    assertTrue("Revision darf nie sinken", r.revision >= (lastRevision[i] ?: 0))
                    lastRevision[i] = r.revision
                    if (r.dueDay != null) assertEquals("Termin nur bei Später", Disposition.LATER, r.disposition)
                    assertNotEquals("Keine Löschung ohne Operation", Disposition.REMOVED, r.disposition)
                }
                val basket = ledger.basket()
                assertEquals(basket.size, basket.map { it.item }.toSet().size)
            }
            val events = ledger.events()
            for (i in items) {
                events.lastOrNull { it.item == i }?.let { assertEquals(ledger.record(i).revision, it.revision) }
            }
        }
    }

    /** Gerätetest K5: aus dem System-Papierkorb zurückgeholt (gleiche ID) → wieder offen und neu entscheidbar. */
    @Test fun restoredFromSystemTrashIsPendingAgain() = runBlocking {
        val ledger = makeLedger()
        ledger.decide(photo(1), DecisionAction.MarkForCleanup)
        val intent = ledger.prepareCleanup(listOf(photo(1)), CleanupOperationKind.PHOTO_LIBRARY_DELETE, 15)
        ledger.markSubmitted(intent.id)
        ledger.reconcile(intent.id, OperationOutcome.Succeeded)
        val removed = ledger.record(photo(1))
        assertEquals(Disposition.REMOVED, removed.disposition)
        // Liegt es wieder in der Mediathek, ist es ein Kandidat → normale Warteschlange.
        val queue = ReviewQueues.normalQueue(listOf(photo(1), photo(2)), ledger.records(listOf(photo(1), photo(2))), ReviewSessionID(), LocalDay(2026, 10, 2))
        assertEquals(listOf(photo(1), photo(2)), queue)
        // Neue Entscheidung ist erlaubt und zählt als Entscheidung, nicht als Korrektur.
        ledger.decide(photo(1), DecisionAction.Keep)
        assertEquals(Disposition.KEPT, ledger.record(photo(1)).disposition)
        assertEquals(HistoryKind.DECISION, ledger.events().last().kind)
        // Rückgängig führt zurück auf „entfernt“.
        assertEquals(Disposition.REMOVED, ledger.undo().disposition)
        // Termin-/Markierungsaktionen bleiben auf einem entfernten Item ungültig.
        assertTrue(DecisionRules.transition(ledger.record(photo(1)), DecisionAction.Unmark).isFailure)
        assertTrue(DecisionRules.transition(ledger.record(photo(1)), DecisionAction.RemoveDeferral).isFailure)
        Unit
    }
}
