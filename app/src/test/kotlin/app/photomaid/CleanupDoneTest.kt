package app.photomaid

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.cleanup.OperationOutcome
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.model.ItemReference
import app.photomaid.feature.cleanup.CleanupBasketScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** „Gespart“-Bildschirm nach dem Leeren: nur echte Größen entfernter Elemente, Summe insgesamt, ehrlicher Hinweis. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "+w393dp-h852dp-xxhdpi")
class CleanupDoneTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private suspend fun AppModel.mark(ref: ItemReference) =
        ledger.apply(DecisionCommand(item = ref, expectedRevision = ledger.record(ref).revision, action = DecisionAction.MarkForCleanup))
    private fun has(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun showsSavedBytesOfRemovedItemsAndTotal() {
        lateinit var lib: FakeMediaLibrary
        val app = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = 3, videos = 0).also { l -> lib = l } }) { a ->
            a.mark(ItemReference.photo(lib.all[0].id)); a.mark(ItemReference.photo(lib.all[1].id))
            // Ein Behalten heute.
            val k = ItemReference.photo(lib.all[2].id)
            a.ledger.apply(DecisionCommand(item = k, expectedRevision = a.ledger.record(k).revision, action = DecisionAction.Keep))
        }
        val nav = RecordingNav()
        rule.setContent { TestHost(app) { CleanupBasketScreen(nav, showsDone = true) } }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("2 Fotos und Videos entfernen")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("2 Fotos und Videos entfernen").performClick()
        rule.onNodeWithText("2 entfernen").performClick()
        rule.waitUntil(10_000) { has("done") }
        rule.mainClock.advanceTimeBy(2_000)
        // FakeMediaLibrary: 2,4 MB + 2,5 MB = 4,9 MB, nur die beiden entfernten.
        rule.onNodeWithTag("done.saved").assertExists()
        rule.onNodeWithText("4,9").assertExists(); rule.onNodeWithText("MB").assertExists()
        rule.onNodeWithText("aussortiert").assertExists()
        rule.onNodeWithText("2 Fotos und Videos liegen jetzt im Android-Papierkorb.").assertExists()
        rule.onNodeWithText("Papierkorb geleert").assertExists()
        rule.onNodeWithText("4,9 MB insgesamt aussortiert").assertExists()
        rule.onNodeWithText("Fotos und Videos liegen jetzt im Android-Papierkorb. Speicher wird erst frei, wenn sie dort endgültig gelöscht werden.").assertExists()
        assertEquals(4_900_000L, app.settings.value.savedTotalBytes)
        assertFalse(app.settings.value.savedTotalLowerBound)
        rule.onNodeWithTag("done.button").performClick()
        assertEquals("back", nav.events.last())
    }

    @Test fun cancelledDialogShowsNoSavings() {
        lateinit var lib: FakeMediaLibrary
        val app = makeTestApp(ctx, media = { FakeMediaLibrary(it).also { l -> lib = l; l.trashOutcome = { OperationOutcome.Cancelled } } }) { a ->
            a.mark(ItemReference.photo(lib.all[0].id))
        }
        rule.setContent { TestHost(app) { CleanupBasketScreen(RecordingNav(), showsDone = true) } }
        rule.onNodeWithText("1 Foto oder Video entfernen").performClick()
        rule.onNodeWithText("1 entfernen").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Abgebrochen. Nichts wurde entfernt.")).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(has("done"))
        assertEquals(0L, app.settings.value.savedTotalBytes)
        assertTrue(true)
    }
}
