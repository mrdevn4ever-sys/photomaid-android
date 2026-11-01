package app.photomaid.feature.games

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.progress.GameMedal
import app.photomaid.core.games.PhotoSnakeCharacter
import app.photomaid.design.AppTypography
import app.photomaid.navigation.LocalApp
import kotlinx.coroutines.launch

private enum class Game { MEMORY, SNAKE, BLOCKS }

/**
 * „Kurze Pause“ (Port von iOS `PhotoGamesView`): Paare, Foto-Snake, Foto Blocks und Bestwerte. Immer dunkel,
 * Bernstein-Akzent. Spielen ist nie eine Entscheidung, nie eine Löschung und zeigt keine Werbung.
 */
@Composable
fun GamesScreen(onClose: () -> Unit) {
    val app = LocalApp.current
    var selected by rememberSaveable { mutableStateOf<Game?>(null) }
    var showRecords by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.games.load() }
    BackHandler(selected != null || showRecords) { if (showRecords) showRecords = false else selected = null }
    when (selected) {
        Game.MEMORY -> MemoryScreen(onClose = onClose, onOtherGames = { selected = null })
        Game.SNAKE -> SnakeScreen(onClose = onClose, onOtherGames = { selected = null })
        Game.BLOCKS -> BlocksScreen(onClose = onClose, onOtherGames = { selected = null })
        null -> if (showRecords) RecordsScreen { showRecords = false } else GamesHub(onClose, onRecords = { showRecords = true }) { selected = it }
    }
}

@Composable
private fun GamesHub(onClose: () -> Unit, onRecords: () -> Unit, onSelect: (Game) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val failed by app.games.saveFailed.collectAsState()
    val prefs by app.games.snakePreferences.collectAsState()
    Column(Modifier.fillMaxSize().background(GameColors.ground).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 8.dp).testTag("games"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GameTextButton(stringResource(R.string.back), Modifier.testTag("games.close"), onClick = onClose)
            Box(Modifier.weight(1f))
            Box(Modifier.size(44.dp).clip(CircleShape).background(GameColors.pauseSurface).border(1.dp, GameColors.pauseStroke, CircleShape)
                .clickable(role = Role.Button, onClick = onRecords).testTag("games.records"), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.EmojiEvents, stringResource(R.string.game_records_title), tint = GameColors.accent)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.memory_break), Modifier.semantics { heading() }, style = AppTypography.largeTitle, color = GameColors.text)
            Text(stringResource(R.string.art_pause_intro), style = AppTypography.subheadline, color = GameColors.pauseText)
        }
        if (failed) Text(stringResource(R.string.blocks_save_retry), Modifier.clickable { scope.launch { app.games.retry() } }.padding(8.dp),
            color = GameColors.accent, style = AppTypography.footnote)
        GameTile(stringResource(R.string.memory_title), stringResource(R.string.games_memory_description), "games.memory", { onSelect(Game.MEMORY) }) {
            Icon(Icons.Rounded.Collections, null, Modifier.size(52.dp), tint = GameColors.accent)
        }
        GameTile(stringResource(R.string.snake_title), stringResource(R.string.game_setup_snake_intro), "games.snake", { onSelect(Game.SNAKE) }) {
            Image(painterResource(when (prefs.character) {
                PhotoSnakeCharacter.SNAKE -> R.drawable.snake_head_snake_idle
                PhotoSnakeCharacter.DRAGON -> R.drawable.snake_head_dragon_idle
                PhotoSnakeCharacter.DINO -> R.drawable.snake_head_dino_idle
                PhotoSnakeCharacter.MONSTER -> R.drawable.snake_head_monster_idle
            }), null, Modifier.size(64.dp))
        }
        GameTile(stringResource(R.string.blocks_title), stringResource(R.string.blocks_subtitle), "games.blocks", { onSelect(Game.BLOCKS) }) {
            Icon(Icons.Rounded.GridView, null, Modifier.size(52.dp), tint = GameColors.accent)
        }
    }
}

/** Spielkarte wie iOS: violette Fläche, Bild 84 dp, Titel und Untertitel. */
@Composable
private fun GameTile(title: String, subtitle: String, tag: String, onClick: () -> Unit, art: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(GameColors.pauseSurface).border(1.dp, GameColors.pauseStroke, shape)
        .clickable(role = Role.Button, onClick = onClick).padding(18.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(18.dp)).background(GameColors.ground), contentAlignment = Alignment.Center) { art() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = AppTypography.title, color = GameColors.text)
            Text(subtitle, style = AppTypography.subheadline, color = GameColors.pauseText)
        }
    }
}

/** Bestwerte (Port von iOS `PhotoGameRecordsView`, vereinfacht): Paare je Kategorie, Snake und Blocks je ein Rekord. */
@Composable
private fun RecordsScreen(onClose: () -> Unit) {
    val app = LocalApp.current
    val records by app.games.records.collectAsState()
    val blocks by app.games.blocks.collectAsState()
    GameScaffold(stringResource(R.string.game_records_title), "records", onClose = onClose, onOtherGames = null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GameSetupCard(stringResource(R.string.memory_title)) {
                val list = records.memory.sortedBy { it.category.pairs }
                if (list.isEmpty()) Text(stringResource(R.string.game_no_best), color = GameColors.secondary)
                list.forEach { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.memory_pair_option, r.category.pairs, r.category.pairs * 2) + " · " +
                            stringResource(if (r.category.usesPhotos) R.string.game_record_photos else R.string.game_record_symbols), color = GameColors.text)
                        Text(gameDuration(r.seconds) + medal(GameMedal.pairs(r.seconds, r.category.pairs)), color = GameColors.accent)
                    }
                }
            }
            GameSetupCard(stringResource(R.string.snake_title)) {
                val best = records.snakeOverallBest
                Text(best?.let { stringResource(R.string.game_best_eaten, it) + medal(GameMedal.snake(it)) } ?: stringResource(R.string.snake_record_empty), color = GameColors.text)
            }
            GameSetupCard(stringResource(R.string.blocks_title)) {
                Text(if (blocks.best > 0) stringResource(R.string.blocks_best, blocks.best) + medal(GameMedal.blocks(blocks.best)) else stringResource(R.string.game_no_best), color = GameColors.text)
            }
            Text(stringResource(R.string.game_records_local_android), color = GameColors.secondary, style = AppTypography.footnote)
        }
    }
}

private fun medal(m: GameMedal) = when (m) { GameMedal.GOLD -> "  🥇"; GameMedal.SILVER -> "  🥈"; GameMedal.BRONZE -> "  🥉"; GameMedal.NONE -> "" }
