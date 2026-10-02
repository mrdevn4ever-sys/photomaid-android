package app.photomaid.core.decisions

import app.photomaid.core.model.CommandID
import app.photomaid.core.model.InstantSerializer
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.OperationID
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.core.model.UUIDSerializer
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * PhotoMaid-Status eines Items. „Behalten“ ist ein PhotoMaid-Status und setzt keinen Favoriten.
 */
@Serializable
enum class Disposition {
    UNDECIDED, KEPT, MARKED_FOR_CLEANUP, LATER,

    /** Nach bestätigter Systemoperation entfernt (Medien: System-Papierkorb, Dateien: je Operation). */
    REMOVED,
}

/** Der fachliche Anteil ohne Revision (für Idempotenz- und Undo-Vergleiche). */
@Serializable
data class DecisionSnapshot(
    val disposition: Disposition,
    val dueDay: LocalDay? = null,
    val skippedInSession: ReviewSessionID? = null,
    /** „Später ohne Termin“: kommt nie von selbst zurück, nur über die Liste „Ohne Termin“. */
    val bookmarked: Boolean = false,
)

/** Aktueller, persistierter Zustand eines Items. */
@Serializable
data class ItemRecord(
    val item: ItemReference,
    /** Monoton steigend; jede Mutation erhöht die Revision, auch Undo. */
    val revision: Int = 0,
    val disposition: Disposition = Disposition.UNDECIDED,
    /** Höchstens ein aktiver Termin. Nur bei [Disposition.LATER] gesetzt. */
    val dueDay: LocalDay? = null,
    /** Nur in dieser Review-Session übersprungen (Wischen nach rechts ohne neues Datum). */
    val skippedInSession: ReviewSessionID? = null,
    /** Nur bei [Disposition.LATER]: ohne Termin beiseitegelegt (siehe [DecisionSnapshot.bookmarked]). */
    val bookmarked: Boolean = false,
) {
    val snapshot: DecisionSnapshot get() = DecisionSnapshot(disposition, dueDay, skippedInSession, bookmarked)

    fun applying(s: DecisionSnapshot) =
        copy(disposition = s.disposition, dueDay = s.dueDay, skippedInSession = s.skippedInSession, bookmarked = s.bookmarked)
}

/** Eine ausdrückliche Nutzeraktion auf einem Item. */
@Serializable
sealed interface DecisionAction {
    /** Finger nach oben. */
    @Serializable data object Keep : DecisionAction

    /** Finger nach links. In den Papierkorb, nicht löschen. */
    @Serializable data object MarkForCleanup : DecisionAction

    /** Finger nach rechts. Nur diese Session zurückstellen; ein vorhandener Termin bleibt. */
    @Serializable data class Later(val session: ReviewSessionID) : DecisionAction

    /** Termin für ein zurückgestelltes Item setzen/ändern. */
    @Serializable data class Schedule(val day: LocalDay) : DecisionAction

    /** Zurückgestelltes Item ohne Termin beiseitelegen („Lesezeichen“): kommt nicht von selbst zurück. */
    @Serializable data object Bookmark : DecisionAction

    /** Zurückstellung entfernen. Löscht nichts; Item wird wieder unentschieden. */
    @Serializable data object RemoveDeferral : DecisionAction

    /** Aus dem Papierkorb zurücklegen. Item wird wieder unentschieden. */
    @Serializable data object Unmark : DecisionAction
}

/**
 * Befehl mit stabiler Item-ID, erwarteter Revision und Command-ID.
 * Entscheidungen werden niemals über einen veränderlichen `currentItem`-Zeiger getroffen.
 */
@Serializable
data class DecisionCommand(
    val id: CommandID = CommandID(),
    val item: ItemReference,
    val expectedRevision: Int,
    val action: DecisionAction,
    /** Ergänzt einen früheren Befehl (Terminwahl nach Swipe rechts): gemeinsames Undo. */
    val amends: CommandID? = null,
)

@Serializable
enum class HistoryKind {
    DECISION, CORRECTION, SCHEDULE_CHANGED, DEFERRAL_REMOVED, UNMARKED, UNDO,
    CLEANUP_CONFIRMED, CLEANUP_NOT_PERFORMED, RECONCILED_MISSING,
}

/** Nachvollziehbarer Verlaufseintrag. Zeitpunkt als UTC-Instant. */
@Serializable
data class DecisionEvent(
    @Serializable(UUIDSerializer::class) val id: UUID = UUID.randomUUID(),
    val commandID: CommandID?,
    val operationID: OperationID? = null,
    val item: ItemReference,
    val kind: HistoryKind,
    val from: DecisionSnapshot,
    val to: DecisionSnapshot,
    val revision: Int,
    @Serializable(InstantSerializer::class) val at: Instant,
    val sessionID: ReviewSessionID?,
)

/** Eintrag der gemeinsamen Command-Historie für Undo. */
@Serializable
data class UndoEntry(
    val commandID: CommandID,
    val item: ItemReference,
    /** Exakter Zustand vor dem Befehl. */
    val before: DecisionSnapshot,
    val beforeRevision: Int,
    /** Revision nach dem (ggf. ergänzten) Befehl. Undo nur, wenn das Item noch diese Revision hat. */
    val afterRevision: Int,
)

sealed class DecisionError(message: String) : Exception(message) {
    /** Das Item wurde inzwischen verändert (andere Revision). */
    data class StaleRevision(val expected: Int, val actual: Int) : DecisionError("stale $expected/$actual")

    data class InvalidTransition(val from: Disposition, val action: String) : DecisionError("invalid $from/$action")

    /** Item ist Teil einer laufenden Systemoperation. */
    data object ItemBusy : DecisionError("busy") { private fun readResolve(): Any = ItemBusy }

    data object NothingToUndo : DecisionError("nothing") { private fun readResolve(): Any = NothingToUndo }

    /** Undo würde eine neuere Entscheidung überschreiben. */
    data object UndoConflict : DecisionError("conflict") { private fun readResolve(): Any = UndoConflict }

    /** Persistenz fehlgeschlagen; Zustand unverändert. */
    data class PersistenceFailed(val reason: String) : DecisionError(reason)
}

sealed interface CommitOutcome {
    val record: ItemRecord

    /** Zustand geändert und persistiert. */
    data class Applied(override val record: ItemRecord) : CommitOutcome

    /** Dieselbe Entscheidung erneut: keine Änderung, keine doppelten Zähler. */
    data class Unchanged(override val record: ItemRecord) : CommitOutcome

    /** Dieselbe Command-ID wurde bereits verarbeitet (Wiederholung). */
    data class Duplicate(override val record: ItemRecord) : CommitOutcome
}
