package app.photomaid.core.feed

import kotlin.math.abs
import kotlin.math.max

/** Tatsächliche Fingerbewegung (Bildschirmkoordinaten: y wächst nach unten). */
enum class SwipeDirection {
    UP, DOWN, LEFT, RIGHT,
}

/** Bedeutung einer Fingerbewegung im Entscheidungs-Feed. FEST verdrahtet. */
enum class FeedIntent {
    /** hoch: Behalten, danach nächstes Medium. */
    KEEP,

    /** links: In den PhotoMaid-Papierkorb (nichts wird gelöscht), danach nächstes Medium. */
    MARK_FOR_CLEANUP,

    /** rechts (gerade oder schräg nach unten): Später mit Standardtermin, danach nächstes Medium. */
    LATER,

    /**
     * Später mit selbst gewähltem Termin. Keine Geste mehr (Produktentscheidung 2026-09-29: die Diagonale entfällt,
     * der Termin wird über „Ändern“ in der Pille gewählt). Nur noch als TalkBack-/Schalter-Aktion; öffnet erst die
     * Auswahl, erst Bestätigen = Später + nächstes Medium.
     */
    LATER_CUSTOM,

    /** runter: vorheriges Medium. Keine Entscheidung. */
    PREVIOUS;

    val isDecision: Boolean get() = this != PREVIOUS

    companion object {
        fun of(direction: SwipeDirection): FeedIntent = when (direction) {
            SwipeDirection.UP -> KEEP
            SwipeDirection.LEFT -> MARK_FOR_CLEANUP
            SwipeDirection.RIGHT -> LATER
            SwipeDirection.DOWN -> PREVIOUS
        }
    }
}

data class Vector2(val dx: Double, val dy: Double) {
    companion object { val ZERO = Vector2(0.0, 0.0) }
}

/** Schwellen in dp (iOS: pt – beide dichteunabhängig). */
data class GestureConfig(
    /** Unterhalb dieser Strecke keine Richtung. */
    val deadZone: Double = 14.0,
    /** Dominante Achse muss um diesen Faktor größer sein (Diagonalen lösen nichts aus). */
    val lockRatio: Double = 1.6,
    /** Hysterese: Bindung löst sich erst unterhalb dieser Strecke entlang der Achse. */
    val unlockDistance: Double = 6.0,
    /** Mindeststrecke für einen Commit ohne Schwung. */
    val commitDistance: Double = 110.0,
    /** Schwung (dp/s) entlang der Richtung, der zusammen mit [flickMinDistance] genügt. */
    val flickVelocity: Double = 650.0,
    val flickMinDistance: Double = 36.0,
    /** Rechts schräg nach oben bis 45° (|dy|/|dx| ≤ 1) zählt als „rechts“. Darüber bis „hoch“: Totzone. */
    val rightUpMaxRatio: Double = 1.0,
    /** Rechts schräg nach unten bis 45° zählt als „rechts“. Darüber bis „runter“: Totzone. */
    val rightDownMaxRatio: Double = 1.0,
)

/**
 * Zentrale Gesten-State-Machine des Feeds (einzige im Projekt).
 *
 * idle → dragging → directionLocked → commitRequested → (persistiert) → idle (nächstes Item)
 *
 * Eine abgebrochene Geste führt zurück nach `idle`, ohne Entscheidung. Diese Klasse kennt kein
 * „aktuelles Item“: Der Commit wird von [FeedSession] mit Item-ID, Revision und Command-ID versehen.
 */
class FeedGestureMachine(val config: GestureConfig = GestureConfig()) {
    sealed interface Phase {
        data object Idle : Phase
        data class Dragging(val t: Vector2) : Phase
        data class DirectionLocked(val direction: SwipeDirection, val t: Vector2) : Phase

        /** Wartet auf Persistenz bzw. Navigation. Weitere Gesten werden ignoriert. */
        data class CommitRequested(val direction: SwipeDirection) : Phase
    }

    sealed interface Resolution {
        data class Commit(val direction: SwipeDirection) : Resolution
        data object Cancelled : Resolution
        data object Ignored : Resolution
    }

    var phase: Phase = Phase.Idle
        private set

    /** Während Zoom, Control-Bedienung oder TalkBack: keine Entscheidungsgesten. */
    var isSuspended = false

    /** Versatz, dem das Medium folgt. Nach Richtungsbindung nur noch entlang der Achse. */
    val displayOffset: Vector2
        get() = when (val p = phase) {
            Phase.Idle, is Phase.CommitRequested -> Vector2.ZERO
            is Phase.Dragging -> p.t
            is Phase.DirectionLocked -> when (p.direction) {
                SwipeDirection.UP, SwipeDirection.DOWN -> Vector2(0.0, p.t.dy)
                SwipeDirection.LEFT, SwipeDirection.RIGHT -> Vector2(p.t.dx, 0.0)
            }
        }

    val lockedDirection: SwipeDirection?
        get() = when (val p = phase) {
            is Phase.DirectionLocked -> p.direction
            is Phase.CommitRequested -> p.direction
            else -> null
        }

    fun update(t: Vector2) {
        if (isSuspended) return
        when (val p = phase) {
            is Phase.CommitRequested -> return
            Phase.Idle, is Phase.Dragging ->
                phase = lockCandidate(t)?.let { Phase.DirectionLocked(it, t) } ?: Phase.Dragging(t)
            is Phase.DirectionLocked -> {
                val d = p.direction
                if (d == SwipeDirection.RIGHT) {
                    // Rechts: der aktuelle Winkel entscheidet; Totzonen lösen die Bindung.
                    phase = if (t.dx < config.unlockDistance) Phase.Dragging(t)
                    else lockCandidate(t)?.let { Phase.DirectionLocked(it, t) } ?: Phase.Dragging(t)
                } else if (along(d, t) < config.unlockDistance) {
                    // Hysterese: erst deutlich zurück, dann ggf. neue Richtung.
                    phase = lockCandidate(t)?.let { Phase.DirectionLocked(it, t) } ?: Phase.Dragging(t)
                } else {
                    phase = Phase.DirectionLocked(d, t)
                }
            }
        }
    }

    /** Finger losgelassen. */
    fun end(t: Vector2, v: Vector2): Resolution {
        if (isSuspended) {
            phase = Phase.Idle
            return Resolution.Ignored
        }
        return when (val p = phase) {
            is Phase.CommitRequested -> Resolution.Ignored
            Phase.Idle -> Resolution.Ignored
            is Phase.Dragging -> {
                phase = Phase.Idle
                Resolution.Cancelled
            }
            is Phase.DirectionLocked -> {
                val d = p.direction
                val distance = along(d, t)
                val speed = along(d, v)
                val offAxis = across(d, t)
                val clearlyDirected = if (d == SwipeDirection.RIGHT) {
                    lockCandidate(t) == d
                } else {
                    offAxis * config.lockRatio <= max(distance, 0.0)
                }
                if (clearlyDirected && (distance >= config.commitDistance ||
                        (speed >= config.flickVelocity && distance >= config.flickMinDistance))
                ) {
                    phase = Phase.CommitRequested(d)
                    Resolution.Commit(d)
                } else {
                    phase = Phase.Idle
                    Resolution.Cancelled
                }
            }
        }
    }

    /** Systemabbruch (Unterbrechung, Hintergrund, Rotation). Keine Entscheidung. */
    fun cancel() { phase = Phase.Idle }

    /** Nach Persistenz bzw. abgeschlossener Navigation (oder Rücksetzen bei Fehler). */
    fun finishCommit() { phase = Phase.Idle }

    // Geometrie

    private fun lockCandidate(t: Vector2): SwipeDirection? {
        val ax = abs(t.dx)
        val ay = abs(t.dy)
        if (max(ax, ay) < config.deadZone) return null
        if (ay >= ax * config.lockRatio) return if (t.dy < 0) SwipeDirection.UP else SwipeDirection.DOWN
        if (t.dx < 0) return if (ax >= ay * config.lockRatio) SwipeDirection.LEFT else null
        val ratio = ay / ax
        val max = if (t.dy < 0) config.rightUpMaxRatio else config.rightDownMaxRatio
        return if (ratio <= max) SwipeDirection.RIGHT else null
    }

    /** Strecke in Richtung `d` (negativ bei Gegenrichtung). */
    private fun along(d: SwipeDirection, t: Vector2): Double = when (d) {
        SwipeDirection.UP -> -t.dy
        SwipeDirection.DOWN -> t.dy
        SwipeDirection.LEFT -> -t.dx
        SwipeDirection.RIGHT -> t.dx
    }

    private fun across(d: SwipeDirection, t: Vector2): Double = when (d) {
        SwipeDirection.UP, SwipeDirection.DOWN -> abs(t.dx)
        else -> abs(t.dy)
    }
}
