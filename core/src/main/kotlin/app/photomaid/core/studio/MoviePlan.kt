package app.photomaid.core.studio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * P6 „Video zusammenfügen“ und „Diashow als Video“: reine Rechnung für Vorschau, Export und Tests.
 * Mehrere Clips (Videos und Fotos als Standbild) nacheinander; Übergang je Naht „keiner“ oder „über Schwarz“
 * (Abdunkeln bis Schwarz und wieder auf, mittig auf der Naht – die Gesamtdauer bleibt gleich). Alle Clips werden in
 * eine gemeinsame Ausgabegröße eingepasst oder füllen sie – nie verzerrt.
 */
object MoviePlan {
    /** Startzeit jedes Clips im Ergebnis (ms). */
    fun starts(project: VideoProject): List<Long> {
        var t = 0L
        return project.clips.map { c -> t.also { t += c.outputDurationMs } }
    }

    fun totalMs(project: VideoProject): Long = project.clips.sumOf { it.outputDurationMs }

    /** Nähte (Zeit im Ergebnis) mit Übergang „über Schwarz“. */
    fun blackSeams(project: VideoProject): List<Long> {
        val s = starts(project)
        return (1 until project.clips.size).filter { project.transitionAt(it - 1) == Transition.FADE_BLACK }.map { s[it] }
    }

    /**
     * Deckkraft der Schwarzblende zur Ergebniszeit: 1 genau auf der Naht, linear auf 0 bis ±halbe Übergangsdauer.
     * Die halbe Dauer ist durch die kürzere Nachbardauer begrenzt, damit ein kurzer Clip nie ganz schwarz bleibt.
     */
    fun blackAlpha(project: VideoProject, outMs: Long): Float {
        val starts = starts(project)
        var a = 0f
        for (i in 1 until project.clips.size) {
            if (project.transitionAt(i - 1) != Transition.FADE_BLACK) continue
            val seam = starts[i]
            val half = min(project.transitionMs / 2, min(project.clips[i - 1].outputDurationMs, project.clips[i].outputDurationMs) / 3).coerceAtLeast(1)
            val d = abs(outMs - seam)
            if (d < half) a = max(a, 1f - d.toFloat() / half)
        }
        return a.coerceIn(0f, 1f)
    }

    /** Lautstärke der Musik zur Ergebniszeit: Grundlautstärke × Ein-/Ausblenden am Anfang und Ende des Films. */
    fun musicGain(project: VideoProject, outMs: Long): Float {
        val m = project.music ?: return 0f
        val total = totalMs(project)
        var g = m.volume.coerceIn(0f, 1f)
        if (m.fadeMs > 0) {
            if (outMs < m.fadeMs) g *= (outMs.toFloat() / m.fadeMs).coerceIn(0f, 1f)
            if (outMs > total - m.fadeMs) g *= ((total - outMs).toFloat() / m.fadeMs).coerceIn(0f, 1f)
        }
        return g
    }

    /**
     * Gemeinsame Ausgabegröße: Seitenverhältnis des Projekts (`null` = wie der erste Clip), kurze Kante nach Profil
     * (HIGH = 1080, nie größer als nötig), gerade Werte. [first] = Anzeigegröße des ersten Clips.
     */
    fun frame(project: VideoProject, first: VSize): VSize {
        val aspect = project.aspect ?: first.aspect
        val short = when (project.profile) { ExportProfile.HIGH -> 1080; ExportProfile.BALANCED -> 1080; ExportProfile.SMALL -> 720 }
            .coerceAtMost(max(2, min(first.width, first.height)).coerceAtLeast(480))
        val (w, h) = if (aspect >= 1f) (short * aspect).roundToInt() to short else short to (short / aspect).roundToInt()
        return VSize(even(w), even(h))
    }

    private fun even(v: Int) = max(2, v - v % 2)

    /** Sanfter Zoom eines Foto-Clips: Faktor 1,00 → 1,08 über die Clipdauer (Anteil 0…1). */
    fun kenBurnsScale(fraction: Float): Float = 1f + 0.08f * fraction.coerceIn(0f, 1f)

    /** Neuer Foto-Clip (Standbild) mit Dauer. */
    fun photoClip(id: String, mediaId: String, durationMs: Long = VideoRules.PHOTO_DEFAULT_MS, kenBurns: Boolean = true) =
        VideoClip(id = id, mediaId = mediaId, isPhoto = true, sourceDurationMs = durationMs, trimStartMs = 0, trimEndMs = durationMs, kenBurns = kenBurns)

    /** Neuer Video-Clip über die ganze Länge. */
    fun videoClip(id: String, mediaId: String, durationMs: Long) = VideoClip(id = id, mediaId = mediaId, sourceDurationMs = durationMs)

    /** Foto-Dauer ändern (Standbild: Quelle = Dauer). */
    fun withPhotoDuration(c: VideoClip, ms: Long): VideoClip = if (!c.isPhoto) c else ms.coerceIn(VideoRules.MIN_CLIP_MS, 60_000L).let { c.copy(sourceDurationMs = it, trimStartMs = 0, trimEndMs = it) }

    /** Grenzen ehrlich: max. Clips und Gesamtdauer. `null` = in Ordnung, sonst der Grund. */
    fun problem(project: VideoProject): Limit? = when {
        project.clips.size < 2 -> Limit.TOO_FEW
        project.clips.size > VideoRules.MAX_CLIPS -> Limit.TOO_MANY
        totalMs(project) > VideoRules.MAX_TOTAL_MS -> Limit.TOO_LONG
        else -> null
    }

    enum class Limit { TOO_FEW, TOO_MANY, TOO_LONG }

    /** Diashow aus Fotos: gleiche Dauer, sanfter Zoom, Übergang über Schwarz. */
    fun slideshow(id: String, photoIds: List<String>, seconds: Int = 3): VideoProject {
        val clips = photoIds.mapIndexed { i, m -> photoClip("$id-$i", m, seconds * 1000L) }
        return VideoProject(id, clips, transitions = List(max(0, clips.size - 1)) { Transition.FADE_BLACK }, transitionMs = 600)
    }
}
