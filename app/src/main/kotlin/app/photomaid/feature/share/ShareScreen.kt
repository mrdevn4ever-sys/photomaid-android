package app.photomaid.feature.share

import app.photomaid.feature.document.PdfRequest
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.photomaid.AppModel
import app.photomaid.R
import app.photomaid.core.share.LibraryFingerprint
import app.photomaid.core.share.ShareAction
import app.photomaid.core.share.ShareActions
import app.photomaid.core.share.ShareIntake
import app.photomaid.core.share.ShareMatch
import app.photomaid.core.share.SharedItem
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.ListRow
import app.photomaid.design.ListSection
import app.photomaid.design.PM
import app.photomaid.design.RowDivider
import app.photomaid.design.ScreenScaffold
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.feature.common.ThumbnailView
import app.photomaid.feature.organize.AlbumPickerSheet
import app.photomaid.feature.workflow.WorkflowRequest
import app.photomaid.feature.workflow.WorkflowRunRequest
import app.photomaid.media.MediaAsset
import app.photomaid.media.MediaCategory
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.system.AppEntry
import app.photomaid.system.EntryRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

object ShareRequests { val map = mutableStateMapOf<String, EntryRequest.Share>() }

/**
 * Übergebene Dateien den Medien der Mediathek zuordnen: zuerst über die MediaStore-ID, sonst nur eindeutig über
 * Dateiname + Größe. Alles andere zählt als „nicht in der Mediathek“ – GaleriQ legt dafür keine Kopie an.
 */
suspend fun resolveShare(app: AppModel, items: List<SharedItem>): Pair<ShareIntake, List<MediaAsset>> = withContext(Dispatchers.IO) {
    val candidates = items.associate { it.uri to AppEntry.libraryCandidates(it.uri) }
    val direct = app.allPhotos.assets(candidates.values.flatten().distinct()).associateBy { it.id }
    val prints by lazy {
        MediaCategory.entries.flatMap { app.allPhotos.assets(it, null) }.distinctBy { it.id }
            .map { LibraryFingerprint(it.id, it.displayName, it.size.bytesOrNull) }
    }
    val intake = ShareIntake.of(items) { item ->
        candidates[item.uri].orEmpty().firstOrNull { it in direct }
            ?: if (item.name != null && item.size != null) ShareMatch.unique(item.name, item.size, prints) else null
    }
    val known = direct.toMutableMap()
    val missing = intake.matched.filter { it !in known }
    if (missing.isNotEmpty()) app.allPhotos.assets(missing).forEach { known[it.id] = it }
    intake to intake.matched.mapNotNull { known[it] }
}

@Composable
fun ShareScreen(req: EntryRequest.Share, nav: Nav, onClose: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val scope = rememberCoroutineScope()
    val access by app.allPhotos.access.collectAsState()
    var result by remember(req) { mutableStateOf<Pair<ShareIntake, List<MediaAsset>>?>(null) }
    var albumSheet by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(req, access.canRead) {
        val r = resolveShare(app, req.items)
        result = r
        // Direktes Teilen an einen Ablauf: gleich zur Vorschau „Das passiert“ (dort erst startet ein Tipp den Ablauf).
        val wf = req.workflowId?.let { app.workflows.get(it) }
        if (wf != null && r.second.isNotEmpty()) {
            onClose()
            nav.openWorkflowRun(WorkflowRunRequest(UUID.randomUUID().toString(), wf.id, r.second.map { it.id }))
        }
    }
    ScreenScaffold(stringResource(R.string.share_title), onBack = onClose, modifier = Modifier.testTag("share.screen")) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            val r = result
            if (r == null) {
                Box(Modifier.fillMaxWidth().padding(AppSpacing.xl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            val (intake, assets) = r
            if (assets.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.share_count, assets.size, assets.size), style = AppTypography.headline, color = c.textPrimary,
                    modifier = Modifier.testTag("share.count"))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    assets.take(12).forEach { ThumbnailView(it.id, size = 64.dp) }
                }
            } else {
                Text(stringResource(if (access.canRead) R.string.share_none else R.string.share_no_access), style = AppTypography.body,
                    color = c.textPrimary, modifier = Modifier.testTag("share.none"))
            }
            if (intake.unmatched > 0) Text(pluralStringResource(R.plurals.share_unmatched, intake.unmatched, intake.unmatched),
                style = AppTypography.footnote, color = c.textSecondary, modifier = Modifier.testTag("share.unmatched"))
            if (intake.rejected > 0) Text(pluralStringResource(R.plurals.share_rejected, intake.rejected, intake.rejected),
                style = AppTypography.footnote, color = c.textSecondary, modifier = Modifier.testTag("share.rejected"))
            if (intake.overflow > 0) Text(pluralStringResource(R.plurals.share_overflow, intake.overflow, intake.overflow),
                style = AppTypography.footnote, color = c.textSecondary)
            val actions = ShareActions.available(assets.size)
            if (actions.isNotEmpty()) ListSection(Modifier.testTag("share.actions"), footer = stringResource(R.string.share_never)) {
                actions.forEachIndexed { i, a ->
                    if (i > 0) RowDivider(AppSpacing.m)
                    when (a) {
                        ShareAction.WORKFLOW -> ListRow(Icons.Rounded.AutoMode, stringResource(R.string.share_workflow), Modifier.testTag("share.workflow")) {
                            nav.openWorkflows(WorkflowRequest(UUID.randomUUID().toString(), assets.map { it.id }))
                        }
                        ShareAction.EDIT -> ListRow(Icons.Rounded.Edit, stringResource(R.string.share_edit), Modifier.testTag("share.edit")) {
                            nav.openEditor(assets.first().id)
                        }
                        ShareAction.PDF -> ListRow(Icons.Rounded.PictureAsPdf, stringResource(R.string.pdf_title), Modifier.testTag("share.pdf")) {
                            nav.openPdf(PdfRequest(UUID.randomUUID().toString(), assets.map { it.id }))
                        }
                        ShareAction.ALBUM -> ListRow(Icons.Rounded.PhotoAlbum, stringResource(R.string.org_add_to_album), Modifier.testTag("share.album")) {
                            albumSheet = true
                        }
                        ShareAction.BASKET -> ListRow(Icons.Rounded.ShoppingBasket, stringResource(R.string.org_basket_add), Modifier.testTag("share.basket")) {
                            scope.launch {
                                val n = app.organize.addToBasket(assets)
                                message = if (n == 0) app.string(R.string.org_basket_already) else app.context.resources.getQuantityString(R.plurals.org_basket_added, n, n)
                            }
                        }
                    }
                }
            }
        }
    }
    if (albumSheet) result?.second?.let { chosen -> AlbumPickerSheet(chosen, onDismiss = { albumSheet = false }) { m -> albumSheet = false; message = m } }
    message?.let { m -> NoticeDialog(m) { message = null } }
}
