package app.photomaid

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.batch.Workflow
import app.photomaid.core.batch.WorkflowFormat
import app.photomaid.core.share.SharedItem
import app.photomaid.core.share.ShortcutTarget
import app.photomaid.feature.organize.OrganizeTarget
import app.photomaid.feature.share.ShareScreen
import app.photomaid.feature.share.resolveShare
import app.photomaid.navigation.openEntry
import app.photomaid.system.AppEntry
import app.photomaid.system.AppShortcuts
import app.photomaid.system.EntryRequest
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

/**
 * Teilen-Ziel und App-Shortcuts: Ein Einstieg von außen öffnet nur Ansichten. Keine Entscheidung (Ledger leer), keine
 * Datei verändert, Zuordnung nur eindeutig (ID oder Name + Größe).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w393dp-h852dp")
class ShareEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()

    private fun start() { app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 6, videos = 1).also { l -> lib = l } }) }
    private fun pumpUntil(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        rule.mainClock.autoAdvance = false
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(5)
            if (cond()) break
            check(System.currentTimeMillis() < end) { "Bedingung nicht erfüllt" }
        }
        rule.mainClock.autoAdvance = true
    }
    private fun count(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun click(tag: String) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true } }
    /** Übergabe, wie sie ein fremder Anbieter liefert: eigene URI, Name + Größe des Mediums. */
    private fun shared(n: Int) = lib.all.take(n).mapIndexed { i, a -> SharedItem("content://other.app/item/$i", a.mimeType, a.displayName, a.size.bytesOrNull) }
    private fun assertUntouched() {
        assertTrue("Ledger muss leer bleiben", app.ledger.stateFlow.value.records.isEmpty())
        assertTrue(lib.trashed.isEmpty()); assertTrue(lib.deleted.isEmpty()); assertTrue(lib.removed.isEmpty())
    }

    @Test fun resolvesByNameAndSizeAndCountsTheRest() = runBlocking<Unit> {
        start()
        val items = shared(2) + SharedItem("content://other.app/x", "image/jpeg", "fremd.jpg", 123) + SharedItem("content://other.app/doc", "application/pdf", "a.pdf", 5)
        val (intake, assets) = resolveShare(app, items)
        assertEquals(lib.all.take(2).map { it.id }, assets.map { it.id })
        assertEquals(1, intake.unmatched); assertEquals(1, intake.rejected)
    }

    @Test fun unknownSizeNeverMatches() = runBlocking<Unit> {
        start()
        val a = lib.all.first()
        val (intake, assets) = resolveShare(app, listOf(SharedItem("content://other.app/1", a.mimeType, a.displayName, null)))
        assertTrue(assets.isEmpty()); assertEquals(1, intake.unmatched)
    }

    @Test fun mediaStoreUrisMapToLibraryIds() {
        assertEquals(listOf("content://media/external/images/media/42"), AppEntry.libraryCandidates("content://media/external_primary/images/media/42"))
        assertEquals(listOf("content://media/external/video/media/7"), AppEntry.libraryCandidates("content://media/external/video/media/7"))
        assertEquals(2, AppEntry.libraryCandidates("content://media/external/file/9").size)
        assertTrue(AppEntry.libraryCandidates("content://com.other/images/media/1").isEmpty())
    }

    @Test fun readsSendIntentsAndShortcutIntents() {
        start()
        val ctx = rule.activity
        val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://other.app/p/1"))
            .putExtra(Intent.EXTRA_SHORTCUT_ID, "wf:abc")
        val r = AppEntry.read(ctx, send) as EntryRequest.Share
        assertEquals(1, r.items.size); assertEquals("image/jpeg", r.items[0].mime); assertEquals("abc", r.workflowId)
        val multi = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Uri.parse("content://other.app/p/1"), Uri.parse("content://other.app/p/2"), Uri.parse("file:///sdcard/x.jpg")))
        val m = AppEntry.read(ctx, multi) as EntryRequest.Share
        assertEquals("file:// wird nicht angenommen", 2, m.items.size)
        assertNull("Platzhalter-Typ ist kein MIME", m.items[0].mime)
        assertNull(m.workflowId)
        for (t in ShortcutTarget.fixed) assertEquals(EntryRequest.Shortcut(t), AppEntry.read(ctx, AppEntry.shortcutIntent(ctx, t)))
        assertNull(AppEntry.read(ctx, Intent(AppEntry.ACTION_SHORTCUT).putExtra(AppEntry.EXTRA_TARGET, "delete")))
        assertNull(AppEntry.read(ctx, Intent(Intent.ACTION_DELETE)))
        assertNull(AppEntry.read(ctx, Intent(Intent.ACTION_SEND).setType("image/jpeg")))
    }

    @Test fun pendingEntryGoesOnlyToItsWindowAndOnlyOnce() {
        val r = EntryRequest.Shortcut(ShortcutTarget.Due)
        AppEntry.post(1, r)
        assertNull("anderes Fenster (z. B. PhotoMaid im Hintergrund) holt nicht ab", AppEntry.take(2))
        assertEquals(r, AppEntry.take(1))
        assertNull(AppEntry.take(1))
    }

    @Test fun shortcutsOpenOnlyViews() = runBlocking<Unit> {
        start()
        openEntry(app, nav, EntryRequest.Shortcut(ShortcutTarget.Clean))
        openEntry(app, nav, EntryRequest.Shortcut(ShortcutTarget.Due))
        openEntry(app, nav, EntryRequest.Shortcut(ShortcutTarget.Basket))
        openEntry(app, nav, EntryRequest.Shortcut(ShortcutTarget.Workflows))
        openEntry(app, nav, EntryRequest.Shortcut(ShortcutTarget.Workflow("gibt-es-nicht")))
        app.workflows.upsert(Workflow("w1", "Kleinanzeigen", WorkflowFormat.JPEG, longEdge = 2048))
        openEntry(app, nav, EntryRequest.Shortcut(ShortcutTarget.Workflow("w1")))
        assertEquals(listOf(FeedRequest.Source.AllMedia, FeedRequest.Source.Due), nav.requests.map { it.source })
        assertEquals(listOf<OrganizeTarget>(OrganizeTarget.Basket), nav.organize)
        assertEquals(2, nav.workflowLists.size); assertTrue(nav.workflowLists.all { it.assetIds == null })
        assertEquals(1, nav.picks.size)
        assertUntouched()
    }

    @Test fun shareScreenOffersActionsAndBasketIsNoDecision() {
        start()
        val req = EntryRequest.Share(shared(2), null)
        rule.setContent { TestHost(app) { ShareScreen(req, nav) {} } }
        pumpUntil { count("share.actions") == 1 }
        assertEquals(1, count("share.count"))
        assertEquals("Bearbeiten nur für genau ein Medium", 0, count("share.edit"))
        click("share.workflow")
        assertEquals(lib.all.take(2).map { it.id }, nav.workflowLists.single().assetIds)
        click("share.pdf")
        assertEquals(lib.all.take(2).map { it.id }, nav.pdfs.single().assetIds)
        click("share.basket"); pumpUntil { app.organize.state.value.basket.size == 2 }
        assertUntouched()
    }

    @Test fun singleShareCanBeEdited() {
        start()
        rule.setContent { TestHost(app) { ShareScreen(EntryRequest.Share(shared(1), null), nav) {} } }
        pumpUntil { count("share.edit") == 1 }
        click("share.edit")
        assertEquals("editor:${lib.all.first().id}", nav.events.last())
        assertUntouched()
    }

    @Test fun directShareToWorkflowOpensPreviewNotRun() {
        start()
        runBlocking { app.workflows.upsert(Workflow("w1", "Klein", WorkflowFormat.JPEG, longEdge = 2048)) }
        var closed = false
        rule.setContent { TestHost(app) { ShareScreen(EntryRequest.Share(shared(3), "w1"), nav) { closed = true } } }
        pumpUntil { nav.workflowRuns.isNotEmpty() }
        assertTrue(closed)
        assertEquals("w1", nav.workflowRuns.single().workflowId)
        assertEquals(lib.all.take(3).map { it.id }, nav.workflowRuns.single().assetIds)
        assertUntouched()
    }

    @Test fun nothingInLibraryShowsHonestNote() {
        start()
        rule.setContent { TestHost(app) { ShareScreen(EntryRequest.Share(listOf(SharedItem("content://x/1", "image/png", "fremd.png", 10)), null), nav) {} } }
        pumpUntil { count("share.none") == 1 }
        assertEquals(1, count("share.unmatched")); assertEquals(0, count("share.actions"))
    }

    @Test fun shortcutsFollowWorkflowsWithoutMediaData() = runBlocking<Unit> {
        start()
        val ctx = rule.activity
        AppShortcuts.sync(ctx, listOf(Workflow("a", "Kleinanzeigen"), Workflow("b", "Familie")))
        val dyn = ShortcutManagerCompat.getDynamicShortcuts(ctx)
        val ids = dyn.map { it.id }
        assertTrue(ids.containsAll(listOf("clean", "due", "basket", "workflows", "wf:a", "wf:b")))
        val wf = dyn.first { it.id == "wf:a" }
        assertEquals("Kleinanzeigen", wf.shortLabel.toString())
        assertTrue(AppShortcuts.CATEGORY_WORKFLOW_SHARE in wf.categories.orEmpty())
        for (s in dyn) {
            val extras = s.intent.extras!!
            assertEquals(setOf(AppEntry.EXTRA_TARGET), extras.keySet())
            assertNull(s.intent.data)
        }
        AppShortcuts.sync(ctx, listOf(Workflow("b", "Familie")))
        val after = ShortcutManagerCompat.getDynamicShortcuts(ctx).map { it.id }
        assertFalse("wf:a" in after); assertTrue("wf:b" in after)
    }
}
