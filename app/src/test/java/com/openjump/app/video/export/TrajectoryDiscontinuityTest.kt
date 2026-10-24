package com.openjump.app.video.export

import com.openjump.app.encoder.EncoderSample
import com.openjump.app.encoder.MetricPoint
import com.openjump.app.encoder.ObservationKind
import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingDiagnostics
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.TrackingResult
import com.openjump.app.tracking.TrackingSample
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.video.EncoderPlaybackTimeline
import com.openjump.app.video.VideoFrameIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Export/review discontinuity parity.
 *
 * Feeds the same valid points to the review timeline (read-only) and to the
 * export timeline, then folds the export through the production
 * [trajectoryPathStep] — the exact step the canvas overlay draws — and
 * requires identical segments and no line joining across the gap. A canvas
 * draw pass itself needs an AVD and is intentionally not claimed here.
 */
class TrajectoryDiscontinuityTest {

    @Test
    fun `export splits valid points across a significant PTS gap exactly like review`() {
        val index = index(longArrayOf(0L, 33_333L, 66_666L, 200_000L, 233_333L))
        val frames = listOf(0, 1, 2, 3, 4)
        val review = EncoderPlaybackTimeline(index, frames.map { encoderSample(index, it) }, calibration)
        val export = TrajectoryTimeline(index, frames.map { exportResult(index, it) })

        assertEquals(
            review.samples.map { it.breaksPathBefore },
            export.samples.map { it.breaksPathBefore },
        )
        assertEquals(
            listOf(false, false, false, true, false),
            export.samples.map { it.breaksPathBefore },
        )

        val expected = listOf(
            listOf(pts(0), pts(1), pts(2)),
            listOf(pts(3), pts(4)),
        )
        assertEquals(expected, review.pathSegmentsAtOrBefore(Long.MAX_VALUE))
        val fold = foldExport(export, Long.MAX_VALUE)
        assertEquals(expected, fold.segments)
        assertEquals(
            listOf(pts(0) to pts(1), pts(1) to pts(2), pts(3) to pts(4)),
            fold.lines,
        )
    }

    @Test
    fun `export splits on a missing frameIndex even when the PTS jump stays below threshold`() {
        // Deltas: 33333, 667, 33333, 33333 -> median 33333, threshold ~49999.
        // Skipping frame 1 jumps only 34000 in PTS, so only the frameIndex rule fires.
        val index = index(longArrayOf(0L, 33_333L, 34_000L, 67_333L, 100_666L))
        val frames = listOf(0, 2, 3, 4)
        val review = EncoderPlaybackTimeline(index, frames.map { encoderSample(index, it) }, calibration)
        val export = TrajectoryTimeline(index, frames.map { exportResult(index, it) })

        assertEquals(
            review.samples.map { it.breaksPathBefore },
            export.samples.map { it.breaksPathBefore },
        )
        assertEquals(
            listOf(false, true, false, false),
            export.samples.map { it.breaksPathBefore },
        )

        val expected = listOf(
            listOf(pts(0)),
            listOf(pts(2), pts(3), pts(4)),
        )
        assertEquals(expected, review.pathSegmentsAtOrBefore(Long.MAX_VALUE))
        val fold = foldExport(export, Long.MAX_VALUE)
        assertEquals(expected, fold.segments)
        assertEquals(listOf(pts(2) to pts(3), pts(3) to pts(4)), fold.lines)
    }

    @Test
    fun `export and review agree on LOST breaks without joining across them`() {
        val index = index(longArrayOf(0L, 33_333L, 66_666L, 100_000L, 133_333L))
        val statuses = listOf(
            TrackingStatus.TRACKING,
            TrackingStatus.TRACKING,
            TrackingStatus.LOST,
            TrackingStatus.TRACKING,
            TrackingStatus.TRACKING,
        )
        val review = EncoderPlaybackTimeline(
            index,
            statuses.mapIndexed { frame, status -> encoderSample(index, frame, status) },
            calibration,
        )
        val export = TrajectoryTimeline(
            index,
            statuses.mapIndexed { frame, status -> exportResult(index, frame, status) },
        )

        assertEquals(
            review.samples.map { it.breaksPathBefore },
            export.samples.map { it.breaksPathBefore },
        )

        val expected = listOf(
            listOf(pts(0), pts(1)),
            listOf(pts(3), pts(4)),
        )
        assertEquals(expected, review.pathSegmentsAtOrBefore(Long.MAX_VALUE))
        val fold = foldExport(export, Long.MAX_VALUE)
        assertEquals(expected, fold.segments)
        assertEquals(listOf(pts(0) to pts(1), pts(3) to pts(4)), fold.lines)
    }

    private data class Fold(
        val segments: List<List<ImagePoint>>,
        val lines: List<Pair<ImagePoint, ImagePoint>>,
    )

    /**
     * Replays the export timeline through the production overlay step and
     * rebuilds both the drawn lines and the path segments they form, with the
     * same carry-forward state the overlay keeps across incremental draws.
     */
    private fun foldExport(timeline: TrajectoryTimeline, presentationTimeUs: Long): Fold {
        val end = timeline.indexAtOrBefore(presentationTimeUs)
        val segments = mutableListOf<MutableList<ImagePoint>>()
        val lines = mutableListOf<Pair<ImagePoint, ImagePoint>>()
        var current: MutableList<ImagePoint>? = null
        var previous: ImagePoint? = null
        for (i in 0..end) {
            val sample = timeline.samples[i]
            if (sample.breaksPathBefore && current != null) {
                segments += current
                current = null
            }
            val step = trajectoryPathStep(previous, sample)
            val from = step.lineFrom
            val to = step.lineTo
            val usable = step.nextPreviousPoint
            if (from != null && to != null) {
                lines += from to to
            }
            if (usable == null) {
                if (current != null) {
                    segments += current
                    current = null
                }
            } else {
                if (current == null) current = mutableListOf()
                current += usable
            }
            previous = usable
        }
        if (current != null) segments += current
        return Fold(segments, lines)
    }

    private val calibration = MetricCalibration(
        0,
        ImagePoint(20.0, 20.0),
        ImagePoint(20.0, 40.0),
        0.2,
        0.01,
    )

    private fun pts(frame: Int) = ImagePoint(100.0 + frame * 10.0, 200.0 + frame * 5.0)

    private fun index(times: LongArray) = VideoFrameIndex(
        frameTimesUs = times,
        durationUs = times.last() - times.first() + 10_000L,
        width = 1920,
        height = 1080,
        rotationDegrees = 0,
        mimeType = "video/avc",
    )

    private fun exportResult(
        index: VideoFrameIndex,
        frame: Int,
        status: TrackingStatus = TrackingStatus.TRACKING,
    ): TrackingFrameResult {
        val point = if (status == TrackingStatus.LOST) null else pts(frame)
        return TrackingFrameResult(
            frameIndex = frame,
            result = TrackingResult(
                sample = TrackingSample(index.timeOf(frame), point, if (point == null) 0.0 else 1.0, status),
                region = null,
                featurePoints = emptyList(),
                diagnostics = TrackingDiagnostics.EMPTY,
            ),
        )
    }

    private fun encoderSample(
        index: VideoFrameIndex,
        frame: Int,
        status: TrackingStatus = TrackingStatus.TRACKING,
    ): EncoderSample {
        val point = if (status == TrackingStatus.LOST) null else pts(frame)
        return EncoderSample(
            ordinal = frame,
            frameIndex = frame,
            sourcePtsUs = index.timeOf(frame),
            physicalTimeUs = frame.toLong(),
            rawPixelPoint = point,
            rawMetricPoint = point?.let { MetricPoint(it.x, it.y) },
            smoothedMetricPoint = null,
            velocityXMps = null,
            velocityYMps = null,
            confidence = 1.0,
            trackingStatus = status,
            observationKind = if (point == null) ObservationKind.MISSING else ObservationKind.MEASURED,
            fitResidualM = null,
            gapBeforeUs = null,
        )
    }
}
