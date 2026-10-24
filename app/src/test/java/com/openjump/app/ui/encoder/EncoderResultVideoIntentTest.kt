package com.openjump.app.ui.encoder

import com.openjump.app.encoder.EncoderRepetition
import com.openjump.app.encoder.EncoderSample
import com.openjump.app.encoder.MovementDirection
import com.openjump.app.encoder.MovementPhase
import com.openjump.app.encoder.ObservationKind
import com.openjump.app.encoder.RepetitionQuality
import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.video.EncoderPlaybackAvailability
import com.openjump.app.video.EncoderPlaybackCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class EncoderResultVideoIntentTest {
    private val samples = listOf(
        sample(frame = 0, ptsUs = 103_000L, physicalTimeUs = 0L),
        sample(frame = 1, ptsUs = 157_000L, physicalTimeUs = 27_000L),
        sample(frame = 2, ptsUs = 311_000L, physicalTimeUs = 104_000L),
        sample(frame = 3, ptsUs = 509_000L, physicalTimeUs = 203_000L),
        sample(frame = 4, ptsUs = 877_000L, physicalTimeUs = 387_000L),
    )
    private val first = repetition(ordinal = 1, start = 0, end = 1)
    private val second = repetition(ordinal = 4, start = 2, end = 4)

    @Test
    fun `normal video entry seeks selected repetition source PTS not physical time`() {
        val entered = EncoderResultVideoIntentState().enterVideo(second, samples)

        assertEquals(EncoderResultVideoSeekIntent(4, 311_000L), entered.pending)
        assertEquals(104_000L, samples[second.startSample].physicalTimeUs)
    }

    @Test
    fun `hidden rapid point and repetition intents coalesce to latest before one available dispatch`() {
        val pending = EncoderResultVideoIntentState()
            .explorePoint(4, 311_000L)
            .explorePoint(4, 509_000L)
            .selectVideoRail(second, samples)
            .explorePoint(4, 877_000L)
            .enterVideo(second, samples)

        val (stillPending, hiddenDispatch) = pending.dispatchWhenReady(
            selectedOrdinal = 4,
            playerAvailable = false,
            requestOutstanding = false,
        )
        assertNull(hiddenDispatch)
        assertEquals(EncoderResultVideoSeekIntent(4, 877_000L), stillPending.pending)

        val (consumed, availableDispatch) = stillPending.dispatchWhenReady(
            selectedOrdinal = 4,
            playerAvailable = true,
            requestOutstanding = false,
        )
        assertEquals(EncoderResultVideoSeekIntent(4, 877_000L), availableDispatch)
        assertNull(consumed.pending)
        val (_, duplicateDispatch) = consumed.dispatchWhenReady(4, playerAvailable = true, requestOutstanding = false)
        assertNull(duplicateDispatch)
    }

    @Test
    fun `changing or removing repetition clears another repetitions pending point`() {
        val pending = EncoderResultVideoIntentState().explorePoint(4, 509_000L)

        assertNull(pending.selectRepetition(1).pending)
        assertNull(pending.clearForRemoval().pending)
        assertEquals(
            EncoderResultVideoSeekIntent(1, 103_000L),
            pending.selectRepetition(1).enterVideo(first, samples).pending,
        )
    }

    @Test
    fun `an intention never creates a confirmed rendered cursor`() {
        val coordinator = EncoderPlaybackCoordinator()
        val intent = EncoderResultVideoIntentState()
            .enterVideo(second, samples)
            .dispatchWhenReady(4, playerAvailable = true, requestOutstanding = false)
            .second
        assertNotNull(intent)
        assertNull(coordinator.state.renderedSourcePtsUs)

        val generation = coordinator.bind(EncoderPlaybackAvailability.AVAILABLE, firstSourcePtsUs = 103_000L)
        coordinator.requestSeek(requireNotNull(intent).sourcePtsUs)
        val emitted = coordinator.consumePendingSeek()
        assertEquals(311_000L, emitted?.sourcePtsUs)
        assertNull(coordinator.state.renderedSourcePtsUs)

        assertEquals(true, coordinator.onFrameRendered(generation, 311_000L))
        assertEquals(311_000L, coordinator.state.renderedSourcePtsUs)
    }

    private fun repetition(ordinal: Int, start: Int, end: Int) = EncoderRepetition(
        ordinal = ordinal,
        startSample = start,
        endSample = end,
        eccentricPhase = MovementPhase(MovementDirection.DOWN, start, start),
        concentricPhase = MovementPhase(MovementDirection.UP, start, end),
        quality = RepetitionQuality.VALID,
        reasons = emptySet(),
        metrics = emptyList(),
    )

    private fun sample(frame: Int, ptsUs: Long, physicalTimeUs: Long) = EncoderSample(
        ordinal = frame,
        frameIndex = frame,
        sourcePtsUs = ptsUs,
        physicalTimeUs = physicalTimeUs,
        rawPixelPoint = ImagePoint(12.0 + frame, 18.0),
        rawMetricPoint = null,
        smoothedMetricPoint = null,
        velocityXMps = null,
        velocityYMps = null,
        confidence = 1.0,
        trackingStatus = TrackingStatus.TRACKING,
        observationKind = ObservationKind.MEASURED,
        fitResidualM = null,
        gapBeforeUs = null,
    )
}
