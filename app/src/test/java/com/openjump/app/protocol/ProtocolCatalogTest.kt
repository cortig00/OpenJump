package com.openjump.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCatalogTest {

    @Test
    fun `catalog has stable unique ids and seven available protocols`() {
        assertEquals(ProtocolCatalog.all.size, ProtocolCatalog.all.map { it.id.storageKey }.distinct().size)
        assertEquals(7, ProtocolCatalog.available.size)
        ProtocolCatalog.available.forEach {
            if (it.id != ProtocolId.HORIZONTAL && it.id != ProtocolId.ASYMMETRY) assertTrue(it.requiredEvents.isNotEmpty())
            assertNotNull(ProtocolCatalog.find(it.id.storageKey))
        }
        assertEquals(MetricKey.DISTANCE_M, ProtocolCatalog.find(ProtocolId.HORIZONTAL).primaryMetric)
        assertEquals(ProtocolAvailability.AVAILABLE, ProtocolCatalog.find(ProtocolId.ASYMMETRY).availability)
    }

    @Test
    fun `rsi mod is a capability and not a standalone protocol`() {
        assertFalse(ProtocolCatalog.all.any { it.id.storageKey == "RSI_MOD" })
        assertTrue(ProtocolCatalog.find(ProtocolId.CMJ).includesRsiMod)
        assertTrue(ProtocolCatalog.find(ProtocolId.ABALAKOV).includesRsiMod)
    }

    @Test
    fun `setup validates side and drop height`() {
        val unilateral = ProtocolCatalog.find(ProtocolId.UNILATERAL)
        assertNotNull(ProtocolCatalog.validateSetup(unilateral, ProtocolSetup()))
        assertNull(ProtocolCatalog.validateSetup(unilateral, ProtocolSetup(side = MeasurementSide.LEFT)))

        val drop = ProtocolCatalog.find(ProtocolId.DROP_JUMP)
        assertNotNull(ProtocolCatalog.validateSetup(drop, ProtocolSetup(dropHeightCm = 0.0)))
        assertNotNull(ProtocolCatalog.validateSetup(drop, ProtocolSetup(dropHeightCm = Double.NaN)))
        assertNull(ProtocolCatalog.validateSetup(drop, ProtocolSetup(dropHeightCm = 40.0)))
    }

    @Test
    fun `coming soon protocols cannot start`() {
        val repeated = ProtocolCatalog.find(ProtocolId.REPEATED_10_5)
        assertNotNull(ProtocolCatalog.validateSetup(repeated, ProtocolSetup()))
    }

    @Test
    fun `movement start is optional only in countermovement protocols`() {
        listOf(ProtocolId.CMJ, ProtocolId.ABALAKOV, ProtocolId.UNILATERAL).forEach { protocol ->
            val definition = ProtocolCatalog.find(protocol)
            assertEquals(listOf(EventType.MOVEMENT_START), definition.optionalEvents)
            assertEquals(
                listOf(EventType.MOVEMENT_START, EventType.TAKEOFF, EventType.LANDING),
                definition.requiredEvents,
            )
            assertEquals(listOf(EventType.TAKEOFF, EventType.LANDING), definition.mandatoryEvents)
        }
        listOf(
            ProtocolId.SJ,
            ProtocolId.DROP_JUMP,
            ProtocolId.HORIZONTAL,
            ProtocolId.ASYMMETRY,
            ProtocolId.REPEATED_10_5,
        ).forEach { protocol ->
            val definition = ProtocolCatalog.find(protocol)
            assertEquals(emptyList<EventType>(), definition.optionalEvents)
            assertEquals(definition.requiredEvents, definition.mandatoryEvents)
        }
    }
}
