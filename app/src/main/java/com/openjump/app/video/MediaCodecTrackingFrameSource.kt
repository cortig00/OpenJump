package com.openjump.app.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.openjump.app.tracking.DecodedTrackingFrame
import com.openjump.app.tracking.TrackingFrameSource
import com.openjump.app.tracking.TrackingPerformanceProfiler
import com.openjump.app.tracking.VideoPresentationGeometry
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Hardware sequential [MediaCodec] luma-plane decoder for offline Encoder tracking.
 *
 * Performs a single forward pass with [MediaExtractor] + [MediaCodec], reading only the Y (luma)
 * plane directly into a downsampled [com.openjump.app.tracking.GrayFrame] and overlapping hardware
 * decode with KLT tracking via a bounded channel. If a device's decoder cannot supply usable YUV
 * frames for a given clip, it transparently falls back to [VideoTrackingFrameSource] starting at
 * the exact remaining frame index.
 */
class MediaCodecTrackingFrameSource(
    private val context: Context,
    private val uri: Uri,
    private val index: VideoFrameIndex,
    private val maximumTrackingDimension: Int = TrackingDeviceProfile.recommendedTrackingDimension(context),
    private val performanceProfiler: TrackingPerformanceProfiler = TrackingPerformanceProfiler(enabled = false),
    private val previewObserver: ((frameIndex: Int, ptsUs: Long, borrowedBitmap: Bitmap, rotationDegrees: Int) -> Unit)? = null,
    private val decoderDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val previewMinIntervalMs: Long = DEFAULT_PREVIEW_MIN_INTERVAL_MS,
) : TrackingFrameSource {

    override val frameCount: Int get() = index.frameCount

    private val presentation = VideoPresentationGeometry.fromEncoded(
        index.width,
        index.height,
        index.rotationDegrees,
    )

    private val targetDimensions: Pair<Int, Int> = VideoFrameSafety.checkedGraySize(
        presentation.width,
        presentation.height,
        maximumTrackingDimension,
    )
    private val targetWidth: Int get() = targetDimensions.first
    private val targetHeight: Int get() = targetDimensions.second

    private var fallbackSource: VideoTrackingFrameSource? = null

    private fun fallback(): VideoTrackingFrameSource = fallbackSource ?: VideoTrackingFrameSource(
        context = context,
        uri = uri,
        index = index,
        maximumTrackingDimension = maximumTrackingDimension,
        performanceProfiler = performanceProfiler,
        previewObserver = previewObserver,
        decoderDispatcher = decoderDispatcher,
    ).also { fallbackSource = it }

    private fun normalizeFrame(frame: DecodedTrackingFrame): DecodedTrackingFrame {
        val normalized = MediaCodecYPlaneExtractor.normalizeSize(frame.image, targetWidth, targetHeight)
        return if (normalized === frame.image) frame else frame.copy(image = normalized)
    }

    override suspend fun frameAt(index: Int): DecodedTrackingFrame =
        normalizeFrame(fallback().frameAt(index))

    override suspend fun forEachFrame(
        startIndex: Int,
        onFrame: suspend (DecodedTrackingFrame) -> Boolean,
    ) {
        require(startIndex in 0..frameCount)
        if (startIndex == frameCount) return

        var nextIndexToDeliver = startIndex
        var stoppedByConsumer = false
        var hardwareFailed = false

        try {
            coroutineScope {
                val channel = Channel<DecodedTrackingFrame>(capacity = PIPELINE_CAPACITY)
                val producer = launch(decoderDispatcher) {
                    try {
                        decodeSequentialIntoChannel(startIndex, channel)
                        channel.close()
                    } catch (cancelled: CancellationException) {
                        channel.close(cancelled)
                        throw cancelled
                    } catch (failure: Throwable) {
                        channel.close(failure)
                    }
                }

                var lastPreviewUptimeMs = 0L
                try {
                    for (decoded in channel) {
                        coroutineContext.ensureActive()
                        if (decoded.frameIndex != nextIndexToDeliver) {
                            hardwareFailed = true
                            break
                        }
                        val observer = previewObserver
                        if (observer != null) {
                            val now = SystemClock.uptimeMillis()
                            val isLast = decoded.frameIndex == frameCount - 1
                            if (isLast || lastPreviewUptimeMs == 0L || now - lastPreviewUptimeMs >= previewMinIntervalMs) {
                                lastPreviewUptimeMs = now
                                val preview = MediaCodecYPlaneExtractor.toPreviewBitmap(decoded.image)
                                try {
                                    observer(decoded.frameIndex, decoded.timestampUs, preview, 0)
                                } finally {
                                    preview.recycle()
                                }
                            }
                        }
                        val keepGoing = onFrame(decoded)
                        nextIndexToDeliver++
                        if (!keepGoing) {
                            stoppedByConsumer = true
                            break
                        }
                    }
                } catch (cancelled: CancellationException) {
                    producer.cancel()
                    throw cancelled
                } catch (_: Throwable) {
                    hardwareFailed = true
                } finally {
                    producer.cancel()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            hardwareFailed = true
        }

        if (!stoppedByConsumer && (hardwareFailed || nextIndexToDeliver < frameCount)) {
            fallback().forEachFrame(nextIndexToDeliver) { fallbackFrame ->
                onFrame(normalizeFrame(fallbackFrame))
            }
        }
    }

    private suspend fun decodeSequentialIntoChannel(
        startIndex: Int,
        channel: Channel<DecodedTrackingFrame>,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Unable to open video descriptor.")
            pfd.use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor)
            }
            val trackIndex = selectVideoTrack(extractor)
                ?: throw IllegalStateException("No video track found.")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Missing video MIME.")
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )

            codec = MediaCodec.createDecoderByType(mime).also { decoder ->
                decoder.configure(format, null, null, 0)
                decoder.start()
            }

            val startPtsUs = index.timeOf(startIndex)
            extractor.seekTo(startPtsUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var eosDrainRetries = 0
            var nextExpected = startIndex
            var rowScratch: ByteArray? = null
            var expandLimitedRange = isLimitedColorRange(format)
            var activeOutputFormat: MediaFormat = format

            while (nextExpected < frameCount) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime
                                val flags = extractor.sampleFlags
                                codec.queueInputBuffer(inIdx, 0, sampleSize, pts, flags)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        eosDrainRetries = 0
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val hasData = bufferInfo.size > 0
                        var emitted: DecodedTrackingFrame? = null
                        try {
                            if (hasData) {
                                val pts = bufferInfo.presentationTimeUs
                                val targetPts = index.timeOf(nextExpected)
                                val toleranceUs = ptsToleranceUs(nextExpected)
                                if (pts + toleranceUs >= targetPts) {
                                    // Match exact target index (allowing minor codec PTS rounding)
                                    val matched = matchFrameIndex(pts, nextExpected, toleranceUs)
                                    if (matched == nextExpected) {
                                        val image = codec.getOutputImage(outIdx)
                                            ?: throw IllegalStateException("Decoder did not expose YUV output Image.")
                                        try {
                                            val plane = image.planes.firstOrNull()
                                                ?: throw IllegalStateException("Missing Y plane.")
                                            val crop = resolveCropRect(image, activeOutputFormat)
                                            val cropL = crop[0]
                                            val cropT = crop[1]
                                            val cropW = crop[2]
                                            val cropH = crop[3]
                                            val rotationToApply = rotationStillRequired(cropW, cropH)
                                            val needed = (cropW - 1) * plane.pixelStride + 1
                                            if (rowScratch == null || rowScratch.size < needed) {
                                                rowScratch = ByteArray(needed)
                                            }
                                            val gray = performanceProfiler.measure(TrackingPerformanceProfiler.Stage.BITMAP_TO_GRAY) {
                                                MediaCodecYPlaneExtractor.extractFromByteBuffer(
                                                    buffer = plane.buffer,
                                                    rowStride = plane.rowStride,
                                                    pixelStride = plane.pixelStride,
                                                    cropLeft = cropL,
                                                    cropTop = cropT,
                                                    cropWidth = cropW,
                                                    cropHeight = cropH,
                                                    rotationDegrees = rotationToApply,
                                                    maximumDimension = maximumTrackingDimension,
                                                    rowScratch = rowScratch,
                                                    targetWidth = targetWidth,
                                                    targetHeight = targetHeight,
                                                    expandLimitedRange = expandLimitedRange,
                                                )
                                            }
                                            emitted = DecodedTrackingFrame(
                                                frameIndex = nextExpected,
                                                timestampUs = targetPts,
                                                image = gray,
                                                presentationWidth = presentation.width,
                                                presentationHeight = presentation.height,
                                            )
                                            performanceProfiler.count(TrackingPerformanceProfiler.Metric.DELIVERED_FRAMES)
                                            nextExpected++
                                        } finally {
                                            image.close()
                                        }
                                    } else if (matched > nextExpected) {
                                        // Decoder skipped an indexed frame -> bail out to exact fallback
                                        throw IllegalStateException("Hardware decoder skipped frame $nextExpected.")
                                    }
                                }
                            }
                        } finally {
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                        if (emitted != null) {
                            channel.send(emitted)
                        }
                        if (isEos) break
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        activeOutputFormat = codec.outputFormat
                        expandLimitedRange = isLimitedColorRange(activeOutputFormat)
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone && ++eosDrainRetries >= MAX_EOS_DRAIN_RETRIES) break
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun isLimitedColorRange(format: MediaFormat): Boolean {
        if (!format.containsKey(MediaFormat.KEY_COLOR_RANGE)) return true
        return runCatching { format.getInteger(MediaFormat.KEY_COLOR_RANGE) }
            .getOrDefault(MediaFormat.COLOR_RANGE_LIMITED) != MediaFormat.COLOR_RANGE_FULL
    }

    private fun resolveCropRect(image: android.media.Image, format: MediaFormat): IntArray {
        val imgW = image.width
        val imgH = image.height
        val crop = image.cropRect
        var left = if (crop.width() > 0 && crop.height() > 0) crop.left.coerceIn(0, imgW - 1) else 0
        var top = if (crop.width() > 0 && crop.height() > 0) crop.top.coerceIn(0, imgH - 1) else 0
        var width = if (crop.width() > 0 && crop.height() > 0) crop.width().coerceIn(1, imgW - left) else imgW
        var height = if (crop.width() > 0 && crop.height() > 0) crop.height().coerceIn(1, imgH - top) else imgH

        if (width == imgW && height == imgH &&
            format.containsKey("crop-left") && format.containsKey("crop-right") &&
            format.containsKey("crop-top") && format.containsKey("crop-bottom")
        ) {
            val fLeft = format.getInteger("crop-left").coerceIn(0, imgW - 1)
            val fTop = format.getInteger("crop-top").coerceIn(0, imgH - 1)
            val fWidth = (format.getInteger("crop-right") - fLeft + 1).coerceIn(1, imgW - fLeft)
            val fHeight = (format.getInteger("crop-bottom") - fTop + 1).coerceIn(1, imgH - fTop)
            left = fLeft
            top = fTop
            width = fWidth
            height = fHeight
        }
        // Trim uncropped 16px macroblock padding when dimensions exceed the container's encoded size.
        if (left == 0 && width > index.width && width - index.width <= 32) width = index.width
        if (top == 0 && height > index.height && height - index.height <= 32) height = index.height
        return intArrayOf(left, top, width, height)
    }

    private fun rotationStillRequired(cropWidth: Int, cropHeight: Int): Int {
        val rotation = ((index.rotationDegrees % 360) + 360) % 360
        if (rotation == 0 || rotation == 180) return rotation
        val decodedAspect = cropWidth.toDouble() / cropHeight
        val presentationAspect = presentation.width.toDouble() / presentation.height
        val encodedAspect = index.width.toDouble() / index.height
        return if (abs(decodedAspect - presentationAspect) < abs(decodedAspect - encodedAspect)) 0 else rotation
    }

    private fun ptsToleranceUs(expectedIndex: Int): Long {
        val stepUs = when {
            expectedIndex + 1 < frameCount -> index.timeOf(expectedIndex + 1) - index.timeOf(expectedIndex)
            expectedIndex > 0 -> index.timeOf(expectedIndex) - index.timeOf(expectedIndex - 1)
            else -> 16_667L
        }.coerceAtLeast(1_000L)
        return (stepUs / 3L).coerceIn(MIN_PTS_TOLERANCE_US, MAX_PTS_TOLERANCE_US)
    }

    private fun matchFrameIndex(ptsUs: Long, expectedIndex: Int, toleranceUs: Long): Int {
        val expectedPts = index.timeOf(expectedIndex)
        if (abs(ptsUs - expectedPts) <= toleranceUs) return expectedIndex
        if (ptsUs < expectedPts) return -1
        var probe = expectedIndex + 1
        while (probe < frameCount && probe <= expectedIndex + 4) {
            if (abs(ptsUs - index.timeOf(probe)) <= toleranceUs) return probe
            probe++
        }
        return if (ptsUs > expectedPts) expectedIndex + 1 else -1
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("video/")) return i
        }
        return null
    }

    override fun close() {
        fallbackSource?.close()
        fallbackSource = null
    }

    private companion object {
        const val PIPELINE_CAPACITY = 3
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val MAX_EOS_DRAIN_RETRIES = 50
        const val MIN_PTS_TOLERANCE_US = 1_500L
        const val MAX_PTS_TOLERANCE_US = 6_000L
        const val DEFAULT_PREVIEW_MIN_INTERVAL_MS = 150L
    }
}
