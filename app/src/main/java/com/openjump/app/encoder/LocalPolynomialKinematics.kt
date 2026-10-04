package com.openjump.app.encoder

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

object LocalPolynomialKinematics {
    data class Observation(
        val timeUs: Long,
        val point: MetricPoint,
        val confidence: Double,
        val block: Int,
    )

    data class Estimate(
        val point: MetricPoint,
        val velocityX: Double,
        val velocityY: Double,
        val residualM: Double,
    )

    fun windowForFps(effectiveFps: Double?): Int = when {
        effectiveFps == null -> 0
        effectiveFps >= 180.0 -> 19
        effectiveFps >= 90.0 -> 11
        effectiveFps >= 45.0 -> 7
        else -> 5
    }

    fun estimateAll(observations: List<Observation>, windowSize: Int): List<Estimate?> {
        if (windowSize < 5 || windowSize % 2 == 0) return List(observations.size) { null }
        return observations.indices.map { center ->
            val sameBlock = observations.indices.filter { observations[it].block == observations[center].block }
            val selected = sameBlock.sortedBy { abs(it - center) }
                .take(windowSize)
                .sorted()
            estimate(observations, center, selected)
        }
    }

    private fun estimate(
        observations: List<Observation>,
        center: Int,
        selected: List<Int>,
    ): Estimate? {
        if (selected.size < 4) return null
        val centerTime = observations[center].timeUs
        val timeSeconds = selected.map { (observations[it].timeUs - centerTime) / 1_000_000.0 }
        val scale = timeSeconds.maxOf { abs(it) }
        if (!scale.isFinite() || scale <= 0.0) return null

        val normal = Array(4) { DoubleArray(4) }
        val rhsX = DoubleArray(4)
        val rhsY = DoubleArray(4)
        selected.forEachIndexed { localIndex, observationIndex ->
            val observation = observations[observationIndex]
            val z = timeSeconds[localIndex] / scale
            val powers = doubleArrayOf(1.0, z, z * z, z * z * z)
            val weight = observation.confidence.coerceIn(0.01, 1.0).pow(2)
            for (row in 0..3) {
                rhsX[row] += weight * powers[row] * observation.point.x
                rhsY[row] += weight * powers[row] * observation.point.y
                for (column in 0..3) {
                    normal[row][column] += weight * powers[row] * powers[column]
                }
            }
        }
        val coefficientsX = solve(normal, rhsX) ?: return null
        val coefficientsY = solve(normal, rhsY) ?: return null
        val point = MetricPoint(coefficientsX[0], coefficientsY[0])
        val raw = observations[center].point
        return Estimate(
            point = point,
            velocityX = coefficientsX[1] / scale,
            velocityY = coefficientsY[1] / scale,
            residualM = hypot(point.x - raw.x, point.y - raw.y),
        )
    }

    private fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val augmented = Array(4) { row -> DoubleArray(5) { column ->
            if (column < 4) matrix[row][column] else rhs[row]
        } }
        for (pivot in 0..3) {
            var best = pivot
            for (row in pivot + 1..3) {
                if (abs(augmented[row][pivot]) > abs(augmented[best][pivot])) best = row
            }
            if (abs(augmented[best][pivot]) < 1e-10) return null
            if (best != pivot) {
                val temporary = augmented[pivot]
                augmented[pivot] = augmented[best]
                augmented[best] = temporary
            }
            val divisor = augmented[pivot][pivot]
            for (column in pivot..4) augmented[pivot][column] /= divisor
            for (row in 0..3) {
                if (row == pivot) continue
                val factor = augmented[row][pivot]
                for (column in pivot..4) augmented[row][column] -= factor * augmented[pivot][column]
            }
        }
        return DoubleArray(4) { augmented[it][4] }
    }
}
