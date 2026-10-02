package app.photomaid.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import app.photomaid.core.edit.Affine
import app.photomaid.core.edit.ColorMatrices
import app.photomaid.core.edit.EditFormat
import app.photomaid.core.edit.EditGeometry
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.ExifOrientationMath
import app.photomaid.core.edit.PxSize
import app.photomaid.core.edit.Sharpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Fehler beim Bearbeiten (Grund nur fürs Log/Meldung, nie mit Pfaden oder Namen). */
sealed class EditError(msg: String) : Exception(msg) {
    /** Reicht der Arbeitsspeicher auch mit Ausschnitt-Dekodierung nicht. */
    class TooLarge : EditError("too-large")
    class Unreadable : EditError("unreadable")
    class WriteFailed(reason: String) : EditError(reason)
}

/** Ergebnis eines Exports: fertige Datei (Cache) + echte Werte. */
data class EditExport(val file: File, val format: EditFormat, val size: PxSize, val hdrDropped: Boolean, val lossless: Boolean)

/** Vorschau-Quelle: verkleinertes, korrekt ausgerichtetes Bild + echte Größe des Originals. */
data class EditSource(val preview: Bitmap, val full: PxSize, val mime: String?, val hasGainmap: Boolean)

/**
 * Lokale Bildbearbeitung ohne Netz und ohne Bildanalyse. Vorschau und Export nutzen dieselbe Geometrie
 * ([EditGeometry]) und dieselben Farbmatrizen – die Vorschau ist das Ergebnis, nur kleiner.
 * Das Original wird nie verändert: es wird nur gelesen.
 */
class ImageEditEngine(private val context: Context) {
    private val resolver get() = context.contentResolver
    /** Arbeits-Dispatcher (Tests: Robolectric-Native-Grafik ist nicht threadsicher neben laufender Compose-UI). */
    var io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

    /** MediaStore-URIs über den ContentResolver; `file://` (QA-Medien, Tests) direkt. */
    private fun input(uri: Uri): java.io.InputStream? =
        if (uri.scheme == "file") uri.path?.let { File(it) }?.takeIf { it.exists() }?.inputStream() else resolver.openInputStream(uri)

    private fun source(uri: Uri): ImageDecoder.Source =
        if (uri.scheme == "file") ImageDecoder.createSource(File(uri.path!!)) else ImageDecoder.createSource(resolver, uri)

    /** Verkleinerte Vorschau (lange Kante ≤ [maxEdge]), Ausrichtung nach EXIF, Farbraum bleibt. */
    suspend fun loadPreview(uri: Uri, mime: String?, maxEdge: Int = 1600): EditSource = withContext(io) {
        var full = PxSize(0, 0)
        var gain = false
        val bmp = runCatching {
            ImageDecoder.decodeBitmap(source(uri)) { d, info, _ ->
                full = PxSize(info.size.width, info.size.height)
                val s = min(1.0, maxEdge.toDouble() / max(info.size.width, info.size.height))
                d.setTargetSize(max(1, (info.size.width * s).roundToInt()), max(1, (info.size.height * s).roundToInt()))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = false
            }
        }.getOrNull() ?: decodeLegacy(uri, maxEdge)?.also { (b, f) -> full = f; return@withContext EditSource(b, f, mime, false) }?.first
            ?: throw EditError.Unreadable()
        if (Build.VERSION.SDK_INT >= 34) gain = bmp.hasGainmap()
        EditSource(bmp, full, mime, gain)
    }

    /** Fallback (z. B. Robolectric/ältere Decoder): BitmapFactory + EXIF-Ausrichtung. */
    private fun decodeLegacy(uri: Uri, maxEdge: Int): Pair<Bitmap, PxSize>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        input(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); true } ?: return null // nur Maße: Rückgabe ist immer null
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val raw = input(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val o = orientationOf(uri)
        val oriented = orient(raw, o)
        val full = if (o in 5..8) PxSize(bounds.outHeight, bounds.outWidth) else PxSize(bounds.outWidth, bounds.outHeight)
        return oriented to full
    }

    private fun orientationOf(uri: Uri): Int = runCatching {
        input(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /** Rohbild → angezeigte Ausrichtung (EXIF 1…8). */
    private fun orientationMatrix(o: Int): Matrix = Matrix().apply {
        when (o) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }

    private fun orient(b: Bitmap, o: Int): Bitmap =
        if (o <= 1 || o > 8) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, orientationMatrix(o), true)

    /**
     * Rendert das Rezept auf [src] (beliebige Auflösung derselben Aufnahme). [colorAndSharpen] = false für die
     * Live-Vorschau (Farbe kommt dort über die GPU). [extra] verschiebt zusätzlich (Ausschnitt-Dekodierung).
     */
    fun render(src: Bitmap, recipe: EditRecipe, colorAndSharpen: Boolean, sourceSize: PxSize = PxSize(src.width, src.height), extra: Affine = Affine()): Bitmap {
        val g = EditGeometry(sourceSize, recipe)
        val cs = src.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
        val out = Bitmap.createBitmap(g.output.width, g.output.height, Bitmap.Config.ARGB_8888, src.hasAlpha(), cs)
        val m = Matrix().apply { setValues((g.sourceToOutput * extra).toAndroidValues()) }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        if (colorAndSharpen && recipe.hasColor) paint.colorFilter = ColorMatrixColorFilter(ColorMatrices.forRecipe(recipe))
        Canvas(out).drawBitmap(src, m, paint)
        if (colorAndSharpen && recipe.hasSharpen) sharpenInStripes(out, recipe.adjust.sharpness)
        // Ultra HDR (Android 14+): Gain-Map bei reiner Geometrie mitführen, damit die HDR-Helligkeit erhalten bleibt.
        if (Build.VERSION.SDK_INT >= 34 && src.hasGainmap() && !recipe.hasColor && !recipe.hasSharpen) carryGainmap(src, out, g.sourceToOutput * extra)
        return out
    }

    @androidx.annotation.RequiresApi(34)
    private fun carryGainmap(src: Bitmap, out: Bitmap, m: Affine) {
        runCatching {
            val gm = src.gainmap ?: return
            val c = gm.gainmapContents
            val sx = c.width.toDouble() / src.width; val sy = c.height.toDouble() / src.height
            val gw = max(1, (out.width * sx).roundToInt()); val gh = max(1, (out.height * sy).roundToInt())
            val contents = Bitmap.createBitmap(gw, gh, c.config ?: Bitmap.Config.ALPHA_8)
            val gmM = Affine.scale(sx, sy) * m * Affine.scale(1 / sx, 1 / sy)
            Canvas(contents).drawBitmap(c, Matrix().apply { setValues(gmM.toAndroidValues()) }, Paint(Paint.FILTER_BITMAP_FLAG))
            val n = android.graphics.Gainmap(contents)
            n.setRatioMin(gm.ratioMin[0], gm.ratioMin[1], gm.ratioMin[2]); n.setRatioMax(gm.ratioMax[0], gm.ratioMax[1], gm.ratioMax[2])
            n.setGamma(gm.gamma[0], gm.gamma[1], gm.gamma[2]); n.setEpsilonSdr(gm.epsilonSdr[0], gm.epsilonSdr[1], gm.epsilonSdr[2])
            n.setEpsilonHdr(gm.epsilonHdr[0], gm.epsilonHdr[1], gm.epsilonHdr[2])
            n.displayRatioForFullHdr = gm.displayRatioForFullHdr; n.minDisplayRatioForHdrTransition = gm.minDisplayRatioForHdrTransition
            out.gainmap = n
        }
    }

    /** Schärfen in Streifen (wenig Speicher, auch bei großen Fotos); Überlappung = Radius, Ergebnis exakt. */
    private fun sharpenInStripes(b: Bitmap, sharpness: Float) {
        val r = Sharpen.radiusFor(max(b.width, b.height)); val amount = Sharpen.amount(sharpness)
        val stripe = 256
        // Erst alle Streifen aus dem ungeschärften Bild berechnen, dann schreiben (Nachbarstreifen bleiben unbeeinflusst).
        val results = ArrayList<Pair<Int, IntArray>>()
        var y = 0
        while (y < b.height) {
            val top = max(0, y - r); val bottom = min(b.height, y + stripe + r); val h = bottom - top
            val px = IntArray(b.width * h)
            b.getPixels(px, 0, b.width, 0, top, b.width, h)
            val sharp = Sharpen.apply(px, b.width, h, amount, r)
            val rows = min(stripe, b.height - y)
            results += y to sharp.copyOfRange((y - top) * b.width, (y - top + rows) * b.width)
            y += stripe
        }
        for ((row, data) in results) b.setPixels(data, 0, b.width, 0, row, b.width, data.size / b.width)
    }

    /**
     * Export in voller Auflösung in eine Cache-Datei. Das Original wird nur gelesen. Reihenfolge:
     * 1) nur Drehen/Spiegeln bei JPEG → verlustfrei (Bytes kopieren, nur EXIF-Ausrichtung ändern),
     * 2) normal: in der benötigten Auflösung dekodieren (Verkleinern übernimmt der Decoder), rendern, kodieren,
     * 3) zu wenig Speicher: nur den benötigten Bereich dekodieren (Ausschnitt), sonst [EditError.TooLarge].
     */
    suspend fun export(uri: Uri, mime: String?, recipe: EditRecipe, full: PxSize, removeLocation: Boolean, softwareTag: String): EditExport =
        withContext(io) {
            val format = EditFormat.forSource(mime)
            val out = File(context.cacheDir, "edit_${System.nanoTime()}.${format.extension}")
            try {
                if (recipe.isOrientationOnly && mime.equals("image/jpeg", true)) {
                    input(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } ?: throw EditError.Unreadable()
                    val ex = ExifInterface(out)
                    // Eigene Rechnung statt ExifInterface.rotate(): die bricht bei fehlender/0-Ausrichtung ab.
                    val cur = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    ex.setAttribute(ExifInterface.TAG_ORIENTATION, ExifOrientationMath.compose(cur, recipe.turns, recipe.flipH, recipe.flipV).toString())
                    if (removeLocation) clearLocation(ex)
                    ex.setAttribute(ExifInterface.TAG_SOFTWARE, softwareTag)
                    ex.saveAttributes()
                    val g = EditGeometry(full, recipe)
                    return@withContext EditExport(out, EditFormat.JPEG, g.output, hdrDropped = false, lossless = true)
                }
                val g = EditGeometry(full, recipe)
                // Verkleinern übernimmt der Decoder (hochwertiger, weniger Speicher).
                val scale = min(1.0, max(g.output.width.toDouble() / g.cropSize.width, g.output.height.toDouble() / g.cropSize.height))
                val dec = PxSize(max(1, (full.width * scale).roundToInt()), max(1, (full.height * scale).roundToInt()))
                val need = (dec.pixels + g.output.pixels) * 4 + g.output.width.toLong() * 512 * 4 * 3
                val bitmap: Bitmap; val extra: Affine; val srcSize: PxSize; var gain = false
                if (need <= budget()) {
                    bitmap = decodeFull(uri, dec)
                    if (Build.VERSION.SDK_INT >= 34) gain = bitmap.hasGainmap()
                    extra = Affine(); srcSize = dec
                } else {
                    val region = decodeRegion(uri, full, EditGeometry(full, recipe).sourceBounds(), scale) ?: throw EditError.TooLarge()
                    bitmap = region.first; srcSize = dec
                    extra = Affine.translate(region.second.left * scale, region.second.top * scale) * Affine.scale(region.third)
                    gain = false
                }
                // In der dekodierten Auflösung entspricht die Zielgröße dem (verkleinerten) Ausschnitt.
                val scaled = recipe.copy(resizeWidth = g.output.width, resizeHeight = g.output.height)
                val rendered = render(bitmap, scaled, colorAndSharpen = true, sourceSize = srcSize, extra = extra)
                bitmap.recycle()
                out.outputStream().use { o ->
                    val ok = when (format) {
                        EditFormat.JPEG -> rendered.compress(Bitmap.CompressFormat.JPEG, EditFormat.JPEG_QUALITY, o)
                        EditFormat.PNG -> rendered.compress(Bitmap.CompressFormat.PNG, 100, o)
                        EditFormat.WEBP -> rendered.compress(Bitmap.CompressFormat.WEBP_LOSSY, EditFormat.JPEG_QUALITY, o)
                    }
                    if (!ok) throw EditError.WriteFailed("encode")
                }
                val size = PxSize(rendered.width, rendered.height)
                rendered.recycle()
                copyExif(uri, out, size, removeLocation, softwareTag)
                EditExport(out, format, size, hdrDropped = gain && (recipe.hasColor || recipe.hasSharpen), lossless = false)
            } catch (e: OutOfMemoryError) {
                out.delete(); throw EditError.TooLarge()
            } catch (e: EditError) {
                out.delete(); throw e
            } catch (e: Exception) {
                out.delete(); throw EditError.WriteFailed(e.javaClass.simpleName)
            }
        }

    private fun budget(): Long {
        val rt = Runtime.getRuntime()
        return ((rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) * 0.8).toLong()
    }

    private fun decodeFull(uri: Uri, size: PxSize): Bitmap = runCatching {
        ImageDecoder.decodeBitmap(source(uri)) { d, info, _ ->
            if (info.size.width != size.width || info.size.height != size.height) d.setTargetSize(size.width, size.height)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull() ?: run {
        // Fallback (Robolectric): BitmapFactory + Ausrichtung + Skalierung.
        val raw = input(uri)?.use { BitmapFactory.decodeStream(it) } ?: throw EditError.Unreadable()
        val o = orient(raw, orientationOf(uri))
        if (o.width == size.width && o.height == size.height) o else Bitmap.createScaledBitmap(o, size.width, size.height, true)
    }

    /**
     * Nur den benötigten Bereich dekodieren (sehr große Fotos). Rückgabe: Bitmap (ausgerichtet, in Ziel-Skalierung),
     * Bereich in ausgerichteten Vollbild-Pixeln und Skalierung Bereich-Bitmap → Ziel.
     */
    private fun decodeRegion(uri: Uri, full: PxSize, bounds: IntArray, scale: Double): Triple<Bitmap, Rect, Double>? = runCatching {
        val o = orientationOf(uri)
        val raw = if (o in 5..8) PxSize(full.height, full.width) else full
        // Ausgerichteter Bereich → Rohbereich.
        val m = orientationMatrix(o)
        val dst = RectF(0f, 0f, raw.width.toFloat(), raw.height.toFloat()).also { m.mapRect(it) }
        m.postTranslate(-dst.left, -dst.top)
        val inv = Matrix().also { m.invert(it) }
        val r = RectF(bounds[0].toFloat(), bounds[1].toFloat(), bounds[2].toFloat(), bounds[3].toFloat()).also { inv.mapRect(it) }
        val rawRect = Rect(r.left.toInt().coerceAtLeast(0), r.top.toInt().coerceAtLeast(0), kotlin.math.ceil(r.right.toDouble()).toInt().coerceAtMost(raw.width),
            kotlin.math.ceil(r.bottom.toDouble()).toInt().coerceAtMost(raw.height))
        var sample = 1
        while (scale * sample * 2 <= 1.0) sample *= 2
        val need = rawRect.width().toLong() * rawRect.height() / (sample * sample) * 4 * 3
        if (need > budget()) return null
        val decoder = input(uri)?.use {
            if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(it) else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(it, false)
        } ?: return null
        val part = decoder.decodeRegion(rawRect, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        decoder.recycle()
        val oriented = orient(part, o)
        // Bereich in ausgerichteten Koordinaten.
        val back = RectF(rawRect).also { m.mapRect(it) }
        val area = Rect(back.left.toInt(), back.top.toInt(), back.right.toInt(), back.bottom.toInt())
        // Bitmap-Pixel → Ziel-Skalierung.
        Triple(oriented, area, scale * area.width() / oriented.width)
    }.getOrNull()

    private fun clearLocation(ex: ExifInterface) {
        for (t in GPS_TAGS) ex.setAttribute(t, null)
    }

    /** EXIF des Originals übernehmen (Datum, Kamera, Belichtung, optional Ort); Ausrichtung danach „normal“. */
    private fun copyExif(src: Uri, out: File, size: PxSize, removeLocation: Boolean, softwareTag: String) {
        runCatching {
            val from = input(src)?.use { ExifInterface(it) } ?: return
            val to = ExifInterface(out)
            for (t in COPY_TAGS + if (removeLocation) emptyList() else GPS_TAGS) from.getAttribute(t)?.let { to.setAttribute(t, it) }
            to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            to.setAttribute(ExifInterface.TAG_SOFTWARE, softwareTag)
            to.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, size.width.toString())
            to.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, size.height.toString())
            to.saveAttributes()
        }
    }

    companion object {
        private val COPY_TAGS = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FLASH, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        )
        val GPS_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_PROCESSING_METHOD,
        )
    }
}
