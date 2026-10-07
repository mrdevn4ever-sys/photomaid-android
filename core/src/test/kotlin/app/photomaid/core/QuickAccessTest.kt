package app.photomaid.core

import app.photomaid.core.quickaccess.QuickAccess
import app.photomaid.core.quickaccess.QuickAccessItem
import app.photomaid.core.quickaccess.QuickSourceKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class QuickAccessTest {
    private fun q(k: QuickSourceKind, f: String? = null) = QuickAccessItem(k, f)

    @Test fun defaultsArePhotosAndVideos() {
        assertEquals(listOf(q(QuickSourceKind.PHOTOS), q(QuickSourceKind.VIDEOS)), QuickAccess.defaults)
    }

    @Test fun addRemoveMoveAndLimit() {
        var l = QuickAccess.defaults
        l = QuickAccess.add(l, q(QuickSourceKind.PHOTOS)) // Duplikat
        assertEquals(2, l.size)
        for (k in listOf(QuickSourceKind.SCREENSHOTS, QuickSourceKind.LATEST_PHOTOS, QuickSourceKind.LATEST_VIDEOS, QuickSourceKind.THIS_WEEK)) l = QuickAccess.add(l, q(k))
        assertEquals(QuickAccess.MAX_ITEMS, l.size)
        assertFalse(QuickAccess.canAdd(l, q(QuickSourceKind.FOLDER, "Pictures/A/")))
        l = QuickAccess.move(l, q(QuickSourceKind.THIS_WEEK), -1)
        assertEquals(QuickSourceKind.THIS_WEEK, l[4].kind)
        assertEquals(l, QuickAccess.move(l, q(QuickSourceKind.PHOTOS), -1)) // Rand
        l = QuickAccess.remove(l, q(QuickSourceKind.VIDEOS))
        assertEquals(5, l.size)
        // Zwei verschiedene Ordner sind zwei Schnellzugriffe.
        assertEquals(2, QuickAccess.add(listOf(q(QuickSourceKind.FOLDER, "Pictures/A/")), q(QuickSourceKind.FOLDER, "Pictures/B/")).size)
    }

    @Test fun sanitizeAndPersistence() {
        val raw = listOf(q(QuickSourceKind.PHOTOS), q(QuickSourceKind.PHOTOS), q(QuickSourceKind.FOLDER, null)) + List(8) { q(QuickSourceKind.FOLDER, "Pictures/$it/") }
        val s = QuickAccess.sanitize(raw)
        assertEquals(6, s.size); assertEquals(QuickSourceKind.PHOTOS, s[0].kind)
        // Gespeichert wird nur Art + Ordnerpfad (eine Abfrage), keine Medien-IDs.
        val json = Json.encodeToString(QuickAccessItem.serializer(), q(QuickSourceKind.LATEST_PHOTOS))
        assertEquals("""{"kind":"LATEST_PHOTOS"}""", json)
    }

    @Test fun thisWeekIsLocalCalendarWeek() {
        val zone = ZoneId.of("Europe/Zurich")
        // Mittwoch 30.09.2026 10:00 lokal → Montag 28.09. 00:00 bis Montag 05.10. 00:00 (lokal).
        val (from, to) = QuickAccess.thisWeek(Instant.parse("2026-09-30T08:00:00Z"), zone, Locale.GERMANY)
        assertEquals(Instant.parse("2026-09-27T22:00:00Z"), from)
        assertEquals(Instant.parse("2026-10-04T22:00:00Z"), to)
        // Sonntag 23:30 lokal gehört noch zur Woche, obwohl in UTC schon ein anderer Tag.
        val (f2, _) = QuickAccess.thisWeek(Instant.parse("2026-10-04T21:30:00Z"), zone, Locale.GERMANY)
        assertEquals(from, f2)
        // Region mit Sonntag als Wochenbeginn.
        assertEquals(Instant.parse("2026-09-27T04:00:00Z"), QuickAccess.thisWeek(Instant.parse("2026-09-30T08:00:00Z"), ZoneId.of("America/New_York"), Locale.US).first)
    }
}
