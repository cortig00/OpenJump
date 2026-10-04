package com.openjump.app.video

import com.openjump.app.tracking.GrayFrame
import kotlin.math.max
import kotlin.math.roundToInt

/** Fixed resource envelope for video metadata and temporary tracking-frame conversion. */
internal object VideoFrameSafety {
    const val MAX_FRAME_COUNT = 250_000
    const val MAX_TRACK_COUNT = 64
    const val MAX_DIMENSION = 16_384
    const val MAX_FRAME_AREA = 12_000_000L
    const val MAX_WORKING_BYTES = 64L * 1024L * 1024L
    private const val PREVIEW_COPY_ALLOWANCE_BYTES = 384L * 384L * 4L
    const val MAX_INDEXED_BATCH = 8

    fun checkedArea(width: Int, height: Int): Long {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
            "Video dimensions exceed the processing limit."
        }
        val area = Math.multiplyExact(width.toLong(), height.toLong())
        require(area <= MAX_FRAME_AREA) { "Video frame exceeds the processing limit." }
        return area
    }

    fun checkedGraySize(width: Int, height: Int, maximumDimension: Int): Pair<Int, Int> {
        require(maximumDimension >= 1)
        checkedArea(width, height)
        val scale = minOf(1.0, maximumDimension.toDouble() / max(width, height))
        val grayWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val grayHeight = (height * scale).roundToInt().coerceAtLeast(1)
        Math.multiplyExact(grayWidth, grayHeight)
        return grayWidth to grayHeight
    }

    /** Conservative pre-decode count: padded ARGB_8888 storage + grayscale output + row scratch. */
    fun indexedBatchSize(width: Int, height: Int, maximumDimension: Int): Int {
        val area = checkedArea(width, height)
        val (grayWidth, grayHeight) = checkedGraySize(width, height, maximumDimension)
        val grayBytes = Math.multiplyExact(grayWidth.toLong(), grayHeight.toLong())
        val bitmapUpperBound = Math.addExact(
            Math.multiplyExact(area, 4L),
            Math.multiplyExact(max(width, height).toLong(), 64L),
        )
        val perFrame = Math.addExact(bitmapUpperBound, grayBytes)
        val rowScratch = Math.multiplyExact(max(width, height).toLong(), 4L)
        val available = MAX_WORKING_BYTES - rowScratch - PREVIEW_COPY_ALLOWANCE_BYTES
        require(available >= perFrame) { "Video frame exceeds the processing budget." }
        return (available / perFrame).coerceIn(1L, MAX_INDEXED_BATCH.toLong()).toInt()
    }

    /** Validate actual decoded storage before grayscale or row scratch arrays are allocated. */
    fun validateDecodedBatch(
        dimensionsAndAllocations: List<Triple<Int, Int, Int>>,
        maximumDimension: Int,
    ) {
        require(dimensionsAndAllocations.isNotEmpty())
        var total = 0L
        var widest = 0
        dimensionsAndAllocations.forEach { (width, height, allocationBytes) ->
            val area = checkedArea(width, height)
            require(allocationBytes > 0) { "Decoded frame has no allocated pixel storage." }
            val allocation = allocationBytes.toLong()
            require(allocation <= MAX_WORKING_BYTES) { "Decoded frame exceeds the processing budget." }
            val (grayWidth, grayHeight) = checkedGraySize(width, height, maximumDimension)
            val grayBytes = Math.multiplyExact(grayWidth.toLong(), grayHeight.toLong())
            total = Math.addExact(total, Math.addExact(allocation, grayBytes))
            widest = max(widest, width)
        }
        total = Math.addExact(total, Math.multiplyExact(widest.toLong(), 4L))
        total = Math.addExact(total, PREVIEW_COPY_ALLOWANCE_BYTES)
        require(total <= MAX_WORKING_BYTES) { "Decoded frame batch exceeds the processing budget." }
    }

    /** Exact nearest-coordinate sampling with bounded row scratch, including post-rotation geometry. */
    fun toGray(
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        maximumDimension: Int,
        readRow: (y: Int, destination: IntArray) -> Unit,
    ): GrayFrame {
        checkedArea(sourceWidth, sourceHeight)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        val swaps = rotationDegrees == 90 || rotationDegrees == 270
        val orientedWidth = if (swaps) sourceHeight else sourceWidth
        val orientedHeight = if (swaps) sourceWidth else sourceHeight
        val (outputWidth, outputHeight) = checkedGraySize(orientedWidth, orientedHeight, maximumDimension)
        val output = ByteArray(Math.multiplyExact(outputWidth, outputHeight))
        val row = IntArray(sourceWidth)

        if (!swaps) {
            for (y in 0 until outputHeight) {
                val orientedY = sampleCoordinate(y, orientedHeight, outputHeight)
                val sourceY = if (rotationDegrees == 180) sourceHeight - 1 - orientedY else orientedY
                readRow(sourceY, row)
                for (x in 0 until outputWidth) {
                    val orientedX = sampleCoordinate(x, orientedWidth, outputWidth)
                    val sourceX = if (rotationDegrees == 180) sourceWidth - 1 - orientedX else orientedX
                    output[y * outputWidth + x] = luminance(row[sourceX])
                }
            }
        } else {
            for (x in 0 until outputWidth) {
                val orientedX = sampleCoordinate(x, orientedWidth, outputWidth)
                val sourceY = if (rotationDegrees == 90) sourceHeight - 1 - orientedX else orientedX
                readRow(sourceY, row)
                for (y in 0 until outputHeight) {
                    val orientedY = sampleCoordinate(y, orientedHeight, outputHeight)
                    val sourceX = if (rotationDegrees == 90) orientedY else sourceWidth - 1 - orientedY
                    output[y * outputWidth + x] = luminance(row[sourceX])
                }
            }
        }
        return GrayFrame(outputWidth, outputHeight, output)
    }

    private fun sampleCoordinate(index: Int, sourceExtent: Int, outputExtent: Int): Int =
        (index.toLong() * sourceExtent / outputExtent).toInt()

    private fun luminance(color: Int): Byte {
        val red = color shr 16 and 0xFF
        val green = color shr 8 and 0xFF
        val blue = color and 0xFF
        return ((77 * red + 150 * green + 29 * blue) shr 8).toByte()
    }
}
