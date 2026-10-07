package app.photomaid.feature.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.NormRect
import app.photomaid.core.studio.BackgroundKind
import app.photomaid.core.studio.CellFit
import app.photomaid.core.studio.CollageItem
import app.photomaid.core.studio.CollageKind
import app.photomaid.core.studio.CollageProject
import app.photomaid.core.studio.StripLayout
import app.photomaid.media.ImageEditEngine
import app.photomaid.media.LayerRenderer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import app.photomaid.media.LayerBox

/**
 * Zeichnet eine Collage – derselbe Code für Vorschau (kleine Quellen) und Export (Quellen nur in der Auflösung ihres
 * Feldes). [load] liefert ein ausgerichtetes Foto mit langer Kante ≤ maxEdge (oder `null`, wenn es fehlt).
 */
class CollageRenderer(private val engine: ImageEditEngine) {
    /** Quelle eines Feldes: gedreht/gespiegelt und mit Filter/Reglern (gleiche Pipeline wie der Foto-Editor). */
    fun prepare(raw: Bitmap, item: CollageItem): Bitmap {
        var b = raw
        if (item.quarterTurns % 4 != 0 || item.flipH) {
            val m = Matrix().apply { if (item.flipH) postScale(-1f, 1f); postRotate(90f * (item.quarterTurns % 4)) }
            b = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        }
        val r = EditRecipe(adjust = item.adjust, filter = item.filter, filterIntensity = item.filterIntensity)
        if (r.hasColor) { if (!b.isMutable || b === raw) b = b.copy(Bitmap.Config.ARGB_8888, true); engine.applyPixels(b, r) }
        return b
    }

    /** Ausgabegröße: Raster/Frei aus dem Seitenverhältnis; Streifen aus den Fotos. */
    fun size(p: CollageProject, longEdge: Int, sizes: List<Pair<Int, Int>>): Pair<Int, Int> =
        if (p.kind == CollageKind.STRIP) StripLayout.layout(sizes, p.items, p.stripVertical, spacingPx(p, longEdge), longEdge.coerceAtMost(4096)).first
        else if (p.aspect >= 1f) longEdge to max(1, (longEdge / p.aspect).roundToInt()) else max(1, (longEdge * p.aspect).roundToInt()) to longEdge

    private fun spacingPx(p: CollageProject, cross: Int) = (p.spacing * cross).roundToInt()

    /** Benötigte lange Kante je Foto für eine Ausgabe der Größe [w]×[h] (Feldgröße × Zoom). */
    fun neededEdge(p: CollageProject, i: Int, w: Int, h: Int): Int = when (p.kind) {
        CollageKind.GRID -> p.cellRects().getOrNull(i)?.let { r -> (max(r.width * w, r.height * h) * max(1f, p.items[i].zoom) * 1.6f).roundToInt() } ?: 512
        CollageKind.FREE -> (p.items[i].width * w * 1.4f).roundToInt()
        CollageKind.STRIP -> if (p.stripVertical) w * 2 else h * 2
    }.coerceIn(64, 8192)

    fun render(p: CollageProject, w: Int, h: Int, sources: List<Bitmap?>): Bitmap =
        render(p, w, h, sources.map { it?.let { b -> b.width to b.height } ?: (1 to 1) }, { sources.getOrNull(it) }, { _, _ -> })

    /**
     * Export-Variante: [get] lädt ein Foto erst, wenn es gezeichnet wird, [release] gibt es danach frei → auch 20 große
     * Fotos ohne Speicherspitze. [sizes] = Seitenverhältnisse (für Streifen vorab nötig).
     */
    fun render(p: CollageProject, w: Int, h: Int, sizes: List<Pair<Int, Int>>, get: (Int) -> Bitmap?, release: (Int, Bitmap) -> Unit): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        drawBackground(c, p, w, h, get, release)
        val short = min(w, h).toFloat()
        when (p.kind) {
            CollageKind.GRID -> p.cellRects().forEachIndexed { i, r ->
                val src = get(i) ?: return@forEachIndexed
                val cell = RectF(r.left * w, r.top * h, r.right * w, r.bottom * h)
                drawInCell(c, src, p.items[i], cell, p.corner * short, paint)
                release(i, src)
            }
            CollageKind.FREE -> p.items.forEachIndexed { i, it ->
                val src = get(i) ?: return@forEachIndexed
                drawFree(c, src, it, w, h, p.polaroid, paint)
                release(i, src)
            }
            CollageKind.STRIP -> {
                val (_, place) = StripLayout.layout(sizes, p.items, p.stripVertical, spacingPx(p, if (p.stripVertical) w else h), if (p.stripVertical) w else h, maxEdge = Int.MAX_VALUE)
                place.forEachIndexed { i, (dst, srcR) ->
                    val src = get(i) ?: return@forEachIndexed
                    val s = Rect((srcR.left * src.width).roundToInt(), (srcR.top * src.height).roundToInt(), (srcR.right * src.width).roundToInt(), (srcR.bottom * src.height).roundToInt())
                    c.drawBitmap(src, s, RectF(dst.left * w, dst.top * h, dst.right * w, dst.bottom * h), paint)
                    release(i, src)
                }
            }
        }
        LayerRenderer.apply(out, p.layers, w, h)
        return out
    }

    private fun drawBackground(c: Canvas, p: CollageProject, w: Int, h: Int, get: (Int) -> Bitmap?, release: (Int, Bitmap) -> Unit) {
        val bg = p.background
        if (bg.kind == BackgroundKind.BLUR) {
            val idx = bg.blurIndex.coerceIn(0, (p.items.size - 1).coerceAtLeast(0))
            val src = get(idx)
            if (src != null) {
                // Stark weichgezeichnet und leicht abgedunkelt: ruhiger Hintergrund aus einem der Fotos.
                val fill = CellFit.source(src.width.toFloat() / src.height, w.toFloat() / h, 1f, 0f, 0f, false)
                val cut = Bitmap.createBitmap(src, (fill.left * src.width).roundToInt().coerceIn(0, src.width - 1), (fill.top * src.height).roundToInt().coerceIn(0, src.height - 1),
                    max(1, (fill.width * src.width).roundToInt()).coerceAtMost(src.width), max(1, (fill.height * src.height).roundToInt()).coerceAtMost(src.height))
                var b = cut
                repeat(2) { val small = Bitmap.createScaledBitmap(b, max(1, w / 28), max(1, h / 28), true); b = Bitmap.createScaledBitmap(small, w, h, true) }
                c.drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                c.drawColor(0x33000000)
                release(idx, src)
                return
            }
        }
        c.drawColor(bg.color or 0xFF000000.toInt())
    }

    private fun drawInCell(c: Canvas, src: Bitmap, it: CollageItem, cell: RectF, radius: Float, paint: Paint) {
        val ia = src.width.toFloat() / src.height; val ca = cell.width() / max(1f, cell.height())
        c.save()
        if (radius > 0f) c.clipPath(Path().apply { addRoundRect(cell, radius, radius, Path.Direction.CW) }) else c.clipRect(cell)
        if (it.fit) {
            val f = CellFit.fitRect(ia, ca)
            c.drawBitmap(src, null, RectF(cell.left + f.left * cell.width(), cell.top + f.top * cell.height(), cell.left + f.right * cell.width(), cell.top + f.bottom * cell.height()), paint)
        } else {
            val s = CellFit.source(ia, ca, it.zoom, it.panX, it.panY, false)
            c.drawBitmap(src, srcRect(s, src), cell, paint)
        }
        c.restore()
    }

    private fun srcRect(s: NormRect, b: Bitmap) = Rect((s.left * b.width).roundToInt().coerceIn(0, b.width - 1), (s.top * b.height).roundToInt().coerceIn(0, b.height - 1),
        (s.right * b.width).roundToInt().coerceIn(1, b.width), (s.bottom * b.height).roundToInt().coerceIn(1, b.height))

    private fun drawFree(c: Canvas, src: Bitmap, it: CollageItem, w: Int, h: Int, polaroid: Boolean, paint: Paint) {
        val iw = it.width * w; val ih = iw * src.height / src.width
        c.save()
        c.translate(it.center.x * w, it.center.y * h); c.rotate(it.rotation)
        val img = RectF(-iw / 2, -ih / 2, iw / 2, ih / 2)
        if (polaroid) {
            val pad = iw * 0.045f
            val card = RectF(img.left - pad, img.top - pad, img.right + pad, img.bottom + pad * 3.2f)
            c.drawRect(card, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); setShadowLayer(iw * 0.03f, 0f, iw * 0.012f, 0x55000000) })
        }
        c.drawBitmap(src, null, img, paint)
        c.restore()
    }

    /** Lage eines Fotos in der freien Collage (für Treffer und Auswahl), in Ausgabepixeln. */
    fun freeBox(it: CollageItem, srcAspect: Float, w: Int, h: Int, polaroid: Boolean): LayerBox {
        val iw = it.width * w; val ih = iw / srcAspect
        val pad = if (polaroid) iw * 0.045f else 0f
        return LayerBox(it.center.x * w, it.center.y * h + (if (polaroid) pad * 1.1f else 0f), iw + 2 * pad, ih + (if (polaroid) pad * 4.2f else 0f), it.rotation)
    }
}
