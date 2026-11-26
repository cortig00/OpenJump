package com.openjump.app.tracking

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CircularPlateReanchorTest {

    @Test
    fun `learns rim signature and recovers subpixel center despite initial offset`() {
        val frame = syntheticPlateFrame(width = 240, height = 240, cx = 120.4, cy = 118.7, radius = 36.0)
        val sig = CircularPlateReanchor.learnSignature(frame, ImagePoint(120.0, 119.0), radiusPx = 36.0)
        assertNotNull("Expected circular plate signature to be learned", sig)

        // Offset prediction by ~4.5 px to simulate accumulated KLT drift
        val estimate = CircularPlateReanchor.refineCenter(frame, ImagePoint(124.2, 115.8), requireNotNull(sig))
        assertNotNull("Expected rim re-anchor to recover center", estimate)
        assertEquals(120.4, estimate!!.center.x, 0.35)
        assertEquals(118.7, estimate.center.y, 0.35)
        assertTrue("Expected high rim support", estimate.supportFraction >= 0.80)
        assertTrue("Expected >= 10 covered sectors", estimate.coveredSectors >= 10)
    }

    @Test
    fun `ignores partial occlusion covering one third of the plate rim`() {
        val clean = syntheticPlateFrame(width = 240, height = 240, cx = 120.0, cy = 120.0, radius = 36.0)
        val sig = requireNotNull(CircularPlateReanchor.learnSignature(clean, ImagePoint(120.0, 120.0), 36.0))

        // Occlude a 110-degree wedge on the right side (e.g. athlete's arm or rack upright)
        val occluded = syntheticPlateFrame(
            width = 240,
            height = 240,
            cx = 123.5,
            cy = 114.2,
            radius = 36.0,
            occludeRightWedge = true,
        )
        val estimate = CircularPlateReanchor.refineCenter(occluded, ImagePoint(120.0, 116.5), sig)
        assertNotNull("Partial occlusion should still re-anchor accurately via IRLS", estimate)
        assertEquals(123.5, estimate!!.center.x, 0.5)
        assertEquals(114.2, estimate.center.y, 0.5)
    }

    @Test
    fun `returns null on complete occlusion and rejects non-circular seeds`() {
        val clean = syntheticPlateFrame(width = 240, height = 240, cx = 120.0, cy = 120.0, radius = 36.0)
        val sig = requireNotNull(CircularPlateReanchor.learnSignature(clean, ImagePoint(120.0, 120.0), 36.0))

        val blank = GrayFrame(240, 240, ByteArray(240 * 240) { 60 })
        assertNull(CircularPlateReanchor.refineCenter(blank, ImagePoint(120.0, 120.0), sig))
        assertNull(CircularPlateReanchor.learnSignature(blank, ImagePoint(120.0, 120.0), 36.0))
    }

    private fun syntheticPlateFrame(
        width: Int,
        height: Int,
        cx: Double,
        cy: Double,
        radius: Double,
        occludeRightWedge: Boolean = false,
    ): GrayFrame {
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - cx
                val dy = y - cy
                val r = hypot(dx, dy)
                val angle = atan2(dy, dx)
                val occluded = occludeRightWedge && abs(angle) < 0.95 && r > radius * 0.4
                val v = when {
                    occluded -> 185
                    r <= radius - 0.75 -> 35
                    r >= radius + 0.75 -> 195
                    else -> {
                        val t = (r - (radius - 0.75)) / 1.5
                        (35 + t * 160).toInt()
                    }
                }
                pixels[y * width + x] = v.coerceIn(0, 255).toByte()
            }
        }
        return GrayFrame(width, height, pixels)
    }
}
