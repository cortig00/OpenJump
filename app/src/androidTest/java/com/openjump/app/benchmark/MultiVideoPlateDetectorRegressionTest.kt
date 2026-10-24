package com.openjump.app.benchmark

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.encoder.GeometricPlateDetector
import com.openjump.app.encoder.PlateDetectionOutcome
import com.openjump.app.encoder.PlateDetectionSource
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.video.VideoFrameIndexer
import com.openjump.app.video.VideoTrackingFrameSource
import com.openjump.app.video.toPresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in detector-only corpus regression; no args means an explicit safe skip. */
@RunWith(AndroidJUnit4::class)
class MultiVideoPlateDetectorRegressionTest {
    private data class Case(
        val arg: String,
        val name: String,
        val sha256: String,
        val tap: ImagePoint,
        val center: ImagePoint,
        val diameter: Double,
        val tolerance: Double,
        val frameCount: Int,
        val oracleProvisional: Boolean,
    )

    @Test
    fun detectorCorpusRegression() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val cases = listOf(
            Case("int07_fixture", "int07", "2de089422db77a9ff6ed0fd216a840396aaacba71a7d02a1204e74983317c0f3", ImagePoint(480.0, 930.0), ImagePoint(488.0, 871.0), 344.0, 8.0, 584, false),
            Case("full_fixture", "full", "1256d70cba86c0347d983190410e5af119bba416f019b3e7dfc04bb22cf69980", ImagePoint(470.0, 843.0), ImagePoint(470.0, 843.0), 376.0, 12.0, 925, true),
            Case("cropped_fixture", "cropped", "6868e66ce0f67c21f10e89987a359a7472b4ab4b6e7907bcaa4dab194930a279", ImagePoint(617.0, 847.0), ImagePoint(617.0, 847.0), 374.0, 12.0, 316, true),
        )
        val supplied = cases.filter { args.getString(it.arg) != null }
        assumeTrue("detector corpus is opt-in; supply one or more fixture args", supplied.isNotEmpty())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = JSONArray()
        val failures = mutableListOf<String>()
        val reportFile = File(context.filesDir, "multi_video_plate_detector_regression.json")

        try {
            supplied.forEach { spec ->
                val caseReport = JSONObject().apply {
                    put("name", spec.name)
                    put("argument", spec.arg)
                    put("path", args.getString(spec.arg) ?: JSONObject.NULL)
                    put("sha256", spec.sha256)
                    put("oracle", "center=${spec.center.x},${spec.center.y};diameter=${spec.diameter}")
                    put("oracle_provisional", spec.oracleProvisional)
                    put("status", "RUNNING")
                    put("errors", JSONArray())
                }
                report.put(caseReport)
                val caseFailures = mutableListOf<String>()
                var cache: EncoderValidation.VerifiedFixtureCache? = null
                var source: VideoTrackingFrameSource? = null

                fun recordFailure(message: String) {
                    caseFailures += message
                    failures += "${spec.name}: $message"
                    caseReport.getJSONArray("errors").put(message)
                }

                try {
                    val path = args.getString(spec.arg)
                        ?: throw IllegalArgumentException("argument ${spec.arg} is missing")
                    val input = File(path)
                    caseReport.put("bytes", if (input.isFile) input.length() else JSONObject.NULL)
                    if (!input.isFile || !input.canRead()) {
                        throw IllegalArgumentException("fixture is missing or unreadable: $path")
                    }
                    val actualSha256 = EncoderValidation.sha256(input)
                    caseReport.put("actual_sha256", actualSha256)
                    if (actualSha256 != spec.sha256) {
                        throw IllegalArgumentException(
                            "SHA-256 mismatch: expected ${spec.sha256}, got $actualSha256",
                        )
                    }
                    caseReport.put("stage", "fixture_verified")

                    cache = EncoderValidation.copyVerifiedFixture(
                        input, File(context.cacheDir, "trajectory_exports"), spec.sha256, "detector-${spec.name}",
                    )
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        cache!!.file,
                    )
                    val index = VideoFrameIndexer.indexFromUri(context, uri)
                    caseReport.put("index", "${index.frameCount} frames/${index.width}x${index.height}")
                    if (index.frameCount != spec.frameCount) {
                        throw IllegalStateException(
                            "frame count expected ${spec.frameCount}, got ${index.frameCount}",
                        )
                    }
                    if (index.frameCount == 0 || index.timeOf(0) != 0L) {
                        throw IllegalStateException("index first PTS expected 0, got ${index.timeOf(0)}")
                    }

                    source = VideoTrackingFrameSource(context, uri, index)
                    val frame0 = source!!.frameAt(0)
                    if (frame0.timestampUs != 0L) {
                        throw IllegalStateException("decoded first PTS expected 0, got ${frame0.timestampUs}")
                    }
                    val image = frame0.image // shared immutable GrayFrame for AUTO and ASSISTED
                    caseReport.put("presentation", "${frame0.presentationWidth}x${frame0.presentationHeight}")
                    caseReport.put("tracking", "${image.width}x${image.height}")
                    caseReport.put("frame_ordinal", 0)
                    caseReport.put("pts_us", frame0.timestampUs)
                    caseReport.put("stage", "detecting")

                    val tapTracking = frame0.toTracking(spec.tap)
                    val autoStarted = System.nanoTime()
                    val auto = GeometricPlateDetector().detectAutomatic(image)
                    val autoMs = (System.nanoTime() - autoStarted) / 1_000_000.0
                    val assistedStarted = System.nanoTime()
                    val assisted = GeometricPlateDetector().detectAssisted(image, tapTracking)
                    val assistedMs = (System.nanoTime() - assistedStarted) / 1_000_000.0
                    caseReport.put("auto", outcomeJson(auto, frame0, autoMs))
                    caseReport.put("assisted", outcomeJson(assisted, frame0, assistedMs))
                    caseReport.put("stage", "outcomes_recorded")

                    val assistedFound = assisted as? PlateDetectionOutcome.Found
                    if (assistedFound == null) {
                        recordFailure("ASSISTED expected Found, got $assisted")
                    } else {
                        val detection = assistedFound.detection.toPresentation(frame0)
                        if (!within(detection.center, spec.center, spec.tolerance) ||
                            kotlin.math.abs(detection.diameterPx - spec.diameter) > spec.tolerance
                        ) {
                            recordFailure(
                                "ASSISTED geometry ${detection.center}/${detection.diameterPx} outside oracle",
                            )
                        }
                        recordCircleSafetyFailure("ASSISTED", detection, ::recordFailure)
                    }
                    val autoFound = auto as? PlateDetectionOutcome.Found
                    if (autoFound != null) {
                        val detection = autoFound.detection.toPresentation(frame0)
                        recordFailure(
                            "AUTO false/out-of-bounds Found ${detection.center}/${detection.diameterPx}",
                        )
                        recordCircleSafetyFailure("AUTO", detection, ::recordFailure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    recordFailure("${error::class.simpleName}: ${error.message ?: "no message"}")
                } finally {
                    var closeCancellation: CancellationException? = null
                    try {
                        source?.close()
                    } catch (cancelled: CancellationException) {
                        closeCancellation = cancelled
                    } catch (error: Throwable) {
                        recordFailure("source close ${error::class.simpleName}: ${error.message ?: "no message"}")
                    }
                    try {
                        EncoderValidation.cleanupVerifiedFixture(cache)
                    } finally {
                        caseReport.put("status", if (caseFailures.isEmpty()) "PASS" else "FAIL")
                    }
                    closeCancellation?.let { throw it }
                }
            }
        } finally {
            EncoderValidation.atomicWrite(reportFile, report.toString(2))
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    private fun outcomeJson(
        outcome: PlateDetectionOutcome,
        frame: com.openjump.app.tracking.DecodedTrackingFrame,
        wallMs: Double,
    ) = JSONObject().apply {
        put("outcome", outcome::class.simpleName)
        put("reason", (outcome as? PlateDetectionOutcome.NotFound)?.reason ?: JSONObject.NULL)
        put("candidate_count", when (outcome) {
            is PlateDetectionOutcome.Found -> outcome.candidateCount
            is PlateDetectionOutcome.Ambiguous -> outcome.candidateCount
            is PlateDetectionOutcome.NotFound -> outcome.candidateCount
        })
        put("wall_ms", wallMs)
        put("detector_ms", outcome.diagnostics.contourScanMs + outcome.diagnostics.geometricRefinementMs)
        put("diagnostics", JSONObject().apply {
            put("contour_scan_ms", outcome.diagnostics.contourScanMs)
            put("geometric_refinement_ms", outcome.diagnostics.geometricRefinementMs)
            put("candidates_rejected_by_edge", outcome.diagnostics.candidatesRejectedByEdge)
            put("best_source", outcome.diagnostics.bestSource?.name ?: JSONObject.NULL)
            put("candidate_diameters_px", JSONArray(outcome.diagnostics.candidateDiametersPx))
            put("candidate_sources", JSONArray(outcome.diagnostics.candidateSources.map { it.name }))
            put("circle_diameters_passing_gates_px", JSONArray(outcome.diagnostics.circleDiametersPassingGatesPx))
        })
        (outcome as? PlateDetectionOutcome.Found)?.detection?.let { detection ->
            val presentation = detection.toPresentation(frame)
            put("center_x", presentation.center.x)
            put("center_y", presentation.center.y)
            put("diameter_px", presentation.diameterPx)
            put("source", presentation.source.name)
            put("confidence", presentation.confidence)
            put("perspective_ratio", presentation.perspectiveRatio)
            put("auto_calibration_accepted", presentation.autoCalibrationAccepted)
            put("circle_safety_valid", circleSafetyValid(presentation))
        }
    }

    private fun recordCircleSafetyFailure(
        mode: String,
        detection: com.openjump.app.encoder.PlateDetection,
        recordFailure: (String) -> Unit,
    ) {
        if (detection.source == PlateDetectionSource.GRADIENT_CIRCLE &&
            !circleSafetyValid(detection)
        ) {
            recordFailure(
                "$mode GRADIENT_CIRCLE safety violation: " +
                    "perspectiveRatio=${detection.perspectiveRatio}, " +
                    "autoCalibrationAccepted=${detection.autoCalibrationAccepted}",
            )
        }
    }

    private fun circleSafetyValid(detection: com.openjump.app.encoder.PlateDetection): Boolean =
        detection.source != PlateDetectionSource.GRADIENT_CIRCLE ||
            (detection.perspectiveRatio == 0.0 && !detection.autoCalibrationAccepted)

    private fun within(actual: ImagePoint, expected: ImagePoint, tolerance: Double) =
        kotlin.math.abs(actual.x - expected.x) <= tolerance && kotlin.math.abs(actual.y - expected.y) <= tolerance

}
