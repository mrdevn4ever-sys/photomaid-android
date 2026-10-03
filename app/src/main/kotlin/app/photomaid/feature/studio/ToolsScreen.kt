package app.photomaid.feature.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.photomaid.AppModel
import app.photomaid.R
import app.photomaid.core.batch.BatchOptions
import app.photomaid.core.batch.BatchRules
import app.photomaid.core.batch.BatchSummary
import app.photomaid.core.batch.BatchTool
import app.photomaid.core.batch.GifEncoder
import app.photomaid.core.batch.GifPlan
import app.photomaid.core.batch.StitchPlan
import app.photomaid.core.cleanup.OperationOutcome
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.edit.EditFormat
import app.photomaid.core.edit.EditKind
import app.photomaid.core.edit.EditNames
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.EditRecord
import app.photomaid.core.edit.PxSize
import app.photomaid.core.edit.ResizeRules
import app.photomaid.core.edit.SaveMode
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.MediaKind
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.design.ScreenScaffold
import app.photomaid.design.SizeText
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.feature.editor.Chip
import app.photomaid.feature.editor.ProHint
import app.photomaid.feature.editor.clock
import app.photomaid.media.EditSaveRequest
import app.photomaid.media.EditSaveResult
import app.photomaid.media.MediaAsset
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** Werkzeuge im Studio (P7). */
enum class ToolKind { RESIZE, CONVERT, STRIP_LOCATION, STITCH, GIF }

data class ToolRequest(val id: String, val kind: ToolKind, val assetIds: List<String>)

object ToolRequests { val map = mutableStateMapOf<String, ToolRequest>() }

private const val SOFTWARE = "PhotoMaid"

/**
 * Stapel-Werkzeuge, Screenshots zusammenfügen, GIF erstellen (P7). Immer neue Dateien; Originale bleiben, außer
 * „Originale danach in den Android-Papierkorb“ (eine Systembestätigung, Standard aus). Größen werden erst NACH dem
 * Schreiben gemessen; „frei“ heißt es nur, wenn Originale wirklich im Android-Papierkorb liegen. Free: Stapel bis
 * 10 Dateien, GIF mit Pro/Test. Ergebnis = „Behalten“; nie eine Entscheidung über die Quellen.
 */
@Composable
fun ToolsScreen(req: ToolRequest, nav: Nav, onClose: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val scope = rememberCoroutineScope()
    var assets by remember { mutableStateOf<List<MediaAsset>?>(null) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    // Optionen
    var longEdge by remember { mutableStateOf<Int?>(2048) }
    var quality by remember { mutableStateOf(BatchRules.QUALITIES[1]) }
    var format by remember { mutableStateOf(EditFormat.JPEG) }
    var removeLocation by remember { mutableStateOf(req.kind == ToolKind.STRIP_LOCATION) }
    var trashOriginals by remember { mutableStateOf(false) }
    var gifSize by remember { mutableStateOf(480) }
    var gifDelay by remember { mutableStateOf(1000) }
    var gifFill by remember { mutableStateOf(false) }
    var gifRange by remember { mutableStateOf(0f..3000f) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(req.assetIds) { assets = withContext(Dispatchers.IO) { app.allPhotos.assets(req.assetIds) } }
    val list = assets.orEmpty()
    val pro = app.capabilities.hasProToolsAccess
    // GIF: genau ein Video ODER Fotos (Videos in einer Fotoauswahl werden nicht verwendet).
    val video = req.kind == ToolKind.GIF && list.size == 1 && list[0].kind == MediaKind.VIDEO
    val gifPhotos = list.filter { it.kind != MediaKind.VIDEO }
    LaunchedEffect(list) { if (video) gifRange = 0f..(list[0].duration * 1000).toFloat().coerceAtMost(3000f) }
    val overLimit = !pro && req.kind in setOf(ToolKind.RESIZE, ToolKind.CONVERT, ToolKind.STRIP_LOCATION) && list.size > BatchRules.FREE_LIMIT
    val needsPro = req.kind == ToolKind.GIF && !pro
    // Vorschau für Zusammenfügen/GIF (kleine Auflösung, gleiche Rechnung wie das Ergebnis).
    LaunchedEffect(list, gifFill, req.kind) {
        if (list.isEmpty() || req.kind !in setOf(ToolKind.STITCH, ToolKind.GIF)) return@LaunchedEffect
        preview = withContext(Dispatchers.IO) {
            runCatching { if (req.kind == ToolKind.STITCH) stitch(app, list, previewWidth = 360) else gifFrames(app, if (video) list else gifPhotos, 240, gifFill, video, gifRange, 1).firstOrNull()?.first }.getOrNull()
        }
    }

    fun run() {
        if (needsPro) { nav.openPro(); return }
        running = true; progress = 0f
        val label = app.string(R.string.tools_running)
        val job = app.exports.run(label) { onP ->
            when (req.kind) {
                ToolKind.STITCH -> saveStitch(app, list).let { app.string(R.string.tools_stitch_saved) }
                ToolKind.GIF -> saveGif(app, if (video) list else gifPhotos, gifSize, gifDelay, gifFill, video, gifRange, onP).let { app.string(R.string.tools_gif_saved, SizeText.bytes(it)) }
                else -> {
                    val tool = when (req.kind) { ToolKind.RESIZE -> BatchTool.RESIZE; ToolKind.CONVERT -> BatchTool.CONVERT; else -> BatchTool.STRIP_LOCATION }
                    val o = BatchOptions(tool, longEdge = if (tool == BatchTool.RESIZE) longEdge else null, quality = quality, format = format,
                        removeLocation = removeLocation || tool == BatchTool.STRIP_LOCATION, trashOriginals = trashOriginals)
                    batchMessage(app, runBatch(app, if (pro) list else list.take(BatchRules.FREE_LIMIT), o, onP))
                }
            }
        }
        scope.launch {
            val watch = launch { app.exports.state.collect { s -> progress = s?.progress } }
            try { message = job.await(); done = true }
            catch (e: kotlinx.coroutines.CancellationException) { if (!job.isCancelled) throw e }
            catch (e: Exception) { message = app.string(R.string.tools_failed) }
            finally { watch.cancel(); running = false; progress = null }
        }
    }

    val title = stringResource(when (req.kind) { ToolKind.RESIZE -> R.string.tools_resize; ToolKind.CONVERT -> R.string.tools_convert; ToolKind.STRIP_LOCATION -> R.string.tools_location
        ToolKind.STITCH -> R.string.tools_stitch; ToolKind.GIF -> R.string.tools_gif })
    ScreenScaffold(title, onBack = { if (!running) onClose() }, modifier = Modifier.testTag("tools"), bottomBar = {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs), horizontalAlignment = Alignment.CenterHorizontally) {
            val pr = progress
            if (running) { if (pr != null && pr > 0f) LinearProgressIndicator(progress = { pr }, modifier = Modifier.fillMaxWidth()) else CircularProgressIndicator(Modifier.size(24.dp)) }
            ProminentButton(stringResource(if (needsPro) R.string.tools_pro_save else R.string.tools_start), Modifier.testTag("tools.start"),
                enabled = !running && list.isNotEmpty() && !(req.kind == ToolKind.STITCH && list.size < 2) && !(req.kind == ToolKind.GIF && !video && gifPhotos.size < 2), fillWidth = true, large = true) { run() }
        }
    }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Text(pluralStringResource(R.plurals.tools_selected, list.size, list.size), style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = c.textPrimary, modifier = Modifier.testTag("tools.count"))
            if (overLimit) Text(stringResource(R.string.tools_free_limit, BatchRules.FREE_LIMIT), style = AppTypography.footnote, color = c.textSecondary, modifier = Modifier.testTag("tools.limit"))
            if (overLimit || needsPro) ProHint(onPro = nav::openPro)
            when (req.kind) {
                ToolKind.RESIZE -> {
                    Label(R.string.tools_long_edge)
                    ChipLine { for (e in BatchRules.LONG_EDGES) Chip("$e px", longEdge == e, Modifier.testTag("tools.edge.$e")) { longEdge = e } }
                    Label(R.string.tools_quality)
                    ChipLine { for ((q, l) in BatchRules.QUALITIES.zip(listOf(R.string.tools_q_high, R.string.tools_q_balanced, R.string.tools_q_small)))
                        Chip(stringResource(l), quality == q, Modifier.testTag("tools.q.$q")) { quality = q } }
                    Toggle(R.string.tools_remove_location, removeLocation, "tools.loc") { removeLocation = it }
                }
                ToolKind.CONVERT -> {
                    Label(R.string.tools_target_format)
                    ChipLine { for (f in listOf(EditFormat.JPEG, EditFormat.PNG)) Chip(f.extension.uppercase(), format == f, Modifier.testTag("tools.fmt.${f.name}")) { format = f } }
                    Text(stringResource(R.string.tools_convert_note), style = AppTypography.footnote, color = c.textSecondary)
                    Toggle(R.string.tools_remove_location, removeLocation, "tools.loc") { removeLocation = it }
                }
                ToolKind.STRIP_LOCATION -> Text(stringResource(R.string.tools_location_note), style = AppTypography.footnote, color = c.textSecondary)
                ToolKind.STITCH -> Text(stringResource(R.string.tools_stitch_note), style = AppTypography.footnote, color = c.textSecondary)
                ToolKind.GIF -> {
                    Label(R.string.tools_gif_size)
                    ChipLine { for (sz in GifPlan.SIZES) Chip("$sz px", gifSize == sz, Modifier.testTag("tools.gifsize.$sz")) { gifSize = sz } }
                    if (video) {
                        val dur = (list[0].duration * 1000).toFloat().coerceAtLeast(500f)
                        Label(R.string.tools_gif_part)
                        Text("${clock(gifRange.start.roundToLong())} – ${clock(gifRange.endInclusive.roundToLong())}", style = AppTypography.footnote, color = c.textSecondary)
                        RangeSlider(gifRange, { r -> gifRange = r.start..r.endInclusive.coerceAtMost(r.start + GifPlan.MAX_VIDEO_MS) }, valueRange = 0f..dur,
                            modifier = Modifier.fillMaxWidth().testTag("tools.gifrange"),
                            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.textSecondary.copy(alpha = 0.3f)))
                    } else {
                        Label(R.string.tools_gif_delay)
                        ChipLine { for (d in listOf(500, 1000, 2000)) Chip(java.text.NumberFormat.getInstance().format(d / 1000.0) + " s", gifDelay == d, Modifier.testTag("tools.gifdelay.$d")) { gifDelay = d } }
                        ChipLine {
                            Chip(stringResource(R.string.tools_gif_fit), !gifFill, Modifier.testTag("tools.giffit")) { gifFill = false }
                            Chip(stringResource(R.string.tools_gif_fill), gifFill, Modifier.testTag("tools.giffill")) { gifFill = true }
                        }
                    }
                    Text(stringResource(R.string.tools_gif_note), style = AppTypography.footnote, color = c.textSecondary)
                }
            }
            if (req.kind in setOf(ToolKind.RESIZE, ToolKind.CONVERT, ToolKind.STRIP_LOCATION)) {
                Toggle(R.string.tools_trash_originals, trashOriginals, "tools.trash") { trashOriginals = it }
                Text(stringResource(R.string.tools_trash_note), style = AppTypography.footnote, color = c.textSecondary)
            }
            preview?.let { b ->
                Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(12.dp)).background(c.backgroundTint).testTag("tools.preview"), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    message?.let { m -> NoticeDialog(m) { message = null; if (done) onClose() } }
}

@Composable private fun Label(t: Int) = Text(stringResource(t), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = PM.colors.textSecondary)
@Composable private fun ChipLine(content: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) { content() }
@Composable private fun Toggle(label: Int, on: Boolean, tag: String, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = AppTypography.body, color = PM.colors.textPrimary, modifier = Modifier.weight(1f)); Switch(on, onChange, Modifier.testTag(tag))
    }

// ---- Ausführung ------------------------------------------------------------------------------------------------

/** Stapel: je Datei eine neue Datei (Ordner des Originals), Größen gemessen; Originale optional in einem Schritt in den Papierkorb. */
internal suspend fun runBatch(app: AppModel, list: List<MediaAsset>, o: BatchOptions, onProgress: (Float) -> Unit): BatchSummary {
    var sum = BatchSummary()
    val replaced = mutableListOf<MediaAsset>()
    val suffix = app.string(R.string.edit_copy_suffix)
    for ((i, a) in list.withIndex()) {
        onProgress(i.toFloat() / list.size)
        if (a.kind == MediaKind.VIDEO) { sum = sum.copy(skipped = sum.skipped + 1); continue }
        val hasLoc = withContext(Dispatchers.IO) { app.editEngine.hasLocation(a.uri) }
        if (!BatchRules.needsWork(o, a.pixelWidth, a.pixelHeight, a.mimeType, hasLoc)) { sum = sum.copy(skipped = sum.skipped + 1); continue }
        try {
            val full = PxSize(a.pixelWidth.coerceAtLeast(1), a.pixelHeight.coerceAtLeast(1))
            val recipe = o.longEdge?.let { e -> ResizeRules.forLongEdge(full, e).let { s -> EditRecipe(resizeWidth = s.width, resizeHeight = s.height) } } ?: EditRecipe()
            val fmt = when (o.tool) { BatchTool.CONVERT -> o.format; BatchTool.RESIZE -> if (a.mimeType.equals("image/png", true)) EditFormat.PNG else EditFormat.JPEG
                BatchTool.STRIP_LOCATION -> if (a.mimeType.equals("image/jpeg", true)) EditFormat.JPEG else EditFormat.forSource(a.mimeType) }
            // Nur Standort entfernen bei JPEG: verlustfrei (Bytes kopieren, nur GPS löschen).
            val quality = if (o.tool == BatchTool.STRIP_LOCATION && fmt == EditFormat.JPEG) null else o.quality
            val export = app.editEngine.export(a.uri, a.mimeType, recipe, full, o.removeLocation, SOFTWARE, formatOverride = fmt, quality = quality)
            try {
                val r = app.photos.saveEdited(EditSaveRequest(sourceId = a.id, file = export.file, mime = export.format.mime,
                    displayName = EditNames.copyName(a.displayName, suffix, export.format.extension), relativePath = a.relativePath,
                    dateTakenMs = a.creationDate?.toEpochMilli(), video = false, replace = false, favorite = false, replaceName = null))
                val saved = r as? EditSaveResult.Saved ?: throw IllegalStateException("save")
                runCatching { app.decide(ItemReference.photo(saved.newId), DecisionAction.Keep) }
                app.edits.add(EditRecord(saved.newId, a.id, SaveMode.COPY, false, System.currentTimeMillis(),
                    if (o.tool == BatchTool.CONVERT) EditKind.CONVERT else EditKind.COMPRESS))
                sum = sum.add((a.size as? KnownSize.Known)?.bytes, saved.bytes.takeIf { it > 0 })
                replaced += a
            } finally { export.file.delete() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { sum = sum.copy(failed = sum.failed + 1) }
    }
    if (o.trashOriginals && replaced.isNotEmpty()) {
        // Eine Bestätigung für alle Originale (Android-Papierkorb, wiederherstellbar). Zählt wie „Original ersetzen“ nicht zum Tageslimit.
        val outcome = app.photos.trash(replaced.map { it.id })
        val n = when (outcome) {
            OperationOutcome.Succeeded -> replaced.size
            is OperationOutcome.Partial -> outcome.removed.size
            else -> 0
        }
        sum = sum.copy(originalsTrashed = n)
    }
    onProgress(1f)
    return sum
}

private fun batchMessage(app: AppModel, s: BatchSummary): String {
    val res = app.context.resources
    val parts = mutableListOf(res.getQuantityString(R.plurals.tools_done, s.done, s.done))
    if (s.skipped > 0) parts += res.getQuantityString(R.plurals.tools_skipped, s.skipped, s.skipped)
    if (s.failed > 0) parts += res.getQuantityString(R.plurals.tools_failed_n, s.failed, s.failed)
    if (s.done > 0 && s.beforeBytes > 0) parts += app.string(R.string.tools_sizes, SizeText.bytes(s.beforeBytes), SizeText.bytes(s.afterBytes))
    if (s.unknownSizes > 0) parts += res.getQuantityString(R.plurals.tools_unknown, s.unknownSizes, s.unknownSizes)
    parts += when {
        s.freedLaterBytes > 0 -> app.string(R.string.tools_freed_later, SizeText.bytes(s.freedLaterBytes))
        s.originalsTrashed > 0 -> res.getQuantityString(R.plurals.tools_originals_trashed, s.originalsTrashed, s.originalsTrashed)
        else -> app.string(R.string.tools_originals_kept)
    }
    return parts.joinToString("\n")
}

/** Screenshots untereinander (gemeinsame Breite, proportional). [previewWidth] = kleine Vorschau. */
internal suspend fun stitch(app: AppModel, list: List<MediaAsset>, previewWidth: Int? = null): Bitmap = withContext(Dispatchers.IO) {
    val sizes = list.map { it.pixelWidth.coerceAtLeast(1) to it.pixelHeight.coerceAtLeast(1) }
    val (size0, places0) = StitchPlan.layout(sizes)
    val f = previewWidth?.let { it.toDouble() / size0.first } ?: 1.0
    val w = (size0.first * f).toInt().coerceAtLeast(1); val h = (size0.second * f).toInt().coerceAtLeast(1)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out); val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    for ((a, p) in list.zip(places0)) {
        val target = PxSize(maxOf(1, (p.width * f).toInt()), maxOf(1, (p.height * f).toInt()))
        val b = app.editEngine.decodeScaled(a.uri, target) ?: continue
        canvas.drawBitmap(b, null, Rect(0, (p.top * f).toInt(), w, ((p.top + p.height) * f).toInt()), paint)
        b.recycle()
    }
    out
}

private suspend fun saveStitch(app: AppModel, list: List<MediaAsset>) {
    val bmp = stitch(app, list)
    val f = File(app.context.cacheDir, "stitch_${System.nanoTime()}.png")
    withContext(Dispatchers.IO) { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle() }
    saveOwn(app, f, "image/png", "png", "Screenshots", EditKind.COLLAGE, list.size, video = false)
}

/** GIF-Bilder: Fotos (eingepasst/gefüllt) oder Bilder aus dem Videobereich (10 Bilder je Sekunde). */
internal suspend fun gifFrames(app: AppModel, list: List<MediaAsset>, maxEdge: Int, fill: Boolean, video: Boolean, range: ClosedFloatingPointRange<Float>, limit: Int = GifPlan.MAX_FRAMES):
    List<Pair<Bitmap, Int>> = withContext(Dispatchers.IO) {
    if (video) {
        val a = list[0]
        val times = GifPlan.videoTimes(range.start.roundToLong(), range.endInclusive.roundToLong(), 10).take(limit)
        return@withContext times.mapNotNull { t -> app.videoExporter.frame(a.uri, t, maxEdge)?.let { it to 100 } }
            .let { frames -> if (frames.isEmpty()) frames else { val (w, h) = frames[0].first.width to frames[0].first.height; frames.map { (b, d) -> fit(b, w, h, false) to d } } }
    }
    val first = list.first()
    val (w, h) = GifPlan.frame(first.pixelWidth.coerceAtLeast(1), first.pixelHeight.coerceAtLeast(1), maxEdge)
    list.take(limit).mapNotNull { a -> app.editEngine.decodeScaled(a.uri, PxSize(maxEdge, maxEdge))?.let { fit(it, w, h, fill) to 1000 } }
}

/** In den Rahmen einpassen (schwarze Ränder) oder füllen (Rand abgeschnitten) – nie verzerrt. */
private fun fit(b: Bitmap, w: Int, h: Int, fill: Boolean): Bitmap {
    if (b.width == w && b.height == h) return b
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val r = GifPlan.place(b.width, b.height, w, h, fill)
    Canvas(out).apply { drawColor(android.graphics.Color.BLACK); drawBitmap(b, null, Rect(r[0], r[1], r[2], r[3]), Paint(Paint.FILTER_BITMAP_FLAG)) }
    b.recycle()
    return out
}

private suspend fun saveGif(app: AppModel, list: List<MediaAsset>, size: Int, delay: Int, fill: Boolean, video: Boolean, range: ClosedFloatingPointRange<Float>, onP: (Float) -> Unit): Long {
    val frames = gifFrames(app, list, size, fill, video, range)
    if (frames.isEmpty()) throw IllegalStateException("frames")
    onP(0.4f)
    val w = frames[0].first.width; val h = frames[0].first.height
    val bytes = withContext(Dispatchers.Default) {
        GifEncoder.encode(w, h, frames.map { (b, d) -> IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h); b.recycle() }.let { GifEncoder.Frame(it, if (video) d else delay) } })
    }
    onP(0.9f)
    val f = File(app.context.cacheDir, "gif_${System.nanoTime()}.gif").apply { writeBytes(bytes) }
    saveOwn(app, f, "image/gif", "gif", "GIF", EditKind.GIF, list.size, video = false)
    return bytes.size.toLong()
}

private suspend fun saveOwn(app: AppModel, f: File, mime: String, ext: String, name: String, kind: EditKind, count: Int, video: Boolean) {
    try {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val r = app.photos.saveEdited(EditSaveRequest(sourceId = "", file = f, mime = mime, displayName = "PhotoMaid_${name}_$stamp.$ext",
            relativePath = "Pictures/PhotoMaid/", dateTakenMs = System.currentTimeMillis(), video = video, replace = false, favorite = false, replaceName = null))
        val saved = r as? EditSaveResult.Saved ?: throw IllegalStateException("save")
        runCatching { app.decide(ItemReference.photo(saved.newId), DecisionAction.Keep) }
        app.edits.add(EditRecord(saved.newId, "", SaveMode.COPY, video, System.currentTimeMillis(), kind, count))
    } finally { f.delete() }
}
