package com.openjump.app.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.openjump.app.tracking.DecodedTrackingFrame
import com.openjump.app.tracking.GrayFrame
import com.openjump.app.tracking.TrackingFrameSource
import com.openjump.app.tracking.TrackingPerformanceProfiler
import com.openjump.app.tracking.VideoPresentationGeometry
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Offline decoder. It never retains more than a small bitmap batch and emits grayscale frames. */
class VideoTrackingFrameSource(
    private val context: Context,
    private val uri: Uri,
    private val index: VideoFrameIndex,
    private val maximumTrackingDimension: Int = 720,
    private val performanceProfiler: TrackingPerformanceProfiler = TrackingPerformanceProfiler(enabled = false),
    /** Optional borrowed-bitmap observation; the receiver must copy anything it retains. */
    private val previewObserver: ((frameIndex: Int, ptsUs: Long, borrowedBitmap: Bitmap, rotationDegrees: Int) -> Unit)? = null,
) : TrackingFrameSource {

    override val frameCount: Int get() = index.frameCount

    private val presentation = VideoPresentationGeometry.fromEncoded(
        index.width,
        index.height,
        index.rotationDegrees,
    )
    private var retriever: MediaMetadataRetriever? = null

    init {
        require(maximumTrackingDimension >= 64)
        VideoFrameSafety.checkedArea(index.width, index.height)
        VideoFrameSafety.indexedBatchSize(index.width, index.height, maximumTrackingDimension)
    }

    override suspend fun frameAt(index: Int): DecodedTrackingFrame = withContext(Dispatchers.IO) {
        require(index in 0 until frameCount)
        coroutineContext.ensureActive()
        val decoder = decoder()
        val bitmap = performanceProfiler.measure(TrackingPerformanceProfiler.Stage.DECODE) {
            if (Build.VERSION.SDK_INT >= 28 && retrieverFrameCount(decoder) == frameCount) {
                frameAtIndex(decoder, index)
            } else {
                frameAtTime(decoder, this@VideoTrackingFrameSource.index.timeOf(index))
            }
        } ?: error("No se pudo decodificar el frame ${index + 1}.")
        try {
            coroutineContext.ensureActive()
            validateDecodedBatch(listOf(bitmap))
            decoded(index, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun forEachFrame(
        startIndex: Int,
        onFrame: suspend (DecodedTrackingFrame) -> Boolean,
    ) = withContext(Dispatchers.IO) {
        require(startIndex in 0..frameCount)
        if (startIndex == frameCount) return@withContext
        val decoder = decoder()
        var next = startIndex
        val canUseIndexedBatches = Build.VERSION.SDK_INT >= 28 && retrieverFrameCount(decoder) == frameCount

        if (canUseIndexedBatches) {
            while (next < frameCount) {
                coroutineContext.ensureActive()
                val count = minOf(VideoFrameSafety.indexedBatchSize(index.width, index.height, maximumTrackingDimension), frameCount - next)
                val decodeStartedNs = System.nanoTime()
                val bitmaps = try {
                    framesAtIndex(decoder, next, count)
                } catch (_: RuntimeException) {
                    emptyList()
                } finally {
                    performanceProfiler.record(
                        TrackingPerformanceProfiler.Stage.DECODE,
                        System.nanoTime() - decodeStartedNs,
                        repetitions = count,
                    )
                }
                if (bitmaps.size != count) {
                    bitmaps.forEach(Bitmap::recycle)
                    break
                }
                try {
                    validateDecodedBatch(bitmaps)
                    var keepGoing = true
                    for (offset in bitmaps.indices) {
                        coroutineContext.ensureActive()
                        val bitmap = bitmaps[offset]
                        val frameIndex = next + offset
                        previewObserver?.invoke(
                            frameIndex,
                            this@VideoTrackingFrameSource.index.timeOf(frameIndex),
                            bitmap,
                            rotationStillRequired(bitmap),
                        )
                        keepGoing = onFrame(decoded(frameIndex, bitmap))
                        if (!keepGoing) break
                    }
                    next += count
                    if (!keepGoing) return@withContext
                } finally {
                    bitmaps.forEach(Bitmap::recycle)
                }
            }
        }

        // API 26–27, mismatched frame counts, or an indexed decode failure: exact PTS fallback.
        while (next < frameCount) {
            coroutineContext.ensureActive()
            val bitmap = performanceProfiler.measure(TrackingPerformanceProfiler.Stage.DECODE) {
                frameAtTime(decoder, index.timeOf(next))
            } ?: error("No se pudo decodificar el frame ${next + 1}.")
            val keepGoing = try {
                coroutineContext.ensureActive()
                validateDecodedBatch(listOf(bitmap))
                previewObserver?.invoke(next, index.timeOf(next), bitmap, rotationStillRequired(bitmap))
                onFrame(decoded(next, bitmap))
            } finally {
                bitmap.recycle()
            }
            next++
            if (!keepGoing) return@withContext
        }
    }

    override fun close() {
        try {
            retriever?.release()
        } finally {
            retriever = null
        }
    }

    private fun decoder(): MediaMetadataRetriever = retriever ?: MediaMetadataRetriever().also {
        it.setDataSource(context, uri)
        retriever = it
    }

    private fun retrieverFrameCount(decoder: MediaMetadataRetriever): Int? =
        if (Build.VERSION.SDK_INT >= 28) {
            decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
        } else {
            null
        }

    @androidx.annotation.RequiresApi(28)
    private fun frameAtIndex(decoder: MediaMetadataRetriever, frameIndex: Int): Bitmap? =
        decoder.getFrameAtIndex(frameIndex, bitmapParams())

    @androidx.annotation.RequiresApi(28)
    private fun framesAtIndex(
        decoder: MediaMetadataRetriever,
        frameIndex: Int,
        count: Int,
    ): List<Bitmap> = decoder.getFramesAtIndex(frameIndex, count, bitmapParams())

    @androidx.annotation.RequiresApi(28)
    private fun bitmapParams() = MediaMetadataRetriever.BitmapParams().apply {
        preferredConfig = Bitmap.Config.RGB_565
    }

    private fun frameAtTime(decoder: MediaMetadataRetriever, timestampUs: Long): Bitmap? =
        if (Build.VERSION.SDK_INT >= 27) {
            scaledFrameAtTime(decoder, timestampUs)
        } else {
            // API 26 has no scaled retriever call. The bitmap is downsampled immediately below.
            decoder.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST)
        }

    @androidx.annotation.RequiresApi(27)
    private fun scaledFrameAtTime(decoder: MediaMetadataRetriever, timestampUs: Long): Bitmap? =
        decoder.getScaledFrameAtTime(
            timestampUs,
            MediaMetadataRetriever.OPTION_CLOSEST,
            minOf(presentation.width, maximumTrackingDimension),
            minOf(presentation.height, maximumTrackingDimension),
        )

    private fun validateDecodedBatch(bitmaps: List<Bitmap>) {
        VideoFrameSafety.validateDecodedBatch(
            bitmaps.map { bitmap -> Triple(bitmap.width, bitmap.height, bitmap.allocationByteCount) },
            maximumTrackingDimension,
        )
    }

    private fun decoded(frameIndex: Int, bitmap: Bitmap): DecodedTrackingFrame {
        validateDecodedBatch(listOf(bitmap))
        val rotationToApply = rotationStillRequired(bitmap)
        return DecodedTrackingFrame(
            frameIndex = frameIndex,
            timestampUs = index.timeOf(frameIndex),
            image = performanceProfiler.measure(TrackingPerformanceProfiler.Stage.BITMAP_TO_GRAY) {
                bitmapToGray(bitmap, rotationToApply)
            },
            presentationWidth = presentation.width,
            presentationHeight = presentation.height,
        )
    }

    /** Android normally applies container rotation. Dimension/aspect comparison handles codecs that do not. */
    private fun rotationStillRequired(bitmap: Bitmap): Int {
        val rotation = ((index.rotationDegrees % 360) + 360) % 360
        if (rotation == 0 || rotation == 180) return 0
        val bitmapAspect = bitmap.width.toDouble() / bitmap.height
        val presentationAspect = presentation.width.toDouble() / presentation.height
        val encodedAspect = index.width.toDouble() / index.height
        return if (abs(bitmapAspect - presentationAspect) <= abs(bitmapAspect - encodedAspect)) 0 else rotation
    }

    private fun bitmapToGray(bitmap: Bitmap, rotationDegrees: Int): GrayFrame =
        VideoFrameSafety.toGray(
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
            rotationDegrees = rotationDegrees,
            maximumDimension = maximumTrackingDimension,
        ) { y, row -> bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1) }
}
