package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.model.MediaKind
import app.photomaid.core.studio.CollageProject
import app.photomaid.design.AppearanceChoice
import app.photomaid.design.LocalEditorImeOverride
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import app.photomaid.feature.editor.EditorScreen
import app.photomaid.feature.review.SeriesCompareDialog
import app.photomaid.feature.studio.CollageRequest
import app.photomaid.feature.studio.CollageScreen
import app.photomaid.feature.studio.MovieRequest
import app.photomaid.feature.studio.MovieScreen
import app.photomaid.feature.studio.ToolKind
import app.photomaid.feature.studio.ToolRequest
import app.photomaid.feature.studio.ToolsScreen
import app.photomaid.feature.studio.movieFrom
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Editor-Redesign 2026-10 (Prüflog #3): reproduzierbare Aufnahmen der Editorfamilie für Vorher/Nachher.
 * Gleiche Medien (Testbilder `landscape_sunset`, `portrait_lake`, `video_coast`), gleiche Fenster, gleiche Schrift → build/outputs/roborazzi/ed_*.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()
    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val gen = mutableIntStateOf(0)
    private var shown = false

    private fun pump(n: Int = 60) {
        rule.mainClock.autoAdvance = false
        repeat(n) { shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(4) }
        rule.mainClock.autoAdvance = true
    }
    private fun tap(tag: String) {
        rule.openMoreFor(tag) { pump(30) }
        val n = rule.onAllNodesWithTag(tag, useUnmergedTree = true)
        if (n.fetchSemanticsNodes().isNotEmpty()) { n[0].performSemanticsAction(SemanticsActions.OnClick); pump(30) }
    }

    private val ime = mutableStateOf<androidx.compose.ui.unit.Dp?>(null)
    private var landscapeWindow = false
    private fun run(variant: String, dark: Boolean = false) {
        landscapeWindow = variant == "landscape"
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l } },
            purchase = FakePurchaseGateway(PurchaseState.PURCHASED)) { a -> a.updateSettings { it.copy(editorTourSeen = true, appearance = if (dark) AppearanceChoice.DARK else AppearanceChoice.LIGHT) } }
        app.editEngine.io = Dispatchers.Main; app.editEngine.parallel = false
        app.videoTrimmer = FakeVideoTrimmer(rule.activity)
        val photo = lib.all.first { it.displayName == "landscape_sunset" }
        val tall = lib.all.first { it.displayName == "portrait_lake" }
        val video = lib.all.first { it.displayName == "video_coast" }
        val photos = lib.all.filter { it.kind != MediaKind.VIDEO }
        data class Shot(val name: String, val screen: @Composable () -> Unit, val after: () -> Unit = {})
        val shots = listOf(
            Shot("photo_adjust", { EditorScreen(photo.id, nav, onClose = {}) }) { tap("tab.ADJUST") },
            Shot("photo_crop", { EditorScreen(tall.id, nav, onClose = {}) }) { tap("tab.CROP") },
            Shot("photo_filter", { EditorScreen(photo.id, nav, onClose = {}) }) { tap("tab.FILTER") },
            Shot("photo_markup", { EditorScreen(photo.id, nav, onClose = {}) }) { tap("tab.MARKUP") },
            Shot("video_trim", { EditorScreen(video.id, nav, onClose = {}) }) { tap("tab.TRIM") },
            Shot("video_audio", { EditorScreen(video.id, nav, onClose = {}) }) { tap("tab.AUDIO") },
            Shot("export", { EditorScreen(photo.id, nav, onClose = {}) }) { tap("tab.CROP"); tap("editor.rotate"); tap("editor.save") },
            // Tastatur simuliert (Robolectric zeigt keine IME): 300 dp unten belegt, Fläche bleibt im Bild leer.
            Shot("photo_text_keyboard", { CompositionLocalProvider(LocalEditorImeOverride provides ime.value) { EditorScreen(tall.id, nav, onClose = {}) } }) {
                tap("tab.MARKUP"); tap("mk.text"); ime.value = (if (landscapeWindow) 200 else 300).dp },
            Shot("collage", { CollageScreen(CollageRequest("c", CollageProject.create("c", photos.take(4).map { it.id }), fromDraft = false), nav, pick = {}, onClose = {}) }),
            Shot("movie", { MovieScreen(MovieRequest("m", movieFrom(listOf(video, photo, tall), false), false), nav, onClose = {}) }),
            Shot("gif", { ToolsScreen(ToolRequest("g", ToolKind.GIF, photos.take(3).map { it.id }), nav, onClose = {}) }),
            Shot("series", { SeriesCompareDialog(photos.take(3), onConfirm = {}, onDismiss = {}) }),
        )
        for (s in shots) {
            content.value = s.screen; gen.intValue++
            if (!shown) { shown = true; rule.setContent { TestHost(app) { key(gen.intValue) { content.value?.invoke() } } } }
            ime.value = null
            pump(); s.after(); pump(40)
            captureScreenRoboImage("build/outputs/roborazzi/ed_${variant}_${s.name}.png")
        }
    }

    @Test @Config(qualifiers = "de-w393dp-h852dp") fun light393() = run("light393")
    @Test @Config(qualifiers = "de-w360dp-h800dp-night") fun dark360() = run("dark360", dark = true)
    @Test @Config(qualifiers = "de-w393dp-h852dp", fontScale = 1.5f) fun font150() = run("font150")
    @Test @Config(qualifiers = "de-w393dp-h852dp", fontScale = 2.0f) fun font200() = run("font200")
    @Test @Config(qualifiers = "ar-w393dp-h852dp") fun arabic() = run("arabic")
    @Test @Config(qualifiers = "de-w852dp-h393dp-land") fun landscape() = run("landscape")
    @Test @Config(qualifiers = "de-w800dp-h360dp-land-night") fun landscapeDark() = run("landscapeDark", dark = true)
}
