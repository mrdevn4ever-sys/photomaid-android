package app.photomaid.media

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.photomaid.core.cleanup.ExistenceProbe
import app.photomaid.core.cleanup.OperationOutcome
import app.photomaid.system.SystemDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.MediaKind
import app.photomaid.core.organize.MediaFolder
import app.photomaid.core.organize.MediaFolders
import android.content.ContentValues
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Tatsächlicher Systemzustand der Medienberechtigung.
 * - [AUTHORIZED]: alle Fotos und Videos (iOS `.authorized`).
 * - [LIMITED]: Android 14+ „Ausgewählte Fotos“ (`READ_MEDIA_VISUAL_USER_SELECTED`, iOS `.limited`).
 * - [DENIED]: abgelehnt; ggf. nur noch über die Systemeinstellungen änderbar.
 * - [NOT_DETERMINED]: noch nie angefragt.
 * Android kennt kein „restricted“ wie iOS (Bildschirmzeit).
 */
enum class PhotoAccess {
    NOT_DETERMINED, AUTHORIZED, LIMITED, DENIED;

    val canRead: Boolean get() = this == AUTHORIZED || this == LIMITED
}

enum class MediaCategory { PHOTOS, VIDEOS, SCREENSHOTS }

/** Leichtgewichtige Beschreibung eines MediaStore-Eintrags. */
data class MediaAsset(
    /** Content-URI (stabile Identität). */
    val id: String,
    val kind: MediaKind,
    val creationDate: Instant?,
    /** Sekunden. */
    val duration: Double,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val size: KnownSize,
    val mimeType: String?,
    val displayName: String?,
    val isFavorite: Boolean,
    /** MediaStore `RELATIVE_PATH` (z. B. `DCIM/Camera/`), `null` wenn unbekannt. */
    val relativePath: String? = null,
    /** MediaStore `DATE_ADDED`: wann das Medium auf dieses Gerät kam (≠ Aufnahmedatum). */
    val dateAdded: Instant? = null,
) {
    val reference: ItemReference get() = ItemReference.photo(id)
    val uri: Uri get() = Uri.parse(id)
    val aspectRatio: Float get() = if (pixelHeight > 0 && pixelWidth > 0) pixelWidth.toFloat() / pixelHeight else 1f
}

/** Laufzeitberechtigungen je Android-Version (keine veraltete READ_EXTERNAL_STORAGE-only-Logik). */
object MediaPermissions {
    fun requestable(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun current(ctx: Context, everAsked: Boolean): PhotoAccess {
        val full = if (Build.VERSION.SDK_INT >= 33) {
            granted(ctx, Manifest.permission.READ_MEDIA_IMAGES) && granted(ctx, Manifest.permission.READ_MEDIA_VIDEO)
        } else granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
        if (full) return PhotoAccess.AUTHORIZED
        if (Build.VERSION.SDK_INT >= 34 && granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) return PhotoAccess.LIMITED
        // Nur Fotos ODER nur Videos freigegeben: lesbar, aber unvollständig (nicht „kein Zugriff“).
        if (Build.VERSION.SDK_INT >= 33 &&
            (granted(ctx, Manifest.permission.READ_MEDIA_IMAGES) || granted(ctx, Manifest.permission.READ_MEDIA_VIDEO))) return PhotoAccess.LIMITED
        return if (everAsked) PhotoAccess.DENIED else PhotoAccess.NOT_DETERMINED
    }
}

/** Ergebnis eines Schreibwunschs an die Mediathek (Favorit, Verschieben). Erfolg nur nach erneutem Lesen. */
sealed interface MediaWriteResult {
    data object Done : MediaWriteResult
    /** Nutzer hat die Systembestätigung abgelehnt. */
    data object Cancelled : MediaWriteResult
    /** Technisch gescheitert oder Ergebnis nicht belegbar (Grund nur fürs Log, nie im UI). */
    data class Failed(val reason: String) : MediaWriteResult
}

/**
 * Alle prüfbaren Medien der Mediathek (Fotos inkl. Screenshots + Videos), neueste zuerst.
 * Einzige Quelle für die Zahl auf „Heute“ UND den gemeinsamen Feed „Prüfung starten“ – beide zählen dasselbe.
 */
fun MediaLibrary.reviewableMedia(): List<MediaAsset> =
    (assets(MediaCategory.PHOTOS) + assets(MediaCategory.VIDEOS)).distinctBy { it.id }
        .sortedWith(compareByDescending<MediaAsset> { it.creationDate }.thenBy { it.id })

interface MediaLibrary {
    val access: StateFlow<PhotoAccess>
    val changeToken: StateFlow<Int>
    fun refreshAccess()
    fun bumpChangeToken()
    fun count(category: MediaCategory): Int
    fun assets(category: MediaCategory, limit: Int? = null): List<MediaAsset>
    fun assets(ids: List<String>): List<MediaAsset>
    fun probe(ids: List<String>): Map<ItemReference, ExistenceProbe>
    fun openStream(id: String): java.io.InputStream?

    /**
     * In den System-Papierkorb verschieben (mit Systembestätigung) und Ergebnis je Item belegen.
     * `null` = Ergebnis nicht belegbar → Abgleich beim nächsten Start.
     */
    suspend fun trash(ids: List<String>): OperationOutcome?

    /**
     * Systemfavorit setzen/entfernen (`MediaStore.createFavoriteRequest`, Android zeigt eine Bestätigung).
     * `Done` nur, wenn der danach gelesene Wert stimmt.
     */
    suspend fun setFavorite(id: String, favorite: Boolean): MediaWriteResult

    /** Ordner (RELATIVE_PATH) mit Anzahl sichtbarer Medien. */
    fun folders(): List<MediaFolder>

    /**
     * Medium in einen Ordner verschieben (`createWriteRequest` + `RELATIVE_PATH`). Keine Kopie, Content-URI bleibt.
     * `Done` nur, wenn der danach gelesene Pfad stimmt.
     */
    suspend fun moveToFolder(id: String, relativePath: String): MediaWriteResult

    /**
     * Mehrere Medien mit EINER Systembestätigung verschieben. Ergebnis je Medium, `Done` nur nach bestätigtem Pfad.
     * Standard (z. B. Testattrappen): nacheinander.
     */
    suspend fun moveManyToFolder(ids: List<String>, relativePath: String): Map<String, MediaWriteResult> =
        ids.associateWith { moveToFolder(it, relativePath) }

    /** Mehrere Medien mit EINER Systembestätigung als Favorit markieren/entmarkieren; Ergebnis je Medium nach erneutem Lesen. */
    suspend fun setFavoriteMany(ids: List<String>, favorite: Boolean): Map<String, MediaWriteResult> =
        ids.associateWith { setFavorite(it, favorite) }
}

/**
 * Einziger Zugang zu MediaStore (iOS `PhotoLibraryService`). Nur lokale Medien; keine Netzwerkzugriffe.
 * Liest ausschließlich öffentliche Spalten; entzogene Berechtigung führt zu leeren Ergebnissen, nie zum Absturz.
 */
class PhotoLibraryService(private val context: Context, private val everAsked: () -> Boolean) : MediaLibrary {
    private val resolver: ContentResolver = context.contentResolver
    private val _access = MutableStateFlow(MediaPermissions.current(context, everAsked()))
    override val access: StateFlow<PhotoAccess> = _access.asStateFlow()

    /** Erhöht sich bei jeder Mediatheksänderung (Screens laden daraufhin neu). */
    private val _changeToken = MutableStateFlow(0)
    override val changeToken: StateFlow<Int> = _changeToken.asStateFlow()
    private var observer: ContentObserver? = null

    override fun refreshAccess() {
        val new = MediaPermissions.current(context, everAsked())
        if (new != _access.value) {
            _access.value = new
            _changeToken.value += 1
        }
        startObservingIfAllowed()
    }

    override fun bumpChangeToken() { _changeToken.value += 1 }

    private fun startObservingIfAllowed() {
        if (!_access.value.canRead || observer != null) return
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { _changeToken.value += 1 }
        }
        runCatching {
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, o)
            observer = o
        }
    }

    private fun collection(video: Boolean): Uri =
        if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /**
     * Screenshots: MediaStore hat kein Screenshot-Attribut. Android legt sie in `…/Screenshots/` ab
     * (Pictures/Screenshots bzw. DCIM/Screenshots) – dieser Ordner ist das belastbare Kriterium.
     */
    private val screenshotSelection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
    private val screenshotArgs = arrayOf("%Screenshots%")

    private val baseColumns = arrayOf(
        MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.IS_FAVORITE, MediaStore.MediaColumns.ORIENTATION,
    )

    private fun query(category: MediaCategory, limit: Int? = null): List<MediaAsset> {
        if (!_access.value.canRead) return emptyList()
        val video = category == MediaCategory.VIDEOS
        val cols = if (video) baseColumns + MediaStore.Video.VideoColumns.DURATION else baseColumns
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            if (category == MediaCategory.SCREENSHOTS) {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, screenshotSelection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, screenshotArgs)
            }
            if (limit != null) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        return runCatching {
            resolver.query(collection(video), cols, args, null)?.use { c -> readAll(c, video) } ?: emptyList()
        }.getOrElse { emptyList() } // Entzug während der Laufzeit: leer statt Absturz.
    }

    private fun readAll(c: android.database.Cursor, video: Boolean): List<MediaAsset> {
        val out = ArrayList<MediaAsset>(c.count)
        val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        val iFav = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)
        val iOrient = c.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
        val iDur = if (video) c.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION) else -1
        val base = collection(video)
        while (c.moveToNext()) {
            val id = c.getLong(iId)
            val taken = if (c.isNull(iTaken)) null else c.getLong(iTaken).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
            val added = if (c.isNull(iAdded)) null else c.getLong(iAdded).takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) }
            val size = if (c.isNull(iSize)) KnownSize.Unknown else c.getLong(iSize).let { if (it > 0) KnownSize.Known(it) else KnownSize.Unknown }
            val path = c.getString(iPath)
            val rotated = !c.isNull(iOrient) && (c.getInt(iOrient) == 90 || c.getInt(iOrient) == 270)
            val w = c.getInt(iW); val h = c.getInt(iH)
            val kind = when {
                video -> MediaKind.VIDEO
                path?.contains("Screenshots", ignoreCase = true) == true -> MediaKind.SCREENSHOT
                else -> MediaKind.PHOTO
            }
            out += MediaAsset(
                id = ContentUris.withAppendedId(base, id).toString(), kind = kind, creationDate = taken ?: added,
                duration = if (iDur >= 0 && !c.isNull(iDur)) c.getLong(iDur) / 1000.0 else 0.0,
                pixelWidth = if (rotated) h else w, pixelHeight = if (rotated) w else h, size = size,
                mimeType = c.getString(iMime), displayName = c.getString(iName), isFavorite = c.getInt(iFav) == 1,
                relativePath = path, dateAdded = added,
            )
        }
        return out
    }

    override fun count(category: MediaCategory): Int {
        if (!_access.value.canRead) return 0
        val video = category == MediaCategory.VIDEOS
        val args = Bundle().apply {
            if (category == MediaCategory.SCREENSHOTS) {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, screenshotSelection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, screenshotArgs)
            }
        }
        return runCatching {
            resolver.query(collection(video), arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { it.count } ?: 0
        }.getOrElse { 0 }
    }

    override fun assets(category: MediaCategory, limit: Int?): List<MediaAsset> = query(category, limit)

    /** Einträge zu bekannten IDs, in der übergebenen Reihenfolge; nicht (mehr) vorhandene fehlen. */
    override fun assets(ids: List<String>): List<MediaAsset> {
        if (!_access.value.canRead || ids.isEmpty()) return emptyList()
        val byId = HashMap<String, MediaAsset>()
        for (video in listOf(false, true)) {
            val base = collection(video).toString()
            val nums = ids.filter { it.startsWith("$base/") }.mapNotNull { it.substringAfterLast('/').toLongOrNull() }
            for (chunk in nums.chunked(500)) {
                val sel = "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",")})"
                val cols = if (video) baseColumns + MediaStore.Video.VideoColumns.DURATION else baseColumns
                runCatching {
                    resolver.query(collection(video), cols, Bundle().apply { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel) }, null)
                        ?.use { c -> readAll(c, video).forEach { byId[it.id] = it } }
                }
            }
        }
        return ids.mapNotNull { byId[it] }
    }

    /**
     * Existenzprüfung für den Abgleich. Fehlender Zugriff ist kein Löschbeweis.
     * - Zeile vorhanden, nicht im Papierkorb → EXISTS
     * - Zeile im System-Papierkorb (`IS_TRASHED = 1`) → belegt entfernt
     * - Zeile fehlt: nur bei vollem Zugriff belastbar, sonst UNKNOWN
     */
    override fun probe(ids: List<String>): Map<ItemReference, ExistenceProbe> {
        val access = _access.value
        val out = ids.associate { ItemReference.photo(it) to ExistenceProbe.UNKNOWN }.toMutableMap()
        if (!access.canRead) return out
        for (id in ids) {
            val uri = Uri.parse(id)
            val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
            val result = runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED), args, null)?.use { c ->
                    if (!c.moveToFirst()) null else c.getInt(1) == 1
                }
            }
            out[ItemReference.photo(id)] = when {
                result.isFailure -> ExistenceProbe.UNKNOWN
                result.getOrNull() == false -> ExistenceProbe.EXISTS
                result.getOrNull() == true -> ExistenceProbe.MISSING_WITH_FULL_ACCESS
                access == PhotoAccess.AUTHORIZED -> ExistenceProbe.MISSING_WITH_FULL_ACCESS
                else -> ExistenceProbe.UNKNOWN
            }
        }
        return out
    }

    /**
     * Offizielle Papierkorb-Anfrage (Android 11+, `MediaStore.createTrashRequest`). Das System zeigt seine
     * eigene Bestätigung. Ergebnis wird nie nur aus dem Dialog-Code abgeleitet: nach „OK“ wird jedes Item geprüft.
     */
    override suspend fun trash(ids: List<String>): OperationOutcome? {
        val request = runCatching { MediaStore.createTrashRequest(resolver, ids.map { Uri.parse(it) }, true) }
            .getOrElse { return OperationOutcome.Failed("request:${it.javaClass.simpleName}") }
        val code = SystemDialogs.launch(request.intentSender) ?: return OperationOutcome.Failed("no-activity")
        if (code != Activity.RESULT_OK) return OperationOutcome.Cancelled
        val probes = withContext(Dispatchers.IO) { probe(ids) }
        if (probes.values.any { it == ExistenceProbe.UNKNOWN }) return null
        val removed = probes.filterValues { it == ExistenceProbe.MISSING_WITH_FULL_ACCESS }.keys
        return if (removed.size == ids.size) OperationOutcome.Succeeded else OperationOutcome.Partial(removed)
    }

    /** Einzelne Spalte eines Eintrags frisch lesen (Verifikation nach Schreibwunsch). */
    private fun readColumn(uri: Uri, column: String): String? = runCatching {
        resolver.query(uri, arrayOf(column), null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    override suspend fun setFavorite(id: String, favorite: Boolean): MediaWriteResult {
        val uri = Uri.parse(id)
        val request = runCatching { MediaStore.createFavoriteRequest(resolver, listOf(uri), favorite) }
            .getOrElse { return failed("favorite-request", it) }
        val code = SystemDialogs.launch(request.intentSender) ?: return MediaWriteResult.Failed("no-activity")
        if (code != Activity.RESULT_OK) return MediaWriteResult.Cancelled
        val now = withContext(Dispatchers.IO) { readColumn(uri, MediaStore.MediaColumns.IS_FAVORITE) }
        return if (now == (if (favorite) "1" else "0")) MediaWriteResult.Done.also { bumpChangeToken() }
        else MediaWriteResult.Failed("favorite-unverified")
    }

    override fun folders(): List<MediaFolder> {
        if (!_access.value.canRead) return emptyList()
        val counts = HashMap<String, Int>()
        for (video in listOf(false, true)) runCatching {
            resolver.query(collection(video), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null)?.use { c ->
                while (c.moveToNext()) {
                    val p = c.getString(0)?.let { MediaFolders.normalize(it) }?.takeIf { it.isNotEmpty() } ?: continue
                    counts[p] = (counts[p] ?: 0) + 1
                }
            }
        }
        return counts.map { (p, n) -> MediaFolder(p, n) }
    }

    override suspend fun moveToFolder(id: String, relativePath: String): MediaWriteResult {
        val uri = Uri.parse(id)
        val target = MediaFolders.normalize(relativePath)
        val request = runCatching { MediaStore.createWriteRequest(resolver, listOf(uri)) }
            .getOrElse { return failed("write-request", it) }
        val code = SystemDialogs.launch(request.intentSender) ?: return MediaWriteResult.Failed("no-activity")
        if (code != Activity.RESULT_OK) return MediaWriteResult.Cancelled
        return withContext(Dispatchers.IO) {
            val updated = runCatching {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, target) }, null, null)
            }.getOrElse { return@withContext failed("update", it) }
            val now = readColumn(uri, MediaStore.MediaColumns.RELATIVE_PATH)?.let { MediaFolders.normalize(it) }
            if (updated == 1 && now == target) MediaWriteResult.Done.also { bumpChangeToken() } else MediaWriteResult.Failed("move-unverified")
        }
    }

    override suspend fun moveManyToFolder(ids: List<String>, relativePath: String): Map<String, MediaWriteResult> {
        if (ids.isEmpty()) return emptyMap()
        val uris = ids.map { Uri.parse(it) }
        val target = MediaFolders.normalize(relativePath)
        val request = runCatching { MediaStore.createWriteRequest(resolver, uris) }
            .getOrElse { e -> return ids.associateWith { failed("write-request", e) } }
        val code = SystemDialogs.launch(request.intentSender) ?: return ids.associateWith { MediaWriteResult.Failed("no-activity") }
        if (code != Activity.RESULT_OK) return ids.associateWith { MediaWriteResult.Cancelled }
        val out = withContext(Dispatchers.IO) {
            ids.associateWith { id ->
                val uri = Uri.parse(id)
                val updated = runCatching { resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, target) }, null, null) }.getOrDefault(0)
                val now = readColumn(uri, MediaStore.MediaColumns.RELATIVE_PATH)?.let { MediaFolders.normalize(it) }
                if (updated == 1 && now == target) MediaWriteResult.Done else MediaWriteResult.Failed("move-unverified")
            }
        }
        bumpChangeToken()
        return out
    }

    override suspend fun setFavoriteMany(ids: List<String>, favorite: Boolean): Map<String, MediaWriteResult> {
        if (ids.isEmpty()) return emptyMap()
        val request = runCatching { MediaStore.createFavoriteRequest(resolver, ids.map { Uri.parse(it) }, favorite) }
            .getOrElse { e -> return ids.associateWith { failed("favorite-request", e) } }
        val code = SystemDialogs.launch(request.intentSender) ?: return ids.associateWith { MediaWriteResult.Failed("no-activity") }
        if (code != Activity.RESULT_OK) return ids.associateWith { MediaWriteResult.Cancelled }
        val out = withContext(Dispatchers.IO) {
            ids.associateWith { id ->
                if (readColumn(Uri.parse(id), MediaStore.MediaColumns.IS_FAVORITE) == (if (favorite) "1" else "0")) MediaWriteResult.Done
                else MediaWriteResult.Failed("favorite-unverified")
            }
        }
        bumpChangeToken()
        return out
    }

    /** Nur Fehlerart loggen – keine URIs, Dateinamen oder Pfade. */
    private fun failed(step: String, e: Throwable): MediaWriteResult {
        Log.w("PhotoMaid", "media write failed: $step ${e.javaClass.simpleName}")
        return MediaWriteResult.Failed("$step:${e.javaClass.simpleName}")
    }

    /** Lokale Bytes für Duplikat-Hashing. Nie Netzwerk. */
    override fun openStream(id: String) = runCatching { resolver.openInputStream(Uri.parse(id)) }.getOrNull()
}
