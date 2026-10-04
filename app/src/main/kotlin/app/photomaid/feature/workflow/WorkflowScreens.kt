package app.photomaid.feature.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.photomaid.AppModel
import app.photomaid.R
import app.photomaid.core.batch.BatchRules
import app.photomaid.core.batch.Workflow
import app.photomaid.core.batch.WorkflowFinish
import app.photomaid.core.batch.WorkflowFormat
import app.photomaid.core.batch.WorkflowRules
import app.photomaid.core.batch.WorkflowStep
import app.photomaid.core.model.MediaKind
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassIconButton
import app.photomaid.design.ListSection
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.ProminentButton
import app.photomaid.design.RowDivider
import app.photomaid.design.ScreenScaffold
import app.photomaid.design.SizeText
import app.photomaid.feature.common.ConfirmationSheet
import app.photomaid.feature.editor.Chip
import app.photomaid.feature.editor.ProHint
import app.photomaid.feature.studio.PickRequest
import app.photomaid.media.MediaAsset
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.system.MediaIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Liste der Abläufe; mit [assetIds] (aus einer Auswahl) führt ein Tipp den Ablauf direkt für diese Medien aus. */
data class WorkflowRequest(val id: String, val assetIds: List<String>?)
data class WorkflowRunRequest(val id: String, val workflowId: String, val assetIds: List<String>)

object WorkflowRequests {
    val lists = mutableStateMapOf<String, WorkflowRequest>()
    val runs = mutableStateMapOf<String, WorkflowRunRequest>()
    /** Noch nicht gespeicherte (neue) Abläufe für den Editor. */
    val drafts = mutableStateMapOf<String, Workflow>()
}

private const val PHOTOMAID_FOLDER = "Pictures/PhotoMaid/"

// ---- Liste ------------------------------------------------------------------------------------------------------

@Composable
fun WorkflowsScreen(req: WorkflowRequest, nav: Nav, onClose: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val items by app.workflows.items.collectAsState()
    val scope = rememberCoroutineScope()
    fun edit(w: Workflow) { if (app.workflows.get(w.id) == null) WorkflowRequests.drafts[w.id] = w; nav.push("workflow/edit/${w.id}") }
    fun run(w: Workflow) {
        val ids = req.assetIds
        if (ids != null) { nav.openWorkflowRun(WorkflowRunRequest(UUID.randomUUID().toString(), w.id, ids)); return }
        nav.openPicker(PickRequest(UUID.randomUUID().toString(), photos = true, videos = true, min = 1, max = 500, title = R.string.wf_pick, library = true) { picked ->
            nav.openWorkflowRun(WorkflowRunRequest(UUID.randomUUID().toString(), w.id, picked))
        })
    }
    ScreenScaffold(stringResource(R.string.wf_title), onBack = onClose, modifier = Modifier.testTag("wf.list"), actions = {
        GlassIconButton(Icons.Rounded.Add, stringResource(R.string.wf_new), Modifier.testTag("wf.new")) {
            edit(Workflow(UUID.randomUUID().toString(), "", WorkflowFormat.JPEG, longEdge = 2048))
        }
    }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            Text(stringResource(R.string.wf_never_delete), style = AppTypography.subheadline, color = c.textSecondary)
            if (items.isEmpty()) Text(stringResource(R.string.wf_empty), style = AppTypography.body, color = c.textPrimary, modifier = Modifier.testTag("wf.empty"))
            else ListSection(Modifier.testTag("wf.items")) {
                items.forEachIndexed { i, w ->
                    if (i > 0) RowDivider(AppSpacing.m)
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { run(w) }.padding(start = AppSpacing.m, end = AppSpacing.xs, top = AppSpacing.xs, bottom = AppSpacing.xs)
                        .testTag("wf.item"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                        Icon(Icons.Rounded.AutoMode, null, tint = c.accent)
                        Column(Modifier.weight(1f)) {
                            Text(w.name, style = AppTypography.body.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(compact(app, w), style = AppTypography.footnote, color = c.textSecondary)
                        }
                        GlassIconButton(Icons.Rounded.Edit, stringResource(R.string.wf_edit), Modifier.testTag("wf.edit"), quiet = true) { edit(w) }
                    }
                }
            }
            Text(stringResource(R.string.wf_templates), style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.semantics { heading() })
            ListSection(Modifier.testTag("wf.templates")) {
                WorkflowTemplate.entries.forEachIndexed { i, t ->
                    if (i > 0) RowDivider(AppSpacing.m)
                    val sample = remember(t) { t.create(app) }
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = AppSpacing.m, end = AppSpacing.xs, top = AppSpacing.xs, bottom = AppSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(t.label), style = AppTypography.body, color = c.textPrimary)
                            Text(compact(app, sample), style = AppTypography.footnote, color = c.textSecondary)
                        }
                        PlainButton(stringResource(R.string.wf_add_template), Modifier.testTag("wf.tpl.${t.name}")) {
                            scope.launch { app.workflows.upsert(t.create(app)) }
                        }
                    }
                }
            }
        }
    }
}

/** Kurzform „JPEG · 2048 px · ≤ 2 MB · Nur teilen“ (nur Werte, die der Ablauf wirklich setzt). */
private fun compact(app: AppModel, w: Workflow): String = buildList {
    if (w.format != WorkflowFormat.KEEP) add(w.format.name)
    w.longEdge?.let { add("$it px") }
    w.photoMaxBytes?.let { add("≤ " + SizeText.bytes(it)) }
    if (w.removeLocation) add(app.string(R.string.wf_location))
    w.videoMaxBytes?.let { add(app.string(R.string.wf_videos) + " ≤ " + SizeText.bytes(it)) }
    add(app.string(when (w.finish) { WorkflowFinish.SAVE -> R.string.wf_finish_save; WorkflowFinish.SHARE -> R.string.wf_finish_share; WorkflowFinish.SAVE_AND_SHARE -> R.string.wf_finish_both }))
}.joinToString(" · ")

/** Ein Satz je Schritt für „Das passiert“. */
internal fun describe(app: AppModel, w: Workflow): List<Pair<WorkflowStep, String>> = WorkflowRules.steps(w).map { s ->
    s to when (s) {
        WorkflowStep.FORMAT -> app.string(R.string.wf_step_format, w.format.name)
        WorkflowStep.RESIZE -> app.string(R.string.wf_step_resize, "${w.longEdge}")
        WorkflowStep.TARGET_SIZE -> app.string(R.string.wf_step_target, SizeText.bytes(w.photoMaxBytes ?: 0))
        WorkflowStep.QUALITY -> app.string(R.string.wf_step_quality, "${w.quality}")
        WorkflowStep.LOCATION -> app.string(R.string.wf_location)
        WorkflowStep.VIDEO_TARGET -> app.string(R.string.wf_step_video, SizeText.bytes(w.videoMaxBytes ?: 0))
        WorkflowStep.RENAME -> app.string(R.string.wf_step_rename, WorkflowRules.fileStem(w, 0, 1, null, ""))
        WorkflowStep.FOLDER -> app.string(R.string.wf_step_folder, (w.folder ?: "").trimEnd('/'))
        WorkflowStep.SAVE -> app.string(R.string.wf_step_save)
        WorkflowStep.SHARE -> app.string(if (w.saves) R.string.wf_step_share else R.string.wf_step_share_only)
    }
}

// ---- Editor -----------------------------------------------------------------------------------------------------

@Composable
fun WorkflowEditorScreen(id: String, nav: Nav, onClose: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val scope = rememberCoroutineScope()
    val existing = remember(id) { app.workflows.get(id) }
    var w by remember(id) { mutableStateOf(existing ?: WorkflowRequests.drafts[id] ?: Workflow(id, "")) }
    var nameBase by remember(id) { mutableStateOf(w.nameBase ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    val current = w.copy(nameBase = WorkflowRules.sanitizeBase(nameBase)?.let { nameBase.trim() })
    val valid = WorkflowRules.isValid(current)
    fun save() = scope.launch {
        app.workflows.upsert(current.copy(name = WorkflowRules.normalizeName(current.name) ?: current.name))
        WorkflowRequests.drafts.remove(id); onClose()
    }
    ScreenScaffold(stringResource(R.string.wf_edit), onBack = { WorkflowRequests.drafts.remove(id); onClose() }, modifier = Modifier.testTag("wf.editor"), actions = {
        PlainButton(stringResource(R.string.save), Modifier.testTag("wf.save"), enabled = valid) { save() }
    }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            OutlinedTextField(w.name, { w = w.copy(name = it.take(WorkflowRules.MAX_NAME)) }, Modifier.fillMaxWidth().testTag("wf.name"), singleLine = true,
                label = { Text(stringResource(R.string.wf_name)) }, shape = RoundedCornerShape(14.dp))
            Label(R.string.wf_format)
            ChipLine { for ((f, l) in listOf(WorkflowFormat.KEEP to stringResource(R.string.wf_format_keep), WorkflowFormat.JPEG to "JPEG", WorkflowFormat.PNG to "PNG"))
                Chip(l, w.format == f, Modifier.testTag("wf.fmt.${f.name}")) { w = w.copy(format = f) } }
            Label(R.string.wf_long_edge)
            ChipLine {
                Chip(stringResource(R.string.wf_original), w.longEdge == null, Modifier.testTag("wf.edge.0")) { w = w.copy(longEdge = null) }
                for (e in WorkflowRules.LONG_EDGES) Chip("$e px", w.longEdge == e, Modifier.testTag("wf.edge.$e")) { w = w.copy(longEdge = e, minLongEdge = minOf(w.minLongEdge, e)) }
            }
            if (w.format != WorkflowFormat.PNG) {
                Label(R.string.wf_quality)
                ChipLine { for ((q, l) in BatchRules.QUALITIES.zip(listOf(R.string.tools_q_high, R.string.tools_q_balanced, R.string.tools_q_small)))
                    Chip(stringResource(l), w.quality == q, Modifier.testTag("wf.q.$q")) { w = w.copy(quality = q, minQuality = minOf(w.minQuality, q)) } }
            }
            Label(R.string.wf_target)
            ChipLine {
                Chip(stringResource(R.string.wf_off), w.photoMaxBytes == null, Modifier.testTag("wf.target.0")) { w = w.copy(photoMaxBytes = null) }
                for (b in WorkflowRules.PHOTO_TARGETS) Chip(SizeText.bytes(b), w.photoMaxBytes == b, Modifier.testTag("wf.target.$b")) { w = w.copy(photoMaxBytes = b) }
            }
            if (w.photoMaxBytes != null) {
                Label(R.string.wf_min_edge)
                ChipLine { for (e in WorkflowRules.MIN_EDGES.filter { w.longEdge == null || it <= w.longEdge!! })
                    Chip("$e px", w.minLongEdge == e, Modifier.testTag("wf.min.$e")) { w = w.copy(minLongEdge = e) } }
                Note(R.string.wf_target_note)
            }
            Toggle(R.string.wf_location, w.removeLocation, "wf.loc") { w = w.copy(removeLocation = it) }
            Label(R.string.wf_videos)
            ChipLine {
                Chip(stringResource(R.string.wf_video_skip), w.videoMaxBytes == null, Modifier.testTag("wf.video.0")) { w = w.copy(videoMaxBytes = null) }
                for (b in WorkflowRules.VIDEO_TARGETS) Chip("≤ " + SizeText.bytes(b), w.videoMaxBytes == b, Modifier.testTag("wf.video.$b")) { w = w.copy(videoMaxBytes = b) }
            }
            if (w.videoMaxBytes != null) Note(R.string.wf_video_note)
            Label(R.string.wf_name_base)
            OutlinedTextField(nameBase, { nameBase = it.take(WorkflowRules.MAX_NAME) }, Modifier.fillMaxWidth().testTag("wf.namebase"), singleLine = true,
                placeholder = { Text(stringResource(R.string.wf_name_base_hint)) }, shape = RoundedCornerShape(14.dp))
            Label(R.string.wf_finish)
            ChipLine { for ((f, l) in listOf(WorkflowFinish.SAVE to R.string.wf_finish_save, WorkflowFinish.SHARE to R.string.wf_finish_share, WorkflowFinish.SAVE_AND_SHARE to R.string.wf_finish_both))
                Chip(stringResource(l), w.finish == f, Modifier.testTag("wf.finish.${f.name}")) { w = w.copy(finish = f) } }
            if (w.saves) {
                Label(R.string.wf_folder)
                ChipLine {
                    Chip(stringResource(R.string.wf_folder_same), w.folder == null, Modifier.testTag("wf.folder.same")) { w = w.copy(folder = null) }
                    Chip(stringResource(R.string.wf_folder_photomaid), w.folder == PHOTOMAID_FOLDER, Modifier.testTag("wf.folder.pm")) { w = w.copy(folder = PHOTOMAID_FOLDER) }
                }
            }
            Note(R.string.wf_never_delete)
            ProminentButton(stringResource(R.string.save), Modifier.testTag("wf.save.bottom"), fillWidth = true, enabled = valid) { save() }
            if (existing != null) PlainButton(stringResource(R.string.wf_delete), Modifier.testTag("wf.delete"), color = c.cleanup) { confirmDelete = true }
        }
    }
    if (confirmDelete) ConfirmationSheet(stringResource(R.string.wf_delete), stringResource(R.string.wf_delete_text), stringResource(R.string.wf_delete),
        onConfirm = { scope.launch { app.workflows.remove(id); onClose() } }, onDismiss = { confirmDelete = false })
}

// ---- Ausführen --------------------------------------------------------------------------------------------------

private val TOTAL_BUDGETS = listOf(5_000_000L, 10_000_000L, 25_000_000L)

@Composable
fun WorkflowRunScreen(req: WorkflowRunRequest, nav: Nav, onClose: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val scope = rememberCoroutineScope()
    val w = remember(req.workflowId) { app.workflows.get(req.workflowId) }
    var assets by remember { mutableStateOf<List<MediaAsset>?>(null) }
    var budget by remember { mutableStateOf<Long?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<WorkflowRun?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(req.assetIds) { assets = withContext(Dispatchers.IO) { app.allPhotos.assets(req.assetIds) } }
    LaunchedEffect(w) { if (w == null) onClose() }
    if (w == null) return
    val list = assets.orEmpty()
    val pro = app.capabilities.hasProToolsAccess
    val overLimit = !pro && list.size > BatchRules.FREE_LIMIT
    val photos = list.count { it.kind != MediaKind.VIDEO }; val videos = list.size - photos
    fun share(r: WorkflowRun) {
        if (r.shared.isEmpty()) return
        runCatching { app.context.startActivity(MediaIntents.shareMany(r.shared, r.items.filter { it.status == WorkflowItemResult.Status.DONE }
            .map { if (it.asset.kind == MediaKind.VIDEO) "video/mp4" else if (it.outputName?.endsWith(".png") == true) "image/png" else "image/jpeg" },
            app.string(R.string.share)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    fun start() {
        running = true; progress = 0f
        val todo = if (pro) list else list.take(BatchRules.FREE_LIMIT)
        val job = app.exports.run(app.string(R.string.wf_running)) { onP -> WorkflowRunner.run(app, w, todo, budget?.let { WorkflowBudget(it) }, onP) }
        scope.launch {
            val watch = launch { app.exports.state.collect { s -> progress = s?.progress } }
            try { val r = job.await(); result = r; share(r) }
            catch (e: kotlinx.coroutines.CancellationException) { if (!job.isCancelled) throw e }
            catch (e: Exception) { failed = true }
            finally { watch.cancel(); running = false; progress = null }
        }
    }
    androidx.activity.compose.BackHandler { if (!running) onClose() }
    ScreenScaffold(w.name, onBack = { if (!running) onClose() }, modifier = Modifier.testTag("wf.run")) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            val r = result
            if (r == null) {
                Text(stringResource(R.string.wf_counts, "$photos", "$videos"), style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = c.textPrimary, modifier = Modifier.testTag("wf.counts"))
                Text(stringResource(R.string.wf_preview_title), style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.semantics { heading() })
                ListSection(Modifier.testTag("wf.steps")) {
                    describe(app, w).forEachIndexed { i, (step, text) ->
                        if (i > 0) RowDivider(AppSpacing.m)
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = AppSpacing.m, vertical = AppSpacing.xs).testTag("wf.step.${step.name}"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                            Text("${i + 1}", style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                            Text(text, style = AppTypography.body, color = c.textPrimary)
                        }
                    }
                }
                if (videos > 0 && !w.handlesVideos) Note(R.string.wf_skip_videos_note)
                if (w.photoMaxBytes != null || w.videoMaxBytes != null) {
                    ChipLine {
                        Chip(stringResource(R.string.wf_budget_each), budget == null, Modifier.testTag("wf.budget.each")) { budget = null }
                        for (b in TOTAL_BUDGETS) Chip(stringResource(R.string.wf_budget_total, SizeText.bytes(b)), budget == b, Modifier.testTag("wf.budget.$b")) { budget = b }
                    }
                }
                Note(R.string.wf_never_delete)
                if (overLimit) { Text(stringResource(R.string.tools_free_limit, BatchRules.FREE_LIMIT), style = AppTypography.footnote, color = c.textSecondary,
                    modifier = Modifier.testTag("wf.limit")); ProHint(onPro = nav::openPro) }
                val p = progress
                if (running) { if (p != null && p > 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().testTag("wf.progress")) else CircularProgressIndicator(Modifier.size(24.dp)) }
                if (failed) Text(stringResource(R.string.wf_reason_error), style = AppTypography.footnote, color = c.cleanup)
                ProminentButton(stringResource(R.string.wf_start), Modifier.testTag("wf.start"), fillWidth = true, enabled = !running && list.isNotEmpty()) { start() }
            } else {
                Text(stringResource(R.string.wf_result_title), style = AppTypography.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.wf_summary, r.done, r.skipped, r.notMet, r.failed), style = AppTypography.subheadline, color = c.textSecondary,
                    modifier = Modifier.testTag("wf.summary"))
                ListSection(Modifier.testTag("wf.results")) {
                    r.items.forEachIndexed { i, it -> if (i > 0) RowDivider(AppSpacing.m); ResultRow(it) }
                }
                if (r.shared.isNotEmpty()) ProminentButton(stringResource(R.string.wf_share_again), Modifier.testTag("wf.share"), fillWidth = true) { share(r) }
                PlainButton(stringResource(R.string.done), Modifier.testTag("wf.done")) { onClose() }
            }
        }
    }
}

@Composable
private fun ResultRow(r: WorkflowItemResult) {
    val c = PM.colors
    val (icon, tint, label) = when (r.status) {
        WorkflowItemResult.Status.DONE -> Triple(Icons.Rounded.CheckCircle, c.success, R.string.wf_status_done)
        WorkflowItemResult.Status.SKIPPED -> Triple(Icons.Rounded.RemoveCircleOutline, c.textSecondary, R.string.wf_status_skipped)
        WorkflowItemResult.Status.TARGET_NOT_MET -> Triple(Icons.Rounded.WarningAmber, c.warning, R.string.wf_status_notmet)
        WorkflowItemResult.Status.FAILED -> Triple(Icons.Rounded.ErrorOutline, c.cleanup, R.string.wf_status_failed)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = AppSpacing.m, vertical = AppSpacing.xs).testTag("wf.result.${r.status.name}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Icon(icon, null, tint = tint)
        Column(Modifier.weight(1f)) {
            Text(r.outputName ?: r.asset.displayName ?: "—", style = AppTypography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sizes = if (r.beforeBytes != null && r.afterBytes != null && r.status == WorkflowItemResult.Status.DONE)
                stringResource(R.string.tools_sizes, SizeText.bytes(r.beforeBytes), SizeText.bytes(r.afterBytes)) else null
            Text(listOfNotNull(stringResource(label), sizes, reasonText(r)).joinToString(" · "), style = AppTypography.footnote, color = c.textSecondary)
        }
    }
}

@Composable
private fun reasonText(r: WorkflowItemResult): String? = when (r.reason) {
    WorkflowItemResult.Reason.VIDEO_NOT_IN_WORKFLOW -> stringResource(R.string.wf_reason_video)
    WorkflowItemResult.Reason.VIDEO_TOO_LONG_FOR_TARGET -> stringResource(R.string.wf_reason_video_long)
    WorkflowItemResult.Reason.PHOTO_TARGET_UNREACHABLE, WorkflowItemResult.Reason.VIDEO_TARGET_UNREACHABLE ->
        r.afterBytes?.let { stringResource(R.string.wf_reason_unreachable, SizeText.bytes(it)) } ?: stringResource(R.string.wf_reason_video_long)
    WorkflowItemResult.Reason.ERROR -> stringResource(R.string.wf_reason_error)
    null -> null
}

@Composable private fun Label(t: Int) = Text(stringResource(t), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = PM.colors.textSecondary,
    modifier = Modifier.padding(top = AppSpacing.xs))
@Composable private fun Note(t: Int) = Text(stringResource(t), style = AppTypography.footnote, color = PM.colors.textSecondary)
@Composable private fun ChipLine(content: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) { content() }
@Composable private fun Toggle(label: Int, on: Boolean, tag: String, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Switch) { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = AppTypography.body, color = PM.colors.textPrimary, modifier = Modifier.weight(1f)); Switch(on, onChange, Modifier.testTag(tag))
    }
