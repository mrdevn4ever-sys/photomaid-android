package app.photomaid.core

import app.photomaid.core.share.LibraryFingerprint
import app.photomaid.core.share.ShareAction
import app.photomaid.core.share.ShareActions
import app.photomaid.core.share.ShareIntake
import app.photomaid.core.share.ShareMatch
import app.photomaid.core.share.ShareRules
import app.photomaid.core.share.SharedItem
import app.photomaid.core.share.ShortcutTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTest {
    @Test fun acceptsOnlyPhotosAndVideos() {
        assertTrue(ShareRules.accepts("image/jpeg")); assertTrue(ShareRules.accepts("VIDEO/mp4"))
        assertFalse(ShareRules.accepts("application/pdf")); assertFalse(ShareRules.accepts(null)); assertFalse(ShareRules.accepts("text/plain"))
    }

    @Test fun matchNeedsNameAndSizeAndNeverGuesses() {
        val lib = listOf(LibraryFingerprint("a", "IMG_1.jpg", 100), LibraryFingerprint("b", "img_2.jpg", 200), LibraryFingerprint("c", "img_2.jpg", 200))
        assertEquals("a", ShareMatch.unique("img_1.JPG", 100, lib))
        assertNull(ShareMatch.unique("img_2.jpg", 200, lib))
        assertNull(ShareMatch.unique("IMG_1.jpg", null, lib))
        assertNull(ShareMatch.unique("IMG_1.jpg", 0, lib))
        assertNull(ShareMatch.unique(null, 100, lib))
        assertNull(ShareMatch.unique("IMG_1.jpg", 101, lib))
    }

    @Test fun intakeCountsRejectedUnmatchedAndDeduplicates() {
        val items = listOf(SharedItem("u1", "image/jpeg"), SharedItem("u2", "application/pdf"), SharedItem("u3", "video/mp4"),
            SharedItem("u1b", "image/jpeg"), SharedItem("u4", "image/png"))
        val ids = mapOf("u1" to "m1", "u1b" to "m1", "u3" to "m3")
        val r = ShareIntake.of(items) { ids[it.uri] }
        assertEquals(listOf("m1", "m3"), r.matched)
        assertEquals(1, r.unmatched); assertEquals(1, r.rejected); assertEquals(0, r.overflow)
    }

    @Test fun intakeCapsAtMax() {
        val items = (0 until ShareRules.MAX_ITEMS + 3).map { SharedItem("u$it", "image/jpeg") }
        val r = ShareIntake.of(items) { it.uri }
        assertEquals(ShareRules.MAX_ITEMS, r.matched.size); assertEquals(3, r.overflow)
    }

    @Test fun actionsNeverIncludeADecision() {
        assertEquals(emptyList<ShareAction>(), ShareActions.available(0))
        assertTrue(ShareAction.EDIT in ShareActions.available(1))
        assertFalse(ShareAction.EDIT in ShareActions.available(2))
        // Es gibt keine Aktion Behalten/Papierkorb/Löschen.
        assertEquals(setOf("WORKFLOW", "EDIT", "PDF", "ALBUM", "BASKET"), ShareAction.entries.map { it.name }.toSet())
    }

    @Test fun shortcutIdsRoundTrip() {
        for (t in ShortcutTarget.fixed + ShortcutTarget.Workflow("abc")) assertEquals(t, ShortcutTarget.parse(t.id))
        assertNull(ShortcutTarget.parse("delete")); assertNull(ShortcutTarget.parse("wf:")); assertNull(ShortcutTarget.parse(null))
    }

    @Test fun shortcutPlanRespectsLimits() {
        val p = ShortcutTarget.plan(listOf("1", "2", "3", "4", "5", "1"), max = 15)
        assertEquals(4 + 4, p.size)
        assertEquals(ShortcutTarget.fixed, p.take(4))
        assertEquals(listOf("1", "2", "3", "4"), p.drop(4).map { (it as ShortcutTarget.Workflow).workflowId })
        assertEquals(5, ShortcutTarget.plan(listOf("1", "2"), max = 5).size)
        assertEquals(3, ShortcutTarget.plan(listOf("1"), max = 3).size)
    }
}
