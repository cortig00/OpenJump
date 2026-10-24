package com.openjump.app.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VideoFrameIndexTest {

    private fun index(times: LongArray) = VideoFrameIndex(
        frameTimesUs = times,
        durationUs = times.lastOrNull() ?: 0L,
        width = 1920,
        height = 1080,
        rotationDegrees = 0,
        mimeType = "video/avc",
    )

    @Test
    fun `detecta 240 fps desde PTS reales`() {
        val value = index(longArrayOf(0L, 4_167L, 8_333L, 12_500L, 16_667L))
        assertEquals(240, value.detectedFps)
    }

    @Test
    fun `detecta huecos temporales aunque la mediana indique 240 fps`() {
        // Seis frames a 240 fps seguidos de un hueco de 104 ms, patrón observado
        // en la grabación HS interna del OnePlus. La métrica principal sigue
        // usando PTS, pero el archivo debe declararse no continuo.
        val value = index(longArrayOf(
            0L, 4_167L, 8_333L, 12_500L, 16_667L, 20_833L, 25_000L, 129_122L,
        ))

        assertEquals(240, value.detectedFps)
        assertEquals(1, value.temporalContinuity.gapCount)
        assertEquals(104_122L, value.temporalContinuity.largestGapUs)
        assertEquals(8_334L, value.temporalContinuity.gapThresholdUs)
    }

    @Test
    fun `elige el PTS mas cercano y desempata hacia el anterior`() {
        val value = index(longArrayOf(0L, 10_000L, 20_000L))
        assertEquals(0, value.indexOfNearest(4_999L))
        assertEquals(0, value.indexOfNearest(5_000L))
        assertEquals(1, value.indexOfNearest(5_001L))
        assertEquals(2, value.indexOfNearest(99_000L))
    }

    @Test
    fun `normaliza duracion cuando el primer PTS no es cero`() {
        val absoluteDuration = index(longArrayOf(500_000L, 533_333L, 566_667L)).copy(durationUs = 600_000L)
        val alreadyRelative = absoluteDuration.copy(durationUs = 100_000L)

        assertEquals(100_000L, absoluteDuration.playbackDurationUs)
        assertEquals(100_000L, alreadyRelative.playbackDurationUs)
    }

    @Test
    fun `normaliza orden de presentacion para B frames y rechaza PTS duplicados`() {
        assertArrayEquals(
            longArrayOf(10L, 20L, 30L),
            PresentationTimestampNormalizer.normalize(listOf(30L, 10L, 20L)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PresentationTimestampNormalizer.normalize(listOf(10L, 20L, 10L))
        }
    }

    @Test
    fun `normalizador rechaza coleccion declarada demasiado grande antes de iterarla`() {
        val oversized = object : AbstractCollection<Long>() {
            override val size: Int get() = VideoFrameSafety.MAX_FRAME_COUNT + 1
            override fun iterator(): Iterator<Long> = error("oversized collection must not be iterated")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PresentationTimestampNormalizer.normalize(oversized)
        }
    }

    @Test
    fun `normalizador detiene iterables no acotados en el limite`() {
        var visited = 0
        val unbounded = Iterable {
            object : Iterator<Long> {
                override fun hasNext() = true
                override fun next(): Long = (++visited).toLong()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PresentationTimestampNormalizer.normalize(unbounded)
        }
        assertEquals(VideoFrameSafety.MAX_FRAME_COUNT + 1, visited)
    }

    @Test
    fun `acepta bitrate positivo y rechaza bitrate invalido`() {
        val value = index(longArrayOf(0L, 10_000L)).copy(averageBitrate = 12_000_000)
        assertEquals(12_000_000, value.averageBitrate)
        assertThrows(IllegalArgumentException::class.java) { value.copy(averageBitrate = 0) }
    }

    @Test
    fun `rechaza indices vacios duplicados o desordenados`() {
        assertThrows(IllegalArgumentException::class.java) { index(longArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { index(longArrayOf(0L, 0L)) }
        assertThrows(IllegalArgumentException::class.java) { index(longArrayOf(10L, 0L)) }
    }
}
