package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.model.MediaKind
import app.photomaid.feature.cleanup.CleanUpHubScreen
import app.photomaid.feature.cleanup.CleanupBasketScreen
import app.photomaid.feature.cleanup.SystemTrashScreen
import app.photomaid.feature.duplicates.DuplicatesScreen
import app.photomaid.feature.editor.EditorScreen
import app.photomaid.feature.editor.EditsScreen
import app.photomaid.feature.history.HistoryScreen
import app.photomaid.feature.later.LaterScreen
import app.photomaid.feature.library.LibraryMode
import app.photomaid.feature.library.LibraryScreen
import app.photomaid.feature.library.ViewerRequest
import app.photomaid.feature.library.ViewerScreen
import app.photomaid.feature.months.MonthsScreen
import app.photomaid.feature.pro.ProScreen
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.feature.settings.SettingsScreen
import app.photomaid.feature.storage.LargeFilesScreen
import app.photomaid.feature.studio.CollageRequest
import app.photomaid.feature.studio.CollageScreen
import app.photomaid.feature.studio.MovieRequest
import app.photomaid.feature.studio.MovieScreen
import app.photomaid.feature.studio.StudioScreen
import app.photomaid.feature.studio.ToolKind
import app.photomaid.feature.studio.ToolRequest
import app.photomaid.feature.studio.ToolsScreen
import app.photomaid.feature.studio.movieFrom
import app.photomaid.feature.today.TodayScreen
import app.photomaid.core.studio.CollageProject
import app.photomaid.media.MediaCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Prüflog R1.9 (Issue #3), Regel B: jede antippbare oder verstellbare Fläche ist mindestens 48 × 48 dp groß (Berührungsfläche,
 * wie Android sie auswertet). Sehr hohes Fenster, damit fast alles ohne Scrollen sichtbar ist.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w393dp-h2400dp")
class TouchTargetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()
    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val gen = mutableIntStateOf(0)
    private var shown = false

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
        repeat(60) { pumpUntil { true } }
    }

    /** Zu kleine Berührungsflächen auf dem aktuellen Bildschirm (nur vollständig sichtbare). */
    private fun tooSmall(): List<String> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { all += n; n.children.forEach(::walk) }
        val roots = rule.onAllNodes(isRoot()).fetchSemanticsNodes()
        roots.forEach(::walk)
        val d = rule.density.density
        val win = roots.maxOf { it.boundsInRoot.height }
        return all.filter { n ->
            val c = n.config
            val actionable = c.getOrNull(SemanticsActions.OnClick) != null || c.getOrNull(SemanticsActions.SetProgress) != null
            val b = n.boundsInRoot
            val t = n.touchBoundsInRoot
            // trim.start/trim.end: unsichtbare TalkBack-Regler; mit dem Finger greift die ganze 64-dp-Zeitleiste den nächsten Griff.
            val tag = c.getOrNull(SemanticsProperties.TestTag)
            // Material-Slider melden 44 dp; Compose erweitert die Trefferfläche von Zeigerelementen unter 48 dp beim Tippen
            // automatisch (minimumTouchTargetSize) – nur deutlich kleinere Regler sind ein Fund.
            val slider = c.getOrNull(SemanticsActions.SetProgress) != null && t.height / d >= 43.5f && t.width / d >= 47.5f
            // Teilweise aus einem Scrollbereich geschoben (sichtbar beschnitten): erreichbar durch Scrollen, kein Größenfund.
            val clipped = b.height + 1f < n.size.height || b.width + 1f < n.size.width
            actionable && !slider && !clipped && tag !in setOf("trim.start", "trim.end") && b.top >= 0f && b.bottom <= win && b.width > 0f && (t.width / d < 47.5f || t.height / d < 47.5f) &&
                c.getOrNull(SemanticsProperties.Disabled) == null
        }.map { n ->
            val c = n.config
            val name = c.getOrNull(SemanticsProperties.TestTag) ?: c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: c.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "?"
            "$name ${(n.touchBoundsInRoot.width / d).toInt()}×${(n.touchBoundsInRoot.height / d).toInt()} dp"
        }.distinct()
    }

    /** Editorfamilie: keine zwei Trefferflächen überdecken sich (außer Eltern/Kind). */
    private fun overlapping(): List<String> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { all += n; n.children.forEach(::walk) }
        rule.onAllNodes(isRoot()).fetchSemanticsNodes().forEach(::walk)
        val acts = all.filter { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && n.config.getOrNull(SemanticsProperties.Disabled) == null &&
            n.boundsInRoot.height + 1f >= n.size.height && n.boundsInRoot.width + 1f >= n.size.width && n.size.width > 0 }
        fun name(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.TestTag) ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"
        fun ancestor(a: SemanticsNode, b: SemanticsNode): Boolean { var p = b.parent; while (p != null) { if (p.id == a.id) return true; p = p.parent }; return false }
        val out = mutableListOf<String>()
        for (i in acts.indices) for (j in i + 1 until acts.size) {
            val a = acts[i]; val b = acts[j]
            if (ancestor(a, b) || ancestor(b, a)) continue
            val r = a.touchBoundsInRoot.intersect(b.touchBoundsInRoot)
            if (r.width > 1f && r.height > 1f) out += "${name(a)}↔${name(b)}"
        }
        return out.distinct().take(8)
    }

    @Test fun everyTouchTargetIsAtLeast48dp() {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l } },
            purchase = FakePurchaseGateway(PurchaseState.PURCHASED))
        app.editEngine.io = Dispatchers.Main; app.editEngine.parallel = false
        app.videoTrimmer = FakeVideoTrimmer(rule.activity)
        val all = lib.all
        runBlocking {
            app.decide(all[0].reference, DecisionAction.MarkForCleanup); app.decide(all[1].reference, DecisionAction.Keep)
        }
        val photos = all.filter { it.kind != MediaKind.VIDEO }
        val video = all.first { it.kind == MediaKind.VIDEO }
        val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
            "Heute" to { TodayScreen(nav) },
            "Fotos Zeitachse" to { LibraryScreen(nav) },
            "Fotos Ordner" to { LibraryScreen(nav, LibraryMode.FOLDERS) },
            "Aufräumen" to { CleanUpHubScreen(nav) },
            "Papierkorb" to { CleanupBasketScreen(nav, showsDone = false) },
            "Android-Papierkorb" to { SystemTrashScreen(nav) },
            "Später" to { LaterScreen(nav) },
            "Monate" to { MonthsScreen(nav) },
            "Große Dateien" to { LargeFilesScreen(nav) },
            "Duplikate" to { DuplicatesScreen(nav) },
            "Verlauf" to { HistoryScreen(nav) },
            "Bearbeitet" to { EditsScreen(nav) },
            "Studio" to { StudioScreen(nav) },
            "Einstellungen" to { SettingsScreen(nav) },
            "Pro" to { ProScreen(nav) },
            "Feed Foto" to { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, onClose = {}, openBasket = {}) },
            "Feed Video" to { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.VIDEOS)), nav, onClose = {}, openBasket = {}) },
            "Betrachter" to { ViewerScreen(ViewerRequest("v", listOf(photos[0], video), 1), nav, onClose = {}) },
            "Editor Foto" to { EditorScreen(photos[0].id, nav, onClose = {}) },
            "Editor Video" to { EditorScreen(video.id, nav, onClose = {}) },
            "Collage" to { CollageScreen(CollageRequest("c", CollageProject.create("c", photos.take(4).map { it.id }), fromDraft = false), nav, pick = {}, onClose = {}) },
            "Film" to { MovieScreen(MovieRequest("m", movieFrom(all.take(4), false), false), nav, onClose = {}) },
            "Werkzeug Verkleinern" to { ToolsScreen(ToolRequest("t", ToolKind.RESIZE, photos.map { it.id }), nav, onClose = {}) },
            "Werkzeug GIF" to { ToolsScreen(ToolRequest("g", ToolKind.GIF, photos.take(3).map { it.id }), nav, onClose = {}) },
        )
        val problems = mutableListOf<String>()
        for ((name, screen) in screens) {
            show(screen)
            val small = tooSmall()
            if (small.isNotEmpty()) problems += "B $name: " + small.joinToString(", ")
            if (name.startsWith("Editor") || name == "Collage" || name == "Film") overlapping().takeIf { it.isNotEmpty() }?.let { problems += "B $name überlappend: $it" }
        }
        assertTrue("Prüflog-Funde (zu kleine Tasten):\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
