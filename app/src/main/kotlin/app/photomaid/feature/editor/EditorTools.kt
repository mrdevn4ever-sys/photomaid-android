package app.photomaid.feature.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.ScreenRotationAlt
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Deblur
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Brightness3
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Details
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.FilterTiltShift
import androidx.compose.material.icons.rounded.Vignette
import androidx.compose.material.icons.rounded.Grain
import app.photomaid.core.edit.EditPreset
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.edit.AspectPreset
import app.photomaid.core.edit.ColorMatrices
import app.photomaid.core.edit.FilterId
import app.photomaid.core.edit.PxSize
import app.photomaid.core.edit.ResizeRules
import app.photomaid.design.AppIconSize
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.PM
import app.photomaid.design.glassCapsule
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.roundToInt
import app.photomaid.navigation.LocalApp

internal val EditorTab.icon: ImageVector get() = when (this) {
    EditorTab.CROP -> Icons.Rounded.Crop; EditorTab.ADJUST -> Icons.Rounded.Tune; EditorTab.FILTER -> Icons.Rounded.AutoAwesome
    EditorTab.RETOUCH -> Icons.Rounded.Healing
    EditorTab.MARKUP -> Icons.Rounded.Draw; EditorTab.FRAME -> Icons.Rounded.CropFree
    EditorTab.RESIZE -> Icons.Rounded.PhotoSizeSelectLarge; EditorTab.TRIM -> Icons.Rounded.ContentCut
}

internal val EditorTab.label: Int get() = when (this) {
    EditorTab.CROP -> R.string.edit_tab_crop; EditorTab.ADJUST -> R.string.edit_tab_adjust; EditorTab.FILTER -> R.string.edit_tab_filter
    EditorTab.RETOUCH -> R.string.edit_tab_retouch
    EditorTab.MARKUP -> R.string.edit_tab_markup; EditorTab.FRAME -> R.string.edit_tab_frame
    EditorTab.RESIZE -> R.string.edit_tab_resize; EditorTab.TRIM -> R.string.edit_tab_trim
}

/** Reiter, deren Speichern Pro braucht (Vorschau bleibt frei). */
internal val EditorTab.isPro: Boolean get() = this != EditorTab.CROP && this != EditorTab.MARKUP && this != EditorTab.RETOUCH

internal val AdjustTool.icon: ImageVector get() = when (this) {
    AdjustTool.EXPOSURE -> Icons.Rounded.Exposure; AdjustTool.BRIGHTNESS -> Icons.Rounded.BrightnessMedium
    AdjustTool.CONTRAST -> Icons.Rounded.Contrast; AdjustTool.HIGHLIGHTS -> Icons.Rounded.WbSunny
    AdjustTool.SHADOWS -> Icons.Rounded.Brightness3; AdjustTool.WHITES -> Icons.Rounded.LightMode; AdjustTool.BLACKS -> Icons.Rounded.DarkMode
    AdjustTool.WARMTH -> Icons.Rounded.Thermostat; AdjustTool.TINT -> Icons.Rounded.Gradient; AdjustTool.SATURATION -> Icons.Rounded.Palette
    AdjustTool.VIBRANCE -> Icons.Rounded.ColorLens; AdjustTool.CLARITY -> Icons.Rounded.Details; AdjustTool.SHARPNESS -> Icons.Rounded.Deblur
    AdjustTool.SHARPEN_RADIUS -> Icons.Rounded.RadioButtonChecked; AdjustTool.SHARPEN_THRESHOLD -> Icons.Rounded.FilterTiltShift
    AdjustTool.VIGNETTE -> Icons.Rounded.Vignette; AdjustTool.GRAIN -> Icons.Rounded.Grain
}

private val FilterId.label: Int get() = when (this) {
    FilterId.NONE -> R.string.edit_filter_none; FilterId.MONO -> R.string.edit_filter_mono; FilterId.MONO_CONTRAST -> R.string.edit_filter_mono_contrast
    FilterId.WARM -> R.string.edit_filter_warm; FilterId.COOL -> R.string.edit_filter_cool; FilterId.SEPIA -> R.string.edit_filter_sepia
    FilterId.FADE -> R.string.edit_filter_fade; FilterId.VIVID -> R.string.edit_filter_vivid
}

@Composable
private fun aspectLabel(p: AspectPreset, swapped: Boolean): String = when (p) {
    AspectPreset.FREE -> stringResource(R.string.edit_aspect_free)
    AspectPreset.ORIGINAL -> stringResource(R.string.edit_aspect_original)
    else -> if (swapped) "${p.h}:${p.w}" else "${p.w}:${p.h}"
}

internal fun signed(v: Float, digits: Int = 0): String {
    val nf = NumberFormat.getNumberInstance().apply { maximumFractionDigits = digits; minimumFractionDigits = digits }
    val r = if (digits == 0) v.roundToInt().toFloat() else v
    return (if (r > 0f) "+" else "") + nf.format(r)
}

// ---------------------------------------------------------------------------------------------------------------------
// Gemeinsame Bausteine

/** Regler mit Wert, Einrasten bei 0 (Haptik), Antippen des Werts = 0. Rückgängig-Schritt erst beim Loslassen. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun EditSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, modifier: Modifier = Modifier,
    snap: Float = 1.5f, onChange: (Float, Boolean) -> Unit,
) {
    val c = PM.colors
    val haptics = LocalHapticFeedback.current
    var last by remember { mutableStateOf(value) }
    val resetLabel = stringResource(R.string.edit_reset_value)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, modifier = Modifier.weight(1f))
            Box(Modifier.heightIn(min = AppSpacing.minTouch).clip(CircleShape).clickable(role = Role.Button, onClickLabel = resetLabel) {
                onChange(0f.coerceIn(range), true) }.padding(horizontal = AppSpacing.s), contentAlignment = Alignment.Center) {
                Text(valueText, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = if (value == 0f) c.textSecondary else c.accent, modifier = Modifier.testTag("editor.value"))
            }
        }
        val bipolar = range.start < 0f
        val colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = if (bipolar) c.textSecondary.copy(alpha = 0.25f) else c.accent,
            inactiveTrackColor = c.textSecondary.copy(alpha = 0.25f), activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Nullpunkt-Markierung (Regler −100…+100 füllen nicht von links, sondern zeigen die Abweichung von 0).
            if (bipolar) Box(Modifier.size(width = 2.dp, height = 14.dp).clip(CircleShape).background(c.textSecondary.copy(alpha = 0.6f)))
            Slider(value = value, valueRange = range, modifier = Modifier.fillMaxWidth().testTag("editor.slider")
                .semantics { contentDescription = label; stateDescription = valueText },
                onValueChange = { raw ->
                    val v = if (abs(raw) < snap && bipolar) 0f else raw
                    if ((v == 0f) != (last == 0f) && v == 0f) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    last = v
                    onChange(v, false)
                },
                onValueChangeFinished = { onChange(last, true) },
                colors = colors,
                track = { st -> SliderDefaults.Track(sliderState = st, colors = colors, drawStopIndicator = null, thumbTrackGapSize = 4.dp) })
        }
    }
}

@Composable
internal fun ToolButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, iconModifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = PM.colors
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) {}.padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xxs).heightIn(min = AppSpacing.minTouch),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = c.textPrimary, modifier = iconModifier.size(AppIconSize.regular))
        Text(label, style = AppTypography.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null, dot: Boolean = false,
                  onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = PM.colors
    Row(modifier.heightIn(min = 40.dp).clip(CircleShape)
        .background(if (selected) c.accentFill else c.backgroundTint)
        .combinedClickable(role = Role.RadioButton, onLongClick = onLongClick, onClick = onClick).semantics { this.selected = selected }
        .padding(horizontal = AppSpacing.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, null, tint = if (selected) Color.White else c.textPrimary, modifier = Modifier.size(18.dp))
        Text(text, style = AppTypography.subheadline.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) Color.White else c.textPrimary, maxLines = 1)
        if (dot) Box(Modifier.size(6.dp).clip(CircleShape).background(if (selected) Color.White else c.accent))
    }
}

/** „Vorschau frei · Speichern mit Pro“ – sichtbar, sobald ein Pro-Werkzeug wirkt und kein Zugang besteht. */
@Composable
internal fun ProHint(onPro: () -> Unit, modifier: Modifier = Modifier) {
    val c = PM.colors
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = 0.10f))
        .clickable(role = Role.Button, onClick = onPro).padding(horizontal = AppSpacing.s, vertical = AppSpacing.xs).testTag("editor.prohint"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Icon(Icons.Rounded.WorkspacePremium, null, tint = c.accent, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.edit_pro_hint), style = AppTypography.footnote, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.edit_pro_more), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Zuschneiden & Drehen

@Composable
internal fun CropPanel(vm: EditorViewModel) {
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Chip(stringResource(R.string.edit_crop_mode_crop), !vm.perspectiveMode, Modifier.testTag("crop.mode.crop")) { vm.perspectiveMode = false }
            Chip(stringResource(R.string.edit_crop_mode_perspective), vm.perspectiveMode, Modifier.testTag("crop.mode.persp"), dot = vm.recipe.hasPerspective) { vm.perspectiveMode = true }
        }
        if (vm.perspectiveMode) { PerspectivePanel(vm); return@Column }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("editor.aspects"),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
            for (p in AspectPreset.entries) {
                val sel = vm.aspect == p
                val swapped = sel && p.w != p.h && p.w > 0 && vm.landscape != (p.w > p.h)
                Chip(aspectLabel(p, swapped), sel, Modifier.testTag("aspect.${p.name}")) {
                    vm.chooseAspect(p); haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToolButton(Icons.Rounded.Rotate90DegreesCw, stringResource(R.string.edit_rotate), Modifier.weight(1f).testTag("editor.rotate")) {
                vm.rotate(); haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            ToolButton(Icons.Rounded.Flip, stringResource(R.string.edit_flip_h), Modifier.weight(1f).testTag("editor.flipH"), onClick = vm::flipHorizontal)
            ToolButton(Icons.Rounded.Flip, stringResource(R.string.edit_flip_v), Modifier.weight(1f).testTag("editor.flipV"), Modifier.rotate(90f), onClick = vm::flipVertical)
            val canToggle = vm.aspect != AspectPreset.FREE && vm.aspect != AspectPreset.ORIGINAL && vm.aspect != AspectPreset.SQUARE
            if (canToggle) ToolButton(Icons.Rounded.ScreenRotationAlt,
                stringResource(if (vm.landscape) R.string.edit_portrait else R.string.edit_landscape), Modifier.weight(1f).testTag("editor.orientation"),
                onClick = vm::toggleOrientation)
            ToolButton(Icons.Rounded.RestartAlt, stringResource(R.string.edit_reset), Modifier.weight(1f).testTag("editor.reset"), onClick = vm::resetTab)
        }
        EditSlider(stringResource(R.string.edit_straighten), vm.recipe.straighten, -45f..45f, signed(vm.recipe.straighten, 1) + "°",
            snap = 0.4f) { v, commit -> vm.straighten((v * 10).roundToInt() / 10f, commit) }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Anpassen

@Composable
internal fun AdjustPanel(vm: EditorViewModel) {
    val a = vm.recipe.adjust
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("adjust.groups"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            for (g in AdjustGroup.entries) Chip(stringResource(g.label), vm.adjustGroup == g, Modifier.testTag("group.${g.name}"),
                dot = if (g == AdjustGroup.MIX) !vm.recipe.colorMix.isNeutral else AdjustTool.of(g).any { it.get(a) != 0f }) {
                vm.adjustGroup = g
                if (g != AdjustGroup.MIX && vm.adjustTool.group != g) vm.adjustTool = AdjustTool.of(g).first()
            }
        }
        if (vm.adjustGroup == AdjustGroup.MIX) {
            MixPanel(vm)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ToolButton(Icons.Rounded.RestartAlt, stringResource(R.string.edit_reset_group, stringResource(AdjustGroup.MIX.label)), Modifier.testTag("editor.reset")) {
                    vm.resetGroup(AdjustGroup.MIX) }
            }
            return@Column
        }
        val tools = AdjustTool.of(vm.adjustGroup).filter { !it.advanced }
        LazyRow(Modifier.fillMaxWidth().testTag("adjust.tools"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(tools, key = { it.name }) { t -> AdjustToolButton(t, vm.adjustTool == t, t.get(a) != 0f, Modifier.width(68.dp)) { vm.adjustTool = t } }
        }
        val t = vm.adjustTool
        AdjustSlider(vm, t)
        // Schärfen: Radius und Schwelle nur, wenn geschärft wird.
        if (t == AdjustTool.SHARPNESS && a.sharpness >= 0.5f) {
            AdjustSlider(vm, AdjustTool.SHARPEN_RADIUS)
            AdjustSlider(vm, AdjustTool.SHARPEN_THRESHOLD)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ToolButton(Icons.Rounded.RestartAlt, stringResource(R.string.edit_reset_group, stringResource(vm.adjustGroup.label)), Modifier.testTag("editor.reset")) {
                vm.resetGroup(vm.adjustGroup)
            }
        }
    }
}

@Composable
private fun AdjustSlider(vm: EditorViewModel, t: AdjustTool) {
    val v = vm.valueOf(t)
    val text = when {
        t == AdjustTool.SHARPEN_RADIUS && v < 0.5f -> stringResource(R.string.edit_auto_value)
        t.bipolar -> signed(v)
        else -> "${v.roundToInt()}"
    }
    EditSlider(stringResource(t.label), v, t.min..t.max, text, Modifier.testTag("slider.${t.name}")) { nv, commit ->
        vm.adjust(t, nv.roundToInt().toFloat(), commit)
    }
}

/** Werkzeug als Kreis mit Symbol, darunter der Name; Punkt = Wert ≠ 0. */
@Composable
internal fun AdjustToolButton(t: AdjustTool, selected: Boolean, changed: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = PM.colors
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.RadioButton, onClick = onClick)
        .semantics(mergeDescendants = true) { this.selected = selected }.testTag("adjust.${t.name}").padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (selected) c.accentFill else c.backgroundTint), contentAlignment = Alignment.Center) {
            Icon(t.icon, null, tint = if (selected) Color.White else c.textPrimary, modifier = Modifier.size(22.dp))
            if (changed) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(7.dp).clip(CircleShape).background(if (selected) Color.White else c.accent))
        }
        Text(stringResource(t.label), style = AppTypography.caption.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) c.accent else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Filter

@Composable
internal fun FilterPanel(vm: EditorViewModel) {
    val c = PM.colors
    val app = LocalApp.current
    val presets by app.presets.items.collectAsState()
    var renaming by remember { mutableStateOf<EditPreset?>(null) }
    var menuFor by remember { mutableStateOf<EditPreset?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        LazyRow(Modifier.fillMaxWidth().testTag("editor.filters"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            items(FilterId.entries, key = { it.name }) { f ->
                val sel = vm.recipe.filter == f
                val name = stringResource(f.label)
                Column(Modifier.width(84.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.RadioButton) { vm.setFilter(f) }
                    .semantics(mergeDescendants = true) { selected = sel; contentDescription = name }.testTag("filter.${f.name}"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    FilterThumb(vm.filterThumbs[f] ?: vm.filterThumb, null, Modifier.size(64.dp)
                        .border(BorderStroke(if (sel) 2.dp else 0.dp, if (sel) c.accent else Color.Transparent), RoundedCornerShape(10.dp)))
                    Text(name, style = AppTypography.caption.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (sel) c.accent else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (vm.recipe.filter != FilterId.NONE) {
            val pct = (vm.recipe.filterIntensity * 100).roundToInt()
            EditSlider(stringResource(R.string.edit_filter_strength), vm.recipe.filterIntensity, 0f..1f,
                NumberFormat.getPercentInstance().format(pct / 100.0), snap = 0f) { v, commit -> vm.filterIntensity(v, commit) }
        }
        // Eigene Vorlagen: nur Einstellungen (Regler, Filter, Farbmischer).
        Text(stringResource(R.string.edit_presets), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("editor.presets"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            // Ein Tipp sichert sofort (fortlaufender Name); Umbenennen über langes Drücken.
            Chip(stringResource(R.string.edit_preset_save), false, Modifier.testTag("preset.save"), icon = Icons.Rounded.Add) { vm.savePreset() }
            for (p in presets) Box {
                Chip(p.name, false, Modifier.testTag("preset.${p.name}"), onLongClick = { menuFor = p }) { vm.applyPreset(p) }
                DropdownMenu(menuFor == p, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.edit_preset_rename)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menuFor = null; renaming = p })
                    DropdownMenuItem(text = { Text(stringResource(R.string.edit_preset_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = { menuFor = null; vm.deletePreset(p) })
                }
            }
        }
    }
    renaming?.let { p -> PresetNameDialog(p.name, onDismiss = { renaming = null }) { name -> renaming = null; vm.renamePreset(p, name) } }
}

@Composable
private fun PresetNameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("preset.dialog"),
        title = { Text(stringResource(R.string.edit_preset_save_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                Text(stringResource(R.string.edit_preset_save_text), style = AppTypography.subheadline)
                OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text(stringResource(R.string.edit_preset_name)) },
                    modifier = Modifier.testTag("preset.name"))
            }
        },
        confirmButton = { TextButton({ onSave(name) }, Modifier.testTag("preset.confirm")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}

// ---------------------------------------------------------------------------------------------------------------------
// Größe

@Composable
internal fun ResizePanel(vm: EditorViewModel) {
    val c = PM.colors
    val base = vm.cropSize ?: return
    val out = vm.outputSize ?: base
    val presets = ResizeRules.presetsFor(base)
    var custom by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(true) }
    val current = when {
        vm.recipe.resizeWidth == null -> 0
        custom -> -1
        else -> presets.firstOrNull { ResizeRules.forLongEdge(base, it) == out } ?: -1
    }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Chip(stringResource(R.string.edit_size_original), current == 0, Modifier.testTag("size.original")) { custom = false; vm.resize(null) }
            for (p in presets) Chip(stringResource(R.string.edit_size_long, p), current == p, Modifier.testTag("size.$p")) {
                custom = false; vm.resize(ResizeRules.forLongEdge(base, p))
            }
            Chip(stringResource(R.string.edit_size_custom), current == -1, Modifier.testTag("size.custom")) { custom = true }
        }
        if (current == -1 || custom) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                SizeField(stringResource(R.string.edit_width), out.width, Modifier.weight(1f).testTag("size.width")) { w ->
                    vm.resize(if (locked) ResizeRules.forWidth(base, w) else ResizeRules.clampToSource(w, out.height, base))
                }
                Box(Modifier.size(AppSpacing.minTouch).clip(CircleShape).clickable(role = Role.Switch) { locked = !locked }
                    .semantics { contentDescription = "" }.testTag("size.lock"), contentAlignment = Alignment.Center) {
                    Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        stringResource(if (locked) R.string.edit_ratio_locked else R.string.edit_ratio_free), tint = if (locked) c.accent else c.textSecondary)
                }
                SizeField(stringResource(R.string.edit_height), out.height, Modifier.weight(1f).testTag("size.height")) { h ->
                    vm.resize(if (locked) ResizeRules.forHeight(base, h) else ResizeRules.clampToSource(out.width, h, base))
                }
            }
        }
        Text(stringResource(R.string.edit_size_result, base.width, base.height, out.width, out.height), style = AppTypography.subheadline,
            color = c.textSecondary, modifier = Modifier.testTag("size.result"))
        if (presets.isEmpty() && !custom) Text(stringResource(R.string.edit_size_small), style = AppTypography.footnote, color = c.textSecondary)
    }
}

@Composable
private fun SizeField(label: String, value: Int, modifier: Modifier, onDone: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf("$value") }
    OutlinedTextField(text, { t -> text = t.filter(Char::isDigit).take(5); text.toIntOrNull()?.let { if (it >= ResizeRules.MIN_EDGE) onDone(it) } },
        modifier, label = { Text(label) }, singleLine = true, suffix = { Text("px") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

// ---------------------------------------------------------------------------------------------------------------------
// Reiterleiste

@Composable
internal fun EditorTabs(tabs: List<EditorTab>, selected: EditorTab, locked: Boolean, onSelect: (EditorTab) -> Unit, modifier: Modifier = Modifier) {
    val c = PM.colors
    BoxWithConstraints(modifier.fillMaxWidth().glassCapsule(elevation = 2.dp).padding(4.dp)) {
        // Passen nicht alle Reiter mit mindestens 64 dp, wird die Leiste waagrecht scrollbar.
        val fits = maxWidth / tabs.size >= 64.dp
        val itemW = if (fits) maxWidth / tabs.size else 72.dp
        Row(if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())) {
            for (t in tabs) {
                val sel = t == selected
                Column(Modifier.width(itemW).heightIn(min = 52.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (sel) c.accent.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(t) }.semantics(mergeDescendants = true) { this.selected = sel }.testTag("tab.${t.name}")
                    .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box {
                        Icon(t.icon, null, tint = if (sel) c.accent else c.textSecondary, modifier = Modifier.size(22.dp))
                        if (locked && t.isPro) Icon(Icons.Rounded.WorkspacePremium, stringResource(R.string.pro_badge), tint = c.accent,
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-4).dp).size(12.dp))
                    }
                    Text(stringResource(t.label), style = AppTypography.caption.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (sel) c.accent else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Ausgabegröße nach dem Ausschnitt, für die Kopfzeile („2048 × 1536“). */
internal fun PxSize.label(): String = "$width × $height"

@Composable
internal fun HapticOnChange(key: Any?) {
    val haptics = LocalHapticFeedback.current
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(key) { if (first) first = false else haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
}
