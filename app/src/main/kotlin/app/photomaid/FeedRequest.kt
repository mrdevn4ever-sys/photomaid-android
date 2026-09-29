package app.photomaid

import app.photomaid.core.model.ItemReference
import app.photomaid.core.collections.SmartCollection
import app.photomaid.media.MediaCategory
import java.util.UUID

/** Anfrage an den Feed: Quelle + Warteschlange (iOS `FeedRequest`). */
data class FeedRequest(val source: Source, val id: String = UUID.randomUUID().toString()) {
    sealed interface Source {
        data class Category(val category: MediaCategory) : Source
        /** Gemeinsamer Feed aller offenen Medien (Fotos, Screenshots, Videos) – „Prüfung starten“ auf Heute. */
        data object AllMedia : Source
        data object Due : Source
        /** Dauerhafte Sammlung (z. B. „Videos größer als 100 MB“): beim Öffnen frisch ausgewertet. */
        data class Collection(val collection: SmartCollection) : Source
        data class Items(val items: List<ItemReference>, val titleRes: Int) : Source
    }

    val titleRes: Int
        get() = when (val s = source) {
            is Source.Category -> when (s.category) {
                MediaCategory.PHOTOS -> R.string.feed_title_photos
                MediaCategory.VIDEOS -> R.string.feed_title_videos
                MediaCategory.SCREENSHOTS -> R.string.feed_title_screenshots
            }
            Source.AllMedia -> R.string.feed_title_all
            Source.Due -> R.string.feed_title_due
            is Source.Collection -> R.string.feed_title_collection
            is Source.Items -> s.titleRes
        }
}
