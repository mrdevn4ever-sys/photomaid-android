package app.photomaid.system

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import app.photomaid.core.model.MediaKind

/**
 * Teilen und Öffnen über Android (kein eigenes Sharing, kein Upload). Die Empfänger-App erhält nur eine
 * befristete Leseberechtigung für genau diese Content-URI.
 */
object MediaIntents {
    fun mimeType(kind: MediaKind, mime: String?): String =
        mime?.takeIf { it.contains('/') } ?: if (kind == MediaKind.VIDEO) "video/*" else "image/*"

    fun share(uri: Uri, kind: MediaKind, mime: String?, chooserTitle: String): Intent {
        val type = mimeType(kind, mime)
        val send = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Mehrere Medien teilen (eine Auswahl): befristete Leseberechtigung für genau diese URIs. */
    fun shareMany(uris: List<Uri>, mimes: List<String>, chooserTitle: String): Intent {
        if (uris.size == 1) return share(uris[0], if (mimes.firstOrNull()?.startsWith("video") == true) MediaKind.VIDEO else MediaKind.PHOTO, mimes.firstOrNull(), chooserTitle)
        val type = mimes.distinct().singleOrNull() ?: when {
            mimes.all { it.startsWith("image") } -> "image/*"
            mimes.all { it.startsWith("video") } -> "video/*"
            else -> "*/*"
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            this.type = type
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newRawUri(null, uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun view(uri: Uri, kind: MediaKind, mime: String?): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType(kind, mime)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
