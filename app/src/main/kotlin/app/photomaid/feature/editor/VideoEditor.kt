package app.photomaid.feature.editor

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalContext
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import app.photomaid.R
import app.photomaid.core.studio.ExportProfile
import app.photomaid.core.studio.VideoPlan
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.PM
import app.photomaid.design.SizeText
import app.photomaid.design.glassCapsule
import app.photomaid.media.LayerRenderer
import app.photomaid.media.Media3VideoEditExporter
import app.photomaid.media.colorNeutral
import app.photomaid.navigation.LocalApp
import kotlinx.coroutines.delay
import java.text.NumberFormat
import kotlin.math.abs

/** Tempo-Stufen (Plan §12): 0,5×/1×/1,5×/2× vorn, 0,25×/4× dahinter. */
private val SPEEDS = listOf(0.5f, 1f, 1.5f, 2f, 0.25f, 4f)

private fun speedLabel(s: Float): String = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(s) + "×"

/**
 * Vorschau mit eigenem Player und denselben Media3-Effekten wie der Export (Drehen, Farbe als Farbtabelle, Format);
 * Abschnitte nach Schnitten als Wiedergabeliste, Tempo, Lautstärke mit Blenden. Texte/Sticker liegen darüber
 * (gleiche Zeichnung wie im Export). Tippen = Pause/Play – nie eine Entscheidung.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoEditStage(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val a = vm.asset ?: return
    val project = vm.videoProject() ?: return
    val clip = project.clips.first()
    val layout = vm.videoLayout
    val player = remember { ExoPlayer.Builder(ctx).build().apply { volume = 0f; repeatMode = Player.REPEAT_MODE_ALL } }
    var playing by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(true) }
    var outMs by remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) {
        app.player.stop()
        val l = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    val segments = remember(clip.trimStartMs, clip.trimEndMs, clip.cuts) { VideoPlan.segments(clip) }
    // Effekte für die Vorschau in kleiner Auflösung (flüssig); gleiche Reihenfolge wie beim Export.
    val effectsKey = listOf(clip.quarterTurns, clip.flipH, clip.adjust, clip.filter, clip.filterIntensity, clip.colorMix, project.aspect, project.fill)
    // Media3: Effekte müssen VOR prepare() gesetzt sein, sonst entsteht keine Effekt-Kette und spätere Filter wirken nicht
    // (Gerätetest: „Filter gehen nicht“). Deshalb bei jeder Änderung: anhalten, Effekte setzen, neu vorbereiten, Position und
    // Wiedergabe beibehalten. Auch neu, sobald die Video-Infos (Abmessungen) da sind.
    LaunchedEffect(segments, effectsKey, vm.videoInfo) {
        delay(150)
        val keepIndex = player.currentMediaItemIndex; val keepPos = player.currentPosition; val wasPlaying = player.isPlaying
        val items = segments.map { s -> MediaItem.Builder().setUri(a.uri).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(s.source.startMs).setEndPositionMs(s.source.endMs).build()).build() }
        val small = vm.videoInfo?.display?.let { VideoPlan.layout(it, clip, project.copy(profile = ExportProfile.SMALL)) }
        player.stop()
        runCatching { player.setVideoEffects(if (small != null) Media3VideoEditExporter.videoEffects(clip, small) else emptyList()) }
            .onFailure { android.util.Log.w("PhotoMaid", "Video-Vorschau ohne Effekte", it) }
        player.setMediaItems(items, keepIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)), keepPos.coerceAtLeast(0))
        player.prepare()
        if (wasPlaying) player.play()
    }
    LaunchedEffect(clip.speed) { player.setPlaybackSpeed(VideoPlan.speedOf(clip).toFloat()) }
    LaunchedEffect(vm.seekRequest) {
        val ms = vm.seekRequest ?: return@LaunchedEffect
        val i = segments.indexOfFirst { ms in it.source.startMs..it.source.endMs }.takeIf { it >= 0 } ?: 0
        player.seekTo(i, (ms - (segments.getOrNull(i)?.source?.startMs ?: 0L)).coerceAtLeast(0))
        vm.seekRequest = null
    }
    val clipNow by rememberUpdatedState(clip)
    val mutedNow by rememberUpdatedState(muted)
    LaunchedEffect(Unit) {
        while (true) {
            delay(80)
            val segs = VideoPlan.segments(clipNow)
            val s = segs.getOrNull(player.currentMediaItemIndex) ?: continue
            val src = s.source.startMs + player.currentPosition
            vm.playheadMs = src
            outMs = VideoPlan.toOutputMs(clipNow, src) ?: s.outStartMs
            player.volume = if (mutedNow || project.removeAudio) 0f else VideoPlan.gain(clipNow, outMs).coerceIn(0f, 1f)
        }
    }
    val ratio = layout?.output?.aspect ?: if (a.pixelWidth > 0 && a.pixelHeight > 0) a.aspectRatio else 16f / 9f
    val playLabel = stringResource(if (playing) R.string.pause else R.string.play)
    Box(modifier.padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xxs), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f).clip(RoundedCornerShape(12.dp)).background(Color.Black)
            .clickable(role = Role.Button, onClickLabel = playLabel) { if (player.isPlaying) player.pause() else player.play() }
            .testTag("editor.video")) {
            PlayerSurface(player, Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_TEXTURE_VIEW)
            LayerPreview(vm, outMs, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.BottomStart).padding(AppSpacing.xs), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).glassCapsule(elevation = 2.dp), contentAlignment = Alignment.Center) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = PM.colors.textPrimary)
                }
                Box(Modifier.size(40.dp).glassCapsule(elevation = 2.dp).clip(CircleShape)
                    .clickable(role = Role.Switch, onClickLabel = stringResource(if (muted) R.string.unmute else R.string.mute)) { muted = !muted }
                    .testTag("video.mute"), contentAlignment = Alignment.Center) {
                    Icon(if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, null, tint = PM.colors.textPrimary)
                }
                Text("${clock(outMs)} / ${clock(clip.outputDurationMs)}", Modifier.glassCapsule(elevation = 2.dp).padding(horizontal = AppSpacing.s, vertical = 6.dp)
                    .testTag("video.time"), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = PM.colors.textPrimary)
            }
            // Standbild der aktuellen Stelle (Originalauflösung, wie im Ergebnis).
            Box(Modifier.align(Alignment.TopEnd).padding(AppSpacing.xs).size(40.dp).glassCapsule(elevation = 2.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.vid_frame)) { vm.saveFrame() }.testTag("video.frame"),
                contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PhotoCamera, stringResource(R.string.vid_frame), tint = PM.colors.textPrimary) }
        }
    }
}

/** Texte/Sticker über dem Video, mit Zeitfenster und weichem Ein-/Ausblenden (wie im Export). */
@Composable
private fun LayerPreview(vm: EditorViewModel, outMs: Long, modifier: Modifier) {
    val project = vm.videoProject() ?: return
    if (project.layers.isEmpty()) return
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.roundToPx() }.coerceAtLeast(2); val h = with(density) { maxHeight.roundToPx() }.coerceAtLeast(2)
        val bitmaps = remember(project.layers.map { it.layer }, w, h) {
            project.layers.map { tl -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { LayerRenderer.apply(it, listOf(tl.layer), w, h) }.asImageBitmap() }
        }
        Canvas(Modifier.fillMaxSize().testTag("video.layers")) {
            project.layers.forEachIndexed { i, tl ->
                val alpha = VideoPlan.layerAlpha(tl, outMs)
                if (alpha > 0f) drawImage(bitmaps[i], alpha = alpha)
            }
        }
    }
}

/** Text/Sticker bearbeiten: auf einem Bild der aktuellen Stelle (wie im Ergebnis). */
@Composable
internal fun VideoPosterStage(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val poster = vm.videoPoster
    Box(modifier) {
        if (poster == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        else MarkupStage(vm.markup, poster, poster, null, Modifier.fillMaxSize())
    }
}

/** Zeitfenster des gewählten Textes/Stickers (Ergebniszeit). */
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun LayerTimeRow(vm: EditorViewModel) {
    val c = PM.colors
    val id = vm.markup.selectedLayerId ?: return
    val total = (vm.clip?.outputDurationMs ?: 0L).coerceAtLeast(1)
    val (s, e) = vm.layerTime(id)
    Column(Modifier.fillMaxWidth().testTag("video.layertime")) {
        Text(stringResource(R.string.vid_layer_time, clock(s), clock(e)), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
        RangeSlider(value = s.toFloat()..e.toFloat(), valueRange = 0f..total.toFloat(),
            onValueChange = { r -> vm.setLayerTime(id, r.start.toLong(), r.endInclusive.toLong(), commit = false) },
            onValueChangeFinished = { vm.commit() },
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.textSecondary.copy(alpha = 0.25f)))
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Kürzen & Schneiden

/** Zeitleiste: Griffe für Anfang/Ende, weggeschnittene Bereiche rot, „Bereich entfernen“ mit eigenen Griffen. */
@Composable
internal fun TrimPanel(vm: EditorViewModel) {
    val c = PM.colors
    val clip = vm.clip ?: return
    val range = vm.trim ?: return
    val total = vm.durationMs.coerceAtLeast(1)
    val density = LocalDensity.current
    val draft = vm.cutDraft
    val outSec = clip.outputDurationMs / 1000.0
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Text(stringResource(R.string.edit_trim_range, clock(range.startMs), clock(range.endMs),
            NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(outSec)),
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
            val rangeNow by rememberUpdatedState(range)
            val draftNow by rememberUpdatedState(draft)
            Canvas(Modifier.fillMaxSize().pointerInput(total, wPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val d = draftNow
                    // 1/2 = Anfang/Ende, 3/4 = Schnitt-Anfang/-Ende
                    val active = if (d != null) { if (abs(down.position.x - xOf(d.startMs)) <= abs(down.position.x - xOf(d.endMs))) 3 else 4 }
                        else { val r = rangeNow; if (abs(down.position.x - xOf(r.startMs)) <= abs(down.position.x - xOf(r.endMs))) 1 else 2 }
                    down.consume()
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val dx = ch.positionChange().x
                        if (dx != 0f) {
                            ch.consume(); moved = true
                            val dms = (dx / track * total).toLong()
                            when (active) {
                                1 -> vm.setTrim(rangeNow.startMs + dms, rangeNow.endMs)
                                2 -> vm.setTrim(rangeNow.startMs, rangeNow.endMs + dms)
                                3 -> draftNow?.let { vm.moveCutDraft(it.startMs + dms, it.endMs) }
                                4 -> draftNow?.let { vm.moveCutDraft(it.startMs, it.endMs + dms) }
                            }
                            vm.seekRequest = when (active) { 1 -> rangeNow.startMs; 2 -> (rangeNow.endMs - 300).coerceAtLeast(rangeNow.startMs)
                                3 -> draftNow?.startMs; else -> draftNow?.endMs }
                        }
                    }
                    if (!moved) vm.seekRequest = ((down.position.x - handle) / track * total).toLong().coerceIn(rangeNow.startMs, rangeNow.endMs)
                    else if (active <= 2) vm.commit()
                }
            }) {
                val sx = xOf(range.startMs); val ex = xOf(range.endMs)
                val h = size.height
                val dim = Color.Black.copy(alpha = 0.5f)
                drawRect(dim, Offset(handle, 4.dp.toPx()), Size((sx - handle).coerceAtLeast(0f), h - 8.dp.toPx()))
                drawRect(dim, Offset(ex, 4.dp.toPx()), Size((wPx - handle - ex).coerceAtLeast(0f), h - 8.dp.toPx()))
                // Weggeschnittene Bereiche
                val red = Color(0xFFE5484D)
                for (cut in clip.cuts) {
                    val a = xOf(cut.startMs); val b = xOf(cut.endMs)
                    drawRect(red.copy(alpha = 0.55f), Offset(a, 4.dp.toPx()), Size(b - a, h - 8.dp.toPx()))
                    var x = a
                    while (x < b) { drawLine(Color.White.copy(alpha = 0.6f), Offset(x, h - 6.dp.toPx()), Offset(minOf(b, x + 10.dp.toPx()), 6.dp.toPx()), 1.dp.toPx()); x += 8.dp.toPx() }
                }
                val accent = c.later
                drawRoundRect(accent, Offset(sx, 0f), Size(ex - sx, h), CornerRadius(8.dp.toPx()), style = Stroke(3.dp.toPx()))
                drawRoundRect(accent, Offset(sx - handle, 0f), Size(handle, h), CornerRadius(6.dp.toPx()))
                drawRoundRect(accent, Offset(ex, 0f), Size(handle, h), CornerRadius(6.dp.toPx()))
                for (x in listOf(sx - handle / 2, ex + handle / 2)) drawLine(Color.White, Offset(x, h * 0.35f), Offset(x, h * 0.65f), 2.dp.toPx())
                if (draft != null) {
                    val a = xOf(draft.startMs); val b = xOf(draft.endMs)
                    drawRect(red.copy(alpha = 0.35f), Offset(a, 0f), Size(b - a, h))
                    drawRect(red, Offset(a, 0f), Size(b - a, h), style = Stroke(2.dp.toPx()))
                    for (x in listOf(a, b)) drawRoundRect(red, Offset(x - 5.dp.toPx(), h * 0.2f), Size(10.dp.toPx(), h * 0.6f), CornerRadius(4.dp.toPx()))
                }
                val ph = vm.playheadMs
                if (ph in range.startMs..range.endMs) drawLine(Color.White, Offset(xOf(ph), 2.dp.toPx()), Offset(xOf(ph), h - 2.dp.toPx()), 2.dp.toPx())
            }
            // TalkBack: Anfang/Ende als Regler (Sekundenschritte).
            val startLabel = stringResource(R.string.edit_trim_start); val endLabel = stringResource(R.string.edit_trim_end)
            Box(Modifier.size(1.dp).semantics {
                contentDescription = startLabel; stateDescription = clock(range.startMs)
                progressBarRangeInfo = ProgressBarRangeInfo(range.startMs.toFloat(), 0f..total.toFloat(), steps = 0)
                setProgress { v -> vm.setTrim(v.toLong(), rangeNow.endMs); vm.commit(); true }
            }.testTag("trim.start"))
            Box(Modifier.size(1.dp).align(Alignment.TopEnd).semantics {
                contentDescription = endLabel; stateDescription = clock(range.endMs)
                progressBarRangeInfo = ProgressBarRangeInfo(range.endMs.toFloat(), 0f..total.toFloat(), steps = 0)
                setProgress { v -> vm.setTrim(rangeNow.startMs, v.toLong()); vm.commit(); true }
            }.testTag("trim.end"))
        }
        if (draft == null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                verticalAlignment = Alignment.CenterVertically) {
                Chip(stringResource(R.string.vid_cut_start), false, Modifier.testTag("cut.start"), icon = Icons.Rounded.ContentCut) { vm.startCut() }
                clip.cuts.forEachIndexed { i, cut ->
                    Chip("${clock(cut.startMs)}–${clock(cut.endMs)}", false, Modifier.testTag("cut.$i").semantics {
                        contentDescription = "${clock(cut.startMs)}–${clock(cut.endMs)}"; stateDescription = "" }, icon = Icons.Rounded.Close) { vm.removeCut(i) }
                }
            }
            val trimOnly = vm.videoProject()?.let { EditSaver.isTrimOnly(it) } == true
            Text(stringResource(when { clip.cuts.isNotEmpty() -> R.string.vid_cut_hint_restore; trimOnly -> R.string.edit_trim_hint; else -> R.string.vid_trim_hint_reencode }, clock(total)),
                style = AppTypography.footnote, color = c.textSecondary)
        } else {
            Text(stringResource(R.string.vid_cut_draft, clock(draft.startMs), clock(draft.endMs)), style = AppTypography.subheadline, color = c.textPrimary,
                modifier = Modifier.testTag("cut.draft"))
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                Chip(stringResource(R.string.vid_cut_confirm), true, Modifier.testTag("cut.confirm"), icon = Icons.Rounded.ContentCut) { vm.confirmCut() }
                Chip(stringResource(R.string.cancel), false, Modifier.testTag("cut.cancel")) { vm.cutDraft = null }
            }
            // Bedienungshilfe: Schnittbereich mit Reglern statt Ziehen
            val dNow by rememberUpdatedState(draft)
            val cutLabel = stringResource(R.string.vid_cut_start)
            Box(Modifier.size(1.dp).semantics {
                contentDescription = cutLabel
                progressBarRangeInfo = ProgressBarRangeInfo(draft.startMs.toFloat(), 0f..total.toFloat(), steps = 0)
                // Bedienungshilfe: ganzen Bereich verschieben (Länge bleibt).
                setProgress { v -> val len = dNow.endMs - dNow.startMs; vm.moveCutDraft(v.toLong(), v.toLong() + len); true }
            }.testTag("cut.draft.start"))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Tempo, Ton, Format

@Composable
internal fun SpeedPanel(vm: EditorViewModel) {
    val c = PM.colors
    val clip = vm.clip ?: return
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("speed.chips"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            for (s in SPEEDS) Chip(speedLabel(s), clip.speed == s, Modifier.testTag("speed.$s")) { vm.setSpeed(s) }
        }
        Text(stringResource(R.string.vid_speed_result, clock(clip.outputDurationMs)), style = AppTypography.subheadline, color = c.textPrimary, modifier = Modifier.testTag("speed.result"))
        Text(stringResource(R.string.vid_speed_hint), style = AppTypography.footnote, color = c.textSecondary)
    }
}

@Composable
internal fun AudioPanel(vm: EditorViewModel) {
    val c = PM.colors
    val clip = vm.clip ?: return
    val p = vm.video ?: return
    val hasAudio = vm.videoInfo?.hasAudio != false
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        if (!hasAudio) { Text(stringResource(R.string.vid_no_audio), style = AppTypography.subheadline, color = c.textSecondary, modifier = Modifier.testTag("audio.none")); return@Column }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Switch) { vm.setRemoveAudio(!p.removeAudio) }
            .padding(horizontal = AppSpacing.xs, vertical = 6.dp).testTag("audio.remove"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.vid_remove_audio), style = AppTypography.body, color = c.textPrimary)
                Text(stringResource(R.string.vid_remove_audio_text), style = AppTypography.footnote, color = c.textSecondary)
            }
            Switch(p.removeAudio, null)
        }
        if (!p.removeAudio) {
            val pct = clip.volume * 100f
            EditSlider(stringResource(R.string.vid_volume), pct, 0f..200f, "${pct.toInt()} %", Modifier.testTag("audio.volume"), snap = 0f) { v, commit ->
                vm.setVolume((v / 5f).toInt() * 5 / 100f, commit) }
            Text(stringResource(R.string.vid_fade), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                for (ms in listOf(0L, 500L, 1000L, 2000L)) {
                    val label = if (ms == 0L) stringResource(R.string.vid_fade_none) else NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(ms / 1000.0) + " s"
                    Chip(label, clip.fadeInMs == ms && clip.fadeOutMs == ms, Modifier.testTag("fade.$ms")) { vm.setFades(ms, ms) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ToolButton(Icons.Rounded.MusicNote, stringResource(R.string.vid_audio_export), Modifier.testTag("audio.export"), onClick = vm::saveAudio)
            }
        }
    }
}

@Composable
internal fun VideoFormatPanel(vm: EditorViewModel) {
    val p = vm.video ?: return
    val options = listOf<Pair<Float?, String>>(null to stringResource(R.string.edit_aspect_original), 1f to "1:1", 0.8f to "4:5", 0.5625f to "9:16", 1.7778f to "16:9")
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("vformat.aspects"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            for ((a, label) in options) Chip(label, if (a == null) p.aspect == null else p.aspect?.let { abs(it - a) < 0.005f } == true,
                Modifier.testTag("vaspect.${a ?: 0f}")) { vm.setVideoAspect(a) }
        }
        if (p.aspect != null) Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Chip(stringResource(R.string.vid_fill), p.fill, Modifier.testTag("vformat.fill")) { vm.setVideoFill(true) }
            Chip(stringResource(R.string.vid_fit), !p.fill, Modifier.testTag("vformat.fit")) { vm.setVideoFill(false) }
        }
        if (p.aspect != null) Text(stringResource(if (p.fill) R.string.vid_fill_hint else R.string.vid_fit_hint), style = AppTypography.footnote, color = PM.colors.textSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToolButton(Icons.Rounded.Rotate90DegreesCw, stringResource(R.string.edit_rotate), Modifier.weight(1f).testTag("vformat.rotate")) { vm.update(vm.recipe.copy(quarterTurns = (vm.recipe.turns + 1) % 4)) }
            ToolButton(Icons.Rounded.Flip, stringResource(R.string.edit_flip_h), Modifier.weight(1f).testTag("vformat.flip")) { vm.update(vm.recipe.copy(flipH = !vm.recipe.flipH)) }
            ToolButton(Icons.Rounded.RestartAlt, stringResource(R.string.edit_reset), Modifier.weight(1f).testTag("editor.reset"), onClick = vm::resetTab)
        }
        vm.videoLayout?.let { l -> Text("${l.output.width} × ${l.output.height}", style = AppTypography.footnote, color = PM.colors.textSecondary, modifier = Modifier.testTag("vformat.size")) }
    }
}

/** Exportprofil im Speichern-Blatt: Größe ausdrücklich als „ca.“ (Bitrate × Dauer), danach zeigt das Ergebnis die echte Größe. */
@Composable
internal fun VideoProfileRow(vm: EditorViewModel) {
    val c = PM.colors
    val p = vm.video ?: return
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Text(stringResource(R.string.vid_profile), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
        for (pr in ExportProfile.entries) {
            val sel = p.profile == pr
            val est = vm.estimateBytes(pr)
            val label = stringResource(when (pr) { ExportProfile.HIGH -> R.string.vid_profile_high; ExportProfile.BALANCED -> R.string.vid_profile_balanced; ExportProfile.SMALL -> R.string.vid_profile_small })
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (sel) c.accent.copy(alpha = 0.12f) else c.backgroundTint)
                .border(if (sel) 2.dp else 0.dp, if (sel) c.accent else Color.Transparent, RoundedCornerShape(12.dp))
                .clickable(role = Role.RadioButton) { vm.setProfile(pr) }.semantics(mergeDescendants = true) { selected = sel }
                .padding(horizontal = AppSpacing.s, vertical = AppSpacing.xs).testTag("vprofile.${pr.name}"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                Icon(if (sel) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (sel) c.accent else c.textSecondary, modifier = Modifier.size(20.dp))
                Text(label, style = AppTypography.body.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal), color = c.textPrimary, modifier = Modifier.weight(1f))
                if (est != null) Text(stringResource(R.string.vid_estimate, SizeText.bytes(est)), style = AppTypography.footnote, color = c.textSecondary)
            }
        }
        Text(stringResource(R.string.vid_estimate_hint), style = AppTypography.footnote, color = c.textSecondary)
        if (vm.videoInfo?.hdr == true && vm.videoProject()?.clips?.first()?.let { !it.colorNeutral() } == true)
            Text(stringResource(R.string.vid_hdr_hint), style = AppTypography.footnote, color = c.textSecondary, modifier = Modifier.testTag("vprofile.hdr"))
    }
}
