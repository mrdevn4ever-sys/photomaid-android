package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.archive.BackupService
import app.photomaid.archive.FileArchiveTarget
import app.photomaid.core.archive.TargetKind
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.model.KnownSize
import app.photomaid.design.AppearanceChoice
import app.photomaid.design.PM
import app.photomaid.feature.archive.ArchiveRequest
import app.photomaid.feature.archive.ArchiveScreen
import app.photomaid.feature.archive.BackupScreen
import app.photomaid.feature.archive.RestorePreview
import app.photomaid.media.reviewableMedia
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Paket 5: Aufnahmen „Auslagern“ (vorher/Ergebnis) und „Sichern und wiederherstellen“ → build/outputs/roborazzi/archive_*. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArchiveShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val nav = RecordingNav()

    private fun pump(n: Int = 60) {
        rule.mainClock.autoAdvance = false
        repeat(n) { shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(4) }
        rule.mainClock.autoAdvance = true
    }
    private fun until(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!cond()) { pump(5); check(System.currentTimeMillis() < end) }
    }

    private fun run(variant: String, dark: Boolean = false, kind: TargetKind = TargetKind.REMOVABLE) {
        lateinit var lib: FakeMediaLibrary
        val app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 5, videos = 1).also { l -> lib = l
            l.all.forEach { a -> l.sizes[a.id] = KnownSize.Known(File(a.id.removePrefix("file://")).length()) } } }) { a ->
            a.updateSettings { it.copy(appearance = if (dark) AppearanceChoice.DARK else AppearanceChoice.LIGHT) } }
        app.archive.testTarget = FileArchiveTarget(File(rule.activity.cacheDir, "sd-${System.nanoTime()}"), kind, "SD-Karte › Archiv", 31_000_000_000)
        val ids = lib.reviewableMedia().map { it.id }
        rule.setContent { TestHost(app) { ArchiveScreen(ArchiveRequest("r", ids), nav) {} } }
        pump(); pump(60)
        captureScreenRoboImage("build/outputs/roborazzi/archive_${variant}_before.png")
        // Bei großer Schrift liegt die Taste weiter unten in der Liste: erst hinscrollen, Aufnahme oben ist schon gemacht.
        runCatching { rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasTestTag("archive.start")) }
        pump(10)
        rule.onNodeWithTag("archive.start").performSemanticsAction(SemanticsActions.OnClick)
        until { rule.onAllNodesWithTag("archive.summary").fetchSemanticsNodes().isNotEmpty() }
        runCatching { rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToIndex(0) }
        pump(30)
        captureScreenRoboImage("build/outputs/roborazzi/archive_${variant}_result.png")
    }

    private fun backup(variant: String, dark: Boolean = false) {
        lateinit var lib: FakeMediaLibrary
        val app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 4, videos = 1).also { l -> lib = l } }) { a ->
            a.updateSettings { it.copy(appearance = if (dark) AppearanceChoice.DARK else AppearanceChoice.LIGHT) } }
        val a = lib.reviewableMedia()
        runBlocking(Dispatchers.IO) {
            app.decide(a[0].reference, DecisionAction.Keep); app.decide(a[1].reference, DecisionAction.MarkForCleanup)
            app.organize.createAlbum("Ferien", listOf(a[0], a[2])); app.organize.editTags(listOf(a[3]), add = listOf("Rechnung"))
        }
        val b = runBlocking { BackupService.create(app) }
        // Vorschau wie auf einem anderen Gerät: zwei Medien fehlen dort.
        lib.removed += listOf(a[2].id, a[3].id)
        runBlocking(Dispatchers.IO) { app.undo(); app.undo() }
        val plan = runBlocking { BackupService.plan(app, b) }
        rule.setContent {
            TestHost(app) {
                Column {
                    Box(Modifier.weight(1f)) { BackupScreen(nav) }
                    Box(Modifier.weight(1f).background(PM.colors.background).padding(16.dp)) {
                        RestorePreview(app, b, plan, applying = null, restored = null) {}
                    }
                }
            }
        }
        pump(); pump(60)
        captureScreenRoboImage("build/outputs/roborazzi/archive_backup_${variant}.png")
    }

    @Test @Config(qualifiers = "de-w393dp-h1400dp") fun light() = run("light")
    @Test @Config(qualifiers = "de-w360dp-h1400dp-night") fun dark() = run("dark", dark = true, kind = TargetKind.SAME_DEVICE)
    @Test @Config(qualifiers = "de-w393dp-h1600dp", fontScale = 2.0f) fun font200() = run("font200", kind = TargetKind.PROVIDER)
    @Test @Config(qualifiers = "ar-w393dp-h1400dp") fun arabic() = run("arabic")
    @Test @Config(qualifiers = "de-w393dp-h1600dp") fun backupLight() = backup("light")
    @Test @Config(qualifiers = "en-w393dp-h1600dp-night") fun backupDarkEnglish() = backup("dark_en", dark = true)
}
