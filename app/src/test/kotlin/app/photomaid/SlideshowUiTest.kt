package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.library.SlideshowOrder
import app.photomaid.core.library.SlideshowSettings
import app.photomaid.core.model.MediaKind
import app.photomaid.feature.library.SlideshowRequest
import app.photomaid.feature.library.SlideshowScreen
import app.photomaid.feature.library.ViewerRequest
import app.photomaid.feature.library.ViewerScreen
import app.photomaid.feature.library.slideshowOrder
import app.photomaid.media.reviewableMedia
import app.photomaid.playback.FeedPlayer
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

/** G3: Diashow (Reihenfolge, Ablauf, Einstellungen), Kopfhörer nur Play/Pause, Video-Leiste. Nie eine Entscheidung. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+w393dp-h852dp")
class SlideshowUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()

    private fun start() { app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 6, videos = 2).also { l -> lib = l } }) }
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
    private fun has(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun click(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }

    private fun hasKeepScreenOn(v: android.view.View): Boolean =
        v.keepScreenOn || (v is android.view.ViewGroup && (0 until v.childCount).any { hasKeepScreenOn(v.getChildAt(it)) })

    @Test fun orderStartsAtChosenItemAndCanSkipVideos() {
        start()
        val all = lib.reviewableMedia()
        val photos = all.filter { it.kind != MediaKind.VIDEO }
        val s = SlideshowSettings(order = SlideshowOrder.CHRONOLOGICAL, playVideos = false)
        val r = slideshowOrder(SlideshowRequest("x", all, photos[2].id), s, 1)
        assertEquals(photos.size, r.size)
        assertEquals(photos[2].id, r.first().id)
        assertTrue(r.none { it.kind == MediaKind.VIDEO })
        assertEquals(all.size, slideshowOrder(SlideshowRequest("x", all), s.copy(playVideos = true), 1).size)
    }

    @Test fun photosAdvanceByTimerKeepScreenOnAndEnd() {
        start()
        runBlocking { app.updateSettings { it.copy(slideshow = SlideshowSettings(seconds = 3, playVideos = false)) } }
        val photos = lib.reviewableMedia().filter { it.kind != MediaKind.VIDEO }.take(3)
        var closed = false
        rule.setContent { TestHost(app) { SlideshowScreen(SlideshowRequest("s", photos), onClose = { closed = true }) } }
        pumpUntil { has("1 / 3") }
        assertTrue("Bildschirm bleibt an", hasKeepScreenOn(rule.activity.window.decorView))
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(3_100); pumpUntil { has("2 / 3") }
        rule.mainClock.advanceTimeBy(3_100); pumpUntil { has("3 / 3") }
        rule.mainClock.advanceTimeBy(3_100); pumpUntil { count("slideshow.end") == 1 }
        click("slideshow.again"); pumpUntil { has("1 / 3") && count("slideshow.end") == 0 }
        if (count("slideshow.close") == 0) rule.onNodeWithTag("slideshow").performTouchInput { down(center); up() }
        pumpUntil { count("slideshow.close") == 1 }
        click("slideshow.close"); pumpUntil { closed }
        assertTrue(app.ledger.stateFlow.value.records.isEmpty())
    }

    @Test fun pauseStopsTimerAndManualNextWorks() {
        start()
        runBlocking { app.updateSettings { it.copy(slideshow = SlideshowSettings(seconds = 3, playVideos = false)) } }
        val photos = lib.reviewableMedia().filter { it.kind != MediaKind.VIDEO }.take(3)
        rule.setContent { TestHost(app) { SlideshowScreen(SlideshowRequest("s", photos), onClose = {}) } }
        pumpUntil { has("1 / 3") }
        click("slideshow.pause")
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(7_000); pumpUntil { true }
        assertTrue(has("1 / 3"))
        click("slideshow.next"); pumpUntil { has("2 / 3") }
        click("slideshow.prev"); pumpUntil { has("1 / 3") }
    }

    @Test fun settingsSheetIsSaved() {
        start()
        val photos = lib.reviewableMedia()
        rule.setContent { TestHost(app) { SlideshowScreen(SlideshowRequest("s", photos), onClose = {}) } }
        pumpUntil { count("slideshow.settings") == 1 }
        click("slideshow.settings"); pumpUntil { count("slideshow.sheet") == 1 }
        click("slideshow.order.ALBUMS_RANDOM"); pumpUntil { app.settings.value.slideshow.order == SlideshowOrder.ALBUMS_RANDOM }
        pumpUntil { count("slideshow.within") == 1 }
        click("slideshow.sec.8"); pumpUntil { app.settings.value.slideshow.seconds == 8 }
        click("slideshow.loop"); pumpUntil { app.settings.value.slideshow.loop }
        click("slideshow.videos"); pumpUntil { !app.settings.value.slideshow.playVideos }
    }

    @Test fun headsetButtonsOnlyPlayPauseNeverNext() {
        start()
        val exo = ExoPlayer.Builder(rule.activity).build()
        val p = FeedPlayer.ButtonsOnly(exo)
        assertTrue(p.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        for (c in listOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA)) assertFalse("Befehl $c", p.isCommandAvailable(c))
        assertFalse(p.hasNextMediaItem())
        exo.release()
    }

    @Test fun viewerVideoBarChangesSpeedWithoutDecision() {
        start()
        val list = runBlocking { lib.reviewableMedia() }
        val vi = list.indexOfFirst { it.kind == MediaKind.VIDEO }
        rule.setContent { TestHost(app) { ViewerScreen(ViewerRequest("v", list, vi), nav, onClose = {}) } }
        pumpUntil { count("viewer.video") == 1 }
        click("viewer.video.speed"); pumpUntil { app.player.state.value.speed == 1.5f }
        click("viewer.video.speed"); pumpUntil { app.player.state.value.speed == 2f }
        assertEquals(list[vi].id, app.player.state.value.itemID)
        click("viewer.more"); pumpUntil { count("viewer.slideshow") == 1 }
        click("viewer.slideshow"); pumpUntil { nav.slideshows.isNotEmpty() }
        assertEquals(list[vi].id, nav.slideshows.single().startId)
        assertTrue(app.ledger.stateFlow.value.records.isEmpty())
    }
}
