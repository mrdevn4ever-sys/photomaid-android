package app.photomaid

import app.photomaid.core.edit.EditFormat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.FilterId
import app.photomaid.core.edit.NormRect
import app.photomaid.core.edit.PxSize
import app.photomaid.core.edit.SaveMode
import app.photomaid.core.model.ItemReference
import app.photomaid.feature.editor.EditSaver
import app.photomaid.feature.editor.EditorScreen
import app.photomaid.feature.editor.EditsScreen
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.media.MediaCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

/**
 * Editor (E1–E6): Export-Pipeline, Speichern als Kopie/Ersetzen, Ledger, Pro-Sperre, Verwerfen, gemerkte Speicherart,
 * Stift-Symbol in der Aktionsleiste, Liste „Bearbeitet“ mit „Original wiederherstellen“, Video kürzen (Fake-Trimmer).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+w393dp-h852dp")
class EditorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary

    private fun start(pro: Boolean = true, videos: Int = 0) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 4, videos = videos).also { l -> lib = l } },
            purchase = if (pro) FakePurchaseGateway(PurchaseState.PURCHASED) else null)
    }

    private fun <T> io(block: suspend () -> T): T = runBlocking { kotlinx.coroutines.withContext(Dispatchers.IO) { block() } }
    private fun waitTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun click(tag: String) { rule.openMoreFor(tag) { rule.waitForIdle() }; rule.onNodeWithTag(tag, useUnmergedTree = false).performSemanticsAction(SemanticsActions.OnClick); rule.waitForIdle() }
    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    private fun file(id: String) = File(Uri.parse(id).path!!)
    private fun rec(id: String) = app.ledger.stateFlow.value.records[ItemReference.photo(id)]

    /** Robolectric: Kodieren nicht parallel zur Compose-Darstellung (native Grafik ist dort nicht threadsicher). Nur in UI-Tests. */
    /**
     * Während des Speicherns läuft ein unbestimmter Fortschrittskreis (Endlos-Animation): Compose wird nie „idle“, also
     * die Hauptschleife und die Frames hier selbst weiterschalten.
     */
    private fun pumpUntil(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        rule.mainClock.autoAdvance = false
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "Bedingung nicht erfüllt" }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            rule.mainClock.advanceTimeByFrame()
            Thread.sleep(5)
        }
        rule.mainClock.autoAdvance = true
    }

    private fun uiEngine() { app.editEngine.io = Dispatchers.Main; app.editEngine.parallel = false }

    private fun openEditor(i: Int = 0, nav: RecordingNav = RecordingNav(), closed: () -> Unit = {}) {
        uiEngine()
        val id = lib.all[i].id
        rule.setContent { TestHost(app) { EditorScreen(id, nav, onClose = closed) } }
        waitTag("editor.crop")
    }

    // MARK: Export-Pipeline (echte Pixel, kleine Bilder)

    @Test fun exportRotatesCropsAndFiltersWithoutTouchingOriginal() {
        start()
        io {
        val a = lib.all[0]
        val before = sha(file(a.id))
        val src = app.editEngine.loadPreview(a.uri, a.mimeType)
        assertEquals(300, src.full.width); assertEquals(400, src.full.height)
        val recipe = EditRecipe(quarterTurns = 1, crop = NormRect(0f, 0f, 0.5f, 1f), filter = FilterId.MONO)
        val out = app.editEngine.export(a.uri, a.mimeType, recipe, src.full, removeLocation = false, softwareTag = "GaleriQ")
        val b = BitmapFactory.decodeFile(out.file.absolutePath)
        // Gedreht → 400 × 300, linke Hälfte → 200 × 300.
        assertEquals(200, b.width); assertEquals(300, b.height)
        val px = b.getPixel(100, 150)
        val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val bl = px and 0xFF
        assertTrue("Schwarzweiß: r=$r g=$g b=$bl", kotlin.math.abs(r - g) <= 2 && kotlin.math.abs(g - bl) <= 2)
        assertEquals("image/png", out.format.mime)
        assertEquals(before, sha(file(a.id))) // Original unverändert (SHA-256)
        out.file.delete()
        }
    }

    @Test fun jpegOrientationOnlyIsLosslessAndKeepsExif() {
        start()
        io {
        val f = jpegWithExif()
        val pixelsBefore = BitmapFactory.decodeFile(f.absolutePath).let { it.getPixel(10, 10) }
        val out = app.editEngine.export(Uri.fromFile(f), "image/jpeg", EditRecipe(quarterTurns = 1), PxSize(120, 80),
            removeLocation = true, softwareTag = "GaleriQ")
        assertTrue(out.lossless)
        val ex = ExifInterface(out.file)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals("2026:09:20 10:11:12", ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNull(ex.latLong) // Standort entfernt
        // Bilddaten unverändert (nur EXIF geändert): gleiche Pixel ohne Ausrichtung dekodiert.
        assertEquals(pixelsBefore, BitmapFactory.decodeFile(out.file.absolutePath).getPixel(10, 10))
        out.file.delete()
        }
    }

    @Test fun jpegRenderCopiesExifAndCanDropLocation() {
        start()
        io {
        val f = jpegWithExif()
        val keep = app.editEngine.export(Uri.fromFile(f), "image/jpeg", EditRecipe(crop = NormRect(0.25f, 0f, 1f, 1f)), PxSize(120, 80),
            removeLocation = false, softwareTag = "GaleriQ")
        assertFalse(keep.lossless)
        val ek = ExifInterface(keep.file)
        assertEquals("2026:09:20 10:11:12", ek.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNotNull(ek.latLong)
        assertEquals(ExifInterface.ORIENTATION_NORMAL, ek.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        assertEquals(90, BitmapFactory.decodeFile(keep.file.absolutePath).width)
        val drop = app.editEngine.export(Uri.fromFile(f), "image/jpeg", EditRecipe(crop = NormRect(0.25f, 0f, 1f, 1f)), PxSize(120, 80),
            removeLocation = true, softwareTag = "GaleriQ")
        assertNull(ExifInterface(drop.file).latLong)
        keep.file.delete(); drop.file.delete()
        }
    }

    /** T9 „Standort entfernen“: gleicher Weg wie das Werkzeug – JPEG verlustfrei kopiert, nur GPS gelöscht, Aufnahmezeit bleibt. */
    @Test fun stripLocationToolPathIsLosslessAndRemovesOnlyGps() {
        start()
        io {
        val f = jpegWithExif()
        assertTrue(app.editEngine.hasLocation(Uri.fromFile(f)))
        val out = app.editEngine.export(Uri.fromFile(f), "image/jpeg", EditRecipe(), PxSize(120, 80), removeLocation = true,
            softwareTag = "GaleriQ", formatOverride = EditFormat.JPEG, quality = null)
        assertTrue(out.lossless)
        assertFalse(app.editEngine.hasLocation(Uri.fromFile(out.file)))
        assertEquals("2026:09:20 10:11:12", ExifInterface(out.file).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        val a = BitmapFactory.decodeFile(f.absolutePath); val b = BitmapFactory.decodeFile(out.file.absolutePath)
        assertEquals(a.width, b.width); assertEquals(a.getPixel(10, 10), b.getPixel(10, 10)); assertEquals(a.getPixel(100, 70), b.getPixel(100, 70))
        assertTrue(ExifInterface(f).latLong != null) // Original unverändert
        out.file.delete()
        }
    }

    private fun jpegWithExif(): File {
        val f = File(app.context.cacheDir, "exif_${System.nanoTime()}.jpg")
        val bmp = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(0xFF3366CC.toInt()); drawRect(0f, 0f, 60f, 80f, Paint().apply { color = 0xFFEE8833.toInt() }) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(f).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:09:20 10:11:12")
            setLatLong(47.37, 8.54)
            saveAttributes()
        }
        return f
    }

    // MARK: Speichern + Ledger

    @Test fun copyIsKeptAndOriginalStays() {
        start()
        io {
        val a = lib.all[0]
        val src = app.editEngine.loadPreview(a.uri, a.mimeType)
        val r = EditSaver.savePhoto(app, a, EditRecipe(quarterTurns = 1), src.full, SaveMode.COPY, removeLocation = false)
        assertFalse(r.replaced)
        assertTrue(lib.all.any { it.id == a.id } && a.id !in lib.removed)
        assertEquals(Disposition.KEPT, rec(r.newId)?.disposition)
        assertEquals("QA_0_bearbeitet.png", lib.saved.single().displayName)
        assertEquals(a.relativePath ?: "DCIM/Camera/", lib.saved.single().relativePath ?: "DCIM/Camera/")
        assertEquals(1, app.edits.records.value.size)
        assertTrue(r.bytes > 0)
        }
    }

    @Test fun replaceTrashesOriginalAndLeavesBasket() {
        start()
        io {
        val a = lib.all[1]
        app.decide(a.reference, DecisionAction.MarkForCleanup)
        val src = app.editEngine.loadPreview(a.uri, a.mimeType)
        val r = EditSaver.savePhoto(app, a, EditRecipe(flipH = true), src.full, SaveMode.REPLACE, removeLocation = false)
        assertTrue(r.replaced)
        assertTrue(a.id in lib.trashed)
        assertEquals(Disposition.KEPT, rec(r.newId)?.disposition)
        assertFalse(rec(a.id)?.disposition == Disposition.MARKED_FOR_CLEANUP) // nicht mehr im PhotoMaid-Papierkorb
        assertEquals(SaveMode.REPLACE, app.edits.records.value.first().mode)
        }
    }

    @Test fun replaceDeclinedKeepsBoth() {
        start()
        io {
        lib.replaceConfirmed = false
        val a = lib.all[0]
        val src = app.editEngine.loadPreview(a.uri, a.mimeType)
        val r = EditSaver.savePhoto(app, a, EditRecipe(quarterTurns = 2), src.full, SaveMode.REPLACE, removeLocation = false)
        assertFalse(r.replaced); assertTrue(r.replaceDeclined)
        assertTrue(lib.trashed.isEmpty())
        assertEquals(SaveMode.COPY, app.edits.records.value.first().mode)
        }
    }

    // MARK: Editor-UI

    @Test fun rotateAndSaveCopyRemembersMode() {
        start()
        var closed = false
        openEditor(closed = { closed = true })
        rule.onNodeWithTag("editor.size").assertExists()
        rule.onNode(hasText("300 × 400", substring = true)).assertExists()
        click("editor.rotate")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("400 × 300", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        click("editor.save")
        waitTag("editor.sheet")
        rule.onNodeWithTag("editor.mode.COPY").assertIsSelected() // erstes Mal: Original behalten
        rule.onNodeWithTag("editor.sheet.save").performSemanticsAction(SemanticsActions.OnClick)
        pumpUntil { closed }
        assertEquals(1, app.edits.records.value.size)
        assertEquals(SaveMode.COPY, app.settings.value.editSaveMode)
    }

    @Test fun replaceModeIsExplicitAndNeverPreselected() {
        start()
        // Selbst wenn zuletzt „Original in den Papierkorb“ gewählt war: Vorauswahl ist immer die Kopie (Redesign 2026-10).
        kotlinx.coroutines.runBlocking { app.updateSettings { it.copy(editSaveMode = SaveMode.REPLACE) } }
        var closed = false
        openEditor(closed = { closed = true })
        click("editor.flipH")
        click("editor.save")
        waitTag("editor.sheet")
        rule.onNodeWithTag("editor.mode.COPY").assertIsSelected()
        click("editor.mode.REPLACE")
        rule.onNodeWithTag("editor.mode.REPLACE").assertIsSelected()
        rule.onNode(hasText("Android fragt einmal nach, ob das Original in den Android-Papierkorb darf.")).assertExists()
        rule.onNodeWithTag("editor.sheet.save").performSemanticsAction(SemanticsActions.OnClick)
        pumpUntil { closed }
        assertEquals(SaveMode.REPLACE, app.settings.value.editSaveMode)
        assertTrue(lib.trashed.isNotEmpty())
    }

    @Test fun proToolsPreviewFreeButSaveLocked() {
        start(pro = false)
        val nav = RecordingNav()
        openEditor(nav = nav)
        click("tab.FILTER")
        waitTag("filter.MONO")
        click("filter.MONO")
        waitTag("editor.prohint")
        click("editor.save")
        waitTag("editor.sheet.pro")
        assertTrue(rule.onAllNodesWithTag("editor.sheet.save").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag("editor.sheet.geometry").fetchSemanticsNodes().isEmpty()) // keine Geometrie geändert
        click("editor.sheet.unlock")
        assertTrue("pro" in nav.events)
        assertTrue(app.edits.records.value.isEmpty())
    }

    @Test fun freeCanSaveCropOnlyFromLockedSheet() {
        start(pro = false)
        var closed = false
        openEditor(closed = { closed = true })
        click("editor.rotate")
        click("tab.ADJUST")
        waitTag("adjust.CONTRAST")
        click("tab.FILTER")
        waitTag("filter.WARM")
        click("filter.WARM")
        click("editor.save")
        waitTag("editor.sheet.geometry")
        rule.onNodeWithTag("editor.sheet.geometry").performSemanticsAction(SemanticsActions.OnClick)
        pumpUntil { closed }
        val saved = BitmapFactory.decodeFile(File(Uri.parse(app.edits.records.value.first().newId).path!!).absolutePath)
        assertEquals(400, saved.width) // gedreht, ohne Filter
    }

    @Test fun cancelWithChangesAsksToDiscard() {
        start()
        var closed = false
        openEditor(closed = { closed = true })
        click("editor.cancel")
        assertTrue(closed) // ohne Änderungen sofort zu
        closed = false
        click("editor.rotate")
        click("editor.cancel")
        waitTag("editor.discard")
        assertFalse(closed)
        click("editor.discard.confirm")
        assertTrue(closed)
        assertTrue(app.edits.records.value.isEmpty())
        assertTrue(lib.saved.isEmpty())
    }

    @Test fun undoRedoInEditor() {
        start()
        openEditor()
        click("editor.rotate")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("400 × 300", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        click("editor.undo")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("300 × 400", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        click("editor.redo")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("400 × 300", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun resizeNeverUpscales() {
        start()
        openEditor()
        click("tab.RESIZE")
        waitTag("size.result")
        // 300 × 400: keine der Vorgaben (4096/2048/1080) verkleinert → nur „Originalgröße“ und „Eigene“.
        assertTrue(rule.onAllNodesWithTag("size.1080").fetchSemanticsNodes().isEmpty())
        rule.onNode(hasText("Dieses Foto ist schon klein. GaleriQ vergrößert nie.")).assertExists()
    }

    // MARK: Einstieg im Feed + Rückmeldung

    @Test fun pencilInActionBarOpensEditorWithoutDecision() {
        start()
        val nav = RecordingNav()
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), nav, onClose = {}, openBasket = {}) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("feed.media.ready").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("action.edit").assertExists()
        rule.onNodeWithTag("action.share").assertExists()
        click("action.edit")
        assertTrue(nav.events.any { it.startsWith("editor:") })
        assertTrue(app.ledger.stateFlow.value.events.isEmpty())
    }

    @Test @Config(qualifiers = "w320dp-h640dp") fun narrowPhoneMovesShareIntoMenu() {
        start()
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), RecordingNav(), onClose = {}, openBasket = {}) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("feed.media.ready").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("action.edit").assertExists()
        assertTrue(rule.onAllNodesWithTag("action.share").fetchSemanticsNodes().isEmpty())
        click("action.more")
        waitTag("action.share")
    }

    @Test fun feedSwapsReplacedItemAndShowsNotice() {
        start()
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)), RecordingNav(), onClose = {}, openBasket = {}) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("feed.media.ready").fetchSemanticsNodes().isNotEmpty() }
        val a = lib.assets(MediaCategory.PHOTOS).first()
        runBlocking(Dispatchers.IO) {
            val src = app.editEngine.loadPreview(a.uri, a.mimeType)
            EditSaver.savePhoto(app, a, EditRecipe(quarterTurns = 1), src.full, SaveMode.REPLACE, removeLocation = false)
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Original ersetzt · liegt im Android-Papierkorb")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("1 von 4")).assertExists() // gleiche Stelle, keine Entscheidung gezählt
    }

    // MARK: Liste „Bearbeitet“

    @Test fun editsListRestoresReplacedOriginal() {
        start()
        val a = lib.all[0]
        runBlocking(Dispatchers.IO) {
            val src = app.editEngine.loadPreview(a.uri, a.mimeType)
            EditSaver.savePhoto(app, a, EditRecipe(quarterTurns = 1), src.full, SaveMode.REPLACE, removeLocation = false)
            val b = lib.all[1]
            EditSaver.savePhoto(app, b, EditRecipe(quarterTurns = 3), app.editEngine.loadPreview(b.uri, b.mimeType).full, SaveMode.COPY, removeLocation = false)
        }
        val nav = RecordingNav()
        rule.setContent { TestHost(app) { EditsScreen(nav) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("edits.row").fetchSemanticsNodes().size == 2 }
        rule.onNode(hasText("Original angepasst")).assertExists()
        rule.onNode(hasText("Kopie")).assertExists()
        rule.onNode(hasText("Original liegt im Android-Papierkorb")).assertExists()
        click("edits.restore")
        rule.waitUntil(10_000) { a.id !in lib.trashed && rule.onAllNodes(hasText("Original wurde wiederhergestellt")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("edits.message").assertExists()
    }

    // MARK: Video kürzen

    @Test fun trimVideoWithProSavesCopy() {
        start(videos = 1)
        app.videoTrimmer = FakeVideoTrimmer(rule.activity)
        val v = lib.all.last()
        var closed = false
        uiEngine()
        rule.setContent { TestHost(app) { EditorScreen(v.id, RecordingNav(), onClose = { closed = true }) } }
        waitTag("trim.timeline")
        rule.onNodeWithTag("trim.start").performSemanticsAction(SemanticsActions.SetProgress) { it(5_000f) }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("0:05 – 0:32 · 27 s")).fetchSemanticsNodes().isNotEmpty() }
        click("editor.save")
        waitTag("editor.sheet.save")
        rule.onNodeWithTag("editor.sheet.save").performSemanticsAction(SemanticsActions.OnClick)
        pumpUntil { closed }
        val t = app.videoTrimmer as FakeVideoTrimmer
        assertEquals(5_000L, t.calls.single().startMs)
        assertTrue(app.edits.records.value.single().video)
        assertEquals("video/mp4", lib.saved.single().mime)
    }

    @Test fun trimVideoNeedsPro() {
        start(pro = false, videos = 1)
        app.videoTrimmer = FakeVideoTrimmer(rule.activity)
        val v = lib.all.last()
        uiEngine()
        rule.setContent { TestHost(app) { EditorScreen(v.id, RecordingNav(), onClose = {}) } }
        waitTag("trim.timeline")
        waitTag("editor.prohint")
        rule.onNodeWithTag("trim.start").performSemanticsAction(SemanticsActions.SetProgress) { it(3_000f) }
        click("editor.save")
        waitTag("editor.sheet.pro")
        assertTrue((app.videoTrimmer as FakeVideoTrimmer).calls.isEmpty())
    }
}
