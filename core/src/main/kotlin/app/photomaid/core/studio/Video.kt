package app.photomaid.core.studio

import app.photomaid.core.edit.Adjustments
import app.photomaid.core.edit.FilterId
import app.photomaid.core.edit.Layer
import app.photomaid.core.edit.NormRect
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Zeitbereich in Millisekunden (Quellzeit eines Clips). */
@Serializable
data class TimeRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = max(0L, endMs - startMs)
    fun overlaps(o: TimeRange) = startMs < o.endMs && o.startMs < endMs
}

@Serializable
enum class Transition { NONE, FADE_BLACK }

/** Exportprofil: verständliche Stufen; „Erweitert“ = eigene Höhe/Bitrate. */
@Serializable
enum class ExportProfile(val height: Int, val bitsPerPixel: Float) {
    /** Original-Auflösung, hohe Bitrate. */
    HIGH(0, 0.14f),
    BALANCED(1080, 0.09f),
    SMALL(720, 0.06f),
}

/**
 * Ein Clip (Video oder Foto als Standbild). Alle Bearbeitungen sind Angaben – geschrieben wird erst beim Export.
 * [cuts] = entfernte Bereiche innerhalb von [trimStartMs]…[trimEndMs] (werden wirklich entfernt, nicht versteckt).
 */
@Serializable
data class VideoClip(
    val id: String,
    val mediaId: String,
    val isPhoto: Boolean = false,
    val sourceDurationMs: Long,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = sourceDurationMs,
    val cuts: List<TimeRange> = emptyList(),
    val speed: Float = 1f,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val crop: NormRect = NormRect.FULL,
    val adjust: Adjustments = Adjustments(),
    val filter: FilterId = FilterId.NONE,
    val filterIntensity: Float = 1f,
    val colorMix: app.photomaid.core.edit.ColorMix = app.photomaid.core.edit.ColorMix(),
    /** Foto-Clip: sanfter Zoom. */
    val kenBurns: Boolean = true,
) {
    /** Behaltene Abschnitte (Quellzeit), aufsteigend, ohne Schnitte. */
    fun keptSegments(): List<TimeRange> {
        val base = TimeRange(trimStartMs, trimEndMs)
        if (isPhoto) return listOf(base)
        var parts = listOf(base)
        for (c in cuts.sortedBy { it.startMs }) parts = parts.flatMap { p ->
            if (!p.overlaps(c)) listOf(p) else listOfNotNull(
                TimeRange(p.startMs, c.startMs).takeIf { it.durationMs >= VideoRules.MIN_SEGMENT_MS },
                TimeRange(c.endMs, p.endMs).takeIf { it.durationMs >= VideoRules.MIN_SEGMENT_MS })
        }
        return parts
    }

    /** Dauer im Ergebnis (nach Schnitten und Tempo). */
    val outputDurationMs: Long get() = (keptSegments().sumOf { it.durationMs } / speed.coerceIn(0.25f, 4f).toDouble()).roundToLong()
}

/** Text/Sticker über dem Video mit Ein- und Ausblendzeit (Zeit im Ergebnis). */
@Serializable
data class TimedLayer(val layer: Layer, val startMs: Long = 0, val endMs: Long = Long.MAX_VALUE)

/** Hintergrundmusik: eigene Datei (URI über die System-Dateiauswahl), Startpunkt in der Musik, Lautstärke. */
@Serializable
data class MusicTrack(val uri: String, val name: String, val offsetMs: Long = 0, val volume: Float = 0.8f, val fadeMs: Long = 1500)

@Serializable
data class VideoProject(
    val id: String,
    val clips: List<VideoClip>,
    /** Übergang je Naht (Größe = clips − 1). */
    val transitions: List<Transition> = List(max(0, clips.size - 1)) { Transition.NONE },
    val transitionMs: Long = 500,
    /** Ausgabe-Seitenverhältnis (Breite/Höhe); `null` = wie der erste Clip. */
    val aspect: Float? = null,
    /** Ausfüllen (zuschneiden) statt ganzes Bild zeigen (mit Hintergrund). */
    val fill: Boolean = false,
    val background: Int = 0xFF000000.toInt(),
    val music: MusicTrack? = null,
    /** Originalton aller Clips entfernen (Tonspur wird nicht geschrieben). */
    val removeAudio: Boolean = false,
    val layers: List<TimedLayer> = emptyList(),
    val profile: ExportProfile = ExportProfile.HIGH,
    /** Zielgröße (Arbeitsabläufe): feste Video-Bitrate und höchstens diese kurze Seite; `null` = aus dem Profil. */
    val targetBitrate: Int? = null,
    val targetShortSide: Int? = null,
) {
    val durationMs: Long get() = clips.sumOf { it.outputDurationMs }

    fun transitionAt(i: Int): Transition = transitions.getOrNull(i) ?: Transition.NONE

    fun withClips(next: List<VideoClip>): VideoProject = copy(clips = next, transitions = List(max(0, next.size - 1)) { transitions.getOrNull(it) ?: Transition.NONE })

    fun move(from: Int, to: Int): VideoProject {
        if (from !in clips.indices || to !in clips.indices) return this
        val l = clips.toMutableList(); val c = l.removeAt(from); l.add(to, c)
        return withClips(l)
    }
}

object VideoRules {
    const val MIN_SEGMENT_MS = 300L
    const val MIN_CLIP_MS = 500L
    const val PHOTO_DEFAULT_MS = 3000L
    const val MAX_CLIPS = 50
    const val MAX_TOTAL_MS = 30L * 60_000

    /** Geschätzte Größe in Bytes (Bitrate × Dauer + Ton). Wird in der Oberfläche ausdrücklich als „ca.“ gezeigt. */
    fun estimateBytes(width: Int, height: Int, fps: Float, durationMs: Long, profile: ExportProfile, audio: Boolean): Long {
        val videoBps = width.toLong() * height * fps * profile.bitsPerPixel
        val audioBps = if (audio) 128_000L else 0L
        return ((videoBps + audioBps) * durationMs / 8_000.0).roundToLong()
    }

    /** Zielgröße für ein Profil (nie hochskalieren, gerade Werte für Encoder). */
    fun targetSize(srcW: Int, srcH: Int, profile: ExportProfile, maxShortSide: Int? = null): Pair<Int, Int> {
        val short = min(srcW, srcH)
        val byProfile = if (profile.height == 0 || profile.height >= short) short else profile.height
        val h = min(byProfile, maxShortSide ?: byProfile)
        val scale = h.toDouble() / short
        fun even(v: Double) = (v.roundToLong().toInt() / 2) * 2
        return if (srcW <= srcH) even(srcW * scale) to even(srcH * scale) else even(srcW * scale) to even(srcH * scale)
    }

    /** Schnitt [r] (Quellzeit) zum Clip hinzufügen: überlappende Schnitte werden zusammengelegt. */
    fun addCut(clip: VideoClip, r: TimeRange): VideoClip {
        val clipped = TimeRange(max(clip.trimStartMs, r.startMs), min(clip.trimEndMs, r.endMs))
        if (clipped.durationMs < MIN_SEGMENT_MS) return clip
        val all = (clip.cuts + clipped).sortedBy { it.startMs }
        val merged = mutableListOf<TimeRange>()
        for (c in all) {
            val last = merged.lastOrNull()
            if (last != null && c.startMs <= last.endMs) merged[merged.lastIndex] = TimeRange(last.startMs, max(last.endMs, c.endMs)) else merged += c
        }
        val next = clip.copy(cuts = merged)
        // Nie alles wegschneiden.
        return if (next.keptSegments().sumOf { it.durationMs } < MIN_CLIP_MS) clip else next
    }
}
