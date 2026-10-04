package com.openjump.app.tracking

import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

internal data class FeatureMotion(
    val from: ImagePoint,
    val to: ImagePoint,
)

internal data class MotionEstimate(
    val dx: Double,
    val dy: Double,
    val confidence: Double,
    val inliers: List<FeatureMotion>,
    val diagnostics: TrackingDiagnostics,
)

/** Robust translation estimate. Every confidence component is an observable tracker diagnostic. */
internal object RobustMotionEstimator {

    fun estimate(
        attemptedFeatures: Int,
        motions: List<FeatureMotion>,
        region: ImageRegion,
        minimumSupport: Int,
    ): MotionEstimate? {
        if (attemptedFeatures <= 0 || motions.isEmpty()) return null

        val dx = median(motions.map { it.to.x - it.from.x })
        val dy = median(motions.map { it.to.y - it.from.y })
        val residuals = motions.map { motion ->
            hypot((motion.to.x - motion.from.x) - dx, (motion.to.y - motion.from.y) - dy)
        }
        val medianResidual = median(residuals)
        val mad = median(residuals.map { kotlin.math.abs(it - medianResidual) })
        val maximumCoherentResidual = maxOf(1.5, min(region.width, region.height) * 0.08)
        val inlierThreshold = min(
            maximumCoherentResidual,
            maxOf(1.5, medianResidual + 2.5 * maxOf(mad, 0.25)),
        )
        val inliers = motions.filterIndexed { index, _ -> residuals[index] <= inlierThreshold }
        if (inliers.isEmpty()) return null

        val robustDx = median(inliers.map { it.to.x - it.from.x })
        val robustDy = median(inliers.map { it.to.y - it.from.y })
        val inlierResidual = median(inliers.map { motion ->
            hypot(
                (motion.to.x - motion.from.x) - robustDx,
                (motion.to.y - motion.from.y) - robustDy,
            )
        })
        val coverage = spatialCoverage(inliers.map { it.from }, region)

        val supportScore = (inliers.size.toDouble() / minimumSupport).coerceIn(0.0, 1.0)
        val survivalScore = (motions.size.toDouble() / attemptedFeatures).coerceIn(0.0, 1.0)
        val inlierScore = (inliers.size.toDouble() / motions.size).coerceIn(0.0, 1.0)
        val coherenceScore = (1.0 - inlierResidual / maximumCoherentResidual).coerceIn(0.0, 1.0)
        // Covering 15% of the ROI is enough to avoid trusting a tiny, local cluster.
        val coverageScore = (coverage / 0.15).coerceIn(0.0, 1.0)
        val confidence = (
            supportScore * survivalScore * inlierScore * coherenceScore * coverageScore
            ).coerceIn(0.0, 1.0).pow(1.0 / 5.0)

        return MotionEstimate(
            dx = robustDx,
            dy = robustDy,
            confidence = confidence,
            inliers = inliers,
            diagnostics = TrackingDiagnostics(
                attemptedFeatures = attemptedFeatures,
                validFeatures = motions.size,
                inlierFeatures = inliers.size,
                medianResidualPx = inlierResidual,
                spatialCoverage = coverage,
            ),
        )
    }

    fun spatialCoverage(points: List<ImagePoint>, region: ImageRegion): Double {
        if (points.size < 2) return 0.0
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val area = (maxX - minX).coerceAtLeast(0.0) * (maxY - minY).coerceAtLeast(0.0)
        return (area / (region.width * region.height)).coerceIn(0.0, 1.0)
    }

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }
}
