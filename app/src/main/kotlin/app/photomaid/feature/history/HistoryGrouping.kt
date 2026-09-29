package app.photomaid.feature.history

import app.photomaid.core.decisions.DecisionEvent
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.HistoryKind

/**
 * Eine sichtbare Verlaufszeile. Reine Darstellung: das Journal bleibt unverändert, jedes Event bleibt erhalten.
 * [schedule] ist der unmittelbar zugehörige erste Termin eines „Auf später gelegt“.
 */
data class HistoryEntry(val primary: DecisionEvent, val schedule: DecisionEvent? = null) {
    val events: List<DecisionEvent> get() = listOfNotNull(primary, schedule)
}

/**
 * Fasst „Auf später gelegt“ und den direkt folgenden ersten Termin zu einem Vorgang zusammen.
 * Nur bei eindeutiger Beziehung: gleiches Item, unmittelbar folgende Revision (dazwischen kann nichts anderes
 * mit dem Item passiert sein, auch kein Undo) und der Termin setzt genau den Zustand fort, den das Später
 * hinterlassen hat (noch ohne Datum). Keine Gruppierung nach Uhrzeit, Ähnlichkeit oder Session.
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
        val isLater = (e.kind == HistoryKind.DECISION || e.kind == HistoryKind.CORRECTION) &&
            e.to.disposition == Disposition.LATER && e.to.dueDay == null
        if (isLater) laterByItemRevision[e.item to e.revision] = out.size
        out += HistoryEntry(e)
    }
    return out
}
