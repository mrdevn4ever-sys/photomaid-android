package app.photomaid.feature.review

import app.photomaid.design.glassRect
import app.photomaid.design.PlainButton
import app.photomaid.design.ProminentButton
import app.photomaid.design.AppIconSize
import app.photomaid.design.AppStroke
import app.photomaid.design.AppMotion
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.foundation.border
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import app.photomaid.R
import app.photomaid.core.model.MediaKind
import app.photomaid.design.AppRadii
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.DateText
import app.photomaid.design.GlassButton
import app.photomaid.design.LocalReduceMotion
import app.photomaid.design.PM
import app.photomaid.design.SizeText
import app.photomaid.design.glassCapsule
import app.photomaid.media.MediaAsset
import app.photomaid.navigation.LocalApp
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import kotlinx.coroutines.delay

/** Zielgröße: sichtbare Fläche × Dichte, gedeckelt (iOS `PreviewPipeline.targetSize`: max. 2800 px). Nie Originale. */
@Composable
fun rememberPreviewPx(maxWDp: Float, maxHDp: Float): Pair<Int, Int> {
    val d = LocalDensity.current.density
    var w = maxWDp * d; var h = maxHDp * d
    val m = maxOf(w, h)
    if (m > 2800f) { w *= 2800f / m; h *= 2800f / m }
    return maxOf(1, w.toInt()) to maxOf(1, h.toInt())
}

@Composable
private fun previewRequest(asset: MediaAsset, px: Pair<Int, Int>): ImageRequest =
    ImageRequest.Builder(LocalContext.current).data(asset.uri).size(px.first, px.second).precision(Precision.INEXACT)
        .memoryCacheKey("preview:${asset.id}:${px.first}x${px.second}").build()

/** Vorab-Dekodierung für Nachbar-Items (landet im Speichercache, nichts auf der Platte). */
@Composable
fun PrefetchImage(asset: MediaAsset, px: Pair<Int, Int>, onResult: (Boolean) -> Unit) {
    val req = previewRequest(asset, px)
    val ctx = LocalContext.current
    LaunchedEffect(asset.id, px) { onResult(ctx.imageLoader.execute(req) is SuccessResult) }
}

/**
 * Ein Medium pro Seite. Das Original steht vollständig im Mittelpunkt: Rahmen exakt im Seitenverhältnis
 * des Mediums (Fit, nie Fill, kein Beschnitt), Fotos und Videos nach derselben Geometrie. Die freie Fläche
 * daneben bleibt ruhiger App-Hintergrund statt weißer Karte.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MediaPage(
    asset: MediaAsset,
    px: Pair<Int, Int>,
    preview: PreviewState,
    onResult: (Boolean) -> Unit,
    skip: () -> Unit,
    zoomed: Boolean,
    /** Bedeutungsfarbe, sobald Loslassen entscheiden würde (feine Kontur statt Text/Kapsel). */
    intentColor: Color? = null,
    /** Zähler für „Erneut versuchen“ – ein neuer Wert lädt das Vorschaubild neu. */
    retry: Int = 0,
    onRetry: () -> Unit = {},
) {
    val c = PM.colors
    val app = LocalApp.current
    val player by app.player.state.collectAsState()
    val kind = stringResource(when (asset.kind) { MediaKind.VIDEO -> R.string.video; MediaKind.SCREENSHOT -> R.string.screenshot; else -> R.string.photo })
    val description = asset.creationDate?.let { stringResource(R.string.a11y_kind_date, kind, DateText.long(it)) } ?: kind
    val isVideo = asset.kind == MediaKind.VIDEO
    val playPauseLabel = stringResource(if (player.itemID == asset.id && player.isPlaying) R.string.pause else R.string.play)
    BoxWithConstraints(Modifier.fillMaxSize()
        .testTag(if (preview == PreviewState.READY) "feed.media.ready" else "feed.media")
        .semantics {
            contentDescription = description
            // TalkBack/Schalter: Wiedergabe ohne Tipp-Geste.
            if (isVideo) customActions = listOf(CustomAccessibilityAction(playPauseLabel) { toggleVideo(app.player, asset); true })
        }, contentAlignment = Alignment.Center) {
        val knownRatio = asset.pixelWidth > 0 && asset.pixelHeight > 0
        val ratio = asset.aspectRatio
        val shape = RoundedCornerShape(AppRadii.media)
        val tall = ratio < maxWidth / maxHeight
        val frame = if (knownRatio) Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = tall) else Modifier.fillMaxSize()
        val ring by animateColorAsState(intentColor ?: Color.Transparent, AppMotion.fast(), label = "intent")
        Box(frame.clip(shape).background(if (knownRatio) c.backgroundTint else Color.Transparent)
            .border(AppStroke.intent, ring, shape).testTag(if (intentColor != null) "feed.media.intent" else "feed.media.frame")) {
            val active = asset.kind == MediaKind.VIDEO && player.itemID == asset.id
            if (active && app.player.player != null) {
                // Ohne bekannte Maße wie bisher: Player in sein Seitenverhältnis einpassen, nie strecken.
                PlayerSurface(app.player.player, if (knownRatio) Modifier.fillMaxSize() else Modifier.align(Alignment.Center).fillMaxWidth()
                    .aspectRatio(ratio, matchHeightConstraintsFirst = tall), surfaceType = SURFACE_TYPE_SURFACE_VIEW)
            } else {
                androidx.compose.runtime.key(retry) {
                    AsyncImage(
                        model = previewRequest(asset, px), contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clearAndSetSemantics {},
                        onSuccess = { onResult(true) }, onError = { onResult(false) },
                    )
                }
            }
            if (isVideo && !zoomed && preview != PreviewState.LOADING) VideoControls(asset, skip)
        }
        // Video ohne Standbild: trotzdem abspielbar; sobald das erste Bild wirklich läuft, gilt es als gesehen.
        LaunchedEffect(asset.id, player.itemID, player.firstFrameRendered) {
            if (asset.kind == MediaKind.VIDEO && player.itemID == asset.id && player.firstFrameRendered && preview != PreviewState.READY) onResult(true)
        }
        when (preview) {
            PreviewState.LOADING -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(AppIconSize.large), strokeWidth = AppStroke.progress)
            PreviewState.FAILED -> if (isVideo && !zoomed) {
                Column(Modifier.align(Alignment.TopCenter).padding(AppSpacing.m), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text(stringResource(R.string.video_poster_failed), style = AppTypography.footnote, color = c.textPrimary, textAlign = TextAlign.Center,
                        modifier = Modifier.glassCapsule().padding(horizontal = AppSpacing.s, vertical = AppSpacing.xxs))
                    GlassButton(stringResource(R.string.skip_without_decision), onClick = skip)
                }
            } else MediaProblem(stringResource(R.string.preview_failed), stringResource(R.string.preview_failed_hint), onRetry, skip, Modifier.align(Alignment.Center))
            PreviewState.READY -> Unit
        }
    }
}

/**
 * Dezente Infozeile unter dem Medium: Datum · Dateigröße · (Video) Dauer. Nur Werte, die MediaStore ohnehin
 * liefert; unbekannte Größe wird weggelassen (nie „0 B“). Verdeckt weder Medium noch Bedienelemente.
 */
@Composable
fun MediaInfoLine(asset: MediaAsset?, modifier: Modifier = Modifier) {
    val parts = if (asset == null) emptyList() else listOfNotNull(
        asset.creationDate?.let { DateText.long(it) },
        asset.size.bytesOrNull?.let { SizeText.bytes(it) },
        if (asset.kind == MediaKind.VIDEO && asset.duration > 0) DateText.duration(asset.duration) else null,
    )
    Text(parts.joinToString(" · "), style = AppTypography.footnote, color = PM.colors.textSecondary, maxLines = 1,
        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth().testTag("feed.info"))
}

/** Tipp aufs Video (wie TikTok): läuft → Pause, pausiert → weiter, noch nicht geladen → starten (stumm). */
fun toggleVideo(player: app.photomaid.playback.FeedPlayer, asset: MediaAsset) {
    if (player.state.value.itemID == asset.id) player.togglePlay() else player.load(asset.id, asset.uri, autoplay = true, durationSec = asset.duration)
}

/**
 * Video-Bedienung ohne Knopfleiste: Pause/Play per Tipp aufs Medium (vom Feed gemeldet), ein Play-Symbol nur im
 * pausierten Zustand, unten eine dünne Fortschrittsleiste (anfassbar zum Spulen, verbraucht ihre Bewegung →
 * keine Entscheidungsgeste) und ein kleines Stumm-Symbol.
 */
@Composable
private fun BoxScope.VideoControls(asset: MediaAsset, skip: () -> Unit) {
    val app = LocalApp.current
    val reduceMotion = LocalReduceMotion.current
    val state by app.player.state.collectAsState()
    val active = state.itemID == asset.id
    // Autoplay stumm, wenn Animationen nicht reduziert sind.
    LaunchedEffect(asset.id) { if (!reduceMotion && !active) app.player.load(asset.id, asset.uri, autoplay = true, durationSec = asset.duration) }
    LaunchedEffect(active, state.isPlaying) { while (active && state.isPlaying) { app.player.tick(); delay(200) } }
    if (active && state.failed) {
        // Wiedergabe fehlgeschlagen: erklären und einen Ausweg anbieten (nie eine Entscheidung).
        MediaProblem(stringResource(R.string.video_failed), null,
            onRetry = { app.player.load(asset.id, asset.uri, autoplay = true, durationSec = asset.duration) }, skip = skip,
            modifier = Modifier.align(Alignment.Center))
        return
    }
    if (!(active && state.isPlaying)) {
        Box(Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)).clearAndSetSemantics {}
            .testTag("video.paused"), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.size(44.dp))
        }
    }
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(end = AppSpacing.xxs), horizontalArrangement = Arrangement.End) {
            val muteLabel = stringResource(if (state.isMuted) R.string.unmute else R.string.mute)
            Box(Modifier.size(AppSpacing.minTouch).clip(CircleShape).clickable(enabled = active) { app.player.toggleMute() }
                .semantics { contentDescription = muteLabel }.testTag("video.mute"), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.32f)), contentAlignment = Alignment.Center) {
                    Icon(if (state.isMuted || !active) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, null,
                        tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        SeekBar(progress = if (active) state.progress else 0f, enabled = active, onSeek = app.player::seek)
    }
}

/** Ruhiger Problemzustand eines Mediums: was passiert ist + „Erneut versuchen“ / „Weiter ohne Entscheidung“. */
@Composable
private fun MediaProblem(title: String, hint: String?, onRetry: () -> Unit, skip: () -> Unit, modifier: Modifier) {
    val c = PM.colors
    Column(modifier.padding(AppSpacing.m).glassRect(AppRadii.card, 0.dp).padding(AppSpacing.l).testTag("feed.media.problem"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Icon(Icons.Rounded.BrokenImage, null, tint = c.textSecondary, modifier = Modifier.size(AppIconSize.large))
        Text(title, style = AppTypography.headline, color = c.textPrimary, textAlign = TextAlign.Center)
        if (hint != null) Text(hint, style = AppTypography.footnote, color = c.textSecondary, textAlign = TextAlign.Center)
        ProminentButton(stringResource(R.string.retry), onClick = onRetry)
        PlainButton(stringResource(R.string.skip_without_decision), onClick = skip)
    }
}

/** Dünne Leiste wie bei YouTube: 3 dp, beim Anfassen dicker mit Punkt. */
@Composable
private fun SeekBar(progress: Float, enabled: Boolean, onSeek: (Float) -> Unit) {
    val accent = PM.colors.accent
    val label = stringResource(R.string.playback_position)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragValue else progress
    Box(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .testTag("video.seek")
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                if (enabled) setProgress { onSeek(it.coerceIn(0f, 1f)); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    dragging = true
                    fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                    dragValue = at(down.position.x); onSeek(dragValue)
                    while (true) {
                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { ch.consume(); break }
                        ch.consume()
                        dragValue = at(ch.position.x); onSeek(dragValue)
                    }
                    dragging = false
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(Modifier.fillMaxWidth().height(if (dragging) 12.dp else 6.dp)) {
            val h = if (dragging) 5.dp.toPx() else 3.dp.toPx()
            val y = size.height - h
            drawRect(Color.White.copy(alpha = 0.35f), topLeft = Offset(0f, y), size = androidx.compose.ui.geometry.Size(size.width, h))
            drawRect(accent, topLeft = Offset(0f, y), size = androidx.compose.ui.geometry.Size(size.width * shown, h))
            if (dragging) drawCircle(accent, radius = 6.dp.toPx(), center = Offset(size.width * shown, y + h / 2))
        }
    }
}
