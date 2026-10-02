package app.photomaid.feature.review

import app.photomaid.design.coachTarget
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.BurstMode
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.model.MediaKind
import app.photomaid.core.organize.MediaFolder
import app.photomaid.core.organize.MediaFolders
import app.photomaid.design.AppIconSize
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.design.RowDivider
import app.photomaid.design.glassCapsule
import app.photomaid.media.MediaAsset

/**
 * Kompakte Aktionsleiste unter dem Medium: ♡ Favorit · Teilen · Ordner · ⋯ – daneben der Papierkorb-Zähler.
 * Liegt AUSSERHALB der Gestenfläche: ein Tipp hier kann nie wischen, entscheiden oder weiterblättern.
 */
@Composable
fun FeedActionBar(
    asset: MediaAsset?,
    basketCount: Int,
    busy: Boolean,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onFolder: () -> Unit,
    onInfo: () -> Unit,
    onOpenElsewhere: () -> Unit,
    onHints: () -> Unit,
    openBasket: () -> Unit,
    modifier: Modifier = Modifier,
    /** Fotoserie des aktuellen Mediums (≥ 3), sonst `null` – Knopf „Serie · n“ (keine Entscheidung). */
    seriesCount: Int? = null,
    seriesLocked: Boolean = false,
    onSeries: () -> Unit = {},
    onExcludeFolder: (() -> Unit)? = null,
    onGrid: (() -> Unit)? = null,
) {
    val c = PM.colors
    val enabled = asset != null && !busy
    var menu by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().testTag("feed.actions")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Row(Modifier.coachTarget("feed.actions").glassCapsule(elevation = 2.dp).padding(horizontal = AppSpacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                val fav = asset?.isFavorite == true
                ActionIcon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    stringResource(if (fav) R.string.fav_remove else R.string.fav_add), if (fav) c.cleanup else c.textPrimary, enabled,
                    Modifier.testTag("action.favorite"), state = stringResource(if (fav) R.string.fav_state_on else R.string.fav_state_off), onClick = onFavorite)
                ActionIcon(Icons.Rounded.Share, stringResource(R.string.share), c.textPrimary, asset != null, Modifier.testTag("action.share"), onClick = onShare)
                ActionIcon(Icons.Outlined.Folder, stringResource(R.string.folder_action), c.textPrimary, enabled, Modifier.testTag("action.folder"), onClick = onFolder)
                Box {
                    ActionIcon(Icons.Rounded.MoreHoriz, stringResource(R.string.more_actions), c.textPrimary, true, Modifier.testTag("action.more")) { menu = true }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.info)) }, leadingIcon = { Icon(Icons.Rounded.Info, null) },
                            enabled = asset != null, onClick = { menu = false; onInfo() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.open_in_app)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                            enabled = asset != null, onClick = { menu = false; onOpenElsewhere() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.feed_show_hints)) }, leadingIcon = { Icon(Icons.Rounded.PanTool, null) },
                            onClick = { menu = false; onHints() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.feed_view_basket)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = { menu = false; openBasket() })
                        if (onGrid != null) DropdownMenuItem(text = { Text(stringResource(R.string.grid_open)) }, leadingIcon = { Icon(Icons.Rounded.GridView, null) },
                            modifier = Modifier.testTag("action.grid"), onClick = { menu = false; onGrid() })
                        if (onExcludeFolder != null) DropdownMenuItem(text = { Text(stringResource(R.string.excl_action)) }, leadingIcon = { Icon(Icons.Rounded.FolderOff, null) },
                            modifier = Modifier.testTag("action.exclude"), enabled = asset != null, onClick = { menu = false; onExcludeFolder() })
                    }
                }
            }
            if (seriesCount != null) {
                val label = stringResource(R.string.series_button, seriesCount)
                Row(Modifier.heightIn(min = AppSpacing.minTouch).glassCapsule(elevation = 2.dp).clickable(enabled = !busy, role = Role.Button, onClick = onSeries)
                    .semantics(mergeDescendants = true) { contentDescription = label }.coachTarget("feed.series").testTag("action.series")
                    .padding(horizontal = AppSpacing.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.BurstMode, null, tint = c.accent, modifier = Modifier.size(AppIconSize.regular))
                    Text("$seriesCount", style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
                    if (seriesLocked) Icon(Icons.Rounded.WorkspacePremium, null, tint = c.accent, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            BasketChip(basketCount, Modifier.testTag("feed.dock").coachTarget("feed.basket"), openBasket)
        }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, tint: Color, enabled: Boolean, modifier: Modifier = Modifier, state: String? = null, onClick: () -> Unit) {
    // Sichtbar klein (24 dp), Touch-Ziel 48 dp.
    Box(modifier.size(AppSpacing.minTouch).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label; if (state != null) stateDescription = state }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.38f), modifier = Modifier.size(AppIconSize.regular + 2.dp))
    }
}

@Composable
private fun BasketChip(count: Int, modifier: Modifier, onClick: () -> Unit) {
    val c = PM.colors
    val hint = stringResource(R.string.dock_hint)
    val label = stringResource(R.string.dock_count, count)
    Row(modifier.heightIn(min = AppSpacing.minTouch + 4.dp).glassCapsule(elevation = 2.dp)
        .clickable(role = Role.Button, onClickLabel = hint, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = label }
        .padding(horizontal = AppSpacing.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Icon(Icons.Rounded.Delete, null, tint = c.cleanup, modifier = Modifier.size(AppIconSize.regular))
        // Symbol + Zahl (passt auch auf 320 dp); TalkBack liest „Papierkorb: n“.
        Text("$count", style = AppTypography.headline, color = c.textPrimary, maxLines = 1)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.textSecondary)
    }
}

/** Geschützter Favorit nach links gewischt: kurze, verständliche Rückfrage. */
@Composable
fun ProtectedFavoriteDialog(p: ProtectedFavorite, onUnfavorite: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, modifier = Modifier.testTag("feed.protected"),
        title = { Text(stringResource(if (p.isVideo) R.string.fav_protected_video else R.string.fav_protected_photo)) },
        text = { Text(stringResource(R.string.fav_protected_text)) },
        confirmButton = { TextButton(onUnfavorite) { Text(stringResource(R.string.fav_remove)) } },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.cancel)) } })
}

/**
 * „In Ordner verschieben“: oben angepinnte (max. 5) und zuletzt verwendete Ziele, Suche, dann alle erlaubten Ordner
 * (häufigste zuerst) und „Neuer Ordner“. Bewusst nicht „Album“: Android verschiebt die Datei in einen Ordner; ob die
 * Galerie ihn als Album zeigt, hängt von der Galerie-App ab. [kind] bestimmt die erlaubten Ziele (Fotos/Videos);
 * [current] (Ordner des Mediums) ist markiert und nicht wählbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSheet(
    kind: MediaKind,
    current: String?,
    folders: List<MediaFolder>?,
    pinned: List<String>,
    recent: List<String>,
    onTogglePin: (String) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.folder_action),
) {
    val c = PM.colors
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val cur = current?.let { MediaFolders.normalize(it) }
    fun pick(path: String) { onDismiss(); onPick(path) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.m).testTag("folder.sheet"),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Text(title, style = AppTypography.headline, color = c.textPrimary)
            Text(stringResource(R.string.folder_note), style = AppTypography.caption, color = c.textSecondary)
            val all = folders
            if (all == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.folder_loading), style = AppTypography.footnote, color = c.textSecondary)
                }
            } else {
                val allowed = MediaFolders.choices(all, kind)
                if (allowed.size > 5) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("folder.search"), singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text(stringResource(R.string.folder_search)) })
                val list = MediaFolders.choices(all, kind, query)
                val byPath = allowed.associateBy { it.relativePath.lowercase() }
                fun resolve(paths: List<String>) = paths.mapNotNull { byPath[MediaFolders.normalize(it).lowercase()] }
                    .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                val pins = resolve(pinned)
                val recents = resolve(recent).filter { r -> pins.none { it.relativePath.equals(r.relativePath, ignoreCase = true) } }
                if (list.isEmpty()) Text(stringResource(R.string.folder_none), style = AppTypography.footnote, color = c.textSecondary)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    fun section(header: Int, rows: List<MediaFolder>, prefix: String) {
                        if (rows.isEmpty()) return
                        item(key = "h$prefix") {
                            Text(stringResource(header), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary,
                                modifier = Modifier.padding(top = AppSpacing.xs, bottom = 2.dp))
                        }
                        items(rows, key = { prefix + it.relativePath }) { f ->
                            val isPinned = pinned.any { MediaFolders.normalize(it).equals(f.relativePath, ignoreCase = true) }
                            FolderRow(f, f.relativePath.equals(cur, ignoreCase = true), isPinned, canPin = isPinned || pinned.size < MAX_PINNED,
                                onPin = { onTogglePin(f.relativePath) }) { pick(f.relativePath) }
                            RowDivider()
                        }
                    }
                    section(R.string.folder_pinned, pins, "p:")
                    section(R.string.folder_recent, recents, "r:")
                    section(R.string.folder_all, list, "a:")
                }
            }
            if (!creating) {
                Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.minTouch).clickable(role = Role.Button) { creating = true }.testTag("folder.new"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                    Icon(Icons.Rounded.CreateNewFolder, null, tint = c.accent)
                    Text(stringResource(R.string.folder_new), style = AppTypography.body, color = c.accent)
                }
            } else {
                fun create() {
                    val path = MediaFolders.newFolderPath(name)
                    if (path == null) invalid = true else pick(path)
                }
                OutlinedTextField(name, { name = it; invalid = false }, Modifier.fillMaxWidth().testTag("folder.name"), singleLine = true,
                    label = { Text(stringResource(R.string.folder_name)) }, isError = invalid,
                    supportingText = if (invalid) ({ Text(stringResource(R.string.folder_invalid)) }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { create() }))
                ProminentButton(stringResource(R.string.folder_create_move), fillWidth = true, enabled = name.isNotBlank(), onClick = ::create)
            }
            Spacer(Modifier.size(AppSpacing.xxs))
        }
    }
}

/** Höchstens so viele angepinnte Ziele. */
const val MAX_PINNED = 5
/** So viele zuletzt verwendete Ziele werden gemerkt. */
const val MAX_RECENT = 5

@Composable
private fun FolderRow(f: MediaFolder, isCurrent: Boolean, isPinned: Boolean, canPin: Boolean, onPin: () -> Unit, onClick: () -> Unit) {
    val c = PM.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !isCurrent, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Icon(Icons.Rounded.Folder, null, tint = if (isCurrent) c.textSecondary else c.accent)
        Column(Modifier.weight(1f)) {
            Text(f.name, style = AppTypography.body, color = if (isCurrent) c.textSecondary else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Echter Ablageort – damit klar ist, was passiert.
            Text(if (isCurrent) stringResource(R.string.folder_current) else f.relativePath.trimEnd('/'), style = AppTypography.caption,
                color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(pluralStringResource(R.plurals.items_count, f.count, f.count), style = AppTypography.caption, color = c.textSecondary)
        val pinLabel = stringResource(if (isPinned) R.string.folder_unpin else R.string.folder_pin)
        Box(Modifier.size(AppSpacing.minTouch).clickable(enabled = canPin, role = Role.Button, onClick = onPin)
            .semantics { contentDescription = pinLabel }.testTag("folder.pin"), contentAlignment = Alignment.Center) {
            Icon(if (isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, null,
                tint = if (isPinned) c.accent else c.textSecondary.copy(alpha = if (canPin) 1f else 0.38f), modifier = Modifier.size(20.dp))
        }
    }
}

/** Kurzer Hinweis nach belegtem Erfolg (unten im Feed, blendet selbst aus). */
@Composable
fun NoticePill(text: String) {
    val c = PM.colors
    Row(Modifier.glassCapsule().heightIn(min = AppSpacing.minTouch).padding(horizontal = AppSpacing.m).testTag("feed.notice"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
    }
}

internal fun MediaAsset.isVideo() = kind == MediaKind.VIDEO
