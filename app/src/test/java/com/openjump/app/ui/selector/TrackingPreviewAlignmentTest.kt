package com.openjump.app.ui.selector

import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingDiagnostics
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.TrackingResult
import com.openjump.app.tracking.TrackingSample
import com.openjump.app.tracking.TrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingPreviewAlignmentTest {
    private fun result(index: Int, pts: Long) = TrackingFrameResult(index, TrackingResult(
        TrackingSample(pts, ImagePoint(index.toDouble(), 20.0), 1.0, TrackingStatus.TRACKING),
        null, emptyList(), TrackingDiagnostics.EMPTY,
    ))

    @Test fun `player still selects its own frame rather than latest processed result`() {
        val first = result(0, 10_000)
        val future = result(5, 180_000)
        assertEquals(first, visibleTrackingResult(listOf(first, future), 0, 10_000))
    }

    @Test fun `frame and PTS must both match`() {
        val sample = result(5, 180_000)
        assertNull(visibleTrackingResult(listOf(sample), 4, 180_000))
        assertNull(visibleTrackingResult(listOf(sample), 5, 179_999))
        assertEquals(sample, visibleTrackingResult(listOf(sample), 5, 180_000))
    }

    @Test fun `unknown render has no marker`() {
        val samples = listOf(result(0, 10_000))
        assertNull(visibleTrackingResult(samples, null, 10_000))
        assertNull(visibleTrackingResult(samples, 0, null))
        assertNull(visibleTrackingResult(emptyList(), 0, 10_000))
    }
}
