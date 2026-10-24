package com.openjump.app.ui.selector

import com.openjump.app.encoder.PlateDetectionMode
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateCalibrationReviewPolicyTest {
    private fun proposal(requiresAdjustment: Boolean, adjusted: Boolean) = PlateCalibrationProposal(
        sessionKey = "session",
        videoUri = "content://video",
        generation = 4L,
        frameIndex = 2,
        ptsUs = 66_000L,
        presentationWidth = 1080,
        presentationHeight = 1920,
        originalDetection = null,
        center = ImagePoint(540.0, 960.0),
        diameterPx = 320.0,
        angleRadians = 0.0,
        provenance = PlateDetectionMode.ASSISTED.name,
        adjusted = adjusted,
        requiresAdjustment = requiresAdjustment,
    )

    @Test
    fun `assisted provisional proposal cannot be confirmed before adjustment`() {
        assertFalse(
            PlateDetectionUiState(
                phase = PlateDetectionPhase.REVIEW,
                proposal = proposal(requiresAdjustment = true, adjusted = false),
            ).canConfirm,
        )
    }

    @Test
    fun `accepted untouched detector proposal can be confirmed in review`() {
        assertTrue(
            PlateDetectionUiState(
                phase = PlateDetectionPhase.REVIEW,
                proposal = proposal(requiresAdjustment = false, adjusted = false),
            ).canConfirm,
        )
    }

    @Test
    fun `adjustment makes a rejected proposal confirmable without detector provenance`() {
        assertTrue(
            PlateDetectionUiState(
                phase = PlateDetectionPhase.REVIEW,
                proposal = proposal(requiresAdjustment = true, adjusted = true),
            ).canConfirm,
        )
    }

    @Test
    fun `review confirm requires the effective FIT mapper`() {
        val fit = VideoCoordinateMapper(
            VideoPresentationGeometry(1000, 1000),
            ViewportSize(500.0, 500.0),
            VideoScaleMode.FIT,
        )
        val crop = VideoCoordinateMapper(
            VideoPresentationGeometry(1000, 1000),
            ViewportSize(500.0, 500.0),
            VideoScaleMode.CROP,
        )
        assertTrue(isPlateReviewMapperReady(PlateDetectionPhase.REVIEW, fit))
        assertFalse(isPlateReviewMapperReady(PlateDetectionPhase.REVIEW, crop))
        assertTrue(isPlateReviewMapperReady(PlateDetectionPhase.DETECTED, null))
    }

    @Test
    fun `confirmation rejects nonfinite angle and circle outside presentation bounds`() {
        val invalidAngle = proposal(false, true).copy(angleRadians = Double.NaN)
        val outside = proposal(false, true).copy(center = ImagePoint(20.0, 20.0), diameterPx = 320.0)
        assertFalse(PlateDetectionUiState(PlateDetectionPhase.REVIEW, proposal = invalidAngle).canConfirm)
        assertFalse(PlateDetectionUiState(PlateDetectionPhase.REVIEW, proposal = outside).canConfirm)
    }
}
