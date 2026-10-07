package app.photomaid.feature.studio

import app.photomaid.design.EditorWideButton
import app.photomaid.design.EditorSubTabs
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.FormatPaint
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoSizeSelectActual
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.photomaid.R
import app.photomaid.core.edit.FilterId
import app.photomaid.core.edit.NormPoint
import app.photomaid.core.edit.NormRect
import app.photomaid.core.studio.BackgroundKind
import app.photomaid.core.studio.CollageKind
import app.photomaid.core.studio.CollageLayout
import app.photomaid.core.studio.CollageProject
import app.photomaid.design.DockItem
import app.photomaid.design.EditorActionButton
import app.photomaid.design.EditorDock
import app.photomaid.design.EditorExportPage
import app.photomaid.design.EditorPrimaryButton
import app.photomaid.design.EditorScaffold
import app.photomaid.design.EditorStatus
import app.photomaid.design.EditorTheme
import app.photomaid.design.EditorTopBar
import app.photomaid.design.CancelButton
import app.photomaid.design.AppBackground
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassButton
import app.photomaid.design.GlassIconButton
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.ProminentButton
import app.photomaid.design.glassCapsule
import app.photomaid.design.glassRect
import app.photomaid.feature.editor.Chip
import app.photomaid.feature.editor.ColorSwatches
import app.photomaid.feature.editor.EditSlider
import app.photomaid.feature.editor.MarkupPanel
import app.photomaid.feature.editor.MarkupStage
import app.photomaid.feature.editor.PALETTE
import app.photomaid.feature.editor.fitRect
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.system.MediaIntents
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import app.photomaid.core.model.MediaKind
import app.photomaid.core.studio.CollageItem
import app.photomaid.core.studio.Divider
import app.photomaid.core.studio.StripLayout
import app.photomaid.feature.studio.CollageRendererBox
import app.photomaid.media.LayerBox

/** Öffnen einer Collage: neues Projekt aus Medien-IDs oder ein Entwurf. */
data class CollageRequest(val id: String, val project: CollageProject, val fromDraft: Boolean)

object CollageRequests { val map = androidx.compose.runtime.mutableStateMapOf<String, CollageRequest>() }

private val CollageTab.icon: ImageVector get() = when (this) {
    CollageTab.LAYOUT -> Icons.Rounded.Dashboard; CollageTab.FORMAT -> Icons.Rounded.AspectRatio; CollageTab.SPACING -> Icons.Rounded.SpaceBar
    CollageTab.BACKGROUND -> Icons.Rounded.FormatPaint; CollageTab.PHOTO -> Icons.Rounded.Image; CollageTab.MARKUP -> Icons.Rounded.Draw
}
private val CollageTab.label: Int get() = when (this) {
    CollageTab.LAYOUT -> R.string.col_tab_layout; CollageTab.FORMAT -> R.string.col_tab_format; CollageTab.SPACING -> R.string.col_tab_spacing
    CollageTab.BACKGROUND -> R.string.col_tab_background; CollageTab.PHOTO -> R.string.col_tab_photo; CollageTab.MARKUP -> R.string.edit_tab_markup
}

@Composable
fun CollageScreen(req: CollageRequest, nav: Nav, pick: (PickRequest) -> Unit, onClose: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val vm: CollageViewModel = viewModel(key = "collage:${req.id}", factory = viewModelFactory { initializer { CollageViewModel(app, req.project, req.fromDraft) } })
    var askDraft by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    LaunchedEffect(vm.savedId) { if (vm.savedId != null) onClose() }
    BackHandler { if (!vm.saving) { if (vm.dirty) askDraft = true else onClose() } }

    // Höchstens fünf Einträge in der Navigation: beim Raster wandern Abstand und Markieren unter „Mehr“.
    val moreTabs = if (vm.project.kind == CollageKind.GRID) listOf(CollageTab.SPACING, CollageTab.MARKUP) else emptyList()
    var lastMore by rememberSaveable { mutableStateOf(CollageTab.SPACING) }
    LaunchedEffect(vm.tab) { if (vm.tab in moreTabs) lastMore = vm.tab }
    val tabs = when (vm.project.kind) {
        CollageKind.GRID -> listOf(CollageTab.LAYOUT, CollageTab.FORMAT, CollageTab.BACKGROUND, CollageTab.PHOTO)
        CollageKind.FREE -> listOf(CollageTab.LAYOUT, CollageTab.FORMAT, CollageTab.BACKGROUND, CollageTab.PHOTO, CollageTab.MARKUP)
        CollageKind.STRIP -> listOf(CollageTab.LAYOUT, CollageTab.SPACING, CollageTab.BACKGROUND, CollageTab.PHOTO, CollageTab.MARKUP)
    }
    val dockItems = tabs.map { t -> DockItem(t.name, t.icon, stringResource(t.label), "ctab.${t.name}") } +
        (if (moreTabs.isEmpty()) emptyList() else listOf(DockItem("MORE", Icons.Rounded.MoreHoriz, stringResource(R.string.dock_more), "ctab.MORE")))
    EditorTheme {
    Box(Modifier.fillMaxSize().testTag("collage")) {
        // Gleiches Gerüst wie Foto-/Video-Editor: Leinwand = feste Bühne, Bedienung darunter (scrollt in der Werkzeugzone).
        EditorScaffold(
            header = {
                EditorTopBar(leading = {
                    CancelButton(stringResource(R.string.cancel), Modifier.testTag("collage.cancel"), enabled = !vm.saving) {
                        if (vm.dirty) askDraft = true else onClose()
                    }
                }, trailing = {
                    EditorPrimaryButton(stringResource(R.string.save), Modifier.testTag("collage.save"), enabled = !vm.saving && vm.preview != null) { showSave = true }
                })
            },
            stage = {
                if (vm.tab == CollageTab.MARKUP) MarkupStage(vm.markup, vm.preview, vm.preview, null, Modifier.fillMaxSize())
                else CollageStage(vm, Modifier.fillMaxSize())
            },
            actions = {
                EditorActionButton(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.undo), Modifier.testTag("collage.undo"), enabled = vm.canUndo, onClick = vm::undo)
                EditorActionButton(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.redo), Modifier.testTag("collage.redo"), enabled = vm.canRedo, onClick = vm::redo)
                EditorStatus(vm.preview?.let { "\u2066${it.width} × ${it.height}\u2069" } ?: "")
            },
            tool = {
                Column(Modifier.fillMaxWidth().testTag("collage.panel.${vm.tab.name}"), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                    if (vm.tab in moreTabs) EditorSubTabs(moreTabs.map { Triple(it.name, stringResource(it.label), false) }, vm.tab.name,
                        { vm.tab = CollageTab.valueOf(it) }, tagPrefix = "ctab.")
                    if (vm.missing.isNotEmpty()) Text(stringResource(R.string.col_missing), Modifier.fillMaxWidth().testTag("collage.missing"),
                        style = AppTypography.footnote, color = c.warning, textAlign = TextAlign.Center)
                    if (vm.needsPro) ProHintRow(nav::openPro)
                    when (vm.tab) {
                        CollageTab.LAYOUT -> LayoutPanel(vm)
                        CollageTab.FORMAT -> FormatPanel(vm)
                        CollageTab.SPACING -> SpacingPanel(vm)
                        CollageTab.BACKGROUND -> BackgroundPanel(vm)
                        CollageTab.PHOTO -> PhotoPanel(vm, pick)
                        CollageTab.MARKUP -> MarkupPanel(vm.markup)
                    }
                }
            },
            dock = { EditorDock(dockItems, if (vm.tab in moreTabs) "MORE" else vm.tab.name, { k -> vm.tab = if (k == "MORE") lastMore else CollageTab.valueOf(k) }) },
        )
        AnimatedVisibility(vm.saving, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}.testTag("collage.saving"), contentAlignment = Alignment.Center) {
                Column(Modifier.glassRect().padding(AppSpacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                    CircularProgressIndicator(Modifier.size(48.dp))
                    Text(stringResource(R.string.edit_saving), style = AppTypography.headline, color = c.textPrimary)
                }
            }
        }
    }

    }
    if (showSave) EditorTheme { CollageSaveSheet(vm, onDismiss = { showSave = false }, onPro = { showSave = false; nav.openPro() }) }
    if (askDraft) AlertDialog(onDismissRequest = { askDraft = false }, modifier = Modifier.testTag("collage.draft"),
        title = { Text(stringResource(R.string.col_draft_title)) }, text = { Text(stringResource(R.string.col_draft_text)) },
        confirmButton = { TextButton({ askDraft = false; vm.saveDraft(onClose) }, Modifier.testTag("collage.draft.keep")) { Text(stringResource(R.string.col_draft_keep)) } },
        dismissButton = { TextButton({ askDraft = false; vm.discardDraft(onClose) }, Modifier.testTag("collage.draft.discard")) { Text(stringResource(R.string.edit_discard)) } })
    vm.error?.let { msg ->
        AlertDialog(onDismissRequest = { vm.error = null }, title = { Text(stringResource(R.string.notice)) }, text = { Text(msg) },
            confirmButton = { TextButton({ vm.error = null }) { Text(stringResource(R.string.ok)) } })
    }
    val ctx = LocalContext.current
    val shareTitle = stringResource(R.string.share)
    val shareFailed = stringResource(R.string.edit_err_save)
    LaunchedEffect(vm.shareUri) {
        val u = vm.shareUri ?: return@LaunchedEffect
        vm.shareUri = null
        runCatching { ctx.startActivity(MediaIntents.share(u, MediaKind.PHOTO, if (vm.png) "image/png" else "image/jpeg", shareTitle)) }
            .onFailure { vm.error = shareFailed }
    }
}

@Composable
private fun ProHintRow(onPro: () -> Unit) {
    val c = PM.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = 0.10f)).clickable(role = Role.Button, onClick = onPro)
        .padding(horizontal = AppSpacing.s, vertical = AppSpacing.xs).testTag("collage.prohint"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Icon(Icons.Rounded.WorkspacePremium, null, tint = c.accent, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.col_pro_hint), style = AppTypography.footnote, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.edit_pro_more), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Bühne

/**
 * Raster: Feld antippen = auswählen, ziehen = Bildausschnitt verschieben, zwei Finger = zoomen, lange drücken und auf ein
 * anderes Feld ziehen = tauschen, Griffe auf den Trennlinien = Felder größer/kleiner. Frei: verschieben, zwei Finger
 * skalieren/drehen. Alles nur Anordnung – nie eine Entscheidung.
 */
@Composable
private fun CollageStage(vm: CollageViewModel, modifier: Modifier) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val prev = vm.preview
    val vmNow by rememberUpdatedState(vm)
    var swapTarget by remember { mutableStateOf<Int?>(null) }
    var swapping by remember { mutableStateOf(false) }
    val label = stringResource(R.string.col_stage)
    val swapNext = stringResource(R.string.col_swap_next)
    val selectNext = stringResource(R.string.col_select_next)
    val photoLabel = stringResource(R.string.photo)
    val accent = PM.colors.accent
    BoxWithConstraints(modifier.padding(AppSpacing.m).testTag("collage.stage"), contentAlignment = Alignment.Center) {
        if (prev == null) { CircularProgressIndicator(); return@BoxWithConstraints }
        val box = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val disp = fitRect(box, prev.width.toFloat() / prev.height)
        val img = remember(prev) { prev.asImageBitmap() }
        val p = vm.project
        val cells = remember(p) { if (p.kind == CollageKind.GRID) p.cellRects() else emptyList() }
        fun cellAt(o: Offset): Int? {
            val x = (o.x - disp.left) / disp.width; val y = (o.y - disp.top) / disp.height
            return cells.indexOfFirst { x in it.left..it.right && y in it.top..it.bottom }.takeIf { it >= 0 }
        }
        fun freeAt(o: Offset): Int? {
            val x = (o.x - disp.left) / disp.width * prev.width; val y = (o.y - disp.top) / disp.height * prev.height
            return p.items.indices.reversed().firstOrNull { i ->
                val a = vm.aspectOf(i) ?: return@firstOrNull false
                CollageRendererBox.freeBox(p.items[i], a, prev.width, prev.height, p.polaroid).contains(x, y, 8f)
            }
        }
        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(Modifier.fillMaxSize().testTag("collage.canvas").semantics {
            contentDescription = label
            val sel = vmNow.selected
            val n = vmNow.project.count
            // Bedienungshilfen: Auswahl ohne Ziehen (Raster zusätzlich über die Feld-Knoten unten).
            customActions = listOfNotNull(
                CustomAccessibilityAction(selectNext) { vmNow.selected = ((sel ?: -1) + 1) % n; true },
                if (sel != null && n > 1) CustomAccessibilityAction(swapNext) { vmNow.swap(sel, (sel + 1) % n); true } else null)
        }.pointerInput(p.kind, disp, cells) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val v = vmNow
                if (v.project.kind == CollageKind.FREE) {
                    val hit = freeAt(down.position); v.selected = hit
                    val i = hit ?: return@awaitEachGesture
                    var moved = false
                    val start = v.project.items[i]
                    var scale = 1f; var rot = start.rotation
                    while (true) {
                        val ev = awaitPointerEvent(); if (ev.changes.all { !it.pressed }) break
                        if (ev.changes.size >= 2) { scale *= ev.calculateZoom(); rot += ev.calculateRotation()
                            v.updateItem(i, false) { it.copy(width = (start.width * scale).coerceIn(0.08f, 1.2f), rotation = rot) }
                        } else { val d = ev.changes.first().positionChange()
                            v.updateItem(i, false) { it.copy(center = NormPoint((it.center.x + d.x / disp.width).coerceIn(0f, 1f), (it.center.y + d.y / disp.height).coerceIn(0f, 1f))) } }
                        moved = true; ev.changes.forEach { it.consume() }
                    }
                    if (moved) v.commit()
                    return@awaitEachGesture
                }
                if (v.project.kind != CollageKind.GRID) { stripSelect(v, down.position, disp); return@awaitEachGesture }
                val i = cellAt(down.position) ?: return@awaitEachGesture
                v.selected = i
                val cell = cells[i]
                // Langes Drücken (ohne Bewegung) = Tauschen beginnen.
                val longPress = withTimeoutOrNull(400) {
                    while (true) { val ev = awaitPointerEvent(); if (ev.changes.all { !it.pressed }) return@withTimeoutOrNull false
                        if (ev.changes.size > 1 || ev.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }) return@withTimeoutOrNull true }
                    @Suppress("UNREACHABLE_CODE") false
                }
                if (longPress == null) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress); swapping = true
                    while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.first(); if (!ch.pressed) break
                        swapTarget = cellAt(ch.position)?.takeIf { it != i }; ch.consume() }
                    swapTarget?.let { t -> v.swap(i, t) }
                    swapTarget = null; swapping = false
                    return@awaitEachGesture
                }
                if (longPress == false) return@awaitEachGesture // nur getippt
                // Verschieben / zoomen im Feld
                val start = v.project.items[i]
                var zoom = start.zoom
                while (true) {
                    val ev = awaitPointerEvent(); if (ev.changes.all { !it.pressed }) break
                    if (ev.changes.size >= 2) { zoom = (zoom * ev.calculateZoom()).coerceIn(1f, 5f); v.updateItem(i, false) { it.copy(zoom = zoom) } }
                    else { val d = ev.changes.first().positionChange()
                        val cw = cell.width * disp.width; val ch = cell.height * disp.height
                        v.updateItem(i, false) { it.copy(panX = (it.panX - d.x / max(1f, cw) * 2f / max(0.2f, zoom - 1f + 0.6f)).coerceIn(-1f, 1f),
                            panY = (it.panY - d.y / max(1f, ch) * 2f / max(0.2f, zoom - 1f + 0.6f)).coerceIn(-1f, 1f)) } }
                    ev.changes.forEach { it.consume() }
                }
                v.commit()
            }
        }) {
            val sel = vm.selected
            if (p.kind == CollageKind.GRID) {
                // Auswahl: Akzent mit hellem Innenrand – sichtbar auf hellen wie dunklen Hintergründen.
                sel?.let { s -> cells.getOrNull(s)?.let { r -> val o = Offset(disp.left + r.left * disp.width, disp.top + r.top * disp.height)
                    val sz = Size(r.width * disp.width, r.height * disp.height); val w = 3.dp.toPx()
                    drawRect(accent, o, sz, style = Stroke(w))
                    drawRect(Color.White.copy(alpha = 0.9f), Offset(o.x + w, o.y + w), Size(sz.width - 2 * w, sz.height - 2 * w), style = Stroke(1.dp.toPx())) } }
                swapTarget?.let { s -> cells.getOrNull(s)?.let { r -> drawRect(Color(0xFFFFCC00), Offset(disp.left + r.left * disp.width, disp.top + r.top * disp.height),
                    Size(r.width * disp.width, r.height * disp.height), style = Stroke(4.dp.toPx())) } }
            } else if (p.kind == CollageKind.FREE && sel != null) {
                val a = vm.aspectOf(sel)
                if (a != null) { val b = CollageRendererBox.freeBox(p.items[sel], a, prev.width, prev.height, p.polaroid)
                    val k = disp.width / prev.width
                    val cx = disp.left + b.cx * k; val cy = disp.top + b.cy * k
                    rotate(b.rotation, Offset(cx, cy)) { drawRect(accent, Offset(cx - b.w * k / 2, cy - b.h * k / 2), Size(b.w * k, b.h * k), style = Stroke(2.dp.toPx())) } }
            }
        }
        // Feld-Knoten nur für Bedienungshilfen (keine Zeigereingabe: Berührungen gehen an die Bühne).
        if (p.kind == CollageKind.GRID) cells.forEachIndexed { i, r ->
            val l = with(density) { (disp.left + r.left * disp.width).toDp() }; val t = with(density) { (disp.top + r.top * disp.height).toDp() }
            Box(Modifier.align(Alignment.TopStart).offset(l, t).size(with(density) { (r.width * disp.width).toDp() }, with(density) { (r.height * disp.height).toDp() })
                .semantics { contentDescription = "$photoLabel ${i + 1}"; selected = vm.selected == i; onClick { vm.selected = i; true } }.testTag("collage.cell"))
        }
        // Griffe auf den Trennlinien (eigene Ziele, 48 dp)
        if (p.kind == CollageKind.GRID && !swapping) {
            val dividers = remember(p.tree) { CollageLayout.dividers(p.tree) }
            for (d in dividers) DividerHandle(vm, d, disp, p, Modifier.align(Alignment.TopStart))
        }
    }
}

private fun stripSelect(v: CollageViewModel, o: Offset, disp: Rect) {
    val p = v.project
    val sizes = p.items.indices.map { i -> v.aspectOf(i)?.let { (it * 1000).toInt() to 1000 } ?: (1 to 1) }
    val (_, place) = StripLayout.layout(sizes, p.items, p.stripVertical, 0, 1000, Int.MAX_VALUE)
    val x = (o.x - disp.left) / disp.width; val y = (o.y - disp.top) / disp.height
    v.selected = place.indexOfFirst { (r, _) -> x in r.left..r.right && y in r.top..r.bottom }.takeIf { it >= 0 }
}

@Composable
private fun DividerHandle(vm: CollageViewModel, d: Divider, disp: Rect, p: CollageProject, modifier: Modifier) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val c = PM.colors
    val label = stringResource(if (d.vertical) R.string.col_divider_v else R.string.col_divider_h)
    // Lage mit Abständen: Mitte der Trennlinie im Bild
    // Baumkoordinaten → Bild (Außenrand wie in cellRects)
    val mx = p.margin * (if (p.aspect >= 1f) 1f / p.aspect else 1f); val my = p.margin * (if (p.aspect >= 1f) 1f else p.aspect)
    val sw = disp.width * (1f - 2 * mx); val sh = disp.height * (1f - 2 * my)
    val cx = disp.left + mx * disp.width + ((d.span.left + d.span.right) / 2) * sw
    val cy = disp.top + my * disp.height + ((d.span.top + d.span.bottom) / 2) * sh
    val size = with(density) { 48.dp.toPx() }
    val dNow by rememberUpdatedState(d)
    Box(modifier.offset { IntOffset((cx - size / 2).roundToInt(), (cy - size / 2).roundToInt()) }.size(48.dp)
        .semantics {
            contentDescription = label
            customActions = listOf(
                CustomAccessibilityAction("−") { vm.moveDivider(dNow, dNow.position - 0.05f, true); true },
                CustomAccessibilityAction("+") { vm.moveDivider(dNow, dNow.position + 0.05f, true); true })
        }
        .testTag("collage.divider")
        .pointerInput(d.path, d.vertical) {
            awaitEachGesture {
                val down = awaitFirstDown(); down.consume()
                var pos = dNow.position
                var lastSnap = false
                while (true) {
                    val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val delta = ch.positionChange()
                    pos += if (d.vertical) delta.x / sw else delta.y / sh
                    // Einrasten bei der Hälfte des Elternbereichs
                    val half = if (d.vertical) (d.parent.left + d.parent.right) / 2 else (d.parent.top + d.parent.bottom) / 2
                    val snap = abs(pos - half) < 0.012f
                    if (snap && !lastSnap) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    lastSnap = snap
                    vm.moveDivider(dNow, if (snap) half else pos, false); ch.consume()
                }
                vm.commit()
            }
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(if (d.vertical) 10.dp else 30.dp, if (d.vertical) 30.dp else 10.dp).clip(CircleShape).background(Color.White)
            .border(1.dp, c.accent, CircleShape))
    }
}

/** Zugriff auf die Box-Berechnung ohne Renderer-Instanz in der Oberfläche. */
internal object CollageRendererBox {
    fun freeBox(it: CollageItem, a: Float, w: Int, h: Int, polaroid: Boolean): LayerBox {
        val iw = it.width * w; val ih = iw / a
        val pad = if (polaroid) iw * 0.045f else 0f
        return LayerBox(it.center.x * w, it.center.y * h + (if (polaroid) pad * 1.1f else 0f), iw + 2 * pad, ih + (if (polaroid) pad * 4.2f else 0f), it.rotation)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Werkzeuge

@Composable
private fun LayoutPanel(vm: CollageViewModel) {
    val p = vm.project
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Chip(stringResource(R.string.col_kind_grid), p.kind == CollageKind.GRID, Modifier.testTag("ckind.GRID")) { vm.setKind(CollageKind.GRID) }
            Chip(stringResource(R.string.col_kind_free), p.kind == CollageKind.FREE, Modifier.testTag("ckind.FREE")) { vm.setKind(CollageKind.FREE) }
            Chip(stringResource(R.string.col_kind_strip), p.kind == CollageKind.STRIP, Modifier.testTag("ckind.STRIP")) { vm.setKind(CollageKind.STRIP) }
        }
        when (p.kind) {
            CollageKind.GRID -> {
                val templates = remember(p.count) { CollageLayout.templatesFor(p.count) }
                LazyRow(Modifier.fillMaxWidth().testTag("collage.templates"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    items(templates) { t -> TemplateThumb(t, p.count, t == p.template) { vm.setTemplate(t) } }
                }
                Text(stringResource(R.string.col_layout_hint), style = AppTypography.footnote, color = PM.colors.textSecondary)
            }
            CollageKind.FREE -> SwitchLine(stringResource(R.string.col_polaroid), p.polaroid, "collage.polaroid") { vm.setPolaroid(it) }
            CollageKind.STRIP -> Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                Chip(stringResource(R.string.col_strip_vertical), p.stripVertical, Modifier.testTag("strip.v")) { vm.setStripVertical(true) }
                Chip(stringResource(R.string.col_strip_horizontal), !p.stripVertical, Modifier.testTag("strip.h")) { vm.setStripVertical(false) }
            }
        }
    }
}

@Composable
private fun SwitchLine(label: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.minTouch).clickable(role = Role.Switch) { onChange(!on) }.testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AppTypography.body, color = PM.colors.textPrimary, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(on, null)
    }
}

@Composable
private fun TemplateThumb(t: String, n: Int, sel: Boolean, onClick: () -> Unit) {
    val c = PM.colors
    val cells = remember(t, n) { CollageLayout.cells(CollageLayout.template(t, n)).values.toList() }
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(if (sel) c.accent.copy(alpha = 0.15f) else c.backgroundTint)
        .border(if (sel) 2.dp else 0.dp, if (sel) c.accent else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable(role = Role.RadioButton, onClick = onClick).semantics { selected = sel }.testTag("template.$t").padding(7.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val g = 1.5.dp.toPx()
            for (r in cells) drawRoundRect(if (sel) c.accent else c.textSecondary.copy(alpha = 0.6f), Offset(r.left * size.width + g, r.top * size.height + g),
                Size(r.width * size.width - 2 * g, r.height * size.height - 2 * g), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
    }
}

@Composable
private fun FormatPanel(vm: CollageViewModel) {
    val p = vm.project
    val options = listOf(1f to "1:1", 0.8f to "4:5", 0.75f to "3:4", 0.5625f to "9:16", 1.7778f to "16:9", 1.5f to "3:2", 0.7071f to stringResource(R.string.col_a4_portrait), 1.4142f to stringResource(R.string.col_a4_landscape))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("collage.aspects"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        for ((a, label) in options) Chip(label, abs(p.aspect - a) < 0.005f, Modifier.testTag("caspect.$label")) { vm.setAspect(a) }
    }
}

@Composable
private fun SpacingPanel(vm: CollageViewModel) {
    val p = vm.project
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        EditSlider(stringResource(R.string.col_spacing), p.spacing * 1000f, 0f..60f, "${(p.spacing * 1000).roundToInt()}", Modifier.testTag("slider.spacing"), snap = 0f) { v, c -> vm.setSpacing(v / 1000f, c) }
        if (p.kind == CollageKind.GRID) {
            EditSlider(stringResource(R.string.col_margin), p.margin * 1000f, 0f..80f, "${(p.margin * 1000).roundToInt()}", snap = 0f) { v, c -> vm.setMargin(v / 1000f, c) }
            EditSlider(stringResource(R.string.edit_frame_corner), p.corner * 1000f, 0f..100f, "${(p.corner * 1000).roundToInt()}", snap = 0f) { v, c -> vm.setCorner(v / 1000f, c) }
        }
    }
}

@Composable
private fun BackgroundPanel(vm: CollageViewModel) {
    val p = vm.project
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        ColorSwatches(if (p.background.kind == BackgroundKind.COLOR) p.background.color else 0, { vm.setBackground(it) },
            listOf(0xFFFFFFFF, 0xFF111111, 0xFFF4EFE6, 0xFFE5E5EA).map { it.toInt() } + PALETTE.drop(2), "cbg")
        Chip(stringResource(R.string.col_bg_blur), p.background.kind == BackgroundKind.BLUR, Modifier.testTag("cbg.blur")) { vm.setBlurBackground() }
    }
}

@Composable
private fun PhotoPanel(vm: CollageViewModel, pick: (PickRequest) -> Unit) {
    val c = PM.colors
    val app = LocalApp.current
    val sel = vm.selected
    val p = vm.project
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (p.count < CollageProject.MAX) ToolCircle(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.collage_add), "cphoto.add") {
                pick(PickRequest(java.util.UUID.randomUUID().toString(), max = CollageProject.MAX - p.count, title = R.string.collage_add, onDone = { vm.addPhotos(it) }))
            }
            if (sel != null) {
                ToolCircle(Icons.Rounded.SwapHoriz, stringResource(R.string.col_replace), "cphoto.replace") {
                    pick(PickRequest(java.util.UUID.randomUUID().toString(), max = 1, title = R.string.col_replace, onDone = { it.firstOrNull()?.let(vm::replaceSelected) }))
                }
                ToolCircle(Icons.Rounded.Rotate90DegreesCw, stringResource(R.string.edit_rotate), "cphoto.rotate") { vm.updateItem(sel, true) { it.copy(quarterTurns = (it.quarterTurns + 1) % 4, zoom = 1f, panX = 0f, panY = 0f) } }
                ToolCircle(Icons.Rounded.Flip, stringResource(R.string.edit_flip_h), "cphoto.flip") { vm.updateItem(sel, true) { it.copy(flipH = !it.flipH) } }
                if (p.kind == CollageKind.GRID) ToolCircle(if (p.items[sel].fit) Icons.Rounded.ZoomOutMap else Icons.Rounded.PhotoSizeSelectActual,
                    stringResource(if (p.items[sel].fit) R.string.col_fill else R.string.col_fit), "cphoto.fit") { vm.updateItem(sel, true) { it.copy(fit = !it.fit) } }
                if (p.count > 1) ToolCircle(Icons.Rounded.Delete, stringResource(R.string.edit_mk_delete), "cphoto.remove") { vm.removeSelected() }
            }
        }
        if (sel == null) Text(stringResource(R.string.col_photo_hint), style = AppTypography.footnote, color = c.textSecondary, modifier = Modifier.testTag("cphoto.hint"))
        else {
            val it = p.items[sel]
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                for (f in FilterId.entries) Chip(stringResource(filterLabel(f)), it.filter == f, Modifier.testTag("cfilter.${f.name}")) { vm.updateItem(sel, true) { x -> x.copy(filter = f) } }
            }
            if (p.kind == CollageKind.STRIP) {
                EditSlider(stringResource(if (p.stripVertical) R.string.col_trim_top else R.string.col_trim_left), it.trimStart * 100f, 0f..45f, "${(it.trimStart * 100).roundToInt()} %", Modifier.testTag("slider.trimStart"), snap = 0f) { v, cm ->
                    vm.updateItem(sel, cm) { x -> x.copy(trimStart = v / 100f) } }
                EditSlider(stringResource(if (p.stripVertical) R.string.col_trim_bottom else R.string.col_trim_right), it.trimEnd * 100f, 0f..45f, "${(it.trimEnd * 100).roundToInt()} %", snap = 0f) { v, cm ->
                    vm.updateItem(sel, cm) { x -> x.copy(trimEnd = v / 100f) } }
            }
        }
    }
}

private fun filterLabel(f: FilterId) = when (f) {
    FilterId.NONE -> R.string.edit_filter_none; FilterId.MONO -> R.string.edit_filter_mono; FilterId.MONO_CONTRAST -> R.string.edit_filter_mono_contrast
    FilterId.WARM -> R.string.edit_filter_warm; FilterId.COOL -> R.string.edit_filter_cool; FilterId.SEPIA -> R.string.edit_filter_sepia
    FilterId.FADE -> R.string.edit_filter_fade; FilterId.VIVID -> R.string.edit_filter_vivid
}

@Composable
private fun ToolCircle(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val c = PM.colors
    Column(Modifier.width(70.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) {}
        .testTag(tag).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(c.backgroundTint), contentAlignment = Alignment.Center) { Icon(icon, null, tint = c.textPrimary, modifier = Modifier.size(22.dp)) }
        Text(label, style = AppTypography.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Speichern

@Composable
private fun CollageSaveSheet(vm: CollageViewModel, onDismiss: () -> Unit, onPro: () -> Unit) {
    val c = PM.colors
    EditorExportPage(stringResource(R.string.save), onDismiss, Modifier.testTag("collage.sheet")) {
        run {
            Text(stringResource(R.string.col_size), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                Chip(stringResource(R.string.col_size_standard), vm.size == CollageSize.STANDARD, Modifier.testTag("csize.STANDARD")) { vm.size = CollageSize.STANDARD }
                Chip(stringResource(R.string.col_size_large), vm.size == CollageSize.LARGE, Modifier.testTag("csize.LARGE")) { vm.size = CollageSize.LARGE }
                if (vm.project.kind != CollageKind.STRIP) Chip(stringResource(R.string.col_size_a4), vm.size == CollageSize.A4, Modifier.testTag("csize.A4"),
                    icon = if (!LocalApp.current.capabilities.hasProToolsAccess) Icons.Rounded.WorkspacePremium else null) { vm.size = CollageSize.A4 }
            }
            Text(stringResource(R.string.col_format), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                Chip("JPEG", !vm.png, Modifier.testTag("cfmt.jpeg")) { vm.png = false }
                Chip("PNG", vm.png, Modifier.testTag("cfmt.png")) { vm.png = true }
            }
            Text(stringResource(if (vm.png) R.string.col_png_hint else R.string.col_jpeg_hint), style = AppTypography.footnote, color = c.textSecondary)
            if (vm.needsPro) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.accent.copy(alpha = 0.10f)).padding(AppSpacing.m).testTag("collage.sheet.pro"),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text(stringResource(R.string.edit_pro_title), style = AppTypography.headline, color = c.textPrimary)
                    Text(stringResource(R.string.col_pro_text), style = AppTypography.subheadline, color = c.textSecondary)
                    EditorWideButton(stringResource(R.string.edit_pro_unlock), Modifier.testTag("collage.sheet.unlock"), onClick = onPro)
                }
            } else {
                EditorWideButton(stringResource(R.string.col_save_action), Modifier.testTag("collage.sheet.save")) { onDismiss(); vm.save() }
                EditorWideButton(stringResource(R.string.edit_share_only), Modifier.testTag("collage.sheet.share"), primary = false) { onDismiss(); vm.share() }
                Text(stringResource(R.string.col_save_where), style = AppTypography.footnote, color = c.textSecondary)
            }
        }
    }
}
