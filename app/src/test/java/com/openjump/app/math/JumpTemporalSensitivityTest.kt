package com.openjump.app.math

import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpTemporalSensitivityTest {
    @Test
    fun `vfr neighbors produce an asymmetric range without combining perturbations`() {
        val draft = draft(
            ProtocolId.SJ,
            EventType.TAKEOFF to 500_000L,
            EventType.LANDING to 1_200_000L,
        )
        val sensitivity = JumpTemporalSensitivity.compute(
            draft,
            listOf(
                neighborhood(EventType.TAKEOFF, 1, 500_000L, 350_000L, 570_000L),
                neighborhood(EventType.LANDING, 2, 1_200_000L, 1_050_000L, 1_430_000L),
            ),
        )

        requireNotNull(sensitivity)
        val flight = sensitivity.ranges.single { it.key == com.openjump.app.protocol.MetricKey.FLIGHT_TIME_MS }
        assertTrue(flight.minimum < flight.base)
        assertTrue(flight.base < flight.maximum)
        assertEquals(4, sensitivity.validVariants.size)
        assertTrue(sensitivity.validVariants.all { it.eventKey.ordinal == 0 })
    }

    @Test
    fun `invalid order and sub fifty millisecond flight are discarded`() {
        val draft = draft(
            ProtocolId.SJ,
            EventType.TAKEOFF to 500_000L,
            EventType.LANDING to 600_000L,
        )
        val sensitivity = JumpTemporalSensitivity.compute(
            draft,
            listOf(
                neighborhood(EventType.TAKEOFF, 1, 500_000L, 100_000L, 550_000L),
                neighborhood(EventType.LANDING, 2, 600_000L, 400_000L, 650_000L),
            ),
        )
        requireNotNull(sensitivity)
        assertTrue(sensitivity.validVariants.none { it.eventKey.type == EventType.LANDING && it.toPtsUs <= 500_000L })
        assertTrue(sensitivity.validVariants.none { it.eventKey.type == EventType.LANDING && it.toPtsUs == 400_000L })
    }

    @Test
    fun `only previous or only next neighbor still yields a changed range`() {
        val previousOnly = JumpTemporalSensitivity.compute(
            draft(ProtocolId.SJ, EventType.TAKEOFF to 500_000L, EventType.LANDING to 1_200_000L),
            listOf(neighborhood(EventType.TAKEOFF, 1, 500_000L, 450_000L, null)),
        )
        val nextOnly = JumpTemporalSensitivity.compute(
            draft(ProtocolId.SJ, EventType.TAKEOFF to 500_000L, EventType.LANDING to 1_200_000L),
            listOf(neighborhood(EventType.LANDING, 2, 1_200_000L, null, 1_300_000L)),
        )
        assertTrue(previousOnly?.ranges?.any { it.key == com.openjump.app.protocol.MetricKey.FLIGHT_TIME_MS } == true)
        assertTrue(nextOnly?.ranges?.any { it.key == com.openjump.app.protocol.MetricKey.FLIGHT_TIME_MS } == true)
    }

    @Test
    fun `protocol event semantics publish independent metric dependencies`() {
        val cmjBase = draft(
            ProtocolId.CMJ,
            EventType.MOVEMENT_START to 100_000L,
            EventType.TAKEOFF to 700_000L,
            EventType.LANDING to 1_300_000L,
        )
        fun cmjFor(type: EventType): Set<com.openjump.app.protocol.MetricKey> = requireNotNull(
            JumpTemporalSensitivity.compute(
                cmjBase,
                listOf(neighborhood(type, cmjBase.events.getValue(EventKey(type)).frameIndex, cmjBase.events.getValue(EventKey(type)).ptsUs, cmjBase.events.getValue(EventKey(type)).ptsUs - 50_000L, cmjBase.events.getValue(EventKey(type)).ptsUs + 50_000L)),
            ),
        ).ranges.map { it.key }.toSet()
        assertEquals(setOf(com.openjump.app.protocol.MetricKey.TIME_TO_TAKEOFF_MS, com.openjump.app.protocol.MetricKey.RSI_MOD), cmjFor(EventType.MOVEMENT_START))
        assertTrue(cmjFor(EventType.TAKEOFF).containsAll(setOf(com.openjump.app.protocol.MetricKey.HEIGHT_CM, com.openjump.app.protocol.MetricKey.FLIGHT_TIME_MS, com.openjump.app.protocol.MetricKey.TIME_TO_TAKEOFF_MS, com.openjump.app.protocol.MetricKey.RSI_MOD)))
        assertTrue(cmjFor(EventType.LANDING).containsAll(setOf(com.openjump.app.protocol.MetricKey.HEIGHT_CM, com.openjump.app.protocol.MetricKey.FLIGHT_TIME_MS, com.openjump.app.protocol.MetricKey.RSI_MOD)))

        val sjBase = draft(ProtocolId.SJ, EventType.TAKEOFF to 500_000L, EventType.LANDING to 1_200_000L)
        val sjLanding = requireNotNull(JumpTemporalSensitivity.compute(sjBase, listOf(neighborhood(EventType.LANDING, 2, 1_200_000L, 1_150_000L, 1_250_000L))))
        assertTrue(sjLanding.ranges.map { it.key }.toSet().containsAll(setOf(com.openjump.app.protocol.MetricKey.HEIGHT_CM, com.openjump.app.protocol.MetricKey.FLIGHT_TIME_MS, com.openjump.app.protocol.MetricKey.TAKEOFF_VELOCITY_MPS)))

        val dropBase = draft(ProtocolId.DROP_JUMP, EventType.INITIAL_CONTACT to 100_000L, EventType.TAKEOFF to 300_000L, EventType.LANDING to 900_000L)
        val dropContact = requireNotNull(JumpTemporalSensitivity.compute(dropBase, listOf(neighborhood(EventType.INITIAL_CONTACT, 1, 100_000L, 50_000L, 150_000L))))
        assertEquals(setOf(com.openjump.app.protocol.MetricKey.RSI, com.openjump.app.protocol.MetricKey.CONTACT_TIME_MS), dropContact.ranges.map { it.key }.toSet())
    }

    @Test
    fun `protocol event semantics only publish metrics affected by each marker`() {
        val cases = listOf(
            ProtocolId.CMJ to listOf(EventType.MOVEMENT_START, EventType.TAKEOFF, EventType.LANDING),
            ProtocolId.ABALAKOV to listOf(EventType.MOVEMENT_START, EventType.TAKEOFF, EventType.LANDING),
            ProtocolId.UNILATERAL to listOf(EventType.MOVEMENT_START, EventType.TAKEOFF, EventType.LANDING),
            ProtocolId.SJ to listOf(EventType.TAKEOFF, EventType.LANDING),
            ProtocolId.DROP_JUMP to listOf(EventType.INITIAL_CONTACT, EventType.TAKEOFF, EventType.LANDING),
        )
        cases.forEach { (protocol, events) ->
            val points = events.mapIndexed { index, event -> event to (100_000L + index * 500_000L) }
            val sensitivity = JumpTemporalSensitivity.compute(
                draft(protocol, *points.toTypedArray()),
                points.mapIndexed { index, (event, pts) ->
                    neighborhood(event, index + 1, pts, pts - 100_000L, pts + 100_000L)
                },
            )
            assertTrue("$protocol should have a sensitivity result", sensitivity != null)
            assertFalse(requireNotNull(sensitivity).ranges.any { it.key.isEstimate })
        }
    }

    @Test
    fun `horizontal asymmetry and legacy null neighbors are unavailable`() {
        assertNull(
            JumpTemporalSensitivity.compute(
                draft(ProtocolId.HORIZONTAL, EventType.TAKEOFF to 500_000L),
                emptyList(),
            ),
        )
        assertNull(
            JumpTemporalSensitivity.compute(
                draft(ProtocolId.ASYMMETRY),
                emptyList(),
            ),
        )
        assertNull(
            JumpTemporalSensitivity.compute(
                draft(ProtocolId.SJ, EventType.TAKEOFF to 500_000L, EventType.LANDING to 1_200_000L),
                listOf(
                    neighborhood(EventType.TAKEOFF, null, 500_000L, null, null),
                    neighborhood(EventType.LANDING, null, 1_200_000L, null, null),
                ),
            ),
        )
        assertNull(
            JumpTemporalSensitivity.compute(
                draft(ProtocolId.SJ, EventType.TAKEOFF to 500_000L, EventType.LANDING to 1_200_000L),
                listOf(
                    neighborhood(EventType.TAKEOFF, null, 500_000L, 400_000L, 600_000L),
                    neighborhood(EventType.LANDING, 2, 1_200_000L, null, null),
                ),
            ),
        )
        assertNull(
            JumpTemporalSensitivity.compute(
                draft(ProtocolId.SJ, EventType.TAKEOFF to 500_000L, EventType.LANDING to 1_200_000L),
                listOf(
                    neighborhood(EventType.TAKEOFF, 1, 500_000L, null, null),
                    neighborhood(EventType.LANDING, 2, 1_200_000L, null, null),
                ),
            ),
        )
    }

    @Test
    fun `two-event countermovement sensitivity uses only the real marks`() {
        val draft = draft(
            ProtocolId.CMJ,
            EventType.TAKEOFF to 700_000L,
            EventType.LANDING to 1_300_000L,
        )
        val sensitivity = requireNotNull(
            JumpTemporalSensitivity.compute(
                draft,
                listOf(
                    neighborhood(EventType.TAKEOFF, 1, 700_000L, 650_000L, 750_000L),
                    neighborhood(EventType.LANDING, 2, 1_300_000L, 1_250_000L, 1_350_000L),
                ),
            ),
        )

        assertEquals(
            setOf(
                com.openjump.app.protocol.MetricKey.HEIGHT_CM,
                com.openjump.app.protocol.MetricKey.FLIGHT_TIME_MS,
                com.openjump.app.protocol.MetricKey.TAKEOFF_VELOCITY_MPS,
            ),
            sensitivity.ranges.map { it.key }.toSet(),
        )
        assertTrue(sensitivity.ranges.none { it.key == com.openjump.app.protocol.MetricKey.TIME_TO_TAKEOFF_MS })
        assertTrue(sensitivity.ranges.none { it.key == com.openjump.app.protocol.MetricKey.RSI_MOD })
        assertTrue(sensitivity.validVariants.all { it.eventKey.type != EventType.MOVEMENT_START })
    }

    private fun neighborhood(
        type: EventType,
        frame: Int?,
        pts: Long,
        previous: Long?,
        next: Long?,
    ) = TemporalEventNeighborhood(EventKey(type), frame, pts, previous, next)

    private fun draft(
        protocol: ProtocolId,
        vararg events: Pair<EventType, Long>,
    ) = MeasurementDraft(
        sessionKey = "sensitivity",
        protocolId = protocol,
        setup = when (protocol) {
            ProtocolId.DROP_JUMP -> ProtocolSetup(dropHeightCm = 40.0)
            ProtocolId.UNILATERAL -> ProtocolSetup(side = com.openjump.app.protocol.MeasurementSide.RIGHT)
            else -> ProtocolSetup()
        },
        events = events.mapIndexed { index, (type, pts) ->
            val key = EventKey(type)
            key to EventMark(key, pts, index + 1)
        }.toMap(),
    )
}
