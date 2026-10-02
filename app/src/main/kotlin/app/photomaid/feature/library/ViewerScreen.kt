package app.photomaid.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import app.photomaid.FeedRequest
import app.photomaid.R
import app.photomaid.core.model.MediaKind
import app.photomaid.core.organize.MediaFolder
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassIconButton
import app.photomaid.design.PM
import app.photomaid.design.SizeText
import app.photomaid.design.glassCapsule
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.feature.editor.clock
import app.photomaid.feature.review.FolderSheet
import app.photomaid.feature.review.rememberRecentFolder
import app.photomaid.feature.review.togglePinnedFolder
import app.photomaid.media.MediaAsset
import app.photomaid.media.MediaWriteResult
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.system.MediaIntents
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import app.photomaid.core.decisions.Disposition

/** Öffnen des Betrachters: Reihenfolge wie in der Übersicht (nur im Prozess). */
data class ViewerRequest(val id: String, val assets: List<MediaAsset>, val start: Int)

object ViewerRequests { val map = androidx.compose.runtime.mutableStateMapOf<String, ViewerRequest>() }

/**
 * Vollbild-Betrachter: links/rechts blättern, runter = schließen, zwei Finger/Doppeltipp = Zoom, Tipp = Leisten ein/aus.
 * Keine Wisch-Aktion nach oben, keine Entscheidung durch Ansehen. Unten die bekannte Aktionsleiste; „In den Papierkorb“
 * (PhotoMaid-eigen) mit Rückgängig.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(req: ViewerRequest, nav: Nav, onClose: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.collectAsState()
    val overrides = remember { mutableStateMapOf<String, MediaAsset>() }
    /** Gerade ausgeblendet (Rückgängig); ausgeblendete Medien verschwinden aus dem Blättern. */
    var hiddenUndo by remember { mutableStateOf<String?>(null) }
    val hiddenNow = remember { mutableStateMapOf<String, Boolean>() }
    val assets = req.assets.filter { hiddenNow[it.id] != true }.map { overrides[it.id] ?: it }
    val pager = rememberPagerState(initialPage = req.start.coerceIn(0, (assets.size - 1).coerceAtLeast(0))) { assets.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var dragDown by remember { mutableFloatStateOf(0f) }
    var info by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var folderSheet by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<MediaFolder>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var undo by remember { mutableStateOf<Map<MediaAsset, Disposition>?>(null) }
    val current = assets.getOrNull(pager.currentPage)
    BackHandler(onBack = onClose)
    DisposableEffect(Unit) { onDispose { app.player.stop() } }
    LaunchedEffect(pager.currentPage) { app.player.stop(); zoomed = false }
    LaunchedEffect(undo) { if (undo != null) { delay(5_000); undo = null } }
    // Video läuft: Leisten blenden nach kurzer Zeit von selbst aus (Tipp holt sie zurück).
    val playerState by app.player.state.collectAsState()
    val videoPlaying = playerState.isPlaying && playerState.itemID == current?.id
    LaunchedEffect(videoPlaying, chrome) { if (videoPlaying && chrome) { delay(2_500); chrome = false } }
    LaunchedEffect(hiddenUndo) { if (hiddenUndo != null) { delay(5_000); hiddenUndo = null } }
    LaunchedEffect(assets.isEmpty()) { if (assets.isEmpty() && hiddenUndo == null) onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - abs(dragDown) / 900f).coerceIn(0.2f, 1f))).testTag("viewer")) {
        HorizontalPager(pager, Modifier.fillMaxSize().graphicsLayer { translationY = dragDown }, userScrollEnabled = !zoomed, key = { assets[it].id },
            beyondViewportPageCount = 1) { page ->
            val a = assets[page]
            ViewerPage(a, active = page == pager.currentPage, onTap = { chrome = !chrome }, onPlay = { chrome = false }, onZoom = { zoomed = it },
                onDragDown = { dy, end -> if (end) { if (dragDown > 220f) onClose() else dragDown = 0f } else dragDown = max(0f, dragDown + dy) })
        }
        // Kopfzeile
        AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(AppSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.Rounded.Close, stringResource(R.string.close), Modifier.testTag("viewer.close"), onClick = onClose)
                Column(Modifier.weight(1f).padding(horizontal = AppSpacing.s), horizontalAlignment = Alignment.CenterHorizontally) {
                    current?.creationDate?.let { d ->
                        Text(DateFormat.getDateInstance(DateFormat.LONG).format(Date(d.toEpochMilli())), style = AppTypography.headline, color = Color.White, maxLines = 1)
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(d.toEpochMilli())), style = AppTypography.footnote, color = Color.White.copy(alpha = 0.8f))
                    }
                }
                Text("\u2066${pager.currentPage + 1} / ${assets.size}\u2069", Modifier.glassCapsule(elevation = 2.dp).padding(horizontal = AppSpacing.s, vertical = 6.dp).testTag("viewer.index"),
                    style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = PM.colors.textPrimary)
            }
        }
        // Aktionsleiste (außerhalb der Bildfläche – ein Tipp hier blättert nie)
        AnimatedVisibility(chrome && current != null, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            val a = current ?: return@AnimatedVisibility
            Column(Modifier.navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (a.kind == MediaKind.VIDEO) VideoControls(a)
            Row(Modifier.padding(AppSpacing.s).glassCapsule(elevation = 4.dp).padding(horizontal = AppSpacing.xxs).testTag("viewer.actions"),
                verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(if (a.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(if (a.isFavorite) R.string.fav_remove else R.string.fav_add),
                    Modifier.testTag("viewer.fav"), quiet = true) {
                    scope.launch {
                        when (val r = app.photos.setFavorite(a.id, !a.isFavorite)) {
                            MediaWriteResult.Done -> overrides[a.id] = a.copy(isFavorite = !a.isFavorite)
                            is MediaWriteResult.Failed -> message = app.string(R.string.fav_failed)
                            else -> Unit
                        }
                    }
                }
                val shareTitle = stringResource(R.string.share)
                GlassIconButton(Icons.Rounded.Share, shareTitle, Modifier.testTag("viewer.share"), quiet = true) {
                    runCatching { ctx.startActivity(MediaIntents.share(a.uri, a.kind, a.mimeType, shareTitle)) }
                }
                GlassIconButton(Icons.Outlined.Folder, stringResource(R.string.folder_action), Modifier.testTag("viewer.folder"), quiet = true) {
                    folders = null; folderSheet = true
                    scope.launch { folders = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.photos.folders() }.getOrDefault(emptyList()) } }
                }
                GlassIconButton(Icons.Outlined.Edit, stringResource(R.string.edit_action), Modifier.testTag("viewer.edit"), quiet = true) { nav.openEditor(a.id) }
                Box {
                    GlassIconButton(Icons.Rounded.MoreHoriz, stringResource(R.string.more_actions), Modifier.testTag("viewer.more"), quiet = true) { menu = true }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.info)) }, leadingIcon = { Icon(Icons.Rounded.Info, null) },
                            modifier = Modifier.testTag("viewer.info"), onClick = { menu = false; info = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lib_review_from_here)) }, leadingIcon = { Icon(Icons.Rounded.CleaningServices, null) },
                            modifier = Modifier.testTag("viewer.review"), onClick = {
                                menu = false
                                val rest = app.cleanupCandidates(assets.drop(pager.currentPage)).map { it.reference }
                                nav.openFeed(FeedRequest(FeedRequest.Source.Items(rest, R.string.lib_feed_title)))
                            })
                        DropdownMenuItem(text = { Text(stringResource(R.string.slideshow_from_here)) }, leadingIcon = { Icon(Icons.Rounded.Slideshow, null) },
                            modifier = Modifier.testTag("viewer.slideshow"), onClick = {
                                menu = false; app.player.stop()
                                nav.openSlideshow(SlideshowRequest(java.util.UUID.randomUUID().toString(), assets, a.id))
                            })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lib_hide)) }, leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) },
                            modifier = Modifier.testTag("viewer.hide"), onClick = {
                                menu = false; undo = null
                                scope.launch { setMediaHidden(app, listOf(a.id), true); hiddenNow[a.id] = true; hiddenUndo = a.id }
                            })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lib_trash_action)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            modifier = Modifier.testTag("viewer.trash"), onClick = {
                                menu = false
                                if (settings.protectFavorites && a.isFavorite) message = app.string(R.string.lib_trash_fav_protected)
                                else scope.launch { hiddenUndo = null; undo = markForCleanup(app, listOf(a)) }
                            })
                    }
                }
            }
            }
        }
        // „In den Papierkorb gelegt · Rückgängig“ (nichts gelöscht)

        undo?.let { before ->
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp).glassCapsule(elevation = 6.dp)
                .padding(start = AppSpacing.m, end = AppSpacing.xxs).testTag("viewer.undo"), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.lib_trashed_one), style = AppTypography.subheadline, color = PM.colors.textPrimary)
                TextButton({ scope.launch { undoMark(app, before); undo = null } }, Modifier.testTag("viewer.undo.action")) { Text(stringResource(R.string.undo)) }
            }
        }
        // „In PhotoMaid ausgeblendet · Rückgängig“ (Datei unverändert)
        hiddenUndo?.let { id ->
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp).glassCapsule(elevation = 6.dp)
                .padding(start = AppSpacing.m, end = AppSpacing.xxs).testTag("viewer.hidden"), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.lib_hidden_one), style = AppTypography.subheadline, color = PM.colors.textPrimary)
                TextButton({ scope.launch { setMediaHidden(app, listOf(id), false); hiddenNow.remove(id); hiddenUndo = null } },
                    Modifier.testTag("viewer.hidden.undo")) { Text(stringResource(R.string.undo)) }
            }
        }
    }

    if (info && current != null) ModalBottomSheet(onDismissRequest = { info = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("viewer.infosheet")) { InfoContent(current) }
    if (folderSheet && current != null) FolderSheet(current.kind, current.relativePath, folders, settings.pinnedFolders, settings.recentFolders,
        onTogglePin = { p -> scope.launch { togglePinnedFolder(app, p) } },
        onPick = { path ->
            folderSheet = false
            scope.launch {
                when (app.photos.moveToFolder(current.id, path)) {
                    MediaWriteResult.Done -> { overrides[current.id] = current.copy(relativePath = path); rememberRecentFolder(app, path)
                        message = app.context.resources.getQuantityString(R.plurals.grid_moved, 1, 1, MediaFolder(path, 0).name) }
                    is MediaWriteResult.Failed -> message = app.string(R.string.folder_failed)
                    else -> Unit
                }
            }
        }, onDismiss = { folderSheet = false })
    message?.let { m -> NoticeDialog(m) { message = null } }
}

/**
 * Video-Leiste im Betrachter: Wiedergabe/Pause, Zeit, Spulen, Ton, Tempo. Ansehen ist nie eine Entscheidung; das
 * Videoende bleibt beim selben Medium. Kopfhörer-Tasten steuern denselben Player (nur Wiedergabe/Pause).
 */
@Composable
private fun VideoControls(a: MediaAsset) {
    val app = LocalApp.current
    val c = PM.colors
    val state by app.player.state.collectAsState()
    val mine = state.itemID == a.id
    var pos by remember(a.id) { mutableStateOf(0L to (a.duration * 1000).toLong()) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(mine, state.isPlaying) {
        while (mine) { app.player.positionMs()?.let { pos = it }; app.player.tick(); if (!state.isPlaying) break; kotlinx.coroutines.delay(250) }
    }
    val total = pos.second.coerceAtLeast(1)
    val frac = dragging ?: if (mine) (pos.first.toFloat() / total).coerceIn(0f, 1f) else 0f
    fun ensure(play: Boolean) {
        if (!mine) { app.player.load(a.id, a.uri, autoplay = play, durationSec = a.duration); if (app.player.state.value.isMuted) app.player.toggleMute() }
        else if (play) app.player.play() else app.player.pause()
    }
    // Mediensteuerung und Zeitleiste werden auch in RTL nicht gespiegelt (Material-Richtlinie, wie Videoplayer üblich).
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
    Row(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.s).glassCapsule(elevation = 4.dp).padding(horizontal = AppSpacing.xxs).testTag("viewer.video"),
        verticalAlignment = Alignment.CenterVertically) {
        val playing = mine && state.isPlaying
        GlassIconButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (playing) R.string.slideshow_pause else R.string.play),
            Modifier.testTag("viewer.video.play"), quiet = true) { ensure(!playing) }
        Text(clock(if (dragging != null) (frac * total).toLong() else if (mine) pos.first else 0L), style = AppTypography.caption.copy(fontFeatureSettings = "tnum"), color = c.textPrimary)
        androidx.compose.material3.Slider(frac, { dragging = it }, Modifier.weight(1f).padding(horizontal = AppSpacing.xxs).testTag("viewer.video.seek"),
            onValueChangeFinished = { dragging?.let { f -> if (!mine) ensure(false); app.player.seek(f) }; dragging = null },
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent,
                inactiveTrackColor = c.textSecondary.copy(alpha = 0.3f), activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
        Text(clock(total), style = AppTypography.caption.copy(fontFeatureSettings = "tnum"), color = c.textSecondary)
        GlassIconButton(if (!mine || state.isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            stringResource(if (!mine || state.isMuted) R.string.unmute else R.string.mute), Modifier.testTag("viewer.video.mute"), quiet = true, enabled = mine) {
            app.player.toggleMute() }
        val speeds = listOf(1f, 1.5f, 2f, 0.5f)
        val sp = if (mine) state.speed else 1f
        val speedText = (if (sp % 1f == 0f) "${sp.toInt()}" else java.text.NumberFormat.getInstance().format(sp)) + "×"
        TextButton({ if (!mine) ensure(false); app.player.setSpeed(speeds[(speeds.indexOf(sp).coerceAtLeast(0) + 1) % speeds.size]) },
            Modifier.testTag("viewer.video.speed").semantics { contentDescription = speedText }) {
            Text(speedText, style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
        }
    }
    }
}

/** Eine Seite: Foto mit Zoom bzw. Video mit Abspielen. Runterziehen (ohne Zoom) schließt. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ViewerPage(a: MediaAsset, active: Boolean, onTap: () -> Unit, onPlay: () -> Unit, onZoom: (Boolean) -> Unit, onDragDown: (Float, Boolean) -> Unit) {
    val app = LocalApp.current
    val state by app.player.state.collectAsState()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var lastTap by remember { mutableStateOf(0L) }
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }
    val zoomLabel = stringResource(R.string.edit_zoom_100)
    BoxWithConstraints(Modifier.fillMaxSize().semantics {
        contentDescription = a.displayName ?: ""
        customActions = listOf(CustomAccessibilityAction(zoomLabel) { scale = if (scale > 1.01f) 1f else 2.5f; offset = Offset.Zero; onZoom(scale > 1.01f); true })
    }) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.roundToPx() }; val hPx = with(density) { maxHeight.roundToPx() }
        // Doppelte Bildschirmgröße: scharf auch beim Zoomen, ohne das Original ganz zu laden.
        val req = ImageRequest.Builder(LocalContext.current).data(a.uri).size(minOf(4096, wPx * 2), minOf(4096, hPx * 2)).memoryCacheKey("viewer:${a.id}").build()
        Box(Modifier.fillMaxSize().pointerInput(a.id) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var moved = false; var vertical: Boolean? = null; var total = Offset.Zero
                do {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.count { it.pressed }
                    if (pressed >= 2) {
                        scale = (scale * ev.calculateZoom()).coerceIn(1f, 6f)
                        offset = if (scale <= 1.01f) Offset.Zero else offset + ev.calculatePan()
                        onZoom(scale > 1.01f); moved = true
                        ev.changes.forEach { it.consume() }
                    } else if (pressed == 1) {
                        val d = ev.changes.first().positionChange(); total += d
                        if (total.getDistance() > viewConfiguration.touchSlop) moved = true
                        if (scale > 1.01f) { offset += d; ev.changes.forEach { it.consume() } }
                        else {
                            if (vertical == null && total.getDistance() > viewConfiguration.touchSlop) vertical = abs(total.y) > abs(total.x) * 1.4f && total.y > 0
                            if (vertical == true) { onDragDown(d.y, false); ev.changes.forEach { it.consume() } }
                        }
                    }
                } while (ev.changes.any { it.pressed })
                if (vertical == true) onDragDown(0f, true)
                if (!moved) {
                    val now = System.currentTimeMillis()
                    if (now - lastTap < 300) { lastTap = 0; if (scale > 1.01f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f; onZoom(scale > 1.01f) }
                    // Tipp = Leisten ein/aus (auch beim Video, damit man es ohne Bedienelemente sehen kann).
                    else { lastTap = now; onTap() }
                }
            }
        }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentAlignment = Alignment.Center) {
            AsyncImage(req, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().testTag("viewer.image"))
            if (a.kind == MediaKind.VIDEO && active && state.itemID == a.id && app.player.player != null)
                PlayerSurface(app.player.player, Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_SURFACE_VIEW)
        }
        // Großer Start-Knopf nur, solange das Video nicht läuft; beim Abspielen ist nichts über dem Bild.
        val playing = state.itemID == a.id && state.isPlaying
        if (a.kind == MediaKind.VIDEO && !playing) Box(Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).glassCapsule(elevation = 4.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                if (state.itemID == a.id) app.player.play()
                else { app.player.load(a.id, a.uri, autoplay = true, durationSec = a.duration); if (app.player.state.value.isMuted) app.player.toggleMute() }
                onPlay()
            }.testTag("viewer.play"), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, stringResource(R.string.play), tint = PM.colors.textPrimary, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun InfoContent(a: MediaAsset) {
    val c = PM.colors
    val app = LocalApp.current
    // Kamera aus EXIF (nur lesen, lokal; fehlt sie, gibt es keine Zeile).
    val camera by androidx.compose.runtime.produceState<String?>(app.cameras.cached(a.id), a.id) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.cameras.model(a) }
    }
    val rows = listOfNotNull(
        a.displayName?.let { stringResource(R.string.lib_info_name) to it },
        a.creationDate?.let { stringResource(R.string.lib_info_taken) to DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(it.toEpochMilli())) },
        a.relativePath?.let { stringResource(R.string.lib_info_folder) to it.trimEnd('/') },
        (stringResource(R.string.lib_info_size) to SizeText.size(LocalContext.current, a.size)),
        if (a.pixelWidth > 0 && a.pixelHeight > 0) stringResource(R.string.lib_info_resolution) to "${a.pixelWidth} × ${a.pixelHeight} · ${"%.1f".format(a.pixelWidth * a.pixelHeight / 1_000_000.0)} MP" else null,
        if (a.kind == MediaKind.VIDEO) stringResource(R.string.lib_info_duration) to clock((a.duration * 1000).toLong()) else null,
        a.mimeType?.let { stringResource(R.string.lib_info_type) to it.substringAfter('/').uppercase() },
        camera?.let { stringResource(R.string.lib_info_camera) to it },
        a.dateAdded?.let { stringResource(R.string.lib_info_added) to DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.toEpochMilli())) },
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.xl), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Text(stringResource(R.string.info), style = AppTypography.title, color = c.textPrimary)
        for ((k, v) in rows) Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(k, style = AppTypography.footnote, color = c.textSecondary)
            Text(v, style = AppTypography.body, color = c.textPrimary)
        }
    }
}
