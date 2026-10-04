package com.openjump.app.ui.selector

import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import kotlin.math.max
import kotlin.math.min

const val MIN_CROP_ZOOM = 1.0
const val MAX_CROP_ZOOM = 4.0

data class ViewportSize(val width: Double, val height: Double) {
    init {
        require(width > 0.0 && height > 0.0 && width.isFinite() && height.isFinite())
    }
}

data class ContentRect(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
) {
    val right: Double get() = left + width
    val bottom: Double get() = top + height

    fun contains(point: ImagePoint): Boolean =
        point.x >= left && point.x <= right && point.y >= top && point.y <= bottom
}

enum class VideoScaleMode { FIT, CROP }

/** REVIEW must render the same full-frame transform used by its PlayerView. */
internal fun isPlateReviewMapperReady(
    phase: PlateDetectionPhase,
    mapper: VideoCoordinateMapper?,
): Boolean = phase != PlateDetectionPhase.REVIEW || mapper?.let {
    it.scaleMode == VideoScaleMode.FIT &&
        it.cropAlignment == CropAlignment.Centered &&
        it.zoom == MIN_CROP_ZOOM
} == true

data class CropAlignment(
    val x: Double = 0.5,
    val y: Double = 0.5,
) {
    init {
        require(x.isFinite() && x in 0.0..1.0)
        require(y.isFinite() && y in 0.0..1.0)
    }

    companion object {
        val Centered = CropAlignment()
    }
}

/** State changed by the local crop viewport controls; it is never persisted. */
data class CropViewportState(
    val zoom: Double = MIN_CROP_ZOOM,
    val alignment: CropAlignment = CropAlignment.Centered,
) {
    init {
        require(zoom.isFinite() && zoom in MIN_CROP_ZOOM..MAX_CROP_ZOOM)
    }
}

/** Pure mapper matching PlayerView's RESIZE_MODE_FIT and RESIZE_MODE_ZOOM behavior. */
fun VideoCoordinateMapper.isPlateCircleVisible(
    center: ImagePoint,
    diameterPx: Double,
    angleRadians: Double,
): Boolean {
    if (!center.x.isFinite() || !center.y.isFinite() ||
        !diameterPx.isFinite() || diameterPx <= 0.0 || !angleRadians.isFinite()
    ) return false
    val radius = diameterPx / 2.0
    // The effective proposal is a circle. Its viewport extents are independent
    // of the stored handle angle; using cos/sin here would make a 90° circle
    // appear to have zero horizontal extent.
    val xExtent = radius
    val yExtent = radius
    val points = listOf(
        ImagePoint(center.x - xExtent, center.y - yExtent),
        ImagePoint(center.x - xExtent, center.y + yExtent),
        ImagePoint(center.x + xExtent, center.y - yExtent),
        ImagePoint(center.x + xExtent, center.y + yExtent),
    )
    return points.all { point ->
        val viewPoint = videoToView(point)
        viewPoint.x in 0.0..viewport.width && viewPoint.y in 0.0..viewport.height &&
            contentRect.contains(viewPoint)
    }
}

class VideoCoordinateMapper(
    val video: VideoPresentationGeometry,
    val viewport: ViewportSize,
    val scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    val cropAlignment: CropAlignment = CropAlignment.Centered,
    val zoom: Double = MIN_CROP_ZOOM,
) {
    init {
        require(zoom.isFinite() && zoom in MIN_CROP_ZOOM..MAX_CROP_ZOOM)
    }

    private val widthScale = viewport.width / video.width
    private val heightScale = viewport.height / video.height
    private val baselineScale = when (scaleMode) {
        VideoScaleMode.FIT -> min(widthScale, heightScale)
        VideoScaleMode.CROP -> max(widthScale, heightScale)
    }
    private val contentScale = baselineScale * if (scaleMode == VideoScaleMode.CROP) zoom else 1.0
    private val contentWidth = video.width * contentScale
    private val contentHeight = video.height * contentScale

    val cropOverflowX: Double = (contentWidth - viewport.width).coerceAtLeast(0.0)
    val cropOverflowY: Double = (contentHeight - viewport.height).coerceAtLeast(0.0)

    val contentRect: ContentRect = ContentRect(
        left = when (scaleMode) {
            VideoScaleMode.FIT -> (viewport.width - contentWidth) / 2.0
            VideoScaleMode.CROP -> -cropOverflowX * cropAlignment.x
        },
        top = when (scaleMode) {
            VideoScaleMode.FIT -> (viewport.height - contentHeight) / 2.0
            VideoScaleMode.CROP -> -cropOverflowY * cropAlignment.y
        },
        width = contentWidth,
        height = contentHeight,
    )

    /** Scale applied to exo_content_frame on top of Media3's baseline resize. */
    val nativeScale: Double = if (scaleMode == VideoScaleMode.CROP) zoom else 1.0

    /** Translation from Media3's centered ZOOM position to this crop viewport. */
    val contentTranslation: ImagePoint = ImagePoint(
        x = if (scaleMode == VideoScaleMode.CROP) {
            contentRect.left - (viewport.width - contentWidth) / 2.0
        } else {
            0.0
        },
        y = if (scaleMode == VideoScaleMode.CROP) {
            contentRect.top - (viewport.height - contentHeight) / 2.0
        } else {
            0.0
        },
    )

    /** Moves the cropped content with the finger and clamps it so no empty area is exposed. */
    fun alignmentAfterPan(deltaX: Double, deltaY: Double): CropAlignment = CropAlignment(
        x = if (cropOverflowX > 0.0 && deltaX.isFinite()) {
            (cropAlignment.x - deltaX / cropOverflowX).coerceIn(0.0, 1.0)
        } else {
            cropAlignment.x
        },
        y = if (cropOverflowY > 0.0 && deltaY.isFinite()) {
            (cropAlignment.y - deltaY / cropOverflowY).coerceIn(0.0, 1.0)
        } else {
            cropAlignment.y
        },
    )

    /**
     * Applies pinch zoom around [centroid], then the centroid pan, preserving the
     * source point under the pinch whenever the viewport bounds permit it.
     */
    fun stateAfterGesture(
        centroid: ImagePoint,
        zoomChange: Double,
        panDeltaX: Double = 0.0,
        panDeltaY: Double = 0.0,
    ): CropViewportState {
        if (scaleMode != VideoScaleMode.CROP || !zoomChange.isFinite() ||
            !panDeltaX.isFinite() || !panDeltaY.isFinite()
        ) return CropViewportState(zoom, cropAlignment)

        val source = viewToVideo(centroid) ?: return CropViewportState(zoom, cropAlignment)
        val nextZoom = (zoom * zoomChange).coerceIn(MIN_CROP_ZOOM, MAX_CROP_ZOOM)
        val anchoredAlignment = alignmentForSourceAt(
            source = source,
            viewPoint = centroid,
            nextZoom = nextZoom,
        )
        val nextMapper = VideoCoordinateMapper(
            video = video,
            viewport = viewport,
            scaleMode = VideoScaleMode.CROP,
            cropAlignment = anchoredAlignment,
            zoom = nextZoom,
        )
        return CropViewportState(
            zoom = nextZoom,
            alignment = nextMapper.alignmentAfterPan(panDeltaX, panDeltaY),
        )
    }

    private fun alignmentForSourceAt(
        source: ImagePoint,
        viewPoint: ImagePoint,
        nextZoom: Double,
    ): CropAlignment {
        val nextScale = baselineScale * nextZoom
        val nextWidth = video.width * nextScale
        val nextHeight = video.height * nextScale
        val nextOverflowX = (nextWidth - viewport.width).coerceAtLeast(0.0)
        val nextOverflowY = (nextHeight - viewport.height).coerceAtLeast(0.0)
        val sourceX = source.x / (video.width - 1.0).coerceAtLeast(1.0)
        val sourceY = source.y / (video.height - 1.0).coerceAtLeast(1.0)
        val desiredLeft = viewPoint.x - sourceX * nextWidth
        val desiredTop = viewPoint.y - sourceY * nextHeight
        return CropAlignment(
            x = if (nextOverflowX > 0.0) {
                (-desiredLeft / nextOverflowX).coerceIn(0.0, 1.0)
            } else 0.5,
            y = if (nextOverflowY > 0.0) {
                (-desiredTop / nextOverflowY).coerceIn(0.0, 1.0)
            } else 0.5,
        )
    }

    /** Returns null for touches outside the visible video (the FIT letterbox bars). */
    fun viewToVideo(point: ImagePoint): ImagePoint? {
        if (point.x !in 0.0..viewport.width || point.y !in 0.0..viewport.height ||
            !contentRect.contains(point)
        ) return null
        val normalizedX = (point.x - contentRect.left) / contentRect.width
        val normalizedY = (point.y - contentRect.top) / contentRect.height
        return ImagePoint(
            x = normalizedX * (video.width - 1.0),
            y = normalizedY * (video.height - 1.0),
        )
    }

    fun videoToView(point: ImagePoint): ImagePoint = ImagePoint(
        x = contentRect.left + point.x / (video.width - 1.0).coerceAtLeast(1.0) * contentRect.width,
        y = contentRect.top + point.y / (video.height - 1.0).coerceAtLeast(1.0) * contentRect.height,
    )
}
