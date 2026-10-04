package com.openjump.app.measurement

import com.openjump.app.tracking.ImagePoint
import kotlin.math.abs
import kotlin.math.hypot

/** Two-point metric scale in full-resolution presentation coordinates. */
data class MetricCalibration(
    val frameIndex: Int,
    val pointA: ImagePoint,
    val pointB: ImagePoint,
    val referenceLengthM: Double,
    val metersPerPixel: Double,
) {
    init {
        require(frameIndex >= 0)
        require(referenceLengthM.isFinite() && referenceLengthM > 0.0)
        require(metersPerPixel.isFinite() && metersPerPixel > 0.0)
    }
}

object MetricScale {
    const val MINIMUM_REFERENCE_PIXELS = 8.0

    fun create(
        frameIndex: Int,
        pointA: ImagePoint,
        pointB: ImagePoint,
        referenceLengthM: Double,
    ): MetricCalibration {
        require(referenceLengthM.isFinite() && referenceLengthM > 0.0) {
            "La longitud de referencia debe ser positiva."
        }
        val distancePixels = hypot(pointB.x - pointA.x, pointB.y - pointA.y)
        require(distancePixels >= MINIMUM_REFERENCE_PIXELS) {
            "Los puntos de calibración están demasiado juntos."
        }
        return MetricCalibration(
            frameIndex = frameIndex,
            pointA = pointA,
            pointB = pointB,
            referenceLengthM = referenceLengthM,
            metersPerPixel = referenceLengthM / distancePixels,
        )
    }

    /** Absolute progress from [start] to [end] projected onto the calibrated A→B floor axis. */
    fun projectedDistanceMeters(
        start: ImagePoint,
        end: ImagePoint,
        calibration: MetricCalibration,
    ): Double {
        val axisX = calibration.pointB.x - calibration.pointA.x
        val axisY = calibration.pointB.y - calibration.pointA.y
        val axisLengthPixels = hypot(axisX, axisY)
        require(axisLengthPixels >= MINIMUM_REFERENCE_PIXELS) {
            "La referencia de calibración no es válida."
        }
        val projectedPixels = abs(
            (end.x - start.x) * axisX / axisLengthPixels +
                (end.y - start.y) * axisY / axisLengthPixels,
        )
        val distanceMeters = projectedPixels * calibration.metersPerPixel
        require(distanceMeters.isFinite() && distanceMeters > 0.0) {
            "La salida y el talón no producen una distancia válida sobre el eje calibrado."
        }
        return distanceMeters
    }
}
