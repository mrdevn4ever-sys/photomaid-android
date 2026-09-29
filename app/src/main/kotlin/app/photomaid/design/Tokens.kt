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
 * Farbrollen der hellblauen PhotoMaid-Identität (Port von `AppColors` in Tokens.swift).
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
    val accent: Color,
    val keep: Color,
    val cleanup: Color,
    val later: Color,
    val warning: Color,
    val success: Color,
    val indigo: Color,
    val isDark: Boolean,
) {
    companion object {
        // iOS-Systemfarben (Light/Dark) – nachgebildet, keine Apple-Assets.
        private val systemBlueLight = Color(0xFF007AFF); private val systemBlueDark = Color(0xFF0A84FF)
        private val systemRedLight = Color(0xFFFF3B30); private val systemRedDark = Color(0xFFFF453A)
        private val systemTealLight = Color(0xFF30B0C7); private val systemTealDark = Color(0xFF40C8E0)
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
                // Neutraler Grund mit leichtem PhotoMaid-Blau; Flächen fast deckend und mit feiner Kontur statt weißer Kante.
                background = c(0xF3F6FA, 0x0C1522, 0xF6FAFF, 0x050B14),
                backgroundTint = c(0xE6EDF6, 0x111F33, 0xE8F1FC, 0x0A1628),
                surface = c(0xFFFFFF, 0x16233A, 0xFFFFFF, 0x101C2E, 0.94f, 0.92f),
                surfaceStroke = c(0xDDE5EF, 0x26374F, 0x8FA6C4, 0x6C84A6, 1f, 0.9f),
                textPrimary = c(0x0F2747, 0xEAF2FF, 0x06152B, 0xFFFFFF),
                textSecondary = c(0x4A6282, 0xA9BCD6, 0x2B3F5C, 0xD5E1F2),
                glass = if (highContrast) c(0xFFFFFF, 0x15243A, 0xFFFFFF, 0x101C2E)
                else if (dark) Color(0xFF1E3150).copy(alpha = 0.62f) else Color.White.copy(alpha = 0.58f),
                glassHighlight = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.75f),
                glassStroke = if (highContrast) c(0xFFFFFF, 0x2A3E5C, 0x8FA6C4, 0x6C84A6)
                else if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.85f),
                glassBar = if (highContrast) c(0xFFFFFF, 0x15243A, 0xFFFFFF, 0x101C2E)
                else if (dark) Color(0xFF17263D) else Color(0xFFFBFCFE),
                accent = if (dark) systemBlueDark else systemBlueLight,
                keep = if (dark) systemBlueDark else systemBlueLight,
                cleanup = if (dark) systemRedDark else systemRedLight,
                later = if (dark) systemTealDark else systemTealLight,
                warning = if (dark) systemOrangeDark else systemOrangeLight,
                success = if (dark) systemGreenDark else systemGreenLight,
                indigo = if (dark) systemIndigoDark else systemIndigoLight,
                isDark = dark,
            )
        }
    }
}

/** Systemschrift, skaliert mit der Schriftgröße (sp). Entspricht den iOS-Dynamic-Type-Stilen. */
object AppTypography {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold)
    val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val subheadline = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val caption2 = TextStyle(fontSize = 11.sp, lineHeight = 13.sp)
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
    val pageMargin = 18.dp
    /** Mindest-Touchfläche (iOS 44 pt; Android-Empfehlung 48 dp – wir nehmen 48). */
    val minTouch = 48.dp
}

object AppRadii {
    val small = 12.dp
    val card = 22.dp
    val sheet = 28.dp
    val thumb = 10.dp
    /** Medium im Feed: kleiner Radius, damit Ecken von Screenshots/Dokumenten kaum beschnitten werden. */
    val media = 12.dp
}

/** Animationsdauern/-federn (iOS `AppMotion`). Reduce Motion → keine Kipp-/Flugeffekte. */
object AppMotion {
    fun <T> standard(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else spring(dampingRatio = 0.86f, stiffness = 380f)

    fun <T> commit(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else tween(durationMillis = 220, easing = FastOutSlowInEasing)

    const val HINT_VISIBLE_MS = 2_600L
    const val HINT_FADE_MS = 600
    /** Länger als früher: „Ändern“ in der Pille ist der Weg zum eigenen Termin. */
    const val LATER_PILL_MS = 4_000L
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
