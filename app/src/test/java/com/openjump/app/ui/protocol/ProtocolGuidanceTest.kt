package com.openjump.app.ui.protocol

import com.openjump.app.R
import com.openjump.app.protocol.ProtocolAvailability
import com.openjump.app.protocol.ProtocolCatalog
import com.openjump.app.protocol.ProtocolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolGuidanceTest {
    @Test
    fun `every available protocol has complete executable guidance`() {
        val available = ProtocolCatalog.all.filter { it.availability == ProtocolAvailability.AVAILABLE }
        assertEquals(available.map { it.id }.toSet(), ProtocolGuidanceCatalog.available().keys)
        available.forEach { definition ->
            val guidance = ProtocolGuidanceCatalog.forProtocol(definition.id)
            assertNotNull(guidance)
            assertEquals(3, guidance!!.executionCues.size)
            assertTrue(guidance.executionCues.all { it != 0 })
            assertTrue(guidance.cameraCue != 0)
            assertTrue(guidance.markingSummary != 0)
            assertTrue(guidance.limits != 0)
            assertTrue(guidance.illustrationDescription != 0)
        }
    }

    @Test
    fun `coming soon protocols do not expose executable guidance`() {
        ProtocolCatalog.all
            .filter { it.availability == ProtocolAvailability.COMING_SOON }
            .forEach { assertEquals(null, ProtocolGuidanceCatalog.forProtocol(it.id)) }
        assertEquals(null, ProtocolGuidanceCatalog.forProtocol(ProtocolId.REPEATED_10_5))
    }

    @Test
    fun `basic mode summary lists only take-off and landing for CMJ Abalakov and unilateral`() {
        listOf(ProtocolId.CMJ, ProtocolId.ABALAKOV, ProtocolId.UNILATERAL).forEach { protocolId ->
            val full = ProtocolGuidanceCatalog.forProtocol(protocolId, markMovementStart = true)
            val basic = ProtocolGuidanceCatalog.forProtocol(protocolId, markMovementStart = false)
            assertNotNull(full)
            assertNotNull(basic)
            assertEquals(R.string.guidance_mark_events_vertical, full!!.markingSummary)
            assertEquals(R.string.guidance_mark_events_sj, basic!!.markingSummary)
            // Only the marking summary changes: physical cues, camera, limits and illustration stay.
            assertEquals(full.copy(markingSummary = basic.markingSummary), basic)
            // All default entry points describe the basic two-event flow.
            assertEquals(basic, ProtocolGuidanceCatalog.forProtocol(protocolId))
        }
    }

    @Test
    fun `bilateral basic mode still explains every side and attempt`() {
        val full = ProtocolGuidanceCatalog.forProtocol(ProtocolId.ASYMMETRY, markMovementStart = true)!!
        val basic = ProtocolGuidanceCatalog.forProtocol(ProtocolId.ASYMMETRY, markMovementStart = false)!!
        assertEquals(R.string.guidance_mark_events_bilateral, full.markingSummary)
        assertEquals(R.string.guidance_mark_events_bilateral_no_start, basic.markingSummary)
        assertEquals(full.copy(markingSummary = basic.markingSummary), basic)
    }

    @Test
    fun `protocols without an optional start ignore the marking mode`() {
        listOf(ProtocolId.SJ, ProtocolId.DROP_JUMP, ProtocolId.HORIZONTAL).forEach { protocolId ->
            assertEquals(
                ProtocolGuidanceCatalog.forProtocol(protocolId, markMovementStart = true),
                ProtocolGuidanceCatalog.forProtocol(protocolId, markMovementStart = false),
            )
        }
    }
}
