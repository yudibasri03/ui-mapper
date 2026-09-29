package app.uimapper.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/*
 * Brand palette: deep blue #0F3D5E (primary) with an amber accent #FFC940 (secondary).
 * Used when dynamic colour is not available (Android 11 and lower).
 */

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0F3D5E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE5F8),
    onPrimaryContainer = Color(0xFF001E31),
    inversePrimary = Color(0xFF9ACBF0),
    secondary = Color(0xFF785A00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFC940),
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = Color(0xFF3E6374),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC2E8FC),
    onTertiaryContainer = Color(0xFF001F2A),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF191C1F),
    surface = Color(0xFFF8F9FC),
    onSurface = Color(0xFF191C1F),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF41484D),
    surfaceTint = Color(0xFF0F3D5E),
    inverseSurface = Color(0xFF2E3134),
    inverseOnSurface = Color(0xFFEFF1F4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF72787E),
    outlineVariant = Color(0xFFC1C7CE),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF8F9FC),
    surfaceDim = Color(0xFFD8DADD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F7),
    surfaceContainer = Color(0xFFECEEF1),
    surfaceContainerHigh = Color(0xFFE6E8EB),
    surfaceContainerHighest = Color(0xFFE1E2E5),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF9ACBF0),
    onPrimary = Color(0xFF003350),
    primaryContainer = Color(0xFF0F3D5E),
    onPrimaryContainer = Color(0xFFCFE5F8),
    inversePrimary = Color(0xFF2B6284),
    secondary = Color(0xFFFFC940),
    onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFF5B4300),
    onSecondaryContainer = Color(0xFFFFDF9A),
    tertiary = Color(0xFFA6CCDF),
    onTertiary = Color(0xFF0A3544),
    tertiaryContainer = Color(0xFF254B5B),
    onTertiaryContainer = Color(0xFFC2E8FC),
    background = Color(0xFF0F1417),
    onBackground = Color(0xFFE1E2E5),
    surface = Color(0xFF0F1417),
    onSurface = Color(0xFFE1E2E5),
    surfaceVariant = Color(0xFF41484D),
    onSurfaceVariant = Color(0xFFC1C7CE),
    surfaceTint = Color(0xFF9ACBF0),
    inverseSurface = Color(0xFFE1E2E5),
    inverseOnSurface = Color(0xFF2E3134),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8B9198),
    outlineVariant = Color(0xFF41484D),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353A3E),
    surfaceDim = Color(0xFF0F1417),
    surfaceContainerLowest = Color(0xFF0A0F12),
    surfaceContainerLow = Color(0xFF171C20),
    surfaceContainer = Color(0xFF1B2024),
    surfaceContainerHigh = Color(0xFF262B2F),
    surfaceContainerHighest = Color(0xFF31363A),
)

private val AppTypography = Typography()

/**
 * App theme. Uses Material You dynamic colour on Android 12+ (API 31), otherwise the brand
 * light/dark schemes. Follows the system dark-mode setting.
 */
@Composable
fun UiMapperTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = remember(dark, context) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> DarkScheme
            else -> LightScheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
