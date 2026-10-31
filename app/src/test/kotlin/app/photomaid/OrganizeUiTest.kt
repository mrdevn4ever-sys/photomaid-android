package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.feature.library.LibraryScreen
import app.photomaid.feature.library.ViewerRequest
import app.photomaid.feature.library.ViewerScreen
import app.photomaid.feature.organize.OrganizeScreen
import app.photomaid.feature.organize.OrganizeTarget
import app.photomaid.media.reviewableMedia
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Paket 1 Organisieren: Alben, Schlagwörter, Notizen, Auswahlkorb. In jedem Ablauf gilt: keine Datei wird verändert,
 * gelöscht oder verschoben, und es entsteht keine Aufräum-Entscheidung (Ledger bleibt leer).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w393dp-h852dp")
class OrganizeUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()

    private fun start() { app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 8, videos = 2).also { l -> lib = l } }) }
    private fun pumpUntil(timeoutMs: Long = 20_000, cond: () -> Boolean) {
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
    private fun click(tag: String) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
    /** Laufende Menü-/Blatt-Animationen erst ausklingen lassen (sonst wartet die Texteingabe bei angehaltener Uhr ewig). */
    private fun settle() = repeat(60) { pumpUntil { true } }
    private fun type(tag: String, text: String) { settle(); rule.mainClock.autoAdvance = false; rule.onNodeWithTag(tag).performTextInput(text); pumpUntil { true } }
    private fun ordered() = runBlocking { lib.reviewableMedia() }
    private fun org() = app.organize.state.value

    /** Nichts an Dateien und Entscheidungen geändert. */
    private fun assertUntouched() {
        assertTrue("Ledger muss leer bleiben", app.ledger.stateFlow.value.records.isEmpty())
        assertTrue(lib.trashed.isEmpty()); assertTrue(lib.deleted.isEmpty()); assertTrue(lib.removed.isEmpty())
    }

    private fun selectFirst(n: Int) {
        rule.setContent { TestHost(app) { LibraryScreen(nav) } }
        pumpUntil { count("lib.tile") > 0 }
        rule.onAllNodesWithTag("lib.tile")[0].performSemanticsAction(SemanticsActions.OnLongClick); pumpUntil { count("lib.selbar") == 1 }
        for (i in 1 until n) { rule.onAllNodesWithTag("lib.tile")[i].performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
    }

    @Test fun selectionCreatesAlbumWithoutTouchingFiles() {
        start()
        selectFirst(3)
        click("lib.sel.more"); pumpUntil { count("lib.sel.album") > 0 }
        click("lib.sel.album"); pumpUntil { count("album.picker.new") > 0 }
        click("album.picker.new"); pumpUntil { count("org.name.input") > 0 }
        type("org.name.input", "Ferien 2026")
        click("org.name.ok")
        pumpUntil { org().albums.size == 1 }
        val a = org().albums.single()
        assertEquals("Ferien 2026", a.name)
        assertEquals(ordered().take(3).map { it.id }, a.items)
        pumpUntil { rule.onAllNodes(hasText("3 zu „Ferien 2026“ hinzugefügt.")).fetchSemanticsNodes().isNotEmpty() }
        assertUntouched()
    }

    @Test fun removingFromAlbumAndDeletingAlbumKeepFiles() {
        start()
        val media = ordered()
        val id = runBlocking { app.organize.createAlbum("Familie", media.take(3)) }!!
        var closed = false
        rule.setContent { TestHost(app) { OrganizeScreen(OrganizeTarget.AlbumOf(id), nav) { closed = true } } }
        pumpUntil { count("lib.tile") == 3 }
        rule.onAllNodesWithTag("lib.tile")[0].performSemanticsAction(SemanticsActions.OnLongClick); pumpUntil { count("lib.selbar") == 1 }
        click("org.sel.remove")
        pumpUntil { org().album(id)!!.items.size == 2 }
        pumpUntil { rule.onAllNodes(hasText("Die Datei bleibt erhalten.", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(media.size, ordered().size)
        // Album löschen: Bestätigung erklärt, dass die Medien bleiben.
        rule.onAllNodes(hasText("OK") and hasClickAction()).fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let {
            rule.onAllNodes(hasText("OK") and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
        click("org.more"); pumpUntil { count("org.delete") > 0 }
        click("org.delete")
        pumpUntil { rule.onAllNodes(hasText("Nur die Zusammenstellung wird gelöscht.", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("Album löschen") and hasClickAction()).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        rule.onAllNodes(hasText("Album löschen") and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        pumpUntil { org().albums.isEmpty() && closed }
        assertEquals(media.size, ordered().size)
        assertUntouched()
    }

    @Test fun missingMediaAreListedAndRemovedOnlyOnRequest() {
        start()
        val media = ordered()
        val id = runBlocking { app.organize.createAlbum("Alt", media.take(2)) }!!
        lib.removed += media[0].id   // außerhalb von GaleriQ gelöscht (ohne Fingerabdruck-Treffer)
        lib.changeToken.value += 1
        rule.setContent { TestHost(app) { OrganizeScreen(OrganizeTarget.AlbumOf(id), nav) {} } }
        pumpUntil { count("org.missing") == 1 }
        assertEquals(2, org().album(id)!!.items.size) // nicht still verworfen
        assertEquals(1, count("lib.tile"))
        click("org.missing.remove")
        pumpUntil { org().album(id)!!.items == listOf(media[1].id) }
        lib.removed.clear()
    }

    @Test fun tagsAreAddedToSelectionAndFoundBySearch() {
        start()
        selectFirst(2)
        click("lib.sel.more"); pumpUntil { count("lib.sel.tags") > 0 }
        click("lib.sel.tags"); pumpUntil { count("tag.input") > 0 }
        type("tag.input", "#Rechnung ")
        click("tag.add")
        pumpUntil { org().tags.size == 2 }
        assertEquals(listOf("Rechnung"), org().tags[ordered()[0].id])
        click("tag.done")
        click("lib.seldone")
        // Suche ohne Akzente und Groß/klein findet die eigenen Schlagwörter.
        click("lib.search"); pumpUntil { count("lib.query") > 0 }
        type("lib.query", "rechnung")
        pumpUntil { count("lib.tile") == 2 }
        assertUntouched()
    }

    @Test fun basketCollectsAcrossSessionsAndClearingKeepsFiles() {
        start()
        selectFirst(2)
        click("lib.sel.more"); pumpUntil { count("lib.sel.basket") > 0 }
        click("lib.sel.basket")
        pumpUntil { org().basket.size == 2 }
        pumpUntil { rule.onAllNodes(hasText("2 in den Auswahlkorb gelegt.")).fetchSemanticsNodes().isNotEmpty() }
        // Korb-Symbol im Kopf erscheint, sobald etwas darin liegt (Auswahl beenden).
        rule.onAllNodes(hasText("OK") and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true }
        click("lib.seldone"); pumpUntil { count("lib.basket") == 1 }
        click("lib.basket")
        assertTrue(nav.organize.last() == OrganizeTarget.Basket)
        // Neustart: Korb bleibt (Room).
        val reloaded = runBlocking(kotlinx.coroutines.Dispatchers.IO) { app.organize.also { it.load() }.state.value }
        assertEquals(ordered().take(2).map { it.id }, reloaded.basket)
        assertUntouched()
    }

    @Test fun basketScreenClearsOnlyTheBasket() {
        start()
        val media = ordered()
        runBlocking { app.organize.addToBasket(media.take(3)) }
        rule.setContent { TestHost(app) { OrganizeScreen(OrganizeTarget.Basket, nav) {} } }
        pumpUntil { count("lib.tile") == 3 }
        click("org.more"); pumpUntil { count("org.delete") > 0 }
        click("org.delete")
        rule.onAllNodes(hasText("Korb leeren") and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        pumpUntil { org().basket.isEmpty() && count("org.empty") == 1 }
        assertEquals(media.size, ordered().size)
        assertUntouched()
    }

    @Test fun viewerInfoEditsNoteAndShowsTags() {
        start()
        val list = ordered()
        runBlocking { app.organize.editTags(listOf(list[0]), add = listOf("Wohnung")) }
        rule.setContent { TestHost(app) { ViewerScreen(ViewerRequest("v", list, 0), nav, onClose = {}) } }
        pumpUntil { count("viewer.index") == 1 }
        click("viewer.more"); pumpUntil { count("viewer.info") > 0 }
        click("viewer.info"); pumpUntil { count("viewer.organize") == 1 }
        assertEquals(1, count("viewer.tag.Wohnung"))
        type("viewer.note", "Ersatzteil Spülmaschine")
        click("viewer.note.save")
        pumpUntil { org().notes[list[0].id] == "Ersatzteil Spülmaschine" }
        assertUntouched()
    }
}
