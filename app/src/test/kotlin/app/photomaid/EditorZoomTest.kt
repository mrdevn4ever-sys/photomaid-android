package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.model.MediaKind
import app.photomaid.design.StageZoomScaleKey
import app.photomaid.feature.editor.EditorScreen
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Editor wie die Fotos-App: Fotos und Videos lassen sich auf jeder Bühne mit zwei Fingern zoomen. Zoomen ist nur Ansicht –
 * es ändert das Rezept nicht (Speichern bleibt aus), verschiebt keinen Ausschnitt und zeichnet keinen Strich.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w393dp-h852dp")
class EditorZoomTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary

    private fun open(video: Boolean = false) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 3, videos = 1).also { l -> lib = l } },
            purchase = FakePurchaseGateway(PurchaseState.PURCHASED))
        app.editEngine.io = Dispatchers.Main; app.editEngine.parallel = false
        val a = lib.all.first { (it.kind == MediaKind.VIDEO) == video }
        rule.setContent { TestHost(app) { EditorScreen(a.id, RecordingNav(), onClose = {}) } }
        waitTag(if (video) "editor.video" else "editor.crop")
    }
    private fun waitTag(tag: String) = rule.waitUntil(15_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun zoom(): Float = rule.onAllNodesWithTag("editor.zoomable", useUnmergedTree = true).fetchSemanticsNodes().first().config[StageZoomScaleKey]
    private fun click(tag: String) { rule.openMoreFor(tag) { rule.waitForIdle() }; rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); rule.waitForIdle() }
    private fun pinchOpen() {
        rule.onAllNodesWithTag("editor.zoomable", useUnmergedTree = true)[0].performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(240f, 0f), center + Offset(40f, 0f), center + Offset(240f, 0f), durationMillis = 400)
        }
        rule.waitForIdle()
    }

    @Test fun cropStageZoomsWithTwoFingersWithoutChangingTheCrop() {
        open()
        assertTrue(zoom() == 1f)
        pinchOpen()
        assertTrue("Zuschneiden vergrößert: ${zoom()}", zoom() > 1.5f)
        rule.onNodeWithTag("editor.save").assertIsNotEnabled()
    }

    @Test fun markupZoomsAndPinchDrawsNothingButOneFingerStillDraws() {
        open()
        click("tab.MARKUP"); waitTag("mk.draw"); click("mk.draw")
        pinchOpen()
        assertTrue("Markieren vergrößert: ${zoom()}", zoom() > 1.5f)
        rule.onNodeWithTag("editor.save").assertIsNotEnabled()
        rule.onAllNodesWithTag("markup.canvas", useUnmergedTree = true)[0].performTouchInput { swipe(center, center + Offset(120f, 60f), 200) }
        rule.waitForIdle()
        rule.onNodeWithTag("editor.save").assertIsEnabled()
    }

    @Test fun videoZoomsWithTwoFingers() {
        open(video = true)
        assertTrue(zoom() == 1f)
        pinchOpen()
        assertTrue("Video vergrößert: ${zoom()}", zoom() > 1.5f)
        rule.onNodeWithTag("editor.save").assertIsNotEnabled()
        assertTrue(app.ledger.stateFlow.value.records.isEmpty())
    }
}
