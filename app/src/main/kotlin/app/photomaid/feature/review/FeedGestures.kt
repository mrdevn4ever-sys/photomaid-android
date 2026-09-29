package app.photomaid.feature.review

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Density
import kotlin.math.hypot

/**
 * Rohe Fingerbewegung → [FeedViewModel] → [app.photomaid.core.feed.FeedGestureMachine].
 * Diese Schicht entscheidet NICHTS: sie misst nur Translation und Geschwindigkeit (in dp) und meldet
 * Zwei-Finger-Zoom. Von Kind-Controls (Slider) verbrauchte Bewegungen brechen die Geste ab.
 */
fun Modifier.feedGestures(
    enabled: Boolean,
    density: Density,
    onDrag: (Offset) -> Unit,
    onEnd: (Offset, Offset) -> Unit,
    onCancel: () -> Unit,
    onZoom: (Float) -> Unit,
    onDoubleTap: () -> Unit,
): Modifier = if (!enabled) this else pointerInput(density) {
    var lastTapUp = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        if (down.uptimeMillis - lastTapUp < 280) { onDoubleTap(); lastTapUp = 0L }
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        val start = down.position
        var total = Offset.Zero
        var zoomStartDist = 0f
        var zooming = false
        var cancelled = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                // Zwei Finger: Zoom. Keine Entscheidungsgeste.
                val d = hypot((pressed[0].position.x - pressed[1].position.x).toDouble(), (pressed[0].position.y - pressed[1].position.y).toDouble()).toFloat()
                if (!zooming) { zooming = true; zoomStartDist = d; onCancel() } else if (zoomStartDist > 0f) onZoom(d / zoomStartDist)
                event.changes.forEach { it.consume() }
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            if (!change.pressed) {
                if (zooming || cancelled) break
                tracker.addPosition(change.uptimeMillis, change.position)
                val v = tracker.calculateVelocity()
                val moved = hypot(total.x.toDouble(), total.y.toDouble())
                if (moved < 4.0) lastTapUp = change.uptimeMillis
                with(density) { onEnd(Offset(total.x.toDp().value, total.y.toDp().value), Offset(v.x.toDp().value, v.y.toDp().value)) }
                break
            }
            if (zooming || cancelled) continue
            if (change.isConsumed && change.positionChange() != Offset.Zero) {
                // Ein Kind-Control (z. B. Wiedergabeposition) bedient die Bewegung.
                cancelled = true; onCancel(); continue
            }
            tracker.addPosition(change.uptimeMillis, change.position)
            total = change.position - start
            with(density) { onDrag(Offset(total.x.toDp().value, total.y.toDp().value)) }
        }
        if (currentEvent.changes.any { it.pressed }) onCancel()
    }
}
