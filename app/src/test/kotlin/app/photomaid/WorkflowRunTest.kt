package app.photomaid

import app.photomaid.core.studio.VSize
import app.photomaid.media.VideoInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.batch.Workflow
import app.photomaid.core.batch.WorkflowFinish
import app.photomaid.core.batch.WorkflowFormat
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.model.MediaKind
import app.photomaid.feature.workflow.WorkflowBudget
import app.photomaid.feature.workflow.WorkflowItemResult
import app.photomaid.feature.workflow.WorkflowRequest
import app.photomaid.feature.workflow.WorkflowRunRequest
import app.photomaid.feature.workflow.WorkflowRunScreen
import app.photomaid.feature.workflow.WorkflowRunner
import app.photomaid.feature.workflow.WorkflowsScreen
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
 * Paket 2 Arbeitsabläufe: neue Dateien mit Namensschema/Ordner/Format, Zielgröße gemessen (nie zu groß gespeichert),
 * Videos nur, wenn vorgesehen, „Nur teilen“ speichert nichts. Quellen bleiben unverändert und ohne Entscheidung.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w393dp-h852dp")
class WorkflowRunTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()
    private lateinit var video: FakeVideoEditExporter

    private fun start(pro: Boolean = true, count: Int = 4) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = count, videos = 1).also { l -> lib = l
            l.all.forEach { a -> l.dims[a.id] = 300 to 400 } } }, purchase = if (pro) FakePurchaseGateway(PurchaseState.PURCHASED) else null)
        video = FakeVideoEditExporter(rule.activity).also { app.videoExporter = it }
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
    private fun count(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun click(tag: String) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
    private fun media() = runBlocking { lib.reviewableMedia() }
    /** Nur die ursprünglichen Testmedien (frühere Läufe legen neue Dateien in die Fake-Mediathek). */
    private fun originals() = media().filter { !it.id.contains("qa-edits") }
    private fun runNow(w: Workflow, budget: WorkflowBudget? = null) = runBlocking { WorkflowRunner.run(app, w, originals(), budget) {} }

    /** Quellen: keine Entscheidung, nichts gelöscht/verschoben/im Papierkorb. Neue Dateien dürfen „Behalten“ sein. */
    private fun assertSourcesUntouched() {
        val sourceIds = media().filter { !it.id.contains("qa-edits") }.map { it.id }.toSet()
        val decided = app.ledger.stateFlow.value.records.filter { (k, r) -> k.localIdentifier in sourceIds && r.disposition != Disposition.UNDECIDED }
        assertTrue("Quellen ohne Entscheidung: $decided", decided.isEmpty())
        assertTrue(lib.trashed.isEmpty()); assertTrue(lib.deleted.isEmpty()); assertTrue(lib.removed.isEmpty())
    }

    @Test fun photosBecomeNewNumberedFilesInChosenFolderAndVideosAreSkipped() {
        start()
        val w = Workflow("w", "Kleinanzeige", WorkflowFormat.JPEG, longEdge = 1080, removeLocation = true, nameBase = "Anzeige",
            folder = "Pictures/PhotoMaid/", finish = WorkflowFinish.SAVE)
        val r = runNow(w)
        val photos = r.items.filter { it.asset.kind != MediaKind.VIDEO }
        assertTrue(photos.all { it.status == WorkflowItemResult.Status.DONE })
        assertEquals(listOf("Anzeige_001.jpg", "Anzeige_002.jpg", "Anzeige_003.jpg", "Anzeige_004.jpg").take(photos.size),
            lib.saved.map { it.displayName })
        assertTrue(lib.saved.all { it.relativePath == "Pictures/PhotoMaid/" && it.mime == "image/jpeg" && !it.replace })
        val v = r.items.single { it.asset.kind == MediaKind.VIDEO }
        assertEquals(WorkflowItemResult.Status.SKIPPED, v.status); assertEquals(WorkflowItemResult.Reason.VIDEO_NOT_IN_WORKFLOW, v.reason)
        assertTrue(r.shared.isEmpty())
        assertSourcesUntouched()
    }

    @Test fun targetSizeIsMeasuredAndNeverSavedTooLarge() {
        start()
        val target = 12_000L
        val r = runNow(Workflow("t", "Klein", WorkflowFormat.JPEG, photoMaxBytes = target, minLongEdge = 640, minQuality = 30, quality = 90))
        val photos = r.items.filter { it.asset.kind != MediaKind.VIDEO }
        assertTrue(photos.isNotEmpty())
        for (p in photos) when (p.status) {
            WorkflowItemResult.Status.DONE -> assertTrue("gemessen ${p.afterBytes} ≤ $target", p.afterBytes!! <= target)
            WorkflowItemResult.Status.TARGET_NOT_MET -> assertTrue(p.afterBytes!! > target)
            else -> error("unerwartet: $p")
        }
        assertEquals(photos.count { it.status == WorkflowItemResult.Status.DONE }, lib.saved.size)
        // Unerreichbares Ziel: KEINE Datei, Grund mit kleinster gemessener Größe.
        lib.saved.clear()
        val impossible = runNow(Workflow("i", "Winzig", WorkflowFormat.JPEG, photoMaxBytes = 1, minLongEdge = 300, minQuality = 30))
        assertTrue(impossible.items.filter { it.asset.kind != MediaKind.VIDEO }.all { it.status == WorkflowItemResult.Status.TARGET_NOT_MET && it.afterBytes != null })
        assertTrue(lib.saved.isEmpty())
        assertSourcesUntouched()
    }

    @Test fun videoTargetUsesPlannedBitrateAndMeasures() {
        start()
        val w = Workflow("v", "Video", videoMaxBytes = 50_000_000, finish = WorkflowFinish.SAVE)
        val r = runNow(w)
        val v = r.items.single { it.asset.kind == MediaKind.VIDEO }
        assertEquals(WorkflowItemResult.Status.DONE, v.status)
        val project = video.exports.single().first
        assertTrue("Bitrate gesetzt", (project.targetBitrate ?: 0) > 0)
        assertTrue(lib.saved.any { it.video && it.displayName.endsWith(".mp4") })
        // Zu lang für das Ziel: nichts exportiert, nichts gespeichert.
        video.exports.clear(); lib.saved.clear()
        video.info = video.info.copy(durationMs = 3_600_000)
        val long = runNow(w.copy(videoMaxBytes = 10_000_000)).items.single { it.asset.kind == MediaKind.VIDEO }
        assertEquals(WorkflowItemResult.Reason.VIDEO_TOO_LONG_FOR_TARGET, long.reason)
        assertTrue(video.exports.isEmpty()); assertTrue(lib.saved.none { it.video })
    }

    /** Exporter, dessen Datei so groß wird, wie die Bitrate es vorgibt – mit 10 % Übergröße (realistisch: Encoder überschießen). */
    private class SizedExporter(private val ctx: android.content.Context, private val info: app.photomaid.media.VideoInfo, private val overshoot: Double,
                                private val floorBytes: Long = 0) :
        app.photomaid.media.VideoEditExporter {
        val bitrates = mutableListOf<Int>()
        override fun info(uri: android.net.Uri) = info
        override suspend fun export(uri: android.net.Uri, project: app.photomaid.core.studio.VideoProject, info: app.photomaid.media.VideoInfo, audioOnly: Boolean,
                                    onProgress: (Float) -> Unit): java.io.File {
            val br = project.targetBitrate ?: 8_000_000; bitrates += br
            val bytes = maxOf(floorBytes, ((br + 128_000) / 8.0 * info.durationMs / 1000 * overshoot).toLong())
            return java.io.File(ctx.cacheDir, "sized_${System.nanoTime()}.mp4").apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(bytes) } }
        }
        override suspend fun frame(uri: android.net.Uri, sourceMs: Long, maxEdge: Int?): android.graphics.Bitmap? = null
    }

    @Test fun videoMeasuredTooLargeIsReencodedOnceAndNeverSavedTooLarge() {
        start()
        val info = VideoInfo(VSize(1920, 1080), 30f, hdr = false, hasAudio = true, durationMs = 20_000)
        // 1. Durchgang 10 % zu groß → gemessen, Bitrate gesenkt, 2. Durchgang passt.
        val over = SizedExporter(rule.activity, info, overshoot = 1.10).also { app.videoExporter = it }
        val target = 20_000_000L
        val r = runNow(Workflow("v", "Video", videoMaxBytes = target)).items.single { it.asset.kind == MediaKind.VIDEO }
        assertEquals(WorkflowItemResult.Status.DONE, r.status)
        assertEquals(2, over.bitrates.size); assertTrue(over.bitrates[1] < over.bitrates[0])
        assertTrue(r.afterBytes!! <= target)
        // Encoder kommt nie unter 25 MB (z. B. Mindestgröße bei der Auflösung): auch der 2. Durchgang ist zu groß → keine Datei, ehrlicher Grund.
        lib.saved.clear()
        SizedExporter(rule.activity, info, overshoot = 1.0, floorBytes = 25_000_000).also { app.videoExporter = it }
        val bad = runNow(Workflow("v2", "Video", videoMaxBytes = target)).items.single { it.asset.kind == MediaKind.VIDEO }
        assertEquals(WorkflowItemResult.Status.TARGET_NOT_MET, bad.status)
        assertEquals(WorkflowItemResult.Reason.VIDEO_TARGET_UNREACHABLE, bad.reason)
        assertTrue(bad.afterBytes!! > target)
        assertTrue(lib.saved.none { it.video })
    }

    @Test fun shareOnlySavesNothingAndBudgetSplitsAcrossSelection() {
        start()
        val r = runNow(Workflow("s", "Teilen", WorkflowFormat.JPEG, longEdge = 1080, finish = WorkflowFinish.SHARE))
        assertTrue(lib.saved.isEmpty())
        assertEquals(r.done, r.shared.size)
        assertTrue(r.done > 0)
        // Gemeinsames Budget: Summe der gespeicherten Ergebnisse bleibt darunter.
        val budget = 60_000L
        val b = runNow(Workflow("b", "Budget", WorkflowFormat.JPEG, photoMaxBytes = 5_000_000, minLongEdge = 300, minQuality = 30), WorkflowBudget(budget))
        assertTrue(b.items.filter { it.status == WorkflowItemResult.Status.DONE }.sumOf { it.afterBytes!! } <= budget)
        assertSourcesUntouched()
    }

    @Test fun templateEditAndRunFromSelectionShowsStepsAndResult() {
        start()
        val ids = media().map { it.id }
        rule.setContent { TestHost(app) { WorkflowsScreen(WorkflowRequest("r", ids), nav) {} } }
        pumpUntil { count("wf.tpl.CLASSIFIEDS") == 1 }
        click("wf.tpl.CLASSIFIEDS")
        pumpUntil { app.workflows.items.value.size == 1 }
        val w = app.workflows.items.value.single()
        assertEquals("Für Kleinanzeigen", w.name); assertEquals("Kleinanzeige", w.nameBase); assertEquals(WorkflowFinish.SHARE, w.finish)
        click("wf.item")
        // Aus einer Auswahl: Tippen führt den Ablauf direkt für genau diese Medien aus.
        val run = nav.workflowRuns.single()
        assertEquals(w.id, run.workflowId); assertEquals(ids, run.assetIds)
        // Bearbeiten öffnet den Editor.
        click("wf.edit")
        assertEquals("push:workflow/edit/${w.id}", nav.events.last())
    }

    @Test fun runScreenPreviewsStepsThenShowsMeasuredResult() {
        start()
        val w = Workflow("w2", "Familie", WorkflowFormat.JPEG, longEdge = 1080, finish = WorkflowFinish.SAVE, folder = "Pictures/PhotoMaid/")
        runBlocking { app.workflows.upsert(w) }
        rule.setContent { TestHost(app) { WorkflowRunScreen(WorkflowRunRequest("x", w.id, media().map { it.id }), nav) {} } }
        pumpUntil { count("wf.steps") == 1 && count("wf.counts") == 1 }
        assertEquals(1, count("wf.step.RESIZE")); assertEquals(1, count("wf.step.SAVE")); assertEquals(1, count("wf.step.FOLDER"))
        click("wf.start")
        pumpUntil(60_000) { count("wf.summary") == 1 }
        assertTrue(count("wf.result.DONE") >= 1); assertEquals(1, count("wf.result.SKIPPED"))
        assertSourcesUntouched()
    }

    @Test fun freeRunsAreLimitedToTenFiles() {
        start(pro = false, count = 12)
        val w = Workflow("f", "Free", WorkflowFormat.JPEG, longEdge = 1080, finish = WorkflowFinish.SAVE)
        runBlocking { app.workflows.upsert(w) }
        val ids = media().filter { it.kind != MediaKind.VIDEO }.map { it.id }
        assertTrue(ids.size > 10)
        rule.setContent { TestHost(app) { WorkflowRunScreen(WorkflowRunRequest("y", w.id, ids), nav) {} } }
        pumpUntil { count("wf.limit") == 1 }
        click("wf.start")
        pumpUntil(90_000) { count("wf.summary") == 1 }
        assertEquals(10, lib.saved.size) // nur die ersten 10
    }
}
