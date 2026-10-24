package com.openjump.app.ui.selector

import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateDetectionRequestPolicyTest {
    private fun request(
        frameIndex: Int,
        diameterCm: Double = 45.0,
        timing: VideoTimingDecision = VideoTimingDecision(VideoTimingMode.REAL_TIME),
        sessionGeneration: Long = 1L,
        displayGeneration: Long = 10L,
    ) = PlateDetectionRequestIdentity(
        sessionKey = "session-a",
        videoUri = "content://video/a",
        videoUriObject = "content://video/a",
        frameIndex = frameIndex,
        ptsUs = frameIndex * 10_000L,
        timingDecision = timing,
        sessionPlateDiameterCm = diameterCm,
        sessionGeneration = sessionGeneration,
        displayRequestGeneration = displayGeneration,
    )

    @Test
    fun `editing physical diameter rebases identity without changing reviewed pixels`() {
        val identity = request(4)
        val proposal = PlateCalibrationProposal(
            sessionKey = identity.sessionKey, videoUri = identity.videoUri, generation = 1,
            frameIndex = 4, ptsUs = identity.ptsUs, presentationWidth = 1080, presentationHeight = 1920,
            originalDetection = null, center = com.openjump.app.tracking.ImagePoint(500.0, 900.0),
            diameterPx = 320.0, angleRadians = 0.0, provenance = "USER_CIRCLE", adjusted = true,
            requiresAdjustment = true, requestIdentity = identity,
        )
        val rebased = proposal.withReferenceDiameter(40.0)
        org.junit.Assert.assertEquals(proposal.center, rebased.center)
        org.junit.Assert.assertEquals(proposal.diameterPx, rebased.diameterPx, 0.0)
        org.junit.Assert.assertEquals(proposal.adjusted, rebased.adjusted)
        assertTrue(plateDetectionRequestIsCurrent(rebased.requestIdentity!!, request(4, diameterCm = 40.0)))
        assertFalse(plateDetectionRequestIsCurrent(identity, rebased.requestIdentity))
    }

    @Test
    fun `old A callback is stale after A to B to A even when ordinal returns`() {
        val oldA = request(frameIndex = 0, displayGeneration = 10L)
        val currentA = request(frameIndex = 0, displayGeneration = 12L)

        assertFalse(plateDetectionRequestIsCurrent(oldA, currentA))
        assertTrue(plateDetectionRequestIsCurrent(currentA, currentA))
    }

    @Test
    fun `timing diameter session and URI are part of request identity`() {
        val original = request(frameIndex = 4)
        assertFalse(plateDetectionRequestIsCurrent(original, request(4, diameterCm = 40.0)))
        assertFalse(
            plateDetectionRequestIsCurrent(
                original,
                request(4, timing = VideoTimingDecision(VideoTimingMode.SLOW_MOTION, 4.0)),
            ),
        )
        assertFalse(plateDetectionRequestIsCurrent(original, request(4, sessionGeneration = 2L)))
        assertFalse(
            plateDetectionRequestIsCurrent(
                original,
                original.copy(videoUri = "content://video/b", videoUriObject = "content://video/b"),
            ),
        )
    }
}
