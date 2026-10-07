package app.photomaid.core.collections

import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.later.ReviewQueues
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.core.model.UUIDSerializer
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Woraus eine Sammlung schöpft (Mediathek; für Dateien in Ordnern gibt es „Große Dateien“). */
@Serializable
enum class CollectionSource { PHOTOS, VIDEOS, SCREENSHOTS }

/** Eine einzige, verständliche Bedingung. Keine Skripte, keine KI. */
@Serializable
sealed interface CollectionFilter {
    /** „größer als …“ – nur Elemente mit bekannter Größe; unbekannt zählt nie als groß (und nie als 0). */
    @Serializable data class LargerThan(val bytes: Long) : CollectionFilter
    /** „älter als …“ – nach Aufnahme-/Erstelldatum; ohne Datum nicht enthalten. */
    @Serializable data class OlderThan(val days: Int) : CollectionFilter
}

@Serializable
@JvmInline
value class SmartCollectionID(@Serializable(UUIDSerializer::class) val rawValue: UUID = UUID.randomUUID())

/**
 * Dauerhafte Sammlung (früher „Regel“): immer aktuell, jederzeit antippbar. Kein Auslöser, keine Aufgaben,
 * keine Hintergrundarbeit, niemals Löschung – sie öffnet nur den Feed mit den passenden, noch offenen Medien.
 */
@Serializable
data class SmartCollection(
    val id: SmartCollectionID = SmartCollectionID(),
    val source: CollectionSource,
    val filter: CollectionFilter,
    /** Fertige Sammlungen (für alle); eigene Sammlungen sind Pro. */
    val builtIn: Boolean = false,
)

/** Ein tatsächlich gelesenes Element (MediaStore bzw. verbundener Ordner). */
data class CollectionCandidate(val item: ItemReference, val createdAt: Instant?, val size: KnownSize)

/** Aktueller Stand einer Sammlung. */
data class CollectionResult(
    /** Noch offene passende Elemente in Feed-Reihenfolge (wie die normale Prüfung: offen oder „später ohne Termin“). */
    val items: List<ItemReference>,
    /** Summe der bekannten Größen dieser Elemente. */
    val knownBytes: Long,
    /** Elemente der Quelle, deren Größe unbekannt ist (bei „größer als“ nicht enthalten – nur als Hinweis). */
    val unknownSizeCount: Int,
)

object SmartCollections {
    /** Fertige Sammlungen. Feste IDs, damit Zustand/Tests stabil bleiben. */
    val builtIns: List<SmartCollection> = listOf(
        SmartCollection(fixed(1), CollectionSource.VIDEOS, CollectionFilter.LargerThan(100L * MB), builtIn = true),
        SmartCollection(fixed(2), CollectionSource.SCREENSHOTS, CollectionFilter.OlderThan(30), builtIn = true),
        SmartCollection(fixed(3), CollectionSource.PHOTOS, CollectionFilter.LargerThan(10L * MB), builtIn = true),
        SmartCollection(fixed(4), CollectionSource.VIDEOS, CollectionFilter.OlderThan(365), builtIn = true),
        SmartCollection(fixed(5), CollectionSource.PHOTOS, CollectionFilter.OlderThan(365), builtIn = true),
    )

    /** Schnellwahl je Quelle – passend zur Medienart statt eines +/−-Zählers. */
    fun sizeChoices(source: CollectionSource): List<Long> = when (source) {
        CollectionSource.VIDEOS -> listOf(50L * MB, 100L * MB, 500L * MB, 1_000L * MB)
        CollectionSource.PHOTOS, CollectionSource.SCREENSHOTS -> listOf(2L * MB, 5L * MB, 10L * MB, 20L * MB)
    }

    val ageChoices: List<Int> = listOf(30, 182, 365, 730)

    fun evaluate(
        collection: SmartCollection,
        candidates: List<CollectionCandidate>,
        records: Map<ItemReference, ItemRecord>,
        now: Instant,
        zone: ZoneId,
    ): CollectionResult {
        val today = LocalDay.of(now, zone)
        val matching = candidates.filter { c ->
            when (val f = collection.filter) {
                is CollectionFilter.LargerThan -> c.size.bytesOrNull?.let { it > f.bytes } ?: false
                is CollectionFilter.OlderThan -> c.createdAt?.let { LocalDay.of(it, zone).addingDays(f.days) <= today } ?: false
            }
        }
        // Gleiche Offen-Logik wie die normale Prüfung (entschiedene Elemente tauchen nicht wieder auf).
        val open = ReviewQueues.normalQueue(matching.map { it.item }, records, ReviewSessionID(), today)
        val openSet = open.toSet()
        val known = matching.filter { it.item in openSet }.sumOf { it.size.bytesOrNull ?: 0L }
        val unknown = if (collection.filter !is CollectionFilter.LargerThan) 0 else
            ReviewQueues.normalQueue(candidates.filter { it.size.bytesOrNull == null }.map { it.item }, records, ReviewSessionID(), today).size
        return CollectionResult(open, known, unknown)
    }

    const val MB = 1_000_000L
    private fun fixed(n: Int) = SmartCollectionID(UUID(0x50686f746f4d6169L, n.toLong()))
}
