package app.photomaid.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.photomaid.core.edit.BrushKind
import app.photomaid.core.edit.BrushStroke
import app.photomaid.core.edit.DrawLayer
import app.photomaid.core.edit.DrawStroke
import app.photomaid.core.edit.Layer
import app.photomaid.core.edit.LayerOps
import app.photomaid.core.edit.NormPoint
import app.photomaid.core.edit.NormRect
import app.photomaid.core.edit.RedactLayer
import app.photomaid.core.edit.RedactMode
import app.photomaid.core.edit.ShapeKind
import app.photomaid.core.edit.ShapeLayer
import app.photomaid.core.edit.StickerKind
import app.photomaid.core.edit.StickerLayer
import app.photomaid.core.edit.TextLayer

/**
 * Zustand und Aktionen fürs Markieren (Text, Sticker, Zeichnen, Formen, Abdecken) – gemeinsam für Foto-Editor und
 * Collage. Die Ebenenliste gehört dem Besitzer ([layers]/[setLayers]); so laufen Rückgängig und Speichern dort.
 */
class MarkupController(
    private val layers: () -> List<Layer>,
    private val setLayers: (List<Layer>, Boolean) -> Unit,
    private val defaultText: () -> String,
    private val dateText: () -> String,
) {
    var tool by mutableStateOf(MarkupTool.SELECT)
    var selectedLayerId by mutableStateOf<String?>(null)
    /** Text, der gerade im Blatt bearbeitet wird. */
    var editingTextId by mutableStateOf<String?>(null)
    var showStickers by mutableStateOf(false)
    var brush by mutableStateOf(BrushKind.PEN)
    var drawWidth by mutableStateOf(0.012f)
    var drawColor by mutableStateOf(0xFFFF3B30.toInt())
    var shapeKind by mutableStateOf(ShapeKind.ARROW)
    var shapeFilled by mutableStateOf(false)
    var redactMode by mutableStateOf(RedactMode.SOLID)
    var redactBrush by mutableStateOf(false)
    var redactStrength by mutableStateOf(0.6f)

    val all: List<Layer> get() = layers()
    val selectedLayer: Layer? get() = selectedLayerId?.let { id -> layers().firstOrNull { it.id == id } }

    private fun newId() = java.util.UUID.randomUUID().toString()
    private fun set(list: List<Layer>, commit: Boolean = true) = setLayers(list, commit)

    fun addText() {
        val l = TextLayer(newId(), defaultText(), color = 0xFFFFFFFF.toInt())
        set(layers() + l); selectedLayerId = l.id; editingTextId = l.id; tool = MarkupTool.SELECT
    }

    fun stickerDate(): String = dateText()

    fun addSticker(kind: StickerKind, value: String = "") {
        val v = if (kind == StickerKind.DATE) value.ifEmpty { dateText() } else value
        val l = StickerLayer(newId(), kind, v, color = if (kind == StickerKind.DATE) 0xFFFF8A00.toInt() else drawColor)
        set(layers() + l); selectedLayerId = l.id; showStickers = false; tool = MarkupTool.SELECT
    }

    /** Ebene ändern; [commit] = Schritt fürs Rückgängig (beim Loslassen). */
    fun updateLayer(l: Layer, commit: Boolean) = set(LayerOps.replace(layers(), l), commit)

    fun deleteSelected() { selectedLayerId?.let { set(LayerOps.remove(layers(), it)) }; selectedLayerId = null }
    fun duplicateSelected() { selectedLayerId?.let { id -> val n = newId(); set(LayerOps.duplicate(layers(), id, n)); selectedLayerId = n } }
    fun selectedToFront() { selectedLayerId?.let { set(LayerOps.toFront(layers(), it)) } }
    fun selectedToBack() { selectedLayerId?.let { set(LayerOps.toBack(layers(), it)) } }

    /** Strich fertig: an die oberste Zeichnung anhängen (der Radierer wirkt nur dort), sonst neue Zeichenebene. */
    fun addStroke(points: List<NormPoint>) {
        if (points.isEmpty()) return
        val st = DrawStroke(points, drawWidth, drawColor, brush)
        val list = layers(); val top = list.lastOrNull()
        val next = if (top is DrawLayer) LayerOps.replace(list, top.copy(strokes = top.strokes + st))
            else if (brush == BrushKind.ERASER) return
            else list + DrawLayer(newId(), listOf(st))
        set(next)
    }

    fun addShape(a: NormPoint, b: NormPoint) {
        if (kotlin.math.hypot(b.x - a.x, b.y - a.y) < 0.01f) return
        val l = ShapeLayer(newId(), shapeKind, a, b, drawColor, drawWidth, shapeFilled)
        set(layers() + l); selectedLayerId = l.id
    }

    fun addRedactRect(a: NormPoint, b: NormPoint) {
        val r = NormRect(minOf(a.x, b.x).coerceIn(0f, 1f), minOf(a.y, b.y).coerceIn(0f, 1f), maxOf(a.x, b.x).coerceIn(0f, 1f), maxOf(a.y, b.y).coerceIn(0f, 1f))
        if (r.width < 0.01f || r.height < 0.01f) return
        set(layers() + RedactLayer(newId(), redactMode, rect = r, strength = redactStrength))
    }

    fun addRedactStroke(points: List<NormPoint>) {
        if (points.isEmpty()) return
        set(layers() + RedactLayer(newId(), redactMode, strokes = listOf(BrushStroke(points, radius = drawWidth * 2f, hardness = 1f)), strength = redactStrength))
    }
}
