package app.photomaid.feature.collections

import app.photomaid.AppModel
import app.photomaid.core.collections.CollectionCandidate
import app.photomaid.core.collections.CollectionResult
import app.photomaid.core.collections.CollectionSource
import app.photomaid.core.collections.SmartCollection
import app.photomaid.core.collections.SmartCollectionID
import app.photomaid.core.collections.SmartCollections
import app.photomaid.data.KeyValueStore
import app.photomaid.media.MediaCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Sammlungen (ersetzen „Regeln & Routinen“): fertige für alle, eigene (Pro) lokal gespeichert.
 * Keine Auslöser, keine Aufgaben, keine Hintergrundarbeit – ausgewertet wird nur, wenn der Screen/Feed sie braucht.
 */
class CollectionsStore(private val store: KeyValueStore) {
    private val _custom = MutableStateFlow<List<SmartCollection>>(emptyList())
    val custom: StateFlow<List<SmartCollection>> = _custom.asStateFlow()

    companion object { private const val KEY = "collections.v1" }

    fun load() { _custom.value = runCatching { store.value<List<SmartCollection>>(KEY) }.getOrNull() ?: emptyList() }

    private suspend fun save(list: List<SmartCollection>) {
        _custom.value = list
        withContext(Dispatchers.IO) { runCatching { store.setValue(KEY, list) } }
    }

    suspend fun upsert(c: SmartCollection) {
        val list = _custom.value
        save(if (list.any { it.id == c.id }) list.map { if (it.id == c.id) c.copy(builtIn = false) else it } else list + c.copy(builtIn = false))
    }

    suspend fun delete(id: SmartCollectionID) = save(_custom.value.filter { it.id != id })

    /** Aktueller Stand aus einem echten MediaStore-Lesevorgang (keine erfundenen Zahlen). */
    suspend fun result(c: SmartCollection, app: AppModel): CollectionResult = withContext(Dispatchers.IO) {
        val cat = when (c.source) {
            CollectionSource.PHOTOS -> MediaCategory.PHOTOS
            CollectionSource.VIDEOS -> MediaCategory.VIDEOS
            CollectionSource.SCREENSHOTS -> MediaCategory.SCREENSHOTS
        }
        val candidates = app.photos.assets(cat).map { CollectionCandidate(it.reference, it.creationDate, it.size) }
        val records = app.ledger.records(candidates.map { it.item })
        SmartCollections.evaluate(c, candidates, records, Instant.now(), ZoneId.systemDefault())
    }
}
