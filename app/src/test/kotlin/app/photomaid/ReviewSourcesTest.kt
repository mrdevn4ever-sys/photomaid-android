package app.photomaid

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.later.ReviewQueues
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.MediaKind
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.core.quickaccess.QuickAccess
import app.photomaid.core.quickaccess.QuickAccessItem
import app.photomaid.core.quickaccess.QuickSourceKind
import app.photomaid.feature.quickaccess.resolveSource
import app.photomaid.feature.quickaccess.statusFor
import app.photomaid.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Globale Identität über Quellen (Pflichttests A–J, §39): Quellen sind Filter auf eine Momentaufnahme,
 * der Zustand liegt einmal je Medium im echten Ledger. Deterministisch: feste Uhr (Mittwoch 12:00 Zürich).
 */
@RunWith(AndroidJUnit4::class)
class ReviewSourcesTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val zone = ZoneId.of("Europe/Zurich")
    private val now = Instant.parse("2026-09-30T10:00:00Z")
    private val app = makeTestApp(ctx)

    private fun photo(i: Int, ageMinutes: Long, folder: String = "DCIM/Camera/", kind: MediaKind = MediaKind.PHOTO) = MediaAsset(
        id = "content://media/external/images/media/$i", kind = kind, creationDate = now.minus(Duration.ofMinutes(ageMinutes)),
        duration = 0.0, pixelWidth = 3000, pixelHeight = 4000, size = KnownSize.Known(1000), mimeType = "image/jpeg",
        displayName = "P$i", isFavorite = false, relativePath = folder)

    private fun q(k: QuickSourceKind, f: String? = null) = QuickAccessItem(k, f)
    private val photos = q(QuickSourceKind.PHOTOS); private val latest = q(QuickSourceKind.LATEST_PHOTOS)
    private val week = q(QuickSourceKind.THIS_WEEK); private val album = q(QuickSourceKind.FOLDER, "Pictures/Urlaub/")

    private fun act(ref: ItemReference, a: DecisionAction) = runBlocking(Dispatchers.IO) {
        app.ledger.apply(DecisionCommand(item = ref, expectedRevision = app.ledger.record(ref).revision, action = a))
    }
    private fun open(all: List<MediaAsset>) = runBlocking(Dispatchers.IO) { app.pendingQueue(all).map { it.localIdentifier }.toSet() }
    private fun pendingIn(src: QuickAccessItem, all: List<MediaAsset>) = open(all).let { o -> resolveSource(src, all, now, zone).filter { it.id in o }.map { it.id } }
    private fun events() = app.ledger.stateFlow.value.events

    // X: neuestes Foto, diese Woche, im Ordner „Urlaub“.
    private val x = photo(1, 5, folder = "Pictures/Urlaub/")
    private val lib = listOf(x, photo(2, 60), photo(3, 60 * 24 * 40), photo(4, 30, folder = "Pictures/Urlaub/"))

    @Test fun testA_keepInLatestRemovesFromAllSourcesWithOneEvent() {
        for (s in listOf(photos, latest, week, album)) assertTrue(x.id in pendingIn(s, lib))
        act(x.reference, DecisionAction.Keep)
        for (s in listOf(photos, latest, week, album)) assertFalse("$s", x.id in pendingIn(s, lib))
        assertEquals(1, events().size)
    }

    @Test fun testB_trashInAlbumRemovesEverywhereNothingDeleted() {
        act(x.reference, DecisionAction.MarkForCleanup)
        for (s in listOf(photos, latest, album)) assertFalse(x.id in pendingIn(s, lib))
        assertEquals(Disposition.MARKED_FOR_CLEANUP, app.ledger.stateFlow.value.records[x.reference]!!.disposition)
        runBlocking { app.reload() }
        assertTrue(app.read.value.basket.any { it.item == x.reference })
        assertTrue(app.ledger.stateFlow.value.intents.isEmpty()) // kein Lösch-Vorgang beim Wischen
    }

    @Test fun testC_laterHidesUntilDueThenReviewableAgain() {
        val due = app.today.addingDays(2)
        act(x.reference, DecisionAction.Later(ReviewSessionID())); act(x.reference, DecisionAction.Schedule(due))
        for (s in listOf(photos, week)) assertFalse(x.id in pendingIn(s, lib))
        runBlocking { app.reload() }
        assertTrue(app.read.value.laterRecords.any { it.item == x.reference })
        // Fällig (bestehende Später-Logik): kommt über „wieder dran“ zurück in die Prüfung.
        assertTrue(x.reference in ReviewQueues.dueQueue(app.ledger.stateFlow.value.records.values, ReviewSessionID(), due))
        assertFalse(x.reference in ReviewQueues.dueQueue(app.ledger.stateFlow.value.records.values, ReviewSessionID(), app.today))
    }

    @Test fun testD_undoRestoresEverywhere() {
        act(x.reference, DecisionAction.Keep)
        runBlocking(Dispatchers.IO) { app.undo() }
        for (s in listOf(photos, latest, week, album)) assertTrue(x.id in pendingIn(s, lib))
    }

    @Test fun testE_heroCountsDistinctMedia() {
        val one = listOf(x)
        for (s in listOf(photos, latest, week, album)) assertEquals(listOf(x.id), pendingIn(s, one))
        assertEquals(1, open(one).size) // 1 Medium, nicht 4
    }

    @Test fun testF_G_newSourceRespectsExistingKeepAndLater() {
        val y = lib[3] // im Ordner Urlaub
        act(x.reference, DecisionAction.Keep)
        act(y.reference, DecisionAction.Later(ReviewSessionID())); act(y.reference, DecisionAction.Schedule(app.today.addingDays(5)))
        val added = QuickAccess.add(QuickAccess.defaults, album)
        val st = statusFor(added, lib, open(lib), now, zone)
        assertEquals(2, st.getValue(album.key).members)
        assertEquals(0, st.getValue(album.key).pending)
    }

    @Test fun testH_externallyDeletedDisappearsEverywhere() {
        val without = lib - x
        for (s in listOf(photos, latest, week, album)) assertFalse(x.id in resolveSource(s, without, now, zone).map { it.id })
    }

    @Test fun testI_newPhotoJoinsSourcesAsOneIdentity() {
        val n = photo(99, 1)
        val more = lib + n
        for (s in listOf(photos, latest, week)) assertTrue(n.id in pendingIn(s, more))
        assertEquals(lib.size + 1, open(more).size)
    }

    /**
     * §39 (mit der echten Grenze „Neueste Fotos“ = 50): 100 Fotos – 80 Behalten, 5 Papierkorb, 5 Später, 10 offen.
     * 0..29: diese Woche (Minuten alt) · 30..49: 10 Tage alt · Rest 40 Tage alt → Woche = 0..29, Neueste = 0..49.
     */
    @Test fun section39_countsAreOpenNotTotalAndHeroDedupes() {
        assertEquals(50, QuickAccess.LATEST_PHOTOS_LIMIT)
        val all = (0 until 100).map { i -> photo(1000 + i, when { i < 30 -> i.toLong(); i < 50 -> 60L * 24 * 10 + i; else -> 60L * 24 * 40 + i }) }
        val unreviewed = setOf(0, 5, 10, 35, 45, 60, 70, 80, 90, 95) // Woche: 3 · Neueste: dieselben 3 + 2 · gesamt 10
        val rest = (0 until 100).filter { it !in unreviewed }
        rest.take(5).forEach { act(all[it].reference, DecisionAction.MarkForCleanup) }
        rest.drop(5).take(5).forEach { i -> act(all[i].reference, DecisionAction.Later(ReviewSessionID())); act(all[i].reference, DecisionAction.Schedule(app.today.addingDays(3))) }
        rest.drop(10).forEach { act(all[it].reference, DecisionAction.Keep) }
        val st = statusFor(listOf(photos, latest, week), all, open(all), now, zone)
        assertEquals(10, st.getValue(photos.key).pending); assertEquals(100, st.getValue(photos.key).members)
        assertEquals(5, st.getValue(latest.key).pending); assertEquals(50, st.getValue(latest.key).members)
        assertEquals(3, st.getValue(week.key).pending); assertEquals(30, st.getValue(week.key).members)
        assertEquals(10, open(all).size) // Hero: verschiedene offene Medien, keine Summe der Quellen
    }

    /** Gelöschter/ungültiger Ordner: Quelle ist leer (nicht „alles geprüft“ erfunden), nichts stürzt ab. */
    @Test fun missingFolderIsEmpty() {
        val st = statusFor(listOf(q(QuickSourceKind.FOLDER, "Pictures/Gibtsnicht/")), lib, open(lib), now, zone)
        assertEquals(0, st.values.single().members)
    }

    /** Migration: bestehende Installation ohne Eintrag → Fotos + Videos; andere Daten unberührt. */
    @Test fun upgradeGetsDefaultsWithoutTouchingDecisions() {
        act(x.reference, DecisionAction.Keep)
        val before = app.ledger.stateFlow.value.records
        runBlocking(Dispatchers.IO) { app.quickAccess.load() }
        assertEquals(QuickAccess.defaults, app.quickAccess.items.value)
        runBlocking { app.quickAccess.update { QuickAccess.add(it, album) } }
        assertEquals(before, app.ledger.stateFlow.value.records) // Konfiguration ändert keine Domain-Daten
    }
}
