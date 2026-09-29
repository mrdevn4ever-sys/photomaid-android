package app.photomaid

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.cleanup.CleanupOperationKind
import app.photomaid.core.cleanup.OperationState
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.decisions.DecisionLedger
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.model.ConnectedSourceID
import app.photomaid.core.model.ItemProvider
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.data.KeyValueStore
import app.photomaid.data.PhotoMaidDatabase
import app.photomaid.data.RoomLedgerPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Port von `CoreDataLedgerPersistenceTests.swift`: Roundtrip, Journal nach Neustart, Key-Value. */
@RunWith(AndroidJUnit4::class)
class RoomLedgerPersistenceTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun roundTripSurvivesRestart() = runBlocking(Dispatchers.IO) {
        val db = PhotoMaidDatabase.open(ctx, inMemory = true)
        val p = RoomLedgerPersistence(db)
        val ledger = DecisionLedger(p)
        val s = ReviewSessionID()
        val photo = ItemReference.photo("content://media/external/images/media/42")
        val file = ItemReference(ItemProvider.ConnectedSource(ConnectedSourceID()), "primary:Download/Reiseplan.pdf")
        ledger.apply(DecisionCommand(item = photo, expectedRevision = 0, action = DecisionAction.Later(s)), s)
        ledger.apply(DecisionCommand(item = photo, expectedRevision = 1, action = DecisionAction.Schedule(LocalDay(2026, 10, 1))))
        ledger.apply(DecisionCommand(item = file, expectedRevision = 0, action = DecisionAction.MarkForCleanup))
        // „Neustart“: neuer Ledger auf derselben Datenbank.
        val reloaded = DecisionLedger(RoomLedgerPersistence(db))
        val r = reloaded.record(photo)
        assertEquals(Disposition.LATER, r.disposition)
        assertEquals(LocalDay(2026, 10, 1), r.dueDay)
        assertEquals(listOf(file), reloaded.basket().map { it.item })
        assertEquals(3, reloaded.events().size)
        assertTrue(reloaded.canUndo())
        // Undo nach Neustart nimmt Termin + Später gemeinsam zurück (amends wurde nicht genutzt → nur Termin).
        reloaded.undo()
        db.close()
    }

    @Test fun journalAfterRestartNeedsReconciliation() = runBlocking(Dispatchers.IO) {
        val db = PhotoMaidDatabase.open(ctx, inMemory = true)
        val ledger = DecisionLedger(RoomLedgerPersistence(db))
        val a = ItemReference.photo("content://media/external/images/media/1")
        ledger.apply(DecisionCommand(item = a, expectedRevision = 0, action = DecisionAction.MarkForCleanup))
        val intent = ledger.prepareCleanup(listOf(a), CleanupOperationKind.PHOTO_LIBRARY_DELETE, 15)
        ledger.markSubmitted(intent.id)
        val relaunched = DecisionLedger(RoomLedgerPersistence(db))
        assertEquals(listOf(OperationState.RECONCILIATION_REQUIRED), relaunched.recoverAfterLaunch().map { it.state })
        assertEquals(14, relaunched.allowance(15).available)
        db.close()
    }

    @Test fun keyValueTolerantDecoding() = runBlocking(Dispatchers.IO) {
        val db = PhotoMaidDatabase.open(ctx, inMemory = true)
        val kv = KeyValueStore(db)
        kv.putRaw("app.settings", """{"haptics":false,"unknownFutureField":1}""")
        val s = kv.value<AppSettingsData>("app.settings")!!
        assertEquals(false, s.haptics)
        assertEquals(2, s.laterDefaultDays) // fehlendes Feld behält Standard
        db.close()
    }
}
