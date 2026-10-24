package com.openjump.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraProfilePolicyResolutionTest {

    private fun profile(
        fps: Int,
        width: Int = 1920,
        height: Int = 1080,
        quality: String = "FHD",
    ) = CameraProductProfile(
        "rear",
        quality,
        width,
        height,
        fps,
        if (fps >= 120) CameraSessionKind.HIGH_SPEED else CameraSessionKind.NORMAL,
    )

    private fun mixed(): List<CameraProductProfile> = listOf(
        profile(30),
        profile(240, 1920, 1080),
        profile(240, 1280, 720, "HD"),
        profile(60, 1920, 1080),
        profile(60, 1280, 720, "HD"),
        profile(120, 1920, 1080),
        profile(120, 1280, 720, "HD"),
    )

    @Test
    fun `auto default matches explicit auto on every call`() {
        val profiles = mixed()
        assertEquals(
            CameraProfilePolicy.chooseAuto(profiles, CameraResolutionPreference.AUTO),
            CameraProfilePolicy.chooseAuto(profiles),
        )
        assertEquals(
            CameraProfilePolicy.candidates(profiles, 120, CameraResolutionPreference.AUTO),
            CameraProfilePolicy.candidates(profiles, 120),
        )
        assertEquals(
            CameraProfilePolicy.candidates(profiles, 0, CameraResolutionPreference.AUTO),
            CameraProfilePolicy.candidates(profiles, 0),
        )
        assertEquals(
            CameraProfilePolicy.choose(profiles, 60, CameraResolutionPreference.AUTO),
            CameraProfilePolicy.choose(profiles, 60),
        )
    }

    @Test
    fun `auto keeps fps order 120 60 240 30 with 720p before 1080p`() {
        val auto = CameraProfilePolicy.chooseAuto(mixed())
        assertEquals(120, auto?.fps)
        assertEquals(1280, auto?.width)

        val ordered = CameraProfilePolicy.candidates(mixed(), 0)
            .map { "${it.width}x${it.height}@${it.fps}" }
        assertEquals(
            listOf(
                "1280x720@120",
                "1920x1080@120",
                "1280x720@60",
                "1920x1080@60",
                "1280x720@240",
                "1920x1080@240",
                "1920x1080@30",
            ),
            ordered,
        )
    }

    @Test
    fun `manual 720p binds exact fps at 240 120 and 60`() {
        for (fps in listOf(240, 120, 60)) {
            val candidates = CameraProfilePolicy.candidates(
                mixed(),
                fps,
                CameraResolutionPreference.HD_720P,
            )
            assertEquals(listOf("1280x720@$fps"), candidates.map { "${it.width}x${it.height}@${it.fps}" })
            val chosen = CameraProfilePolicy.choose(mixed(), fps, CameraResolutionPreference.HD_720P)
            assertEquals(1280, chosen?.width)
            assertEquals(720, chosen?.height)
            assertEquals(fps, chosen?.fps)
        }
    }

    @Test
    fun `manual 1080p binds exact fps at 240 120 and 60`() {
        for (fps in listOf(240, 120, 60)) {
            val candidates = CameraProfilePolicy.candidates(
                mixed(),
                fps,
                CameraResolutionPreference.FHD_1080P,
            )
            assertEquals(listOf("1920x1080@$fps"), candidates.map { "${it.width}x${it.height}@${it.fps}" })
            val chosen = CameraProfilePolicy.choose(mixed(), fps, CameraResolutionPreference.FHD_1080P)
            assertEquals(1920, chosen?.width)
            assertEquals(1080, chosen?.height)
            assertEquals(fps, chosen?.fps)
        }
    }

    @Test
    fun `auto fps with manual resolution keeps fps order inside that resolution`() {
        val hd = CameraProfilePolicy.candidates(mixed(), 0, CameraResolutionPreference.HD_720P)
            .map { "${it.width}x${it.height}@${it.fps}" }
        assertEquals(listOf("1280x720@120", "1280x720@60", "1280x720@240"), hd)

        val fhd = CameraProfilePolicy.candidates(mixed(), 0, CameraResolutionPreference.FHD_1080P)
            .map { "${it.width}x${it.height}@${it.fps}" }
        assertEquals(listOf("1920x1080@120", "1920x1080@60", "1920x1080@240", "1920x1080@30"), fhd)

        val autoHd = CameraProfilePolicy.chooseAuto(mixed(), CameraResolutionPreference.HD_720P)
        assertEquals(120, autoHd?.fps)
        assertEquals(1280, autoHd?.width)
    }

    @Test
    fun `unavailable profiles resolve to empty without fallback`() {
        assertTrue(
            CameraProfilePolicy.candidates(
                emptyList(),
                120,
                CameraResolutionPreference.HD_720P,
            ).isEmpty(),
        )
        assertNull(CameraProfilePolicy.chooseAuto(emptyList(), CameraResolutionPreference.FHD_1080P))
        assertNull(CameraProfilePolicy.choose(emptyList(), 60, CameraResolutionPreference.HD_720P))
    }

    @Test
    fun `full profile mismatch never records the other resolution`() {
        val only1080pAt240 = listOf(profile(240, 1920, 1080))
        assertTrue(
            CameraProfilePolicy.candidates(
                only1080pAt240,
                240,
                CameraResolutionPreference.HD_720P,
            ).isEmpty(),
        )
        assertNull(CameraProfilePolicy.choose(only1080pAt240, 240, CameraResolutionPreference.HD_720P))

        val only720pAt60 = listOf(profile(60, 1280, 720, "HD"))
        assertTrue(
            CameraProfilePolicy.candidates(
                only720pAt60,
                60,
                CameraResolutionPreference.FHD_1080P,
            ).isEmpty(),
        )
        assertNull(CameraProfilePolicy.choose(only720pAt60, 60, CameraResolutionPreference.FHD_1080P))
    }

    @Test
    fun `unknown dimensions are never treated as 720p`() {
        val unknown = profile(120, 640, 480, "HD")
        assertTrue(
            CameraProfilePolicy.candidates(
                listOf(unknown),
                120,
                CameraResolutionPreference.HD_720P,
            ).isEmpty(),
        )
        assertTrue(
            CameraProfilePolicy.candidates(
                listOf(unknown),
                120,
                CameraResolutionPreference.FHD_1080P,
            ).isEmpty(),
        )
        assertNull(CameraProfilePolicy.chooseAuto(listOf(unknown), CameraResolutionPreference.HD_720P))
        // Auto still sees the profile; only the manual resolution scopes exclude it.
        assertEquals(unknown, CameraProfilePolicy.chooseAuto(listOf(unknown)))
    }
}
