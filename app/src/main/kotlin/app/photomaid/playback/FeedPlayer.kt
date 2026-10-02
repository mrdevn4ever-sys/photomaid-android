package app.photomaid.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Genau ein Player für den Feed (iOS `FeedPlayer`). Ausgehende Videos werden sofort gestoppt;
 * Videoende bleibt beim selben Medium und erzeugt keine Entscheidung. Autoplay immer stumm.
 * Wird lazy erzeugt und bei `release()` (Hintergrund) freigegeben – keine Decoder im Hintergrund.
 *
 * Kopfhörer-/Bluetooth-Tasten: eine Media3-[MediaSession] auf einem eingeschränkten Player. Erlaubt sind nur
 * Wiedergabe/Pause und Spulen im selben Video – „Weiter/Zurück“ gibt es nicht, damit eine Taste nie zum nächsten
 * Medium springt (keine Entscheidung, kein Schritt). Ohne Dienst: keine Sperrbildschirm-Steuerung, keine
 * Wiedergabe im Hintergrund. Kopfhörer abziehen pausiert.
 */
class FeedPlayer(private val context: Context) {
    data class State(
        val itemID: String? = null,
        val isPlaying: Boolean = false,
        val isMuted: Boolean = true,
        val progress: Float = 0f,
        val durationSec: Double = 0.0,
        /** Erstes Bild dieses Videos wurde tatsächlich angezeigt (zählt als „gesehen“, auch ohne Standbild). */
        val firstFrameRendered: Boolean = false,
        /** Wiedergabe ist fehlgeschlagen (Decoder/Datei). Die UI bietet „Erneut versuchen“ und „Weiter“ an. */
        val failed: Boolean = false,
        /** Wiedergabetempo (1×, 1,5×, 2×, 0,5×); beim Laden eines neuen Videos wieder 1×. */
        val speed: Float = 1f,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var exo: ExoPlayer? = null
    private var session: MediaSession? = null

    val player: Player? get() = exo

    private fun ensure(): ExoPlayer = exo ?: ExoPlayer.Builder(context).build().also { p ->
        p.volume = 0f
        p.repeatMode = Player.REPEAT_MODE_OFF
        // Stumm: fremde Audiowiedergabe nicht unterbrechen (kein Audiofokus); mit Ton: Fokus anfordern.
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
        p.setHandleAudioBecomingNoisy(true)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _state.value = _state.value.copy(isPlaying = isPlaying) }
            override fun onRenderedFirstFrame() { _state.value = _state.value.copy(firstFrameRendered = true) }
            override fun onPlayerError(error: PlaybackException) { _state.value = _state.value.copy(isPlaying = false, failed = true) }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) _state.value = _state.value.copy(isPlaying = false, progress = 1f)
            }
        })
        exo = p
        // Fehlt die Session (z. B. Testumgebung), funktionieren Wiedergabe und Bildschirm-Tasten trotzdem.
        session = runCatching { MediaSession.Builder(context, ButtonsOnly(p)).setId("photomaid.player").build() }.getOrNull()
    }

    fun load(itemID: String, uri: Uri, autoplay: Boolean, durationSec: Double) {
        val p = ensure()
        p.stop()
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        // Rückkehr/Autoplay niemals ungefragt mit Ton.
        p.volume = 0f
        p.setAudioAttributes(p.audioAttributes, false)
        p.playWhenReady = autoplay
        p.setPlaybackSpeed(1f)
        _state.value = State(itemID = itemID, isPlaying = false, isMuted = true, progress = 0f, durationSec = durationSec)
    }

    fun setSpeed(speed: Float) {
        val p = exo ?: return
        p.setPlaybackSpeed(speed)
        _state.value = _state.value.copy(speed = speed)
    }

    /** Aktuelle Position und Dauer in ms (für die Zeitanzeige), `null` solange unbekannt. */
    fun positionMs(): Pair<Long, Long>? {
        val p = exo ?: return null
        val d = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        return p.currentPosition to d
    }

    fun play() {
        val p = exo ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.play()
    }

    fun pause() { exo?.pause() }

    fun togglePlay() { if (_state.value.isPlaying) pause() else play() }

    fun toggleMute() {
        val p = exo ?: return
        val muted = !_state.value.isMuted
        p.volume = if (muted) 0f else 1f
        p.setAudioAttributes(p.audioAttributes, !muted)
        _state.value = _state.value.copy(isMuted = muted)
    }

    fun seek(fraction: Float) {
        val p = exo ?: return
        val d = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        p.seekTo((d * fraction).toLong())
        _state.value = _state.value.copy(progress = fraction)
    }

    /** Fortschritt aktualisieren (vom sichtbaren Video-Overlay periodisch aufgerufen). */
    fun tick() {
        val p = exo ?: return
        val d = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        _state.value = _state.value.copy(progress = (p.currentPosition.toFloat() / d).coerceIn(0f, 1f))
    }

    /** Bei Itemwechsel, Werbung, Kauf, Bereinigung, Verlassen. */
    fun stop() {
        exo?.let { it.stop(); it.clearMediaItems() }
        _state.value = State()
    }

    /** Hintergrund / Verlassen des Feeds: Decoder freigeben. */
    fun release() {
        stop()
        session?.release(); session = null
        exo?.release()
        exo = null
    }

    /** Nur Play/Pause, Stopp und Spulen im selben Video – nie „Weiter/Zurück“ (auch nicht über Kopfhörer). */
    internal class ButtonsOnly(player: Player) : ForwardingPlayer(player) {
        private val allowed = Player.Commands.Builder().addAll(
            // Bewusst ohne GET_CURRENT_MEDIA_ITEM/GET_METADATA/GET_TIMELINE: verbundene Controller (System, Bluetooth)
            // erfahren weder Content-URI noch Titel noch Dauer des Mediums.
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        ).build()
        override fun getAvailableCommands(): Player.Commands = allowed
        override fun isCommandAvailable(command: Int): Boolean = allowed.contains(command)
        override fun seekToNext() = Unit
        override fun seekToPrevious() = Unit
        override fun seekToNextMediaItem() = Unit
        override fun seekToPreviousMediaItem() = Unit
        override fun hasNextMediaItem(): Boolean = false
        override fun hasPreviousMediaItem(): Boolean = false
    }
}
