package app.photomaid

import android.app.Activity
import android.content.Context
import app.photomaid.ads.EntitlementCenter
import app.photomaid.ads.UnconfiguredRewardedAdGateway
import app.photomaid.ads.RewardedAdGateway
import app.photomaid.billing.PlayBillingGateway
import app.photomaid.billing.PurchaseGateway
import app.photomaid.billing.UnconfiguredPurchaseGateway
import app.photomaid.core.capabilities.Capabilities
import app.photomaid.core.cleanup.AllowanceSnapshot
import app.photomaid.core.cleanup.CleanupError
import app.photomaid.core.cleanup.CleanupOperationKind
import app.photomaid.core.cleanup.ExistenceProbe
import app.photomaid.core.cleanup.OperationIntent
import app.photomaid.core.cleanup.OperationOutcome
import app.photomaid.core.cleanup.OperationState
import app.photomaid.core.decisions.CommitOutcome
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.decisions.DecisionError
import app.photomaid.core.decisions.DecisionEvent
import app.photomaid.core.decisions.DecisionLedger
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.decisions.LedgerPersistence
import app.photomaid.core.decisions.LedgerState
import app.photomaid.core.later.LaterStatus
import app.photomaid.core.later.ReviewQueues
import app.photomaid.core.model.ItemProvider
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.core.model.SystemClock
import app.photomaid.data.KeyValueStore
import app.photomaid.data.PhotoMaidDatabase
import app.photomaid.data.RoomLedgerPersistence
import app.photomaid.feature.collections.CollectionsStore
import app.photomaid.files.ConnectedSourceStore
import app.photomaid.media.MediaLibrary
import app.photomaid.media.PhotoLibraryService
import app.photomaid.notifications.ReminderScheduler
import app.photomaid.playback.FeedPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

sealed interface CleanupRunResult {
    data class Finished(val intent: OperationIntent) : CleanupRunResult
    data class Rejected(val error: CleanupError) : CleanupRunResult
    data object NeedsReconciliation : CleanupRunResult
}

/** Abgeleitete Lesemodelle (werden nach jeder Mutation aus dem Ledger neu berechnet). */
data class LedgerReadModel(
    val basket: List<ItemRecord> = emptyList(),
    val laterRecords: List<ItemRecord> = emptyList(),
    val events: List<DecisionEvent> = emptyList(),
    val allowance: AllowanceSnapshot? = null,
    val canUndo: Boolean = false,
    val reconciliationNeeded: List<OperationIntent> = emptyList(),
)

/**
 * Zentrale App-Instanz (iOS `AppModel`): Abhängigkeiten und abgeleitete Lesemodelle.
 * Schreiboperationen laufen ausschließlich über den [DecisionLedger].
 */
class AppModel(
    val context: Context,
    val db: PhotoMaidDatabase,
    persistence: LedgerPersistence = RoomLedgerPersistence(db),
    val isQAFixtureMode: Boolean = false,
    purchaseOverride: PurchaseGateway? = null,
    mediaOverride: ((() -> Boolean) -> MediaLibrary)? = null,
    /** Tests: Werbeanbieter-Fakes. Produktion: AdMob, sofern IDs konfiguriert sind (sonst keine Werbung). */
    adsOverride: RewardedAdGateway? = null,
    feedAdsOverride: app.photomaid.ads.FeedAdSource? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = KeyValueStore(db)
    val ledger = DecisionLedger(persistence, SystemClock)
    private val _settings = MutableStateFlow(AppSettingsData())
    val settings: StateFlow<AppSettingsData> = _settings.asStateFlow()
    val photos: MediaLibrary = mediaOverride?.invoke { _settings.value.mediaPermissionAsked }
        ?: PhotoLibraryService(context) { _settings.value.mediaPermissionAsked }
    val sources = ConnectedSourceStore(context, store)
    val player = FeedPlayer(context)
    val reminders = ReminderScheduler(context) { _settings.value.notificationsOptIn }
    val purchases: PurchaseGateway = purchaseOverride ?: BuildConfig.PLAY_PRO_PRODUCT_ID.let { id ->
        if (id.isBlank()) UnconfiguredPurchaseGateway() else PlayBillingGateway(context, id, store)
    }
    val adSdk = app.photomaid.ads.AdMobSdk(context)
    private val useAdMob = adsOverride == null && feedAdsOverride == null && !isQAFixtureMode && adSdk.isConfigured
    val entitlements = EntitlementCenter(store, purchases,
        adsOverride ?: if (useAdMob) app.photomaid.ads.AdMobRewardedGateway(context, adSdk) else UnconfiguredRewardedAdGateway())
    /** Werbekarten im Feed (nur Free). */
    val feedAds: app.photomaid.ads.FeedAdSource = feedAdsOverride ?: if (useAdMob) app.photomaid.ads.AdMobFeedAds(context, adSdk) else app.photomaid.ads.NoFeedAds
    val feedAdCounter = app.photomaid.ads.FeedAdsStore(store)
    val collections = CollectionsStore(store)
    val quickAccess = app.photomaid.feature.quickaccess.QuickAccessStore(store)

    private val _read = MutableStateFlow(LedgerReadModel())
    val read: StateFlow<LedgerReadModel> = _read.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Offene Absichten nur einmal je Prozessstart klären (nicht bei jedem Resume, s. PORTING_MATRIX iOS-Befund 4). */
    private var recoveredThisProcess = false
    @Volatile private var cleanupInFlight = false

    companion object { private const val SETTINGS_KEY = "app.settings" }

    /** Blockierend laden (Boot, abseits des Main-Threads). */
    fun loadBlocking() {
        _settings.value = store.value<AppSettingsData>(SETTINGS_KEY) ?: AppSettingsData()
        if (_settings.value.firstSeenDay == null) {
            _settings.value = _settings.value.copy(firstSeenDay = today)
            runCatching { store.setValue(SETTINGS_KEY, _settings.value) }
        }
        sources.load()
        collections.load()
        quickAccess.load()
        feedAdCounter.load()
        entitlements.load()
        (purchases as? PlayBillingGateway)?.loadCache()
        reminders.ensureChannel()
    }

    fun start() {
        scope.launch { ledger.stateFlow.collect { publishRead() } }
    }

    val capabilities: Capabilities get() = entitlements.capabilities
    val today: LocalDay get() = SystemClock.today

    fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    suspend fun updateSettings(change: (AppSettingsData) -> AppSettingsData) {
        val s = change(_settings.value)
        try {
            withContext(Dispatchers.IO) { store.setValue(SETTINGS_KEY, s) }
            _settings.value = s
        } catch (e: Exception) {
            _lastError.value = string(R.string.error_settings_not_saved)
        }
    }

    /** Start/Resume: Berechtigungen neu prüfen, offene Operationen klären, Lesemodelle laden. */
    suspend fun onForeground() {
        photos.refreshAccess()
        reminders.refresh()
        withContext(Dispatchers.IO) { runCatching { purchases.refresh() } }
        entitlements.tick()
        if (!recoveredThisProcess && !cleanupInFlight) {
            recoveredThisProcess = true
            recoverOperations()
        }
        reload()
    }

    suspend fun reload() { ledger.snapshotState(); publishRead() }

    private val readMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Lesemodell (Papierkorb, Später, Limit …) neu berechnen – NICHT auf dem Main-Thread (Filtern/Sortieren aller
     * Einträge bei jeder Entscheidung kostete Frames im Feed). Serialisiert und immer aus dem neuesten Ledger-Stand,
     * damit ein langsamerer älterer Lauf nie einen neueren überschreibt.
     */
    private suspend fun publishRead() = readMutex.withLock {
        val s = ledger.stateFlow.value
        val limit = capabilities.dailyCleanupLimit
        _read.value = withContext(Dispatchers.Default) { derive(s, limit) }
    }

    private fun derive(s: LedgerState, limit: Int?): LedgerReadModel {
        return LedgerReadModel(
            // Eine Datei, die über mehrere verbundene Ordner markiert wurde, zählt nur einmal (keine erfundenen Zahlen).
            basket = s.records.values.filter { it.disposition == Disposition.MARKED_FOR_CLEANUP }.sortedBy { it.item.description }
                .distinctBy { aliasOf(it.item) },
            laterRecords = s.records.values.filter { it.disposition == Disposition.LATER },
            events = s.events,
            allowance = ledger.allowance(s, limit),
            canUndo = s.undoStack.isNotEmpty(),
            reconciliationNeeded = s.intents.values.filter { it.state == OperationState.RECONCILIATION_REQUIRED },
        )
    }

    /** Schlüssel der tatsächlichen Ressource (Datei über mehrere Quellen = ein Schlüssel). */
    fun aliasOf(item: ItemReference): String = sources.aliasKey(item) ?: item.description

    /**
     * Noch zu prüfende Medien – dieselbe Regel wie der Feed ([ReviewQueues.normalQueue]).
     * Einzige Quelle für „Heute“ (Zahl, „Alles geprüft“) und die Zähler im Tab „Aufräumen“.
     */
    suspend fun pendingQueue(assets: List<app.photomaid.media.MediaAsset>): List<ItemReference> {
        val refs = assets.map { it.reference }
        return ReviewQueues.normalQueue(refs, ledger.records(refs), ReviewSessionID(), today)
    }

    val dueCount: Int
        get() = _read.value.laterRecords.count { ReviewQueues.laterStatus(it, today) is LaterStatus.Due }

    // MARK: Entscheidungen

    suspend fun apply(command: DecisionCommand, session: ReviewSessionID? = null): CommitOutcome =
        ledger.apply(command, session)

    suspend fun decide(item: ItemReference, action: DecisionAction) {
        try {
            val r = ledger.record(item)
            apply(DecisionCommand(item = item, expectedRevision = r.revision, action = action))
        } catch (e: Exception) {
            _lastError.value = string(R.string.error_change_not_saved)
        }
    }

    suspend fun undo(): ItemRecord? {
        try {
            return ledger.undo()
        } catch (e: DecisionError.UndoConflict) {
            _lastError.value = string(R.string.error_undo_conflict)
        } catch (e: DecisionError.ItemBusy) {
            _lastError.value = string(R.string.error_undo_busy)
        } catch (e: Exception) {
            _lastError.value = string(R.string.error_undo_generic)
        }
        reload()
        return null
    }

    fun clearError() { _lastError.value = null }
    fun reportError(message: String) { _lastError.value = message }

    // MARK: Bereinigung

    /**
     * Führt eine bestätigte Bereinigung für EINEN Provider durch.
     * Entdecken → Entscheiden → Papierkorb prüfen → Bestätigen → Provider-Operation → Abgleichen → Verlauf.
     */
    suspend fun runCleanup(items: List<ItemReference>, kind: CleanupOperationKind): CleanupRunResult {
        cleanupInFlight = true
        try {
            val limit = capabilities.dailyCleanupLimit
            val intent = try {
                ledger.prepareCleanup(items, kind, limit, aliasKeys = items.associateWith(::aliasOf)).also { ledger.markSubmitted(it.id) }
            } catch (e: CleanupError) {
                return CleanupRunResult.Rejected(e)
            } catch (e: Exception) {
                return CleanupRunResult.Rejected(CleanupError.PersistenceFailed(e.toString()))
            }
            player.stop()
            val outcome: OperationOutcome = when (kind) {
                CleanupOperationKind.PHOTO_LIBRARY_DELETE -> photos.trash(items.map { it.localIdentifier })
                    ?: return CleanupRunResult.NeedsReconciliation
                CleanupOperationKind.FILE_DELETE_PERMANENTLY -> withContext(Dispatchers.IO) { sources.deletePermanently(items) }
                // Nicht angeboten: kein universeller Datei-Papierkorb (siehe PORTING_MATRIX).
                CleanupOperationKind.FILE_MOVE_TO_TRASH -> OperationOutcome.Failed("trash-not-supported")
            }
            return try {
                val done = ledger.reconcile(intent.id, outcome)
                unmarkAliasesOf(done.confirmedRemoved)
                CleanupRunResult.Finished(done)
            } catch (e: Exception) {
                // Systemoperation lief, DB-Abgleich scheiterte: beim nächsten Start reconciliationRequired.
                CleanupRunResult.NeedsReconciliation
            } finally {
                photos.bumpChangeToken()
            }
        } finally {
            cleanupInFlight = false
        }
    }

    /**
     * Ältere Doppel-Markierungen derselben Datei (anderer verbundener Ordner) verlassen den Papierkorb, sobald die Datei
     * entfernt ist – sonst tauchte sie dort danach erneut auf. Nur „Zurücklegen“, nie eine weitere Löschung.
     */
    private suspend fun unmarkAliasesOf(removed: Set<ItemReference>) {
        if (removed.none { it.provider != ItemProvider.PhotoLibrary }) return
        val gone = removed.map(::aliasOf).toSet()
        for (r in ledger.stateFlow.value.records.values) {
            if (r.disposition == Disposition.MARKED_FOR_CLEANUP && r.item !in removed && aliasOf(r.item) in gone)
                runCatching { apply(DecisionCommand(item = r.item, expectedRevision = r.revision, action = DecisionAction.Unmark)) }
        }
    }

    private suspend fun recoverOperations() {
        val open = runCatching { ledger.recoverAfterLaunch() }.getOrElse { return }
        for (intent in open.filter { it.state == OperationState.RECONCILIATION_REQUIRED }) {
            val refs = intent.items.keys.toList()
            val probes = withContext(Dispatchers.IO) {
                photos.probe(refs.filter { it.provider == ItemProvider.PhotoLibrary }.map { it.localIdentifier }) +
                    sources.probe(refs.filter { it.provider != ItemProvider.PhotoLibrary })
            }
            runCatching { ledger.resolveReconciliation(intent.id, probes) }
        }
    }

    suspend fun purchasePro(activity: Activity) = purchases.purchasePro(activity)
}
