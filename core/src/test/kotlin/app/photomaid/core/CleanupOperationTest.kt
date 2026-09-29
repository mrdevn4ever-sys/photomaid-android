package app.photomaid.core

import app.photomaid.core.cleanup.CleanupError
import app.photomaid.core.cleanup.CleanupOperationKind.FILE_DELETE_PERMANENTLY
import app.photomaid.core.cleanup.CleanupOperationKind.PHOTO_LIBRARY_DELETE
import app.photomaid.core.cleanup.ExistenceProbe
import app.photomaid.core.cleanup.OperationOutcome
import app.photomaid.core.cleanup.OperationState
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionError
import app.photomaid.core.decisions.DecisionLedger
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.HistoryKind
import app.photomaid.core.decisions.InMemoryLedgerPersistence
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port von `CleanupOperationTests.swift` (Bereinigung, Journal, Free-Limit). */
class CleanupOperationTest {
    private suspend fun markedLedger(
        n: Int,
        clock: TestClock = TestClock("2026-06-10T10:00"),
        persistence: InMemoryLedgerPersistence = InMemoryLedgerPersistence(),
    ): DecisionLedger {
        val ledger = makeLedger(clock, persistence)
        for (i in 0 until n) ledger.decide(photo(i), DecisionAction.MarkForCleanup)
        return ledger
    }

    @Test fun success() = runBlocking {
        val ledger = markedLedger(3)
        val intent = ledger.prepareCleanup(listOf(photo(0), photo(1)), PHOTO_LIBRARY_DELETE, 15)
        assertEquals(13, ledger.allowance(15).available)
        ledger.markSubmitted(intent.id)
        val done = ledger.reconcile(intent.id, OperationOutcome.Succeeded)
        assertEquals(OperationState.SUCCEEDED, done.state)
        assertEquals(2, ledger.allowance(15).confirmed)
        assertEquals(13, ledger.allowance(15).available)
        assertEquals(listOf(photo(2)), ledger.basket().map { it.item })
        assertEquals(2, ledger.events().count { it.kind == HistoryKind.CLEANUP_CONFIRMED })
    }

    @Test fun cancelled() = runBlocking {
        val ledger = markedLedger(2)
        val intent = ledger.prepareCleanup(listOf(photo(0), photo(1)), PHOTO_LIBRARY_DELETE, 15)
        ledger.markSubmitted(intent.id)
        assertEquals(OperationState.CANCELLED, ledger.reconcile(intent.id, OperationOutcome.Cancelled).state)
        assertEquals(15, ledger.allowance(15).available)
        assertEquals(2, ledger.basket().size)
    }

    @Test fun failedIsNotPartial() = runBlocking {
        val ledger = markedLedger(2)
        val intent = ledger.prepareCleanup(listOf(photo(0), photo(1)), PHOTO_LIBRARY_DELETE, 15)
        ledger.markSubmitted(intent.id)
        val done = ledger.reconcile(intent.id, OperationOutcome.Failed("x"))
        assertEquals(OperationState.FAILED, done.state)
        assertTrue(done.confirmedRemoved.isEmpty())
        assertEquals(0, ledger.allowance(15).confirmed)
    }

    @Test fun partialCountsOnlyRemoved() = runBlocking {
        val ledger = markedLedger(3)
        val intent = ledger.prepareCleanup(listOf(photo(0), photo(1), photo(2)), FILE_DELETE_PERMANENTLY, 15)
        ledger.markSubmitted(intent.id)
        val done = ledger.reconcile(intent.id, OperationOutcome.Partial(setOf(photo(1), photo(99))))
        assertEquals(OperationState.PARTIALLY_SUCCEEDED, done.state)
        assertEquals(setOf(photo(1)), done.confirmedRemoved)
        assertEquals(1, ledger.allowance(15).confirmed)
    }

    @Test fun repeatedCallbacksReconciledOnce() = runBlocking {
        val ledger = markedLedger(2)
        val intent = ledger.prepareCleanup(listOf(photo(0), photo(1)), PHOTO_LIBRARY_DELETE, 15)
        ledger.markSubmitted(intent.id)
        ledger.reconcile(intent.id, OperationOutcome.Succeeded)
        ledger.reconcile(intent.id, OperationOutcome.Succeeded)
        ledger.reconcile(intent.id, OperationOutcome.Cancelled)
        assertEquals(2, ledger.allowance(15).confirmed)
        assertEquals(2, ledger.events().count { it.kind == HistoryKind.CLEANUP_CONFIRMED })
    }

    @Test fun doubleTap() = runBlocking {
        val ledger = markedLedger(1)
        ledger.prepareCleanup(listOf(photo(0)), PHOTO_LIBRARY_DELETE, 15)
        assertEquals(CleanupError.ItemBusy(photo(0)), assertThrowsSuspend<CleanupError.ItemBusy> {
            runBlocking { ledger.prepareCleanup(listOf(photo(0)), PHOTO_LIBRARY_DELETE, 15) }
        })
        assertThrowsSuspend<DecisionError.ItemBusy> { runBlocking { ledger.decide(photo(0), DecisionAction.Keep) } }
        Unit
    }

    @Test fun allowanceBoundaries() = runBlocking {
        for (requested in listOf(0, 1, 14, 15, 16)) {
            val ledger = markedLedger(16)
            val items = (0 until requested).map { photo(it) }
            when {
                requested == 0 -> assertThrowsSuspend<CleanupError.EmptySelection> {
                    runBlocking { ledger.prepareCleanup(items, PHOTO_LIBRARY_DELETE, 15) }
                }
                requested <= 15 -> {
                    val intent = ledger.prepareCleanup(items, PHOTO_LIBRARY_DELETE, 15)
                    ledger.markSubmitted(intent.id)
                    ledger.reconcile(intent.id, OperationOutcome.Succeeded)
                    assertEquals(15 - requested, ledger.allowance(15).available)
                }
                else -> assertEquals(CleanupError.AllowanceExceeded(16, 15), assertThrowsSuspend<CleanupError.AllowanceExceeded> {
                    runBlocking { ledger.prepareCleanup(items, PHOTO_LIBRARY_DELETE, 15) }
                })
            }
        }
    }

    @Test fun fortyCandidatesSevenAvailable() = runBlocking {
        val ledger = markedLedger(48)
        val first = ledger.prepareCleanup((0 until 8).map { photo(it) }, PHOTO_LIBRARY_DELETE, 15)
        ledger.markSubmitted(first.id)
        ledger.reconcile(first.id, OperationOutcome.Succeeded)
        assertEquals(7, ledger.allowance(15).available)
        val all = (8 until 48).map { photo(it) }
        assertEquals(CleanupError.AllowanceExceeded(40, 7), assertThrowsSuspend<CleanupError.AllowanceExceeded> {
            runBlocking { ledger.prepareCleanup(all, PHOTO_LIBRARY_DELETE, 15) }
        })
        val i2 = ledger.prepareCleanup(all.take(7), PHOTO_LIBRARY_DELETE, 15)
        ledger.markSubmitted(i2.id)
        ledger.reconcile(i2.id, OperationOutcome.Succeeded)
        assertEquals(33, ledger.basket().size)
        assertEquals(0, ledger.allowance(15).available)
    }

    @Test fun concurrentReservationsAreAtomic() = runBlocking {
        val ledger = markedLedger(30)
        val results = (0 until 6).map { batch ->
            async(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { ledger.prepareCleanup((batch * 5 until (batch + 1) * 5).map { photo(it) }, PHOTO_LIBRARY_DELETE, 15) }.isSuccess
            }
        }.awaitAll()
        assertEquals(3, results.count { it })
        assertEquals(0, ledger.allowance(15).available)
    }

    @Test fun proUnlimited() = runBlocking {
        val ledger = markedLedger(40)
        val intent = ledger.prepareCleanup((0 until 40).map { photo(it) }, PHOTO_LIBRARY_DELETE, null)
        ledger.markSubmitted(intent.id)
        ledger.reconcile(intent.id, OperationOutcome.Succeeded)
        assertEquals(0, ledger.allowance(15).confirmed)
    }

    @Test fun lostCallbackNeedsReconciliation() = runBlocking {
        val p = InMemoryLedgerPersistence()
        val clock = TestClock("2026-06-10T10:00")
        val ledger = markedLedger(2, clock, p)
        val intent = ledger.prepareCleanup(listOf(photo(0), photo(1)), PHOTO_LIBRARY_DELETE, 15)
        ledger.markSubmitted(intent.id)
        val relaunched = makeLedger(clock, p)
        assertEquals(listOf(OperationState.RECONCILIATION_REQUIRED), relaunched.recoverAfterLaunch().map { it.state })
        assertEquals(13, relaunched.allowance(15).available)
        val unknown = relaunched.resolveReconciliation(
            intent.id, mapOf(photo(0) to ExistenceProbe.UNKNOWN, photo(1) to ExistenceProbe.MISSING_WITH_FULL_ACCESS),
        )
        assertEquals(OperationState.RECONCILIATION_REQUIRED, unknown.state)
        assertEquals(Disposition.MARKED_FOR_CLEANUP, relaunched.record(photo(0)).disposition)
        val resolved = relaunched.resolveReconciliation(
            intent.id, mapOf(photo(0) to ExistenceProbe.EXISTS, photo(1) to ExistenceProbe.MISSING_WITH_FULL_ACCESS),
        )
        assertEquals(OperationState.PARTIALLY_SUCCEEDED, resolved.state)
        assertEquals(Disposition.REMOVED, relaunched.record(photo(1)).disposition)
        assertEquals(Disposition.MARKED_FOR_CLEANUP, relaunched.record(photo(0)).disposition)
        assertEquals(1, relaunched.allowance(15).confirmed)
        assertTrue(relaunched.events().any { it.kind == HistoryKind.RECONCILED_MISSING && it.item == photo(1) })
    }

    @Test fun preparedNeverSubmitted() = runBlocking {
        val p = InMemoryLedgerPersistence()
        val clock = TestClock("2026-06-10T10:00")
        val ledger = markedLedger(2, clock, p)
        ledger.prepareCleanup(listOf(photo(0)), PHOTO_LIBRARY_DELETE, 15)
        val relaunched = makeLedger(clock, p)
        assertTrue(relaunched.recoverAfterLaunch().isEmpty())
        assertEquals(15, relaunched.allowance(15).available)
        assertEquals(Disposition.MARKED_FOR_CLEANUP, relaunched.record(photo(0)).disposition)
    }

    @Test fun systemSuccessDbFailure() = runBlocking {
        val p = InMemoryLedgerPersistence()
        val clock = TestClock("2026-06-10T10:00")
        val ledger = markedLedger(2, clock, p)
        val intent = ledger.prepareCleanup(listOf(photo(0), photo(1)), PHOTO_LIBRARY_DELETE, 15)
        ledger.markSubmitted(intent.id)
        p.failNextCommits = 1
        assertThrowsSuspend<CleanupError.PersistenceFailed> { runBlocking { ledger.reconcile(intent.id, OperationOutcome.Succeeded) } }
        assertEquals(OperationState.SUBMITTED, ledger.intent(intent.id)?.state)
        val relaunched = makeLedger(clock, p)
        relaunched.recoverAfterLaunch()
        val resolved = relaunched.resolveReconciliation(
            intent.id, mapOf(photo(0) to ExistenceProbe.MISSING_WITH_FULL_ACCESS, photo(1) to ExistenceProbe.MISSING_WITH_FULL_ACCESS),
        )
        assertEquals(OperationState.SUCCEEDED, resolved.state)
        assertEquals(2, relaunched.allowance(15).confirmed)
    }

    @Test fun aliasConflict() = runBlocking {
        val ledger = markedLedger(2)
        assertEquals(CleanupError.AliasConflict(photo(1)), assertThrowsSuspend<CleanupError.AliasConflict> {
            runBlocking {
                ledger.prepareCleanup(listOf(photo(0), photo(1)), FILE_DELETE_PERMANENTLY, 15,
                    mapOf(photo(0) to "inode-7", photo(1) to "inode-7"))
            }
        })
    }

    @Test fun clockChanges() = runBlocking {
        val clock = TestClock("2026-06-10T23:50")
        val ledger = markedLedger(6, clock)
        val i1 = ledger.prepareCleanup(listOf(photo(0), photo(1)), PHOTO_LIBRARY_DELETE, 15)
        ledger.markSubmitted(i1.id)
        clock.set("2026-06-11T00:10")
        ledger.reconcile(i1.id, OperationOutcome.Succeeded)
        assertEquals(0, ledger.allowance(15).confirmed)
        clock.set("2026-06-10T23:55")
        assertEquals(2, ledger.allowance(15).confirmed)
        assertEquals(13, ledger.allowance(15).available)
    }
}
