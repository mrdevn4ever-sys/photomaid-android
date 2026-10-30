package app.photomaid

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.cleanup.OperationOutcome
import app.photomaid.core.model.ItemReference
import app.photomaid.feature.cleanup.CleanupBasketScreen
import app.photomaid.feature.duplicates.DuplicatesScreen
import app.photomaid.feature.onboarding.PrivacyAccessScreen
import app.photomaid.feature.settings.SettingsScreen
import app.photomaid.feature.today.TodayScreen
import app.photomaid.media.PhotoAccess
import app.photomaid.navigation.RootContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import app.photomaid.feature.review.ReviewFeedScreen
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Kritische Flows (Masterprompt §44; iOS `PhotoMaidUITests`). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFlowsTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun AppModel.rec(ref: ItemReference) = ledger.stateFlow.value.records[ref] ?: ItemRecord(ref)
    private fun AppModel.available() = ledger.allowance(ledger.stateFlow.value, 20).available

    private suspend fun AppModel.mark(ref: ItemReference) =
        ledger.apply(DecisionCommand(item = ref, expectedRevision = ledger.record(ref).revision, action = DecisionAction.MarkForCleanup))

    @Test fun onboardingShowsPrivacyThenTabs() {
        val app = makeTestApp(ctx, introSeen = false)
        rule.setContent { TestHost(app) { RootContent(app) } }
        // Neues Onboarding: Geschichte → (Überspringen) → Angebot → Kostenlos → Berechtigungen.
        rule.onNodeWithTag("onb.skip").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("offer.free").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("offer.free").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Zugriff auf Fotos und Videos")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Voller Zugriff").assertExists()
        rule.onNodeWithText("Weiter").performClick()
        rule.waitUntil(5_000) { app.settings.value.privacyIntroSeen }
        rule.onAllNodesWithText("Heute").onFirst().assertExists() // Titel + Tab
        rule.onNodeWithText("Aufräumen").assertExists()
        rule.onNodeWithText("Ordnen").assertDoesNotExist() // Tab entfällt; Sammlungen liegen in Aufräumen
        rule.onNodeWithText("Verlauf").assertExists()
    }

    @Test fun permissionNotDeterminedShowsRequestButton() {
        val app = makeTestApp(ctx, media = { FakeMediaLibrary(it, access = PhotoAccess.NOT_DETERMINED) })
        rule.setContent { TestHost(app) { PrivacyAccessScreen(onboarding = true) {} } }
        rule.onNodeWithText("Zugriff erlauben").assertExists()
        rule.onNodeWithText("Voller Zugriff").assertDoesNotExist()
    }

    @Test fun permissionDeniedShowsHonestState() {
        val app = makeTestApp(ctx, media = { FakeMediaLibrary(it, access = PhotoAccess.DENIED) })
        rule.setContent { TestHost(app) { TodayScreen(RecordingNav()) } }
        rule.onNodeWithText("Kein Zugriff auf deine Fotos und Videos").assertExists()
        rule.onNodeWithText("Erlaube den Zugriff in den Android-Einstellungen für GaleriQ. Deine bisherigen Entscheidungen bleiben gespeichert.").assertExists()
        rule.onNodeWithText("Zugriff ändern").assertExists() // Ausweg statt Sackgasse
    }

    @Test fun permissionGrantedShowsPendingCount() {
        val app = makeTestApp(ctx)
        rule.setContent { TestHost(app) { TodayScreen(RecordingNav()) } }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Noch 6 Fotos und Videos durchsehen")).fetchSemanticsNodes().isNotEmpty() }
        // Noch keine Entscheidung getroffen → neue Prüfung (nicht „fortsetzen“).
        rule.onNodeWithText("Jetzt aufräumen").assertExists()
    }

    /** Zahl und Feed zählen dasselbe: „Jetzt aufräumen“ öffnet den gemeinsamen Feed aller offenen Medien. */
    @Test fun startReviewOpensSharedFeedWithSameCount() {
        val app = makeTestApp(ctx) // 5 Fotos (inkl. 1 Screenshot) + 1 Video
        val nav = RecordingNav()
        rule.setContent { TestHost(app) { TodayScreen(nav) } }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Noch 6 Fotos und Videos durchsehen")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Jetzt aufräumen").performClick()
        assertTrue(nav.events.last() == "feed:AllMedia")
    }

    @Test fun sharedFeedContainsPhotosScreenshotsAndVideos() {
        val app = makeTestApp(ctx)
        rule.setContent { TestHost(app) { ReviewFeedScreen(FeedRequest(FeedRequest.Source.AllMedia), RecordingNav(), onClose = {}, openBasket = {}) } }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("1 von 6")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Aufräumen").assertExists()
    }

    @Test fun singularTexts() {
        val res = ctx.resources
        assertEquals("1 Element", res.getQuantityString(R.plurals.items_count, 1, 1))
        assertEquals("15 Elemente", res.getQuantityString(R.plurals.items_count, 15, 15))
        assertEquals("1 Foto oder Video liegt jetzt im Android-Papierkorb.", res.getQuantityString(R.plurals.result_media_trashed, 1, 1))
        assertEquals("1 Kopie in den Papierkorb", res.getQuantityString(R.plurals.dup_move, 1, 1))
        assertEquals("Noch 1 Foto oder Video durchsehen", res.getQuantityString(R.plurals.today_pending_count, 1, 1))
    }

    @Test fun confirmCleanupTrashesReconcilesAndCountsAllowance() {
        lateinit var lib: FakeMediaLibrary
        val app = makeTestApp(ctx, media = { FakeMediaLibrary(it).also { l -> lib = l } }) { a ->
            a.mark(ItemReference.photo(lib.all[0].id)); a.mark(ItemReference.photo(lib.all[1].id))
        }
        rule.setContent { TestHost(app) { CleanupBasketScreen(RecordingNav(), showsDone = true) } }
        rule.onNodeWithText("2 im Papierkorb").assertExists()
        rule.onNodeWithText("Heute noch 20 von 20 möglich").assertExists()
        rule.onNodeWithText("2 Fotos und Videos entfernen").performClick()
        rule.onNodeWithText("2 entfernen").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("2 Fotos und Videos liegen jetzt im Android-Papierkorb.")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(setOf(lib.all[0].id, lib.all[1].id), lib.removed)
        val r = app.rec(ItemReference.photo(lib.all[0].id))
        assertEquals(Disposition.REMOVED, r.disposition)
        assertEquals(18, app.available())
    }

    @Test fun cancelledSystemDialogRemovesNothing() {
        lateinit var lib: FakeMediaLibrary
        val app = makeTestApp(ctx, media = { FakeMediaLibrary(it).also { l -> lib = l; l.trashOutcome = { OperationOutcome.Cancelled } } }) { a ->
            a.mark(ItemReference.photo(lib.all[0].id))
        }
        rule.setContent { TestHost(app) { CleanupBasketScreen(RecordingNav(), showsDone = true) } }
        rule.onNodeWithText("1 Foto oder Video entfernen").performClick()
        rule.onNodeWithText("1 entfernen").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Abgebrochen. Nichts wurde entfernt.")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(20, app.available())
        assertEquals(Disposition.MARKED_FOR_CLEANUP, app.rec(ItemReference.photo(lib.all[0].id)).disposition)
    }

    @Test fun deselectionIsKeptAndOnlySelectedAreRemoved() {
        lateinit var lib: FakeMediaLibrary
        val app = makeTestApp(ctx, media = { FakeMediaLibrary(it).also { l -> lib = l } }) { a ->
            a.mark(ItemReference.photo(lib.all[0].id)); a.mark(ItemReference.photo(lib.all[1].id))
        }
        rule.setContent { TestHost(app) { CleanupBasketScreen(RecordingNav(), showsDone = true) } }
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Ausgewählt: Foto"))[0].performClick()
        rule.onNodeWithText("1 Foto oder Video entfernen").performClick()
        rule.onNodeWithText("1 entfernen").performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.waitForIdle(); app.read.value.basket.size == 1 }
        assertEquals(1, lib.removed.size)
    }

    @Test fun proLockedAndUnlocked() {
        val free = makeTestApp(ctx)
        rule.setContent { TestHost(free) { DuplicatesScreen(RecordingNav()) } }
        rule.onNodeWithText("GaleriQ Pro ansehen").assertExists()
        rule.onNodeWithText("Pro").assertExists()
    }

    @Test fun proUnlockedAllowsExactDuplicates() {
        val pro = makeTestApp(ctx, purchase = FakePurchaseGateway(PurchaseState.PURCHASED))
        rule.setContent { TestHost(pro) { DuplicatesScreen(RecordingNav()) } }
        rule.onNodeWithText("Kopien suchen").assertExists().performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Erneut suchen")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Keine identischen Kopien gefunden").assertExists()
        assertTrue(pro.capabilities.dailyCleanupLimit == null)
    }

    @Test fun settingsTogglePersists() {
        val app = makeTestApp(ctx)
        rule.setContent { TestHost(app) { SettingsScreen(RecordingNav()) } }
        assertTrue(app.settings.value.haptics)
        rule.onNodeWithText("Vibration bei Aktionen").performScrollTo().performClick()
        rule.waitUntil(5_000) { !app.settings.value.haptics }
        rule.onNodeWithText("Käufe sind in dieser Version noch nicht verfügbar.").assertDoesNotExist()
        rule.onNodeWithText("Hilfe").performScrollTo().assertExists()
        rule.onNodeWithText("0.1.0 (1)").performScrollTo().assertExists()
    }
}
