package com.openjump.app.ui.selector

import com.openjump.app.R
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MeasurementValidationIssue
import com.openjump.app.protocol.ProtocolCatalog
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpMarkingActionTest {

    @Test
    fun `SJ follows its two event sequence`() {
        val empty = draft(ProtocolId.SJ)
        assertEquals(
            JumpMarkingAction.Mark(EventType.TAKEOFF, enabled = true),
            resolve(empty),
        )

        val takeoff = draft(ProtocolId.SJ, EventType.TAKEOFF to 10L)
        assertEquals(
            JumpMarkingAction.Mark(EventType.LANDING, enabled = true),
            resolve(takeoff),
        )
        assertEquals(
            JumpMarkingAction.Compute(enabled = true, validationError = null),
            resolve(draft(ProtocolId.SJ, EventType.TAKEOFF to 10L, EventType.LANDING to 20L)),
        )
    }

    @Test
    fun `CMJ follows its three event sequence`() {
        assertEquals(
            JumpMarkingAction.Mark(EventType.MOVEMENT_START, enabled = true),
            resolve(draft(ProtocolId.CMJ, markMovementStart = true)),
        )
        assertEquals(
            JumpMarkingAction.Mark(EventType.TAKEOFF, enabled = true),
            resolve(draft(ProtocolId.CMJ, EventType.MOVEMENT_START to 10L, markMovementStart = true)),
        )
        assertEquals(
            JumpMarkingAction.Mark(EventType.LANDING, enabled = true),
            resolve(
                draft(
                    ProtocolId.CMJ,
                    EventType.MOVEMENT_START to 10L,
                    EventType.TAKEOFF to 20L,
                    markMovementStart = true,
                ),
            ),
        )
        assertEquals(
            JumpMarkingAction.Compute(enabled = true, validationError = null),
            resolve(
                draft(
                    ProtocolId.CMJ,
                    EventType.MOVEMENT_START to 10L,
                    EventType.TAKEOFF to 20L,
                    EventType.LANDING to 30L,
                    markMovementStart = true,
                ),
            ),
        )
    }

    @Test
    fun `basic mode asks only for takeoff and landing without a start hint`() {
        listOf(ProtocolId.CMJ, ProtocolId.ABALAKOV, ProtocolId.UNILATERAL).forEach { protocolId ->
            val first = resolve(draft(protocolId, markMovementStart = false))
            assertEquals(protocolId.storageKey, JumpMarkingAction.Mark(EventType.TAKEOFF, enabled = true), first)
            assertNotEquals(
                protocolId.storageKey,
                R.string.selector_hint_movement_start,
                (first as JumpMarkingAction.Mark).hintResource,
            )
            assertEquals(
                protocolId.storageKey,
                JumpMarkingAction.Mark(EventType.LANDING, enabled = true),
                resolve(draft(protocolId, EventType.TAKEOFF to 10L, markMovementStart = false)),
            )
            assertEquals(
                protocolId.storageKey,
                JumpMarkingAction.Compute(enabled = true, validationError = null),
                resolve(
                    draft(
                        protocolId,
                        EventType.TAKEOFF to 10L,
                        EventType.LANDING to 20L,
                        markMovementStart = false,
                    ),
                ),
            )
        }
    }

    @Test
    fun `all available temporal protocols expose their catalog first event`() {
        ProtocolCatalog.available
            .filter { it.requiredEvents.isNotEmpty() }
            .forEach { definition ->
                assertEquals(
                    definition.id.storageKey,
                    JumpMarkingAction.Mark(definition.requiredEvents.first(), enabled = true),
                    resolve(draft(definition.id)),
                )
            }
    }

    @Test
    fun `mark actions expose localized hint resources for every event`() {
        assertEquals(R.string.selector_hint_movement_start, JumpMarkingAction.Mark(EventType.MOVEMENT_START, true).hintResource)
        assertEquals(R.string.selector_hint_initial_contact, JumpMarkingAction.Mark(EventType.INITIAL_CONTACT, true).hintResource)
        assertEquals(R.string.selector_hint_takeoff, JumpMarkingAction.Mark(EventType.TAKEOFF, true).hintResource)
        assertEquals(R.string.selector_hint_landing, JumpMarkingAction.Mark(EventType.LANDING, true).hintResource)
    }

    @Test
    fun `Mark enabled mirrors marking input`() {
        val action = resolve(draft(ProtocolId.SJ), markingEnabled = false)

        assertEquals(JumpMarkingAction.Mark(EventType.TAKEOFF, enabled = false), action)
        assertFalse((action as JumpMarkingAction.Mark).enabled)
    }

    @Test
    fun `Compute is disabled for invalid playing and CROP and enabled otherwise`() {
        val valid = draft(ProtocolId.SJ, EventType.TAKEOFF to 10L, EventType.LANDING to 20L)
        val invalid = draft(ProtocolId.SJ, EventType.TAKEOFF to 20L, EventType.LANDING to 10L)

        val invalidAction = resolve(invalid)
        assertFalse((invalidAction as JumpMarkingAction.Compute).enabled)
        assertTrue(invalidAction.validationError != null)
        assertEquals(MeasurementValidationIssue.EVENT_ORDER, invalidAction.issue)
        assertFalse((resolve(valid, isPlaying = true) as JumpMarkingAction.Compute).enabled)
        assertFalse((resolve(valid, isCropFraming = true) as JumpMarkingAction.Compute).enabled)
        assertTrue((resolve(valid) as JumpMarkingAction.Compute).enabled)
    }

    @Test
    fun `series presentation exposes multi-attempt progress and compute copy`() {
        val first = seriesPresentationFor(attempt = 1, targetAttempts = 5)
        val second = seriesPresentationFor(attempt = 2, targetAttempts = 5)

        assertTrue(first.showProgress)
        assertEquals(R.string.camera_jump_progress, first.progressResource)
        assertEquals(listOf(1, 5), first.progressArgs)
        assertEquals(R.string.selector_compute_jump, first.computeResource)
        assertEquals(listOf(1, 5), first.computeArgs)
        assertTrue(second.showProgress)
        assertEquals(listOf(2, 5), second.progressArgs)
        assertEquals(listOf(2, 5), second.computeArgs)
    }

    @Test
    fun `series presentation falls back to simple copy without false ordinal`() {
        val single = seriesPresentationFor(attempt = 1, targetAttempts = 1)
        assertFalse(single.showProgress)
        assertEquals(null, single.progressResource)
        assertTrue(single.progressArgs.isEmpty())
        assertEquals(R.string.selector_compute_result, single.computeResource)
        assertTrue(single.computeArgs.isEmpty())

        listOf(
            0 to 5,
            6 to 5,
            1 to 0,
            1 to 6,
        ).forEach { (attempt, targetAttempts) ->
            val invalid = seriesPresentationFor(attempt, targetAttempts)
            assertFalse(invalid.showProgress)
            assertEquals(null, invalid.progressResource)
            assertTrue(invalid.progressArgs.isEmpty())
            assertEquals(R.string.selector_compute_result, invalid.computeResource)
            assertTrue(invalid.computeArgs.isEmpty())
        }
    }

    @Test
    fun `validation issue is structural and independent from localized error text`() {
        val ordered = draft(ProtocolId.SJ, EventType.TAKEOFF to 20L, EventType.LANDING to 10L)
        assertEquals(
            MeasurementValidationIssue.EVENT_ORDER,
            (resolve(ordered) as JumpMarkingAction.Compute).issue,
        )
    }

    @Test
    fun `horizontal null and protocols without temporal events are hidden`() {
        assertEquals(JumpMarkingAction.Hidden, resolve(null))
        assertEquals(JumpMarkingAction.Hidden, resolve(draft(ProtocolId.HORIZONTAL)))
        assertEquals(JumpMarkingAction.Hidden, resolve(draft(ProtocolId.REPEATED_10_5)))
    }

    @Test
    fun `timeline hides the unrequested start without touching mandatory or horizontal marks`() {
        val basic = draft(
            ProtocolId.CMJ,
            EventType.MOVEMENT_START to 10L,
            EventType.TAKEOFF to 20L,
            EventType.LANDING to 30L,
            markMovementStart = false,
        )
        assertEquals(
            listOf(EventType.TAKEOFF, EventType.LANDING),
            visibleTimelineMarks(basic).map { it.key.type },
        )

        val full = draft(
            ProtocolId.CMJ,
            EventType.MOVEMENT_START to 10L,
            EventType.TAKEOFF to 20L,
            EventType.LANDING to 30L,
            markMovementStart = true,
        )
        assertEquals(
            listOf(EventType.MOVEMENT_START, EventType.TAKEOFF, EventType.LANDING),
            visibleTimelineMarks(full).map { it.key.type },
        )

        // SJ has no optional start; horizontal only stores landing and keeps its marker.
        assertEquals(
            listOf(EventType.LANDING, EventType.TAKEOFF),
            visibleTimelineMarks(draft(ProtocolId.SJ, EventType.TAKEOFF to 20L, EventType.LANDING to 10L))
                .map { it.key.type },
        )
        assertEquals(
            listOf(EventType.LANDING),
            visibleTimelineMarks(draft(ProtocolId.HORIZONTAL, EventType.LANDING to 30L, markMovementStart = false))
                .map { it.key.type },
        )
        assertTrue(visibleTimelineMarks(null).isEmpty())
    }

    private fun resolve(
        draft: MeasurementDraft?,
        markingEnabled: Boolean = true,
        isPlaying: Boolean = false,
        isCropFraming: Boolean = false,
    ): JumpMarkingAction = JumpMarkingActionPolicy.resolve(
        draft = draft,
        markingEnabled = markingEnabled,
        isPlaying = isPlaying,
        isCropFraming = isCropFraming,
    )

    private fun draft(
        protocolId: ProtocolId,
        vararg marks: Pair<EventType, Long>,
        /** Preference-on fixture by default; basic-mode tests pass `false` explicitly. */
        markMovementStart: Boolean = true,
    ): MeasurementDraft = MeasurementDraft(
        sessionKey = "test",
        protocolId = protocolId,
        setup = when (protocolId) {
            ProtocolId.DROP_JUMP -> ProtocolSetup(dropHeightCm = 30.0)
            ProtocolId.UNILATERAL -> ProtocolSetup(side = MeasurementSide.LEFT)
            else -> ProtocolSetup()
        },
        markMovementStart = markMovementStart,
        events = marks.associate { (type, ptsUs) ->
            EventKey(type) to EventMark(EventKey(type), ptsUs, frameIndex = ptsUs.toInt())
        },
    )
}
