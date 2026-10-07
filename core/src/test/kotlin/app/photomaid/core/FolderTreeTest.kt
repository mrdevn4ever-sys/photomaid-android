package app.photomaid.core

import app.photomaid.core.library.FolderEntry
import app.photomaid.core.library.FolderListSort
import app.photomaid.core.library.FolderSort
import app.photomaid.core.library.FolderTree
import app.photomaid.core.library.NaturalOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTreeTest {
    private val entries = listOf(
        FolderEntry("DCIM/Camera/", 120, 500),
        FolderEntry("Pictures/Screenshots/", 30, 900),
        FolderEntry("Pictures/Urlaub/", 10, 100),
        FolderEntry("Pictures/Urlaub/Strand/", 5, 300),
        FolderEntry("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", 40, 700),
        FolderEntry("Pictures/Leer/", 0, null),
    )

    @Test fun buildsTreeWithTotalsAndCollapsedChains() {
        val roots = FolderTree.build(entries, FolderListSort.NAME)
        // DCIM hat nur Camera → zusammengefasst; Android/…/WhatsApp Images ebenso.
        assertEquals(listOf("Camera", "Pictures", "WhatsApp Images"), roots.map { it.label })
        val pictures = roots[1]
        assertEquals(0, pictures.own)
        assertEquals(45, pictures.total)
        assertEquals(listOf("Screenshots", "Urlaub"), pictures.children.map { it.label })
        val urlaub = pictures.children[1]
        assertEquals(10, urlaub.own); assertEquals(15, urlaub.total); assertEquals(300L, urlaub.latestMs)
        assertEquals(1, urlaub.depth); assertEquals(2, urlaub.children.single().depth)
        assertEquals("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", roots[2].path)
        assertNull("leere Ordner erscheinen nicht", FolderTree.find(roots, "Pictures/Leer"))
    }

    @Test fun sortsByRecentAndCount() {
        assertEquals(listOf("Pictures", "WhatsApp Images", "Camera"), FolderTree.build(entries, FolderListSort.RECENT).map { it.label })
        assertEquals(listOf("Camera", "Pictures", "WhatsApp Images"), FolderTree.build(entries, FolderListSort.COUNT).map { it.label })
    }

    @Test fun rowsFollowExpansion() {
        val roots = FolderTree.build(entries, FolderListSort.NAME)
        assertEquals(3, FolderTree.rows(roots, emptySet()).size)
        assertEquals(listOf("Camera", "Pictures", "Screenshots", "Urlaub", "WhatsApp Images"),
            FolderTree.rows(roots, setOf("pictures/")).map { it.label })
        assertEquals(6, FolderTree.rows(roots, setOf("pictures/", "pictures/urlaub/")).size)
    }

    @Test fun searchFindsDeepFoldersWithoutAccents() {
        val roots = FolderTree.build(listOf(FolderEntry("Pictures/Café/Été/", 2, 1), FolderEntry("DCIM/Camera/", 1, 1)))
        assertEquals(listOf("Été"), FolderTree.search(roots, "ete").map { it.label })
        assertEquals(0, FolderTree.search(roots, "ete").single().depth)
        assertEquals(roots, FolderTree.search(roots, "  "))
    }

    @Test fun containsRespectsSubfolders() {
        assertTrue(FolderTree.contains("Pictures/Urlaub", "pictures/urlaub/Strand/"))
        assertFalse(FolderTree.contains("Pictures/Urlaub/", "Pictures/Urlaub/Strand/", includeSubfolders = false))
        assertTrue(FolderTree.contains("Pictures/Urlaub/", "Pictures/Urlaub/", includeSubfolders = false))
        assertFalse(FolderTree.contains("Pictures/Url", "Pictures/Urlaub/"))
        assertFalse(FolderTree.contains("", "Pictures/"))
        assertFalse(FolderTree.contains("Pictures/", null))
    }

    private data class M(val n: String?, val d: Long?, val a: Long?, val s: Long?)

    @Test fun mediaSortKeepsUnknownLast() {
        val items = listOf(M("IMG_10", 3, 1, null), M("IMG_2", 1, 3, 500), M(null, null, null, 900), M("img_1", 2, 2, 100))
        fun sorted(s: FolderSort) = s.sort(items, { it.d }, { it.a }, { it.n }, { it.s }).map { it.n }
        assertEquals(listOf("IMG_10", "img_1", "IMG_2", null), sorted(FolderSort.NEWEST))
        assertEquals(listOf("IMG_2", "img_1", "IMG_10", null), sorted(FolderSort.OLDEST))
        assertEquals(listOf("IMG_2", "img_1", "IMG_10", null), sorted(FolderSort.ADDED))
        assertEquals(listOf("img_1", "IMG_2", "IMG_10", null), sorted(FolderSort.NAME))
        // Unbekannte Größe ist nie 0 und nie die größte: ans Ende.
        assertEquals(listOf(null, "IMG_2", "img_1", "IMG_10"), sorted(FolderSort.LARGEST))
    }

    @Test fun naturalOrder() {
        assertEquals(listOf("a2", "A10", "b1"), listOf("A10", "b1", "a2").sortedWith(NaturalOrder))
        assertTrue(NaturalOrder.compare("Été", "ete") == 0)
    }
}
