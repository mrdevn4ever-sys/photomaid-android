package app.photomaid.feature.archive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.archive.ArchiveCleanup
import app.photomaid.archive.ArchiveRunner
import app.photomaid.archive.ArchiveTarget
import app.photomaid.core.archive.ArchiveJournal
import app.photomaid.core.archive.ArchiveOptions
import app.photomaid.core.archive.ArchiveSpace
import app.photomaid.core.archive.EntryReason
import app.photomaid.core.archive.EntryState
import app.photomaid.core.archive.JournalEntry
import app.photomaid.core.archive.SpaceCheck
import app.photomaid.core.archive.TargetKind
import app.photomaid.core.batch.BatchRules
import app.photomaid.core.model.SizeTally
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.DateText
import app.photomaid.design.ListSection
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.ProminentButton
import app.photomaid.design.RowDivider
import app.photomaid.design.ScreenScaffold
import app.photomaid.design.SizeText
import app.photomaid.design.SurfaceCard
import app.photomaid.feature.common.ConfirmationSheet
import app.photomaid.feature.editor.ProHint
import app.photomaid.media.MediaAsset
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.PageEndSpacer
import app.photomaid.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Auslagern einer Auswahl (IDs in Reihenfolge). */
data class ArchiveRequest(val id: String, val assetIds: List<String>)

object ArchiveRequests { val map = mutableStateMapOf<String, ArchiveRequest>() }

/**
 * Auslagern (Paket 5): Ziel wählen → „Das passiert“ → kopieren und prüfen → Ergebnis je Datei. Danach – nur auf eigenen
 * Wunsch und nur bei einem eigenen Datenträger – die geprüften Originale in den PhotoMaid-Papierkorb legen (eine Entscheidung
 * je Medium, gelöscht wird erst beim Leeren). Nie eine Löschung, nie eine Entscheidung ohne Tipp.
 */
@Composable
fun ArchiveScreen(req: ArchiveRequest, nav: Nav, onClose: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var assets by remember { mutableStateOf<List<MediaAsset>?>(null) }
    var target by remember { mutableStateOf<ArchiveTarget?>(null) }
    var available by remember { mutableStateOf<Long?>(null) }
    var options by remember { mutableStateOf(ArchiveOptions()) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var result by remember { mutableStateOf<ArchiveJournal?>(null) }
    var failed by remember { mutableStateOf(false) }
    var confirmCleanup by remember { mutableStateOf(false) }
    var cleaned by remember { mutableStateOf<Int?>(null) }
    val last by app.archive.last.collectAsState()
    val exporting by app.exports.state.collectAsState()

    LaunchedEffect(req.assetIds) {
        assets = withContext(Dispatchers.IO) { app.allPhotos.assets(req.assetIds) }
        target = withContext(Dispatchers.IO) { app.archive.lastTarget()?.also { it.label } } // Anzeigename abseits des Main-Threads lesen
    }
    LaunchedEffect(target) { available = target?.let { t -> withContext(Dispatchers.IO) { runCatching { t.availableBytes() }.getOrNull() } } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch { target = withContext(Dispatchers.IO) { app.archive.choose(uri)?.also { it.label } } }
    }

    val list = assets.orEmpty()
    val pro = app.capabilities.hasProToolsAccess
    val overLimit = !pro && list.size > BatchRules.FREE_LIMIT
    val todo = if (pro) list else list.take(BatchRules.FREE_LIMIT)
    val space = ArchiveSpace.check(todo.map { it.size.bytesOrNull }, available)

    fun start() {
        val t = target ?: return
        running = true; progress = 0f; failed = false
        val job = app.exports.run(app.string(R.string.archive_running)) { onP -> ArchiveRunner.run(app, t, todo, options, onP) }
        scope.launch {
            val watch = launch { app.exports.state.collect { s -> progress = s?.progress } }
            try { result = job.await() }
            catch (e: kotlinx.coroutines.CancellationException) { if (!job.isCancelled) throw e; result = app.archive.last.value }
            catch (e: Exception) { failed = true; result = app.archive.last.value?.takeIf { it.finished } }
            finally { watch.cancel(); running = false; progress = null }
        }
    }

    // Seite verlassen, während kopiert wird: der Durchgang läuft weiter (Vordergrunddienst) und meldet sich am Ende.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (app.exports.state.value != null) app.exports.background() } }
    androidx.activity.compose.BackHandler { if (!running) onClose() }
    ScreenScaffold(stringResource(R.string.archive_title), onBack = { if (!running) onClose() }, modifier = Modifier.testTag("archive")) { mod ->
        val r = result
        LazyColumn(mod.padding(horizontal = AppSpacing.pageMargin), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            if (r == null) {
                val prev = last
                // Letzter Durchgang (auch einer, der nach Verlassen der Seite weiterlief) bleibt ansehbar.
                if (prev != null && prev.finished && !running) item("last") {
                    val interrupted = prev.entries.any { it.reason == EntryReason.INTERRUPTED }
                    SurfaceCard(Modifier.fillMaxWidth().testTag(if (interrupted) "archive.interrupted" else "archive.last")) {
                        Text(if (interrupted) stringResource(R.string.archive_last_interrupted, prev.verified.size, prev.entries.size)
                            else stringResource(R.string.archive_last_result, DateText.longWithTime(java.time.Instant.ofEpochMilli(prev.startedAt)),
                                prev.verified.size, prev.entries.size),
                            style = AppTypography.subheadline, color = c.textPrimary)
                        PlainButton(stringResource(R.string.archive_show_result), Modifier.testTag("archive.showlast")) { result = prev }
                    }
                }
                item("counts") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(pluralStringResource(R.plurals.share_count, todo.size, todo.size), style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold),
                            color = c.textPrimary, modifier = Modifier.testTag("archive.counts"))
                        Text(stringResource(R.string.archive_total_size, SizeText.tally(ctx, SizeTally.of(todo.map { it.size }))), style = AppTypography.footnote,
                            color = c.textSecondary)
                    }
                }
                item("target") { TargetSection(target, available, space, todo, enabled = !running) { picker.launch(null) } }
                item("options") {
                    ListSection(header = stringResource(R.string.archive_options)) {
                        Toggle(R.string.archive_by_month, options.byMonth, "archive.bymonth", !running) { options = options.copy(byMonth = it) }
                        RowDivider()
                        Toggle(R.string.archive_write_list, options.writeList, "archive.list", !running) { options = options.copy(writeList = it) }
                        Text(stringResource(R.string.archive_write_list_note), style = AppTypography.footnote, color = c.textSecondary,
                            modifier = Modifier.padding(bottom = AppSpacing.xs))
                    }
                }
                item("steps") {
                    val label = target?.label ?: stringResource(R.string.archive_target_none)
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Text(stringResource(R.string.wf_preview_title), style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.semantics { heading() })
                        ListSection(Modifier.testTag("archive.steps")) {
                            listOf(pluralStringResource(R.plurals.archive_step_copy, todo.size, todo.size, label), stringResource(R.string.archive_step_verify),
                                stringResource(R.string.archive_step_only_verified), stringResource(R.string.archive_step_originals)).forEachIndexed { i, t ->
                                if (i > 0) RowDivider(AppSpacing.m)
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = AppSpacing.m, vertical = AppSpacing.xs),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                                    Text("${i + 1}", style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                                    Text(t, style = AppTypography.body, color = c.textPrimary)
                                }
                            }
                        }
                    }
                }
                item("start") {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                        if (overLimit) {
                            Text(stringResource(R.string.tools_free_limit, BatchRules.FREE_LIMIT), style = AppTypography.footnote, color = c.textSecondary,
                                modifier = Modifier.testTag("archive.limit"))
                            ProHint(onPro = nav::openPro)
                        }
                        val p = progress
                        if (running) {
                            if (p != null && p > 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().testTag("archive.progress"))
                            else CircularProgressIndicator(Modifier.size(24.dp).testTag("archive.progress"))
                            PlainButton(stringResource(R.string.cancel), Modifier.testTag("archive.cancel")) { app.exports.cancel() }
                        }
                        if (failed) Text(stringResource(R.string.archive_failed), style = AppTypography.footnote, color = c.cleanup)
                        ProminentButton(stringResource(R.string.archive_start), Modifier.testTag("archive.start"), fillWidth = true,
                            enabled = !running && target != null && todo.isNotEmpty() && space != SpaceCheck.NOT_ENOUGH && exporting == null) { start() }
                    }
                }
            } else {
                item("summary") { Summary(r) }
                item("cleanup") {
                    CleanupOffer(r, cleaned, onOffer = { confirmCleanup = true }, onOpenBasket = { nav.push(Routes.BASKET_MODAL) })
                }
                item("listHeader") { Text(stringResource(R.string.archive_files), style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.semantics { heading() }) }
                itemsIndexed(r.entries, key = { _, e -> e.sourceId }) { _, e -> EntryRow(e) }
                item("done") { PlainButton(stringResource(R.string.done), Modifier.testTag("archive.done")) { onClose() } }
            }
            item("end") { PageEndSpacer() }
        }
    }
    val r = result
    if (confirmCleanup && r != null) {
        val cand = remember(r) { ArchiveCleanup.candidates(app, r) }
        ConfirmationSheet(
            title = pluralStringResource(R.plurals.archive_cleanup_title, cand.ids.size, cand.ids.size),
            message = listOfNotNull(stringResource(R.string.archive_cleanup_text, r.targetLabel),
                cand.protectedFavorites.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.archive_cleanup_favs, it, it) }).joinToString("\n\n"),
            actionTitle = stringResource(R.string.archive_cleanup_action), destructive = false,
            onConfirm = { scope.launch { cleaned = ArchiveCleanup.markForCleanup(app, cand.ids) } },
            onDismiss = { confirmCleanup = false })
    }
}

@Composable
private fun TargetSection(target: ArchiveTarget?, available: Long?, space: SpaceCheck, todo: List<MediaAsset>, enabled: Boolean, onChoose: () -> Unit) {
    val c = PM.colors
    ListSection(header = stringResource(R.string.archive_target)) {
        val (icon, note, tint) = when (target?.kind) {
            TargetKind.REMOVABLE -> Triple(Icons.Rounded.SdCard, R.string.archive_kind_removable, c.success)
            TargetKind.SAME_DEVICE -> Triple(Icons.Rounded.Smartphone, R.string.archive_kind_device, c.warning)
            TargetKind.PROVIDER -> Triple(Icons.Rounded.Cloud, R.string.archive_kind_provider, c.warning)
            null -> Triple(Icons.Rounded.Folder, null, c.textSecondary)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = AppSpacing.xs).testTag("archive.target"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Icon(icon, null, tint = tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(target?.label ?: stringResource(R.string.archive_target_none), style = AppTypography.body, color = c.textPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (note != null) Text(stringResource(note), style = AppTypography.footnote, color = if (tint == c.warning) c.textPrimary else c.textSecondary,
                    modifier = Modifier.testTag("archive.kind.${target?.kind?.name}"))
                if (target != null) {
                    val free = when {
                        space == SpaceCheck.NOT_ENOUGH && available != null ->
                            stringResource(R.string.archive_no_space, SizeText.bytes(todo.sumOf { it.size.bytesOrNull ?: 0L }), SizeText.bytes(available))
                        available != null -> stringResource(R.string.archive_free, SizeText.bytes(available))
                        else -> stringResource(R.string.archive_free_unknown)
                    }
                    Text(free, style = AppTypography.footnote, color = if (space == SpaceCheck.NOT_ENOUGH) c.cleanup else c.textSecondary,
                        modifier = Modifier.testTag("archive.space.${space.name}"))
                }
            }
        }
        RowDivider()
        PlainButton(stringResource(if (target == null) R.string.archive_choose else R.string.archive_choose_other), Modifier.testTag("archive.choose"),
            enabled = enabled) { onChoose() }
    }
}

@Composable
private fun Summary(r: ArchiveJournal) {
    val c = PM.colors
    val s = r.summary
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Text(stringResource(R.string.archive_result_title), style = AppTypography.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        Text(r.targetLabel, style = AppTypography.subheadline, color = c.textSecondary)
        val parts = listOfNotNull(
            s.verified.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.archive_part_verified, it, it) },
            s.alreadyThere.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.archive_part_already, it, it) },
            s.failed.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.archive_part_failed, it, it) },
            s.notDone.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.archive_part_notdone, it, it) },
        )
        Text(parts.joinToString(" · "), style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary,
            modifier = Modifier.testTag("archive.summary"))
        if (s.verified + s.alreadyThere > 0) Text(stringResource(R.string.archive_verified_bytes, SizeText.bytes(s.verifiedBytes)), style = AppTypography.footnote,
            color = c.textSecondary)
        r.listName?.let { Text(stringResource(R.string.archive_list_saved, it), style = AppTypography.footnote, color = c.textSecondary, modifier = Modifier.testTag("archive.listname")) }
        Text(stringResource(R.string.archive_meaning), style = AppTypography.footnote, color = c.textSecondary)
    }
}

@Composable
private fun CleanupOffer(r: ArchiveJournal, cleaned: Int?, onOffer: () -> Unit, onOpenBasket: () -> Unit) {
    val c = PM.colors
    if (r.verified.isEmpty()) return
    when {
        cleaned != null -> SurfaceCard(Modifier.fillMaxWidth().testTag("archive.cleaned")) {
            Text(pluralStringResource(R.plurals.archive_cleanup_done, cleaned, cleaned), style = AppTypography.subheadline, color = c.textPrimary)
            PlainButton(stringResource(R.string.archive_open_basket), Modifier.testTag("archive.basket")) { onOpenBasket() }
        }
        r.targetKind.allowsCleanupOffer -> ProminentButton(stringResource(R.string.archive_cleanup_button), Modifier.testTag("archive.cleanup"), fillWidth = true) { onOffer() }
        else -> Text(stringResource(if (r.targetKind == TargetKind.SAME_DEVICE) R.string.archive_no_cleanup_device else R.string.archive_no_cleanup_provider),
            style = AppTypography.footnote, color = c.textSecondary, modifier = Modifier.testTag("archive.nocleanup"))
    }
}

@Composable
private fun EntryRow(e: JournalEntry) {
    val c = PM.colors
    val (icon, tint, label) = when (e.state) {
        EntryState.VERIFIED -> Triple(Icons.Rounded.CheckCircle, c.success, R.string.archive_status_verified)
        EntryState.ALREADY_THERE -> Triple(Icons.Rounded.TaskAlt, c.success, R.string.archive_status_already)
        EntryState.FAILED -> Triple(Icons.Rounded.ErrorOutline, c.cleanup, R.string.archive_status_failed)
        EntryState.NOT_DONE, EntryState.PENDING, EntryState.COPYING -> Triple(Icons.Rounded.RemoveCircleOutline, c.textSecondary, R.string.archive_status_notdone)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = AppSpacing.xxs).semantics(mergeDescendants = true) {}.testTag("archive.entry.${e.state.name}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Icon(icon, null, tint = tint)
        Column(Modifier.weight(1f)) {
            Text((e.folder + e.name).joinToString("/"), style = AppTypography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            val reason = when (e.reason) {
                EntryReason.READ_FAILED -> R.string.archive_reason_read; EntryReason.WRITE_FAILED -> R.string.archive_reason_write
                EntryReason.MISMATCH -> R.string.archive_reason_mismatch; EntryReason.CANCELLED -> R.string.archive_reason_cancelled
                EntryReason.INTERRUPTED -> R.string.archive_reason_interrupted; EntryReason.NO_SPACE -> R.string.archive_reason_space
                null -> null
            }
            Text(listOfNotNull(stringResource(label), e.bytes?.takeIf { e.isVerified }?.let { SizeText.bytes(it) }, reason?.let { stringResource(it) }).joinToString(" · "),
                style = AppTypography.footnote, color = c.textSecondary)
        }
    }
}

@Composable
private fun Toggle(label: Int, on: Boolean, tag: String, enabled: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).toggleable(on, enabled = enabled, role = Role.Switch) { onChange(it) }
        .testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = AppTypography.body, color = PM.colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(on, null, enabled = enabled)
    }
