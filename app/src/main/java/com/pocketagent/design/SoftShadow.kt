package com.pocketagent.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Soft shadow modifier replicating Kimi's signature two-layer soft shadow:
 *   box-shadow: 0 4px 12px rgba(0,0,0,0.03), 0 5px 16px -4px rgba(0,0,0,0.07);
 *
 * Material's default shadow is single-layer and ~20% opacity, which feels heavy.
 * We split into two low-opacity layers for the soft iOS hover-state feel.
 *
 * Compose's shadow() takes elevation in dp and renders a single ambient shadow.
 * To approximate two layers we use a Modifier chain with two elevations and a
 * graphicsLayer for ambient color tint. For simplicity we use one elevation
 * with a low value (1dp) and let the system handle ambient/spot separation.
 */
fun Modifier.softShadow(
    elevation: Dp = 2.dp,
    ambientColor: Color = Color.Black.copy(alpha = 0.03f),
    spotColor: Color = Color.Black.copy(alpha = 0.07f)
): Modifier = this.shadow(
    elevation = elevation,
    ambientColor = ambientColor,
    spotColor = spotColor,
    clip = false
)

/**
 * Stronger shadow for elevated sheets/modals.
 */
fun Modifier.softShadowLg(): Modifier = this.softShadow(elevation = 6.dp)

/**
 * Subtle shadow for cards on hover-like states.
 */
fun Modifier.softShadowSm(): Modifier = this.softShadow(elevation = 1.dp)
