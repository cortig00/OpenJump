package com.openjump.app.video.export

import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.video.VideoFrameIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibleExportProfileTest {

    @Test
    fun `mantiene 1080p y limita bitrate aunque la fuente sea HDR de 24 Mbps`() {
        val source = index(1920, 1080, fpsDeltaUs = 33_333L, bitrate = 24_000_000)

        val profile = CompatibleExportProfile.from(source)

        assertFalse(profile.shouldDownscale)
        assertEquals(VideoPresentationGeometry(1920, 1080), profile.targetGeometry)
        assertEquals(30, profile.effectiveFrameRate)
        assertEquals(6_000_000, profile.targetVideoBitrate)
    }

    @Test
    fun `reduce 4K proporcionalmente a 1080 sin cambiar orientacion`() {
        val landscape = CompatibleExportProfile.from(index(3840, 2160, 16_667L))
        val portrait = CompatibleExportProfile.from(index(3840, 2160, 16_667L, rotation = 90))

        assertTrue(landscape.shouldDownscale)
        assertEquals(VideoPresentationGeometry(1920, 1080), landscape.targetGeometry)
        assertEquals(10_000_000, landscape.targetVideoBitrate)
        assertEquals(VideoPresentationGeometry(1080, 1920), portrait.targetGeometry)
    }

    @Test
    fun `usa escalones ligeros para 720 y resolucion pequena`() {
        assertEquals(4_000_000, CompatibleExportProfile.from(index(1280, 720, 33_333L)).targetVideoBitrate)
        assertEquals(6_000_000, CompatibleExportProfile.from(index(1280, 720, 16_667L)).targetVideoBitrate)
        assertEquals(2_500_000, CompatibleExportProfile.from(index(640, 360, 33_333L)).targetVideoBitrate)
        assertEquals(4_000_000, CompatibleExportProfile.from(index(640, 360, 4_167L)).targetVideoBitrate)
    }

    private fun index(
        width: Int,
        height: Int,
        fpsDeltaUs: Long,
        bitrate: Int = 60_000_000,
        rotation: Int = 0,
    ) = VideoFrameIndex(
        frameTimesUs = longArrayOf(0L, fpsDeltaUs, fpsDeltaUs * 2),
        durationUs = fpsDeltaUs * 3,
        width = width,
        height = height,
        rotationDegrees = rotation,
        mimeType = "video/hevc",
        averageBitrate = bitrate,
        colorTransfer = CompatibleExportProfile.COLOR_TRANSFER_HLG,
    )
}
