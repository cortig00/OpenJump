package com.openjump.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraProfilePolicyTest {
    private fun profile(fps: Int, width: Int = 1920, height: Int = 1080) =
        CameraProductProfile("rear", "FHD", width, height, fps, if (fps >= 120) CameraSessionKind.HIGH_SPEED else CameraSessionKind.NORMAL)

    @Test
    fun `auto is exactly 120 then 60 then 240 then degraded 30`() {
        assertEquals(120, CameraProfilePolicy.chooseAuto(listOf(profile(240), profile(30), profile(120)))?.fps)
        assertEquals(60, CameraProfilePolicy.chooseAuto(listOf(profile(240), profile(60), profile(30)))?.fps)
        assertEquals(240, CameraProfilePolicy.chooseAuto(listOf(profile(240), profile(30)))?.fps)
        assertEquals(30, CameraProfilePolicy.chooseAuto(listOf(profile(30)))?.fps)
    }

    @Test
    fun `same fps prefers 720p before 1080p`() {
        assertEquals(1280, CameraProfilePolicy.chooseAuto(listOf(profile(60), profile(60, 1280, 720)))?.width)
    }

    @Test
    fun `manual selection only binds exact fps and allows same-fps quality fallback`() {
        assertEquals(
            listOf(1280, 1920),
            CameraProfilePolicy.candidates(
                listOf(profile(240), profile(240, 1280, 720), profile(60)),
                240,
            ).map { it.width },
        )
        assertEquals(emptyList<Int>(), CameraProfilePolicy.candidates(listOf(profile(60)), 240).map { it.fps })
        assertEquals(emptyList<Int>(), CameraProfilePolicy.candidates(listOf(profile(240)), 120).map { it.fps })
        assertEquals(listOf(120), CameraProfilePolicy.candidates(listOf(profile(240), profile(120)), 120).map { it.fps })
        assertNull(CameraProfilePolicy.choose(listOf(profile(240)), 120))
    }

    @Test
    fun `auto candidates keep all qualities within each fps in product order`() {
        val candidates = CameraProfilePolicy.candidates(
            listOf(
                profile(30),
                profile(240, 1920, 1080),
                profile(60, 1920, 1080),
                profile(60, 1280, 720),
                profile(120, 1920, 1080),
                profile(120, 1280, 720),
            ),
            preferredFps = 0,
        )

        assertEquals(
            listOf("1280x720@120", "1920x1080@120", "1280x720@60", "1920x1080@60", "1920x1080@240", "1920x1080@30"),
            candidates.map { "${it.width}x${it.height}@${it.fps}" },
        )
        assertEquals(120, CameraProfilePolicy.chooseAuto(candidates)?.fps)
        assertEquals(1280, CameraProfilePolicy.chooseAuto(candidates)?.width)
        assertEquals(1280, CameraProfilePolicy.choose(candidates, preferredFps = 0)?.width)
        assertEquals(120, CameraProfilePolicy.choose(candidates, preferredFps = 0)?.fps)
    }

    @Test
    fun `only product fps are visible`() {
        assertEquals(listOf(60, 120, 240), CameraProfilePolicy.visibleFps)
        assertEquals(listOf(60), CameraProfilePolicy.visible(listOf(profile(60), profile(480))).map { it.fps })
    }
}
