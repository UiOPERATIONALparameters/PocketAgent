package com.pocketagent.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * PocketAgent color scheme.
 *
 * Designed by extracting actual computed CSS values from Kimi's live DOM.
 * See /home/z/my-project/research/KIMI_UI_RESEARCH.md for provenance.
 */

// Extended color palette exposed via CompositionLocal for components that need more than Material's defaults
data class PocketExtendedColors(
    val bg: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSubtle: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textOnAccent: Color,
    val bubbleUser: Color,
    val bubbleUserText: Color,
    val bubbleAgent: Color,
    val bubbleAgentText: Color,
    val toolCardBg: Color,
    val toolCardBorder: Color,
    val accent: Color,
    val accentPressed: Color,
    val accentMuted: Color,
    val success: Color,
    val error: Color,
    val warning: Color,
    val info: Color,
    val codeBg: Color,
    val codeText: Color,
    val codeKeyword: Color,
    val codeString: Color,
    val codeComment: Color
)

val LightExtendedColors = PocketExtendedColors(
    bg = KimiBg,
    surface = KimiSurface,
    surfaceRaised = KimiSurfaceRaised,
    surfaceSubtle = KimiSurfaceSubtle,
    divider = KimiDivider,
    textPrimary = KimiTextPrimary,
    textSecondary = KimiTextSecondary,
    textTertiary = KimiTextTertiary,
    textOnAccent = KimiTextOnAccent,
    bubbleUser = KimiBubbleUser,
    bubbleUserText = KimiBubbleUserText,
    bubbleAgent = KimiBubbleAgent,
    bubbleAgentText = KimiBubbleAgentText,
    toolCardBg = KimiToolCardBg,
    toolCardBorder = KimiToolCardBorder,
    accent = KimiAccent,
    accentPressed = KimiAccentPressed,
    accentMuted = KimiAccentMuted,
    success = KimiSuccess,
    error = KimiError,
    warning = KimiWarning,
    info = KimiInfo,
    codeBg = KimiCodeBg,
    codeText = KimiCodeText,
    codeKeyword = KimiCodeKeyword,
    codeString = KimiCodeString,
    codeComment = KimiCodeComment
)

val DarkExtendedColors = PocketExtendedColors(
    bg = KimiBgDark,
    surface = KimiSurfaceDark,
    surfaceRaised = KimiSurfaceRaisedDark,
    surfaceSubtle = KimiSurfaceSubtleDark,
    divider = KimiDividerDark,
    textPrimary = KimiTextPrimaryDark,
    textSecondary = KimiTextSecondaryDark,
    textTertiary = KimiTextTertiaryDark,
    textOnAccent = KimiTextOnAccentDark,
    bubbleUser = KimiBubbleUserDark,
    bubbleUserText = KimiBubbleUserTextDark,
    bubbleAgent = KimiBubbleAgentDark,
    bubbleAgentText = KimiBubbleAgentTextDark,
    toolCardBg = KimiToolCardBgDark,
    toolCardBorder = KimiToolCardBorderDark,
    accent = KimiAccentDark,
    accentPressed = KimiAccentPressedDark,
    accentMuted = KimiAccentMutedDark,
    success = KimiSuccessDark,
    error = KimiErrorDark,
    warning = KimiWarningDark,
    info = KimiInfoDark,
    codeBg = KimiCodeBgDark,
    codeText = KimiCodeTextDark,
    codeKeyword = KimiCodeKeywordDark,
    codeString = KimiCodeStringDark,
    codeComment = KimiCodeCommentDark
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

private val LightColorScheme = lightColorScheme(
    primary = KimiAccent,
    onPrimary = KimiTextOnAccent,
    primaryContainer = KimiAccentMuted,
    onPrimaryContainer = KimiAccent,
    secondary = KimiAccent,
    onSecondary = KimiTextOnAccent,
    tertiary = KimiAccent,
    onTertiary = KimiTextOnAccent,
    background = KimiBg,
    onBackground = KimiTextPrimary,
    surface = KimiSurface,
    onSurface = KimiTextPrimary,
    surfaceVariant = KimiSurfaceSubtle,
    onSurfaceVariant = KimiTextSecondary,
    surfaceTint = KimiAccent,
    inverseSurface = KimiTextPrimary,
    inverseOnSurface = KimiBg,
    error = KimiError,
    onError = Color.White,
    errorContainer = KimiError,
    onErrorContainer = Color.White,
    outline = KimiDivider,
    outlineVariant = KimiDivider,
    scrim = Color.Black
)

private val DarkColorScheme = darkColorScheme(
    primary = KimiAccentDark,
    onPrimary = KimiTextOnAccentDark,
    primaryContainer = KimiAccentMutedDark,
    onPrimaryContainer = KimiAccentDark,
    secondary = KimiAccentDark,
    onSecondary = KimiTextOnAccentDark,
    tertiary = KimiAccentDark,
    onTertiary = KimiTextOnAccentDark,
    background = KimiBgDark,
    onBackground = KimiTextPrimaryDark,
    surface = KimiSurfaceDark,
    onSurface = KimiTextPrimaryDark,
    surfaceVariant = KimiSurfaceSubtleDark,
    onSurfaceVariant = KimiTextSecondaryDark,
    surfaceTint = KimiAccentDark,
    inverseSurface = KimiTextPrimaryDark,
    inverseOnSurface = KimiBgDark,
    error = KimiErrorDark,
    onError = Color.Black,
    errorContainer = KimiErrorDark,
    onErrorContainer = Color.Black,
    outline = KimiDividerDark,
    outlineVariant = KimiDividerDark,
    scrim = Color.Black
)

@Composable
fun PocketTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extended = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PocketTypography,
            shapes = PocketShapes3,
            content = content
        )
    }
}

@Composable
fun extendedColors(): PocketExtendedColors = LocalExtendedColors.current
