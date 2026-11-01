package app.photomaid.feature.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.model.LocalDay
import app.photomaid.core.progress.AchievementGroup
import app.photomaid.core.progress.AchievementID
import app.photomaid.core.progress.AchievementInput
import app.photomaid.core.progress.AchievementState
import app.photomaid.core.progress.Achievements
import app.photomaid.core.progress.ReviewProgress
import app.photomaid.design.AppTypography
import app.photomaid.navigation.LocalApp
import java.text.DateFormat
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/** Tagesziel wie iOS-Standard (Einstellungen › Tagesziel 50); auf Android noch nicht einstellbar. */
private const val DAILY_GOAL = 50

/** Abzeichen, deren Daten auf Android (noch) nicht angebunden sind, werden nicht gezeigt statt falsch „offen“. */
private val NOT_WIRED = setOf(AchievementID.LIBRARY_DONE, AchievementID.FIRST_MONTH_DONE, AchievementID.TWELVE_MONTHS_DONE,
    AchievementID.FIRST_COLLAGE, AchievementID.FIRST_PDF, AchievementID.FIRST_FILM)

/**
 * „Erfolge“ (Port von iOS `AchievementsView`, Gamification 21.10.2026): Level, Serie mit Pausentag je Woche, Tagesziel,
 * diese Woche, Abzeichen. Nur aus echten Ledger-Daten und eigenen Spielwerten, nichts geschätzt, nichts gespeichert;
 * Löschen wird nie besonders belohnt. Nie eine Entscheidung.
 */
@Composable
fun AchievementsScreen(onClose: () -> Unit) {
    val app = LocalApp.current
    val read by app.read.collectAsState()
    val records by app.games.records.collectAsState()
    val blocks by app.games.blocks.collectAsState()
    LaunchedEffect(Unit) { app.games.load() }
    val zone = ZoneId.systemDefault()
    val today = LocalDay.of(java.time.Instant.now(), zone)
    val progress = remember(read.events) {
        ReviewProgress.compute(read.events, app.ledger.stateFlow.value.records.values, today, zone)
    }
    val states = remember(progress, records, blocks) {
        Achievements.evaluate(AchievementInput(progress, zone,
            laterEverUsed = read.events.any { it.to.disposition == Disposition.LATER }, laterOpen = read.laterRecords.size,
            dailyGoal = DAILY_GOAL, pairsTimes = records.memory.map { it.category.pairs to it.seconds },
            snakeBest = records.snakeOverallBest, blocksBest = blocks.best))
    }
    val nf = NumberFormat.getIntegerInstance()
    GameScaffold(stringResource(R.string.ach_title), "achievements", onClose = onClose, onOtherGames = null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.ach_sub), color = GameColors.secondary, style = AppTypography.subheadline)
            val level = progress.level
            GameSetupCard(stringResource(R.string.ach_level)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${level.number}", style = AppTypography.largeTitle, color = GameColors.accent)
                    Text(nf.format(level.total) + " " + stringResource(R.string.ach_reviewed_label), color = GameColors.text, style = AppTypography.subheadline)
                }
                LinearProgressIndicator(progress = { level.fraction.toFloat() }, Modifier.fillMaxWidth(), color = GameColors.accent, trackColor = GameColors.control)
                Text((level.remaining?.let { stringResource(R.string.ach_level_next, nf.format(it), level.number + 1) } ?: stringResource(R.string.ach_level_max)) +
                    " " + stringResource(R.string.ach_equal_note), color = GameColors.secondary, style = AppTypography.footnote)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    GameSetupCard(stringResource(R.string.ach_streak)) {
                        Text(nf.format(progress.streak) + " " + pluralStringResource(R.plurals.ach_days_unit, progress.streak),
                            Modifier.testTag("achievements.streak"), style = AppTypography.title, color = GameColors.text)
                    }
                }
                Box(Modifier.weight(1f)) {
                    GameSetupCard(stringResource(R.string.ach_goal)) {
                        Text(stringResource(R.string.ach_goal_progress, nf.format(progress.reviewedToday), nf.format(DAILY_GOAL)), style = AppTypography.title, color = GameColors.text)
                        Text(if (progress.reviewedToday >= DAILY_GOAL) stringResource(R.string.ach_goal_done) else stringResource(R.string.ach_goal_of, nf.format(DAILY_GOAL)),
                            color = GameColors.secondary, style = AppTypography.footnote)
                    }
                }
            }
            GameSetupCard(stringResource(R.string.ach_week)) {
                val week = progress.currentWeek
                val max = (week.maxOfOrNull { it.reviewed } ?: 0).coerceAtLeast(1)
                Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    week.forEach { d ->
                        val name = d.day.toLocalDate().dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                        Column(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "$name: ${d.reviewed}" },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Box(Modifier.fillMaxWidth().weight(1f, fill = false).height((64f * d.reviewed / max).coerceAtLeast(3f).dp)
                                .clip(RoundedCornerShape(4.dp)).background(if (d.reviewed > 0) GameColors.accent else GameColors.control))
                            Text(name, color = if (d.day == progress.today) GameColors.accent else GameColors.secondary, style = AppTypography.caption)
                        }
                    }
                }
                Text(stringResource(R.string.ach_reviewed_n, nf.format(progress.currentWeek.sumOf { it.reviewed })), color = GameColors.secondary, style = AppTypography.footnote)
            }
            val shown = AchievementID.entries.filter { it !in NOT_WIRED }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ach_badges), style = AppTypography.title, color = GameColors.text)
                Text(stringResource(R.string.ach_badges_count, shown.count { states[it]?.isEarned == true }, shown.size), color = GameColors.secondary)
            }
            AchievementGroup.entries.forEach { group ->
                val ids = shown.filter { it.group == group }
                if (ids.isNotEmpty()) GameSetupCard(stringResource(groupName(group))) {
                    ids.forEach { id -> BadgeRow(badgeTitle(id, nf), states[id]) }
                }
            }
        }
    }
}

@Composable
private fun BadgeRow(title: String, state: AchievementState?) {
    val earned = state is AchievementState.Earned
    val detail = when (state) {
        is AchievementState.Earned -> state.at?.let { stringResource(R.string.ach_earned_on, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(it))) }
            ?: stringResource(R.string.ach_earned)
        is AchievementState.Open -> state.remaining?.takeIf { it > 0 }?.let { stringResource(R.string.ach_remaining, NumberFormat.getIntegerInstance().format(it)) }
        null -> null
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(if (earned) GameColors.accent.copy(alpha = 0.18f) else GameColors.ground)
            .border(1.dp, if (earned) GameColors.accent else GameColors.control, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (earned) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, Modifier.size(20.dp),
                tint = if (earned) GameColors.accent else GameColors.secondary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (earned) GameColors.text else GameColors.secondary, style = AppTypography.body.copy(fontWeight = FontWeight.SemiBold))
            if (detail != null) Text(detail, color = GameColors.secondary, style = AppTypography.footnote)
        }
    }
}

private fun groupName(g: AchievementGroup) = when (g) {
    AchievementGroup.START -> R.string.ach_group_start; AchievementGroup.REVIEWED -> R.string.ach_group_reviewed
    AchievementGroup.STREAK -> R.string.ach_group_streak; AchievementGroup.ORDER -> R.string.ach_group_order
    AchievementGroup.STUDIO -> R.string.tab_studio; AchievementGroup.GAMES -> R.string.ach_group_games
}

@Composable
private fun badgeTitle(id: AchievementID, nf: NumberFormat): String = when (id) {
    AchievementID.FIRST_DECISION -> stringResource(R.string.ach_first_decision)
    AchievementID.ACTIVE_DAYS_3 -> stringResource(R.string.ach_active_3)
    AchievementID.ACTIVE_DAYS_7 -> stringResource(R.string.ach_active_7)
    AchievementID.REVIEWED_100 -> stringResource(R.string.ach_reviewed_n, nf.format(100))
    AchievementID.REVIEWED_1000 -> stringResource(R.string.ach_reviewed_n, nf.format(1_000))
    AchievementID.REVIEWED_5000 -> stringResource(R.string.ach_reviewed_n, nf.format(5_000))
    AchievementID.REVIEWED_10000 -> stringResource(R.string.ach_reviewed_n, nf.format(10_000))
    AchievementID.LIBRARY_DONE -> stringResource(R.string.ach_library_done)
    AchievementID.STREAK_3 -> stringResource(R.string.ach_streak_3)
    AchievementID.STREAK_7 -> stringResource(R.string.ach_streak_7)
    AchievementID.STREAK_30 -> stringResource(R.string.ach_streak_30)
    AchievementID.STREAK_100 -> stringResource(R.string.ach_streak_100)
    AchievementID.FIRST_MONTH_DONE -> stringResource(R.string.ach_first_month)
    AchievementID.TWELVE_MONTHS_DONE -> stringResource(R.string.ach_months_12)
    AchievementID.LATER_CLEARED -> stringResource(R.string.ach_later_clear)
    AchievementID.GOAL_10 -> stringResource(R.string.ach_goal_10)
    AchievementID.FIRST_COLLAGE -> stringResource(R.string.ach_first_collage)
    AchievementID.FIRST_PDF -> stringResource(R.string.ach_first_pdf)
    AchievementID.FIRST_FILM -> stringResource(R.string.ach_first_film)
    AchievementID.PAIRS_UNDER_30 -> stringResource(R.string.ach_pairs_under, stringResource(R.string.memory_title), "30")
    AchievementID.PAIRS_12 -> stringResource(R.string.ach_pairs_12)
    AchievementID.SNAKE_50 -> stringResource(R.string.ach_game_photos, stringResource(R.string.snake_title), nf.format(50))
    AchievementID.BLOCKS_5000 -> stringResource(R.string.ach_game_points, stringResource(R.string.blocks_title), nf.format(5_000))
    AchievementID.ALL_GAMES -> stringResource(R.string.ach_all_games)
}
