package com.openjump.app.ui.selector

import org.junit.Assert.assertEquals
import org.junit.Test

class PtsTimelineMapperTest {

    @Test
    fun `CFR maps endpoints and midpoint`() {
        val mapper = PtsTimelineMapper(longArrayOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L))

        assertEquals(0f, mapper.fractionAtIndex(0), 0.0001f)
        assertEquals(0.5f, mapper.fractionAtIndex(2), 0.0001f)
        assertEquals(1f, mapper.fractionAtIndex(4), 0.0001f)
        assertEquals(2, mapper.indexAtFraction(0.5f))
    }

    @Test
    fun `VFR gap occupies proportional visual space`() {
        val mapper = PtsTimelineMapper(longArrayOf(0L, 10_000L, 20_000L, 90_000L, 100_000L))

        assertEquals(0.2f, mapper.fractionAtIndex(2), 0.0001f)
        assertEquals(0.9f, mapper.fractionAtIndex(3), 0.0001f)
        assertEquals(2, mapper.indexAtFraction(0.5f))
    }

    @Test
    fun `snap tie chooses previous frame`() {
        val mapper = PtsTimelineMapper(longArrayOf(0L, 100L, 200L))

        assertEquals(0, mapper.indexAtFraction(0.25f))
        assertEquals(1, mapper.indexAtFraction(0.75f))
    }

    @Test
    fun `marker PTS is clamped to timeline`() {
        val mapper = PtsTimelineMapper(longArrayOf(10_000L, 20_000L, 30_000L))

        assertEquals(0f, mapper.fractionAtPts(0L), 0.0001f)
        assertEquals(0.5f, mapper.fractionAtPts(20_000L), 0.0001f)
        assertEquals(1f, mapper.fractionAtPts(40_000L), 0.0001f)
    }
}
