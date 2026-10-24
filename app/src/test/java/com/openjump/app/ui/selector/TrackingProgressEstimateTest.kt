package com.openjump.app.ui.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingProgressEstimateTest {
    @Test
    fun `no estimate without enough signal`() {
        assertNull(trackingRemainingMs(0f, 5_000L))
        assertNull(trackingRemainingMs(0.02f, 5_000L))
        assertNull(trackingRemainingMs(0.5f, 1_999L))
        assertNull(trackingRemainingMs(1f, 5_000L))
        assertNull(trackingRemainingMs(Float.NaN, 5_000L))
    }

    @Test
    fun `estimate follows observed throughput since the first frame`() {
        // Halfway after 10 s of actual tracking: about 10 s remain.
        assertEquals(10_000L, trackingRemainingMs(0.5f, 10_000L))
        // 90 % after 90 s of actual tracking: about 10 s remain.
        assertEquals(10_000L, trackingRemainingMs(0.9f, 90_000L))
    }
}
