package app.photomaid.core.studio

import app.photomaid.core.edit.NormRect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Pixelgröße (Breite × Höhe). */
data class VSize(val width: Int, val height: Int) {
    val aspect: Float get() = width.toFloat() / max(1, height)
}

/** Ergebnis-Layout eines Clips: Ausschnitt nach Drehen, Ausgabegröße, Füllen/Einpassen. */
data class VideoLayout(
    /** Bild nach Quelldrehung und Vierteldrehungen (vor dem Ausschnitt). */
    val rotated: VSize,
    /** Ausschnitt in Pixeln von [rotated]. */
    val cropped: VSize,
    /** Ausgabegröße (gerade Werte, nie größer als der Ausschnitt). */
    val output: VSize,
    /** `true` = Bild füllt die Ausgabe (Ränder werden abgeschnitten), sonst ganzes Bild mit Hintergrund. */
    val fill: Boolean,
)

/**
 * Reine Rechnung für den Video-Editor – gleiche Werte für Vorschau, Export und Tests: Abschnitte nach Schnitten und
 * Tempo, Zeitumrechnung Quelle ↔ Ergebnis, Lautstärke mit Ein-/Ausblenden, Ausgabegröße, Größenschätzung („ca.“).
 */
object VideoPlan {
    /** Ein behaltener Abschnitt: Quellzeit und Lage im Ergebnis (nach Tempo). */
    data class Segment(val source: TimeRange, val outStartMs: Long, val outEndMs: Long)

    fun segments(clip: VideoClip): List<Segment> {
        val sp = speedOf(clip)
        var t = 0.0
        return clip.keptSegments().map { r ->
            val start = t; t += r.durationMs / sp
            Segment(r, start.roundToLong(), t.roundToLong())
        }
    }

    fun speedOf(clip: VideoClip): Double = clip.speed.coerceIn(0.25f, 4f).toDouble()

    /** Ergebniszeit eines Quellzeitpunkts; `null`, wenn er weggeschnitten ist. */
    fun toOutputMs(clip: VideoClip, sourceMs: Long): Long? {
        val sp = speedOf(clip)
        for (s in segments(clip)) if (sourceMs >= s.source.startMs && sourceMs <= s.source.endMs)
            return s.outStartMs + ((sourceMs - s.source.startMs) / sp).roundToLong()
        return null
    }

    /** Quellzeit zu einer Ergebniszeit (am Ende geklemmt). */
    fun toSourceMs(clip: VideoClip, outMs: Long): Long {
        val sp = speedOf(clip)
        val segs = segments(clip)
        if (segs.isEmpty()) return clip.trimStartMs
        for (s in segs) if (outMs < s.outEndMs) return s.source.startMs + ((max(0L, outMs - s.outStartMs)) * sp).roundToLong()
        return segs.last().source.endMs
    }

    /** Lautstärke (0…1) zur Ergebniszeit: Grundlautstärke × Einblenden × Ausblenden; stumm = 0. */
    fun gain(clip: VideoClip, outMs: Long): Float {
        if (clip.muted) return 0f
        val total = clip.outputDurationMs
        var g = clip.volume.coerceIn(0f, 2f)
        if (clip.fadeInMs > 0 && outMs < clip.fadeInMs) g *= (outMs.toFloat() / clip.fadeInMs).coerceIn(0f, 1f)
        if (clip.fadeOutMs > 0 && outMs > total - clip.fadeOutMs) g *= ((total - outMs).toFloat() / clip.fadeOutMs).coerceIn(0f, 1f)
        return g
    }

    /**
     * Layout: [display] = Größe, wie das Video angezeigt wird (Quelldrehung schon berücksichtigt). Vierteldrehungen des
     * Clips, dann Ausschnitt, dann Seitenverhältnis des Projekts (`null` = wie der Ausschnitt) und Profil.
     */
    fun layout(display: VSize, clip: VideoClip, project: VideoProject): VideoLayout {
        val rotated = if (((clip.quarterTurns % 4) + 4) % 2 == 1) VSize(display.height, display.width) else display
        val c = clip.crop
        val cropped = VSize(max(2, (c.width * rotated.width).roundToInt()), max(2, (c.height * rotated.height).roundToInt()))
        val aspect = project.aspect ?: cropped.aspect
        // Rahmen im gewünschten Seitenverhältnis, so groß, dass der Ausschnitt (eingepasst bzw. füllend) nicht hochskaliert wird.
        val frame = if (project.fill) {
            // Füllen: Rahmen liegt innerhalb des Ausschnitts.
            if (cropped.aspect > aspect) VSize((cropped.height * aspect).roundToInt(), cropped.height) else VSize(cropped.width, (cropped.width / aspect).roundToInt())
        } else {
            // Einpassen: Rahmen umschließt den Ausschnitt (Ränder mit Hintergrund).
            if (cropped.aspect > aspect) VSize(cropped.width, (cropped.width / aspect).roundToInt()) else VSize((cropped.height * aspect).roundToInt(), cropped.height)
        }
        val (w, h) = VideoRules.targetSize(max(2, frame.width), max(2, frame.height), project.profile)
        return VideoLayout(rotated, cropped, VSize(max(2, w), max(2, h)), project.fill)
    }

    /** Ausschnitt in Normalgeräte-Koordinaten (−1…1, oben = +1) für Media3 `Crop`. */
    fun cropNdc(r: NormRect): FloatArray = floatArrayOf(2f * r.left - 1f, 2f * r.right - 1f, 1f - 2f * r.bottom, 1f - 2f * r.top)

    /** Geschätzte Dateigröße in Bytes – in der Oberfläche immer als „ca.“ beschriftet. */
    fun estimateBytes(project: VideoProject, layout: VideoLayout, fps: Float): Long {
        val clip = project.clips.first()
        val audio = !project.removeAudio && !clip.muted
        return VideoRules.estimateBytes(layout.output.width, layout.output.height, fps.coerceIn(1f, 120f), clip.outputDurationMs, project.profile, audio)
    }

    /** Video-Bitrate für den Encoder (Bit/s) aus Profil, Größe und Bildrate. */
    fun videoBitrate(layout: VideoLayout, fps: Float, profile: ExportProfile): Int =
        (layout.output.width.toLong() * layout.output.height * fps.coerceIn(1f, 120f) * profile.bitsPerPixel).toLong().coerceIn(500_000L, 120_000_000L).toInt()

    /** Sichtbar zur Ergebniszeit (mit Einblenden 200 ms an den Rändern): Deckkraft 0…1. */
    fun layerAlpha(l: TimedLayer, outMs: Long, fadeMs: Long = 200): Float {
        if (outMs < l.startMs || outMs > l.endMs) return 0f
        val inA = if (fadeMs <= 0) 1f else min(1f, (outMs - l.startMs).toFloat() / fadeMs)
        val outA = if (fadeMs <= 0 || l.endMs == Long.MAX_VALUE) 1f else min(1f, (l.endMs - outMs).toFloat() / fadeMs)
        return min(inA, outA).coerceIn(0f, 1f)
    }
}
