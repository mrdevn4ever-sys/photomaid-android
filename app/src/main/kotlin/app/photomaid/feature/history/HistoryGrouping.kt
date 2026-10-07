package app.photomaid.feature.history

import app.photomaid.core.decisions.DecisionEvent
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.HistoryKind

/**
 * Eine sichtbare Verlaufszeile. Reine Darstellung: das Journal bleibt unverändert, jedes Event bleibt erhalten.
 * [schedule] ist der unmittelbar zugehörige erste Termin eines „Auf später gelegt“.
 */
data class HistoryEntry(
    val primary: DecisionEvent,
    val schedule: DecisionEvent? = null,
    /** Weitere Items desselben Systemvorgangs (gleiche `operationID`, gleiche Art), z. B. ein abgebrochenes Leeren. */
    val batch: List<DecisionEvent> = emptyList(),
) {
    val events: List<DecisionEvent> get() = listOfNotNull(primary, schedule) + batch
    /** Anzahl betroffener Items dieser Zeile. */
    val itemCount: Int get() = 1 + batch.size
}

/**
 * Fasst „Auf später gelegt“ und den direkt folgenden ersten Termin zu einem Vorgang zusammen.
 * Nur bei eindeutiger Beziehung: gleiches Item, unmittelbar folgende Revision (dazwischen kann nichts anderes
 * mit dem Item passiert sein, auch kein Undo) und der Termin setzt genau den Zustand fort, den das Später
 * hinterlassen hat (noch ohne Datum). Keine Gruppierung nach Uhrzeit, Ähnlichkeit oder Session.
 * Außerdem: direkt aufeinanderfolgende Events desselben Systemvorgangs (gleiche `operationID`, gleiche Art –
 * z. B. zehn „Bereinigung nicht ausgeführt“ nach einem abgebrochenen Systemdialog) werden eine Zeile mit Anzahl.
 * Alle anderen Events bleiben einzelne Zeilen. [chronological] ist aufsteigend sortiert (wie das Journal).
 */
fun groupHistory(chronological: List<DecisionEvent>): List<HistoryEntry> {
    val laterByItemRevision = HashMap<Pair<Any, Int>, Int>()
    val out = ArrayList<HistoryEntry>(chronological.size)
    for (e in chronological) {
        if (e.kind == HistoryKind.SCHEDULE_CHANGED) {
            val idx = laterByItemRevision.remove(e.item to (e.revision - 1))
            if (idx != null && out[idx].primary.to == e.from && e.from.dueDay == null) {
                out[idx] = out[idx].copy(schedule = e)
                continue
            }
        }
        val op = e.operationID
        val last = out.lastOrNull()
        if (op != null && last != null && last.schedule == null && last.primary.operationID == op && last.primary.kind == e.kind) {
            out[out.size - 1] = last.copy(batch = last.batch + e)
            continue
        }
        val isLater = (e.kind == HistoryKind.DECISION || e.kind == HistoryKind.CORRECTION) &&
            e.to.disposition == Disposition.LATER && e.to.dueDay == null
        if (isLater) laterByItemRevision[e.item to e.revision] = out.size
        out += HistoryEntry(e)
    }
    return out
}
