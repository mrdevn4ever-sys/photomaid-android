package app.photomaid.feature.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.design.AppRadii
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.LocalReduceMotion
import app.photomaid.design.PM
import app.photomaid.design.glassCapsule

/** Fortschritt 0..1 einer Endlosschleife; bei reduzierter Bewegung fest auf [still]. */
@Composable
private fun loop(ms: Int, still: Float): Float {
    if (LocalReduceMotion.current) return still
    val t = rememberInfiniteTransition(label = "story")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart), label = "p")
    return p
}

/** Abschnitt [a, b] von p auf 0..1 abbilden (weich). */
private fun seg(p: Float, a: Float, b: Float): Float {
    val x = ((p - a) / (b - a)).coerceIn(0f, 1f)
    return x * x * (3 - 2 * x)
}

/**
 * O1 – Raster aus Demo-Fotos: drei bekommen das Papierkorb-Abzeichen und fliegen nach links hinaus,
 * danach rücken andere Motive nach. Keine Zahlen, keine Medien der Nutzer.
 */
@Composable
fun GridStoryScene(modifier: Modifier = Modifier) {
    val c = PM.colors
    val p = loop(4_800, still = 0.3f)
    val leaving = setOf(1, 5, 6)
    Column(modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        for (row in 0..2) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            for (col in 0..2) {
                val i = row * 3 + col
                val order = leaving.indexOf(i)
                val badge = if (order >= 0) seg(p, 0.08f + order * 0.08f, 0.16f + order * 0.08f) else 0f
                val out = if (order >= 0) seg(p, 0.42f + order * 0.06f, 0.6f + order * 0.06f) else 0f
                val back = if (order >= 0) seg(p, 0.78f, 0.92f) else 0f
                Box(Modifier.weight(1f).fillMaxHeight()
                    .graphicsLayer {
                        translationX = -size.width * 1.4f * out * (1f - back)
                        alpha = if (back > 0f) back else 1f - out * 0.9f
                        val s = if (back > 0f) 0.85f + 0.15f * back else 1f; scaleX = s; scaleY = s
                        rotationZ = -10f * out * (1f - back)
                    }
                    .clip(RoundedCornerShape(AppRadii.tile + 4.dp))) {
                    DemoPhoto(if (back > 0f && order >= 0) DemoArt.gridRefill[order] else DemoArt.grid[i], Modifier.fillMaxSize())
                    if (badge > 0f && back == 0f) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).graphicsLayer { scaleX = badge; scaleY = badge; alpha = badge }
                        .size(26.dp).clip(CircleShape).background(c.cleanup), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Delete, null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

/**
 * O3 – Sicherheit: Karte wischt nach links in den GaleriQ-Papierkorb (Zähler springt auf 1), danach öffnet sich
 * angedeutet die Bestätigung, über der Android noch einmal fragt. Schematisch, ohne Speicherzahlen.
 */
@Composable
fun SafetyStoryScene(modifier: Modifier = Modifier) {
    val c = PM.colors
    val density = LocalDensity.current
    val p = loop(5_200, still = 0.95f)
    val fly = seg(p, 0.08f, 0.32f)
    val count = seg(p, 0.3f, 0.38f)
    val sheet = seg(p, 0.46f, 0.6f)
    val dialog = seg(p, 0.64f, 0.74f)
    val fade = 1f - seg(p, 0.93f, 1f)
    val confirm = stringResource(R.string.onb_safe_sheet)
    val system = stringResource(R.string.onb_safe_dialog)
    BoxWithConstraints(modifier.clearAndSetSemantics {}.graphicsLayer { alpha = if (p > 0.93f) fade else 1f }) {
        val w = maxWidth; val h = maxHeight
        // Papierkorb-Symbol oben rechts mit Zähler (wie die Kopfzeile im Feed).
        Box(Modifier.align(Alignment.TopEnd).padding(AppSpacing.s).size(52.dp).glassCapsule(4.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Delete, null, tint = c.cleanup)
            Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).graphicsLayer { scaleX = count; scaleY = count }
                .size(20.dp).clip(CircleShape).background(c.cleanup), contentAlignment = Alignment.Center) {
                Text("1", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold), color = Color.White)
            }
        }
        // Demo-Karte: in die Mitte, dann nach oben rechts ins Symbol.
        Box(Modifier.align(Alignment.Center).width(w * 0.48f).aspectRatio(0.78f)
            .graphicsLayer {
                translationX = with(density) { (w * 0.36f).toPx() } * fly
                translationY = -with(density) { (h * 0.36f).toPx() } * fly
                val s = 1f - 0.82f * fly; scaleX = s; scaleY = s
                rotationZ = -12f * fly
                alpha = 1f - seg(p, 0.28f, 0.33f)
            }
            .shadow(10.dp, RoundedCornerShape(AppRadii.hero), clip = false).clip(RoundedCornerShape(AppRadii.hero))) {
            DemoPhoto(DemoArt.grid[5], Modifier.fillMaxSize()) // ein „Aussortier“-Kandidat
        }
        // Angedeutete Bestätigung + Android-Rückfrage.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().graphicsLayer {
            translationY = (1f - sheet) * size.height; alpha = sheet
        }.clip(RoundedCornerShape(topStart = AppRadii.hero, topEnd = AppRadii.hero)).background(c.surface).padding(AppSpacing.m),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(c.surfaceStroke))
            Box(Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(c.cleanup), contentAlignment = Alignment.Center) {
                Text(confirm, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
            }
            Spacer(Modifier.height(AppSpacing.xxs))
        }
        Row(Modifier.align(Alignment.Center).graphicsLayer { scaleX = 0.9f + 0.1f * dialog; scaleY = 0.9f + 0.1f * dialog; alpha = dialog }
            .shadow(16.dp, RoundedCornerShape(AppRadii.card), clip = false).clip(RoundedCornerShape(AppRadii.card)).background(c.surface)
            .padding(horizontal = AppSpacing.m, vertical = AppSpacing.s),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Icon(Icons.Rounded.PhoneAndroid, null, tint = c.accent)
            Text(system, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
        }
    }
}

@Suppress("unused")
private fun IntOffset.half() = IntOffset(x / 2, y / 2)
