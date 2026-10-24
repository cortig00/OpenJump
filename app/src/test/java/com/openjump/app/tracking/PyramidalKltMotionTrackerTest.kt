package com.openjump.app.tracking

import boofcv.abst.tracker.PointTrackerKltPyramid
import boofcv.struct.image.GrayS16
import boofcv.struct.image.GrayU8
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PyramidalKltMotionTrackerTest {

    @Test
    fun `tracks a textured region translated in x and y`() {
        val tracker = PyramidalKltMotionTracker()
        val first = texturedFrame(centerX = 70, centerY = 65)
        val initialized = tracker.initialize(first, 100_000L, ImagePoint(70.0, 65.0))
        assertNotEquals(TrackingStatus.LOST, initialized.sample.status)

        val result = tracker.track(texturedFrame(centerX = 75, centerY = 62), 133_333L)

        assertNotEquals("$result", TrackingStatus.LOST, result.sample.status)
        val point = requireNotNull(result.sample.point)
        assertEquals(75.0, point.x, 1.25)
        assertEquals(62.0, point.y, 1.25)
        assertTrue(result.diagnostics.inlierFeatures >= 4)
        assertTrue(result.sample.confidence > 0.0)
    }

    @Test
    fun `replenished ROI features remain valid over a sequence`() {
        val tracker = PyramidalKltMotionTracker()
        tracker.initialize(texturedFrame(60, 70), 0L, ImagePoint(60.0, 70.0))

        var result: TrackingResult? = null
        for (frameIndex in 1..12) {
            result = tracker.track(
                texturedFrame(60 + frameIndex * 2, 70 - frameIndex),
                frameIndex * 33_333L,
            )
            assertNotEquals("frame $frameIndex: $result", TrackingStatus.LOST, result.sample.status)
        }

        val finalResult = requireNotNull(result)
        assertEquals(84.0, finalResult.sample.point!!.x, 1.5)
        assertEquals(58.0, finalResult.sample.point!!.y, 1.5)
    }

    @Test
    fun `successful update retains validated feature identities`() {
        val tracker = PyramidalKltMotionTracker()
        tracker.initialize(texturedFrame(70, 65), 0L, ImagePoint(70.0, 65.0))
        val initialIds = activeFeatureIds(tracker)

        val result = tracker.track(texturedFrame(73, 63), 33_333L)
        val updatedIds = activeFeatureIds(tracker)

        assertNotEquals(TrackingStatus.LOST, result.sample.status)
        assertTrue("At least one mature KLT track must survive replenishment", (initialIds intersect updatedIds).isNotEmpty())
    }

    @Test
    fun `accelerating target remains stable near a static distractor`() {
        val tracker = PyramidalKltMotionTracker()
        var centerX = 62
        var centerY = 92
        tracker.initialize(
            texturedFrameWithDistractor(centerX, centerY),
            0L,
            ImagePoint(centerX.toDouble(), centerY.toDouble()),
        )

        var result: TrackingResult? = null
        for (frameIndex in 1..10) {
            centerX += if (frameIndex < 5) 1 else 2
            centerY -= frameIndex.coerceAtMost(7)
            result = tracker.track(
                texturedFrameWithDistractor(centerX, centerY),
                frameIndex * 33_333L,
            )
            assertNotEquals("frame $frameIndex: $result", TrackingStatus.LOST, result.sample.status)
        }

        val point = requireNotNull(requireNotNull(result).sample.point)
        assertEquals(centerX.toDouble(), point.x, 2.5)
        assertEquals(centerY.toDouble(), point.y, 2.5)
    }

    @Test
    fun `a flat seed is rejected explicitly`() {
        val tracker = PyramidalKltMotionTracker()
        val blank = GrayFrame(160, 120, ByteArray(160 * 120) { 80 })

        val result = tracker.initialize(blank, 0L, ImagePoint(80.0, 60.0))

        assertEquals(TrackingStatus.LOST, result.sample.status)
        assertNull(result.sample.point)
        assertEquals(0.0, result.sample.confidence, 0.0)
    }

    @Test
    fun `complete occlusion becomes lost and never extrapolates`() {
        val tracker = PyramidalKltMotionTracker()
        tracker.initialize(texturedFrame(70, 65), 0L, ImagePoint(70.0, 65.0))
        val blank = GrayFrame(160, 120, ByteArray(160 * 120) { 15 })

        var result = tracker.track(blank, 33_333L)
        if (result.sample.status != TrackingStatus.LOST) {
            result = tracker.track(blank, 66_666L)
        }
        if (result.sample.status != TrackingStatus.LOST) {
            result = tracker.track(blank, 99_999L)
        }

        assertEquals(TrackingStatus.LOST, result.sample.status)
        assertNull(result.sample.point)
        val afterLoss = tracker.track(texturedFrame(90, 65), 133_332L)
        assertEquals(TrackingStatus.LOST, afterLoss.sample.status)
        assertNull(afterLoss.sample.point)
    }

    @Test
    fun `rejects non increasing timestamps and changing frame dimensions`() {
        val tracker = PyramidalKltMotionTracker()
        tracker.initialize(texturedFrame(70, 65), 10_000L, ImagePoint(70.0, 65.0))

        assertThrows(IllegalArgumentException::class.java) {
            tracker.track(texturedFrame(72, 64), 10_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tracker.track(GrayFrame(80, 80, ByteArray(80 * 80)), 20_000L)
        }
    }

    @Test
    fun `reset permits initialization at another timestamp`() {
        val tracker = PyramidalKltMotionTracker()
        tracker.initialize(texturedFrame(60, 55), 0L, ImagePoint(60.0, 55.0))
        tracker.reset()

        val result = tracker.initialize(texturedFrame(100, 70), 900_000L, ImagePoint(100.0, 70.0))

        assertNotEquals(TrackingStatus.LOST, result.sample.status)
        assertTrue(abs(requireNotNull(result.sample.point).x - 100.0) < 0.01)
    }

    @Suppress("UNCHECKED_CAST")
    private fun activeFeatureIds(tracker: PyramidalKltMotionTracker): Set<Long> {
        val field = PyramidalKltMotionTracker::class.java.getDeclaredField("klt").apply { isAccessible = true }
        val engine = field.get(tracker) as PointTrackerKltPyramid<GrayU8, GrayS16>
        return engine.getActiveTracks(null).map { it.featureId }.toSet()
    }

    private fun texturedFrameWithDistractor(centerX: Int, centerY: Int): GrayFrame {
        val width = 160
        val height = 120
        val pixels = ByteArray(width * height) { 18 }
        for (y in 66..112) {
            for (x in 84..112) {
                pixels[y * width + x] = if ((x / 3 + y / 3) % 2 == 0) 205.toByte() else 35.toByte()
            }
        }
        return texturedFrame(centerX, centerY, pixels)
    }

    private fun texturedFrame(
        centerX: Int,
        centerY: Int,
        pixels: ByteArray = ByteArray(160 * 120) { 18 },
    ): GrayFrame {
        val width = 160
        val height = 120
        for (y in -20..20) {
            for (x in -20..20) {
                val px = centerX + x
                val py = centerY + y
                if (px !in 0 until width || py !in 0 until height) continue
                val checker = if (((x + 20) / 4 + (y + 20) / 4) % 2 == 0) 225 else 45
                val detail = (x * 13 + y * 7).and(15)
                pixels[py * width + px] = (checker - detail).toByte()
            }
        }
        return GrayFrame(width, height, pixels)
    }
}
