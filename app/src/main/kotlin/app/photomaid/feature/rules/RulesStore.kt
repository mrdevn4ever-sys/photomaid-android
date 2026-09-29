package app.photomaid.feature.rules

import app.photomaid.AppModel
import app.photomaid.core.model.InstantSerializer
import app.photomaid.core.rules.InventoryItem
import app.photomaid.core.rules.ReviewRule
import app.photomaid.core.rules.ReviewRuleID
import app.photomaid.core.rules.ReviewTask
import app.photomaid.core.rules.RuleEvaluator
import app.photomaid.core.rules.RuleSource
import app.photomaid.core.rules.RuleTrigger
import app.photomaid.data.KeyValueStore
import app.photomaid.media.MediaCategory
import app.photomaid.notifications.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/** Lokale deklarative Regeln → Review-Aufgaben. Niemals automatische Löschung (iOS `RulesStore`). */
class RulesStore(private val store: KeyValueStore) {
    @Serializable
    private data class Persisted(
        val rules: List<ReviewRule> = emptyList(),
        val tasks: List<ReviewTask> = emptyList(),
        val lastEvaluatedAt: Map<ReviewRuleID, @Serializable(InstantSerializer::class) Instant> = emptyMap(),
        val testScanUsed: Set<ReviewRuleID> = emptySet(),
        val firstSeen: Map<String, @Serializable(InstantSerializer::class) Instant> = emptyMap(),
    )

    data class State(
        val rules: List<ReviewRule> = emptyList(),
        val tasks: List<ReviewTask> = emptyList(),
        val lastEvaluatedAt: Map<ReviewRuleID, Instant> = emptyMap(),
        val testScanUsed: Set<ReviewRuleID> = emptySet(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var firstSeen = mutableMapOf<String, Instant>()

    companion object { private const val KEY = "rules.state" }

    fun load() {
        val p = store.value<Persisted>(KEY) ?: Persisted()
        _state.value = State(p.rules, p.tasks, p.lastEvaluatedAt, p.testScanUsed)
        firstSeen = p.firstSeen.toMutableMap()
    }

    private suspend fun save(s: State) {
        _state.value = s
        val p = Persisted(s.rules, s.tasks, s.lastEvaluatedAt, s.testScanUsed, firstSeen.toMap())
        withContext(Dispatchers.IO) { runCatching { store.setValue(KEY, p) } }
    }

    suspend fun upsert(rule: ReviewRule, reminders: ReminderScheduler) {
        val s = _state.value
        val rules = if (s.rules.any { it.id == rule.id }) s.rules.map { if (it.id == rule.id) rule else it } else s.rules + rule
        save(s.copy(rules = rules))
        withContext(Dispatchers.IO) {
            reminders.removeRuleReminder(rule.id)
            val t = rule.trigger
            if (rule.isEnabled && rule.notify && t is RuleTrigger.Weekly) reminders.scheduleRuleReminder(rule.id, t.weekday)
        }
    }

    /** Regel entfernen: zugehörige Reminder und offene Aufgaben entfernen. */
    suspend fun delete(id: ReviewRuleID, reminders: ReminderScheduler) {
        val s = _state.value
        save(s.copy(rules = s.rules.filter { it.id != id }, tasks = s.tasks.filter { it.ruleID != id }, lastEvaluatedAt = s.lastEvaluatedAt - id))
        withContext(Dispatchers.IO) { reminders.removeRuleReminder(id) }
    }

    suspend fun dismissTask(id: String) {
        val s = _state.value
        save(s.copy(tasks = s.tasks.filter { it.id != id }))
    }

    /** Kernprüfung bei Start/Resume. Nur mit dauerhaft aktiven Routinen (Pro). */
    suspend fun evaluateOnResume(app: AppModel) {
        if (!app.capabilities.persistentRoutines) return
        for (rule in _state.value.rules.filter { it.isEnabled }) run(rule, app)
    }

    /** „Jetzt ausführen“. In der Rewarded-Vorschau genau ein Testscan je Regel. */
    suspend fun runNow(rule: ReviewRule, app: AppModel): Boolean {
        val caps = app.capabilities
        if (!caps.persistentRoutines) {
            if (!caps.oneTimeRuleTestScan || rule.id in _state.value.testScanUsed) return false
            save(_state.value.copy(testScanUsed = _state.value.testScanUsed + rule.id))
        }
        run(rule, app, force = true)
        return true
    }

    private suspend fun run(rule: ReviewRule, app: AppModel, force: Boolean = false) {
        val inventory = inventory(rule.source, app)
        val records = app.ledger.records(inventory.map { it.item })
        val s = _state.value
        val existing = if (force) emptySet() else s.tasks.map { it.id }.toSet()
        val now = Instant.now()
        val task = RuleEvaluator.evaluate(rule, inventory, records, existing, s.lastEvaluatedAt[rule.id], now, ZoneId.systemDefault())
        val tasks = if (task != null) s.tasks.filter { it.id != task.id } + task else s.tasks
        save(_state.value.copy(tasks = tasks, lastEvaluatedAt = _state.value.lastEvaluatedAt + (rule.id to now)))
    }

    /** Tatsächlicher Scan der Quelle (keine erfundenen Fundzahlen). */
    private suspend fun inventory(source: RuleSource, app: AppModel): List<InventoryItem> = withContext(Dispatchers.IO) {
        val now = Instant.now()
        fun seen(key: String): Instant = firstSeen.getOrPut(key) { now }
        when (source) {
            RuleSource.Screenshots, RuleSource.Photos, RuleSource.Videos -> {
                val cat = when (source) {
                    RuleSource.Screenshots -> MediaCategory.SCREENSHOTS
                    RuleSource.Videos -> MediaCategory.VIDEOS
                    else -> MediaCategory.PHOTOS
                }
                // Android: MediaStore liefert öffentliche Größen (SIZE); unbekannt bleibt unbekannt.
                app.photos.assets(cat).map { InventoryItem(it.reference, source, it.creationDate, it.size, seen(it.id)) }
            }
            is RuleSource.ConnectedSource -> runCatching { app.sources.scan(source.id) }.getOrDefault(emptyList()).map {
                InventoryItem(it.reference, source, it.modified, it.size, seen(it.reference.description))
            }
        }
    }
}
