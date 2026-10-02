package app.photomaid.core

import app.photomaid.core.edit.Adjustments
import app.photomaid.core.edit.BrushStroke
import app.photomaid.core.edit.ColorBand
import app.photomaid.core.edit.ColorMix
import app.photomaid.core.edit.Detail
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.FilterId
import app.photomaid.core.edit.Histogram
import app.photomaid.core.edit.HslShift
import app.photomaid.core.edit.LocalAdjustment
import app.photomaid.core.edit.Mask
import app.photomaid.core.edit.NormPoint
import app.photomaid.core.edit.PixelPipeline
import app.photomaid.core.edit.ToneCurve
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PixelPipelineTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF
    private fun lum(p: Int) = 0.2126 * r(p) + 0.7152 * g(p) + 0.0722 * b(p)
    private fun pipe(a: Adjustments = Adjustments(), mix: ColorMix = ColorMix(), locals: List<LocalAdjustment> = emptyList(), filter: FilterId = FilterId.NONE) =
        PixelPipeline(EditRecipe(adjust = a, colorMix = mix, locals = locals, filter = filter))

    @Test fun neutralIsExactIdentity() {
        val px = IntArray(64) { rgb(it * 3, 255 - it * 2, (it * 37) % 256) }
        val copy = px.copyOf()
        pipe().apply(px, 8, 8)
        assertArrayEquals(copy, px)
        // Filter mit Stärke 0 ist ebenfalls neutral.
        assertTrue(PixelPipeline(EditRecipe(filter = FilterId.MONO, filterIntensity = 0f)).isNeutral)
    }

    @Test fun highlightsOnlyTouchBrightAndShadowsOnlyDark() {
        val dark = rgb(30, 30, 30); val bright = rgb(225, 225, 225); val mid = rgb(128, 128, 128)
        val hl = pipe(Adjustments(highlights = -100f))
        assertTrue(abs(lum(hl.applyPoint(dark)) - lum(dark)) <= 1.0)
        assertTrue(lum(hl.applyPoint(bright)) < lum(bright) - 15)
        val sh = pipe(Adjustments(shadows = 100f))
        assertTrue(abs(lum(sh.applyPoint(bright)) - lum(bright)) <= 1.0)
        assertTrue(lum(sh.applyPoint(dark)) > lum(dark) + 8)
        // Schatten heben reines Schwarz nicht an (keine graue Suppe).
        assertEquals(0, r(sh.applyPoint(rgb(0, 0, 0))))
        // Belichtung wirkt überall, aber anders als Helligkeit in den Lichtern.
        val ex = pipe(Adjustments(exposure = 50f))
        assertTrue(lum(ex.applyPoint(mid)) > lum(mid) + 20)
        assertTrue(r(ex.applyPoint(rgb(250, 250, 250))) <= 255)
    }

    @Test fun toneCurveIsMonotoneForExtremeSettings() {
        val a = Adjustments(exposure = 100f, contrast = 100f, shadows = 100f, highlights = -100f, whites = 100f, blacks = -100f, brightness = -100f)
        val lut = ToneCurve.lut(a)
        for (i in 1 until lut.size) assertTrue(lut[i] >= lut[i - 1])
        assertTrue(lut.all { it in 0f..1f })
    }

    @Test fun toneKeepsHueOfSaturatedColour() {
        val red = rgb(200, 40, 30)
        val out = pipe(Adjustments(shadows = 60f, exposure = 30f)).applyPoint(red)
        assertTrue(r(out) > g(out) && r(out) > b(out)) // bleibt rot
    }

    @Test fun colorMixChangesOnlyItsBand() {
        val mix = ColorMix().with(ColorBand.BLUE, HslShift(saturation = -100f))
        val p = pipe(mix = mix)
        val blue = rgb(40, 80, 220); val red = rgb(220, 40, 40); val grey = rgb(120, 120, 120)
        val ob = p.applyPoint(blue)
        assertTrue(b(ob) - r(ob) < (220 - 40) / 3) // deutlich entsättigt
        assertEquals(red, p.applyPoint(red))
        assertEquals(grey, p.applyPoint(grey))
        // Farbton verschieben: Grün → in Richtung Gelb/Aqua, Helligkeit bleibt etwa.
        val hue = pipe(mix = ColorMix().with(ColorBand.GREEN, HslShift(hue = 100f))).applyPoint(rgb(40, 200, 40))
        assertTrue(b(hue) > 40 || r(hue) > 40)
    }

    @Test fun vibranceFavoursMutedColours() {
        val muted = rgb(140, 120, 110); val vivid = rgb(230, 30, 20)
        val p = pipe(Adjustments(vibrance = 100f))
        val dm = (r(p.applyPoint(muted)) - b(p.applyPoint(muted))) - (140 - 110)
        val dv = (r(p.applyPoint(vivid)) - b(p.applyPoint(vivid))) - (230 - 20)
        assertTrue(dm > 0 && dm > dv)
    }

    @Test fun tintAndWarmthDirections() {
        val grey = rgb(128, 128, 128)
        val mag = pipe(Adjustments(tint = 100f)).applyPoint(grey)
        assertTrue(g(mag) < r(mag) && g(mag) < b(mag))
        val warm = pipe(Adjustments(warmth = 100f)).applyPoint(grey)
        assertTrue(r(warm) > b(warm))
    }

    @Test fun vignetteDarkensCornersNotCentre() {
        val w = 21; val h = 21
        val px = IntArray(w * h) { rgb(200, 200, 200) }
        pipe(Adjustments(vignette = -100f)).apply(px, w, h)
        assertTrue(r(px[10 * w + 10]) >= 198)
        assertTrue(r(px[0]) < 150)
    }

    @Test fun tilesGiveSameResultAsWhole() {
        val w = 40; val h = 30
        val src = IntArray(w * h) { rgb((it * 7) % 256, (it * 3) % 256, (it * 11) % 256) }
        val a = Adjustments(vignette = -60f, grain = 70f, exposure = 20f)
        val local = listOf(LocalAdjustment(Mask.Radial(NormPoint(0.3f, 0.4f), 0.2f, 0.3f), Adjustments(exposure = 60f)))
        val whole = src.copyOf(); pipe(a, locals = local).apply(whole, w, h)
        val top = src.copyOfRange(0, w * 12); val bottom = src.copyOfRange(w * 12, w * h)
        val p = pipe(a, locals = local)
        p.apply(top, w, 12, 0, 0, w, h); p.apply(bottom, w, h - 12, 0, 12, w, h)
        assertArrayEquals(whole, top + bottom)
    }

    @Test fun grainIsDeterministic() {
        val a = IntArray(100) { rgb(128, 128, 128) }; val b = a.copyOf()
        pipe(Adjustments(grain = 100f)).apply(a, 10, 10); pipe(Adjustments(grain = 100f)).apply(b, 10, 10)
        assertArrayEquals(a, b)
        assertTrue(a.distinct().size > 3)
    }

    @Test fun radialMaskAffectsInsideOnly() {
        val w = 50; val h = 50
        val px = IntArray(w * h) { rgb(100, 100, 100) }
        pipe(locals = listOf(LocalAdjustment(Mask.Radial(NormPoint(0.5f, 0.5f), 0.2f, 0.2f, feather = 0.1f), Adjustments(exposure = 80f)))).apply(px, w, h)
        assertTrue(r(px[25 * w + 25]) > 140)
        assertEquals(100, r(px[2 * w + 2]))
        // Umgekehrt: außen statt innen.
        val inv = IntArray(w * h) { rgb(100, 100, 100) }
        pipe(locals = listOf(LocalAdjustment(Mask.Radial(NormPoint(0.5f, 0.5f), 0.2f, 0.2f, 0.1f, invert = true), Adjustments(exposure = 80f)))).apply(inv, w, h)
        assertEquals(100, r(inv[25 * w + 25])); assertTrue(r(inv[2 * w + 2]) > 140)
    }

    @Test fun linearAndBrushMasks() {
        val w = 40; val h = 40
        val lin = IntArray(w * h) { rgb(100, 100, 100) }
        pipe(locals = listOf(LocalAdjustment(Mask.Linear(NormPoint(0.5f, 0f), NormPoint(0.5f, 0.5f)), Adjustments(exposure = -80f)))).apply(lin, w, h)
        assertTrue(r(lin[1 * w + 20]) < 70); assertEquals(100, r(lin[38 * w + 20]))
        val stroke = BrushStroke(listOf(NormPoint(0.1f, 0.5f), NormPoint(0.9f, 0.5f)), radius = 0.05f, hardness = 0.8f)
        val br = IntArray(w * h) { rgb(100, 100, 100) }
        pipe(locals = listOf(LocalAdjustment(Mask.Brush(listOf(stroke)), Adjustments(saturation = 0f, exposure = 60f)))).apply(br, w, h)
        assertTrue(r(br[20 * w + 20]) > 130); assertEquals(100, r(br[2 * w + 20]))
        // Radierer nimmt den Strich wieder weg.
        val erased = IntArray(w * h) { rgb(100, 100, 100) }
        pipe(locals = listOf(LocalAdjustment(Mask.Brush(listOf(stroke, stroke.copy(erase = true, radius = 0.08f))), Adjustments(exposure = 60f)))).apply(erased, w, h)
        assertEquals(100, r(erased[20 * w + 20]))
    }

    @Test fun cubeMatchesPointOpsAndIsIdentityWhenNeutral() {
        val n = 9
        val id = pipe().cube(n)
        assertEquals(rgb(255, 0, 128), id[n - 1][0][4] or 0)
        val p = pipe(Adjustments(contrast = 40f, warmth = 30f), filter = FilterId.SEPIA)
        val cube = p.cube(n)
        assertEquals(p.applyPoint(rgb(255, 0, 0)), cube[8][0][0])
        assertEquals(p.applyPoint(rgb(0, 255, 255)), cube[0][8][8])
    }

    @Test fun detailNeutralLeavesPixelsAndSharpensEdges() {
        val w = 30; val h = 10
        val px = IntArray(w * h) { i -> if (i % w < w / 2) rgb(60, 60, 60) else rgb(190, 190, 190) }
        assertArrayEquals(px, Detail.apply(px, w, h, Adjustments(), 30))
        val s = Detail.apply(px, w, h, Adjustments(sharpness = 100f), 30)
        assertTrue(r(s[5 * w + w / 2 - 1]) < 60 && r(s[5 * w + w / 2]) > 190)
        // Schwelle schützt kleine Unterschiede.
        val flat = IntArray(w * h) { i -> rgb(100 + (i % 2), 100, 100) }
        assertArrayEquals(flat, Detail.apply(flat, w, h, Adjustments(sharpness = 100f, sharpenThreshold = 50f), 30))
    }

    @Test fun histogramCountsAndClipping() {
        val px = intArrayOf(rgb(255, 255, 255), rgb(0, 0, 0), rgb(128, 128, 128), rgb(255, 10, 10))
        val hst = Histogram.of(px)
        assertEquals(4, hst.total); assertEquals(2, hst.clippedHigh); assertEquals(1, hst.clippedLow)
        assertEquals(1, hst.luma[128])
    }
}
