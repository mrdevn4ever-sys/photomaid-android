package app.photomaid.feature.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.photomaid.FeedRequest
import app.photomaid.R
import app.photomaid.core.collections.CollectionFilter
import app.photomaid.core.collections.CollectionResult
import app.photomaid.core.collections.CollectionSource
import app.photomaid.core.collections.SmartCollection
import app.photomaid.core.collections.SmartCollectionID
import app.photomaid.core.collections.SmartCollections
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.Badge
import app.photomaid.design.Caption
import app.photomaid.design.GlassButton
import app.photomaid.design.ListSection
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.design.RowDivider
import app.photomaid.design.SegmentedPicker
import app.photomaid.design.SizeText
import app.photomaid.design.SourceRow
import app.photomaid.design.SurfaceCard
import app.photomaid.design.clickableRow
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import kotlinx.coroutines.launch

/** Titel in Alltagssprache, z. B. „Videos größer als 100 MB“, „Screenshots älter als 30 Tage“. */
@Composable
fun collectionTitle(c: SmartCollection): String {
    val what = stringResource(when (c.source) {
        CollectionSource.PHOTOS -> R.string.cat_photos
        CollectionSource.VIDEOS -> R.string.cat_videos
        CollectionSource.SCREENSHOTS -> R.string.cat_screenshots
    })
    return when (val f = c.filter) {
        is CollectionFilter.LargerThan -> stringResource(R.string.col_title_larger, what, SizeText.bytes(f.bytes))
        is CollectionFilter.OlderThan -> stringResource(R.string.col_title_older, what, ageText(f.days))
    }
}

@Composable
private fun ageText(days: Int): String = when (days) {
    182 -> stringResource(R.string.col_age_6m)
    365 -> stringResource(R.string.col_age_1y)
    730 -> stringResource(R.string.col_age_2y)
    else -> pluralStringResource(R.plurals.col_age_days, days, days)
}

private fun icon(c: SmartCollection): ImageVector = when (c.source) {
    CollectionSource.PHOTOS -> Icons.Rounded.Photo
    CollectionSource.VIDEOS -> Icons.Rounded.Videocam
    CollectionSource.SCREENSHOTS -> Icons.Rounded.Screenshot
}

/**
 * Reiter „Sammlungen“ im Aufräumen-Tab: dauerhafte, antippbare Filter mit aktuellem Stand.
 * Fertige Sammlungen für alle, eigene Sammlungen mit Pro (in der Pro-Vorschau ebenfalls).
 */
@Composable
fun CollectionsContent(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val scope = rememberCoroutineScope()
    val custom by app.collections.custom.collectAsState()
    val token by app.photos.changeToken.collectAsState()
    val access by app.photos.access.collectAsState()
    val read by app.read.collectAsState()
    val caps = app.capabilities
    var results by remember { mutableStateOf<Map<SmartCollectionID, CollectionResult>>(emptyMap()) }
    var editing by remember { mutableStateOf<SmartCollection?>(null) }
    val all = SmartCollections.builtIns + custom
    LaunchedEffect(token, access, read.events.size, custom) {
        if (!access.canRead) { results = emptyMap(); return@LaunchedEffect }
        results = all.associate { it.id to app.collections.result(it, app) }
    }
    fun open(col: SmartCollection) = nav.openFeed(FeedRequest(FeedRequest.Source.Collection(col)))

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
        Caption(stringResource(R.string.col_intro), style = AppTypography.subheadline, modifier = Modifier.padding(horizontal = AppSpacing.xxs))
        ListSection(header = stringResource(R.string.col_builtin_header)) {
            SmartCollections.builtIns.forEachIndexed { i, col ->
                if (i > 0) RowDivider()
                CollectionRow(col, results[col.id], enabled = access.canRead, locked = false, onClick = { open(col) })
            }
        }
        if (custom.isNotEmpty()) {
            ListSection(header = stringResource(R.string.col_custom_header)) {
                custom.forEachIndexed { i, col ->
                    if (i > 0) RowDivider()
                    CollectionRow(col, results[col.id], enabled = access.canRead, locked = !caps.customCollections,
                        onClick = { if (caps.customCollections) open(col) else nav.openPro() },
                        onEdit = if (caps.customCollections) ({ editing = col }) else null)
                }
            }
        }
        SurfaceCard(Modifier.testTag("collections.add"), onClick = {
            if (caps.customCollections) editing = SmartCollection(source = CollectionSource.VIDEOS, filter = CollectionFilter.LargerThan(500L * SmartCollections.MB))
            else nav.openPro()
        }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                Icon(Icons.Rounded.Add, null, tint = c.accent)
                Text(stringResource(R.string.col_add), style = AppTypography.headline, color = c.accent, modifier = Modifier.weight(1f))
                if (!caps.customCollections) Badge(stringResource(R.string.pro_badge))
            }
            if (!caps.customCollections) {
                Text(stringResource(R.string.col_custom_pro), style = AppTypography.footnote, color = c.textSecondary, modifier = Modifier.padding(top = AppSpacing.xxs))
            }
        }
    }
    editing?.let { col ->
        CollectionEditor(col, isNew = custom.none { it.id == col.id }, onDismiss = { editing = null },
            onSave = { scope.launch { app.collections.upsert(it) }; editing = null },
            onDelete = { scope.launch { app.collections.delete(col.id) }; editing = null })
    }
}

@Composable
private fun CollectionRow(col: SmartCollection, result: CollectionResult?, enabled: Boolean, locked: Boolean, onClick: () -> Unit, onEdit: (() -> Unit)? = null) {
    val c = PM.colors
    val summary = when {
        !enabled -> null
        result == null -> stringResource(R.string.col_counting)
        result.items.isEmpty() -> stringResource(R.string.col_nothing_open)
        else -> stringResource(R.string.col_summary, pluralStringResource(R.plurals.items_count, result.items.size, result.items.size),
            SizeText.bytes(result.knownBytes))
    }
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f).testTag("collection.${col.id.rawValue}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceRow(icon(col), if (col.filter is CollectionFilter.OlderThan) c.later else c.accent, collectionTitle(col),
                Modifier.weight(1f).then(if (enabled) Modifier.clickableRow(onClick = onClick) else Modifier), subtitle = summary)
            if (locked) Badge(stringResource(R.string.pro_badge))
            if (onEdit != null) TextButton(onEdit) { Text(stringResource(R.string.col_edit)) }
        }
        val unknown = result?.unknownSizeCount ?: 0
        if (enabled && unknown > 0) Text(pluralStringResource(R.plurals.col_unknown_hint, unknown, unknown), style = AppTypography.caption,
            color = c.textSecondary, modifier = Modifier.padding(start = 52.dp_, bottom = AppSpacing.xxs))
    }
}

/** Eigene Sammlung: Was? + Bedingung als Schnellwahl (keine +/−-Zähler, sinnvolle Werte je Medienart). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CollectionEditor(initial: SmartCollection, isNew: Boolean, onDismiss: () -> Unit, onSave: (SmartCollection) -> Unit, onDelete: () -> Unit) {
    val c = PM.colors
    var source by remember { mutableStateOf(initial.source) }
    var older by remember { mutableStateOf(initial.filter is CollectionFilter.OlderThan) }
    var bytes by remember { mutableLongStateOf((initial.filter as? CollectionFilter.LargerThan)?.bytes ?: SmartCollections.sizeChoices(initial.source)[1]) }
    var days by remember { mutableIntStateOf((initial.filter as? CollectionFilter.OlderThan)?.days ?: 365) }
    val sizes = SmartCollections.sizeChoices(source)
    if (bytes !in sizes) bytes = sizes[1]
    val draft = initial.copy(source = source, filter = if (older) CollectionFilter.OlderThan(days) else CollectionFilter.LargerThan(bytes))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.s),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            Text(stringResource(R.string.col_editor_title), style = AppTypography.title, color = c.textPrimary)
            Text(collectionTitle(draft), style = AppTypography.headline, color = c.accent, modifier = Modifier.testTag("collection.preview"))
            Text(stringResource(R.string.col_what), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
            SegmentedPicker(listOf(CollectionSource.PHOTOS to stringResource(R.string.cat_photos), CollectionSource.VIDEOS to stringResource(R.string.cat_videos),
                CollectionSource.SCREENSHOTS to stringResource(R.string.cat_screenshots)), source) { source = it }
            Text(stringResource(R.string.col_condition), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
            SegmentedPicker(listOf(false to stringResource(R.string.col_larger), true to stringResource(R.string.col_older)), older) { older = it }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                if (older) for (d in SmartCollections.ageChoices) Chip(ageText(d), d == days) { days = d }
                else for (b in sizes) Chip(SizeText.bytes(b), b == bytes) { bytes = b }
            }
            ProminentButton(stringResource(R.string.save), fillWidth = true, large = true) { onSave(draft) }
            if (!isNew) GlassButton(stringResource(R.string.col_delete), fillWidth = true, tint = c.cleanup, onClick = onDelete)
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = PM.colors
    Text(label, style = AppTypography.subheadline.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
        color = if (selected) androidx.compose.ui.graphics.Color.White else c.textPrimary,
        modifier = Modifier.clip(CircleShape).background(if (selected) c.accent else c.backgroundTint)
            .clickable(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected }
            .heightIn(min = AppSpacing.minTouch).padding(horizontal = AppSpacing.m, vertical = AppSpacing.s))
}

private val Int.dp_ get() = androidx.compose.ui.unit.Dp(toFloat())
