package com.openjump.app.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EncoderValidationContractInstrumentedTest {
    @Test
    fun requestedMissingFixtureIsFailure() {
        val manifest = EncoderValidation.parseManifest(manifestJson("missing.mp4"))
        val result = EncoderValidation.validateManifest(File("/does-not-exist"), manifest)
        assertTrue(result.errors.any { it.contains("FILE_NOT_FOUND") })
        assertEquals("FAILED", EncoderValidation.decideRun(true, result.errors).status)
    }

    @Test
    fun requestedHashMismatchAndMissingCalibrationAreFailures() {
        val root = createTempDir(prefix = "encoder-contract-")
        try {
            File(root, "fixture.mp4").writeText("fixture")
            val hashMismatch = EncoderValidation.validateManifest(
                root,
                EncoderValidation.parseManifest(manifestJson("fixture.mp4", "0".repeat(64))),
            )
            assertTrue(hashMismatch.errors.any { it.contains("HASH_MISMATCH") })
            assertEquals("FAILED", EncoderValidation.decideRun(true, hashMismatch.errors).status)

            val noCalibration = EncoderValidation.validateManifest(
                root,
                EncoderValidation.parseManifest(manifestJson("fixture.mp4", EncoderValidation.sha256(File(root, "fixture.mp4")), null)),
            )
            assertTrue(noCalibration.errors.any { it.contains("CALIBRATION_MISSING") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun traversalAndUnsupportedVersionAreRejectedBeforeFixtureIo() {
        val manifest = EncoderValidation.parseManifest(manifestJson("../fixture.mp4", version = 42))
        val errors = EncoderValidation.validateManifest(File("/does-not-exist"), manifest).errors
        assertTrue(errors.any { it.contains("unsupported manifest version") })
        assertTrue(errors.any { it.contains("invalid relativePath") })
    }

    @Test
    fun persistedNotRunDecisionContainsConcreteStatusReasonAndCount() {
        val outputDir = createTempDir(prefix = "encoder-not-run-")
        val file = EncoderValidation.encoderBenchmarkReportFile(outputDir)
        try {
            assertEquals("encoder_benchmark_results.json", file.name)
            assertEquals(outputDir.canonicalPath, file.parentFile!!.canonicalPath)
            EncoderValidation.writeNotRunBenchmarkReport(
                file,
                EncoderReportMetadata("HEAD-test", "config-test", "instrumentation-test", 35),
            )
            val persisted = JSONObject(file.readText())
            assertEquals("NOT_RUN", persisted.getString("run_status"))
            assertEquals("benchmark_not_requested", persisted.getString("run_reason"))
            assertTrue(!persisted.getBoolean("requested"))
            assertEquals(0, persisted.getInt("processed_videos"))
            assertEquals("NOT_RUN", persisted.getJSONObject("operational").getString("status"))
            assertEquals("NOT_RUN", persisted.getJSONObject("behavior_regression").getString("status"))
            assertEquals("NOT_RUN", persisted.getJSONObject("accuracy").getString("status"))
            assertTrue(persisted.getJSONObject("accuracy").getString("reason").isNotBlank())
        } finally {
            outputDir.deleteRecursively()
        }
    }

    private fun manifestJson(path: String, hash: String = "a".repeat(64), calibration: String? = calibrationJson(), version: Int = 1): String = """
        {"version":$version,"fixtures":[{"testId":"TEST-01","relativePath":"$path","sha256":"$hash","exercise":"SQUAT","loadKg":100.0,"plateTapPx":[10.0,20.0],"trackingSeedPx":[10.0,20.0],"expectedRepetitions":1,"calibration":$calibration,"cadenceClassification":"NOMINAL_CFR_NORMALIZED"}]}
    """.trimIndent()

    private fun calibrationJson(): String = "{\"source\":\"MANUAL_DECLARED\",\"referenceLengthM\":0.4,\"endpointsPx\":[0.0,0.0,100.0,0.0]}"
}
