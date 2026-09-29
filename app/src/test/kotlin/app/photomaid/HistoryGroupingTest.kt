package app.photomaid

import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.decisions.DecisionLedger
import app.photomaid.core.decisions.HistoryKind
import app.photomaid.core.decisions.InMemoryLedgerPersistence
import app.photomaid.core.model.CommandID
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.feature.history.groupHistory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verlauf-Darstellung (UI-Polish §10): nur eindeutig zusammengehörige Events werden zu einem Vorgang. */
class HistoryGroupingTest {
    private val ledger = DecisionLedger(InMemoryLedgerPersistence())
    private val s = ReviewSessionID()
    private val day = LocalDay(2026, 10, 4)

    private fun cmd(i: Int, action: DecisionAction, amends: CommandID? = null): CommandID = runBlocking {
        val ref = ItemReference.photo("p$i")
        val c = DecisionCommand(item = ref, expectedRevision = ledger.record(ref).revision, action = action, amends = amends)
        ledger.apply(c, s)
        c.id
    }

    @Test fun laterWithFirstDateBecomesOneEntryJournalUnchanged() = runBlocking {
        val later = cmd(1, DecisionAction.Later(s))
        cmd(1, DecisionAction.Schedule(day), amends = later)
        cmd(2, DecisionAction.Keep)
        val events = ledger.events()
        val entries = groupHistory(events)
        assertEquals(3, events.size) // Journal vollständig
        assertEquals(2, entries.size)
        assertEquals(HistoryKind.SCHEDULE_CHANGED, entries[0].schedule?.kind)
        assertEquals(day, entries[0].schedule?.to?.dueDay)
        assertNull(entries[1].schedule) // Behalten bleibt eigener Eintrag
        assertEquals(events, entries.flatMap { it.events }) // nichts verloren, Reihenfolge erhalten
    }

    @Test fun laterDateChangeStaysSeparate() = runBlocking {
        val later = cmd(1, DecisionAction.Later(s))
        cmd(1, DecisionAction.Schedule(day), amends = later)
        cmd(1, DecisionAction.Schedule(LocalDay(2026, 11, 1)))
        val entries = groupHistory(ledger.events())
        assertEquals(2, entries.size) // zweite Terminänderung ist ein eigener Vorgang
        assertEquals(HistoryKind.SCHEDULE_CHANGED, entries[1].primary.kind)
    }

    @Test fun differentItemsOrInterleavedUndoAreNotMerged() = runBlocking {
        cmd(1, DecisionAction.Later(s))
        cmd(2, DecisionAction.Later(s))
        cmd(2, DecisionAction.Schedule(day))
        // Item 3: Später, Undo, erneut Später – ohne Termin bleibt jede Zeile einzeln.
        cmd(3, DecisionAction.Later(s))
        ledger.undo()
        cmd(3, DecisionAction.Later(s))
        val entries = groupHistory(ledger.events())
        val merged = entries.filter { it.schedule != null }
        assertEquals(1, merged.size)
        assertEquals(ItemReference.photo("p2"), merged.single().primary.item)
        assertEquals(ledger.events(), entries.flatMap { it.events })
    }

    /** Gerätetest: zehn „Bereinigung nicht ausgeführt“ eines abgebrochenen Systemdialogs → eine Zeile mit Anzahl. */
    @Test fun sameOperationBecomesOneRowOthersStaySeparate() {
        val op = app.photomaid.core.model.OperationID()
        val other = app.photomaid.core.model.OperationID()
        val snap = app.photomaid.core.decisions.DecisionSnapshot(app.photomaid.core.decisions.Disposition.MARKED_FOR_CLEANUP)
        fun ev(i: Int, o: app.photomaid.core.model.OperationID?, kind: HistoryKind) = app.photomaid.core.decisions.DecisionEvent(
            commandID = null, operationID = o, item = ItemReference.photo("p$i"), kind = kind, from = snap, to = snap,
            revision = 1, at = java.time.Instant.parse("2026-09-29T10:00:00Z"), sessionID = null)
        val events = (0 until 10).map { ev(it, op, HistoryKind.CLEANUP_NOT_PERFORMED) } +
            ev(10, other, HistoryKind.CLEANUP_NOT_PERFORMED) + ev(11, other, HistoryKind.CLEANUP_CONFIRMED)
        val entries = groupHistory(events)
        assertEquals(3, entries.size)
        assertEquals(10, entries[0].itemCount)
        assertEquals(1, entries[1].itemCount) // anderer Vorgang
        assertEquals(1, entries[2].itemCount) // andere Art im selben Vorgang
        assertEquals(events, entries.flatMap { it.events })
    }
}
