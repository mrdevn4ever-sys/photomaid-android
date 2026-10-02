package app.photomaid.core.edit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Affine 2D-Abbildung (a c tx / b d ty), Spaltenvektoren; rein, testbar, auf Android in `Matrix` übertragbar. */
data class Affine(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0, val d: Double = 1.0, val tx: Double = 0.0, val ty: Double = 0.0) {
    /** Erst `other`, dann `this`. */
    operator fun times(o: Affine) = Affine(
        a = a * o.a + c * o.b, b = b * o.a + d * o.b,
        c = a * o.c + c * o.d, d = b * o.c + d * o.d,
        tx = a * o.tx + c * o.ty + tx, ty = b * o.tx + d * o.ty + ty,
    )
    fun map(x: Double, y: Double): Pair<Double, Double> = (a * x + c * y + tx) to (b * x + d * y + ty)
    fun inverse(): Affine {
        val det = a * d - b * c
        require(abs(det) > 1e-12) { "nicht invertierbar" }
        val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
        return Affine(ia, ib, ic, id, -(ia * tx + ic * ty), -(ib * tx + id * ty))
    }
    /** Android-Matrix-Werte (MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, 0, 0, 1). */
    fun toAndroidValues(): FloatArray = floatArrayOf(a.toFloat(), c.toFloat(), tx.toFloat(), b.toFloat(), d.toFloat(), ty.toFloat(), 0f, 0f, 1f)

    companion object {
        fun translate(x: Double, y: Double) = Affine(tx = x, ty = y)
        fun scale(sx: Double, sy: Double = sx) = Affine(a = sx, d = sy)
        fun rotateDeg(deg: Double): Affine { val r = Math.toRadians(deg); return Affine(cos(r), sin(r), -sin(r), cos(r)) }
    }
}

/**
 * Projektive 2D-Abbildung (3 × 3, Zeilen: m0 m1 m2 / m3 m4 m5 / m6 m7 m8) für Perspektive. Gerade Linien bleiben gerade.
 * Gleiche Schreibweise wie Android `Matrix` (MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, MPERSP_0, MPERSP_1, MPERSP_2).
 */
class Homography(val m: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)) {
    init { require(m.size == 9) }
    val isAffine: Boolean get() = abs(m[6]) < 1e-12 && abs(m[7]) < 1e-12

    /** Erst `o`, dann `this`. */
    operator fun times(o: Homography): Homography {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) r[i * 3 + j] = (0..2).sumOf { k -> m[i * 3 + k] * o.m[k * 3 + j] }
        return Homography(r)
    }
    operator fun times(o: Affine): Homography = this * of(o)

    fun map(x: Double, y: Double): Pair<Double, Double> {
        val w = m[6] * x + m[7] * y + m[8]
        return ((m[0] * x + m[1] * y + m[2]) / w) to ((m[3] * x + m[4] * y + m[5]) / w)
    }

    fun inverse(): Homography {
        val a = m
        val c00 = a[4] * a[8] - a[5] * a[7]; val c01 = a[5] * a[6] - a[3] * a[8]; val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        require(abs(det) > 1e-15) { "nicht invertierbar" }
        val inv = doubleArrayOf(
            c00, a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            c01, a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            c02, a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3])
        return Homography(DoubleArray(9) { inv[it] / det })
    }

    fun toAndroidValues(): FloatArray = FloatArray(9) { (m[it] / m[8]).toFloat() }

    companion object {
        fun of(a: Affine) = Homography(doubleArrayOf(a.a, a.c, a.tx, a.b, a.d, a.ty, 0.0, 0.0, 1.0))

        /** Abbildung, die die vier Punkte [src] auf [dst] legt (Reihenfolge gleich). `null` bei entarteten Punkten. */
        fun fromPoints(src: List<Pair<Double, Double>>, dst: List<Pair<Double, Double>>): Homography? {
            require(src.size == 4 && dst.size == 4)
            // 8 Gleichungen, h8 = 1.
            val a = Array(8) { DoubleArray(9) }
            for (i in 0..3) {
                val (x, y) = src[i]; val (u, v) = dst[i]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            for (col in 0..7) {
                val piv = (col..7).maxBy { abs(a[it][col]) }
                if (abs(a[piv][col]) < 1e-12) return null
                val t = a[col]; a[col] = a[piv]; a[piv] = t
                for (r in 0..7) if (r != col) {
                    val f = a[r][col] / a[col][col]
                    if (f != 0.0) for (k in col..8) a[r][k] -= f * a[col][k]
                }
            }
            val h = DoubleArray(9) { if (it == 8) 1.0 else a[it][8] / a[it][it] }
            return Homography(h)
        }
    }
}

operator fun Affine.times(o: Homography): Homography = Homography.of(this) * o

data class PxSize(val width: Int, val height: Int) {
    val longEdge: Int get() = max(width, height)
    val pixels: Long get() = width.toLong() * height
}

/**
 * Geometrie eines Rezepts für ein Quellbild der Größe [src] (bereits korrekt ausgerichtet, z. B. nach EXIF).
 * Liefert den Rahmen nach Vierteldrehung, den Ausschnitt in Pixeln, die Ausgabegröße und die Abbildung Quelle → Ausgabe.
 */
class EditGeometry(val src: PxSize, val recipe: EditRecipe) {
    /** Rahmen nach Vierteldrehung (Breite/Höhe tauschen bei 90°/270°), vor der Perspektive. */
    val baseFrame: PxSize = if (recipe.turns % 2 == 1) PxSize(src.height, src.width) else src

    /** Vergrößerung beim Begradigen, damit das gedrehte Bild den ganzen Rahmen deckt (keine leeren Ecken). */
    val straightenScale: Double = coverScale(baseFrame.width.toDouble(), baseFrame.height.toDouble(), recipe.straighten.toDouble())

    /** Perspektive: Viereck im Grundrahmen → Rechteck [frame] (Seitenlängen wie gemessen, höchstens so viele Pixel wie vorher). */
    private val warp: Pair<Homography, PxSize>? = recipe.perspective?.takeIf { !it.isIdentity }?.let { q -> PerspectiveMath.warp(q, baseFrame) }

    /** Rahmen, auf den sich der Ausschnitt bezieht (nach Vierteldrehung und Perspektive). */
    val frame: PxSize = warp?.second ?: baseFrame

    /** Ausschnitt in Rahmen-Pixeln (mindestens 1 × 1). */
    val cropLeft: Double = recipe.crop.left.toDouble() * frame.width
    val cropTop: Double = recipe.crop.top.toDouble() * frame.height
    val cropSize: PxSize = PxSize(
        max(1, (recipe.crop.width * frame.width).roundToInt()),
        max(1, (recipe.crop.height * frame.height).roundToInt()),
    )

    /** Endgültige Ausgabegröße: Zielgröße (nie größer als der Ausschnitt) oder Ausschnitt. */
    val output: PxSize = ResizeRules.clampToSource(recipe.resizeWidth, recipe.resizeHeight, cropSize)

    /** Quelle → Grundrahmen (Vierteldrehung + Spiegeln + Begradigen um die Mitte). */
    val sourceToBase: Affine = run {
        val w = src.width.toDouble(); val h = src.height.toDouble()
        val fw = baseFrame.width.toDouble(); val fh = baseFrame.height.toDouble()
        val turn = Affine.translate(fw / 2, fh / 2) * Affine.rotateDeg(90.0 * recipe.turns) * Affine.translate(-w / 2, -h / 2)
        val flip = Affine.translate(fw / 2, fh / 2) *
            Affine.scale(if (recipe.flipH) -1.0 else 1.0, if (recipe.flipV) -1.0 else 1.0) * Affine.translate(-fw / 2, -fh / 2)
        val straight = Affine.translate(fw / 2, fh / 2) * Affine.rotateDeg(recipe.straighten.toDouble()) *
            Affine.scale(straightenScale) * Affine.translate(-fw / 2, -fh / 2)
        straight * flip * turn
    }

    /** Quelle → Rahmen (inkl. Perspektive). */
    val sourceToFrame: Homography = warp?.let { it.first * sourceToBase } ?: Homography.of(sourceToBase)

    /** Quelle → Ausgabe (inkl. Ausschnitt und Skalierung). */
    val sourceToOutput: Homography = Affine.scale(output.width / cropSize.width.toDouble(), output.height / cropSize.height.toDouble()) *
        Affine.translate(-cropLeft, -cropTop) * sourceToFrame

    /** Benötigter Bereich der Quelle (Pixel, für Ausschnitt-Dekodierung bei sehr großen Fotos), gerundet nach außen. */
    fun sourceBounds(): IntArray {
        val inv = sourceToOutput.inverse()
        val pts = listOf(0.0 to 0.0, output.width.toDouble() to 0.0, 0.0 to output.height.toDouble(), output.width.toDouble() to output.height.toDouble())
            .map { (x, y) -> inv.map(x, y) }
        val l = pts.minOf { it.first }.toInt().coerceIn(0, src.width - 1)
        val t = pts.minOf { it.second }.toInt().coerceIn(0, src.height - 1)
        val r = kotlin.math.ceil(pts.maxOf { it.first }).toInt().coerceIn(l + 1, src.width)
        val b = kotlin.math.ceil(pts.maxOf { it.second }).toInt().coerceIn(t + 1, src.height)
        return intArrayOf(l, t, r, b)
    }

    companion object {
        /** Kleinste Vergrößerung, mit der ein um [deg] gedrehtes W × H-Bild das W × H-Rechteck vollständig deckt. */
        fun coverScale(w: Double, h: Double, deg: Double): Double {
            val r = Math.toRadians(abs(deg)); val c = cos(r); val s = sin(r)
            return max((w * c + h * s) / w, (w * s + h * c) / h)
        }
    }
}

/** Perspektive: vier Ecken (normiert im Grundrahmen) werden zum Rechteck. Rein manuell, keine Erkennung. */
object PerspectiveMath {
    /** Kleinster Abstand zweier Ecken (Anteil der Rahmenkante), damit das Viereck nicht entartet. */
    const val MIN_SIDE = 0.12f

    fun warp(q: Quad, base: PxSize): Pair<Homography, PxSize>? {
        val bw = base.width.toDouble(); val bh = base.height.toDouble()
        fun px(p: NormPoint) = (p.x * bw) to (p.y * bh)
        val pts = listOf(px(q.tl), px(q.tr), px(q.br), px(q.bl))
        fun len(a: Pair<Double, Double>, b: Pair<Double, Double>) = kotlin.math.hypot(a.first - b.first, a.second - b.second)
        var w = (len(pts[0], pts[1]) + len(pts[3], pts[2])) / 2
        var h = (len(pts[0], pts[3]) + len(pts[1], pts[2])) / 2
        if (w < 1 || h < 1) return null
        val cap = (bw * bh) / (w * h)
        if (cap < 1.0) { val k = kotlin.math.sqrt(cap); w *= k; h *= k }
        val size = PxSize(max(1, w.roundToInt()), max(1, h.roundToInt()))
        val dst = listOf(0.0 to 0.0, size.width.toDouble() to 0.0, size.width.toDouble() to size.height.toDouble(), 0.0 to size.height.toDouble())
        return Homography.fromPoints(pts, dst)?.let { it to size }
    }

    /** Ist das Viereck benutzbar (konvex, nicht zu klein)? */
    fun isValid(q: Quad): Boolean {
        val p = listOf(q.tl, q.tr, q.br, q.bl)
        var sign = 0
        for (i in 0..3) {
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0 || (sign != 0 && s != sign)) return false
            sign = s
            if (kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) < MIN_SIDE) return false
        }
        return true
    }

    /** Ecke [i] (0 = oben links, im Uhrzeigersinn) verschieben – nur wenn das Viereck gültig bleibt. */
    fun moveCorner(q: Quad, i: Int, x: Float, y: Float): Quad {
        val p = NormPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        val n = when (i) { 0 -> q.copy(tl = p); 1 -> q.copy(tr = p); 2 -> q.copy(br = p); else -> q.copy(bl = p) }
        return if (isValid(n)) n else q
    }

    /**
     * Schnelle Korrektur (Gebäude): [vertical] > 0 zieht die oberen Ecken zusammen (stürzende Linien gerade), < 0 die
     * unteren; [horizontal] entsprechend links/rechts. Werte −100…100.
     */
    fun keystone(vertical: Float, horizontal: Float): Quad {
        val v = (vertical / 100f).coerceIn(-1f, 1f) * 0.3f; val h = (horizontal / 100f).coerceIn(-1f, 1f) * 0.3f
        val tIn = max(0f, v); val bIn = max(0f, -v); val lIn = max(0f, h); val rIn = max(0f, -h)
        return Quad(NormPoint(tIn, lIn), NormPoint(1f - tIn, rIn), NormPoint(1f - bIn, 1f - rIn), NormPoint(bIn, 1f - lIn))
    }
}

/** Seitenverhältnisse beim Zuschneiden. */
enum class AspectPreset(val w: Int, val h: Int) {
    FREE(0, 0), ORIGINAL(0, 0), SQUARE(1, 1), R4_5(4, 5), R3_4(3, 4), R2_3(2, 3), R9_16(9, 16), R16_9(16, 9);
}

object CropMath {
    /** Kleinster Ausschnitt (Anteil der kürzeren Rahmenkante). */
    const val MIN_FRACTION = 0.08f

    /** Seitenverhältnis (Breite/Höhe) eines Presets im Rahmen; `null` = frei. [landscape] dreht Hoch-/Querformat. */
    fun ratio(p: AspectPreset, frame: PxSize, landscape: Boolean): Double? = when (p) {
        AspectPreset.FREE -> null
        AspectPreset.ORIGINAL -> frame.width.toDouble() / frame.height
        else -> { val r = p.w.toDouble() / p.h; if (landscape == (r >= 1.0)) r else 1.0 / r }
    }

    /** Größter Ausschnitt mit Verhältnis [ratio] (Pixel), zentriert in [rect], vollständig im Rahmen. */
    fun fitAspect(rect: NormRect, ratio: Double, frame: PxSize): NormRect {
        val rw = (rect.width * frame.width).toDouble(); val rh = (rect.height * frame.height).toDouble()
        val cx = (rect.left + rect.right) / 2f; val cy = (rect.top + rect.bottom) / 2f
        var w = rw; var h = rw / ratio
        if (h > rh) { h = rh; w = rh * ratio }
        val nw = (w / frame.width).toFloat(); val nh = (h / frame.height).toFloat()
        val l = (cx - nw / 2).coerceIn(0f, 1f - nw); val t = (cy - nh / 2).coerceIn(0f, 1f - nh)
        return NormRect(l, t, l + nw, t + nh)
    }

    enum class Handle { MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /**
     * Ziehen an einem Griff um (dx, dy) in Rahmenanteilen. Hält Mindestgröße, Rahmengrenzen und – falls gesetzt – das
     * Seitenverhältnis (Pixel) ein.
     */
    fun drag(rect: NormRect, h: Handle, dx: Float, dy: Float, frame: PxSize, ratio: Double?): NormRect {
        val minW = MIN_FRACTION * minOf(frame.width, frame.height) / frame.width
        val minH = MIN_FRACTION * minOf(frame.width, frame.height) / frame.height
        if (h == Handle.MOVE) {
            val l = (rect.left + dx).coerceIn(0f, 1f - rect.width); val t = (rect.top + dy).coerceIn(0f, 1f - rect.height)
            return NormRect(l, t, l + rect.width, t + rect.height)
        }
        var l = rect.left; var t = rect.top; var r = rect.right; var b = rect.bottom
        when (h) {
            Handle.LEFT, Handle.TOP_LEFT, Handle.BOTTOM_LEFT -> l = (l + dx).coerceIn(0f, r - minW)
            Handle.RIGHT, Handle.TOP_RIGHT, Handle.BOTTOM_RIGHT -> r = (r + dx).coerceIn(l + minW, 1f)
            else -> Unit
        }
        when (h) {
            Handle.TOP, Handle.TOP_LEFT, Handle.TOP_RIGHT -> t = (t + dy).coerceIn(0f, b - minH)
            Handle.BOTTOM, Handle.BOTTOM_LEFT, Handle.BOTTOM_RIGHT -> b = (b + dy).coerceIn(t + minH, 1f)
            else -> Unit
        }
        if (ratio == null) return NormRect(l, t, r, b)
        // Verhältnis halten: abhängige Kante an der gegenüberliegenden (festen) Seite verankern.
        val wPx = ((r - l) * frame.width).toDouble()
        val hPx = ((b - t) * frame.height).toDouble()
        val horizontalDriven = h == Handle.LEFT || h == Handle.RIGHT || (h != Handle.TOP && h != Handle.BOTTOM && abs(dx * frame.width) >= abs(dy * frame.height))
        var nw: Double; var nh: Double
        if (horizontalDriven) { nw = wPx; nh = nw / ratio } else { nh = hPx; nw = nh * ratio }
        // In den Rahmen passen (ausgehend von der festen Ecke).
        val fixedRight = h == Handle.LEFT || h == Handle.TOP_LEFT || h == Handle.BOTTOM_LEFT
        val fixedBottom = h == Handle.TOP || h == Handle.TOP_LEFT || h == Handle.TOP_RIGHT
        val maxW = (if (fixedRight) rect.right else 1f - rect.left) * frame.width.toDouble()
        val maxH = (if (fixedBottom) rect.bottom else 1f - rect.top) * frame.height.toDouble()
        if (nw > maxW) { nw = maxW; nh = nw / ratio }
        if (nh > maxH) { nh = maxH; nw = nh * ratio }
        val fw = (nw / frame.width).toFloat(); val fh = (nh / frame.height).toFloat()
        val cx = (rect.left + rect.right) / 2f; val cy = (rect.top + rect.bottom) / 2f
        val nl: Float; val nr: Float; val nt: Float; val nb: Float
        when {
            h == Handle.TOP || h == Handle.BOTTOM -> { nl = (cx - fw / 2).coerceIn(0f, 1f - fw); nr = nl + fw }
            fixedRight -> { nr = rect.right; nl = nr - fw }
            else -> { nl = rect.left; nr = nl + fw }
        }
        when {
            h == Handle.LEFT || h == Handle.RIGHT -> { nt = (cy - fh / 2).coerceIn(0f, 1f - fh); nb = nt + fh }
            fixedBottom -> { nb = rect.bottom; nt = nb - fh }
            else -> { nt = rect.top; nb = nt + fh }
        }
        return NormRect(nl.coerceAtLeast(0f), nt.coerceAtLeast(0f), nr.coerceAtMost(1f), nb.coerceAtMost(1f))
    }
}

object ResizeRules {
    val PRESET_LONG_EDGES = listOf(4096, 2048, 1080)
    const val MIN_EDGE = 16

    /** Vorgaben, die wirklich verkleinern. */
    fun presetsFor(size: PxSize): List<Int> = PRESET_LONG_EDGES.filter { it < size.longEdge }

    fun forLongEdge(size: PxSize, longEdge: Int): PxSize {
        val l = longEdge.coerceIn(MIN_EDGE, size.longEdge)
        return if (size.width >= size.height) PxSize(l, max(1, (l.toDouble() * size.height / size.width).roundToInt()))
        else PxSize(max(1, (l.toDouble() * size.width / size.height).roundToInt()), l)
    }

    /** Breite eingegeben, Verhältnis gesperrt. */
    fun forWidth(size: PxSize, width: Int): PxSize {
        val w = width.coerceIn(MIN_EDGE, size.width)
        return PxSize(w, max(1, (w.toDouble() * size.height / size.width).roundToInt()))
    }

    fun forHeight(size: PxSize, height: Int): PxSize {
        val h = height.coerceIn(MIN_EDGE, size.height)
        return PxSize(max(1, (h.toDouble() * size.width / size.height).roundToInt()), h)
    }

    /**
     * Nie hochskalieren und NIE verzerren: ein gemeinsamer Faktor für beide Kanten (passt das Ziel nicht ins Verhältnis,
     * gilt die engere Kante). Ohne Ziel = Ausschnitt. So bleibt auch ein älteres Rezept mit getrennten Werten unverzerrt.
     */
    fun clampToSource(w: Int?, h: Int?, crop: PxSize): PxSize {
        if (w == null || h == null) return crop
        val s = minOf(1.0, w.toDouble() / crop.width, h.toDouble() / crop.height).coerceAtLeast(1.0 / max(crop.width, crop.height))
        return PxSize(max(1, (crop.width * s).roundToInt()), max(1, (crop.height * s).roundToInt()))
    }
}

/**
 * Verlustfreies Drehen/Spiegeln: neue EXIF-Ausrichtung (1…8) aus der bisherigen und dem Rezept (erst Vierteldrehung im
 * Uhrzeigersinn, dann Spiegeln – wie [EditGeometry.sourceToFrame]). Unbekannt/0 gilt als 1 („normal“).
 * Darstellung als R(r) ∘ H^f (erst horizontal spiegeln, dann r Vierteldrehungen).
 */
object ExifOrientationMath {
    private val TO_RF = mapOf(1 to (0 to false), 2 to (0 to true), 3 to (2 to false), 4 to (2 to true),
        5 to (3 to true), 6 to (1 to false), 7 to (1 to true), 8 to (3 to false))
    private val FROM_RF = TO_RF.entries.associate { it.value to it.key }

    fun compose(current: Int, turns: Int, flipH: Boolean, flipV: Boolean): Int {
        val (r0, f0) = TO_RF[current] ?: (0 to false)
        var r = r0 + turns; var f = f0
        // H ∘ R(k) = R(−k) ∘ H;  V = R(2) ∘ H
        if (flipH && flipV) r += 2
        else if (flipH) { r = -r; f = !f }
        else if (flipV) { r = 2 - r; f = !f }
        return FROM_RF[((r % 4) + 4) % 4 to f]!!
    }
}
