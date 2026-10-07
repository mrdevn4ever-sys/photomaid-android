package app.photomaid.feature.studio

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.photomaid.AppModel
import app.photomaid.R
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.edit.EditKind
import app.photomaid.core.edit.EditRecord
import app.photomaid.core.edit.Layer
import app.photomaid.core.edit.SaveMode
import app.photomaid.core.model.ItemReference
import app.photomaid.core.studio.CollageExport
import app.photomaid.core.studio.CollageItem
import app.photomaid.core.studio.CollageKind
import app.photomaid.core.studio.CollageLayout
import app.photomaid.core.studio.CollageProject
import app.photomaid.core.studio.Divider
import app.photomaid.feature.editor.MarkupController
import app.photomaid.media.EditSaveRequest
import app.photomaid.media.EditSaveResult
import app.photomaid.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import app.photomaid.core.edit.NormPoint
import app.photomaid.core.studio.BackgroundKind

enum class CollageTab { LAYOUT, FORMAT, SPACING, BACKGROUND, PHOTO, MARKUP }

/** Exportgröße einer Collage. */
enum class CollageSize(val longEdge: Int) { STANDARD(2048), LARGE(4096), A4(CollageExport.A4_LONG) }

/**
 * Collage bearbeiten: Projekt mit Rückgängig/Wiederholen, Vorschau aus kleinen Quellen, Entwürfe, Export.
 * Fotos werden nur gelesen; die Collage ist eine neue Datei (gilt als „Behalten“, nie eine Entscheidung über die Quellen).
 */
class CollageViewModel(private val app: AppModel, start: CollageProject, private val fromDraft: Boolean) : ViewModel() {
    var project by mutableStateOf(start); private set
    private var committed = start
    private val undoStack = ArrayDeque<CollageProject>()
    private val redoStack = ArrayDeque<CollageProject>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    /** Seit dem Öffnen geändert (für „Entwurf behalten?“). */
    var dirty by mutableStateOf(false); private set

    var tab by mutableStateOf(CollageTab.LAYOUT)
    var selected by mutableStateOf<Int?>(null)
    var preview by mutableStateOf<Bitmap?>(null); private set
    private var assets: Map<String, MediaAsset> = emptyMap()
    var missing by mutableStateOf<Set<String>>(emptySet()); private set
    private val raw = HashMap<String, Bitmap>()
    private val prepared = HashMap<Pair<String, CollageItem>, Bitmap>()
    private var renderJob: Job? = null

    var saving by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var savedId by mutableStateOf<String?>(null); private set
    var shareUri by mutableStateOf<android.net.Uri?>(null)
    var targetFolder by mutableStateOf<String?>(null)
    var size by mutableStateOf(CollageSize.STANDARD)
    var png by mutableStateOf(false)

    val markup = MarkupController(layers = { project.layers }, setLayers = { l, c -> update(project.copy(layers = l), c) },
        defaultText = { app.string(R.string.edit_text_default) },
        dateText = { java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(Date()) })

    /** Free: Raster/Streifen bis 4 Fotos; Pro: bis 20, freie Collage, A4-Druck. */
    val needsPro: Boolean get() = !app.capabilities.hasProToolsAccess &&
        (project.count > CollageProject.FREE_MAX || project.kind == CollageKind.FREE || size == CollageSize.A4)

    init { viewModelScope.launch { loadAssets(); rerender() } }

    private suspend fun loadAssets() {
        val ids = project.items.map { it.mediaId }.distinct()
        val found = withContext(Dispatchers.IO) { app.allPhotos.assets(ids) }.associateBy { it.id }
        assets = assets + found
        missing = ids.filterNot { it in found }.toSet()
    }

    // Projekt

    fun update(next: CollageProject, commit: Boolean = true) {
        project = next
        if (commit && next != committed) {
            undoStack.addLast(committed); if (undoStack.size > 80) undoStack.removeFirst()
            redoStack.clear(); committed = next; dirty = true
        }
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
        rerender(fast = !commit)
    }

    fun commit() = update(project, true)

    fun undo() { val p = undoStack.removeLastOrNull() ?: return; redoStack.addLast(committed); committed = p; project = p; refreshStacks() }
    fun redo() { val p = redoStack.removeLastOrNull() ?: return; undoStack.addLast(committed); committed = p; project = p; refreshStacks() }
    private fun refreshStacks() { canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty(); dirty = true; rerender() }

    fun setKind(k: CollageKind) {
        if (k == project.kind) return
        val base = CollageProject.create(project.id, project.items.map { it.mediaId }, k)
        update(base.copy(layers = project.layers, background = if (k == CollageKind.FREE) base.background else project.background))
        selected = null
    }
    fun setTemplate(t: String) = update(project.withTemplate(t))
    fun moveDivider(d: Divider, pos: Float, commit: Boolean) = update(project.copy(tree = CollageLayout.moveDivider(project.tree, d, pos)), commit)
    fun setAspect(a: Float) = update(project.copy(aspect = a))
    fun setSpacing(v: Float, commit: Boolean) = update(project.copy(spacing = v), commit)
    fun setMargin(v: Float, commit: Boolean) = update(project.copy(margin = v), commit)
    fun setCorner(v: Float, commit: Boolean) = update(project.copy(corner = v), commit)
    fun setBackground(color: Int) = update(project.copy(background = project.background.copy(kind = BackgroundKind.COLOR, color = color)))
    fun setBlurBackground() = update(project.copy(background = project.background.copy(kind = BackgroundKind.BLUR, blurIndex = selected ?: 0)))
    fun setPolaroid(on: Boolean) = update(project.copy(polaroid = on))
    fun setStripVertical(v: Boolean) = update(project.copy(stripVertical = v))

    fun updateItem(i: Int, commit: Boolean, change: (CollageItem) -> CollageItem) {
        val it = project.items.getOrNull(i) ?: return
        update(project.copy(items = project.items.toMutableList().also { l -> l[i] = change(it) }), commit)
    }
    fun swap(a: Int, b: Int) { update(project.swap(a, b)); selected = b }
    fun removeSelected() { val i = selected ?: return; update(project.removeItem(i)); selected = null }
    fun addPhotos(ids: List<String>) = viewModelScope.launch {
        val room = CollageProject.MAX - project.count
        update(project.addItems(ids.filterNot { id -> project.items.any { it.mediaId == id } }.take(room))); loadAssets(); rerender()
    }
    fun replaceSelected(id: String) = viewModelScope.launch {
        val i = selected ?: return@launch
        updateItem(i, true) { CollageItem(id, center = it.center, width = it.width, rotation = it.rotation) }; loadAssets(); rerender()
    }

    // Vorschau

    /** Lange Kante der Vorschauquellen: genug für das Feld, wenig Speicher bei vielen Fotos. */
    private fun rawEdge(): Int = (1700 / sqrt(max(1, project.count).toDouble())).roundToInt().coerceIn(420, 1400)

    private suspend fun rawFor(id: String): Bitmap? {
        raw[id]?.let { return it }
        val a = assets[id] ?: return null
        val b = runCatching { app.editEngine.loadPreview(a.uri, a.mimeType, rawEdge()).preview }.getOrNull() ?: return null
        raw[id] = b
        return b
    }

    private fun keyOf(item: CollageItem) = item.mediaId to item.copy(zoom = 1f, panX = 0f, panY = 0f, fit = false,
        center = NormPoint(0f, 0f), width = 0f, rotation = 0f, trimStart = 0f, trimEnd = 0f)

    /** Seitenverhältnis (Breite/Höhe) des Fotos [i] wie gezeichnet (nach Drehen), `null` solange es nicht geladen ist. */
    fun aspectOf(i: Int): Float? {
        val item = project.items.getOrNull(i) ?: return null
        synchronized(prepared) { prepared[keyOf(item)] }?.let { return it.width.toFloat() / it.height }
        val r = raw[item.mediaId] ?: return null
        return if (item.quarterTurns % 2 == 0) r.width.toFloat() / r.height else r.height.toFloat() / r.width
    }

    private fun rerender(fast: Boolean = false) {
        val p = project
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            delay(if (fast) 4 else 16)
            val srcs = p.items.map { item ->
                val r = rawFor(item.mediaId) ?: return@map null
                val key = keyOf(item)
                synchronized(prepared) { prepared[key] } ?: app.collageRenderer.prepare(r, item).also { b ->
                    // Nur die aktuelle Fassung je Foto behalten (Regler erzeugen sonst viele Kopien).
                    synchronized(prepared) { prepared.keys.removeAll { it.first == item.mediaId }; prepared[key] = b }
                }
            }
            val sizes = srcs.map { it?.let { b -> b.width to b.height } ?: (1 to 1) }
            val (w, h) = app.collageRenderer.size(p, if (fast) 900 else 1400, sizes)
            val out = app.collageRenderer.render(p, w, h, srcs)
            withContext(Dispatchers.Main) { preview = out }
        }
    }

    // Entwürfe

    /** Als Entwurf behalten (lokal). Free: die 3 neuesten bleiben. */
    fun saveDraft(after: () -> Unit = {}) = viewModelScope.launch {
        val limit = if (app.capabilities.hasProToolsAccess) DraftStore.MAX else DraftStore.FREE_MAX
        val thumb = preview?.let { b -> val s = 360f / max(b.width, b.height); if (s >= 1f) b else Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true) }
        app.drafts.put(Draft(project.id, System.currentTimeMillis(), collage = project), thumb, limit)
        dirty = false
        after()
    }

    fun discardDraft(after: () -> Unit) = viewModelScope.launch { if (fromDraft) app.drafts.remove(project.id); after() }

    // Speichern

    private suspend fun exportFile(): File = withContext(Dispatchers.Default) {
        val p = project
        val longEdge = CollageExport.clampLong(size.longEdge)
        val sizes = p.items.indices.map { i -> aspectOf(i)?.let { (it * 1000).roundToInt() to 1000 } ?: (1 to 1) }
        val (w, h) = if (size == CollageSize.A4 && p.kind != CollageKind.STRIP)
            (if (p.aspect >= 1f) CollageExport.A4_LONG to CollageExport.A4_SHORT else CollageExport.A4_SHORT to CollageExport.A4_LONG)
        else app.collageRenderer.size(p, longEdge, sizes)
        // A4: Seitenverhältnis an das Papier anpassen (das Raster füllt das Blatt).
        val pp = if (size == CollageSize.A4 && p.kind != CollageKind.STRIP) p.copy(aspect = w.toFloat() / h) else p
        val out = app.collageRenderer.render(pp, w, h, sizes, get = { i ->
            val item = pp.items[i]; val a = assets[item.mediaId] ?: return@render null
            val edge = app.collageRenderer.neededEdge(pp, i, w, h)
            val b = kotlinx.coroutines.runBlocking { runCatching { app.editEngine.loadPreview(a.uri, a.mimeType, edge).preview }.getOrNull() } ?: return@render null
            app.collageRenderer.prepare(b, item).also { if (it !== b) b.recycle() }
        }, release = { _, b -> b.recycle() })
        val f = File(app.context.cacheDir, "collage_${System.nanoTime()}.${if (png) "png" else "jpg"}")
        f.outputStream().use { o -> out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, o) }
        out.recycle()
        f
    }

    private fun fileName(ext: String): String =
        "PhotoMaid_${app.string(R.string.studio_collage_file)}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())}.$ext"
            .replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")

    fun save() {
        if (saving || needsPro) return
        saving = true; error = null
        viewModelScope.launch {
            try {
                val f = exportFile()
                val ext = if (png) "png" else "jpg"
                val r = app.photos.saveEdited(EditSaveRequest(sourceId = "", file = f, mime = if (png) "image/png" else "image/jpeg", displayName = fileName(ext),
                    relativePath = targetFolder ?: "Pictures/PhotoMaid/", dateTakenMs = System.currentTimeMillis(), video = false, replace = false, favorite = false, replaceName = null))
                f.delete()
                val saved = r as? EditSaveResult.Saved ?: throw IllegalStateException("save")
                runCatching { app.decide(ItemReference.photo(saved.newId), DecisionAction.Keep) }
                app.edits.add(EditRecord(saved.newId, "", SaveMode.COPY, false, System.currentTimeMillis(), EditKind.COLLAGE, project.count))
                if (fromDraft) app.drafts.remove(project.id)
                savedId = saved.newId
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: OutOfMemoryError) { error = app.string(R.string.studio_err_memory)
            } catch (e: Exception) { error = app.string(R.string.edit_err_save)
            } finally { saving = false }
        }
    }

    fun share() {
        if (saving || needsPro) return
        saving = true; error = null
        viewModelScope.launch {
            try { val f = exportFile(); shareUri = ShareCache.stage(app.context, f, fileName(if (png) "png" else "jpg")) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { error = app.string(R.string.edit_err_save) }
            finally { saving = false }
        }
    }

    @Suppress("unused") private fun layersOf(): List<Layer> = project.layers
}
