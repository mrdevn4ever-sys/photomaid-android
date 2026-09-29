package app.photomaid.feature.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.photomaid.FeedRequest
import app.photomaid.R
import app.photomaid.core.later.ReviewQueues
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.core.storage.DeviceCapacity
import app.photomaid.design.AppBackground
import app.photomaid.design.AppIconSize
import app.photomaid.design.AppRadii
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppStroke
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassIconButton
import app.photomaid.design.LargeTitleHeader
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.ProminentButton
import app.photomaid.design.SizeText
import app.photomaid.design.SurfaceCard
import app.photomaid.design.readableWidth
import app.photomaid.feature.common.DeviceStorage
import app.photomaid.media.PhotoAccess
import app.photomaid.media.reviewableMedia
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.Routes
import app.photomaid.navigation.TabBarClearance
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Screen 1 – Heute. Beantwortet „Was kann ich jetzt tun?“: ein Hero zum Feed („Jetzt aufräumen“ bzw.
 * „Weiter aufräumen“) mit den nächsten echten Medien, danach Wieder-fällig, eine kompakte Zeile Papierkorb/Später
 * und zuletzt – bewusst leise – der Gerätespeicher. Kategorien und Werkzeuge liegen unter „Aufräumen“.
 */
@Composable
fun TodayScreen(nav: Nav) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val c = PM.colors
    val access by app.photos.access.collectAsState()
    val token by app.photos.changeToken.collectAsState()
    val read by app.read.collectAsState()
    var pending by remember { mutableStateOf<List<String>?>(null) }
    var device by remember { mutableStateOf<DeviceCapacity?>(null) }

    LaunchedEffect(token, access, read.basket.size, read.laterRecords.size, read.events.size) {
        withContext(Dispatchers.IO) {
            device = DeviceStorage.read(ctx)
            pending = if (!access.canRead) null else {
                val refs = app.photos.reviewableMedia().map { it.reference }
                ReviewQueues.normalQueue(refs, app.ledger.records(refs), ReviewSessionID(), app.today).map { it.localIdentifier }
            }
        }
    }

    AppBackground {
        Column(Modifier.fillMaxSize().wrapContentWidth().readableWidth().fillMaxWidth().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = AppSpacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            LargeTitleHeader(stringResource(R.string.app_name), stringResource(R.string.today_subtitle)) {
                GlassIconButton(Icons.Rounded.Settings, stringResource(R.string.settings), onClick = nav::openSettings)
            }
            if (!access.canRead) {
                SurfaceCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Icon(Icons.Rounded.PhotoLibrary, null, tint = c.accent)
                        Text(stringResource(if (access == PhotoAccess.DENIED) R.string.today_access_denied_title else R.string.today_access_title),
                            style = AppTypography.headline, color = c.textPrimary)
                    }
                    Spacer(Modifier.height(AppSpacing.s))
                    Text(stringResource(if (access == PhotoAccess.DENIED) R.string.today_access_denied else R.string.today_access_default),
                        style = AppTypography.subheadline, color = c.textSecondary)
                    Spacer(Modifier.height(AppSpacing.s))
                    // Was kann ich tun? Direkt zur Zugriffsseite (dort Systemdialog bzw. Android-Einstellungen).
                    ProminentButton(stringResource(if (access == PhotoAccess.DENIED) R.string.today_limited_action else R.string.privacy_allow),
                        fillWidth = true, large = true, onClick = nav::openPrivacy)
                }
            }
            if (access.canRead) ReviewHero(pending, hasHistory = read.events.isNotEmpty()) {
                nav.openFeed(FeedRequest(FeedRequest.Source.AllMedia))
            }
            if (access == PhotoAccess.LIMITED) {
                // Nachgeordnet und ruhig: der Feed funktioniert mit den freigegebenen Medien.
                SurfaceCard(Modifier.testTag("today.limited"), padding = PaddingValues(horizontal = AppSpacing.m, vertical = AppSpacing.s)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                        Icon(Icons.Rounded.PhotoLibrary, null, tint = c.accent)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.today_limited_title), style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
                            Text(stringResource(R.string.today_limited_text), style = AppTypography.caption, color = c.textSecondary)
                        }
                    }
                    PlainButton(stringResource(R.string.today_limited_action), onClick = nav::openPrivacy)
                }
            }
            if (app.dueCount > 0) {
                SurfaceCard(Modifier.testTag("today.due"), onClick = { nav.openFeed(FeedRequest(FeedRequest.Source.Due)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Icon(Icons.Rounded.EventRepeat, null, tint = c.later)
                        Text(pluralStringResource(R.plurals.today_due, app.dueCount, app.dueCount), style = AppTypography.headline,
                            color = c.textPrimary, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.textSecondary)
                    }
                }
            }
            if (read.reconciliationNeeded.isNotEmpty()) {
                SurfaceCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Icon(Icons.Rounded.Sync, null, tint = c.warning)
                        Text(stringResource(R.string.today_reconciliation), style = AppTypography.subheadline, color = c.warning)
                    }
                }
            }
            // Echte Zahlen: was gerade im Papierkorb liegt und was zurückgestellt ist (keine „freigegeben“-Behauptung).
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                StatTile(Icons.Rounded.Delete, c.cleanup, stringResource(R.string.basket), read.basket.size, Modifier.weight(1f).testTag("today.basket")) { nav.push(Routes.BASKET) }
                StatTile(Icons.Rounded.Bookmark, c.later, stringResource(R.string.later), read.laterRecords.size, Modifier.weight(1f).testTag("today.later")) { nav.push(Routes.LATER) }
            }
            device?.let { d -> StorageLine(d) { nav.push(Routes.STORAGE) } }
            Spacer(Modifier.height(TabBarClearance))
        }
    }
}

/** Kompakte Kachel mit echter Zahl (Papierkorb, Später). */
@Composable
private fun StatTile(icon: ImageVector, tint: Color, title: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val c = PM.colors
    val desc = pluralStringResource(R.plurals.items_count, count, count)
    SurfaceCard(modifier.semantics(mergeDescendants = true) { contentDescription = "$title, $desc" },
        padding = PaddingValues(horizontal = AppSpacing.m, vertical = AppSpacing.s), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(AppIconSize.regular))
            Text(title, style = AppTypography.subheadline, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Text("$count", style = AppTypography.title, color = c.textPrimary)
    }
}

/** Gerätespeicher: bewusst leise (eine Zeile + dünner Balken), öffnet die Speicherübersicht. */
@Composable
private fun StorageLine(d: DeviceCapacity, onClick: () -> Unit) {
    val c = PM.colors
    SurfaceCard(Modifier.testTag("today.storage").semantics(mergeDescendants = true) {},
        padding = PaddingValues(horizontal = AppSpacing.m, vertical = AppSpacing.s), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.today_storage_title), style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary,
                modifier = Modifier.weight(1f))
            Text(stringResource(R.string.today_storage_value, SizeText.bytes(d.usedBytes), SizeText.bytes(d.totalBytes)),
                style = AppTypography.footnote, color = c.textSecondary)
        }
        if (d.totalBytes > 0) {
            Spacer(Modifier.height(AppSpacing.xs))
            LinearProgressIndicator(progress = { (d.usedBytes.toFloat() / d.totalBytes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(AppStroke.progress).clip(CircleShape),
                color = c.accent.copy(alpha = 0.7f), trackColor = c.backgroundTint, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
        }
    }
}

/**
 * Hero: der Feed ist das Produkt. Zeigt die nächsten echten Medien als kleine Vorschau, die Zahl offener Medien und
 * EINEN großen CTA. Zustände: neu („Jetzt aufräumen“), Fortsetzung („Weiter aufräumen“), alles geprüft.
 */
@Composable
private fun ReviewHero(pending: List<String>?, hasHistory: Boolean, start: () -> Unit) {
    val c = PM.colors
    SurfaceCard(Modifier.testTag("today.review")) {
        when {
            pending == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            pending.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(AppRadii.small)).background(c.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.size(AppIconSize.medium))
                }
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(stringResource(R.string.today_done_title), style = AppTypography.title, color = c.textPrimary)
                    Text(stringResource(R.string.today_done_subtitle), style = AppTypography.subheadline, color = c.textSecondary)
                }
            }
            else -> {
                // Vorschau der nächsten Medien (echte Thumbnails, keine Deko-Grafik).
                Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    pending.take(3).forEach { HeroThumb(it, Modifier.weight(1f).aspectRatio(0.8f)) }
                    repeat(3 - minOf(3, pending.size)) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(AppSpacing.m))
                Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(pluralStringResource(R.plurals.today_pending_count, pending.size, pending.size), style = AppTypography.title, color = c.textPrimary)
                    Text(stringResource(if (hasHistory) R.string.today_continue_sub else R.string.today_start_sub), style = AppTypography.subheadline, color = c.textSecondary)
                }
                Spacer(Modifier.height(AppSpacing.m))
                val hint = stringResource(R.string.today_review_hint)
                ProminentButton(stringResource(if (hasHistory) R.string.today_continue_cta else R.string.today_start_cta), fillWidth = true, large = true,
                    modifier = Modifier.testTag("today.cta").semantics { onClick(label = hint) { start(); true } }, onClick = start)
                Spacer(Modifier.height(AppSpacing.xs))
                Text(stringResource(R.string.today_gestures), style = AppTypography.caption, color = c.textSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics {})
            }
        }
    }
}

@Composable
private fun HeroThumb(uri: String, modifier: Modifier) {
    val px = with(LocalDensity.current) { 140.dp.roundToPx() }
    Box(modifier.clip(RoundedCornerShape(AppRadii.small)).background(PM.colors.backgroundTint)) {
        AsyncImage(ImageRequest.Builder(LocalContext.current).data(android.net.Uri.parse(uri)).size(px, px).memoryCacheKey("hero:$uri").build(),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
