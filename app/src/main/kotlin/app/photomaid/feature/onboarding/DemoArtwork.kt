package app.photomaid.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * Gezeichnete Demo-Motive für Onboarding und Angebot – bewusst keine Fotos (keine fremden Menschen, keine Lizenzfragen,
 * keine Verwechslung mit eigenen Medien). Ruhige Landschaften in warmen Tönen + PhotoMaid-Blau, kein Violett.
 */
object DemoArt {
    const val COUNT = 6
}

private data class Scene(val skyTop: Color, val skyBottom: Color, val sun: Color, val far: Color, val near: Color, val kind: Int)

private val scenes = listOf(
    Scene(Color(0xFF8EC5F2), Color(0xFFF6E7CF), Color(0xFFF2B33D), Color(0xFF6F93B8), Color(0xFF3E6A8E), 0), // Berge, Tag
    Scene(Color(0xFFF3C77A), Color(0xFFFBEBD3), Color(0xFFE88A3A), Color(0xFF7FA6C9), Color(0xFF2F7BBF), 1), // Meer, Abend
    Scene(Color(0xFFBFE0F5), Color(0xFFEFF5E8), Color(0xFFF0C14B), Color(0xFF8DB98A), Color(0xFF4F8A5B), 2), // Hügel
    Scene(Color(0xFF2B4A6F), Color(0xFF6E8FB3), Color(0xFFF5E6B8), Color(0xFF34557A), Color(0xFF1E3550), 0), // Berge, Nacht
    Scene(Color(0xFFDCEBF7), Color(0xFFF8F1E6), Color(0xFFF2B33D), Color(0xFF6E9C6A), Color(0xFF3D6E46), 3), // Wald
    Scene(Color(0xFF9FD3EE), Color(0xFFFFF4DF), Color(0xFFF4B942), Color(0xFFE2C79A), Color(0xFFC9A46C), 1), // Strand
)

@Composable
fun DemoArtwork(variant: Int, modifier: Modifier = Modifier) {
    val s = scenes[((variant % scenes.size) + scenes.size) % scenes.size]
    Canvas(modifier.clearAndSetSemantics {}) { drawScene(s) }
}

private fun DrawScope.drawScene(s: Scene) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(s.skyTop, s.skyBottom)))
    drawCircle(s.sun, radius = w * 0.11f, center = Offset(w * 0.72f, h * 0.30f))
    drawCircle(s.sun.copy(alpha = 0.18f), radius = w * 0.2f, center = Offset(w * 0.72f, h * 0.30f))
    when (s.kind) {
        0 -> { // Berge
            ridge(s.far, h * 0.62f, listOf(0f to 0.62f, 0.22f to 0.38f, 0.42f to 0.55f, 0.63f to 0.33f, 0.86f to 0.52f, 1f to 0.45f))
            ridge(s.near, h, listOf(0f to 0.78f, 0.3f to 0.56f, 0.55f to 0.74f, 0.8f to 0.6f, 1f to 0.72f))
        }
        1 -> { // Meer/Strand
            drawRect(s.far, topLeft = Offset(0f, h * 0.6f), size = Size(w, h * 0.4f))
            for (i in 0..3) drawRect(Color.White.copy(alpha = 0.18f), topLeft = Offset(w * (0.1f + i * 0.22f), h * (0.66f + i * 0.06f)), size = Size(w * 0.16f, h * 0.012f))
            ridge(s.near, h, listOf(0f to 0.86f, 0.4f to 0.8f, 0.7f to 0.84f, 1f to 0.78f))
        }
        2 -> { // Hügel
            ridge(s.far, h, listOf(0f to 0.62f, 0.35f to 0.5f, 0.7f to 0.6f, 1f to 0.52f))
            ridge(s.near, h, listOf(0f to 0.78f, 0.45f to 0.66f, 1f to 0.8f))
        }
        else -> { // Wald
            ridge(s.far, h, listOf(0f to 0.66f, 0.5f to 0.6f, 1f to 0.66f))
            for (i in 0..6) {
                val x = w * (0.04f + i * 0.155f); val top = h * (0.42f + (i % 3) * 0.05f)
                val p = Path().apply { moveTo(x, h * 0.86f); lineTo(x + w * 0.07f, top); lineTo(x + w * 0.14f, h * 0.86f); close() }
                drawPath(p, s.near)
            }
            drawRect(s.near, topLeft = Offset(0f, h * 0.85f), size = Size(w, h * 0.15f))
        }
    }
}

private fun DrawScope.ridge(color: Color, bottom: Float, points: List<Pair<Float, Float>>) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(0f, bottom)
        points.forEach { (x, y) -> lineTo(x * w, y * h) }
        lineTo(w, bottom); close()
    }
    drawPath(p, color)
}
