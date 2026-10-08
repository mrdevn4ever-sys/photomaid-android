package app.photomaid

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.archive.ArchiveCleanup
import app.photomaid.archive.ArchiveRunner
import app.photomaid.archive.BackupService
import app.photomaid.archive.FileArchiveTarget
import app.photomaid.core.archive.ArchiveJournal
import app.photomaid.core.archive.ArchiveOptions
import app.photomaid.core.archive.ArchivePlan
import app.photomaid.core.archive.BackupCheck
import app.photomaid.core.archive.EntryReason
import app.photomaid.core.archive.EntryState
import app.photomaid.core.archive.JournalEntry
import app.photomaid.core.archive.TargetKind
import app.photomaid.core.archive.toHex
import app.photomaid.core.batch.Workflow
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.media.MediaAsset
import app.photomaid.media.reviewableMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId

/** Paket 5: Auslagern (kopieren, neu lesen, vergleichen) und Sichern/Wiederherstellen. Nie eine Entscheidung ohne Tipp. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de")
class ArchiveEngineTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val utc = ZoneId.of("UTC")
    private lateinit var lib: FakeMediaLibrary

    private fun app(count: Int = 5): AppModel = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = count, videos = 1).also { l -> lib = l
        // Gemeldete Größe = echte Dateigröße (wie MediaStore), sonst wäre „schon vorhanden“ nie erkennbar.
        l.all.forEach { a -> l.sizes[a.id] = KnownSize.Known(file(a).length()) } } })

    private fun file(a: MediaAsset) = File(a.id.removePrefix("file://"))
    private fun target(kind: TargetKind = TargetKind.REMOVABLE) = FileArchiveTarget(File(ctx.cacheDir, "sd-${System.nanoTime()}"), kind, "SD-Karte")
    private fun dirOf(t: FileArchiveTarget) = File(Uri.parse(t.id).path!!)
    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).toHex()
    private fun run(app: AppModel, t: FileArchiveTarget, assets: List<MediaAsset>, o: ArchiveOptions = ArchiveOptions()) =
        runBlocking(Dispatchers.IO) { ArchiveRunner.run(app, t, assets, o, zone = utc) }

    @Test fun copiesChecksEveryFileAndKeepsOriginals() {
        val app = app()
        val assets = lib.reviewableMedia()
        val before = assets.associate { it.id to sha(file(it)) }
        val t = target()
        val j = run(app, t, assets)
        assertEquals(assets.size, j.summary.verified)
        assertTrue(j.finished)
        val dir = dirOf(t)
        for (e in j.entries) {
            val f = File(dir, (e.folder + e.name).joinToString("/"))
            val a = assets.first { it.id == e.sourceId }
            assertEquals(listOf("2026", "09"), e.folder) // Aufnahme September 2026 (Testdaten)
            assertTrue("Endung aus dem Dateityp ergänzt: ${e.name}", e.name.endsWith(".png") || e.name.endsWith(".mp4"))
            assertArrayEquals(file(a).readBytes(), f.readBytes())
            assertEquals(sha(f), e.sha256)
            assertEquals(f.length(), e.bytes)
        }
        // Prüfliste mit Kopfzeile und einer Zeile je Datei.
        val csv = dir.listFiles()!!.single { it.name.endsWith(".csv") }
        assertTrue(csv.name, csv.name.startsWith("PhotoMaid-Pr") && csv.name.endsWith(".csv")) // Test-JVM ohne UTF-8-Dateinamen: „ü“ wird hier zu „?“
        val lines = csv.readText().trim().lines()
        assertTrue(lines[0].contains("SHA-256")); assertEquals(assets.size + 1, lines.size)
        assertEquals(j.listName!!.replace(Regex("[^\\x00-\\x7F]"), "?"), csv.name)
        // Originale unverändert, keine Entscheidung, Journal gespeichert.
        assertEquals(before, assets.associate { it.id to sha(file(it)) })
        assertTrue(app.ledger.stateFlow.value.records.isEmpty())
        assertEquals(j, app.archive.last.value)
    }

    @Test fun secondRunFindsIdenticalFilesAndNeverOverwritesADifferentOne() {
        val app = app(count = 3)
        val assets = lib.reviewableMedia()
        val t = target()
        run(app, t, assets, ArchiveOptions(writeList = false))
        val again = run(app, t, assets, ArchiveOptions(writeList = false))
        assertEquals(assets.size, again.summary.alreadyThere)
        // Gleicher Name, gleiche Größe, andere Bytes: bleibt unangetastet, die neue Kopie bekommt „(2)“.
        val first = again.entries.first()
        val existing = File(dirOf(t), (first.folder + first.name).joinToString("/"))
        val other = existing.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        existing.writeBytes(other)
        val third = run(app, t, assets, ArchiveOptions(writeList = false))
        val e = third.entries.first { it.sourceId == first.sourceId }
        assertEquals(EntryState.VERIFIED, e.state)
        assertEquals(ArchivePlan.uniqueName(setOf(first.name), first.name), e.name) // „QA_0 (2).png“
        assertArrayEquals(other, existing.readBytes())
    }

    @Test fun aCopyThatDiffersIsRemovedAndNeverCounts() {
        val app = app(count = 2)
        val t = target().apply { corruptWrites = true }
        val j = run(app, t, lib.reviewableMedia())
        assertTrue(j.entries.all { it.state == EntryState.FAILED && it.reason == EntryReason.MISMATCH })
        assertTrue(j.verified.isEmpty())
        assertNull(j.listName) // keine Prüfliste ohne geprüfte Kopie
        assertTrue(dirOf(t).walkTopDown().none { it.isFile })
    }

    @Test fun fullDestinationStopsCleanly() {
        val app = app(count = 4)
        val assets = lib.reviewableMedia()
        val t = target().apply { failAfterBytes = file(assets[0]).length() + 10 }
        val j = run(app, t, assets, ArchiveOptions(writeList = false))
        assertEquals(EntryState.VERIFIED, j.entries[0].state)
        assertEquals(EntryReason.NO_SPACE, j.entries[1].reason)
        assertTrue(j.entries.drop(2).all { it.state == EntryState.NOT_DONE && it.reason == EntryReason.NO_SPACE })
        assertEquals(1, dirOf(t).walkTopDown().count { it.isFile }) // halbe Kopie entfernt
    }

    @Test fun cancelInTheMiddleRemovesThePartialCopy() {
        val app = app(count = 4)
        val assets = lib.reviewableMedia()
        val t = target()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        var started = false
        lateinit var job: kotlinx.coroutines.Deferred<ArchiveJournal>
        t.beforeWrite = { name -> if (name.startsWith(assets[1].displayName!!) && !started) { started = true; job.cancel() } }
        job = scope.async { ArchiveRunner.run(app, t, assets, ArchiveOptions(writeList = false), zone = utc) }
        val cancelled = runBlocking { runCatching { job.await() }.exceptionOrNull() }
        assertTrue(cancelled is CancellationException)
        val j = app.archive.last.value!!
        assertTrue(j.finished)
        assertEquals(EntryState.VERIFIED, j.entries[0].state)
        assertTrue(j.entries.drop(1).all { it.state == EntryState.NOT_DONE && it.reason == EntryReason.CANCELLED })
        assertEquals(listOf(j.entries[0].name), dirOf(t).walkTopDown().filter { it.isFile }.map { it.name }.toList())
    }

    @Test fun afterARestartOnlyCheckedCopiesCountAndTheHalfCopyIsRemoved() {
        val app = app(count = 2)
        val half = File(ctx.cacheDir, "half-${System.nanoTime()}.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        app.archive.save(ArchiveJournal("j", "SD", TargetKind.REMOVABLE, 1, entries = listOf(
            JournalEntry("a", "a.png", state = EntryState.VERIFIED, bytes = 3, sha256 = "x"),
            JournalEntry("b", "b.png", state = EntryState.COPYING, targetUri = Uri.fromFile(half).toString()),
            JournalEntry("c", "c.png"))))
        app.archive.recoverAfterRestart()
        val j = app.archive.last.value!!
        assertFalse(half.exists())
        assertTrue(j.finished)
        assertEquals(listOf(EntryState.VERIFIED, EntryState.NOT_DONE, EntryState.NOT_DONE), j.entries.map { it.state })
        assertEquals(EntryReason.INTERRUPTED, j.entries[1].reason)
    }

    @Test fun originalsGoToTrashOnlyOnRequestOnlyFromOwnStorageAndFavoritesStay() {
        val app = app(count = 3)
        val assets = lib.reviewableMedia()
        lib.favorites[assets[0].id] = true
        val j = run(app, target(), lib.reviewableMedia(), ArchiveOptions(writeList = false))
        assertTrue(app.ledger.stateFlow.value.records.isEmpty()) // Auslagern allein entscheidet nichts
        val cand = ArchiveCleanup.candidates(app, j)
        assertEquals(1, cand.protectedFavorites)
        assertFalse(assets[0].id in cand.ids)
        val n = runBlocking(Dispatchers.IO) { ArchiveCleanup.markForCleanup(app, cand.ids) }
        assertEquals(assets.size - 1, n)
        val recs = app.ledger.stateFlow.value.records
        assertTrue(cand.ids.all { recs[ItemReference.photo(it)]?.disposition == Disposition.MARKED_FOR_CLEANUP })
        assertTrue(lib.trashed.isEmpty() && lib.deleted.isEmpty()) // nichts gelöscht
        // Gleicher Gerätespeicher oder andere App: kein Angebot.
        val same = run(app, target(TargetKind.SAME_DEVICE), assets, ArchiveOptions(writeList = false))
        assertTrue(ArchiveCleanup.candidates(app, same).ids.isEmpty())
        val other = run(app, target(TargetKind.PROVIDER), assets, ArchiveOptions(writeList = false))
        assertTrue(ArchiveCleanup.candidates(app, other).ids.isEmpty())
    }

    @Test fun backupMovesToANewPhoneAndNeverOverwrites() {
        val old = app(count = 5)
        val a = lib.reviewableMedia().sortedBy { it.displayName }
        runBlocking(Dispatchers.IO) {
            old.decide(a[0].reference, DecisionAction.Keep)
            old.decide(a[1].reference, DecisionAction.Later(ReviewSessionID())); old.decide(a[1].reference, DecisionAction.Schedule(LocalDay(2026, 12, 24)))
            old.decide(a[2].reference, DecisionAction.MarkForCleanup)
            old.organize.createAlbum("Ferien", listOf(a[0], a[1]))
            old.organize.editTags(listOf(a[0]), add = listOf("Strand"))
            old.organize.setNote(a[1], "Oma")
            old.organize.addToBasket(listOf(a[3]))
            old.workflows.upsert(Workflow("w1", "Kleinanzeige"))
            old.updateSettings { it.copy(haptics = false, laterDefaultDays = 7, deletePermanently = true, hiddenMedia = listOf(a[4].id)) }
        }
        val backup = runBlocking { BackupService.create(old) }
        val text = BackupService.encode(backup)
        assertFalse("keine Fotos in der Datei", text.contains("\"bytes\""))
        val decoded = (BackupService.decode(text) as BackupCheck.Ok).backup
        val k = BackupService.contents(decoded)
        assertEquals(3, k.decisions); assertEquals(1, k.albums); assertEquals(1, k.tagged); assertEquals(1, k.notes); assertEquals(1, k.basket)
        assertEquals(1, k.hidden); assertEquals(1, k.workflows)

        // Neues Gerät: dieselben Fotos mit neuen IDs (gleicher Name, gleiche Größe, gleiche Aufnahmezeit); QA_5 fehlt.
        val fresh = app(count = 5)
        val origin = lib.all.toList()
        val clones = origin.filter { it.displayName != "QA_5" }.map { o -> lib.addNew(origin.indexOf(o), o.creationDate!!).also { c -> lib.sizes[c.id] = o.size } }
            .map { c -> lib.all.first { it.id == c.id } }
        lib.removed += origin.map { it.id }
        val byName = clones.associateBy { it.displayName }
        val plan = runBlocking { BackupService.plan(fresh, decoded) }
        assertEquals(3, plan.decisions.size)
        assertEquals(emptyList<String>(), plan.missing.map { it.name }) // QA_5 war nirgends verwendet
        val r = runBlocking { BackupService.apply(fresh, decoded, plan) }
        assertEquals(3, r.decisions); assertEquals(1, r.albumsCreated); assertEquals(0, r.failed)
        val recs = fresh.ledger.stateFlow.value.records
        assertEquals(Disposition.KEPT, recs[byName.getValue("QA_0").reference]?.disposition)
        assertEquals(LocalDay(2026, 12, 24), recs[byName.getValue("QA_1").reference]?.dueDay)
        assertEquals(Disposition.MARKED_FOR_CLEANUP, recs[byName.getValue("QA_2").reference]?.disposition)
        val org = fresh.organize.state.value
        assertEquals(listOf(byName.getValue("QA_0").id, byName.getValue("QA_1").id), org.albums.single().items)
        assertEquals(listOf("Strand"), org.tags[byName.getValue("QA_0").id])
        assertEquals("Oma", org.notes[byName.getValue("QA_1").id])
        assertEquals(listOf(byName.getValue("QA_3").id), org.basket)
        val s = fresh.settings.value
        assertEquals(listOf(byName.getValue("QA_4").id), s.hiddenMedia)
        assertFalse(s.haptics); assertEquals(7, s.laterDefaultDays)
        assertFalse("„Endgültig löschen“ wird nie mit übernommen", s.deletePermanently)
        assertEquals(listOf("w1"), fresh.workflows.items.value.map { it.id })
        // Noch einmal: nichts doppelt, nichts überschrieben.
        val again = runBlocking { BackupService.plan(fresh, decoded) }
        assertTrue(again.decisions.isEmpty()); assertEquals(3, again.decisionsKeptLocal); assertEquals(0, again.newAlbums)
        val r2 = runBlocking { BackupService.apply(fresh, decoded, again) }
        assertEquals(0, r2.decisions); assertEquals(0, r2.albumsCreated); assertEquals(0, r2.workflows)
        assertEquals(1, fresh.organize.state.value.albums.size)
    }

    @Test fun notABackupAndTooNewAreRecognised() {
        assertEquals(BackupCheck.NotABackup, BackupService.decode("{\"hello\":1}"))
        assertEquals(BackupCheck.NotABackup, BackupService.decode("kein json"))
        assertTrue(BackupService.decode("{\"format\":\"photomaid-backup\",\"version\":99,\"createdAt\":1,\"appVersion\":\"9\"}") is BackupCheck.TooNew)
    }
}
