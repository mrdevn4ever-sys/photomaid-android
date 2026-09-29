package app.photomaid.feature.common

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.storage.StorageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.storage.DeviceCapacity
import app.photomaid.design.AppRadii
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassButton
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/** Öffentliche Systemwerte zur Gerätekapazität (iOS `DeviceStorage`). */
object DeviceStorage {
    fun read(ctx: Context): DeviceCapacity? = runCatching {
        val m = ctx.getSystemService(StorageStatsManager::class.java)
        DeviceCapacity(m.getTotalBytes(StorageManager.UUID_DEFAULT), m.getFreeBytes(StorageManager.UUID_DEFAULT))
    }.getOrNull()
}

/** Kleines Vorschaubild (lokal, ohne Netzwerk, begrenzte Dekodiergröße). */
@Composable
fun ThumbnailView(uri: String?, size: Dp = 52.dp, modifier: Modifier = Modifier) {
    val c = PM.colors
    val px = with(LocalDensity.current) { size.roundToPx() }
    Box(modifier.size(size).clip(RoundedCornerShape(AppRadii.thumb)).background(c.backgroundTint).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (uri != null) {
            AsyncImage(
                ImageRequest.Builder(LocalContext.current).data(android.net.Uri.parse(uri)).size(px, px).memoryCacheKey("thumb:$uri:$px").build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size),
            )
        } else Icon(Icons.Rounded.Description, null, tint = c.textSecondary)
    }
}

/** Einfacher Hinweis-Dialog (iOS `.alert("Hinweis") { OK }`). */
@Composable
fun NoticeDialog(message: String?, title: String = stringResource(R.string.notice), onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok)) } },
        title = { Text(title) }, text = { Text(message) })
}

/**
 * Bestätigung einer destruktiven Aktion (iOS `confirmationDialog` → Action-Sheet von unten).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmationSheet(title: String, message: String, actionTitle: String, destructive: Boolean = true, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = PM.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.s),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Text(title, style = AppTypography.headline, color = c.textPrimary, textAlign = TextAlign.Center)
            Text(message, style = AppTypography.subheadline, color = c.textSecondary, textAlign = TextAlign.Center)
            ProminentButton(actionTitle, fillWidth = true, large = true, color = if (destructive) c.cleanup else c.accent, onClick = { onDismiss(); onConfirm() })
            GlassButton(stringResource(R.string.cancel), fillWidth = true, large = true, onClick = onDismiss)
        }
    }
}
