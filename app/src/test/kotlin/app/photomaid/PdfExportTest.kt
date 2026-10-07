package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.document.Caption
import app.photomaid.core.document.ImageFit
import app.photomaid.core.document.PdfOptions
import app.photomaid.core.document.PerPage
import app.photomaid.core.model.MediaKind
import app.photomaid.feature.document.PdfOutcome
import app.photomaid.feature.document.PdfRequest
import app.photomaid.feature.document.PdfScreen
import app.photomaid.feature.document.buildPdf
import app.photomaid.core.capabilities.PurchaseState
import kotlinx.coroutines.Dispatchers
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

/**
 * Paket 4 Dokumente: PDF aus Fotos. Quellen bleiben unverändert und ohne Entscheidung; das PDF ist kein Medium der
 * Mediathek; Bilder nie verzerrt; keine Aufnahmedaten oder Dateinamen als Text im PDF.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w393dp-h852dp")
class PdfExportTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()

    private fun start(pro: Boolean = false) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 5, videos = 1).also { l -> lib = l } },
            purchase = if (pro) FakePurchaseGateway(PurchaseState.PURCHASED) else null)
        app.editEngine.io = Dispatchers.Main; app.editEngine.parallel = false
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
    private fun count(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun click(tag: String, i: Int = 0) { rule.onAllNodesWithTag(tag, useUnmergedTree = true)[i].performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
    private fun photos() = lib.all.filter { it.kind != MediaKind.VIDEO }
    private fun assertUntouched(before: Int) {
        assertTrue("Ledger muss leer bleiben", app.ledger.stateFlow.value.records.isEmpty())
        assertTrue(lib.trashed.isEmpty()); assertTrue(lib.deleted.isEmpty()); assertTrue(lib.removed.isEmpty())
        assertEquals("PDF ist kein Medium der Mediathek", before, lib.all.size)
    }
    private fun pdfText(name: String) = String(lib.documents.getValue(name).readBytes(), Charsets.ISO_8859_1)

    @Test fun savesMultiPagePdfWithoutTouchingSources() {
        start()
        val before = lib.all.size
        val (r, _) = runBlocking { buildPdf(app, photos(), PdfOptions(perPage = PerPage.TWO), "Test.pdf", share = false) }
        r as PdfOutcome.Saved
        assertEquals(3, r.pages); assertEquals(0, r.unreadable)
        val s = pdfText("Test.pdf")
        assertTrue(s.startsWith("%PDF-1.4"))
        assertEquals(3, Regex("/Type /Page /Parent").findAll(s).count())
        assertEquals(5, Regex("/Filter /DCTDecode").findAll(s).count())
        assertEquals("gemessen", lib.documents.getValue("Test.pdf").length(), r.bytes)
        assertUntouched(before)
    }

    @Test fun imagesKeepTheirAspectAndNoMetadataOrNamesLeak() {
        start()
        val ph = photos()
        runBlocking { buildPdf(app, ph, PdfOptions(perPage = PerPage.FOUR, fit = ImageFit.FIT, caption = Caption.NAME), "Meta.pdf", share = false) }
        val s = pdfText("Meta.pdf")
        val dims = Regex("/Width (\\d+) /Height (\\d+) /ColorSpace /DeviceRGB").findAll(s).map { it.groupValues[1].toDouble() / it.groupValues[2].toDouble() }.toList()
        val draws = Regex("q ([\\d.]+) 0 0 ([\\d.]+) [\\d.]+ [\\d.]+ cm /Im(\\d+) Do Q").findAll(s).toList()
        // Je Seite: Bild, Beschriftung, Bild, … – die Bilder (gerade Indizes) haben das Verhältnis ihrer Pixel.
        val imageDraws = draws.filter { it.groupValues[3].toInt() % 2 == 0 }.map { it.groupValues[1].toDouble() / it.groupValues[2].toDouble() }
        assertEquals(dims.size, imageDraws.size)
        dims.zip(imageDraws).forEach { (px, drawn) -> assertEquals("nie verzerrt", px, drawn, px * 0.02) }
        assertFalse("keine EXIF-Daten", s.contains("Exif"))
        for (a in ph) assertFalse("Dateiname nur als Bild, nie als Text", s.contains(a.displayName!!.substringBeforeLast('.')))
        assertEquals(ph.size, Regex("/ColorSpace /DeviceGray").findAll(s).count())
    }

    @Test fun shareStagesFileWithoutSaving() {
        start()
        val (r, uri) = runBlocking { buildPdf(app, photos().take(2), PdfOptions(), "Teilen.pdf", share = true) }
        assertTrue(r is PdfOutcome.Shared); assertTrue(uri != null)
        assertTrue("nichts gespeichert", lib.documents.isEmpty())
    }

    @Test fun screenSkipsVideosAndReorders() {
        start()
        rule.setContent { TestHost(app) { PdfScreen(PdfRequest("r", lib.all.map { it.id }), nav) {} } }
        pumpUntil { count("pdf.summary") == 1 }
        assertEquals("nur die 5 Fotos", 5, count("pdf.up"))
        assertEquals(5, count("pdf.page"))
        click("pdf.down", 0)
        assertUntouched(lib.all.size)
    }

    @Test fun proSavesFromScreen() {
        start(pro = true)
        rule.setContent { TestHost(app) { PdfScreen(PdfRequest("r", photos().map { it.id }), nav) {} } }
        pumpUntil { count("pdf.save") == 1 }
        click("pdf.save")
        pumpUntil { lib.documents.isNotEmpty() }
        assertTrue(lib.documents.keys.single().endsWith(".pdf"))
        assertTrue(app.ledger.stateFlow.value.records.isEmpty())
    }

    @Test fun freeContactSheetAsksForPro() {
        start()
        rule.setContent { TestHost(app) { PdfScreen(PdfRequest("r", photos().map { it.id }, PdfOptions(perPage = PerPage.FOUR)), nav) {} } }
        pumpUntil { count("pdf.save") == 1 }
        assertEquals(1, count("editor.prohint"))
        assertEquals("5 Fotos, 4 je Seite", 2, count("pdf.page"))
        click("pdf.save")
        assertTrue("pro" in nav.events); assertTrue(lib.documents.isEmpty())
    }
}
