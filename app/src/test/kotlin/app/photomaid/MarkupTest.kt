package app.photomaid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.edit.DrawLayer
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.FrameStyle
import app.photomaid.core.edit.NormRect
import app.photomaid.core.edit.RedactLayer
import app.photomaid.core.edit.RedactMode
import app.photomaid.core.edit.SaveMode
import app.photomaid.core.edit.ShapeLayer
import app.photomaid.core.edit.StickerLayer
import app.photomaid.core.edit.TextLayer
import app.photomaid.feature.editor.EditorScreen
import app.photomaid.media.LayerRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import app.photomaid.core.edit.DrawStroke
import app.photomaid.core.edit.NormPoint
import app.photomaid.core.edit.ShapeKind
import app.photomaid.core.edit.StickerKind

/** Markieren (Text, Sticker, Zeichnen, Formen, Abdecken) und Rahmen: echte Pixel im Export, kostenlos bzw. Pro. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+w393dp-h852dp")
class MarkupTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary

    private fun start(pro: Boolean = false) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 2, videos = 0).also { l -> lib = l } },
            purchase = if (pro) FakePurchaseGateway(PurchaseState.PURCHASED) else null)
    }
    private fun <T> io(block: suspend () -> T): T = runBlocking { kotlinx.coroutines.withContext(Dispatchers.IO) { block() } }
    private fun waitTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun click(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); rule.waitForIdle() }
    private fun pumpUntil(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        rule.mainClock.autoAdvance = false
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "Bedingung nicht erfüllt" }
            shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(5)
        }
        rule.mainClock.autoAdvance = true
    }
    private var lastSaved: EditRecipe? = null
    private fun openEditor(closed: () -> Unit = {}) {
        app.editEngine.io = Dispatchers.Main; app.editEngine.parallel = false
        val id = lib.all[0].id
        rule.setContent { TestHost(app) { EditorScreen(id, RecordingNav(), onClose = closed) } }
        waitTag("editor.crop")
        click("tab.MARKUP"); waitTag("markup.canvas")
    }
    private fun swipe(from: Offset, to: Offset) {
        rule.onNodeWithTag("markup.canvas").performTouchInput {
            down(Offset(width * from.x, height * from.y))
            for (i in 1..8) moveTo(Offset(width * (from.x + (to.x - from.x) * i / 8), height * (from.y + (to.y - from.y) * i / 8)))
            up()
        }
        rule.waitForIdle()
    }

    @Test fun solidRedactReplacesPixelsCompletelyInExport() {
        start()
        val a = lib.all[0]
        io {
            val src = app.editEngine.loadPreview(a.uri, a.mimeType)
            val r = EditRecipe(layers = listOf(RedactLayer("r", RedactMode.SOLID, rect = NormRect(0.3f, 0.3f, 0.7f, 0.6f), color = 0xFF000000.toInt())))
            val out = app.editEngine.export(a.uri, a.mimeType, r, src.full, false, "t")
            val b = BitmapFactory.decodeFile(out.file.absolutePath)
            // Mitte des Kreises (weiß) liegt im abgedeckten Bereich → exakt schwarz.
            assertEquals(0xFF000000.toInt(), b.getPixel(150, 170))
            assertTrue(b.getPixel(10, 10) != 0xFF000000.toInt())
        }
    }

    @Test fun pixelateUsesCoarseBlocks() {
        start()
        val w = 200; val h = 200
        val px = IntArray(w * h) { i -> if ((i % w / 3 + i / w / 3) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        LayerRenderer.apply(b, listOf(RedactLayer("p", RedactMode.PIXELATE, rect = NormRect(0f, 0f, 1f, 1f), strength = 0.5f)), w, h)
        // Feines Schachbrett (3 px) wird zu einheitlichen Blöcken (≥ 8 px) → benachbarte Pixel gleich.
        var same = 0
        for (x in 0 until w - 1) if (b.getPixel(x, 100) == b.getPixel(x + 1, 100)) same++
        assertTrue("gleich: $same", same > w * 0.8)
    }

    @Test fun markupIsFreeAndSaves() {
        start(pro = false)
        var closed = false
        openEditor { closed = true }
        click("mk.shape")
        swipe(Offset(0.2f, 0.7f), Offset(0.6f, 0.4f))
        click("mk.redact")
        swipe(Offset(0.55f, 0.6f), Offset(0.8f, 0.7f))
        click("mk.draw")
        swipe(Offset(0.1f, 0.2f), Offset(0.4f, 0.25f))
        click("mk.sticker"); waitTag("sticker.HEART"); click("sticker.HEART")
        click("editor.save"); waitTag("editor.sheet.save")
        assertTrue(rule.onAllNodesWithTag("editor.sheet.pro").fetchSemanticsNodes().isEmpty()) // kostenlos
        rule.onNodeWithTag("editor.sheet.save").performSemanticsAction(SemanticsActions.OnClick)
        pumpUntil { closed }
        val saved = BitmapFactory.decodeFile(File(android.net.Uri.parse(app.edits.records.value.single().newId).path!!).absolutePath)
        assertNotNull(saved)
    }

    @Test fun textAndLayerActions() {
        start()
        openEditor()
        click("mk.text"); waitTag("text.sheet")
        // Blatt schließen (Zurück): Text-Ebene bleibt, ist gewählt.
        rule.activity.onBackPressedDispatcher.onBackPressed(); rule.waitForIdle()
        waitTag("mk.selected")
        click("mk.duplicate")
        click("mk.delete")
        waitTag("mk.hint")
    }

    @Test fun frameNeedsProAndGrowsOutput() {
        start(pro = false)
        val a = lib.all[0]
        assertTrue(EditRecipe(frame = FrameStyle(width = 0.1f)).usesProTools)
        io {
            val src = app.editEngine.loadPreview(a.uri, a.mimeType)
            val out = app.editEngine.export(a.uri, a.mimeType, EditRecipe(frame = FrameStyle(width = 0.1f, aspect = 1f)), src.full, false, "t")
            val b = BitmapFactory.decodeFile(out.file.absolutePath)
            assertEquals(b.width, b.height) // 1:1 Passepartout
            assertEquals(0xFFFFFFFF.toInt(), b.getPixel(2, 2)) // weißer Rand
        }
    }

    @Test fun layerTypesRenderWithoutCrash() {
        start()
        val b = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        val all = listOf(TextLayer("t", "Hallo\nWelt"), StickerLayer("s", StickerKind.STAR),
            StickerLayer("e", StickerKind.EMOJI, "🎉"), StickerLayer("d", StickerKind.DATE, "02.10.26"),
            ShapeLayer("a", ShapeKind.ARROW, NormPoint(0.1f, 0.1f), NormPoint(0.9f, 0.9f)),
            DrawLayer("x", listOf(DrawStroke(listOf(NormPoint(0.1f, 0.5f), NormPoint(0.9f, 0.5f)), 0.02f, -1))))
        LayerRenderer.apply(b, all, 300, 200)
        for (l in all) assertNotNull(LayerRenderer.box(l, 300, 200))
        // Treffer im gedrehten Rahmen
        assertTrue(LayerRenderer.box(TextLayer("t", "Hi", rotation = 45f), 300, 200)!!.contains(150f, 100f))
    }
}
