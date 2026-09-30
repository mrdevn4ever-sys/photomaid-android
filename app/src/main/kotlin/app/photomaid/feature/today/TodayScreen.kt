package app.photomaid.feature.today

import app.photomaid.design.LocalReduceMotion
import app.photomaid.design.RowDividerInset
import app.photomaid.design.RowDivider
import app.photomaid.design.RowStatus
import app.photomaid.design.RowGroup
import app.photomaid.design.ListRow
import app.photomaid.design.AppControl
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.Crossfade
import androidx.compose.material.icons.rounded.WorkspacePremium
import app.photomaid.core.capabilities.Capabilities
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import app.photomaid.feature.quickaccess.QuickAccessSection
import app.photomaid.media.PhotoAccess
import app.photomaid.media.MediaAsset
import app.photomaid.media.reviewableMedia
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.Routes
import app.photomaid.navigation.TabBarEndSpacer
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
    // Eine Momentaufnahme für Hero UND Schnellzugriffe (keine zweite Mediathek-Abfrage je Kachel).
    var snapshot by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var device by remember { mutableStateOf<DeviceCapacity?>(null) }

    LaunchedEffect(token, access, read.basket.size, read.laterRecords.size, read.events.size) {
        withContext(Dispatchers.IO) {
            device = DeviceStorage.read(ctx)
            val all = if (access.canRead) app.photos.reviewableMedia() else emptyList()
            snapshot = all
            pending = if (!access.canRead) null else app.pendingQueue(all).map { it.localIdentifier }
        }
    }

    val settings by app.settings.collectAsState()
    AppBackground {
        // Rhythmus: 24 dp zwischen Blöcken; Reihenfolge Handlung → Zustand → Einstiege → Info.
        Column(Modifier.fillMaxSize().wrapContentWidth().readableWidth().fillMaxWidth().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = AppSpacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.block)) {
            LargeTitleHeader(stringResource(R.string.tab_today)) {
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
            if (access.canRead) ReviewHero(pending, hasHistory = read.events.isNotEmpty(), showGestures = !settings.gestureHintSeen) {
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
            if (read.reconciliationNeeded.isNotEmpty()) {
                SurfaceCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Icon(Icons.Rounded.Sync, null, tint = c.warning)
                        Text(stringResource(R.string.today_reconciliation), style = AppTypography.subheadline, color = c.warning)
                    }
                }
            }
            // Feste Gruppe: was im Papierkorb liegt, was zurückgestellt ist, was wieder dran ist. Echte Zahlen, 0 leise.
            RowGroup(Modifier.testTag("today.status")) {
                val basketDesc = pluralStringResource(R.plurals.items_count, read.basket.size, read.basket.size)
                val laterDesc = pluralStringResource(R.plurals.items_count, read.laterRecords.size, read.laterRecords.size)
                val basket = stringResource(R.string.basket); val later = stringResource(R.string.later)
                if (app.dueCount > 0) {
                    ListRow(Icons.Rounded.EventRepeat, pluralStringResource(R.plurals.today_due, app.dueCount, app.dueCount), Modifier.testTag("today.due"),
                        tint = c.later, tile = c.later.copy(alpha = 0.12f), titleColor = c.textPrimary) { nav.openFeed(FeedRequest(FeedRequest.Source.Due)) }
                    RowDivider(RowDividerInset)
                }
                ListRow(Icons.Rounded.Delete, basket, Modifier.testTag("today.basket"), tint = c.cleanup, tile = c.cleanup.copy(alpha = 0.12f),
                    status = RowStatus.Count(read.basket.size), description = "$basket, $basketDesc") { nav.push(Routes.BASKET) }
                RowDivider(RowDividerInset)
                ListRow(Icons.Rounded.Schedule, later, Modifier.testTag("today.later"), tint = c.later, tile = c.later.copy(alpha = 0.12f),
                    status = RowStatus.Count(read.laterRecords.size), description = "$later, $laterDesc") { nav.push(Routes.LATER) }
            }
            // Schnellzugriffe: gefilterte Einstiege in denselben Feed; neu berechnet bei Mediathek- oder Entscheidungsänderung.
            if (access.canRead) QuickAccessSection(nav, snapshot, pending?.toSet())
            // Leiser Pro-Hinweis: nur Free, erst ab dem zweiten Nutzungstag – dieselbe Zeile wie alle anderen, kein Banner.
            val firstDay = settings.firstSeenDay
            if (app.capabilities.tier == Capabilities.Tier.FREE && firstDay != null && app.today > firstDay) RowGroup {
                ListRow(Icons.Rounded.WorkspacePremium, stringResource(R.string.today_pro_title), Modifier.testTag("today.pro"),
                    subtitle = stringResource(R.string.today_pro_text), onClick = nav::openPro)
            }
            device?.let { d -> StorageLine(d) { nav.push(Routes.STORAGE) } }
            TabBarEndSpacer(minus = AppSpacing.block)
        }
    }
}

/** Gerätespeicher: bewusst leise – keine Karte, nur eine Zeile + dünner Balken; öffnet die Speicherübersicht. */
@Composable
private fun StorageLine(d: DeviceCapacity, onClick: () -> Unit) {
    val c = PM.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadii.small)).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = AppSpacing.xxs, vertical = AppSpacing.xs).testTag("today.storage").semantics(mergeDescendants = true) {}) {
        val title = stringResource(R.string.today_storage_title)
        val value = stringResource(R.string.today_storage_value, SizeText.bytes(d.usedBytes), SizeText.bytes(d.totalBytes))
        // Große Schrift: untereinander statt nebeneinander (sonst bricht der Titel zeichenweise um).
        if (LocalDensity.current.fontScale >= 1.3f) {
            Text(title, style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
            Text(value, style = AppTypography.footnote, color = c.textSecondary)
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            Text(title, style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(value, style = AppTypography.footnote, color = c.textSecondary, maxLines = 1)
        }
        if (d.totalBytes > 0) {
            Spacer(Modifier.height(AppSpacing.xs))
            LinearProgressIndicator(progress = { (d.usedBytes.toFloat() / d.totalBytes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(AppStroke.progress).clip(CircleShape),
                color = c.textSecondary.copy(alpha = 0.55f), trackColor = c.backgroundTint, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
        }
    }
}

/**
 * Hero: der Feed ist das Produkt. Die nächsten echten Medien randlos oben in der Karte (1–3, nie leere Kacheln),
 * darunter die Zahl offener Medien und EIN großer CTA. Zustände: neu („Jetzt aufräumen“), Fortsetzung („Weiter
 * aufräumen“), alles geprüft, Laden (ruhige Fläche statt Spinner). Einzige Karte mit Schatten.
 */
@Composable
private fun ReviewHero(pending: List<String>?, hasHistory: Boolean, showGestures: Boolean, start: () -> Unit) {
    val c = PM.colors
    val shape = RoundedCornerShape(AppRadii.hero)
    Column(Modifier.fillMaxWidth().shadow(12.dp, shape, clip = false, ambientColor = Color(0x14000000), spotColor = Color(0x14000000))
        .clip(shape).background(c.surface, shape).border(1.dp, c.surfaceStroke, shape).testTag("today.review")) {
        when {
            pending == null -> {
                // Laden: ruhige Flächen in der späteren Geometrie (kein Spinner, kein Springen).
                Box(Modifier.fillMaxWidth().height(HeroStripHeight).background(c.backgroundTint))
                Column(Modifier.padding(AppSpacing.m), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(22.dp).clip(RoundedCornerShape(6.dp)).background(c.backgroundTint))
                    Box(Modifier.fillMaxWidth(0.8f).height(14.dp).clip(RoundedCornerShape(6.dp)).background(c.backgroundTint))
                    Spacer(Modifier.height(AppSpacing.xs))
                    Box(Modifier.fillMaxWidth().height(AppControl.largeButton).clip(CircleShape).background(c.backgroundTint))
                }
            }
            pending.isEmpty() -> Row(Modifier.padding(AppSpacing.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(AppRadii.small)).background(c.success.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = c.success, modifier = Modifier.size(AppIconSize.medium))
                }
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(stringResource(R.string.today_done_title), style = AppTypography.title, color = c.textPrimary)
                    Text(stringResource(R.string.today_done_subtitle), style = AppTypography.subheadline, color = c.textSecondary)
                }
            }
            else -> {
                // Randlos: 1 Bild = volle Breite, 2 = Hälften, 3 = Drittel; 2 dp Fugen.
                Row(Modifier.fillMaxWidth().height(HeroStripHeight).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    pending.take(3).forEachIndexed { i, uri -> HeroThumb(i, uri, Modifier.weight(1f).fillMaxHeight()) }
                }
                Column(Modifier.padding(start = AppSpacing.m, end = AppSpacing.m, top = AppSpacing.m, bottom = AppSpacing.m)) {
                    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(pluralStringResource(R.plurals.today_pending_count, pending.size, pending.size), style = AppTypography.title, color = c.textPrimary)
                        Text(stringResource(if (hasHistory) R.string.today_continue_sub else R.string.today_start_sub), style = AppTypography.subheadline, color = c.textSecondary)
                    }
                    Spacer(Modifier.height(AppSpacing.s))
                    val hint = stringResource(R.string.today_review_hint)
                    ProminentButton(stringResource(if (hasHistory) R.string.today_continue_cta else R.string.today_start_cta), fillWidth = true, large = true,
                        modifier = Modifier.testTag("today.cta").semantics { onClick(label = hint) { start(); true } }, onClick = start)
                    // Gesten nur für Neulinge (bis die Gestenhilfe im Feed gesehen wurde) – Symbole statt Pfeilzeichen.
                    if (showGestures) {
                        Spacer(Modifier.height(AppSpacing.xs))
                        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}.testTag("today.gestures"), horizontalArrangement = Arrangement.spacedBy(AppSpacing.m, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically) {
                            GestureChip(Icons.Rounded.ArrowUpward, stringResource(R.string.dir_keep), c.keep)
                            GestureChip(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dir_trash), c.cleanup)
                            GestureChip(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.dir_later), c.later)
                        }
                    }
                }
            }
        }
    }
}

private val HeroStripHeight = 168.dp

@Composable
private fun GestureChip(icon: ImageVector, label: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, style = AppTypography.footnote, color = PM.colors.textSecondary)
    }
}

/** Vorschaubild der Hero; wechselt nach einer Entscheidung weich zum nächsten offenen Medium. */
@Composable
private fun HeroThumb(slot: Int, uri: String, modifier: Modifier) {
    val px = with(LocalDensity.current) { 180.dp.roundToPx() }
    val reduce = LocalReduceMotion.current
    Box(modifier.background(PM.colors.backgroundTint)) {
        Crossfade(uri, animationSpec = if (reduce) snap() else tween(200), label = "hero$slot") { u ->
            AsyncImage(ImageRequest.Builder(LocalContext.current).data(android.net.Uri.parse(u)).size(px, px).memoryCacheKey("hero:$u").build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
