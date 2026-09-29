package app.photomaid.feature.cleanup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.photomaid.AppModel
import app.photomaid.CleanupRunResult
import app.photomaid.R
import app.photomaid.core.cleanup.CleanupError
import app.photomaid.core.cleanup.CleanupOperationKind
import app.photomaid.core.cleanup.OperationState
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.model.ItemProvider
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.MediaKind
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.DateText
import app.photomaid.design.EmptyState
import app.photomaid.design.GlassButton
import app.photomaid.design.ListSection
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.design.RowDivider
import app.photomaid.design.ScreenScaffold
import app.photomaid.design.SizeText
import app.photomaid.feature.common.ConfirmationSheet
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.feature.common.ThumbnailView
import app.photomaid.media.MediaAsset
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen 5 – Papierkorb (PhotoMaid-eigen, nichts ist bisher gelöscht). Reale Kandidaten, Vorschau, Abwahl,
 * genaue Beschreibung der Operation. Gemischter Korb wird nach Provider getrennt bestätigt.
 */
@Composable
fun CleanupBasketScreen(nav: Nav, showsDone: Boolean) {
    val app = LocalApp.current
    val c = PM.colors
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val read by app.read.collectAsState()
    val basket = read.basket
    val photoItems = basket.filter { it.item.provider == ItemProvider.PhotoLibrary }
    val fileItems = basket.filter { it.item.provider != ItemProvider.PhotoLibrary }
    var selection by remember { mutableStateOf<Set<ItemReference>>(emptySet()) }
    var known by remember { mutableStateOf<Set<ItemReference>>(emptySet()) }
    var assets by remember { mutableStateOf<Map<String, MediaAsset>>(emptyMap()) }
    var fileNames by remember { mutableStateOf<Map<ItemReference, String>>(emptyMap()) }
    var confirmPhotos by remember { mutableStateOf(false) }
    var confirmFiles by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var showProAfter by remember { mutableStateOf(false) }

    LaunchedEffect(basket.map { it.item }) {
        val all = basket.map { it.item }.toSet()
        // Abwahl bleibt erhalten; neu hinzugekommene Items sind ausgewählt (iOS setzt hier alles zurück, s. Matrix).
        selection = (selection intersect all) + (all - known)
        known = all
        withContext(Dispatchers.IO) {
            assets = app.photos.assets(photoItems.map { it.item.localIdentifier }).associateBy { it.id }
            fileNames = fileItems.associate { it.item to app.sources.displayName(it.item) }
        }
    }
    val selectedPhotos = photoItems.map { it.item }.filter { it in selection }
    val selectedFiles = fileItems.map { it.item }.filter { it in selection }

    fun run(items: List<ItemReference>, kind: CleanupOperationKind) = scope.launch {
        running = true
        resultMessage = when (val r = app.runCleanup(items, kind)) {
            is CleanupRunResult.Finished -> when (r.intent.state) {
                OperationState.SUCCEEDED -> if (kind == CleanupOperationKind.PHOTO_LIBRARY_DELETE)
                    res.getQuantityString(R.plurals.result_media_trashed, r.intent.confirmedRemoved.size, r.intent.confirmedRemoved.size)
                else res.getQuantityString(R.plurals.result_files_deleted, r.intent.confirmedRemoved.size, r.intent.confirmedRemoved.size)
                OperationState.PARTIALLY_SUCCEEDED -> res.getQuantityString(R.plurals.result_partial, r.intent.confirmedRemoved.size, r.intent.confirmedRemoved.size, r.intent.items.size)
                OperationState.CANCELLED -> res.getString(R.string.result_cancelled)
                else -> res.getString(R.string.result_nothing)
            }
            is CleanupRunResult.Rejected -> when (val e = r.error) {
                is CleanupError.AllowanceExceeded -> { if (e.available == 0) showProAfter = true; res.getQuantityString(R.plurals.result_allowance, e.available, e.available, e.requested) }
                else -> res.getString(R.string.result_changed)
            }
            CleanupRunResult.NeedsReconciliation -> res.getString(R.string.result_reconcile)
        }
        running = false
    }

    ScreenScaffold(
        title = stringResource(R.string.basket),
        onBack = if (showsDone) null else nav::back,
        actions = { if (showsDone) TextButton(nav::back) { Text(stringResource(R.string.done), fontWeight = FontWeight.SemiBold) } },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs), horizontalAlignment = Alignment.CenterHorizontally) {
                if (selectedPhotos.isNotEmpty()) ProminentButton(pluralStringResource(R.plurals.basket_empty_media, selectedPhotos.size, selectedPhotos.size), fillWidth = true, large = true,
                    enabled = !running) { confirmPhotos = true }
                if (selectedFiles.isNotEmpty()) GlassButton(pluralStringResource(R.plurals.basket_delete_files, selectedFiles.size, selectedFiles.size), fillWidth = true, large = true,
                    tint = c.cleanup, enabled = !running) { confirmFiles = true }
                if (running) CircularProgressIndicator(Modifier.size(28.dp))
            }
        },
    ) { mod ->
        if (basket.isEmpty()) {
            Box(mod, contentAlignment = Alignment.Center) {
                EmptyState(Icons.Rounded.Inbox, stringResource(R.string.basket_empty_title), stringResource(R.string.basket_empty_message))
            }
        } else {
            LazyColumn(mod.padding(horizontal = AppSpacing.pageMargin), verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Text(stringResource(R.string.basket_count, basket.size), style = AppTypography.title, color = c.textPrimary)
                        Text(stringResource(R.string.basket_intro), style = AppTypography.caption, color = c.textSecondary)
                        val a = read.allowance
                        val available = a?.available
                        if (a != null && available != null) {
                            Text(stringResource(R.string.basket_allowance, available, a.limit ?: 0), style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = if (available == 0) c.cleanup else c.textSecondary)
                        }
                    }
                }
                if (photoItems.isNotEmpty()) item {
                    ListSection(header = stringResource(R.string.basket_media_header), footer = stringResource(R.string.basket_media_footer)) {
                        photoItems.forEachIndexed { i, r ->
                            if (i > 0) RowDivider()
                            val a = assets[r.item.localIdentifier]
                            val title = stringResource(when (a?.kind) { MediaKind.VIDEO -> R.string.video; MediaKind.SCREENSHOT -> R.string.screenshot; else -> R.string.photo })
                            val sub = listOfNotNull(a?.creationDate?.let { DateText.abbreviated(it) }, a?.size?.let { SizeText.size(ctx, it) }).joinToString(" · ")
                            BasketRow(app, r, title, sub, r.item in selection, { ThumbnailView(r.item.localIdentifier) }) {
                                selection = if (r.item in selection) selection - r.item else selection + r.item
                            }
                        }
                    }
                }
                if (fileItems.isNotEmpty()) item {
                    ListSection(header = stringResource(R.string.basket_files_header), footer = stringResource(R.string.basket_files_footer)) {
                        fileItems.forEachIndexed { i, r ->
                            if (i > 0) RowDivider()
                            val name = fileNames[r.item] ?: r.item.localIdentifier.substringAfterLast('/').substringAfterLast(':')
                            BasketRow(app, r, name, "", r.item in selection, { ThumbnailView(null) }) {
                                selection = if (r.item in selection) selection - r.item else selection + r.item
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(AppSpacing.l)) }
            }
        }
    }
    if (confirmPhotos) ConfirmationSheet(stringResource(R.string.basket_confirm_media_title), stringResource(R.string.basket_confirm_media_message),
        stringResource(R.string.basket_confirm_media_action, selectedPhotos.size), onConfirm = { run(selectedPhotos, CleanupOperationKind.PHOTO_LIBRARY_DELETE) },
        onDismiss = { confirmPhotos = false })
    if (confirmFiles) ConfirmationSheet(stringResource(R.string.basket_confirm_files_title), stringResource(R.string.basket_confirm_files_message),
        stringResource(R.string.basket_confirm_files_action, selectedFiles.size), onConfirm = { run(selectedFiles, CleanupOperationKind.FILE_DELETE_PERMANENTLY) },
        onDismiss = { confirmFiles = false })
    NoticeDialog(resultMessage, stringResource(R.string.result)) {
        resultMessage = null
        if (showProAfter) { showProAfter = false; nav.openPro() }
    }
}

@Composable
private fun BasketRow(app: AppModel, r: ItemRecord, title: String, subtitle: String, selected: Boolean, thumb: @Composable () -> Unit, toggle: () -> Unit) {
    val c = PM.colors
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val selLabel = stringResource(if (selected) R.string.selected else R.string.not_selected)
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Box(Modifier.size(AppSpacing.minTouch).clickable(role = Role.Checkbox, onClick = toggle).semantics { contentDescription = "$selLabel: $title" },
            contentAlignment = Alignment.Center) {
            Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (selected) c.cleanup else c.textSecondary)
        }
        thumb()
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = AppTypography.caption, color = c.textSecondary)
        }
        Box {
            val label = stringResource(R.string.item_actions_for, title)
            Box(Modifier.size(AppSpacing.minTouch).clickable { menu = true }.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MoreHoriz, null, tint = c.textSecondary)
            }
            // iOS: Wischaktionen „Behalten“/„Zurücklegen“ (ohne Vollwisch). Android: Aktionsmenü.
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.keep)) }, leadingIcon = { Icon(Icons.Rounded.Check, null, tint = c.keep) },
                    onClick = { menu = false; scope.launch { app.decide(r.item, DecisionAction.Keep) } })
                DropdownMenuItem({ Text(stringResource(R.string.put_back)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Undo, null) },
                    onClick = { menu = false; scope.launch { app.decide(r.item, DecisionAction.Unmark) } })
            }
        }
    }
}

