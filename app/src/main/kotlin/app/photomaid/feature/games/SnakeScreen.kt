package app.photomaid.feature.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.games.PhotoSnakeCharacter
import app.photomaid.core.games.PhotoSnakeFood
import app.photomaid.core.games.PhotoSnakeGame
import app.photomaid.core.games.PhotoSnakeLayout
import app.photomaid.core.games.PhotoSnakeMotion
import app.photomaid.core.games.PhotoSnakePreferences
import app.photomaid.core.games.PhotoSnakeSpeed
import app.photomaid.core.games.PhotoSnakeSwipe
import app.photomaid.core.games.PhotoSnakeViewport
import app.photomaid.design.AppTypography
import app.photomaid.navigation.LocalApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min

/** Laufende Runde: Regeln (Kern), Bewegungsverlauf fürs Zeichnen und Fotostreifen. */
private class SnakeRound(val game: PhotoSnakeGame, val speed: PhotoSnakeSpeed, val photos: List<ImageBitmap>) {
    val motion = PhotoSnakeMotion(game)
    var started = false
    var running = false
}

/**
 * Foto-Snake (Port von iOS `PhotoSnakeView`): Schlange aus runden Fotos, Steuerung AUSSCHLIESSLICH per Wischgeste
 * (iOS-Nutzerauftrag: keine Richtungstasten, auch nicht optional). Starten geht auch per Knopf. Fingerrichtung ist
 * physisch (in RTL nie gespiegelt). Spielen ist nie eine Entscheidung über ein Medium.
 */
@Composable
fun SnakeScreen(onClose: () -> Unit, onOtherGames: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val prefs by app.games.snakePreferences.collectAsState()
    val records by app.games.records.collectAsState()
    var source by remember { mutableStateOf(GamePhotoSource.LIBRARY) }
    var round by remember { mutableStateOf<SnakeRound?>(null) }
    var loading by remember { mutableStateOf<Job?>(null) }
    var missing by remember { mutableStateOf(false) }
    var frame by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(1f) }
    var newBest by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(PhotoSnakeViewport(12, 16)) }
    var pausedByUser by remember { mutableStateOf(false) }

    fun setPrefs(p: PhotoSnakePreferences) { scope.launch { app.games.saveSnakePreferences(p) } }
    fun newRound(photos: List<ImageBitmap>) {
        val g = PhotoSnakeGame.create(viewport.columns, viewport.rows, prefs.mode) ?: return
        round = SnakeRound(g, prefs.speed, photos); newBest = false; progress = 1f; pausedByUser = false; frame++
    }
    fun start(withPhotos: Boolean) {
        missing = false
        if (!withPhotos) { newRound(emptyList()); return }
        loading = scope.launch {
            val loaded = GamePhotos.load(context, app.photos, source, 12)
            loading = null
            if (loaded.isEmpty()) { missing = true; return@launch }
            newRound(if (prefs.shufflePhotos) loaded.map { it.second }.shuffled() else loaded.map { it.second })
        }
    }
    fun swipe(d: PhotoSnakeGame.Direction) {
        val r = round ?: return
        if (r.game.status != PhotoSnakeGame.Status.PLAYING) { newRound(r.photos); return }
        if (!r.started) { r.game.faceBeforeFirstStep(d); round = SnakeRound(r.game, r.speed, r.photos).also { it.started = true; it.running = true } }
        else {
            if (!r.running) r.running = true
            if (r.game.turn(d) && prefs.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        pausedByUser = false; frame++
    }

    // Spieltakt: feste Schrittzeit je Tempo, dazwischen weich interpoliert. Nach einer Pause wird nichts nachgeholt.
    LaunchedEffect(round, pausedByUser) {
        val r = round ?: return@LaunchedEffect
        var last = -1L; var acc = 0.0
        while (true) {
            val now = withFrameNanos { it }
            if (last < 0) { last = now; continue }
            val dt = min(0.25, (now - last) / 1e9); last = now
            if (!r.running || pausedByUser || r.game.status != PhotoSnakeGame.Status.PLAYING) continue
            acc += dt
            if (acc >= r.speed.interval) {
                acc -= r.speed.interval
                val before = r.game.score
                val event = r.game.step()
                r.motion.advance(r.game, event)
                if (r.game.score != before) {
                    if (prefs.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { if (app.games.update { it.recordSnake(r.game, r.speed, false) }) newBest = true }
                }
                if (r.game.status != PhotoSnakeGame.Status.PLAYING) r.running = false
                frame++
            }
            progress = (acc / r.speed.interval).toFloat().coerceIn(0f, 1f)
        }
    }

    val r = round
    GameScaffold(stringResource(R.string.snake_title), "snake", onClose = { r?.running = false; onClose() },
        onOtherGames = { r?.running = false; onOtherGames() },
        trailing = if (r != null && r.running && r.game.status == PhotoSnakeGame.Status.PLAYING) {
            { GameTextButton(stringResource(R.string.snake_pause), Modifier.testTag("snake.pause")) { pausedByUser = true; frame++ } }
        } else null) {
        when {
            loading != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(color = GameColors.accent)
                Text(stringResource(R.string.memory_loading), color = GameColors.secondary, style = AppTypography.subheadline)
                GameTextButton(stringResource(R.string.cancel)) { loading?.cancel(); loading = null }
            }
            r == null -> SnakeSetup(prefs, source, missing, records.snakeOverallBest, onSource = { source = it; missing = false },
                onPrefs = ::setPrefs, onStart = { start(true) }, onSymbols = { start(false) },
                canRead = app.photos.access.value.canRead)
            else -> {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val vp = PhotoSnakeViewport.of(maxWidth.value.toDouble(), maxHeight.value.toDouble())
                    // Größe ändert nie eine laufende Runde (iOS `updateViewport`).
                    LaunchedEffect(vp) { if (vp != viewport) { viewport = vp; if (!r.started) newRound(r.photos) } }
                    SnakeBoard(r, prefs, frame, progress, Modifier.fillMaxSize().pointerInput(r) {
                        var total = Offset.Zero
                        detectDragGestures(onDragStart = { total = Offset.Zero }, onDragEnd = {
                            PhotoSnakeSwipe.direction(total.x.toDouble() / density, total.y.toDouble() / density)?.let(::swipe)
                        }) { change, drag -> change.consume(); total += drag }
                    })
                }
                Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RecordBadge(stringResource(R.string.snake_eaten, r.game.score))
                    records.snakeOverallBest?.let { RecordBadge(stringResource(R.string.game_best_eaten, it)) }
                }
                val over = r.game.status != PhotoSnakeGame.Status.PLAYING
                if (!r.started || pausedByUser || over) GamePanel {
                    Text(stringResource(when {
                        r.game.status == PhotoSnakeGame.Status.WON -> R.string.snake_won
                        over -> R.string.snake_over
                        pausedByUser -> R.string.snake_paused
                        else -> R.string.snake_swipe_start
                    }), style = AppTypography.title, color = GameColors.text)
                    if (over) Text(stringResource(R.string.snake_eaten, r.game.score), color = GameColors.text)
                    if (newBest && over) RecordBadge(stringResource(R.string.game_new_best))
                    Text(stringResource(if (over) R.string.snake_swipe_restart else if (pausedByUser) R.string.snake_swipe_resume else R.string.snake_swipe_steer),
                        color = GameColors.secondary, style = AppTypography.subheadline)
                    // Starten/Fortsetzen ausdrücklich auch per Knopf (Richtung nur per Wischgeste).
                    GameButton(stringResource(if (over) R.string.blocks_replay else if (pausedByUser) R.string.snake_resume else R.string.snake_start),
                        Modifier.testTag("snake.start"), prominent = true) {
                        when {
                            over -> newRound(r.photos)
                            pausedByUser -> { pausedByUser = false; r.running = r.started; frame++ }
                            else -> swipe(r.game.direction)
                        }
                    }
                    GameButton(stringResource(R.string.game_setup_edit_photos)) { round = null }
                }
            }
        }
    }
}

@Composable
private fun SnakeSetup(prefs: PhotoSnakePreferences, source: GamePhotoSource, missing: Boolean, best: Int?, canRead: Boolean,
                       onSource: (GamePhotoSource) -> Unit, onPrefs: (PhotoSnakePreferences) -> Unit, onStart: () -> Unit, onSymbols: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GameIntro(stringResource(R.string.game_setup_snake_intro), best?.let { stringResource(R.string.game_best_eaten, it) }) {
            Image(painterResource(headRes(prefs.character, "idle")), null, Modifier.size(120.dp))
        }
        GameSetupCard(stringResource(R.string.game_setup_photos)) {
            GameChoices(listOf(GamePhotoSource.LIBRARY to stringResource(R.string.memory_source_library),
                GamePhotoSource.FAVORITES to stringResource(R.string.memory_source_favorites),
                GamePhotoSource.NEWEST to stringResource(R.string.blocks_newest)), source, onSource)
        }
        GameSetupCard(stringResource(R.string.snake_mode)) {
            GameChoices(listOf(PhotoSnakeGame.Mode.RELAXED to stringResource(R.string.snake_relaxed), PhotoSnakeGame.Mode.CLASSIC to stringResource(R.string.snake_classic)),
                prefs.mode) { onPrefs(prefs.copy(mode = it)) }
        }
        GameSetupCard(stringResource(R.string.snake_speed)) {
            GameChoices(listOf(PhotoSnakeSpeed.GENTLE to stringResource(R.string.snake_gentle), PhotoSnakeSpeed.NORMAL to stringResource(R.string.snake_normal),
                PhotoSnakeSpeed.FAST to stringResource(R.string.snake_fast)), prefs.speed) { onPrefs(prefs.copy(speed = it)) }
        }
        GameSetupCard(stringResource(R.string.snake_character)) {
            GameChoices(PhotoSnakeCharacter.entries.map { it to stringResource(characterName(it)) }, prefs.character) { onPrefs(prefs.copy(character = it)) }
        }
        GameSetupCard(stringResource(R.string.snake_food)) {
            GameChoices(PhotoSnakeFood.entries.map { it to stringResource(foodName(it)) }, prefs.food) { onPrefs(prefs.copy(food = it)) }
        }
        GameSetupCard(stringResource(R.string.game_setup_appearance)) {
            GameChoices(listOf(false to stringResource(R.string.game_standard), true to stringResource(R.string.snake_night)), prefs.night) { onPrefs(prefs.copy(night = it)) }
            GameChoices(listOf(true to stringResource(R.string.snake_haptics), false to "–"), prefs.haptics) { onPrefs(prefs.copy(haptics = it)) }
            GameChoices(listOf(true to stringResource(R.string.snake_shuffle), false to "–"), prefs.shufflePhotos) { onPrefs(prefs.copy(shufflePhotos = it)) }
        }
        if (missing) Text(stringResource(R.string.game_not_enough_photos), color = GameColors.accent, style = AppTypography.footnote)
        Text(stringResource(R.string.game_setup_private), color = GameColors.secondary, style = AppTypography.footnote)
        GameButton(stringResource(R.string.snake_start_game), Modifier.testTag("snake.setup.start"), prominent = true, enabled = canRead, onClick = onStart)
        GameButton(stringResource(R.string.game_setup_without_photos), Modifier.testTag("snake.symbols"), onClick = onSymbols)
    }
}

private fun characterName(c: PhotoSnakeCharacter) = when (c) {
    PhotoSnakeCharacter.SNAKE -> R.string.snake_character_snake; PhotoSnakeCharacter.DRAGON -> R.string.snake_character_dragon
    PhotoSnakeCharacter.DINO -> R.string.snake_character_dino; PhotoSnakeCharacter.MONSTER -> R.string.snake_character_monster
}

private fun foodName(f: PhotoSnakeFood) = when (f) {
    PhotoSnakeFood.MIXED -> R.string.snake_food_mixed; PhotoSnakeFood.APPLE -> R.string.snake_food_apple; PhotoSnakeFood.STAR -> R.string.snake_food_star
    PhotoSnakeFood.HEART -> R.string.snake_food_heart; PhotoSnakeFood.GEM -> R.string.snake_food_gem; PhotoSnakeFood.GRAPES -> R.string.snake_food_grapes
    PhotoSnakeFood.DONUT -> R.string.snake_food_donut; PhotoSnakeFood.CHEESE -> R.string.snake_food_cheese; PhotoSnakeFood.CAKE -> R.string.snake_food_cake
    PhotoSnakeFood.PIZZA -> R.string.snake_food_pizza
}

private fun foodRes(f: PhotoSnakeFood) = when (f) {
    PhotoSnakeFood.MIXED, PhotoSnakeFood.APPLE -> R.drawable.snake_food_apple; PhotoSnakeFood.STAR -> R.drawable.snake_food_star
    PhotoSnakeFood.HEART -> R.drawable.snake_food_heart; PhotoSnakeFood.GEM -> R.drawable.snake_food_gem; PhotoSnakeFood.GRAPES -> R.drawable.snake_food_grapes
    PhotoSnakeFood.DONUT -> R.drawable.snake_food_donut; PhotoSnakeFood.CHEESE -> R.drawable.snake_food_cheese; PhotoSnakeFood.CAKE -> R.drawable.snake_food_cake
    PhotoSnakeFood.PIZZA -> R.drawable.snake_food_pizza
}

private fun headRes(c: PhotoSnakeCharacter, pose: String): Int = when (c) {
    PhotoSnakeCharacter.SNAKE -> when (pose) { "eat" -> R.drawable.snake_head_snake_eat; "dead" -> R.drawable.snake_head_snake_dead; else -> R.drawable.snake_head_snake_idle }
    PhotoSnakeCharacter.DRAGON -> when (pose) { "eat" -> R.drawable.snake_head_dragon_eat; "dead" -> R.drawable.snake_head_dragon_dead; else -> R.drawable.snake_head_dragon_idle }
    PhotoSnakeCharacter.DINO -> when (pose) { "eat" -> R.drawable.snake_head_dino_eat; "dead" -> R.drawable.snake_head_dino_dead; else -> R.drawable.snake_head_dino_idle }
    PhotoSnakeCharacter.MONSTER -> when (pose) { "eat" -> R.drawable.snake_head_monster_eat; "dead" -> R.drawable.snake_head_monster_dead; else -> R.drawable.snake_head_monster_idle }
}

/** Spielfeld (Vogelperspektive): Wiese, Verbindungsröhre, runde Fotos mit dünnem Rahmen und leichter Überlappung, Kopf, Futter. */
@Composable
private fun SnakeBoard(r: SnakeRound, prefs: PhotoSnakePreferences, frame: Int, progress: Float, modifier: Modifier) {
    val bg = ImageBitmap.imageResource(if (prefs.night) R.drawable.snake_bg_night else R.drawable.snake_bg_day)
    val dead = r.game.status == PhotoSnakeGame.Status.LOST
    val head = ImageBitmap.imageResource(headRes(prefs.character, if (dead) "dead" else "idle"))
    val food = ImageBitmap.imageResource(foodRes(prefs.food.kind(r.game.score)))
    val description = stringResource(R.string.snake_board)
    Canvas(modifier.semantics { contentDescription = description }.testTag("snake.board")) {
        @Suppress("UNUSED_VARIABLE") val tick = frame
        val l = PhotoSnakeLayout.of(size.width.toDouble(), size.height.toDouble(), r.game.width, r.game.height)
        val pitch = l.pitch.toFloat(); val side = l.photoSide.toFloat()
        val left = (size.width - pitch * r.game.width) / 2f; val top = (size.height - pitch * r.game.height) / 2f
        fun px(p: PhotoSnakeMotion.Point) = Offset(left + p.x.toFloat() * pitch, top + p.y.toFloat() * pitch)
        // Wiese als Kachel (nie verzerrt: quadratische Kachel, quadratisches Ziel).
        val tileSide = (pitch * 4).toInt().coerceAtLeast(32)
        var y = 0; while (y < size.height) { var x = 0; while (x < size.width) {
            drawImage(bg, dstOffset = IntOffset(x, y), dstSize = IntSize(tileSide, tileSide)); x += tileSide }; y += tileSide }
        val boardRect = Size(pitch * r.game.width, pitch * r.game.height)
        if (r.game.mode == PhotoSnakeGame.Mode.CLASSIC) drawRect(Color.White.copy(alpha = 0.35f), Offset(left, top), boardRect, style = Stroke(2.dp.toPx()))
        // Futter.
        r.game.food?.let { f ->
            val c = Offset(left + (f.x + 0.5f) * pitch, top + (f.y + 0.5f) * pitch); val s = side * 0.9f
            drawImage(food, dstOffset = IntOffset((c.x - s / 2).toInt(), (c.y - s / 2).toInt()), dstSize = IntSize(s.toInt(), s.toInt()))
        }
        val p = progress.toDouble()
        val samples = r.motion.points(p)
        // Röhre entlang des erlaubten Wegs (nie diagonal durch belegte Felder).
        r.motion.lines(p, samples).forEach { line ->
            drawLine(Color(0xFF4AAA52), px(line.a), px(line.b), strokeWidth = side * 0.42f, cap = StrokeCap.Round)
        }
        val wraps = r.game.mode == PhotoSnakeGame.Mode.RELAXED
        // Vom Schwanz zum Kopf zeichnen: das Stück näher am Kopf liegt oben (iOS `segmentDepth`).
        for (index in samples.indices.reversed()) {
            val point = if (wraps) r.motion.wrapped(samples[index]) else samples[index]
            val placements = PhotoSnakeLayout.placements(PhotoSnakeMotion.Point(point.x * pitch, point.y * pitch),
                (pitch * r.game.width).toDouble(), (pitch * r.game.height).toDouble(), l.gutter, (if (index == 0) l.headSide else l.photoSide) / 2, wraps, pitch.toDouble())
            for (pl in placements) {
                val c = Offset(left + pl.centre.x.toFloat(), top + pl.centre.y.toFloat())
                if (index == 0) drawHead(head, c, l.headSide.toFloat(), r.game.direction, pl.alpha.toFloat())
                else drawPhoto(r.photos.getOrNull((index - 1) % r.photos.size.coerceAtLeast(1)), c, side, pl.alpha.toFloat())
            }
        }
    }
}

private fun DrawScope.drawPhoto(image: ImageBitmap?, c: Offset, side: Float, alpha: Float) {
    val radius = side / 2
    val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, radius)) }
    if (image == null) {
        drawCircle(Color(0xFFF2B24C).copy(alpha = alpha), radius, c)
    } else clipPath(circle) {
        // Mittiger Beschnitt (aspect fill), nie gestreckt.
        val scale = maxOf(side / image.width, side / image.height)
        val w = image.width * scale; val h = image.height * scale
        drawImage(image, dstOffset = IntOffset((c.x - w / 2).toInt(), (c.y - h / 2).toInt()), dstSize = IntSize(w.toInt(), h.toInt()), alpha = alpha)
    }
    drawCircle(Color.White.copy(alpha = 0.9f * alpha), radius, c, style = Stroke(side * PhotoSnakeLayout.OVERLAP.toFloat() * 0.35f))
}

private fun DrawScope.drawHead(head: ImageBitmap, c: Offset, side: Float, d: PhotoSnakeGame.Direction, alpha: Float) {
    val angle = when (d) { PhotoSnakeGame.Direction.UP -> 0f; PhotoSnakeGame.Direction.RIGHT -> 90f; PhotoSnakeGame.Direction.DOWN -> 180f; PhotoSnakeGame.Direction.LEFT -> 270f }
    withTransform({ rotate(angle, c) }) {
        drawImage(head, dstOffset = IntOffset((c.x - side / 2).toInt(), (c.y - side / 2).toInt()), dstSize = IntSize(side.toInt(), side.toInt()), alpha = alpha)
    }
}

