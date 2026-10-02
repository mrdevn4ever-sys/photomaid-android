package app.photomaid.core.studio

import app.photomaid.core.edit.Adjustments
import app.photomaid.core.edit.FilterId
import app.photomaid.core.edit.Layer
import app.photomaid.core.edit.NormPoint
import app.photomaid.core.edit.NormRect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Collage-Raster als Baum aus Teilungen. Jede Teilung hat ein Verhältnis (0…1), das der Nutzer über die Trennlinie
 * verschiebt; die Vorlage bleibt dabei sauber (keine Lücken, keine Überlappung).
 */
@Serializable
sealed interface SplitNode {
    @Serializable @SerialName("leaf") data class Leaf(val index: Int) : SplitNode
    /** [vertical] = Kinder nebeneinander (senkrechte Trennlinie), sonst übereinander. */
    @Serializable @SerialName("split") data class Split(val vertical: Boolean, val ratio: Float, val a: SplitNode, val b: SplitNode) : SplitNode
}

/** Eine verschiebbare Trennlinie: Pfad zur Teilung (0 = a, 1 = b), Lage im Einheitsquadrat und Bereich der Teilung. */
data class Divider(val path: List<Int>, val vertical: Boolean, val position: Float, val span: NormRect, val parent: NormRect)

object CollageLayout {
    const val MIN_CELL = 0.08f

    fun leafCount(n: SplitNode): Int = when (n) { is SplitNode.Leaf -> 1; is SplitNode.Split -> leafCount(n.a) + leafCount(n.b) }

    /** Rechtecke je Feld (Index → Rechteck im Einheitsquadrat). */
    fun cells(n: SplitNode, r: NormRect = NormRect.FULL, out: MutableMap<Int, NormRect> = linkedMapOf()): Map<Int, NormRect> {
        when (n) {
            is SplitNode.Leaf -> out[n.index] = r
            is SplitNode.Split -> { val (ra, rb) = split(n, r); cells(n.a, ra, out); cells(n.b, rb, out) }
        }
        return out
    }

    private fun split(n: SplitNode.Split, r: NormRect): Pair<NormRect, NormRect> =
        if (n.vertical) { val x = r.left + r.width * n.ratio; NormRect(r.left, r.top, x, r.bottom) to NormRect(x, r.top, r.right, r.bottom) }
        else { val y = r.top + r.height * n.ratio; NormRect(r.left, r.top, r.right, y) to NormRect(r.left, y, r.right, r.bottom) }

    fun dividers(n: SplitNode, r: NormRect = NormRect.FULL, path: List<Int> = emptyList(), out: MutableList<Divider> = mutableListOf()): List<Divider> {
        if (n is SplitNode.Split) {
            val (ra, rb) = split(n, r)
            val pos = if (n.vertical) ra.right else ra.bottom
            out += Divider(path, n.vertical, pos, if (n.vertical) NormRect(pos, r.top, pos, r.bottom) else NormRect(r.left, pos, r.right, pos), r)
            dividers(n.a, ra, path + 0, out); dividers(n.b, rb, path + 1, out)
        }
        return out
    }

    /** Trennlinie [d] auf [position] (Einheitsquadrat) setzen – begrenzt, damit kein Feld zu klein wird. */
    fun moveDivider(root: SplitNode, d: Divider, position: Float): SplitNode {
        val size = if (d.vertical) d.parent.width else d.parent.height
        val start = if (d.vertical) d.parent.left else d.parent.top
        val ratio = ((position - start) / size).coerceIn(MIN_CELL / max(size, 1e-4f), 1f - MIN_CELL / max(size, 1e-4f)).coerceIn(0.05f, 0.95f)
        return withRatio(root, d.path, ratio)
    }

    private fun withRatio(n: SplitNode, path: List<Int>, ratio: Float): SplitNode {
        if (n !is SplitNode.Split) return n
        if (path.isEmpty()) return n.copy(ratio = ratio)
        return if (path[0] == 0) n.copy(a = withRatio(n.a, path.drop(1), ratio)) else n.copy(b = withRatio(n.b, path.drop(1), ratio))
    }

    /** Gleich große Teile entlang einer Achse für die Indizes [ids]. */
    private fun even(ids: List<SplitNode>, vertical: Boolean): SplitNode =
        if (ids.size == 1) ids[0] else SplitNode.Split(vertical, 1f / ids.size, ids[0], even(ids.drop(1), vertical))

    /** Zeilen mit [counts] Fotos je Zeile (z. B. 1,2 = oben eins, unten zwei); [columns] = als Spalten. */
    fun rows(counts: List<Int>, columns: Boolean = false): SplitNode {
        var i = 0
        val lines = counts.map { c -> even((0 until c).map { SplitNode.Leaf(i++) }, vertical = !columns) }
        return even(lines, vertical = columns)
    }

    /** Ein großes Feld plus Rest in einer Spalte/Zeile (Magazin). */
    fun big(n: Int, side: Char): SplitNode {
        val rest = (1 until n).map { SplitNode.Leaf(it) }
        if (rest.isEmpty()) return SplitNode.Leaf(0)
        val restRows = if (rest.size <= 4) even(rest, vertical = side == 't' || side == 'b')
            else rows(listOf((rest.size + 1) / 2, rest.size / 2), columns = side == 'l' || side == 'r').let { renumber(it, 1) }
        val main = SplitNode.Leaf(0)
        return when (side) {
            'l' -> SplitNode.Split(true, 0.62f, main, restRows)
            'r' -> SplitNode.Split(true, 0.38f, restRows, main)
            't' -> SplitNode.Split(false, 0.62f, main, restRows)
            else -> SplitNode.Split(false, 0.38f, restRows, main)
        }
    }

    private fun renumber(n: SplitNode, offset: Int): SplitNode = when (n) {
        is SplitNode.Leaf -> SplitNode.Leaf(n.index + offset)
        is SplitNode.Split -> n.copy(a = renumber(n.a, offset), b = renumber(n.b, offset))
    }

    /** Vorlagen-ID → Baum. „r:1,2“ Zeilen, „c:2,1“ Spalten, „big:l“ Magazin, „grid“ ausgewogen. */
    fun template(id: String, n: Int): SplitNode {
        val (kind, arg) = id.substringBefore(':') to id.substringAfter(':', "")
        return when (kind) {
            "r" -> rows(arg.split(',').map { it.toInt() })
            "c" -> rows(arg.split(',').map { it.toInt() }, columns = true)
            "big" -> big(n, arg.firstOrNull() ?: 'l')
            else -> rows(balanced(n))
        }
    }

    /** Ausgewogene Zeilen (z. B. 7 → 3,2,2; 10 → 4,3,3). */
    fun balanced(n: Int): List<Int> {
        val rowsN = max(1, sqrt(n.toDouble()).roundToInt())
        val base = n / rowsN; val extra = n % rowsN
        return (0 until rowsN).map { base + if (it < extra) 1 else 0 }.filter { it > 0 }
    }

    /** Alle angebotenen Vorlagen für [n] Fotos (2…20), sinnvoll sortiert, ohne Doppelte. */
    fun templatesFor(n: Int): List<String> {
        val out = LinkedHashSet<String>()
        out += "grid"
        if (n in 2..12) {
            out += "r:${n}"; out += "c:${n}"
            // Zwei- und dreizeilige Aufteilungen
            for (a in 1 until n) { val b = n - a; if (a <= 5 && b <= 5) { out += "r:$a,$b"; out += "c:$a,$b" } }
            if (n >= 3) for (a in 1..4) for (b in 1..4) { val c = n - a - b; if (c in 1..4) { out += "r:$a,$b,$c" } }
            out += "big:l"; out += "big:t"; out += "big:r"; out += "big:b"
        } else {
            val bal = balanced(n); out += "r:${bal.joinToString(",")}"; out += "c:${bal.joinToString(",")}"
            out += "big:l"; out += "big:t"
        }
        // Gleichwertige Einträge (z. B. r:2 und c:… bei n=2 bleiben unterschiedlich) – Ergebnisbäume vergleichen.
        val seen = HashSet<String>(); val res = mutableListOf<String>()
        for (id in out) { val key = cells(template(id, n)).values.joinToString(";") { "%.3f,%.3f,%.3f,%.3f".format(it.left, it.top, it.right, it.bottom) }
            if (seen.add(key)) res += id }
        return res.take(40)
    }
}

/** Art der Collage. */
@Serializable
enum class CollageKind { GRID, FREE, STRIP }

/** Ein Foto der Collage mit eigener Lage im Feld (Zoom ≥ 1 füllt, Verschiebung −1…1) und einfachen Anpassungen. */
@Serializable
data class CollageItem(
    val mediaId: String,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    /** Ganzes Foto zeigen (mit Hintergrund) statt Feld füllen. */
    val fit: Boolean = false,
    val filter: FilterId = FilterId.NONE,
    val filterIntensity: Float = 1f,
    val adjust: Adjustments = Adjustments(),
    // Frei
    val center: NormPoint = NormPoint(0.5f, 0.5f),
    /** Breite relativ zur Collage-Breite (Frei). */
    val width: Float = 0.45f,
    val rotation: Float = 0f,
    // Streifen: oben/unten (bzw. links/rechts) abschneiden, Anteil 0…0,45
    val trimStart: Float = 0f,
    val trimEnd: Float = 0f,
)

@Serializable
enum class BackgroundKind { COLOR, BLUR }

@Serializable
data class CollageBackground(val kind: BackgroundKind = BackgroundKind.COLOR, val color: Int = 0xFFFFFFFF.toInt(), val blurIndex: Int = 0)

/** Ganze Collage (Entwurf). Nur lokal; enthält Medien-IDs, nie Bilder. */
@Serializable
data class CollageProject(
    val id: String,
    val kind: CollageKind = CollageKind.GRID,
    val items: List<CollageItem>,
    val template: String = "grid",
    val tree: SplitNode = CollageLayout.template("grid", items.size),
    /** Seitenverhältnis Breite/Höhe (Raster/Frei). */
    val aspect: Float = 1f,
    /** Abstand zwischen Feldern und Außenrand, relativ zur kürzeren Kante. */
    val spacing: Float = 0.015f,
    val margin: Float = 0.015f,
    val corner: Float = 0f,
    val background: CollageBackground = CollageBackground(),
    /** Frei: weißer Rand + Schatten („Sofortbild“). */
    val polaroid: Boolean = true,
    /** Streifen: untereinander (sonst nebeneinander). */
    val stripVertical: Boolean = true,
    val layers: List<Layer> = emptyList(),
) {
    val count: Int get() = items.size

    fun withTemplate(t: String) = copy(template = t, tree = CollageLayout.template(t, items.size))

    /** Feldrechtecke (normiert auf die Collage) inklusive Abständen. */
    fun cellRects(): List<NormRect> {
        val cells = CollageLayout.cells(tree)
        val ar = aspect
        // Abstände sind relativ zur kürzeren Kante; im Einheitsquadrat je Achse umrechnen.
        val sx = if (ar >= 1f) 1f / ar else 1f; val sy = if (ar >= 1f) 1f else ar
        val gx = spacing * sx; val gy = spacing * sy; val mx = margin * sx; val my = margin * sy
        return items.indices.map { i ->
            val r = cells[i] ?: NormRect.FULL
            fun x(v: Float) = mx + v * (1f - 2 * mx)
            fun y(v: Float) = my + v * (1f - 2 * my)
            NormRect(x(r.left) + (if (r.left > 0.0001f) gx / 2 else 0f), y(r.top) + (if (r.top > 0.0001f) gy / 2 else 0f),
                x(r.right) - (if (r.right < 0.9999f) gx / 2 else 0f), y(r.bottom) - (if (r.bottom < 0.9999f) gy / 2 else 0f))
        }
    }

    fun swap(a: Int, b: Int): CollageProject {
        if (a == b || a !in items.indices || b !in items.indices) return this
        val l = items.toMutableList(); val t = l[a]; l[a] = l[b].copy(zoom = 1f, panX = 0f, panY = 0f); l[b] = t.copy(zoom = 1f, panX = 0f, panY = 0f)
        return copy(items = l)
    }

    fun removeItem(i: Int): CollageProject {
        if (items.size <= 1 || i !in items.indices) return this
        val l = items.toMutableList().apply { removeAt(i) }
        val t = if (CollageLayout.templatesFor(l.size).contains(template)) template else "grid"
        return copy(items = l, template = t, tree = CollageLayout.template(t, l.size))
    }

    fun addItems(ids: List<String>): CollageProject {
        val l = items + ids.map { CollageItem(it) }
        val n = l.size
        val t = if (kind == CollageKind.GRID && CollageLayout.templatesFor(n).contains(template)) template else "grid"
        return copy(items = l, template = t, tree = CollageLayout.template(t, n), ).let { p -> if (kind == CollageKind.FREE) p.copy(items = p.items.mapIndexed { i, it -> if (i >= items.size) it.copy(center = FreeLayout.spot(i, n), width = FreeLayout.width(n)) else it }) else p }
    }

    companion object {
        const val MAX = 20
        const val FREE_MAX = 4

        fun create(id: String, ids: List<String>, kind: CollageKind = CollageKind.GRID): CollageProject {
            val n = ids.size
            val items = ids.mapIndexed { i, m -> CollageItem(m, center = FreeLayout.spot(i, n), width = FreeLayout.width(n), rotation = FreeLayout.tilt(i)) }
            return when (kind) {
                CollageKind.GRID -> CollageProject(id, kind, items, "grid", CollageLayout.template("grid", n), aspect = if (n == 2) 1f else 0.8f)
                CollageKind.FREE -> CollageProject(id, kind, items, aspect = 0.8f, background = CollageBackground(color = 0xFFF4EFE6.toInt()))
                CollageKind.STRIP -> CollageProject(id, kind, items, spacing = 0f, margin = 0f)
            }
        }
    }
}

/** Startpositionen für die freie Collage (lockeres Raster, leicht gedreht). */
object FreeLayout {
    fun width(n: Int) = when { n <= 1 -> 0.62f; n <= 2 -> 0.44f; n <= 4 -> 0.36f; n <= 9 -> 0.27f; else -> 0.2f }
    fun spot(i: Int, n: Int): NormPoint {
        val cols = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1); val rows = ceil(n / cols.toDouble()).toInt().coerceAtLeast(1)
        val c = i % cols; val r = i / cols
        return NormPoint((c + 0.5f) / cols, (r + 0.5f) / rows)
    }
    fun tilt(i: Int) = listOf(-4f, 3f, -2f, 5f, -3f, 2f)[i % 6]
}

/** Streifen: Gesamtgröße und Lage je Bild (lange Screenshots untereinander). */
object StripLayout {
    /**
     * [sizes] = Pixelgrößen der Fotos (ausgerichtet). Alle werden auf die gemeinsame Breite (bzw. Höhe) gebracht;
     * [maxEdge] begrenzt die lange Kante (Bitmap-Grenze), dann wird gleichmäßig verkleinert.
     */
    fun layout(sizes: List<Pair<Int, Int>>, items: List<CollageItem>, vertical: Boolean, spacingPx: Int, targetCross: Int, maxEdge: Int = 16_000): Pair<Pair<Int, Int>, List<Pair<NormRect, NormRect>>> {
        var cross = targetCross
        fun lengths(cr: Int) = sizes.mapIndexed { i, (w, h) ->
            val it = items[i]
            val keep = (1f - it.trimStart - it.trimEnd).coerceIn(0.1f, 1f)
            if (vertical) (h * cr / w.toFloat() * keep) else (w * cr / h.toFloat() * keep)
        }
        var total = lengths(cross).sum() + spacingPx * (sizes.size - 1)
        if (total > maxEdge) { cross = max(16, (cross * maxEdge / total).toInt()); total = lengths(cross).sum() + spacingPx * (sizes.size - 1) }
        val lens = lengths(cross)
        val out = mutableListOf<Pair<NormRect, NormRect>>()
        var pos = 0f
        for (i in sizes.indices) {
            val it = items[i]; val l = lens[i]
            val dst = if (vertical) NormRect(0f, pos / total, 1f, (pos + l) / total) else NormRect(pos / total, 0f, (pos + l) / total, 1f)
            val src = if (vertical) NormRect(0f, it.trimStart, 1f, 1f - it.trimEnd) else NormRect(it.trimStart, 0f, 1f - it.trimEnd, 1f)
            out += dst to src
            pos += l + spacingPx
        }
        val w = if (vertical) cross else total.roundToInt(); val h = if (vertical) total.roundToInt() else cross
        return (w to h) to out
    }
}

/** Wie ein Foto in ein Feld kommt (füllen mit Zoom/Verschiebung oder ganz zeigen). Ergebnis: Quellausschnitt (0…1). */
object CellFit {
    fun source(imgAspect: Float, cellAspect: Float, zoom: Float, panX: Float, panY: Float, fit: Boolean): NormRect {
        if (fit) return NormRect.FULL
        val z = max(1f, zoom)
        // Größter Ausschnitt mit Feld-Seitenverhältnis, dann zoomen.
        var w = 1f; var h = 1f
        if (imgAspect > cellAspect) w = cellAspect / imgAspect else h = imgAspect / cellAspect
        w /= z; h /= z
        val cx = 0.5f + panX.coerceIn(-1f, 1f) * (1f - w) / 2f; val cy = 0.5f + panY.coerceIn(-1f, 1f) * (1f - h) / 2f
        return NormRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    /** Ganzes Foto einpassen: Zielrechteck im Feld (normiert aufs Feld). */
    fun fitRect(imgAspect: Float, cellAspect: Float): NormRect =
        if (imgAspect > cellAspect) { val h = cellAspect / imgAspect; NormRect(0f, (1 - h) / 2, 1f, (1 + h) / 2) }
        else { val w = imgAspect / cellAspect; NormRect((1 - w) / 2, 0f, (1 + w) / 2, 1f) }
}

/** Exportgrößen. */
object CollageExport {
    /** A4 bei 300 dpi (hoch). */
    const val A4_SHORT = 2480; const val A4_LONG = 3508
    fun size(aspect: Float, longEdge: Int): Pair<Int, Int> =
        if (aspect >= 1f) longEdge to max(1, (longEdge / aspect).roundToInt()) else max(1, (longEdge * aspect).roundToInt()) to longEdge
    fun clampLong(v: Int) = min(8192, max(512, v))
}
