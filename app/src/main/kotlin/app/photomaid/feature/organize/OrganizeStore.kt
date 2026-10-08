package app.photomaid.feature.organize

import app.photomaid.core.organize.AlbumRules
import app.photomaid.core.organize.BasketRules
import app.photomaid.core.organize.MediaRef
import app.photomaid.core.organize.NoteRules
import app.photomaid.core.organize.Rematch
import app.photomaid.core.organize.TagRules
import app.photomaid.data.AlbumEntity
import app.photomaid.data.AlbumItemEntity
import app.photomaid.data.BasketItemEntity
import app.photomaid.data.MediaNoteEntity
import app.photomaid.data.MediaRefEntity
import app.photomaid.data.MediaTagEntity
import app.photomaid.data.PhotoMaidDatabase
import app.photomaid.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Ein PhotoMaid-Album: bewusst zusammengestellte Medien (IDs in Reihenfolge). Nur PhotoMaid-intern. */
data class Album(val id: String, val name: String, val createdAt: Long, val items: List<String>)

/** Gesamtzustand „Organisieren“ (nur Verwaltungsdaten, keine Dateien). */
data class OrganizeState(
    val albums: List<Album> = emptyList(),
    /** Medium → Schlagwörter (Schreibweise des Nutzers). */
    val tags: Map<String, List<String>> = emptyMap(),
    val notes: Map<String, String> = emptyMap(),
    /** Auswahlkorb in Reihenfolge des Hinzufügens. */
    val basket: List<String> = emptyList(),
) {
    fun album(id: String) = albums.firstOrNull { it.id == id }
    fun albumsOf(mediaId: String) = albums.filter { mediaId in it.items }
}

fun MediaAsset.ref() = MediaRef(id, displayName, size.bytesOrNull, creationDate?.toEpochMilli())

/**
 * Alben, Schlagwörter, Notizen und Auswahlkorb (Room, Version 2). Jede Änderung ist eine Transaktion; der Zustand im
 * Speicher wird erst nach erfolgreichem Schreiben veröffentlicht. Nichts hier löscht oder verändert eine Datei –
 * „Aus Album entfernen“, „Album löschen“, „Schlagwort entfernen“ und „Korb leeren“ betreffen nur diese Verwaltungsdaten.
 */
class OrganizeStore(private val db: PhotoMaidDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.organize()
    private val mutex = Mutex()
    private val _state = MutableStateFlow(OrganizeState())
    val state: StateFlow<OrganizeState> = _state.asStateFlow()

    /** Blockierend laden (Boot, abseits des Main-Threads). */
    fun load() { _state.value = runCatching { read() }.getOrDefault(OrganizeState()) }

    private fun read(): OrganizeState {
        val items = dao.albumItems().groupBy { it.albumId }
        return OrganizeState(
            albums = dao.albums().map { a -> Album(a.id, a.name, a.createdAt, items[a.id].orEmpty().sortedBy { it.position }.map { it.mediaId }) },
            tags = dao.tags().groupBy({ it.mediaId }, { it.tag }),
            notes = dao.notes().associate { it.mediaId to it.text },
            basket = dao.basket().map { it.mediaId },
        )
    }

    private suspend fun write(block: () -> Unit) = mutex.withLock {
        withContext(Dispatchers.IO) { db.runInTransaction(block); _state.value = read() }
    }

    private fun refs(assets: List<MediaAsset>) = assets.map { a -> a.ref().let { MediaRefEntity(it.id, it.name, it.size, it.takenMillis) } }

    // Alben ----------------------------------------------------------------------------------------------------------

    /** Neues Album (Name wird eindeutig gemacht); liefert die ID oder `null` bei leerem Namen. */
    suspend fun createAlbum(rawName: String, assets: List<MediaAsset> = emptyList()): String? {
        val name = AlbumRules.normalizeName(rawName) ?: return null
        val id = UUID.randomUUID().toString()
        val unique = AlbumRules.uniqueName(_state.value.albums.map { it.name }, name)
        val t = now()
        val (ids, _) = AlbumRules.add(emptyList(), assets.map { it.id })
        write {
            dao.upsertAlbums(listOf(AlbumEntity(id, unique, t, _state.value.albums.size)))
            dao.upsertRefs(refs(assets))
            dao.upsertAlbumItems(ids.mapIndexed { i, m -> AlbumItemEntity(id, m, i, t) })
        }
        return id
    }

    suspend fun renameAlbum(id: String, rawName: String): Boolean {
        val a = _state.value.album(id) ?: return false
        val name = AlbumRules.normalizeName(rawName) ?: return false
        val unique = AlbumRules.uniqueName(_state.value.albums.map { it.name }, name, ignore = a.name)
        val pos = _state.value.albums.indexOf(a)
        write { dao.upsertAlbums(listOf(AlbumEntity(a.id, unique, a.createdAt, pos))) }
        return true
    }

    /** Löscht nur das Album (die Zusammenstellung) – die Medien bleiben unverändert in der Mediathek. */
    suspend fun deleteAlbum(id: String) = write { dao.deleteAlbumItems(id); dao.deleteAlbum(id) }

    /** Hängt Medien an; liefert die Zahl wirklich neu hinzugefügter. */
    suspend fun addToAlbum(id: String, assets: List<MediaAsset>): Int {
        val a = _state.value.album(id) ?: return 0
        val (ids, added) = AlbumRules.add(a.items, assets.map { it.id })
        if (added == 0) return 0
        val t = now()
        write {
            dao.upsertRefs(refs(assets))
            dao.upsertAlbumItems(ids.drop(a.items.size).mapIndexed { i, m -> AlbumItemEntity(id, m, a.items.size + i, t) })
        }
        return added
    }

    /** Entfernt Medien aus dem Album – niemals aus der Mediathek. */
    suspend fun removeFromAlbum(id: String, mediaIds: Collection<String>) {
        if (mediaIds.isEmpty()) return
        write { dao.deleteFromAlbum(id, mediaIds.toList()) }
    }

    // Schlagwörter und Notizen ---------------------------------------------------------------------------------------

    /** Fügt [add] allen [assets] hinzu und entfernt [remove] (Schlüssel ohne Groß/klein/Akzente). */
    suspend fun editTags(assets: List<MediaAsset>, add: List<String> = emptyList(), remove: List<String> = emptyList()) {
        if (assets.isEmpty()) return
        val cur = _state.value.tags
        write {
            dao.upsertRefs(refs(assets))
            for (r in remove) dao.deleteTag(assets.map { it.id }, TagRules.key(r))
            for (a in assets) {
                val base = remove.fold(cur[a.id].orEmpty()) { l, r -> TagRules.remove(l, r) }
                val merged = TagRules.merge(base, add)
                dao.upsertTags(merged.filter { m -> base.none { TagRules.key(it) == TagRules.key(m) } }.map { MediaTagEntity(a.id, TagRules.key(it), it) })
            }
        }
    }

    /** Entfernt ein Schlagwort überall (die Medien bleiben unverändert). */
    suspend fun deleteTagEverywhere(tag: String) = write { dao.deleteTagEverywhere(TagRules.key(tag)) }

    /** Setzt oder löscht (leer) die Notiz eines Mediums. */
    suspend fun setNote(asset: MediaAsset, raw: String) {
        val text = NoteRules.normalize(raw)
        write {
            if (text == null) dao.deleteNote(asset.id)
            else { dao.upsertRefs(refs(listOf(asset))); dao.upsertNotes(listOf(MediaNoteEntity(asset.id, text, now()))) }
        }
    }

    // Auswahlkorb ----------------------------------------------------------------------------------------------------

    suspend fun addToBasket(assets: List<MediaAsset>): Int {
        val cur = _state.value.basket
        val (ids, added) = BasketRules.add(cur, assets.map { it.id })
        if (added == 0) return 0
        val t = now()
        write {
            dao.upsertRefs(refs(assets))
            dao.upsertBasket(ids.drop(cur.size).mapIndexed { i, m -> BasketItemEntity(m, cur.size + i, t) })
        }
        return added
    }

    suspend fun removeFromBasket(mediaIds: Collection<String>) { if (mediaIds.isNotEmpty()) write { dao.deleteFromBasket(mediaIds.toList()) } }
    suspend fun clearBasket() = write { dao.clearBasket() }

    /** Gespeicherte Fingerabdrücke aller Bezüge (für die Sicherung: auch Medien, die gerade nicht auffindbar sind). */
    suspend fun storedRefs(): Map<String, MediaRef> = withContext(Dispatchers.IO) {
        runCatching { dao.refs() }.getOrDefault(emptyList()).associate { it.mediaId to MediaRef(it.mediaId, it.name, it.size, it.takenMillis) }
    }

    // Wiederfinden ---------------------------------------------------------------------------------------------------

    /**
     * Gleicht alle Bezüge mit der GANZEN Mediathek ab (auch ausgeblendete Medien zählen als vorhanden): neu indizierte
     * Medien (eindeutiger Fingerabdruck) werden umgeschrieben. Liefert die nicht auffindbaren IDs – sie bleiben gespeichert
     * und werden sichtbar aufgelistet, bis der Nutzer sie entfernt.
     */
    suspend fun reconcile(all: List<MediaAsset>): Set<String> {
        val s = _state.value
        val referenced = (s.albums.flatMap { it.items } + s.tags.keys + s.notes.keys + s.basket).toHashSet()
        if (referenced.isEmpty()) return emptySet()
        val stored = withContext(Dispatchers.IO) { dao.refs() }.associateBy { it.mediaId }
        val refs = referenced.map { id -> stored[id]?.let { MediaRef(id, it.name, it.size, it.takenMillis) } ?: MediaRef(id, null, null, null) }
        val r = Rematch.resolve(refs, all) { it.ref() }
        if (r.moved.isNotEmpty()) write { dao.moveAll(r.moved) }
        return r.missing.toHashSet()
    }
}
