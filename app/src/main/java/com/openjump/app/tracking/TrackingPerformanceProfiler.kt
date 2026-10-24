package com.openjump.app.tracking

import java.util.EnumMap

/**
 * Lightweight in-process timings for the offline pipeline.
 *
 * Samples are aggregated and only formatted when a run finishes. Nothing is logged per frame, so
 * profiling cannot flood Logcat or trigger Compose work while tracking is active.
 */
class TrackingPerformanceProfiler(
    private val enabled: Boolean = true,
    /**
     * Opt-in pipeline details (new stages and counters). Off by default so production keeps the
     * legacy aggregate timings without detail clock reads or storage. Small branch/call costs remain;
     * enabled-versus-disabled overhead must be measured rather than assumed to be zero.
     */
    private val collectPipelineDetails: Boolean = false,
) {
    enum class Stage(val label: String, internal val detailed: Boolean = false) {
        DECODE("decode"),
        BITMAP_TO_GRAY("bitmap_to_gray"),
        FRAME_COPY("frame_copy"),
        KLT_FORWARD_BACKWARD("klt_fb"),
        ROBUST_ESTIMATE("robust"),
        FEATURE_REPLENISHMENT("replenish"),
        PRESENTATION_MAPPING("presentation_mapping"),
        PROGRESS_PUBLISH("progress_publish"),

        // Detail stages: never recorded unless collectPipelineDetails is on.
        DECODER_PREPARE("decoder_prepare", detailed = true),
        FRAME_COUNT_PROBE("frame_count_probe", detailed = true),
        // Same native attempt as legacy amortized DECODE; these totals are NOT additive.
        DECODE_BATCH("decode_batch", detailed = true),
        RESULTS_SNAPSHOT("results_snapshot", detailed = true),
    }

    /** Opt-in counters describing which decode path produced the converted frames. */
    enum class Metric(val label: String) {
        DECODE_BATCH_ATTEMPTS("decode_batch_attempts"),
        DECODE_BATCH_RETURNED_FRAMES("decode_batch_returned_frames"),
        DECODE_BATCH_FAILURES("decode_batch_failures"),
        DECODE_FALLBACK_FRAMES("decode_fallback_frames"),
        INDEXED_SINGLE_ATTEMPTS("indexed_single_attempts"),
        DELIVERED_FRAMES("delivered_frames"),
        DELIVERED_BITMAP_RGB565("delivered_bitmap_rgb565"),
        DELIVERED_BITMAP_ARGB8888("delivered_bitmap_argb8888"),
        DELIVERED_BITMAP_OTHER_CONFIG("delivered_bitmap_other_config"),
    }

    data class StageStats(
        val samples: Int,
        val totalMs: Double,
        val meanMs: Double,
        val p50Ms: Double,
        val p95Ms: Double,
        val maxMs: Double,
    )

    private val durationsNs = EnumMap<Stage, MutableList<Long>>(Stage::class.java)
    private val counterValues = LongArray(Metric.entries.size)

    /** Lets callers avoid optional platform metadata reads when details are not requested. */
    val pipelineDetailsEnabled: Boolean get() = enabled && collectPipelineDetails

    fun <T> measure(stage: Stage, block: () -> T): T {
        if (!enabled) return block()
        val startedNs = System.nanoTime()
        return try {
            block()
        } finally {
            record(stage, System.nanoTime() - startedNs)
        }
    }

    /**
     * Timing for a detail [Stage]. Returns before reading the clock when disabled or details are
     * off. The wrapper still has a branch/call cost; captured lambdas may also allocate.
     */
    fun <T> measureDetailed(stage: Stage, block: () -> T): T {
        if (!enabled || !collectPipelineDetails || !stage.detailed) return block()
        val startedNs = System.nanoTime()
        return try {
            block()
        } finally {
            record(stage, System.nanoTime() - startedNs)
        }
    }

    /** Opt-in counter increment; returns before taking the monitor when disabled or details are off. */
    fun count(metric: Metric, delta: Long = 1L) {
        if (!enabled || !collectPipelineDetails || delta <= 0L) return
        addCount(metric, delta)
    }

    @Synchronized
    fun record(stage: Stage, durationNs: Long, repetitions: Int = 1) {
        if (!enabled || repetitions <= 0) return
        if (stage.detailed && !collectPipelineDetails) return
        val samples = durationsNs.getOrPut(stage) { mutableListOf() }
        val perSampleNs = (durationNs / repetitions).coerceAtLeast(0L)
        repeat(repetitions) { samples += perSampleNs }
    }

    @Synchronized
    fun reset() {
        durationsNs.clear()
        counterValues.fill(0L)
    }

    /**
     * Immutable copy of every opt-in counter, including zero values when collected.
     * Empty means not collected; zero attempts must not be used as a rate denominator.
     */
    @Synchronized
    fun counters(): Map<Metric, Long> {
        if (!enabled || !collectPipelineDetails) return emptyMap()
        return Metric.entries.associateWith { counterValues[it.ordinal] }
    }

    @Synchronized
    private fun addCount(metric: Metric, delta: Long) {
        counterValues[metric.ordinal] = counterValues[metric.ordinal] + delta
    }

    @Synchronized
    fun snapshot(): Map<Stage, StageStats> = Stage.entries.mapNotNull { stage ->
        val values = durationsNs[stage]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val sorted = values.sorted()
        val totalNs = values.sum()
        stage to StageStats(
            samples = values.size,
            totalMs = totalNs.nsToMs(),
            meanMs = (totalNs.toDouble() / values.size).nsToMs(),
            p50Ms = sorted.percentile(0.50).nsToMs(),
            p95Ms = sorted.percentile(0.95).nsToMs(),
            maxMs = sorted.last().nsToMs(),
        )
    }.toMap()

    fun summary(): String {
        val stages = snapshot().entries.joinToString(separator = " | ") { (stage, stats) ->
            "${stage.label}: n=${stats.samples}, total=${"%.1f".format(stats.totalMs)} ms, " +
                "mean=${"%.2f".format(stats.meanMs)}, p50=${"%.2f".format(stats.p50Ms)}, " +
                "p95=${"%.2f".format(stats.p95Ms)}, max=${"%.2f".format(stats.maxMs)}"
        }
        val details = counters()
        if (details.isEmpty()) return stages
        val counterText = Metric.entries.joinToString(separator = ", ") {
            "${it.label}=${details.getValue(it)}"
        }
        return "$stages | counters: $counterText"
    }

    private fun List<Long>.percentile(fraction: Double): Long {
        val index = ((size - 1) * fraction).toInt().coerceIn(indices)
        return this[index]
    }

    private fun Long.nsToMs(): Double = this / 1_000_000.0
    private fun Double.nsToMs(): Double = this / 1_000_000.0
}
