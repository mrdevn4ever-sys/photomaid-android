package app.photomaid.feature.library

import android.content.Context
import android.text.format.DateFormat
import app.photomaid.AppModel
import app.photomaid.R
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.library.SearchText
import app.photomaid.core.library.TimelineKey
import app.photomaid.core.library.TimelineLevel
import app.photomaid.core.model.MediaKind
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
 * Favorit). Nichts verlässt das Gerät, nichts wird gespeichert.
 */
class LibrarySearch(context: Context, assets: List<MediaAsset>, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()) {
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
                d?.year?.toString(), kinds[a.kind], if (a.isFavorite) fav else null, a.mimeType?.substringAfter('/'))
                .map(SearchText::normalize)
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
 * In den PhotoMaid-Papierkorb legen (eine Entscheidung je Medium, derselbe Ledger-Weg wie im Feed). Nichts wird
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
