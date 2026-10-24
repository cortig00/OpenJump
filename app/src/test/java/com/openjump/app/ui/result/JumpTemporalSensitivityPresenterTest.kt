package com.openjump.app.ui.result

import com.openjump.app.math.ProtocolCalculator
import com.openjump.app.protocol.BilateralAttemptSnapshot
import com.openjump.app.protocol.BilateralComparison
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolSetup
import com.openjump.app.protocol.StoredAttempt
import com.openjump.app.protocol.StoredEvent
import com.openjump.app.protocol.StoredMeasurement
import com.openjump.app.protocol.VideoSource
import com.openjump.app.settings.UnitProfile
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class JumpTemporalSensitivityPresenterTest {
    @Test
    fun `live and saved v10 present the same sensitivity ranges`() {
        val draft = draft()
        val result = ProtocolCalculator.compute(draft)
        val live = ResultPresenter.presentLive(draft, result, detectedFps = 30)
        val stored = ResultPresenter.presentStored(
            StoredMeasurement(
                id = 4L,
                dateTime = 10L,
                protocolId = ProtocolId.SJ,
                primaryMetric = result.primaryMetric,
                attempts = listOf(attempt(result.allMetrics)),
            ),
        )

        assertNotNull(live.temporalSensitivity)
        assertEquals(live.temporalSensitivity?.ranges, stored.temporalSensitivity?.ranges)
    }

    @Test
    fun `saved direct base mismatch hides sensitivity`() {
        val draft = draft()
        val result = ProtocolCalculator.compute(draft)
        val mismatched = result.primaryMetric.copy(value = result.primaryMetric.value + 1.0)
        val model = ResultPresenter.presentStored(
            StoredMeasurement(
                id = 4L,
                dateTime = 10L,
                protocolId = ProtocolId.SJ,
                primaryMetric = mismatched,
                attempts = listOf(attempt(result.allMetrics)),
            ),
        )
        assertNull(model.temporalSensitivity)
    }

    @Test
    fun `temporal rows format converted US and seconds units`() {
        assertEquals(
            "3.94 in",
            formatTemporalSensitivityValue(10.0, MetricUnit.CENTIMETER, UnitProfile.UNITED_STATES, Locale.US),
        )
        assertEquals(
            "0.70 s",
            formatTemporalSensitivityValue(
                700.0,
                MetricUnit.MILLISECOND,
                UnitProfile(jumpTiming = com.openjump.app.settings.JumpTimingUnit.SECOND),
                Locale.US,
            ),
        )
    }

    @Test
    fun `bilateral live attempt details explicitly suppress sensitivity`() {
        val leftDraft = unilateralDraft(com.openjump.app.protocol.MeasurementSide.LEFT)
        val rightDraft = unilateralDraft(com.openjump.app.protocol.MeasurementSide.RIGHT)
        val leftResult = ProtocolCalculator.compute(leftDraft)
        val rightResult = ProtocolCalculator.compute(rightDraft)
        val model = BilateralResultPresenter.presentLive(
            BilateralComparison(
                sessionKey = "bilateral-live",
                athleteId = 1L,
                targetAttempts = 1,
                attempts = listOf(
                    BilateralAttemptSnapshot(com.openjump.app.protocol.MeasurementSide.LEFT, 1, leftDraft, leftResult, 30),
                    BilateralAttemptSnapshot(com.openjump.app.protocol.MeasurementSide.RIGHT, 1, rightDraft, rightResult, 30),
                ),
            ),
        )
        assertTrueAllNull(model.attempts.map { it.temporalSensitivity })
    }

    @Test
    fun `bilateral attempt details explicitly suppress sensitivity`() {
        val draft = draft()
        val result = ProtocolCalculator.compute(draft)
        val attempt = attempt(result.allMetrics).copy(ordinal = 0)
        val bilateral = StoredMeasurement(
            id = 4L,
            dateTime = 10L,
            protocolId = ProtocolId.ASYMMETRY,
            primaryMetric = com.openjump.app.protocol.MetricValue(
                com.openjump.app.protocol.MetricKey.ASYMMETRY_PERCENT,
                0.0,
                MetricUnit.PERCENT,
            ),
            attempts = listOf(
                attempt.copy(side = com.openjump.app.protocol.MeasurementSide.LEFT),
                attempt.copy(ordinal = 1, side = com.openjump.app.protocol.MeasurementSide.LEFT),
                attempt.copy(ordinal = 2, side = com.openjump.app.protocol.MeasurementSide.RIGHT),
                attempt.copy(ordinal = 3, side = com.openjump.app.protocol.MeasurementSide.RIGHT),
            ),
        )
        val model = BilateralResultPresenter.presentStored(bilateral)
        assertEquals(4, model.attempts.size)
        assertTrueAllNull(model.attempts.map { it.temporalSensitivity })
    }

    private fun unilateralDraft(side: com.openjump.app.protocol.MeasurementSide): MeasurementDraft {
        val marks = listOf(
            EventType.MOVEMENT_START to 100_000L,
            EventType.TAKEOFF to 700_000L,
            EventType.LANDING to 1_300_000L,
        ).mapIndexed { index, (type, pts) ->
            val key = EventKey(type)
            key to EventMark(key, pts, index + 1, pts - 100_000L, pts + 100_000L)
        }.toMap()
        return MeasurementDraft(
            sessionKey = "bilateral-$side",
            protocolId = ProtocolId.UNILATERAL,
            setup = ProtocolSetup(side = side),
            source = VideoSource.IMPORTED,
            events = marks,
        )
    }

    private fun draft(): MeasurementDraft {
        val events = listOf(
            EventType.TAKEOFF to 500_000L,
            EventType.LANDING to 1_200_000L,
        ).mapIndexed { index, (type, pts) ->
            val key = EventKey(type)
            key to EventMark(key, pts, index + 1, pts - 100_000L, pts + 100_000L)
        }.toMap()
        return MeasurementDraft(
            sessionKey = "presenter",
            protocolId = ProtocolId.SJ,
            setup = ProtocolSetup(),
            source = VideoSource.IMPORTED,
            events = events,
        )
    }

    private fun attempt(metrics: List<com.openjump.app.protocol.MetricValue>) = StoredAttempt(
        ordinal = 0,
        side = null,
        source = VideoSource.IMPORTED,
        videoUri = null,
        detectedFps = 30,
        setup = ProtocolSetup(),
        events = listOf(
            StoredEvent(EventType.TAKEOFF, 0, 500_000L, 1, 400_000L, 600_000L),
            StoredEvent(EventType.LANDING, 0, 1_200_000L, 2, 1_100_000L, 1_300_000L),
        ),
        metrics = metrics,
    )

    private fun assertTrueAllNull(values: List<Any?>) = values.forEach { assertNull(it) }
}
