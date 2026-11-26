package com.openjump.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
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
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
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
    /** Top-level screen title (aligned with M3 titleLarge). */
    val ScreenTitle = OpenJumpTypography.titleLarge

    /** Section titles inside a screen (aligned with M3 titleMedium). */
    val SectionTitle = OpenJumpTypography.titleMedium

    /** Default body copy (aligned with M3 bodyLarge). */
    val Body = OpenJumpTypography.bodyLarge

    /** Supporting / contextual text. */
    val Secondary = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

    /** Small labels / captions (aligned with M3 labelMedium). */
    val Label = OpenJumpTypography.labelMedium

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

    /**
     * Numeric timestamps / technical values use the sans family with tabular
     * figures (`tnum`) so dates, clocks and detail values align without the
     * three-family mix. Monospace is reserved for documented legal/code blocks
     * only (for example the license text in `LicenseScreen`); result values,
     * frame captions and detail rows must use this token instead of ad-hoc
     * `FontFamily.Monospace`.
     */
    val Timestamp = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = "tnum",
    )
}