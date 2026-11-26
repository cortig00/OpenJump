package com.openjump.app.video

import android.graphics.Bitmap
import com.openjump.app.tracking.GrayFrame
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Extracts a downsampled, presentation-oriented [GrayFrame] directly from a MediaCodec YUV420
 * luma (Y) plane without allocating intermediate RGB bitmaps or performing RGB-to-gray math.
 */
object MediaCodecYPlaneExtractor {

    /**
     * Maps studio/limited-range luma [16..235] to full-range [0..255] so MediaCodec Y-plane frames
     * match MediaMetadataRetriever RGB-converted luminance and preserve KLT brightness constancy.
     */
    internal val LIMITED_RANGE_TO_FULL_LUT: ByteArray = ByteArray(256) { y ->
        (((y - 16) * 255 + 109) / 219).coerceIn(0, 255).toByte()
    }

    /**
     * Pure JVM-testable extraction from a byte array backing a luma (Y) plane.
     */
    fun extractFromByteArray(
        yBytes: ByteArray,
        bufferOffset: Int = 0,
        rowStride: Int,
        pixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        rotationDegrees: Int,
        maximumDimension: Int,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        expandLimitedRange: Boolean = false,
    ): GrayFrame {
        require(cropWidth > 0 && cropHeight > 0)
        require(rowStride > 0 && pixelStride > 0)
        require(maximumDimension >= 1)
        val rot = ((rotationDegrees % 360) + 360) % 360
        require(rot in setOf(0, 90, 180, 270))

        val swaps = rot == 90 || rot == 270
        val orientedW = if (swaps) cropHeight else cropWidth
        val orientedH = if (swaps) cropWidth else cropHeight
        val (outW, outH) = if (targetWidth != null && targetHeight != null) {
            require(targetWidth > 0 && targetHeight > 0)
            targetWidth to targetHeight
        } else {
            outputSize(orientedW, orientedH, maximumDimension)
        }
        val output = ByteArray(Math.multiplyExact(outW, outH))
        val lut = if (expandLimitedRange) LIMITED_RANGE_TO_FULL_LUT else null

        for (y in 0 until outH) {
            val oy = sampleCoord(y, orientedH, outH)
            val rowOut = y * outW
            for (x in 0 until outW) {
                val ox = sampleCoord(x, orientedW, outW)
                val (sx, sy) = mapToEncoded(ox, oy, cropWidth, cropHeight, rot)
                val idx = bufferOffset + (cropTop + sy) * rowStride + (cropLeft + sx) * pixelStride
                val raw = yBytes[idx]
                output[rowOut + x] = if (lut != null) lut[raw.toInt() and 0xFF] else raw
            }
        }
        return GrayFrame(outW, outH, output)
    }

    /**
     * Direct extraction from a MediaCodec [ByteBuffer] (luma plane `image.planes[0].buffer`).
     * Reads row-by-row into a tiny reusable row buffer when possible, avoiding full-frame copies.
     */
    fun extractFromByteBuffer(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        rotationDegrees: Int,
        maximumDimension: Int,
        rowScratch: ByteArray? = null,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        expandLimitedRange: Boolean = false,
    ): GrayFrame {
        require(cropWidth > 0 && cropHeight > 0)
        require(rowStride > 0 && pixelStride > 0)
        require(maximumDimension >= 1)
        val rot = ((rotationDegrees % 360) + 360) % 360
        require(rot in setOf(0, 90, 180, 270))

        val basePos = buffer.position()
        val swaps = rot == 90 || rot == 270
        val orientedW = if (swaps) cropHeight else cropWidth
        val orientedH = if (swaps) cropWidth else cropHeight
        val (outW, outH) = if (targetWidth != null && targetHeight != null) {
            require(targetWidth > 0 && targetHeight > 0)
            targetWidth to targetHeight
        } else {
            outputSize(orientedW, orientedH, maximumDimension)
        }
        val output = ByteArray(Math.multiplyExact(outW, outH))
        val lut = if (expandLimitedRange) LIMITED_RANGE_TO_FULL_LUT else null

        val neededRowBytes = (cropWidth - 1) * pixelStride + 1
        val scratch = if (rowScratch != null && rowScratch.size >= neededRowBytes) {
            rowScratch
        } else {
            ByteArray(neededRowBytes)
        }
        val dup = buffer.duplicate()

        if (!swaps) {
            for (y in 0 until outH) {
                val oy = sampleCoord(y, orientedH, outH)
                val sy = if (rot == 180) cropHeight - 1 - oy else oy
                val rowStart = basePos + (cropTop + sy) * rowStride + cropLeft * pixelStride
                dup.position(rowStart)
                dup.get(scratch, 0, neededRowBytes)
                val outRow = y * outW
                if (rot == 0 && pixelStride == 1) {
                    if (lut != null) {
                        for (x in 0 until outW) {
                            output[outRow + x] = lut[scratch[sampleCoord(x, orientedW, outW)].toInt() and 0xFF]
                        }
                    } else {
                        for (x in 0 until outW) {
                            output[outRow + x] = scratch[sampleCoord(x, orientedW, outW)]
                        }
                    }
                } else {
                    for (x in 0 until outW) {
                        val ox = sampleCoord(x, orientedW, outW)
                        val sx = if (rot == 180) cropWidth - 1 - ox else ox
                        val raw = scratch[sx * pixelStride]
                        output[outRow + x] = if (lut != null) lut[raw.toInt() and 0xFF] else raw
                    }
                }
            }
        } else {
            for (x in 0 until outW) {
                val ox = sampleCoord(x, orientedW, outW)
                val sy = if (rot == 90) cropHeight - 1 - ox else ox
                val rowStart = basePos + (cropTop + sy) * rowStride + cropLeft * pixelStride
                dup.position(rowStart)
                dup.get(scratch, 0, neededRowBytes)
                for (y in 0 until outH) {
                    val oy = sampleCoord(y, orientedH, outH)
                    val sx = if (rot == 90) oy else cropWidth - 1 - oy
                    val raw = scratch[sx * pixelStride]
                    output[y * outW + x] = if (lut != null) lut[raw.toInt() and 0xFF] else raw
                }
            }
        }
        return GrayFrame(outW, outH, output)
    }

    /** Guarantees exact [targetWidth]×[targetHeight] dimensions without copying when already matched. */
    fun normalizeSize(frame: GrayFrame, targetWidth: Int, targetHeight: Int): GrayFrame {
        require(targetWidth > 0 && targetHeight > 0)
        if (frame.width == targetWidth && frame.height == targetHeight) return frame
        val output = ByteArray(Math.multiplyExact(targetWidth, targetHeight))
        val src = frame.pixels
        val srcW = frame.width
        val srcH = frame.height
        for (y in 0 until targetHeight) {
            val sy = sampleCoord(y, srcH, targetHeight) * srcW
            val outRow = y * targetWidth
            for (x in 0 until targetWidth) {
                output[outRow + x] = src[sy + sampleCoord(x, srcW, targetWidth)]
            }
        }
        return GrayFrame(targetWidth, targetHeight, output)
    }

    /** Builds a compact RGB_565 preview bitmap directly from a [GrayFrame] (already oriented). */
    fun toPreviewBitmap(frame: GrayFrame, maxSide: Int = 384): Bitmap {
        val (w, h) = outputSize(frame.width, frame.height, maxSide)
        val pixels = IntArray(w * h)
        val src = frame.pixels
        val srcW = frame.width
        val srcH = frame.height
        for (y in 0 until h) {
            val sy = sampleCoord(y, srcH, h) * srcW
            val outRow = y * w
            for (x in 0 until w) {
                val luma = src[sy + sampleCoord(x, srcW, w)].toInt() and 0xFF
                pixels[outRow + x] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.RGB_565)
    }

    private fun outputSize(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
        val scale = minOf(1.0, maxDim.toDouble() / max(width, height))
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    private fun sampleCoord(index: Int, sourceExtent: Int, outputExtent: Int): Int =
        (index.toLong() * sourceExtent / outputExtent).toInt()

    private fun mapToEncoded(
        ox: Int,
        oy: Int,
        cropWidth: Int,
        cropHeight: Int,
        rot: Int,
    ): Pair<Int, Int> = when (rot) {
        0 -> ox to oy
        90 -> oy to (cropHeight - 1 - ox)
        180 -> (cropWidth - 1 - ox) to (cropHeight - 1 - oy)
        270 -> (cropWidth - 1 - oy) to ox
        else -> ox to oy
    }
}
