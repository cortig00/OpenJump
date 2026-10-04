package com.openjump.app.encoder

import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.measurement.MetricScale
import com.openjump.app.tracking.ImagePoint

/** Encoder compatibility facade over the shared metric calibration. */
object MetricCalibrator {
    const val MINIMUM_REFERENCE_PIXELS = MetricScale.MINIMUM_REFERENCE_PIXELS

    fun create(
        frameIndex: Int,
        pointA: ImagePoint,
        pointB: ImagePoint,
        referenceLengthM: Double,
    ): MetricCalibration = MetricScale.create(frameIndex, pointA, pointB, referenceLengthM)

    fun transform(point: ImagePoint, origin: ImagePoint, calibration: MetricCalibration): MetricPoint =
        MetricPoint(
            x = (point.x - origin.x) * calibration.metersPerPixel,
            y = (origin.y - point.y) * calibration.metersPerPixel,
        )
}
