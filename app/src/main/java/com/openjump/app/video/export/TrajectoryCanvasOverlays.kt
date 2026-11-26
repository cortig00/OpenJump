package com.openjump.app.video.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.tracking.TrackingVisualStyle
import com.openjump.app.tracking.VideoPresentationGeometry
import kotlin.math.min

/**
 * Computes (scaleX, scaleY) to project coordinates from [sourceGeometry] into the overlay [targetWidth] x [targetHeight].
 * Returns (1f, 1f) when [sourceGeometry] is null or invalid.
 */
internal fun computeOverlayCoordinateScale(
    targetWidth: Int,
    targetHeight: Int,
    sourceGeometry: VideoPresentationGeometry?,
): Pair<Float, Float> {
    if (sourceGeometry == null || sourceGeometry.width <= 0 || sourceGeometry.height <= 0) {
        return 1f to 1f
    }
    if (targetWidth <= 0 || targetHeight <= 0) return 1f to 1f
    return (targetWidth.toFloat() / sourceGeometry.width.toFloat()) to
        (targetHeight.toFloat() / sourceGeometry.height.toFloat())
}

/**
 * Resolves the first sample index in [timeline] that belongs to the active trajectory window at
 * [presentationTimeUs]. When [repStartPresentationTimesUs] is empty (full-set cumulative mode),
 * always returns 0. When populated (per-repetition mode), resets cleanly to the start of each rep.
 */
internal fun resolveTrajectoryStartIndex(
    timeline: TrajectoryTimeline,
    presentationTimeUs: Long,
    repStartPresentationTimesUs: List<Long>,
): Int {
    if (repStartPresentationTimesUs.isEmpty()) return 0
    val activeRepStartUs = repStartPresentationTimesUs.lastOrNull { it <= presentationTimeUs } ?: return 0
    val samples = timeline.samples
    var low = 0
    var high = samples.lastIndex
    var answer = -1
    while (low <= high) {
        val mid = (low + high).ushr(1)
        if (samples[mid].presentationTimeUs >= activeRepStartUs) {
            answer = mid
            high = mid - 1
        } else {
            low = mid + 1
        }
    }
    return if (answer >= 0) answer else 0
}

/** Cumulative path bitmap. Adds only newly reached PTS and resets cleanly on seeks or per-rep windows. */
@OptIn(UnstableApi::class)
class TrajectoryLineOverlay(
    private val timeline: TrajectoryTimeline,
    private val repStartPresentationTimesUs: List<Long> = emptyList(),
    private val sourceGeometry: VideoPresentationGeometry? = null,
) : CanvasOverlay(true) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var drawnWindowStartIndex = 0
    private var drawnSampleIndex = -1
    private var previousPoint: ImagePoint? = null
    private var lineWidthPx = 3f
    private var coordScaleX = 1f
    private var coordScaleY = 1f

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        lineWidthPx = 3f * (min(videoSize.width, videoSize.height) / 720f).coerceAtLeast(0.6f)
        val (sx, sy) = computeOverlayCoordinateScale(videoSize.width, videoSize.height, sourceGeometry)
        coordScaleX = sx
        coordScaleY = sy
        drawnWindowStartIndex = 0
        drawnSampleIndex = -1
        previousPoint = null
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        val target = timeline.indexAtOrBefore(presentationTimeUs)
        val windowStart = resolveTrajectoryStartIndex(timeline, presentationTimeUs, repStartPresentationTimesUs)
        if (windowStart != drawnWindowStartIndex || target < drawnSampleIndex) {
            canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            drawnWindowStartIndex = windowStart
            drawnSampleIndex = windowStart - 1
            previousPoint = null
        }
        if (target <= drawnSampleIndex) return

        for (index in (drawnSampleIndex + 1)..target) {
            val sample = timeline.samples[index]
            val step = trajectoryPathStep(previousPoint, sample)
            val from = step.lineFrom
            val to = step.lineTo
            if (from != null && to != null) {
                paint.color = TrackingVisualStyle.colorFor(sample.status).toInt()
                paint.alpha = PATH_ALPHA
                paint.strokeWidth = lineWidthPx
                canvas.drawLine(
                    from.x.toFloat() * coordScaleX,
                    from.y.toFloat() * coordScaleY,
                    to.x.toFloat() * coordScaleX,
                    to.y.toFloat() * coordScaleY,
                    paint,
                )
            }
            // On a discontinuity step.nextPreviousPoint restarts the path: the
            // breaking sample's own point seeds the next segment instead of
            // joining across the gap. LOST yields null and breaks likewise.
            previousPoint = step.nextPreviousPoint
        }
        drawnSampleIndex = target
    }

    private companion object {
        const val PATH_ALPHA = 210
    }
}

/** Per-frame point bitmap. Clearing it prevents old point indicators becoming part of the path. */
@OptIn(UnstableApi::class)
class CurrentTrajectoryPointOverlay(
    private val timeline: TrajectoryTimeline,
    private val sourceGeometry: VideoPresentationGeometry? = null,
) : CanvasOverlay(true) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var innerRadiusPx = 9f
    private var outerRadiusPx = 13f
    private var strokeWidthPx = 2f
    private var coordScaleX = 1f
    private var coordScaleY = 1f

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        val scale = (min(videoSize.width, videoSize.height) / 720f).coerceAtLeast(0.6f)
        innerRadiusPx = 9f * scale
        outerRadiusPx = 13f * scale
        strokeWidthPx = 2f * scale
        val (sx, sy) = computeOverlayCoordinateScale(videoSize.width, videoSize.height, sourceGeometry)
        coordScaleX = sx
        coordScaleY = sy
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val sample = timeline.sampleAtOrBefore(presentationTimeUs) ?: return
        val point = sample.point ?: return
        if (sample.status == TrackingStatus.LOST) return

        val px = point.x.toFloat() * coordScaleX
        val py = point.y.toFloat() * coordScaleY

        fill.color = TrackingVisualStyle.OUTLINE_ARGB.toInt()
        fill.alpha = 190
        canvas.drawCircle(px, py, outerRadiusPx, fill)

        fill.color = TrackingVisualStyle.colorFor(sample.status).toInt()
        fill.alpha = 255
        canvas.drawCircle(px, py, innerRadiusPx, fill)

        stroke.color = TrackingVisualStyle.NEUTRAL_ARGB.toInt()
        stroke.alpha = 255
        stroke.strokeWidth = strokeWidthPx
        canvas.drawCircle(px, py, innerRadiusPx, stroke)
    }
}

/**
 * Customizable OpenJump logo + live/summary metrics HUD overlay for Media3 Transformer.
 * Redraws only when [VideoHudFrameState] actually changes, keeping GPU/Canvas overhead minimal.
 */
@OptIn(UnstableApi::class)
class VideoHudCanvasOverlay(
    private val spec: VideoHudSpec,
    private val options: VideoExportOptions,
    private val firstSourcePtsUs: Long,
    private val logoBitmap: Bitmap? = null,
) : CanvasOverlay(true) {
    private var videoWidth = 0
    private var videoHeight = 0
    private var lastState: VideoHudFrameState? = null
    private var drawnOnce = false

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        videoWidth = videoSize.width
        videoHeight = videoSize.height
        lastState = null
        drawnOnce = false
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        if (videoWidth <= 0 || videoHeight <= 0 || !options.hasAnyCanvasHud) return
        val nextState = resolveVideoHudFrameState(spec, presentationTimeUs, firstSourcePtsUs)
        if (drawnOnce && nextState == lastState) return

        canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        VideoHudCanvasRenderer.draw(
            canvas = canvas,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            spec = spec,
            options = options,
            frameState = nextState,
            logoBitmap = logoBitmap,
        )
        lastState = nextState
        drawnOnce = true
    }
}
