package app.photomaid.feature.review

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.photomaid.AppModel
import app.photomaid.FeedRequest
import app.photomaid.R
import app.photomaid.core.feed.BackNavigation
import app.photomaid.core.feed.FeedGestureMachine
import app.photomaid.core.feed.FeedIntent
import app.photomaid.core.feed.FeedSession
import app.photomaid.core.feed.LaterReceipt
import app.photomaid.core.feed.SwipeDirection
import app.photomaid.core.feed.Vector2
import app.photomaid.core.later.LaterInterval
import app.photomaid.core.later.LaterUnit
import app.photomaid.core.later.ReviewQueues
import app.photomaid.core.model.ItemProvider
import app.photomaid.core.model.ItemReference
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.design.AppMotion
import app.photomaid.media.MediaAsset
import app.photomaid.media.reviewableMedia
import app.photomaid.feature.quickaccess.assetsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import app.photomaid.media.MediaWriteResult
import app.photomaid.ads.FeedAd
import app.photomaid.core.ads.FeedAdPolicy
import app.photomaid.core.capabilities.Capabilities
import app.photomaid.core.model.MediaKind
import app.photomaid.core.organize.MediaFolder
import java.util.UUID

enum class PreviewState { LOADING, READY, FAILED }

/** Kurze Bestätigung nach „Später“. Nur Anzeige; der Termin ist bereits gespeichert. */
data class LaterPill(val id: UUID = UUID.randomUUID(), val receiptID: UUID, val interval: LaterInterval)

/**
 * Offene Datumsauswahl nach Wisch schräg rechts oben – noch KEINE Entscheidung.
 * Gebunden an Item und Ledger-Revision beim Öffnen: Bestätigen wirkt nur, wenn beides noch stimmt.
 */
data class CustomLaterRequest(val id: UUID = UUID.randomUUID(), val item: ItemReference, val revision: Int)

/**
 * Haptik-Sprache des Feeds (feine, unterschiedliche Rückmeldung je Bedeutung; respektiert den App-Schalter und –
 * über `performHapticFeedback` – die Systemeinstellung). READY: einmal, wenn Loslassen jetzt entscheiden würde.
 */
enum class FeedHaptic { KEEP, CLEANUP, LATER, READY, DATE, BUMP, UNDO, FAVORITE_ON, FAVORITE_OFF }

/** Geschützter Favorit wurde nach links gewischt: Rückfrage statt Papierkorb. Keine Entscheidung. */
data class ProtectedFavorite(val item: ItemReference, val isVideo: Boolean)

/** Werbekarte zwischen zwei Medien (nur Free). Keine Entscheidung; Wegwischen erst nach [FeedAdPolicy.MIN_VISIBLE_MS]. */
data class AdCard(val id: UUID = UUID.randomUUID(), val ad: FeedAd, val shownAtMs: Long)

/** Kurzer, nur nach belegtem Erfolg gezeigter Hinweis (z. B. „Nach „Urlaub“ verschoben“). */
data class FeedNotice(val id: UUID = UUID.randomUUID(), val text: String)

/**
 * Presentation-Logik des Entscheidungs-Feeds (iOS `FeedViewModel`). Die Entscheidungslogik selbst liegt in
 * [FeedGestureMachine] und [FeedSession] (`:core`); hier wird nur verdrahtet. Überlebt Rotation (ViewModel).
 */
class FeedViewModel(private val app: AppModel, val request: FeedRequest) : ViewModel() {
    var session by mutableStateOf(FeedSession(items = emptyList()))
        private set
    /** Erhöht sich nach jeder Session-Mutation (FeedSession ist kein Snapshot-State). */
    var version by mutableIntStateOf(0)
        private set
    val machine = FeedGestureMachine()
    val assets = mutableStateMapOf<String, MediaAsset>()
    val previews = mutableStateMapOf<ItemReference, PreviewState>()
    var isLoaded by mutableStateOf(false)
        private set
    /** Visueller Versatz des aktuellen Mediums in dp (folgt dem Finger, fliegt beim Commit hinaus). */
    val offset = Animatable(Offset.Zero, Offset.VectorConverter)
    var lockedDirection by mutableStateOf<SwipeDirection?>(null)
        private set
    /** Richtung, in die Loslassen jetzt entscheiden würde (für dezente Rahmenfarbe + eine Haptik). */
    var readyDirection by mutableStateOf<SwipeDirection?>(null)
        private set
    /** Vom Screen gesetzt (Animationen entfernen). */
    var reduceMotion = false
    var errorMessage by mutableStateOf<String?>(null)
    /** Synchron gesetzt, solange ein Commit läuft: Buttons/Aktionen/Zurück können das Item nicht verschieben. */
    var isCommitting by mutableStateOf(false)
        private set
    var laterPill by mutableStateOf<LaterPill?>(null)
        private set
    /** Drehrad-Sheet für „Ändern“ in der Pille (Item ist bereits „Später“ mit Standardtermin). */
    var pickerReceipt by mutableStateOf<LaterReceipt?>(null)
    /** Drehrad-Sheet nach Wisch schräg rechts oben: erst Bestätigen entscheidet. */
    var customLater by mutableStateOf<CustomLaterRequest?>(null)
        private set
    var haptic by mutableStateOf<Pair<Int, FeedHaptic>?>(null)
        private set
    private var hapticSeq = 0
    var viewSizeDp: Size = Size(390f, 600f)
    /**
     * Kompositions-Scope (mit MonotonicFrameClock) für Animationen. Der viewModelScope hat keine Frame-Clock;
     * Animationen dort würden scheitern. Ohne UI (Tests der Logik) wird ohne Animation gesprungen.
     */
    var animationScope: kotlinx.coroutines.CoroutineScope? = null
    private var pillJob: Job? = null
    private var noticeJob: Job? = null
    var protectedFavorite by mutableStateOf<ProtectedFavorite?>(null)
        private set
    var notice by mutableStateOf<FeedNotice?>(null)
        private set
    var adCard by mutableStateOf<AdCard?>(null)
        private set
    /** Uhr für die Mindestanzeigedauer (Tests können sie ersetzen). */
    var nowMs: () -> Long = { System.currentTimeMillis() }
    val adSkippable: Boolean get() = adCard?.let { nowMs() - it.shownAtMs >= FeedAdPolicy.MIN_VISIBLE_MS } == true
    /** Läuft ein Schreibwunsch (Favorit/Verschieben) für das aktuelle Medium? Dann kein zweiter. */
    var mediaWriteBusy by mutableStateOf(false)
        private set

    val current: ItemReference? get() { version; return session.current }
    val currentAsset: MediaAsset? get() = current?.let { assets[it.localIdentifier] }
    /** Nächstes Medium (liegt beim Wischen sichtbar werdend unter dem aktuellen). */
    val next: ItemReference? get() { version; return session.items.getOrNull(session.index + 1) }

    private fun touch() { version++ }
    private fun emit(h: FeedHaptic) { if (app.settings.value.haptics) haptic = (++hapticSeq) to h }

    init {
        viewModelScope.launch { load() }
        // Externe Änderungen (Favorit in der Galerie, Verschieben, Löschen): sichtbares Fenster frisch lesen.
        viewModelScope.launch { app.photos.changeToken.drop(1).collectLatest { if (isLoaded) refreshWindowAssets() } }
    }

    private suspend fun refreshWindowAssets() {
        val ids = windowItems().map { it.localIdentifier }
        if (ids.isEmpty()) return
        val fresh = withContext(Dispatchers.IO) { app.photos.assets(ids) }
        fresh.forEach { assets[it.id] = it }
        // Extern gelöscht/verschoben/Zugriff entzogen: nicht mehr anzeigbar → Problemzustand mit „Weiter ohne Entscheidung“.
        val present = fresh.map { it.id }.toSet()
        for (ref in windowItems()) if (ref.localIdentifier !in present && ref in previews) {
            previews[ref] = PreviewState.FAILED
            session.markPreviewUnavailable(ref)
        }
        touch()
    }

    private suspend fun refreshAsset(id: String) {
        withContext(Dispatchers.IO) { app.photos.assets(listOf(id)) }.firstOrNull()?.let { assets[id] = it }
        touch()
    }

    // Warteschlange

    private suspend fun load() {
        val s = ReviewSessionID()
        val today = app.today
        val candidates: List<ItemReference> = withContext(Dispatchers.IO) {
            when (val src = request.source) {
                is FeedRequest.Source.Category -> {
                    val list = app.photos.assets(src.category)
                    list.forEach { assets[it.id] = it }
                    val refs = list.map { it.reference }
                    ReviewQueues.normalQueue(refs, app.ledger.records(refs), s, today)
                }
                FeedRequest.Source.AllMedia -> {
                    val list = app.photos.reviewableMedia()
                    list.forEach { assets[it.id] = it }
                    val refs = list.map { it.reference }
                    ReviewQueues.normalQueue(refs, app.ledger.records(refs), s, today)
                }
                is FeedRequest.Source.Quick -> {
                    // Quelle liefert nur Mitglieder; welche noch offen sind, entscheidet der globale Ledger.
                    val list = app.photos.assetsFor(src.item)
                    list.forEach { assets[it.id] = it }
                    val refs = list.map { it.reference }
                    ReviewQueues.normalQueue(refs, app.ledger.records(refs), s, today)
                }
                FeedRequest.Source.Due -> ReviewQueues.dueQueue(app.ledger.allRecords(), s, today).filter { it.provider == ItemProvider.PhotoLibrary }
                is FeedRequest.Source.Collection -> app.collections.result(src.collection, app).items.filter { it.provider == ItemProvider.PhotoLibrary }
                is FeedRequest.Source.Items -> src.items.filter { it.provider == ItemProvider.PhotoLibrary }
            }
        }
        val missing = candidates.map { it.localIdentifier }.filter { it !in assets }
        withContext(Dispatchers.IO) { app.photos.assets(missing) }.forEach { assets[it.id] = it }
        // Nicht (mehr) zugängliche Medien nicht anzeigen und nicht entscheiden.
        session = FeedSession(s, candidates.filter { it.localIdentifier in assets })
        isLoaded = true
        refreshWindow()
        touch()
    }

    // Vorschau

    /** Vorheriges, aktuelles und zwei nächste Items puffern; Rest freigeben. */
    fun windowItems(): List<ItemReference> {
        val items = session.items
        if (items.isEmpty()) return emptyList()
        val lo = maxOf(0, session.index - 1)
        val hi = minOf(items.size - 1, session.index + 2)
        return if (lo <= hi) items.subList(lo, hi + 1).toList() else emptyList()
    }

    fun refreshWindow() {
        val window = windowItems().toSet()
        for (ref in previews.keys.toList()) if (ref !in window) { previews.remove(ref); session.markPreviewUnavailable(ref) }
        for (ref in window) if (ref !in previews) previews[ref] = PreviewState.LOADING
    }

    /** „Erneut versuchen“ je Medium (neuer Wert → Vorschaubild wird neu angefordert). */
    val retries = mutableStateMapOf<ItemReference, Int>()

    fun retryPreview(ref: ItemReference) {
        if (ref !in previews) return
        previews[ref] = PreviewState.LOADING
        retries[ref] = (retries[ref] ?: 0) + 1
        touch()
    }

    /** Rückmeldung des Bildladers. Antwort nur übernehmen, wenn das Item noch im Fenster ist. */
    fun previewResult(ref: ItemReference, ok: Boolean) {
        if (ref !in previews) return
        previews[ref] = if (ok) PreviewState.READY else PreviewState.FAILED
        if (ok) session.markPreviewReady(ref) else session.markPreviewUnavailable(ref)
        touch()
    }

    // Gesten

    fun dragChanged(t: Offset) {
        if (customLater != null) return
        machine.update(Vector2(t.x.toDouble(), t.y.toDouble()))
        if (adCard != null) {
            // Werbekarte folgt dem Finger, zeigt aber keine Bedeutung (keine Entscheidung möglich).
            lockedDirection = null; readyDirection = null
            val o = machine.displayOffset
            viewModelScope.launch { offset.snapTo(Offset(o.dx.toFloat(), o.dy.toFloat())) }
            return
        }
        lockedDirection = machine.lockedDirection
        val ready = if (machine.readyToCommit) machine.lockedDirection else null
        if (ready != null && ready != readyDirection) emit(FeedHaptic.READY)
        readyDirection = ready
        val o = machine.displayOffset
        viewModelScope.launch { offset.snapTo(Offset(o.dx.toFloat(), o.dy.toFloat())) }
    }

    fun dragEnded(t: Offset, v: Offset, reduceMotion: Boolean) {
        readyDirection = null
        if (customLater != null) { dragCancelled(); return }
        if (adCard != null) {
            // Jede Richtung überspringt die Werbekarte – erst nach der Mindestdauer, nie eine Entscheidung.
            val r = machine.end(Vector2(t.x.toDouble(), t.y.toDouble()), Vector2(v.x.toDouble(), v.y.toDouble()))
            if (r is FeedGestureMachine.Resolution.Commit && FeedIntent.of(r.direction) == FeedIntent.PREVIOUS) {
                perform(FeedIntent.PREVIOUS, reduceMotion) // „runter“ = sofort zurück, auch vor der Mindestdauer
                return
            }
            machine.finishCommit()
            if (r is FeedGestureMachine.Resolution.Commit && adSkippable) dismissAd()
            lockedDirection = null
            resetOffset(reduceMotion)
            return
        }
        when (val r = machine.end(Vector2(t.x.toDouble(), t.y.toDouble()), Vector2(v.x.toDouble(), v.y.toDouble()))) {
            is FeedGestureMachine.Resolution.Commit -> perform(FeedIntent.of(r.direction), reduceMotion, velocity = v)
            else -> resetOffset(reduceMotion)
        }
    }

    fun dragCancelled() {
        machine.cancel()
        readyDirection = null
        lockedDirection = null
        viewModelScope.launch { offset.snapTo(Offset.Zero) }
    }

    fun setSuspended(suspended: Boolean) { machine.isSuspended = suspended; if (suspended) dragCancelled() }

    private fun resetOffset(reduceMotion: Boolean) {
        lockedDirection = machine.lockedDirection
        val scope = animationScope
        if (scope == null) viewModelScope.launch { offset.snapTo(Offset.Zero) }
        else scope.launch { offset.animateTo(Offset.Zero, AppMotion.standard(reduceMotion)) }
    }

    /** Gemeinsamer Pfad für Gesten, Buttons und TalkBack-Aktionen. */
    fun perform(intent: FeedIntent, reduceMotion: Boolean, velocity: Offset = Offset.Zero) {
        // Solange eine Datumsauswahl offen ist, kann nichts anderes entschieden oder navigiert werden.
        if (customLater != null) { machine.finishCommit(); resetOffset(reduceMotion); return }
        if (adCard != null) {
            // „Zurück“ überspringt die Werbekarte sofort (dann normal zurück); Entscheidungen gibt es auf ihr nicht.
            machine.finishCommit()
            if (intent == FeedIntent.PREVIOUS) dismissAd() else { if (adSkippable) dismissAd(); resetOffset(reduceMotion); return }
        }
        if (isCommitting) return
        if (intent == FeedIntent.LATER_CUSTOM) { openCustomLater(reduceMotion); return }
        if (intent == FeedIntent.PREVIOUS) {
            val nav = session.goBack()
            machine.finishCommit()
            val scope = animationScope
            if (nav is BackNavigation.Moved && scope != null && !reduceMotion) {
                // Vorheriges Medium gleitet von oben herein (statt hartem Wechsel).
                lockedDirection = null
                scope.launch {
                    offset.snapTo(Offset(0f, -viewSizeDp.height * 0.35f))
                    offset.animateTo(Offset.Zero, AppMotion.standard(false))
                }
            } else resetOffset(reduceMotion)
            if (nav == BackNavigation.AtStart) emit(FeedHaptic.BUMP) // dezenter Anschlag, keine Aktion
            app.player.stop()
            refreshWindow()
            touch()
            return
        }
        val item = current ?: run { machine.finishCommit(); lockedDirection = null; return }
        if (protectedFavorite != null) { machine.finishCommit(); resetOffset(reduceMotion); return }
        // Favoriten schützen: kein stiller Papierkorb für einen Systemfavoriten – Rückfrage, Medium bleibt.
        val asset = currentAsset
        if (intent == FeedIntent.MARK_FOR_CLEANUP && app.settings.value.protectFavorites && asset?.isFavorite == true) {
            machine.finishCommit()
            lockedDirection = null
            resetOffset(reduceMotion)
            protectedFavorite = ProtectedFavorite(item, asset.kind == MediaKind.VIDEO)
            return
        }
        isCommitting = true
        viewModelScope.launch { commit(intent, item, reduceMotion, velocity = velocity) }
    }

    /**
     * Schräg rechts oben: nur die Datumsauswahl öffnen. Keine Entscheidung, kein Ledger-Eintrag, kein
     * Weiterblättern. Das Medium federt zurück; versehentliches Öffnen bleibt folgenlos.
     */
    private fun openCustomLater(reduceMotion: Boolean) {
        machine.finishCommit()
        resetOffset(reduceMotion)
        val item = current ?: return
        val revision = (app.ledger.stateFlow.value.records[item] ?: ItemRecord(item)).revision
        customLater = CustomLaterRequest(item = item, revision = revision)
    }

    /**
     * Ergebnis der Datumsauswahl nach Wisch schräg rechts oben.
     * `null` (Abbrechen, Zurück, Wegwischen) = nichts ändern, gleiches Medium.
     * Datum = „Später“ bis zu diesem Tag, dann nächstes Medium – nur für das ursprüngliche Medium und nur,
     * wenn es seit dem Öffnen unverändert ist (sonst verworfen, niemals auf ein anderes Medium angewendet).
     */
    fun customLaterFinished(request: CustomLaterRequest, day: LocalDay?, reduceMotion: Boolean) {
        if (customLater?.id != request.id) return
        customLater = null
        if (day == null || isCommitting) return
        val item = current ?: return
        if (item != request.item) return
        val revision = (app.ledger.stateFlow.value.records[item] ?: ItemRecord(item)).revision
        if (revision != request.revision) return
        isCommitting = true
        viewModelScope.launch { commit(FeedIntent.LATER_CUSTOM, item, reduceMotion, customDay = day) }
    }

    private suspend fun commit(intent: FeedIntent, item: ItemReference, reduceMotion: Boolean, customDay: LocalDay? = null, velocity: Offset = Offset.Zero) {
        try {
            val revision = app.ledger.record(item).revision
            // Entscheidung nur für das Item, auf dem die Aktion ausgelöst wurde.
            val pending = if (session.current == item) session.requestDecision(intent, revision) else null
            if (pending == null || pending.command.item != item) {
                machine.finishCommit()
                resetOffset(reduceMotion)
                return
            }
            val target = exitOffset(intent, offset.value)
            // Fliegt mit der Wischgeschwindigkeit weiter (Feder statt fester Dauer) – schneller Wisch, schneller Wechsel.
            val fly = (animationScope ?: viewModelScope).async {
                runCatching { if (animationScope != null) offset.animateTo(target, AppMotion.flyOut(reduceMotion), initialVelocity = velocity) else offset.snapTo(target) }
            }
            app.player.stop() // ausgehenden Player sofort anhalten
            try {
                val out = app.apply(pending.command, session.id)
                // Animation abgebrochen (z. B. Rotation/Recreation) ist kein Fehler der gespeicherten Entscheidung.
                runCatching { fly.await() }
                session.commitSucceeded(pending.command.id, out.record)
                emit(when (intent) { FeedIntent.KEEP -> FeedHaptic.KEEP; FeedIntent.MARK_FOR_CLEANUP -> FeedHaptic.CLEANUP; else -> FeedHaptic.LATER })
                offset.snapTo(Offset.Zero)
                laterPill = null
                machine.finishCommit()
                lockedDirection = null
                refreshWindow()
                touch()
                afterDecisionAds()
                val receipt = session.receipt
                if (receipt != null && receipt.item == item) {
                    // Gewähltes Datum als Ergänzung derselben Später-Aktion (gemeinsames Undo); sonst Standardtermin.
                    if (customDay != null) schedule(receipt, customDay) else applyDefaultLater(receipt)
                }
            } catch (e: Exception) {
                fly.cancel()
                session.commitFailed(pending.command.id)
                errorMessage = app.string(R.string.error_decision_not_saved)
                machine.finishCommit()
                resetOffset(reduceMotion)
                touch()
            }
        } finally {
            isCommitting = false
            if (machine.phase is FeedGestureMachine.Phase.CommitRequested) machine.finishCommit()
        }
    }

    /** Ziel außerhalb des Bildschirms; die Querbewegung des Fingers bleibt erhalten (natürliche Flugbahn). */
    private fun exitOffset(intent: FeedIntent, from: Offset): Offset {
        val w = viewSizeDp.width; val h = viewSizeDp.height
        return when (intent) {
            FeedIntent.KEEP -> Offset(from.x, -h * 1.2f)
            FeedIntent.MARK_FOR_CLEANUP -> Offset(-w * 1.3f, from.y)
            FeedIntent.LATER -> Offset(w * 1.3f, from.y)
            FeedIntent.LATER_CUSTOM -> Offset(w * 1.3f, -h * 0.6f)
            FeedIntent.PREVIOUS -> Offset.Zero
        }
    }

    /** Nicht darstellbare Items überspringen – ausdrücklich OHNE Entscheidung. */
    fun skipWithoutDecision() {
        if (isCommitting || customLater != null) return
        session.jump(session.index + 1)
        app.player.stop()
        refreshWindow()
        touch()
    }

    // Später-Termin

    val defaultLaterInterval: LaterInterval get() = LaterInterval(app.settings.value.laterDefaultDays, LaterUnit.DAYS)
    val today: LocalDay get() = app.today

    /** Wisch nach rechts: sofort Standardtermin setzen und kurz als Pille zeigen. */
    private suspend fun applyDefaultLater(receipt: LaterReceipt) {
        val interval = defaultLaterInterval
        if (!schedule(receipt, interval.dueDay(app.today))) return
        val pill = LaterPill(receiptID = receipt.id, interval = interval)
        laterPill = pill
        pillJob?.cancel()
        pillJob = viewModelScope.launch {
            delay(AppMotion.LATER_PILL_MS)
            if (laterPill?.id == pill.id) laterPill = null
        }
    }

    /** Ergebnis des Drehrads aus „Ändern“ in der Pille. Abbrechen = bisheriger Termin bleibt. */
    fun pickerFinished(receipt: LaterReceipt, day: LocalDay?) {
        pickerReceipt = null
        if (day == null) return
        viewModelScope.launch {
            if (schedule(receipt, day)) emit(FeedHaptic.DATE)
            laterPill = null
        }
    }

    /** „Ändern“ in der Pille. */
    fun editLater() {
        val pill = laterPill ?: return
        val r = session.receipt ?: return
        if (r.id != pill.receiptID) return
        laterPill = null
        pickerReceipt = r
    }

    /** Speichert den Termin als Ergänzung der ursprünglichen Später-Aktion (gemeinsames Undo). */
    private suspend fun schedule(receipt: LaterReceipt, day: LocalDay): Boolean {
        val cur = session.receipt ?: return false
        if (cur.id != receipt.id) return false
        val cmd = session.scheduleCommand(cur.id, day) ?: return false
        return try {
            val out = app.apply(cmd)
            session.receiptScheduled(cur.id, out.record.revision)
            withContext(Dispatchers.IO) { app.reminders.scheduleDueReminder(day) }
            touch()
            true
        } catch (e: Exception) {
            errorMessage = app.string(R.string.error_date_not_saved)
            false
        }
    }

    // Favorit, Ordner (keine Review-Entscheidungen, kein Weiterblättern, nicht Teil des Undo-Stapels)

    /** ♡ in der Aktionsleiste. Android fragt per Systemdialog; angezeigt wird nur der danach gelesene Zustand. */
    fun toggleFavorite() {
        val a = currentAsset ?: return
        if (mediaWriteBusy || isCommitting) return
        writeFavorite(a.id, !a.isFavorite) {}
    }

    private fun writeFavorite(id: String, value: Boolean, onDone: () -> Unit) {
        mediaWriteBusy = true
        viewModelScope.launch {
            try {
                when (app.photos.setFavorite(id, value)) {
                    MediaWriteResult.Done -> { emit(if (value) FeedHaptic.FAVORITE_ON else FeedHaptic.FAVORITE_OFF); refreshAsset(id); onDone() }
                    MediaWriteResult.Cancelled -> refreshAsset(id)
                    is MediaWriteResult.Failed -> { refreshAsset(id); errorMessage = app.string(R.string.fav_failed) }
                }
            } finally { mediaWriteBusy = false }
        }
    }

    /** Schutzdialog → „Favorit entfernen“: nur den Favoriten entfernen. Das Medium bleibt; erneut wischen = Papierkorb. */
    fun unfavoriteProtected() {
        val p = protectedFavorite ?: return
        protectedFavorite = null
        writeFavorite(p.item.localIdentifier, false) { showNotice(app.string(R.string.fav_removed_hint)) }
    }

    fun dismissProtected() { protectedFavorite = null }

    /** Offenes Ordner-Sheet für dieses Medium; [folders] `null` = wird geladen. */
    var folderSheetFor by mutableStateOf<MediaAsset?>(null)
        private set
    var folders by mutableStateOf<List<MediaFolder>?>(null)
        private set

    fun openFolders() {
        val a = currentAsset ?: return
        if (mediaWriteBusy || isCommitting) return
        folders = null
        folderSheetFor = a
        viewModelScope.launch { folders = withContext(Dispatchers.IO) { runCatching { app.photos.folders() }.getOrDefault(emptyList()) } }
    }

    fun closeFolders() { folderSheetFor = null }

    /** Verschieben in einen Ordner; Erfolgsmeldung nur, wenn MediaStore den neuen Pfad bestätigt. */
    fun moveCurrent(relativePath: String) {
        val a = currentAsset ?: return
        if (mediaWriteBusy || isCommitting) return
        mediaWriteBusy = true
        viewModelScope.launch {
            try {
                when (app.photos.moveToFolder(a.id, relativePath)) {
                    MediaWriteResult.Done -> {
                        refreshAsset(a.id)
                        showNotice(app.string(R.string.folder_moved, MediaFolder(relativePath, 0).name))
                    }
                    MediaWriteResult.Cancelled -> Unit
                    is MediaWriteResult.Failed -> { refreshAsset(a.id); errorMessage = app.string(R.string.folder_failed) }
                }
            } finally { mediaWriteBusy = false }
        }
    }

    private fun showNotice(text: String) {
        laterPill = null
        val n = FeedNotice(text = text)
        notice = n
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch { delay(AppMotion.NOTICE_MS); if (notice?.id == n.id) notice = null }
    }

    // Werbekarte (nur Free; Lifetime und 30-Minuten-Pro sehen keine)

    private fun adsForUser(): Boolean = app.capabilities.let { it.tier == Capabilities.Tier.FREE && it.adsAllowed }

    /** Nach jeder gespeicherten Entscheidung: zählen, ggf. vorladen oder die Karte zeigen. */
    private suspend fun afterDecisionAds() {
        if (!adsForUser()) return
        val counter = app.feedAdCounter
        counter.recordDecision()
        if (!app.feedAds.isAvailable) return
        val today = app.today
        if (counter.isDue(today)) {
            val ad = app.feedAds.take()
            if (ad != null) {
                app.player.stop()
                laterPill = null
                adCard = AdCard(ad = ad, shownAtMs = nowMs())
                counter.recordShown(today)
                touch()
            } else app.feedAds.preload()
        } else if (counter.almostDue(today) || (session.items.size - session.index <= 2 && counter.summaryAllowed(today))) app.feedAds.preload()
    }

    /** Anzeige unter der Sitzungs-Zusammenfassung (nur Free; nie zusammen mit dem deutlichen Pro-Angebot). */
    var summaryAd by mutableStateOf<FeedAd?>(null)
        private set
    private var summaryAdDecided = false

    /** Einmal pro Durchgang entscheiden; „Letztes Medium“ und zurück zeigt dieselbe Anzeige, keine neue. */
    fun prepareSummaryAd(strongUpsell: Boolean) {
        if (summaryAdDecided) return
        summaryAdDecided = true
        if (strongUpsell || !adsForUser() || !app.feedAds.isAvailable) return
        val today = app.today
        if (!app.feedAdCounter.summaryAllowed(today)) return
        val ad = app.feedAds.take() ?: return
        summaryAd = ad
        viewModelScope.launch { app.feedAdCounter.recordShown(today) }
    }

    /** Werbekarte schließen (Wisch nach Mindestdauer, „Weiter“, Zurück, Rückgängig). */
    fun dismissAd(): Boolean {
        val a = adCard ?: return false
        adCard = null
        a.ad.destroy()
        touch()
        return true
    }

    // Undo

    fun undo() {
        dismissAd() // Rückgängig überspringt die Werbekarte – immer, auch wenn gerade noch gespeichert wird
        if (isCommitting || customLater != null) return
        laterPill = null
        viewModelScope.launch {
            val before = session.index
            val record = app.undo() ?: return@launch
            session.applyUndo(record)
            emit(FeedHaptic.UNDO)
            // Zurückgeholtes Medium gleitet von oben herein – eindeutige, kurze Rückmeldung.
            val scope = animationScope
            if (session.index < before && scope != null && !reduceMotion) scope.launch {
                offset.snapTo(Offset(0f, -viewSizeDp.height * 0.35f))
                offset.animateTo(Offset.Zero, AppMotion.standard(false))
            }
            refreshWindow()
            touch()
        }
    }

    override fun onCleared() { app.player.stop(); adCard?.ad?.destroy(); summaryAd?.destroy() }
}
