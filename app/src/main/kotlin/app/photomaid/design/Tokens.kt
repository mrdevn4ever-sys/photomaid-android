package app.photomaid.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Farbrollen der PhotoMaid-Identität: warmer Grund, Blau als Akzent (Port von `AppColors` in Tokens.swift).
 * Light ist die Referenz; Dark ist dieselbe Marke (Nachtblau statt Schwarz).
 * Erhöhter Kontrast (Android 14+ `UiModeManager.contrast`) wird über eigene Werte bedient.
 */
@Immutable
data class PhotoMaidColors(
    val background: Color,
    val backgroundTint: Color,
    val surface: Color,
    val surfaceStroke: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /** Glasfläche schwebender Controls (transluzent, ohne teure Backdrop-Unschärfe). */
    val glass: Color,
    val glassHighlight: Color,
    val glassStroke: Color,
    /** Fläche der Tabbar: deckend (Glas-Anmutung über Kante/Kontur/Schatten), damit darunter scrollender Text nicht durchscheint. */
    val glassBar: Color,
    /** Akzent für Text und Symbole (AA ≥ 4,5 : 1 auf Hintergrund und Karten). */
    val accent: Color,
    /** Akzent als Fläche mit weißer Schrift (Primärbuttons, Abzeichen; ≥ 4,5 : 1 mit Weiß). */
    val accentFill: Color,
    val keep: Color,
    val cleanup: Color,
    val later: Color,
    val warning: Color,
    val success: Color,
    val indigo: Color,
    /** Warmer Markenton (Sand/Latte) für Onboarding, Hero und Illustrationen – nie für Text. */
    val warm: Color,
    val warmStrong: Color,
    /** Bernstein (Sonne) in Illustrationen. */
    val sun: Color,
    /** Bernstein und Erdton für Symbole auf getönten Kacheln (≥ 3 : 1 auf Kachel und Karte). */
    val amber: Color,
    val earth: Color,
    val isDark: Boolean,
) {
    companion object {
        // iOS-Systemfarben (Light/Dark) – nachgebildet, keine Apple-Assets.
        private val systemBlueLight = Color(0xFF007AFF); private val systemBlueDark = Color(0xFF0A84FF)
        private val systemRedLight = Color(0xFFFF3B30); private val systemRedDark = Color(0xFFFF453A)
        private val systemTealLight = Color(0xFF30B0C7); private val systemTealDark = Color(0xFF40C8E0)
        // Kontrastgeprüfte Varianten für Light (WCAG): Text-Blau 5,2 : 1, Flächen-Blau 4,7 : 1 mit Weiß,
        // Rot 5,4 : 1, Türkis 3,8 : 1, Grün 4,4 : 1 (Symbole ≥ 3 : 1). Dark: Text-Blau 6,0 : 1 auf Karten.
        private val accentTextLight = Color(0xFF0064D2); private val accentTextDark = Color(0xFF4DA3FF)
        private val accentFillLight = Color(0xFF0071E3)
        private val redAccessibleLight = Color(0xFFD70015)
        private val tealAccessibleLight = Color(0xFF0E8FA8)
        private val greenAccessibleLight = Color(0xFF248A3D)
        private val systemOrangeLight = Color(0xFFFF9500); private val systemOrangeDark = Color(0xFFFF9F0A)
        private val systemGreenLight = Color(0xFF34C759); private val systemGreenDark = Color(0xFF30D158)
        private val systemIndigoLight = Color(0xFF5856D6); private val systemIndigoDark = Color(0xFF5E5CE6)

        fun resolve(dark: Boolean, highContrast: Boolean): PhotoMaidColors {
            fun c(light: Long, darkV: Long, lightHC: Long, darkHC: Long, lightAlpha: Float = 1f, darkAlpha: Float = 1f): Color {
                val hex = if (dark) (if (highContrast) darkHC else darkV) else (if (highContrast) lightHC else light)
                val alpha = if (highContrast) 1f else if (dark) darkAlpha else lightAlpha
                return Color(0xFF000000 or hex).copy(alpha = alpha)
            }
            return PhotoMaidColors(
                // Warmer Grund (Creme/Sand, Produktentscheidung 2026-10-04), Blau bleibt der Akzent. Dunkel: warmes Anthrazit.
                // Kontrast: Text 14 : 1 / sekundär 5,6 : 1 auf Hintergrund (hell); dunkel 13 : 1 / 7 : 1.
                background = c(0xFAF6F1, 0x161311, 0xFFFBF7, 0x0B0907),
                backgroundTint = c(0xF2EBE3, 0x201B18, 0xF4EEE7, 0x15110F),
                surface = c(0xFFFFFF, 0x262120, 0xFFFFFF, 0x1C1816, 0.96f, 0.94f),
                surfaceStroke = c(0xEADFD3, 0x3A322D, 0xA89584, 0x857565, 1f, 0.9f),
                textPrimary = c(0x231F1C, 0xF5EFE9, 0x120F0D, 0xFFFFFF),
                textSecondary = c(0x6B625A, 0xC2B6AA, 0x453E37, 0xE6DDD3),
                glass = if (highContrast) c(0xFFFFFF, 0x262120, 0xFFFFFF, 0x1C1816)
                else if (dark) Color(0xFF2E2723).copy(alpha = 0.66f) else Color.White.copy(alpha = 0.62f),
                glassHighlight = if (dark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.75f),
                glassStroke = if (highContrast) c(0xFFFFFF, 0x3A322D, 0xA89584, 0x857565)
                else if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.85f),
                glassBar = if (highContrast) c(0xFFFFFF, 0x262120, 0xFFFFFF, 0x1C1816)
                else if (dark) Color(0xFF221D1A) else Color(0xFFFFFCF8),
                // Eigene Töne (kein Pfirsich/Apricot): Sand und Latte, dazu Bernstein nur in Illustrationen.
                warm = c(0xEFE7DA, 0x2B2622, 0xE9DFCF, 0x332C27),
                warmStrong = c(0xDCC9AB, 0x54473A, 0xCDB894, 0x66574A),
                sun = c(0xF0B53C, 0xE5A92F, 0xF0B53C, 0xE5A92F),
                amber = c(0xB86A00, 0xF0B53C, 0x8F5200, 0xFFC857),
                earth = c(0x8A6440, 0xD9B98A, 0x6B4B2C, 0xEBCFA5),
                accent = if (dark) accentTextDark else accentTextLight,
                accentFill = if (dark) systemBlueDark else accentFillLight,
                keep = if (dark) systemBlueDark else systemBlueLight,
                cleanup = if (dark) systemRedDark else redAccessibleLight,
                later = if (dark) systemTealDark else tealAccessibleLight,
                warning = if (dark) systemOrangeDark else systemOrangeLight,
                success = if (dark) systemGreenDark else greenAccessibleLight,
                indigo = if (dark) systemIndigoDark else systemIndigoLight,
                isDark = dark,
            )
        }
    }
}

/** Systemschrift, skaliert mit der Schriftgröße (sp). Entspricht den iOS-Dynamic-Type-Stilen. */
object AppTypography {
    /** Große, freundliche Überschrift (Onboarding, Angebot). */
    val display get() = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = tracking(-0.6f))
    val largeTitle get() = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = tracking(-0.5f))
    val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val subheadline = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    /** Kleinste Schrift der App (nie unter 12 sp). */
    val caption2 = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    /** Abschnittsüberschrift über Gruppen (Versalien, gedeckt). */
    val sectionHeader get() = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = tracking(0.4f))

    /**
     * Zeichenabstand nur für lateinische/kyrillische Schriften: Arabisch (verbundene Schrift) und CJK vertragen keinen
     * Abstand – Buchstaben trennen sich bzw. Wörter brechen unnötig um.
     */
    fun tracking(sp: Float): androidx.compose.ui.unit.TextUnit =
        if (java.util.Locale.getDefault().language in UNTRACKED) 0.sp else sp.sp
    private val UNTRACKED = setOf("ar", "ja", "ko", "zh")
    val metric = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
}

object AppSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Seitliche Ränder. */
    val pageMargin = 16.dp
    /** Abstand zwischen Blöcken einer Seite. */
    val block = 24.dp
    /** Abstand Überschrift → zugehörige Gruppe. */
    val headerGap = 8.dp
    /** Zeilenhöhe in Listen: einzeilig / zweizeilig. */
    val row = 56.dp
    val rowTwoLine = 64.dp
    /** Mindest-Touchfläche (iOS 44 pt; Android-Empfehlung 48 dp – wir nehmen 48). */
    val minTouch = 48.dp
}

/** Konturen. */
object AppStroke {
    val hairline = 0.5.dp
    val regular = 1.dp
    /** Rahmen des Mediums, sobald Loslassen entscheiden würde. */
    val intent = 2.dp
    val progress = 3.dp
}

/** Icon-Größen (Material Rounded, eine Familie). */
object AppIconSize {
    val small = 18.dp
    val regular = 22.dp
    val medium = 28.dp
    val large = 44.dp
}

/** Höhen von Bedienelementen. Touch-Ziel immer ≥ [AppSpacing.minTouch]. */
object AppControl {
    /** Sichtbarer Kreis leiser Kopfzeilen-Buttons (Touch-Ziel bleibt 48 dp). */
    val quietIcon = 40.dp
    val largeButton = 52.dp
    val tabBarItem = 54.dp
}

object AppRadii {
    val small = 12.dp
    /** Gruppen und Karten. */
    val card = 18.dp
    /** Die Hero-Karte auf „Heute“. */
    val hero = 24.dp
    /** Symbolkachel in Listenzeilen. */
    val tile = 9.dp
    val sheet = 28.dp
    val thumb = 10.dp
    /** Segmente, Chips, Eingabefelder. */
    val control = 10.dp
    /** Medium im Feed: kleiner Radius, damit Ecken von Screenshots/Dokumenten kaum beschnitten werden. */
    val media = 12.dp
}

/**
 * Bewegung: wenige zentrale Stufen. Feed darf dynamischer sein, Navigation ist ruhig.
 * Reduce Motion („Animationen entfernen“) → Sprung statt Animation.
 */
object AppMotion {
    const val FAST_MS = 120
    const val NORMAL_MS = 280

    /** Kleine Zustandswechsel (Farbe, Kontur, Druck). */
    fun <T> fast(): FiniteAnimationSpec<T> = tween(durationMillis = FAST_MS, easing = FastOutSlowInEasing)

    /** Navigation und Sheets. */
    fun <T> normal(): FiniteAnimationSpec<T> = tween(durationMillis = NORMAL_MS, easing = FastOutSlowInEasing)

    /** Rückfedern im Feed (betont, aber ohne Nachschwingen). */
    fun <T> standard(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else spring(dampingRatio = 0.86f, stiffness = 380f)

    /**
     * Hinausfliegen mit Wischgeschwindigkeit (kritisch gedämpfte Feder, ~200 ms). Offset in dp; die Schwelle beendet
     * die Animation, sobald das Medium sicher außerhalb des Bildes ist.
     */
    fun flyOut(reduceMotion: Boolean): FiniteAnimationSpec<androidx.compose.ui.geometry.Offset> =
        if (reduceMotion) snap() else spring(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = androidx.compose.ui.geometry.Offset(24f, 24f))

    const val HINT_VISIBLE_MS = 2_600L
    const val HINT_FADE_MS = 600
    /** Länger als früher: „Ändern“ in der Pille ist der Weg zum eigenen Termin. */
    const val LATER_PILL_MS = 4_000L
    /** Kurze Erfolgs-/Hinweispille im Feed (Favorit entfernt, verschoben). */
    const val NOTICE_MS = 3_000L
}

val LocalPhotoMaidColors = staticCompositionLocalOf { PhotoMaidColors.resolve(dark = false, highContrast = false) }
val LocalReduceMotion = staticCompositionLocalOf { false }

object PM {
    val colors: PhotoMaidColors
        @Composable @ReadOnlyComposable get() = LocalPhotoMaidColors.current
}

@Composable
fun ProvideTokens(colors: PhotoMaidColors, reduceMotion: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPhotoMaidColors provides colors, LocalReduceMotion provides reduceMotion, content = content)
}
