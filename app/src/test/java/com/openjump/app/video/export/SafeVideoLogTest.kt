package com.openjump.app.video.export

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeVideoLogTest {
    @Test
    fun `export diagnostics contain only fixed label and numeric codes`() {
        assertEquals("trajectory_export_failed code=42", SafeVideoLog.exportFailure(42))
        assertEquals(
            "trajectory_export_fallback height=1080->720 hdr=2->1",
            SafeVideoLog.exportFallback(1080, 720, 2, 1),
        )
    }
}
