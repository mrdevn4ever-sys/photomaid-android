package app.photomaid.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.billing.PurchaseResult
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.design.AppBackground
import app.photomaid.design.AppRadii
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.GlassButton
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.ProminentButton
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.navigation.LocalApp
import app.photomaid.system.SystemDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class OnbStep { STORY, TRY, SAFE, OFFER, ACCESS }

/** Aktuelle Version des Onboardings (siehe `AppSettingsData.onboardingVersion`). */
const val ONBOARDING_VERSION = 1

/**
 * Erststart: Geschichte → selbst wischen → Sicherheit → Angebot → Berechtigungen (docs/ONBOARDING_PLAN.md).
 * Seitenwechsel als kurzes Überblenden (150 ms), Zurück-Taste = vorige Seite. Lifetime-Pro überspringt das Angebot.
 */
@Composable
fun OnboardingFlow(onDone: () -> Unit) {
    val app = LocalApp.current
    val purchase by app.purchases.state.collectAsState()
    var step by rememberSaveable { mutableStateOf(OnbStep.STORY) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { app.purchases.refresh() } } }
    fun go(s: OnbStep) { step = if (s == OnbStep.OFFER && purchase == PurchaseState.PURCHASED) OnbStep.ACCESS else s }
    BackHandler(enabled = step != OnbStep.STORY) {
        step = OnbStep.entries[step.ordinal - 1].let { if (it == OnbStep.OFFER && purchase == PurchaseState.PURCHASED) OnbStep.SAFE else it }
    }
    Crossfade(step, animationSpec = tween(150), label = "onboarding") { s ->
        when (s) {
            OnbStep.STORY -> StoryPage(0, R.string.onb_story_title, R.string.onb_story_text, onSkip = { go(OnbStep.OFFER) }, onNext = { go(OnbStep.TRY) }) {
                GridStoryScene(Modifier.widthIn(max = 320.dp).aspectRatio(1f, matchHeightConstraintsFirst = true))
            }
            OnbStep.TRY -> {
                val deck = rememberDemoDeck()
                val allDone = deck.done.size >= 3
                StoryPage(1, R.string.onb_try_title, if (allDone) R.string.onb_try_done else R.string.onb_try_text,
                    onSkip = { go(OnbStep.OFFER) }, onNext = { go(OnbStep.SAFE) },
                    below = { DemoChecklist(deck, Modifier.fillMaxWidth()) }) {
                    DemoCardStack(deck, Modifier.widthIn(max = 280.dp).aspectRatio(0.78f, matchHeightConstraintsFirst = true))
                }
            }
            OnbStep.SAFE -> StoryPage(2, R.string.onb_safe_title, R.string.onb_safe_text, onSkip = { go(OnbStep.OFFER) }, onNext = { go(OnbStep.OFFER) },
                below = { LocalLine() }) {
                SafetyStoryScene(Modifier.widthIn(max = 340.dp).aspectRatio(0.95f, matchHeightConstraintsFirst = true))
            }
            OnbStep.OFFER -> OfferStep(onFree = { go(OnbStep.ACCESS) }, onPurchased = { go(OnbStep.ACCESS) })
            OnbStep.ACCESS -> PrivacyAccessScreen(onboarding = true, onDone = onDone)
        }
    }
}

/** Gemeinsamer Rahmen der Erklärseiten: Punkte + Überspringen, Bild, Titel, Text, „Weiter“. */
@Composable
private fun StoryPage(index: Int, title: Int, text: Int, onSkip: (() -> Unit)?, onNext: () -> Unit,
                      below: @Composable () -> Unit = {}, visual: @Composable BoxScope.() -> Unit) {
    val c = PM.colors
    AppBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin).testTag("onb.page.$index"),
            horizontalAlignment = Alignment.CenterHorizontally) {
            TopRow(index, onSkip)
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = AppSpacing.m), contentAlignment = Alignment.Center, content = visual)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(max = 320.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                Text(stringResource(title), style = AppTypography.display, color = c.textPrimary, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(text), style = AppTypography.body, color = c.textSecondary, textAlign = TextAlign.Center)
                below()
            }
            Spacer(Modifier.height(AppSpacing.l))
            ProminentButton(stringResource(R.string.onb_next), Modifier.testTag("onb.next"), fillWidth = true, large = true, onClick = onNext)
            Spacer(Modifier.height(AppSpacing.s))
        }
    }
}

@Composable
private fun TopRow(index: Int, onSkip: (() -> Unit)?) {
    val c = PM.colors
    val label = stringResource(R.string.onb_progress, index + 1, 4)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.semantics(mergeDescendants = true) { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until 4) Box(Modifier.height(6.dp).width(if (i == index) 22.dp else 6.dp).clip(CircleShape)
                .background(if (i == index) c.accent else c.surfaceStroke))
        }
        Spacer(Modifier.weight(1f))
        if (onSkip != null) PlainButton(stringResource(R.string.onb_skip), Modifier.testTag("onb.skip"), color = c.textSecondary, onClick = onSkip)
    }
}

@Composable
private fun LocalLine() {
    val c = PM.colors
    Row(Modifier.clip(RoundedCornerShape(AppRadii.small)).background(c.warm).padding(horizontal = AppSpacing.s, vertical = AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Icon(Icons.Rounded.Lock, null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.onb_safe_local), style = AppTypography.footnote, color = c.textPrimary)
    }
}

/** O4 – Angebot: kostenlos unbegrenzt wischen (mit etwas Werbung) oder Pro einmalig zum Store-Preis. */
@Composable
private fun OfferStep(onFree: () -> Unit, onPurchased: () -> Unit) {
    val app = LocalApp.current
    val c = PM.colors
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val product by app.purchases.product.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var thanks by remember { mutableStateOf(false) }
    AppBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().testTag("onb.page.3")) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.l)) {
                TopRow(3, onSkip = null)
                OfferHero()
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text(stringResource(R.string.onb_offer_title), style = AppTypography.display, color = c.textPrimary, textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.onb_offer_text), style = AppTypography.body, color = c.textSecondary, textAlign = TextAlign.Center)
                }
                OfferBenefits()
                if (app.purchases.isConfigured) OfferPriceCard()
            }
            Column(Modifier.padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.xs), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                ProminentButton(stringResource(R.string.onb_free_start), Modifier.testTag("offer.free"), fillWidth = true, large = true, enabled = !busy, onClick = onFree)
                if (app.purchases.isConfigured) GlassButton(product?.let { stringResource(R.string.onb_buy, it.localizedPrice) } ?: stringResource(R.string.onb_buy_noprice),
                    Modifier.testTag("offer.buy"), fillWidth = true, large = true, enabled = !busy && product != null) {
                    val activity = SystemDialogs.activity ?: return@GlassButton
                    scope.launch {
                        busy = true
                        when (app.purchasePro(activity)) {
                            PurchaseResult.Purchased -> { thanks = true; message = res.getString(R.string.offer_thanks) }
                            PurchaseResult.Pending -> message = res.getString(R.string.pro_msg_pending)
                            PurchaseResult.Cancelled -> Unit
                            is PurchaseResult.Failed -> message = res.getString(R.string.pro_msg_failed)
                            PurchaseResult.NotConfigured -> message = res.getString(R.string.pro_unavailable)
                        }
                        app.reload()
                        busy = false
                    }
                }
                OfferFinePrint()
            }
        }
    }
    NoticeDialog(message) { message = null; if (thanks) onPurchased() }
}
