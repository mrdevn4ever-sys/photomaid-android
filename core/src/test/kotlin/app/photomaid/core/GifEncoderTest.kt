package app.photomaid.core

import app.photomaid.core.batch.GifEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO
import kotlin.math.abs

class GifEncoderTest {
    private fun frame(w: Int, h: Int, f: (Int, Int) -> Int) = IntArray(w * h) { i -> f(i % w, i / w) }

    @Test fun decodesWithJavaImageIoAndKeepsColours() {
        val w = 64; val h = 40
        val red = frame(w, h) { x, _ -> if (x < 32) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val grad = frame(w, h) { x, y -> (0xFF shl 24) or ((x * 4) shl 16) or ((y * 6) shl 8) or 0x40 }
        val bytes = GifEncoder.encode(w, h, listOf(GifEncoder.Frame(red, 500), GifEncoder.Frame(grad, 1000)))
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals(0x3B, bytes.last().toInt())
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(bytes.inputStream())
        assertEquals(2, reader.getNumImages(true))
        val img0 = reader.read(0)
        assertEquals(w, img0.width); assertEquals(h, img0.height)
        assertEquals(0xFF0000, img0.getRGB(5, 5) and 0xFFFFFF)
        assertEquals(0x0000FF, img0.getRGB(50, 5) and 0xFFFFFF)
        val img1 = reader.read(1)
        val p = img1.getRGB(40, 20)
        assertTrue("Verlauf ungefähr erhalten", abs((p shr 16 and 0xFF) - 160) < 24 && abs((p shr 8 and 0xFF) - 120) < 24)
    }

    @Test fun largeNoisyFrameStillValid() {
        val w = 300; val h = 200
        var seed = 12345
        val noise = IntArray(w * h) { seed = seed * 1103515245 + 12345; (0xFF shl 24) or (seed ushr 8 and 0xFFFFFF) }
        val bytes = GifEncoder.encode(w, h, listOf(GifEncoder.Frame(noise, 100)))
        val img = ImageIO.read(bytes.inputStream())
        assertEquals(w, img.width)
    }

    @Test fun transparentPixelsUseBackground() {
        val bytes = GifEncoder.encode(2, 1, listOf(GifEncoder.Frame(intArrayOf(0x00000000, 0xFFFFFFFF.toInt()), 100)), background = 0xFFFFFFFF.toInt())
        val img = ImageIO.read(bytes.inputStream())
        assertEquals(0xFFFFFF, img.getRGB(0, 0) and 0xFFFFFF)
    }

    @Test fun exactColoursRoundTripWithManyDictionaryResets() {
        val w = 320; val h = 240
        val colours = IntArray(200) { i -> (0xFF shl 24) or ((i * 37 % 256) shl 16) or ((i * 91 % 256) shl 8) or (i * 13 % 256) }.distinct()
        var seed = 7
        val px = IntArray(w * h) { seed = seed * 1103515245 + 12345; colours[(seed ushr 9) % colours.size] }
        val img = ImageIO.read(GifEncoder.encode(w, h, listOf(GifEncoder.Frame(px, 100))).inputStream())
        var wrong = 0
        for (y in 0 until h) for (x in 0 until w) if ((img.getRGB(x, y) and 0xFFFFFF) != (px[y * w + x] and 0xFFFFFF)) wrong++
        assertEquals(0, wrong)
    }
}
