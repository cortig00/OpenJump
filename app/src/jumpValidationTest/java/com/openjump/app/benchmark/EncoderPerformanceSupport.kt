package com.openjump.app.benchmark

import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderMetric
import com.openjump.app.encoder.EncoderRepetition
import com.openjump.app.encoder.EncoderSample
import com.openjump.app.encoder.MetricPoint
import com.openjump.app.encoder.MovementPhase
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.ImageRegion
import com.openjump.app.tracking.TrackingDiagnostics
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.TrackingResult
import com.openjump.app.tracking.TrackingSample
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * S0b2a support for the encoder high-FPS benchmark. Everything here is pure JVM (also usable from
 * `androidTest`, because `src/jumpValidationTest` feeds both source sets) and deliberately has no
 * timing, device, provenance or performance claim of its own.
 *
 * Frozen encoding rules for the next runner:
 * - Every `Double`/`Double?` becomes the 16-character lowercase hex of
 *   [Double.doubleToRawLongBits], so `-0.0` vs `0.0`, `NaN` payloads and subnormals stay exactly
 *   distinguishable and are never collapsed to JSON `null`.
 * - `null` is always an explicit `JsonNull` member: defaults, nulls and empty collections are never
 *   omitted, so on/off snapshots are compared with no implicit whitelist.
 * - Enums serialize as `name`; integer identities, indices and PTS stay JSON integers (no float
 *   round-trip).
 * - Every constructor-declared property of a serialized domain type is included. Computed
 *   accessors (`VideoTimingDecision.isReliable/timeScale`, `ImageRegion.left/top/right/bottom`,
 *   `EncoderSample.verticalPositionM/verticalVelocityMps`) are exact functions of serialized
 *   properties, so duplicating them would add bytes but no discriminating power; the
 *   analysis-level derived values named by the contract live under `analysis.derived`.
 * - No timing, provenance, URI or note material is part of the snapshot.
 * - `firstDifference` compares **only** `outputSnapshot` outputs and always reports the first
 *   divergent path (missing/extra key, kind/type, array size/order, or primitive token).
 */
object EncoderPerformanceSupport {
    const val OUTPUT_SNAPSHOT_SCHEMA = "encoder_perf_output_snapshot"
    const val OUTPUT_SNAPSHOT_SCHEMA_VERSION = 1

    const val REPORT_SCHEMA = "encoder_perf_run_report"
    const val REPORT_SCHEMA_VERSION = 1
    const val REPORT_FILE_PREFIX = "encoder_perf_"

    const val STATUS_RUNNING = "RUNNING"
    const val STATUS_PASSED = "PASSED"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_CANCELLED = "CANCELLED"
    const val STATUS_NOT_RUN = "NOT_RUN"
    const val STATUS_INCOMPLETE = "INCOMPLETE"
    const val ACCURACY_NOT_RUN = "NOT_RUN"

    val REPORT_STATUSES: List<String> = listOf(
        STATUS_RUNNING,
        STATUS_PASSED,
        STATUS_FAILED,
        STATUS_CANCELLED,
        STATUS_NOT_RUN,
        STATUS_INCOMPLETE,
    )

    /** Statuses after which the runner must stop writing for that run id. */
    val TERMINAL_STATUSES: List<String> = listOf(
        STATUS_PASSED,
        STATUS_FAILED,
        STATUS_CANCELLED,
        STATUS_NOT_RUN,
        STATUS_INCOMPLETE,
    )

    private val SAFE_RUN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val SAFE_MEMBER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val reportJson = Json { prettyPrint = true }
    private const val HEX = "0123456789abcdef"

    fun isSafeRunId(runId: String): Boolean = SAFE_RUN_ID.matches(runId)

    /** Raw-bit encoding: 16 lowercase hex chars, or an explicit `JsonNull` for `null`. */
    fun doubleBits(value: Double?): JsonElement =
        value?.let { JsonPrimitive(rawBitsHex(it.toRawBits())) } ?: JsonNull

    private fun rawBitsHex(bits: Long): String {
        val chars = CharArray(16)
        var remaining = bits
        for (index in 15 downTo 0) {
            chars[index] = HEX[(remaining and 0xF).toInt()]
            remaining = remaining ushr 4
        }
        return String(chars)
    }

    /**
     * Full-fidelity structural snapshot of the tracking input plus the analyzer output. It is the
     * only thing `firstDifference` is meant to compare; write it beside a run report when a
     * byte-level on/off comparison has to be audited later.
     */
    fun outputSnapshot(tracking: List<TrackingFrameResult>, analysis: EncoderAnalysis): JsonObject = obj(
        "schema" to JsonPrimitive(OUTPUT_SNAPSHOT_SCHEMA),
        "schema_version" to JsonPrimitive(OUTPUT_SNAPSHOT_SCHEMA_VERSION),
        "tracking" to JsonArray(tracking.map { trackingFrame(it) }),
        "analysis" to analysisElement(analysis),
    )

    /**
     * First divergent path between two `outputSnapshot` trees, or `null` when they are identical.
     * Object key order is irrelevant; arrays are ordered and compared index by index. Primitive
     * comparison uses the JSON token text only, so there is no numeric tolerance: `1` differs from
     * `1.0`, and `null` differs from any value. Paths look like
     * `$.analysis.samples[1].source_pts_us` and are suffixed with the reason, e.g.
     * ` (type expected OBJECT actual NULL)` or ` (key unexpected in actual)`.
     */
    fun firstDifference(expected: JsonElement, actual: JsonElement): String? = difference("$", expected, actual)

    /**
     * Machine-readable run report. `accuracy` is always [ACCURACY_NOT_RUN]: this helper never claims
     * coverage, accuracy or a PASS decision — the runner decides that. `speedup` is copied from
     * `measurements["speedup"]` **only** for [STATUS_PASSED]; every other status (including terminal
     * failures and incomplete runs) reports `JsonNull`, so a failed or unfinished run can never
     * carry a speedup claim.
     */
    fun buildRunReport(
        runId: String,
        status: String,
        provenance: JsonObject,
        measurements: JsonObject,
        output: JsonObject? = null,
        error: String? = null,
    ): JsonObject {
        require(isSafeRunId(runId)) { "unsafe run id (expected ${SAFE_RUN_ID.pattern}): '$runId'" }
        require(status in REPORT_STATUSES) { "unknown run status '$status'; expected one of $REPORT_STATUSES" }
        val speedup = if (status == STATUS_PASSED) measurements["speedup"] else null
        return obj(
            "schema" to JsonPrimitive(REPORT_SCHEMA),
            "schema_version" to JsonPrimitive(REPORT_SCHEMA_VERSION),
            "run_id" to JsonPrimitive(runId),
            "status" to JsonPrimitive(status),
            "accuracy" to JsonPrimitive(ACCURACY_NOT_RUN),
            "speedup" to (speedup ?: JsonNull),
            "provenance" to provenance,
            "measurements" to measurements,
            "output" to (output ?: JsonNull),
            "error" to (error?.let { JsonPrimitive(it) } ?: JsonNull),
        )
    }

    /**
     * Unique report path for one run. The runner must call this **once, before the first write**:
     * an existing file means the run id already has a report, so the collision is rejected instead
     * of silently overwriting another run's evidence. Reuse the returned [File] for the atomic
     * updates of that same run. Never returns `encoder_benchmark_results.json`.
     */
    fun distinctReportFile(directory: File, runId: String): File {
        require(isSafeRunId(runId)) { "unsafe run id (expected ${SAFE_RUN_ID.pattern}): '$runId'" }
        require(directory.isDirectory || directory.mkdirs()) { "cannot use report directory: ${directory.path}" }
        val file = File(directory, "$REPORT_FILE_PREFIX$runId.json")
        require(file.name != ENCODER_BENCHMARK_REPORT_FILE_NAME) {
            "refusing to use the frozen benchmark report name ${ENCODER_BENCHMARK_REPORT_FILE_NAME}"
        }
        require(!file.exists()) { "run id '$runId' already has a report at ${file.path}" }
        return file
    }

    /** Atomic sibling replacement, streamed to avoid a second full-report String/byte buffer. */
    @OptIn(ExperimentalSerializationApi::class)
    fun writeReport(file: File, report: JsonObject) {
        require(file.name != ENCODER_BENCHMARK_REPORT_FILE_NAME) {
            "refusing to overwrite the frozen benchmark report ${ENCODER_BENCHMARK_REPORT_FILE_NAME}"
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile ?: File("."), ".${file.name}.${System.nanoTime()}.tmp")
        try {
            temporary.outputStream().buffered().use { stream ->
                reportJson.encodeToStream(JsonObject.serializer(), report, stream)
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** SHA-256 of the exact compact JSON bytes, without materializing the entire encoding. */
    @OptIn(ExperimentalSerializationApi::class)
    fun snapshotSha256(output: JsonObject): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
        DigestOutputStream(sink, digest).use { stream ->
            Json.encodeToStream(JsonObject.serializer(), output, stream)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Descriptive statistics for one stage's raw latencies, in nanoseconds.
     *
     * Percentiles use the nearest-rank definition: samples are sorted ascending and the k-th value
     * of `k = ceil(p * n)` (0-based `k - 1`, clamped to `1..n`) is reported, so `n == 1` yields that
     * single sample. An empty list is an explicit [STATUS_NOT_RUN] with `JsonNull` metrics, never a
     * fabricated zero. Small samples (for example three cold runs) are not statistical evidence:
     * report per-frame stages separately and do not present such a p95 as a percentile result.
     */
    fun summarizeTimings(samplesNs: List<Long>): JsonObject {
        require(samplesNs.all { it >= 0L }) { "timing samples must be non-negative nanoseconds" }
        val count = samplesNs.size
        if (count == 0) {
            return obj(
                "status" to JsonPrimitive(STATUS_NOT_RUN),
                "sample_count" to JsonPrimitive(0),
                "total_ns" to JsonNull,
                "max_ns" to JsonNull,
                "mean_ns_bits" to JsonNull,
                "median_ns_bits" to JsonNull,
                "p95_ns_bits" to JsonNull,
                "percentile_method" to JsonNull,
                "reason" to JsonPrimitive("no timing samples"),
            )
        }
        val sorted = samplesNs.sorted()
        val total = samplesNs.fold(0L) { accumulator, sample -> Math.addExact(accumulator, sample) }
        return obj(
            "status" to JsonPrimitive("REPORTED"),
            "sample_count" to JsonPrimitive(count),
            "total_ns" to JsonPrimitive(total),
            "max_ns" to JsonPrimitive(sorted.last()),
            "mean_ns_bits" to doubleBits(total.toDouble() / count),
            "median_ns_bits" to doubleBits(nearestRank(sorted, 0.5).toDouble()),
            "p95_ns_bits" to doubleBits(nearestRank(sorted, 0.95).toDouble()),
            "percentile_method" to JsonPrimitive(
                "NEAREST_RANK_CEIL: ascending samples, p-th value index = ceil(p*n)-1 clamped to 0..n-1",
            ),
            "reason" to JsonNull,
        )
    }

    private fun nearestRank(sorted: List<Long>, probability: Double): Long {
        val rank = kotlin.math.ceil(probability * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun trackingFrame(frame: TrackingFrameResult): JsonObject = obj(
        "frame_index" to JsonPrimitive(frame.frameIndex),
        "result" to trackingResult(frame.result),
    )

    private fun trackingResult(result: TrackingResult): JsonObject = obj(
        "sample" to trackingSample(result.sample),
        "region" to region(result.region),
        "feature_points" to JsonArray(result.featurePoints.map { pointElement(it) }),
        "diagnostics" to diagnostics(result.diagnostics),
    )

    private fun trackingSample(sample: TrackingSample): JsonObject = obj(
        "timestamp_us" to JsonPrimitive(sample.timestampUs),
        "point" to pointElement(sample.point),
        "confidence" to doubleBits(sample.confidence),
        "status" to JsonPrimitive(sample.status.name),
    )

    private fun diagnostics(diagnostics: TrackingDiagnostics): JsonObject = obj(
        "attempted_features" to JsonPrimitive(diagnostics.attemptedFeatures),
        "valid_features" to JsonPrimitive(diagnostics.validFeatures),
        "inlier_features" to JsonPrimitive(diagnostics.inlierFeatures),
        "median_residual_px" to doubleBits(diagnostics.medianResidualPx),
        "spatial_coverage" to doubleBits(diagnostics.spatialCoverage),
    )

    private fun region(region: ImageRegion?): JsonElement = region?.let {
        obj(
            "center" to pointElement(it.center),
            "width" to doubleBits(it.width),
            "height" to doubleBits(it.height),
        )
    } ?: JsonNull

    private fun analysisElement(analysis: EncoderAnalysis): JsonObject = obj(
        "setup" to obj(
            "exercise" to JsonPrimitive(analysis.setup.exercise.name),
            "load_kg" to doubleBits(analysis.setup.loadKg),
        ),
        "timing_decision" to obj(
            "mode" to JsonPrimitive(analysis.timingDecision.mode.name),
            "slow_motion_factor" to doubleBits(analysis.timingDecision.slowMotionFactor),
        ),
        "calibration" to obj(
            "frame_index" to JsonPrimitive(analysis.calibration.frameIndex),
            "point_a" to pointElement(analysis.calibration.pointA),
            "point_b" to pointElement(analysis.calibration.pointB),
            "reference_length_m" to doubleBits(analysis.calibration.referenceLengthM),
            "meters_per_pixel" to doubleBits(analysis.calibration.metersPerPixel),
        ),
        "samples" to JsonArray(analysis.samples.map { sampleElement(it) }),
        "repetitions" to JsonArray(analysis.repetitions.map { repetitionElement(it) }),
        "effective_fps" to doubleBits(analysis.effectiveFps),
        "smoothing_window_samples" to JsonPrimitive(analysis.smoothingWindowSamples),
        "velocity_enter_mps" to doubleBits(analysis.velocityEnterMps),
        "velocity_exit_mps" to doubleBits(analysis.velocityExitMps),
        "warnings" to JsonArray(analysis.warnings.map { JsonPrimitive(it) }),
        "config" to configElement(analysis),
        "derived" to derivedElement(analysis),
    )

    private fun sampleElement(sample: EncoderSample): JsonObject = obj(
        "ordinal" to JsonPrimitive(sample.ordinal),
        "frame_index" to JsonPrimitive(sample.frameIndex),
        "source_pts_us" to JsonPrimitive(sample.sourcePtsUs),
        "physical_time_us" to (sample.physicalTimeUs?.let { JsonPrimitive(it) } ?: JsonNull),
        "raw_pixel_point" to pointElement(sample.rawPixelPoint),
        "raw_metric_point" to metricPointElement(sample.rawMetricPoint),
        "smoothed_metric_point" to metricPointElement(sample.smoothedMetricPoint),
        "velocity_x_mps" to doubleBits(sample.velocityXMps),
        "velocity_y_mps" to doubleBits(sample.velocityYMps),
        "confidence" to doubleBits(sample.confidence),
        "tracking_status" to JsonPrimitive(sample.trackingStatus.name),
        "observation_kind" to JsonPrimitive(sample.observationKind.name),
        "fit_residual_m" to doubleBits(sample.fitResidualM),
        "gap_before_us" to (sample.gapBeforeUs?.let { JsonPrimitive(it) } ?: JsonNull),
    )

    private fun repetitionElement(repetition: EncoderRepetition): JsonObject = obj(
        "ordinal" to JsonPrimitive(repetition.ordinal),
        "start_sample" to JsonPrimitive(repetition.startSample),
        "end_sample" to JsonPrimitive(repetition.endSample),
        "eccentric_phase" to phaseElement(repetition.eccentricPhase),
        "concentric_phase" to phaseElement(repetition.concentricPhase),
        "quality" to JsonPrimitive(repetition.quality.name),
        // A set has no meaningful order: canonicalize by enum ordinal so insertion order cannot
        // change the snapshot, while ordered lists keep their original order.
        "reasons" to JsonArray(repetition.reasons.sortedBy { it.ordinal }.map { JsonPrimitive(it.name) }),
        "metrics" to JsonArray(repetition.metrics.map { metricElement(it) }),
    )

    private fun phaseElement(phase: MovementPhase): JsonObject = obj(
        "direction" to JsonPrimitive(phase.direction.name),
        "start_sample" to JsonPrimitive(phase.startSample),
        "end_sample" to JsonPrimitive(phase.endSample),
    )

    private fun metricElement(metric: EncoderMetric): JsonObject = obj(
        "key" to JsonPrimitive(metric.key.name),
        "value" to doubleBits(metric.value),
        "unit" to JsonPrimitive(metric.unit.name),
        "validity" to JsonPrimitive(metric.validity.name),
        "reason" to (metric.reason?.let { JsonPrimitive(it.name) } ?: JsonNull),
    )

    private fun configElement(analysis: EncoderAnalysis): JsonObject = with(analysis.config) {
        obj(
            "version" to JsonPrimitive(version),
            "minimum_velocity_mps" to doubleBits(minimumVelocityMps),
            "noise_multiplier" to doubleBits(noiseMultiplier),
            "exit_ratio" to doubleBits(exitRatio),
            "minimum_direction_hold_us" to JsonPrimitive(minimumDirectionHoldUs),
            "stationary_duration_us" to JsonPrimitive(stationaryDurationUs),
            "pause_duration_us" to JsonPrimitive(pauseDurationUs),
            "minimum_phase_duration_us" to JsonPrimitive(minimumPhaseDurationUs),
            "minimum_phase_displacement_m" to doubleBits(minimumPhaseDisplacementM),
            "rom_outlier_fraction" to doubleBits(romOutlierFraction),
            "minimum_median_confidence" to doubleBits(minimumMedianConfidence),
            "maximum_uncertain_fraction" to doubleBits(maximumUncertainFraction),
        )
    }

    private fun derivedElement(analysis: EncoderAnalysis): JsonObject = obj(
        "valid_repetition_ordinals" to JsonArray(analysis.validRepetitions.map { JsonPrimitive(it.ordinal) }),
        "best_mcv" to doubleBits(analysis.bestMcv),
        "set_velocity_loss" to doubleBits(analysis.setVelocityLoss),
    )

    private fun metricPointElement(point: MetricPoint?): JsonElement = point?.let {
        obj("x" to doubleBits(it.x), "y" to doubleBits(it.y))
    } ?: JsonNull

    private fun pointElement(point: ImagePoint?): JsonElement = point?.let {
        obj("x" to doubleBits(it.x), "y" to doubleBits(it.y))
    } ?: JsonNull

    private fun obj(vararg members: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*members))

    private fun difference(path: String, expected: JsonElement, actual: JsonElement): String? {
        if (expected is JsonObject && actual is JsonObject) {
            for (key in expected.keys) {
                if (!actual.containsKey(key)) return "$path${member(key)} (key missing in actual)"
            }
            for (key in actual.keys) {
                if (!expected.containsKey(key)) return "$path${member(key)} (key unexpected in actual)"
            }
            for (key in expected.keys) {
                difference(memberPath(path, key), expected.getValue(key), actual.getValue(key))?.let { return it }
            }
            return null
        }
        if (expected is JsonArray && actual is JsonArray) {
            if (expected.size != actual.size) return "$path (size expected ${expected.size} actual ${actual.size})"
            for (index in expected.indices) {
                difference("$path[$index]", expected[index], actual[index])?.let { return it }
            }
            return null
        }
        val expectedKind = kindOf(expected)
        val actualKind = kindOf(actual)
        if (expectedKind != actualKind) return "$path (type expected $expectedKind actual $actualKind)"
        if (expected is JsonPrimitive && actual is JsonPrimitive && expected.content != actual.content) {
            return "$path ($expectedKind expected ${token(expected.content)} actual ${token(actual.content)})"
        }
        return null
    }

    /** `JsonNull` and a `null` literal are both a real, distinct kind. */
    private fun kindOf(element: JsonElement): String = when (element) {
        is JsonObject -> "OBJECT"
        is JsonArray -> "ARRAY"
        is JsonNull -> "NULL"
        is JsonPrimitive -> if (!element.isString && element.content == "null") "NULL" else if (element.isString) "STRING" else "LITERAL"
    }

    private fun memberPath(path: String, key: String): String = path + member(key)

    private fun member(key: String): String =
        if (SAFE_MEMBER.matches(key)) ".$key" else "[\"${key.replace("\"", "\\\"")}\"]"

    private fun token(value: String): String {
        val single = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\\\"")
        return if (single.length <= 64) "\"$single\"" else "\"${single.take(64)}...\"(${value.length} chars)"
    }
}
