package app.photomaid.feature.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography

/**
 * Einheitlicher Rahmen der drei Spiele (iOS `GameChrome`, Nutzer 22.10.2026): dunkler Spielgrund, Bernstein-Akzent,
 * gleiche Kopfzeile (Schließen · Spielname · Andere Spiele), gleiche Knöpfe und Fenster. Nur das Spielfeld selbst hat
 * sein eigenes Aussehen. Immer dunkel wie auf iOS.
 */
object GameColors {
    val ground = Color(0xFF0F0E0D)
    val surface = Color(0xFF1B1917)
    val stroke = Color(0x33FFFFFF)
    val control = Color(0x40FFFFFF)
    val accent = Color(0xFFF2B24C)
    val onAccent = Color(0xFF1B1308)
    val text = Color(0xFFF4EFE8)
    val secondary = Color(0xFFB8AFA4)
    val pauseSurface = Color(0xFF221D2E)
    val pauseStroke = Color(0x4DC4B5FD)
    val pauseText = Color(0xFFCFC6E8)
    val keep = Color(0xFF7CC8FF)
    val success = Color(0xFF8FD9A8)
}

/** Ganzer Bildschirm eines Spiels mit gemeinsamer Kopfzeile. */
@Composable
fun GameScaffold(
    title: String,
    tag: String,
    onClose: () -> Unit,
    onOtherGames: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(GameColors.ground).testTag(tag).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
            GameTextButton(stringResource(R.string.close), Modifier.testTag("$tag.close"), onClick = onClose)
            Text(title, Modifier.weight(1f).semantics { heading() }, style = AppTypography.headline, color = GameColors.text, textAlign = TextAlign.Center, maxLines = 1)
            if (trailing != null) trailing()
            if (onOtherGames != null) GameTextButton(stringResource(R.string.snake_other_games), Modifier.testTag("$tag.otherGames"), onClick = onOtherGames)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), content = content)
    }
}

@Composable
fun GameTextButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = AppSpacing.minTouch).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = AppTypography.body.copy(fontWeight = FontWeight.SemiBold), color = GameColors.accent, maxLines = 1)
    }
}

/** Breiter Spielknopf. `prominent` = Bernstein, sonst Fläche mit Rand (iOS `GameButtonStyle`). */
@Composable
fun GameButton(text: String, modifier: Modifier = Modifier, prominent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.fillMaxWidth().heightIn(min = AppSpacing.minTouch).clip(shape)
            .background(if (prominent) GameColors.accent else GameColors.surface, shape)
            .then(if (prominent) Modifier else Modifier.border(1.dp, GameColors.control, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = if (prominent) 13.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AppTypography.headline.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center,
            color = (if (prominent) GameColors.onAccent else GameColors.text).copy(alpha = if (enabled) 1f else 0.4f))
    }
}

/** Startseite oben: Szene/Symbol, ein Satz und – falls vorhanden – der Rekord als Bernstein-Plakette. */
@Composable
fun GameIntro(text: String, best: String?, scene: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 140.dp), contentAlignment = Alignment.Center) { scene() }
        Text(text, style = AppTypography.title, color = GameColors.text, textAlign = TextAlign.Center)
        if (best != null) RecordBadge(best)
    }
}

@Composable
fun RecordBadge(text: String, modifier: Modifier = Modifier) {
    Row(modifier.clip(CircleShape).background(GameColors.accent.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Rounded.EmojiEvents, null, Modifier.size(18.dp), tint = GameColors.accent)
        Text(text, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = GameColors.accent)
    }
}

/** Fenster über dem Spielfeld (Pause, Rundenende); scrollt, wenn es nicht passt. */
@Composable
fun BoxScope.GamePanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 380.dp).clip(shape).background(GameColors.surface, shape)
            .border(1.dp, GameColors.stroke, shape).verticalScroll(rememberScrollState()).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Karte für Einstellungen auf der Startseite (Titel + Inhalt). */
@Composable
fun GameSetupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(GameColors.surface, shape).border(1.dp, GameColors.stroke, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = GameColors.secondary)
        content()
    }
}

/** Kleine Auswahl-Kapseln (Quelle, Größe, Tempo …). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun <T> GameChoices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            val shape = CircleShape
            Box(Modifier.heightIn(min = AppSpacing.minTouch).clip(shape)
                .background(if (on) GameColors.accent else GameColors.ground, shape)
                .border(1.dp, if (on) GameColors.accent else GameColors.control, shape)
                .clickable(role = Role.RadioButton) { onSelect(value) }
                .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(label, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = if (on) GameColors.onAccent else GameColors.text)
            }
        }
    }
}

/** Zeit wie iOS `DateText.gameDuration`: m:ss bzw. h:mm:ss. */
fun gameDuration(seconds: Double): String {
    val s = if (seconds.isFinite()) seconds.toLong().coerceAtLeast(0) else 0
    val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
    return if (h > 0) String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", h, m, r) else String.format(java.util.Locale.getDefault(), "%d:%02d", m, r)
}
