package app.photomaid.feature.games

import app.photomaid.core.games.PhotoBlocksArchive
import app.photomaid.core.games.PhotoGameRecords
import app.photomaid.core.games.PhotoSnakePreferences
import app.photomaid.data.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Lokale Spielstände (Port von iOS `PhotoGameScores`/`PhotoBlocksArchive`): nur Zahlen und Regel-Kategorien, nie
 * Foto-Kennungen oder Bilder. Ein Spiel ist nie eine Entscheidung und schreibt nie in den Ledger.
 */
class GameStore(private val kv: KeyValueStore) {
    private val _records = MutableStateFlow(PhotoGameRecords())
    val records: StateFlow<PhotoGameRecords> = _records
    private val _blocks = MutableStateFlow(PhotoBlocksArchive())
    val blocks: StateFlow<PhotoBlocksArchive> = _blocks
    private val _snake = MutableStateFlow(PhotoSnakePreferences())
    val snakePreferences: StateFlow<PhotoSnakePreferences> = _snake
    /** Speichern fehlgeschlagen: Werte gelten für die Sitzung, Hinweis mit „Erneut versuchen“. */
    private val _saveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = _saveFailed
    private var loaded = false

    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        runCatching { kv.value<PhotoGameRecords>(RECORDS) }.getOrNull()?.let { _records.value = it.sanitized() }
        runCatching { kv.value<PhotoBlocksArchive>(BLOCKS) }.getOrNull()?.let { _blocks.value = it.sanitized() }
        runCatching { kv.value<PhotoSnakePreferences>(SNAKE_PREFS) }.getOrNull()?.let { _snake.value = it }
        loaded = true
    }

    /** Neuer Rekord → sofort gespeichert. Gibt `true` zurück, wenn es ein neuer Bestwert war. */
    suspend fun update(change: (PhotoGameRecords) -> Boolean): Boolean {
        val copy = PhotoGameRecords.copyOf(_records.value)
        val improved = change(copy)
        if (improved) { _records.value = copy; save { kv.setValue(RECORDS, copy) } }
        return improved
    }

    suspend fun saveBlocks(archive: PhotoBlocksArchive) { _blocks.value = archive; save { kv.setValue(BLOCKS, archive) } }

    suspend fun saveSnakePreferences(p: PhotoSnakePreferences) { _snake.value = p; save { kv.setValue(SNAKE_PREFS, p) } }

    suspend fun retry() {
        save { kv.setValue(RECORDS, _records.value); kv.setValue(BLOCKS, _blocks.value); kv.setValue(SNAKE_PREFS, _snake.value) }
    }

    private suspend fun save(block: () -> Unit) = withContext(Dispatchers.IO) {
        _saveFailed.value = runCatching(block).isFailure
    }

    companion object {
        const val RECORDS = "games.records.v1"
        const val BLOCKS = "games.blocks.v1"
        const val SNAKE_PREFS = "games.snake.preferences.v1"
    }
}
