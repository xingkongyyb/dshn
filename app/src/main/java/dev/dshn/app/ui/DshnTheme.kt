package dev.dshn.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.dshn.app.data.AppThemeMode

@Immutable
data class DshnSemanticColors(
    val isDark: Boolean,
    val cardBorder: Color,
    val subtleBg: Color,
    val heroBg: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val terminalBg: Color,
    val terminalFg: Color,
    val codeBg: Color
)

private val LightSemantic = DshnSemanticColors(
    isDark = false,
    cardBorder = Color(0xFFD8E1F0),
    subtleBg = Color(0xFFF1F5FB),
    heroBg = Color(0xFFEAF1FF),
    success = Color(0xFF15803D),
    onSuccess = Color.White,
    successContainer = Color(0xFFDCFCE7),
    onSuccessContainer = Color(0xFF052E16),
    warning = Color(0xFFB45309),
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF451A03),
    terminalBg = Color(0xFF0F172A),
    terminalFg = Color(0xFFE6EDF3),
    codeBg = Color(0xFFF0F4FA)
)

private val DarkSemantic = DshnSemanticColors(
    isDark = true,
    cardBorder = Color(0xFF263244),
    subtleBg = Color(0xFF182232),
    heroBg = Color(0xFF13223D),
    success = Color(0xFF4ADE80),
    onSuccess = Color(0xFF042F16),
    successContainer = Color(0xFF123824),
    onSuccessContainer = Color(0xFFBBF7D0),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF3B2809),
    onWarningContainer = Color(0xFFFDE68A),
    terminalBg = Color(0xFF080D14),
    terminalFg = Color(0xFFE6EDF3),
    codeBg = Color(0xFF111824)
)

val LocalDshnColors = staticCompositionLocalOf { LightSemantic }

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF0F2B6B),
    secondary = Color(0xFF3B527A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5EDFC),
    onSecondaryContainer = Color(0xFF14274A),
    tertiary = Color(0xFF0E7490),
    tertiaryContainer = Color(0xFFCFFAFE),
    onTertiaryContainer = Color(0xFF083344),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE8EEF7),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLow = Color(0xFFF8FAFE),
    surfaceContainerHigh = Color(0xFFEAF0FA),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFD8E1F0),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6B9BFF),
    onPrimary = Color(0xFF071B44),
    primaryContainer = Color(0xFF1A366E),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFF9AB4E2),
    onSecondary = Color(0xFF102240),
    secondaryContainer = Color(0xFF1F2B3E),
    onSecondaryContainer = Color(0xFFD8E4FA),
    tertiary = Color(0xFF38BDF8),
    tertiaryContainer = Color(0xFF10374D),
    onTertiaryContainer = Color(0xFFBAE6FD),
    background = Color(0xFF0B1018),
    onBackground = Color(0xFFE7EEF8),
    surface = Color(0xFF121A26),
    onSurface = Color(0xFFE7EEF8),
    surfaceVariant = Color(0xFF1D283A),
    onSurfaceVariant = Color(0xFF9FB0C8),
    surfaceContainerLow = Color(0xFF101722),
    surfaceContainerHigh = Color(0xFF192434),
    outline = Color(0xFF465872),
    outlineVariant = Color(0xFF263244),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF4B161B),
    onErrorContainer = Color(0xFFFECACA)
)

@Composable
fun resolveIsDarkTheme(themeMode: AppThemeMode): Boolean = when (themeMode) {
    AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
}

@Composable
fun DshnTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit
) {
    val dark = resolveIsDarkTheme(themeMode)
    val scheme = if (dark) DarkColors else LightColors
    val semantic = if (dark) DarkSemantic else LightSemantic
    CompositionLocalProvider(LocalDshnColors provides semantic) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(),
            content = content
        )
    }
}
