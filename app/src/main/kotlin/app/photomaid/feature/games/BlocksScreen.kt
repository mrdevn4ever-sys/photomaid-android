package app.photomaid.feature.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import app.photomaid.R
import app.photomaid.core.games.PhotoBlocksArchive
import app.photomaid.core.games.PhotoBlocksCell
import app.photomaid.core.games.PhotoBlocksGame
import app.photomaid.core.games.PhotoBlocksInput
import app.photomaid.core.games.PhotoBlocksPhotos
import app.photomaid.core.games.PhotoBlocksShape
import app.photomaid.design.AppTypography
import app.photomaid.navigation.LocalApp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** Steinfarben wie iOS `PhotoBlocksPalette`. */
private fun shapeColor(s: PhotoBlocksShape): Color = when (s) {
    PhotoBlocksShape.PEBBLE, PhotoBlocksShape.SQUARE -> Color(0.35f, 0.76f, 0.76f)
    PhotoBlocksShape.SHOOT, PhotoBlocksShape.CORNER_RIGHT -> Color(0.75f, 0.64f, 0.91f)
    PhotoBlocksShape.ARCH, PhotoBlocksShape.CROWN -> Color(0.94f, 0.65f, 0.48f)
    PhotoBlocksShape.PETAL, PhotoBlocksShape.STEP_LEFT -> Color(0.68f, 0.81f, 0.48f)
    PhotoBlocksShape.BOUGH, PhotoBlocksShape.CORNER_LEFT -> Color(0.43f, 0.66f, 0.91f)
    PhotoBlocksShape.FERN, PhotoBlocksShape.LINE -> Color(0.91f, 0.53f, 0.66f)
    PhotoBlocksShape.STEP_RIGHT -> Color(0.77f, 0.71f, 0.55f)
}

/**
 * Foto Blocks (Port von iOS `PhotoBlocksView`): eigene Fallstein-Logik; jede volle Reihe legt ein Zehntel eines Fotos frei.
 * Spielstand ohne Bilder/Kennungen lokal gespeichert. Steuerung: seitlich wischen, tippen = drehen, runterziehen = schneller,
 * schnell runterwischen = absetzen. Links/rechts bleiben in RTL physisch.
 */
@Composable
fun BlocksScreen(onClose: () -> Unit, onOtherGames: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val archive by app.games.blocks.collectAsState()
    var source by remember { mutableStateOf(archive.photoSource) }
    var game by remember { mutableStateOf(archive.game) }
    var photos by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var frame by remember { mutableIntStateOf(0) }
    var newBest by remember { mutableStateOf(false) }

    fun persist(g: PhotoBlocksGame?) {
        val best = max(archive.best, g?.score ?: 0)
        scope.launch { app.games.saveBlocks(PhotoBlocksArchive(g, best, g?.discoveredPhotos ?: 0, archive.photoSeed, source).sanitized()) }
    }
    fun loadPhotos(after: () -> Unit) {
        loading = true
        scope.launch {
            val gameSource = when (source) {
                PhotoBlocksPhotos.Source.FAVORITES -> GamePhotoSource.FAVORITES
                PhotoBlocksPhotos.Source.NEWEST -> GamePhotoSource.NEWEST
                else -> GamePhotoSource.LIBRARY
            }
            photos = GamePhotos.load(context, app.photos, gameSource, 6, side = 768).map { it.second }
            loading = false; after()
        }
    }
    fun newGame() {
        newBest = false
        loadPhotos { game = PhotoBlocksGame.create(seed = kotlin.random.Random.nextLong(1, Long.MAX_VALUE).toULong()).also { it.start() }; frame++ }
    }

    // Schwerkraft mit monotoner Zeit; Pausen werden nie nachgeholt.
    LaunchedEffect(game) {
        val g = game ?: return@LaunchedEffect
        var last = -1L
        var lastPhase = g.phase
        var lastScore = g.score
        while (true) {
            val now = withFrameNanos { it }
            if (last >= 0) g.advance((now - last) / 1e9)
            last = now
            if (g.phase != lastPhase || g.score != lastScore) {
                if (g.phase == PhotoBlocksGame.Phase.GAME_OVER) { if (g.score > archive.best) newBest = true; persist(g) }
                lastPhase = g.phase; lastScore = g.score
            }
            frame++
        }
    }
    // Verlassen speichert den Stand (nur Zahlen).
    DisposableEffect(Unit) { onDispose { game?.let { it.pause(); persist(it) } } }

    val g = game
    GameScaffold(stringResource(R.string.blocks_title), "blocks", onClose = onClose, onOtherGames = onOtherGames,
        trailing = if (g != null && g.acceptsInput) { { GameTextButton(stringResource(R.string.blocks_pause), Modifier.testTag("blocks.pause")) { g.pause(); persist(g); frame++ } } } else null) {
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = GameColors.accent)
            g == null || g.phase == PhotoBlocksGame.Phase.READY -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GameIntro(stringResource(R.string.blocks_subtitle), archive.best.takeIf { it > 0 }?.let { stringResource(R.string.blocks_best, it) }) {
                    Icon(Icons.Rounded.GridView, null, Modifier.size(96.dp), tint = GameColors.accent)
                }
                GameSetupCard(stringResource(R.string.blocks_photo_source)) {
                    GameChoices(listOf(PhotoBlocksPhotos.Source.RANDOM to stringResource(R.string.blocks_random),
                        PhotoBlocksPhotos.Source.NEWEST to stringResource(R.string.blocks_newest),
                        PhotoBlocksPhotos.Source.FAVORITES to stringResource(R.string.blocks_favorites)), source) { source = it }
                }
                GameSetupCard(stringResource(R.string.blocks_how)) {
                    listOf(R.string.blocks_hint_move, R.string.blocks_hint_rotate, R.string.blocks_hint_drop).forEach {
                        Text(stringResource(it), color = GameColors.text, style = AppTypography.subheadline)
                    }
                }
                Text(stringResource(R.string.game_setup_private), color = GameColors.secondary, style = AppTypography.footnote)
                GameButton(stringResource(R.string.blocks_start), Modifier.testTag("blocks.start"), prominent = true) { newGame() }
            }
            else -> BlocksPlay(g, photos.getOrNull((g.level - 1) % max(1, photos.size)), frame, newBest, archive.best,
                onActions = { actions ->
                    actions.forEach { a ->
                        when (a) {
                            PhotoBlocksInput.Action.Left -> g.move(-1)
                            PhotoBlocksInput.Action.Right -> g.move(1)
                            PhotoBlocksInput.Action.Rotate -> g.rotate()
                            PhotoBlocksInput.Action.HardDrop -> g.hardDrop()
                            is PhotoBlocksInput.Action.SoftDrop -> g.setSoftDrop(a.enabled)
                        }
                    }
                    frame++
                },
                onResume = { if (photos.isEmpty()) loadPhotos { g.resume(); frame++ } else { g.resume(); frame++ } },
                onRestart = { newGame() },
                onChangePhotos = { g.pause(); persist(g); game = null })
        }
    }
}

@Composable
private fun BlocksPlay(g: PhotoBlocksGame, photo: ImageBitmap?, frame: Int, newBest: Boolean, best: Long,
                       onActions: (List<PhotoBlocksInput.Action>) -> Unit, onResume: () -> Unit, onRestart: () -> Unit, onChangePhotos: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.blocks_score, g.score), color = GameColors.text, style = AppTypography.headline)
                Text(stringResource(R.string.blocks_level, g.level), color = GameColors.text, style = AppTypography.subheadline)
                Text(stringResource(R.string.blocks_rows, g.totalRows), color = GameColors.text, style = AppTypography.subheadline)
                NextPiece(g.next)
            }
            // Foto-Fortschritt (zehn feste Streifen).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(10) { i ->
                    Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (i < g.revealedStrips) GameColors.accent else GameColors.control))
                }
            }
            // Physische Richtungen: das Spielfeld wird in RTL nie gespiegelt.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                BlocksBoard(g, photo, frame, Modifier.weight(1f).aspectRatio(g.width.toFloat() / g.height, matchHeightConstraintsFirst = true), onActions)
            }
            Text(stringResource(R.string.blocks_found, g.discoveredPhotos), color = GameColors.secondary, style = AppTypography.footnote,
                modifier = Modifier.padding(bottom = 8.dp))
        }
        when {
            g.phase == PhotoBlocksGame.Phase.DISCOVERED -> Text(stringResource(R.string.blocks_discovered), Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
                .clip(RoundedCornerShape(50)).background(GameColors.accent).padding(horizontal = 16.dp, vertical = 8.dp), color = GameColors.onAccent, style = AppTypography.headline)
            g.phase == PhotoBlocksGame.Phase.GAME_OVER -> GamePanel {
                Text(stringResource(R.string.blocks_over), style = AppTypography.title, color = GameColors.text)
                Text(stringResource(R.string.blocks_score, g.score), color = GameColors.text)
                if (newBest) RecordBadge(stringResource(R.string.blocks_new_best)) else if (best > 0) RecordBadge(stringResource(R.string.blocks_best, best))
                GameButton(stringResource(R.string.blocks_replay), Modifier.testTag("blocks.replay"), prominent = true, onClick = onRestart)
                GameButton(stringResource(R.string.blocks_change_photos), onClick = onChangePhotos)
            }
            g.isPaused -> GamePanel {
                Text(stringResource(R.string.blocks_paused), style = AppTypography.title, color = GameColors.text)
                GameButton(stringResource(R.string.blocks_continue), Modifier.testTag("blocks.continue"), prominent = true, onClick = onResume)
                GameButton(stringResource(R.string.blocks_restart), onClick = onRestart)
                GameButton(stringResource(R.string.blocks_change_photos), onClick = onChangePhotos)
            }
        }
    }
}

@Composable
private fun NextPiece(shape: PhotoBlocksShape) {
    val label = stringResource(R.string.blocks_next)
    Canvas(Modifier.size(40.dp).semantics { contentDescription = label }) {
        val cells = shape.cells(); val w = cells.maxOf { it.x } + 1; val h = cells.maxOf { it.y } + 1
        val cell = min(size.width / 4, size.height / 4)
        val ox = (size.width - w * cell) / 2; val oy = (size.height - h * cell) / 2
        cells.forEach { drawRoundRect(shapeColor(shape), Offset(ox + it.x * cell + 1, oy + it.y * cell + 1), Size(cell - 2, cell - 2), CornerRadius(cell * 0.2f)) }
    }
}

@Composable
private fun BlocksBoard(g: PhotoBlocksGame, photo: ImageBitmap?, frame: Int, modifier: Modifier, onActions: (List<PhotoBlocksInput.Action>) -> Unit) {
    val gestures = stringResource(R.string.blocks_gestures)
    val position = g.active?.let { stringResource(R.string.blocks_position, it.origin.x + 1, it.origin.y + 1, it.rotation) } ?: ""
    val shape = RoundedCornerShape(4.dp)
    Canvas(
        modifier.clip(shape).border(1.dp, Color.White.copy(alpha = 0.2f), shape).background(GameColors.ground)
            .semantics { contentDescription = gestures; stateDescription = position }
            .testTag("blocks.board")
            .pointerInput(g) {
                val input = PhotoBlocksInput()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = down.position; val t0 = down.uptimeMillis
                    val cellDp = size.width / g.width / density
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val dx = (change.position.x - start.x) / density; val dy = (change.position.y - start.y) / density
                        val time = (change.uptimeMillis - t0) / 1000.0
                        if (!change.pressed) {
                            onActions(input.ended(dx.toDouble(), dy.toDouble(), time, time, cellDp.toDouble()))
                            break
                        }
                        if (change.positionChange() != Offset.Zero) {
                            change.consume()
                            if (g.acceptsInput) onActions(input.changed(dx.toDouble(), dy.toDouble(), time, cellDp.toDouble()))
                        }
                    }
                }
            },
    ) {
        @Suppress("UNUSED_VARIABLE") val tick = frame
        val cell = size.width / g.width
        // Foto im Fotoraum aufgedeckt (oben beginnend), unabhängig vom Raster; beschnitten, nie verzerrt.
        val reveal = PhotoBlocksPhotos.revealedHeight(g.revealedStrips).toFloat()
        val discovered = g.phase == PhotoBlocksGame.Phase.DISCOVERED
        if (photo != null) clipRect(0f, 0f, size.width, if (discovered) size.height else size.height * reveal) {
            val scale = if (discovered) min(size.width / photo.width, size.height / photo.height) else max(size.width / photo.width, size.height / photo.height)
            val w = photo.width * scale; val h = photo.height * scale
            drawImage(photo, dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()), dstSize = IntSize(w.toInt(), h.toInt()))
            if (!discovered) drawRect(Color.Black.copy(alpha = 0.55f))
        } else if (reveal > 0f) drawRect(Brush.linearGradient(listOf(Color(0.12f, 0.29f, 0.31f), GameColors.ground)), size = Size(size.width, size.height * reveal))
        if (discovered) return@Canvas
        val visible = !g.isPaused && g.phase != PhotoBlocksGame.Phase.GAME_OVER
        fun cellRect(c: PhotoBlocksCell) = Offset(c.x * cell + 1.5f, c.y * cell + 1.5f) to Size(cell - 3, cell - 3)
        if (visible) {
            for (x in 1 until g.width) drawLine(Color.White.copy(alpha = 0.07f), Offset(x * cell, 0f), Offset(x * cell, size.height), 1f)
            for (y in 1 until g.height) drawLine(Color.White.copy(alpha = 0.07f), Offset(0f, y * cell), Offset(size.width, y * cell), 1f)
            g.ghost?.let { ghost -> ghost.cells.forEach { c -> val (o, s) = cellRect(c)
                drawRoundRect(shapeColor(ghost.shape).copy(alpha = 0.10f), o, s, CornerRadius(max(3f, cell * 0.19f)))
                drawRoundRect(Color.White.copy(alpha = 0.4f), o, s, CornerRadius(max(3f, cell * 0.19f)), style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))) } }
        }
        val board = g.board
        for (y in board.indices) for (x in board[y].indices) {
            val s = board[y][x] ?: continue
            val (o, sz) = cellRect(PhotoBlocksCell(x, y))
            val alpha = if (y in g.clearedRows) 1f - g.clearFraction.toFloat() else 1f
            drawRoundRect(shapeColor(s).copy(alpha = alpha), o, sz, CornerRadius(max(3f, cell * 0.19f)))
            drawRoundRect(Color.White.copy(alpha = 0.75f * alpha), o, sz, CornerRadius(max(3f, cell * 0.19f)), style = Stroke(1f))
        }
        g.active?.let { a -> a.cells.forEach { c -> val (o, sz) = cellRect(c)
            drawRoundRect(shapeColor(a.shape), o, sz, CornerRadius(max(3f, cell * 0.19f)))
            drawRoundRect(Color.White.copy(alpha = 0.75f), o, sz, CornerRadius(max(3f, cell * 0.19f)), style = Stroke(1f)) } }
        for (y in g.clearedRows) drawRect(GameColors.accent.copy(alpha = (1f - g.clearFraction.toFloat()) * 0.35f), Offset(0f, y * cell), Size(size.width, cell))
    }
}
