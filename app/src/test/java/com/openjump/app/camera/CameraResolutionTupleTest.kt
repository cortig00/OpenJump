package com.openjump.app.camera

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tuple matrix for manual resolution + FPS with the process-session reject cache.
 *
 * A 720p reject must never poison the 1080p tuple at the same FPS, and manual
 * requests must never silently fall back to the other resolution. Auto keeps the
 * 120/60/240/30 order inside the chosen resolution.
 */
class CameraResolutionTupleTest {
    private fun profile(fps: Int, width: Int, height: Int, quality: String) =
        CameraProductProfile(
            "rear",
            quality,
            width,
            height,
            fps,
            if (fps >= 120) CameraSessionKind.HIGH_SPEED else CameraSessionKind.NORMAL,
        )

    private fun catalog() = listOf(
        profile(120, 1280, 720, "HD"),
        profile(120, 1920, 1080, "FHD"),
        profile(60, 1280, 720, "HD"),
        profile(60, 1920, 1080, "FHD"),
        profile(240, 1280, 720, "HD"),
        profile(240, 1920, 1080, "FHD"),
    )

    @Before
    fun setUp() = CameraPreviewIncompatibilityCache.clearForTests()

    @After
    fun tearDown() = CameraPreviewIncompatibilityCache.clearForTests()

    @Test
    fun `720p reject leaves 1080p at same fps usable and fps globally available`() {
        val hd120 = profile(120, 1280, 720, "HD")
        val catalog = catalog()
        CameraPreviewIncompatibilityCache.reject(hd120)

        val usable = CameraPreviewIncompatibilityCache.filter(catalog)
        assertFalse(hd120 in usable)
        // Global per-FPS set stays empty because 1080p@120 is still valid.
        assertEquals(emptySet<Int>(), CameraPreviewIncompatibilityCache.unavailableFps(catalog))
        // Exact 720p@120 tuple is now impossible, 1080p@120 still binds.
        assertTrue(CameraProfilePolicy.candidates(usable, 120, CameraResolutionPreference.HD_720P).isEmpty())
        val fhd = CameraProfilePolicy.choose(usable, 120, CameraResolutionPreference.FHD_1080P)
        assertEquals(1920, fhd?.width)
        assertEquals(120, fhd?.fps)
        // Full catalog filtered snapshot forces UI recompose even with empty global set.
        assertEquals(5, usable.size)
    }

    @Test
    fun `manual fps plus manual resolution never falls back to other resolution`() {
        val only1080At60 = listOf(profile(60, 1920, 1080, "FHD"))
        assertTrue(
            CameraProfilePolicy.candidates(
                only1080At60,
                60,
                CameraResolutionPreference.HD_720P,
            ).isEmpty(),
        )
        val only720At240 = listOf(profile(240, 1280, 720, "HD"))
        assertTrue(
            CameraProfilePolicy.candidates(
                only720At240,
                240,
                CameraResolutionPreference.FHD_1080P,
            ).isEmpty(),
        )
    }

    @Test
    fun `auto keeps order inside manual resolution and default callers stay auto`() {
        val catalog = catalog()
        val hdAuto = CameraProfilePolicy.candidates(catalog, 0, CameraResolutionPreference.HD_720P)
            .map { it.fps }
        assertEquals(listOf(120, 60, 240), hdAuto)
        // Default AUTO parameter preserves previous callers.
        assertEquals(
            CameraProfilePolicy.chooseAuto(catalog, CameraResolutionPreference.AUTO),
            CameraProfilePolicy.chooseAuto(catalog),
        )
        assertEquals(
            CameraProfilePolicy.candidates(catalog, 120, CameraResolutionPreference.AUTO),
            CameraProfilePolicy.candidates(catalog, 120),
        )
    }

    @Test
    fun `rec guard accepts auto inside resolution and exact manual tuple`() {
        val hd120 = profile(120, 1280, 720, "HD")
        val fhd120 = profile(120, 1920, 1080, "FHD")
        // Auto accepts any FPS inside the chosen resolution.
        assertTrue(
            CameraProfilePolicy.isCompatible(hd120, 0, CameraResolutionPreference.HD_720P),
        )
        assertTrue(
            CameraProfilePolicy.isCompatible(fhd120, 0, CameraResolutionPreference.FHD_1080P),
        )
        // Manual requires the exact tuple, never the other resolution.
        assertTrue(
            CameraProfilePolicy.isCompatible(hd120, 120, CameraResolutionPreference.HD_720P),
        )
        assertFalse(
            CameraProfilePolicy.isCompatible(hd120, 120, CameraResolutionPreference.FHD_1080P),
        )
        assertFalse(
            CameraProfilePolicy.isCompatible(hd120, 60, CameraResolutionPreference.HD_720P),
        )
        assertFalse(CameraProfilePolicy.isCompatible(null, 0))
    }
}
