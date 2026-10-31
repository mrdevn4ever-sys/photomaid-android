package app.photomaid.core.games

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Foto-Snake (Port von iOS `PhotoSnakeGame`): diskrete, deterministische Regeln. Keine Uhren, Bilder, Berechtigungen oder
 * Foto-Entscheidungen. Steuerung ausschließlich per Wischgeste (iOS-Nutzerauftrag 16.10.2026: keine Richtungstasten).
 */
class PhotoSnakeGame private constructor(
    val width: Int,
    val height: Int,
    val mode: Mode,
    seed: ULong,
) {
    data class Cell(val x: Int, val y: Int)

    enum class Direction(val dx: Int, val dy: Int) {
        UP(0, -1), RIGHT(1, 0), DOWN(0, 1), LEFT(-1, 0);
        val opposite: Direction get() = when (this) { UP -> DOWN; RIGHT -> LEFT; DOWN -> UP; LEFT -> RIGHT }
    }

    @Serializable enum class Mode { CLASSIC, RELAXED }
    enum class Status { PLAYING, LOST, WON }
    enum class Event { MOVED, COLLECTED, LOST, WON, IGNORED }

    var body: List<Cell> = listOf(Cell(width / 2, height / 2), Cell(width / 2 - 1, height / 2), Cell(width / 2 - 2, height / 2))
        private set
    var direction = Direction.RIGHT
        private set
    var food: Cell? = null
        private set
    var score = 0
        private set
    var status = Status.PLAYING
        private set
    private val turns = ArrayDeque<Direction>()
    private var randomState: ULong = seed
    private var hasStepped = false

    val nextDirection: Direction get() = turns.firstOrNull() ?: direction

    init { placeFood() }

    /** Die erste Wischgeste wählt die Startrichtung (auch links). Eine laufende Runde springt nie. */
    fun faceBeforeFirstStep(proposed: Direction): Boolean {
        if (hasStepped || status != Status.PLAYING) return false
        val head = Cell(if (proposed == Direction.LEFT) (width - 1) / 2 else width / 2,
            if (proposed == Direction.UP) (height - 1) / 2 else height / 2)
        body = (0 until 3).map { Cell(head.x - it * proposed.dx, head.y - it * proposed.dy) }
        direction = proposed; turns.clear()
        food?.let { if (it in body) placeFood() }
        return true
    }

    /** Puffert höchstens zwei echte Kurven; schnelle Eingaben laufen nie rückwärts in den Hals. */
    fun turn(proposed: Direction): Boolean {
        val previous = turns.lastOrNull() ?: direction
        if (status != Status.PLAYING || turns.size >= 2 || proposed == previous || proposed == previous.opposite) return false
        turns.addLast(proposed)
        return true
    }

    fun step(): Event {
        if (status != Status.PLAYING) return Event.IGNORED
        hasStepped = true
        if (turns.isNotEmpty()) direction = turns.removeFirst()
        var next = Cell(body[0].x + direction.dx, body[0].y + direction.dy)
        if (mode == Mode.RELAXED) {
            next = Cell((next.x + width) % width, (next.y + height) % height)
        } else if (next.x < 0 || next.x >= width || next.y < 0 || next.y >= height) {
            status = Status.LOST; return Event.LOST
        }
        val eats = next == food
        // In das wegziehende Schwanzende darf man fahren – außer beim Fressen, dann bleibt es stehen.
        val occupied = if (eats) body else body.dropLast(1)
        if (next in occupied) { status = Status.LOST; return Event.LOST }
        val grown = listOf(next) + body
        if (eats) {
            body = grown
            score += 1
            placeFood()
            return if (status == Status.WON) Event.WON else Event.COLLECTED
        }
        body = grown.dropLast(1)
        return Event.MOVED
    }

    private fun placeFood() {
        val occupied = body.toSet()
        val free = (0 until height).flatMap { y -> (0 until width).map { Cell(it, y) } }.filter { it !in occupied }
        if (free.isEmpty()) { food = null; status = Status.WON; return }
        randomState = randomState * 6364136223846793005uL + 1442695040888963407uL
        food = free[(randomState % free.size.toULong()).toInt()]
    }

    companion object {
        fun create(width: Int = 12, height: Int = 16, mode: Mode = Mode.CLASSIC,
                   seed: ULong = kotlin.random.Random.nextLong(1, Long.MAX_VALUE).toULong()): PhotoSnakeGame? {
            if (width !in 4..24 || height !in 4..24) return null
            return PhotoSnakeGame(width, height, mode, seed)
        }
    }
}

@Serializable
enum class PhotoSnakeSpeed(val interval: Double) { GENTLE(0.32), NORMAL(0.22), FAST(0.14) }

/** Verlauf der Feldmitten in ungewickelten Koordinaten; Abstände entlang des erlaubten rechtwinkligen Wegs. */
class PhotoSnakeMotion(game: PhotoSnakeGame) {
    data class Point(val x: Double, val y: Double)
    data class Line(val a: Point, val b: Point)

    private val history = mutableListOf<Point>()
    private var travel = 0.0
    var count: Int = game.body.size
        private set
    val width = game.width
    val height = game.height

    init {
        for (cell in game.body) {
            var p = Point(cell.x + 0.5, cell.y + 0.5)
            val previous = history.lastOrNull()
            if (previous != null && game.mode == PhotoSnakeGame.Mode.RELAXED) {
                p = Point(previous.x + wrappedDelta(p.x - previous.x, width.toDouble()),
                    previous.y + wrappedDelta(p.y - previous.y, height.toDouble()))
            }
            history += p
        }
    }

    fun advance(game: PhotoSnakeGame, event: PhotoSnakeGame.Event) {
        val head = history.firstOrNull() ?: return
        if (event == PhotoSnakeGame.Event.IGNORED) return
        // Ein Zusammenstoß animiert nur bis zur Berührung, nie in das verbotene Feld.
        travel = if (event == PhotoSnakeGame.Event.LOST) 0.45 else 1.0
        val d = game.direction
        history.add(0, Point(head.x + d.dx * travel, head.y + d.dy * travel))
        count = game.body.size
        while (history.size > count + 4) history.removeAt(history.lastIndex)
    }

    fun points(progress: Double): List<Point> {
        val p = if (progress.isFinite()) progress.coerceIn(0.0, 1.0) else 0.0
        val start = travel * (1 - p)
        var cursor = 0; var traversed = 0.0
        return (0 until count).map { index ->
            val distance = start + index
            while (cursor + 1 < history.size) {
                val a = history[cursor]; val b = history[cursor + 1]
                val length = hypot(b.x - a.x, b.y - a.y)
                if (traversed + length >= distance && length > 0) {
                    val f = (distance - traversed) / length
                    return@map Point(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
                }
                traversed += length; cursor += 1
            }
            history.lastOrNull() ?: Point(0.5, 0.5)
        }
    }

    /** Alle Ecken dazwischen, statt Fotomitten diagonal zu verbinden. */
    fun route(progress: Double, samples: List<Point>? = null): List<Point> {
        val s = samples ?: points(progress)
        val head = s.firstOrNull() ?: return emptyList()
        val tail = s.last()
        val start = travel * (1 - (if (progress.isFinite()) progress else 0.0).coerceIn(0.0, 1.0))
        val end = start + max(0, count - 1)
        var distance = 0.0
        val route = mutableListOf(head)
        for (i in 1 until history.size) {
            val a = history[i - 1]; val b = history[i]
            distance += hypot(b.x - a.x, b.y - a.y)
            if (distance > start && distance < end) route += b
        }
        route += tail
        return route
    }

    fun wrapped(p: Point): Point = Point(mod(p.x, width.toDouble()), mod(p.y, height.toDouble()))

    /** Randübergänge als zwei kurze sichtbare Stücke; keine Linie quer über das ganze Feld. */
    fun lines(progress: Double, samples: List<Point>? = null): List<Line> {
        val route = route(progress, samples)
        if (route.size <= 1) return emptyList()
        val out = mutableListOf<Line>()
        val w = width.toDouble(); val h = height.toDouble()
        for (i in 1 until route.size) {
            val a = wrapped(route[i - 1]); val b = wrapped(route[i])
            val dx = b.x - a.x; val dy = b.y - a.y
            if (abs(dx) > w / 2) {
                val edge = if (dx > 0) 0.0 else w
                out += Line(a, Point(edge, a.y)); out += Line(Point(w - edge, b.y), b)
            } else if (abs(dy) > h / 2) {
                val edge = if (dy > 0) 0.0 else h
                out += Line(a, Point(a.x, edge)); out += Line(Point(b.x, h - edge), b)
            } else out += Line(a, b)
        }
        return out
    }

    private companion object {
        fun mod(n: Double, period: Double): Double { val v = n % period; return if (v < 0) v + period else v }
        fun wrappedDelta(n: Double, period: Double): Double = when {
            n > period / 2 -> n - period
            n < -period / 2 -> n + period
            else -> n
        }
    }
}

/** Feldgröße in Fotogröße; bleibt während einer laufenden Runde fest. */
data class PhotoSnakeViewport(val columns: Int, val rows: Int) {
    companion object {
        fun of(width: Double, height: Double): PhotoSnakeViewport {
            if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) return PhotoSnakeViewport(12, 16)
            val inset = PhotoSnakeLayout.edgeInset(width, height)
            val w = max(1.0, width - 2 * inset); val h = max(1.0, height - 2 * inset)
            val cell = max(PhotoSnakeLayout.MINIMUM_PITCH, max(w, h) / 24)
            return PhotoSnakeViewport(min(24, max(4, floor(w / cell).toInt())), min(24, max(4, floor(h / cell).toInt())))
        }
    }
}

/** Gemeinsame Geometrie für Zeichnen und Tests; ändert nie die Regeln. */
data class PhotoSnakeLayout(val pitch: Double, val photoSide: Double, val gutter: Double) {
    val cornerRadius: Double get() = photoSide * CORNER_FRACTION
    val headSide: Double get() = photoSide * 1.1

    data class Placement(val centre: PhotoSnakeMotion.Point, val alpha: Double)

    companion object {
        const val OVERLAP = 0.125
        const val MINIMUM_PITCH = 44.0 * (1 - OVERLAP)
        const val PHOTO_FRACTION = 1 / (1 - OVERLAP)
        const val CORNER_FRACTION = 0.5

        fun of(width: Double, height: Double, columns: Int, rows: Int): PhotoSnakeLayout {
            val w = if (width.isFinite()) max(1.0, width) else 1.0
            val h = if (height.isFinite()) max(1.0, height) else 1.0
            val gutter = edgeInset(w, h)
            val available = min((w - 2 * gutter) / max(1, columns), (h - 2 * gutter) / max(1, rows))
            val headExtent = 1.1 * 1.06 * sqrt(2.0) / 2
            val pitch = min(min(50 / PHOTO_FRACTION, available), gutter / (headExtent * PHOTO_FRACTION - 0.5))
            return PhotoSnakeLayout(pitch, pitch * PHOTO_FRACTION, gutter)
        }

        fun edgeInset(width: Double, height: Double): Double = min(20.0, max(0.0, min(width, height)) / 12)

        /** Feste Reihenfolge: das Stück näher am Kopf liegt über seinem Nachfolger. */
        fun segmentDepth(index: Int): Double = 5 - max(1, index).toDouble() / 1024

        /** Überblendung an den äußeren Feldmitten (Modus ohne Wände). */
        fun placements(p: PhotoSnakeMotion.Point, width: Double, height: Double, gutter: Double, halfExtent: Double,
                       wraps: Boolean, pitch: Double? = null): List<Placement> {
            val inset = max(0.0, halfExtent - gutter)
            if (!wraps) return listOf(Placement(PhotoSnakeMotion.Point(p.x.coerceIn(inset, max(inset, width - inset)),
                p.y.coerceIn(inset, max(inset, height - inset))), 1.0))
            val band = max(0.0, pitch?.let { it / 2 } ?: (gutter - halfExtent - 0.000001))
            if (band <= 0) return listOf(Placement(p, 1.0))
            fun axis(value: Double, period: Double): List<Pair<Double, Double>> = when {
                value < band -> { val a = 0.5 + 0.5 * max(0.0, value) / band
                    listOf(max(inset, value) to a, min(period - inset, value + period) to 1 - a) }
                value > period - band -> { val a = 0.5 + 0.5 * max(0.0, period - value) / band
                    listOf(min(period - inset, value) to a, max(inset, value - period) to 1 - a) }
                else -> listOf(value to 1.0)
            }
            val xs = axis(p.x, width); val ys = axis(p.y, height)
            return ys.flatMap { y -> xs.map { x -> Placement(PhotoSnakeMotion.Point(x.first, y.first), x.second * y.second) } }
        }
    }
}

@Serializable enum class PhotoSnakeFood { MIXED, APPLE, STAR, HEART, GEM, GRAPES, DONUT, CHEESE, CAKE, PIZZA;
    /** Bei „gemischt“ wechselt das Futter mit dem Punktestand, sonst immer dasselbe. */
    fun kind(score: Int): PhotoSnakeFood {
        val choices = entries.filter { it != MIXED }
        return if (this == MIXED) choices[max(0, score) % choices.size] else this
    }
}

@Serializable enum class PhotoSnakeCharacter { SNAKE, DRAGON, DINO, MONSTER }

@Serializable
data class PhotoSnakePreferences(
    val character: PhotoSnakeCharacter = PhotoSnakeCharacter.SNAKE,
    val night: Boolean = false,
    val sound: Boolean = true,
    val haptics: Boolean = true,
    val shufflePhotos: Boolean = false,
    val food: PhotoSnakeFood = PhotoSnakeFood.MIXED,
    val mode: PhotoSnakeGame.Mode = PhotoSnakeGame.Mode.RELAXED,
    val speed: PhotoSnakeSpeed = PhotoSnakeSpeed.NORMAL,
)

/** Wischrichtung aus der tatsächlichen Fingerbewegung (physisch, in RTL nie gespiegelt). */
object PhotoSnakeSwipe {
    const val MINIMUM_DISTANCE = 16.0
    fun direction(dx: Double, dy: Double): PhotoSnakeGame.Direction? {
        if (!dx.isFinite() || !dy.isFinite() || max(abs(dx), abs(dy)) < MINIMUM_DISTANCE) return null
        return if (abs(dx) > abs(dy)) (if (dx > 0) PhotoSnakeGame.Direction.RIGHT else PhotoSnakeGame.Direction.LEFT)
        else (if (dy > 0) PhotoSnakeGame.Direction.DOWN else PhotoSnakeGame.Direction.UP)
    }
}
