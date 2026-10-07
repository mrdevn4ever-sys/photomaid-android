package app.photomaid.feature.library

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import app.photomaid.playback.FitVideo
import app.photomaid.R
import app.photomaid.core.library.Slideshow
import app.photomaid.core.library.SlideshowOrder
import app.photomaid.core.library.SlideshowSettings
import app.photomaid.core.library.SlideshowTransition
import app.photomaid.core.model.MediaKind
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassButton
import app.photomaid.design.GlassIconButton
import app.photomaid.design.LocalReduceMotion
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.design.glassCapsule
import app.photomaid.feature.editor.Chip
import app.photomaid.media.MediaAsset
import app.photomaid.navigation.LocalApp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Diashow-Aufruf: Medien in Anzeige-Reihenfolge, optional Startmedium („Diashow ab hier“). */
data class SlideshowRequest(val id: String, val assets: List<MediaAsset>, val startId: String? = null)

object SlideshowRequests { val map = androidx.compose.runtime.mutableStateMapOf<String, SlideshowRequest>() }

/** Reihenfolge berechnen und so drehen, dass das Startmedium vorne steht; Videos ggf. auslassen. */
internal fun slideshowOrder(req: SlideshowRequest, s: SlideshowSettings, seed: Long): List<MediaAsset> {
    val pool = if (s.playVideos) req.assets else req.assets.filter { it.kind != MediaKind.VIDEO }
    val ordered = Slideshow.order(pool, s, seed, { it.relativePath }, { (it.creationDate ?: it.dateAdded)?.toEpochMilli() })
    val i = req.startId?.let { id -> ordered.indexOfFirst { it.id == id } } ?: -1
    return if (i > 0) ordered.drop(i) + ordered.take(i) else ordered
}

/**
 * Diashow (nur Ansehen, nie eine Entscheidung): Vollbild, Bildschirm bleibt an, Tippen zeigt die Steuerung,
 * Wischen blättert. Videos laufen stumm bis zum Ende (oder werden übersprungen). Einstellungen bleiben gespeichert.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlideshowScreen(req: SlideshowRequest, onClose: () -> Unit) {
    val app = LocalApp.current
    val settings by app.settings.collectAsState()
    val s = settings.slideshow
    val scope = rememberCoroutineScope()
    val reduce = LocalReduceMotion.current
    val seed = remember { System.nanoTime() }
    var order by remember { mutableStateOf(slideshowOrder(req, s, seed)) }
    var index by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var finished by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    val player by app.player.state.collectAsState()
    val current = order.getOrNull(index)
    val view = LocalView.current
    BackHandler(onBack = onClose)
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false; app.player.stop() } }
    // Reihenfolge neu, wenn sich Ordnung/Videos ändern – das gerade gezeigte Medium bleibt.
    LaunchedEffect(s.order, s.shuffleWithinAlbum, s.playVideos) {
        val keep = current?.id
        val next = slideshowOrder(req.copy(startId = null), s, seed)
        if (next.map { it.id } != order.map { it.id }) {
            order = next; index = next.indexOfFirst { it.id == keep }.coerceAtLeast(0)
        }
    }
    fun go(delta: Int) {
        if (order.isEmpty()) return
        val n = index + delta
        when {
            n in order.indices -> { index = n; finished = false }
            n >= order.size && s.loop -> index = 0
            n >= order.size -> finished = true
            else -> index = if (s.loop) order.lastIndex else 0
        }
    }
    // Fotos: nach der eingestellten Dauer weiter. Videos: bis zum Ende (stumm), dann weiter.
    LaunchedEffect(index, paused, finished, s.seconds, current?.id) {
        val a = current ?: return@LaunchedEffect
        if (paused || finished) { if (a.kind == MediaKind.VIDEO) app.player.pause(); return@LaunchedEffect }
        if (a.kind == MediaKind.VIDEO) {
            if (player.itemID != a.id) app.player.load(a.id, a.uri, autoplay = true, durationSec = a.duration) else app.player.play()
        } else {
            app.player.stop()
            delay(s.seconds * 1000L)
            go(1)
        }
    }
    LaunchedEffect(player.itemID, player.progress, player.isPlaying, player.failed) {
        val a = current ?: return@LaunchedEffect
        if (a.kind != MediaKind.VIDEO || player.itemID != a.id || paused) return@LaunchedEffect
        if (player.failed || (!player.isPlaying && player.progress >= 1f)) go(1)
    }
    LaunchedEffect(player.isPlaying) { while (player.isPlaying) { app.player.tick(); delay(250) } }
    LaunchedEffect(controls, paused) { if (controls && !paused && !sheet) { delay(3_000); controls = false } }

    val nextLabel = stringResource(R.string.slideshow_next); val prevLabel = stringResource(R.string.slideshow_prev)
    val pauseLabel = stringResource(if (paused) R.string.play else R.string.slideshow_pause)
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("slideshow")
        .semantics { customActions = listOf(
            CustomAccessibilityAction(nextLabel) { go(1); true }, CustomAccessibilityAction(prevLabel) { go(-1); true },
            CustomAccessibilityAction(pauseLabel) { paused = !paused; true }) }
        .pointerInput(order.size) { detectTapGestures { controls = !controls } }
        .pointerInput(order.size) {
            var dx = 0f
            detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (abs(dx) > 80.dp.toPx()) go(if (dx < 0) 1 else -1) }) { _, d -> dx += d }
        }) {
        val fade = s.transition == SlideshowTransition.FADE && !reduce
        Crossfade(current, animationSpec = if (fade) tween(700) else snap(), label = "slide") { a ->
            if (a == null) return@Crossfade
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Wie im Betrachter: doppelte Bildschirmgröße (max. 4096 px), nie das ganze Original laden; gleicher Cache.
                val d = androidx.compose.ui.platform.LocalDensity.current
                val w = with(d) { maxWidth.roundToPx() }; val h = with(d) { maxHeight.roundToPx() }
                AsyncImage(ImageRequest.Builder(LocalContext.current).data(a.uri).size(minOf(4096, w * 2), minOf(4096, h * 2)).memoryCacheKey("viewer:${a.id}").build(), null,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().testTag("slideshow.image"))
                if (a.kind == MediaKind.VIDEO && player.itemID == a.id && app.player.player != null)
                    FitVideo(app.player.player!!, Modifier.fillMaxSize())
            }
        }
        AnimatedVisibility(controls || paused || finished, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(AppSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(Icons.Rounded.Close, stringResource(R.string.close), Modifier.testTag("slideshow.close"), onClick = onClose)
                    Box(Modifier.weight(1f))
                    if (order.isNotEmpty()) Text("\u2066${index + 1} / ${order.size}\u2069", Modifier.glassCapsule(elevation = 2.dp).padding(horizontal = AppSpacing.s, vertical = 6.dp)
                        .testTag("slideshow.index"), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = PM.colors.textPrimary)
                }
                Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(AppSpacing.s).glassCapsule(elevation = 4.dp)
                    .padding(horizontal = AppSpacing.xxs).testTag("slideshow.controls"), verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(Icons.AutoMirrored.Rounded.NavigateBefore, prevLabel, Modifier.testTag("slideshow.prev"), quiet = true) { go(-1) }
                    GlassIconButton(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, pauseLabel, Modifier.testTag("slideshow.pause"), quiet = true) { paused = !paused }
                    GlassIconButton(Icons.AutoMirrored.Rounded.NavigateNext, nextLabel, Modifier.testTag("slideshow.next"), quiet = true) { go(1) }
                    GlassIconButton(Icons.Rounded.Tune, stringResource(R.string.slideshow_settings), Modifier.testTag("slideshow.settings"), quiet = true) { sheet = true; paused = true }
                }
                if (finished) Column(Modifier.align(Alignment.Center).glassCapsule(elevation = 6.dp).padding(AppSpacing.m).testTag("slideshow.end"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                    Text(stringResource(R.string.slideshow_end), style = AppTypography.headline, color = PM.colors.textPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        GlassButton(stringResource(R.string.close), onClick = onClose)
                        ProminentButton(stringResource(R.string.slideshow_again), Modifier.testTag("slideshow.again")) {
                            index = 0; finished = false; paused = false }
                    }
                }
            }
        }
        if (order.isEmpty()) Text(stringResource(R.string.slideshow_empty), Modifier.align(Alignment.Center).padding(AppSpacing.l), style = AppTypography.body, color = Color.White)
    }
    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false; paused = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SlideshowSettingsContent(s) { change -> scope.launch { app.updateSettings { it.copy(slideshow = change(it.slideshow)) } } }
    }
}

@Composable
private fun SlideshowSettingsContent(s: SlideshowSettings, update: ((SlideshowSettings) -> SlideshowSettings) -> Unit) {
    val c = PM.colors
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.l).testTag("slideshow.sheet"),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Text(stringResource(R.string.slideshow_settings), style = AppTypography.headline, color = c.textPrimary)
        Label(R.string.slideshow_order)
        ChipRow {
            for ((o, l) in listOf(SlideshowOrder.CHRONOLOGICAL to R.string.slideshow_order_chrono, SlideshowOrder.RANDOM to R.string.slideshow_order_random,
                SlideshowOrder.ALBUMS_RANDOM to R.string.slideshow_order_albums))
                Chip(stringResource(l), s.order == o, Modifier.testTag("slideshow.order.${o.name}")) { update { it.copy(order = o) } }
        }
        if (s.order == SlideshowOrder.ALBUMS_RANDOM) Toggle(R.string.slideshow_shuffle_within, s.shuffleWithinAlbum, "slideshow.within") { v -> update { it.copy(shuffleWithinAlbum = v) } }
        Label(R.string.slideshow_duration)
        ChipRow {
            for (sec in SlideshowSettings.SECONDS) Chip(stringResource(R.string.slideshow_seconds, sec), s.seconds == sec, Modifier.testTag("slideshow.sec.$sec")) {
                update { it.copy(seconds = sec) } }
        }
        Label(R.string.slideshow_transition)
        ChipRow {
            Chip(stringResource(R.string.slideshow_fade), s.transition == SlideshowTransition.FADE, Modifier.testTag("slideshow.fade")) { update { it.copy(transition = SlideshowTransition.FADE) } }
            Chip(stringResource(R.string.slideshow_cut), s.transition == SlideshowTransition.NONE, Modifier.testTag("slideshow.cut")) { update { it.copy(transition = SlideshowTransition.NONE) } }
        }
        Toggle(R.string.slideshow_videos, s.playVideos, "slideshow.videos") { v -> update { it.copy(playVideos = v) } }
        Toggle(R.string.slideshow_loop, s.loop, "slideshow.loop") { v -> update { it.copy(loop = v) } }
        Text(stringResource(R.string.slideshow_note), style = AppTypography.footnote, color = c.textSecondary)
    }
}

@Composable
private fun Label(text: Int) = Text(stringResource(text), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = PM.colors.textSecondary)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) { content() }

@Composable
private fun Toggle(text: Int, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val label = stringResource(text)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { stateDescription = if (on) "✓" else "" },
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AppTypography.body, color = PM.colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(on, onChange, Modifier.testTag(tag))
    }
}
