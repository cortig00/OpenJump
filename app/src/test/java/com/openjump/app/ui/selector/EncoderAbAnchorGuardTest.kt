package com.openjump.app.ui.selector

import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import com.openjump.app.protocol.VideoSource
import com.openjump.app.tracking.ImagePoint
import org.junit.Assert.*
import org.junit.Test

/** Pure drift policy for the anchored A/B dialog: no VM, no decoder, no Compose. */
class EncoderAbAnchorGuardTest {
    private val timing = VideoTimingDecision(VideoTimingMode.REAL_TIME)
    private val otherTiming = VideoTimingDecision(VideoTimingMode.SLOW_MOTION, 2.0)
    private val a = ImagePoint(10.0, 20.0)
    private val b = ImagePoint(30.0, 40.0)

    private fun anchor() = AbEditorAnchor(
        sessionKey = "s1",
        videoUri = "content://video/1",
        renderedIndex = 3,
        renderedPtsUs = 30_000L,
        videoWidth = 540.0,
        videoHeight = 960.0,
        liveA = a,
        liveB = b,
        requestedIndex = 3,
        timing = timing,
        source = VideoSource.IMPORTED,
    )

    private fun validArgs(anchor: AbEditorAnchor = anchor()) = Triple(anchor, true, true)

    @Test fun `matching snapshot stays valid`() {
        val anchor = anchor()
        assertTrue(
            isAbAnchorStillValid(
                anchor = anchor,
                sessionKey = "s1",
                timing = timing,
                calibrationPresent = false,
                liveA = a,
                liveB = b,
                videoUri = "content://video/1",
                source = VideoSource.IMPORTED,
                renderedIndex = 3,
                renderedPtsUs = 30_000L,
                requestedIndex = 3,
                canMark = true,
                phaseIsManual = true,
                videoWidth = 540.0,
                videoHeight = 960.0,
                canonicalPtsUs = 30_000L,
            ),
        )
    }

    @Test fun `confirmed calibration invalidates and never clears scale`() {
        val anchor = anchor()
        assertFalse(
            isAbAnchorStillValid(
                anchor, "s1", timing, true, a, b,
                "content://video/1", VideoSource.IMPORTED, 3, 30_000L, 3, true, true,
                540.0, 960.0, 30_000L,
            ),
        )
    }

    @Test fun `stored PTS is actually checked against canonical`() {
        val anchor = anchor()
        // Same index but different actual PTS.
        assertFalse(
            isAbAnchorStillValid(
                anchor, "s1", timing, false, a, b,
                "content://video/1", VideoSource.IMPORTED, 3, 31_000L, 3, true, true,
                540.0, 960.0, 31_000L,
            ),
        )
        // Same actual PTS but canonical re-index differs.
        assertFalse(
            isAbAnchorStillValid(
                anchor, "s1", timing, false, a, b,
                "content://video/1", VideoSource.IMPORTED, 3, 30_000L, 3, true, true,
                540.0, 960.0, 31_000L,
            ),
        )
    }

    @Test fun `seek away and back invalidates via index or requested`() {
        val anchor = anchor()
        assertFalse(
            isAbAnchorStillValid(
                anchor, "s1", timing, false, a, b,
                "content://video/1", VideoSource.IMPORTED, 4, 40_000L, 4, true, true,
                540.0, 960.0, 40_000L,
            ),
        )
        // Same rendered index but requested moved (seek in flight).
        assertFalse(
            isAbAnchorStillValid(
                anchor, "s1", timing, false, a, b,
                "content://video/1", VideoSource.IMPORTED, 3, 30_000L, 4, false, true,
                540.0, 960.0, 30_000L,
            ),
        )
    }

    @Test fun `source URI timing pair geometry all pinned`() {
        val anchor = anchor()
        fun check(
            uri: String? = "content://video/1",
            source: VideoSource? = VideoSource.IMPORTED,
            timing: VideoTimingDecision? = this.timing,
            liveA: ImagePoint? = a,
            liveB: ImagePoint? = b,
            w: Double? = 540.0,
            h: Double? = 960.0,
        ) = isAbAnchorStillValid(
            anchor, "s1", timing, false, liveA, liveB, uri, source,
            3, 30_000L, 3, true, true, w, h, 30_000L,
        )
        assertFalse(check(uri = "content://video/2"))
        assertFalse(check(source = VideoSource.RECORDED))
        assertFalse(check(timing = otherTiming))
        assertFalse(check(timing = null))
        assertFalse(check(liveA = ImagePoint(11.0, 21.0)))
        assertFalse(check(liveB = null))
        assertFalse(check(w = 541.0))
        assertFalse(check(h = 961.0))
        assertTrue(check())
    }

    @Test fun `phase and canMark loss invalidate`() {
        val anchor = anchor()
        assertFalse(
            isAbAnchorStillValid(
                anchor, "s1", timing, false, a, b,
                "content://video/1", VideoSource.IMPORTED, 3, 30_000L, 3, true, false,
                540.0, 960.0, 30_000L,
            ),
        )
        assertFalse(
            isAbAnchorStillValid(
                anchor, "s1", timing, false, a, b,
                "content://video/1", VideoSource.IMPORTED, 3, 30_000L, 3, false, true,
                540.0, 960.0, 30_000L,
            ),
        )
    }

    @Test fun `session change invalidates`() {
        val anchor = anchor()
        assertFalse(
            isAbAnchorStillValid(
                anchor, "s2", timing, false, a, b,
                "content://video/1", VideoSource.IMPORTED, 3, 30_000L, 3, true, true,
                540.0, 960.0, 30_000L,
            ),
        )
    }
}
