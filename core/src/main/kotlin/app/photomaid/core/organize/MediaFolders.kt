package app.photomaid.core.organize

import app.photomaid.core.model.MediaKind

/**
 * Ordner der Mediathek (MediaStore `RELATIVE_PATH`, z. B. `Pictures/Urlaub/`). Android kennt kein universelles
 * Album: Galerie-Apps zeigen diese Ordner meist als Album, eigene App-Alben (Google Fotos, Samsung) sind privat.
 * GaleriQ bietet deshalb nur „In Ordner verschieben“ an – nie „Album“ als Systembegriff.
 */
data class MediaFolder(
    /** Immer mit abschließendem `/`. */
    val relativePath: String,
    val count: Int,
) {
    val name: String get() = relativePath.trimEnd('/').substringAfterLast('/')
}

object MediaFolders {
    /** Ziel neuer Ordner: für Fotos und Videos gleichermaßen erlaubt. */
    const val NEW_FOLDER_ROOT = "Pictures/"
    const val MAX_NAME = 40

    /** MediaStore-Regeln (Android 11+): Bilder nur unter DCIM/Pictures, Videos unter DCIM/Movies/Pictures. */
    fun allowedTop(kind: MediaKind): Set<String> = when (kind) {
        MediaKind.VIDEO -> setOf("DCIM", "Movies", "Pictures")
        MediaKind.PHOTO, MediaKind.SCREENSHOT -> setOf("DCIM", "Pictures")
        MediaKind.FILE -> emptySet()
    }

    fun normalize(path: String): String = path.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }

    fun isAllowed(kind: MediaKind, relativePath: String): Boolean {
        val p = normalize(relativePath)
        if (p.isEmpty() || p.split('/').any { it.startsWith(".") }) return false
        // Nur Unterordner (nicht direkt „Pictures/“ selbst) – dort legen Galerien keine Alben an.
        val parts = p.trimEnd('/').split('/')
        return parts.size >= 2 && parts.first() in allowedTop(kind)
    }

    /**
     * Prüft einen neuen Ordnernamen. `null` = ungültig (leer, zu lang, Pfadzeichen, versteckt).
     * Ergebnis ist der bereinigte Name ohne umgebende Leerzeichen.
     */
    fun sanitizeName(raw: String): String? {
        val n = raw.trim().replace(Regex("\\s+"), " ")
        if (n.isEmpty() || n.length > MAX_NAME || n.startsWith(".")) return null
        if (n.any { it in "/\\:*?\"<>|" || it.isISOControl() }) return null
        return n
    }

    fun newFolderPath(name: String): String? = sanitizeName(name)?.let { "$NEW_FOLDER_ROOT$it/" }

    /**
     * Auswahlliste für ein Medium: nur erlaubte Ziele, der aktuelle Ordner zuerst markiert (nicht wählbar),
     * danach nach Anzahl absteigend, dann nach Name. `query` filtert nach Namen (ohne Groß-/Kleinschreibung).
     */
    fun choices(all: List<MediaFolder>, kind: MediaKind, query: String = ""): List<MediaFolder> {
        val q = query.trim()
        return all.filter { isAllowed(kind, it.relativePath) }
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            .distinctBy { it.relativePath.lowercase() }
            .sortedWith(compareByDescending<MediaFolder> { it.count }.thenBy { it.name.lowercase() })
    }
}
