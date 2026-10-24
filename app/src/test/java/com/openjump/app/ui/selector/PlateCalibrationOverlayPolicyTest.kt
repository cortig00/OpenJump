package com.openjump.app.ui.selector

import com.openjump.app.tracking.ImagePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlateCalibrationOverlayPolicyTest {
    private val center = ImagePoint(100.0, 100.0)
    private val handle = ImagePoint(140.0, 100.0)

    @Test
    fun `drag target is latched from the initial center or handle hitbox`() {
        assertEquals(
            PlateDragMode.CENTER,
            plateDragModeFor(ImagePoint(105.0, 100.0), center, handle, hitRadiusPx = 24.0),
        )
        assertEquals(
            PlateDragMode.HANDLE,
            plateDragModeFor(ImagePoint(140.0, 119.0), center, handle, hitRadiusPx = 24.0),
        )
    }

    @Test
    fun `disk interior moves the whole outline and overlapping targets choose nearest`() {
        assertEquals(PlateDragMode.CENTER, plateDragModeFor(ImagePoint(100.0, 100.0), center, ImagePoint(105.0, 105.0), 24.0, 7.0))
        assertEquals(PlateDragMode.CENTER, plateDragModeFor(ImagePoint(100.0, 130.0), center, handle, 24.0, 40.0))
        assertEquals(PlateDragMode.HANDLE, plateDragModeFor(ImagePoint(105.0, 105.0), center, ImagePoint(105.0, 105.0), 24.0, 7.0))
    }

    @Test
    fun `outside hitbox has no drag target and therefore cannot consume assisted taps`() {
        assertNull(plateDragModeFor(ImagePoint(180.0, 180.0), center, handle, hitRadiusPx = 24.0))
    }
}
