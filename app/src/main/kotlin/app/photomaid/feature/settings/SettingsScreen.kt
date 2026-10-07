package app.photomaid.feature.settings

import app.photomaid.design.GestureIcons
import app.photomaid.core.capabilities.Capabilities
import androidx.compose.ui.res.pluralStringResource
import app.photomaid.system.SystemDialogs
import app.photomaid.feature.pro.minutesLeft
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Notifications
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
import app.photomaid.feature.common.ConfirmationSheet
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
    var confirmPermanent by remember { mutableStateOf(false) }

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
                RowDivider()
                SourceRow(Icons.Rounded.FolderOff, c.accent, stringResource(R.string.set_excluded_folders), Modifier.clickableRow { nav.push(Routes.EXCLUDED) }.testTag("settings.excluded"),
                    detail = settings.excludedFolders.size.takeIf { it > 0 }?.toString())
                if (BuildConfig.PRIVACY_POLICY_URL.isNotBlank()) {
                    RowDivider()
                    // Öffentliche Erklärung: Deutsch für de, sonst die englische Fassung.
                    val lang = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]?.language
                    val url = BuildConfig.PRIVACY_POLICY_URL.let { if (lang == "de" || !it.endsWith("/privacy/")) it else it + "en/" }
                    SourceRow(Icons.Rounded.Policy, c.accent, stringResource(R.string.privacy_policy), Modifier.clickableRow {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }.testTag("settings.policy"))
                }
            }
            ListSection(header = stringResource(R.string.set_controls)) {
                ToggleRow(stringResource(R.string.set_protect_favorites), settings.protectFavorites, Modifier.testTag("settings.protectFavorites"),
                    subtitle = stringResource(R.string.set_protect_favorites_detail)) { v -> scope.launch { app.updateSettings { it.copy(protectFavorites = v) } } }
                // „Endgültig löschen“: Einschalten nur nach deutlicher Erklärung; Ausschalten sofort.
                ToggleRow(stringResource(R.string.set_delete_permanently), settings.deletePermanently, Modifier.testTag("settings.deletePermanently"),
                    subtitle = stringResource(R.string.set_delete_permanently_detail)) { v ->
                    if (v) confirmPermanent = true else scope.launch { app.updateSettings { it.copy(deletePermanently = false) } } }
                RowDivider()
                ToggleRow(stringResource(R.string.set_haptics), settings.haptics) { v -> scope.launch { app.updateSettings { it.copy(haptics = v) } } }
                RowDivider()
                ToggleRow(stringResource(R.string.set_hints), settings.showGestureHintOnOpen) { v -> scope.launch { app.updateSettings { it.copy(showGestureHintOnOpen = v) } } }
                RowDivider()
                // Bedienungshilfe: Tasten unter dem Medium, gleicher Weg wie die Gesten (Produktentscheidung 2026-10-02).
                ToggleRow(stringResource(R.string.set_buttons), settings.decisionButtons, Modifier.testTag("settings.buttons"),
                    subtitle = stringResource(R.string.set_buttons_detail)) { v -> scope.launch { app.updateSettings { it.copy(decisionButtons = v) } } }
                RowDivider()
                MenuRowPicker(stringResource(R.string.set_later_default), LaterInterval.allowedQuickDays.map { d ->
                    d to if (d == 1) stringResource(R.string.set_tomorrow) else pluralStringResource(R.plurals.set_in_days, d, d)
                }, settings.laterDefaultDays) { v -> scope.launch { app.updateSettings { it.copy(laterDefaultDays = v) } } }
                RowDivider()
                MenuRowPicker(stringResource(R.string.set_appearance), listOf(
                    AppearanceChoice.SYSTEM to stringResource(R.string.appearance_system), AppearanceChoice.LIGHT to stringResource(R.string.appearance_light),
                    AppearanceChoice.DARK to stringResource(R.string.appearance_dark),
                ), settings.appearance) { v -> scope.launch { app.updateSettings { it.copy(appearance = v) } } }
            }
            ListSection(header = stringResource(R.string.set_how_to_swipe)) {
                val defaultLater = if (settings.laterDefaultDays == 1) stringResource(R.string.set_tomorrow) else pluralStringResource(R.plurals.set_in_days, settings.laterDefaultDays, settings.laterDefaultDays)
                GestureLegendRow(Icons.Rounded.ArrowUpward, stringResource(R.string.dir_keep), stringResource(R.string.hint_up), c.keep); RowDivider()
                GestureLegendRow(GestureIcons.Left, stringResource(R.string.dir_trash), stringResource(R.string.hint_left), c.cleanup); RowDivider()
                GestureLegendRow(GestureIcons.Right, stringResource(R.string.dir_later),
                    stringResource(R.string.gesture_with_default, stringResource(R.string.hint_right), defaultLater), c.later); RowDivider()
                GestureLegendRow(Icons.Rounded.ArrowDownward, stringResource(R.string.dir_back), stringResource(R.string.hint_down), c.textSecondary)
            }
            ListSection {
                SourceRow(Icons.Rounded.WorkspacePremium, c.accent, stringResource(R.string.pro_title), Modifier.clickableRow(onClick = nav::openPro),
                    detail = app.capabilities.rewardRemaining?.let { stringResource(R.string.reward_active, minutesLeft(it)) }
                        ?: stringResource(if (purchase == PurchaseState.PURCHASED) R.string.set_pro_active else R.string.set_pro_once))
                if (purchase != PurchaseState.PURCHASED && app.adSdk.privacyOptionsRequired) {
                    RowDivider()
                    PlainButton(stringResource(R.string.set_ad_privacy)) { SystemDialogs.activity?.let { app.adSdk.showPrivacyOptions(it) } }
                }
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
            }
            Spacer(Modifier.height(AppSpacing.xl))
        }
    }
    NoticeDialog(restoreMessage) { restoreMessage = null }
    if (confirmPermanent) ConfirmationSheet(stringResource(R.string.set_delete_permanently_confirm_title),
        stringResource(R.string.set_delete_permanently_confirm_text), stringResource(R.string.set_delete_permanently_confirm_action),
        onConfirm = { scope.launch { app.updateSettings { it.copy(deletePermanently = true) } } }, onDismiss = { confirmPermanent = false })
}

@Composable
fun ToggleRow(title: String, checked: Boolean, modifier: Modifier = Modifier, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    val c = PM.colors
    Row(modifier.fillMaxWidth().heightIn(min = AppSpacing.minTouch).toggleable(checked, role = Role.Switch) { onChange(it) }
        .padding(vertical = if (subtitle != null) AppSpacing.xs else 0.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.body, color = c.textPrimary)
            if (subtitle != null) Text(subtitle, style = AppTypography.caption, color = c.textSecondary)
        }
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
            val app = LocalApp.current
            val scope = rememberCoroutineScope()
            var reset by remember { mutableStateOf(false) }
            ListSection {
                PlainButton(stringResource(R.string.help_tours_again), Modifier.testTag("help.tours")) {
                    scope.launch {
                        app.updateSettings { it.copy(todayTourSeen = false, feedTourSeen = false, monthsTourSeen = false, seriesTourSeen = false,
                            gridTourSeen = false, editorTourSeen = false, gestureHintSeen = false) }
                        reset = true
                    }
                }
                if (reset) Text(stringResource(R.string.help_tours_reset), style = AppTypography.caption, color = c.textSecondary,
                    modifier = Modifier.padding(bottom = AppSpacing.xs))
            }
            for ((h, t) in listOf(R.string.help_decide to R.string.help_decide_text, R.string.help_delete to R.string.help_delete_text,
                R.string.help_cloud to R.string.help_cloud_text, R.string.help_free to R.string.help_free_text)) {
                ListSection(header = stringResource(h)) {
                    // help_free_text enthält das kostenlose Tageslimit als Platzhalter (Zahl nur aus der Konfiguration).
                    val text = if (t == R.string.help_free_text) stringResource(t, Capabilities.FREE_DAILY_CLEANUP_LIMIT) else stringResource(t)
                    Text(text, style = AppTypography.body, color = c.textPrimary, modifier = Modifier.padding(vertical = AppSpacing.s))
                }
            }
            Spacer(Modifier.height(AppSpacing.xl))
        }
    }
}
