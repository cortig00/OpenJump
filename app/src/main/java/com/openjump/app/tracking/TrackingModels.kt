package com.openjump.app.tracking

import kotlin.math.max

/** Grayscale frame owned by the caller. Pixels are row-major unsigned luminance values. */
data class GrayFrame(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "Frame dimensions must be positive." }
        require(pixels.size == width * height) { "Expected ${width * height} pixels, got ${pixels.size}." }
    }
}

data class ImagePoint(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite()) { "Point coordinates must be finite." }
    }
}

/** Full video dimensions after applying container rotation for presentation. */
data class VideoPresentationGeometry(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0)
    }

    companion object {
        fun fromEncoded(width: Int, height: Int, rotationDegrees: Int): VideoPresentationGeometry {
            require(width > 0 && height > 0)
            val normalized = ((rotationDegrees % 360) + 360) % 360
            require(normalized in setOf(0, 90, 180, 270)) { "Unsupported video rotation: $rotationDegrees" }
            return if (normalized == 90 || normalized == 270) {
                VideoPresentationGeometry(height, width)
            } else {
                VideoPresentationGeometry(width, height)
            }
        }
    }
}

data class ImageRegion(
    val center: ImagePoint,
    val width: Double,
    val height: Double,
) {
    init {
        require(width.isFinite() && height.isFinite() && width > 0.0 && height > 0.0) {
            "Region dimensions must be finite and positive."
        }
    }

    val left: Double get() = center.x - width / 2.0
    val top: Double get() = center.y - height / 2.0
    val right: Double get() = center.x + width / 2.0
    val bottom: Double get() = center.y + height / 2.0

    fun contains(point: ImagePoint, margin: Double = 0.0): Boolean =
        point.x >= left + margin && point.x <= right - margin &&
            point.y >= top + margin && point.y <= bottom - margin

    fun moved(dx: Double, dy: Double): ImageRegion = copy(
        center = ImagePoint(center.x + dx, center.y + dy),
    )

    fun clampedTo(frameWidth: Int, frameHeight: Int): ImageRegion {
        val halfWidth = width / 2.0
        val halfHeight = height / 2.0
        val x = center.x.coerceIn(halfWidth, max(halfWidth, frameWidth - 1.0 - halfWidth))
        val y = center.y.coerceIn(halfHeight, max(halfHeight, frameHeight - 1.0 - halfHeight))
        return copy(center = ImagePoint(x, y))
    }
}

enum class TrackingStatus { TRACKING, UNCERTAIN, LOST }

data class TrackingDiagnostics(
    val attemptedFeatures: Int,
    val validFeatures: Int,
    val inlierFeatures: Int,
    val medianResidualPx: Double,
    val spatialCoverage: Double,
) {
    init {
        require(attemptedFeatures >= 0)
        require(validFeatures in 0..attemptedFeatures)
        require(inlierFeatures in 0..validFeatures)
        require(medianResidualPx.isFinite() && medianResidualPx >= 0.0)
        require(spatialCoverage in 0.0..1.0)
    }

    companion object {
        val EMPTY = TrackingDiagnostics(0, 0, 0, 0.0, 0.0)
    }
}

data class TrackingSample(
    val timestampUs: Long,
    val point: ImagePoint?,
    val confidence: Double,
    val status: TrackingStatus,
) {
    init {
        require(timestampUs >= 0L) { "Timestamp must be non-negative." }
        require(confidence.isFinite() && confidence in 0.0..1.0) { "Confidence must be in 0..1." }
        require(status != TrackingStatus.LOST || point == null) { "A lost sample cannot claim a position." }
        require(status == TrackingStatus.LOST || point != null) { "A measured sample requires a position." }
    }
}

data class TrackingResult(
    val sample: TrackingSample,
    val region: ImageRegion?,
    val featurePoints: List<ImagePoint>,
    val diagnostics: TrackingDiagnostics,
) {
    init {
        require(sample.status != TrackingStatus.LOST || region == null) {
            "A lost result cannot claim a current region."
        }
    }
}

data class TrackingFrameResult(
    val frameIndex: Int,
    val result: TrackingResult,
) {
    init {
        require(frameIndex >= 0)
    }
}
