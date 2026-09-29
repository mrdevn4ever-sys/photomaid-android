package app.photomaid.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/** `CDItemRecord`: eindeutig über `itemKey` (= `ItemReference.description`). */
@Entity(tableName = "item_record")
data class ItemRecordEntity(
    @PrimaryKey val itemKey: String,
    val revision: Long,
    val disposition: String,
    val dueDayKey: String?,
    val payload: String,
)

/** `CDEvent`: eindeutig über `id`, Reihenfolge über `sequence`. */
@Entity(tableName = "event", indices = [Index(value = ["id"], unique = true), Index("itemKey")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val id: String,
    val itemKey: String,
    val kind: String,
    val atMillis: Long,
    val payload: String,
)

/** `CDIntent`: Operation-Journal. */
@Entity(tableName = "intent")
data class IntentEntity(@PrimaryKey val id: String, val state: String, val payload: String)

/** `CDDayUsage`: Tageskontingent je lokalem Kalendertag. */
@Entity(tableName = "day_usage")
data class DayUsageEntity(@PrimaryKey val dayKey: String, val payload: String)

/** `CDKeyValue`: Undo-Stack, Einstellungen, Quellen, Regeln, Rewards, Kauf-Cache (JSON). */
@Entity(tableName = "key_value")
data class KeyValueEntity(@PrimaryKey val key: String, val payload: String)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM item_record") fun records(): List<ItemRecordEntity>
    @Query("SELECT * FROM event ORDER BY sequence ASC") fun events(): List<EventEntity>
    @Query("SELECT * FROM intent") fun intents(): List<IntentEntity>
    @Query("SELECT * FROM day_usage") fun usage(): List<DayUsageEntity>
    @Upsert fun upsertRecords(r: List<ItemRecordEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertEvents(e: List<EventEntity>)
    @Upsert fun upsertIntents(i: List<IntentEntity>)
    @Upsert fun upsertUsage(u: List<DayUsageEntity>)
}

@Dao
interface KeyValueDao {
    @Query("SELECT payload FROM key_value WHERE `key` = :key LIMIT 1") fun get(key: String): String?
    @Upsert fun put(kv: KeyValueEntity)
}

@Database(
    entities = [ItemRecordEntity::class, EventEntity::class, IntentEntity::class, DayUsageEntity::class, KeyValueEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PhotoMaidDatabase : RoomDatabase() {
    abstract fun ledger(): LedgerDao
    abstract fun keyValue(): KeyValueDao

    companion object {
        const val NAME = "photomaid.db"

        /** Lokal, ohne Cloud. `inMemory` nur für Tests und QA-Fixtures. */
        fun open(context: Context, inMemory: Boolean = false): PhotoMaidDatabase {
            val b = if (inMemory) Room.inMemoryDatabaseBuilder(context, PhotoMaidDatabase::class.java)
            else Room.databaseBuilder(context, PhotoMaidDatabase::class.java, NAME)
            return b.setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
        }
    }
}
