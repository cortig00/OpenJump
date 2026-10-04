package com.openjump.app.video.export

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
import kotlin.math.min

/** Cumulative path bitmap. It adds only newly reached PTS and rebuilds if Transformer seeks back. */
@OptIn(UnstableApi::class)
class TrajectoryLineOverlay(private val timeline: TrajectoryTimeline) : CanvasOverlay(true) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var drawnSampleIndex = -1
    private var previousPoint: ImagePoint? = null
    private var lineWidthPx = 3f

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        lineWidthPx = 3f * (min(videoSize.width, videoSize.height) / 720f).coerceAtLeast(0.6f)
        drawnSampleIndex = -1
        previousPoint = null
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        val target = timeline.indexAtOrBefore(presentationTimeUs)
        if (target < drawnSampleIndex) {
            canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            drawnSampleIndex = -1
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
                    from.x.toFloat(),
                    from.y.toFloat(),
                    to.x.toFloat(),
                    to.y.toFloat(),
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
class CurrentTrajectoryPointOverlay(private val timeline: TrajectoryTimeline) : CanvasOverlay(true) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var innerRadiusPx = 9f
    private var outerRadiusPx = 13f
    private var strokeWidthPx = 2f

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        val scale = (min(videoSize.width, videoSize.height) / 720f).coerceAtLeast(0.6f)
        innerRadiusPx = 9f * scale
        outerRadiusPx = 13f * scale
        strokeWidthPx = 2f * scale
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val sample = timeline.sampleAtOrBefore(presentationTimeUs) ?: return
        val point = sample.point ?: return
        if (sample.status == TrackingStatus.LOST) return

        fill.color = TrackingVisualStyle.OUTLINE_ARGB.toInt()
        fill.alpha = 190
        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), outerRadiusPx, fill)

        fill.color = TrackingVisualStyle.colorFor(sample.status).toInt()
        fill.alpha = 255
        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), innerRadiusPx, fill)

        stroke.color = TrackingVisualStyle.NEUTRAL_ARGB.toInt()
        stroke.alpha = 255
        stroke.strokeWidth = strokeWidthPx
        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), innerRadiusPx, stroke)
    }
}
