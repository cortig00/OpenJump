package com.openjump.app.ui.selector

import com.openjump.app.tracking.ImagePoint
import org.junit.Assert.*
import org.junit.Test

class PlateProposalBoundsTest {
    @Test fun `maximum circle never inverts center clamp bounds`() {
        val (center, diameter) = boundedPlateProposalGeometry(ImagePoint(0.0, 999.0), 1080.0, 1080, 1920)!!
        assertEquals(1079.0, diameter, 0.0)
        assertEquals(539.5, center.x, 0.0)
        assertTrue(center.y in diameter / 2.0..1919.0 - diameter / 2.0)
    }
    @Test fun `invalid geometry is rejected and minimum valid frame stays bounded`() {
        assertNull(boundedPlateProposalGeometry(ImagePoint(2.0, 2.0), 0.0, 100, 100))
        assertNull(boundedPlateProposalGeometry(ImagePoint(2.0, 2.0), Double.POSITIVE_INFINITY, 100, 100))
        assertNull(boundedPlateProposalGeometry(ImagePoint(2.0, 2.0), 1.0, 8, 8))
        assertEquals(ImagePoint(4.0, 4.0), boundedPlateProposalGeometry(ImagePoint(-1.0, 20.0), 1.0, 9, 9)!!.first)
    }
}
