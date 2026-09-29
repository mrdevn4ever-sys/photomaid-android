package app.photomaid.feature.review

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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
fun MediaPage(asset: MediaAsset, px: Pair<Int, Int>, preview: PreviewState, onResult: (Boolean) -> Unit, skip: () -> Unit, zoomed: Boolean) {
    val c = PM.colors
    val app = LocalApp.current
    val player by app.player.state.collectAsState()
    val kind = stringResource(when (asset.kind) { MediaKind.VIDEO -> R.string.video; MediaKind.SCREENSHOT -> R.string.screenshot; else -> R.string.photo })
    val description = asset.creationDate?.let { stringResource(R.string.a11y_kind_date, kind, DateText.long(it)) } ?: kind
    BoxWithConstraints(Modifier.fillMaxSize()
        .testTag(if (preview == PreviewState.READY) "feed.media.ready" else "feed.media")
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        val knownRatio = asset.pixelWidth > 0 && asset.pixelHeight > 0
        val ratio = asset.aspectRatio
        val shape = RoundedCornerShape(AppRadii.media)
        val tall = ratio < maxWidth / maxHeight
        val frame = if (knownRatio) Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = tall) else Modifier.fillMaxSize()
        Box(frame.clip(shape).background(if (knownRatio) c.backgroundTint else Color.Transparent).testTag("feed.media.frame")) {
            val active = asset.kind == MediaKind.VIDEO && player.itemID == asset.id
            if (active && app.player.player != null) {
                // Ohne bekannte Maße wie bisher: Player in sein Seitenverhältnis einpassen, nie strecken.
                PlayerSurface(app.player.player, if (knownRatio) Modifier.fillMaxSize() else Modifier.align(Alignment.Center).fillMaxWidth()
                    .aspectRatio(ratio, matchHeightConstraintsFirst = tall), surfaceType = SURFACE_TYPE_SURFACE_VIEW)
            } else {
                AsyncImage(
                    model = previewRequest(asset, px), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clearAndSetSemantics {},
                    onSuccess = { onResult(true) }, onError = { onResult(false) },
                )
            }
        }
        when (preview) {
            PreviewState.LOADING -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            PreviewState.FAILED -> Column(Modifier.align(Alignment.Center).padding(AppSpacing.m), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                Icon(Icons.Rounded.WarningAmber, null, tint = c.warning, modifier = Modifier.size(44.dp))
                Text(stringResource(R.string.preview_failed), style = AppTypography.headline, color = c.textPrimary, textAlign = TextAlign.Center)
                GlassButton(stringResource(R.string.skip_without_decision), onClick = skip)
            }
            PreviewState.READY -> if (asset.kind == MediaKind.VIDEO && !zoomed) VideoOverlay(asset)
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

/** Video-Steuerung in eigener Control-Zone (Slider verbraucht seine Bewegung → keine Entscheidungsgeste). */
@Composable
private fun VideoOverlay(asset: MediaAsset) {
    val app = LocalApp.current
    val c = PM.colors
    val reduceMotion = LocalReduceMotion.current
    val state by app.player.state.collectAsState()
    val active = state.itemID == asset.id
    fun start(autoplay: Boolean) = app.player.load(asset.id, asset.uri, autoplay, asset.duration)
    // Autoplay stumm, wenn Animationen nicht reduziert sind.
    LaunchedEffect(asset.id) { if (!reduceMotion) start(true) }
    LaunchedEffect(active, state.isPlaying) { while (active && state.isPlaying) { app.player.tick(); delay(250) } }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        val playLabel = stringResource(if (active && state.isPlaying) R.string.pause else R.string.play)
        Box(Modifier.size(64.dp).glassCapsule().clickable { if (active) app.player.togglePlay() else start(true) }
            .semantics { contentDescription = playLabel }, contentAlignment = Alignment.Center) {
            Icon(if (active && state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = c.textPrimary, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.padding(AppSpacing.s).fillMaxWidth().glassCapsule().padding(horizontal = AppSpacing.s, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Text(DateText.duration(asset.duration), style = AppTypography.caption, color = c.textPrimary)
            if (active) {
                val posLabel = stringResource(R.string.playback_position)
                Slider(value = state.progress, onValueChange = { app.player.seek(it) }, modifier = Modifier.weight(1f).semantics { contentDescription = posLabel },
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.textSecondary.copy(alpha = 0.3f)))
            } else Spacer(Modifier.weight(1f))
            val muteLabel = stringResource(if (state.isMuted) R.string.unmute else R.string.mute)
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(enabled = active) { app.player.toggleMute() }
                .semantics { contentDescription = muteLabel }, contentAlignment = Alignment.Center) {
                Icon(if (state.isMuted || !active) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, null, tint = c.textPrimary)
            }
        }
    }
}
