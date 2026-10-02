package app.photomaid.feature.later

import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.photomaid.FeedRequest
import app.photomaid.R
import app.photomaid.design.spacedItem
import app.photomaid.design.lazyListSection
import androidx.compose.material.icons.rounded.Schedule
import app.photomaid.design.EmptyState
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.later.LaterGroup
import app.photomaid.core.later.ReviewQueues
import app.photomaid.core.model.ItemProvider
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.MediaKind
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.DayText
import app.photomaid.design.ListSection
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.RowDivider
import app.photomaid.design.ScreenScaffold
import app.photomaid.design.SurfaceCard
import app.photomaid.feature.common.ThumbnailView
import app.photomaid.feature.review.LaterDateDialog
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.testTag
import app.photomaid.design.GlassButton
import app.photomaid.notifications.ReminderScheduler
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.PageEndSpacer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen 11 – Später. Fällige und kommende Wiedervorlagen in echten Datumsgruppen.
 * Ansehen, Datum ändern, jetzt prüfen, Zurückstellung entfernen (löscht keine Datei).
 */
@Composable
fun LaterScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val scope = rememberCoroutineScope()
    val read by app.read.collectAsState()
    val groups = ReviewQueues.laterGroups(read.laterRecords, app.today)
    var editing by remember { mutableStateOf<ItemRecord?>(null) }
    var names by remember { mutableStateOf<Map<ItemReference, String>>(emptyMap()) }
    var kinds by remember { mutableStateOf<Map<String, MediaKind>>(emptyMap()) }
    LaunchedEffect(read.laterRecords.size) {
        withContext(Dispatchers.IO) {
            names = read.laterRecords.filter { it.item.provider != ItemProvider.PhotoLibrary }.associate { it.item to app.sources.displayName(it.item) }
            // Typ (Foto/Video/Screenshot) aus derselben MediaStore-Abfrage wie die Vorschau – kein zusätzlicher Scan.
            val ids = read.laterRecords.filter { it.item.provider == ItemProvider.PhotoLibrary }.map { it.item.localIdentifier }
            kinds = if (ids.isEmpty()) emptyMap() else app.photos.assets(ids).associate { it.id to it.kind }
        }
    }

    val notif by app.reminders.access.collectAsState()
    // Mitteilungen erst hier anfragen: im Kontext von „Später“, und nur, wenn es etwas Zurückgestelltes gibt.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { app.updateSettings { s -> s.copy(notificationsOptIn = true) }; app.reminders.refresh() }
    }
    ScreenScaffold(stringResource(R.string.later), onBack = nav::back) { mod ->
        val headers = groups.map { (key, records) ->
            when (key) {
                LaterGroup.Due -> stringResource(R.string.later_group_due, records.size)
                LaterGroup.Tomorrow -> stringResource(R.string.later_group_tomorrow, records.size)
                LaterGroup.WithinSevenDays -> stringResource(R.string.later_group_7, records.size)
                is LaterGroup.Later -> stringResource(R.string.later_group_from, DayText.string(key.day), records.size)
                LaterGroup.NoDate -> stringResource(R.string.later_group_nodate, records.size)
                LaterGroup.Bookmarked -> stringResource(R.string.later_group_bookmarked, records.size)
            }
        }
        val reviewDue = groups.firstOrNull { it.first == LaterGroup.Due }?.second?.size?.let { stringResource(R.string.later_review_now_count, it) }
        // Lazy je Zeile: lange Später-Listen laden nur sichtbare Vorschaubilder.
        LazyColumn(mod.padding(horizontal = AppSpacing.pageMargin)) {
            if (groups.isNotEmpty() && notif == ReminderScheduler.Access.NOT_DETERMINED) spacedItem("notify") {
                SurfaceCard(Modifier.testTag("later.notify")) {
                    Text(stringResource(R.string.later_notify_title), style = AppTypography.headline, color = c.textPrimary)
                    Text(stringResource(R.string.privacy_notifications_text), style = AppTypography.caption, color = c.textSecondary)
                    Spacer(Modifier.height(AppSpacing.s))
                    GlassButton(stringResource(R.string.privacy_notifications_allow)) {
                        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else scope.launch { app.updateSettings { s -> s.copy(notificationsOptIn = true) }; app.reminders.refresh() }
                    }
                }
            }
            if (groups.isEmpty()) spacedItem("empty") {
                EmptyState(Icons.Rounded.Schedule, stringResource(R.string.later_empty_title), stringResource(R.string.later_empty),
                    hint = stringResource(R.string.later_empty_hint), tint = c.later)
            }
            groups.forEachIndexed { gi, (key, records) ->
                lazyListSection(key.toString(), records, { it.item.localIdentifier }, header = headers[gi],
                    trailingRow = if (key == LaterGroup.Due && reviewDue != null) ({
                        PlainButton(reviewDue) { nav.openFeed(FeedRequest(FeedRequest.Source.Items(records.map { it.item }, R.string.feed_title_due))) }
                    }) else null) { r ->
                    LaterRow(r, names[r.item], kinds[r.item.localIdentifier], app.today, onEdit = { editing = r },
                        onReview = { nav.openFeed(FeedRequest(FeedRequest.Source.Items(listOf(r.item), R.string.later))) },
                        onRemove = { scope.launch { app.decide(r.item, DecisionAction.RemoveDeferral) } })
                }
            }
            item { PageEndSpacer() }
        }
    }
    editing?.let { r ->
        LaterDateDialog(app.today, onDismiss = { editing = null }) { day ->
            scope.launch {
                app.decide(r.item, DecisionAction.Schedule(day))
                withContext(Dispatchers.IO) { app.reminders.scheduleDueReminder(day) }
            }
        }
    }
}

@Composable
private fun LaterRow(r: ItemRecord, fileName: String?, kind: MediaKind?, today: LocalDay, onEdit: () -> Unit, onReview: () -> Unit, onRemove: () -> Unit) {
    val c = PM.colors
    var menu by remember { mutableStateOf(false) }
    val isPhoto = r.item.provider == ItemProvider.PhotoLibrary
    val title = stringResource(when {
        !isPhoto -> R.string.file
        kind == MediaKind.VIDEO -> R.string.video
        kind == MediaKind.SCREENSHOT -> R.string.screenshot
        kind != null -> R.string.photo
        else -> R.string.medium // Medium nicht (mehr) lesbar: Typ unbekannt, nichts erfinden.
    })
    val day = r.dueDay
    val when_ = when {
        r.bookmarked -> stringResource(R.string.later_bookmarked_row)
        day == null -> stringResource(R.string.later_next_review)
        day == today -> stringResource(R.string.later_due_today)
        day < today -> stringResource(R.string.later_due_since, DayText.string(day))
        else -> stringResource(R.string.later_review_on, DayText.string(day))
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp_).padding(vertical = AppSpacing.xxs).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        ThumbnailView(if (isPhoto) r.item.localIdentifier else null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp_)) {
            Text(title, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1)
            Text(when_, style = AppTypography.footnote, color = if (day != null && day <= today) c.later else c.textSecondary)
            if (!isPhoto) Text(fileName ?: r.item.localIdentifier.substringAfterLast('/'), style = AppTypography.caption, color = c.textSecondary,
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        Box {
            val label = stringResource(R.string.actions)
            Box(Modifier.size(AppSpacing.minTouch).clickable { menu = true }.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MoreHoriz, null, tint = c.textSecondary)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.change_date)) }, leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null) }, onClick = { menu = false; onEdit() })
                if (isPhoto) DropdownMenuItem({ Text(stringResource(R.string.later_review_now)) }, leadingIcon = { Icon(Icons.Rounded.Visibility, null) }, onClick = { menu = false; onReview() })
                DropdownMenuItem({ Text(stringResource(if (r.bookmarked) R.string.later_back_to_stack else R.string.later_remove)) },
                    leadingIcon = { Icon(Icons.Rounded.EventBusy, null) }, onClick = { menu = false; onRemove() }, modifier = Modifier.testTag("later.remove"))
            }
        }
    }
}

private val Int.dp_ get() = androidx.compose.ui.unit.Dp(toFloat())
