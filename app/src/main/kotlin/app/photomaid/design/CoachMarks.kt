package app.photomaid.design

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.photomaid.R

/** Positionen der Ziele (im Root-Koordinatensystem), die ein [CoachHost] hervorhebt. */
class CoachRegistry { val bounds = mutableStateMapOf<String, Rect>() }

val LocalCoach = staticCompositionLocalOf<CoachRegistry?> { null }

/** Markiert ein Element als Ziel einer Hinweiskarte. Ohne aktiven [CoachHost] wirkungslos. */
fun Modifier.coachTarget(key: String): Modifier = composed {
    val reg = LocalCoach.current
    if (reg == null) this else onGloballyPositioned { reg.bounds[key] = it.boundsInRoot() }
}

data class CoachStep(val key: String, val title: String, val text: String)

/**
 * Hinweiskarten-Tutorial („2/5 · Titel · Text · Tutorial überspringen · Weiter“). Dunkelt alles ab, spart das Ziel
 * aus und legt eine Karte darunter oder darüber. Blockiert solange jede Berührung (keine versehentliche Aktion);
 * Zurück-Taste = überspringen. Fehlt ein Ziel, steht die Karte in der Mitte.
 */
@Composable
fun CoachHost(steps: List<CoachStep>, active: Boolean, onFinish: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val registry = remember { CoachRegistry() }
    // Andere Schritte (z. B. nach dem Feed-Tutorial der Serien-Hinweis) beginnen wieder bei 1.
    var index by rememberSaveable(steps.joinToString("|") { it.key }) { mutableIntStateOf(0) }
    var hostOrigin by remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { hostOrigin = it.boundsInRoot().topLeft }) {
        CompositionLocalProvider(LocalCoach provides registry) { content() }
        val show = active && steps.isNotEmpty() && index < steps.size
        if (show) BackHandler { onFinish() }
        // Beim Ausblenden können die Schritte schon leer sein (z. B. Tutorial erledigt): die zuletzt gezeigten weiterverwenden.
        var lastSteps by remember { androidx.compose.runtime.mutableStateOf(steps) }
        if (steps.isNotEmpty()) lastSteps = steps
        AnimatedVisibility(show, Modifier.fillMaxSize(), enter = fadeIn(AppMotion.normal()), exit = fadeOut(AppMotion.fast())) {
            val shown = if (steps.isNotEmpty()) steps else lastSteps
            if (shown.isEmpty()) return@AnimatedVisibility
            val step = shown[index.coerceIn(0, shown.lastIndex)]
            val target = registry.bounds[step.key]?.translate(-hostOrigin)
            CoachOverlay(step, index.coerceIn(0, shown.lastIndex), shown.size, target,
                next = { if (index < steps.lastIndex) index++ else onFinish() }, skip = onFinish)
        }
    }
}

@Composable
private fun CoachOverlay(step: CoachStep, index: Int, total: Int, target: Rect?, next: () -> Unit, skip: () -> Unit) {
    val c = PM.colors
    val density = LocalDensity.current
    val pad = with(density) { 8.dp.toPx() }
    val hole = target?.let { Rect(it.left - pad, it.top - pad, it.right + pad, it.bottom + pad) }
    // Weiches Wandern der Aussparung zwischen den Schritten.
    val l by animateFloatAsState(hole?.left ?: 0f, AppMotion.normal(), label = "l")
    val t by animateFloatAsState(hole?.top ?: 0f, AppMotion.normal(), label = "t")
    val r by animateFloatAsState(hole?.right ?: 0f, AppMotion.normal(), label = "r")
    val b by animateFloatAsState(hole?.bottom ?: 0f, AppMotion.normal(), label = "b")
    BoxWithConstraints(Modifier.fillMaxSize().testTag("coach").pointerInput(Unit) {
        // Alles unter der Abdunkelung ist gesperrt: das Tutorial löst nie eine Aktion aus.
        awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume(); do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed }) }
    }) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color.Black.copy(alpha = if (c.isDark) 0.66f else 0.52f))
            if (hole != null) drawRoundRect(Color.Black, topLeft = Offset(l, t), size = Size(r - l, b - t),
                cornerRadius = CornerRadius(with(density) { 20.dp.toPx() }), blendMode = BlendMode.Clear)
        }
        val heightPx = with(density) { maxHeight.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        var cardH by remember { mutableIntStateOf(0) }
        val below = hole == null || hole.center.y < heightPx * 0.5f
        // Karte unter bzw. über dem Ziel, immer vollständig im Bild; ohne Ziel mittig.
        val yPx = when {
            hole == null -> (heightPx - cardH) / 2f
            below -> (hole.bottom + margin).coerceAtMost(heightPx - cardH - margin)
            else -> (hole.top - margin - cardH).coerceAtLeast(margin)
        }
        Column(Modifier.align(Alignment.TopCenter).offset { IntOffset(0, yPx.toInt()) }
            .onSizeChanged { cardH = it.height }
            .fillMaxWidth().padding(horizontal = AppSpacing.pageMargin).widthIn(max = 560.dp)
            .shadow(18.dp, RoundedCornerShape(AppRadii.hero), clip = false)
            .background(c.surface.copy(alpha = 1f), RoundedCornerShape(AppRadii.hero))
            .padding(AppSpacing.l)
            .semantics { paneTitle = step.title; liveRegion = LiveRegionMode.Polite }
            .testTag("coach.card"),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
            Text(stringResource(R.string.tour_count, index + 1, total), style = AppTypography.footnote, color = c.textSecondary)
            Text(step.title, style = AppTypography.title.copy(fontWeight = FontWeight.Bold), color = c.textPrimary)
            Text(step.text, style = AppTypography.body, color = c.textPrimary)
            Spacer(Modifier.padding(top = AppSpacing.xs))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (index < total - 1) PlainButton(stringResource(R.string.tour_skip), Modifier.testTag("coach.skip"), onClick = skip)
                Spacer(Modifier.weight(1f))
                ProminentButton(stringResource(if (index < total - 1) R.string.tour_next else R.string.tour_done), Modifier.testTag("coach.next"), onClick = next)
            }
        }
    }
}
