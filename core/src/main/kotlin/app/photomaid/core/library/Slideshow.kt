package app.photomaid.core.library

import kotlin.random.Random

/** Reihenfolge der Diashow (nur Ansehen, nie eine Entscheidung). */
enum class SlideshowOrder { CHRONOLOGICAL, RANDOM, ALBUMS_RANDOM }

/** Übergang zwischen zwei Bildern. */
enum class SlideshowTransition { FADE, NONE }

/** Einstellungen der Diashow (gespeichert, keine Medien-IDs). */
@kotlinx.serialization.Serializable
data class SlideshowSettings(
    val order: SlideshowOrder = SlideshowOrder.CHRONOLOGICAL,
    /** Bei „Album für Album“: innerhalb eines Ordners zufällig (sonst chronologisch). */
    val shuffleWithinAlbum: Boolean = false,
    val seconds: Int = 5,
    val transition: SlideshowTransition = SlideshowTransition.FADE,
    /** Videos ganz abspielen (stumm) statt überspringen. */
    val playVideos: Boolean = true,
    val loop: Boolean = false,
) {
    companion object { val SECONDS = listOf(3, 5, 8, 12) }
}

/**
 * Reihenfolge einer Diashow. Fester Zufalls-Startwert je Start (wiederholbar, testbar).
 * - Chronologisch: älteste zuerst; Medien ohne Datum am Ende (keine erfundenen Daten).
 * - Zufällig: alle gemischt.
 * - Album für Album zufällig: Ordner in zufälliger Reihenfolge, jeder Ordner zusammenhängend; innen chronologisch
 *   oder gemischt. Medien ohne Ordner bilden einen eigenen Block.
 */
object Slideshow {
    fun <T> order(items: List<T>, settings: SlideshowSettings, seed: Long, folder: (T) -> String?, date: (T) -> Long?): List<T> {
        val rnd = Random(seed)
        fun chrono(list: List<T>) = list.sortedWith(compareBy<T> { if (date(it) == null) 1 else 0 }.thenBy { date(it) ?: 0L })
        return when (settings.order) {
            SlideshowOrder.CHRONOLOGICAL -> chrono(items)
            SlideshowOrder.RANDOM -> items.shuffled(rnd)
            SlideshowOrder.ALBUMS_RANDOM -> items.groupBy { folder(it)?.let(FolderTree::normalize)?.lowercase() ?: "" }
                .entries.sortedBy { it.key }.shuffled(rnd)
                .flatMap { (_, v) -> if (settings.shuffleWithinAlbum) v.shuffled(rnd) else chrono(v) }
        }
    }
}
