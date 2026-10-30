package app.photomaid.feature.workflow

import android.net.Uri
import app.photomaid.AppModel
import app.photomaid.R
import app.photomaid.core.batch.TargetSize
import app.photomaid.core.batch.VideoTarget
import app.photomaid.core.batch.Workflow
import app.photomaid.core.batch.WorkflowFinish
import app.photomaid.core.batch.WorkflowFormat
import app.photomaid.core.batch.WorkflowRules
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.edit.EditFormat
import app.photomaid.core.edit.EditKind
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.EditRecord
import app.photomaid.core.edit.PxSize
import app.photomaid.core.edit.ResizeRules
import app.photomaid.core.edit.SaveMode
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.MediaKind
import app.photomaid.core.studio.VideoClip
import app.photomaid.core.studio.VideoProject
import app.photomaid.data.KeyValueStore
import app.photomaid.feature.studio.ShareCache
import app.photomaid.media.EditSaveRequest
import app.photomaid.media.EditSaveResult
import app.photomaid.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/** Vorlagen zum Start (werden beim Hinzufügen als eigene, änderbare Kopie gespeichert). */
enum class WorkflowTemplate(val label: Int) {
    CLASSIFIEDS(R.string.wf_tpl_classifieds), SEND_SMALL(R.string.wf_tpl_send_small), FAMILY(R.string.wf_tpl_family);

    fun create(app: AppModel): Workflow {
        val id = UUID.randomUUID().toString(); val name = app.string(label)
        return when (this) {
            CLASSIFIEDS -> Workflow(id, name, WorkflowFormat.JPEG, longEdge = 2048, removeLocation = true,
                nameBase = app.string(R.string.wf_tpl_classifieds_base), finish = WorkflowFinish.SHARE)
            SEND_SMALL -> Workflow(id, name, WorkflowFormat.JPEG, removeLocation = true, photoMaxBytes = 2_000_000, videoMaxBytes = 20_000_000,
                finish = WorkflowFinish.SHARE)
            FAMILY -> Workflow(id, name, WorkflowFormat.JPEG, longEdge = 2048, quality = 85, folder = "Pictures/GaleriQ/", finish = WorkflowFinish.SAVE)
        }
    }
}

/** Gespeicherte Arbeitsabläufe (lokal, JSON im Key-Value-Speicher wie die Vorlagen des Editors). */
class WorkflowStore(private val store: KeyValueStore) {
    private val _items = MutableStateFlow<List<Workflow>>(emptyList())
    val items: StateFlow<List<Workflow>> = _items.asStateFlow()

    companion object { private const val KEY = "workflows.v1"; const val MAX = 30 }

    fun load() { _items.value = runCatching { store.value<List<Workflow>>(KEY) }.getOrNull() ?: emptyList() }

    /** Neu oder an gleicher Stelle ersetzen. */
    suspend fun upsert(w: Workflow) = save(if (_items.value.any { it.id == w.id }) _items.value.map { if (it.id == w.id) w else it } else (_items.value + w).take(MAX))
    suspend fun remove(id: String) = save(_items.value.filterNot { it.id == id })
    fun get(id: String) = _items.value.firstOrNull { it.id == id }

    private suspend fun save(next: List<Workflow>) {
        withContext(Dispatchers.IO) { runCatching { store.setValue(KEY, next) } }
        _items.value = next
    }
}

/** Ergebnis je Datei – alles gemessen, nichts geschätzt. */
data class WorkflowItemResult(
    val asset: MediaAsset,
    val status: Status,
    val outputName: String? = null,
    val beforeBytes: Long? = null,
    val afterBytes: Long? = null,
    val reason: Reason? = null,
) {
    enum class Status { DONE, SKIPPED, TARGET_NOT_MET, FAILED }
    enum class Reason { VIDEO_NOT_IN_WORKFLOW, VIDEO_TOO_LONG_FOR_TARGET, PHOTO_TARGET_UNREACHABLE, VIDEO_TARGET_UNREACHABLE, ERROR }
}

data class WorkflowRun(val items: List<WorkflowItemResult>, val shared: List<Uri>) {
    val done get() = items.count { it.status == WorkflowItemResult.Status.DONE }
    val skipped get() = items.count { it.status == WorkflowItemResult.Status.SKIPPED }
    val notMet get() = items.count { it.status == WorkflowItemResult.Status.TARGET_NOT_MET }
    val failed get() = items.count { it.status == WorkflowItemResult.Status.FAILED }
}

/** Gemeinsames Budget für die ganze Auswahl statt einer Grenze je Datei. */
data class WorkflowBudget(val totalBytes: Long)

/**
 * Führt einen Ablauf aus. Jede Datei: neue Datei (gespeichert und/oder nur zum Teilen im Cache); Quellen bleiben
 * unverändert und ohne Entscheidung; gespeicherte Ergebnisse gelten als „Behalten“. Eine Zielgröße wird gemessen und bei
 * Bedarf erneut kodiert – wird sie innerhalb der Grenzen nicht erreicht, entsteht KEINE (zu große) Datei.
 */
object WorkflowRunner {
    private const val SOFTWARE = "GaleriQ"

    suspend fun run(app: AppModel, w: Workflow, assets: List<MediaAsset>, budget: WorkflowBudget? = null, onProgress: (Float) -> Unit): WorkflowRun {
        val out = mutableListOf<WorkflowItemResult>()
        val shared = mutableListOf<Uri>()
        val suffix = app.string(R.string.edit_copy_suffix)
        // Budget: Anteile nach Originalgröße – nur für Medien, die der Ablauf überhaupt bearbeitet.
        val handled = assets.filter { it.kind != MediaKind.VIDEO || w.handlesVideos }
        val shares = budget?.let { b -> handled.zip(WorkflowRules.budgetShares(handled.map { it.size.bytesOrNull }, b.totalBytes)).toMap() } ?: emptyMap()
        for ((i, a) in assets.withIndex()) {
            onProgress(i.toFloat() / assets.size.coerceAtLeast(1))
            val stem = WorkflowRules.fileStem(w, i, assets.size, a.displayName, suffix)
            val r = try {
                if (a.kind == MediaKind.VIDEO) video(app, w, a, stem, shares[a], shared) else photo(app, w, a, stem, shares[a], shared)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("PhotoMaid", "Arbeitsablauf: Datei fehlgeschlagen", e); WorkflowItemResult(a, WorkflowItemResult.Status.FAILED, beforeBytes = a.size.bytesOrNull, reason = WorkflowItemResult.Reason.ERROR) }
            out += r
        }
        onProgress(1f)
        return WorkflowRun(out, shared)
    }

    private suspend fun photo(app: AppModel, w: Workflow, a: MediaAsset, stem: String, share: Long?, shared: MutableList<Uri>): WorkflowItemResult {
        val fmt = if (WorkflowRules.outputFormat(w, a.mimeType) == WorkflowFormat.PNG) EditFormat.PNG else EditFormat.JPEG
        val full = PxSize(a.pixelWidth.coerceAtLeast(1), a.pixelHeight.coerceAtLeast(1))
        val edge = WorkflowRules.targetEdge(w, full.width, full.height)
        val files = mutableMapOf<TargetSize.Attempt, File>()
        suspend fun encode(at: TargetSize.Attempt): Long {
            val recipe = if (at.longEdge < max(full.width, full.height)) ResizeRules.forLongEdge(full, at.longEdge).let { EditRecipe(resizeWidth = it.width, resizeHeight = it.height) } else EditRecipe()
            val e = app.editEngine.export(a.uri, a.mimeType, recipe, full, w.removeLocation, SOFTWARE, formatOverride = fmt,
                quality = if (fmt == EditFormat.JPEG) at.quality else null)
            files[at]?.delete(); files[at] = e.file
            return e.file.length()
        }
        val maxBytes = listOfNotNull(w.photoMaxBytes, share).minOrNull()
        val chosen: TargetSize.Outcome = if (maxBytes == null) {
            val at = TargetSize.Attempt(edge, w.quality); TargetSize.Outcome.Met(at, encode(at))
        } else TargetSize.search(maxBytes, edge, w.quality, minOf(w.minLongEdge, edge), w.minQuality, lossless = fmt == EditFormat.PNG) { encode(it) }
        try {
            if (chosen is TargetSize.Outcome.Unreachable)
                return WorkflowItemResult(a, WorkflowItemResult.Status.TARGET_NOT_MET, beforeBytes = a.size.bytesOrNull, afterBytes = chosen.bytes,
                    reason = WorkflowItemResult.Reason.PHOTO_TARGET_UNREACHABLE)
            val file = files.getValue(chosen.attempt)
            return finish(app, w, a, file, fmt.mime, "$stem.${fmt.extension}", video = false, kind = if (w.format != WorkflowFormat.KEEP) EditKind.CONVERT else EditKind.COMPRESS, shared = shared)
        } finally { files.values.forEach { it.delete() } }
    }

    private suspend fun video(app: AppModel, w: Workflow, a: MediaAsset, stem: String, share: Long?, shared: MutableList<Uri>): WorkflowItemResult {
        val target = listOfNotNull(w.videoMaxBytes, share).minOrNull()
            ?: return WorkflowItemResult(a, WorkflowItemResult.Status.SKIPPED, beforeBytes = a.size.bytesOrNull, reason = WorkflowItemResult.Reason.VIDEO_NOT_IN_WORKFLOW)
        val info = withContext(Dispatchers.IO) { app.videoExporter.info(a.uri) } ?: throw IllegalStateException("info")
        val plan = VideoTarget.plan(target, info.durationMs, info.hasAudio, info.display.width, info.display.height, info.fps)
            ?: return WorkflowItemResult(a, WorkflowItemResult.Status.TARGET_NOT_MET, beforeBytes = a.size.bytesOrNull, reason = WorkflowItemResult.Reason.VIDEO_TOO_LONG_FOR_TARGET)
        var bitrate = plan.videoBitrate
        repeat(2) { attempt ->
            val clip = VideoClip(UUID.randomUUID().toString(), a.id, sourceDurationMs = info.durationMs)
            val project = VideoProject(UUID.randomUUID().toString(), listOf(clip), targetBitrate = bitrate, targetShortSide = plan.maxHeight)
            val file = app.videoExporter.export(a.uri, project, info, audioOnly = false) {}
            val bytes = file.length()
            if (bytes <= target) return finish(app, w, a, file, "video/mp4", "$stem.mp4", video = true, kind = EditKind.COMPRESS, shared = shared)
            file.delete()
            if (attempt == 1) return WorkflowItemResult(a, WorkflowItemResult.Status.TARGET_NOT_MET, beforeBytes = a.size.bytesOrNull, afterBytes = bytes,
                reason = WorkflowItemResult.Reason.VIDEO_TARGET_UNREACHABLE)
            // Gemessen zu groß: Bitrate im Verhältnis senken (mit Reserve) und einmal neu kodieren.
            bitrate = max(VideoTarget.MIN_VIDEO_BPS, (bitrate * 0.92 * target / bytes).toInt())
        }
        error("unreachable")
    }

    /** Speichern (neue Datei = „Behalten“) und/oder für das Teilen bereitstellen; liefert das gemessene Ergebnis. */
    private suspend fun finish(app: AppModel, w: Workflow, a: MediaAsset, file: File, mime: String, name: String, video: Boolean, kind: EditKind, shared: MutableList<Uri>): WorkflowItemResult {
        val bytes = file.length()
        if (w.saves) {
            val r = app.photos.saveEdited(EditSaveRequest(sourceId = a.id, file = file, mime = mime, displayName = name, relativePath = w.folder ?: a.relativePath,
                dateTakenMs = a.creationDate?.toEpochMilli(), video = video, replace = false, favorite = false, replaceName = null))
            val saved = r as? EditSaveResult.Saved ?: throw IllegalStateException("save")
            runCatching { app.decide(ItemReference.photo(saved.newId), DecisionAction.Keep) }
            app.edits.add(EditRecord(saved.newId, a.id, SaveMode.COPY, video, System.currentTimeMillis(), kind))
        }
        // Teilen: Datei in den Teilen-Ordner verschieben (befristete Freigabe); sonst die Zwischendatei löschen.
        if (w.shares) shared += withContext(Dispatchers.IO) { ShareCache.stage(app.context, file, name) } else file.delete()
        return WorkflowItemResult(a, WorkflowItemResult.Status.DONE, name, a.size.bytesOrNull, bytes)
    }
}
