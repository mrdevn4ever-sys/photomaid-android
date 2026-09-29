package app.photomaid.feature.rules

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.photomaid.R
import app.photomaid.core.rules.ReviewRule
import app.photomaid.core.rules.RuleFilter
import app.photomaid.core.rules.RuleSource
import app.photomaid.core.rules.RuleTrigger
import app.photomaid.design.AppLocale
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassButton
import app.photomaid.design.ListSection
import app.photomaid.design.MenuRowPicker
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.RowDivider
import app.photomaid.design.ScreenScaffold
import app.photomaid.design.SizeText
import app.photomaid.design.SurfaceCard
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.files.ConnectedSource
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.TabBarClearance
import app.photomaid.feature.settings.ToggleRow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle

/** iOS-Wochentag (1 = Sonntag … 7 = Samstag) als Name. */
fun weekdayName(wd: Int): String = DayOfWeek.of(((wd + 5) % 7) + 1).getDisplayName(TextStyle.FULL, AppLocale)

object RuleText {
    fun title(ctx: Context, r: ReviewRule): String {
        val source = ctx.getString(when (r.source) {
            RuleSource.Screenshots -> R.string.cat_screenshots; RuleSource.Photos -> R.string.cat_photos
            RuleSource.Videos -> R.string.cat_videos; is RuleSource.ConnectedSource -> R.string.rule_connected_folder
        })
        return when (val f = r.filter) {
            is RuleFilter.OlderThan -> ctx.resources.getQuantityString(R.plurals.rule_title_older, f.days, source, f.days)
            is RuleFilter.KnownSizeAtLeast -> ctx.getString(R.string.rule_title_size, source, SizeText.bytes(f.bytes))
            RuleFilter.All -> ctx.getString(R.string.rule_title_all, source)
        }
    }

    fun detail(ctx: Context, r: ReviewRule): String = when (val t = r.trigger) {
        RuleTrigger.Daily -> ctx.getString(R.string.rule_detail_daily)
        is RuleTrigger.Weekly -> ctx.getString(R.string.rule_detail_weekly, weekdayName(t.weekday))
        is RuleTrigger.NewlyDiscovered -> ctx.resources.getQuantityString(R.plurals.rule_detail_new, t.count, t.count)
    }
}

/**
 * Screen 12 – Regeln & Routinen. Erstellen, Bearbeiten, Pausieren, Löschen, Jetzt ausführen.
 * Regeln erzeugen immer Review-Aufgaben, niemals automatische Löschung.
 */
@Composable
fun RulesScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val state by app.rules.state.collectAsState()
    val sources by app.sources.sources.collectAsState()
    var editing by remember { mutableStateOf<ReviewRule?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val caps = app.capabilities

    ScreenScaffold(stringResource(R.string.rules_title), onBack = nav::back) { mod ->
        LazyColumn(mod.padding(horizontal = AppSpacing.pageMargin), verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            item {
                SurfaceCard {
                    Text(stringResource(R.string.rules_intro), style = AppTypography.subheadline, color = c.textSecondary)
                    if (!caps.persistentRoutines) {
                        Spacer(Modifier.height(AppSpacing.s))
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                            Icon(Icons.Rounded.Info, null, tint = c.accent)
                            Text(stringResource(if (caps.oneTimeRuleTestScan) R.string.rules_preview_hint else R.string.rules_pro_hint),
                                style = AppTypography.caption, color = c.textPrimary)
                        }
                        PlainButton(stringResource(R.string.pro_view), onClick = nav::openPro)
                    }
                }
            }
            items(state.rules, key = { it.id.rawValue.toString() }) { rule ->
                SurfaceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp_)) {
                            Text(RuleText.title(ctx, rule), style = AppTypography.headline, color = c.textPrimary)
                            Text(RuleText.detail(ctx, rule), style = AppTypography.caption, color = c.textSecondary)
                        }
                        Switch(rule.isEnabled, onCheckedChange = { on -> scope.launch { app.rules.upsert(rule.copy(isEnabled = on), app.reminders) } },
                            colors = SwitchDefaults.colors(checkedTrackColor = c.success, checkedThumbColor = Color.White))
                    }
                    Spacer(Modifier.height(AppSpacing.s))
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        GlassButton(stringResource(R.string.rules_run_now)) {
                            scope.launch {
                                val ok = app.rules.runNow(rule, app)
                                message = res.getString(if (ok) R.string.rules_run_done else R.string.rules_run_unavailable)
                            }
                        }
                        GlassButton(stringResource(R.string.rules_edit)) { editing = rule }
                        // iOS: Wischaktion „Löschen“. Android: sichtbare Aktion.
                        GlassButton(stringResource(R.string.rules_delete), tint = c.cleanup) { scope.launch { app.rules.delete(rule.id, app.reminders) } }
                    }
                }
            }
            item {
                SurfaceCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Icon(Icons.Rounded.Add, null, tint = c.accent)
                        PlainButton(stringResource(R.string.rules_add)) {
                            editing = ReviewRule(source = RuleSource.Screenshots, filter = RuleFilter.OlderThan(30), trigger = RuleTrigger.Daily)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), modifier = Modifier.padding(horizontal = AppSpacing.xs)) {
                    Icon(Icons.Rounded.GppGood, null, tint = c.textSecondary)
                    Text(stringResource(R.string.rules_local), style = AppTypography.caption, color = c.textSecondary)
                }
            }
            item { Spacer(Modifier.height(TabBarClearance)) }
        }
    }
    editing?.let { rule ->
        RuleEditor(rule, sources, onDismiss = { editing = null }) { r -> scope.launch { app.rules.upsert(r, app.reminders) } }
    }
    NoticeDialog(message) { message = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditor(initial: ReviewRule, sources: List<ConnectedSource>, onDismiss: () -> Unit, save: (ReviewRule) -> Unit) {
    val c = PM.colors
    var source by remember { mutableStateOf(initial.source) }
    var notify by remember { mutableStateOf(initial.notify) }
    var filterKind by remember { mutableIntStateOf(when (initial.filter) { is RuleFilter.OlderThan -> 0; is RuleFilter.KnownSizeAtLeast -> 1; RuleFilter.All -> 2 }) }
    var days by remember { mutableIntStateOf((initial.filter as? RuleFilter.OlderThan)?.days ?: 30) }
    var sizeMB by remember { mutableIntStateOf(((initial.filter as? RuleFilter.KnownSizeAtLeast)?.bytes?.div(1_000_000))?.toInt() ?: 1000) }
    var triggerKind by remember { mutableIntStateOf(when (initial.trigger) { RuleTrigger.Daily -> 0; is RuleTrigger.Weekly -> 1; is RuleTrigger.NewlyDiscovered -> 2 }) }
    var weekday by remember { mutableIntStateOf((initial.trigger as? RuleTrigger.Weekly)?.weekday ?: 1) }
    var count by remember { mutableIntStateOf((initial.trigger as? RuleTrigger.NewlyDiscovered)?.count ?: 10) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.m),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
                Text(stringResource(R.string.rule_editor_title), style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                TextButton({
                    val filter = listOf(RuleFilter.OlderThan(days), RuleFilter.KnownSizeAtLeast(sizeMB.toLong() * 1_000_000), RuleFilter.All)[filterKind]
                    val trigger = listOf(RuleTrigger.Daily, RuleTrigger.Weekly(weekday), RuleTrigger.NewlyDiscovered(count))[triggerKind]
                    save(initial.copy(source = source, filter = filter, trigger = trigger, notify = notify))
                    onDismiss()
                }) { Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold) }
            }
            ListSection {
                MenuRowPicker(stringResource(R.string.rule_source), listOf<Pair<RuleSource, String>>(
                    RuleSource.Screenshots to stringResource(R.string.cat_screenshots), RuleSource.Photos to stringResource(R.string.cat_photos),
                    RuleSource.Videos to stringResource(R.string.cat_videos),
                ) + sources.map { RuleSource.ConnectedSource(it.id) to it.displayName }, source) { source = it }
                RowDivider()
                MenuRowPicker(stringResource(R.string.filter), listOf(0 to stringResource(R.string.rule_filter_older), 1 to stringResource(R.string.rule_filter_size),
                    2 to stringResource(R.string.rule_filter_all)), filterKind) { filterKind = it }
                if (filterKind == 0) { RowDivider(); Stepper(pluralStringResource(R.plurals.rule_days, days, days), days, 1..3650, 1) { days = it } }
                if (filterKind == 1) { RowDivider(); Stepper(stringResource(R.string.rule_mb, sizeMB), sizeMB, 10..100_000, 100) { sizeMB = it } }
                RowDivider()
                MenuRowPicker(stringResource(R.string.rule_trigger), listOf(0 to stringResource(R.string.rule_trigger_daily), 1 to stringResource(R.string.rule_trigger_weekly),
                    2 to stringResource(R.string.rule_trigger_new)), triggerKind) { triggerKind = it }
                if (triggerKind == 1) {
                    RowDivider()
                    MenuRowPicker(stringResource(R.string.rule_weekday), (1..7).map { it to weekdayName(it) }, weekday) { weekday = it }
                    RowDivider()
                    ToggleRow(stringResource(R.string.rule_notify), notify) { notify = it }
                }
                if (triggerKind == 2) { RowDivider(); Stepper(pluralStringResource(R.plurals.rule_count, count, count), count, 1..500, 1) { count = it } }
            }
            Text(stringResource(R.string.rule_note), style = AppTypography.caption, color = c.textSecondary)
            Spacer(Modifier.height(AppSpacing.l))
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    val c = PM.colors
    Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AppTypography.body, color = c.textPrimary, modifier = Modifier.weight(1f))
        TextButton({ onChange((value - step).coerceIn(range)) }, enabled = value > range.first) { Text("−", style = AppTypography.title) }
        TextButton({ onChange((value + step).coerceIn(range)) }, enabled = value < range.last) { Text("+", style = AppTypography.title) }
    }
}

private val Int.dp_ get() = androidx.compose.ui.unit.Dp(toFloat())
