package app.photomaid.system

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutManagerCompat
import app.photomaid.core.share.ShareRules
import app.photomaid.core.share.SharedItem
import app.photomaid.core.share.ShortcutTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Einstiege von außen: App-Shortcuts (Launcher, angeheftet) und das Teilen-Ziel („Senden an PhotoMaid“, auch direkt
 * an einen gespeicherten Ablauf). Ein Einstieg öffnet nur eine Ansicht – nie eine Entscheidung, nie Löschen.
 * Die App-Oberfläche holt den offenen Einstieg ab, sobald sie bereit ist (nach dem Onboarding).
 */
sealed interface EntryRequest {
    data class Shortcut(val target: ShortcutTarget) : EntryRequest
    /** [workflowId]: über einen Direktes-Teilen-Shortcut eines Ablaufs übergeben. */
    data class Share(val items: List<SharedItem>, val workflowId: String?, val stamp: Long = System.nanoTime()) : EntryRequest
}

object AppEntry {
    const val ACTION_SHORTCUT = "app.photomaid.action.SHORTCUT"
    const val EXTRA_TARGET = "app.photomaid.extra.SHORTCUT_TARGET"

    /**
     * Offener Einstieg je Fenster ([owner] = Activity-Kennung): Teilen startet oft eine zweite Activity im Task der
     * sendenden App – eine im Hintergrund weiterlebende PhotoMaid-Activity darf den Einstieg nicht abholen.
     */
    private val _pending = MutableStateFlow<Pair<Int, EntryRequest>?>(null)
    val pending: StateFlow<Pair<Int, EntryRequest>?> = _pending.asStateFlow()

    fun post(owner: Int, request: EntryRequest) { _pending.value = owner to request }
    /** Abholen: genau einmal und nur durch das Fenster, an das der Einstieg ging. */
    fun take(owner: Int): EntryRequest? {
        val p = _pending.value ?: return null
        if (p.first != owner || !_pending.compareAndSet(p, null)) return null
        return p.second
    }

    fun shortcutIntent(context: Context, target: ShortcutTarget): Intent =
        Intent(ACTION_SHORTCUT).setClassName(context, "app.photomaid.MainActivity").putExtra(EXTRA_TARGET, target.id)

    /**
     * Liest den Intent aus. Die Übergabe-Rechte gelten nur solange die Activity lebt – deshalb hier sofort Typ, Name,
     * Größe und (für Dokument-URIs) die MediaStore-Entsprechung abfragen. Läuft auf einem Hintergrund-Thread.
     */
    fun read(context: Context, intent: Intent?): EntryRequest? {
        intent ?: return null
        return when (intent.action) {
            ACTION_SHORTCUT -> ShortcutTarget.parse(intent.getStringExtra(EXTRA_TARGET))?.let { t ->
                runCatching { ShortcutManagerCompat.reportShortcutUsed(context, t.id) }
                EntryRequest.Shortcut(t)
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val uris = streams(intent).distinct().take(ShareRules.MAX_ITEMS * 2)
                if (uris.isEmpty()) return null
                val shortcut = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
                val workflowId = (ShortcutTarget.parse(shortcut) as? ShortcutTarget.Workflow)?.workflowId
                EntryRequest.Share(uris.map { describe(context, it, intent.type) }, workflowId)
            }
            else -> null
        }
    }

    private fun streams(intent: Intent): List<Uri> {
        val out = ArrayList<Uri>()
        if (intent.action == Intent.ACTION_SEND) IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(out::add)
        else IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(out::addAll)
        intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(out::add) }
        return out.filter { it.scheme == "content" }
    }

    private fun describe(context: Context, uri: Uri, intentType: String?): SharedItem {
        val resolver = context.contentResolver
        val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: intentType?.takeIf { !it.endsWith("/*") && it != "*/*" }
        var name: String? = null; var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val iN = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val iS = c.getColumnIndex(OpenableColumns.SIZE)
                    if (iN >= 0 && !c.isNull(iN)) name = c.getString(iN)
                    if (iS >= 0 && !c.isNull(iS)) size = c.getLong(iS).takeIf { it > 0 }
                }
            }
        }
        val media = mediaUri(context, uri)
        return SharedItem(media?.toString() ?: uri.toString(), mime, name, size)
    }

    /** MediaStore-URI, falls die Übergabe auf die Mediathek zeigt (direkt oder als Dokument). */
    private fun mediaUri(context: Context, uri: Uri): Uri? {
        if (uri.authority == MediaStore.AUTHORITY) return uri
        if (runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false))
            return runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
        return null
    }

    /**
     * Kandidaten-IDs im Format der Mediathek (`content://media/external/{images|video}/media/<id>`).
     * `…/file/<id>` kann Foto oder Video sein – beide Kandidaten, die Mediathek bestätigt.
     */
    fun libraryCandidates(uri: String): List<String> {
        val u = runCatching { Uri.parse(uri) }.getOrNull() ?: return emptyList()
        if (u.authority != MediaStore.AUTHORITY) return emptyList()
        val id = runCatching { ContentUris.parseId(u) }.getOrDefault(-1L).takeIf { it >= 0 } ?: return emptyList()
        val images = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id).toString()
        val video = ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id).toString()
        val segs = u.pathSegments
        return when {
            "images" in segs -> listOf(images)
            "video" in segs -> listOf(video)
            "file" in segs -> listOf(images, video)
            else -> emptyList()
        }
    }
}
