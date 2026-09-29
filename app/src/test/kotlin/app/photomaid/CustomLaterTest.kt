package app.photomaid

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.feed.FeedIntent
import app.photomaid.core.model.ItemReference
import app.photomaid.feature.review.CustomLaterRequest
import app.photomaid.feature.review.FeedViewModel
import app.photomaid.media.MediaCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Datumsauswahl nach ↗ ohne UI: Bindung an Item + Revision, verspätete/fremde Rückmeldungen wirken nie.
 * (Die TalkBack-/Schalter-Aktion „Später, Datum wählen“ nutzt denselben Pfad `perform(LATER_CUSTOM)`.)
 */
@RunWith(AndroidJUnit4::class)
class CustomLaterTest {
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private lateinit var vm: FeedViewModel

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        app = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 3, videos = 0).also { l -> lib = l } })
        vm = FeedViewModel(app, FeedRequest(FeedRequest.Source.Category(MediaCategory.PHOTOS)))
        idleUntil { vm.isLoaded }
        // Vorschau bereit (sonst lehnt die Session Entscheidungen ab – wie im echten Feed).
        vm.session.items.take(2).forEach { vm.previewResult(it, true) }
    }

    private fun idleUntil(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < end) { "Timeout" }
            Thread.sleep(10)
        }
    }

    private fun item(i: Int) = ItemReference.photo(lib.all[i].id)
    private fun rec(i: Int) = app.ledger.stateFlow.value.records[item(i)] ?: ItemRecord(item(i))

    @Test fun openingIsNoDecision() {
        vm.perform(FeedIntent.LATER_CUSTOM, reduceMotion = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(vm.customLater)
        assertEquals(item(0), vm.customLater?.item)
        assertEquals(item(0), vm.current)
        assertEquals(0, app.ledger.stateFlow.value.events.size)
        // Andere Aktionen sind blockiert, solange die Auswahl offen ist.
        vm.perform(FeedIntent.KEEP, reduceMotion = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, app.ledger.stateFlow.value.events.size)
    }

    @Test fun cancelChangesNothing() {
        vm.perform(FeedIntent.LATER_CUSTOM, reduceMotion = true)
        vm.customLaterFinished(vm.customLater!!, null, reduceMotion = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(vm.customLater)
        assertEquals(item(0), vm.current)
        assertEquals(Disposition.UNDECIDED, rec(0).disposition)
        assertEquals(0, app.ledger.stateFlow.value.events.size)
    }

    @Test fun confirmAppliesOnlyToOriginalItem() {
        vm.perform(FeedIntent.LATER_CUSTOM, reduceMotion = true)
        val day = app.today.addingDays(30)
        vm.customLaterFinished(vm.customLater!!, day, reduceMotion = true)
        idleUntil { rec(0).dueDay != null }
        assertEquals(Disposition.LATER, rec(0).disposition)
        assertEquals(day, rec(0).dueDay)
        assertEquals(item(1), vm.current)
        assertEquals(Disposition.UNDECIDED, rec(1).disposition)
    }

    @Test fun staleRevisionIsDiscarded() {
        vm.perform(FeedIntent.LATER_CUSTOM, reduceMotion = true)
        val req = vm.customLater!!
        // Während die Auswahl offen ist, ändert sich das Item anderswo (z. B. zweites Fenster, Regel, Undo).
        runBlocking(Dispatchers.IO) {
            app.ledger.apply(DecisionCommand(item = item(0), expectedRevision = app.ledger.record(item(0)).revision, action = DecisionAction.Keep))
        }
        vm.customLaterFinished(req, app.today.addingDays(30), reduceMotion = true)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Disposition.KEPT, rec(0).disposition)
        assertNull(rec(0).dueDay)
        assertEquals(Disposition.UNDECIDED, rec(1).disposition)
        assertEquals(1, app.ledger.stateFlow.value.events.size)
    }

    @Test fun foreignOrLateCallbackIsIgnored() {
        vm.perform(FeedIntent.LATER_CUSTOM, reduceMotion = true)
        val open = vm.customLater!!
        // Rückmeldung einer anderen (alten) Auswahl, sogar für das nächste Medium: wirkungslos.
        vm.customLaterFinished(CustomLaterRequest(item = item(1), revision = 0), app.today.addingDays(5), reduceMotion = true)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(open, vm.customLater)
        assertEquals(0, app.ledger.stateFlow.value.events.size)
        // Doppelte Bestätigung derselben Auswahl entscheidet nur einmal.
        vm.customLaterFinished(open, app.today.addingDays(5), reduceMotion = true)
        vm.customLaterFinished(open, app.today.addingDays(9), reduceMotion = true)
        idleUntil { rec(0).dueDay != null }
        Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(app.today.addingDays(5), rec(0).dueDay)
        assertEquals(Disposition.UNDECIDED, rec(1).disposition)
        assertEquals(2, app.ledger.stateFlow.value.events.size)
    }
}
