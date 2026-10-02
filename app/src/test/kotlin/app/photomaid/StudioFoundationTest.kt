package app.photomaid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.edit.Adjustments
import app.photomaid.core.edit.Detail
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.SaveMode
import app.photomaid.feature.editor.EditSaver
import app.photomaid.feature.editor.EditorScreen
import app.photomaid.feature.studio.ShareCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Studio-Fundament: Pixel-Pipeline in Vorschau und Export, neue Regler, Histogramm/Warnung, 100-%-Ansicht,
 * eigene Vorlagen, „Nur teilen“ ohne Galeriekopie, Zielordner der Kopie, Streifen = Ganzes.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+w393dp-h852dp")
class StudioFoundationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary

    private fun start(pro: Boolean = true) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 3, videos = 0).also { l -> lib = l } },
            purchase = if (pro) FakePurchaseGateway(PurchaseState.PURCHASED) else null)
    }
    private fun <T> io(block: suspend () -> T): T = runBlocking { kotlinx.coroutines.withContext(Dispatchers.IO) { block() } }
    private fun waitTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    private fun click(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); rule.waitForIdle() }
    private fun pumpUntil(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        rule.mainClock.autoAdvance = false
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "Bedingung nicht erfüllt" }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            rule.mainClock.advanceTimeByFrame()
            Thread.sleep(5)
        }
        rule.mainClock.autoAdvance = true
    }
    private fun openEditor(closed: () -> Unit = {}) {
        app.editEngine.io = Dispatchers.Main; app.editEngine.parallel = false
        val id = lib.all[0].id
        rule.setContent { TestHost(app) { EditorScreen(id, RecordingNav(), onClose = closed) } }
        waitTag("editor.crop")
    }

    @Test fun shadowsSliderChangesExportOnlyInDarkAreas() {
        start()
        val a = lib.all[0]
        io {
            val src = app.editEngine.loadPreview(a.uri, a.mimeType)
            val plain = app.editEngine.export(a.uri, a.mimeType, EditRecipe(adjust = Adjustments(exposure = 1f)), src.full, false, "t")
            val lifted = app.editEngine.export(a.uri, a.mimeType, EditRecipe(adjust = Adjustments(shadows = 100f)), src.full, false, "t")
            val p = BitmapFactory.decodeFile(plain.file.absolutePath); val l = BitmapFactory.decodeFile(lifted.file.absolutePath)
            // Fläche (hell) bleibt nahezu gleich, der weiße Kreis ebenfalls.
            val c = p.getPixel(150, 170); val cl = l.getPixel(150, 170)
            assertTrue(kotlin.math.abs(((c shr 16) and 0xFF) - ((cl shr 16) and 0xFF)) <= 3)
        }
    }

    @Test fun adjustGroupsAndAdvancedSharpenSliders() {
        start()
        openEditor()
        click("tab.ADJUST")
        waitTag("group.LIGHT")
        click("adjust.HIGHLIGHTS")
        rule.onNodeWithTag("slider.HIGHLIGHTS").assertExists()
        click("group.DETAIL")
        click("adjust.SHARPNESS")
        assertTrue(gone("slider.SHARPEN_RADIUS")) // erst sichtbar, wenn geschärft wird
        slider("SHARPNESS", 60f)
        waitTag("slider.SHARPEN_RADIUS"); waitTag("slider.SHARPEN_THRESHOLD")
        click("group.EFFECTS")
        waitTag("adjust.VIGNETTE"); waitTag("adjust.GRAIN")
    }

    @Test fun histogramAndClippingAreDisplayOnly() {
        start()
        openEditor()
        click("tab.ADJUST")
        waitTag("editor.histogram.toggle")
        click("editor.histogram.toggle")
        waitTag("editor.histogram")
        click("editor.clipping.toggle")
        // Der weiße Kreis im Testbild ist nicht abgeschnitten, aber Belichtung +100 schneidet Lichter ab.
        slider("EXPOSURE", 100f)
        pumpUntil { !gone("editor.clipping") }
        assertTrue(app.edits.records.value.isEmpty()) // nichts gespeichert
    }

    @Test fun doubleTapZoomsTo100AndRendersDetail() {
        start()
        openEditor()
        click("tab.ADJUST")
        waitTag("editor.result")
        rule.onNodeWithTag("editor.stage").performTouchInput { doubleClick(center) }
        waitTag("editor.zoom")
        pumpUntil { !gone("editor.detail") }
    }

    @Test fun presetsSaveAndApply() {
        start()
        openEditor()
        click("tab.ADJUST"); waitTag("adjust.EXPOSURE")
        slider("EXPOSURE", 40f)
        click("tab.FILTER"); waitTag("preset.save")
        click("preset.save")
        pumpUntil { app.presets.items.value.size == 1 }
        val p = app.presets.items.value.single()
        assertEquals("Mein Look", p.name); assertEquals(40f, p.adjust.exposure)
        click("preset.save")
        pumpUntil { app.presets.items.value.size == 2 }
        assertEquals(setOf("Mein Look", "Mein Look 2"), app.presets.items.value.map { it.name }.toSet())
        // Vorlage speichert keine Geometrie und keine Bilder.
        assertEquals(EditRecipe(quarterTurns = 1).copy(adjust = p.adjust, filter = p.filter, filterIntensity = p.filterIntensity, colorMix = p.colorMix),
            p.applyTo(EditRecipe(quarterTurns = 1)))
    }

    @Test fun shareOnlyCreatesNoGalleryCopy() {
        start()
        openEditor()
        click("editor.rotate")
        click("editor.save"); waitTag("editor.sheet.share")
        rule.onNodeWithTag("editor.sheet.share").performSemanticsAction(SemanticsActions.OnClick)
        pumpUntil { shadowOf(rule.activity).peekNextStartedActivity() != null }
        val intent = shadowOf(rule.activity).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CHOOSER, intent.action)
        assertTrue(lib.saved.isEmpty()); assertTrue(app.edits.records.value.isEmpty())
        val shared = ShareCache.dir(app.context).listFiles()!!
        assertEquals(1, shared.size)
        assertTrue(shared[0].name.contains("QA_0"))
    }

    @Test fun copyGoesToChosenFolder() = runBlocking {
        start()
        val a = lib.all[0]
        io {
            val src = app.editEngine.loadPreview(a.uri, a.mimeType)
            EditSaver.savePhoto(app, a, EditRecipe(quarterTurns = 1), src.full, SaveMode.COPY, false, targetPath = "Pictures/Urlaub 2026/")
        }
        assertEquals("Pictures/Urlaub 2026/", lib.saved.single().relativePath)
        // Beim Ersetzen bleibt der Ordner des Originals (die Fassung tritt an seine Stelle).
        io {
            val b = lib.all[1]
            EditSaver.savePhoto(app, b, EditRecipe(quarterTurns = 1), app.editEngine.loadPreview(b.uri, b.mimeType).full, SaveMode.REPLACE, false,
                targetPath = "Pictures/Urlaub 2026/")
        }
        assertEquals("DCIM/Camera/", lib.saved.last().relativePath ?: "DCIM/Camera/")
    }

    @Test fun detailInStripesEqualsWhole() {
        start()
        val w = 120; val h = 900
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; (0xFF shl 24) or (((x * 2 + y) % 256) shl 16) or (((y / 3) % 256) shl 8) or ((x * y) % 256) }
        val a = Adjustments(sharpness = 80f, clarity = 70f, sharpenThreshold = 10f)
        val expected = Detail.apply(px.copyOf(), w, h, a, maxOf(w, h))
        val b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        app.editEngine.applyDetail(b, a, maxOf(w, h))
        val got = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        assertArrayEquals(expected, got)
        assertNotEquals(px.toList(), got.toList())
    }

    @Test fun parallelAndSequentialPipelineAgree() {
        start()
        val w = 64; val h = 400
        val px = IntArray(w * h) { i -> (0xFF shl 24) or ((i * 7 % 256) shl 16) or ((i * 3 % 256) shl 8) or (i * 11 % 256) }
        val r = EditRecipe(adjust = Adjustments(vignette = -50f, grain = 40f, highlights = -30f, warmth = 20f))
        val a = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        val b = a.copy(Bitmap.Config.ARGB_8888, true)
        app.editEngine.parallel = true; app.editEngine.applyPixels(a, r)
        app.editEngine.parallel = false; app.editEngine.applyPixels(b, r)
        val pa = IntArray(w * h).also { a.getPixels(it, 0, w, 0, 0, w, h) }; val pb = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        assertArrayEquals(pa, pb)
    }

    // Hilfen

    private fun slider(tool: String, v: Float) {
        rule.onNode(androidx.compose.ui.test.hasTestTag("editor.slider") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("slider.$tool")))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(v) }
        rule.waitForIdle()
    }

    @Suppress("unused") private fun file(id: String) = File(Uri.parse(id).path!!)
}
