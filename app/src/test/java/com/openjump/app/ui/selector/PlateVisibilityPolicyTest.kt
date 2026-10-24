package com.openjump.app.ui.selector

import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateVisibilityPolicyTest {
    private val video = VideoPresentationGeometry(1000, 1000)

    @Test
    fun `FIT keeps a centered circle fully visible`() {
        val mapper = VideoCoordinateMapper(video, ViewportSize(500.0, 500.0), VideoScaleMode.FIT)
        assertTrue(mapper.isPlateCircleVisible(ImagePoint(500.0, 500.0), 300.0, 0.0))
    }

    @Test
    fun `CROP pan rejects circle outside effective visible video`() {
        val mapper = VideoCoordinateMapper(
            video,
            ViewportSize(500.0, 500.0),
            VideoScaleMode.CROP,
            cropAlignment = CropAlignment(1.0, 0.5),
        )
        assertFalse(mapper.isPlateCircleVisible(ImagePoint(100.0, 500.0), 300.0, 0.0))
        assertTrue(mapper.isPlateCircleVisible(ImagePoint(800.0, 500.0), 100.0, 0.0))
    }
}
