package app.photomaid.core.edit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Ebenen über dem Bild (Markieren). Koordinaten normiert auf das Ausgabebild (nach Zuschnitt), Größen relativ zur
 * kürzeren Kante → gleiche Wirkung in Vorschau, 100-%-Ansicht und Export. Keine Erkennung: alles setzt der Nutzer.
 */
@Serializable
sealed interface Layer {
    val id: String
    fun moved(dx: Float, dy: Float): Layer
    fun transformed(scale: Float, rotationDeg: Float): Layer = this
}

@Serializable
enum class TextStyle { FILL, OUTLINE, PILL, SHADOW }

@Serializable
enum class TextAlign { START, CENTER, END }

/** Systemschriften (alle Sprachen inkl. Arabisch/CJK über die Rückfall-Schriften des Systems, keine Lizenzfragen). */
@Serializable
enum class FontId(val family: String, val bold: Boolean = false) {
    SANS("sans-serif"), SANS_BOLD("sans-serif", true), BLACK("sans-serif-black"), CONDENSED("sans-serif-condensed"),
    SERIF("serif"), MONO("monospace"), HANDWRITING("cursive"), CASUAL("casual"), SMALLCAPS("sans-serif-smallcaps"),
}

@Serializable @SerialName("text")
data class TextLayer(
    override val id: String,
    val text: String,
    val center: NormPoint = NormPoint(0.5f, 0.5f),
    /** Schriftgröße relativ zur kürzeren Bildkante. */
    val size: Float = 0.08f,
    val rotation: Float = 0f,
    val color: Int = 0xFFFFFFFF.toInt(),
    /** Hintergrund (Pille) bzw. Kontur/Schatten. */
    val accent: Int = 0xFF000000.toInt(),
    val style: TextStyle = TextStyle.SHADOW,
    val align: TextAlign = TextAlign.CENTER,
    val font: FontId = FontId.SANS_BOLD,
    val opacity: Float = 1f,
) : Layer {
    override fun moved(dx: Float, dy: Float) = copy(center = NormPoint(center.x + dx, center.y + dy))
    override fun transformed(scale: Float, rotationDeg: Float) = copy(size = (size * scale).coerceIn(0.01f, 0.6f), rotation = rotationDeg)
}

@Serializable
enum class StickerKind { EMOJI, ARROW, HEART, STAR, CHECK, CROSS, BUBBLE, CIRCLE, SPARKLE, DATE }

@Serializable @SerialName("sticker")
data class StickerLayer(
    override val id: String,
    val kind: StickerKind,
    /** Emoji-Zeichen bzw. Text (Datum). */
    val value: String = "",
    val center: NormPoint = NormPoint(0.5f, 0.5f),
    val size: Float = 0.2f,
    val rotation: Float = 0f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val opacity: Float = 1f,
) : Layer {
    override fun moved(dx: Float, dy: Float) = copy(center = NormPoint(center.x + dx, center.y + dy))
    override fun transformed(scale: Float, rotationDeg: Float) = copy(size = (size * scale).coerceIn(0.03f, 1.2f), rotation = rotationDeg)
}

@Serializable
enum class BrushKind { PEN, MARKER, NEON, ERASER }

@Serializable
data class DrawStroke(val points: List<NormPoint>, val width: Float, val color: Int, val brush: BrushKind = BrushKind.PEN)

/** Freihand (eine Ebene je Zeichenvorgang; der Radierer wirkt nur auf Zeichnungen derselben Ebene). */
@Serializable @SerialName("draw")
data class DrawLayer(override val id: String, val strokes: List<DrawStroke>) : Layer {
    override fun moved(dx: Float, dy: Float) = copy(strokes = strokes.map { s -> s.copy(points = s.points.map { NormPoint(it.x + dx, it.y + dy) }) })
}

@Serializable
enum class ShapeKind { ARROW, RECT, ELLIPSE, LINE }

@Serializable @SerialName("shape")
data class ShapeLayer(
    override val id: String,
    val kind: ShapeKind,
    val start: NormPoint,
    val end: NormPoint,
    val color: Int = 0xFFFF3B30.toInt(),
    val width: Float = 0.012f,
    val filled: Boolean = false,
    val opacity: Float = 1f,
) : Layer {
    override fun moved(dx: Float, dy: Float) = copy(start = NormPoint(start.x + dx, start.y + dy), end = NormPoint(end.x + dx, end.y + dy))
}

@Serializable
enum class RedactMode {
    /** Deckend (empfohlen für Sensibles): Pixel werden vollständig ersetzt. */
    SOLID,
    PIXELATE,
    BLUR,
}

/**
 * Abdecken: Rechteck oder Pinsel. Wird beim Export **in die Pixel gerechnet** (nie nur überlagert). Verpixeln/Unschärfe
 * arbeiten mit groben Blöcken, damit nichts rekonstruierbar bleibt; „Deckend“ ist die sichere Wahl.
 */
@Serializable @SerialName("redact")
data class RedactLayer(
    override val id: String,
    val mode: RedactMode = RedactMode.SOLID,
    val rect: NormRect? = null,
    val strokes: List<BrushStroke> = emptyList(),
    val color: Int = 0xFF000000.toInt(),
    /** Blockgröße/Unschärfe relativ zur kürzeren Kante (0…1 → 1…6 %). */
    val strength: Float = 0.6f,
) : Layer {
    override fun moved(dx: Float, dy: Float) = copy(
        rect = rect?.let { NormRect(it.left + dx, it.top + dy, it.right + dx, it.bottom + dy) },
        strokes = strokes.map { s -> s.copy(points = s.points.map { NormPoint(it.x + dx, it.y + dy) }) })

    /** Blockgröße in Pixeln für ein Ausgabebild mit kürzerer Kante [short]: mindestens 8 px, nie fein. */
    fun blockPx(short: Int): Int = max(8, (short * (0.01f + 0.05f * strength.coerceIn(0f, 1f))).roundToInt())
}

/** Rahmen: innen über dem Bildrand oder außen (Bild wird größer), optional auf ein Seitenverhältnis (Passepartout). */
@Serializable
enum class FrameMode { INSIDE, OUTSIDE }

@Serializable
data class FrameStyle(
    val mode: FrameMode = FrameMode.OUTSIDE,
    /** Breite relativ zur kürzeren Kante (0…0,25). */
    val width: Float = 0.04f,
    val color: Int = 0xFFFFFFFF.toInt(),
    /** Außen: Ziel-Seitenverhältnis Breite/Höhe (z. B. 0,8 für 4:5), `null` = gleichmäßiger Rand. */
    val aspect: Float? = null,
    /** Ecken des Bildes abrunden (relativ zur kürzeren Kante). */
    val corner: Float = 0f,
) {
    val isNeutral: Boolean get() = width < 0.001f && aspect == null && corner < 0.001f
}

object FrameMath {
    /** Größe nach dem Rahmen und Lage des Bildes darin (links, oben). */
    fun layout(image: PxSize, f: FrameStyle?): Pair<PxSize, Pair<Int, Int>> {
        if (f == null || f.isNeutral || f.mode == FrameMode.INSIDE) return image to (0 to 0)
        val b = (minOf(image.width, image.height) * f.width.coerceIn(0f, 0.25f)).roundToInt()
        var w = image.width + 2 * b; var h = image.height + 2 * b
        f.aspect?.let { a ->
            if (w.toFloat() / h > a) h = (w / a).roundToInt() else w = (h * a).roundToInt()
        }
        return PxSize(w, h) to ((w - image.width) / 2 to (h - image.height) / 2)
    }
}

/** Kleine Hilfen fürs Bearbeiten der Ebenenliste (Reihenfolge = Zeichenreihenfolge). */
object LayerOps {
    fun replace(list: List<Layer>, l: Layer) = list.map { if (it.id == l.id) l else it }
    fun remove(list: List<Layer>, id: String) = list.filterNot { it.id == id }
    fun toFront(list: List<Layer>, id: String) = list.firstOrNull { it.id == id }?.let { l -> list.filterNot { it.id == id } + l } ?: list
    fun toBack(list: List<Layer>, id: String) = list.firstOrNull { it.id == id }?.let { l -> listOf(l) + list.filterNot { it.id == id } } ?: list
    fun duplicate(list: List<Layer>, id: String, newId: String): List<Layer> {
        val l = list.firstOrNull { it.id == id } ?: return list
        val copy = when (l) {
            is TextLayer -> l.copy(id = newId); is StickerLayer -> l.copy(id = newId); is DrawLayer -> l.copy(id = newId)
            is ShapeLayer -> l.copy(id = newId); is RedactLayer -> l.copy(id = newId)
        }.moved(0.03f, 0.03f)
        return list + copy
    }

    /** Einrasten an Mitte (Hilfslinie) innerhalb [tol]; liefert den eingerasteten Wert und ob gerastet wurde. */
    fun snap(v: Float, target: Float = 0.5f, tol: Float = 0.015f): Pair<Float, Boolean> = if (abs(v - target) <= tol) target to true else v to false

    /** Drehung an 0/90/180/270° einrasten (±4°). */
    fun snapRotation(deg: Float, tol: Float = 4f): Pair<Float, Boolean> {
        val n = ((deg / 90f).roundToInt() * 90f)
        return if (abs(deg - n) <= tol) n to true else deg to false
    }
}
