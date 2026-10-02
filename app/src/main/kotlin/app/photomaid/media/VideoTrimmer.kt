package app.photomaid.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import app.photomaid.core.edit.TrimRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Video kürzen: Ergebnis ist eine neue MP4-Datei im Cache; das Original wird nur gelesen. */
interface VideoTrimmer {
    /** [onProgress] 0…1 (falls bekannt). Abbrechen über Coroutine-Abbruch. */
    suspend fun trim(uri: Uri, range: TrimRange, onProgress: (Float) -> Unit): File
}

/**
 * Media3 Transformer mit Trim-Optimierung: nur der Anfang bis zum nächsten Schlüsselbild wird neu kodiert, der Rest
 * unverändert übernommen (gleiche Qualität, schnell). Ton, Drehung und Farbinformationen bleiben erhalten.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class])
class Media3VideoTrimmer(private val context: Context) : VideoTrimmer {
    override suspend fun trim(uri: Uri, range: TrimRange, onProgress: (Float) -> Unit): File = withContext(Dispatchers.Main) {
        val out = File(context.cacheDir, "trim_${System.nanoTime()}.mp4")
        val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(range.startMs).setEndPositionMs(range.endMs).build(),
        ).build()
        coroutineScope {
            var transformer: Transformer? = null
            val poll = launch {
                val holder = ProgressHolder()
                while (true) {
                    delay(200)
                    val t = transformer ?: continue
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                }
            }
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    val t = Transformer.Builder(context)
                        .experimentalSetTrimOptimizationEnabled(true)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(EditError.WriteFailed("transform:${exportException.errorCode}"))
                            }
                        }).build()
                    transformer = t
                    cont.invokeOnCancellation { runCatching { t.cancel() }; out.delete() }
                    t.start(EditedMediaItem.Builder(item).build(), out.absolutePath)
                }
            } finally { poll.cancel() }
        }
        onProgress(1f)
        out
    }
}
