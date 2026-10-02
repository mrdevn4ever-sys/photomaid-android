package app.photomaid.feature.editor

import app.photomaid.core.edit.EditRecord
import app.photomaid.data.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Lokale Liste der von PhotoMaid gespeicherten Bearbeitungen (für „Bearbeitet“). Nie hochgeladen. */
class EditStore(private val store: KeyValueStore) {
    private val _records = MutableStateFlow<List<EditRecord>>(emptyList())
    val records: StateFlow<List<EditRecord>> = _records.asStateFlow()

    companion object { private const val KEY = "edits.v1"; const val MAX = 500 }

    fun load() { _records.value = runCatching { store.value<List<EditRecord>>(KEY) }.getOrNull() ?: emptyList() }

    suspend fun add(r: EditRecord) {
        val next = (listOf(r) + _records.value.filterNot { it.newId == r.newId }).take(MAX)
        withContext(Dispatchers.IO) { runCatching { store.setValue(KEY, next) } }
        _records.value = next
    }
}
