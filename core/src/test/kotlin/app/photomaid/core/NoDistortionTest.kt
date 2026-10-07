package app.photomaid.core

import app.photomaid.core.edit.EditGeometry
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.PxSize
import app.photomaid.core.edit.ResizeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Grundsatz: keine Funktion verzerrt. Ausgabegröße hat immer das Verhältnis des Ausschnitts (±1 px Rundung). */
class NoDistortionTest {
    private fun sameRatio(a: PxSize, b: PxSize) = abs(a.width.toDouble() / a.height - b.width.toDouble() / b.height) < 2.0 / minOf(b.width, b.height)

    @Test fun oldRecipeWithIndependentSidesIsNotStretched() {
        val crop = PxSize(4000, 3000)
        val out = ResizeRules.clampToSource(2000, 2000, crop)
        assertEquals(PxSize(2000, 1500), out)
        assertTrue(sameRatio(crop, ResizeRules.clampToSource(100, 3000, crop)))
        assertEquals(crop, ResizeRules.clampToSource(9000, 9000, crop)) // nie hochskalieren
        assertEquals(crop, ResizeRules.clampToSource(null, 10, crop))
    }

    @Test fun presetsAndFieldsKeepRatio() {
        val crop = PxSize(3024, 4032)
        for (p in ResizeRules.presetsFor(crop)) assertTrue(sameRatio(crop, ResizeRules.forLongEdge(crop, p)))
        assertTrue(sameRatio(crop, ResizeRules.forWidth(crop, 1000)))
        assertTrue(sameRatio(crop, ResizeRules.forHeight(crop, 999)))
    }

    @Test fun geometryOutputFollowsCrop() {
        val g = EditGeometry(PxSize(4000, 3000), EditRecipe(resizeWidth = 1000, resizeHeight = 1000))
        assertTrue(sameRatio(PxSize(4000, 3000), g.output))
    }
}
