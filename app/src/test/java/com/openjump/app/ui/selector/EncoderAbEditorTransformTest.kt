package com.openjump.app.ui.selector

import com.openjump.app.tracking.ImagePoint
import org.junit.Assert.*
import org.junit.Test

/** The A/B editor never touches the shared FIT mapper; this local affine is the only zoom path. */
class EncoderAbEditorTransformTest {
    private fun view(
        zoom: Double = 1.0,
        panX: Double = 0.0,
        panY: Double = 0.0,
        viewportWidth: Double = 400.0,
        viewportHeight: Double = 300.0,
    ) = AbEditorView(
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        bitmapWidth = 200,
        bitmapHeight = 100,
        videoWidth = 200.0,
        videoHeight = 100.0,
        zoom = zoom,
        panX = panX,
        panY = panY,
    )

    @Test fun `zoom 1x shows the full frame with no crop`() {
        val v = view()
        // FIT centers the 2:1 bitmap in the 4:3 viewport: full width, vertical bars.
        val topLeft = abEditorViewToVideo(v, 0.0, 50.0)
        val bottomRight = abEditorViewToVideo(v, 400.0, 250.0)
        assertNotNull(topLeft)
        assertNotNull(bottomRight)
        assertEquals(0.0, topLeft!!.x, 0.5)
        assertEquals(0.0, topLeft.y, 0.5)
        assertEquals(199.0, bottomRight!!.x, 0.5)
        assertEquals(99.0, bottomRight.y, 0.5)
    }

    @Test fun `touches on FIT letterbox bars map to nothing`() {
        val v = view()
        assertNull(abEditorViewToVideo(v, 200.0, 10.0))
        assertNull(abEditorViewToVideo(v, 200.0, 290.0))
    }

    @Test fun `round trip is invariant under zoom and pan`() {
        val v = view(zoom = 2.5, panX = 40.0, panY = -30.0)
        val source = ImagePoint(123.0, 45.0)
        val projected = abEditorVideoToView(v, source)
        val inverse = abEditorViewToVideo(v, projected.x, projected.y)
        assertNotNull(inverse)
        assertEquals(source.x, inverse!!.x, 0.01)
        assertEquals(source.y, inverse.y, 0.01)
    }

    @Test fun `zoom keeps the frame center stable without pan`() {
        val before = abEditorVideoToView(view(), ImagePoint(99.5, 49.5))
        val after = abEditorVideoToView(view(zoom = 3.0), ImagePoint(99.5, 49.5))
        assertEquals(before.x, after.x, 0.01)
        assertEquals(before.y, after.y, 0.01)
    }

    @Test fun `pan is clamped so the zoomed frame keeps covering the viewport`() {
        val v = view(zoom = 2.0)
        val (x, y) = clampAbPan(v, 10_000.0, -10_000.0)
        // Content is 400x200 at zoom 2 in a 400x300 viewport: horizontal slack 200, vertical 50.
        assertEquals(200.0, x, 0.001)
        assertEquals(-50.0, y, 0.001)
        val (zx, zy) = clampAbPan(view(), 10_000.0, 10_000.0)
        assertEquals(0.0, zx, 0.001)
        assertEquals(0.0, zy, 0.001)
    }

    @Test fun `invalid zoom never maps a touch`() {
        assertNull(abEditorViewToVideo(view(zoom = 0.5), 200.0, 150.0))
        assertNull(abEditorViewToVideo(view(zoom = 9.0), 200.0, 150.0))
    }

    @Test fun `touches outside the viewport never map like the shared mapper`() {
        val v = view()
        assertNull(abEditorViewToVideo(v, -1.0, 150.0))
        assertNull(abEditorViewToVideo(v, 401.0, 150.0))
        assertNull(abEditorViewToVideo(v, 200.0, -5.0))
        assertNull(abEditorViewToVideo(v, 200.0, 305.0))
    }

    @Test fun `zoomed touch maps under pan with independently computed expectation`() {
        // 400x300 viewport, 200x100 bitmap FIT -> scale 2.0, content 400x200 centered (top 50).
        // Zoom 2x about (200,150), then pan (40,-30): the source center moves
        // to viewport (240,120). Inverting viewport center gives FIT (180,165),
        // hence normalized source (180/400, (165-50)/200) * (199,99).
        val v = view(zoom = 2.0, panX = 40.0, panY = -30.0)
        val center = abEditorViewToVideo(v, 200.0, 150.0)
        assertNotNull(center)
        assertEquals(89.55, center!!.x, 0.001)
        assertEquals(56.925, center.y, 0.001)
        val movedCenter = abEditorViewToVideo(v, 240.0, 120.0)
        assertNotNull(movedCenter)
        assertEquals(99.5, movedCenter!!.x, 0.001)
        assertEquals(49.5, movedCenter.y, 0.001)
        // Zoomed content covers the former top FIT bar: inverse Y is 92.5,
        // so (92.5-50)/200 * 99 = 21.0375. It must now accept this tap.
        val formerBar = abEditorViewToVideo(v, 200.0, 5.0)
        assertNotNull(formerBar)
        assertEquals(21.0375, formerBar!!.y, 0.001)
    }
}
