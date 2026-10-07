package app.photomaid.core.batch

import app.photomaid.core.edit.EditFormat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Stapel-Werkzeuge (P7). Jede Datei wird als neue Datei geschrieben; Originale bleiben, außer der Nutzer wählt anders. */
enum class BatchTool { RESIZE, CONVERT, STRIP_LOCATION }

data class BatchOptions(
    val tool: BatchTool,
    /** Längste Kante (nur verkleinern), `null` = Originalgröße. */
    val longEdge: Int? = 2048,
    val quality: Int = 85,
    val format: EditFormat = EditFormat.JPEG,
    /** Standort zusätzlich entfernen (bei „Standort entfernen“ immer). */
    val removeLocation: Boolean = false,
    /** Originale danach mit einer Systembestätigung in den Android-Papierkorb (Standard aus). */
    val trashOriginals: Boolean = false,
)

object BatchRules {
    /** Free: so viele Dateien je Durchgang; Pro/Test: unbegrenzt. */
    const val FREE_LIMIT = 10
    val LONG_EDGES = listOf(4096, 2048, 1080)
    val QUALITIES = listOf(90, 80, 70)

    /** Wirklich etwas zu tun? Verkleinern nur, wenn die Kante größer ist; sonst übersprungen (keine sinnlose Kopie). */
    fun needsWork(o: BatchOptions, width: Int, height: Int, mime: String?, hasLocation: Boolean): Boolean = when (o.tool) {
        BatchTool.RESIZE -> o.longEdge != null && max(width, height) > o.longEdge
        BatchTool.CONVERT -> EditFormat.forSource(mime) != o.format || !(mime ?: "").equals(o.format.mime, ignoreCase = true)
        BatchTool.STRIP_LOCATION -> hasLocation
    }
}

/** Ergebnis ehrlich: Größen nur gemessen, unbekannte gezählt; „frei“ erst, wenn Originale wirklich weg sind. */
data class BatchSummary(
    val done: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val beforeBytes: Long = 0,
    val afterBytes: Long = 0,
    val unknownSizes: Int = 0,
    val originalsTrashed: Int = 0,
) {
    fun add(before: Long?, after: Long?): BatchSummary =
        // Unbekannte Größe: nur gezählt, nie in die Bilanz (sonst würde „vorher/nachher“ ungleiche Mengen vergleichen).
        if (before == null || after == null) copy(done = done + 1, unknownSizes = unknownSizes + 1)
        else copy(done = done + 1, beforeBytes = beforeBytes + before, afterBytes = afterBytes + after)

    /** Nach dem Leeren des Android-Papierkorbs frei werdender Platz: nur wenn ALLE Originale dort liegen und alle Größen bekannt sind; sonst 0. */
    val freedLaterBytes: Long get() = if (done == 0 || originalsTrashed < done || unknownSizes > 0) 0 else max(0L, beforeBytes - afterBytes)
}

/** Screenshots untereinander: gemeinsame Breite = schmalstes Bild (nie hochskaliert), proportional, max. Höhe. */
object StitchPlan {
    data class Place(val top: Int, val width: Int, val height: Int)
    const val MAX_HEIGHT = 20_000

    fun layout(sizes: List<Pair<Int, Int>>): Pair<Pair<Int, Int>, List<Place>> {
        require(sizes.isNotEmpty())
        var w = sizes.minOf { it.first }.coerceAtLeast(1)
        var heights = sizes.map { (sw, sh) -> max(1, (sh.toDouble() * w / sw).roundToInt()) }
        val total = heights.sum()
        if (total > MAX_HEIGHT) {
            val f = MAX_HEIGHT.toDouble() / total
            w = max(1, (w * f).roundToInt()); heights = sizes.map { (sw, sh) -> max(1, (sh.toDouble() * w / sw).roundToInt()) }
        }
        var top = 0
        val places = heights.map { h -> Place(top, w, h).also { top += h } }
        return (w to top) to places
    }
}

/** GIF: Rahmengröße, Platzierung je Bild (einpassen/füllen, nie verzerrt), Bildzeiten aus einem Video. */
object GifPlan {
    val SIZES = listOf(480, 720)
    const val MAX_FRAMES = 90
    const val MAX_VIDEO_MS = 10_000L

    fun frame(firstW: Int, firstH: Int, maxEdge: Int): Pair<Int, Int> {
        val s = min(1.0, maxEdge.toDouble() / max(firstW, firstH))
        return max(2, (firstW * s).roundToInt()) to max(2, (firstH * s).roundToInt())
    }

    /** Zielrechteck (l, t, r, b) im Rahmen; bei [fill] größer als der Rahmen (Rand wird abgeschnitten). */
    fun place(srcW: Int, srcH: Int, frameW: Int, frameH: Int, fill: Boolean): IntArray {
        val s = if (fill) max(frameW.toDouble() / srcW, frameH.toDouble() / srcH) else min(frameW.toDouble() / srcW, frameH.toDouble() / srcH)
        val w = (srcW * s).roundToInt(); val h = (srcH * s).roundToInt()
        val l = (frameW - w) / 2; val t = (frameH - h) / 2
        return intArrayOf(l, t, l + w, t + h)
    }

    /** Zeitpunkte (ms) für ein Video-GIF: gleichmäßig mit [fps], höchstens [MAX_FRAMES] und [MAX_VIDEO_MS]. */
    fun videoTimes(startMs: Long, endMs: Long, fps: Int): List<Long> {
        val end = min(endMs, startMs + MAX_VIDEO_MS)
        val stepMs = 1000.0 / fps.coerceIn(1, 30)
        val out = ArrayList<Long>()
        var t = startMs.toDouble()
        while (t < end && out.size < MAX_FRAMES) { out += t.roundToInt().toLong(); t += stepMs }
        return out
    }
}
