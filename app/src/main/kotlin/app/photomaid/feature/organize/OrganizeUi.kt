package app.photomaid.feature.organize

import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.MediaKind
import app.photomaid.core.organize.AlbumRules
import app.photomaid.core.organize.NoteRules
import app.photomaid.feature.editor.Chip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.photomaid.AppModel
import app.photomaid.R
import app.photomaid.core.library.SearchText
import app.photomaid.core.library.TimelineLevel
import app.photomaid.core.organize.TagRules
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.EmptyState
import app.photomaid.design.GlassIconButton
import app.photomaid.design.ListSection
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.ProminentButton
import app.photomaid.design.ScreenScaffold
import app.photomaid.feature.common.ConfirmationSheet
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.feature.library.GridCell
import app.photomaid.feature.library.MediaGridPane
import app.photomaid.feature.library.PruneSelection
import app.photomaid.feature.library.RawIndex
import app.photomaid.feature.library.SelectionActions
import app.photomaid.feature.library.SelectionExtra
import app.photomaid.feature.library.SelectionHeader
import app.photomaid.feature.library.SlideshowRequest
import app.photomaid.feature.library.ViewerRequest
import app.photomaid.media.MediaAsset
import app.photomaid.media.reviewableMedia
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.TabBarClearance
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Was eine Organisieren-Seite zeigt: ein Album, alle Medien mit einem Schlagwort oder den Auswahlkorb. */
sealed interface OrganizeTarget {
    data class AlbumOf(val id: String) : OrganizeTarget
    data class TagOf(val tag: String) : OrganizeTarget
    data object Basket : OrganizeTarget

    fun encode(): String = when (this) { is AlbumOf -> "a:$id"; is TagOf -> "t:$tag"; Basket -> "b:" }

    companion object {
        fun decode(s: String): OrganizeTarget? = when {
            s.startsWith("a:") -> AlbumOf(s.drop(2))
            s.startsWith("t:") -> TagOf(s.drop(2))
            s == "b:" -> Basket
            else -> null
        }
    }
}

/** Ganze Mediathek (für das Wiederfinden: Ausgeblendetes gilt als vorhanden) und sichtbare Medien (zum Anzeigen). */
private data class Library(val all: List<MediaAsset>, val visible: List<MediaAsset>, val raw: RawIndex)

@Composable
private fun rememberLibrary(app: AppModel): Library? {
    val token by app.allPhotos.changeToken.collectAsState()
    val settings by app.settings.collectAsState()
    var lib by remember { mutableStateOf<Library?>(null) }
    LaunchedEffect(token, settings.hiddenMedia, settings.hiddenFolders) {
        val all = withContext(Dispatchers.IO) { runCatching { app.allPhotos.reviewableMedia() }.getOrDefault(emptyList()) }
        val visible = withContext(Dispatchers.IO) { runCatching { app.visiblePhotos.reviewableMedia() }.getOrDefault(emptyList()) }
        val raw = withContext(Dispatchers.Default) { RawIndex(visible) }
        app.organize.reconcile(all)
        lib = Library(all, raw.visible(visible), raw)
    }
    return lib
}

// ---- Tab „Fotos“ › Alben ---------------------------------------------------------------------------------------

/**
 * Ansicht „Alben“: eigene Alben (Cover, Name, Anzahl), Schlagwörter mit Anzahl, Auswahlkorb. Alles nur PhotoMaid-intern;
 * Ordner bleiben der Speicherort, Sammlungen die Suchbedingung.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlbumsPane(nav: Nav, assets: List<MediaAsset>, query: String) {
    val app = LocalApp.current
    val c = PM.colors
    val org by app.organize.state.collectAsState()
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    val byId = remember(assets) { assets.associateBy { it.id } }
    val q = SearchText.tokens(query)
    val albums = org.albums.filter { q.isEmpty() || SearchText.matches(q, listOf(SearchText.normalize(it.name))) }
    val tags = remember(org.tags) { TagRules.counts(org.tags) }.filter { (t, _) -> q.isEmpty() || SearchText.matches(q, listOf(SearchText.normalize(t))) }
    val px = with(LocalDensity.current) { 180.dp.roundToPx() }

    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().testTag("albums.pane"),
        contentPadding = PaddingValues(start = AppSpacing.pageMargin, end = AppSpacing.pageMargin, top = AppSpacing.xs, bottom = TabBarClearance + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.lib_mode_albums), style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.weight(1f).semantics { heading() })
                PlainButton(stringResource(R.string.org_new_album), Modifier.testTag("albums.new")) { naming = true }
            }
        }
        if (org.albums.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(vertical = AppSpacing.m).testTag("albums.empty"), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Rounded.PhotoAlbum, stringResource(R.string.org_albums_empty_title), stringResource(R.string.org_albums_empty_text))
            }
        }
        items(albums, key = { it.id }) { a ->
            val present = a.items.mapNotNull { byId[it] }
            val cover = present.firstOrNull()
            Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { nav.openOrganize(OrganizeTarget.AlbumOf(a.id)) }
                .semantics(mergeDescendants = true) { contentDescription = a.name }.testTag("album.card")) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(c.backgroundTint), contentAlignment = Alignment.Center) {
                    if (cover != null) AsyncImage(ImageRequest.Builder(LocalContext.current).data(cover.uri).size(px, px).memoryCacheKey("album:${cover.id}:$px").build(),
                        null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(Icons.Rounded.PhotoAlbum, null, tint = c.accent, modifier = Modifier.size(40.dp))
                }
                Text(a.name, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(pluralStringResource(R.plurals.org_items, present.size, present.size), style = AppTypography.caption, color = c.textSecondary)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s), modifier = Modifier.padding(top = AppSpacing.s)) {
                // Auswahlkorb: immer erreichbar, auch leer (erklärt sich selbst).
                ListSection(Modifier.testTag("albums.basket")) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { nav.openOrganize(OrganizeTarget.Basket) }
                        .padding(horizontal = AppSpacing.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                        Icon(Icons.Rounded.ShoppingBasket, null, tint = c.accent)
                        Text(stringResource(R.string.org_basket), style = AppTypography.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                        Text("${org.basket.count { it in byId }}", style = AppTypography.body, color = c.textSecondary)
                    }
                }
                Text(stringResource(R.string.org_tags), style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.semantics { heading() })
                if (tags.isEmpty()) Text(stringResource(R.string.org_tags_none), style = AppTypography.subheadline, color = c.textSecondary, modifier = Modifier.testTag("albums.tags.none"))
                else FlowRow(Modifier.fillMaxWidth().testTag("albums.tags"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    for ((t, n) in tags) Chip("$t · $n", false, Modifier.testTag("albums.tag.$t"), icon = Icons.Rounded.LocalOffer) {
                        nav.openOrganize(OrganizeTarget.TagOf(t)) }
                }
                Text(stringResource(R.string.org_albums_footer), style = AppTypography.footnote, color = c.textSecondary)
            }
        }
    }
    if (naming) NameDialog(stringResource(R.string.org_new_album), "", stringResource(R.string.org_create), onDismiss = { naming = false }) { name ->
        naming = false; scope.launch { app.organize.createAlbum(name) }
    }
}

// ---- Album · Schlagwort · Auswahlkorb ----------------------------------------------------------------------------

/**
 * Inhalt eines Albums, eines Schlagworts oder des Auswahlkorbs. Ansehen und Organisieren sind nie eine Entscheidung;
 * „Aus Album entfernen“ / „Schlagwort entfernen“ / „Aus dem Korb nehmen“ ändern nur Verwaltungsdaten. Nicht mehr
 * auffindbare Medien werden sichtbar gezählt und erst auf Wunsch aus der Liste entfernt.
 */
@Composable
fun OrganizeScreen(target: OrganizeTarget, nav: Nav, onClose: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val org by app.organize.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lib = rememberLibrary(app)
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var basketToAlbum by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val album = (target as? OrganizeTarget.AlbumOf)?.let { org.album(it.id) }
    val ids: List<String> = when (target) {
        is OrganizeTarget.AlbumOf -> album?.items.orEmpty()
        is OrganizeTarget.TagOf -> org.tags.filterValues { l -> l.any { TagRules.key(it) == TagRules.key(target.tag) } }.keys.toList()
        OrganizeTarget.Basket -> org.basket
    }
    // Album gelöscht oder Schlagwort überall entfernt: zurück.
    LaunchedEffect(album, ids.isEmpty()) {
        if (target is OrganizeTarget.AlbumOf && album == null) onClose()
        if (target is OrganizeTarget.TagOf && ids.isEmpty() && lib != null) onClose()
    }
    val allIds = remember(lib) { lib?.all?.map { it.id }?.toHashSet() ?: emptySet() }
    val visibleById = remember(lib) { lib?.visible?.associateBy { it.id } ?: emptyMap() }
    // Schlagwort: chronologisch (neueste zuerst); Album und Korb: in Reihenfolge des Hinzufügens.
    val members = remember(ids, visibleById) {
        val list = ids.mapNotNull { visibleById[it] }
        if (target is OrganizeTarget.TagOf) list.sortedByDescending { (it.creationDate ?: it.dateAdded)?.toEpochMilli() ?: Long.MIN_VALUE } else list
    }
    val missing = if (lib == null) emptyList() else ids.filter { it !in allIds }
    PruneSelection(lib?.visible, selection) { selection = it }
    val chosen = members.filter { it.id in selection }
    val title = when (target) {
        is OrganizeTarget.AlbumOf -> album?.name ?: ""
        is OrganizeTarget.TagOf -> target.tag
        OrganizeTarget.Basket -> stringResource(R.string.org_basket)
    }
    val cells = remember(members) { members.map { GridCell.Tile(it) } }

    val extra = when (target) {
        is OrganizeTarget.AlbumOf -> SelectionExtra(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.org_album_remove), "org.sel.remove") {
            val n = chosen.size
            scope.launch { app.organize.removeFromAlbum(target.id, chosen.map { it.id }); selection = emptySet()
                message = app.context.resources.getQuantityString(R.plurals.org_removed_from_album, n, n) }
        }
        is OrganizeTarget.TagOf -> SelectionExtra(Icons.Rounded.Remove, stringResource(R.string.org_tag_remove), "org.sel.remove") {
            scope.launch { app.organize.editTags(chosen, remove = listOf(target.tag)); selection = emptySet() }
        }
        OrganizeTarget.Basket -> SelectionExtra(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.org_basket_remove), "org.sel.remove") {
            scope.launch { app.organize.removeFromBasket(chosen.map { it.id }); selection = emptySet() }
        }
    }

    ScreenScaffold(title, onBack = onClose, modifier = Modifier.testTag("org.screen"), actions = {
        Box {
            GlassIconButton(Icons.Rounded.MoreHoriz, stringResource(R.string.more_actions), Modifier.testTag("org.more")) { menu = true }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.slideshow_start)) }, leadingIcon = { Icon(Icons.Rounded.Slideshow, null) },
                    enabled = members.isNotEmpty(), modifier = Modifier.testTag("org.slideshow"),
                    onClick = { menu = false; nav.openSlideshow(SlideshowRequest(UUID.randomUUID().toString(), members)) })
                when (target) {
                    is OrganizeTarget.AlbumOf -> {
                        DropdownMenuItem(text = { Text(stringResource(R.string.org_rename)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            modifier = Modifier.testTag("org.rename"), onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.org_album_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            modifier = Modifier.testTag("org.delete"), onClick = { menu = false; confirm = true })
                    }
                    is OrganizeTarget.TagOf -> DropdownMenuItem(text = { Text(stringResource(R.string.org_tag_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        modifier = Modifier.testTag("org.delete"), onClick = { menu = false; confirm = true })
                    OrganizeTarget.Basket -> {
                        DropdownMenuItem(text = { Text(stringResource(R.string.org_basket_to_album)) }, leadingIcon = { Icon(Icons.Rounded.PhotoAlbum, null) },
                            enabled = members.isNotEmpty(), modifier = Modifier.testTag("org.tolbum"), onClick = { menu = false; basketToAlbum = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.org_basket_clear)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            enabled = org.basket.isNotEmpty(), modifier = Modifier.testTag("org.delete"), onClick = { menu = false; confirm = true })
                    }
                }
            }
        }
    }) { mod ->
        Column(mod) {
            Column(Modifier.padding(horizontal = AppSpacing.pageMargin), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                if (target == OrganizeTarget.Basket) Text(stringResource(R.string.org_basket_intro), style = AppTypography.subheadline, color = c.textSecondary)
                if (selection.isNotEmpty()) SelectionHeader(selection.size, members.size,
                    onAll = { selection = if (selection.size == members.size) emptySet() else members.map { it.id }.toSet() }, onDone = { selection = emptySet() })
                else if (members.isNotEmpty()) Text(pluralStringResource(R.plurals.org_items, members.size, members.size), style = AppTypography.footnote,
                    color = c.textSecondary, modifier = Modifier.testTag("org.count"))
                if (missing.isNotEmpty()) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.warning.copy(alpha = 0.12f))
                    .padding(horizontal = AppSpacing.s, vertical = AppSpacing.xs).testTag("org.missing"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Icon(Icons.Rounded.Warning, null, tint = c.warning, modifier = Modifier.size(18.dp))
                    Text(pluralStringResource(R.plurals.org_missing, missing.size, missing.size), style = AppTypography.footnote, color = c.textPrimary, modifier = Modifier.weight(1f))
                    PlainButton(stringResource(R.string.org_missing_remove), Modifier.testTag("org.missing.remove")) {
                        scope.launch {
                            when (target) {
                                is OrganizeTarget.AlbumOf -> app.organize.removeFromAlbum(target.id, missing)
                                is OrganizeTarget.TagOf -> app.organize.editTags(missing.map { placeholder(it) }, remove = listOf(target.tag))
                                OrganizeTarget.Basket -> app.organize.removeFromBasket(missing)
                            }
                        }
                    }
                }
            }
            when {
                lib == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                members.isEmpty() -> Box(Modifier.fillMaxSize().padding(AppSpacing.pageMargin).testTag("org.empty"), contentAlignment = Alignment.Center) {
                    if (target == OrganizeTarget.Basket) EmptyState(Icons.Rounded.ShoppingBasket, stringResource(R.string.org_basket_empty_title), stringResource(R.string.org_basket_empty_text))
                    else EmptyState(Icons.Rounded.PhotoAlbum, title, stringResource(R.string.org_album_empty))
                }
                else -> Box(Modifier.fillMaxSize().padding(top = AppSpacing.xs)) {
                    MediaGridPane(cells, TimelineLevel.DAY, selection, { selection = it },
                        onOpen = { a -> nav.openViewer(ViewerRequest(UUID.randomUUID().toString(), members, members.indexOfFirst { it.id == a.id }.coerceAtLeast(0), lib.raw.partner)) },
                        bottomPadding = 96.dp, tag = "org.grid", rawIds = lib.raw.partner.keys)
                    SelectionActions(nav, chosen, onClear = { selection = emptySet() }, Modifier.align(Alignment.BottomCenter).padding(bottom = AppSpacing.s),
                        raw = lib.raw, extra = extra)
                }
            }
        }
    }
    if (renaming && album != null) NameDialog(stringResource(R.string.org_rename), album.name, stringResource(R.string.org_rename), onDismiss = { renaming = false }) { n ->
        renaming = false; scope.launch { app.organize.renameAlbum(album.id, n) }
    }
    if (basketToAlbum) NameDialog(stringResource(R.string.org_basket_to_album), "", stringResource(R.string.org_create), onDismiss = { basketToAlbum = false }) { n ->
        basketToAlbum = false
        scope.launch { app.organize.createAlbum(n, members)?.let { nav.openOrganize(OrganizeTarget.AlbumOf(it)) } }
    }
    if (confirm) when (target) {
        is OrganizeTarget.AlbumOf -> ConfirmationSheet(stringResource(R.string.org_album_delete_title, title), stringResource(R.string.org_album_delete_text),
            stringResource(R.string.org_album_delete), onConfirm = { scope.launch { app.organize.deleteAlbum(target.id) } }, onDismiss = { confirm = false })
        is OrganizeTarget.TagOf -> ConfirmationSheet(stringResource(R.string.org_tag_delete_title, title), stringResource(R.string.org_tag_delete_text),
            stringResource(R.string.org_tag_delete), onConfirm = { scope.launch { app.organize.deleteTagEverywhere(target.tag) } }, onDismiss = { confirm = false })
        OrganizeTarget.Basket -> ConfirmationSheet(stringResource(R.string.org_basket_clear_title), stringResource(R.string.org_basket_clear_text),
            stringResource(R.string.org_basket_clear), onConfirm = { scope.launch { app.organize.clearBasket() } }, onDismiss = { confirm = false })
    }
    message?.let { m -> NoticeDialog(m) { message = null } }
}

/** Nur für das Entfernen nicht mehr vorhandener Medien aus Schlagwörtern (die ID genügt). */
private fun placeholder(id: String) = MediaAsset(id, MediaKind.PHOTO, null, 0.0, 0, 0,
    KnownSize.Unknown, null, null, false)

// ---- Auswahl › ⋯ (Album, Schlagwörter, Korb) ---------------------------------------------------------------------

/** Einträge im ⋯-Menü der Auswahl. Keiner davon ist eine Aufräum-Entscheidung. */
@Composable
internal fun OrganizeMenuItems(chosen: List<MediaAsset>, close: () -> Unit, onAlbum: () -> Unit, onTags: () -> Unit, onMessage: (String) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    DropdownMenuItem(text = { Text(stringResource(R.string.org_add_to_album)) }, leadingIcon = { Icon(Icons.Rounded.PhotoAlbum, null) },
        modifier = Modifier.testTag("lib.sel.album"), onClick = { close(); onAlbum() })
    DropdownMenuItem(text = { Text(stringResource(R.string.org_tags)) }, leadingIcon = { Icon(Icons.Rounded.LocalOffer, null) },
        modifier = Modifier.testTag("lib.sel.tags"), onClick = { close(); onTags() })
    DropdownMenuItem(text = { Text(stringResource(R.string.org_basket_add)) }, leadingIcon = { Icon(Icons.Rounded.ShoppingBasket, null) },
        modifier = Modifier.testTag("lib.sel.basket"), onClick = {
            close()
            scope.launch {
                val n = app.organize.addToBasket(chosen)
                onMessage(if (n == 0) app.string(R.string.org_basket_already) else app.context.resources.getQuantityString(R.plurals.org_basket_added, n, n))
            }
        })
}

/** Zu einem Album hinzufügen: vorhandene Alben oder ein neues (Name direkt im Blatt – keine zweite Ebene). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumPickerSheet(chosen: List<MediaAsset>, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val org by app.organize.state.collectAsState()
    val scope = rememberCoroutineScope()
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val nameOk = AlbumRules.normalizeName(name) != null
    fun added(albumName: String, n: Int) = if (n == 0) app.string(R.string.org_already_in_album, albumName)
        else app.context.resources.getQuantityString(R.plurals.org_added_to_album, n, n, albumName)
    fun create() {
        if (!nameOk) return
        scope.launch {
            val id = app.organize.createAlbum(name, chosen) ?: return@launch
            onDone(added(app.organize.state.value.album(id)?.name ?: name, chosen.size))
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("album.picker")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Text(stringResource(R.string.org_add_to_album), style = AppTypography.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
            if (naming) {
                OutlinedTextField(name, { name = it.take(AlbumRules.MAX_NAME) }, Modifier.fillMaxWidth().testTag("org.name.input"), singleLine = true,
                    label = { Text(stringResource(R.string.org_album_name)) }, shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { create() }))
                ProminentButton(stringResource(R.string.org_create), Modifier.testTag("org.name.ok"), fillWidth = true, enabled = nameOk) { create() }
            } else Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { naming = true }
                .padding(horizontal = AppSpacing.s).testTag("album.picker.new"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                Icon(Icons.Rounded.Add, null, tint = c.accent)
                Text(stringResource(R.string.org_new_album), style = AppTypography.body, color = c.accent)
            }
            for (a in org.albums) {
                val all = chosen.isNotEmpty() && chosen.all { it.id in a.items }
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) {
                    scope.launch { val n = app.organize.addToAlbum(a.id, chosen); onDone(added(a.name, n)) }
                }.padding(horizontal = AppSpacing.s).semantics { if (all) stateDescription = app.string(R.string.org_tag_state_all) }.testTag("album.picker.item"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                    Icon(Icons.Rounded.PhotoAlbum, null, tint = c.textSecondary)
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = AppTypography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(pluralStringResource(R.plurals.org_items, a.items.size, a.items.size), style = AppTypography.caption, color = c.textSecondary)
                    }
                    if (all) Icon(Icons.Rounded.Check, null, tint = c.accent)
                }
            }
        }
    }
}

/** Schlagwörter für eine Auswahl: vorhandene an/aus (bei allen · bei einigen · keine), neue hinzufügen. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TagSheet(chosen: List<MediaAsset>, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val org by app.organize.state.collectAsState()
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    val known = remember(org.tags) { TagRules.counts(org.tags).map { it.first } }
    val selTags = chosen.map { org.tags[it.id].orEmpty() }
    fun addInput() {
        val t = TagRules.normalize(input) ?: return
        input = ""
        scope.launch { app.organize.editTags(chosen, add = listOf(t)) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("tag.sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin).padding(bottom = AppSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Text(pluralStringResource(R.plurals.org_tags_for, chosen.size, chosen.size), style = AppTypography.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                OutlinedTextField(input, { input = it.take(TagRules.MAX_LENGTH) }, Modifier.weight(1f).testTag("tag.input"), singleLine = true,
                    placeholder = { Text(stringResource(R.string.org_tag_new_hint)) }, shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { addInput() }))
                PlainButton(stringResource(R.string.org_tag_add), Modifier.testTag("tag.add"), enabled = TagRules.normalize(input) != null) { addInput() }
            }
            if (known.isEmpty()) Text(stringResource(R.string.org_tags_none), style = AppTypography.subheadline, color = c.textSecondary)
            else FlowRow(Modifier.fillMaxWidth().testTag("tag.list"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                for (t in known) {
                    val st = TagRules.state(t, selTags)
                    val stateText = when (st) { TagRules.State.ALL -> stringResource(R.string.org_tag_state_all); TagRules.State.SOME -> stringResource(R.string.org_tag_state_some); TagRules.State.NONE -> null }
                    Chip(if (st == TagRules.State.SOME) "$t –" else t, st == TagRules.State.ALL,
                        Modifier.testTag("tag.chip.$t").semantics { stateText?.let { stateDescription = it } }, icon = Icons.Rounded.LocalOffer) {
                        // Bei allen → entfernen; sonst allen hinzufügen.
                        scope.launch { if (st == TagRules.State.ALL) app.organize.editTags(chosen, remove = listOf(t)) else app.organize.editTags(chosen, add = listOf(t)) }
                    }
                }
            }
            ProminentButton(stringResource(R.string.done), Modifier.testTag("tag.done"), fillWidth = true) { addInput(); onDismiss() }
        }
    }
}

/** Schlagwörter und Notiz im Info-Blatt des Betrachters (nur GaleriQ, die Datei bleibt unverändert). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaOrganizeInfo(a: MediaAsset, nav: Nav?) {
    val app = LocalApp.current
    val c = PM.colors
    val org by app.organize.state.collectAsState()
    val scope = rememberCoroutineScope()
    var tagging by remember { mutableStateOf(false) }
    val saved = org.notes[a.id].orEmpty()
    var note by remember(a.id, saved) { mutableStateOf(saved) }
    val albums = org.albumsOf(a.id)
    Column(Modifier.fillMaxWidth().testTag("viewer.organize"), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.org_tags), style = AppTypography.footnote, color = c.textSecondary, modifier = Modifier.weight(1f))
            GlassIconButton(Icons.Rounded.Edit, stringResource(R.string.org_tags), Modifier.testTag("viewer.tags.edit"), quiet = true) { tagging = true }
        }
        val tags = org.tags[a.id].orEmpty()
        if (tags.isEmpty()) Text("—", style = AppTypography.body, color = c.textSecondary)
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            for (t in tags) Chip(t, false, Modifier.testTag("viewer.tag.$t"), icon = Icons.Rounded.LocalOffer) {
                nav?.openOrganize(OrganizeTarget.TagOf(t)) }
        }
        if (albums.isNotEmpty()) {
            Text(stringResource(R.string.lib_mode_albums), style = AppTypography.footnote, color = c.textSecondary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                for (al in albums) Chip(al.name, false, Modifier.testTag("viewer.album"), icon = Icons.Rounded.PhotoAlbum) {
                    nav?.openOrganize(OrganizeTarget.AlbumOf(al.id)) }
            }
        }
        Text(stringResource(R.string.org_note), style = AppTypography.footnote, color = c.textSecondary)
        OutlinedTextField(note, { note = it.take(NoteRules.MAX_LENGTH) }, Modifier.fillMaxWidth().testTag("viewer.note"),
            placeholder = { Text(stringResource(R.string.org_note_hint)) }, minLines = 2, maxLines = 6, shape = RoundedCornerShape(14.dp))
        if (note.trim() != saved) PlainButton(stringResource(R.string.org_note_save), Modifier.testTag("viewer.note.save")) {
            scope.launch { app.organize.setNote(a, note) }
        }
    }
    if (tagging) TagSheet(listOf(a)) { tagging = false }
}

/** Name eingeben (Album anlegen/umbenennen). */
@Composable
internal fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val ok = AlbumRules.normalizeName(name) != null
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("org.name"),
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it.take(AlbumRules.MAX_NAME) }, Modifier.fillMaxWidth().testTag("org.name.input"), singleLine = true,
                label = { Text(stringResource(R.string.org_album_name)) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onConfirm(name) }))
        },
        confirmButton = { TextButton({ onConfirm(name) }, enabled = ok, modifier = Modifier.testTag("org.name.ok")) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}
