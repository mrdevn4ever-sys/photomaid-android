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

/** Regler −100…+100 (Schärfe 0…100). 0 = keine Änderung. */
@Serializable
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val sharpness: Float = 0f,
) {
    val isNeutral: Boolean get() = listOf(brightness, contrast, saturation, warmth, sharpness).all { abs(it) < 0.5f }
    val colorNeutral: Boolean get() = listOf(brightness, contrast, saturation, warmth).all { abs(it) < 0.5f }
}

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
) {
    val turns: Int get() = ((quarterTurns % 4) + 4) % 4
    val hasGeometry: Boolean get() = turns != 0 || flipH || flipV || abs(straighten) > 0.01f || !crop.isFull
    val hasColor: Boolean get() = !adjust.colorNeutral || (filter != FilterId.NONE && filterIntensity > 0.001f)
    val hasSharpen: Boolean get() = adjust.sharpness >= 0.5f
    val hasResize: Boolean get() = resizeWidth != null && resizeHeight != null
    val isIdentity: Boolean get() = !hasGeometry && !hasColor && !hasSharpen && !hasResize

    /** Nur Drehen/Spiegeln – bei JPEG verlustfrei über die EXIF-Ausrichtung möglich. */
    val isOrientationOnly: Boolean get() =
        (turns != 0 || flipH || flipV) && abs(straighten) <= 0.01f && crop.isFull && !hasColor && !hasSharpen && !hasResize

    /** Pro-Werkzeuge (Regler, Filter, Größe) – Zuschneiden/Drehen sind kostenlos. */
    val usesProTools: Boolean get() = hasColor || hasSharpen || hasResize

    fun rotatedClockwise(): EditRecipe = copy(quarterTurns = (turns + 1) % 4, crop = crop.rotatedClockwise(), resizeWidth = null, resizeHeight = null)
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
