package app.photomaid.feature.studio

import android.content.Context
import android.graphics.Bitmap
import app.photomaid.core.studio.CollageProject
import app.photomaid.data.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import app.photomaid.core.studio.VideoProject

/** Ein Entwurf im Studio (Collage; Video-Projekte folgen demselben Muster). Nur lokal, nie hochgeladen. */
@Serializable
data class Draft(val id: String, val updatedAt: Long, val collage: CollageProject? = null, val video: VideoProject? = null) {
    val count: Int get() = collage?.count ?: video?.clips?.size ?: 0
}

/**
 * Entwürfe (Projekte mit Medien-IDs und Einstellungen, keine Bilder) + kleines Vorschaubild in `files/drafts/`.
 * Free behält die [FREE_MAX] neuesten, Pro bis [MAX].
 */
class DraftStore(private val store: KeyValueStore, private val context: Context) {
    private val _items = MutableStateFlow<List<Draft>>(emptyList())
    val items: StateFlow<List<Draft>> = _items.asStateFlow()

    companion object { private const val KEY = "studio.drafts.v1"; const val MAX = 50; const val FREE_MAX = 3 }

    private fun dir() = File(context.filesDir, "drafts").apply { mkdirs() }
    fun thumb(id: String) = File(dir(), "$id.jpg")

    fun load() { _items.value = runCatching { store.value<List<Draft>>(KEY) }.getOrNull() ?: emptyList() }

    /** Speichern (neueste zuerst). [limit] = erlaubte Anzahl; ältere fallen weg (mit Vorschaubild). */
    suspend fun put(d: Draft, preview: Bitmap?, limit: Int) {
        val next = (listOf(d) + _items.value.filterNot { it.id == d.id }).sortedByDescending { it.updatedAt }
        val kept = next.take(limit); val dropped = next.drop(limit)
        withContext(Dispatchers.IO) {
            preview?.let { b -> runCatching { thumb(d.id).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) } } }
            dropped.forEach { thumb(it.id).delete() }
            runCatching { store.setValue(KEY, kept) }
        }
        _items.value = kept
    }

    suspend fun remove(id: String) {
        val next = _items.value.filterNot { it.id == id }
        withContext(Dispatchers.IO) { thumb(id).delete(); runCatching { store.setValue(KEY, next) } }
        _items.value = next
    }

    fun get(id: String): Draft? = _items.value.firstOrNull { it.id == id }
}
