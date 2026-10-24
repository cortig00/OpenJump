package com.openjump.app.ui.selector

import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import org.junit.Assert.*
import org.junit.Test

class PlatePrecisionTransformTest {
    @Test fun `snapshot crop removes FIT bars using actual surface coordinates`() {
        assertEquals(PlateSnapshotCrop(100, 0, 400, 800), plateSnapshotCrop(
            ContentRect(150.0, 20.0, 400.0, 800.0), ContentRect(50.0, 20.0, 600.0, 800.0), 600, 800,
        ))
        assertEquals(PlateSnapshotCrop(0, 0, 300, 400), plateSnapshotCrop(
            ContentRect(150.0, 20.0, 600.0, 800.0), ContentRect(150.0, 20.0, 600.0, 800.0), 300, 400,
        ))
    }
    @Test fun `incomplete surface cannot supply a full frame snapshot`() {
        assertNull(plateSnapshotCrop(ContentRect(0.0, 0.0, 600.0, 800.0), ContentRect(10.0, 0.0, 590.0, 800.0), 590, 800))
    }
    @Test fun `precision crop maps touch back to presentation coordinates for portrait landscape and rotated geometry`() {
        listOf(VideoPresentationGeometry(1080, 1920), VideoPresentationGeometry(1920, 1080),
            VideoPresentationGeometry.fromEncoded(1920, 1080, 90)).forEach { video ->
            val focus = ImagePoint(video.width * 0.5, video.height * 0.5)
            val mapper = platePrecisionMapper(video, ViewportSize(600.0, 500.0), focus, 3.0)
            val visible = mapper.videoToView(focus)
            assertEquals(300.0, visible.x, 0.01)
            assertEquals(250.0, visible.y, 0.01)
            val inverse = mapper.viewToVideo(visible)!!
            assertEquals(focus.x, inverse.x, 0.01)
            assertEquals(focus.y, inverse.y, 0.01)
        }
    }
}
