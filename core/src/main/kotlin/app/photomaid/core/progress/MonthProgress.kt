package app.photomaid.core.progress

import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.ItemRecord
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Ein Kalendermonat (lokale Zeitzone), z. B. `2026-09`. Monate sind nur Filter – nichts wird je Monat gespeichert. */
data class MonthKey(val year: Int, val month: Int) : Comparable<MonthKey> {
    override fun compareTo(other: MonthKey) = compareValuesBy(this, other, { it.year }, { it.month })
    override fun toString() = "%04d-%02d".format(year, month)
    val yearMonth: YearMonth get() = YearMonth.of(year, month)

    companion object {
        fun of(t: Instant, zone: ZoneId = ZoneId.systemDefault()): MonthKey = t.atZone(zone).let { MonthKey(it.year, it.monthValue) }
        fun parse(s: String?): MonthKey? = s?.let { runCatching { YearMonth.parse(it) }.getOrNull() }?.let { MonthKey(it.year, it.monthValue) }
    }
}

enum class MonthState { NOT_STARTED, IN_PROGRESS, DONE }

/**
 * Echter Fortschritt eines Monats aus dem Ledger. „Durchgesehen“ = Behalten, Papierkorb, entfernt oder Später mit
 * Termin / ohne Termin beiseitegelegt; [later] davon getrennt sichtbar. Nur in einer Sitzung übersprungen zählt als offen.
 */
data class MonthProgress(val month: MonthKey, val total: Int, val reviewed: Int, val later: Int) {
    val open: Int get() = total - reviewed
    val state: MonthState get() = when {
        total == 0 || reviewed >= total -> MonthState.DONE
        reviewed == 0 -> MonthState.NOT_STARTED
        else -> MonthState.IN_PROGRESS
    }
    val fraction: Float get() = if (total == 0) 1f else reviewed.toFloat() / total
}

object ProgressRules {
    fun isReviewed(r: ItemRecord?): Boolean = when (r?.disposition) {
        null, Disposition.UNDECIDED -> false
        Disposition.KEPT, Disposition.MARKED_FOR_CLEANUP, Disposition.REMOVED -> true
        Disposition.LATER -> r.dueDay != null || r.bookmarked
    }

    fun isLater(r: ItemRecord?): Boolean = r?.disposition == Disposition.LATER && isReviewed(r)

    /**
     * Monate (neueste zuerst) aus Medien mit Datum; Medien ohne Datum bilden keinen Monat.
     * [REMOVED] zählt nur, solange das Medium noch in der Liste ist (wiederhergestellt) – dann ist es wieder offen.
     */
    fun <T> months(items: List<T>, date: (T) -> Instant?, record: (T) -> ItemRecord?, zone: ZoneId = ZoneId.systemDefault()): List<MonthProgress> =
        items.mapNotNull { a -> date(a)?.let { MonthKey.of(it, zone) to a } }
            .groupBy({ it.first }, { it.second })
            .map { (m, list) ->
                val recs = list.map(record)
                // Ein noch vorhandenes, früher entferntes Medium wurde zurückgeholt: wieder offen (wie im Feed).
                val reviewed = recs.count { isReviewed(it) && it?.disposition != Disposition.REMOVED }
                MonthProgress(m, list.size, reviewed, recs.count { isLater(it) })
            }
            .sortedByDescending { it.month }
}
