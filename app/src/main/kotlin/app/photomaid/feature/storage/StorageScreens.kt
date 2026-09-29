package app.photomaid.feature.storage

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.SizeTally
import app.photomaid.core.storage.DeviceCapacity
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.DateText
import app.photomaid.design.ListSection
import app.photomaid.design.MetricCard
import app.photomaid.design.PM
import app.photomaid.design.RowDivider
import app.photomaid.design.ScreenScaffold
import app.photomaid.design.SegmentedPicker
import app.photomaid.design.SizeText
import app.photomaid.design.SurfaceCard
import app.photomaid.feature.common.DeviceStorage
import app.photomaid.feature.common.ThumbnailView
import app.photomaid.feature.files.FileIndex
import app.photomaid.feature.files.scanAll
import app.photomaid.media.MediaAsset
import app.photomaid.media.MediaCategory
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.TabBarClearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

private enum class LargeSort { LARGEST, NEWEST, OLDEST }

private data class LargeRow(val id: ItemReference, val title: String, val size: KnownSize, val date: Instant?, val thumb: String?)

/**
 * Screen 8 – Große Dateien. 100 MB voreingestellt und änderbar. Bekannte Größen absteigend,
 * unbekannte Größen separat (nie als 0). Android: MediaStore meldet Videogrößen direkt (öffentliche Spalte).
 */
@Composable
fun LargeFilesScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.collectAsState()
    var index by remember { mutableStateOf(FileIndex()) }
    var videos by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var sort by rememberSaveable { mutableStateOf(LargeSort.LARGEST) }
    var scanning by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        scanning = true
        index = scanAll(app)
        videos = withContext(Dispatchers.IO) { app.photos.assets(MediaCategory.VIDEOS) }
        scanning = false
    }
    val threshold = settings.largeFileThresholdMB.toLong() * 1_000_000
    val videoTitle = stringResource(R.string.video)
    val rows = index.entries.map { LargeRow(it.reference, it.name, it.size, it.modified, null) } +
        videos.map { LargeRow(it.reference, videoTitle, it.size, it.creationDate, it.id) }
    val known = rows.filter { (it.size.bytesOrNull ?: -1) >= threshold }.let { l ->
        when (sort) {
            LargeSort.LARGEST -> l.sortedByDescending { it.size.bytesOrNull ?: 0 }
            LargeSort.NEWEST -> l.sortedByDescending { it.date ?: Instant.MIN }
            LargeSort.OLDEST -> l.sortedBy { it.date ?: Instant.MAX }
        }
    }
    val unknown = rows.filter { it.size == KnownSize.Unknown }

    ScreenScaffold(stringResource(R.string.large_files), onBack = nav::back) { mod ->
        LazyColumn(mod.padding(horizontal = AppSpacing.pageMargin), verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            item {
                MetricCard(SizeText.tally(ctx, SizeTally.of(known.map { it.size })), stringResource(R.string.large_in_files, known.size),
                    footnote = stringResource(R.string.large_footnote, settings.largeFileThresholdMB))
            }
            item {
                ListSection {
                    Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.large_filter, settings.largeFileThresholdMB), style = AppTypography.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                        // iOS: Stepper 10…5000, Schritt 50.
                        StepButton(Icons.Rounded.Remove, stringResource(R.string.decrease), settings.largeFileThresholdMB > 10) {
                            scope.launch { app.updateSettings { it.copy(largeFileThresholdMB = (it.largeFileThresholdMB - 50).coerceAtLeast(10)) } }
                        }
                        StepButton(Icons.Rounded.Add, stringResource(R.string.increase), settings.largeFileThresholdMB < 5000) {
                            scope.launch { app.updateSettings { it.copy(largeFileThresholdMB = (it.largeFileThresholdMB + 50).coerceAtMost(5000)) } }
                        }
                    }
                    RowDivider()
                    SegmentedPicker(listOf(LargeSort.LARGEST to stringResource(R.string.sort_largest), LargeSort.NEWEST to stringResource(R.string.sort_newest),
                        LargeSort.OLDEST to stringResource(R.string.sort_oldest)), sort, Modifier.padding(vertical = AppSpacing.xs)) { sort = it }
                }
            }
            item {
                ListSection(header = stringResource(R.string.large_known)) {
                    if (scanning && known.isEmpty()) Box(Modifier.fillMaxWidth().padding(AppSpacing.m), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    known.forEachIndexed { i, r -> if (i > 0) RowDivider(); LargeRowView(r) }
                    if (known.isEmpty() && !scanning) Text(stringResource(R.string.large_none), style = AppTypography.body, color = c.textSecondary, modifier = Modifier.padding(vertical = AppSpacing.s))
                }
            }
            if (unknown.isNotEmpty()) item {
                ListSection(header = stringResource(R.string.large_unknown_header, unknown.size), footer = stringResource(R.string.large_unknown_footer)) {
                    unknown.take(200).forEachIndexed { i, r -> if (i > 0) RowDivider(); LargeRowView(r) }
                }
            }
            item { Spacer(Modifier.height(TabBarClearance)) }
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = PM.colors
    Box(Modifier.size(AppSpacing.minTouch).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (enabled) c.accent else c.textSecondary.copy(alpha = 0.4f))
    }
}

@Composable
private fun LargeRowView(r: LargeRow) {
    val app = LocalApp.current
    val c = PM.colors
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        ThumbnailView(r.thumb)
        Column(Modifier.weight(1f)) {
            Text(r.title, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            r.date?.let { Text(DateText.abbreviated(it), style = AppTypography.caption, color = c.textSecondary) }
        }
        Text(SizeText.size(ctx, r.size), style = AppTypography.subheadline, color = c.textPrimary)
        Box {
            val label = stringResource(R.string.item_actions_for, r.title)
            Box(Modifier.size(AppSpacing.minTouch).clickable { menu = true }.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MoreHoriz, null, tint = c.textSecondary)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.dir_trash)) }, onClick = { menu = false; scope.launch { app.decide(r.id, DecisionAction.MarkForCleanup) } })
                DropdownMenuItem({ Text(stringResource(R.string.keep)) }, onClick = { menu = false; scope.launch { app.decide(r.id, DecisionAction.Keep) } })
            }
        }
    }
}

/** Screen 10 – Speicherübersicht. A: Gerät, B: erfasste Quellen, C: nicht einsehbar (nur textlich). */
@Composable
fun StorageInsightsScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val ctx = LocalContext.current
    val res = LocalResources.current
    var device by remember { mutableStateOf<DeviceCapacity?>(null) }
    var counts by remember { mutableStateOf<Map<MediaCategory, Int>>(emptyMap()) }
    var tallies by remember { mutableStateOf<Map<MediaCategory, SizeTally>>(emptyMap()) }
    var index by remember { mutableStateOf(FileIndex()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            device = DeviceStorage.read(ctx)
            val assets = MediaCategory.entries.associateWith { app.photos.assets(it) }
            counts = assets.mapValues { it.value.size }
            tallies = assets.mapValues { e -> SizeTally.of(e.value.map { it.size }) }
        }
        index = scanAll(app)
    }
    ScreenScaffold(stringResource(R.string.storage_overview), onBack = nav::back) { mod ->
        LazyColumn(mod.padding(horizontal = AppSpacing.pageMargin), verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            item {
                ListSection(header = stringResource(R.string.storage_a), plain = true) {
                    val d = device
                    if (d != null) MetricCard(SizeText.bytes(d.usedBytes), stringResource(R.string.storage_used_of, SizeText.bytes(d.totalBytes)),
                        progress = if (d.totalBytes > 0) d.usedBytes.toFloat() / d.totalBytes else null,
                        footnote = stringResource(R.string.storage_available, SizeText.bytes(d.availableBytes)))
                    else SurfaceCard { Text(stringResource(R.string.storage_unavailable), style = AppTypography.body, color = c.textSecondary) }
                }
            }
            item {
                ListSection(header = stringResource(R.string.storage_b), footer = stringResource(R.string.storage_b_footer)) {
                    @Composable
                    fun row(icon: ImageVector, title: String, value: String) {
                        Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.minTouch).semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                            Icon(icon, null, tint = c.accent)
                            Text(title, style = AppTypography.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                            Text(value, style = AppTypography.subheadline, color = c.textSecondary)
                        }
                    }
                    fun v(cat: MediaCategory) = res.getString(R.string.storage_value_items, counts[cat] ?: 0, SizeText.tally(ctx, tallies[cat] ?: SizeTally()))
                    row(Icons.Rounded.Photo, stringResource(R.string.cat_photos), v(MediaCategory.PHOTOS)); RowDivider()
                    row(Icons.Rounded.CameraAlt, stringResource(R.string.storage_of_which_screens), v(MediaCategory.SCREENSHOTS)); RowDivider()
                    row(Icons.Rounded.Videocam, stringResource(R.string.cat_videos), v(MediaCategory.VIDEOS)); RowDivider()
                    row(Icons.Rounded.Folder, stringResource(R.string.storage_connected_folders), SizeText.tally(ctx, SizeTally.of(index.entries.map { it.size })))
                }
            }
            item {
                ListSection(header = stringResource(R.string.storage_c)) {
                    Text(stringResource(R.string.storage_c_text), style = AppTypography.subheadline, color = c.textSecondary, modifier = Modifier.padding(vertical = AppSpacing.s))
                }
            }
            item {
                SurfaceCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Icon(Icons.Rounded.GppGood, null, tint = c.accent)
                        Text(stringResource(R.string.storage_local), style = AppTypography.body, color = c.accent)
                    }
                }
            }
            item { Spacer(Modifier.height(TabBarClearance)) }
        }
    }
}
