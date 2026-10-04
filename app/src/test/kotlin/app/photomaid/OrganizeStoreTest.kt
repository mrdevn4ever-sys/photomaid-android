package app.photomaid

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.decisions.DecisionLedger
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.MediaKind
import app.photomaid.data.PhotoMaidDatabase
import app.photomaid.data.RoomLedgerPersistence
import app.photomaid.feature.organize.OrganizeStore
import app.photomaid.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** Organisieren (Paket 1): Speicherung, Wiederfinden, Neustart und Migration der Datenbank von Version 1 auf 2. */
@RunWith(AndroidJUnit4::class)
class OrganizeStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun asset(id: String, name: String, size: Long = 1000, taken: Long = 1_000_000) =
        MediaAsset(id, MediaKind.PHOTO, Instant.ofEpochMilli(taken), 0.0, 100, 100, KnownSize.Known(size), "image/jpeg", name, false, "DCIM/Camera/")

    @Test fun albumsTagsNotesAndBasketSurviveRestart() = runBlocking<Unit>(Dispatchers.IO) {
        val db = PhotoMaidDatabase.open(ctx, inMemory = true)
        val s = OrganizeStore(db)
        val a = asset("content://media/1", "a.jpg"); val b = asset("content://media/2", "b.jpg"); val c = asset("content://media/3", "c.jpg")
        val id = s.createAlbum("  Ferien   2026 ", listOf(a, b))!!
        assertEquals(1, s.addToAlbum(id, listOf(b, c)))              // b schon enthalten
        val second = s.createAlbum("ferien 2026", listOf(a))!!         // eindeutiger Name
        s.editTags(listOf(a, b), add = listOf("#Rechnung", "Wohnung"))
        s.editTags(listOf(b), remove = listOf("WOHNUNG"))
        s.setNote(a, "  Ersatzteil Spülmaschine ")
        assertEquals(2, s.addToBasket(listOf(c, a)))
        assertEquals(0, s.addToBasket(listOf(a)))

        // „Neustart“: neuer Store auf derselben Datenbank.
        val r = OrganizeStore(db).also { it.load() }.state.value
        assertEquals(listOf("Ferien 2026", "ferien 2026 (2)"), r.albums.map { it.name })
        assertEquals(listOf(a.id, b.id, c.id), r.album(id)!!.items)
        assertEquals(listOf(a.id), r.album(second)!!.items)
        assertEquals(setOf("Rechnung", "Wohnung"), r.tags[a.id]!!.toSet())
        assertEquals(listOf("Rechnung"), r.tags[b.id])
        assertEquals("Ersatzteil Spülmaschine", r.notes[a.id])
        assertEquals(listOf(c.id, a.id), r.basket)

        // Entfernen/Löschen betrifft nur die Verwaltungsdaten.
        s.removeFromAlbum(id, listOf(a.id)); s.deleteAlbum(second); s.setNote(a, "   "); s.removeFromBasket(listOf(c.id))
        val after = s.state.value
        assertEquals(listOf(b.id, c.id), after.album(id)!!.items)
        assertEquals(1, after.albums.size)
        assertTrue(a.id !in after.notes)
        assertEquals(listOf(a.id), after.basket)
        s.clearBasket(); assertTrue(s.state.value.basket.isEmpty())
        db.close()
    }

    @Test fun reconcileRewritesReindexedMediaAndListsMissing() = runBlocking<Unit>(Dispatchers.IO) {
        val db = PhotoMaidDatabase.open(ctx, inMemory = true)
        val s = OrganizeStore(db)
        val a = asset("content://media/1", "a.jpg", 10, 1); val b = asset("content://media/2", "b.jpg", 20, 2); val gone = asset("content://media/3", "x.jpg", 30, 3)
        val id = s.createAlbum("Album", listOf(a, b, gone))!!
        s.editTags(listOf(b), add = listOf("Auto")); s.addToBasket(listOf(b))
        // b neu indiziert (neue ID, gleicher Fingerabdruck), gone verschwunden.
        val bNew = b.copy(id = "content://media/22")
        val missing = s.reconcile(listOf(a, bNew))
        assertEquals(setOf(gone.id), missing)
        val st = s.state.value
        assertEquals(listOf(a.id, bNew.id, gone.id), st.album(id)!!.items) // fehlendes bleibt sichtbar gespeichert
        assertEquals(listOf("Auto"), st.tags[bNew.id]); assertEquals(listOf(bNew.id), st.basket)
        db.close()
    }

    /** Version 1 (bisherige Installationen) → 2: Ledger-Daten bleiben erhalten, neue Tabellen sind nutzbar. */
    @Test fun migrationFromVersion1KeepsLedgerData() = runBlocking<Unit>(Dispatchers.IO) {
        val name = "migration-test.db"
        ctx.deleteDatabase(name)
        val file: File = ctx.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        val json = listOf("schemas", "app/schemas").map { File("$it/app.photomaid.data.PhotoMaidDatabase/1.json") }.first { it.exists() }
        val schema = JSONObject(json.readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i); val t = e.getString("tableName")
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", t))
                e.optJSONArray("indices")?.let { ix -> for (j in 0 until ix.length()) old.execSQL(ix.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", t)) }
            }
            val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.version = 1
        }
        // Daten wie in Version 1 schreiben: über Room v1 geht nicht mehr – also über die Ledger-Tabellen direkt vorbereitet:
        // eine Entscheidung mit dem aktuellen Ledger auf einer v2-DB anlegen, Zeilen in die v1-Datei kopieren.
        val mem = PhotoMaidDatabase.open(ctx, inMemory = true)
        val item = ItemReference.photo("content://media/external/images/media/7")
        DecisionLedger(RoomLedgerPersistence(mem)).apply(DecisionCommand(item = item, expectedRevision = 0, action = DecisionAction.MarkForCleanup))
        val rec = mem.ledger().records().single(); val ev = mem.ledger().events().single(); mem.close()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.execSQL("INSERT INTO item_record (itemKey, revision, disposition, dueDayKey, payload) VALUES (?,?,?,?,?)",
                arrayOf<Any?>(rec.itemKey, rec.revision, rec.disposition, rec.dueDayKey, rec.payload))
            old.execSQL("INSERT INTO event (id, itemKey, kind, atMillis, payload) VALUES (?,?,?,?,?)", arrayOf<Any?>(ev.id, ev.itemKey, ev.kind, ev.atMillis, ev.payload))
            old.execSQL("INSERT INTO key_value (`key`, payload) VALUES ('app.settings', '{}')")
        }
        // Öffnen mit Version 2 → AutoMigration.
        val db = androidx.room.Room.databaseBuilder(ctx, PhotoMaidDatabase::class.java, name).build()
        val ledger = DecisionLedger(RoomLedgerPersistence(db))
        assertEquals(Disposition.MARKED_FOR_CLEANUP, ledger.record(item).disposition)
        assertEquals(1, ledger.events().size)
        assertNotNull(db.keyValue().get("app.settings"))
        val s = OrganizeStore(db)
        val id = s.createAlbum("Nach Migration", listOf(asset("content://media/1", "a.jpg")))
        assertEquals(1, OrganizeStore(db).also { it.load() }.state.value.album(id!!)!!.items.size)
        db.close(); ctx.deleteDatabase(name)
    }
}
