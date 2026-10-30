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
import app.photomaid.core.library.FolderGroup
import app.photomaid.core.library.FolderSort
import app.photomaid.feature.library.FolderScreen
import app.photomaid.feature.library.FolderTarget
import app.photomaid.feature.library.HiddenScreen
import app.photomaid.feature.library.LibraryScreen
import app.photomaid.feature.library.ViewerRequest
import app.photomaid.feature.library.ViewerScreen
import app.photomaid.feature.library.cleanupCandidates
import app.photomaid.feature.library.folderKey
import app.photomaid.feature.library.saveGroup
import app.photomaid.feature.library.setFolderHidden
import app.photomaid.media.MediaCategory
import app.photomaid.media.reviewableMedia
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

/** G2: Ordnerbaum, Sortierung je Ordner, Favoriten je Ordner, Gruppen, neuer Ordner, „In PhotoMaid ausblenden“. Nie eine Entscheidung. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+w393dp-h852dp")
class FoldersTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()

    /** 0–2: Pictures/Urlaub, 3: Pictures/Urlaub/Strand, 4: Pictures/Arbeit, Rest: DCIM/Camera. */
    private fun start() {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 8, videos = 2).also { l -> lib = l } })
        (0..2).forEach { lib.paths[lib.all[it].id] = "Pictures/Urlaub/" }
        lib.paths[lib.all[3].id] = "Pictures/Urlaub/Strand/"
        lib.paths[lib.all[4].id] = "Pictures/Arbeit/"
    }
    private fun pumpUntil(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        rule.mainClock.autoAdvance = false
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(5)
            if (cond()) break
            check(System.currentTimeMillis() < end) { "Bedingung nicht erfüllt: tiles=${count("lib.tile")} rows=${count("lib.folder.row")} hidden=${app.settings.value.hiddenMedia.size}" }
        }
        rule.mainClock.autoAdvance = !holdClock
    }
    /** Dialoge mit Textfeld: Uhr angehalten lassen (blinkender Cursor hält Compose sonst nie still). */
    private var holdClock = false
    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    private fun click(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
    private fun clickText(text: String) {
        pumpUntil { rule.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText(text) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true }
    }
    private fun type(tag: String, text: String) { rule.mainClock.autoAdvance = false; rule.onNodeWithTag(tag).performTextInput(text); pumpUntil { true } }
    private fun showFolders() {
        rule.setContent { TestHost(app) { LibraryScreen(nav) } }
        pumpUntil { count("lib.tile") > 0 }
        clickText("Ordner"); pumpUntil { count("lib.folder.row") > 0 }
    }
    private fun records() = app.ledger.stateFlow.value.records

    @Test fun treeCollapsesChainsExpandsAndOpensFolder() {
        start(); showFolders()
        // „Pictures“ ohne eigene Medien bleibt Elternknoten (zwei Kinder), DCIM/Camera wird zu „Camera“.
        assertEquals(2, count("lib.folder.row"))
        rule.onAllNodesWithTag("lib.folder.expand").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        pumpUntil { count("lib.folder.row") == 4 }
        clickText("Urlaub")
        assertEquals(FolderTarget.Path("Pictures/Urlaub/"), nav.folders.last())
        assertTrue(records().isEmpty())
    }

    @Test fun folderShowsOwnMediaSubfoldersSortAndFavoritesOnly() {
        start()
        lib.favorites[lib.all[1].id] = true
        rule.setContent { TestHost(app) { FolderScreen(FolderTarget.Path("Pictures/Urlaub/"), nav, onClose = {}) } }
        pumpUntil { count("lib.tile") == 3 }
        assertEquals(1, count("folder.sub"))
        click("folder.sort"); pumpUntil { count("folder.sort.NAME") == 1 }
        click("folder.sort.NAME")
        pumpUntil { app.settings.value.folderSorts[folderKey("Pictures/Urlaub/")] == FolderSort.NAME }
        click("folder.sort"); pumpUntil { count("folder.sort.default") == 1 }
        click("folder.sort.default")
        pumpUntil { app.settings.value.defaultFolderSort == FolderSort.NAME && app.settings.value.folderSorts.isEmpty() }
        click("folder.favonly")
        pumpUntil { count("lib.tile") == 1 }
        assertTrue(folderKey("Pictures/Urlaub/") in app.settings.value.folderFavoritesOnly)
        click("folder.sub"); assertEquals(FolderTarget.Path("Pictures/Urlaub/Strand/"), nav.folders.last())
        assertTrue(records().isEmpty())
    }

    @Test fun groupCombinesFoldersWithoutMovingAnything() {
        start(); showFolders()
        click("lib.groups.new"); pumpUntil { count("lib.groupsheet") == 1 }
        type("lib.group.name", "Ferien")
        rule.onAllNodesWithTag("lib.group.pick").fetchSemanticsNodes().size.let { assertTrue(it >= 4) }
        // Urlaub (mit Strand) und Arbeit wählen
        clickText("Urlaub"); clickText("Arbeit")
        click("lib.group.save")
        pumpUntil { app.settings.value.folderGroups.size == 1 }
        val g = app.settings.value.folderGroups.single()
        assertEquals("Ferien", g.name)
        assertEquals(setOf("Pictures/Arbeit/", "Pictures/Urlaub/"), g.paths.toSet())
        pumpUntil { count("lib.group.row") == 1 }
        click("lib.group.row"); assertEquals(FolderTarget.Group(g.id), nav.folders.last())
        assertTrue(lib.writes.isEmpty())
    }

    @Test fun groupScreenShowsAllMembersIncludingSubfolders() {
        start()
        runBlocking { saveGroup(app, FolderGroup("g1", "Ferien", listOf("Pictures/Urlaub/", "Pictures/Arbeit/"))) }
        rule.setContent { TestHost(app) { FolderScreen(FolderTarget.Group("g1"), nav, onClose = {}) } }
        pumpUntil { count("lib.tile") == 5 }
    }

    @Test fun newFolderAsksNameThenMovesPickedMedia() {
        start(); showFolders()
        holdClock = true
        click("lib.folders.new"); pumpUntil { count("lib.newfolder") == 1 }
        type("lib.newfolder.name", "Neu 2026")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("lib.newfolder.next").performSemanticsAction(SemanticsActions.OnClick)
        pumpUntil { nav.picks.isNotEmpty() }
        val req = nav.picks.single()
        assertTrue("Mediathek-Auswahl", req.library)
        req.onDone(listOf(lib.all[6].id))
        pumpUntil { lib.writes.contains("move:Pictures/Neu 2026/") }
        assertTrue(records().isEmpty())
    }

    @Test fun hideMediaAndFolderAndShowAgain() {
        start()
        rule.setContent { TestHost(app) { LibraryScreen(nav) } }
        pumpUntil { count("lib.tile") > 0 }
        rule.onAllNodesWithTag("lib.tile")[0].performSemanticsAction(SemanticsActions.OnLongClick); pumpUntil { count("lib.sel.hide") == 1 }
        val first = lib.reviewableMedia().first().id
        click("lib.sel.hide")
        clickText("In GaleriQ ausblenden")
        pumpUntil { first in app.settings.value.hiddenMedia && app.visiblePhotos.reviewableMedia().none { it.id == first } }
        // Auch beim Aufräumen nicht mehr da, Datei unverändert.
        assertFalse(app.photos.assets(MediaCategory.PHOTOS).any { it.id == first } || app.photos.assets(MediaCategory.VIDEOS).any { it.id == first })
        assertTrue(lib.writes.isEmpty()); assertTrue(records().isEmpty())
        runBlocking { setFolderHidden(app, "Pictures/Urlaub", true) }
        val expected = lib.reviewableMedia().count { it.id != first && it.relativePath?.startsWith("Pictures/Urlaub/") != true }
        pumpUntil { app.visiblePhotos.reviewableMedia().size == expected }
    }

    @Test fun hiddenScreenShowsFoldersAndMediaAndShowsThemAgain() {
        start()
        val first = lib.all[6].id
        runBlocking { setFolderHidden(app, "Pictures/Urlaub", true); app.updateSettings { it.copy(hiddenMedia = listOf(first)) } }
        rule.setContent { TestHost(app) { HiddenScreen(nav) } }
        pumpUntil { count("hidden.folders") == 1 && count("lib.tile") == 1 }
        click("hidden.folder.unhide")
        pumpUntil { app.settings.value.hiddenFolders.isEmpty() }
        click("lib.tile"); pumpUntil { count("lib.sel.unhide") == 1 }
        click("lib.sel.unhide")
        pumpUntil { app.settings.value.hiddenMedia.isEmpty() }
    }

    @Test fun excludedFolderStaysVisibleInLibraryButNotInCleanup() {
        start()
        runBlocking { app.updateSettings { it.copy(excludedFolders = listOf("Pictures/Urlaub/")) } }
        val visible = app.visiblePhotos.reviewableMedia()
        assertEquals(10, visible.size)
        assertEquals(6, app.photos.reviewableMedia().size)
        assertEquals(6, app.cleanupCandidates(visible).size)
    }

    @Test fun favoritesViewGroupsByFolderOrDate() {
        start()
        lib.favorites[lib.all[0].id] = true; lib.favorites[lib.all[4].id] = true; lib.favorites[lib.all[7].id] = true
        rule.setContent { TestHost(app) { LibraryScreen(nav) } }
        pumpUntil { count("lib.tile") > 0 }
        clickText("Favoriten")
        pumpUntil { count("lib.tile") == 3 }
        assertEquals(3, count("lib.header")) // Urlaub, Arbeit, Camera
        click("lib.fav.bydate")
        pumpUntil { !app.settings.value.favoritesByFolder }
        assertEquals(3, count("lib.tile"))
    }

    @Test fun viewerHideRemovesPageAndUndoBringsItBack() {
        start()
        val list = runBlocking { lib.reviewableMedia() }
        rule.setContent { TestHost(app) { ViewerScreen(ViewerRequest("v", list, 0), nav, onClose = {}) } }
        pumpUntil { count("viewer.index") == 1 }
        click("viewer.more"); pumpUntil { count("viewer.hide") == 1 }
        click("viewer.hide")
        pumpUntil { count("viewer.hidden") == 1 && rule.onAllNodes(hasText("1 / ${list.size - 1}", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(list[0].id in app.settings.value.hiddenMedia)
        click("viewer.hidden.undo")
        // Wieder da; der Betrachter bleibt beim gerade gezeigten Medium.
        pumpUntil { app.settings.value.hiddenMedia.isEmpty() && rule.onAllNodes(hasText(" / ${list.size}", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(records().isEmpty())
    }
}
