package app.photomaid

import app.photomaid.core.model.ItemReference
import app.photomaid.core.rules.ReviewTask
import app.photomaid.media.MediaCategory
import java.util.UUID

/** Anfrage an den Feed: Quelle + Warteschlange (iOS `FeedRequest`). */
data class FeedRequest(val source: Source, val id: String = UUID.randomUUID().toString()) {
    sealed interface Source {
        data class Category(val category: MediaCategory) : Source
        data object Due : Source
        data class Task(val task: ReviewTask) : Source
        data class Items(val items: List<ItemReference>, val titleRes: Int) : Source
    }

    val titleRes: Int
        get() = when (val s = source) {
            is Source.Category -> when (s.category) {
                MediaCategory.PHOTOS -> R.string.feed_title_photos
                MediaCategory.VIDEOS -> R.string.feed_title_videos
                MediaCategory.SCREENSHOTS -> R.string.feed_title_screenshots
            }
            Source.Due -> R.string.feed_title_due
            is Source.Task -> R.string.feed_title_task
            is Source.Items -> s.titleRes
        }
}
