package app.photomaid.media

import app.photomaid.core.organize.MediaFolders

/**
 * Mediathek ohne vom Nutzer ausgenommene Ordner (Einstellungen › „Ausgenommene Ordner“).
 * Ausgenommene Medien erscheinen in keinem Feed, keiner Zahl, keiner Sammlung und keiner Monatsübersicht.
 * Unterordner zählen mit. Gezielte Abfragen nach IDs (Papierkorb, Verlauf, Später) bleiben unverändert, damit bereits
 * getroffene Entscheidungen sichtbar und rückgängig zu machen bleiben. Die Ordnerliste (Verschieben, Ausnehmen) ist vollständig.
 */
class ExcludingMediaLibrary(private val base: MediaLibrary, private val excluded: () -> Collection<String>) : MediaLibrary by base {
    override fun assets(category: MediaCategory, limit: Int?): List<MediaAsset> {
        val ex = keys()
        if (ex.isEmpty()) return base.assets(category, limit)
        val kept = base.assets(category, null).filter { !isExcluded(it, ex) }
        return if (limit != null) kept.take(limit) else kept
    }

    override fun count(category: MediaCategory): Int =
        if (keys().isEmpty()) base.count(category) else assets(category, null).size

    private fun keys(): List<String> = excluded().map { MediaFolders.normalize(it).lowercase() }.filter { it.isNotEmpty() }

    companion object {
        fun isExcluded(a: MediaAsset, keys: List<String>): Boolean {
            val p = a.relativePath?.let { MediaFolders.normalize(it).lowercase() } ?: return false
            return keys.any { p.startsWith(it) }
        }
    }
}
