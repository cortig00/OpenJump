package com.openjump.app.ui.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderPanelPolicyTest {
    @Test
    fun `every input resolves to one mode and diameter only belongs to plate step`() {
        PlateDetectionPhase.entries.forEach { platePhase ->
            TrackingPhase.entries.forEach { trackingPhase ->
                listOf(false, true).forEach { hasCalibration ->
                    listOf(false, true).forEach { reviewVisible ->
                        val policy = resolveEncoderPanelPolicy(
                            hasCalibration,
                            platePhase,
                            trackingPhase,
                            reviewVisible,
                        )
                        assertTrue(policy.mode in EncoderPanelMode.entries)
                        assertEquals(
                            policy.mode in setOf(
                                EncoderPanelMode.PLATE_DETECTING,
                                EncoderPanelMode.PLATE_ASSISTED,
                                EncoderPanelMode.PLATE_ASSISTED_FAILED,
                                EncoderPanelMode.PLATE_BUSY,
                                EncoderPanelMode.PLATE_REVIEW,
                            ),
                            policy.showsPlateDiameter,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `review gate dominates state and raw terminal phases recover`() {
        val reviewed = resolveEncoderPanelPolicy(false, PlateDetectionPhase.MANUAL, TrackingPhase.LOST, true)
        assertEquals(EncoderPanelMode.REVIEW, reviewed.mode)
        assertFalse(reviewed.showsPlateDiameter)

        TrackingPhase.entries.filter { it in setOf(TrackingPhase.COMPLETED, TrackingPhase.LOST, TrackingPhase.ERROR) }.forEach { phase ->
            assertEquals(
                EncoderPanelMode.TRACKING_RECOVERY,
                resolveEncoderPanelPolicy(true, PlateDetectionPhase.DETECTED, phase, false).mode,
            )
        }
    }

    @Test
    fun `target selection is limited to calibrated manual or detected idle`() {
        listOf(PlateDetectionPhase.MANUAL, PlateDetectionPhase.DETECTED).forEach { platePhase ->
            assertEquals(
                EncoderPanelMode.MANUAL_TARGET_SELECTION,
                resolveEncoderPanelPolicy(true, platePhase, TrackingPhase.IDLE, false).mode,
            )
        }

        listOf(TrackingPhase.INITIALIZING, TrackingPhase.READY, TrackingPhase.PROCESSING).forEach { trackingPhase ->
            assertTrue(
                resolveEncoderPanelPolicy(true, PlateDetectionPhase.MANUAL, trackingPhase, false).mode !=
                    EncoderPanelMode.MANUAL_TARGET_SELECTION,
            )
            assertTrue(
                resolveEncoderPanelPolicy(true, PlateDetectionPhase.DETECTED, trackingPhase, false).mode !=
                    EncoderPanelMode.MANUAL_TARGET_SELECTION,
            )
        }
        assertTrue(
            resolveEncoderPanelPolicy(true, PlateDetectionPhase.DETECTED, TrackingPhase.IDLE, true).mode !=
                EncoderPanelMode.MANUAL_TARGET_SELECTION,
        )
    }

    @Test
    fun `calibration and tracking states stay mutually exclusive`() {
        assertEquals(
            EncoderPanelMode.PLATE_REVIEW,
            resolveEncoderPanelPolicy(false, PlateDetectionPhase.REVIEW, TrackingPhase.IDLE, false).mode,
        )
        assertEquals(
            EncoderPanelMode.MANUAL_CALIBRATION,
            resolveEncoderPanelPolicy(false, PlateDetectionPhase.MANUAL, TrackingPhase.IDLE, false).mode,
        )

        val expectedByTrackingPhase = listOf(
            TrackingPhase.IDLE to EncoderPanelMode.MANUAL_TARGET_SELECTION,
            TrackingPhase.INITIALIZING to EncoderPanelMode.TRACKING_INITIALIZING,
            TrackingPhase.READY to EncoderPanelMode.TRACKING_READY,
            TrackingPhase.PROCESSING to EncoderPanelMode.TRACKING_PROCESSING,
            TrackingPhase.COMPLETED to EncoderPanelMode.TRACKING_RECOVERY,
            TrackingPhase.LOST to EncoderPanelMode.TRACKING_RECOVERY,
            TrackingPhase.ERROR to EncoderPanelMode.TRACKING_RECOVERY,
        )
        expectedByTrackingPhase.forEach { (trackingPhase, expectedMode) ->
            assertEquals(
                expectedMode,
                resolveEncoderPanelPolicy(true, PlateDetectionPhase.MANUAL, trackingPhase, false).mode,
            )
            assertEquals(
                expectedMode,
                resolveEncoderPanelPolicy(true, PlateDetectionPhase.DETECTED, trackingPhase, false).mode,
            )
        }
    }
}
