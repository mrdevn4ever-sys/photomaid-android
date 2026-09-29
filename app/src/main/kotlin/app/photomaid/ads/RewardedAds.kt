package app.photomaid.ads

import app.photomaid.billing.PurchaseGateway
import app.photomaid.core.capabilities.Capabilities
import app.photomaid.core.capabilities.RewardLedger
import app.photomaid.data.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Freiwillige Rewarded-Werbung. Belohnung NUR nach tatsächlichem SDK-Reward-Callback. */
sealed interface RewardedAdResult {
    data class Rewarded(val rewardID: String) : RewardedAdResult
    data object DismissedWithoutReward : RewardedAdResult
    data object NoFill : RewardedAdResult
    data class Failed(val reason: String) : RewardedAdResult
    data object NotConfigured : RewardedAdResult
}

interface RewardedAdGateway {
    val isConfigured: Boolean
    suspend fun presentRewardedAd(): RewardedAdResult
}

/**
 * Werbeanbieter ist (wie auf iOS) noch nicht festgelegt – Release-Blocker. Die Oberfläche zeigt die
 * Vorschau dann als nicht verfügbar an. Kein Werbe-SDK im Build, keine Ad-Requests.
 */
class UnconfiguredRewardedAdGateway : RewardedAdGateway {
    override val isConfigured = false
    override suspend fun presentRewardedAd() = RewardedAdResult.NotConfigured
}

/** Was der Pro-Screen zum 30-Minuten-Test zeigt. */
enum class RewardOffer {
    /** Lifetime Pro oder kein Werbeanbieter konfiguriert: nichts anbieten (keine tote CTA). */
    HIDDEN,
    AVAILABLE,
    /** Test läuft gerade. */
    ACTIVE,
    /** Heute schon genutzt – morgen wieder. */
    USED_TODAY,
    /** Kaufstatus noch unklar: vorerst keine Werbung. */
    NOT_NOW,
}

/** Persistente Reward-Buchführung + Capability-Auflösung (iOS `EntitlementCenter`). */
class EntitlementCenter(
    private val store: KeyValueStore,
    val purchases: PurchaseGateway,
    val ads: RewardedAdGateway,
) {
    private val _rewards = MutableStateFlow(RewardLedger())
    val rewards: StateFlow<RewardLedger> = _rewards.asStateFlow()

    /** Beobachtbarer Zeitstempel: alle 30 s und bei Resume erhöht (Ablauf der Vorschau sichtbar). */
    private val _now = MutableStateFlow(Instant.now())
    val now: StateFlow<Instant> = _now.asStateFlow()

    companion object { private const val KEY = "rewards.ledger" }

    fun load() { store.value<RewardLedger>(KEY)?.let { _rewards.value = it } }

    /** Immer mit der aktuellen Uhrzeit aufgelöst. Einzige Stelle, an der Screens Kaufstatus „sehen“. */
    val capabilities: Capabilities get() = Capabilities.resolve(purchases.state.value, _rewards.value, Instant.now())

    fun tick() { _now.value = Instant.now() }

    val canOfferReward: Boolean
        get() = ads.isConfigured && capabilities.adsAllowed &&
            _rewards.value.canOfferReward(Instant.now(), ZoneId.systemDefault(), purchases.state.value)

    val rewardOffer: RewardOffer
        get() {
            val caps = capabilities
            if (caps.tier == Capabilities.Tier.LIFETIME_PRO || !ads.isConfigured) return RewardOffer.HIDDEN
            if (caps.tier == Capabilities.Tier.TEMPORARY_PRO) return RewardOffer.ACTIVE
            if (_rewards.value.usedToday(Instant.now(), ZoneId.systemDefault())) return RewardOffer.USED_TODAY
            if (!caps.adsAllowed) return RewardOffer.NOT_NOW
            return RewardOffer.AVAILABLE
        }

    /**
     * Zeigt eine freiwillige Anzeige – nur nach bewusster Nutzeraktion. Freischaltung ausschließlich nach dem
     * Reward-Callback des Anbieters (idempotent je `rewardID`); Abbruch, No-Fill oder Fehler verbrauchen den Tag nicht.
     */
    suspend fun watchRewardedAd(pausePlayback: () -> Unit): RewardedAdResult {
        if (!canOfferReward) return RewardedAdResult.NotConfigured
        pausePlayback()
        val result = ads.presentRewardedAd()
        tick()
        if (result !is RewardedAdResult.Rewarded) return result
        return try {
            val (ledger, _) = _rewards.value.granting(result.rewardID, Instant.now(), ZoneId.systemDefault(), purchases.state.value)
            // Room erlaubt keinen Main-Thread-Zugriff; erst nach erfolgreichem Speichern gilt der Reward.
            withContext(Dispatchers.IO) { store.setValue(KEY, ledger) }
            _rewards.value = ledger
            tick()
            result
        } catch (e: Exception) {
            // Nicht gespeichert oder nicht erlaubt (Kauf/zweiter Reward): keine Erfolgsmeldung.
            RewardedAdResult.Failed("grant:${e.javaClass.simpleName}")
        }
    }
}
