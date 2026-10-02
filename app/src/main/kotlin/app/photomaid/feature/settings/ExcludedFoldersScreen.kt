package app.photomaid.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import app.photomaid.AppModel
import app.photomaid.R
import app.photomaid.core.organize.MediaFolder
import app.photomaid.core.organize.MediaFolders
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.ListSection
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.RowDivider
import app.photomaid.design.ScreenScaffold
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ordner vom Aufräumen ausnehmen bzw. wieder anzeigen. Gespeichert werden nur Ordnerpfade, keine Medien-IDs. */
@Composable
fun ExcludedFoldersScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val scope = rememberCoroutineScope()
    val settings by app.settings.collectAsState()
    val token by app.photos.changeToken.collectAsState()
    var folders by remember { mutableStateOf<List<MediaFolder>?>(null) }
    LaunchedEffect(token) { folders = withContext(Dispatchers.IO) { runCatching { app.allPhotos.folders() }.getOrDefault(emptyList()) } }
    val excluded = settings.excludedFolders

    ScreenScaffold(stringResource(R.string.excl_title), onBack = nav::back) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            Text(stringResource(R.string.excl_intro), style = AppTypography.subheadline, color = c.textSecondary)
            ListSection(Modifier.testTag("excl.list"), header = stringResource(R.string.excl_header)) {
                if (excluded.isEmpty()) Text(stringResource(R.string.excl_none), style = AppTypography.body, color = c.textSecondary,
                    modifier = Modifier.padding(vertical = AppSpacing.s))
                excluded.forEachIndexed { i, path ->
                    if (i > 0) RowDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                        Icon(Icons.Rounded.FolderOff, null, tint = c.textSecondary)
                        Column(Modifier.weight(1f)) {
                            Text(MediaFolder(path, 0).name, style = AppTypography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(path.trimEnd('/'), style = AppTypography.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        PlainButton(stringResource(R.string.excl_show_again), Modifier.testTag("excl.restore")) { scope.launch { setExcluded(app, path, false) } }
                    }
                }
            }
            ListSection(header = stringResource(R.string.excl_add_header), footer = stringResource(R.string.excl_add_footer)) {
                val all = folders
                if (all == null) CircularProgressIndicator(Modifier.padding(AppSpacing.s))
                else {
                    val keys = excluded.map { MediaFolders.normalize(it).lowercase() }
                    val choices = all.filter { f -> keys.none { f.relativePath.lowercase().startsWith(it) } }
                        .sortedWith(compareByDescending<MediaFolder> { it.count }.thenBy { it.name.lowercase() })
                    choices.forEachIndexed { i, f ->
                        if (i > 0) RowDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { scope.launch { setExcluded(app, f.relativePath, true) } }
                            .testTag("excl.add"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                            Icon(Icons.Rounded.Folder, null, tint = c.accent)
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = AppTypography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(f.relativePath.trimEnd('/'), style = AppTypography.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(pluralStringResource(R.plurals.items_count, f.count, f.count), style = AppTypography.caption, color = c.textSecondary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(AppSpacing.xl))
        }
    }
}

/** Ordner aufnehmen/entfernen und Mediathek-Zähler neu laden lassen. */
suspend fun setExcluded(app: AppModel, path: String, exclude: Boolean) {
    val p = MediaFolders.normalize(path)
    if (p.isEmpty()) return
    app.updateSettings { s ->
        val rest = s.excludedFolders.filterNot { it.equals(p, ignoreCase = true) }
        s.copy(excludedFolders = if (exclude) rest + p else rest)
    }
    app.photos.bumpChangeToken()
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
