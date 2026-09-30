package app.photomaid.feature.quickaccess

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.photomaid.FeedRequest
import app.photomaid.R
import app.photomaid.core.organize.MediaFolder
import app.photomaid.core.quickaccess.QuickAccess
import app.photomaid.core.quickaccess.QuickAccessItem
import app.photomaid.core.quickaccess.QuickSourceKind
import app.photomaid.design.AppIconSize
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.RowDivider
import app.photomaid.design.SurfaceCard
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun quickIcon(kind: QuickSourceKind): ImageVector = when (kind) {
    QuickSourceKind.PHOTOS -> Icons.Rounded.Photo
    QuickSourceKind.VIDEOS -> Icons.Rounded.Videocam
    QuickSourceKind.SCREENSHOTS -> Icons.Rounded.Screenshot
    QuickSourceKind.LATEST_PHOTOS, QuickSourceKind.LATEST_VIDEOS -> Icons.Rounded.Update
    QuickSourceKind.THIS_WEEK -> Icons.Rounded.DateRange
    QuickSourceKind.FOLDER -> Icons.Rounded.Folder
}

/**
 * „Schnellzugriffe“ auf Heute: kompakte Kacheln (2 pro Reihe) mit „N offen“ bzw. „Alles geprüft“ und eine
 * Plus-Kachel. Jede Kachel öffnet denselben Review-Feed mit anderer Quelle. Zähler werden nie gespeichert, sondern
 * je Anzeige aus Mitgliedschaft (MediaStore) + globalem Ledger abgeleitet – ein Medium in mehreren Quellen hat
 * überall denselben Zustand.
 */
@Composable
fun QuickAccessSection(nav: Nav, reloadKey: Any) {
    val app = LocalApp.current
    val c = PM.colors
    val scope = rememberCoroutineScope()
    val items by app.quickAccess.items.collectAsState()
    var pending by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<MediaFolder>?>(null) }

    LaunchedEffect(items, reloadKey) {
        pending = withContext(Dispatchers.IO) { items.associate { it.key to app.pendingQueue(app.photos.assetsFor(it)).size } }
    }

    Column(Modifier.testTag("today.quick"), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        // Große Schrift: eine Kachel pro Reihe und Kopfzeile untereinander (keine abgeschnittenen Namen).
        val large = LocalDensity.current.fontScale >= 1.3f
        if (large) {
            Text(stringResource(R.string.qa_title), style = AppTypography.headline, color = c.textPrimary)
            if (items.isNotEmpty()) PlainButton(stringResource(R.string.qa_edit), Modifier.testTag("quick.edit")) { editing = true }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.qa_title), style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) PlainButton(stringResource(R.string.qa_edit), Modifier.testTag("quick.edit")) { editing = true }
        }
        val cells: List<QuickAccessItem?> = items + if (items.size < QuickAccess.MAX_ITEMS) listOf(null) else emptyList()
        for (row in cells.chunked(if (large) 1 else 2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                for (cell in row) {
                    if (cell == null) AddTile(Modifier.weight(1f)) {
                        folders = null
                        adding = true
                        // Außerhalb des Sheets laden (Zustand der Hauptkomposition).
                        scope.launch { folders = withContext(Dispatchers.IO) { runCatching { app.photos.folders() }.getOrDefault(emptyList()) } }
                    }
                    else QuickTile(cell, pending[cell.key], Modifier.weight(1f)) { nav.openFeed(FeedRequest(FeedRequest.Source.Quick(cell))) }
                }
                if (row.size == 1 && !large) Spacer(Modifier.weight(1f))
            }
        }
    }
    if (adding) AddSheet(items, folders, onAdd = { q -> adding = false; scope.launch { app.quickAccess.update { QuickAccess.add(it, q) } } }, onDismiss = { adding = false })
    if (editing) EditSheet(items, onMove = { q, d -> scope.launch { app.quickAccess.update { QuickAccess.move(it, q, d) } } },
        onRemove = { q -> scope.launch { app.quickAccess.update { QuickAccess.remove(it, q) } } }, onDismiss = { editing = false })
}

@Composable
private fun QuickTile(item: QuickAccessItem, pending: Int?, modifier: Modifier, onClick: () -> Unit) {
    val c = PM.colors
    val title = quickTitle(item)
    val status = when (pending) {
        null -> ""
        0 -> stringResource(R.string.qa_all_done)
        else -> pluralStringResource(R.plurals.qa_open, pending, pending)
    }
    SurfaceCard(modifier.testTag("quick.${item.key}").semantics(mergeDescendants = true) { contentDescription = "$title, $status" },
        padding = PaddingValues(horizontal = AppSpacing.s, vertical = AppSpacing.s), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Icon(quickIcon(item.kind), null, tint = c.accent, modifier = Modifier.size(AppIconSize.small))
            Text(title, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
            if (pending == 0) Icon(Icons.Rounded.Check, null, tint = c.success, modifier = Modifier.size(AppIconSize.small))
            Text(status, style = AppTypography.footnote, color = if (pending == 0) c.textSecondary else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AddTile(modifier: Modifier, onClick: () -> Unit) {
    val c = PM.colors
    SurfaceCard(modifier.testTag("quick.add").heightIn(min = AppSpacing.minTouch), padding = PaddingValues(AppSpacing.s), onClick = onClick) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs, Alignment.CenterHorizontally)) {
            Icon(Icons.Rounded.Add, null, tint = c.accent, modifier = Modifier.size(AppIconSize.regular))
            Text(stringResource(R.string.qa_add), style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.accent, maxLines = 1)
        }
    }
}

private val addable = listOf(QuickSourceKind.PHOTOS, QuickSourceKind.VIDEOS, QuickSourceKind.SCREENSHOTS,
    QuickSourceKind.LATEST_PHOTOS, QuickSourceKind.LATEST_VIDEOS, QuickSourceKind.THIS_WEEK)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(current: List<QuickAccessItem>, folders: List<MediaFolder>?, onAdd: (QuickAccessItem) -> Unit, onDismiss: () -> Unit) {
    val c = PM.colors
    var choosingFolder by remember { mutableStateOf(false) }
    val full = current.size >= QuickAccess.MAX_ITEMS
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.m).testTag("quick.sheet"),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Text(stringResource(if (choosingFolder) R.string.qa_choose_folder else R.string.qa_add_title), style = AppTypography.headline, color = c.textPrimary)
            if (full) { Text(stringResource(R.string.qa_limit, QuickAccess.MAX_ITEMS), style = AppTypography.footnote, color = c.textSecondary); return@Column }
            if (!choosingFolder) {
                val open = addable.map { QuickAccessItem(it) }.filter { QuickAccess.canAdd(current, it) }
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(open, key = { it.key }) { q ->
                        SheetRow(quickIcon(q.kind), quickTitle(q), quickSubtitle(q), Modifier.testTag("quick.option.${q.key}")) { onAdd(q) }
                        RowDivider()
                    }
                    item {
                        SheetRow(Icons.Rounded.Folder, stringResource(R.string.qa_choose_folder), stringResource(R.string.qa_choose_folder_sub),
                            Modifier.testTag("quick.option.folder")) { choosingFolder = true }
                    }
                }
            } else {
                val list = folders?.filter { f -> QuickAccess.canAdd(current, QuickAccessItem(QuickSourceKind.FOLDER, f.relativePath)) }
                    ?.sortedWith(compareByDescending<MediaFolder> { it.count }.thenBy { it.name.lowercase() })
                when {
                    list == null -> Text(stringResource(R.string.folder_loading), style = AppTypography.footnote, color = c.textSecondary)
                    list.isEmpty() -> Text(stringResource(R.string.qa_no_folders), style = AppTypography.footnote, color = c.textSecondary)
                    else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                        items(list, key = { it.relativePath }) { f ->
                            SheetRow(Icons.Rounded.Folder, f.name, pluralStringResource(R.plurals.items_count, f.count, f.count),
                                Modifier.testTag("quick.folder.${f.relativePath}")) { onAdd(QuickAccessItem(QuickSourceKind.FOLDER, f.relativePath)) }
                            RowDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun quickSubtitle(q: QuickAccessItem): String? = when (q.kind) {
    QuickSourceKind.LATEST_PHOTOS -> stringResource(R.string.qa_latest_photos_sub, QuickAccess.LATEST_PHOTOS_LIMIT)
    QuickSourceKind.LATEST_VIDEOS -> stringResource(R.string.qa_latest_videos_sub, QuickAccess.LATEST_VIDEOS_LIMIT)
    QuickSourceKind.THIS_WEEK -> stringResource(R.string.qa_this_week_sub)
    else -> null
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, subtitle: String?, modifier: Modifier, onClick: () -> Unit) {
    val c = PM.colors
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Icon(icon, null, tint = c.accent)
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = AppTypography.caption, color = c.textSecondary)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.textSecondary)
    }
}

/** Entfernen und Sortieren mit großen Tippflächen (auch mit TalkBack bedienbar, ohne Ziehen). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(items: List<QuickAccessItem>, onMove: (QuickAccessItem, Int) -> Unit, onRemove: (QuickAccessItem) -> Unit, onDismiss: () -> Unit) {
    val c = PM.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.m).testTag("quick.editSheet"),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Text(stringResource(R.string.qa_edit_title), style = AppTypography.headline, color = c.textPrimary)
            items.forEachIndexed { i, q ->
                val title = quickTitle(q)
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
                    Icon(quickIcon(q.kind), null, tint = c.accent)
                    Text(title, style = AppTypography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = AppSpacing.xs))
                    EditIcon(Icons.Rounded.ArrowUpward, stringResource(R.string.qa_move_up, title), enabled = i > 0) { onMove(q, -1) }
                    EditIcon(Icons.Rounded.ArrowDownward, stringResource(R.string.qa_move_down, title), enabled = i < items.size - 1) { onMove(q, +1) }
                    EditIcon(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.qa_remove, title), enabled = true, tint = c.cleanup) { onRemove(q) }
                }
                if (i < items.size - 1) RowDivider()
            }
        }
    }
}

@Composable
private fun EditIcon(icon: ImageVector, label: String, enabled: Boolean, tint: androidx.compose.ui.graphics.Color = PM.colors.textSecondary, onClick: () -> Unit) {
    Box(Modifier.size(AppSpacing.minTouch).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(AppIconSize.regular))
    }
}
