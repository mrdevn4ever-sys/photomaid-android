package app.photomaid.ads

import app.photomaid.billing.PurchaseGateway
import app.photomaid.core.capabilities.Capabilities
import app.photomaid.core.capabilities.RewardLedger
import app.photomaid.data.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
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

    /** Zeigt eine freiwillige Anzeige; schaltet nur nach Reward-Callback frei (idempotent). */
    suspend fun watchRewardedAd(pausePlayback: () -> Unit): RewardedAdResult {
        if (!canOfferReward) return RewardedAdResult.NotConfigured
        pausePlayback()
        val result = ads.presentRewardedAd()
        if (result is RewardedAdResult.Rewarded) {
            runCatching {
                val (ledger, _) = _rewards.value.granting(result.rewardID, Instant.now(), ZoneId.systemDefault(), purchases.state.value)
                store.setValue(KEY, ledger)
                _rewards.value = ledger
            }
        }
        tick()
        return result
    }
}
