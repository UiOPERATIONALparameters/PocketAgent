package com.pocketagent.design

import androidx.compose.ui.graphics.Color

/**
 * Color tokens extracted directly from Kimi's live DOM via headless browser inspection.
 *
 * Key insights:
 * - Background is off-white #F9FBFC, not pure white. This is THE iOS-clean signature.
 * - Text is 90% opacity black (softer than pure black).
 * - Shadows are 3-7% opacity, two-layer (NOT Material's heavy 20% shadows).
 */

// Light theme - verified against Kimi's DOM
val KimiBg = Color(0xFFF9FBFC)           // rgb(249, 251, 252) - page background
val KimiSurface = Color(0xFFFFFFFF)       // pure white - composer/cards
val KimiSurfaceRaised = Color(0xFFFFFFFF) // pure white, elevated
val KimiSurfaceSubtle = Color(0xFFF1F4F6) // very subtle hover/selected state
val KimiDivider = Color(0xFFE8ECEE)       // hairline dividers

val KimiTextPrimary = Color(0xE6000000.toInt())    // 90% black
val KimiTextSecondary = Color(0x99000000.toInt())  // 60% black - placeholders
val KimiTextTertiary = Color(0x66000000.toInt())   // 40% black - hints
val KimiTextOnAccent = Color(0xFFFFFFFF)            // white on accent

// User message bubble - slightly darker than surface, like iMessage
val KimiBubbleUser = Color(0xFF0A8C7A)              // refined teal accent
val KimiBubbleUserText = Color(0xFFFFFFFF)

// Agent message - transparent, sits on background
val KimiBubbleAgent = Color(0x00000000)             // transparent
val KimiBubbleAgentText = KimiTextPrimary

// Tool call card - subtle surface tint
val KimiToolCardBg = Color(0xFFF6F8FA)
val KimiToolCardBorder = Color(0xFFE8ECEE)

// Accent (refined teal, slightly darker than ChatGPT's for premium feel)
val KimiAccent = Color(0xFF0A8C7A)
val KimiAccentPressed = Color(0xFF07715F)
val KimiAccentMuted = Color(0x1A0A8C7A)  // 10% accent for backgrounds

// Status colors (kept muted to match aesthetic)
val KimiSuccess = Color(0xFF16A34A)
val KimiError = Color(0xFFDC2626)
val KimiWarning = Color(0xFFD97706)
val KimiInfo = Color(0xFF2563EB)

// Code blocks
val KimiCodeBg = Color(0xFFF6F8FA)
val KimiCodeText = Color(0xFF1F2328)
val KimiCodeKeyword = Color(0xFFCF222E)
val KimiCodeString = Color(0xFF0A8C7A)
val KimiCodeComment = Color(0xFF6E7781)

// Dark theme - hand-crafted to preserve the off-white-on-dark contrast ratio
val KimiBgDark = Color(0xFF000000)                  // true black
val KimiSurfaceDark = Color(0xFF0E0E10)             // very dark surface
val KimiSurfaceRaisedDark = Color(0xFF161618)       // elevated
val KimiSurfaceSubtleDark = Color(0xFF1C1C1F)       // hover/selected
val KimiDividerDark = Color(0xFF26262A)

val KimiTextPrimaryDark = Color(0xE6FFFFFF.toInt())   // 90% white
val KimiTextSecondaryDark = Color(0x99FFFFFF.toInt()) // 60% white
val KimiTextTertiaryDark = Color(0x66FFFFFF.toInt())  // 40% white
val KimiTextOnAccentDark = Color(0xFFFFFFFF)

val KimiBubbleUserDark = Color(0xFF0A8C7A)
val KimiBubbleUserTextDark = Color(0xFFFFFFFF)

val KimiBubbleAgentDark = Color(0x00000000)
val KimiBubbleAgentTextDark = KimiTextPrimaryDark

val KimiToolCardBgDark = Color(0xFF161618)
val KimiToolCardBorderDark = Color(0xFF26262A)

val KimiAccentDark = Color(0xFF14B8A6)              // slightly brighter for dark bg
val KimiAccentPressedDark = Color(0xFF0D9488)
val KimiAccentMutedDark = Color(0x2614B8A6)

val KimiSuccessDark = Color(0xFF22C55E)
val KimiErrorDark = Color(0xFFEF4444)
val KimiWarningDark = Color(0xFFF59E0B)
val KimiInfoDark = Color(0xFF3B82F6)

val KimiCodeBgDark = Color(0xFF161618)
val KimiCodeTextDark = Color(0xFFE6EDF3)
val KimiCodeKeywordDark = Color(0xFFFF7B72)
val KimiCodeStringDark = Color(0xFF7EE787)
val KimiCodeCommentDark = Color(0xFF8B949E)
