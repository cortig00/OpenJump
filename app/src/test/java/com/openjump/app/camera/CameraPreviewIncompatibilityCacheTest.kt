package com.openjump.app.camera

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CameraPreviewIncompatibilityCacheTest {
    private val hs120Hd = profile("rear", "HD", 1280, 720, 120)
    private val hs120Fhd = profile("rear", "FHD", 1920, 1080, 120)
    private val hs240Hd = profile("rear", "HD", 1280, 720, 240)

    @Before
    fun setUp() = CameraPreviewIncompatibilityCache.clearForTests()

    @After
    fun tearDown() = CameraPreviewIncompatibilityCache.clearForTests()

    @Test
    fun `rejection is scoped to the exact profile`() {
        CameraPreviewIncompatibilityCache.reject(hs120Hd)

        assertTrue(CameraPreviewIncompatibilityCache.isRejected(hs120Hd))
        assertFalse(CameraPreviewIncompatibilityCache.isRejected(hs120Fhd))
        assertFalse(CameraPreviewIncompatibilityCache.isRejected(hs240Hd))
        assertFalse(CameraPreviewIncompatibilityCache.isRejected(hs120Hd.copy(cameraId = "front")))
        assertFalse(CameraPreviewIncompatibilityCache.isRejected(hs120Hd.copy(sessionKind = CameraSessionKind.NORMAL)))
    }

    @Test
    fun `same fps alternate quality remains eligible`() {
        CameraPreviewIncompatibilityCache.reject(hs120Hd)

        assertEquals(listOf(hs120Fhd), CameraPreviewIncompatibilityCache.filter(listOf(hs120Hd, hs120Fhd)))
        assertEquals(emptySet<Int>(), CameraPreviewIncompatibilityCache.unavailableFps(listOf(hs120Hd, hs120Fhd)))
    }

    @Test
    fun `process session filtering removes rejected profile before policy`() {
        CameraPreviewIncompatibilityCache.reject(hs120Hd)

        val candidates = CameraProfilePolicy.candidates(
            CameraPreviewIncompatibilityCache.filter(listOf(hs120Hd, hs240Hd)),
            preferredFps = 0,
        )

        assertEquals(listOf(240), candidates.map { it.fps })
    }

    @Test
    fun `fps becomes unavailable only after all qualified profiles are rejected`() {
        assertEquals(emptySet<Int>(), CameraPreviewIncompatibilityCache.unavailableFps(listOf(hs120Hd, hs120Fhd, hs240Hd)))

        CameraPreviewIncompatibilityCache.reject(hs120Hd)
        assertEquals(emptySet<Int>(), CameraPreviewIncompatibilityCache.unavailableFps(listOf(hs120Hd, hs120Fhd, hs240Hd)))

        CameraPreviewIncompatibilityCache.reject(hs120Fhd)
        assertEquals(setOf(120), CameraPreviewIncompatibilityCache.unavailableFps(listOf(hs120Hd, hs120Fhd, hs240Hd)))
    }

    @Test
    fun `clear reset support removes process session rejections`() {
        CameraPreviewIncompatibilityCache.reject(hs120Hd)
        CameraPreviewIncompatibilityCache.clearForTests()

        assertFalse(CameraPreviewIncompatibilityCache.isRejected(hs120Hd))
        assertEquals(listOf(hs120Hd), CameraPreviewIncompatibilityCache.filter(listOf(hs120Hd)))
    }

    @Test
    fun `duplicate rejection is idempotent`() {
        assertTrue(CameraPreviewIncompatibilityCache.reject(hs120Hd))
        assertFalse(CameraPreviewIncompatibilityCache.reject(hs120Hd))
        assertEquals(emptyList<CameraProductProfile>(), CameraPreviewIncompatibilityCache.filter(listOf(hs120Hd, hs120Hd)))
    }

    private fun profile(
        cameraId: String,
        quality: String,
        width: Int,
        height: Int,
        fps: Int,
    ) = CameraProductProfile(
        cameraId = cameraId,
        quality = quality,
        width = width,
        height = height,
        fps = fps,
        sessionKind = CameraSessionKind.HIGH_SPEED,
    )
}
