package app.photomaid.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.library.FolderGroup
import app.photomaid.core.library.FolderListSort
import app.photomaid.core.library.FolderNode
import app.photomaid.core.library.FolderSort
import app.photomaid.core.library.FolderTree
import app.photomaid.core.library.Timeline
import app.photomaid.core.library.TimelineLevel
import app.photomaid.core.organize.MediaFolder
import app.photomaid.core.organize.MediaFolders
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.EmptyState
import app.photomaid.design.GlassIconButton
import app.photomaid.design.ListSection
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.RowDivider
import app.photomaid.design.ScreenScaffold
import app.photomaid.feature.common.ConfirmationSheet
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.feature.editor.Chip
import app.photomaid.FeedRequest
import app.photomaid.core.library.NaturalOrder
import app.photomaid.core.library.SearchText
import app.photomaid.design.ProminentButton
import app.photomaid.feature.settings.setExcluded
import app.photomaid.feature.studio.PickRequest
import app.photomaid.media.MediaAsset
import app.photomaid.media.reviewableMedia
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.Routes
import app.photomaid.navigation.TabBarClearance
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID

/** Ziel der Ordner-Ansicht: ein Ordner (Pfad wie in MediaStore) oder eine eigene Gruppe. */
sealed interface FolderTarget {
    data class Path(val path: String) : FolderTarget
    data class Group(val id: String) : FolderTarget

    /** Routenargument (wird von der Navigation kodiert). */
    fun encode(): String = when (this) { is Path -> "path:$path"; is Group -> "group:$id" }

    companion object {
        fun decode(s: String): FolderTarget? = when {
            s.startsWith("path:") -> Path(s.removePrefix("path:"))
            s.startsWith("group:") -> Group(s.removePrefix("group:"))
            else -> null
        }
    }
}

// ---- Ordnerliste im Tab „Fotos“ -------------------------------------------------------------------------------

/**
 * Ordner als Baum (Unterordner aufklappbar, leere Zwischenordner zusammengefasst), eigene Gruppen oben, Suche ohne
 * Akzente über alle Ebenen, Sortierung der Liste. Langer Druck auf einen Ordner: Ausblenden, Aufräumen, Ausnehmen.
 */
@Composable
internal fun FoldersPane(nav: Nav, assets: List<MediaAsset>, query: String) {
    val app = LocalApp.current
    val c = PM.colors
    val settings by app.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val roots = remember(assets, settings.folderListSort) { FolderTree.build(folderEntries(assets), settings.folderListSort) }
    val covers = remember(roots, assets) { folderCovers(roots, assets) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var sortMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var groupEdit by remember { mutableStateOf<FolderGroup?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var hideConfirm by remember { mutableStateOf<FolderNode?>(null) }
    val rows = remember(roots, expanded, query) { if (query.isBlank()) FolderTree.rows(roots, expanded) else FolderTree.search(roots, query) }
    val groups = settings.folderGroups.filter { query.isBlank() || SearchText.matches(
        SearchText.tokens(query), listOf(SearchText.normalize(it.name))) }

    LazyColumn(Modifier.fillMaxSize().testTag("lib.folders"), contentPadding = PaddingValues(bottom = TabBarClearance + 32.dp)) {
        item(key = "tools") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Chip(stringResource(settings.folderListSort.label), false, Modifier.testTag("lib.folders.sort"), icon = Icons.AutoMirrored.Rounded.Sort) { sortMenu = true }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        for (s in FolderListSort.entries) DropdownMenuItem(text = { Text(stringResource(s.label)) },
                            leadingIcon = { if (s == settings.folderListSort) Icon(Icons.Rounded.Check, null) },
                            modifier = Modifier.testTag("lib.folders.sort.${s.name}"),
                            onClick = { sortMenu = false; scope.launch { app.updateSettings { it.copy(folderListSort = s) } } })
                    }
                }
                Chip(stringResource(R.string.lib_new_folder), false, Modifier.testTag("lib.folders.new"), icon = Icons.Rounded.CreateNewFolder) { newFolder = true }
                Chip(stringResource(R.string.lib_new_group), false, Modifier.testTag("lib.groups.new"), icon = Icons.Rounded.Workspaces) {
                    groupEdit = FolderGroup(UUID.randomUUID().toString(), "", emptyList()) }
            }
        }
        if (groups.isNotEmpty()) {
            item(key = "gh") { PaneHeader(stringResource(R.string.lib_groups)) }
            items(groups, key = { "g:" + it.id }) { g ->
                val members = assets.filter { a -> g.paths.any { FolderTree.contains(it, a.relativePath) } }
                val cover = members.maxByOrNull { (it.creationDate ?: it.dateAdded)?.toEpochMilli() ?: Long.MIN_VALUE }
                FolderRowView(icon = Icons.Rounded.Workspaces, label = g.name, subtitle = pluralStringResource(R.plurals.lib_group_folders, g.paths.size, g.paths.size),
                    count = members.size, cover = cover, depth = 0, expandState = null, onExpand = {},
                    modifier = Modifier.testTag("lib.group.row"),
                    menu = { close -> GroupMenuItems(g, onEdit = { close(); groupEdit = g }, onDelete = { close(); scope.launch { deleteGroup(app, g.id) } }) }) {
                    nav.openFolder(FolderTarget.Group(g.id))
                }
            }
        }
        item(key = "fh") { PaneHeader(stringResource(R.string.lib_mode_folders)) }
        if (rows.isEmpty()) item(key = "none") {
            Box(Modifier.fillMaxWidth().padding(AppSpacing.l), contentAlignment = Alignment.Center) {
                if (query.isNotBlank()) EmptyState(Icons.Outlined.Folder, stringResource(R.string.lib_search_none_title), stringResource(R.string.lib_folders_none_query, query))
                else EmptyState(Icons.Outlined.Folder, stringResource(R.string.picker_empty_title), stringResource(R.string.lib_empty_text))
            }
        }
        items(rows, key = { "f:" + it.path.lowercase() }) { n ->
            val key = n.path.lowercase()
            val excluded = settings.excludedFolders.any { FolderTree.contains(it, n.path) }
            val subtitle = buildList {
                if (query.isNotBlank() || n.path.trimEnd('/').count { it == '/' } > n.depth + 1) add(n.path.trimEnd('/'))
                if (n.hasChildren && query.isBlank()) add(pluralStringResource(R.plurals.lib_subfolders, n.children.size, n.children.size))
                if (excluded) add(stringResource(R.string.lib_folder_excluded))
            }.joinToString(" · ").ifEmpty { null }
            FolderRowView(icon = Icons.Outlined.Folder, label = n.label, subtitle = subtitle, count = n.total, cover = covers[key], depth = n.depth,
                expandState = if (n.hasChildren && query.isBlank()) key in expanded else null,
                onExpand = { expanded = if (key in expanded) expanded - key else expanded + key },
                modifier = Modifier.testTag("lib.folder.row"),
                menu = { close -> FolderMenuItems(nav, n, excluded, assets, onHide = { close(); hideConfirm = n }, close = close) }) {
                nav.openFolder(FolderTarget.Path(n.path))
            }
        }
        item(key = "foot") {
            Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.s), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                if (settings.hiddenFolders.isNotEmpty() || settings.hiddenMedia.isNotEmpty())
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.backgroundTint).clickable(role = Role.Button) { nav.push(Routes.HIDDEN) }
                        .padding(AppSpacing.s).testTag("lib.hidden.open"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                        Icon(Icons.Rounded.VisibilityOff, null, tint = c.textSecondary)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.lib_hidden_title), style = AppTypography.body, color = c.textPrimary)
                            Text(stringResource(R.string.lib_hidden_row), style = AppTypography.footnote, color = c.textSecondary)
                        }
                    }
                Text(stringResource(R.string.lib_folders_footer), style = AppTypography.footnote, color = c.textSecondary)
            }
        }
    }

    if (newFolder) NewFolderDialog(existing = roots, onDismiss = { newFolder = false }) { path ->
        newFolder = false
        nav.openPicker(PickRequest(UUID.randomUUID().toString(), photos = true, videos = true, min = 1, max = 500, title = R.string.lib_new_folder_pick,
            library = true, onDone = { ids -> scope.launch { message = moveAll(app, ids, path) } }))
    }
    groupEdit?.let { g -> GroupSheet(g, roots, onDismiss = { groupEdit = null }) { saved -> groupEdit = null; scope.launch { saveGroup(app, saved) } } }
    hideConfirm?.let { n -> ConfirmationSheet(stringResource(R.string.lib_hide_folder_title, n.label), stringResource(R.string.lib_hide_folder_text),
        stringResource(R.string.lib_hide), destructive = false, onConfirm = { scope.launch { setFolderHidden(app, n.path, true) } }, onDismiss = { hideConfirm = null }) }
    message?.let { m -> NoticeDialog(m) { message = null } }
}

@Composable
private fun PaneHeader(text: String) {
    Text(text.uppercase(), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = PM.colors.textSecondary,
        modifier = Modifier.padding(start = AppSpacing.pageMargin, end = AppSpacing.pageMargin, top = AppSpacing.m, bottom = AppSpacing.xxs))
}

/** Zeile: Titelbild, Name, Untertitel, Anzahl, Aufklappen. Langer Druck öffnet das Menü (auch als Bedienungshilfe-Aktion). */
@Composable
private fun FolderRowView(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, subtitle: String?, count: Int, cover: MediaAsset?, depth: Int,
    expandState: Boolean?, onExpand: () -> Unit, modifier: Modifier = Modifier,
    menu: @Composable (close: () -> Unit) -> Unit, onOpen: () -> Unit,
) {
    val c = PM.colors
    var open by remember { mutableStateOf(false) }
    val px = with(LocalDensity.current) { 56.dp.roundToPx() }
    val menuLabel = stringResource(R.string.more_actions)
    val expandLabel = stringResource(if (expandState == true) R.string.lib_collapse else R.string.lib_expand)
    Box {
        Row(modifier.fillMaxWidth().heightIn(min = 64.dp).combinedClickable(role = Role.Button, onClick = onOpen, onLongClick = { open = true })
            .semantics(mergeDescendants = true) {
                customActions = listOfNotNull(CustomAccessibilityAction(menuLabel) { open = true; true },
                    if (expandState != null) CustomAccessibilityAction(expandLabel) { onExpand(); true } else null)
                if (expandState != null) stateDescription = expandLabel
            }
            .padding(start = AppSpacing.pageMargin + (16 * depth.coerceAtMost(4)).dp, end = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(c.backgroundTint), contentAlignment = Alignment.Center) {
                if (cover != null) AsyncImage(ImageRequest.Builder(LocalContext.current).data(cover.uri).size(px, px).memoryCacheKey("fold:${cover.id}:$px").build(),
                    null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(icon, null, tint = c.accent)
            }
            Column(Modifier.weight(1f).padding(vertical = AppSpacing.xs)) {
                Text(label, style = AppTypography.body.copy(fontWeight = FontWeight.Medium), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = AppTypography.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("$count", style = AppTypography.subheadline.copy(fontFeatureSettings = "tnum"), color = c.textSecondary)
            if (expandState != null) GlassIconButton(if (expandState) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, expandLabel,
                Modifier.testTag("lib.folder.expand"), quiet = true, onClick = onExpand)
            else Spacer(Modifier.width(AppSpacing.xs))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) { menu { open = false } }
    }
    RowDivider(inset = AppSpacing.pageMargin + 60.dp)
}

@Composable
private fun FolderMenuItems(nav: Nav, n: FolderNode, excluded: Boolean, assets: List<MediaAsset>, onHide: () -> Unit, close: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    DropdownMenuItem(text = { Text(stringResource(R.string.lib_folder_cleanup)) }, leadingIcon = { Icon(Icons.Rounded.CleaningServices, null) },
        enabled = !excluded, modifier = Modifier.testTag("lib.folder.cleanup"), onClick = {
            close()
            val items = app.cleanupCandidates(sortMedia(assets.filter { FolderTree.contains(n.path, it.relativePath) }, FolderSort.NEWEST))
            nav.openFeed(FeedRequest(FeedRequest.Source.Items(items.map { it.reference }, R.string.lib_feed_title)))
        })
    DropdownMenuItem(text = { Text(stringResource(if (excluded) R.string.lib_folder_include else R.string.lib_folder_exclude)) },
        leadingIcon = { Icon(if (excluded) Icons.Rounded.FolderSpecial else Icons.Rounded.FolderOff, null) },
        modifier = Modifier.testTag("lib.folder.exclude"), onClick = { close(); scope.launch { setExcluded(app, n.path, !excluded) } })
    DropdownMenuItem(text = { Text(stringResource(R.string.lib_hide)) }, leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) },
        modifier = Modifier.testTag("lib.folder.hide"), onClick = onHide)
}

@Composable
private fun GroupMenuItems(g: FolderGroup, onEdit: () -> Unit, onDelete: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(R.string.lib_group_edit)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
        modifier = Modifier.testTag("lib.group.edit"), onClick = onEdit)
    DropdownMenuItem(text = { Text(stringResource(R.string.lib_group_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
        modifier = Modifier.testTag("lib.group.delete"), onClick = onDelete)
}

/**
 * „Neuer Ordner“: Name prüfen, danach Medien wählen und verschieben. Android kennt keine leeren Ordner in der
 * Mediathek – der Ordner entsteht erst mit dem ersten verschobenen Medium unter `Pictures/<Name>/`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewFolderDialog(existing: List<FolderNode>, onDismiss: () -> Unit, onNext: (String) -> Unit) {
    val c = PM.colors
    var name by remember { mutableStateOf("") }
    val path = MediaFolders.newFolderPath(name)
    val exists = path != null && FolderTree.find(existing, path) != null
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.m).testTag("lib.newfolder"),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Text(stringResource(R.string.lib_new_folder), style = AppTypography.headline, color = c.textPrimary)
            OutlinedTextField(name, { name = it.take(MediaFolders.MAX_NAME) }, Modifier.fillMaxWidth().testTag("lib.newfolder.name"), singleLine = true,
                placeholder = { Text(stringResource(R.string.folder_name)) }, isError = name.isNotBlank() && path == null)
            Text(stringResource(when {
                name.isNotBlank() && path == null -> R.string.folder_invalid
                exists -> R.string.lib_new_folder_exists
                else -> R.string.lib_new_folder_note
            }), style = AppTypography.footnote, color = c.textSecondary)
            ProminentButton(stringResource(R.string.lib_new_folder_next), Modifier.testTag("lib.newfolder.next"), enabled = path != null, fillWidth = true, large = true) {
                path?.let(onNext)
            }
        }
    }
}

/** Gruppe anlegen/bearbeiten: Name und Ordner (Häkchen). Gespeichert werden nur Name und Pfade. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupSheet(group: FolderGroup, roots: List<FolderNode>, onDismiss: () -> Unit, onSave: (FolderGroup) -> Unit) {
    val c = PM.colors
    var name by remember { mutableStateOf(group.name) }
    var paths by remember { mutableStateOf(group.paths.map(FolderTree::normalize).toSet()) }
    var filter by remember { mutableStateOf("") }
    val all = remember(roots) { FolderTree.rows(roots, allPaths(roots)) }
    val shown = if (filter.isBlank()) all else FolderTree.search(roots, filter)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.m).testTag("lib.groupsheet"),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Text(stringResource(if (group.name.isEmpty()) R.string.lib_new_group else R.string.lib_group_edit), style = AppTypography.headline, color = c.textPrimary)
            OutlinedTextField(name, { name = it.take(MediaFolders.MAX_NAME) }, Modifier.fillMaxWidth().testTag("lib.group.name"), singleLine = true,
                placeholder = { Text(stringResource(R.string.lib_group_name_hint)) })
            Text(stringResource(R.string.lib_group_note), style = AppTypography.footnote, color = c.textSecondary)
            if (all.size > 8) OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(R.string.folder_search)) })
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(shown, key = { it.path.lowercase() }) { n ->
                    val on = paths.any { it.equals(n.path, ignoreCase = true) }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) {
                        paths = if (on) paths.filterNot { it.equals(n.path, ignoreCase = true) }.toSet() else paths + n.path
                    }.padding(start = (16 * n.depth.coerceAtMost(4)).dp).semantics { stateDescription = if (on) "✓" else "" }.testTag("lib.group.pick"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                        Icon(if (on) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null, tint = if (on) c.accent else c.textSecondary)
                        Text(n.label, style = AppTypography.body, color = c.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${n.total}", style = AppTypography.footnote, color = c.textSecondary)
                    }
                }
            }
            ProminentButton(stringResource(R.string.save), Modifier.testTag("lib.group.save"), enabled = name.isNotBlank() && paths.isNotEmpty(),
                fillWidth = true, large = true) { onSave(group.copy(name = name.trim(), paths = paths.toList().sortedWith { a, b -> NaturalOrder.compare(a, b) })) }
        }
    }
}

private fun allPaths(roots: List<FolderNode>): Set<String> {
    val out = HashSet<String>()
    fun walk(n: FolderNode) { out += n.path.lowercase(); n.children.forEach(::walk) }
    roots.forEach(::walk)
    return out
}

// ---- Ein Ordner bzw. eine Gruppe -------------------------------------------------------------------------------

/**
 * Inhalt eines Ordners (eigene Medien, Unterordner als Karten oben) bzw. einer Gruppe (alle Ordner inkl. Unterordner).
 * Reihenfolge je Ordner gespeichert, „Nur Favoriten“ je Ordner. Ansehen und Sortieren sind nie eine Entscheidung.
 */
@Composable
fun FolderScreen(target: FolderTarget, nav: Nav, onClose: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val c = PM.colors
    val settings by app.settings.collectAsState()
    val token by app.visiblePhotos.changeToken.collectAsState()
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<MediaAsset>?>(null) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var level by rememberSaveable { mutableStateOf(TimelineLevel.DAY) }
    var sortMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var hideConfirm by remember { mutableStateOf(false) }
    var groupEdit by remember { mutableStateOf<FolderGroup?>(null) }
    var raw by remember { mutableStateOf(RawIndex.EMPTY) }
    LaunchedEffect(token) {
        val loaded = withContext(Dispatchers.IO) { app.visiblePhotos.reviewableMedia() }
        val idx = withContext(Dispatchers.Default) { RawIndex(loaded) }
        raw = idx; all = idx.visible(loaded)
    }
    PruneSelection(all, selection) { selection = it }

    val group = (target as? FolderTarget.Group)?.let { t -> settings.folderGroups.firstOrNull { it.id == t.id } }
    val roots = remember(all) { FolderTree.build(folderEntries(all.orEmpty()), FolderListSort.NAME) }
    val node = (target as? FolderTarget.Path)?.let { FolderTree.find(roots, it.path) }
    val key = when (target) { is FolderTarget.Path -> folderKey(target.path); is FolderTarget.Group -> groupKey(target.id) }
    val sort = settings.sortFor(key)
    val favOnly = key in settings.folderFavoritesOnly
    val title = group?.name ?: node?.label ?: (target as? FolderTarget.Path)?.let { MediaFolder(FolderTree.normalize(it.path), 0).name } ?: ""
    val members = remember(all, target, group) {
        all.orEmpty().filter { a ->
            when (target) {
                is FolderTarget.Path -> FolderTree.contains(target.path, a.relativePath, includeSubfolders = false)
                is FolderTarget.Group -> group?.paths?.any { FolderTree.contains(it, a.relativePath) } == true
            }
        }
    }
    val zone = remember { ZoneId.systemDefault() }
    val cells = remember(members, sort, favOnly, level) {
        val list = sortMedia(if (favOnly) members.filter { it.isFavorite } else members, sort)
        if (sort == FolderSort.NEWEST || sort == FolderSort.OLDEST) {
            val sections = Timeline.sections(list, { it.creationDate }, TimelineLevel.MONTH, zone).let { if (sort == FolderSort.OLDEST) it.reversed() else it }
            sections.flatMap { s ->
                val t = timelineTitle(ctx, s.key, TimelineLevel.MONTH)
                listOf<GridCell>(GridCell.Header(s.key.toString(), t, t, s.items.size)) + sortMedia(s.items, sort).map { GridCell.Tile(it) }
            }
        } else list.map { GridCell.Tile(it) }
    }
    val ordered = remember(cells) { cells.tiles() }
    val chosen = ordered.filter { it.id in selection }
    val grid = rememberLazyGridState()
    // Gruppe gelöscht oder Ordner ausgeblendet: zurück.
    LaunchedEffect(group, settings.hiddenFolders) {
        if (target is FolderTarget.Group && group == null) onClose()
        if (target is FolderTarget.Path && settings.hiddenFolders.any { FolderTree.contains(it, target.path) }) onClose()
    }

    ScreenScaffold(title, onBack = onClose, modifier = Modifier.testTag("folder.screen"), actions = {
        Box {
            GlassIconButton(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.lib_sort), Modifier.testTag("folder.sort")) { sortMenu = true }
            DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                for (s in FolderSort.entries) DropdownMenuItem(text = { Text(stringResource(s.label)) }, leadingIcon = { if (s == sort) Icon(Icons.Rounded.Check, null) },
                    modifier = Modifier.testTag("folder.sort.${s.name}"), onClick = { sortMenu = false; scope.launch { setFolderSort(app, key, s, asDefault = false) } })
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.lib_sort_default)) }, enabled = settings.folderSorts.containsKey(key) || sort != settings.defaultFolderSort,
                    modifier = Modifier.testTag("folder.sort.default"), onClick = { sortMenu = false; scope.launch { setFolderSort(app, key, sort, asDefault = true) } })
            }
        }
        Box {
            GlassIconButton(Icons.Rounded.MoreHoriz, stringResource(R.string.more_actions), Modifier.testTag("folder.more")) { menu = true }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.lib_folder_cleanup)) }, leadingIcon = { Icon(Icons.Rounded.CleaningServices, null) },
                    enabled = ordered.isNotEmpty(), modifier = Modifier.testTag("folder.cleanup"), onClick = {
                        menu = false
                        nav.openFeed(FeedRequest(FeedRequest.Source.Items(app.cleanupCandidates(ordered).map { it.reference }, R.string.lib_feed_title)))
                    })
                DropdownMenuItem(text = { Text(stringResource(R.string.slideshow_start)) }, leadingIcon = { Icon(Icons.Rounded.Slideshow, null) },
                    enabled = ordered.isNotEmpty(), modifier = Modifier.testTag("folder.slideshow"), onClick = {
                        menu = false; nav.openSlideshow(SlideshowRequest(UUID.randomUUID().toString(), ordered)) })
                if (group != null) {
                    GroupMenuItems(group, onEdit = { menu = false; groupEdit = group }, onDelete = { menu = false; scope.launch { deleteGroup(app, group.id) } })
                } else DropdownMenuItem(text = { Text(stringResource(R.string.lib_hide)) }, leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) },
                    modifier = Modifier.testTag("folder.hide"), onClick = { menu = false; hideConfirm = true })
            }
        }
    }) { mod ->
        Column(mod) {
            if (selection.isNotEmpty()) Box(Modifier.padding(horizontal = AppSpacing.pageMargin)) {
                SelectionHeader(selection.size, ordered.size, onAll = { selection = if (selection.size == ordered.size) emptySet() else ordered.map { it.id }.toSet() },
                    onDone = { selection = emptySet() })
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Chip(stringResource(R.string.lib_fav_only), favOnly, Modifier.testTag("folder.favonly"), icon = Icons.Rounded.Favorite) {
                    scope.launch { setFavoritesOnly(app, key, !favOnly) } }
                for (l in TimelineLevel.entries) Chip(stringResource(levelLabel(l)), level == l, Modifier.testTag("folder.level.${l.name}")) { level = l }
            }
            node?.children?.takeIf { it.isNotEmpty() }?.let { kids ->
                val covers = remember(kids, all) { folderCovers(kids, all.orEmpty()) }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.xs).testTag("folder.subfolders"),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    for (k in kids) SubfolderCard(k, covers[k.path.lowercase()]) { nav.openFolder(FolderTarget.Path(k.path)) }
                }
            }
            when {
                all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                ordered.isEmpty() -> Box(Modifier.fillMaxSize().padding(AppSpacing.pageMargin).testTag("folder.empty"), contentAlignment = Alignment.Center) {
                    if (favOnly && members.isNotEmpty()) EmptyState(Icons.Rounded.Favorite, stringResource(R.string.lib_fav_empty_title), stringResource(R.string.lib_folder_no_favs))
                    else EmptyState(Icons.Outlined.Folder, stringResource(R.string.picker_empty_title),
                        stringResource(if (node?.hasChildren == true) R.string.lib_folder_only_sub else R.string.lib_folder_empty))
                }
                else -> Box(Modifier.fillMaxSize()) {
                    MediaGridPane(cells, level, selection, { selection = it },
                        onOpen = { a -> nav.openViewer(ViewerRequest(UUID.randomUUID().toString(), ordered, ordered.indexOfFirst { it.id == a.id }.coerceAtLeast(0), raw.partner)) },
                        grid = grid, onLevel = { l, _ -> level = l }, bottomPadding = 96.dp, tag = "folder.grid", rawIds = raw.partner.keys)
                    SelectionActions(nav, chosen, onClear = { selection = emptySet() }, Modifier.align(Alignment.BottomCenter).padding(bottom = AppSpacing.s), raw = raw)
                }
            }
        }
    }
    if (hideConfirm) ConfirmationSheet(stringResource(R.string.lib_hide_folder_title, title), stringResource(R.string.lib_hide_folder_text),
        stringResource(R.string.lib_hide), destructive = false,
        onConfirm = { (target as? FolderTarget.Path)?.let { t -> scope.launch { setFolderHidden(app, t.path, true) } } }, onDismiss = { hideConfirm = false })
    groupEdit?.let { g ->
        val allRoots = remember(all) { FolderTree.build(folderEntries(all.orEmpty()), FolderListSort.NAME) }
        GroupSheet(g, allRoots, onDismiss = { groupEdit = null }) { saved -> groupEdit = null; scope.launch { saveGroup(app, saved) } }
    }
}

@Composable
private fun SubfolderCard(n: FolderNode, cover: MediaAsset?, onClick: () -> Unit) {
    val c = PM.colors
    val px = with(LocalDensity.current) { 96.dp.roundToPx() }
    Column(Modifier.width(96.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = "${n.label}, ${n.total}" }.testTag("folder.sub")) {
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(c.backgroundTint), contentAlignment = Alignment.Center) {
            if (cover != null) AsyncImage(ImageRequest.Builder(LocalContext.current).data(cover.uri).size(px, px).memoryCacheKey("fold:${cover.id}:$px").build(),
                null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Outlined.Folder, null, tint = c.accent)
        }
        Text(n.label, style = AppTypography.footnote.copy(fontWeight = FontWeight.Medium), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp))
        Text("${n.total}", style = AppTypography.caption, color = c.textSecondary)
    }
}

// ---- Ausgeblendet ---------------------------------------------------------------------------------------------

/**
 * „Ausgeblendet“: Ordner und Medien, die PhotoMaid nicht zeigt (weder im Tab „Fotos“ noch beim Aufräumen). Die Dateien
 * bleiben unverändert und in anderen Apps sichtbar. Wieder einblenden je Ordner bzw. per Auswahl.
 */
@Composable
fun HiddenScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val settings by app.settings.collectAsState()
    val token by app.allPhotos.changeToken.collectAsState()
    val scope = rememberCoroutineScope()
    var media by remember { mutableStateOf<List<MediaAsset>?>(null) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(token, settings.hiddenMedia) {
        media = withContext(Dispatchers.IO) { runCatching { app.allPhotos.assets(settings.hiddenMedia) }.getOrDefault(emptyList()) }
            .sortedByDescending { (it.creationDate ?: it.dateAdded)?.toEpochMilli() ?: Long.MIN_VALUE }
    }
    PruneSelection(media, selection) { selection = it }
    val cells = remember(media) { media.orEmpty().map { GridCell.Tile(it) } }
    val ordered = media.orEmpty()
    ScreenScaffold(stringResource(R.string.lib_hidden_title), onBack = nav::back, modifier = Modifier.testTag("hidden.screen")) { mod ->
        Column(mod) {
            Column(Modifier.padding(horizontal = AppSpacing.pageMargin), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                Text(stringResource(R.string.lib_hidden_intro), style = AppTypography.subheadline, color = c.textSecondary)
                if (selection.isNotEmpty()) SelectionHeader(selection.size, ordered.size,
                    onAll = { selection = if (selection.size == ordered.size) emptySet() else ordered.map { it.id }.toSet() }, onDone = { selection = emptySet() })
                if (settings.hiddenFolders.isNotEmpty()) ListSection(Modifier.testTag("hidden.folders"), header = stringResource(R.string.lib_mode_folders)) {
                    settings.hiddenFolders.forEachIndexed { i, p ->
                        if (i > 0) RowDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                            Icon(Icons.Rounded.VisibilityOff, null, tint = c.textSecondary)
                            Column(Modifier.weight(1f)) {
                                Text(MediaFolder(p, 0).name, style = AppTypography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(p.trimEnd('/'), style = AppTypography.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            PlainButton(stringResource(R.string.lib_unhide), Modifier.testTag("hidden.folder.unhide")) { scope.launch { setFolderHidden(app, p, false) } }
                        }
                    }
                }
                if (settings.hiddenMedia.isNotEmpty()) Text(pluralStringResource(R.plurals.lib_hidden_media, ordered.size, ordered.size),
                    style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary, modifier = Modifier.padding(start = AppSpacing.m))
            }
            when {
                settings.hiddenFolders.isEmpty() && settings.hiddenMedia.isEmpty() -> Box(Modifier.fillMaxSize().padding(AppSpacing.pageMargin).testTag("hidden.empty"), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Rounded.Visibility, stringResource(R.string.lib_hidden_none_title), stringResource(R.string.lib_hidden_none_text))
                }
                media == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> Box(Modifier.fillMaxSize().padding(top = AppSpacing.xs)) {
                    MediaGridPane(cells, TimelineLevel.MONTH, selection, { selection = it },
                        onOpen = { a -> selection = selection.toggle(a.id) }, bottomPadding = 96.dp, tag = "hidden.grid")
                    SelectionActions(nav, ordered.filter { it.id in selection }, onClear = { selection = emptySet() },
                        Modifier.align(Alignment.BottomCenter).padding(bottom = AppSpacing.s), unhide = true)
                }
            }
        }
    }
}
