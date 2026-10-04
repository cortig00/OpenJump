package com.openjump.app.tracking

import java.io.Closeable

/** A decoded frame reduced for tracking but tied to the video's full presentation coordinates. */
data class DecodedTrackingFrame(
    val frameIndex: Int,
    val timestampUs: Long,
    val image: GrayFrame,
    val presentationWidth: Int,
    val presentationHeight: Int,
) {
    init {
        require(frameIndex >= 0)
        require(timestampUs >= 0L)
        require(presentationWidth > 0 && presentationHeight > 0)
    }

    fun toTracking(point: ImagePoint): ImagePoint = ImagePoint(
        x = scaleCoordinate(point.x, presentationWidth, image.width),
        y = scaleCoordinate(point.y, presentationHeight, image.height),
    )

    private fun scaleCoordinate(value: Double, fromSize: Int, toSize: Int): Double = when {
        fromSize <= 1 || toSize <= 1 -> 0.0
        else -> value * (toSize - 1.0) / (fromSize - 1.0)
    }
}

interface TrackingFrameSource : Closeable {
    val frameCount: Int

    suspend fun frameAt(index: Int): DecodedTrackingFrame

    /** Return false from [onFrame] to stop decoding immediately after the current frame. */
    suspend fun forEachFrame(
        startIndex: Int,
        onFrame: suspend (DecodedTrackingFrame) -> Boolean,
    )
}

/** Coordinates frame extraction and the UI-independent engine without retaining decoded video frames. */
class OfflineVideoTracker(
    private val source: TrackingFrameSource,
    private val tracker: MotionTracker,
    private val performanceProfiler: TrackingPerformanceProfiler = TrackingPerformanceProfiler(enabled = false),
) : Closeable {

    private val mutableResults = mutableListOf<TrackingFrameResult>()
    val results: List<TrackingFrameResult> get() = mutableResults.toList()
    val samples: List<TrackingSample> get() = mutableResults.map { it.result.sample }

    private var seedFrameIndex: Int? = null

    suspend fun initialize(frameIndex: Int, seedInPresentation: ImagePoint): TrackingFrameResult {
        require(frameIndex in 0 until source.frameCount)
        return initialize(source.frameAt(frameIndex), seedInPresentation)
    }

    /** Reuses a one-shot detection frame instead of decoding and converting the seed frame again. */
    suspend fun initialize(frame: DecodedTrackingFrame, seedInPresentation: ImagePoint): TrackingFrameResult {
        require(frame.frameIndex in 0 until source.frameCount)
        mutableResults.clear()
        val raw = tracker.initialize(frame.image, frame.timestampUs, frame.toTracking(seedInPresentation))
        val result = performanceProfiler.measure(TrackingPerformanceProfiler.Stage.PRESENTATION_MAPPING) {
            TrackingFrameResult(frame.frameIndex, raw.toPresentation(frame))
        }
        mutableResults += result
        seedFrameIndex = frame.frameIndex
        return result
    }

    suspend fun trackRemaining(
        onProgress: suspend (result: TrackingFrameResult, processed: Int, total: Int) -> Unit,
    ): List<TrackingFrameResult> {
        val seed = requireNotNull(seedFrameIndex) { "Initialize before processing." }
        if (mutableResults.last().result.sample.status == TrackingStatus.LOST) return results
        val start = seed + 1
        val total = (source.frameCount - start).coerceAtLeast(0)
        var processed = 0
        source.forEachFrame(start) { frame ->
            val raw = tracker.track(frame.image, frame.timestampUs)
            val result = performanceProfiler.measure(TrackingPerformanceProfiler.Stage.PRESENTATION_MAPPING) {
                TrackingFrameResult(frame.frameIndex, raw.toPresentation(frame))
            }
            mutableResults += result
            processed++
            val publishStartedNs = System.nanoTime()
            try {
                onProgress(result, processed, total)
            } finally {
                performanceProfiler.record(
                    TrackingPerformanceProfiler.Stage.PROGRESS_PUBLISH,
                    System.nanoTime() - publishStartedNs,
                )
            }
            raw.sample.status != TrackingStatus.LOST
        }
        return results
    }

    fun performanceSummary(): String = performanceProfiler.summary()

    override fun close() {
        tracker.reset()
        source.close()
    }

    private fun TrackingResult.toPresentation(frame: DecodedTrackingFrame): TrackingResult {
        val scaleX = if (frame.image.width <= 1) 1.0 else
            (frame.presentationWidth - 1.0) / (frame.image.width - 1.0)
        val scaleY = if (frame.image.height <= 1) 1.0 else
            (frame.presentationHeight - 1.0) / (frame.image.height - 1.0)
        fun ImagePoint.scale() = ImagePoint(x * scaleX, y * scaleY)
        return copy(
            sample = sample.copy(point = sample.point?.scale()),
            region = region?.let {
                ImageRegion(
                    center = it.center.scale(),
                    width = it.width * scaleX,
                    height = it.height * scaleY,
                )
            },
            featurePoints = featurePoints.map { it.scale() },
            diagnostics = diagnostics.copy(
                medianResidualPx = diagnostics.medianResidualPx * (scaleX + scaleY) / 2.0,
            ),
        )
    }
}
