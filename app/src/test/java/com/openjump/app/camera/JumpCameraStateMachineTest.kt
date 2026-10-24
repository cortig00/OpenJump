package com.openjump.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpCameraStateMachineTest {
    @Test
    fun `recording is unavailable before bind and stale callbacks are ignored`() {
        val state = JumpCameraStateMachine()
        val token = state.beginBinding()
        assertFalse(state.beginRecording(token))
        assertTrue(state.bound(token))
        assertFalse(state.beginRecording(token))
        assertTrue(state.streaming(token))
        assertTrue(state.beginRecording(token))
        assertTrue(state.started(token))
        state.cancel()
        assertFalse(state.beginFinalize(token))
        assertFalse(state.terminal(token, true))
    }

    @Test
    fun `finalization is terminal exactly once`() {
        val state = JumpCameraStateMachine()
        val token = state.beginBinding()
        state.bound(token)
        state.streaming(token)
        state.beginRecording(token)
        state.started(token)
        assertTrue(state.beginFinalize(token))
        assertTrue(state.terminal(token, true))
        assertFalse(state.started(token))
        assertEquals(JumpCameraState.READY, state.state)
        assertFalse(state.terminal(token, false))
    }

    @Test
    fun `unexpected finalize enters finalizing from starting and can terminalize`() {
        val state = JumpCameraStateMachine()
        val token = state.beginBinding()
        assertTrue(state.bound(token))
        assertTrue(state.streaming(token))
        assertTrue(state.beginRecording(token))

        assertTrue(state.beginFinalize(token))
        assertFalse(state.started(token))
        assertTrue(state.terminal(token, true))
        assertEquals(JumpCameraState.READY, state.state)
    }

    @Test
    fun `failed transition terminalizes current binding`() {
        val state = JumpCameraStateMachine()
        val token = state.beginBinding()

        assertTrue(state.terminal(token, false))
        assertFalse(state.beginRecording(token))
        assertEquals(JumpCameraState.ERROR, state.state)
    }

    @Test
    fun `dispose invalidates generation`() {
        val state = JumpCameraStateMachine()
        val token = state.beginBinding()
        state.dispose()
        assertFalse(state.isCurrent(token))
        assertFalse(state.bound(token))
    }
}
