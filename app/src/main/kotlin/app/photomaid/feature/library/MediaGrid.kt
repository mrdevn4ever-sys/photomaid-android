package app.photomaid.feature.library

import app.photomaid.feature.document.PdfRequest
import app.photomaid.feature.workflow.WorkflowRequest
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.PictureAsPdf
import app.photomaid.system.MediaIntents
import app.photomaid.feature.organize.TagSheet
import app.photomaid.feature.organize.AlbumPickerSheet
import app.photomaid.feature.organize.OrganizeMenuItems
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.library.TimelineLevel
import app.photomaid.core.model.MediaKind
import app.photomaid.core.organize.MediaFolder
import app.photomaid.core.studio.CollageProject
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassIconButton
import app.photomaid.design.PM
import app.photomaid.design.glassCapsule
import app.photomaid.feature.common.ConfirmationSheet
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.feature.editor.clock
import app.photomaid.feature.review.FolderSheet
import app.photomaid.feature.review.rememberRecentFolder
import app.photomaid.feature.review.togglePinnedFolder
import app.photomaid.feature.studio.CollageRequest
import app.photomaid.feature.studio.MovieRequest
import app.photomaid.feature.studio.movieFrom
import app.photomaid.media.MediaAsset
import app.photomaid.media.MediaWriteResult
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.TabBarClearance
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

/** Zelle eines Medienrasters: Abschnittskopf (Titel vorberechnet) oder ein Medium. */
internal sealed interface GridCell {
    /** [scrollLabel]: Text der Blase am Scroll-Griff (bei Tagen der Monat). */
    data class Header(val id: String, val title: String, val scrollLabel: String, val count: Int) : GridCell
    data class Tile(val asset: MediaAsset) : GridCell
}

internal fun List<GridCell>.tiles(): List<MediaAsset> = filterIsInstance<GridCell.Tile>().map { it.asset }

internal fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

/**
 * Gemeinsames Medienraster (Zeitachse, Favoriten, Ordner): Kacheln mit Abschnittsköpfen, Auswahl per langem Druck,
 * Scroll-Griff ab 60 Medien. [level] bestimmt Spalten und Kachelgröße; [onLevel] (optional) erlaubt Zwei-Finger-Zoom.
 */
@Composable
internal fun MediaGridPane(
    cells: List<GridCell>,
    level: TimelineLevel,
    selection: Set<String>,
    onSelection: (Set<String>) -> Unit,
    onOpen: (MediaAsset) -> Unit,
    modifier: Modifier = Modifier,
    grid: LazyGridState = rememberLazyGridState(),
    onLevel: ((TimelineLevel, String?) -> Unit)? = null,
    bottomPadding: androidx.compose.ui.unit.Dp = TabBarClearance + 48.dp,
    tag: String = "lib.grid",
    /** Medien mit RAW-Partner (Abzeichen „RAW“). */
    rawIds: Set<String> = emptySet(),
) {
    val haptics = LocalHapticFeedback.current
    Box(modifier.fillMaxSize()) {
        val finer = stringResource(R.string.lib_level_finer); val coarser = stringResource(R.string.lib_level_coarser)
        var gridMod = Modifier.fillMaxSize().testTag(tag)
        if (onLevel != null) gridMod = gridMod
            .semantics { customActions = listOf(
                CustomAccessibilityAction(finer) { onLevel(level.finer(), firstVisibleId(grid, cells)); true },
                CustomAccessibilityAction(coarser) { onLevel(level.coarser(), firstVisibleId(grid, cells)); true }) }
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
                                onLevel(next, firstVisibleId(grid, cells))
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); z = 1f
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
        LazyVerticalGrid(GridCells.Fixed(level.columns), state = grid, modifier = gridMod,
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(cells, key = { _, cell -> when (cell) { is GridCell.Header -> "h:${cell.id}"; is GridCell.Tile -> cell.asset.id } },
                span = { _, cell -> if (cell is GridCell.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { _, cell -> if (cell is GridCell.Header) 0 else 1 }) { _, cell ->
                when (cell) {
                    is GridCell.Header -> SectionHeader(cell.title, cell.count)
                    is GridCell.Tile -> LibraryTile(cell.asset, level, cell.asset.id in selection, selection.isNotEmpty(), raw = cell.asset.id in rawIds,
                        onClick = { if (selection.isNotEmpty()) onSelection(selection.toggle(cell.asset.id)) else onOpen(cell.asset) },
                        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onSelection(selection.toggle(cell.asset.id)) })
                }
            }
        }
        if (cells.count { it is GridCell.Tile } > 60) FastScroller(grid, cells, Modifier.align(Alignment.CenterEnd))
    }
}

internal fun firstVisibleId(grid: LazyGridState, cells: List<GridCell>): String? =
    grid.layoutInfo.visibleItemsInfo.asSequence().mapNotNull { cells.getOrNull(it.index) as? GridCell.Tile }.firstOrNull()?.asset?.id

@Composable
private fun SectionHeader(title: String, count: Int) {
    val c = PM.colors
    Row(Modifier.fillMaxWidth().padding(start = AppSpacing.pageMargin - 2.dp, end = AppSpacing.pageMargin, top = AppSpacing.s, bottom = AppSpacing.xxs)
        .semantics(mergeDescendants = true) { heading() }.testTag("lib.header"), verticalAlignment = Alignment.Bottom) {
        Text(title, style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(pluralStringResource(R.plurals.items_count, count, count), style = AppTypography.footnote, color = c.textSecondary)
    }
}

@Composable
private fun LibraryTile(a: MediaAsset, level: TimelineLevel, selected: Boolean, selecting: Boolean, raw: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit) {
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
        if (raw && level != TimelineLevel.YEAR) Text("RAW", Modifier.align(Alignment.TopStart).padding(4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp, vertical = 1.dp).testTag("lib.tile.raw"),
            style = AppTypography.caption.copy(fontWeight = FontWeight.Bold), color = Color.White)
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

/** Griff am rechten Rand: ziehen springt durch das Raster, eine Blase zeigt den Abschnitt. */
@Composable
private fun FastScroller(grid: LazyGridState, cells: List<GridCell>, modifier: Modifier) {
    val c = PM.colors
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
                val header = cells.subList(0, (i + 1).coerceAtMost(cells.size)).lastOrNull { it is GridCell.Header } as? GridCell.Header
                if (header != null) Text(header.scrollLabel,
                    Modifier.align(Alignment.TopEnd).offset { IntOffset(-64.dp.roundToPx(), y.roundToInt()) }.glassCapsule(elevation = 4.dp)
                        .padding(horizontal = AppSpacing.s, vertical = 6.dp).testTag("lib.scroller.label"),
                    style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * Aktionen für eine Auswahl (Favorit, Verschieben, Collage, Ausblenden, GaleriQ-Papierkorb) samt Blättern und
 * Meldungen. Nichts davon ist eine Entscheidung außer „In den Papierkorb“ (Ledger, Favoritenschutz).
 * [unhide] = Ansicht „Ausgeblendet“: statt Ausblenden „Wieder einblenden“, kein Papierkorb.
 */
@Composable
internal fun SelectionActions(
    nav: Nav,
    chosen: List<MediaAsset>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    unhide: Boolean = false,
    /** RAW-Partner gehen bei Papierkorb, Ausblenden und Verschieben mit. */
    raw: RawIndex = RawIndex.EMPTY,
    /** Zusätzliche erste Aktion (z. B. „Aus Album entfernen“) – nie eine Entscheidung. */
    extra: SelectionExtra? = null,
) {
    val app = LocalApp.current
    val settings by app.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var folderSheet by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<MediaFolder>?>(null) }
    var confirmTrash by remember { mutableStateOf(false) }
    var confirmHide by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var albumSheet by remember { mutableStateOf(false) }
    var tagSheet by remember { mutableStateOf(false) }
    val allFav = chosen.isNotEmpty() && chosen.all { it.isFavorite }

    // Passt die Leiste nicht in die Breite (viele Aktionen, große Schrift), scrollt sie – nichts wird abgeschnitten.
    if (chosen.isNotEmpty()) Row(modifier.navigationBarsPadding().padding(horizontal = AppSpacing.s).glassCapsule(elevation = 6.dp)
        .horizontalScroll(rememberScrollState()).padding(horizontal = AppSpacing.xs, vertical = 4.dp).testTag("lib.selbar"),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
        if (unhide) {
            GlassIconButton(Icons.Rounded.Visibility, stringResource(R.string.lib_unhide), Modifier.testTag("lib.sel.unhide"), quiet = true) {
                val ids = chosen.map { it.id }
                scope.launch { setMediaHidden(app, ids, false); onClear()
                    message = app.context.resources.getQuantityString(R.plurals.lib_unhidden, ids.size, ids.size) }
            }
            return@Row
        }
        extra?.let { e -> GlassIconButton(e.icon, e.label, Modifier.testTag(e.tag), quiet = true, onClick = e.onClick) }
        GlassIconButton(if (allFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(if (allFav) R.string.fav_remove else R.string.fav_add),
            Modifier.testTag("lib.sel.fav"), quiet = true) {
            val target = !allFav
            scope.launch {
                val r = app.photos.setFavoriteMany(chosen.map { it.id }, target)
                if (r.values.any { it is MediaWriteResult.Failed }) message = app.string(R.string.fav_failed)
            }
        }
        GlassIconButton(Icons.Outlined.Folder, stringResource(R.string.grid_move), Modifier.testTag("lib.sel.move"), quiet = true) {
            folders = null; folderSheet = true
            scope.launch { folders = withContext(Dispatchers.IO) { runCatching { app.photos.folders() }.getOrDefault(emptyList()) } }
        }
        if (chosen.count { it.kind != MediaKind.VIDEO } >= 2) GlassIconButton(Icons.Rounded.Dashboard, stringResource(R.string.grid_collage), Modifier.testTag("lib.sel.collage"), quiet = true) {
            val id = UUID.randomUUID().toString()
            nav.openCollage(CollageRequest(id, CollageProject.create(id, chosen.filter { it.kind != MediaKind.VIDEO }.map { it.id }.take(CollageProject.MAX)), fromDraft = false))
        }
        // P6: zwei oder mehr Medien → Video zusammenfügen (Reihenfolge wie ausgewählt angezeigt).
        if (chosen.size >= 2) GlassIconButton(Icons.Rounded.Movie, stringResource(R.string.movie_title), Modifier.testTag("lib.sel.movie"), quiet = true) {
            nav.openMovie(MovieRequest(UUID.randomUUID().toString(), movieFrom(chosen, false), false))
        }
        GlassIconButton(Icons.Rounded.VisibilityOff, stringResource(R.string.lib_hide), Modifier.testTag("lib.sel.hide"), quiet = true) { confirmHide = true }
        GlassIconButton(Icons.Rounded.Delete, stringResource(R.string.lib_trash_action), Modifier.testTag("lib.sel.trash"), quiet = true) { confirmTrash = true }
        // Organisieren und Teilen (nie eine Entscheidung).
        Box {
            GlassIconButton(Icons.Rounded.MoreHoriz, stringResource(R.string.more_actions), Modifier.testTag("lib.sel.more"), quiet = true) { more = true }
            DropdownMenu(more, onDismissRequest = { more = false }) {
                OrganizeMenuItems(chosen, close = { more = false }, onAlbum = { albumSheet = true }, onTags = { tagSheet = true }) { message = it }
                DropdownMenuItem(text = { Text(stringResource(R.string.wf_apply)) }, leadingIcon = { Icon(Icons.Rounded.AutoMode, null) },
                    modifier = Modifier.testTag("lib.sel.workflow"), onClick = {
                        more = false; nav.openWorkflows(WorkflowRequest(UUID.randomUUID().toString(), chosen.map { it.id }))
                    })
                if (chosen.any { it.kind != MediaKind.VIDEO }) DropdownMenuItem(text = { Text(stringResource(R.string.pdf_from_selection)) },
                    leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null) }, modifier = Modifier.testTag("lib.sel.pdf"), onClick = {
                        more = false; nav.openPdf(PdfRequest(UUID.randomUUID().toString(), chosen.map { it.id }))
                    })
                DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) },
                    modifier = Modifier.testTag("lib.sel.share"), onClick = {
                        more = false
                        runCatching { app.context.startActivity(MediaIntents.shareMany(chosen.map { it.uri }, chosen.map { MediaIntents.mimeType(it.kind, it.mimeType) },
                            app.string(R.string.share)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    })
            }
        }
    }
    if (albumSheet) AlbumPickerSheet(chosen, onDismiss = { albumSheet = false }) { m -> albumSheet = false; message = m; onClear() }
    if (tagSheet) TagSheet(chosen) { tagSheet = false }

    if (confirmHide) ConfirmationSheet(
        title = pluralStringResource(R.plurals.lib_hide_title, chosen.size, chosen.size),
        message = stringResource(R.string.lib_hide_text),
        actionTitle = stringResource(R.string.lib_hide), destructive = false,
        onConfirm = {
            val ids = raw.withPartners(chosen).map { it.id }
            scope.launch { setMediaHidden(app, ids, true); onClear()
                message = app.context.resources.getQuantityString(R.plurals.lib_hidden_done, ids.size, ids.size) }
        }, onDismiss = { confirmHide = false })
    if (confirmTrash) ConfirmationSheet(
        title = pluralStringResource(R.plurals.lib_trash_title, chosen.size, chosen.size),
        message = stringResource(if (settings.protectFavorites && chosen.any { it.isFavorite }) R.string.lib_trash_text_fav else R.string.lib_trash_text),
        actionTitle = stringResource(R.string.lib_trash_action),
        onConfirm = {
            val list = raw.withPartners(if (settings.protectFavorites) chosen.filterNot { it.isFavorite } else chosen)
            scope.launch { markForCleanup(app, list); onClear()
                message = app.context.resources.getQuantityString(R.plurals.lib_trashed, list.size, list.size) }
        }, onDismiss = { confirmTrash = false })
    if (folderSheet) {
        val kind = if (chosen.isNotEmpty() && chosen.all { it.kind == MediaKind.VIDEO }) MediaKind.VIDEO else MediaKind.PHOTO
        FolderSheet(kind, null, folders, settings.pinnedFolders, settings.recentFolders,
            onTogglePin = { p -> scope.launch { togglePinnedFolder(app, p) } },
            onPick = { path ->
                folderSheet = false
                scope.launch { message = moveAll(app, raw.withPartners(chosen).map { it.id }, path) { onClear() } }
            }, onDismiss = { folderSheet = false }, title = pluralStringResource(R.plurals.grid_move_title, chosen.size, chosen.size))
    }
    message?.let { m -> NoticeDialog(m) { message = null } }
}

/** Zusätzliche Aktion in der Auswahlleiste (z. B. „Aus Album entfernen“). */
data class SelectionExtra(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val tag: String, val onClick: () -> Unit)

/** Mehrere Medien verschieben (eine Systembestätigung); liefert die Meldung oder `null` bei Abbruch. */
internal suspend fun moveAll(app: app.photomaid.AppModel, ids: List<String>, path: String, onMoved: () -> Unit = {}): String? {
    val r = app.photos.moveManyToFolder(ids, path)
    val moved = r.values.count { it == MediaWriteResult.Done }
    if (moved > 0) { rememberRecentFolder(app, path); onMoved() }
    val name = MediaFolder(path, 0).name
    return when {
        r.values.all { it == MediaWriteResult.Cancelled } -> null
        moved == ids.size -> app.context.resources.getQuantityString(R.plurals.grid_moved, moved, moved, name)
        moved > 0 -> app.string(R.string.grid_moved_partial, moved, ids.size, name)
        else -> app.string(R.string.folder_failed)
    }
}

/** Kopf in der Auswahl: Anzahl, Alle/Keine, Fertig. */
@Composable
internal fun SelectionHeader(count: Int, total: Int, onAll: () -> Unit, onDone: () -> Unit) {
    val c = PM.colors
    Row(Modifier.fillMaxWidth().padding(top = AppSpacing.s), verticalAlignment = Alignment.CenterVertically) {
        Text(pluralStringResource(R.plurals.grid_selected, count, count), style = AppTypography.title, color = c.textPrimary,
            modifier = Modifier.weight(1f).semantics { heading() }.testTag("lib.selcount"))
        androidx.compose.material3.TextButton(onAll) {
            Text(stringResource(if (count == total) R.string.grid_select_none else R.string.grid_select_all))
        }
        GlassIconButton(Icons.Rounded.Close, stringResource(R.string.done), Modifier.testTag("lib.seldone"), onClick = onDone)
    }
}

/** Neue Auswahl nach einem Neuladen: nur noch vorhandene Medien. */
@Composable
internal fun PruneSelection(assets: List<MediaAsset>?, selection: Set<String>, onSelection: (Set<String>) -> Unit) {
    LaunchedEffect(assets) {
        val ids = assets?.map { it.id }?.toHashSet() ?: return@LaunchedEffect
        if (selection.any { it !in ids }) onSelection(selection.filter { it in ids }.toSet())
    }
}
