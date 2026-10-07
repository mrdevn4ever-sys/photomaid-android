package app.photomaid.feature.quickaccess

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.photomaid.R
import app.photomaid.core.organize.MediaFolder
import app.photomaid.core.quickaccess.QuickAccessItem
import app.photomaid.core.quickaccess.QuickSourceKind

fun quickTitleRes(kind: QuickSourceKind): Int = when (kind) {
    QuickSourceKind.PHOTOS -> R.string.qa_photos
    QuickSourceKind.VIDEOS -> R.string.qa_videos
    QuickSourceKind.SCREENSHOTS -> R.string.qa_screenshots
    QuickSourceKind.LATEST_PHOTOS -> R.string.qa_latest_photos
    QuickSourceKind.LATEST_VIDEOS -> R.string.qa_latest_videos
    QuickSourceKind.THIS_WEEK -> R.string.qa_this_week
    QuickSourceKind.FOLDER -> R.string.qa_folder
}

fun quickEmptyRes(kind: QuickSourceKind): Int = when (kind) {
    QuickSourceKind.PHOTOS -> R.string.feed_empty_photos
    QuickSourceKind.VIDEOS -> R.string.feed_empty_videos
    QuickSourceKind.SCREENSHOTS -> R.string.feed_empty_screenshots
    QuickSourceKind.LATEST_PHOTOS -> R.string.feed_empty_latest_photos
    QuickSourceKind.LATEST_VIDEOS -> R.string.feed_empty_latest_videos
    QuickSourceKind.THIS_WEEK -> R.string.feed_empty_this_week
    QuickSourceKind.FOLDER -> R.string.feed_empty_folder
}

/** Sichtbarer Name: Ordner heißen wie ihr Ordnername (so, wie Galerie-Apps sie als Album zeigen). */
@Composable
fun quickTitle(item: QuickAccessItem): String =
    item.folder?.takeIf { item.kind == QuickSourceKind.FOLDER }?.let { MediaFolder(it, 0).name } ?: stringResource(quickTitleRes(item.kind))
