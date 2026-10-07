package app.photomaid.core.cleanup

import app.photomaid.core.model.InstantSerializer
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.OperationID
import kotlinx.serialization.Serializable
import java.time.Instant

/** Konkrete Systemoperation. Medien und Dateien verwenden bewusst verschiedene APIs. */
@Serializable
enum class CleanupOperationKind {
    /**
     * `MediaStore.createTrashRequest`: Das System zeigt eine eigene Bestätigung; Medien landen
     * im System-Papierkorb (Android iOS-Pendant zu „Zuletzt gelöscht“).
     */
    PHOTO_LIBRARY_DELETE,

    /** Nur, wenn eine Quelle nachweislich einen Papierkorb anbietet. Nicht angeboten. */
    FILE_MOVE_TO_TRASH,

    /** `DocumentsContract.deleteDocument` nach eigener, deutlicher Bestätigung. Kein Undo. */
    FILE_DELETE_PERMANENTLY,

    /**
     * `MediaStore.createDeleteRequest`: nur, wenn der Nutzer „Endgültig löschen“ in den Einstellungen einschaltet
     * (Standard aus). Das System bestätigt; danach gibt es kein Zurück (nicht im Android-Papierkorb).
     */
    PHOTO_LIBRARY_DELETE_PERMANENTLY,
}

@Serializable
enum class OperationState {
    /** Journal geschrieben, Kapazität reserviert, Systemoperation noch nicht gestartet. */
    PREPARED,

    /** Systemoperation wurde gestartet. Ergebnis ausstehend. */
    SUBMITTED,
    SUCCEEDED,
    PARTIALLY_SUCCEEDED,
    CANCELLED,
    FAILED,

    /** Prozessabbruch mit unklarem Ergebnis. Keine automatische Wiederholung. */
    RECONCILIATION_REQUIRED;

    val isTerminal: Boolean get() = this == SUCCEEDED || this == PARTIALLY_SUCCEEDED || this == CANCELLED || this == FAILED
}

/** Dauerhaft gespeicherte Absicht vor einer Systemoperation. */
@Serializable
data class OperationIntent(
    val id: OperationID,
    val kind: CleanupOperationKind,
    /** Item → Revision zum Zeitpunkt der Freigabe. */
    val items: Map<ItemReference, Int>,
    /** Tag, auf den die Kapazität gebucht wird (lokaler Kalendertag bei Freigabe). */
    val allowanceDay: LocalDay,
    /** Reservierte Tageskapazität (0 bei unbegrenzter Pro-Bereinigung). */
    val reservedCount: Int,
    val state: OperationState,
    @Serializable(InstantSerializer::class) val createdAt: Instant,
    @Serializable(InstantSerializer::class) val updatedAt: Instant = createdAt,
    /** Tatsächlich bestätigt entfernte Items (nach Abgleich). */
    val confirmedRemoved: Set<ItemReference> = emptySet(),
)

/** Ergebnis einer Provider-Operation, so wie die jeweilige API es belegt. */
sealed interface OperationOutcome {
    data object Succeeded : OperationOutcome
    data class Partial(val removed: Set<ItemReference>) : OperationOutcome

    /** Nutzer hat die System-Bestätigung abgebrochen. */
    data object Cancelled : OperationOutcome

    /** Fehler ohne belegten Teilerfolg: es gilt „nichts entfernt“. */
    data class Failed(val reason: String) : OperationOutcome
}

/** Ergebnis einer Existenzprüfung beim Abgleich. */
enum class ExistenceProbe {
    EXISTS,

    /** Nicht mehr auffindbar, und der Zugriff war vollständig (nicht eingeschränkt/abgelehnt). */
    MISSING_WITH_FULL_ACCESS,

    /** Kein Beweis möglich (fehlender Zugriff, Provider-Fehler). Kein Löschbeweis. */
    UNKNOWN,
}

sealed class CleanupError(message: String) : Exception(message) {
    data object EmptySelection : CleanupError("empty") { private fun readResolve(): Any = EmptySelection }
    data class NotMarked(val item: ItemReference) : CleanupError("notMarked")
    data class StaleRevision(val item: ItemReference) : CleanupError("stale")
    data class ItemBusy(val item: ItemReference) : CleanupError("busy")

    /** Mehr gewählt als heute verfügbar. */
    data class AllowanceExceeded(val requested: Int, val available: Int) : CleanupError("allowance")
    data object UnknownOperation : CleanupError("unknown") { private fun readResolve(): Any = UnknownOperation }
    data class InvalidState(val state: OperationState) : CleanupError("state $state")
    data class AliasConflict(val item: ItemReference) : CleanupError("alias")
    data class PersistenceFailed(val reason: String) : CleanupError(reason)
}

/** Tageskontingent für bestätigte Bereinigungen (Free: 20 pro lokalem Kalendertag). */
@Serializable
data class DayUsage(
    val confirmed: Int = 0,
    val reservations: Map<OperationID, Int> = emptyMap(),
) {
    val reserved: Int get() = reservations.values.sum()
}

data class AllowanceSnapshot(val day: LocalDay, val limit: Int?, val confirmed: Int, val reserved: Int) {
    /** `null` = unbegrenzt. */
    val available: Int? get() = limit?.let { maxOf(0, it - confirmed - reserved) }
}
