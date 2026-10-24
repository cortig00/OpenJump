package com.openjump.app.ui.encoder

import com.openjump.app.encoder.EncoderAnalysisConfig
import com.openjump.app.encoder.EncoderRepetition
import com.openjump.app.encoder.MovementDirection
import com.openjump.app.encoder.MovementPhase
import com.openjump.app.encoder.QualityReason
import com.openjump.app.encoder.RepetitionQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderTechnicalAggregatesTest {
    @Test
    fun `version helper exposes the real analysis config version`() {
        val analysis = encoderAggregateFixture(version = 7)
        assertEquals(7, technicalAnalysisVersion(analysis))
        assertEquals(1, technicalAnalysisVersion(encoderAggregateFixture()))
    }

    @Test
    fun `valid repetitions never contribute to reason counts`() {
        val reps = listOf(
            repetition(0, RepetitionQuality.VALID, setOf(QualityReason.LOW_CONFIDENCE)),
            repetition(1, RepetitionQuality.VALID, emptySet()),
        )
        // Even if a VALID rep carried a stale set, the helper only counts non-valid.
        // Production never sets reasons on VALID, so this guards the filter.
        assertTrue(aggregateNonValidReasonCounts(reps.filter { it.quality != RepetitionQuality.VALID }).isEmpty())
        assertEquals(emptyMap<QualityReason, Int>(), aggregateNonValidReasonCounts(reps))
    }

    @Test
    fun `each non-valid repetition counts each distinct reason once and overlaps`() {
        val reps = listOf(
            repetition(0, RepetitionQuality.INVALID, setOf(QualityReason.TRACKING_LOST, QualityReason.INCOMPLETE_PHASE)),
            repetition(1, RepetitionQuality.UNCERTAIN, setOf(QualityReason.TRACKING_LOST)),
            repetition(2, RepetitionQuality.VALID, emptySet()),
            repetition(3, RepetitionQuality.INVALID, setOf(QualityReason.LOW_CONFIDENCE)),
        )
        val counts = aggregateNonValidReasonCounts(reps)
        assertEquals(2, counts[QualityReason.TRACKING_LOST])
        assertEquals(1, counts[QualityReason.INCOMPLETE_PHASE])
        assertEquals(1, counts[QualityReason.LOW_CONFIDENCE])
        // Overlap: one rep with two reasons makes the summed counts (4) exceed non-valid reps (3).
        assertEquals(4, counts.values.sum())
    }

    @Test
    fun `duplicate reasons inside one set are counted once`() {
        // Sets cannot duplicate, but the helper must not multiply by metrics.
        val reps = listOf(
            repetition(0, RepetitionQuality.INVALID, setOf(QualityReason.DISCONTINUITY)),
        )
        assertEquals(mapOf(QualityReason.DISCONTINUITY to 1), aggregateNonValidReasonCounts(reps))
    }

    @Test
    fun `empty repetitions and empty reason sets mean no classified reasons`() {
        assertEquals(emptyMap<QualityReason, Int>(), aggregateNonValidReasonCounts(emptyList()))
        val noReasons = listOf(
            repetition(0, RepetitionQuality.INVALID, emptySet()),
            repetition(1, RepetitionQuality.UNCERTAIN, emptySet()),
        )
        assertEquals(emptyMap<QualityReason, Int>(), aggregateNonValidReasonCounts(noReasons))
    }

    private fun repetition(
        ordinal: Int,
        quality: RepetitionQuality,
        reasons: Set<QualityReason>,
    ): EncoderRepetition = EncoderRepetition(
        ordinal = ordinal,
        startSample = ordinal,
        endSample = ordinal + 1,
        eccentricPhase = MovementPhase(MovementDirection.DOWN, ordinal, ordinal),
        concentricPhase = MovementPhase(MovementDirection.UP, ordinal + 1, ordinal + 1),
        quality = quality,
        reasons = reasons,
        metrics = emptyList(),
    )

    private fun encoderAggregateFixture(version: Int = 1): com.openjump.app.encoder.EncoderAnalysis {
        // Minimal analysis to expose config.version without touching engine formulas.
        return com.openjump.app.encoder.EncoderAnalysis(
            setup = com.openjump.app.encoder.EncoderSetup(com.openjump.app.encoder.EncoderExercise.SQUAT, 40.0),
            timingDecision = com.openjump.app.encoder.VideoTimingDecision(com.openjump.app.encoder.VideoTimingMode.REAL_TIME),
            calibration = com.openjump.app.measurement.MetricCalibration(
                0,
                com.openjump.app.tracking.ImagePoint(0.0, 0.0),
                com.openjump.app.tracking.ImagePoint(100.0, 0.0),
                0.45,
                0.0045,
            ),
            samples = emptyList(),
            repetitions = emptyList(),
            effectiveFps = 60.0,
            smoothingWindowSamples = 5,
            velocityEnterMps = 0.08,
            velocityExitMps = 0.04,
            warnings = emptyList(),
            config = EncoderAnalysisConfig(version = version),
        )
    }
}
