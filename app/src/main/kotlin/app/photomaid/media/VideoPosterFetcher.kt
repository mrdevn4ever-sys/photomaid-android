package app.photomaid.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Dimension
import coil3.toAndroidUri

/**
 * Standbild für MediaStore-Videos (`content://media/…/video/…`), zuerst über den System-Thumbnailer
 * (`ContentResolver.loadThumbnail`, wie Galerie-Apps), sonst über ein skaliertes Einzelbild.
 * Hintergrund: Auf dem Gerätetest (Realme RMX3933, Android 14) schlug der generische Video-Frame-Decoder
 * für ein vorhandenes Video fehl („Vorschau konnte nicht geladen werden“). Nichts wird auf die Platte kopiert.
 */
class VideoPosterFetcher(private val uri: Uri, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val android = uri.toAndroidUri()
        val w = (options.size.width as? Dimension.Pixels)?.px?.coerceIn(64, MAX) ?: DEFAULT
        val h = (options.size.height as? Dimension.Pixels)?.px?.coerceIn(64, MAX) ?: DEFAULT
        val bitmap: Bitmap = runCatching { options.context.contentResolver.loadThumbnail(android, Size(w, h), null) }.getOrNull()
            ?: frame(android, w, h)
            ?: error("Kein Standbild für $android")
        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    private fun frame(u: android.net.Uri, w: Int, h: Int): Bitmap? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(options.context, u)
            if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
            else r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }.getOrNull()

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (isMediaStoreVideo(data)) VideoPosterFetcher(data, options) else null
    }

    companion object {
        private const val MAX = 2048
        private const val DEFAULT = 1024
        fun isMediaStoreVideo(u: Uri): Boolean =
            u.scheme == "content" && u.authority == "media" && u.path?.contains("/video/") == true
    }
}
