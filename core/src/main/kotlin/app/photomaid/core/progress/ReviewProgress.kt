package app.photomaid.core.progress

import app.photomaid.core.decisions.DecisionEvent
import app.photomaid.core.decisions.DecisionSnapshot
import app.photomaid.core.decisions.HistoryKind
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.LocalDay
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

/**
 * Fortschritt fürs Durchsehen (Port von iOS, Gamification 21.10.2026). Nur aus echten Ledger-Daten abgeleitet, nichts
 * davon wird gespeichert. „Durchgesehen“ wie bei den Monaten ([ProgressRules.isReviewed]): Behalten, Papierkorb, entfernt
 * und Später mit/ohne Termin zählen gleich – Löschen wird nie besonders belohnt.
 */
data class ReviewProgress(
    val totalReviewed: Int,
    val perDay: Map<LocalDay, Int>,
    val today: LocalDay,
    val streak: Int,
    /** Überbrückte Pausentage der laufenden Serie (höchstens einer je Woche). */
    val pauseDays: Set<LocalDay>,
    val longestStreak: Int,
    /** Erreichte Schwellen (erstmals durchgesehene Medien) mit Zeitpunkt. */
    val reviewedMilestones: Map<Int, Instant>,
    /** Erreichte Serienlängen mit dem Tag des Erreichens. */
    val streakMilestones: Map<Int, LocalDay>,
    val activeDays: List<LocalDay>,
    val firstDecisionAt: Instant?,
) {
    data class Day(val day: LocalDay, val reviewed: Int)
    data class Week(val monday: LocalDay, val reviewed: Int, val activeDays: Int)

    val reviewedToday: Int get() = perDay[today] ?: 0
    val level: ReviewLevel get() = ReviewLevel(totalReviewed)

    /** Durchgesehen je Tag der laufenden Woche (Montag bis Sonntag). */
    val currentWeek: List<Day>
        get() { val m = monday(today); return (0 until 7).map { val d = m.addingDays(it); Day(d, perDay[d] ?: 0) } }

    /** Rückblick auf die vorige Woche. */
    val previousWeek: Week
        get() {
            val m = monday(today).addingDays(-7)
            val counts = (0 until 7).map { perDay[m.addingDays(it)] ?: 0 }
            return Week(m, counts.sum(), counts.count { it > 0 })
        }

    /** Tage mit mindestens `goal` durchgesehenen Medien, aufsteigend. */
    fun goalDays(goal: Int): List<LocalDay> = if (goal <= 0) emptyList() else activeDays.filter { (perDay[it] ?: 0) >= goal }

    companion object {
        val EMPTY = ReviewProgress(0, emptyMap(), LocalDay(2000, 1, 1), 0, emptySet(), 0, emptyMap(), emptyMap(), emptyList(), null)
        val REVIEWED_THRESHOLDS = listOf(100, 1_000, 5_000, 10_000)
        val STREAK_THRESHOLDS = listOf(3, 7, 30, 100)

        fun compute(events: List<DecisionEvent>, records: Collection<ItemRecord>, today: LocalDay, zone: ZoneId): ReviewProgress {
            val total = records.count { ProgressRules.isReviewed(it) }
            val daySets = mutableMapOf<LocalDay, MutableSet<ItemReference>>()
            val ever = mutableSetOf<ItemReference>()
            val milestones = mutableMapOf<Int, Instant>()
            var first: Instant? = null
            for (e in events.sortedBy { it.at }) {
                val day = LocalDay.of(e.at, zone)
                when (e.kind) {
                    HistoryKind.DECISION, HistoryKind.CORRECTION -> {
                        if (!isReviewed(e.to)) continue
                        daySets.getOrPut(day) { mutableSetOf() } += e.item
                        if (first == null) first = e.at
                        if (ever.add(e.item)) REVIEWED_THRESHOLDS.filter { ever.size == it }.forEach { milestones[it] = e.at }
                    }
                    HistoryKind.UNDO -> if (!isReviewed(e.to)) daySets[day]?.remove(e.item)
                    else -> Unit
                }
            }
            val perDay = daySets.filterValues { it.isNotEmpty() }.mapValues { it.value.size }
            val active = perDay.keys.sorted()
            val (count, pauses) = currentStreak(active.toSet(), today, active.firstOrNull())
            val (longest, reached) = longest(active, today)
            return ReviewProgress(total, perDay, today, count, pauses, max(longest, count), milestones, reached, active, first)
        }

        internal fun isReviewed(s: DecisionSnapshot): Boolean = ProgressRules.isReviewed(
            ItemRecord(item = ItemReference.photo(""), disposition = s.disposition, dueDay = s.dueDay, bookmarked = s.bookmarked))

        /**
         * Laufende Serie rückwärts ab heute. Heute ohne Entscheidung beendet sie noch nicht. Je Woche (Mo–So) darf ein
         * Tag fehlen; ein zweiter fehlender Tag derselben Woche beendet die Serie.
         */
        internal fun currentStreak(active: Set<LocalDay>, today: LocalDay, first: LocalDay?): Pair<Int, Set<LocalDay>> {
            first ?: return 0 to emptySet()
            var day = if (today in active) today else today.addingDays(-1)
            var count = 0
            val usedWeeks = mutableSetOf<LocalDay>()
            val pauses = mutableSetOf<LocalDay>()
            val pending = mutableListOf<LocalDay>()
            while (day >= first) {
                if (day in active) { count += 1; pauses += pending; pending.clear() }
                else {
                    val week = monday(day)
                    if (week in usedWeeks) break
                    usedWeeks += week; pending += day
                }
                day = day.addingDays(-1)
            }
            return count to if (count > 0) pauses else emptySet()
        }

        internal fun longest(active: List<LocalDay>, today: LocalDay): Pair<Int, Map<Int, LocalDay>> {
            val first = active.firstOrNull() ?: return 0 to emptyMap()
            val set = active.toSet()
            var run = 0; var best = 0
            var usedWeeks = mutableSetOf<LocalDay>()
            val reached = mutableMapOf<Int, LocalDay>()
            var day = first
            while (day <= today) {
                if (day in set) {
                    run += 1; best = max(best, run)
                    STREAK_THRESHOLDS.filter { run == it && it !in reached }.forEach { reached[it] = day }
                } else {
                    val week = monday(day)
                    if (week in usedWeeks) { run = 0; usedWeeks = mutableSetOf() } else usedWeeks += week
                }
                day = day.addingDays(1)
            }
            return best to reached
        }

        /** Montag der ISO-Woche. */
        fun monday(day: LocalDay): LocalDay = day.addingDays(-(day.toLocalDate().dayOfWeek.value - DayOfWeek.MONDAY.value))
    }
}

/** Level nach Anzahl durchgesehener Medien (nur Zahlen, keine Wertung). */
data class ReviewLevel(private val rawTotal: Int) {
    val total: Int = max(0, rawTotal)
    /** 1 bis 10. */
    val number: Int get() = THRESHOLDS.count { it <= total } + 1
    val floor: Int get() = if (number == 1) 0 else THRESHOLDS[number - 2]
    val next: Int? get() = if (number <= THRESHOLDS.size) THRESHOLDS[number - 1] else null
    val remaining: Int? get() = next?.let { it - total }
    val fraction: Double get() = next?.let { (total - floor).toDouble() / max(1, it - floor) } ?: 1.0

    companion object { val THRESHOLDS = listOf(50, 250, 1_000, 2_500, 5_000, 10_000, 25_000, 50_000, 100_000) }
}

enum class AchievementGroup { START, REVIEWED, STREAK, ORDER, STUDIO, GAMES }

/** Abzeichen (Port von iOS). Nur echte Daten, nie geschätzt; bewusst keines für gelöschte Mengen oder Speicher. */
enum class AchievementID(val group: AchievementGroup) {
    FIRST_DECISION(AchievementGroup.START), ACTIVE_DAYS_3(AchievementGroup.START), ACTIVE_DAYS_7(AchievementGroup.START),
    REVIEWED_100(AchievementGroup.REVIEWED), REVIEWED_1000(AchievementGroup.REVIEWED), REVIEWED_5000(AchievementGroup.REVIEWED),
    REVIEWED_10000(AchievementGroup.REVIEWED), LIBRARY_DONE(AchievementGroup.REVIEWED),
    STREAK_3(AchievementGroup.STREAK), STREAK_7(AchievementGroup.STREAK), STREAK_30(AchievementGroup.STREAK), STREAK_100(AchievementGroup.STREAK),
    FIRST_MONTH_DONE(AchievementGroup.ORDER), TWELVE_MONTHS_DONE(AchievementGroup.ORDER), LATER_CLEARED(AchievementGroup.ORDER), GOAL_10(AchievementGroup.ORDER),
    FIRST_COLLAGE(AchievementGroup.STUDIO), FIRST_PDF(AchievementGroup.STUDIO), FIRST_FILM(AchievementGroup.STUDIO),
    PAIRS_UNDER_30(AchievementGroup.GAMES), PAIRS_12(AchievementGroup.GAMES), SNAKE_50(AchievementGroup.GAMES),
    BLOCKS_5000(AchievementGroup.GAMES), ALL_GAMES(AchievementGroup.GAMES),
}

data class AchievementInput(
    val progress: ReviewProgress,
    val zone: ZoneId,
    val monthsDone: Int = 0,
    val monthsTotal: Int = 0,
    val laterEverUsed: Boolean = false,
    val laterOpen: Int = 0,
    val dailyGoal: Int = 0,
    /** Bestzeiten bei Paare: (Paare, Sekunden). */
    val pairsTimes: List<Pair<Int, Double>> = emptyList(),
    val snakeBest: Int? = null,
    val blocksBest: Long = 0,
    val firstCollage: Instant? = null,
    val firstPDF: Instant? = null,
    val firstFilm: Instant? = null,
)

sealed interface AchievementState {
    data class Earned(val at: Instant?) : AchievementState
    data class Open(val remaining: Long?) : AchievementState
    val isEarned: Boolean get() = this is Earned
}

object Achievements {
    const val PAIRS_UNDER_SECONDS = 30.0
    const val SNAKE_TARGET = 50
    const val BLOCKS_TARGET = 5_000L
    const val GOAL_DAYS_TARGET = 10

    fun evaluate(input: AchievementInput): Map<AchievementID, AchievementState> {
        val p = input.progress
        fun noon(day: LocalDay): Instant = day.startOfDay(input.zone).plusSeconds(12 * 3600)
        fun open(n: Number?) = AchievementState.Open(n?.toLong())
        fun activeDays(n: Int): AchievementState =
            if (p.activeDays.size >= n) AchievementState.Earned(noon(p.activeDays[n - 1])) else open(n - p.activeDays.size)
        fun reviewed(n: Int): AchievementState = p.reviewedMilestones[n]?.let { AchievementState.Earned(it) }
            ?: if (p.totalReviewed >= n) AchievementState.Earned(null) else open(n - p.totalReviewed)
        fun streak(n: Int): AchievementState = p.streakMilestones[n]?.let { AchievementState.Earned(noon(it)) }
            ?: if (max(p.longestStreak, p.streak) >= n) AchievementState.Earned(null) else open(n - p.streak)
        fun flag(done: Boolean, at: Instant? = null): AchievementState = if (done) AchievementState.Earned(at) else open(null)
        val goalDays = p.goalDays(input.dailyGoal)
        val bestPairs = input.pairsTimes.filter { it.first >= 6 }.minOfOrNull { it.second }
        val snake = input.snakeBest ?: 0
        return AchievementID.entries.associateWith { id ->
            when (id) {
                AchievementID.FIRST_DECISION -> flag(p.firstDecisionAt != null, p.firstDecisionAt)
                AchievementID.ACTIVE_DAYS_3 -> activeDays(3)
                AchievementID.ACTIVE_DAYS_7 -> activeDays(7)
                AchievementID.REVIEWED_100 -> reviewed(100)
                AchievementID.REVIEWED_1000 -> reviewed(1_000)
                AchievementID.REVIEWED_5000 -> reviewed(5_000)
                AchievementID.REVIEWED_10000 -> reviewed(10_000)
                AchievementID.LIBRARY_DONE -> flag(input.monthsTotal > 0 && input.monthsDone == input.monthsTotal)
                AchievementID.STREAK_3 -> streak(3)
                AchievementID.STREAK_7 -> streak(7)
                AchievementID.STREAK_30 -> streak(30)
                AchievementID.STREAK_100 -> streak(100)
                AchievementID.FIRST_MONTH_DONE -> if (input.monthsDone >= 1) AchievementState.Earned(null) else open(null)
                AchievementID.TWELVE_MONTHS_DONE -> if (input.monthsDone >= 12) AchievementState.Earned(null) else open(12 - input.monthsDone)
                AchievementID.LATER_CLEARED -> flag(input.laterEverUsed && input.laterOpen == 0)
                AchievementID.GOAL_10 -> if (goalDays.size >= GOAL_DAYS_TARGET) AchievementState.Earned(noon(goalDays[GOAL_DAYS_TARGET - 1]))
                    else open(if (input.dailyGoal > 0) GOAL_DAYS_TARGET - goalDays.size else null)
                AchievementID.FIRST_COLLAGE -> flag(input.firstCollage != null, input.firstCollage)
                AchievementID.FIRST_PDF -> flag(input.firstPDF != null, input.firstPDF)
                AchievementID.FIRST_FILM -> flag(input.firstFilm != null, input.firstFilm)
                AchievementID.PAIRS_UNDER_30 -> flag((bestPairs ?: Double.POSITIVE_INFINITY) < PAIRS_UNDER_SECONDS)
                AchievementID.PAIRS_12 -> flag(input.pairsTimes.any { it.first >= 12 })
                AchievementID.SNAKE_50 -> if (snake >= SNAKE_TARGET) AchievementState.Earned(null) else open(SNAKE_TARGET - snake)
                AchievementID.BLOCKS_5000 -> if (input.blocksBest >= BLOCKS_TARGET) AchievementState.Earned(null) else open(BLOCKS_TARGET - input.blocksBest)
                AchievementID.ALL_GAMES -> flag(input.pairsTimes.isNotEmpty() && snake > 0 && input.blocksBest > 0)
            }
        }
    }
}

/** Persönliche Medaillen je Spiel – nur an eigenen Werten gemessen. */
enum class GameMedal { NONE, BRONZE, SILVER, GOLD;
    companion object {
        val SNAKE_STEPS = listOf(10L, 25L, 50L)
        val BLOCKS_STEPS = listOf(1_000L, 2_500L, 5_000L)

        /** Paare: Sekunden je Paar (≤ 9 Bronze, ≤ 6 Silber, ≤ 4 Gold). */
        fun pairs(seconds: Double, pairs: Int): GameMedal {
            if (pairs <= 0 || !seconds.isFinite()) return NONE
            val per = seconds / pairs
            return if (per <= 4) GOLD else if (per <= 6) SILVER else if (per <= 9) BRONZE else NONE
        }
        fun snake(eaten: Int): GameMedal = entries[SNAKE_STEPS.count { eaten >= it }]
        fun blocks(score: Long): GameMedal = entries[BLOCKS_STEPS.count { score >= it }]
        fun next(value: Long, steps: List<Long>): Pair<GameMedal, Long>? {
            val i = steps.indexOfFirst { value < it }
            return if (i < 0) null else entries[i + 1] to steps[i]
        }
    }
}
