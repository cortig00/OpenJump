package com.openjump.app.ui.selector

import com.openjump.app.encoder.PlateDetection
import com.openjump.app.encoder.PlateDetectionMode
import com.openjump.app.encoder.PlateDetectionSource
import com.openjump.app.tracking.ImagePoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED regression for genuine oblique plates.
 *
 * A CONTOUR_ELLIPSE detection with perspective below the automatic-calibration
 * threshold (but above the detection floor) is a genuine oblique view, not an
 * uncertain provisional proposal. Policy must let the user explicitly confirm
 * it untouched (requiresAdjustment=true, adjusted=false) with a precision
 * warning, while provisional/gradient proposals stay blocked untouched.
 */
class PlateObliqueConfirmPolicyTest {
    private fun obliqueDetection() = PlateDetection(
        center = ImagePoint(540.0, 960.0),
        diameterPx = 320.0,
        confidence = 0.85,
        perspectiveRatio = 0.65,
        mode = PlateDetectionMode.AUTO,
        ellipseMajorAxisPx = 340.0,
        ellipseMinorAxisPx = 221.0,
        ellipseAngleRadians = 0.0,
        autoCalibrationAccepted = false,
        source = PlateDetectionSource.CONTOUR_ELLIPSE,
    )

    private fun proposalFor(
        detection: PlateDetection?,
        provenance: String?,
        requiresAdjustment: Boolean,
        adjusted: Boolean,
    ) = PlateCalibrationProposal(
        sessionKey = "session",
        videoUri = "content://video",
        generation = 4L,
        frameIndex = 2,
        ptsUs = 66_000L,
        presentationWidth = 1080,
        presentationHeight = 1920,
        originalDetection = detection,
        center = ImagePoint(540.0, 960.0),
        diameterPx = 320.0,
        angleRadians = 0.0,
        provenance = provenance,
        adjusted = adjusted,
        requiresAdjustment = requiresAdjustment,
    )

    @Test
    fun `genuine oblique contour ellipse can be confirmed untouched with warning`() {
        val proposal = proposalFor(
            detection = obliqueDetection(),
            provenance = PlateDetectionSource.CONTOUR_ELLIPSE.name,
            requiresAdjustment = true,
            adjusted = false,
        )
        assertTrue(
            PlateDetectionUiState(
                phase = PlateDetectionPhase.REVIEW,
                proposal = proposal,
            ).canConfirm,
        )
    }

    @Test
    fun `accepted untouched detector proposal stays confirmable`() {
        val accepted = obliqueDetection().copy(perspectiveRatio = 0.9, autoCalibrationAccepted = true)
        val proposal = proposalFor(
            detection = accepted,
            provenance = PlateDetectionSource.CONTOUR_ELLIPSE.name,
            requiresAdjustment = false,
            adjusted = false,
        )
        assertTrue(
            PlateDetectionUiState(phase = PlateDetectionPhase.REVIEW, proposal = proposal).canConfirm,
        )
    }

    @Test
    fun `provisional user circle untouched stays blocked`() {
        val proposal = proposalFor(
            detection = null,
            provenance = "USER_CIRCLE",
            requiresAdjustment = true,
            adjusted = false,
        )
        assertFalse(
            PlateDetectionUiState(phase = PlateDetectionPhase.REVIEW, proposal = proposal).canConfirm,
        )
    }

    @Test
    fun `assisted tap provisional untouched stays blocked`() {
        val proposal = proposalFor(
            detection = null,
            provenance = "ASSISTED_TAP",
            requiresAdjustment = true,
            adjusted = false,
        )
        assertFalse(
            PlateDetectionUiState(phase = PlateDetectionPhase.REVIEW, proposal = proposal).canConfirm,
        )
    }

    @Test
    fun `gradient circle fallback untouched stays blocked`() {
        val gradient = PlateDetection(
            center = ImagePoint(540.0, 960.0),
            diameterPx = 320.0,
            confidence = 0.7,
            perspectiveRatio = 0.0,
            mode = PlateDetectionMode.ASSISTED,
            ellipseMajorAxisPx = 320.0,
            ellipseMinorAxisPx = 320.0,
            ellipseAngleRadians = 0.0,
            autoCalibrationAccepted = false,
            source = PlateDetectionSource.GRADIENT_CIRCLE,
        )
        val proposal = proposalFor(
            detection = gradient,
            provenance = PlateDetectionSource.GRADIENT_CIRCLE.name,
            requiresAdjustment = true,
            adjusted = false,
        )
        assertFalse(
            PlateDetectionUiState(phase = PlateDetectionPhase.REVIEW, proposal = proposal).canConfirm,
        )
    }

    @Test
    fun `oblique out of bounds stays blocked`() {
        val proposal = proposalFor(
            detection = obliqueDetection(),
            provenance = PlateDetectionSource.CONTOUR_ELLIPSE.name,
            requiresAdjustment = true,
            adjusted = false,
        ).copy(center = ImagePoint(20.0, 20.0))
        assertFalse(
            PlateDetectionUiState(phase = PlateDetectionPhase.REVIEW, proposal = proposal).canConfirm,
        )
    }

    @Test
    fun `oblique with nonfinite angle stays blocked`() {
        val proposal = proposalFor(
            detection = obliqueDetection(),
            provenance = PlateDetectionSource.CONTOUR_ELLIPSE.name,
            requiresAdjustment = true,
            adjusted = false,
        ).copy(angleRadians = Double.NaN)
        assertFalse(
            PlateDetectionUiState(phase = PlateDetectionPhase.REVIEW, proposal = proposal).canConfirm,
        )
    }

    @Test
    fun `oblique while busy stays blocked`() {
        val proposal = proposalFor(
            detection = obliqueDetection(),
            provenance = PlateDetectionSource.CONTOUR_ELLIPSE.name,
            requiresAdjustment = true,
            adjusted = false,
        )
        assertFalse(
            PlateDetectionUiState(
                phase = PlateDetectionPhase.REVIEW,
                proposal = proposal,
                confirming = true,
            ).canConfirm,
        )
    }

    @Test
    fun `oblique override helper accepts only genuine untouched contour ellipse`() {
        val genuine = proposalFor(
            detection = obliqueDetection(),
            provenance = PlateDetectionSource.CONTOUR_ELLIPSE.name,
            requiresAdjustment = true,
            adjusted = false,
        )
        assertTrue(isObliquePlateOverrideEligible(genuine))
        assertFalse(
            isObliquePlateOverrideEligible(
                proposalFor(null, "USER_CIRCLE", requiresAdjustment = true, adjusted = false),
            ),
        )
        val gradient = PlateDetection(
            center = ImagePoint(540.0, 960.0),
            diameterPx = 320.0,
            confidence = 0.7,
            perspectiveRatio = 0.0,
            mode = PlateDetectionMode.ASSISTED,
            ellipseMajorAxisPx = 320.0,
            ellipseMinorAxisPx = 320.0,
            ellipseAngleRadians = 0.0,
            autoCalibrationAccepted = false,
            source = PlateDetectionSource.GRADIENT_CIRCLE,
        )
        assertFalse(
            isObliquePlateOverrideEligible(
                proposalFor(
                    gradient,
                    PlateDetectionSource.GRADIENT_CIRCLE.name,
                    requiresAdjustment = true,
                    adjusted = false,
                ),
            ),
        )
        assertFalse(
            isObliquePlateOverrideEligible(genuine.copy(adjusted = true)),
        )
        assertFalse(
            isObliquePlateOverrideEligible(genuine.copy(provenance = "USER_CIRCLE")),
        )
    }

    @Test
    fun `manual commit provenance never invents automatic detection`() {
        val oblique = proposalFor(
            detection = obliqueDetection(),
            provenance = PlateDetectionSource.CONTOUR_ELLIPSE.name,
            requiresAdjustment = true,
            adjusted = false,
        )
        assertTrue(obliqueDetection().autoCalibrationAccepted.not())
        assertTrue(committedPlateDetectionForConfirm(oblique) == null)
        val provisional = proposalFor(
            detection = null,
            provenance = "USER_CIRCLE",
            requiresAdjustment = true,
            adjusted = false,
        )
        assertTrue(committedPlateDetectionForConfirm(provisional) == null)
        val adjustedManual = oblique.copy(adjusted = true)
        assertTrue(committedPlateDetectionForConfirm(adjustedManual) == null)
    }

    @Test
    fun `accepted untouched detector path keeps detector provenance`() {
        val accepted = obliqueDetection().copy(perspectiveRatio = 0.9, autoCalibrationAccepted = true)
        val proposal = proposalFor(
            detection = accepted,
            provenance = PlateDetectionSource.CONTOUR_ELLIPSE.name,
            requiresAdjustment = false,
            adjusted = false,
        )
        assertFalse(isObliquePlateOverrideEligible(proposal))
        assertTrue(committedPlateDetectionForConfirm(proposal) === proposal.originalDetection)
    }
}
