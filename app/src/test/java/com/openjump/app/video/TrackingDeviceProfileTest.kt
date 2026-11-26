package com.openjump.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingDeviceProfileTest {

    @Test
    fun `selects low tier dimension on low-ram devices`() {
        assertEquals(
            TrackingDeviceProfile.LOW_TIER_MAX_DIMENSION,
            TrackingDeviceProfile.recommendedTrackingDimension(
                isLowRamDevice = true,
                availableProcessors = 8,
                memoryClassMb = 512,
            ),
        )
    }

    @Test
    fun `selects low tier dimension on quad-core or smaller devices`() {
        assertEquals(
            TrackingDeviceProfile.LOW_TIER_MAX_DIMENSION,
            TrackingDeviceProfile.recommendedTrackingDimension(
                isLowRamDevice = false,
                availableProcessors = 4,
                memoryClassMb = 256,
            ),
        )
    }

    @Test
    fun `selects low tier dimension when heap class is at or below 192 MB`() {
        assertEquals(
            TrackingDeviceProfile.LOW_TIER_MAX_DIMENSION,
            TrackingDeviceProfile.recommendedTrackingDimension(
                isLowRamDevice = false,
                availableProcessors = 8,
                memoryClassMb = 192,
            ),
        )
    }

    @Test
    fun `selects standard dimension on modern devices`() {
        assertEquals(
            TrackingDeviceProfile.STANDARD_MAX_DIMENSION,
            TrackingDeviceProfile.recommendedTrackingDimension(
                isLowRamDevice = false,
                availableProcessors = 8,
                memoryClassMb = 256,
            ),
        )
    }
}
