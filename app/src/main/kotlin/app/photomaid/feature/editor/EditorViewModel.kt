package app.photomaid.feature.editor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.photomaid.AppModel
import app.photomaid.R
import app.photomaid.core.edit.Adjustments
import app.photomaid.core.edit.AspectPreset
import app.photomaid.core.edit.FilterId
import app.photomaid.core.edit.CropMath
import app.photomaid.core.edit.EditGeometry
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.NormRect
import app.photomaid.core.edit.PxSize
import app.photomaid.core.edit.SaveMode
import app.photomaid.core.edit.TrimRange
import app.photomaid.core.edit.flippedH
import app.photomaid.core.edit.flippedV
import app.photomaid.core.model.MediaKind
import app.photomaid.media.EditError
import app.photomaid.media.EditSource
import app.photomaid.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EditorTab { CROP, ADJUST, FILTER, RESIZE, TRIM }
enum class AdjustTool { BRIGHTNESS, CONTRAST, SATURATION, WARMTH, SHARPNESS }

/**
 * Zustand des Editors. Bearbeitet wird ein Rezept ([EditRecipe]) – nicht destruktiv, mit eigenem Rückgängig/Wiederholen.
 * Das Original wird erst beim Speichern gelesen und nie überschrieben (Kopie oder Ersetzen über den Android-Papierkorb).
 */
class EditorViewModel(private val app: AppModel, val assetId: String) : ViewModel() {
    var asset by mutableStateOf<MediaAsset?>(null); private set
    var source by mutableStateOf<EditSource?>(null); private set
    var loadFailed by mutableStateOf(false); private set
    val isVideo: Boolean get() = asset?.kind == MediaKind.VIDEO

    var recipe by mutableStateOf(EditRecipe()); private set
    private var committed = EditRecipe()
    private val undoStack = ArrayDeque<EditRecipe>()
    private val redoStack = ArrayDeque<EditRecipe>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    var tab by mutableStateOf(EditorTab.CROP)
    var adjustTool by mutableStateOf(AdjustTool.BRIGHTNESS)
    var aspect by mutableStateOf(AspectPreset.FREE); private set
    var landscape by mutableStateOf(false); private set

    /** Vorschau ohne Farbe (Farbe kommt live über die GPU): im Zuschneiden der ganze Rahmen, sonst das Ergebnis. */
    var framePreview by mutableStateOf<Bitmap?>(null); private set
    var resultPreview by mutableStateOf<Bitmap?>(null); private set
    var filterThumb by mutableStateOf<Bitmap?>(null); private set
    private var renderJob: Job? = null

    // Video
    var trim by mutableStateOf<TrimRange?>(null); private set
    /** `null` = wird geladen; leer = keine Vorschaubilder lesbar (Zeitleiste bleibt bedienbar). */
    var thumbnails by mutableStateOf<List<Bitmap>?>(null); private set
    /** Wiedergabeposition der Vorschau (Zeitleiste). */
    var playheadMs by mutableStateOf(0L)
    val durationMs: Long get() = ((asset?.duration ?: 0.0) * 1000).toLong()

    // Speichern
    var saving by mutableStateOf(false); private set
    var progress by mutableStateOf<Float?>(null); private set
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<EditOutcome.Saved?>(null); private set
    private var saveJob: Job? = null

    val hasChanges: Boolean get() = if (isVideo) trim?.let { it.startMs > 0 || it.endMs < durationMs } == true else !recipe.isIdentity
    val needsPro: Boolean get() = (isVideo || recipe.usesProTools) && !app.capabilities.hasProToolsAccess
    val fullSize: PxSize? get() = source?.full
    val outputSize: PxSize? get() = fullSize?.let { EditGeometry(it, recipe).output }
    val cropSize: PxSize? get() = fullSize?.let { EditGeometry(it, recipe).cropSize }
    val frameSize: PxSize? get() = fullSize?.let { EditGeometry(it, recipe).frame }

    init {
        viewModelScope.launch {
            val a = withContext(Dispatchers.IO) { app.allPhotos.assets(listOf(assetId)).firstOrNull() }
            asset = a
            if (a == null) { loadFailed = true; return@launch }
            if (a.kind == MediaKind.VIDEO) {
                trim = TrimRange(0, ((a.duration) * 1000).toLong().coerceAtLeast(TrimRange.MIN_MS))
                tab = EditorTab.TRIM
                thumbnails = withContext(Dispatchers.IO) { videoFrames(a) }
            } else {
                source = runCatching { app.editEngine.loadPreview(a.uri, a.mimeType) }.getOrElse { loadFailed = true; return@launch }
                filterThumb = withContext(Dispatchers.Default) { source?.preview?.let { scaleDown(it, 220) } }
                rerender()
            }
        }
    }

    private fun scaleDown(b: Bitmap, edge: Int): Bitmap {
        val s = edge.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, maxOf(1, (b.width * s).toInt()), maxOf(1, (b.height * s).toInt()), true)
    }

    private fun videoFrames(a: MediaAsset): List<Bitmap> = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(app.context, a.uri)
            val total = (a.duration * 1_000_000).toLong()
            (0 until 8).mapNotNull { i -> r.getScaledFrameAtTime(total * i / 8, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160) }
        } finally { r.release() }
    }.getOrDefault(emptyList())

    // Rezept

    /** [commit] = Schritt für Rückgängig (Loslassen eines Reglers, Tipp auf einen Knopf). */
    fun update(next: EditRecipe, commit: Boolean = true) {
        recipe = next
        if (commit && next != committed) {
            undoStack.addLast(committed); if (undoStack.size > 100) undoStack.removeFirst()
            redoStack.clear(); committed = next
        }
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
        rerender()
    }

    fun commit() = update(recipe, commit = true)

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(committed); committed = prev; recipe = prev
        canUndo = undoStack.isNotEmpty(); canRedo = true; rerender()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(committed); committed = next; recipe = next
        canUndo = true; canRedo = redoStack.isNotEmpty(); rerender()
    }

    fun resetTab() {
        val r = recipe
        update(when (tab) {
            EditorTab.CROP -> r.copy(quarterTurns = 0, flipH = false, flipV = false, straighten = 0f, crop = NormRect.FULL, resizeWidth = null, resizeHeight = null)
            EditorTab.ADJUST -> r.copy(adjust = Adjustments())
            EditorTab.FILTER -> r.copy(filter = FilterId.NONE, filterIntensity = 1f)
            EditorTab.RESIZE -> r.copy(resizeWidth = null, resizeHeight = null)
            EditorTab.TRIM -> r
        }.also { if (tab == EditorTab.CROP) aspect = AspectPreset.FREE })
    }

    // Zuschneiden

    fun rotate() = update(recipe.rotatedClockwise().let { r -> currentRatio(r)?.let { r.copy(crop = CropMath.fitAspect(r.crop, it, frameOf(r))) } ?: r })
    fun flipHorizontal() = update(recipe.copy(flipH = !recipe.flipH, crop = recipe.crop.flippedH()))
    fun flipVertical() = update(recipe.copy(flipV = !recipe.flipV, crop = recipe.crop.flippedV()))
    /** Beim Begradigen zeigt der Ausschnitt ein feines Raster. */
    var straightening by mutableStateOf(false); private set
    fun straighten(deg: Float, commit: Boolean) {
        straightening = !commit
        update(recipe.copy(straighten = deg.coerceIn(-45f, 45f)), commit)
    }

    private fun frameOf(r: EditRecipe) = EditGeometry(fullSize ?: PxSize(1, 1), r).frame
    private fun currentRatio(r: EditRecipe = recipe): Double? = CropMath.ratio(aspect, frameOf(r), landscape)

    fun chooseAspect(p: AspectPreset) {
        aspect = p
        val f = frameOf(recipe)
        if (p == AspectPreset.ORIGINAL) landscape = f.width >= f.height
        else if (p != AspectPreset.FREE && p != AspectPreset.SQUARE) landscape = p.w > p.h // wie beschriftet; Hoch/Quer tauscht
        currentRatio()?.let { update(recipe.copy(crop = CropMath.fitAspect(NormRect.FULL, it, f), resizeWidth = null, resizeHeight = null)) }
    }

    fun toggleOrientation() {
        landscape = !landscape
        currentRatio()?.let { update(recipe.copy(crop = CropMath.fitAspect(NormRect.FULL, it, frameOf(recipe)), resizeWidth = null, resizeHeight = null)) }
    }

    fun dragCrop(h: CropMath.Handle, dx: Float, dy: Float) {
        val next = CropMath.drag(recipe.crop, h, dx, dy, frameOf(recipe), currentRatio())
        update(recipe.copy(crop = next, resizeWidth = null, resizeHeight = null), commit = false)
    }

    /** Bedienungshilfe: Ausschnitt gleichmäßig um [step] verkleinern (negativ = vergrößern). */
    fun insetCrop(step: Float) {
        val c = recipe.crop
        val nx = (c.width * step / 2); val ny = (c.height * step / 2)
        val next = NormRect((c.left + nx).coerceIn(0f, 1f), (c.top + ny).coerceIn(0f, 1f), (c.right - nx).coerceIn(0f, 1f), (c.bottom - ny).coerceIn(0f, 1f))
        if (next.width > 0.08f && next.height > 0.08f) update(recipe.copy(crop = next, resizeWidth = null, resizeHeight = null))
    }

    // Anpassen / Filter / Größe

    fun adjust(tool: AdjustTool, v: Float, commit: Boolean) {
        val a = recipe.adjust
        update(recipe.copy(adjust = when (tool) {
            AdjustTool.BRIGHTNESS -> a.copy(brightness = v); AdjustTool.CONTRAST -> a.copy(contrast = v)
            AdjustTool.SATURATION -> a.copy(saturation = v); AdjustTool.WARMTH -> a.copy(warmth = v)
            AdjustTool.SHARPNESS -> a.copy(sharpness = v.coerceIn(0f, 100f))
        }), commit)
    }

    fun valueOf(tool: AdjustTool): Float = recipe.adjust.let {
        when (tool) { AdjustTool.BRIGHTNESS -> it.brightness; AdjustTool.CONTRAST -> it.contrast; AdjustTool.SATURATION -> it.saturation
            AdjustTool.WARMTH -> it.warmth; AdjustTool.SHARPNESS -> it.sharpness }
    }

    fun setFilter(id: FilterId) = update(recipe.copy(filter = id, filterIntensity = if (id == recipe.filter) recipe.filterIntensity else 1f))
    fun filterIntensity(v: Float, commit: Boolean) = update(recipe.copy(filterIntensity = v.coerceIn(0f, 1f)), commit)
    /** „Nur Zuschnitt speichern“ (ohne Pro): Regler, Filter und Größe zurücknehmen, Geometrie bleibt. */
    fun dropProTools() = update(recipe.copy(adjust = Adjustments(), filter = FilterId.NONE, filterIntensity = 1f, resizeWidth = null, resizeHeight = null))
    fun resize(size: PxSize?) = update(recipe.copy(resizeWidth = size?.width, resizeHeight = size?.height))

    // Video

    fun setTrim(start: Long, end: Long) { trim = TrimRange.clamp(start, end, durationMs) }

    // Vorschau

    private fun rerender() {
        val src = source ?: return
        val r = recipe
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            delay(16) // Ziehen bündeln
            val previewRecipe = r.copy(resizeWidth = null, resizeHeight = null)
            val frame = app.editEngine.render(src.preview, previewRecipe.copy(crop = NormRect.FULL), colorAndSharpen = false)
            // Ergebnis: Schärfen hier (CPU), Farbe kommt in der Anzeige über die GPU – gleiche Mathematik wie beim Export.
            val sharpOnly = previewRecipe.copy(adjust = Adjustments(sharpness = r.adjust.sharpness), filter = FilterId.NONE)
            val res = app.editEngine.render(src.preview, sharpOnly, colorAndSharpen = r.hasSharpen)
            withContext(Dispatchers.Main) { framePreview = frame; resultPreview = res }
        }
    }

    // Speichern

    fun save(mode: SaveMode, removeLocation: Boolean) {
        val a = asset ?: return
        if (saving || needsPro) return
        saving = true; progress = null; error = null
        saveJob = viewModelScope.launch {
            app.updateSettings { it.copy(editSaveMode = mode) }
            try {
                // Geändert, gelöscht oder verschoben seit dem Öffnen? Dann nichts speichern.
                val now = withContext(Dispatchers.IO) { app.allPhotos.assets(listOf(a.id)).firstOrNull() }
                if (now == null) { error = app.string(R.string.edit_err_gone); return@launch }
                result = if (a.kind == MediaKind.VIDEO) EditSaver.saveVideo(app, a, trim ?: return@launch, mode) { p -> progress = p }
                else EditSaver.savePhoto(app, a, recipe, source?.full ?: return@launch, mode, removeLocation)
            } catch (e: EditError.TooLarge) {
                error = app.string(R.string.edit_err_too_large)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = app.string(R.string.edit_err_save)
            } finally { saving = false; progress = null }
        }
    }

    fun cancelSave() { saveJob?.cancel(); saving = false; progress = null }
}
