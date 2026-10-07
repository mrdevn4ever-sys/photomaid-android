package app.photomaid.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.edit.ColorBand
import app.photomaid.core.edit.Mask
import app.photomaid.core.edit.NormPoint
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.PM
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------------------------------
// Farbmischer

/** Anzeigefarbe je Farbbereich (nur für die Auswahl). */
/** Spur des Farbmischer-Reglers: Farbton = Nachbarfarben, Sättigung = Grau → Farbe, Helligkeit = dunkel → hell. */
internal fun mixBrush(b: ColorBand, f: MixField): androidx.compose.ui.graphics.Brush {
    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(b.swatch.toArgb(), hsv)
    fun hue(h: Float, s: Float = 0.85f, v: Float = 0.95f) = Color(android.graphics.Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, v)))
    val colors = when (f) {
        MixField.HUE -> listOf(hue(hsv[0] - 40f), hue(hsv[0]), hue(hsv[0] + 40f))
        MixField.SATURATION -> listOf(hue(hsv[0], 0f, 0.7f), hue(hsv[0], 0.45f, 0.85f), hue(hsv[0], 1f, 0.95f))
        MixField.LUMINANCE -> listOf(hue(hsv[0], 0.9f, 0.25f), hue(hsv[0], 0.85f, 0.8f), hue(hsv[0], 0.35f, 1f))
    }
    return androidx.compose.ui.graphics.Brush.horizontalGradient(colors)
}

internal val ColorBand.swatch: Color get() = when (this) {
    ColorBand.RED -> Color(0xFFE5484D); ColorBand.ORANGE -> Color(0xFFF76B15); ColorBand.YELLOW -> Color(0xFFFFC53D)
    ColorBand.GREEN -> Color(0xFF30A46C); ColorBand.AQUA -> Color(0xFF12A594); ColorBand.BLUE -> Color(0xFF3E63DD)
    ColorBand.PURPLE -> Color(0xFF8E4EC6); ColorBand.MAGENTA -> Color(0xFFD6409F)
}

private val ColorBand.label: Int get() = when (this) {
    ColorBand.RED -> R.string.edit_band_red; ColorBand.ORANGE -> R.string.edit_band_orange; ColorBand.YELLOW -> R.string.edit_band_yellow
    ColorBand.GREEN -> R.string.edit_band_green; ColorBand.AQUA -> R.string.edit_band_aqua; ColorBand.BLUE -> R.string.edit_band_blue
    ColorBand.PURPLE -> R.string.edit_band_purple; ColorBand.MAGENTA -> R.string.edit_band_magenta
}

/** Selektive Farben: Farbbereich wählen, dann Farbton, Sättigung und Helligkeit nur dieses Bereichs. */
@Composable
internal fun MixPanel(vm: EditorViewModel) {
    val c = PM.colors
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("mix.bands"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            for (b in ColorBand.entries) {
                val sel = vm.mixBand == b
                val changed = !vm.recipe.colorMix[b].isNeutral
                val name = stringResource(b.label)
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.RadioButton) { vm.mixBand = b }
                    .semantics { selected = sel; contentDescription = name }.testTag("band.${b.name}"), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (sel) 34.dp else 28.dp).clip(CircleShape).background(b.swatch)
                        .border(if (sel) 3.dp else 0.dp, if (sel) c.textPrimary else Color.Transparent, CircleShape))
                    if (changed) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(8.dp).clip(CircleShape).background(c.accent).border(1.dp, Color.White, CircleShape))
                }
            }
        }
        Text(stringResource(vm.mixBand.label), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
        for (f in MixField.entries) {
            val v = vm.mixValue(vm.mixBand, f)
            EditSlider(stringResource(f.label), v, -100f..100f, signed(v), Modifier.testTag("mix.${f.name}"), trackBrush = mixBrush(vm.mixBand, f)) { nv, commit ->
                vm.mix(f, nv.roundToInt().toFloat(), commit) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Perspektive (im Reiter „Zuschneiden“)

@Composable
internal fun PerspectivePanel(vm: EditorViewModel) {
    val c = PM.colors
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Text(stringResource(R.string.edit_persp_hint), style = AppTypography.footnote, color = c.textSecondary)
        EditSlider(stringResource(R.string.edit_persp_vertical), vm.keyV, -100f..100f, signed(vm.keyV), Modifier.testTag("persp.v")) { v, commit ->
            vm.keystone(v.roundToInt().toFloat(), vm.keyH, commit) }
        EditSlider(stringResource(R.string.edit_persp_horizontal), vm.keyH, -100f..100f, signed(vm.keyH), Modifier.testTag("persp.h")) { v, commit ->
            vm.keystone(vm.keyV, v.roundToInt().toFloat(), commit) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ToolButton(Icons.Rounded.RestartAlt, stringResource(R.string.edit_persp_reset), Modifier.testTag("persp.reset"), onClick = vm::resetPerspective)
        }
    }
}

/** Vier Ecken auf dem ganzen Rahmen ziehen; Raster im Viereck; Lupe an der gezogenen Ecke. */
@Composable
internal fun PerspectiveStage(vm: EditorViewModel) {
    val frame = vm.framePreview
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val vmNow by rememberUpdatedState(vm)
    var active by remember { mutableStateOf<Int?>(null) }
    val label = stringResource(R.string.edit_persp_area)
    val cornerNames = listOf(R.string.edit_persp_tl, R.string.edit_persp_tr, R.string.edit_persp_br, R.string.edit_persp_bl).map { stringResource(it) }
    val inward = stringResource(R.string.edit_persp_in); val outward = stringResource(R.string.edit_persp_out)
    BoxWithConstraints(Modifier.fillMaxSize().padding(AppSpacing.l)) {
        if (frame == null) { CircularProgressIndicator(Modifier.align(Alignment.Center)); return@BoxWithConstraints }
        val box = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val disp = fitRect(box, frame.width.toFloat() / frame.height)
        val img = remember(frame) { frame.asImageBitmap() }
        val touch = with(density) { 32.dp.toPx() }
        fun toScreen(p: NormPoint) = Offset(disp.left + p.x * disp.width, disp.top + p.y * disp.height)
        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(Modifier.fillMaxSize().testTag("persp.stage").semantics {
            contentDescription = label
            customActions = cornerNames.flatMapIndexed { i, n ->
                listOf(CustomAccessibilityAction("$n · $inward") { vmNow.nudgeCorner(i, true); true },
                    CustomAccessibilityAction("$n · $outward") { vmNow.nudgeCorner(i, false); true })
            }
        }.pointerInput(disp) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val q = vmNow.quad.corners
                val i = q.indices.minByOrNull { hypot(toScreen(q[it]).x - down.position.x, toScreen(q[it]).y - down.position.y) } ?: return@awaitEachGesture
                if (hypot(toScreen(q[i]).x - down.position.x, toScreen(q[i]).y - down.position.y) > touch * 1.4f) return@awaitEachGesture
                down.consume(); active = i
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                var pos = toScreen(q[i])
                while (true) {
                    val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    pos += ch.positionChange(); ch.consume()
                    vmNow.moveCorner(i, (pos.x - disp.left) / disp.width, (pos.y - disp.top) / disp.height, false)
                }
                active = null
                vmNow.commit()
            }
        }) {
            val q = vm.quad.corners.map(::toScreen)
            // Außen abdunkeln
            val outer = Path().apply { addRect(disp) }
            val inner = Path().apply { moveTo(q[0].x, q[0].y); for (k in 1..3) lineTo(q[k].x, q[k].y); close() }
            clipPath(inner, androidx.compose.ui.graphics.ClipOp.Difference) { drawPath(outer, Color.Black.copy(alpha = 0.5f)) }
            // Raster (Kanten in Drittel geteilt)
            for (k in 1..2) {
                val t = k / 3f
                drawLine(Color.White.copy(alpha = 0.55f), lerp(q[0], q[1], t), lerp(q[3], q[2], t), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.55f), lerp(q[0], q[3], t), lerp(q[1], q[2], t), 1.dp.toPx())
            }
            drawPath(inner, Color.White, style = Stroke(1.5.dp.toPx()))
            q.forEachIndexed { k, p ->
                drawCircle(Color.Black.copy(alpha = 0.25f), 13.dp.toPx(), p)
                drawCircle(Color.White, 11.dp.toPx(), p, style = Stroke(2.5.dp.toPx()))
                if (k == active) drawCircle(Color.White.copy(alpha = 0.35f), 11.dp.toPx(), p)
            }
            active?.let { k -> drawLoupe(img, q[k], disp, frame.width, frame.height, box) }
        }
    }
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/** Lupe (2,5×) über der gezogenen Ecke – oben gegenüber, damit der Finger sie nicht verdeckt. */
private fun DrawScope.drawLoupe(img: androidx.compose.ui.graphics.ImageBitmap, at: Offset, disp: Rect, iw: Int, ih: Int, box: Size) {
    val r = 52.dp.toPx(); val zoom = 2.5f
    val center = Offset(if (at.x < box.width / 2) box.width - r - 8.dp.toPx() else r + 8.dp.toPx(), r + 8.dp.toPx())
    val kx = iw / disp.width; val ky = ih / disp.height
    val srcW = (2 * r / zoom * kx).roundToInt().coerceAtLeast(2); val srcH = (2 * r / zoom * ky).roundToInt().coerceAtLeast(2)
    val sx = ((at.x - disp.left) * kx - srcW / 2).roundToInt().coerceIn(0, max(0, iw - srcW))
    val sy = ((at.y - disp.top) * ky - srcH / 2).roundToInt().coerceIn(0, max(0, ih - srcH))
    val circle = Path().apply { addOval(Rect(center, r)) }
    drawCircle(Color.Black.copy(alpha = 0.3f), r + 3.dp.toPx(), center)
    clipPath(circle) {
        drawImage(img, IntOffset(sx, sy), IntSize(min(srcW, iw), min(srcH, ih)),
            IntOffset((center.x - r).roundToInt(), (center.y - r).roundToInt()), IntSize((2 * r).roundToInt(), (2 * r).roundToInt()))
        // Fadenkreuz an der Stelle der Ecke
        val px = center.x - r + ((at.x - disp.left) * kx - sx) / srcW * 2 * r
        val py = center.y - r + ((at.y - disp.top) * ky - sy) / srcH * 2 * r
        drawLine(Color.White, Offset(px - 10.dp.toPx(), py), Offset(px + 10.dp.toPx(), py), 1.5.dp.toPx())
        drawLine(Color.White, Offset(px, py - 10.dp.toPx()), Offset(px, py + 10.dp.toPx()), 1.5.dp.toPx())
    }
    drawCircle(Color.White, r, center, style = Stroke(2.dp.toPx()))
}

// ---------------------------------------------------------------------------------------------------------------------
// Retusche: Bereiche und Rote Augen

private val Mask.kind: RetouchTool get() = when (this) { is Mask.Radial -> RetouchTool.RADIAL; is Mask.Linear -> RetouchTool.LINEAR; is Mask.Brush -> RetouchTool.BRUSH }

/** Griff einer Maske, der gerade gezogen wird. */
private enum class MaskHandle { CENTER, RX, RY, START, END, LINE }

/**
 * Bild mit Masken-Griffen. Ein Finger: Griffe ziehen, malen (Pinsel), tippen (neuer Bereich, Rote Augen).
 * Zwei Finger: zoomen und verschieben (für kleine Stellen wie Augen).
 */
@Composable
internal fun RetouchStage(vm: EditorViewModel) {
    val image = vm.baseResult
    val density = LocalDensity.current
    val vmNow by rememberUpdatedState(vm)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    val label = stringResource(R.string.ret_stage)
    val grow = stringResource(R.string.ret_grow); val shrink = stringResource(R.string.ret_shrink)
    BoxWithConstraints(Modifier.fillMaxSize().padding(AppSpacing.m).clipToBounds()) {
        if (image == null) { CircularProgressIndicator(Modifier.align(Alignment.Center)); return@BoxWithConstraints }
        val box = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val disp = fitRect(box, image.width.toFloat() / image.height)
        val img = remember(image) { image.asImageBitmap() }
        val touch = with(density) { 28.dp.toPx() }
        // Äußere Ebene: zwei Finger = Zoom/Verschieben (ein Finger geht an die Werkzeuge darunter).
        Box(Modifier.fillMaxSize().pointerInput(disp) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val ev = awaitPointerEvent()
                    if (ev.changes.count { it.pressed } >= 2) {
                        val ns = (scale * ev.calculateZoom()).coerceIn(1f, 6f)
                        val mx = disp.width * (ns - 1f) / 2f; val my = disp.height * (ns - 1f) / 2f
                        offset = if (ns <= 1.001f) Offset.Zero else (offset + ev.calculatePan()).let { Offset(it.x.coerceIn(-mx, mx), it.y.coerceIn(-my, my)) }
                        scale = ns
                        ev.changes.forEach { it.consume() }
                    }
                } while (ev.changes.any { it.pressed })
            }
        }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }) {
            Image(img, null, Modifier.fillMaxSize().testTag("retouch.image"), contentScale = ContentScale.Fit)
            vm.maskOverlay?.let { o -> val ov = remember(o) { o.asImageBitmap() }; Image(ov, null, Modifier.fillMaxSize().testTag("retouch.mask"), contentScale = ContentScale.Fit) }
            fun toScreen(p: NormPoint) = Offset(disp.left + p.x * disp.width, disp.top + p.y * disp.height)
            fun toNorm(o: Offset) = NormPoint(((o.x - disp.left) / disp.width).coerceIn(0f, 1f), ((o.y - disp.top) / disp.height).coerceIn(0f, 1f))
            Canvas(Modifier.fillMaxSize().testTag("retouch.canvas").semantics {
                contentDescription = label
                stateDescription = vmNow.selectedLocal?.let { "${it + 1}" } ?: ""
                customActions = listOf(
                    CustomAccessibilityAction(grow) { vmNow.resizeSelected(1.15f); true },
                    CustomAccessibilityAction(shrink) { vmNow.resizeSelected(1f / 1.15f); true })
            }.pointerInput(disp) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val v = vmNow
                    val t = touch / scale
                    val p0 = down.position
                    val tool = v.retouchTool
                    // Griff der gewählten Maske?
                    val sel = v.currentLocal?.mask
                    val handle: MaskHandle? = when (sel) {
                        is Mask.Radial -> {
                            val cpt = toScreen(sel.center)
                            val rxp = Offset(cpt.x + sel.rx * disp.width, cpt.y); val ryp = Offset(cpt.x, cpt.y - sel.ry * disp.height)
                            when {
                                (p0 - rxp).getDistance() <= t -> MaskHandle.RX
                                (p0 - ryp).getDistance() <= t -> MaskHandle.RY
                                (p0 - cpt).getDistance() <= max(t, min(sel.rx * disp.width, sel.ry * disp.height)) -> MaskHandle.CENTER
                                else -> null
                            }
                        }
                        is Mask.Linear -> {
                            val a = toScreen(sel.start); val b = toScreen(sel.end)
                            when {
                                (p0 - a).getDistance() <= t -> MaskHandle.START
                                (p0 - b).getDistance() <= t -> MaskHandle.END
                                (p0 - lerp(a, b, 0.5f)).getDistance() <= t -> MaskHandle.LINE
                                else -> null
                            }
                        }
                        else -> null
                    }
                    var moved = false
                    val points = mutableListOf(toNorm(p0))
                    var last = p0
                    val painting = handle == null && tool == RetouchTool.BRUSH && (sel == null || sel is Mask.Brush)
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } >= 2) { if (painting && moved) v.brush(points, true); return@awaitEachGesture }
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if ((ch.position - p0).getDistance() > viewConfiguration.touchSlop / scale) moved = true
                        if (!moved) continue
                        ch.consume(); dragging = true
                        val n = toNorm(ch.position)
                        when {
                            handle != null && sel is Mask.Radial -> v.updateMask(v.selectedLocal!!, when (handle) {
                                MaskHandle.RX -> sel.copy(rx = abs(n.x - sel.center.x).coerceIn(0.02f, 1.5f))
                                MaskHandle.RY -> sel.copy(ry = abs(n.y - sel.center.y).coerceIn(0.02f, 1.5f))
                                else -> { val d = ch.positionChange(); sel.copy(center = NormPoint((v.radialCenter().x + d.x / disp.width).coerceIn(0f, 1f), (v.radialCenter().y + d.y / disp.height).coerceIn(0f, 1f))) }
                            }, false)
                            handle != null && sel is Mask.Linear -> {
                                val cur = v.currentLocal?.mask as? Mask.Linear ?: sel
                                val d = ch.positionChange(); val dx = d.x / disp.width; val dy = d.y / disp.height
                                v.updateMask(v.selectedLocal!!, when (handle) {
                                    MaskHandle.START -> cur.copy(start = n)
                                    MaskHandle.END -> cur.copy(end = n)
                                    else -> cur.copy(start = NormPoint(cur.start.x + dx, cur.start.y + dy), end = NormPoint(cur.end.x + dx, cur.end.y + dy))
                                }, false)
                            }
                            painting -> if ((ch.position - last).getDistance() > 2f) { last = ch.position; points += n; v.brush(points.toList(), false) }
                        }
                    }
                    dragging = false
                    when {
                        moved && painting -> v.brush(points.toList(), true)
                        moved && handle != null -> v.commit()
                        !moved -> v.tapRetouch(toNorm(p0), toScreen = { toScreen(it) }, at0 = p0, touch = t)
                    }
                }
            }) {
                val sw = 2.dp.toPx() / scale
                // Rote Augen: Kreise
                for (s in vm.recipe.redEyes) {
                    val cpt = toScreen(s.center); val rr = s.radius * max(disp.width, disp.height)
                    drawCircle(Color.White, rr, cpt, style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f / scale, 4f / scale))))
                }
                vm.recipe.locals.forEachIndexed { i, l ->
                    val selected = i == vm.selectedLocal
                    when (val m = l.mask) {
                        is Mask.Radial -> {
                            val cpt = toScreen(m.center)
                            if (selected) {
                                val rw = m.rx * disp.width; val rh = m.ry * disp.height
                                drawOval(Color.White, Offset(cpt.x - rw, cpt.y - rh), Size(2 * rw, 2 * rh), style = Stroke(sw))
                                val f = 1f - m.feather
                                drawOval(Color.White.copy(alpha = 0.6f), Offset(cpt.x - rw * f, cpt.y - rh * f), Size(2 * rw * f, 2 * rh * f),
                                    style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f / scale, 6f / scale))))
                                handleDot(Offset(cpt.x + rw, cpt.y), scale); handleDot(Offset(cpt.x, cpt.y - rh), scale)
                            }
                            pin(cpt, selected, scale)
                        }
                        is Mask.Linear -> {
                            val a = toScreen(m.start); val b = toScreen(m.end)
                            if (selected) {
                                val dir = b - a; val len = dir.getDistance().coerceAtLeast(1f)
                                val nx = -dir.y / len * max(disp.width, disp.height); val ny = dir.x / len * max(disp.width, disp.height)
                                for ((pt, alpha) in listOf(a to 1f, lerp(a, b, 0.5f) to 0.5f, b to 1f))
                                    drawLine(Color.White.copy(alpha = alpha), Offset(pt.x - nx, pt.y - ny), Offset(pt.x + nx, pt.y + ny), sw,
                                        pathEffect = if (pt == b) PathEffect.dashPathEffect(floatArrayOf(8f / scale, 6f / scale)) else null)
                                handleDot(a, scale); handleDot(b, scale)
                            }
                            pin(lerp(a, b, 0.5f), selected, scale)
                        }
                        is Mask.Brush -> m.strokes.lastOrNull()?.points?.lastOrNull()?.let { pin(toScreen(it), selected, scale) }
                    }
                }
            }
        }
        if (scale > 1.01f) Text("${(scale * 100).roundToInt()} %", Modifier.align(Alignment.BottomStart).padding(4.dp).testTag("retouch.zoom"),
            style = AppTypography.footnote, color = PM.colors.textPrimary)
    }
}

private fun DrawScope.handleDot(p: Offset, scale: Float) {
    drawCircle(Color.Black.copy(alpha = 0.25f), 9.dp.toPx() / scale, p)
    drawCircle(Color.White, 7.dp.toPx() / scale, p)
}

private fun DrawScope.pin(p: Offset, selected: Boolean, scale: Float) {
    drawCircle(Color.White, (if (selected) 9.dp else 7.dp).toPx() / scale, p)
    drawCircle(if (selected) Color(0xFF0A84FF) else Color.Black.copy(alpha = 0.55f), (if (selected) 6.dp else 4.dp).toPx() / scale, p)
}

@Composable
internal fun RetouchPanel(vm: EditorViewModel) {
    val c = PM.colors
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("retouch.tools"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            for (t in RetouchTool.entries) Chip(stringResource(t.label), vm.retouchTool == t, Modifier.testTag("rtool.${t.name}")) { vm.chooseRetouchTool(t) }
        }
        if (vm.retouchTool == RetouchTool.RED_EYE) {
            Text(stringResource(R.string.ret_red_eye_hint), style = AppTypography.footnote, color = c.textSecondary, modifier = Modifier.testTag("redeye.hint"))
            val pct = vm.redEyeSize * 100f
            EditSlider(stringResource(R.string.ret_size), pct, 1f..8f, "${(pct * 10).roundToInt() / 10f}", Modifier.testTag("redeye.size"), snap = 0f) { v, _ -> vm.redEyeSize = v / 100f }
            if (vm.recipe.redEyes.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ToolButton(Icons.Rounded.Undo, stringResource(R.string.ret_red_eye_undo), Modifier.testTag("redeye.undo"), onClick = vm::removeLastRedEye)
            }
            return@Column
        }
        // Vorhandene Bereiche + Neu
        val locals = vm.recipe.locals
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("retouch.locals"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            locals.forEachIndexed { i, l ->
                Chip("${stringResource(l.mask.kind.label)} ${i + 1}", vm.selectedLocal == i, Modifier.testTag("local.$i"), dot = !l.adjust.pointNeutral) { vm.selectLocal(i) }
            }
            if (locals.size < EditorViewModel.MAX_LOCALS) Chip(stringResource(R.string.ret_new), false, Modifier.testTag("local.add"), icon = Icons.Rounded.Add) {
                vm.selectedLocal = null
                if (vm.retouchTool != RetouchTool.BRUSH) vm.addLocal(vm.retouchTool)
            }
        }
        val cur = vm.currentLocal
        if (cur == null) {
            Text(stringResource(if (vm.retouchTool == RetouchTool.BRUSH) R.string.ret_hint_brush else R.string.ret_hint_tap), style = AppTypography.footnote,
                color = c.textSecondary, modifier = Modifier.testTag("retouch.hint"))
            if (vm.retouchTool == RetouchTool.BRUSH) BrushControls(vm)
            return@Column
        }
        val tools = AdjustTool.entries.filter { it.local }
        LazyRow(Modifier.fillMaxWidth().testTag("local.tools"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(tools, key = { it.name }) { t -> AdjustToolButton(t, vm.localTool == t, t.get(cur.adjust) != 0f, Modifier) { vm.localTool = t } }
        }
        val lv = vm.localValue(vm.localTool)
        EditSlider(stringResource(vm.localTool.label), lv, -100f..100f, signed(lv), Modifier.testTag("local.slider")) { v, commit -> vm.adjustLocal(vm.localTool, v.roundToInt().toFloat(), commit) }
        when (val m = cur.mask) {
            is Mask.Radial -> EditSlider(stringResource(R.string.ret_feather), m.feather * 100f, 0f..100f, "${(m.feather * 100).roundToInt()}", Modifier.testTag("local.feather"), snap = 0f) { v, commit ->
                vm.updateMask(vm.selectedLocal ?: return@EditSlider, m.copy(feather = v / 100f), commit) }
            is Mask.Brush -> BrushControls(vm)
            else -> Unit
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Chip(stringResource(R.string.ret_show_mask), vm.showMask, Modifier.testTag("local.showmask"), icon = Icons.Rounded.Visibility) { vm.showMask = !vm.showMask }
            if (cur.mask is Mask.Radial) Chip(stringResource(R.string.ret_invert), (cur.mask as Mask.Radial).invert, Modifier.testTag("local.invert"), icon = Icons.Rounded.Flip) {
                val m = cur.mask as Mask.Radial; vm.updateMask(vm.selectedLocal ?: return@Chip, m.copy(invert = !m.invert), true) }
            Box(Modifier.weight(1f))
            ToolButton(Icons.Rounded.Delete, stringResource(R.string.ret_delete), Modifier.testTag("local.delete"), onClick = vm::removeLocal)
        }
    }
}

@Composable
private fun BrushControls(vm: EditorViewModel) {
    val pct = vm.brushSize * 100f
    EditSlider(stringResource(R.string.ret_size), pct, 1f..20f, "${pct.roundToInt()}", Modifier.testTag("brush.size"), snap = 0f) { v, _ -> vm.brushSize = v / 100f }
    Chip(stringResource(R.string.ret_erase), vm.brushErase, Modifier.testTag("brush.erase"), icon = Icons.Rounded.AutoFixOff) { vm.brushErase = !vm.brushErase }
}
