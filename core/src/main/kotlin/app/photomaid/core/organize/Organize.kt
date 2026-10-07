package app.photomaid.core.organize

import app.photomaid.core.library.SearchText

/**
 * Organisieren (Ausbau Paket 1): Alben, Schlagwörter, Notizen und der Auswahlkorb sind reine PhotoMaid-Verwaltungsdaten.
 * Sie verändern keine Datei, erzeugen nie eine Aufräum-Entscheidung und beruhen ausschließlich auf Nutzereingaben –
 * keine Erkennung von Inhalten.
 *
 * Abgrenzung: Ordner = wo die Datei liegt · Sammlung = gespeicherte Suchbedingung · Album = bewusst zusammengestellt.
 */

/**
 * Wiederfinden eines Mediums: zuerst über die ID (Content-URI), sonst über den Fingerabdruck aus Dateiname, Größe und
 * Aufnahmezeit. Nur Pfadtexte zu speichern würde nach Verschieben/Neuindizieren nicht reichen.
 */
data class MediaRef(val id: String, val name: String?, val size: Long?, val takenMillis: Long?) {
    /** Fingerabdruck nur, wenn er trägt: Name plus Größe oder Aufnahmezeit. */
    val fingerprint: String? get() {
        val n = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (size == null && takenMillis == null) return null
        return "$n|${size ?: "?"}|${takenMillis ?: "?"}"
    }
}

object Rematch {
    /**
     * [found]: gespeicherte ID → aktuelles Medium. [moved]: alte ID → neue ID (über den Fingerabdruck wiedergefunden).
     * [missing]: nicht (eindeutig) auffindbar – werden sichtbar aufgelistet, nie still verworfen.
     */
    data class Result<T>(val found: Map<String, T>, val moved: Map<String, String>, val missing: List<String>)

    fun <T> resolve(stored: List<MediaRef>, current: List<T>, ref: (T) -> MediaRef): Result<T> {
        val byId = HashMap<String, T>(current.size * 2)
        val refs = HashMap<String, MediaRef>(current.size * 2)
        for (c in current) { val r = ref(c); byId[r.id] = c; refs[r.id] = r }
        val found = LinkedHashMap<String, T>()
        val used = HashSet<String>()
        for (s in stored) byId[s.id]?.let { found[s.id] = it; used += s.id }
        // Fingerabdruck nur eindeutig: genau ein noch nicht vergebenes aktuelles Medium mit diesem Abdruck.
        val byPrint = HashMap<String, MutableList<String>>()
        for ((id, r) in refs) if (id !in used) r.fingerprint?.let { byPrint.getOrPut(it) { mutableListOf() } += id }
        val moved = LinkedHashMap<String, String>()
        val missing = mutableListOf<String>()
        val wanted = HashMap<String, Int>()
        for (s in stored) if (s.id !in found) s.fingerprint?.let { wanted[it] = (wanted[it] ?: 0) + 1 }
        for (s in stored) {
            if (s.id in found) continue
            val p = s.fingerprint
            val candidates = p?.let { byPrint[it] }
            if (p != null && candidates != null && candidates.size == 1 && wanted[p] == 1 && candidates[0] !in used) {
                val id = candidates[0]; used += id
                found[s.id] = byId.getValue(id); moved[s.id] = id
            } else missing += s.id
        }
        return Result(found, moved, missing)
    }
}

/** Eigene Schlagwörter: kurz, ohne „#“, ohne Unterschied bei Groß/klein und Akzenten. */
object TagRules {
    const val MAX_LENGTH = 40
    const val MAX_PER_MEDIA = 30
    private val spaces = Regex("\\s+")

    /** Bereinigt eine Eingabe; `null`, wenn nichts übrig bleibt. */
    fun normalize(raw: String): String? =
        raw.trim().trimStart('#').trim().replace(spaces, " ").take(MAX_LENGTH).trim().takeIf { it.isNotEmpty() }

    /** Vergleichsschlüssel: „Café“ = „cafe“. */
    fun key(tag: String): String = SearchText.normalize(tag)

    /** Fügt hinzu (erste Schreibweise bleibt), höchstens [MAX_PER_MEDIA]. */
    fun merge(existing: List<String>, added: List<String>): List<String> {
        val out = LinkedHashMap<String, String>()
        for (t in existing + added.mapNotNull(::normalize)) out.putIfAbsent(key(t), t)
        return out.values.take(MAX_PER_MEDIA)
    }

    fun remove(existing: List<String>, tag: String): List<String> = existing.filter { key(it) != key(tag) }

    /** Zustand eines Schlagworts für eine Auswahl. */
    enum class State { ALL, SOME, NONE }

    fun state(tag: String, tagsOfSelection: List<List<String>>): State {
        if (tagsOfSelection.isEmpty()) return State.NONE
        val k = key(tag)
        val n = tagsOfSelection.count { list -> list.any { key(it) == k } }
        return when (n) { 0 -> State.NONE; tagsOfSelection.size -> State.ALL; else -> State.SOME }
    }

    /** Alle Schlagwörter mit Anzahl, häufigste zuerst, dann alphabetisch; gleiche Schlüssel zusammengefasst. */
    fun counts(tagsByMedia: Map<String, List<String>>): List<Pair<String, Int>> {
        val name = LinkedHashMap<String, String>(); val count = HashMap<String, Int>()
        for (list in tagsByMedia.values) for (t in list) { val k = key(t); name.putIfAbsent(k, t); count[k] = (count[k] ?: 0) + 1 }
        return name.entries.map { (k, n) -> n to count.getValue(k) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { key(it.first) })
    }
}

/** Notizen: freier Text, begrenzt, leer = keine Notiz. */
object NoteRules {
    const val MAX_LENGTH = 500
    fun normalize(raw: String): String? = raw.trim().take(MAX_LENGTH).trim().takeIf { it.isNotEmpty() }
}

/** Alben: Name eindeutig (ohne Groß/klein/Akzente), Reihenfolge wie hinzugefügt, keine Dubletten. */
object AlbumRules {
    const val MAX_NAME = 60
    const val MAX_ITEMS = 5000
    private val spaces = Regex("\\s+")

    fun normalizeName(raw: String): String? = raw.trim().replace(spaces, " ").take(MAX_NAME).trim().takeIf { it.isNotEmpty() }

    /** „Ferien“ → „Ferien (2)“, wenn es „Ferien“ schon gibt. */
    fun uniqueName(existing: Collection<String>, wanted: String, ignore: String? = null): String {
        val taken = existing.filter { it != ignore }.map(SearchText::normalize).toHashSet()
        if (SearchText.normalize(wanted) !in taken) return wanted
        var i = 2
        while (SearchText.normalize("$wanted ($i)") in taken) i++
        return "$wanted ($i)"
    }

    /** Hängt neue IDs hinten an (bereits enthaltene bleiben an ihrem Platz). Liefert die neue Liste und die Zahl neuer. */
    fun add(existing: List<String>, added: List<String>): Pair<List<String>, Int> {
        val seen = existing.toHashSet()
        val fresh = added.filter { seen.add(it) }
        val room = (MAX_ITEMS - existing.size).coerceAtLeast(0)
        return (existing + fresh.take(room)) to fresh.take(room).size
    }
}

/** Auswahlkorb: dauerhafter Arbeitsvorrat in Reihenfolge des Hinzufügens. Ohne jede Aufräum-Bedeutung. */
object BasketRules {
    const val MAX = 1000

    fun add(existing: List<String>, added: List<String>): Pair<List<String>, Int> {
        val seen = existing.toHashSet()
        val fresh = added.filter { seen.add(it) }
        val room = (MAX - existing.size).coerceAtLeast(0)
        return (existing + fresh.take(room)) to fresh.take(room).size
    }
}
