package app.photomaid.core.decisions

import app.photomaid.core.cleanup.DayUsage
import app.photomaid.core.cleanup.OperationIntent
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.OperationID

/**
 * Gesamter lokaler Zustand, der transaktional gemeinsam gespeichert wird:
 * Entscheidungen, Verlauf, Undo-Historie, Operation-Journal und Tageskontingent.
 */
data class LedgerState(
    val records: Map<ItemReference, ItemRecord> = emptyMap(),
    val events: List<DecisionEvent> = emptyList(),
    val undoStack: List<UndoEntry> = emptyList(),
    val intents: Map<OperationID, OperationIntent> = emptyMap(),
    val usage: Map<LocalDay, DayUsage> = emptyMap(),
) {
    fun merging(change: LedgerChange): LedgerState = LedgerState(
        records = records + change.records.associateBy { it.item },
        events = events + change.events,
        undoStack = change.undoStack ?: undoStack,
        intents = intents + change.intents.associateBy { it.id },
        usage = usage + change.usage,
    )
}

/** Eine atomare Änderung. Die Persistenz schreibt sie ganz oder gar nicht. */
data class LedgerChange(
    val records: List<ItemRecord> = emptyList(),
    val events: List<DecisionEvent> = emptyList(),
    /** Vollständige neue Undo-Historie, wenn geändert. */
    val undoStack: List<UndoEntry>? = null,
    val intents: List<OperationIntent> = emptyList(),
    val usage: Map<LocalDay, DayUsage> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = records.isEmpty() && events.isEmpty() && undoStack == null && intents.isEmpty() && usage.isEmpty()
}

/**
 * Einzige Persistenzquelle für Entscheidungen (Produktion: Room, eine Transaktion je Change).
 *
 * `commit` ist bewusst blockierend/synchron: Der Ledger darf zwischen Berechnung und
 * Speicherung nicht suspendieren, sonst könnten sich Befehle überholen.
 */
interface LedgerPersistence {
    fun load(): LedgerState
    fun commit(change: LedgerChange)
}

/** Speicher ohne Datei. Nur für Tests, Previews und QA-Builds. */
class InMemoryLedgerPersistence(initial: LedgerState = LedgerState()) : LedgerPersistence {
    private val lock = Any()
    private var stored = initial

    /** Testhilfe: nächste `commit`-Aufrufe schlagen fehl. */
    @Volatile var failNextCommits = 0
    var commitCount = 0
        private set

    override fun load(): LedgerState = synchronized(lock) { stored }

    override fun commit(change: LedgerChange) = synchronized(lock) {
        if (failNextCommits > 0) {
            failNextCommits -= 1
            throw IllegalStateException("injected")
        }
        stored = stored.merging(change)
        commitCount += 1
    }

    val storedState: LedgerState get() = synchronized(lock) { stored }
}
