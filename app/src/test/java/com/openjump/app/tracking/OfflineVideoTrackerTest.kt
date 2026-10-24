package com.openjump.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineVideoTrackerTest {

    @Test
    fun `uses source timestamps scales coordinates and stops decoding on loss`() = kotlinx.coroutines.runBlocking {
        val source = FakeFrameSource(frameCount = 5)
        val engine = FakeMotionTracker(lostAtTimestampUs = 20_000L)
        val runner = OfflineVideoTracker(source, engine)

        val initial = runner.initialize(frameIndex = 0, seedInPresentation = ImagePoint(50.0, 40.0))
        runner.trackRemaining { _, _, _ -> }

        assertEquals(50.0, initial.result.sample.point!!.x, 0.001)
        assertEquals(listOf(0L, 10_000L, 20_000L), runner.samples.map { it.timestampUs })
        assertEquals(TrackingStatus.LOST, runner.results.last().result.sample.status)
        assertNull(runner.results.last().result.sample.point)
        assertEquals(listOf(1, 2), source.decodedAfterSeed)
    }

    @Test
    fun `can reuse a decoded detection frame for tracker initialization`() = kotlinx.coroutines.runBlocking {
        val source = FakeFrameSource(frameCount = 3)
        val runner = OfflineVideoTracker(source, FakeMotionTracker(lostAtTimestampUs = Long.MAX_VALUE))

        runner.initialize(source.decodedFrame(1), ImagePoint(40.0, 50.0))

        assertEquals(0, source.frameAtCalls)
        assertEquals(1, runner.results.single().frameIndex)
    }

    @Test
    fun `profiler on and off produce identical results samples and callbacks`() = kotlinx.coroutines.runBlocking {
        val baseline = runFakePipeline(TrackingPerformanceProfiler(enabled = false))
        val profiled = runFakePipeline(TrackingPerformanceProfiler(enabled = true, collectPipelineDetails = true))

        assertEquals(baseline.results, profiled.results)
        assertEquals(baseline.samples, profiled.samples)
        assertEquals(baseline.statuses, profiled.statuses)
        assertEquals(baseline.decodedAfterSeed, profiled.decodedAfterSeed)
        assertEquals(baseline.progress, profiled.progress)
        assertEquals(TrackingStatus.LOST, profiled.statuses.last())
        assertNull(profiled.results.last().result.sample.point)
    }

    @Test
    fun `profiled runner records legacy and detail stages without releasing its snapshots`() =
        kotlinx.coroutines.runBlocking {
            val profiler = TrackingPerformanceProfiler(enabled = true, collectPipelineDetails = true)
            val source = FakeFrameSource(frameCount = 5)
            val runner = OfflineVideoTracker(source, FakeMotionTracker(lostAtTimestampUs = 20_000L), profiler)

            runner.initialize(frameIndex = 0, seedInPresentation = ImagePoint(50.0, 40.0))
            val earlySnapshot = runner.results
            assertEquals(1, earlySnapshot.size)

            val remaining = runner.trackRemaining { _, _, _ -> }

            assertEquals(3, remaining.size)
            assertEquals(1, earlySnapshot.size)
            assertTrue(profiler.snapshot().containsKey(TrackingPerformanceProfiler.Stage.PRESENTATION_MAPPING))
            assertTrue(profiler.snapshot().containsKey(TrackingPerformanceProfiler.Stage.PROGRESS_PUBLISH))
            assertTrue(profiler.snapshot().containsKey(TrackingPerformanceProfiler.Stage.RESULTS_SNAPSHOT))
        }

    @Test
    fun `details off leaves the new stages and counters empty`() = kotlinx.coroutines.runBlocking {
        val profiler = TrackingPerformanceProfiler(enabled = true)
        val run = runFakePipeline(profiler)

        val detailStages = setOf(
            TrackingPerformanceProfiler.Stage.DECODER_PREPARE,
            TrackingPerformanceProfiler.Stage.FRAME_COUNT_PROBE,
            TrackingPerformanceProfiler.Stage.DECODE_BATCH,
            TrackingPerformanceProfiler.Stage.RESULTS_SNAPSHOT,
        )

        assertFalse(profiler.snapshot().keys.any { it in detailStages })
        assertTrue(profiler.counters().isEmpty())
        assertTrue(profiler.snapshot().containsKey(TrackingPerformanceProfiler.Stage.PRESENTATION_MAPPING))
        assertEquals(3, run.results.size)
        assertEquals(TrackingStatus.LOST, run.statuses.last())
    }

    @Test
    fun `lost latch still short circuits with the profiler enabled`() = kotlinx.coroutines.runBlocking {
        val profiler = TrackingPerformanceProfiler(enabled = true, collectPipelineDetails = true)
        val source = FakeFrameSource(frameCount = 5)
        val runner = OfflineVideoTracker(source, FakeMotionTracker(lostAtTimestampUs = 20_000L), profiler)
        runner.initialize(frameIndex = 0, seedInPresentation = ImagePoint(50.0, 40.0))
        runner.trackRemaining { _, _, _ -> }
        val resultsAfterLoss = runner.results
        val decodedAfterLoss = source.decodedAfterSeed.toList()

        val repeated = runner.trackRemaining { _, _, _ -> }

        assertEquals(resultsAfterLoss, repeated)
        assertEquals(decodedAfterLoss, source.decodedAfterSeed)
        assertEquals(TrackingStatus.LOST, repeated.last().result.sample.status)
    }

    private class FakeRun(
        val results: List<TrackingFrameResult>,
        val samples: List<TrackingSample>,
        val statuses: List<TrackingStatus>,
        val decodedAfterSeed: List<Int>,
        val progress: List<Int>,
    )

    private suspend fun runFakePipeline(profiler: TrackingPerformanceProfiler): FakeRun {
        val source = FakeFrameSource(frameCount = 5)
        val runner = OfflineVideoTracker(source, FakeMotionTracker(lostAtTimestampUs = 20_000L), profiler)
        runner.initialize(frameIndex = 0, seedInPresentation = ImagePoint(50.0, 40.0))
        val progress = mutableListOf<Int>()
        val returned = runner.trackRemaining { _, processed, _ -> progress += processed }
        val results = runner.results
        assertEquals(returned.size, results.size)
        return FakeRun(
            results = results,
            samples = runner.samples,
            statuses = results.map { it.result.sample.status },
            decodedAfterSeed = source.decodedAfterSeed.toList(),
            progress = progress.toList(),
        )
    }

    private class FakeFrameSource(override val frameCount: Int) : TrackingFrameSource {
        val decodedAfterSeed = mutableListOf<Int>()
        var frameAtCalls = 0

        override suspend fun frameAt(index: Int): DecodedTrackingFrame {
            frameAtCalls++
            return frame(index)
        }

        fun decodedFrame(index: Int): DecodedTrackingFrame = frame(index)

        override suspend fun forEachFrame(
            startIndex: Int,
            onFrame: suspend (DecodedTrackingFrame) -> Boolean,
        ) {
            for (index in startIndex until frameCount) {
                decodedAfterSeed += index
                if (!onFrame(frame(index))) break
            }
        }

        override fun close() = Unit

        private fun frame(index: Int) = DecodedTrackingFrame(
            frameIndex = index,
            timestampUs = index * 10_000L,
            image = GrayFrame(10, 10, ByteArray(100)),
            presentationWidth = 100,
            presentationHeight = 100,
        )
    }

    private class FakeMotionTracker(private val lostAtTimestampUs: Long) : MotionTracker {
        private var point = ImagePoint(0.0, 0.0)

        override fun initialize(frame: GrayFrame, timestampUs: Long, seed: ImagePoint): TrackingResult {
            point = seed
            return measured(timestampUs)
        }

        override fun track(frame: GrayFrame, timestampUs: Long): TrackingResult {
            if (timestampUs >= lostAtTimestampUs) {
                return TrackingResult(
                    TrackingSample(timestampUs, null, 0.0, TrackingStatus.LOST),
                    null,
                    emptyList(),
                    TrackingDiagnostics.EMPTY,
                )
            }
            point = ImagePoint(point.x + 1.0, point.y)
            return measured(timestampUs)
        }

        override fun reset() = Unit

        private fun measured(timestampUs: Long) = TrackingResult(
            sample = TrackingSample(timestampUs, point, 1.0, TrackingStatus.TRACKING),
            region = ImageRegion(point, 2.0, 2.0),
            featurePoints = listOf(point),
            diagnostics = TrackingDiagnostics(1, 1, 1, 0.0, 1.0),
        )
    }
}
