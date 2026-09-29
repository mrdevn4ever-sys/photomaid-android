package app.photomaid.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import app.photomaid.BuildConfig
import app.photomaid.R
import app.photomaid.billing.PurchaseResult
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.later.LaterInterval
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.AppearanceChoice
import app.photomaid.design.ListSection
import app.photomaid.design.MenuRowPicker
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.RowDivider
import app.photomaid.design.ScreenScaffold
import app.photomaid.design.SourceRow
import app.photomaid.design.GestureLegendRow
import app.photomaid.design.clickableRow
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.Routes
import app.photomaid.notifications.ReminderScheduler
import kotlinx.coroutines.launch

/** Screen 15 – Einstellungen. */
@Composable
fun SettingsScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.collectAsState()
    val notif by app.reminders.access.collectAsState()
    val sources by app.sources.sources.collectAsState()
    val purchase by app.purchases.state.collectAsState()
    var restoreMessage by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(stringResource(R.string.settings), onBack = null, actions = {
        TextButton(nav::back) { Text(stringResource(R.string.done), fontWeight = FontWeight.SemiBold) }
    }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            ListSection {
                SourceRow(Icons.Rounded.Shield, c.accent, stringResource(R.string.set_privacy), Modifier.clickableRow(onClick = nav::openPrivacy),
                    subtitle = stringResource(R.string.set_privacy_detail))
                RowDivider()
                // Android-Einstellungen der App-Mitteilungen (öffentliche, dokumentierte Intent-Aktion).
                SourceRow(Icons.Rounded.Notifications, c.accent, stringResource(R.string.set_notifications), Modifier.clickableRow {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }, detail = stringResource(if (notif == ReminderScheduler.Access.ALLOWED) R.string.allowed else R.string.off))
                RowDivider()
                SourceRow(Icons.Rounded.Folder, c.accent, stringResource(R.string.set_folder_access), Modifier.clickableRow { nav.push(Routes.SOURCES_MODAL) },
                    detail = stringResource(R.string.set_connected_n, sources.size))
            }
            ListSection(header = stringResource(R.string.set_controls)) {
                ToggleRow(stringResource(R.string.set_haptics), settings.haptics) { v -> scope.launch { app.updateSettings { it.copy(haptics = v) } } }
                RowDivider()
                ToggleRow(stringResource(R.string.set_hints), settings.showGestureHintOnOpen) { v -> scope.launch { app.updateSettings { it.copy(showGestureHintOnOpen = v) } } }
                RowDivider()
                MenuRowPicker(stringResource(R.string.set_later_default), LaterInterval.allowedQuickDays.map { d ->
                    d to if (d == 1) stringResource(R.string.set_tomorrow) else stringResource(R.string.set_in_days, d)
                }, settings.laterDefaultDays) { v -> scope.launch { app.updateSettings { it.copy(laterDefaultDays = v) } } }
                RowDivider()
                MenuRowPicker(stringResource(R.string.set_appearance), listOf(
                    AppearanceChoice.SYSTEM to stringResource(R.string.appearance_system), AppearanceChoice.LIGHT to stringResource(R.string.appearance_light),
                    AppearanceChoice.DARK to stringResource(R.string.appearance_dark),
                ), settings.appearance) { v -> scope.launch { app.updateSettings { it.copy(appearance = v) } } }
            }
            ListSection(header = stringResource(R.string.set_how_to_swipe)) {
                val defaultLater = if (settings.laterDefaultDays == 1) stringResource(R.string.set_tomorrow) else stringResource(R.string.set_in_days, settings.laterDefaultDays)
                GestureLegendRow(Icons.Rounded.ArrowUpward, stringResource(R.string.dir_keep), stringResource(R.string.hint_up), c.keep); RowDivider()
                GestureLegendRow(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dir_trash), stringResource(R.string.hint_left), c.cleanup); RowDivider()
                GestureLegendRow(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.dir_later),
                    stringResource(R.string.gesture_with_default, stringResource(R.string.hint_right), defaultLater), c.later); RowDivider()
                GestureLegendRow(Icons.Rounded.NorthEast, stringResource(R.string.hint_date), stringResource(R.string.hint_diag), c.later); RowDivider()
                GestureLegendRow(Icons.Rounded.ArrowDownward, stringResource(R.string.dir_back), stringResource(R.string.hint_down), c.textSecondary)
            }
            ListSection {
                SourceRow(Icons.Rounded.WorkspacePremium, c.accent, stringResource(R.string.pro_title), Modifier.clickableRow(onClick = nav::openPro),
                    detail = stringResource(if (purchase == PurchaseState.PURCHASED) R.string.set_pro_active else R.string.set_pro_once))
                if (app.purchases.isConfigured) {
                    RowDivider()
                    PlainButton(stringResource(R.string.pro_restore)) {
                        scope.launch {
                            restoreMessage = res.getString(if (app.purchases.restore() == PurchaseResult.Purchased) R.string.pro_msg_restored else R.string.pro_msg_nothing)
                        }
                    }
                }
            }
            ListSection {
                SourceRow(Icons.AutoMirrored.Rounded.HelpOutline, c.accent, stringResource(R.string.help), Modifier.clickableRow { nav.push(Routes.HELP) })
                RowDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.version), style = AppTypography.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                    Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = AppTypography.body, color = c.textSecondary)
                }
                if (BuildConfig.PRIVACY_POLICY_URL.isNotBlank()) {
                    RowDivider()
                    PlainButton(stringResource(R.string.privacy_policy)) {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
            Spacer(Modifier.height(AppSpacing.xl))
        }
    }
    NoticeDialog(restoreMessage) { restoreMessage = null }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = PM.colors
    Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.minTouch).clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = AppTypography.body, color = c.textPrimary, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = c.success, checkedThumbColor = androidx.compose.ui.graphics.Color.White))
    }
}

/** Hilfe. */
@Composable
fun HelpScreen(nav: Nav) {
    val c = PM.colors
    ScreenScaffold(stringResource(R.string.help), onBack = nav::back) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            for ((h, t) in listOf(R.string.help_decide to R.string.help_decide_text, R.string.help_delete to R.string.help_delete_text,
                R.string.help_cloud to R.string.help_cloud_text, R.string.help_free to R.string.help_free_text)) {
                ListSection(header = stringResource(h)) {
                    Text(stringResource(t), style = AppTypography.body, color = c.textPrimary, modifier = Modifier.padding(vertical = AppSpacing.s))
                }
            }
            Spacer(Modifier.height(AppSpacing.xl))
        }
    }
}
