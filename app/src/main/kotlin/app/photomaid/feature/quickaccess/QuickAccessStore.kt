package app.photomaid.feature.quickaccess

import app.photomaid.core.organize.MediaFolders
import app.photomaid.core.quickaccess.QuickAccess
import app.photomaid.core.quickaccess.QuickAccessItem
import app.photomaid.core.quickaccess.QuickSourceKind
import app.photomaid.data.KeyValueStore
import app.photomaid.media.MediaAsset
import app.photomaid.core.model.MediaKind
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
 * Mitglieder einer Quelle aus EINER gemeinsamen Momentaufnahme der Mediathek ([reviewableMedia]: Fotos inkl.
 * Screenshots + Videos, neueste zuerst). Keine eigene MediaStore-Abfrage je Quelle, keine gespeicherten Listen.
 * Liefert nur Mitglieder – offen/entschieden ergibt ausschließlich der globale Ledger ([app.photomaid.AppModel.pendingQueue]).
 */
fun resolveSource(item: QuickAccessItem, all: List<MediaAsset>, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<MediaAsset> {
    val photos = { all.filter { it.kind != MediaKind.VIDEO } }
    val videos = { all.filter { it.kind == MediaKind.VIDEO } }
    return when (item.kind) {
        QuickSourceKind.PHOTOS -> photos()
        QuickSourceKind.VIDEOS -> videos()
        QuickSourceKind.SCREENSHOTS -> all.filter { it.kind == MediaKind.SCREENSHOT }
        QuickSourceKind.LATEST_PHOTOS -> newest(photos(), QuickAccess.LATEST_PHOTOS_LIMIT)
        QuickSourceKind.LATEST_VIDEOS -> newest(videos(), QuickAccess.LATEST_VIDEOS_LIMIT)
        QuickSourceKind.THIS_WEEK -> {
            val (from, to) = QuickAccess.thisWeek(now, zone)
            all.filter { a -> a.creationDate?.let { !it.isBefore(from) && it.isBefore(to) } == true }
        }
        QuickSourceKind.FOLDER -> {
            val folder = item.folder?.let { MediaFolders.normalize(it) }
            all.filter { a -> a.relativePath?.let { MediaFolders.normalize(it) }.equals(folder, ignoreCase = true) }
        }
    }
}

/** Feed-Einstieg: frische Momentaufnahme, dann dieselbe Auflösung wie auf „Heute“. */
fun MediaLibrary.assetsFor(item: QuickAccessItem, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<MediaAsset> =
    resolveSource(item, reviewableMedia(), now, zone)

/** Anzeige-Stand je Quelle: Mitglieder insgesamt und davon offen (für „Leer“, „✓“ bzw. Zahl). */
data class SourceStatus(val members: Int, val pending: Int)

/** Alle Quellen aus einer Momentaufnahme + der globalen Menge offener Medien-IDs – ohne weitere Abfragen. */
fun statusFor(items: List<QuickAccessItem>, all: List<MediaAsset>, pendingIds: Set<String>, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Map<String, SourceStatus> =
    items.associate { q -> resolveSource(q, all, now, zone).let { m -> q.key to SourceStatus(m.size, m.count { it.id in pendingIds }) } }

private fun newest(list: List<MediaAsset>, limit: Int) =
    list.sortedWith(compareByDescending<MediaAsset> { it.creationDate }.thenBy { it.id }).take(limit)
