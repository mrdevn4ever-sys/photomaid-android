package app.photomaid.core.feed

import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.random.Random

/** Reihenfolge der offenen Medien im Feed. Ändert nur die Abfolge, nie welche Medien offen sind. */
@Serializable
enum class FeedOrder { NEWEST, OLDEST, LARGEST, RANDOM }

object FeedOrdering {
    /**
     * Sortiert stabil: bei gleichem Schlüssel entscheidet die ID. Unbekanntes Datum/unbekannte Größe stehen immer am Ende
     * (unbekannt ≠ 0). [RANDOM] mischt mit festem Startwert je Sitzung, damit „runter“ zuverlässig zurückführt.
     */
    fun <T> apply(items: List<T>, order: FeedOrder, date: (T) -> Instant?, size: (T) -> Long?, id: (T) -> String, seed: Long = 0): List<T> =
        when (order) {
            FeedOrder.NEWEST -> items.sortedWith(compareBy<T> { date(it) == null }.thenByDescending { date(it) }.thenBy(id))
            FeedOrder.OLDEST -> items.sortedWith(compareBy<T> { date(it) == null }.thenBy { date(it) }.thenBy(id))
            FeedOrder.LARGEST -> items.sortedWith(compareBy<T> { size(it) == null }.thenByDescending { size(it) }.thenBy(id))
            FeedOrder.RANDOM -> items.sortedBy(id).shuffled(Random(seed))
        }
}
