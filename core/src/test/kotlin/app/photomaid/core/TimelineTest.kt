package app.photomaid.core

import app.photomaid.core.library.SearchText
import app.photomaid.core.library.Timeline
import app.photomaid.core.library.TimelineKey
import app.photomaid.core.library.TimelineLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimelineTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private data class M(val id: String, val at: Instant?)
    private val items = listOf(
        M("a", Instant.parse("2026-10-01T22:30:00Z")), // in Zürich schon 2. Oktober
        M("b", Instant.parse("2026-10-01T08:00:00Z")),
        M("c", Instant.parse("2026-09-15T08:00:00Z")),
        M("d", Instant.parse("2025-12-31T23:30:00Z")), // in Zürich 2026-01-01
        M("e", null),
    )

    @Test fun groupsByDayInLocalTimeNewestFirstUnknownLast() {
        val s = Timeline.sections(items, { it.at }, TimelineLevel.DAY, zone)
        assertEquals(listOf(TimelineKey(2026, 10, 2), TimelineKey(2026, 10, 1), TimelineKey(2026, 9, 15), TimelineKey(2026, 1, 1)), s.dropLast(1).map { it.key })
        assertTrue(s.last().key.unknown)
        assertEquals(listOf("e"), s.last().items.map { it.id })
    }

    @Test fun monthAndYearLevels() {
        val m = Timeline.sections(items, { it.at }, TimelineLevel.MONTH, zone)
        assertEquals(listOf("a", "b"), m.first().items.map { it.id })
        val y = Timeline.sections(items, { it.at }, TimelineLevel.YEAR, zone)
        assertEquals(TimelineKey(2026), y.first().key)
        assertEquals(4, y.first().items.size)
        assertEquals(TimelineLevel.MONTH, TimelineLevel.DAY.coarser())
        assertEquals(TimelineLevel.DAY, TimelineLevel.DAY.finer())
    }

    @Test fun searchIgnoresAccentsCaseAndSharpS() {
        val fields = listOf(SearchText.normalize("Café Zürich Straße"), SearchText.normalize("DCIM/Été 2026/"))
        assertTrue(SearchText.matches(SearchText.tokens("cafe"), fields))
        assertTrue(SearchText.matches(SearchText.tokens("ZURICH strasse"), fields))
        assertTrue(SearchText.matches(SearchText.tokens("ete"), fields))
        assertTrue(SearchText.matches(SearchText.tokens("été"), fields))
        assertFalse(SearchText.matches(SearchText.tokens("cafe bern"), fields))
        assertFalse(SearchText.matches(SearchText.tokens("   "), fields))
        assertEquals("ob", SearchText.normalize("Øb"))
    }
}
