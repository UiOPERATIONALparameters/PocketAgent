package com.pocketagent.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography - Kimi uses the SYSTEM font stack (no custom webfont).
 * On Android, FontFamily.Default resolves to Roboto, which IS what Kimi targets.
 * Body size is 14px (compact, premium feel - ChatGPT uses 16px which feels too spacious).
 *
 * Verified against Kimi's computed styles:
 * font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, Roboto...
 * font-size: 14px (body)
 */
object PocketType {
    val Family = FontFamily.Default  // system font - zero hallucination risk

    // Display
    val Display = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp
    )

    val Headline = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp
    )

    val Title = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp
    )

    val TitleSmall = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp
    )

    // Body (Kimi's signature 14px body)
    val Body = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )

    val BodyMedium = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )

    val BodySmall = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )

    val Label = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    )

    val LabelSmall = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    )

    // Code & monospace (we DO override this with monospace for code blocks)
    val Code = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    )

    val CodeSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp
    )
}

val PocketTypography = Typography(
    displayLarge = PocketType.Display,
    displayMedium = PocketType.Headline,
    displaySmall = PocketType.Title,
    headlineLarge = PocketType.Headline,
    headlineMedium = PocketType.Title,
    headlineSmall = PocketType.TitleSmall,
    titleLarge = PocketType.Title,
    titleMedium = PocketType.TitleSmall,
    titleSmall = PocketType.Label,
    bodyLarge = PocketType.Body,
    bodyMedium = PocketType.Body,
    bodySmall = PocketType.BodySmall,
    labelLarge = PocketType.Label,
    labelMedium = PocketType.Label,
    labelSmall = PocketType.LabelSmall
)
