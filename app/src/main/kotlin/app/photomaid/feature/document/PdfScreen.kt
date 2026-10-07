package app.photomaid.feature.document

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.photomaid.AppModel
import app.photomaid.R
import app.photomaid.core.document.Caption
import app.photomaid.core.document.ImageFit
import app.photomaid.core.document.PageMargin
import app.photomaid.core.document.PageOrientation
import app.photomaid.core.document.PaperSize
import app.photomaid.core.document.PdfLayout
import app.photomaid.core.document.PdfOptions
import app.photomaid.core.document.PdfRules
import app.photomaid.core.document.PerPage
import app.photomaid.core.document.SourceSize
import app.photomaid.core.model.MediaKind
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.DateText
import app.photomaid.design.GlassIconButton
import app.photomaid.design.ListSection
import app.photomaid.design.MenuRowPicker
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.ProminentButton
import app.photomaid.design.RowDivider
import app.photomaid.design.ScreenScaffold
import app.photomaid.design.SegmentedPicker
import app.photomaid.design.SizeText
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.feature.common.ThumbnailView
import app.photomaid.feature.editor.ProHint
import app.photomaid.feature.studio.ShareCache
import app.photomaid.media.EditSaveResult
import app.photomaid.media.MediaAsset
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.system.MediaIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** PDF aus diesen Medien (Videos werden nicht übernommen). */
data class PdfRequest(val id: String, val assetIds: List<String>, val options: PdfOptions = PdfOptions())

object PdfRequests { val map = mutableStateMapOf<String, PdfRequest>() }

/** Ergebnis eines Durchgangs – gemessen, nichts geschätzt. */
sealed interface PdfOutcome {
    data class Saved(val bytes: Long, val pages: Int, val unreadable: Int) : PdfOutcome
    data class Shared(val pages: Int, val unreadable: Int) : PdfOutcome
    data object Failed : PdfOutcome
}

/**
 * Erstellt das PDF und speichert es unter `Documents/PhotoMaid/` oder bereitet es zum Teilen vor. Nie eine
 * Entscheidung über die Quellen, kein Ledger-Eintrag; das PDF ist kein Medium der Mediathek.
 */
suspend fun buildPdf(app: AppModel, assets: List<MediaAsset>, options: PdfOptions, name: String, share: Boolean, onProgress: (Float) -> Unit = {}): Pair<PdfOutcome, android.net.Uri?> {
    val sizes = app.pdf.sizes(assets)
    val pages = PdfLayout.pages(sizes, options)
    val file = withContext(Dispatchers.IO) { File.createTempFile("pdf", ".pdf", app.context.cacheDir) }
    return try {
        val r = app.pdf.export(assets, sizes, pages, captionFor(app, assets, options.caption), file, onProgress = onProgress)
        if (share) {
            val uri = withContext(Dispatchers.IO) { ShareCache.stage(app.context, file, name) }
            PdfOutcome.Shared(r.pages, r.unreadable) to uri
        } else when (val s = app.photos.saveDocument(file, name)) {
            is EditSaveResult.Saved -> PdfOutcome.Saved(s.bytes, r.pages, r.unreadable) to null
            is EditSaveResult.Failed -> PdfOutcome.Failed to null
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) {
        android.util.Log.w("PhotoMaid", "pdf failed: ${e.javaClass.simpleName}"); PdfOutcome.Failed to null
    } finally { withContext(Dispatchers.IO) { file.delete() } }
}

internal fun captionFor(app: AppModel, assets: List<MediaAsset>, c: Caption): (Int) -> String? = { i ->
    val a = assets[i]
    when (c) {
        Caption.NONE -> null
        Caption.NUMBER -> (i + 1).toString()
        Caption.NAME -> a.displayName
        Caption.DATE -> a.creationDate?.let { DateText.abbreviated(it) } ?: app.string(R.string.pdf_no_date)
    }
}

@Composable
fun PdfScreen(req: PdfRequest, nav: Nav, onClose: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val scope = rememberCoroutineScope()
    val pro = app.capabilities.hasProToolsAccess
    var assets by remember { mutableStateOf<List<MediaAsset>?>(null) }
    var videos by remember { mutableStateOf(0) }
    var capped by remember { mutableStateOf(false) }
    val order = remember { mutableStateListOf<String>() }
    var sizes by remember { mutableStateOf<Map<String, SourceSize>>(emptyMap()) }
    var options by remember { mutableStateOf(req.options) }
    var name by remember { mutableStateOf("PhotoMaid_${LocalDate.now()}") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(req) {
        val all = withContext(Dispatchers.IO) { app.allPhotos.assets(req.assetIds) }
        val photos = all.filter { it.kind != MediaKind.VIDEO }
        videos = all.size - photos.size
        capped = photos.size > PdfRules.PRO_MAX
        val kept = photos.take(PdfRules.PRO_MAX)
        sizes = kept.map { it.id }.zip(app.pdf.sizes(kept)).toMap()
        order.clear(); order.addAll(kept.map { it.id }); assets = kept
    }
    val byId = remember(assets) { assets.orEmpty().associateBy { it.id } }
    val ordered = order.mapNotNull { byId[it] }
    val pages = remember(ordered, options, sizes) { if (sizes.isEmpty()) emptyList() else PdfLayout.pages(ordered.map { sizes[it.id] ?: SourceSize(1, 1) }, options) }
    val needsPro = PdfRules.needsPro(options, ordered.size)

    // Vorschau: dieselbe Aufteilung und derselbe Zeichenweg wie der Export.
    val previews = remember { mutableStateMapOf<Int, Bitmap>() }
    val thumbs = remember { HashMap<String, Bitmap>() }
    LaunchedEffect(pages) {
        previews.clear()
        val cap = captionFor(app, ordered, options.caption)
        pages.take(MAX_PREVIEWS).forEachIndexed { i, p -> previews[i] = app.pdf.preview(p, ordered, ordered.map { sizes[it.id] ?: SourceSize(1, 1) }, cap, 480, thumbs) }
    }
    DisposableEffect(Unit) { onDispose { previews.clear(); thumbs.clear() } }

    fun run(share: Boolean) {
        if (needsPro && !pro) { nav.openPro(); return }
        val file = PdfRules.fileName(name, "PhotoMaid")
        progress = 0f
        scope.launch {
            val (r, uri) = buildPdf(app, ordered, options, file, share) { progress = it }
            progress = null
            message = when (r) {
                is PdfOutcome.Saved -> app.string(R.string.pdf_saved, SizeText.bytes(r.bytes)) + unreadable(app, r.unreadable)
                is PdfOutcome.Shared -> {
                    uri?.let { u -> runCatching { app.context.startActivity(MediaIntents.share(u, MediaKind.PHOTO, "application/pdf", app.string(R.string.share))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                    unreadable(app, r.unreadable).trim().ifEmpty { null }
                }
                PdfOutcome.Failed -> app.string(R.string.pdf_failed)
            }
        }
    }

    ScreenScaffold(stringResource(R.string.pdf_title), onBack = onClose, modifier = Modifier.testTag("pdf.screen")) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            if (assets == null) return@Column
            if (ordered.isEmpty()) { Text(stringResource(R.string.pdf_no_photos), style = AppTypography.body, color = c.textPrimary, modifier = Modifier.testTag("pdf.empty")); return@Column }
            Text(stringResource(R.string.pdf_summary, pluralStringResource(R.plurals.pdf_pages, pages.size, pages.size), pluralStringResource(R.plurals.pdf_photos, ordered.size, ordered.size)),
                style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.testTag("pdf.summary"))
            Row(Modifier.horizontalScroll(rememberScrollState()).testTag("pdf.previews"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                pages.take(MAX_PREVIEWS).forEachIndexed { i, p ->
                    val label = stringResource(R.string.pdf_page_of, i + 1, pages.size)
                    Box(Modifier.width(150.dp).aspectRatio((p.widthPt / p.heightPt).toFloat()).clip(RoundedCornerShape(4.dp)).background(Color.White)
                        .border(0.5.dp, c.textSecondary.copy(alpha = 0.3f), RoundedCornerShape(4.dp)).semantics { contentDescription = label }.testTag("pdf.page")) {
                        previews[i]?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth()) }
                    }
                }
            }
            if (videos > 0) Text(pluralStringResource(R.plurals.pdf_videos_skipped, videos, videos), style = AppTypography.footnote, color = c.textSecondary)
            if (capped) Text(stringResource(R.string.pdf_capped, PdfRules.PRO_MAX), style = AppTypography.footnote, color = c.textSecondary)

            Text(stringResource(R.string.pdf_per_page), style = AppTypography.subheadline, color = c.textSecondary)
            SegmentedPicker(PerPage.entries.map { it to it.count.toString() }, options.perPage, Modifier.testTag("pdf.perpage")) { options = options.copy(perPage = it) }
            SegmentedPicker(listOf(ImageFit.FIT to stringResource(R.string.pdf_fit), ImageFit.FILL to stringResource(R.string.pdf_fill)), options.fit,
                Modifier.testTag("pdf.fit")) { options = options.copy(fit = it) }
            ListSection {
                MenuRowPicker(stringResource(R.string.pdf_paper), listOf(PaperSize.A4 to "A4", PaperSize.A5 to "A5", PaperSize.LETTER to stringResource(R.string.pdf_paper_letter)),
                    options.paper) { options = options.copy(paper = it) }
                RowDivider(AppSpacing.m)
                MenuRowPicker(stringResource(R.string.pdf_orientation), listOf(PageOrientation.AUTO to stringResource(R.string.pdf_orient_auto),
                    PageOrientation.PORTRAIT to stringResource(R.string.pdf_orient_portrait), PageOrientation.LANDSCAPE to stringResource(R.string.pdf_orient_landscape)),
                    options.orientation) { options = options.copy(orientation = it) }
                RowDivider(AppSpacing.m)
                MenuRowPicker(stringResource(R.string.pdf_margin), listOf(PageMargin.NONE to stringResource(R.string.pdf_margin_none),
                    PageMargin.SMALL to stringResource(R.string.pdf_margin_small), PageMargin.NORMAL to stringResource(R.string.pdf_margin_normal)),
                    options.margin) { options = options.copy(margin = it) }
                RowDivider(AppSpacing.m)
                MenuRowPicker(stringResource(R.string.pdf_caption), listOf(Caption.NONE to stringResource(R.string.pdf_caption_none),
                    Caption.NUMBER to stringResource(R.string.pdf_caption_number), Caption.NAME to stringResource(R.string.pdf_caption_name),
                    Caption.DATE to stringResource(R.string.pdf_caption_date)), options.caption) { options = options.copy(caption = it) }
            }

            Text(stringResource(R.string.pdf_order), style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.semantics { heading() })
            ListSection(Modifier.testTag("pdf.order")) {
                ordered.forEachIndexed { i, a ->
                    if (i > 0) RowDivider(AppSpacing.m)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = AppSpacing.s), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                        Text("${i + 1}", style = AppTypography.footnote, color = c.textSecondary, modifier = Modifier.width(24.dp))
                        ThumbnailView(a.id, size = 40.dp)
                        Text(a.displayName ?: "", style = AppTypography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        GlassIconButton(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.pdf_move_up), Modifier.testTag("pdf.up"), quiet = true, enabled = i > 0) {
                            order.add(i - 1, order.removeAt(i))
                        }
                        GlassIconButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.pdf_move_down), Modifier.testTag("pdf.down"), quiet = true,
                            enabled = i < ordered.size - 1) { order.add(i + 1, order.removeAt(i)) }
                    }
                }
            }

            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth().testTag("pdf.name"), singleLine = true,
                label = { Text(stringResource(R.string.pdf_name)) }, shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            if (needsPro && !pro) ProHint(onPro = nav::openPro)
            Text(stringResource(R.string.pdf_privacy), style = AppTypography.footnote, color = c.textSecondary)
            progress?.let { p ->
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text(stringResource(R.string.pdf_running), style = AppTypography.footnote, color = c.textSecondary)
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(4.dp).testTag("pdf.progress"))
                }
            }
            ProminentButton(stringResource(R.string.pdf_save), Modifier.testTag("pdf.save"), fillWidth = true, enabled = progress == null) { run(share = false) }
            PlainButton(stringResource(R.string.pdf_share), Modifier.align(Alignment.CenterHorizontally).testTag("pdf.share"), enabled = progress == null) { run(share = true) }
        }
    }
    message?.let { m -> NoticeDialog(m) { message = null } }
}

private const val MAX_PREVIEWS = 12

private fun unreadable(app: AppModel, n: Int): String =
    if (n == 0) "" else "\n" + app.context.resources.getQuantityString(R.plurals.pdf_unreadable, n, n)
