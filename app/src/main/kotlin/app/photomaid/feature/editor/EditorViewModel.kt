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
import app.photomaid.core.studio.ExportProfile
import app.photomaid.core.studio.TimedLayer
import app.photomaid.core.studio.VSize
import app.photomaid.core.studio.VideoClip
import app.photomaid.core.studio.VideoLayout
import app.photomaid.core.studio.VideoPlan
import app.photomaid.core.studio.VideoProject
import app.photomaid.core.studio.VideoRules
import app.photomaid.core.studio.TimeRange
import app.photomaid.media.VideoInfo
import app.photomaid.core.edit.ColorBand
import app.photomaid.core.edit.LocalAdjustment
import app.photomaid.core.edit.Mask
import app.photomaid.core.edit.MaskGrid
import app.photomaid.core.edit.PerspectiveMath
import app.photomaid.core.edit.Quad
import app.photomaid.core.edit.RedEyeSpot

/** Ausschnitt der 100-%-Ansicht (normiert auf das Ausgabebild) und sein Bild. */
data class DetailTile(val view: NormRect, val bitmap: Bitmap)

enum class EditorTab { CROP, ADJUST, FILTER, RETOUCH, MARKUP, FRAME, RESIZE, TRIM, SPEED, AUDIO, FORMAT }

/** Werkzeug im Reiter „Markieren“. */
enum class MarkupTool { SELECT, DRAW, SHAPE, REDACT }

/**
 * Zustand des Editors. Bearbeitet wird ein Rezept ([EditRecipe]) – nicht destruktiv, mit eigenem Rückgängig/Wiederholen.
 * Das Original wird erst beim Speichern gelesen und nie überschrieben (Kopie oder Ersetzen über den Android-Papierkorb).
 */
class EditorViewModel(private val app: AppModel, val assetId: String) : ViewModel() {
    companion object { const val MAX_LOCALS = 8 }
    var asset by mutableStateOf<MediaAsset?>(null); private set
    var source by mutableStateOf<EditSource?>(null); private set
    var loadFailed by mutableStateOf(false); private set
    val isVideo: Boolean get() = asset?.kind == MediaKind.VIDEO

    var recipe by mutableStateOf(EditRecipe()); private set
    private var committed = EditRecipe()
    /** Ein Rückgängig-Schritt: Rezept und (bei Videos) die Video-Einstellungen. */
    private data class Snapshot(val recipe: EditRecipe, val video: VideoProject?)
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    private var tabState by mutableStateOf(EditorTab.CROP)
    /** Reiterwechsel rechnet die passende Vorschau (Zuschneiden: ganzer Rahmen, sonst Ergebnis). */
    var tab: EditorTab
        get() = tabState
        set(v) { if (v != tabState) { tabState = v; rerender(); if (isVideo && v == EditorTab.MARKUP) loadPoster() } }
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
    /** Vergleich: Original anzeigen, solange gehalten (Taste in der Hilfsleiste oder Finger auf dem Bild). */
    var comparing by mutableStateOf(false)
    /** Aktuelle Zoomstufe der Bühne in % (bezogen auf 1 Bildpunkt = 1 Bildschirmpunkt), `null` = eingepasst. */
    var zoomPercent by mutableStateOf<Int?>(null)
    private var clipState by mutableStateOf(false)
    var showClipping: Boolean
        get() = clipState
        set(v) { clipState = v; rerender() }
    var clipOverlay by mutableStateOf<Bitmap?>(null); private set

    // 100-%-Ansicht
    var detail by mutableStateOf<DetailTile?>(null); private set
    private var detailJob: Job? = null

    // Video
    /** Video: Schnitte, Tempo, Ton, Format, Zeitfenster der Texte (Farbe/Drehung/Texte stehen im Rezept). */
    var video by mutableStateOf<VideoProject?>(null); private set
    private var committedVideo: VideoProject? = null
    private var videoBase: VideoProject? = null
    var videoInfo by mutableStateOf<VideoInfo?>(null); private set
    val trim: TrimRange? get() = video?.clips?.firstOrNull()?.let { TrimRange(it.trimStartMs, it.trimEndMs) }
    val clip: VideoClip? get() = video?.clips?.firstOrNull()
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

    val hasChanges: Boolean get() = if (isVideo) videoProject() != videoBase?.let { withRecipe(it, EditRecipe()) } else !recipe.isIdentity
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
                val dur = ((a.duration) * 1000).toLong().coerceAtLeast(TrimRange.MIN_MS)
                val base = VideoProject(a.id, listOf(VideoClip("c", a.id, sourceDurationMs = dur)))
                video = base; committedVideo = base; videoBase = base
                tab = EditorTab.TRIM
                videoInfo = withContext(Dispatchers.IO) { app.videoExporter.info(a.uri) }
                // Filter-Vorschaubilder aus einem Bild des Videos (gleiche Pipeline).
                val thumb = app.videoExporter.frame(a.uri, minOf(1000L, dur / 2), 220)
                filterThumb = thumb
                if (thumb != null) filterThumbs = withContext(Dispatchers.Default) {
                    FilterId.entries.associateWith { f -> thumb.copy(Bitmap.Config.ARGB_8888, true).also { app.editEngine.applyPixels(it, EditRecipe(filter = f)) } }
                }
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
        if (commit && (next != committed || video != committedVideo)) {
            undoStack.addLast(Snapshot(committed, committedVideo)); if (undoStack.size > 100) undoStack.removeFirst()
            redoStack.clear(); committed = next; committedVideo = video
        }
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
        detail = null
        rerender(fast = !commit)
    }

    fun commit() = update(recipe, commit = true)

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(Snapshot(committed, committedVideo)); restore(prev)
        canUndo = undoStack.isNotEmpty(); canRedo = true; rerender()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(Snapshot(committed, committedVideo)); restore(next)
        canUndo = true; canRedo = redoStack.isNotEmpty(); rerender()
    }

    private fun restore(s: Snapshot) { committed = s.recipe; recipe = s.recipe; committedVideo = s.video; video = s.video; detail = null }

    fun resetTab() {
        val r = recipe
        update(when (tab) {
            EditorTab.CROP -> r.copy(quarterTurns = 0, flipH = false, flipV = false, straighten = 0f, crop = NormRect.FULL, resizeWidth = null, resizeHeight = null,
                perspective = null).also { keyV = 0f; keyH = 0f }
            EditorTab.RETOUCH -> r.copy(locals = emptyList(), redEyes = emptyList()).also { selectedLocal = null }
            EditorTab.ADJUST -> r.copy(adjust = Adjustments(), colorMix = ColorMix())
            EditorTab.FILTER -> r.copy(filter = FilterId.NONE, filterIntensity = 1f)
            EditorTab.RESIZE -> r.copy(resizeWidth = null, resizeHeight = null)
            EditorTab.MARKUP -> r.copy(layers = emptyList()).also { markup.selectedLayerId = null }
            EditorTab.FRAME -> r.copy(frame = null)
            EditorTab.TRIM -> r.also { clip?.let { c -> video = video?.withClips(listOf(c.copy(trimStartMs = 0, trimEndMs = c.sourceDurationMs, cuts = emptyList()))) } }
            EditorTab.SPEED -> r.also { clip?.let { c -> video = video?.withClips(listOf(c.copy(speed = 1f))) } }
            EditorTab.AUDIO -> r.also { clip?.let { c -> video = video?.withClips(listOf(c.copy(volume = 1f, fadeInMs = 0, fadeOutMs = 0)))?.copy(removeAudio = false) } }
            EditorTab.FORMAT -> r.copy(quarterTurns = 0, flipH = false).also { video = video?.copy(aspect = null, fill = false) }
        }.also { if (tab == EditorTab.CROP) aspect = AspectPreset.FREE })
    }

    // Zuschneiden

    fun rotate() = update(recipe.rotatedClockwise().let { r -> currentRatio(r)?.let { r.copy(crop = CropMath.fitAspect(r.crop, it, frameOf(r))) } ?: r })
    fun flipHorizontal() = update(recipe.copy(flipH = !recipe.flipH, crop = recipe.crop.flippedH(), perspective = recipe.perspective?.flippedH()))
    fun flipVertical() = update(recipe.copy(flipV = !recipe.flipV, crop = recipe.crop.flippedV(), perspective = recipe.perspective?.flippedV()))

    // Perspektive (kostenlos, wie Zuschneiden): vier Ecken im Grundrahmen

    private var perspectiveState by mutableStateOf(false)
    /** Im Reiter „Zuschneiden“: Ecken der Perspektive bearbeiten statt Ausschnitt. */
    var perspectiveMode: Boolean
        get() = perspectiveState
        set(v) { if (v != perspectiveState) { perspectiveState = v; rerender() } }
    /** Schnellkorrektur (stürzende Linien); Eckenziehen ändert das Viereck danach frei. */
    var keyV by mutableStateOf(0f); private set
    var keyH by mutableStateOf(0f); private set
    val quad: Quad get() = recipe.perspective ?: Quad.FULL

    fun keystone(v: Float, h: Float, commit: Boolean) {
        keyV = v; keyH = h
        val q = PerspectiveMath.keystone(v, h)
        update(recipe.copy(perspective = if (q.isIdentity) null else q), commit)
    }

    fun moveCorner(i: Int, x: Float, y: Float, commit: Boolean) {
        val q = PerspectiveMath.moveCorner(quad, i, x, y)
        update(recipe.copy(perspective = if (q.isIdentity) null else q), commit)
    }

    /** Bedienungshilfe: Ecke [i] schrittweise nach innen/außen. */
    fun nudgeCorner(i: Int, inward: Boolean) {
        val p = quad.corners[i]; val d = if (inward) 0.02f else -0.02f
        val sx = if (i == 0 || i == 3) d else -d; val sy = if (i <= 1) d else -d
        moveCorner(i, p.x + sx, p.y + sy, true)
    }

    fun resetPerspective() { keyV = 0f; keyH = 0f; update(recipe.copy(perspective = null)) }
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
    fun resetGroup(g: AdjustGroup) = if (g == AdjustGroup.MIX) update(recipe.copy(colorMix = ColorMix()))
        else update(recipe.copy(adjust = AdjustTool.of(g).fold(recipe.adjust) { a, t -> t.set(a, 0f) }))

    // Farbmischer (selektive Farben)
    var mixBand by mutableStateOf(ColorBand.RED)
    fun mixValue(b: ColorBand, f: MixField): Float = recipe.colorMix[b].let { when (f) { MixField.HUE -> it.hue; MixField.SATURATION -> it.saturation; MixField.LUMINANCE -> it.luminance } }
    fun mix(f: MixField, v: Float, commit: Boolean) {
        val cur = recipe.colorMix[mixBand]; val x = v.coerceIn(-100f, 100f)
        val next = when (f) { MixField.HUE -> cur.copy(hue = x); MixField.SATURATION -> cur.copy(saturation = x); MixField.LUMINANCE -> cur.copy(luminance = x) }
        update(recipe.copy(colorMix = recipe.colorMix.with(mixBand, next)), commit)
    }

    // Retusche: Bereiche (lokale Anpassungen) und Rote Augen

    var retouchTool by mutableStateOf(RetouchTool.RADIAL)
    /** Gewählter Bereich (Index in `recipe.locals`). */
    var selectedLocal by mutableStateOf<Int?>(null)
    var localTool by mutableStateOf(AdjustTool.EXPOSURE)
    private var maskState by mutableStateOf(false)
    /** Maske rot einblenden (zeigt nur an). Ohne Wirkung wird sie automatisch gezeigt. */
    var showMask: Boolean
        get() = maskState
        set(v) { maskState = v; rerender() }
    var maskOverlay by mutableStateOf<Bitmap?>(null); private set
    var brushSize by mutableStateOf(0.05f)
    var brushErase by mutableStateOf(false)
    var redEyeSize by mutableStateOf(0.025f)
    val currentLocal: LocalAdjustment? get() = selectedLocal?.let { recipe.locals.getOrNull(it) }
    /** Maske anzeigen: eingeschaltet oder (noch) ohne Wirkung. */
    val maskVisible: Boolean get() = currentLocal?.let { showMask || it.adjust.pointNeutral } == true

    private fun setLocals(l: List<LocalAdjustment>, commit: Boolean) = update(recipe.copy(locals = l), commit)

    /** Neuer Bereich an [at] (normiert im Bild); Pinsel startet leer. */
    fun addLocal(kind: RetouchTool, at: NormPoint = NormPoint(0.5f, 0.5f)): Int? {
        val mask = when (kind) {
            RetouchTool.RADIAL -> Mask.Radial(at, 0.22f, 0.22f * aspectOfImage(), feather = 0.5f)
            RetouchTool.LINEAR -> Mask.Linear(NormPoint(at.x, (at.y - 0.15f).coerceIn(0f, 1f)), NormPoint(at.x, (at.y + 0.15f).coerceIn(0f, 1f)))
            RetouchTool.BRUSH -> Mask.Brush(emptyList())
            RetouchTool.RED_EYE -> return null
        }
        if (recipe.locals.size >= MAX_LOCALS) return null
        setLocals(recipe.locals + LocalAdjustment(mask), true)
        selectedLocal = recipe.locals.lastIndex
        return selectedLocal
    }

    /** Kreis rund halten: Höhe relativ zur Höhe = Breite relativ zur Breite × Seitenverhältnis. */
    private fun aspectOfImage(): Float = imageSize?.let { it.width.toFloat() / it.height } ?: 1f

    fun updateMask(i: Int, m: Mask, commit: Boolean) {
        val l = recipe.locals.toMutableList(); val cur = l.getOrNull(i) ?: return
        l[i] = cur.copy(mask = m); setLocals(l, commit)
    }

    fun localValue(t: AdjustTool): Float = currentLocal?.let { t.get(it.adjust) } ?: 0f
    fun adjustLocal(t: AdjustTool, v: Float, commit: Boolean) {
        val i = selectedLocal ?: return
        val l = recipe.locals.toMutableList(); val cur = l.getOrNull(i) ?: return
        l[i] = cur.copy(adjust = t.set(cur.adjust, v)); setLocals(l, commit)
    }

    fun removeLocal() {
        val i = selectedLocal ?: return
        setLocals(recipe.locals.filterIndexed { k, _ -> k != i }, true); selectedLocal = null
    }

    /** Pinselstrich anfangen/fortsetzen ([commit] = Finger losgelassen). */
    fun brush(points: List<NormPoint>, commit: Boolean) {
        val i = selectedLocal ?: addLocal(RetouchTool.BRUSH) ?: return
        val m = recipe.locals.getOrNull(i)?.mask as? Mask.Brush ?: return
        val stroke = BrushStroke(points, brushSize, 0.5f, brushErase)
        // Der letzte Strich wird beim Ziehen ersetzt (ein Strich je Fingerbewegung).
        val base = if (drawingStroke) m.strokes.dropLast(1) else m.strokes
        drawingStroke = !commit
        updateMask(i, Mask.Brush(base + stroke), commit)
    }
    private var drawingStroke = false

    fun addRedEye(at: NormPoint) {
        if (recipe.redEyes.size >= 12) return
        update(recipe.copy(redEyes = recipe.redEyes + RedEyeSpot(at, redEyeSize)))
    }
    fun chooseRetouchTool(t: RetouchTool) {
        retouchTool = t
        // Gewählter Bereich passt nicht zum Werkzeug → abwählen (Neu legt einen passenden an).
        if (t == RetouchTool.RED_EYE || currentLocal?.mask?.let { kindOf(it) } != t) selectedLocal = null
        rerender()
    }

    fun selectLocal(i: Int) {
        selectedLocal = i
        recipe.locals.getOrNull(i)?.mask?.let { retouchTool = kindOf(it) }
        rerender()
    }

    private fun kindOf(m: Mask) = when (m) { is Mask.Radial -> RetouchTool.RADIAL; is Mask.Linear -> RetouchTool.LINEAR; is Mask.Brush -> RetouchTool.BRUSH }

    /** Mitte des gewählten Kreises (aktueller Stand beim Ziehen). */
    fun radialCenter(): NormPoint = (currentLocal?.mask as? Mask.Radial)?.center ?: NormPoint(0.5f, 0.5f)

    /** Bedienungshilfe/Stufen: gewählten Bereich bzw. Pinsel größer/kleiner. */
    fun resizeSelected(k: Float) {
        val i = selectedLocal
        when (val m = currentLocal?.mask) {
            is Mask.Radial -> updateMask(i!!, m.copy(rx = (m.rx * k).coerceIn(0.02f, 1.5f), ry = (m.ry * k).coerceIn(0.02f, 1.5f)), true)
            is Mask.Linear -> {
                val cx = (m.start.x + m.end.x) / 2; val cy = (m.start.y + m.end.y) / 2
                fun sc(p: NormPoint) = NormPoint(cx + (p.x - cx) * k, cy + (p.y - cy) * k)
                updateMask(i!!, m.copy(start = sc(m.start), end = sc(m.end)), true)
            }
            else -> if (retouchTool == RetouchTool.RED_EYE) redEyeSize = (redEyeSize * k).coerceIn(0.01f, 0.08f) else brushSize = (brushSize * k).coerceIn(0.01f, 0.2f)
        }
    }

    /**
     * Tippen in der Retusche: Rote Augen setzen; sonst einen anderen Bereich wählen (Tipp auf seine Markierung) oder –
     * wenn keiner gewählt ist – einen neuen Bereich an dieser Stelle anlegen. Tipp ins Leere wählt ab.
     */
    fun tapRetouch(at: NormPoint, toScreen: (NormPoint) -> androidx.compose.ui.geometry.Offset, at0: androidx.compose.ui.geometry.Offset, touch: Float) {
        if (retouchTool == RetouchTool.RED_EYE) { addRedEye(at); return }
        val hit = recipe.locals.indices.firstOrNull { i ->
            val p = when (val m = recipe.locals[i].mask) {
                is Mask.Radial -> m.center
                is Mask.Linear -> NormPoint((m.start.x + m.end.x) / 2, (m.start.y + m.end.y) / 2)
                is Mask.Brush -> m.strokes.lastOrNull()?.points?.lastOrNull() ?: return@firstOrNull false
            }
            (toScreen(p) - at0).getDistance() <= touch
        }
        when {
            hit != null -> selectLocal(hit)
            selectedLocal != null -> { selectedLocal = null; rerender() }
            retouchTool != RetouchTool.BRUSH -> addLocal(retouchTool, at)
        }
    }

    fun removeLastRedEye() { if (recipe.redEyes.isNotEmpty()) update(recipe.copy(redEyes = recipe.redEyes.dropLast(1))) }

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
    val markup = MarkupController(layers = { recipe.layers }, setLayers = { l, c -> update(recipe.copy(layers = l), c) },
        defaultText = { app.string(R.string.edit_text_default) }, dateText = { stickerDate() })

    /** Datum des Fotos (Aufnahme), sonst heute – nur lokale Formatierung. */
    fun stickerDate(): String {
        val d = asset?.creationDate?.let { java.util.Date(it.toEpochMilli()) } ?: java.util.Date()
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(d)
    }

    // Rahmen (Pro)
    fun setFrame(f: FrameStyle?, commit: Boolean = true) = update(recipe.copy(frame = f), commit)

    fun resize(size: PxSize?) = update(recipe.copy(resizeWidth = size?.width, resizeHeight = size?.height))

    // Video

    fun setTrim(start: Long, end: Long) {
        val c = clip ?: return
        val t = TrimRange.clamp(start, end, durationMs)
        // Schnitte außerhalb des neuen Bereichs fallen weg.
        updateClip(c.copy(trimStartMs = t.startMs, trimEndMs = t.endMs, cuts = c.cuts.filter { it.startMs < t.endMs && it.endMs > t.startMs }), commit = false)
    }

    // Video-Einstellungen (alle mit Rückgängig)

    fun updateVideo(next: VideoProject, commit: Boolean = true) { video = next; update(recipe, commit) }
    fun updateClip(next: VideoClip, commit: Boolean = true) { video?.let { updateVideo(it.withClips(listOf(next)), commit) } }

    /** Bereich (Quellzeit) wirklich entfernen; überlappende Schnitte werden zusammengelegt, nie alles. */
    fun addCut(start: Long, end: Long): Boolean {
        val c = clip ?: return false
        val n = VideoRules.addCut(c, TimeRange(minOf(start, end), maxOf(start, end)))
        if (n == c) return false
        updateClip(n); return true
    }
    fun removeCut(i: Int) { val c = clip ?: return; updateClip(c.copy(cuts = c.cuts.filterIndexed { k, _ -> k != i })) }
    fun setSpeed(s: Float) { clip?.let { updateClip(it.copy(speed = s)) } }
    fun setVolume(v: Float, commit: Boolean) { clip?.let { updateClip(it.copy(volume = v.coerceIn(0f, 2f)), commit) } }
    fun setFades(inMs: Long, outMs: Long) { clip?.let { updateClip(it.copy(fadeInMs = inMs, fadeOutMs = outMs)) } }
    fun setRemoveAudio(on: Boolean) { video?.let { updateVideo(it.copy(removeAudio = on)) } }
    fun setVideoAspect(a: Float?) { video?.let { updateVideo(it.copy(aspect = a)) } }
    fun setVideoFill(fill: Boolean) { video?.let { updateVideo(it.copy(fill = fill)) } }
    fun setProfile(p: ExportProfile) { video?.let { updateVideo(it.copy(profile = p), commit = false) } }
    /** Zeitfenster eines Textes/Stickers (Ergebniszeit). */
    fun setLayerTime(id: String, startMs: Long, endMs: Long, commit: Boolean) {
        val v = video ?: return
        val l = recipe.layers.firstOrNull { it.id == id } ?: return
        val others = v.layers.filterNot { it.layer.id == id }
        updateVideo(v.copy(layers = others + TimedLayer(l, startMs.coerceAtLeast(0), endMs)), commit)
    }
    fun layerTime(id: String): Pair<Long, Long> {
        val total = clip?.outputDurationMs ?: 0L
        return video?.layers?.firstOrNull { it.layer.id == id }?.let { it.startMs to minOf(it.endMs, total) } ?: (0L to total)
    }

    /** Video-Projekt mit Farbe/Drehung/Texten aus dem Rezept (das, was exportiert wird). */
    fun videoProject(): VideoProject? = video?.let { withRecipe(it, recipe) }

    private fun withRecipe(v: VideoProject, r: EditRecipe): VideoProject {
        val c = v.clips.first().copy(adjust = r.adjust, filter = r.filter, filterIntensity = r.filterIntensity, colorMix = r.colorMix,
            quarterTurns = r.turns, flipH = r.flipH)
        val layers = r.layers.map { l -> v.layers.firstOrNull { it.layer.id == l.id }?.copy(layer = l) ?: TimedLayer(l) }
        return v.withClips(listOf(c)).copy(layers = layers)
    }

    /** Ausgabe-Layout (Größe nach Drehen, Seitenverhältnis, Profil). */
    val videoLayout: VideoLayout? get() {
        val p = videoProject() ?: return null
        val d = videoInfo?.display ?: asset?.let { VSize(maxOf(2, it.pixelWidth), maxOf(2, it.pixelHeight)) } ?: return null
        return VideoPlan.layout(d, p.clips.first(), p)
    }

    /** „ca. 48 MB“ – nur Schätzung aus Bitrate × Dauer, ausdrücklich so beschriftet. */
    fun estimateBytes(profile: ExportProfile): Long? {
        val p = videoProject()?.copy(profile = profile) ?: return null
        val d = videoInfo?.display ?: return null
        return VideoPlan.estimateBytes(p, VideoPlan.layout(d, p.clips.first(), p), videoInfo?.fps ?: 30f)
    }

    /** Bereich zum Entfernen (Quellzeit), solange er gewählt wird. */
    var cutDraft by mutableStateOf<TimeRange?>(null)
    fun startCut() {
        val t = trim ?: return
        val s0 = playheadMs.coerceIn(t.startMs, maxOf(t.startMs, t.endMs - 1000))
        cutDraft = TimeRange(s0, minOf(t.endMs, s0 + 2000))
    }
    fun moveCutDraft(start: Long, end: Long) {
        val t = trim ?: return
        val s0 = start.coerceIn(t.startMs, t.endMs - VideoRules.MIN_SEGMENT_MS); val e0 = end.coerceIn(s0 + VideoRules.MIN_SEGMENT_MS, t.endMs)
        cutDraft = TimeRange(s0, e0)
    }
    /** Entfernen bestätigen; `false`, wenn sonst nichts übrig bliebe. */
    fun confirmCut(): Boolean { val d = cutDraft ?: return false; val ok = addCut(d.startMs, d.endMs); if (ok) cutDraft = null; return ok }

    /** Sprung der Vorschau (Quellzeit), vom Bedienfeld angefordert. */
    var seekRequest by mutableStateOf<Long?>(null)

    /** Bild an der Abspielstelle wie im Ergebnis (Drehung, Farbe, Format) – Untergrund für Text/Sticker. */
    var videoPoster by mutableStateOf<Bitmap?>(null); private set
    private var posterJob: Job? = null
    fun loadPoster() {
        val a = asset ?: return; val layout = videoLayout ?: return
        val r = recipe.copy(layers = emptyList(), crop = NormRect.FULL)
        val at = playheadMs
        posterJob?.cancel()
        posterJob = viewModelScope.launch {
            val raw = app.videoExporter.frame(a.uri, at, 1080) ?: return@launch
            videoPoster = withContext(Dispatchers.Default) {
                val shaped = app.editEngine.render(raw, r, colorAndSharpen = true)
                val k = 1080f / maxOf(layout.output.width, layout.output.height)
                val w = maxOf(2, (layout.output.width * minOf(1f, k)).toInt()); val h = maxOf(2, (layout.output.height * minOf(1f, k)).toInt())
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(out); cv.drawColor(android.graphics.Color.BLACK)
                val sa = shaped.width.toFloat() / shaped.height; val da = w.toFloat() / h
                val (dw, dh) = if ((sa > da) != layout.fill) w.toFloat() to w / sa else h * sa to h.toFloat()
                cv.drawBitmap(shaped, null, android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2),
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                out
            }
        }
    }

    /** Hinweis nach Standbild/Ton (bleibt im Editor). */
    var notice by mutableStateOf<String?>(null)
    /** Export läuft im Hintergrund weiter; der Editor wird geschlossen. */
    var continuedInBackground by mutableStateOf(false); private set
    fun continueInBackground() { app.exports.background(); continuedInBackground = true }

    /** Gerade wird ein Standbild gespeichert (kurz, kein Hintergrund-Weiter). */
    var isFrameSave by mutableStateOf(false); private set
    fun saveFrame() {
        val a = asset ?: return; val p = videoProject() ?: return
        if (saving) return
        if (needsPro) { error = app.string(R.string.vid_pro_needed); return }
        saving = true; error = null; isFrameSave = true
        viewModelScope.launch {
            try {
                EditSaver.saveFrame(app, a, p, playheadMs)
                notice = app.string(R.string.vid_frame_saved)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { error = app.string(R.string.edit_err_save)
            } finally { saving = false; isFrameSave = false }
        }
    }

    fun saveAudio() {
        val a = asset ?: return; val p = videoProject() ?: return
        if (saving || app.exports.isBusy) return
        if (needsPro) { error = app.string(R.string.vid_pro_needed); return }
        val info = videoInfo ?: return
        saving = true; progress = null; error = null
        val job = app.exports.run(app.string(R.string.vid_audio_running)) { onP -> EditSaver.saveAudio(app, a, p, info, onP) }
        saveJob = viewModelScope.launch {
            val watch = launch { app.exports.state.collect { s -> progress = s?.progress } }
            try { val name = job.await(); notice = app.string(R.string.vid_audio_saved, name) }
            catch (e: kotlinx.coroutines.CancellationException) { if (job.isCancelled) Unit else throw e }
            catch (e: Exception) { error = app.string(R.string.vid_audio_failed) }
            finally { watch.cancel(); saving = false; progress = null }
        }
    }

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
                // Perspektive bearbeiten: Grundrahmen ohne Perspektive (Ecken liegen darauf); sonst entzerrter Rahmen.
                val frameRecipe = if (perspectiveMode) previewRecipe.copy(crop = NormRect.FULL, perspective = null) else previewRecipe.copy(crop = NormRect.FULL)
                val frame = app.editEngine.render(base, frameRecipe, colorAndSharpen = false)
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
                val maskOv = if (tab == EditorTab.RETOUCH && maskVisible) currentLocal?.let { maskOverlayOf(it.mask, baseRes.width, baseRes.height) } else null
                withContext(Dispatchers.Main) { resultPreview = res; baseResult = baseRes; histogram = hist; clipOverlay = overlay; maskOverlay = maskOv }
            }
        }
    }

    /** Maske als rote Fläche (Deckkraft = Wirkung), in reduzierter Größe – reine Anzeige. */
    private fun maskOverlayOf(m: Mask, w: Int, h: Int): Bitmap {
        val k = maxOf(1, maxOf(w, h) / 360); val ow = maxOf(1, w / k); val oh = maxOf(1, h / k)
        val grid = MaskGrid.of(m, w, h)
        val px = IntArray(ow * oh)
        for (y in 0 until oh) for (x in 0 until ow) {
            val a = (grid.sample((x + 0.5f) / ow, (y + 0.5f) / oh) * 150f).toInt().coerceIn(0, 150)
            px[y * ow + x] = (a shl 24) or 0xFF3B30
        }
        return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
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
                result = if (a.kind == MediaKind.VIDEO) {
                    val p = videoProject() ?: return@launch
                    val info = videoInfo ?: VideoInfo(VSize(maxOf(2, a.pixelWidth), maxOf(2, a.pixelHeight)), 30f, false, true, durationMs)
                    // Im App-Bereich: läuft weiter, wenn der Editor geschlossen wird (Vordergrunddienst mit Fortschritt).
                    val job = app.exports.run(app.string(R.string.vid_export_running)) { onP -> EditSaver.saveVideoProject(app, a, p, info, mode, onP) }
                    val watch = launch { app.exports.state.collect { s -> progress = s?.progress } }
                    try { job.await() } finally { watch.cancel() }
                }
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

    fun cancelSave() { if (isVideo) app.exports.cancel(); saveJob?.cancel(); saving = false; progress = null }
}
