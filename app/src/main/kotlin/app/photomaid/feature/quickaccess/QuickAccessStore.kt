package app.photomaid.feature.quickaccess

import app.photomaid.core.organize.MediaFolders
import app.photomaid.core.quickaccess.QuickAccess
import app.photomaid.core.quickaccess.QuickAccessItem
import app.photomaid.core.quickaccess.QuickSourceKind
import app.photomaid.data.KeyValueStore
import app.photomaid.media.MediaAsset
import app.photomaid.media.MediaCategory
import app.photomaid.media.MediaLibrary
import app.photomaid.media.reviewableMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** Auswahl der Schnellzugriffe auf „Heute“ (lokal gespeichert; nur Art + Ordnerpfad, nie Medien-IDs). */
class QuickAccessStore(private val store: KeyValueStore) {
    private val _items = MutableStateFlow(QuickAccess.defaults)
    val items: StateFlow<List<QuickAccessItem>> = _items.asStateFlow()

    companion object { private const val KEY = "quickaccess.v1" }

    /** Erster Start: Fotos + Videos. */
    fun load() { _items.value = runCatching { store.value<List<QuickAccessItem>>(KEY) }.getOrNull()?.let(QuickAccess::sanitize) ?: QuickAccess.defaults }

    suspend fun update(change: (List<QuickAccessItem>) -> List<QuickAccessItem>) {
        val next = QuickAccess.sanitize(change(_items.value))
        _items.value = next
        withContext(Dispatchers.IO) { runCatching { store.setValue(KEY, next) } }
    }
}

/**
 * Mitglieder einer Quelle, frisch aus MediaStore (keine gespeicherten Listen). Liefert Medien, keinen Zustand:
 * offen/entschieden ergibt erst `AppModel.pendingQueue` aus dem globalen Ledger.
 */
fun MediaLibrary.assetsFor(item: QuickAccessItem, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<MediaAsset> =
    when (item.kind) {
        QuickSourceKind.PHOTOS -> assets(MediaCategory.PHOTOS)
        QuickSourceKind.VIDEOS -> assets(MediaCategory.VIDEOS)
        QuickSourceKind.SCREENSHOTS -> assets(MediaCategory.SCREENSHOTS)
        QuickSourceKind.LATEST_PHOTOS -> newest(assets(MediaCategory.PHOTOS, QuickAccess.LATEST_PHOTOS_LIMIT), QuickAccess.LATEST_PHOTOS_LIMIT)
        QuickSourceKind.LATEST_VIDEOS -> newest(assets(MediaCategory.VIDEOS, QuickAccess.LATEST_VIDEOS_LIMIT), QuickAccess.LATEST_VIDEOS_LIMIT)
        QuickSourceKind.THIS_WEEK -> {
            val (from, to) = QuickAccess.thisWeek(now, zone)
            reviewableMedia().filter { a -> a.creationDate?.let { !it.isBefore(from) && it.isBefore(to) } == true }
        }
        QuickSourceKind.FOLDER -> {
            val folder = item.folder?.let { MediaFolders.normalize(it) }
            reviewableMedia().filter { a -> a.relativePath?.let { MediaFolders.normalize(it) }.equals(folder, ignoreCase = true) }
        }
    }

private fun newest(list: List<MediaAsset>, limit: Int) =
    list.sortedWith(compareByDescending<MediaAsset> { it.creationDate }.thenBy { it.id }).take(limit)
