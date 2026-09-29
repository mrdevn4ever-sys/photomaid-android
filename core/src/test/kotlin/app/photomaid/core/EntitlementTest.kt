package app.photomaid.core

import app.photomaid.core.capabilities.Capabilities
import app.photomaid.core.capabilities.Capabilities.Tier
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.core.capabilities.RewardError
import app.photomaid.core.capabilities.RewardLedger
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Entitlement-Modell: Pro-Werkzeuge und Papierkorb-Limit sind getrennt; Pro-Test 30 min, 1× pro lokalem Tag. */
class EntitlementTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val t0 = Instant.parse("2026-09-29T08:00:00Z")

    @Test fun matrixFreeTemporaryLifetime() {
        val free = Capabilities.resolve(PurchaseState.NOT_PURCHASED, RewardLedger(), t0)
        assertEquals(Tier.FREE, free.tier)
        assertFalse(free.hasProToolsAccess); assertFalse(free.hasUnlimitedTrashAccess); assertEquals(15, free.dailyCleanupLimit)

        val (ledger, _) = RewardLedger().granting("r1", t0, zone, PurchaseState.NOT_PURCHASED)
        val temp = Capabilities.resolve(PurchaseState.NOT_PURCHASED, ledger, t0)
        assertEquals(Tier.TEMPORARY_PRO, temp.tier)
        assertTrue(temp.hasProToolsAccess); assertTrue(temp.exactDuplicates); assertTrue(temp.customCollections)
        // KRITISCH: der Pro-Test hebt das tägliche Papierkorb-Limit NICHT auf und erhöht es nicht.
        assertFalse(temp.hasUnlimitedTrashAccess)
        assertEquals(Capabilities.FREE_DAILY_CLEANUP_LIMIT, temp.dailyCleanupLimit)

        val life = Capabilities.resolve(PurchaseState.PURCHASED, RewardLedger(), t0)
        assertEquals(Tier.LIFETIME_PRO, life.tier)
        assertTrue(life.hasProToolsAccess); assertTrue(life.hasUnlimitedTrashAccess); assertNull(life.dailyCleanupLimit)
        assertFalse(life.adsAllowed)
    }

    @Test fun thirtyMinuteWindowBoundaries() {
        val (ledger, w) = RewardLedger().granting("r1", t0, zone, PurchaseState.NOT_PURCHASED)
        assertEquals(t0.plus(Duration.ofMinutes(30)), w.endsAt)
        fun tier(at: Instant) = Capabilities.resolve(PurchaseState.NOT_PURCHASED, ledger, at).tier
        assertEquals(Tier.TEMPORARY_PRO, tier(t0))
        assertEquals(Tier.TEMPORARY_PRO, tier(t0.plusSeconds(30 * 60 - 1)))
        assertEquals(Tier.FREE, tier(t0.plusSeconds(30 * 60)))
        // Läuft auch bei geschlossener App/Neustart weiter: nur die reale Uhrzeit zählt.
        assertEquals(Tier.FREE, tier(t0.plus(Duration.ofHours(5))))
    }

    @Test fun oncePerLocalDayAndAgainTomorrow() {
        val (ledger, _) = RewardLedger().granting("r1", t0, zone, PurchaseState.NOT_PURCHASED)
        val later = t0.plus(Duration.ofHours(3)) // abgelaufen, aber gleicher Tag
        assertFalse(ledger.canOfferReward(later, zone, PurchaseState.NOT_PURCHASED))
        assertThrows(RewardError.DailyUnlockUsed::class.java) { ledger.granting("r2", later, zone, PurchaseState.NOT_PURCHASED) }
        val tomorrow = Instant.parse("2026-09-30T06:00:00Z")
        assertTrue(ledger.canOfferReward(tomorrow, zone, PurchaseState.NOT_PURCHASED))
        assertEquals(2, ledger.granting("r2", tomorrow, zone, PurchaseState.NOT_PURCHASED).first.windows.size)
    }

    @Test fun sameRewardEventIsIdempotent() {
        val (ledger, w) = RewardLedger().granting("r1", t0, zone, PurchaseState.NOT_PURCHASED)
        val (again, w2) = ledger.granting("r1", t0.plusSeconds(60), zone, PurchaseState.NOT_PURCHASED)
        assertEquals(ledger, again); assertEquals(w, w2)
    }

    /** Uhr zurückgestellt (z. B. auf gestern): kein zweiter Test, laufender Test konservativ beendet. */
    @Test fun clockTurnedBackDoesNotGrantAgain() {
        val (ledger, _) = RewardLedger().granting("r1", t0, zone, PurchaseState.NOT_PURCHASED)
        val yesterday = t0.minus(Duration.ofDays(1))
        assertFalse(ledger.canOfferReward(yesterday, zone, PurchaseState.NOT_PURCHASED))
        assertThrows(RewardError.DailyUnlockUsed::class.java) { ledger.granting("r2", yesterday, zone, PurchaseState.NOT_PURCHASED) }
        assertEquals(Tier.FREE, Capabilities.resolve(PurchaseState.NOT_PURCHASED, ledger, yesterday).tier)
    }

    /** Zeitzonenwechsel: maßgeblich ist der lokale Kalendertag der aktuellen Zone. */
    @Test fun localDayFollowsCurrentZone() {
        val late = Instant.parse("2026-09-29T21:30:00Z") // Zürich 23:30 (29.), Tokio 06:30 (30.)
        val (ledger, _) = RewardLedger().granting("r1", late, zone, PurchaseState.NOT_PURCHASED)
        assertTrue(ledger.canOfferReward(Instant.parse("2026-09-29T22:30:00Z"), zone, PurchaseState.NOT_PURCHASED)) // Zürich 00:30 am 30.
        assertFalse(ledger.canOfferReward(Instant.parse("2026-09-29T21:45:00Z"), zone, PurchaseState.NOT_PURCHASED))
    }

    @Test fun lifetimeWinsDuringTemporaryAndNeverOffersReward() {
        val (ledger, _) = RewardLedger().granting("r1", t0, zone, PurchaseState.NOT_PURCHASED)
        val caps = Capabilities.resolve(PurchaseState.PURCHASED, ledger, t0.plusSeconds(60)) // Kauf/Restore während des Tests
        assertEquals(Tier.LIFETIME_PRO, caps.tier); assertNull(caps.rewardRemaining); assertNull(caps.dailyCleanupLimit)
        assertFalse(RewardLedger().canOfferReward(t0, zone, PurchaseState.PURCHASED))
        assertThrows(RewardError.AlreadyPurchased::class.java) { RewardLedger().granting("r9", t0, zone, PurchaseState.PURCHASED) }
        // Nach Ablauf der 30 Minuten bleibt Lifetime natürlich aktiv.
        assertEquals(Tier.LIFETIME_PRO, Capabilities.resolve(PurchaseState.PURCHASED, ledger, t0.plus(Duration.ofHours(2))).tier)
    }

    /** App-Neustart / Process Kill / Geräte-Neustart: persistierter Stand umgeht das Tageslimit nicht. */
    @Test fun persistedLedgerSurvivesRestart() {
        val (ledger, _) = RewardLedger().granting("r1", t0, zone, PurchaseState.NOT_PURCHASED)
        val restored = Json.decodeFromString<RewardLedger>(Json.encodeToString(RewardLedger.serializer(), ledger))
        assertFalse(restored.canOfferReward(t0.plusSeconds(7200), zone, PurchaseState.NOT_PURCHASED))
        assertEquals(Tier.TEMPORARY_PRO, Capabilities.resolve(PurchaseState.NOT_PURCHASED, restored, t0.plusSeconds(600)).tier)
    }

    @Test fun unknownPurchaseStateRequestsNoAds() {
        assertFalse(Capabilities.resolve(PurchaseState.UNKNOWN, RewardLedger(), t0).adsAllowed)
        assertTrue(Capabilities.resolve(PurchaseState.NOT_PURCHASED, RewardLedger(), t0).adsAllowed)
    }
}
