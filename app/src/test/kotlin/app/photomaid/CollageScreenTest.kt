package app.photomaid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.edit.EditKind
import app.photomaid.core.model.ItemReference
import app.photomaid.core.studio.CollageBackground
import app.photomaid.core.studio.CollageKind
import app.photomaid.core.studio.CollageProject
import app.photomaid.feature.studio.CollageRenderer
import app.photomaid.feature.studio.CollageRequest
import app.photomaid.feature.studio.CollageScreen
import app.photomaid.feature.studio.Draft
import app.photomaid.feature.studio.StudioScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Collage: Zeichnen (Vorschau = Export), Speichern als neues Bild (= Behalten), Pro-Grenze, Entwürfe, Studio-Tab. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+w393dp-h852dp")
class CollageScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary

    private fun start(pro: Boolean = false, count: Int = 6) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = count, videos = 0).also { l -> lib = l } },
            purchase = if (pro) FakePurchaseGateway(PurchaseState.PURCHASED) else null)
        app.editEngine.io = Dispatchers.Main; app.editEngine.parallel = false
    }
    private fun waitTag(tag: String) = pumpUntil { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun click(tag: String) { rule.openMoreFor(tag) { pumpUntil { true } }; rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
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
    private fun open(n: Int, closed: () -> Unit = {}, nav: RecordingNav = RecordingNav()): CollageProject {
        val p = CollageProject.create("c1", lib.all.take(n).map { it.id })
        rule.setContent { TestHost(app) { CollageScreen(CollageRequest("r1", p, fromDraft = false), nav, pick = {}, onClose = closed) } }
        waitTag("collage.canvas")
        return p
    }
    private fun solid(color: Int, w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun gridRendersEachPhotoInItsCellWithSpacing() {
        start()
        val ids = listOf("a", "b")
        val p = CollageProject.create("x", ids).copy(aspect = 2f, spacing = 0.1f, margin = 0f, background = CollageBackground(color = Color.WHITE))
        val out = CollageRenderer(app.editEngine).render(p, 400, 200, listOf(solid(Color.RED, 50, 50), solid(Color.BLUE, 50, 50)))
        assertEquals(Color.RED, out.getPixel(40, 100))
        assertEquals(Color.BLUE, out.getPixel(360, 100))
        assertEquals(Color.WHITE, out.getPixel(200, 100)) // Abstand in der Mitte
    }

    @Test fun stripStacksPhotosAtFullWidth() {
        start()
        val p = CollageProject.create("x", listOf("a", "b"), CollageKind.STRIP)
        val r = CollageRenderer(app.editEngine)
        val sizes = listOf(100 to 50, 100 to 100)
        val (w, h) = r.size(p, 300, sizes)
        // Gleiche Breite, Höhen im Seitenverhältnis: 1/2 + 1 → Höhe 1,5 × Breite.
        assertEquals(1.5f, h.toFloat() / w, 0.02f)
        val out = r.render(p, w, h, sizes, { i -> if (i == 0) solid(Color.RED, 100, 50) else solid(Color.BLUE, 100, 100) }, { _, _ -> })
        assertEquals(Color.RED, out.getPixel(w / 2, h / 6))
        assertEquals(Color.BLUE, out.getPixel(w / 2, h * 2 / 3))
    }

    @Test fun collageSavesAsNewKeptImageAndRecordsKind() {
        start()
        var closed = false
        open(3, closed = { closed = true })
        click("collage.save"); waitTag("collage.sheet.save")
        assertTrue(rule.onAllNodesWithTag("collage.sheet.pro").fetchSemanticsNodes().isEmpty())
        click("collage.sheet.save")
        pumpUntil { closed }
        val rec = app.edits.records.value.single()
        assertEquals(EditKind.COLLAGE, rec.kind)
        assertEquals(3, rec.count)
        assertEquals("", rec.sourceId)
        val f = File(android.net.Uri.parse(rec.newId).path!!)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.absolutePath, o)
        assertEquals(2048, maxOf(o.outWidth, o.outHeight)) // Standard
        assertEquals(Disposition.KEPT, app.ledger.stateFlow.value.records[ItemReference.photo(rec.newId)]?.disposition)
        // Die Quellfotos bleiben unberührt (keine Entscheidung).
        for (a in lib.all.take(3)) assertTrue(app.ledger.stateFlow.value.records[ItemReference.photo(a.id)] == null)
    }

    @Test fun moreThanFourPhotosNeedsProButCanBeTried() {
        start(pro = false)
        open(5)
        waitTag("collage.prohint")
        click("collage.save"); waitTag("collage.sheet.pro")
        assertTrue(rule.onAllNodesWithTag("collage.sheet.save").fetchSemanticsNodes().isEmpty())
    }

    @Test fun proSavesFivePhotos() {
        start(pro = true)
        var closed = false
        open(5, closed = { closed = true })
        assertTrue(rule.onAllNodesWithTag("collage.prohint").fetchSemanticsNodes().isEmpty())
        click("collage.save"); waitTag("collage.sheet.save"); click("collage.sheet.save")
        pumpUntil { closed }
        assertEquals(5, app.edits.records.value.single().count)
    }

    @Test fun dividerDragChangesLayoutAndCanBeUndone() {
        start()
        open(2)
        rule.onNodeWithTag("collage.undo").assertIsNotEnabled()
        rule.onAllNodesWithTag("collage.divider").onFirst().performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(80f, 0f)); moveBy(androidx.compose.ui.geometry.Offset(80f, 0f)); up() }
        pumpUntil { true }
        rule.onNodeWithTag("collage.undo").assertIsEnabled()
        click("collage.undo")
        rule.onNodeWithTag("collage.undo").assertIsNotEnabled()
    }

    @Test fun cancelAfterChangeOffersDraftAndKeepsIt() {
        start()
        var closed = false
        open(3, closed = { closed = true })
        click("template.r:1,2")
        click("collage.cancel"); waitTag("collage.draft")
        click("collage.draft.keep")
        pumpUntil { closed }
        val d = app.drafts.items.value.single()
        assertEquals("r:1,2", d.collage?.template)
        assertTrue(app.edits.records.value.isEmpty())
    }

    @Test fun studioListsDraftsAndDeletesAfterConfirm() {
        start()
        val p = CollageProject.create("d1", lib.all.take(2).map { it.id })
        runBlocking { app.drafts.put(Draft("d1", 1_000L, collage = p), null, 3) }
        val nav = RecordingNav()
        rule.setContent { TestHost(app) { StudioScreen(nav) } }
        waitTag("studio.draft")
        click("studio.edits")
        assertTrue(nav.events.contains("push:edits"))
        click("studio.draft.delete"); waitTag("studio.draft.confirm")
        click("studio.draft.delete.ok")
        pumpUntil { app.drafts.items.value.isEmpty() }
    }
}
