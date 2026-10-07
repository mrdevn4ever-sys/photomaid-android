package app.photomaid.core

import app.photomaid.core.cleanup.CleanupSavings
import app.photomaid.core.cleanup.SavingsTotal
import app.photomaid.core.model.KnownSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupSavingsTest {
    @Test fun onlyRemovedItemsWithKnownSizeCount() {
        val sizes = mapOf(photo(1) to KnownSize.Known(3_000_000), photo(2) to KnownSize.Unknown, photo(3) to KnownSize.Known(9_999_999))
        // photo(3) war Kandidat, wurde aber nicht entfernt → zählt nicht.
        val s = CleanupSavings.of(setOf(photo(1), photo(2)), sizes)
        assertEquals(3_000_000, s.bytes); assertEquals(1, s.unknownCount); assertEquals(2, s.removedCount)
        assertTrue(s.isLowerBound) // unbekannt ≠ 0 → „mind.“
        val all = CleanupSavings.of(setOf(photo(1)), sizes)
        assertFalse(all.isLowerBound)
        val total = SavingsTotal().adding(all).adding(s)
        assertEquals(6_000_000, total.bytes); assertTrue(total.lowerBound)
        // Fehlt die Größe ganz (nicht ermittelt), gilt sie als unbekannt – nie als 0.
        assertEquals(1, CleanupSavings.of(setOf(photo(4)), sizes).unknownCount)
    }
}
