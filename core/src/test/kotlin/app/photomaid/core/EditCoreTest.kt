package app.photomaid.core

import app.photomaid.core.edit.Adjustments
import app.photomaid.core.edit.Affine
import app.photomaid.core.edit.AspectPreset
import app.photomaid.core.edit.ColorMatrices
import app.photomaid.core.edit.CropMath
import app.photomaid.core.edit.EditFormat
import app.photomaid.core.edit.EditGeometry
import app.photomaid.core.edit.EditNames
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.FilterId
import app.photomaid.core.edit.NormRect
import app.photomaid.core.edit.PxSize
import app.photomaid.core.edit.ResizeRules
import app.photomaid.core.edit.Sharpen
import app.photomaid.core.edit.TrimRange
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Editor-Kern: Geometrie, Ausschnitt, Größe, Farbe, Schärfen – reine Mathematik. */
class EditCoreTest {
    private val photo = PxSize(4000, 3000)
    private fun near(a: Double, b: Double, eps: Double = 0.5) = assertTrue("$a ≈ $b", abs(a - b) <= eps)

    @Test fun identityRecipeKeepsSizeAndPixelsInPlace() {
        val g = EditGeometry(photo, EditRecipe())
        assertEquals(photo, g.output)
        val (x, y) = g.sourceToOutput.map(123.0, 456.0)
        near(x, 123.0); near(y, 456.0)
        assertTrue(EditRecipe().isIdentity)
    }

    @Test fun quarterTurnSwapsFrameAndMapsCorners() {
        val g = EditGeometry(photo, EditRecipe(quarterTurns = 1))
        assertEquals(PxSize(3000, 4000), g.output)
        // Linke obere Ecke der Quelle landet nach 90° im Uhrzeigersinn rechts oben.
        val (x, y) = g.sourceToOutput.map(0.0, 0.0)
        near(x, 3000.0); near(y, 0.0)
        assertTrue(EditRecipe(quarterTurns = 1).isOrientationOnly)
        assertFalse(EditRecipe(quarterTurns = 1).usesProTools)
    }

    @Test fun flipMirrorsHorizontally() {
        val (x, _) = EditGeometry(photo, EditRecipe(flipH = true)).sourceToOutput.map(0.0, 10.0)
        near(x, 4000.0)
    }

    @Test fun straightenNeverShowsEmptyCorners() {
        for (deg in listOf(-45f, -12.5f, 3f, 30f, 45f)) {
            val g = EditGeometry(photo, EditRecipe(straighten = deg))
            val inv = g.sourceToOutput.inverse()
            for ((x, y) in listOf(0.0 to 0.0, 4000.0 to 0.0, 0.0 to 3000.0, 4000.0 to 3000.0)) {
                val (sx, sy) = inv.map(x, y)
                assertTrue("deg=$deg Ecke ($x,$y) → ($sx,$sy) liegt im Bild", sx >= -0.5 && sx <= 4000.5 && sy >= -0.5 && sy <= 3000.5)
            }
        }
        near(EditGeometry.coverScale(4000.0, 3000.0, 0.0), 1.0, 1e-9)
    }

    @Test fun cropAndResizeComposeIntoOutput() {
        val r = EditRecipe(crop = NormRect(0.25f, 0.5f, 0.75f, 1f), resizeWidth = 1000, resizeHeight = 750)
        val g = EditGeometry(photo, r)
        assertEquals(PxSize(2000, 1500), g.cropSize)
        assertEquals(PxSize(1000, 750), g.output)
        val (x, y) = g.sourceToOutput.map(1000.0, 1500.0) // linke obere Ecke des Ausschnitts
        near(x, 0.0); near(y, 0.0)
        assertArrayEquals(intArrayOf(1000, 1500, 3000, 3000), g.sourceBounds())
        assertTrue(r.usesProTools)
    }

    @Test fun resizeNeverUpscales() {
        val g = EditGeometry(photo, EditRecipe(resizeWidth = 9000, resizeHeight = 9000))
        assertEquals(photo, g.output)
        assertEquals(listOf(2048, 1080), ResizeRules.presetsFor(PxSize(3000, 2000)))
        assertEquals(PxSize(2048, 1536), ResizeRules.forLongEdge(photo, 2048))
        assertEquals(PxSize(1536, 2048), ResizeRules.forLongEdge(PxSize(3000, 4000), 2048))
        assertEquals(PxSize(4000, 3000), ResizeRules.forWidth(photo, 8000))
        assertEquals(PxSize(1000, 750), ResizeRules.forHeight(photo, 750))
    }

    @Test fun rotatingKeepsCropOnSameImageArea() {
        val left = EditRecipe(crop = NormRect(0f, 0f, 0.5f, 1f)) // linke Hälfte
        val turned = left.rotatedClockwise()
        assertEquals(NormRect(0f, 0f, 1f, 0.5f), turned.crop) // nach 90° im Uhrzeigersinn: obere Hälfte
        assertEquals(1, turned.turns)
    }

    @Test fun aspectFitAndDragKeepRatio() {
        val sq = CropMath.fitAspect(NormRect.FULL, 1.0, photo)
        near(((sq.right - sq.left) * 4000).toDouble(), 3000.0, 1.0); near(((sq.bottom - sq.top) * 3000).toDouble(), 3000.0, 1.0)
        assertEquals(1.0, CropMath.ratio(AspectPreset.SQUARE, photo, false)!!, 1e-9)
        assertEquals(4.0 / 5.0, CropMath.ratio(AspectPreset.R4_5, photo, false)!!, 1e-9)
        assertEquals(5.0 / 4.0, CropMath.ratio(AspectPreset.R4_5, photo, true)!!, 1e-9)
        assertEquals(4000.0 / 3000.0, CropMath.ratio(AspectPreset.ORIGINAL, photo, false)!!, 1e-9)
        val dragged = CropMath.drag(sq, CropMath.Handle.TOP_LEFT, 0.1f, 0.05f, photo, 1.0)
        near(((dragged.right - dragged.left) * 4000).toDouble(), ((dragged.bottom - dragged.top) * 3000).toDouble(), 2.0)
        assertTrue(dragged.left >= 0f && dragged.top >= 0f && dragged.right <= 1f && dragged.bottom <= 1f)
        // Freies Ziehen hält Mindestgröße und Rahmen.
        val tiny = CropMath.drag(NormRect.FULL, CropMath.Handle.RIGHT, -5f, 0f, photo, null)
        assertTrue(tiny.width > 0.05f)
        val moved = CropMath.drag(NormRect(0.2f, 0.2f, 0.6f, 0.6f), CropMath.Handle.MOVE, 0.9f, -0.9f, photo, null)
        listOf(moved.left to 0.6f, moved.top to 0f, moved.right to 1f, moved.bottom to 0.4f).forEach { (a, b) -> assertEquals(b, a, 1e-5f) }
    }

    @Test fun colorMatricesAreNeutralAtZeroAndMonoIsGray() {
        assertArrayEquals(ColorMatrices.IDENTITY, ColorMatrices.forRecipe(EditRecipe()), 1e-5f)
        val red = 0xFFFF0000.toInt()
        val gray = ColorMatrices.apply(ColorMatrices.filter(FilterId.MONO), red)
        val r = (gray shr 16) and 0xFF; val g = (gray shr 8) and 0xFF; val b = gray and 0xFF
        assertTrue(r == g && g == b)
        assertEquals(0xFF, (gray ushr 24) and 0xFF)
        // Stärke 0 = kein Filter.
        assertArrayEquals(ColorMatrices.IDENTITY, ColorMatrices.forRecipe(EditRecipe(filter = FilterId.SEPIA, filterIntensity = 0f)), 1e-5f)
        // Heller = größere Werte, Grenzen eingehalten.
        val mid = 0xFF808080.toInt()
        assertTrue(((ColorMatrices.apply(ColorMatrices.brightness(50f), mid) shr 16) and 0xFF) > 0x80)
        assertEquals(0xFF, (ColorMatrices.apply(ColorMatrices.brightness(100f), 0xFFFFFFFF.toInt()) shr 16) and 0xFF)
        // Wärme: mehr Rot als Blau.
        val w = ColorMatrices.apply(ColorMatrices.warmth(100f), mid)
        assertTrue(((w shr 16) and 0xFF) > (w and 0xFF))
        assertTrue(EditRecipe(adjust = Adjustments(contrast = 20f)).hasColor)
    }

    @Test fun sharpenKeepsFlatAreasAndBoostsEdges() {
        val w = 12; val h = 8
        val flat = IntArray(w * h) { 0xFF808080.toInt() }
        assertArrayEquals(flat, Sharpen.apply(flat, w, h, 1f, 1))
        val edge = IntArray(w * h) { i -> if (i % w < w / 2) 0xFF404040.toInt() else 0xFFC0C0C0.toInt() }
        val out = Sharpen.apply(edge, w, h, 1f, 1)
        val darkSide = (out[w / 2 - 1] shr 16) and 0xFF; val brightSide = (out[w / 2] shr 16) and 0xFF
        assertTrue(darkSide < 0x40 && brightSide > 0xC0)
    }

    @Test fun trimRangeClampsToVideo() {
        assertEquals(TrimRange(0, 1_000), TrimRange.clamp(-50, 200, 10_000))
        assertEquals(TrimRange(9_000, 10_000), TrimRange.clamp(9_800, 12_000, 10_000))
        assertEquals(TrimRange(2_000, 7_000), TrimRange.clamp(2_000, 7_000, 10_000))
    }

    @Test fun formatsAndNames() {
        assertEquals(EditFormat.PNG, EditFormat.forSource("image/png"))
        assertEquals(EditFormat.JPEG, EditFormat.forSource("image/heic"))
        assertEquals("IMG_1_bearbeitet.jpg", EditNames.copyName("IMG_1.HEIC", "bearbeitet", "jpg"))
        assertEquals("a_b_bearbeitet.png", EditNames.copyName("a/b.png", "bearbeitet", "png"))
        assertEquals("IMG_1.jpg", EditNames.sameName("IMG_1.HEIC", "jpg"))
    }

    /** „Original ersetzen“ im Feed: neue Fassung an derselben Stelle, keine Entscheidung, Zählung bleibt. */
    @Test fun feedSessionReplacesEditedItemInPlace() {
        val a = app.photomaid.core.model.ItemReference.photo("a"); val b = app.photomaid.core.model.ItemReference.photo("b")
        val n = app.photomaid.core.model.ItemReference.photo("a2")
        val s = app.photomaid.core.feed.FeedSession(items = listOf(a, b))
        s.markPreviewReady(a)
        s.replaceItem(a, n)
        assertEquals(listOf(n, b), s.items)
        assertEquals(n, s.current)
        assertEquals(0, s.summary.reviewed)
        assertFalse(s.isPreviewReady(n)) // erst nach geladener Vorschau entscheidbar
        s.replaceItem(n, b) // schon in der Liste: nichts doppelt
        assertEquals(listOf(n, b), s.items)
    }

    @Test fun exifOrientationComposesLikeTheRenderer() {
        val m = app.photomaid.core.edit.ExifOrientationMath
        assertEquals(6, m.compose(1, 1, false, false))  // 90° im Uhrzeigersinn
        assertEquals(3, m.compose(6, 1, false, false))
        assertEquals(1, m.compose(8, 1, false, false))
        assertEquals(2, m.compose(1, 0, true, false))   // horizontal gespiegelt
        assertEquals(4, m.compose(1, 0, false, true))   // vertikal gespiegelt
        assertEquals(3, m.compose(1, 0, true, true))    // beides = 180°
        assertEquals(6, m.compose(0, 1, false, false))  // unbekannt = normal
        assertEquals(5, m.compose(1, 1, true, false))  // erst drehen, dann spiegeln = transpose
        // Gegenprobe gegen die EXIF-Definitionen (lineare Teile, Bildschirmkoordinaten y nach unten).
        val exif = mapOf(1 to Affine(), 2 to Affine.scale(-1.0, 1.0), 3 to Affine.rotateDeg(180.0), 4 to Affine.scale(1.0, -1.0),
            5 to Affine(0.0, 1.0, 1.0, 0.0), 6 to Affine.rotateDeg(90.0), 7 to Affine(0.0, -1.0, -1.0, 0.0), 8 to Affine.rotateDeg(270.0))
        fun same(x: Affine, y: Affine) =
            listOf(x.a - y.a, x.b - y.b, x.c - y.c, x.d - y.d).all { abs(it) < 1e-9 }
        for (o in 1..8) for (t in 0..3) for (h in listOf(false, true)) for (v in listOf(false, true)) {
            val recipe = Affine.scale(if (h) -1.0 else 1.0, if (v) -1.0 else 1.0) * Affine.rotateDeg(90.0 * t)
            assertTrue("o=$o t=$t h=$h v=$v", same(exif.getValue(m.compose(o, t, h, v)), recipe * exif.getValue(o)))
        }
    }
}
