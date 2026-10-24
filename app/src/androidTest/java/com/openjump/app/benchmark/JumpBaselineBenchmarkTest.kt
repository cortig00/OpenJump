package com.openjump.app.benchmark

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.math.ProtocolCalculator
import com.openjump.app.protocol.MetricKey
import com.openjump.app.video.VideoFrameIndexer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class JumpBaselineBenchmarkTest {
    private val reportName = "jump_release_qualification.json"

    @Test
    fun runFullJumpReleaseBenchmark() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val hasFixtureDirArg = arguments.containsKey("jump_fixture_dir")
        val fixtureDirArg = arguments.getString("jump_fixture_dir")
        // An absent argument means this opt-in test is safely skipped. Empty is supplied input and fails.
        assumeTrue("jump_fixture_dir not supplied; release benchmark is opt-in", hasFixtureDirArg)
        val results = JSONArray()
        val failures = mutableListOf<String>()
        val report = JSONObject()
            .put("schema", "com.openjump.jump-release-report")
            .put("schema_version", 1)
            .put("corpus_version", JSONObject.NULL)
            .put("candidate_sha", arguments.getString("candidate_sha") ?: "UNKNOWN")
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT))
            .put("total_trials", 0)
            .put("results", results)
            .put("status", "ERROR")
        var cancellation: CancellationException? = null
        try {
            val fixtureDir = File(requireNotNull(fixtureDirArg))
            val catalog = JumpGoldenCatalog.parse(
                context.assets.open("golden_set.json").bufferedReader().use { it.readText() },
            )
            report.put("corpus_version", catalog.corpusVersion)
            report.put("total_trials", catalog.trials.size)
            catalog.trials.forEach { trial ->
                val entry = JSONObject()
                    .put("id", "${trial.test_id}/${trial.trial_id}")
                    .put("test_id", trial.test_id)
                    .put("trial_id", trial.trial_id)
                    .put("protocol", trial.protocol)
                    .put("status", "ERROR")
                    .put("ballistic_expected", JSONObject().apply {
                        put("flight_time_ms", trial.ballistic_expected.flight_time_ms)
                        put("height_cm", trial.ballistic_expected.height_cm)
                        putOpt("takeoff_velocity_mps", trial.ballistic_expected.takeoff_velocity_mps)
                        putOpt("time_to_takeoff_ms", trial.ballistic_expected.time_to_takeoff_ms)
                        putOpt("rsi", trial.ballistic_expected.rsi)
                        putOpt("rsi_mod", trial.ballistic_expected.rsi_mod)
                        putOpt("contact_time_ms", trial.ballistic_expected.contact_time_ms)
                    })
                    .put("instrument_measurement", JSONObject().apply {
                        putOpt("height_cm", trial.instrument_measurement.height_cm)
                        putOpt("contact_time_ms", trial.instrument_measurement.contact_time_ms)
                        putOpt("method", trial.instrument_measurement.method)
                        putOpt("device", trial.instrument_measurement.device)
                        putOpt("provenance", trial.instrument_measurement.provenance)
                    })
                try {
                    val clip = File(fixtureDir, trial.clip_file)
                    require(clip.isFile && clip.canRead()) { "missing/unreadable clip ${trial.clip_file}" }
                    val actualSha = sha256(clip)
                    require(actualSha == trial.clip_sha256) { "SHA mismatch for ${trial.clip_file}" }
                    val index = VideoFrameIndexer.indexFromPath(clip.absolutePath)
                    val toleranceUs = maxOf(20_000L, index.temporalContinuity.medianDeltaUs / 2L)
                    val diagnostics = JSONObject()
                    trial.events_pts_us.forEach { (name, pts) ->
                        val frame = index.indexOfNearest(pts)
                        val indexedPts = index.timeOf(frame)
                        val delta = indexedPts - pts
                        require(abs(delta) <= toleranceUs) { "$name nearest-frame delta ${delta}us exceeds ${toleranceUs}us" }
                        diagnostics.put(name, JSONObject().put("frame_index", frame).put("indexed_pts_us", indexedPts).put("delta_us", delta))
                    }
                    entry.put("video_indexing", JSONObject()
                        .put("status", "INDEXED")
                        .put("frame_count", index.frameCount)
                        .put("duration_us", index.durationUs)
                        .put("nearest_frame_diagnostics", diagnostics))
                    val result = ProtocolCalculator.compute(catalog.draft(trial))
                    val metrics = result.allMetrics.associateBy { it.key }
                    val required = JumpGoldenCatalog.releaseMetricKeys.getValue(result.protocolId)
                    require(metrics.keys == required) { "Unexpected canonical metric set: ${metrics.keys}" }
                    val output = JSONObject()
                    required.forEach { key ->
                        val value = metrics.getValue(key).value
                        require(value.isFinite() && value > 0.0) { "non-positive metric $key" }
                        output.put(key.reportName, value)
                    }
                    entry.put("ballistic_output", output).put("status", "SUCCESS")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failures += "${trial.test_id}/${trial.trial_id}: ${error.message ?: error::class.java.simpleName}"
                    entry.put("error", error.message ?: error.toString())
                }
                results.put(entry)
            }
            report.put("status", if (failures.isEmpty()) "SUCCESS" else "ERROR")
            if (failures.isNotEmpty()) report.put("error", failures.joinToString())
        } catch (error: CancellationException) {
            cancellation = error
            report.put("status", "CANCELLED").put("error", error.message ?: "Benchmark cancelled")
        } catch (error: Exception) {
            failures += error.message ?: error::class.java.simpleName
            report.put("status", "ERROR").put("error", failures.joinToString())
        } finally {
            report.put("total_trials", results.length())
            // This is deliberately the only operation in finally: assertions happen after durable output.
            File(context.filesDir, reportName).writeText(report.toString(2))
        }
        cancellation?.let { throw it }
        assertTrue("Qualification failures: ${failures.joinToString()}", failures.isEmpty())
        assertTrue("All catalog trials must be reported", report.optInt("total_trials") == JumpGoldenCatalog.EXPECTED_TRIALS)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val MetricKey.reportName: String
        get() = when (this) {
            MetricKey.HEIGHT_CM -> "height_cm"
            MetricKey.FLIGHT_TIME_MS -> "flight_time_ms"
            MetricKey.TAKEOFF_VELOCITY_MPS -> "takeoff_velocity_mps"
            MetricKey.TIME_TO_TAKEOFF_MS -> "time_to_takeoff_ms"
            MetricKey.RSI -> "rsi"
            MetricKey.RSI_MOD -> "rsi_mod"
            MetricKey.CONTACT_TIME_MS -> "contact_time_ms"
            else -> error("Metric $this is not part of release report")
        }
}
