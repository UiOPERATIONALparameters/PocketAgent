package com.pocketagent.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape tokens - verified against Kimi's DOM.
 *
 * Composer: 24dp radius (signature iOS look)
 * Buttons: 12dp radius
 * Icon chips: 14dp radius
 * Cards: 16dp radius (medium)
 * Sheets: 24dp top radius
 */
object PocketShapes {
    val EditorShape = RoundedCornerShape(24.dp)
    val ButtonShape = RoundedCornerShape(12.dp)
    val IconShape = RoundedCornerShape(14.dp)
    val CardShape = RoundedCornerShape(16.dp)
    val SheetTopShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val PillShape = RoundedCornerShape(50)
    val SmallShape = RoundedCornerShape(8.dp)
    val TinyShape = RoundedCornerShape(6.dp)
}

val PocketShapes3 = Shapes(
    extraSmall = PocketShapes.TinyShape,
    small = PocketShapes.SmallShape,
    medium = PocketShapes.CardShape,
    large = PocketShapes.EditorShape,
    extraLarge = PocketShapes.SheetTopShape
)
