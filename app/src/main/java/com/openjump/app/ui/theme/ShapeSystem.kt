package com.openjump.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * OpenJump shape tokens — a restrained, functional radius scale.
 *
 * Larger radii communicate component hierarchy, never decoration.
 * `full` is reserved for pills / status chips.
 */
object ShapeTokens {
    /** Small inlay (avatars, tiny badges). */
    val extraSmall: CornerBasedShape = RoundedCornerShape(6.dp)
    /** Buttons / chips / compact rows. */
    val small: CornerBasedShape = RoundedCornerShape(10.dp)
    /** Default surfaces: list rows, sections, cards. */
    val medium: CornerBasedShape = RoundedCornerShape(12.dp)
    /** Elevated surfaces, dialogs, hero metric groups. */
    val large: CornerBasedShape = RoundedCornerShape(16.dp)
    /** Prominent surfaces (result summary, sheets). */
    val extraLarge: CornerBasedShape = RoundedCornerShape(20.dp)
    /** Pill shape for status / category chips. */
    val full: CornerBasedShape = RoundedCornerShape(999.dp)
    /** Circle for icon containers. */
    val circle: CornerBasedShape = CircleShape
}

/** Material 3 [Shapes] wired to the OpenJump token set. */
val OpenJumpShapes = Shapes(
    extraSmall = ShapeTokens.extraSmall,
    small = ShapeTokens.small,
    medium = ShapeTokens.medium,
    large = ShapeTokens.large,
    extraLarge = ShapeTokens.extraLarge,
)