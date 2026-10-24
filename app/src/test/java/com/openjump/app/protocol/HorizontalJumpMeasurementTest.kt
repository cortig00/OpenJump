package com.openjump.app.protocol

import com.openjump.app.tracking.ImagePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HorizontalJumpMeasurementTest {

    @Test
    fun `projects jump onto calibration axis and ignores perpendicular offset`() {
        val draft = calibrated(ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), 0.5)
            .markNextPoint(ImagePoint(10.0, 30.0), frameIndex = 2, ptsUs = 100_000L)
            .markNextPoint(ImagePoint(496.0, 80.0), frameIndex = 20, ptsUs = 700_000L)

        assertEquals(2.43, draft.distanceMeters(), 1e-9)
        assertNull(draft.validationError())
        assertEquals(HorizontalJumpStage.COMPLETE, draft.stage)
    }

    @Test
    fun `projection is positive when jump runs opposite to A B`() {
        val draft = calibrated(ImagePoint(100.0, 0.0), ImagePoint(0.0, 0.0), 1.0)
            .markNextPoint(ImagePoint(300.0, 10.0), 1, 100L)
            .markNextPoint(ImagePoint(57.0, 80.0), 2, 200L)

        assertEquals(2.43, draft.distanceMeters(), 1e-9)
    }

    @Test
    fun `landing must follow start and have progress on calibrated axis`() {
        val unordered = calibrated(ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), 1.0)
            .markNextPoint(ImagePoint(0.0, 0.0), 5, 500L)
            .markNextPoint(ImagePoint(100.0, 0.0), 4, 400L)
        val perpendicular = calibrated(ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), 1.0)
            .markNextPoint(ImagePoint(0.0, 0.0), 1, 100L)
            .markNextPoint(ImagePoint(0.0, 100.0), 2, 200L)

        assertEquals(MeasurementValidationIssue.HORIZONTAL_EVENT_ORDER, unordered.validationIssue())
        assertEquals("El aterrizaje debe estar después de la salida.", unordered.validationError())
        assertEquals(MeasurementValidationIssue.HORIZONTAL_GEOMETRY, perpendicular.validationIssue())
        assertThrows(IllegalStateException::class.java) { perpendicular.distanceMeters() }
    }

    @Test
    fun `validation issues describe each incomplete stage without parsing messages`() {
        val empty = HorizontalJumpDraft()
        val pointA = empty.markCalibrationPoint(ImagePoint(0.0, 0.0), frameIndex = 0)
        val calibrated = pointA
            .markCalibrationPoint(ImagePoint(100.0, 0.0), frameIndex = 0)
            .confirmCalibration(1.0)
        val started = calibrated.markNextPoint(ImagePoint(0.0, 0.0), frameIndex = 1, ptsUs = 100L)

        assertEquals(MeasurementValidationIssue.HORIZONTAL_CALIBRATION, empty.validationIssue())
        assertEquals(MeasurementValidationIssue.HORIZONTAL_CALIBRATION, pointA.validationIssue())
        assertEquals(MeasurementValidationIssue.HORIZONTAL_START, calibrated.validationIssue())
        assertEquals(MeasurementValidationIssue.HORIZONTAL_LANDING, started.validationIssue())
    }

    @Test
    fun `new calibration frame resets previous points`() {
        val firstPoint = HorizontalJumpDraft().markCalibrationPoint(ImagePoint(0.0, 0.0), 2)
        val reset = firstPoint.markCalibrationPoint(ImagePoint(10.0, 10.0), 3)

        assertEquals(ImagePoint(10.0, 10.0), reset.calibrationPointA)
        assertEquals(3, reset.calibrationFrameIndex)
        assertEquals(null, reset.calibrationPointB)
    }

    private fun calibrated(a: ImagePoint, b: ImagePoint, lengthM: Double): HorizontalJumpDraft =
        HorizontalJumpDraft()
            .markCalibrationPoint(a, frameIndex = 0)
            .markCalibrationPoint(b, frameIndex = 0)
            .confirmCalibration(lengthM)
}
