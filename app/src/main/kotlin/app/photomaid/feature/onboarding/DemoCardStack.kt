package app.photomaid.feature.onboarding

import app.photomaid.design.GestureIcons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.feed.FeedGestureMachine
import app.photomaid.core.feed.FeedIntent
import app.photomaid.core.feed.SwipeDirection
import app.photomaid.core.feed.Vector2
import app.photomaid.design.AppIconSize
import app.photomaid.design.AppMotion
import app.photomaid.design.AppRadii
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.LocalReduceMotion
import app.photomaid.design.PM
import app.photomaid.design.glassCapsule
import app.photomaid.feature.review.feedGestures
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Zustand eines Demo-Stapels. Nutzt dieselbe Gesten-State-Machine wie der Feed (keine zweite), speichert aber NICHTS:
 * kein Ledger, keine Medien, kein Werbe-Zähler – reines Üben.
 */
@Stable
class DemoDeck {
    val machine = FeedGestureMachine()
    val offset = Animatable(Offset.Zero, Offset.VectorConverter)
    var index by mutableIntStateOf(0)
    var locked by mutableStateOf<SwipeDirection?>(null)
    var ready by mutableStateOf(false)
    /** Zuletzt gelungene Aktion je Richtung (für die Haken unter dem Stapel). */
    var done by mutableStateOf(emptySet<FeedIntent>())
    var touched by mutableStateOf(false)
}

@Composable
fun rememberDemoDeck() = remember { DemoDeck() }

/**
 * Demo-Kartenstapel. [auto]: wischt sich selbst (Angebotsseite), solange niemand ihn berührt; reduzierte Bewegung → still.
 */
@Composable
fun DemoCardStack(deck: DemoDeck, modifier: Modifier = Modifier, auto: Boolean = false, onAction: (FeedIntent) -> Unit = {}) {
    val c = PM.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val reduce = LocalReduceMotion.current
    BoxWithConstraints(modifier) {
        val w = maxWidth.value; val h = maxHeight.value

        fun exit(intent: FeedIntent, from: Offset) = when (intent) {
            FeedIntent.KEEP -> Offset(from.x, -h * 1.3f)
            FeedIntent.MARK_FOR_CLEANUP -> Offset(-w * 1.5f, from.y)
            else -> Offset(w * 1.5f, from.y)
        }

        suspend fun act(intent: FeedIntent, velocity: Offset = Offset.Zero) {
            if (intent == FeedIntent.PREVIOUS) {
                // „runter“: vorige Demo-Karte gleitet von oben herein (wie Rückgängig im Feed), keine Aktion.
                deck.index = (deck.index - 1 + DemoArt.COUNT) % DemoArt.COUNT
                deck.offset.snapTo(Offset(0f, -h * 0.35f))
                deck.offset.animateTo(Offset.Zero, AppMotion.standard(reduce))
                return
            }
            haptic.performHapticFeedback(when (intent) {
                FeedIntent.KEEP -> HapticFeedbackType.Confirm
                FeedIntent.MARK_FOR_CLEANUP -> HapticFeedbackType.ContextClick
                else -> HapticFeedbackType.SegmentTick
            })
            runCatching { deck.offset.animateTo(exit(intent, deck.offset.value), AppMotion.flyOut(reduce), initialVelocity = velocity) }
            deck.index = (deck.index + 1) % DemoArt.COUNT
            deck.offset.snapTo(Offset.Zero)
            deck.done = deck.done + intent
            onAction(intent)
        }

        // Selbstlauf (Angebot): hoch → links → rechts, jeweils als halber Zug mit Stempel und dann hinaus.
        if (auto && !reduce) LaunchedEffect(deck) {
            val order = listOf(SwipeDirection.UP, SwipeDirection.LEFT, SwipeDirection.RIGHT)
            var i = 0
            while (true) {
                delay(1_600)
                if (deck.touched) { delay(4_000); deck.touched = false; continue }
                val d = order[i++ % order.size]
                deck.locked = d
                val target = when (d) { SwipeDirection.UP -> Offset(0f, -h * 0.28f); SwipeDirection.LEFT -> Offset(-w * 0.32f, 0f); else -> Offset(w * 0.32f, 0f) }
                deck.offset.animateTo(target, AppMotion.standard(false))
                delay(420)
                act(FeedIntent.of(d))
                deck.locked = null
            }
        }

        val keep = stringResource(R.string.dir_keep); val trash = stringResource(R.string.dir_trash)
        val later = stringResource(R.string.dir_later); val back = stringResource(R.string.dir_back)
        val desc = stringResource(R.string.onb_demo_desc)
        Box(Modifier.fillMaxSize().testTag("onb.deck")
            .semantics {
                contentDescription = desc
                customActions = listOf(
                    CustomAccessibilityAction(keep) { scope.launch { act(FeedIntent.KEEP) }; true },
                    CustomAccessibilityAction(trash) { scope.launch { act(FeedIntent.MARK_FOR_CLEANUP) }; true },
                    CustomAccessibilityAction(later) { scope.launch { act(FeedIntent.LATER) }; true },
                    CustomAccessibilityAction(back) { scope.launch { act(FeedIntent.PREVIOUS) }; true },
                )
            }
            .feedGestures(enabled = true, density = density,
                onDrag = { t ->
                    deck.touched = true
                    deck.machine.update(Vector2(t.x.toDouble(), t.y.toDouble()))
                    deck.locked = deck.machine.lockedDirection
                    val r = deck.machine.readyToCommit
                    if (r && !deck.ready) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    deck.ready = r
                    val o = deck.machine.displayOffset
                    scope.launch { deck.offset.snapTo(Offset(o.dx.toFloat(), o.dy.toFloat())) }
                },
                onEnd = { t, v ->
                    deck.ready = false
                    when (val r = deck.machine.end(Vector2(t.x.toDouble(), t.y.toDouble()), Vector2(v.x.toDouble(), v.y.toDouble()))) {
                        is FeedGestureMachine.Resolution.Commit -> scope.launch {
                            act(FeedIntent.of(r.direction), v); deck.machine.finishCommit(); deck.locked = null
                        }
                        else -> scope.launch { deck.locked = null; deck.offset.animateTo(Offset.Zero, AppMotion.standard(reduce)) }
                    }
                },
                onCancel = { deck.machine.cancel(); deck.locked = null; deck.ready = false; scope.launch { deck.offset.snapTo(Offset.Zero) } },
                onZoom = {}, onDoubleTap = {}, waitForDoubleTap = { false })) {
            // Zwei Karten: die nächste wartet darunter und wächst mit dem Zug (wie im Feed).
            for (layer in 1 downTo 0) {
                val v = deck.index + layer
                key(v) {
                    val top = layer == 0
                    Box(Modifier.fillMaxSize().padding(horizontal = AppSpacing.xs)
                        .then(if (top) Modifier.offset { val o = deck.offset.value; with(density) { IntOffset(o.x.dp.roundToPx(), o.y.dp.roundToPx()) } } else Modifier)
                        .graphicsLayer {
                            val o = deck.offset.value
                            if (top) rotationZ = (o.x / w) * 8f
                            else {
                                // Die nächste Karte schaut leicht schräg hervor (Stapel-Gefühl) und richtet sich mit dem Zug auf.
                                val p = (hypot(o.x, o.y) / (w * 0.9f)).coerceIn(0f, 1f)
                                val s = 0.94f + 0.06f * p; scaleX = s; scaleY = s
                                rotationZ = 5f * (1f - p)
                                translationY = 14.dp.toPx() * (1f - p)
                                alpha = 0.85f + 0.15f * p
                            }
                        }
                        .shadow(if (top) 10.dp else 0.dp, RoundedCornerShape(AppRadii.hero), clip = false)
                        .clip(RoundedCornerShape(AppRadii.hero)).background(c.surface)) {
                        DemoArtwork(v, Modifier.fillMaxSize())
                        if (top) DemoStamp(deck, Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

/** Stempel in unserem Stil (Glas-Kapsel mit Richtungsfarbe), wird mit dem Zug deutlicher. */
@Composable
private fun DemoStamp(deck: DemoDeck, modifier: Modifier) {
    val c = PM.colors
    val d = deck.locked ?: return
    val (icon, label, tint) = when (FeedIntent.of(d)) {
        FeedIntent.KEEP -> Triple(Icons.Rounded.ArrowUpward, R.string.dir_keep, c.keep)
        FeedIntent.MARK_FOR_CLEANUP -> Triple(GestureIcons.Left, R.string.dir_trash, c.cleanup)
        FeedIntent.LATER -> Triple(GestureIcons.Right, R.string.dir_later, c.later)
        else -> return
    }
    Row(modifier.graphicsLayer {
        val o = deck.offset.value
        alpha = (maxOf(abs(o.x), abs(o.y)) / 70f).coerceIn(0.35f, 1f)
    }.glassCapsule(elevation = 2.dp).padding(horizontal = AppSpacing.m, vertical = AppSpacing.xs).testTag("onb.stamp"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(AppIconSize.small))
        Text(stringResource(label), style = AppTypography.headline.copy(fontWeight = FontWeight.Bold), color = tint)
    }
}

/** Die drei Übungs-Chips unter dem Stapel: ↑ Behalten · ← Papierkorb · → Später, mit Haken, sobald geübt. */
@Composable
fun DemoChecklist(deck: DemoDeck, modifier: Modifier = Modifier) {
    val c = PM.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs, Alignment.CenterHorizontally)) {
        @Composable
        fun chip(intent: FeedIntent, icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, tint: Color) {
            val ok = intent in deck.done
            val text = stringResource(label)
            Row(Modifier.clip(RoundedCornerShape(50)).background(if (ok) tint.copy(alpha = 0.14f) else c.backgroundTint)
                .padding(horizontal = AppSpacing.s, vertical = AppSpacing.xs)
                .semantics(mergeDescendants = true) { contentDescription = if (ok) "$text ✓" else text }
                .testTag(if (ok) "onb.chip.done" else "onb.chip"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
                Icon(if (ok) Icons.Rounded.Check else icon, null, tint = tint, modifier = Modifier.size(16.dp))
                Text(text, style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = if (ok) tint else c.textSecondary)
            }
        }
        chip(FeedIntent.KEEP, Icons.Rounded.ArrowUpward, R.string.dir_keep, c.keep)
        chip(FeedIntent.MARK_FOR_CLEANUP, GestureIcons.Left, R.string.dir_trash, c.cleanup)
        chip(FeedIntent.LATER, GestureIcons.Right, R.string.dir_later, c.later)
    }
}
