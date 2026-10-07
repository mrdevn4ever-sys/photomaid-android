package app.photomaid.core.ads

import app.photomaid.core.model.LocalDay
import kotlinx.serialization.Serializable

/**
 * Zähler für Werbekarten im Feed (nur Free). Gezählt werden Entscheidungen (Behalten, Papierkorb, Später),
 * nicht „Zurück“, nicht Favorit/Teilen/Ordner. Lokal gespeichert, keine Medien-IDs.
 */
@Serializable
data class FeedAdState(
    val decisionsTotal: Int = 0,
    val sinceLastAd: Int = 0,
    val day: LocalDay? = null,
    val shownOnDay: Int = 0,
    /** Wanduhr (ms) der letzten Werbekarte im Feed – für den Mindestabstand [FeedAdPolicy.MIN_GAP_MS]. */
    val lastCardAtMs: Long = 0,
)

/** Einzige Stelle für Takt, Schonfrist und Tagesgrenze der Werbekarte. */
object FeedAdPolicy {
    /** Nach je 7 Entscheidungen eine Werbekarte (Produktentscheidung 2026-09-30). */
    const val INTERVAL = 7
    /** Die ersten 30 Entscheidungen nach der Installation bleiben werbefrei. */
    const val WARMUP_DECISIONS = 30
    /** Höchstens 30 Anzeigen (Feed-Karte + Zusammenfassung) pro lokalem Kalendertag (Produktentscheidung 2026-10-02). */
    const val DAILY_CAP = 30
    /** Wegwischen erst nach dieser Zeit (keine versehentlichen Klicks, „bisschen nerven“). */
    const val MIN_VISIBLE_MS = 1_500L
    /** Zusätzlich zum Takt: mindestens 60 s seit der letzten Karte (schnelles Wischen = nicht öfter Werbung). */
    const val MIN_GAP_MS = 60_000L

    /** Abstand eingehalten? Eine zurückgestellte Uhr (jetzt < letzte Karte) sperrt nicht dauerhaft. */
    fun gapElapsed(s: FeedAdState, nowMs: Long): Boolean =
        s.lastCardAtMs == 0L || nowMs < s.lastCardAtMs || nowMs - s.lastCardAtMs >= MIN_GAP_MS

    fun shownToday(s: FeedAdState, today: LocalDay): Int = if (s.day == today) s.shownOnDay else 0

    fun onDecision(s: FeedAdState): FeedAdState = s.copy(decisionsTotal = s.decisionsTotal + 1, sinceLastAd = s.sinceLastAd + 1)

    fun isDue(s: FeedAdState, today: LocalDay, nowMs: Long = Long.MAX_VALUE): Boolean =
        s.decisionsTotal >= WARMUP_DECISIONS && s.sinceLastAd >= INTERVAL && shownToday(s, today) < DAILY_CAP && gapElapsed(s, nowMs)

    /**
     * Anzeige unter der Sitzungs-Zusammenfassung: nach der Schonfrist und innerhalb der gemeinsamen Tagesgrenze.
     * Kein Takt – die Zusammenfassung kommt höchstens einmal pro Durchgang. Zählt wie eine Karte ([onShown]).
     */
    fun summaryAdAllowed(s: FeedAdState, today: LocalDay): Boolean =
        s.decisionsTotal >= WARMUP_DECISIONS && shownToday(s, today) < DAILY_CAP

    fun onShown(s: FeedAdState, today: LocalDay): FeedAdState =
        s.copy(sinceLastAd = 0, day = today, shownOnDay = shownToday(s, today) + 1)

    /** Werbekarte im Feed gezeigt: wie [onShown] und Zeitpunkt für den Mindestabstand merken. */
    fun onCardShown(s: FeedAdState, today: LocalDay, nowMs: Long): FeedAdState = onShown(s, today).copy(lastCardAtMs = nowMs)
}
