package app.photomaid.core.capabilities

import app.photomaid.core.model.InstantSerializer
import app.photomaid.core.model.LocalDay
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Kaufstatus aus dem einzigen Kauf-Gateway (Google Play Billing). */
@Serializable
enum class PurchaseState { UNKNOWN, NOT_PURCHASED, PENDING, PURCHASED, REVOKED }

/** Freiwillig freigeschaltete Rewarded-Vorschau: 30 reale Minuten Pro-Werkzeuge. */
@Serializable
data class RewardWindow(
    val rewardID: String,
    @Serializable(InstantSerializer::class) val grantedAt: Instant,
    val grantedDay: LocalDay,
) {
    val endsAt: Instant get() = grantedAt.plus(DURATION)

    /** Restzeit. Läuft im Hintergrund weiter. Uhr zurückgestellt → konservativ abgelaufen. */
    fun remaining(now: Instant): Duration {
        if (now.isBefore(grantedAt)) return Duration.ZERO
        val left = Duration.between(now, endsAt)
        return if (left.isNegative) Duration.ZERO else minOf(DURATION, left)
    }

    companion object { val DURATION: Duration = Duration.ofMinutes(30) }
}

sealed class RewardError(message: String) : Exception(message) {
    data object AlreadyPurchased : RewardError("purchased") { private fun readResolve(): Any = AlreadyPurchased }
    data object DailyUnlockUsed : RewardError("daily") { private fun readResolve(): Any = DailyUnlockUsed }
}

/** Lokale, persistierte Reward-Buchführung (idempotent). */
@Serializable
data class RewardLedger(val windows: List<RewardWindow> = emptyList()) {
    /** Nur nach tatsächlichem SDK-Reward-Callback aufrufen. Gleiche `rewardID` → keine zweite Freischaltung. */
    fun granting(rewardID: String, now: Instant, zone: ZoneId, purchase: PurchaseState): Pair<RewardLedger, RewardWindow> {
        windows.firstOrNull { it.rewardID == rewardID }?.let { return this to it }
        if (purchase == PurchaseState.PURCHASED) throw RewardError.AlreadyPurchased
        if (usedToday(now, zone)) throw RewardError.DailyUnlockUsed
        val w = RewardWindow(rewardID, now, LocalDay.of(now, zone))
        return RewardLedger(windows + w) to w
    }

    fun active(now: Instant): RewardWindow? = windows.lastOrNull()?.takeIf { it.remaining(now) > Duration.ZERO }

    /** Heute schon genutzt? Auch „ja“, wenn die Uhr vor den letzten Reward zurückgestellt wurde (konservativ). */
    fun usedToday(now: Instant, zone: ZoneId): Boolean {
        val day = LocalDay.of(now, zone)
        return windows.any { it.grantedDay == day } || windows.any { now.isBefore(it.grantedAt) }
    }

    fun canOfferReward(now: Instant, zone: ZoneId, purchase: PurchaseState): Boolean =
        purchase != PurchaseState.PURCHASED && !usedToday(now, zone)
}

/**
 * Zentrale Capability-Auflösung. Einzige Stelle, die Free / Pro-Test / Lifetime Pro unterscheidet.
 *
 * Fachlich getrennt (bewusst kein einzelnes `isPro`):
 * | Stufe          | Pro-Werkzeuge | Papierkorb ohne Tageslimit |
 * |----------------|---------------|----------------------------|
 * | FREE           | nein          | nein (15/Tag)              |
 * | TEMPORARY_PRO  | ja (30 min)   | **nein** (15/Tag bleibt)   |
 * | LIFETIME_PRO   | ja            | ja                         |
 */
data class Capabilities(
    val tier: Tier,
    /** Werbung darf angeboten/angefragt werden (nie für Lifetime Pro, nie bei unklarem Kaufstatus). */
    val adsAllowed: Boolean,
    /** Restzeit des 30-Minuten-Pro-Tests, sonst `null`. */
    val rewardRemaining: Duration?,
) {
    enum class Tier { FREE, TEMPORARY_PRO, LIFETIME_PRO }

    /** Identische Kopien, eigene Sammlungen – Lifetime Pro und der 30-Minuten-Test. */
    val hasProToolsAccess: Boolean get() = tier != Tier.FREE

    /** Nur der Kauf hebt das tägliche Papierkorb-Limit auf. Der Pro-Test ändert es NICHT. */
    val hasUnlimitedTrashAccess: Boolean get() = tier == Tier.LIFETIME_PRO

    /** `null` = unbegrenzt. Abgeleitet ausschließlich aus [hasUnlimitedTrashAccess]. */
    val dailyCleanupLimit: Int? get() = if (hasUnlimitedTrashAccess) null else FREE_DAILY_CLEANUP_LIMIT

    val exactDuplicates: Boolean get() = hasProToolsAccess

    /** Eigene Sammlungen anlegen und öffnen (fertige Sammlungen sind für alle). */
    val customCollections: Boolean get() = hasProToolsAccess

    companion object {
        const val FREE_DAILY_CLEANUP_LIMIT = 15

        fun resolve(purchase: PurchaseState, rewards: RewardLedger, now: Instant): Capabilities {
            // Lifetime Pro gewinnt immer (auch während eines laufenden Tests).
            if (purchase == PurchaseState.PURCHASED) return Capabilities(Tier.LIFETIME_PRO, adsAllowed = false, rewardRemaining = null)
            rewards.active(now)?.let { w -> return Capabilities(Tier.TEMPORARY_PRO, adsAllowed = true, rewardRemaining = w.remaining(now)) }
            // Bei unbekanntem Kaufstatus keine Ad-Requests, bis der Status geklärt ist.
            return Capabilities(Tier.FREE, adsAllowed = purchase != PurchaseState.UNKNOWN, rewardRemaining = null)
        }
    }
}

/**
 * Begrenzte Arbeitseinheit (z. B. ein Duplikat-Scan), die bei Ablauf der Vorschau noch
 * abgeschlossen wird. Gespeicherte Entscheidungen bleiben immer erhalten.
 */
data class WorkUnitGrant(val id: UUID, val startedAt: Instant) {
    companion object {
        /** Startet nur, wenn die Fähigkeit jetzt vorhanden ist. */
        fun begin(requires: (Capabilities) -> Boolean, caps: Capabilities, now: Instant): WorkUnitGrant? =
            if (requires(caps)) WorkUnitGrant(UUID.randomUUID(), now) else null
    }
}
