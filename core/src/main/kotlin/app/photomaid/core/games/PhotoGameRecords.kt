package app.photomaid.core.games

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max

/** Persönliche Bestwerte: nur Zahlen und Regel-Kategorien, nie Foto-Kennungen oder Bilder (Port von iOS). */
@Serializable
class PhotoGameRecords(
    @SerialName("memory") private val memoryList: MutableList<MemoryRecord> = mutableListOf(),
    @SerialName("snake") private val snakeList: MutableList<SnakeRecord> = mutableListOf(),
) {


    @Serializable
    data class MemoryCategory(val pairs: Int, val usesPhotos: Boolean, val manualMismatch: Boolean = false) {
        internal val isValid: Boolean get() = pairs in 2..PhotoMemoryGame.MAXIMUM_PAIRS
    }

    @Serializable
    data class SnakeCategory(
        val mode: PhotoSnakeGame.Mode,
        val speed: PhotoSnakeSpeed,
        val stepMode: Boolean,
        val width: Int = 12,
        val height: Int = 16,
    ) {
        internal val isValid: Boolean get() = width in 4..24 && height in 4..24
    }

    @Serializable data class MemoryRecord(val category: MemoryCategory, val seconds: Double)
    @Serializable data class SnakeRecord(val category: SnakeCategory, val eaten: Int)

    val memory: List<MemoryRecord> get() = memoryList.toList()
    val snake: List<SnakeRecord> get() = snakeList.toList()

    fun memoryBest(category: MemoryCategory): Double? = memoryList.firstOrNull { it.category == category }?.seconds
    fun snakeBest(category: SnakeCategory): Int? = snakeList.firstOrNull { it.category == category }?.eaten

    /** Ein Rekord je Spiel (iOS-Nutzerentscheidung 21.10.2026): bester Wert über alle Snake-Varianten und Feldgrößen. */
    val snakeOverallBest: Int? get() = snakeList.maxOfOrNull { it.eaten }

    /** Eine abgebrochene oder unvollständige Runde ergibt nie eine Bestzeit. */
    fun recordMemory(game: PhotoMemoryGame, seconds: Double, usesPhotos: Boolean, manualMismatch: Boolean = false): Boolean {
        if (!game.isFinished) return false
        return considerMemory(MemoryRecord(MemoryCategory(game.pairCount, usesPhotos, manualMismatch), seconds))
    }

    /** Bei jedem eingesammelten Bild speichern, damit Verlassen nie einen verdienten Rekord verliert. */
    fun recordSnake(game: PhotoSnakeGame, speed: PhotoSnakeSpeed, stepMode: Boolean): Boolean =
        considerSnake(SnakeRecord(SnakeCategory(game.mode, speed, stepMode, game.width, game.height), game.score))

    private fun considerMemory(record: MemoryRecord): Boolean {
        if (!record.category.isValid || !record.seconds.isFinite() || record.seconds <= 0) return false
        val previous = memoryBest(record.category)
        if (previous != null && previous <= record.seconds) return false
        memoryList.removeAll { it.category == record.category }; memoryList += record
        return true
    }

    private fun considerSnake(record: SnakeRecord): Boolean {
        if (!record.category.isValid || record.eaten <= 0 || record.eaten > record.category.width * record.category.height - 3) return false
        val previous = snakeBest(record.category)
        if (previous != null && previous >= record.eaten) return false
        snakeList.removeAll { it.category == record.category }; snakeList += record
        return true
    }

    /** Beim Laden jeden Eintrag erneut prüfen – beschädigte lokale Daten erzeugen nie einen Rekord. */
    fun sanitized(): PhotoGameRecords {
        val clean = PhotoGameRecords()
        memoryList.forEach { clean.considerMemory(it) }
        snakeList.forEach { clean.considerSnake(it) }
        return clean
    }
}

/** Monotone, von außen getaktete Stoppuhr: Schlaf, Hintergrund und Uhrzeitänderungen spielen keine Rolle. */
class PhotoGameStopwatch {
    var hasStarted = false
        private set
    private var accumulated = 0.0
    private var startedAt: Double? = null
    val isRunning: Boolean get() = startedAt != null

    fun elapsed(instant: Double): Double {
        val s = startedAt ?: return accumulated
        if (!instant.isFinite()) return accumulated
        return accumulated + max(0.0, instant - s)
    }

    fun resume(instant: Double) {
        if (!instant.isFinite() || startedAt != null) return
        hasStarted = true; startedAt = instant
    }

    fun pause(instant: Double) { accumulated = elapsed(instant); startedAt = null }

    fun reset() { hasStarted = false; accumulated = 0.0; startedAt = null }
}
