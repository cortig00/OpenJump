package com.openjump.app.tracking

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Learned circular rim signature of a barbell plate at initialization time.
 * Only created when the seed frame actually exhibits strong, polarity-consistent radial edges
 * around the calibrated radius; non-circular targets keep signature = null and bypass re-anchoring.
 */
internal data class CircularRimSignature(
    val radiusPx: Double,
    /** +1.0 when rim transitions dark-inside -> bright-outside; -1.0 when bright-inside -> dark-outside. */
    val expectedPolarity: Double,
    val baselineGradient: Double,
)

internal data class CircularReanchorEstimate(
    val center: ImagePoint,
    val correctionPx: Double,
    val supportFraction: Double,
    val coveredSectors: Int,
    val medianResidualPx: Double,
)

/**
 * Fast, sub-pixel radial-edge circle center refiner used to eliminate cumulative KLT drift on
 * rotating barbell plates and high-contrast rack backgrounds.
 *
 * Uses 36 radial rays with a fixed calibrated radius and 2-pass iteratively reweighted least
 * squares (Huber/Tukey) so partial occlusions (rack uprights, hands, arms) are ignored cleanly.
 */
internal object CircularPlateReanchor {

    private const val RAY_COUNT = 36
    private const val SECTOR_COUNT = 12
    private const val MIN_RADIUS_PX = 12.0
    private const val MIN_GRADIENT_STRENGTH = 12.0
    private const val MIN_RADIAL_ALIGNMENT = 0.70
    private const val MIN_SEED_SUPPORT = 0.40
    private const val MIN_SEED_SECTORS = 6
    private const val MIN_TRACK_SUPPORT = 0.38
    private const val MIN_TRACK_SECTORS = 5

    private val cosTable = DoubleArray(RAY_COUNT) { i -> cos(2.0 * PI * i / RAY_COUNT) }
    private val sinTable = DoubleArray(RAY_COUNT) { i -> sin(2.0 * PI * i / RAY_COUNT) }

    /**
     * Inspects the seed frame around [seedCenter] with calibrated [radiusPx]. Returns a validated
     * [CircularRimSignature] only if a consistent circular rim actually exists there.
     */
    fun learnSignature(
        frame: GrayFrame,
        seedCenter: ImagePoint,
        radiusPx: Double,
    ): CircularRimSignature? {
        if (!radiusPx.isFinite() || radiusPx < MIN_RADIUS_PX) return null
        // At initialization the human/detector already confirmed center and radius: use a tight
        // radial band so nearby rack uprights or inner plate rings cannot hijack the rim signature.
        val band = max(2.5, min(5.0, radiusPx * 0.09))
        var posCount = 0
        var negCount = 0
        var posSum = 0.0
        var negSum = 0.0
        val posSectors = BooleanArray(SECTOR_COUNT)
        val negSectors = BooleanArray(SECTOR_COUNT)

        for (i in 0 until RAY_COUNT) {
            val ux = cosTable[i]
            val uy = sinTable[i]
            val best = bestRadialEdge(
                frame = frame,
                cx = seedCenter.x,
                cy = seedCenter.y,
                ux = ux,
                uy = uy,
                expectedRadius = radiusPx,
                searchBand = band,
                requiredPolarity = 0.0,
            ) ?: continue
            val sector = (i * SECTOR_COUNT) / RAY_COUNT
            if (best.signedGradient > 0.0) {
                posCount++
                posSum += abs(best.signedGradient)
                posSectors[sector] = true
            } else {
                negCount++
                negSum += abs(best.signedGradient)
                negSectors[sector] = true
            }
        }

        val usePositive = posSum >= negSum
        val dominantCount = if (usePositive) posCount else negCount
        val dominantSectors = (if (usePositive) posSectors else negSectors).count { it }
        val dominantMeanGrad = if (dominantCount > 0) (if (usePositive) posSum else negSum) / dominantCount else 0.0
        val support = dominantCount.toDouble() / RAY_COUNT

        if (support < MIN_SEED_SUPPORT || dominantSectors < MIN_SEED_SECTORS || dominantMeanGrad < MIN_GRADIENT_STRENGTH) {
            return null
        }

        val candidateSig = CircularRimSignature(
            radiusPx = radiusPx,
            expectedPolarity = if (usePositive) 1.0 else -1.0,
            baselineGradient = dominantMeanGrad,
        )
        // Verify that the learned rim actually agrees closely with the human/detector seed center.
        val verify = refineCenter(frame, seedCenter, candidateSig, maxCorrectionPx = max(4.0, radiusPx * 0.15))
            ?: return null
        if (verify.correctionPx > max(3.5, radiusPx * 0.12)) return null
        return candidateSig
    }

    /**
     * Refines [predictedCenter] against the circular rim in [frame] with fixed radius
     * `signature.radiusPx`. Returns null if the rim is occluded, weak, or geometrically incoherent.
     */
    fun refineCenter(
        frame: GrayFrame,
        predictedCenter: ImagePoint,
        signature: CircularRimSignature,
        maxCorrectionPx: Double = max(6.0, signature.radiusPx * 0.24),
    ): CircularReanchorEstimate? {
        val radius = signature.radiusPx
        val searchBand = max(4.0, min(14.0, radius * 0.24))
        // Keep low-contrast sectors where a dark plate meets a dark rack upright or shadow.
        val minGrad = max(MIN_GRADIENT_STRENGTH, signature.baselineGradient * 0.14)

        val rayIndices = IntArray(RAY_COUNT)
        val radialOffsets = DoubleArray(RAY_COUNT)
        val rayWeights = DoubleArray(RAY_COUNT)
        var validCount = 0

        for (i in 0 until RAY_COUNT) {
            val best = bestRadialEdge(
                frame = frame,
                cx = predictedCenter.x,
                cy = predictedCenter.y,
                ux = cosTable[i],
                uy = sinTable[i],
                expectedRadius = radius,
                searchBand = searchBand,
                requiredPolarity = signature.expectedPolarity,
            ) ?: continue
            val mag = abs(best.signedGradient)
            if (mag < minGrad) continue
            rayIndices[validCount] = i
            // Radial residual relative to predictedCenter: r_obs - R ≈ dx*ux + dy*uy
            radialOffsets[validCount] = best.radius - radius
            rayWeights[validCount] = (mag / signature.baselineGradient).coerceIn(0.25, 2.0)
            validCount++
        }

        if (validCount < (RAY_COUNT * MIN_TRACK_SUPPORT).toInt()) return null

        // 2-pass IRLS solving min sum w_i * (dx * ux_i + dy * uy_i - offset_i)^2
        var dx = 0.0
        var dy = 0.0
        val inlierTol = max(1.75, radius * 0.055)
        val workingWeights = DoubleArray(validCount) { rayWeights[it] }

        for (pass in 0 until 3) {
            var sxx = 0.0
            var sxy = 0.0
            var syy = 0.0
            var bx = 0.0
            var by = 0.0
            for (k in 0 until validCount) {
                val w = workingWeights[k]
                if (w <= 0.0) continue
                val idx = rayIndices[k]
                val ux = cosTable[idx]
                val uy = sinTable[idx]
                val b = radialOffsets[k]
                sxx += w * ux * ux
                sxy += w * ux * uy
                syy += w * uy * uy
                bx += w * ux * b
                by += w * uy * b
            }
            val det = sxx * syy - sxy * sxy
            if (det < 1e-6) return null
            dx = (syy * bx - sxy * by) / det
            dy = (-sxy * bx + sxx * by) / det
            if (!dx.isFinite() || !dy.isFinite()) return null

            if (pass < 2) {
                for (k in 0 until validCount) {
                    val idx = rayIndices[k]
                    val residual = abs(dx * cosTable[idx] + dy * sinTable[idx] - radialOffsets[k])
                    workingWeights[k] = when {
                        residual <= inlierTol -> rayWeights[k]
                        residual <= inlierTol * 2.0 -> rayWeights[k] * (inlierTol / residual) * 0.5
                        else -> 0.0 // Reject occluded/rack outlier ray completely
                    }
                }
            }
        }

        val correction = hypot(dx, dy)
        if (correction > maxCorrectionPx) return null

        val inlierResiduals = mutableListOf<Double>()
        val sectors = BooleanArray(SECTOR_COUNT)
        for (k in 0 until validCount) {
            val idx = rayIndices[k]
            val residual = abs(dx * cosTable[idx] + dy * sinTable[idx] - radialOffsets[k])
            if (residual <= inlierTol) {
                inlierResiduals += residual
                sectors[(idx * SECTOR_COUNT) / RAY_COUNT] = true
            }
        }

        val supportFraction = inlierResiduals.size.toDouble() / RAY_COUNT
        val coveredSectors = sectors.count { it }
        if (supportFraction < MIN_TRACK_SUPPORT || coveredSectors < MIN_TRACK_SECTORS) return null

        inlierResiduals.sort()
        val medianResidual = inlierResiduals[inlierResiduals.size / 2]
        if (medianResidual > max(1.5, radius * 0.045)) return null

        val refinedX = (predictedCenter.x + dx).coerceIn(0.0, frame.width - 1.0)
        val refinedY = (predictedCenter.y + dy).coerceIn(0.0, frame.height - 1.0)
        return CircularReanchorEstimate(
            center = ImagePoint(refinedX, refinedY),
            correctionPx = correction,
            supportFraction = supportFraction,
            coveredSectors = coveredSectors,
            medianResidualPx = medianResidual,
        )
    }

    private data class RadialPeak(val radius: Double, val signedGradient: Double)

    private fun bestRadialEdge(
        frame: GrayFrame,
        cx: Double,
        cy: Double,
        ux: Double,
        uy: Double,
        expectedRadius: Double,
        searchBand: Double,
        requiredPolarity: Double,
    ): RadialPeak? {
        val rStart = max(3.0, expectedRadius - searchBand)
        val rEnd = expectedRadius + searchBand
        val steps = (rEnd - rStart).toInt().coerceAtLeast(1)

        var bestR = Double.NaN
        var bestScore = 0.0
        var bestSigned = 0.0
        var prevGrad = 0.0
        var bestIdx = -1
        val grads = DoubleArray(steps + 1)
        val radii = DoubleArray(steps + 1)

        for (s in 0..steps) {
            val r = rStart + s
            radii[s] = r
            val px = cx + r * ux
            val py = cy + r * uy
            val left = sampleBilinear(frame, px - 1.0, py) ?: continue
            val right = sampleBilinear(frame, px + 1.0, py) ?: continue
            val up = sampleBilinear(frame, px, py - 1.0) ?: continue
            val down = sampleBilinear(frame, px, py + 1.0) ?: continue
            val gx = right - left
            val gy = down - up
            val gradMag = hypot(gx, gy)
            if (gradMag < MIN_GRADIENT_STRENGTH) continue
            // Project 2D gradient onto the outward radial normal and reject edges that are not
            // tangent to the circle (e.g. vertical rack uprights crossing the top/bottom of the plate).
            val g = gx * ux + gy * uy
            val alignment = abs(g) / gradMag
            if (alignment < MIN_RADIAL_ALIGNMENT) continue
            grads[s] = g
            val polarityOk = requiredPolarity == 0.0 || g * requiredPolarity > 0.0
            if (!polarityOk) continue
            val mag = abs(g)
            // Mild proximity bias prefers edges near the expected radius when tied.
            val dist = (r - expectedRadius) / searchBand
            val weighted = mag * alignment * (1.0 - 0.15 * dist * dist)
            if (weighted > bestScore) {
                bestScore = weighted
                bestSigned = g
                bestR = r
                bestIdx = s
            }
            prevGrad = g
        }
        if (bestIdx < 0 || abs(bestSigned) < MIN_GRADIENT_STRENGTH) return null

        // Parabolic sub-pixel peak refinement along the ray when interior to the search band.
        var refinedR = bestR
        if (bestIdx in 1 until steps) {
            val g0 = abs(grads[bestIdx - 1])
            val g1 = abs(grads[bestIdx])
            val g2 = abs(grads[bestIdx + 1])
            val denom = g0 - 2.0 * g1 + g2
            if (denom < -1e-6) {
                val shift = (0.5 * (g0 - g2) / denom).coerceIn(-0.5, 0.5)
                refinedR = radii[bestIdx] + shift
            }
        }
        @Suppress("UNUSED_VALUE")
        prevGrad
        return RadialPeak(refinedR, bestSigned)
    }

    private fun sampleBilinear(frame: GrayFrame, x: Double, y: Double): Double? {
        if (x < 0.0 || y < 0.0 || x > frame.width - 1.001 || y > frame.height - 1.001) return null
        val x0 = x.toInt()
        val y0 = y.toInt()
        val fx = x - x0
        val fy = y - y0
        val w = frame.width
        val data = frame.pixels
        val row0 = y0 * w + x0
        val row1 = row0 + w
        val v00 = data[row0].toInt() and 0xFF
        val v10 = data[row0 + 1].toInt() and 0xFF
        val v01 = data[row1].toInt() and 0xFF
        val v11 = data[row1 + 1].toInt() and 0xFF
        val top = v00 + (v10 - v00) * fx
        val bottom = v01 + (v11 - v01) * fx
        return top + (bottom - top) * fy
    }
}
