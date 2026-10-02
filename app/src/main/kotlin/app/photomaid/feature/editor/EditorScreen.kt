package app.photomaid.feature.editor

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
    CoachHost(steps, active = ready && !settings.editorTourSeen,
        onFinish = { scope.launch { app.updateSettings { it.copy(editorTourSeen = true) } } }, modifier = Modifier.fillMaxSize()) {
        AppBackground(Modifier.testTag("editor")) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // Kopfzeile: Abbrechen · Rückgängig/Wiederholen · Speichern
                Row(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.s, vertical = AppSpacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                    PlainButton(stringResource(R.string.cancel), Modifier.padding(horizontal = AppSpacing.xs).testTag("editor.cancel"), enabled = !vm.saving) {
                        if (vm.hasChanges) confirmDiscard = true else onClose()
                    }
                    Spacer(Modifier.weight(1f))
                    if (!vm.isVideo) {
                        GlassIconButton(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.undo), Modifier.testTag("editor.undo"),
                            enabled = vm.canUndo && !vm.saving, quiet = true, onClick = vm::undo)
                        GlassIconButton(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.redo), Modifier.testTag("editor.redo"),
                            enabled = vm.canRedo && !vm.saving, quiet = true, onClick = vm::redo)
                    }
                    Spacer(Modifier.weight(1f))
                    ProminentButton(stringResource(R.string.save), Modifier.coachTarget("editor.save").testTag("editor.save"),
                        enabled = vm.hasChanges && !vm.saving) { showSave = true }
                }
                val out = vm.outputSize
                if (!vm.isVideo && out != null) Text(out.label(), Modifier.fillMaxWidth().testTag("editor.size"), style = AppTypography.caption,
                    color = c.textSecondary, textAlign = TextAlign.Center)

                val stageMod = Modifier.weight(1f).fillMaxWidth().coachTarget("editor.stage")
                if (vm.isVideo) VideoTrimStage(vm, stageMod) else EditorStage(vm, stageMod)

                Column(Modifier.fillMaxWidth().widthIn(max = 720.dp).align(Alignment.CenterHorizontally)
                    .padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.xs), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                    if (vm.needsPro && (vm.isVideo || vm.recipe.usesProTools)) ProHint(onPro = nav::openPro)
                    Box(Modifier.fillMaxWidth().testTag("editor.panel.${vm.tab.name}")) {
                        when (vm.tab) {
                            EditorTab.CROP -> CropPanel(vm)
                            EditorTab.ADJUST -> AdjustPanel(vm)
                            EditorTab.FILTER -> FilterPanel(vm)
                            EditorTab.RETOUCH -> RetouchPanel(vm)
                            EditorTab.MARKUP -> MarkupPanel(vm.markup)
                            EditorTab.FRAME -> FramePanel(vm)
                            EditorTab.RESIZE -> ResizePanel(vm)
                            EditorTab.TRIM -> TrimPanel(vm)
                        }
                    }
                    val tabs = if (vm.isVideo) listOf(EditorTab.TRIM) else listOf(EditorTab.CROP, EditorTab.ADJUST, EditorTab.FILTER, EditorTab.RETOUCH, EditorTab.MARKUP, EditorTab.FRAME, EditorTab.RESIZE)
                    if (tabs.size > 1) EditorTabs(tabs, vm.tab, locked = !app.capabilities.hasProToolsAccess, onSelect = { vm.tab = it },
                        modifier = Modifier.coachTarget("editor.tabs").testTag("editor.tabs"))
                    else Box(Modifier.coachTarget("editor.tabs").size(1.dp))
                }
            }
            SavingOverlay(vm)
        }
    }

    var pickFolder by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<MediaFolder>?>(null) }
    if (showSave && !pickFolder) SaveSheet(vm, initial = settings.editSaveMode, onDismiss = { showSave = false }, onPro = { showSave = false; nav.openPro() },
        onFolder = {
            pickFolder = true
            scope.launch { folders = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.allPhotos.folders() } }
        },
        onShare = { showSave = false; vm.share(removeLocation = true) },
        onSave = { mode, removeLocation -> showSave = false; vm.save(mode, removeLocation) })
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
                val text = if (vm.isVideo) stringResource(R.string.edit_trimming, NumberFormat.getPercentInstance().format((p ?: 0f).toDouble()))
                else stringResource(R.string.edit_saving)
                Text(text, style = AppTypography.headline, color = c.textPrimary, textAlign = TextAlign.Center)
                if (vm.isVideo) GlassButton(stringResource(R.string.cancel), Modifier.testTag("editor.saving.cancel"), onClick = vm::cancelSave)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveSheet(vm: EditorViewModel, initial: SaveMode, onDismiss: () -> Unit, onPro: () -> Unit, onFolder: () -> Unit, onShare: () -> Unit,
                      onSave: (SaveMode, Boolean) -> Unit) {
    val c = PM.colors
    var mode by rememberSaveable { mutableStateOf(initial) }
    var removeLocation by rememberSaveable { mutableStateOf(false) }
    val src = vm.source
    val a = vm.asset
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("editor.sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Text(stringResource(R.string.edit_save_title), style = AppTypography.title, color = c.textPrimary)
            SaveOption(SaveMode.COPY, mode, stringResource(R.string.edit_save_copy), stringResource(R.string.edit_save_copy_text)) { mode = it }
            SaveOption(SaveMode.REPLACE, mode, stringResource(R.string.edit_save_replace), stringResource(R.string.edit_save_replace_text)) { mode = it }
            // Ablage der Kopie: wie das Original oder direkt in einen anderen Ordner (keine Entscheidung im Feed).
            if (mode == SaveMode.COPY && !vm.isVideo) {
                val current = vm.targetFolder ?: a?.relativePath
                Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.minTouch).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onFolder)
                    .padding(horizontal = AppSpacing.xs).testTag("editor.folder"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.edit_save_folder), style = AppTypography.body, color = c.textPrimary)
                        Text(current?.let { MediaFolder(it, 0).name } ?: stringResource(R.string.edit_save_folder_default),
                            style = AppTypography.footnote, color = c.textSecondary)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.textSecondary)
                }
            }
            if (!vm.isVideo) {
                Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.minTouch).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Switch) { removeLocation = !removeLocation }.padding(horizontal = AppSpacing.xs).testTag("editor.location"),
                    verticalAlignment = Alignment.CenterVertically) {
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

            if (vm.needsPro) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.accent.copy(alpha = 0.10f)).padding(AppSpacing.m)
                    .testTag("editor.sheet.pro"), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Icon(Icons.Rounded.WorkspacePremium, null, tint = c.accent)
                        Text(stringResource(R.string.edit_pro_title), style = AppTypography.headline, color = c.textPrimary)
                    }
                    Text(stringResource(R.string.edit_pro_text), style = AppTypography.subheadline, color = c.textSecondary)
                    ProminentButton(stringResource(R.string.edit_pro_unlock), Modifier.testTag("editor.sheet.unlock"), fillWidth = true, onClick = onPro)
                    if (!vm.isVideo && vm.recipe.hasGeometry) GlassButton(stringResource(R.string.edit_save_geometry_only), Modifier.testTag("editor.sheet.geometry"),
                        fillWidth = true) { vm.dropProTools(); onSave(mode, removeLocation) }
                }
            } else {
                ProminentButton(stringResource(if (mode == SaveMode.COPY) R.string.edit_save_copy_action else R.string.edit_save_replace_action),
                    Modifier.testTag("editor.sheet.save"), fillWidth = true, large = true) { onSave(mode, removeLocation) }
                // Nur teilen: Export in den Cache, keine Galeriekopie (Standort standardmäßig entfernt).
                if (!vm.isVideo) GlassButton(stringResource(R.string.edit_share_only), Modifier.testTag("editor.sheet.share"), fillWidth = true) { onShare() }
                if (!vm.isVideo) Text(stringResource(R.string.edit_share_only_text), style = AppTypography.footnote, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun SaveOption(value: SaveMode, selected: SaveMode, title: String, text: String, onSelect: (SaveMode) -> Unit) {
    val c = PM.colors
    val sel = value == selected
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(if (sel) c.accent.copy(alpha = 0.10f) else c.backgroundTint)
        .border(if (sel) 2.dp else 0.dp, if (sel) c.accent else Color.Transparent, shape)
        .clickable(role = Role.RadioButton) { onSelect(value) }.semantics(mergeDescendants = true) { this.selected = sel }
        .padding(AppSpacing.m).testTag("editor.mode.${value.name}"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Icon(if (sel) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (sel) c.accent else c.textSecondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AppTypography.headline, color = c.textPrimary)
            Text(text, style = AppTypography.subheadline, color = c.textSecondary)
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
