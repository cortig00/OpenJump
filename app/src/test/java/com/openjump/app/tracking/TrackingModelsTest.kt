package com.openjump.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrackingModelsTest {

    @Test
    fun `presentation geometry swaps encoded dimensions for quarter turns`() {
        assertEquals(VideoPresentationGeometry(1080, 1920), VideoPresentationGeometry.fromEncoded(1920, 1080, 90))
        assertEquals(VideoPresentationGeometry(1920, 1080), VideoPresentationGeometry.fromEncoded(1920, 1080, 180))
    }

    @Test
    fun `lost samples cannot claim a coordinate`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackingSample(10L, ImagePoint(1.0, 2.0), 0.0, TrackingStatus.LOST)
        }
    }

    @Test
    fun `measured samples require a coordinate`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackingSample(10L, null, 0.5, TrackingStatus.TRACKING)
        }
    }

    @Test
    fun `confidence is bounded`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackingSample(10L, ImagePoint(1.0, 2.0), 1.1, TrackingStatus.TRACKING)
        }
    }
}
