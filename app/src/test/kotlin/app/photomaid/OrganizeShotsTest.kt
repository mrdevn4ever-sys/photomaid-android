package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.design.AppearanceChoice
import app.photomaid.feature.library.LibraryMode
import app.photomaid.feature.library.LibraryScreen
import app.photomaid.feature.library.ViewerRequest
import app.photomaid.feature.library.ViewerScreen
import app.photomaid.feature.organize.OrganizeScreen
import app.photomaid.feature.organize.OrganizeTarget
import app.photomaid.media.reviewableMedia
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Paket 1 Organisieren: Aufnahmen der neuen Ansichten (hell/dunkel, große Schrift) → build/outputs/roborazzi/org_*. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrganizeShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()
    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val gen = mutableIntStateOf(0)
    private var shown = false

    private fun pump(n: Int = 60) {
        rule.mainClock.autoAdvance = false
        repeat(n) { shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(4) }
        rule.mainClock.autoAdvance = true
    }
    private fun tap(tag: String, long: Boolean = false) {
        val n = rule.onAllNodesWithTag(tag, useUnmergedTree = true)
        if (n.fetchSemanticsNodes().isNotEmpty()) { n[0].performSemanticsAction(if (long) SemanticsActions.OnLongClick else SemanticsActions.OnClick); pump(30) }
    }

    private fun run(variant: String, dark: Boolean = false) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l } }) { a ->
            a.updateSettings { it.copy(appearance = if (dark) AppearanceChoice.DARK else AppearanceChoice.LIGHT) } }
        val media = runBlocking { lib.reviewableMedia() }
        val ferien = runBlocking {
            val id = app.organize.createAlbum("Ferien 2026", media.take(5))!!
            app.organize.createAlbum("Familie", media.drop(2).take(3)); app.organize.createAlbum("Zum Drucken", media.takeLast(2))
            app.organize.editTags(media.take(2), add = listOf("Rechnung")); app.organize.editTags(media.drop(1).take(3), add = listOf("Wohnung", "Verkaufen"))
            app.organize.setNote(media[0], "Ersatzteil für die Spülmaschine, Modell XY")
            app.organize.addToBasket(media.take(4)); id
        }
        data class Shot(val name: String, val screen: @Composable () -> Unit, val after: () -> Unit = {})
        val shots = listOf(
            Shot("albums", { LibraryScreen(nav, initialMode = LibraryMode.ALBUMS) }),
            Shot("album", { OrganizeScreen(OrganizeTarget.AlbumOf(ferien), nav) {} }),
            Shot("album_selection", { OrganizeScreen(OrganizeTarget.AlbumOf(ferien), nav) {} }) { tap("lib.tile", long = true) },
            Shot("selection_menu", { LibraryScreen(nav) }) { tap("lib.tile", long = true); tap("lib.sel.more") },
            Shot("album_picker", { LibraryScreen(nav) }) { tap("lib.tile", long = true); tap("lib.sel.more"); tap("lib.sel.album") },
            Shot("tag_sheet", { LibraryScreen(nav) }) { tap("lib.tile", long = true); tap("lib.sel.more"); tap("lib.sel.tags") },
            Shot("basket", { OrganizeScreen(OrganizeTarget.Basket, nav) {} }),
            Shot("tag", { OrganizeScreen(OrganizeTarget.TagOf("Wohnung"), nav) {} }),
            Shot("viewer_info", { ViewerScreen(ViewerRequest("v", media, 0), nav, onClose = {}) }) { tap("viewer.more"); tap("viewer.info") },
        )
        for (s in shots) {
            content.value = s.screen; gen.intValue++
            if (!shown) { shown = true; rule.setContent { TestHost(app) { key(gen.intValue) { content.value?.invoke() } } } }
            pump(); s.after(); pump(40)
            captureScreenRoboImage("build/outputs/roborazzi/org_${variant}_${s.name}.png")
        }
    }

    @Test @Config(qualifiers = "de-w393dp-h852dp") fun light() = run("light")
    @Test @Config(qualifiers = "de-w360dp-h800dp-night") fun dark() = run("dark", dark = true)
    @Test @Config(qualifiers = "de-w393dp-h852dp", fontScale = 2.0f) fun font200() = run("font200")
    @Test @Config(qualifiers = "ar-w393dp-h852dp") fun arabic() = run("arabic")
}
