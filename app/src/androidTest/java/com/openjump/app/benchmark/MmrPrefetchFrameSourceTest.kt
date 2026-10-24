package com.openjump.app.benchmark

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.tracking.DecodedTrackingFrame
import com.openjump.app.tracking.GrayFrame
import com.openjump.app.tracking.TrackingFrameSource
import com.openjump.app.video.VideoFrameIndexer
import com.openjump.app.video.VideoTrackingFrameSource
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fast canary checks for the test-only prefetch wrapper. No Room, no UI, no preview.
 * Real-fixture test reuses the 20-frame translated asset and compares exact grayscale bytes
 * (contentEquals, never a self-hash) plus canonical PTS from the index.
 *
 * Coordination uses [CompletableDeferred] barriers (never sleep-only) and every suspending test
 * carries a JUnit timeout so a regression hangs the test instead of the instrumentation run.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class MmrPrefetchFrameSourceTest {

    @Test
    fun envelopeConstantsStayBounded() {
        assertEquals(2, MmrPrefetchFrameSource.QUEUE_CAPACITY)
        assertEquals(720, MmrPrefetchFrameSource.MAX_TRACKING_DIMENSION)
        assertEquals(518400, MmrPrefetchFrameSource.MAX_GRAY_BYTES)
        assertEquals(4, MmrPrefetchFrameSource.MAX_OUTSTANDING_FRAMES)
        assertEquals(2073600, MmrPrefetchFrameSource.MAX_ADDITIONAL_BYTES)
        assertTrue(MmrPrefetchFrameSource.MAX_ADDITIONAL_BYTES < 2 * 1024 * 1024)
        assertEquals(66L * 1024L * 1024L, MmrPrefetchFrameSource.COMBINED_IMAGE_ENVELOPE_BYTES)
    }

    @Test(timeout = 120_000)
    fun prefetchPreservesExactGrayscaleAndPtsOnTranslatedFixture() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val video = File(context.cacheDir, "mmr-prefetch-translation-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("tracking_translation.mp4").use { input ->
            video.outputStream().use { output -> input.copyTo(output) }
        }
        var executor: java.util.concurrent.ExecutorService? = null
        var dispatcher: ExecutorCoroutineDispatcher? = null
        var direct: VideoTrackingFrameSource? = null
        var inner: VideoTrackingFrameSource? = null
        var wrapper: MmrPrefetchFrameSource? = null
        try {
            val index = VideoFrameIndexer.indexFromPath(video.absolutePath)
            assertTrue("translated fixture expected", index.frameCount in 2..120)
            direct = VideoTrackingFrameSource(context, Uri.fromFile(video), index)
            val expected = mutableListOf<DecodedTrackingFrame>()
            direct.forEachFrame(0) { frame ->
                expected += copyGray(frame)
                true
            }
            assertEquals(index.frameCount, expected.size)

            val owned = MmrPrefetchFrameSource.newOwnedDispatcher("mmr-prefetch-test-owner")
            executor = owned.first
            dispatcher = owned.second
            inner = VideoTrackingFrameSource(
                context = context,
                uri = Uri.fromFile(video),
                index = index,
                previewObserver = null,
                decoderDispatcher = dispatcher,
            )
            wrapper = MmrPrefetchFrameSource(
                delegate = inner,
                ownerDispatcher = dispatcher,
                ownerExecutor = executor,
            )
            // Seed stays on the owned thread via the wrapper withContext(owner) seam.
            val seed = wrapper.frameAt(0)
            assertEquals(index.timeOf(0), seed.timestampUs)
            assertEquals(expected[0].image.width, seed.image.width)
            assertTrue(seed.image.pixels.contentEquals(expected[0].image.pixels))

            val actual = mutableListOf<DecodedTrackingFrame>()
            val consumerThread = Thread.currentThread().id
            wrapper.forEachFrame(0) { frame ->
                assertEquals(consumerThread, Thread.currentThread().id)
                actual += frame
                true
            }
            assertEquals(expected.size, actual.size)
            actual.forEachIndexed { position, frame ->
                val want = expected[position]
                assertEquals(position, frame.frameIndex)
                assertEquals(index.timeOf(position), frame.timestampUs)
                assertEquals(index.frameTimesUs[position], frame.timestampUs)
                assertEquals(want.image.width, frame.image.width)
                assertEquals(want.image.height, frame.image.height)
                assertTrue("gray pixels differ at frame $position", want.image.pixels.contentEquals(frame.image.pixels))
                assertTrue(frame.image.width <= 720 && frame.image.height <= 720)
            }
            val diagnostics = wrapper.diagnostics()
            assertEquals(2, diagnostics.queueCapacity)
            assertTrue(diagnostics.highWaterMark in 0..4)
            assertTrue(diagnostics.maxOwnedBytes <= MmrPrefetchFrameSource.MAX_ADDITIONAL_BYTES)
            assertEquals(index.frameCount, diagnostics.produced)
            assertEquals(index.frameCount, diagnostics.consumed)
            assertEquals(1, diagnostics.producerThreadIds.size)
            assertNotEquals(consumerThread, diagnostics.producerThreadIds.single())
            assertEquals(consumerThread, diagnostics.consumerThreadId)
            assertTrue(diagnostics.joined)
            wrapper.close()
            assertTrue(executor.isShutdown)
            assertTrue("owner must actually terminate, not just shutdown", executor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(wrapper.diagnostics().ownerShutdown)
            assertTrue(wrapper.diagnostics().ownerTerminated)
            // Repeated close stays idempotent without throwing.
            wrapper.close()
        } finally {
            try {
                wrapper?.close()
            } catch (_: Throwable) {
            }
            try {
                direct?.close()
            } catch (_: Throwable) {
            }
            video.delete()
        }
    }

    @Test(timeout = 30_000)
    fun fakeOrderBackpressureAndSingleOwnerThread() = runBlocking {
        val owned = MmrPrefetchFrameSource.newOwnedDispatcher("mmr-prefetch-fake-owner")
        val executor = owned.first
        val dispatcher = owned.second
        val frames = (0 until 6).map { position ->
            DecodedTrackingFrame(
                frameIndex = position,
                timestampUs = 1_000L * position,
                image = GrayFrame(4, 4, ByteArray(16) { position.toByte() }),
                presentationWidth = 4,
                presentationHeight = 4,
            )
        }
        val fake = FakeOwnerSource(frames, dispatcher)
        val wrapper = MmrPrefetchFrameSource(fake, dispatcher, executor)
        try {
            val seen = mutableListOf<Int>()
            wrapper.forEachFrame(0) { frame ->
                seen += frame.frameIndex
                delay(5)
                true
            }
            assertEquals(listOf(0, 1, 2, 3, 4, 5), seen)
            assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L), frames.map { it.timestampUs })
            val diagnostics = wrapper.diagnostics()
            assertEquals(6, diagnostics.produced)
            assertEquals(6, diagnostics.consumed)
            // Bounded backpressure: at most queue + send + current outstanding.
            assertTrue(diagnostics.highWaterMark in 1..4)
            assertTrue(diagnostics.maxOwnedBytes in 16..(4 * 16).toLong())
            assertTrue(diagnostics.maxOwnedBytes <= 4 * 16)
            assertEquals(1, diagnostics.producerThreadIds.size)
            // Every delegate entry ran on the single owned thread.
            assertTrue(fake.forEachThreads.isNotEmpty())
            assertEquals(1, (fake.forEachThreads).toSet().size)
            assertEquals(diagnostics.producerThreadIds.single(), fake.forEachThreads.first())
            assertNotEquals(Thread.currentThread().id, fake.forEachThreads.first())
        } finally {
            wrapper.close()
            assertTrue(executor.isShutdown)
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(wrapper.diagnostics().ownerTerminated)
        }
    }

    @Test(timeout = 30_000)
    fun consumerFalseStopsWithoutProgressingFuture() = runBlocking {
        val owned = MmrPrefetchFrameSource.newOwnedDispatcher("mmr-prefetch-false-owner")
        val executor = owned.first
        val dispatcher = owned.second
        val frames = (0 until 8).map { position ->
            DecodedTrackingFrame(
                position, 1_000L * position,
                GrayFrame(4, 4, ByteArray(16) { position.toByte() }), 4, 4,
            )
        }
        val fake = FakeOwnerSource(frames, dispatcher)
        val wrapper = MmrPrefetchFrameSource(fake, dispatcher, executor)
        try {
            val seen = mutableListOf<Int>()
            wrapper.forEachFrame(0) { frame ->
                seen += frame.frameIndex
                seen.size < 2
            }
            assertEquals(listOf(0, 1), seen)
            val diagnostics = wrapper.diagnostics()
            assertEquals(2, diagnostics.consumed)
            // Prefetch may decode at most the bounded ahead window, never the whole future.
            assertTrue(diagnostics.produced <= diagnostics.consumed + 3)
            // Cancelled only when the producer was still active; if it already completed the flag
            // stays false (no race to assert).
            if (diagnostics.produced < frames.size) {
                assertTrue(diagnostics.cancelled)
            }
            assertTrue(diagnostics.joined)
        } finally {
            wrapper.close()
        }
    }

    @Test(timeout = 30_000)
    fun producerErrorPropagatesAfterPendingConsumed() = runBlocking {
        val owned = MmrPrefetchFrameSource.newOwnedDispatcher("mmr-prefetch-error-owner")
        val executor = owned.first
        val dispatcher = owned.second
        val frames = (0 until 5).map { position ->
            DecodedTrackingFrame(
                position, 1_000L * position,
                GrayFrame(4, 4, ByteArray(16) { position.toByte() }), 4, 4,
            )
        }
        val failure = RuntimeException("fake decode failure at 3")
        val fake = FakeOwnerSource(frames, dispatcher, failAtIndex = 3, failWith = failure)
        val wrapper = MmrPrefetchFrameSource(fake, dispatcher, executor)
        try {
            val seen = mutableListOf<Int>()
            try {
                wrapper.forEachFrame(0) { frame ->
                    seen += frame.frameIndex
                    true
                }
                fail("producer failure must propagate")
            } catch (error: RuntimeException) {
                assertEquals("fake decode failure at 3", error.message)
            }
            // Pending frames before the failure were still delivered in order. Single error path
            // via channel close: no uncaught crash, producer joined.
            assertEquals(listOf(0, 1, 2), seen)
            assertTrue(wrapper.diagnostics().joined)
        } finally {
            wrapper.close()
        }
    }

    @Test(timeout = 30_000)
    fun consumerErrorCancelsProducerWithoutLosingPrimary() = runBlocking {
        val owned = MmrPrefetchFrameSource.newOwnedDispatcher("mmr-prefetch-consumer-error")
        val executor = owned.first
        val dispatcher = owned.second
        val frames = (0 until 6).map { position ->
            DecodedTrackingFrame(
                position, 1_000L * position,
                GrayFrame(4, 4, ByteArray(16) { position.toByte() }), 4, 4,
            )
        }
        val fake = FakeOwnerSource(frames, dispatcher)
        val wrapper = MmrPrefetchFrameSource(fake, dispatcher, executor)
        try {
            try {
                wrapper.forEachFrame(0) {
                    throw IllegalStateException("fake KLT failure")
                }
                fail("consumer failure must propagate")
            } catch (error: IllegalStateException) {
                assertEquals("fake KLT failure", error.message)
            }
            val diagnostics = wrapper.diagnostics()
            assertTrue(diagnostics.cancelled)
            assertTrue(diagnostics.joined)
            // Consumer-bound counts: the throwing frame counts as delivered-once (finally), the
            // rest stay produced-but-undelivered, never silently swallowed.
            assertEquals(1, diagnostics.consumed)
            assertTrue(diagnostics.produced >= diagnostics.consumed)
        } finally {
            wrapper.close()
        }
    }

    @Test(timeout = 30_000)
    fun concurrentCloseAndReentryAreRejectedExplicitly() = runBlocking {
        val owned = MmrPrefetchFrameSource.newOwnedDispatcher("mmr-prefetch-reentry")
        val executor = owned.first
        val dispatcher = owned.second
        val frames = (0 until 4).map { position ->
            DecodedTrackingFrame(
                position, 1_000L * position,
                GrayFrame(2, 2, ByteArray(4) { position.toByte() }), 2, 2,
            )
        }
        val fake = FakeOwnerSource(frames, dispatcher)
        val wrapper = MmrPrefetchFrameSource(fake, dispatcher, executor)
        try {
            val entered = CompletableDeferred<Unit>()
            // Concurrent forEachFrame calls race explicitly instead of corrupting the queue.
            val first = launch {
                wrapper.forEachFrame(0) {
                    if (!entered.isCompleted) entered.complete(Unit)
                    delay(20)
                    true
                }
            }
            entered.await()
            try {
                wrapper.forEachFrame(0) { true }
                fail("concurrent forEachFrame must be rejected")
            } catch (_: IllegalStateException) {
            }
            first.join()
            wrapper.close()
            try {
                wrapper.frameAt(0)
                fail("frameAt after close must be rejected")
            } catch (_: IllegalStateException) {
            }
            try {
                wrapper.forEachFrame(0) { true }
                fail("forEachFrame after close must be rejected")
            } catch (_: IllegalStateException) {
            }
            wrapper.close()
            assertTrue(executor.isShutdown)
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            try {
                wrapper.close()
            } catch (_: Throwable) {
            }
        }
    }

    @Test(timeout = 30_000)
    fun closeFromOwnerThreadDoesNotDeadlock() = runBlocking {
        val owned = MmrPrefetchFrameSource.newOwnedDispatcher("mmr-prefetch-owner-close")
        val executor = owned.first
        val dispatcher = owned.second
        val frames = (0 until 2).map { position ->
            DecodedTrackingFrame(
                position, 1_000L * position,
                GrayFrame(2, 2, ByteArray(4)), 2, 2,
            )
        }
        val fake = FakeOwnerSource(frames, dispatcher)
        val wrapper = MmrPrefetchFrameSource(fake, dispatcher, executor)
        try {
            withContext(dispatcher) {
                // Synchronous close from the owner thread itself must call through directly.
                // Eager owner bind makes the guard valid before any op.
                wrapper.close()
            }
            assertTrue(fake.closed)
            assertTrue(fake.closeThreads.isNotEmpty())
            assertTrue(executor.isShutdown)
            // Owner idle close must not self-await; termination observed externally after return.
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(wrapper.diagnostics().ownerTerminated)
        } finally {
            try {
                wrapper.close()
            } catch (_: Throwable) {
            }
        }
    }

    @Test(timeout = 30_000)
    fun cancellationWhenBlockedJoinsProducer() = runBlocking {
        val owned = MmrPrefetchFrameSource.newOwnedDispatcher("mmr-prefetch-cancel-blocked")
        val executor = owned.first
        val dispatcher = owned.second
        val frames = (0 until 10).map { position ->
            DecodedTrackingFrame(
                position, 1_000L * position,
                GrayFrame(4, 4, ByteArray(16) { position.toByte() }), 4, 4,
            )
        }
        val fake = FakeOwnerSource(frames, dispatcher)
        val wrapper = MmrPrefetchFrameSource(fake, dispatcher, executor)
        try {
            val entered = CompletableDeferred<Unit>()
            val job = launch {
                wrapper.forEachFrame(0) {
                    if (!entered.isCompleted) entered.complete(Unit)
                    delay(50)
                    true
                }
            }
            entered.await()
            job.cancel(CancellationException("test cancel while producer blocked on backpressure"))
            job.join()
            val diagnostics = wrapper.diagnostics()
            assertTrue(diagnostics.cancelled)
            assertTrue(diagnostics.joined)
        } finally {
            wrapper.close()
        }
    }

    @Test(timeout = 30_000)
    fun concurrentCloseWhileActiveRejectedThenCancelJoinIdleClose() = runBlocking {
        val owned = MmrPrefetchFrameSource.newOwnedDispatcher("mmr-prefetch-close-active")
        val executor = owned.first
        val dispatcher = owned.second
        val frames = (0 until 10).map { position ->
            DecodedTrackingFrame(
                position, 1_000L * position,
                GrayFrame(4, 4, ByteArray(16) { position.toByte() }), 4, 4,
            )
        }
        val fake = FakeOwnerSource(frames, dispatcher)
        val wrapper = MmrPrefetchFrameSource(fake, dispatcher, executor)
        val entered = CompletableDeferred<Unit>()
        val work = launch {
            wrapper.forEachFrame(0) {
                if (!entered.isCompleted) entered.complete(Unit)
                delay(100)
                true
            }
        }
        try {
            entered.await()
            // Synchronous close racing an active forEachFrame is explicitly rejected WITHOUT
            // setting closed/shutdown/release (minimal approved option, no async auto-close).
            try {
                wrapper.close()
                fail("close while active must be rejected")
            } catch (_: IllegalStateException) {
            }
            assertFalse(wrapper.diagnostics().closed)
            assertFalse(wrapper.diagnostics().ownerShutdown)
            assertFalse(executor.isShutdown)
            // Caller cancels its own operation; forEachFrame joins the producer, drains and frees
            // the slot. Then an idle close on the owner succeeds with no leaks.
            work.cancel(CancellationException("test cancel active forEachFrame"))
            work.join()
            assertTrue(wrapper.diagnostics().joined)
            withContext(dispatcher) {
                wrapper.close()
            }
            assertTrue(fake.closed)
            assertTrue(executor.isShutdown)
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(wrapper.diagnostics().ownerTerminated)
        } finally {
            if (work.isActive) {
                work.cancel(CancellationException("test cleanup"))
                work.join()
            }
            try {
                wrapper.close()
            } catch (_: Throwable) {
            }
        }
    }

    @Test(timeout = 30_000)
    fun consumerCurrentBytesCountedDuringOnFrame() = runBlocking {
        val owned = MmrPrefetchFrameSource.newOwnedDispatcher("mmr-prefetch-consumer-current")
        val executor = owned.first
        val dispatcher = owned.second
        val frames = (0 until 4).map { position ->
            DecodedTrackingFrame(
                position, 1_000L * position,
                GrayFrame(4, 4, ByteArray(16) { position.toByte() }), 4, 4,
            )
        }
        val fake = FakeOwnerSource(frames, dispatcher)
        val wrapper = MmrPrefetchFrameSource(fake, dispatcher, executor)
        try {
            val observedConsumed = mutableListOf<Int>()
            wrapper.forEachFrame(0) { frame ->
                // Consumed increments AFTER onFrame (finally), so during delivery of frame N the
                // counter still reads N, proving consumer-current is still owned in the window.
                observedConsumed += wrapper.diagnostics().consumed
                assertEquals(frame.frameIndex, wrapper.diagnostics().consumed)
                true
            }
            assertEquals(listOf(0, 1, 2, 3), observedConsumed)
            val diagnostics = wrapper.diagnostics()
            assertEquals(4, diagnostics.consumed)
            assertTrue(diagnostics.highWaterMark in 1..4)
            assertTrue(diagnostics.maxOwnedBytes in 16..(4 * 16).toLong())
        } finally {
            wrapper.close()
        }
    }

    @Test(timeout = 30_000)
    fun overWideFrameFailsFast() = runBlocking {
        val owned = MmrPrefetchFrameSource.newOwnedDispatcher("mmr-prefetch-overwide")
        val executor = owned.first
        val dispatcher = owned.second
        val wide = DecodedTrackingFrame(
            frameIndex = 0,
            timestampUs = 0L,
            image = GrayFrame(800, 2, ByteArray(1600)),
            presentationWidth = 800,
            presentationHeight = 2,
        )
        val fake = FakeOwnerSource(listOf(wide), dispatcher)
        val wrapper = MmrPrefetchFrameSource(fake, dispatcher, executor)
        try {
            try {
                wrapper.forEachFrame(0) { true }
                fail("over-wide frame must fail fast")
            } catch (_: IllegalArgumentException) {
            }
            assertTrue(wrapper.diagnostics().joined)
            // Seed path enforces the same envelope.
            val seedFake = FakeOwnerSource(listOf(wide), dispatcher)
            val seedOwned = MmrPrefetchFrameSource.newOwnedDispatcher("mmr-prefetch-overwide-seed")
            val seedExecutor = seedOwned.first
            val seedDispatcher = seedOwned.second
            val seedWrapper = MmrPrefetchFrameSource(seedFake, seedDispatcher, seedExecutor)
            try {
                try {
                    seedWrapper.frameAt(0)
                    fail("over-wide seed must fail fast")
                } catch (_: IllegalArgumentException) {
                }
            } finally {
                seedWrapper.close()
                assertTrue(seedExecutor.awaitTermination(5, TimeUnit.SECONDS))
            }
        } finally {
            wrapper.close()
        }
    }

    @Test(timeout = 30_000)
    fun ownerTerminationSeparateFromShutdown() = runBlocking {
        val owned = MmrPrefetchFrameSource.newOwnedDispatcher("mmr-prefetch-termination")
        val executor = owned.first
        val dispatcher = owned.second
        val frames = (0 until 2).map { position ->
            DecodedTrackingFrame(
                position, 1_000L * position,
                GrayFrame(2, 2, ByteArray(4)), 2, 2,
            )
        }
        val fake = FakeOwnerSource(frames, dispatcher)
        val wrapper = MmrPrefetchFrameSource(fake, dispatcher, executor)
        try {
            wrapper.forEachFrame(0) { true }
            assertFalse(executor.isShutdown)
            wrapper.close()
            assertTrue(executor.isShutdown)
            assertTrue("actual termination needs await, not OR-shutdown", executor.awaitTermination(5, TimeUnit.SECONDS))
            val diagnostics = wrapper.diagnostics()
            assertTrue(diagnostics.ownerShutdown)
            assertTrue(diagnostics.ownerTerminated)
            assertTrue(diagnostics.ownerTerminatedOrShutdown)
        } finally {
            try {
                wrapper.close()
            } catch (_: Throwable) {
            }
        }
    }

    @Test(timeout = 30000)
    fun repeatedCancellationHandoffLeavesSlotReusableAndOwnerTerminates() = runBlocking {
        val (executor, dispatcher) = MmrPrefetchFrameSource.newOwnedDispatcher("mmr-prefetch-cancel-race")
        val frames = (0 until 40).map { i ->
            DecodedTrackingFrame(i, i * 1000L, GrayFrame(2, 2, ByteArray(4)), 2, 2)
        }
        val wrapper = MmrPrefetchFrameSource(FakeOwnerSource(frames, dispatcher), dispatcher, executor)
        try {
            repeat(100) {
                val firstReceived = kotlinx.coroutines.CompletableDeferred<Unit>()
                val running = launch {
                    wrapper.forEachFrame(0) {
                        firstReceived.complete(Unit)
                        kotlinx.coroutines.yield()
                        true
                    }
                }
                firstReceived.await()
                running.cancel()
                running.join()
                assertTrue(wrapper.diagnostics().joined)
                assertEquals(0, wrapper.frameAt(0).frameIndex)
            }
        } finally {
            wrapper.close()
        }
        assertTrue(wrapper.diagnostics().ownerTerminated)
    }

    private fun copyGray(frame: DecodedTrackingFrame): DecodedTrackingFrame = frame.copy(
        image = GrayFrame(frame.image.width, frame.image.height, frame.image.pixels.clone()),
    )

    private class FakeOwnerSource(
        private val frames: List<DecodedTrackingFrame>,
        private val ownerDispatcher: ExecutorCoroutineDispatcher,
        private val failAtIndex: Int? = null,
        private val failWith: Throwable? = null,
    ) : TrackingFrameSource {
        override val frameCount: Int get() = frames.size
        val frameAtThreads: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val forEachThreads: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val closeThreads: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        var closed: Boolean = false

        override suspend fun frameAt(index: Int): DecodedTrackingFrame = withContext(ownerDispatcher) {
            frameAtThreads += Thread.currentThread().id
            if (failAtIndex != null && index == failAtIndex) throw failWith ?: RuntimeException("fake failure at $index")
            frames[index]
        }

        override suspend fun forEachFrame(
            startIndex: Int,
            onFrame: suspend (DecodedTrackingFrame) -> Boolean,
        ) = withContext(ownerDispatcher) {
            forEachThreads += Thread.currentThread().id
            for (position in startIndex until frames.size) {
                if (failAtIndex != null && position == failAtIndex) {
                    throw failWith ?: RuntimeException("fake failure at $position")
                }
                if (!onFrame(frames[position])) break
            }
        }

        override fun close() {
            closeThreads += Thread.currentThread().id
            closed = true
        }
    }
}
