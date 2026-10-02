package app.photomaid.core

import app.photomaid.core.edit.EditGeometry
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.Homography
import app.photomaid.core.edit.NormPoint
import app.photomaid.core.edit.PerspectiveMath
import app.photomaid.core.edit.PixelPipeline
import app.photomaid.core.edit.PxSize
import app.photomaid.core.edit.Quad
import app.photomaid.core.edit.RedEyeSpot
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

import org.junit.Assert.assertTrue

class PerspectiveTest {
    private val photo = PxSize(4000, 3000)

    @Test fun homographyMapsFourPointsExactly() {
        val src = listOf(10.0 to 20.0, 300.0 to 5.0, 320.0 to 260.0, 0.0 to 240.0)
        val dst = listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 80.0, 0.0 to 80.0)
        val h = Homography.fromPoints(src, dst)!!
        for (i in 0..3) {
            val (x, y) = h.map(src[i].first, src[i].second)
            assertEquals(dst[i].first, x, 1e-6); assertEquals(dst[i].second, y, 1e-6)
        }
        // Inverse rückwärts
        val (bx, by) = h.inverse().map(100.0, 80.0)
        assertEquals(320.0, bx, 1e-6); assertEquals(260.0, by, 1e-6)
    }

    @Test fun identityQuadChangesNothing() {
        val a = EditGeometry(photo, EditRecipe(straighten = 3f))
        val b = EditGeometry(photo, EditRecipe(straighten = 3f, perspective = Quad.FULL))
        assertEquals(a.frame, b.frame)
        val (x1, y1) = a.sourceToOutput.map(123.0, 456.0); val (x2, y2) = b.sourceToOutput.map(123.0, 456.0)
        assertEquals(x1, x2, 1e-6); assertEquals(y1, y2, 1e-6)
    }

    @Test fun keystoneMapsQuadCornersToFrameCornersAndKeepsPixelBudget() {
        val q = PerspectiveMath.keystone(60f, 0f)
        val g = EditGeometry(photo, EditRecipe(perspective = q))
        assertTrue(g.frame.pixels <= photo.pixels)
        // Obere linke Ecke des Vierecks (Quelle) landet in der Ecke (0,0) des Ergebnisses.
        val (x, y) = g.sourceToOutput.map(q.tl.x * 4000.0, q.tl.y * 3000.0)
        assertEquals(0.0, x, 1e-3); assertEquals(0.0, y, 1e-3)
        val (x2, y2) = g.sourceToOutput.map(q.br.x * 4000.0, q.br.y * 3000.0)
        assertEquals(g.output.width.toDouble(), x2, 1e-3); assertEquals(g.output.height.toDouble(), y2, 1e-3)
        // Benötigter Quellbereich deckt das Viereck.
        val sb = g.sourceBounds()
        assertTrue(sb[0] <= (q.bl.x * 4000).toInt() && sb[2] >= (q.br.x * 4000).toInt() - 1)
    }

    @Test fun rotatingKeepsPerspectiveOnTheSameImageContent() {
        val q = Quad(NormPoint(0.1f, 0.05f), NormPoint(0.85f, 0.1f), NormPoint(0.95f, 0.9f), NormPoint(0.05f, 0.95f))
        val r0 = EditRecipe(perspective = q)
        val r1 = r0.rotatedClockwise()
        val g0 = EditGeometry(photo, r0); val g1 = EditGeometry(photo, r1)
        // Gleicher Quellpunkt → im gedrehten Ergebnis an der um 90° gedrehten Stelle.
        val (x0, y0) = g0.sourceToOutput.map(2100.0, 1300.0)
        val (x1, y1) = g1.sourceToOutput.map(2100.0, 1300.0)
        assertEquals(g0.output.width.toDouble(), g1.output.height.toDouble(), 2.0)
        assertEquals(g1.output.width - y0, x1, 3.0); assertEquals(x0, y1, 3.0)
    }

    @Test fun invalidCornerMoveIsRejected() {
        val q = Quad.FULL
        // Ecke über die gegenüberliegende Seite ziehen → Viereck würde sich überschlagen.
        assertEquals(q, PerspectiveMath.moveCorner(q, 0, 1f, 1f))
        assertFalse(PerspectiveMath.isValid(Quad(NormPoint(0f, 0f), NormPoint(0.05f, 0f), NormPoint(0.05f, 1f), NormPoint(0f, 1f))))
        val moved = PerspectiveMath.moveCorner(q, 0, 0.2f, 0.1f)
        assertEquals(NormPoint(0.2f, 0.1f), moved.tl)
    }

    @Test fun redEyeRemovesRedInsideOnlyAndSparesSkin() {
        val w = 100; val h = 100
        val red = 0xFFD02020.toInt(); val skin = 0xFFE0A88C.toInt()
        val px = IntArray(w * h) { i -> val x = i % w; if (x < 50) red else skin }
        val r = EditRecipe(redEyes = listOf(RedEyeSpot(NormPoint(0.5f, 0.5f), 0.3f)))
        PixelPipeline(r).apply(px, w, h)
        fun R(p: Int) = (p shr 16) and 0xFF
        assertTrue("Pupille ohne Rot: ${R(px[50 * w + 40])}", R(px[50 * w + 40]) < 60)
        assertEquals(skin, px[50 * w + 60]) // Haut im Kreis bleibt
        assertEquals(red, px[50 * w + 5]) // außerhalb des Kreises bleibt alles
    }

    @Test fun mixerDesaturationKeepsLuminance() {
        val blue = 0xFF7FB3F5.toInt()
        val r = EditRecipe(colorMix = app.photomaid.core.edit.ColorMix().with(app.photomaid.core.edit.ColorBand.BLUE, app.photomaid.core.edit.HslShift(saturation = -100f)))
        val out = PixelPipeline(r).applyPoint(blue)
        fun ch(p: Int, s: Int) = (p shr s) and 0xFF
        fun luma(p: Int) = 0.2126 * ch(p, 16) + 0.7152 * ch(p, 8) + 0.0722 * ch(p, 0)
        assertEquals(luma(blue), luma(out), 4.0)
        // deutlich weniger bunt
        assertTrue(ch(out, 0) - ch(out, 16) < (ch(blue, 0) - ch(blue, 16)) / 2)
    }
}
