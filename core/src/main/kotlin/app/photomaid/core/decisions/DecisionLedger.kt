package app.photomaid.core.decisions

import app.photomaid.core.cleanup.AllowanceSnapshot
import app.photomaid.core.cleanup.CleanupError
import app.photomaid.core.cleanup.CleanupOperationKind
import app.photomaid.core.cleanup.DayUsage
import app.photomaid.core.cleanup.ExistenceProbe
import app.photomaid.core.cleanup.OperationIntent
import app.photomaid.core.cleanup.OperationOutcome
import app.photomaid.core.cleanup.OperationState
import app.photomaid.core.model.Clock
import app.photomaid.core.model.CommandID
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.OperationID
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.core.model.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reine Übergangsregeln für Entscheidungen. Keine Seiteneffekte. */
object DecisionRules {
    fun transition(record: ItemRecord, action: DecisionAction): Result<DecisionSnapshot> {
        val from = record.disposition
        if (from == Disposition.REMOVED) return Result.failure(DecisionError.InvalidTransition(from, name(action)))
        return when (action) {
            DecisionAction.Keep -> Result.success(DecisionSnapshot(Disposition.KEPT))
            DecisionAction.MarkForCleanup -> Result.success(DecisionSnapshot(Disposition.MARKED_FOR_CLEANUP))
            is DecisionAction.Later -> {
                // Ein vorhandener Termin bleibt bestehen (fälliges Item erneut ohne Datum auf Später).
                val due = if (from == Disposition.LATER) record.dueDay else null
                Result.success(DecisionSnapshot(Disposition.LATER, due, action.session))
            }
            is DecisionAction.Schedule ->
                if (from != Disposition.LATER) Result.failure(DecisionError.InvalidTransition(from, name(action)))
                else Result.success(DecisionSnapshot(Disposition.LATER, action.day, record.skippedInSession))
            DecisionAction.RemoveDeferral ->
                if (from != Disposition.LATER) Result.failure(DecisionError.InvalidTransition(from, name(action)))
                else Result.success(DecisionSnapshot(Disposition.UNDECIDED))
            DecisionAction.Unmark ->
                if (from != Disposition.MARKED_FOR_CLEANUP) Result.failure(DecisionError.InvalidTransition(from, name(action)))
                else Result.success(DecisionSnapshot(Disposition.UNDECIDED))
        }
    }

    fun historyKind(from: DecisionSnapshot, action: DecisionAction): HistoryKind = when (action) {
        DecisionAction.Keep, DecisionAction.MarkForCleanup, is DecisionAction.Later ->
            if (from.disposition == Disposition.UNDECIDED) HistoryKind.DECISION else HistoryKind.CORRECTION
        is DecisionAction.Schedule -> HistoryKind.SCHEDULE_CHANGED
        DecisionAction.RemoveDeferral -> HistoryKind.DEFERRAL_REMOVED
        DecisionAction.Unmark -> HistoryKind.UNMARKED
    }

    fun name(action: DecisionAction): String = when (action) {
        DecisionAction.Keep -> "keep"
        DecisionAction.MarkForCleanup -> "markForCleanup"
        is DecisionAction.Later -> "later"
        is DecisionAction.Schedule -> "schedule"
        DecisionAction.RemoveDeferral -> "removeDeferral"
        DecisionAction.Unmark -> "unmark"
    }
}

/**
 * Mutex-geschützte Command-Instanz für alle zustandsändernden Aktionen (iOS: `actor DecisionLedger`).
 *
 * Jede Methode berechnet eine [LedgerChange], schreibt sie synchron über [LedgerPersistence]
 * und übernimmt sie erst nach erfolgreichem Schreiben in den Speicherzustand.
 * Bei Persistenzfehler bleibt der Zustand unverändert und der Fehler wird geworfen.
 */
class DecisionLedger(
    private val persistence: LedgerPersistence,
    private val clock: Clock = SystemClock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object { const val UNDO_LIMIT = 200 }

    private val mutex = Mutex()
    private var state: LedgerState = persistence.load()
    private val processedCommands: MutableMap<CommandID, ItemReference> =
        state.events.mapNotNull { e -> e.commandID?.let { it to e.item } }.toMap().toMutableMap()
    private val _state = MutableStateFlow(state)

    /** Beobachtbarer, bereits persistierter Zustand (Lesemodelle der UI). */
    val stateFlow: StateFlow<LedgerState> = _state.asStateFlow()

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(io) { block() } }

    // MARK: Abfragen

    private fun recordUnlocked(item: ItemReference) = state.records[item] ?: ItemRecord(item)

    suspend fun record(item: ItemReference): ItemRecord = locked { recordUnlocked(item) }

    suspend fun records(items: List<ItemReference>): Map<ItemReference, ItemRecord> =
        locked { items.associateWith { recordUnlocked(it) } }

    suspend fun allRecords(): List<ItemRecord> = locked { state.records.values.toList() }

    suspend fun events(): List<DecisionEvent> = locked { state.events }

    suspend fun canUndo(): Boolean = locked { state.undoStack.isNotEmpty() }

    suspend fun openIntents(): List<OperationIntent> = locked { openIntentsUnlocked() }

    private fun openIntentsUnlocked() =
        state.intents.values.filter { !it.state.isTerminal }.sortedBy { it.createdAt }

    suspend fun intent(id: OperationID): OperationIntent? = locked { state.intents[id] }

    suspend fun snapshotState(): LedgerState = locked { state }

    /** Items, die aktuell im PhotoMaid-Papierkorb liegen. */
    suspend fun basket(): List<ItemRecord> = locked {
        state.records.values.filter { it.disposition == Disposition.MARKED_FOR_CLEANUP }.sortedBy { it.item.description }
    }

    suspend fun allowance(limit: Int?): AllowanceSnapshot = locked { allowanceUnlocked(state, limit) }

    fun allowance(snapshot: LedgerState, limit: Int?): AllowanceSnapshot = allowanceUnlocked(snapshot, limit)

    private fun allowanceUnlocked(s: LedgerState, limit: Int?): AllowanceSnapshot {
        val day = clock.today
        val u = s.usage[day] ?: DayUsage()
        return AllowanceSnapshot(day, limit, u.confirmed, u.reserved)
    }

    // MARK: Entscheidungen

    suspend fun apply(command: DecisionCommand, sessionID: ReviewSessionID? = null): CommitOutcome = locked {
        val seen = processedCommands[command.id]
        if (seen != null && seen == command.item) return@locked CommitOutcome.Duplicate(recordUnlocked(command.item))
        val current = recordUnlocked(command.item)
        if (current.revision != command.expectedRevision) {
            throw DecisionError.StaleRevision(command.expectedRevision, current.revision)
        }
        if (isBusy(command.item)) throw DecisionError.ItemBusy
        val target = DecisionRules.transition(current, command.action).getOrThrow()
        if (target == current.snapshot) return@locked CommitOutcome.Unchanged(current)

        val next = current.applying(target).copy(revision = current.revision + 1)
        val event = DecisionEvent(
            commandID = command.id, item = command.item,
            kind = DecisionRules.historyKind(current.snapshot, command.action),
            from = current.snapshot, to = target, revision = next.revision,
            at = clock.now, sessionID = sessionID,
        )
        val undo = state.undoStack.toMutableList()
        val amendedIdx = command.amends?.let { a ->
            undo.indexOfLast { it.commandID == a && it.item == command.item }
        } ?: -1
        if (amendedIdx >= 0 && undo[amendedIdx].afterRevision == current.revision) {
            undo[amendedIdx] = undo[amendedIdx].copy(afterRevision = next.revision)
        } else {
            undo.add(UndoEntry(command.id, command.item, current.snapshot, current.revision, next.revision))
            if (undo.size > UNDO_LIMIT) repeat(undo.size - UNDO_LIMIT) { undo.removeAt(0) }
        }
        persist(LedgerChange(records = listOf(next), events = listOf(event), undoStack = undo))
        processedCommands[command.id] = command.item
        CommitOutcome.Applied(next)
    }

    /** Nimmt den jüngsten Befehl exakt zurück. Überschreibt keine neuere Entscheidung. */
    suspend fun undo(): ItemRecord = locked {
        val entry = state.undoStack.lastOrNull() ?: throw DecisionError.NothingToUndo
        val undo = state.undoStack.dropLast(1).toMutableList()
        val current = recordUnlocked(entry.item)
        // Laufende Systemoperation: Eintrag bleibt erhalten, Undo ist nur gerade nicht möglich.
        if (isBusy(entry.item)) throw DecisionError.ItemBusy
        if (current.revision != entry.afterRevision) {
            // Nicht mehr anwendbar (neuere Entscheidung oder Bereinigung): Eintrag verwerfen.
            persist(LedgerChange(undoStack = undo))
            throw DecisionError.UndoConflict
        }
        val next = current.applying(entry.before).copy(revision = current.revision + 1)
        // Der vorherige Befehl desselben Items bleibt rücknehmbar: sein Zielzustand ist wiederhergestellt.
        val idx = undo.indexOfLast { it.item == entry.item }
        if (idx >= 0 && undo[idx].afterRevision == entry.beforeRevision) {
            undo[idx] = undo[idx].copy(afterRevision = next.revision)
        }
        persist(
            LedgerChange(
                records = listOf(next), undoStack = undo,
                events = listOf(
                    DecisionEvent(
                        commandID = null, item = entry.item, kind = HistoryKind.UNDO,
                        from = current.snapshot, to = entry.before, revision = next.revision,
                        at = clock.now, sessionID = null,
                    ),
                ),
            ),
        )
        next
    }

    // MARK: Bereinigung (Operation-Journal + Tageskontingent)

    /**
     * Schreibt die Absicht dauerhaft und reserviert Tageskapazität atomar.
     * @param dailyLimit `null` = unbegrenzt (Pro).
     * @param aliasKeys gleiche Ressource über mehrere Quellen; Konflikte werden abgelehnt.
     */
    suspend fun prepareCleanup(
        items: List<ItemReference>,
        kind: CleanupOperationKind,
        dailyLimit: Int?,
        aliasKeys: Map<ItemReference, String> = emptyMap(),
    ): OperationIntent = locked {
        val unique = items.toSet().sortedBy { it.description }
        if (unique.isEmpty()) throw CleanupError.EmptySelection
        val seenAliases = mutableMapOf<String, ItemReference>()
        val revisions = linkedMapOf<ItemReference, Int>()
        for (item in unique) {
            val r = recordUnlocked(item)
            if (r.disposition != Disposition.MARKED_FOR_CLEANUP) throw CleanupError.NotMarked(item)
            if (isBusy(item)) throw CleanupError.ItemBusy(item)
            aliasKeys[item]?.let { key ->
                if (seenAliases[key] != null) throw CleanupError.AliasConflict(item)
                seenAliases[key] = item
            }
            revisions[item] = r.revision
        }
        val day = clock.today
        val usage = state.usage[day] ?: DayUsage()
        var reserved = 0
        if (dailyLimit != null) {
            val available = maxOf(0, dailyLimit - usage.confirmed - usage.reserved)
            if (unique.size > available) throw CleanupError.AllowanceExceeded(unique.size, available)
            reserved = unique.size
        }
        val intent = OperationIntent(
            id = OperationID(), kind = kind, items = revisions, allowanceDay = day,
            reservedCount = reserved, state = OperationState.PREPARED, createdAt = clock.now,
        )
        val newUsage = usage.copy(reservations = usage.reservations + (intent.id to reserved))
        persistCleanup(LedgerChange(intents = listOf(intent), usage = mapOf(day to newUsage)))
        intent
    }

    /** Muss unmittelbar vor dem Start der Systemoperation gespeichert werden. */
    suspend fun markSubmitted(id: OperationID): Unit = locked {
        val intent = state.intents[id] ?: throw CleanupError.UnknownOperation
        if (intent.state != OperationState.PREPARED) throw CleanupError.InvalidState(intent.state)
        persistCleanup(LedgerChange(intents = listOf(intent.copy(state = OperationState.SUBMITTED, updatedAt = clock.now))))
    }

    /** Gleicht das Ergebnis genau einmal ab. Wiederholte Callbacks sind wirkungslos. */
    suspend fun reconcile(id: OperationID, outcome: OperationOutcome): OperationIntent = locked {
        val intent = state.intents[id] ?: throw CleanupError.UnknownOperation
        if (intent.state.isTerminal) return@locked intent
        val all = intent.items.keys
        val removed: Set<ItemReference> = when (outcome) {
            OperationOutcome.Succeeded -> all
            is OperationOutcome.Partial -> outcome.removed.intersect(all)
            OperationOutcome.Cancelled, is OperationOutcome.Failed -> emptySet()
        }
        val final = when (outcome) {
            OperationOutcome.Cancelled -> OperationState.CANCELLED
            is OperationOutcome.Failed -> if (removed.isEmpty()) OperationState.FAILED else OperationState.PARTIALLY_SUCCEEDED
            OperationOutcome.Succeeded, is OperationOutcome.Partial ->
                if (removed == all) OperationState.SUCCEEDED
                else if (removed.isEmpty()) OperationState.FAILED else OperationState.PARTIALLY_SUCCEEDED
        }
        finalize(intent, removed, final, HistoryKind.CLEANUP_CONFIRMED)
    }

    /**
     * Nach App-Start: offene Absichten klären. Nie automatisch erneut löschen.
     * - `PREPARED` → abgebrochen, Kapazität frei.
     * - `SUBMITTED` → `RECONCILIATION_REQUIRED`, Kapazität bleibt reserviert.
     */
    suspend fun recoverAfterLaunch(): List<OperationIntent> = locked {
        val result = mutableListOf<OperationIntent>()
        for (intent in openIntentsUnlocked()) {
            when (intent.state) {
                OperationState.PREPARED ->
                    finalize(intent, emptySet(), OperationState.CANCELLED, HistoryKind.CLEANUP_NOT_PERFORMED)
                OperationState.SUBMITTED -> {
                    val i = intent.copy(state = OperationState.RECONCILIATION_REQUIRED, updatedAt = clock.now)
                    persistCleanup(LedgerChange(intents = listOf(i)))
                    result.add(i)
                }
                OperationState.RECONCILIATION_REQUIRED -> result.add(intent)
                else -> Unit
            }
        }
        result
    }

    /**
     * Abgleich eines unklaren Ergebnisses über Existenzprüfungen.
     * Fehlender Zugriff ([ExistenceProbe.UNKNOWN]) ist kein Löschbeweis: dann bleibt der Status.
     */
    suspend fun resolveReconciliation(id: OperationID, probes: Map<ItemReference, ExistenceProbe>): OperationIntent = locked {
        val intent = state.intents[id] ?: throw CleanupError.UnknownOperation
        if (intent.state != OperationState.RECONCILIATION_REQUIRED) throw CleanupError.InvalidState(intent.state)
        val missing = mutableSetOf<ItemReference>()
        for (item in intent.items.keys) {
            when (probes[item] ?: ExistenceProbe.UNKNOWN) {
                ExistenceProbe.EXISTS -> Unit
                ExistenceProbe.MISSING_WITH_FULL_ACCESS -> missing.add(item)
                ExistenceProbe.UNKNOWN -> return@locked intent
            }
        }
        val final = if (missing.isEmpty()) OperationState.FAILED
        else if (missing.size == intent.items.size) OperationState.SUCCEEDED else OperationState.PARTIALLY_SUCCEEDED
        finalize(intent, missing, final, HistoryKind.RECONCILED_MISSING)
    }

    // MARK: Intern

    private fun isBusy(item: ItemReference) =
        state.intents.values.any { !it.state.isTerminal && it.items.containsKey(item) }

    private fun finalize(
        intent: OperationIntent,
        removed: Set<ItemReference>,
        final: OperationState,
        kind: HistoryKind,
    ): OperationIntent {
        val i = intent.copy(state = final, updatedAt = clock.now, confirmedRemoved = removed)
        val records = mutableListOf<ItemRecord>()
        val events = mutableListOf<DecisionEvent>()
        for (item in intent.items.keys.sortedBy { it.description }) {
            val current = recordUnlocked(item)
            if (item in removed) {
                val next = current.applying(DecisionSnapshot(Disposition.REMOVED)).copy(revision = current.revision + 1)
                records.add(next)
                events.add(
                    DecisionEvent(
                        commandID = null, operationID = intent.id, item = item, kind = kind,
                        from = current.snapshot, to = next.snapshot, revision = next.revision,
                        at = clock.now, sessionID = null,
                    ),
                )
            } else if (final == OperationState.FAILED || final == OperationState.CANCELLED || final == OperationState.PARTIALLY_SUCCEEDED) {
                events.add(
                    DecisionEvent(
                        commandID = null, operationID = intent.id, item = item, kind = HistoryKind.CLEANUP_NOT_PERFORMED,
                        from = current.snapshot, to = current.snapshot, revision = current.revision,
                        at = clock.now, sessionID = null,
                    ),
                )
            }
        }
        val usage = state.usage[intent.allowanceDay] ?: DayUsage()
        val newUsage = DayUsage(
            confirmed = usage.confirmed + if (intent.reservedCount > 0) removed.size else 0,
            reservations = usage.reservations - intent.id,
        )
        persistCleanup(
            LedgerChange(records = records, events = events, intents = listOf(i), usage = mapOf(intent.allowanceDay to newUsage)),
        )
        return i
    }

    private fun persist(change: LedgerChange) {
        try {
            persistence.commit(change)
        } catch (e: Exception) {
            throw DecisionError.PersistenceFailed(e.toString())
        }
        state = state.merging(change)
        _state.value = state
    }

    private fun persistCleanup(change: LedgerChange) {
        try {
            persistence.commit(change)
        } catch (e: Exception) {
            throw CleanupError.PersistenceFailed(e.toString())
        }
        state = state.merging(change)
        _state.value = state
    }
}
