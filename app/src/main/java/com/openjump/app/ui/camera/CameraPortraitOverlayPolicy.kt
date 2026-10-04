package com.openjump.app.ui.camera

import kotlin.math.min

internal data class CameraPortraitOverlayMetrics(
    val horizontalPaddingDp: Float,
    val topOverlayStartClearanceDp: Float,
    val topOverlayEndPaddingDp: Float,
    val topOverlayWidthDp: Float,
    val bottomOverlayWidthDp: Float,
    val recordButtonSizeDp: Float,
    val compactControls: Boolean,
    val quickAthleteTopOffsetDp: Float,
    val experimentalBadgeTopOffsetDp: Float,
)

/**
 * Derives portrait overlay bounds from the current viewport rather than a device profile.
 *
 * The preview remains unconstrained by this policy. These values only reserve enough room for
 * readable, inset-safe controls while allowing the FPS row to scroll when it cannot fit.
 */
internal object CameraPortraitOverlayPolicy {
    private const val MAX_OVERLAY_WIDTH_DP = 560f
    private const val MIN_HORIZONTAL_PADDING_DP = 12f
    private const val MAX_HORIZONTAL_PADDING_DP = 24f
    private const val MIN_TOP_OVERLAY_WIDTH_DP = 176f
    private const val BACK_OUTER_EDGE_DP = 56f
    private const val TOP_OVERLAY_GAP_DP = 8f

    fun calculate(
        widthDp: Float,
        heightDp: Float,
        fontScale: Float,
        hasQuickAthletePicker: Boolean = false,
        hasExperimentalBadge: Boolean = false,
    ): CameraPortraitOverlayMetrics {
        require(widthDp > 0f)
        require(heightDp > 0f)
        require(fontScale > 0f)

        val horizontalPadding = (widthDp * 0.04f).coerceIn(
            MIN_HORIZONTAL_PADDING_DP,
            MAX_HORIZONTAL_PADDING_DP,
        )
        val availableWidth = (widthDp - 2f * horizontalPadding).coerceAtLeast(48f)
        val compactControls = heightDp < 600f || widthDp < 360f || fontScale > 1.3f
        val metadataTopOffset = if (fontScale >= 1.2f) 88f else 56f
        val badgeTopOffset = if (hasQuickAthletePicker && hasExperimentalBadge) {
            metadataTopOffset + 56f
        } else {
            metadataTopOffset
        }
        val topOverlayStartClearance = BACK_OUTER_EDGE_DP + TOP_OVERLAY_GAP_DP
        val topAvailableWidth = (widthDp - topOverlayStartClearance - horizontalPadding)
            .coerceAtLeast(48f)
        val topWidth = min(
            topAvailableWidth,
            min(
                MAX_OVERLAY_WIDTH_DP,
                (widthDp * if (fontScale > 1.2f) 0.9f else 0.82f)
                    .coerceAtLeast(MIN_TOP_OVERLAY_WIDTH_DP),
            ),
        )

        return CameraPortraitOverlayMetrics(
            horizontalPaddingDp = horizontalPadding,
            topOverlayStartClearanceDp = topOverlayStartClearance,
            topOverlayEndPaddingDp = horizontalPadding,
            topOverlayWidthDp = topWidth,
            bottomOverlayWidthDp = min(availableWidth, MAX_OVERLAY_WIDTH_DP),
            recordButtonSizeDp = if (compactControls) 72f else 80f,
            compactControls = compactControls,
            quickAthleteTopOffsetDp = metadataTopOffset,
            experimentalBadgeTopOffsetDp = badgeTopOffset,
        )
    }
}
