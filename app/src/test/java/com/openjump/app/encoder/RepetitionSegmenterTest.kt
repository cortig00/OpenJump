package com.openjump.app.encoder

import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepetitionSegmenterTest {
    @Test
    fun `explicit stationary intervals and stall do not invent extra reps`() {
        val samples = mutableListOf<EncoderSample>()
        var position = 0.0
        fun append(count: Int, velocity: Double) {
            repeat(count) {
                position += velocity / 120.0
                val ordinal = samples.size
                samples += EncoderSample(
                    ordinal = ordinal,
                    frameIndex = ordinal,
                    sourcePtsUs = ordinal * 8_333L,
                    physicalTimeUs = ordinal * 8_333L,
                    rawPixelPoint = ImagePoint(0.0, 0.0),
                    rawMetricPoint = MetricPoint(0.0, position),
                    smoothedMetricPoint = MetricPoint(0.0, position),
                    velocityXMps = 0.0,
                    velocityYMps = velocity,
                    confidence = 1.0,
                    trackingStatus = TrackingStatus.TRACKING,
                    observationKind = ObservationKind.MEASURED,
                    fitResidualM = 0.0,
                    gapBeforeUs = if (ordinal == 0) null else 8_333L,
                )
            }
        }
        append(40, 0.0)
        append(35, 0.8)
        append(12, 0.0) // stall breve dentro del ascenso
        append(35, 0.8)
        append(40, 0.0)
        append(70, -0.8)
        append(40, 0.0)

        val result = RepetitionSegmenter.segment(
            EncoderExercise.DEADLIFT,
            samples,
            EncoderAnalysisConfig(),
        )

        assertEquals(1, result.repetitions.size)
        assertTrue(result.stationaryIntervals.size >= 2)
        assertTrue(result.repetitions.single().second.startSample < result.repetitions.single().first.startSample)
    }

    @Test
    fun `continuous multi-rep set without pauses still detects fatigued slow reps`() {
        // Simulate 10 continuous bench press reps with zero pause between reps where concentric
        // velocity fatigues from 0.65 m/s down to 0.18 m/s (realistic failure set).
        val samples = mutableListOf<EncoderSample>()
        var position = 0.35
        val dt = 1.0 / 60.0
        var idx = 0
        fun addPhase(durationSec: Double, displacementM: Double) {
            val steps = (durationSec * 60.0).toInt().coerceAtLeast(1)
            for (s in 0 until steps) {
                val u = (s + 0.5) / steps
                val v = (kotlin.math.PI / 2.0) * (displacementM / durationSec) * kotlin.math.sin(kotlin.math.PI * u)
                position += v * dt
                samples += EncoderSample(
                    ordinal = idx,
                    frameIndex = idx,
                    sourcePtsUs = (idx * 16_667L),
                    physicalTimeUs = (idx * 16_667L),
                    rawPixelPoint = ImagePoint(0.0, 0.0),
                    rawMetricPoint = MetricPoint(0.0, position),
                    smoothedMetricPoint = MetricPoint(0.0, position),
                    velocityXMps = 0.0,
                    velocityYMps = v,
                    confidence = 1.0,
                    trackingStatus = TrackingStatus.TRACKING,
                    observationKind = ObservationKind.MEASURED,
                    fitResidualM = 0.0,
                    gapBeforeUs = if (idx == 0) null else 16_667L,
                )
                idx++
            }
        }
        val concentricDurations = listOf(0.55, 0.58, 0.62, 0.68, 0.75, 0.85, 1.00, 1.20, 1.50, 1.90)
        for (concDur in concentricDurations) {
            addPhase(durationSec = 0.70, displacementM = -0.35)
            addPhase(durationSec = concDur, displacementM = 0.35)
        }

        val result = RepetitionSegmenter.segment(
            EncoderExercise.BENCH_PRESS,
            samples,
            EncoderAnalysisConfig(),
        )

        assertTrue("vEnter (${result.velocityEnterMps}) must stay low on continuous sets", result.velocityEnterMps <= 0.10)
        assertEquals("All 10 reps including slow fatigued reps must be detected", 10, result.repetitions.size)
    }
}
