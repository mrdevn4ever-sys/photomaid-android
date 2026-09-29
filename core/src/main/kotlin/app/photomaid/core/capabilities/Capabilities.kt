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
        val day = LocalDay.of(now, zone)
        if (windows.any { it.grantedDay == day }) throw RewardError.DailyUnlockUsed
        val w = RewardWindow(rewardID, now, day)
        return RewardLedger(windows + w) to w
    }

    fun active(now: Instant): RewardWindow? = windows.lastOrNull()?.takeIf { it.remaining(now) > Duration.ZERO }

    fun canOfferReward(now: Instant, zone: ZoneId, purchase: PurchaseState): Boolean {
        if (purchase == PurchaseState.PURCHASED) return false
        val day = LocalDay.of(now, zone)
        return windows.none { it.grantedDay == day }
    }
}

/** Zentrale Capability-Auflösung. Einzige Stelle, die Free/Pro/Rewarded unterscheidet. */
data class Capabilities(
    val tier: Tier,
    /** `null` = unbegrenzt. */
    val dailyCleanupLimit: Int?,
    val exactDuplicates: Boolean,
    val advancedFileTools: Boolean,
    /** Eigene Sammlungen anlegen und öffnen (fertige Sammlungen sind für alle). */
    val customCollections: Boolean,
    /** Werbung darf angeboten/angefragt werden (nie für Pro). */
    val adsAllowed: Boolean,
    val rewardRemaining: Duration?,
) {
    enum class Tier { FREE, REWARDED_PREVIEW, PRO }

    companion object {
        const val FREE_DAILY_CLEANUP_LIMIT = 15

        fun resolve(purchase: PurchaseState, rewards: RewardLedger, now: Instant): Capabilities {
            if (purchase == PurchaseState.PURCHASED) {
                return Capabilities(Tier.PRO, null, exactDuplicates = true, advancedFileTools = true, customCollections = true, adsAllowed = false, rewardRemaining = null)
            }
            rewards.active(now)?.let { w ->
                return Capabilities(
                    Tier.REWARDED_PREVIEW, FREE_DAILY_CLEANUP_LIMIT, exactDuplicates = true, advancedFileTools = true,
                    customCollections = true, adsAllowed = true, rewardRemaining = w.remaining(now),
                )
            }
            return Capabilities(
                Tier.FREE, FREE_DAILY_CLEANUP_LIMIT, exactDuplicates = false, advancedFileTools = false,
                customCollections = false,
                // Bei unbekanntem Kaufstatus keine Ad-Requests, bis der Status geklärt ist.
                adsAllowed = purchase != PurchaseState.UNKNOWN, rewardRemaining = null,
            )
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
