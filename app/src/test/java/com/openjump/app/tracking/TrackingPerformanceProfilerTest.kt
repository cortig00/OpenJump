package com.openjump.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingPerformanceProfilerTest {

    @Test
    fun `aggregates per-frame timings without logging each sample`() {
        val profiler = TrackingPerformanceProfiler()
        profiler.record(TrackingPerformanceProfiler.Stage.DECODE, 40_000_000L, repetitions = 4)
        profiler.record(TrackingPerformanceProfiler.Stage.DECODE, 20_000_000L)

        val stats = requireNotNull(profiler.snapshot()[TrackingPerformanceProfiler.Stage.DECODE])

        assertEquals(5, stats.samples)
        assertEquals(60.0, stats.totalMs, 0.001)
        assertEquals(12.0, stats.meanMs, 0.001)
        assertEquals(10.0, stats.p50Ms, 0.001)
        assertEquals(10.0, stats.p95Ms, 0.001)
        assertEquals(20.0, stats.maxMs, 0.001)
        assertTrue(profiler.summary().contains("decode: n=5"))
    }

    @Test
    fun `disabled profiler has no samples`() {
        val profiler = TrackingPerformanceProfiler(enabled = false)

        val value = profiler.measure(TrackingPerformanceProfiler.Stage.KLT_FORWARD_BACKWARD) { 42 }
        profiler.record(TrackingPerformanceProfiler.Stage.DECODE, 10_000_000L)

        assertEquals(42, value)
        assertTrue(profiler.snapshot().isEmpty())
    }

    @Test
    fun `detail stages and counters stay absent until details are collected`() {
        val profiler = TrackingPerformanceProfiler()

        val value = profiler.measureDetailed(TrackingPerformanceProfiler.Stage.DECODE_BATCH) { 7 }
        profiler.record(TrackingPerformanceProfiler.Stage.DECODE_BATCH, 5_000_000L)
        profiler.count(TrackingPerformanceProfiler.Metric.DELIVERED_FRAMES)

        assertEquals(7, value)
        assertTrue(profiler.snapshot().isEmpty())
        assertTrue(profiler.counters().isEmpty())
        assertTrue(!profiler.summary().contains("counters"))
    }

    @Test
    fun `detail stages and counters accumulate when details are enabled`() {
        val profiler = TrackingPerformanceProfiler(collectPipelineDetails = true)

        profiler.measureDetailed(TrackingPerformanceProfiler.Stage.DECODER_PREPARE) { "prepared" }
        profiler.measureDetailed(TrackingPerformanceProfiler.Stage.RESULTS_SNAPSHOT) { listOf(1) }
        profiler.count(TrackingPerformanceProfiler.Metric.DECODE_BATCH_ATTEMPTS, 2L)
        profiler.count(TrackingPerformanceProfiler.Metric.DELIVERED_FRAMES, 3L)

        assertEquals(
            1,
            requireNotNull(profiler.snapshot()[TrackingPerformanceProfiler.Stage.DECODER_PREPARE]).samples,
        )
        assertEquals(
            1,
            requireNotNull(profiler.snapshot()[TrackingPerformanceProfiler.Stage.RESULTS_SNAPSHOT]).samples,
        )
        assertEquals(
            2L,
            requireNotNull(profiler.counters()[TrackingPerformanceProfiler.Metric.DECODE_BATCH_ATTEMPTS]),
        )
        assertEquals(
            3L,
            requireNotNull(profiler.counters()[TrackingPerformanceProfiler.Metric.DELIVERED_FRAMES]),
        )
        assertTrue(profiler.counters().containsKey(TrackingPerformanceProfiler.Metric.DECODE_BATCH_FAILURES))
        assertTrue(profiler.summary().contains("counters: decode_batch_attempts=2"))
    }

    @Test
    fun `disabled profiler wins over enabled details`() {
        val profiler = TrackingPerformanceProfiler(enabled = false, collectPipelineDetails = true)

        val value = profiler.measureDetailed(TrackingPerformanceProfiler.Stage.DECODE_BATCH) { 3 }
        profiler.count(TrackingPerformanceProfiler.Metric.DELIVERED_FRAMES, 5L)

        assertEquals(3, value)
        assertTrue(profiler.snapshot().isEmpty())
        assertTrue(profiler.counters().isEmpty())
        assertEquals("", profiler.summary())
    }

    @Test
    fun `counter deltas ignore non positive values and reset clears stages and counters`() {
        val profiler = TrackingPerformanceProfiler(collectPipelineDetails = true)
        profiler.count(TrackingPerformanceProfiler.Metric.DECODE_BATCH_FAILURES, 2L)
        profiler.count(TrackingPerformanceProfiler.Metric.DECODE_BATCH_FAILURES, 0L)
        profiler.count(TrackingPerformanceProfiler.Metric.DECODE_BATCH_FAILURES, -3L)

        val snapshot = profiler.counters()
        assertEquals(2L, requireNotNull(snapshot[TrackingPerformanceProfiler.Metric.DECODE_BATCH_FAILURES]))

        profiler.record(TrackingPerformanceProfiler.Stage.DECODE, 10_000_000L)
        profiler.reset()

        assertTrue(profiler.snapshot().isEmpty())
        assertTrue(profiler.counters().values.all { it == 0L })
        assertEquals(2L, requireNotNull(snapshot[TrackingPerformanceProfiler.Metric.DECODE_BATCH_FAILURES]))
    }

    @Test
    fun `batch timing keeps one real sample while legacy decode stays amortized`() {
        val profiler = TrackingPerformanceProfiler(collectPipelineDetails = true)

        profiler.record(TrackingPerformanceProfiler.Stage.DECODE, 40_000_000L, repetitions = 4)
        profiler.record(TrackingPerformanceProfiler.Stage.DECODE_BATCH, 40_000_000L)

        val decode = requireNotNull(profiler.snapshot()[TrackingPerformanceProfiler.Stage.DECODE])
        val batch = requireNotNull(profiler.snapshot()[TrackingPerformanceProfiler.Stage.DECODE_BATCH])

        assertEquals(4, decode.samples)
        assertEquals(40.0, decode.totalMs, 0.001)
        assertEquals(10.0, decode.meanMs, 0.001)
        assertEquals(1, batch.samples)
        assertEquals(40.0, batch.totalMs, 0.001)
    }

    @Test
    fun `detailed measure propagates the failure and only records when details are collected`() {
        val silent = TrackingPerformanceProfiler()
        var silentFailureMessage: String? = null
        try {
            silent.measureDetailed(TrackingPerformanceProfiler.Stage.DECODER_PREPARE) { error("prepare failed") }
        } catch (failure: IllegalStateException) {
            silentFailureMessage = failure.message
        }

        assertEquals("prepare failed", silentFailureMessage)
        assertTrue(silent.snapshot().isEmpty())

        val detailed = TrackingPerformanceProfiler(collectPipelineDetails = true)
        var detailedFailureMessage: String? = null
        try {
            detailed.measureDetailed(TrackingPerformanceProfiler.Stage.DECODER_PREPARE) { error("prepare failed") }
        } catch (failure: IllegalStateException) {
            detailedFailureMessage = failure.message
        }

        assertEquals("prepare failed", detailedFailureMessage)
        assertEquals(
            1,
            requireNotNull(detailed.snapshot()[TrackingPerformanceProfiler.Stage.DECODER_PREPARE]).samples,
        )
    }
}
