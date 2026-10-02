package app.photomaid.core.library

import kotlinx.serialization.Serializable

/** Reihenfolge der Medien in einem Ordner (je Ordner gespeichert, sonst Standard). Nur Ansicht, nie eine Entscheidung. */
enum class FolderSort { NEWEST, OLDEST, ADDED, NAME, LARGEST;

    /**
     * Sortiert nach dieser Regel. Fehlende Werte (kein Datum, unbekannte Größe) kommen immer ans Ende – eine unbekannte
     * Größe ist nie 0. Gleichstand: neueste zuerst, dann Name.
     */
    fun <T> sort(items: List<T>, date: (T) -> Long?, added: (T) -> Long?, name: (T) -> String?, size: (T) -> Long?): List<T> {
        val tie = compareByDescending<T> { date(it) ?: Long.MIN_VALUE }.thenBy { name(it)?.lowercase() ?: "" }
        val primary: Comparator<T> = when (this) {
            NEWEST -> compareBy<T> { if (date(it) == null) 1 else 0 }.thenByDescending { date(it) ?: 0L }
            OLDEST -> compareBy<T> { if (date(it) == null) 1 else 0 }.thenBy { date(it) ?: 0L }
            ADDED -> compareBy<T> { if (added(it) == null) 1 else 0 }.thenByDescending { added(it) ?: 0L }
            NAME -> compareBy<T> { if (name(it).isNullOrBlank()) 1 else 0 }.thenComparator { a, b -> NaturalOrder.compare(name(a) ?: "", name(b) ?: "") }
            LARGEST -> compareBy<T> { if (size(it) == null) 1 else 0 }.thenByDescending { size(it) ?: 0L }
        }
        return items.sortedWith(primary.then(tie))
    }
}

/** Reihenfolge der Ordnerliste. */
enum class FolderListSort { RECENT, NAME, COUNT }

/** Eigene Gruppe: virtuelle Zusammenstellung von Ordnern. Gespeichert werden nur Name und Pfade (keine Medien-IDs). */
@Serializable
data class FolderGroup(val id: String, val name: String, val paths: List<String>)

/** Eingabe: ein Ordner der Momentaufnahme mit eigener Anzahl und jüngstem Aufnahmedatum. */
data class FolderEntry(val path: String, val count: Int, val latestMs: Long?)

/**
 * Knoten im Ordnerbaum. [path] immer mit abschließendem `/`. [own] = Medien direkt hier, [total] = inkl. Unterordner.
 * [label] ist der Anzeigename; zusammengefasste Ketten (z. B. `Android/media/…/WhatsApp Images`) zeigen nur das
 * letzte Glied, der volle Pfad steht im Untertitel.
 */
data class FolderNode(
    val path: String,
    val label: String,
    val own: Int,
    val total: Int,
    val latestMs: Long?,
    val children: List<FolderNode>,
    val depth: Int = 0,
) {
    val hasChildren: Boolean get() = children.isNotEmpty()
}

/**
 * Ordnerbaum aus MediaStore-Pfaden. Fehlende Zwischenordner entstehen als leere Knoten; ein Zwischenordner ohne eigene
 * Medien mit genau einem Unterordner wird mit diesem zusammengefasst, damit lange Ketten nicht aufklappen müssen.
 * Groß-/Kleinschreibung wie MediaStore (Pfade sind auf Android nicht unterscheidend, gruppiert wird ohne).
 */
object FolderTree {
    fun normalize(path: String): String = path.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }

    fun build(entries: List<FolderEntry>, sort: FolderListSort = FolderListSort.RECENT): List<FolderNode> {
        class Mut(val path: String, val name: String) {
            var own = 0; var latest: Long? = null
            val kids = LinkedHashMap<String, Mut>()
        }
        val root = Mut("", "")
        for (e in entries) {
            val p = normalize(e.path)
            if (p.isEmpty() || e.count <= 0) continue
            var cur = root
            var acc = ""
            for (part in p.trimEnd('/').split('/')) {
                acc += "$part/"
                cur = cur.kids.getOrPut(part.lowercase()) { Mut(acc, part) }
            }
            cur.own += e.count
            cur.latest = listOfNotNull(cur.latest, e.latestMs).maxOrNull()
        }
        fun freeze(m: Mut): FolderNode {
            val kids = m.kids.values.map(::freeze)
            val total = m.own + kids.sumOf { it.total }
            val latest = (listOfNotNull(m.latest) + kids.mapNotNull { it.latestMs }).maxOrNull()
            val node = FolderNode(m.path, m.name, m.own, total, latest, kids)
            // Kette ohne eigene Medien zusammenfassen.
            return if (m.own == 0 && kids.size == 1) kids.single() else node
        }
        return order(root.kids.values.map(::freeze), sort, 0)
    }

    private fun order(nodes: List<FolderNode>, sort: FolderListSort, depth: Int): List<FolderNode> {
        val c: Comparator<FolderNode> = when (sort) {
            FolderListSort.RECENT -> compareByDescending<FolderNode> { it.latestMs ?: Long.MIN_VALUE }
            FolderListSort.NAME -> Comparator { a, b -> NaturalOrder.compare(a.label, b.label) }
            FolderListSort.COUNT -> compareByDescending { it.total }
        }
        return nodes.sortedWith(c.thenComparator { a, b -> NaturalOrder.compare(a.label, b.label) })
            .map { it.copy(depth = depth, children = order(it.children, sort, depth + 1)) }
    }

    /** Sichtbare Zeilen: aufgeklappte Knoten zeigen ihre Kinder (eingerückt über [FolderNode.depth]). */
    fun rows(roots: List<FolderNode>, expanded: Set<String>): List<FolderNode> {
        val out = ArrayList<FolderNode>()
        fun walk(n: FolderNode) {
            out += n
            if (n.path.lowercase() in expanded) n.children.forEach(::walk)
        }
        roots.forEach(::walk)
        return out
    }

    /** Suche in Ordnernamen (ohne Akzente): flache Trefferliste, tiefe Treffer eingeschlossen. */
    fun search(roots: List<FolderNode>, query: String): List<FolderNode> {
        val t = SearchText.tokens(query)
        if (t.isEmpty()) return roots
        val out = ArrayList<FolderNode>()
        fun walk(n: FolderNode) {
            if (SearchText.matches(t, listOf(SearchText.normalize(n.label), SearchText.normalize(n.path)))) out += n.copy(depth = 0)
            n.children.forEach(::walk)
        }
        roots.forEach(::walk)
        return out
    }

    /** Liegt [path] in [folder] oder einem seiner Unterordner? */
    fun contains(folder: String, path: String?, includeSubfolders: Boolean = true): Boolean {
        val p = path?.let(::normalize)?.lowercase() ?: return false
        val f = normalize(folder).lowercase()
        if (f.isEmpty()) return false
        return if (includeSubfolders) p.startsWith(f) else p == f
    }

    fun find(roots: List<FolderNode>, path: String): FolderNode? {
        val key = normalize(path).lowercase()
        fun walk(n: FolderNode): FolderNode? = if (n.path.lowercase() == key) n else n.children.firstNotNullOfOrNull(::walk)
        return roots.firstNotNullOfOrNull(::walk)
    }
}

/** Natürliche Reihenfolge („IMG_2“ vor „IMG_10“), ohne Groß-/Kleinschreibung und Akzente. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")
    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(SearchText.normalize(a)).map { it.value }.toList()
        val y = chunk.findAll(SearchText.normalize(b)).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val r = if (p[0].isDigit() && q[0].isDigit()) {
                val pi = p.trimStart('0'); val qi = q.trimStart('0')
                if (pi.length != qi.length) pi.length - qi.length else pi.compareTo(qi)
            } else p.compareTo(q)
            if (r != 0) return r
        }
        return x.size - y.size
    }
}
