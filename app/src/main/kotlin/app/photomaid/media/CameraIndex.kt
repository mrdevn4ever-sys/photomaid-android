package app.photomaid.media

import androidx.exifinterface.media.ExifInterface
import app.photomaid.core.model.MediaKind
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Kamera-Modell je Foto aus EXIF (`Make`/`Model`) für Suche und Info-Blatt. Nur gelesen, nur im Speicher dieser
 * Sitzung, nie gespeichert oder gesendet. Videos und Fotos ohne EXIF haben kein Modell (keine Schätzung).
 */
class CameraIndex(private val open: (String) -> java.io.InputStream?) {
    private val models = ConcurrentHashMap<String, String>()
    private val _version = MutableStateFlow(0)
    /** Steigt, sobald neue Modelle gelesen wurden (Suche baut ihren Index dann neu). */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun cached(id: String): String? = models[id]?.takeIf { it.isNotEmpty() }

    fun model(a: MediaAsset): String? {
        if (a.kind == MediaKind.VIDEO) return null
        models[a.id]?.let { return it.takeIf(String::isNotEmpty) }
        val m = runCatching { open(a.id)?.use { read(ExifInterface(it)) } }.getOrNull()
        models[a.id] = m ?: ""
        return m
    }

    /** Liest fehlende Modelle nach und nach (abbrechbar); meldet sich alle 200 Fotos und am Ende über [version]. */
    suspend fun index(assets: List<MediaAsset>) {
        var n = 0
        for (a in assets) {
            coroutineContext.ensureActive()
            if (a.kind == MediaKind.VIDEO || models.containsKey(a.id)) continue
            model(a)
            if (++n % 200 == 0) _version.value++
        }
        if (n > 0) _version.value++
    }

    companion object {
        fun read(exif: ExifInterface): String? = combine(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL))

        /** „Google“ + „Pixel 9“ → „Google Pixel 9“; „Apple“ + „Apple iPhone 15“ → „Apple iPhone 15“. */
        fun combine(make: String?, model: String?): String? {
            val mk = make?.trim()?.takeIf { it.isNotEmpty() }
            val md = model?.trim()?.takeIf { it.isNotEmpty() } ?: return mk
            return if (mk == null || md.contains(mk, ignoreCase = true)) md else "$mk $md"
        }
    }
}
