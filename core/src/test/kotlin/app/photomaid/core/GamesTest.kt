package app.photomaid.core

import app.photomaid.core.games.PhotoBlocksArchive
import app.photomaid.core.games.PhotoBlocksCell
import app.photomaid.core.games.PhotoBlocksGame
import app.photomaid.core.games.PhotoBlocksInput
import app.photomaid.core.games.PhotoBlocksInput.Action
import app.photomaid.core.games.PhotoBlocksPhotos
import app.photomaid.core.games.PhotoBlocksPiece
import app.photomaid.core.games.PhotoBlocksShape
import app.photomaid.core.games.PhotoGameRecords
import app.photomaid.core.games.PhotoGameStopwatch
import app.photomaid.core.games.PhotoMemoryBoardLayout
import app.photomaid.core.games.PhotoMemoryGame
import app.photomaid.core.games.PhotoMemorySelection
import app.photomaid.core.games.PhotoSnakeGame
import app.photomaid.core.games.PhotoSnakeGame.Cell
import app.photomaid.core.games.PhotoSnakeGame.Direction
import app.photomaid.core.games.PhotoSnakeSpeed
import app.photomaid.core.games.PhotoSnakeSwipe
import app.photomaid.core.games.PhotoSnakeViewport
import app.photomaid.core.model.PhotoMaidJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port der iOS-Tests PhotoMemoryGameTests, PhotoSnakeGameTests, PhotoGameRecordsTests, PhotoBlocks*Tests. */
class GamesTest {
    // ---------- Paare ----------
    @Test fun memoryRejectsUnplayableDecksAndDeduplicates() {
        assertNull(PhotoMemoryGame.create(emptyList()))
        assertNull(PhotoMemoryGame.create(listOf("a", "a", "")))
        assertNull(PhotoMemoryGame.create((0 until 19).map { "$it" }))
        val g = PhotoMemoryGame.create(listOf("a", "b", "a", ""), shuffled = false)!!
        assertEquals(2, g.pairCount)
        assertEquals(4, g.cards.map { it.id }.toSet().size)
        assertEquals(2, g.cards.count { it.pairID == "a" })
    }

    @Test fun memoryRapidTapsCannotRevealThirdCard() {
        val g = PhotoMemoryGame.create(listOf("a", "b"), shuffled = false)!!
        assertEquals(PhotoMemoryGame.Turn.IGNORED, g.reveal(-1))
        assertEquals(PhotoMemoryGame.Turn.REVEALED, g.reveal(0))
        assertEquals(PhotoMemoryGame.Turn.IGNORED, g.reveal(0))
        assertEquals(0, g.moves)
        assertEquals(PhotoMemoryGame.Turn.MISMATCHED, g.reveal(2))
        assertEquals(PhotoMemoryGame.Turn.IGNORED, g.reveal(1))
        assertEquals(listOf(0, 2), g.faceUp); assertEquals(1, g.moves)
        g.concealMismatch()
        assertTrue(g.faceUp.isEmpty())
        assertEquals(PhotoMemoryGame.Turn.REVEALED, g.reveal(0))
        assertEquals(PhotoMemoryGame.Turn.MATCHED, g.reveal(1))
        assertEquals(setOf("a"), g.matchedPairs); assertEquals(2, g.moves)
        assertEquals(PhotoMemoryGame.Turn.IGNORED, g.reveal(0))
    }

    @Test fun memorySelectingNewCardDismissesMismatch() {
        val g = PhotoMemoryGame.create(listOf("a", "b", "c"), shuffled = false)!!
        assertEquals(PhotoMemoryGame.Turn.REVEALED, g.select(0))
        assertEquals(PhotoMemoryGame.Turn.MISMATCHED, g.select(2))
        assertEquals(PhotoMemoryGame.Turn.IGNORED, g.select(2))
        assertEquals(PhotoMemoryGame.Turn.REVEALED, g.select(4))
        assertEquals(listOf(4), g.faceUp); assertEquals(1, g.moves)
        assertEquals(PhotoMemoryGame.Turn.MATCHED, g.select(5))
        assertEquals(PhotoMemoryGame.Turn.REVEALED, g.select(0))
        assertEquals(PhotoMemoryGame.Turn.MATCHED, g.select(1))
        assertEquals(PhotoMemoryGame.Turn.REVEALED, g.select(2))
        assertEquals(PhotoMemoryGame.Turn.MATCHED, g.select(3))
        assertTrue(g.isFinished)
    }

    @Test fun memoryShuffledDecksHaveTwoOfEach() {
        for (count in 2..18) {
            val pairs = (0 until count).map { "$it" }
            val g = PhotoMemoryGame.create(pairs)!!
            assertEquals(count * 2, g.cards.size)
            pairs.forEach { p -> assertEquals(2, g.cards.count { it.pairID == p }) }
        }
    }

    @Test fun memorySourcesRespectScopeAndExclusions() {
        val items = listOf(
            PhotoMemorySelection.Candidate("favorite", isFavorite = true), PhotoMemorySelection.Candidate("normal"),
            PhotoMemorySelection.Candidate("video", isPhoto = false, isFavorite = true),
            PhotoMemorySelection.Candidate("vault", isFavorite = true), PhotoMemorySelection.Candidate("normal"),
        )
        assertEquals(listOf("favorite", "normal"), PhotoMemorySelection.pool(items, PhotoMemorySelection.Scope.Library, setOf("vault")))
        assertEquals(listOf("favorite"), PhotoMemorySelection.pool(items, PhotoMemorySelection.Scope.Favorites, setOf("vault")))
        assertEquals(listOf("normal"), PhotoMemorySelection.pool(items, PhotoMemorySelection.Scope.Collection(setOf("normal", "video", "vault")), setOf("vault")))
        val ids = (0 until 1000).map { "$it" }
        for (count in listOf(6, 12, 18)) {
            val chosen = PhotoMemorySelection.sample(ids + ids, count)
            assertEquals(count * 3, chosen.size); assertEquals(chosen.size, chosen.toSet().size)
        }
        assertTrue(PhotoMemorySelection.sample(listOf("a", "b"), 6).isEmpty())
        assertEquals(listOf("only"), PhotoMemorySelection.sample(listOf("only"), 1, minimumCount = 1))
        assertTrue(PhotoMemorySelection.sample(listOf("only"), 1).isEmpty())
    }

    @Test fun memoryBoardLayoutKeepsTouchTargets() {
        val l = PhotoMemoryBoardLayout.of(36, 390.0, 600.0)
        assertTrue(l.cardHeight >= 44)
        assertTrue(l.columns in 1..6)
        assertEquals(2, PhotoMemoryBoardLayout.of(12, 390.0, 600.0, accessibility = true).columns.coerceAtMost(2))
    }

    // ---------- Snake ----------
    @Test fun snakeFirstSwipeChoosesDirectionButCannotTeleport() {
        for (w in 4..24) for (d in Direction.entries) {
            val g = PhotoSnakeGame.create(w, w, seed = 7uL)!!
            assertTrue(g.faceBeforeFirstStep(d))
            assertEquals(3, g.body.toSet().size)
            assertTrue(g.body.all { it.x in 0 until w && it.y in 0 until w })
            assertFalse(g.food!! in g.body)
            g.step()
            assertFalse(g.faceBeforeFirstStep(d.opposite))
        }
    }

    @Test fun snakeViewportMatchesIos() {
        val phone = PhotoSnakeViewport.of(402.0, 874.0)
        assertEquals(9, phone.columns); assertEquals(21, phone.rows)
        val expanded = PhotoSnakeViewport.of(744.0, 1133.0)
        assertEquals(15, expanded.columns); assertEquals(24, expanded.rows)
        assertEquals(15, PhotoSnakeViewport.of(1133.0, 744.0).rows)
        assertEquals(12, PhotoSnakeViewport.of(Double.NaN, 100.0).columns)
        assertEquals(16, PhotoSnakeViewport.of(100.0, Double.POSITIVE_INFINITY).rows)
    }

    @Test fun snakeRapidTurnsBufferedAndCannotReverse() {
        val g = PhotoSnakeGame.create(seed = 1uL)!!
        assertFalse(g.turn(Direction.LEFT))
        assertTrue(g.turn(Direction.UP))
        assertFalse(g.turn(Direction.DOWN))
        assertTrue(g.turn(Direction.LEFT))
        assertFalse(g.turn(Direction.DOWN))
        g.step(); assertEquals(Direction.UP, g.direction)
        g.step(); assertEquals(Direction.LEFT, g.direction)
    }

    @Test fun snakeClassicWallEndsRelaxedWraps() {
        val classic = PhotoSnakeGame.create(6, 6, seed = 3uL)!!
        val relaxed = PhotoSnakeGame.create(6, 6, PhotoSnakeGame.Mode.RELAXED, seed = 3uL)!!
        repeat(3) { classic.step(); relaxed.step() }
        assertEquals(PhotoSnakeGame.Status.LOST, classic.status)
        assertEquals(PhotoSnakeGame.Event.IGNORED, classic.step())
        assertEquals(PhotoSnakeGame.Status.PLAYING, relaxed.status)
        assertEquals(Cell(0, 3), relaxed.body[0])
    }

    @Test fun snakeCollectingGrowsOnce() {
        val g = (0 until 1000).mapNotNull { PhotoSnakeGame.create(6, 6, seed = it.toULong()) }.first { it.food == Cell(4, 3) }
        assertEquals(PhotoSnakeGame.Event.COLLECTED, g.step())
        assertEquals(1, g.score); assertEquals(4, g.body.size)
        assertFalse(g.food!! in g.body)
    }

    @Test fun snakeBodyCollisionEndsRound() {
        // Same LCG as iOS: seed 174 places food at (4,3), then (4,2).
        val g = PhotoSnakeGame.create(6, 6, seed = 174uL)!!
        assertEquals(Cell(4, 3), g.food)
        assertEquals(PhotoSnakeGame.Event.COLLECTED, g.step())
        assertEquals(Cell(4, 2), g.food)
        g.turn(Direction.UP)
        assertEquals(PhotoSnakeGame.Event.COLLECTED, g.step())
        g.turn(Direction.LEFT); g.step()
        g.turn(Direction.DOWN)
        assertEquals(PhotoSnakeGame.Event.LOST, g.step())
    }

    private fun cycleGame(eaten: Int = Int.MAX_VALUE): PhotoSnakeGame {
        val cycle = listOf(Cell(0, 0), Cell(1, 0), Cell(2, 0), Cell(3, 0), Cell(3, 1), Cell(2, 1), Cell(1, 1), Cell(1, 2),
            Cell(2, 2), Cell(3, 2), Cell(3, 3), Cell(2, 3), Cell(1, 3), Cell(0, 3), Cell(0, 2), Cell(0, 1))
        val g = PhotoSnakeGame.create(4, 4, seed = 47uL)!!
        var index = cycle.indexOf(g.body[0])
        for (i in 0 until 500) {
            if (g.status != PhotoSnakeGame.Status.PLAYING || g.score >= eaten) break
            val next = cycle[(index + 1) % cycle.size]; val head = g.body[0]
            g.turn(when { next.x > head.x -> Direction.RIGHT; next.x < head.x -> Direction.LEFT; next.y > head.y -> Direction.DOWN; else -> Direction.UP })
            g.step(); index = (index + 1) % cycle.size
        }
        return g
    }

    @Test fun snakeFullBoardWins() {
        val g = cycleGame()
        assertEquals(PhotoSnakeGame.Status.WON, g.status); assertNull(g.food)
        assertEquals(16, g.body.size); assertEquals(13, g.score)
    }

    @Test fun snakeSwipeUsesPhysicalFingerDirection() {
        assertNull(PhotoSnakeSwipe.direction(10.0, 5.0))
        assertEquals(Direction.RIGHT, PhotoSnakeSwipe.direction(40.0, 10.0))
        assertEquals(Direction.LEFT, PhotoSnakeSwipe.direction(-40.0, 10.0))
        assertEquals(Direction.UP, PhotoSnakeSwipe.direction(5.0, -40.0))
        assertEquals(Direction.DOWN, PhotoSnakeSwipe.direction(5.0, 40.0))
    }

    // ---------- Rekorde ----------
    private fun finishedMemory(pairs: Int = 6) = PhotoMemoryGame.create((0 until pairs).map { "$it" }, shuffled = false)!!
        .also { g -> g.cards.forEach { g.reveal(it.id) } }

    @Test fun recordsSnakeKeepsLargest() {
        val r = PhotoGameRecords()
        val cat = PhotoGameRecords.SnakeCategory(PhotoSnakeGame.Mode.CLASSIC, PhotoSnakeSpeed.NORMAL, false, 4, 4)
        assertFalse(r.recordSnake(cycleGame(0), PhotoSnakeSpeed.NORMAL, false))
        assertTrue(r.recordSnake(cycleGame(3), PhotoSnakeSpeed.NORMAL, false))
        assertFalse(r.recordSnake(cycleGame(2), PhotoSnakeSpeed.NORMAL, false))
        assertFalse(r.recordSnake(cycleGame(3), PhotoSnakeSpeed.NORMAL, false))
        assertEquals(3, r.snakeBest(cat)); assertEquals(3, r.snakeOverallBest)
    }

    @Test fun recordsMemoryRequiresWinAndFasterTime() {
        val r = PhotoGameRecords()
        val unfinished = PhotoMemoryGame.create((0 until 6).map { "$it" }, shuffled = false)!!
        assertFalse(r.recordMemory(unfinished, 1.0, true))
        val g = finishedMemory()
        assertTrue(r.recordMemory(g, 32.0, true))
        assertTrue(r.recordMemory(g, 31.25, true))
        assertFalse(r.recordMemory(g, 35.0, true))
        for (t in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) assertFalse(r.recordMemory(g, t, true))
        assertEquals(31.25, r.memoryBest(PhotoGameRecords.MemoryCategory(6, true))!!, 0.0)
    }

    @Test fun recordsRoundTripAndSanitize() {
        val r = PhotoGameRecords()
        r.recordMemory(finishedMemory(), 20.0, true)
        r.recordSnake(cycleGame(3), PhotoSnakeSpeed.NORMAL, false)
        val json = PhotoMaidJson.encodeToString(PhotoGameRecords.serializer(), r)
        assertTrue(json.contains("\"memory\"") && json.contains("\"snake\""))
        val back = PhotoMaidJson.decodeFromString(PhotoGameRecords.serializer(), json).sanitized()
        assertEquals(r.memory, back.memory); assertEquals(r.snake, back.snake)
        val broken = json.replace("\"seconds\":20.0", "\"seconds\":-1.0")
        assertTrue(PhotoMaidJson.decodeFromString(PhotoGameRecords.serializer(), broken).sanitized().memory.isEmpty())
        assertTrue(PhotoMaidJson.decodeFromString(PhotoGameRecords.serializer(), "{}").memory.isEmpty())
    }

    @Test fun stopwatchExcludesPausesAndRejectsInvalidSamples() {
        val c = PhotoGameStopwatch()
        assertEquals(0.0, c.elapsed(100.0), 0.0); assertFalse(c.hasStarted)
        c.resume(100.0); assertEquals(3.25, c.elapsed(103.25), 1e-9)
        c.pause(104.0); assertEquals(4.0, c.elapsed(10000.0), 1e-9)
        c.resume(10000.0); c.pause(10002.0); assertEquals(6.0, c.elapsed(20000.0), 1e-9)
        c.reset(); c.resume(Double.NaN); assertFalse(c.hasStarted)
        c.resume(10.0); assertEquals(0.0, c.elapsed(9.0), 0.0)
    }

    // ---------- Blocks ----------
    private fun blank(w: Int = 9, h: Int = 17): List<List<PhotoBlocksShape?>> = List(h) { List(w) { null } }
    private fun json(g: PhotoBlocksGame) = PhotoMaidJson.encodeToString(PhotoBlocksGame.serializer(), g)

    @Test fun blocksShapesConnectedAndRotate() {
        assertEquals(7, PhotoBlocksShape.playableShapes.size)
        assertEquals(setOf(4), PhotoBlocksShape.playableShapes.map { it.cells().size }.toSet())
        for (shape in PhotoBlocksShape.entries) {
            for (rot in 0 until 4) {
                val cells = shape.cells(rot).toSet()
                assertEquals(shape.cells().size, cells.size)
                assertTrue(cells.all { it.x >= 0 && it.y >= 0 })
                val visited = mutableSetOf(cells.first())
                repeat(cells.size) {
                    visited.toList().forEach { c ->
                        listOf(PhotoBlocksCell(c.x - 1, c.y), PhotoBlocksCell(c.x + 1, c.y), PhotoBlocksCell(c.x, c.y - 1), PhotoBlocksCell(c.x, c.y + 1))
                            .filter { it in cells }.forEach { visited += it }
                    }
                }
                assertEquals(cells, visited)
            }
            assertEquals(shape.cells(), shape.cells(4))
        }
    }

    @Test fun blocksSeededBagsFairAndReproducible() {
        val a = PhotoBlocksGame.create(14, 28, 42uL); val b = PhotoBlocksGame.create(14, 28, 42uL)
        a.start(); b.start()
        assertEquals(json(a), json(b))
        val drawn = mutableListOf<PhotoBlocksShape>()
        repeat(7) { drawn += a.active!!.shape; a.hardDrop(); b.hardDrop() }
        assertEquals(json(a), json(b))
        assertEquals(PhotoBlocksShape.playableShapes.toSet(), drawn.toSet())
    }

    @Test fun blocksRotateAtWallsAndLockFourCells() {
        for (shape in PhotoBlocksShape.playableShapes) {
            val cells = shape.cells(); val w = cells.maxOf { it.x } + 1; val h = cells.maxOf { it.y } + 1
            for (x in listOf(0, 9 - w)) {
                val g = PhotoBlocksGame.forTest(blank(), PhotoBlocksPiece(shape, 0, PhotoBlocksCell(x, 17 - h)))
                repeat(4) { assertTrue(g.rotate()); assertTrue(g.fits(g.active!!)) }
                g.hardDrop()
                assertEquals(1L, g.piecesPlaced)
                assertEquals(4, g.board.flatten().count { it != null })
                assertTrue(g.isValidSnapshot)
            }
        }
    }

    @Test fun blocksLineClearsFourRowsAndReveals40Percent() {
        val board = blank().map { it.toMutableList() }
        for (y in 13 until 17) { for (x in 0 until 9) board[y][x] = PhotoBlocksShape.SQUARE; board[y][4] = null }
        val g = PhotoBlocksGame.forTest(board, PhotoBlocksPiece(PhotoBlocksShape.LINE, 0, PhotoBlocksCell(4, 13)))
        g.hardDrop()
        assertEquals(listOf(13, 14, 15, 16), g.clearedRows)
        g.advance(0.18)
        assertEquals(4, g.totalRows); assertEquals(0.4, g.progress, 1e-9)
        assertEquals(405L, g.score)
        assertTrue(g.board.all { row -> row.all { it == null } })
    }

    @Test fun blocksGravityAndFloorLockOnce() {
        val g = PhotoBlocksGame.create(); g.start()
        val before = g.active!!.origin.y
        repeat(4) { g.advance(0.22) }
        assertEquals(before + 1, g.active!!.origin.y)
        val floor = PhotoBlocksGame.forTest(blank(), PhotoBlocksPiece(PhotoBlocksShape.PEBBLE, 0, PhotoBlocksCell(2, 16)))
        floor.advance(0.20); assertEquals(0L, floor.piecesPlaced)
        floor.advance(0.20); assertEquals(1L, floor.piecesPlaced)
        assertEquals(5L, floor.score)
    }

    @Test fun blocksTenRowsDiscoverPhotoAndNextLevel() {
        val board = blank().map { it.toMutableList() }
        for (y in 13 until 17) { for (x in 0 until 9) board[y][x] = PhotoBlocksShape.SQUARE; board[y][4] = null }
        val g = PhotoBlocksGame.forTest(board, PhotoBlocksPiece(PhotoBlocksShape.LINE, 0, PhotoBlocksCell(4, 13)), revealed = 8)
        g.hardDrop(); g.advance(0.18)
        assertEquals(PhotoBlocksGame.Phase.DISCOVERED, g.phase)
        assertEquals(1, g.discoveredPhotos); assertTrue(g.isValidSnapshot)
        g.advance(2.0)
        assertEquals(2, g.level); assertEquals(2, g.revealedStrips)
        assertEquals(PhotoBlocksGame.Phase.PLAYING, g.phase)
    }

    @Test fun blocksArchiveRoundTripAndRejectsBrokenSnapshot() {
        val g = PhotoBlocksGame.create(seed = 9uL); g.start(); g.hardDrop()
        val archive = PhotoBlocksArchive(g, best = 1234)
        val text = PhotoMaidJson.encodeToString(PhotoBlocksArchive.serializer(), archive)
        val back = PhotoMaidJson.decodeFromString(PhotoBlocksArchive.serializer(), text).sanitized()
        assertEquals(1234L, back.best); assertNotNull(back.game); assertEquals(json(g), json(back.game!!))
        val broken = text.replace("\"levelValue\":1", "\"levelValue\":0")
        assertNull(PhotoMaidJson.decodeFromString(PhotoBlocksArchive.serializer(), broken).sanitized().game)
    }

    @Test fun blocksPhotosCycleAndRestrict() {
        val p = PhotoBlocksPhotos(listOf("a", "b", "a", "", "c"), shuffle = false, seed = 1uL)
        assertEquals(listOf("a", "b", "c"), p.order)
        assertEquals("a", p.id(1)); assertEquals("c", p.id(3)); assertEquals("a", p.id(4))
        p.restrict(setOf("b")); assertEquals("b", p.id(7))
        assertNull(PhotoBlocksPhotos(emptyList(), true, 1uL).id(1))
        val s1 = PhotoBlocksPhotos((0 until 20).map { "$it" }, true, 5uL).order
        assertEquals(s1, PhotoBlocksPhotos((0 until 20).map { "$it" }, true, 5uL).order)
    }

    @Test fun blocksInputSwipesTapsAndFlicks() {
        for (sign in listOf(-1.0, 1.0)) {
            val input = PhotoBlocksInput()
            assertEquals(listOf(if (sign < 0) Action.Left else Action.Right), input.changed(sign * 16, 2.0, 0.08, 32.0))
            assertEquals(3, input.changed(sign * 80, 4.0, 0.25, 32.0).size)
            val end = input.ended(sign * 80, 4.0, 0.27, 0.27, 32.0)
            assertFalse(Action.Rotate in end || Action.HardDrop in end)
        }
        val input = PhotoBlocksInput()
        assertEquals(listOf(Action.Rotate), input.ended(2.0, 3.0, 0.14, 0.14, 30.0))
        assertTrue(input.ended(0.0, 0.0, 0.8, 0.8, 30.0).isEmpty())
        assertEquals(listOf(Action.SoftDrop(true)), input.changed(2.0, 24.0, 0.12, 30.0))
        assertEquals(listOf(Action.SoftDrop(false)), input.ended(3.0, 100.0, 0.9, 0.9, 30.0))
        assertEquals(1, input.ended(4.0, 120.0, 0.13, 0.13, 30.0).count { it == Action.HardDrop })
        assertTrue(input.ended(0.0, -150.0, 0.1, 0.1, 30.0).isEmpty())
        assertEquals(List(5) { Action.Right }, input.changed(100.0, 0.0, 0.2, 30.0))
    }
}
