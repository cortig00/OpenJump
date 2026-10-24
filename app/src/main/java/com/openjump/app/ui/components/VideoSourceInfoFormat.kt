package com.openjump.app.ui.components

import java.text.NumberFormat
import java.util.Locale

/**
 * Pure, locale-aware display formatting for [com.openjump.app.video.VideoSourceInfo].
 *
 * Numbers always use the caller's user locale; `Locale.US` is never hardcoded.
 * Unknown data renders as [UNKNOWN] ("—"), never as a fabricated zero; an
 * in-flight size read renders as [LOADING] ("…"), never as an error.
 * Resolution aliases ("720p"/"1080p") apply only to exact encoded dimensions;
 * anything else shows the encoded `W×H` geometry. Dimensions are encoded, never
 * rotation-swapped presentation values: callers pass `index.width/height` as is.
 */
object VideoSourceInfoFormat {

    const val UNKNOWN = "—"
    const val LOADING = "…"

    fun resolutionLabel(width: Int, height: Int): String = when {
        width == 1280 && height == 720 -> "720p"
        width == 1920 && height == 1080 -> "1080p"
        width > 0 && height > 0 -> "$width×$height"
        else -> UNKNOWN
    }

    /**
     * Encoded `W×H` geometry for the detail sheet. The compact summary may
     * use the [resolutionLabel] alias; details always keep the full width so
     * no dimension is ever lost behind the shorthand.
     */
    fun resolutionGeometry(width: Int, height: Int): String = when {
        width > 0 && height > 0 -> "$width×$height"
        else -> UNKNOWN
    }

    /**
     * Normalized codec label. Never leaks raw MIME strings or URIs:
     * undisclosed or unrecognized codecs render as [UNKNOWN].
     */
    fun codecLabel(mimeType: String?): String = when (mimeType?.trim()?.lowercase(Locale.ROOT)) {
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/mp4v-es" -> "MPEG-4"
        "video/x-vnd.on2.vp9", "video/vp9" -> "VP9"
        "video/av01", "video/av1" -> "AV1"
        else -> UNKNOWN
    }

    /** Effective (PTS-median) frame rate with one decimal; invalid stays [UNKNOWN]. */
    fun effectiveFps(locale: Locale, fps: Double?): String {
        if (fps == null || !fps.isFinite() || fps <= 0.0) return UNKNOWN
        return oneDecimal(locale, fps)
    }

    /**
     * Declared (nominal) frame rate: whole values render without decimals
     * (120.0 → "120"), fractional values with one decimal; invalid stays [UNKNOWN].
     */
    fun nominalFps(locale: Locale, fps: Double?): String {
        if (fps == null || !fps.isFinite() || fps <= 0.0) return UNKNOWN
        return if (fps == kotlin.math.floor(fps)) {
            integer(locale, fps.toLong())
        } else {
            oneDecimal(locale, fps)
        }
    }

    /** Decimal megabytes (`bytes / 1e6`); loading shows [LOADING], unknown shows [UNKNOWN]. */
    fun sizeMegabytes(locale: Locale, sizeMegabytesDecimal: Double?, isLoading: Boolean): String {
        if (isLoading) return LOADING
        if (sizeMegabytesDecimal == null || !sizeMegabytesDecimal.isFinite() || sizeMegabytesDecimal < 0.0) {
            return UNKNOWN
        }
        return oneDecimal(locale, sizeMegabytesDecimal)
    }

    /** Declared average video bitrate in Mbps with one decimal; undisclosed stays [UNKNOWN]. */
    fun bitrateMbps(locale: Locale, declaredAverageBitrateBps: Int?): String {
        if (declaredAverageBitrateBps == null || declaredAverageBitrateBps <= 0) return UNKNOWN
        return oneDecimal(locale, declaredAverageBitrateBps / 1_000_000.0)
    }

    fun frameCount(locale: Locale, count: Int): String {
        if (count < 0) return UNKNOWN
        return integer(locale, count.toLong())
    }

    /** Largest PTS gap in milliseconds with one decimal; non-positive stays [UNKNOWN]. */
    fun gapLargestMs(locale: Locale, largestGapUs: Long): String {
        if (largestGapUs <= 0L) return UNKNOWN
        return oneDecimal(locale, largestGapUs / 1_000.0)
    }

    fun hasGaps(gapCount: Int): Boolean = gapCount > 0

    private fun oneDecimal(locale: Locale, value: Double): String =
        NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(value)

    private fun integer(locale: Locale, value: Long): String =
        NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = 0
            maximumFractionDigits = 0
        }.format(value)
}
