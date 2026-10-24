package com.openjump.app.benchmark

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.tracking.TrackingPerformanceProfiler
import com.openjump.app.ui.selector.copyTrackingPreview
import com.openjump.app.video.VideoFrameIndexer
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fast canary checks for the AndroidTest-only UI preview wrapper. Frozen core
 * [MmrPrefetchFrameSource] stays untouched; this covers preview cadence, tiny-copy bounds,
 * exact frame/PTS delivery on the consumer, producer/consumer thread separation and
 * drain-after-join lifecycle.
 */
@RunWith(AndroidJUnit4::class)
class PreviewMmrPrefetchFrameSourceTest {

    @Test
    fun envelopeConstantsStayBounded() {
        assertEquals(5, PreviewMmrPrefetchFrameSource.PREVIEW_CADENCE)
        assertEquals(4, PreviewMmrPrefetchFrameSource.MAX_PREVIEW_COPIES)
        assertEquals(384, PreviewMmrPrefetchFrameSource.TINY_SIDE)
        assertEquals(384 * 384 * 4, PreviewMmrPrefetchFrameSource.TINY_BYTES)
        assertEquals(4 * 384 * 384 * 4, PreviewMmrPrefetchFrameSource.MAX_PREVIEW_BYTES)
        assertTrue(PreviewMmrPrefetchFrameSource.MAX_PREVIEW_BYTES < 3 * 1024 * 1024)
        assertEquals(70L * 1024L * 1024L, PreviewMmrPrefetchFrameSource.COMBINED_IMAGE_ENVELOPE_BYTES)
        assertEquals(
            "conservative bounded image envelope, NOT an RSS/native-heap bound",
            PreviewMmrPrefetchFrameSource.ENVELOPE_NOTE,
        )
        // Core gray envelope unchanged: 4 x 720 x 720 beyond 64MiB MMR.
        assertEquals(2, MmrPrefetchFrameSource.QUEUE_CAPACITY)
        assertEquals(66L * 1024L * 1024L, MmrPrefetchFrameSource.COMBINED_IMAGE_ENVELOPE_BYTES)
    }

    @Test
    fun previewCadenceMatchesEveryFiveOrLast() {
        val frameCount = 20
        val seed = 3
        val expected = (0 until frameCount).filter { (it - seed) % 5 == 0 || it == frameCount - 1 }
        expected.forEach { frame ->
            assertTrue(
                "frame $frame should be preview cadence",
                PreviewMmrPrefetchFrameSource.isPreviewCadence(frame, seed, frameCount),
            )
        }
        (0 until frameCount).filter { it !in expected }.forEach { frame ->
            assertTrue(
                "frame $frame should not be preview cadence",
                !PreviewMmrPrefetchFrameSource.isPreviewCadence(frame, seed, frameCount),
            )
        }
        // Seed-relative: seed frame itself is always cadence.
        assertTrue(PreviewMmrPrefetchFrameSource.isPreviewCadence(seed, seed, frameCount))
    }

    @Test
    fun tinyCopyDelegatesToProductionHelperPixelExact() {
        val borrowed = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        try {
            // Deterministic color content (real video is RGB, not neutral-only).
            for (y in 0 until 480 step 7) {
                for (x in 0 until 640 step 7) {
                    val color = android.graphics.Color.rgb((x * 37) % 256, (y * 53) % 256, ((x + y) * 17) % 256)
                    borrowed.setPixel(x, y, color)
                }
            }
            val wrapperCopy = PreviewMmrPrefetchFrameSource.tinyPreviewCopy(borrowed, 90)
            val baseline = copyTrackingPreview(borrowed, 90)
            try {
                assertEquals(baseline.width, wrapperCopy.width)
                assertEquals(baseline.height, wrapperCopy.height)
                assertTrue(wrapperCopy.width <= 384 && wrapperCopy.height <= 384)
                assertTrue(wrapperCopy.allocationByteCount <= PreviewMmrPrefetchFrameSource.TINY_BYTES)
                assertTrue("wrapper copy must be pixel-identical to production helper", wrapperCopy.sameAs(baseline))
            } finally {
                if (!wrapperCopy.isRecycled) wrapperCopy.recycle()
                if (!baseline.isRecycled) baseline.recycle()
            }
        } finally {
            if (!borrowed.isRecycled) borrowed.recycle()
        }
    }

    @Test
    fun lastFrameIsCadenceEvenOffGrid() {
        // FrameCount 21, seed 0: last index 20 is on-grid; use 22 so last (21) is off-grid but still cadence.
        assertTrue(PreviewMmrPrefetchFrameSource.isPreviewCadence(21, 0, 22))
        assertEquals(6, PreviewMmrPrefetchFrameSource.expectedPreviewPackets(0, 22))
    }

    @Test
    fun tinyCopyStaysWithin384Side() {
        val borrowed = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888)
        try {
            val tiny = PreviewMmrPrefetchFrameSource.tinyPreviewCopy(borrowed, 90)
            try {
                assertTrue("width ${tiny.width} exceeds 384", tiny.width <= 384)
                assertTrue("height ${tiny.height} exceeds 384", tiny.height <= 384)
                assertTrue(tiny.allocationByteCount <= PreviewMmrPrefetchFrameSource.TINY_BYTES)
            } finally {
                if (!tiny.isRecycled) tiny.recycle()
            }
        } finally {
            if (!borrowed.isRecycled) borrowed.recycle()
        }
    }

    @Test(timeout = 120_000)
    fun previewPairsExactPtsOnConsumerAndDrains() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val video = File(context.cacheDir, "preview-mmr-translation-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("tracking_translation.mp4").use { input ->
            video.outputStream().use { output -> input.copyTo(output) }
        }
        var wrapper: PreviewMmrPrefetchFrameSource? = null
        try {
            val index = VideoFrameIndexer.indexFromPath(video.absolutePath)
            assertTrue("translated fixture expected", index.frameCount in 2..120)
            val profiler = TrackingPerformanceProfiler(enabled = false)
            val consumerThread = Thread.currentThread().id
            val observed = Collections.synchronizedList(mutableListOf<PreviewCall>())
            val observerThreads = Collections.synchronizedList(mutableListOf<Long>())
            wrapper = PreviewMmrPrefetchFrameSource(
                context = context,
                uri = Uri.fromFile(video),
                index = index,
                profiler = profiler,
                seedFrameIndex = 0,
                originalPreviewObserver = { frame, pts, borrowed, rotation ->
                    observerThreads += Thread.currentThread().id
                    assertEquals(0, rotation)
                    assertTrue(borrowed.width <= 384 && borrowed.height <= 384)
                    observed += PreviewCall(frame, pts, borrowed)
                },
            )
            val seenFrames = mutableListOf<Int>()
            wrapper.forEachFrame(0) { frame ->
                assertEquals(consumerThread, Thread.currentThread().id)
                seenFrames += frame.frameIndex
                assertEquals(index.timeOf(frame.frameIndex), frame.timestampUs)
                true
            }
            assertEquals(index.frameCount, seenFrames.size)
            val expectedPreviewFrames = (0 until index.frameCount).filter {
                PreviewMmrPrefetchFrameSource.isPreviewCadence(it, 0, index.frameCount)
            }
            assertEquals(expectedPreviewFrames, observed.map { it.frameIndex })
            observed.forEach { call ->
                assertEquals(index.timeOf(call.frameIndex), call.ptsUs)
            }
            // Original observer never ran on the producer thread.
            val diagnostics = wrapper.previewDiagnostics()
            val producerThreads = diagnostics.producerThreadIds
            assertTrue(producerThreads.isNotEmpty())
            observerThreads.forEach { observerThread ->
                assertTrue(
                    "preview observer ran on producer thread",
                    observerThread !in producerThreads,
                )
                assertEquals(consumerThread, observerThread)
            }
            assertEquals(consumerThread, diagnostics.consumerThreadId)
            assertEquals(observed.size, diagnostics.deliveredPreviewPackets)
            assertEquals(observed.size, diagnostics.previewPtsMatches)
            assertEquals(0, diagnostics.previewPtsMismatches)
            assertEquals(0, diagnostics.droppedOldest)
            assertTrue(diagnostics.maxPendingPreviewPackets in 0..4)
            assertTrue(diagnostics.maxLivePreviewCopies in 0..4)
            assertTrue(diagnostics.maxLivePreviewBytes <= PreviewMmrPrefetchFrameSource.MAX_PREVIEW_BYTES)
            // Fail-fast cadence reconciliation: every cadence packet produced from index 0 is
            // delivered exactly once; non-cadence frames retain nothing.
            val expectedCadence = PreviewMmrPrefetchFrameSource.expectedPreviewPackets(0, index.frameCount)
            assertEquals(expectedCadence, diagnostics.expectedPreviewPackets)
            assertEquals(expectedCadence, diagnostics.producedPreviewPackets)
            assertEquals(diagnostics.producedPreviewPackets, diagnostics.deliveredPreviewPackets)
            assertEquals(0, diagnostics.pendingPreviewPackets)
            // Private borrowed copies are recycled after the observer; display copies are owned
            // by the VM (here the test never retains one, so every recorded bitmap is recycled).
            observed.forEach { call ->
                assertTrue("private preview copy must be recycled after observer", call.bitmap.isRecycled)
            }
            // Underlying prefetch joined its producer and terminated its owner.
            assertTrue(diagnostics.prefetchDiagnostics.joined)
            assertEquals(index.frameCount, diagnostics.prefetchDiagnostics.produced)
            assertEquals(index.frameCount, diagnostics.prefetchDiagnostics.consumed)
            wrapper.close()
            assertTrue(wrapper.previewDiagnostics().closed)
            assertTrue(wrapper.previewDiagnostics().prefetchDiagnostics.ownerShutdown)
            // Idle double-close is idempotent and must not throw or leak.
            wrapper.close()
            assertTrue(wrapper.previewDiagnostics().closed)
            assertTrue(wrapper.previewDiagnostics().prefetchDiagnostics.ownerTerminated)
        } finally {
            try {
                wrapper?.close()
            } catch (_: Throwable) {
            }
            video.delete()
        }
    }

    private data class PreviewCall(val frameIndex: Int, val ptsUs: Long, val bitmap: Bitmap)
}
