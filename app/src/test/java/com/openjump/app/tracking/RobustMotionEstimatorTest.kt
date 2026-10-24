package com.openjump.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RobustMotionEstimatorTest {

    @Test
    fun `median displacement rejects a single extreme feature`() {
        val coherent = listOf(
            motion(10.0, 10.0, 4.0, -2.0),
            motion(30.0, 10.0, 4.2, -2.1),
            motion(10.0, 30.0, 3.9, -1.8),
            motion(30.0, 30.0, 4.1, -2.0),
            motion(20.0, 20.0, 4.0, -2.2),
            motion(25.0, 35.0, 3.8, -1.9),
        )
        val outlier = motion(40.0, 40.0, -25.0, 30.0)

        val estimate = RobustMotionEstimator.estimate(
            attemptedFeatures = 7,
            motions = coherent + outlier,
            region = ImageRegion(ImagePoint(25.0, 25.0), 50.0, 50.0),
            minimumSupport = 4,
        )

        assertNotNull(estimate)
        assertEquals(4.0, estimate!!.dx, 0.15)
        assertEquals(-2.0, estimate.dy, 0.15)
        assertEquals(6, estimate.inliers.size)
        assertTrue(estimate.confidence > 0.5)
    }

    @Test
    fun `coverage reports a tiny cluster as weak evidence`() {
        val region = ImageRegion(ImagePoint(50.0, 50.0), 80.0, 80.0)
        val clustered = listOf(ImagePoint(49.0, 49.0), ImagePoint(51.0, 51.0))
        val spread = listOf(ImagePoint(20.0, 20.0), ImagePoint(80.0, 80.0))

        assertTrue(
            RobustMotionEstimator.spatialCoverage(clustered, region) <
                RobustMotionEstimator.spatialCoverage(spread, region),
        )
    }

    private fun motion(x: Double, y: Double, dx: Double, dy: Double) = FeatureMotion(
        from = ImagePoint(x, y),
        to = ImagePoint(x + dx, y + dy),
    )
}
