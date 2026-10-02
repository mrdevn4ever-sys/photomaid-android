package app.photomaid.media

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.photomaid.core.edit.BrushKind
import app.photomaid.core.edit.BrushStroke
import app.photomaid.core.edit.DrawLayer
import app.photomaid.core.edit.DrawStroke
import app.photomaid.core.edit.FontId
import app.photomaid.core.edit.FrameMath
import app.photomaid.core.edit.FrameMode
import app.photomaid.core.edit.FrameStyle
import app.photomaid.core.edit.Layer
import app.photomaid.core.edit.NormPoint
import app.photomaid.core.edit.PxSize
import app.photomaid.core.edit.RedactLayer
import app.photomaid.core.edit.RedactMode
import app.photomaid.core.edit.ShapeKind
import app.photomaid.core.edit.ShapeLayer
import app.photomaid.core.edit.StickerKind
import app.photomaid.core.edit.StickerLayer
import app.photomaid.core.edit.TextAlign
import app.photomaid.core.edit.TextLayer
import app.photomaid.core.edit.TextStyle
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Lage einer Ebene im Ausgabebild (Pixel): Mitte, Größe (ungedreht), Drehung. Für Auswahlrahmen und Treffer. */
data class LayerBox(val cx: Float, val cy: Float, val w: Float, val h: Float, val rotation: Float) {
    /** Liegt (x, y) im (gedrehten) Rahmen, mit [pad] Spielraum? */
    fun contains(x: Float, y: Float, pad: Float = 0f): Boolean {
        val r = Math.toRadians(-rotation.toDouble())
        val dx = x - cx; val dy = y - cy
        val lx = dx * cos(r) - dy * sin(r); val ly = dx * sin(r) + dy * cos(r)
        return kotlin.math.abs(lx) <= w / 2 + pad && kotlin.math.abs(ly) <= h / 2 + pad
    }
}

/**
 * Zeichnet Ebenen (Text, Sticker, Zeichnung, Formen, Abdeckungen) und Rahmen – derselbe Code für Vorschau,
 * 100-%-Ansicht und Export. Abdeckungen werden in die Pixel von [apply] gerechnet (nicht nur überlagert).
 */
object LayerRenderer {
    private val typefaces = HashMap<FontId, Typeface>()
    fun typeface(f: FontId): Typeface = typefaces.getOrPut(f) { Typeface.create(f.family, if (f.bold) Typeface.BOLD else Typeface.NORMAL) }

    /**
     * Zeichnet [layers] in [b]. [view] = der Teil des Ausgabebildes (Ausgabe-Pixel), den [b] zeigt (ganzes Bild oder
     * Ausschnitt der Detailansicht); [outW]/[outH] = Ausgabegröße. [skipId] = Ebene auslassen (wird gerade live gezeigt).
     */
    fun apply(b: Bitmap, layers: List<Layer>, outW: Int, outH: Int, view: RectF = RectF(0f, 0f, outW.toFloat(), outH.toFloat()), skipId: String? = null) {
        if (layers.isEmpty()) return
        val c = Canvas(b)
        val sx = b.width / view.width(); val sy = b.height / view.height()
        c.scale(sx, sy); c.translate(-view.left, -view.top)
        val short = min(outW, outH).toFloat(); val long = max(outW, outH).toFloat()
        for (l in layers) {
            if (l.id == skipId) continue
            when (l) {
                is TextLayer -> drawText(c, l, outW, outH, short)
                is StickerLayer -> drawSticker(c, l, outW, outH, short)
                is DrawLayer -> drawStrokes(c, l.strokes, outW, outH, short)
                is ShapeLayer -> drawShape(c, l, outW, outH, short)
                is RedactLayer -> redact(b, c, l, outW, outH, short, long, view, sx, sy)
            }
        }
    }

    // Text

    private fun textPaint(l: TextLayer, short: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = typeface(l.font); textSize = max(1f, l.size * short); color = l.color
    }

    private fun layout(l: TextLayer, p: TextPaint): StaticLayout {
        val lines = l.text.ifEmpty { " " }.split('\n')
        val width = max(1, lines.maxOf { p.measureText(it) }.roundToInt() + 2)
        val align = when (l.align) { TextAlign.START -> Layout.Alignment.ALIGN_NORMAL; TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER; TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE }
        return StaticLayout.Builder.obtain(l.text.ifEmpty { " " }, 0, l.text.ifEmpty { " " }.length, p, width).setAlignment(align).setIncludePad(false).build()
    }

    private fun textPad(l: TextLayer, short: Float) = if (l.style == TextStyle.PILL) l.size * short * 0.45f else l.size * short * 0.12f

    fun box(l: Layer, outW: Int, outH: Int): LayerBox? {
        val short = min(outW, outH).toFloat()
        return when (l) {
            is TextLayer -> { val lay = layout(l, textPaint(l, short)); val pad = textPad(l, short)
                LayerBox(l.center.x * outW, l.center.y * outH, lay.width + 2 * pad, lay.height + 2 * pad, l.rotation) }
            is StickerLayer -> { val s = l.size * short; val w = if (l.kind == StickerKind.DATE) s * 2.6f else s
                LayerBox(l.center.x * outW, l.center.y * outH, w, if (l.kind == StickerKind.DATE) s * 0.8f else s, l.rotation) }
            is ShapeLayer -> { val ax = l.start.x * outW; val ay = l.start.y * outH; val bx = l.end.x * outW; val by = l.end.y * outH
                val pad = l.width * short * 2f
                LayerBox((ax + bx) / 2, (ay + by) / 2, kotlin.math.abs(bx - ax) + pad, kotlin.math.abs(by - ay) + pad, 0f) }
            is RedactLayer -> l.rect?.let { r -> LayerBox((r.left + r.right) / 2 * outW, (r.top + r.bottom) / 2 * outH, r.width * outW, r.height * outH, 0f) }
                ?: strokeBounds(l.strokes, outW, outH)
            is DrawLayer -> drawBounds(l.strokes, outW, outH, short)
        }
    }

    private fun strokeBounds(strokes: List<BrushStroke>, outW: Int, outH: Int): LayerBox? {
        val pts = strokes.flatMap { it.points }; if (pts.isEmpty()) return null
        val long = max(outW, outH); val r = strokes.maxOf { it.radius } * long
        val l = pts.minOf { it.x } * outW - r; val t = pts.minOf { it.y } * outH - r; val rr = pts.maxOf { it.x } * outW + r; val bb = pts.maxOf { it.y } * outH + r
        return LayerBox((l + rr) / 2, (t + bb) / 2, rr - l, bb - t, 0f)
    }

    private fun drawBounds(strokes: List<DrawStroke>, outW: Int, outH: Int, short: Float): LayerBox? {
        val pts = strokes.filter { it.brush != BrushKind.ERASER }.flatMap { it.points }; if (pts.isEmpty()) return null
        val r = strokes.maxOf { it.width } * short
        val l = pts.minOf { it.x } * outW - r; val t = pts.minOf { it.y } * outH - r; val rr = pts.maxOf { it.x } * outW + r; val bb = pts.maxOf { it.y } * outH + r
        return LayerBox((l + rr) / 2, (t + bb) / 2, rr - l, bb - t, 0f)
    }

    private fun drawText(c: Canvas, l: TextLayer, outW: Int, outH: Int, short: Float) {
        val p = textPaint(l, short)
        val lay = layout(l, p)
        val pad = textPad(l, short)
        val alpha = (l.opacity.coerceIn(0f, 1f) * 255).roundToInt()
        c.save()
        c.translate(l.center.x * outW, l.center.y * outH); c.rotate(l.rotation)
        if (alpha < 255) c.saveLayerAlpha(null, alpha)
        c.translate(-lay.width / 2f, -lay.height / 2f)
        when (l.style) {
            TextStyle.PILL -> {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.accent }
                c.drawRoundRect(RectF(-pad, -pad * 0.6f, lay.width + pad, lay.height + pad * 0.6f), pad * 1.2f, pad * 1.2f, bg)
                lay.draw(c)
            }
            TextStyle.OUTLINE -> {
                val stroke = TextPaint(p).apply { style = Paint.Style.STROKE; strokeWidth = p.textSize * 0.14f; strokeJoin = Paint.Join.ROUND; color = l.accent }
                StaticLayout.Builder.obtain(l.text.ifEmpty { " " }, 0, l.text.ifEmpty { " " }.length, stroke, lay.width).setAlignment(lay.alignment).setIncludePad(false).build().draw(c)
                lay.draw(c)
            }
            TextStyle.SHADOW -> { p.setShadowLayer(p.textSize * 0.10f, 0f, p.textSize * 0.04f, 0x99000000.toInt()); layout(l, p).draw(c) }
            TextStyle.FILL -> lay.draw(c)
        }
        if (alpha < 255) c.restore()
        c.restore()
    }

    // Sticker

    private fun drawSticker(c: Canvas, l: StickerLayer, outW: Int, outH: Int, short: Float) {
        val s = l.size * short
        c.save()
        c.translate(l.center.x * outW, l.center.y * outH); c.rotate(l.rotation)
        val alpha = (l.opacity.coerceIn(0f, 1f) * 255).roundToInt()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.color; this.alpha = (Color.alpha(l.color) * alpha / 255) }
        val shadow = 0x66000000
        fill.setShadowLayer(s * 0.04f, 0f, s * 0.02f, shadow)
        val h = s / 2
        when (l.kind) {
            StickerKind.EMOJI -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = s * 0.86f; textAlign = Paint.Align.CENTER; this.alpha = alpha }
                val fm = p.fontMetrics
                c.drawText(l.value, 0f, -(fm.ascent + fm.descent) / 2, p)
            }
            StickerKind.ARROW -> c.drawPath(Path().apply {
                moveTo(-h, -h * 0.18f); lineTo(h * 0.2f, -h * 0.18f); lineTo(h * 0.2f, -h * 0.55f); lineTo(h, 0f)
                lineTo(h * 0.2f, h * 0.55f); lineTo(h * 0.2f, h * 0.18f); lineTo(-h, h * 0.18f); close() }, fill)
            StickerKind.HEART -> c.drawPath(Path().apply {
                moveTo(0f, h * 0.85f); cubicTo(-h * 1.3f, -h * 0.1f, -h * 0.55f, -h * 1.05f, 0f, -h * 0.4f)
                cubicTo(h * 0.55f, -h * 1.05f, h * 1.3f, -h * 0.1f, 0f, h * 0.85f); close() }, fill)
            StickerKind.STAR -> c.drawPath(star(5, h, h * 0.45f), fill)
            StickerKind.SPARKLE -> c.drawPath(star(4, h, h * 0.22f), fill)
            StickerKind.CHECK -> c.drawPath(Path().apply { moveTo(-h * 0.7f, 0f); lineTo(-h * 0.2f, h * 0.5f); lineTo(h * 0.75f, -h * 0.55f) },
                Paint(fill).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.16f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND })
            StickerKind.CROSS -> { val p = Paint(fill).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.16f; strokeCap = Paint.Cap.ROUND }
                c.drawLine(-h * 0.6f, -h * 0.6f, h * 0.6f, h * 0.6f, p); c.drawLine(h * 0.6f, -h * 0.6f, -h * 0.6f, h * 0.6f, p) }
            StickerKind.CIRCLE -> c.drawCircle(0f, 0f, h * 0.85f, Paint(fill).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.08f })
            StickerKind.BUBBLE -> c.drawPath(Path().apply {
                addRoundRect(RectF(-h, -h * 0.75f, h, h * 0.45f), h * 0.4f, h * 0.4f, Path.Direction.CW)
                moveTo(-h * 0.35f, h * 0.4f); lineTo(-h * 0.55f, h * 0.95f); lineTo(h * 0.05f, h * 0.4f); close() }, fill)
            StickerKind.DATE -> {
                val hh = s * 0.8f
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = hh * 0.62f; textAlign = Paint.Align.CENTER; color = l.color; typeface = Typeface.create("monospace", Typeface.BOLD)
                    setShadowLayer(hh * 0.2f, 0f, 0f, (l.color and 0x00FFFFFF) or 0xAA000000.toInt()); this.alpha = alpha }
                val fm = p.fontMetrics
                c.drawText(l.value, 0f, -(fm.ascent + fm.descent) / 2, p)
            }
        }
        c.restore()
    }

    private fun star(points: Int, outer: Float, inner: Float) = Path().apply {
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) outer else inner
            val a = Math.PI / points * i - Math.PI / 2
            val x = (r * cos(a)).toFloat(); val y = (r * sin(a)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    // Zeichnen

    /** Geglätteter Pfad durch die Punkte (Mittelpunkte als Stützstellen). */
    fun smoothPath(pts: List<NormPoint>, outW: Int, outH: Int): Path = Path().apply {
        if (pts.isEmpty()) return@apply
        val p0 = pts[0]; moveTo(p0.x * outW, p0.y * outH)
        if (pts.size == 1) { lineTo(p0.x * outW + 0.1f, p0.y * outH); return@apply }
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            quadTo(a.x * outW, a.y * outH, (a.x + b.x) / 2 * outW, (a.y + b.y) / 2 * outH)
        }
        val last = pts.last(); lineTo(last.x * outW, last.y * outH)
    }

    fun strokePaint(s: DrawStroke, short: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        strokeWidth = max(1f, s.width * short); color = s.color
        when (s.brush) {
            BrushKind.PEN -> Unit
            BrushKind.MARKER -> { alpha = 115; strokeCap = Paint.Cap.SQUARE; strokeWidth *= 2.2f }
            BrushKind.NEON -> maskFilter = BlurMaskFilter(max(1f, strokeWidth * 0.9f), BlurMaskFilter.Blur.SOLID)
            BrushKind.ERASER -> { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); strokeWidth *= 1.6f }
        }
    }

    private fun drawStrokes(c: Canvas, strokes: List<DrawStroke>, outW: Int, outH: Int, short: Float) {
        if (strokes.isEmpty()) return
        // Eigene Zeichenfläche: der Radierer löscht nur Striche dieser Ebene, nie das Foto.
        val count = c.saveLayer(null, null)
        for (s in strokes) {
            val path = smoothPath(s.points, outW, outH)
            val p = strokePaint(s, short)
            c.drawPath(path, p)
            if (s.brush == BrushKind.NEON) c.drawPath(path, Paint(p).apply { maskFilter = null; color = Color.WHITE; strokeWidth *= 0.35f })
        }
        c.restoreToCount(count)
    }

    // Formen

    private fun drawShape(c: Canvas, l: ShapeLayer, outW: Int, outH: Int, short: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = l.color; alpha = (Color.alpha(l.color) * l.opacity.coerceIn(0f, 1f)).roundToInt()
            style = if (l.filled && l.kind != ShapeKind.LINE && l.kind != ShapeKind.ARROW) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = max(1f, l.width * short); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val ax = l.start.x * outW; val ay = l.start.y * outH; val bx = l.end.x * outW; val by = l.end.y * outH
        val r = RectF(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by))
        when (l.kind) {
            ShapeKind.RECT -> c.drawRoundRect(r, p.strokeWidth * 0.6f, p.strokeWidth * 0.6f, p)
            ShapeKind.ELLIPSE -> c.drawOval(r, p)
            ShapeKind.LINE -> c.drawLine(ax, ay, bx, by, p)
            ShapeKind.ARROW -> {
                val len = hypot(bx - ax, by - ay); if (len < 1f) return
                val head = min(len * 0.45f, max(p.strokeWidth * 4.5f, len * 0.18f))
                val ang = atan2(by - ay, bx - ax)
                val baseX = bx - head * 0.8f * cos(ang); val baseY = by - head * 0.8f * sin(ang)
                c.drawLine(ax, ay, baseX, baseY, p)
                val hp = Paint(p).apply { style = Paint.Style.FILL }
                c.drawPath(Path().apply {
                    moveTo(bx, by)
                    lineTo(bx - head * cos(ang - 0.45f), by - head * sin(ang - 0.45f))
                    lineTo(bx - head * cos(ang + 0.45f), by - head * sin(ang + 0.45f)); close() }, hp)
            }
        }
    }

    // Abdecken (in die Pixel)

    private fun redactPath(l: RedactLayer, outW: Int, outH: Int, long: Float): Path {
        l.rect?.let { r -> return Path().apply { addRect(r.left * outW, r.top * outH, r.right * outW, r.bottom * outH, Path.Direction.CW) } }
        val out = Path()
        for (s in l.strokes) {
            val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = s.radius * long * 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            val fill = Path(); stroke.getFillPath(smoothPath(s.points, outW, outH), fill); out.op(fill, Path.Op.UNION)
        }
        return out
    }

    private fun redact(b: Bitmap, c: Canvas, l: RedactLayer, outW: Int, outH: Int, short: Float, long: Float, view: RectF, sx: Float, sy: Float) {
        val path = redactPath(l, outW, outH, long)
        if (path.isEmpty) return
        if (l.mode == RedactMode.SOLID) { c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.color or 0xFF000000.toInt() }); return }
        // Betroffenen Bereich in Bitmap-Pixeln bestimmen, grob verarbeiten, durch den Pfad zurückzeichnen.
        val bounds = RectF(); path.computeBounds(bounds, true)
        val px = Rect(((bounds.left - view.left) * sx).toInt().coerceIn(0, b.width), ((bounds.top - view.top) * sy).toInt().coerceIn(0, b.height),
            kotlin.math.ceil((bounds.right - view.left) * sx).toInt().coerceIn(0, b.width), kotlin.math.ceil((bounds.bottom - view.top) * sy).toInt().coerceIn(0, b.height))
        if (px.width() < 1 || px.height() < 1) return
        val region = Bitmap.createBitmap(b, px.left, px.top, px.width(), px.height())
        val block = max(2f, l.blockPx(short.roundToInt()) * sx)
        val processed = when (l.mode) {
            RedactMode.PIXELATE -> {
                val small = Bitmap.createScaledBitmap(region, max(1, (px.width() / block).roundToInt()), max(1, (px.height() / block).roundToInt()), true)
                Bitmap.createScaledBitmap(small, px.width(), px.height(), false)
            }
            else -> {
                // Starke Unschärfe: zweimal grob verkleinern und weich vergrößern (Details sind danach nicht mehr vorhanden).
                var cur = region
                repeat(2) {
                    val small = Bitmap.createScaledBitmap(cur, max(1, (px.width() / (block * 1.6f)).roundToInt()), max(1, (px.height() / (block * 1.6f)).roundToInt()), true)
                    cur = Bitmap.createScaledBitmap(small, px.width(), px.height(), true)
                }
                cur
            }
        }
        c.save()
        c.clipPath(path)
        c.drawBitmap(processed, null, RectF(view.left + px.left / sx, view.top + px.top / sy, view.left + px.right / sx, view.top + px.bottom / sy), Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
    }

    // Rahmen

    /** Neues Bitmap mit Rahmen (außen: größer; innen: gleiche Größe). Bild unverändert, wenn kein Rahmen. */
    fun frame(src: Bitmap, f: FrameStyle?): Bitmap {
        if (f == null || f.isNeutral) return src
        val (size, pos) = FrameMath.layout(PxSize(src.width, src.height), f)
        val short = min(src.width, src.height).toFloat()
        val radius = f.corner.coerceIn(0f, 0.5f) * short
        val out = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val img = RectF(pos.first.toFloat(), pos.second.toFloat(), (pos.first + src.width).toFloat(), (pos.second + src.height).toFloat())
        c.drawColor(f.color or 0xFF000000.toInt())
        if (radius > 0f) {
            c.save(); c.clipPath(Path().apply { addRoundRect(img, radius, radius, Path.Direction.CW) }); c.drawBitmap(src, img.left, img.top, paint); c.restore()
        } else c.drawBitmap(src, img.left, img.top, paint)
        if (f.mode == FrameMode.INSIDE) {
            val w = f.width.coerceIn(0f, 0.25f) * short
            if (w > 0f) c.drawRect(img.left + w / 2, img.top + w / 2, img.right - w / 2, img.bottom - w / 2,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w; color = f.color or 0xFF000000.toInt() })
        }
        return out
    }
}
