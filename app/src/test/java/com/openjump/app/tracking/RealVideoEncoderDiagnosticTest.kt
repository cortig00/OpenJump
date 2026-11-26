package com.openjump.app.tracking

import com.openjump.app.encoder.EncoderAnalyzer
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.GeometricPlateDetector
import com.openjump.app.encoder.MetricCalibrator
import com.openjump.app.encoder.PlateDetectionOutcome
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import java.io.File
import kotlin.math.hypot
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in real-video diagnostic harness. Skips unless `-Dopenjump.realVideoDir=<path>` is provided.
 */
class RealVideoEncoderDiagnosticTest {

    private data class RealClipSpec(
        val name: String,
        val exercise: EncoderExercise,
        val loadKg: Double,
        val plateDiameterCm: Double,
        val expectedReps: Int,
        val startFrame: Int,
        val approxTapFracX: Double,
        val approxTapFracY: Double,
        val fallbackDiamFracW: Double,
        val useManualCircle: Boolean = false,
    )

    @Test
    fun `evaluate real gym videos AB baseline vs improved`() = runBlocking {
        val dirPath = System.getenv("OPENJUMP_REAL_VIDEO_DIR") ?: System.getProperty("openjump.realVideoDir")
        assumeTrue("Set OPENJUMP_REAL_VIDEO_DIR to run real video diagnostics", !dirPath.isNullOrBlank())
        val root = File(dirPath!!)
        assumeTrue("Directory does not exist: $dirPath", root.isDirectory)

        val specs = listOf(
            RealClipSpec(
                name = "pesomuerto",
                exercise = EncoderExercise.DEADLIFT,
                loadKg = 50.0,
                plateDiameterCm = 35.0,
                expectedReps = 1,
                startFrame = 0,
                approxTapFracX = 0.52,
                approxTapFracY = 0.56,
                fallbackDiamFracW = 0.46,
                useManualCircle = false,
            ),
            RealClipSpec(
                name = "pressbanca",
                exercise = EncoderExercise.BENCH_PRESS,
                loadKg = 70.0,
                plateDiameterCm = 40.0,
                expectedReps = 15,
                startFrame = 80, // Bar unracked, ready for Rep 1
                approxTapFracX = 0.6448, // Exact 20kg plate center (412/639)
                approxTapFracY = 0.3733, // Exact 20kg plate center (134/359)
                fallbackDiamFracW = 0.2066, // Exact 20kg plate diameter (132/639)
                useManualCircle = true, // Simulate user confirming exact plate circle in PLATE_REVIEW
            ),
        )

        val report = StringBuilder()
        for (spec in specs) {
            report.appendLine("================================================================")
            report.appendLine("VIDEO: ${spec.name} (startFrame=${spec.startFrame}) | ${spec.exercise} | ${spec.loadKg}kg | plate=${spec.plateDiameterCm}cm | expectedReps=${spec.expectedReps}")
            report.appendLine("================================================================")

            // Detect plate on 720px frame first (just like production PlateDetection)
            val clip720 = StreamingClip(File(root, "${spec.name}_720"))
            val detector = GeometricPlateDetector()
            val frame0 = clip720.frameAt(spec.startFrame).image
            clip720.close()
            val autoOutcome = detector.detectAutomatic(frame0)
            val approxTap = ImagePoint(frame0.width * spec.approxTapFracX, frame0.height * spec.approxTapFracY)
            val assistedOutcome = detector.detectAssisted(frame0, approxTap)

            report.appendLine("AUTO detection (720px): ${describeOutcome(autoOutcome)}")
            report.appendLine("ASSISTED detection (720px @ ${approxTap.x.toInt()},${approxTap.y.toInt()}): ${describeOutcome(assistedOutcome)}")

            val chosenDetection = if (spec.useManualCircle) {
                null
            } else {
                (assistedOutcome as? PlateDetectionOutcome.Found)?.detection
                    ?: (autoOutcome as? PlateDetectionOutcome.Found)?.detection
            }
            val normCenterX = (chosenDetection?.center?.x ?: approxTap.x) / (frame0.width - 1.0)
            val normCenterY = (chosenDetection?.center?.y ?: approxTap.y) / (frame0.height - 1.0)
            val normDiameter = (chosenDetection?.diameterPx ?: (frame0.width * spec.fallbackDiamFracW)) / (frame0.width - 1.0)
            report.appendLine(
                "Seed normalized: center=(%.4f, %.4f) diameterFracW=%.4f (perspective=%.3f)".format(
                    normCenterX, normCenterY, normDiameter, chosenDetection?.perspectiveRatio ?: -1.0,
                ),
            )

            val runs = listOf(
                Triple("v1.3 Baseline 720px", 720, false),
                Triple("v1.3 Baseline 640px", 640, false),
                Triple("Improved 640px", 640, true),
                Triple("Improved 480px", 480, true),
            )

            for ((label, res, improved) in runs) {
                val source = StreamingClip(File(root, "${spec.name}_$res"))
                val w = source.width
                val h = source.height
                val totalFrameCount = source.frameCount - spec.startFrame
                val seed = ImagePoint(normCenterX * (w - 1.0), normCenterY * (h - 1.0))
                val plateDiamPx = normDiameter * (w - 1.0)

                val rimSig = CircularPlateReanchor.learnSignature(source.frameAt(spec.startFrame).image, seed, plateDiamPx / 2.0)
                val tracker = PyramidalKltMotionTracker(
                    config = PyramidalKltMotionTracker.Config(
                        targetDiameterPx = if (improved) plateDiamPx else null,
                        enableCircularReanchor = improved,
                    ),
                )
                val runner = OfflineVideoTracker(source, tracker)
                val t0 = System.nanoTime()
                val results = runner.use {
                    it.initialize(spec.startFrame, seed)
                    it.trackRemaining { _, _, _ -> }
                }
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

                val trackingCount = results.count { it.result.sample.status == TrackingStatus.TRACKING }
                val uncertainCount = results.count { it.result.sample.status == TrackingStatus.UNCERTAIN }
                val lostCount = results.count { it.result.sample.status == TrackingStatus.LOST }

                val calibration = MetricCalibrator.create(
                    frameIndex = 0,
                    pointA = ImagePoint(seed.x - plateDiamPx / 2.0, seed.y),
                    pointB = ImagePoint(seed.x + plateDiamPx / 2.0, seed.y),
                    referenceLengthM = spec.plateDiameterCm / 100.0,
                )
                val analysis = EncoderAnalyzer.analyze(
                    setup = EncoderSetup(spec.exercise, spec.loadKg),
                    timingDecision = VideoTimingDecision(VideoTimingMode.REAL_TIME),
                    calibration = calibration,
                    tracking = results,
                )

                val firstPt = results.firstNotNullOfOrNull { it.result.sample.point }
                val lastPt = results.lastOrNull { it.result.sample.point != null }?.result?.sample?.point
                val endOffsetPx = if (firstPt != null && lastPt != null) {
                    hypot(lastPt.x - firstPt.x, lastPt.y - firstPt.y)
                } else -1.0
                val endOffsetCm = endOffsetPx * calibration.metersPerPixel * 100.0

                report.appendLine(
                    "  %-22s | %dx%d | %.0fms (%.2fms/f) | rimSig=%s | frames=%d/%d (T=%d U=%d L=%d) | endDelta=%.1fpx(%.1fcm) | vEnter=%.3fm/s | reps=%d/%d"
                        .format(
                            label, w, h, elapsedMs, elapsedMs / results.size.coerceAtLeast(1),
                            if (improved) (if (rimSig != null) "YES(pol=${rimSig.expectedPolarity.toInt()},g=${rimSig.baselineGradient.toInt()})" else "NO") else "off",
                            results.size, totalFrameCount, trackingCount, uncertainCount, lostCount,
                            endOffsetPx, endOffsetCm, analysis.velocityEnterMps ?: -1.0, analysis.repetitions.size, spec.expectedReps,
                        ),
                )
                analysis.repetitions.forEach { rep ->
                    val rom = rep.metrics.firstOrNull { it.key == EncoderMetricKey.ROM }?.value?.times(100.0) ?: Double.NaN
                    val mcv = rep.metrics.firstOrNull { it.key == EncoderMetricKey.MCV }?.value ?: Double.NaN
                    val peak = rep.metrics.firstOrNull { it.key == EncoderMetricKey.PEAK_VELOCITY }?.value ?: Double.NaN
                    val vloss = rep.metrics.firstOrNull { it.key == EncoderMetricKey.VELOCITY_LOSS }?.value
                    report.appendLine(
                        "      Rep %2d [%s]: ROM=%.1fcm | MCV=%.2fm/s | Vp=%.2fm/s%s | reasons=%s".format(
                            rep.ordinal + 1,
                            rep.quality.name,
                            rom,
                            mcv,
                            peak,
                            vloss?.let { " | Vloss=%.1f%%".format(it) } ?: "",
                            rep.reasons.joinToString(","),
                        ),
                    )
                }

                // Export trajectory CSV for 640px runs so we can overlay visually on actual frames
                if (res == 640) {
                    val tag = if (improved) "improved_640" else "baseline_640"
                    val csv = File(root, "${spec.name}_${tag}.csv")
                    csv.printWriter().use { pw ->
                        pw.println("frameIndex,x,y,status,confidence,plateDiamPx")
                        results.forEach { r ->
                            val p = r.result.sample.point
                            pw.println("${r.frameIndex},${p?.x ?: ""},${p?.y ?: ""},${r.result.sample.status},${r.result.sample.confidence},$plateDiamPx")
                        }
                    }
                }
            }
        }

        val outFile = File(root, "diagnostic_report.txt")
        outFile.writeText(report.toString())
        println(report.toString())
    }

    private fun describeOutcome(outcome: PlateDetectionOutcome): String = when (outcome) {
        is PlateDetectionOutcome.Found -> {
            val d = outcome.detection
            "FOUND center=(%.1f, %.1f) diam=%.1fpx conf=%.3f persp=%.3f autoOK=%s src=%s".format(
                d.center.x, d.center.y, d.diameterPx, d.confidence, d.perspectiveRatio, d.autoCalibrationAccepted, d.source,
            )
        }
        is PlateDetectionOutcome.Ambiguous -> "AMBIGUOUS candidates=${outcome.candidateCount} bestConf=${outcome.bestConfidence}"
        is PlateDetectionOutcome.NotFound -> "NOT_FOUND (${outcome.reason})"
    }

    private class StreamingClip(dir: File) : TrackingFrameSource {
        val width: Int
        val height: Int
        private val pts: List<Long>
        private val frameBytes: Int
        private val raf: java.io.RandomAccessFile
        override val frameCount: Int

        init {
            val dims = File(dir, "dims.txt").readText().trim().split(Regex("\\s+")).map { it.toInt() }
            width = dims[0]
            height = dims[1]
            pts = File(dir, "pts_us.txt").readLines().mapNotNull { it.trim().toLongOrNull() }
            frameBytes = width * height
            val rawFile = File(dir, "frames.gray")
            raf = java.io.RandomAccessFile(rawFile, "r")
            frameCount = minOf(pts.size, (rawFile.length() / frameBytes).toInt())
        }

        override suspend fun frameAt(index: Int): DecodedTrackingFrame {
            val buf = ByteArray(frameBytes)
            raf.seek(index.toLong() * frameBytes)
            raf.readFully(buf)
            return DecodedTrackingFrame(
                frameIndex = index,
                timestampUs = pts[index],
                image = GrayFrame(width, height, buf),
                presentationWidth = width,
                presentationHeight = height,
            )
        }

        override suspend fun forEachFrame(
            startIndex: Int,
            onFrame: suspend (DecodedTrackingFrame) -> Boolean,
        ) {
            raf.seek(startIndex.toLong() * frameBytes)
            for (i in startIndex until frameCount) {
                val buf = ByteArray(frameBytes)
                raf.readFully(buf)
                val frame = DecodedTrackingFrame(
                    frameIndex = i,
                    timestampUs = pts[i],
                    image = GrayFrame(width, height, buf),
                    presentationWidth = width,
                    presentationHeight = height,
                )
                if (!onFrame(frame)) break
            }
        }

        override fun close() {
            raf.close()
        }
    }
}
