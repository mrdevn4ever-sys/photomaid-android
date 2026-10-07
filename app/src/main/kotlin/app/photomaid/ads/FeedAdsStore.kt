package app.photomaid.ads

import app.photomaid.core.ads.FeedAdPolicy
import app.photomaid.core.ads.FeedAdState
import app.photomaid.core.model.LocalDay
import app.photomaid.data.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Persistenter Zähler für Werbekarten (Takt/Schonfrist/Tagesgrenze aus [FeedAdPolicy]). */
class FeedAdsStore(private val store: KeyValueStore) {
    var state: FeedAdState = FeedAdState()
        private set

    companion object { private const val KEY = "feedads.v1" }

    fun load() { state = runCatching { store.value<FeedAdState>(KEY) }.getOrNull() ?: FeedAdState() }

    private suspend fun save(s: FeedAdState) {
        state = s
        withContext(Dispatchers.IO) { runCatching { store.setValue(KEY, s) } }
    }

    suspend fun recordDecision() = save(FeedAdPolicy.onDecision(state))
    fun isDue(today: LocalDay, nowMs: Long = System.currentTimeMillis()) = FeedAdPolicy.isDue(state, today, nowMs)
    /** Bald fällig → rechtzeitig vorladen, damit die Karte ohne Wartezeit erscheint. */
    fun almostDue(today: LocalDay) = state.decisionsTotal + 2 >= FeedAdPolicy.WARMUP_DECISIONS && state.sinceLastAd + 2 >= FeedAdPolicy.INTERVAL &&
        FeedAdPolicy.shownToday(state, today) < FeedAdPolicy.DAILY_CAP
    fun summaryAllowed(today: LocalDay) = FeedAdPolicy.summaryAdAllowed(state, today)
    suspend fun recordShown(today: LocalDay) = save(FeedAdPolicy.onShown(state, today))
    suspend fun recordCardShown(today: LocalDay, nowMs: Long = System.currentTimeMillis()) = save(FeedAdPolicy.onCardShown(state, today, nowMs))
}
