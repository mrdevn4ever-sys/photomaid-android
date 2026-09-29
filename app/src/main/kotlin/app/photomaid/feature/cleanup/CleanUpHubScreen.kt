package app.photomaid.feature.cleanup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FilterNone
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.photomaid.FeedRequest
import app.photomaid.R
import app.photomaid.design.AppBackground
import app.photomaid.design.AppSpacing
import app.photomaid.design.readableWidth
import androidx.compose.foundation.layout.wrapContentWidth
import app.photomaid.design.GlassIconButton
import app.photomaid.design.LargeTitleHeader
import app.photomaid.design.PM
import app.photomaid.design.SegmentedPicker
import app.photomaid.design.SourceRow
import app.photomaid.design.SurfaceCard
import app.photomaid.media.MediaCategory
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.Routes
import app.photomaid.navigation.TabBarClearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class HubFilter { ALL, MEDIA, FILES }

/** Screen 6 – Aufräumen-Hub. */
@Composable
fun CleanUpHubScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val access by app.photos.access.collectAsState()
    val token by app.photos.changeToken.collectAsState()
    val read by app.read.collectAsState()
    val sources by app.sources.sources.collectAsState()
    var filter by rememberSaveable { mutableStateOf(HubFilter.ALL) }
    var counts by remember { mutableStateOf<Map<MediaCategory, Int>>(emptyMap()) }
    LaunchedEffect(token, access) { counts = withContext(Dispatchers.IO) { MediaCategory.entries.associateWith { app.photos.count(it) } } }

    AppBackground {
        Column(Modifier.fillMaxSize().wrapContentWidth().readableWidth().fillMaxWidth().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = AppSpacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            LargeTitleHeader(stringResource(R.string.tab_cleanup), stringResource(R.string.hub_subtitle)) {
                GlassIconButton(Icons.Rounded.Settings, stringResource(R.string.settings), onClick = nav::openSettings)
            }
            SegmentedPicker(listOf(HubFilter.ALL to stringResource(R.string.filter_all), HubFilter.MEDIA to stringResource(R.string.filter_media),
                HubFilter.FILES to stringResource(R.string.filter_files)), filter) { filter = it }
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                @Composable
                fun media(cat: MediaCategory, icon: ImageVector, title: Int, sub: Int) {
                    val enabled = access.canRead
                    SurfaceCard(Modifier.alpha(if (enabled) 1f else 0.45f).testTag("hub.${cat.name.lowercase()}"),
                        onClick = if (enabled) ({ nav.openFeed(FeedRequest(FeedRequest.Source.Category(cat))) }) else null) {
                        SourceRow(icon, c.accent, stringResource(title), subtitle = stringResource(sub), value = counts[cat]?.toString(),
                            valueDescription = counts[cat]?.let { pluralStringResource(R.plurals.items_count, it, it) })
                    }
                }
                @Composable
                fun link(id: String, icon: ImageVector, title: String, sub: String, route: String) {
                    SurfaceCard(Modifier.testTag("hub.$id"), onClick = { nav.push(route) }) { SourceRow(icon, c.accent, title, subtitle = sub) }
                }
                if (filter != HubFilter.FILES) {
                    media(MediaCategory.PHOTOS, Icons.Rounded.Photo, R.string.cat_photos, R.string.hub_photos_sub)
                    media(MediaCategory.VIDEOS, Icons.Rounded.Videocam, R.string.cat_videos, R.string.hub_videos_sub)
                    media(MediaCategory.SCREENSHOTS, Icons.Rounded.Screenshot, R.string.cat_screenshots, R.string.hub_screens_sub)
                }
                if (filter != HubFilter.MEDIA) {
                    link("files", Icons.Rounded.Download, stringResource(if (app.sources.downloadsSource == null) R.string.files else R.string.downloads),
                        stringResource(if (sources.isEmpty()) R.string.choose_folder else R.string.hub_files_sub), Routes.FILES)
                }
                link("large", Icons.Rounded.InsertDriveFile, stringResource(R.string.large_files), stringResource(R.string.hub_large_sub), Routes.LARGE)
                link("duplicates", Icons.Rounded.FilterNone, stringResource(R.string.duplicates), stringResource(R.string.hub_dup_sub), Routes.DUPLICATES)
                link("storage", Icons.Rounded.BarChart, stringResource(R.string.storage_overview), stringResource(R.string.hub_storage_sub), Routes.STORAGE)
                link("basket", Icons.Rounded.Delete, stringResource(R.string.basket), pluralStringResource(R.plurals.hub_basket_sub, read.basket.size, read.basket.size), Routes.BASKET)
            }
            Spacer(Modifier.height(TabBarClearance))
        }
    }
}
