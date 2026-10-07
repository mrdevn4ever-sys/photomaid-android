package app.photomaid.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import app.photomaid.core.document.Crop
import app.photomaid.core.document.PdfLayout
import app.photomaid.core.document.PdfPage
import app.photomaid.core.document.PdfWriter
import app.photomaid.core.document.PtRect
import app.photomaid.core.document.SourceSize
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PDF aus Fotos (Paket 4). Vorschau und Export nutzen dieselbe Seitenaufteilung ([PdfLayout]) und denselben
 * Zeichenweg ([drawItem]) – die Vorschau ist das Dokument, nur kleiner. Die Bilder werden neu als JPEG kodiert:
 * Aufnahmedaten wie der Standort gelangen nicht ins PDF. Die Quellen bleiben unverändert.
 */
class PdfExporter(private val engine: ImageEditEngine) {
    /** Ausgerichtete Pixelmaße; unbekannt → aus der Datei gelesen. */
    suspend fun sizes(assets: List<MediaAsset>): List<SourceSize> = assets.map { a ->
        if (a.pixelWidth > 0 && a.pixelHeight > 0) SourceSize(a.pixelWidth, a.pixelHeight)
        else runCatching { engine.loadPreview(a.uri, a.mimeType, 64).full }.getOrNull()?.let { SourceSize(max(1, it.width), max(1, it.height)) }
            ?: SourceSize(1, 1)
    }

    /**
     * Schreibt das PDF nach [out]. [caption] liefert die Beschriftung je Bild (oder `null`). Fehlt ein Bild
     * (nicht lesbar), bleibt sein Feld leer und es wird in [Result.unreadable] gezählt – nie still übersprungen.
     */
    suspend fun export(assets: List<MediaAsset>, sizes: List<SourceSize>, pages: List<PdfPage>, caption: (Int) -> String?, out: File,
                       dpi: Int = 200, onProgress: (Float) -> Unit = {}): Result = withContext(engine.io) {
        var unreadable = 0
        out.outputStream().buffered().use { stream ->
            val w = PdfWriter(stream)
            val total = pages.sumOf { it.items.size }.coerceAtLeast(1); var done = 0
            for (p in pages) {
                val draws = ArrayList<PdfWriter.Draw>()
                for (it in p.items) {
                    coroutineContext.ensureActive()
                    val src = sizes[it.index]
                    val (pw, ph) = PdfLayout.pixelsFor(it.rect, src, it.crop, dpi)
                    val bmp = decodeCropped(assets[it.index], src, it.crop, pw, ph)
                    if (bmp == null) unreadable++ else {
                        val jpeg = ByteArrayOutputStream().also { o -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, o) }.toByteArray()
                        draws += PdfWriter.Draw(PdfWriter.Image.Jpeg(jpeg, bmp.width, bmp.height), it.rect)
                        bmp.recycle()
                    }
                    val text = caption(it.index); val capRect = it.caption
                    if (text != null && capRect != null) draws += PdfWriter.Draw(captionImage(text, capRect, dpi), capRect)
                    onProgress(++done / total.toFloat())
                }
                w.addPage(p.widthPt, p.heightPt, draws)
            }
            w.finish()
        }
        Result(pages.size, unreadable, out.length())
    }

    data class Result(val pages: Int, val unreadable: Int, val bytes: Long)

    /** Vorschau einer Seite: [widthPx] breit, gleiche Rechtecke und Ausschnitte wie das PDF. */
    suspend fun preview(page: PdfPage, assets: List<MediaAsset>, sizes: List<SourceSize>, caption: (Int) -> String?, widthPx: Int,
                        cache: MutableMap<String, Bitmap>): Bitmap = withContext(engine.io) {
        val s = widthPx / page.widthPt
        val out = Bitmap.createBitmap(widthPx, max(1, (page.heightPt * s).roundToInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.WHITE)
        for (it in page.items) {
            val a = assets[it.index]
            val bmp = cache.getOrPut(a.id) { runCatching { engine.loadPreview(a.uri, a.mimeType, 900).preview }.getOrNull() ?: return@getOrPut placeholder() }
            drawItem(c, bmp, it.crop, it.rect, s)
            val text = caption(it.index); val capRect = it.caption
            if (text != null && capRect != null) drawCaption(c, text, capRect, s)
        }
        out
    }

    private fun placeholder(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }

    /** Gemeinsamer Zeichenweg: Ausschnitt der Quelle in das Rechteck (gleiches Seitenverhältnis, nie verzerrt). */
    private fun drawItem(c: Canvas, bmp: Bitmap, crop: Crop, rect: PtRect, scale: Double) {
        val src = Rect((crop.left * bmp.width).roundToInt(), (crop.top * bmp.height).roundToInt(),
            (crop.right * bmp.width).roundToInt(), (crop.bottom * bmp.height).roundToInt())
        val dst = RectF((rect.x * scale).toFloat(), (rect.y * scale).toFloat(), ((rect.x + rect.w) * scale).toFloat(), ((rect.y + rect.h) * scale).toFloat())
        c.drawBitmap(bmp, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun drawCaption(c: Canvas, text: String, rect: PtRect, scale: Double) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 40); textSize = (9.0 * scale).toFloat(); textAlign = Paint.Align.CENTER }
        val w = (rect.w * scale).toFloat()
        val t = TextUtils.ellipsize(text, p, w - 4 * scale.toFloat(), TextUtils.TruncateAt.MIDDLE).toString()
        val cy = ((rect.y + rect.h / 2) * scale).toFloat() - (p.descent() + p.ascent()) / 2
        c.drawText(t, ((rect.x + rect.w / 2) * scale).toFloat(), cy, p)
    }

    /** Beschriftung als Graustufen-Raster (alle Schriften des Systems, auch Arabisch/Japanisch). */
    private fun captionImage(text: String, rect: PtRect, dpi: Int): PdfWriter.Image.Gray {
        val s = dpi / 72.0
        val w = max(1, ceil(rect.w * s).toInt()); val h = max(1, ceil(rect.h * s).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        drawCaption(c, text, PtRect(0.0, 0.0, rect.w, rect.h), s)
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h); bmp.recycle()
        val gray = ByteArray(w * h) { i -> val v = px[i]; ((Color.red(v) * 299 + Color.green(v) * 587 + Color.blue(v) * 114) / 1000).toByte() }
        return PdfWriter.Image.Gray(gray, w, h)
    }

    /** Nur so groß dekodieren wie nötig, dann den Ausschnitt auf genau [w] × [h] bringen (Seitenverhältnis wie der Ausschnitt). */
    private suspend fun decodeCropped(a: MediaAsset, src: SourceSize, crop: Crop, w: Int, h: Int): Bitmap? {
        val frac = minOf(crop.right - crop.left, crop.bottom - crop.top).coerceAtLeast(0.01)
        val need = (max(w, h) / frac).roundToInt().coerceIn(64, max(src.width, src.height).coerceAtLeast(64))
        val full = runCatching { engine.loadPreview(a.uri, a.mimeType, need).preview }.getOrNull() ?: return null
        val r = Rect((crop.left * full.width).roundToInt(), (crop.top * full.height).roundToInt(),
            (crop.right * full.width).roundToInt(), (crop.bottom * full.height).roundToInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(full, r, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG)) }
        if (full !== out) full.recycle()
        return out
    }
}
