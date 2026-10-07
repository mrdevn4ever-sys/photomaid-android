package app.photomaid.media

import app.photomaid.core.organize.MediaFolders

/**
 * Mediathek ohne ausgenommene bzw. ausgeblendete Ordner (inkl. Unterordner) und ohne einzeln ausgeblendete Medien.
 * - [AppModel.photos] (Aufräumen): ohne „Ausgenommene Ordner“ und ohne „In PhotoMaid ausgeblendet“.
 * - [AppModel.visiblePhotos] (Tab „Fotos“): nur ohne Ausgeblendetes – ausgenommene Ordner bleiben ansehbar.
 * Ausgeblendete Medien erscheinen in keinem Feed, keiner Zahl, keiner Sammlung und keiner Monatsübersicht.
 * Gezielte Abfragen nach IDs (Papierkorb, Verlauf, Später) bleiben unverändert, damit bereits getroffene
 * Entscheidungen sichtbar und rückgängig zu machen bleiben. Die Ordnerliste (Verschieben, Ausnehmen) ist vollständig.
 */
class ExcludingMediaLibrary(
    private val base: MediaLibrary,
    private val hiddenIds: () -> Collection<String> = { emptyList() },
    private val excluded: () -> Collection<String>,
) : MediaLibrary by base {
    override fun assets(category: MediaCategory, limit: Int?): List<MediaAsset> {
        val ex = keys()
        val ids = hiddenIds().toHashSet()
        if (ex.isEmpty() && ids.isEmpty()) return base.assets(category, limit)
        val kept = base.assets(category, null).filter { it.id !in ids && !isExcluded(it, ex) }
        return if (limit != null) kept.take(limit) else kept
    }

    override fun count(category: MediaCategory): Int =
        if (keys().isEmpty() && hiddenIds().isEmpty()) base.count(category) else assets(category, null).size

    private fun keys(): List<String> = excluded().map { MediaFolders.normalize(it).lowercase() }.filter { it.isNotEmpty() }.distinct()

    companion object {
        fun isExcluded(a: MediaAsset, keys: List<String>): Boolean {
            val p = a.relativePath?.let { MediaFolders.normalize(it).lowercase() } ?: return false
            return keys.any { p.startsWith(it) }
        }
    }
}
