package com.openjump.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientSessionPolicyTest {
    @Test
    fun processDeathBlocksEveryRestoredTransientRoute() {
        assertFalse(TransientSessionPolicy.canOpenCamera(false, false))
        assertFalse(TransientSessionPolicy.canOpenSelector(false, false, hasVideo = false))
        assertFalse(TransientSessionPolicy.canOpenJumpResult(false))
        assertFalse(TransientSessionPolicy.canOpenEncoderResult(false))
    }

    @Test
    fun cameraAcceptsEitherSessionButSelectorAlsoRequiresVideo() {
        assertTrue(TransientSessionPolicy.canOpenCamera(hasJumpDraft = true, hasEncoderDraft = false))
        assertTrue(TransientSessionPolicy.canOpenCamera(hasJumpDraft = false, hasEncoderDraft = true))
        assertFalse(TransientSessionPolicy.canOpenSelector(true, false, hasVideo = false))
        assertTrue(TransientSessionPolicy.canOpenSelector(false, true, hasVideo = true))
    }
}
