package com.openjump.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraXGraphPolicyTest {
    @Test
    fun `normal graph only permits preview target resolution`() {
        assertTrue(CameraXGraphPolicy.NORMAL.allowsPreviewTargetResolution)
        assertFalse(CameraXGraphPolicy.NORMAL.allowsPreviewTargetAspectRatio)
        assertFalse(CameraXGraphPolicy.NORMAL.allowsPreviewResolutionSelector)
        assertFalse(CameraXGraphPolicy.NORMAL.allowsUseCaseTargetFrameRate)
        assertTrue(CameraXGraphPolicy.NORMAL.usesQualitySelectorForVideo)
    }

    @Test
    fun `high speed graph leaves sizing and fps to CameraX session`() {
        assertFalse(CameraXGraphPolicy.HIGH_SPEED.allowsPreviewTargetResolution)
        assertFalse(CameraXGraphPolicy.HIGH_SPEED.allowsPreviewTargetAspectRatio)
        assertFalse(CameraXGraphPolicy.HIGH_SPEED.allowsPreviewResolutionSelector)
        assertFalse(CameraXGraphPolicy.HIGH_SPEED.allowsUseCaseTargetFrameRate)
        assertTrue(CameraXGraphPolicy.HIGH_SPEED.usesQualitySelectorForVideo)
    }
}
