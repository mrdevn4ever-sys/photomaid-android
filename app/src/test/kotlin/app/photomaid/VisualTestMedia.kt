package app.photomaid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.MediaKind
import app.photomaid.media.MediaAsset
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/**
 * Feste, aussagekräftige Testmotive für JVM-Screenshots – ausschließlich im Testcode, zur Laufzeit gezeichnet.
 * Keine Fotos realer Personen/Orte, keine privaten Inhalte, keine Lizenzfragen, kein Netzwerk.
 * Die Motive sind erkennbar synthetisch (Landschaftsgrafik), decken aber die Geometrien echter Mediatheken ab:
 * Hochformat 3:4, Querformat 4:3, Panorama 3:1, Screenshot 9:19,5 mit lesbarem Text, Video 16:9.
 * Reihenfolge und Arten passen zu den Indizes in `ScreenshotTest.seededApp`.
 */
object VisualTestMedia {
    private data class Spec(val name: String, val kind: MediaKind, val w: Int, val h: Int, val declaredW: Int, val declaredH: Int,
                            val bytes: Long, val duration: Double = 0.0, val draw: (Canvas, Int, Int) -> Unit)

    private val specs = listOf(
        Spec("portrait_lake", MediaKind.PHOTO, 900, 1200, 3024, 4032, 3_412_000) { c, w, h -> landscape(c, w, h, 0xFF6FA8DC, 0xFFD6E9F8, 0xFF4E7F52, 0xFF2F5D3A, lake = true) },
        Spec("landscape_sunset", MediaKind.PHOTO, 1200, 900, 4032, 3024, 2_986_000) { c, w, h -> landscape(c, w, h, 0xFFE9785B, 0xFFF7D08A, 0xFF7A4B6B, 0xFF3F2B45, lake = false) },
        Spec("screenshot_settings", MediaKind.SCREENSHOT, 540, 1170, 1080, 2340, 412_000) { c, w, h -> screenshot(c, w, h) },
        Spec("panorama_ridge", MediaKind.PHOTO, 1800, 600, 9000, 3000, 7_830_000) { c, w, h -> landscape(c, w, h, 0xFF8EC5E8, 0xFFEAF4FB, 0xFF8095A8, 0xFF4F6275, lake = false) },
        Spec("portrait_evening", MediaKind.PHOTO, 900, 1200, 3024, 4032, 3_120_000) { c, w, h -> landscape(c, w, h, 0xFF2E3A6E, 0xFF9C7BB5, 0xFF3C4A5C, 0xFF1F2733, lake = true) },
        Spec("landscape_meadow", MediaKind.PHOTO, 1200, 900, 4032, 3024, 2_640_000) { c, w, h -> landscape(c, w, h, 0xFF9DD3F0, 0xFFF2F8EC, 0xFF8CBF5A, 0xFF5E9442, lake = false) },
        Spec("video_coast", MediaKind.VIDEO, 1280, 720, 1920, 1080, 48_200_000, duration = 32.0) { c, w, h -> landscape(c, w, h, 0xFF4F9CC9, 0xFFBFE3F2, 0xFFC9B48A, 0xFF8C7A55, lake = true) },
        Spec("video_portrait", MediaKind.VIDEO, 720, 1280, 1080, 1920, 21_700_000, duration = 14.0) { c, w, h -> landscape(c, w, h, 0xFF7C5FA8, 0xFFF0B98D, 0xFF4E4A6B, 0xFF2B2940, lake = false) },
    )

    fun create(context: Context): List<MediaAsset> {
        val dir = File(context.cacheDir, "qa-visual").apply { mkdirs() }
        val base = Instant.parse("2026-09-27T16:40:00Z")
        return specs.mapIndexed { i, s ->
            val f = File(dir, "${s.name}.png")
            if (!f.exists()) {
                val bmp = Bitmap.createBitmap(s.w, s.h, Bitmap.Config.ARGB_8888)
                s.draw(Canvas(bmp), s.w, s.h)
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            MediaAsset(
                id = "file://${f.absolutePath}", kind = s.kind, creationDate = base.minusSeconds(86_400L * i + 3_600L * i),
                duration = s.duration, pixelWidth = s.declaredW, pixelHeight = s.declaredH, size = KnownSize.Known(s.bytes),
                mimeType = if (s.kind == MediaKind.VIDEO) "video/mp4" else "image/png", displayName = s.name, isFavorite = false,
            )
        }
    }

    private fun landscape(c: Canvas, w: Int, h: Int, skyTop: Long, skyBottom: Long, hillFar: Long, hillNear: Long, lake: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.65f, skyTop.toInt(), skyBottom.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = Color.argb(230, 255, 244, 214); c.drawCircle(w * 0.72f, h * 0.28f, minOf(w, h) * 0.08f, p)
        fun ridge(color: Long, base: Float, amp: Float, phase: Float) {
            val path = Path().apply {
                moveTo(0f, h.toFloat())
                val steps = 24
                for (k in 0..steps) {
                    val x = w * k / steps.toFloat()
                    val y = h * base - amp * h * (0.5f + 0.5f * kotlin.math.sin(k * 0.9f + phase) * kotlin.math.cos(k * 0.37f))
                    lineTo(x, y)
                }
                lineTo(w.toFloat(), h.toFloat()); close()
            }
            p.color = color.toInt(); c.drawPath(path, p)
        }
        ridge(hillFar, 0.62f, 0.16f, 0.3f)
        ridge(hillNear, 0.78f, 0.12f, 1.7f)
        if (lake) {
            p.shader = LinearGradient(0f, h * 0.8f, 0f, h.toFloat(), skyBottom.toInt(), skyTop.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, h * 0.84f, w.toFloat(), h.toFloat(), p); p.shader = null
        }
    }

    /** Synthetischer Einstellungs-Screenshot mit lesbarem Text (prüft: kein Beschnitt, Text scharf). */
    private fun screenshot(c: Canvas, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(Color.rgb(242, 242, 247))
        p.color = Color.rgb(20, 20, 24); p.textSize = w * 0.035f
        c.drawText("09:41", w * 0.06f, h * 0.035f, p)
        c.drawText("100 %", w * 0.82f, h * 0.035f, p)
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = w * 0.075f
        c.drawText("Einstellungen", w * 0.06f, h * 0.11f, p)
        p.typeface = Typeface.DEFAULT
        val rows = listOf("WLAN" to "Zuhause", "Bluetooth" to "Ein", "Mobilfunk" to "", "Akku" to "82 %", "Anzeige" to "", "Töne" to "",
            "Datenschutz" to "", "Speicher" to "41,2 GB frei", "Allgemein" to "", "Über dieses Gerät" to "")
        var y = h * 0.15f
        val rowH = h * 0.058f
        for ((i, r) in rows.withIndex()) {
            if (i == 0 || i == 4 || i == 7) { y += rowH * 0.4f }
            p.color = Color.WHITE; c.drawRect(w * 0.04f, y, w * 0.96f, y + rowH, p)
            p.color = listOf(Color.rgb(0, 122, 255), Color.rgb(52, 199, 89), Color.rgb(255, 149, 0), Color.rgb(255, 59, 48))[i % 4]
            c.drawRoundRect(w * 0.07f, y + rowH * 0.2f, w * 0.07f + rowH * 0.6f, y + rowH * 0.8f, 10f, 10f, p)
            p.color = Color.rgb(20, 20, 24); p.textSize = w * 0.045f
            c.drawText(r.first, w * 0.07f + rowH * 0.85f, y + rowH * 0.65f, p)
            if (r.second.isNotEmpty()) {
                p.color = Color.rgb(120, 120, 128)
                c.drawText(r.second, w * 0.9f - p.measureText(r.second), y + rowH * 0.65f, p)
            }
            p.color = Color.rgb(220, 220, 225); c.drawRect(w * 0.07f + rowH * 0.85f, y + rowH - 1, w * 0.96f, y + rowH, p)
            y += rowH
        }
        p.color = Color.rgb(120, 120, 128); p.textSize = w * 0.032f
        c.drawText("Testmotiv · GaleriQ QA", w * 0.06f, h * 0.97f, p)
    }
}
