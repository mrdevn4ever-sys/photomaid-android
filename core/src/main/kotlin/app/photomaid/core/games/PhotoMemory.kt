package app.photomaid.core.games

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * „Paare“ (Port von iOS `PhotoMemoryGame`): kleines, unterbrechbares Zuordnungsspiel. Paar-Kennungen sind undurchsichtig –
 * das Spiel liest oder ändert nie ein Foto und ist nie eine Entscheidung.
 */
class PhotoMemoryGame private constructor(val cards: List<Card>) {
    data class Card(val id: Int, val pairID: String)

    enum class Turn { IGNORED, REVEALED, MATCHED, MISMATCHED }

    private val _faceUp = mutableListOf<Int>()
    private val _matched = mutableSetOf<String>()
    val faceUp: List<Int> get() = _faceUp.toList()
    val matchedPairs: Set<String> get() = _matched.toSet()
    var moves = 0
        private set

    val pairCount: Int get() = cards.size / 2
    val isFinished: Boolean get() = _matched.size == pairCount
    val hasMismatch: Boolean get() = _faceUp.size == 2

    fun isRevealed(card: Card): Boolean = card.id in _faceUp || card.pairID in _matched

    /** Ein offener Fehlversuch blockiert eine dritte Karte. Wiederholte Tipps und Tipps auf gefundene Paare zählen nicht. */
    fun reveal(id: Int): Turn {
        if (hasMismatch) return Turn.IGNORED
        val card = cards.firstOrNull { it.id == id } ?: return Turn.IGNORED
        if (isRevealed(card)) return Turn.IGNORED
        _faceUp += id
        if (_faceUp.size != 2) return Turn.REVEALED
        moves += 1
        val first = cards.firstOrNull { it.id == _faceUp[0] }
        if (first?.pairID == card.pairID) {
            _matched += card.pairID
            _faceUp.clear()
            return Turn.MATCHED
        }
        return Turn.MISMATCHED
    }

    fun concealMismatch() { if (hasMismatch) _faceUp.clear() }

    /** Ein bewusster Tipp auf eine andere verdeckte Karte schließt einen offenen Fehlversuch und deckt sie auf. */
    fun select(id: Int): Turn {
        val card = cards.firstOrNull { it.id == id } ?: return Turn.IGNORED
        if (isRevealed(card)) return Turn.IGNORED
        concealMismatch()
        return reveal(id)
    }

    companion object {
        const val MAXIMUM_PAIRS = 18

        /** Bis zu 18 verschiedene Bilder; doppelte Auswahl erzeugt nie mehrdeutige Viererpaare. */
        fun create(pairIDs: List<String>, shuffled: Boolean = true, random: kotlin.random.Random = kotlin.random.Random.Default): PhotoMemoryGame? {
            val seen = mutableSetOf<String>()
            val pairs = pairIDs.filter { it.isNotEmpty() && seen.add(it) }
            if (pairs.size !in 2..MAXIMUM_PAIRS) return null
            var deck = pairs.flatMap { listOf(it, it) }
            if (shuffled) deck = deck.shuffled(random)
            return PhotoMemoryGame(deck.mapIndexed { i, p -> Card(i, p) })
        }
    }
}

/** Fotoauswahl unabhängig vom Spiel: eine Quelle fällt nie auf den Rest der Mediathek zurück. */
object PhotoMemorySelection {
    data class Candidate(val id: String, val isPhoto: Boolean = true, val isFavorite: Boolean = false)

    sealed interface Scope {
        data object Library : Scope
        data object Favorites : Scope
        data class Collection(val ids: Set<String>) : Scope
    }

    fun pool(candidates: List<Candidate>, scope: Scope, excluding: Set<String> = emptySet()): List<String> {
        val seen = mutableSetOf<String>()
        return candidates.mapNotNull { item ->
            if (!item.isPhoto || item.id.isEmpty() || item.id in excluding || !seen.add(item.id)) return@mapNotNull null
            when (scope) {
                Scope.Library -> item.id
                Scope.Favorites -> if (item.isFavorite) item.id else null
                is Scope.Collection -> if (item.id in scope.ids) item.id else null
            }
        }
    }

    /** Gleichverteilte Kandidaten (3× Reserve für nicht ladbare Vorschauen); nie die ganze Mediathek laden. */
    fun sample(ids: List<String>, pairCount: Int, minimumCount: Int = 2, random: kotlin.random.Random = kotlin.random.Random.Default): List<String> {
        if (minimumCount < 1 || minimumCount > PhotoMemoryGame.MAXIMUM_PAIRS || pairCount !in minimumCount..PhotoMemoryGame.MAXIMUM_PAIRS) return emptyList()
        val seen = mutableSetOf<String>()
        val unique = ids.filter { it.isNotEmpty() && seen.add(it) }
        if (unique.size < pairCount) return emptyList()
        return unique.shuffled(random).take(pairCount * 3)
    }
}

/** Passt das Deck in den Bildschirm, ohne eine Karte unter 44 dp Tippfläche zu verkleinern. */
data class PhotoMemoryBoardLayout(val columns: Int, val cardHeight: Double, val height: Double, val requiresScrolling: Boolean) {
    companion object {
        fun of(cards: Int, width: Double, availableHeight: Double, accessibility: Boolean = false): PhotoMemoryBoardLayout {
            val count = max(1, cards); val gap = 8.0
            val w = max(44.0, width); val available = max(44.0, availableHeight)
            val maximum = min(count, if (accessibility) 2 else min(6, max(1, ((w + gap) / (44 + gap)).toInt())))
            var chosen = 1; var chosenHeight = 44.0; var chosenScore = Double.NEGATIVE_INFINITY
            for (cols in 1..maximum) {
                val rows = (count + cols - 1) / cols
                val cellWidth = (w - (cols - 1) * gap) / cols
                val preferred = min(if (accessibility) 180.0 else 112.0, cellWidth * 1.08)
                val cellHeight = max(44.0, min(preferred, (available - (rows - 1) * gap) / rows))
                val total = rows * cellHeight + (rows - 1) * gap
                val fits = total <= available + 0.5
                val shape = min(cellWidth, cellHeight) - abs(cellWidth - cellHeight) * 0.1
                val completeRows = if (count % cols == 0) 8.0 else 0.0
                val score = shape + completeRows + if (fits) 10_000.0 else -total
                if (score > chosenScore) { chosen = cols; chosenHeight = cellHeight; chosenScore = score }
            }
            val rows = (count + chosen - 1) / chosen
            val height = rows * chosenHeight + (rows - 1) * gap
            return PhotoMemoryBoardLayout(chosen, chosenHeight, height, height > available + 0.5)
        }
    }
}
