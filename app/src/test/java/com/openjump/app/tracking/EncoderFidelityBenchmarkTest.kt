package com.openjump.app.tracking

import com.openjump.app.encoder.EncoderAnalyzer
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.MetricCalibrator
import com.openjump.app.encoder.RepetitionQuality
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quantitative A/B fidelity benchmark comparing:
 *  - Baseline v1.3 KLT (fixed square ROI, no circular mask, no rim re-anchor)
 *  - Improved KLT (calibrated circular ROI + sub-pixel circular rim anti-drift re-anchor)
 *
 * Uses exact analytical barbell kinematics (ROM, mean concentric velocity, peak velocity) rendered
 * as a rotating olympic plate moving in front of high-contrast vertical rack uprights with optional
 * vertical motion blur and sensor noise, then runs the full production [EncoderAnalyzer] pipeline.
 */
class EncoderFidelityBenchmarkTest {

    private data class GroundTruthSample(
        val frameIndex: Int,
        val timestampUs: Long,
        val trueCenterX: Double,
        val trueCenterY: Double,
        val trueVelocityYMps: Double,
        val plateAngleRad: Double,
    )

    private data class BenchmarkMetrics(
        val label: String,
        val trackedFrames: Int,
        val totalFrames: Int,
        val lostCount: Int,
        val maePositionMm: Double,
        val maxPositionErrorMm: Double,
        val finalDriftMm: Double,
        val detectedReps: Int,
        val validReps: Int,
        val meanRomCm: Double,
        val romErrorCm: Double,
        val meanMcvMps: Double,
        val mcvErrorMps: Double,
        val meanPeakVelMps: Double,
        val peakVelErrorMps: Double,
    )

    @Test
    fun `AB benchmark 8-rep rotating plate with rack background proves lower drift and higher fidelity`() = runBlocking {
        val scenario = buildSquatSetScenario(
            width = 640,
            height = 360,
            fps = 60,
            reps = 8,
            romMeters = 0.65,
            eccentricSec = 1.0,
            concentricSec = 0.85,
            pauseSec = 0.15,
            plateDiameterPx = 72.0,
            spinRadPerRep = 1.4,
            motionBlurStrength = 0.35,
            rackUprights = true,
            noiseAmplitude = 4,
        )

        val baseline = evaluateTracker(
            label = "Baseline v1.3 (square ROI, no re-anchor)",
            scenario = scenario,
            tracker = PyramidalKltMotionTracker(
                config = PyramidalKltMotionTracker.Config(
                    targetDiameterPx = null,
                    enableCircularReanchor = false,
                ),
            ),
        )
        val improved = evaluateTracker(
            label = "Improved (circular ROI + rim re-anchor)",
            scenario = scenario,
            tracker = PyramidalKltMotionTracker(
                config = PyramidalKltMotionTracker.Config(
                    targetDiameterPx = scenario.plateDiameterPx,
                    enableCircularReanchor = true,
                ),
            ),
        )

        println("=== A/B BENCHMARK: 8-rep rotating plate + rack uprights (640x360 @ 60fps) ===")
        println(formatReport(baseline))
        println(formatReport(improved))

        assertEquals("Improved tracker must complete all frames without LOST", 0, improved.lostCount)
        assertEquals("Improved tracker must detect all 8 reps", 8, improved.detectedReps)
        assertTrue(
            "Improved MAE (${improved.maePositionMm} mm) must be <= 4.0 mm and beat baseline (${baseline.maePositionMm} mm)",
            improved.maePositionMm <= 4.0 && improved.maePositionMm <= baseline.maePositionMm,
        )
        assertTrue(
            "Improved final drift (${improved.finalDriftMm} mm) must be <= 5.0 mm and beat baseline (${baseline.finalDriftMm} mm)",
            improved.finalDriftMm <= 5.0 && improved.finalDriftMm <= baseline.finalDriftMm,
        )
        assertTrue("Improved ROM error (${improved.romErrorCm} cm) must be <= 1.2 cm", improved.romErrorCm <= 1.2)
        assertTrue("Improved MCV error (${improved.mcvErrorMps} m/s) must be <= 0.04 m/s", improved.mcvErrorMps <= 0.04)
        assertTrue("Improved Peak V error (${improved.peakVelErrorMps} m/s) must be <= 0.08 m/s", improved.peakVelErrorMps <= 0.08)
    }

    @Test
    fun `AB benchmark fast explosive lift with motion blur preserves velocity fidelity`() = runBlocking {
        val scenario = buildSquatSetScenario(
            width = 640,
            height = 360,
            fps = 60,
            reps = 4,
            romMeters = 0.60,
            eccentricSec = 0.80,
            concentricSec = 0.50, // ~1.20 m/s mean concentric, ~1.88 m/s peak!
            pauseSec = 0.15,
            plateDiameterPx = 68.0,
            spinRadPerRep = 2.0,
            motionBlurStrength = 0.65,
            rackUprights = true,
            noiseAmplitude = 5,
        )

        val baseline = evaluateTracker(
            label = "Baseline v1.3 (explosive 1.2 m/s + blur)",
            scenario = scenario,
            tracker = PyramidalKltMotionTracker(
                config = PyramidalKltMotionTracker.Config(targetDiameterPx = null, enableCircularReanchor = false),
            ),
        )
        val improved = evaluateTracker(
            label = "Improved (explosive 1.2 m/s + blur)",
            scenario = scenario,
            tracker = PyramidalKltMotionTracker(
                config = PyramidalKltMotionTracker.Config(
                    targetDiameterPx = scenario.plateDiameterPx,
                    enableCircularReanchor = true,
                ),
            ),
        )

        println("=== A/B BENCHMARK: Fast explosive 1.2 m/s + motion blur + spin ===")
        println(formatReport(baseline))
        println(formatReport(improved))

        assertEquals(0, improved.lostCount)
        assertEquals(4, improved.detectedReps)
        assertTrue("MAE ${improved.maePositionMm} mm <= 6.0 mm", improved.maePositionMm <= 6.0)
        assertTrue("ROM error ${improved.romErrorCm} cm <= 1.5 cm", improved.romErrorCm <= 1.5)
        assertTrue("MCV error ${improved.mcvErrorMps} m/s <= 0.06 m/s", improved.mcvErrorMps <= 0.06)
    }

    @Test
    fun `resolution parity 720px vs 640px vs 480px maintains physical measurement accuracy`() = runBlocking {
        val resolutions = listOf(
            Triple("720x405 (legacy)", 720, 405),
            Triple("640x360 (standard)", 640, 360),
            Triple("480x270 (low-tier)", 480, 270),
        )
        println("=== RESOLUTION PARITY BENCHMARK (4 reps, 65 cm ROM, 60fps) ===")
        for ((tag, w, h) in resolutions) {
            val scale = w / 640.0
            val scenario = buildSquatSetScenario(
                width = w,
                height = h,
                fps = 60,
                reps = 4,
                romMeters = 0.65,
                eccentricSec = 0.95,
                concentricSec = 0.80,
                pauseSec = 0.15,
                plateDiameterPx = 72.0 * scale,
                spinRadPerRep = 1.2,
                motionBlurStrength = 0.30,
                rackUprights = true,
                noiseAmplitude = 4,
            )
            val metrics = evaluateTracker(
                label = tag,
                scenario = scenario,
                tracker = PyramidalKltMotionTracker(
                    config = PyramidalKltMotionTracker.Config(
                        targetDiameterPx = scenario.plateDiameterPx,
                        enableCircularReanchor = true,
                    ),
                ),
            )
            println(formatReport(metrics))
            assertEquals("$tag lostCount", 0, metrics.lostCount)
            assertEquals("$tag reps", 4, metrics.detectedReps)
            assertTrue("$tag ROM error ${metrics.romErrorCm} cm <= 1.2 cm", metrics.romErrorCm <= 1.2)
            assertTrue("$tag MCV error ${metrics.mcvErrorMps} m/s <= 0.04 m/s", metrics.mcvErrorMps <= 0.04)
            assertTrue("$tag MAE ${metrics.maePositionMm} mm <= 5.0 mm", metrics.maePositionMm <= 5.0)
        }
    }

    private data class Scenario(
        val width: Int,
        val height: Int,
        val plateDiameterPx: Double,
        val plateDiameterMeters: Double,
        val trueRomCm: Double,
        val trueMcvMps: Double,
        val truePeakVelMps: Double,
        val samples: List<GroundTruthSample>,
        val frames: List<DecodedTrackingFrame>,
    )

    private suspend fun evaluateTracker(
        label: String,
        scenario: Scenario,
        tracker: MotionTracker,
    ): BenchmarkMetrics {
        val source = InMemoryFrameSource(scenario.frames)
        val runner = OfflineVideoTracker(source, tracker)
        val seed = scenario.samples.first()
        runner.initialize(0, ImagePoint(seed.trueCenterX, seed.trueCenterY))
        val results = runner.trackRemaining { _, _, _ -> }

        val metersPerPx = scenario.plateDiameterMeters / scenario.plateDiameterPx
        val posErrorsMm = mutableListOf<Double>()
        var lostCount = 0
        for (res in results) {
            if (res.result.sample.status == TrackingStatus.LOST || res.result.sample.point == null) {
                lostCount++
                continue
            }
            val gt = scenario.samples[res.frameIndex]
            val pt = res.result.sample.point
            val errPx = hypot(pt.x - gt.trueCenterX, pt.y - gt.trueCenterY)
            posErrorsMm += errPx * metersPerPx * 1000.0
        }

        val lastValid = results.lastOrNull { it.result.sample.point != null }
        val finalDriftMm = if (lastValid != null) {
            val gt = scenario.samples[lastValid.frameIndex]
            val pt = lastValid.result.sample.point!!
            hypot(pt.x - gt.trueCenterX, pt.y - gt.trueCenterY) * metersPerPx * 1000.0
        } else {
            Double.POSITIVE_INFINITY
        }

        val calibration = MetricCalibrator.create(
            frameIndex = 0,
            pointA = ImagePoint(seed.trueCenterX - scenario.plateDiameterPx / 2.0, seed.trueCenterY),
            pointB = ImagePoint(seed.trueCenterX + scenario.plateDiameterPx / 2.0, seed.trueCenterY),
            referenceLengthM = scenario.plateDiameterMeters,
        )
        val analysis = EncoderAnalyzer.analyze(
            setup = EncoderSetup(EncoderExercise.SQUAT, 100.0),
            timingDecision = VideoTimingDecision(VideoTimingMode.REAL_TIME),
            calibration = calibration,
            tracking = results,
        )
        val reps = analysis.repetitions
        val meanRomCm = reps.mapNotNull { rep -> rep.metrics.firstOrNull { it.key == EncoderMetricKey.ROM }?.value?.times(100.0) }
            .takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val meanMcv = reps.mapNotNull { rep -> rep.metrics.firstOrNull { it.key == EncoderMetricKey.MCV }?.value }
            .takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val meanPeak = reps.mapNotNull { rep -> rep.metrics.firstOrNull { it.key == EncoderMetricKey.PEAK_VELOCITY }?.value }
            .takeIf { it.isNotEmpty() }?.average() ?: 0.0

        return BenchmarkMetrics(
            label = label,
            trackedFrames = results.size,
            totalFrames = scenario.frames.size,
            lostCount = lostCount,
            maePositionMm = posErrorsMm.takeIf { it.isNotEmpty() }?.average() ?: Double.POSITIVE_INFINITY,
            maxPositionErrorMm = posErrorsMm.maxOrNull() ?: Double.POSITIVE_INFINITY,
            finalDriftMm = finalDriftMm,
            detectedReps = reps.size,
            validReps = reps.count { it.quality == RepetitionQuality.VALID },
            meanRomCm = meanRomCm,
            romErrorCm = abs(meanRomCm - scenario.trueRomCm),
            meanMcvMps = meanMcv,
            mcvErrorMps = abs(meanMcv - scenario.trueMcvMps),
            meanPeakVelMps = meanPeak,
            peakVelErrorMps = abs(meanPeak - scenario.truePeakVelMps),
        )
    }

    private fun buildSquatSetScenario(
        width: Int,
        height: Int,
        fps: Int,
        reps: Int,
        romMeters: Double,
        eccentricSec: Double,
        concentricSec: Double,
        pauseSec: Double,
        plateDiameterPx: Double,
        spinRadPerRep: Double,
        motionBlurStrength: Double,
        rackUprights: Boolean,
        noiseAmplitude: Int,
    ): Scenario {
        val plateDiameterMeters = 0.45
        val pixelsPerMeter = plateDiameterPx / plateDiameterMeters
        val romPx = romMeters * pixelsPerMeter
        val topY = height * 0.28
        val centerX = width * 0.50
        val dtSec = 1.0 / fps
        val dtUs = (1_000_000.0 / fps).roundToInt().toLong()

        val leadInSec = 0.25
        val repDurSec = eccentricSec + pauseSec + concentricSec + pauseSec
        val totalSec = leadInSec + reps * repDurSec + 0.25
        val frameCount = (totalSec * fps).roundToInt()

        // Raised-cosine displacement profile: y(u) = 0.5 * ROM * (1 - cos(pi * u))
        // Mean concentric velocity = ROM / concentricSec
        // Peak concentric velocity = (pi / 2) * ROM / concentricSec
        val trueMcv = romMeters / concentricSec
        val truePeak = (PI / 2.0) * romMeters / concentricSec

        val samples = ArrayList<GroundTruthSample>(frameCount)
        val frames = ArrayList<DecodedTrackingFrame>(frameCount)

        for (i in 0 until frameCount) {
            val t = i * dtSec
            var yOffsetM = 0.0 // 0 at top, +romMeters at bottom (image Y increases downward)
            var vUpMps = 0.0
            var angle = 0.0
            if (t >= leadInSec && t < leadInSec + reps * repDurSec) {
                val repTime = t - leadInSec
                val repIdx = (repTime / repDurSec).toInt().coerceAtMost(reps - 1)
                val localT = repTime - repIdx * repDurSec
                angle = repIdx * spinRadPerRep + (localT / repDurSec) * spinRadPerRep
                when {
                    localT < eccentricSec -> {
                        val u = localT / eccentricSec
                        yOffsetM = 0.5 * romMeters * (1.0 - cos(PI * u))
                        vUpMps = -(0.5 * romMeters * PI / eccentricSec) * sin(PI * u)
                    }
                    localT < eccentricSec + pauseSec -> {
                        yOffsetM = romMeters
                        vUpMps = 0.0
                    }
                    localT < eccentricSec + pauseSec + concentricSec -> {
                        val u = (localT - eccentricSec - pauseSec) / concentricSec
                        yOffsetM = 0.5 * romMeters * (1.0 + cos(PI * u))
                        vUpMps = (0.5 * romMeters * PI / concentricSec) * sin(PI * u)
                    }
                    else -> {
                        yOffsetM = 0.0
                        vUpMps = 0.0
                    }
                }
            } else if (t >= leadInSec + reps * repDurSec) {
                angle = reps * spinRadPerRep
            }
            // Slight realistic horizontal bar path drift during squat (+/- 1.5 cm)
            val xOffsetPx = sin(yOffsetM / romMeters * PI) * (0.015 * pixelsPerMeter)
            val cx = centerX + xOffsetPx
            val cy = topY + yOffsetM * pixelsPerMeter
            samples += GroundTruthSample(
                frameIndex = i,
                timestampUs = i * dtUs,
                trueCenterX = cx,
                trueCenterY = cy,
                trueVelocityYMps = vUpMps,
                plateAngleRad = angle,
            )
            val dyFramePx = abs(vUpMps) * pixelsPerMeter * dtSec * motionBlurStrength
            val gray = renderGymFrame(
                width = width,
                height = height,
                plateCx = cx,
                plateCy = cy,
                plateRadius = plateDiameterPx / 2.0,
                plateAngle = angle,
                blurHalfSpanPx = dyFramePx * 0.5,
                rackUprights = rackUprights,
                frameSeed = i,
                noiseAmplitude = noiseAmplitude,
            )
            frames += DecodedTrackingFrame(
                frameIndex = i,
                timestampUs = i * dtUs,
                image = gray,
                presentationWidth = width,
                presentationHeight = height,
            )
        }

        return Scenario(
            width = width,
            height = height,
            plateDiameterPx = plateDiameterPx,
            plateDiameterMeters = plateDiameterMeters,
            trueRomCm = romMeters * 100.0,
            trueMcvMps = trueMcv,
            truePeakVelMps = truePeak,
            samples = samples,
            frames = frames,
        )
    }

    private fun renderGymFrame(
        width: Int,
        height: Int,
        plateCx: Double,
        plateCy: Double,
        plateRadius: Double,
        plateAngle: Double,
        blurHalfSpanPx: Double,
        rackUprights: Boolean,
        frameSeed: Int,
        noiseAmplitude: Int,
    ): GrayFrame {
        val pixels = ByteArray(width * height)
        val cosA = cos(plateAngle)
        val sinA = sin(plateAngle)
        val blurTaps = if (blurHalfSpanPx >= 0.75) 5 else 1

        // High-contrast vertical squat rack uprights passing directly behind the plate!
        val upright1X = (width * 0.455).toInt()
        val upright2X = (width * 0.545).toInt()
        val uprightHalfW = (width * 0.014).toInt().coerceAtLeast(3)

        for (y in 0 until height) {
            val rowOff = y * width
            for (x in 0 until width) {
                var accum = 0.0
                for (tap in 0 until blurTaps) {
                    val frac = if (blurTaps == 1) 0.0 else (tap.toDouble() / (blurTaps - 1) - 0.5) * 2.0
                    val sampleCy = plateCy + frac * blurHalfSpanPx
                    val dx = x - plateCx
                    val dy = y - sampleCy
                    val r = hypot(dx, dy)
                    val value = if (r <= plateRadius + 0.75) {
                        // Olympic bumper plate: dark rubber body, bright outer lip + sleeve + rotating lettering/ribs
                        val rx = dx * cosA + dy * sinA
                        val ry = -dx * sinA + dy * cosA
                        val normR = r / plateRadius
                        val basePlate = when {
                            normR < 0.16 -> 165.0 // Chrome barbell sleeve center
                            normR < 0.24 -> 62.0  // Inner collar ring
                            normR < 0.84 -> {
                                // Subtle rotating spokes / lettering texture on dark rubber plate
                                val rib = sin(rx * 0.42) * cos(ry * 0.42) * 22.0 +
                                    if (abs(ry) < plateRadius * 0.14 && abs(rx) > plateRadius * 0.30 && abs(rx) < plateRadius * 0.72) 48.0 else 0.0
                                42.0 + rib
                            }
                            else -> 32.0 // Dark outer rim
                        }
                        if (r >= plateRadius - 0.75) {
                            val bg = backgroundPixel(x, y, width, height, rackUprights, upright1X, upright2X, uprightHalfW)
                            val blend = ((r - (plateRadius - 0.75)) / 1.5).coerceIn(0.0, 1.0)
                            basePlate * (1.0 - blend) + bg * blend
                        } else {
                            basePlate
                        }
                    } else {
                        backgroundPixel(x, y, width, height, rackUprights, upright1X, upright2X, uprightHalfW)
                    }
                    accum += value
                }
                val baseVal = accum / blurTaps
                val noise = if (noiseAmplitude > 0) {
                    val h = ((x * 73856093) xor (y * 19349663) xor (frameSeed * 83492791))
                    ((h and 0xFF) / 255.0 * 2.0 - 1.0) * noiseAmplitude
                } else {
                    0.0
                }
                pixels[rowOff + x] = (baseVal + noise).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return GrayFrame(width, height, pixels)
    }

    private fun backgroundPixel(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rackUprights: Boolean,
        upright1X: Int,
        upright2X: Int,
        uprightHalfW: Int,
    ): Double {
        // Gym wall gradient + floor horizon
        var bg = if (y > height * 0.85) 115.0 else 178.0 + (y.toDouble() / height) * 20.0
        if (rackUprights) {
            val onUpright = abs(x - upright1X) <= uprightHalfW || abs(x - upright2X) <= uprightHalfW
            if (onUpright) {
                // Perforated rack upright with bolt holes every 14 px (classic KLT distractor!)
                val hole = (y % 14) in 5..8 && (abs(x - upright1X) <= 2 || abs(x - upright2X) <= 2)
                bg = if (hole) 195.0 else 58.0
            }
        }
        return bg
    }

    private fun formatReport(m: BenchmarkMetrics): String =
        "%s | frames=%d/%d lost=%d | MAE=%.2fmm max=%.2fmm finalDrift=%.2fmm | reps=%d(valid=%d) | ROM=%.2fcm(err=%.2fcm) | MCV=%.3fm/s(err=%.3f) | Vp=%.3fm/s(err=%.3f)"
            .format(
                m.label,
                m.trackedFrames,
                m.totalFrames,
                m.lostCount,
                m.maePositionMm,
                m.maxPositionErrorMm,
                m.finalDriftMm,
                m.detectedReps,
                m.validReps,
                m.meanRomCm,
                m.romErrorCm,
                m.meanMcvMps,
                m.mcvErrorMps,
                m.meanPeakVelMps,
                m.peakVelErrorMps,
            )

    private class InMemoryFrameSource(private val frames: List<DecodedTrackingFrame>) : TrackingFrameSource {
        override val frameCount: Int get() = frames.size
        override suspend fun frameAt(index: Int): DecodedTrackingFrame = frames[index]
        override suspend fun forEachFrame(
            startIndex: Int,
            onFrame: suspend (DecodedTrackingFrame) -> Boolean,
        ) {
            for (i in startIndex until frames.size) {
                if (!onFrame(frames[i])) break
            }
        }
        override fun close() = Unit
    }
}
