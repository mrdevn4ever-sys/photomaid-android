package app.photomaid.core.organize

import java.time.Duration
import java.time.Instant

/**
 * Fotoserien OHNE Bildanalyse: Medien aus demselben Ordner, kurz hintereinander aufgenommen. Bewusst nur so benannt
 * („Kurz hintereinander aufgenommen“) – nie „ähnlich“ oder „bestes Foto“.
 */
object PhotoSeries {
    /** Höchster Abstand zur jeweils vorigen Aufnahme. */
    val MAX_GAP: Duration = Duration.ofSeconds(10)
    const val MIN_SIZE = 3

    data class Member(val id: String, val folder: String?, val takenAt: Instant?)

    /**
     * Gruppen in Aufnahme-Reihenfolge (älteste zuerst). Medien ohne Zeit oder Ordner gehören zu keiner Serie.
     * Ordner werden ohne Groß-/Kleinschreibung verglichen; Gruppen kleiner [MIN_SIZE] fallen weg.
     */
    fun groups(items: List<Member>, maxGap: Duration = MAX_GAP, minSize: Int = MIN_SIZE): List<List<Member>> {
        val out = mutableListOf<List<Member>>()
        items.filter { it.takenAt != null && !it.folder.isNullOrBlank() }
            .groupBy { MediaFolders.normalize(it.folder!!).lowercase() }
            .values.forEach { inFolder ->
                var run = mutableListOf<Member>()
                for (m in inFolder.sortedWith(compareBy<Member> { it.takenAt }.thenBy { it.id })) {
                    val last = run.lastOrNull()
                    if (last != null && Duration.between(last.takenAt, m.takenAt) > maxGap) {
                        if (run.size >= minSize) out += run
                        run = mutableListOf()
                    }
                    run += m
                }
                if (run.size >= minSize) out += run
            }
        return out.sortedBy { it.first().takenAt }
    }

    /** Zuordnung Medium → seine Serie. */
    fun index(items: List<Member>): Map<String, List<Member>> = groups(items).flatMap { g -> g.map { it.id to g } }.toMap()
}
