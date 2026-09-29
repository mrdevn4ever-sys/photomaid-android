package app.photomaid.core.rules

import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.model.ConnectedSourceID
import app.photomaid.core.model.InstantSerializer
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.UUIDSerializer
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Quelle einer Regel. */
@Serializable
sealed interface RuleSource {
    @Serializable data object Screenshots : RuleSource
    @Serializable data object Photos : RuleSource
    @Serializable data object Videos : RuleSource
    @Serializable data class ConnectedSource(val id: ConnectedSourceID) : RuleSource
}

/** Deklarativer Filter. Keine Skripte, keine KI. */
@Serializable
sealed interface RuleFilter {
    @Serializable data class OlderThan(val days: Int) : RuleFilter
    @Serializable data class KnownSizeAtLeast(val bytes: Long) : RuleFilter
    @Serializable data object All : RuleFilter
}

@Serializable
sealed interface RuleTrigger {
    /** Bei jedem Start/Resume geprüft, höchstens eine Aufgabe pro lokalem Tag. */
    @Serializable data object Daily : RuleTrigger

    /** An einem Wochentag (1 = Sonntag … 7 = Samstag, wie iOS), eine Aufgabe pro Woche. */
    @Serializable data class Weekly(val weekday: Int) : RuleTrigger

    /** Sobald mindestens `count` neu **entdeckte** Items warten. */
    @Serializable data class NewlyDiscovered(val count: Int) : RuleTrigger
}

@Serializable
@JvmInline
value class ReviewRuleID(@Serializable(UUIDSerializer::class) val rawValue: UUID = UUID.randomUUID())

/** Quelle + Filter + Auslöser → Review-Aufgabe. Niemals Löschung. */
@Serializable
data class ReviewRule(
    val id: ReviewRuleID = ReviewRuleID(),
    val source: RuleSource,
    val filter: RuleFilter,
    val trigger: RuleTrigger,
    val isEnabled: Boolean = true,
    val notify: Boolean = false,
)

/** Inventar-Eintrag, wie er bei einem tatsächlichen Scan erfasst wurde. */
data class InventoryItem(
    val item: ItemReference,
    val source: RuleSource,
    val createdAt: Instant?,
    val size: KnownSize,
    /** Zeitpunkt, zu dem PhotoMaid das Item zum ersten Mal gesehen hat. */
    val firstSeenAt: Instant,
)

/** Ergebnis einer Regel: eine Review-Aufgabe. Enthält ausdrücklich keine Löschfreigabe. */
@Serializable
data class ReviewTask(
    /** Idempotenzschlüssel: gleiche Regel + gleicher Zeitraum → dieselbe Aufgabe. */
    val id: String,
    val ruleID: ReviewRuleID,
    val items: List<ItemReference>,
    @Serializable(InstantSerializer::class) val createdAt: Instant,
)

/** iOS-Wochentag (1 = Sonntag … 7 = Samstag) eines Zeitpunkts. */
fun iosWeekday(instant: Instant, zone: ZoneId): Int = instant.atZone(zone).dayOfWeek.value % 7 + 1

object RuleEvaluator {
    /** Wertet eine Regel gegen ein tatsächlich gescanntes Inventar aus. */
    fun evaluate(
        rule: ReviewRule,
        inventory: List<InventoryItem>,
        records: Map<ItemReference, ItemRecord>,
        existingTaskIDs: Set<String>,
        lastEvaluatedAt: Instant?,
        now: Instant,
        zone: ZoneId,
    ): ReviewTask? {
        if (!rule.isEnabled) return null
        val today = LocalDay.of(now, zone)
        val periodKey = when (val t = rule.trigger) {
            RuleTrigger.Daily -> "d$today"
            is RuleTrigger.Weekly -> {
                if (iosWeekday(now, zone) != t.weekday) return null
                "w$today"
            }
            is RuleTrigger.NewlyDiscovered -> "n$today"
        }
        val taskID = "${rule.id.rawValue}|$periodKey"
        if (taskID in existingTaskIDs) return null

        val matching = inventory.filter { inv ->
            if (inv.source != rule.source) return@filter false
            val r = records[inv.item]
            if (r != null && r.disposition != Disposition.UNDECIDED) return@filter false
            when (val f = rule.filter) {
                RuleFilter.All -> true
                is RuleFilter.OlderThan -> inv.createdAt?.let { LocalDay.of(it, zone).addingDays(f.days) <= today } ?: false
                // unbekannt ≠ 0, aber auch nicht ≥ x
                is RuleFilter.KnownSizeAtLeast -> inv.size.bytesOrNull?.let { it >= f.bytes } ?: false
            }
        }
        val trigger = rule.trigger
        val selected = if (trigger is RuleTrigger.NewlyDiscovered) {
            val fresh = matching.filter { inv -> lastEvaluatedAt?.let { inv.firstSeenAt > it } ?: true }
            if (fresh.size < trigger.count) return null
            fresh
        } else matching
        if (selected.isEmpty()) return null
        return ReviewTask(taskID, rule.id, selected.map { it.item }.sortedBy { it.description }, now)
    }
}
