package com.openjump.app.ui.selector

import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingDiagnostics
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.TrackingResult
import com.openjump.app.tracking.TrackingSample
import com.openjump.app.tracking.TrackingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderReviewPolicyTest {
    private fun result(frame: Int, point: ImagePoint? = ImagePoint(10.0, 10.0)) = TrackingFrameResult(
        frame,
        TrackingResult(
            TrackingSample(frame.toLong(), point, 1.0, if (point == null) TrackingStatus.LOST else TrackingStatus.TRACKING),
            null,
            emptyList(),
            TrackingDiagnostics.EMPTY,
        ),
    )

    @Test fun `completed and partial lost with canonical point are reviewable`() {
        listOf(TrackingPhase.COMPLETED, TrackingPhase.LOST).forEach { phase ->
            val samples = listOf(result(0), result(1, null))
            assertTrue(EncoderReviewPolicy.resolve(EncoderReviewInput(phase, samples, samples, false)).visible)
        }
    }

    @Test fun `seed lost without a point and non-terminal phases stay out of review`() {
        val empty = listOf(result(0, null))
        assertFalse(EncoderReviewPolicy.resolve(EncoderReviewInput(TrackingPhase.LOST, empty, empty, false)).visible)
        val current = listOf(result(0))
        listOf(TrackingPhase.IDLE, TrackingPhase.READY, TrackingPhase.PROCESSING, TrackingPhase.ERROR).forEach { phase ->
            assertFalse(EncoderReviewPolicy.resolve(EncoderReviewInput(phase, current, current, false)).visible)
        }
        assertFalse(EncoderReviewPolicy.resolve(EncoderReviewInput(TrackingPhase.COMPLETED, current, empty, false)).visible)
        assertFalse(EncoderReviewPolicy.resolve(EncoderReviewInput(TrackingPhase.COMPLETED, current, current, true)).canAnalyze)
    }

    @Test fun `completion is ordered and idempotent`() {
        val samples = listOf(result(0))
        val calls = mutableListOf<String>()
        val coordinator = EncoderTrackingCompletionCoordinator()
        val first = coordinator.complete("session", 3, 3, TrackingPhase.LOST, samples, true, { calls += "commit" }) { calls += "publish" }
        val second = coordinator.complete("session", 3, 3, TrackingPhase.LOST, samples, true, { calls += "commit" }) { calls += "publish" }
        assertTrue(first)
        assertFalse(second)
        assertTrue(calls == listOf("commit", "publish"))
    }

    @Test fun `stale generation and empty terminal cannot commit`() {
        val coordinator = EncoderTrackingCompletionCoordinator()
        val sample = listOf(result(0))
        assertFalse(coordinator.complete("session", 1, 2, TrackingPhase.COMPLETED, sample, true, {}, {}))
        assertFalse(coordinator.complete("session", 2, 2, TrackingPhase.LOST, listOf(result(0, null)), true, {}, {}))
        assertFalse(
            coordinator.complete(
                sessionKey = "other-session",
                expectedGeneration = 1,
                currentGeneration = 1,
                phase = TrackingPhase.COMPLETED,
                results = sample,
                calibrationConfirmed = true,
                commit = {},
                publish = {},
                currentSessionKey = "current-session",
            ),
        )
    }
}
