package app.photomaid.feature.library

import android.content.Context
import android.text.format.DateFormat
import app.photomaid.AppModel
import app.photomaid.AppSettingsData
import app.photomaid.R
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.library.FolderEntry
import app.photomaid.core.library.FolderGroup
import app.photomaid.core.library.FolderListSort
import app.photomaid.core.library.FolderNode
import app.photomaid.core.library.FolderSort
import app.photomaid.core.library.FolderTree
import app.photomaid.core.library.SearchText
import app.photomaid.core.library.TimelineKey
import app.photomaid.core.library.TimelineLevel
import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.MediaKind
import app.photomaid.core.organize.MediaFolders
import app.photomaid.media.ExcludingMediaLibrary
import app.photomaid.media.MediaAsset
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Filter oben in „Fotos“ (nur Ansicht, nichts gespeichert). */
enum class LibraryFilter(val label: Int) {
    ALL(R.string.lib_filter_all), PHOTOS(R.string.cat_photos), VIDEOS(R.string.cat_videos), SCREENSHOTS(R.string.cat_screenshots), FAVORITES(R.string.lib_filter_favorites);

    fun accepts(a: MediaAsset): Boolean = when (this) {
        ALL -> true
        PHOTOS -> a.kind == MediaKind.PHOTO
        VIDEOS -> a.kind == MediaKind.VIDEO
        SCREENSHOTS -> a.kind == MediaKind.SCREENSHOT
        FAVORITES -> a.isFavorite
    }
}

/**
 * Suchindex einer Momentaufnahme: je Medium normalisierte Felder (Name, Ordner, Monat/Jahr in der Gerätesprache, Art,
 * Favorit, Kamera-Modell aus EXIF, sobald gelesen). Nichts verlässt das Gerät, nichts wird gespeichert.
 */
class LibrarySearch(context: Context, assets: List<MediaAsset>, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault(),
    camera: (String) -> String? = { null },
    /** Eigene Schlagwörter und Notizen je Medium (Organisieren). */
    extra: (String) -> List<String> = { emptyList() }) {
    private val fields: Map<String, List<String>>

    init {
        val month = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMMyyyy"), locale)
        val monthShort = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMyyyy"), locale)
        val day = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMM"), locale)
        val kinds = mapOf(MediaKind.PHOTO to context.getString(R.string.photo), MediaKind.VIDEO to context.getString(R.string.video),
            MediaKind.SCREENSHOT to context.getString(R.string.cat_screenshots))
        val fav = context.getString(R.string.lib_kw_favorite)
        fields = assets.associate { a ->
            val d = a.creationDate?.atZone(zone)
            a.id to listOfNotNull(a.displayName, a.relativePath, d?.let(month::format), d?.let(monthShort::format), d?.let(day::format),
                d?.year?.toString(), kinds[a.kind], if (a.isFavorite) fav else null, a.mimeType?.substringAfter('/'), camera(a.id))
                .plus(extra(a.id)).map(SearchText::normalize)
        }
    }

    fun filter(assets: List<MediaAsset>, query: String): List<MediaAsset> {
        val t = SearchText.tokens(query)
        if (t.isEmpty()) return assets
        return assets.filter { SearchText.matches(t, fields[it.id] ?: emptyList()) }
    }
}

/** Kopfzeile eines Abschnitts in der Gerätesprache („Freitag, 2. Oktober 2026“, „Oktober 2026“, „2026“). */
fun timelineTitle(context: Context, key: TimelineKey, level: TimelineLevel, locale: Locale = Locale.getDefault()): String {
    if (key.unknown) return context.getString(R.string.lib_no_date)
    return when (level) {
        TimelineLevel.DAY -> key.date?.let { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMyyyy"), locale).format(it) }
        TimelineLevel.MONTH -> key.yearMonth?.let { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMMyyyy"), locale).format(it) }
        TimelineLevel.YEAR -> null
    } ?: key.year.toString()
}

/**
 * In den GaleriQ-Papierkorb legen (eine Entscheidung je Medium, derselbe Ledger-Weg wie im Feed). Nichts wird
 * gelöscht; endgültig entfernt wird erst im Papierkorb mit Systembestätigung.
 */
suspend fun markForCleanup(app: AppModel, assets: List<MediaAsset>): Map<MediaAsset, Disposition> {
    val before = assets.associateWith { app.ledger.record(it.reference).disposition }
    for (a in assets) if (before[a] != Disposition.MARKED_FOR_CLEANUP) app.decide(a.reference, DecisionAction.MarkForCleanup)
    return before
}

/** Rückgängig: vorheriger Zustand (unentschieden oder behalten); war es schon im Papierkorb, bleibt es dort. */
suspend fun undoMark(app: AppModel, before: Map<MediaAsset, Disposition>) {
    for ((a, d) in before) when (d) {
        Disposition.MARKED_FOR_CLEANUP -> Unit
        Disposition.KEPT -> app.decide(a.reference, DecisionAction.Keep)
        else -> app.decide(a.reference, DecisionAction.Unmark)
    }
}

// ---- G2: Ordner, Gruppen, Sortierung, Ausblenden -------------------------------------------------------------

/** Ordner der Momentaufnahme für den Baum (eigene Anzahl, jüngstes Aufnahmedatum). Medien ohne Pfad fehlen. */
fun folderEntries(assets: List<MediaAsset>): List<FolderEntry> =
    assets.filter { !it.relativePath.isNullOrBlank() }.groupBy { FolderTree.normalize(it.relativePath!!).lowercase() }.map { (_, v) ->
        FolderEntry(v.first().relativePath!!, v.size, v.mapNotNull { (it.creationDate ?: it.dateAdded)?.toEpochMilli() }.maxOrNull())
    }

/** Titelbild je Ordnerpfad (klein geschrieben): neuestes Medium im Ordner inkl. Unterordnern. */
fun folderCovers(roots: List<FolderNode>, assets: List<MediaAsset>): Map<String, MediaAsset> {
    val newest = assets.filter { it.relativePath != null }.groupBy { FolderTree.normalize(it.relativePath!!).lowercase() }
        .mapValues { (_, v) -> v.maxBy { (it.creationDate ?: it.dateAdded)?.toEpochMilli() ?: Long.MIN_VALUE } }
    val out = HashMap<String, MediaAsset>()
    fun walk(n: FolderNode): MediaAsset? {
        val candidates = listOfNotNull(newest[n.path.lowercase()]) + n.children.mapNotNull(::walk)
        val best = candidates.maxByOrNull { (it.creationDate ?: it.dateAdded)?.toEpochMilli() ?: Long.MIN_VALUE }
        if (best != null) out[n.path.lowercase()] = best
        return best
    }
    roots.forEach { walk(it) }
    return out
}

/** Schlüssel für Sortierung/Favoritenfilter: Ordnerpfad klein geschrieben bzw. `group:<id>`. */
fun folderKey(path: String): String = FolderTree.normalize(path).lowercase()
fun groupKey(id: String): String = "group:$id"

fun AppSettingsData.sortFor(key: String): FolderSort = folderSorts[key] ?: defaultFolderSort

fun sortMedia(assets: List<MediaAsset>, sort: FolderSort): List<MediaAsset> = sort.sort(assets,
    date = { (it.creationDate ?: it.dateAdded)?.toEpochMilli() }, added = { it.dateAdded?.toEpochMilli() },
    name = { it.displayName }, size = { (it.size as? KnownSize.Known)?.bytes })

val FolderSort.label: Int get() = when (this) {
    FolderSort.NEWEST -> R.string.lib_sort_newest; FolderSort.OLDEST -> R.string.lib_sort_oldest
    FolderSort.ADDED -> R.string.lib_sort_added; FolderSort.NAME -> R.string.lib_sort_name; FolderSort.LARGEST -> R.string.lib_sort_largest
}

val FolderListSort.label: Int get() = when (this) {
    FolderListSort.RECENT -> R.string.lib_folders_sort_recent; FolderListSort.NAME -> R.string.lib_sort_name; FolderListSort.COUNT -> R.string.lib_folders_sort_count
}

/** Reihenfolge für einen Ordner merken; „Für alle Ordner“ setzt den Standard und entfernt Einzelwerte nicht. */
suspend fun setFolderSort(app: AppModel, key: String, sort: FolderSort, asDefault: Boolean) = app.updateSettings { s ->
    if (asDefault) s.copy(defaultFolderSort = sort, folderSorts = s.folderSorts - key)
    else s.copy(folderSorts = s.folderSorts + (key to sort))
}

suspend fun setFavoritesOnly(app: AppModel, key: String, on: Boolean) = app.updateSettings { s ->
    s.copy(folderFavoritesOnly = (s.folderFavoritesOnly - key).let { if (on) it + key else it })
}

/** Medien in GaleriQ ausblenden bzw. wieder einblenden (nur lokale Liste; die Datei bleibt unverändert). */
suspend fun setMediaHidden(app: AppModel, ids: Collection<String>, hidden: Boolean) {
    if (ids.isEmpty()) return
    app.updateSettings { s -> s.copy(hiddenMedia = if (hidden) (s.hiddenMedia + ids).distinct() else s.hiddenMedia - ids.toSet()) }
    app.photos.bumpChangeToken()
}

suspend fun setFolderHidden(app: AppModel, path: String, hidden: Boolean) {
    val p = FolderTree.normalize(path)
    if (p.isEmpty()) return
    app.updateSettings { s ->
        val rest = s.hiddenFolders.filterNot { it.equals(p, ignoreCase = true) }
        s.copy(hiddenFolders = if (hidden) rest + p else rest)
    }
    app.photos.bumpChangeToken()
}

/** Gruppe anlegen oder ändern (gleiche ID). Leere Gruppen sind erlaubt nicht: ohne Ordner wird nichts gespeichert. */
suspend fun saveGroup(app: AppModel, group: FolderGroup) {
    if (group.paths.isEmpty() || group.name.isBlank()) return
    app.updateSettings { s ->
        val i = s.folderGroups.indexOfFirst { it.id == group.id }
        s.copy(folderGroups = if (i >= 0) s.folderGroups.toMutableList().also { it[i] = group } else s.folderGroups + group)
    }
}

suspend fun deleteGroup(app: AppModel, id: String) = app.updateSettings { s ->
    s.copy(folderGroups = s.folderGroups.filterNot { it.id == id }, folderSorts = s.folderSorts - groupKey(id),
        folderFavoritesOnly = s.folderFavoritesOnly - groupKey(id))
}

/** Für „Aufräumen“ aus der Mediathek: vom Aufräumen ausgenommene Ordner bleiben draußen. */
fun AppModel.cleanupCandidates(assets: List<MediaAsset>): List<MediaAsset> {
    val keys = settings.value.excludedFolders.map { MediaFolders.normalize(it).lowercase() }.filter { it.isNotEmpty() }
    return if (keys.isEmpty()) assets else assets.filterNot { ExcludingMediaLibrary.isExcluded(it, keys) }
}

/**
 * RAW+JPEG-Paare einer Momentaufnahme: das RAW steht hinter seinem JPEG (nicht einzeln im Raster) und geht bei
 * Papierkorb, Ausblenden und Verschieben immer mit. Reine Namensregel, siehe [app.photomaid.core.library.RawPairs].
 */
class RawIndex(assets: List<MediaAsset>) {
    /** JPEG/HEIC-ID → RAW. */
    val partner: Map<String, MediaAsset> = app.photomaid.core.library.RawPairs.pairs(assets, { it.displayName }, { it.relativePath }, { it.mimeType })
        .entries.associate { (k, v) -> k.id to v }
    private val hidden: Set<String> = partner.values.map { it.id }.toHashSet()

    /** Ohne RAWs, die einen Partner haben. */
    fun visible(list: List<MediaAsset>): List<MediaAsset> = if (hidden.isEmpty()) list else list.filter { it.id !in hidden }

    /** Auswahl samt RAW-Partnern (für Papierkorb, Ausblenden, Verschieben). */
    fun withPartners(list: List<MediaAsset>): List<MediaAsset> = if (partner.isEmpty()) list else (list + list.mapNotNull { partner[it.id] }).distinctBy { it.id }

    companion object { val EMPTY = RawIndex(emptyList()) }
}
