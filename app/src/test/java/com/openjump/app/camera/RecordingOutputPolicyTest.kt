package com.openjump.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingOutputPolicyTest {
    @Test
    fun `only successful finalize with at least two indexed PTS can publish`() {
        assertTrue(RecordingOutputPolicy.isPublishable(true, true, 10, 2))
        assertFalse(RecordingOutputPolicy.isPublishable(false, true, 10, 2))
        assertFalse(RecordingOutputPolicy.isPublishable(true, true, 10, 1))
        assertFalse(RecordingOutputPolicy.isPublishable(true, false, 10, 2))
        assertFalse(RecordingOutputPolicy.isPublishable(true, true, 0, 2))
    }
}
