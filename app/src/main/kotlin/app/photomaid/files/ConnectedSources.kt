package app.photomaid.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.photomaid.core.cleanup.ExistenceProbe
import app.photomaid.core.cleanup.OperationOutcome
import app.photomaid.core.duplicates.ByteSourceProvider
import app.photomaid.core.model.ConnectedSourceID
import app.photomaid.core.model.InstantSerializer
import app.photomaid.core.model.ItemProvider
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.KnownSize
import app.photomaid.data.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.time.Instant

/** Vom Nutzer ausdrücklich freigegebener Ordner (SAF-Tree-URI mit dauerhafter Berechtigung). */
@Serializable
data class ConnectedSource(
    val id: ConnectedSourceID,
    val displayName: String,
    val treeUri: String,
    @Serializable(InstantSerializer::class) val addedAt: Instant,
    /** Zuletzt festgestellte Schreibfähigkeit (Löschen möglich). */
    val isWritable: Boolean,
    /** Als Downloads-Quelle erkannt (nur dann heißt der Screen „Downloads“). */
    val isDownloads: Boolean,
)

enum class SourceProblem { ACCESS_DENIED, UNAVAILABLE, CLOUD_PROVIDER_NOT_SUPPORTED, NOT_A_DIRECTORY }

class SourceException(val problem: SourceProblem) : Exception(problem.name)

enum class FileKindFilter {
    ALL, PDF, ARCHIVE, IMAGE, VIDEO, DOCUMENT, OTHER;

    companion object {
        fun classify(mime: String?): FileKindFilter {
            val m = mime?.lowercase() ?: return OTHER
            return when {
                m == "application/pdf" -> PDF
                m in ARCHIVES || m.contains("zip") || m.contains("compressed") || m.contains("x-tar") || m.contains("x-7z") || m.contains("x-rar") -> ARCHIVE
                m.startsWith("image/") -> IMAGE
                m.startsWith("video/") -> VIDEO
                m.startsWith("text/") || m.contains("document") || m.contains("presentation") || m.contains("spreadsheet") ||
                    m.contains("msword") || m.contains("ms-excel") || m.contains("ms-powerpoint") || m.contains("opendocument") ||
                    m == "application/rtf" || m == "application/json" -> DOCUMENT
                else -> OTHER
            }
        }

        private val ARCHIVES = setOf("application/zip", "application/gzip", "application/x-gzip", "application/java-archive")
    }
}

/** Eine Datei innerhalb einer verbundenen Quelle. */
data class FileEntry(
    val reference: ItemReference,
    val sourceID: ConnectedSourceID,
    /** Anzeigepfad innerhalb der Quelle (nur lokal verwendet, nie extern übertragen). */
    val relativePath: String,
    val documentUri: String,
    val name: String,
    val size: KnownSize,
    val modified: Instant?,
    val mimeType: String?,
    val canDelete: Boolean,
    /** Gleiche Ressource über zwei Quellen (Authority + Dokument-ID). */
    val aliasKey: String?,
) {
    val kind: FileKindFilter get() = FileKindFilter.classify(mimeType)
}

/**
 * Verwaltung verbundener Quellen (iOS `ConnectedSourceStore`). Verbindung entfernen ist keine Dateilöschung.
 * Blockierende Methoden (Scan, Löschen, Prüfen) nur abseits des Main-Threads aufrufen.
 */
class ConnectedSourceStore(private val context: Context, private val store: KeyValueStore) {
    private val resolver: ContentResolver = context.contentResolver
    private val _sources = MutableStateFlow<List<ConnectedSource>>(emptyList())
    val sources: StateFlow<List<ConnectedSource>> = _sources.asStateFlow()
    private val _problems = MutableStateFlow<Map<ConnectedSourceID, SourceProblem>>(emptyMap())
    val problems: StateFlow<Map<ConnectedSourceID, SourceProblem>> = _problems.asStateFlow()

    companion object {
        private const val KEY = "sources.connected"

        /** Cloud-Anbieter, deren Offline-Verhalten nicht belegt ist (iOS: iCloud Drive abgelehnt). */
        private val CLOUD_AUTHORITIES = setOf(
            "com.google.android.apps.docs.storage", "com.google.android.apps.docs.storage.legacy",
            "com.microsoft.skydrive.content.StorageAccessProvider", "com.dropbox.android.FileCache",
            "com.box.android.documents", "org.nextcloud.documents", "com.owncloud.android.providers",
        )
    }

    /** Muss vor dem ersten Zugriff einmal (abseits des Main-Threads) geladen werden. */
    fun load() { _sources.value = store.value<List<ConnectedSource>>(KEY) ?: emptyList() }

    val downloadsSource: ConnectedSource? get() = _sources.value.firstOrNull { it.isDownloads }

    private fun save(list: List<ConnectedSource>) {
        store.setValue(KEY, list)
        _sources.value = list
    }

    /** Tree-URI aus `ACTION_OPEN_DOCUMENT_TREE`. */
    fun add(treeUri: Uri, asDownloads: Boolean) {
        if (treeUri.authority in CLOUD_AUTHORITIES) throw SourceException(SourceProblem.CLOUD_PROVIDER_NOT_SUPPORTED)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(treeUri, flags) }
            .recoverCatching { resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .getOrElse { throw SourceException(SourceProblem.ACCESS_DENIED) }
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        var name = docId.substringAfterLast(':').substringAfterLast('/').ifEmpty { docId }
        var isDir = false
        var writable = false
        resolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                isDir = c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR
                writable = hasWritePermission(treeUri)
            }
        }
        if (!isDir) throw SourceException(SourceProblem.NOT_A_DIRECTORY)
        // Nur Ordner, die tatsächlich „Download(s)“ heißen, erhalten den Titel „Downloads“.
        val looksLikeDownloads = name.equals("Download", true) || name.equals("Downloads", true)
        var list = _sources.value.filter { it.treeUri != treeUri.toString() }
        val markDownloads = asDownloads && looksLikeDownloads && list.none { it.isDownloads }
        list = list + ConnectedSource(ConnectedSourceID(), name, treeUri.toString(), Instant.now(), writable, markDownloads)
        save(list)
    }

    private fun hasWritePermission(tree: Uri) =
        resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /** Entfernt nur die Verbindung (und gibt die Berechtigung frei). Keine Datei wird gelöscht. */
    fun remove(id: ConnectedSourceID) {
        val s = _sources.value.firstOrNull { it.id == id } ?: return
        runCatching {
            resolver.releasePersistableUriPermission(Uri.parse(s.treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (s.isWritable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
        }
        save(_sources.value.filter { it.id != id })
        _problems.value = _problems.value - id
    }

    /** Zugriff prüfen (iOS `beginAccess`): dauerhafte Berechtigung noch vorhanden? */
    fun rootIfAccessible(id: ConnectedSourceID): Uri? {
        val s = _sources.value.firstOrNull { it.id == id } ?: return null
        val tree = Uri.parse(s.treeUri)
        val held = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        if (!held) { _problems.value = _problems.value + (id to SourceProblem.ACCESS_DENIED); return null }
        val writable = hasWritePermission(tree)
        if (writable != s.isWritable) save(_sources.value.map { if (it.id == id) it.copy(isWritable = writable) else it })
        _problems.value = _problems.value - id
        return tree
    }

    /**
     * Inventarisiert eine Quelle (rekursiv, ohne versteckte Einträge, höchstens [limit] Dateien).
     * Wirft [SourceException] bei nicht erreichbarer Quelle.
     */
    fun scan(id: ConnectedSourceID, limit: Int = 20_000, isCancelled: () -> Boolean = { false }): List<FileEntry> {
        val tree = rootIfAccessible(id) ?: throw SourceException(SourceProblem.ACCESS_DENIED)
        val result = ArrayList<FileEntry>()
        val queue = ArrayDeque<Pair<String, String>>() // documentId, relativer Pfad
        queue.add(DocumentsContract.getTreeDocumentId(tree) to "")
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS,
        )
        var reachedRoot = false
        while (queue.isNotEmpty() && result.size < limit && !isCancelled()) {
            val (parent, prefix) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            val cursor = runCatching { resolver.query(children, cols, null, null, null) }.getOrNull()
            if (cursor == null) {
                if (!reachedRoot) throw SourceException(SourceProblem.UNAVAILABLE)
                continue
            }
            reachedRoot = true
            cursor.use { c ->
                while (c.moveToNext() && result.size < limit) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue // versteckte Dateien überspringen
                    val mime = c.getString(2)
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { queue.add(docId to path); continue }
                    val flags = if (c.isNull(5)) 0 else c.getInt(5)
                    if (flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0) continue // keine echten Bytes
                    val size = if (c.isNull(3)) KnownSize.Unknown else KnownSize.Known(c.getLong(3))
                    val modified = if (c.isNull(4)) null else c.getLong(4).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    result += FileEntry(
                        reference = ItemReference(ItemProvider.ConnectedSource(id), docId), sourceID = id,
                        relativePath = path, documentUri = docUri.toString(), name = name, size = size,
                        modified = modified, mimeType = mime,
                        canDelete = flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0,
                        aliasKey = "${tree.authority}|$docId",
                    )
                }
            }
        }
        return result
    }

    /** Dokument-URI eines Items (für Vorschau, Löschen, Hashing). */
    fun documentUri(item: ItemReference): Uri? {
        val p = item.provider as? ItemProvider.ConnectedSource ?: return null
        val s = _sources.value.firstOrNull { it.id == p.id } ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(s.treeUri), item.localIdentifier)
    }

    /** Anzeigename eines Dateieintrags (Dokument-ID ist bei manchen Anbietern undurchsichtig). */
    fun displayName(item: ItemReference): String {
        val fallback = item.localIdentifier.substringAfterLast('/').substringAfterLast(':')
        val uri = documentUri(item) ?: return fallback
        return runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: fallback
    }

    fun openStream(item: ItemReference): InputStream? =
        documentUri(item)?.let { runCatching { resolver.openInputStream(it) }.getOrNull() }

    /**
     * Löscht einzelne Dateien endgültig (`DocumentsContract.deleteDocument`). Kein rekursives Ordnerlöschen.
     * Ergebnis belegt pro Datei, was tatsächlich entfernt wurde (anschließende Existenzprüfung).
     */
    fun deletePermanently(items: List<ItemReference>): OperationOutcome {
        val removed = mutableSetOf<ItemReference>()
        for (item in items) {
            val p = item.provider as? ItemProvider.ConnectedSource ?: continue
            if (rootIfAccessible(p.id) == null) continue
            val uri = documentUri(item) ?: continue
            val isFile = runCatching {
                resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                    c.moveToFirst() && c.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR
                }
            }.getOrNull() == true
            if (!isFile) continue
            val ok = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
            if (ok && probeOne(p.id, uri) == ExistenceProbe.MISSING_WITH_FULL_ACCESS) removed += item
        }
        if (removed.isEmpty()) return OperationOutcome.Failed("nothing-removed")
        return if (removed.size == items.size) OperationOutcome.Succeeded else OperationOutcome.Partial(removed)
    }

    private fun probeOne(id: ConnectedSourceID, uri: Uri): ExistenceProbe {
        if (rootIfAccessible(id) == null) return ExistenceProbe.UNKNOWN
        return try {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null).use { c ->
                if (c != null && c.moveToFirst()) ExistenceProbe.EXISTS else ExistenceProbe.MISSING_WITH_FULL_ACCESS
            }
        } catch (_: java.io.FileNotFoundException) {
            ExistenceProbe.MISSING_WITH_FULL_ACCESS
        } catch (_: IllegalArgumentException) {
            ExistenceProbe.MISSING_WITH_FULL_ACCESS // Anbieter melden fehlende Dokumente teils so
        } catch (_: Exception) {
            ExistenceProbe.UNKNOWN
        }
    }

    /** Existenzprüfung für den Abgleich. Ohne Quellzugriff: unbekannt (kein Löschbeweis). */
    fun probe(items: List<ItemReference>): Map<ItemReference, ExistenceProbe> = items.associateWith { item ->
        val p = item.provider as? ItemProvider.ConnectedSource
        val uri = documentUri(item)
        if (p == null || uri == null) ExistenceProbe.UNKNOWN else probeOne(p.id, uri)
    }

    /** Byte-Quelle für den Duplikat-Scan. */
    fun byteProvider() = ByteSourceProvider { item -> openStream(item) }
}
