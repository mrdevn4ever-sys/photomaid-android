package app.photomaid.feature.studio

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.photomaid.R
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.edit.EditKind
import app.photomaid.core.edit.EditRecord
import app.photomaid.core.edit.SaveMode
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.MediaKind
import app.photomaid.core.studio.ExportProfile
import app.photomaid.core.studio.MoviePlan
import app.photomaid.core.studio.MusicTrack
import app.photomaid.core.studio.Transition
import app.photomaid.core.studio.VSize
import app.photomaid.core.studio.VideoClip
import app.photomaid.core.studio.VideoProject
import app.photomaid.core.studio.VideoRules
import app.photomaid.design.AppBackground
import app.photomaid.design.CancelButton
import app.photomaid.design.DockItem
import app.photomaid.design.EditorActionButton
import app.photomaid.design.EditorDock
import app.photomaid.design.EditorPrimaryButton
import app.photomaid.design.EditorScaffold
import app.photomaid.design.EditorStatus
import app.photomaid.design.EditorTheme
import app.photomaid.design.EditorTopBar
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.AspectRatio
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassButton
import app.photomaid.design.GlassIconButton
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.ProminentButton
import app.photomaid.design.glassCapsule
import app.photomaid.design.glassRect
import app.photomaid.feature.common.ConfirmationSheet
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.feature.editor.Chip
import app.photomaid.feature.editor.EditSlider
import app.photomaid.feature.editor.ProHint
import app.photomaid.feature.editor.clock
import app.photomaid.media.EditSaveRequest
import app.photomaid.media.EditSaveResult
import app.photomaid.media.MediaAsset
import app.photomaid.media.MovieSource
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.playback.FitVideo
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Aufruf „Video zusammenfügen“ bzw. „Diashow als Video“ mit Startprojekt. */
data class MovieRequest(val id: String, val project: VideoProject, val slideshow: Boolean)

object MovieRequests { val map = mutableStateMapOf<String, MovieRequest>() }

/** Startprojekt aus gewählten Medien in Auswahl-Reihenfolge (Videos ganz, Fotos 3 s mit sanftem Zoom). */
fun movieFrom(assets: List<MediaAsset>, slideshow: Boolean): VideoProject {
    val id = UUID.randomUUID().toString()
    if (slideshow) return MoviePlan.slideshow(id, assets.filter { it.kind != MediaKind.VIDEO }.map { it.id })
    val clips = assets.mapIndexed { i, a ->
        if (a.kind == MediaKind.VIDEO) MoviePlan.videoClip("$id-$i", a.id, (a.duration * 1000).roundToLong().coerceAtLeast(VideoRules.MIN_CLIP_MS))
        else MoviePlan.photoClip("$id-$i", a.id)
    }
    return VideoProject(id, clips)
}

private enum class MovieTab(val label: Int) { CLIPS(R.string.movie_tab_clips), TRANSITION(R.string.movie_tab_transition), FORMAT(R.string.movie_tab_format), MUSIC(R.string.movie_tab_music) }

/**
 * Video zusammenfügen / Diashow als Video (P6): Clips nacheinander, Fotos als Standbild mit sanftem Zoom, Übergang
 * „über Schwarz“, Format (wie erster Clip, 9:16, 1:1, 16:9; einpassen oder füllen – nie verzerrt), eigene Musik.
 * Vorschau und Export aus derselben Komposition. Ergebnis = neues Video in `Movies/PhotoMaid/` (gilt als „Behalten“),
 * die Quellen bleiben ohne Entscheidung. Speichern mit Pro/30-Minuten-Test; ausprobieren immer frei.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun MovieScreen(req: MovieRequest, nav: Nav, onClose: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf(req.project) }
    var selected by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(MovieTab.CLIPS) }
    val assets = remember { mutableStateMapOf<String, MediaAsset>() }
    var sources by remember { mutableStateOf<Map<String, MovieSource>>(emptyMap()) }
    var player by remember { mutableStateOf<Player?>(null) }
    var playing by remember { mutableStateOf(false) }
    var posMs by remember { mutableStateOf(0L) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val canSave = app.capabilities.hasProToolsAccess
    val changed = project != req.project
    BackHandler { if (changed && !saving) confirmDiscard = true else if (!saving) onClose() }

    // Quellen laden (Anzeigegröße, Ton, HDR) – nur lokal gelesen.
    LaunchedEffect(project.clips.map { it.mediaId }.toSet()) {
        val ids = project.clips.map { it.mediaId }.distinct().filter { it !in sources }
        if (ids.isEmpty()) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) { app.allPhotos.assets(ids) }
        val add = withContext(Dispatchers.IO) {
            found.associate { a ->
                assets[a.id] = a
                val info = if (a.kind == MediaKind.VIDEO) runCatching { app.videoExporter.info(a.uri) }.getOrNull() else null
                a.id to MovieSource(a.uri, a.kind != MediaKind.VIDEO, info?.display ?: VSize(a.pixelWidth.coerceAtLeast(2), a.pixelHeight.coerceAtLeast(2)),
                    hasAudio = info?.hasAudio ?: (a.kind == MediaKind.VIDEO), hdr = info?.hdr ?: false)
            }
        }
        sources = sources + add
    }
    // Vorschau: ein CompositionPlayer, nach Änderungen kurz verzögert neu aufgebaut.
    LaunchedEffect(sources.keys, project) {
        if (project.clips.any { it.mediaId !in sources }) return@LaunchedEffect
        delay(300)
        val p = player
        if (p == null) player = app.movieExporter.previewPlayer(project, sources)?.also { pl ->
            pl.addListener(object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } })
        } else app.movieExporter.updatePreview(p, project, sources)
    }
    DisposableEffect(Unit) { app.player.stop(); onDispose { player?.release() } }
    LaunchedEffect(playing) { while (playing) { posMs = player?.currentPosition ?: 0L; delay(200) } }

    fun update(next: VideoProject) { project = next; selected = selected.coerceIn(0, (next.clips.size - 1).coerceAtLeast(0)) }
    fun addMedia() = nav.openPicker(PickRequest(UUID.randomUUID().toString(), photos = true, videos = !req.slideshow, min = 1,
        max = (VideoRules.MAX_CLIPS - project.clips.size).coerceAtLeast(1), title = R.string.movie_add, library = true) { ids ->
        scope.launch {
            val added = withContext(Dispatchers.IO) { app.allPhotos.assets(ids) }
            val clips = movieFrom(added, req.slideshow).clips.mapIndexed { i, cl -> cl.copy(id = "${project.id}-${System.nanoTime()}-$i") }
            val next = project.withClips(project.clips + clips)
            update(if (project.transitions.all { it == Transition.FADE_BLACK } && project.clips.size > 1) next.copy(transitions = next.transitions.map { Transition.FADE_BLACK }) else next)
        }
    })
    fun save() {
        if (!canSave) { nav.openPro(); return }
        val problem = MoviePlan.problem(project)
        if (problem != null) { message = app.string(when (problem) { MoviePlan.Limit.TOO_FEW -> R.string.movie_too_few; MoviePlan.Limit.TOO_MANY -> R.string.movie_too_many; MoviePlan.Limit.TOO_LONG -> R.string.movie_too_long }); return }
        saving = true; progress = 0f; player?.pause()
        val label = app.string(if (req.slideshow) R.string.movie_running_slideshow else R.string.movie_running)
        val job = app.exports.run(label) { onP ->
            val file = app.movieExporter.export(project, sources, onP)
            try {
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                val r = app.photos.saveEdited(EditSaveRequest(sourceId = "", file = file, mime = "video/mp4",
                    displayName = "PhotoMaid_${if (req.slideshow) "Diashow" else "Film"}_$stamp.mp4", relativePath = "Movies/PhotoMaid/",
                    dateTakenMs = System.currentTimeMillis(), video = true, replace = false, favorite = false, replaceName = null))
                val saved = r as? EditSaveResult.Saved ?: throw IllegalStateException("save")
                runCatching { app.decide(ItemReference.photo(saved.newId), DecisionAction.Keep) }
                app.edits.add(EditRecord(saved.newId, "", SaveMode.COPY, true, System.currentTimeMillis(), if (req.slideshow) EditKind.SLIDESHOW else EditKind.MERGE, project.clips.size))
                saved
            } finally { file.delete() }
        }
        scope.launch {
            val watch = launch { app.exports.state.collect { s -> progress = s?.progress } }
            try { job.await(); message = app.string(R.string.movie_saved) ; MovieRequests.map.remove(req.id) }
            catch (e: kotlinx.coroutines.CancellationException) { if (!job.isCancelled) throw e }
            catch (e: Exception) { message = app.string(R.string.movie_failed) }
            finally { watch.cancel(); saving = false; progress = null }
        }
    }

    val tabIcons = mapOf(MovieTab.CLIPS to Icons.Rounded.ViewCarousel, MovieTab.TRANSITION to Icons.Rounded.Gradient,
        MovieTab.FORMAT to Icons.Rounded.AspectRatio, MovieTab.MUSIC to Icons.Rounded.MusicNote)
    val dockItems = MovieTab.entries.map { t -> DockItem(t.name, tabIcons.getValue(t), stringResource(t.label), "movie.tab.${t.name}") }
    EditorTheme {
    Box(Modifier.fillMaxSize().testTag("movie")) {
        EditorScaffold(
            header = {
                EditorTopBar(leading = {
                    CancelButton(stringResource(R.string.cancel), Modifier.testTag("movie.cancel"), enabled = !saving) { if (changed) confirmDiscard = true else onClose() }
                }, trailing = {
                    EditorPrimaryButton(stringResource(R.string.save), Modifier.testTag("movie.save"), enabled = !saving && project.clips.size >= 2) { save() }
                }, title = stringResource(if (req.slideshow) R.string.movie_title_slideshow else R.string.movie_title))
            },
            stage = {
                // Vorschau: eingepasst, nie verzerrt, keine Maske; Wiedergabe und Zeit liegen in der Hilfsleiste darunter.
                BoxWithConstraints(Modifier.fillMaxSize().padding(AppSpacing.s), contentAlignment = Alignment.Center) {
                    val first = project.clips.firstNotNullOfOrNull { sources[it.mediaId]?.display } ?: VSize(16, 9)
                    val frame = MoviePlan.frame(project.copy(profile = ExportProfile.SMALL), first)
                    Box(Modifier.aspectRatio(frame.aspect, matchHeightConstraintsFirst = frame.aspect < maxWidth / maxHeight).background(Color.Black).testTag("movie.stage")) {
                        val p = player
                        if (p != null) FitVideo(p, Modifier.fillMaxSize(), surfaceType = androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW)
                        else project.clips.getOrNull(selected)?.let { cl -> assets[cl.mediaId]?.let { a ->
                            AsyncImage(ImageRequest.Builder(LocalContext.current).data(a.uri).build(), null, contentScale = if (project.fill) ContentScale.Crop else ContentScale.Fit,
                                modifier = Modifier.fillMaxSize())
                        } }
                    }
                }
            },
            actions = {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        val p = player
                        EditorActionButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            stringResource(if (playing) R.string.slideshow_pause else R.string.play), Modifier.testTag("movie.play"), enabled = p != null) {
                            p?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } } }
                        EditorStatus("\u2066${clock(posMs)} / ${clock(MoviePlan.totalMs(project))}\u2069", Modifier.testTag("movie.time"), emphasized = true)
                    }
                }
            },
            tool = {
                ClipStrip(project, assets, selected, onSelect = { selected = it }, onAdd = ::addMedia)
                if (!canSave) ProHint(onPro = nav::openPro)
                when (tab) {
                    MovieTab.CLIPS -> project.clips.getOrNull(selected)?.let { cl -> ClipPanel(project, selected, cl) { update(it) } }
                    MovieTab.TRANSITION -> TransitionPanel(project) { update(it) }
                    MovieTab.FORMAT -> FormatPanel(project) { update(it) }
                    MovieTab.MUSIC -> MusicPanel(project) { update(it) }
                }
            },
            dock = { EditorDock(dockItems, tab.name, { k -> tab = MovieTab.valueOf(k) }, Modifier.testTag("movie.tabs")) },
        )
        if (saving) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(enabled = true, onClick = {}).testTag("movie.saving"),
            contentAlignment = Alignment.Center) {
            Column(Modifier.glassRect().padding(AppSpacing.xl).width(280.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                Text(stringResource(if (req.slideshow) R.string.movie_running_slideshow else R.string.movie_running), style = AppTypography.headline, color = c.textPrimary)
                val pr = progress
                if (pr != null && pr > 0f) LinearProgressIndicator(progress = { pr }, modifier = Modifier.fillMaxWidth()) else CircularProgressIndicator()
                pr?.let { Text("${(it * 100).roundToInt()} %", style = AppTypography.footnote, color = c.textSecondary) }
                GlassButton(stringResource(R.string.vid_continue_background), Modifier.testTag("movie.background")) { app.exports.background(); onClose() }
                PlainButton(stringResource(R.string.cancel), Modifier.testTag("movie.cancelexport")) { app.exports.cancel() }
            }
        }
    }
    }
    if (confirmDiscard) ConfirmationSheet(stringResource(R.string.movie_discard_title), stringResource(R.string.movie_discard_text), stringResource(R.string.edit_discard),
        onConfirm = { onClose() }, onDismiss = { confirmDiscard = false })
    message?.let { m -> NoticeDialog(m) { val done = m == app.string(R.string.movie_saved); message = null; if (done) onClose() } }
}

@Composable
private fun ClipStrip(project: VideoProject, assets: Map<String, MediaAsset>, selected: Int, onSelect: (Int) -> Unit, onAdd: () -> Unit) {
    val c = PM.colors
    val px = with(LocalDensity.current) { 72.dp.roundToPx() }
    LazyRow(Modifier.fillMaxWidth().testTag("movie.strip"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(project.clips, key = { _, cl -> cl.id }) { i, cl ->
            val a = assets[cl.mediaId]
            val label = "${i + 1}, ${stringResource(if (cl.isPhoto) R.string.photo else R.string.video)}, ${clock(cl.outputDurationMs)}"
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(c.backgroundTint)
                .then(if (i == selected) Modifier.border(3.dp, c.accent, RoundedCornerShape(10.dp)) else Modifier)
                .clickable(role = Role.Tab) { onSelect(i) }.semantics { this.selected = i == selected; contentDescription = label }.testTag("movie.clip")) {
                if (a != null) AsyncImage(ImageRequest.Builder(LocalContext.current).data(a.uri).size(px, px).build(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (!cl.isPhoto) Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(3.dp).size(16.dp))
                Text(clock(cl.outputDurationMs), Modifier.align(Alignment.BottomEnd).padding(3.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 3.dp), style = AppTypography.caption, color = Color.White)
            }
        }
        item(key = "add") {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).border(1.5.dp, c.accent, RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onAdd)
                .testTag("movie.add"), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, stringResource(R.string.movie_add), tint = c.accent)
            }
        }
    }
}

@Composable
private fun ClipPanel(project: VideoProject, index: Int, clip: VideoClip, update: (VideoProject) -> Unit) {
    val c = PM.colors
    fun set(cl: VideoClip) = update(project.withClips(project.clips.toMutableList().also { it[index] = cl }))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Text(stringResource(R.string.movie_clip_n, index + 1, project.clips.size), style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold),
            color = c.textPrimary, modifier = Modifier.weight(1f))
        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.movie_move_earlier), Modifier.testTag("movie.left"), enabled = index > 0, quiet = true) {
            update(project.move(index, index - 1)) }
        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.movie_move_later), Modifier.testTag("movie.right"), enabled = index < project.clips.size - 1, quiet = true) {
            update(project.move(index, index + 1)) }
        GlassIconButton(Icons.Rounded.Delete, stringResource(R.string.movie_remove_clip), Modifier.testTag("movie.remove"), enabled = project.clips.size > 1, quiet = true) {
            update(project.withClips(project.clips.filterIndexed { i, _ -> i != index })) }
    }
    if (clip.isPhoto) {
        Text(stringResource(R.string.movie_photo_duration), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            for (s in listOf(2, 3, 5, 8)) Chip(stringResource(R.string.slideshow_seconds, s), clip.outputDurationMs == s * 1000L, Modifier.testTag("movie.dur.$s")) {
                set(MoviePlan.withPhotoDuration(clip, s * 1000L)) }
            Chip(stringResource(R.string.movie_all_photos), false, Modifier.testTag("movie.dur.all")) {
                update(project.withClips(project.clips.map { if (it.isPhoto) MoviePlan.withPhotoDuration(it, clip.outputDurationMs) else it })) }
        }
        ToggleLine(R.string.movie_kenburns, clip.kenBurns, "movie.kenburns") { set(clip.copy(kenBurns = it)) }
    } else {
        Text(stringResource(R.string.movie_trim, clock(clip.trimStartMs), clock(clip.trimEndMs)), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
        RangeSlider(value = clip.trimStartMs.toFloat()..clip.trimEndMs.toFloat(), valueRange = 0f..clip.sourceDurationMs.toFloat(),
            onValueChange = { r -> if (r.endInclusive - r.start >= VideoRules.MIN_CLIP_MS) set(clip.copy(trimStartMs = r.start.roundToLong(), trimEndMs = r.endInclusive.roundToLong())) },
            modifier = Modifier.fillMaxWidth().testTag("movie.trim"),
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.textSecondary.copy(alpha = 0.3f)))
        ToggleLine(R.string.movie_clip_mute, clip.muted, "movie.mute") { set(clip.copy(muted = it)) }
        if (!clip.muted) EditSlider(stringResource(R.string.vid_volume), clip.volume * 100f, 0f..200f, "${(clip.volume * 100).roundToInt()} %", Modifier.testTag("movie.volume"), snap = 0f) { v, _ ->
            set(clip.copy(volume = v / 100f)) }
    }
}

@Composable
private fun TransitionPanel(project: VideoProject, update: (VideoProject) -> Unit) {
    val c = PM.colors
    val all = project.transitions.distinct().singleOrNull() ?: Transition.NONE
    Text(stringResource(R.string.movie_transition_all), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Chip(stringResource(R.string.slideshow_cut), all == Transition.NONE, Modifier.testTag("movie.tr.NONE")) { update(project.copy(transitions = project.transitions.map { Transition.NONE })) }
        Chip(stringResource(R.string.movie_fade_black), all == Transition.FADE_BLACK, Modifier.testTag("movie.tr.FADE_BLACK")) {
            update(project.copy(transitions = project.transitions.map { Transition.FADE_BLACK })) }
    }
    if (project.transitions.any { it == Transition.FADE_BLACK }) {
        Text(stringResource(R.string.movie_transition_duration), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            for (ms in listOf(300L, 600L, 1000L, 1500L)) Chip(java.text.NumberFormat.getInstance().apply { maximumFractionDigits = 1 }.format(ms / 1000.0) + " s",
                project.transitionMs == ms, Modifier.testTag("movie.trms.$ms")) { update(project.copy(transitionMs = ms)) }
        }
    }
    Text(stringResource(R.string.movie_transition_note), style = AppTypography.footnote, color = c.textSecondary)
}

@Composable
private fun FormatPanel(project: VideoProject, update: (VideoProject) -> Unit) {
    val c = PM.colors
    Text(stringResource(R.string.movie_format), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        for ((a, l) in listOf<Pair<Float?, String>>(null to stringResource(R.string.movie_format_first), 9f / 16f to "9:16", 1f to "1:1", 4f / 5f to "4:5", 16f / 9f to "16:9"))
            Chip(l, project.aspect == a, Modifier.testTag("movie.aspect.${a ?: 0f}")) { update(project.copy(aspect = a)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Chip(stringResource(R.string.vid_fit), !project.fill, Modifier.testTag("movie.fit")) { update(project.copy(fill = false)) }
        Chip(stringResource(R.string.vid_fill), project.fill, Modifier.testTag("movie.fill")) { update(project.copy(fill = true)) }
    }
    Text(stringResource(R.string.movie_fit_note), style = AppTypography.footnote, color = c.textSecondary)
    Text(stringResource(R.string.movie_quality), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        for ((p, l) in listOf(ExportProfile.HIGH to R.string.movie_quality_high, ExportProfile.BALANCED to R.string.movie_quality_balanced, ExportProfile.SMALL to R.string.movie_quality_small))
            Chip(stringResource(l), project.profile == p, Modifier.testTag("movie.q.${p.name}")) { update(project.copy(profile = p)) }
    }
}

@Composable
private fun MusicPanel(project: VideoProject, update: (VideoProject) -> Unit) {
    val c = PM.colors
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            // Lesezugriff behalten, damit der Export auch nach einem Neustart der App die Datei lesen kann.
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
            update(project.copy(music = MusicTrack(uri.toString(), name ?: "Audio")))
        }
    }
    val m = project.music
    if (m == null) {
        Text(stringResource(R.string.movie_music_none), style = AppTypography.subheadline, color = c.textSecondary)
        GlassButton(stringResource(R.string.movie_music_pick), Modifier.testTag("movie.music.pick")) { pick.launch(arrayOf("audio/*")) }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Icon(Icons.Rounded.MusicNote, null, tint = c.accent)
            Text(m.name, Modifier.weight(1f).testTag("movie.music.name"), style = AppTypography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PlainButton(stringResource(R.string.movie_music_remove), Modifier.testTag("movie.music.remove")) { update(project.copy(music = null)) }
        }
        EditSlider(stringResource(R.string.movie_music_volume), m.volume * 100f, 0f..100f, "${(m.volume * 100).roundToInt()} %", Modifier.testTag("movie.music.volume"), snap = 0f) { v, _ ->
            update(project.copy(music = m.copy(volume = v / 100f))) }
        ToggleLine(R.string.movie_music_fade, m.fadeMs > 0, "movie.music.fade") { on -> update(project.copy(music = m.copy(fadeMs = if (on) 1500 else 0))) }
    }
    ToggleLine(R.string.movie_original_off, project.removeAudio, "movie.removeaudio") { update(project.copy(removeAudio = it)) }
    Text(stringResource(R.string.movie_music_note), style = AppTypography.footnote, color = c.textSecondary)
}

@Composable
private fun ToggleLine(label: Int, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = AppTypography.body, color = PM.colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(on, onChange, Modifier.testTag(tag))
    }
}
