package app.photomaid.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.photomaid.R

/** Helle, luftige Grundfläche (Verlauf). Kein Präsentations-Wallpaper. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val c = PM.colors
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.background, c.backgroundTint)))) { content() }
}

/** Ruhige Inhaltsfläche (kein Glas), für Listen und Karten. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(AppSpacing.m),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = PM.colors
    val shape = RoundedCornerShape(AppRadii.card)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface, shape)
            .border(1.dp, c.surfaceStroke, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/**
 * Glas für schwebende Controls/Navigation (iOS: Liquid Glass `glassEffect`).
 * Android: transluzente Fläche + Lichtkante + dünne Kontur + weicher Schatten.
 * Bewusst **ohne** Echtzeit-Backdrop-Unschärfe (auf schwachen Geräten zu teuer beim Swipen).
 * Bei erhöhtem Kontrast deckend (iOS: Reduce Transparency → deckende Fläche).
 */
fun Modifier.glass(colors: PhotoMaidColors, shape: Shape, elevation: Dp = 6.dp): Modifier = this
    .shadow(elevation, shape, clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x22002A66))
    .clip(shape)
    .background(colors.glass, shape)
    .background(Brush.verticalGradient(listOf(colors.glassHighlight, Color.Transparent), endY = 60f), shape)
    .border(BorderStroke(1.dp, colors.glassStroke), shape)

@Composable
fun Modifier.glassCapsule(elevation: Dp = 6.dp): Modifier = glass(PM.colors, CircleShape, elevation)

@Composable
fun Modifier.glassRect(radius: Dp = AppRadii.card, elevation: Dp = 6.dp): Modifier =
    glass(PM.colors, RoundedCornerShape(radius), elevation)

/** Kreisförmiger Glas-Button für Kopfzeilen (Zurück, Schließen, Mehr, Einstellungen). */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = PM.colors.textPrimary,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(AppSpacing.minTouch)
            .alpha(if (enabled) 1f else 0.45f)
            .glassCapsule(elevation = 4.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
}

/** Glas-Button (iOS `.buttonStyle(.glass)`). */
@Composable
fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = PM.colors.accent,
    fillWidth: Boolean = false,
    large: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = if (large) 52.dp else AppSpacing.minTouch)
            .alpha(if (enabled) 1f else 0.45f)
            .glassCapsule(elevation = 3.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = AppSpacing.l, vertical = AppSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AppTypography.headline.copy(fontWeight = FontWeight.SemiBold), color = tint, textAlign = TextAlign.Center)
    }
}

/** Primäre Aktion (iOS `.buttonStyle(.glassProminent)`): Systemblau, weiße Schrift. */
@Composable
fun ProminentButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
    large: Boolean = false,
    color: Color = PM.colors.accent,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val shape = CircleShape
    Column(
        modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = if (large) 52.dp else AppSpacing.minTouch)
            .alpha(if (enabled) 1f else 0.45f)
            .shadow(6.dp, shape, clip = false, spotColor = color.copy(alpha = 0.5f))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.92f), color)), shape)
            .border(1.dp, Color.White.copy(alpha = 0.35f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = AppSpacing.l, vertical = AppSpacing.xs),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = AppTypography.headline, color = Color.White, textAlign = TextAlign.Center)
        if (subtitle != null) Text(subtitle, style = AppTypography.caption, color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center)
    }
}

/** Textbutton ohne Fläche (iOS Plain-Button in Listen). */
@Composable
fun PlainButton(text: String, modifier: Modifier = Modifier, color: Color = PM.colors.accent, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = AppSpacing.minTouch)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, style = AppTypography.body, color = color) }
}

/** Kopfzeile mit Titel, Untertitel und Glas-Buttons (Mockup-Stil, iOS `GlassToolbar`). */
@Composable
fun GlassToolbar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = PM.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.xxs),
        verticalAlignment = Alignment.Top,
    ) {
        Row(Modifier.sizeIn(minWidth = AppSpacing.minTouch, minHeight = AppSpacing.minTouch), content = leading)
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.padding(horizontal = AppSpacing.xs).semantics(mergeDescendants = true) { heading() },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = AppTypography.headline, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = AppTypography.caption, color = c.textSecondary, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.sizeIn(minWidth = AppSpacing.minTouch, minHeight = AppSpacing.minTouch),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), content = trailing,
        )
    }
}

/** Großer Titel der Tab-Wurzeln (Heute, Aufräumen, Ordnen). */
@Composable
fun LargeTitleHeader(title: String, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    val c = PM.colors
    Row(Modifier.fillMaxWidth().padding(top = AppSpacing.s), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }, verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
            Text(title, style = AppTypography.largeTitle, color = c.textPrimary)
            if (subtitle != null) Text(subtitle, style = AppTypography.subheadline, color = c.textSecondary)
        }
        trailing()
    }
}

/** Pushed Screen mit iOS-artiger Inline-Navigationsleiste (Zurück als Glas-Button). */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backIcon: ImageVector = Icons.AutoMirrored.Rounded.ArrowBackIos,
    backLabel: String = stringResource(R.string.back),
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    AppBackground(modifier) {
        Column(Modifier.fillMaxSize()) {
            GlassToolbar(
                title = title,
                modifier = Modifier.statusBarsPadding().padding(top = AppSpacing.xxs),
                leading = { if (onBack != null) GlassIconButton(backIcon, backLabel, onClick = onBack) },
                trailing = actions,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) { content(Modifier.fillMaxSize()) }
            bottomBar()
        }
    }
}

/** Zeile für Quellen/Kategorien: Symbol, Titel, Untertitel, Wert, Chevron (iOS `SourceRow`). */
@Composable
fun SourceRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    detail: String? = null,
    showChevron: Boolean = true,
) {
    val c = PM.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = AppSpacing.minTouch).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(AppRadii.small)).background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AppTypography.headline, color = c.textPrimary)
            if (subtitle != null) Text(subtitle, style = AppTypography.caption, color = c.textSecondary)
        }
        // Detail darf höchstens so breit werden wie der Titelbereich (schmale Geräte, große Schrift).
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (value != null) Text(value, style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
            if (detail != null) Text(detail, style = AppTypography.caption, color = c.textSecondary, textAlign = TextAlign.End)
        }
        if (showChevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
    }
}

/** Kennzahl mit ehrlicher Beschriftung („mind.“ bei unbekannten Größen). */
@Composable
fun MetricCard(value: String, caption: String, modifier: Modifier = Modifier, progress: Float? = null, footnote: String? = null) {
    val c = PM.colors
    SurfaceCard(modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Text(value, style = AppTypography.metric, color = c.textPrimary)
            Text(caption, style = AppTypography.subheadline, color = c.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (progress != null) {
            Spacer(Modifier.size(AppSpacing.xs))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().clip(CircleShape),
                color = c.accent, trackColor = c.backgroundTint, strokeCap = StrokeCap.Round, gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        if (footnote != null) {
            Spacer(Modifier.size(AppSpacing.xs))
            Text(footnote, style = AppTypography.caption, color = c.textSecondary)
        }
    }
}

/** Schmale Papierkorb-Leiste unten im Feed (iOS `ReviewDock`). */
@Composable
fun ReviewDock(count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = PM.colors
    val hint = stringResource(R.string.dock_hint)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .glassCapsule()
            .clickable(role = Role.Button, onClickLabel = hint, onClick = onClick)
            .padding(horizontal = AppSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s),
    ) {
        Icon(Icons.Rounded.Delete, null, tint = c.cleanup)
        Text(stringResource(R.string.dock_count, count), style = AppTypography.subheadline.copy(fontWeight = FontWeight.Medium), color = c.textPrimary)
        Spacer(Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.textPrimary)
    }
}

/** Leerer Zustand (iOS `ContentUnavailableView`). */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionTitle: String? = null,
    action: (() -> Unit)? = null,
) {
    val c = PM.colors
    Column(
        modifier.fillMaxWidth().padding(AppSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s),
    ) {
        Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(52.dp))
        Text(title, style = AppTypography.title, color = c.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(message, style = AppTypography.subheadline, color = c.textSecondary, textAlign = TextAlign.Center)
        if (actionTitle != null && action != null) ProminentButton(actionTitle, onClick = action)
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, retry: (() -> Unit)? = null) {
    EmptyState(
        Icons.Rounded.WarningAmber, stringResource(R.string.error_title), message, modifier,
        actionTitle = retry?.let { stringResource(R.string.retry) }, action = retry,
    )
}

/** Segmentierte Auswahl (iOS `.pickerStyle(.segmented)`). */
@Composable
fun <T> SegmentedPicker(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val c = PM.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.backgroundTint.copy(alpha = 0.9f)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((value, label) in options) {
            val isSel = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (isSel) Modifier.background(c.surface.copy(alpha = 1f)).border(0.5.dp, c.surfaceStroke, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .semantics { this.selected = isSel }
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = AppTypography.footnote.copy(fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal),
                    color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Abschnitt im iOS-„insetGrouped“-Listenstil: Überschrift, Fläche mit Trennern, Fußnote. */
@Composable
fun ListSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    plain: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = PM.colors
    Column(modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header, style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary,
                modifier = Modifier.padding(start = AppSpacing.m, bottom = AppSpacing.xxs, top = AppSpacing.xs).semantics { heading() },
            )
        }
        if (plain) Column(content = content)
        else SurfaceCard(padding = PaddingValues(horizontal = AppSpacing.m, vertical = AppSpacing.xxs), content = content)
        if (footer != null) {
            Text(footer, style = AppTypography.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = AppSpacing.m, vertical = AppSpacing.xxs))
        }
    }
}

@Composable
fun RowDivider() = HorizontalDivider(thickness = 0.5.dp, color = PM.colors.textSecondary.copy(alpha = 0.22f))

/** Kleines Abzeichen (z. B. „Pro“). */
@Composable
fun Badge(text: String, color: Color = PM.colors.warning) {
    Text(
        text, style = AppTypography.caption.copy(fontWeight = FontWeight.Bold), color = PM.colors.textPrimary,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.2f)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = PM.colors.textSecondary, align: TextAlign? = null, style: TextStyle = AppTypography.caption) =
    Text(text, modifier = modifier, style = style, color = color, textAlign = align)

/** Kachel der Gestenlegende (Einstellungen › So wischst du). */
@Composable
fun SwipeTile(icon: ImageVector, dir: String, meaning: String, tint: Color, modifier: Modifier = Modifier) {
    val c = PM.colors
    Column(modifier.clearAndSetSemantics { contentDescription = "$dir: $meaning" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(48.dp).glassRect(AppRadii.small, 3.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Text(dir, style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, textAlign = TextAlign.Center)
        Text(meaning, style = AppTypography.caption2, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 72.dp))
    }
}

@Composable
fun FixedWidth(width: Dp, content: @Composable () -> Unit) = Box(Modifier.width(width)) { content() }

/** Ganze Zeile als Schaltfläche (iOS `Button { } label: { SourceRow }`). */
fun Modifier.clickableRow(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(enabled = enabled, role = Role.Button, onClick = onClick)

/** Auswahlzeile mit Menü (iOS `Picker` im Menüstil). */
@Composable
fun <T> MenuRowPicker(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val c = PM.colors
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.minTouch).clickable(role = Role.DropdownList) { open = true },
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = AppTypography.body, color = c.textPrimary, modifier = Modifier.weight(1f))
            Text(options.firstOrNull { it.first == selected }?.second.orEmpty() + " \u2304", style = AppTypography.body, color = c.accent)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }, modifier = Modifier.align(Alignment.CenterEnd)) {
            for ((v, label) in options) androidx.compose.material3.DropdownMenuItem({ Text(label) }, onClick = { open = false; onSelect(v) })
        }
    }
}
