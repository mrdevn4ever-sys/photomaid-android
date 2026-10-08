package app.photomaid.core.archive

import app.photomaid.core.decisions.Disposition
import app.photomaid.core.model.LocalDay
import app.photomaid.core.organize.AlbumRules
import app.photomaid.core.organize.MediaRef
import app.photomaid.core.organize.NoteRules
import app.photomaid.core.organize.Rematch
import app.photomaid.core.organize.TagRules
import app.photomaid.core.library.SearchText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Sicherung der PhotoMaid-Daten (Ausbau Paket 5): eine kleine JSON-Datei, die der Nutzer selbst irgendwo ablegt.
 * Sie enthält KEINE Fotos – nur Entscheidungen, Alben, Schlagwörter, Notizen, Auswahlkorb, Ausgeblendetes und Einstellungen.
 * Medien werden über ID und Fingerabdruck (Name, Größe, Aufnahmezeit) bezeichnet, damit sie auch auf einem neuen Gerät
 * wiedergefunden werden. Was nicht eindeutig wiederzufinden ist, wird als Liste gezeigt – nie geraten.
 *
 * Wiederherstellen überschreibt nie etwas, das auf diesem Gerät schon entschieden oder eingetragen ist.
 */
@Serializable
data class BackupRef(val id: String, val name: String? = null, val size: Long? = null, val taken: Long? = null) {
    fun toMediaRef() = MediaRef(id, name, size, taken)

    companion object { fun of(r: MediaRef) = BackupRef(r.id, r.name, r.size, r.takenMillis) }
}

/** Gesicherter Zustand einer Entscheidung (nur Endzustände, kein Verlauf). */
@Serializable
enum class BackupState { KEPT, MARKED, LATER }

@Serializable
data class BackupDecision(val ref: Int, val state: BackupState, val due: LocalDay? = null, val bookmarked: Boolean = false)

@Serializable
data class BackupAlbum(val name: String, val createdAt: Long, val items: List<Int>)

@Serializable
data class PhotoMaidBackup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val createdAt: Long,
    val appVersion: String,
    /** Alle bezeichneten Medien; alles andere verweist per Index hierauf. */
    val refs: List<BackupRef> = emptyList(),
    val decisions: List<BackupDecision> = emptyList(),
    val albums: List<BackupAlbum> = emptyList(),
    /** Index → Schlagwörter. */
    val tags: Map<Int, List<String>> = emptyMap(),
    val notes: Map<Int, String> = emptyMap(),
    val basket: List<Int> = emptyList(),
    val hiddenMedia: List<Int> = emptyList(),
    /** App-Teile ohne Medienbezug (Einstellungen, Abläufe, Vorlagen, Sammlungen, Schnellzugriffe) – die App ordnet sie zu. */
    val extras: Map<String, JsonElement> = emptyMap(),
) {
    companion object {
        const val FORMAT = "photomaid-backup"
        const val VERSION = 1
    }
}

/** Was sich aus einer Datei lesen lässt. */
sealed interface BackupCheck {
    data class Ok(val backup: PhotoMaidBackup) : BackupCheck
    /** Keine PhotoMaid-Sicherung (anderes Format, beschädigt). */
    data object NotABackup : BackupCheck
    /** Aus einer neueren PhotoMaid-Version – lieber nicht halb übernehmen. */
    data class TooNew(val version: Int) : BackupCheck
}

object BackupRules {
    /** Höchstens so groß wird eine Sicherungsdatei gelesen (sie enthält keine Medien). */
    const val MAX_BYTES = 32L * 1024 * 1024

    fun check(decoded: PhotoMaidBackup?): BackupCheck = when {
        decoded == null || decoded.format != PhotoMaidBackup.FORMAT -> BackupCheck.NotABackup
        decoded.version > PhotoMaidBackup.VERSION -> BackupCheck.TooNew(decoded.version)
        else -> BackupCheck.Ok(decoded)
    }

    fun state(d: Disposition): BackupState? = when (d) {
        Disposition.KEPT -> BackupState.KEPT
        Disposition.MARKED_FOR_CLEANUP -> BackupState.MARKED
        Disposition.LATER -> BackupState.LATER
        // Unentschieden muss nicht gesichert werden; Entferntes existiert nicht mehr.
        Disposition.UNDECIDED, Disposition.REMOVED -> null
    }
}

/** Baut die Bezugsliste (jede ID genau einmal) und liefert Indizes. */
class BackupRefTable {
    private val index = LinkedHashMap<String, Int>()
    private val list = mutableListOf<BackupRef>()

    fun add(ref: MediaRef): Int = index.getOrPut(ref.id) { list += BackupRef.of(ref); list.size - 1 }
    fun refs(): List<BackupRef> = list.toList()
}

/** Wiederherstellen: was übernommen wird – berechnet, bevor irgendetwas geschrieben wird („Das passiert“). */
data class RestorePlan(
    /** Aktuelle Medien-ID → Zustand (nur für hier noch unentschiedene Medien). */
    val decisions: Map<String, BackupDecision>,
    /** Hier schon entschieden – bleibt, wie es ist. */
    val decisionsKeptLocal: Int,
    /** Albumname → aktuelle Medien-IDs (in Reihenfolge, nie leer); vorhandene Alben gleichen Namens werden ergänzt. */
    val albums: List<Pair<String, List<String>>>,
    val newAlbums: Int,
    val tags: Map<String, List<String>>,
    /** Nur Notizen für Medien ohne eigene Notiz. */
    val notes: Map<String, String>,
    val basket: List<String>,
    val hidden: List<String>,
    /** Nicht (eindeutig) wiedergefundene Medien – werden als Liste gezeigt. */
    val missing: List<BackupRef>,
) {
    /** Vorhandene Alben gleichen Namens, die ergänzt werden. */
    val extendedAlbums: Int get() = albums.map { it.first }.distinctBy { SearchText.normalize(it) }.size - newAlbums

    val isEmpty: Boolean get() = decisions.isEmpty() && albums.isEmpty() && tags.isEmpty() && notes.isEmpty() &&
        basket.isEmpty() && hidden.isEmpty()
}

object BackupRestore {
    /**
     * [current]: alle Medien dieses Geräts. [dispositions]: aktueller Zustand je Medien-ID (fehlend = unentschieden).
     * [albumNames]: vorhandene Alben. [notesPresent]: Medien, die hier schon eine Notiz haben.
     */
    fun <T> plan(
        b: PhotoMaidBackup,
        current: List<T>,
        ref: (T) -> MediaRef,
        dispositions: Map<String, Disposition>,
        albumNames: Collection<String>,
        notesPresent: Set<String>,
    ): RestorePlan {
        val used = HashSet<Int>()
        b.decisions.forEach { used += it.ref }; b.albums.forEach { used += it.items }; used += b.tags.keys; used += b.notes.keys
        used += b.basket; used += b.hiddenMedia
        val stored = used.filter { it in b.refs.indices }.map { b.refs[it] }
        val r = Rematch.resolve(stored.map { it.toMediaRef() }, current, ref)
        // Gesicherte ID → aktuelle ID
        val now: (Int) -> String? = { i -> b.refs.getOrNull(i)?.let { s -> r.found[s.id]?.let { ref(it).id } } }

        val decisions = LinkedHashMap<String, BackupDecision>()
        var keptLocal = 0
        for (d in b.decisions) {
            val id = now(d.ref) ?: continue
            val cur = dispositions[id] ?: Disposition.UNDECIDED
            if (cur != Disposition.UNDECIDED) { keptLocal++; continue }
            decisions.putIfAbsent(id, d)
        }
        val existingKeys = albumNames.map(SearchText::normalize).toHashSet()
        // Ein Album, von dem hier nichts wiederzufinden ist, wird nicht leer angelegt.
        val albums = b.albums.mapNotNull { a ->
            val name = AlbumRules.normalizeName(a.name) ?: return@mapNotNull null
            (name to a.items.mapNotNull(now).distinct()).takeIf { it.second.isNotEmpty() }
        }
        val newAlbums = albums.map { SearchText.normalize(it.first) }.distinct().count { it !in existingKeys }
        val tags = LinkedHashMap<String, List<String>>()
        for ((i, list) in b.tags) { val id = now(i) ?: continue; val clean = list.mapNotNull(TagRules::normalize); if (clean.isNotEmpty()) tags[id] = TagRules.merge(tags[id].orEmpty(), clean) }
        val notes = LinkedHashMap<String, String>()
        for ((i, text) in b.notes) { val id = now(i) ?: continue; if (id in notesPresent) continue; NoteRules.normalize(text)?.let { notes[id] = it } }
        return RestorePlan(
            decisions = decisions, decisionsKeptLocal = keptLocal,
            albums = albums, newAlbums = newAlbums, tags = tags, notes = notes,
            basket = b.basket.mapNotNull(now).distinct(),
            hidden = b.hiddenMedia.mapNotNull(now).distinct(),
            missing = stored.associateBy { it.id }.let { byId -> r.missing.mapNotNull { byId[it] } },
        )
    }
}
