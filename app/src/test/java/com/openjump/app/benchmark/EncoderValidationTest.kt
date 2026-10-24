package com.openjump.app.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncoderValidationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun parserRejectsMissingCalibration() {
        val manifest = EncoderValidation.parseManifest(manifestJson(calibration = "null"))
        val result = EncoderValidation.validateManifest(temporaryFolder.root, manifest)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("CALIBRATION_MISSING") })
    }

    @Test
    fun validatorRejectsAbsentAndZeroExpectedRepetitions() {
        listOf(null, 0).forEach { expected ->
            val expectedJson = expected?.toString() ?: "null"
            val manifest = EncoderValidation.parseManifest(manifestJson(expectedRepetitions = expectedJson))
            val result = EncoderValidation.validateManifest(temporaryFolder.root, manifest)
            assertTrue(result.errors.any { it.contains("expectedRepetitions must be positive and required") })
        }
    }

    @Test
    fun validatorRejectsHashMismatchAndMissingFixture() {
        val existing = temporaryFolder.newFile("fixture.mp4").apply { writeText("fixture") }
        val mismatch = EncoderValidation.validateManifest(
            temporaryFolder.root,
            EncoderValidation.parseManifest(manifestJson(path = existing.name, sha256 = "0".repeat(64))),
        )
        assertTrue(mismatch.errors.any { it.contains("HASH_MISMATCH") })

        val missing = EncoderValidation.validateManifest(
            temporaryFolder.root,
            EncoderValidation.parseManifest(manifestJson(path = "missing.mp4", sha256 = "0".repeat(64))),
        )
        assertTrue(missing.errors.any { it.contains("FILE_NOT_FOUND") })
    }

    @Test
    fun manifestRejectsDotAliasAndCanonicalSymlinkAlias() {
        val file = temporaryFolder.newFile("fixture.mp4").apply { writeText("fixture") }
        val base = EncoderValidation.parseManifest(manifestJson(path = file.name, sha256 = EncoderValidation.sha256(file))).fixtures.single()
        val dotAlias = base.copy(testId = "TEST-02", relativePath = "./${file.name}")
        val result = EncoderValidation.validateManifest(temporaryFolder.root, EncoderBenchmarkManifest(1, listOf(base, dotAlias)))
        assertTrue(result.errors.any { it.contains("invalid relativePath") })
        assertTrue(result.errors.any { it.contains("duplicate canonical") })
        try {
            val link = temporaryFolder.root.toPath().resolve("alias.mp4")
            java.nio.file.Files.createSymbolicLink(link, file.toPath())
            val symlinkResult = EncoderValidation.validateManifest(
                temporaryFolder.root,
                EncoderBenchmarkManifest(1, listOf(base, base.copy(testId = "TEST-03", relativePath = "alias.mp4"))),
            )
            assertTrue(symlinkResult.errors.any { it.contains("duplicate canonical") })
        } catch (_: UnsupportedOperationException) {
            // Symlinks are unavailable on some Windows test hosts.
        } catch (_: java.nio.file.FileSystemException) {
            // Symlink privilege is not a validation failure.
        }
    }

    @Test
    fun manifestBasenameMustBeSafe() {
        assertTrue(EncoderValidation.validateManifestName("../benchmark_manifest.json").isNotEmpty())
        assertTrue(EncoderValidation.validateManifestName("nested/manifest.json").isNotEmpty())
        assertTrue(EncoderValidation.validateManifestName("benchmark_manifest.json").isEmpty())
    }

    @Test
    fun validatorRejectsVersionTraversalExerciseAndRepetitionSchemaErrors() {
        val invalid = EncoderValidation.parseManifest(
            manifestJson(
                path = "../outside.mp4",
                version = 99,
                exercise = "NOT_AN_EXERCISE",
                expectedRepetitions = -1,
                independent = true,
                groundTruth = "[{\"rep\":1,\"meanVelocityMps\":1.0},{\"rep\":1,\"peakVelocityMps\":2.0}]",
            ),
        )
        val result = EncoderValidation.validateManifest(temporaryFolder.root, invalid)
        assertTrue(result.errors.any { it.contains("unsupported manifest version") })
        assertTrue(result.errors.any { it.contains("CANONICAL_PATH_ESCAPE") || it.contains("invalid relativePath") })
        assertTrue(result.errors.any { it.contains("invalid exercise") })
        assertTrue(result.errors.any { it.contains("expectedRepetitions must be positive and required") })
        assertTrue(result.errors.any { it.contains("duplicate ground-truth repetition") })
        assertTrue(result.errors.any { it.contains("cardinality mismatch") })
    }

    @Test
    fun manifestLevelErrorsAreCombinedEvenWhenThereAreNoFixtures() {
        val manifest = EncoderValidation.parseManifest("{\"version\":99,\"fixtures\":[]}")
        val result = EncoderValidation.validateManifest(temporaryFolder.root, manifest)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("unsupported manifest version") })
        assertTrue(result.errors.any { it.contains("at least one fixture") })
    }

    @Test
    fun operationalAndBehaviorStatusesRemainSeparate() {
        val decision = EncoderValidation.decideRun(
            requested = true,
            processedVideos = 1,
            operationalStatuses = listOf("PASSED"),
            behaviorStatuses = listOf("FAILED"),
            accuracyStatus = "INCOMPLETE",
        )
        assertEquals("FAILED", decision.status)
        assertEquals("PASSED", decision.operationalStatus)
        assertEquals("FAILED", decision.behaviorStatus)
        assertEquals("INCOMPLETE", decision.accuracyStatus)
        assertEquals("behavior_regression_failed", decision.reason)
    }

    @Test
    fun validatorRejectsMissingRepIdentityAndEmptyNonFiniteGroundTruthRows() {
        val manifest = EncoderValidation.parseManifest(
            manifestJson(
                independent = true,
                expectedRepetitions = 1,
                groundTruth = "[{\"meanVelocityMps\":1.0},{\"rep\":1}]",
            ),
        )
        val result = EncoderValidation.validateManifest(temporaryFolder.root, manifest)
        assertTrue(result.errors.any { it.contains("positive repetition identity") })
        assertTrue(result.errors.any { it.contains("no numeric metric") })
        assertTrue(result.errors.any { it.contains("expectedRepetitions does not match") })
    }

    @Test
    fun accuracyMatchesByRepetitionAndReportsEachMetricSeparatelyWithoutTruncation() {
        val gt = """
            [{"rep":1,"meanVelocityMps":1.0,"peakVelocityMps":2.0,"romM":0.5},
             {"rep":2,"meanVelocityMps":0.8,"peakVelocityMps":1.6,"romM":0.4}]
        """.trimIndent()
        val fixtureOne = EncoderValidation.parseManifest(
            manifestJson(expectedRepetitions = 2, independent = true, groundTruth = gt),
        ).fixtures.single()
        val fixtureTwo = EncoderValidation.parseManifest(
            manifestJson(expectedRepetitions = 2, independent = true, groundTruth = gt).replace("TEST-01", "TEST-02"),
        ).fixtures.single()
        val reports = EncoderValidation.accuracyReports(
            listOf(
                EncoderValidation.AccuracyFixtureObservation(
                    fixtureOne,
                    listOf(
                        ObservedEncoderRepetition("TEST-01", 2, "SQUAT", mapOf("mean_velocity_mps" to 0.7, "peak_velocity_mps" to null, "rom_m" to null)),
                        ObservedEncoderRepetition("TEST-01", 1, "SQUAT", mapOf("mean_velocity_mps" to 1.1, "peak_velocity_mps" to 2.2, "rom_m" to 0.55)),
                    ),
                ),
                EncoderValidation.AccuracyFixtureObservation(
                    fixtureTwo,
                    listOf(
                        ObservedEncoderRepetition("TEST-02", 2, "SQUAT", mapOf("mean_velocity_mps" to 0.9, "peak_velocity_mps" to 1.7, "rom_m" to 0.45)),
                        ObservedEncoderRepetition("TEST-02", 1, "SQUAT", mapOf("mean_velocity_mps" to 1.0, "peak_velocity_mps" to 2.0, "rom_m" to 0.5)),
                    ),
                ),
            ),
        ).associateBy { it.metric }

        assertEquals(setOf("mean_velocity_mps", "peak_velocity_mps", "rom_m"), reports.keys)
        assertEquals("REPORTED", reports.getValue("mean_velocity_mps").status)
        assertEquals(4, reports.getValue("mean_velocity_mps").eligible)
        assertEquals(4, reports.getValue("mean_velocity_mps").compared)
        assertEquals("REPORTED", reports.getValue("mean_velocity_mps").status)
        assertEquals(0.025, reports.getValue("mean_velocity_mps").meanSignedError!!, 1e-9)
        assertEquals(0.075, reports.getValue("mean_velocity_mps").mae!!, 1e-9)
        assertEquals(kotlin.math.sqrt(0.03 / 4.0), reports.getValue("mean_velocity_mps").rmse!!, 1e-9)
        assertEquals("INCOMPLETE", reports.getValue("peak_velocity_mps").status)
        assertEquals(4, reports.getValue("peak_velocity_mps").eligible)
        assertEquals(3, reports.getValue("peak_velocity_mps").compared)
        assertEquals(1, reports.getValue("peak_velocity_mps").missing)
        assertEquals(5.416666666666667, reports.getValue("peak_velocity_mps").meanRelativeErrorPct!!, 1e-9)
        assertEquals("INCOMPLETE", reports.getValue("rom_m").status)
        assertEquals(4, reports.getValue("rom_m").eligible)
        assertEquals(3, reports.getValue("rom_m").compared)
        assertEquals(1, reports.getValue("rom_m").missing)
        assertEquals(0.03333333333333333, reports.getValue("rom_m").mae!!, 1e-9)
        assertEquals("INCOMPLETE", reports.getValue("rom_m").status)
    }

    @Test
    fun duplicateAndExtraObservedIdentitiesCannotReportAccuracy() {
        val fixture = EncoderValidation.parseManifest(
            manifestJson(expectedRepetitions = 2, independent = true, groundTruth = "[{\"rep\":1,\"meanVelocityMps\":1.0},{\"rep\":2,\"meanVelocityMps\":1.0}]"),
        ).fixtures.single()
        val report = EncoderValidation.accuracyReports(
            listOf(
                EncoderValidation.AccuracyFixtureObservation(
                    fixture,
                    listOf(
                        ObservedEncoderRepetition("TEST-01", 1, "SQUAT", mapOf("mean_velocity_mps" to 1.0)),
                        ObservedEncoderRepetition("TEST-01", 1, "SQUAT", mapOf("mean_velocity_mps" to 1.0)),
                        ObservedEncoderRepetition("TEST-01", 3, "SQUAT", mapOf("mean_velocity_mps" to 1.0)),
                    ),
                ),
            ),
        ).single()
        assertEquals("INCOMPLETE", report.status)
        assertTrue(report.identityErrors.any { it.contains("duplicate") })
        assertTrue(report.identityErrors.any { it.contains("extra") })
    }

    @Test
    fun fixtureAndExerciseIdentityMismatchesCannotReportAccuracy() {
        val fixture = EncoderValidation.parseManifest(
            manifestJson(
                expectedRepetitions = 1,
                independent = true,
                groundTruth = "[{\"rep\":1,\"meanVelocityMps\":1.0}]",
            ),
        ).fixtures.single()
        val report = EncoderValidation.accuracyReports(
            listOf(
                EncoderValidation.AccuracyFixtureObservation(
                    fixture,
                    listOf(
                        ObservedEncoderRepetition("OTHER-FIXTURE", 1, "SQUAT", mapOf("mean_velocity_mps" to 1.0)),
                        ObservedEncoderRepetition("TEST-01", 1, "DEADLIFT", mapOf("mean_velocity_mps" to 1.0)),
                    ),
                ),
            ),
        ).single()
        assertEquals("INCOMPLETE", report.status)
        assertEquals(0, report.compared)
        assertTrue(report.identityErrors.any { it.contains("fixtureId") })
        assertTrue(report.identityErrors.any { it.contains("exercise") })
    }

    @Test
    fun mixedNotRunAndPassedBehaviorIsIncomplete() {
        val decision = EncoderValidation.decideRun(
            requested = true,
            processedVideos = 2,
            operationalStatuses = listOf("PASSED", "PASSED"),
            behaviorStatuses = listOf("NOT_RUN", "PASSED"),
        )
        assertEquals("INCOMPLETE", decision.behaviorStatus)
        assertEquals("FAILED", decision.status)
    }

    @Test
    fun atomicReportWriterReplacesWholeDocumentAndFixtureCacheVerifiesAndCleans() {
        val report = temporaryFolder.newFile("report.json").apply { writeText("old") }
        EncoderValidation.atomicWrite(report, "new-complete-report")
        assertEquals("new-complete-report", report.readText())

        val source = temporaryFolder.newFile("source.mp4").apply { writeText("fixture-bytes") }
        val cacheDir = temporaryFolder.newFolder("cache")
        val expected = EncoderValidation.sha256(source)
        val cached = EncoderValidation.copyVerifiedFixture(source, cacheDir, expected, "TEST-01")
        assertEquals(expected, cached.observedSha256)
        assertEquals("fixture-bytes", cached.file.readText())
        EncoderValidation.cleanupVerifiedFixture(cached)
        assertFalse(cached.file.exists())
        try {
            EncoderValidation.copyVerifiedFixture(source, cacheDir, "0".repeat(64), "TEST-02")
            error("hash mismatch should fail")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("HASH_MISMATCH"))
        }
        assertTrue(cacheDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun validationFailureStateIsFullAndCancellationIsTerminal() {
        val metadata = EncoderReportMetadata("HEAD", "config", "device", 35)
        val validation = EncoderValidation.buildValidationFailureReport(metadata, listOf("missing"))
        assertEquals("FAILED", validation.runStatus)
        assertEquals(0, validation.processedVideos)
        assertEquals(0, validation.operational.processedVideos)
        assertEquals(listOf("missing"), validation.validationErrors)
        val cancellation = EncoderValidation.buildCancellationReport(metadata, 2)
        assertEquals("FAILED", cancellation.runStatus)
        assertEquals("INCOMPLETE", cancellation.operational.status)
        assertEquals(2, cancellation.processedVideos)
        assertEquals(2, cancellation.operational.processedVideos)
    }

    @Test
    fun runDecisionAndPersistedReportExposeNotRunAndFailureReasons() {
        val notRun = EncoderValidation.decideRun(false)
        assertEquals("NOT_RUN", notRun.status)
        assertEquals("benchmark_not_requested", notRun.reason)
        assertEquals(0, notRun.processedVideos)

        val failed = EncoderValidation.decideRun(true, processedVideos = 0)
        assertEquals("FAILED", failed.status)
        assertEquals("zero_videos_processed", failed.reason)

        val report = temporaryFolder.newFile("decision.json")
        EncoderValidation.writeNotRunBenchmarkReport(
            report,
            EncoderReportMetadata("HEAD-test", "config-test", "jvm-test", 35),
        )
        val persisted = report.readText()
        assertTrue(persisted.contains("\"run_status\":\"NOT_RUN\""))
        assertTrue(persisted.contains("\"requested\":false"))
        assertTrue(persisted.contains("\"processed_videos\":0"))
        assertNotNull(persisted)
    }

    private fun manifestJson(
        path: String = "fixture.mp4",
        sha256: String = "a".repeat(64),
        calibration: String = calibrationJson(),
        version: Int = 1,
        exercise: String = "SQUAT",
        expectedRepetitions: Any? = 1,
        independent: Boolean = false,
        groundTruth: String = "[]",
    ): String = """
        {
          "version": $version,
          "fixtures": [{
            "testId": "TEST-01",
            "relativePath": "$path",
            "sha256": "$sha256",
            "exercise": "$exercise",
            "loadKg": 100.0,
            "plateTapPx": [10.0, 20.0],
            "trackingSeedPx": [10.0, 20.0],
            "expectedRepetitions": $expectedRepetitions,
            "independentGroundTruth": $independent,
            "groundTruth": $groundTruth,
            "calibration": $calibration,
            "cadenceClassification": "NOMINAL_CFR_NORMALIZED"
          }]
        }
    """.trimIndent()

    private fun calibrationJson(): String = "{\"source\":\"MANUAL_DECLARED\",\"referenceLengthM\":0.4,\"endpointsPx\":[0.0,0.0,100.0,0.0]}"
}
