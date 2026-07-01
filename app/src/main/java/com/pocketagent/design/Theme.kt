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
 * v6 PocketAgent Theme — Monochrome / e-ink aesthetic.
 *
 * Single accent (graphite), pure grayscale palette, high-contrast text.
 * Calm, focused, paper-like. No bright chromatic colors.
 */

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
    bg = InkBg,
    surface = InkSurface,
    surfaceRaised = InkSurfaceRaised,
    surfaceSubtle = InkSurfaceSubtle,
    divider = InkDivider,
    textPrimary = InkTextPrimary,
    textSecondary = InkTextSecondary,
    textTertiary = InkTextTertiary,
    textOnAccent = InkTextOnAccent,
    bubbleUser = InkBubbleUser,
    bubbleUserText = InkBubbleUserText,
    bubbleAgent = InkBubbleAgent,
    bubbleAgentText = InkBubbleAgentText,
    toolCardBg = InkToolCardBg,
    toolCardBorder = InkToolCardBorder,
    accent = InkAccent,
    accentPressed = InkAccentPressed,
    accentMuted = InkAccentMuted,
    success = InkSuccess,
    error = InkError,
    warning = InkWarning,
    info = InkInfo,
    codeBg = InkCodeBg,
    codeText = InkCodeText,
    codeKeyword = InkCodeKeyword,
    codeString = InkCodeString,
    codeComment = InkCodeComment
)

val DarkExtendedColors = PocketExtendedColors(
    bg = InkBgDark,
    surface = InkSurfaceDark,
    surfaceRaised = InkSurfaceRaisedDark,
    surfaceSubtle = InkSurfaceSubtleDark,
    divider = InkDividerDark,
    textPrimary = InkTextPrimaryDark,
    textSecondary = InkTextSecondaryDark,
    textTertiary = InkTextTertiaryDark,
    textOnAccent = InkTextOnAccentDark,
    bubbleUser = InkBubbleUserDark,
    bubbleUserText = InkBubbleUserTextDark,
    bubbleAgent = InkBubbleAgentDark,
    bubbleAgentText = InkBubbleAgentTextDark,
    toolCardBg = InkToolCardBgDark,
    toolCardBorder = InkToolCardBorderDark,
    accent = InkAccentDark,
    accentPressed = InkAccentPressedDark,
    accentMuted = InkAccentMutedDark,
    success = InkSuccessDark,
    error = InkErrorDark,
    warning = InkWarningDark,
    info = InkInfoDark,
    codeBg = InkCodeBgDark,
    codeText = InkCodeTextDark,
    codeKeyword = InkCodeKeywordDark,
    codeString = InkCodeStringDark,
    codeComment = InkCodeCommentDark
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

private val LightColorScheme = lightColorScheme(
    primary = InkAccent,
    onPrimary = InkTextOnAccent,
    primaryContainer = InkAccentMuted,
    onPrimaryContainer = InkAccent,
    secondary = InkAccent,
    onSecondary = InkTextOnAccent,
    tertiary = InkAccent,
    onTertiary = InkTextOnAccent,
    background = InkBg,
    onBackground = InkTextPrimary,
    surface = InkSurface,
    onSurface = InkTextPrimary,
    surfaceVariant = InkSurfaceSubtle,
    onSurfaceVariant = InkTextSecondary,
    surfaceTint = InkAccent,
    inverseSurface = InkTextPrimary,
    inverseOnSurface = InkBg,
    error = InkError,
    onError = Color.White,
    errorContainer = InkError,
    onErrorContainer = Color.White,
    outline = InkDivider,
    outlineVariant = InkDivider,
    scrim = Color.Black
)

private val DarkColorScheme = darkColorScheme(
    primary = InkAccentDark,
    onPrimary = InkTextOnAccentDark,
    primaryContainer = InkAccentMutedDark,
    onPrimaryContainer = InkAccentDark,
    secondary = InkAccentDark,
    onSecondary = InkTextOnAccentDark,
    tertiary = InkAccentDark,
    onTertiary = InkTextOnAccentDark,
    background = InkBgDark,
    onBackground = InkTextPrimaryDark,
    surface = InkSurfaceDark,
    onSurface = InkTextPrimaryDark,
    surfaceVariant = InkSurfaceSubtleDark,
    onSurfaceVariant = InkTextSecondaryDark,
    surfaceTint = InkAccentDark,
    inverseSurface = InkTextPrimaryDark,
    inverseOnSurface = InkBgDark,
    error = InkErrorDark,
    onError = Color.Black,
    errorContainer = InkErrorDark,
    onErrorContainer = Color.Black,
    outline = InkDividerDark,
    outlineVariant = InkDividerDark,
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
