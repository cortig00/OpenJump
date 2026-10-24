package com.openjump.app.ui.selector

import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCoordinateMapperTest {

    @Test
    fun `landscape video maps through vertical letterbox`() {
        val mapper = VideoCoordinateMapper(
            VideoPresentationGeometry(1920, 1080),
            ViewportSize(1000.0, 1000.0),
        )

        assertEquals(218.75, mapper.contentRect.top, 0.001)
        val center = requireNotNull(mapper.viewToVideo(ImagePoint(500.0, 500.0)))
        assertEquals(959.5, center.x, 0.001)
        assertEquals(539.5, center.y, 0.001)
        assertNull(mapper.viewToVideo(ImagePoint(500.0, 100.0)))
    }

    @Test
    fun `portrait video maps through horizontal letterbox`() {
        val mapper = VideoCoordinateMapper(
            VideoPresentationGeometry(1080, 1920),
            ViewportSize(1000.0, 600.0),
        )

        assertEquals(331.25, mapper.contentRect.left, 0.001)
        val center = requireNotNull(mapper.viewToVideo(ImagePoint(500.0, 300.0)))
        assertEquals(539.5, center.x, 0.001)
        assertEquals(959.5, center.y, 0.001)
        assertNull(mapper.viewToVideo(ImagePoint(100.0, 300.0)))
    }

    @Test
    fun `crop fills viewport without changing aspect ratio`() {
        val mapper = VideoCoordinateMapper(
            VideoPresentationGeometry(1920, 1080),
            ViewportSize(1000.0, 1000.0),
            VideoScaleMode.CROP,
        )

        assertEquals(-388.888, mapper.contentRect.left, 0.001)
        assertEquals(0.0, mapper.contentRect.top, 0.001)
        assertEquals(1_777.777, mapper.contentRect.width, 0.001)
        assertEquals(1_000.0, mapper.contentRect.height, 0.001)
        val center = requireNotNull(mapper.viewToVideo(ImagePoint(500.0, 500.0)))
        assertEquals(959.5, center.x, 0.001)
        assertEquals(539.5, center.y, 0.001)
    }

    @Test
    fun `crop maps only the visible central source region`() {
        val mapper = VideoCoordinateMapper(
            VideoPresentationGeometry(1920, 1080),
            ViewportSize(1000.0, 1000.0),
            VideoScaleMode.CROP,
        )

        val left = requireNotNull(mapper.viewToVideo(ImagePoint(0.0, 500.0)))
        val right = requireNotNull(mapper.viewToVideo(ImagePoint(1000.0, 500.0)))
        assertEquals(419.781, left.x, 0.001)
        assertEquals(1_499.219, right.x, 0.001)
        assertNull(mapper.viewToVideo(ImagePoint(-1.0, 500.0)))
    }

    @Test
    fun `crop alignment selects the visible source edge`() {
        val leftAligned = VideoCoordinateMapper(
            VideoPresentationGeometry(1920, 1080),
            ViewportSize(1000.0, 1000.0),
            VideoScaleMode.CROP,
            CropAlignment(x = 0.0, y = 0.5),
        )
        val rightAligned = VideoCoordinateMapper(
            VideoPresentationGeometry(1920, 1080),
            ViewportSize(1000.0, 1000.0),
            VideoScaleMode.CROP,
            CropAlignment(x = 1.0, y = 0.5),
        )

        assertEquals(0.0, leftAligned.contentRect.left, 0.001)
        assertEquals(-777.777, rightAligned.contentRect.left, 0.001)
        assertEquals(
            0.0,
            requireNotNull(leftAligned.viewToVideo(ImagePoint(0.0, 500.0))).x,
            0.001,
        )
        assertEquals(
            1_919.0,
            requireNotNull(rightAligned.viewToVideo(ImagePoint(1000.0, 500.0))).x,
            0.001,
        )
    }

    @Test
    fun `portrait crop can reveal the bottom of the source`() {
        val mapper = VideoCoordinateMapper(
            VideoPresentationGeometry(1080, 1920),
            ViewportSize(1000.0, 1000.0),
            VideoScaleMode.CROP,
            CropAlignment(x = 0.5, y = 1.0),
        )

        assertEquals(-777.777, mapper.contentRect.top, 0.001)
        val bottom = requireNotNull(mapper.viewToVideo(ImagePoint(500.0, 1000.0)))
        assertEquals(1_919.0, bottom.y, 0.001)
    }

    @Test
    fun `crop pan follows the finger and clamps at content edges`() {
        val mapper = VideoCoordinateMapper(
            VideoPresentationGeometry(1080, 1920),
            ViewportSize(1000.0, 1000.0),
            VideoScaleMode.CROP,
        )

        assertEquals(1.0, mapper.alignmentAfterPan(0.0, -10_000.0).y, 0.001)
        assertEquals(0.0, mapper.alignmentAfterPan(0.0, 10_000.0).y, 0.001)
        assertEquals(0.5, mapper.alignmentAfterPan(10_000.0, 0.0).x, 0.001)
    }

    @Test
    fun `crop translation is relative to Media3 centered zoom`() {
        val mapper = VideoCoordinateMapper(
            VideoPresentationGeometry(1080, 1920),
            ViewportSize(1000.0, 1000.0),
            VideoScaleMode.CROP,
            CropAlignment(x = 0.5, y = 1.0),
        )

        assertEquals(0.0, mapper.contentTranslation.x, 0.001)
        assertEquals(-388.888, mapper.contentTranslation.y, 0.001)
    }

    @Test
    fun `panned crop keeps overlay and touch mapping reversible`() {
        val mapper = VideoCoordinateMapper(
            VideoPresentationGeometry(1080, 1920),
            ViewportSize(1000.0, 1000.0),
            VideoScaleMode.CROP,
            CropAlignment(x = 0.5, y = 0.82),
        )
        val source = ImagePoint(432.5, 1_300.25)

        val roundTrip = requireNotNull(mapper.viewToVideo(mapper.videoToView(source)))

        assertEquals(source.x, roundTrip.x, 0.001)
        assertEquals(source.y, roundTrip.y, 0.001)
    }

    @Test
    fun `fit stays centered when a crop alignment is preserved`() {
        val mapper = VideoCoordinateMapper(
            VideoPresentationGeometry(1080, 1920),
            ViewportSize(1000.0, 1000.0),
            VideoScaleMode.FIT,
            CropAlignment(x = 0.0, y = 1.0),
        )

        assertEquals(218.75, mapper.contentRect.left, 0.001)
        assertEquals(0.0, mapper.contentRect.top, 0.001)
        assertEquals(0.0, mapper.contentTranslation.x, 0.001)
        assertEquals(0.0, mapper.contentTranslation.y, 0.001)
    }

    @Test
    fun `container rotation swaps presentation dimensions`() {
        assertEquals(
            VideoPresentationGeometry(1080, 1920),
            VideoPresentationGeometry.fromEncoded(1920, 1080, 90),
        )
        assertEquals(
            VideoPresentationGeometry(1920, 1080),
            VideoPresentationGeometry.fromEncoded(1920, 1080, 180),
        )
        assertEquals(
            VideoPresentationGeometry(1080, 1920),
            VideoPresentationGeometry.fromEncoded(1920, 1080, 270),
        )
    }

    @Test
    fun `content edges map to valid pixel indices`() {
        val mapper = VideoCoordinateMapper(
            VideoPresentationGeometry(320, 240),
            ViewportSize(640.0, 480.0),
        )

        val bottomRight = requireNotNull(
            mapper.viewToVideo(ImagePoint(mapper.contentRect.right, mapper.contentRect.bottom)),
        )
        assertEquals(319.0, bottomRight.x, 0.001)
        assertEquals(239.0, bottomRight.y, 0.001)
    }

    @Test
    fun `pinch zoom preserves source point under centroid`() {
        val mapper = VideoCoordinateMapper(
            VideoPresentationGeometry(1920, 1080),
            ViewportSize(1000.0, 1000.0),
            VideoScaleMode.CROP,
        )
        val centroid = ImagePoint(420.0, 530.0)
        val source = requireNotNull(mapper.viewToVideo(centroid))
        val next = mapper.stateAfterGesture(centroid, zoomChange = 2.0)
        val zoomed = VideoCoordinateMapper(
            mapper.video,
            mapper.viewport,
            VideoScaleMode.CROP,
            next.alignment,
            next.zoom,
        )

        val mapped = zoomed.videoToView(source)
        assertEquals(centroid.x, mapped.x, 0.001)
        assertEquals(centroid.y, mapped.y, 0.001)
    }

    @Test
    fun `combined pinch and pan keeps the previous source under the moved centroid`() {
        val mapper = VideoCoordinateMapper(
            VideoPresentationGeometry(1920, 1080),
            ViewportSize(1000.0, 1000.0),
            VideoScaleMode.CROP,
        )
        val previousCentroid = ImagePoint(470.0, 430.0)
        val source = requireNotNull(mapper.viewToVideo(previousCentroid))
        val panX = 28.0
        val panY = -18.0
        val next = mapper.stateAfterGesture(
            centroid = previousCentroid,
            zoomChange = 1.8,
            panDeltaX = panX,
            panDeltaY = panY,
        )
        val zoomed = VideoCoordinateMapper(
            mapper.video,
            mapper.viewport,
            VideoScaleMode.CROP,
            next.alignment,
            next.zoom,
        )

        val mapped = zoomed.videoToView(source)
        assertEquals(previousCentroid.x + panX, mapped.x, 0.001)
        assertEquals(previousCentroid.y + panY, mapped.y, 0.001)
    }

    @Test
    fun `zoomed and panned mapping round trips all presentation orientations`() {
        val geometries = listOf(
            VideoPresentationGeometry.fromEncoded(1920, 1080, 0),
            VideoPresentationGeometry.fromEncoded(1080, 1920, 0),
            VideoPresentationGeometry.fromEncoded(1920, 1080, 90),
            VideoPresentationGeometry.fromEncoded(1920, 1080, 270),
        )

        geometries.forEach { geometry ->
            val mapper = VideoCoordinateMapper(
                geometry,
                ViewportSize(900.0, 700.0),
                VideoScaleMode.CROP,
                CropAlignment(x = 0.23, y = 0.71),
                zoom = 3.2,
            )
            val source = requireNotNull(mapper.viewToVideo(ImagePoint(450.0, 350.0)))
            val roundTrip = requireNotNull(mapper.viewToVideo(mapper.videoToView(source)))

            assertEquals(source.x, roundTrip.x, 0.001)
            assertEquals(source.y, roundTrip.y, 0.001)
        }
    }

    @Test
    fun `fit ignores custom crop zoom and clears native transform`() {
        val centered = VideoCoordinateMapper(
            VideoPresentationGeometry(1920, 1080),
            ViewportSize(1000.0, 700.0),
            VideoScaleMode.FIT,
        )
        val custom = VideoCoordinateMapper(
            centered.video,
            centered.viewport,
            VideoScaleMode.FIT,
            CropAlignment(x = 0.1, y = 0.9),
            zoom = MAX_CROP_ZOOM,
        )

        assertEquals(centered.contentRect, custom.contentRect)
        assertEquals(1.0, custom.nativeScale, 0.001)
        assertEquals(0.0, custom.contentTranslation.x, 0.001)
        assertEquals(0.0, custom.contentTranslation.y, 0.001)
    }

    @Test
    fun `crop clamp keeps every edge covered at minimum and maximum zoom`() {
        listOf(
            VideoPresentationGeometry(1920, 1080),
            VideoPresentationGeometry(1080, 1920),
        ).forEach { geometry ->
            listOf(MIN_CROP_ZOOM, MAX_CROP_ZOOM).forEach { zoom ->
                listOf(
                    CropAlignment(x = 0.0, y = 0.0),
                    CropAlignment(x = 0.5, y = 0.5),
                    CropAlignment(x = 1.0, y = 1.0),
                ).forEach { alignment ->
                    val mapper = VideoCoordinateMapper(
                        geometry,
                        ViewportSize(1000.0, 1000.0),
                        VideoScaleMode.CROP,
                        alignment,
                        zoom,
                    )
                    assertTrue(mapper.contentRect.left <= 0.0)
                    assertTrue(mapper.contentRect.top <= 0.0)
                    assertTrue(mapper.contentRect.right >= mapper.viewport.width)
                    assertTrue(mapper.contentRect.bottom >= mapper.viewport.height)
                }
            }
        }
    }

    @Test
    fun `pinch zoom and pan remain finite and clamp zoom`() {
        val mapper = VideoCoordinateMapper(
            VideoPresentationGeometry(1920, 1080),
            ViewportSize(1000.0, 1000.0),
            VideoScaleMode.CROP,
        )
        val state = mapper.stateAfterGesture(
            centroid = ImagePoint(500.0, 500.0),
            zoomChange = 100.0,
            panDeltaX = 100_000.0,
            panDeltaY = -100_000.0,
        )

        assertEquals(MAX_CROP_ZOOM, state.zoom, 0.001)
        assertEquals(0.0, state.alignment.x, 0.001)
        assertEquals(1.0, state.alignment.y, 0.001)
        assert(state.zoom.isFinite())
        assert(state.alignment.x.isFinite() && state.alignment.y.isFinite())

        val min = mapper.stateAfterGesture(ImagePoint(500.0, 500.0), 0.001)
        assertEquals(MIN_CROP_ZOOM, min.zoom, 0.001)
        val unchanged = mapper.stateAfterGesture(ImagePoint(500.0, 500.0), Double.NaN)
        assertEquals(MIN_CROP_ZOOM, unchanged.zoom, 0.001)
    }

    @Test
    fun `frame to view and back is reversible`() {
        val mapper = VideoCoordinateMapper(
            VideoPresentationGeometry(1920, 1080),
            ViewportSize(1200.0, 900.0),
        )
        val source = ImagePoint(321.5, 777.25)

        val roundTrip = requireNotNull(mapper.viewToVideo(mapper.videoToView(source)))

        assertEquals(source.x, roundTrip.x, 0.001)
        assertEquals(source.y, roundTrip.y, 0.001)
    }
}
