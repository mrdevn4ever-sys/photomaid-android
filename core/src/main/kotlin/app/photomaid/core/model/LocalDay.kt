package app.photomaid.core.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Ein lokaler Kalendertag (gregorianisches Datum ohne Uhrzeit und ohne Zeitzone).
 *
 * Wiedervorlagen und Tageslimits werden als lokaler Kalendertag gespeichert, Audit-Zeitpunkte
 * zusätzlich als UTC-Instant. Arithmetik ausschließlich kalenderbasiert, nie über `n × 24 h`.
 */
@Serializable
data class LocalDay(val year: Int, val month: Int, val day: Int) : Comparable<LocalDay> {
    private val date: LocalDate get() = LocalDate.of(year, month, day)

    /** Tagesbeginn in der Zeitzone (bei fehlender Mitternacht der erste gültige Zeitpunkt). */
    fun startOfDay(zone: ZoneId): Instant = date.atStartOfDay(zone).toInstant()

    /** `n` Kalendertage später (früher bei negativem `n`). */
    fun addingDays(n: Int): LocalDay = of(date.plusDays(n.toLong()))

    /** `n` Kalendermonate später. Nicht existierende Tage werden auf das Monatsende gekürzt. */
    fun addingMonths(n: Int): LocalDay = of(date.plusMonths(n.toLong()))

    /** Kalendertage von `this` bis `other` (positiv, wenn `other` später liegt). */
    fun daysUntil(other: LocalDay): Int = ChronoUnit.DAYS.between(date, other.date).toInt()

    fun toLocalDate(): LocalDate = date

    override fun compareTo(other: LocalDay): Int =
        compareValuesBy(this, other, LocalDay::year, LocalDay::month, LocalDay::day)

    /** ISO `2026-10-08`, immer mit ASCII-Ziffern (Speicherschlüssel – nie abhängig von der Gerätesprache). */
    override fun toString(): String = date.toString()

    companion object {
        fun of(d: LocalDate) = LocalDay(d.year, d.monthValue, d.dayOfMonth)
        fun of(instant: Instant, zone: ZoneId) = of(instant.atZone(zone).toLocalDate())
    }
}

/** Injizierbare Uhr. Produktion: [SystemClock]. Tests: eigene Implementierung. */
interface Clock {
    val now: Instant
    val zone: ZoneId
    val today: LocalDay get() = LocalDay.of(now, zone)
}

object SystemClock : Clock {
    override val now: Instant get() = Instant.now()
    override val zone: ZoneId get() = ZoneId.systemDefault()
}
