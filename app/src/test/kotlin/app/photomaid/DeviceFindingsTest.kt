package app.photomaid

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.feature.today.TodayScreen
import app.photomaid.media.PhotoAccess
import app.photomaid.media.VideoPosterFetcher
import app.photomaid.navigation.RootContent
import coil3.toUri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Regressionen aus dem Gerätetest (Realme RMX3933, Android 14, 2026-09-29). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeviceFindingsTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun waitText(t: String, ms: Long = 10_000) = rule.waitUntil(ms) { rule.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty() }

    /** Befund 1: Feed schließen darf nie zu einem leeren Bildschirm führen (doppeltes Zurück). */
    @Test fun closingFeedReturnsToTodayRepeatedly() {
        val app = makeTestApp(ctx)
        rule.setContent { TestHost(app) { RootContent(app) } }
        repeat(3) {
            waitText("Jetzt aufräumen")
            rule.onNodeWithText("Jetzt aufräumen").performClick()
            waitText("1 von 6")
            rule.onNode(hasContentDescription("Schließen")).performClick()
            rule.waitForIdle()
            waitText("Jetzt aufräumen")
            rule.onNodeWithText("Jetzt aufräumen").assertExists()
        }
    }

    /** Befund 3: eingeschränkter Zugriff wird klar benannt statt nur „Fotos 0“. */
    @Test fun limitedAccessShowsHint() {
        val app = makeTestApp(ctx, media = { FakeMediaLibrary(it, access = PhotoAccess.LIMITED) })
        rule.setContent { TestHost(app) { TodayScreen(RecordingNav()) } }
        waitText("Eingeschränkter Zugriff")
        rule.onNodeWithText("Zugriff ändern").assertExists()
    }

    /** Befund 2: Video-Standbilder aus MediaStore laufen über den System-Thumbnailer. */
    @Test fun videoPosterFetcherMatchesOnlyMediaStoreVideos() {
        assertTrue(VideoPosterFetcher.isMediaStoreVideo("content://media/external/video/media/42".toUri()))
        assertTrue(VideoPosterFetcher.isMediaStoreVideo("content://media/external_primary/video/media/7".toUri()))
        assertFalse(VideoPosterFetcher.isMediaStoreVideo("content://media/external/images/media/42".toUri()))
        assertFalse(VideoPosterFetcher.isMediaStoreVideo("file:///sdcard/Movies/a.mp4".toUri()))
        assertFalse(VideoPosterFetcher.isMediaStoreVideo("content://com.android.externalstorage.documents/document/video%2Fa.mp4".toUri()))
    }
}
