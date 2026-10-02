package app.photomaid.core

import app.photomaid.core.library.Slideshow
import app.photomaid.core.library.SlideshowOrder
import app.photomaid.core.library.SlideshowSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowTest {
    private data class M(val id: Int, val folder: String?, val t: Long?)
    private val items = (0 until 30).map { M(it, listOf("A/", "B/", "C/", null)[it % 4], if (it == 7) null else 1000L - it) }
    private fun run(s: SlideshowSettings, seed: Long = 42) = Slideshow.order(items, s, seed, { it.folder }, { it.t })

    @Test fun chronologicalOldestFirstUnknownLast() {
        val r = run(SlideshowSettings(order = SlideshowOrder.CHRONOLOGICAL))
        assertEquals(7, r.last().id)
        val dated = r.dropLast(1).map { it.t!! }
        assertEquals(dated.sorted(), dated)
    }

    @Test fun randomIsPermutationAndRepeatableWithSeed() {
        val a = run(SlideshowSettings(order = SlideshowOrder.RANDOM))
        assertEquals(items.toSet(), a.toSet()); assertEquals(items.size, a.size)
        assertEquals(a, run(SlideshowSettings(order = SlideshowOrder.RANDOM)))
        assertNotEquals(a, run(SlideshowSettings(order = SlideshowOrder.RANDOM), seed = 7))
    }

    @Test fun albumsStayTogetherInRandomOrder() {
        for (within in listOf(false, true)) {
            val r = run(SlideshowSettings(order = SlideshowOrder.ALBUMS_RANDOM, shuffleWithinAlbum = within))
            assertEquals(items.toSet(), r.toSet())
            // Jeder Ordner bildet genau einen zusammenhängenden Block.
            val blocks = r.map { it.folder }.fold(mutableListOf<String?>()) { acc, f -> if (acc.lastOrNull() != f || acc.isEmpty()) acc.add(f); acc }
            assertEquals(4, blocks.size)
            if (!within) r.groupBy { it.folder }.values.forEach { v -> val ts = v.mapNotNull { it.t }; assertEquals(ts.sorted(), ts) }
        }
        // Verschiedene Startwerte mischen die Ordner verschieden.
        val orders = (1L..8L).map { s -> run(SlideshowSettings(order = SlideshowOrder.ALBUMS_RANDOM), s).map { it.folder }.distinct() }.toSet()
        assertTrue(orders.size > 1)
    }
}
