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
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.photomaid.core.share.SharedItem
import app.photomaid.feature.share.ShareScreen
import app.photomaid.system.EntryRequest

/** Teilen-Ziel: Aufnahmen der Übernahme-Seite (hell/dunkel, große Schrift, Arabisch) → build/outputs/roborazzi/share_*. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val nav = RecordingNav()

    private fun pump(n: Int = 60) {
        rule.mainClock.autoAdvance = false
        repeat(n) { shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(4) }
        rule.mainClock.autoAdvance = true
    }

    private fun run(variant: String, dark: Boolean = false) {
        lateinit var lib: FakeMediaLibrary
        val app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l } }) { a ->
            a.updateSettings { it.copy(appearance = if (dark) AppearanceChoice.DARK else AppearanceChoice.LIGHT) } }
        val items = lib.all.take(3).mapIndexed { i, a -> SharedItem("content://other.app/$i", a.mimeType, a.displayName, a.size.bytesOrNull) } +
            SharedItem("content://other.app/x", "image/jpeg", "aus-dem-chat.jpg", 4711)
        rule.setContent { TestHost(app) { ShareScreen(EntryRequest.Share(items, null), nav) {} } }
        pump(); pump(40)
        captureScreenRoboImage("build/outputs/roborazzi/share_${variant}.png")
    }

    @Test @Config(qualifiers = "de-w393dp-h852dp") fun light() = run("light")
    @Test @Config(qualifiers = "de-w360dp-h800dp-night") fun dark() = run("dark", dark = true)
    @Test @Config(qualifiers = "de-w393dp-h852dp", fontScale = 2.0f) fun font200() = run("font200")
    @Test @Config(qualifiers = "ar-w393dp-h852dp") fun arabic() = run("arabic")
}
