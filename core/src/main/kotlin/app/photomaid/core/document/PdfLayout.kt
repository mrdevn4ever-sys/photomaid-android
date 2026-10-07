package app.photomaid.core.document

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dokumente (Ausbau Paket 4): mehrseitiges PDF aus Fotos und Kontaktbögen. Keine Text- oder Dokumenterkennung –
 * nur Anordnung. Fotos werden nie verzerrt: eingepasst (ganzes Bild) oder beschnitten (Feld gefüllt).
 * Maße in PDF-Punkten (1/72 Zoll), Ursprung oben links; der Schreiber rechnet in PDF-Koordinaten um.
 */
enum class PaperSize(val shortPt: Double, val longPt: Double) {
    A4(595.28, 841.89), LETTER(612.0, 792.0), A5(419.53, 595.28)
}

enum class PageOrientation { AUTO, PORTRAIT, LANDSCAPE }

enum class PageMargin(val pt: Double) { NONE(0.0), SMALL(18.0), NORMAL(36.0) }

/** Bilder je Seite; ab vier ist es ein Kontaktbogen. */
enum class PerPage(val cols: Int, val rows: Int) {
    ONE(1, 1), TWO(1, 2), FOUR(2, 2), SIX(2, 3), TWELVE(3, 4);
    val count: Int get() = cols * rows
    val isContactSheet: Boolean get() = count >= 4
}

enum class ImageFit { FIT, FILL }

/** Beschriftung unter jedem Bild – nur, was die App ohnehin kennt (Nummer, Dateiname, Datum). */
enum class Caption { NONE, NUMBER, NAME, DATE }

data class PdfOptions(
    val paper: PaperSize = PaperSize.A4,
    val orientation: PageOrientation = PageOrientation.AUTO,
    val margin: PageMargin = PageMargin.NORMAL,
    val perPage: PerPage = PerPage.ONE,
    val fit: ImageFit = ImageFit.FIT,
    val caption: Caption = Caption.NONE,
)

/** Rechteck in Punkten, Ursprung oben links. */
data class PtRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val aspect: Double get() = w / h
}

/** Sichtbarer Ausschnitt der Quelle, normiert 0…1. */
data class Crop(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val isFull: Boolean get() = left == 0.0 && top == 0.0 && right == 1.0 && bottom == 1.0
    companion object { val FULL = Crop(0.0, 0.0, 1.0, 1.0) }
}

/** Ein Bild auf der Seite: [rect] = Zeichenfläche (Seitenverhältnis = Ausschnitt), [caption] = Feld für die Beschriftung. */
data class Placed(val index: Int, val cell: PtRect, val rect: PtRect, val crop: Crop, val caption: PtRect?)

data class PdfPage(val widthPt: Double, val heightPt: Double, val items: List<Placed>)

/** Ausgerichtete Pixelmaße einer Quelle (nach EXIF-Drehung). */
data class SourceSize(val width: Int, val height: Int) {
    val aspect: Double get() = width.toDouble() / height
}

object PdfLayout {
    const val GAP_PT = 10.0
    const val CAPTION_PT = 16.0

    fun pages(sources: List<SourceSize>, o: PdfOptions): List<PdfPage> {
        if (sources.isEmpty()) return emptyList()
        return sources.indices.chunked(o.perPage.count).map { chunk -> page(chunk, sources, o) }
    }

    private fun page(chunk: List<Int>, sources: List<SourceSize>, o: PdfOptions): PdfPage {
        val landscape = when (o.orientation) {
            PageOrientation.PORTRAIT -> false
            PageOrientation.LANDSCAPE -> true
            // Automatisch: ein Bild je Seite folgt dem Bild, sonst Hochformat.
            PageOrientation.AUTO -> o.perPage == PerPage.ONE && sources[chunk.first()].aspect > 1.0
        }
        val w = if (landscape) o.paper.longPt else o.paper.shortPt
        val h = if (landscape) o.paper.shortPt else o.paper.longPt
        val m = o.margin.pt
        // Im Querformat Spalten und Zeilen tauschen, damit die Felder nicht unnötig schmal werden.
        val cols = if (landscape) o.perPage.rows else o.perPage.cols
        val rows = if (landscape) o.perPage.cols else o.perPage.rows
        val gap = if (o.perPage == PerPage.ONE) 0.0 else GAP_PT
        val cellW = (w - 2 * m - gap * (cols - 1)) / cols
        val cellH = (h - 2 * m - gap * (rows - 1)) / rows
        val items = chunk.mapIndexed { slot, i ->
            val cell = PtRect(m + (slot % cols) * (cellW + gap), m + (slot / cols) * (cellH + gap), cellW, cellH)
            val capH = if (o.caption == Caption.NONE) 0.0 else CAPTION_PT
            val area = PtRect(cell.x, cell.y, cell.w, cell.h - capH)
            val (rect, crop) = place(sources[i], area, o.fit)
            Placed(i, cell, rect, crop, if (capH > 0) PtRect(cell.x, cell.y + cell.h - capH, cell.w, capH) else null)
        }
        return PdfPage(w, h, items)
    }

    /** Einpassen: größtes Rechteck mit dem Seitenverhältnis der Quelle, mittig. Füllen: ganze Fläche, Quelle mittig beschnitten. */
    fun place(src: SourceSize, area: PtRect, fit: ImageFit): Pair<PtRect, Crop> = when (fit) {
        ImageFit.FIT -> {
            val s = min(area.w / src.width, area.h / src.height)
            val dw = src.width * s; val dh = src.height * s
            PtRect(area.x + (area.w - dw) / 2, area.y + (area.h - dh) / 2, dw, dh) to Crop.FULL
        }
        ImageFit.FILL -> {
            val a = area.aspect
            val crop = if (src.aspect > a) {
                val keep = a / src.aspect; Crop((1 - keep) / 2, 0.0, (1 + keep) / 2, 1.0)
            } else {
                val keep = src.aspect / a; Crop(0.0, (1 - keep) / 2, 1.0, (1 + keep) / 2)
            }
            area to crop
        }
    }

    /**
     * Pixel für die Ausgabe: [dpi] bezogen auf die Zeichenfläche, nie mehr als der sichtbare Ausschnitt der Quelle hergibt
     * (nie hochrechnen). Seitenverhältnis bleibt das des Ausschnitts.
     */
    fun pixelsFor(rect: PtRect, src: SourceSize, crop: Crop, dpi: Int = 200): Pair<Int, Int> {
        val availW = src.width * (crop.right - crop.left)
        val availH = src.height * (crop.bottom - crop.top)
        val wantW = rect.w / 72.0 * dpi
        val s = min(1.0, wantW / availW)
        return max(1, (availW * s).roundToInt()) to max(1, (availH * s).roundToInt())
    }
}

/** Free / Pro – ausprobieren (Vorschau) ist immer frei. */
object PdfRules {
    const val FREE_MAX = 10
    const val PRO_MAX = 200
    fun maxImages(pro: Boolean) = if (pro) PRO_MAX else FREE_MAX
    /** Kontaktbögen (ab 4 je Seite) speichern mit Pro, wie der A4-Druck der Collage. */
    fun needsPro(o: PdfOptions, count: Int): Boolean = o.perPage.isContactSheet || count > FREE_MAX
    /** Dateiname ohne Pfadzeichen; leer → Vorgabe. */
    fun fileName(raw: String, fallback: String): String {
        val clean = raw.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").take(80).trim('.', ' ')
        val base = clean.ifEmpty { fallback }
        return if (base.lowercase().endsWith(".pdf")) base else "$base.pdf"
    }
}
