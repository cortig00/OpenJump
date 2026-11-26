package com.openjump.app.tracking

import com.openjump.app.encoder.EncoderAnalyzer
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.GeometricPlateDetector
import com.openjump.app.encoder.MetricCalibrator
import com.openjump.app.encoder.PlateDetectionOutcome
import com.openjump.app.encoder.RepetitionQuality
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end validation of the bundled `demo_encoder_squat_sample.mp4` resource through the
 * production plate detector, pyramidal KLT tracker with circular rim re-anchor, and repetition
 * segmenter/analyzer.
 */
class DemoEncoderSampleValidationTest {

    private fun resolveDemoMp4(): File {
        val candidates = listOf(
            File("src/main/res/raw/demo_encoder_squat_sample.mp4"),
            File("app/src/main/res/raw/demo_encoder_squat_sample.mp4"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("Bundled demo_encoder_squat_sample.mp4 not found")
    }

    @Test
    fun `bundled encoder demo video tracks cleanly and produces 3 progressive squat reps`() = runBlocking {
        val mp4 = resolveDemoMp4()
        assertTrue("Demo video should stay lightweight (<300 KB)", mp4.length() in 20_000L..300_000L)

        val ffmpegAvailable = runCatching {
            ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0
        }.getOrDefault(false)
        assumeTrue("ffmpeg not installed in this test environment", ffmpegAvailable)

        val width = 360
        val height = 640
        val fps = 60.0
        val frameSize = width * height
        val rawBytes = ProcessBuilder(
            "ffmpeg", "-v", "error", "-i", mp4.absolutePath,
            "-f", "rawvideo", "-pix_fmt", "gray", "-",
        ).start().let { proc ->
            val bytes = proc.inputStream.readBytes()
            assertEquals("ffmpeg decode exit code", 0, proc.waitFor())
            bytes
        }
        val frameCount = rawBytes.size / frameSize
        assertEquals("Expected 420 frames (7 s @ 60 fps)", 420, frameCount)

        val source = object : TrackingFrameSource {
            override val frameCount: Int = frameCount
            override suspend fun frameAt(index: Int): DecodedTrackingFrame {
                val offset = index * frameSize
                val slice = rawBytes.copyOfRange(offset, offset + frameSize)
                return DecodedTrackingFrame(
                    frameIndex = index,
                    timestampUs = ((index * 1_000_000.0) / fps).toLong(),
                    image = GrayFrame(width, height, slice),
                    presentationWidth = width,
                    presentationHeight = height,
                )
            }
            override suspend fun forEachFrame(
                startIndex: Int,
                onFrame: suspend (DecodedTrackingFrame) -> Boolean,
            ) {
                for (i in startIndex until frameCount) {
                    if (!onFrame(frameAt(i))) break
                }
            }
            override fun close() = Unit
        }

        // 1. Plate detection on frame 0 (automatic + assisted tap near the 45 cm plate center)
        val frame0 = source.frameAt(0).image
        val detector = GeometricPlateDetector()
        val auto = detector.detectAutomatic(frame0)
        val assisted = detector.detectAssisted(frame0, ImagePoint(186.0, 259.0))
        val detection = (assisted as? PlateDetectionOutcome.Found)?.detection
            ?: (auto as? PlateDetectionOutcome.Found)?.detection
        assertNotNull("Expected plate detection on frame 0 (auto=$auto, assisted=$assisted)", detection)
        val found = detection!!
        assertTrue(
            "Detected diameter should be close to 79.2 px (45 cm @ 176 px/m), got ${found.diameterPx}",
            abs(found.diameterPx - 79.2) <= 5.0,
        )

        // 2. Full tracking through PyramidalKltMotionTracker + CircularPlateReanchor
        val tracker = PyramidalKltMotionTracker(
            config = PyramidalKltMotionTracker.Config(
                targetDiameterPx = found.diameterPx,
                enableCircularReanchor = true,
            ),
        )
        val results = OfflineVideoTracker(source, tracker).use { runner ->
            runner.initialize(0, found.center)
            runner.trackRemaining { _, _, _ -> }
        }
        val lost = results.count { it.result.sample.status == TrackingStatus.LOST }
        assertEquals("Demo video should have 0 lost frames", 0, lost)

        // 3. Calibration (45 cm Olympic plate) + EncoderAnalyzer (80 kg Back Squat)
        val calibration = MetricCalibrator.create(
            frameIndex = 0,
            pointA = ImagePoint(found.center.x - found.diameterPx / 2.0, found.center.y),
            pointB = ImagePoint(found.center.x + found.diameterPx / 2.0, found.center.y),
            referenceLengthM = 0.45,
        )
        val firstPt = results.first().result.sample.point!!
        val lastPt = results.last().result.sample.point!!
        val driftMm = hypot(lastPt.x - firstPt.x, lastPt.y - firstPt.y) * calibration.metersPerPixel * 1000.0

        val analysis = EncoderAnalyzer.analyze(
            setup = EncoderSetup(EncoderExercise.SQUAT, 80.0),
            timingDecision = VideoTimingDecision(VideoTimingMode.REAL_TIME),
            calibration = calibration,
            tracking = results,
        )

        val validReps = analysis.repetitions.filter { it.quality == RepetitionQuality.VALID }
        val repMcvs = validReps.mapNotNull { rep -> rep.metrics.firstOrNull { it.key == EncoderMetricKey.MCV }?.value }
        val repRoms = validReps.mapNotNull { rep -> rep.metrics.firstOrNull { it.key == EncoderMetricKey.ROM }?.value }
        println(
            "DEMO_SQUAT_REPORT: auto=${auto::class.simpleName} assisted=${assisted::class.simpleName} " +
                "diamPx=${"%.2f".format(found.diameterPx)} driftMm=${"%.2f".format(driftMm)} " +
                "reps=${validReps.size}/${analysis.repetitions.size} " +
                "mcvs=${repMcvs.map { "%.3f".format(it) }} roms=${repRoms.map { "%.3f".format(it) }}",
        )

        assertTrue("Automatic plate detection should succeed on demo frame 0 (got $auto)", auto is PlateDetectionOutcome.Found)
        assertTrue("Final drift should be < 10 mm (~1.7 px), got $driftMm mm", driftMm < 10.0)
        assertEquals("Expected exactly 3 valid squat repetitions", 3, validReps.size)
        assertTrue("Expected progressive velocity loss: $repMcvs", repMcvs[0] > repMcvs[1] && repMcvs[1] > repMcvs[2])
        repRoms.forEach { rom ->
            assertTrue("Expected squat ROM ~0.56 m (0.48..0.64 m), got $rom", rom in 0.48..0.64)
        }
    }
}
