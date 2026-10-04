package com.openjump.app.encoder

import boofcv.alg.filter.binary.BinaryImageOps
import boofcv.alg.filter.binary.ContourOps
import boofcv.alg.filter.binary.ThresholdImageOps
import boofcv.struct.ConnectRule
import boofcv.struct.image.GrayU8
import com.openjump.app.tracking.GrayFrame
import com.openjump.app.tracking.ImagePoint
import georegression.fitting.curves.FitEllipseAlgebraic_F64
import georegression.geometry.UtilEllipse_F64
import georegression.struct.curve.EllipseRotated_F64
import georegression.struct.point.Point2D_F64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class PlateDetectionMode { AUTO, ASSISTED, MANUAL }

enum class PlateDetectionSource { CONTOUR_ELLIPSE, GRADIENT_CIRCLE }

data class PlateDetection(
    val center: ImagePoint,
    val diameterPx: Double,
    val confidence: Double,
    val perspectiveRatio: Double,
    val mode: PlateDetectionMode,
    val ellipseMajorAxisPx: Double,
    val ellipseMinorAxisPx: Double,
    val ellipseAngleRadians: Double,
    val autoCalibrationAccepted: Boolean,
    val source: PlateDetectionSource = PlateDetectionSource.CONTOUR_ELLIPSE,
    val edgeSupport: Double = 0.0,
    val edgeAlignment: Double = 0.0,
    val coveredSectors: Int = 0,
) {
    init {
        require(diameterPx.isFinite() && diameterPx > 0.0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(perspectiveRatio.isFinite() && perspectiveRatio in 0.0..1.0)
        require(edgeSupport in 0.0..1.0)
        require(edgeAlignment in 0.0..1.0)
        require(coveredSectors in 0..12)
        require(ellipseMajorAxisPx.isFinite() && ellipseMajorAxisPx > 0.0)
        require(ellipseMinorAxisPx.isFinite() && ellipseMinorAxisPx > 0.0)
        require(ellipseAngleRadians.isFinite())
        require(mode != PlateDetectionMode.MANUAL)
    }

    fun calibrationEndpoints(): Pair<ImagePoint, ImagePoint> {
        val half = diameterPx / 2.0
        val dx = cos(ellipseAngleRadians) * half
        val dy = sin(ellipseAngleRadians) * half
        return ImagePoint(center.x - dx, center.y - dy) to ImagePoint(center.x + dx, center.y + dy)
    }
}

data class PlateDetectorConfig(
    val assistedRoiFraction: Double = 1.0,
    val minimumAssistedRoiPx: Int = 160,
    val maximumAssistedRoiPx: Int = 520,
    val minimumDiameterFraction: Double = 0.065,
    val maximumDiameterFraction: Double = 0.68,
    val minimumContourPoints: Int = 28,
    val minimumPerspectiveRatioForDetection: Double = 0.52,
    val minimumPerspectiveRatioForCalibration: Double = 0.82,
    val minimumAssistedConfidence: Double = 0.62,
    val minimumAutomaticConfidence: Double = 0.78,
    val automaticAmbiguityMargin: Double = 0.14,
    val maximumStackCenterOffsetFraction: Double = 0.16,
    val minimumStackCenterOffsetPx: Double = 6.0,
    val maximumTapEllipseDistance: Double = 1.35,
) {
    init {
        require(assistedRoiFraction in 0.2..1.0)
        require(minimumAssistedRoiPx in 32..maximumAssistedRoiPx)
        require(minimumDiameterFraction in 0.01..maximumDiameterFraction)
        require(maximumDiameterFraction <= 1.0)
        require(minimumContourPoints >= 12)
        require(minimumPerspectiveRatioForDetection in 0.0..1.0)
        require(minimumPerspectiveRatioForCalibration in minimumPerspectiveRatioForDetection..1.0)
        require(minimumAssistedConfidence in 0.0..1.0)
        require(minimumAutomaticConfidence in minimumAssistedConfidence..1.0)
        require(automaticAmbiguityMargin in 0.0..1.0)
        require(maximumStackCenterOffsetFraction in 0.01..0.35)
        require(minimumStackCenterOffsetPx >= 0.0)
        require(maximumTapEllipseDistance >= 1.0)
    }
}

data class PlateDetectorDiagnostics(
    val contourScanMs: Double,
    val geometricRefinementMs: Double,
    val candidatesRejectedByEdge: Int = 0,
    val bestSource: PlateDetectionSource? = null,
    val candidateDiametersPx: List<Double> = emptyList(),
    val candidateSources: List<PlateDetectionSource> = emptyList(),
    val circleDiametersPassingGatesPx: List<Double> = emptyList(),
)

internal fun interface PlateDetectionTraceSink {
    fun onTrace(trace: PlateDetectionTrace)
    companion object { val NO_OP: PlateDetectionTraceSink = PlateDetectionTraceSink { } }
}

internal data class PlateDetectionTrace(
    val frameWidth: Int,
    val frameHeight: Int,
    val edgeThreshold: Int? = null,
    val edgesSampled: Int = 0,
    val pairsAttempted: Int = 0,
    val rejectedParallel: Int = 0,
    val rejectedRadius: Int = 0,
    val rejectedTap: Int = 0,
    val rejectedEvidence: Int = 0,
    val peaks: List<PlateDetectionTracePeak> = emptyList(),
    val referenceHypothesis: PlateDetectionTraceReference? = null,
)
internal data class PlateDetectionTracePeak(
    val rank: Int = 0, val localX: Int = 0, val localY: Int = 0,
    val frameX: Int = 0, val frameY: Int = 0, val votes: Int = 0,
    val centerX: Double = frameX.toDouble(), val centerY: Double = frameY.toDouble(),
    val radius: Double = 0.0, val support: Double = 0.0,
    val coveredSectors: Int = 0, val score: Double = 0.0,
)
internal data class PlateDetectionTraceReference(
    val centerX: Double, val centerY: Double, val diameterPx: Double,
    val support: Double = 0.0, val alignment: Double = 0.0,
    val coveredSectors: Int = 0, val perspectiveRatio: Double = 0.0,
    val rejection: String? = null,
)

sealed interface PlateDetectionOutcome {
    val diagnostics: PlateDetectorDiagnostics

    data class Found(
        val detection: PlateDetection,
        val candidateCount: Int,
        override val diagnostics: PlateDetectorDiagnostics,
    ) : PlateDetectionOutcome

    data class Ambiguous(
        val candidateCount: Int,
        val bestConfidence: Double,
        override val diagnostics: PlateDetectorDiagnostics,
    ) : PlateDetectionOutcome

    data class NotFound(
        val reason: String,
        val candidateCount: Int = 0,
        override val diagnostics: PlateDetectorDiagnostics = PlateDetectorDiagnostics(0.0, 0.0),
    ) : PlateDetectionOutcome
}

/**
 * One-shot geometric detector for lateral barbell plates.
 *
 * It thresholds one grayscale ROI at a few image-derived levels, extracts external contours and
 * fits ellipses to them. AUTO is deliberately conservative; ASSISTED uses the tap only to restrict
 * the ROI and rank ellipses, never as the final tracking point.
 */
class GeometricPlateDetector(
    private val config: PlateDetectorConfig = PlateDetectorConfig(),
) {
    internal var traceSink: PlateDetectionTraceSink = PlateDetectionTraceSink.NO_OP
    internal constructor(config: PlateDetectorConfig = PlateDetectorConfig(), traceSink: PlateDetectionTraceSink) : this(config) {
        this.traceSink = traceSink
    }
    fun detectAutomatic(frame: GrayFrame): PlateDetectionOutcome = detect(
        frame = frame,
        mode = PlateDetectionMode.AUTO,
        tap = null,
        roi = Roi(0, 0, frame.width, frame.height),
    )

    fun detectAssisted(frame: GrayFrame, approximateTap: ImagePoint): PlateDetectionOutcome {
        require(approximateTap.x in 0.0..(frame.width - 1.0) && approximateTap.y in 0.0..(frame.height - 1.0))
        val minimumDimension = min(frame.width, frame.height)
        val side = (minimumDimension * config.assistedRoiFraction).toInt()
            .coerceIn(config.minimumAssistedRoiPx, config.maximumAssistedRoiPx)
            .coerceAtMost(minimumDimension)
        val left = (approximateTap.x.toInt() - side / 2).coerceIn(0, frame.width - side)
        val top = (approximateTap.y.toInt() - side / 2).coerceIn(0, frame.height - side)
        return detect(
            frame = frame,
            mode = PlateDetectionMode.ASSISTED,
            tap = approximateTap,
            roi = Roi(left, top, side, side),
        )
    }

    private fun detect(
        frame: GrayFrame,
        mode: PlateDetectionMode,
        tap: ImagePoint?,
        roi: Roi,
    ): PlateDetectionOutcome {
        val gray = copyRoi(frame, roi)
        val timing = TimingAccumulator(frame)
        try {
        val thresholds = quantileThresholds(gray)
        if (thresholds.isEmpty()) return PlateDetectionOutcome.NotFound("La imagen no tiene contraste suficiente.")

        val candidates = buildList {
            thresholds.forEach { threshold ->
                addAll(candidatesAtThreshold(gray, roi, threshold, down = true, frame, tap, timing))
                addAll(candidatesAtThreshold(gray, roi, threshold, down = false, frame, tap, timing))
            }
            if (mode == PlateDetectionMode.ASSISTED && tap != null) {
                addAll(assistedCircleCandidates(frame, roi, tap, timing))
            }
        }
        val distinct = collapseEquivalentCandidates(candidates)
            .sortedWith(compareByDescending<Candidate> { it.confidence }.thenByDescending { it.diameterPx })

        if (distinct.isEmpty()) {
            return PlateDetectionOutcome.NotFound(
                "No se encontró un contorno circular o elíptico fiable.",
                diagnostics = timing.snapshot(),
            )
        }

        return when (mode) {
            PlateDetectionMode.ASSISTED -> {
                val eligible = distinct.filter {
                    it.tapDistance <= config.maximumTapEllipseDistance &&
                        it.confidence >= config.minimumAssistedConfidence
                }
                // The tap already removes most ambiguity. Prefer a containing geometry and then
                // the largest coherent rim so inner hubs/rings do not replace the loaded plate.
                val best = eligible.maxWithOrNull(
                    compareBy<Candidate> { it.tapDistance <= 1.05 }
                        .thenBy { it.diameterPx }
                        .thenBy { it.confidence },
                ) ?: return PlateDetectionOutcome.NotFound(
                    reason = "La geometría alrededor del toque no es suficientemente consistente.",
                    candidateCount = distinct.size,
                    diagnostics = timing.snapshot(),
                )
                PlateDetectionOutcome.Found(best.toDetection(mode), distinct.size, timing.snapshot(best, distinct))
            }

            PlateDetectionMode.AUTO -> {
                val best = distinct.first()
                if (best.confidence < config.minimumAutomaticConfidence) {
                    return PlateDetectionOutcome.NotFound(
                        reason = "La mejor geometría tiene confianza insuficiente.",
                        candidateCount = distinct.size,
                        diagnostics = timing.snapshot(),
                    )
                }
                val competing = distinct.drop(1).firstOrNull { other ->
                    !sameLoadedStack(best, other) &&
                        other.confidence >= best.confidence - config.automaticAmbiguityMargin
                }
                if (competing != null) {
                    PlateDetectionOutcome.Ambiguous(distinct.size, best.confidence, timing.snapshot())
                } else {
                    PlateDetectionOutcome.Found(best.toDetection(mode), distinct.size, timing.snapshot(best, distinct))
                }
            }

            PlateDetectionMode.MANUAL -> error("MANUAL does not run the geometric detector.")
        }
        } finally {
            if (traceSink !== PlateDetectionTraceSink.NO_OP) traceSink.onTrace(timing.trace())
        }
    }

    private fun candidatesAtThreshold(
        gray: GrayU8,
        roi: Roi,
        threshold: Int,
        down: Boolean,
        frame: GrayFrame,
        tap: ImagePoint?,
        timing: TimingAccumulator,
    ): List<Candidate> {
        val scanStartedNs = System.nanoTime()
        val binary = ThresholdImageOps.threshold(gray, null, threshold, down)
        val contours = BinaryImageOps.contourExternal(binary, ConnectRule.EIGHT)
        timing.contourScanNs += System.nanoTime() - scanStartedNs
        return contours.mapNotNull { contour ->
            val refinementStartedNs = System.nanoTime()
            try {
                val points = contour.external
                if (points.size < config.minimumContourPoints ||
                    ContourOps.isTouchBorder(points, gray.width, gray.height)
                ) {
                    return@mapNotNull null
                }

                val perimeter = polygonPerimeter(points)
                val area = abs(polygonArea(points))
                if (perimeter <= 0.0 || area <= 0.0) return@mapNotNull null

                val sampled = sampleContour(points).map { point ->
                    Point2D_F64(point.x + roi.left.toDouble(), point.y + roi.top.toDouble())
                }
                val fitter = FitEllipseAlgebraic_F64()
                if (!fitter.process(sampled)) return@mapNotNull null
                val ellipse = UtilEllipse_F64.convert(fitter.ellipse, EllipseRotated_F64())
                    ?: return@mapNotNull null
                ellipseCandidate(ellipse, sampled, perimeter, area, frame, tap)
            } finally {
                timing.geometricRefinementNs += System.nanoTime() - refinementStartedNs
            }
        }
    }

    /** Gradient-normal circle voting complements contours when a rack or sleeve breaks the rim. */
    private fun assistedCircleCandidates(
        frame: GrayFrame,
        roi: Roi,
        tap: ImagePoint,
        timing: TimingAccumulator,
    ): List<Candidate> {
        val scanStartedNs = System.nanoTime()
        val size = roi.width * roi.height
        val gradientX = IntArray(size)
        val gradientY = IntArray(size)
        val magnitude = IntArray(size)
        val histogram = IntArray(1_445)
        for (localY in 1 until roi.height - 1) {
            val y = roi.top + localY
            for (localX in 1 until roi.width - 1) {
                val x = roi.left + localX
                fun luminance(px: Int, py: Int) = frame.pixels[py * frame.width + px].toInt() and 0xFF
                val gx =
                    -luminance(x - 1, y - 1) + luminance(x + 1, y - 1) +
                        -2 * luminance(x - 1, y) + 2 * luminance(x + 1, y) +
                        -luminance(x - 1, y + 1) + luminance(x + 1, y + 1)
                val gy =
                    -luminance(x - 1, y - 1) - 2 * luminance(x, y - 1) - luminance(x + 1, y - 1) +
                        luminance(x - 1, y + 1) + 2 * luminance(x, y + 1) + luminance(x + 1, y + 1)
                val index = localY * roi.width + localX
                val value = sqrt((gx * gx + gy * gy).toDouble()).roundToInt().coerceIn(0, histogram.lastIndex)
                gradientX[index] = gx
                gradientY[index] = gy
                magnitude[index] = value
                histogram[value]++
            }
        }
        val edgeThreshold = histogramQuantile(histogram, 0.86).coerceAtLeast(45)
        val frameMinimum = min(frame.width, frame.height).toDouble()
        val minimumRadius = (frameMinimum * config.minimumDiameterFraction / 2.0).roundToInt().coerceAtLeast(12)
        val maximumRadius = (frameMinimum * config.maximumDiameterFraction / 2.0).roundToInt()
            .coerceAtMost(min(roi.width, roi.height) / 2 - 2)
        if (maximumRadius <= minimumRadius) return emptyList()
        val radiusStep = ((maximumRadius - minimumRadius) / 42).coerceAtLeast(3)
        val votes = IntArray(size)
        for (localY in 2 until roi.height - 2 step 2) {
            for (localX in 2 until roi.width - 2 step 2) {
                val index = localY * roi.width + localX
                val mag = magnitude[index]
                if (mag < edgeThreshold) continue
                val unitX = gradientX[index] / mag.toDouble()
                val unitY = gradientY[index] / mag.toDouble()
                var radius = minimumRadius
                while (radius <= maximumRadius) {
                    voteCircleCenter(localX, localY, unitX, unitY, radius, roi, tap, votes)
                    voteCircleCenter(localX, localY, -unitX, -unitY, radius, roi, tap, votes)
                    radius += radiusStep
                }
            }
        }
        val peaks = mutableListOf<Int>()
        votes.indices.asSequence()
            .filter { votes[it] > 0 }
            .sortedByDescending { votes[it] }
            .take(1_200)
            .forEach { candidate ->
                if (peaks.size >= 40) return@forEach
                val x = candidate % roi.width
                val y = candidate / roi.width
                if (peaks.none { existing ->
                        hypot(
                            (existing % roi.width - x).toDouble(),
                            (existing / roi.width - y).toDouble(),
                        ) < 8.0
                    }
                ) {
                    peaks += candidate
                }
            }
        timing.contourScanNs += System.nanoTime() - scanStartedNs

        val refinementStartedNs = System.nanoTime()
        try {
            return peaks.mapNotNull { peak ->
                val centerX = peak % roi.width
                val centerY = peak / roi.width
                var bestRadius = 0
                var bestSupport = 0.0
                var bestAlignment = 0.0
                var radius = minimumRadius
                while (radius <= maximumRadius) {
                    var supported = 0
                    var alignmentSum = 0.0
                    val samples = 96
                    repeat(samples) { sample ->
                        val angle = 2.0 * PI * sample / samples
                        val radialX = cos(angle)
                        val radialY = sin(angle)
                        var bestMagnitude = 0
                        var bestIndex = -1
                        for (offset in -2..2) {
                            val x = (centerX + radialX * (radius + offset)).roundToInt()
                            val y = (centerY + radialY * (radius + offset)).roundToInt()
                            if (x !in 1 until roi.width - 1 || y !in 1 until roi.height - 1) continue
                            val index = y * roi.width + x
                            if (magnitude[index] > bestMagnitude) {
                                bestMagnitude = magnitude[index]
                                bestIndex = index
                            }
                        }
                        if (bestMagnitude >= edgeThreshold && bestIndex >= 0) {
                            supported++
                            val mag = magnitude[bestIndex].coerceAtLeast(1)
                            alignmentSum += abs(
                                gradientX[bestIndex] / mag.toDouble() * radialX +
                                    gradientY[bestIndex] / mag.toDouble() * radialY,
                            )
                        }
                    }
                    val support = supported / samples.toDouble()
                    val alignment = if (supported == 0) 0.0 else alignmentSum / supported
                    val score = support * 0.72 + alignment * 0.28
                    if (score > bestSupport * 0.72 + bestAlignment * 0.28) {
                        bestRadius = radius
                        bestSupport = support
                        bestAlignment = alignment
                    }
                    radius += 2
                }
                if (bestRadius <= 0) return@mapNotNull null
                val center = ImagePoint((centerX + roi.left).toDouble(), (centerY + roi.top).toDouble())
                val tapDistance = hypot(center.x - tap.x, center.y - tap.y) / bestRadius
                if (tapDistance > config.maximumTapEllipseDistance) return@mapNotNull null
                val voteScore = (votes[peak] / 20.0).coerceIn(0.0, 1.0)
                val confidence = (bestSupport * 0.62 + bestAlignment * 0.23 + voteScore * 0.15)
                    .coerceIn(0.0, 1.0)
                if (bestSupport < 0.42 || bestAlignment < 0.58 || confidence < config.minimumAssistedConfidence) {
                    return@mapNotNull null
                }
                timing.circleDiameters += bestRadius * 2.0
                Candidate(
                    center = center,
                    diameterPx = bestRadius * 2.0,
                    confidence = confidence,
                    perspectiveRatio = 0.0,
                    majorAxisPx = bestRadius * 2.0,
                    minorAxisPx = bestRadius * 2.0,
                    angleRadians = 0.0,
                    tapDistance = tapDistance,
                    source = PlateDetectionSource.GRADIENT_CIRCLE,
                )
            }
        } finally {
            timing.geometricRefinementNs += System.nanoTime() - refinementStartedNs
        }
    }

    private fun voteCircleCenter(
        edgeX: Int,
        edgeY: Int,
        directionX: Double,
        directionY: Double,
        radius: Int,
        roi: Roi,
        tap: ImagePoint,
        votes: IntArray,
    ) {
        val centerX = (edgeX + directionX * radius).roundToInt()
        val centerY = (edgeY + directionY * radius).roundToInt()
        if (centerX !in 1 until roi.width - 1 || centerY !in 1 until roi.height - 1) return
        val globalX = centerX + roi.left
        val globalY = centerY + roi.top
        if (hypot(globalX - tap.x, globalY - tap.y) > radius * config.maximumTapEllipseDistance) return
        votes[centerY * roi.width + centerX]++
    }

    private fun histogramQuantile(histogram: IntArray, quantile: Double): Int {
        val total = histogram.sum()
        if (total == 0) return 0
        val target = (total * quantile).toInt()
        var cumulative = 0
        histogram.forEachIndexed { value, count ->
            cumulative += count
            if (cumulative >= target) return value
        }
        return histogram.lastIndex
    }

    private fun ellipseCandidate(
        ellipse: EllipseRotated_F64,
        contour: List<Point2D_F64>,
        perimeter: Double,
        area: Double,
        frame: GrayFrame,
        tap: ImagePoint?,
    ): Candidate? {
        if (!ellipse.a.isFinite() || !ellipse.b.isFinite() || ellipse.a <= 0.0 || ellipse.b <= 0.0) return null
        val majorRadius = max(ellipse.a, ellipse.b)
        val minorRadius = min(ellipse.a, ellipse.b)
        val angle = if (ellipse.a >= ellipse.b) ellipse.phi else ellipse.phi + PI / 2.0
        val major = majorRadius * 2.0
        val minor = minorRadius * 2.0
        val diameter = (major + minor) / 2.0
        val frameMinimum = min(frame.width, frame.height).toDouble()
        if (diameter !in frameMinimum * config.minimumDiameterFraction..frameMinimum * config.maximumDiameterFraction) return null

        val center = ImagePoint(ellipse.center.x, ellipse.center.y)
        if (center.x !in 0.0..(frame.width - 1.0) || center.y !in 0.0..(frame.height - 1.0)) return null
        val perspectiveRatio = (minor / major).coerceIn(0.0, 1.0)
        if (perspectiveRatio < config.minimumPerspectiveRatioForDetection) return null

        val fitError = contour.map { point ->
            abs(normalizedEllipseDistance(point.x, point.y, center, majorRadius, minorRadius, angle) - 1.0)
        }.average()
        val fitScore = (1.0 - fitError / 0.24).coerceIn(0.0, 1.0)
        val circularity = (4.0 * PI * area / (perimeter * perimeter)).coerceIn(0.0, 1.0)
        val circularityScore = (circularity / 0.78).coerceIn(0.0, 1.0)
        val perspectiveScore = ((perspectiveRatio - config.minimumPerspectiveRatioForDetection) /
            (1.0 - config.minimumPerspectiveRatioForDetection)).coerceIn(0.0, 1.0)
        val ellipseArea = PI * majorRadius * minorRadius
        val fillScore = (1.0 - abs(area / ellipseArea - 1.0) / 0.38).coerceIn(0.0, 1.0)
        val diameterFraction = diameter / frameMinimum
        val sizeScore = when {
            diameterFraction < 0.10 -> ((diameterFraction - config.minimumDiameterFraction) / 0.035).coerceIn(0.0, 1.0)
            diameterFraction > 0.55 -> ((config.maximumDiameterFraction - diameterFraction) / 0.13).coerceIn(0.0, 1.0)
            else -> 1.0
        }
        val confidence = (
            0.34 * fitScore +
                0.24 * circularityScore +
                0.20 * perspectiveScore +
                0.12 * fillScore +
                0.10 * sizeScore
            ).coerceIn(0.0, 1.0)
        val tapDistance = tap?.let {
            normalizedEllipseDistance(it.x, it.y, center, majorRadius, minorRadius, angle)
        } ?: Double.POSITIVE_INFINITY

        return Candidate(
            center = center,
            diameterPx = diameter,
            confidence = confidence,
            perspectiveRatio = perspectiveRatio,
            majorAxisPx = major,
            minorAxisPx = minor,
            angleRadians = angle,
            tapDistance = tapDistance,
            source = PlateDetectionSource.CONTOUR_ELLIPSE,
        )
    }

    private fun collapseEquivalentCandidates(candidates: List<Candidate>): List<Candidate> {
        val groups = mutableListOf<MutableList<Candidate>>()
        candidates.sortedByDescending { it.confidence }.forEach { candidate ->
            val group = groups.firstOrNull { existing -> existing.any { sameLoadedStack(it, candidate) } }
            if (group == null) groups += mutableListOf(candidate) else group += candidate
        }
        return groups.map { group ->
            val coherent = group.filter { it.confidence >= config.minimumAssistedConfidence }
            if (coherent.isNotEmpty()) coherent.maxBy { it.diameterPx } else group.maxBy { it.confidence }
        }
    }

    /**
     * Candidates sharing an axle belong to one loaded stack even when a small training plate and
     * a full-size plate have very different diameters. Nearby rack plates keep separate centers,
     * so AUTO will still treat those as ambiguous instead of choosing the largest object globally.
     */
    private fun sameLoadedStack(first: Candidate, second: Candidate): Boolean {
        val centerDistance = hypot(first.center.x - second.center.x, first.center.y - second.center.y)
        val largerDiameter = max(first.diameterPx, second.diameterPx)
        val centerTolerance = max(
            config.minimumStackCenterOffsetPx,
            largerDiameter * config.maximumStackCenterOffsetFraction,
        )
        return centerDistance <= centerTolerance
    }

    private fun Candidate.toDetection(mode: PlateDetectionMode) = PlateDetection(
        center = center,
        diameterPx = diameterPx,
        confidence = confidence,
        perspectiveRatio = perspectiveRatio,
        mode = mode,
        ellipseMajorAxisPx = majorAxisPx,
        ellipseMinorAxisPx = minorAxisPx,
        ellipseAngleRadians = angleRadians,
        autoCalibrationAccepted = source == PlateDetectionSource.CONTOUR_ELLIPSE &&
            perspectiveRatio >= config.minimumPerspectiveRatioForCalibration,
        source = source,
    )

    private fun copyRoi(frame: GrayFrame, roi: Roi): GrayU8 {
        val output = GrayU8(roi.width, roi.height)
        for (y in 0 until roi.height) {
            System.arraycopy(
                frame.pixels,
                (roi.top + y) * frame.width + roi.left,
                output.data,
                y * output.stride,
                roi.width,
            )
        }
        return output
    }

    private fun quantileThresholds(gray: GrayU8): List<Int> {
        val histogram = IntArray(256)
        for (y in 0 until gray.height) {
            var index = gray.startIndex + y * gray.stride
            repeat(gray.width) { histogram[gray.data[index++].toInt() and 0xFF]++ }
        }
        val total = gray.width * gray.height
        if (total <= 0) return emptyList()
        return listOf(0.02, 0.05, 0.10, 0.20, 0.35, 0.50, 0.65, 0.80, 0.90, 0.95, 0.98)
            .map { quantile ->
                val target = (total * quantile).toInt()
                var cumulative = 0
                histogram.indexOfFirst { count ->
                    cumulative += count
                    cumulative >= target
                }
            }
            .filter { it in 4..251 }
            .distinct()
    }

    private fun sampleContour(points: List<georegression.struct.point.Point2D_I32>) = buildList {
        val step = (points.size / 360).coerceAtLeast(1)
        var index = 0
        while (index < points.size) {
            add(points[index])
            index += step
        }
    }

    private fun polygonArea(points: List<georegression.struct.point.Point2D_I32>): Double {
        var sum = 0.0
        points.forEachIndexed { index, point ->
            val next = points[(index + 1) % points.size]
            sum += point.x.toDouble() * next.y - next.x.toDouble() * point.y
        }
        return sum / 2.0
    }

    private fun polygonPerimeter(points: List<georegression.struct.point.Point2D_I32>): Double {
        var sum = 0.0
        points.forEachIndexed { index, point ->
            val next = points[(index + 1) % points.size]
            sum += hypot((next.x - point.x).toDouble(), (next.y - point.y).toDouble())
        }
        return sum
    }

    private fun normalizedEllipseDistance(
        x: Double,
        y: Double,
        center: ImagePoint,
        majorRadius: Double,
        minorRadius: Double,
        angle: Double,
    ): Double {
        val dx = x - center.x
        val dy = y - center.y
        val cosine = cos(angle)
        val sine = sin(angle)
        val majorCoordinate = dx * cosine + dy * sine
        val minorCoordinate = -dx * sine + dy * cosine
        return sqrt(
            majorCoordinate * majorCoordinate / (majorRadius * majorRadius) +
                minorCoordinate * minorCoordinate / (minorRadius * minorRadius),
        )
    }

    private data class Roi(val left: Int, val top: Int, val width: Int, val height: Int)

    private class TimingAccumulator(private val frame: GrayFrame) {
        var contourScanNs: Long = 0L
        var geometricRefinementNs: Long = 0L
        val circleDiameters = mutableListOf<Double>()

        fun snapshot(best: Candidate? = null, candidates: List<Candidate> = emptyList()) = PlateDetectorDiagnostics(
            contourScanMs = contourScanNs / 1_000_000.0,
            geometricRefinementMs = geometricRefinementNs / 1_000_000.0,
            bestSource = best?.source,
            candidateDiametersPx = candidates.map { it.diameterPx },
            candidateSources = candidates.map { it.source },
            circleDiametersPassingGatesPx = circleDiameters.distinct().sorted(),
        )

        fun trace() = PlateDetectionTrace(frame.width, frame.height)
    }

    private data class Candidate(
        val center: ImagePoint,
        val diameterPx: Double,
        val confidence: Double,
        val perspectiveRatio: Double,
        val majorAxisPx: Double,
        val minorAxisPx: Double,
        val angleRadians: Double,
        val tapDistance: Double,
        val source: PlateDetectionSource,
    )
}
