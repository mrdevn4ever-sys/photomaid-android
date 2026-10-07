package app.photomaid.core

import app.photomaid.core.batch.TargetSize
import app.photomaid.core.batch.VideoTarget
import app.photomaid.core.batch.Workflow
import app.photomaid.core.batch.WorkflowFinish
import app.photomaid.core.batch.WorkflowFormat
import app.photomaid.core.batch.WorkflowRules
import app.photomaid.core.batch.WorkflowStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowTest {
    private val classifieds = Workflow("k", "Für Kleinanzeigen", WorkflowFormat.JPEG, longEdge = 2000, removeLocation = true,
        nameBase = "Kleinanzeige", finish = WorkflowFinish.SHARE)

    @Test fun stepsDescribeOnlyWhatHappensInOrder() {
        assertEquals(listOf(WorkflowStep.FORMAT, WorkflowStep.RESIZE, WorkflowStep.QUALITY, WorkflowStep.LOCATION, WorkflowStep.RENAME, WorkflowStep.SHARE),
            WorkflowRules.steps(classifieds))
        val family = Workflow("f", "Familie", longEdge = 2048, folder = "Pictures/Familie/", photoMaxBytes = 2_000_000, videoMaxBytes = 20_000_000)
        assertEquals(listOf(WorkflowStep.RESIZE, WorkflowStep.TARGET_SIZE, WorkflowStep.VIDEO_TARGET, WorkflowStep.FOLDER, WorkflowStep.SAVE),
            WorkflowRules.steps(family))
        // Teilen ohne Speichern: kein Ordner-Schritt (es wird nichts in der Galerie gespeichert).
        assertFalse(WorkflowStep.FOLDER in WorkflowRules.steps(family.copy(finish = WorkflowFinish.SHARE)))
    }

    @Test fun namesAreNumberedAndSafe() {
        assertEquals("Kleinanzeige_001", WorkflowRules.fileStem(classifieds, 0, 12, "IMG_1.jpg", "PhotoMaid"))
        assertEquals("Kleinanzeige_0042", WorkflowRules.fileStem(classifieds, 41, 1200, "IMG_1.jpg", "PhotoMaid"))
        assertEquals("IMG_0042_bearbeitet", WorkflowRules.fileStem(classifieds.copy(nameBase = null), 0, 1, "IMG_0042.HEIC", "bearbeitet"))
        assertEquals("Ferien_2026", WorkflowRules.sanitizeBase(" Ferien 2026/ "))
        assertNull(WorkflowRules.sanitizeBase(" /:* "))
    }

    @Test fun formatSizeAndValidity() {
        assertEquals(WorkflowFormat.PNG, WorkflowRules.outputFormat(Workflow("a", "a"), "image/png"))
        assertEquals(WorkflowFormat.JPEG, WorkflowRules.outputFormat(Workflow("a", "a"), "image/heic"))
        assertEquals(1200, WorkflowRules.targetEdge(classifieds, 1200, 900)) // nie hochskaliert
        assertEquals(2000, WorkflowRules.targetEdge(classifieds, 4032, 3024))
        assertTrue(WorkflowRules.isValid(classifieds))
        assertFalse(WorkflowRules.isValid(classifieds.copy(name = "  ")))
        assertFalse(WorkflowRules.isValid(classifieds.copy(photoMaxBytes = 10_000)))
        assertFalse(WorkflowRules.isValid(classifieds.copy(minLongEdge = 3000)))
    }

    @Test fun budgetIsSharedBySize() {
        assertEquals(listOf(2_000_000L, 6_000_000L), WorkflowRules.budgetShares(listOf(1_000_000L, 3_000_000L), 8_000_000))
        assertEquals(listOf(4_000_000L, 4_000_000L), WorkflowRules.budgetShares(listOf(null, 3_000_000L), 8_000_000))
    }

    /** Modell-Encoder: Bytes ∝ Fläche × Qualitätsfaktor (für die Suche genügt Monotonie). */
    private fun model(edge: Int, q: Int) = (edge.toLong() * edge * 3 / 4) * (q + 10) / 120

    @Test fun targetSizeFindsHighestFittingQualityFirst() = runBlocking {
        val tried = mutableListOf<TargetSize.Attempt>()
        val r = TargetSize.search(2_000_000, 2000, 90, 1024, 60, lossless = false) { a -> tried += a; model(a.longEdge, a.quality) }
        assertTrue(r is TargetSize.Outcome.Met)
        assertEquals(2000, r.attempt.longEdge) // Kante bleibt, nur die Qualität sinkt
        assertTrue(r.bytes <= 2_000_000)
        assertTrue("höchste passende Qualität", model(2000, r.attempt.quality + 3) > 2_000_000)
    }

    @Test fun targetSizeShrinksEdgeOnlyWhenNeededAndReportsUnreachable() = runBlocking {
        val shrunk = TargetSize.search(600_000, 2000, 90, 1024, 60, lossless = false) { a -> model(a.longEdge, a.quality) }
        assertTrue(shrunk is TargetSize.Outcome.Met)
        assertTrue(shrunk.attempt.longEdge in 1024 until 2000)
        assertTrue(shrunk.bytes <= 600_000)
        val impossible = TargetSize.search(50_000, 2000, 90, 1024, 60, lossless = false) { a -> model(a.longEdge, a.quality) }
        assertTrue(impossible is TargetSize.Outcome.Unreachable)
        assertEquals(1024, impossible.attempt.longEdge); assertEquals(60, impossible.attempt.quality)
        assertTrue(impossible.bytes > 50_000)
        // PNG (verlustfrei): nur die Kante kann sinken.
        val png = TargetSize.search(1_000_000, 2000, 90, 640, 60, lossless = true) { a -> a.longEdge.toLong() * a.longEdge }
        assertTrue(png is TargetSize.Outcome.Met); assertEquals(100, png.attempt.quality)
    }

    @Test fun videoTargetPlansBitrateAndNeverUpscales() {
        val p = VideoTarget.plan(20_000_000, 60_000, hasAudio = true, width = 1920, height = 1080)!!
        assertTrue(p.videoBitrate in 2_000_000..2_500_000)
        assertTrue(p.maxHeight <= 1080)
        val small = VideoTarget.plan(20_000_000, 60_000, hasAudio = true, width = 640, height = 360)!!
        assertEquals(360, small.maxHeight) // nie hochskaliert
        // 10 Minuten in 10 MB: zu wenig Bits – nicht erreichbar, statt stillschweigend zu groß.
        assertNull(VideoTarget.plan(10_000_000, 600_000, hasAudio = true, width = 1920, height = 1080))
        // Knappe Bitrate → kleinere Stufe.
        val tight = VideoTarget.plan(10_000_000, 120_000, hasAudio = true, width = 3840, height = 2160)!!
        assertTrue(tight.maxHeight < 2160)
    }
}
