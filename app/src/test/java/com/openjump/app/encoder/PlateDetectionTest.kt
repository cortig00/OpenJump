package com.openjump.app.encoder

import com.openjump.app.tracking.GrayFrame
import com.openjump.app.tracking.ImagePoint
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateDetectionTest {
    private val detector = GeometricPlateDetector()

    @Test
    fun `assisted tap is refined to center and diameter`() {
        val frame = syntheticFrame(
            width = 320,
            height = 240,
            ellipses = listOf(TestEllipse(190.0, 118.0, 58.0, 56.0, 35)),
        )

        val outcome = detector.detectAssisted(frame, ImagePoint(155.0, 105.0))
        assertTrue(outcome is PlateDetectionOutcome.Found)
        val detection = (outcome as PlateDetectionOutcome.Found).detection
        assertEquals(PlateDetectionMode.ASSISTED, detection.mode)
        assertEquals(190.0, detection.center.x, 3.0)
        assertEquals(118.0, detection.center.y, 3.0)
        assertEquals(
            "source=${detection.source} ratio=${detection.perspectiveRatio} axes=${detection.ellipseMajorAxisPx}/${detection.ellipseMinorAxisPx}",
            114.0,
            detection.diameterPx,
            7.0,
        )
        assertTrue(detection.confidence >= PlateDetectorConfig().minimumAssistedConfidence)
        assertTrue(detection.autoCalibrationAccepted)
    }

    @Test
    fun `assisted detection is polarity independent for a bright colored plate`() {
        val darkPixels = ByteArray(320 * 240) { 35.toByte() }
        for (y in 0 until 240) for (x in 0 until 320) {
            val dx = x - 175.0
            val dy = y - 120.0
            if (dx * dx / (58.0 * 58.0) + dy * dy / (56.0 * 56.0) <= 1.0) {
                darkPixels[y * 320 + x] = 205.toByte()
            }
        }

        val outcome = detector.detectAssisted(GrayFrame(320, 240, darkPixels), ImagePoint(195.0, 130.0))

        assertTrue(outcome is PlateDetectionOutcome.Found)
        assertEquals(175.0, (outcome as PlateDetectionOutcome.Found).detection.center.x, 4.0)
    }

    @Test
    fun `tap near edge still finds full plate`() {
        val frame = syntheticFrame(
            320,
            240,
            listOf(TestEllipse(160.0, 120.0, 62.0, 60.0, 50)),
        )

        val outcome = detector.detectAssisted(frame, ImagePoint(215.0, 120.0))
        assertTrue(outcome is PlateDetectionOutcome.Found)
        val detection = (outcome as PlateDetectionOutcome.Found).detection
        assertEquals(160.0, detection.center.x, 3.0)
        assertEquals(
            "source=${detection.source} ratio=${detection.perspectiveRatio} axes=${detection.ellipseMajorAxisPx}/${detection.ellipseMinorAxisPx}",
            122.0,
            detection.diameterPx,
            7.0,
        )
    }

    @Test
    fun `assisted mode chooses largest concentric geometry`() {
        val frame = syntheticFrame(
            360,
            280,
            listOf(
                TestEllipse(180.0, 140.0, 78.0, 76.0, 45),
                TestEllipse(180.0, 140.0, 45.0, 44.0, 115),
                TestEllipse(180.0, 140.0, 18.0, 18.0, 35),
            ),
        )

        val outcome = detector.detectAssisted(frame, ImagePoint(170.0, 130.0))
        assertTrue(outcome is PlateDetectionOutcome.Found)
        val detection = (outcome as PlateDetectionOutcome.Found).detection
        assertEquals(
            "source=${detection.source} ratio=${detection.perspectiveRatio} axes=${detection.ellipseMajorAxisPx}/${detection.ellipseMinorAxisPx}",
            154.0,
            detection.diameterPx,
            9.0,
        )
    }

    @Test
    fun `automatic mode chooses largest plate in a concentric mixed diameter stack`() {
        val frame = syntheticFrame(
            400,
            300,
            listOf(
                // Approximate 20 kg vs 5 kg diameter ratio, sharing the same bar axis.
                TestEllipse(205.0, 150.0, 86.0, 84.0, 35),
                TestEllipse(205.0, 150.0, 41.0, 40.0, 120),
                TestEllipse(205.0, 150.0, 13.0, 13.0, 30),
            ),
        )

        val outcome = detector.detectAutomatic(frame)

        assertTrue(outcome.toString(), outcome is PlateDetectionOutcome.Found)
        assertEquals(170.0, (outcome as PlateDetectionOutcome.Found).detection.diameterPx, 10.0)
    }

    @Test
    fun `assisted gradient fallback tolerates an interrupted outer contour`() {
        val base = syntheticFrame(
            360,
            280,
            listOf(TestEllipse(180.0, 140.0, 74.0, 72.0, 40)),
        )
        val interrupted = base.pixels.copyOf()
        for (y in 45 until 235) {
            for (x in 173..187) interrupted[y * base.width + x] = 220.toByte()
        }

        val outcome = detector.detectAssisted(
            GrayFrame(base.width, base.height, interrupted),
            ImagePoint(150.0, 140.0),
        )

        assertTrue(outcome.toString(), outcome is PlateDetectionOutcome.Found)
        val found = outcome as PlateDetectionOutcome.Found
        val detection = found.detection
        assertEquals(180.0, detection.center.x, 8.0)
        assertEquals(146.0, detection.diameterPx, 14.0)
        assertEquals(PlateDetectionSource.GRADIENT_CIRCLE, detection.source)
        assertEquals(0.0, detection.perspectiveRatio, 0.0)
        assertFalse(detection.autoCalibrationAccepted)
    }

    @Test
    fun `automatic mode refuses two similarly credible plates`() {
        val frame = syntheticFrame(
            480,
            280,
            listOf(
                TestEllipse(135.0, 140.0, 60.0, 59.0, 35),
                TestEllipse(345.0, 140.0, 61.0, 60.0, 50),
            ),
        )

        val outcome = detector.detectAutomatic(frame)
        assertTrue(outcome is PlateDetectionOutcome.Ambiguous)
    }

    @Test
    fun `automatic mode accepts one clear plate`() {
        val frame = syntheticFrame(
            360,
            280,
            listOf(TestEllipse(205.0, 145.0, 70.0, 68.0, 45)),
        )

        val outcome = detector.detectAutomatic(frame)
        assertTrue(outcome is PlateDetectionOutcome.Found)
        val detection = (outcome as PlateDetectionOutcome.Found).detection
        assertEquals(PlateDetectionMode.AUTO, detection.mode)
        assertEquals(205.0, detection.center.x, 3.0)
    }

    @Test
    fun `strong ellipse permits target but rejects automatic calibration`() {
        val frame = syntheticFrame(
            360,
            280,
            listOf(TestEllipse(180.0, 140.0, 72.0, 42.0, 40, angleRadians = 0.25)),
        )

        val outcome = detector.detectAssisted(frame, ImagePoint(180.0, 140.0))
        assertTrue(outcome is PlateDetectionOutcome.Found)
        val detection = (outcome as PlateDetectionOutcome.Found).detection
        assertTrue(detection.perspectiveRatio < PlateDetectorConfig().minimumPerspectiveRatioForCalibration)
        assertFalse(detection.autoCalibrationAccepted)
    }

    @Test
    fun `detected diameter feeds existing metric calibrator exactly`() {
        val detection = PlateDetection(
            center = ImagePoint(200.0, 120.0),
            diameterPx = 150.0,
            confidence = 0.9,
            perspectiveRatio = 0.95,
            mode = PlateDetectionMode.ASSISTED,
            ellipseMajorAxisPx = 154.0,
            ellipseMinorAxisPx = 146.0,
            ellipseAngleRadians = 0.4,
            autoCalibrationAccepted = true,
        )
        val (pointA, pointB) = detection.calibrationEndpoints()
        val calibration = MetricCalibrator.create(7, pointA, pointB, 0.45)

        assertEquals(0.45 / 150.0, calibration.metersPerPixel, 1e-12)
        assertEquals(7, calibration.frameIndex)
    }

    @Test
    fun `contour clipped by assisted ROI is never reported as ellipse`() {
        val frame = syntheticFrame(
            320,
            240,
            listOf(TestEllipse(24.0, 120.0, 42.0, 40.0, 40)),
        )
        val outcome = detector.detectAssisted(frame, ImagePoint(24.0, 120.0))
        if (outcome is PlateDetectionOutcome.Found) {
            assertTrue(outcome.detection.source != PlateDetectionSource.CONTOUR_ELLIPSE)
        }
    }

    @Test
    fun `flat frame falls back cleanly`() {
        val frame = GrayFrame(240, 180, ByteArray(240 * 180) { 90.toByte() })

        assertTrue(detector.detectAutomatic(frame) is PlateDetectionOutcome.NotFound)
        assertTrue(detector.detectAssisted(frame, ImagePoint(120.0, 90.0)) is PlateDetectionOutcome.NotFound)
    }

    private fun syntheticFrame(
        width: Int,
        height: Int,
        ellipses: List<TestEllipse>,
    ): GrayFrame {
        val pixels = ByteArray(width * height) { 220.toByte() }
        ellipses.forEach { ellipse ->
            val cosine = cos(ellipse.angleRadians)
            val sine = sin(ellipse.angleRadians)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val dx = x - ellipse.centerX
                    val dy = y - ellipse.centerY
                    val localX = dx * cosine + dy * sine
                    val localY = -dx * sine + dy * cosine
                    if (localX * localX / (ellipse.radiusX * ellipse.radiusX) +
                        localY * localY / (ellipse.radiusY * ellipse.radiusY) <= 1.0
                    ) {
                        pixels[y * width + x] = ellipse.gray.toByte()
                    }
                }
            }
        }
        return GrayFrame(width, height, pixels)
    }

    private data class TestEllipse(
        val centerX: Double,
        val centerY: Double,
        val radiusX: Double,
        val radiusY: Double,
        val gray: Int,
        val angleRadians: Double = 0.0,
    )
}
