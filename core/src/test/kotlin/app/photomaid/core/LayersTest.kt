package app.photomaid.core

import app.photomaid.core.edit.BrushKind
import app.photomaid.core.edit.DrawLayer
import app.photomaid.core.edit.DrawStroke
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.FrameMath
import app.photomaid.core.edit.FrameMode
import app.photomaid.core.edit.FrameStyle
import app.photomaid.core.edit.LayerOps
import app.photomaid.core.edit.NormPoint
import app.photomaid.core.edit.NormRect
import app.photomaid.core.edit.PxSize
import app.photomaid.core.edit.RedactLayer
import app.photomaid.core.edit.RedactMode
import app.photomaid.core.edit.ShapeKind
import app.photomaid.core.edit.ShapeLayer
import app.photomaid.core.edit.StickerKind
import app.photomaid.core.edit.StickerLayer
import app.photomaid.core.edit.TextLayer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayersTest {
    private val layers = listOf(
        TextLayer("t", "Hallo"), StickerLayer("s", StickerKind.EMOJI, "🎉"),
        DrawLayer("d", listOf(DrawStroke(listOf(NormPoint(0.1f, 0.1f), NormPoint(0.2f, 0.3f)), 0.01f, -1, BrushKind.MARKER))),
        ShapeLayer("a", ShapeKind.ARROW, NormPoint(0.1f, 0.1f), NormPoint(0.5f, 0.5f)),
        RedactLayer("r", RedactMode.PIXELATE, rect = NormRect(0.2f, 0.2f, 0.4f, 0.3f)),
    )

    @Test fun recipeWithLayersRoundTripsAsJson() {
        val r = EditRecipe(layers = layers, frame = FrameStyle(width = 0.05f))
        val json = Json.encodeToString(EditRecipe.serializer(), r)
        assertEquals(r, Json.decodeFromString(EditRecipe.serializer(), json))
    }

    @Test fun markupIsFreeFrameIsPro() {
        assertFalse(EditRecipe(layers = layers).usesProTools)
        assertTrue(EditRecipe(frame = FrameStyle()).usesProTools)
        assertFalse(EditRecipe(layers = layers).isIdentity)
        assertFalse(EditRecipe(quarterTurns = 1, layers = layers).isOrientationOnly) // nicht mehr verlustfrei
    }

    @Test fun frameLayout() {
        val img = PxSize(1000, 800)
        assertEquals(img to (0 to 0), FrameMath.layout(img, FrameStyle(mode = FrameMode.INSIDE)))
        val (size, pos) = FrameMath.layout(img, FrameStyle(width = 0.05f))
        assertEquals(PxSize(1080, 880), size); assertEquals(40 to 40, pos)
        // Passepartout 4:5 (hoch): Breite bleibt, Höhe wächst; Bild mittig.
        val (s2, p2) = FrameMath.layout(img, FrameStyle(width = 0f, aspect = 0.8f))
        assertEquals(PxSize(1000, 1250), s2); assertEquals(0 to 225, p2)
    }

    @Test fun layerOrderingAndDuplicate() {
        assertEquals("t", LayerOps.toFront(layers, "t").last().id)
        assertEquals("r", LayerOps.toBack(layers, "r").first().id)
        val dup = LayerOps.duplicate(layers, "t", "t2")
        assertEquals(6, dup.size)
        assertEquals(0.53f, (dup.last() as TextLayer).center.x, 1e-5f)
        assertEquals(4, LayerOps.remove(layers, "s").size)
    }

    @Test fun snappingAndRedactBlocks() {
        assertEquals(0.5f to true, LayerOps.snap(0.51f))
        assertEquals(0.53f to false, LayerOps.snap(0.53f))
        assertEquals(90f to true, LayerOps.snapRotation(87f))
        assertEquals(45f to false, LayerOps.snapRotation(45f))
        // Verpixeln nie feiner als 8 px (sonst lesbar).
        assertTrue(RedactLayer("x", strength = 0f).blockPx(200) >= 8)
        assertTrue(RedactLayer("x", strength = 1f).blockPx(3000) >= 150)
    }
}
