package com.openjump.app.ui.selector

import com.openjump.app.tracking.ImagePoint
import org.junit.Assert.*
import org.junit.Test

/** The existing markCalibrationPoint callback alternates; the plan must land exactly. */
class EncoderAbPublishPlanTest {
    private val a = ImagePoint(10.0, 20.0)
    private val b = ImagePoint(30.0, 40.0)
    private val a2 = ImagePoint(11.0, 21.0)
    private val b2 = ImagePoint(31.0, 41.0)

    @Test fun `empty local pair publishes nothing and never fabricates B as A`() {
        assertEquals(AbPublishPlan(false, emptyList()), planAbPublish(a, b, null, null))
        assertEquals(AbPublishPlan(false, emptyList()), planAbPublish(a, null, null, b2))
        assertEquals(AbPublishPlan(false, emptyList()), planAbPublish(null, null, null, null))
    }

    @Test fun `unchanged pair publishes nothing`() {
        assertEquals(AbPublishPlan(false, emptyList()), planAbPublish(a, b, a, b))
        assertEquals(AbPublishPlan(false, emptyList()), planAbPublish(a, null, a, null))
    }

    @Test fun `fresh pair from empty marks plainly without reset`() {
        assertEquals(AbPublishPlan(false, listOf(a, b)), planAbPublish(null, null, a, b))
        assertEquals(AbPublishPlan(false, listOf(a)), planAbPublish(null, null, a, null))
    }

    @Test fun `changed pair resets via the existing manual route then marks`() {
        assertEquals(AbPublishPlan(true, listOf(a2, b2)), planAbPublish(a, b, a2, b2))
        assertEquals(AbPublishPlan(true, listOf(a2)), planAbPublish(a, b, a2, null))
    }

    @Test fun `partial live A resets before republishing instead of landing on B`() {
        assertEquals(AbPublishPlan(true, listOf(a2, b2)), planAbPublish(a, null, a2, b2))
        assertEquals(AbPublishPlan(true, listOf(a2)), planAbPublish(a, null, a2, null))
    }

    @Test fun `completing a partial A with the same A resets then marks both`() {
        // Reset-first is uniform and safe: after the reset the plain A,B marks
        // always land, with no dependence on alternation history.
        assertEquals(AbPublishPlan(true, listOf(a, b)), planAbPublish(a, null, a, b))
    }
}
