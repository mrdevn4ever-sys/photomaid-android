package app.photomaid.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.library.FolderTree
import app.photomaid.core.library.Timeline
import app.photomaid.core.library.TimelineLevel
import app.photomaid.design.AppBackground
import app.photomaid.design.AppSpacing
import app.photomaid.design.EmptyState
import app.photomaid.design.GlassIconButton
import app.photomaid.design.LargeTitleHeader
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.design.SegmentedPicker
import app.photomaid.feature.editor.Chip
import app.photomaid.media.MediaAsset
import app.photomaid.media.reviewableMedia
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.TabBarClearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID

/** Ansicht im Tab „Fotos“ (nur Ansicht, nicht gespeichert). */
enum class LibraryMode(val label: Int) { TIMELINE(R.string.lib_mode_timeline), FOLDERS(R.string.lib_mode_folders), FAVORITES(R.string.lib_filter_favorites) }

/**
 * Tab „Fotos“: Zeitachse (Tag · Monat · Jahr, zwei Finger wechseln die Stufe), Ordner (Baum, Gruppen) und Favoriten
 * (nach Ordnern oder chronologisch). Suche ohne Akzente, Auswahl. Ansehen ist nie eine Entscheidung; nur „In den
 * Papierkorb“ in der Auswahl entscheidet (PhotoMaid-Papierkorb, nichts gelöscht). „In PhotoMaid ausgeblendet“ fehlt hier.
 */
@Composable
fun LibraryScreen(nav: Nav, initialMode: LibraryMode = LibraryMode.TIMELINE) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val access by app.visiblePhotos.access.collectAsState()
    val token by app.visiblePhotos.changeToken.collectAsState()
    val cameraVersion by app.cameras.version.collectAsState()
    val settings by app.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var level by rememberSaveable { mutableStateOf(TimelineLevel.DAY) }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var all by remember { mutableStateOf<List<MediaAsset>?>(null) }
    var search by remember { mutableStateOf<LibrarySearch?>(null) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    val zone = remember { ZoneId.systemDefault() }

    LaunchedEffect(token, access) {
        if (!access.canRead) { all = emptyList(); return@LaunchedEffect }
        all = withContext(Dispatchers.IO) { app.visiblePhotos.reviewableMedia() }
    }
    // Kamera-Modelle erst lesen, wenn gesucht wird (EXIF, lokal, im Hintergrund).
    LaunchedEffect(all, searching) { val list = all; if (searching && list != null) withContext(Dispatchers.IO) { app.cameras.index(list) } }
    LaunchedEffect(all, cameraVersion) {
        val list = all ?: return@LaunchedEffect
        search = withContext(Dispatchers.Default) { LibrarySearch(ctx, list, camera = app.cameras::cached) }
    }
    PruneSelection(all, selection) { selection = it }
    LaunchedEffect(mode) { selection = emptySet() }

    val searched = remember(all, query, search) { val base = all.orEmpty(); search?.filter(base, query) ?: base }
    val cells: List<GridCell> = remember(searched, filter, level, mode, settings.favoritesByFolder, settings.folderSorts, settings.defaultFolderSort) {
        when (mode) {
            LibraryMode.TIMELINE -> timelineCells(ctx, searched.filter(filter::accepts), level, zone)
            LibraryMode.FAVORITES -> {
                val favs = searched.filter { it.isFavorite }
                if (settings.favoritesByFolder) favoritesByFolder(favs, settings) else timelineCells(ctx, favs, level, zone)
            }
            LibraryMode.FOLDERS -> emptyList()
        }
    }
    val ordered = remember(cells) { cells.tiles() }
    val grid = rememberLazyGridState()
    // Stufenwechsel: dasselbe Medium bleibt oben sichtbar.
    var anchor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cells) {
        val id = anchor ?: return@LaunchedEffect
        val i = cells.indexOfFirst { it is GridCell.Tile && it.asset.id == id }
        if (i >= 0) grid.scrollToItem((i - 1).coerceAtLeast(0))
        anchor = null
    }
    fun open(a: MediaAsset) = nav.openViewer(ViewerRequest(UUID.randomUUID().toString(), ordered, ordered.indexOfFirst { it.id == a.id }.coerceAtLeast(0)))
    val chosen = ordered.filter { it.id in selection }
    val onLevel: (TimelineLevel, String?) -> Unit = { l, id -> anchor = id; level = l }

    AppBackground(Modifier.testTag("library")) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.padding(horizontal = AppSpacing.pageMargin)) {
                if (selection.isEmpty()) LargeTitleHeader(stringResource(R.string.tab_library)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
                        if (mode != LibraryMode.FOLDERS && ordered.isNotEmpty()) GlassIconButton(Icons.Rounded.Slideshow, stringResource(R.string.slideshow_start),
                            Modifier.testTag("lib.slideshow")) { nav.openSlideshow(SlideshowRequest(UUID.randomUUID().toString(), ordered)) }
                        GlassIconButton(Icons.Rounded.Search, stringResource(R.string.lib_search), Modifier.testTag("lib.search")) { searching = !searching; if (!searching) query = "" }
                        GlassIconButton(Icons.Rounded.Settings, stringResource(R.string.settings), onClick = nav::openSettings)
                    }
                } else SelectionHeader(selection.size, ordered.size,
                    onAll = { selection = if (selection.size == ordered.size) emptySet() else ordered.map { it.id }.toSet() }, onDone = { selection = emptySet() })
                if (searching) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = AppSpacing.xs).testTag("lib.query"),
                    placeholder = { Text(stringResource(if (mode == LibraryMode.FOLDERS) R.string.lib_folders_search_hint else R.string.lib_search_hint)) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) GlassIconButton(Icons.Rounded.Close, stringResource(R.string.lib_search_clear), quiet = true) { query = "" } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), shape = RoundedCornerShape(16.dp))
                if (selection.isEmpty()) SegmentedPicker(LibraryMode.entries.map { it to stringResource(it.label) }, mode,
                    Modifier.padding(top = AppSpacing.xs).testTag("lib.mode")) { mode = it }
                when (mode) {
                    LibraryMode.TIMELINE -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = AppSpacing.xs).testTag("lib.filters"),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        for (f in LibraryFilter.entries) if (f != LibraryFilter.FAVORITES) Chip(stringResource(f.label), filter == f, Modifier.testTag("lib.filter.${f.name}")) { filter = f }
                        Box(Modifier.width(1.dp).height(24.dp).background(PM.colors.textSecondary.copy(alpha = 0.3f)))
                        for (l in TimelineLevel.entries) Chip(stringResource(levelLabel(l)), level == l, Modifier.testTag("lib.level.${l.name}")) {
                            anchor = firstVisibleId(grid, cells); level = l }
                    }
                    LibraryMode.FAVORITES -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = AppSpacing.xs).testTag("lib.favgroup"),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Chip(stringResource(R.string.lib_fav_by_folder), settings.favoritesByFolder, Modifier.testTag("lib.fav.byfolder")) {
                            scope.launch { app.updateSettings { it.copy(favoritesByFolder = true) } } }
                        Chip(stringResource(R.string.lib_fav_by_date), !settings.favoritesByFolder, Modifier.testTag("lib.fav.bydate")) {
                            scope.launch { app.updateSettings { it.copy(favoritesByFolder = false) } } }
                    }
                    LibraryMode.FOLDERS -> Box(Modifier.height(AppSpacing.xs))
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
                mode == LibraryMode.FOLDERS -> FoldersPane(nav, list, if (searching) query else "")
                ordered.isEmpty() -> Box(Modifier.fillMaxSize().padding(AppSpacing.pageMargin).testTag("lib.empty"), contentAlignment = Alignment.Center) {
                    when {
                        query.isNotBlank() -> EmptyState(Icons.Rounded.Search, stringResource(R.string.lib_search_none_title), stringResource(R.string.lib_search_none_text, query))
                        mode == LibraryMode.FAVORITES -> EmptyState(Icons.Rounded.Favorite, stringResource(R.string.lib_fav_empty_title), stringResource(R.string.lib_fav_empty_text))
                        else -> EmptyState(Icons.Rounded.PhotoLibrary, stringResource(R.string.picker_empty_title), stringResource(R.string.lib_empty_text))
                    }
                }
                else -> Box(Modifier.fillMaxSize()) {
                    MediaGridPane(cells, level, selection, { selection = it }, ::open, grid = grid, onLevel = onLevel)
                    SelectionActions(nav, chosen, onClear = { selection = emptySet() }, Modifier.align(Alignment.BottomCenter).padding(bottom = TabBarClearance - 20.dp))
                }
            }
        }
    }
}

internal fun levelLabel(l: TimelineLevel) = when (l) { TimelineLevel.DAY -> R.string.lib_level_day; TimelineLevel.MONTH -> R.string.lib_level_month; TimelineLevel.YEAR -> R.string.lib_level_year }

/** Zeitachse als Rasterzellen (Kopf je Tag/Monat/Jahr, Blase zeigt bei Tagen den Monat). */
internal fun timelineCells(ctx: android.content.Context, items: List<MediaAsset>, level: TimelineLevel, zone: ZoneId): List<GridCell> =
    Timeline.sections(items, { it.creationDate }, level, zone).flatMap { s ->
        val bubble = timelineTitle(ctx, s.key, if (level == TimelineLevel.DAY) TimelineLevel.MONTH else level)
        listOf<GridCell>(GridCell.Header(s.key.toString(), timelineTitle(ctx, s.key, level), bubble, s.items.size)) + s.items.map { GridCell.Tile(it) }
    }

/** Favoriten je Ordner: Abschnitte nach jüngstem Favoriten, innen in der Reihenfolge des Ordners. */
private fun favoritesByFolder(favs: List<MediaAsset>, settings: app.photomaid.AppSettingsData): List<GridCell> =
    favs.groupBy { it.relativePath?.let(FolderTree::normalize)?.lowercase() ?: "" }.entries
        .sortedByDescending { (_, v) -> v.maxOf { (it.creationDate ?: it.dateAdded)?.toEpochMilli() ?: Long.MIN_VALUE } }
        .flatMap { (key, v) ->
            val name = v.first().relativePath?.let { app.photomaid.core.organize.MediaFolder(FolderTree.normalize(it), 0).name } ?: "—"
            listOf<GridCell>(GridCell.Header("f:$key", name, name, v.size)) + sortMedia(v, settings.sortFor(key)).map { GridCell.Tile(it) }
        }
