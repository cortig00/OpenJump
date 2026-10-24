package com.openjump.app.encoder

import com.openjump.app.measurement.MetricScale
import com.openjump.app.tracking.ImagePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MetricCalibrationTest {
    @Test
    fun `calibration maps pixels to meters with ascent positive`() {
        val calibration = MetricCalibrator.create(4, ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), 0.5)
        val point = MetricCalibrator.transform(ImagePoint(120.0, 80.0), ImagePoint(100.0, 100.0), calibration)

        assertEquals(0.005, calibration.metersPerPixel, 1e-9)
        assertEquals(0.10, point.x, 1e-9)
        assertEquals(0.10, point.y, 1e-9)
    }

    @Test
    fun `shared scale projects onto A B instead of using euclidean offset`() {
        val calibration = MetricCalibrator.create(0, ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), 0.5)

        assertEquals(
            2.43,
            MetricScale.projectedDistanceMeters(ImagePoint(10.0, 30.0), ImagePoint(496.0, 80.0), calibration),
            1e-9,
        )
    }

    @Test
    fun `projection follows an angled calibration axis`() {
        val calibration = MetricCalibrator.create(0, ImagePoint(0.0, 0.0), ImagePoint(100.0, 100.0), 1.0)

        assertEquals(
            1.0,
            MetricScale.projectedDistanceMeters(ImagePoint(0.0, 0.0), ImagePoint(50.0, 150.0), calibration),
            1e-9,
        )
    }

    @Test
    fun `calibration rejects coincident reference points`() {
        assertThrows(IllegalArgumentException::class.java) {
            MetricCalibrator.create(0, ImagePoint(10.0, 10.0), ImagePoint(12.0, 10.0), 0.45)
        }
    }
}
