package app.photomaid.feature.review

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Density
import kotlin.math.hypot

/**
 * Rohe Fingerbewegung → [FeedViewModel] → [app.photomaid.core.feed.FeedGestureMachine].
 * Diese Schicht entscheidet NICHTS: sie misst nur Translation und Geschwindigkeit (in dp) und meldet
 * Zwei-Finger-Zoom. Von Kind-Controls (Slider) verbrauchte Bewegungen brechen die Geste ab.
 * Einfacher Tipp ([onTap], z. B. Video Pause/Play wie TikTok) wird erst gemeldet, wenn kein zweiter Tipp folgt
 * (Doppeltipp = Zoom zurücksetzen). Von Kind-Controls verbrauchte Tipps zählen nicht.
 */
fun Modifier.feedGestures(
    enabled: Boolean,
    density: Density,
    onDrag: (Offset) -> Unit,
    onEnd: (Offset, Offset) -> Unit,
    onCancel: () -> Unit,
    onZoom: (Float) -> Unit,
    onDoubleTap: () -> Unit,
    onTap: () -> Unit = {},
    /** Nur wenn ein Doppeltipp etwas bewirkt (gezoomt), wartet der einfache Tipp 280 ms; sonst reagiert er sofort. */
    waitForDoubleTap: () -> Boolean = { true },
    /**
     * `true` (Feed): übernimmt ein Kind-Control den Finger (Fortschrittsleiste), ist es keine Entscheidungsgeste.
     * `false` (Werbekarte): die Anzeige (`NativeAdView`) nimmt jede Berührung an – Wischen muss trotzdem wirken.
     */
    yieldToChildren: Boolean = true,
): Modifier = if (!enabled) this else composed {
    // Immer die aktuellen Callbacks verwenden, ohne die laufende Geste neu zu starten.
    val drag by rememberUpdatedState(onDrag); val end by rememberUpdatedState(onEnd); val cancel by rememberUpdatedState(onCancel)
    val zoom by rememberUpdatedState(onZoom); val doubleTap by rememberUpdatedState(onDoubleTap); val tap by rememberUpdatedState(onTap)
    val waitDouble by rememberUpdatedState(waitForDoubleTap)
    pointerInput(density) { coroutineScope {
    var lastTapUp: Long? = null
    var pendingTap: Job? = null
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        // Ein Kind-Control (Wiedergabeposition) hat den Finger schon übernommen: keine Entscheidungsgeste.
        val childOwnsDown = yieldToChildren && down.isConsumed
        var secondTap = false
        val last = lastTapUp
        if (last != null && down.uptimeMillis - last < DOUBLE_TAP_MS) { pendingTap?.cancel(); doubleTap(); lastTapUp = null; secondTap = true }
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        val start = down.position
        var total = Offset.Zero
        var zoomStartDist = 0f
        var zooming = false
        var cancelled = childOwnsDown
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                // Zwei Finger: Zoom. Keine Entscheidungsgeste.
                val d = hypot((pressed[0].position.x - pressed[1].position.x).toDouble(), (pressed[0].position.y - pressed[1].position.y).toDouble()).toFloat()
                if (!zooming) { zooming = true; zoomStartDist = d; cancel() } else if (zoomStartDist > 0f) zoom(d / zoomStartDist)
                event.changes.forEach { it.consume() }
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            if (!change.pressed) {
                if (zooming || cancelled) break
                tracker.addPosition(change.uptimeMillis, change.position)
                val v = tracker.calculateVelocity()
                val moved = hypot(total.x.toDouble(), total.y.toDouble())
                if (moved < 4.0 && !change.isConsumed && !secondTap) {
                    pendingTap?.cancel()
                    if (waitDouble()) { lastTapUp = change.uptimeMillis; pendingTap = launch { delay(DOUBLE_TAP_MS); tap() } }
                    else { lastTapUp = null; tap() }
                }
                with(density) { end(Offset(total.x.toDp().value, total.y.toDp().value), Offset(v.x.toDp().value, v.y.toDp().value)) }
                break
            }
            if (zooming || cancelled) continue
            // positionChange() ist bei verbrauchten Änderungen immer 0 – deshalb die Rohbewegung prüfen (Gerätebefund:
            // Spulen auf der Fortschrittsleiste löste sonst „Später“ aus).
            if (yieldToChildren && change.isConsumed && change.positionChangeIgnoreConsumed() != Offset.Zero) {
                // Ein Kind-Control (z. B. Wiedergabeposition) bedient die Bewegung.
                cancelled = true; cancel(); continue
            }
            tracker.addPosition(change.uptimeMillis, change.position)
            total = change.position - start
            with(density) { drag(Offset(total.x.toDp().value, total.y.toDp().value)) }
        }
        if (currentEvent.changes.any { it.pressed }) cancel()
    }
} } }

private const val DOUBLE_TAP_MS = 280L
