package app.photomaid.core.games

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Foto Blocks (Port von iOS `PhotoBlocksGame`): eigene Fallstein-Logik. Jede volle Reihe legt ein Zehntel eines Fotos frei;
 * zehn Reihen = ein Foto entdeckt, nächste Stufe. Die Simulation besitzt alle Übergänge; Zeichnen ändert nie das Brett.
 */
@Serializable
data class PhotoBlocksCell(val x: Int, val y: Int)

/** Vier-Felder-Steine. Alte Werte (bis `FERN`) behalten ihre Form, damit eine gespeicherte Runde gültig bleibt. */
@Serializable
enum class PhotoBlocksShape {
    PEBBLE, SHOOT, ARCH, PETAL, BOUGH, FERN,
    SQUARE, LINE, CORNER_LEFT, CORNER_RIGHT, CROWN, STEP_LEFT, STEP_RIGHT;

    val isLegacy: Boolean get() = ordinal <= FERN.ordinal

    fun cells(rotation: Int = 0): List<PhotoBlocksCell> {
        fun c(x: Int, y: Int) = PhotoBlocksCell(x, y)
        var cells = when (this) {
            PEBBLE -> listOf(c(0, 0), c(1, 0))
            SHOOT -> listOf(c(0, 0), c(0, 1), c(1, 1))
            ARCH -> listOf(c(0, 0), c(2, 0), c(0, 1), c(1, 1), c(2, 1))
            PETAL -> listOf(c(0, 0), c(1, 0), c(0, 1), c(1, 1), c(0, 2))
            BOUGH -> listOf(c(0, 0), c(0, 1), c(0, 2), c(1, 2), c(2, 2))
            FERN -> listOf(c(0, 0), c(0, 1), c(1, 1), c(0, 2), c(0, 3))
            SQUARE -> listOf(c(0, 0), c(1, 0), c(0, 1), c(1, 1))
            LINE -> listOf(c(0, 0), c(0, 1), c(0, 2), c(0, 3))
            CORNER_LEFT -> listOf(c(0, 0), c(0, 1), c(0, 2), c(1, 2))
            CORNER_RIGHT -> listOf(c(1, 0), c(1, 1), c(1, 2), c(0, 2))
            CROWN -> listOf(c(0, 0), c(1, 0), c(2, 0), c(1, 1))
            STEP_LEFT -> listOf(c(1, 0), c(2, 0), c(0, 1), c(1, 1))
            STEP_RIGHT -> listOf(c(0, 0), c(1, 0), c(1, 1), c(2, 1))
        }
        repeat(((rotation % 4) + 4) % 4) {
            cells = cells.map { c(-it.y, it.x) }
            val minX = cells.minOf { it.x }; val minY = cells.minOf { it.y }
            cells = cells.map { c(it.x - minX, it.y - minY) }
        }
        return cells
    }

    companion object {
        val playableShapes = listOf(SQUARE, LINE, CORNER_LEFT, CORNER_RIGHT, CROWN, STEP_LEFT, STEP_RIGHT)
    }
}

@Serializable
data class PhotoBlocksPiece(val shape: PhotoBlocksShape, val rotation: Int = 0, val origin: PhotoBlocksCell) {
    val cells: List<PhotoBlocksCell>
        get() = shape.cells(((rotation % 4) + 4) % 4).map { PhotoBlocksCell(it.x + origin.x, it.y + origin.y) }
    fun moved(dx: Int, dy: Int) = copy(origin = PhotoBlocksCell(origin.x + dx, origin.y + dy))
}

/** Ausdrücklicher Zufallsgenerator mit gespeichertem Zustand (SplitMix64) – Beutel und Fortsetzungen sind reproduzierbar. */
@Serializable
data class PhotoBlocksRandom(var state: ULong) {
    fun next(): ULong {
        state += 0x9e3779b97f4a7c15uL
        var z = state
        z = (z xor (z shr 30)) * 0xbf58476d1ce4e5b9uL
        z = (z xor (z shr 27)) * 0x94d049bb133111ebuL
        return z xor (z shr 31)
    }

    fun <T> shuffle(values: MutableList<T>) {
        if (values.size <= 1) return
        for (index in values.size - 1 downTo 1) {
            val j = (next() % (index + 1).toULong()).toInt()
            val t = values[index]; values[index] = values[j]; values[j] = t
        }
    }
}

@Serializable
class PhotoBlocksGame private constructor(
    val width: Int,
    val height: Int,
    private var boardRows: MutableList<MutableList<PhotoBlocksShape?>>,
    private var activePiece: PhotoBlocksPiece? = null,
    private var nextShape: PhotoBlocksShape = PhotoBlocksShape.SQUARE,
    private var phaseValue: Phase = Phase.READY,
    private var paused: Boolean = false,
    private var scoreValue: Long = 0,
    private var totalRowsValue: Int = 0,
    private var levelValue: Int = 1,
    private var revealed: Int = 0,
    private var discovered: Int = 0,
    private var cleared: MutableList<Int> = mutableListOf(),
    private var placed: Long = 0,
    private var carryRows: Int = 0,
    private var phaseTime: Double = 0.0,
    private var fallTime: Double = 0.0,
    private var lockTime: Double = 0.0,
    private var lockResets: Int = 0,
    private var softDrop: Boolean = false,
    private var bag: MutableList<PhotoBlocksShape> = mutableListOf(),
    private var random: PhotoBlocksRandom,
) {
    @Serializable enum class Phase { READY, PLAYING, CLEARING, DISCOVERED, GAME_OVER }

    val board: List<List<PhotoBlocksShape?>> get() = boardRows.map { it.toList() }
    val active: PhotoBlocksPiece? get() = activePiece
    val next: PhotoBlocksShape get() = nextShape
    val phase: Phase get() = phaseValue
    val isPaused: Boolean get() = paused
    val score: Long get() = scoreValue
    val totalRows: Int get() = totalRowsValue
    val level: Int get() = levelValue
    val revealedStrips: Int get() = revealed
    val discoveredPhotos: Int get() = discovered
    val clearedRows: List<Int> get() = cleared.toList()
    val piecesPlaced: Long get() = placed

    val progress: Double get() = revealed / 10.0
    val clearFraction: Double get() = if (phaseValue == Phase.CLEARING) min(1.0, phaseTime / 0.18) else 0.0
    /** 0,88 s am Anfang, gleitend bis 0,16 s – kein plötzlicher Tempo-Sprung. */
    val fallInterval: Double get() = 0.16 + 0.72 * 0.90.pow(min(levelValue - 1, 1000).toDouble())
    val acceptsInput: Boolean get() = phaseValue == Phase.PLAYING && !paused
    val ghost: PhotoBlocksPiece?
        get() {
            var piece = activePiece ?: return null
            while (fits(piece.moved(0, 1))) piece = piece.moved(0, 1)
            return piece
        }

    fun start() { if (phaseValue == Phase.READY) phaseValue = Phase.PLAYING }
    fun pause() { setSoftDrop(false); if (phaseValue != Phase.READY && phaseValue != Phase.GAME_OVER) paused = true }
    fun resume() { paused = false; softDrop = false }

    private val softInterval: Double get() = min(0.045, fallInterval / 8)

    fun setSoftDrop(enabled: Boolean) {
        val target = enabled && acceptsInput
        if (target == softDrop) return
        val before = if (softDrop) softInterval else fallInterval
        softDrop = target
        val after = if (softDrop) softInterval else fallInterval
        // Anteiligen Fortschritt erhalten, nicht die Sekunden der vorigen Geschwindigkeit.
        fallTime = min(1.0, fallTime / before) * after
    }

    fun move(dx: Int): Boolean {
        val a = activePiece ?: return false
        if (!acceptsInput || abs(dx) != 1) return false
        val candidate = a.moved(dx, 0)
        if (!fits(candidate)) return false
        resetLockIfAllowed(); activePiece = candidate; return true
    }

    fun rotate(): Boolean {
        val a = activePiece ?: return false
        if (!acceptsInput) return false
        val candidate = a.copy(rotation = (a.rotation + 1) % 4)
        val offsets = listOf(0 to 0, -1 to 0, 1 to 0, -2 to 0, 2 to 0, 0 to -1, 0 to -2) +
            if (a.shape == PhotoBlocksShape.LINE) listOf(-3 to 0, 3 to 0, 0 to -3) else emptyList()
        for ((dx, dy) in offsets) {
            val kicked = candidate.moved(dx, dy)
            if (fits(kicked)) { resetLockIfAllowed(); activePiece = kicked; return true }
        }
        return false
    }

    fun hardDrop(): Boolean {
        val a = activePiece ?: return false
        val g = ghost ?: return false
        if (!acceptsInput) return false
        scoreValue += max(0, g.origin.y - a.origin.y) // ein Punkt je gefallenem Feld
        activePiece = g; lock(); return true
    }

    /** Von außen gelieferte monotone Zeit; nach einer Pause wird die Schwerkraft nie nachgeholt. */
    fun advance(seconds: Double) {
        if (!seconds.isFinite() || seconds <= 0 || paused) return
        when (phaseValue) {
            Phase.READY, Phase.GAME_OVER -> return
            Phase.CLEARING -> { phaseTime += min(seconds, 3.0); if (phaseTime >= 0.18) finishClear() }
            Phase.DISCOVERED -> { phaseTime += min(seconds, 3.0); if (phaseTime >= 2) finishDiscovery() }
            Phase.PLAYING -> {
                val a = activePiece ?: return
                val delta = min(seconds, 0.25)
                if (!fits(a.moved(0, 1))) {
                    lockTime += delta
                    if (lockTime >= 0.40) lock()
                } else {
                    lockTime = 0.0; fallTime += delta
                    val interval = if (softDrop) softInterval else fallInterval
                    while (fallTime >= interval) {
                        val current = activePiece ?: break
                        fallTime -= interval
                        val candidate = current.moved(0, 1)
                        if (!fits(candidate)) { fallTime = 0.0; break }
                        activePiece = candidate
                    }
                }
            }
        }
    }

    fun fits(piece: PhotoBlocksPiece): Boolean = piece.cells.all { c ->
        c.x in 0 until width && c.y in 0 until height && boardRows[c.y][c.x] == null
    }

    private fun resetLockIfAllowed() {
        val a = activePiece ?: return
        if (!fits(a.moved(0, 1)) && lockResets < 8) { lockTime = 0.0; lockResets += 1 }
    }

    private fun draw(): PhotoBlocksShape {
        bag.removeAll { it.isLegacy }
        if (bag.isEmpty()) { bag = PhotoBlocksShape.playableShapes.toMutableList(); random.shuffle(bag) }
        return bag.removeAt(bag.lastIndex)
    }

    private fun spawn() {
        val shape = nextShape; nextShape = draw()
        val columns = (shape.cells().maxOfOrNull { it.x } ?: 0) + 1
        val piece = PhotoBlocksPiece(shape, 0, PhotoBlocksCell((width - columns) / 2, 0))
        fallTime = 0.0; lockTime = 0.0; lockResets = 0; softDrop = false
        if (fits(piece)) activePiece = piece else { activePiece = null; phaseValue = Phase.GAME_OVER }
    }

    private fun lock() {
        val a = activePiece ?: return
        if (!fits(a)) return
        for (c in a.cells) boardRows[c.y][c.x] = a.shape
        activePiece = null; softDrop = false; placed += 1
        scoreValue += 5
        cleared = boardRows.indices.filter { y -> boardRows[y].all { it != null } }.toMutableList()
        if (cleared.isEmpty()) spawn() else { phaseValue = Phase.CLEARING; phaseTime = 0.0 }
    }

    private fun finishClear() {
        val count = cleared.size
        val removed = cleared.toSet()
        val kept = boardRows.filterIndexed { i, _ -> i !in removed }
        boardRows = (List(count) { MutableList<PhotoBlocksShape?>(width) { null } } + kept).toMutableList()
        totalRowsValue += count
        // Eigene, maßvolle Wertung: 40 / 120 / 240 / 400 …, +10 % je Stufe, höchstens doppelt.
        val base = 20L * count * (count + 1)
        scoreValue += base * (10 + min(10, levelValue - 1)) / 10
        revealed += count; cleared = mutableListOf(); phaseTime = 0.0
        if (revealed >= 10) {
            carryRows = revealed - 10; revealed = 10
            discovered += 1; phaseValue = Phase.DISCOVERED
        } else { phaseValue = Phase.PLAYING; spawn() }
    }

    private fun finishDiscovery() {
        levelValue += 1; revealed = carryRows; carryRows = 0
        phaseTime = 0.0; phaseValue = Phase.PLAYING; spawn()
    }

    /** Keine Bilder oder Kennungen im Spielstand. Beschädigte lokale Daten werden vor jedem Zugriff verworfen. */
    val isValidSnapshot: Boolean
        get() {
            if (width !in 6..14 || height !in 12..28 || boardRows.size != height || boardRows.any { it.size != width }) return false
            if (scoreValue !in 0..9_000_000_000_000_000 || totalRowsValue < 0 || levelValue !in 1..100_000_000) return false
            if (discovered != (if (phaseValue == Phase.DISCOVERED) levelValue else levelValue - 1)) return false
            if (totalRowsValue != (levelValue - 1) * 10 + revealed + carryRows) return false
            if (revealed !in 0..10 || carryRows !in 0..4 || placed !in 0..1_000_000_000_000 || lockResets !in 0..8) return false
            if (listOf(phaseTime, fallTime, lockTime).any { !it.isFinite() || it < 0 || it > 10 }) return false
            if (bag.size > PhotoBlocksShape.playableShapes.size || bag.toSet().size != bag.size) return false
            if (cleared.size > 5 || cleared.toSet().size != cleared.size || cleared.any { it !in 0 until height }) return false
            val a = activePiece
            if (a != null && (a.rotation !in 0..3 || a.origin.x !in 0 until width || a.origin.y !in 0 until height || !fits(a))) return false
            if ((phaseValue == Phase.READY || phaseValue == Phase.PLAYING) != (a != null)) return false
            val full = boardRows.indices.filter { y -> boardRows[y].all { it != null } }
            if (phaseValue == Phase.CLEARING) { if (cleared.isEmpty() || cleared != full) return false } else if (cleared.isNotEmpty()) return false
            return if (phaseValue == Phase.DISCOVERED) revealed == 10 else revealed < 10
        }

    companion object {
        fun create(width: Int = 9, height: Int = 17, seed: ULong = 1uL): PhotoBlocksGame {
            val w = width.coerceIn(6, 14); val h = height.coerceIn(12, 28)
            val g = PhotoBlocksGame(w, h, MutableList(h) { MutableList<PhotoBlocksShape?>(w) { null } }, random = PhotoBlocksRandom(seed))
            g.nextShape = g.draw(); g.spawn()
            return g
        }

        /** Nur für Tests: dieselben Regeln, keine Schummel-Schnittstelle für die App. */
        internal fun forTest(board: List<List<PhotoBlocksShape?>>, active: PhotoBlocksPiece, revealed: Int = 0, level: Int = 1): PhotoBlocksGame {
            val g = create(board[0].size, board.size)
            g.boardRows = board.map { it.toMutableList() }.toMutableList()
            g.activePiece = active; g.phaseValue = Phase.PLAYING
            g.revealed = revealed; g.levelValue = level; g.discovered = level - 1
            g.totalRowsValue = (level - 1) * 10 + revealed
            return g
        }
    }
}

/** Gespeicherter Stand: nur Zahlen, nie Bilder oder Kennungen. */
@Serializable
data class PhotoBlocksArchive(
    val game: PhotoBlocksGame? = null,
    val best: Long = 0,
    val discoveredImages: Int = 0,
    val photoSeed: ULong = 1uL,
    val photoSource: PhotoBlocksPhotos.Source = PhotoBlocksPhotos.Source.RANDOM,
) {
    /** Ungültige Spielstände verwerfen, Zahlen begrenzen (wie iOS `init(from:)`). */
    fun sanitized(): PhotoBlocksArchive {
        val g = game?.takeIf { it.isValidSnapshot }
        return copy(game = g, best = max(0, best), discoveredImages = discoveredImages.coerceIn(0, g?.discoveredPhotos ?: 0))
    }
}

/** Nur Metadaten-Reihenfolge (keine Bilddaten, kein Cache, keine Analyse). */
class PhotoBlocksPhotos(ids: List<String>, shuffle: Boolean, seed: ULong) {
    @Serializable enum class Source { RANDOM, NEWEST, FAVORITES, SELECTION }

    var order: List<String>
        private set

    init {
        val seen = mutableSetOf<String>()
        val unique = ids.filter { it.isNotEmpty() && seen.add(it) }.toMutableList()
        if (shuffle) PhotoBlocksRandom(seed).shuffle(unique)
        order = unique
    }

    /** Erst alle Kandidaten, dann im selben Kreislauf wiederholen. Leer = neutrales Ersatzbild. */
    fun id(level: Int): String? = if (order.isEmpty()) null else order[max(0, level - 1) % order.size]

    fun restrict(allowed: Set<String>) { order = order.filter { it in allowed } }

    companion object {
        /** Feste Streifen im Fotoraum; Brettzeilen spielen keine Rolle. */
        fun revealedHeight(strips: Int): Double = strips.coerceIn(0, 10) / 10.0
    }
}

/** Ein Erkenner für Tippen, achsengebundenes Ziehen und Wischen beim Loslassen (Port von iOS `PhotoBlocksInput`). */
class PhotoBlocksInput {
    sealed interface Action {
        data object Left : Action
        data object Right : Action
        data object Rotate : Action
        data class SoftDrop(val enabled: Boolean) : Action
        data object HardDrop : Action
    }

    private enum class Axis { HORIZONTAL, VERTICAL }
    private var axis: Axis? = null
    private var anchorX = 0.0
    private var lastY = 0.0
    private var lastTime = 0.0
    private var velocityY = 0.0
    private var maxDistance = 0.0
    private var dropping = false
    private var engaged = false
    private var movedHorizontally = false

    fun changed(x: Double, y: Double, time: Double, cellSize: Double): List<Action> {
        if (!listOf(x, y, time, cellSize).all { it.isFinite() } || cellSize <= 0) return emptyList()
        if (!engaged) { engaged = true; lastTime = time }
        val dt = time - lastTime
        if (dt > 0.001) velocityY = (y - lastY) / dt
        lastY = y; lastTime = time; maxDistance = max(maxDistance, hypot(x, y))
        if (axis == null && max(abs(x), abs(y)) >= 12) {
            if (abs(x) >= abs(y) * 1.15) axis = Axis.HORIZONTAL
            else if (abs(y) >= abs(x) * 1.15) axis = Axis.VERTICAL
        }
        val result = mutableListOf<Action>()
        if (axis == Axis.HORIZONTAL) {
            val step = max(18.0, cellSize * 0.65)
            if (!movedHorizontally && abs(x) >= 12) {
                result += if (x < 0) Action.Left else Action.Right
                anchorX = if (x < 0) -12.0 else 12.0; movedHorizontally = true
            }
            while (abs(x - anchorX) >= step && result.size < 14) {
                val right = x > anchorX
                result += if (right) Action.Right else Action.Left
                anchorX += if (right) step else -step
            }
        } else if (axis == Axis.VERTICAL) {
            val enabled = y >= 18
            if (enabled != dropping) { dropping = enabled; result += Action.SoftDrop(enabled) }
        }
        return result
    }

    fun ended(x: Double, y: Double, duration: Double, time: Double, cellSize: Double): List<Action> {
        val oldTime = lastTime
        val previousVelocity = velocityY
        val result = changed(x, y, time, cellSize).toMutableList()
        val average = if (duration > 0) y / duration else 0.0
        val recentVelocity = if (time - oldTime <= 0.08) max(previousVelocity, velocityY) else velocityY
        // Ein Wurf braucht Weg UND Tempo; langsames Ziehen wird nie zum Fallenlassen.
        if (axis == Axis.VERTICAL && y >= 56 && duration <= 0.55 && max(average, recentVelocity) >= 650) {
            result += Action.HardDrop
        } else if (axis == null && maxDistance < 10 && duration >= 0 && duration <= 0.28) {
            result += Action.Rotate
        }
        if (dropping) result += Action.SoftDrop(false)
        reset(); return result
    }

    fun cancel() = reset()

    private fun reset() {
        axis = null; anchorX = 0.0; lastY = 0.0; lastTime = 0.0; velocityY = 0.0
        maxDistance = 0.0; dropping = false; engaged = false; movedHorizontally = false
    }
}
