package app.photomaid.feature.review

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.photomaid.FeedRequest
import app.photomaid.R
import app.photomaid.core.feed.FeedIntent
import app.photomaid.core.feed.SessionSummary
import app.photomaid.core.model.MediaKind
import app.photomaid.design.AppBackground
import app.photomaid.design.AppIconSize
import app.photomaid.feature.collections.collectionTitle
import app.photomaid.design.AppMotion
import app.photomaid.design.AppRadii
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.DateText
import app.photomaid.design.EmptyState
import app.photomaid.design.GlassButton
import app.photomaid.design.GlassIconButton
import app.photomaid.design.GlassToolbar
import app.photomaid.design.LaterText
import app.photomaid.design.LocalReduceMotion
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.design.ReviewDock
import app.photomaid.design.SurfaceCard
import app.photomaid.design.glassCapsule
import app.photomaid.design.glassRect
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Vertikaler Entscheidungs-Feed (Screens 2 und 3).
 * HOCH = Behalten · LINKS = Papierkorb · RECHTS (bis 45° schräg) = Später mit Standardtermin, eigener Termin
 * über „Ändern“ in der Pille · RUNTER = Zurück. Keine Richtungs-Kapsel: das Medium selbst zeigt die Bewegung.
 */
@Composable
fun ReviewFeedScreen(request: FeedRequest, nav: Nav, onClose: () -> Unit, openBasket: () -> Unit) {
    val app = LocalApp.current
    val model: FeedViewModel = viewModel(key = request.id, factory = viewModelFactory { initializer { FeedViewModel(app, request) } })
    val ctx = LocalContext.current
    val reduceMotion = LocalReduceMotion.current
    SideEffect { model.reduceMotion = reduceMotion }
    val settings by app.settings.collectAsState()
    val read by app.read.collectAsState()
    val a11y = remember { ctx.getSystemService(AccessibilityManager::class.java) }
    val talkBack = remember { a11y?.isTouchExplorationEnabled == true }
    val switchAccess = remember {
        a11y?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.id.contains("switchaccess", ignoreCase = true) } == true
    }
    /** Mit TalkBack oder Schaltersteuerung bleiben die Aktions-Buttons sichtbar. */
    val needsButtons = talkBack || switchAccess
    var showHints by remember { mutableStateOf(false) }
    var hintToken by remember { mutableIntStateOf(0) }
    var showInfo by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val haptics = LocalHapticFeedback.current
    val animScope = rememberCoroutineScope()
    DisposableEffect(model, animScope) { model.animationScope = animScope; onDispose { model.animationScope = null } }

    fun flashHints() { hintToken++; showHints = true }
    fun hideHints() { if (showHints) { hintToken++; showHints = false } }

    LaunchedEffect(hintToken, showHints) {
        if (showHints) { delay(AppMotion.HINT_VISIBLE_MS); showHints = false }
    }
    LaunchedEffect(model.isLoaded) {
        if (model.isLoaded && model.session.items.isNotEmpty() && (settings.showGestureHintOnOpen || !settings.gestureHintSeen)) {
            flashHints()
            if (!settings.gestureHintSeen) app.updateSettings { it.copy(gestureHintSeen = true) }
        }
    }
    LaunchedEffect(talkBack, zoom) { model.setSuspended(talkBack || zoom > 1.01f) }
    LaunchedEffect(model.haptic) {
        val h = model.haptic?.second ?: return@LaunchedEffect
        haptics.performHapticFeedback(when (h) {
            FeedHaptic.KEEP -> HapticFeedbackType.Confirm
            FeedHaptic.CLEANUP -> HapticFeedbackType.ContextClick
            FeedHaptic.LATER -> HapticFeedbackType.SegmentTick
            FeedHaptic.READY -> HapticFeedbackType.GestureThresholdActivate
            FeedHaptic.DATE -> HapticFeedbackType.ToggleOn
            // Anschlag am Anfang ist kein Fehler: nur ein sehr leichter Tick.
            FeedHaptic.BUMP -> HapticFeedbackType.SegmentFrequentTick
            FeedHaptic.UNDO -> HapticFeedbackType.VirtualKey
        })
    }

    AppBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom = AppSpacing.xs), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            model.version // Session-Änderungen beobachten
            val items = model.session.items
            val (pos, total) = model.session.position
            var menu by remember { mutableStateOf(false) }
            GlassToolbar(
                title = (request.source as? FeedRequest.Source.Collection)?.let { collectionTitle(it.collection) }
                    ?: stringResource(request.titleRes),
                subtitle = if (items.isEmpty()) null else stringResource(R.string.feed_position, pos, total),
                leading = { GlassIconButton(Icons.Rounded.Close, stringResource(R.string.close), quiet = true, onClick = onClose) },
                trailing = {
                    GlassIconButton(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.undo), enabled = read.canUndo, quiet = true, onClick = model::undo)
                    Box {
                        GlassIconButton(Icons.Rounded.MoreHoriz, stringResource(R.string.more_actions), quiet = true) { menu = true }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.feed_view_basket)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                onClick = { menu = false; openBasket() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.info)) }, leadingIcon = { Icon(Icons.Rounded.Info, null) },
                                enabled = model.currentAsset != null, onClick = { menu = false; showInfo = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.feed_show_hints)) }, leadingIcon = { Icon(Icons.Rounded.PanTool, null) },
                                onClick = { menu = false; flashHints() })
                        }
                    }
                },
            )
            when {
                !model.isLoaded -> Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.s, Alignment.CenterVertically)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.feed_loading), style = AppTypography.footnote, color = PM.colors.textSecondary)
                }
                items.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Rounded.CheckCircle, stringResource(R.string.feed_empty_title), stringResource(R.string.feed_empty_message),
                        actionTitle = stringResource(R.string.close), action = onClose)
                }
                model.session.isFinished -> SessionSummaryView(
                    model.session.summary, read.basket.size, Modifier.weight(1f),
                    reviewBasket = openBasket, goBack = { model.perform(FeedIntent.PREVIOUS, reduceMotion) }, done = onClose,
                )
                else -> {
                    MediaStage(model, reduceMotion, showHints, gesturesEnabled = !talkBack && zoom <= 1.01f, zoom = zoom,
                        onZoom = { zoom = it.coerceIn(1f, 5f) }, onZoomReset = { zoom = 1f }, hideHints = ::hideHints,
                        openBasket = openBasket, modifier = Modifier.weight(1f))
                    MediaInfoLine(model.currentAsset, Modifier.padding(horizontal = AppSpacing.pageMargin))
                    Column(Modifier.padding(horizontal = AppSpacing.pageMargin), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        if (needsButtons) CompactActions { model.perform(it, reduceMotion) }
                        ReviewDock(read.basket.size, Modifier.testTag("feed.dock"), onClick = openBasket)
                    }
                }
            }
        }
    }

    model.errorMessage?.let { msg ->
        AlertDialog(onDismissRequest = { model.errorMessage = null },
            confirmButton = { TextButton({ model.errorMessage = null }) { Text(stringResource(R.string.ok)) } },
            title = { Text(stringResource(R.string.notice)) }, text = { Text(msg) })
    }
    if (showInfo) {
        val a = model.currentAsset
        val text = if (a == null) "" else buildList {
            a.creationDate?.let { add(DateText.longWithTime(it)) }
            add("${a.pixelWidth} × ${a.pixelHeight}")
            if (a.kind == MediaKind.VIDEO) add(DateText.duration(a.duration))
        }.joinToString("\n")
        AlertDialog(onDismissRequest = { showInfo = false }, confirmButton = { TextButton({ showInfo = false }) { Text(stringResource(R.string.ok)) } },
            title = { Text(stringResource(R.string.info)) }, text = { Text(text) })
    }
    model.pickerReceipt?.let { receipt ->
        LaterPickerSheet(initial = model.defaultLaterInterval, today = model.today) { day -> model.pickerFinished(receipt, day) }
    }
    // Schräg rechts oben: Auswahl ist noch keine Entscheidung; Abbrechen lässt alles unverändert.
    model.customLater?.let { req ->
        androidx.compose.runtime.key(req.id) {
            LaterPickerSheet(initial = model.defaultLaterInterval, today = model.today) { day -> model.customLaterFinished(req, day, reduceMotion) }
        }
    }
}

@Composable
private fun MediaStage(
    model: FeedViewModel,
    reduceMotion: Boolean,
    showHints: Boolean,
    gesturesEnabled: Boolean,
    zoom: Float,
    onZoom: (Float) -> Unit,
    onZoomReset: () -> Unit,
    hideHints: () -> Unit,
    openBasket: () -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val app = LocalApp.current
    val labels = listOf(
        stringResource(R.string.act_keep_next) to FeedIntent.KEEP, stringResource(R.string.act_trash) to FeedIntent.MARK_FOR_CLEANUP,
        stringResource(R.string.act_later) to FeedIntent.LATER, stringResource(R.string.act_later_custom) to FeedIntent.LATER_CUSTOM,
        stringResource(R.string.act_previous) to FeedIntent.PREVIOUS,
    )
    val undoLabel = stringResource(R.string.undo)
    val basketLabel = stringResource(R.string.feed_view_basket)
    val stageLabel = stringResource(R.string.feed_stage)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.xs)
            .testTag("feed.stage")
            // Zugängliche Aktionen zusätzlich zu Gesten. Keine Scroll-Aktion (verhindert unbemerkte Entscheidungen).
            .semantics {
                contentDescription = stageLabel
                customActions = labels.map { (l, i) -> CustomAccessibilityAction(l) { model.perform(i, true); true } } +
                    CustomAccessibilityAction(undoLabel) { model.undo(); true } +
                    CustomAccessibilityAction(basketLabel) { openBasket(); true }
            }
            .feedGestures(
                enabled = true, density = density,
                onDrag = { hideHints(); if (gesturesEnabled) model.dragChanged(it) },
                onEnd = { t, v -> if (gesturesEnabled) model.dragEnded(t, v, reduceMotion) },
                onCancel = model::dragCancelled,
                onZoom = { onZoom(it) },
                onDoubleTap = onZoomReset,
                // Tipp aufs Video = Pause/Play (wie TikTok); bei Fotos ohne Wirkung. Nie eine Entscheidung.
                onTap = { model.currentAsset?.takeIf { it.kind == MediaKind.VIDEO && zoom <= 1.01f }?.let { toggleVideo(app.player, it) } },
            ),
    ) {
        model.viewSizeDp = Size(maxWidth.value, maxHeight.value)
        val px = rememberPreviewPx(maxWidth.value, maxHeight.value)
        val item = model.current
        val asset = model.currentAsset
        // Vorschaufenster: vorheriges/nächste Medien vorab dekodieren (unsichtbar, gleiche Zielgröße).
        for (ref in model.windowItems()) if (ref != item) model.assets[ref.localIdentifier]?.let { PrefetchImage(it, px) { ok -> model.previewResult(ref, ok) } }
        if (item != null && asset != null) {
            val o = model.offset.value
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { with(density) { IntOffset(o.x.dp.roundToPx(), o.y.dp.roundToPx()) } }
                    .graphicsLayer { rotationZ = if (reduceMotion) 0f else o.x / 40f; scaleX = zoom; scaleY = zoom },
            ) {
                androidx.compose.runtime.key(item) {
                    val c = PM.colors
                    val intent = when (model.readyDirection?.let { FeedIntent.of(it) }) {
                        FeedIntent.KEEP -> c.keep
                        FeedIntent.MARK_FOR_CLEANUP -> c.cleanup
                        FeedIntent.LATER, FeedIntent.LATER_CUSTOM -> c.later
                        else -> null
                    }
                    MediaPage(asset, px, model.previews[item] ?: PreviewState.LOADING, onResult = { ok -> model.previewResult(item, ok) },
                        skip = model::skipWithoutDecision, zoomed = zoom > 1.01f, intentColor = intent,
                        retry = model.retries[item] ?: 0, onRetry = { model.retryPreview(item) })
                }
            }
        }
        DirectionPill(model, Modifier.align(Alignment.TopCenter).padding(top = AppSpacing.s))
        AnimatedVisibility(showHints, enter = fadeIn(), exit = fadeOut(tween(AppMotion.HINT_FADE_MS))) { GestureHintOverlay() }
        val pill = model.laterPill
        AnimatedVisibility(pill != null, Modifier.align(Alignment.BottomCenter).padding(bottom = AppSpacing.l),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            pill?.let { LaterPillView(it, model::editLater) }
        }
    }
}

/**
 * Dezente Pille während des Wischens (Nutzerwunsch): „↑ Behalten“, „← Papierkorb“, „→ Später“. Ruhig oben über dem
 * Medium, nur für Entscheidungen (nicht für „runter“). Kräftiger, sobald Loslassen entscheiden würde.
 */
@Composable
private fun DirectionPill(model: FeedViewModel, modifier: Modifier) {
    val c = PM.colors
    val d = model.lockedDirection
    val meaning = when (d?.let { FeedIntent.of(it) }) {
        FeedIntent.KEEP -> Triple(Icons.Rounded.ArrowUpward, R.string.dir_keep, c.keep)
        FeedIntent.MARK_FOR_CLEANUP -> Triple(Icons.AutoMirrored.Rounded.ArrowBack, R.string.dir_trash, c.cleanup)
        FeedIntent.LATER -> Triple(Icons.AutoMirrored.Rounded.ArrowForward, R.string.dir_later, c.later)
        else -> null
    }
    var last by remember { mutableStateOf(meaning) }
    if (meaning != null) last = meaning
    val ready = model.readyDirection != null
    AnimatedVisibility(meaning != null, modifier, enter = fadeIn(AppMotion.fast()), exit = fadeOut(AppMotion.fast())) {
        val (icon, label, tint) = last ?: return@AnimatedVisibility
        Row(Modifier.alpha(if (ready) 1f else 0.8f).glassCapsule(elevation = 0.dp).padding(horizontal = AppSpacing.s, vertical = AppSpacing.xxs + 2.dp)
            .testTag("feed.direction").clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs + 2.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(AppIconSize.small))
            Text(stringResource(label), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = tint)
        }
    }
}

/** Aktions-Buttons nur für TalkBack/Schaltersteuerung (sonst reine Gestenbedienung). */
@Composable
fun CompactActions(act: (FeedIntent) -> Unit) {
    val c = PM.colors
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        @Composable
        fun b(icon: ImageVector, label: String, tint: Color, i: FeedIntent) {
            Box(Modifier.weight(1f).heightIn(min = AppSpacing.minTouch).glassCapsule(3.dp).clickable { act(i) }
                .semantics { contentDescription = label }, contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint) }
        }
        b(Icons.Rounded.ArrowDownward, stringResource(R.string.act_previous), c.textSecondary, FeedIntent.PREVIOUS)
        b(Icons.Rounded.Delete, stringResource(R.string.act_trash), c.cleanup, FeedIntent.MARK_FOR_CLEANUP)
        b(Icons.Rounded.ArrowUpward, stringResource(R.string.act_keep_next), c.keep, FeedIntent.KEEP)
        b(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.act_later), c.later, FeedIntent.LATER)
        b(Icons.Rounded.CalendarMonth, stringResource(R.string.act_later_custom), c.later, FeedIntent.LATER_CUSTOM)
    }
}

/** Kleine Glas-Pille nach „Später“: „In 2 Tagen wieder · Ändern“. */
@Composable
fun LaterPillView(pill: LaterPill, edit: () -> Unit) {
    val c = PM.colors
    val ctx = LocalContext.current
    Row(Modifier.glassCapsule().padding(horizontal = AppSpacing.m).testTag("later.pill"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Icon(Icons.Rounded.History, null, tint = c.later)
        Text(LaterText.inInterval(ctx, pill.interval), style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
        Box(Modifier.heightIn(min = AppSpacing.minTouch).clickable(onClick = edit).testTag("later.edit"), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.change), style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
        }
    }
}

/** Gestenhilfe: erscheint beim Öffnen kurz, blendet von selbst aus, blockiert nichts. */
@Composable
fun GestureHintOverlay() {
    val c = PM.colors
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(AppRadii.sheet)).background(Color.Black.copy(alpha = 0.18f)).clearAndSetSemantics {},
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            Hint(Icons.Rounded.ArrowUpward, stringResource(R.string.hint_up), stringResource(R.string.dir_keep), c.keep)
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                Hint(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.hint_left), stringResource(R.string.dir_trash), c.cleanup)
                Hint(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.hint_right), stringResource(R.string.dir_later), c.later)
            }
            Hint(Icons.Rounded.ArrowDownward, stringResource(R.string.hint_down), stringResource(R.string.dir_back), c.textSecondary)
        }
    }
}

@Composable
private fun Hint(icon: ImageVector, title: String, meaning: String, tint: Color) {
    Column(Modifier.widthIn(min = 116.dp).glassRect().padding(horizontal = AppSpacing.s, vertical = AppSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Text(title, style = AppTypography.caption, color = PM.colors.textPrimary)
        Text(meaning, style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold), color = tint, textAlign = TextAlign.Center)
    }
}

@Composable
fun SessionSummaryView(summary: SessionSummary, basketCount: Int, modifier: Modifier, reviewBasket: () -> Unit, goBack: () -> Unit, done: () -> Unit) {
    val c = PM.colors
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(AppSpacing.pageMargin),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.l)) {
        Icon(Icons.Rounded.Verified, null, tint = c.accent, modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.summary_done), style = AppTypography.title, color = c.textPrimary)
        SurfaceCard {
            @Composable
            fun row(icon: ImageVector, title: String, n: Int, tint: Color) {
                Row(Modifier.fillMaxWidth().padding(vertical = AppSpacing.xxs).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp).padding(end = 4.dp))
                    Text(title, style = AppTypography.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                    Text("$n", style = AppTypography.headline, color = c.textPrimary)
                }
            }
            row(Icons.Rounded.ArrowUpward, stringResource(R.string.summary_kept), summary.kept, c.keep)
            row(Icons.Rounded.Delete, stringResource(R.string.summary_trash), summary.marked, c.cleanup)
            row(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.summary_later), summary.later, c.later)
        }
        if (basketCount > 0) {
            ProminentButton(stringResource(R.string.summary_view_basket, basketCount), fillWidth = true, large = true, onClick = reviewBasket)
            Text(stringResource(R.string.summary_basket_hint), style = AppTypography.caption, color = c.textSecondary, textAlign = TextAlign.Center)
        }
        GlassButton(stringResource(R.string.summary_last), onClick = goBack)
        GlassButton(stringResource(R.string.done), onClick = done)
        Spacer(Modifier.size(AppSpacing.s))
    }
}

@Suppress("unused")
private fun Modifier.dim(v: Boolean) = if (v) alpha(0.45f) else this
