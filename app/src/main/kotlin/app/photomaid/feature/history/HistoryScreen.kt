package app.photomaid.feature.history

import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.HighlightOff
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.design.spacedItem
import app.photomaid.design.lazyListSection
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.History
import app.photomaid.design.EmptyState
import app.photomaid.core.decisions.DecisionEvent
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.HistoryKind
import app.photomaid.core.model.ItemProvider
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.DateText
import app.photomaid.design.DayText
import app.photomaid.design.GlassIconButton
import app.photomaid.design.ListSection
import app.photomaid.design.PM
import app.photomaid.design.PhotoMaidColors
import app.photomaid.design.RowDivider
import app.photomaid.design.ScreenScaffold
import app.photomaid.design.SegmentedPicker
import app.photomaid.design.SurfaceCard
import app.photomaid.feature.common.ThumbnailView
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.TabBarClearance
import kotlinx.coroutines.launch
import java.time.ZoneId

private enum class HistoryFilter { ALL, KEPT, CLEANED, LATER }

/** Screen 13 – Verlauf. Alle / Behalten / Bereinigt / Später. Echte Events, keine erfundenen Statistiken. */
@Composable
fun HistoryScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val scope = rememberCoroutineScope()
    val read by app.read.collectAsState()
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    val filtered = read.events.filter { e ->
        when (filter) {
            HistoryFilter.ALL -> true
            HistoryFilter.KEPT -> e.to.disposition == Disposition.KEPT && e.kind != HistoryKind.UNDO
            HistoryFilter.CLEANED -> e.kind == HistoryKind.CLEANUP_CONFIRMED || e.kind == HistoryKind.RECONCILED_MISSING
            HistoryFilter.LATER -> e.to.disposition == Disposition.LATER && e.kind != HistoryKind.UNDO
        }
    }
    val zone = ZoneId.systemDefault()
    // Darstellung: zusammengehörige Später+Termin-Paare als ein Vorgang; das Journal selbst bleibt unverändert.
    val entries = groupHistory(filtered).asReversed()
    val days = entries.groupBy { it.primary.at.atZone(zone).toLocalDate() }
    val keys = days.keys.sortedDescending().take(60)

    ScreenScaffold(stringResource(R.string.tab_history), onBack = null, actions = {
        GlassIconButton(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.undo), enabled = read.canUndo) { scope.launch { app.undo() } }
    }) { mod ->
        val headers = keys.map { day -> val n = days.getValue(day).size
            pluralStringResource(R.plurals.history_day_header, n, DateText.complete(day.atStartOfDay(zone).toInstant()), n) }
        // Lazy je Eintrag: langer Verlauf scrollt flüssig (vorher bis zu 300 Zeilen je Tag auf einmal).
        LazyColumn(mod.padding(horizontal = AppSpacing.pageMargin)) {
            spacedItem("filter") {
                SegmentedPicker(listOf(HistoryFilter.ALL to stringResource(R.string.history_all), HistoryFilter.KEPT to stringResource(R.string.history_kept),
                    HistoryFilter.CLEANED to stringResource(R.string.history_cleaned), HistoryFilter.LATER to stringResource(R.string.history_later)), filter) { filter = it }
            }
            if (keys.isEmpty()) spacedItem("empty") {
                if (read.events.isEmpty()) EmptyState(Icons.Rounded.History, stringResource(R.string.history_empty_title), stringResource(R.string.history_empty))
                else EmptyState(Icons.Rounded.FilterList, stringResource(R.string.history_filter_empty_title), stringResource(R.string.history_filter_empty))
            }
            keys.forEachIndexed { i, day ->
                lazyListSection(day.toString(), days.getValue(day), { it.primary.id }, header = headers[i]) { e -> HistoryRow(e) }
            }
            item { Spacer(Modifier.height(TabBarClearance)) }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry) {
    val c = PM.colors
    val e = entry.primary
    val (icon, tint, title) = describe(e, c)
    // Termin des Vorgangs (zusammengefasster Termin oder ein eigenständig geänderter Termin).
    val day = (entry.schedule ?: e.takeIf { it.kind == HistoryKind.SCHEDULE_CHANGED })?.to?.dueDay
    val text = when {
        day != null -> stringResource(R.string.history_with_day, stringResource(title), DayText.string(day))
        entry.itemCount > 1 -> stringResource(R.string.history_with_day, stringResource(title), pluralStringResource(R.plurals.items_count, entry.itemCount, entry.itemCount))
        else -> stringResource(title)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        if (e.item.provider == ItemProvider.PhotoLibrary) ThumbnailView(e.item.localIdentifier, 44.dp)
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
            Text(DateText.time(e.at), style = AppTypography.caption, color = c.textSecondary)
        }
    }
}

private fun describe(e: DecisionEvent, c: PhotoMaidColors): Triple<ImageVector, Color, Int> = when (e.kind) {
    HistoryKind.CLEANUP_CONFIRMED -> Triple(Icons.Rounded.DeleteForever, c.cleanup, R.string.h_cleaned)
    HistoryKind.RECONCILED_MISSING -> Triple(Icons.Rounded.HelpOutline, c.warning, R.string.h_reconciled)
    HistoryKind.CLEANUP_NOT_PERFORMED -> Triple(Icons.Rounded.HighlightOff, c.textSecondary, R.string.h_not_performed)
    HistoryKind.UNDO -> Triple(Icons.AutoMirrored.Rounded.Undo, c.textSecondary, R.string.h_undo)
    HistoryKind.SCHEDULE_CHANGED -> Triple(Icons.Rounded.CalendarMonth, c.later, R.string.h_schedule)
    HistoryKind.DEFERRAL_REMOVED -> Triple(Icons.Rounded.EventBusy, c.textSecondary, R.string.h_deferral_removed)
    HistoryKind.UNMARKED -> Triple(Icons.Rounded.RemoveCircleOutline, c.textSecondary, R.string.h_unmarked)
    HistoryKind.DECISION, HistoryKind.CORRECTION -> {
        val corr = e.kind == HistoryKind.CORRECTION
        when (e.to.disposition) {
            Disposition.KEPT -> Triple(Icons.Rounded.CheckCircle, c.keep, if (corr) R.string.h_kept_corr else R.string.h_kept)
            Disposition.MARKED_FOR_CLEANUP -> Triple(Icons.Rounded.Delete, c.cleanup, if (corr) R.string.h_trash_corr else R.string.h_trash)
            Disposition.LATER -> Triple(Icons.Rounded.Schedule, c.later, if (corr) R.string.h_later_corr else R.string.h_later)
            else -> Triple(Icons.Rounded.Circle, c.textSecondary, R.string.h_changed)
        }
    }
}
