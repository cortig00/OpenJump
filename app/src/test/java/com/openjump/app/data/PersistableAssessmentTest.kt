package com.openjump.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PersistableAssessmentTest {
    @Test
    fun `legacy singular payload remains one child while plural preserves ordered children`() {
        val assessment = AssessmentEntity(sessionKey = "s", protocolId = "SJ", dateTime = 1L, primaryMetricKey = "HEIGHT_CM", primaryMetricValue = 10.0, primaryMetricUnit = "CENTIMETER")
        val single = PersistableAssessment(assessment, AttemptEntity(ordinal = 0, side = null, source = "UNKNOWN", videoUri = null, detectedFps = 30, dropHeightCm = null, distanceCm = null), emptyList(), emptyList())
        assertEquals(listOf(0), single.children().map { it.attempt.ordinal })
        val plural = single.copy(attempts = listOf(
            PersistableAttempt(single.attempt.copy(ordinal = 0), emptyList(), emptyList()),
            PersistableAttempt(single.attempt.copy(ordinal = 1), emptyList(), emptyList()),
        ))
        assertEquals(listOf(0, 1), plural.children().map { it.attempt.ordinal })
    }
}
