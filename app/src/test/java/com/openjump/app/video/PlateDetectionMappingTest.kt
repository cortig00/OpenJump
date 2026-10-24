package com.openjump.app.video

import com.openjump.app.encoder.PlateDetection
import com.openjump.app.encoder.PlateDetectionMode
import com.openjump.app.tracking.DecodedTrackingFrame
import com.openjump.app.tracking.GrayFrame
import com.openjump.app.tracking.ImagePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlateDetectionMappingTest {
    @Test
    fun `presentation mapping preserves rejected calibration even when mapped ratio passes`() {
        val detection = PlateDetection(
            center = ImagePoint(131.0, 262.0),
            diameterPx = 262.0,
            confidence = 0.9,
            perspectiveRatio = 0.84,
            mode = PlateDetectionMode.ASSISTED,
            ellipseMajorAxisPx = 262.0,
            ellipseMinorAxisPx = 220.0,
            ellipseAngleRadians = 0.0,
            autoCalibrationAccepted = false,
        )
        val frame = DecodedTrackingFrame(0, 0L, GrayFrame(405, 720, ByteArray(405 * 720)), 1080, 1920)

        val mapped = detection.toPresentation(frame)

        assertFalse(mapped.autoCalibrationAccepted)
        assertEquals(699.5107582037758, mapped.diameterPx, 1e-9)
    }

    @Test
    fun `accepted calibration is retained only when mapped perspective remains eligible`() {
        val detection = PlateDetection(
            center = ImagePoint(100.0, 100.0),
            diameterPx = 100.0,
            confidence = 0.9,
            perspectiveRatio = 0.9,
            mode = PlateDetectionMode.AUTO,
            ellipseMajorAxisPx = 100.0,
            ellipseMinorAxisPx = 90.0,
            ellipseAngleRadians = 0.0,
            autoCalibrationAccepted = true,
        )
        val frame = DecodedTrackingFrame(0, 0L, GrayFrame(100, 100, ByteArray(10000)), 200, 100)

        assertFalse(detection.toPresentation(frame).autoCalibrationAccepted)
    }
}
