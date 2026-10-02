package app.photomaid.feature.editor

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Crop54
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FlipToBack
import androidx.compose.material.icons.rounded.FlipToFront
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.Interests
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photomaid.R
import app.photomaid.core.edit.BrushKind
import app.photomaid.core.edit.FontId
import app.photomaid.core.edit.FrameMath
import app.photomaid.core.edit.FrameMode
import app.photomaid.core.edit.FrameStyle
import app.photomaid.core.edit.Layer
import app.photomaid.core.edit.LayerOps
import app.photomaid.core.edit.NormPoint
import app.photomaid.core.edit.PxSize
import app.photomaid.core.edit.RedactLayer
import app.photomaid.core.edit.RedactMode
import app.photomaid.core.edit.ShapeKind
import app.photomaid.core.edit.ShapeLayer
import app.photomaid.core.edit.StickerKind
import app.photomaid.core.edit.StickerLayer
import app.photomaid.core.edit.TextAlign
import app.photomaid.core.edit.TextLayer
import app.photomaid.core.edit.TextStyle
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.PM
import app.photomaid.media.LayerRenderer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Farbauswahl für Text, Sticker, Zeichnen, Formen (gut unterscheidbar, hell und dunkel). */
internal val PALETTE = listOf(
    0xFFFFFFFF, 0xFF111111, 0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF00C7BE, 0xFF007AFF, 0xFF5856D6, 0xFFAF52DE, 0xFFFF2D55, 0xFF8E8E93,
).map { it.toInt() }

/** Emoji für Sticker (Systemschrift; keine fremden Sticker-Pakete). */
private val EMOJI = ("😀 😂 🥹 😍 😎 🤩 😘 😜 🤔 😴 😭 😡 👍 👎 👏 🙏 💪 👀 ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 ✨ ⭐ 🔥 🎉 🎂 🎁 🎈 🌸 🌻 🌈 ☀️ " +
    "🌙 ⛄ 🌊 🏔️ 🌴 🐶 🐱 🦊 🐻 🐼 🦄 🐝 🍕 🍔 🍦 🍩 ☕ 🍷 🍺 ⚽ 🏀 🎾 🚴 🏖️ ✈️ 🚗 🏠 📍 📷 🎵 💡 ✅ ❌ ⚠️ 🔒 💯 👉 👆").split(' ')

private fun Int.c() = Color(this)

/** Bildbereich im Reiter „Markieren“: Ebenen auswählen, verschieben, skalieren/drehen, zeichnen, Formen, Abdecken. */
@Composable
internal fun MarkupStage(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val res = vm.resultPreview
    val base = vm.baseResult
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val vmNow by rememberUpdatedState(vm)
    val live = remember { mutableStateListOf<Offset>() }
    var drag by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var guides by remember { mutableStateOf(false to false) }
    val stageLabel = stringResource(R.string.edit_markup_stage)
    BoxWithConstraints(modifier.padding(AppSpacing.m).testTag("editor.markup"), contentAlignment = Alignment.Center) {
        if (res == null || base == null) return@BoxWithConstraints
        val box = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val disp = fitRect(box, res.width.toFloat() / res.height)
        // Mit Rahmen: Lage des eigentlichen Bildes im gerahmten Vorschaubild.
        val (framed, pos) = FrameMath.layout(PxSize(base.width, base.height), vm.recipe.frame)
        val inner = Rect(disp.left + pos.first / framed.width.toFloat() * disp.width, disp.top + pos.second / framed.height.toFloat() * disp.height,
            disp.left + (pos.first + base.width) / framed.width.toFloat() * disp.width, disp.top + (pos.second + base.height) / framed.height.toFloat() * disp.height)
        val pw = base.width; val ph = base.height
        val k = inner.width / pw // Vorschau-Pixel → Bildschirm
        fun norm(o: Offset) = NormPoint(((o.x - inner.left) / inner.width).coerceIn(0f, 1f), ((o.y - inner.top) / inner.height).coerceIn(0f, 1f))
        val img = remember(res) { res.asImageBitmap() }
        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(Modifier.fillMaxSize().semantics {
            contentDescription = stageLabel
            val sel = vmNow.selectedLayer
            if (sel != null) customActions = listOf(
                CustomAccessibilityAction("←") { vmNow.updateLayer(sel.moved(-0.05f, 0f), true); true },
                CustomAccessibilityAction("→") { vmNow.updateLayer(sel.moved(0.05f, 0f), true); true },
                CustomAccessibilityAction("↑") { vmNow.updateLayer(sel.moved(0f, -0.05f), true); true },
                CustomAccessibilityAction("↓") { vmNow.updateLayer(sel.moved(0f, 0.05f), true); true },
            )
        }.testTag("markup.canvas").pointerInput(vm.markupTool, inner, vm.redactBrush) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val v = vmNow
                when (v.markupTool) {
                    MarkupTool.DRAW, MarkupTool.REDACT -> {
                        val brushLike = v.markupTool == MarkupTool.DRAW || v.redactBrush
                        live.clear(); live.add(down.position); drag = down.position to down.position
                        while (true) {
                            val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (brushLike) live.add(ch.position) else drag = down.position to ch.position
                            ch.consume()
                        }
                        val pts = live.map(::norm)
                        if (v.markupTool == MarkupTool.DRAW) v.addStroke(pts)
                        else if (v.redactBrush) v.addRedactStroke(pts)
                        else drag?.let { (a, b) -> v.addRedactRect(norm(a), norm(b)) }
                        live.clear(); drag = null
                    }
                    MarkupTool.SHAPE -> {
                        drag = down.position to down.position
                        while (true) {
                            val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            drag = down.position to ch.position; ch.consume()
                        }
                        drag?.let { (a, b) -> v.addShape(norm(a), norm(b)) }
                        drag = null
                    }
                    MarkupTool.SELECT -> {
                        // Oberste getroffene Ebene wählen (Abstand 12 dp für kleine Elemente).
                        val px = (down.position.x - inner.left) / k; val py = (down.position.y - inner.top) / k
                        val pad = 12.dp.toPx() / k
                        val hit = v.layers.asReversed().firstOrNull { LayerRenderer.box(it, pw, ph)?.contains(px, py, pad) == true }
                        v.selectedLayerId = hit?.id
                        val start = hit ?: return@awaitEachGesture
                        var cur: Layer = start
                        var moved = false; var snappedX = false; var snappedY = false
                        val base0 = start
                        var scaleAcc = 1f; var rotAcc = (start as? TextLayer)?.rotation ?: (start as? StickerLayer)?.rotation ?: 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.all { !it.pressed }) break
                            if (ev.changes.size >= 2) {
                                scaleAcc *= ev.calculateZoom()
                                val (r, snapped) = LayerOps.snapRotation(rotAcc + ev.calculateRotation())
                                rotAcc += ev.calculateRotation()
                                cur = cur.transformed(1f, r).let { if (it is TextLayer) it.copy(size = ((base0 as TextLayer).size * scaleAcc).coerceIn(0.01f, 0.6f))
                                    else if (it is StickerLayer) it.copy(size = ((base0 as StickerLayer).size * scaleAcc).coerceIn(0.03f, 1.2f)) else it }
                                if (snapped && !snappedX) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                snappedX = snapped
                            } else {
                                val ch = ev.changes.first(); val d = ch.positionChange()
                                if (d != Offset.Zero) {
                                    cur = cur.moved(d.x / inner.width, d.y / inner.height)
                                    // Einrasten an der Bildmitte (Hilfslinie + Haptik).
                                    if (cur is TextLayer || cur is StickerLayer) {
                                        val c = if (cur is TextLayer) (cur as TextLayer).center else (cur as StickerLayer).center
                                        val (sx, okX) = LayerOps.snap(c.x); val (sy, okY) = LayerOps.snap(c.y)
                                        if (okX && !snappedX || okY && !snappedY) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        snappedX = okX; snappedY = okY; guides = okX to okY
                                        cur = cur.moved(sx - c.x, sy - c.y)
                                    }
                                }
                            }
                            moved = true
                            ev.changes.forEach { it.consume() }
                            v.updateLayer(cur, commit = false)
                        }
                        guides = false to false
                        if (moved) v.updateLayer(cur, commit = true)
                    }
                }
            }
        }) {
            // Hilfslinien beim Einrasten
            val (gx, gy) = guides
            val guide = Color(0xFFFFCC00)
            if (gx) drawLine(guide, Offset(inner.center.x, inner.top), Offset(inner.center.x, inner.bottom), 1.dp.toPx())
            if (gy) drawLine(guide, Offset(inner.left, inner.center.y), Offset(inner.right, inner.center.y), 1.dp.toPx())
            // Auswahlrahmen
            vm.selectedLayer?.let { l -> LayerRenderer.box(l, pw, ph) }?.let { b ->
                val cx = inner.left + b.cx * k; val cy = inner.top + b.cy * k
                rotate(b.rotation, Offset(cx, cy)) {
                    val r = Rect(cx - b.w * k / 2 - 6.dp.toPx(), cy - b.h * k / 2 - 6.dp.toPx(), cx + b.w * k / 2 + 6.dp.toPx(), cy + b.h * k / 2 + 6.dp.toPx())
                    drawRect(Color.White, r.topLeft, r.size, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                    for (p in listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight)) drawCircle(Color.White, 5.dp.toPx(), p)
                }
            }
            // Live-Strich / Form / Rechteck
            if (live.size > 1) {
                val path = Path().apply { moveTo(live[0].x, live[0].y); live.drop(1).forEach { lineTo(it.x, it.y) } }
                val short = min(pw, ph) * k
                val w = if (vm.markupTool == MarkupTool.REDACT) vm.drawWidth * 4f * max(pw, ph) * k else vm.drawWidth * short * (if (vm.brush == BrushKind.MARKER) 2.2f else 1f)
                val col = when {
                    vm.markupTool == MarkupTool.REDACT -> Color.Black.copy(alpha = 0.6f)
                    vm.brush == BrushKind.ERASER -> Color.White.copy(alpha = 0.5f)
                    vm.brush == BrushKind.MARKER -> vm.drawColor.c().copy(alpha = 0.45f)
                    else -> vm.drawColor.c()
                }
                drawPath(path, col, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            drag?.let { (a, b) ->
                val r = Rect(Offset(min(a.x, b.x), min(a.y, b.y)), Offset(max(a.x, b.x), max(a.y, b.y)))
                if (vm.markupTool == MarkupTool.REDACT) drawRect(Color.Black.copy(alpha = 0.55f), r.topLeft, r.size)
                else when (vm.shapeKind) {
                    ShapeKind.RECT -> drawRect(vm.drawColor.c(), r.topLeft, r.size, style = Stroke(vm.drawWidth * min(pw, ph) * k))
                    ShapeKind.ELLIPSE -> drawOval(vm.drawColor.c(), r.topLeft, r.size, style = Stroke(vm.drawWidth * min(pw, ph) * k))
                    else -> drawLine(vm.drawColor.c(), a, b, vm.drawWidth * min(pw, ph) * k, StrokeCap.Round)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Werkzeuge

@Composable
private fun MarkupToolButton(icon: ImageVector, label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val c = PM.colors
    Column(Modifier.width(66.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.RadioButton, onClick = onClick)
        .semantics(mergeDescendants = true) { this.selected = selected }.testTag(tag).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (selected) c.accentFill else c.backgroundTint), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (selected) Color.White else c.textPrimary, modifier = Modifier.size(22.dp))
        }
        Text(label, style = AppTypography.caption, color = if (selected) c.accent else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ColorSwatches(selected: Int, onPick: (Int) -> Unit, colors: List<Int> = PALETTE, tagPrefix: String = "color") {
    val c = PM.colors
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(colors) { col ->
            val sel = col == selected
            Box(Modifier.size(AppSpacing.minTouch).clip(CircleShape).clickable(role = Role.RadioButton) { onPick(col) }
                .semantics { this.selected = sel }.testTag("$tagPrefix.${Integer.toHexString(col)}"), contentAlignment = Alignment.Center) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(col.c()).border(if (sel) 3.dp else 1.dp, if (sel) c.accent else c.textSecondary.copy(alpha = 0.4f), CircleShape))
            }
        }
    }
}

@Composable
internal fun MarkupPanel(vm: EditorViewModel) {
    val c = PM.colors
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("markup.tools"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MarkupToolButton(Icons.Rounded.NearMe, stringResource(R.string.edit_mk_select), vm.markupTool == MarkupTool.SELECT, "mk.select") { vm.markupTool = MarkupTool.SELECT }
            MarkupToolButton(Icons.Rounded.TextFields, stringResource(R.string.edit_mk_text), false, "mk.text") { vm.addText() }
            MarkupToolButton(Icons.Rounded.EmojiEmotions, stringResource(R.string.edit_mk_sticker), false, "mk.sticker") { vm.showStickers = true }
            MarkupToolButton(Icons.Rounded.Draw, stringResource(R.string.edit_mk_draw), vm.markupTool == MarkupTool.DRAW, "mk.draw") { vm.markupTool = MarkupTool.DRAW; vm.selectedLayerId = null }
            MarkupToolButton(Icons.Rounded.Interests, stringResource(R.string.edit_mk_shape), vm.markupTool == MarkupTool.SHAPE, "mk.shape") { vm.markupTool = MarkupTool.SHAPE; vm.selectedLayerId = null }
            MarkupToolButton(Icons.Rounded.BlurOn, stringResource(R.string.edit_mk_redact), vm.markupTool == MarkupTool.REDACT, "mk.redact") { vm.markupTool = MarkupTool.REDACT; vm.selectedLayerId = null }
        }
        when (vm.markupTool) {
            MarkupTool.SELECT -> {
                val sel = vm.selectedLayer
                if (sel == null) Text(stringResource(R.string.edit_mk_hint), style = AppTypography.footnote, color = c.textSecondary, modifier = Modifier.testTag("mk.hint"))
                else SelectedLayerTools(vm, sel)
            }
            MarkupTool.DRAW -> {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    for (b in BrushKind.entries) Chip(stringResource(b.label), vm.brush == b, Modifier.testTag("brush.${b.name}")) { vm.brush = b }
                }
                EditSlider(stringResource(R.string.edit_mk_size), vm.drawWidth * 1000f, 2f..60f, "${(vm.drawWidth * 1000).roundToInt()}", snap = 0f) { v, _ -> vm.drawWidth = v / 1000f }
                if (vm.brush != BrushKind.ERASER) ColorSwatches(vm.drawColor, { vm.drawColor = it })
            }
            MarkupTool.SHAPE -> {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    for (k in ShapeKind.entries) Chip(stringResource(k.label), vm.shapeKind == k, Modifier.testTag("shape.${k.name}")) { vm.shapeKind = k }
                }
                if (vm.shapeKind == ShapeKind.RECT || vm.shapeKind == ShapeKind.ELLIPSE) SwitchRow(stringResource(R.string.edit_mk_filled), vm.shapeFilled) { vm.shapeFilled = it }
                EditSlider(stringResource(R.string.edit_mk_size), vm.drawWidth * 1000f, 2f..60f, "${(vm.drawWidth * 1000).roundToInt()}", snap = 0f) { v, _ -> vm.drawWidth = v / 1000f }
                ColorSwatches(vm.drawColor, { vm.drawColor = it })
            }
            MarkupTool.REDACT -> {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    for (m in RedactMode.entries) Chip(stringResource(m.label), vm.redactMode == m, Modifier.testTag("redact.${m.name}")) { vm.redactMode = m }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Chip(stringResource(R.string.edit_mk_rect), !vm.redactBrush, Modifier.testTag("redact.rect"), icon = Icons.Rounded.Crop54) { vm.redactBrush = false }
                    Chip(stringResource(R.string.edit_mk_brush), vm.redactBrush, Modifier.testTag("redact.brush"), icon = Icons.Rounded.Brush) { vm.redactBrush = true }
                }
                if (vm.redactMode != RedactMode.SOLID) EditSlider(stringResource(R.string.edit_mk_strength), vm.redactStrength * 100f, 0f..100f,
                    "${(vm.redactStrength * 100).roundToInt()}", snap = 0f) { v, _ -> vm.redactStrength = v / 100f }
                Text(stringResource(if (vm.redactMode == RedactMode.SOLID) R.string.edit_mk_redact_solid_hint else R.string.edit_mk_redact_hint),
                    style = AppTypography.footnote, color = c.textSecondary)
            }
        }
    }
    if (vm.showStickers) StickerSheet(vm)
    vm.editingTextId?.let { id -> (vm.layers.firstOrNull { it.id == id } as? TextLayer)?.let { TextSheet(vm, it) } ?: run { vm.editingTextId = null } }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.minTouch).clickable(role = Role.Switch) { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AppTypography.body, color = PM.colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(on, null)
    }
}

@Composable
private fun SelectedLayerTools(vm: EditorViewModel, l: Layer) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("mk.selected"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (l is TextLayer) MarkupToolButton(Icons.Rounded.Edit, stringResource(R.string.edit_mk_edit_text), false, "mk.edit") { vm.editingTextId = l.id }
        MarkupToolButton(Icons.Rounded.ContentCopy, stringResource(R.string.edit_mk_duplicate), false, "mk.duplicate") { vm.duplicateSelected() }
        MarkupToolButton(Icons.Rounded.FlipToFront, stringResource(R.string.edit_mk_front), false, "mk.front") { vm.selectedToFront() }
        MarkupToolButton(Icons.Rounded.FlipToBack, stringResource(R.string.edit_mk_back), false, "mk.back") { vm.selectedToBack() }
        MarkupToolButton(Icons.Rounded.Delete, stringResource(R.string.edit_mk_delete), false, "mk.delete") { vm.deleteSelected() }
    }
    when (l) {
        is TextLayer -> ColorSwatches(l.color, { vm.updateLayer(l.copy(color = it), true) })
        is StickerLayer -> if (l.kind != StickerKind.EMOJI) ColorSwatches(l.color, { vm.updateLayer(l.copy(color = it), true) })
        is ShapeLayer -> ColorSwatches(l.color, { vm.updateLayer(l.copy(color = it), true) })
        is RedactLayer -> if (l.mode == RedactMode.SOLID) ColorSwatches(l.color, { vm.updateLayer(l.copy(color = it), true) }, listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF8E8E93.toInt()))
        else -> Unit
    }
    val opacity = when (l) { is TextLayer -> l.opacity; is StickerLayer -> l.opacity; is ShapeLayer -> l.opacity; else -> null }
    if (opacity != null) EditSlider(stringResource(R.string.edit_mk_opacity), opacity * 100f, 10f..100f, "${(opacity * 100).roundToInt()} %", snap = 0f) { v, commit ->
        val o = v / 100f
        vm.updateLayer(when (l) { is TextLayer -> l.copy(opacity = o); is StickerLayer -> l.copy(opacity = o); is ShapeLayer -> l.copy(opacity = o); else -> l }, commit)
    }
}

private val BrushKind.label: Int get() = when (this) {
    BrushKind.PEN -> R.string.edit_brush_pen; BrushKind.MARKER -> R.string.edit_brush_marker; BrushKind.NEON -> R.string.edit_brush_neon; BrushKind.ERASER -> R.string.edit_brush_eraser
}
private val ShapeKind.label: Int get() = when (this) {
    ShapeKind.ARROW -> R.string.edit_shape_arrow; ShapeKind.RECT -> R.string.edit_shape_rect; ShapeKind.ELLIPSE -> R.string.edit_shape_ellipse; ShapeKind.LINE -> R.string.edit_shape_line
}
private val RedactMode.label: Int get() = when (this) {
    RedactMode.SOLID -> R.string.edit_redact_solid; RedactMode.PIXELATE -> R.string.edit_redact_pixelate; RedactMode.BLUR -> R.string.edit_redact_blur
}
private val TextStyle.label: Int get() = when (this) {
    TextStyle.FILL -> R.string.edit_ts_plain; TextStyle.SHADOW -> R.string.edit_ts_shadow; TextStyle.OUTLINE -> R.string.edit_ts_outline; TextStyle.PILL -> R.string.edit_ts_pill
}
private val FontId.label: Int get() = when (this) {
    FontId.SANS -> R.string.edit_font_sans; FontId.SANS_BOLD -> R.string.edit_font_bold; FontId.BLACK -> R.string.edit_font_black
    FontId.CONDENSED -> R.string.edit_font_condensed; FontId.SERIF -> R.string.edit_font_serif; FontId.MONO -> R.string.edit_font_mono
    FontId.HANDWRITING -> R.string.edit_font_hand; FontId.CASUAL -> R.string.edit_font_casual; FontId.SMALLCAPS -> R.string.edit_font_smallcaps
}

// ---------------------------------------------------------------------------------------------------------------------
// Text- und Sticker-Blatt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextSheet(vm: EditorViewModel, l: TextLayer) {
    val c = PM.colors
    var text by remember(l.id) { mutableStateOf(l.text) }
    ModalBottomSheet(onDismissRequest = { vm.updateLayer(l.copy(text = text), true); vm.editingTextId = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("text.sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.xl), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            OutlinedTextField(text, { t -> text = t.take(300); vm.updateLayer(l.copy(text = text), false) }, Modifier.fillMaxWidth().testTag("text.field"),
                label = { Text(stringResource(R.string.edit_mk_text)) }, minLines = 1, maxLines = 4)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                for (st in TextStyle.entries) Chip(stringResource(st.label), l.style == st, Modifier.testTag("ts.${st.name}")) { vm.updateLayer(l.copy(style = st, text = text), true) }
            }
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                items(FontId.entries) { f ->
                    val sel = l.font == f
                    val family = remember(f) { FontFamily(LayerRenderer.typeface(f)) }
                    Box(Modifier.heightIn(min = 40.dp).clip(CircleShape).background(if (sel) c.accentFill else c.backgroundTint)
                        .clickable(role = Role.RadioButton) { vm.updateLayer(l.copy(font = f, text = text), true) }.semantics { selected = sel }
                        .padding(horizontal = AppSpacing.s).testTag("font.${f.name}"), contentAlignment = Alignment.Center) {
                        Text(stringResource(f.label), fontFamily = family, fontSize = 15.sp, color = if (sel) Color.White else c.textPrimary,
                            fontWeight = if (f.bold) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                AlignButton(Icons.AutoMirrored.Rounded.FormatAlignLeft, R.string.edit_align_start, l.align == TextAlign.START) { vm.updateLayer(l.copy(align = TextAlign.START, text = text), true) }
                AlignButton(Icons.Rounded.FormatAlignCenter, R.string.edit_align_center, l.align == TextAlign.CENTER) { vm.updateLayer(l.copy(align = TextAlign.CENTER, text = text), true) }
                AlignButton(Icons.AutoMirrored.Rounded.FormatAlignRight, R.string.edit_align_end, l.align == TextAlign.END) { vm.updateLayer(l.copy(align = TextAlign.END, text = text), true) }
            }
            Text(stringResource(R.string.edit_mk_color), style = AppTypography.footnote, color = c.textSecondary)
            ColorSwatches(l.color, { vm.updateLayer(l.copy(color = it, text = text), true) }, tagPrefix = "tcolor")
            if (l.style == TextStyle.PILL || l.style == TextStyle.OUTLINE) {
                Text(stringResource(if (l.style == TextStyle.PILL) R.string.edit_mk_background else R.string.edit_mk_outline), style = AppTypography.footnote, color = c.textSecondary)
                ColorSwatches(l.accent, { vm.updateLayer(l.copy(accent = it, text = text), true) }, tagPrefix = "acolor")
            }
            EditSlider(stringResource(R.string.edit_mk_size), l.size * 100f, 2f..40f, "${(l.size * 100).roundToInt()}", snap = 0f) { v, commit ->
                vm.updateLayer(l.copy(size = v / 100f, text = text), commit)
            }
        }
    }
}

@Composable
private fun AlignButton(icon: ImageVector, label: Int, sel: Boolean, onClick: () -> Unit) {
    val c = PM.colors
    val text = stringResource(label)
    Box(Modifier.size(AppSpacing.minTouch).clip(CircleShape).background(if (sel) c.accentFill else c.backgroundTint)
        .clickable(role = Role.RadioButton, onClick = onClick).semantics { selected = sel; contentDescription = text }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (sel) Color.White else c.textPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StickerSheet(vm: EditorViewModel) {
    val c = PM.colors
    ModalBottomSheet(onDismissRequest = { vm.showStickers = false }, modifier = Modifier.testTag("sticker.sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.l), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Text(stringResource(R.string.edit_mk_shapes_stickers), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                for (k in StickerKind.entries.filter { it != StickerKind.EMOJI }) {
                    val preview = remember(k) { stickerPreview(k, vm.drawColor, if (k == StickerKind.DATE) vm.stickerDate() else "") }
                    val label = stringResource(k.label)
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(c.backgroundTint).clickable(role = Role.Button) { vm.addSticker(k) }
                        .semantics { contentDescription = label }.testTag("sticker.${k.name}"), contentAlignment = Alignment.Center) {
                        Image(preview.asImageBitmap(), null, Modifier.size(44.dp))
                    }
                }
            }
            Text(stringResource(R.string.edit_mk_emoji), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
            LazyVerticalGrid(GridCells.Adaptive(48.dp), Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("sticker.emoji")) {
                items(EMOJI) { e ->
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { vm.addSticker(StickerKind.EMOJI, e) }
                        .semantics { contentDescription = e }, contentAlignment = Alignment.Center) { Text(e, fontSize = 28.sp) }
                }
            }
        }
    }
}

private val StickerKind.label: Int get() = when (this) {
    StickerKind.EMOJI -> R.string.edit_mk_emoji; StickerKind.ARROW -> R.string.edit_st_arrow; StickerKind.HEART -> R.string.edit_st_heart
    StickerKind.STAR -> R.string.edit_st_star; StickerKind.CHECK -> R.string.edit_st_check; StickerKind.CROSS -> R.string.edit_st_cross
    StickerKind.BUBBLE -> R.string.edit_st_bubble; StickerKind.CIRCLE -> R.string.edit_st_circle; StickerKind.SPARKLE -> R.string.edit_st_sparkle
    StickerKind.DATE -> R.string.edit_st_date
}

/** Kleine Vorschau eines Vektor-Stickers mit demselben Renderer. */
private fun stickerPreview(k: StickerKind, color: Int, value: String): Bitmap {
    val b = Bitmap.createBitmap(132, 132, Bitmap.Config.ARGB_8888)
    val l = StickerLayer("p", k, value, size = if (k == StickerKind.DATE) 0.32f else 0.8f, color = if (k == StickerKind.DATE) 0xFFFF8A00.toInt() else color)
    LayerRenderer.apply(b, listOf(l), 132, 132)
    return b
}

// ---------------------------------------------------------------------------------------------------------------------
// Rahmen

@Composable
internal fun FramePanel(vm: EditorViewModel) {
    val f = vm.recipe.frame ?: FrameStyle(width = 0f)
    val aspects = listOf<Pair<Float?, String>>(null to stringResource(R.string.edit_frame_even), 1f to "1:1", 0.8f to "4:5", 0.5625f to "9:16", 1.7778f to "16:9")
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Chip(stringResource(R.string.edit_frame_outside), f.mode == FrameMode.OUTSIDE, Modifier.testTag("frame.OUTSIDE")) { vm.setFrame(f.copy(mode = FrameMode.OUTSIDE)) }
            Chip(stringResource(R.string.edit_frame_inside), f.mode == FrameMode.INSIDE, Modifier.testTag("frame.INSIDE")) { vm.setFrame(f.copy(mode = FrameMode.INSIDE, aspect = null)) }
        }
        EditSlider(stringResource(R.string.edit_frame_width), f.width * 100f, 0f..25f, "${(f.width * 100).roundToInt()} %", Modifier.testTag("slider.FRAME"), snap = 0f) { v, commit ->
            vm.setFrame(f.copy(width = v / 100f), commit)
        }
        if (f.mode == FrameMode.OUTSIDE) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            for ((a, label) in aspects) Chip(label, f.aspect == a, Modifier.testTag("frame.aspect.${a ?: 0f}")) { vm.setFrame(f.copy(aspect = a)) }
        }
        EditSlider(stringResource(R.string.edit_frame_corner), f.corner * 100f, 0f..20f, "${(f.corner * 100).roundToInt()}", snap = 0f) { v, commit ->
            vm.setFrame(f.copy(corner = v / 100f), commit)
        }
        ColorSwatches(f.color, { vm.setFrame(f.copy(color = it)) }, listOf(0xFFFFFFFF, 0xFF111111, 0xFFF4EFE6, 0xFFE5E5EA, 0xFF8E8E93).map { it.toInt() } + PALETTE.drop(2), "fcolor")
    }
}
