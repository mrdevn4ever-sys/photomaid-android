package app.photomaid.feature.pro

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
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Search
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
import app.photomaid.design.glassRect
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.system.SystemDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen 14 – PhotoMaid Pro. Einmalkauf, echter Storepreis, Wiederherstellen, freiwillige
 * Rewarded-Vorschau (30 Minuten, 1× pro Tag, Tageslimit bleibt 15).
 */
@Composable
fun ProScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val ctx = LocalContext.current
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
    now; rewards
    val caps = app.capabilities

    AppBackground {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.s).padding(top = AppSpacing.xl)) {
                TextButton(nav::back) { Text(stringResource(R.string.close)) }
            }
            Column(Modifier.padding(horizontal = AppSpacing.pageMargin), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.l)) {
                Box(Modifier.glassRect().padding(AppSpacing.l)) { Icon(Icons.Rounded.WorkspacePremium, null, tint = c.accent, modifier = Modifier.size(48.dp)) }
                Text(stringResource(R.string.pro_title), style = AppTypography.largeTitle, color = c.textPrimary)
                Text(stringResource(R.string.pro_subtitle), style = AppTypography.subheadline, color = c.textSecondary)
                SurfaceCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
                        Feature(Icons.Rounded.AllInclusive, R.string.pro_f1, R.string.pro_f1d)
                        Feature(Icons.Rounded.Delete, R.string.pro_f2, R.string.pro_f2d)
                        Feature(Icons.Rounded.Search, R.string.pro_f3, R.string.pro_f3d)
                        Feature(Icons.Rounded.GridView, R.string.pro_f4, R.string.pro_f4d)
                        Feature(Icons.Rounded.SettingsSuggest, R.string.pro_f5, R.string.pro_f5d)
                        Feature(Icons.Rounded.Block, R.string.pro_f6, R.string.pro_f6d)
                    }
                }
                // Kaufbereich
                when (state) {
                    PurchaseState.PURCHASED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Icon(Icons.Rounded.Verified, null, tint = c.success)
                        Text(stringResource(R.string.pro_active), style = AppTypography.headline, color = c.success)
                    }
                    PurchaseState.PENDING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Icon(Icons.Rounded.HourglassTop, null, tint = c.textSecondary)
                        Text(stringResource(R.string.pro_pending), style = AppTypography.subheadline, color = c.textPrimary)
                    }
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                        val p = product
                        if (purchases.isConfigured && p != null) {
                            ProminentButton(stringResource(R.string.pro_unlock), fillWidth = true, large = true, enabled = !busy,
                                subtitle = stringResource(R.string.pro_price_once, p.localizedPrice)) {
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
                            // Ohne echte Storekonfiguration kein erfundener Preis und kein toter Button.
                            Text(stringResource(if (purchases.isConfigured) R.string.pro_price_loading else R.string.pro_unavailable),
                                style = AppTypography.subheadline, color = c.textSecondary, textAlign = TextAlign.Center)
                        }
                        if (purchases.isConfigured) GlassButton(stringResource(R.string.pro_restore), enabled = !busy) {
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
                // Rewarded-Bereich
                val remaining = caps.rewardRemaining
                if (caps.tier == Capabilities.Tier.REWARDED_PREVIEW && remaining != null) {
                    SurfaceCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                            Icon(Icons.Rounded.Timer, null, tint = c.accent)
                            Text(stringResource(R.string.pro_preview_active, (remaining.seconds / 60 + 1).toInt()), style = AppTypography.body, color = c.textPrimary)
                        }
                    }
                } else if (state != PurchaseState.PURCHASED) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        GlassButton(stringResource(R.string.pro_reward_cta), fillWidth = true, enabled = app.entitlements.canOfferReward && !busy) {
                            scope.launch {
                                busy = true
                                message = when (app.entitlements.watchRewardedAd { app.player.stop() }) {
                                    is RewardedAdResult.Rewarded -> res.getString(R.string.pro_msg_rewarded)
                                    RewardedAdResult.DismissedWithoutReward -> res.getString(R.string.pro_msg_dismissed)
                                    RewardedAdResult.NoFill -> res.getString(R.string.pro_msg_nofill)
                                    is RewardedAdResult.Failed, RewardedAdResult.NotConfigured -> res.getString(R.string.pro_msg_ad_unavailable)
                                }
                                busy = false
                            }
                        }
                        Text(stringResource(R.string.pro_reward_sub), style = AppTypography.caption, color = c.textSecondary, textAlign = TextAlign.Center)
                        if (!app.entitlements.ads.isConfigured) Text(stringResource(R.string.pro_reward_unavailable), style = AppTypography.caption, color = c.textSecondary, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.pro_reward_limit), style = AppTypography.caption, color = c.textSecondary, textAlign = TextAlign.Center)
                    }
                }
                Text(stringResource(R.string.pro_privacy), style = AppTypography.caption, color = c.textSecondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(AppSpacing.l))
            }
        }
    }
    NoticeDialog(message) { message = null }
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
