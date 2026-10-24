package com.openjump.app.ui.encoder

import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderRepetition
import com.openjump.app.encoder.MovementDirection
import com.openjump.app.encoder.MovementPhase
import com.openjump.app.encoder.RepetitionQuality
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.MetricCalibrator
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.video.VideoFrameIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderResultStateTest {
    @Test
    fun `removal is allowed only for an unsaved active result`() {
        val analysis = baseAnalysis().copy(repetitions = repetitions(3))
        val active = EncoderResultState.Content(analysis = analysis)
        val saving = active.copy(saving = true)
        val saved = active.copy(savedId = 7L)

        assertTrue(active.canRemoveRepetition(1))
        assertFalse(saving.canRemoveRepetition(1))
        assertFalse(saved.canRemoveRepetition(1))
        assertFalse(active.canRemoveRepetition(99))
    }

    @Test
    fun `selection after removal prefers successor then previous and clears when empty`() {
        val repetitions = repetitions(3)

        assertEquals(0, selectionAfterRemoval(repetitions, 0))
        assertEquals(1, selectionAfterRemoval(repetitions, 1))
        assertEquals(1, selectionAfterRemoval(repetitions, 2))
        assertNull(selectionAfterRemoval(repetitions, 99))
        assertNull(selectionAfterRemoval(repetitions(1), 0))
    }

    @Test
    fun `save failure keeps content analysis and exposes contextual error`() {
        val analysis = baseAnalysis()
        val index = VideoFrameIndex(longArrayOf(10L, 20L), 20L, 100, 100, 0, "video/avc")
        val content = EncoderResultState.Content(
            analysis = analysis,
            videoUri = "content://video/live",
            videoIndex = index,
            saving = true,
        )

        val failed = content.withSaveError("Testing session is no longer active")

        assertSame(analysis, failed.analysis)
        assertEquals("content://video/live", failed.videoUri)
        assertSame(index, failed.videoIndex)
        assertEquals(false, failed.saving)
        assertEquals("Testing session is no longer active", failed.saveError)
    }

    private fun baseAnalysis(): EncoderAnalysis = EncoderAnalysis(
        setup = EncoderSetup(EncoderExercise.SQUAT, 80.0),
        timingDecision = VideoTimingDecision(VideoTimingMode.REAL_TIME),
        calibration = MetricCalibrator.create(
            frameIndex = 0,
            pointA = ImagePoint(0.0, 0.0),
            pointB = ImagePoint(100.0, 0.0),
            referenceLengthM = 0.5,
        ),
        samples = emptyList(),
        repetitions = emptyList(),
        effectiveFps = 120.0,
        smoothingWindowSamples = 3,
        velocityEnterMps = null,
        velocityExitMps = null,
        warnings = emptyList(),
    )

    private fun repetitions(count: Int): List<EncoderRepetition> = (0 until count).map { ordinal ->
        EncoderRepetition(
            ordinal = ordinal,
            startSample = ordinal,
            endSample = ordinal + 1,
            eccentricPhase = MovementPhase(MovementDirection.DOWN, ordinal, ordinal),
            concentricPhase = MovementPhase(MovementDirection.UP, ordinal + 1, ordinal + 1),
            quality = RepetitionQuality.VALID,
            reasons = emptySet(),
            metrics = emptyList(),
        )
    }
}
