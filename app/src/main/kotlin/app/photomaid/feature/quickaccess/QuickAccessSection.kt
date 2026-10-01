package app.photomaid.feature.quickaccess

import androidx.compose.ui.graphics.Color
import app.photomaid.design.coachTarget
import kotlinx.coroutines.delay
import app.photomaid.design.SectionTitle
import app.photomaid.design.RowStatus
import app.photomaid.design.RowGroup
import app.photomaid.design.RowDividerInset
import app.photomaid.design.ListRow
import app.photomaid.design.AppRadii
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
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
    QuickSourceKind.PHOTOS, QuickSourceKind.LATEST_PHOTOS -> Icons.Rounded.Photo
    QuickSourceKind.VIDEOS, QuickSourceKind.LATEST_VIDEOS -> Icons.Rounded.Videocam
    QuickSourceKind.SCREENSHOTS -> Icons.Rounded.Screenshot
    // Nie das Verlauf-Symbol: „Neueste“ unterscheidet sich über den Titel.
    QuickSourceKind.THIS_WEEK -> Icons.Rounded.CalendarMonth
    QuickSourceKind.FOLDER -> Icons.Rounded.Folder
}

/** Eigene, ruhige Farbe je Art (Kachel getönt, Symbol kräftig) – macht die Liste weniger „technisch“. */
@Composable
fun quickTint(kind: QuickSourceKind): Color {
    val c = PM.colors
    return when (kind) {
        QuickSourceKind.PHOTOS, QuickSourceKind.LATEST_PHOTOS -> c.accent
        QuickSourceKind.VIDEOS, QuickSourceKind.LATEST_VIDEOS -> c.amber
        QuickSourceKind.SCREENSHOTS -> c.later
        QuickSourceKind.THIS_WEEK -> c.success
        QuickSourceKind.FOLDER -> c.earth
    }
}

/** Status einer Quelle: Zahl offen · ✓ nichts offen · „Leer“ ohne Mitglieder. */
@Composable
fun rowStatusOf(status: SourceStatus?): RowStatus = when {
    status == null -> RowStatus.None
    status.members == 0 -> RowStatus.Label(stringResource(R.string.qa_empty))
    status.pending == 0 -> RowStatus.Done
    else -> RowStatus.Count(status.pending)
}

/**
 * „Schnellzugriffe“ auf Heute: Überschrift + eine Gruppe, eine Zeile je Quelle – Kachel · Name · Status rechts · Chevron –
 * und „Hinzufügen“ als letzte Zeile. Jede Zeile öffnet denselben Review-Feed; eine leere Quelle öffnet „Bearbeiten“.
 * Zahlen werden nie gespeichert: [snapshot] (eine Mediathek-Abfrage von „Heute“) + globale offene IDs ([pendingIds]).
 */
@Composable
fun QuickAccessSection(nav: Nav, snapshot: List<app.photomaid.media.MediaAsset>, pendingIds: Set<String>?) {
    val app = LocalApp.current
    val c = PM.colors
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val items by app.quickAccess.items.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<MediaFolder>?>(null) }
    var justAdded by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(justAdded) { if (justAdded != null) { delay(900); justAdded = null } }
    // Reine Filter im Speicher – kein weiterer MediaStore-Zugriff.
    val status = remember(items, snapshot, pendingIds) { pendingIds?.let { statusFor(items, snapshot, it) } ?: emptyMap() }

    Column(Modifier.testTag("today.quick").coachTarget("today.quick"), verticalArrangement = Arrangement.spacedBy(AppSpacing.headerGap)) {
        SectionTitle(stringResource(R.string.qa_title)) {
            if (items.isNotEmpty()) PlainButton(stringResource(R.string.qa_edit), Modifier.testTag("quick.edit"), style = AppTypography.subheadline) { editing = true }
        }
        RowGroup {
            items.forEachIndexed { i, q ->
                if (i > 0) RowDivider(RowDividerInset)
                val st = status[q.key]
                val title = quickTitle(q)
                val desc = when {
                    st == null -> ""
                    st.members == 0 -> stringResource(R.string.qa_empty)
                    st.pending == 0 -> stringResource(R.string.qa_all_done)
                    else -> pluralStringResource(R.plurals.qa_open, st.pending, st.pending)
                }
                androidx.compose.runtime.key(q.key) {
                    ListRow(quickIcon(q.kind), title, Modifier.testTag("quick.${q.key}"), tint = quickTint(q.kind), tile = quickTint(q.kind).copy(alpha = 0.13f),
                        status = rowStatusOf(st), description = "$title, $desc",
                        showChevron = st?.members != 0, highlight = justAdded == q.key) {
                        if (st?.members == 0) editing = true else nav.openFeed(FeedRequest(FeedRequest.Source.Quick(q)))
                    }
                }
            }
            if (items.size < QuickAccess.MAX_ITEMS) {
                if (items.isNotEmpty()) RowDivider(RowDividerInset)
                ListRow(Icons.Rounded.Add, stringResource(R.string.qa_add), Modifier.testTag("quick.add"), tile = c.accent.copy(alpha = 0.12f),
                    titleColor = c.accent, showChevron = false) {
                    folders = null
                    adding = true
                    // Außerhalb des Sheets laden (Zustand der Hauptkomposition).
                    scope.launch { folders = withContext(Dispatchers.IO) { runCatching { app.photos.folders() }.getOrDefault(emptyList()) } }
                }
            }
        }
    }
    if (adding) AddSheet(items, snapshot, pendingIds, folders, onAdd = { q ->
        adding = false
        if (app.settings.value.haptics) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        justAdded = q.key
        scope.launch { app.quickAccess.update { QuickAccess.add(it, q) } }
    }, onDismiss = { adding = false })
    if (editing) EditSheet(items, onMove = { q, d ->
        if (app.settings.value.haptics) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        scope.launch { app.quickAccess.update { QuickAccess.move(it, q, d) } }
    }, onRemove = { q -> scope.launch { app.quickAccess.update { QuickAccess.remove(it, q) } } }, onDismiss = { editing = false })
}

private val addable = listOf(QuickSourceKind.PHOTOS, QuickSourceKind.VIDEOS, QuickSourceKind.SCREENSHOTS,
    QuickSourceKind.LATEST_PHOTOS, QuickSourceKind.LATEST_VIDEOS, QuickSourceKind.THIS_WEEK)

/** So viele Ordner direkt im Sheet (nach Anzahl); der Rest über „Alle Ordner …“. */
private const val INLINE_FOLDERS = 5

/**
 * Stabiler Katalog: alle Quellen immer an derselben Stelle, schon vorhandene mit „Auf Heute“ (nicht antippbar),
 * jede mit ihrer echten offenen Anzahl. Ordner direkt darunter (die größten zuerst), Antippen fügt sofort hinzu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(current: List<QuickAccessItem>, snapshot: List<app.photomaid.media.MediaAsset>, pendingIds: Set<String>?,
                     folders: List<MediaFolder>?, onAdd: (QuickAccessItem) -> Unit, onDismiss: () -> Unit) {
    val c = PM.colors
    var allFolders by remember { mutableStateOf(false) }
    val full = current.size >= QuickAccess.MAX_ITEMS
    val onToday = stringResource(R.string.qa_on_today)
    val catalogue = remember { addable.map { QuickAccessItem(it) } }
    val folderItems = remember(folders) {
        folders?.sortedWith(compareByDescending<MediaFolder> { it.count }.thenBy { it.name.lowercase() })
            ?.map { it to QuickAccessItem(QuickSourceKind.FOLDER, it.relativePath) }
    }
    val status = remember(snapshot, pendingIds, folderItems) {
        pendingIds?.let { statusFor(catalogue + folderItems.orEmpty().map { it.second }, snapshot, it) } ?: emptyMap()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.m).testTag("quick.sheet"),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Text(stringResource(if (allFolders) R.string.qa_folders_header else R.string.qa_add_title), style = AppTypography.headline, color = c.textPrimary)
            if (full) { Text(stringResource(R.string.qa_limit, QuickAccess.MAX_ITEMS), style = AppTypography.footnote, color = c.textSecondary); return@Column }
            @Composable
            fun option(q: QuickAccessItem, tag: String, title: String = quickTitle(q), subtitle: String? = quickSubtitle(q)) {
                val present = current.any { it.key == q.key }
                ListRow(quickIcon(q.kind), title, Modifier.testTag(tag), subtitle = subtitle, tint = quickTint(q.kind), tile = quickTint(q.kind).copy(alpha = 0.13f),
                    status = if (present) RowStatus.Label(onToday) else rowStatusOf(status[q.key]), showChevron = false,
                    enabled = !present) { onAdd(q) }
            }
            LazyColumn(Modifier.heightIn(max = 560.dp)) {
                if (!allFolders) {
                    item(key = "catalogue") {
                        RowGroup {
                            catalogue.forEachIndexed { i, q ->
                                if (i > 0) RowDivider(RowDividerInset)
                                option(q, "quick.option.${q.key}")
                            }
                        }
                    }
                    item(key = "folders.header") { SectionTitle(stringResource(R.string.qa_folders_header), Modifier.padding(top = AppSpacing.block, bottom = AppSpacing.headerGap)) }
                }
                when {
                    folderItems == null -> item { Text(stringResource(R.string.folder_loading), style = AppTypography.footnote, color = c.textSecondary) }
                    folderItems.isEmpty() -> item { Text(stringResource(R.string.qa_no_folders), style = AppTypography.footnote, color = c.textSecondary) }
                    else -> item(key = "folders") {
                        val shown = if (allFolders) folderItems else folderItems.take(INLINE_FOLDERS)
                        RowGroup {
                            shown.forEachIndexed { i, (f, q) ->
                                if (i > 0) RowDivider(RowDividerInset)
                                option(q, "quick.folder.${f.relativePath}", title = f.name, subtitle = null)
                            }
                            if (!allFolders && folderItems.size > INLINE_FOLDERS) {
                                RowDivider(RowDividerInset)
                                ListRow(Icons.Rounded.FolderOpen, stringResource(R.string.qa_all_folders), Modifier.testTag("quick.option.folder"),
                                    status = RowStatus.Label("${folderItems.size}")) { allFolders = true }
                            }
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

/**
 * Bearbeiten: ⊖ links (gedämpftes Rot), Ziehgriff rechts (Ziehen sortiert, Haptik je Schritt), „Fertig“ oben.
 * TalkBack/Schalter: „Nach vorn“/„Nach hinten“ als Aktionen der Zeile – ohne Ziehen bedienbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(items: List<QuickAccessItem>, onMove: (QuickAccessItem, Int) -> Unit, onRemove: (QuickAccessItem) -> Unit, onDismiss: () -> Unit) {
    val c = PM.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val rowPx = with(LocalDensity.current) { AppSpacing.row.toPx() }
    val current by rememberUpdatedState(items)
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = c.background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.m).testTag("quick.editSheet"),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.qa_edit_title), style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.weight(1f))
                PlainButton(stringResource(R.string.done), Modifier.testTag("quick.edit.done"), style = AppTypography.headline) {
                    scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
                }
            }
            RowGroup {
                items.forEachIndexed { i, q ->
                    androidx.compose.runtime.key(q.key) {
                        val title = quickTitle(q)
                        val dragging = dragKey == q.key
                        val up = stringResource(R.string.qa_move_up, title); val down = stringResource(R.string.qa_move_down, title)
                        if (i > 0) RowDivider(RowDividerInset + AppSpacing.minTouch)
                        Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.row).zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragOffset else 0f; shadowElevation = if (dragging) 8.dp.toPx() else 0f }
                            .background(if (dragging) c.surface else androidx.compose.ui.graphics.Color.Transparent)
                            .testTag("quick.edit.${q.key}")
                            .semantics(mergeDescendants = true) {
                                customActions = listOfNotNull(
                                    if (i > 0) CustomAccessibilityAction(up) { onMove(q, -1); true } else null,
                                    if (i < items.size - 1) CustomAccessibilityAction(down) { onMove(q, +1); true } else null,
                                )
                            },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
                            EditIcon(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.qa_remove, title), tint = c.cleanup.copy(alpha = 0.8f)) { onRemove(q) }
                            Box(Modifier.size(32.dp).clip(RoundedCornerShape(AppRadii.tile)).background(c.backgroundTint), contentAlignment = Alignment.Center) {
                                Icon(quickIcon(q.kind), null, tint = c.accent, modifier = Modifier.size(20.dp))
                            }
                            Text(title, style = AppTypography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = AppSpacing.xs))
                            // Ziehgriff: verbraucht die Bewegung (das Sheet zieht nicht mit).
                            Box(Modifier.size(AppSpacing.minTouch).testTag("quick.drag.${q.key}").clearAndSetSemantics {}
                                .pointerInput(q.key) {
                                    detectDragGestures(
                                        onDragStart = { dragKey = q.key; dragOffset = 0f },
                                        onDragEnd = { dragKey = null; dragOffset = 0f },
                                        onDragCancel = { dragKey = null; dragOffset = 0f },
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val idx = current.indexOfFirst { it.key == q.key }
                                        if (dragOffset > rowPx / 2 && idx in 0 until current.size - 1) { onMove(q, +1); dragOffset -= rowPx }
                                        else if (dragOffset < -rowPx / 2 && idx > 0) { onMove(q, -1); dragOffset += rowPx }
                                    }
                                }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.DragHandle, null, tint = c.textSecondary, modifier = Modifier.size(AppIconSize.regular))
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.qa_remove_note), style = AppTypography.footnote, color = c.textSecondary, modifier = Modifier.padding(horizontal = AppSpacing.xxs))
            if (items.size >= QuickAccess.MAX_ITEMS) Text(stringResource(R.string.qa_limit, QuickAccess.MAX_ITEMS), style = AppTypography.footnote, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = AppSpacing.xxs))
        }
    }
}

@Composable
private fun EditIcon(icon: ImageVector, label: String, enabled: Boolean = true, tint: androidx.compose.ui.graphics.Color = PM.colors.textSecondary, onClick: () -> Unit) {
    Box(Modifier.size(AppSpacing.minTouch).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(AppIconSize.regular))
    }
}
