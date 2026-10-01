package app.photomaid.feature.pro

import app.photomaid.feature.onboarding.OfferBenefits
import app.photomaid.feature.onboarding.OfferHero
import app.photomaid.design.clickableRow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.rounded.Block
import app.photomaid.design.ListSection
import app.photomaid.design.AppRadii
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.FilterNone
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.ads.RewardedAdResult
import app.photomaid.ads.RewardOffer
import app.photomaid.design.AppIconSize
import app.photomaid.design.Badge
import app.photomaid.design.PlainButton
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import app.photomaid.billing.PurchaseResult
import app.photomaid.core.capabilities.Capabilities
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.design.AppBackground
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassButton
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.design.SurfaceCard
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.system.SystemDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Restminuten, aufgerundet (nie „0 Min.“, solange noch etwas läuft). */
fun minutesLeft(d: java.time.Duration): Int = ((d.seconds + 59) / 60).toInt().coerceAtLeast(1)

/**
 * Screen 14 – PhotoMaid Pro. Primär: Einmalkauf (echter Storepreis) und Wiederherstellen. Sekundär und ruhig:
 * „30 Minuten Pro testen“ (Rewarded, 1× täglich; Papierkorb-Limit bleibt unverändert). Lifetime-Nutzer sehen
 * nur ihren Status – keine Werbung, kein Upsell.
 */
@Composable
fun ProScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val purchases = app.purchases
    val state by purchases.state.collectAsState()
    val product by purchases.product.collectAsState()
    val now by app.entitlements.now.collectAsState()
    val rewards by app.entitlements.rewards.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { purchases.refresh() } } }
    // Ablauf des Tests auch bei offenem Screen sichtbar machen.
    LaunchedEffect(Unit) { while (true) { delay(15_000); app.entitlements.tick() } }
    now; rewards; state
    val caps = app.capabilities
    val lifetime = caps.tier == Capabilities.Tier.LIFETIME_PRO

    AppBackground {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.s).padding(top = AppSpacing.xl)) {
                TextButton(nav::back) { Text(stringResource(R.string.close)) }
            }
            Column(Modifier.padding(horizontal = AppSpacing.pageMargin), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.l)) {
                // Lebende Wischkarte wie im Onboarding-Angebot (gleiche Bausteine).
                OfferHero()
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
                    Text(stringResource(R.string.pro_title), style = AppTypography.largeTitle, color = c.textPrimary, textAlign = TextAlign.Center)
                    Text(stringResource(if (lifetime) R.string.pro_active_sub else R.string.pro_subtitle), style = AppTypography.subheadline,
                        color = c.textSecondary, textAlign = TextAlign.Center)
                }
                // Tatsächlich freigeschaltete Vorteile (Capabilities: werbefrei, Limit, identische Kopien, eigene Sammlungen).
                // Free: ehrlicher Vergleich wie im Onboarding (kostenlos unbegrenzt + Pro einmalig). Lifetime: freigeschaltete Vorteile.
                if (!lifetime) OfferBenefits()
                else SurfaceCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
                        Feature(Icons.Rounded.Block, R.string.pro_f1, R.string.pro_f1d)
                        Feature(Icons.Rounded.AllInclusive, R.string.pro_f2, R.string.pro_f2d)
                        Feature(Icons.Rounded.FilterNone, R.string.pro_f3, R.string.pro_f3d)
                        Feature(Icons.Rounded.SettingsSuggest, R.string.pro_f5, R.string.pro_f5d)
                    }
                }
                if (!lifetime) Text(stringResource(R.string.pro_also), style = AppTypography.footnote, color = c.textSecondary, textAlign = TextAlign.Center)
                // Kaufbereich (primär)
                when {
                    lifetime -> Row(Modifier.testTag("pro.lifetime"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Icon(Icons.Rounded.Verified, null, tint = c.success)
                        Text(stringResource(R.string.pro_active_title), style = AppTypography.headline, color = c.success)
                    }
                    state == PurchaseState.PENDING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Icon(Icons.Rounded.HourglassTop, null, tint = c.textSecondary)
                        Text(stringResource(R.string.pro_pending), style = AppTypography.subheadline, color = c.textPrimary)
                    }
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                        caps.rewardRemaining?.let { left -> TemporaryProStatus(minutesLeft(left)) }
                        val p = product
                        if (purchases.isConfigured && p != null) {
                            ProminentButton(stringResource(R.string.pro_unlock), fillWidth = true, large = true, enabled = !busy,
                                modifier = Modifier.testTag("pro.buy"), subtitle = stringResource(R.string.pro_price_once, p.localizedPrice)) {
                                val activity = SystemDialogs.activity ?: return@ProminentButton
                                scope.launch {
                                    busy = true
                                    app.player.stop()
                                    message = when (app.purchasePro(activity)) {
                                        PurchaseResult.Purchased -> res.getString(R.string.pro_msg_active)
                                        PurchaseResult.Pending -> res.getString(R.string.pro_msg_pending)
                                        PurchaseResult.Cancelled -> null
                                        is PurchaseResult.Failed -> res.getString(R.string.pro_msg_failed)
                                        PurchaseResult.NotConfigured -> res.getString(R.string.pro_unavailable)
                                    }
                                    app.reload()
                                    busy = false
                                }
                            }
                        } else {
                            // Ohne echte Storekonfiguration kein erfundener Preis und kein toter Button: nur ein kompakter Status.
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadii.small)).background(c.backgroundTint).padding(AppSpacing.s),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                                Icon(if (purchases.isConfigured) Icons.Rounded.HourglassTop else Icons.Rounded.Info, null, tint = c.textSecondary)
                                Text(stringResource(if (purchases.isConfigured) R.string.pro_price_loading else R.string.pro_unavailable),
                                    style = AppTypography.subheadline, color = c.textPrimary)
                            }
                        }
                        if (purchases.isConfigured) PlainButton(stringResource(R.string.pro_restore), enabled = !busy) {
                            scope.launch {
                                busy = true
                                message = res.getString(if (purchases.restore() == PurchaseResult.Purchased) R.string.pro_msg_restored else R.string.pro_msg_nothing)
                                app.reload()
                                busy = false
                            }
                        }
                        if (state == PurchaseState.REVOKED) Text(stringResource(R.string.pro_revoked), style = AppTypography.caption, color = c.warning)
                    }
                }
                // Sekundär: 30-Minuten-Test. Nie für Lifetime, nie ohne Anbieter, nie automatisch gestartet.
                val offer = app.entitlements.rewardOffer
                if (offer != RewardOffer.HIDDEN && offer != RewardOffer.ACTIVE) RewardCard(offer, busy) {
                    scope.launch {
                        busy = true
                        message = when (app.entitlements.watchRewardedAd { app.player.stop() }) {
                            is RewardedAdResult.Rewarded -> res.getString(R.string.pro_msg_rewarded)
                            RewardedAdResult.DismissedWithoutReward -> res.getString(R.string.pro_msg_dismissed)
                            RewardedAdResult.NoFill -> res.getString(R.string.pro_msg_nofill)
                            is RewardedAdResult.Failed, RewardedAdResult.NotConfigured -> res.getString(R.string.pro_msg_ad_unavailable)
                        }
                        app.reload()
                        busy = false
                    }
                }
                // Technische Einzelheiten nachgeordnet und aufklappbar.
                var details by rememberSaveable { mutableStateOf(false) }
                val detailState = stringResource(if (details) R.string.expanded else R.string.collapsed)
                ListSection {
                    Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.minTouch).clickableRow { details = !details }
                        .semantics { stateDescription = detailState }, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.pro_details), style = AppTypography.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                        Icon(if (details) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.textSecondary)
                    }
                    if (details) Column(Modifier.padding(bottom = AppSpacing.s), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        for (t in listOf(R.string.pro_details_cleanup, R.string.pro_details_dup, R.string.pro_details_rules))
                            Text(stringResource(t), style = AppTypography.footnote, color = c.textSecondary)
                    }
                }
                Text(stringResource(if (app.adSdk.isConfigured || app.entitlements.ads.isConfigured) R.string.pro_privacy else R.string.pro_privacy_no_ads), style = AppTypography.caption, color = c.textSecondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(AppSpacing.l))
            }
        }
    }
    NoticeDialog(message) { message = null }
}

/** Dezenter Status des laufenden Tests + der Limit-Hinweis (kein Countdown-Banner). */
@Composable
fun TemporaryProStatus(minutes: Int, modifier: Modifier = Modifier) {
    val c = PM.colors
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadii.small)).background(c.accent.copy(alpha = 0.08f)).padding(AppSpacing.s)
        .semantics(mergeDescendants = true) {}.testTag("pro.temporary"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Icon(Icons.Rounded.Timer, null, tint = c.accent, modifier = Modifier.size(AppIconSize.small))
            Text(stringResource(R.string.reward_active, minutes), style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
        }
        Text(stringResource(R.string.reward_limit_note), style = AppTypography.footnote, color = c.textSecondary)
    }
}

/** In Pro-Werkzeugen: kleiner Hinweis, dass sie gerade über den 30-Minuten-Test freigeschaltet sind. */
@Composable
fun TemporaryProTag(caps: Capabilities, modifier: Modifier = Modifier) {
    val left = caps.rewardRemaining ?: return
    if (caps.tier != Capabilities.Tier.TEMPORARY_PRO) return
    Row(modifier.testTag("pro.temporary.tag"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
        Icon(Icons.Rounded.Timer, null, tint = PM.colors.accent, modifier = Modifier.size(AppIconSize.small))
        Text(stringResource(R.string.reward_active_tools, minutesLeft(left)), style = AppTypography.caption, color = PM.colors.textSecondary)
    }
}

@Composable
private fun RewardCard(offer: RewardOffer, busy: Boolean, watch: () -> Unit) {
    val c = PM.colors
    SurfaceCard(Modifier.testTag("pro.reward")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Text(stringResource(R.string.reward_title), style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.weight(1f))
            Badge(stringResource(R.string.reward_badge), color = c.accent)
        }
        Spacer(Modifier.height(AppSpacing.xxs))
        Text(stringResource(R.string.reward_body), style = AppTypography.subheadline, color = c.textSecondary)
        Spacer(Modifier.height(AppSpacing.xs))
        // Direkt sichtbar, nicht im Kleingedruckten.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Icon(Icons.Rounded.Info, null, tint = c.textPrimary, modifier = Modifier.size(AppIconSize.small))
            Text(stringResource(R.string.reward_limit_note), style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
        }
        Spacer(Modifier.height(AppSpacing.s))
        when (offer) {
            RewardOffer.AVAILABLE -> GlassButton(stringResource(R.string.reward_cta), fillWidth = true, enabled = !busy, onClick = watch)
            RewardOffer.USED_TODAY -> Column(Modifier.fillMaxWidth().testTag("pro.reward.used"), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.reward_used), style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
                Text(stringResource(R.string.reward_tomorrow), style = AppTypography.footnote, color = c.textSecondary)
            }
            else -> Text(stringResource(R.string.reward_not_now), style = AppTypography.footnote, color = c.textSecondary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: Int, detail: Int) {
    val c = PM.colors
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(24.dp))
        Column {
            Text(stringResource(title), style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
            Text(stringResource(detail), style = AppTypography.caption, color = c.textSecondary)
        }
    }
}

