package app.photomaid.feature.editor

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import app.photomaid.R
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.navigation.LocalApp
import app.photomaid.design.PM
import app.photomaid.design.glassCapsule
import kotlinx.coroutines.delay
import kotlin.math.abs

internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Vorschau im vorhandenen Player: spielt nur den gewählten Bereich, in Schleife. Stumm, bis der Nutzer Ton einschaltet. */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoTrimStage(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val a = vm.asset ?: return
    val state by app.player.state.collectAsState()
    val key = "edit:" + a.id
    DisposableEffect(key) {
        app.player.load(key, a.uri, autoplay = false, durationSec = a.duration)
        onDispose { app.player.stop() }
    }
    val range = vm.trim
    val rangeNow by rememberUpdatedState(range)
    // Bereich in Schleife; nach dem Ziehen eines Griffs an dessen Stelle springen.
    LaunchedEffect(key) {
        while (true) {
            delay(80)
            val p = app.player.player ?: continue
            val r = rangeNow ?: continue
            if (p.currentPosition >= r.endMs || p.currentPosition < r.startMs - 250) p.seekTo(r.startMs)
            vm.playheadMs = p.currentPosition
        }
    }
    LaunchedEffect(range?.startMs) { range?.let { app.player.player?.seekTo(it.startMs) } }
    LaunchedEffect(range?.endMs) { range?.let { r -> if (!state.isPlaying) app.player.player?.seekTo((r.endMs - 300).coerceAtLeast(r.startMs)) } }
    val ratio = if (a.pixelWidth > 0 && a.pixelHeight > 0) a.aspectRatio else 16f / 9f
    Box(modifier.padding(AppSpacing.m), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f).clip(RoundedCornerShape(12.dp)).background(Color.Black)
            .clickable(role = Role.Button, onClickLabel = stringResource(if (state.isPlaying) R.string.pause else R.string.play)) { app.player.togglePlay() }
            .testTag("editor.video")) {
            if (state.itemID == key && app.player.player != null)
                PlayerSurface(app.player.player, Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_SURFACE_VIEW)
            Row(Modifier.align(Alignment.BottomStart).padding(AppSpacing.xs), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                Box(Modifier.size(40.dp).glassCapsule(elevation = 2.dp), contentAlignment = Alignment.Center) {
                    Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = PM.colors.textPrimary)
                }
                Box(Modifier.size(40.dp).glassCapsule(elevation = 2.dp).clip(CircleShape)
                    .clickable(role = Role.Switch, onClickLabel = stringResource(if (state.isMuted) R.string.unmute else R.string.mute)) { app.player.toggleMute() },
                    contentAlignment = Alignment.Center) {
                    Icon(if (state.isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, null, tint = PM.colors.textPrimary)
                }
            }
        }
    }
}

/** Zeitleiste mit Vorschaubildern und zwei Griffen; Anzeige „0:12 – 0:47 · 35 s“. */
@Composable
internal fun TrimPanel(vm: EditorViewModel) {
    val c = PM.colors
    val range = vm.trim ?: return
    val total = vm.durationMs.coerceAtLeast(1)
    val density = LocalDensity.current
    val lenSec = ((range.endMs - range.startMs) / 1000.0)
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Text(stringResource(R.string.edit_trim_range, clock(range.startMs), clock(range.endMs), java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(lenSec)),
            style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.testTag("trim.range"))
        BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp).testTag("trim.timeline")) {
            val wPx = with(density) { maxWidth.toPx() }
            val handle = with(density) { 16.dp.toPx() }
            val track = wPx - 2 * handle
            fun xOf(ms: Long) = handle + track * ms / total
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(c.backgroundTint)) {
                val thumbs = vm.thumbnails
                if (thumbs == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                else for (t in thumbs) {
                    val img = remember(t) { t.asImageBitmap() }
                    Image(img, null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                }
            }
            var active by remember { mutableStateOf(0) } // 1 = Anfang, 2 = Ende
            val rangeNow by rememberUpdatedState(range)
            var acc by remember { mutableFloatStateOf(0f) }
            Canvas(Modifier.fillMaxSize().pointerInput(total, wPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val r = rangeNow
                    val ds = abs(down.position.x - xOf(r.startMs)); val de = abs(down.position.x - xOf(r.endMs))
                    active = if (ds <= de) 1 else 2
                    down.consume(); acc = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val dx = ch.positionChange().x
                        if (dx != 0f) {
                            ch.consume()
                            val cur = rangeNow
                            val dms = (dx / track * total).toLong()
                            if (active == 1) vm.setTrim(cur.startMs + dms, cur.endMs) else vm.setTrim(cur.startMs, cur.endMs + dms)
                        }
                    }
                    active = 0
                }
            }) {
                val sx = xOf(range.startMs); val ex = xOf(range.endMs)
                val top = 0f; val h = size.height
                val dim = Color.Black.copy(alpha = 0.5f)
                drawRect(dim, Offset(handle, 4.dp.toPx()), Size((sx - handle).coerceAtLeast(0f), h - 8.dp.toPx()))
                drawRect(dim, Offset(ex, 4.dp.toPx()), Size((wPx - handle - ex).coerceAtLeast(0f), h - 8.dp.toPx()))
                val accent = c.later
                drawRoundRect(accent, Offset(sx, top), Size(ex - sx, h), CornerRadius(8.dp.toPx()), style = Stroke(3.dp.toPx()))
                drawRoundRect(accent, Offset(sx - handle, top), Size(handle, h), CornerRadius(6.dp.toPx()))
                drawRoundRect(accent, Offset(ex, top), Size(handle, h), CornerRadius(6.dp.toPx()))
                for (x in listOf(sx - handle / 2, ex + handle / 2))
                    drawLine(Color.White, Offset(x, h * 0.35f), Offset(x, h * 0.65f), 2.dp.toPx())
                val ph = vm.playheadMs
                if (ph in range.startMs..range.endMs) drawLine(Color.White, Offset(xOf(ph), 2.dp.toPx()), Offset(xOf(ph), h - 2.dp.toPx()), 2.dp.toPx())
            }
            // TalkBack: zwei Regler (Anfang, Ende) mit Sekundenschritten.
            val startLabel = stringResource(R.string.edit_trim_start); val endLabel = stringResource(R.string.edit_trim_end)
            Box(Modifier.size(1.dp).semantics {
                contentDescription = startLabel; stateDescription = clock(range.startMs)
                progressBarRangeInfo = ProgressBarRangeInfo(range.startMs.toFloat(), 0f..total.toFloat(), steps = 0)
                setProgress { v -> vm.setTrim(v.toLong(), rangeNow.endMs); true }
            }.testTag("trim.start"))
            Box(Modifier.size(1.dp).align(Alignment.TopEnd).semantics {
                contentDescription = endLabel; stateDescription = clock(range.endMs)
                progressBarRangeInfo = ProgressBarRangeInfo(range.endMs.toFloat(), 0f..total.toFloat(), steps = 0)
                setProgress { v -> vm.setTrim(rangeNow.startMs, v.toLong()); true }
            }.testTag("trim.end"))
        }
        Text(stringResource(R.string.edit_trim_hint, clock(total)), style = AppTypography.footnote, color = c.textSecondary)
    }
}
