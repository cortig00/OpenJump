package com.openjump.app.video

import com.openjump.app.encoder.EncoderRepetition
import com.openjump.app.encoder.EncoderSample
import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.encoder.ObservationKind

/** A repetition's inclusive source-PTS range in the original video. */
data class EncoderRepetitionPlaybackSegment(
    val ordinal: Int,
    val startSourcePtsUs: Long,
    val endSourcePtsUs: Long,
) {
    init {
        require(ordinal >= 0)
        require(startSourcePtsUs <= endSourcePtsUs)
    }

    fun contains(sourcePtsUs: Long): Boolean = sourcePtsUs in startSourcePtsUs..endSourcePtsUs
}

/** Resolves sample-index repetition bounds to the authoritative source PTS values. */
fun buildEncoderRepetitionPlaybackSegments(
    repetitions: List<EncoderRepetition>,
    samples: List<EncoderSample>,
): List<EncoderRepetitionPlaybackSegment> = repetitions.map { repetition ->
    require(repetition.startSample in samples.indices && repetition.endSample in samples.indices) {
        "Los límites de repetición están fuera de las muestras."
    }
    require(repetition.startSample <= repetition.endSample)
    EncoderRepetitionPlaybackSegment(
        ordinal = repetition.ordinal,
        startSourcePtsUs = samples[repetition.startSample].sourcePtsUs,
        endSourcePtsUs = samples[repetition.endSample].sourcePtsUs,
    )
}.also(::validateEncoderRepetitionPlaybackSegments)

fun validateEncoderRepetitionPlaybackSegments(
    segments: List<EncoderRepetitionPlaybackSegment>,
) {
    require(segments.map { it.ordinal }.toSet().size == segments.size) {
        "Los ordinales de repetición deben ser únicos."
    }
    require(segments.zipWithNext().all { (a, b) ->
        a.startSourcePtsUs < b.startSourcePtsUs && a.endSourcePtsUs < b.startSourcePtsUs
    }) { "Los tramos de repetición deben ser cronológicos y no solaparse." }
}

data class EncoderRepetitionRailGeometry(
    val ordinal: Int,
    val startPx: Float,
    val endPx: Float,
)

fun encoderRepetitionRailGeometry(
    segments: List<EncoderRepetitionPlaybackSegment>,
    index: VideoFrameIndex,
    railWidthPx: Float,
): List<EncoderRepetitionRailGeometry> {
    if (!railWidthPx.isFinite() || railWidthPx <= 0f || index.playbackDurationUs <= 0L) return emptyList()
    val first = index.frameTimesUs.first()
    val duration = index.playbackDurationUs.toDouble()
    return segments.map { segment ->
        val start = ((segment.startSourcePtsUs - first).coerceAtLeast(0L).toDouble() / duration * railWidthPx)
            .toFloat().coerceIn(0f, railWidthPx)
        val end = ((segment.endSourcePtsUs - first).coerceAtLeast(0L).toDouble() / duration * railWidthPx)
            .toFloat().coerceIn(start, railWidthPx)
        EncoderRepetitionRailGeometry(segment.ordinal, start, end)
    }
}

fun hitTestEncoderRepetitionRail(
    geometries: List<EncoderRepetitionRailGeometry>,
    tapX: Float,
    railWidthPx: Float,
    tolerancePx: Float,
): Int? {
    if (!tapX.isFinite() || !railWidthPx.isFinite() || railWidthPx <= 0f ||
        !tolerancePx.isFinite() || tolerancePx < 0f || tapX !in 0f..railWidthPx
    ) return null
    return geometries
        .filter { tapX in (it.startPx - tolerancePx)..(it.endPx + tolerancePx) }
        .minWithOrNull(compareBy<EncoderRepetitionRailGeometry> {
            when {
                tapX < it.startPx -> it.startPx - tapX
                tapX > it.endPx -> tapX - it.endPx
                else -> 0f
            }
        }.thenBy { it.startPx })
        ?.ordinal
}

/** Inline labels are shown only when measured text plus horizontal padding fits. */
fun shouldShowEncoderRepetitionLabel(
    segmentWidth: Float,
    measuredTextWidth: Float,
    horizontalPadding: Float,
): Boolean = segmentWidth.isFinite() && measuredTextWidth.isFinite() &&
    horizontalPadding >= 0f && measuredTextWidth >= 0f &&
    segmentWidth >= measuredTextWidth + horizontalPadding

/** Active playback is derived from rendered source PTS and never changes selection. */
fun activeEncoderRepetitionOrdinal(
    segments: List<EncoderRepetitionPlaybackSegment>,
    renderedSourcePtsUs: Long?,
): Int? = renderedSourcePtsUs?.let { pts -> segments.firstOrNull { it.contains(pts) }?.ordinal }

/** Timeline used by both live and saved Encoder result viewers. Coordinates are post-rotation. */
data class EncoderPlaybackSample(
    val frameIndex: Int,
    val presentationTimeUs: Long,
    val point: ImagePoint?,
    val status: TrackingStatus,
    val breaksPathBefore: Boolean = false,
    val hasUsablePoint: Boolean = point != null && status != TrackingStatus.LOST,
)

class EncoderPlaybackTimeline(
    val sourceIndex: VideoFrameIndex,
    samples: List<EncoderSample>,
    calibration: MetricCalibration,
) {
    val samples: List<EncoderPlaybackSample>

    init {
        require(samples.isNotEmpty()) { "No hay muestras de trayectoria." }
        require(samples.zipWithNext().all { (a, b) -> a.frameIndex < b.frameIndex }) {
            "Las muestras deben estar ordenadas y sin frames duplicados."
        }
        val geometry = VideoPresentationGeometry.fromEncoded(
            sourceIndex.width,
            sourceIndex.height,
            sourceIndex.rotationDegrees,
        )
        require(calibration.frameIndex in 0 until sourceIndex.frameCount) {
            "Frame de calibración fuera del vídeo."
        }
        require(calibration.pointA.inBounds(geometry.width.toDouble(), geometry.height.toDouble()))
        require(calibration.pointB.inBounds(geometry.width.toDouble(), geometry.height.toDouble()))
        val firstPtsUs = sourceIndex.frameTimesUs.first()
        val sourceDeltas = sourceIndex.frameTimesUs.toList().zipWithNext { a, b -> b - a }
        val medianSourceDelta = sourceDeltas.sorted().let { deltas ->
            when {
                deltas.isEmpty() -> Long.MAX_VALUE
                deltas.size % 2 == 1 -> deltas[deltas.size / 2]
                else -> (deltas[deltas.size / 2 - 1] + deltas[deltas.size / 2]) / 2L
            }
        }
        this.samples = samples.mapIndexed { position, sample ->
            require(sample.frameIndex in 0 until sourceIndex.frameCount) {
                "Frame de trayectoria fuera del vídeo: ${sample.frameIndex}."
            }
            require(sample.sourcePtsUs == sourceIndex.timeOf(sample.frameIndex)) {
                "PTS de trayectoria desalineado en el frame ${sample.frameIndex}."
            }
            require(sample.rawPixelPoint == null || sample.rawPixelPoint.inBounds(geometry.width.toDouble(), geometry.height.toDouble())) {
                "Punto de trayectoria fuera de la geometría del vídeo."
            }
            EncoderPlaybackSample(
                frameIndex = sample.frameIndex,
                presentationTimeUs = sample.sourcePtsUs - firstPtsUs,
                point = sample.rawPixelPoint,
                status = sample.trackingStatus,
                breaksPathBefore = sample.trackingStatus == TrackingStatus.LOST ||
                    sample.observationKind == ObservationKind.MISSING ||
                    (position > 0 && (
                        sample.frameIndex != samples[position - 1].frameIndex + 1 ||
                            sample.sourcePtsUs - samples[position - 1].sourcePtsUs > medianSourceDelta * 1.5
                        )),
                hasUsablePoint = sample.rawPixelPoint != null &&
                    sample.trackingStatus != TrackingStatus.LOST &&
                    sample.observationKind != ObservationKind.MISSING,
            )
        }
        require(this.samples.zipWithNext().all { (a, b) -> a.presentationTimeUs < b.presentationTimeUs })
    }

    fun indexAtOrBefore(presentationTimeUs: Long): Int {
        var low = 0
        var high = samples.lastIndex
        var result = -1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (samples[middle].presentationTimeUs <= presentationTimeUs) {
                result = middle
                low = middle + 1
            } else high = middle - 1
        }
        return result
    }

    fun sampleAtOrBefore(presentationTimeUs: Long): EncoderPlaybackSample? =
        indexAtOrBefore(presentationTimeUs).takeIf { it >= 0 }?.let(samples::get)

    /** Points shown up to the current frame. LOST/MISSING samples never invent a point. */
    fun pathAtOrBefore(presentationTimeUs: Long): List<ImagePoint> = buildList {
        val end = indexAtOrBefore(presentationTimeUs)
        for (i in 0..end) samples[i].takeIf { it.hasUsablePoint }?.point?.let(::add)
    }

    /** Continuous path segments; a LOST/MISSING sample intentionally breaks the line. */
    fun pathSegmentsAtOrBefore(presentationTimeUs: Long): List<List<ImagePoint>> = buildList {
        val end = indexAtOrBefore(presentationTimeUs)
        var segment = mutableListOf<ImagePoint>()
        for (i in 0..end) {
            val item = samples[i]
            val point = item.point.takeUnless { !item.hasUsablePoint }
            if (item.breaksPathBefore) {
                if (segment.isNotEmpty()) add(segment)
                segment = mutableListOf()
            }
            if (point == null) {
                if (segment.isNotEmpty()) add(segment)
                segment = mutableListOf()
            } else {
                segment += point
            }
        }
        if (segment.isNotEmpty()) add(segment)
    }

    private fun ImagePoint.inBounds(width: Double, height: Double): Boolean =
        x.isFinite() && y.isFinite() && x in 0.0..(width - 1.0).coerceAtLeast(0.0) &&
            y in 0.0..(height - 1.0).coerceAtLeast(0.0)
}

data class EncoderPlaybackCompatibility(
    val compatible: Boolean,
    val reason: String? = null,
)

/** Compatibility is intentionally not an identity/fingerprint check. */
fun checkEncoderPlaybackCompatibility(
    index: VideoFrameIndex,
    samples: List<EncoderSample>,
    calibration: MetricCalibration,
): EncoderPlaybackCompatibility = runCatching {
    EncoderPlaybackTimeline(index, samples, calibration)
}.fold(
    onSuccess = { EncoderPlaybackCompatibility(true) },
    onFailure = { EncoderPlaybackCompatibility(false, it.message) },
)
