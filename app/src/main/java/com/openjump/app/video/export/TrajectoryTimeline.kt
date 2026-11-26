package com.openjump.app.video.export

import com.openjump.app.encoder.EncoderSample
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingDiagnostics
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.TrackingResult
import com.openjump.app.tracking.TrackingSample
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.video.VideoFrameIndex

/** A tracking sample aligned to Transformer's zero-based presentation timeline. */
data class TrajectorySample(
    val frameIndex: Int,
    val presentationTimeUs: Long,
    val point: ImagePoint?,
    val status: TrackingStatus,
    /**
     * True when this sample starts a new path segment instead of joining the
     * previous point. Mirrors the review rule in EncoderPlaybackTimeline:
     * LOST, a missing frameIndex, or a PTS jump beyond 1.5x the source median
     * delta. Export inputs carry no ObservationKind, so absent frames surface
     * here as frameIndex gaps; LOST samples already carry a null point.
     */
    val breaksPathBefore: Boolean = false,
)

/**
 * Immutable PTS mapping used by the exporter.
 *
 * Selection is floor-based: a sample is never shown before its source frame is presented.
 */
class TrajectoryTimeline(
    sourceIndex: VideoFrameIndex,
    results: List<TrackingFrameResult>,
) {
    val samples: List<TrajectorySample>

    init {
        require(results.isNotEmpty()) { "No hay trayectoria para exportar." }
        require(results.any { it.result.sample.point != null }) {
            "El tracking no produjo ninguna posición exportable."
        }
        require(results.zipWithNext().all { (a, b) -> a.frameIndex < b.frameIndex }) {
            "Los resultados de tracking deben estar ordenados y sin frames duplicados."
        }
        val firstSourcePtsUs = sourceIndex.frameTimesUs.first()
        val sourceDeltas = sourceIndex.frameTimesUs.toList().zipWithNext { a, b -> b - a }
        val medianSourceDeltaUs = sourceDeltas.sorted().let { deltas ->
            when {
                deltas.isEmpty() -> Long.MAX_VALUE
                deltas.size % 2 == 1 -> deltas[deltas.size / 2]
                else -> (deltas[deltas.size / 2 - 1] + deltas[deltas.size / 2]) / 2L
            }
        }
        samples = results.mapIndexed { position, frame ->
            require(frame.frameIndex in 0 until sourceIndex.frameCount) {
                "Frame de tracking fuera del vídeo: ${frame.frameIndex}."
            }
            val canonicalPtsUs = sourceIndex.timeOf(frame.frameIndex)
            require(frame.result.sample.timestampUs == canonicalPtsUs) {
                "PTS de tracking desalineado en el frame ${frame.frameIndex}."
            }
            TrajectorySample(
                frameIndex = frame.frameIndex,
                presentationTimeUs = canonicalPtsUs - firstSourcePtsUs,
                point = frame.result.sample.point,
                status = frame.result.sample.status,
                breaksPathBefore = frame.result.sample.status == TrackingStatus.LOST ||
                    (position > 0 && (
                        frame.frameIndex != results[position - 1].frameIndex + 1 ||
                            canonicalPtsUs - sourceIndex.timeOf(results[position - 1].frameIndex) >
                            medianSourceDeltaUs * 1.5
                        )),
            )
        }
        require(samples.zipWithNext().all { (a, b) -> a.presentationTimeUs < b.presentationTimeUs })
    }

    /** Index of the newest measured state visible at [presentationTimeUs], or -1 before the seed. */
    fun indexAtOrBefore(presentationTimeUs: Long): Int {
        var low = 0
        var high = samples.lastIndex
        var answer = -1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (samples[middle].presentationTimeUs <= presentationTimeUs) {
                answer = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return answer
    }

    fun sampleAtOrBefore(presentationTimeUs: Long): TrajectorySample? =
        indexAtOrBefore(presentationTimeUs).takeIf { it >= 0 }?.let(samples::get)

    companion object {
        /**
         * Builds a [TrajectoryTimeline] from persisted/analyzed [EncoderSample]s.
         * Returns null gracefully when samples cannot be mapped to [sourceIndex] or carry no points.
         */
        fun fromEncoderSamples(
            sourceIndex: VideoFrameIndex,
            encoderSamples: List<EncoderSample>,
        ): TrajectoryTimeline? {
            if (encoderSamples.isEmpty()) return null
            val mapped = encoderSamples
                .asSequence()
                .filter { it.frameIndex in 0 until sourceIndex.frameCount }
                .sortedBy { it.frameIndex }
                .distinctBy { it.frameIndex }
                .mapNotNull { sample ->
                    val canonicalPtsUs = sourceIndex.timeOf(sample.frameIndex)
                    val point = sample.rawPixelPoint
                    val status = if (point == null) TrackingStatus.LOST else sample.trackingStatus
                    TrackingFrameResult(
                        frameIndex = sample.frameIndex,
                        result = TrackingResult(
                            sample = TrackingSample(
                                timestampUs = canonicalPtsUs,
                                point = point,
                                confidence = sample.confidence,
                                status = status,
                            ),
                            region = null,
                            featurePoints = emptyList(),
                            diagnostics = TrackingDiagnostics.EMPTY,
                        ),
                    )
                }
                .toList()
            if (mapped.none { it.result.sample.point != null }) return null
            return runCatching { TrajectoryTimeline(sourceIndex, mapped) }.getOrNull()
        }
    }
}

/**
 * One incremental line step of the cumulative export path.
 *
 * Pure so JVM tests can exercise the exact decision the canvas overlay draws:
 * a LOST sample never yields a usable endpoint, and a sample flagged with
 * [TrajectorySample.breaksPathBefore] discards the carried previous point
 * instead of joining across the gap. The returned [nextPreviousPoint] is the
 * only state the overlay carries forward, so seeks replay deterministically.
 */
internal data class TrajectoryPathStep(
    val lineFrom: ImagePoint?,
    val lineTo: ImagePoint?,
    val nextPreviousPoint: ImagePoint?,
)

internal fun trajectoryPathStep(
    previousPoint: ImagePoint?,
    sample: TrajectorySample,
): TrajectoryPathStep {
    val usablePoint = sample.point.takeIf { sample.status != TrackingStatus.LOST }
    val from = previousPoint.takeUnless { sample.breaksPathBefore }
    return if (from != null && usablePoint != null) {
        TrajectoryPathStep(lineFrom = from, lineTo = usablePoint, nextPreviousPoint = usablePoint)
    } else {
        TrajectoryPathStep(lineFrom = null, lineTo = null, nextPreviousPoint = usablePoint)
    }
}
