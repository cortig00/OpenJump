package com.openjump.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material 3 typography roles, tuned slightly for a compact sports-instrument.
 * Hierarchy is produced by size/weight/opacity, not by making everything bold.
 */
internal val OpenJumpTypography = Typography(
    displayLarge = TextStyle(fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

/**
 * OpenJump semantic text roles. Use these for product language and metrics so
 * that hierarchy is consistent ("VALUE -> CONTEXT -> LABEL -> SECONDARY").
 *
 * Large numeric values use tabular figures (via fontFeatureSettings) for stable
 * alignment; metric units are intentionally subordinate to their value.
 */
object OpenJumpTypes {
    /** Top-level screen title. */
    val ScreenTitle = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)

    /** Section titles inside a screen. */
    val SectionTitle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)

    /** Default body copy. */
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)

    /** Supporting / contextual text. */
    val Secondary = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

    /** Small labels / captions. */
    val Label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)

    /** Hero numeric result. Keep dominant; rely on font scaling for overflow. */
    val MetricHero =
        TextStyle(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")

    /** Secondary / group numeric value. */
    val MetricValue =
        TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")

    /** Unit, visually subordinate to the value. */
    val MetricUnit = TextStyle(fontSize = 14.sp, lineHeight = 18.sp)

    /** Metric label (small, above / beside a value). */
    val MetricLabel =
        TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)

    /** Timestamps / technical values. */
    val Timestamp = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
}