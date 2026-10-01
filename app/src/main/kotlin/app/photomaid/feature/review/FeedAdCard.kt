package app.photomaid.feature.review

import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.photomaid.R
import app.photomaid.ads.FeedAd
import app.photomaid.core.ads.FeedAdPolicy
import app.photomaid.design.AppRadii
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassButton
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.glassRect
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAdView
import kotlinx.coroutines.delay

/**
 * Werbekarte zwischen zwei Medien (nur Free). Folgt dem Finger wie ein Medium; jede Wischrichtung überspringt sie
 * nach 1,5 s – nie eine Entscheidung, zählt nicht fürs Tageslimit. Klicks auf die Anzeige nur über ihren Knopf.
 * Darunter: „Ohne Werbung mit Pro“.
 */
@Composable
fun AdStage(model: FeedViewModel, card: AdCard, reduceMotion: Boolean, openPro: () -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    val c = PM.colors
    var canSkip by remember(card.id) { mutableStateOf(model.adSkippable) }
    LaunchedEffect(card.id) {
        val left = FeedAdPolicy.MIN_VISIBLE_MS - (model.nowMs() - card.shownAtMs)
        if (left > 0) delay(left)
        canSkip = true
    }
    val skipLabel = stringResource(R.string.ad_skip)
    Column(modifier.fillMaxWidth().padding(horizontal = AppSpacing.xs).testTag("feed.ad")
        .semantics { customActions = listOf(CustomAccessibilityAction(skipLabel) { if (model.adSkippable) model.dismissAd() else false }) }
        // Ab Berührungsschwelle übernimmt die Karte die Geste (Initial-Pass): die Anzeige bekommt ein CANCEL und
        // zählt kein Wischen als Klick (AdMob-Richtlinie: keine versehentlichen Klicks). Tipps bleiben bei der Anzeige.
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var dragging = false
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!dragging && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) dragging = true
                    if (dragging) ch.consume()
                    if (!ch.pressed) break
                }
            }
        }
        .feedGestures(enabled = true, density = density, onDrag = model::dragChanged, onEnd = { t, v -> model.dragEnded(t, v, reduceMotion) },
            onCancel = model::dragCancelled, onZoom = {}, onDoubleTap = {}, yieldToChildren = false),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Box(Modifier.weight(1f).fillMaxWidth().offset { val o = model.offset.value; with(density) { IntOffset(o.x.dp.roundToPx(), o.y.dp.roundToPx()) } }
            .clip(RoundedCornerShape(AppRadii.media)).background(c.surface)) {
            Column(Modifier.fillMaxSize().padding(AppSpacing.m), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                Text(stringResource(R.string.ad_label), style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary,
                    modifier = Modifier.glassRect(AppRadii.control, 0.dp).padding(horizontal = AppSpacing.xs, vertical = 2.dp).testTag("feed.ad.label"))
                Box(Modifier.weight(1f).fillMaxWidth()) { AdContent(card.ad) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.pageMargin - AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
            PlainButton(stringResource(R.string.ad_remove), Modifier.weight(1f).testTag("feed.ad.pro"), onClick = openPro)
            // Vor der Mindestdauer: derselbe Knopf, nur gedimmt (stabiles Layout, kein Springen).
            val wait = stringResource(R.string.ad_wait)
            GlassButton(skipLabel, Modifier.testTag(if (canSkip) "feed.ad.skip" else "feed.ad.wait")
                .semantics { if (!canSkip) stateDescription = wait }, enabled = canSkip) { model.dismissAd() }
        }
    }
}

/**
 * Anzeige unter der Sitzungs-Zusammenfassung (nur Free). Ruhig, fest gekennzeichnet, keine Entscheidung.
 * Die Anzeige (`NativeAdView`) nimmt jede Berührung an; damit die Zusammenfassung über ihr trotzdem scrollt,
 * übernimmt die Karte ab der Berührungsschwelle die Bewegung (Initial-Pass) und scrollt selbst –
 * die Anzeige erhält ein CANCEL, ein Scrollen zählt nie als Klick.
 */
@Composable
fun SummaryAd(ad: FeedAd, scroll: ScrollState, openPro: () -> Unit, modifier: Modifier = Modifier) {
    val c = PM.colors
    val fling = ScrollableDefaults.flingBehavior()
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth().testTag("summary.ad"), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Box(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(AppRadii.card)).background(c.surface)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    var dragging = false
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        if (!dragging && abs(ch.position.y - down.position.y) > viewConfiguration.touchSlop) dragging = true
                        if (dragging) {
                            val dy = ch.position.y - ch.previousPosition.y
                            ch.consume()
                            scroll.dispatchRawDelta(-dy)
                        }
                        if (!ch.pressed) {
                            if (dragging) {
                                val v = -tracker.calculateVelocity().y
                                scope.launch { scroll.scroll(MutatePriority.UserInput) { with(fling) { performFling(v) } } }
                            }
                            break
                        }
                    }
                }
            }) {
            Column(Modifier.fillMaxSize().padding(AppSpacing.m), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                Text(stringResource(R.string.ad_label), style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary,
                    modifier = Modifier.glassRect(AppRadii.control, 0.dp).padding(horizontal = AppSpacing.xs, vertical = 2.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) { AdContent(ad) }
            }
        }
        PlainButton(stringResource(R.string.ad_remove), Modifier.align(Alignment.CenterHorizontally).testTag("summary.ad.pro"), onClick = openPro)
    }
}

/** Echte Anzeige in `NativeAdView` (Pflicht für AdMob-Native); ohne SDK (Tests) nur die Texte. */
@Composable
private fun AdContent(ad: FeedAd) {
    val c = PM.colors
    val native = ad.native
    if (native == null) {
        // Ohne SDK (Tests) verhält sich die Fläche wie `NativeAdView`: sie nimmt jede Berührung an (Klick-Erfassung).
        // So prüfen die Tests realistisch, dass Wischen über der Anzeige trotzdem beim Feed ankommt (Gerätebefund E5).
        Column(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
            }
        }, verticalArrangement = Arrangement.spacedBy(AppSpacing.xs, Alignment.CenterVertically)) {
            Text(ad.headline, style = AppTypography.title, color = c.textPrimary)
            ad.body?.let { Text(it, style = AppTypography.body, color = c.textSecondary) }
            ad.advertiser?.let { Text(it, style = AppTypography.footnote, color = c.textSecondary) }
            ad.callToAction?.let { GlassButton(it) {} }
        }
        return
    }
    val primary = c.textPrimary.toArgb(); val secondary = c.textSecondary.toArgb(); val accent = c.accent.toArgb()
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
        NativeAdView(ctx).apply {
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val media = MediaView(ctx)
            val headline = TextView(ctx).apply { textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(primary) }
            val body = TextView(ctx).apply { textSize = 15f; setTextColor(secondary); maxLines = 3 }
            val advertiser = TextView(ctx).apply { textSize = 13f; setTextColor(secondary) }
            val cta = Button(ctx).apply { isAllCaps = false; setTextColor(android.graphics.Color.WHITE); setBackgroundColor(accent) }
            col.addView(media, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            for (v in listOf(headline, body, advertiser)) col.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 })
            col.addView(cta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 })
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            mediaView = media; headlineView = headline; bodyView = body; advertiserView = advertiser; callToActionView = cta
        }
    }, update = { v ->
        (v.headlineView as TextView).text = native.headline
        (v.bodyView as TextView).text = native.body ?: ""
        (v.advertiserView as TextView).text = native.advertiser ?: ""
        (v.callToActionView as Button).text = native.callToAction ?: ""
        v.setNativeAd(native)
    })
}

