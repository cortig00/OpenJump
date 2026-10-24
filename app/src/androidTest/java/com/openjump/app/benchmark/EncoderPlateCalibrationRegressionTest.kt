package com.openjump.app.benchmark

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.encoder.EncoderAnalyzer
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.MetricCalibrator
import com.openjump.app.encoder.PlateDetectionOutcome
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import com.openjump.app.tracking.OfflineVideoTracker
import com.openjump.app.tracking.PyramidalKltMotionTracker
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoFrameIndexer
import com.openjump.app.video.VideoTrackingFrameSource
import com.openjump.app.video.toPresentation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Opt-in INT-07 behavior regression. This test checks indexing, accepted detection, tracking and
 * segmentation only; it is not an instrumental accuracy claim. The ordinary androidTest suite
 * deliberately does not require local video files; pass -e int07_fixture /path/to/set_01.mp4.
 */
@RunWith(AndroidJUnit4::class)
class EncoderPlateCalibrationRegressionTest {
    /** Identity control for the user-declared geometry; it does not validate numerical accuracy. */
    @Test
    fun int07_manual_calibration_identity_is_behavior_regression() {
        val calibration = MetricCalibrator.create(
            frameIndex = 0,
            pointA = com.openjump.app.tracking.ImagePoint(0.0, 0.0),
            pointB = com.openjump.app.tracking.ImagePoint(INT07_DECLARED_DIAMETER_PX, 0.0),
            referenceLengthM = INT07_DECLARED_REFERENCE_LENGTH_M,
        )
        assertEquals(
            INT07_DECLARED_REFERENCE_LENGTH_M / INT07_DECLARED_DIAMETER_PX,
            calibration.metersPerPixel,
            1e-12,
        )
    }

    @Test
    fun int07_plate_calibration_and_tracking_regression() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val fixtureArgument = args.getString("int07_fixture")
        assumeTrue("INT-07 is opt-in; pass -e int07_fixture <fixture>", fixtureArgument != null)
        val fixturePath = requireNotNull(fixtureArgument)
        val fixture = File(fixturePath)
        assertTrue("INT-07 fixture is missing: $fixturePath", fixture.isFile)
        assertEquals(INT07_SHA256, EncoderValidation.sha256(fixture))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val diagnosticsFile = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "int07_plate_calibration_regression.json",
        )
        val diagnostics = JSONObject().apply {
            put("schema_version", 1)
            put("claim", "BEHAVIOR_REGRESSION")
            put("test_id", "INT-07")
            put("sha256", INT07_SHA256)
            put("fixture_path", fixturePath)
            put("head", args.getString("encoder_benchmark_head") ?: "UNKNOWN")
            put("config", args.getString("encoder_benchmark_config") ?: "int07_behavior_regression")
            put("device", android.os.Build.MODEL ?: android.os.Build.DEVICE)
            put("api_level", android.os.Build.VERSION.SDK_INT)
            put("stage", "fixture_verified")
        }
        fun persistDiagnostics() = EncoderValidation.atomicWrite(diagnosticsFile, diagnostics.toString(2))
        persistDiagnostics()

        val cacheDir = File(context.cacheDir, "trajectory_exports").apply { mkdirs() }
        var verifiedCache: EncoderValidation.VerifiedFixtureCache? = null
        lateinit var source: VideoTrackingFrameSource
        var sourceCreated = false
        try {
            verifiedCache = EncoderValidation.copyVerifiedFixture(
                fixture, cacheDir, INT07_SHA256, "int07-regression",
            )
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", verifiedCache!!.file)
            val index = VideoFrameIndexer.indexFromUri(context, uri)
            assertEquals(584, index.frameCount)
            assertEquals(1080, index.width)
            assertEquals(1920, index.height)
            assertEquals(0L, index.timeOf(0))

            source = VideoTrackingFrameSource(context, uri, index)
            sourceCreated = true
            val detector = com.openjump.app.encoder.GeometricPlateDetector()
            val frame0 = source.frameAt(0)
            assertEquals(405, frame0.image.width)
            assertEquals(720, frame0.image.height)
            assertEquals(1080, frame0.presentationWidth)
            assertEquals(1920, frame0.presentationHeight)

            val tap = com.openjump.app.tracking.ImagePoint(480.0, 930.0)
            diagnostics.put("frame_ordinal", 0)
            diagnostics.put("frame_pts_us", index.timeOf(0))
            diagnostics.put("presentation_size", "${frame0.presentationWidth}x${frame0.presentationHeight}")
            diagnostics.put("tracking_size", "${frame0.image.width}x${frame0.image.height}")
            diagnostics.put("tap_presentation_x", tap.x)
            diagnostics.put("tap_presentation_y", tap.y)
            diagnostics.put("stage", "detecting")
            persistDiagnostics()

            val outcome = detector.detectAssisted(frame0.image, frame0.toTracking(tap))
            assertTrue("INT-07 detector outcome: $outcome", outcome is PlateDetectionOutcome.Found)
            val foundOutcome = outcome as PlateDetectionOutcome.Found
            diagnostics.put("candidate_count", foundOutcome.candidateCount)
            diagnostics.put("candidate_diameters_tracking_px", JSONArray(foundOutcome.diagnostics.candidateDiametersPx))
            diagnostics.put("candidate_sources", JSONArray(foundOutcome.diagnostics.candidateSources.map { it.name }))
            diagnostics.put(
                "circle_diameters_passing_gates_tracking_px",
                JSONArray(foundOutcome.diagnostics.circleDiametersPassingGatesPx),
            )
            val detection = foundOutcome.detection.toPresentation(frame0)
            diagnostics.put("stage", "detected")
            diagnostics.put("plate_source", detection.source.name)
            diagnostics.put("confidence", detection.confidence)
            diagnostics.put("center_x", detection.center.x)
            diagnostics.put("center_y", detection.center.y)
            diagnostics.put("diameter_px", detection.diameterPx)
            diagnostics.put("ellipse_major_axis_px", detection.ellipseMajorAxisPx)
            diagnostics.put("ellipse_minor_axis_px", detection.ellipseMinorAxisPx)
            diagnostics.put("ellipse_angle_radians", detection.ellipseAngleRadians)
            diagnostics.put("perspective_ratio", detection.perspectiveRatio)
            diagnostics.put("edge_support", detection.edgeSupport)
            diagnostics.put("edge_alignment", detection.edgeAlignment)
            diagnostics.put("covered_sectors", detection.coveredSectors)
            diagnostics.put("auto_calibration_accepted", detection.autoCalibrationAccepted)
            persistDiagnostics()

            // User-provided visible exterior annotation; behavior fixture only, not numerical GT.
            assertTrue("center_x=${detection.center.x}", detection.center.x in 480.0..496.0)
            assertTrue("center_y=${detection.center.y}", detection.center.y in 863.0..879.0)
            assertTrue("diameter=${detection.diameterPx}", detection.diameterPx in 338.0..352.0)
            assertTrue("unexpected large fallback diameter=${detection.diameterPx}", detection.diameterPx < 500.0)
            val metersPerPixel = INT07_DECLARED_REFERENCE_LENGTH_M / detection.diameterPx
            assertTrue("scale=$metersPerPixel", metersPerPixel.isFinite() && metersPerPixel > 0.0)
            if (detection.source == com.openjump.app.encoder.PlateDetectionSource.GRADIENT_CIRCLE) {
                assertEquals(0.0, detection.perspectiveRatio, 0.0)
                assertTrue(!detection.autoCalibrationAccepted)
            } else {
                assertTrue("the measured perspective must pass the calibration gate", detection.autoCalibrationAccepted)
            }
            diagnostics.put("meters_per_pixel", metersPerPixel)
            diagnostics.put("stage", "geometry_assertions_passed")
            persistDiagnostics()

            val endpoints = detection.calibrationEndpoints()
            val calibration = com.openjump.app.measurement.MetricCalibration(
                frameIndex = 0,
                pointA = endpoints.first,
                pointB = endpoints.second,
                referenceLengthM = INT07_DECLARED_REFERENCE_LENGTH_M,
                metersPerPixel = metersPerPixel,
            )
            val runner = OfflineVideoTracker(source, PyramidalKltMotionTracker())
            try {
                val results = runner.initialize(frame0, detection.center).let {
                    runner.trackRemaining { _, _, _ -> }
                }
                val lostFrames = results.count { it.result.sample.status == TrackingStatus.LOST }
                diagnostics.put("stage", "tracking")
                diagnostics.put("frames_processed", results.size)
                diagnostics.put("lost_frames", lostFrames)
                persistDiagnostics()
                assertEquals(584, results.size)
                assertTrue(results.none { it.result.sample.status == TrackingStatus.LOST })
                val timingDecision = VideoTimingDecision(VideoTimingMode.REAL_TIME, 1.0)
                val analysis = EncoderAnalyzer.analyze(
                    setup = EncoderSetup(EncoderExercise.SQUAT, loadKg = 131.54),
                    timingDecision = timingDecision,
                    calibration = calibration,
                    tracking = results,
                )
                val repetitionsJson = JSONArray()
                analysis.repetitions.forEach { rep ->
                    val repJson = JSONObject().apply {
                        put("ordinal", rep.ordinal)
                        put("quality", rep.quality.name)
                        put("start_sample", rep.startSample)
                        put("end_sample", rep.endSample)
                        put("concentric_start_sample", rep.concentricPhase.startSample)
                        put("concentric_end_sample", rep.concentricPhase.endSample)
                        put("reasons", JSONArray(rep.reasons.map { it.name }))
                        put("metrics", JSONArray(rep.metrics.map { metric ->
                            JSONObject().apply {
                                put("key", metric.key.name)
                                put("value", metric.value ?: JSONObject.NULL)
                                put("unit", metric.unit.name)
                                put("validity", metric.validity.name)
                                put("reason", metric.reason?.name ?: JSONObject.NULL)
                            }
                        }))
                    }
                    repetitionsJson.put(repJson)
                }
                diagnostics.put("stage", "analysis")
                diagnostics.put("total_repetitions", analysis.repetitions.size)
                diagnostics.put("valid_repetitions", analysis.validRepetitions.size)
                diagnostics.put("repetitions", repetitionsJson)
                persistDiagnostics()

                assertEquals(3, analysis.repetitions.size)
                assertEquals(3, analysis.validRepetitions.size)
                analysis.validRepetitions.forEach { rep ->
                    assertTrue("behavior regression must retain a valid repetition", rep.reasons.isEmpty())
                    val concentricTime = rep.metric(EncoderMetricKey.CONCENTRIC_TIME)?.value
                        ?: error("concentric time must remain present")
                    assertTrue("concentric time must remain finite and positive", concentricTime.isFinite() && concentricTime > 0.0)
                    // PTS-derived behavior output-lock, not instrumental accuracy.
                    val localIntervalSeconds = localPhysicalPtsIntervalSeconds(
                        index = index,
                        sampleIndex = rep.concentricPhase.startSample,
                        slowMotionFactor = timingDecision.slowMotionFactor,
                    )
                    assertTrue("PTS behavior output-lock", abs(concentricTime - 0.80) <= localIntervalSeconds)
                }
                diagnostics.put("stage", "assertions_passed")
                persistDiagnostics()
            } finally {
                diagnostics.put("stage", "runner_finally")
                persistDiagnostics()
                runner.close()
            }
        } finally {
            diagnostics.put("cleanup_complete", true)
            persistDiagnostics()
            try {
                if (sourceCreated) source.close()
            } finally {
                EncoderValidation.cleanupVerifiedFixture(verifiedCache)
            }
        }
    }

    private fun localPhysicalPtsIntervalSeconds(
        index: VideoFrameIndex,
        sampleIndex: Int,
        slowMotionFactor: Double,
    ): Double {
        val adjacentDeltasUs = buildList<Long> {
            if (sampleIndex > 0) add(index.timeOf(sampleIndex) - index.timeOf(sampleIndex - 1))
            if (sampleIndex + 1 < index.frameCount) add(index.timeOf(sampleIndex + 1) - index.timeOf(sampleIndex))
        }
        val deltaUs = adjacentDeltasUs.firstOrNull { it > 0L }
            ?: error("No positive adjacent PTS interval at sample $sampleIndex")
        return deltaUs / 1_000_000.0 / slowMotionFactor
    }

    /* trace serialization removed: rollback trace is passive and bounded. */
    private fun unusedTraceSerializationMarker() = Unit

    /*
    private fun PlateDetectionTrace.toJson(): JSONObject = JSONObject().apply {
        put("frame", "${frameWidth}x$frameHeight")
        put("edge_threshold", edgeThreshold ?: JSONObject.NULL)
        put("edges_sampled", edgesSampled)
        put("pairs_attempted", pairsAttempted)
        put("rejected_parallel", rejectedParallel)
        put("rejected_radius", rejectedRadius)
        put("rejected_tap", rejectedTap)
        put("rejected_evidence", rejectedEvidence)
        put("peaks", JSONArray(peaks.map { peak ->
            JSONObject().apply {
                put("rank", peak.rank)
                put("local_x", peak.localX)
                put("local_y", peak.localY)
                put("frame_x", peak.frameX)
                put("frame_y", peak.frameY)
                put("consensus", peak.votes)
                put("center_x", peak.centerX)
                put("center_y", peak.centerY)
                put("radius", peak.radius)
                put("support", peak.support)
                put("covered_sectors", peak.coveredSectors)
                put("score", peak.score)
            }
        }))
        put("reference_hypothesis", referenceHypothesis?.let { reference ->
            JSONObject().apply {
                put("center_x", reference.centerX)
                put("center_y", reference.centerY)
                put("diameter_px", reference.diameterPx)
                put("support", reference.support)
                put("alignment", reference.alignment)
                put("covered_sectors", reference.coveredSectors)
                put("perspective_ratio", reference.perspectiveRatio)
                put("rejection", reference.rejection ?: JSONObject.NULL)
            }
        } ?: JSONObject.NULL)
    }

    }
    */

    companion object {
        const val INT07_SHA256 = "2de089422db77a9ff6ed0fd216a840396aaacba71a7d02a1204e74983317c0f3"
        const val INT07_DECLARED_REFERENCE_LENGTH_M = 0.400
        const val INT07_DECLARED_DIAMETER_PX = 344.0
    }
}
