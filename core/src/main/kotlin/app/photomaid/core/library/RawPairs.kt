package app.photomaid.core.library

/**
 * RAW+JPEG-Paare (G4): Kameras speichern oft beides mit gleichem Namen im selben Ordner (`IMG_0042.JPG` +
 * `IMG_0042.DNG`). In der Mediathek steht das RAW hinter dem JPEG (ein Eintrag mit „RAW“-Hinweis); in den Papierkorb
 * gehen beide zusammen. Reine Namens-/Ordnerregel – keine Bildanalyse.
 */
object RawPairs {
    val RAW_EXTENSIONS = setOf("dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "rwl",
        "pef", "srw", "raw", "3fr", "erf", "kdc", "mrw", "x3f", "iiq", "mos")
    val PREVIEW_EXTENSIONS = setOf("jpg", "jpeg", "heic", "heif")

    fun extension(name: String?): String = name?.substringAfterLast('.', "")?.lowercase().orEmpty()

    fun isRaw(name: String?, mime: String?): Boolean {
        val m = mime?.lowercase().orEmpty()
        return extension(name) in RAW_EXTENSIONS || m == "image/x-adobe-dng" || m == "image/dng" || m.contains("-raw") || m.endsWith("/raw")
    }

    private fun isPreview(name: String?, mime: String?): Boolean =
        !isRaw(name, mime) && (extension(name) in PREVIEW_EXTENSIONS || mime?.lowercase() in setOf("image/jpeg", "image/heic", "image/heif"))

    private fun key(name: String?, folder: String?): String? {
        val n = name ?: return null
        val base = n.substringBeforeLast('.', n).lowercase()
        if (base.isEmpty()) return null
        return (folder?.let(FolderTree::normalize)?.lowercase() ?: "") + "\u0000" + base
    }

    /** Vorschau (JPEG/HEIC) → zugehöriges RAW. Ein RAW ohne Partner bleibt ein eigenes Medium. */
    fun <T> pairs(items: List<T>, name: (T) -> String?, folder: (T) -> String?, mime: (T) -> String?): Map<T, T> {
        val raws = HashMap<String, T>()
        for (it in items) if (isRaw(name(it), mime(it))) key(name(it), folder(it))?.let { k -> raws.putIfAbsent(k, it) }
        if (raws.isEmpty()) return emptyMap()
        val out = LinkedHashMap<T, T>()
        for (it in items) if (isPreview(name(it), mime(it))) key(name(it), folder(it))?.let { k -> raws[k]?.let { r -> out[it] = r } }
        return out
    }
}
