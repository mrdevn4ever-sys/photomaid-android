package app.photomaid.feature.cleanup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.photomaid.R
import app.photomaid.core.model.MediaKind
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.DateText
import app.photomaid.design.GlassIconButton
import app.photomaid.design.GlassToolbar
import app.photomaid.design.PM
import app.photomaid.design.SizeText
import app.photomaid.feature.review.MediaPage
import app.photomaid.feature.review.PreviewState
import app.photomaid.feature.review.rememberPreviewPx
import app.photomaid.feature.review.toggleVideo
import app.photomaid.media.MediaAsset
import app.photomaid.navigation.LocalApp

/**
 * Große Ansicht eines Mediums im Papierkorb (Foto im Ganzen, Video abspielbar per Tipp).
 * Reine Ansicht: ändert weder Auswahl noch Papierkorb. Zurück/Schließen = unverändert.
 */
@Composable
fun BasketPreview(asset: MediaAsset, onClose: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val ctx = LocalContext.current
    DisposableEffect(asset.id) { onDispose { app.player.stop() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(c.background).testTag("basket.preview")) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom = AppSpacing.s),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                val title = stringResource(when (asset.kind) { MediaKind.VIDEO -> R.string.video; MediaKind.SCREENSHOT -> R.string.screenshot; else -> R.string.photo })
                val sub = listOfNotNull(asset.creationDate?.let { DateText.abbreviated(it) }, SizeText.size(ctx, asset.size).takeIf { asset.size.bytesOrNull != null })
                    .joinToString(" · ")
                GlassToolbar(title = title, subtitle = sub.ifEmpty { null },
                    leading = { GlassIconButton(Icons.Rounded.Close, stringResource(R.string.close), quiet = true, onClick = onClose) }, trailing = {})
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = AppSpacing.xs)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = asset.kind == MediaKind.VIDEO) {
                        toggleVideo(app.player, asset)
                    }) {
                    val px = rememberPreviewPx(maxWidth.value, maxHeight.value)
                    var state by remember(asset.id) { mutableStateOf(PreviewState.LOADING) }
                    MediaPage(asset, px, state, onResult = { ok -> state = if (ok) PreviewState.READY else PreviewState.FAILED }, skip = onClose, zoomed = false, skipLabel = R.string.close)
                }
                Text(stringResource(R.string.basket_preview_note), style = AppTypography.caption, color = c.textSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.pageMargin))
            }
        }
    }
}
