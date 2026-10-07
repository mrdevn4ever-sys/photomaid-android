package app.photomaid.core

import app.photomaid.core.studio.ExportProfile
import app.photomaid.core.studio.MoviePlan
import app.photomaid.core.studio.MusicTrack
import app.photomaid.core.studio.Transition
import app.photomaid.core.studio.VSize
import app.photomaid.core.studio.VideoProject
import app.photomaid.core.studio.VideoRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoviePlanTest {
    private fun project() = VideoProject("p", listOf(MoviePlan.photoClip("a", "1", 3000), MoviePlan.videoClip("b", "2", 4000), MoviePlan.photoClip("c", "3", 2000)),
        transitions = listOf(Transition.FADE_BLACK, Transition.NONE), transitionMs = 600)

    @Test fun startsAndTotal() {
        val p = project()
        assertEquals(listOf(0L, 3000L, 7000L), MoviePlan.starts(p))
        assertEquals(9000L, MoviePlan.totalMs(p))
        assertEquals(listOf(3000L), MoviePlan.blackSeams(p))
    }

    @Test fun blackAlphaPeaksOnSeamOnly() {
        val p = project()
        assertEquals(1f, MoviePlan.blackAlpha(p, 3000), 0.001f)
        assertEquals(0.5f, MoviePlan.blackAlpha(p, 2850), 0.01f)
        assertEquals(0f, MoviePlan.blackAlpha(p, 2600), 0.001f)
        assertEquals(0f, MoviePlan.blackAlpha(p, 7000), 0.001f) // Naht ohne Übergang
        // Kurzer Clip: halbe Dauer höchstens ein Drittel des kürzeren Nachbarn.
        val short = p.copy(clips = listOf(MoviePlan.photoClip("a", "1", 600), MoviePlan.photoClip("b", "2", 600)), transitions = listOf(Transition.FADE_BLACK), transitionMs = 1500)
        assertEquals(0f, MoviePlan.blackAlpha(short, 300), 0.001f)
    }

    @Test fun musicFadesAtBothEnds() {
        val p = project().copy(music = MusicTrack("u", "n", volume = 0.5f, fadeMs = 1000))
        assertEquals(0f, MoviePlan.musicGain(p, 0), 0.001f)
        assertEquals(0.25f, MoviePlan.musicGain(p, 500), 0.001f)
        assertEquals(0.5f, MoviePlan.musicGain(p, 4000), 0.001f)
        assertEquals(0f, MoviePlan.musicGain(p, 9000), 0.001f)
        assertEquals(0f, MoviePlan.musicGain(project(), 4000), 0.001f)
    }

    @Test fun frameKeepsAspectAndEvenSizes() {
        val p = project()
        assertEquals(VSize(1920, 1080), MoviePlan.frame(p, VSize(4000, 2250)))
        assertEquals(VSize(1080, 1920), MoviePlan.frame(p.copy(aspect = 9f / 16f), VSize(4000, 3000)))
        assertEquals(VSize(1080, 1080), MoviePlan.frame(p.copy(aspect = 1f), VSize(4000, 3000)))
        val small = MoviePlan.frame(p.copy(profile = ExportProfile.SMALL), VSize(4000, 3000))
        assertEquals(720, small.height); assertTrue(small.width % 2 == 0)
    }

    @Test fun limitsAndSlideshow() {
        assertNull(MoviePlan.problem(project()))
        assertEquals(MoviePlan.Limit.TOO_FEW, MoviePlan.problem(project().copy(clips = project().clips.take(1))))
        val s = MoviePlan.slideshow("s", listOf("1", "2", "3"), 4)
        assertEquals(12_000L, MoviePlan.totalMs(s))
        assertEquals(List(2) { Transition.FADE_BLACK }, s.transitions)
        assertTrue(s.clips.all { it.isPhoto && it.kenBurns })
        assertEquals(VideoRules.MIN_CLIP_MS, MoviePlan.withPhotoDuration(s.clips[0], 10).outputDurationMs)
        assertEquals(1f, MoviePlan.kenBurnsScale(-1f), 0f); assertEquals(1.08f, MoviePlan.kenBurnsScale(2f), 0.0001f)
    }
}
