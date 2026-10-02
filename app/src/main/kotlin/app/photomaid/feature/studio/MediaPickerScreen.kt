package app.photomaid.feature.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.model.MediaKind
import app.photomaid.core.organize.MediaFolder
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.EmptyState
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.design.ScreenScaffold
import app.photomaid.feature.editor.Chip
import app.photomaid.media.MediaAsset
import app.photomaid.media.MediaCategory
import app.photomaid.navigation.LocalApp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Auswahlauftrag: welche Medien, wie viele, was danach passiert (nur im Prozess, nie gespeichert). */
data class PickRequest(
    val id: String,
    val photos: Boolean = true,
    val videos: Boolean = false,
    val min: Int = 1,
    val max: Int = 20,
    val title: Int,
    /** Hinweis bei Grenze (z. B. „Free: bis 4 Fotos“), sonst `null`. */
    val limitNote: String? = null,
    /** Mediathek-Auswahl (z. B. „Neuer Ordner“): auch vom Aufräumen ausgenommene Ordner, ohne Ausgeblendetes. */
    val library: Boolean = false,
    val onDone: (List<String>) -> Unit,
)

object PickRequests { val map = mutableStateMapOf<String, PickRequest>() }

/**
 * Medienauswahl im Studio: Raster mit Reihenfolge-Nummern, Filter Fotos/Videos und Ordner. Wählen ist nie eine
 * Entscheidung (kein Ledger-Eintrag).
 */
@Composable
fun MediaPickerScreen(req: PickRequest, onClose: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val token by app.photos.changeToken.collectAsState()
    var all by remember { mutableStateOf<List<MediaAsset>?>(null) }
    var folders by remember { mutableStateOf<List<MediaFolder>>(emptyList()) }
    var folder by remember { mutableStateOf<String?>(null) }
    var kindFilter by remember { mutableStateOf<MediaKind?>(null) }
    var folderMenu by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>() }
    LaunchedEffect(token) {
        val lib = if (req.library) app.visiblePhotos else app.photos
        all = withContext(Dispatchers.IO) {
            (if (req.photos) lib.assets(MediaCategory.PHOTOS) else emptyList()) +
                (if (req.videos) lib.assets(MediaCategory.VIDEOS) else emptyList())
        }.distinctBy { it.id }.sortedByDescending { it.creationDate ?: it.dateAdded }
        folders = withContext(Dispatchers.IO) { lib.folders() }
    }
    val shown = all?.filter { a ->
        (folder == null || a.relativePath.equals(folder, ignoreCase = true)) &&
            (kindFilter == null || (kindFilter == MediaKind.VIDEO) == (a.kind == MediaKind.VIDEO))
    }
    ScreenScaffold(stringResource(req.title), onBack = onClose, bottomBar = {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            req.limitNote?.let { Text(it, style = AppTypography.footnote, color = c.textSecondary, modifier = Modifier.testTag("picker.limit")) }
            ProminentButton(if (picked.isEmpty()) stringResource(R.string.picker_choose, req.min) else pluralStringResource(R.plurals.picker_next, picked.size, picked.size),
                Modifier.testTag("picker.done"), enabled = picked.size >= req.min, fillWidth = true, large = true) { req.onDone(picked.toList()) }
        }
    }) { mod ->
        Column(mod) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                if (req.photos && req.videos) {
                    Chip(stringResource(R.string.picker_all), kindFilter == null, Modifier.testTag("picker.kind.all")) { kindFilter = null }
                    Chip(stringResource(R.string.cat_photos), kindFilter == MediaKind.PHOTO, Modifier.testTag("picker.kind.photo")) { kindFilter = MediaKind.PHOTO }
                    Chip(stringResource(R.string.cat_videos), kindFilter == MediaKind.VIDEO, Modifier.testTag("picker.kind.video")) { kindFilter = MediaKind.VIDEO }
                }
                Box {
                    Chip(folder?.let { MediaFolder(it, 0).name } ?: stringResource(R.string.picker_all_folders), folder != null, Modifier.testTag("picker.folder")) { folderMenu = true }
                    DropdownMenu(folderMenu, onDismissRequest = { folderMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.picker_all_folders)) }, onClick = { folder = null; folderMenu = false })
                        for (f in folders.sortedByDescending { it.count }) DropdownMenuItem(text = { Text("${f.name} · ${f.count}") }, onClick = { folder = f.relativePath; folderMenu = false })
                    }
                }
            }
            when {
                shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Rounded.PhotoLibrary, stringResource(R.string.picker_empty_title), stringResource(R.string.picker_empty_text))
                }
                else -> LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxSize().padding(horizontal = 2.dp).testTag("picker.grid"),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(shown, key = { it.id }) { a ->
                        val order = picked.indexOf(a.id)
                        PickTile(a, order, enabled = order >= 0 || picked.size < req.max) {
                            if (order >= 0) picked.remove(a.id) else if (picked.size < req.max) picked.add(a.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickTile(a: MediaAsset, order: Int, enabled: Boolean, onClick: () -> Unit) {
    val c = PM.colors
    val px = with(LocalDensity.current) { 120.dp.roundToPx() }
    val sel = order >= 0
    val label = stringResource(if (a.kind == MediaKind.VIDEO) R.string.video else R.string.photo)
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(c.backgroundTint)
        .border(if (sel) 3.dp else 0.dp, if (sel) c.accent else Color.Transparent, RoundedCornerShape(4.dp))
        .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
        .semantics(mergeDescendants = true) { selected = sel; contentDescription = if (sel) "$label ${order + 1}" else label }
        .testTag("picker.tile")) {
        AsyncImage(ImageRequest.Builder(LocalContext.current).data(a.uri).size(px, px).memoryCacheKey("pick:${a.id}").build(),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (!enabled) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.5f)))
        if (a.kind == MediaKind.VIDEO) Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(20.dp))
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape)
            .background(if (sel) c.accentFill else Color.Black.copy(alpha = 0.25f)).border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
            if (sel) Text("${order + 1}", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold), color = Color.White)
        }
    }
}
