package app.photomaid

import app.photomaid.core.quickaccess.QuickAccessItem

import app.photomaid.core.quickaccess.QuickSourceKind

import kotlinx.coroutines.runBlocking

import androidx.compose.ui.test.performScrollTo

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Schedule

import app.photomaid.media.PhotoAccess

import androidx.compose.ui.test.swipeLeft

import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp

import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction

import androidx.compose.ui.test.onNodeWithTag

import com.github.takahirom.roborazzi.captureScreenRoboImage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.design.AppearanceChoice
import app.photomaid.design.LocalReduceMotion
import app.photomaid.feature.cleanup.CleanUpHubScreen
import app.photomaid.feature.cleanup.CleanupBasketScreen
import app.photomaid.feature.duplicates.DuplicatesScreen
import app.photomaid.feature.files.FileBrowserScreen
import app.photomaid.feature.history.HistoryScreen
import app.photomaid.feature.later.LaterScreen
import app.photomaid.feature.onboarding.PrivacyAccessScreen
import app.photomaid.feature.pro.ProScreen
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.feature.collections.CollectionsContent
import app.photomaid.design.AppBackground
import app.photomaid.feature.settings.SettingsScreen
import app.photomaid.feature.storage.LargeFilesScreen
import app.photomaid.feature.storage.StorageInsightsScreen
import app.photomaid.feature.today.TodayScreen
import app.photomaid.media.MediaCategory
import app.photomaid.navigation.GlassTabBar
import app.photomaid.navigation.AppTab
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import app.photomaid.design.AppSpacing
import androidx.compose.foundation.layout.padding
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * JVM-Screenshots (Roborazzi) aller Kernscreens für den visuellen Vergleich mit iOS (Masterprompt §41).
 * Das ist KEINE Geräteabnahme. `./gradlew :app:recordRoborazziDebug` → `app/build/outputs/roborazzi/`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+w393dp-h852dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var lib: FakeMediaLibrary

    private fun seededApp(dark: Boolean = false, pro: Boolean = false) = makeTestApp(ctx,
        media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l } },
        purchase = if (pro) FakePurchaseGateway(PurchaseState.PURCHASED) else null) { a ->
        if (dark) a.updateSettings { it.copy(appearance = AppearanceChoice.DARK) }
        val s = ReviewSessionID()
        suspend fun act(i: Int, action: DecisionAction) {
            val ref = ItemReference.photo(lib.all[i].id)
            a.ledger.apply(DecisionCommand(item = ref, expectedRevision = a.ledger.record(ref).revision, action = action), s)
        }
        act(0, DecisionAction.Keep)
        act(1, DecisionAction.MarkForCleanup)
        act(6, DecisionAction.MarkForCleanup)
        act(2, DecisionAction.Later(s)); act(2, DecisionAction.Schedule(a.today.addingDays(1)))
        act(3, DecisionAction.Later(s)); act(3, DecisionAction.Schedule(a.today.addingDays(5)))
        act(4, DecisionAction.Later(s))
    }

    private fun shot(name: String, app: AppModel, reduceMotion: Boolean = false, waitTag: String? = null, content: @Composable () -> Unit) {
        rule.setContent { TestHost(app) { CompositionLocalProvider(LocalReduceMotion provides reduceMotion) { content() } } }
        if (waitTag != null) rule.waitUntil(10_000) { rule.onAllNodesWithTag(waitTag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Composable
    private fun WithTabBar(tab: AppTab, content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) {
        content(); GlassTabBar(tab, Modifier.align(Alignment.BottomCenter)) {}
    }

    private val nav = RecordingNav()

    @Test fun s01_today() = seededApp().let { shot("01_today_light", it) { WithTabBar(AppTab.TODAY) { TodayScreen(nav) } } }
    @Test fun s01_today_dark() = seededApp(dark = true).let { shot("01_today_dark", it) { WithTabBar(AppTab.TODAY) { TodayScreen(nav) } } }
    @Test fun s02_feed_photo() = seededApp().let {
        shot("02_feed_photo", it, waitTag = "feed.media.ready") { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, {}, {}) }
    }
    @Test fun s02_feed_photo_dark() = seededApp(dark = true).let {
        shot("02_feed_photo_dark", it, waitTag = "feed.media.ready") { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, {}, {}) }
    }
    @Test fun s03_feed_video() = seededApp().let {
        shot("03_feed_video", it, reduceMotion = true, waitTag = "feed.media.ready") { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.VIDEOS)), nav, {}, {}) }
    }
    @Test fun s04_privacy() = seededApp().let { shot("04_privacy", it) { PrivacyAccessScreen(onboarding = true) {} } }
    @Test fun s05_basket() = seededApp().let { shot("05_basket", it) { CleanupBasketScreen(nav, showsDone = false) } }
    @Test fun s05_basket_dark() = seededApp(dark = true).let { shot("05_basket_dark", it) { CleanupBasketScreen(nav, showsDone = false) } }
    @Test fun s06_hub() = seededApp().let { shot("06_cleanup_hub", it) { WithTabBar(AppTab.CLEANUP) { CleanUpHubScreen(nav) } } }
    @Test fun s07_files() = seededApp().let { shot("07_files", it) { FileBrowserScreen(nav) } }
    @Test fun s08_large() = seededApp().let { shot("08_large_files", it) { LargeFilesScreen(nav) } }
    @Test fun s09_duplicates() = seededApp().let { shot("09_duplicates_free", it) { DuplicatesScreen(nav) } }
    @Test fun s10_storage() = seededApp().let { shot("10_storage", it) { StorageInsightsScreen(nav) } }
    @Test fun s11_later() = seededApp().let { shot("11_later", it) { LaterScreen(nav) } }
    @Test fun s12_collections() = seededApp().let {
        shot("12_collections", it) { AppBackground { androidx.compose.foundation.layout.Column(Modifier.padding(AppSpacing.pageMargin)) { CollectionsContent(nav) } } }
    }
    @Test fun s13_history() = seededApp().let { shot("13_history", it) { WithTabBar(AppTab.HISTORY) { HistoryScreen(nav) } } }
    @Test fun s13_history_dark() = seededApp(dark = true).let { shot("13_history_dark", it) { WithTabBar(AppTab.HISTORY) { HistoryScreen(nav) } } }
    @Test fun s14_pro() = seededApp().let { shot("14_pro", it) { ProScreen(nav) } }
    @Test fun s14_pro_active() = seededApp(pro = true).let { shot("14_pro_active", it) { ProScreen(nav) } }
    @Test fun s15_settings() = seededApp().let { shot("15_settings", it) { SettingsScreen(nav) } }
    @Test fun s15_settings_dark() = seededApp(dark = true).let { shot("15_settings_dark", it) { SettingsScreen(nav) } }

    // Premium-Pass: leere Zustände und Problemzustand (ohne Seed-Entscheidungen).
    private fun emptyApp() = makeTestApp(ctx, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l } })
    @Test fun p01_later_empty() = emptyApp().let { shot("p01_later_empty", it) { LaterScreen(nav) } }
    @Test fun p02_history_empty() = emptyApp().let { shot("p02_history_empty", it) { WithTabBar(AppTab.HISTORY) { HistoryScreen(nav) } } }
    @Test fun p03_basket_empty() = emptyApp().let { shot("p03_basket_empty", it) { CleanupBasketScreen(nav, showsDone = false) } }
    @Test fun p04_feed_problem() = emptyApp().let {
        java.io.File(lib.all[0].id.removePrefix("file://")).delete()
        shot("p04_feed_problem", it, waitTag = "feed.media.problem") { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Items(listOf(ItemReference.photo(lib.all[0].id)), R.string.feed_title_photos)), nav, {}, {}) }
    }

    // Medien-Geometrie im Feed (UI-Polish §7/§8): jedes Format vollständig, ohne Beschnitt, gleiche Logik für Foto/Video.
    private fun feedOf(i: Int, name: String, title: Int = R.string.feed_title_photos, reduceMotion: Boolean = false) = seededApp().let {
        val ref = ItemReference.photo(lib.all[i].id)
        shot(name, it, reduceMotion = reduceMotion, waitTag = "feed.media.ready") {
            ReviewFeedScreen(FeedRequest(FeedRequest.Source.Items(listOf(ref), title)), nav, {}, {})
        }
    }
    @Test fun g01_feed_portrait() = feedOf(0, "g01_feed_portrait")
    @Test fun g02_feed_landscape() = feedOf(1, "g02_feed_landscape")
    @Test fun g03_feed_panorama() = feedOf(3, "g03_feed_panorama")
    @Test fun g04_feed_screenshot_text() = feedOf(2, "g04_feed_screenshot_text", R.string.feed_title_screenshots)
    @Test fun g05_feed_video_landscape() = feedOf(6, "g05_feed_video_landscape", R.string.feed_title_videos, reduceMotion = true)
    @Test fun g06_feed_video_portrait() = feedOf(7, "g06_feed_video_portrait", R.string.feed_title_videos, reduceMotion = true)

    // Gerätematrix (Masterprompt §17/§45): kleines Telefon, Fold außen/innen, 8"/11"-Tablet, Schriftgröße.
    @Test @Config(qualifiers = "+w320dp-h568dp-xhdpi") fun m_small_phone_feed() = seededApp().let {
        shot("m_small_phone_feed", it, waitTag = "feed.media.ready") { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, {}, {}) }
    }
    @Test @Config(qualifiers = "+w320dp-h568dp-xhdpi") fun m_small_phone_today() = seededApp().let { shot("m_small_phone_today", it) { WithTabBar(AppTab.TODAY) { TodayScreen(nav) } } }
    @Test @Config(qualifiers = "+w360dp-h840dp-xxhdpi") fun m_fold_cover_today() = seededApp().let { shot("m_fold_cover_today", it) { WithTabBar(AppTab.TODAY) { TodayScreen(nav) } } }
    @Test @Config(qualifiers = "+w673dp-h841dp-xhdpi") fun m_fold_open_feed() = seededApp().let {
        shot("m_fold_open_feed", it, waitTag = "feed.media.ready") { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, {}, {}) }
    }
    @Test @Config(qualifiers = "+w600dp-h960dp-xhdpi") fun m_tablet8_today() = seededApp().let { shot("m_tablet8_today", it) { WithTabBar(AppTab.TODAY) { TodayScreen(nav) } } }
    @Test @Config(qualifiers = "+w834dp-h1194dp-xhdpi") fun m_tablet11_basket() = seededApp().let { shot("m_tablet11_basket", it) { CleanupBasketScreen(nav, showsDone = false) } }
    @Test fun m_font_150_today() = seededApp().let { RuntimeEnvironment.setFontScale(1.5f); shot("m_font150_today", it) { WithTabBar(AppTab.TODAY) { TodayScreen(nav) } } }
    @Test fun m_font_200_feed() = seededApp().let {
        RuntimeEnvironment.setFontScale(2.0f)
        shot("m_font200_feed", it, waitTag = "feed.media.ready") { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, {}, {}) }
    }
    @Test fun m_font_200_settings() = seededApp().let { RuntimeEnvironment.setFontScale(2.0f); shot("m_font200_settings", it) { SettingsScreen(nav) } }

    // Produkt-UX-Pass: neue Zustände (Dialoge/Sheets als ganzer Bildschirm inkl. Fenster).
    private fun shotScreen(name: String) { rule.waitForIdle(); captureScreenRoboImage("build/outputs/roborazzi/$name.png") }
    private fun waitTagX(tag: String) = rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun feedPhotos(a: AppModel) = rule.setContent { TestHost(a) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, {}, {}) } }

    @Test fun x01_today_start() = emptyApp().let { shot("x01_today_start", it) { WithTabBar(AppTab.TODAY) { TodayScreen(nav) } } }
    @Test fun x05_feed_favorite_on() = emptyApp().let {
        lib.favorites[lib.all[0].id] = true
        shot("x05_feed_favorite_on", it, waitTag = "feed.media.ready") { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, {}, {}) }
    }
    @Test fun x07_feed_protected_favorite() = emptyApp().let {
        lib.favorites[lib.all[0].id] = true
        feedPhotos(it); waitTagX("feed.media.ready")
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeLeft() }
        waitTagX("feed.protected"); shotScreen("x07_feed_protected_favorite")
    }
    @Test fun x08_folder_sheet() = emptyApp().let {
        feedPhotos(it); waitTagX("feed.media.ready")
        rule.onNodeWithTag("action.folder").performClick()
        waitTagX("folder.new"); shotScreen("x08_folder_sheet")
    }
    @Test fun x10_basket_preview() = seededApp().let {
        rule.setContent { TestHost(it) { CleanupBasketScreen(nav, showsDone = false) } }
        waitTagX("basket.thumb")
        rule.onAllNodesWithTag("basket.thumb")[0].performClick()
        waitTagX("basket.preview"); rule.mainClock.advanceTimeBy(1500); shotScreen("x10_basket_preview")
    }
    private fun rewardApp(grantedMinutesAgo: Long?) = makeTestApp(ctx, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l } },
        purchase = FakePurchaseGateway(PurchaseState.NOT_PURCHASED), ads = FakeRewardedAdGateway()) { a ->
        if (grantedMinutesAgo != null) {
            val at = java.time.Instant.now().minusSeconds(grantedMinutesAgo * 60)
            a.store.setValue("rewards.ledger", app.photomaid.core.capabilities.RewardLedger(listOf(app.photomaid.core.capabilities.RewardWindow("r0", at,
                app.photomaid.core.model.LocalDay.of(at, java.time.ZoneId.systemDefault())))))
            a.entitlements.load()
        }
    }
    @Test fun x13_pro_reward_available() = rewardApp(null).let { shot("x13_pro_reward_available", it) { ProScreen(nav) } }
    @Test fun x14_pro_reward_used() = rewardApp(40).let { shot("x14_pro_reward_used", it) { ProScreen(nav) } }
    @Test fun x15_pro_temporary() = rewardApp(6).let { shot("x15_pro_temporary", it) { ProScreen(nav) } }
    @Test fun x18_today_limited() = makeTestApp(ctx, media = { FakeMediaLibrary(it, visual = true, access = PhotoAccess.LIMITED).also { l -> lib = l } }).let {
        shot("x18_today_limited", it) { WithTabBar(AppTab.TODAY) { TodayScreen(nav) } }
    }
    @Test fun x19_favorite_error() = emptyApp().let {
        lib.writeResult = app.photomaid.media.MediaWriteResult.Failed("test")
        feedPhotos(it); waitTagX("feed.media.ready")
        rule.onNodeWithTag("action.favorite").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Die Favoritenmarkierung konnte nicht geändert werden. Sie bleibt unverändert.")).fetchSemanticsNodes().isNotEmpty() }
        shotScreen("x19_favorite_error")
    }
    @Test fun x20_later_notify() = seededApp().let { shot("x20_later_notify", it) { LaterScreen(nav) } }

    // Home-Statuskarten: Zähler 0/1/6/99/999, Schrift 1.0–2.0, schmales Telefon.
    private fun statusCards(name: String, scale: Float) = emptyApp().let {
        RuntimeEnvironment.setFontScale(scale)
        shot(name, it) {
            AppBackground {
                androidx.compose.foundation.layout.Column(Modifier.padding(AppSpacing.pageMargin),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AppSpacing.xs)) {
                    val c = app.photomaid.design.PM.colors
                    for (n in listOf(0, 1, 6, 99, 999)) app.photomaid.design.RowGroup {
                        app.photomaid.design.ListRow(Icons.Rounded.Delete, "Papierkorb", tint = c.cleanup, tile = c.cleanup.copy(alpha = 0.12f),
                            status = app.photomaid.design.RowStatus.Count(n)) {}
                        app.photomaid.design.RowDivider(app.photomaid.design.RowDividerInset)
                        app.photomaid.design.ListRow(Icons.Rounded.Schedule, "Später", tint = c.later, tile = c.later.copy(alpha = 0.12f),
                            status = app.photomaid.design.RowStatus.Count(n)) {}
                    }
                }
            }
        }
    }
    @Test fun h01_status_cards_100() = statusCards("h01_status_cards_100", 1.0f)
    @Test fun h02_status_cards_150() = statusCards("h02_status_cards_150", 1.5f)
    @Test @Config(qualifiers = "+w320dp-h900dp-xhdpi") fun h03_status_cards_200_small() = statusCards("h03_status_cards_200_small", 2.0f)

    // Aufräumen-Hub: A = alles geprüft (6 für später), B = offene Elemente.
    private fun hubDoneApp() = makeTestApp(ctx, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l } }) { a ->
        val sid = ReviewSessionID()
        lib.all.forEachIndexed { i, m ->
            val ref = ItemReference.photo(m.id)
            if (i < 6) {
                a.ledger.apply(DecisionCommand(item = ref, expectedRevision = a.ledger.record(ref).revision, action = DecisionAction.Later(sid)))
                a.ledger.apply(DecisionCommand(item = ref, expectedRevision = a.ledger.record(ref).revision, action = DecisionAction.Schedule(a.today.addingDays(2))))
            } else a.ledger.apply(DecisionCommand(item = ref, expectedRevision = a.ledger.record(ref).revision, action = DecisionAction.Keep))
        }
    }
    @Test fun k01_hub_all_done() = hubDoneApp().let { shot("k01_hub_all_done", it) { WithTabBar(AppTab.CLEANUP) { CleanUpHubScreen(nav) } } }
    @Test fun k02_hub_pending() = emptyApp().let { shot("k02_hub_pending", it) { WithTabBar(AppTab.CLEANUP) { CleanUpHubScreen(nav) } } }
    @Test @Config(qualifiers = "+w320dp-h900dp-xhdpi") fun k03_hub_small_100() = hubDoneApp().let { shot("k03_hub_small_100", it) { CleanUpHubScreen(nav) } }
    @Test @Config(qualifiers = "+w320dp-h1200dp-xhdpi") fun k04_hub_small_150() = emptyApp().let { RuntimeEnvironment.setFontScale(1.5f); shot("k04_hub_small_150", it) { CleanUpHubScreen(nav) } }
    @Test @Config(qualifiers = "+w320dp-h1600dp-xhdpi") fun k05_hub_small_200() = hubDoneApp().let { RuntimeEnvironment.setFontScale(2.0f); shot("k05_hub_small_200", it) { CleanUpHubScreen(nav) } }

    // Schnellzugriffe auf Heute.
    private fun quickApp(six: Boolean) = seededApp().also { a ->
        if (six) runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            a.quickAccess.update { listOf("PHOTOS", "VIDEOS", "SCREENSHOTS", "LATEST_PHOTOS", "LATEST_VIDEOS", "THIS_WEEK")
                .map { k -> app.photomaid.core.quickaccess.QuickAccessItem(app.photomaid.core.quickaccess.QuickSourceKind.valueOf(k)) } }
        }
    }
    private fun todayScroll(name: String, a: AppModel) = shot(name, a, waitTag = "quick.PHOTOS") {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) { TodayScreen(nav) }
    }
    @Test fun q03_quick_add_sheet() = quickApp(false).let {
        rule.setContent { TestHost(it) { TodayScreen(nav) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("quick.add").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("quick.add").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("quick.option.SCREENSHOTS").fetchSemanticsNodes().isNotEmpty() }
        shotScreen("q03_quick_add_sheet")
    }
    @Test fun q04_quick_edit_sheet() = quickApp(true).let {
        rule.setContent { TestHost(it) { TodayScreen(nav) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("quick.edit").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("quick.edit").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("quick.editSheet").fetchSemanticsNodes().isNotEmpty() }
        shotScreen("q04_quick_edit_sheet")
    }

    // Abschnitt 45: A–G.
    private fun homeApp(dark: Boolean = false, allDone: Boolean, four: Boolean = false) = makeTestApp(ctx,
        media = { FakeMediaLibrary(it, count = 10, videos = 4).also { l -> lib = l } }) { a ->
        if (dark) a.updateSettings { it.copy(appearance = AppearanceChoice.DARK) }
        val sid = ReviewSessionID()
        suspend fun act(i: Int, action: DecisionAction) {
            val ref = ItemReference.photo(lib.all[i].id)
            a.ledger.apply(DecisionCommand(item = ref, expectedRevision = a.ledger.record(ref).revision, action = action), sid)
        }
        if (allDone) lib.all.indices.forEach { act(it, DecisionAction.Keep) }
        else {
            act(0, DecisionAction.MarkForCleanup); act(1, DecisionAction.MarkForCleanup)
            for (i in 3..5) { act(i, DecisionAction.Later(sid)); act(i, DecisionAction.Schedule(a.today.addingDays(2))) }
            act(10, DecisionAction.Keep); act(11, DecisionAction.Keep)
        }
        if (four) a.quickAccess.update { listOf(QuickSourceKind.PHOTOS, QuickSourceKind.VIDEOS, QuickSourceKind.SCREENSHOTS, QuickSourceKind.LATEST_PHOTOS).map { k -> QuickAccessItem(k) } }
    }
    private fun home(name: String, a: AppModel) = shot(name, a, waitTag = "quick.PHOTOS") { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) { TodayScreen(nav) } }
    @Test @Config(qualifiers = "+w393dp-h1100dp-xxhdpi") fun r01_home_all_done() = home("r01_home_all_done", homeApp(allDone = true))
    @Test @Config(qualifiers = "+w393dp-h1100dp-xxhdpi") fun r02_home_open() = home("r02_home_open", homeApp(allDone = false))
    @Test @Config(qualifiers = "+w393dp-h1200dp-xxhdpi") fun r04_home_four_sources() = home("r04_home_four_sources", homeApp(allDone = false, four = true))
    @Test @Config(qualifiers = "+w393dp-h1200dp-xxhdpi") fun r05_home_dark() = home("r05_home_dark", homeApp(dark = true, allDone = false, four = true))
    @Test @Config(qualifiers = "+w320dp-h1100dp-xhdpi") fun r06_home_small() = home("r06_home_small", homeApp(allDone = false, four = true))
    @Test @Config(qualifiers = "+w393dp-h1600dp-xxhdpi") fun r07_home_font150() = homeApp(allDone = false, four = true).let { RuntimeEnvironment.setFontScale(1.5f); home("r07_home_font150", it) }
    @Test @Config(qualifiers = "+w320dp-h2000dp-xhdpi") fun r08_home_font200_small() = homeApp(allDone = false, four = true).let { RuntimeEnvironment.setFontScale(2.0f); home("r08_home_font200_small", it) }

    // Werbung + Kaufmomente (JVM, keine echte Anzeige: Fake-Quelle ohne SDK).
    private fun adApp(dark: Boolean = false) = makeTestApp(ctx, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l } },
        purchase = FakePurchaseGateway(PurchaseState.NOT_PURCHASED), feedAds = FakeFeedAds()) { a ->
        if (dark) a.updateSettings { it.copy(appearance = AppearanceChoice.DARK) }
        a.store.setValue("feedads.v1", app.photomaid.core.ads.FeedAdState(29, 29)); a.feedAdCounter.load()
    }
    private fun adShot(name: String, a: AppModel, skippable: Boolean) {
        feedPhotos(a); waitTagX("feed.media.ready")
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeUp() }
        waitTagX("feed.ad")
        if (skippable) { Thread.sleep(1_700); waitTagX("feed.ad.skip") }
        shotScreen(name)
    }
    @Test fun z01_ad_card_wait() = adShot("z01_ad_card_wait", adApp(), skippable = false)
    @Test fun z02_ad_card_skip() = adShot("z02_ad_card_skip", adApp(), skippable = true)
    @Test fun z03_ad_card_dark() = adShot("z03_ad_card_dark", adApp(dark = true), skippable = true)
    private fun summary(name: String, upsell: app.photomaid.feature.review.SummaryUpsell?, left: Int?) = seededApp().let { a ->
        val allowance = left?.let { app.photomaid.core.cleanup.AllowanceSnapshot(a.today, 20, 20 - it, 0) }
        shot(name, a) { AppBackground { app.photomaid.feature.review.SessionSummaryView(app.photomaid.core.feed.SessionSummary(kept = 9, marked = 6, later = 2), 6,
            Modifier.fillMaxSize(), {}, {}, {}, upsell = upsell, allowance = allowance) } }
    }
    @Test @Config(qualifiers = "+w393dp-h1100dp-xxhdpi") fun z04_summary_offer() = summary("z04_summary_offer", app.photomaid.feature.review.SummaryUpsell.STRONG, 3)
    @Test fun z05_summary_quiet() = summary("z05_summary_quiet", app.photomaid.feature.review.SummaryUpsell.QUIET, null)
    @Test fun z06_limit_sheet() = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 22, videos = 0).also { l -> lib = l } },
        purchase = FakePurchaseGateway(PurchaseState.NOT_PURCHASED)) { a ->
        val refs = lib.all.map { ItemReference.photo(it.id) }
        suspend fun mark(r: ItemReference) = a.ledger.apply(DecisionCommand(item = r, expectedRevision = a.ledger.record(r).revision, action = DecisionAction.MarkForCleanup))
        refs.take(18).forEach { mark(it) }
        a.runCleanup(refs.take(18), app.photomaid.core.cleanup.CleanupOperationKind.PHOTO_LIBRARY_DELETE)
        refs.drop(18).forEach { mark(it) }
    }.let {
        rule.setContent { TestHost(it) { CleanupBasketScreen(nav, showsDone = false) } }
        rule.waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("4 Fotos und Videos entfernen")).fetchSemanticsNodes().isNotEmpty() }
        shotScreen("z06a_basket_limit_soon")
        rule.onNode(androidx.compose.ui.test.hasText("4 Fotos und Videos entfernen")).performClick()
        waitTagX("basket.limit.pro"); rule.mainClock.advanceTimeBy(1000); shotScreen("z06b_limit_sheet")
    }
    @Test @Config(qualifiers = "+w393dp-h1300dp-xxhdpi") fun z07_pro_full() = seededApp().let { shot("z07_pro_full", it) { ProScreen(nav) } }
    @Test @Config(qualifiers = "+w393dp-h1300dp-xxhdpi") fun z08_pro_dark() = seededApp(dark = true).let { shot("z08_pro_dark", it) { ProScreen(nav) } }
    @Test @Config(qualifiers = "+w393dp-h1300dp-xxhdpi") fun z09_today_pro_hint() = makeTestApp(ctx, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l } },
        purchase = FakePurchaseGateway(PurchaseState.NOT_PURCHASED)) { a -> a.updateSettings { s -> s.copy(firstSeenDay = a.today.addingDays(-1)) } }
        .let { shot("z09_today_pro_hint", it, waitTag = "today.pro") { WithTabBar(AppTab.TODAY) { TodayScreen(nav) } } }

    // Design-Review: Hero mit 1 bzw. 2 offenen Medien (keine leeren Kacheln), Gestenzeile für Neulinge.
    private fun heroApp(n: Int) = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = n, videos = 0).also { l -> lib = l } }) { a ->
        a.updateSettings { it.copy(gestureHintSeen = false) }
    }
    @Test fun d01_hero_one() = heroApp(1).let { shot("d01_hero_one", it, waitTag = "today.cta") { WithTabBar(AppTab.TODAY) { TodayScreen(nav) } } }
    @Test fun d02_hero_two() = heroApp(2).let { shot("d02_hero_two", it, waitTag = "today.cta") { WithTabBar(AppTab.TODAY) { TodayScreen(nav) } } }

    // Onboarding + Angebot + Tutorial (2026-10-04)
    private fun onbApp(dark: Boolean = false) = makeTestApp(ctx, purchase = FakePurchaseGateway(PurchaseState.NOT_PURCHASED), introSeen = false) { a ->
        if (dark) a.updateSettings { it.copy(appearance = AppearanceChoice.DARK) }
    }
    private fun onbTo(a: AppModel, nexts: Int, name: String, skip: Boolean = false) {
        rule.setContent { TestHost(a) { app.photomaid.navigation.RootContent(a) } }
        waitTagX("onb.page.0")
        if (skip) rule.onNodeWithTag("onb.skip").performClick()
        else repeat(nexts) { rule.onNodeWithTag("onb.next").performClick(); rule.mainClock.advanceTimeBy(400) }
        rule.mainClock.advanceTimeBy(1_200)
        shotScreen(name)
    }
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun o01_story() = onbTo(onbApp(), 0, "o01_story")
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun o02_try() = onbTo(onbApp(), 1, "o02_try")
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun o03_safe() = onbTo(onbApp(), 2, "o03_safe")
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun o04_offer() = onbTo(onbApp(), 0, "o04_offer", skip = true)
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun o04_offer_dark() = onbTo(onbApp(dark = true), 0, "o04_offer_dark", skip = true)
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun o05_access() = onbApp().let { a ->
        onbTo(a, 0, "o05_tmp", skip = true); rule.onNodeWithTag("offer.free").performClick(); rule.mainClock.advanceTimeBy(600); shotScreen("o05_access")
    }
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun o06_today_tour() = makeTestApp(ctx, media = { FakeMediaLibrary(it, visual = true, count = 8, videos = 2).also { l -> lib = l } }) { a ->
        a.updateSettings { it.copy(todayTourSeen = false) }
    }.let { a -> rule.setContent { TestHost(a) { app.photomaid.navigation.RootContent(a) } }; waitTagX("coach.card"); rule.mainClock.advanceTimeBy(800); shotScreen("o06_today_tour_1")
        rule.onNodeWithTag("coach.next").performClick(); rule.mainClock.advanceTimeBy(800); shotScreen("o06_today_tour_2")
        rule.onNodeWithTag("coach.next").performClick(); rule.onNodeWithTag("coach.next").performClick(); rule.mainClock.advanceTimeBy(800); shotScreen("o06_today_tour_4") }
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun o07_feed_tour() = makeTestApp(ctx, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l } }) { a ->
        a.updateSettings { it.copy(feedTourSeen = false) }
    }.let { a -> feedPhotos(a); waitTagX("coach.card"); rule.mainClock.advanceTimeBy(800); shotScreen("o07_feed_tour_1")
        rule.onNodeWithTag("coach.next").performClick(); rule.onNodeWithTag("coach.next").performClick(); rule.mainClock.advanceTimeBy(800); shotScreen("o07_feed_tour_3") }
    @Test @Config(qualifiers = "+w393dp-h1300dp-xxhdpi") fun o08_pro_offer() = seededApp().let { shot("o08_pro_offer", it) { ProScreen(nav) } }

    private fun doneShot(name: String, dark: Boolean, kind: app.photomaid.core.cleanup.CleanupOperationKind, unknown: Int) =
        makeTestApp(ctx) { a -> if (dark) a.updateSettings { it.copy(appearance = AppearanceChoice.DARK) } }.let { a ->
            val d = app.photomaid.feature.cleanup.CleanupDone(app.photomaid.core.cleanup.CleanupSavings(42_500_000, unknown, 13), kind,
                "13 Fotos und Videos liegen jetzt im Android-Papierkorb.", false, 7, 1_240_000_000, unknown > 0)
            rule.setContent { TestHost(a) { app.photomaid.feature.cleanup.CleanupDoneView(d) {} } }
            rule.mainClock.advanceTimeBy(2_000); shotScreen(name)
        }
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun d10_done() = doneShot("d10_done", false, app.photomaid.core.cleanup.CleanupOperationKind.PHOTO_LIBRARY_DELETE, 0)
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun d11_done_dark_unknown() = doneShot("d11_done_dark_unknown", true, app.photomaid.core.cleanup.CleanupOperationKind.FILE_DELETE_PERMANENTLY, 2)

    // Sprachen (2026-10): Längen und RTL je Kernbildschirm. Arabisch mit ldrtl – Gestenpfeile bleiben physisch.
    private fun langFeed(name: String) = seededApp().let {
        shot(name, it, waitTag = "feed.media.ready") { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, {}, {}) }
    }
    @Test @Config(qualifiers = "+en-w393dp-h1200dp-xxhdpi") fun l_en_home() = home("l_en_home", homeApp(allDone = false, four = true))
    @Test @Config(qualifiers = "+en-w393dp-h852dp-xxhdpi") fun l_en_feed() = langFeed("l_en_feed")
    @Test @Config(qualifiers = "+en-w393dp-h852dp-xxhdpi") fun l_en_offer() = onbTo(onbApp(), 0, "l_en_offer", skip = true)
    @Test @Config(qualifiers = "+en-w393dp-h852dp-xxhdpi") fun l_en_story() = onbTo(onbApp(), 0, "l_en_story")
    @Test @Config(qualifiers = "+en-w393dp-h852dp-xxhdpi") fun l_en_done() = doneShot("l_en_done", false, app.photomaid.core.cleanup.CleanupOperationKind.PHOTO_LIBRARY_DELETE, 2)
    @Test @Config(qualifiers = "+en-w393dp-h1300dp-xxhdpi") fun l_en_settings() = seededApp().let { shot("l_en_settings", it) { SettingsScreen(nav) } }
    @Test @Config(qualifiers = "+ar-ldrtl-w393dp-h1200dp-xxhdpi") fun l_ar_home() = home("l_ar_home", homeApp(allDone = false, four = true))
    @Test @Config(qualifiers = "+ar-ldrtl-w393dp-h852dp-xxhdpi") fun l_ar_feed() = langFeed("l_ar_feed")
    @Test @Config(qualifiers = "+ar-ldrtl-w393dp-h852dp-xxhdpi") fun l_ar_offer() = onbTo(onbApp(), 0, "l_ar_offer", skip = true)
    @Test @Config(qualifiers = "+ar-ldrtl-w393dp-h852dp-xxhdpi") fun l_ar_story() = onbTo(onbApp(), 0, "l_ar_story")
    @Test @Config(qualifiers = "+ar-ldrtl-w393dp-h852dp-xxhdpi") fun l_ar_done() = doneShot("l_ar_done", false, app.photomaid.core.cleanup.CleanupOperationKind.PHOTO_LIBRARY_DELETE, 2)
    @Test @Config(qualifiers = "+ar-ldrtl-w393dp-h1300dp-xxhdpi") fun l_ar_settings() = seededApp().let { shot("l_ar_settings", it) { SettingsScreen(nav) } }
    @Test @Config(qualifiers = "+ru-w393dp-h1200dp-xxhdpi") fun l_ru_home() = home("l_ru_home", homeApp(allDone = false, four = true))
    @Test @Config(qualifiers = "+ru-w393dp-h852dp-xxhdpi") fun l_ru_feed() = langFeed("l_ru_feed")
    @Test @Config(qualifiers = "+ru-w393dp-h852dp-xxhdpi") fun l_ru_offer() = onbTo(onbApp(), 0, "l_ru_offer", skip = true)
    @Test @Config(qualifiers = "+ru-w393dp-h852dp-xxhdpi") fun l_ru_story() = onbTo(onbApp(), 0, "l_ru_story")
    @Test @Config(qualifiers = "+ru-w393dp-h852dp-xxhdpi") fun l_ru_done() = doneShot("l_ru_done", false, app.photomaid.core.cleanup.CleanupOperationKind.PHOTO_LIBRARY_DELETE, 2)
    @Test @Config(qualifiers = "+ru-w393dp-h1300dp-xxhdpi") fun l_ru_settings() = seededApp().let { shot("l_ru_settings", it) { SettingsScreen(nav) } }
    @Test @Config(qualifiers = "+ja-w393dp-h1200dp-xxhdpi") fun l_ja_home() = home("l_ja_home", homeApp(allDone = false, four = true))
    @Test @Config(qualifiers = "+ja-w393dp-h852dp-xxhdpi") fun l_ja_feed() = langFeed("l_ja_feed")
    @Test @Config(qualifiers = "+ja-w393dp-h852dp-xxhdpi") fun l_ja_offer() = onbTo(onbApp(), 0, "l_ja_offer", skip = true)
    @Test @Config(qualifiers = "+ja-w393dp-h852dp-xxhdpi") fun l_ja_story() = onbTo(onbApp(), 0, "l_ja_story")
    @Test @Config(qualifiers = "+ja-w393dp-h852dp-xxhdpi") fun l_ja_done() = doneShot("l_ja_done", false, app.photomaid.core.cleanup.CleanupOperationKind.PHOTO_LIBRARY_DELETE, 2)
    @Test @Config(qualifiers = "+ja-w393dp-h1300dp-xxhdpi") fun l_ja_settings() = seededApp().let { shot("l_ja_settings", it) { SettingsScreen(nav) } }
    @Test @Config(qualifiers = "+fr-w393dp-h1200dp-xxhdpi") fun l_fr_home() = home("l_fr_home", homeApp(allDone = false, four = true))
    @Test @Config(qualifiers = "+fr-w393dp-h852dp-xxhdpi") fun l_fr_feed() = langFeed("l_fr_feed")
    @Test @Config(qualifiers = "+fr-w393dp-h852dp-xxhdpi") fun l_fr_offer() = onbTo(onbApp(), 0, "l_fr_offer", skip = true)
    @Test @Config(qualifiers = "+fr-w393dp-h852dp-xxhdpi") fun l_fr_story() = onbTo(onbApp(), 0, "l_fr_story")
    @Test @Config(qualifiers = "+fr-w393dp-h852dp-xxhdpi") fun l_fr_done() = doneShot("l_fr_done", false, app.photomaid.core.cleanup.CleanupOperationKind.PHOTO_LIBRARY_DELETE, 2)
    @Test @Config(qualifiers = "+fr-w393dp-h1300dp-xxhdpi") fun l_fr_settings() = seededApp().let { shot("l_fr_settings", it) { SettingsScreen(nav) } }
    @Test @Config(qualifiers = "+pl-w393dp-h1200dp-xxhdpi") fun l_pl_home() = home("l_pl_home", homeApp(allDone = false, four = true))
    @Test @Config(qualifiers = "+pl-w393dp-h852dp-xxhdpi") fun l_pl_feed() = langFeed("l_pl_feed")
    @Test @Config(qualifiers = "+pl-w393dp-h852dp-xxhdpi") fun l_pl_offer() = onbTo(onbApp(), 0, "l_pl_offer", skip = true)
    @Test @Config(qualifiers = "+pl-w393dp-h852dp-xxhdpi") fun l_pl_story() = onbTo(onbApp(), 0, "l_pl_story")
    @Test @Config(qualifiers = "+pl-w393dp-h852dp-xxhdpi") fun l_pl_done() = doneShot("l_pl_done", false, app.photomaid.core.cleanup.CleanupOperationKind.PHOTO_LIBRARY_DELETE, 2)
    @Test @Config(qualifiers = "+pl-w393dp-h1300dp-xxhdpi") fun l_pl_settings() = seededApp().let { shot("l_pl_settings", it) { SettingsScreen(nav) } }

    // Ausbau 2026-10: Monate, Raster, Serie, Ausnahmen, Heute-Karten, Tasten, Beiseitegelegt.
    private fun expApp(pro: Boolean = false, dark: Boolean = false) = makeTestApp(ctx, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l
            val t = java.time.Instant.parse("2026-09-20T09:00:00Z")
            (0..2).forEach { i -> l.takenAt[i] = t.minusSeconds(i * 3L) }
            (3 until l.all.size).forEach { i -> l.takenAt[i] = java.time.Instant.parse("2026-08-10T10:00:00Z").minusSeconds(i * 86_400L * 9) }
        } }, purchase = if (pro) FakePurchaseGateway(PurchaseState.PURCHASED) else null) { a ->
        if (dark) a.updateSettings { it.copy(appearance = AppearanceChoice.DARK) }
        val s = ReviewSessionID()
        suspend fun act(i: Int, action: DecisionAction) {
            val ref = ItemReference.photo(lib.all[i].id)
            a.ledger.apply(DecisionCommand(item = ref, expectedRevision = a.ledger.record(ref).revision, action = action), s)
        }
        act(4, DecisionAction.Keep); act(5, DecisionAction.MarkForCleanup)
        act(6, DecisionAction.Later(s)); act(6, DecisionAction.Bookmark)
    }
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun n01_months() = expApp().let { shot("n01_months", it, waitTag = "months") { app.photomaid.feature.months.MonthsScreen(nav) } }
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun n01_months_dark() = expApp(dark = true).let { shot("n01_months_dark", it, waitTag = "months") { app.photomaid.feature.months.MonthsScreen(nav) } }
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun n02_grid() = expApp().let { a ->
        rule.setContent { TestHost(a) { app.photomaid.feature.grid.MediaGridScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, onClose = {}) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("grid.tile").fetchSemanticsNodes().size >= 3 }
        rule.onAllNodesWithTag("grid.tile")[0].performClick(); rule.onAllNodesWithTag("grid.tile")[1].performClick()
        rule.mainClock.advanceTimeBy(1_500); shotScreen("n02_grid")
    }
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun n03_series() = expApp(pro = true).let { a ->
        feedPhotos(a); waitTagX("action.series"); shotScreen("n03a_feed_series_button")
        rule.onNodeWithTag("action.series").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(500); waitTagX("series")
        rule.onAllNodesWithTag("series.check")[1].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.mainClock.advanceTimeBy(1_500); shotScreen("n03b_series_view")
    }
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun n04_excluded() = makeTestApp(ctx, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l } }) { a ->
        a.updateSettings { it.copy(excludedFolders = listOf("Pictures/Work/", "DCIM/WhatsApp/")) }
    }.let { shot("n04_excluded", it) { app.photomaid.feature.settings.ExcludedFoldersScreen(nav) } }
    @Test @Config(qualifiers = "+w393dp-h1100dp-xxhdpi") fun n05_today_progress() = expApp().let { a ->
        runBlocking { a.updateSettings { it.copy(continueMonth = "2026-09", lastSessionEndMs = java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()) } }
        shot("n05_today_progress", a, waitTag = "today.progress") { TodayScreen(nav) }
    }
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun n06_feed_buttons() = expApp().let { a ->
        runBlocking { a.updateSettings { it.copy(decisionButtons = true) } }
        shot("n06_feed_buttons", a, waitTag = "feed.media.ready") { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, {}, {}) }
    }
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun n07_later_bookmarked() = expApp().let { shot("n07_later_bookmarked", it) { LaterScreen(nav) } }
    @Test @Config(qualifiers = "+w393dp-h852dp-xxhdpi") fun n08_hub_months() = expApp().let { shot("n08_hub", it) { WithTabBar(AppTab.CLEANUP) { CleanUpHubScreen(nav) } } }

    // Editor (E1–E6). Visuelle Prüfung: Zuschneiden, Anpassen, Filter (Free mit Pro-Hinweis), Größe, Speichern-Blatt,
    // Video kürzen, Liste „Bearbeitet“, schmale Aktionsleiste (Teilen im ⋯-Menü).
    private fun editor(name: String, a: AppModel, i: Int = 0, after: () -> Unit = {}) {
        val id = lib.all[i].id
        a.editEngine.io = kotlinx.coroutines.Dispatchers.Main; a.editEngine.parallel = false
        rule.setContent { TestHost(a) { app.photomaid.feature.editor.EditorScreen(id, nav, onClose = {}) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("editor.stage").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithTag("trim.timeline").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        after()
        Thread.sleep(200); rule.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }
    private fun tap(tag: String) { rule.openMoreFor(tag) { rule.waitForIdle() }; rule.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); rule.waitForIdle() }

    @Test fun e01_editor_crop() = expApp(pro = true).let { editor("e01_editor_crop", it, i = 1) { tap("aspect.R4_5") } }
    @Test fun e02_editor_crop_dark() = expApp(pro = true, dark = true).let { editor("e02_editor_crop_dark", it, i = 1) { tap("editor.rotate") } }
    @Test fun e03_editor_adjust() = expApp(pro = true).let { editor("e03_editor_adjust", it, i = 1) { tap("tab.ADJUST"); tap("adjust.WARMTH") } }
    @Test fun e04_editor_filter_free() = expApp().let { editor("e04_editor_filter_free", it, i = 1) { tap("tab.FILTER"); tap("filter.MONO") } }
    @Test fun e05_editor_resize() = expApp(pro = true).let { editor("e05_editor_resize", it, i = 1) { tap("tab.RESIZE") } }
    @Test fun e06_editor_save_sheet() = expApp(pro = true).let { editor("e06_editor_save_sheet", it, i = 1) {
        tap("editor.rotate"); tap("editor.save")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("editor.sheet.save").fetchSemanticsNodes().isNotEmpty() }
    } }
    @Test fun e07_editor_save_sheet_pro() = expApp().let { editor("e07_editor_save_sheet_pro", it, i = 1) {
        tap("editor.rotate"); tap("tab.FILTER"); tap("filter.WARM"); tap("editor.save")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("editor.sheet.pro").fetchSemanticsNodes().isNotEmpty() }
    } }
    @Test fun e08_editor_trim() = expApp(pro = true).let { a -> editor("e08_editor_trim", a, i = lib.all.indexOfFirst { it.kind == app.photomaid.core.model.MediaKind.VIDEO }) }
    @Test @Config(qualifiers = "ar-w393dp-h852dp-xxhdpi") fun e09_editor_rtl() = expApp(pro = true).let { editor("e09_editor_rtl", it, i = 1) { tap("tab.ADJUST") } }
    @Test fun e10_edits_list() = expApp().let { a ->
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val x = lib.all[1]; val y = lib.all[3]
            app.photomaid.feature.editor.EditSaver.savePhoto(a, x, app.photomaid.core.edit.EditRecipe(quarterTurns = 1), a.editEngine.loadPreview(x.uri, x.mimeType).full,
                app.photomaid.core.edit.SaveMode.REPLACE, false)
            app.photomaid.feature.editor.EditSaver.savePhoto(a, y, app.photomaid.core.edit.EditRecipe(filter = app.photomaid.core.edit.FilterId.SEPIA), a.editEngine.loadPreview(y.uri, y.mimeType).full,
                app.photomaid.core.edit.SaveMode.COPY, false)
        }
        shot("e10_edits_list", a, waitTag = "edits") { app.photomaid.feature.editor.EditsScreen(nav) }
    }
    @Test @Config(qualifiers = "w320dp-h640dp-xxhdpi") fun e11_feed_actions_320() = expApp().let { a ->
        shot("e11_feed_actions_320", a, waitTag = "feed.media.ready") { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, {}, {}) }
    }

    @Test fun e12_editor_light_histogram() = expApp(pro = true).let { editor("e12_editor_light_histogram", it, i = 1) {
        tap("tab.ADJUST"); tap("adjust.SHADOWS")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("editor.menu").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(androidx.compose.ui.test.hasTestTag("editor.slider") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("slider.SHADOWS")))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(60f) }
        tap("editor.histogram.toggle")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("editor.histogram").fetchSemanticsNodes().isNotEmpty() }
    } }
    @Test fun e13_editor_filters_presets() = expApp(pro = true).let { editor("e13_editor_filters_presets", it, i = 1) { tap("tab.FILTER"); tap("filter.WARM") } }

    private fun swipeOn(tag: String, from: androidx.compose.ui.geometry.Offset, to: androidx.compose.ui.geometry.Offset) {
        rule.onNodeWithTag(tag).performTouchInput {
            down(androidx.compose.ui.geometry.Offset(width * from.x, height * from.y))
            for (i in 1..10) moveTo(androidx.compose.ui.geometry.Offset(width * (from.x + (to.x - from.x) * i / 10), height * (from.y + (to.y - from.y) * i / 10)))
            up()
        }
        rule.waitForIdle()
    }
    @Test fun e14_editor_markup() = expApp().let { editor("e14_editor_markup", it, i = 1) {
        tap("tab.MARKUP"); rule.waitUntil(10_000) { rule.onAllNodesWithTag("markup.canvas").fetchSemanticsNodes().isNotEmpty() }
        tap("mk.shape"); swipeOn("markup.canvas", androidx.compose.ui.geometry.Offset(0.2f, 0.7f), androidx.compose.ui.geometry.Offset(0.55f, 0.45f))
        tap("mk.redact"); swipeOn("markup.canvas", androidx.compose.ui.geometry.Offset(0.6f, 0.62f), androidx.compose.ui.geometry.Offset(0.85f, 0.7f))
        tap("mk.sticker"); rule.waitUntil(10_000) { rule.onAllNodesWithTag("sticker.HEART").fetchSemanticsNodes().isNotEmpty() }; tap("sticker.HEART")
    } }
    @Test fun e15_editor_text_sheet() = expApp().let { editor("e15_editor_text_sheet", it, i = 1) {
        tap("tab.MARKUP"); rule.waitUntil(10_000) { rule.onAllNodesWithTag("markup.canvas").fetchSemanticsNodes().isNotEmpty() }
        tap("mk.text"); rule.waitUntil(10_000) { rule.onAllNodesWithTag("text.sheet").fetchSemanticsNodes().isNotEmpty() }
        tap("ts.PILL")
    } }
    @Test fun e16_editor_sticker_sheet() = expApp().let { editor("e16_editor_sticker_sheet", it, i = 1) {
        tap("tab.MARKUP"); tap("mk.sticker"); rule.waitUntil(10_000) { rule.onAllNodesWithTag("sticker.sheet").fetchSemanticsNodes().isNotEmpty() }
    } }
    @Test fun e17_editor_frame() = expApp(pro = true).let { editor("e17_editor_frame", it, i = 1) {
        tap("tab.FRAME"); tap("frame.aspect.0.8")
        rule.onNode(androidx.compose.ui.test.hasTestTag("editor.slider") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("slider.FRAME")))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(6f) }
    } }

    // Studio P3: Collage (Raster, Frei, Streifen), Speichern-Blatt, Studio-Tab, Medienauswahl.
    private fun photoIds(n: Int) = lib.all.filter { it.kind == app.photomaid.core.model.MediaKind.PHOTO }.take(n).map { it.id }
    private fun collage(name: String, a: AppModel, p: app.photomaid.core.studio.CollageProject, after: () -> Unit = {}) {
        a.editEngine.io = kotlinx.coroutines.Dispatchers.Main; a.editEngine.parallel = false
        rule.setContent { TestHost(a) { app.photomaid.feature.studio.CollageScreen(app.photomaid.feature.studio.CollageRequest("s", p, false), nav, pick = {}, onClose = {}) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("collage.canvas").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle(); after(); Thread.sleep(150); rule.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }
    @Test fun c01_collage_grid() = expApp().let { a -> collage("c01_collage_grid", a, app.photomaid.core.studio.CollageProject.create("c", photoIds(3))) { rule.onAllNodesWithTag("collage.cell")[1].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); tap("ctab.PHOTO") } }
    @Test fun c02_collage_grid_dark_pro() = expApp(pro = true, dark = true).let { a ->
        collage("c02_collage_grid_dark", a, app.photomaid.core.studio.CollageProject.create("c", photoIds(5)).withTemplate("big:l")) { tap("ctab.SPACING") } }
    @Test fun c03_collage_free() = expApp(pro = true).let { a ->
        collage("c03_collage_free", a, app.photomaid.core.studio.CollageProject.create("c", photoIds(4), app.photomaid.core.studio.CollageKind.FREE)) }
    @Test fun c04_collage_strip() = expApp().let { a ->
        collage("c04_collage_strip", a, app.photomaid.core.studio.CollageProject.create("c", photoIds(3), app.photomaid.core.studio.CollageKind.STRIP)) }
    @Test fun c05_collage_free_tier_pro_hint() = expApp().let { a ->
        collage("c05_collage_pro_hint", a, app.photomaid.core.studio.CollageProject.create("c", photoIds(6))) { tap("ctab.BACKGROUND"); tap("cbg.blur") } }
    @Test fun c06_collage_save_sheet() = expApp().let { a ->
        collage("c06_collage_save_sheet", a, app.photomaid.core.studio.CollageProject.create("c", photoIds(2))) {
            tap("collage.save"); rule.waitUntil(10_000) { rule.onAllNodesWithTag("collage.sheet.save").fetchSemanticsNodes().isNotEmpty() } } }
    @Test fun c07_studio_tab() = expApp().let { a ->
        runBlocking { a.drafts.put(app.photomaid.feature.studio.Draft("d1", 1_700_000_000_000L, collage = app.photomaid.core.studio.CollageProject.create("d1", photoIds(3))), null, 3) }
        shot("c07_studio_tab", a, waitTag = "studio.draft") { WithTabBar(AppTab.STUDIO) { app.photomaid.feature.studio.StudioScreen(nav) } }
    }
    @Test fun c08_picker() = expApp().let { a ->
        shot("c08_picker", a, waitTag = "picker.grid") {
            app.photomaid.feature.studio.MediaPickerScreen(app.photomaid.feature.studio.PickRequest("p", min = 2, max = 20, title = R.string.studio_pick_collage,
                limitNote = "Free: bis 4 Fotos · Pro: bis 20 und frei angeordnet") {}, onClose = {})
        }
        rule.onAllNodesWithTag("picker.tile")[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.onAllNodesWithTag("picker.tile")[2].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle(); rule.onRoot().captureRoboImage("build/outputs/roborazzi/c08_picker_selected.png")
    }

    // P4: Perspektive, Farbmischer, Retusche (Kreis, Pinsel, Rote Augen).
    private fun sliderTo(parent: String, v: Float) = rule.onNode(androidx.compose.ui.test.hasTestTag("editor.slider") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(parent)))
        .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(v) }
    private fun tapAt(tag: String, x: Float, y: Float) { rule.onNodeWithTag(tag).performTouchInput { down(androidx.compose.ui.geometry.Offset(width * x, height * y)); up() }; rule.waitForIdle() }
    @Test fun r01_perspective() = expApp().let { editor("r01_perspective", it, i = 1) {
        tap("crop.mode.persp"); rule.waitUntil(10_000) { rule.onAllNodesWithTag("persp.stage").fetchSemanticsNodes().isNotEmpty() }
        sliderTo("persp.v", 45f) } }
    @Test fun r02_perspective_result() = expApp().let { editor("r02_perspective_result", it, i = 1) {
        tap("crop.mode.persp"); rule.waitUntil(10_000) { rule.onAllNodesWithTag("persp.stage").fetchSemanticsNodes().isNotEmpty() }
        sliderTo("persp.v", 45f); tap("crop.mode.crop") } }
    @Test fun r03_mixer() = expApp(pro = true).let { editor("r03_mixer", it, i = 1) {
        tap("tab.ADJUST"); tap("group.MIX"); tap("band.BLUE"); sliderTo("mix.SATURATION", -80f); sliderTo("mix.LUMINANCE", -30f) } }
    @Test fun r04_retouch_radial() = expApp(pro = true).let { editor("r04_retouch_radial", it, i = 1) {
        tap("tab.RETOUCH"); rule.waitUntil(10_000) { rule.onAllNodesWithTag("retouch.canvas").fetchSemanticsNodes().isNotEmpty() }
        tapAt("retouch.canvas", 0.5f, 0.55f)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("local.0").fetchSemanticsNodes().isNotEmpty() }
        sliderTo("local.slider", 70f) } }
    @Test fun r05_retouch_mask_dark() = expApp(pro = true, dark = true).let { editor("r05_retouch_mask_dark", it, i = 1) {
        tap("tab.RETOUCH"); rule.waitUntil(10_000) { rule.onAllNodesWithTag("retouch.canvas").fetchSemanticsNodes().isNotEmpty() }
        tap("rtool.LINEAR"); tapAt("retouch.canvas", 0.5f, 0.3f)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("local.0").fetchSemanticsNodes().isNotEmpty() } } }
    @Test fun r06_red_eye() = expApp().let { editor("r06_red_eye", it, i = 1) {
        tap("tab.RETOUCH"); rule.waitUntil(10_000) { rule.onAllNodesWithTag("retouch.canvas").fetchSemanticsNodes().isNotEmpty() }
        tap("rtool.RED_EYE"); tapAt("retouch.canvas", 0.4f, 0.4f); tapAt("retouch.canvas", 0.6f, 0.4f) } }

    // P5: Video-Editor (Vorschau ist in Robolectric schwarz; geprüft werden Bedienfelder und Zeitleiste).
    private fun video(name: String, a: AppModel, after: () -> Unit = {}) {
        a.videoExporter = FakeVideoEditExporter(ctx); a.exports.useService = false
        editor(name, a, i = lib.all.indexOfFirst { it.kind == app.photomaid.core.model.MediaKind.VIDEO }, after)
    }
    private fun prog(tag: String, v: Float) { rule.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(v) }; rule.waitForIdle() }
    @Test fun v01_video_cut() = expApp(pro = true).let { video("v01_video_cut", it) { prog("trim.start", 2_000f); tap("cut.start"); prog("cut.draft.start", 9_000f); tap("cut.confirm"); tap("cut.start") } }
    @Test fun v02_video_speed() = expApp(pro = true).let { video("v02_video_speed", it) { tap("tab.SPEED"); tap("speed.1.5") } }
    @Test fun v03_video_audio() = expApp(pro = true).let { video("v03_video_audio", it) { tap("tab.AUDIO"); tap("fade.1000") } }
    @Test fun v04_video_format() = expApp(pro = true, dark = true).let { video("v04_video_format", it) { tap("tab.FORMAT"); tap("vaspect.0.5625"); tap("vformat.fill") } }
    @Test fun v05_video_save_sheet() = expApp(pro = true).let { video("v05_video_save_sheet", it) { tap("tab.SPEED"); tap("speed.2.0"); tap("editor.save")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("vprofile.SMALL").fetchSemanticsNodes().isNotEmpty() } } }
    @Test fun v06_video_text() = expApp(pro = true).let { video("v06_video_text", it) { tap("tab.MARKUP")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("markup.canvas").fetchSemanticsNodes().isNotEmpty() }
        tap("mk.sticker"); rule.waitUntil(10_000) { rule.onAllNodesWithTag("sticker.HEART").fetchSemanticsNodes().isNotEmpty() }; tap("sticker.HEART") } }

    // G1: Tab „Fotos“ (Zeitachse, Filter, Auswahl), Betrachter, Info, Tabbar mit 5 Tabs.
    @Test fun g01_library_days() = expApp().let { shot("g01_library_days", it, waitTag = "lib.tile") { WithTabBar(AppTab.LIBRARY) { app.photomaid.feature.library.LibraryScreen(nav) } } }
    @Test fun g02_library_months_dark() = expApp(dark = true).let { a ->
        shot("g02_library_months_dark", a, waitTag = "lib.tile") { WithTabBar(AppTab.LIBRARY) { app.photomaid.feature.library.LibraryScreen(nav) } }
        tap("lib.level.MONTH"); rule.waitForIdle(); rule.onRoot().captureRoboImage("build/outputs/roborazzi/g02_library_months_dark.png")
        tap("lib.level.YEAR"); rule.waitForIdle(); rule.onRoot().captureRoboImage("build/outputs/roborazzi/g03_library_years_dark.png")
    }
    @Test fun g04_library_selection() = expApp().let { a ->
        shot("g04_library_selection", a, waitTag = "lib.tile") { WithTabBar(AppTab.LIBRARY) { app.photomaid.feature.library.LibraryScreen(nav) } }
        rule.onAllNodesWithTag("lib.tile")[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        rule.onAllNodesWithTag("lib.tile")[2].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle(); rule.onRoot().captureRoboImage("build/outputs/roborazzi/g04_library_selection.png")
    }
    @Test fun g05_viewer() = expApp().let { a ->
        val list = lib.all.sortedByDescending { it.creationDate }
        shot("g05_viewer", a, waitTag = "viewer.index") { app.photomaid.feature.library.ViewerScreen(app.photomaid.feature.library.ViewerRequest("v", list, 1), nav, onClose = {}) }
        tap("viewer.more"); rule.waitUntil(5_000) { rule.onAllNodesWithTag("viewer.info").fetchSemanticsNodes().isNotEmpty() }
        tap("viewer.info"); rule.waitUntil(5_000) { rule.onAllNodesWithTag("viewer.infosheet").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle(); captureScreenRoboImage("build/outputs/roborazzi/g06_viewer_info.png")
    }
    @Test @Config(qualifiers = "de-w320dp-h640dp-xxhdpi") fun g07_tabbar_320() = expApp().let { shot("g07_tabbar_320", it, waitTag = "lib.tile") { WithTabBar(AppTab.LIBRARY) { app.photomaid.feature.library.LibraryScreen(nav) } } }
    @Test @Config(qualifiers = "fr-w320dp-h640dp-xxhdpi") fun g08_tabbar_320_fr() = expApp().let { shot("g08_tabbar_320_fr", it, waitTag = "lib.tile") { WithTabBar(AppTab.TODAY) { app.photomaid.feature.library.LibraryScreen(nav) } } }
    @Test @Config(qualifiers = "uk-w320dp-h640dp-xxhdpi") fun g09_tabbar_320_uk() = expApp().let { shot("g09_tabbar_320_uk", it, waitTag = "lib.tile") { WithTabBar(AppTab.CLEANUP) { app.photomaid.feature.library.LibraryScreen(nav) } } }

    // G2: Ordnerbaum, Ordner mit Unterordnern, Favoriten je Ordner, Gruppen, neuer Ordner, Ausgeblendet.
    private fun folderApp(dark: Boolean = false) = expApp(dark = dark).also {
        (0..2).forEach { i -> lib.paths[lib.all[i].id] = "Pictures/Urlaub/" }
        lib.paths[lib.all[3].id] = "Pictures/Urlaub/Strand/"; lib.paths[lib.all[4].id] = "Pictures/Urlaub/Berge/"
        lib.paths[lib.all[5].id] = "Pictures/Screenshots/"; lib.paths[lib.all[6].id] = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"
        lib.favorites[lib.all[0].id] = true; lib.favorites[lib.all[3].id] = true; lib.favorites[lib.all[7].id] = true
        runBlocking { app.photomaid.feature.library.saveGroup(it, app.photomaid.core.library.FolderGroup("g1", "Reisen", listOf("Pictures/Urlaub/", "DCIM/Camera/"))) }
    }
    private fun folders(name: String, a: AppModel, after: () -> Unit = {}) {
        shot(name, a, waitTag = "lib.folder.row") { WithTabBar(AppTab.LIBRARY) { app.photomaid.feature.library.LibraryScreen(nav, app.photomaid.feature.library.LibraryMode.FOLDERS) } }
        after(); rule.waitForIdle(); rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
    @Test fun l01_folders() = folderApp().let { folders("l01_folders", it) }
    @Test fun l02_folders_expanded_dark() = folderApp(dark = true).let { folders("l02_folders_expanded_dark", it) {
        rule.onAllNodesWithTag("lib.folder.expand").fetchSemanticsNodes().indices.reversed().forEach { i ->
            rule.onAllNodesWithTag("lib.folder.expand")[i].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); rule.waitForIdle() } } }
    @Test fun l03_folder_screen() = folderApp().let { a ->
        shot("l03_folder_screen", a, waitTag = "lib.tile") { app.photomaid.feature.library.FolderScreen(app.photomaid.feature.library.FolderTarget.Path("Pictures/Urlaub/"), nav, onClose = {}) }
        tap("folder.sort"); rule.waitForIdle(); captureScreenRoboImage("build/outputs/roborazzi/l04_folder_sort_menu.png")
    }
    @Test fun l05_favorites_by_folder() = folderApp().let { shot("l05_favorites_by_folder", it, waitTag = "lib.tile") {
        WithTabBar(AppTab.LIBRARY) { app.photomaid.feature.library.LibraryScreen(nav, app.photomaid.feature.library.LibraryMode.FAVORITES) } } }
    @Test fun l06_group_sheet() = folderApp().let { folders("l06_group_sheet", it) { tap("lib.groups.new")
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("lib.groupsheet").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("lib.group.pick")[1].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); rule.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/l06_group_sheet.png") } }
    @Test fun l07_new_folder() = folderApp().let { folders("l07_new_folder", it) { tap("lib.folders.new")
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("lib.newfolder").fetchSemanticsNodes().isNotEmpty() }
        captureScreenRoboImage("build/outputs/roborazzi/l07_new_folder.png") } }
    @Test fun l08_hidden() = folderApp().let { a ->
        runBlocking { app.photomaid.feature.library.setFolderHidden(a, "Pictures/Screenshots/", true)
            app.photomaid.feature.library.setMediaHidden(a, listOf(lib.all[1].id, lib.all[2].id), true) }
        shot("l08_hidden", a, waitTag = "lib.tile") { app.photomaid.feature.library.HiddenScreen(nav) }
    }
    @Test @Config(qualifiers = "ar-w393dp-h852dp-xxhdpi") fun l09_folders_rtl() = folderApp().let { folders("l09_folders_rtl", it) }
    @Test @Config(qualifiers = "de-w320dp-h640dp-xxhdpi") fun l10_folders_320() = folderApp().let { folders("l10_folders_320", it) }
    @Test fun l11_folders_font200() = folderApp().let { RuntimeEnvironment.setFontScale(2.0f); folders("l11_folders_font200", it) }

    // G3: Diashow und Video-Leiste im Betrachter.
    @Test fun sl01_slideshow() = folderApp().let { a ->
        val list = lib.all.filter { it.kind != app.photomaid.core.model.MediaKind.VIDEO }
        shot("sl01_slideshow", a, waitTag = "slideshow.controls") { app.photomaid.feature.library.SlideshowScreen(app.photomaid.feature.library.SlideshowRequest("s", list), onClose = {}) }
        rule.mainClock.autoAdvance = false
        repeat(40) { shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(25) }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/sl01_slideshow.png")
    }
    @Test fun sl02_slideshow_settings_dark() = folderApp(dark = true).let { a ->
        runBlocking { a.updateSettings { it.copy(slideshow = app.photomaid.core.library.SlideshowSettings(order = app.photomaid.core.library.SlideshowOrder.ALBUMS_RANDOM)) } }
        shot("sl02_slideshow_settings_dark", a, waitTag = "slideshow.controls") { app.photomaid.feature.library.SlideshowScreen(app.photomaid.feature.library.SlideshowRequest("s", lib.all), onClose = {}) }
        tap("slideshow.settings"); rule.waitUntil(5_000) { rule.onAllNodesWithTag("slideshow.sheet").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle(); captureScreenRoboImage("build/outputs/roborazzi/sl02_slideshow_settings_dark.png")
    }
    @Test fun sl03_viewer_video_bar() = expApp().let { a ->
        val list = lib.all.sortedByDescending { it.creationDate }
        val vi = list.indexOfFirst { it.kind == app.photomaid.core.model.MediaKind.VIDEO }
        shot("sl03_viewer_video_bar", a, waitTag = "viewer.video") { app.photomaid.feature.library.ViewerScreen(app.photomaid.feature.library.ViewerRequest("v", list, vi), nav, onClose = {}) }
    }
    @Test @Config(qualifiers = "ar-w393dp-h852dp-xxhdpi") fun sl04_viewer_video_bar_rtl() = expApp().let { a ->
        val list = lib.all.sortedByDescending { it.creationDate }
        val vi = list.indexOfFirst { it.kind == app.photomaid.core.model.MediaKind.VIDEO }
        shot("sl04_viewer_video_bar_rtl", a, waitTag = "viewer.video") { app.photomaid.feature.library.ViewerScreen(app.photomaid.feature.library.ViewerRequest("v", list, vi), nav, onClose = {}) }
    }

    // Gerätegröße realme RMX3933 (720×1600 ≈ 360×800 dp): Video-Editor je Werkzeug.
    @Test @Config(qualifiers = "de-w360dp-h800dp-xhdpi") fun rv01_trim() = expApp(pro = true).let { video("rv01_trim", it) }
    @Test @Config(qualifiers = "de-w360dp-h800dp-xhdpi") fun rv02_filter() = expApp(pro = true).let { video("rv02_filter", it) { tap("tab.FILTER") } }
    @Test @Config(qualifiers = "de-w360dp-h800dp-xhdpi") fun rv03_adjust() = expApp(pro = true).let { video("rv03_adjust", it) { tap("tab.ADJUST") } }
    @Test @Config(qualifiers = "de-w360dp-h800dp-xhdpi") fun rv04_mix() = expApp(pro = true).let { video("rv04_mix", it) { tap("tab.ADJUST")
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag("group.MIX")).fetchSemanticsNodes().takeIf { n -> n.isNotEmpty() }?.let { tap("group.MIX") } } }
    @Test @Config(qualifiers = "de-w360dp-h800dp-xhdpi") fun rv05_audio() = expApp(pro = true).let { video("rv05_audio", it) { tap("tab.AUDIO") } }
    @Test @Config(qualifiers = "de-w360dp-h800dp-xhdpi") fun rv06_free_filter() = expApp().let { video("rv06_free_filter", it) { tap("tab.FILTER") } }
    @Test @Config(qualifiers = "de-w360dp-h800dp-xhdpi") fun rp01_photo_mix() = expApp(pro = true).let { editor("rp01_photo_mix", it, i = 1) { tap("tab.ADJUST")
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag("group.MIX")).fetchSemanticsNodes().takeIf { n -> n.isNotEmpty() }?.let { tap("group.MIX") } } }

    // G4: Android-Papierkorb mit Restzeit, PhotoMaid-Papierkorb mit Sortierung.
    @Test fun t01_system_trash() = expApp().let { a ->
        lib.all.take(5).forEachIndexed { i, m -> lib.trashed += m.id; lib.removed += m.id; lib.trashExpiry[m.id] = java.time.Instant.now().plus(java.time.Duration.ofDays(listOf(2L, 9L, 17L, 25L, 29L)[i])) }
        shot("t01_system_trash", a, waitTag = "systrash.tile") { app.photomaid.feature.cleanup.SystemTrashScreen(nav) }
        rule.onAllNodesWithTag("systrash.tile")[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle(); rule.onRoot().captureRoboImage("build/outputs/roborazzi/t02_system_trash_selected.png")
    }
    @Test fun t03_basket_sort_dark() = expApp(dark = true).also { a -> runBlocking { lib.all.take(3).forEach { m -> a.decide(m.reference, DecisionAction.MarkForCleanup) } } }.let { shot("t03_basket_sort_dark", it, waitTag = "basket.sort") { CleanupBasketScreen(nav, showsDone = false) } }

    // P6: Video zusammenfügen / Diashow als Video.
    private fun movie(name: String, a: AppModel, slideshow: Boolean = false, after: () -> Unit = {}) {
        val list = if (slideshow) lib.all.filter { it.kind != app.photomaid.core.model.MediaKind.VIDEO } else lib.all.take(5)
        shot(name, a, waitTag = "movie.clip") { app.photomaid.feature.studio.MovieScreen(app.photomaid.feature.studio.MovieRequest("m", app.photomaid.feature.studio.movieFrom(list, slideshow), slideshow), nav, onClose = {}) }
        after(); rule.waitForIdle(); captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }
    @Test fun mv01_movie_clips() = expApp(pro = true).let { movie("mv01_movie_clips", it) }
    @Test fun mv02_movie_format_dark() = expApp(pro = true, dark = true).let { movie("mv02_movie_format_dark", it) { tap("movie.tab.FORMAT") } }
    @Test fun mv03_movie_music() = expApp(pro = true).let { movie("mv03_movie_music", it) { tap("movie.tab.MUSIC") } }
    @Test @Config(qualifiers = "de-w360dp-h800dp-xhdpi") fun mv04_slideshow_free_360() = expApp().let { movie("mv04_slideshow_free_360", it, slideshow = true) }
    @Test fun mv05_studio() = expApp().let { shot("mv05_studio", it) { WithTabBar(AppTab.STUDIO) { app.photomaid.feature.studio.StudioScreen(nav) } } }

    // P7: Werkzeuge.
    private fun tool(name: String, a: AppModel, kind: app.photomaid.feature.studio.ToolKind, ids: List<String>, wait: String = "tools.count") {
        shot(name, a, waitTag = wait) { app.photomaid.feature.studio.ToolsScreen(app.photomaid.feature.studio.ToolRequest("t", kind, ids), nav, onClose = {}) }
    }
    @Test fun tl01_resize() = expApp().let { tool("tl01_resize", it, app.photomaid.feature.studio.ToolKind.RESIZE, lib.all.filter { m -> m.kind != app.photomaid.core.model.MediaKind.VIDEO }.map { m -> m.id }) }
    @Test fun tl02_stitch_preview() = expApp(pro = true).let { tool("tl02_stitch_preview", it, app.photomaid.feature.studio.ToolKind.STITCH, lib.all.filter { m -> m.kind != app.photomaid.core.model.MediaKind.VIDEO }.take(2).map { m -> m.id }, wait = "tools.preview") }
    @Test fun tl03_gif_free_dark() = expApp(dark = true).let { tool("tl03_gif_free_dark", it, app.photomaid.feature.studio.ToolKind.GIF, lib.all.filter { m -> m.kind != app.photomaid.core.model.MediaKind.VIDEO }.take(4).map { m -> m.id }, wait = "tools.preview") }
    @Test @Config(qualifiers = "de-w360dp-h800dp-xhdpi") fun tl04_convert_360() = expApp().let { tool("tl04_convert_360", it, app.photomaid.feature.studio.ToolKind.CONVERT, lib.all.take(3).map { m -> m.id }) }
    @Test fun tl05_studio_tools() = expApp().let { a ->
        shot("tl05_studio_tools_top", a) { WithTabBar(AppTab.STUDIO) { app.photomaid.feature.studio.StudioScreen(nav) } }
        rule.onNodeWithTag("studio.tool.GIF").performScrollTo(); rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/tl05_studio_tools.png")
    }
}
