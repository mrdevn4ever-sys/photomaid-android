package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.model.MediaKind
import app.photomaid.core.studio.CollageProject
import app.photomaid.design.AppearanceChoice
import app.photomaid.feature.editor.EditorScreen
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.feature.review.SeriesCompareDialog
import app.photomaid.feature.studio.CollageRequest
import app.photomaid.feature.studio.CollageScreen
import app.photomaid.feature.studio.ToolKind
import app.photomaid.feature.studio.ToolRequest
import app.photomaid.feature.studio.ToolsScreen
import app.photomaid.media.MediaCategory
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Prüflog R1.10 (Issue #3): Screenshot-Raster der in R1 geänderten Bildschirme → build/outputs/roborazzi/r1_*. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RasterShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()
    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val gen = mutableIntStateOf(0)
    private var shown = false

    private fun pump(n: Int = 80) {
        rule.mainClock.autoAdvance = false
        repeat(n) { shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(5) }
        rule.mainClock.autoAdvance = true
    }

    private fun run(variant: String, dark: Boolean = false) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l } },
            purchase = FakePurchaseGateway(PurchaseState.PURCHASED)) { a -> if (dark) a.updateSettings { it.copy(appearance = AppearanceChoice.DARK) } }
        app.editEngine.io = Dispatchers.Main; app.editEngine.parallel = false
        app.videoTrimmer = FakeVideoTrimmer(rule.activity)
        val photos = lib.all.filter { it.kind != MediaKind.VIDEO }
        val video = lib.all.first { it.kind == MediaKind.VIDEO }
        val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
            "feed_video" to { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.VIDEOS)), nav, onClose = {}, openBasket = {}) },
            "feed_photo" to { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, onClose = {}, openBasket = {}) },
            "editor_photo" to { EditorScreen(photos[0].id, nav, onClose = {}) },
            "editor_video" to { EditorScreen(video.id, nav, onClose = {}) },
            "collage" to { CollageScreen(CollageRequest("c", CollageProject.create("c", photos.take(4).map { it.id }), fromDraft = false), nav, pick = {}, onClose = {}) },
            "series" to { SeriesCompareDialog(photos.take(3), onConfirm = {}, onDismiss = {}) },
            "tools_gif" to { ToolsScreen(ToolRequest("g", ToolKind.GIF, photos.take(3).map { it.id }), nav, onClose = {}) },
        )
        for ((name, screen) in screens) {
            content.value = screen; gen.intValue++
            if (!shown) { shown = true; rule.setContent { TestHost(app) { key(gen.intValue) { content.value?.invoke() } } } }
            pump()
            captureScreenRoboImage("build/outputs/roborazzi/r1_${variant}_$name.png")
        }
    }

    @Test @Config(qualifiers = "de-w393dp-h852dp") fun light393() = run("light393")
    @Test @Config(qualifiers = "de-w360dp-h800dp-night") fun dark360() = run("dark360", dark = true)
    @Test @Config(qualifiers = "de-w393dp-h852dp", fontScale = 2.0f) fun font200() = run("font200")
    @Test @Config(qualifiers = "ar-w393dp-h852dp") fun arabic() = run("arabic")
}
