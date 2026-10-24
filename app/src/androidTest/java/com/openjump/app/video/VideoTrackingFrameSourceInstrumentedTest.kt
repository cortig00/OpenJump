package com.openjump.app.video

import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.tracking.DecodedTrackingFrame
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.OfflineVideoTracker
import com.openjump.app.tracking.PyramidalKltMotionTracker
import com.openjump.app.tracking.TrackingPerformanceProfiler
import com.openjump.app.tracking.TrackingStatus
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoTrackingFrameSourceInstrumentedTest {

    @Test
    fun translatedPatternUsesCanonicalPtsAndProducesTrajectory() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val video = File(context.cacheDir, "tracking_translation.mp4")
        instrumentation.context.assets.open("tracking_translation.mp4").use { input ->
            video.outputStream().use { output -> input.copyTo(output) }
        }

        try {
            val index = VideoFrameIndexer.indexFromPath(video.absolutePath)
            val source = VideoTrackingFrameSource(context, Uri.fromFile(video), index)
            val decoded = source.frameAt(0)
            assertEquals(index.timeOf(0), decoded.timestampUs)
            assertTrue(decoded.image.width <= 720)
            assertTrue(decoded.image.height <= 720)
            assertEquals(decoded.image.width * decoded.image.height, decoded.image.pixels.size)
            val runner = OfflineVideoTracker(source, PyramidalKltMotionTracker())
            try {
                val initial = runner.initialize(0, ImagePoint(60.0, 120.0))
                assertNotEquals(TrackingStatus.LOST, initial.result.sample.status)

                val results = runner.trackRemaining { _, _, _ -> }

                assertEquals(index.frameCount, results.size)
                assertEquals(index.frameTimesUs.toList(), results.map { it.result.sample.timestampUs })
                val last = results.last().result.sample
                assertNotEquals(TrackingStatus.LOST, last.status)
                assertEquals(98.0, last.point!!.x, 3.0)
                assertEquals(101.0, last.point!!.y, 3.0)
                assertTrue(results.all { it.result.sample.confidence in 0.0..1.0 })
            } finally {
                runner.close()
            }
        } finally {
            video.delete()
        }
    }

    @Test
    fun pipelineDetailsDoNotChangeDecodedFramesOrCounters() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val video = File(context.cacheDir, "tracking_translation.mp4")
        instrumentation.context.assets.open("tracking_translation.mp4").use { input ->
            video.outputStream().use { output -> input.copyTo(output) }
        }

        try {
            val index = VideoFrameIndexer.indexFromPath(video.absolutePath)
            val profiled = TrackingPerformanceProfiler(enabled = true, collectPipelineDetails = true)
            val plain = TrackingPerformanceProfiler(enabled = false)
            val production = TrackingPerformanceProfiler() // enabled, details off: the real UI default
            val withProfiler = VideoTrackingFrameSource(
                context,
                Uri.fromFile(video),
                index,
                performanceProfiler = profiled,
            )
            val withoutProfiler = VideoTrackingFrameSource(
                context,
                Uri.fromFile(video),
                index,
                performanceProfiler = plain,
            )
            val productionSource = VideoTrackingFrameSource(
                context,
                Uri.fromFile(video),
                index,
                performanceProfiler = production,
            )
            try {
                assertSameFrame(withoutProfiler.frameAt(0), withProfiler.frameAt(0))

                val framesWithProfiler = decodeAll(withProfiler)
                val framesWithoutProfiler = decodeAll(withoutProfiler)
                val productionFrames = decodeAll(productionSource)
                assertEquals(framesWithoutProfiler.size, productionFrames.size)
                productionFrames.forEachIndexed { position, frame ->
                    assertSameFrame(framesWithoutProfiler[position], frame)
                }
                assertTrue(production.snapshot().keys.none { it.detailed })
                assertTrue(production.counters().isEmpty())
                assertTrue(production.snapshot().containsKey(TrackingPerformanceProfiler.Stage.DECODE))
                assertEquals(index.frameCount, framesWithProfiler.size)
                assertEquals(framesWithoutProfiler.size, framesWithProfiler.size)
                framesWithProfiler.forEachIndexed { position, frame ->
                    assertSameFrame(framesWithoutProfiler[position], frame)
                }

                assertEquals(
                    1,
                    requireNotNull(profiled.snapshot()[TrackingPerformanceProfiler.Stage.DECODER_PREPARE]).samples,
                )
                val counters = profiled.counters()
                val delivered = requireNotNull(counters[TrackingPerformanceProfiler.Metric.DELIVERED_FRAMES])
                assertEquals(framesWithProfiler.size + 1, delivered.toInt())
                val attempts = requireNotNull(counters[TrackingPerformanceProfiler.Metric.DECODE_BATCH_ATTEMPTS])
                val fallback = requireNotNull(counters[TrackingPerformanceProfiler.Metric.DECODE_FALLBACK_FRAMES])
                val failures = requireNotNull(counters[TrackingPerformanceProfiler.Metric.DECODE_BATCH_FAILURES])
                val returned = requireNotNull(counters[TrackingPerformanceProfiler.Metric.DECODE_BATCH_RETURNED_FRAMES])
                assertTrue("no decode path was recorded", attempts > 0L || fallback > 0L)
                assertTrue("failures exceed attempts", failures <= attempts)
                if (attempts > failures) assertTrue("a successful batch returned no frames", returned > 0L)
                if (failures == 0L && attempts > 0L) {
                    assertEquals("successful batches cover decodeAll", index.frameCount.toLong(), returned)
                }
                // A failed first batch can legitimately return zero frames and use PTS fallback.
                // Fallback counts also include frameAt(0) if its metadata selected that path.
                if (Build.VERSION.SDK_INT >= 28) {
                    assertNotNull(profiled.snapshot()[TrackingPerformanceProfiler.Stage.FRAME_COUNT_PROBE])
                } else {
                    assertNull(profiled.snapshot()[TrackingPerformanceProfiler.Stage.FRAME_COUNT_PROBE])
                }
                assertTrue(plain.snapshot().isEmpty())
                assertTrue(plain.counters().isEmpty())
            } finally {
                withProfiler.close()
                withoutProfiler.close()
                productionSource.close()
            }
        } finally {
            video.delete()
        }
    }

    private suspend fun decodeAll(source: VideoTrackingFrameSource): List<DecodedTrackingFrame> {
        val frames = mutableListOf<DecodedTrackingFrame>()
        source.forEachFrame(0) { frame ->
            frames += frame
            true
        }
        return frames
    }

    private fun assertSameFrame(expected: DecodedTrackingFrame, actual: DecodedTrackingFrame) {
        assertEquals(expected.frameIndex, actual.frameIndex)
        assertEquals(expected.timestampUs, actual.timestampUs)
        assertEquals(expected.presentationWidth, actual.presentationWidth)
        assertEquals(expected.presentationHeight, actual.presentationHeight)
        assertEquals(expected.image.width, actual.image.width)
        assertEquals(expected.image.height, actual.image.height)
        assertTrue(
            "gray pixels differ at frame ${actual.frameIndex}",
            expected.image.pixels.contentEquals(actual.image.pixels),
        )
    }
}
