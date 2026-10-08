package app.photomaid.archive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.photomaid.AppModel
import app.photomaid.R
import app.photomaid.core.archive.ArchiveJournal
import app.photomaid.core.archive.ArchiveJournalRules
import app.photomaid.core.archive.ArchiveList
import app.photomaid.core.archive.ArchiveOptions
import app.photomaid.core.archive.ArchivePlan
import app.photomaid.core.archive.ArchiveSource
import app.photomaid.core.archive.ArchiveTargets
import app.photomaid.core.archive.EntryReason
import app.photomaid.core.archive.EntryState
import app.photomaid.core.archive.JournalEntry
import app.photomaid.core.archive.TargetKind
import app.photomaid.core.archive.toHex
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.model.ItemReference
import app.photomaid.data.KeyValueStore
import app.photomaid.media.MediaAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Eine Datei am Ziel. */
interface ArchiveFile {
    val uri: String
    /** Tatsächlicher Name am Ziel (Anbieter dürfen ihn beim Anlegen anpassen). */
    val name: String
    /** Gemeldete Größe, `null` = unbekannt. */
    val size: Long?
    fun openInput(): InputStream
    fun openOutput(): OutputStream
    fun delete(): Boolean
}

/** Ein Ordner am Ziel. */
interface ArchiveFolder {
    val path: List<String>
    fun names(): Set<String>
    fun find(name: String): ArchiveFile?
    fun subfolder(name: String): ArchiveFolder
    fun create(name: String, mime: String): ArchiveFile
}

/** Vom Nutzer gewählter Zielordner. */
interface ArchiveTarget {
    val id: String
    val label: String
    val kind: TargetKind
    /** Freier Platz laut Speicher, `null` = unbekannt. */
    fun availableBytes(): Long?
    fun root(): ArchiveFolder
    /** Entfernt eine eigene, unvollständige Kopie (nach Abbruch). */
    fun delete(uri: String): Boolean
}

/** Zielordner über das Android-Dateisystem (Storage Access Framework, `ACTION_OPEN_DOCUMENT_TREE`). */
class SafArchiveTarget(context: Context, private val tree: Uri) : ArchiveTarget {
    private val resolver = context.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(tree)
    override val id: String = tree.toString()
    override val kind: TargetKind = ArchiveTargets.classify(tree.authority, rootDocId)

    private data class RootInfo(val title: String?, val available: Long?)

    private val rootInfo: RootInfo by lazy {
        runCatching {
            resolver.query(DocumentsContract.buildRootsUri(tree.authority), arrayOf(DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES), null, null, null)?.use { c ->
                var best: RootInfo? = null; var bestLen = -1
                while (c.moveToNext()) {
                    val doc = c.getString(0) ?: continue
                    if (rootDocId.startsWith(doc) && doc.length > bestLen) {
                        best = RootInfo(c.getString(1), if (c.isNull(2)) null else c.getLong(2)); bestLen = doc.length
                    }
                }
                best
            }
        }.getOrNull() ?: RootInfo(null, null)
    }

    override val label: String by lazy {
        val folder = runCatching {
            resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootDocId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val path = ArchiveTargets.displayPath(rootDocId)
        listOfNotNull(rootInfo.title, path.ifEmpty { folder }?.takeIf { it != rootInfo.title }).joinToString(" › ").ifEmpty { folder ?: rootDocId }
    }

    override fun availableBytes(): Long? = rootInfo.available

    override fun root(): ArchiveFolder = SafFolder(rootDocId, emptyList())

    override fun delete(uri: String): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(uri)) }.getOrDefault(false)

    private data class Child(val docId: String, val name: String, val mime: String?, val size: Long?)

    private inner class SafFile(override val uri: String, override val name: String, override val size: Long?) : ArchiveFile {
        override fun openInput(): InputStream = resolver.openInputStream(Uri.parse(uri)) ?: throw IOException("no-input")
        override fun openOutput(): OutputStream = resolver.openOutputStream(Uri.parse(uri), "w") ?: throw IOException("no-output")
        override fun delete(): Boolean = this@SafArchiveTarget.delete(uri)
    }

    private inner class SafFolder(private val docId: String, override val path: List<String>) : ArchiveFolder {
        private var cache: MutableMap<String, Child>? = null

        private fun children(): MutableMap<String, Child> = cache ?: HashMap<String, Child>().also { map ->
            resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE),
                null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue; val n = c.getString(1) ?: continue
                    map[n.lowercase()] = Child(id, n, c.getString(2), if (c.isNull(3)) null else c.getLong(3))
                }
            } ?: throw IOException("unavailable")
            cache = map
        }

        override fun names(): Set<String> = children().values.mapTo(HashSet()) { it.name }

        override fun find(name: String): ArchiveFile? = children()[name.lowercase()]?.takeIf { it.mime != DocumentsContract.Document.MIME_TYPE_DIR }
            ?.let { SafFile(DocumentsContract.buildDocumentUriUsingTree(tree, it.docId).toString(), it.name, it.size) }

        override fun subfolder(name: String): ArchiveFolder {
            val existing = children()[name.lowercase()]
            if (existing != null && existing.mime == DocumentsContract.Document.MIME_TYPE_DIR) return SafFolder(existing.docId, path + existing.name)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val created = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name) ?: throw IOException("mkdir")
            val id = DocumentsContract.getDocumentId(created)
            children()[name.lowercase()] = Child(id, name, DocumentsContract.Document.MIME_TYPE_DIR, null)
            return SafFolder(id, path + name)
        }

        override fun create(name: String, mime: String): ArchiveFile {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val created = DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw IOException("create")
            val actual = runCatching {
                resolver.query(created, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: name
            children()[actual.lowercase()] = Child(DocumentsContract.getDocumentId(created), actual, mime, null)
            return SafFile(created.toString(), actual, null)
        }
    }
}

/** Zielordner als einfaches Verzeichnis (Tests, QA). */
class FileArchiveTarget(private val dir: File, override val kind: TargetKind, override val label: String = dir.name,
                        private val available: Long? = null) : ArchiveTarget {
    override val id: String = dir.toURI().toString()
    override fun availableBytes(): Long? = available
    override fun root(): ArchiveFolder = Folder(dir.apply { mkdirs() }, emptyList())
    override fun delete(uri: String): Boolean = File(Uri.parse(uri).path ?: return false).delete()

    /** Tests: Bytes beim Schreiben verfälschen (Prüfung muss die Abweichung finden). */
    var corruptWrites: Boolean = false
    /** Tests: Ziel ist nach so vielen Bytes insgesamt voll. */
    var failAfterBytes: Long? = null
    /** Tests: vor jedem Schreibblock (Dateiname), z. B. um mitten im Kopieren abzubrechen. */
    var beforeWrite: ((String) -> Unit)? = null
    private var totalWritten = 0L

    private inner class F(val file: File) : ArchiveFile {
        override val uri: String get() = Uri.fromFile(file).toString()
        override val name: String get() = file.name
        override val size: Long? get() = if (file.exists()) file.length() else null
        override fun openInput(): InputStream = file.inputStream()
        override fun openOutput(): OutputStream {
            val out = file.outputStream()
            return object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    beforeWrite?.invoke(file.name)
                    failAfterBytes?.let { if (totalWritten + len > it) throw IOException("ENOSPC (No space left on device)") }
                    if (corruptWrites && len > 0) { val copy = b.copyOfRange(off, off + len); copy[0] = (copy[0] + 1).toByte(); out.write(copy) } else out.write(b, off, len)
                    totalWritten += len
                }
                override fun flush() = out.flush()
                override fun close() = out.close()
            }
        }
        override fun delete(): Boolean = file.delete()
    }

    private inner class Folder(val dir: File, override val path: List<String>) : ArchiveFolder {
        override fun names(): Set<String> = dir.list()?.toSet().orEmpty()
        override fun find(name: String): ArchiveFile? = dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, true) }?.let { F(it) }
        override fun subfolder(name: String): ArchiveFolder =
            Folder((dir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(name, true) } ?: File(dir, name)).apply { mkdirs() }, path + name)
        override fun create(name: String, mime: String): ArchiveFile = F(File(dir, name).apply { createNewFile() })
    }
}

/** Letzter Durchgang und zuletzt gewähltes Ziel (nur die Ordner-Freigabe, keine Medien). */
class ArchiveCenter(private val context: Context, private val store: KeyValueStore, private val now: () -> Long = System::currentTimeMillis) {
    private val _last = MutableStateFlow<ArchiveJournal?>(null)
    val last: StateFlow<ArchiveJournal?> = _last.asStateFlow()
    /** Tests/QA: festes Ziel statt der Ordnerauswahl. */
    var testTarget: ArchiveTarget? = null

    companion object { private const val KEY = "archive.journal.v1"; private const val TARGET_KEY = "archive.target.v1" }

    fun load() { _last.value = runCatching { store.value<ArchiveJournal>(KEY) }.getOrNull() }

    fun save(j: ArchiveJournal) { runCatching { store.setValue(KEY, j) }; _last.value = j }

    /**
     * Nach einem Neustart der App: Ein unfertiger Durchgang gilt nur mit seinen geprüften Kopien. Die halbe Kopie der
     * zuletzt bearbeiteten Datei wird entfernt (nur eigene, gerade angelegte Dateien – nie etwas, das schon da war).
     */
    fun recoverAfterRestart() {
        val j = _last.value ?: return
        if (j.finished) return
        val (closed, remove) = ArchiveJournalRules.close(j, EntryReason.INTERRUPTED, now())
        for (u in remove) runCatching {
            if (Uri.parse(u).scheme == "file") File(Uri.parse(u).path!!).delete() else DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(u))
        }
        save(closed)
    }

    /** Zuletzt benutztes Ziel, wenn die Freigabe noch besteht. */
    fun lastTarget(): ArchiveTarget? {
        testTarget?.let { return it }
        val raw = runCatching { store.raw(TARGET_KEY) }.getOrNull() ?: return null
        val uri = Uri.parse(raw)
        val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return if (held) runCatching { SafArchiveTarget(context, uri) }.getOrNull() else null
    }

    /** Neu gewählter Ordner: Schreibrecht dauerhaft halten (der Durchgang läuft auch nach Verlassen der Seite weiter). */
    fun choose(tree: Uri): ArchiveTarget? {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(tree, flags) }
        runCatching { store.putRaw(TARGET_KEY, tree.toString()) }
        return runCatching { SafArchiveTarget(context, tree) }.getOrNull()
    }
}

/**
 * Kopieren und prüfen: Original lesen (Prüfsumme beim Lesen), am Ziel schreiben, die Kopie danach NEU lesen und vergleichen.
 * Nur übereinstimmende Kopien gelten als geprüft; eine abweichende Kopie wird sofort wieder entfernt. Originale werden nie
 * verändert. Das Journal wird nach jedem Schritt gespeichert, damit nach einem Abbruch klar ist, was geprüft ist.
 */
object ArchiveRunner {
    private const val BUFFER = 256 * 1024

    private class ReadFailed(cause: Exception) : IOException(cause)

    suspend fun run(
        app: AppModel, target: ArchiveTarget, assets: List<MediaAsset>, options: ArchiveOptions,
        onProgress: (Float) -> Unit = {}, now: () -> Long = System::currentTimeMillis, zone: ZoneId = ZoneId.systemDefault(),
    ): ArchiveJournal = withContext(Dispatchers.IO) {
        val center = app.archive
        var j = ArchiveJournal(UUID.randomUUID().toString(), target.label, target.kind, now(), options = options,
            entries = assets.map { JournalEntry(it.id, it.displayName.orEmpty(), size = it.size.bytesOrNull) })
        center.save(j)
        val undated = app.string(R.string.archive_undated)
        val total = assets.sumOf { it.size.bytesOrNull ?: 0L }
        val bytesKnown = total > 0 && assets.all { it.size.bytesOrNull != null }
        var doneBytes = 0L
        try {
            val root = target.root()
            val folders = HashMap<List<String>, ArchiveFolder>()
            for ((i, a) in assets.withIndex()) {
                coroutineContext.ensureActive()
                val src = ArchiveSource(a.id, a.displayName, a.mimeType, a.size.bytesOrNull, a.creationDate?.toEpochMilli())
                val path = ArchivePlan.folderFor(src, options, undated, zone)
                val wanted = ArchivePlan.safeName(a.displayName, ArchivePlan.fallbackName(i, a.mimeType))
                val entry = try {
                    val folder = folders.getOrPut(path) { path.fold(root) { f, seg -> f.subfolder(seg) } }
                    copyOne(app, a, folder, wanted) { uri, name -> j = j.update(a.id) { it.copy(state = EntryState.COPYING, targetUri = uri, name = name, folder = path) }; center.save(j) }
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    JournalEntry(a.id, wanted, path, a.size.bytesOrNull, EntryState.FAILED, if (noSpace(e)) EntryReason.NO_SPACE else EntryReason.WRITE_FAILED)
                }
                j = j.update(a.id) { entry.copy(size = it.size) }
                center.save(j)
                doneBytes += a.size.bytesOrNull ?: 0L
                onProgress(if (bytesKnown) doneBytes.toFloat() / total else (i + 1).toFloat() / assets.size)
                if (entry.reason == EntryReason.NO_SPACE) {
                    // Ziel voll: der Rest würde ebenso scheitern – sauber als „nicht kopiert“ beenden.
                    j = j.copy(entries = j.entries.map { if (it.state == EntryState.PENDING) it.copy(state = EntryState.NOT_DONE, reason = EntryReason.NO_SPACE) else it })
                    break
                }
            }
        } catch (e: CancellationException) {
            val (closed, remove) = ArchiveJournalRules.close(j, EntryReason.CANCELLED, now())
            remove.forEach { runCatching { target.delete(it) } }
            center.save(closed)
            throw e
        }
        if (options.writeList && j.verified.isNotEmpty()) j = j.copy(listName = runCatching { writeList(app, target, j, zone) }.getOrNull())
        j = j.copy(finishedAt = now())
        center.save(j)
        j
    }

    private fun noSpace(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) { val m = t.message.orEmpty(); if (m.contains("ENOSPC") || m.contains("No space", true)) return true; t = t.cause }
        return false
    }

    private fun hash(input: InputStream): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(BUFFER); var n = 0L
        input.use { while (true) { val r = it.read(buf); if (r < 0) break; md.update(buf, 0, r); n += r } }
        return md.digest().toHex() to n
    }

    private suspend fun copyOne(app: AppModel, a: MediaAsset, folder: ArchiveFolder, wanted: String, onCreated: (String, String) -> Unit): JournalEntry {
        val base = JournalEntry(a.id, wanted, folder.path, a.size.bytesOrNull)
        // Gleicher Name, gleiche Größe schon da (z. B. zweiter Durchgang): nur zählen, wenn die Bytes wirklich gleich sind.
        val existing = folder.find(wanted)
        if (existing != null && existing.size != null && existing.size == a.size.bytesOrNull) {
            val src = runCatching { app.allPhotos.openStream(a.id)?.let(::hash) }.getOrNull()
                ?: return base.copy(state = EntryState.FAILED, reason = EntryReason.READ_FAILED)
            val dst = runCatching { hash(existing.openInput()) }.getOrNull()
            if (dst != null && dst == src) return base.copy(name = existing.name, state = EntryState.ALREADY_THERE, bytes = dst.second, sha256 = dst.first)
        }
        val input = runCatching { app.allPhotos.openStream(a.id) }.getOrNull() ?: return base.copy(state = EntryState.FAILED, reason = EntryReason.READ_FAILED)
        val name = ArchivePlan.uniqueName(folder.names(), wanted)
        val file = try { folder.create(name, a.mimeType ?: "application/octet-stream") } catch (e: Exception) {
            input.close(); return base.copy(name = name, state = EntryState.FAILED, reason = if (noSpace(e)) EntryReason.NO_SPACE else EntryReason.WRITE_FAILED)
        }
        onCreated(file.uri, file.name)
        val md = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            input.use { inp ->
                file.openOutput().use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val r = try { inp.read(buf) } catch (e: IOException) { throw ReadFailed(e) }
                        if (r < 0) break
                        md.update(buf, 0, r); count += r
                        out.write(buf, 0, r)
                    }
                    out.flush()
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: ReadFailed) {
            file.delete(); return base.copy(name = file.name, state = EntryState.FAILED, reason = EntryReason.READ_FAILED)
        } catch (e: Exception) {
            file.delete(); return base.copy(name = file.name, state = EntryState.FAILED, reason = if (noSpace(e)) EntryReason.NO_SPACE else EntryReason.WRITE_FAILED)
        }
        val sha = md.digest().toHex()
        // Kopie neu lesen und vergleichen – erst dann gilt sie.
        val check = runCatching { hash(file.openInput()) }.getOrNull()
        if (check == null || check.first != sha || check.second != count) {
            file.delete(); return base.copy(name = file.name, state = EntryState.FAILED, reason = EntryReason.MISMATCH)
        }
        return base.copy(name = file.name, state = EntryState.VERIFIED, bytes = count, sha256 = sha, targetUri = file.uri)
    }

    /** Prüfliste (CSV) im Zielordner: Ordner, Name, Aufnahme, Bytes, SHA-256 und eigene Infos (Alben, Schlagwörter, Notiz). */
    private fun writeList(app: AppModel, target: ArchiveTarget, j: ArchiveJournal, zone: ZoneId): String {
        val org = app.organize.state.value
        val assets = app.allPhotos.assets(j.verified.map { it.sourceId }).associateBy { it.id }
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        val rows = j.verified.map { e ->
            val a = assets[e.sourceId]
            ArchiveList.Row(e.folder.joinToString("/"), e.name, a?.creationDate?.let(fmt::format).orEmpty(), e.bytes?.toString().orEmpty(), e.sha256.orEmpty(),
                org.albumsOf(e.sourceId).joinToString("; ") { it.name }, org.tags[e.sourceId].orEmpty().joinToString("; "), org.notes[e.sourceId].orEmpty())
        }
        val header = listOf(R.string.archive_col_folder, R.string.archive_col_name, R.string.archive_col_taken, R.string.archive_col_bytes,
            R.string.archive_col_sha, R.string.archive_col_albums, R.string.archive_col_tags, R.string.archive_col_note).map { app.string(it) }
        val root = target.root()
        val day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone).format(Instant.ofEpochMilli(j.startedAt))
        val name = ArchivePlan.uniqueName(root.names(), ArchivePlan.safeName(app.string(R.string.archive_list_file, day), "PhotoMaid.csv"))
        val f = root.create(name, "text/csv")
        f.openOutput().use { it.write(ArchiveList.render(header, rows).toByteArray(Charsets.UTF_8)) }
        return f.name
    }
}

/**
 * Nach dem Auslagern, nur auf ausdrücklichen Wunsch: geprüfte Originale in den PhotoMaid-Papierkorb legen (eine Entscheidung je
 * Medium über das Ledger). Gelöscht wird dadurch nichts; Favoriten bleiben, wenn „Favoriten schützen“ an ist.
 */
object ArchiveCleanup {
    data class Candidates(val ids: List<String>, val protectedFavorites: Int)

    fun candidates(app: AppModel, j: ArchiveJournal): Candidates {
        if (!j.targetKind.allowsCleanupOffer) return Candidates(emptyList(), 0)
        val assets = app.allPhotos.assets(j.verified.map { it.sourceId })
        val protect = app.settings.value.protectFavorites
        val (fav, rest) = assets.partition { protect && it.isFavorite }
        return Candidates(rest.map { it.id }, fav.size)
    }

    suspend fun markForCleanup(app: AppModel, ids: List<String>): Int {
        var n = 0
        for (id in ids) {
            val item = ItemReference.photo(id)
            runCatching {
                val r = app.ledger.record(item)
                if (r.disposition != Disposition.MARKED_FOR_CLEANUP) {
                    app.apply(DecisionCommand(item = item, expectedRevision = r.revision, action = DecisionAction.MarkForCleanup)); n++
                }
            }
        }
        return n
    }
}
