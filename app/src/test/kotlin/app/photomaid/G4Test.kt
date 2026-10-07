package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.cleanup.CleanupOperationKind
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.Disposition
import app.photomaid.feature.cleanup.BasketSort
import app.photomaid.feature.cleanup.CleanupBasketScreen
import app.photomaid.feature.cleanup.SystemTrashScreen
import app.photomaid.feature.cleanup.daysLeft
import app.photomaid.feature.library.LibraryScreen
import app.photomaid.feature.library.RawIndex
import app.photomaid.media.reviewableMedia
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant

/** G4: Papierkorb-Sortierung, Android-Papierkorb, „Endgültig löschen“ (Standard aus), RAW+JPEG-Paare. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "+w393dp-h852dp")
class G4Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()

    private fun start(setup: (FakeMediaLibrary) -> Unit = {}) { app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 8, videos = 2).also { l -> lib = l; setup(l) } }) }
    private fun pumpUntil(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        rule.mainClock.autoAdvance = false
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(5)
            if (cond()) break
            check(System.currentTimeMillis() < end) { "Bedingung nicht erfüllt" }
        }
        rule.mainClock.autoAdvance = true
    }
    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    private fun click(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
    private fun clickText(t: String) {
        pumpUntil { rule.onAllNodes(hasText(t) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText(t) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true }
    }

    @Test fun permanentDeletionIsOffByDefaultAndUsesDeleteRequestWhenOn() {
        start()
        assertFalse(AppSettingsData().deletePermanently)
        val refs = lib.all.take(2).map { it.reference }
        runBlocking { refs.forEach { app.decide(it, DecisionAction.MarkForCleanup) } }
        // Standard: Android-Papierkorb.
        val r1 = runBlocking { app.runCleanup(listOf(refs[0]), CleanupOperationKind.PHOTO_LIBRARY_DELETE) }
        assertTrue(r1 is CleanupRunResult.Finished)
        assertTrue(lib.all[0].id in lib.trashed); assertFalse(lib.all[0].id in lib.deleted)
        // Endgültig: kein Papierkorb, belegt entfernt, Ledger REMOVED.
        val r2 = runBlocking { app.runCleanup(listOf(refs[1]), CleanupOperationKind.PHOTO_LIBRARY_DELETE_PERMANENTLY) }
        assertTrue(r2 is CleanupRunResult.Finished)
        assertTrue(lib.all[1].id in lib.deleted); assertFalse(lib.all[1].id in lib.trashed)
        assertEquals(Disposition.REMOVED, runBlocking { app.ledger.record(refs[1]) }.disposition)
    }

    @Test fun basketUsesSettingAndSortsByPlacement() {
        start()
        val refs = lib.all.take(3).map { it.reference }
        runBlocking { for (r in listOf(refs[2], refs[0], refs[1])) { app.decide(r, DecisionAction.MarkForCleanup); Thread.sleep(5) } ; app.updateSettings { it.copy(deletePermanently = true) } }
        rule.setContent { TestHost(app) { CleanupBasketScreen(nav, showsDone = false) } }
        pumpUntil { count("basket.sort") == 1 }
        click("basket.sort.TAKEN"); pumpUntil { app.settings.value.basketSort == BasketSort.TAKEN }
        click("basket.sort.SIZE"); pumpUntil { app.settings.value.basketSort == BasketSort.SIZE }
        click("basket.systrash"); assertTrue(nav.events.contains("push:systemTrash"))
        // Mit „Endgültig löschen“: deutlich andere Bestätigung.
        click("basket.empty.media")
        pumpUntil { rule.onAllNodes(hasText("Endgültig löschen?")).fetchSemanticsNodes().isNotEmpty() }
        clickText("3 Elemente endgültig löschen")
        pumpUntil { lib.deleted.size == 3 }
    }

    @Test fun systemTrashListsExpiryRestoresAsKeptAndDeletes() {
        start()
        val a = lib.all[0]; val b = lib.all[1]
        runBlocking { app.decide(a.reference, DecisionAction.MarkForCleanup); app.runCleanup(listOf(a.reference), CleanupOperationKind.PHOTO_LIBRARY_DELETE) }
        lib.trashed += b.id; lib.removed += b.id
        lib.trashExpiry[a.id] = Instant.now().plus(Duration.ofDays(2)); lib.trashExpiry[b.id] = Instant.now().plus(Duration.ofDays(20))
        rule.setContent { TestHost(app) { SystemTrashScreen(nav) } }
        pumpUntil { count("systrash.tile") == 2 }
        assertTrue(rule.onAllNodes(hasText("noch 2 Tage")).fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodesWithTag("systrash.tile")[0].performSemanticsAction(SemanticsActions.OnClick); pumpUntil { count("systrash.restore") == 1 }
        click("systrash.restore")
        pumpUntil { a.id !in lib.trashed }
        // Von PhotoMaid entfernt und zurückgeholt = behalten.
        pumpUntil { runBlocking { app.ledger.record(a.reference) }.disposition == Disposition.KEPT }
        pumpUntil { count("systrash.tile") == 1 }
        rule.onAllNodesWithTag("systrash.tile")[0].performSemanticsAction(SemanticsActions.OnClick); pumpUntil { count("systrash.delete") == 1 }
        click("systrash.delete")
        // Gleicher Text wie die Taste unten: die Bestätigung im Blatt ist der letzte Treffer.
        pumpUntil { rule.onAllNodes(hasText("1 Element endgültig löschen") and hasClickAction()).fetchSemanticsNodes().size >= 2 }
        rule.onAllNodes(hasText("1 Element endgültig löschen") and hasClickAction()).let { n -> n[n.fetchSemanticsNodes().size - 1] }
            .performSemanticsAction(SemanticsActions.OnClick)
        pumpUntil { b.id in lib.deleted && count("systrash.empty") == 1 }
        assertEquals(Disposition.UNDECIDED, runBlocking { app.ledger.record(b.reference) }.disposition) // fremdes Medium: kein Eintrag
    }

    @Test fun daysLeftRoundsUpAndUnknownStaysUnknown() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        assertEquals(1, daysLeft(now.plus(Duration.ofHours(3)), now))
        assertEquals(30, daysLeft(now.plus(Duration.ofDays(30)), now))
        assertEquals(0, daysLeft(now.minus(Duration.ofDays(1)), now))
        assertNull(daysLeft(null, now))
    }

    @Test fun rawStaysBehindJpegAndGoesAlongToTrash() {
        start()
        val jpg = lib.all[0]; val raw = lib.all[1]
        lib.names[jpg.id] = "IMG_0042.JPG"; lib.names[raw.id] = "IMG_0042.DNG"; lib.mimes[raw.id] = "image/x-adobe-dng"
        lib.paths[jpg.id] = "DCIM/Camera/"; lib.paths[raw.id] = "DCIM/Camera/"
        val all = lib.reviewableMedia()
        val idx = RawIndex(all)
        assertEquals(raw.id, idx.partner[all.first { it.id == jpg.id }.id]?.id)
        assertFalse(idx.visible(all).any { it.id == raw.id })
        assertEquals(setOf(jpg.id, raw.id), idx.withPartners(all.filter { it.id == jpg.id }).map { it.id }.toSet())
        rule.setContent { TestHost(app) { LibraryScreen(nav) } }
        pumpUntil { count("lib.tile") > 0 }
        pumpUntil { rule.onAllNodesWithTag("lib.tile.raw", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
    }
}
