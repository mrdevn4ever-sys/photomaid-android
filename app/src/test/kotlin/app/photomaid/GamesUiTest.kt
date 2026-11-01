package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.games.PhotoGameRecords
import app.photomaid.feature.games.GamesScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * „Kurze Pause“ (iOS-Port): Übersicht öffnet die drei Spiele, eine Paare-Runde mit Motiven lässt sich zu Ende spielen,
 * ergibt einen Bestwert – und Spielen ist nie eine Entscheidung (kein Ledger-Eintrag).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "+w393dp-h852dp")
class GamesUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel

    private fun click(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); rule.waitForIdle() }
    private fun waitTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** Uhr von Hand vorstellen (Spielschleifen zeichnen laufend, die App wird nie „idle“). */
    private fun pump(ms: Long) {
        repeat((ms / 16).toInt().coerceAtLeast(1)) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            rule.mainClock.advanceTimeByFrame()
        }
    }

    private fun open() {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 4, videos = 0) })
        rule.setContent { TestHost(app) { GamesScreen(onClose = {}) } }
        waitTag("games")
    }

    @Test fun hubOpensEveryGameAndComesBack() {
        open()
        for ((tile, screen) in listOf("games.memory" to "memory", "games.snake" to "snake", "games.blocks" to "blocks")) {
            click(tile); waitTag(screen)
            click("$screen.otherGames"); waitTag("games")
        }
        click("games.records"); waitTag("records")
    }

    @Test fun memoryWithMotifsFinishesAndRecordsWithoutAnyDecision() {
        open()
        click("games.memory"); waitTag("memory.symbols")
        click("memory.symbols"); waitTag("memory.card.0")
        // Paare über die Zustandsbeschreibung finden (das Motiv steht erst nach dem Aufdecken darin).
        val cards = 12
        val known = mutableMapOf<Int, String>()
        val matched = mutableSetOf<Int>()
        fun tap(i: Int): String {
            click("memory.card.$i")
            val node = rule.onNodeWithTag("memory.card.$i").fetchSemanticsNode()
            return node.config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].substringAfterLast(", ")
        }
        var guard = 0
        while (matched.size < cards && guard++ < 100) {
            val pair = known.entries.groupBy({ it.value }, { it.key }).values.firstOrNull { it.size == 2 && it.none { x -> x in matched } }
            if (pair != null) { tap(pair[0]); tap(pair[1]); matched += pair; continue }
            val a = (0 until cards).first { it !in matched && it !in known }
            val va = tap(a); known[a] = va
            val partner = known.entries.firstOrNull { it.key != a && it.value == va && it.key !in matched }?.key
            if (partner != null) { tap(partner); matched += setOf(a, partner); continue }
            val b = (0 until cards).first { it !in matched && it !in known }
            val vb = tap(b); known[b] = vb
            if (vb == va) matched += setOf(a, b)
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("memory.finished").fetchSemanticsNodes().isNotEmpty() }
        val records = runBlocking { withContext(Dispatchers.IO) { app.games.records.value } }
        assertTrue(records.memoryBest(PhotoGameRecords.MemoryCategory(6, usesPhotos = false)) != null)
        // Spielen ist nie eine Entscheidung: nichts im Papierkorb, nichts zurückgestellt.
        assertEquals(0, app.read.value.basket.size)
        assertEquals(0, app.read.value.laterRecords.size)
    }

    @Test fun snakeStartsWithoutPhotosAndOnlySwipesSteer() {
        open()
        click("games.snake"); waitTag("snake.symbols")
        // Die Spielschleife zeichnet laufend: Uhr ab hier von Hand vorstellen.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("snake.symbols").performSemanticsAction(SemanticsActions.OnClick)
        pump(1_000)
        assertTrue(rule.onAllNodesWithTag("snake.board").fetchSemanticsNodes().isNotEmpty())
        // Keine Richtungstasten – nur Start/Weiter als Knopf.
        assertTrue(rule.onAllNodesWithTag("snake.up").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("snake.board").performTouchInput { swipeUp() }
        pump(2_000)
        assertTrue(rule.onAllNodesWithTag("snake.pause").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("snake.pause").performSemanticsAction(SemanticsActions.OnClick)
        pump(500)
        assertTrue(rule.onAllNodesWithTag("snake.start").fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, app.read.value.basket.size)
    }

    @Test fun blocksStartsTapRotatesAndPauseSavesOnlyNumbers() {
        open()
        click("games.blocks"); waitTag("blocks.start")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("blocks.start").performSemanticsAction(SemanticsActions.OnClick)
        var tries = 0
        while (rule.onAllNodesWithTag("blocks.board").fetchSemanticsNodes().isEmpty() && tries++ < 200) {
            pump(100); Thread.sleep(20)
        }
        rule.onNodeWithTag("blocks.board").performTouchInput { click() }
        pump(1_000)
        rule.onNodeWithTag("blocks.pause").performSemanticsAction(SemanticsActions.OnClick)
        pump(500)
        assertTrue(rule.onAllNodesWithTag("blocks.continue").fetchSemanticsNodes().isNotEmpty())
        tries = 0
        while (app.games.blocks.value.game == null && tries++ < 200) { pump(100); Thread.sleep(20) }
        val saved = app.games.blocks.value
        assertTrue(saved.game!!.isValidSnapshot)
    }
}
