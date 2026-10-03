package app.photomaid.feature.editor

import app.photomaid.design.LocalEditorPalette
import app.photomaid.design.EditorWideButton
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.photomaid.R
import app.photomaid.core.edit.EditFormat
import app.photomaid.core.edit.SaveMode
import app.photomaid.design.CancelButton
import app.photomaid.design.DockItem
import app.photomaid.design.EditorActionButton
import app.photomaid.design.EditorDock
import app.photomaid.design.EditorSubTabs
import app.photomaid.design.EditorHoldButton
import app.photomaid.design.EditorLayoutPolicy
import app.photomaid.design.EditorPrimaryButton
import app.photomaid.design.EditorScaffold
import app.photomaid.design.EditorStatus
import app.photomaid.design.EditorTheme
import app.photomaid.design.EditorTopBar
import app.photomaid.design.EditorExportPage
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.layout.imePadding
import app.photomaid.design.AppBackground
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.CoachHost
import app.photomaid.design.CoachStep
import app.photomaid.design.ErrorState
import app.photomaid.design.GlassButton
import app.photomaid.design.GlassIconButton
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.ProminentButton
import app.photomaid.design.coachTarget
import app.photomaid.design.glassRect
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import kotlinx.coroutines.launch
import java.text.NumberFormat
import app.photomaid.core.model.MediaKind
import app.photomaid.core.organize.MediaFolder
import app.photomaid.feature.review.FolderSheet
import app.photomaid.feature.review.rememberRecentFolder
import app.photomaid.feature.review.togglePinnedFolder
import app.photomaid.system.MediaIntents

/**
 * Editor (ein Bildschirm): Fotos mit Zuschneiden · Anpassen · Filter · Größe, Videos mit Kürzen. Bearbeitet wird ein
 * Rezept; erst „Speichern“ schreibt – als Kopie oder als Ersatz des Originals (Original in den Android-Papierkorb).
 * Nie eine Entscheidung im Feed; die gespeicherte Fassung gilt als „Behalten“.
 */
@Composable
fun EditorScreen(assetId: String, nav: Nav, onClose: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val vm: EditorViewModel = viewModel(key = "editor:$assetId", factory = viewModelFactory { initializer { EditorViewModel(app, assetId) } })
    val settings by app.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmDiscard by remember { mutableStateOf(false) }
    var showSave by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm.result) { if (vm.result != null) onClose() }
    LaunchedEffect(vm.continuedInBackground) { if (vm.continuedInBackground) onClose() }
    BackHandler {
        when {
            vm.saving -> Unit
            vm.hasChanges -> confirmDiscard = true
            else -> onClose()
        }
    }

    if (vm.loadFailed) {
        AppBackground {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.padding(AppSpacing.xs)) { PlainButton(stringResource(R.string.close), Modifier.padding(horizontal = AppSpacing.xs), onClick = onClose) }
                ErrorState(stringResource(R.string.edit_err_load), Modifier.fillMaxSize())
            }
        }
        return
    }

    val steps = listOf(
        CoachStep("editor.tabs", stringResource(R.string.tour_editor_tools_title), stringResource(if (vm.isVideo) R.string.tour_editor_trim_text else R.string.tour_editor_tools_text)),
        CoachStep("editor.stage", stringResource(R.string.tour_editor_compare_title), stringResource(R.string.tour_editor_compare_text)),
        CoachStep("editor.save", stringResource(R.string.tour_editor_save_title), stringResource(R.string.tour_editor_save_text)),
    ).let { s -> if (vm.isVideo) s.filter { it.key != "editor.stage" } else s }
    val ready = vm.asset != null && (vm.isVideo || vm.source != null)
    // Navigation: vier Hauptwerkzeuge + „Mehr“ – passt bei normaler Schrift vollständig, ohne kleinere Beschriftungen.
    val mainTabs = if (vm.isVideo) listOf(EditorTab.TRIM, EditorTab.ADJUST, EditorTab.FILTER, EditorTab.MARKUP)
        else listOf(EditorTab.CROP, EditorTab.ADJUST, EditorTab.FILTER, EditorTab.MARKUP)
    val moreTabs = if (vm.isVideo) listOf(EditorTab.SPEED, EditorTab.AUDIO, EditorTab.FORMAT) else listOf(EditorTab.RETOUCH, EditorTab.FRAME, EditorTab.RESIZE)
    var lastMore by rememberSaveable(vm.isVideo) { mutableStateOf(moreTabs.first()) }
    LaunchedEffect(vm.tab) { if (vm.tab in moreTabs) lastMore = vm.tab }
    val locked = !app.capabilities.hasProToolsAccess
    val dockItems = mainTabs.map { t -> DockItem(t.name, t.icon, stringResource(if (t == EditorTab.CROP) R.string.dock_crop else t.label), "tab.${t.name}",
        badge = if (locked && t.isPro) Icons.Rounded.WorkspacePremium else null) } +
        DockItem(MORE, Icons.Rounded.MoreHoriz, stringResource(R.string.dock_more), "tab.$MORE")
    EditorTheme {
    CoachHost(steps, active = ready && !settings.editorTourSeen,
        onFinish = { scope.launch { app.updateSettings { it.copy(editorTourSeen = true) } } }, modifier = Modifier.fillMaxSize()) {
        // Ein Player für den ganzen Editor: bleibt über Werkzeugwechsel (auch Markieren) erhalten.
        val preview = if (vm.isVideo) rememberVideoPreview(vm) else null
        Box(Modifier.fillMaxSize().testTag("editor")) {
            EditorScaffold(
                header = {
                    EditorTopBar(leading = {
                        CancelButton(stringResource(R.string.cancel), Modifier.testTag("editor.cancel"), enabled = !vm.saving) {
                            if (vm.hasChanges) confirmDiscard = true else onClose()
                        }
                    }, trailing = {
                        EditorPrimaryButton(stringResource(R.string.save), Modifier.coachTarget("editor.save").testTag("editor.save"),
                            enabled = vm.hasChanges && !vm.saving) { showSave = true }
                    })
                },
                stage = {
                    Box(Modifier.fillMaxSize().coachTarget("editor.stage")) {
                        when {
                            vm.isVideo && vm.tab == EditorTab.MARKUP -> VideoPosterStage(vm, Modifier.fillMaxSize())
                            vm.isVideo -> preview?.let { VideoEditStage(vm, it, Modifier.fillMaxSize()) }
                            else -> EditorStage(vm, Modifier.fillMaxSize())
                        }
                    }
                },
                actions = {
                    EditorActionButton(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.undo), Modifier.testTag("editor.undo"),
                        enabled = vm.canUndo && !vm.saving, onClick = vm::undo)
                    EditorActionButton(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.redo), Modifier.testTag("editor.redo"),
                        enabled = vm.canRedo && !vm.saving, onClick = vm::redo)
                    if (vm.isVideo) {
                        if (preview != null) VideoTransport(vm, preview)
                    } else {
                        val out = vm.outputSize
                        val status = when {
                            vm.comparing -> stringResource(R.string.edit_original)
                            vm.zoomPercent != null -> "\u2066${vm.zoomPercent} %\u2069"
                            out != null -> "\u2066" + out.label() + "\u2069"
                            else -> ""
                        }
                        EditorStatus(status, Modifier.testTag(when { vm.comparing -> "editor.status"; vm.zoomPercent != null -> "editor.zoom"; out != null -> "editor.size"; else -> "editor.status" }), emphasized = vm.comparing)
                        val geometryTab = vm.tab == EditorTab.CROP || vm.tab == EditorTab.MARKUP || vm.tab == EditorTab.RETOUCH
                        EditorHoldButton(Icons.Rounded.Compare, stringResource(R.string.edit_compare_hint), vm.comparing, { vm.comparing = it },
                            Modifier.testTag("editor.compare"), enabled = vm.hasChanges && !geometryTab)
                        EditorActionButton(Icons.Rounded.BarChart, stringResource(R.string.edit_histogram), Modifier.testTag("editor.histogram.toggle"),
                            active = vm.showHistogram) { vm.showHistogram = !vm.showHistogram }
                        EditorActionButton(Icons.Rounded.WarningAmber, stringResource(R.string.edit_clipping), Modifier.testTag("editor.clipping.toggle"),
                            active = vm.showClipping) { vm.showClipping = !vm.showClipping }
                    }
                },
                tool = {
                    if (vm.tab in moreTabs) EditorSubTabs(moreTabs.map { Triple(it.name, stringResource(it.label), false) }, vm.tab.name,
                        { vm.tab = EditorTab.valueOf(it) }, Modifier.testTag("editor.more"), tagPrefix = "tab.")
                    if (vm.needsPro && (vm.isVideo || vm.recipe.usesProTools)) ProHint(onPro = nav::openPro)
                    if (!vm.isVideo && vm.showHistogram) vm.histogram?.let { HistogramCard(it, Modifier.fillMaxWidth()) }
                    Box(Modifier.fillMaxWidth().testTag("editor.panel.${vm.tab.name}")) {
                        when (vm.tab) {
                            EditorTab.CROP -> CropPanel(vm)
                            EditorTab.ADJUST -> AdjustPanel(vm)
                            EditorTab.FILTER -> FilterPanel(vm)
                            EditorTab.RETOUCH -> RetouchPanel(vm)
                            EditorTab.MARKUP -> if (vm.isVideo) Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) { LayerTimeRow(vm); MarkupPanel(vm.markup) } else MarkupPanel(vm.markup)
                            EditorTab.FRAME -> FramePanel(vm)
                            EditorTab.RESIZE -> ResizePanel(vm)
                            EditorTab.TRIM -> TrimPanel(vm)
                            EditorTab.SPEED -> SpeedPanel(vm)
                            EditorTab.AUDIO -> AudioPanel(vm)
                            EditorTab.FORMAT -> VideoFormatPanel(vm)
                        }
                    }
                },
                dock = {
                    EditorDock(dockItems, if (vm.tab in moreTabs) MORE else vm.tab.name, { k -> vm.tab = if (k == MORE) lastMore else EditorTab.valueOf(k) }, Modifier.coachTarget("editor.tabs").testTag("editor.tabs"))
                },
            )
            SavingOverlay(vm)
        }
    }
    }

    var pickFolder by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<MediaFolder>?>(null) }
    if (showSave) EditorTheme { SaveSheet(vm, initial = SaveMode.COPY, onDismiss = { showSave = false }, onPro = { showSave = false; nav.openPro() },
        onFolder = {
            pickFolder = true
            scope.launch { folders = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.allPhotos.folders() } }
        },
        onShare = { showSave = false; vm.share(removeLocation = true) },
        onSave = { mode, removeLocation -> showSave = false; vm.save(mode, removeLocation) }) }
    if (pickFolder) vm.asset?.let { a ->
        FolderSheet(a.kind, vm.targetFolder ?: a.relativePath, folders, settings.pinnedFolders, settings.recentFolders,
            onTogglePin = { p -> scope.launch { togglePinnedFolder(app, p) } },
            onPick = { p -> vm.targetFolder = p; pickFolder = false; scope.launch { rememberRecentFolder(app, p) } },
            onDismiss = { pickFolder = false }, title = stringResource(R.string.edit_save_folder))
    }
    // Teilen ohne Galeriekopie: System-Dialog öffnen, danach im Editor bleiben.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shareTitle = stringResource(R.string.share)
    val shareFailed = stringResource(R.string.edit_err_save)
    LaunchedEffect(vm.shareUri) {
        val u = vm.shareUri ?: return@LaunchedEffect
        vm.shareUri = null
        runCatching { ctx.startActivity(MediaIntents.share(u, MediaKind.PHOTO, ctx.contentResolver.getType(u), shareTitle)) }
            .onFailure { vm.error = shareFailed }
    }

    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, modifier = Modifier.testTag("editor.discard"),
        title = { Text(stringResource(R.string.edit_discard_title)) }, text = { Text(stringResource(R.string.edit_discard_text)) },
        confirmButton = { TextButton({ confirmDiscard = false; onClose() }, Modifier.testTag("editor.discard.confirm")) { Text(stringResource(R.string.edit_discard)) } },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text(stringResource(R.string.edit_keep_editing)) } })

    vm.notice?.let { msg ->
        AlertDialog(onDismissRequest = { vm.notice = null }, modifier = Modifier.testTag("editor.notice"),
            title = { Text(stringResource(R.string.notice)) }, text = { Text(msg) },
            confirmButton = { TextButton({ vm.notice = null }, Modifier.testTag("editor.notice.ok")) { Text(stringResource(R.string.ok)) } })
    }
    vm.error?.let { msg ->
        AlertDialog(onDismissRequest = { vm.error = null }, modifier = Modifier.testTag("editor.error"),
            title = { Text(stringResource(R.string.notice)) }, text = { Text(msg) },
            confirmButton = { TextButton({ vm.error = null }) { Text(stringResource(R.string.ok)) } })
    }
}

@Composable
private fun SavingOverlay(vm: EditorViewModel) {
    val c = PM.colors
    AnimatedVisibility(vm.saving, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}.testTag("editor.saving"),
            contentAlignment = Alignment.Center) {
            Column(Modifier.glassRect().padding(AppSpacing.xl).widthIn(max = 320.dp).semantics { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                val p = vm.progress
                if (p != null) CircularProgressIndicator(progress = { p }, Modifier.size(48.dp)) else CircularProgressIndicator(Modifier.size(48.dp))
                val text = if (vm.isVideo && p != null) stringResource(R.string.vid_exporting, NumberFormat.getPercentInstance().format(p.toDouble()))
                else stringResource(R.string.edit_saving)
                Text(text, style = AppTypography.headline, color = c.textPrimary, textAlign = TextAlign.Center)
                if (vm.isVideo) {
                    GlassButton(stringResource(R.string.cancel), Modifier.testTag("editor.saving.cancel"), onClick = vm::cancelSave)
                    // Langer Export: Editor schließen, Export läuft mit Benachrichtigung weiter.
                    if (vm.progress != null && !vm.isFrameSave) PlainButton(stringResource(R.string.vid_continue_background), Modifier.testTag("editor.saving.background"), onClick = vm::continueInBackground)
                }
            }
        }
    }
}

/**
 * Speichern als eigener Schritt (ganze Ansicht statt Blatt über dem Bild): klar, dass eine neue Datei entsteht und das
 * Original bleibt; vorhandene Optionen (Kopie/Ersetzen, Ordner, Standort, Videoprofil) unverändert.
 */
private const val MORE = "MORE"

@Composable
private fun SaveSheet(vm: EditorViewModel, initial: SaveMode, onDismiss: () -> Unit, onPro: () -> Unit, onFolder: () -> Unit, onShare: () -> Unit,
                      onSave: (SaveMode, Boolean) -> Unit) {
    val c = PM.colors
    var mode by rememberSaveable { mutableStateOf(initial) }
    var removeLocation by rememberSaveable { mutableStateOf(false) }
    val src = vm.source
    val a = vm.asset
    EditorExportPage(stringResource(R.string.edit_save_title), onDismiss, Modifier.testTag("editor.sheet")) {
        // 1. Wie speichern: Kopie ist immer vorausgewählt; „Original in den Papierkorb“ nur auf ausdrückliche Wahl.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.backgroundTint).selectableGroup()) {
            SaveOption(SaveMode.COPY, mode, stringResource(R.string.edit_save_copy), stringResource(R.string.edit_save_copy_text)) { mode = it }
            Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(1.dp).background(LocalEditorPalette.current.stroke))
            SaveOption(SaveMode.REPLACE, mode, stringResource(R.string.edit_save_replace), stringResource(R.string.edit_save_replace_text)) { mode = it }
        }
        // 2. Ablage und Standort.
        if (!vm.isVideo) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.backgroundTint)) {
            val current = vm.targetFolder ?: a?.relativePath
            // Ablage der Kopie: wie das Original oder direkt in einen anderen Ordner (keine Entscheidung im Feed).
            if (mode == SaveMode.COPY) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onFolder)
                    .padding(horizontal = AppSpacing.s).testTag("editor.folder"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.edit_save_folder), style = AppTypography.body, color = c.textPrimary)
                        Text(current?.let { MediaFolder(it, 0).name } ?: stringResource(R.string.edit_save_folder_default),
                            style = AppTypography.footnote, color = c.textSecondary)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.textSecondary)
                }
                Box(Modifier.fillMaxWidth().padding(start = AppSpacing.s).height(1.dp).background(LocalEditorPalette.current.stroke))
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(removeLocation, role = Role.Switch) { removeLocation = it }
                .padding(horizontal = AppSpacing.s).testTag("editor.location"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.edit_remove_location), style = AppTypography.body, color = c.textPrimary)
                    Text(stringResource(R.string.edit_remove_location_text), style = AppTypography.footnote, color = c.textSecondary)
                }
                Switch(removeLocation, null)
            }
        }
        // Ehrliche Hinweise (nur wenn zutreffend).
        val mime = src?.mime ?: a?.mimeType
        if (!vm.isVideo && (mime?.contains("heic", true) == true || mime?.contains("heif", true) == true) && EditFormat.forSource(mime) == EditFormat.JPEG)
            Hint(Icons.Rounded.Info, stringResource(R.string.edit_hint_heic))
        if (!vm.isVideo && src?.hasGainmap == true && (vm.recipe.hasPixelOps || vm.recipe.hasSharpen))
            Hint(Icons.Rounded.Info, stringResource(R.string.edit_hint_hdr))
        if (!vm.isVideo && vm.recipe.isOrientationOnly && EditFormat.forSource(mime) == EditFormat.JPEG)
            Hint(Icons.Rounded.CheckCircle, stringResource(R.string.edit_hint_lossless))
        if (mode == SaveMode.REPLACE) Hint(Icons.Rounded.Info, stringResource(R.string.edit_hint_replace_confirm))
        if (vm.isVideo) VideoProfileRow(vm)

        // 3. Aktion passend zur Wahl; „Nur teilen“ untergeordnet.
        if (vm.needsPro) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.backgroundTint).padding(AppSpacing.m)
                .testTag("editor.sheet.pro"), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Icon(Icons.Rounded.WorkspacePremium, null, tint = c.accent)
                    Text(stringResource(R.string.edit_pro_title), style = AppTypography.headline, color = c.textPrimary)
                }
                Text(stringResource(R.string.edit_pro_text), style = AppTypography.subheadline, color = c.textSecondary)
            }
            EditorWideButton(stringResource(R.string.edit_pro_unlock), Modifier.testTag("editor.sheet.unlock"), onClick = onPro)
            if (!vm.isVideo && vm.recipe.hasGeometry) EditorWideButton(stringResource(R.string.edit_save_geometry_only), Modifier.testTag("editor.sheet.geometry"),
                primary = false) { vm.dropProTools(); onSave(mode, removeLocation) }
        } else {
            EditorWideButton(stringResource(if (mode == SaveMode.COPY) R.string.edit_save_copy_action else R.string.edit_save_replace_action),
                Modifier.testTag("editor.sheet.save")) { onSave(mode, removeLocation) }
            // Nur teilen: Export in den Cache, keine Galeriekopie (Standort standardmäßig entfernt).
            if (!vm.isVideo) {
                EditorWideButton(stringResource(R.string.edit_share_only), Modifier.testTag("editor.sheet.share"), primary = false) { onShare() }
                Text(stringResource(R.string.edit_share_only_text), style = AppTypography.footnote, color = c.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Kompakte Auswahlzeile (Radio): Titel + eine Zeile Erklärung; Auswahl über Symbol, Schrift und Semantik. */
@Composable
private fun SaveOption(value: SaveMode, selected: SaveMode, title: String, text: String, onSelect: (SaveMode) -> Unit) {
    val c = PM.colors
    val sel = value == selected
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(sel, role = Role.RadioButton) { onSelect(value) }
        .padding(horizontal = AppSpacing.s, vertical = AppSpacing.xs).testTag("editor.mode.${value.name}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Icon(if (sel) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (sel) c.accent else c.textSecondary,
            modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AppTypography.body.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal), color = c.textPrimary)
            Text(text, style = AppTypography.footnote, color = c.textSecondary)
        }
    }
}

@Composable
private fun Hint(icon: ImageVector, text: String) {
    val c = PM.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.xs), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
        Text(text, style = AppTypography.footnote.copy(fontWeight = FontWeight.Normal), color = c.textSecondary)
    }
}
