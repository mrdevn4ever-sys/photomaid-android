package app.photomaid.core

import app.photomaid.core.edit.NormRect
import app.photomaid.core.studio.CellFit
import app.photomaid.core.studio.CollageItem
import app.photomaid.core.studio.CollageKind
import app.photomaid.core.studio.CollageLayout
import app.photomaid.core.studio.CollageProject
import app.photomaid.core.studio.StripLayout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CollageTest {
    private fun area(r: NormRect) = r.width * r.height

    @Test fun everyTemplateTilesTheSquareWithoutGaps() {
        for (n in 2..20) {
            val ts = CollageLayout.templatesFor(n)
            assertTrue("n=$n", ts.isNotEmpty())
            for (t in ts) {
                val cells = CollageLayout.cells(CollageLayout.template(t, n))
                assertEquals("$t n=$n", n, cells.size)
                assertEquals("$t n=$n", (0 until n).toSet(), cells.keys)
                assertEquals("$t n=$n", 1f, cells.values.sumOf { area(it).toDouble() }.toFloat(), 1e-3f)
            }
        }
        assertTrue(CollageLayout.templatesFor(3).size >= 6)
    }

    @Test fun movingADividerResizesNeighboursAndClamps() {
        val tree = CollageLayout.template("c:1,1", 2) // zwei Spalten
        val d = CollageLayout.dividers(tree).single()
        assertTrue(d.vertical); assertEquals(0.5f, d.position, 1e-5f)
        val moved = CollageLayout.moveDivider(tree, d, 0.7f)
        val cells = CollageLayout.cells(moved)
        assertEquals(0.7f, cells[0]!!.width, 1e-4f); assertEquals(0.3f, cells[1]!!.width, 1e-4f)
        // Nie kleiner als die Mindestgröße
        val edge = CollageLayout.cells(CollageLayout.moveDivider(tree, d, 0.99f))
        assertTrue(edge[1]!!.width >= 0.05f - 1e-4f)
        // Verschachtelt: innere Linie gezielt verschieben
        val t3 = CollageLayout.template("r:1,2", 3)
        val inner = CollageLayout.dividers(t3).first { it.path.isNotEmpty() }
        val c3 = CollageLayout.cells(CollageLayout.moveDivider(t3, inner, inner.parent.left + inner.parent.width * 0.25f))
        assertEquals(0.25f, c3[1]!!.width, 1e-3f)
    }

    @Test fun spacingKeepsCellsInsideAndApart() {
        val p = CollageProject.create("x", listOf("a", "b", "c", "d")).copy(spacing = 0.04f, margin = 0.05f, aspect = 1f)
        val rects = p.cellRects()
        assertTrue(rects.all { it.left >= 0.05f - 1e-4f && it.right <= 0.95f + 1e-4f })
        val (a, b) = rects[0] to rects[1]
        assertTrue(b.left - a.right >= 0.04f - 1e-3f || b.top - a.bottom >= 0.04f - 1e-3f)
    }

    @Test fun swapRemoveAddAndJson() {
        val p = CollageProject.create("x", listOf("a", "b", "c"))
        assertEquals(listOf("b", "a", "c"), p.swap(0, 1).items.map { it.mediaId })
        val r = p.removeItem(1); assertEquals(2, r.count); assertEquals(2, CollageLayout.leafCount(r.tree))
        val add = p.addItems(listOf("d", "e")); assertEquals(5, CollageLayout.leafCount(add.tree))
        val j = Json.encodeToString(CollageProject.serializer(), add)
        assertEquals(add, Json.decodeFromString(CollageProject.serializer(), j))
        assertEquals(1, p.removeItem(0).removeItem(0).count) // nie leer
        assertEquals(1, p.removeItem(0).removeItem(0).removeItem(0).count)
    }

    @Test fun cellFitCoversAndPans() {
        // Querformat-Foto (2:1) in quadratisches Feld: Ausschnitt halb so breit, mittig.
        val s = CellFit.source(2f, 1f, 1f, 0f, 0f, fit = false)
        assertEquals(0.5f, s.width, 1e-4f); assertEquals(0.25f, s.left, 1e-4f)
        val right = CellFit.source(2f, 1f, 1f, 1f, 0f, fit = false); assertEquals(1f, right.right, 1e-4f)
        val zoomed = CellFit.source(1f, 1f, 2f, 0f, 0f, false); assertEquals(0.5f, zoomed.width, 1e-4f)
        assertEquals(NormRect.FULL, CellFit.source(2f, 1f, 3f, 1f, 1f, fit = true))
        val fr = CellFit.fitRect(2f, 1f); assertEquals(0.5f, fr.height, 1e-4f)
    }

    @Test fun stripStacksScreenshotsWithTrims() {
        val items = listOf(CollageItem("a", trimStart = 0.1f), CollageItem("b", trimEnd = 0.2f))
        val (size, place) = StripLayout.layout(listOf(1080 to 2400, 1080 to 2400), items, vertical = true, spacingPx = 0, targetCross = 1080)
        assertEquals(1080, size.first)
        assertEquals((2400 * 0.9f + 2400 * 0.8f).toInt(), size.second, )
        assertEquals(0.1f, place[0].second.top, 1e-4f)
        assertEquals(1f, place[1].first.bottom, 1e-3f)
        // Sehr lang: wird gleichmäßig verkleinert, bleibt unter der Grenze.
        val many = (1..20).map { CollageItem("$it") }
        val (s2, _) = StripLayout.layout(many.map { 1080 to 2400 }, many, true, 0, 1080, maxEdge = 16_000)
        assertTrue(s2.second <= 16_000 && s2.first < 1080)
        assertTrue(abs(s2.second - 16_000) < 200)
    }

    @Test fun freeCollageStartsSpreadOut() {
        val p = CollageProject.create("f", listOf("a", "b", "c", "d"), CollageKind.FREE)
        assertEquals(4, p.items.map { it.center }.toSet().size)
    }
}
