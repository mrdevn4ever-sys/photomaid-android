package app.photomaid.feature.cleanup

import androidx.compose.material.icons.rounded.Folder
import app.photomaid.design.SizeText
import app.photomaid.design.SectionTitle
import app.photomaid.design.RowStatus
import app.photomaid.design.RowGroup
import app.photomaid.design.RowDividerInset
import app.photomaid.design.RowDivider
import app.photomaid.design.ListRow
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
import app.photomaid.media.MediaCategory
import app.photomaid.feature.collections.CollectionsContent
import app.photomaid.feature.quickaccess.resolveSource
import app.photomaid.media.reviewableMedia
import app.photomaid.core.quickaccess.QuickAccessItem
import app.photomaid.core.quickaccess.QuickSourceKind
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.Routes
import app.photomaid.navigation.TabBarEndSpacer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class HubTab { AREAS, COLLECTIONS }

/** Screen 6 – Aufräumen-Hub. */
@Composable
fun CleanUpHubScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val access by app.photos.access.collectAsState()
    val token by app.photos.changeToken.collectAsState()
    val read by app.read.collectAsState()
    val sources by app.sources.sources.collectAsState()
    var tab by rememberSaveable { mutableStateOf(HubTab.AREAS) }
    var counts by remember { mutableStateOf<Map<MediaCategory, Int>>(emptyMap()) }
    // Noch zu prüfen (nicht Gesamtbestand): dieselbe Quelle wie „Heute“, neu bei Mediathek- und Entscheidungsänderung.
    LaunchedEffect(token, access, read.events.size, read.basket.size, read.laterRecords.size) {
        counts = withContext(Dispatchers.IO) {
            if (!access.canRead) emptyMap() else {
                // Eine Momentaufnahme + eine Ledger-Abfrage; Bereiche sind nur Filter darauf (wie Schnellzugriffe).
                val all = app.photos.reviewableMedia()
                val open = app.pendingQueue(all).map { it.localIdentifier }.toSet()
                val kindOf = { cat: MediaCategory -> when (cat) {
                    MediaCategory.PHOTOS -> QuickSourceKind.PHOTOS
                    MediaCategory.VIDEOS -> QuickSourceKind.VIDEOS
                    MediaCategory.SCREENSHOTS -> QuickSourceKind.SCREENSHOTS
                } }
                MediaCategory.entries.associateWith { cat ->
                    resolveSource(QuickAccessItem(kindOf(cat)), all).count { it.id in open }
                }
            }
        }
    }

    val settings by app.settings.collectAsState()
    AppBackground {
        Column(Modifier.fillMaxSize().wrapContentWidth().readableWidth().fillMaxWidth().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = AppSpacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.block)) {
            LargeTitleHeader(stringResource(R.string.tab_cleanup)) {
                GlassIconButton(Icons.Rounded.Settings, stringResource(R.string.settings), onClick = nav::openSettings)
            }
            // Oben zwei Reiter: Bereiche (Mediathek und Werkzeuge) und Sammlungen (dauerhafte Filter).
            SegmentedPicker(listOf(HubTab.AREAS to stringResource(R.string.hub_tab_areas), HubTab.COLLECTIONS to stringResource(R.string.hub_tab_collections)),
                tab, Modifier.testTag("hub.tabs")) { tab = it }
            if (tab == HubTab.COLLECTIONS) CollectionsContent(nav)
            else {
                // Zwei ruhige Gruppen statt einer Kachelwand. Papierkorb und Später stehen auf „Heute“ (keine Doppelung).
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.headerGap)) {
                    SectionTitle(stringResource(R.string.hub_media_header))
                    RowGroup {
                        @Composable
                        fun media(cat: MediaCategory, icon: ImageVector, title: Int) {
                            val pending = counts[cat]
                            val t = stringResource(title)
                            val desc = pending?.let { if (it == 0) stringResource(R.string.hub_nothing_pending) else pluralStringResource(R.plurals.hub_pending, it, it) }
                            // 0 offen → Haken (heißt nur: gerade nichts zu prüfen; neue Medien zeigen wieder eine Zahl).
                            ListRow(icon, t, Modifier.testTag("hub.${cat.name.lowercase()}"), enabled = access.canRead,
                                status = when (pending) { null -> RowStatus.None; 0 -> RowStatus.Done; else -> RowStatus.Count(pending) },
                                description = desc?.let { "$t, $it" }) { nav.openFeed(FeedRequest(FeedRequest.Source.Category(cat))) }
                        }
                        media(MediaCategory.PHOTOS, Icons.Rounded.Photo, R.string.cat_photos)
                        RowDivider(RowDividerInset)
                        media(MediaCategory.VIDEOS, Icons.Rounded.Videocam, R.string.cat_videos)
                        RowDivider(RowDividerInset)
                        media(MediaCategory.SCREENSHOTS, Icons.Rounded.Screenshot, R.string.cat_screenshots)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.headerGap)) {
                    SectionTitle(stringResource(R.string.hub_tools_header))
                    RowGroup {
                        ListRow(Icons.Rounded.InsertDriveFile, stringResource(R.string.large_files), Modifier.testTag("hub.large"),
                            subtitle = stringResource(R.string.hub_large_from, SizeText.bytes(settings.largeFileThresholdMB * 1_000_000L))) { nav.push(Routes.LARGE) }
                        RowDivider(RowDividerInset)
                        ListRow(Icons.Rounded.FilterNone, stringResource(R.string.duplicates), Modifier.testTag("hub.duplicates"),
                            subtitle = stringResource(R.string.hub_dup_sub), badge = if (app.capabilities.exactDuplicates) null else stringResource(R.string.pro_badge)) { nav.push(Routes.DUPLICATES) }
                        RowDivider(RowDividerInset)
                        ListRow(Icons.Rounded.Folder, stringResource(if (app.sources.downloadsSource == null) R.string.files else R.string.downloads), Modifier.testTag("hub.files"),
                            subtitle = stringResource(if (sources.isEmpty()) R.string.choose_folder else R.string.hub_files_sub)) { nav.push(Routes.FILES) }
                        RowDivider(RowDividerInset)
                        ListRow(Icons.Rounded.BarChart, stringResource(R.string.storage_overview), Modifier.testTag("hub.storage")) { nav.push(Routes.STORAGE) }
                    }
                }
            }
            TabBarEndSpacer(minus = AppSpacing.block)
        }
    }
}
