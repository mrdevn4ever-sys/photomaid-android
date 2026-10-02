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
import app.photomaid.core.edit.ColorMix
import app.photomaid.core.edit.Histogram
import app.photomaid.core.edit.BrushKind
import app.photomaid.core.edit.BrushStroke
import app.photomaid.core.edit.DrawLayer
import app.photomaid.core.edit.DrawStroke
import app.photomaid.core.edit.FrameStyle
import app.photomaid.core.edit.Layer
import app.photomaid.core.edit.LayerOps
import app.photomaid.core.edit.NormPoint
import app.photomaid.core.edit.RedactLayer
import app.photomaid.core.edit.RedactMode
import app.photomaid.core.edit.ShapeKind
import app.photomaid.core.edit.ShapeLayer
import app.photomaid.core.edit.StickerKind
import app.photomaid.core.edit.StickerLayer
import app.photomaid.core.edit.TextLayer
import app.photomaid.core.edit.EditPreset
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
import app.photomaid.media.LayerRenderer
import app.photomaid.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.photomaid.core.edit.FrameMath

/** Ausschnitt der 100-%-Ansicht (normiert auf das Ausgabebild) und sein Bild. */
data class DetailTile(val view: NormRect, val bitmap: Bitmap)

enum class EditorTab { CROP, ADJUST, FILTER, MARKUP, FRAME, RESIZE, TRIM }

/** Werkzeug im Reiter „Markieren“. */
enum class MarkupTool { SELECT, DRAW, SHAPE, REDACT }

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

    private var tabState by mutableStateOf(EditorTab.CROP)
    /** Reiterwechsel rechnet die passende Vorschau (Zuschneiden: ganzer Rahmen, sonst Ergebnis). */
    var tab: EditorTab
        get() = tabState
        set(v) { if (v != tabState) { tabState = v; rerender() } }
    var adjustGroup by mutableStateOf(AdjustGroup.LIGHT)
    var adjustTool by mutableStateOf(AdjustTool.EXPOSURE)
    var aspect by mutableStateOf(AspectPreset.FREE); private set
    var landscape by mutableStateOf(false); private set

    /** Zuschneiden: ganzer (gedrehter) Rahmen mit Tonwerten/Farbe; sonst das fertige Ergebnis (gleiche Pipeline wie Export). */
    var framePreview by mutableStateOf<Bitmap?>(null); private set
    var resultPreview by mutableStateOf<Bitmap?>(null); private set
    var filterThumb by mutableStateOf<Bitmap?>(null); private set
    /** Vorschaubilder je Filter (aus dem aktuellen Foto, gleiche Pipeline). */
    var filterThumbs by mutableStateOf<Map<FilterId, Bitmap>>(emptyMap()); private set
    /** Kleine Stellvertreter-Fassung für flüssiges Ziehen; nach dem Loslassen wird in Vorschaugröße nachgerechnet. */
    private var proxy: Bitmap? = null
    private var renderJob: Job? = null
    /** Grundbild ohne Ebenen/Rahmen (Schlüssel, Bild, aus Stellvertreter gerechnet?). */
    private var baseCache: Triple<EditRecipe, Bitmap, Boolean>? = null
    /** Ergebnis ohne Ebenen (Markieren zeigt die gewählte Ebene live darüber). */
    var baseResult by mutableStateOf<Bitmap?>(null); private set


    // Anzeige-Hilfen (zeigen nur an, entscheiden nichts)
    var histogram by mutableStateOf<Histogram?>(null); private set
    var showHistogram by mutableStateOf(false)
    private var clipState by mutableStateOf(false)
    var showClipping: Boolean
        get() = clipState
        set(v) { clipState = v; rerender() }
    var clipOverlay by mutableStateOf<Bitmap?>(null); private set

    // 100-%-Ansicht
    var detail by mutableStateOf<DetailTile?>(null); private set
    private var detailJob: Job? = null

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
    val outputSize: PxSize? get() = fullSize?.let { FrameMath.layout(EditGeometry(it, recipe).output, recipe.frame).first }
    /** Größe des Bildes ohne Rahmen (Ebenen-Koordinaten). */
    val imageSize: PxSize? get() = fullSize?.let { EditGeometry(it, recipe).output }
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
                proxy = withContext(Dispatchers.Default) { source?.preview?.let { scaleDown(it, 720) } }
                rerender()
                val thumb = withContext(Dispatchers.Default) { source?.preview?.let { scaleDown(it, 220) } }
                filterThumb = thumb
                if (thumb != null) filterThumbs = withContext(Dispatchers.Default) {
                    FilterId.entries.associateWith { f ->
                        thumb.copy(Bitmap.Config.ARGB_8888, true).also { app.editEngine.applyPixels(it, EditRecipe(filter = f)) }
                    }
                }
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
        detail = null
        rerender(fast = !commit)
    }

    fun commit() = update(recipe, commit = true)

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(committed); committed = prev; recipe = prev; detail = null
        canUndo = undoStack.isNotEmpty(); canRedo = true; rerender()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(committed); committed = next; recipe = next; detail = null
        canUndo = true; canRedo = redoStack.isNotEmpty(); rerender()
    }

    fun resetTab() {
        val r = recipe
        update(when (tab) {
            EditorTab.CROP -> r.copy(quarterTurns = 0, flipH = false, flipV = false, straighten = 0f, crop = NormRect.FULL, resizeWidth = null, resizeHeight = null)
            EditorTab.ADJUST -> r.copy(adjust = Adjustments(), colorMix = ColorMix())
            EditorTab.FILTER -> r.copy(filter = FilterId.NONE, filterIntensity = 1f)
            EditorTab.RESIZE -> r.copy(resizeWidth = null, resizeHeight = null)
            EditorTab.MARKUP -> r.copy(layers = emptyList()).also { selectedLayerId = null }
            EditorTab.FRAME -> r.copy(frame = null)
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

    fun adjust(tool: AdjustTool, v: Float, commit: Boolean) = update(recipe.copy(adjust = tool.set(recipe.adjust, v)), commit)

    fun valueOf(tool: AdjustTool): Float = tool.get(recipe.adjust)

    /** Gruppe zurücksetzen (z. B. alle Licht-Regler). */
    fun resetGroup(g: AdjustGroup) = update(recipe.copy(adjust = AdjustTool.of(g).fold(recipe.adjust) { a, t -> t.set(a, 0f) }))

    // Eigene Vorlagen (nur Einstellungen, keine Bilder)

    /** Sofort sichern unter „Mein Look“, „Mein Look 2“, … (umbenennen per langem Drücken). */
    fun savePreset() = viewModelScope.launch {
        val base = app.string(R.string.edit_preset_default_name)
        val names = app.presets.items.value.map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in names }
        app.presets.add(EditPreset.of(name, recipe))
    }
    fun renamePreset(p: EditPreset, name: String) = viewModelScope.launch {
        val n = name.trim(); if (n.isNotEmpty()) app.presets.replace(p.copy(name = n.take(40)))
    }
    fun applyPreset(p: EditPreset) = update(p.applyTo(recipe))
    fun deletePreset(p: EditPreset) = viewModelScope.launch { app.presets.remove(p.id) }

    fun setFilter(id: FilterId) = update(recipe.copy(filter = id, filterIntensity = if (id == recipe.filter) recipe.filterIntensity else 1f))
    fun filterIntensity(v: Float, commit: Boolean) = update(recipe.copy(filterIntensity = v.coerceIn(0f, 1f)), commit)
    /** „Nur Zuschnitt speichern“ (ohne Pro): Regler, Filter und Größe zurücknehmen, Geometrie bleibt. */
    fun dropProTools() = update(recipe.copy(adjust = Adjustments(), filter = FilterId.NONE, filterIntensity = 1f, resizeWidth = null, resizeHeight = null,
        colorMix = ColorMix(), locals = emptyList(), frame = null))
    // Markieren (Ebenen) – kostenlos; nie eine Entscheidung

    var markupTool by mutableStateOf(MarkupTool.SELECT)
    var selectedLayerId by mutableStateOf<String?>(null)
    /** Text, der gerade im Blatt bearbeitet wird. */
    var editingTextId by mutableStateOf<String?>(null)
    var showStickers by mutableStateOf(false)
    var brush by mutableStateOf(BrushKind.PEN)
    var drawWidth by mutableStateOf(0.012f)
    var drawColor by mutableStateOf(0xFFFF3B30.toInt())
    var shapeKind by mutableStateOf(ShapeKind.ARROW)
    var shapeFilled by mutableStateOf(false)
    var redactMode by mutableStateOf(RedactMode.SOLID)
    var redactBrush by mutableStateOf(false)
    var redactStrength by mutableStateOf(0.6f)

    val layers: List<Layer> get() = recipe.layers
    val selectedLayer: Layer? get() = selectedLayerId?.let { id -> recipe.layers.firstOrNull { it.id == id } }

    private fun newId() = java.util.UUID.randomUUID().toString()
    private fun setLayers(list: List<Layer>, commit: Boolean = true) = update(recipe.copy(layers = list), commit)

    fun addText() {
        val l = TextLayer(newId(), app.string(R.string.edit_text_default), color = 0xFFFFFFFF.toInt())
        setLayers(recipe.layers + l); selectedLayerId = l.id; editingTextId = l.id; markupTool = MarkupTool.SELECT
    }

    fun addSticker(kind: StickerKind, value: String = "") {
        val v = if (kind == StickerKind.DATE) value.ifEmpty { stickerDate() } else value
        val l = StickerLayer(newId(), kind, v, color = if (kind == StickerKind.DATE) 0xFFFF8A00.toInt() else drawColor)
        setLayers(recipe.layers + l); selectedLayerId = l.id; showStickers = false; markupTool = MarkupTool.SELECT
    }

    /** Datum des Fotos (Aufnahme), sonst heute – nur lokale Formatierung. */
    fun stickerDate(): String {
        val d = asset?.creationDate?.let { java.util.Date(it.toEpochMilli()) } ?: java.util.Date()
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(d)
    }

    /** Ebene ändern; [commit] = Schritt fürs Rückgängig (beim Loslassen). */
    fun updateLayer(l: Layer, commit: Boolean) = setLayers(LayerOps.replace(recipe.layers, l), commit)

    fun deleteSelected() { selectedLayerId?.let { setLayers(LayerOps.remove(recipe.layers, it)) }; selectedLayerId = null }
    fun duplicateSelected() { selectedLayerId?.let { id -> val n = newId(); setLayers(LayerOps.duplicate(recipe.layers, id, n)); selectedLayerId = n } }
    fun selectedToFront() { selectedLayerId?.let { setLayers(LayerOps.toFront(recipe.layers, it)) } }
    fun selectedToBack() { selectedLayerId?.let { setLayers(LayerOps.toBack(recipe.layers, it)) } }

    /** Strich fertig: an die oberste Zeichnung anhängen (der Radierer wirkt nur dort), sonst neue Zeichenebene. */
    fun addStroke(points: List<NormPoint>) {
        if (points.isEmpty()) return
        val st = DrawStroke(points, drawWidth, drawColor, brush)
        val top = recipe.layers.lastOrNull()
        val next = if (top is DrawLayer) LayerOps.replace(recipe.layers, top.copy(strokes = top.strokes + st))
            else if (brush == BrushKind.ERASER) return
            else recipe.layers + DrawLayer(newId(), listOf(st))
        setLayers(next)
    }

    fun addShape(a: NormPoint, b: NormPoint) {
        if (kotlin.math.hypot(b.x - a.x, b.y - a.y) < 0.01f) return
        val l = ShapeLayer(newId(), shapeKind, a, b, drawColor, drawWidth, shapeFilled)
        setLayers(recipe.layers + l); selectedLayerId = l.id
    }

    fun addRedactRect(a: NormPoint, b: NormPoint) {
        val r = NormRect(minOf(a.x, b.x).coerceIn(0f, 1f), minOf(a.y, b.y).coerceIn(0f, 1f), maxOf(a.x, b.x).coerceIn(0f, 1f), maxOf(a.y, b.y).coerceIn(0f, 1f))
        if (r.width < 0.01f || r.height < 0.01f) return
        setLayers(recipe.layers + RedactLayer(newId(), redactMode, rect = r, strength = redactStrength))
    }

    fun addRedactStroke(points: List<NormPoint>) {
        if (points.isEmpty()) return
        setLayers(recipe.layers + RedactLayer(newId(), redactMode, strokes = listOf(BrushStroke(points, radius = drawWidth * 2f, hardness = 1f)), strength = redactStrength))
    }

    // Rahmen (Pro)
    fun setFrame(f: FrameStyle?, commit: Boolean = true) = update(recipe.copy(frame = f), commit)

    fun resize(size: PxSize?) = update(recipe.copy(resizeWidth = size?.width, resizeHeight = size?.height))

    // Video

    fun setTrim(start: Long, end: Long) { trim = TrimRange.clamp(start, end, durationMs) }

    // Vorschau

    private fun rerender(fast: Boolean = false) {
        val src = source ?: return
        val r = recipe
        val cropTab = tab == EditorTab.CROP
        val clip = showClipping
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            delay(if (fast) 8 else 24) // Ziehen bündeln
            val base = if (fast) proxy ?: src.preview else src.preview
            val previewRecipe = r.copy(resizeWidth = null, resizeHeight = null)
            if (cropTab) {
                val frame = app.editEngine.render(base, previewRecipe.copy(crop = NormRect.FULL), colorAndSharpen = false)
                app.editEngine.applyPixels(frame, previewRecipe, pointOnly = true)
                withContext(Dispatchers.Main) { framePreview = frame }
            } else {
                // Ebenen/Rahmen ändern das Foto darunter nicht: Grundbild wiederverwenden (scharf auch beim Ziehen einer Ebene).
                val key = previewRecipe.copy(layers = emptyList(), frame = null)
                val cached = baseCache
                val baseRes = if (cached != null && cached.first == key && (!cached.third || fast)) cached.second
                    else app.editEngine.render(base, key, colorAndSharpen = true).also { baseCache = Triple(key, it, fast) }
                val res = if (!r.hasLayers && !r.hasFrame) baseRes else {
                    val c = baseRes.copy(Bitmap.Config.ARGB_8888, true)
                    LayerRenderer.apply(c, r.layers, c.width, c.height)
                    LayerRenderer.frame(c, r.frame)
                }
                val px = IntArray(res.width * res.height).also { res.getPixels(it, 0, res.width, 0, 0, res.width, res.height) }
                val hist = Histogram.of(px, step = 2)
                val overlay = if (clip) clippingOverlay(px, res.width, res.height) else null
                withContext(Dispatchers.Main) { resultPreview = res; baseResult = baseRes; histogram = hist; clipOverlay = overlay }
            }
        }
    }

    /** Rot = abgeschnittene Lichter, Blau = abgeschnittene Tiefen (reine Anzeige). */
    private fun clippingOverlay(px: IntArray, w: Int, h: Int): Bitmap {
        val out = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]; val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            out[i] = when {
                r >= 254 || g >= 254 || b >= 254 -> 0xD0FF3030.toInt()
                r <= 1 && g <= 1 && b <= 1 -> 0xD03070FF.toInt()
                else -> 0
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 100-%-Ansicht: sichtbaren Ausschnitt in voller Qualität nachrechnen (nach kurzer Ruhe). */
    fun requestDetail(view: NormRect, tw: Int, th: Int) {
        val a = asset ?: return; val full = fullSize ?: return
        val r = recipe
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            delay(220)
            val b = app.editEngine.renderDetail(a.uri, r.copy(resizeWidth = null, resizeHeight = null), full, view, tw.coerceIn(16, 2048), th.coerceIn(16, 2048))
            if (b != null && r == recipe) detail = DetailTile(view, b)
        }
    }

    fun clearDetail() { detailJob?.cancel(); detail = null }

    // Speichern

    /** Zielordner der Kopie (`null` = wie das Original). Keine Entscheidung, nur Ablage. */
    var targetFolder by mutableStateOf<String?>(null)
    /** Fertig exportiert zum Teilen (Bildschirm öffnet den System-Dialog). */
    var shareUri by mutableStateOf<android.net.Uri?>(null)

    fun share(removeLocation: Boolean) {
        val a = asset ?: return
        if (saving || needsPro || isVideo) return
        saving = true; error = null
        saveJob = viewModelScope.launch {
            try {
                shareUri = EditSaver.sharePhoto(app, a, recipe, source?.full ?: return@launch, removeLocation)
            } catch (e: EditError.TooLarge) {
                error = app.string(R.string.edit_err_too_large)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = app.string(R.string.edit_err_save)
            } finally { saving = false }
        }
    }

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
                else EditSaver.savePhoto(app, a, recipe, source?.full ?: return@launch, mode, removeLocation, targetFolder)
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
