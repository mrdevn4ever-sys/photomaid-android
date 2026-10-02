package app.photomaid.core.feed

import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.model.CommandID
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.ReviewSessionID
import java.util.UUID

/** Offener Commit: an Item, Revision und Command-ID gebunden – nie an `currentIndex`. */
data class PendingCommit(val command: DecisionCommand, val intent: FeedIntent, val index: Int)

/** Terminleiste/Pille nach Swipe rechts. Gehört zum zurückgestellten Item, nicht zum nächsten. */
data class LaterReceipt(
    val id: UUID,
    val item: ItemReference,
    /** Command-ID der ursprünglichen Später-Aktion (Undo nimmt Termin + Später gemeinsam zurück). */
    val laterCommandID: CommandID,
    /** Revision nach der Später-Aktion. */
    val revision: Int,
    /** `true` nach Wisch schräg nach rechts oben: Termin im Drehrad selbst wählen. */
    val wantsCustomDate: Boolean,
)

sealed interface BackNavigation {
    data class Moved(val to: Int) : BackNavigation

    /** Anfang der Session: dezenter Anschlag, keine Aktion. */
    data object AtStart : BackNavigation
}

data class SessionSummary(val kept: Int = 0, val marked: Int = 0, val later: Int = 0) {
    val reviewed: Int get() = kept + marked + later
}

/** Eine Review-Session im Feed. Reine Logik, testbar ohne UI. */
class FeedSession(val id: ReviewSessionID = ReviewSessionID(), items: List<ItemReference>) {
    var items: List<ItemReference> = items.distinct()
        private set
    var index: Int = 0
        private set
    var pending: PendingCommit? = null
        private set
    var receipt: LaterReceipt? = null
        private set

    /** Letzte Entscheidung je Item in dieser Session (Korrekturen ersetzen, kein Doppelzählen). */
    private val decisions = linkedMapOf<ItemReference, FeedIntent>()

    /** Nur Items, deren Vorschau tatsächlich geladen ist, dürfen entschieden werden. */
    private val previewReady = mutableSetOf<ItemReference>()

    val current: ItemReference? get() = items.getOrNull(index)
    val isFinished: Boolean get() = index >= items.size
    val position: Pair<Int, Int> get() = minOf(index + 1, items.size) to items.size

    val summary: SessionSummary
        get() {
            var k = 0; var m = 0; var l = 0
            for (d in decisions.values) when (d) {
                FeedIntent.KEEP -> k++
                FeedIntent.MARK_FOR_CLEANUP -> m++
                FeedIntent.LATER, FeedIntent.LATER_CUSTOM -> l++
                FeedIntent.PREVIOUS -> Unit
            }
            return SessionSummary(k, m, l)
        }

    fun markPreviewReady(item: ItemReference) { previewReady.add(item) }
    fun markPreviewUnavailable(item: ItemReference) { previewReady.remove(item) }
    fun isPreviewReady(item: ItemReference) = item in previewReady

    /**
     * Erzeugt einen Befehl für das aktuell sichtbare Item. `null`, wenn bereits ein Commit läuft,
     * die Session zu Ende ist oder die Vorschau fehlt.
     */
    fun requestDecision(intent: FeedIntent, currentRevision: Int, commandID: CommandID = CommandID()): PendingCommit? {
        if (!intent.isDecision || pending != null) return null
        val item = current ?: return null
        if (item !in previewReady) return null
        val action = when (intent) {
            FeedIntent.KEEP -> DecisionAction.Keep
            FeedIntent.MARK_FOR_CLEANUP -> DecisionAction.MarkForCleanup
            FeedIntent.LATER, FeedIntent.LATER_CUSTOM -> DecisionAction.Later(id)
            FeedIntent.PREVIOUS -> return null
        }
        val p = PendingCommit(DecisionCommand(commandID, item, currentRevision, action), intent, index)
        pending = p
        receipt = null // Neue Entscheidung schließt die alte Terminleiste.
        return p
    }

    /** Persistenz erfolgreich. Veraltete Callbacks (andere Command-ID) werden verworfen. */
    fun commitSucceeded(commandID: CommandID, record: ItemRecord): Boolean {
        val p = pending ?: return false
        if (p.command.id != commandID || record.item != p.command.item) return false
        pending = null
        decisions[p.command.item] = p.intent
        if (p.intent == FeedIntent.LATER || p.intent == FeedIntent.LATER_CUSTOM) {
            receipt = LaterReceipt(
                UUID.randomUUID(), p.command.item, commandID, record.revision,
                wantsCustomDate = p.intent == FeedIntent.LATER_CUSTOM,
            )
        }
        index = minOf(p.index + 1, items.size) // Genau ein Medium weiter.
        return true
    }

    /** Persistenz fehlgeschlagen: Item bleibt sichtbar, keine Entscheidung gezählt. */
    fun commitFailed(commandID: CommandID): Boolean {
        val p = pending ?: return false
        if (p.command.id != commandID) return false
        pending = null
        index = p.index
        return true
    }

    /** Finger nach unten: vorheriges Medium, keine Entscheidung. */
    fun goBack(): BackNavigation {
        if (pending != null || index <= 0) return BackNavigation.AtStart
        index -= 1
        return BackNavigation.Moved(index)
    }

    /** Programmgesteuertes Springen. Erzeugt niemals eine Entscheidung. */
    fun jump(to: Int) {
        if (pending != null) return
        index = to.coerceIn(0, items.size)
    }

    /**
     * Kommende Medien (ab dem aktuellen) aus der Warteschlange nehmen – ausdrücklich OHNE Entscheidung, z. B. nach
     * „Ordner ausnehmen“ oder nach einer Serienauswahl. Bereits gesehene Medien bleiben (Zurück funktioniert weiter).
     */
    fun dropUpcoming(remove: Set<ItemReference>) {
        if (pending != null || remove.isEmpty()) return
        items = items.filterIndexed { i, it -> i < index || it !in remove }
        index = index.coerceAtMost(items.size)
    }

    /** Kommende Medien neu ordnen (Reihenfolge-Wechsel). Gesehene bleiben vorne, nichts wird entschieden. */
    fun reorderUpcoming(order: (List<ItemReference>) -> List<ItemReference>) {
        if (pending != null) return
        val rest = items.drop(index)
        val sorted = order(rest)
        if (sorted.toSet() != rest.toSet()) return
        items = items.take(index) + sorted
    }

    /**
     * Bearbeitet und „Original anpassen“: das ersetzte Medium an derselben Stelle gegen die neue Datei tauschen.
     * Keine Entscheidung (die neue Datei ist im Ledger bereits „Behalten“); die Zählung der Sitzung bleibt unverändert.
     */
    fun replaceItem(old: ItemReference, new: ItemReference) {
        if (pending != null || old == new || new in items) return
        val i = items.indexOf(old)
        if (i < 0) return
        items = items.toMutableList().also { it[i] = new }
        decisions.remove(old)?.let { decisions[new] = it }
        previewReady.remove(old)
        if (receipt?.item == old) receipt = null
    }

    /** Außerhalb des Wischens getroffene Entscheidung (Serienauswahl) für die Zusammenfassung zählen. */
    fun recordDecision(item: ItemReference, intent: FeedIntent) {
        if (intent.isDecision) decisions[item] = intent
    }

    /** Befehl für einen Termin aus der Terminleiste. `null`, wenn die Leiste veraltet ist. */
    fun scheduleCommand(receiptID: UUID, day: LocalDay): DecisionCommand? {
        val r = receipt ?: return null
        if (r.id != receiptID) return null
        return DecisionCommand(item = r.item, expectedRevision = r.revision, action = DecisionAction.Schedule(day), amends = r.laterCommandID)
    }

    /** „Ohne Termin“ aus der Terminleiste (gemeinsames Undo mit der Später-Aktion). */
    fun bookmarkCommand(receiptID: UUID): DecisionCommand? {
        val r = receipt ?: return null
        if (r.id != receiptID) return null
        return DecisionCommand(item = r.item, expectedRevision = r.revision, action = DecisionAction.Bookmark, amends = r.laterCommandID)
    }

    /** Nach gespeichertem Termin: Pille bleibt änderbar, jetzt auf der neuen Revision. */
    fun receiptScheduled(receiptID: UUID, revision: Int) {
        val r = receipt ?: return
        if (r.id != receiptID) return
        receipt = r.copy(revision = revision, wantsCustomDate = false)
    }

    fun dismissReceipt(receiptID: UUID) {
        if (receipt?.id == receiptID) receipt = null
    }

    /** Nach erfolgreichem Undo: Session-Zählung an den tatsächlichen Zustand anpassen. */
    fun applyUndo(record: ItemRecord) {
        if (receipt?.item == record.item) receipt = null
        when {
            record.disposition == Disposition.KEPT -> decisions[record.item] = FeedIntent.KEEP
            record.disposition == Disposition.MARKED_FOR_CLEANUP -> decisions[record.item] = FeedIntent.MARK_FOR_CLEANUP
            record.disposition == Disposition.LATER && record.skippedInSession == id -> decisions[record.item] = FeedIntent.LATER
            else -> decisions.remove(record.item)
        }
        val i = items.indexOf(record.item)
        if (i >= 0 && pending == null) index = i
    }
}
