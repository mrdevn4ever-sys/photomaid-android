package app.photomaid.core.batch

import java.io.ByteArrayOutputStream

/**
 * GIF89a-Encoder (P7) ohne Fremdbibliothek: gemeinsame Palette (Median-Cut über Stichproben aller Bilder, max. 256
 * Farben), LZW-Kompression, Endlosschleife. Alle Bilder müssen gleich groß sein (ARGB, Transparenz wird auf den
 * Hintergrund gelegt). Reine Rechnung – keine Bildanalyse.
 */
object GifEncoder {
    class Frame(val argb: IntArray, val delayMs: Int)

    fun encode(width: Int, height: Int, frames: List<Frame>, background: Int = 0xFF000000.toInt()): ByteArray {
        require(width in 1..4096 && height in 1..4096) { "size" }
        require(frames.isNotEmpty() && frames.all { it.argb.size == width * height }) { "frames" }
        val flat = frames.map { f -> IntArray(f.argb.size) { i -> flatten(f.argb[i], background) } }
        val palette = palette(flat)
        val lookup = IntArray(32 * 32 * 32) { -1 }
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        le16(out, width); le16(out, height)
        out.write(0xF7) // globale Farbtabelle, 8 Bit, 256 Einträge
        out.write(0); out.write(0)
        for (i in 0 until 256) { val c = palette.getOrElse(i) { 0 }; out.write(c shr 16 and 0xFF); out.write(c shr 8 and 0xFF); out.write(c and 0xFF) }
        // NETSCAPE2.0: endlos wiederholen.
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B)); out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1, 0, 0, 0))
        for ((fi, pixels) in flat.withIndex()) {
            val delay = (frames[fi].delayMs / 10).coerceIn(2, 65535)
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0)); le16(out, delay); out.write(0); out.write(0)
            out.write(0x2C); le16(out, 0); le16(out, 0); le16(out, width); le16(out, height); out.write(0)
            val idx = ByteArray(pixels.size) { i -> nearest(pixels[i], palette, lookup).toByte() }
            lzw(out, idx, 8)
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    private fun flatten(c: Int, bg: Int): Int {
        val a = c ushr 24 and 0xFF
        if (a == 255) return c and 0xFFFFFF
        fun ch(s: Int) = ((c shr s and 0xFF) * a + (bg shr s and 0xFF) * (255 - a)) / 255
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Palette aus dem Farb-Histogramm (bis 2 Mio. Pixel vollständig, darüber Stichproben). Höchstens 256 verschiedene
     * Farben → genau diese (verlustfrei, z. B. Screenshots); sonst Median-Cut über die verschiedenen Farben, nach
     * Häufigkeit gewichtet – gleiche Farben landen nie in zwei Boxen.
     */
    internal fun palette(frames: List<IntArray>): IntArray {
        val total = frames.sumOf { it.size }
        val step = maxOf(1, total / 2_000_000)
        val hist = HashMap<Int, Int>(4096)
        var k = 0
        for (f in frames) for (p in f) { if (k++ % step == 0) { val c = p and 0xFFFFFF; hist[c] = (hist[c] ?: 0) + 1 } }
        if (hist.size <= 256) return hist.keys.sorted().toIntArray()
        class Box(val colours: List<Int>) { val weight = colours.sumOf { hist[it]!!.toLong() } }
        val boxes = mutableListOf(Box(hist.keys.toList()))
        while (boxes.size < 256) {
            val i = boxes.indices.filter { boxes[it].colours.size > 1 }.maxByOrNull { range(boxes[it].colours).toLong() * boxes[it].weight } ?: break
            val b = boxes[i]
            val ch = channel(b.colours)
            val sorted = b.colours.sortedBy { it shr ch and 0xFF }
            // Gewichteter Median, aber beide Hälften nicht leer.
            var acc = 0L; var cut = 1
            for ((n, c) in sorted.withIndex()) { acc += hist[c]!!; if (acc * 2 >= b.weight) { cut = (n + 1).coerceIn(1, sorted.size - 1); break } }
            boxes.removeAt(i); boxes.add(Box(sorted.subList(0, cut))); boxes.add(Box(sorted.subList(cut, sorted.size)))
        }
        return boxes.map { b ->
            var r = 0L; var g = 0L; var bl = 0L
            for (c in b.colours) { val w = hist[c]!!.toLong(); r += (c shr 16 and 0xFF) * w; g += (c shr 8 and 0xFF) * w; bl += (c and 0xFF) * w }
            (((r / b.weight).toInt()) shl 16) or (((g / b.weight).toInt()) shl 8) or (bl / b.weight).toInt()
        }.distinct().toIntArray()
    }

    private fun span(b: List<Int>, s: Int): Int { var lo = 255; var hi = 0; for (p in b) { val v = p shr s and 0xFF; if (v < lo) lo = v; if (v > hi) hi = v }; return hi - lo }
    private fun range(b: List<Int>) = if (b.isEmpty()) 0 else maxOf(span(b, 16), span(b, 8), span(b, 0))
    private fun channel(b: List<Int>): Int { val r = span(b, 16); val g = span(b, 8); val bl = span(b, 0); return if (r >= g && r >= bl) 16 else if (g >= bl) 8 else 0 }

    private fun nearest(c: Int, palette: IntArray, lookup: IntArray): Int {
        val key = ((c shr 19 and 31) shl 10) or ((c shr 11 and 31) shl 5) or (c shr 3 and 31)
        lookup[key].let { if (it >= 0) return it }
        val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
        var best = 0; var bd = Int.MAX_VALUE
        for (i in palette.indices) {
            val p = palette[i]; val dr = r - (p shr 16 and 0xFF); val dg = g - (p shr 8 and 0xFF); val db = b - (p and 0xFF)
            val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (d < bd) { bd = d; best = i }
        }
        lookup[key] = best
        return best
    }

    /** LZW nach GIF-Spezifikation, Ausgabe in Unterblöcken zu max. 255 Bytes. */
    private fun lzw(out: ByteArrayOutputStream, data: ByteArray, minCode: Int) {
        out.write(minCode)
        val clear = 1 shl minCode; val eoi = clear + 1
        val blocks = ByteArrayOutputStream()
        var bitBuf = 0L; var bitCount = 0
        var codeSize = minCode + 1
        fun emit(code: Int) {
            bitBuf = bitBuf or (code.toLong() shl bitCount); bitCount += codeSize
            while (bitCount >= 8) { blocks.write((bitBuf and 0xFF).toInt()); bitBuf = bitBuf ushr 8; bitCount -= 8 }
        }
        val dict = HashMap<Long, Int>(8192)
        var next = eoi + 1
        emit(clear)
        if (data.isEmpty()) { emit(eoi) } else {
            var prefix = data[0].toInt() and 0xFF
            for (i in 1 until data.size) {
                val c = data[i].toInt() and 0xFF
                val key = (prefix.toLong() shl 8) or c.toLong()
                val found = dict[key]
                if (found != null) { prefix = found; continue }
                emit(prefix)
                if (next < 4096) {
                    dict[key] = next++
                    if (next > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    emit(clear); dict.clear(); next = eoi + 1; codeSize = minCode + 1
                }
                prefix = c
            }
            emit(prefix); emit(eoi)
        }
        if (bitCount > 0) blocks.write((bitBuf and 0xFF).toInt())
        val bytes = blocks.toByteArray()
        var p = 0
        while (p < bytes.size) { val n = minOf(255, bytes.size - p); out.write(n); out.write(bytes, p, n); p += n }
        out.write(0)
    }

    private fun le16(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write(v shr 8 and 0xFF) }
}
