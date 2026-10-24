package com.openjump.app.benchmark

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in MMR prefetch canary experiment. Reuses the exact harness parser, pipeline, timing,
 * profiler/sampler and snapshot/report protocol; only the frame-source route differs.
 *
 * Invocation (serial, explicit emulator, parent-owned AVD):
 * `encoder_perf_mmr_prefetch=OFF|ON` plus the usual `encoder_perf_*` arguments. OFF is the exact
 * usual MMR route (default Dispatchers.IO). ON owns a dedicated single thread + bounded queue.
 * Both arms are CORE_ONLY with onProgress noop and previewObserver null
 * (UI_PREVIEW_NOT_RUN); no UI integration in this slice.
 *
 * Report checks stream via File.useLines and never call readText/parse the full 40MB+ report
 * (previous OOM). Full snapshot equality stays parent-host owned.
 */
@RunWith(AndroidJUnit4::class)
class MmrPrefetchExperimentTest {

    @Test
    fun parserAcceptsOnOffAndRejectsInvalidPrefetchFlag() {
        val prefix = EncoderPerfArguments.PREFIX
        val key = EncoderPerfArguments.MMR_PREFETCH_KEY
        val off = EncoderPerfArguments.parse(validBundle("$key" to "OFF"))
        assertTrue("OFF must parse, got $off", off is EncoderPerfParse.Requested)
        assertEquals("OFF", (off as EncoderPerfParse.Requested).request.mmrPrefetch)
        val on = EncoderPerfArguments.parse(validBundle("$key" to "ON"))
        assertTrue("ON must parse, got $on", on is EncoderPerfParse.Requested)
        assertEquals("ON", (on as EncoderPerfParse.Requested).request.mmrPrefetch)
        val absent = EncoderPerfArguments.parse(validBundle())
        assertTrue("absent flag defaults to OFF, got $absent", absent is EncoderPerfParse.Requested)
        assertEquals("OFF", (absent as EncoderPerfParse.Requested).request.mmrPrefetch)
        val bad = EncoderPerfArguments.parse(validBundle("$key" to "SOMETIMES"))
        assertTrue("invalid flag must be rejected, got $bad", bad is EncoderPerfParse.Invalid)
        val blank = EncoderPerfArguments.parse(blankBundle(key))
        assertTrue("blank flag must be rejected, got $blank", blank is EncoderPerfParse.Invalid)
    }

    @Test
    fun experimentRunWhenRequested() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        if (!arguments.containsKey(EncoderPerfArguments.MMR_PREFETCH_KEY)) {
            EncoderPerfRunner.recordNotRequested(instrumentation.targetContext)
            assumeTrue("no encoder_perf_mmr_prefetch requested: experiment NOT_RUN", false)
        }
        val context = instrumentation.targetContext
        val result: EncoderPerfRunResult = runBlocking {
            when (val parsed = EncoderPerfArguments.parse(arguments)) {
                EncoderPerfParse.NotRequested -> error("unreachable: prefetch flag was present")
                is EncoderPerfParse.Invalid -> {
                    EncoderPerfRunner.recordRejected(context, parsed)
                    throw AssertionError("rejected encoder_perf_ arguments: ${parsed.errors.joinToString("; ")}")
                }
                is EncoderPerfParse.Requested -> EncoderPerfRunner.execute(context, parsed.request)
            }
        }
        assertEquals(EncoderPerformanceSupport.STATUS_PASSED, result.status)
        assertTrue(result.reportFile.isFile)
        // Streamed canary checks: tiny key/value markers only, no full JSON/readText (43MB OOM).
        val requested = arguments.getString(EncoderPerfArguments.MMR_PREFETCH_KEY)
        fun containsMarker(marker: String): Boolean =
            result.reportFile.useLines { lines -> lines.any { it.contains(marker) } }
        assertTrue("report must carry top-level PASSED", containsMarker("\"status\": \"PASSED\""))
        assertTrue(containsMarker("\"mmr_prefetch\": \"$requested\""))
        assertTrue(containsMarker("\"core_scope\": \"CORE_ONLY\""))
        assertTrue(containsMarker("\"ui_preview\": \"UI_PREVIEW_NOT_RUN\""))
        assertTrue(containsMarker("\"on_progress\": \"NOOP\""))
        assertTrue(containsMarker("\"preview_observer\": \"NULL\""))
        if (requested == EncoderPerfArguments.MMR_PREFETCH_ON) {
            assertTrue(containsMarker("\"status\": \"ENABLED\""))
            assertTrue(containsMarker("\"source_route\": \"MMR_PREFETCH_OWNED_SINGLE_THREAD\""))
            assertTrue(containsMarker("\"queue_capacity\": 2"))
            assertTrue(containsMarker("\"core_scope\": \"CORE_ONLY\""))
            assertTrue(containsMarker("\"ui_preview\": \"UI_PREVIEW_NOT_RUN\""))
        } else {
            assertTrue(containsMarker("\"status\": \"DISABLED\""))
            assertTrue(containsMarker("\"source_route\": \"DEFAULT_DISPATCHERS_IO\""))
        }
        assertTrue(containsMarker("prefetch producer join"))
        assertTrue(containsMarker(EncoderPerformanceSupport.OUTPUT_SNAPSHOT_SCHEMA))
    }

    private fun validBundle(vararg overrides: Pair<String, String>): Bundle {
        val prefix = EncoderPerfArguments.PREFIX
        val values = linkedMapOf(
            "${prefix}run_id" to "prefetch-parse",
            "${prefix}fixture" to "/tmp/prefetch-fixture.mp4",
            "${prefix}sha256" to "0".repeat(64),
            "${prefix}run_kind" to "MEASURED",
            "${prefix}expected_frames" to "100",
            "${prefix}seed_x" to "10",
            "${prefix}seed_y" to "20",
            "${prefix}cal_a_x" to "10",
            "${prefix}cal_a_y" to "20",
            "${prefix}cal_b_x" to "90",
            "${prefix}cal_b_y" to "20",
            "${prefix}reference_m" to "0.4",
            "${prefix}calibration_source" to "SYNTHETIC_TEST_NOT_PHYSICAL",
            "${prefix}exercise" to "BENCH_PRESS",
            "${prefix}expected_presentation_width" to "1080",
            "${prefix}expected_presentation_height" to "1920",
            "${prefix}load_source" to EncoderPerfArguments.PLACEHOLDER_LOAD_SOURCE,
        )
        overrides.forEach { (key, value) -> values[key] = value }
        return Bundle().apply {
            values.forEach { (key, value) -> putString(key, value) }
        }
    }

    private fun blankBundle(key: String): Bundle {
        val bundle = validBundle()
        bundle.putString(key, "   ")
        return bundle
    }
}
