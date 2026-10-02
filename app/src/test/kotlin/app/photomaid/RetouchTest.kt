package app.photomaid

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.edit.EditGeometry
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.Mask
import app.photomaid.core.edit.PerspectiveMath
import app.photomaid.core.edit.PxSize
import app.photomaid.feature.editor.EditorScreen
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** P4: Perspektive und Rote Augen (kostenlos), Bereiche und Farbmischer (Pro), jeweils bis in die gespeicherte Datei. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+w393dp-h852dp")
class RetouchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary

    private fun start(pro: Boolean) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 2, videos = 0).also { l -> lib = l } },
            purchase = if (pro) FakePurchaseGateway(PurchaseState.PURCHASED) else null)
        app.editEngine.io = Dispatchers.Main; app.editEngine.parallel = false
    }
    private fun pumpUntil(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        rule.mainClock.autoAdvance = false
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(5)
            if (cond()) break
            check(System.currentTimeMillis() < end) {
                "Bedingung nicht erfüllt\n" + Thread.getAllStackTraces().filter { (t, st) -> st.any { it.className.startsWith("app.photomaid") } }
                    .entries.joinToString("\n\n") { (t, st) -> t.name + "\n" + st.take(40).joinToString("\n") { "  $it" } }
            }
        }
        rule.mainClock.autoAdvance = true
    }
    private fun waitTag(tag: String) = pumpUntil { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun click(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
    private fun slider(parent: String, v: Float) {
        rule.onNode(hasTestTag("editor.slider") and hasAnyAncestor(hasTestTag(parent))).performSemanticsAction(SemanticsActions.SetProgress) { it(v) }
        pumpUntil { true }
    }
    private var closed = false
    private fun open() {
        val id = lib.all[0].id
        rule.setContent { TestHost(app) { EditorScreen(id, RecordingNav(), onClose = { closed = true }) } }
        waitTag("editor.crop")
    }
    private fun tapCanvas(tag: String, x: Float, y: Float) {
        rule.onNodeWithTag(tag).performTouchInput { down(Offset(width * x, height * y)); up() }
        pumpUntil { true }
    }
    private fun saveCopy(): File {
        click("editor.save"); waitTag("editor.sheet.save")
        assertTrue(rule.onAllNodesWithTag("editor.sheet.pro").fetchSemanticsNodes().isEmpty())
        click("editor.sheet.save")
        pumpUntil { closed }
        return File(Uri.parse(app.edits.records.value.single().newId).path!!)
    }

    @Test fun perspectiveIsFreeAndChangesTheSavedGeometry() {
        start(pro = false)
        open()
        click("crop.mode.persp"); waitTag("persp.stage")
        slider("persp.v", 60f)
        val f = saveCopy()
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.absolutePath, o)
        val expect = EditGeometry(PxSize(300, 400), EditRecipe(perspective = PerspectiveMath.keystone(60f, 0f))).output
        assertEquals(expect.width, o.outWidth); assertEquals(expect.height, o.outHeight)
        assertTrue(o.outWidth < 300)
    }

    @Test fun cornerDragMovesQuadAndCanBeUndone() {
        start(pro = false)
        open()
        click("crop.mode.persp"); waitTag("persp.stage")
        // Ecke oben links (Bedienungshilfe-Aktion „nach innen“) – derselbe Weg wie Ziehen
        val actions = rule.onNodeWithTag("persp.stage").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        rule.runOnIdle { actions.first().action() }
        pumpUntil { true }
        click("editor.undo")
        click("editor.redo")
        val f = saveCopy()
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.absolutePath, o)
        assertTrue("Perspektive wirkt: ${o.outWidth}x${o.outHeight}", o.outWidth != 300 || o.outHeight != 400)
    }

    @Test fun redEyeIsFreeAndSaves() {
        start(pro = false)
        open()
        click("tab.RETOUCH"); waitTag("retouch.canvas")
        click("rtool.RED_EYE"); waitTag("redeye.hint")
        tapCanvas("retouch.canvas", 0.5f, 0.45f)
        waitTag("redeye.undo")
        saveCopy()
    }

    @Test fun radialAreaNeedsProThenBrightensInsideOnly() {
        start(pro = false)
        open()
        click("tab.RETOUCH"); waitTag("retouch.canvas")
        tapCanvas("retouch.canvas", 0.5f, 0.42f)
        waitTag("local.0")
        slider("local.slider", 80f)
        waitTag("editor.prohint") // Bereiche speichern mit Pro (Vorschau frei)
        click("editor.save"); waitTag("editor.sheet.pro")
    }

    @Test fun proSavesRadialAndMixer() {
        start(pro = true)
        open()
        click("tab.RETOUCH"); waitTag("retouch.canvas")
        tapCanvas("retouch.canvas", 0.5f, 0.42f)
        waitTag("local.0")
        slider("local.slider", 100f)
        click("tab.ADJUST"); click("group.MIX"); waitTag("mix.bands")
        click("band.BLUE"); slider("mix.SATURATION", -100f)
        val f = saveCopy()
        val b = BitmapFactory.decodeFile(f.absolutePath)
        val src = BitmapFactory.decodeFile(File(Uri.parse(lib.all[0].id).path!!).absolutePath)
        fun lum(p: Int) = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
        // Mitte (im Kreis) heller als vorher, Ecke (außerhalb) kaum verändert.
        val msg = "Mitte ${Integer.toHexString(src.getPixel(150, 168))}→${Integer.toHexString(b.getPixel(150, 168))}, Ecke ${Integer.toHexString(src.getPixel(5, 395))}→${Integer.toHexString(b.getPixel(5, 395))}"
        assertTrue(msg, lum(b.getPixel(150, 168)) > lum(src.getPixel(150, 168)) + 20)
        assertTrue(msg, kotlin.math.abs(lum(b.getPixel(5, 395)) - lum(src.getPixel(5, 395))) < 60)
    }

    @Test fun brushPaintsAStroke() {
        start(pro = true)
        open()
        click("tab.RETOUCH"); waitTag("retouch.canvas")
        click("rtool.BRUSH"); waitTag("retouch.hint")
        rule.onNodeWithTag("retouch.canvas").performTouchInput {
            down(Offset(width * 0.3f, height * 0.5f)); for (i in 1..10) moveTo(Offset(width * (0.3f + i * 0.04f), height * 0.5f)); up()
        }
        pumpUntil { true }
        waitTag("local.0")
        waitTag("brush.erase")
    }
}
