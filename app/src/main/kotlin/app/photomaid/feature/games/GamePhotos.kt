package app.photomaid.feature.games

import android.content.Context
import android.graphics.Bitmap
import androidx.core.net.toUri
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.photomaid.core.games.PhotoMemorySelection
import app.photomaid.media.MediaCategory
import app.photomaid.media.MediaLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Bildquelle der Spiele (wie iOS: Mediathek, Favoriten, neueste Fotos). Fällt nie auf eine andere Quelle zurück. */
enum class GamePhotoSource { LIBRARY, FAVORITES, NEWEST }

/**
 * Lädt kleine Vorschauen (256 px) für Spiele – nur im Arbeitsspeicher, nie auf die Platte, nie hochgeladen.
 * Quelle ist [MediaLibrary] von `AppModel.photos`: ausgeblendete Medien und vom Aufräumen ausgenommene Ordner bleiben
 * draußen (wie iOS Tresor/Ausgeblendet/Ausgenommen). Bis zu sechs Vorschauen gleichzeitig (iOS 22.10.2026).
 */
object GamePhotos {
    private const val SIDE = 256
    private const val NEWEST_WINDOW = 200

    suspend fun load(context: Context, library: MediaLibrary, source: GamePhotoSource, count: Int, side: Int = SIDE): List<Pair<String, ImageBitmap>> =
        withContext(Dispatchers.IO) {
            if (!library.access.value.canRead || count <= 0) return@withContext emptyList()
            val all = library.assets(MediaCategory.PHOTOS)
            val candidates = when (source) {
                GamePhotoSource.LIBRARY -> PhotoMemorySelection.pool(all.map { PhotoMemorySelection.Candidate(it.id, isFavorite = it.isFavorite) }, PhotoMemorySelection.Scope.Library).shuffled()
                GamePhotoSource.FAVORITES -> PhotoMemorySelection.pool(all.map { PhotoMemorySelection.Candidate(it.id, isFavorite = it.isFavorite) }, PhotoMemorySelection.Scope.Favorites).shuffled()
                GamePhotoSource.NEWEST -> all.sortedByDescending { it.creationDate }.take(NEWEST_WINDOW).map { it.id }.shuffled()
            }
            // 3× Reserve für nicht ladbare Vorschauen; nie die ganze Mediathek laden.
            val tries = candidates.take(count * 3)
            val gate = Semaphore(6)
            val out = mutableListOf<Pair<String, ImageBitmap>>()
            for (chunk in tries.chunked(count)) {
                coroutineScope {
                    chunk.map { id -> async { gate.withPermit { ensureActive(); thumbnail(context, id, side)?.let { id to it } } } }.awaitAll()
                }.filterNotNull().forEach { if (out.size < count) out += it }
                if (out.size >= count) break
            }
            out
        }

    private fun thumbnail(context: Context, id: String, side: Int): ImageBitmap? = runCatching {
        val bmp: Bitmap = context.contentResolver.loadThumbnail(id.toUri(), Size(side, side), null)
        bmp.asImageBitmap()
    }.getOrNull()
}
