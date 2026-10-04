package com.openjump.app.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/**
 * OpenJump spacing tokens.
 *
 * The scale is intentionally small and purpose-built:
 * related content sits closer together, separate groups are farther apart.
 * Screens should use these tokens (and especially [screenHorizontal]) instead
 * of inventing their own padding values.
 */
object Spacing {
    // Micro spacing between tightly coupled elements.
    val xs = 4.dp
    // Compact internal relationships (inside a row / list item).
    val sm = 8.dp
    /** Default small component spacing. */
    val md = 12.dp
    /** Default spacing between content and screen edges / between groups. */
    val lg = 16.dp
    /** Section-level relationship spacing. */
    val xl = 24.dp
    /** Major separation between distinct blocks. */
    val xxl = 32.dp
    /** Exceptional large separation (rare). */
    val xxxl = 48.dp

    /** Shared horizontal padding for full-width screens. */
    val screenHorizontal = lg

    /** Padding inside a contained surface (card / section container). */
    val surface: Dp = lg
}