package app.photomaid.feature.files

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.photomaid.AppModel
import app.photomaid.R
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.model.ConnectedSourceID
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.DateText
import app.photomaid.design.GlassIconButton
import app.photomaid.design.ListSection
import app.photomaid.design.MenuRowPicker
import app.photomaid.design.PM
import app.photomaid.design.PhotoMaidColors
import app.photomaid.design.ProminentButton
import app.photomaid.design.RowDivider
import app.photomaid.design.ScreenScaffold
import app.photomaid.design.SizeText
import app.photomaid.design.SurfaceCard
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.files.FileEntry
import app.photomaid.files.FileKindFilter
import app.photomaid.files.SourceException
import app.photomaid.files.SourceProblem
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.TabBarClearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Inventar aller verbundenen Quellen (nur im Speicher; Quelle der Wahrheit bleibt der Anbieter). */
data class FileIndex(val entries: List<FileEntry> = emptyList(), val failed: List<ConnectedSourceID> = emptyList(), val scanning: Boolean = false)

suspend fun scanAll(app: AppModel): FileIndex = withContext(Dispatchers.IO) {
    val all = mutableListOf<FileEntry>(); val failed = mutableListOf<ConnectedSourceID>()
    for (s in app.sources.sources.value) {
        try { all += app.sources.scan(s.id) } catch (e: Exception) { failed += s.id }
    }
    FileIndex(all, failed, false)
}

/** Launcher für den Ordnerdialog (SAF). Ablehnung von Cloud-Ordnern wie iOS (iCloud Drive). */
@Composable
fun rememberFolderPicker(asDownloads: Boolean, onDone: (String?) -> Unit): () -> Unit {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                try { app.sources.add(uri, asDownloads); null }
                catch (e: SourceException) { res.getString(if (e.problem == SourceProblem.CLOUD_PROVIDER_NOT_SUPPORTED) R.string.error_cloud_folder else R.string.error_folder_connect) }
                catch (e: Exception) { res.getString(R.string.error_folder_connect) }
            }
            onDone(err)
        }
    }
    return { runCatching { launcher.launch(null) }.onFailure { onDone(res.getString(R.string.error_folder_connect)) } }
}

/** Screen 7 – Downloads/Dateien. Nur verbundene Quellen. Heißt „Downloads“ nur bei erkannter Downloads-Quelle. */
@Composable
fun FileBrowserScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val sources by app.sources.sources.collectAsState()
    var index by remember { mutableStateOf(FileIndex(scanning = true)) }
    var records by remember { mutableStateOf<Map<ItemReference, ItemRecord>>(emptyMap()) }
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(FileKindFilter.ALL) }
    var errorText by remember { mutableStateOf<String?>(null) }
    suspend fun reload() {
        index = index.copy(scanning = true)
        index = scanAll(app)
        records = app.ledger.records(index.entries.map { it.reference })
    }
    val pick = rememberFolderPicker(asDownloads = true) { err -> errorText = err; scope.launch { reload() } }
    LaunchedEffect(sources.size) { reload() }

    val filtered = index.entries.filter { kind == FileKindFilter.ALL || it.kind == kind }
        .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        .sortedByDescending { it.modified ?: Instant.MIN }
    val zone = ZoneId.systemDefault()
    val todayDate = LocalDate.now(zone)
    val now = Instant.now()
    val groups = listOf(
        stringResource(R.string.group_today) to filtered.filter { it.modified?.atZone(zone)?.toLocalDate() == todayDate },
        stringResource(R.string.group_30_days) to filtered.filter { m -> m.modified?.let { it.atZone(zone).toLocalDate() != todayDate && Duration.between(it, now).toDays() < 30 } == true },
        stringResource(R.string.group_older) to filtered.filter { m -> m.modified?.let { Duration.between(it, now).toDays() >= 30 } ?: true },
    ).filter { it.second.isNotEmpty() }

    fun open(e: FileEntry) {
        // Native Anzeige über andere Apps (iOS: Quick Look). Kein Entpacken, kein Ausführen.
        if (e.kind == FileKindFilter.ARCHIVE || e.kind == FileKindFilter.OTHER) return
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(e.documentUri), e.mimeType ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(intent) } catch (_: ActivityNotFoundException) { errorText = res.getString(R.string.no_viewer) }
    }
    fun decide(e: FileEntry, a: DecisionAction) = scope.launch {
        app.decide(e.reference, a)
        records = records + (e.reference to app.ledger.record(e.reference))
    }

    ScreenScaffold(stringResource(if (app.sources.downloadsSource == null) R.string.files else R.string.downloads), onBack = nav::back, actions = {
        GlassIconButton(Icons.Rounded.CreateNewFolder, stringResource(R.string.add_folder), onClick = pick)
    }) { mod ->
        LazyColumn(mod.padding(horizontal = AppSpacing.pageMargin), verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            if (sources.isEmpty()) {
                item {
                    SurfaceCard {
                        Text(stringResource(R.string.files_none_title), style = AppTypography.headline, color = c.textPrimary)
                        Spacer(Modifier.height(AppSpacing.s))
                        Text(stringResource(R.string.files_none_message), style = AppTypography.caption, color = c.textSecondary)
                        Spacer(Modifier.height(AppSpacing.s))
                        ProminentButton(stringResource(R.string.choose_folder), onClick = pick)
                    }
                }
            } else {
                item {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text(stringResource(R.string.files_search)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        shape = RoundedCornerShape(14.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = c.surface, focusedContainerColor = c.surface,
                            unfocusedBorderColor = c.surfaceStroke))
                }
                item {
                    ListSection {
                        MenuRowPicker(stringResource(R.string.file_type), FileKindFilter.entries.map { it to kindLabel(it) }, kind) { kind = it }
                    }
                }
                if (index.scanning && index.entries.isEmpty()) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                items(groups, key = { it.first }) { (title, entries) ->
                    ListSection(header = title) {
                        entries.forEachIndexed { i, e ->
                            if (i > 0) RowDivider()
                            FileRow(e, records[e.reference]?.disposition ?: Disposition.UNDECIDED, onOpen = { open(e) },
                                onKeep = { decide(e, DecisionAction.Keep) }, onTrash = { decide(e, DecisionAction.MarkForCleanup) },
                                onLater = { decide(e, DecisionAction.Later(ReviewSessionID())) })
                        }
                    }
                }
                if (index.failed.isNotEmpty()) item {
                    SurfaceCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                            Icon(Icons.Rounded.WarningAmber, null, tint = c.warning)
                            Text(stringResource(R.string.files_source_unreachable), style = AppTypography.subheadline, color = c.warning)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(TabBarClearance)) }
        }
    }
    NoticeDialog(errorText) { errorText = null }
}

@Composable
fun kindLabel(k: FileKindFilter): String = stringResource(when (k) {
    FileKindFilter.ALL -> R.string.kind_all; FileKindFilter.PDF -> R.string.kind_pdf; FileKindFilter.ARCHIVE -> R.string.kind_archive
    FileKindFilter.IMAGE -> R.string.kind_image; FileKindFilter.VIDEO -> R.string.kind_video; FileKindFilter.DOCUMENT -> R.string.kind_document
    FileKindFilter.OTHER -> R.string.kind_other
})

fun kindIcon(k: FileKindFilter) = when (k) {
    FileKindFilter.PDF -> Icons.Rounded.PictureAsPdf; FileKindFilter.ARCHIVE -> Icons.Rounded.FolderZip; FileKindFilter.IMAGE -> Icons.Rounded.Image
    FileKindFilter.VIDEO -> Icons.Rounded.Movie; FileKindFilter.DOCUMENT -> Icons.Rounded.Description; else -> Icons.Rounded.InsertDriveFile
}

fun kindTint(k: FileKindFilter, c: PhotoMaidColors): Color = when (k) {
    FileKindFilter.PDF -> c.cleanup; FileKindFilter.ARCHIVE -> c.textSecondary; FileKindFilter.VIDEO -> Color(0xFFAF52DE); else -> c.accent
}

@Composable
private fun FileRow(e: FileEntry, state: Disposition, onOpen: () -> Unit, onKeep: () -> Unit, onTrash: () -> Unit, onLater: () -> Unit) {
    val c = PM.colors
    val ctx = LocalContext.current
    val res = LocalResources.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Icon(kindIcon(e.kind), null, tint = kindTint(e.kind, c), modifier = Modifier.size(32.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(SizeText.size(ctx, e.size), e.modified?.let { DateText.abbreviated(it) }).joinToString(" · "), style = AppTypography.caption, color = c.textSecondary)
        }
        when (state) {
            Disposition.MARKED_FOR_CLEANUP -> Icon(Icons.Rounded.Delete, null, tint = c.cleanup)
            Disposition.LATER -> Icon(Icons.Rounded.Bookmark, null, tint = c.later)
            Disposition.KEPT -> Icon(Icons.Rounded.Check, null, tint = c.keep)
            else -> Unit
        }
        Box {
            val label = stringResource(R.string.item_actions_for, e.name)
            Box(Modifier.size(AppSpacing.minTouch).clickable { menu = true }.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MoreHoriz, null, tint = c.textSecondary)
            }
            // iOS: Wischaktionen (Behalten | Papierkorb, Später). Android: Aktionsmenü.
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.keep)) }, onClick = { menu = false; onKeep() })
                DropdownMenuItem({ Text(stringResource(R.string.dir_trash)) }, onClick = { menu = false; onTrash() })
                DropdownMenuItem({ Text(stringResource(R.string.later)) }, onClick = { menu = false; onLater() })
            }
        }
    }
}

