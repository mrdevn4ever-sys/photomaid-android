package app.photomaid.core

import app.photomaid.core.document.Caption
import app.photomaid.core.document.Crop
import app.photomaid.core.document.ImageFit
import app.photomaid.core.document.PageMargin
import app.photomaid.core.document.PageOrientation
import app.photomaid.core.document.PaperSize
import app.photomaid.core.document.PdfLayout
import app.photomaid.core.document.PdfOptions
import app.photomaid.core.document.PdfRules
import app.photomaid.core.document.PdfWriter
import app.photomaid.core.document.PerPage
import app.photomaid.core.document.PtRect
import app.photomaid.core.document.SourceSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfTest {
    private val portrait = SourceSize(3000, 4000)
    private val wide = SourceSize(4000, 2000)

    private fun inside(inner: PtRect, outer: PtRect) =
        inner.x >= outer.x - 1e-6 && inner.y >= outer.y - 1e-6 && inner.x + inner.w <= outer.x + outer.w + 1e-6 && inner.y + inner.h <= outer.y + outer.h + 1e-6

    @Test fun fitNeverDistortsAndStaysInside() {
        for (per in PerPage.entries) for (src in listOf(portrait, wide, SourceSize(1000, 1000))) {
            val pages = PdfLayout.pages(List(per.count) { src }, PdfOptions(perPage = per, fit = ImageFit.FIT, caption = Caption.NUMBER))
            for (p in pages) for (it in p.items) {
                assertEquals("Seitenverhältnis bleibt", src.aspect, it.rect.aspect, 1e-6)
                assertTrue(inside(it.rect, it.cell)); assertEquals(Crop.FULL, it.crop)
                assertTrue("Beschriftung unter dem Bild", it.rect.y + it.rect.h <= it.caption!!.y + 1e-6)
            }
        }
    }

    @Test fun fillCropsInsteadOfStretching() {
        val p = PdfLayout.pages(listOf(wide), PdfOptions(perPage = PerPage.FOUR, fit = ImageFit.FILL)).single()
        val it = p.items.single()
        assertEquals(it.cell, it.rect)
        val cw = (it.crop.right - it.crop.left) * wide.width; val ch = (it.crop.bottom - it.crop.top) * wide.height
        assertEquals("sichtbarer Ausschnitt hat das Verhältnis des Felds", it.rect.aspect, cw / ch, 1e-6)
        assertEquals(0.0, it.crop.top, 0.0); assertEquals(1.0, it.crop.bottom, 0.0)
    }

    @Test fun pagingOrientationAndMargins() {
        val src = listOf(wide, portrait, wide, portrait, portrait)
        val one = PdfLayout.pages(src, PdfOptions())
        assertEquals(5, one.size)
        assertTrue("Querbild → Querseite", one[0].widthPt > one[0].heightPt)
        assertTrue(one[1].heightPt > one[1].widthPt)
        val sheet = PdfLayout.pages(src, PdfOptions(perPage = PerPage.FOUR))
        assertEquals(listOf(4, 1), sheet.map { it.items.size })
        assertTrue("Kontaktbogen automatisch hoch", sheet.all { it.heightPt > it.widthPt })
        val land = PdfLayout.pages(src, PdfOptions(orientation = PageOrientation.LANDSCAPE, perPage = PerPage.SIX, margin = PageMargin.NONE))
        assertTrue(land.all { it.widthPt > it.heightPt })
        assertEquals(0.0, land[0].items[0].cell.x, 1e-9)
        assertEquals(listOf(0, 1, 2, 3, 4), sheet.flatMap { p -> p.items.map { it.index } })
        val letter = PdfLayout.pages(listOf(portrait), PdfOptions(paper = PaperSize.LETTER)).single()
        assertEquals(612.0, letter.widthPt, 0.0); assertEquals(792.0, letter.heightPt, 0.0)
    }

    @Test fun pixelsNeverExceedSource() {
        val small = SourceSize(400, 300)
        val (rect, crop) = PdfLayout.place(small, PtRect(0.0, 0.0, 500.0, 700.0), ImageFit.FIT)
        assertEquals(400 to 300, PdfLayout.pixelsFor(rect, small, crop, 300))
        val (w, h) = PdfLayout.pixelsFor(PtRect(0.0, 0.0, 72.0, 54.0), portrait, Crop.FULL, 200)
        assertEquals(200, w); assertEquals(267, h)
    }

    @Test fun rulesAndNames() {
        assertTrue(PdfRules.needsPro(PdfOptions(perPage = PerPage.FOUR), 2))
        assertTrue(PdfRules.needsPro(PdfOptions(), PdfRules.FREE_MAX + 1))
        assertFalse(PdfRules.needsPro(PdfOptions(perPage = PerPage.TWO), PdfRules.FREE_MAX))
        assertEquals("Rechnung 2026.pdf", PdfRules.fileName(" Rechnung/ 2026 ", "GaleriQ"))
        assertEquals("GaleriQ.pdf", PdfRules.fileName("..", "GaleriQ"))
        assertEquals("a.pdf", PdfRules.fileName("a.PDF".lowercase(), "x"))
    }

    @Test fun writerProducesConsistentPdf() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val bos = ByteArrayOutputStream()
        val w = PdfWriter(bos)
        w.addPage(595.28, 841.89, listOf(PdfWriter.Draw(PdfWriter.Image.Jpeg(jpeg, 300, 400), PtRect(36.0, 36.0, 300.0, 400.0)),
            PdfWriter.Draw(PdfWriter.Image.Gray(ByteArray(20 * 4) { 0x7F }, 20, 4), PtRect(36.0, 440.0, 100.0, 20.0))))
        w.addPage(841.89, 595.28, listOf(PdfWriter.Draw(PdfWriter.Image.Jpeg(jpeg, 400, 200), PtRect(10.0, 10.0, 400.0, 200.0))))
        w.finish()
        val s = String(bos.toByteArray(), Charsets.ISO_8859_1)
        assertTrue(s.startsWith("%PDF-1.4")); assertTrue(s.trimEnd().endsWith("%%EOF"))
        assertEquals(2, Regex("/Type /Page /Parent").findAll(s).count())
        assertTrue(s.contains("/Count 2"))
        assertTrue(s.contains("/MediaBox [0 0 595.28 841.89]"))
        // y in PDF-Koordinaten: 841.89 - (36 + 400) = 405.89
        assertTrue(s.contains("q 300 0 0 400 36 405.89 cm /Im0 Do Q"))
        // Querverweise zeigen genau auf die Objekte.
        val xrefAt = s.substringAfterLast("startxref\n").trim().removeSuffix("%%EOF").trim().toInt()
        val table = s.substring(xrefAt).lines().drop(2).takeWhile { it.endsWith(" n ") || it.endsWith(" f ") }
        table.drop(1).forEachIndexed { i, line ->
            val off = line.substring(0, 10).toInt()
            assertTrue("Objekt ${i + 1} bei $off", s.startsWith("${i + 1} 0 obj", off))
        }
        assertNull("keine Pfade/Namen im Dokument", Regex("/Title|/Author|/Subject").find(s))
    }
}
