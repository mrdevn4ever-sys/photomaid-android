package app.photomaid.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/*
 * Organisieren (Ausbau Paket 1, Datenbank-Version 2): Alben, Schlagwörter, Notizen, Auswahlkorb. Reine
 * PhotoMaid-Verwaltungsdaten – keine Datei wird verändert, nichts davon ist eine Aufräum-Entscheidung.
 * Medien werden über ihre ID (Content-URI) referenziert; `media_ref` hält den Fingerabdruck (Name, Größe, Aufnahmezeit),
 * damit ein neu indiziertes Medium wiedergefunden werden kann.
 */

@Entity(tableName = "album")
data class AlbumEntity(@androidx.room.PrimaryKey val id: String, val name: String, val createdAt: Long, val position: Int)

@Entity(tableName = "album_item", primaryKeys = ["albumId", "mediaId"], indices = [Index("mediaId")])
data class AlbumItemEntity(val albumId: String, val mediaId: String, val position: Int, val addedAt: Long)

@Entity(tableName = "media_tag", primaryKeys = ["mediaId", "tagKey"], indices = [Index("tagKey")])
data class MediaTagEntity(val mediaId: String, val tagKey: String, val tag: String)

@Entity(tableName = "media_note")
data class MediaNoteEntity(@androidx.room.PrimaryKey val mediaId: String, val text: String, val updatedAt: Long)

@Entity(tableName = "basket_item")
data class BasketItemEntity(@androidx.room.PrimaryKey val mediaId: String, val position: Int, val addedAt: Long)

@Entity(tableName = "media_ref")
data class MediaRefEntity(@androidx.room.PrimaryKey val mediaId: String, val name: String?, val size: Long?, val takenMillis: Long?)

@Dao
interface OrganizeDao {
    @Query("SELECT * FROM album ORDER BY position ASC, createdAt ASC") fun albums(): List<AlbumEntity>
    @Query("SELECT * FROM album_item ORDER BY albumId, position ASC") fun albumItems(): List<AlbumItemEntity>
    @Query("SELECT * FROM media_tag") fun tags(): List<MediaTagEntity>
    @Query("SELECT * FROM media_note") fun notes(): List<MediaNoteEntity>
    @Query("SELECT * FROM basket_item ORDER BY position ASC") fun basket(): List<BasketItemEntity>
    @Query("SELECT * FROM media_ref") fun refs(): List<MediaRefEntity>

    @Upsert fun upsertAlbums(a: List<AlbumEntity>)
    @Upsert fun upsertAlbumItems(i: List<AlbumItemEntity>)
    @Upsert fun upsertTags(t: List<MediaTagEntity>)
    @Upsert fun upsertNotes(n: List<MediaNoteEntity>)
    @Upsert fun upsertBasket(b: List<BasketItemEntity>)
    @Upsert fun upsertRefs(r: List<MediaRefEntity>)

    @Query("DELETE FROM album WHERE id = :id") fun deleteAlbum(id: String)
    @Query("DELETE FROM album_item WHERE albumId = :albumId") fun deleteAlbumItems(albumId: String)
    @Query("DELETE FROM album_item WHERE albumId = :albumId AND mediaId IN (:ids)") fun deleteFromAlbum(albumId: String, ids: List<String>)
    @Query("DELETE FROM media_tag WHERE mediaId IN (:ids) AND tagKey = :key") fun deleteTag(ids: List<String>, key: String)
    @Query("DELETE FROM media_tag WHERE tagKey = :key") fun deleteTagEverywhere(key: String)
    @Query("DELETE FROM media_note WHERE mediaId = :id") fun deleteNote(id: String)
    @Query("DELETE FROM basket_item WHERE mediaId IN (:ids)") fun deleteFromBasket(ids: List<String>)
    @Query("DELETE FROM basket_item") fun clearBasket()

    /** Neu indiziertes Medium (neue ID, gleicher Fingerabdruck): alle Bezüge umschreiben. */
    @Query("UPDATE OR REPLACE album_item SET mediaId = :new WHERE mediaId = :old") fun moveAlbumItems(old: String, new: String)
    @Query("UPDATE OR REPLACE media_tag SET mediaId = :new WHERE mediaId = :old") fun moveTags(old: String, new: String)
    @Query("UPDATE OR REPLACE media_note SET mediaId = :new WHERE mediaId = :old") fun moveNote(old: String, new: String)
    @Query("UPDATE OR REPLACE basket_item SET mediaId = :new WHERE mediaId = :old") fun moveBasket(old: String, new: String)
    @Query("UPDATE OR REPLACE media_ref SET mediaId = :new WHERE mediaId = :old") fun moveRef(old: String, new: String)

    @Transaction fun moveAll(moved: Map<String, String>) {
        for ((old, new) in moved) { moveAlbumItems(old, new); moveTags(old, new); moveNote(old, new); moveBasket(old, new); moveRef(old, new) }
    }
}
