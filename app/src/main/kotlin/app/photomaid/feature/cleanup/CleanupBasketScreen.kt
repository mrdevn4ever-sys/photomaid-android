package app.photomaid.feature.cleanup

import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.HistoryKind
import app.photomaid.core.model.KnownSize
import app.photomaid.core.cleanup.CleanupSavings
import app.photomaid.feature.review.LIMIT_SOON
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import app.photomaid.navigation.Routes
import app.photomaid.feature.editor.Chip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import app.photomaid.design.PlainButton
import androidx.compose.runtime.Composable
import app.photomaid.ads.RewardedAdResult
import app.photomaid.core.capabilities.LimitBonus
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
import app.photomaid.design.spacedItem
import app.photomaid.design.lazyListSection
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
/** Reihenfolge im PhotoMaid-Papierkorb (nur Ansicht): zuletzt hineingelegt, Aufnahmedatum, Größe (unbekannt am Ende). */
enum class BasketSort(val label: Int) { ADDED(R.string.basket_sort_added), TAKEN(R.string.basket_sort_taken), SIZE(R.string.lib_sort_largest) }

private val CleanupOperationKind.isPhotoLibrary get() =
    this == CleanupOperationKind.PHOTO_LIBRARY_DELETE || this == CleanupOperationKind.PHOTO_LIBRARY_DELETE_PERMANENTLY

@Composable
fun CleanupBasketScreen(nav: Nav, showsDone: Boolean) {
    val app = LocalApp.current
    val c = PM.colors
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val read by app.read.collectAsState()
    val basket = read.basket
    val settingsNow by app.settings.collectAsState()
    var assets by remember { mutableStateOf<Map<String, MediaAsset>>(emptyMap()) }
    // Zeitpunkt des Hineinlegens = letzte Entscheidung „In den Papierkorb“ im Verlauf (nichts zusätzlich gespeichert).
    val placedAt = remember(read.events) {
        read.events.filter { it.to.disposition == Disposition.MARKED_FOR_CLEANUP }.groupBy { it.item }.mapValues { (_, v) -> v.maxOf { it.at } }
    }
    val photoItems = remember(basket, assets, settingsNow.basketSort, placedAt) {
        val p = basket.filter { it.item.provider == ItemProvider.PhotoLibrary }
        when (settingsNow.basketSort) {
            BasketSort.ADDED -> p.sortedByDescending { placedAt[it.item] ?: java.time.Instant.MIN }
            BasketSort.TAKEN -> p.sortedWith(compareBy<ItemRecord> { if (assets[it.item.localIdentifier]?.creationDate == null) 1 else 0 }
                .thenByDescending { assets[it.item.localIdentifier]?.creationDate })
            BasketSort.SIZE -> p.sortedWith(compareBy<ItemRecord> { if (assets[it.item.localIdentifier]?.size?.bytesOrNull == null) 1 else 0 }
                .thenByDescending { assets[it.item.localIdentifier]?.size?.bytesOrNull ?: 0L })
        }
    }
    val photoKind = if (settingsNow.deletePermanently) CleanupOperationKind.PHOTO_LIBRARY_DELETE_PERMANENTLY else CleanupOperationKind.PHOTO_LIBRARY_DELETE
    val fileItems = basket.filter { it.item.provider != ItemProvider.PhotoLibrary }
    var selection by remember { mutableStateOf<Set<ItemReference>>(emptySet()) }
    var known by remember { mutableStateOf<Set<ItemReference>>(emptySet()) }
    var fileNames by remember { mutableStateOf<Map<ItemReference, String>>(emptyMap()) }
    // Offene Bestätigung mit genau den Elementen, die entfernt würden (ggf. auf das Tageslimit gekürzt).
    var confirmPhotos by remember { mutableStateOf<List<ItemReference>?>(null) }
    var confirmFiles by remember { mutableStateOf<List<ItemReference>?>(null) }
    // Auswahl größer als das heute noch freie Limit: VOR jeder Bestätigung erklären (keine Sackgasse).
    var limitFor by remember { mutableStateOf<Pair<List<ItemReference>, CleanupOperationKind>?>(null) }
    var previewAsset by remember { mutableStateOf<MediaAsset?>(null) }
    var running by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var resultTitle by remember { mutableIntStateOf(R.string.result) }
    val haptics = LocalHapticFeedback.current
    val settings by app.settings.collectAsState()
    var showProAfter by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<CleanupDone?>(null) }

    LaunchedEffect(basket.map { it.item }) {
        val all = basket.map { it.item }.toSet()
        // Abwahl bleibt erhalten; neu hinzugekommene Items sind ausgewählt (iOS setzt hier alles zurück, s. Matrix).
        selection = (selection intersect all) + (all - known)
        known = all
        withContext(Dispatchers.IO) {
            assets = app.photos.assets(basket.filter { it.item.provider == ItemProvider.PhotoLibrary }.map { it.item.localIdentifier }).associateBy { it.id }
            fileNames = fileItems.associate { it.item to app.sources.displayName(it.item) }
        }
    }
    val selectedPhotos = photoItems.map { it.item }.filter { it in selection }
    val selectedFiles = fileItems.map { it.item }.filter { it in selection }
    val available = read.allowance?.available
    // Ordner-Dateien: Papierkorb des Speicherorts, wenn er für alle gewählten Dateien angeboten wird (Android 17+).
    var filesTrashable by remember { mutableStateOf(false) }
    LaunchedEffect(selectedFiles) { filesTrashable = withContext(Dispatchers.IO) { app.sources.supportsTrash(selectedFiles) } }
    val fileKind = if (filesTrashable) CleanupOperationKind.FILE_MOVE_TO_TRASH else CleanupOperationKind.FILE_DELETE_PERMANENTLY

    fun request(items: List<ItemReference>, kind: CleanupOperationKind) {
        if (available != null && items.size > available) limitFor = items to kind
        else if (kind.isPhotoLibrary) confirmPhotos = items else confirmFiles = items
    }

    // Video-Bonus „+10 heute“ aus dem Limit-Blatt. Erst nach dem Reward-Callback gilt die neue Zahl; dann geht es
    // mit derselben Auswahl direkt weiter (Bestätigung bzw. erneutes Limit-Blatt mit neuer Zahl).
    fun watchBonus(items: List<ItemReference>, kind: CleanupOperationKind) = scope.launch {
        limitFor = null
        running = true
        val r = app.entitlements.watchLimitBonusAd { app.player.stop() }
        app.reload()
        running = false
        when (r) {
            is RewardedAdResult.Rewarded -> {
                val left = app.read.value.allowance?.available
                if (left != null && items.size > left) limitFor = items to kind
                else if (kind.isPhotoLibrary) confirmPhotos = items else confirmFiles = items
            }
            RewardedAdResult.DismissedWithoutReward -> { resultTitle = R.string.notice; resultMessage = res.getString(R.string.limit_bonus_dismissed) }
            else -> { resultTitle = R.string.notice; resultMessage = res.getString(R.string.limit_bonus_unavailable) }
        }
    }

    fun run(items: List<ItemReference>, kind: CleanupOperationKind) = scope.launch {
        running = true
        // Größen VOR dem Löschen festhalten (danach gibt es sie nicht mehr). Fehlende = unbekannt, nie 0.
        val sizes = withContext(Dispatchers.IO) {
            items.associateWith { ref ->
                if (ref.provider == ItemProvider.PhotoLibrary) assets[ref.localIdentifier]?.size ?: KnownSize.Unknown else app.sources.sizeOf(ref)
            }
        }
        val r = app.runCleanup(items, kind)
        // Nur die Überschrift je Ausgang (Meldungstexte und Semantik unverändert).
        resultTitle = when (r) {
            is CleanupRunResult.Finished -> when (r.intent.state) {
                OperationState.SUCCEEDED -> R.string.result_title_done
                OperationState.PARTIALLY_SUCCEEDED -> R.string.result_title_partial
                else -> R.string.result_title_not_done
            }
            is CleanupRunResult.Rejected -> if (r.error is CleanupError.AllowanceExceeded) R.string.result_title_limit else R.string.notice
            CleanupRunResult.NeedsReconciliation -> R.string.notice
        }
        if (settings.haptics && r is CleanupRunResult.Finished && r.intent.state == OperationState.SUCCEEDED) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        resultMessage = when (r) {
            is CleanupRunResult.Finished -> when (r.intent.state) {
                OperationState.SUCCEEDED -> if (kind == CleanupOperationKind.PHOTO_LIBRARY_DELETE)
                    res.getQuantityString(R.plurals.result_media_trashed, r.intent.confirmedRemoved.size, r.intent.confirmedRemoved.size)
                else if (kind == CleanupOperationKind.PHOTO_LIBRARY_DELETE_PERMANENTLY)
                    res.getQuantityString(R.plurals.result_media_deleted, r.intent.confirmedRemoved.size, r.intent.confirmedRemoved.size)
                else if (kind == CleanupOperationKind.FILE_MOVE_TO_TRASH)
                    res.getQuantityString(R.plurals.result_files_trashed, r.intent.confirmedRemoved.size, r.intent.confirmedRemoved.size)
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
        // Erfolg (auch teilweise): statt Meldung der „Gespart“-Bildschirm – nur mit tatsächlich entfernten Elementen.
        if (r is CleanupRunResult.Finished && r.intent.confirmedRemoved.isNotEmpty() &&
            (r.intent.state == OperationState.SUCCEEDED || r.intent.state == OperationState.PARTIALLY_SUCCEEDED)) {
            val saving = CleanupSavings.of(r.intent.confirmedRemoved, sizes)
            app.updateSettings { it.copy(savedTotalBytes = it.savedTotalBytes + saving.bytes, savedTotalLowerBound = it.savedTotalLowerBound || saving.isLowerBound) }
            val st = app.settings.value
            val zone = java.time.ZoneId.systemDefault(); val today = java.time.LocalDate.now(zone)
            val kept = app.read.value.events.count { e ->
                e.to.disposition == Disposition.KEPT && e.kind != HistoryKind.UNDO && e.at.atZone(zone).toLocalDate() == today
            }
            done = CleanupDone(saving, kind, resultMessage ?: "", r.intent.state == OperationState.PARTIALLY_SUCCEEDED, kept,
                st.savedTotalBytes, st.savedTotalLowerBound)
            resultMessage = null
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
                if (selectedPhotos.isNotEmpty()) ProminentButton(pluralStringResource(R.plurals.basket_empty_media, selectedPhotos.size, selectedPhotos.size), Modifier.testTag("basket.empty.media"), fillWidth = true, large = true,
                    enabled = !running) { request(selectedPhotos, photoKind) }
                if (selectedFiles.isNotEmpty()) GlassButton(pluralStringResource(if (filesTrashable) R.plurals.basket_trash_files else R.plurals.basket_delete_files,
                    selectedFiles.size, selectedFiles.size), fillWidth = true, large = true,
                    tint = c.cleanup, enabled = !running) { request(selectedFiles, fileKind) }
                if (running) CircularProgressIndicator(Modifier.size(28.dp))
            }
        },
    ) { mod ->
        if (basket.isEmpty()) {
            Box(mod, contentAlignment = Alignment.Center) {
                EmptyState(Icons.Rounded.Inbox, stringResource(R.string.basket_empty_title), stringResource(R.string.basket_empty_message))
            }
        } else {
            val mediaHeader = stringResource(R.string.basket_media_header); val mediaFooter = stringResource(R.string.basket_media_footer)
            val filesHeader = stringResource(R.string.basket_files_header); val filesFooter = stringResource(R.string.basket_files_footer)
            // Lazy: bei hunderten Einträgen werden nur sichtbare Zeilen (und Vorschaubilder) geladen.
            LazyColumn(mod.padding(horizontal = AppSpacing.pageMargin)) {
                spacedItem("intro") {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Text(stringResource(R.string.basket_count, basket.size), style = AppTypography.title, color = c.textPrimary)
                        Text(stringResource(if (settingsNow.deletePermanently) R.string.basket_intro_delete else R.string.basket_intro), style = AppTypography.caption, color = c.textSecondary)
                        if (photoItems.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("basket.sort"),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                            for (b in BasketSort.entries) Chip(stringResource(b.label), settingsNow.basketSort == b, Modifier.testTag("basket.sort.${b.name}")) {
                                scope.launch { app.updateSettings { it.copy(basketSort = b) } } }
                        }
                        PlainButton(stringResource(R.string.systrash_open), Modifier.testTag("basket.systrash")) { nav.push(Routes.SYSTEM_TRASH) }
                        val a = read.allowance
                        val available = a?.available
                        if (a != null && available != null) {
                            Text(stringResource(R.string.basket_allowance, available, a.limit ?: 0), style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = if (available == 0) c.textPrimary else c.textSecondary)
                            if (available < LIMIT_SOON) PlainButton(stringResource(R.string.limit_soon_pro), Modifier.testTag("basket.limit.soon"), onClick = nav::openPro)
                        }
                    }
                }
                if (photoItems.isNotEmpty()) lazyListSection("media", photoItems, { it.item.localIdentifier }, header = mediaHeader, footer = mediaFooter) { r ->
                    val a = assets[r.item.localIdentifier]
                    val title = stringResource(when (a?.kind) { MediaKind.VIDEO -> R.string.video; MediaKind.SCREENSHOT -> R.string.screenshot; else -> R.string.photo })
                    val sub = listOfNotNull(a?.creationDate?.let { DateText.abbreviated(it) }, a?.size?.let { SizeText.size(ctx, it) }).joinToString(" · ")
                    val openLabel = stringResource(R.string.basket_preview_open, title)
                    BasketRow(app, r, title, sub, r.item in selection, {
                        // Tipp aufs Vorschaubild = große Ansicht; ändert die Auswahl nicht.
                        ThumbnailView(r.item.localIdentifier, size = 60.dp, modifier = Modifier.testTag("basket.thumb")
                            .clickable(enabled = a != null, role = Role.Button, onClickLabel = openLabel) { previewAsset = a }
                            .semantics { contentDescription = openLabel })
                    }) {
                        selection = if (r.item in selection) selection - r.item else selection + r.item
                    }
                }
                if (fileItems.isNotEmpty()) lazyListSection("files", fileItems, { it.item.localIdentifier }, header = filesHeader, footer = filesFooter) { r ->
                    val name = fileNames[r.item] ?: r.item.localIdentifier.substringAfterLast('/').substringAfterLast(':')
                    BasketRow(app, r, name, "", r.item in selection, { ThumbnailView(null) }) {
                        selection = if (r.item in selection) selection - r.item else selection + r.item
                    }
                }
                item { Spacer(Modifier.height(AppSpacing.l)) }
            }
        }
    }
    confirmPhotos?.let { items ->
        if (photoKind == CleanupOperationKind.PHOTO_LIBRARY_DELETE_PERMANENTLY) ConfirmationSheet(stringResource(R.string.basket_confirm_delete_title),
            stringResource(R.string.basket_confirm_delete_message), pluralStringResource(R.plurals.basket_confirm_delete_action, items.size, items.size),
            onConfirm = { run(items, photoKind) }, onDismiss = { confirmPhotos = null })
        else ConfirmationSheet(stringResource(R.string.basket_confirm_media_title), stringResource(R.string.basket_confirm_media_message),
            stringResource(R.string.basket_confirm_media_action, items.size), onConfirm = { run(items, CleanupOperationKind.PHOTO_LIBRARY_DELETE) },
            onDismiss = { confirmPhotos = null })
    }
    confirmFiles?.let { items ->
        if (fileKind == CleanupOperationKind.FILE_MOVE_TO_TRASH) ConfirmationSheet(stringResource(R.string.basket_confirm_files_trash_title),
            stringResource(R.string.basket_confirm_files_trash_message), pluralStringResource(R.plurals.basket_trash_files, items.size, items.size),
            onConfirm = { run(items, CleanupOperationKind.FILE_MOVE_TO_TRASH) }, onDismiss = { confirmFiles = null })
        else ConfirmationSheet(stringResource(R.string.basket_confirm_files_title), stringResource(R.string.basket_confirm_files_message),
            stringResource(R.string.basket_confirm_files_action, items.size), onConfirm = { run(items, CleanupOperationKind.FILE_DELETE_PERMANENTLY) },
            onDismiss = { confirmFiles = null })
    }
    limitFor?.let { (items, kind) ->
        LimitSheet(available ?: 0, read.allowance?.limit ?: 0, items.size,
            bonus = if (app.entitlements.canOfferLimitBonus) ({ watchBonus(items, kind) }) else null,
            takePartial = { limitFor = null; request(items.take(available ?: 0), kind) },
            openPro = { limitFor = null; nav.openPro() }, onDismiss = { limitFor = null })
    }
    previewAsset?.let { a -> BasketPreview(a) { previewAsset = null } }
    done?.let { d ->
        // Vollbild über dem Papierkorb; „Fertig“/Zurück führt zurück (Heute bzw. Feed).
        androidx.compose.ui.window.Dialog(onDismissRequest = { done = null; nav.back() },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            CleanupDoneView(d) { done = null; nav.back() }
        }
    }
    NoticeDialog(resultMessage, stringResource(resultTitle)) {
        resultMessage = null
        if (showProAfter) { showProAfter = false; nav.openPro() }
    }
}

/** Tageslimit reicht nicht für die Auswahl: erklären, Teilmenge oder Pro anbieten – bevor Android fragt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LimitSheet(available: Int, limit: Int, selected: Int, bonus: (() -> Unit)?, takePartial: () -> Unit, openPro: () -> Unit, onDismiss: () -> Unit) {
    val c = PM.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.s).testTag("basket.limit"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Text(if (available > 0) stringResource(R.string.limit_title_partial, available) else stringResource(R.string.limit_title_none),
                style = AppTypography.headline, color = c.textPrimary, textAlign = TextAlign.Center)
            Text(stringResource(R.string.limit_message, limit, selected), style = AppTypography.subheadline, color = c.textSecondary, textAlign = TextAlign.Center)
            if (available == 0) Text(stringResource(R.string.limit_tomorrow), style = AppTypography.footnote, color = c.textSecondary, textAlign = TextAlign.Center)
            // Primär: alles auf einmal mit Pro; die Teilmenge bleibt als ehrliche Alternative.
            ProminentButton(stringResource(R.string.limit_all_with_pro, selected), Modifier.testTag("basket.limit.pro"), fillWidth = true, large = true, onClick = openPro)
            // Freiwillig: kurze Werbung gegen +10 nur für heute (1× täglich). Nie automatisch gestartet.
            if (bonus != null) {
                GlassButton(stringResource(R.string.limit_bonus, LimitBonus.AMOUNT), Modifier.testTag("basket.limit.bonus"), fillWidth = true, large = true, onClick = bonus)
                Text(stringResource(R.string.limit_bonus_note), style = AppTypography.caption, color = c.textSecondary, textAlign = TextAlign.Center)
            }
            if (available > 0) GlassButton(stringResource(R.string.limit_take_partial, available), Modifier.testTag("basket.limit.partial"), fillWidth = true, large = true,
                tint = c.cleanup, onClick = takePartial)
            PlainButton(stringResource(R.string.cancel), onClick = onDismiss)
        }
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

