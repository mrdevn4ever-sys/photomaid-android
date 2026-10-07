package app.photomaid.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

// =====================================================================================================================
// Medien-Editor im Stil der Fotos-App (Nutzerwunsch 2026-10-07): die Bühne bekommt den Platz, die Bedienung ist schmal.
// Kopf in zwei Zeilen (✕ … ✓ / Rückgängig · Titel · Extras), darunter die Bühne, eine Werkzeugzone mit runden Werkzeugen
// und Skalenregler und unten eine Reiterkapsel. Zoomen mit zwei Fingern auf jeder Bühne.
// =====================================================================================================================

/** Ergebnis der kompakten Policy (alles dp; nur Fenster, Schrift, Tastatur und Werkzeugart zählen – nie das Medium). */
@Immutable
data class MediaEditorLayout(
    val mode: EditorMode, val header: Dp, val actions: Dp, val tool: Dp, val dock: Dp,
    val stageWidth: Dp, val stageHeight: Dp, val inspectorWidth: Dp, val keyboard: Boolean,
)

object MediaEditorLayoutPolicy {
    /** Kopf: Zeile 1 (✕ / ✓) + Zeile 2 (Rückgängig · Titel · Extras); im Querformat eine Zeile. */
    fun header(fontScale: Float, side: Boolean): Dp {
        val fs = fontScale.coerceIn(1f, 2f)
        val row = 48.dp + 10.dp * (fs - 1f)
        return if (side) row + 4.dp else row * 2 + 4.dp
    }
    /** Video: schmale Wiedergabezeile direkt unter der Bühne. */
    val actions: Dp = 44.dp
    /** Reiterkapsel: Symbol + Beschriftung, mit Rand. */
    fun dock(fontScale: Float): Dp = 64.dp + 16.dp * (fontScale.coerceIn(1f, 2f) - 1f) + 12.dp
    /** Schmale Werkzeugzone (runde Werkzeuge + Skalenregler). */
    fun compactTool(fontScale: Float): Dp = 152.dp + 60.dp * (fontScale.coerceIn(1f, 2f) - 1f)
    val minStage: Dp = 160.dp

    /**
     * [rich] = Werkzeug mit vielen Einstellungen (Markieren, Retusche, Größe …): höhere Werkzeugzone. Die Hauptwerkzeuge
     * (Anpassen, Filter, Zuschneiden) bleiben schmal – dort gehört der Platz dem Bild.
     */
    fun compute(width: Dp, height: Dp, fontScale: Float, ime: Dp = 0.dp, hasActions: Boolean = false, rich: Boolean = false): MediaEditorLayout {
        val fs = fontScale.coerceIn(1f, 2f)
        val keyboard = ime > 0.dp
        val avail = max(0.dp, height - ime)
        val side = !keyboard && width >= 560.dp && width > height * 1.2f
        if (side) {
            val head = header(fs, side = true)
            val inspector = (width * 0.40f).coerceIn(320.dp, 440.dp)
            val bodyH = avail - head
            val dock = dock(fs)
            val act = if (hasActions) actions else 0.dp
            return MediaEditorLayout(EditorMode.SIDE, head, act, max(0.dp, bodyH - dock), dock, width - inspector,
                max(minStage, bodyH - act), inspector, false)
        }
        val head = header(fs, side = false)
        val act = if (hasActions && !keyboard) actions else 0.dp
        val dock = if (!keyboard) dock(fs) else 0.dp
        val bodyH = avail - head - act - dock
        var tool = if (rich) (avail * 0.30f).coerceIn(184.dp, 280.dp) + 40.dp * (fs - 1f) else compactTool(fs)
        if (keyboard) tool = min(tool, 168.dp + 40.dp * (fs - 1f))
        var stage = bodyH - tool
        if (stage < minStage) { tool = max(96.dp, bodyH - minStage); stage = bodyH - tool }
        tool = min(tool, max(0.dp, bodyH)); stage = max(0.dp, bodyH - tool)
        if (keyboard && stage < minStage) { tool = max(0.dp, bodyH); stage = 0.dp }
        return MediaEditorLayout(EditorMode.STACKED, head, act, tool, dock, width, stage, width, keyboard)
    }
}

/**
 * Gerüst des Foto-/Video-Editors. Kopf: [cancel] und [confirm] (Zeile 1), [leadingTools] · [title]/[subtitle] ·
 * [trailingTools] (Zeile 2). Die Bühne ist ein neutraler Rahmen; ihr Inhalt passt das Medium selbst ein (nie gestreckt).
 */
@Composable
fun MediaEditorScaffold(
    cancel: @Composable () -> Unit,
    confirm: @Composable () -> Unit,
    leadingTools: @Composable RowScope.() -> Unit,
    trailingTools: @Composable RowScope.() -> Unit,
    title: String,
    stage: @Composable BoxScope.() -> Unit,
    tool: @Composable () -> Unit,
    dock: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleTag: String = "editor.status",
    actions: (@Composable RowScope.() -> Unit)? = null,
    rich: Boolean = false,
    toolScrolls: Boolean = true,
    stageTag: String = "media.stage",
) {
    val p = LocalEditorPalette.current
    val density = LocalDensity.current
    val imeOverride = LocalEditorImeOverride.current
    val imePx = WindowInsets.ime.getBottom(density)
    Box(modifier.fillMaxSize().background(p.background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val navBottom = with(density) { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).getBottom(this).toDp() }
            val ime = imeOverride ?: with(density) { imePx.toDp() }
            val bottomInset = if (ime > 0.dp) ime else navBottom
            val layout = MediaEditorLayoutPolicy.compute(maxWidth, if (ime > 0.dp) maxHeight else maxHeight - navBottom, density.fontScale,
                ime = ime, hasActions = actions != null, rich = rich)
            val side = layout.mode == EditorMode.SIDE
            val titleBlock: @Composable (Modifier) -> Unit = { m ->
                // Titel wächst höchstens auf 130 % (bei 200 % Schrift sonst abgeschnitten); der Name steht zusätzlich in der Semantik.
                val cap = (minOf(density.fontScale, 1.3f) / density.fontScale)
                Column(m.padding(horizontal = AppSpacing.xs), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title.uppercase(), style = AppTypography.footnote.copy(fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp,
                        fontSize = AppTypography.footnote.fontSize * cap),
                        color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("editor.title"))
                    if (!subtitle.isNullOrEmpty()) Text(subtitle, style = AppTypography.caption.copy(fontFeatureSettings = "tnum", fontSize = AppTypography.caption.fontSize * cap), color = p.textSecondary.copy(alpha = 0.8f),
                        maxLines = 1, modifier = Modifier.testTag(subtitleTag))
                }
            }
            val toolBox: @Composable (Modifier) -> Unit = { m ->
                Box(m.testTag("editor.toolzone")) {
                    Column(Modifier.fillMaxSize().then(if (toolScrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(start = AppSpacing.m, end = AppSpacing.m, top = AppSpacing.xs, bottom = AppSpacing.s),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) { tool() }
                }
            }
            val stageBox: @Composable (Modifier) -> Unit = { m ->
                Box(m.background(p.stage).clipToBounds().testTag(stageTag), contentAlignment = Alignment.Center, content = stage)
            }
            Column(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
                // Kopf
                Column(Modifier.fillMaxWidth().height(layout.header).padding(horizontal = AppSpacing.s).testTag("editor.header"),
                    verticalArrangement = Arrangement.SpaceEvenly) {
                    if (side) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        cancel(); EditorPill(content = leadingTools)
                        titleBlock(Modifier.weight(1f))
                        EditorPill(content = trailingTools); confirm()
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            cancel(); Box(Modifier.weight(1f)); confirm()
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            EditorPill(content = leadingTools)
                            titleBlock(Modifier.weight(1f))
                            EditorPill(content = trailingTools)
                        }
                    }
                }
                if (side) {
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(layout.stageWidth).fillMaxHeight()) {
                            stageBox(Modifier.fillMaxWidth().height(layout.stageHeight))
                            if (actions != null) MediaActionRow(Modifier.height(layout.actions), actions)
                        }
                        Column(Modifier.width(layout.inspectorWidth).fillMaxHeight().testTag("editor.inspector")) {
                            toolBox(Modifier.fillMaxWidth().height(layout.tool))
                            Box(Modifier.fillMaxWidth().height(layout.dock), contentAlignment = Alignment.Center) { dock() }
                        }
                    }
                } else {
                    stageBox(Modifier.fillMaxWidth().height(layout.stageHeight))
                    if (actions != null && !layout.keyboard) MediaActionRow(Modifier.height(layout.actions), actions)
                    toolBox(Modifier.fillMaxWidth().height(layout.tool))
                    if (!layout.keyboard) Box(Modifier.fillMaxWidth().height(layout.dock), contentAlignment = Alignment.Center) { dock() }
                }
            }
        }
    }
}

@Composable
private fun MediaActionRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = AppSpacing.xs), contentAlignment = Alignment.CenterStart) {
        Row(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth).testTag("editor.actions"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, content = content)
    }
}

/** Dunkle Kapsel für Kopf-Aktionen (wie die Fotos-App). */
@Composable
fun EditorPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val p = LocalEditorPalette.current
    Row(modifier.heightIn(min = AppSpacing.minTouch).clip(CircleShape).background(p.surfaceRaised).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Kopf-Aktion „✕“ bzw. „✓“: eigene Kapsel, 48 dp hoch; [primary] = Akzentfläche, wenn aktiv. */
@Composable
fun EditorPillButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    val p = LocalEditorPalette.current
    val c = PM.colors
    val bg by animateColorAsState(if (primary && enabled) c.accentFill else p.surfaceRaised, tween(160), label = "pill")
    Box(modifier.widthIn(min = 72.dp).heightIn(min = AppSpacing.minTouch).clip(CircleShape).background(bg)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = when { !enabled -> p.textSecondary.copy(alpha = 0.45f); primary -> Color.White; else -> p.text }, modifier = Modifier.size(24.dp))
    }
}

/**
 * Reiterkapsel unten: Symbol + Beschriftung, aktiv = Akzent + Punkt (nicht nur Farbe). Passt sie nicht, scrollt sie und
 * holt den aktiven Eintrag ins Bild.
 */
@Composable
fun EditorCapsuleDock(items: List<DockItem>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalEditorPalette.current
    val c = PM.colors
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = AppSpacing.s, vertical = 6.dp), contentAlignment = Alignment.Center) {
        val outer = maxWidth
        Row(Modifier.widthIn(max = outer).clip(CircleShape).background(p.surface).border(1.dp, p.stroke, CircleShape)
            .horizontalScroll(rememberScrollState()).padding(horizontal = AppSpacing.xs, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (it in items) {
                val sel = it.key == selected
                val bring = remember { BringIntoViewRequester() }
                LaunchedEffect(sel) { if (sel) runCatching { bring.bringIntoView() } }
                val fg by animateColorAsState(if (sel) c.accent else p.text, tween(160), label = "dock")
                Column(Modifier.bringIntoViewRequester(bring).widthIn(min = 56.dp).heightIn(min = 52.dp).clip(RoundedCornerShape(24.dp))
                    .clickable(role = Role.Tab) { onSelect(it.key) }.semantics(mergeDescendants = true) { this.selected = sel }
                    .testTag(it.tag).padding(horizontal = AppSpacing.xs, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                    Box(Modifier.size(4.dp).clip(CircleShape).background(if (sel) c.accent else Color.Transparent))
                    Box {
                        Icon(it.icon, null, tint = fg, modifier = Modifier.size(22.dp))
                        if (it.badge != null) Icon(it.badge, null, tint = c.accent, modifier = Modifier.align(Alignment.TopEnd).padding(start = 14.dp).size(11.dp))
                    }
                    Text(it.label, style = AppTypography.caption.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (sel) c.accent else p.textSecondary, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/**
 * Rundes Werkzeug (Belichtung, Kontrast …): 56 dp Kreis. Ring zeigt die Stärke ([amount] −1…1, Bogen ab 12 Uhr), aktiv
 * = helle Fläche. Name und Wert stehen in der Semantik; sichtbar ist der Name im Titel des Kopfs.
 */
@Composable
fun EditorRoundTool(icon: ImageVector, label: String, selected: Boolean, amount: Float, modifier: Modifier = Modifier,
                    valueText: String? = null, iconModifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalEditorPalette.current
    val c = PM.colors
    val bg by animateColorAsState(if (selected) p.text.copy(alpha = 0.16f) else Color.Transparent, tween(160), label = "round")
    Box(modifier.size(60.dp).clip(CircleShape).clickable(role = Role.RadioButton, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = label; this.selected = selected; if (valueText != null) stateDescription = valueText },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(56.dp)) {
            val sw = 1.5.dp.toPx()
            drawCircle(bg, radius = size.minDimension / 2f)
            drawCircle(if (selected) p.text.copy(alpha = 0.55f) else p.stroke, radius = size.minDimension / 2f - sw / 2f, style = Stroke(sw))
            val a = amount.coerceIn(-1f, 1f)
            if (abs(a) > 0.001f) drawArc(c.accent, startAngle = -90f, sweepAngle = 360f * a, useCenter = false,
                topLeft = Offset(sw, sw), size = androidx.compose.ui.geometry.Size(size.width - 2 * sw, size.height - 2 * sw),
                style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        Icon(icon, null, tint = if (selected) p.text else p.text.copy(alpha = 0.85f), modifier = iconModifier.size(24.dp))
    }
}

/**
 * Skalenregler (wie das Rad der Fotos-App): Striche laufen unter einer festen Mittelmarke. Ziehen ändert den Wert,
 * Einrasten bei 0 (Haptik), Antippen des Werts = 0. Rückgängig-Schritt erst beim Loslassen. TalkBack: Regler mit Wert.
 */
@Composable
fun EditorRuler(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, modifier: Modifier = Modifier,
                snap: Float = 1.5f, step: Float? = null, onChange: (Float, Boolean) -> Unit) {
    val p = LocalEditorPalette.current
    val c = PM.colors
    val haptics = LocalHapticFeedback.current
    val bipolar = range.start < 0f
    // Strichabstand: etwa 40 Striche über den ganzen Bereich, auf „runde“ Werte (1, 2, 2,5, 5 × 10ⁿ).
    @Suppress("NAME_SHADOWING") val step = step ?: niceStep((range.endInclusive - range.start) / 40f)
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    var live by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    val shown = if (dragging) live else value
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Wert nur anzeigen; zurück auf 0: Doppeltipp auf die Skala (TalkBack: Wert direkt setzen).
        Box(Modifier.heightIn(min = 20.dp).padding(horizontal = AppSpacing.s), contentAlignment = Alignment.Center) {
            Text(valueText, style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = if (value == 0f) p.textSecondary else c.accent, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.testTag("editor.value"))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(AppSpacing.minTouch)) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
            // Ganzer Bereich ≈ 1,6 Bildbreiten: fein genug, aber ohne endloses Wischen.
            val pxPerUnit = widthPx * 1.6f / (range.endInclusive - range.start)
            Canvas(Modifier.fillMaxSize().testTag("editor.slider")
                .semantics {
                    contentDescription = label; stateDescription = valueText
                    progressBarRangeInfo = ProgressBarRangeInfo(current, range)
                    setProgress { v -> change(v.coerceIn(range), true); true }
                }
                .pointerInput(range) { detectTapGestures(onDoubleTap = { change(0f.coerceIn(range), true) }) }
                .pointerInput(range, pxPerUnit) {
                    detectHorizontalDragGestures(
                        onDragStart = { live = current; dragging = true },
                        onDragEnd = { dragging = false; change(live, true) },
                        onDragCancel = { dragging = false; change(live, true) },
                    ) { ch, dx ->
                        ch.consume()
                        var nv = (live - dx / pxPerUnit).coerceIn(range)
                        if (bipolar && abs(nv) < snap) nv = 0f
                        val before = floor(live / (step * 5)); val after = floor(nv / (step * 5))
                        if ((nv == 0f && live != 0f) || before != after) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        live = nv
                        change(nv, false)
                    }
                }) {
                val cx = size.width / 2f
                val first = (floor(range.start / step) * step)
                var t = first
                while (t <= range.endInclusive + 0.001f) {
                    val x = cx + (t - shown) * pxPerUnit
                    if (x in -2f..size.width + 2f) {
                        val major = abs(t % (step * 5)) < 0.001f
                        val zero = abs(t) < 0.001f
                        val fade = 1f - (abs(x - cx) / cx).coerceIn(0f, 1f) * 0.85f
                        val h = size.height * when { zero -> 0.7f; major -> 0.5f; else -> 0.32f }
                        drawLine(p.text.copy(alpha = (if (zero || major) 0.75f else 0.4f) * fade), Offset(x, (size.height - h) / 2f), Offset(x, (size.height + h) / 2f),
                            if (zero) 2.dp.toPx() else 1.dp.toPx(), StrokeCap.Round)
                    }
                    t += step
                }
                // Feste Mittelmarke.
                drawLine(c.accent, Offset(cx, 0f), Offset(cx, size.height), 2.5.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

internal fun niceStep(raw: Float): Float {
    if (raw <= 0f) return 1f
    var mag = 1f
    while (mag * 10f <= raw) mag *= 10f
    while (mag > raw) mag /= 10f
    return listOf(1f, 2f, 2.5f, 5f, 10f).map { it * mag }.first { it >= raw * 0.999f }
}

/** Zoomfaktor der Bühne in der Semantik (für Tests; die Bühne selbst bleibt ohne Bedienelemente). */
val StageZoomScaleKey = androidx.compose.ui.semantics.SemanticsPropertyKey<Float>("StageZoomScale")

/** Zoomzustand einer Bühne (nur Ansicht – nie Teil des Rezepts). */
@Stable
class StageZoom {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    val zoomed: Boolean get() = scale > 1.01f
    fun reset() { scale = 1f; offset = Offset.Zero }
    internal fun clamp(o: Offset, s: Float, size: IntSize): Offset {
        val mx = size.width * (s - 1f) / 2f; val my = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
}

/**
 * Zoombare Bühne: zwei Finger zoomen und verschieben (bis [maxScale]); ein Finger bleibt beim Werkzeug darunter
 * (Zuschneiden, Zeichnen, Pinsel …), dessen Koordinaten automatisch mitskaliert sind. Gleichmäßig skaliert – nie verzerrt.
 */
@Composable
fun ZoomableStage(zoom: StageZoom, modifier: Modifier = Modifier, enabled: Boolean = true, maxScale: Float = 5f, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clipToBounds().pointerInput(enabled, maxScale) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pinched = false
            do {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                if (ev.changes.count { it.pressed } >= 2) {
                    pinched = true
                    val z = ev.calculateZoom(); val pan = ev.calculatePan()
                    val ns = (zoom.scale * z).coerceIn(1f, maxScale)
                    zoom.offset = if (ns <= 1.001f) Offset.Zero else zoom.clamp(zoom.offset + pan, ns, size)
                    zoom.scale = ns
                    // Werkzeug darunter sieht die Zwei-Finger-Geste nicht (kein Strich, kein Verschieben).
                    ev.changes.forEach { it.consume() }
                } else if (pinched) ev.changes.forEach { it.consume() } // bis alle Finger los sind: kein Rest-Ziehen
            } while (ev.changes.any { it.pressed })
        }
    }.semantics { set(StageZoomScaleKey, zoom.scale) }.testTag("editor.zoomable"), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.scale; scaleY = zoom.scale; translationX = zoom.offset.x; translationY = zoom.offset.y },
            contentAlignment = Alignment.Center, content = content)
    }
}

/** Zoom zurück auf „ganzes Bild“ per Doppeltipp – nur für Bühnen ohne eigene Tippgeste. */
fun Modifier.doubleTapToFit(zoom: StageZoom): Modifier = this.pointerInput(zoom) {
    detectTapGestures(onDoubleTap = { pos: Offset ->
        if (zoom.zoomed) zoom.reset() else {
            val target = 2.5f
            val c = Offset(size.width / 2f, size.height / 2f)
            zoom.offset = zoom.clamp((c - pos) * (target - 1f), target, size); zoom.scale = target
        }
    })
}

/** Dunkler Editor: helle Symbole in Status- und Navigationsleiste, beim Verlassen wieder wie vorher. */
@Composable
fun DarkSystemBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    if (view.isInEditMode) return
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val ctl = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val status = ctl?.isAppearanceLightStatusBars; val nav = ctl?.isAppearanceLightNavigationBars
        ctl?.isAppearanceLightStatusBars = false; ctl?.isAppearanceLightNavigationBars = false
        onDispose { if (status != null) ctl.isAppearanceLightStatusBars = status; if (nav != null) ctl.isAppearanceLightNavigationBars = nav }
    }
}
