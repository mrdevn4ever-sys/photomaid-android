package app.photomaid.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.toggleableState
import app.photomaid.core.edit.Histogram
import app.photomaid.design.glassRect
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.edit.CropMath
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.NormRect
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.PM
import app.photomaid.design.glassCapsule
import kotlin.math.abs
import kotlin.math.hypot

/** Größtes Rechteck mit [ratio] (B/H), das in [box] passt, mittig. */
internal fun fitRect(box: Size, ratio: Float): Rect {
    val w: Float; val h: Float
    if (box.width / box.height > ratio) { h = box.height; w = h * ratio } else { w = box.width; h = w / ratio }
    return Rect(Offset((box.width - w) / 2, (box.height - h) / 2), Size(w, h))
}

/**
 * Bildbereich des Editors. Zuschneiden: ganzer (gedrehter, begradigter) Rahmen mit Ausschnitt, Griffen und Raster.
 * Sonst: das Ergebnis; Finger halten zeigt das Original (Vorher/Nachher).
 */
@Composable
internal fun EditorStage(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val c = PM.colors
    val src = vm.source
    Box(modifier.background(c.backgroundTint.copy(alpha = 0.5f)).testTag("editor.stage"), contentAlignment = Alignment.Center) {
        when {
            src == null -> CircularProgressIndicator()
            vm.tab == EditorTab.CROP && vm.perspectiveMode -> PerspectiveStage(vm)
            vm.tab == EditorTab.CROP -> CropStage(vm)
            vm.tab == EditorTab.RETOUCH -> RetouchStage(vm)
            vm.tab == EditorTab.MARKUP -> MarkupStage(vm.markup, vm.resultPreview, vm.baseResult, vm.recipe.frame, Modifier.fillMaxSize())
            else -> ResultStage(vm, src.preview)
        }
    }
}

/**
 * Ergebnis mit Zoom: zwei Finger zoomen/verschieben, Doppeltipp = 100 % (1 Bildpunkt = 1 Bildschirmpunkt) bzw. zurück,
 * Finger halten (ohne Bewegung) = Original. Ab etwa 115 % wird der sichtbare Ausschnitt in voller Qualität nachgerechnet.
 */
@Composable
private fun ResultStage(vm: EditorViewModel, original: Bitmap) {
    var showOriginal by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var lastTap by remember { mutableStateOf(0L) }
    val result = vm.resultPreview
    val shown = if (showOriginal) original else result
    val compareLabel = stringResource(R.string.edit_compare_hint)
    val originalLabel = stringResource(R.string.edit_original)
    val zoomLabel = stringResource(R.string.edit_zoom_100)
    val density = LocalDensity.current
    val out = vm.outputSize
    BoxWithConstraints(Modifier.fillMaxSize().padding(AppSpacing.m).clipToBounds()
        .semantics {
            contentDescription = compareLabel
            customActions = listOf(CustomAccessibilityAction(zoomLabel) { if (scale > 1.01f) { scale = 1f; offset = Offset.Zero } else scale = 2f; true })
        }, contentAlignment = Alignment.Center) {
        if (shown == null) { CircularProgressIndicator(); return@BoxWithConstraints }
        val box = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val disp = fitRect(box, shown.width.toFloat() / shown.height)
        val scale100 = if (out != null && disp.width > 0f) out.width / disp.width else 2f
        val maxScale = maxOf(4f, scale100 * 2f)
        fun clampOffset(o: Offset, sc: Float): Offset {
            val mx = maxOf(0f, (disp.width * sc - box.width) / 2f + 0f).let { maxOf(it, disp.width * (sc - 1f) / 2f) }
            val my = maxOf(0f, disp.height * (sc - 1f) / 2f)
            return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }
        val img = remember(shown) { shown.asImageBitmap() }
        Box(Modifier.fillMaxSize().pointerInput(disp, maxScale) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val slop = viewConfiguration.touchSlop
                var transformed = false
                // Erst prüfen: halten (Original), tippen (Doppeltipp) oder ziehen/zoomen.
                val decided = withTimeoutOrNull(260) {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.size > 1 || ev.changes.any { (it.position - down.position).getDistance() > slop }) { transformed = true; return@withTimeoutOrNull true }
                        if (ev.changes.all { !it.pressed }) return@withTimeoutOrNull false
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                when {
                    decided == null -> { // gehalten
                        showOriginal = true
                        while (true) { val ev = awaitPointerEvent(); if (ev.changes.all { !it.pressed }) break }
                        showOriginal = false
                    }
                    decided == false -> { // getippt
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 320) {
                            lastTap = 0L
                            if (scale > 1.01f) { scale = 1f; offset = Offset.Zero } else {
                                val target = scale100.coerceIn(2f, maxScale)
                                val c = Offset(size.width / 2f, size.height / 2f)
                                offset = clampOffset((c - down.position) * (target - 1f), target); scale = target
                            }
                        } else lastTap = now
                    }
                    transformed -> do {
                        val ev = awaitPointerEvent()
                        val z = ev.calculateZoom(); val pan = ev.calculatePan()
                        val ns = (scale * z).coerceIn(1f, maxScale)
                        offset = clampOffset(if (ns <= 1.001f) Offset.Zero else offset + pan, ns); scale = ns
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    } while (ev.changes.any { it.pressed })
                }
            }
        }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }) {
            Image(img, null, Modifier.fillMaxSize().testTag(if (showOriginal) "editor.original" else "editor.result"), contentScale = ContentScale.Fit)
            if (!showOriginal) vm.clipOverlay?.let { o ->
                val ov = remember(o) { o.asImageBitmap() }
                Image(ov, null, Modifier.fillMaxSize().testTag("editor.clipping"), contentScale = ContentScale.Fit)
            }
        }
        // Sichtbarer Ausschnitt (normiert) → 100-%-Detail anfordern.
        val view = remember(scale, offset, disp, box) { visibleRect(disp, box, scale, offset) }
        LaunchedEffect(view, vm.recipe, showOriginal) {
            if (scale > 1.15f && !showOriginal && view != null) {
                val tw = (view.width * disp.width * scale).toInt(); val th = (view.height * disp.height * scale).toInt()
                vm.requestDetail(view, tw, th)
            } else vm.clearDetail()
        }
        val d = vm.detail
        if (d != null && view != null && !showOriginal && d.view == view && scale > 1.15f) {
            val r = screenRect(d.view, disp, box, scale, offset)
            val dimg = remember(d) { d.bitmap.asImageBitmap() }
            Canvas(Modifier.fillMaxSize().testTag("editor.detail")) {
                drawImage(dimg, dstOffset = androidx.compose.ui.unit.IntOffset(r.left.toInt(), r.top.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(r.width.toInt().coerceAtLeast(1), r.height.toInt().coerceAtLeast(1)))
            }
        }
        if (showOriginal) Text(originalLabel, Modifier.align(Alignment.TopCenter).glassCapsule(elevation = 2.dp)
            .padding(horizontal = AppSpacing.s, vertical = AppSpacing.xxs), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = PM.colors.textPrimary)
        if (scale > 1.01f) Text("${(scale / scale100 * 100).roundToInt()} %", Modifier.align(Alignment.BottomStart).glassCapsule(elevation = 2.dp)
            .padding(horizontal = AppSpacing.s, vertical = AppSpacing.xxs).testTag("editor.zoom"), style = AppTypography.footnote, color = PM.colors.textPrimary)
        StageTools(vm, Modifier.align(Alignment.TopEnd))
        if (vm.showHistogram) vm.histogram?.let { HistogramCard(it, Modifier.align(Alignment.TopStart)) }
    }
}

/** Sichtbarer Teil des Bildes (normiert 0…1) bei Zoom [scale] und Verschiebung [offset]; `null`, wenn nichts sichtbar. */
private fun visibleRect(disp: Rect, box: Size, scale: Float, offset: Offset): NormRect? {
    val cx = box.width / 2f; val cy = box.height / 2f
    fun un(x: Float, y: Float) = Offset(cx + (x - offset.x - cx) / scale, cy + (y - offset.y - cy) / scale)
    val a = un(0f, 0f); val b = un(box.width, box.height)
    val l = ((maxOf(a.x, disp.left) - disp.left) / disp.width).coerceIn(0f, 1f)
    val t = ((maxOf(a.y, disp.top) - disp.top) / disp.height).coerceIn(0f, 1f)
    val r = ((minOf(b.x, disp.right) - disp.left) / disp.width).coerceIn(0f, 1f)
    val bt = ((minOf(b.y, disp.bottom) - disp.top) / disp.height).coerceIn(0f, 1f)
    if (r - l < 0.001f || bt - t < 0.001f) return null
    // Auf 1/2000 runden: kleine Rundungsunterschiede sollen keine neue Berechnung auslösen.
    fun q(v: Float) = (v * 2000f).roundToInt() / 2000f
    return NormRect(q(l), q(t), q(r), q(bt))
}

private fun screenRect(v: NormRect, disp: Rect, box: Size, scale: Float, offset: Offset): Rect {
    val cx = box.width / 2f; val cy = box.height / 2f
    fun fwd(x: Float, y: Float) = Offset(cx + (x - cx) * scale + offset.x, cy + (y - cy) * scale + offset.y)
    val a = fwd(disp.left + v.left * disp.width, disp.top + v.top * disp.height)
    val b = fwd(disp.left + v.right * disp.width, disp.top + v.bottom * disp.height)
    return Rect(a, b)
}

/** Histogramm und Warnung für abgeschnittene Bereiche – zeigen nur an, ändern nichts. */
@Composable
private fun StageTools(vm: EditorViewModel, modifier: Modifier) {
    val c = PM.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
        StageToggle(Icons.Rounded.BarChart, stringResource(R.string.edit_histogram), vm.showHistogram, "editor.histogram.toggle") { vm.showHistogram = !vm.showHistogram }
        StageToggle(Icons.Rounded.WarningAmber, stringResource(R.string.edit_clipping), vm.showClipping, "editor.clipping.toggle") { vm.showClipping = !vm.showClipping }
    }
}

@Composable
private fun StageToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val c = PM.colors
    Box(Modifier.size(AppSpacing.minTouch).clickable(role = Role.Switch, onClick = onClick)
        .semantics { contentDescription = label; toggleableState = androidx.compose.ui.state.ToggleableState(on) }
        .testTag(tag), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp).then(if (on) Modifier.clip(CircleShape).background(c.accentFill) else Modifier.glassCapsule(elevation = 3.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (on) Color.White else c.textPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun HistogramCard(h: Histogram, modifier: Modifier) {
    val c = PM.colors
    val hi = h.clippedHigh * 1000 / maxOf(1, h.total); val lo = h.clippedLow * 1000 / maxOf(1, h.total)
    val label = stringResource(R.string.edit_histogram)
    Column(modifier.padding(4.dp).glassRect(radius = 12.dp, elevation = 2.dp).padding(8.dp).width(150.dp).testTag("editor.histogram")
        .semantics(mergeDescendants = true) { contentDescription = label }) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val maxV = (h.luma.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
            fun curve(a: IntArray, col: Color, fill: Boolean) {
                val path = androidx.compose.ui.graphics.Path()
                path.moveTo(0f, size.height)
                for (i in 0 until 256) path.lineTo(i / 255f * size.width, size.height - (a[i] / maxV).coerceAtMost(1f) * size.height)
                path.lineTo(size.width, size.height); path.close()
                if (fill) drawPath(path, col) else drawPath(path, col, style = Stroke(1.dp.toPx()))
            }
            curve(h.luma, c.textSecondary.copy(alpha = 0.45f), true)
            curve(h.red, Color(0xFFE5484D).copy(alpha = 0.8f), false)
            curve(h.green, Color(0xFF30A46C).copy(alpha = 0.8f), false)
            curve(h.blue, Color(0xFF3E63DD).copy(alpha = 0.8f), false)
        }
        if (hi >= 1 || lo >= 1) Text(stringResource(R.string.edit_clipped_info, (lo / 10f).toString(), (hi / 10f).toString()),
            style = AppTypography.caption, color = c.textSecondary)
    }
}

@Composable
private fun CropStage(vm: EditorViewModel) {
    val frame = vm.framePreview
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    val vmState by rememberUpdatedState(vm)
    val shrink = stringResource(R.string.edit_crop_shrink)
    val grow = stringResource(R.string.edit_crop_grow)
    val cropLabel = stringResource(R.string.edit_crop_area)
    val cropState = vm.cropSize?.let { "${it.width} × ${it.height}" } ?: ""
    BoxWithConstraints(Modifier.fillMaxSize().padding(AppSpacing.l)) {
        if (frame == null) { CircularProgressIndicator(Modifier.align(Alignment.Center)); return@BoxWithConstraints }
        val box = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val disp = fitRect(box, frame.width.toFloat() / frame.height)
        val img = remember(frame) { frame.asImageBitmap() }
        val touch = with(density) { 28.dp.toPx() }
        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(Modifier.fillMaxSize().testTag("editor.crop")
            .semantics {
                contentDescription = cropLabel; stateDescription = cropState
                customActions = listOf(
                    CustomAccessibilityAction(shrink) { vmState.insetCrop(0.1f); true },
                    CustomAccessibilityAction(grow) { vmState.insetCrop(-0.1f); true },
                )
            }
            .pointerInput(disp) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val handle = hitHandle(down.position, cropRect(vmState.recipe.crop, disp), touch) ?: return@awaitEachGesture
                    down.consume()
                    dragging = true
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val d = ch.positionChange()
                        if (d != Offset.Zero) { vmState.dragCrop(handle, d.x / disp.width, d.y / disp.height); ch.consume() }
                    }
                    dragging = false
                    vmState.commit()
                }
            }) {
            val r = cropRect(vm.recipe.crop, disp)
            drawCropOverlay(disp, r, grid = dragging || vm.straightening, fine = vm.straightening)
        }
    }
}

private fun cropRect(c: NormRect, disp: Rect) = Rect(disp.left + c.left * disp.width, disp.top + c.top * disp.height,
    disp.left + c.right * disp.width, disp.top + c.bottom * disp.height)

private fun hitHandle(p: Offset, r: Rect, t: Float): CropMath.Handle? {
    fun near(a: Offset) = hypot(p.x - a.x, p.y - a.y) <= t
    return when {
        near(r.topLeft) -> CropMath.Handle.TOP_LEFT
        near(r.topRight) -> CropMath.Handle.TOP_RIGHT
        near(r.bottomLeft) -> CropMath.Handle.BOTTOM_LEFT
        near(r.bottomRight) -> CropMath.Handle.BOTTOM_RIGHT
        abs(p.x - r.left) <= t && p.y in r.top..r.bottom -> CropMath.Handle.LEFT
        abs(p.x - r.right) <= t && p.y in r.top..r.bottom -> CropMath.Handle.RIGHT
        abs(p.y - r.top) <= t && p.x in r.left..r.right -> CropMath.Handle.TOP
        abs(p.y - r.bottom) <= t && p.x in r.left..r.right -> CropMath.Handle.BOTTOM
        r.contains(p) -> CropMath.Handle.MOVE
        else -> null
    }
}

private fun DrawScope.drawCropOverlay(disp: Rect, r: Rect, grid: Boolean, fine: Boolean) {
    val dim = Color.Black.copy(alpha = 0.55f)
    // Abdunkeln außerhalb des Ausschnitts (vier Flächen, keine Überlappung).
    drawRect(dim, disp.topLeft, Size(disp.width, r.top - disp.top))
    drawRect(dim, Offset(disp.left, r.bottom), Size(disp.width, disp.bottom - r.bottom))
    drawRect(dim, Offset(disp.left, r.top), Size(r.left - disp.left, r.height))
    drawRect(dim, Offset(r.right, r.top), Size(disp.right - r.right, r.height))
    val line = Color.White.copy(alpha = 0.9f)
    if (grid) {
        val n = if (fine) 9 else 3
        for (i in 1 until n) {
            val a = if (fine && i % 3 != 0) 0.22f else 0.5f
            val x = r.left + r.width * i / n; val y = r.top + r.height * i / n
            drawLine(Color.White.copy(alpha = a), Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = a), Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
        }
    }
    drawRect(line, r.topLeft, r.size, style = Stroke(1.dp.toPx()))
    // Eckgriffe (L-Form) und Kantengriffe.
    val len = minOf(22.dp.toPx(), r.width / 3, r.height / 3); val w = 3.dp.toPx()
    val o = w / 2
    fun corner(p: Offset, sx: Float, sy: Float) {
        drawLine(Color.White, Offset(p.x - sx * o, p.y - sy * o), Offset(p.x + sx * len, p.y - sy * o), w, StrokeCap.Round)
        drawLine(Color.White, Offset(p.x - sx * o, p.y - sy * o), Offset(p.x - sx * o, p.y + sy * len), w, StrokeCap.Round)
    }
    corner(r.topLeft, 1f, 1f); corner(r.topRight, -1f, 1f); corner(r.bottomLeft, 1f, -1f); corner(r.bottomRight, -1f, -1f)
    val e = minOf(18.dp.toPx(), r.width / 4, r.height / 4)
    drawLine(Color.White, Offset(r.center.x - e, r.top - o), Offset(r.center.x + e, r.top - o), w, StrokeCap.Round)
    drawLine(Color.White, Offset(r.center.x - e, r.bottom + o), Offset(r.center.x + e, r.bottom + o), w, StrokeCap.Round)
    drawLine(Color.White, Offset(r.left - o, r.center.y - e), Offset(r.left - o, r.center.y + e), w, StrokeCap.Round)
    drawLine(Color.White, Offset(r.right + o, r.center.y - e), Offset(r.right + o, r.center.y + e), w, StrokeCap.Round)
}

/** Kleine Filter-Vorschau aus dem aktuellen Foto. */
@Composable
internal fun FilterThumb(bitmap: Bitmap?, filter: ColorFilter?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(PM.colors.backgroundTint)) {
        if (bitmap != null) {
            val img = remember(bitmap) { bitmap.asImageBitmap() }
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = filter)
        } else Box(Modifier.size(1.dp))
    }
}
