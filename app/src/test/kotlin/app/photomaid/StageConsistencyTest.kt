package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.model.MediaKind
import app.photomaid.feature.editor.EditorScreen
import app.photomaid.feature.library.SlideshowRequest
import app.photomaid.feature.library.SlideshowScreen
import app.photomaid.feature.library.ViewerRequest
import app.photomaid.feature.library.ViewerScreen
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.feature.review.SeriesCompareDialog
import app.photomaid.feature.studio.CollageRequest
import app.photomaid.feature.studio.CollageScreen
import app.photomaid.feature.studio.MovieRequest
import app.photomaid.feature.studio.MovieScreen
import app.photomaid.feature.studio.ToolKind
import app.photomaid.feature.studio.ToolRequest
import app.photomaid.feature.studio.ToolsScreen
import app.photomaid.feature.studio.movieFrom
import app.photomaid.core.studio.CollageProject
import app.photomaid.media.MediaAsset
import app.photomaid.media.MediaCategory
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Prüflog R1 (Issue #3): Regel M – die Medienfläche („media.stage“ bzw. „feed.stage“) ist je Bildschirm gleich groß,
 * egal ob Foto/Video, hoch/quer, welcher Reiter; Regel W – keine Bedienelemente über dem Medium.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w393dp-h852dp")
class StageConsistencyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()
    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val gen = mutableIntStateOf(0)
    private var shown = false

    private fun start() {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l } },
            purchase = FakePurchaseGateway(PurchaseState.PURCHASED))
        app.editEngine.io = Dispatchers.Main; app.editEngine.parallel = false
        app.videoTrimmer = FakeVideoTrimmer(rule.activity)
    }
    private fun pumpUntil(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        rule.mainClock.autoAdvance = false
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(5)
            if (cond()) break
            check(System.currentTimeMillis() < end) { "Bedingung nicht erfüllt" }
        }
        rule.mainClock.autoAdvance = true
    }
    private fun show(c: @Composable () -> Unit) {
        content.value = c; gen.intValue++
        if (!shown) { shown = true; rule.setContent { TestHost(app) { key(gen.intValue) { content.value?.invoke() } } } }
        pumpUntil { true }
    }
    private fun nodes(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
    private fun rect(tag: String): Rect { pumpUntil { nodes(tag).isNotEmpty() }; repeat(10) { pumpUntil { true } }; return nodes(tag).first().boundsInRoot }
    private fun click(tag: String) { rule.onAllNodesWithTag(tag)[0].performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
    private fun dp(px: Float) = px / rule.density.density

    private val photosPortrait get() = lib.all.first { it.displayName == "portrait_lake" }
    private val photoLandscape get() = lib.all.first { it.displayName == "landscape_sunset" }
    private val panorama get() = lib.all.first { it.displayName == "panorama_ridge" }
    private val videoLandscape get() = lib.all.first { it.displayName == "video_coast" }
    private val videoPortrait get() = lib.all.first { it.displayName == "video_portrait" }

    /** Alle Rechtecke gleich (± 1,5 px)? Sonst lesbarer Bericht. */
    private fun sameSize(what: String, rects: Map<String, Rect>): String? {
        val ref = rects.values.first()
        val bad = rects.filterValues { abs(it.width - ref.width) > 1.5f || abs(it.height - ref.height) > 1.5f || abs(it.top - ref.top) > 1.5f }
        if (bad.isEmpty()) return null
        return "$what: " + rects.entries.joinToString("; ") { (k, r) -> "$k ${dp(r.width).toInt()}×${dp(r.height).toInt()} dp @y=${dp(r.top).toInt()}" }
    }

    /** Klickbare/verstellbare Knoten, die das Medium [media] schneiden (ohne Container, die das Medium ganz enthalten). */
    private fun controlsOver(media: Rect, ignore: Set<String> = emptySet()): List<String> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { all += n; n.children.forEach(::walk) }
        rule.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return all.filter { n ->
            val actionable = n.config.getOrNull(SemanticsActions.OnClick) != null || n.config.getOrNull(SemanticsActions.SetProgress) != null
            val b = n.boundsInRoot
            val tag = n.config.getOrNull(SemanticsProperties.TestTag) ?: ""
            actionable && tag !in ignore && b.overlaps(media) && !(b.left <= media.left + 1 && b.top <= media.top + 1 && b.right >= media.right - 1 && b.bottom >= media.bottom - 1)
        }.map { it.config.getOrNull(SemanticsProperties.TestTag) ?: it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?" }
    }

    private val problems = mutableListOf<String>()
    private fun report() { assertTrue("Prüflog-Funde:\n" + problems.joinToString("\n"), problems.isEmpty()) }

    @Test fun editorStageIsTheSameForPhotosAndVideosInEveryTab() {
        start()
        val rects = linkedMapOf<String, Rect>()
        for (a in listOf(photosPortrait, photoLandscape, panorama, videoLandscape, videoPortrait)) {
            show { EditorScreen(a.id, nav, onClose = {}) }
            val tabs = if (a.kind == MediaKind.VIDEO) listOf("TRIM", "SPEED", "AUDIO", "FORMAT", "FILTER", "ADJUST") else listOf("CROP", "ADJUST", "FILTER", "RETOUCH", "MARKUP", "FRAME", "RESIZE")
            pumpUntil { nodes("tab.${tabs[0]}").isNotEmpty() }
            for (t in tabs) { click("tab.$t"); rects["${a.displayName}/$t"] = rect("media.stage") }
            if (a.kind == MediaKind.VIDEO) {
                val over = controlsOver(rect("editor.video"), ignore = setOf("editor.video"))
                if (over.isNotEmpty()) problems += "W Video-Editor (${a.displayName}): Bedienelemente über dem Video: $over"
            }
        }
        sameSize("M Editor-Bühne", rects)?.let { problems += it }
        report()
    }

    @Test fun feedStageIsTheSameForPhotosAndVideosAndNothingCoversTheVideo() {
        start()
        val rects = linkedMapOf<String, Rect>()
        show { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, onClose = {}, openBasket = {}) }
        rects["Foto"] = rect("feed.stage")
        show { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.VIDEOS)), nav, onClose = {}, openBasket = {}) }
        pumpUntil { nodes("video.seek").isNotEmpty() || nodes("feed.media.problem").isNotEmpty() }
        rects["Video"] = rect("feed.stage")
        sameSize("M Feed-Fläche", rects)?.let { problems += it }
        val over = controlsOver(rect("feed.media.frame"))
        if (over.isNotEmpty()) problems += "W Feed-Video: Bedienelemente über dem Video: $over"
        report()
    }

    @Test fun movieStageIsTheSameInEveryTabAndForEveryFirstClip() {
        start()
        val rects = linkedMapOf<String, Rect>()
        for ((name, list) in listOf("quer zuerst" to listOf(videoLandscape, photosPortrait, videoPortrait), "hoch zuerst" to listOf(videoPortrait, photoLandscape, panorama))) {
            show { MovieScreen(MovieRequest("m$name", movieFrom(list, false), false), nav, onClose = {}) }
            for (t in listOf("CLIPS", "TRANSITION", "FORMAT", "MUSIC")) { click("movie.tab.$t"); rects["$name/$t"] = rect("media.stage") }
            val over = controlsOver(rect("movie.stage"), ignore = setOf("movie.stage"))
            if (over.isNotEmpty()) problems += "W Film ($name): Bedienelemente über der Vorschau: $over"
        }
        sameSize("M Film-Vorschau", rects)?.let { problems += it }
        report()
    }

    @Test fun collageCanvasIsTheSameInEveryTab() {
        start()
        val ids = lib.all.filter { it.kind != MediaKind.VIDEO }.take(4).map { it.id }
        show { CollageScreen(CollageRequest("c", CollageProject.create("c", ids), fromDraft = false), nav, pick = {}, onClose = {}) }
        val rects = linkedMapOf<String, Rect>()
        for (t in listOf("LAYOUT", "FORMAT", "SPACING", "BACKGROUND", "PHOTO", "MARKUP")) {
            if (nodes("ctab.$t").isEmpty()) continue
            click("ctab.$t"); rects[t] = rect("media.stage")
        }
        sameSize("M Collage-Leinwand", rects)?.let { problems += it }
        report()
    }

    @Test fun toolPreviewIsTheSameForStitchAndGif() {
        start()
        val photos = lib.all.filter { it.kind != MediaKind.VIDEO }
        val rects = linkedMapOf<String, Rect>()
        show { ToolsScreen(ToolRequest("s", ToolKind.STITCH, listOf(photos[2].id, photos[0].id)), nav, onClose = {}) }
        rects["Zusammenfügen (laden)"] = rect("media.stage")
        pumpUntil { nodes("tools.preview").isNotEmpty() }
        rects["Zusammenfügen"] = rect("media.stage")
        show { ToolsScreen(ToolRequest("g", ToolKind.GIF, listOf(panorama.id, photosPortrait.id)), nav, onClose = {}) }
        pumpUntil { nodes("tools.preview").isNotEmpty() }
        rects["GIF Panorama"] = rect("media.stage")
        sameSize("M Werkzeug-Vorschau", rects)?.let { problems += it }
        report()
    }

    @Test fun seriesCompareShowsBothImagesEquallyAndNothingOnThem() {
        start()
        val members = listOf(photosPortrait, panorama, photoLandscape)
        show { SeriesCompareDialog(members, onConfirm = {}, onDismiss = {}) }
        val rects = linkedMapOf<String, Rect>()
        rects["links Hochformat"] = rect("series.img0"); rects["rechts Panorama"] = rect("series.img1")
        rule.onAllNodesWithTag("series.tile")[2].performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true }
        rects["rechts Querformat"] = rect("series.img1")
        sameSize("M Serienvergleich", mapOf("links" to rects.getValue("links Hochformat"), "rechts" to rects.getValue("rechts Panorama").let { Rect(it.left, rects.getValue("links Hochformat").top, it.right, rects.getValue("links Hochformat").top + it.height) }))?.let { problems += it }
        sameSize("M Serienvergleich rechts", mapOf("Panorama" to rects.getValue("rechts Panorama"), "Querformat" to rects.getValue("rechts Querformat")))?.let { problems += it }
        for (side in 0..1) { val over = controlsOver(rect("series.img$side")); if (over.isNotEmpty()) problems += "W Serienvergleich Bild $side: $over" }
        report()
    }

    @Test fun viewerAndSlideshowHideEverythingOnTap() {
        start()
        val list = listOf(photosPortrait, videoLandscape, photoLandscape)
        show { ViewerScreen(ViewerRequest("v", list, 1), nav, onClose = {}) }
        pumpUntil { nodes("viewer.index").isNotEmpty() }
        val screen = rect("viewer")
        // Tipp neben die Mitte: Leisten aus → nichts Bedienbares mehr über dem Video.
        rule.onAllNodesWithTag("viewer")[0].performTouchInput { down(androidx.compose.ui.geometry.Offset(width * 0.3f, height * 0.3f)); up() }
        repeat(30) { pumpUntil { true } }
        val over = controlsOver(screen, ignore = setOf("viewer"))
        if (over.isNotEmpty()) problems += "W Betrachter (Leisten aus): $over"
        Thread.sleep(400) // kein Doppeltipp (der zoomt)
        rule.onAllNodesWithTag("viewer")[0].performTouchInput { down(androidx.compose.ui.geometry.Offset(width * 0.3f, height * 0.3f)); up() }
        pumpUntil { nodes("viewer.actions").isNotEmpty() }

        show { SlideshowScreen(SlideshowRequest("s", list.filter { it.kind != MediaKind.VIDEO }), onClose = {}) }
        pumpUntil { nodes("slideshow.controls").isNotEmpty() }
        val ss = rect("slideshow")
        rule.onAllNodesWithTag("slideshow")[0].performTouchInput { down(androidx.compose.ui.geometry.Offset(width * 0.3f, height * 0.3f)); up() }
        repeat(30) { pumpUntil { true } }
        val over2 = controlsOver(ss, ignore = setOf("slideshow"))
        if (over2.isNotEmpty()) problems += "W Diashow (Steuerung aus): $over2"
        report()
    }

    private fun MediaAsset.label() = displayName
}
