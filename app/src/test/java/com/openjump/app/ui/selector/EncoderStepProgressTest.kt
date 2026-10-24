package com.openjump.app.ui.selector

import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderStepProgressTest {
    @Test
    fun `calibration takes precedence until confirmed`() {
        assertEquals(
            EncoderProgressStep.CALIBRATE,
            resolveEncoderProgressStep(
                hasCalibration = false,
                trackingPhase = TrackingPhase.COMPLETED,
                hasUsablePoint = true,
            ),
        )
    }

    @Test
    fun `only usable completed or lost tracking reaches review`() {
        listOf(TrackingPhase.COMPLETED, TrackingPhase.LOST).forEach { phase ->
            assertEquals(
                EncoderProgressStep.REVIEW,
                resolveEncoderProgressStep(true, phase, hasUsablePoint = true),
            )
            assertEquals(
                EncoderProgressStep.TRACK,
                resolveEncoderProgressStep(true, phase, hasUsablePoint = false),
            )
        }
        assertEquals(
            EncoderProgressStep.TRACK,
            resolveEncoderProgressStep(true, TrackingPhase.ERROR, hasUsablePoint = true),
        )
    }

    @Test
    fun `compact presentation starts at the accessibility font scale convention`() {
        assertEquals(
            EncoderStepPresentation(EncoderProgressStep.TRACK, compact = false),
            encoderStepPresentationFor(true, TrackingPhase.READY, hasUsablePoint = false, fontScale = 1.29f),
        )
        assertEquals(
            EncoderStepPresentation(EncoderProgressStep.TRACK, compact = true),
            encoderStepPresentationFor(true, TrackingPhase.READY, hasUsablePoint = false, fontScale = 1.3f),
        )
    }
}
