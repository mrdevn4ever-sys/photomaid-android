package app.photomaid.feature.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import app.photomaid.design.GestureLegendRow
import app.photomaid.R
import app.photomaid.design.AppBackground
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassButton
import app.photomaid.design.PlainButton
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.design.SurfaceCard
import app.photomaid.design.glassRect
import app.photomaid.media.MediaPermissions
import app.photomaid.media.PhotoAccess
import app.photomaid.navigation.LocalApp
import app.photomaid.notifications.ReminderScheduler
import kotlinx.coroutines.launch

/**
 * Screen 4 – Datenschutz/Zugriff (auch Onboarding). Zeigt echte Systemzustände; keine Fake-Schalter.
 * Anfragen erfolgen kontextuell per Button, erst nach Erklärung. Kein zusätzlicher Berechtigungsscreen.
 */
@Composable
fun PrivacyAccessScreen(onboarding: Boolean, onDone: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val access by app.photos.access.collectAsState()
    val notif by app.reminders.access.collectAsState()
    LaunchedEffect(Unit) { app.reminders.refresh(); app.photos.refreshAccess() }

    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch {
            app.updateSettings { s -> s.copy(mediaPermissionAsked = true) }
            app.photos.refreshAccess()
            app.photos.bumpChangeToken()
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { app.updateSettings { s -> s.copy(notificationsOptIn = true) }; app.reminders.refresh() }
    }
    fun openAppSettings() = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    AppBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(AppSpacing.pageMargin),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.l)) {
                Box(Modifier.glassRect().padding(AppSpacing.l)) { Icon(Icons.Rounded.Shield, null, tint = c.accent, modifier = Modifier.size(56.dp)) }
                Text(stringResource(R.string.privacy_title), style = AppTypography.title, color = c.textPrimary, textAlign = TextAlign.Center)
                Text(stringResource(R.string.privacy_text), style = AppTypography.subheadline, color = c.textSecondary, textAlign = TextAlign.Center)
                SurfaceCard {
                    Header(Icons.Rounded.PhotoLibrary, stringResource(R.string.privacy_media)) {
                        val (label, color) = when (access) {
                            PhotoAccess.AUTHORIZED -> R.string.access_full to c.success
                            PhotoAccess.LIMITED -> R.string.access_limited to c.warning
                            PhotoAccess.DENIED -> R.string.access_denied to c.cleanup
                            PhotoAccess.NOT_DETERMINED -> R.string.access_not_asked to c.textSecondary
                        }
                        Badge(stringResource(label), color)
                    }
                    Text(stringResource(R.string.privacy_media_text), style = AppTypography.caption, color = c.textSecondary)
                    Spacer(Modifier.height(AppSpacing.s))
                    when (access) {
                        PhotoAccess.NOT_DETERMINED -> ProminentButton(stringResource(R.string.privacy_allow)) { mediaLauncher.launch(MediaPermissions.requestable()) }
                        // Android 14+: erneute Anfrage zeigt die System-Auswahl „Ausgewählte Fotos“ (iOS: limited picker).
                        PhotoAccess.LIMITED -> Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                            GlassButton(stringResource(R.string.privacy_manage)) { mediaLauncher.launch(MediaPermissions.requestable()) }
                            // Wurde eine Medienart dauerhaft abgelehnt, zeigt Android keinen Dialog mehr: Weg über die Einstellungen.
                            PlainButton(stringResource(R.string.privacy_open_settings)) { openAppSettings() }
                        }
                        PhotoAccess.DENIED -> GlassButton(stringResource(R.string.privacy_open_settings)) { openAppSettings() }
                        PhotoAccess.AUTHORIZED -> Unit
                    }
                }
                if (onboarding) {
                    // Erststart kurz halten: was PhotoMaid macht, die vier Gesten, und dass nichts ohne Bestätigung gelöscht wird.
                    // Mitteilungen und Ordner werden erst im jeweiligen Kontext angefragt (Später bzw. Dateien).
                    SurfaceCard(Modifier.testTag("onboarding.gestures")) {
                        Text(stringResource(R.string.set_how_to_swipe), style = AppTypography.headline, color = c.textPrimary)
                        Spacer(Modifier.height(AppSpacing.xs))
                        GestureLegendRow(Icons.Rounded.ArrowUpward, stringResource(R.string.dir_keep), stringResource(R.string.hint_up), c.keep)
                        GestureLegendRow(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dir_trash), stringResource(R.string.hint_left), c.cleanup)
                        GestureLegendRow(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.dir_later), stringResource(R.string.hint_right), c.later)
                        GestureLegendRow(Icons.Rounded.ArrowDownward, stringResource(R.string.dir_back), stringResource(R.string.hint_down), c.textSecondary)
                        Spacer(Modifier.height(AppSpacing.xs))
                        Text(stringResource(R.string.onboarding_safe), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
                    }
                } else {
                    SurfaceCard {
                        Header(Icons.Rounded.Notifications, stringResource(R.string.set_notifications)) {
                            Text(stringResource(when (notif) {
                                ReminderScheduler.Access.ALLOWED -> R.string.allowed
                                ReminderScheduler.Access.DENIED -> R.string.access_denied
                                ReminderScheduler.Access.NOT_DETERMINED -> R.string.access_not_asked
                            }), style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
                        }
                        Text(stringResource(R.string.privacy_notifications_text), style = AppTypography.caption, color = c.textSecondary)
                        if (notif == ReminderScheduler.Access.NOT_DETERMINED) {
                            Spacer(Modifier.height(AppSpacing.s))
                            GlassButton(stringResource(R.string.privacy_notifications_allow)) {
                                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else scope.launch { app.updateSettings { s -> s.copy(notificationsOptIn = true) }; app.reminders.refresh() }
                            }
                        }
                    }
                    SurfaceCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                            Icon(Icons.Rounded.Folder, null, tint = c.accent)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(R.string.privacy_folders), style = AppTypography.headline, color = c.textPrimary)
                                Text(stringResource(R.string.privacy_folders_text), style = AppTypography.caption, color = c.textSecondary)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.privacy_purchases), style = AppTypography.caption, color = c.textSecondary, textAlign = TextAlign.Center)
                if (LocalApp.current.adSdk.isConfigured) Text(stringResource(R.string.privacy_ads), style = AppTypography.caption, color = c.textSecondary, textAlign = TextAlign.Center)
            }
            // „Weiter“ immer sichtbar, auch auf kleinen Geräten und bei großer Schrift.
            ProminentButton(stringResource(R.string.continue_), Modifier.padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.xs),
                fillWidth = true, large = true, onClick = onDone)
        }
    }
}

@Composable
private fun Header(icon: ImageVector, title: String, trailing: @Composable () -> Unit) {
    val c = PM.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Icon(icon, null, tint = c.accent)
        Text(title, style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Badge(text: String, color: Color) = Text(text, style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold), color = color)
