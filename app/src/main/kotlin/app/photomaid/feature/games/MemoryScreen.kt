package app.photomaid.feature.games

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.games.PhotoGameRecords
import app.photomaid.core.games.PhotoGameStopwatch
import app.photomaid.core.games.PhotoMemoryBoardLayout
import app.photomaid.core.games.PhotoMemoryGame
import app.photomaid.design.AppTypography
import app.photomaid.design.LocalReduceMotion
import app.photomaid.navigation.LocalApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Motive für „Ohne Fotos spielen“ (iOS: sun, leaf, mountain, camera, heart, moon). */
private val MOTIFS = listOf("sun", "leaf", "mountain", "camera", "heart", "moon")
private val MOTIF_ICONS = listOf(Icons.Rounded.WbSunny, Icons.Rounded.Eco, Icons.Rounded.Landscape, Icons.Rounded.PhotoCamera, Icons.Rounded.Favorite, Icons.Rounded.DarkMode)
private val MOTIF_NAMES = listOf(R.string.memory_sun, R.string.memory_leaf, R.string.memory_mountain, R.string.memory_camera, R.string.memory_heart, R.string.memory_moon)

/**
 * „Paare“ (Port von iOS `PhotoMemoryView`): Fotopaare finden, ohne Zeitdruck. Ansehen/Spielen ist nie eine Entscheidung.
 * Fehlpaare bleiben offen, bis eine neue Karte getippt wird (oder „Karten umdrehen“).
 */
@Composable
fun MemoryScreen(onClose: () -> Unit, onOtherGames: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val access by app.photos.access.collectAsState()
    val records by app.games.records.collectAsState()
    var source by remember { mutableStateOf(GamePhotoSource.LIBRARY) }
    var pairCount by remember { mutableIntStateOf(6) }
    var game by remember { mutableStateOf<PhotoMemoryGame?>(null) }
    var images by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var usesPhotos by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf<Job?>(null) }
    var missing by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var newBest by remember { mutableStateOf(false) }
    val clock = remember { PhotoGameStopwatch() }
    var elapsed by remember { mutableDoubleStateOf(0.0) }
    val now = { System.nanoTime() / 1e9 }

    fun begin(ids: List<String>, photos: Boolean) {
        game = PhotoMemoryGame.create(ids)
        usesPhotos = photos; clock.reset(); elapsed = 0.0; newBest = false; revision++
    }
    fun startPhotos() {
        missing = false
        loading = scope.launch {
            val loaded = GamePhotos.load(context, app.photos, source, pairCount)
            loading = null
            if (loaded.size < pairCount) { missing = true; return@launch }
            images = loaded.toMap()
            begin(loaded.map { it.first }, true)
        }
    }
    fun startMotifs() { images = emptyMap(); begin(MOTIFS, false) }
    fun replay() { val g = game ?: return; if (usesPhotos) begin(g.cards.map { it.pairID }.distinct(), true) else startMotifs() }

    // Uhr nur während einer laufenden Runde.
    LaunchedEffect(revision, game?.isFinished) {
        while (game != null && game?.isFinished == false && clock.isRunning) { elapsed = clock.elapsed(now()); delay(200) }
    }

    GameScaffold(stringResource(R.string.memory_title), "memory", onClose = { clock.pause(now()); onClose() },
        onOtherGames = { clock.pause(now()); onOtherGames() }) {
        val g = game
        when {
            loading != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(color = GameColors.accent)
                Text(stringResource(R.string.memory_loading), color = GameColors.secondary, style = AppTypography.subheadline)
                GameTextButton(stringResource(R.string.cancel)) { loading?.cancel(); loading = null }
            }
            g == null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val best = records.memoryBest(PhotoGameRecords.MemoryCategory(pairCount, usesPhotos = true))
                GameIntro(stringResource(R.string.game_setup_pairs_intro), best?.let { stringResource(R.string.game_best_time, gameDuration(it)) }) {
                    Icon(Icons.Rounded.Collections, null, Modifier.size(96.dp), tint = GameColors.accent)
                }
                GameSetupCard(stringResource(R.string.game_setup_photos)) {
                    GameChoices(listOf(GamePhotoSource.LIBRARY to stringResource(R.string.memory_source_library),
                        GamePhotoSource.FAVORITES to stringResource(R.string.memory_source_favorites),
                        GamePhotoSource.NEWEST to stringResource(R.string.blocks_newest)), source) { source = it; missing = false }
                }
                GameSetupCard(stringResource(R.string.memory_size)) {
                    GameChoices(listOf(6, 12, 18).map { it to stringResource(R.string.memory_pair_option, it, it * 2) }, pairCount) { pairCount = it; missing = false }
                }
                if (missing) Text(stringResource(R.string.game_not_enough_photos), color = GameColors.accent, style = AppTypography.footnote)
                Text(stringResource(R.string.game_setup_private), color = GameColors.secondary, style = AppTypography.footnote)
                GameButton(stringResource(R.string.snake_start_game), Modifier.testTag("memory.start"), prominent = true,
                    enabled = access.canRead) { startPhotos() }
                GameButton(stringResource(R.string.game_setup_pairs_symbols), Modifier.testTag("memory.symbols")) { startMotifs() }
            }
            else -> MemoryBoard(g, images, revision, elapsed, newBest,
                best = records.memoryBest(PhotoGameRecords.MemoryCategory(g.pairCount, usesPhotos)),
                onTap = { id ->
                    if (!clock.hasStarted || !clock.isRunning) clock.resume(now())
                    val turn = g.select(id)
                    if (turn != PhotoMemoryGame.Turn.IGNORED) revision++
                    if (g.isFinished) {
                        clock.pause(now()); elapsed = clock.elapsed(now())
                        val seconds = elapsed
                        scope.launch { newBest = app.games.update { it.recordMemory(g, seconds, usesPhotos) } }
                    }
                },
                onFlip = { g.concealMismatch(); revision++ },
                onReplay = { replay() },
                onChange = { clock.reset(); game = null; images = emptyMap() })
        }
    }
}

@Composable
private fun MemoryBoard(
    game: PhotoMemoryGame, images: Map<String, ImageBitmap>, revision: Int, elapsed: Double, newBest: Boolean, best: Double?,
    onTap: (Int) -> Unit, onFlip: () -> Unit, onReplay: () -> Unit, onChange: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.memory_pairs, game.matchedPairs.size, game.pairCount), color = GameColors.text, style = AppTypography.subheadline)
            Text(stringResource(R.string.memory_moves, game.moves), color = GameColors.text, style = AppTypography.subheadline)
            Text(stringResource(R.string.memory_elapsed, gameDuration(elapsed)), Modifier.testTag("memory.elapsed"), color = GameColors.text, style = AppTypography.subheadline)
        }
        Text(best?.let { stringResource(R.string.game_best_time, gameDuration(it)) } ?: stringResource(R.string.game_no_best),
            color = GameColors.secondary, style = AppTypography.footnote)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val layout = PhotoMemoryBoardLayout.of(game.cards.size, maxWidth.value.toDouble(), maxHeight.value.toDouble())
            val gap = 8.dp
            val cellW = (maxWidth - gap * (layout.columns - 1)) / layout.columns
            Column(Modifier.align(Alignment.Center).then(if (layout.requiresScrolling) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(gap)) {
                game.cards.chunked(layout.columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { card -> MemoryCard(card, game.isRevealed(card), card.pairID in game.matchedPairs, images, Modifier.width(cellW).height(layout.cardHeight.dp), onTap) }
                    }
                }
            }
        }
        if (game.hasMismatch) GameButton(stringResource(R.string.memory_flip), Modifier.testTag("memory.flip"), onClick = onFlip)
        if (game.isFinished) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.CheckCircle, null, tint = GameColors.success)
                Text(stringResource(R.string.memory_done), Modifier.testTag("memory.finished"), color = GameColors.success, style = AppTypography.title)
            }
            if (newBest) RecordBadge(stringResource(R.string.game_new_best))
            GameButton(stringResource(R.string.blocks_replay), prominent = true, onClick = onReplay)
        }
        GameButton(stringResource(R.string.game_setup_edit_photos), Modifier.testTag("memory.change").padding(bottom = 12.dp), onClick = onChange)
    }
}

@Composable
// Zustand als einfache Werte übergeben: die Spielinstanz bleibt dieselbe, sonst würde Compose die Karte überspringen.
private fun MemoryCard(card: PhotoMemoryGame.Card, revealed: Boolean, matched: Boolean, images: Map<String, ImageBitmap>, modifier: Modifier, onTap: (Int) -> Unit) {
    val reduce = LocalReduceMotion.current
    val turn by animateFloatAsState(if (revealed) 180f else 0f, label = "flip")
    val shape = RoundedCornerShape(12.dp)
    val hidden = stringResource(R.string.memory_hidden)
    val motif = MOTIFS.indexOf(card.pairID)
    val photoIndex = images.keys.indexOf(card.pairID)
    val value = when {
        !revealed -> hidden
        motif >= 0 -> stringResource(MOTIF_NAMES[motif])
        else -> stringResource(R.string.memory_photo, photoIndex + 1)
    }.let { if (matched) stringResource(R.string.memory_matched) + ", " + it else it }
    val label = stringResource(R.string.memory_card, card.id + 1)
    val shown = if (reduce) revealed else turn > 90f
    Box(
        modifier.graphicsLayer { if (!reduce) rotationY = turn; cameraDistance = 12f * density }
            .clip(shape).background(if (shown) GameColors.surface else GameColors.accent, shape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .clickable(enabled = !revealed) { onTap(card.id) }
            .semantics { contentDescription = label; stateDescription = value }
            .testTag("memory.card.${card.id}"),
        contentAlignment = Alignment.Center,
    ) {
        if (shown) {
            Box(Modifier.fillMaxSize().graphicsLayer { if (!reduce) rotationY = 180f }, contentAlignment = Alignment.Center) {
                val img = images[card.pairID]
                if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else if (motif >= 0) Icon(MOTIF_ICONS[motif], null, Modifier.size(36.dp), tint = GameColors.accent)
                if (matched) Icon(Icons.Rounded.CheckCircle, null, Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp), tint = GameColors.success)
            }
        } else {
            Icon(Icons.Rounded.Collections, null, Modifier.size(28.dp), tint = GameColors.onAccent.copy(alpha = 0.85f))
        }
    }
}

