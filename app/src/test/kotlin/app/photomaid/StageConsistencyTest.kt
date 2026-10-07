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
import app.photomaid.design.LocalEditorImeOverride
import app.photomaid.design.EditorLayoutPolicy
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
    private fun click(tag: String) { rule.openMoreFor(tag) { pumpUntil { true } }; rule.onAllNodesWithTag(tag)[0].performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
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

    /**
     * Medien-Editor (Fotos-App-Stil): Hauptwerkzeuge (Anpassen, Filter, Zuschneiden, Kürzen) haben eine schmale
     * Werkzeugzone und dieselbe große Bühne; Werkzeuge mit vielen Einstellungen eine gemeinsame zweite Größe. Foto und
     * Video beginnen auf gleicher Höhe; Video hat zusätzlich die Wiedergabezeile.
     */
    @Test fun editorStageIsTheSameForPhotosAndVideosInEveryTab() {
        start()
        val compact = linkedMapOf<String, Rect>(); val rich = linkedMapOf<String, Rect>()
        val videoCompact = linkedMapOf<String, Rect>(); val videoRich = linkedMapOf<String, Rect>()
        for (a in listOf(photosPortrait, photoLandscape, panorama, videoLandscape, videoPortrait)) {
            show { EditorScreen(a.id, nav, onClose = {}) }
            val video = a.kind == MediaKind.VIDEO
            val tabs = if (video) listOf("TRIM", "SPEED", "AUDIO", "FORMAT", "FILTER", "ADJUST") else listOf("CROP", "ADJUST", "FILTER", "RETOUCH", "MARKUP", "FRAME", "RESIZE")
            val main = setOf("TRIM", "FILTER", "ADJUST", "CROP")
            pumpUntil { nodes("tab.${tabs[0]}").isNotEmpty() }
            for (t in tabs) {
                click("tab.$t")
                val target = when { video && t in main -> videoCompact; video -> videoRich; t in main -> compact; else -> rich }
                target["${a.displayName}/$t"] = rect("media.stage")
            }
            if (video) {
                val over = controlsOver(rect("editor.video"), ignore = setOf("editor.video"))
                if (over.isNotEmpty()) problems += "W Video-Editor (${a.displayName}): Bedienelemente über dem Video: $over"
            }
        }
        for ((what, m) in listOf("Foto Hauptwerkzeuge" to compact, "Foto weitere" to rich, "Video Hauptwerkzeuge" to videoCompact, "Video weitere" to videoRich))
            sameSize("M Editor-Bühne $what", m)?.let { problems += it }
        if (compact.values.first().height <= rich.values.first().height) problems += "M Hauptwerkzeuge sollen die größere Bühne haben"
        val tops = (compact.values + rich.values + videoCompact.values + videoRich.values).map { it.top }
        if (tops.max() - tops.min() > 1.5f) problems += "M Bühne beginnt nicht überall auf gleicher Höhe"
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
            rule.openMoreFor("ctab.$t") { pumpUntil { true } }
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

    /** Redesign: Foto-Editor, Video-Editor, Collage und Film teilen exakt eine Bühnengeometrie (gleiches Gerüst, gleiche Policy). */
    /** Foto- und Video-Editor teilen Breite und Oberkante; Collage und Film (eigenes Gerüst) teilen ihre Geometrie. */
    @Test fun allEditorsShareOneStageGeometry() {
        start()
        val photos = lib.all.filter { it.kind != MediaKind.VIDEO }
        val editors = linkedMapOf<String, Rect>(); val others = linkedMapOf<String, Rect>()
        show { EditorScreen(photosPortrait.id, nav, onClose = {}) }; editors["Foto-Editor"] = rect("media.stage")
        show { EditorScreen(videoLandscape.id, nav, onClose = {}) }; editors["Video-Editor"] = rect("media.stage")
        show { CollageScreen(CollageRequest("c", CollageProject.create("c", photos.take(4).map { it.id }), fromDraft = false), nav, pick = {}, onClose = {}) }
        others["Collage"] = rect("media.stage")
        show { MovieScreen(MovieRequest("m", movieFrom(listOf(videoLandscape, photosPortrait), false), false), nav, onClose = {}) }
        others["Film"] = rect("media.stage")
        val ref = editors.values.first()
        if (editors.values.any { abs(it.left - ref.left) > 1.5f || abs(it.width - ref.width) > 1.5f || abs(it.top - ref.top) > 1.5f }) problems += "M Editor-Bühne: $editors"
        sameSize("M Collage/Film", others)?.let { problems += it }
        if (editors.getValue("Foto-Editor").height < others.getValue("Collage").height) problems += "M Foto-Editor-Bühne kleiner als Collage"
        report()
    }

    /** Die Bühne springt auch WÄHREND eines Werkzeugwechsels nicht (jedes Bild der Animation gemessen). */
    @Test fun stageNeverJumpsDuringToolSwitches() {
        start()
        show { EditorScreen(photoLandscape.id, nav, onClose = {}) }
        click("tab.ADJUST")
        val compact = rect("media.stage")
        click("tab.MARKUP")
        val rich = rect("media.stage")
        val seen = mutableListOf<String>()
        fun same(r: Rect, ref: Rect) = abs(r.top - ref.top) <= 1f && abs(r.height - ref.height) <= 1f && abs(r.width - ref.width) <= 1f
        // Jedes Bild des Wechsels zeigt genau eine der beiden Größen (kein Zwischenzustand, kein Wackeln).
        for (t in listOf("ADJUST", "FILTER", "CROP", "MARKUP", "RETOUCH", "FRAME", "RESIZE", "ADJUST")) {
            rule.openMoreFor("tab.$t") { pumpUntil { true } }
            rule.mainClock.autoAdvance = false
            rule.onAllNodesWithTag("tab.$t")[0].performSemanticsAction(SemanticsActions.OnClick)
            val ref = if (t in setOf("ADJUST", "FILTER", "CROP")) compact else rich
            repeat(24) {
                shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame()
                val r = nodes("media.stage").first().boundsInRoot
                if (!same(r, compact) && !same(r, rich)) seen += "$t Bild $it: $r"
                if (it == 23 && !same(r, ref)) seen += "$t Endgröße: $r"
            }
            rule.mainClock.autoAdvance = true
        }
        if (seen.isNotEmpty()) problems += "M Bühne sprang: " + seen.take(5)
        report()
    }

    /** Tastatur im Text-Werkzeug: Eingabefeld und „Fertig“ über der Tastatur, Speichern erreichbar, danach exakt zurück. */
    @Test fun keyboardKeepsTextFieldAndSaveReachableAndRestores() {
        start()
        val ime = mutableStateOf<androidx.compose.ui.unit.Dp?>(null)
        show { androidx.compose.runtime.CompositionLocalProvider(LocalEditorImeOverride provides ime.value) { EditorScreen(photoLandscape.id, nav, onClose = {}) } }
        rule.onAllNodesWithTag("tab.MARKUP")[0].performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true }
        val before = rect("media.stage")
        rule.onAllNodesWithTag("mk.text")[0].performSemanticsAction(SemanticsActions.OnClick)
        pumpUntil { nodes("text.field").isNotEmpty() }
        ime.value = 300f.let { androidx.compose.ui.unit.Dp(it) }
        repeat(20) { pumpUntil { true } }
        val h = rule.onRoot().fetchSemanticsNode().boundsInRoot.height
        val kbTop = h - 300f * rule.density.density
        for (t in listOf("text.field", "text.done", "editor.save")) {
            val r = rect(t)
            if (r.bottom > kbTop + 1f || r.height < 1f) problems += "B Tastatur verdeckt $t (${dp(r.bottom).toInt()} dp > ${dp(kbTop).toInt()} dp)"
        }
        val st = rect("media.stage")
        if (dp(st.height) < EditorLayoutPolicy.minStage.value - 1f) problems += "M Bühne mit Tastatur zu klein: ${dp(st.height)} dp"
        // Textebene bleibt erhalten (Text-Werkzeug offen, Ebene im Rezept).
        ime.value = null
        rule.onAllNodesWithTag("text.done")[0].performSemanticsAction(SemanticsActions.OnClick)
        repeat(20) { pumpUntil { true } }
        sameSize("M Bühne nach der Tastatur", mapOf("vorher" to before, "nachher" to rect("media.stage")))?.let { problems += it }
        report()
    }

    private fun MediaAsset.label() = displayName
}
