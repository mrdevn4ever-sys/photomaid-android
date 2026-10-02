package app.photomaid.core.edit

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Eine Pixel-Pipeline für Vorschau, Export und (als Farbtabelle) Video. Reines Kotlin, keine Bildanalyse, keine KI:
 * jede Stufe ist eine feste Formel der Regler. Reihenfolge (fest):
 * 1. Filter (mit Stärke) · 2. Tonwerte (Belichtung, Schwarz/Weiß, Schatten/Lichter, Kontrast, Helligkeit – als Kurve auf
 * der Helligkeit, Farbton bleibt) · 3. Wärme/Tönung/Sättigung · 4. Dynamik · 5. Farbmischer · 6. lokale Anpassungen ·
 * 7. Vignette · 8. Körnung. Danach (mit Nachbarpixeln, siehe [Detail]): Klarheit, Schärfen.
 *
 * Positionen sind normiert auf das Ausgabebild ([apply] bekommt den Versatz einer Kachel) → Vorschau und Export sehen
 * bei gleicher Anzeigegröße gleich aus.
 */
class PixelPipeline(val recipe: EditRecipe) {
    private val point = PointOps.of(recipe.adjust, recipe.filter, recipe.filterIntensity, recipe.colorMix)
    private val locals = recipe.locals.filter { !it.adjust.pointNeutral }.map { it.mask to PointOps.of(it.adjust, FilterId.NONE, 0f, ColorMix()) }
    private val vignette = recipe.adjust.vignette / 100f
    private val grain = (recipe.adjust.grain / 100f).coerceIn(0f, 1f)
    private val maskCache = HashMap<Mask, MaskGrid>()

    val isNeutral: Boolean get() = point.isNeutral && locals.isEmpty() && abs(vignette) < 0.005f && grain < 0.005f
    /** Nur punktweise (ohne Position): darf als Farbtabelle (Video, Filter-Vorschaubilder) genutzt werden. */
    val isPointOnly: Boolean get() = locals.isEmpty() && abs(vignette) < 0.005f && grain < 0.005f

    /**
     * Wendet die Pipeline auf eine Kachel an. [x0]/[y0] = Lage der Kachel im Ausgabebild der Größe [outW]×[outH].
     * [px] wird überschrieben (ARGB, Alpha bleibt).
     */
    fun apply(px: IntArray, w: Int, h: Int, x0: Int = 0, y0: Int = 0, outW: Int = w, outH: Int = h) {
        if (isNeutral) return
        val c = FloatArray(3)
        val masks = locals.map { (m, _) -> maskCache.getOrPut(m) { MaskGrid.of(m, outW, outH) } }
        val grainCell = max(1f, max(outW, outH) / 1100f)
        val invW = 1f / outW; val invH = 1f / outH
        for (y in 0 until h) {
            val gy = y0 + y
            val v = (gy + 0.5f) * invH
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]
                c[0] = ((p shr 16) and 0xFF) / 255f; c[1] = ((p shr 8) and 0xFF) / 255f; c[2] = (p and 0xFF) / 255f
                point.apply(c)
                if (masks.isNotEmpty()) {
                    val u = (x0 + x + 0.5f) * invW
                    for (k in masks.indices) {
                        val wgt = masks[k].sample(u, v)
                        if (wgt <= 0.0005f) continue
                        val r0 = c[0]; val g0 = c[1]; val b0 = c[2]
                        locals[k].second.apply(c)
                        c[0] = r0 + (c[0] - r0) * wgt; c[1] = g0 + (c[1] - g0) * wgt; c[2] = b0 + (c[2] - b0) * wgt
                    }
                }
                if (abs(vignette) >= 0.005f) {
                    val u = (x0 + x + 0.5f) * invW
                    val dx = (u - 0.5f) * 2f; val dy = (v - 0.5f) * 2f
                    val d = sqrt(dx * dx + dy * dy) / 1.41421356f
                    val f = 1f + vignette * 0.65f * smooth(0.3f, 1.05f, d)
                    c[0] *= f; c[1] *= f; c[2] *= f
                }
                if (grain >= 0.005f) {
                    val n = valueNoise((x0 + x) / grainCell, gy / grainCell)
                    val l = luma(c[0], c[1], c[2]).coerceIn(0f, 1f)
                    val amt = grain * 0.16f * (0.35f + 2.6f * l * (1f - l)) * n
                    c[0] += amt; c[1] += amt; c[2] += amt
                }
                px[i] = (p and 0xFF000000.toInt()) or (q(c[0]) shl 16) or (q(c[1]) shl 8) or q(c[2])
            }
        }
    }

    /** Ein einzelner Farbwert durch die punktweisen Stufen (Tests, Farbtabellen). */
    fun applyPoint(argb: Int): Int {
        val c = floatArrayOf(((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f)
        point.apply(c)
        return (argb and 0xFF000000.toInt()) or (q(c[0]) shl 16) or (q(c[1]) shl 8) or q(c[2])
    }

    /**
     * 3D-Farbtabelle [r][g][b] (ARGB) der punktweisen Stufen – für Video (Media3 `SingleColorLut`). Gleiche Formeln wie
     * bei Fotos, daher gleiche Farbwirkung.
     */
    fun cube(n: Int = 33): Array<Array<IntArray>> = Array(n) { r ->
        Array(n) { g ->
            IntArray(n) { b ->
                val c = floatArrayOf(r / (n - 1f), g / (n - 1f), b / (n - 1f))
                point.apply(c)
                (0xFF shl 24) or (q(c[0]) shl 16) or (q(c[1]) shl 8) or q(c[2])
            }
        }
    }

    companion object {
        internal fun q(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        internal fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
        internal fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

        private fun hash(x: Int, y: Int): Float {
            var h = x * 374761393 + y * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFFFF) / 16777215f - 0.5f
        }

        /** Weiches Rauschen (bilinear zwischen Gitterpunkten), deterministisch. */
        internal fun valueNoise(x: Float, y: Float): Float {
            val xi = floor(x).toInt(); val yi = floor(y).toInt()
            val fx = x - xi; val fy = y - yi
            val a = hash(xi, yi); val b = hash(xi + 1, yi); val c = hash(xi, yi + 1); val d = hash(xi + 1, yi + 1)
            val top = a + (b - a) * fx; val bottom = c + (d - c) * fx
            return (top + (bottom - top) * fy) * 2f
        }
    }
}

/** Punktweise Stufen eines Reglersatzes (vorberechnet: Tonkurve, Farbmatrix, Farbmischer). */
internal class PointOps private constructor(
    private val filter: FloatArray?,
    private val tone: FloatArray?,
    private val matrix: FloatArray?,
    private val vibrance: Float,
    private val mix: Array<HslShift>?,
) {
    val isNeutral: Boolean get() = filter == null && tone == null && matrix == null && abs(vibrance) < 0.005f && mix == null

    fun apply(c: FloatArray) {
        filter?.let { mul(it, c) }
        tone?.let { applyTone(it, c) }
        matrix?.let { mul(it, c) }
        if (abs(vibrance) >= 0.005f) applyVibrance(c)
        mix?.let { applyMix(it, c) }
        c[0] = c[0].coerceIn(0f, 1f); c[1] = c[1].coerceIn(0f, 1f); c[2] = c[2].coerceIn(0f, 1f)
    }

    private fun mul(m: FloatArray, c: FloatArray) {
        val r = c[0]; val g = c[1]; val b = c[2]
        c[0] = m[0] * r + m[1] * g + m[2] * b + m[4]
        c[1] = m[5] * r + m[6] * g + m[7] * b + m[9]
        c[2] = m[10] * r + m[11] * g + m[12] * b + m[14]
    }

    private fun applyTone(lut: FloatArray, c: FloatArray) {
        val r = c[0].coerceIn(0f, 1f); val g = c[1].coerceIn(0f, 1f); val b = c[2].coerceIn(0f, 1f)
        val y = PixelPipeline.luma(r, g, b)
        val fi = y * (LUT - 1); val i = fi.toInt().coerceIn(0, LUT - 2); val t = fi - i
        val y2 = lut[i] + (lut[i + 1] - lut[i]) * t
        if (y < 1e-4f) { c[0] = y2; c[1] = y2; c[2] = y2; return }
        val s = y2 / y
        var nr = r * s; var ng = g * s; var nb = b * s
        val m = max(nr, max(ng, nb))
        if (m > 1f && m > y2) {
            // Weich in den Farbumfang zurück: Helligkeit bleibt, Sättigung gibt nach (keine harten Farbsprünge).
            val k = (1f - y2) / (m - y2)
            nr = y2 + (nr - y2) * k; ng = y2 + (ng - y2) * k; nb = y2 + (nb - y2) * k
        }
        c[0] = nr; c[1] = ng; c[2] = nb
    }

    private fun applyVibrance(c: FloatArray) {
        val mx = max(c[0], max(c[1], c[2])); val mn = min(c[0], min(c[1], c[2]))
        val sat = if (mx <= 1e-4f) 0f else (mx - mn) / mx
        // Wenig gesättigte Farben stärker, kräftige kaum – anders als die Sättigung.
        val f = 1f + vibrance * (1f - sat).pow(1.5f) * 0.9f
        val y = PixelPipeline.luma(c[0], c[1], c[2])
        c[0] = y + (c[0] - y) * f; c[1] = y + (c[1] - y) * f; c[2] = y + (c[2] - y) * f
    }

    private fun applyMix(m: Array<HslShift>, c: FloatArray) {
        val r = c[0].coerceIn(0f, 1f); val g = c[1].coerceIn(0f, 1f); val b = c[2].coerceIn(0f, 1f)
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val ch = mx - mn
        if (ch < 1e-3f || mx <= 0f) return
        var hue = when (mx) { r -> 60f * (((g - b) / ch) % 6f); g -> 60f * ((b - r) / ch + 2f); else -> 60f * ((r - g) / ch + 4f) }
        if (hue < 0f) hue += 360f
        val sat = ch / mx
        val greyGuard = PixelPipeline.smooth(0.03f, 0.22f, sat)
        var dh = 0f; var ds = 0f; var dl = 0f
        val bands = ColorBand.entries
        for (k in bands.indices) {
            val s = m[k]; if (s.isNeutral) continue
            val wgt = bandWeight(bands, k, hue) * greyGuard
            if (wgt <= 0f) continue
            dh += wgt * s.hue * 0.3f; ds += wgt * s.saturation / 100f; dl += wgt * s.luminance / 100f
        }
        if (dh == 0f && ds == 0f && dl == 0f) return
        var h2 = (hue + dh) % 360f; if (h2 < 0f) h2 += 360f
        val s2 = (sat * (1f + ds)).coerceIn(0f, 1f)
        val v2 = (mx * (1f + dl * 0.6f * sat)).coerceIn(0f, 1f)
        hsvToRgb(h2, s2, v2, c)
    }

    companion object {
        const val LUT = 1024

        fun of(a: Adjustments, filterId: FilterId, intensity: Float, mix: ColorMix): PointOps {
            val f = if (filterId == FilterId.NONE || intensity <= 0.001f) null
            else norm(ColorMatrices.lerp(ColorMatrices.IDENTITY, ColorMatrices.filter(filterId), intensity))
            val toneActive = listOf(a.exposure, a.blacks, a.whites, a.shadows, a.highlights, a.contrast, a.brightness).any { abs(it) >= 0.5f }
            val tone = if (toneActive) ToneCurve.lut(a) else null
            val colorActive = listOf(a.warmth, a.tint, a.saturation).any { abs(it) >= 0.5f }
            val m = if (colorActive) norm(ColorMatrices.concat(ColorMatrices.saturation(a.saturation), ColorMatrices.concat(tintMatrix(a.tint), ColorMatrices.warmth(a.warmth)))) else null
            val mixArr = if (mix.isNeutral) null else Array(ColorBand.entries.size) { mix[ColorBand.entries[it]] }
            return PointOps(f, tone, m, a.vibrance / 100f, mixArr)
        }

        /** Offsets von 0…255 nach 0…1. */
        private fun norm(m: FloatArray) = m.copyOf().also { for (r in 0..3) it[r * 5 + 4] = m[r * 5 + 4] / 255f }

        /** Tönung: positiv = Magenta, negativ = Grün. */
        fun tintMatrix(v: Float): FloatArray {
            val t = v / 100f
            return floatArrayOf(1f + 0.06f * t, 0f, 0f, 0f, 0f, 0f, 1f - 0.12f * t, 0f, 0f, 0f, 0f, 0f, 1f + 0.06f * t, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        }

        private fun angDist(a: Float, b: Float): Float { val d = abs(a - b) % 360f; return if (d > 180f) 360f - d else d }

        /** Dreiecksgewicht zwischen den Nachbar-Mitten (weich), Summe der Bänder ≈ 1. */
        internal fun bandWeight(bands: List<ColorBand>, k: Int, hue: Float): Float {
            val c = bands[k].centerDeg
            val prev = bands[(k - 1 + bands.size) % bands.size].centerDeg; val next = bands[(k + 1) % bands.size].centerDeg
            val d = angDist(hue, c)
            val toward = if (((hue - c + 360f) % 360f) < 180f) angDist(next, c) else angDist(prev, c)
            if (d >= toward) return 0f
            val t = 1f - d / toward
            return t * t * (3f - 2f * t)
        }

        internal fun hsvToRgb(h: Float, s: Float, v: Float, c: FloatArray) {
            val cc = v * s; val x = cc * (1f - abs((h / 60f) % 2f - 1f)); val m = v - cc
            val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
                0 -> Triple(cc, x, 0f); 1 -> Triple(x, cc, 0f); 2 -> Triple(0f, cc, x)
                3 -> Triple(0f, x, cc); 4 -> Triple(x, 0f, cc); else -> Triple(cc, 0f, x)
            }
            c[0] = r + m; c[1] = g + m; c[2] = b + m
        }
    }
}

/** Tonwertkurve auf der (Gamma-)Helligkeit. Jede Stufe wirkt in ihrem Bereich; Ergebnis monoton und in 0…1. */
object ToneCurve {
    private fun toLin(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    private fun toGamma(v: Float) = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
    private fun smooth(e0: Float, e1: Float, x: Float) = PixelPipeline.smooth(e0, e1, x)

    /** Weiche Schulter statt hartem Abschneiden bei hoher Belichtung. */
    private fun shoulder(x: Float): Float = if (x <= 0.85f) x else 0.85f + 0.15f * (1f - exp(-(x - 0.85f) / 0.15f))

    fun map(a: Adjustments, y0: Float): Float {
        var y = y0
        if (abs(a.exposure) >= 0.5f) y = toGamma(shoulder(toLin(y) * 2f.pow(a.exposure / 100f * 2f)).coerceIn(0f, 1f))
        if (abs(a.blacks) >= 0.5f) y += 0.16f * (a.blacks / 100f) * (1f - y).pow(4f)
        if (abs(a.whites) >= 0.5f) y += 0.16f * (a.whites / 100f) * y.pow(4f)
        if (abs(a.shadows) >= 0.5f) y += 0.28f * (a.shadows / 100f) * (1f - smooth(0f, 0.6f, y)) * smooth(0f, 0.06f, y) * (1f - y)
        if (abs(a.highlights) >= 0.5f) y += 0.28f * (a.highlights / 100f) * smooth(0.45f, 1f, y) * y.coerceAtMost(1f) * (1f - 0.5f * smooth(0.95f, 1f, y))
        if (abs(a.contrast) >= 0.5f) {
            val c = a.contrast / 100f
            val yc = y.coerceIn(0f, 1f)
            y = if (c > 0f) yc + (yc * yc * (3f - 2f * yc) - yc) * c * 0.9f else yc + (0.5f + (yc - 0.5f) * 0.45f - yc) * (-c) * 0.8f
        }
        if (abs(a.brightness) >= 0.5f) y = y.coerceIn(0f, 1f).pow(2f.pow(-a.brightness / 100f * 0.85f))
        return y.coerceIn(0f, 1f)
    }

    fun lut(a: Adjustments, n: Int = PointOps.LUT): FloatArray {
        val out = FloatArray(n) { map(a, it / (n - 1f)) }
        for (i in 1 until n) if (out[i] < out[i - 1]) out[i] = out[i - 1] // monoton
        return out
    }
}

/** Maske als kleines Raster (lange Kante ≤ 512), bilinear gelesen. Unabhängig von der Exportgröße. */
class MaskGrid private constructor(private val w: Int, private val h: Int, private val v: FloatArray?, private val fn: ((Float, Float) -> Float)?) {
    fun sample(u: Float, vv: Float): Float {
        fn?.let { return it(u, vv) }
        val a = v!!
        val fx = (u * w - 0.5f).coerceIn(0f, w - 1f); val fy = (vv * h - 0.5f).coerceIn(0f, h - 1f)
        val x = fx.toInt().coerceAtMost(w - 2).coerceAtLeast(0); val y = fy.toInt().coerceAtMost(h - 2).coerceAtLeast(0)
        if (w < 2 || h < 2) return a[0]
        val tx = fx - x; val ty = fy - y
        val p = a[y * w + x]; val q = a[y * w + x + 1]; val r = a[(y + 1) * w + x]; val s = a[(y + 1) * w + x + 1]
        return (p + (q - p) * tx) + ((r + (s - r) * tx) - (p + (q - p) * tx)) * ty
    }

    companion object {
        fun of(m: Mask, outW: Int, outH: Int): MaskGrid {
            val aspect = outW.toFloat() / outH
            return when (m) {
                is Mask.Radial -> MaskGrid(0, 0, null) { u, v ->
                    val dx = (u - m.center.x) / max(1e-4f, m.rx); val dy = (v - m.center.y) / max(1e-4f, m.ry)
                    val d = sqrt(dx * dx + dy * dy)
                    val f = m.feather.coerceIn(0f, 1f)
                    val w = if (f <= 0.001f) (if (d <= 1f) 1f else 0f) else 1f - PixelPipeline.smooth(1f - f, 1f, d)
                    if (m.invert) 1f - w else w
                }
                is Mask.Linear -> MaskGrid(0, 0, null) { u, v ->
                    val sx = m.start.x * aspect; val ex = m.end.x * aspect; val px = u * aspect
                    val dx = ex - sx; val dy = m.end.y - m.start.y
                    val len2 = dx * dx + dy * dy
                    if (len2 < 1e-8f) 0f else { val t = ((px - sx) * dx + (v - m.start.y) * dy) / len2; 1f - PixelPipeline.smooth(0f, 1f, t) }
                }
                is Mask.Brush -> {
                    val long = 512
                    val gw = if (aspect >= 1f) long else max(2, (long * aspect).toInt())
                    val gh = if (aspect >= 1f) max(2, (long / aspect).toInt()) else long
                    MaskGrid(gw, gh, rasterize(m.strokes, gw, gh), null)
                }
            }
        }

        /** Striche nacheinander (Radierer zieht ab). Radius relativ zur langen Kante. */
        internal fun rasterize(strokes: List<BrushStroke>, gw: Int, gh: Int): FloatArray {
            val a = FloatArray(gw * gh)
            val long = max(gw, gh).toFloat()
            for (s in strokes) {
                val rad = max(0.5f, s.radius * long)
                val hard = s.hardness.coerceIn(0f, 0.98f)
                val pts = s.points.map { (it.x * gw) to (it.y * gh) }
                if (pts.isEmpty()) continue
                val minX = max(0, (pts.minOf { it.first } - rad).toInt()); val maxX = min(gw - 1, (pts.maxOf { it.first } + rad).toInt() + 1)
                val minY = max(0, (pts.minOf { it.second } - rad).toInt()); val maxY = min(gh - 1, (pts.maxOf { it.second } + rad).toInt() + 1)
                for (y in minY..maxY) for (x in minX..maxX) {
                    val px = x + 0.5f; val py = y + 0.5f
                    var best = Float.MAX_VALUE
                    if (pts.size == 1) best = dist(px, py, pts[0].first, pts[0].second)
                    else for (k in 0 until pts.size - 1) best = min(best, segDist(px, py, pts[k], pts[k + 1]))
                    val t = best / rad
                    if (t >= 1f) continue
                    val wgt = if (t <= hard) 1f else 1f - PixelPipeline.smooth(hard, 1f, t)
                    val i = y * gw + x
                    a[i] = if (s.erase) a[i] * (1f - wgt) else max(a[i], wgt)
                }
            }
            return a
        }

        private fun dist(x: Float, y: Float, ax: Float, ay: Float) = sqrt((x - ax) * (x - ax) + (y - ay) * (y - ay))
        private fun segDist(x: Float, y: Float, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
            val dx = b.first - a.first; val dy = b.second - a.second
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-6f) 0f else (((x - a.first) * dx + (y - a.second) * dy) / l2).coerceIn(0f, 1f)
            return dist(x, y, a.first + t * dx, a.second + t * dy)
        }
    }
}

/**
 * Detail-Stufen mit Nachbarpixeln: Klarheit (Kontrast mittlerer Strukturen, großer Radius, nur Mitteltöne) und Schärfen
 * (Unschärfemaske mit Radius und Schwelle). Radius in Pixeln wächst mit der Ausgabegröße → gleicher Eindruck.
 */
object Detail {
    fun clarityRadius(longEdge: Int) = (longEdge / 140).coerceIn(2, 48)
    fun sharpenRadius(a: Adjustments, longEdge: Int): Int {
        val base = Sharpen.radiusFor(longEdge)
        return if (a.sharpenRadius < 0.5f) base else max(1, (base * (0.5f + a.sharpenRadius / 100f * 2.5f)).toInt())
    }

    /** Nötige Überlappung beim Arbeiten in Streifen (Ergebnis identisch zum Ganzen). */
    fun overlap(a: Adjustments, longEdge: Int): Int = 2 * max(if (abs(a.clarity) >= 0.5f) clarityRadius(longEdge) * 2 else 0, if (a.sharpness >= 0.5f) sharpenRadius(a, longEdge) else 0)

    fun apply(px: IntArray, w: Int, h: Int, a: Adjustments, longEdge: Int): IntArray {
        var out = px
        if (abs(a.clarity) >= 0.5f) {
            val r = clarityRadius(longEdge)
            val blur = Sharpen.boxBlur(Sharpen.boxBlur(out, w, h, r), w, h, r)
            val amount = a.clarity / 100f * 0.7f
            val res = IntArray(out.size)
            for (i in out.indices) {
                val p = out[i]; val bl = blur[i]
                val l = (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f
                val mid = 4f * l * (1f - l)
                fun ch(s: Int): Int { val c = (p shr s) and 0xFF; val b = (bl shr s) and 0xFF; return (c + amount * mid * (c - b)).toInt().coerceIn(0, 255) }
                res[i] = (p and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
            out = res
        }
        if (a.sharpness >= 0.5f) out = Sharpen.apply(out, w, h, Sharpen.amount(a.sharpness), sharpenRadius(a, longEdge), (a.sharpenThreshold / 100f * 40f).toInt())
        return out
    }
}

/** Histogramm (Helligkeit + R/G/B, je 256 Klassen) und Anteil abgeschnittener Lichter/Tiefen. Nur Anzeige. */
class Histogram(val luma: IntArray, val red: IntArray, val green: IntArray, val blue: IntArray, val total: Int, val clippedHigh: Int, val clippedLow: Int) {
    companion object {
        fun of(px: IntArray, step: Int = 1): Histogram {
            val l = IntArray(256); val r = IntArray(256); val g = IntArray(256); val b = IntArray(256)
            var n = 0; var hi = 0; var lo = 0
            var i = 0
            while (i < px.size) {
                val p = px[i]
                val rr = (p shr 16) and 0xFF; val gg = (p shr 8) and 0xFF; val bb = p and 0xFF
                r[rr]++; g[gg]++; b[bb]++
                l[((0.2126f * rr + 0.7152f * gg + 0.0722f * bb) + 0.5f).toInt().coerceIn(0, 255)]++
                if (rr >= 254 || gg >= 254 || bb >= 254) hi++
                if (rr <= 1 && gg <= 1 && bb <= 1) lo++
                n++; i += step
            }
            return Histogram(l, r, g, b, n, hi, lo)
        }
    }
}
