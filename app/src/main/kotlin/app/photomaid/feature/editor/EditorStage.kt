package app.photomaid.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.ColorMatrix
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
import app.photomaid.core.edit.ColorMatrices
import app.photomaid.core.edit.CropMath
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.NormRect
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.PM
import app.photomaid.design.glassCapsule
import kotlin.math.abs
import kotlin.math.hypot

/** Farbe live über die GPU – dieselbe Matrix wie beim Export ([ColorMatrices.forRecipe]). */
internal fun colorFilterFor(r: EditRecipe): ColorFilter? =
    if (!r.hasColor) null else ColorFilter.colorMatrix(ColorMatrix(ColorMatrices.forRecipe(r)))

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
            vm.tab == EditorTab.CROP -> CropStage(vm)
            else -> ResultStage(vm, src.preview)
        }
    }
}

@Composable
private fun ResultStage(vm: EditorViewModel, original: Bitmap) {
    var showOriginal by remember { mutableStateOf(false) }
    val result = vm.resultPreview
    val shown = if (showOriginal) original else result
    val compareLabel = stringResource(R.string.edit_compare_hint)
    val originalLabel = stringResource(R.string.edit_original)
    Box(Modifier.fillMaxSize().padding(AppSpacing.m)
        .pointerInput(Unit) { detectTapGestures(onPress = { showOriginal = true; tryAwaitRelease(); showOriginal = false }) }
        .semantics { contentDescription = compareLabel }, contentAlignment = Alignment.Center) {
        if (shown == null) { CircularProgressIndicator(); return@Box }
        val img = remember(shown) { shown.asImageBitmap() }
        Image(img, null, Modifier.fillMaxSize().testTag(if (showOriginal) "editor.original" else "editor.result"),
            contentScale = ContentScale.Fit, colorFilter = if (showOriginal) null else colorFilterFor(vm.recipe))
        if (showOriginal) Text(originalLabel, Modifier.align(Alignment.TopCenter).glassCapsule(elevation = 2.dp)
            .padding(horizontal = AppSpacing.s, vertical = AppSpacing.xxs), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = PM.colors.textPrimary)
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
        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = colorFilterFor(vm.recipe))
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
