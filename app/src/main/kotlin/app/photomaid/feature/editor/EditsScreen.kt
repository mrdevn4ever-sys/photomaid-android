package app.photomaid.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.photomaid.FeedRequest
import app.photomaid.R
import app.photomaid.core.edit.EditRecord
import app.photomaid.core.edit.SaveMode
import app.photomaid.core.model.MediaKind
import app.photomaid.core.edit.EditKind
import androidx.compose.ui.res.pluralStringResource
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.Badge
import app.photomaid.design.EmptyState
import app.photomaid.design.GlassButton
import app.photomaid.design.PM
import app.photomaid.design.RowDivider
import app.photomaid.design.RowGroup
import app.photomaid.design.ScreenScaffold
import app.photomaid.design.clickableRow
import app.photomaid.media.MediaAsset
import app.photomaid.media.MediaWriteResult
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Original eines Eintrags: noch da, im Android-Papierkorb (wiederherstellbar) oder nicht mehr vorhanden. */
private enum class OriginalState { PRESENT, TRASHED, GONE }

private data class EditRow(val record: EditRecord, val asset: MediaAsset, val original: OriginalState)

/**
 * Aufräumen › Mediathek › „Bearbeitet“: alle von GaleriQ gespeicherten Fassungen, neueste zuerst. Einträge, deren
 * Datei verschwunden ist, fallen weg. Bei „Original angepasst“: „Original wiederherstellen“, solange es im
 * Android-Papierkorb liegt. Antippen öffnet die Fassungen im Feed (ab der gewählten).
 */
@Composable
fun EditsScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val records by app.edits.records.collectAsState()
    val token by app.photos.changeToken.collectAsState()
    var rows by remember { mutableStateOf<List<EditRow>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(records, token, reload) {
        rows = withContext(Dispatchers.IO) {
            val assets = app.allPhotos.assets(records.map { it.newId }).associateBy { it.id }
            val originals = app.allPhotos.assets(records.map { it.sourceId }.filter { it.isNotEmpty() }).associateBy { it.id }
            records.mapNotNull { r ->
                val a = assets[r.newId] ?: return@mapNotNull null
                val o = when {
                    r.sourceId.isEmpty() -> OriginalState.GONE
                    originals.containsKey(r.sourceId) -> OriginalState.PRESENT
                    app.photos.isTrashed(r.sourceId) == true -> OriginalState.TRASHED
                    else -> OriginalState.GONE
                }
                EditRow(r, a, o)
            }
        }
    }
    val restored = stringResource(R.string.edits_restored)
    val restoreFailed = stringResource(R.string.edits_restore_failed)
    ScreenScaffold(stringResource(R.string.edits_title), onBack = nav::back) { mod ->
        val list = rows
        when {
            list == null -> Box(mod, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(mod, contentAlignment = Alignment.Center) {
                EmptyState(Icons.Outlined.Edit, stringResource(R.string.edits_empty_title), stringResource(R.string.edits_empty_text))
            }
            else -> LazyColumn(mod.padding(horizontal = AppSpacing.pageMargin).testTag("edits"), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                item { Text(stringResource(R.string.edits_intro), style = AppTypography.subheadline, color = c.textSecondary) }
                message?.let { m -> item { Text(m, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary,
                    modifier = Modifier.testTag("edits.message")) } }
                item {
                    RowGroup {
                        list.forEachIndexed { i, row ->
                            if (i > 0) RowDivider()
                            EditRowView(row, busy == row.record.newId,
                                open = { nav.openFeed(FeedRequest(FeedRequest.Source.Items(list.drop(i).map { it.asset.reference }, R.string.edits_title))) },
                                restore = {
                                    busy = row.record.newId
                                    scope.launch {
                                        val r = app.photos.restoreFromTrash(row.record.sourceId)
                                        busy = null; reload++
                                        message = if (r is MediaWriteResult.Done) restored else if (r is MediaWriteResult.Cancelled) null else restoreFailed
                                    }
                                })
                        }
                    }
                }
                item { Box(Modifier.size(AppSpacing.xxl)) }
            }
        }
    }
}

@Composable
private fun EditRowView(row: EditRow, busy: Boolean, open: () -> Unit, restore: () -> Unit) {
    val c = PM.colors
    val px = with(LocalDensity.current) { 64.dp.roundToPx() }
    val replaced = row.record.mode == SaveMode.REPLACE
    Column(Modifier.fillMaxWidth().testTag("edits.row")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickableRow(onClick = open).padding(horizontal = AppSpacing.m, vertical = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(c.backgroundTint)) {
                AsyncImage(ImageRequest.Builder(LocalContext.current).data(row.asset.uri).size(px, px).memoryCacheKey("edit:${row.asset.id}").build(),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (row.asset.kind == MediaKind.VIDEO) Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.asset.displayName ?: stringResource(if (row.asset.kind == MediaKind.VIDEO) R.string.video else R.string.photo),
                    style = AppTypography.body, color = c.textPrimary, maxLines = 1)
                val collage = row.record.kind == EditKind.COLLAGE
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    if (collage) Badge(stringResource(R.string.edits_badge_collage), c.accent)
                    else Badge(stringResource(if (replaced) R.string.edits_badge_replaced else R.string.edits_badge_copy), if (replaced) c.later else c.keep)
                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(row.record.atMs)), style = AppTypography.footnote, color = c.textSecondary)
                }
                val origText = when {
                    collage -> pluralStringResource(R.plurals.edits_collage_from, row.record.count, row.record.count)
                    replaced && row.original == OriginalState.TRASHED -> stringResource(R.string.edits_orig_trashed)
                    replaced && row.original == OriginalState.PRESENT -> stringResource(R.string.edits_orig_back)
                    !replaced && row.original == OriginalState.PRESENT -> stringResource(R.string.edits_orig_present)
                    else -> stringResource(R.string.edits_orig_gone)
                }
                Text(origText, style = AppTypography.footnote, color = c.textSecondary)
            }
        }
        if (replaced && row.original == OriginalState.TRASHED) {
            Box(Modifier.fillMaxWidth().padding(start = 84.dp, end = AppSpacing.m, bottom = AppSpacing.s).semantics(mergeDescendants = false) {}) {
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                else GlassButton(stringResource(R.string.edits_restore), Modifier.testTag("edits.restore"), onClick = restore)
            }
        }
    }
}
