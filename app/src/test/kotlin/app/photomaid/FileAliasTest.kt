package app.photomaid

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.cleanup.CleanupError
import app.photomaid.core.cleanup.CleanupOperationKind
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.model.ConnectedSourceID
import app.photomaid.core.model.ItemProvider
import app.photomaid.core.model.ItemReference
import app.photomaid.files.ConnectedSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Gerätebefund Lauf 6 (K8): dieselbe Datei über zwei verbundene Ordner („Download“ und „Download/Test“) darf nur
 * einmal im Papierkorb zählen und nicht doppelt gegen das Tageslimit laufen.
 */
@RunWith(AndroidJUnit4::class)
class FileAliasTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val download = ConnectedSourceID()
    private val test = ConnectedSourceID()
    private val other = ConnectedSourceID()
    private val docId = "primary:Download/Test/PhotoMaid_Run6_K8_DELETE.jpg"

    private fun app() = makeTestApp(ctx) { a ->
        val tree = "content://com.android.externalstorage.documents/tree/"
        a.store.setValue("sources.connected", listOf(
            ConnectedSource(download, "Download", tree + "primary%3ADownload", Instant.now(), true, true),
            ConnectedSource(test, "Test", tree + "primary%3ADownload%2FTest", Instant.now(), true, false),
            ConnectedSource(other, "Dokumente", "content://com.example.docs/tree/root", Instant.now(), true, false),
        ))
        a.sources.load()
    }

    private suspend fun AppModel.mark(ref: ItemReference) =
        ledger.apply(DecisionCommand(item = ref, expectedRevision = ledger.record(ref).revision, action = DecisionAction.MarkForCleanup))

    @Test fun sameFileViaTwoFoldersCountsOnce() = runBlocking {
        val app = app()
        val viaDownload = ItemReference(ItemProvider.ConnectedSource(download), docId)
        val viaTest = ItemReference(ItemProvider.ConnectedSource(test), docId)
        val elsewhere = ItemReference(ItemProvider.ConnectedSource(other), docId) // anderer Anbieter = andere Datei
        assertEquals(app.aliasOf(viaDownload), app.aliasOf(viaTest))
        assertNotEquals(app.aliasOf(viaDownload), app.aliasOf(elsewhere))
        app.mark(viaDownload); app.mark(viaTest); app.mark(elsewhere)
        app.reload()
        assertEquals(2, app.read.value.basket.size)
        // Beide Verweise zusammen löschen wäre dieselbe Datei zweimal: abgelehnt, bevor Android fragt oder Limit belegt wird.
        val r = app.runCleanup(listOf(viaDownload, viaTest), CleanupOperationKind.FILE_DELETE_PERMANENTLY)
        assertTrue(r is CleanupRunResult.Rejected && r.error is CleanupError.AliasConflict)
        assertEquals(20, app.read.value.allowance?.available)
    }
}
