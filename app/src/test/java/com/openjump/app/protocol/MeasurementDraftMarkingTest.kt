package com.openjump.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The marking preference only decides which events the selector asks for. Persisted data stays
 * authoritative: two real events complete a measurement and a marked start is always ordered.
 */
class MeasurementDraftMarkingTest {

    @Test
    fun `preference on asks for the movement start first`() {
        val draft = draft(markMovementStart = true)
        assertEquals(
            listOf(EventType.MOVEMENT_START, EventType.TAKEOFF, EventType.LANDING),
            draft.markingEvents,
        )
        assertEquals(EventType.MOVEMENT_START, draft.nextRequiredEvent())
        assertFalse(draft.isComplete())
        assertEquals(MeasurementValidationIssue.MISSING_EVENTS, draft.validationIssue())
    }

    @Test
    fun `preference off asks only for takeoff and landing`() {
        val draft = draft(markMovementStart = false)
        assertEquals(listOf(EventType.TAKEOFF, EventType.LANDING), draft.markingEvents)
        assertEquals(EventType.TAKEOFF, draft.nextRequiredEvent())
        assertEquals(
            EventType.LANDING,
            draft(markMovementStart = false, EventType.TAKEOFF to 700_000L).nextRequiredEvent(),
        )
    }

    @Test
    fun `two real events complete the measurement regardless of the captured mode`() {
        val twoEvents = arrayOf(EventType.TAKEOFF to 700_000L, EventType.LANDING to 1_300_000L)
        listOf(true, false).forEach { mode ->
            val draft = draft(markMovementStart = mode, *twoEvents)
            assertTrue("mode=$mode", draft.isComplete())
            assertNull("mode=$mode", draft.validationError())
        }
        val missingLanding = draft(markMovementStart = true, EventType.TAKEOFF to 700_000L)
        assertFalse(missingLanding.isComplete())
        assertEquals(MeasurementValidationIssue.MISSING_EVENTS, missingLanding.validationIssue())
    }

    @Test
    fun `a marked start stays ordered even when the preference is off`() {
        val complete = draft(
            markMovementStart = false,
            EventType.MOVEMENT_START to 100_000L,
            EventType.TAKEOFF to 700_000L,
            EventType.LANDING to 1_300_000L,
        )
        assertEquals(
            listOf(EventType.MOVEMENT_START, EventType.TAKEOFF, EventType.LANDING),
            complete.orderedMarks().map { it.key.type },
        )
        assertNull(complete.nextRequiredEvent())
        assertNull(complete.validationError())

        val backwards = draft(
            markMovementStart = false,
            EventType.MOVEMENT_START to 800_000L,
            EventType.TAKEOFF to 700_000L,
            EventType.LANDING to 1_300_000L,
        )
        assertEquals(MeasurementValidationIssue.EVENT_ORDER, backwards.validationIssue())
    }

    @Test
    fun `a partial start keeps its place in the real marks`() {
        val draft = draft(markMovementStart = true, EventType.MOVEMENT_START to 100_000L)
        assertEquals(listOf(EventType.MOVEMENT_START), draft.orderedMarks().map { it.key.type })
        assertEquals(EventType.TAKEOFF, draft.nextRequiredEvent())
        assertFalse(draft.isComplete())
    }

    @Test
    fun `squat jump ignores the mode because it has no optional event`() {
        val draft = MeasurementDraft(
            sessionKey = "draft",
            protocolId = ProtocolId.SJ,
            markMovementStart = true,
        )
        assertEquals(listOf(EventType.TAKEOFF, EventType.LANDING), draft.markingEvents)
        assertEquals(EventType.TAKEOFF, draft.nextRequiredEvent())
        assertTrue(
            MeasurementDraft(
                sessionKey = "draft",
                protocolId = ProtocolId.SJ,
                markMovementStart = true,
                events = marks(EventType.TAKEOFF to 700_000L, EventType.LANDING to 1_300_000L),
            ).isComplete(),
        )
    }

    @Test
    fun `drop jump keeps initial contact mandatory`() {
        val definition = ProtocolCatalog.find(ProtocolId.DROP_JUMP)
        assertEquals(listOf(EventType.INITIAL_CONTACT, EventType.TAKEOFF, EventType.LANDING), definition.mandatoryEvents)
        val draft = MeasurementDraft(
            sessionKey = "draft",
            protocolId = ProtocolId.DROP_JUMP,
            setup = ProtocolSetup(dropHeightCm = 40.0),
            markMovementStart = false,
            events = marks(EventType.TAKEOFF to 700_000L, EventType.LANDING to 1_300_000L),
        )
        assertFalse(draft.isComplete())
        assertEquals(EventType.INITIAL_CONTACT, draft.nextRequiredEvent())
    }

    private fun draft(markMovementStart: Boolean, vararg events: Pair<EventType, Long>) = MeasurementDraft(
        sessionKey = "draft",
        protocolId = ProtocolId.CMJ,
        markMovementStart = markMovementStart,
        events = marks(*events),
    )

    private fun marks(vararg events: Pair<EventType, Long>): Map<EventKey, EventMark> =
        events.mapIndexed { index, (type, pts) ->
            EventKey(type) to EventMark(EventKey(type), pts, index)
        }.toMap()
}
