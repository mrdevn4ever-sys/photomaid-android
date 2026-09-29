package app.photomaid

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
import app.photomaid.feature.organize.OrganizeScreen
import app.photomaid.feature.pro.ProScreen
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.feature.rules.RulesScreen
import app.photomaid.feature.settings.SettingsScreen
import app.photomaid.feature.storage.LargeFilesScreen
import app.photomaid.feature.storage.StorageInsightsScreen
import app.photomaid.feature.today.TodayScreen
import app.photomaid.media.MediaCategory
import app.photomaid.navigation.GlassTabBar
import app.photomaid.navigation.AppTab
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
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
@Config(qualifiers = "w393dp-h852dp-xxhdpi")
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
    @Test fun s06b_organize() = seededApp().let { shot("06b_organize", it) { WithTabBar(AppTab.ORGANIZE) { OrganizeScreen(nav) } } }
    @Test fun s07_files() = seededApp().let { shot("07_files", it) { FileBrowserScreen(nav) } }
    @Test fun s08_large() = seededApp().let { shot("08_large_files", it) { LargeFilesScreen(nav) } }
    @Test fun s09_duplicates() = seededApp().let { shot("09_duplicates_free", it) { DuplicatesScreen(nav) } }
    @Test fun s10_storage() = seededApp().let { shot("10_storage", it) { StorageInsightsScreen(nav) } }
    @Test fun s11_later() = seededApp().let { shot("11_later", it) { LaterScreen(nav) } }
    @Test fun s12_rules() = seededApp().let { shot("12_rules", it) { RulesScreen(nav) } }
    @Test fun s13_history() = seededApp().let { shot("13_history", it) { WithTabBar(AppTab.HISTORY) { HistoryScreen(nav) } } }
    @Test fun s13_history_dark() = seededApp(dark = true).let { shot("13_history_dark", it) { WithTabBar(AppTab.HISTORY) { HistoryScreen(nav) } } }
    @Test fun s14_pro() = seededApp().let { shot("14_pro", it) { ProScreen(nav) } }
    @Test fun s14_pro_active() = seededApp(pro = true).let { shot("14_pro_active", it) { ProScreen(nav) } }
    @Test fun s15_settings() = seededApp().let { shot("15_settings", it) { SettingsScreen(nav) } }
    @Test fun s15_settings_dark() = seededApp(dark = true).let { shot("15_settings_dark", it) { SettingsScreen(nav) } }

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
    @Test @Config(qualifiers = "w320dp-h568dp-xhdpi") fun m_small_phone_feed() = seededApp().let {
        shot("m_small_phone_feed", it, waitTag = "feed.media.ready") { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, {}, {}) }
    }
    @Test @Config(qualifiers = "w320dp-h568dp-xhdpi") fun m_small_phone_today() = seededApp().let { shot("m_small_phone_today", it) { WithTabBar(AppTab.TODAY) { TodayScreen(nav) } } }
    @Test @Config(qualifiers = "w360dp-h840dp-xxhdpi") fun m_fold_cover_today() = seededApp().let { shot("m_fold_cover_today", it) { WithTabBar(AppTab.TODAY) { TodayScreen(nav) } } }
    @Test @Config(qualifiers = "w673dp-h841dp-xhdpi") fun m_fold_open_feed() = seededApp().let {
        shot("m_fold_open_feed", it, waitTag = "feed.media.ready") { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, {}, {}) }
    }
    @Test @Config(qualifiers = "w600dp-h960dp-xhdpi") fun m_tablet8_today() = seededApp().let { shot("m_tablet8_today", it) { WithTabBar(AppTab.TODAY) { TodayScreen(nav) } } }
    @Test @Config(qualifiers = "w834dp-h1194dp-xhdpi") fun m_tablet11_basket() = seededApp().let { shot("m_tablet11_basket", it) { CleanupBasketScreen(nav, showsDone = false) } }
    @Test fun m_font_150_today() = seededApp().let { RuntimeEnvironment.setFontScale(1.5f); shot("m_font150_today", it) { WithTabBar(AppTab.TODAY) { TodayScreen(nav) } } }
    @Test fun m_font_200_feed() = seededApp().let {
        RuntimeEnvironment.setFontScale(2.0f)
        shot("m_font200_feed", it, waitTag = "feed.media.ready") { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, {}, {}) }
    }
    @Test fun m_font_200_settings() = seededApp().let { RuntimeEnvironment.setFontScale(2.0f); shot("m_font200_settings", it) { SettingsScreen(nav) } }
}
