package app.photomaid.core

import app.photomaid.core.edit.NormRect
import app.photomaid.core.edit.TextLayer
import app.photomaid.core.studio.ExportProfile
import app.photomaid.core.studio.TimeRange
import app.photomaid.core.studio.TimedLayer
import app.photomaid.core.studio.VSize
import app.photomaid.core.studio.VideoClip
import app.photomaid.core.studio.VideoPlan
import app.photomaid.core.studio.VideoProject
import app.photomaid.core.studio.VideoRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlanTest {
    private val clip = VideoClip("c", "m", sourceDurationMs = 10_000)

    @Test fun cutRemovesTheMiddleAndTimesMapBothWays() {
        val c = VideoRules.addCut(clip, TimeRange(3_000, 5_000))
        val segs = VideoPlan.segments(c)
        assertEquals(2, segs.size)
        assertEquals(8_000, c.outputDurationMs)
        assertNull(VideoPlan.toOutputMs(c, 4_000)) // weggeschnitten
        assertEquals(3_500L, VideoPlan.toOutputMs(c, 5_500))
        assertEquals(5_500L, VideoPlan.toSourceMs(c, 3_500))
        assertEquals(1_000L, VideoPlan.toSourceMs(c, 1_000))
    }

    @Test fun speedShortensOutputAndScalesMapping() {
        val c = clip.copy(speed = 2f, trimStartMs = 2_000)
        assertEquals(4_000, c.outputDurationMs)
        assertEquals(1_000L, VideoPlan.toOutputMs(c, 4_000))
        assertEquals(4_000L, VideoPlan.toSourceMs(c, 1_000))
    }

    @Test fun overlappingCutsMergeAndNeverRemoveEverything() {
        var c = VideoRules.addCut(clip, TimeRange(1_000, 4_000))
        c = VideoRules.addCut(c, TimeRange(3_000, 6_000))
        assertEquals(listOf(TimeRange(1_000, 6_000)), c.cuts)
        val all = VideoRules.addCut(c, TimeRange(0, 10_000))
        assertEquals(c, all) // alles wegschneiden geht nicht
    }

    @Test fun gainFadesInAndOutAndMuteIsSilent() {
        val c = clip.copy(volume = 0.5f, fadeInMs = 1_000, fadeOutMs = 2_000)
        assertEquals(0f, VideoPlan.gain(c, 0), 1e-4f)
        assertEquals(0.25f, VideoPlan.gain(c, 500), 1e-4f)
        assertEquals(0.5f, VideoPlan.gain(c, 5_000), 1e-4f)
        assertEquals(0.25f, VideoPlan.gain(c, 9_000), 1e-4f)
        assertEquals(0f, VideoPlan.gain(c.copy(muted = true), 5_000), 1e-4f)
    }

    @Test fun layoutRotatesCropsAndNeverUpscales() {
        val p = VideoProject("p", listOf(clip.copy(quarterTurns = 1, crop = NormRect(0f, 0f, 1f, 0.5f))))
        val l = VideoPlan.layout(VSize(1920, 1080), p.clips[0], p)
        assertEquals(VSize(1080, 1920), l.rotated)
        assertEquals(VSize(1080, 960), l.cropped)
        assertEquals(VSize(1080, 960), l.output)
        // Kleines Profil: kurze Seite 720, gerade Werte.
        val small = VideoPlan.layout(VSize(1920, 1080), clip, p.copy(clips = listOf(clip), profile = ExportProfile.SMALL))
        assertEquals(VSize(1280, 720), small.output)
        // Ausfüllen 9:16 aus 16:9: Rahmen liegt im Bild (Höhe bleibt 1080).
        val fill = VideoPlan.layout(VSize(1920, 1080), clip, p.copy(clips = listOf(clip), aspect = 9f / 16f, fill = true))
        assertEquals(1080, fill.output.height); assertEquals(608, fill.output.width)
        // Einpassen 1:1: Rahmen umschließt (Ränder), Breite bleibt.
        val fit = VideoPlan.layout(VSize(1920, 1080), clip, p.copy(clips = listOf(clip), aspect = 1f, fill = false))
        assertEquals(VSize(1920, 1920), fit.output) // Bild bleibt 1920 breit, Ränder oben/unten
    }

    @Test fun cropNdcIsTopUp() {
        val n = VideoPlan.cropNdc(NormRect(0.25f, 0f, 0.75f, 0.5f))
        assertEquals(-0.5f, n[0], 1e-6f); assertEquals(0.5f, n[1], 1e-6f); assertEquals(0f, n[2], 1e-6f); assertEquals(1f, n[3], 1e-6f)
    }

    @Test fun estimateIsMonotoneInProfileAndAudio() {
        val p = VideoProject("p", listOf(clip))
        val l = VideoPlan.layout(VSize(1920, 1080), clip, p)
        val high = VideoPlan.estimateBytes(p, l, 30f)
        val lSmall = VideoPlan.layout(VSize(1920, 1080), clip, p.copy(profile = ExportProfile.SMALL))
        val small = VideoPlan.estimateBytes(p.copy(profile = ExportProfile.SMALL), lSmall, 30f)
        assertTrue(small < high)
        assertTrue(VideoPlan.estimateBytes(p.copy(removeAudio = true), l, 30f) < high)
    }

    @Test fun layerAlphaFadesAtEdges() {
        val l = TimedLayer(TextLayer("t", "Hallo"), 1_000, 3_000)
        assertEquals(0f, VideoPlan.layerAlpha(l, 500), 1e-4f)
        assertEquals(0.5f, VideoPlan.layerAlpha(l, 1_100), 1e-4f)
        assertEquals(1f, VideoPlan.layerAlpha(l, 2_000), 1e-4f)
        assertEquals(0f, VideoPlan.layerAlpha(l, 3_100), 1e-4f)
    }
}
