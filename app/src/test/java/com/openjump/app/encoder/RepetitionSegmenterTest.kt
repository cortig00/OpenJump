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
}
