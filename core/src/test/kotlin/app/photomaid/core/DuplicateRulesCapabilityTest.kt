package app.photomaid.core

import app.photomaid.core.model.LocalDay
import app.photomaid.core.collections.CollectionCandidate
import app.photomaid.core.collections.CollectionFilter
import app.photomaid.core.collections.CollectionSource
import app.photomaid.core.collections.SmartCollection
import app.photomaid.core.collections.SmartCollections
import app.photomaid.core.capabilities.Capabilities
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.capabilities.RewardError
import app.photomaid.core.capabilities.RewardLedger
import app.photomaid.core.capabilities.WorkUnitGrant
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.duplicates.DuplicateCandidate
import app.photomaid.core.duplicates.DuplicateFinder
import app.photomaid.core.duplicates.DuplicateGroup
import app.photomaid.core.model.ConnectedSourceID
import app.photomaid.core.model.ItemProvider
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.SizeTally






import app.photomaid.core.storage.DeviceCapacity
import app.photomaid.core.storage.StorageCategory
import app.photomaid.core.storage.StorageOverview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.math.abs

private val SOURCE = ConnectedSourceID(UUID.fromString("00000000-0000-0000-0000-000000000001"))
fun file(name: String) = ItemReference(ItemProvider.ConnectedSource(SOURCE), name)

/** Port von `DuplicateRulesCapabilityTests.swift` – Teil „Exakte Duplikate“. */
class DuplicateTest {
    @Test fun sameBytesDifferentName() {
        val src = MemoryByteSource()
        val payload = ByteArray(14 * 1024) { (it % 251).toByte() }
        src.contents[file("a.jpg")] = payload
        src.contents[file("Kopie von a.JPG")] = payload
        src.contents[file("b.bin")] = payload
        src.contents[file("c.jpg")] = payload.copyOf().also { it[100] = (it[100].toInt() xor 0xFF).toByte() }
        val size = KnownSize.Known(payload.size.toLong())
        val r = DuplicateFinder().scan(listOf("a.jpg", "Kopie von a.JPG", "b.bin", "c.jpg").map { DuplicateCandidate(file(it), size) }, src)
        assertEquals(1, r.groups.size)
        val g = r.groups.first()
        assertEquals(3, g.members.size)
        assertFalse(file("c.jpg") in g.members)

        val big = DuplicateGroup("x", 14_000_000, listOf(file("1"), file("2"), file("3")))
        assertEquals(42_000_000, big.totalBytes)
        assertEquals(28_000_000, big.extraCopiesBytes)
        val sel = big.defaultSelection(file("2"))
        assertTrue(big.isValidSelection(sel))
        assertEquals(28_000_000, big.selectedBytes(sel))
        assertFalse("Mindestens eine Kopie bleibt", big.isValidSelection(big.members.toSet()))
    }

    @Test fun largeFilesAreStreamed() {
        val src = MemoryByteSource()
        val n = 3 * 1024 * 1024 + 17
        val a = ByteArray(n) { 7 }
        val b = a.copyOf().also { it[n - 1] = 8 }
        src.contents[file("a")] = a
        src.contents[file("a2")] = a
        src.contents[file("b")] = b
        val finder = DuplicateFinder(chunkSize = 256 * 1024)
        val r = finder.scan(listOf("a", "a2", "b").map { DuplicateCandidate(file(it), KnownSize.Known(n.toLong())) }, src)
        assertEquals(listOf(listOf(file("a"), file("a2"))), r.groups.map { it.members })
        assertTrue(finder.bytesIdentical(file("a"), file("a2"), src))
        assertFalse(finder.bytesIdentical(file("a"), file("b"), src))
    }

    @Test fun specialCases() {
        val src = MemoryByteSource()
        src.contents[file("z1")] = ByteArray(0)
        src.contents[file("z2")] = ByteArray(0)
        src.contents[file("x")] = byteArrayOf(1, 2, 3)
        val r = DuplicateFinder().scan(listOf(
            DuplicateCandidate(file("z1"), KnownSize.Known(0)),
            DuplicateCandidate(file("z2"), KnownSize.Known(0)),
            DuplicateCandidate(file("u"), KnownSize.Unknown),
            DuplicateCandidate(ItemReference.photo("live"), KnownSize.Known(3), isComparable = false),
            DuplicateCandidate(file("x"), KnownSize.Known(3)),
            DuplicateCandidate(file("cloud"), KnownSize.Known(3)),
        ), src)
        assertTrue(r.groups.isEmpty())
        assertEquals(listOf(file("z1"), file("z2")), r.zeroByte)
        assertTrue(file("u") in r.notChecked)
        assertTrue(file("cloud") in r.notChecked)
        assertEquals(listOf(ItemReference.photo("live")), r.notComparable)
    }

    @Test fun aliasesAreNotCopies() {
        val src = MemoryByteSource()
        src.contents[file("a")] = byteArrayOf(1, 2, 3, 4)
        src.contents[file("alias-a")] = byteArrayOf(1, 2, 3, 4)
        val r = DuplicateFinder().scan(listOf(
            DuplicateCandidate(file("a"), KnownSize.Known(4), aliasKey = "res-1"),
            DuplicateCandidate(file("alias-a"), KnownSize.Known(4), aliasKey = "res-1"),
        ), src)
        assertTrue(r.groups.isEmpty())
    }

    @Test fun sha256Vector() {
        val src = MemoryByteSource()
        src.contents[file("abc")] = "abc".toByteArray()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DuplicateFinder().hash(file("abc"), src, null))
    }

    @Test fun scanIsCancellable() {
        val src = MemoryByteSource()
        for (i in 0 until 50) src.contents[file("f$i")] = ByteArray(200_000) { 1 }
        val candidates = (0 until 50).map { DuplicateCandidate(file("f$it"), KnownSize.Known(200_000)) }
        assertThrowsSuspend<CancellationException> { DuplicateFinder().scan(candidates, src, isCancelled = { true }) }
        Unit
    }

    /** Android-Zusatz (Masterprompt §46): 50 000 Metadaten, nur Kandidaten mit gleicher Größe werden gelesen. */
    @Test fun fiftyThousandEntriesHashOnlyCandidates() {
        val src = MemoryByteSource()
        val candidates = (0 until 50_000).map { DuplicateCandidate(file("m$it"), KnownSize.Known(1_000L + it)) } +
            listOf(DuplicateCandidate(file("dupA"), KnownSize.Known(5)), DuplicateCandidate(file("dupB"), KnownSize.Known(5)))
        src.contents[file("dupA")] = byteArrayOf(1, 2, 3, 4, 5)
        src.contents[file("dupB")] = byteArrayOf(1, 2, 3, 4, 5)
        val r = DuplicateFinder().scan(candidates, src)
        assertEquals(1, r.groups.size)
        assertEquals("kein Full-Hash ohne Kandidatenfilter", 2, src.opened)
    }
}

/** Sammlungen (ersetzen „Regeln & Routinen“, Produktentscheidung 2026-09-29): immer aktuell, kein Auslöser. */
class CollectionTest {
    private val tz: ZoneId = ZoneId.of("Europe/Zurich")
    private val now = TestClock.date("2026-06-10T09:00", tz.id)

    private fun cand(n: Int, daysOld: Long?, size: KnownSize = KnownSize.Known(1)) =
        CollectionCandidate(photo(n), daysOld?.let { now.minusSeconds(it * 86_400) }, size)

    @Test fun olderThan() {
        val c = SmartCollection(source = CollectionSource.SCREENSHOTS, filter = CollectionFilter.OlderThan(30))
        val r = SmartCollections.evaluate(c, listOf(cand(1, 45), cand(2, 5), cand(3, null)), emptyMap(), now, tz)
        assertEquals(listOf(photo(1)), r.items) // ohne Datum nicht enthalten
    }

    @Test fun largerThanExcludesDecidedAndUnknownButReportsUnknown() {
        val c = SmartCollection(source = CollectionSource.VIDEOS, filter = CollectionFilter.LargerThan(50_000_000))
        val kept = ItemRecord(photo(1), disposition = Disposition.KEPT)
        val r = SmartCollections.evaluate(c, listOf(
            cand(1, 1, KnownSize.Known(80_000_000)), cand(2, 1, KnownSize.Unknown), cand(3, 1, KnownSize.Known(60_000_000)),
            cand(4, 1, KnownSize.Known(50_000_000)), // genau 50 MB ist nicht „größer als“
        ), mapOf(photo(1) to kept), now, tz)
        assertEquals(listOf(photo(3)), r.items)
        assertEquals(60_000_000L, r.knownBytes) // nur bekannte Größen offener Elemente, nichts erfunden
        assertEquals(1, r.unknownSizeCount)
    }

    @Test fun alwaysCurrentNoTrigger() {
        // Dieselbe Sammlung liefert zu jeder Zeit den aktuellen Stand – keine Aufgaben, keine „einmal pro Tag“-Sperre.
        val c = SmartCollections.builtIns.first()
        val items = listOf(cand(1, 1, KnownSize.Known(200_000_000)))
        assertEquals(1, SmartCollections.evaluate(c, items, emptyMap(), now, tz).items.size)
        assertEquals(1, SmartCollections.evaluate(c, items, emptyMap(), now.plusSeconds(60), tz).items.size)
        val later = ItemRecord(photo(1), disposition = Disposition.LATER, dueDay = LocalDay.of(now, tz).addingDays(2))
        assertEquals(0, SmartCollections.evaluate(c, items, mapOf(photo(1) to later), now, tz).items.size)
    }

    @Test fun builtInsAreStableAndSizeChoicesFitMediaType() {
        assertTrue(SmartCollections.builtIns.all { it.builtIn })
        assertEquals(SmartCollections.builtIns.map { it.id }, SmartCollections.builtIns.map { it.id }.distinct())
        assertTrue(SmartCollections.sizeChoices(CollectionSource.PHOTOS).all { it <= 20_000_000 })
        assertTrue(SmartCollections.sizeChoices(CollectionSource.VIDEOS).first() >= 50_000_000)
    }
}

/** Port – Teil „Free, Pro, Rewarded“. */
class CapabilityTest {
    private val tz: ZoneId = ZoneId.of("Europe/Zurich")

    @Test fun purchaseStates() {
        for (state in PurchaseState.entries) {
            val caps = Capabilities.resolve(state, RewardLedger(), Instant.now())
            if (state == PurchaseState.PURCHASED) {
                assertTrue(caps.tier == Capabilities.Tier.LIFETIME_PRO && caps.dailyCleanupLimit == null && !caps.adsAllowed && caps.exactDuplicates)
            } else {
                assertTrue(caps.tier == Capabilities.Tier.FREE && caps.dailyCleanupLimit == 20 && !caps.exactDuplicates)
                assertEquals(state != PurchaseState.UNKNOWN, caps.adsAllowed)
            }
        }
    }

    @Test fun reward() {
        val now = TestClock.date("2026-06-10T09:00", tz.id)
        var (ledger, w) = RewardLedger().granting("r1", now, tz, PurchaseState.NOT_PURCHASED)
        assertEquals(w, ledger.granting("r1", now.plusSeconds(5), tz, PurchaseState.NOT_PURCHASED).second)
        assertThrowsSuspend<RewardError.DailyUnlockUsed> { ledger.granting("r2", now.plusSeconds(3600), tz, PurchaseState.NOT_PURCHASED) }
        val during = Capabilities.resolve(PurchaseState.NOT_PURCHASED, ledger, now.plusSeconds(600))
        assertTrue(during.tier == Capabilities.Tier.TEMPORARY_PRO && during.exactDuplicates && during.dailyCleanupLimit == 20)
        assertTrue(during.customCollections)
        assertTrue(abs((during.rewardRemaining ?: Duration.ZERO).seconds - 20 * 60) < 1)
        assertEquals(Capabilities.Tier.FREE, Capabilities.resolve(PurchaseState.NOT_PURCHASED, ledger, now.plusSeconds(30 * 60 + 1)).tier)
        assertTrue(ledger.canOfferReward(now.plusSeconds(86_400), tz, PurchaseState.NOT_PURCHASED))
    }

    @Test fun clockBackExpiresConservatively() {
        val now = TestClock.date("2026-06-10T09:00", tz.id)
        val (ledger, _) = RewardLedger().granting("r1", now, tz, PurchaseState.NOT_PURCHASED)
        assertNull(ledger.active(now.minusSeconds(60)))
    }

    @Test fun proNoRewardNoAds() {
        assertThrowsSuspend<RewardError.AlreadyPurchased> { RewardLedger().granting("r", Instant.now(), tz, PurchaseState.PURCHASED) }
        assertFalse(RewardLedger().canOfferReward(Instant.now(), tz, PurchaseState.PURCHASED))
    }

    @Test fun workUnit() {
        val now = TestClock.date("2026-06-10T09:00", tz.id)
        val (ledger, _) = RewardLedger().granting("r1", now, tz, PurchaseState.NOT_PURCHASED)
        val caps = Capabilities.resolve(PurchaseState.NOT_PURCHASED, ledger, now.plusSeconds(29 * 60))
        assertNotNull(WorkUnitGrant.begin({ it.exactDuplicates }, caps, now))
        val expired = Capabilities.resolve(PurchaseState.NOT_PURCHASED, ledger, now.plusSeconds(31 * 60))
        assertNull(WorkUnitGrant.begin({ it.exactDuplicates }, expired, now))
    }

    @Test fun storageDoesNotAddOverlappingCategories() {
        val o = StorageOverview(DeviceCapacity(128, 64), mapOf(
            StorageCategory.PHOTOS to SizeTally.of(listOf(KnownSize.Known(10), KnownSize.Unknown)),
            StorageCategory.VIDEOS to SizeTally.of(listOf(KnownSize.Known(20))),
            StorageCategory.SCREENSHOTS to SizeTally.of(listOf(KnownSize.Known(5))),
            StorageCategory.DUPLICATES to SizeTally.of(listOf(KnownSize.Known(10))),
        ), null)
        assertEquals(30, o.scannedTotal.knownBytes)
        assertTrue(o.scannedTotal.isLowerBound)
        assertTrue(StorageCategory.SCREENSHOTS.isOverlappingView)
    }
}
