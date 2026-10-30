package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.edit.EditKind
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.MediaKind
import app.photomaid.feature.studio.StudioScreen
import app.photomaid.feature.studio.ToolKind
import app.photomaid.feature.studio.ToolRequest
import app.photomaid.feature.studio.ToolsScreen
import app.photomaid.media.reviewableMedia
import app.photomaid.core.capabilities.PurchaseState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** P7: Stapel-Werkzeuge, Screenshots zusammenfügen, GIF. Immer neue Dateien, Quellen ohne Entscheidung. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+w393dp-h852dp")
class ToolsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()

    private fun start(pro: Boolean = false, count: Int = 6) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = count, videos = 1).also { l -> lib = l
            // Gemeldete Maße = echte Testdateien (300 × 400), sonst würde die Testumgebung riesige Bilder rechnen.
            l.all.forEach { a -> l.dims[a.id] = 300 to 400 } } },
            purchase = if (pro) FakePurchaseGateway(PurchaseState.PURCHASED) else null)
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
    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    private fun click(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
    private fun has(t: String) = rule.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private val req = androidx.compose.runtime.mutableStateOf<ToolRequest?>(null)
    private var shown = false
    /** Ein Inhalt je Test; ein neuer Auftrag ersetzt den Bildschirm (eigener Schlüssel = frischer Zustand). */
    private fun open(kind: ToolKind, ids: List<String>) {
        req.value = ToolRequest("t${System.nanoTime()}", kind, ids)
        if (!shown) { shown = true; rule.setContent { TestHost(app) { req.value?.let { r -> androidx.compose.runtime.key(r.id) { ToolsScreen(r, nav, onClose = { nav.events += "closed" }) } } } } }
        pumpUntil { count("tools.count") == 1 && rule.onNodeWithTag("tools.count").fetchSemanticsNode().config.toString().contains("${ids.size}") }
    }

    @Test fun studioOffersToolsThroughThePicker() {
        start()
        rule.setContent { TestHost(app) { StudioScreen(nav) } }
        pumpUntil { count("studio.tool.GIF") == 1 }
        click("studio.tool.STITCH")
        val pick = nav.picks.single()
        assertEquals(2, pick.min); assertTrue(pick.library); assertFalse(pick.videos)
        pick.onDone(listOf("a", "b"))
        assertEquals(ToolKind.STITCH, nav.tools.single().kind)
        click("studio.tool.GIF"); assertTrue(nav.picks.last().videos)
    }

    @Test fun resizeWritesCopiesSkipsVideosAndNeverDecidesSources() {
        start()
        val media = lib.reviewableMedia()
        val photos = media.filter { it.kind != MediaKind.VIDEO }.take(3)
        val video = media.first { it.kind == MediaKind.VIDEO }
        open(ToolKind.RESIZE, photos.map { it.id } + video.id)
        click("tools.edge.1080")
        // Testbilder sind kleiner als 1080 px: nichts zu tun → übersprungen, keine sinnlose Kopie.
        click("tools.start")
        pumpUntil { has("übersprungen") }
        assertTrue(lib.saved.isEmpty())
        assertTrue(has("Deine Originale sind unverändert"))
    }

    @Test fun convertMakesCopiesKeepsThemAndCanTrashOriginals() {
        start()
        val photos = lib.reviewableMedia().filter { it.kind != MediaKind.VIDEO }.take(2)
        open(ToolKind.CONVERT, photos.map { it.id })
        click("tools.fmt.JPEG")
        click("tools.trash")
        click("tools.start")
        pumpUntil { lib.saved.size == 2 && has("Kopien gespeichert") }
        assertTrue(lib.saved.all { it.mime == "image/jpeg" && it.displayName.endsWith(".jpg") && !it.replace })
        // Neue Dateien = „Behalten“ und als Umwandlung vermerkt; Originale im Android-Papierkorb, ohne Ledger-Entscheidung.
        val recs = app.edits.records.value.filter { it.kind == EditKind.CONVERT }
        assertEquals(2, recs.size)
        assertTrue(recs.all { runBlocking { app.ledger.record(ItemReference.photo(it.newId)) }.disposition == Disposition.KEPT })
        assertTrue(photos.all { it.id in lib.trashed })
        assertTrue(photos.all { runBlocking { app.ledger.record(it.reference) }.disposition == Disposition.UNDECIDED })
        assertTrue(has("Gemessen"))
    }

    @Test fun freeBatchIsLimitedAndGifNeedsPro() {
        start(count = 12)
        val photos = lib.reviewableMedia().filter { it.kind != MediaKind.VIDEO }
        open(ToolKind.STRIP_LOCATION, photos.map { it.id })
        assertEquals(1, count("tools.limit"))
        open(ToolKind.GIF, photos.take(3).map { it.id })
        pumpUntil { has("Mit Pro erstellen") }
        click("tools.start")
        assertTrue(nav.events.contains("pro"))
        assertTrue(lib.saved.isEmpty())
    }

    @Test fun stitchAndGifSaveOwnImagesInPhotoMaidFolder() {
        start(pro = true)
        val shots = lib.reviewableMedia().filter { it.kind != MediaKind.VIDEO }.take(3)
        open(ToolKind.STITCH, shots.map { it.id })
        pumpUntil { count("tools.preview") == 1 }
        click("tools.start")
        pumpUntil { lib.saved.size == 1 }
        val st = lib.saved.single()
        assertEquals("image/png", st.mime); assertEquals("Pictures/GaleriQ/", st.relativePath)
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val out = File(lib.all.last().id.removePrefix("file://"))
        android.graphics.BitmapFactory.decodeFile(out.absolutePath, o)
        assertEquals(300, o.outWidth); assertEquals(1200, o.outHeight) // untereinander, gleiche Breite, nicht verzerrt
        pumpUntil { has("Pictures/GaleriQ") }

        rule.onAllNodes(hasText("OK")).fetchSemanticsNodes() // Hinweis offen lassen: neuer Auftrag ersetzt den Bildschirm
        open(ToolKind.GIF, shots.map { it.id })
        pumpUntil { count("tools.gifdelay.500") == 1 }
        click("tools.gifdelay.500")
        click("tools.start")
        pumpUntil { lib.saved.size == 2 }
        val gif = File(lib.all.last().id.removePrefix("file://"))
        assertEquals("GIF89a", String(gif.readBytes().copyOfRange(0, 6)))
        pumpUntil { app.edits.records.value.any { it.kind == EditKind.GIF && it.count == 3 } }
        assertTrue(shots.all { runBlocking { app.ledger.record(it.reference) }.disposition == Disposition.UNDECIDED })
    }
}
