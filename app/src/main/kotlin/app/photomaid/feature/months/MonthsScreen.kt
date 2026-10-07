package app.photomaid.feature.months

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.photomaid.AppModel
import app.photomaid.FeedRequest
import app.photomaid.R
import app.photomaid.core.progress.MonthProgress
import app.photomaid.core.progress.MonthState
import app.photomaid.core.progress.ProgressRules
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppStroke
import app.photomaid.design.AppTypography
import app.photomaid.design.CoachHost
import app.photomaid.design.CoachStep
import app.photomaid.design.EmptyState
import app.photomaid.design.MonthText
import app.photomaid.design.PM
import app.photomaid.design.RowDivider
import app.photomaid.design.RowGroup
import app.photomaid.design.ScreenScaffold
import app.photomaid.design.SectionTitle
import app.photomaid.design.coachTarget
import app.photomaid.media.reviewableMedia
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.PageEndSpacer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Alle Monate mit echtem Fortschritt aus dem Ledger (eine Mediathek-Abfrage, Monate sind nur Filter). */
suspend fun loadMonths(app: AppModel): List<MonthProgress> = withContext(Dispatchers.IO) {
    if (!app.photos.access.value.canRead) return@withContext emptyList()
    val all = app.photos.reviewableMedia()
    val records = app.ledger.records(all.map { it.reference })
    ProgressRules.months(all, { it.creationDate }, { records[it.reference] })
}

/** Aufräumen › Mediathek › „Nach Monat“: Noch nicht begonnen · In Arbeit · Durchgesehen. */
@Composable
fun MonthsScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val token by app.photos.changeToken.collectAsState()
    val read by app.read.collectAsState()
    val settings by app.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var months by remember { mutableStateOf<List<MonthProgress>?>(null) }
    LaunchedEffect(token, read.events.size, read.basket.size, read.laterRecords.size) { months = loadMonths(app) }

    val steps = listOf(
        CoachStep("months.row", stringResource(R.string.tour_months_row_title), stringResource(R.string.tour_months_row_text)),
        CoachStep("months.later", stringResource(R.string.tour_months_later_title), stringResource(R.string.tour_months_later_text)),
        CoachStep("months.more", stringResource(R.string.tour_months_more_title), stringResource(R.string.tour_months_more_text)),
    )
    CoachHost(steps, active = !settings.monthsTourSeen && !months.isNullOrEmpty(),
        onFinish = { scope.launch { app.updateSettings { it.copy(monthsTourSeen = true) } } }, modifier = Modifier.fillMaxSize()) {
        ScreenScaffold(stringResource(R.string.months_title), onBack = nav::back) { mod ->
            val list = months
            when {
                list == null -> Box(mod, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Box(mod, contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Rounded.CalendarMonth, stringResource(R.string.months_empty_title), stringResource(R.string.months_empty_text))
                }
                else -> LazyColumn(mod.padding(horizontal = AppSpacing.pageMargin).testTag("months"), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                    item { Text(stringResource(R.string.months_intro), style = AppTypography.subheadline, color = c.textSecondary) }
                    list.groupBy { it.month.year }.forEach { (year, inYear) ->
                        item(key = "y$year") { SectionTitle("$year", Modifier.padding(top = AppSpacing.s)) }
                        item(key = "g$year") {
                            RowGroup {
                                inYear.forEachIndexed { i, m ->
                                    if (i > 0) RowDivider()
                                    MonthRow(m, first = list.first() == m,
                                        open = { nav.openFeed(FeedRequest(FeedRequest.Source.Month(m.month))) },
                                        again = { nav.openFeed(FeedRequest(FeedRequest.Source.Month(m.month, again = true))) },
                                        grid = { nav.openGrid(FeedRequest(FeedRequest.Source.Month(m.month, again = true))) })
                                }
                            }
                        }
                    }
                    item { PageEndSpacer() }
                }
            }
        }
    }
}

@Composable
private fun MonthRow(m: MonthProgress, first: Boolean, open: () -> Unit, again: () -> Unit, grid: () -> Unit) {
    val c = PM.colors
    var menu by remember { mutableStateOf(false) }
    val title = MonthText.title(m.month)
    val progress = when (m.state) {
        MonthState.DONE -> stringResource(R.string.month_done)
        MonthState.NOT_STARTED -> pluralStringResource(R.plurals.month_not_started, m.total, m.total)
        MonthState.IN_PROGRESS -> pluralStringResource(R.plurals.month_progress, m.total, m.reviewed, m.total)
    }
    val later = if (m.later > 0) pluralStringResource(R.plurals.month_later, m.later, m.later) else null
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (first) Modifier.coachTarget("months.row") else Modifier)
        .clickable(enabled = m.state != MonthState.DONE, role = Role.Button, onClick = open).testTag("month.${m.month}")
        .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(title, progress, later).joinToString(", ") }
        .padding(vertical = AppSpacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = AppTypography.body.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
                if (m.state == MonthState.DONE) Icon(Icons.Rounded.CheckCircle, null, tint = c.success, modifier = Modifier.size(18.dp))
            }
            Text(progress, style = AppTypography.caption, color = c.textSecondary)
            if (later != null) Text(later, style = AppTypography.caption, color = c.later,
                modifier = if (first) Modifier.coachTarget("months.later") else Modifier)
            if (m.state == MonthState.IN_PROGRESS) {
                Spacer(Modifier.height(2.dp))
                LinearProgressIndicator(progress = { m.fraction }, modifier = Modifier.fillMaxWidth().height(AppStroke.progress).clip(CircleShape),
                    color = c.accent, trackColor = c.backgroundTint, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
            }
        }
        Box {
            val label = stringResource(R.string.month_actions, title)
            Box(Modifier.size(AppSpacing.minTouch).clickable(role = Role.Button) { menu = true }.semantics { contentDescription = label }
                .then(if (first) Modifier.coachTarget("months.more") else Modifier).testTag("month.more"), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MoreHoriz, null, tint = c.textSecondary)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.grid_open)) }, leadingIcon = { Icon(Icons.Rounded.GridView, null) },
                    onClick = { menu = false; grid() }, modifier = Modifier.testTag("month.grid"))
                DropdownMenuItem({ Text(stringResource(R.string.month_again)) }, leadingIcon = { Icon(Icons.Rounded.Replay, null) },
                    enabled = m.reviewed > 0, onClick = { menu = false; again() }, modifier = Modifier.testTag("month.again"))
            }
        }
    }
}
