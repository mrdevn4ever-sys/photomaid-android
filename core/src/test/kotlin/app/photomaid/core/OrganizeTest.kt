package app.photomaid.core

import app.photomaid.core.organize.AlbumRules
import app.photomaid.core.organize.BasketRules
import app.photomaid.core.organize.MediaRef
import app.photomaid.core.organize.NoteRules
import app.photomaid.core.organize.Rematch
import app.photomaid.core.organize.TagRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizeTest {
    @Test fun rematchPrefersIdThenUniqueFingerprintAndListsTheRest() {
        val a = MediaRef("content://1", "IMG_1.jpg", 100, 1000)
        val b = MediaRef("content://2", "IMG_2.jpg", 200, 2000)
        val c = MediaRef("content://3", "IMG_3.jpg", null, null) // kein tragfähiger Fingerabdruck
        val gone = MediaRef("content://9", "IMG_9.jpg", 900, 9000)
        // b wurde neu indiziert (neue ID), a unverändert, c verschwunden, gone weg.
        val now = listOf(MediaRef("content://1", "IMG_1.jpg", 100, 1000), MediaRef("content://22", "img_2.JPG", 200, 2000))
        val r = Rematch.resolve(listOf(a, b, c, gone), now) { it }
        assertEquals(setOf("content://1", "content://2"), r.found.keys)
        assertEquals(mapOf("content://2" to "content://22"), r.moved)
        assertEquals(listOf("content://3", "content://9"), r.missing)
    }

    @Test fun rematchNeverGuessesBetweenTwoCandidates() {
        val stored = MediaRef("old", "same.jpg", 5, 7)
        val now = listOf(MediaRef("x", "same.jpg", 5, 7), MediaRef("y", "same.jpg", 5, 7))
        val r = Rematch.resolve(listOf(stored), now) { it }
        assertEquals(listOf("old"), r.missing)
        // Zwei gespeicherte mit gleichem Abdruck, ein Kandidat: ebenfalls nicht raten.
        val r2 = Rematch.resolve(listOf(MediaRef("o1", "same.jpg", 5, 7), MediaRef("o2", "same.jpg", 5, 7)), listOf(MediaRef("x", "same.jpg", 5, 7))) { it }
        assertEquals(listOf("o1", "o2"), r2.missing)
        // Ein bereits über die ID vergebenes Medium wird nicht ein zweites Mal zugeordnet.
        val r3 = Rematch.resolve(listOf(MediaRef("x", "same.jpg", 5, 7), MediaRef("o", "same.jpg", 5, 7)), listOf(MediaRef("x", "same.jpg", 5, 7))) { it }
        assertEquals(listOf("o"), r3.missing)
    }

    @Test fun tagsAreTrimmedDedupedAccentAndCaseInsensitive() {
        assertEquals("Rechnung", TagRules.normalize("  #Rechnung "))
        assertEquals("Noch drucken", TagRules.normalize("Noch   drucken"))
        assertNull(TagRules.normalize(" # "))
        assertEquals(TagRules.MAX_LENGTH, TagRules.normalize("x".repeat(99))!!.length)
        assertEquals(listOf("Café", "Ferien"), TagRules.merge(listOf("Café"), listOf("cafe", "Ferien", "FERIEN", "")))
        assertEquals(listOf("Ferien"), TagRules.remove(listOf("Café", "Ferien"), "CAFE"))
        assertEquals(TagRules.MAX_PER_MEDIA, TagRules.merge(emptyList(), (1..50).map { "t$it" }).size)
    }

    @Test fun tagStateAndCounts() {
        val sel = listOf(listOf("Ferien", "Familie"), listOf("ferien"), emptyList())
        assertEquals(TagRules.State.SOME, TagRules.state("Ferien", sel))
        assertEquals(TagRules.State.ALL, TagRules.state("Ferien", sel.take(2)))
        assertEquals(TagRules.State.NONE, TagRules.state("Wohnung", sel))
        val counts = TagRules.counts(mapOf("a" to listOf("Ferien", "Wohnung"), "b" to listOf("ferien"), "c" to listOf("Auto")))
        assertEquals(listOf("Ferien" to 2, "Auto" to 1, "Wohnung" to 1), counts)
    }

    @Test fun notesAndAlbumNames() {
        assertNull(NoteRules.normalize("   "))
        assertEquals(NoteRules.MAX_LENGTH, NoteRules.normalize("n".repeat(800))!!.length)
        assertEquals("Ferien 2026", AlbumRules.normalizeName("  Ferien   2026 "))
        assertNull(AlbumRules.normalizeName(" "))
        assertEquals("Ferien (2)", AlbumRules.uniqueName(listOf("ferien"), "Ferien"))
        assertEquals("Ferien (3)", AlbumRules.uniqueName(listOf("Ferien", "Ferien (2)"), "Ferien"))
        assertEquals("Ferien", AlbumRules.uniqueName(listOf("Ferien"), "Ferien", ignore = "Ferien")) // Umbenennen auf sich selbst
    }

    @Test fun albumAndBasketKeepOrderAndSkipDuplicates() {
        val (list, added) = AlbumRules.add(listOf("a", "b"), listOf("b", "c", "c", "d"))
        assertEquals(listOf("a", "b", "c", "d"), list); assertEquals(2, added)
        val (basket, n) = BasketRules.add(List(BasketRules.MAX - 1) { "x$it" }, listOf("y", "z"))
        assertEquals(BasketRules.MAX, basket.size); assertEquals(1, n)
        assertTrue(basket.last() == "y")
    }
}
