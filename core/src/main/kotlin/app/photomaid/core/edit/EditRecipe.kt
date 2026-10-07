package app.photomaid.core.edit

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Normalisiertes Rechteck (0…1) im Bildrahmen nach Vierteldrehung/Spiegeln. */
@Serializable
data class NormRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = abs(left) < EPS && abs(top) < EPS && abs(right - 1f) < EPS && abs(bottom - 1f) < EPS
    companion object { const val EPS = 1e-4f; val FULL = NormRect() }
}

/**
 * Regler −100…+100 (Schärfe, Körnung 0…100). 0 = keine Änderung. Jeder Regler hat eine eigene Wirkung
 * (siehe [PixelPipeline]): Lichter ändern nur helle, Schatten nur dunkle Bereiche; Belichtung wirkt linear auf das Licht.
 */
@Serializable
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val sharpness: Float = 0f,
    val exposure: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val clarity: Float = 0f,
    /** Schärfen: Radius 0…100 (0 = automatisch nach Bildgröße) und Schwelle 0…100 (schützt Flächen/Rauschen). */
    val sharpenRadius: Float = 0f,
    val sharpenThreshold: Float = 0f,
) {
    private fun nz(v: Float) = abs(v) >= 0.5f
    /** Punktweise Tonwert-/Farbänderung (unabhängig von Nachbarpixeln und Position). */
    val pointNeutral: Boolean get() = listOf(brightness, contrast, saturation, warmth, exposure, highlights, shadows, whites, blacks, tint, vibrance).none(::nz)
    /** Positionsabhängig (Vignette, Körnung). */
    val spatialNeutral: Boolean get() = !nz(vignette) && grain < 0.5f
    val detailNeutral: Boolean get() = sharpness < 0.5f && !nz(clarity)
    val isNeutral: Boolean get() = pointNeutral && spatialNeutral && detailNeutral
    val colorNeutral: Boolean get() = pointNeutral && spatialNeutral
}

/** Ein Farbbereich des Farbmischers: Farbton ±100 (= ±30°), Sättigung, Helligkeit −100…+100. */
@Serializable
data class HslShift(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f) {
    val isNeutral: Boolean get() = abs(hue) < 0.5f && abs(saturation) < 0.5f && abs(luminance) < 0.5f
}

/** Selektive Farben: nur der gewählte Farbbereich ändert sich (weiche Übergänge zu den Nachbarn, Grau bleibt grau). */
@Serializable
enum class ColorBand(val centerDeg: Float) { RED(0f), ORANGE(30f), YELLOW(60f), GREEN(120f), AQUA(180f), BLUE(225f), PURPLE(270f), MAGENTA(315f) }

@Serializable
data class ColorMix(val shifts: Map<ColorBand, HslShift> = emptyMap()) {
    val isNeutral: Boolean get() = shifts.values.all { it.isNeutral }
    operator fun get(b: ColorBand): HslShift = shifts[b] ?: HslShift()
    fun with(b: ColorBand, s: HslShift): ColorMix = ColorMix(shifts + (b to s))
}

/** Normierter Punkt (0…1) im Ausgabebild. */
@Serializable
data class NormPoint(val x: Float, val y: Float)

/** Pinselstrich (Ausgabekoordinaten 0…1, Radius relativ zur langen Kante, Härte 0…1). */
@Serializable
data class BrushStroke(val points: List<NormPoint>, val radius: Float, val hardness: Float = 0.5f, val erase: Boolean = false)

/** Maske einer lokalen Anpassung. Rein manuell (keine Erkennung). */
@Serializable
sealed interface Mask {
    /** Ellipse um [center] mit Halbachsen relativ zur Bildbreite/-höhe, [feather] 0…1 weiche Kante. */
    @Serializable data class Radial(val center: NormPoint, val rx: Float, val ry: Float, val feather: Float = 0.5f, val invert: Boolean = false) : Mask
    /** Verlauf von [start] (voll) nach [end] (null). */
    @Serializable data class Linear(val start: NormPoint, val end: NormPoint) : Mask
    @Serializable data class Brush(val strokes: List<BrushStroke>) : Mask
}

/** Lokale Anpassung: Maske + Regler (nur punktweise Regler wirken lokal). */
@Serializable
data class LocalAdjustment(val mask: Mask, val adjust: Adjustments = Adjustments())

/** Viereck (normiert): oben links, oben rechts, unten rechts, unten links. */
@Serializable
data class Quad(val tl: NormPoint = NormPoint(0f, 0f), val tr: NormPoint = NormPoint(1f, 0f), val br: NormPoint = NormPoint(1f, 1f), val bl: NormPoint = NormPoint(0f, 1f)) {
    val isIdentity: Boolean get() = listOf(tl.x, tl.y, tr.x - 1f, tr.y, br.x - 1f, br.y - 1f, bl.x, bl.y - 1f).all { abs(it) < NormRect.EPS }
    val corners: List<NormPoint> get() = listOf(tl, tr, br, bl)
    /** Nach Vierteldrehung des Rahmens im Uhrzeigersinn (Ecken wandern mit). */
    fun rotatedClockwise(): Quad { val r = { p: NormPoint -> NormPoint(1f - p.y, p.x) }; return Quad(r(bl), r(tl), r(tr), r(br)) }
    fun flippedH(): Quad { val f = { p: NormPoint -> NormPoint(1f - p.x, p.y) }; return Quad(f(tr), f(tl), f(bl), f(br)) }
    fun flippedV(): Quad { val f = { p: NormPoint -> NormPoint(p.x, 1f - p.y) }; return Quad(f(bl), f(br), f(tr), f(tl)) }
    companion object { val FULL = Quad() }
}

/** Rote Augen: Kreis um die Pupille (Ausgabekoordinaten, Radius relativ zur langen Kante). Klassische Farbregel. */
@Serializable
data class RedEyeSpot(val center: NormPoint, val radius: Float)

/** Fest eingebaute Filter (reine Farbmatrizen, kein Download, keine KI). */
@Serializable
enum class FilterId { NONE, MONO, MONO_CONTRAST, WARM, COOL, SEPIA, FADE, VIVID }

/**
 * Nicht destruktive Bearbeitung eines Fotos. Wird erst beim Speichern auf das Original angewendet; Vorschau und Export
 * nutzen dieselbe Beschreibung (gleiche Mathematik, andere Auflösung).
 */
@Serializable
data class EditRecipe(
    /** Vierteldrehungen im Uhrzeigersinn (0…3). */
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** Begradigen in Grad (−45…45), Bild wird so vergrößert, dass keine leeren Ecken entstehen. */
    val straighten: Float = 0f,
    val crop: NormRect = NormRect.FULL,
    val adjust: Adjustments = Adjustments(),
    val filter: FilterId = FilterId.NONE,
    /** Filterstärke 0…1. */
    val filterIntensity: Float = 1f,
    /** Zielgröße in Pixeln; `null` = Größe des Ausschnitts. Nie größer als der Ausschnitt. */
    val resizeWidth: Int? = null,
    val resizeHeight: Int? = null,
    val colorMix: ColorMix = ColorMix(),
    val locals: List<LocalAdjustment> = emptyList(),
    /** Markieren: Text, Sticker, Zeichnungen, Formen, Abdeckungen (in dieser Reihenfolge gezeichnet). */
    val layers: List<Layer> = emptyList(),
    val frame: FrameStyle? = null,
    /** Perspektive im Grundrahmen (nach Drehen/Begradigen); `null` = keine. Kostenlos wie Zuschneiden. */
    val perspective: Quad? = null,
    /** Rote-Augen-Korrektur (kostenlos). */
    val redEyes: List<RedEyeSpot> = emptyList(),
) {
    val turns: Int get() = ((quarterTurns % 4) + 4) % 4
    val hasPerspective: Boolean get() = perspective != null && !perspective.isIdentity
    val hasGeometry: Boolean get() = turns != 0 || flipH || flipV || abs(straighten) > 0.01f || !crop.isFull || hasPerspective
    val hasRedEye: Boolean get() = redEyes.isNotEmpty()
    /** Ändert Pixelwerte (Tonwerte, Farbe, Filter, Vignette, Körnung, Farbmischer, lokale Anpassungen). */
    val hasColor: Boolean get() = !adjust.colorNeutral || (filter != FilterId.NONE && filterIntensity > 0.001f) || !colorMix.isNeutral ||
        locals.any { !it.adjust.pointNeutral }
    /** Schärfen oder Klarheit (Nachbarpixel nötig). */
    val hasSharpen: Boolean get() = !adjust.detailNeutral
    val hasResize: Boolean get() = resizeWidth != null && resizeHeight != null
    val hasLayers: Boolean get() = layers.isNotEmpty()
    val hasFrame: Boolean get() = frame != null && !frame.isNeutral
    val isIdentity: Boolean get() = !hasGeometry && !hasColor && !hasSharpen && !hasResize && !hasLayers && !hasFrame && !hasRedEye
    /** Pixel ändern sich über die Pipeline (Farbe oder Rote Augen). */
    val hasPixelOps: Boolean get() = hasColor || hasRedEye

    /** Nur Drehen/Spiegeln – bei JPEG verlustfrei über die EXIF-Ausrichtung möglich. */
    val isOrientationOnly: Boolean get() =
        (turns != 0 || flipH || flipV) && abs(straighten) <= 0.01f && crop.isFull && !hasPerspective && !hasRedEye && !hasColor && !hasSharpen && !hasResize && !hasLayers && !hasFrame

    /** Pro-Werkzeuge (Regler, Filter, Größe, Rahmen) – Zuschneiden/Drehen und Markieren sind kostenlos. */
    val usesProTools: Boolean get() = hasColor || hasSharpen || hasResize || hasFrame

    fun rotatedClockwise(): EditRecipe = copy(quarterTurns = (turns + 1) % 4, crop = crop.rotatedClockwise(), perspective = perspective?.rotatedClockwise(),
        resizeWidth = null, resizeHeight = null)
}

/** Ausschnitt nach einer Vierteldrehung im Uhrzeigersinn (der Rahmen dreht mit). */
fun NormRect.rotatedClockwise(): NormRect = NormRect(left = 1f - bottom, top = left, right = 1f - top, bottom = right)

/** Ausschnitt nach horizontalem bzw. vertikalem Spiegeln. */
fun NormRect.flippedH(): NormRect = NormRect(1f - right, top, 1f - left, bottom)
fun NormRect.flippedV(): NormRect = NormRect(left, 1f - bottom, right, 1f - top)

/** Speicherart, die der Nutzer im Speichern-Blatt wählt (die letzte Wahl wird gemerkt). */
@Serializable
enum class SaveMode {
    /** Als Kopie neben dem Original speichern; Original bleibt unverändert. */
    COPY,
    /** Bearbeitete Fassung ersetzt das Original; das Original kommt in den Android-Papierkorb (30 Tage wiederherstellbar). */
    REPLACE,
}

/** Video-Schnitt in Millisekunden. */
@Serializable
data class TrimRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
    companion object {
        const val MIN_MS = 1_000L
        fun clamp(start: Long, end: Long, totalMs: Long): TrimRange {
            val s = start.coerceIn(0, maxOf(0, totalMs - MIN_MS))
            val e = end.coerceIn(s + MIN_MS, maxOf(s + MIN_MS, totalMs))
            return TrimRange(s, minOf(e, totalMs))
        }
    }
}

/** Eigene Vorlage: nur Einstellungen (Regler, Filter, Farbmischer) – keine Bilder, keine Geometrie. Bleibt lokal. */
@Serializable
data class EditPreset(
    val id: String,
    val name: String,
    val adjust: Adjustments,
    val filter: FilterId = FilterId.NONE,
    val filterIntensity: Float = 1f,
    val colorMix: ColorMix = ColorMix(),
) {
    /** Übernimmt Farbe/Tonwerte; Zuschnitt, Größe und lokale Masken des Fotos bleiben. */
    fun applyTo(r: EditRecipe): EditRecipe = r.copy(adjust = adjust, filter = filter, filterIntensity = filterIntensity, colorMix = colorMix)

    companion object {
        fun of(name: String, r: EditRecipe, id: String = java.util.UUID.randomUUID().toString()) =
            EditPreset(id, name.take(40), r.adjust, r.filter, r.filterIntensity, r.colorMix)
    }
}
