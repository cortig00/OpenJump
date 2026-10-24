package com.openjump.app.ui.selector

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoReviewControlsTest {

    @Test
    fun `frame repeat accelerates after sustained hold`() {
        assertEquals(120L, frameRepeatDelayMs(0L))
        assertEquals(120L, frameRepeatDelayMs(799L))
        assertEquals(70L, frameRepeatDelayMs(800L))
        assertEquals(70L, frameRepeatDelayMs(1_999L))
        assertEquals(35L, frameRepeatDelayMs(2_000L))
        assertEquals(35L, frameRepeatDelayMs(10_000L))
    }
}
