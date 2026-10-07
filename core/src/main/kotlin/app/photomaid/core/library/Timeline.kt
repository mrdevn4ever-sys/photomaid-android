package app.photomaid.core.library

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Stufe der Zeitachse: Tage (große Kacheln) · Monate · Jahre (kleine Kacheln). Zwei Finger wechseln die Stufe. */
enum class TimelineLevel(val columns: Int) {
    DAY(3), MONTH(5), YEAR(8);
    fun finer(): TimelineLevel = entries[(ordinal - 1).coerceAtLeast(0)]
    fun coarser(): TimelineLevel = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]
}

/** Abschnitt der Zeitachse: Schlüssel (Tag, Monat oder Jahr) und die Medien darin (neueste zuerst). */
data class TimelineSection<T>(val key: TimelineKey, val items: List<T>)

/** Zeitlicher Schlüssel. Ohne Datum: [unknown]. */
data class TimelineKey(val year: Int, val month: Int = 0, val day: Int = 0, val unknown: Boolean = false) : Comparable<TimelineKey> {
    override fun compareTo(other: TimelineKey): Int = compareValuesBy(this, other, { if (it.unknown) 0 else 1 }, { it.year }, { it.month }, { it.day })
    val date: LocalDate? get() = if (unknown || day == 0) null else LocalDate.of(year, month, day)
    val yearMonth: YearMonth? get() = if (unknown || month == 0) null else YearMonth.of(year, month)
}

/**
 * Zeitachse aus einer Momentaufnahme: reine Gruppierung, nichts gespeichert. Neueste Abschnitte zuerst; Medien ohne
 * Datum am Ende in einem eigenen Abschnitt (keine erfundenen Daten).
 */
object Timeline {
    fun <T> sections(items: List<T>, date: (T) -> Instant?, level: TimelineLevel, zone: ZoneId): List<TimelineSection<T>> {
        val groups = LinkedHashMap<TimelineKey, MutableList<T>>()
        for (it in items) {
            val k = key(date(it), level, zone)
            groups.getOrPut(k) { mutableListOf() }.add(it)
        }
        return groups.entries.sortedByDescending { it.key }.map { (k, v) ->
            TimelineSection(k, v.sortedByDescending { date(it) ?: Instant.MIN })
        }
    }

    fun key(i: Instant?, level: TimelineLevel, zone: ZoneId): TimelineKey {
        if (i == null) return TimelineKey(0, unknown = true)
        val d = i.atZone(zone).toLocalDate()
        return when (level) {
            TimelineLevel.DAY -> TimelineKey(d.year, d.monthValue, d.dayOfMonth)
            TimelineLevel.MONTH -> TimelineKey(d.year, d.monthValue)
            TimelineLevel.YEAR -> TimelineKey(d.year)
        }
    }
}

/**
 * Suche ohne Akzente und Groß/klein: „é“ = „e“, „ß“ = „ss“, „Ø“ = „o“. Treffer = jedes Wort der Anfrage kommt in einem
 * der Felder vor (Teilwort genügt). Rein lokal.
 */
object SearchText {
    private val marks = Regex("\\p{M}+")
    private val extra = mapOf('ß' to "ss", 'ø' to "o", 'đ' to "d", 'ł' to "l", 'æ' to "ae", 'œ' to "oe", 'ı' to "i")

    fun normalize(s: String): String {
        val lower = s.lowercase()
        val sb = StringBuilder(lower.length)
        for (ch in lower) sb.append(extra[ch] ?: ch)
        return marks.replace(Normalizer.normalize(sb, Normalizer.Form.NFD), "")
    }

    fun tokens(query: String): List<String> = normalize(query).split(Regex("[\\s,;/_.-]+")).filter { it.isNotBlank() }

    /** [fields] sollten schon normalisiert sein (einmal je Medium), [queryTokens] aus [tokens]. */
    fun matches(queryTokens: List<String>, fields: List<String>): Boolean =
        queryTokens.isNotEmpty() && queryTokens.all { t -> fields.any { it.contains(t) } }
}
