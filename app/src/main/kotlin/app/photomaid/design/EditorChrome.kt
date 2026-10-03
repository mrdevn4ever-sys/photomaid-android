package app.photomaid.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

// =====================================================================================================================
// Editorfamilie (Redesign 2026-10): gemeinsame Farben, eine Layout-Policy, ein Gerüst. Foto- und Video-Editor, Collage,
// Film und Werkzeug-Vorschau benutzen dieselbe Geometrie. Werte sind PhotoMaid-Vorgaben, keine Apple-/Google-Werte.
// =====================================================================================================================

/** Ruhige, neutrale Arbeitsfarben. Akzent = bestehender PhotoMaid-Akzent (nur Auswahl, Primäraktion, aktive Werte). */
@Immutable
data class EditorPalette(
    val background: Color, val surface: Color, val surfaceRaised: Color, val stroke: Color,
    val text: Color, val textSecondary: Color, val stage: Color,
) {
    companion object {
        val Dark = EditorPalette(background = Color(0xFF101113), surface = Color(0xFF1C1D21), surfaceRaised = Color(0xFF2A2B30),
            stroke = Color(0xFF34363C), text = Color(0xFFF7F7F8), textSecondary = Color(0xFFB8BBC2), stage = Color(0xFF08090A))
        val Light = EditorPalette(background = Color(0xFFF5F5F7), surface = Color(0xFFFFFFFF), surfaceRaised = Color(0xFFE9E9ED),
            stroke = Color(0xFFD9DADF), text = Color(0xFF1D1D1F), textSecondary = Color(0xFF62656D), stage = Color(0xFFE6E6EA))
    }
}

val LocalEditorPalette = compositionLocalOf { EditorPalette.Light }

/** Nur für Tests/Vorschauen: Tastaturhöhe vorgeben (Robolectric zeigt keine echte Tastatur). `null` = echte IME-Insets. */
val LocalEditorImeOverride = compositionLocalOf<Dp?> { null }

/**
 * Editor-Farben für den ganzen Inhalt: die bestehenden Panels lesen weiter [PM.colors] – hier werden nur Grund-, Flächen-
 * und Textrollen durch die ruhige Editorpalette ersetzt. Akzent und Signalfarben bleiben PhotoMaid.
 */
@Composable
fun EditorTheme(content: @Composable () -> Unit) {
    val base = PM.colors
    val p = if (base.isDark) EditorPalette.Dark else EditorPalette.Light
    val colors = remember(base, p) {
        base.copy(background = p.background, backgroundTint = p.surfaceRaised, surface = p.surface, surfaceStroke = p.stroke,
            textPrimary = p.text, textSecondary = p.textSecondary, glass = p.surface, glassBar = p.surface, glassStroke = p.stroke)
    }
    CompositionLocalProvider(LocalPhotoMaidColors provides colors, LocalEditorPalette provides p, content = content)
}

enum class EditorMode { STACKED, SIDE }

/** Ergebnis der Policy (alles in dp, aus Fenstergröße, Schrift und Tastatur – nie aus dem Medium). */
@Immutable
data class EditorLayout(
    val mode: EditorMode, val header: Dp, val actions: Dp, val tool: Dp, val dock: Dp,
    val stageWidth: Dp, val stageHeight: Dp, val inspectorWidth: Dp, val keyboard: Boolean,
)

object EditorLayoutPolicy {
    /** Kopfzeile: Abbrechen/Speichern, wächst mit der Schrift. */
    fun header(fontScale: Float): Dp = 56.dp + 16.dp * (fontScale.coerceIn(1f, 2f) - 1f)
    /** Hilfsaktionen (Rückgängig, Vergleich, Wiedergabe …): eine Zeile mit 48-dp-Zielen. */
    val actions: Dp = 52.dp
    /** Werkzeugnavigation: Symbol + eine Zeile Beschriftung. */
    fun dock(fontScale: Float): Dp = 56.dp + 16.dp * fontScale.coerceIn(1f, 2f)
    val minStage: Dp = 160.dp

    /**
     * Teilt den verfügbaren Raum (nach System-Insets) auf. Gleiche Eingaben → gleiche Geometrie; das Medium spielt keine
     * Rolle. SIDE: breite, niedrige Fenster (Bühne links, Werkzeuge seitlich). Tastatur offen: Bühne und Werkzeugzone
     * rücken über die Tastatur, Hilfsaktionen und Navigation ruhen.
     */
    fun compute(width: Dp, height: Dp, fontScale: Float, ime: Dp = 0.dp, hasActions: Boolean = true, hasDock: Boolean = true): EditorLayout {
        val fs = fontScale.coerceIn(1f, 2f)
        val head = header(fs)
        val keyboard = ime > 0.dp
        val avail = max(0.dp, height - ime)
        val side = !keyboard && width >= 560.dp && width > height * 1.2f
        if (side) {
            val inspector = (width * 0.42f).coerceIn(340.dp, 460.dp)
            val stageW = width - inspector
            val bodyH = avail - head
            val dock = if (hasDock) dock(fs) else 0.dp
            val act = if (hasActions) actions else 0.dp
            // Querformat: Hilfsleiste unter der Bühne (linke Spalte), rechts nur Werkzeugzone + Navigation – so bleibt die
            // zentrale Bedienung auch in niedrigen Fenstern vollständig sichtbar. Bühne fest je Fenster, nie je Werkzeug.
            val tool = max(0.dp, bodyH - dock)
            return EditorLayout(EditorMode.SIDE, head, act, tool, dock, stageW, max(minStage, bodyH - act), inspector, false)
        }
        val act = if (hasActions && !keyboard) actions else 0.dp
        val dock = if (hasDock && !keyboard) dock(fs) else 0.dp
        val bodyH = avail - head - act - dock
        // Werkzeugzone: fester Anteil je Fenster (scrollt innen), nie abhängig vom Reiter.
        var tool = (avail * 0.31f).coerceIn(140.dp, 260.dp) + 40.dp * (fs - 1f)
        if (keyboard) tool = min(tool, 168.dp + 40.dp * (fs - 1f))
        var stage = bodyH - tool
        if (stage < minStage) { tool = max(96.dp, bodyH - minStage); stage = bodyH - tool }
        // Sehr wenig Platz (z. B. Querformat mit Tastatur): Eingabe/Werkzeug hat Vorrang, die Bühne weicht vorübergehend.
        tool = min(tool, max(0.dp, bodyH)); stage = max(0.dp, bodyH - tool)
        // Tastatur und zu wenig Platz für eine brauchbare Bühne: Eingabe bekommt alles, Bühne kehrt beim Schließen zurück.
        if (keyboard && stage < minStage) { tool = max(0.dp, bodyH); stage = 0.dp }
        return EditorLayout(EditorMode.STACKED, head, act, tool, dock, width, stage, width, keyboard)
    }
}

/**
 * Gemeinsames Editor-Gerüst: Kopfzeile · feste Bühne · Hilfsaktionen · Werkzeugzone · Werkzeugnavigation.
 * Die Bühne ist ein neutraler Rahmen ohne Rundung/Schatten; ihr Inhalt passt das Medium selbst ein (nie gestreckt).
 * Nichts Bedienbares liegt auf der Bühne – Aktionen, Werte und Zeitleisten gehören in [actions]/[tool].
 */
@Composable
fun EditorScaffold(
    header: @Composable () -> Unit,
    stage: @Composable BoxScope.() -> Unit,
    tool: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    dock: (@Composable () -> Unit)? = null,
    toolScrolls: Boolean = true,
    stageTag: String = "media.stage",
) {
    val p = LocalEditorPalette.current
    val density = LocalDensity.current
    val imeOverride = LocalEditorImeOverride.current
    val imePx = WindowInsets.ime.getBottom(density)
    Box(modifier.fillMaxSize().background(p.background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Unten: Navigationsleiste ODER Tastatur (die Tastatur deckt die Navigationsleiste ab).
            val navBottom = with(density) { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).getBottom(this).toDp() }
            val ime = imeOverride ?: with(density) { imePx.toDp() }
            val bottomInset = if (ime > 0.dp) ime else navBottom
            val layout = EditorLayoutPolicy.compute(maxWidth, if (ime > 0.dp) maxHeight else maxHeight - navBottom, density.fontScale,
                ime = ime, hasActions = actions != null, hasDock = dock != null)
            val toolBox: @Composable (Modifier) -> Unit = { m ->
                Box(m.testTag("editor.toolzone")) {
                    Column(Modifier.fillMaxSize().then(if (toolScrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(start = AppSpacing.m, end = AppSpacing.m, top = AppSpacing.xs, bottom = AppSpacing.xl),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) { tool() }
                }
            }
            Column(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
                Box(Modifier.fillMaxWidth().height(layout.header).testTag("editor.header"), contentAlignment = Alignment.Center) { header() }
                if (layout.mode == EditorMode.SIDE) {
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(layout.stageWidth).fillMaxHeight()) {
                            Box(Modifier.fillMaxWidth().height(layout.stageHeight).background(p.stage).testTag(stageTag), contentAlignment = Alignment.Center, content = stage)
                            if (actions != null) Box(Modifier.fillMaxWidth().background(p.surface)) { EditorActionRow(Modifier.height(layout.actions), actions) }
                        }
                        Column(Modifier.width(layout.inspectorWidth).fillMaxHeight().background(p.surface).testTag("editor.inspector")) {
                            toolBox(Modifier.fillMaxWidth().height(layout.tool))
                            if (dock != null) Box(Modifier.fillMaxWidth().height(layout.dock), contentAlignment = Alignment.Center) { dock() }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(layout.stageHeight).background(p.stage).testTag(stageTag), contentAlignment = Alignment.Center, content = stage)
                    Column(Modifier.fillMaxWidth().weight(1f).background(p.surface)) {
                        if (actions != null && !layout.keyboard) EditorActionRow(Modifier.height(layout.actions), actions)
                        toolBox(Modifier.fillMaxWidth().height(layout.tool))
                        if (dock != null && !layout.keyboard) Box(Modifier.fillMaxWidth().height(layout.dock), contentAlignment = Alignment.Center) { dock() }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorActionRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    val p = LocalEditorPalette.current
    Column(modifier.fillMaxWidth()) {
        // Natürliche Breiten; passt alles, verteilt sich die Zeile, sonst scrollt sie (bei großer Schrift nichts abgeschnitten).
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = AppSpacing.xs), contentAlignment = Alignment.CenterStart) {
            Row(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth).testTag("editor.actions"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, content = content)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.stroke))
    }
}

/** Kopfzeile: [leading] (Abbrechen/Zurück) · optionaler Titel (entfällt zuerst) · [trailing] (Speichern). */
@Composable
fun EditorTopBar(leading: @Composable () -> Unit, trailing: @Composable () -> Unit, title: String? = null) {
    val p = LocalEditorPalette.current
    Row(Modifier.fillMaxSize().padding(horizontal = AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Box(Modifier.weight(1f).padding(horizontal = AppSpacing.xs), contentAlignment = Alignment.Center) {
            if (title != null) Text(title, style = AppTypography.headline, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Symbol-Aktion der Hilfsleiste: 22 dp gezeichnet, 48 × 48 dp bedienbar; aktiver Zustand nicht nur über Farbe (Fläche + Semantik). */
@Composable
fun EditorActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, active: Boolean? = null, onClick: () -> Unit) {
    val p = LocalEditorPalette.current
    val c = PM.colors
    val on = active == true
    val bg by animateColorAsState(if (on) c.accent.copy(alpha = 0.16f) else Color.Transparent, tween(160), label = "act")
    Box(modifier.size(AppSpacing.minTouch).clip(CircleShape).background(bg)
        .clickable(enabled = enabled, role = if (active != null) Role.Switch else Role.Button, onClick = onClick)
        .semantics { contentDescription = label; if (active != null) toggleableState = androidx.compose.ui.state.ToggleableState(on) },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = when { !enabled -> p.textSecondary.copy(alpha = 0.38f); on -> c.accent; else -> p.text }, modifier = Modifier.size(22.dp))
    }
}

/**
 * Halten = vorübergehend Original zeigen (Vergleich); TalkBack/Tastatur: Antippen schaltet um. Kein Konflikt mit
 * Zeichnen/Zuschneiden, weil die Taste außerhalb der Bühne liegt.
 */
@Composable
fun EditorHoldButton(icon: ImageVector, label: String, holding: Boolean, onHold: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = LocalEditorPalette.current
    val c = PM.colors
    Box(modifier.size(AppSpacing.minTouch).clip(CircleShape).background(if (holding) c.accent.copy(alpha = 0.16f) else Color.Transparent)
        .then(if (enabled) Modifier.pointerInput(Unit) { detectTapGestures(onPress = { onHold(true); tryAwaitRelease(); onHold(false) }) } else Modifier)
        .semantics { contentDescription = label; role = Role.Switch; toggleableState = androidx.compose.ui.state.ToggleableState(holding); if (enabled) onClick { onHold(!holding); true } },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (!enabled) p.textSecondary.copy(alpha = 0.38f) else if (holding) c.accent else p.text, modifier = Modifier.size(22.dp))
    }
}

/** Ein Eintrag der Werkzeugnavigation. */
@Immutable
data class DockItem(val key: String, val icon: ImageVector, val label: String, val tag: String, val badge: ImageVector? = null)

/**
 * Werkzeugnavigation: eine dezente, deckende Fläche mit feinem Rand (kein Blur über dem Medium). Alle Einträge haben
 * denselben Textstil; passt es nicht, scrollt die Leiste waagrecht – nie wird Schrift einzeln verkleinert.
 */
@Composable
fun EditorDock(items: List<DockItem>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalEditorPalette.current
    val c = PM.colors
    val scroll = rememberScrollState()
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.stroke))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xxs)) {
            // Natürliche Breiten, gleicher Textstil. Passt alles (Normalfall: höchstens fünf Einträge), verteilt sich die Leiste;
            // bei großer Schrift scrollt sie – und bringt den aktiven Eintrag immer ganz ins Bild.
            Row(Modifier.horizontalScroll(scroll).widthIn(min = maxWidth), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                for (it in items) {
                    val sel = it.key == selected
                    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
                    androidx.compose.runtime.LaunchedEffect(sel) { if (sel) runCatching { bring.bringIntoView() } }
                    val fg by animateColorAsState(if (sel) c.accent else p.textSecondary, tween(160), label = "dock")
                    Column(Modifier.bringIntoViewRequester(bring).widthIn(min = 64.dp).heightIn(min = AppSpacing.minTouch).clip(RoundedCornerShape(14.dp))
                        .clickable(role = Role.Tab) { onSelect(it.key) }.semantics(mergeDescendants = true) { this.selected = sel }
                        .testTag(it.tag).padding(horizontal = AppSpacing.xs, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box {
                            Icon(it.icon, null, tint = fg, modifier = Modifier.size(22.dp))
                            if (it.badge != null) Icon(it.badge, null, tint = c.accent, modifier = Modifier.align(Alignment.TopEnd).padding(start = 14.dp).size(11.dp))
                        }
                        Text(it.label, style = AppTypography.caption.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium),
                            color = if (sel) c.accent else p.text, maxLines = 1, softWrap = false)
                        // Aktiv: kleiner Balken (nicht nur Farbe).
                        Box(Modifier.size(width = 16.dp, height = 2.dp).clip(CircleShape).background(if (sel) c.accent else Color.Transparent))
                    }
                }
            }
        }
    }
}

/**
 * Einzelwerkzeug (z. B. Belichtung, Stift): ein einheitlicher Stil für alle Werkzeugreihen. Ruhig = feiner Ring;
 * aktiv = Akzentring, Akzentsymbol und Akzentbeschriftung (Zustand auch über Semantik, nicht nur Farbe); Punkt = geändert.
 */
@Composable
fun EditorToolButton(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier = Modifier, changed: Boolean = false,
                     changedLabel: String? = null, iconModifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalEditorPalette.current
    val c = PM.colors
    Column(modifier.widthIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.RadioButton, onClick = onClick)
        .semantics(mergeDescendants = true) { this.selected = selected; if (changed && changedLabel != null) stateDescription = changedLabel }
        .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(if (selected) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                .border(if (selected) 1.5.dp else 1.dp, if (selected) c.accent else p.stroke, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (selected) c.accent else p.text, modifier = iconModifier.size(22.dp))
            }
            if (changed) Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(p.surface).padding(1.dp).clip(CircleShape).background(c.accent))
        }
        Text(label, style = AppTypography.caption.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) c.accent else p.textSecondary, maxLines = 1, softWrap = false)
    }
}

/** Ruhige Textreiter (Unterebene, z. B. Licht/Farbe/Detail oder die Werkzeuge unter „Mehr“): aktiv = Akzenttext + Unterstrich. */
@Composable
fun EditorSubTabs(items: List<Triple<String, String, Boolean>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier, tagPrefix: String = "sub.") {
    val p = LocalEditorPalette.current
    val c = PM.colors
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
        for ((key, label, dot) in items) {
            val sel = key == selected
            val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
            androidx.compose.runtime.LaunchedEffect(sel) { if (sel) runCatching { bring.bringIntoView() } }
            Column(Modifier.bringIntoViewRequester(bring).heightIn(min = AppSpacing.minTouch).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Tab) { onSelect(key) }
                .semantics(mergeDescendants = true) { this.selected = sel }.testTag(tagPrefix + key).padding(horizontal = AppSpacing.s),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = AppTypography.subheadline.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (sel) p.text else p.textSecondary, maxLines = 1, softWrap = false)
                    if (dot) Box(Modifier.size(5.dp).clip(CircleShape).background(c.accent))
                }
                Box(Modifier.padding(top = 4.dp).size(width = 20.dp, height = 2.dp).clip(CircleShape).background(if (sel) c.accent else Color.Transparent))
            }
        }
    }
}

/** Ganze Breite, flach (kein Verlauf/Glanz): primär = Akzentfläche, sekundär = Akzenttext. */
@Composable
fun EditorWideButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val c = PM.colors
    Box(modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp))
        .background(if (primary) (if (enabled) c.accentFill else c.accentFill.copy(alpha = 0.38f)) else Color.Transparent)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = AppSpacing.m, vertical = AppSpacing.xs),
        contentAlignment = Alignment.Center) {
        Text(text, style = AppTypography.headline, color = if (primary) Color.White else c.accent, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Kurzer Status in der Hilfsleiste (z. B. „1200 × 900“, „Original“, „0:12 / 0:32“) – nimmt den restlichen Platz, Ziffern gleich breit. */
@Composable
fun EditorStatus(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    val p = LocalEditorPalette.current
    Text(text, modifier.padding(horizontal = AppSpacing.xs), style = AppTypography.footnote.copy(
        fontFeatureSettings = "tnum", fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal),
        color = if (emphasized) p.text else p.textSecondary, maxLines = 1, softWrap = false,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/** Primäre Aktion der Kopfzeile („Speichern“): eine Zeile, nie gequetscht (Titel weicht zuerst). */
@Composable
fun EditorPrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = PM.colors
    val p = LocalEditorPalette.current
    Box(modifier.heightIn(min = AppSpacing.minTouch).clip(CircleShape).background(if (enabled) c.accentFill else p.surfaceRaised)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = AppSpacing.m), contentAlignment = Alignment.Center) {
        Text(text, style = AppTypography.headline, color = if (enabled) Color.White else p.textSecondary, maxLines = 1, softWrap = false)
    }
}


/**
 * Speichern/Export als eigener Schritt: ganze Ansicht (kein Blatt über dem Werkstück), Zurück oben links und per
 * Android-Zurück. Inhalt scrollt; die Tastatur schiebt ihn hoch.
 */
@Composable
fun EditorExportPage(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val p = LocalEditorPalette.current
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(p.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.fillMaxWidth().height(EditorLayoutPolicy.header(LocalDensity.current.fontScale))) {
            EditorTopBar(leading = {
                GlassIconButton(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, androidx.compose.ui.res.stringResource(app.photomaid.R.string.back),
                    Modifier.testTag("export.back"), quiet = true, onClick = onBack)
            }, trailing = { Box(Modifier.size(AppSpacing.minTouch)) }, title = title)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.m).padding(bottom = AppSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s), content = content)
    }
}
