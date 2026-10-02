package app.photomaid.feature.cleanup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photomaid.R
import app.photomaid.core.cleanup.CleanupOperationKind
import app.photomaid.core.cleanup.CleanupSavings
import app.photomaid.design.AppBackground
import app.photomaid.design.AppRadii
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassIconButton
import app.photomaid.design.LocalReduceMotion
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.design.SizeText

/** Ergebnis einer bestätigten Leerung (nur echte Werte: entfernte Elemente, bekannte Größen). */
data class CleanupDone(
    val savings: CleanupSavings,
    val kind: CleanupOperationKind,
    /** Bisherige Ergebnismeldung („3 Medien wurden in den Papierkorb des Systems verschoben.“). */
    val message: String,
    val partial: Boolean,
    val keptToday: Int,
    val totalBytes: Long,
    val totalLowerBound: Boolean,
)

/**
 * Erfolgsbildschirm nach dem Leeren: groß „gespart“ (zählt hoch), Entfernt / Heute behalten, Summe insgesamt.
 * Ehrlich: Medien liegen erst im Android-Papierkorb (Platz frei nach dessen Leerung), unbekannte Größen werden nicht
 * mitgezählt („mind.“), ohne bekannte Größe keine Zahl.
 */
@Composable
fun CleanupDoneView(done: CleanupDone, onDone: () -> Unit) {
    val c = PM.colors
    val reduce = LocalReduceMotion.current
    val s = done.savings
    val bytes = remember { Animatable(if (reduce) s.bytes.toFloat() else 0f) }
    val pop = remember { Animatable(if (reduce) 1f else 0.6f) }
    LaunchedEffect(Unit) {
        if (!reduce) {
            pop.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
            bytes.animateTo(s.bytes.toFloat(), tween(900, easing = FastOutSlowInEasing))
        }
    }
    AppBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().testTag("done")) {
            Box(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.s, vertical = AppSpacing.xs), contentAlignment = Alignment.CenterEnd) {
                GlassIconButton(Icons.Rounded.Close, stringResource(R.string.close), onClick = onDone)
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.m, Alignment.CenterVertically)) {
                Box(Modifier.size(64.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.clip(CircleShape).background(c.success.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = c.success, modifier = Modifier.size(34.dp))
                }
                Text(stringResource(R.string.done_title), style = AppTypography.title.copy(fontWeight = FontWeight.Bold), color = c.textPrimary,
                    modifier = Modifier.semantics { heading() })
                if (s.bytes > 0) {
                    // Zahl und Einheit getrennt gesetzt (wie im Vorbild), die Einheit kleiner.
                    val final = SizeText.bytes(s.bytes)
                    val shown = SizeText.bytes(bytes.value.toLong().coerceAtMost(s.bytes))
                    val number = shown.substringBeforeLast(' '); val unit = shown.substringAfterLast(' ')
                    val spoken = if (s.isLowerBound) stringResource(R.string.done_at_least, final) else final
                    val savedWord = stringResource(R.string.done_saved)
                    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$spoken $savedWord" }.testTag("done.saved"),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        if (s.isLowerBound) Text(stringResource(R.string.done_at_least, "").trim(), style = AppTypography.subheadline, color = c.textSecondary)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(number, style = AppTypography.display.copy(fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = AppTypography.tracking(-1.5f)), color = c.textPrimary)
                            Text(unit, style = AppTypography.display.copy(fontSize = 30.sp, lineHeight = 44.sp), color = c.textPrimary,
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                        }
                        Text(savedWord, style = AppTypography.title, color = c.textSecondary)
                    }
                }
                Text(done.message, style = AppTypography.subheadline, color = c.textSecondary, textAlign = TextAlign.Center)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                    Tile(Modifier.weight(1f), s.removedCount, Icons.Rounded.Delete, stringResource(R.string.done_removed), c.cleanup, "done.removed")
                    Tile(Modifier.weight(1f), done.keptToday, Icons.Rounded.ArrowUpward, stringResource(R.string.done_kept), c.keep, "done.kept")
                }
                if (done.totalBytes > 0) Text(
                    stringResource(if (done.totalLowerBound) R.string.done_total_at_least else R.string.done_total, SizeText.bytes(done.totalBytes)),
                    style = AppTypography.headline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("done.total"))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadii.card)).background(c.warm).padding(AppSpacing.m),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text(stringResource(when (done.kind) {
                        CleanupOperationKind.PHOTO_LIBRARY_DELETE -> R.string.done_note_media
                        CleanupOperationKind.PHOTO_LIBRARY_DELETE_PERMANENTLY -> R.string.done_note_media_deleted
                        CleanupOperationKind.FILE_MOVE_TO_TRASH -> R.string.done_note_files_trash
                        else -> R.string.done_note_files
                    }),
                        style = AppTypography.footnote, color = c.textPrimary)
                    if (s.unknownCount > 0) Text(pluralStringResource(R.plurals.done_unknown, s.unknownCount, s.unknownCount),
                        style = AppTypography.footnote, color = c.textSecondary)
                    if (done.partial) Text(stringResource(R.string.done_partial), style = AppTypography.footnote, color = c.textSecondary)
                }
                Spacer(Modifier.height(AppSpacing.s))
            }
            ProminentButton(stringResource(R.string.done_button), Modifier.padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.xs).testTag("done.button"),
                fillWidth = true, large = true, onClick = onDone)
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, count: Int, icon: ImageVector, label: String, tint: Color, tag: String) {
    Column(modifier.clip(RoundedCornerShape(AppRadii.card)).background(tint.copy(alpha = 0.12f)).padding(vertical = AppSpacing.m)
        .semantics(mergeDescendants = true) {}.testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$count", style = AppTypography.display.copy(fontSize = 40.sp, lineHeight = 46.sp), color = tint)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Text(label, style = AppTypography.subheadline.copy(fontWeight = FontWeight.Bold), color = tint)
        }
    }
}
