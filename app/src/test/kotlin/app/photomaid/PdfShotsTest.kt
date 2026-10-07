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
import app.photomaid.core.document.Caption
import app.photomaid.core.model.MediaKind
import app.photomaid.core.document.PdfOptions
import app.photomaid.core.document.PerPage
import app.photomaid.feature.document.PdfRequest
import app.photomaid.feature.document.PdfScreen

/** Paket 4: Aufnahmen der PDF-Seite (hell/dunkel, große Schrift, Arabisch) → build/outputs/roborazzi/pdf_*. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfShotsTest {
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
        app.editEngine.io = kotlinx.coroutines.Dispatchers.Main; app.editEngine.parallel = false
        val ids = lib.all.filter { it.kind != MediaKind.VIDEO }.take(5).map { it.id }
        rule.setContent { TestHost(app) { PdfScreen(PdfRequest("p", ids, PdfOptions(perPage = PerPage.FOUR, caption = Caption.NUMBER)), nav) {} } }
        pump(); pump(120)
        captureScreenRoboImage("build/outputs/roborazzi/pdf_${variant}.png")
    }

    @Test @Config(qualifiers = "de-w393dp-h852dp") fun light() = run("light")
    @Test @Config(qualifiers = "de-w360dp-h800dp-night") fun dark() = run("dark", dark = true)
    @Test @Config(qualifiers = "de-w393dp-h852dp", fontScale = 2.0f) fun font200() = run("font200")
    @Test @Config(qualifiers = "ar-w393dp-h852dp") fun arabic() = run("arabic")
}
