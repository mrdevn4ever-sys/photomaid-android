package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.archive.BackupService
import app.photomaid.archive.FileArchiveTarget
import app.photomaid.core.archive.TargetKind
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.KnownSize
import app.photomaid.feature.archive.ArchiveRequest
import app.photomaid.feature.archive.ArchiveScreen
import app.photomaid.feature.archive.BackupScreen
import app.photomaid.feature.archive.RestorePreview
import app.photomaid.feature.library.LibraryScreen
import app.photomaid.feature.settings.SettingsScreen
import app.photomaid.feature.studio.StudioScreen
import app.photomaid.media.reviewableMedia
import app.photomaid.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w393dp-h2400dp")
class ArchiveUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()

    private fun start(count: Int = 4, pro: Boolean = false) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = count, videos = 1).also { l -> lib = l
            l.all.forEach { a -> l.sizes[a.id] = KnownSize.Known(File(a.id.removePrefix("file://")).length()) } } },
            purchase = if (pro) FakePurchaseGateway(PurchaseState.PURCHASED) else null)
    }
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
    private fun click(tag: String) {
        runCatching { rule.onNodeWithTag(tag).performScrollTo() } // nur, wo es etwas zu scrollen gibt
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); pumpUntil { true }
    }
    private fun has(t: String) = rule.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun target(kind: TargetKind, available: Long? = null) =
        FileArchiveTarget(File(rule.activity.cacheDir, "sd-${System.nanoTime()}"), kind, "SD-Karte › Archiv", available).also { app.archive.testTarget = it }
    private fun records() = app.ledger.stateFlow.value.records

    @Test fun copyAndCheckThenOfferTrashOnlyOnRequest() {
        start()
        val assets = lib.reviewableMedia()
        lib.favorites[assets[0].id] = true
        target(TargetKind.REMOVABLE)
        rule.setContent { TestHost(app) { ArchiveScreen(ArchiveRequest("r", assets.map { it.id }), nav, onClose = {}) } }
        pumpUntil { count("archive.kind.REMOVABLE") == 1 }
        assertTrue(has("SD-Karte › Archiv"))
        assertEquals(1, count("archive.space.UNKNOWN")) // freier Platz unbekannt → nie „passt“ behaupten
        assertTrue(has("Byte für Byte"))
        click("archive.start")
        pumpUntil { count("archive.summary") == 1 }
        assertEquals(assets.size, count("archive.entry.VERIFIED"))
        assertTrue(records().isEmpty()) // Kopieren ist keine Entscheidung
        click("archive.cleanup")
        pumpUntil { has("PhotoMaid-Papierkorb legen?") }
        assertTrue(has("1 Favorit bleibt"))
        rule.onAllNodes(hasText("In den Papierkorb legen") and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        pumpUntil { count("archive.cleaned") == 1 }
        val marked = records().filterValues { it.disposition == Disposition.MARKED_FOR_CLEANUP }.keys
        assertEquals(assets.drop(1).map { ItemReference.photo(it.id) }.toSet(), marked)
        assertTrue(lib.trashed.isEmpty()) // nichts gelöscht
        click("archive.basket"); assertTrue("push:${Routes.BASKET_MODAL}" in nav.events)
    }

    @Test fun sameDeviceWarnsAndOffersNoTrash() {
        start(count = 2)
        target(TargetKind.SAME_DEVICE)
        rule.setContent { TestHost(app) { ArchiveScreen(ArchiveRequest("r", lib.reviewableMedia().map { it.id }), nav, onClose = {}) } }
        pumpUntil { count("archive.kind.SAME_DEVICE") == 1 }
        assertTrue(has("demselben Speicher"))
        click("archive.start")
        pumpUntil { count("archive.summary") == 1 }
        assertEquals(1, count("archive.nocleanup"))
        assertEquals(0, count("archive.cleanup"))
    }

    @Test fun notEnoughSpaceBlocksTheStartAndFreeIsLimited() {
        start(count = 12)
        target(TargetKind.REMOVABLE, available = 10)
        rule.setContent { TestHost(app) { ArchiveScreen(ArchiveRequest("r", lib.reviewableMedia().map { it.id }), nav, onClose = {}) } }
        pumpUntil { count("archive.space.NOT_ENOUGH") == 1 }
        assertTrue(has("Nicht genug Platz"))
        rule.onNodeWithTag("archive.start").assertIsNotEnabled()
        assertEquals(1, count("archive.limit")) // Free: höchstens 10 je Durchgang
        assertTrue(has("10 Fotos und Videos"))
    }

    @Test fun entriesFromStudioSelectionAndAlbumOpenArchiving() {
        start()
        rule.setContent { TestHost(app) { StudioScreen(nav) } }
        pumpUntil { count("studio.archive") == 1 }
        click("studio.archive")
        val pick = nav.picks.single()
        assertTrue(pick.photos && pick.videos)
        val ids = lib.reviewableMedia().take(2).map { it.id }
        pick.onDone(ids); pumpUntil { nav.archives.isNotEmpty() }
        assertEquals(ids, nav.archives.single().assetIds)
        assertTrue(records().isEmpty())
    }

    @Test fun selectionMenuOpensArchiving() {
        start()
        rule.setContent { TestHost(app) { LibraryScreen(nav) } }
        pumpUntil { count("lib.tile") > 0 }
        rule.onAllNodesWithTag("lib.tile")[0].performSemanticsAction(SemanticsActions.OnLongClick); pumpUntil { count("lib.selbar") == 1 }
        click("lib.sel.more"); pumpUntil { count("lib.sel.archive") == 1 }
        click("lib.sel.archive"); pumpUntil { nav.archives.isNotEmpty() }
        assertEquals(1, nav.archives.single().assetIds.size)
    }

    @Test fun backupPageShowsContentsAndRestorePreviewAppliesWithoutOverwriting() {
        start(count = 3)
        val a = lib.reviewableMedia()
        runBlocking(Dispatchers.IO) {
            app.decide(a[0].reference, DecisionAction.Keep)
            app.organize.createAlbum("Ferien", listOf(a[1]))
        }
        content.value = { SettingsScreen(nav) }
        rule.setContent { TestHost(app) { content.value?.invoke() } }
        pumpUntil { count("settings.backup") == 1 }
        click("settings.backup"); assertTrue("push:${Routes.BACKUP}" in nav.events)
        val b = runBlocking { BackupService.create(app) }
        // Wiederherstellen auf demselben Gerät: alles schon da → nichts doppelt.
        val plan = runBlocking { BackupService.plan(app, b) }
        assertEquals(1, plan.decisionsKeptLocal); assertTrue(plan.decisions.isEmpty())
        content.value = { BackupScreen(nav) }
        pumpUntil { count("backup.contains") == 1 }
        assertTrue(has("1 Entscheidung") && has("1 Album"))
        assertTrue(has("Dateinamen, Schlagwörter und Notizen"))
        // Vorschau mit einem Unentschiedenen: Entscheidung wird übernommen.
        runBlocking(Dispatchers.IO) { app.undo() }
        val plan2 = runBlocking { BackupService.plan(app, b) }
        assertEquals(1, plan2.decisions.size)
        var result: app.photomaid.archive.RestoreResult? = null
        content.value = { RestorePreview(app, b, plan2, applying = null, restored = result) {
            result = runBlocking { BackupService.apply(app, b, plan2) }
        } }
        pumpUntil { count("backup.apply") == 1 }
        assertTrue(has("1 Entscheidung wird übernommen"))
        click("backup.apply")
        assertEquals(Disposition.KEPT, records()[a[0].reference]?.disposition)
        assertEquals(1, app.organize.state.value.albums.size) // gleiches Album ergänzt, nicht verdoppelt
    }

    private val content = androidx.compose.runtime.mutableStateOf<(@androidx.compose.runtime.Composable () -> Unit)?>(null)
}
