package app.photomaid.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.PixelPipeline
import app.photomaid.core.studio.TimedLayer
import app.photomaid.core.studio.VSize
import app.photomaid.core.studio.VideoClip
import app.photomaid.core.studio.VideoLayout
import app.photomaid.core.studio.VideoPlan
import app.photomaid.core.studio.VideoProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Eckdaten eines Videos (aus den Metadaten; nichts geschätzt außer der Bildrate, falls sie fehlt). */
data class VideoInfo(val display: VSize, val fps: Float, val hdr: Boolean, val hasAudio: Boolean, val durationMs: Long)

/** Video-Bearbeitung: Export eines Projekts (ein Clip), Ton allein (M4A), Standbild in voller Auflösung. */
interface VideoEditExporter {
    fun info(uri: Uri): VideoInfo?
    /** [audioOnly] = nur die Tonspur (AAC in MP4/M4A). [onProgress] 0…1. Abbrechen über Coroutine-Abbruch. */
    suspend fun export(uri: Uri, project: VideoProject, info: VideoInfo, audioOnly: Boolean, onProgress: (Float) -> Unit): File
    /** Bild zur Quellzeit in Originalauflösung (genaues Bild, nicht das nächste Schlüsselbild). */
    suspend fun frame(uri: Uri, sourceMs: Long, maxEdge: Int? = null): Bitmap?
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class Media3VideoEditExporter(private val context: Context) : VideoEditExporter {

    override fun info(uri: Uri): VideoInfo? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            fun i(k: Int) = r.extractMetadata(k)?.toIntOrNull()
            val w = i(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: return null
            val h = i(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: return null
            val rot = i(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
            val transfer = i(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)
            val hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
            val audio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val display = if (rot % 180 != 0) VSize(h, w) else VSize(w, h)
            VideoInfo(display, frameRate(uri) ?: 30f, hdr, audio, dur)
        } finally { r.release() }
    }.getOrNull()

    private fun frameRate(uri: Uri): Float? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_FRAME_RATE) }?.let { f -> runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrElse { f.getFloat(MediaFormat.KEY_FRAME_RATE) } }
        } finally { ex.release() }
    }.getOrNull()

    override suspend fun frame(uri: Uri, sourceMs: Long, maxEdge: Int?): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                if (maxEdge == null) r.getFrameAtTime(sourceMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                else r.getScaledFrameAtTime(sourceMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, maxEdge, maxEdge)
            } finally { r.release() }
        }.getOrNull()
    }

    override suspend fun export(uri: Uri, project: VideoProject, info: VideoInfo, audioOnly: Boolean, onProgress: (Float) -> Unit): File = withContext(Dispatchers.Main) {
        val clip = project.clips.single()
        val layout = VideoPlan.layout(info.display, clip, project)
        val out = File(context.cacheDir, "video_${System.nanoTime()}.${if (audioOnly) "m4a" else "mp4"}")
        val removeAudio = project.removeAudio || clip.muted || !info.hasAudio
        val speed = VideoPlan.speedOf(clip).toFloat()
        val itemEffects = if (audioOnly) Effects.EMPTY else Effects(emptyList(), videoEffects(clip, layout))
        val items = VideoPlan.segments(clip).map { s ->
            val mi = MediaItem.Builder().setUri(uri).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(s.source.startMs).setEndPositionMs(s.source.endMs).build()).build()
            EditedMediaItem.Builder(mi).setRemoveAudio(removeAudio && !audioOnly).setRemoveVideo(audioOnly)
                .apply { if (speed != 1f) setSpeed(ConstantSpeed(speed)) }
                .setEffects(itemEffects).build()
        }
        val audioFx: List<AudioProcessor> = if (removeAudio) emptyList() else listOf(GainProcessor(clip))
        val overlays = if (audioOnly || project.layers.isEmpty()) emptyList<Effect>() else listOf(overlayEffect(project.layers, layout))
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(items).build())
            .setEffects(Effects(audioFx, overlays))
            // Farbeffekte rechnen in SDR: HDR wird dann ehrlich in SDR umgewandelt (Hinweis in der Oberfläche).
            .setHdrMode(if (info.hdr && !clip.colorNeutral()) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR)
            .build()
        val encoder = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(VideoPlan.videoBitrate(layout, info.fps, project.profile)).build())
            .build()
        if (audioOnly && removeAudio) throw EditError.WriteFailed("no-audio")
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
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setEncoderFactory(encoder)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(EditError.WriteFailed("transform:${exportException.errorCode}"))
                            }
                        }).build()
                    transformer = t
                    cont.invokeOnCancellation { runCatching { t.cancel() }; out.delete() }
                    t.start(composition, out.absolutePath)
                }
            } finally { poll.cancel() }
        }
        onProgress(1f)
        out
    }

    companion object {
        /** Effekte je Abschnitt in fester Reihenfolge: drehen/spiegeln → Ausschnitt → Farbe (LUT) → Größe/Seitenverhältnis. */
        fun videoEffects(clip: VideoClip, layout: VideoLayout): List<Effect> = buildList {
            val turns = ((clip.quarterTurns % 4) + 4) % 4
            if (turns != 0 || clip.flipH) add(ScaleAndRotateTransformation.Builder()
                .setScale(if (clip.flipH) -1f else 1f, 1f)
                // Media3 dreht gegen den Uhrzeigersinn; unsere Vierteldrehungen sind im Uhrzeigersinn.
                .setRotationDegrees((360 - 90 * turns) % 360f).build())
            if (!clip.crop.isFull) { val n = VideoPlan.cropNdc(clip.crop); add(Crop(n[0], n[1], n[2], n[3])) }
            if (!clip.colorNeutral()) add(SingleColorLut.createFromCube(PixelPipeline(clip.recipe()).cube(33)))
            add(Presentation.createForWidthAndHeight(layout.output.width, layout.output.height,
                if (layout.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT))
        }

        /** Text/Sticker als Bild in Ausgabegröße, sichtbar nur in ihrem Zeitfenster (weich ein-/ausgeblendet). */
        fun overlayEffect(layers: List<TimedLayer>, layout: VideoLayout): Effect {
            val w = layout.output.width; val h = layout.output.height
            val overlays: List<TextureOverlay> = layers.map { tl ->
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                LayerRenderer.apply(bmp, listOf(tl.layer), w, h)
                object : BitmapOverlay() {
                    override fun getBitmap(presentationTimeUs: Long): Bitmap = bmp
                    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
                        StaticOverlaySettings.Builder().setAlphaScale(VideoPlan.layerAlpha(tl, presentationTimeUs / 1000)).build()
                }
            }
            return OverlayEffect(overlays)
        }
    }
}

/** Farb-Rezept eines Clips (gleiche Pipeline wie Fotos → gleiche Wirkung). */
fun VideoClip.recipe(): EditRecipe = EditRecipe(adjust = adjust.copy(vignette = 0f, grain = 0f, sharpness = 0f, clarity = 0f), filter = filter, filterIntensity = filterIntensity, colorMix = colorMix)
fun VideoClip.colorNeutral(): Boolean = PixelPipeline(recipe()).isNeutral

@UnstableApi
private class ConstantSpeed(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

/** Lautstärke mit Ein-/Ausblenden auf 16-Bit-PCM, nach Zeit im Ergebnis (nach Tempo und Schnitten). */
@UnstableApi
private class GainProcessor(private val clip: VideoClip) : BaseAudioProcessor() {
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val fmt = inputAudioFormat
        val n = inputBuffer.remaining()
        val out = replaceOutputBuffer(n).order(ByteOrder.nativeOrder())
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        val ch = fmt.channelCount.coerceAtLeast(1)
        while (src.remaining() >= 2 * ch) {
            val g = VideoPlan.gain(clip, frames * 1000 / fmt.sampleRate.coerceAtLeast(1))
            repeat(ch) { out.putShort((src.short * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()) }
            frames++
        }
        out.flip()
    }

    override fun onFlush() { frames = 0 }
    override fun onReset() { frames = 0 }
}
