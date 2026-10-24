package com.openjump.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCadencePolicyTest {
    @Test
    fun `cadence warning clears when a new bind or recording starts`() {
        assertTrue(RecordingCadencePolicy.shouldClearWarning(JumpCameraState.BINDING))
        assertTrue(RecordingCadencePolicy.shouldClearWarning(JumpCameraState.STARTING))
        assertTrue(RecordingCadencePolicy.shouldClearWarning(JumpCameraState.RECORDING))
        assertFalse(RecordingCadencePolicy.shouldClearWarning(JumpCameraState.READY))
    }

    @Test
    fun `60 fps within five percent is not degraded`() {
        assertFalse(RecordingCadencePolicy.isDegraded(60, 1_000_000L / 60L, 0))
        assertFalse(RecordingCadencePolicy.isDegraded(60, 1_000_000L / 62L, 0))
    }

    @Test
    fun `120 fps outside five percent or with gaps is degraded`() {
        assertFalse(RecordingCadencePolicy.isDegraded(120, 1_000_000L / 120L, 0))
        assertTrue(RecordingCadencePolicy.isDegraded(120, 1_000_000L / 110L, 0))
        assertTrue(RecordingCadencePolicy.isDegraded(120, 1_000_000L / 120L, 1))
    }

    @Test
    fun `240 fps outside five percent is degraded`() {
        assertFalse(RecordingCadencePolicy.isDegraded(240, 1_000_000L / 240L, 0))
        assertTrue(RecordingCadencePolicy.isDegraded(240, 1_000_000L / 225L, 0))
    }
}
