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
import app.photomaid.design.AppearanceChoice
import app.photomaid.feature.workflow.WorkflowEditorScreen
import app.photomaid.feature.workflow.WorkflowRequest
import app.photomaid.feature.workflow.WorkflowRunRequest
import app.photomaid.feature.workflow.WorkflowRunScreen
import app.photomaid.feature.workflow.WorkflowTemplate
import app.photomaid.feature.workflow.WorkflowsScreen
import app.photomaid.media.reviewableMedia
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Paket 2 Arbeitsabläufe: Aufnahmen (hell, dunkel, 200 %, Arabisch) → build/outputs/roborazzi/wf_*. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkflowShotsTest {
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
    private fun waitFor(tag: String) {
        rule.mainClock.autoAdvance = false
        val end = System.currentTimeMillis() + 60_000
        while (rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() && System.currentTimeMillis() < end) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(5)
        }
        rule.mainClock.autoAdvance = true
    }
    private fun tap(tag: String) {
        val n = rule.onAllNodesWithTag(tag, useUnmergedTree = true)
        if (n.fetchSemanticsNodes().isNotEmpty()) { n[0].performSemanticsAction(SemanticsActions.OnClick); pump(30) }
    }

    private fun run(variant: String, dark: Boolean = false) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 5, videos = 1).also { l -> lib = l; l.all.forEach { a -> l.dims[a.id] = 300 to 400 } } },
            purchase = FakePurchaseGateway(PurchaseState.PURCHASED)) { a ->
            a.updateSettings { it.copy(appearance = if (dark) AppearanceChoice.DARK else AppearanceChoice.LIGHT) } }
        app.videoExporter = FakeVideoEditExporter(rule.activity)
        val ids = runBlocking { lib.reviewableMedia() }.map { it.id }
        val send = WorkflowTemplate.SEND_SMALL.create(app).copy(photoMaxBytes = 12_000, minLongEdge = 640)
        val classifieds = WorkflowTemplate.CLASSIFIEDS.create(app)
        runBlocking { app.workflows.upsert(classifieds); app.workflows.upsert(send) }
        data class Shot(val name: String, val screen: @Composable () -> Unit, val after: () -> Unit = {})
        val shots = listOf(
            Shot("list", { WorkflowsScreen(WorkflowRequest("l", null), nav) {} }),
            Shot("editor", { WorkflowEditorScreen(send.id, nav) {} }),
            Shot("preview", { WorkflowRunScreen(WorkflowRunRequest("p", send.id, ids), nav) {} }) { waitFor("wf.steps") },
            Shot("result", { WorkflowRunScreen(WorkflowRunRequest("r", send.id, ids), nav) {} }) { waitFor("wf.start"); tap("wf.start"); waitFor("wf.summary") },
        )
        for (s in shots) {
            content.value = s.screen; gen.intValue++
            if (!shown) { shown = true; rule.setContent { TestHost(app) { key(gen.intValue) { content.value?.invoke() } } } }
            pump(); s.after(); pump(40)
            captureScreenRoboImage("build/outputs/roborazzi/wf_${variant}_${s.name}.png")
        }
    }

    @Test @Config(qualifiers = "de-w393dp-h852dp") fun light() = run("light")
    @Test @Config(qualifiers = "de-w360dp-h800dp-night") fun dark() = run("dark", dark = true)
    @Test @Config(qualifiers = "de-w393dp-h852dp", fontScale = 2.0f) fun font200() = run("font200")
    @Test @Config(qualifiers = "ar-w393dp-h852dp") fun arabic() = run("arabic")
}
