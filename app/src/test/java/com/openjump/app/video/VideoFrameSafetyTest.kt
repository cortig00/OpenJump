package com.openjump.app.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameSafetyTest {

    @Test
    fun `dimension and area validation uses bounded checked geometry`() {
        assertEquals(8_294_400L, VideoFrameSafety.checkedArea(3840, 2160))
        assertThrows(IllegalArgumentException::class.java) {
            VideoFrameSafety.checkedArea(16_385, 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoFrameSafety.checkedArea(4000, 4000)
        }
    }

    @Test
    fun `indexed batch budget includes conservative bitmap storage and gray output`() {
        assertEquals(1, VideoFrameSafety.indexedBatchSize(4000, 3000, 720))
        val hdBatch = VideoFrameSafety.indexedBatchSize(1920, 1080, 720)
        assertTrue(hdBatch in 1..VideoFrameSafety.MAX_INDEXED_BATCH)
    }

    @Test
    fun `actual allocation batch is rejected before conversion when total exceeds budget`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoFrameSafety.validateDecodedBatch(
                List(3) { Triple(1920, 1080, 30 * 1024 * 1024) },
                maximumDimension = 720,
            )
        }
    }

    @Test
    fun `bounded nearest sampling preserves non-multiple dimensions and 0 90 180 270 rotations`() {
        fun frame(rotation: Int) = VideoFrameSafety.toGray(
            sourceWidth = 5,
            sourceHeight = 3,
            rotationDegrees = rotation,
            maximumDimension = 4,
            readRow = { y, row ->
                for (x in row.indices) {
                    val value = y * 10 + x
                    row[x] = (value shl 16) or (value shl 8) or value
                }
            },
        )

        val normal = frame(0)
        assertEquals(4, normal.width)
        assertEquals(2, normal.height)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 10, 11, 12, 13), normal.pixels)

        val clockwise = frame(90)
        assertEquals(2, clockwise.width)
        assertEquals(4, clockwise.height)
        assertArrayEquals(byteArrayOf(20, 10, 21, 11, 22, 12, 23, 13), clockwise.pixels)

        val upsideDown = frame(180)
        assertArrayEquals(byteArrayOf(24, 23, 22, 21, 14, 13, 12, 11), upsideDown.pixels)

        val counterClockwise = frame(270)
        assertArrayEquals(byteArrayOf(4, 14, 3, 13, 2, 12, 1, 11), counterClockwise.pixels)
    }
}
