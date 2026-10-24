package com.openjump.app.benchmark

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderAnalyzer
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.PhysicalTimeline
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.measurement.MetricScale
import com.openjump.app.protocol.VideoSource
import com.openjump.app.tracking.DecodedTrackingFrame
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.OfflineVideoTracker
import com.openjump.app.tracking.PyramidalKltMotionTracker
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.TrackingFrameSource
import com.openjump.app.tracking.TrackingPerformanceProfiler
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoFrameIndexer
import com.openjump.app.video.VideoTrackingFrameSource
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S0b2b initial runner: exactly one opt-in run of the real production pipeline
 * (`VideoFrameIndexer` -> `VideoTrackingFrameSource` -> `PyramidalKltMotionTracker` ->
 * `OfflineVideoTracker` -> `EncoderAnalyzer`) with a durable report and an exact output snapshot.
 *
 * What this runner deliberately does NOT do: seed or write Room, change the algorithm or any
 * production source, cap/downsample the video, aggregate a single run into medians/p95, or claim a
 * speedup, accuracy or a physically cold OS/decoder. Producer stage timings and pipeline counters are
 * collected as explicit test-core configurations: `encoder_perf_profile=false` is OFF (profiler disabled),
 * `profile=true` with `encoder_perf_pipeline_details=false` is LEGACY (legacy stage timings only, producer
 * counters NOT_COLLECTED) and `profile=true` with `pipeline_details=true` is DETAILED. `pipeline_details`
 * defaults to `profile`, so an existing `profile=true` caller keeps the legacy+details behavior; a missing
 * `pipeline_details` is never guessed as zero. The memory sampler is off by default, and the whole run is
 * skipped unless `encoder_perf_*` arguments are supplied.
 *
 * Argument prefix: `encoder_perf_`. Unknown argument names and malformed values are a durable
 * FAILED rejection; an explicitly provided missing fixture is FAILED, not a skip.
 */
@RunWith(AndroidJUnit4::class)
class EncoderPerformanceHarnessTest {

    @Test
    fun runnerIsNotRequestedWithoutOptInArguments() {
        assertEquals(
            EncoderPerfParse.NotRequested,
            EncoderPerfArguments.parse(Bundle()),
        )
        assertEquals(
            EncoderPerfParse.NotRequested,
            EncoderPerfArguments.parse(bundleOf(EncoderPerfArguments.SYNTHETIC_KEY to "true")),
        )
        assertFalse(EncoderPerfArguments.isOptIn(bundleOf(EncoderPerfArguments.SYNTHETIC_KEY to "true")))
        assertTrue(EncoderPerfArguments.isOptIn(bundleOf("${EncoderPerfArguments.PREFIX}run_id" to "x")))
    }

    @Test
    fun invalidArgumentsAreRejectedDurably() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = temporaryDirectory(context, "invalid")
        val arguments = bundleOf(
            "${EncoderPerfArguments.PREFIX}run_id" to "s0b2b-invalid",
            "${EncoderPerfArguments.PREFIX}fixture" to File(directory, "absent.mp4").absolutePath,
            "${EncoderPerfArguments.PREFIX}sha256" to "not-a-hash",
            "${EncoderPerfArguments.PREFIX}run_kind" to "TURBO",
            "${EncoderPerfArguments.PREFIX}expected_frames" to "many",
            "${EncoderPerfArguments.PREFIX}exercise" to "NOT_AN_EXERCISE",
            "${EncoderPerfArguments.PREFIX}profile" to "yes",
            "${EncoderPerfArguments.PREFIX}unknown_key" to "1",
        )
        val parsed = EncoderPerfArguments.parse(arguments)
        assertTrue("expected a durable rejection, got $parsed", parsed is EncoderPerfParse.Invalid)
        val invalid = parsed as EncoderPerfParse.Invalid
        assertTrue(invalid.errors.any { it.contains("unknown argument") })
        assertTrue(invalid.errors.any { it.contains("malformed") || it.contains("invalid") || it.contains("missing") })

        val reportFile = EncoderPerfRunner.recordRejected(context, invalid, directory)
        val text = reportFile.readText()
        assertTrue(text.contains("\"status\": \"FAILED\""))
        assertTrue(text.contains("ARGUMENT_REJECTION"))
        assertTrue("the rejection must not leak a filesystem path", !text.contains(directory.absolutePath))
        assertTrue("the rejection must not leak the fixture path", !text.contains("absent.mp4"))
    }

    @Test
    fun pipelineDetailsSelectsTheProfilerModeStrictly() {
        val prefix = EncoderPerfArguments.PREFIX
        val off = parsedRequest()
        assertFalse("profile defaults to false (OFF)", off.profile)
        assertFalse("pipeline details default to the profile flag", off.pipelineDetails)
        val explicitOff = parsedRequest("${prefix}profile" to "false", "${prefix}pipeline_details" to "false")
        assertFalse(explicitOff.profile)
        assertFalse(explicitOff.pipelineDetails)
        val legacy = parsedRequest("${prefix}profile" to "true", "${prefix}pipeline_details" to "false")
        assertTrue("legacy keeps the profiler enabled", legacy.profile)
        assertFalse("legacy must not collect pipeline details", legacy.pipelineDetails)
        val detailed = parsedRequest("${prefix}profile" to "true", "${prefix}pipeline_details" to "true")
        assertTrue(detailed.profile)
        assertTrue(detailed.pipelineDetails)
        val backwardCompatible = parsedRequest("${prefix}profile" to "true")
        assertTrue("profile=true without pipeline_details keeps the legacy+details behavior", backwardCompatible.pipelineDetails)
        val profileKindLegacy = parsedRequest(
            "${prefix}run_kind" to "PROFILE",
            "${prefix}profile" to "true",
            "${prefix}pipeline_details" to "false",
        )
        assertFalse("run kind PROFILE accepts the legacy profiler configuration", profileKindLegacy.pipelineDetails)

        val blank = EncoderPerfArguments.parse(validArgumentBundle("${prefix}pipeline_details" to ""))
        assertTrue("blank pipeline_details must be rejected, got $blank", blank is EncoderPerfParse.Invalid)
        assertTrue((blank as EncoderPerfParse.Invalid).errors.any { it.contains("blank") })
        val malformed = EncoderPerfArguments.parse(validArgumentBundle("${prefix}pipeline_details" to "yes"))
        assertTrue("malformed pipeline_details must be rejected, got $malformed", malformed is EncoderPerfParse.Invalid)
        assertTrue((malformed as EncoderPerfParse.Invalid).errors.any { it.contains("malformed boolean") })
    }

    @Test
    fun pipelineDetailsTrueWithoutProfileIsRejectedDurably() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = temporaryDirectory(context, "details-rejection")
        val prefix = EncoderPerfArguments.PREFIX
        val parsed = EncoderPerfArguments.parse(
            validArgumentBundle("${prefix}profile" to "false", "${prefix}pipeline_details" to "true"),
        )
        assertTrue("profile=false with details=true must be rejected, not clamped: $parsed", parsed is EncoderPerfParse.Invalid)
        val invalid = parsed as EncoderPerfParse.Invalid
        assertTrue(invalid.errors.any { it.contains("pipeline_details") })
        val reportFile = EncoderPerfRunner.recordRejected(context, invalid, directory)
        val text = reportFile.readText()
        assertTrue(text.contains("\"status\": \"FAILED\""))
        assertTrue(text.contains("ARGUMENT_REJECTION"))
        assertTrue(text.contains("pipeline_details"))
    }

    @Test
    fun explicitlyProvidedMissingFixtureWritesFailedReport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = temporaryDirectory(context, "missing")
        val request = syntheticStyleRequest(File(directory, "absent.mp4").absolutePath, runId = "s0b2b-missing")
        val failure = runCatching { runBlocking { EncoderPerfRunner.execute(context, request, directory) } }.exceptionOrNull()
        assertTrue("an explicitly provided missing fixture must fail", failure is AssertionError)
        val reportFile = File(directory, "${EncoderPerformanceSupport.REPORT_FILE_PREFIX}${request.runId}.json")
        assertTrue(reportFile.isFile)
        val text = reportFile.readText()
        assertTrue(text.contains("\"status\": \"FAILED\""))
        assertTrue(text.contains("FIXTURE_NOT_FOUND"))
        assertTrue(!text.contains(directory.absolutePath))
    }

    @Test
    fun reportCollisionNeverOverwritesThePreviousRun() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = temporaryDirectory(context, "collision")
        val request = syntheticStyleRequest(File(directory, "absent.mp4").absolutePath, runId = "s0b2b-collision")
        val firstFailure = runCatching { runBlocking { EncoderPerfRunner.execute(context, request, directory) } }.exceptionOrNull()
        assertTrue("first invalid fixture must fail and leave a report", firstFailure is AssertionError)
        val firstFile = File(directory, "${EncoderPerformanceSupport.REPORT_FILE_PREFIX}${request.runId}.json")
        val before = firstFile.readText()

        val failure = runCatching { runBlocking { EncoderPerfRunner.execute(context, request, directory) } }.exceptionOrNull()
        assertTrue("a collision must be rejected with an assertion", failure is AssertionError)
        assertEquals("the existing report must stay byte-identical", before, firstFile.readText())
        val diagnostic = directory.listFiles().orEmpty().filter { it.name != firstFile.name }
        assertTrue("a sanitized diagnostic report must document the rejection", diagnostic.isNotEmpty())
        assertTrue(diagnostic.first().readText().contains("REPORT_COLLISION"))
    }

    @Test
    fun syntheticAssetRunnerSmokeWhenOptedIn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "no encoder_perf_synthetic=true requested",
            arguments.getString(EncoderPerfArguments.SYNTHETIC_KEY) == "true",
        )
        val asset = instrumentation.context.assets.open(SYNTHETIC_FIXTURE_ASSET)
        val directory = temporaryDirectory(context, "synthetic")
        val video = File(directory, "synthetic_$SYNTHETIC_FIXTURE_ASSET")
        asset.use { input -> video.outputStream().use { output -> input.copyTo(output) } }
        val index = VideoFrameIndexer.indexFromPath(video.absolutePath)
        val presentation = VideoPresentationGeometry.fromEncoded(index.width, index.height, index.rotationDegrees)
        val modes = listOf(
            SyntheticProfilerMode("OFF", profile = false, pipelineDetails = false, sampleMemory = false),
            SyntheticProfilerMode("LEGACY", profile = true, pipelineDetails = false, sampleMemory = false),
            SyntheticProfilerMode("DETAILED", profile = true, pipelineDetails = true, sampleMemory = true),
        )
        val reports = modes.associateWith { mode ->
            val request = syntheticStyleRequest(
                fixturePath = video.absolutePath,
                runId = "synthetic-${mode.label.lowercase()}-${System.nanoTime()}",
                profile = mode.profile,
                pipelineDetails = mode.pipelineDetails,
                sampleMemory = mode.sampleMemory,
                declaredSha256 = EncoderValidation.sha256(video),
                expectedHostFrames = index.frameCount,
                expectedPresentationWidth = presentation.width,
                expectedPresentationHeight = presentation.height,
                calibrationAX = presentation.width * 0.2,
                calibrationAY = presentation.height * 0.2,
                calibrationBX = presentation.width * 0.8,
                calibrationBY = presentation.height * 0.2,
            )
            val result = runBlocking { EncoderPerfRunner.execute(context, request, directory) }
            assertEquals("${mode.label} must reach PASSED", EncoderPerformanceSupport.STATUS_PASSED, result.status)
            val text = result.reportFile.readText()
            assertTrue(text.contains(EncoderPerformanceSupport.OUTPUT_SNAPSHOT_SCHEMA))
            assertTrue(text.contains("\"analysis\""))
            Json.parseToJsonElement(text) as JsonObject
        }
        reports.forEach { (mode, report) ->
            assertEquals(JsonPrimitive(mode.profile), elementAt(report, "provenance", "profile"))
            assertEquals(JsonPrimitive(mode.pipelineDetails), elementAt(report, "provenance", "pipeline_details"))
            assertEquals(JsonPrimitive(mode.label), elementAt(report, "provenance", "profiler_mode"))
            assertEquals(JsonPrimitive(mode.label), elementAt(report, "measurements", "producer_stage_timings", "mode"))
            assertEquals(JsonPrimitive(mode.label), elementAt(report, "measurements", "producer_counters", "mode"))
        }

        // OFF and LEGACY never convert an unavailable counter into zero. The counting wrapper stays
        // the independent denominator, so a PASSED legacy run proves wrapper-only coverage is valid.
        val off = reports.getValue(modes[0])
        assertEquals(JsonPrimitive("NOT_COLLECTED"), elementAt(off, "measurements", "producer_stage_timings", "status"))
        assertEquals(JsonPrimitive("NOT_COLLECTED"), elementAt(off, "measurements", "producer_counters", "status"))
        val legacy = reports.getValue(modes[1])
        assertEquals(JsonPrimitive("REPORTED"), elementAt(legacy, "measurements", "producer_stage_timings", "status"))
        assertEquals(JsonPrimitive("NOT_COLLECTED"), elementAt(legacy, "measurements", "producer_stage_timings", "detail_stages"))
        assertEquals(JsonPrimitive("NOT_COLLECTED"), elementAt(legacy, "measurements", "producer_counters", "status"))
        assertTrue(
            "legacy must explain why producer counters are unavailable",
            textAt(legacy, "measurements", "producer_counters", "reason").contains("pipeline_details"),
        )
        val legacyStages = elementAt(legacy, "measurements", "producer_stage_timings", "stages") as JsonObject
        assertTrue("legacy stage samples must still be collected", legacyStages.containsKey("decode"))
        assertEquals(
            "legacy coverage must come from the independent counting wrapper",
            index.frameCount.toLong(),
            textAt(legacy, "measurements", "denominators", "wrapper_consumed_total").toLong(),
        )
        val detailed = reports.getValue(modes[2])
        assertEquals(JsonPrimitive("REPORTED"), elementAt(detailed, "measurements", "producer_stage_timings", "status"))
        assertEquals(JsonPrimitive("COLLECTED"), elementAt(detailed, "measurements", "producer_stage_timings", "detail_stages"))
        assertEquals(JsonPrimitive("REPORTED"), elementAt(detailed, "measurements", "producer_counters", "status"))
        assertTrue(
            "detailed counters must be reported with values",
            (elementAt(detailed, "measurements", "producer_counters", "values") as JsonObject).isNotEmpty(),
        )

        val outputs = modes.map { reports.getValue(it).getValue("output") as JsonObject }
        for (first in outputs.indices) {
            for (second in first + 1 until outputs.size) {
                assertEquals(
                    "every profiler mode must preserve every output bit",
                    null,
                    EncoderPerformanceSupport.firstDifference(outputs[first], outputs[second]),
                )
            }
        }
    }

    @Test
    fun invalidVideoAfterRunningWritesTerminalFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = temporaryDirectory(context, "invalid-video")
        val video = File(directory, "not-a-video.mp4").apply { writeText("not a valid MP4") }
        val request = syntheticStyleRequest(video.absolutePath, "invalid-video", declaredSha256 = EncoderValidation.sha256(video))
        val failure = runCatching { runBlocking { EncoderPerfRunner.execute(context, request, directory) } }.exceptionOrNull()
        assertTrue("index failure must propagate", failure != null)
        val file = File(directory, "${EncoderPerformanceSupport.REPORT_FILE_PREFIX}${request.runId}.json")
        val report = Json.parseToJsonElement(file.readText()) as JsonObject
        assertEquals(JsonPrimitive(EncoderPerformanceSupport.STATUS_FAILED), report.getValue("status"))
        assertTrue("failure artifact must not leak fixture path", !file.readText().contains(video.absolutePath))
    }

    @Test
    fun realFixtureRunWhenRequested() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        if (!EncoderPerfArguments.isOptIn(arguments)) {
            EncoderPerfRunner.recordNotRequested(instrumentation.targetContext)
            assumeTrue("no encoder_perf_* arguments: real runner NOT_RUN", false)
        }
        if (arguments.containsKey(EncoderPerfArguments.MMR_PREFETCH_KEY)) {
            val rejected = EncoderPerfParse.Invalid(
                errors = listOf("experiment flag '${EncoderPerfArguments.MMR_PREFETCH_KEY}' requires the experiment runner"),
                requestedRunId = null,
            )
            EncoderPerfRunner.recordRejected(instrumentation.targetContext, rejected)
            throw AssertionError("experiment flag '${EncoderPerfArguments.MMR_PREFETCH_KEY}' requires the experiment runner")
        }
        val context = instrumentation.targetContext
        val result: EncoderPerfRunResult = runBlocking {
            when (val parsed = EncoderPerfArguments.parse(arguments)) {
                EncoderPerfParse.NotRequested -> error("unreachable: opt-in arguments were present")
                is EncoderPerfParse.Invalid -> {
                    EncoderPerfRunner.recordRejected(context, parsed)
                    throw AssertionError("rejected encoder_perf_ arguments: ${parsed.errors.joinToString("; ")}")
                }
                is EncoderPerfParse.Requested -> EncoderPerfRunner.execute(context, parsed.request)
            }
        }
        assertEquals(EncoderPerformanceSupport.STATUS_PASSED, result.status)
        assertTrue(result.reportFile.isFile)
        assertTrue(result.reportFile.useLines { lines ->
            lines.any { it.contains(EncoderPerformanceSupport.OUTPUT_SNAPSHOT_SCHEMA) }
        })
    }

    private fun syntheticStyleRequest(
        fixturePath: String,
        runId: String,
        profile: Boolean = false,
        pipelineDetails: Boolean = profile,
        sampleMemory: Boolean = false,
        declaredSha256: String = "0".repeat(64),
        expectedHostFrames: Int = 1,
        expectedPresentationWidth: Int = 1,
        expectedPresentationHeight: Int = 1,
        calibrationAX: Double = 0.0,
        calibrationAY: Double = 0.0,
        calibrationBX: Double = 80.0,
        calibrationBY: Double = 0.0,
        mmrPrefetch: String = EncoderPerfArguments.MMR_PREFETCH_OFF,
    ): EncoderPerfRequest = EncoderPerfRequest(
        runId = runId,
        runKind = "MEASURED",
        fixturePath = fixturePath,
        fixtureLabel = File(fixturePath).name.take(64).ifBlank { "fixture" },
        declaredSha256 = declaredSha256,
        expectedHostFrames = expectedHostFrames,
        seedX = SYNTHETIC_SEED_X,
        seedY = SYNTHETIC_SEED_Y,
        calibrationAX = calibrationAX,
        calibrationAY = calibrationAY,
        calibrationBX = calibrationBX,
        calibrationBY = calibrationBY,
        referenceM = 0.4,
        calibrationSource = "SYNTHETIC_TEST_NOT_PHYSICAL",
        exercise = EncoderExercise.BENCH_PRESS.name,
        loadKg = 0.0,
        loadSource = "BENCHMARK_PLACEHOLDER_0KG_UNKNOWN",
        expectedPresentationWidth = expectedPresentationWidth,
        expectedPresentationHeight = expectedPresentationHeight,
        profile = profile,
        pipelineDetails = pipelineDetails,
        sampleMemory = sampleMemory,
        sampleIntervalMs = EncoderPerfSampler.DEFAULT_INTERVAL_MS,
        timingPolicy = EncoderPerfArguments.TIMING_AUTOMATIC_IMPORTED,
        mmrPrefetch = mmrPrefetch,
        purpose = "SYNTHETIC_SMOKE",
        classification = "SYNTHETIC_TEST_NOT_PHYSICAL",
    )

    private fun bundleOf(vararg pairs: Pair<String, String>): Bundle = Bundle().apply {
        pairs.forEach { (key, value) -> putString(key, value) }
    }

    private fun temporaryDirectory(context: Context, suffix: String): File =
        File(context.cacheDir, "encoder-perf-$suffix-${System.nanoTime()}").apply {
            require(mkdirs() || isDirectory) { "cannot create temporary directory" }
        }

    private fun validArgumentBundle(vararg overrides: Pair<String, String>): Bundle {
        val prefix = EncoderPerfArguments.PREFIX
        val values = linkedMapOf(
            "${prefix}run_id" to "parse-fixture",
            "${prefix}fixture" to "/tmp/parse-fixture.mp4",
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
        return bundleOf(*values.map { it.key to it.value }.toTypedArray())
    }

    private fun parsedRequest(vararg overrides: Pair<String, String>): EncoderPerfRequest {
        val parsed = EncoderPerfArguments.parse(validArgumentBundle(*overrides))
        assertTrue("expected a valid request, got $parsed", parsed is EncoderPerfParse.Requested)
        return (parsed as EncoderPerfParse.Requested).request
    }

    private fun elementAt(report: JsonObject, vararg path: String): JsonElement {
        var element: JsonElement = report
        path.forEach { key -> element = (element as JsonObject).getValue(key) }
        return element
    }

    private fun textAt(report: JsonObject, vararg path: String): String =
        (elementAt(report, *path) as JsonPrimitive).content

    private data class SyntheticProfilerMode(
        val label: String,
        val profile: Boolean,
        val pipelineDetails: Boolean,
        val sampleMemory: Boolean,
    )

    private companion object {
        const val SYNTHETIC_FIXTURE_ASSET = "tracking_translation.mp4"
        const val SYNTHETIC_SEED_X = 60.0
        const val SYNTHETIC_SEED_Y = 120.0
    }
}

/** One parsed `encoder_perf_*` invocation. Parsing always sets every field explicitly; `pipelineDetails` only has a `= profile` default for direct programmatic callers. */
internal data class EncoderPerfRequest(
    val runId: String,
    val runKind: String,
    val fixturePath: String,
    val fixtureLabel: String,
    val declaredSha256: String,
    val expectedHostFrames: Int,
    val seedX: Double,
    val seedY: Double,
    val calibrationAX: Double,
    val calibrationAY: Double,
    val calibrationBX: Double,
    val calibrationBY: Double,
    val referenceM: Double,
    val calibrationSource: String,
    val exercise: String,
    val loadKg: Double,
    val loadSource: String,
    val expectedPresentationWidth: Int,
    val expectedPresentationHeight: Int,
    val profile: Boolean,
    /** Detail stage counters; defaults to [profile] so callers that omit it keep the legacy+details behavior. */
    val pipelineDetails: Boolean = profile,
    val sampleMemory: Boolean,
    val sampleIntervalMs: Long,
    val timingPolicy: String,
    val purpose: String,
    val classification: String,
    /** Test-only MMR prefetch arm: "OFF" (default usual route) or "ON" (owned single-thread prefetch). */
    val mmrPrefetch: String = "OFF",
    val head: String? = null,
    val apkSha256: String? = null,
    val sourceFingerprint: String? = null,
    val sourceDirty: String? = null,
    val buildType: String? = null,
)

internal sealed class EncoderPerfParse {
    object NotRequested : EncoderPerfParse()

    /** Rejected arguments. [requestedRunId] is only the already-safe run id, never a raw value. */
    data class Invalid(val errors: List<String>, val requestedRunId: String?) : EncoderPerfParse()

    data class Requested(val request: EncoderPerfRequest) : EncoderPerfParse()
}

internal object EncoderPerfArguments {
    const val PREFIX = "encoder_perf_"
    const val SYNTHETIC_KEY = "${PREFIX}synthetic"
    const val TIMING_AUTOMATIC_IMPORTED = "AUTOMATIC_IMPORTED"
    const val TIMING_EXPLICIT_REAL_TIME = "EXPLICIT_REAL_TIME"
    const val PLACEHOLDER_LOAD_SOURCE = "BENCHMARK_PLACEHOLDER_0KG_UNKNOWN"
    const val MMR_PREFETCH_KEY = "${PREFIX}mmr_prefetch"
    const val MMR_PREFETCH_ON = "ON"
    const val MMR_PREFETCH_OFF = "OFF"

    private val runKinds = linkedSetOf("COLD_FIRST", "WARMUP", "MEASURED", "PROFILE")
    private val timingPolicies = linkedSetOf(TIMING_AUTOMATIC_IMPORTED, TIMING_EXPLICIT_REAL_TIME)
    private val safeLabel = Regex("[A-Za-z0-9_.:-]{1,64}")
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")
    private val knownKeys: Set<String> = linkedSetOf(
        "${PREFIX}fixture",
        "${PREFIX}sha256",
        "${PREFIX}run_id",
        "${PREFIX}run_kind",
        "${PREFIX}head",
        "${PREFIX}apk_sha256",
        "${PREFIX}source_fingerprint",
        "${PREFIX}source_dirty",
        "${PREFIX}build_type",
        "${PREFIX}expected_frames",
        "${PREFIX}seed_x",
        "${PREFIX}seed_y",
        "${PREFIX}cal_a_x",
        "${PREFIX}cal_a_y",
        "${PREFIX}cal_b_x",
        "${PREFIX}cal_b_y",
        "${PREFIX}reference_m",
        "${PREFIX}calibration_source",
        "${PREFIX}exercise",
        "${PREFIX}load_kg",
        "${PREFIX}load_source",
        "${PREFIX}expected_presentation_width",
        "${PREFIX}expected_presentation_height",
        "${PREFIX}profile",
        "${PREFIX}pipeline_details",
        "${PREFIX}sample_memory",
        "${PREFIX}sample_interval_ms",
        "${PREFIX}timing_policy",
        "${PREFIX}purpose",
        "${PREFIX}classification",
        MMR_PREFETCH_KEY,
        SYNTHETIC_KEY,
    )

    /** The real runner is requested by any `encoder_perf_*` argument other than `synthetic`. */
    fun isOptIn(arguments: Bundle): Boolean =
        arguments.keySet().any { it.startsWith(PREFIX) && it != SYNTHETIC_KEY }

    fun parse(arguments: Bundle): EncoderPerfParse {
        val provided = arguments.keySet().filter { it.startsWith(PREFIX) }
        if (provided.none { it != SYNTHETIC_KEY }) return EncoderPerfParse.NotRequested
        val errors = mutableListOf<String>()

        provided.filterNot { it in knownKeys }.forEach { errors += "unknown argument '$it'" }

        fun text(key: String): String? =
            if (arguments.containsKey(key)) arguments.getString(key)?.takeIf { it.isNotBlank() } else null

        fun doubleValue(key: String, required: Boolean): Double? {
            val raw = text(key)
            if (raw == null) {
                if (required) errors += "missing or blank required argument '$key'"
                return null
            }
            val parsed = raw.trim().toDoubleOrNull()
            if (parsed == null || !parsed.isFinite()) {
                errors += "malformed number for '$key'"
                return null
            }
            return parsed
        }

        fun intValue(key: String, required: Boolean, minimum: Int): Int? {
            val raw = text(key)
            if (raw == null) {
                if (required) errors += "missing or blank required argument '$key'"
                return null
            }
            val parsed = raw.trim().toIntOrNull()
            if (parsed == null || parsed < minimum) {
                errors += "malformed integer for '$key' (minimum $minimum)"
                return null
            }
            return parsed
        }

        fun booleanValue(key: String, required: Boolean): Boolean? = when (val raw = text(key)) {
            null -> {
                if (required) errors += "missing required boolean '$key'"
                null
            }
            "true" -> true
            "false" -> false
            else -> {
                errors += "malformed boolean for '$key' (expected true or false, was '$raw')"
                null
            }
        }

        fun labelValue(key: String, required: Boolean): String? {
            val raw = text(key)
            if (raw == null) {
                if (required) errors += "missing required label '$key'"
                return null
            }
            if (!safeLabel.matches(raw)) {
                errors += "unsafe label for '$key' (expected ${safeLabel.pattern})"
                return null
            }
            return raw
        }

        val runId = text("${PREFIX}run_id")
        if (runId == null) {
            errors += "missing required argument '${PREFIX}run_id'"
        } else if (!EncoderPerformanceSupport.isSafeRunId(runId)) {
            errors += "unsafe run id at '${PREFIX}run_id'"
        }

        val fixturePath = text("${PREFIX}fixture")
        if (fixturePath == null) errors += "missing required argument '${PREFIX}fixture'"

        val declaredSha256 = text("${PREFIX}sha256")
        if (declaredSha256 == null || !sha256Pattern.matches(declaredSha256)) {
            errors += "invalid SHA-256 at '${PREFIX}sha256'"
        }

        val runKind = text("${PREFIX}run_kind")
        if (runKind == null || runKind !in runKinds) {
            errors += "invalid run kind at '${PREFIX}run_kind' (expected $runKinds)"
        }

        val expectedFrames = intValue("${PREFIX}expected_frames", required = true, minimum = 1)
        val seedX = doubleValue("${PREFIX}seed_x", required = true)
        val seedY = doubleValue("${PREFIX}seed_y", required = true)
        val calAX = doubleValue("${PREFIX}cal_a_x", required = true)
        val calAY = doubleValue("${PREFIX}cal_a_y", required = true)
        val calBX = doubleValue("${PREFIX}cal_b_x", required = true)
        val calBY = doubleValue("${PREFIX}cal_b_y", required = true)
        val referenceM = doubleValue("${PREFIX}reference_m", required = true)
        if (referenceM != null && referenceM <= 0.0) errors += "'${PREFIX}reference_m' must be positive"

        val calibrationSource = labelValue("${PREFIX}calibration_source", required = true)

        val exercise = text("${PREFIX}exercise")
        if (exercise == null || EncoderExercise.entries.none { it.name == exercise }) {
            errors += "invalid exercise at '${PREFIX}exercise' (expected one of ${EncoderExercise.entries.map { it.name }})"
        }

        val loadSource = labelValue("${PREFIX}load_source", required = false)
        val loadKgRaw = doubleValue("${PREFIX}load_kg", required = false)
        if (loadKgRaw != null && loadKgRaw < 0.0) errors += "'${PREFIX}load_kg' must be >= 0"
        if (loadKgRaw == null && loadSource != PLACEHOLDER_LOAD_SOURCE) {
            errors += "absent '${PREFIX}load_kg' needs '${PREFIX}load_source'=$PLACEHOLDER_LOAD_SOURCE" +
                " to declare the benchmark placeholder load"
        }

        val presentationWidth = intValue("${PREFIX}expected_presentation_width", required = true, minimum = 1)
        val presentationHeight = intValue("${PREFIX}expected_presentation_height", required = true, minimum = 1)

        val profileKey = "${PREFIX}profile"
        val profile = booleanValue(profileKey, required = false) ?: false
        if (arguments.containsKey(profileKey) && text(profileKey) == null) {
            errors += "blank '$profileKey'"
        }
        val detailsKey = "${PREFIX}pipeline_details"
        val detailsProvided = arguments.containsKey(detailsKey)
        val details = if (detailsProvided) booleanValue(detailsKey, required = false) else null
        if (detailsProvided && text(detailsKey) == null) {
            errors += "blank '$detailsKey'"
        }
        if (details == true && !profile) {
            errors += "invalid combination: '$detailsKey=true' requires '$profileKey=true' (OFF cannot collect pipeline details)"
        }
        // Omitted details follow the profile flag, so existing profile=true callers keep legacy+details.
        val pipelineDetails = details ?: profile
        if (runKind == "PROFILE" && !profile) {
            errors += "run kind PROFILE requires '$profileKey=true'"
        }
        val sampleMemory = booleanValue("${PREFIX}sample_memory", required = false) ?: false
        val intervalProvided = arguments.containsKey("${PREFIX}sample_interval_ms")
        var sampleIntervalMs = EncoderPerfSampler.DEFAULT_INTERVAL_MS
        if (intervalProvided) {
            val interval = intValue("${PREFIX}sample_interval_ms", required = false, minimum = EncoderPerfSampler.MINIMUM_INTERVAL_MS.toInt())
            if (interval != null) sampleIntervalMs = interval.toLong()
            if (!sampleMemory) errors += "'${PREFIX}sample_interval_ms' requires '${PREFIX}sample_memory=true'"
        }

        val timingPolicy = text("${PREFIX}timing_policy") ?: TIMING_AUTOMATIC_IMPORTED
        if (timingPolicy !in timingPolicies) {
            errors += "invalid timing policy at '${PREFIX}timing_policy' (expected $timingPolicies)"
        }
        val mmrPrefetchRaw = text(MMR_PREFETCH_KEY)
        val mmrPrefetch = when (mmrPrefetchRaw) {
            null -> MMR_PREFETCH_OFF
            MMR_PREFETCH_ON, MMR_PREFETCH_OFF -> mmrPrefetchRaw
            else -> {
                errors += "invalid value at '$MMR_PREFETCH_KEY' (expected ON or OFF)"
                MMR_PREFETCH_OFF
            }
        }
        if (arguments.containsKey(MMR_PREFETCH_KEY) && text(MMR_PREFETCH_KEY) == null) {
            errors += "blank '$MMR_PREFETCH_KEY'"
        }

        val apkSha256 = text("${PREFIX}apk_sha256")
        if (apkSha256 != null && !sha256Pattern.matches(apkSha256)) {
            errors += "invalid SHA-256 at '${PREFIX}apk_sha256'"
        }
        val sourceDirty = text("${PREFIX}source_dirty")
        if (sourceDirty != null && sourceDirty !in setOf("true", "false", "unknown")) {
            errors += "invalid value at '${PREFIX}source_dirty' (expected true, false or unknown)"
        }
        val synthetic = text(SYNTHETIC_KEY)
        if (synthetic != null && synthetic != "true" && synthetic != "false") {
            errors += "malformed boolean for '$SYNTHETIC_KEY' (expected true or false, was '$synthetic')"
        }

        if (errors.isNotEmpty()) {
            return EncoderPerfParse.Invalid(
                errors = errors.distinct(),
                requestedRunId = runId?.takeIf { EncoderPerformanceSupport.isSafeRunId(it) },
            )
        }

        return EncoderPerfParse.Requested(
            EncoderPerfRequest(
                runId = requireNotNull(runId),
                runKind = requireNotNull(runKind),
                fixturePath = requireNotNull(fixturePath),
                fixtureLabel = File(requireNotNull(fixturePath)).name.takeIf { safeLabel.matches(it) } ?: "fixture",
                declaredSha256 = requireNotNull(declaredSha256).lowercase(),
                expectedHostFrames = requireNotNull(expectedFrames),
                seedX = requireNotNull(seedX),
                seedY = requireNotNull(seedY),
                calibrationAX = requireNotNull(calAX),
                calibrationAY = requireNotNull(calAY),
                calibrationBX = requireNotNull(calBX),
                calibrationBY = requireNotNull(calBY),
                referenceM = requireNotNull(referenceM),
                calibrationSource = requireNotNull(calibrationSource),
                exercise = requireNotNull(exercise),
                loadKg = loadKgRaw ?: 0.0,
                loadSource = loadSource ?: PLACEHOLDER_LOAD_SOURCE,
                expectedPresentationWidth = requireNotNull(presentationWidth),
                expectedPresentationHeight = requireNotNull(presentationHeight),
                profile = profile,
                pipelineDetails = pipelineDetails,
                sampleMemory = sampleMemory,
                sampleIntervalMs = sampleIntervalMs,
                timingPolicy = timingPolicy,
                mmrPrefetch = mmrPrefetch,
                purpose = text("${PREFIX}purpose") ?: "NOT_DECLARED",
                classification = text("${PREFIX}classification") ?: "NOT_DECLARED",
                head = text("${PREFIX}head"),
                apkSha256 = apkSha256?.lowercase(),
                sourceFingerprint = text("${PREFIX}source_fingerprint"),
                sourceDirty = sourceDirty,
                buildType = text("${PREFIX}build_type"),
            ),
        )
    }
}

internal data class EncoderPerfRunResult(
    val status: String,
    val reportFile: File,
    val reasons: List<String>,
)

internal object EncoderPerfRunner {
    const val REPORT_DIRECTORY = "encoder-performance"
    private const val SEED_FRAME_INDEX = 0
    private const val REFUSED_STATUS_ERROR = "run did not reach PASSED"
    private const val MODE_OFF = "OFF"
    private const val MODE_LEGACY = "LEGACY"
    private const val MODE_DETAILED = "DETAILED"

    fun reportDirectory(context: Context): File = File(context.filesDir, REPORT_DIRECTORY)

    fun generatedDiagnosticRunId(): String = "diagnostic-${System.nanoTime()}"

    fun recordNotRequested(context: Context) {
        val id = "not-requested-${System.nanoTime()}"
        val file = EncoderPerformanceSupport.distinctReportFile(reportDirectory(context), id)
        EncoderPerformanceSupport.writeReport(file, EncoderPerformanceSupport.buildRunReport(
            id, EncoderPerformanceSupport.STATUS_NOT_RUN,
            obj("reason" to JsonPrimitive("OPT_IN_ARGUMENTS_ABSENT")),
            obj("timer" to JsonPrimitive("NOT_STARTED")),
        ))
    }

    /** Durable FAILED artifact for rejected arguments; never touches an existing run report. */
    fun recordRejected(context: Context, invalid: EncoderPerfParse.Invalid, directory: File = reportDirectory(context)): File {
        val runId = invalid.requestedRunId ?: generatedDiagnosticRunId()
        val file = EncoderPerformanceSupport.distinctReportFile(directory, runId)
        EncoderPerformanceSupport.writeReport(
            file,
            EncoderPerformanceSupport.buildRunReport(
                runId = runId,
                status = EncoderPerformanceSupport.STATUS_FAILED,
                provenance = obj(
                    "status" to JsonPrimitive("ARGUMENT_REJECTION"),
                    "requested_run_id_accepted" to JsonPrimitive(invalid.requestedRunId != null),
                    "environment" to environment(context, buildTypeArg = null),
                ),
                measurements = obj("timer" to JsonPrimitive("NOT_STARTED")),
                output = null,
                error = "ARGUMENT_REJECTION: " + invalid.errors.joinToString(" | "),
            ),
        )
        return file
    }

    suspend fun execute(
        context: Context,
        request: EncoderPerfRequest,
        directory: File = reportDirectory(context),
    ): EncoderPerfRunResult {
        val reportFile = openReportFile(context, request, directory)
        val provenance = provenance(context, request)
        var terminalStatus: String? = null

        fun writeTerminal(status: String, measurements: JsonObject, output: JsonObject?, error: String?) {
            EncoderPerformanceSupport.writeReport(
                reportFile,
                EncoderPerformanceSupport.buildRunReport(
                    runId = request.runId,
                    status = status,
                    provenance = provenance,
                    measurements = measurements,
                    output = output,
                    error = error,
                ),
            )
            if (status in EncoderPerformanceSupport.TERMINAL_STATUSES) terminalStatus = status
        }

        writeTerminal(EncoderPerformanceSupport.STATUS_RUNNING, obj("timer" to JsonPrimitive("RUNNING")), null, null)

        val fixtureSource = resolveFixture(context, request.fixturePath)
        if (fixtureSource == null || !fixtureSource.isFile) {
            val reason = "FIXTURE_NOT_FOUND label=${request.fixtureLabel}"
            writeTerminal(
                EncoderPerformanceSupport.STATUS_FAILED,
                obj(
                    "timer" to JsonPrimitive("NOT_STARTED"),
                    "reason" to JsonPrimitive(reason),
                    "note" to JsonPrimitive("an explicitly requested missing fixture is a failure, never a skip"),
                ),
                null,
                reason,
            )
            throw AssertionError("explicitly requested fixture not found; FAILED report: ${reportFile.name}")
        }

        val sampler = try {
            EncoderPerfSampler(context, request.sampleMemory, request.sampleIntervalMs)
        } catch (error: Exception) {
            writeTerminal(EncoderPerformanceSupport.STATUS_FAILED, obj("timer" to JsonPrimitive("NOT_STARTED")), null,
                "INVALID_SAMPLER_CONFIGURATION type=${error.javaClass.simpleName}")
            throw error
        }
        var verified: EncoderValidation.VerifiedFixtureCache? = null
        try {
            val provisionStartNs = System.nanoTime()
            val observedSha256 = EncoderValidation.sha256(fixtureSource)
            if (observedSha256 != request.declaredSha256) {
                val reason = "HASH_MISMATCH label=${request.fixtureLabel} declared=${request.declaredSha256} observed=$observedSha256"
                writeTerminal(EncoderPerformanceSupport.STATUS_FAILED, obj("timer" to JsonPrimitive("FIXTURE_VERIFICATION")), null, reason)
                throw AssertionError("fixture hash mismatch; declared and observed hashes are in the FAILED report")
            }
            val copy = try {
                EncoderValidation.copyVerifiedFixture(
                    source = fixtureSource,
                    cacheDirectory = File(context.cacheDir, "trajectory_exports"),
                    expectedSha256 = request.declaredSha256,
                    identity = request.fixtureLabel,
                )
            } catch (error: Exception) {
                val reason = "FIXTURE_COPY_FAILED label=${request.fixtureLabel} type=${error.javaClass.simpleName}"
                writeTerminal(EncoderPerformanceSupport.STATUS_FAILED, obj("timer" to JsonPrimitive("FIXTURE_VERIFICATION")), null, reason)
                throw AssertionError("could not provision a verified fixture copy: ${error.javaClass.simpleName}")
            }
            verified = copy
            val provisionNs = System.nanoTime() - provisionStartNs

            val pipeline = runPipeline(context, request, copy.file, provisionNs, sampler)
            writeTerminal(pipeline.status, pipeline.measurements, pipeline.output, pipeline.error)
            if (pipeline.status != EncoderPerformanceSupport.STATUS_PASSED) {
                throw AssertionError(
                    "$REFUSED_STATUS_ERROR (${pipeline.status}): ${pipeline.reasons.joinToString("; ")}",
                )
            }
            return EncoderPerfRunResult(pipeline.status, reportFile, pipeline.reasons)
        } catch (cancellation: CancellationException) {
            if (terminalStatus == null) {
                writeTerminal(
                    EncoderPerformanceSupport.STATUS_CANCELLED,
                    obj("timer" to JsonPrimitive("CANCELLED"), "note" to JsonPrimitive("coroutine cancellation stopped the measured window")),
                    null,
                    "CANCELLED",
                )
            }
            throw cancellation
        } catch (error: Throwable) {
            if (terminalStatus == null) {
                writeTerminal(
                    EncoderPerformanceSupport.STATUS_FAILED,
                    obj("timer" to JsonPrimitive("FAILED"), "note" to JsonPrimitive("pipeline threw before a terminal status was written")),
                    null,
                    "PIPELINE_ERROR type=${error.javaClass.simpleName}",
                )
            }
            throw error
        } finally {
            sampler.stop()
            EncoderValidation.cleanupVerifiedFixture(verified)
        }
    }

    private fun openReportFile(context: Context, request: EncoderPerfRequest, directory: File): File = try {
        EncoderPerformanceSupport.distinctReportFile(directory, request.runId)
    } catch (collision: IllegalArgumentException) {
        val diagnosticRunId = generatedDiagnosticRunId()
        val diagnosticFile = EncoderPerformanceSupport.distinctReportFile(directory, diagnosticRunId)
        EncoderPerformanceSupport.writeReport(
            diagnosticFile,
            EncoderPerformanceSupport.buildRunReport(
                runId = diagnosticRunId,
                status = EncoderPerformanceSupport.STATUS_FAILED,
                provenance = obj(
                    "status" to JsonPrimitive("REPORT_COLLISION"),
                    "environment" to environment(context, buildTypeArg = request.buildType),
                ),
                measurements = obj("timer" to JsonPrimitive("NOT_STARTED")),
                output = null,
                error = "REPORT_COLLISION: the requested run id already has a report; the existing artifact was not modified",
            ),
        )
        throw AssertionError("run id already has a report; existing artifact preserved (diagnostic: ${diagnosticFile.name})")
    }

    private fun resolveFixture(context: Context, path: String): File? = try {
        val candidate = File(path)
        if (candidate.isAbsolute) candidate else EncoderValidation.containedCanonical(context.filesDir, path)
    } catch (_: Exception) {
        null
    }

    private suspend fun runPipeline(
        context: Context,
        request: EncoderPerfRequest,
        videoFile: File,
        provisionNs: Long,
        sampler: EncoderPerfSampler,
    ): PipelineRun {
        val indexStartNs = System.nanoTime()
        val index = VideoFrameIndexer.indexFromPath(videoFile.absolutePath)
        val indexNs = System.nanoTime() - indexStartNs
        val presentation = VideoPresentationGeometry.fromEncoded(index.width, index.height, index.rotationDegrees)

        val rejections = mutableListOf<String>()
        if (index.frameCount != request.expectedHostFrames) {
            rejections += "EXPECTED_FRAMES_MISMATCH host=${request.expectedHostFrames} android=${index.frameCount}"
        }
        if (presentation.width != request.expectedPresentationWidth || presentation.height != request.expectedPresentationHeight) {
            rejections += "PRESENTATION_SIZE_MISMATCH expected=${request.expectedPresentationWidth}x${request.expectedPresentationHeight}" +
                " android=${presentation.width}x${presentation.height}"
        }
        if (request.seedX !in 0.0..(presentation.width - 1).toDouble() || request.seedY !in 0.0..(presentation.height - 1).toDouble()) {
            rejections += "SEED_OUT_OF_BOUNDS"
        }
        val calibration: MetricCalibration? = try {
            MetricScale.create(
                SEED_FRAME_INDEX,
                ImagePoint(request.calibrationAX, request.calibrationAY),
                ImagePoint(request.calibrationBX, request.calibrationBY),
                request.referenceM,
            )
        } catch (error: Exception) {
            rejections += "INVALID_CALIBRATION type=${error.javaClass.simpleName}"
            null
        }
        if (rejections.isNotEmpty()) {
            return PipelineRun(
                status = EncoderPerformanceSupport.STATUS_FAILED,
                measurements = obj(
                    "timer" to JsonPrimitive("NOT_STARTED"),
                    "index_create_ns" to JsonPrimitive(indexNs),
                    "rejections" to JsonArray(rejections.map { JsonPrimitive(it) }),
                ),
                output = null,
                error = "ARGUMENT_REJECTION: " + rejections.joinToString(" | "),
                reasons = rejections,
            )
        }

        val profiler = TrackingPerformanceProfiler(
            enabled = request.profile,
            collectPipelineDetails = request.pipelineDetails,
        )
        val sourceStartNs = System.nanoTime()
        var prefetchSource: MmrPrefetchFrameSource? = null
        val decodingSource: TrackingFrameSource = if (request.mmrPrefetch == EncoderPerfArguments.MMR_PREFETCH_ON) {
            val (executor, dispatcher) = MmrPrefetchFrameSource.newOwnedDispatcher("mmr-prefetch-owner")
            try {
                val inner = VideoTrackingFrameSource(
                    context = context,
                    uri = Uri.fromFile(videoFile),
                    index = index,
                    performanceProfiler = profiler,
                    decoderDispatcher = dispatcher,
                )
                MmrPrefetchFrameSource(
                    delegate = inner,
                    ownerDispatcher = dispatcher,
                    ownerExecutor = executor,
                ).also { prefetchSource = it }
            } catch (error: Throwable) {
                try {
                    dispatcher.close()
                } catch (_: Throwable) {
                }
                executor.shutdown()
                throw error
            }
        } else {
            VideoTrackingFrameSource(
                context = context,
                uri = Uri.fromFile(videoFile),
                index = index,
                performanceProfiler = profiler,
            )
        }
        val sourceNs = System.nanoTime() - sourceStartNs
        val counting = CountingFrameSource(decodingSource)
        val offline = OfflineVideoTracker(
            source = counting,
            tracker = PyramidalKltMotionTracker(performanceProfiler = profiler),
            performanceProfiler = profiler,
        )
        val decision = when (request.timingPolicy) {
            EncoderPerfArguments.TIMING_EXPLICIT_REAL_TIME -> VideoTimingDecision(VideoTimingMode.REAL_TIME)
            else -> PhysicalTimeline.automaticTimingDecision(VideoSource.IMPORTED, index)
        }
        val setup = EncoderSetup(EncoderExercise.valueOf(request.exercise), request.loadKg)

        var seedNs = 0L
        var trackNs = 0L
        var analyzeNs = 0L
        var closeNs = 0L
        var coreWallNs = 0L
        var tracking: List<TrackingFrameResult> = emptyList()
        var analysis: EncoderAnalysis? = null
        var analysisFailure: String? = null
        var pipelineFailure: Throwable? = null
        sampler.captureStart()
        try {
            val coreStartNs = System.nanoTime()
            sampler.start() // Launch overhead is part of the profiled core wall, not free.
            val seedStartNs = System.nanoTime()
            offline.initialize(SEED_FRAME_INDEX, ImagePoint(request.seedX, request.seedY))
            seedNs = System.nanoTime() - seedStartNs
            val trackStartNs = System.nanoTime()
            tracking = offline.trackRemaining { _, _, _ -> }
            trackNs = System.nanoTime() - trackStartNs
            val analyzeStartNs = System.nanoTime()
            try {
                analysis = EncoderAnalyzer.analyze(setup, decision, requireNotNull(calibration), tracking)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                analysisFailure = error.javaClass.simpleName
            }
            analyzeNs = System.nanoTime() - analyzeStartNs
            coreWallNs = System.nanoTime() - coreStartNs
        } catch (error: Throwable) {
            pipelineFailure = error
            throw error
        } finally {
            // Stop the observer before codec release and output materialization. Runtime-counter
            // deltas include observer stop/join overhead, but never close/JSON/report allocation.
            sampler.stop()
            sampler.captureEnd()
            val closeStartNs = System.nanoTime()
            try {
                offline.close()
            } catch (closeError: Throwable) {
                val primary = pipelineFailure
                if (primary != null) primary.addSuppressed(closeError) else throw closeError
            } finally {
                closeNs = System.nanoTime() - closeStartNs
            }
        }

        // Everything below runs after the core timer stopped: sampler stop/join, endpoint snapshot,
        // status decision, output dump and report write are never attributed to the measured window.
        sampler.stop()
        val memory = samplerElement { sampler.endpointSnapshot() }
        val sampling = samplerElement { sampler.sampledSummary() }

        val counters = profiler.counters()
        val deliveredFrames = (counting.frameAtDelivered + counting.forEachDelivered).toLong()
        val producerDelivered = counters[TrackingPerformanceProfiler.Metric.DELIVERED_FRAMES]
        val lost = tracking.isEmpty() || tracking.any { it.result.sample.status == TrackingStatus.LOST }
        val frameOrderExact = tracking.withIndex().all { (position, frame) -> frame.frameIndex == position }
        val ptsExact = tracking.all { frame ->
            frame.frameIndex in index.frameTimesUs.indices &&
                frame.result.sample.timestampUs == index.frameTimesUs[frame.frameIndex]
        }
        val coverageExact = tracking.size == index.frameCount && deliveredFrames == index.frameCount.toLong() && frameOrderExact
        val reasons = mutableListOf<String>()
        if (producerDelivered != null && producerDelivered != deliveredFrames) reasons += "PRODUCER_WRAPPER_COUNT_MISMATCH"
        if (lost) reasons += "TRACKING_LOST"
        if (!ptsExact) reasons += "PTS_NOT_EXACT"
        if (!coverageExact) {
            reasons += "COVERAGE_INCOMPLETE tracked=${tracking.size} delivered=$deliveredFrames index=${index.frameCount}"
        }
        if (analysis == null) reasons += "ANALYZER_NOT_RETURNED ${analysisFailure.orEmpty()}"
        val status = if (reasons.isEmpty()) EncoderPerformanceSupport.STATUS_PASSED else EncoderPerformanceSupport.STATUS_INCOMPLETE

        val output: JsonObject = analysis?.let { EncoderPerformanceSupport.outputSnapshot(tracking, it) }
            ?: trackingPrefixDiagnostic(tracking, analysisFailure)
        val outputSha256 = EncoderPerformanceSupport.snapshotSha256(output)

        val measurements = obj(
            "timer" to JsonPrimitive("STOPPED"),
            "status_reasons" to JsonArray(reasons.map { JsonPrimitive(it) }),
            "timing" to timingElement(request, coreWallNs, provisionNs, indexNs, sourceNs, seedNs, trackNs, analyzeNs, closeNs),
            "prefetch" to prefetchElement(request, prefetchSource),
            "producer_stage_timings" to producerStageTimings(profiler, request),
            "producer_counters" to producerCounters(profiler, request),
            "denominators" to denominators(index, request, counting, deliveredFrames, tracking.size, analysis?.samples?.size),
            "nesting" to obj(
                "frame_count_probe_inside_decode_frame_at" to JsonPrimitive(true),
                "decode_batch_additive_with_decode_amortized" to JsonPrimitive(false),
                "note" to JsonPrimitive(
                    "DECODE (amortized per requested frame) and DECODE_BATCH (per native attempt) describe the same" +
                        " native work and must never be added; FRAME_COUNT_PROBE is nested inside DECODE for frameAt only," +
                        " and is outside DECODE for the batch path. Amortization divides by requested, not delivered, frames.",
                ),
                "analyzer_internals" to JsonPrimitive("NOT_SPLIT: EncoderAnalyzer.analyze is one interval; no analyzer formula changed"),
            ),
            "index" to indexElement(index, presentation, decision, request),
            "analysis" to obj(
                "status" to JsonPrimitive(if (analysis != null) "RETURNED" else "NOT_RETURNED"),
                "error" to (analysisFailure?.let { JsonPrimitive(it) } ?: JsonNull),
                "scope" to JsonPrimitive("EncoderAnalyzer.analyze only; samples/repetitions live in the output snapshot"),
            ),
            "memory" to memory,
            "sampling" to sampling,
            "output_snapshot_sha256" to JsonPrimitive(outputSha256),
            "cross_run" to JsonPrimitive(
                "SINGLE_RUN_PER_INVOCATION: no aggregate, median or p95 over one sample and no speedup value is produced",
            ),
        )
        return PipelineRun(
            status = status,
            measurements = measurements,
            output = output,
            error = reasons.takeIf { it.isNotEmpty() }?.joinToString(" | "),
            reasons = reasons,
        )
    }

    private fun prefetchElement(request: EncoderPerfRequest, prefetch: MmrPrefetchFrameSource?): JsonObject {
        if (request.mmrPrefetch != EncoderPerfArguments.MMR_PREFETCH_ON || prefetch == null) {
            return obj(
                "status" to JsonPrimitive("DISABLED"),
                "mode" to JsonPrimitive(EncoderPerfArguments.MMR_PREFETCH_OFF),
                "source_route" to JsonPrimitive("DEFAULT_DISPATCHERS_IO"),
                "core_scope" to JsonPrimitive("CORE_ONLY"),
                "ui_preview" to JsonPrimitive("UI_PREVIEW_NOT_RUN"),
                "note" to JsonPrimitive("usual MMR route; decoderDispatcher defaults to Dispatchers.IO"),
            )
        }
        val diagnostics = prefetch.diagnostics()
        return obj(
            "status" to JsonPrimitive("ENABLED"),
            "mode" to JsonPrimitive(EncoderPerfArguments.MMR_PREFETCH_ON),
            "source_route" to JsonPrimitive("MMR_PREFETCH_OWNED_SINGLE_THREAD"),
            "core_scope" to JsonPrimitive("CORE_ONLY"),
            "ui_preview" to JsonPrimitive("UI_PREVIEW_NOT_RUN"),
            "queue_capacity" to JsonPrimitive(diagnostics.queueCapacity),
            "high_water_mark" to JsonPrimitive(diagnostics.highWaterMark),
            "max_owned_bytes" to JsonPrimitive(diagnostics.maxOwnedBytes),
            "produced" to JsonPrimitive(diagnostics.produced),
            "consumed" to JsonPrimitive(diagnostics.consumed),
            "producer_thread_ids" to JsonArray(diagnostics.producerThreadIds.map { JsonPrimitive(it) }),
            "consumer_thread_id" to (diagnostics.consumerThreadId?.let { JsonPrimitive(it) } ?: JsonNull),
            "owner_thread_id" to (diagnostics.ownerThreadId?.let { JsonPrimitive(it) } ?: JsonNull),
            "cancelled" to JsonPrimitive(diagnostics.cancelled),
            "joined" to JsonPrimitive(diagnostics.joined),
            "closed" to JsonPrimitive(diagnostics.closed),
            "owner_shutdown" to JsonPrimitive(diagnostics.ownerShutdown),
            "owner_terminated" to JsonPrimitive(diagnostics.ownerTerminated),
            "owner_terminated_or_shutdown" to JsonPrimitive(diagnostics.ownerTerminatedOrShutdown),
            "queue_plus_send_plus_current_max_frames" to JsonPrimitive(MmrPrefetchFrameSource.MAX_OUTSTANDING_FRAMES),
            "additional_buffer_bound_bytes" to JsonPrimitive(MmrPrefetchFrameSource.MAX_ADDITIONAL_BYTES.toLong()),
            "combined_image_envelope_bytes" to JsonPrimitive(MmrPrefetchFrameSource.COMBINED_IMAGE_ENVELOPE_BYTES),
            "envelope_note" to JsonPrimitive(MmrPrefetchFrameSource.ENVELOPE_NOTE),
        )
    }

    private fun samplerElement(block: () -> JsonObject): JsonObject = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        obj(
            "status" to JsonPrimitive("NOT_AVAILABLE"),
            "reason" to JsonPrimitive("observation failed type=${error.javaClass.simpleName}; the tracked result is unaffected"),
        )
    }

    private fun timingElement(
        request: EncoderPerfRequest,
        coreWallNs: Long,
        provisionNs: Long,
        indexNs: Long,
        sourceNs: Long,
        seedNs: Long,
        trackNs: Long,
        analyzeNs: Long,
        closeNs: Long,
    ): JsonObject = obj(
        "units" to JsonPrimitive("nanoseconds"),
        "core" to obj(
            "scope" to JsonPrimitive("initialize(seed) + trackRemaining (including prefetch producer join when mmr_prefetch=ON) + EncoderAnalyzer.analyze"),
            "wall_ns" to JsonPrimitive(coreWallNs),
            "profiler_configuration" to JsonPrimitive(profilerConfiguration(request)),
            "pts_exactness_scope" to JsonPrimitive(
                "Equality validates index-derived timestamp plumbing only, not decoded picture-to-PTS correspondence; fallback uses OPTION_CLOSEST.",
            ),
            "sampler_sampling_included" to JsonPrimitive(request.sampleMemory),
            "excluded" to JsonPrimitive(
                "fixture provisioning, index construction, source construction, close, JSON dump, report write and sampler stop/join",
            ),
            "no_claim" to JsonPrimitive("one sample only: no median, no p95, no speedup"),
        ),
        "outer_exclusive" to obj(
            "fixture_provision_ns" to JsonPrimitive(provisionNs),
            "index_create_ns" to JsonPrimitive(indexNs),
            "source_construct_ns" to JsonPrimitive(sourceNs),
            "seed_initialize_ns" to JsonPrimitive(seedNs),
            "track_remaining_ns" to JsonPrimitive(trackNs),
            "analyze_ns" to JsonPrimitive(analyzeNs),
            "close_ns" to JsonPrimitive(closeNs),
        ),
    )

    private fun producerStageTimings(profiler: TrackingPerformanceProfiler, request: EncoderPerfRequest): JsonElement {
        val mode = profilerModeLabel(request)
        if (mode == MODE_OFF) {
            return obj(
                "status" to JsonPrimitive("NOT_COLLECTED"),
                "mode" to JsonPrimitive(MODE_OFF),
                "reason" to JsonPrimitive("profile=false; enabled=false, so producer stages were not instrumented for this run"),
            )
        }
        val detailed = mode == MODE_DETAILED
        return obj(
            "status" to JsonPrimitive("REPORTED"),
            "mode" to JsonPrimitive(mode),
            "method" to JsonPrimitive("TrackingPerformanceProfiler(enabled=true, collectPipelineDetails=$detailed) samples"),
            "stage_scope" to JsonPrimitive(
                if (detailed) {
                    "legacy and detail stages; DECODE_BATCH totals are not additive with the legacy amortized DECODE"
                } else {
                    "legacy stages only; detail stages were not instrumented and are absent, never zero"
                },
            ),
            "detail_stages" to JsonPrimitive(if (detailed) "COLLECTED" else "NOT_COLLECTED"),
            "stages" to JsonObject(
                profiler.snapshot().entries.associate { (stage, stats) ->
                    stage.label to obj(
                        "samples" to JsonPrimitive(stats.samples.toLong()),
                        "total_ms" to JsonPrimitive(stats.totalMs),
                        "mean_ms" to JsonPrimitive(stats.meanMs),
                        "p50_ms" to JsonPrimitive(stats.p50Ms),
                        "p95_ms" to JsonPrimitive(stats.p95Ms),
                        "max_ms" to JsonPrimitive(stats.maxMs),
                    )
                },
            ),
        )
    }

    private fun producerCounters(profiler: TrackingPerformanceProfiler, request: EncoderPerfRequest): JsonElement {
        val mode = profilerModeLabel(request)
        val counters = profiler.counters()
        if (counters.isEmpty()) {
            val reason = when (mode) {
                MODE_OFF -> "profile=false; enabled=false, so producer counters were not collected"
                MODE_LEGACY -> "pipeline_details=false (LEGACY); producer counters are detail-only and are NOT_COLLECTED, not zero"
                else -> "the profiler returned no counters although DETAILED requested them"
            }
            return obj(
                "status" to JsonPrimitive("NOT_COLLECTED"),
                "mode" to JsonPrimitive(mode),
                "reason" to JsonPrimitive(reason),
            )
        }
        return obj(
            "status" to JsonPrimitive("REPORTED"),
            "mode" to JsonPrimitive(mode),
            "values" to JsonObject(counters.entries.associate { (metric, value) -> metric.label to JsonPrimitive(value) }),
            "note" to JsonPrimitive(
                "DECODE_BATCH_RETURNED_FRAMES includes partial batches later discarded by the size gate;" +
                    " DECODE_FALLBACK_FRAMES includes the seed fallback after an indexed-decode repair",
            ),
        )
    }

    /** Effective test-core profiler configuration; `profile=false` always disables the profiler. */
    private fun profilerModeLabel(request: EncoderPerfRequest): String = when {
        !request.profile -> MODE_OFF
        !request.pipelineDetails -> MODE_LEGACY
        else -> MODE_DETAILED
    }

    private fun profilerConfiguration(request: EncoderPerfRequest): String = when (profilerModeLabel(request)) {
        MODE_OFF -> "OFF: TrackingPerformanceProfiler(enabled=false, collectPipelineDetails=false); no producer stage" +
            " timings and no producer counters are collected. profile=false always disables the profiler." +
            " Shipped UI default is enabled=true/details=false: an on/off delta includes the legacy profiler overhead, not a production A/B."
        MODE_LEGACY -> "LEGACY: TrackingPerformanceProfiler(enabled=true, collectPipelineDetails=false); legacy stage timings only;" +
            " detail stages and producer counters are NOT_COLLECTED. This is the shipped tracker profiler configuration" +
            " (test-core, not a production UI benchmark)."
        else -> "DETAILED: TrackingPerformanceProfiler(enabled=true, collectPipelineDetails=true); legacy and detail stage" +
            " timings plus producer counters are collected."
    }

    private fun denominators(
        index: VideoFrameIndex,
        request: EncoderPerfRequest,
        counting: CountingFrameSource,
        deliveredFrames: Long,
        tracked: Int,
        analysisSamples: Int?,
    ): JsonObject = obj(
        "index_frame_count" to JsonPrimitive(index.frameCount.toLong()),
        "expected_host_frames" to JsonPrimitive(request.expectedHostFrames.toLong()),
        "host_equals_android" to JsonPrimitive(index.frameCount == request.expectedHostFrames),
        "seed_frames" to JsonPrimitive(1L),
        "wrapper_frame_at_delivered" to JsonPrimitive(counting.frameAtDelivered.toLong()),
        "wrapper_foreach_delivered" to JsonPrimitive(counting.forEachDelivered.toLong()),
        "wrapper_consumed_total" to JsonPrimitive((counting.frameAtDelivered + counting.forEachDelivered).toLong()),
        "n_decoded_converted" to JsonPrimitive(deliveredFrames),
        "n_tracked_including_seed" to JsonPrimitive(tracked.toLong()),
        "n_analysis_samples" to (analysisSamples?.let { JsonPrimitive(it.toLong()) } ?: JsonPrimitive("NOT_AVAILABLE")),
        "eligible" to JsonPrimitive(index.frameCount == request.expectedHostFrames),
        "rule" to JsonPrimitive(
            "host and Android frame counts must match before any timing is comparable;" +
                " wrapper counts come from the test-only counting source and never alter decode order, backpressure or progress",
        ),
    )

    private fun indexElement(
        index: VideoFrameIndex,
        presentation: VideoPresentationGeometry,
        decision: VideoTimingDecision,
        request: EncoderPerfRequest,
    ): JsonObject = obj(
        "encoded_width" to JsonPrimitive(index.width),
        "encoded_height" to JsonPrimitive(index.height),
        "presentation_width" to JsonPrimitive(presentation.width),
        "presentation_height" to JsonPrimitive(presentation.height),
        "rotation_degrees" to JsonPrimitive(index.rotationDegrees),
        "mime_type" to (index.mimeType?.let { JsonPrimitive(it) } ?: JsonNull),
        "duration_us" to JsonPrimitive(index.durationUs),
        "average_bitrate" to (index.averageBitrate?.let { JsonPrimitive(it) } ?: JsonNull),
        "has_audio" to JsonPrimitive(index.hasAudio),
        "color_standard" to (index.colorStandard?.let { JsonPrimitive(it) } ?: JsonNull),
        "color_transfer" to (index.colorTransfer?.let { JsonPrimitive(it) } ?: JsonNull),
        "color_range" to (index.colorRange?.let { JsonPrimitive(it) } ?: JsonNull),
        "nominal_frame_rate_bits" to EncoderPerformanceSupport.doubleBits(index.nominalFrameRate),
        "metadata_capture_frame_rate_bits" to EncoderPerformanceSupport.doubleBits(index.metadataCaptureFrameRate),
        "detected_fps" to JsonPrimitive(index.detectedFps),
        "temporal_continuity" to obj(
            "median_delta_us" to JsonPrimitive(index.temporalContinuity.medianDeltaUs),
            "gap_threshold_us" to JsonPrimitive(index.temporalContinuity.gapThresholdUs),
            "gap_count" to JsonPrimitive(index.temporalContinuity.gapCount),
            "largest_gap_us" to JsonPrimitive(index.temporalContinuity.largestGapUs),
        ),
        "pts_authority" to JsonPrimitive("index.frameTimesUs (MediaExtractor sample times, strictly increasing)"),
        "pts_index_us" to JsonArray(index.frameTimesUs.map { JsonPrimitive(it) }),
        "timing_policy" to JsonPrimitive(request.timingPolicy),
        "timing_decision" to obj(
            "mode" to JsonPrimitive(decision.mode.name),
            "slow_motion_factor_bits" to EncoderPerformanceSupport.doubleBits(decision.slowMotionFactor),
            "time_scale_bits" to EncoderPerformanceSupport.doubleBits(decision.timeScale),
            "is_reliable" to JsonPrimitive(decision.isReliable),
        ),
        "source" to JsonPrimitive(
            if (request.timingPolicy == EncoderPerfArguments.TIMING_EXPLICIT_REAL_TIME) {
                "EXPLICIT_REAL_TIME_DECLARATION"
            } else {
                "VideoSource.IMPORTED"
            },
        ),
    )

    private fun trackingPrefixDiagnostic(tracking: List<TrackingFrameResult>, analysisFailure: String?): JsonObject = obj(
        "schema" to JsonPrimitive("encoder_perf_tracking_prefix_diagnostic"),
        "schema_version" to JsonPrimitive(1),
        "analysis" to JsonPrimitive("NOT_RETURNED"),
        "analysis_error" to JsonPrimitive(analysisFailure ?: "unknown"),
        "note" to JsonPrimitive(
            "The analyzer did not return, so the exact EncoderPerformanceSupport.outputSnapshot is unavailable." +
                " Every processed frame is preserved below with its exact index, PTS and raw-bit point/confidence.",
        ),
        "frames" to JsonArray(
            tracking.map { frame ->
                obj(
                    "frame_index" to JsonPrimitive(frame.frameIndex),
                    "source_pts_us" to JsonPrimitive(frame.result.sample.timestampUs),
                    "tracking_status" to JsonPrimitive(frame.result.sample.status.name),
                    "point" to (
                        frame.result.sample.point?.let {
                            obj(
                                "x" to EncoderPerformanceSupport.doubleBits(it.x),
                                "y" to EncoderPerformanceSupport.doubleBits(it.y),
                            )
                        } ?: JsonNull
                        ),
                    "confidence" to EncoderPerformanceSupport.doubleBits(frame.result.sample.confidence),
                )
            },
        ),
    )

    private fun provenance(context: Context, request: EncoderPerfRequest): JsonObject = obj(
        "source_label" to JsonPrimitive(request.fixtureLabel),
        "fixture_sha256_declared" to JsonPrimitive(request.declaredSha256),
        "run_kind" to JsonPrimitive(request.runKind),
        "cold_claim" to JsonPrimitive(
            if (request.runKind == "COLD_FIRST") {
                "FIRST_PROCESS_AND_LIBRARY_USAGE_IN_THIS_APK: decoder, OS page cache and disk state are not proven cold"
            } else {
                "NOT_APPLICABLE"
            },
        ),
        "timing_policy" to JsonPrimitive(request.timingPolicy),
        "head" to declaredOrNot(request.head),
        "apk_sha256" to declaredOrNot(request.apkSha256),
        "source_fingerprint" to declaredOrNot(request.sourceFingerprint),
        "source_dirty" to declaredOrNot(request.sourceDirty),
        "build_type" to declaredOrNot(request.buildType),
        "purpose" to JsonPrimitive(request.purpose),
        "classification" to JsonPrimitive(request.classification),
        "expected_host_frames" to JsonPrimitive(request.expectedHostFrames.toLong()),
        "seed_frame_index" to JsonPrimitive(SEED_FRAME_INDEX),
        "seed_point" to obj(
            "x" to EncoderPerformanceSupport.doubleBits(request.seedX),
            "y" to EncoderPerformanceSupport.doubleBits(request.seedY),
        ),
        "calibration" to obj(
            "source" to JsonPrimitive(request.calibrationSource),
            "reference_length_m" to EncoderPerformanceSupport.doubleBits(request.referenceM),
            "point_a" to obj(
                "x" to EncoderPerformanceSupport.doubleBits(request.calibrationAX),
                "y" to EncoderPerformanceSupport.doubleBits(request.calibrationAY),
            ),
            "point_b" to obj(
                "x" to EncoderPerformanceSupport.doubleBits(request.calibrationBX),
                "y" to EncoderPerformanceSupport.doubleBits(request.calibrationBY),
            ),
        ),
        "exercise" to JsonPrimitive(request.exercise),
        "load_kg" to EncoderPerformanceSupport.doubleBits(request.loadKg),
        "load_source" to JsonPrimitive(request.loadSource),
        "profile" to JsonPrimitive(request.profile),
        "pipeline_details" to JsonPrimitive(request.pipelineDetails),
        "profiler_mode" to JsonPrimitive(profilerModeLabel(request)),
        "mmr_prefetch" to JsonPrimitive(request.mmrPrefetch),
        "core_scope" to JsonPrimitive("CORE_ONLY"),
        "ui_preview" to JsonPrimitive("UI_PREVIEW_NOT_RUN"),
        "on_progress" to JsonPrimitive("NOOP"),
        "preview_observer" to JsonPrimitive("NULL"),
        "sample_memory" to JsonPrimitive(request.sampleMemory),
        "sample_interval_ms" to JsonPrimitive(request.sampleIntervalMs),
        "fixture_cache" to JsonPrimitive("cache/trajectory_exports (FileProvider cache-path, unique owned copy, deleted after the run)"),
        "fixture_uri" to JsonPrimitive("file:// Uri read in-process; no URI is stored in this report"),
        "environment" to environment(context, request.buildType),
        "claim_limits" to JsonArray(
            listOf(
                "no accuracy, ground truth or clinical claim",
                "no speedup, median or p95 claim from a single run",
                "COLD_FIRST does not prove a cold decoder, OS cache or disk",
                "no 240-cap or downsample is applied by this runner",
                "AVD battery and thermal values are simulated, not physical",
                "no Room write, no profile/athlete identifier and no video bytes are persisted",
            ).map { JsonPrimitive(it) },
        ),
    )

    private fun declaredOrNot(value: String?): JsonElement = value?.takeIf { it.isNotBlank() }
        ?.let { obj("status" to JsonPrimitive("DECLARED"), "value" to JsonPrimitive(it)) }
        ?: obj("status" to JsonPrimitive("NOT_DECLARED"))

    private fun environment(context: Context, buildTypeArg: String?): JsonObject {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        @Suppress("DEPRECATION")
        val versionCode = packageInfo.versionCode.toLong()
        return obj(
            "manufacturer" to JsonPrimitive(Build.MANUFACTURER),
            "model" to JsonPrimitive(Build.MODEL),
            "device" to JsonPrimitive(Build.DEVICE),
            "hardware" to JsonPrimitive(Build.HARDWARE),
            "abis" to JsonArray(Build.SUPPORTED_ABIS.map { JsonPrimitive(it) }),
            "sdk_int" to JsonPrimitive(Build.VERSION.SDK_INT),
            "release" to JsonPrimitive(Build.VERSION.RELEASE),
            "fingerprint" to JsonPrimitive(Build.FINGERPRINT),
            "application_id" to JsonPrimitive(context.packageName),
            "version_name" to JsonPrimitive(packageInfo.versionName ?: "UNKNOWN"),
            "version_code" to JsonPrimitive(versionCode),
            "debuggable" to JsonPrimitive(debuggable),
            "build_type_declared" to declaredOrNot(buildTypeArg),
            "minification_claim" to JsonPrimitive(
                "debuggable=$debuggable: a non-debuggable build is expected for minified releases," +
                    " but this flag alone does not prove R8/minification",
            ),
        )
    }

    private data class PipelineRun(
        val status: String,
        val measurements: JsonObject,
        val output: JsonObject?,
        val error: String?,
        val reasons: List<String>,
    )
}

/**
 * Test-only counting wrapper. It forwards every call and counts the frames handed to the consumer
 * in `frameAt` and `forEachFrame` without retaining gray pixels or bitmaps, without reordering and
 * without changing the boolean/backpressure contract or progress values.
 */
private class CountingFrameSource(private val delegate: TrackingFrameSource) : TrackingFrameSource {
    var frameAtDelivered = 0
        private set
    var forEachDelivered = 0
        private set

    override val frameCount: Int get() = delegate.frameCount

    override suspend fun frameAt(index: Int): DecodedTrackingFrame = delegate.frameAt(index).also {
        frameAtDelivered++
    }

    override suspend fun forEachFrame(
        startIndex: Int,
        onFrame: suspend (DecodedTrackingFrame) -> Boolean,
    ) {
        delegate.forEachFrame(startIndex) { frame ->
            forEachDelivered++
            onFrame(frame)
        }
    }

    override fun close() = delegate.close()
}

private fun obj(vararg members: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*members))
