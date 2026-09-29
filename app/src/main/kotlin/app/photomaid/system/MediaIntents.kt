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

    fun view(uri: Uri, kind: MediaKind, mime: String?): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType(kind, mime)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
