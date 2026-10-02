package app.photomaid

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.edit.EditKind
import app.photomaid.core.edit.FilterId
import app.photomaid.core.model.ItemReference
import app.photomaid.core.studio.ExportProfile
import app.photomaid.feature.editor.EditorScreen
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** P5: Video-Editor – Schnitte, Tempo, Ton, Format, Farbe, Profil, Standbild, Ton als M4A; immer neue Datei, nie eine Entscheidung über das Original. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+w393dp-h852dp")
class VideoEditorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val fake get() = app.videoExporter as FakeVideoEditExporter
    private var closed = false
    private lateinit var videoId: String

    private fun start(pro: Boolean = true) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 1, videos = 1).also { l -> lib = l } },
            purchase = if (pro) FakePurchaseGateway(PurchaseState.PURCHASED) else null)
        app.editEngine.io = Dispatchers.Main; app.editEngine.parallel = false
        app.videoTrimmer = FakeVideoTrimmer(rule.activity)
        val v = lib.all.last(); videoId = v.id
        rule.setContent { TestHost(app) { EditorScreen(v.id, RecordingNav(), onClose = { closed = true }) } }
        waitTag("trim.timeline")
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
    private fun waitTag(tag: String) = pumpUntil { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun click(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
    private fun progress(tag: String, v: Float) { rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { it(v) }; pumpUntil { true } }
    private fun save() {
        click("editor.save"); waitTag("editor.sheet.save")
        click("editor.sheet.save")
        pumpUntil { closed }
    }

    @Test fun cutSpeedAudioFormatAndColorReachTheExport() {
        start()
        // Bereich 10–14 s entfernen (Regler der Bedienungshilfe statt Ziehen)
        progress("trim.start", 2_000f)
        click("cut.start"); waitTag("cut.draft")
        progress("cut.draft.start", 10_000f)
        click("cut.confirm"); waitTag("cut.0")
        click("tab.SPEED"); click("speed.2.0")
        click("tab.AUDIO"); click("fade.1000")
        click("tab.FORMAT"); click("vaspect.1.0"); click("vformat.fill"); click("vformat.rotate")
        click("tab.FILTER"); click("filter.WARM")
        click("editor.save"); waitTag("vprofile.SMALL")
        click("vprofile.SMALL")
        assertTrue(rule.onAllNodesWithTag("editor.sheet.pro").fetchSemanticsNodes().isEmpty())
        click("editor.sheet.save")
        pumpUntil { closed }
        val (p, audioOnly) = fake.exports.single()
        assertFalse(audioOnly)
        val c = p.clips.single()
        assertEquals(2_000L, c.trimStartMs)
        assertEquals(1, c.cuts.size); assertEquals(10_000L, c.cuts[0].startMs); assertEquals(12_000L, c.cuts[0].endMs)
        assertEquals(2f, c.speed)
        assertEquals(1_000L, c.fadeInMs); assertEquals(1_000L, c.fadeOutMs)
        assertEquals(1f, p.aspect!!, 1e-4f); assertTrue(p.fill)
        assertEquals(1, c.quarterTurns)
        assertEquals(FilterId.WARM, c.filter)
        assertEquals(ExportProfile.SMALL, p.profile)
        // Neue Datei = Behalten; das Original bleibt ohne Entscheidung.
        val rec = app.edits.records.value.single()
        assertTrue(rec.video)
        assertEquals(Disposition.KEPT, app.ledger.stateFlow.value.records[ItemReference.photo(rec.newId)]?.disposition)
        assertEquals(null, app.ledger.stateFlow.value.records[ItemReference.photo(videoId)])
        assertTrue((app.videoTrimmer as FakeVideoTrimmer).calls.isEmpty()) // nicht nur gekürzt → kein Schnellweg
    }

    @Test fun trimOnlyUsesTheFastLosslessPath() {
        start()
        progress("trim.start", 5_000f)
        save()
        assertEquals(5_000L, (app.videoTrimmer as FakeVideoTrimmer).calls.single().startMs)
        assertTrue(fake.exports.isEmpty())
    }

    @Test fun undoRevertsVideoSettings() {
        start()
        click("tab.SPEED"); click("speed.2.0")
        rule.onNodeWithTag("speed.result").assertExists()
        click("editor.undo")
        click("editor.redo"); click("editor.undo")
        // Nichts geändert → Speichern nicht möglich
        assertFalse(rule.onNodeWithTag("editor.save").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not())
    }

    @Test fun removeAudioAndExportSoundAsM4a() {
        start()
        click("tab.AUDIO"); waitTag("audio.export")
        click("audio.export")
        pumpUntil { lib.audio.isNotEmpty() }
        waitTag("editor.notice")
        assertTrue(fake.exports.single().second) // nur Ton
        assertTrue(lib.audio.single().endsWith(".m4a"))
        click("editor.notice.ok")
        click("audio.remove")
        rule.onAllNodesWithTag("audio.export").fetchSemanticsNodes().isEmpty().let { assertTrue(it) }
    }

    @Test fun stillFrameSavesNewPhotoAsKept() {
        start()
        click("video.frame")
        waitTag("editor.notice")
        val rec = app.edits.records.value.single()
        assertEquals(EditKind.FRAME, rec.kind)
        assertFalse(rec.video)
        assertEquals("image/jpeg", lib.saved.single().mime)
        assertNotNull(android.graphics.BitmapFactory.decodeFile(java.io.File(Uri.parse(rec.newId).path!!).absolutePath))
        assertEquals(Disposition.KEPT, app.ledger.stateFlow.value.records[ItemReference.photo(rec.newId)]?.disposition)
    }

    @Test fun textGetsTimeWindowAndReachesExport() {
        start()
        click("tab.MARKUP"); waitTag("markup.canvas")
        click("mk.sticker"); waitTag("sticker.HEART"); click("sticker.HEART")
        waitTag("video.layertime")
        save()
        val p = fake.exports.single().first
        assertEquals(1, p.layers.size)
        assertEquals(0L, p.layers[0].startMs)
    }

    @Test fun freeUserCanTryButNotSave() {
        start(pro = false)
        waitTag("editor.prohint")
        click("tab.SPEED"); click("speed.0.5")
        click("editor.save"); waitTag("editor.sheet.pro")
        assertTrue(fake.exports.isEmpty())
    }
}
