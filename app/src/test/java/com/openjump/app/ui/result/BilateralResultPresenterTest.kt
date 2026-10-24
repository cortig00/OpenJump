package com.openjump.app.ui.result

import com.openjump.app.protocol.BilateralAttemptSnapshot
import com.openjump.app.protocol.BilateralComparison
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.StoredAttempt
import com.openjump.app.protocol.StoredMeasurement
import com.openjump.app.protocol.ProtocolSetup
import com.openjump.app.protocol.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BilateralResultPresenterTest {
    @Test
    fun `live bilateral presents best and mean with all attempt details`() {
        val attempts = listOf(20.0, 30.0, 10.0, 25.0).mapIndexed { index, value ->
            val side = if (index < 2) MeasurementSide.LEFT else MeasurementSide.RIGHT
            val sideOrdinal = if (index < 2) index + 1 else index - 1
            val key = EventKey(EventType.MOVEMENT_START)
            val draft = MeasurementDraft("live-$index", ProtocolId.UNILATERAL, 1L, setup = ProtocolSetup(side = side), events = mapOf(key to EventMark(key, 100L, 1)))
            BilateralAttemptSnapshot(side, sideOrdinal, draft, com.openjump.app.protocol.ProtocolResult(ProtocolId.UNILATERAL, MetricValue(MetricKey.HEIGHT_CM, value, MetricUnit.CENTIMETER), emptyList()), 30)
        }
        val model = BilateralResultPresenter.presentLive(BilateralComparison("live", 1L, 2, attempts))
        assertEquals(30.0, model.best.leftValue, 0.0)
        assertEquals(17.5, model.mean.rightValue, 0.0)
        assertEquals(4, model.attempts.size)
    }

    @Test
    fun `saved bilateral opens best by default and reconstructs every unilateral detail`() {
        val measurement = stored(listOf(20.0, 30.0, 10.0, 25.0))
        val model = BilateralResultPresenter.presentStored(measurement)
        assertEquals(30.0, model.best.leftValue, 0.0)
        assertEquals(25.0, model.best.rightValue, 0.0)
        assertEquals(4, model.attempts.size)
        assertEquals(MeasurementSide.LEFT, model.attempts[0].attempt.side)
        assertEquals(MeasurementSide.RIGHT, model.attempts[3].attempt.side)
    }

    @Test
    fun `saved bilateral rejects wrong protocol, missing metric and invalid order`() {
        assertThrows(IllegalArgumentException::class.java) {
            BilateralResultPresenter.presentStored(stored(listOf(20.0, 20.0), ProtocolId.SJ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BilateralResultPresenter.presentStored(stored(listOf(20.0, 20.0), ProtocolId.ASYMMETRY, missingHeight = true))
        }
        val wrong = stored(listOf(20.0, 20.0)).let { it.copy(attempts = it.attempts.mapIndexed { index, attempt -> attempt.copy(side = if (index == 0) MeasurementSide.RIGHT else MeasurementSide.LEFT) }) }
        assertThrows(IllegalArgumentException::class.java) { BilateralResultPresenter.presentStored(wrong) }
    }

    private fun stored(values: List<Double>, protocol: ProtocolId = ProtocolId.ASYMMETRY, missingHeight: Boolean = false): StoredMeasurement {
        val half = values.size / 2
        return StoredMeasurement(
            id = 1L,
            dateTime = 1L,
            athleteId = 1L,
            protocolId = protocol,
            primaryMetric = MetricValue(
                MetricKey.ASYMMETRY_PERCENT,
                if (protocol == ProtocolId.ASYMMETRY && !missingHeight) {
                    val left = values.take(half).maxOrNull()!!
                    val right = values.drop(half).maxOrNull()!!
                    kotlin.math.abs(left - right) / maxOf(left, right) * 100.0
                } else 10.0,
                MetricUnit.PERCENT,
            ),
            attempts = values.mapIndexed { index, value ->
                StoredAttempt(
                    ordinal = index,
                    side = if (index < half) MeasurementSide.LEFT else MeasurementSide.RIGHT,
                    source = VideoSource.IMPORTED,
                    videoUri = null,
                    detectedFps = 30,
                    setup = ProtocolSetup(side = if (index < half) MeasurementSide.LEFT else MeasurementSide.RIGHT),
                    events = listOf(
                        com.openjump.app.protocol.StoredEvent(EventType.MOVEMENT_START, 0, 100L),
                        com.openjump.app.protocol.StoredEvent(EventType.TAKEOFF, 0, 200L),
                        com.openjump.app.protocol.StoredEvent(EventType.LANDING, 0, 400L),
                    ),
                    metrics = if (missingHeight && index == 0) emptyList() else listOf(
                        MetricValue(MetricKey.HEIGHT_CM, value, MetricUnit.CENTIMETER),
                    ),
                )
            },
        )
    }
}
