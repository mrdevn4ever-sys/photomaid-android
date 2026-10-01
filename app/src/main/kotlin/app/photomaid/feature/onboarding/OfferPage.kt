package app.photomaid.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.capabilities.Capabilities
import app.photomaid.core.capabilities.LimitBonus
import app.photomaid.design.AppRadii
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.PM
import app.photomaid.navigation.LocalApp

/** Lebende Wischkarte oben auf der Angebotsseite (wischt sich selbst, reagiert auf den Finger). */
@Composable
fun OfferHero(modifier: Modifier = Modifier) {
    val c = PM.colors
    val deck = rememberDemoDeck()
    Box(modifier.fillMaxWidth().height(204.dp).clip(RoundedCornerShape(AppRadii.hero)).background(c.warm).padding(vertical = AppSpacing.m),
        contentAlignment = Alignment.Center) {
        DemoCardStack(deck, Modifier.width(140.dp).height(176.dp), auto = true)
    }
}

/**
 * „Was du bekommst“ – ehrlich: kostenlos unbegrenzt wischen, etwas Werbung, Löschlimit pro Tag (+ Video-Bonus),
 * Pro einmalig. Zahlen kommen aus [Capabilities] und [LimitBonus], nie als Text im Code.
 */
@Composable
fun OfferBenefits(modifier: Modifier = Modifier, showFree: Boolean = true) {
    val c = PM.colors
    Column(modifier.fillMaxWidth().testTag("offer.benefits"), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        val rows = buildList {
            if (showFree) {
                add(Benefit(Icons.Rounded.AllInclusive, R.string.offer_free_title, R.string.offer_free_text, c.keep, null))
                add(Benefit(Icons.Rounded.SlowMotionVideo, R.string.offer_ads_title, R.string.offer_ads_text, c.later, null))
                add(Benefit(Icons.Rounded.Delete, R.string.offer_limit_title, R.string.offer_limit_text, c.cleanup,
                    Capabilities.FREE_DAILY_CLEANUP_LIMIT to LimitBonus.AMOUNT))
            }
            add(Benefit(Icons.Rounded.WorkspacePremium, R.string.offer_pro_title, R.string.offer_pro_text, c.accent, null))
        }
        rows.forEachIndexed { i, b -> BenefitRow(b, last = i == rows.lastIndex) }
    }
}

private data class Benefit(val icon: ImageVector, val title: Int, val text: Int, val tint: Color, val numbers: Pair<Int, Int>?)

/** Zeile im Stil eines ruhigen Zeitstrahls: Symbolkreis, Verbindungslinie, Titel + Text. */
@Composable
private fun BenefitRow(b: Benefit, last: Boolean) {
    val c = PM.colors
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(b.tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(b.icon, null, tint = b.tint, modifier = Modifier.size(18.dp))
            }
            if (!last) Box(Modifier.width(2.dp).height(30.dp).background(c.surfaceStroke))
        }
        Column(Modifier.padding(top = 6.dp, bottom = if (last) 0.dp else AppSpacing.s), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(b.title), style = AppTypography.headline, color = c.textPrimary)
            val text = b.numbers?.let { (limit, bonus) -> stringResource(b.text, limit, bonus) } ?: stringResource(b.text)
            Text(text, style = AppTypography.subheadline, color = c.textSecondary)
        }
    }
}

/**
 * Preiskarte: ausschließlich der Store-Preis. Ohne geladenes Produkt keine Zahl (keine erfundenen Preise).
 */
@Composable
fun OfferPriceCard(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val c = PM.colors
    val product by app.purchases.product.collectAsState()
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadii.card)).background(c.surface)
        .border(1.5.dp, c.accent.copy(alpha = 0.55f), RoundedCornerShape(AppRadii.card))
        .padding(horizontal = AppSpacing.m, vertical = AppSpacing.s).testTag("offer.price"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.pro_title), style = AppTypography.headline, color = c.textPrimary)
            Text(product?.let { stringResource(R.string.offer_price_once, it.localizedPrice) } ?: stringResource(R.string.offer_price_store),
                style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
        }
        Row(Modifier.clip(CircleShape).background(c.accentFill).padding(horizontal = AppSpacing.s, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.offer_no_sub), style = AppTypography.caption.copy(fontWeight = FontWeight.Bold), color = Color.White)
        }
    }
}

/** Kleiner, ehrlicher Hinweis unter den Knöpfen. */
@Composable
fun OfferFinePrint(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.offer_fine), modifier, style = AppTypography.caption, color = PM.colors.textSecondary, textAlign = TextAlign.Center)
}
