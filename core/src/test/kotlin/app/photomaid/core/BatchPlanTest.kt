package app.photomaid.core

import app.photomaid.core.batch.BatchOptions
import app.photomaid.core.batch.BatchRules
import app.photomaid.core.batch.BatchSummary
import app.photomaid.core.batch.BatchTool
import app.photomaid.core.batch.GifPlan
import app.photomaid.core.batch.StitchPlan
import app.photomaid.core.edit.EditFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BatchPlanTest {
    @Test fun needsWorkSkipsPointlessCopies() {
        val r = BatchOptions(BatchTool.RESIZE, longEdge = 2048)
        assertTrue(BatchRules.needsWork(r, 4000, 3000, "image/jpeg", false))
        assertFalse(BatchRules.needsWork(r, 2048, 1536, "image/jpeg", false))
        val c = BatchOptions(BatchTool.CONVERT, format = EditFormat.JPEG)
        assertTrue(BatchRules.needsWork(c, 10, 10, "image/heic", false))
        assertFalse(BatchRules.needsWork(c, 10, 10, "image/jpeg", false))
        assertTrue(BatchRules.needsWork(c, 10, 10, "image/png", false))
        val l = BatchOptions(BatchTool.STRIP_LOCATION)
        assertFalse(BatchRules.needsWork(l, 10, 10, "image/jpeg", false)); assertTrue(BatchRules.needsWork(l, 10, 10, "image/jpeg", true))
    }

    @Test fun summaryIsHonest() {
        val s = BatchSummary().add(5_000_000, 1_000_000).add(null, 800_000).add(3_000_000, 900_000)
        assertEquals(3, s.done); assertEquals(1, s.unknownSizes)
        assertEquals(8_000_000, s.beforeBytes); assertEquals(0, s.freedLaterBytes) // Originale nicht im Papierkorb → nichts frei
        assertEquals(1_900_000, s.afterBytes) // unbekanntes Paar nicht in der Bilanz
        assertEquals(0, s.copy(originalsTrashed = 3).freedLaterBytes) // eine Größe unbekannt → keine Zahl
        val k = BatchSummary().add(5_000_000, 1_000_000).add(3_000_000, 900_000)
        assertEquals(8_000_000 - 1_900_000, k.copy(originalsTrashed = 2).freedLaterBytes)
        assertEquals(0, k.copy(originalsTrashed = 1).freedLaterBytes) // nur teilweise im Papierkorb → keine Zahl
        assertEquals(0, BatchSummary(beforeBytes = 1, afterBytes = 5, originalsTrashed = 1).freedLaterBytes)
    }

    @Test fun stitchKeepsAspectAndNeverUpscales() {
        val (size, places) = StitchPlan.layout(listOf(1080 to 2400, 1440 to 3200, 720 to 1600))
        assertEquals(720, size.first)
        places.zip(listOf(1080 to 2400, 1440 to 3200, 720 to 1600)).forEach { (p, s) -> assertTrue(abs(p.width.toDouble() / p.height - s.first.toDouble() / s.second) < 0.01) }
        assertEquals(places.sumOf { it.height }, size.second)
        val tall = StitchPlan.layout(List(20) { 1080 to 2400 })
        assertTrue(tall.first.second <= StitchPlan.MAX_HEIGHT)
    }

    @Test fun gifFramePlacementAndTimes() {
        assertEquals(480 to 360, GifPlan.frame(4000, 3000, 480))
        assertEquals(300 to 200, GifPlan.frame(300, 200, 720)) // nie hochskaliert
        val fit = GifPlan.place(1000, 1000, 480, 360, fill = false)
        assertEquals((fit[2] - fit[0]).toDouble() / (fit[3] - fit[1]), 1.0, 0.01)
        val fill = GifPlan.place(1000, 1000, 480, 360, fill = true)
        assertEquals(480, fill[2] - fill[0]); assertTrue(fill[1] < 0)
        val t = GifPlan.videoTimes(1000, 60_000, 10)
        assertEquals(1000L, t.first()); assertTrue(t.size <= GifPlan.MAX_FRAMES); assertTrue(t.last() < 11_000)
    }
}
