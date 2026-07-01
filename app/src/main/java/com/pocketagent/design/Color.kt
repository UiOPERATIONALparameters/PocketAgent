package com.pocketagent.design

import androidx.compose.ui.graphics.Color

/**
 * v6 Color tokens — Monochrome / e-ink aesthetic.
 *
 * Design philosophy:
 *   - Single accent (muted graphite black, no chromatic color)
 *   - Pure grayscale palette for everything else
 *   - High contrast for text legibility (the e-ink feel)
 *   - Soft off-white background (paper-like)
 *   - No bright colors except for status indicators (kept subdued)
 *
 * The goal: a calm, focused interface that feels like reading paper.
 * Inspiration: Kindle, Readwise, Bear app, iA Writer.
 */

// ─── Light (paper) theme ──────────────────────────────────────────
// Off-white #FAFAF7 — slight warm tint, like aged paper
val InkBg = Color(0xFFFAFAF7)
val InkSurface = Color(0xFFFFFFFF)
val InkSurfaceRaised = Color(0xFFFFFFFF)
val InkSurfaceSubtle = Color(0xFFF2F2EE)         // subtle hover/selected
val InkDivider = Color(0xFFE5E5E0)               // hairline dividers

// Text — pure black with opacity for hierarchy
val InkTextPrimary = Color(0xFF1A1A1A)           // near-black, high contrast
val InkTextSecondary = Color(0xFF525252)         // 60% black
val InkTextTertiary = Color(0xFF8A8A8A)          // 40% black
val InkTextOnAccent = Color(0xFFFAFAF7)          // paper-white on accent

// User message bubble — solid graphite, inverted
val InkBubbleUser = Color(0xFF1A1A1A)
val InkBubbleUserText = Color(0xFFFAFAF7)

// Agent message — transparent, sits on background
val InkBubbleAgent = Color(0x00000000)
val InkBubbleAgentText = InkTextPrimary

// Tool call card — subtle gray
val InkToolCardBg = Color(0xFFF6F6F2)
val InkToolCardBorder = Color(0xFFE5E5E0)

// Accent — single graphite black, no chromatic accent
val InkAccent = Color(0xFF1A1A1A)
val InkAccentPressed = Color(0xFF000000)
val InkAccentMuted = Color(0xFFE5E5E0)

// Status — kept subdued, still distinguishable
val InkSuccess = Color(0xFF4A7C5C)               // muted forest green
val InkError = Color(0xFFA04545)                 // muted brick red
val InkWarning = Color(0xFF8A6D3B)               // muted amber
val InkInfo = Color(0xFF4A6A8A)                  // muted slate blue

// Code blocks — slightly different bg
val InkCodeBg = Color(0xFFF2F2EE)
val InkCodeText = Color(0xFF1A1A1A)
val InkCodeKeyword = Color(0xFF525252)
val InkCodeString = Color(0xFF1A1A1A)
val InkCodeComment = Color(0xFF8A8A8A)

// ─── Dark (e-ink dark / OLED-friendly) theme ──────────────────────
// True black for OLED screens
val InkBgDark = Color(0xFF000000)
val InkSurfaceDark = Color(0xFF0A0A0A)
val InkSurfaceRaisedDark = Color(0xFF141414)
val InkSurfaceSubtleDark = Color(0xFF1A1A1A)
val InkDividerDark = Color(0xFF262626)

val InkTextPrimaryDark = Color(0xFFE5E5E5)       // off-white, easier than pure
val InkTextSecondaryDark = Color(0xFFA0A0A0)
val InkTextTertiaryDark = Color(0xFF666666)
val InkTextOnAccentDark = Color(0xFF000000)

val InkBubbleUserDark = Color(0xFFE5E5E5)
val InkBubbleUserTextDark = Color(0xFF000000)

val InkBubbleAgentDark = Color(0x00000000)
val InkBubbleAgentTextDark = InkTextPrimaryDark

val InkToolCardBgDark = Color(0xFF141414)
val InkToolCardBorderDark = Color(0xFF262626)

val InkAccentDark = Color(0xFFE5E5E5)
val InkAccentPressedDark = Color(0xFFFFFFFF)
val InkAccentMutedDark = Color(0xFF262626)

val InkSuccessDark = Color(0xFF6FA886)
val InkErrorDark = Color(0xFFC77878)
val InkWarningDark = Color(0xFFB89968)
val InkInfoDark = Color(0xFF7898B8)

val InkCodeBgDark = Color(0xFF141414)
val InkCodeTextDark = Color(0xFFE5E5E5)
val InkCodeKeywordDark = Color(0xFFA0A0A0)
val InkCodeStringDark = Color(0xFFE5E5E5)
val InkCodeCommentDark = Color(0xFF666666)
