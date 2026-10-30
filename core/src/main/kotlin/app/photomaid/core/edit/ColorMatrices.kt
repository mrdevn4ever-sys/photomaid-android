package app.photomaid.core.edit

/**
 * 4×5-Farbmatrizen im Android-`ColorMatrix`-Format (Zeilen R, G, B, A; Spalten r g b a + Versatz in 0…255).
 * Reine Mathematik: identisch für Vorschau (GPU) und Export.
 */
object ColorMatrices {
    val IDENTITY: FloatArray get() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    private const val LR = 0.2126f; private const val LG = 0.7152f; private const val LB = 0.0722f

    /** Erst [b], dann [a] anwenden. */
    fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (r in 0..3) {
            for (c in 0..3) out[r * 5 + c] = (0..3).sumOf { k -> (a[r * 5 + k] * b[k * 5 + c]).toDouble() }.toFloat()
            out[r * 5 + 4] = (0..3).sumOf { k -> (a[r * 5 + k] * b[k * 5 + 4]).toDouble() }.toFloat() + a[r * 5 + 4]
        }
        return out
    }

    fun lerp(a: FloatArray, b: FloatArray, t: Float): FloatArray = FloatArray(20) { a[it] + (b[it] - a[it]) * t.coerceIn(0f, 1f) }

    fun brightness(v: Float): FloatArray { val o = 255f * 0.25f * (v / 100f); return IDENTITY.also { it[4] = o; it[9] = o; it[14] = o } }

    fun contrast(v: Float): FloatArray {
        val k = 1f + 0.6f * (v / 100f); val o = 127.5f * (1f - k)
        return floatArrayOf(k, 0f, 0f, 0f, o, 0f, k, 0f, 0f, o, 0f, 0f, k, 0f, o, 0f, 0f, 0f, 1f, 0f)
    }

    fun saturation(v: Float): FloatArray = saturationFactor(1f + v / 100f)

    fun saturationFactor(s: Float): FloatArray {
        val i = 1f - s
        return floatArrayOf(
            LR * i + s, LG * i, LB * i, 0f, 0f,
            LR * i, LG * i + s, LB * i, 0f, 0f,
            LR * i, LG * i, LB * i + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Wärmer (positiv) = mehr Rot/weniger Blau, kühler (negativ) umgekehrt. */
    fun warmth(v: Float): FloatArray {
        val t = v / 100f
        return floatArrayOf(1f + 0.10f * t, 0f, 0f, 0f, 8f * t, 0f, 1f + 0.02f * t, 0f, 0f, 0f, 0f, 0f, 1f - 0.10f * t, 0f, -8f * t, 0f, 0f, 0f, 1f, 0f)
    }

    fun filter(id: FilterId): FloatArray = when (id) {
        FilterId.NONE -> IDENTITY
        FilterId.MONO -> saturationFactor(0f)
        FilterId.MONO_CONTRAST -> concat(contrast(45f), saturationFactor(0f))
        FilterId.WARM -> concat(warmth(55f), saturation(10f))
        FilterId.COOL -> concat(warmth(-55f), saturation(-5f))
        FilterId.SEPIA -> floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        FilterId.FADE -> concat(brightness(10f), concat(contrast(-30f), saturation(-25f)))
        FilterId.VIVID -> concat(contrast(15f), saturation(45f))
    }

    /** Gesamtmatrix: erst Filter (mit Stärke), dann Regler (Wärme, Sättigung, Kontrast, Helligkeit). */
    fun forRecipe(r: EditRecipe): FloatArray {
        val f = lerp(IDENTITY, filter(r.filter), if (r.filter == FilterId.NONE) 0f else r.filterIntensity)
        val adj = concat(brightness(r.adjust.brightness), concat(contrast(r.adjust.contrast), concat(saturation(r.adjust.saturation), warmth(r.adjust.warmth))))
        return concat(adj, f)
    }

    /** Auf ein ARGB-Pixel anwenden (Export-Pfad ohne GPU, Tests). */
    fun apply(m: FloatArray, argb: Int): Int {
        val a = (argb ushr 24) and 0xFF; val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        fun ch(row: Int) = (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 3] * a + m[row * 5 + 4]).toInt().coerceIn(0, 255)
        return (ch(3) shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
    }
}

/** Unschärfe-Maske (Schärfen) auf ARGB-Pixeln, ohne Bildanalyse. Rand wird gespiegelt behandelt. */
object Sharpen {
    /** Stärke 0…100 → Faktor; Radius wächst mit der Bildgröße (gleicher Eindruck in Vorschau und Export). */
    fun amount(sharpness: Float): Float = 1.4f * (sharpness / 100f).coerceIn(0f, 1f)
    fun radiusFor(longEdge: Int): Int = (longEdge / 1600).coerceIn(1, 4)

    /** [threshold] 0…255: kleinere Unterschiede (Flächen, Rauschen) bleiben ungeschärft. */
    fun apply(px: IntArray, w: Int, h: Int, amount: Float, radius: Int, threshold: Int = 0): IntArray {
        if (amount <= 0f || w < 3 || h < 3) return px
        val blur = boxBlur(px, w, h, radius)
        val out = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]; val q = blur[i]
            fun ch(shift: Int): Int {
                val c = (p shr shift) and 0xFF; val bl = (q shr shift) and 0xFF
                val d = c - bl
                return if (kotlin.math.abs(d) <= threshold) c else (c + amount * d).toInt().coerceIn(0, 255)
            }
            out[i] = (p and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return out
    }

    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        if (w < 2 || h < 2) return px.copyOf()
        @Suppress("NAME_SHADOWING") val r = minOf(r, w - 1, h - 1).coerceAtLeast(1)
        val tmp = IntArray(px.size); val out = IntArray(px.size)
        pass(px, tmp, w, h, r, horizontal = true); pass(tmp, out, w, h, r, horizontal = false)
        return out
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val n = if (horizontal) w else h; val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(i: Int): Int { val k = if (i < 0) -i else if (i >= n) 2 * n - 2 - i else i; return if (horizontal) line * w + k else k * w + line }
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val p = src[idx(i)]; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
            for (i in 0 until n) {
                dst[idx(i)] = (src[idx(i)] and 0xFF000000.toInt()) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[idx(i + r + 1)]; val rem = src[idx(i - r)]
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF); sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF); sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }
}

/** Ausgabeformat je Quelle: Format bleibt, HEIC/unbekannt → JPEG. */
enum class EditFormat(val mime: String, val extension: String) {
    JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), WEBP("image/webp", "webp");

    companion object {
        fun forSource(mime: String?): EditFormat = when (mime?.lowercase()) {
            "image/png" -> PNG
            "image/webp" -> WEBP
            else -> JPEG
        }
        const val JPEG_QUALITY = 95
    }
}

/** Ein von PhotoMaid gespeichertes Ergebnis (nur lokal, für die Liste „Bearbeitet“). */
@kotlinx.serialization.Serializable
data class EditRecord(
    val newId: String,
    val sourceId: String,
    val mode: SaveMode,
    val video: Boolean,
    val atMs: Long,
    /** Art: Bearbeitung eines Mediums oder eigenes Werk (Collage, zusammengefügtes Video, Diashow, GIF). */
    val kind: EditKind = EditKind.EDIT,
    /** Anzahl verwendeter Medien bei eigenen Werken. */
    val count: Int = 0,
)

@kotlinx.serialization.Serializable
enum class EditKind { EDIT, COLLAGE, MERGE, SLIDESHOW, GIF, FRAME, COMPRESS, CONVERT }

/** Dateiname der Kopie: „IMG_1234_bearbeitet.jpg“ (Zusatz aus den Texten). */
object EditNames {
    fun copyName(original: String?, suffix: String, extension: String): String {
        val base = original?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "GaleriQ"
        val clean = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        return "${clean}_$suffix.$extension"
    }
    fun sameName(original: String?, extension: String): String {
        val base = original?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "GaleriQ"
        return "$base.$extension"
    }
}
