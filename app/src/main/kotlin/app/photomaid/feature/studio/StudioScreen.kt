package app.photomaid.feature.studio

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import app.photomaid.core.studio.VideoRules
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoSizeSelectSmall
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material.icons.rounded.Gif
import app.photomaid.core.batch.GifPlan
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.studio.CollageProject
import app.photomaid.design.AppBackground
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassIconButton
import app.photomaid.design.LargeTitleHeader
import app.photomaid.design.ListRow
import app.photomaid.design.PM
import app.photomaid.design.RowDivider
import app.photomaid.design.RowDividerInset
import app.photomaid.design.RowGroup
import app.photomaid.design.SectionTitle
import app.photomaid.design.clickableRow
import app.photomaid.design.readableWidth
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.Routes
import app.photomaid.navigation.TabBarEndSpacer
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * Tab „Studio“: eigene Werke aus Fotos (Collage, Foto bearbeiten), Entwürfe und die Liste „Bearbeitet“. Nur
 * Einträge, die wirklich funktionieren; alles lokal.
 */
@Composable
fun StudioScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val access by app.photos.access.collectAsState()
    val drafts by app.drafts.items.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<Draft?>(null) }
    val pickCollage = stringResource(R.string.studio_free_limit)

    fun newCollage() = nav.openPicker(PickRequest(UUID.randomUUID().toString(), min = 2, max = CollageProject.MAX, title = R.string.studio_pick_collage,
        limitNote = if (app.capabilities.hasProToolsAccess) null else pickCollage) { ids ->
        nav.openCollage(CollageRequest(UUID.randomUUID().toString(), CollageProject.create(UUID.randomUUID().toString(), ids), fromDraft = false))
    })

    AppBackground(Modifier.testTag("studio")) {
        Column(Modifier.fillMaxSize().wrapContentWidth().readableWidth().fillMaxWidth().verticalScroll(rememberScrollState()).statusBarsPadding()
            .padding(horizontal = AppSpacing.pageMargin), verticalArrangement = Arrangement.spacedBy(AppSpacing.block)) {
            LargeTitleHeader(stringResource(R.string.tab_studio), subtitle = stringResource(R.string.studio_intro)) {
                GlassIconButton(Icons.Rounded.Settings, stringResource(R.string.settings), onClick = nav::openSettings)
            }
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.headerGap)) {
                SectionTitle(stringResource(R.string.studio_create_header))
                RowGroup {
                    ListRow(Icons.Rounded.Dashboard, stringResource(R.string.studio_collage), Modifier.testTag("studio.collage"), tint = c.accent,
                        tile = c.accent.copy(alpha = 0.13f), subtitle = stringResource(R.string.studio_collage_sub), enabled = access.canRead) { newCollage() }
                    RowDivider(RowDividerInset)
                    // P6: Video zusammenfügen (Videos + Fotos) und Diashow als Video (nur Fotos).
                    ListRow(Icons.Rounded.Movie, stringResource(R.string.movie_title), Modifier.testTag("studio.movie"), tint = c.cleanup,
                        tile = c.cleanup.copy(alpha = 0.13f), subtitle = stringResource(R.string.movie_studio_sub), enabled = access.canRead) {
                        nav.openPicker(PickRequest(UUID.randomUUID().toString(), photos = true, videos = true, min = 2, max = VideoRules.MAX_CLIPS, title = R.string.movie_pick, library = true) { ids ->
                            scope.launch { val a = withContext(Dispatchers.IO) { app.allPhotos.assets(ids) }; nav.openMovie(MovieRequest(UUID.randomUUID().toString(), movieFrom(a, false), false)) }
                        })
                    }
                    RowDivider(RowDividerInset)
                    ListRow(Icons.Rounded.Slideshow, stringResource(R.string.movie_title_slideshow), Modifier.testTag("studio.slideshow"), tint = c.keep,
                        tile = c.keep.copy(alpha = 0.13f), subtitle = stringResource(R.string.movie_slideshow_sub), enabled = access.canRead) {
                        nav.openPicker(PickRequest(UUID.randomUUID().toString(), photos = true, videos = false, min = 2, max = VideoRules.MAX_CLIPS, title = R.string.movie_pick_photos, library = true) { ids ->
                            scope.launch { val a = withContext(Dispatchers.IO) { app.allPhotos.assets(ids) }; nav.openMovie(MovieRequest(UUID.randomUUID().toString(), movieFrom(a, true), true)) }
                        })
                    }
                    RowDivider(RowDividerInset)
                    ListRow(Icons.Outlined.Edit, stringResource(R.string.studio_edit_photo), Modifier.testTag("studio.edit"), tint = c.earth,
                        tile = c.earth.copy(alpha = 0.13f), subtitle = stringResource(R.string.studio_edit_photo_sub), enabled = access.canRead) {
                        nav.openPicker(PickRequest(UUID.randomUUID().toString(), photos = true, videos = true, min = 1, max = 1, title = R.string.studio_pick_edit) { ids ->
                            ids.firstOrNull()?.let(nav::openEditor)
                        })
                    }
                }
            }
            // P7: Werkzeuge – immer neue Dateien, nie eine Entscheidung über die Quellen.
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.headerGap)) {
                SectionTitle(stringResource(R.string.tools_header))
                RowGroup {
                    val tools = listOf(
                        Triple(ToolKind.RESIZE, Icons.Rounded.PhotoSizeSelectSmall, R.string.tools_resize to R.string.tools_resize_sub),
                        Triple(ToolKind.CONVERT, Icons.Rounded.SwapHoriz, R.string.tools_convert to R.string.tools_convert_sub),
                        Triple(ToolKind.STRIP_LOCATION, Icons.Rounded.LocationOff, R.string.tools_location to R.string.tools_location_sub),
                        Triple(ToolKind.STITCH, Icons.Rounded.ViewDay, R.string.tools_stitch to R.string.tools_stitch_sub),
                        Triple(ToolKind.GIF, Icons.Rounded.Gif, R.string.tools_gif to R.string.tools_gif_sub),
                    )
                    tools.forEachIndexed { i, (kind, icon, texts) ->
                        if (i > 0) RowDivider(RowDividerInset)
                        ListRow(icon, stringResource(texts.first), Modifier.testTag("studio.tool.${kind.name}"), tint = c.accent,
                            tile = c.accent.copy(alpha = 0.13f), subtitle = stringResource(texts.second), enabled = access.canRead) {
                            val gif = kind == ToolKind.GIF
                            nav.openPicker(PickRequest(UUID.randomUUID().toString(), photos = true, videos = gif,
                                min = if (kind == ToolKind.STITCH) 2 else 1, max = if (gif) GifPlan.MAX_FRAMES else 500,
                                title = if (gif) R.string.tools_pick_gif else R.string.tools_pick, library = true) { ids ->
                                nav.openTool(ToolRequest(UUID.randomUUID().toString(), kind, ids))
                            })
                        }
                    }
                }
            }
            if (drafts.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.headerGap)) {
                SectionTitle(stringResource(R.string.studio_drafts_header))
                RowGroup(Modifier.testTag("studio.drafts")) {
                    drafts.forEachIndexed { i, d ->
                        if (i > 0) RowDivider(72.dp)
                        DraftRow(d, onOpen = {
                            d.collage?.let { p -> nav.openCollage(CollageRequest(UUID.randomUUID().toString(), p, fromDraft = true)) }
                        }, onDelete = { confirmDelete = d })
                    }
                }
                if (!app.capabilities.hasProToolsAccess) Text(stringResource(R.string.studio_drafts_free_note), style = AppTypography.footnote,
                    color = c.textSecondary, modifier = Modifier.padding(horizontal = AppSpacing.xxs))
            }
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.headerGap)) {
                SectionTitle(stringResource(R.string.studio_saved_header))
                RowGroup {
                    ListRow(Icons.Rounded.PhotoLibrary, stringResource(R.string.edits_title), Modifier.testTag("studio.edits"), tint = c.success,
                        tile = c.success.copy(alpha = 0.13f), subtitle = stringResource(R.string.studio_saved_sub), enabled = access.canRead) { nav.push(Routes.EDITS) }
                }
            }
            TabBarEndSpacer()
        }
    }

    confirmDelete?.let { d ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, modifier = Modifier.testTag("studio.draft.confirm"),
            title = { Text(stringResource(R.string.studio_draft_delete)) }, text = { Text(stringResource(R.string.studio_draft_delete_text)) },
            confirmButton = { TextButton({ confirmDelete = null; scope.launch { app.drafts.remove(d.id) } }, Modifier.testTag("studio.draft.delete.ok")) {
                Text(stringResource(R.string.edit_mk_delete), color = c.cleanup) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun DraftRow(d: Draft, onOpen: () -> Unit, onDelete: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val file = remember(d.id, d.updatedAt) { app.drafts.thumb(d.id) }
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickableRow(onClick = onOpen).padding(vertical = AppSpacing.xs).testTag("studio.draft"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(c.backgroundTint)) {
            if (file.exists()) AsyncImage(ImageRequest.Builder(LocalContext.current).data(file).memoryCacheKey("draft:${d.id}:${d.updatedAt}").build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Rounded.Dashboard, null, tint = c.textSecondary, modifier = Modifier.align(Alignment.Center))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(pluralStringResource(R.plurals.studio_draft_collage, d.count, d.count), style = AppTypography.body, color = c.textPrimary, maxLines = 1)
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(d.updatedAt)), style = AppTypography.footnote, color = c.textSecondary)
        }
        IconButton(onDelete, Modifier.testTag("studio.draft.delete")) { Icon(Icons.Rounded.Delete, stringResource(R.string.studio_draft_delete), tint = c.textSecondary) }
    }
}
