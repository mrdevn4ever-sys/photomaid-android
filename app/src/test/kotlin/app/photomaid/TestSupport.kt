package app.photomaid

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.photomaid.billing.PurchaseGateway
import app.photomaid.billing.PurchaseResult
import app.photomaid.billing.StoreProductInfo
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.cleanup.ExistenceProbe
import app.photomaid.core.cleanup.OperationOutcome
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.MediaKind
import app.photomaid.data.PhotoMaidDatabase
import app.photomaid.design.PhotoMaidTheme
import app.photomaid.media.MediaAsset
import app.photomaid.media.MediaCategory
import app.photomaid.media.MediaLibrary
import app.photomaid.media.MediaWriteResult
import app.photomaid.core.organize.MediaFolder
import app.photomaid.media.PhotoAccess
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import coil3.imageDecoderEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/**
 * Synthetische Mediathek für Tests und Screenshots (keine persönlichen Daten, nur generierte Bilder).
 * Entspricht den QA-Medien der iOS-Tests (`scripts/qa_media.swift`).
 */
class FakeMediaLibrary(
    private val context: Context,
    count: Int = 5,
    access: PhotoAccess = PhotoAccess.AUTHORIZED,
    videos: Int = 1,
    /** Aussagekräftige Motive (Hoch-/Querformat, Panorama, Text-Screenshot, Video) für visuelle Prüfungen. */
    visual: Boolean = false,
) : MediaLibrary {
    override val access = MutableStateFlow(access)
    override val changeToken = MutableStateFlow(0)
    val removed = mutableSetOf<String>()
    var trashOutcome: ((List<String>) -> OperationOutcome?)? = null
    /** Nächste Antwort des (simulierten) Systemdialogs für Favorit/Verschieben: OK, abgelehnt oder Fehler. */
    var writeResult: MediaWriteResult = MediaWriteResult.Done
    /** Aktueller Systemzustand je ID (extern änderbar, wie MediaStore). */
    val favorites = mutableMapOf<String, Boolean>()
    val paths = mutableMapOf<String, String>()
    val writes = mutableListOf<String>()
    /** Aufnahme- bzw. Hinzufügezeit je Index überschreiben (Serien, Monate, „Neu seit“). */
    val takenAt = mutableMapOf<Int, Instant>()
    val addedAt = mutableMapOf<Int, Instant>()
    private var base: List<MediaAsset> = emptyList()
    /** G4: Dateiname/MIME je Medium überschreiben (RAW+JPEG-Paare). */
    val names = mutableMapOf<String, String>()
    val mimes = mutableMapOf<String, String>()
    /** P7: gemeldete Pixelmaße je Medium überschreiben (z. B. echte Dateigröße 300 × 400). */
    val dims = mutableMapOf<String, Pair<Int, Int>>()
    /** Gemeldete Größe je Medium überschreiben (z. B. unbekannt). */
    val sizes = mutableMapOf<String, KnownSize>()
    val all: List<MediaAsset> get() = base.mapIndexed { i, a -> a.copy(size = sizes[a.id] ?: a.size, pixelWidth = dims[a.id]?.first ?: a.pixelWidth, pixelHeight = dims[a.id]?.second ?: a.pixelHeight,
        isFavorite = favorites[a.id] ?: a.isFavorite, relativePath = paths[a.id] ?: a.relativePath ?: "DCIM/Camera/",
        displayName = names[a.id] ?: a.displayName, mimeType = mimes[a.id] ?: a.mimeType,
        creationDate = takenAt[i] ?: a.creationDate, dateAdded = addedAt[i] ?: a.dateAdded ?: a.creationDate) }

    init {
        base = if (visual) VisualTestMedia.create(context) else simple(context, count, videos)
    }

    private fun simple(context: Context, count: Int, videos: Int): List<MediaAsset> {
        val all: List<MediaAsset>
        val dir = File(context.cacheDir, "qa-media").apply { mkdirs() }
        val palette = listOf(0xFF7FB3F5, 0xFFF5B97F, 0xFF8FD6A4, 0xFFE58FB0, 0xFFB39DDB, 0xFF80CBC4, 0xFFFFE082).map { it.toInt() }
        val now = Instant.parse("2026-09-28T10:00:00Z")
        all = (0 until count + videos).map { i ->
            val f = File(dir, "QA_$i.png")
            if (!f.exists()) {
                val bmp = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(palette[i % palette.size])
                val p = Paint().apply { color = Color.WHITE; alpha = 170; isAntiAlias = true }
                canvas.drawCircle(150f, 170f, 90f, p)
                p.alpha = 110; canvas.drawRect(40f, 300f, 260f, 350f, p)
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val isVideo = i >= count
            MediaAsset(
                id = "file://${f.absolutePath}", kind = if (isVideo) MediaKind.VIDEO else if (i == 2) MediaKind.SCREENSHOT else MediaKind.PHOTO,
                creationDate = now.minusSeconds(86_400L * i), duration = if (isVideo) 32.0 else 0.0, pixelWidth = 3024, pixelHeight = 4032,
                size = KnownSize.Known(2_400_000L + i * 100_000L), mimeType = if (isVideo) "video/mp4" else "image/png",
                displayName = "QA_$i", isFavorite = false,
            )
        }
        return all
    }

    private fun visible() = all.filter { it.id !in removed }

    /** Simuliert ein extern neu aufgenommenes Medium (Kopie von Eintrag [i] mit neuer ID). */
    fun addNew(i: Int = 0, date: java.time.Instant = java.time.Instant.parse("2026-09-29T12:00:00Z")): MediaAsset {
        val a = base[i].copy(id = base[i].id + "?new=" + base.size, creationDate = date)
        base = base + a
        changeToken.value += 1
        return a
    }

    override fun refreshAccess() {}
    override fun bumpChangeToken() { changeToken.value += 1 }
    override fun count(category: MediaCategory) = if (access.value.canRead) assets(category).size else 0
    override fun assets(category: MediaCategory, limit: Int?): List<MediaAsset> {
        if (!access.value.canRead) return emptyList()
        return visible().filter {
            when (category) {
                MediaCategory.PHOTOS -> it.kind != MediaKind.VIDEO
                MediaCategory.VIDEOS -> it.kind == MediaKind.VIDEO
                MediaCategory.SCREENSHOTS -> it.kind == MediaKind.SCREENSHOT
            }
        }
    }
    override fun assets(ids: List<String>) = visible().filter { it.id in ids }.sortedBy { ids.indexOf(it.id) }
    override fun probe(ids: List<String>): Map<ItemReference, ExistenceProbe> = ids.associate {
        ItemReference.photo(it) to if (it in removed) ExistenceProbe.MISSING_WITH_FULL_ACCESS else ExistenceProbe.EXISTS
    }
    override fun openStream(id: String) = File(id.removePrefix("file://")).takeIf { it.exists() }?.inputStream()
    override suspend fun setFavorite(id: String, favorite: Boolean): MediaWriteResult {
        writes += "favorite:$favorite"
        if (writeResult == MediaWriteResult.Done) { favorites[id] = favorite; changeToken.value += 1 }
        return writeResult
    }
    override fun folders(): List<MediaFolder> = visible().groupingBy { it.relativePath ?: "DCIM/Camera/" }.eachCount().map { MediaFolder(it.key, it.value) } +
        listOf(MediaFolder("Pictures/Urlaub 2026/", 12), MediaFolder("Movies/Clips/", 3))
    override suspend fun moveToFolder(id: String, relativePath: String): MediaWriteResult {
        writes += "move:$relativePath"
        if (writeResult == MediaWriteResult.Done) { paths[id] = relativePath; changeToken.value += 1 }
        return writeResult
    }
    override suspend fun trash(ids: List<String>): OperationOutcome? {
        trashOutcome?.let { return it(ids) }
        removed += ids; trashed += ids
        return OperationOutcome.Succeeded
    }
    /** Endgültig gelöscht (G4: Einstellung „Endgültig löschen“ bzw. Android-Papierkorb). */
    val deleted = mutableSetOf<String>()
    val trashExpiry = mutableMapOf<String, java.time.Instant>()
    override fun trashed(): List<app.photomaid.media.TrashedAsset> = all.filter { it.id in trashed && it.id !in deleted }
        .map { app.photomaid.media.TrashedAsset(it, trashExpiry[it.id] ?: java.time.Instant.now().plus(java.time.Duration.ofDays(30))) }
    override suspend fun deletePermanently(ids: List<String>): OperationOutcome? {
        writes += "delete:${ids.size}"
        removed += ids; trashed -= ids.toSet(); deleted += ids; changeToken.value += 1
        return OperationOutcome.Succeeded
    }
    override suspend fun restoreManyFromTrash(ids: List<String>): Map<String, MediaWriteResult> = ids.associateWith { restoreFromTrash(it) }

    // Bearbeiten: neue Datei wie MediaStore (eigene ID), Ersetzen = Original in den (simulierten) Android-Papierkorb.
    /** Antwort der simulierten Papierkorb-Bestätigung beim Ersetzen. */
    var replaceConfirmed = true
    val trashed = mutableSetOf<String>()
    val saved = mutableListOf<app.photomaid.media.EditSaveRequest>()
    override suspend fun saveEdited(request: app.photomaid.media.EditSaveRequest): app.photomaid.media.EditSaveResult {
        // Eigene Werke (Collage) haben keine Quelle: Vorlage nur für die Felder des Fakes.
        val src = all.firstOrNull { it.id == request.sourceId } ?: (if (request.sourceId.isEmpty()) all.firstOrNull() else null)
            ?: return app.photomaid.media.EditSaveResult.Failed("gone")
        val dir = File(context.cacheDir, "qa-edits").apply { mkdirs() }
        val f = File(dir, "${base.size}_${request.displayName}")
        request.file.copyTo(f, overwrite = true)
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
        val a = src.copy(id = "file://${f.absolutePath}", displayName = request.displayName, mimeType = request.mime, size = KnownSize.Known(f.length()),
            pixelWidth = if (o.outWidth > 0) o.outWidth else src.pixelWidth, pixelHeight = if (o.outHeight > 0) o.outHeight else src.pixelHeight,
            isFavorite = request.favorite, creationDate = request.dateTakenMs?.let { Instant.ofEpochMilli(it) } ?: src.creationDate)
        base = base + a
        saved += request
        var replaced = false; var declined = false
        if (request.replace) { if (replaceConfirmed) { removed += src.id; trashed += src.id; replaced = true } else declined = true }
        changeToken.value += 1
        return app.photomaid.media.EditSaveResult.Saved(a.id, replaced, declined, f.length())
    }
    val audio = mutableListOf<String>()
    /** Gespeicherte PDFs (Name → Datei), nie in der Mediathek. */
    val documents = mutableMapOf<String, File>()
    override suspend fun saveDocument(file: File, displayName: String): app.photomaid.media.EditSaveResult {
        val f = File(File(context.cacheDir, "qa-docs").apply { mkdirs() }, displayName); file.copyTo(f, overwrite = true)
        documents[displayName] = f
        return app.photomaid.media.EditSaveResult.Saved("doc://$displayName", replaced = false, replaceDeclined = false, bytes = f.length())
    }
    override suspend fun saveAudio(file: File, displayName: String): app.photomaid.media.EditSaveResult {
        audio += displayName
        return app.photomaid.media.EditSaveResult.Saved("audio://$displayName", replaced = false, replaceDeclined = false, bytes = file.length())
    }
    override fun isTrashed(id: String): Boolean? = if (id in trashed) true else if (all.any { it.id == id }) false else null
    override suspend fun restoreFromTrash(id: String): MediaWriteResult {
        if (id !in trashed) return MediaWriteResult.Failed("not-trashed")
        if (writeResult != MediaWriteResult.Done) return writeResult
        trashed -= id; removed -= id; changeToken.value += 1
        return MediaWriteResult.Done
    }
}

/** P6 ohne Transformer (JVM): schreibt eine kleine MP4-Attrappe, meldet Fortschritt, merkt sich das Projekt. */
class FakeMovieExporter(private val context: Context) : app.photomaid.media.MovieExporter {
    val exported = mutableListOf<app.photomaid.core.studio.VideoProject>()
    override suspend fun export(project: app.photomaid.core.studio.VideoProject, sources: Map<String, app.photomaid.media.MovieSource>, onProgress: (Float) -> Unit): File {
        exported += project
        onProgress(0.5f); onProgress(1f)
        return File(context.cacheDir, "movie_${System.nanoTime()}.mp4").apply { writeBytes(ByteArray(2048)) }
    }
}

/** Video kürzen ohne Transformer (JVM): kopiert die Quelle, meldet Fortschritt. */
class FakeVideoTrimmer(private val context: Context) : app.photomaid.media.VideoTrimmer {
    val calls = mutableListOf<app.photomaid.core.edit.TrimRange>()
    override suspend fun trim(uri: Uri, range: app.photomaid.core.edit.TrimRange, onProgress: (Float) -> Unit): File {
        calls += range
        onProgress(0.5f)
        val out = File(context.cacheDir, "trim_${System.nanoTime()}.mp4")
        File(uri.path!!).copyTo(out, overwrite = true)
        onProgress(1f)
        return out
    }
}

/** Video-Export ohne Media3-Kodierung (Robolectric): merkt sich das Projekt; Datei = Kopie der Quelle. */
class FakeVideoEditExporter(private val context: Context) : app.photomaid.media.VideoEditExporter {
    val exports = mutableListOf<Pair<app.photomaid.core.studio.VideoProject, Boolean>>()
    var frames = mutableListOf<Long>()
    var info = app.photomaid.media.VideoInfo(app.photomaid.core.studio.VSize(1280, 720), 30f, hdr = false, hasAudio = true, durationMs = 32_000)
    override fun info(uri: Uri) = info
    override suspend fun export(uri: Uri, project: app.photomaid.core.studio.VideoProject, info: app.photomaid.media.VideoInfo, audioOnly: Boolean, onProgress: (Float) -> Unit): File {
        exports += project to audioOnly
        onProgress(0.5f)
        val out = File(context.cacheDir, "vexp_${System.nanoTime()}.${if (audioOnly) "m4a" else "mp4"}")
        File(uri.path!!).copyTo(out, overwrite = true)
        onProgress(1f)
        return out
    }
    override suspend fun frame(uri: Uri, sourceMs: Long, maxEdge: Int?): android.graphics.Bitmap? {
        frames += sourceMs
        // Wie der echte Exporter: auf maxEdge verkleinert (Seitenverhältnis bleibt).
        val b = android.graphics.BitmapFactory.decodeFile(uri.path!!) ?: return null
        val edge = maxEdge ?: return b
        val f = edge.toFloat() / maxOf(b.width, b.height)
        return if (f >= 1f) b else android.graphics.Bitmap.createScaledBitmap(b, maxOf(1, (b.width * f).toInt()), maxOf(1, (b.height * f).toInt()), true)
    }
}

class FakePurchaseGateway(state: PurchaseState) : PurchaseGateway {
    override val state: MutableStateFlow<PurchaseState> = MutableStateFlow(state)
    override val product: StateFlow<StoreProductInfo?> = MutableStateFlow(StoreProductInfo("3,99 CHF (TEST)", "PhotoMaid Pro (Test)"))
    override val isConfigured = true
    override suspend fun refresh() {}
    override suspend fun purchasePro(activity: Activity) = PurchaseResult.NotConfigured
    override suspend fun restore() = PurchaseResult.Failed("test")
}

/** AppModel mit In-Memory-Room und Fakes. Muss abseits des Main-Threads gebaut werden (Room). */
/** Test-Werbeanbieter: liefert der Reihe nach die vorgegebenen Ergebnisse (kein SDK, keine echten Ads). */
class FakeRewardedAdGateway(vararg results: app.photomaid.ads.RewardedAdResult) : app.photomaid.ads.RewardedAdGateway {
    private val queue = ArrayDeque(results.toList())
    var shown = 0
    override val isConfigured = true
    override suspend fun presentRewardedAd(): app.photomaid.ads.RewardedAdResult {
        shown++
        return queue.removeFirstOrNull() ?: app.photomaid.ads.RewardedAdResult.NoFill
    }
}

/** Test-Werbekarten (keine SDK-Anzeigen, keine Netzwerkanfragen). */
class FakeFeedAds(override val isAvailable: Boolean = true, private var emptyTakes: Int = 0) : app.photomaid.ads.FeedAdSource {
    var taken = 0
    var preloads = 0
    override fun preload() { preloads++ }
    /** [emptyTakes]: so oft noch „nichts geladen“ (Vorladen läuft noch). */
    override fun take(): app.photomaid.ads.FeedAd? = if (!isAvailable) null
        else if (emptyTakes > 0) { emptyTakes--; null }
        else app.photomaid.ads.FeedAd("Testanzeige ${++taken}", "Beispieltext der Anzeige", "Mehr erfahren", "Werbepartner")
}

fun makeTestApp(
    context: Context,
    media: (Context) -> MediaLibrary = { FakeMediaLibrary(it) },
    purchase: PurchaseGateway? = null,
    ads: app.photomaid.ads.RewardedAdGateway? = null,
    feedAds: app.photomaid.ads.FeedAdSource? = null,
    introSeen: Boolean = true,
    configure: suspend (AppModel) -> Unit = {},
): AppModel = runBlocking(Dispatchers.IO) {
    // FileProvider merkt sich die Pfadregeln statisch je Authority. Robolectric gibt jedem Test ein eigenes Datenverzeichnis –
    // ohne Zurücksetzen zeigt der Cache auf das Verzeichnis eines früheren Tests und Teilen schlägt fehl (nur in Tests).
    runCatching { androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        .let { f -> synchronized(f.get(null)!!) { (f.get(null) as java.util.HashMap<*, *>).clear() } } }
    // Robolectric kennt kein ImageDecoder: in Tests BitmapFactory-Decoder verwenden.
    coil3.SingletonImageLoader.setUnsafe(coil3.ImageLoader.Builder(context).imageDecoderEnabled(false).build())
    val db = PhotoMaidDatabase.open(context, inMemory = true)
    val lib = media(context)
    val app = AppModel(context, db, isQAFixtureMode = true, purchaseOverride = purchase, mediaOverride = { lib }, adsOverride = ads, feedAdsOverride = feedAds)
    app.videoExporter = FakeVideoEditExporter(context)
    app.movieExporter = FakeMovieExporter(context)
    app.exports.useService = false
    app.loadBlocking()
    if (introSeen) app.updateSettings { it.copy(privacyIntroSeen = true, showGestureHintOnOpen = false, gestureHintSeen = true, todayTourSeen = true, feedTourSeen = true,
        monthsTourSeen = true, seriesTourSeen = true, gridTourSeen = true, editorTourSeen = true) }
    configure(app)
    app.reload()
    app.start()
    app
}

class RecordingNav : Nav {
    val events = mutableListOf<String>()
    override fun openFeed(request: FeedRequest) { events += "feed:${request.source}"; requests += request }
    override fun openGrid(request: FeedRequest) { events += "grid:${request.source}"; requests += request }
    override fun openEditor(assetId: String) { events += "editor:$assetId" }
    val viewers = mutableListOf<app.photomaid.feature.library.ViewerRequest>()
    override fun openViewer(request: app.photomaid.feature.library.ViewerRequest) { events += "viewer"; viewers += request }
    val requests = mutableListOf<FeedRequest>()
    val folders = mutableListOf<app.photomaid.feature.library.FolderTarget>()
    override fun openFolder(target: app.photomaid.feature.library.FolderTarget) { events += "folder:${target.encode()}"; folders += target }
    val picks = mutableListOf<app.photomaid.feature.studio.PickRequest>()
    val slideshows = mutableListOf<app.photomaid.feature.library.SlideshowRequest>()
    override fun openSlideshow(request: app.photomaid.feature.library.SlideshowRequest) { events += "slideshow"; slideshows += request }
    val movies = mutableListOf<app.photomaid.feature.studio.MovieRequest>()
    override fun openMovie(request: app.photomaid.feature.studio.MovieRequest) { events += "movie"; movies += request }
    val tools = mutableListOf<app.photomaid.feature.studio.ToolRequest>()
    override fun openTool(request: app.photomaid.feature.studio.ToolRequest) { events += "tool:${request.kind}"; tools += request }
    override fun openPicker(request: app.photomaid.feature.studio.PickRequest) { events += "picker"; picks += request }
    val workflowRuns = mutableListOf<app.photomaid.feature.workflow.WorkflowRunRequest>()
    override fun openWorkflowRun(request: app.photomaid.feature.workflow.WorkflowRunRequest) { events += "workflowRun"; workflowRuns += request }
    override fun push(route: String) { events += "push:$route" }
    val pdfs = mutableListOf<app.photomaid.feature.document.PdfRequest>()
    override fun openPdf(request: app.photomaid.feature.document.PdfRequest) { events += "pdf"; pdfs += request }
    val workflowLists = mutableListOf<app.photomaid.feature.workflow.WorkflowRequest>()
    override fun openWorkflows(request: app.photomaid.feature.workflow.WorkflowRequest) { events += "workflows"; workflowLists += request }
    val organize = mutableListOf<app.photomaid.feature.organize.OrganizeTarget>()
    override fun openOrganize(target: app.photomaid.feature.organize.OrganizeTarget) { events += "organize:${target.encode()}"; organize += target }
    override fun openSettings() { events += "settings" }
    override fun openPro() { events += "pro" }
    override fun openPrivacy() { events += "privacy" }
    override fun back() { events += "back" }
}

@Composable
fun TestHost(app: AppModel, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalApp provides app) {
        val s by app.settings.collectAsState()
        PhotoMaidTheme(s.appearance) { content() }
    }
}
