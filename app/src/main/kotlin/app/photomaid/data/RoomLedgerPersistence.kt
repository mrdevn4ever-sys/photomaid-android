package app.photomaid.data

import app.photomaid.core.cleanup.DayUsage
import app.photomaid.core.cleanup.OperationIntent
import app.photomaid.core.decisions.DecisionEvent
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.decisions.LedgerChange
import app.photomaid.core.decisions.LedgerPersistence
import app.photomaid.core.decisions.LedgerState
import app.photomaid.core.decisions.UndoEntry
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.PhotoMaidJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
private data class DayUsageEntry(val day: LocalDay, val usage: DayUsage)

/**
 * [LedgerPersistence] über Room. `commit` schreibt eine [LedgerChange] in genau einer Transaktion
 * (iOS: ein `NSManagedObjectContext.save`). Aufruf nur abseits des Main-Threads (Ledger nutzt IO).
 */
class RoomLedgerPersistence(private val db: PhotoMaidDatabase) : LedgerPersistence {
    private val dao = db.ledger()
    private val kv = db.keyValue()

    companion object {
        const val UNDO_KEY = "ledger.undoStack"
        fun itemKey(item: ItemReference) = item.description
    }

    override fun load(): LedgerState {
        val records = dao.records().map { PhotoMaidJson.decodeFromString<ItemRecord>(it.payload) }.associateBy { it.item }
        val events = dao.events().map { PhotoMaidJson.decodeFromString<DecisionEvent>(it.payload) }
        val intents = dao.intents().map { PhotoMaidJson.decodeFromString<OperationIntent>(it.payload) }.associateBy { it.id }
        val usage = dao.usage().map { PhotoMaidJson.decodeFromString<DayUsageEntry>(it.payload) }.associate { it.day to it.usage }
        val undo = kv.get(UNDO_KEY)?.let { PhotoMaidJson.decodeFromString<List<UndoEntry>>(it) } ?: emptyList()
        return LedgerState(records, events, undo, intents, usage)
    }

    override fun commit(change: LedgerChange) {
        if (change.isEmpty) return
        // Vorab kodieren: Kodierfehler dürfen keine halbe Transaktion hinterlassen.
        val records = change.records.map {
            ItemRecordEntity(itemKey(it.item), it.revision.toLong(), it.disposition.name, it.dueDay?.toString(), PhotoMaidJson.encodeToString(it))
        }
        val events = change.events.map {
            EventEntity(id = it.id.toString(), itemKey = itemKey(it.item), kind = it.kind.name, atMillis = it.at.toEpochMilli(), payload = PhotoMaidJson.encodeToString(it))
        }
        val intents = change.intents.map { IntentEntity(it.id.rawValue.toString(), it.state.name, PhotoMaidJson.encodeToString(it)) }
        val usage = change.usage.map { (day, u) -> DayUsageEntity(day.toString(), PhotoMaidJson.encodeToString(DayUsageEntry(day, u))) }
        val undo = change.undoStack?.let { PhotoMaidJson.encodeToString(it) }
        db.runInTransaction {
            if (records.isNotEmpty()) dao.upsertRecords(records)
            if (events.isNotEmpty()) dao.insertEvents(events)
            if (intents.isNotEmpty()) dao.upsertIntents(intents)
            if (usage.isNotEmpty()) dao.upsertUsage(usage)
            if (undo != null) kv.put(KeyValueEntity(UNDO_KEY, undo))
        }
    }
}

/** Key-Value-App-Zustand (iOS `CoreDataStore.value/setValue`). Blockierend: nur abseits des Main-Threads. */
class KeyValueStore(private val db: PhotoMaidDatabase) {
    val json = PhotoMaidJson

    inline fun <reified T> value(key: String): T? =
        raw(key)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    inline fun <reified T> setValue(key: String, value: T) = putRaw(key, json.encodeToString(value))

    fun raw(key: String): String? = db.keyValue().get(key)
    fun putRaw(key: String, payload: String) = db.keyValue().put(KeyValueEntity(key, payload))
}
