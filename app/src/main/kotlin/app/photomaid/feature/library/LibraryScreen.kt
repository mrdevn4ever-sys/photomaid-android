package app.photomaid.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.library.Timeline
import app.photomaid.core.library.TimelineKey
import app.photomaid.core.library.TimelineLevel
import app.photomaid.core.model.MediaKind
import app.photomaid.core.organize.MediaFolder
import app.photomaid.core.studio.CollageProject
import app.photomaid.design.AppBackground
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.EmptyState
import app.photomaid.design.GlassIconButton
import app.photomaid.design.LargeTitleHeader
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.design.glassCapsule
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.feature.editor.Chip
import app.photomaid.feature.review.FolderSheet
import app.photomaid.feature.review.rememberRecentFolder
import app.photomaid.feature.review.togglePinnedFolder
import app.photomaid.feature.studio.CollageRequest
import app.photomaid.media.MediaAsset
import app.photomaid.media.MediaWriteResult
import app.photomaid.media.reviewableMedia
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.TabBarClearance
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt
import app.photomaid.feature.common.ConfirmationSheet
import app.photomaid.feature.editor.clock

/** Zelle der Zeitachse: Kopf eines Abschnitts oder ein Medium. */
private sealed interface Cell {
    data class Header(val key: TimelineKey, val count: Int) : Cell
    data class Tile(val asset: MediaAsset) : Cell
}

/**
 * Tab „Fotos“: alle Medien chronologisch (Tag · Monat · Jahr, zwei Finger wechseln die Stufe), Filter, Suche ohne
 * Akzente, schneller Scroll-Griff mit Datum, Auswahl. Ansehen ist nie eine Entscheidung; nur „In den Papierkorb“ in
 * der Auswahl entscheidet (PhotoMaid-Papierkorb, nichts gelöscht).
 */
@Composable
fun LibraryScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val ctx = LocalContext.current
    val access by app.photos.access.collectAsState()
    val token by app.photos.changeToken.collectAsState()
    val settings by app.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var level by rememberSaveable { mutableStateOf(TimelineLevel.DAY) }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var all by remember { mutableStateOf<List<MediaAsset>?>(null) }
    var search by remember { mutableStateOf<LibrarySearch?>(null) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var message by remember { mutableStateOf<String?>(null) }
    var folderSheet by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<MediaFolder>?>(null) }
    var confirmTrash by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }

    LaunchedEffect(token, access) {
        if (!access.canRead) { all = emptyList(); return@LaunchedEffect }
        val list = withContext(Dispatchers.IO) { app.photos.reviewableMedia() }
        all = list
        search = withContext(Dispatchers.Default) { LibrarySearch(ctx, list) }
        selection = selection.filter { id -> list.any { it.id == id } }.toSet()
    }
    val shown = remember(all, filter, query, search) {
        val base = all.orEmpty().filter(filter::accepts)
        search?.filter(base, query) ?: base
    }
    val cells = remember(shown, level) {
        Timeline.sections(shown, { it.creationDate }, level, zone).flatMap { s -> listOf<Cell>(Cell.Header(s.key, s.items.size)) + s.items.map { Cell.Tile(it) } }
    }
    val ordered = remember(cells) { cells.filterIsInstance<Cell.Tile>().map { it.asset } }
    val grid = rememberLazyGridState()
    // Stufenwechsel: dasselbe Medium bleibt oben sichtbar.
    var anchor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cells) {
        val id = anchor ?: return@LaunchedEffect
        val i = cells.indexOfFirst { it is Cell.Tile && it.asset.id == id }
        if (i >= 0) grid.scrollToItem((i - 1).coerceAtLeast(0))
        anchor = null
    }
    fun open(a: MediaAsset) = nav.openViewer(ViewerRequest(UUID.randomUUID().toString(), ordered, ordered.indexOfFirst { it.id == a.id }.coerceAtLeast(0)))
    val chosen = ordered.filter { it.id in selection }

    AppBackground(Modifier.testTag("library")) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.padding(horizontal = AppSpacing.pageMargin)) {
                if (selection.isEmpty()) LargeTitleHeader(stringResource(R.string.tab_library)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
                        GlassIconButton(Icons.Rounded.Search, stringResource(R.string.lib_search), Modifier.testTag("lib.search")) { searching = !searching; if (!searching) query = "" }
                        GlassIconButton(Icons.Rounded.Settings, stringResource(R.string.settings), onClick = nav::openSettings)
                    }
                } else Row(Modifier.fillMaxWidth().padding(top = AppSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Text(pluralStringResource(R.plurals.grid_selected, selection.size, selection.size), style = AppTypography.title, color = c.textPrimary,
                        modifier = Modifier.weight(1f).semantics { heading() }.testTag("lib.selcount"))
                    TextButton({ selection = if (selection.size == ordered.size) emptySet() else ordered.map { it.id }.toSet() }) {
                        Text(stringResource(if (selection.size == ordered.size) R.string.grid_select_none else R.string.grid_select_all))
                    }
                    GlassIconButton(Icons.Rounded.Close, stringResource(R.string.done), Modifier.testTag("lib.seldone")) { selection = emptySet() }
                }
                if (searching) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = AppSpacing.xs).testTag("lib.query"),
                    placeholder = { Text(stringResource(R.string.lib_search_hint)) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) GlassIconButton(Icons.Rounded.Close, stringResource(R.string.lib_search_clear), quiet = true) { query = "" } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), shape = RoundedCornerShape(16.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = AppSpacing.xs).testTag("lib.filters"),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    for (f in LibraryFilter.entries) Chip(stringResource(f.label), filter == f, Modifier.testTag("lib.filter.${f.name}")) { filter = f }
                    Box(Modifier.width(1.dp).height(24.dp).background(c.textSecondary.copy(alpha = 0.3f)))
                    for (l in TimelineLevel.entries) Chip(stringResource(levelLabel(l)), level == l, Modifier.testTag("lib.level.${l.name}")) {
                        anchor = firstVisibleId(grid, cells); level = l }
                }
            }
            val list = all
            when {
                !access.canRead -> Box(Modifier.fillMaxSize().padding(AppSpacing.pageMargin), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                        EmptyState(Icons.Rounded.PhotoLibrary, stringResource(R.string.today_access_title), stringResource(R.string.today_access_default))
                        ProminentButton(stringResource(R.string.privacy_allow), onClick = nav::openPrivacy)
                    }
                }
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(AppSpacing.pageMargin).testTag("lib.empty"), contentAlignment = Alignment.Center) {
                    if (query.isNotBlank()) EmptyState(Icons.Rounded.Search, stringResource(R.string.lib_search_none_title), stringResource(R.string.lib_search_none_text, query))
                    else EmptyState(Icons.Rounded.PhotoLibrary, stringResource(R.string.picker_empty_title), stringResource(R.string.lib_empty_text))
                }
                else -> Box(Modifier.fillMaxSize()) {
                    val pinchLabel = stringResource(R.string.lib_level_finer); val coarseLabel = stringResource(R.string.lib_level_coarser)
                    LazyVerticalGrid(GridCells.Fixed(level.columns), state = grid,
                        modifier = Modifier.fillMaxSize().testTag("lib.grid")
                            .semantics { customActions = listOf(
                                CustomAccessibilityAction(pinchLabel) { anchor = firstVisibleId(grid, cells); level = level.finer(); true },
                                CustomAccessibilityAction(coarseLabel) { anchor = firstVisibleId(grid, cells); level = level.coarser(); true }) }
                            .pointerInput(level) {
                                // Zwei Finger: Stufe wechseln (auseinander = feiner). Ein Finger bleibt beim Scrollen.
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    var z = 1f
                                    do {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        if (ev.changes.count { it.pressed } >= 2) {
                                            z *= ev.calculateZoom()
                                            ev.changes.forEach { it.consume() }
                                            val next = if (z > 1.3f) level.finer() else if (z < 0.77f) level.coarser() else null
                                            if (next != null && next != level) {
                                                anchor = firstVisibleId(grid, cells); level = next
                                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); z = 1f
                                            }
                                        }
                                    } while (ev.changes.any { it.pressed })
                                }
                            },
                        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = TabBarClearance + 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(cells, key = { _, cell -> when (cell) { is Cell.Header -> "h:${cell.key}"; is Cell.Tile -> cell.asset.id } },
                            span = { _, cell -> if (cell is Cell.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                            contentType = { _, cell -> if (cell is Cell.Header) 0 else 1 }) { _, cell ->
                            when (cell) {
                                is Cell.Header -> SectionHeader(timelineTitle(ctx, cell.key, level), cell.count)
                                is Cell.Tile -> LibraryTile(cell.asset, level, cell.asset.id in selection, selection.isNotEmpty(),
                                    onClick = { if (selection.isNotEmpty()) selection = selection.toggle(cell.asset.id) else open(cell.asset) },
                                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); selection = selection.toggle(cell.asset.id) })
                            }
                        }
                    }
                    if (ordered.size > 60) FastScroller(grid, cells, level, Modifier.align(Alignment.CenterEnd))
                    if (selection.isNotEmpty()) SelectionBar(chosen, busy = false, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = TabBarClearance - 20.dp),
                        onFavorite = {
                            val target = !chosen.all { it.isFavorite }
                            scope.launch {
                                val r = app.photos.setFavoriteMany(chosen.map { it.id }, target)
                                if (r.values.any { it is MediaWriteResult.Failed }) message = app.string(R.string.fav_failed)
                            }
                        },
                        onMove = { folders = null; folderSheet = true; scope.launch { folders = withContext(Dispatchers.IO) { runCatching { app.photos.folders() }.getOrDefault(emptyList()) } } },
                        onCollage = {
                            val id = UUID.randomUUID().toString()
                            nav.openCollage(CollageRequest(id, CollageProject.create(id, chosen.filter { it.kind != MediaKind.VIDEO }.map { it.id }.take(CollageProject.MAX)), fromDraft = false))
                        },
                        onTrash = { confirmTrash = true })
                }
            }
        }
    }

    if (confirmTrash) ConfirmationSheet(
        title = pluralStringResource(R.plurals.lib_trash_title, chosen.size, chosen.size),
        message = stringResource(if (settings.protectFavorites && chosen.any { it.isFavorite }) R.string.lib_trash_text_fav else R.string.lib_trash_text),
        actionTitle = stringResource(R.string.lib_trash_action),
        onConfirm = {
            val list = if (settings.protectFavorites) chosen.filterNot { it.isFavorite } else chosen
            scope.launch { markForCleanup(app, list); selection = emptySet()
                message = app.context.resources.getQuantityString(R.plurals.lib_trashed, list.size, list.size) }
        }, onDismiss = { confirmTrash = false })
    if (folderSheet) {
        val kind = if (chosen.isNotEmpty() && chosen.all { it.kind == MediaKind.VIDEO }) MediaKind.VIDEO else MediaKind.PHOTO
        FolderSheet(kind, null, folders, settings.pinnedFolders, settings.recentFolders,
            onTogglePin = { p -> scope.launch { togglePinnedFolder(app, p) } },
            onPick = { path ->
                folderSheet = false
                scope.launch {
                    val ids = chosen.map { it.id }
                    val r = app.photos.moveManyToFolder(ids, path)
                    val moved = r.values.count { it == MediaWriteResult.Done }
                    if (moved > 0) { rememberRecentFolder(app, path); selection = emptySet() }
                    val name = MediaFolder(path, 0).name
                    message = when {
                        r.values.all { it == MediaWriteResult.Cancelled } -> null
                        moved == ids.size -> app.context.resources.getQuantityString(R.plurals.grid_moved, moved, moved, name)
                        moved > 0 -> app.string(R.string.grid_moved_partial, moved, ids.size, name)
                        else -> app.string(R.string.folder_failed)
                    }
                }
            }, onDismiss = { folderSheet = false }, title = pluralStringResource(R.plurals.grid_move_title, selection.size, selection.size))
    }
    message?.let { m -> NoticeDialog(m) { message = null } }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

private fun levelLabel(l: TimelineLevel) = when (l) { TimelineLevel.DAY -> R.string.lib_level_day; TimelineLevel.MONTH -> R.string.lib_level_month; TimelineLevel.YEAR -> R.string.lib_level_year }

private fun firstVisibleId(grid: androidx.compose.foundation.lazy.grid.LazyGridState, cells: List<Cell>): String? =
    grid.layoutInfo.visibleItemsInfo.asSequence().mapNotNull { cells.getOrNull(it.index) as? Cell.Tile }.firstOrNull()?.asset?.id

@Composable
private fun SectionHeader(title: String, count: Int) {
    val c = PM.colors
    Row(Modifier.fillMaxWidth().padding(start = AppSpacing.pageMargin - 2.dp, end = AppSpacing.pageMargin, top = AppSpacing.s, bottom = AppSpacing.xxs)
        .semantics(mergeDescendants = true) { heading() }.testTag("lib.header"), verticalAlignment = Alignment.Bottom) {
        Text(title, style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text(pluralStringResource(R.plurals.items_count, count, count), style = AppTypography.footnote, color = c.textSecondary)
    }
}

@Composable
private fun LibraryTile(a: MediaAsset, level: TimelineLevel, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = PM.colors
    val px = with(LocalDensity.current) { (if (level == TimelineLevel.DAY) 140.dp else if (level == TimelineLevel.MONTH) 90.dp else 56.dp).roundToPx() }
    val label = stringResource(when (a.kind) { MediaKind.VIDEO -> R.string.video; MediaKind.SCREENSHOT -> R.string.cat_screenshots; else -> R.string.photo })
    val date = a.creationDate?.let { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it.toEpochMilli())) }
    Box(Modifier.aspectRatio(1f).background(c.backgroundTint)
        .combinedClickable(role = if (selecting) Role.Checkbox else Role.Image, onClick = onClick, onLongClick = onLongClick)
        .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(label, date, if (a.isFavorite) "♥" else null).joinToString(", "); if (selecting) this.selected = selected }
        .testTag("lib.tile")) {
        AsyncImage(ImageRequest.Builder(LocalContext.current).data(a.uri).size(px, px).memoryCacheKey("lib:${a.id}:$px").build(),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (a.kind == MediaKind.VIDEO && level != TimelineLevel.YEAR) Row(Modifier.align(Alignment.BottomStart).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.size(16.dp))
            if (level == TimelineLevel.DAY) Text(clock((a.duration * 1000).toLong()), style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White, modifier = Modifier.padding(start = 3.dp))
        }
        if (a.isFavorite && level != TimelineLevel.YEAR) Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(14.dp))
        if (selecting) {
            if (selected) Box(Modifier.fillMaxSize().border(3.dp, c.accent))
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(if (selected) c.accentFill else Color.Black.copy(alpha = 0.25f))
                .border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun SelectionBar(chosen: List<MediaAsset>, busy: Boolean, modifier: Modifier, onFavorite: () -> Unit, onMove: () -> Unit, onCollage: () -> Unit, onTrash: () -> Unit) {
    val allFav = chosen.isNotEmpty() && chosen.all { it.isFavorite }
    Row(modifier.glassCapsule(elevation = 6.dp).padding(horizontal = AppSpacing.xs, vertical = 4.dp).testTag("lib.selbar"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
        GlassIconButton(if (allFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(if (allFav) R.string.fav_remove else R.string.fav_add),
            Modifier.testTag("lib.sel.fav"), enabled = !busy, quiet = true, onClick = onFavorite)
        GlassIconButton(Icons.Outlined.Folder, stringResource(R.string.grid_move), Modifier.testTag("lib.sel.move"), enabled = !busy, quiet = true, onClick = onMove)
        if (chosen.count { it.kind != MediaKind.VIDEO } >= 2) GlassIconButton(Icons.Rounded.Dashboard, stringResource(R.string.grid_collage), Modifier.testTag("lib.sel.collage"),
            enabled = !busy, quiet = true, onClick = onCollage)
        GlassIconButton(Icons.Rounded.Delete, stringResource(R.string.lib_trash_action), Modifier.testTag("lib.sel.trash"), enabled = !busy, quiet = true, onClick = onTrash)
    }
}

/** Griff am rechten Rand: ziehen springt durch die Zeitachse, eine Blase zeigt den Abschnitt (Tag/Monat/Jahr). */
@Composable
private fun FastScroller(grid: androidx.compose.foundation.lazy.grid.LazyGridState, cells: List<Cell>, level: TimelineLevel, modifier: Modifier) {
    val c = PM.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var frac by remember { mutableStateOf(0f) }
    val first = grid.firstVisibleItemIndex
    val shownFrac = if (dragging) frac else first.toFloat() / cells.size.coerceAtLeast(1)
    BoxWithConstraints(modifier.fillMaxHeight().width(56.dp).padding(vertical = AppSpacing.l).testTag("lib.scroller")) {
        val hPx = with(LocalDensity.current) { maxHeight.toPx() }
        val thumbH = with(LocalDensity.current) { 44.dp.toPx() }
        val y = (shownFrac * (hPx - thumbH)).coerceIn(0f, hPx - thumbH)
        Box(Modifier.fillMaxSize().pointerInput(cells.size) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (down.position.x < size.width - 36.dp.toPx()) return@awaitEachGesture
                down.consume(); dragging = true
                var pos = down.position.y
                while (true) {
                    val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    pos += ch.positionChange().y; ch.consume()
                    frac = (pos / hPx).coerceIn(0f, 1f)
                    scope.launch { grid.scrollToItem((frac * (cells.size - 1)).roundToInt()) }
                }
                dragging = false
            }
        }) {
            Box(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y.roundToInt()) }.padding(end = 4.dp).size(width = 8.dp, height = 44.dp)
                .clip(CircleShape).background(if (dragging) c.accent else c.textSecondary.copy(alpha = 0.5f)))
            if (dragging) {
                val i = (frac * (cells.size - 1)).roundToInt()
                val header = cells.subList(0, (i + 1).coerceAtMost(cells.size)).lastOrNull { it is Cell.Header } as? Cell.Header
                if (header != null) Text(timelineTitle(ctx, header.key, if (level == TimelineLevel.DAY) TimelineLevel.MONTH else level),
                    Modifier.align(Alignment.TopEnd).offset { IntOffset(-64.dp.roundToPx(), y.roundToInt()) }.glassCapsule(elevation = 4.dp)
                        .padding(horizontal = AppSpacing.s, vertical = 6.dp).testTag("lib.scroller.label"),
                    style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, softWrap = false)
            }
        }
    }
}
