package app.photomaid.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.photomaid.core.studio.ExportProfile
import app.photomaid.core.studio.MoviePlan
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
import kotlin.math.roundToInt

/** Quelle eines Clips: Anzeigegröße (Drehung berücksichtigt), Ton vorhanden, HDR. */
data class MovieSource(val uri: Uri, val isPhoto: Boolean, val display: VSize, val hasAudio: Boolean, val hdr: Boolean)

/** Zusammenfügen/Diashow (P6): Vorschau und Export aus derselben Komposition. */
interface MovieExporter {
    /** Vorschau-Player (Media3 `CompositionPlayer`), `null` wenn das Gerät/Umfeld keine Vorschau kann. */
    fun previewPlayer(project: VideoProject, sources: Map<String, MovieSource>): Player? = null
    fun updatePreview(player: Player, project: VideoProject, sources: Map<String, MovieSource>) {}
    suspend fun export(project: VideoProject, sources: Map<String, MovieSource>, onProgress: (Float) -> Unit): File
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class])
class Media3MovieExporter(private val context: Context) : MovieExporter {

    /** Komposition: eine Bildspur (Videos + Fotos), optional Musik als zweite, sich wiederholende Tonspur. */
    fun composition(project: VideoProject, sources: Map<String, MovieSource>, preview: Boolean): Composition {
        val p = if (preview) project.copy(profile = ExportProfile.SMALL) else project
        val first = p.clips.firstNotNullOfOrNull { sources[it.mediaId]?.display } ?: VSize(1920, 1080)
        val frame = MoviePlan.frame(p, first)
        val anyAudio = !p.removeAudio && p.clips.any { c -> !c.isPhoto && !c.muted && sources[c.mediaId]?.hasAudio == true }
        val starts = MoviePlan.starts(p)
        val items = p.clips.flatMapIndexed { i, clip -> clipItems(clip, sources[clip.mediaId] ?: return@flatMapIndexed emptyList(), p, frame, starts[i], anyAudio) }
        val video = EditedMediaItemSequence.Builder(if (anyAudio) setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO) else setOf(C.TRACK_TYPE_VIDEO))
            .addItems(items).experimentalSetForceAudioTrack(anyAudio || p.music != null).build()
        val sequences = mutableListOf(video)
        p.music?.let { m ->
            val mi = MediaItem.Builder().setUri(Uri.parse(m.uri)).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(m.offsetMs.coerceAtLeast(0)).build()).build()
            val music = EditedMediaItem.Builder(mi).setRemoveVideo(true).setEffects(Effects(listOf(TimedGain { ms -> MoviePlan.musicGain(p, ms) }), emptyList())).build()
            sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItem(music).setIsLooping(true).build()
        }
        val overlays: List<Effect> = if (MoviePlan.blackSeams(p).isEmpty()) emptyList() else listOf(blackFade(p, frame))
        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), overlays))
            .setHdrMode(if (p.clips.any { sources[it.mediaId]?.hdr == true }) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR)
            .build()
    }

    private fun clipItems(clip: VideoClip, src: MovieSource, p: VideoProject, frame: VSize, startMs: Long, anyAudio: Boolean): List<EditedMediaItem> {
        val layout = commonLayout(src.display, clip, frame, p.fill)
        val base = Media3VideoEditExporter.videoEffects(clip, layout).toMutableList()
        if (clip.isPhoto) {
            if (clip.kenBurns) {
                val dur = clip.outputDurationMs.coerceAtLeast(1)
                // Zeitstempel der Effekte liegen im Ergebnis (inkl. Versatz der vorherigen Clips).
                base.add(base.size - 1, MatrixTransformation { tUs ->
                    val s = MoviePlan.kenBurnsScale(((tUs / 1000 - startMs).toFloat() / dur))
                    Matrix().apply { setScale(s, s) }
                })
            }
            val mi = MediaItem.Builder().setUri(src.uri).setImageDurationMs(clip.outputDurationMs).build()
            return listOf(EditedMediaItem.Builder(mi).setFrameRate(30).setEffects(Effects(emptyList(), base)).build())
        }
        val speed = VideoPlan.speedOf(clip).toFloat()
        val removeAudio = !anyAudio || clip.muted || !src.hasAudio
        val audio: List<AudioProcessor> = if (removeAudio) emptyList() else listOf(TimedGain { clip.volume.coerceIn(0f, 2f) })
        return VideoPlan.segments(clip).map { s ->
            val mi = MediaItem.Builder().setUri(src.uri).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(s.source.startMs).setEndPositionMs(s.source.endMs).build()).build()
            EditedMediaItem.Builder(mi).setRemoveAudio(removeAudio)
                .apply { if (speed != 1f) setSpeed(ConstantSpeed(speed)) }
                .setEffects(Effects(audio, base)).build()
        }
    }

    /** Gemeinsamer Rahmen: Clip wird eingepasst (Ränder) oder füllt (Rand abgeschnitten) – nie verzerrt. */
    private fun commonLayout(display: VSize, clip: VideoClip, frame: VSize, fill: Boolean): VideoLayout {
        val rotated = if (((clip.quarterTurns % 4) + 4) % 2 == 1) VSize(display.height, display.width) else display
        val cropped = VSize((clip.crop.width * rotated.width).roundToInt().coerceAtLeast(2), (clip.crop.height * rotated.height).roundToInt().coerceAtLeast(2))
        return VideoLayout(rotated, cropped, frame, fill)
    }

    /** Schwarzblende an den Nähten: schwarzes Bild, dessen Deckkraft der Ergebniszeit folgt. */
    private fun blackFade(p: VideoProject, frame: VSize): Effect {
        // Bild in Ausgabegröße: deckt den Rahmen ohne Skalierung ab.
        val black = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val overlay = object : BitmapOverlay() {
            override fun getBitmap(presentationTimeUs: Long): Bitmap = black
            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
                StaticOverlaySettings.Builder().setAlphaScale(MoviePlan.blackAlpha(p, presentationTimeUs / 1000)).build()
        }
        return OverlayEffect(listOf(overlay))
    }

    override fun previewPlayer(project: VideoProject, sources: Map<String, MovieSource>): Player? = runCatching {
        CompositionPlayer.Builder(context).build().apply { setComposition(composition(project, sources, preview = true)); prepare() }
    }.onFailure { android.util.Log.w("PhotoMaid", "Film-Vorschau nicht möglich", it) }.getOrNull()

    override fun updatePreview(player: Player, project: VideoProject, sources: Map<String, MovieSource>) {
        val cp = player as? CompositionPlayer ?: return
        runCatching {
            val pos = cp.currentPosition; val playing = cp.isPlaying
            cp.setComposition(composition(project, sources, preview = true), pos.coerceAtMost(MoviePlan.totalMs(project)) * 1000)
            cp.prepare(); if (playing) cp.play()
        }.onFailure { android.util.Log.w("PhotoMaid", "Film-Vorschau aktualisieren", it) }
    }

    override suspend fun export(project: VideoProject, sources: Map<String, MovieSource>, onProgress: (Float) -> Unit): File = withContext(Dispatchers.Main) {
        val comp = composition(project, sources, preview = false)
        val first = project.clips.firstNotNullOfOrNull { sources[it.mediaId]?.display } ?: VSize(1920, 1080)
        val frame = MoviePlan.frame(project, first)
        val out = File(context.cacheDir, "movie_${System.nanoTime()}.mp4")
        val encoder = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder()
                .setBitrate(VideoPlan.videoBitrate(VideoLayout(frame, frame, frame, project.fill), 30f, project.profile)).build())
            .build()
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
                                if (cont.isActive) cont.resumeWithException(EditError.WriteFailed("movie:${exportException.errorCode}"))
                            }
                        }).build()
                    transformer = t
                    cont.invokeOnCancellation { runCatching { t.cancel() }; out.delete() }
                    t.start(comp, out.absolutePath)
                }
            } finally { poll.cancel() }
        }
        onProgress(1f)
        out
    }
}

/** Lautstärke nach eigener Laufzeit (gezählte Samples, unabhängig von Zeitstempeln); bleibt beim Wiederholen weiter. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class TimedGain(private val gainAt: (Long) -> Float) : BaseAudioProcessor() {
    private var frames = 0L
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val fmt = inputAudioFormat
        val out = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        val ch = fmt.channelCount.coerceAtLeast(1)
        while (src.remaining() >= 2 * ch) {
            val g = gainAt(frames * 1000 / fmt.sampleRate.coerceAtLeast(1))
            repeat(ch) { out.putShort((src.short * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()) }
            frames++
        }
        out.flip()
    }
    override fun onReset() { frames = 0 }
}
