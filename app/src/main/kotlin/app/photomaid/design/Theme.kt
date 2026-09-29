package app.photomaid.design

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@kotlinx.serialization.Serializable
enum class AppearanceChoice { SYSTEM, LIGHT, DARK }

/** Android 14+: Systemkontrast (iOS „Erhöhter Kontrast“). */
fun isHighContrast(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < 34) return false
    val ui = ctx.getSystemService(UiModeManager::class.java) ?: return false
    return ui.contrast >= 0.5f
}

/** Animationen aus (Entwickleroptionen/Bedienungshilfen „Animationen entfernen“) = iOS Reduce Motion. */
fun isReduceMotion(ctx: Context): Boolean =
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * Zentrale Theme-Schicht. Material 3 dient nur als technische Basis (Dialoge, Sheets, Picker);
 * die visuelle Sprache kommt aus [PhotoMaidColors].
 */
@Composable
fun PhotoMaidTheme(appearance: AppearanceChoice = AppearanceChoice.SYSTEM, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = when (appearance) {
        AppearanceChoice.SYSTEM -> isSystemInDarkTheme()
        AppearanceChoice.LIGHT -> false
        AppearanceChoice.DARK -> true
    }
    val hc = remember(ctx) { isHighContrast(ctx) }
    val reduce = remember(ctx) { isReduceMotion(ctx) }
    val colors = remember(dark, hc) { PhotoMaidColors.resolve(dark, hc) }
    val scheme = if (dark) darkColorScheme(
        primary = colors.accent, background = colors.background, surface = colors.background,
        surfaceContainerHigh = colors.backgroundTint, surfaceContainerLow = colors.background,
        onSurface = colors.textPrimary, onSurfaceVariant = colors.textSecondary, error = colors.cleanup,
    ) else lightColorScheme(
        primary = colors.accent, background = colors.background, surface = colors.background,
        surfaceContainerHigh = colors.background, surfaceContainerLow = colors.background,
        onSurface = colors.textPrimary, onSurfaceVariant = colors.textSecondary, error = colors.cleanup,
    )
    MaterialTheme(colorScheme = scheme) { ProvideTokens(colors, reduce, content) }
}
