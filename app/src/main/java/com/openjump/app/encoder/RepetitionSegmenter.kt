package com.openjump.app.encoder

import kotlin.math.abs
import kotlin.math.max

object RepetitionSegmenter {
    data class StationaryInterval(val startSample: Int, val endSample: Int)

    data class Segmentation(
        val repetitions: List<Pair<MovementPhase, MovementPhase>>,
        val stationaryIntervals: List<StationaryInterval>,
        val velocityEnterMps: Double,
        val velocityExitMps: Double,
    )

    private data class Run(
        val direction: MovementDirection,
        val start: Int,
        val end: Int,
    )

    fun segment(
        exercise: EncoderExercise,
        samples: List<EncoderSample>,
        config: EncoderAnalysisConfig,
    ): Segmentation {
        val velocities = samples.mapNotNull { it.verticalVelocityMps }
        if (velocities.isEmpty()) return Segmentation(
            emptyList(),
            emptyList(),
            config.minimumVelocityMps,
            config.minimumVelocityMps * config.exitRatio,
        )
        val lowMotionLimit = percentile(velocities.map(::abs), 0.25)
        val lowMotion = velocities.filter { abs(it) <= lowMotionLimit }
        val sigmaNoise = 1.4826 * mad(lowMotion)
        val enter = max(config.minimumVelocityMps, config.noiseMultiplier * sigmaNoise)
        val exit = enter * config.exitRatio
        val medianDelta = medianLong(
            samples.mapNotNull { it.physicalTimeUs }.zipWithNext { a, b -> b - a },
        ) ?: config.minimumDirectionHoldUs
        val holdUs = max(config.minimumDirectionHoldUs, 2L * medianDelta)
        val runs = directionalRuns(samples, enter, exit, holdUs)
            .filter { phaseIsLargeEnough(it, samples, config) }
        val stationary = stationaryIntervals(samples, exit, config.stationaryDurationUs)

        val expectedFirst = if (exercise.eccentricFirst) MovementDirection.DOWN else MovementDirection.UP
        val expectedSecond = if (exercise.eccentricFirst) MovementDirection.UP else MovementDirection.DOWN
        val pairs = mutableListOf<Pair<MovementPhase, MovementPhase>>()
        var index = 0
        while (index < runs.size - 1) {
            val first = runs[index]
            val second = runs[index + 1]
            if (first.direction == expectedFirst && second.direction == expectedSecond) {
                val firstPhase = MovementPhase(first.direction, first.start, first.end)
                val secondPhase = MovementPhase(second.direction, second.start, second.end)
                pairs += if (exercise.eccentricFirst) {
                    firstPhase to secondPhase
                } else {
                    secondPhase to firstPhase
                }
                index += 2
            } else {
                index += 1
            }
        }
        return Segmentation(pairs, stationary, enter, exit)
    }

    private fun stationaryIntervals(
        samples: List<EncoderSample>,
        velocityExitMps: Double,
        minimumDurationUs: Long,
    ): List<StationaryInterval> {
        val result = mutableListOf<StationaryInterval>()
        var start = -1
        fun close(end: Int) {
            if (start < 0 || end < start) return
            val startTime = samples[start].physicalTimeUs
            val endTime = samples[end].physicalTimeUs
            val positions = samples.subList(start, end + 1).mapNotNull { it.verticalPositionM }
            if (startTime != null && endTime != null && endTime - startTime >= minimumDurationUs &&
                positions.isNotEmpty() && positions.max() - positions.min() <= 0.01) {
                result += StationaryInterval(start, end)
            }
            start = -1
        }
        samples.indices.forEach { index ->
            val velocity = samples[index].verticalVelocityMps
            val stationary = velocity != null && abs(velocity) <= velocityExitMps
            if (stationary && start < 0) start = index
            if (!stationary && start >= 0) close(index - 1)
        }
        if (start >= 0) close(samples.lastIndex)
        return result
    }

    /** Returned pair is always eccentric to concentric, independent of exercise order. */
    private fun directionalRuns(
        samples: List<EncoderSample>,
        enter: Double,
        exit: Double,
        holdUs: Long,
    ): List<Run> {
        val runs = mutableListOf<Run>()
        var current: MovementDirection? = null
        var currentStart = -1
        var lastActive = -1
        var candidate: MovementDirection? = null
        var candidateStart = -1

        fun directionAt(index: Int): MovementDirection? {
            val velocity = samples[index].verticalVelocityMps ?: return null
            return when {
                velocity >= enter -> MovementDirection.UP
                velocity <= -enter -> MovementDirection.DOWN
                else -> null
            }
        }

        for (index in samples.indices) {
            val time = samples[index].physicalTimeUs ?: continue
            val strong = directionAt(index)
            if (current == null) {
                if (strong == null) {
                    candidate = null
                    continue
                }
                if (candidate != strong) {
                    candidate = strong
                    candidateStart = index
                }
                val startTime = samples[candidateStart].physicalTimeUs ?: time
                if (time - startTime >= holdUs) {
                    current = strong
                    currentStart = candidateStart
                    lastActive = index
                    candidate = null
                }
                continue
            }

            val velocity = samples[index].verticalVelocityMps
            if (velocity != null && ((current == MovementDirection.UP && velocity >= exit) ||
                    (current == MovementDirection.DOWN && velocity <= -exit))) {
                lastActive = index
            }

            if (strong == null || strong == current) {
                candidate = null
                continue
            }
            if (candidate != strong) {
                candidate = strong
                candidateStart = index
            }
            val candidateTime = samples[candidateStart].physicalTimeUs ?: time
            if (time - candidateTime >= holdUs) {
                if (lastActive >= currentStart) runs += Run(current, currentStart, lastActive)
                current = strong
                currentStart = candidateStart
                lastActive = index
                candidate = null
            }
        }
        if (current != null && lastActive >= currentStart) runs += Run(current, currentStart, lastActive)
        return runs
    }

    private fun phaseIsLargeEnough(
        run: Run,
        samples: List<EncoderSample>,
        config: EncoderAnalysisConfig,
    ): Boolean {
        val startTime = samples[run.start].physicalTimeUs ?: return false
        val endTime = samples[run.end].physicalTimeUs ?: return false
        val startPosition = samples[run.start].verticalPositionM ?: return false
        val endPosition = samples[run.end].verticalPositionM ?: return false
        return endTime - startTime >= config.minimumPhaseDurationUs &&
            abs(endPosition - startPosition) >= config.minimumPhaseDisplacementM
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[((sorted.lastIndex * fraction).toInt()).coerceIn(0, sorted.lastIndex)]
    }

    private fun mad(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val center = median(values)
        return median(values.map { abs(it - center) })
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }

    private fun medianLong(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2L
    }
}
