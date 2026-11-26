package com.openjump.app.video

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCodecYPlaneExtractorTest {

    @Test
    fun `handles rowStride pixelStride and crop rectangle consistently across array and buffer`() {
        // Logical 4x3 image with crop (left=1, top=1, width=2, height=2)
        // Stored with pixelStride=2 and rowStride=10
        val rowStride = 10
        val pixelStride = 2
        val raw = ByteArray(4 * rowStride)
        fun put(x: Int, y: Int, value: Int) {
            raw[y * rowStride + x * pixelStride] = value.toByte()
        }
        put(1, 1, 10)
        put(2, 1, 20)
        put(1, 2, 30)
        put(2, 2, 40)

        val fromArray = MediaCodecYPlaneExtractor.extractFromByteArray(
            yBytes = raw,
            rowStride = rowStride,
            pixelStride = pixelStride,
            cropLeft = 1,
            cropTop = 1,
            cropWidth = 2,
            cropHeight = 2,
            rotationDegrees = 0,
            maximumDimension = 640,
        )
        val fromBuffer = MediaCodecYPlaneExtractor.extractFromByteBuffer(
            buffer = ByteBuffer.wrap(raw),
            rowStride = rowStride,
            pixelStride = pixelStride,
            cropLeft = 1,
            cropTop = 1,
            cropWidth = 2,
            cropHeight = 2,
            rotationDegrees = 0,
            maximumDimension = 640,
        )

        assertEquals(2, fromArray.width)
        assertEquals(2, fromArray.height)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), fromArray.pixels)
        assertArrayEquals(fromArray.pixels, fromBuffer.pixels)
    }

    @Test
    fun `rotations 0 90 180 270 orient pixels accurately for both array and buffer paths`() {
        // 3x2 encoded image:
        // [1, 2, 3]
        // [4, 5, 6]
        val raw = byteArrayOf(1, 2, 3, 4, 5, 6)

        val expectedByRot = mapOf(
            0 to Triple(3, 2, byteArrayOf(1, 2, 3, 4, 5, 6)),
            90 to Triple(2, 3, byteArrayOf(4, 1, 5, 2, 6, 3)),
            180 to Triple(3, 2, byteArrayOf(6, 5, 4, 3, 2, 1)),
            270 to Triple(2, 3, byteArrayOf(3, 6, 2, 5, 1, 4)),
        )

        for ((rot, expected) in expectedByRot) {
            val (expW, expH, expPixels) = expected
            val arr = MediaCodecYPlaneExtractor.extractFromByteArray(
                yBytes = raw,
                rowStride = 3,
                pixelStride = 1,
                cropLeft = 0,
                cropTop = 0,
                cropWidth = 3,
                cropHeight = 2,
                rotationDegrees = rot,
                maximumDimension = 100,
            )
            val buf = MediaCodecYPlaneExtractor.extractFromByteBuffer(
                buffer = ByteBuffer.wrap(raw),
                rowStride = 3,
                pixelStride = 1,
                cropLeft = 0,
                cropTop = 0,
                cropWidth = 3,
                cropHeight = 2,
                rotationDegrees = rot,
                maximumDimension = 100,
            )
            assertEquals("rot=$rot width", expW, arr.width)
            assertEquals("rot=$rot height", expH, arr.height)
            assertArrayEquals("rot=$rot array", expPixels, arr.pixels)
            assertArrayEquals("rot=$rot buffer", expPixels, buf.pixels)
        }
    }

    @Test
    fun `downsamples to maximumDimension preserving aspect ratio`() {
        val width = 1280
        val height = 720
        val raw = ByteArray(width * height) { i -> (i and 0xFF).toByte() }
        val result = MediaCodecYPlaneExtractor.extractFromByteBuffer(
            buffer = ByteBuffer.wrap(raw),
            rowStride = width,
            pixelStride = 1,
            cropLeft = 0,
            cropTop = 0,
            cropWidth = width,
            cropHeight = height,
            rotationDegrees = 0,
            maximumDimension = 480,
        )
        assertEquals(480, result.width)
        assertEquals(270, result.height)
        assertEquals(480 * 270, result.pixels.size)
    }

    @Test
    fun `limited range LUT maps studio luma 16 to 235 into full range 0 to 255`() {
        val raw = byteArrayOf(0, 16, 126, 235.toByte(), 255.toByte())
        val result = MediaCodecYPlaneExtractor.extractFromByteBuffer(
            buffer = ByteBuffer.wrap(raw),
            rowStride = 5,
            pixelStride = 1,
            cropLeft = 0,
            cropTop = 0,
            cropWidth = 5,
            cropHeight = 1,
            rotationDegrees = 0,
            maximumDimension = 100,
            expandLimitedRange = true,
        )
        val values = result.pixels.map { it.toInt() and 0xFF }
        assertEquals(0, values[0])
        assertEquals(0, values[1])
        assertEquals(128, values[2])
        assertEquals(255, values[3])
        assertEquals(255, values[4])
    }

    @Test
    fun `explicit targetWidth targetHeight and normalizeSize guarantee exact tracking dimensions`() {
        // Simulate macroblock-padded 1920x1088 frame mapped to exact 640x360 target
        val raw = ByteArray(16 * 12) { i -> (i and 0xFF).toByte() }
        val extracted = MediaCodecYPlaneExtractor.extractFromByteBuffer(
            buffer = ByteBuffer.wrap(raw),
            rowStride = 16,
            pixelStride = 1,
            cropLeft = 0,
            cropTop = 0,
            cropWidth = 16,
            cropHeight = 12,
            rotationDegrees = 0,
            maximumDimension = 10,
            targetWidth = 8,
            targetHeight = 5,
        )
        assertEquals(8, extracted.width)
        assertEquals(5, extracted.height)

        val normalized = MediaCodecYPlaneExtractor.normalizeSize(extracted, 6, 4)
        assertEquals(6, normalized.width)
        assertEquals(4, normalized.height)
        // Already-matching size returns same instance without reallocation
        assertEquals(normalized, MediaCodecYPlaneExtractor.normalizeSize(normalized, 6, 4))
    }
}
