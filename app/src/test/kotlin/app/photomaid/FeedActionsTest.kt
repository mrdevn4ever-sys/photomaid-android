package app.photomaid

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.model.ItemReference
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.media.MediaCategory
import app.photomaid.media.MediaWriteResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * Aktionsleiste im Feed: Favorit (Systemfavorit), Favoritenschutz, Teilen, In Ordner verschieben.
 * Keine dieser Aktionen ist eine Review-Entscheidung, keine blättert weiter.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedActionsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary

    private fun start(favoriteFirst: Boolean = false, protect: Boolean = true, videos: Int = 0, category: MediaCategory = MediaCategory.PHOTOS) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 5, videos = videos).also { l -> lib = l } }) { a ->
            if (!protect) a.updateSettings { it.copy(protectFavorites = false) }
        }
        if (favoriteFirst) lib.favorites[lib.assets(category).first().id] = true
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.Category(category)), RecordingNav(), onClose = {}, openBasket = {}) } }
        awaitReady("1 von ${lib.assets(category).size}")
    }

    private fun awaitReady(position: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithTag("feed.media.ready").fetchSemanticsNodes().isNotEmpty() && rule.onAllNodes(hasText(position)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitText(t: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty() }
    private fun id(i: Int) = lib.all[i].id
    private fun rec(i: Int) = app.ledger.stateFlow.value.records[ItemReference.photo(id(i))] ?: ItemRecord(ItemReference.photo(id(i)))
    private fun events() = app.ledger.stateFlow.value.events

    // MARK: Gesture-Isolation

    /** Tipps auf ♡, Teilen, Ordner, ⋯ lösen nie Swipe, Entscheidung oder Weiterblättern aus. */
    @Test fun actionTapsNeverSwipeOrDecide() {
        start()
        lib.writeResult = MediaWriteResult.Cancelled // Systemdialog abgelehnt
        rule.onNodeWithTag("action.favorite").performClick()
        rule.onNodeWithTag("action.share").performClick()
        rule.onNodeWithTag("action.folder").performClick()
        waitTag("folder.sheet")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.onNodeWithTag("action.more").performClick()
        rule.onNodeWithText("Infos").assertExists()
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        rule.onNodeWithText("1 von 5").assertExists()
        assertEquals(0, events().size)
        assertEquals(Disposition.UNDECIDED, rec(0).disposition)
        assertTrue(lib.removed.isEmpty())
    }

    // MARK: Favoriten

    @Test fun setAndRemoveSystemFavorite() {
        start()
        rule.onNode(hasContentDescription("Als Favorit markieren")).performClick()
        rule.waitUntil(10_000) { lib.favorites[id(0)] == true && rule.onAllNodes(hasContentDescription("Aus Favoriten entfernen")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasContentDescription("Aus Favoriten entfernen")).performClick()
        rule.waitUntil(10_000) { lib.favorites[id(0)] == false && rule.onAllNodes(hasContentDescription("Als Favorit markieren")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("favorite:true", "favorite:false"), lib.writes)
        assertEquals(0, events().size) // keine Review-Entscheidung
        rule.onNodeWithText("1 von 5").assertExists()
    }

    @Test fun favoriteCancelledKeepsRealState() {
        start()
        lib.writeResult = MediaWriteResult.Cancelled
        rule.onNode(hasContentDescription("Als Favorit markieren")).performClick()
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Als Favorit markieren")).assertExists()
        assertFalse(lib.favorites[id(0)] == true)
        assertTrue(rule.onAllNodes(hasText("Die Favoritenmarkierung konnte nicht geändert werden. Sie bleibt unverändert.")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun favoriteFailureShowsHonestError() {
        start()
        lib.writeResult = MediaWriteResult.Failed("test")
        rule.onNode(hasContentDescription("Als Favorit markieren")).performClick()
        waitText("Die Favoritenmarkierung konnte nicht geändert werden. Sie bleibt unverändert.")
        rule.onNode(hasContentDescription("Als Favorit markieren")).assertExists()
    }

    /** Favorit extern (Galerie-App) geändert → nach MediaStore-Änderung zeigt der Feed den echten Zustand. */
    @Test fun externalFavoriteChangeIsReflected() {
        start()
        lib.favorites[id(0)] = true
        lib.bumpChangeToken()
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Aus Favoriten entfernen")).fetchSemanticsNodes().isNotEmpty() }
        lib.favorites[id(0)] = false
        lib.bumpChangeToken()
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Als Favorit markieren")).fetchSemanticsNodes().isNotEmpty() }
    }

    // MARK: Favoritenschutz

    @Test fun protectedFavoriteSwipeLeftAsksAndDoesNotTrash() {
        start(favoriteFirst = true)
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeLeft() }
        waitText("Dieses Foto ist als Favorit geschützt.")
        assertEquals(Disposition.UNDECIDED, rec(0).disposition)
        rule.onNodeWithText("Abbrechen").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("1 von 5").assertExists()
        assertEquals(0, events().size)
        assertTrue(lib.favorites[id(0)] == true)
    }

    @Test fun unfavoriteThenTrashWorksNormally() {
        start(favoriteFirst = true)
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeLeft() }
        waitText("Dieses Foto ist als Favorit geschützt.")
        rule.onNodeWithText("Aus Favoriten entfernen").performClick()
        rule.waitUntil(5_000) { lib.favorites[id(0)] == false }
        waitText("Nicht mehr als Favorit markiert. Wische nach links für den Papierkorb.")
        // Kein automatisches Weiterlegen: Medium bleibt, keine Entscheidung.
        rule.onNodeWithText("1 von 5").assertExists()
        assertEquals(Disposition.UNDECIDED, rec(0).disposition)
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeLeft() }
        awaitReady("2 von 5")
        assertEquals(Disposition.MARKED_FOR_CLEANUP, rec(0).disposition)
    }

    @Test fun protectionOffTrashesFavoriteLikeAnyOther() {
        start(favoriteFirst = true, protect = false)
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeLeft() }
        awaitReady("2 von 5")
        assertEquals(Disposition.MARKED_FOR_CLEANUP, rec(0).disposition)
        assertTrue(lib.favorites[id(0)] == true) // Favorit bleibt unverändert
    }

    @Test fun protectedVideoFavoriteUsesVideoWording() {
        start(favoriteFirst = true, videos = 2, category = MediaCategory.VIDEOS)
        rule.onNodeWithTag("feed.stage").performTouchInput { swipeLeft() }
        waitText("Dieses Video ist als Favorit geschützt.")
    }

    /** Default AN, auch für gespeicherte Einstellungen ohne das neue Feld (Update bestehender Nutzer). */
    @Test fun protectionDefaultsOnAndPersists() {
        assertTrue(AppSettingsData().protectFavorites)
        assertTrue(Json { ignoreUnknownKeys = true }.decodeFromString<AppSettingsData>("""{"haptics":false}""").protectFavorites)
        start()
        val stored = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            app.updateSettings { it.copy(protectFavorites = false) }
            app.store.value<AppSettingsData>("app.settings")
        }
        assertNotNull(stored)
        assertFalse(stored!!.protectFavorites)
    }

    // MARK: Teilen

    @Test fun shareUsesNativeChooserWithReadGrantAndKeepsPosition() {
        start()
        rule.onNodeWithTag("action.share").performClick()
        val chooser = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        @Suppress("DEPRECATION") val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(Uri.parse(id(0)), stream)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(Uri.parse(id(0)), send.clipData!!.getItemAt(0).uri)
        rule.waitForIdle()
        rule.onNodeWithText("1 von 5").assertExists()
        assertEquals(0, events().size)
        assertEquals(Disposition.UNDECIDED, rec(0).disposition)
    }

    @Test fun shareVideoUsesVideoMime() {
        start(videos = 1, category = MediaCategory.VIDEOS)
        rule.onNodeWithTag("action.share").performClick()
        @Suppress("DEPRECATION") val send = shadowOf(rule.activity).nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("video/mp4", send.type)
    }

    // MARK: In Ordner verschieben

    @Test fun moveToExistingFolderReportsOnlyVerifiedSuccess() {
        start()
        val before = lib.all.size
        rule.onNodeWithTag("action.folder").performClick()
        waitText("Urlaub 2026")
        rule.onNodeWithText("Movies/Clips").assertDoesNotExist() // Bilder dürfen nicht nach Movies/
        rule.onNodeWithText("Urlaub 2026").performClick()
        rule.waitUntil(5_000) { lib.paths[id(0)] == "Pictures/Urlaub 2026/" }
        waitText("Nach „Urlaub 2026“ verschoben")
        assertEquals(before, lib.all.size) // keine Kopie
        assertTrue(lib.removed.isEmpty()) // keine Löschung
        rule.onNodeWithText("1 von 5").assertExists()
        assertEquals(0, events().size)
    }

    @Test fun moveCancelledOrFailedChangesNothing() {
        start()
        lib.writeResult = MediaWriteResult.Cancelled
        rule.onNodeWithTag("action.folder").performClick()
        waitText("Urlaub 2026")
        rule.onNodeWithText("Urlaub 2026").performClick()
        rule.waitForIdle()
        assertEquals("DCIM/Camera/", lib.all[0].relativePath)
        assertTrue(rule.onAllNodesWithTag("feed.notice").fetchSemanticsNodes().isEmpty())
        lib.writeResult = MediaWriteResult.Failed("test")
        // Erstes Sheet ist vollständig geschlossen, bevor das zweite geöffnet wird.
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("folder.sheet").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("action.folder").performClick()
        waitTag("folder.sheet")
        waitText("Urlaub 2026")
        rule.onNodeWithText("Urlaub 2026").performClick()
        waitText("Die Datei konnte nicht verschoben werden. Sie bleibt im bisherigen Ordner.")
        assertEquals("DCIM/Camera/", lib.all[0].relativePath)
    }

    @Test fun newFolderValidatesAndMoves() {
        start()
        rule.onNodeWithTag("action.folder").performClick()
        waitTag("folder.new")
        rule.onNodeWithTag("folder.new").performClick()
        rule.onNodeWithTag("folder.name").performTextInput("a/b")
        rule.onNodeWithText("Anlegen und verschieben").performClick()
        waitText("Gib einen kurzen Ordnernamen ohne Zeichen wie / ein.")
        assertTrue(lib.writes.isEmpty())
        rule.onNodeWithTag("folder.name").performTextClearance()
        rule.onNodeWithTag("folder.name").performTextInput("Hunde")
        rule.onNodeWithText("Anlegen und verschieben").performClick()
        rule.waitUntil(5_000) { lib.paths[id(0)] == "Pictures/Hunde/" }
    }

    /** Medium extern gelöscht, während es angezeigt wird: kein Absturz, keine Entscheidung, klarer Ausweg. */
    @Test fun externallyDeletedMediumShowsProblemWithoutDecision() {
        start()
        lib.removed += id(0)
        lib.bumpChangeToken()
        waitTag("feed.media.problem")
        rule.onNodeWithText("Ohne Auswahl weiter").performClick()
        awaitReady("2 von 5")
        assertEquals(0, events().size)
    }
}
