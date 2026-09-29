package app.photomaid.core.later

import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.ReviewSessionID
import kotlinx.serialization.Serializable

/** Voreinstellungen: Morgen · 7 Tage · 30 Tage. */
enum class LaterPreset(val days: Int) {
    TOMORROW(1), SEVEN_DAYS(7), THIRTY_DAYS(30);

    /** Kalenderarithmetik auf dem lokalen Tag, nicht `n × 24 h`. */
    fun dueDay(from: LocalDay): LocalDay = from.addingDays(days)
}

/** Einheit des Drehrads (iOS: `LaterInterval.Unit`). */
@Serializable
enum class LaterUnit { DAYS, WEEKS, MONTHS }

/** Selbst gewählter Abstand im Drehrad: „In [n] [Tagen | Wochen | Monaten]“. */
@Serializable
class LaterInterval private constructor(val value: Int, val unit: LaterUnit) {
    fun dueDay(from: LocalDay): LocalDay = when (unit) {
        LaterUnit.DAYS -> from.addingDays(value)
        LaterUnit.WEEKS -> from.addingDays(7 * value)
        LaterUnit.MONTHS -> from.addingMonths(value)
    }

    override fun equals(other: Any?) = other is LaterInterval && other.value == value && other.unit == unit
    override fun hashCode() = value * 31 + unit.hashCode()
    override fun toString() = "LaterInterval($value, $unit)"

    companion object {
        operator fun invoke(value: Int, unit: LaterUnit) = LaterInterval(maxOf(1, value), unit)

        /** Standard beim Wisch nach rechts (einstellbar, Vorgabe: 2 Tage). */
        val defaultQuick = LaterInterval(2, LaterUnit.DAYS)
        val allowedQuickDays = listOf(1, 2, 3, 7)

        /** Wertebereich des Drehrads je Einheit. */
        fun range(unit: LaterUnit): IntRange = when (unit) {
            LaterUnit.DAYS -> 1..30
            LaterUnit.WEEKS -> 1..12
            LaterUnit.MONTHS -> 1..12
        }
    }
}

sealed interface LaterStatus {
    /** Nur in einer Session übersprungen, kein Termin. */
    data object SessionOnly : LaterStatus
    data class Upcoming(val day: LocalDay) : LaterStatus
    data class Due(val day: LocalDay) : LaterStatus
}

/** Aufbau der Review-Warteschlangen aus Kandidaten und gespeicherten Entscheidungen. */
object ReviewQueues {
    fun laterStatus(record: ItemRecord, today: LocalDay): LaterStatus? {
        if (record.disposition != Disposition.LATER) return null
        val day = record.dueDay ?: return LaterStatus.SessionOnly
        return if (day <= today) LaterStatus.Due(day) else LaterStatus.Upcoming(day)
    }

    /**
     * Normale Warteschlange: unentschiedene Items sowie terminlos Zurückgestellte aus früheren
     * Sessions. Terminierte Items sind ausgeschlossen, fällige erscheinen nur im Wieder-fällig-Bereich.
     */
    fun normalQueue(
        candidates: List<ItemReference>,
        records: Map<ItemReference, ItemRecord>,
        session: ReviewSessionID,
        @Suppress("UNUSED_PARAMETER") today: LocalDay,
    ): List<ItemReference> = candidates.filter { item ->
        val r = records[item] ?: return@filter true
        when (r.disposition) {
            Disposition.UNDECIDED -> true
            Disposition.KEPT, Disposition.MARKED_FOR_CLEANUP, Disposition.REMOVED -> false
            Disposition.LATER -> r.dueDay == null && r.skippedInSession != session
        }
    }

    /** Wieder fällig: Termin erreicht und nicht in der aktuellen Session übersprungen. */
    fun dueQueue(records: Collection<ItemRecord>, session: ReviewSessionID, today: LocalDay): List<ItemReference> =
        records.filter { laterStatus(it, today) is LaterStatus.Due && it.skippedInSession != session }
            .sortedWith(compareBy<ItemRecord> { it.dueDay }.thenBy { it.item.description })
            .map { it.item }

    /** Gruppierung für den Später-Screen nach echten Datumsgruppen. */
    fun laterGroups(records: Collection<ItemRecord>, today: LocalDay): List<Pair<LaterGroup, List<ItemRecord>>> {
        val groups = mutableMapOf<LaterGroup, MutableList<ItemRecord>>()
        for (r in records) {
            val key = when (val s = laterStatus(r, today) ?: continue) {
                LaterStatus.SessionOnly -> LaterGroup.NoDate
                is LaterStatus.Due -> LaterGroup.Due
                is LaterStatus.Upcoming -> {
                    val n = today.daysUntil(s.day)
                    if (n == 1) LaterGroup.Tomorrow else if (n <= 7) LaterGroup.WithinSevenDays else LaterGroup.Later(s.day)
                }
            }
            groups.getOrPut(key) { mutableListOf() }.add(r)
        }
        return groups.keys.sorted().map { k -> k to groups.getValue(k).sortedBy { it.item.description } }
    }
}

sealed interface LaterGroup : Comparable<LaterGroup> {
    data object Due : LaterGroup
    data object Tomorrow : LaterGroup
    data object WithinSevenDays : LaterGroup
    data class Later(val day: LocalDay) : LaterGroup
    data object NoDate : LaterGroup

    private val order: Int
        get() = when (this) {
            Due -> 0; Tomorrow -> 1; WithinSevenDays -> 2; is Later -> 3; NoDate -> 4
        }

    override fun compareTo(other: LaterGroup): Int {
        if (order != other.order) return order.compareTo(other.order)
        if (this is Later && other is Later) return day.compareTo(other.day)
        return 0
    }
}
