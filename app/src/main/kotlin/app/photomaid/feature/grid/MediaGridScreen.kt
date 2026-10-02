package app.photomaid.feature.grid

import app.photomaid.feature.common.NoticeDialog
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.photomaid.AppModel
import app.photomaid.FeedRequest
import app.photomaid.R
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.model.MediaKind
import app.photomaid.core.organize.MediaFolder
import app.photomaid.core.progress.MonthKey
import app.photomaid.design.AppRadii
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.CoachHost
import app.photomaid.design.CoachStep
import app.photomaid.design.EmptyState
import app.photomaid.design.GlassButton
import app.photomaid.design.GlassIconButton
import app.photomaid.design.GlassToolbar
import app.photomaid.design.AppBackground
import app.photomaid.design.MonthText
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.design.coachTarget
import app.photomaid.feature.collections.collectionTitle
import app.photomaid.feature.quickaccess.assetsFor
import app.photomaid.feature.quickaccess.quickTitle
import app.photomaid.feature.review.FolderSheet
import app.photomaid.feature.review.rememberRecentFolder
import app.photomaid.feature.review.togglePinnedFolder
import app.photomaid.media.MediaAsset
import app.photomaid.media.MediaWriteResult
import app.photomaid.media.reviewableMedia
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Alle Mitglieder einer Quelle (offen UND entschieden) – für die Übersicht. Nur Medien der Mediathek. */
suspend fun sourceMembers(app: AppModel, source: FeedRequest.Source): List<MediaAsset> = withContext(Dispatchers.IO) {
    when (source) {
        is FeedRequest.Source.Category -> app.photos.assets(source.category)
        FeedRequest.Source.AllMedia -> app.photos.reviewableMedia()
        is FeedRequest.Source.Quick -> app.photos.assetsFor(source.item)
        is FeedRequest.Source.Month -> {
            val zone = ZoneId.systemDefault()
            app.photos.reviewableMedia().filter { a -> a.creationDate?.let { MonthKey.of(it, zone) } == source.month }
        }
        is FeedRequest.Source.NewSince -> app.photos.reviewableMedia().filter { a -> a.dateAdded?.toEpochMilli()?.let { it > source.epochMs } == true }
        is FeedRequest.Source.Collection -> app.photos.assets(app.collections.result(source.collection, app).items.map { it.localIdentifier })
        is FeedRequest.Source.Items -> app.photos.assets(source.items.map { it.localIdentifier })
        FeedRequest.Source.Due -> emptyList()
    }
}

/**
 * Übersicht als Raster (Mehrfachauswahl): Tippen wählt, lange drücken und ziehen wählt einen Bereich.
 * „In Ordner verschieben“ (eine Android-Bestätigung für alle) und „Favorit“. Verschieben ist KEINE Entscheidung:
 * kein Behalten, keine Feed-Bewegung, kein Verlaufseintrag. Abzeichen zeigen den Stand aus dem Ledger.
 */
@Composable
fun MediaGridScreen(request: FeedRequest, nav: Nav, onClose: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val scope = rememberCoroutineScope()
    val settings by app.settings.collectAsState()
    val token by app.photos.changeToken.collectAsState()
    val read by app.read.collectAsState()
    var members by remember { mutableStateOf<List<MediaAsset>?>(null) }
    var records by remember { mutableStateOf<Map<String, ItemRecord>>(emptyMap()) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var folderSheet by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<MediaFolder>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(token, read.events.size) {
        val list = sourceMembers(app, request.source)
        records = app.ledger.records(list.map { it.reference }).mapKeys { it.key.localIdentifier }
        members = list
        selection = selection.filter { id -> list.any { it.id == id } }.toSet()
    }
    val title = (request.source as? FeedRequest.Source.Collection)?.let { collectionTitle(it.collection) }
        ?: (request.source as? FeedRequest.Source.Quick)?.let { quickTitle(it.item) }
        ?: (request.source as? FeedRequest.Source.Month)?.let { MonthText.title(it.month) }
        ?: stringResource(request.titleRes)

    val steps = listOf(
        CoachStep("grid.tile", stringResource(R.string.tour_grid_select_title), stringResource(R.string.tour_grid_select_text)),
        CoachStep("grid.note", stringResource(R.string.tour_grid_note_title), stringResource(R.string.tour_grid_note_text)),
    )
    CoachHost(steps, active = !settings.gridTourSeen && !members.isNullOrEmpty(),
        onFinish = { scope.launch { app.updateSettings { it.copy(gridTourSeen = true) } } }, modifier = Modifier.fillMaxSize()) {
    AppBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().testTag("grid")) {
            val list = members
            GlassToolbar(title, Modifier.padding(top = AppSpacing.xxs),
                subtitle = list?.let { if (selection.isEmpty()) pluralStringResource(R.plurals.items_count, it.size, it.size)
                    else pluralStringResource(R.plurals.grid_selected, selection.size, selection.size) },
                leading = { GlassIconButton(Icons.Rounded.Close, stringResource(R.string.close), quiet = true, onClick = onClose) },
                trailing = {
                    if (!list.isNullOrEmpty()) TextButton({ selection = if (selection.size == list.size) emptySet() else list.map { it.id }.toSet() },
                        Modifier.testTag("grid.all")) {
                        Text(stringResource(if (selection.size == list.size) R.string.grid_select_none else R.string.grid_select_all))
                    }
                })
            Text(stringResource(R.string.grid_note), style = AppTypography.caption, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = AppSpacing.pageMargin).coachTarget("grid.note"))
            when {
                list == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Rounded.GridView, stringResource(R.string.grid_empty_title), stringResource(R.string.grid_empty_text))
                }
                else -> {
                    val state = rememberLazyGridState()
                    var dragStart by remember { mutableStateOf<Int?>(null) }
                    var dragBase by remember { mutableStateOf<Set<String>>(emptySet()) }
                    fun indexAt(o: androidx.compose.ui.geometry.Offset): Int? = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                        o.x.toInt() in info.offset.x until info.offset.x + info.size.width && o.y.toInt() in info.offset.y until info.offset.y + info.size.height
                    }?.index
                    LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.weight(1f).fillMaxWidth().padding(horizontal = AppSpacing.xs)
                        .pointerInput(list) {
                            // Lange drücken und ziehen: Bereich vom Startfeld bis zum aktuellen Feld auswählen.
                            detectDragGesturesAfterLongPress(
                                onDragStart = { o -> dragStart = indexAt(o); dragBase = selection
                                    dragStart?.let { selection = dragBase + list[it].id } },
                                onDrag = { ch, _ ->
                                    val s0 = dragStart ?: return@detectDragGesturesAfterLongPress
                                    val i = indexAt(ch.position) ?: return@detectDragGesturesAfterLongPress
                                    val range = if (i >= s0) s0..i else i..s0
                                    selection = dragBase + range.map { list[it].id }
                                },
                                onDragEnd = { dragStart = null }, onDragCancel = { dragStart = null })
                        }.testTag("grid.list"), state = state,
                        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        itemsIndexed(list, key = { _, a -> a.id }) { i, a ->
                            Tile(a, records[a.id], a.id in selection, if (i == 0) Modifier.coachTarget("grid.tile") else Modifier) {
                                selection = if (a.id in selection) selection - a.id else selection + a.id
                            }
                        }
                    }
                }
            }
            if (selection.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                ProminentButton(stringResource(R.string.grid_move), Modifier.weight(1f).testTag("grid.move"), fillWidth = true, enabled = !busy) {
                    folders = null; folderSheet = true
                    scope.launch { folders = withContext(Dispatchers.IO) { runCatching { app.photos.folders() }.getOrDefault(emptyList()) } }
                }
                val allFav = members.orEmpty().filter { it.id in selection }.all { it.isFavorite }
                GlassIconButton(if (allFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(if (allFav) R.string.fav_remove else R.string.fav_add),
                    Modifier.testTag("grid.favorite"), enabled = !busy) {
                    busy = true
                    scope.launch {
                        val r = app.photos.setFavoriteMany(selection.toList(), !allFav)
                        busy = false
                        if (r.values.any { it is MediaWriteResult.Failed }) message = app.string(R.string.fav_failed)
                    }
                }
            }
        }
    }
    }
    if (folderSheet) {
        val chosen = members.orEmpty().filter { it.id in selection }
        val kind = if (chosen.isNotEmpty() && chosen.all { it.kind == MediaKind.VIDEO }) MediaKind.VIDEO else MediaKind.PHOTO
        FolderSheet(kind, null, folders, settings.pinnedFolders, settings.recentFolders,
            onTogglePin = { p -> scope.launch { togglePinnedFolder(app, p) } },
            onPick = { path ->
                busy = true
                scope.launch {
                    val ids = chosen.map { it.id }
                    val r = app.photos.moveManyToFolder(ids, path)
                    busy = false
                    val moved = r.values.count { it == MediaWriteResult.Done }
                    if (moved > 0) rememberRecentFolder(app, path)
                    val name = MediaFolder(path, 0).name
                    message = when {
                        r.values.all { it == MediaWriteResult.Cancelled } -> null
                        moved == ids.size -> app.context.resources.getQuantityString(R.plurals.grid_moved, moved, moved, name)
                        moved > 0 -> app.string(R.string.grid_moved_partial, moved, ids.size, name)
                        else -> app.string(R.string.folder_failed)
                    }
                    if (moved > 0) selection = emptySet()
                }
            }, onDismiss = { folderSheet = false }, title = pluralStringResource(R.plurals.grid_move_title, selection.size, selection.size))
    }
    message?.let { m -> NoticeDialog(m) { message = null } }
}

@Composable
private fun Tile(a: MediaAsset, r: ItemRecord?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = PM.colors
    val px = with(LocalDensity.current) { 120.dp.roundToPx() }
    val kind = stringResource(when (a.kind) { MediaKind.VIDEO -> R.string.video; MediaKind.SCREENSHOT -> R.string.screenshot; else -> R.string.photo })
    val stateLabel = when (r?.disposition) {
        Disposition.KEPT -> stringResource(R.string.dir_keep)
        Disposition.MARKED_FOR_CLEANUP -> stringResource(R.string.dir_trash)
        Disposition.LATER -> stringResource(R.string.dir_later)
        else -> null
    }
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(c.backgroundTint)
        .border(if (selected) 3.dp else 0.dp, if (selected) c.accent else Color.Transparent, RoundedCornerShape(4.dp))
        .clickable(role = Role.Checkbox, onClick = onClick)
        .semantics(mergeDescendants = true) { this.selected = selected; contentDescription = listOfNotNull(kind, stateLabel).joinToString(", ") }
        .testTag("grid.tile")) {
        AsyncImage(ImageRequest.Builder(LocalContext.current).data(a.uri).size(px, px).memoryCacheKey("grid:${a.id}").build(),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (selected) Box(Modifier.fillMaxSize().background(c.accent.copy(alpha = 0.18f)))
        Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Outlined.Circle, null, tint = if (selected) c.accent else Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(if (selected) Color.White else Color.Transparent, CircleShape))
        Row(Modifier.align(Alignment.BottomStart).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val badge = when (r?.disposition) {
                Disposition.KEPT -> Icons.Rounded.ArrowUpward to c.keep
                Disposition.MARKED_FOR_CLEANUP -> Icons.Rounded.Delete to c.cleanup
                Disposition.LATER -> Icons.Rounded.Schedule to c.later
                else -> null
            }
            badge?.let { (icon, tint) -> Box(Modifier.size(20.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(13.dp)) } }
            if (a.isFavorite) Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(18.dp))
            if (a.kind == MediaKind.VIDEO) Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}
