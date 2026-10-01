package app.photomaid

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.cleanup.CleanupOperationKind
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.model.ItemReference
import app.photomaid.feature.cleanup.CleanupBasketScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Papierkorb: große Vorschau ohne Auswahländerung, Tageslimit VOR der Bestätigung. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BasketTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var lib: FakeMediaLibrary

    private suspend fun AppModel.mark(ref: ItemReference) =
        ledger.apply(DecisionCommand(item = ref, expectedRevision = ledger.record(ref).revision, action = DecisionAction.MarkForCleanup))

    /** `used` Medien heute schon bestätigt entfernt, danach `marked` weitere im Papierkorb. */
    private fun app(used: Int, marked: Int): AppModel = makeTestApp(ctx, media = { FakeMediaLibrary(it, count = used + marked, videos = 1).also { l -> lib = l } }) { a ->
        val refs = lib.all.map { ItemReference.photo(it.id) }
        if (used > 0) {
            refs.take(used).forEach { a.mark(it) }
            a.runCleanup(refs.take(used), CleanupOperationKind.PHOTO_LIBRARY_DELETE)
        }
        refs.drop(used).take(marked).forEach { a.mark(it) }
    }

    private fun waitText(t: String) = rule.waitUntil(5_000) { rule.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun previewShowsMediumLargeAndKeepsSelection() {
        val app = app(0, 2)
        rule.setContent { TestHost(app) { CleanupBasketScreen(RecordingNav(), showsDone = true) } }
        waitText("Papierkorb leeren: 2 Fotos/Videos")
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("basket.thumb").fetchSemanticsNodes().size == 2 }
        rule.onAllNodesWithTag("basket.thumb").onFirst().performClick()
        rule.onNodeWithTag("basket.preview").assertExists()
        // Das Foto ist geladen (kein hängender Ladekreis).
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("feed.media.ready", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Nur ansehen – deine Auswahl bleibt, wie sie ist.").assertExists()
        rule.onNodeWithContentDescription("Schließen").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("basket.preview").fetchSemanticsNodes().isEmpty())
        // Auswahl und Papierkorb unverändert.
        rule.onNodeWithText("Papierkorb leeren: 2 Fotos/Videos").assertExists()
        assertEquals(2, app.read.value.basket.size)
        assertTrue(lib.removed.isEmpty())
    }

    @Test fun videoPreviewOpens() {
        val app = app(0, 0).also { a ->
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { a.mark(ItemReference.photo(lib.all.last().id)); a.reload() }
        }
        rule.setContent { TestHost(app) { CleanupBasketScreen(RecordingNav(), showsDone = true) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("basket.thumb").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("basket.thumb").performClick()
        rule.onNodeWithTag("basket.preview").assertExists()
        assertEquals(2, rule.onAllNodes(hasText("Video")).fetchSemanticsNodes().size) // Zeile + Vorschau-Titel
    }

    /** Auswahl > Rest-Limit: Erklärung vor jeder Bestätigung; Teilmenge führt in die normale Bestätigung. */
    @Test fun partialLimitIsExplainedBeforeConfirmation() {
        val app = app(used = 18, marked = 4)
        rule.setContent { TestHost(app) { CleanupBasketScreen(RecordingNav(), showsDone = true) } }
        waitText("Papierkorb leeren: 4 Fotos/Videos")
        rule.onNodeWithText("Papierkorb leeren: 4 Fotos/Videos").performClick()
        waitText("Heute noch 2 frei")
        rule.onNodeWithText("Du hast 4 ausgewählt. Ohne Pro kannst du pro Tag bis zu 20 endgültig entfernen. Der Rest bleibt sicher im Papierkorb.").assertExists()
        assertTrue(rule.onAllNodes(hasText("über Android entfernen", substring = true)).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("2 davon entfernen").performClick()
        waitText("2 über Android entfernen")
        rule.onNodeWithText("2 über Android entfernen").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("2 Medien wurden in den Papierkorb des Systems verschoben.")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, app.read.value.basket.size)
        assertEquals(20, lib.removed.size) // 18 vorher + 2 jetzt, nie mehr als das Limit
    }

    @Test fun exhaustedLimitOffersProInsteadOfDeadEnd() {
        val app = app(used = 20, marked = 1)
        val nav = RecordingNav()
        rule.setContent { TestHost(app) { CleanupBasketScreen(nav, showsDone = true) } }
        waitText("Papierkorb leeren: 1 Foto/Video")
        rule.onNodeWithText("Papierkorb leeren: 1 Foto/Video").performClick()
        waitText("Tageslimit für heute erreicht")
        rule.onNodeWithText("Morgen ist das Limit wieder frei.").assertExists()
        rule.onNodeWithText("Alle 1 entfernen – mit Pro").performClick()
        assertEquals("pro", nav.events.last())
        assertEquals(Disposition.MARKED_FOR_CLEANUP, app.read.value.basket.single().disposition)
    }
}
