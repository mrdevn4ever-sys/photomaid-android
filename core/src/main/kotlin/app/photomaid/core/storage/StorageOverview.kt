package app.photomaid.core.storage

import app.photomaid.core.model.SizeTally
import java.time.Instant

/** A. Systemkapazität laut öffentlicher API (`StorageStatsManager`). */
data class DeviceCapacity(val totalBytes: Long, val availableBytes: Long) {
    val usedBytes: Long get() = maxOf(0, totalBytes - availableBytes)
}

enum class StorageCategory {
    PHOTOS, VIDEOS,
    /** Teilmenge von [PHOTOS]. */
    SCREENSHOTS,
    CONNECTED_FILES,
    /** Überlappende Ansicht. */
    LARGE_FILES,
    /** Überlappende Ansicht. */
    DUPLICATES;

    val isOverlappingView: Boolean get() = this !in DISJOINT

    companion object {
        /** Kategorien, die sich nicht überschneiden und daher addiert werden dürfen. */
        val DISJOINT = listOf(PHOTOS, VIDEOS, CONNECTED_FILES)
    }
}

/** B. Erfasste zugängliche Medien/Dateien. C. bleibt ausdrücklich unbekannt (keine Differenzrechnung). */
data class StorageOverview(
    val device: DeviceCapacity?,
    val categories: Map<StorageCategory, SizeTally>,
    val scannedAt: Instant?,
) {
    /** Summe nur der disjunkten Kategorien. */
    val scannedTotal: SizeTally
        get() = StorageCategory.DISJOINT.mapNotNull { categories[it] }.fold(SizeTally()) { a, b -> a.merging(b) }
}
