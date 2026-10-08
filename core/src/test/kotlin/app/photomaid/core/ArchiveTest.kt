package app.photomaid.core

import app.photomaid.core.archive.ArchiveJournal
import app.photomaid.core.archive.ArchiveJournalRules
import app.photomaid.core.archive.ArchiveList
import app.photomaid.core.archive.ArchiveOptions
import app.photomaid.core.archive.ArchivePlan
import app.photomaid.core.archive.ArchiveSource
import app.photomaid.core.archive.ArchiveSpace
import app.photomaid.core.archive.ArchiveTargets
import app.photomaid.core.archive.BackupAlbum
import app.photomaid.core.archive.BackupCheck
import app.photomaid.core.archive.BackupDecision
import app.photomaid.core.archive.BackupRef
import app.photomaid.core.archive.BackupRefTable
import app.photomaid.core.archive.BackupRestore
import app.photomaid.core.archive.BackupRules
import app.photomaid.core.archive.BackupState
import app.photomaid.core.archive.EntryReason
import app.photomaid.core.archive.EntryState
import app.photomaid.core.archive.JournalEntry
import app.photomaid.core.archive.PhotoMaidBackup
import app.photomaid.core.archive.SpaceCheck
import app.photomaid.core.archive.TargetKind
import app.photomaid.core.archive.toHex
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.PhotoMaidJson
import app.photomaid.core.organize.MediaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ArchiveTest {
    private val utc = ZoneId.of("UTC")

    @Test fun targetKindSeparatesDeviceRemovableAndOtherApps() {
        assertEquals(TargetKind.SAME_DEVICE, ArchiveTargets.classify(ArchiveTargets.EXTERNAL_STORAGE, "primary:DCIM/Archiv"))
        assertEquals(TargetKind.SAME_DEVICE, ArchiveTargets.classify(ArchiveTargets.EXTERNAL_STORAGE, "home:"))
        assertEquals(TargetKind.REMOVABLE, ArchiveTargets.classify(ArchiveTargets.EXTERNAL_STORAGE, "1A2B-3C4D:Archiv"))
        assertEquals(TargetKind.SAME_DEVICE, ArchiveTargets.classify("com.android.providers.downloads.documents", "raw:/x"))
        // Andere Apps (z. B. Online-Speicher) sind nie automatisch „lokal“.
        assertEquals(TargetKind.PROVIDER, ArchiveTargets.classify("com.google.android.apps.docs.storage", "acc=1;doc=2"))
        assertEquals(TargetKind.PROVIDER, ArchiveTargets.classify(null, null))
        assertTrue(TargetKind.REMOVABLE.allowsCleanupOffer)
        assertFalse(TargetKind.SAME_DEVICE.allowsCleanupOffer); assertFalse(TargetKind.PROVIDER.allowsCleanupOffer)
        assertEquals("DCIM/Archiv", ArchiveTargets.displayPath("primary:DCIM/Archiv"))
        assertEquals("", ArchiveTargets.displayPath("1A2B-3C4D:"))
    }

    @Test fun foldersByMonthAndNeverAnInventedDate() {
        val s = ArchiveSource("a", "IMG_1.jpg", "image/jpeg", 10, java.time.Instant.parse("2026-10-07T12:00:00Z").toEpochMilli())
        assertEquals(listOf("2026", "10"), ArchivePlan.folderFor(s, ArchiveOptions(), "Ohne Datum", utc))
        assertEquals(emptyList<String>(), ArchivePlan.folderFor(s, ArchiveOptions(byMonth = false), "Ohne Datum", utc))
        assertEquals(listOf("Ohne Datum"), ArchivePlan.folderFor(s.copy(takenMillis = null), ArchiveOptions(), "Ohne Datum", utc))
    }

    @Test fun folderNamesNeverDependOnTheDeviceLanguage() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar"))
            val s = ArchiveSource("a", "x.jpg", "image/jpeg", 1, java.time.Instant.parse("2026-10-07T12:00:00Z").toEpochMilli())
            assertEquals(listOf("2026", "10"), ArchivePlan.folderFor(s, ArchiveOptions(), "x", utc))
            assertEquals("PhotoMaid_0001.jpg", ArchivePlan.fallbackName(0, "image/jpeg"))
            assertEquals("0aff", byteArrayOf(10, -1).toHex())
            // Gespeicherte Schlüssel ebenso: Tageszähler und „Weitermachen“.
            assertEquals("2026-10-08", app.photomaid.core.model.LocalDay(2026, 10, 8).toString())
            assertEquals("2026-09", app.photomaid.core.progress.MonthKey(2026, 9).toString())
            assertEquals(app.photomaid.core.progress.MonthKey(2026, 9), app.photomaid.core.progress.MonthKey.parse("٢٠٢٦-٠٩"))
        } finally { java.util.Locale.setDefault(before) }
    }

    @Test fun namesAreSafeForMemoryCardsAndUnique() {
        assertEquals("Urlaub_ Strand_.jpg", ArchivePlan.safeName("Urlaub: Strand?.jpg", "x"))
        assertEquals("x", ArchivePlan.safeName("  ..  ", "x"))
        assertEquals("x", ArchivePlan.safeName(null, "x"))
        val long = "a".repeat(300) + ".jpeg"
        val cut = ArchivePlan.safeName(long, "x")
        assertEquals(120, cut.length); assertTrue(cut.endsWith(".jpeg"))
        assertEquals("IMG.jpg", ArchivePlan.uniqueName(setOf("other.jpg"), "IMG.jpg"))
        assertEquals("IMG (2).jpg", ArchivePlan.uniqueName(setOf("img.JPG"), "IMG.jpg"))
        assertEquals("IMG (3).jpg", ArchivePlan.uniqueName(setOf("IMG.jpg", "IMG (2).jpg"), "IMG.jpg"))
        assertEquals("README (2)", ArchivePlan.uniqueName(setOf("README"), "README"))
        assertEquals("PhotoMaid_0007.mp4", ArchivePlan.fallbackName(6, "video/mp4"))
    }

    @Test fun spaceIsOnlyEnoughWhenEverythingIsKnown() {
        assertEquals(SpaceCheck.ENOUGH, ArchiveSpace.check(listOf(10, 20), 30))
        assertEquals(SpaceCheck.NOT_ENOUGH, ArchiveSpace.check(listOf(10, 21), 30))
        assertEquals(SpaceCheck.UNKNOWN, ArchiveSpace.check(listOf(10, null), 30))
        assertEquals(SpaceCheck.NOT_ENOUGH, ArchiveSpace.check(listOf(40, null), 30))
        assertEquals(SpaceCheck.UNKNOWN, ArchiveSpace.check(listOf(10), null))
    }

    @Test fun onlyVerifiedCopiesCountAfterAnInterruption() {
        val j = ArchiveJournal("j", "SD", TargetKind.REMOVABLE, 1, entries = listOf(
            JournalEntry("a", "a.jpg", state = EntryState.VERIFIED, bytes = 100, sha256 = "x"),
            JournalEntry("b", "b.jpg", state = EntryState.ALREADY_THERE, bytes = 50, sha256 = "y"),
            JournalEntry("c", "c.jpg", state = EntryState.COPYING, targetUri = "content://half"),
            JournalEntry("d", "d.jpg"),
            JournalEntry("e", "e.jpg", state = EntryState.FAILED, reason = EntryReason.MISMATCH),
        ))
        val (closed, remove) = ArchiveJournalRules.close(j, EntryReason.INTERRUPTED, 9)
        assertEquals(listOf("content://half"), remove) // nur die eigene halbe Kopie
        assertEquals(9L, closed.finishedAt)
        assertEquals(EntryState.NOT_DONE, closed.entries[2].state); assertNull(closed.entries[2].targetUri)
        assertEquals(EntryReason.INTERRUPTED, closed.entries[3].reason)
        assertEquals(listOf("a", "b"), closed.verified.map { it.sourceId })
        val s = closed.summary
        assertEquals(5, s.total); assertEquals(1, s.verified); assertEquals(1, s.alreadyThere); assertEquals(1, s.failed); assertEquals(2, s.notDone)
        assertEquals(150L, s.verifiedBytes)
        // Journal übersteht das Speichern (Prozessende).
        assertEquals(closed, PhotoMaidJson.decodeFromString(ArchiveJournal.serializer(), PhotoMaidJson.encodeToString(ArchiveJournal.serializer(), closed)))
    }

    @Test fun listIsValidCsvAndNeverAFormula() {
        val csv = ArchiveList.render(listOf("Ordner", "Name"), listOf(ArchiveList.Row("2026/10", "a,b.jpg", "2026-10-07", "12", "ab",
            "Ferien", "=HYPERLINK(\"x\")", "Zeile 1\nZeile 2")))
        assertTrue(csv.startsWith("﻿Ordner,Name\r\n"))
        assertTrue(csv.contains("\"a,b.jpg\""))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"x\"\")\""))
        assertTrue(csv.contains("\"Zeile 1\nZeile 2\""))
        assertEquals("'-1", ArchiveList.cell("-1")); assertEquals("x", ArchiveList.cell("x"))
    }

    @Test fun backupChecksFormatAndVersion() {
        assertEquals(BackupCheck.NotABackup, BackupRules.check(null))
        assertEquals(BackupCheck.NotABackup, BackupRules.check(PhotoMaidBackup(format = "x", createdAt = 1, appVersion = "1")))
        assertEquals(BackupCheck.TooNew(9), BackupRules.check(PhotoMaidBackup(version = 9, createdAt = 1, appVersion = "1")))
        assertTrue(BackupRules.check(PhotoMaidBackup(createdAt = 1, appVersion = "1")) is BackupCheck.Ok)
        assertEquals(BackupState.KEPT, BackupRules.state(Disposition.KEPT))
        assertNull(BackupRules.state(Disposition.UNDECIDED)); assertNull(BackupRules.state(Disposition.REMOVED))
    }

    @Test fun restoreFindsMediaOnANewPhoneAndNeverOverwrites() {
        val t = BackupRefTable()
        val kept = t.add(MediaRef("old/1", "IMG_1.jpg", 100, 1000))
        val later = t.add(MediaRef("old/2", "IMG_2.jpg", 200, 2000))
        val decidedHere = t.add(MediaRef("old/3", "IMG_3.jpg", 300, 3000))
        val gone = t.add(MediaRef("old/4", "IMG_4.jpg", 400, 4000))
        val twin = t.add(MediaRef("old/5", "IMG_5.jpg", 500, 5000))
        assertEquals(kept, t.add(MediaRef("old/1", "IMG_1.jpg", 100, 1000))) // jede ID genau einmal
        val b = PhotoMaidBackup(createdAt = 1, appVersion = "1", refs = t.refs(),
            decisions = listOf(BackupDecision(kept, BackupState.KEPT), BackupDecision(later, BackupState.LATER, LocalDay(2026, 10, 20)),
                BackupDecision(decidedHere, BackupState.MARKED), BackupDecision(gone, BackupState.KEPT), BackupDecision(twin, BackupState.KEPT)),
            albums = listOf(BackupAlbum("Ferien", 1, listOf(kept, gone)), BackupAlbum("Neu", 1, listOf(later))),
            tags = mapOf(kept to listOf("#Strand", "strand")), notes = mapOf(kept to "Notiz", later to "Andere"),
            basket = listOf(later), hiddenMedia = listOf(gone))
        // Neues Gerät: andere IDs, gleicher Fingerabdruck. IMG_5 gibt es zweimal → nicht eindeutig.
        val current = listOf(MediaRef("new/1", "IMG_1.jpg", 100, 1000), MediaRef("new/2", "img_2.JPG", 200, 2000),
            MediaRef("new/3", "IMG_3.jpg", 300, 3000), MediaRef("new/5a", "IMG_5.jpg", 500, 5000), MediaRef("new/5b", "IMG_5.jpg", 500, 5000))
        val p = BackupRestore.plan(b, current, { it }, dispositions = mapOf("new/3" to Disposition.KEPT),
            albumNames = listOf("ferien"), notesPresent = setOf("new/2"))
        assertEquals(setOf("new/1", "new/2"), p.decisions.keys)
        assertEquals(LocalDay(2026, 10, 20), p.decisions.getValue("new/2").due)
        assertEquals(1, p.decisionsKeptLocal) // hier schon entschieden → bleibt
        assertEquals(listOf("Ferien" to listOf("new/1"), "Neu" to listOf("new/2")), p.albums)
        assertEquals(1, p.newAlbums) // „Ferien“ gibt es schon (ohne Groß/klein) → wird ergänzt
        assertEquals(mapOf("new/1" to listOf("Strand")), p.tags)
        assertEquals(mapOf("new/1" to "Notiz"), p.notes) // vorhandene Notiz bleibt
        assertEquals(listOf("new/2"), p.basket)
        assertEquals(emptyList<String>(), p.hidden)
        assertEquals(setOf("old/4", "old/5"), p.missing.map { it.id }.toSet()) // sichtbar aufgelistet, nie geraten
        assertFalse(p.isEmpty)
        // Gleiches Gerät: IDs passen direkt.
        val same = BackupRestore.plan(b, listOf(MediaRef("old/4", "IMG_4.jpg", 400, 4000)), { it }, emptyMap(), emptyList(), emptySet())
        assertEquals(listOf("old/4"), same.hidden)
        assertEquals(setOf("old/4"), same.decisions.keys)
    }

    @Test fun backupSurvivesJson() {
        val b = PhotoMaidBackup(createdAt = 5, appVersion = "0.1.0", refs = listOf(BackupRef("a", "x.jpg", 1, 2)),
            decisions = listOf(BackupDecision(0, BackupState.LATER, bookmarked = true)), tags = mapOf(0 to listOf("t")))
        val back = PhotoMaidJson.decodeFromString(PhotoMaidBackup.serializer(), PhotoMaidJson.encodeToString(PhotoMaidBackup.serializer(), b))
        assertEquals(b, back)
    }
}
