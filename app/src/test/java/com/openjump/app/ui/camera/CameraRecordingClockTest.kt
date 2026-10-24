package com.openjump.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraRecordingClockTest {
    @Test fun elapsedTimeUsesWholeSecondsAndDoesNotWrapAtAnHour() {
        assertEquals("00:00", CameraRecordingClock.format(-1L))
        assertEquals("00:00", CameraRecordingClock.format(999_999_999L))
        assertEquals("00:09", CameraRecordingClock.format(9_999_999_999L))
        assertEquals("01:00", CameraRecordingClock.format(60_000_000_000L))
        assertEquals("60:00", CameraRecordingClock.format(3_600_000_000_000L))
    }
}
