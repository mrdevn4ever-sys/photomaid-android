package app.photomaid.feature.today

import androidx.compose.foundation.layout.Arrangement
import app.photomaid.design.AppRadii
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.FilterNone
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.photomaid.FeedRequest
import app.photomaid.R
import app.photomaid.core.later.ReviewQueues
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.core.storage.DeviceCapacity
import app.photomaid.design.AppBackground
import app.photomaid.design.AppSpacing
import app.photomaid.design.readableWidth
import androidx.compose.foundation.layout.wrapContentWidth
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassIconButton
import app.photomaid.design.LargeTitleHeader
import app.photomaid.design.MetricCard
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.design.RowDivider
import app.photomaid.design.SizeText
import app.photomaid.design.SourceRow
import app.photomaid.design.SurfaceCard
import app.photomaid.design.clickableRow
import app.photomaid.feature.common.DeviceStorage
import app.photomaid.media.MediaCategory
import app.photomaid.media.PhotoAccess
import app.photomaid.media.reviewableMedia
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.Routes
import app.photomaid.navigation.TabBarClearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Screen 1 – Heute: fortsetzbare Review-Session, echte Kennzahlen, Wieder-fällig-Hinweis. */
@Composable
fun TodayScreen(nav: Nav) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val c = PM.colors
    val access by app.photos.access.collectAsState()
    val token by app.photos.changeToken.collectAsState()
    val read by app.read.collectAsState()
    val sources by app.sources.sources.collectAsState()
    var counts by remember { mutableStateOf<Map<MediaCategory, Int>>(emptyMap()) }
    var pending by remember { mutableStateOf<Int?>(null) }
    var device by remember { mutableStateOf<DeviceCapacity?>(null) }

    LaunchedEffect(token, access, read.basket.size, read.laterRecords.size) {
        withContext(Dispatchers.IO) {
            device = DeviceStorage.read(ctx)
            counts = MediaCategory.entries.associateWith { app.photos.count(it) }
            pending = if (!access.canRead) null else {
                val refs = app.photos.reviewableMedia().map { it.reference }
                ReviewQueues.normalQueue(refs, app.ledger.records(refs), ReviewSessionID(), app.today).size
            }
        }
    }

    AppBackground {
        Column(Modifier.fillMaxSize().wrapContentWidth().readableWidth().fillMaxWidth().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = AppSpacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            LargeTitleHeader(stringResource(R.string.app_name), stringResource(R.string.today_subtitle)) {
                GlassIconButton(Icons.Rounded.Settings, stringResource(R.string.settings), onClick = nav::openSettings)
            }
            if (!access.canRead) {
                SurfaceCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Icon(Icons.Rounded.PhotoLibrary, null, tint = c.accent)
                        Text(stringResource(R.string.today_access_title), style = AppTypography.headline, color = c.textPrimary)
                    }
                    Spacer(Modifier.height(AppSpacing.s))
                    Text(stringResource(if (access == PhotoAccess.DENIED) R.string.today_access_denied else R.string.today_access_default),
                        style = AppTypography.subheadline, color = c.textSecondary)
                    Spacer(Modifier.height(AppSpacing.s))
                    ProminentButton(stringResource(R.string.learn_more), onClick = nav::openPrivacy)
                }
            }
            if (access.canRead) ReviewEntryCard(pending, hasHistory = read.events.isNotEmpty()) {
                nav.openFeed(FeedRequest(FeedRequest.Source.AllMedia))
            }
            if (app.dueCount > 0) {
                SurfaceCard(onClick = { nav.openFeed(FeedRequest(FeedRequest.Source.Due)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Icon(Icons.Rounded.EventRepeat, null, tint = c.accent)
                        Text(pluralStringResource(R.plurals.today_due, app.dueCount, app.dueCount), style = AppTypography.headline, color = c.textPrimary)
                    }
                }
            }
            if (read.reconciliationNeeded.isNotEmpty()) {
                SurfaceCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Icon(Icons.Rounded.Sync, null, tint = c.warning)
                        Text(stringResource(R.string.today_reconciliation), style = AppTypography.subheadline, color = c.warning)
                    }
                }
            }
            device?.let { d ->
                MetricCard(SizeText.bytes(d.usedBytes), stringResource(R.string.storage_used_of, SizeText.bytes(d.totalBytes)),
                    progress = if (d.totalBytes > 0) d.usedBytes.toFloat() / d.totalBytes else null,
                    footnote = stringResource(R.string.storage_device_footnote))
            }
            SurfaceCard {
                @Composable
                fun cat(cat: MediaCategory, icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int) {
                    val enabled = access.canRead
                    SourceRow(icon, c.accent, stringResource(title), Modifier.alpha(if (enabled) 1f else 0.45f)
                        .then(if (enabled) Modifier.clickableRow { nav.openFeed(FeedRequest(FeedRequest.Source.Category(cat))) } else Modifier),
                        value = counts[cat]?.toString(), valueDescription = counts[cat]?.let { pluralStringResource(R.plurals.items_count, it, it) })
                }
                cat(MediaCategory.PHOTOS, Icons.Rounded.Photo, R.string.cat_photos); RowDivider()
                cat(MediaCategory.VIDEOS, Icons.Rounded.Videocam, R.string.cat_videos); RowDivider()
                cat(MediaCategory.SCREENSHOTS, Icons.Rounded.Screenshot, R.string.cat_screenshots); RowDivider()
                SourceRow(Icons.Rounded.Download, c.accent, stringResource(if (app.sources.downloadsSource == null) R.string.files else R.string.downloads),
                    Modifier.clickableRow { nav.push(Routes.FILES) },
                    subtitle = stringResource(if (sources.isEmpty()) R.string.choose_folder else R.string.connected_sources_short))
                RowDivider()
                SourceRow(Icons.Rounded.FilterNone, c.indigo, stringResource(R.string.duplicates), Modifier.clickableRow { nav.push(Routes.DUPLICATES) },
                    subtitle = stringResource(R.string.duplicates_subtitle_today))
                RowDivider()
                SourceRow(Icons.Rounded.Bookmark, c.later, stringResource(R.string.later), Modifier.clickableRow { nav.push(Routes.LATER) },
                    value = read.laterRecords.size.toString(),
                    valueDescription = pluralStringResource(R.plurals.items_count, read.laterRecords.size, read.laterRecords.size))
            }
            Spacer(Modifier.height(TabBarClearance))
        }
    }
}


/**
 * Haupteinstieg in die Prüfung – stärker gewichtet als Speicher und Kategorien.
 * Zustand: neue Prüfung, Fortsetzung (es gibt schon Entscheidungen) oder nichts offen.
 */
@Composable
private fun ReviewEntryCard(pending: Int?, hasHistory: Boolean, start: () -> Unit) {
    val c = PM.colors
    SurfaceCard(Modifier.testTag("today.review")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(AppRadii.small)).background(c.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(if (pending == 0) Icons.Rounded.CheckCircle else Icons.Rounded.PhotoLibrary, null, tint = c.accent, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when {
                    pending == null -> CircularProgressIndicator(Modifier.size(24.dp))
                    pending == 0 -> {
                        Text(stringResource(R.string.today_done_title), style = AppTypography.title, color = c.textPrimary)
                        Text(stringResource(R.string.today_done_subtitle), style = AppTypography.subheadline, color = c.textSecondary)
                    }
                    else -> {
                        Text(stringResource(if (hasHistory) R.string.today_continue_title else R.string.today_start_title),
                            style = AppTypography.title, color = c.textPrimary)
                        Text(pluralStringResource(R.plurals.today_pending_count, pending, pending), style = AppTypography.subheadline, color = c.textSecondary)
                    }
                }
            }
        }
        if (pending != null && pending > 0) {
            Spacer(Modifier.height(AppSpacing.m))
            val hint = stringResource(R.string.today_review_hint)
            ProminentButton(stringResource(if (hasHistory) R.string.today_continue_cta else R.string.today_start_cta), fillWidth = true, large = true,
                modifier = Modifier.semantics { onClick(label = hint) { start(); true } }, onClick = start)
        }
    }
}
