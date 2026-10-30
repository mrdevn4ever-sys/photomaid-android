package app.photomaid.core.batch

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Gespeicherte Arbeitsabläufe (Ausbau Paket 2): mehrere vorhandene Stapel-Schritte als benannter Ablauf, z. B.
 * „Für Kleinanzeigen“: JPEG → längste Seite 2.000 px → Standort entfernen → Name „Kleinanzeige_001“ → Teilen.
 * Ein Ablauf erzeugt immer NEUE Dateien (oder nur Teil-Dateien im Cache); er löscht nie etwas, legt nichts in einen
 * Papierkorb und trifft nie eine Aufräum-Entscheidung über die Quellen. Zielgrößen sind Vorgaben, keine Behauptungen:
 * das Ergebnis wird gemessen; ist das Ziel innerhalb der Grenzen nicht erreichbar, wird das gesagt.
 */

@Serializable enum class WorkflowFormat { KEEP, JPEG, PNG }

/** Was am Ende passiert: in der Galerie speichern, nur teilen (Kopien im Cache) oder beides. */
@Serializable enum class WorkflowFinish { SAVE, SHARE, SAVE_AND_SHARE }

@Serializable
data class Workflow(
    val id: String,
    val name: String,
    val format: WorkflowFormat = WorkflowFormat.KEEP,
    /** Längste Seite höchstens (nie hochskaliert), `null` = Originalgröße. */
    val longEdge: Int? = null,
    val quality: Int = 85,
    val removeLocation: Boolean = false,
    /** Foto höchstens so groß (Bytes), `null` = keine Vorgabe. */
    val photoMaxBytes: Long? = null,
    /** Untergrenzen für die Zielgröße: so klein/so schlecht wird ein Foto nie. */
    val minLongEdge: Int = 1024,
    val minQuality: Int = 60,
    /** Video höchstens so groß (Bytes); `null` = Videos werden übersprungen. */
    val videoMaxBytes: Long? = null,
    /** Neuer Dateiname „Basis_001“; `null` = Originalname mit Zusatz. */
    val nameBase: String? = null,
    /** Ziel-Ordner (MediaStore `RELATIVE_PATH`); `null` = Ordner des Originals. */
    val folder: String? = null,
    val finish: WorkflowFinish = WorkflowFinish.SAVE,
) {
    val handlesVideos: Boolean get() = videoMaxBytes != null
    val saves: Boolean get() = finish != WorkflowFinish.SHARE
    val shares: Boolean get() = finish != WorkflowFinish.SAVE
}

/** Ein Schritt in der Beschreibung „Das passiert“ (Reihenfolge = Ausführungsreihenfolge). */
enum class WorkflowStep { FORMAT, RESIZE, TARGET_SIZE, QUALITY, LOCATION, VIDEO_TARGET, RENAME, FOLDER, SAVE, SHARE }

object WorkflowRules {
    const val MAX_NAME = 40
    const val MIN_PHOTO_BYTES = 50_000L
    const val MIN_VIDEO_BYTES = 1_000_000L
    val PHOTO_TARGETS = listOf(500_000L, 1_000_000L, 2_000_000L, 5_000_000L)
    val VIDEO_TARGETS = listOf(10_000_000L, 20_000_000L, 50_000_000L)
    val LONG_EDGES = listOf(4096, 2048, 1600, 1080)
    val MIN_EDGES = listOf(640, 1024, 1600)
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val spaces = Regex("\\s+")

    /** Schritte in Ausführungsreihenfolge (nur, was der Ablauf wirklich tut). */
    fun steps(w: Workflow): List<WorkflowStep> = buildList {
        if (w.format != WorkflowFormat.KEEP) add(WorkflowStep.FORMAT)
        if (w.longEdge != null) add(WorkflowStep.RESIZE)
        if (w.photoMaxBytes != null) add(WorkflowStep.TARGET_SIZE) else if (w.format != WorkflowFormat.PNG) add(WorkflowStep.QUALITY)
        if (w.removeLocation) add(WorkflowStep.LOCATION)
        if (w.videoMaxBytes != null) add(WorkflowStep.VIDEO_TARGET)
        if (w.nameBase != null) add(WorkflowStep.RENAME)
        if (w.folder != null && w.saves) add(WorkflowStep.FOLDER)
        if (w.saves) add(WorkflowStep.SAVE)
        if (w.shares) add(WorkflowStep.SHARE)
    }

    fun normalizeName(raw: String): String? = raw.trim().replace(spaces, " ").take(MAX_NAME).trim().takeIf { it.isNotEmpty() }

    /** Dateinamen-Basis ohne verbotene Zeichen; `null`, wenn nichts übrig bleibt. */
    fun sanitizeBase(raw: String): String? = raw.replace(illegal, "").trim().replace(spaces, "_").take(MAX_NAME).trim('_', '.', ' ').takeIf { it.isNotEmpty() }

    /** Ein gültiger Ablauf: Name, sinnvolle Grenzen, nichts Widersprüchliches. */
    fun isValid(w: Workflow): Boolean = normalizeName(w.name) != null &&
        (w.longEdge == null || w.longEdge >= 320) && w.quality in 30..100 && w.minQuality in 30..w.quality.coerceAtLeast(30) &&
        (w.photoMaxBytes == null || w.photoMaxBytes >= MIN_PHOTO_BYTES) && (w.videoMaxBytes == null || w.videoMaxBytes >= MIN_VIDEO_BYTES) &&
        (w.longEdge == null || w.minLongEdge <= w.longEdge) && (w.nameBase == null || sanitizeBase(w.nameBase) != null)

    /** Ausgabeformat eines Fotos. KEEP: PNG bleibt PNG, alles andere wird JPEG (HEIC/WebP sind nicht überall teilbar). */
    fun outputFormat(w: Workflow, mime: String?): WorkflowFormat = when (w.format) {
        WorkflowFormat.KEEP -> if (mime.equals("image/png", ignoreCase = true)) WorkflowFormat.PNG else WorkflowFormat.JPEG
        else -> w.format
    }

    /** Längste Seite des Ergebnisses: nie größer als das Original. */
    fun targetEdge(w: Workflow, width: Int, height: Int): Int {
        val src = max(width, height).coerceAtLeast(1)
        return min(src, w.longEdge ?: src)
    }

    /**
     * Dateiname ohne Endung. Mit Basis: „Basis_001“ (mindestens dreistellig, mehr bei großen Mengen). Ohne Basis: Original-
     * name + Zusatz (z. B. „IMG_0042_PhotoMaid“). Nummern laufen in der Reihenfolge der Auswahl.
     */
    fun fileStem(w: Workflow, index: Int, total: Int, originalName: String?, suffix: String): String {
        val base = w.nameBase?.let(::sanitizeBase)
        if (base != null) {
            val digits = max(3, total.toString().length)
            return base + "_" + (index + 1).toString().padStart(digits, '0')
        }
        val stem = originalName?.substringBeforeLast('.')?.let(::sanitizeBase) ?: "GaleriQ"
        return stem + "_" + (sanitizeBase(suffix) ?: "GaleriQ")
    }

    /** Gemeinsames Budget für eine Auswahl: Anteile nach Originalgröße; sind Größen unbekannt, gleich verteilt. */
    fun budgetShares(sizes: List<Long?>, total: Long): List<Long> {
        if (sizes.isEmpty()) return emptyList()
        val known = sizes.filterNotNull()
        if (known.size < sizes.size || known.sum() <= 0) return List(sizes.size) { total / sizes.size }
        val sum = known.sum().toDouble()
        return sizes.map { (total * (it!! / sum)).toLong() }
    }
}

/**
 * Zielgröße für Fotos: erst die höchste Qualität, die passt; reicht die Mindestqualität nicht, wird die Kante stufenweise
 * verkleinert (bis zur Mindestkante). Jeder Versuch wird tatsächlich kodiert und gemessen ([encode] liefert Bytes).
 */
object TargetSize {
    data class Attempt(val longEdge: Int, val quality: Int)
    sealed interface Outcome {
        val attempt: Attempt
        val bytes: Long
        data class Met(override val attempt: Attempt, override val bytes: Long) : Outcome
        /** Nicht erreichbar innerhalb der Grenzen; [attempt] ist der kleinste gemessene Versuch. */
        data class Unreachable(override val attempt: Attempt, override val bytes: Long) : Outcome
    }

    const val EDGE_STEP = 0.8
    const val MAX_ATTEMPTS = 24

    suspend fun search(
        maxBytes: Long, startEdge: Int, startQuality: Int, minEdge: Int, minQuality: Int, lossless: Boolean,
        encode: suspend (Attempt) -> Long,
    ): Outcome {
        var best: Pair<Attempt, Long>? = null
        var attempts = 0
        suspend fun tryIt(a: Attempt): Long {
            attempts++
            val b = encode(a)
            if (best == null || b < best!!.second) best = a to b
            return b
        }
        var edge = startEdge.coerceAtLeast(1)
        val floorEdge = min(minEdge, startEdge).coerceAtLeast(1)
        val qMin = min(minQuality, startQuality)
        while (attempts < MAX_ATTEMPTS) {
            if (lossless) {
                val a = Attempt(edge, 100)
                val b = tryIt(a)
                if (b <= maxBytes) return Outcome.Met(a, b)
            } else {
                val top = Attempt(edge, startQuality)
                val bTop = tryIt(top)
                if (bTop <= maxBytes) return Outcome.Met(top, bTop)
                val low = Attempt(edge, qMin)
                val bLow = if (qMin == startQuality) bTop else tryIt(low)
                if (bLow <= maxBytes) {
                    // Höchste passende Qualität zwischen qMin (passt) und startQuality (passt nicht).
                    var lo = qMin; var hi = startQuality; var fit = low to bLow
                    while (hi - lo > 2 && attempts < MAX_ATTEMPTS) {
                        val mid = (lo + hi) / 2
                        val a = Attempt(edge, mid); val b = tryIt(a)
                        if (b <= maxBytes) { lo = mid; fit = a to b } else hi = mid
                    }
                    return Outcome.Met(fit.first, fit.second)
                }
            }
            if (edge <= floorEdge) break
            edge = max(floorEdge, (edge * EDGE_STEP).roundToInt())
        }
        val (a, b) = best!!
        return if (b <= maxBytes) Outcome.Met(a, b) else Outcome.Unreachable(a, b)
    }
}

/**
 * Zielgröße für Videos: Bitrate aus Ziel und Dauer (abzüglich Ton und Container), Auflösung nur verkleinert, wenn die
 * Bitrate für die Originalhöhe zu knapp wäre. `null` = nicht sinnvoll erreichbar (zu lang für das Ziel).
 */
object VideoTarget {
    const val AUDIO_BPS = 128_000
    const val CONTAINER_FACTOR = 0.95
    const val MIN_VIDEO_BPS = 250_000
    /** Bits je Pixel und Bild, unter denen eine Stufe kleiner gewählt wird. */
    const val MIN_BPP = 0.035
    val HEIGHTS = listOf(2160, 1440, 1080, 720, 480)

    data class Plan(val videoBitrate: Int, val maxHeight: Int)

    fun plan(maxBytes: Long, durationMs: Long, hasAudio: Boolean, width: Int, height: Int, fps: Float = 30f): Plan? {
        if (durationMs <= 0) return null
        val seconds = durationMs / 1000.0
        val total = maxBytes * 8 * CONTAINER_FACTOR / seconds
        val video = (total - if (hasAudio) AUDIO_BPS else 0).roundToLong()
        if (video < MIN_VIDEO_BPS) return null
        val shortSide = min(width, height).coerceAtLeast(1); val longSide = max(width, height).coerceAtLeast(1)
        val f = fps.coerceIn(1f, 60f)
        // Höchste Stufe ≤ Original, bei der genug Bits je Pixel bleiben; sonst die kleinste.
        val options = (listOf(shortSide) + HEIGHTS.filter { it < shortSide })
        val h = options.firstOrNull { s -> video / (s.toDouble() * (longSide.toDouble() * s / shortSide) * f) >= MIN_BPP } ?: options.last()
        return Plan(video.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), h)
    }
}
