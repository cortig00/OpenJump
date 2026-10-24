package com.openjump.app.benchmark

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * S0b2b memory / thermal / battery observation for the encoder performance runner.
 *
 * Ownership and honesty rules:
 * - The sampler owns exactly one child job (`SupervisorJob` + `Dispatchers.Default`). No global
 *   executor, no trace, no forced GC and no runtime-counter reset: `art.gc.*` values are read as
 *   snapshots and deltas are only published for keys whose value moved forward (monotonic).
 * - `samplingEnabled = false` (the runner default) collects only the endpoint snapshot, taken after
 *   the core timer has stopped. Nothing runs during the measured window in that mode.
 * - `samplingEnabled = true` requires `intervalMs >= 1000`, runs inside the same wall-clock window
 *   as the core measurement and therefore *adds overhead to the measured wall time*; the run report
 *   says so instead of pretending the samples are free.
 * - Only scalar accumulators are kept (counts, min/max/sum), so a long run cannot grow the heap
 *   through this class. Sampled values are labelled as sampled maxima, never as process peaks.
 * - Sampler failures never replace a pipeline failure: an observation that throws is counted and
 *   reported as NOT_AVAILABLE, while the tracking/analysis exception keeps propagating.
 */
class EncoderPerfSampler(
    context: Context,
    val samplingEnabled: Boolean,
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    companion object {
        const val MINIMUM_INTERVAL_MS = 1000L
        const val DEFAULT_INTERVAL_MS = 1000L
    }

    init {
        require(intervalMs >= MINIMUM_INTERVAL_MS) {
            "sample_interval_ms must be >= $MINIMUM_INTERVAL_MS, was $intervalMs"
        }
    }

    private val appContext: Context = context.applicationContext ?: context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var job: Job? = null
    private var finished = false

    // Scalars only, written by the single child job and read after it has been joined.
    private var sampleCount = 0
    private var sampleFailures = 0
    private var observationCostTotalNs = 0L
    private var observationCostMaxNs = 0L
    private var intervalCount = 0
    private var intervalTotalNs = 0L
    private var intervalMinNs = Long.MAX_VALUE
    private var intervalMaxNs = 0L
    private var maxJavaHeapUsedBytes = 0L
    private var maxNativeHeapAllocatedBytes = 0L
    private var maxTotalPssKb = 0L
    private var maxRssKb = 0L
    private var maxThermalStatus = -1
    private var lastObservationError: String? = null

    private var runtimeStatsStart: Map<String, String>? = null
    private var runtimeStatsEnd: Map<String, String>? = null

    /** Freeze ART counters after observer stop/join, before codec close and output allocation. */
    fun captureEnd() { runtimeStatsEnd = runtimeStats() }

    /** Cheap snapshot taken before the core timer starts; it is not a measurement of the core. */
    fun captureStart() {
        if (runtimeStatsStart != null) return
        runtimeStatsStart = runtimeStats()
    }

    fun start() {
        check(!finished) { "sampler has already been stopped" }
        if (!samplingEnabled || job != null) return
        job = scope.launch {
            var previousStartNs = System.nanoTime()
            while (currentCoroutineContext().isActive) {
                val startNs = System.nanoTime()
                if (sampleCount > 0) recordInterval(startNs - previousStartNs)
                previousStartNs = startNs
                try {
                    observe()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    sampleFailures++
                    lastObservationError = error.javaClass.simpleName
                }
                val costNs = System.nanoTime() - startNs
                sampleCount++
                observationCostTotalNs += costNs
                if (costNs > observationCostMaxNs) observationCostMaxNs = costNs
                delay(intervalMs)
            }
        }
    }

    /**
     * Cancels and joins the owned child job. Safe to call when sampling was never enabled, and safe
     * while the caller is being cancelled: joining runs under [NonCancellable] so the child cannot
     * outlive the run and keep observing.
     */
    suspend fun stop() {
        if (finished) return
        finished = true
        val running = job
        job = null
        scope.coroutineContext[Job]?.cancel()
        running?.cancel()
        withContext(NonCancellable) { running?.join() }
    }

    private fun recordInterval(valueNs: Long) {
        val nonNegative = valueNs.coerceAtLeast(0L)
        intervalCount++
        intervalTotalNs += nonNegative
        if (nonNegative < intervalMinNs) intervalMinNs = nonNegative
        if (nonNegative > intervalMaxNs) intervalMaxNs = nonNegative
    }

    private fun observe() {
        val javaUsedBytes = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val nativeAllocatedBytes = Debug.getNativeHeapAllocatedSize()
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val rssKb = rssKb()
        val thermalStatus = thermalStatus()
        if (javaUsedBytes > maxJavaHeapUsedBytes) maxJavaHeapUsedBytes = javaUsedBytes
        if (nativeAllocatedBytes > maxNativeHeapAllocatedBytes) maxNativeHeapAllocatedBytes = nativeAllocatedBytes
        val totalPssKb = memoryInfo.totalPss.toLong()
        if (totalPssKb > maxTotalPssKb) maxTotalPssKb = totalPssKb
        if (rssKb != null && rssKb > maxRssKb) maxRssKb = rssKb
        if (thermalStatus != null && thermalStatus > maxThermalStatus) maxThermalStatus = thermalStatus
    }

    /**
     * Endpoint observation: memory + thermal + battery, collected once after the core timer stopped.
     * It is a sampled snapshot, not a peak and not a guarantee of the true process maximum.
     */
    fun endpointSnapshot(): JsonObject = try {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val runtime = Runtime.getRuntime()
        val endStats = runtimeStatsEnd ?: runtimeStats()
        obj(
            "status" to JsonPrimitive("REPORTED"),
            "collected_when" to JsonPrimitive("AFTER_CORE_AND_CODEC_CLOSE; ART_COUNTERS_FROZEN_BEFORE_CLOSE"),
            "sampling_enabled" to JsonPrimitive(samplingEnabled),
            "claim" to JsonPrimitive("SAMPLED_SNAPSHOT_NOT_A_PEAK; no strict RSS limit is asserted"),
            "capabilities" to obj(
                "debug_memory_info" to JsonPrimitive("AVAILABLE"),
                "debug_native_heap_allocated_size" to JsonPrimitive("AVAILABLE"),
                "debug_runtime_stats" to JsonPrimitive("AVAILABLE_ON_MIN_SDK_26"),
                "power_manager_thermal_status" to JsonPrimitive(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "AVAILABLE" else "NOT_AVAILABLE"),
                "proc_self_status_rss" to JsonPrimitive("BEST_EFFORT_GUARDED"),
                "battery_sticky_temperature" to JsonPrimitive("BEST_EFFORT_SIMULATED_ON_AVD"),
            ),
            "endpoint" to obj(
                "java_heap_used_bytes" to JsonPrimitive(runtime.totalMemory() - runtime.freeMemory()),
                "java_heap_max_bytes" to JsonPrimitive(runtime.maxMemory()),
                "native_heap_allocated_bytes" to JsonPrimitive(Debug.getNativeHeapAllocatedSize()),
                "native_heap_size_bytes" to JsonPrimitive(Debug.getNativeHeapSize()),
                "debug_total_pss_kb" to JsonPrimitive(memoryInfo.totalPss.toLong()),
                "debug_total_private_dirty_kb" to JsonPrimitive(memoryInfo.totalPrivateDirty.toLong()),
                "proc_self_vm_rss_kb" to (rssKb()?.let { JsonPrimitive(it) } ?: JsonNull),
                "thermal_status" to (thermalStatus()?.let { JsonPrimitive(thermalStatusLabel(it)) } ?: JsonPrimitive("NOT_AVAILABLE")),
                "battery" to batterySnapshot(),
                "system_memory" to systemMemorySnapshot(),
                "runtime_stats" to runtimeStatsElement(endStats),
                "runtime_stats_start" to (runtimeStatsStart?.let { runtimeStatsElement(it) } ?: JsonPrimitive("NOT_COLLECTED")),
                "runtime_stats_delta_valid_monotonic_only" to runtimeStatsDelta(endStats),
                "runtime_stats_scope" to JsonPrimitive("CORE_PLUS_OBSERVER_LAUNCH_STOP_JOIN; excludes codec close and output allocation"),
            ),
        )
    } catch (error: Exception) {
        obj("status" to JsonPrimitive("NOT_AVAILABLE"), "reason" to JsonPrimitive(error.javaClass.simpleName))
    }

    /**
     * Structured sampling summary. When sampling is off nothing was collected: an explicit
     * NOT_COLLECTED status is reported instead of a fabricated denominator.
     */
    fun sampledSummary(): JsonObject {
        if (!samplingEnabled) {
            return obj(
                "status" to JsonPrimitive("NOT_COLLECTED"),
                "reason" to JsonPrimitive("sample_memory=false; only the endpoint snapshot was collected"),
                "interval_ms_requested" to JsonPrimitive(intervalMs),
            )
        }
        val observationCostMeanNs = if (sampleCount == 0) null else observationCostTotalNs / sampleCount
        val intervalMeanNs = if (intervalCount == 0) null else intervalTotalNs / intervalCount
        val status = when {
            sampleCount == 0 -> "NOT_AVAILABLE"
            sampleFailures == sampleCount -> "NOT_AVAILABLE"
            else -> "REPORTED"
        }
        return obj(
            "status" to JsonPrimitive(status),
            "reason" to (
                if (status == "REPORTED") {
                    JsonNull
                } else {
                    JsonPrimitive("every observation failed or nothing was observed; the tracking result is reported separately")
                }
                ),
            "interval_ms_requested" to JsonPrimitive(intervalMs),
            "sample_count" to JsonPrimitive(sampleCount.toLong()),
            "failed_observations" to JsonPrimitive(sampleFailures.toLong()),
            "last_error" to (lastObservationError?.let { JsonPrimitive(it) } ?: JsonNull),
            "observation_cost_ns_mean" to (observationCostMeanNs?.let { JsonPrimitive(it) } ?: JsonNull),
            "observation_cost_ns_max" to (if (sampleCount == 0) JsonNull else JsonPrimitive(observationCostMaxNs)),
            "interval_count" to JsonPrimitive(intervalCount.toLong()),
            "interval_ns_min" to (if (intervalCount == 0) JsonNull else JsonPrimitive(intervalMinNs)),
            "interval_ns_mean" to (intervalMeanNs?.let { JsonPrimitive(it) } ?: JsonNull),
            "interval_ns_max" to (if (intervalCount == 0) JsonNull else JsonPrimitive(intervalMaxNs)),
            "sampled_maxima" to obj(
                "java_heap_used_bytes" to JsonPrimitive(maxJavaHeapUsedBytes),
                "native_heap_allocated_bytes" to JsonPrimitive(maxNativeHeapAllocatedBytes),
                "debug_total_pss_kb" to JsonPrimitive(maxTotalPssKb),
                "proc_self_vm_rss_kb" to (if (maxRssKb == 0L) JsonNull else JsonPrimitive(maxRssKb)),
                "thermal_status" to (if (maxThermalStatus < 0) JsonPrimitive("NOT_AVAILABLE") else JsonPrimitive(thermalStatusLabel(maxThermalStatus))),
            ),
            "overhead" to JsonPrimitive(
                "Sampling runs inside the core wall-clock window: its cost is included in the core timing, never subtracted",
            ),
            "claim" to JsonPrimitive("SAMPLED_MAXIMA_OF_OBSERVATIONS_NOT_PROCESS_PEAKS; no strict RSS limit asserted"),
        )
    }

    private fun systemMemorySnapshot(): JsonObject = try {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(info)
        obj(
            "available_bytes" to (manager?.let { JsonPrimitive(info.availMem) } ?: JsonNull),
            "total_bytes" to (manager?.let { JsonPrimitive(info.totalMem) } ?: JsonNull),
            "low_memory" to (manager?.let { JsonPrimitive(info.lowMemory) } ?: JsonNull),
        )
    } catch (_: Throwable) {
        obj("status" to JsonPrimitive("NOT_AVAILABLE"), "reason" to JsonPrimitive("ActivityManager.MemoryInfo unavailable"))
    }

    private fun batterySnapshot(): JsonObject = try {
        val sticky = ContextCompat.registerReceiver(
            appContext,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (sticky == null) {
            obj("status" to JsonPrimitive("NOT_AVAILABLE"), "reason" to JsonPrimitive("no sticky ACTION_BATTERY_CHANGED"))
        } else {
            fun intExtra(key: String): Int? = sticky.takeIf { it.hasExtra(key) }?.getIntExtra(key, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
            val temperatureDeciC = intExtra(BatteryManager.EXTRA_TEMPERATURE)
            obj(
                "status" to JsonPrimitive("REPORTED"),
                "temperature_deci_c" to (temperatureDeciC?.let { JsonPrimitive(it) } ?: JsonNull),
                "temperature_c_bits" to (temperatureDeciC?.let { EncoderPerformanceSupport.doubleBits(it / 10.0) } ?: JsonNull),
                "level" to (intExtra(BatteryManager.EXTRA_LEVEL)?.let { JsonPrimitive(it) } ?: JsonNull),
                "scale" to (intExtra(BatteryManager.EXTRA_SCALE)?.let { JsonPrimitive(it) } ?: JsonNull),
                "plugged" to (intExtra(BatteryManager.EXTRA_PLUGGED)?.let { JsonPrimitive(it) } ?: JsonNull),
                "status_code" to (intExtra(BatteryManager.EXTRA_STATUS)?.let { JsonPrimitive(it) } ?: JsonNull),
                "note" to JsonPrimitive("AVD battery/thermal values are simulated, not physical"),
            )
        }
    } catch (_: Throwable) {
        obj("status" to JsonPrimitive("NOT_AVAILABLE"), "reason" to JsonPrimitive("battery sticky intent unavailable"))
    }

    private fun thermalStatus(): Int? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    private fun thermalStatusLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN_$status"
    }

    private fun runtimeStats(): Map<String, String> = try {
        Debug.getRuntimeStats()
    } catch (_: Throwable) {
        emptyMap()
    }

    private fun runtimeStatsElement(stats: Map<String, String>): JsonElement = if (stats.isEmpty()) {
        obj("status" to JsonPrimitive("NOT_AVAILABLE"), "reason" to JsonPrimitive("Debug.getRuntimeStats returned nothing"))
    } else {
        obj(
            "status" to JsonPrimitive("RAW_AS_REPORTED_BY_ART"),
            "values" to JsonObject(stats.entries.associate { (key, value) -> key to JsonPrimitive(value) }),
        )
    }

    private fun runtimeStatsDelta(end: Map<String, String>): JsonElement {
        val start = runtimeStatsStart ?: return obj(
            "status" to JsonPrimitive("NOT_COLLECTED"),
            "reason" to JsonPrimitive("no start snapshot was captured"),
        )
        val deltas = linkedMapOf<String, JsonElement>()
        var skipped = 0
        start.keys.forEach { key ->
            val before = start[key]?.toLongOrNull()
            val after = end[key]?.toLongOrNull()
            if (before != null && after != null && after >= before) {
                deltas[key] = JsonPrimitive(after - before)
            } else {
                skipped++
            }
        }
        return obj(
            "status" to JsonPrimitive("RAW_DELTAS"),
            "rule" to JsonPrimitive("only keys parsed as longs whose end value >= start value; the rest are non-monotonic or unsupported"),
            "delta_valid_monotonic" to JsonObject(deltas),
            "skipped_keys" to JsonPrimitive(skipped),
            "reset_or_forced_gc" to JsonPrimitive(false),
        )
    }

    private fun rssKb(): Long? = try {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
        }
    } catch (_: Throwable) {
        null
    }

    private fun obj(vararg members: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*members))
}
