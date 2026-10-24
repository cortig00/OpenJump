package com.openjump.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewRecoveryPolicyTest {
    @Test
    fun `idle after finalize waits and does not become ready`() {
        assertEquals(
            PreviewRecoveryPolicy.Decision.WAIT_FOR_STREAM,
            PreviewRecoveryPolicy.afterFinalize(previewCompatible = true, streaming = false),
        )
    }

    @Test
    fun `current streaming after finalize readies once`() {
        assertEquals(
            PreviewRecoveryPolicy.Decision.READY,
            PreviewRecoveryPolicy.afterFinalize(previewCompatible = true, streaming = true),
        )
        assertEquals(
            PreviewRecoveryPolicy.Decision.READY,
            PreviewRecoveryPolicy.afterRecovery(previewCompatible = true, streaming = true),
        )
    }

    @Test
    fun `recovery timeout reports preview error without removing saved outcome`() {
        val timeout = PreviewRecoveryPolicy.afterTimeout(savedDelivered = true)
        assertEquals(false, timeout.ready)
        assertEquals(true, timeout.preserveSavedOutcome)
    }
}
