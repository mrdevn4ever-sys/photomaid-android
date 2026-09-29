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
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.design.AppMotion
import app.photomaid.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class PreviewState { LOADING, READY, FAILED }

/** Kurze Bestätigung nach „Später“. Nur Anzeige; der Termin ist bereits gespeichert. */
data class LaterPill(val id: UUID = UUID.randomUUID(), val receiptID: UUID, val interval: LaterInterval)

/** Haptik-Ereignis (feine, unterschiedliche Rückmeldung je Aktion). */
enum class FeedHaptic { KEEP, CLEANUP, LATER, BUMP, UNDO }

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
    var errorMessage by mutableStateOf<String?>(null)
    /** Synchron gesetzt, solange ein Commit läuft: Buttons/Aktionen/Zurück können das Item nicht verschieben. */
    var isCommitting by mutableStateOf(false)
        private set
    var laterPill by mutableStateOf<LaterPill?>(null)
        private set
    /** Drehrad-Sheet nach Wisch schräg rechts oben (oder „Ändern“ in der Pille). */
    var pickerReceipt by mutableStateOf<LaterReceipt?>(null)
    var haptic by mutableStateOf<Pair<Int, FeedHaptic>?>(null)
        private set
    private var hapticSeq = 0
    var viewSizeDp: Size = Size(390f, 600f)
    private var pillJob: Job? = null

    val current: ItemReference? get() { version; return session.current }
    val currentAsset: MediaAsset? get() = current?.let { assets[it.localIdentifier] }

    private fun touch() { version++ }
    private fun emit(h: FeedHaptic) { if (app.settings.value.haptics) haptic = (++hapticSeq) to h }

    init { viewModelScope.launch { load() } }

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
                FeedRequest.Source.Due -> ReviewQueues.dueQueue(app.ledger.allRecords(), s, today).filter { it.provider == ItemProvider.PhotoLibrary }
                is FeedRequest.Source.Task -> src.task.items.filter { it.provider == ItemProvider.PhotoLibrary }
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

    /** Rückmeldung des Bildladers. Antwort nur übernehmen, wenn das Item noch im Fenster ist. */
    fun previewResult(ref: ItemReference, ok: Boolean) {
        if (ref !in previews) return
        previews[ref] = if (ok) PreviewState.READY else PreviewState.FAILED
        if (ok) session.markPreviewReady(ref) else session.markPreviewUnavailable(ref)
        touch()
    }

    // Gesten

    fun dragChanged(t: Offset) {
        machine.update(Vector2(t.x.toDouble(), t.y.toDouble()))
        lockedDirection = machine.lockedDirection
        val o = machine.displayOffset
        viewModelScope.launch { offset.snapTo(Offset(o.dx.toFloat(), o.dy.toFloat())) }
    }

    fun dragEnded(t: Offset, v: Offset, reduceMotion: Boolean) {
        when (val r = machine.end(Vector2(t.x.toDouble(), t.y.toDouble()), Vector2(v.x.toDouble(), v.y.toDouble()))) {
            is FeedGestureMachine.Resolution.Commit -> perform(FeedIntent.of(r.direction), reduceMotion)
            else -> resetOffset(reduceMotion)
        }
    }

    fun dragCancelled() {
        machine.cancel()
        lockedDirection = null
        viewModelScope.launch { offset.snapTo(Offset.Zero) }
    }

    fun setSuspended(suspended: Boolean) { machine.isSuspended = suspended; if (suspended) dragCancelled() }

    private fun resetOffset(reduceMotion: Boolean) {
        lockedDirection = machine.lockedDirection
        viewModelScope.launch { offset.animateTo(Offset.Zero, AppMotion.standard(reduceMotion)) }
    }

    /** Gemeinsamer Pfad für Gesten, Buttons und TalkBack-Aktionen. */
    fun perform(intent: FeedIntent, reduceMotion: Boolean) {
        if (isCommitting) return
        if (intent == FeedIntent.PREVIOUS) {
            val nav = session.goBack()
            machine.finishCommit()
            resetOffset(reduceMotion)
            if (nav == BackNavigation.AtStart) emit(FeedHaptic.BUMP) // dezenter Anschlag, keine Aktion
            app.player.stop()
            refreshWindow()
            touch()
            return
        }
        val item = current ?: run { machine.finishCommit(); lockedDirection = null; return }
        isCommitting = true
        viewModelScope.launch { commit(intent, item, reduceMotion) }
    }

    private suspend fun commit(intent: FeedIntent, item: ItemReference, reduceMotion: Boolean) {
        try {
            val revision = app.ledger.record(item).revision
            // Entscheidung nur für das Item, auf dem die Aktion ausgelöst wurde.
            val pending = if (session.current == item) session.requestDecision(intent, revision) else null
            if (pending == null || pending.command.item != item) {
                machine.finishCommit()
                resetOffset(reduceMotion)
                return
            }
            val fly = viewModelScope.async { offset.animateTo(exitOffset(intent), AppMotion.commit(reduceMotion)) }
            app.player.stop() // ausgehenden Player sofort anhalten
            try {
                val out = app.apply(pending.command, session.id)
                fly.await()
                session.commitSucceeded(pending.command.id, out.record)
                emit(when (intent) { FeedIntent.KEEP -> FeedHaptic.KEEP; FeedIntent.MARK_FOR_CLEANUP -> FeedHaptic.CLEANUP; else -> FeedHaptic.LATER })
                offset.snapTo(Offset.Zero)
                laterPill = null
                machine.finishCommit()
                lockedDirection = null
                refreshWindow()
                touch()
                val receipt = session.receipt
                if (receipt != null && receipt.item == item) {
                    if (receipt.wantsCustomDate) pickerReceipt = receipt else applyDefaultLater(receipt)
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

    private fun exitOffset(intent: FeedIntent): Offset {
        val w = viewSizeDp.width; val h = viewSizeDp.height
        return when (intent) {
            FeedIntent.KEEP -> Offset(0f, -h * 1.2f)
            FeedIntent.MARK_FOR_CLEANUP -> Offset(-w * 1.3f, 0f)
            FeedIntent.LATER -> Offset(w * 1.3f, 0f)
            FeedIntent.LATER_CUSTOM -> Offset(w * 1.3f, -h * 0.6f)
            FeedIntent.PREVIOUS -> Offset.Zero
        }
    }

    /** Nicht darstellbare Items überspringen – ausdrücklich OHNE Entscheidung. */
    fun skipWithoutDecision() {
        if (isCommitting) return
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

    /** Ergebnis des Drehrads. Abbrechen = Standardtermin (ein Später hat immer einen Termin). */
    fun pickerFinished(receipt: LaterReceipt, day: LocalDay?) {
        pickerReceipt = null
        viewModelScope.launch {
            if (day != null) {
                schedule(receipt, day)
                laterPill = null
            } else if (session.receipt?.id == receipt.id) {
                applyDefaultLater(session.receipt ?: receipt)
            }
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

    // Undo

    fun undo() {
        if (isCommitting) return
        laterPill = null
        viewModelScope.launch {
            val record = app.undo() ?: return@launch
            session.applyUndo(record)
            emit(FeedHaptic.UNDO)
            refreshWindow()
            touch()
        }
    }

    override fun onCleared() { app.player.stop() }
}
