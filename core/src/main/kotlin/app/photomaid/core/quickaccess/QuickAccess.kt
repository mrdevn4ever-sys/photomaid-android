package app.photomaid.core.quickaccess

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Art einer Review-Quelle für Schnellzugriffe. Eine Quelle ist nur eine Abfrage (welche Medien); sie hat KEINEN
 * eigenen Entscheidungszustand. Entscheidungen hängen am Medium (Ledger) – ein Medium in mehreren Quellen
 * ist überall gleichzeitig entschieden. Neue Quellen (z. B. später „Momente“) = neuer Eintrag hier + eine Abfrage.
 */
@Serializable
enum class QuickSourceKind { PHOTOS, VIDEOS, SCREENSHOTS, LATEST_PHOTOS, LATEST_VIDEOS, THIS_WEEK, FOLDER }

/** Ein Schnellzugriff. [folder] nur bei [QuickSourceKind.FOLDER] (MediaStore `RELATIVE_PATH`, mit `/`). */
@Serializable
data class QuickAccessItem(val kind: QuickSourceKind, val folder: String? = null) {
    val key: String get() = if (folder != null) "${kind.name}:$folder" else kind.name
}

object QuickAccess {
    /** Bewusst begrenzt: drei Reihen à zwei Kacheln, Heute bleibt ruhig. */
    const val MAX_ITEMS = 6
    /** „Neueste Fotos“ = die neuesten 50 zugänglichen Fotos (davon zählen nur die offenen). */
    const val LATEST_PHOTOS_LIMIT = 50
    /** „Neueste Videos“ = die neuesten 20 zugänglichen Videos. */
    const val LATEST_VIDEOS_LIMIT = 20

    val defaults: List<QuickAccessItem> = listOf(QuickAccessItem(QuickSourceKind.PHOTOS), QuickAccessItem(QuickSourceKind.VIDEOS))

    fun canAdd(list: List<QuickAccessItem>, item: QuickAccessItem): Boolean = list.size < MAX_ITEMS && list.none { it.key == item.key }

    fun add(list: List<QuickAccessItem>, item: QuickAccessItem): List<QuickAccessItem> = if (canAdd(list, item)) list + item else list

    fun remove(list: List<QuickAccessItem>, item: QuickAccessItem): List<QuickAccessItem> = list.filter { it.key != item.key }

    /** Um eine Position verschieben (−1 = nach vorn, +1 = nach hinten); am Rand ohne Wirkung. */
    fun move(list: List<QuickAccessItem>, item: QuickAccessItem, delta: Int): List<QuickAccessItem> {
        val i = list.indexOfFirst { it.key == item.key }
        val j = i + delta
        if (i < 0 || j !in list.indices) return list
        return list.toMutableList().apply { add(j, removeAt(i)) }
    }

    /** Gespeicherten Stand bereinigen (Duplikate, Überlänge, Ordner ohne Pfad). */
    fun sanitize(list: List<QuickAccessItem>): List<QuickAccessItem> =
        list.filter { it.kind != QuickSourceKind.FOLDER || !it.folder.isNullOrBlank() }.distinctBy { it.key }.take(MAX_ITEMS)

    /**
     * Aktuelle lokale Kalenderwoche [Beginn, Ende) nach Wochenbeginn der Region (Deutsch: Montag).
     * Keine „letzten 7 Tage in UTC“: „Diese Woche“ heißt die Woche, die der Kalender zeigt.
     */
    fun thisWeek(now: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): Pair<Instant, Instant> {
        val first = WeekFields.of(locale).firstDayOfWeek
        val start: LocalDate = LocalDate.ofInstant(now, zone).with(TemporalAdjusters.previousOrSame(first))
        return start.atStartOfDay(zone).toInstant() to start.plusWeeks(1).atStartOfDay(zone).toInstant()
    }
}
