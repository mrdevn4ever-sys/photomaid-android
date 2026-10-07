package app.photomaid.feature.editor

import app.photomaid.core.edit.EditPreset
import app.photomaid.data.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Eigene Vorlagen (nur Einstellungen). Lokal, nie hochgeladen. */
class PresetStore(private val store: KeyValueStore) {
    private val _items = MutableStateFlow<List<EditPreset>>(emptyList())
    val items: StateFlow<List<EditPreset>> = _items.asStateFlow()

    companion object { private const val KEY = "edit.presets.v1"; const val MAX = 50 }

    fun load() { _items.value = runCatching { store.value<List<EditPreset>>(KEY) }.getOrNull() ?: emptyList() }

    suspend fun add(p: EditPreset) = save((listOf(p) + _items.value.filterNot { it.id == p.id }).take(MAX))
    /** An gleicher Stelle ersetzen (Umbenennen). */
    suspend fun replace(p: EditPreset) = save(_items.value.map { if (it.id == p.id) p else it })
    suspend fun remove(id: String) = save(_items.value.filterNot { it.id == id })

    private suspend fun save(next: List<EditPreset>) {
        withContext(Dispatchers.IO) { runCatching { store.setValue(KEY, next) } }
        _items.value = next
    }
}
