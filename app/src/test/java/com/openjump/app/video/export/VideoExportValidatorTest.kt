package com.openjump.app.video.export

import com.openjump.app.video.VideoFrameIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VideoExportValidatorTest {

    @Test
    fun `acepta PTS normalizados con pequeno redondeo y rotacion equivalente`() {
        val source = index(
            times = longArrayOf(900_000L, 904_167L, 908_333L, 912_500L),
            duration = 916_667L,
            width = 1920,
            height = 1080,
            rotation = 90,
        )
        val output = index(
            times = longArrayOf(0L, 4_160L, 8_340L, 12_505L),
            duration = 16_600L,
            width = 1080,
            height = 1920,
            rotation = 0,
        )

        val result = VideoExportValidator.validate(source, output)

        assertEquals(4, result.frameCount)
        assertEquals(7L, result.maxPtsDriftUs)
        assertEquals(67L, result.durationDriftUs)
    }

    @Test
    fun `acepta downscale 4K proporcional al perfil 1080`() {
        val source = index(
            times = longArrayOf(0L, 33_333L, 66_667L),
            duration = 100_000L,
            width = 3840,
            height = 2160,
        )
        val output = index(
            times = longArrayOf(0L, 33_333L, 66_667L),
            duration = 100_000L,
            width = 1920,
            height = 1080,
        )

        val result = VideoExportValidator.validate(source, output)

        assertEquals(1920, result.presentationGeometry.width)
        assertEquals(1080, result.presentationGeometry.height)
    }

    @Test
    fun `rechaza frame perdido drift aspecto resolucion excesiva HDR y duracion`() {
        val source = index(longArrayOf(0L, 4_167L, 8_333L), 12_500L)

        assertThrows(IllegalArgumentException::class.java) {
            VideoExportValidator.validate(source, index(longArrayOf(0L, 4_167L), 12_500L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoExportValidator.validate(
                source,
                index(longArrayOf(0L, 4_167L, 9_000L), 12_500L),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoExportValidator.validate(
                source,
                index(longArrayOf(0L, 4_167L, 8_333L), 12_500L, width = 1280, height = 720),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoExportValidator.validate(
                source,
                index(longArrayOf(0L, 4_167L, 8_333L), 12_500L, width = 1920, height = 900),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoExportValidator.validate(
                source,
                index(longArrayOf(0L, 4_167L, 8_333L), 12_500L, colorTransfer = 7),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoExportValidator.validate(
                source,
                index(longArrayOf(0L, 4_167L, 8_333L), 50_000L),
            )
        }
    }

    private fun index(
        times: LongArray,
        duration: Long,
        width: Int = 1920,
        height: Int = 1080,
        rotation: Int = 0,
        colorTransfer: Int? = null,
    ) = VideoFrameIndex(
        frameTimesUs = times,
        durationUs = duration,
        width = width,
        height = height,
        rotationDegrees = rotation,
        mimeType = "video/avc",
        colorTransfer = colorTransfer,
    )
}
