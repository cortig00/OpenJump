package com.openjump.app.benchmark

import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.encoder.EncoderAnalyzer
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.OfflineVideoTracker
import com.openjump.app.tracking.PyramidalKltMotionTracker
import com.openjump.app.video.VideoFrameIndexer
import com.openjump.app.video.VideoTrackingFrameSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in validation; ordinary connected tests do not require local media. */
@RunWith(AndroidJUnit4::class)
class EncoderBaselineBenchmarkTest {
    @Test
    fun runEncoderValidationWhenExplicitlyRequested() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
        outputDir.mkdirs()
        val report = JSONObject().apply {
            put("schema_version", 2)
            put("head", args.getString("encoder_benchmark_head") ?: "UNKNOWN")
            put("config", args.getString("encoder_benchmark_config") ?: "default")
            put("device", Build.MODEL ?: Build.DEVICE)
            put("api_level", Build.VERSION.SDK_INT)
            put("cadence_claim", "NOMINAL_CFR_NORMALIZED; no VFR coverage")
            put("operational", JSONObject().put("status", "NOT_RUN").put("processed_videos", 0))
            put("behavior_regression", JSONObject().put("status", "NOT_RUN").put("processed_videos", 0))
            put("accuracy", JSONObject().put("status", "NOT_RUN").put("reason", "No independent numerical ground truth declared"))
            put("fixture_hashes", JSONObject())
            put("results", JSONArray())
            put("validation_errors", JSONArray())
        }
        val reportFile = EncoderValidation.encoderBenchmarkReportFile(outputDir)
        fun persist() = EncoderValidation.atomicWrite(reportFile, report.toString(2))

        val rootArgument = args.getString("encoder_benchmark_root")
        if (rootArgument == null) {
            EncoderValidation.writeNotRunBenchmarkReport(
                reportFile,
                EncoderReportMetadata(
                    head = args.getString("encoder_benchmark_head") ?: "UNKNOWN",
                    config = args.getString("encoder_benchmark_config") ?: "default",
                    device = Build.MODEL ?: Build.DEVICE,
                    apiLevel = Build.VERSION.SDK_INT,
                ),
            )
            assumeTrue("Encoder benchmark is opt-in; pass -e encoder_benchmark_root <dataset-root>", false)
            return@runBlocking
        }

        // A requested run is durable from the first instruction, before any validation or I/O.
        report.put("run_status", "RUNNING")
            .put("run_reason", "benchmark_requested")
            .put("requested", true)
            .put("processed_videos", 0)
        report.getJSONObject("operational").put("status", "PENDING").put("processed_videos", 0)
        report.getJSONObject("behavior_regression").put("status", "NOT_RUN").put("processed_videos", 0)
        persist()
        var processedVideos = 0

        try {
            val root = File(rootArgument)
            val manifestName = args.getString("encoder_benchmark_manifest") ?: "benchmark_manifest.json"
        val pathErrors = EncoderValidation.validateRootAndManifest(root, manifestName)
        if (pathErrors.isNotEmpty()) {
            failRequestedReport(report, ::persist, pathErrors)
        }
        val manifestFile = EncoderValidation.containedCanonical(root, manifestName)
        if (!manifestFile.isFile) {
            failRequestedReport(report, ::persist, listOf("FILE_NOT_FOUND ${manifestFile.path}"))
        }
        val manifest = try {
            EncoderValidation.parseManifest(manifestFile.readText())
        } catch (error: Exception) {
            failRequestedReport(report, ::persist, listOf("INVALID_MANIFEST ${error.message ?: error}"))
            error("unreachable")
        }
        val validation = EncoderValidation.validateManifest(root, manifest)
        val hashes = report.getJSONObject("fixture_hashes")
        manifest.fixtures.forEach { hashes.put(it.testId, it.sha256.lowercase()) }
        if (!validation.isValid) {
            failRequestedReport(report, ::persist, validation.errors)
        }

        val results = report.getJSONArray("results")
        val operationalStatuses = mutableListOf<String>()
        val behaviorStatuses = mutableListOf<String>()
        val behaviorFailureReasons = mutableListOf<String>()
        val accuracyInputs = mutableListOf<EncoderValidation.AccuracyFixtureObservation>()
        val accuracyBlockedReasons = mutableListOf<String>()
        for ((fixtureIndex, fixture) in manifest.fixtures.withIndex()) {
            var cached: EncoderValidation.VerifiedFixtureCache? = null
            var operationalStatus = "FAILED"
            var behaviorStatus = "NOT_RUN"
            var behaviorReason: String? = null
            var observedForAccuracy: List<ObservedEncoderRepetition> = emptyList()
            var processedFrames = 0
            var expectedFrames = 0
            var repetitionCount = 0
            val result = JSONObject().apply {
                put("test_id", fixture.testId)
                put("fixture", fixture.relativePath)
                put("fixture_sha256", fixture.sha256.lowercase())
                put("cadence_classification", fixture.cadenceClassification)
                put("calibration_source", fixture.calibration!!.source)
                put("ground_truth_status", if (fixture.independentGroundTruth) "INDEPENDENT_NUMERICAL" else "NOT_AVAILABLE")
            }
            try {
                val sourceFile = EncoderValidation.containedCanonical(root, fixture.relativePath)
                // Stream-copy and verify again immediately before processing to avoid TOCTOU.
                cached = EncoderValidation.copyVerifiedFixture(
                    sourceFile,
                    File(context.cacheDir, "trajectory_exports"),
                    fixture.sha256,
                    "${fixtureIndex}-${fixture.testId}",
                )
                result.put("observed_sha256", cached!!.observedSha256)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cached!!.file)
                val index = VideoFrameIndexer.indexFromUri(context, uri)
                expectedFrames = index.frameCount
                val source = VideoTrackingFrameSource(context, uri, index)
                var runner: OfflineVideoTracker? = null
                try {
                    val frame0 = source.frameAt(0)
                    val calibrationDeclaration = fixture.calibration!!
                    val pointA = ImagePoint(calibrationDeclaration.endpointsPx[0], calibrationDeclaration.endpointsPx[1])
                    val pointB = ImagePoint(calibrationDeclaration.endpointsPx[2], calibrationDeclaration.endpointsPx[3])
                    val distancePx = kotlin.math.hypot(pointB.x - pointA.x, pointB.y - pointA.y)
                    val calibration = MetricCalibration(
                        frameIndex = 0,
                        pointA = pointA,
                        pointB = pointB,
                        referenceLengthM = calibrationDeclaration.referenceLengthM,
                        metersPerPixel = calibrationDeclaration.referenceLengthM / distancePx,
                    )
                    val seed = ImagePoint(fixture.trackingSeedPx[0], fixture.trackingSeedPx[1])
                    runner = OfflineVideoTracker(source, PyramidalKltMotionTracker())
                    runner.initialize(frame0, seed)
                    val tracking = runner.trackRemaining { _, _, _ -> }
                    val analysis = EncoderAnalyzer.analyze(
                        setup = EncoderSetup(EncoderExercise.valueOf(fixture.exercise), fixture.loadKg),
                        timingDecision = VideoTimingDecision(VideoTimingMode.REAL_TIME, 1.0),
                        calibration = calibration,
                        tracking = tracking,
                    )
                    observedForAccuracy = analysis.repetitions.map { repetition ->
                        ObservedEncoderRepetition(
                            fixtureId = fixture.testId,
                            rep = repetition.ordinal + 1,
                            exercise = fixture.exercise,
                            metrics = mapOf(
                                "mean_velocity_mps" to repetition.metric(EncoderMetricKey.MCV)?.value,
                                "peak_velocity_mps" to repetition.metric(EncoderMetricKey.PEAK_VELOCITY)?.value,
                                "rom_m" to repetition.metric(EncoderMetricKey.ROM)?.value,
                            ),
                        )
                    }
                    processedFrames = tracking.size
                    repetitionCount = analysis.repetitions.size
                    operationalStatus = "PASSED"
                    processedVideos++
                    result.put("frame_count", index.frameCount)
                    result.put("frames_processed", tracking.size)
                    result.put("lost_frames", tracking.count { it.result.sample.status.name == "LOST" })
                    result.put("repetitions", repetitionCount)
                    result.put("valid_repetitions", analysis.validRepetitions.size)
                    result.put("meters_per_pixel", calibration.metersPerPixel)
                } finally {
                    runner?.close()
                    source.close()
                }
                if (operationalStatus == "PASSED") {
                    val behaviorErrors = buildList {
                        if (repetitionCount != fixture.expectedRepetitions) {
                            add("expected ${fixture.expectedRepetitions} reps, got $repetitionCount")
                        }
                        if (processedFrames != expectedFrames) add("incomplete tracking: $processedFrames/$expectedFrames frames")
                    }
                    if (behaviorErrors.isEmpty()) {
                        behaviorStatus = "PASSED"
                        if (fixture.independentGroundTruth) {
                            accuracyInputs += EncoderValidation.AccuracyFixtureObservation(fixture, observedForAccuracy)
                        }
                    } else {
                        behaviorStatus = "FAILED"
                        behaviorReason = behaviorErrors.joinToString("; ")
                        behaviorFailureReasons += "${fixture.testId}: $behaviorReason"
                        if (fixture.independentGroundTruth) accuracyBlockedReasons += "${fixture.testId}: declared independent numerical ground truth blocked by behavior failure: $behaviorReason"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                operationalStatus = "FAILED"
                behaviorStatus = "NOT_RUN"
                val operationalReason = error.message ?: error.toString()
                result.put("error", operationalReason)
                if (fixture.independentGroundTruth) {
                    accuracyBlockedReasons += "${fixture.testId}: declared independent numerical ground truth unavailable after operational failure: $operationalReason"
                }
            } finally {
                EncoderValidation.cleanupVerifiedFixture(cached)
                result.put("operational_status", operationalStatus)
                result.put("behavior_regression_status", behaviorStatus)
                result.put("behavior_regression_reason", behaviorReason ?: JSONObject.NULL)
                result.put("status", operationalStatus)
                operationalStatuses += operationalStatus
                behaviorStatuses += behaviorStatus
                results.put(result)
                persist()
            }
        }

        val accuracyReports = EncoderValidation.accuracyReports(accuracyInputs)
        val accuracyStatus = when {
            accuracyBlockedReasons.isNotEmpty() -> "INCOMPLETE"
            accuracyReports.isEmpty() -> "NOT_RUN"
            accuracyReports.all { it.status == "REPORTED" } -> "REPORTED"
            else -> "INCOMPLETE"
        }
        val decision = EncoderValidation.decideRun(true, processedVideos = operationalStatuses.count { it == "PASSED" }, operationalStatuses = operationalStatuses, behaviorStatuses = behaviorStatuses, accuracyStatus = accuracyStatus)
        report.put("run_status", decision.status)
            .put("run_reason", decision.reason)
            .put("requested", decision.requested)
            .put("processed_videos", decision.processedVideos)
        report.put("operational", JSONObject().put("status", decision.operationalStatus).put("processed_videos", decision.processedVideos))
        report.put("behavior_regression", JSONObject().put("status", decision.behaviorStatus)
            .put("processed_videos", decision.processedVideos)
            .put("reasons", JSONArray(behaviorFailureReasons)))
        if (accuracyReports.isEmpty()) {
            val reason = if (accuracyBlockedReasons.isEmpty()) "No independent numerical ground truth declared" else accuracyBlockedReasons.joinToString("; ")
            report.put("accuracy", JSONObject().put("status", accuracyStatus).put("reason", reason))
        } else {
            val groups = JSONArray()
            accuracyReports.forEach { accuracy ->
                groups.put(JSONObject().apply {
                    put("exercise", accuracy.exercise)
                    put("metric", accuracy.metric)
                    put("unit", accuracy.unit)
                    put("eligible", accuracy.eligible)
                    put("compared", accuracy.compared)
                    put("missing", accuracy.missing)
                    put("coverage", accuracy.coverage)
                    put("status", accuracy.status)
                    put("mean_signed_error", accuracy.meanSignedError ?: JSONObject.NULL)
                    put("mae", accuracy.mae ?: JSONObject.NULL)
                    put("rmse", accuracy.rmse ?: JSONObject.NULL)
                    put("mean_relative_error_pct", accuracy.meanRelativeErrorPct ?: JSONObject.NULL)
                    put("identity_errors", JSONArray(accuracy.identityErrors))
                    put("observation_ids", JSONArray(accuracy.observationIds))
                })
            }
            val accuracyJson = JSONObject().put("status", accuracyStatus).put("metrics", groups)
            if (accuracyBlockedReasons.isNotEmpty()) accuracyJson.put("blocked_reasons", JSONArray(accuracyBlockedReasons))
            report.put("accuracy", accuracyJson)
        }
        persist()
        assertTrue("Requested Encoder benchmark failed: ${decision.reason}", decision.status == "PASSED")
        } catch (cancelled: CancellationException) {
            val processed = processedVideos
            val cancelledReport = EncoderValidation.buildCancellationReport(
                EncoderReportMetadata(
                    head = report.getString("head"), config = report.getString("config"),
                    device = report.getString("device"), apiLevel = report.getInt("api_level"),
                ),
                processed,
            )
            EncoderValidation.writeReport(reportFile, cancelledReport)
            throw cancelled
        }
    }

    private fun failRequestedReport(report: JSONObject, persist: () -> Unit, errors: List<String>): Nothing {
        report.put("run_status", "FAILED")
            .put("run_reason", "fixture_validation_failed")
            .put("requested", true)
            .put("validation_errors", JSONArray(errors))
        report.put("processed_videos", 0)
        report.put("operational", JSONObject().put("status", "FAILED").put("processed_videos", 0))
        report.put("behavior_regression", JSONObject().put("status", "NOT_RUN").put("processed_videos", 0))
        report.put("accuracy", JSONObject().put("status", "NOT_RUN").put("reason", "fixture_validation_failed"))
        persist()
        assertTrue("Requested Encoder benchmark validation failed: $errors", false)
        error("unreachable")
    }
}
