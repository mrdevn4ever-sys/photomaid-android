package app.photomaid.archive

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.photomaid.AppModel
import app.photomaid.AppSettingsData
import app.photomaid.BuildConfig
import app.photomaid.core.archive.BackupAlbum
import app.photomaid.core.archive.BackupCheck
import app.photomaid.core.archive.BackupDecision
import app.photomaid.core.archive.BackupRefTable
import app.photomaid.core.archive.BackupRestore
import app.photomaid.core.archive.BackupRules
import app.photomaid.core.archive.BackupState
import app.photomaid.core.archive.PhotoMaidBackup
import app.photomaid.core.archive.RestorePlan
import app.photomaid.core.batch.Workflow
import app.photomaid.core.collections.SmartCollection
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.edit.EditPreset
import app.photomaid.core.library.SearchText
import app.photomaid.core.model.ItemProvider
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.PhotoMaidJson
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.core.organize.MediaRef
import app.photomaid.core.quickaccess.QuickAccess
import app.photomaid.core.quickaccess.QuickAccessItem
import app.photomaid.feature.organize.ref
import app.photomaid.media.reviewableMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Was eine Sicherung enthält (für „Enthält …“ vor dem Speichern und „Das passiert“ vor dem Übernehmen). */
data class BackupContents(val decisions: Int, val albums: Int, val tagged: Int, val notes: Int, val basket: Int, val hidden: Int,
                          val workflows: Int, val presets: Int, val collections: Int)

/** Ergebnis des Übernehmens – nur, was wirklich geschrieben wurde. */
data class RestoreResult(val decisions: Int, val albumsCreated: Int, val albumsExtended: Int, val tagged: Int, val notes: Int, val basket: Int,
                         val hidden: Int, val settings: Boolean, val workflows: Int, val presets: Int, val collections: Int, val failed: Int)

/**
 * Sichern und Wiederherstellen der PhotoMaid-Daten (Paket 5). Die Datei wählt der Nutzer selbst (Android-Dateiauswahl);
 * sie enthält keine Fotos, aber Dateinamen, Schlagwörter und Notizen. Wiederherstellen überschreibt nichts, was hier schon
 * entschieden oder eingetragen ist, und zeigt nicht Wiedergefundenes als Liste.
 */
object BackupService {
    /**
     * Einstellungen, die mitgesichert werden: nur Vorlieben und Ordnerpfade. Bewusst NICHT: „Endgültig löschen“ (bleibt, wie es
     * auf diesem Gerät eingestellt ist), Mitteilungen (an eine Systemberechtigung gebunden), Rundgang-/Hinweis-Zustände,
     * Zähler und Speicherart im Editor (Vorauswahl bleibt „Als Kopie speichern“).
     */
    val SETTINGS_KEYS = setOf(
        "haptics", "showGestureHintOnOpen", "appearance", "largeFileThresholdMB", "laterDefaultDays", "protectFavorites",
        "excludedFolders", "decisionButtons", "feedOrder", "pinnedFolders", "hiddenFolders", "folderSorts", "defaultFolderSort",
        "folderListSort", "folderFavoritesOnly", "folderGroups", "favoritesByFolder", "slideshow", "basketSort",
    )
    private const val X_SETTINGS = "settings"; private const val X_WORKFLOWS = "workflows"; private const val X_PRESETS = "presets"
    private const val X_COLLECTIONS = "collections"; private const val X_QUICK = "quickAccess"

    /** Baut die Sicherung aus dem aktuellen Stand (abseits des Main-Threads). */
    suspend fun create(app: AppModel, now: Long = System.currentTimeMillis()): PhotoMaidBackup = withContext(Dispatchers.IO) {
        val records = app.ledger.allRecords().filter { it.item.provider == ItemProvider.PhotoLibrary && BackupRules.state(it.disposition) != null }
        val org = app.organize.state.value
        val hidden = app.settings.value.hiddenMedia
        val ids = (records.map { it.item.localIdentifier } + org.albums.flatMap { it.items } + org.tags.keys + org.notes.keys + org.basket + hidden).distinct()
        val current = app.allPhotos.assets(ids).associateBy { it.id }
        val stored = app.organize.storedRefs()
        fun ref(id: String): MediaRef = current[id]?.ref() ?: stored[id] ?: MediaRef(id, null, null, null)
        val table = BackupRefTable()
        // Entscheidungen nur für Medien, die es hier noch gibt (Entferntes lässt sich nirgends wiederfinden).
        val decisions = records.filter { it.item.localIdentifier in current }.map { r ->
            BackupDecision(table.add(ref(r.item.localIdentifier)), BackupRules.state(r.disposition)!!, r.dueDay, r.bookmarked)
        }
        val albums = org.albums.map { a -> BackupAlbum(a.name, a.createdAt, a.items.map { table.add(ref(it)) }) }
        val tags = org.tags.filterValues { it.isNotEmpty() }.entries.associate { (id, t) -> table.add(ref(id)) to t }
        val notes = org.notes.entries.associate { (id, n) -> table.add(ref(id)) to n }
        val basket = org.basket.map { table.add(ref(it)) }
        val hiddenIdx = hidden.map { table.add(ref(it)) }
        val settings = JsonObject(PhotoMaidJson.encodeToJsonElement(AppSettingsData.serializer(), app.settings.value).jsonObject.filterKeys { it in SETTINGS_KEYS })
        PhotoMaidBackup(
            createdAt = now, appVersion = BuildConfig.VERSION_NAME, refs = table.refs(), decisions = decisions, albums = albums,
            tags = tags, notes = notes, basket = basket, hiddenMedia = hiddenIdx,
            extras = mapOf(
                X_SETTINGS to settings,
                X_WORKFLOWS to PhotoMaidJson.encodeToJsonElement(ListSerializer(Workflow.serializer()), app.workflows.items.value),
                X_PRESETS to PhotoMaidJson.encodeToJsonElement(ListSerializer(EditPreset.serializer()), app.presets.items.value),
                X_COLLECTIONS to PhotoMaidJson.encodeToJsonElement(ListSerializer(SmartCollection.serializer()), app.collections.custom.value),
                X_QUICK to PhotoMaidJson.encodeToJsonElement(ListSerializer(QuickAccessItem.serializer()), app.quickAccess.items.value),
            ),
        )
    }

    fun contents(b: PhotoMaidBackup) = BackupContents(
        decisions = b.decisions.size, albums = b.albums.size, tagged = b.tags.size, notes = b.notes.size, basket = b.basket.size,
        hidden = b.hiddenMedia.size,
        workflows = decodeList(b, X_WORKFLOWS, Workflow.serializer()).size, presets = decodeList(b, X_PRESETS, EditPreset.serializer()).size,
        collections = decodeList(b, X_COLLECTIONS, SmartCollection.serializer()).size,
    )

    fun encode(b: PhotoMaidBackup): String = PhotoMaidJson.encodeToString(PhotoMaidBackup.serializer(), b)

    fun decode(text: String): BackupCheck =
        BackupRules.check(runCatching { PhotoMaidJson.decodeFromString(PhotoMaidBackup.serializer(), text) }.getOrNull())

    /** In die vom Nutzer gewählte Datei schreiben (`ACTION_CREATE_DOCUMENT`). */
    suspend fun write(context: Context, uri: Uri, b: PhotoMaidBackup): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(encode(b).toByteArray(Charsets.UTF_8)) } != null }.getOrDefault(false)
    }

    /** Lesen (`ACTION_OPEN_DOCUMENT`); `null` = nicht lesbar. Sehr große Dateien sind keine Sicherung. */
    suspend fun read(context: Context, uri: Uri): BackupCheck? = withContext(Dispatchers.IO) {
        runCatching {
            val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
            if (size != null && size > BackupRules.MAX_BYTES) return@runCatching BackupCheck.NotABackup
            val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it, BackupRules.MAX_BYTES) } ?: return@runCatching null
            if (bytes == null) BackupCheck.NotABackup else decode(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }

    /** Was übernommen würde – nichts wird geschrieben. Alle Medien zählen (auch ausgeblendete). */
    suspend fun plan(app: AppModel, b: PhotoMaidBackup): RestorePlan = withContext(Dispatchers.IO) {
        val all = app.allPhotos.reviewableMedia()
        val disp = app.ledger.records(all.map { it.reference }).entries.associate { (k, v) -> k.localIdentifier to v.disposition }
        val org = app.organize.state.value
        BackupRestore.plan(b, all, { it.ref() }, disp, org.albums.map { it.name }, org.notes.keys)
    }

    /** Übernehmen. Jede Entscheidung ist ein eigener Befehl über das Ledger (wie eine Geste), nur für hier Unentschiedenes. */
    suspend fun apply(app: AppModel, b: PhotoMaidBackup, p: RestorePlan, onProgress: (Float) -> Unit = {}): RestoreResult = withContext(Dispatchers.IO) {
        var failed = 0; var decided = 0
        val session = ReviewSessionID()
        val total = p.decisions.size.coerceAtLeast(1)
        for ((i, e) in p.decisions.entries.withIndex()) {
            val (id, d) = e
            val ok = runCatching {
                val item = ItemReference.photo(id)
                var r = app.ledger.record(item)
                if (r.disposition != Disposition.UNDECIDED) return@runCatching false
                suspend fun step(a: DecisionAction) { r = app.apply(DecisionCommand(item = item, expectedRevision = r.revision, action = a)).record }
                when (d.state) {
                    BackupState.KEPT -> step(DecisionAction.Keep)
                    BackupState.MARKED -> step(DecisionAction.MarkForCleanup)
                    BackupState.LATER -> {
                        step(DecisionAction.Later(session))
                        if (d.bookmarked) step(DecisionAction.Bookmark) else d.due?.let { step(DecisionAction.Schedule(it)) }
                    }
                }
                true
            }.getOrElse { failed++; false }
            if (ok) decided++
            onProgress(0.8f * (i + 1) / total)
        }
        // Organisieren: Alben gleichen Namens werden ergänzt, nichts entfernt.
        val needed = (p.albums.flatMap { it.second } + p.tags.keys + p.notes.keys + p.basket).distinct()
        val assets = app.allPhotos.assets(needed).associateBy { it.id }
        var created = 0; var extended = 0
        for ((name, ids) in p.albums) {
            val list = ids.mapNotNull { assets[it] }
            val existing = app.organize.state.value.albums.firstOrNull { SearchText.normalize(it.name) == SearchText.normalize(name) }
            if (existing == null) { if (app.organize.createAlbum(name, list) != null) created++ }
            else if (app.organize.addToAlbum(existing.id, list) > 0) extended++
        }
        // Gleiche Schlagwort-Listen gemeinsam schreiben (eine Transaktion je Gruppe).
        p.tags.entries.groupBy({ it.value }, { it.key }).forEach { (tags, ids) -> app.organize.editTags(ids.mapNotNull { assets[it] }, add = tags) }
        for ((id, text) in p.notes) assets[id]?.let { app.organize.setNote(it, text) }
        val basketAdded = app.organize.addToBasket(p.basket.mapNotNull { assets[it] })
        val hiddenBefore = app.settings.value.hiddenMedia.toSet()
        val hiddenNew = p.hidden.filter { it !in hiddenBefore }
        onProgress(0.9f)
        // Einstellungen (nur die gesicherten Vorlieben), Abläufe, Vorlagen, Sammlungen, Schnellzugriffe.
        val settingsJson = b.extras[X_SETTINGS] as? JsonObject
        var settingsOk = false
        app.updateSettings { cur ->
            val base = PhotoMaidJson.encodeToJsonElement(AppSettingsData.serializer(), cur).jsonObject
            val merged = settingsJson?.let { s -> runCatching { PhotoMaidJson.decodeFromJsonElement(AppSettingsData.serializer(),
                JsonObject(base + s.filterKeys { it in SETTINGS_KEYS })) }.getOrNull() }
            if (merged != null) settingsOk = true
            (merged ?: cur).copy(hiddenMedia = cur.hiddenMedia + hiddenNew)
        }
        val wfHave = app.workflows.items.value.map { it.id }.toSet()
        val wfNew = decodeList(b, X_WORKFLOWS, Workflow.serializer()).filter { it.id !in wfHave }
        wfNew.forEach { app.workflows.upsert(it) }
        val prHave = app.presets.items.value.map { it.id }.toSet()
        val prNew = decodeList(b, X_PRESETS, EditPreset.serializer()).filter { it.id !in prHave }
        prNew.reversed().forEach { app.presets.add(it) }
        val coHave = app.collections.custom.value.map { it.id }.toSet()
        val coNew = decodeList(b, X_COLLECTIONS, SmartCollection.serializer()).filter { it.id !in coHave }
        coNew.forEach { app.collections.upsert(it) }
        // Schnellzugriffe nur, solange hier noch die Vorgabe steht (eigene Anordnung bleibt).
        val quick = decodeList(b, X_QUICK, QuickAccessItem.serializer())
        if (quick.isNotEmpty() && app.quickAccess.items.value == QuickAccess.defaults) app.quickAccess.update { quick }
        onProgress(1f)
        RestoreResult(decided, created, extended, p.tags.size, p.notes.size, basketAdded, hiddenNew.size, settingsOk,
            wfNew.size, prNew.size, coNew.size, failed)
    }

    /** Liest höchstens [max] Bytes; `null`, wenn die Datei größer ist. */
    private fun readLimited(input: java.io.InputStream, max: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
        while (true) { val r = input.read(buf); if (r < 0) break; out.write(buf, 0, r); if (out.size() > max) return null }
        return out.toByteArray()
    }

    private fun <T> decodeList(b: PhotoMaidBackup, key: String, s: kotlinx.serialization.KSerializer<T>): List<T> =
        b.extras[key]?.let { runCatching { PhotoMaidJson.decodeFromJsonElement(ListSerializer(s), it) }.getOrNull() }.orEmpty()
}
