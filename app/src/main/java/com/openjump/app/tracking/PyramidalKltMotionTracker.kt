package com.openjump.app.tracking

import boofcv.abst.feature.detect.interest.ConfigGeneralDetector
import boofcv.abst.feature.detect.interest.ConfigPointDetector
import boofcv.abst.feature.detect.interest.ConfigShiTomasi
import boofcv.abst.feature.detect.interest.PointDetectorTypes
import boofcv.abst.filter.derivative.ImageGradient
import boofcv.abst.tracker.PointTrackerKltPyramid
import boofcv.alg.feature.detect.interest.GeneralFeatureDetector
import boofcv.alg.filter.derivative.DerivativeType
import boofcv.alg.tracker.klt.ConfigPKlt
import boofcv.factory.feature.detect.interest.FactoryDetectPoint
import boofcv.factory.filter.derivative.FactoryDerivative
import boofcv.concurrency.BoofConcurrency
import boofcv.factory.tracker.FactoryPointTracker
import boofcv.struct.ConfigLength
import boofcv.struct.image.GrayS16
import boofcv.struct.image.GrayU8
import boofcv.struct.pyramid.ConfigDiscreteLevels
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * Tracks a manually seeded visual region using multiple Shi–Tomasi features and pyramidal KLT.
 * Translation is estimated robustly; a single bad feature can never move the whole target.
 */
class PyramidalKltMotionTracker(
    private val config: Config = Config(),
    private val performanceProfiler: TrackingPerformanceProfiler = TrackingPerformanceProfiler(enabled = false),
) : MotionTracker {

    companion object {
        init {
            // On Android/ART the multi-threaded Shi-Tomasi implementation spends more time in
            // ForkJoin/JIT synchronization than in useful work at this image size. The serial and
            // concurrent implementations selected the same ROI support in the parity fixtures,
            // while one worker was 2.3–2.5x faster. Configure it once, as recommended by BoofCV.
            BoofConcurrency.setMaxThreads(1)
        }
    }

    data class Config(
        val targetFeatures: Int = 48,
        val minimumFeatures: Int = 8,
        val absoluteMinimumFeatures: Int = 4,
        val featureSpacingPx: Double = 5.0,
        val forwardBackwardTolerancePx: Double = 1.5,
        val trackingConfidenceThreshold: Double = 0.60,
        val lostConfidenceThreshold: Double = 0.20,
        val maximumUncertainFrames: Int = 3,
    )

    private val gradient: ImageGradient<GrayU8, GrayS16> =
        FactoryDerivative.sobel(GrayU8::class.java, GrayS16::class.java)
    private val featureDetector: GeneralFeatureDetector<GrayU8, GrayS16> = run {
        val general = ConfigGeneralDetector().apply {
            maxFeatures = 1_500
            radius = 3
            threshold = 1f
        }
        val corners = ConfigShiTomasi().apply {
            radius = general.radius
            weighted = true
        }
        FactoryDetectPoint.createShiTomasi(general, corners, GrayS16::class.java, gradient.divisor())
    }

    private var klt: PointTrackerKltPyramid<GrayU8, GrayS16>? = null
    private var gray = GrayU8(1, 1)
    private var derivX = GrayS16(1, 1)
    private var derivY = GrayS16(1, 1)
    private var frameWidth = 0
    private var frameHeight = 0
    private var region: ImageRegion? = null
    private var lastTimestampUs: Long? = null
    private var uncertainFrames = 0
    private var lost = false

    override fun initialize(frame: GrayFrame, timestampUs: Long, seed: ImagePoint): TrackingResult {
        reset()
        require(timestampUs >= 0L)
        require(seed.x in 0.0..(frame.width - 1.0) && seed.y in 0.0..(frame.height - 1.0)) {
            "Seed must be inside the frame."
        }
        ensureEngine(frame)
        performanceProfiler.measure(TrackingPerformanceProfiler.Stage.FRAME_COPY) {
            copyFrame(frame)
        }

        val side = (min(frame.width, frame.height) * 0.16)
            .coerceIn(48.0, 96.0)
            .coerceAtMost(min(frame.width, frame.height) - 4.0)
        val initialRegion = ImageRegion(seed, side, side).clampedTo(frame.width, frame.height)
        region = initialRegion

        val tracker = requireNotNull(klt)
        performanceProfiler.measure(TrackingPerformanceProfiler.Stage.KLT_FORWARD_BACKWARD) {
            tracker.process(gray)
        }
        performanceProfiler.measure(TrackingPerformanceProfiler.Stage.FEATURE_REPLENISHMENT) {
            prepareForManualSeeding(tracker)
            seedFeatures(tracker, initialRegion)
        }
        val featurePoints = activeFeaturePoints(tracker)
        val coverage = RobustMotionEstimator.spatialCoverage(featurePoints, initialRegion)
        val support = (featurePoints.size.toDouble() / config.minimumFeatures).coerceIn(0.0, 1.0)
        val coverageScore = (coverage / 0.15).coerceIn(0.0, 1.0)
        val confidence = (support * coverageScore).coerceIn(0.0, 1.0).pow(0.5)
        val status = when {
            featurePoints.size < config.absoluteMinimumFeatures -> TrackingStatus.LOST
            featurePoints.size < config.minimumFeatures || confidence < config.trackingConfidenceThreshold ->
                TrackingStatus.UNCERTAIN
            else -> TrackingStatus.TRACKING
        }
        lastTimestampUs = timestampUs
        uncertainFrames = if (status == TrackingStatus.UNCERTAIN) 1 else 0
        lost = status == TrackingStatus.LOST

        return if (lost) {
            lostResult(timestampUs, featurePoints.size, featurePoints.size)
        } else {
            TrackingResult(
                sample = TrackingSample(timestampUs, initialRegion.center, confidence, status),
                region = initialRegion,
                featurePoints = featurePoints,
                diagnostics = TrackingDiagnostics(
                    attemptedFeatures = featurePoints.size,
                    validFeatures = featurePoints.size,
                    inlierFeatures = featurePoints.size,
                    medianResidualPx = 0.0,
                    spatialCoverage = coverage,
                ),
            )
        }
    }

    override fun track(frame: GrayFrame, timestampUs: Long): TrackingResult {
        if (lost) return lostResult(timestampUs, 0, 0)
        val previousTimestamp = requireNotNull(lastTimestampUs) { "Initialize the tracker first." }
        require(timestampUs > previousTimestamp) { "Tracking timestamps must increase." }
        require(frame.width == frameWidth && frame.height == frameHeight) {
            "All tracking frames must have the same dimensions."
        }
        val previousRegion = requireNotNull(region)
        val tracker = requireNotNull(klt)
        val previousPositions = tracker.getActiveTracks(null).associate { track ->
            track.featureId to ImagePoint(track.pixel.x, track.pixel.y)
        }
        val attempted = previousPositions.size

        performanceProfiler.measure(TrackingPerformanceProfiler.Stage.FRAME_COPY) {
            copyFrame(frame)
        }
        performanceProfiler.measure(TrackingPerformanceProfiler.Stage.KLT_FORWARD_BACKWARD) {
            tracker.process(gray)
        }
        val trackedMotions = tracker.getActiveTracks(null).mapNotNull { track ->
            previousPositions[track.featureId]?.let { previous ->
                track to FeatureMotion(previous, ImagePoint(track.pixel.x, track.pixel.y))
            }
        }
        val motions = trackedMotions.map { it.second }
        val estimate = performanceProfiler.measure(TrackingPerformanceProfiler.Stage.ROBUST_ESTIMATE) {
            RobustMotionEstimator.estimate(
                attemptedFeatures = attempted,
                motions = motions,
                region = previousRegion,
                minimumSupport = config.minimumFeatures,
            )
        }
        lastTimestampUs = timestampUs

        if (estimate == null ||
            estimate.inliers.size < config.absoluteMinimumFeatures ||
            estimate.confidence < config.lostConfidenceThreshold
        ) {
            lost = true
            return lostResult(timestampUs, attempted, motions.size)
        }

        val nextRegion = previousRegion.moved(estimate.dx, estimate.dy).clampedTo(frameWidth, frameHeight)
        region = nextRegion
        val weak = estimate.inliers.size < config.minimumFeatures ||
            estimate.confidence < config.trackingConfidenceThreshold ||
            estimate.diagnostics.spatialCoverage < 0.05
        uncertainFrames = if (weak) uncertainFrames + 1 else 0
        if (uncertainFrames >= config.maximumUncertainFrames) {
            lost = true
            return TrackingResult(
                sample = TrackingSample(timestampUs, null, estimate.confidence, TrackingStatus.LOST),
                region = null,
                featurePoints = emptyList(),
                diagnostics = estimate.diagnostics,
            )
        }

        // Keep mature object support instead of replacing every feature on every frame. A newly
        // detected background corner can only influence motion after surviving KLT and the robust
        // motion gate; geometrically inconsistent tracks are removed before replenishment.
        val inliers = estimate.inliers.toSet()
        trackedMotions.filter { it.second !in inliers }.forEach { (track, _) -> tracker.dropTrack(track) }
        performanceProfiler.measure(TrackingPerformanceProfiler.Stage.FEATURE_REPLENISHMENT) {
            prepareForManualSeeding(tracker)
            seedFeatures(tracker, nextRegion)
        }
        val currentFeatures = activeFeaturePoints(tracker)

        return TrackingResult(
            sample = TrackingSample(
                timestampUs = timestampUs,
                point = nextRegion.center,
                confidence = estimate.confidence,
                status = if (weak) TrackingStatus.UNCERTAIN else TrackingStatus.TRACKING,
            ),
            region = nextRegion,
            featurePoints = currentFeatures,
            diagnostics = estimate.diagnostics,
        )
    }

    override fun reset() {
        klt = null
        frameWidth = 0
        frameHeight = 0
        region = null
        lastTimestampUs = null
        uncertainFrames = 0
        lost = false
    }

    private fun ensureEngine(frame: GrayFrame) {
        frameWidth = frame.width
        frameHeight = frame.height
        gray = GrayU8(frame.width, frame.height)
        derivX = GrayS16(frame.width, frame.height)
        derivY = GrayS16(frame.width, frame.height)
        val kltConfig = ConfigPKlt().apply {
            templateRadius = 4
            toleranceFB = this@PyramidalKltMotionTracker.config.forwardBackwardTolerancePx
            pyramidLevels = ConfigDiscreteLevels.minSize(24)
            maximumTracks = ConfigLength.fixed(this@PyramidalKltMotionTracker.config.targetFeatures.toDouble())
            this.config.maxPerPixelError = 30f
            this.config.maxIterations = 20
        }
        val detectorConfig = ConfigPointDetector().apply {
            type = PointDetectorTypes.SHI_TOMASI
            general.maxFeatures = config.targetFeatures
            general.radius = 3
            general.threshold = 1f
        }
        klt = FactoryPointTracker.klt(
            kltConfig,
            DerivativeType.SOBEL,
            detectorConfig,
            GrayU8::class.java,
            GrayS16::class.java,
        )
    }

    private fun copyFrame(frame: GrayFrame) {
        gray.reshape(frame.width, frame.height)
        frame.pixels.copyInto(gray.data, destinationOffset = 0, startIndex = 0, endIndex = frame.pixels.size)
    }

    /**
     * PointTrackerKltPyramid leaves its low-level descriptor on the previous pyramid after
     * forward-backward validation. spawnTracks() selects the current pyramid. Only its temporary
     * global tracks are discarded; mature ROI tracks must retain their identity and descriptors.
     */
    private fun prepareForManualSeeding(tracker: PointTrackerKltPyramid<GrayU8, GrayS16>) {
        val activeTracks = tracker.totalActive
        if (activeTracks == 0) {
            // Initialization still needs spawnTracks() to select the current pyramid. Its temporary
            // global tracks are discarded before the manually constrained ROI is populated.
            tracker.spawnTracks()
            tracker.getNewTracks(null).toList().forEach(tracker::dropTrack)
            return
        }

        // FB validation leaves the low-level KLT descriptor on prevPyr. BoofCV's spawnTracks()
        // switches it back to currPyr before checking the track limit. Temporarily making that
        // limit equal to the active support reaches the early return without a full-frame detector
        // pass and without creating or replacing any track.
        val originalMaximumTracks = tracker.configMaxTracks.copy()
        tracker.configMaxTracks = ConfigLength.fixed(activeTracks.toDouble())
        try {
            tracker.spawnTracks()
            check(tracker.getNewTracks(null).isEmpty()) {
                "Selecting the current KLT pyramid must not spawn global tracks."
            }
        } finally {
            tracker.configMaxTracks = originalMaximumTracks
        }
    }

    private fun seedFeatures(
        tracker: PointTrackerKltPyramid<GrayU8, GrayS16>,
        targetRegion: ImageRegion,
    ) {
        val selected = activeFeaturePoints(tracker).toMutableList()
        if (selected.size >= config.targetFeatures) return

        derivX.reshape(gray.width, gray.height)
        derivY.reshape(gray.width, gray.height)
        gradient.process(gray, derivX, derivY)
        featureDetector.process(gray, derivX, derivY, null, null, null)
        val margin = 6.0
        val candidates = featureDetector.maximums.toList()
            .map { ImagePoint(it.x.toDouble(), it.y.toDouble()) }
            .filter { targetRegion.contains(it, margin) }
            .sortedBy { hypot(it.x - targetRegion.center.x, it.y - targetRegion.center.y) }
        // New one-frame tracks must not be able to outvote the mature inlier set on the next frame.
        // Grow support gradually; initialization is the only time the ROI is filled from scratch.
        val maximumNewFeatures = if (selected.isEmpty()) {
            config.targetFeatures
        } else {
            minOf(config.targetFeatures - selected.size, selected.size)
        }
        var added = 0
        for (candidate in candidates) {
            if (selected.all { hypot(it.x - candidate.x, it.y - candidate.y) >= config.featureSpacingPx } &&
                tracker.addTrack(candidate.x, candidate.y) != null
            ) {
                selected += candidate
                added++
                if (added >= maximumNewFeatures) break
            }
        }
    }

    private fun activeFeaturePoints(tracker: PointTrackerKltPyramid<GrayU8, GrayS16>): List<ImagePoint> =
        tracker.getActiveTracks(null).map { ImagePoint(it.pixel.x, it.pixel.y) }

    private fun lostResult(timestampUs: Long, attempted: Int, valid: Int): TrackingResult = TrackingResult(
        sample = TrackingSample(timestampUs, null, 0.0, TrackingStatus.LOST),
        region = null,
        featurePoints = emptyList(),
        diagnostics = TrackingDiagnostics(
            attemptedFeatures = attempted.coerceAtLeast(valid),
            validFeatures = valid,
            inlierFeatures = 0,
            medianResidualPx = 0.0,
            spatialCoverage = 0.0,
        ),
    )
}
