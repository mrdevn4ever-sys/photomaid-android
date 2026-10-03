package app.photomaid.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.photomaid.R
import app.photomaid.design.AppRadii
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.DateText
import app.photomaid.design.GlassIconButton
import app.photomaid.design.GlassToolbar
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.media.MediaAsset
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * Fotoserie vergleichen (Pro): oben die Serie als Leiste (Haken = behalten), darunter zwei Bilder groß
 * (Hochformat übereinander) mit gemeinsamem Zoom. Erst „n behalten · m in den Papierkorb“ entscheidet –
 * vorher ändert nichts etwas. Start: alle behalten; der Nutzer nimmt heraus, was weg darf.
 */
@Composable
fun SeriesCompareDialog(members: List<MediaAsset>, onConfirm: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val c = PM.colors
    var keep by remember(members) { mutableStateOf(members.map { it.id }.toSet()) }
    var left by remember(members) { mutableStateOf(members.first().id) }
    var right by remember(members) { mutableStateOf(members.getOrElse(1) { members.first() }.id) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val byId = members.associateBy { it.id }
    fun toggle(id: String) { keep = if (id in keep) keep - id else keep + id }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(c.background).testTag("series")) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom = AppSpacing.s),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                GlassToolbar(stringResource(R.string.series_title), subtitle = stringResource(R.string.series_subtitle, members.size),
                    leading = { GlassIconButton(Icons.Rounded.Close, stringResource(R.string.close), quiet = true, onClick = onDismiss) })
                Text(stringResource(R.string.series_hint), style = AppTypography.caption, color = c.textSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.pageMargin))
                LazyRow(Modifier.fillMaxWidth().testTag("series.strip"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AppSpacing.pageMargin)) {
                    items(members, key = { it.id }) { a ->
                        val kept = a.id in keep
                        val shown = a.id == left || a.id == right
                        val keepLabel = stringResource(R.string.series_keep_toggle)
                        val state = stringResource(if (kept) R.string.series_state_keep else R.string.series_state_trash)
                        Box(Modifier.size(76.dp).clip(RoundedCornerShape(AppRadii.thumb))
                            .border(if (shown) 3.dp else 0.dp, if (shown) c.accent else Color.Transparent, RoundedCornerShape(AppRadii.thumb))
                            // Tipp auf die Kachel: rechts/unten vergleichen. Der Haken entscheidet über „behalten“.
                            .clickable(role = Role.Button) { if (a.id != left) right = a.id }
                            .testTag("series.tile")) {
                            Thumb(a, Modifier.fillMaxSize(), 160)
                            if (!kept) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                            Box(Modifier.align(Alignment.TopEnd).size(40.dp).clickable(role = Role.Checkbox) { toggle(a.id) }
                                .semantics { contentDescription = keepLabel; stateDescription = state }.testTag("series.check"), contentAlignment = Alignment.Center) {
                                Icon(if (kept) Icons.Rounded.CheckCircle else Icons.Outlined.Circle, null,
                                    tint = if (kept) c.keep else Color.White, modifier = Modifier.size(24.dp).background(Color.White.copy(alpha = if (kept) 1f else 0f), CircleShape))
                            }
                            if (a.isFavorite) Icon(Icons.Rounded.Favorite, null, tint = c.cleanup, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(16.dp))
                        }
                    }
                }
                // Zwei Bilder groß, gemeinsamer Zoom (Zwei-Finger-Zoom auf einem wirkt auf beide).
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = AppSpacing.xs)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, p, z, _ ->
                            scale = (scale * z).coerceIn(1f, 5f)
                            pan = if (scale <= 1.01f) Offset.Zero else pan + p
                        }
                    }, verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    for ((id, side) in listOf(left to 0, right to 1)) {
                        val a = byId[id] ?: continue
                        // Bild und Auswahl getrennt: die Behalten/Papierkorb-Taste liegt UNTER dem Bild, nie darauf.
                        Column(Modifier.weight(1f).fillMaxWidth().testTag("series.side$side"), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(AppRadii.card)).background(c.backgroundTint).testTag("series.img$side")) {
                                BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }) {
                                    val px = with(LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() }
                                    Thumb(a, Modifier.fillMaxSize(), px, fit = true)
                                }
                            }
                            val kept = a.id in keep
                            Row(Modifier.padding(top = AppSpacing.xxs).heightIn(min = AppSpacing.minTouch).clip(CircleShape)
                                .clickable(role = Role.Checkbox) { toggle(a.id) }.padding(horizontal = AppSpacing.s).testTag("series.keep$side"),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(if (kept) Icons.Rounded.CheckCircle else Icons.Outlined.Circle, null, tint = if (kept) c.keep else c.textSecondary, modifier = Modifier.size(20.dp))
                                Text(stringResource(if (kept) R.string.series_state_keep else R.string.series_state_trash) +
                                    (a.creationDate?.let { " · " + DateText.time(it) } ?: ""), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
                            }
                        }
                    }
                }
                val trash = members.size - keep.size
                ProminentButton(stringResource(R.string.series_confirm, keep.size, trash), Modifier.padding(horizontal = AppSpacing.pageMargin).testTag("series.confirm"),
                    fillWidth = true, large = true) { onConfirm(keep) }
                Text(stringResource(R.string.series_footer), style = AppTypography.caption, color = c.textSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.pageMargin))
            }
        }
    }
}

@Composable
private fun Thumb(a: MediaAsset, modifier: Modifier, px: Int, fit: Boolean = false) {
    AsyncImage(ImageRequest.Builder(LocalContext.current).data(a.uri).size(px, px).memoryCacheKey("series:${a.id}:$px").build(),
        contentDescription = null, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop, modifier = modifier)
}
