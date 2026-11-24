package dev.dshn.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshn.app.data.AppLocaleMode
import dev.dshn.app.data.AppThemeMode
import dev.dshn.app.data.resolveIsZhLocale

/** DeepSeek-style brand blue used across the app and the launcher icon. */
val DshnBrandBlue = Color(0xFF4D6BFE)
val DshnBrandGradientStart = Color(0xFF6A86FF)
val DshnBrandGradientEnd = Color(0xFF2F43D6)

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
    val codeBg: Color,
    val userBubble: Color,
    val onUserBubble: Color,
    val composerBg: Color,
    val thinkingBar: Color
)

private val LightSemantic = DshnSemanticColors(
    isDark = false,
    cardBorder = Color(0xFFE4E8F0),
    subtleBg = Color(0xFFF2F4F9),
    heroBg = Color(0xFFEEF2FF),
    success = Color(0xFF16A34A),
    onSuccess = Color.White,
    successContainer = Color(0xFFDDF7E5),
    onSuccessContainer = Color(0xFF0B3B1E),
    warning = Color(0xFFD97706),
    warningContainer = Color(0xFFFFF1D6),
    onWarningContainer = Color(0xFF4A2A04),
    terminalBg = Color(0xFF14161C),
    terminalFg = Color(0xFFE6EAF2),
    codeBg = Color(0xFFF4F6FA),
    userBubble = Color(0xFFE9EEFF),
    onUserBubble = Color(0xFF16204D),
    composerBg = Color(0xFFFFFFFF),
    thinkingBar = Color(0xFFD5DBE8)
)

private val DarkSemantic = DshnSemanticColors(
    isDark = true,
    cardBorder = Color(0xFF2B2E36),
    subtleBg = Color(0xFF1D1F25),
    heroBg = Color(0xFF1B2242),
    success = Color(0xFF4ADE80),
    onSuccess = Color(0xFF052E14),
    successContainer = Color(0xFF14331F),
    onSuccessContainer = Color(0xFFBBF7D0),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF3A2A0A),
    onWarningContainer = Color(0xFFFDE68A),
    terminalBg = Color(0xFF0B0C10),
    terminalFg = Color(0xFFE6EAF2),
    codeBg = Color(0xFF1A1C22),
    userBubble = Color(0xFF26304F),
    onUserBubble = Color(0xFFE3E8FF),
    composerBg = Color(0xFF1C1E24),
    thinkingBar = Color(0xFF3A3E49)
)

val LocalDshnColors = staticCompositionLocalOf { LightSemantic }

/** Whether UI copy should be Chinese. Driven by the in-app language setting. */
val LocalIsZh = staticCompositionLocalOf { true }

/** Inline bilingual copy: returns [zh] when the UI language is Chinese, otherwise [en]. */
@Composable
@ReadOnlyComposable
fun tr(zh: String, en: String): String = if (LocalIsZh.current) zh else en

fun resolveIsZh(mode: AppLocaleMode): Boolean = resolveIsZhLocale(mode)

private val LightColors = lightColorScheme(
    primary = DshnBrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5EAFF),
    onPrimaryContainer = Color(0xFF15206B),
    secondary = Color(0xFF5A6787),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EDF7),
    onSecondaryContainer = Color(0xFF1B2540),
    tertiary = Color(0xFF0E8C7E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3F5EF),
    onTertiaryContainer = Color(0xFF07342E),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF141824),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141824),
    surfaceVariant = Color(0xFFEEF1F7),
    onSurfaceVariant = Color(0xFF5B6475),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9FAFD),
    surfaceContainer = Color(0xFFF2F4F9),
    surfaceContainerHigh = Color(0xFFECEFF6),
    surfaceContainerHighest = Color(0xFFE5E9F2),
    inverseSurface = Color(0xFF262A35),
    inverseOnSurface = Color(0xFFF2F4F9),
    outline = Color(0xFFA5ADBD),
    outlineVariant = Color(0xFFE3E7EF),
    error = Color(0xFFE5484D),
    onError = Color.White,
    errorContainer = Color(0xFFFFE8E8),
    onErrorContainer = Color(0xFF7A1B1E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7C93FF),
    onPrimary = Color(0xFF0A1A5C),
    primaryContainer = Color(0xFF26346F),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFAAB5D3),
    onSecondary = Color(0xFF1B2540),
    secondaryContainer = Color(0xFF262A35),
    onSecondaryContainer = Color(0xFFDCE2F2),
    tertiary = Color(0xFF5ED9C6),
    onTertiary = Color(0xFF06302A),
    tertiaryContainer = Color(0xFF12403A),
    onTertiaryContainer = Color(0xFFC4F5EC),
    background = Color(0xFF111216),
    onBackground = Color(0xFFE7E9EF),
    surface = Color(0xFF18191E),
    onSurface = Color(0xFFE7E9EF),
    surfaceVariant = Color(0xFF23252C),
    onSurfaceVariant = Color(0xFFA3A9B7),
    surfaceContainerLowest = Color(0xFF0E0F12),
    surfaceContainerLow = Color(0xFF16171B),
    surfaceContainer = Color(0xFF1C1D22),
    surfaceContainerHigh = Color(0xFF23252B),
    surfaceContainerHighest = Color(0xFF2A2C33),
    inverseSurface = Color(0xFFE7E9EF),
    inverseOnSurface = Color(0xFF1C1D22),
    outline = Color(0xFF4A4F5C),
    outlineVariant = Color(0xFF2C2F37),
    error = Color(0xFFFF6B6F),
    onError = Color(0xFF4A0B0E),
    errorContainer = Color(0xFF4A1C1F),
    onErrorContainer = Color(0xFFFFD6D7)
)

private val BaseTypography = Typography()

private val DshnTypography = BaseTypography.copy(
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = BaseTypography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = BaseTypography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
)

private val DshnShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
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
    localeMode: AppLocaleMode = AppLocaleMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = resolveIsDarkTheme(themeMode)
    val scheme = if (dark) DarkColors else LightColors
    val semantic = if (dark) DarkSemantic else LightSemantic
    CompositionLocalProvider(
        LocalDshnColors provides semantic,
        LocalIsZh provides resolveIsZh(localeMode)
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = DshnTypography,
            shapes = DshnShapes,
            content = content
        )
    }
}
