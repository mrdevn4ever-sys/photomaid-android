package app.photomaid.core.document

import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater

/**
 * Schlanker PDF-1.4-Schreiber ohne Fremdbibliothek: JPEG-Bilder (DCTDecode, unverändert eingebettet) und
 * Graustufen-Raster (Flate, z. B. Beschriftungen). Schreibt Seite für Seite (wenig Speicher). Keine Metadaten außer
 * dem Erzeuger – keine Dateinamen, Pfade oder Aufnahmedaten im Dokument-Info.
 */
class PdfWriter(output: OutputStream) {
    sealed interface Image {
        val widthPx: Int; val heightPx: Int
        class Jpeg(val bytes: ByteArray, override val widthPx: Int, override val heightPx: Int) : Image
        /** 8 Bit Graustufen, zeilenweise, Länge = Breite × Höhe. */
        class Gray(val pixels: ByteArray, override val widthPx: Int, override val heightPx: Int) : Image {
            init { require(pixels.size == widthPx * heightPx) }
        }
    }

    /** [rect] in Punkten, Ursprung oben links. */
    data class Draw(val image: Image, val rect: PtRect)

    private val out = CountingStream(output)
    private val offsets = ArrayList<Long>()
    private val pageIds = ArrayList<Int>()
    private var finished = false

    init {
        out.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        offsets += 0L; offsets += 0L // 1 = Katalog, 2 = Seitenbaum (am Ende geschrieben)
    }

    val pageCount: Int get() = pageIds.size

    fun addPage(widthPt: Double, heightPt: Double, draws: List<Draw>) {
        check(!finished)
        val names = draws.indices.map { "Im$it" }
        val imageIds = draws.map { d -> writeImage(d.image) }
        val content = buildString {
            for ((i, d) in draws.withIndex()) {
                val r = d.rect
                val y = heightPt - (r.y + r.h)
                append("q ").append(n(r.w)).append(" 0 0 ").append(n(r.h)).append(' ').append(n(r.x)).append(' ').append(n(y))
                    .append(" cm /").append(names[i]).append(" Do Q\n")
            }
        }.toByteArray(Charsets.ISO_8859_1)
        val contentId = obj { s -> s.ascii("<< /Length ${content.size} >>\nstream\n"); s.write(content); s.ascii("\nendstream") }
        val xobjects = draws.indices.joinToString(" ") { "/${names[it]} ${imageIds[it]} 0 R" }
        val pageId = obj { s ->
            s.ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(widthPt)} ${n(heightPt)}] " +
                "/Resources << /XObject << $xobjects >> >> /Contents $contentId 0 R >>")
        }
        pageIds += pageId
    }

    fun finish() {
        check(!finished); finished = true
        put(1) { it.ascii("<< /Type /Catalog /Pages 2 0 R >>") }
        put(2) { it.ascii("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { id -> "$id 0 R" }}] /Count ${pageIds.size} >>") }
        val info = obj { it.ascii("<< /Producer (PhotoMaid) >>") }
        val xref = out.count
        val sb = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append(String.format(Locale.ROOT, "%010d 00000 n \n", o))
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.ascii(sb.toString())
        out.flush()
    }

    private fun writeImage(img: Image): Int = obj { s ->
        when (img) {
            is Image.Jpeg -> {
                s.ascii("<< /Type /XObject /Subtype /Image /Width ${img.widthPx} /Height ${img.heightPx} /ColorSpace /DeviceRGB " +
                    "/BitsPerComponent 8 /Filter /DCTDecode /Length ${img.bytes.size} >>\nstream\n")
                s.write(img.bytes)
            }
            is Image.Gray -> {
                val z = deflate(img.pixels)
                s.ascii("<< /Type /XObject /Subtype /Image /Width ${img.widthPx} /Height ${img.heightPx} /ColorSpace /DeviceGray " +
                    "/BitsPerComponent 8 /Filter /FlateDecode /Length ${z.size} >>\nstream\n")
                s.write(z)
            }
        }
        s.ascii("\nendstream")
    }

    private fun obj(body: (CountingStream) -> Unit): Int {
        offsets += 0L
        val id = offsets.size
        put(id, body)
        return id
    }

    private fun put(id: Int, body: (CountingStream) -> Unit) {
        offsets[id - 1] = out.count
        out.ascii("$id 0 obj\n"); body(out); out.ascii("\nendobj\n")
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION); d.setInput(data); d.finish()
        val buf = java.io.ByteArrayOutputStream(); val chunk = ByteArray(16 * 1024)
        while (!d.finished()) buf.write(chunk, 0, d.deflate(chunk))
        d.end(); return buf.toByteArray()
    }

    private fun n(v: Double): String = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    private class CountingStream(private val o: OutputStream) : OutputStream() {
        var count = 0L; private set
        override fun write(b: Int) { o.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); count += len }
        override fun flush() = o.flush()
        fun ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
    }
}
