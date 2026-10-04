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
) {
    enum class Stage(val label: String) {
        DECODE("decode"),
        BITMAP_TO_GRAY("bitmap_to_gray"),
        FRAME_COPY("frame_copy"),
        KLT_FORWARD_BACKWARD("klt_fb"),
        ROBUST_ESTIMATE("robust"),
        FEATURE_REPLENISHMENT("replenish"),
        PRESENTATION_MAPPING("presentation_mapping"),
        PROGRESS_PUBLISH("progress_publish"),
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

    fun <T> measure(stage: Stage, block: () -> T): T {
        if (!enabled) return block()
        val startedNs = System.nanoTime()
        return try {
            block()
        } finally {
            record(stage, System.nanoTime() - startedNs)
        }
    }

    @Synchronized
    fun record(stage: Stage, durationNs: Long, repetitions: Int = 1) {
        if (!enabled || repetitions <= 0) return
        val samples = durationsNs.getOrPut(stage) { mutableListOf() }
        val perSampleNs = (durationNs / repetitions).coerceAtLeast(0L)
        repeat(repetitions) { samples += perSampleNs }
    }

    @Synchronized
    fun reset() {
        durationsNs.clear()
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

    fun summary(): String = snapshot().entries.joinToString(separator = " | ") { (stage, stats) ->
        "${stage.label}: n=${stats.samples}, total=${"%.1f".format(stats.totalMs)} ms, " +
            "mean=${"%.2f".format(stats.meanMs)}, p50=${"%.2f".format(stats.p50Ms)}, " +
            "p95=${"%.2f".format(stats.p95Ms)}, max=${"%.2f".format(stats.maxMs)}"
    }

    private fun List<Long>.percentile(fraction: Double): Long {
        val index = ((size - 1) * fraction).toInt().coerceIn(indices)
        return this[index]
    }

    private fun Long.nsToMs(): Double = this / 1_000_000.0
    private fun Double.nsToMs(): Double = this / 1_000_000.0
}
