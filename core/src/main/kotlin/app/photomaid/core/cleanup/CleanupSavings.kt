package app.photomaid.core.cleanup

import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.KnownSize

/**
 * „Gespart“ nach dem Leeren – nur aus TATSÄCHLICH entfernten Elementen mit bekannter Größe (keine Kandidatengrößen,
 * unbekannt ≠ 0). [unknownCount] > 0 → Anzeige als „mind.“.
 */
data class CleanupSavings(val bytes: Long, val unknownCount: Int, val removedCount: Int) {
    val isLowerBound: Boolean get() = unknownCount > 0

    companion object {
        fun of(removed: Set<ItemReference>, sizes: Map<ItemReference, KnownSize>): CleanupSavings {
            var bytes = 0L; var unknown = 0
            for (r in removed) when (val s = sizes[r]) {
                is KnownSize.Known -> bytes += s.bytes
                else -> unknown++
            }
            return CleanupSavings(bytes, unknown, removed.size)
        }
    }
}

/** Laufende Summe über alle Leerungen (lokal gespeichert, nur Zahlen). */
data class SavingsTotal(val bytes: Long = 0, val lowerBound: Boolean = false) {
    fun adding(s: CleanupSavings) = SavingsTotal(bytes + s.bytes, lowerBound || s.isLowerBound)
}
