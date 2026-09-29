package app.photomaid.core

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
import app.photomaid.core.rules.InventoryItem
import app.photomaid.core.rules.ReviewRule
import app.photomaid.core.rules.RuleEvaluator
import app.photomaid.core.rules.RuleFilter
import app.photomaid.core.rules.RuleSource
import app.photomaid.core.rules.RuleTrigger
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

/** Port – Teil „Regeln und Routinen“. */
class RuleTest {
    private val tz: ZoneId = ZoneId.of("Europe/Zurich")

    private fun inv(n: Int, daysOld: Long, size: KnownSize = KnownSize.Known(1), now: Instant, firstSeen: Instant? = null) =
        InventoryItem(photo(n), RuleSource.Screenshots, now.minusSeconds(daysOld * 86_400), size, firstSeen ?: now)

    @Test fun olderThanCreatesIdempotentTask() {
        val now = TestClock.date("2026-06-10T09:00", tz.id)
        val rule = ReviewRule(source = RuleSource.Screenshots, filter = RuleFilter.OlderThan(30), trigger = RuleTrigger.Daily)
        val items = listOf(inv(1, 45, now = now), inv(2, 5, now = now))
        val task = RuleEvaluator.evaluate(rule, items, emptyMap(), emptySet(), null, now, tz)
        assertEquals(listOf(photo(1)), task?.items)
        assertNull("Keine doppelte Aufgabe am selben Tag",
            RuleEvaluator.evaluate(rule, items, emptyMap(), setOf(task!!.id), now, now.plusSeconds(3600), tz))
    }

    @Test fun decidedAndUnknownSizesExcluded() {
        val now = TestClock.date("2026-06-10T09:00", tz.id)
        val rule = ReviewRule(source = RuleSource.Screenshots, filter = RuleFilter.KnownSizeAtLeast(1_000_000_000), trigger = RuleTrigger.Daily)
        val kept = ItemRecord(photo(1), disposition = Disposition.KEPT)
        val task = RuleEvaluator.evaluate(rule, listOf(
            inv(1, 1, KnownSize.Known(2_000_000_000), now), inv(2, 1, KnownSize.Unknown, now), inv(3, 1, KnownSize.Known(1_500_000_000), now),
        ), mapOf(photo(1) to kept), emptySet(), null, now, tz)
        assertEquals(listOf(photo(3)), task?.items)
    }

    @Test fun weeklyOnlyOnWeekday() {
        val sunday = TestClock.date("2026-06-14T09:00", tz.id)
        val monday = TestClock.date("2026-06-15T09:00", tz.id)
        var rule = ReviewRule(source = RuleSource.Screenshots, filter = RuleFilter.All, trigger = RuleTrigger.Weekly(1))
        val items = listOf(inv(1, 1, now = sunday))
        assertNotNull(RuleEvaluator.evaluate(rule, items, emptyMap(), emptySet(), null, sunday, tz))
        assertNull(RuleEvaluator.evaluate(rule, items, emptyMap(), emptySet(), null, monday, tz))
        rule = rule.copy(isEnabled = false)
        assertNull(RuleEvaluator.evaluate(rule, items, emptyMap(), emptySet(), null, sunday, tz))
    }

    @Test fun newlyDiscoveredAfterTen() {
        val last = TestClock.date("2026-06-09T09:00", tz.id)
        val now = TestClock.date("2026-06-10T09:00", tz.id)
        val rule = ReviewRule(source = RuleSource.Screenshots, filter = RuleFilter.All, trigger = RuleTrigger.NewlyDiscovered(10))
        val nine = (0 until 9).map { inv(it, 0, now = now) }
        val old = (100 until 120).map { inv(it, 3, now = now, firstSeen = last.minusSeconds(60)) }
        assertNull(RuleEvaluator.evaluate(rule, nine + old, emptyMap(), emptySet(), last, now, tz))
        val ten = (0 until 10).map { inv(it, 0, now = now) }
        assertEquals(10, RuleEvaluator.evaluate(rule, ten + old, emptyMap(), emptySet(), last, now, tz)?.items?.size)
    }
}

/** Port – Teil „Free, Pro, Rewarded“. */
class CapabilityTest {
    private val tz: ZoneId = ZoneId.of("Europe/Zurich")

    @Test fun purchaseStates() {
        for (state in PurchaseState.entries) {
            val caps = Capabilities.resolve(state, RewardLedger(), Instant.now())
            if (state == PurchaseState.PURCHASED) {
                assertTrue(caps.tier == Capabilities.Tier.PRO && caps.dailyCleanupLimit == null && !caps.adsAllowed && caps.exactDuplicates)
            } else {
                assertTrue(caps.tier == Capabilities.Tier.FREE && caps.dailyCleanupLimit == 15 && !caps.exactDuplicates)
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
        assertTrue(during.tier == Capabilities.Tier.REWARDED_PREVIEW && during.exactDuplicates && during.dailyCleanupLimit == 15)
        assertTrue(!during.persistentRoutines && during.oneTimeRuleTestScan)
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
