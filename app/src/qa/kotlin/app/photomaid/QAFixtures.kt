package app.photomaid

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.MediaStore
import app.photomaid.core.collections.CollectionFilter
import app.photomaid.core.collections.CollectionSource
import app.photomaid.core.collections.SmartCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deterministische QA-Umgebung (iOS `QAFixtures`). Existiert ausschließlich im Build-Typ `qa` und wird nur mit
 * dem Intent-Extra `qa-fixtures=true` aktiv (In-Memory-Speicher). Keine persönlichen Daten.
 * Mit `qa-seed-media=true` legt der QA-Build synthetische Bilder (`QA_*.png`, Ordner `Pictures/PhotoMaid-QA`)
 * über MediaStore an – für Emulator-Durchgänge (`scripts/ci_emulator_smoke.sh`).
 */
object QAFixtures {
    var seedMedia = false

    fun install(app: AppModel) {
        app.scope.launch {
            if (seedMedia) withContext(Dispatchers.IO) { seed(app) }
            // Eine eigene Beispiel-Sammlung, damit „Deine Sammlungen“ im QA-Build nicht leer ist.
            app.collections.upsert(SmartCollection(source = CollectionSource.VIDEOS, filter = CollectionFilter.LargerThan(1_000_000_000)))
            app.photos.bumpChangeToken()
        }
    }

    private fun seed(app: AppModel) {
        val resolver = app.context.contentResolver
        val dir = "Pictures/PhotoMaid-QA/"
        val existing = resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(dir), null,
        )?.use { it.count } ?: 0
        if (existing > 0) return
        val palette = intArrayOf(0xFF7FB3F5.toInt(), 0xFFF5B97F.toInt(), 0xFF8FD6A4.toInt(), 0xFFE58FB0.toInt(), 0xFFB39DDB.toInt(), 0xFF80CBC4.toInt())
        for (i in 0 until 8) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "QA_$i.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: continue
            val bmp = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            // Zwei Paare identischer Bytes (exakte Duplikate für den Duplikat-Scan).
            c.drawColor(palette[(if (i >= 6) i - 6 else i) % palette.size])
            val p = Paint().apply { color = Color.WHITE; alpha = 170; isAntiAlias = true }
            c.drawCircle(450f, 520f, 260f, p)
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }
}
