package com.openjump.app.benchmark

import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderAnalysisConfig
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderMetric
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.encoder.EncoderMetricUnit
import com.openjump.app.encoder.EncoderRepetition
import com.openjump.app.encoder.EncoderSample
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.MetricPoint
import com.openjump.app.encoder.MetricValidity
import com.openjump.app.encoder.MovementDirection
import com.openjump.app.encoder.MovementPhase
import com.openjump.app.encoder.ObservationKind
import com.openjump.app.encoder.QualityReason
import com.openjump.app.encoder.RepetitionQuality
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.ImageRegion
import com.openjump.app.tracking.TrackingDiagnostics
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.TrackingResult
import com.openjump.app.tracking.TrackingSample
import com.openjump.app.tracking.TrackingStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/**
 * Negative controls for [EncoderPerformanceSupport]. The fixtures are real domain objects (no
 * Android mocks, no filesystem outside the JUnit temporary folder) with deliberately irregular PTS
 * so ordering/PTS regressions cannot hide.
 */
class EncoderPerformanceSupportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun identicalInputsProduceIdenticalSnapshotsAndKeyOrderIsIrrelevant() {
        val first = EncoderPerformanceSupport.outputSnapshot(trackingFixture(), analysisFixture())
        val second = EncoderPerformanceSupport.outputSnapshot(trackingFixture(), analysisFixture())

        assertEquals(first, second)
        assertNull(EncoderPerformanceSupport.firstDifference(first, second))

        val reversedMembers = JsonObject(first.keys.reversed().associateWith { first.getValue(it) })
        val analysis = first.getValue("analysis").jsonObject
        val reversedAnalysis = JsonObject(analysis.keys.reversed().associateWith { analysis.getValue(it) })
        val reordered = replaceMember(reversedMembers, "analysis", reversedAnalysis)

        assertEquals(setOf("schema", "schema_version", "tracking", "analysis"), first.keys)
        assertNull(EncoderPerformanceSupport.firstDifference(first, reordered))
    }

    @Test
    fun snapshotContainsEveryDeclaredFieldOfEveryDomainType() {
        val snapshot = EncoderPerformanceSupport.outputSnapshot(trackingFixture(), analysisFixture())
        val tracking = snapshot.getValue("tracking").jsonArray
        val frame = tracking.first().jsonObject
        val result = frame.getValue("result").jsonObject
        val sample = result.getValue("sample").jsonObject
        val analysis = snapshot.getValue("analysis").jsonObject
        val encoderSample = analysis.getValue("samples").jsonArray.first().jsonObject
        val repetition = analysis.getValue("repetitions").jsonArray.first().jsonObject

        assertEquals(4, tracking.size)
        assertEquals(4, analysis.getValue("samples").jsonArray.size)
        assertEquals(3, analysis.getValue("repetitions").jsonArray.size)
        assertEquals(3, result.getValue("feature_points").jsonArray.size)

        assertFieldsCovered(TrackingFrameResult::class.java, frame)
        assertFieldsCovered(TrackingResult::class.java, result)
        assertFieldsCovered(TrackingSample::class.java, sample)
        assertFieldsCovered(ImagePoint::class.java, sample.getValue("point").jsonObject)
        assertFieldsCovered(ImageRegion::class.java, result.getValue("region").jsonObject)
        assertFieldsCovered(TrackingDiagnostics::class.java, result.getValue("diagnostics").jsonObject)
        assertFieldsCovered(EncoderAnalysis::class.java, analysis)
        assertFieldsCovered(EncoderSetup::class.java, analysis.getValue("setup").jsonObject)
        assertFieldsCovered(VideoTimingDecision::class.java, analysis.getValue("timing_decision").jsonObject)
        assertFieldsCovered(MetricCalibration::class.java, analysis.getValue("calibration").jsonObject)
        assertFieldsCovered(EncoderSample::class.java, encoderSample)
        assertFieldsCovered(MetricPoint::class.java, encoderSample.getValue("raw_metric_point").jsonObject)
        assertFieldsCovered(EncoderRepetition::class.java, repetition)
        assertFieldsCovered(MovementPhase::class.java, repetition.getValue("eccentric_phase").jsonObject)
        assertFieldsCovered(EncoderMetric::class.java, repetition.getValue("metrics").jsonArray.first().jsonObject)
        assertFieldsCovered(EncoderAnalysisConfig::class.java, analysis.getValue("config").jsonObject)
    }

    @Test
    fun derivedAnalysisSummaryTracksValidRepetitions() {
        val base = encodedFixture()
        val derived = EncoderPerformanceSupport.outputSnapshot(base.tracking, base.analysis)
            .getValue("analysis").jsonObject.getValue("derived").jsonObject

        assertEquals(listOf(1, 3), derived.getValue("valid_repetition_ordinals").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(bitsHex(0.42), derived.getValue("best_mcv").jsonPrimitive.content)
        assertTrue("rep 3 has no velocity loss, so the set value must stay explicit null", derived.getValue("set_velocity_loss") is JsonNull)

        val promoted = base.withRepetition(1) { it.copy(quality = RepetitionQuality.VALID) }
        val promotedDerived = EncoderPerformanceSupport.outputSnapshot(promoted.tracking, promoted.analysis)
            .getValue("analysis").jsonObject.getValue("derived").jsonObject

        assertEquals(listOf(1, 2, 3), promotedDerived.getValue("valid_repetition_ordinals").jsonArray.map { it.jsonPrimitive.int })
        assertTrue(
            "the last valid repetition still has an invalid velocity loss, so the set value stays null",
            promotedDerived.getValue("set_velocity_loss") is JsonNull,
        )
        assertEquals(
            "$.analysis.repetitions[1].quality",
            EncoderPerformanceSupport.firstDifference(
                EncoderPerformanceSupport.outputSnapshot(base.tracking, base.analysis),
                EncoderPerformanceSupport.outputSnapshot(promoted.tracking, promoted.analysis),
            )!!.substringBefore(" ("),
        )

        val numericVelocityLoss = base.withRepetition(2) { repetition ->
            repetition.copy(
                metrics = repetition.metrics.map { metric ->
                    if (metric.key == EncoderMetricKey.VELOCITY_LOSS) {
                        metric.copy(value = 11.0, validity = MetricValidity.LOW_CONFIDENCE, reason = QualityReason.LOW_CONFIDENCE)
                    } else {
                        metric
                    }
                },
            )
        }
        val numericDerived = EncoderPerformanceSupport.outputSnapshot(numericVelocityLoss.tracking, numericVelocityLoss.analysis)
            .getValue("analysis").jsonObject.getValue("derived").jsonObject
        assertEquals(listOf(1, 3), numericDerived.getValue("valid_repetition_ordinals").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(bitsHex(11.0), numericDerived.getValue("set_velocity_loss").jsonPrimitive.content)
        assertEquals(
            "$.analysis.repetitions[2].metrics[2].value",
            EncoderPerformanceSupport.firstDifference(
                EncoderPerformanceSupport.outputSnapshot(base.tracking, base.analysis),
                EncoderPerformanceSupport.outputSnapshot(numericVelocityLoss.tracking, numericVelocityLoss.analysis),
            )!!.substringBefore(" ("),
        )
    }

    @Test
    fun enumsSerializeAsExactNamesAndAnUnknownEnumNameIsDetected() {
        val snapshot = EncoderPerformanceSupport.outputSnapshot(trackingFixture(), analysisFixture())
        val sample = firstTrackingSample(snapshot)
        val analysis = snapshot.getValue("analysis").jsonObject
        val repetition = analysis.getValue("repetitions").jsonArray.first().jsonObject

        assertEquals("TRACKING", sample.getValue("status").jsonPrimitive.content)
        assertNotEquals(TrackingStatus.TRACKING.ordinal.toString(), sample.getValue("status").jsonPrimitive.content)
        assertEquals("MEASURED", analysis.getValue("samples").jsonArray.first().jsonObject.getValue("observation_kind").jsonPrimitive.content)
        assertEquals("BENCH_PRESS", analysis.getValue("setup").jsonObject.getValue("exercise").jsonPrimitive.content)
        assertEquals("REAL_TIME", analysis.getValue("timing_decision").jsonObject.getValue("mode").jsonPrimitive.content)
        assertEquals("VALID", repetition.getValue("quality").jsonPrimitive.content)
        assertEquals("DOWN", repetition.getValue("eccentric_phase").jsonObject.getValue("direction").jsonPrimitive.content)
        assertEquals("MCV", repetition.getValue("metrics").jsonArray.first().jsonObject.getValue("key").jsonPrimitive.content)
        assertEquals("METER_PER_SECOND", repetition.getValue("metrics").jsonArray.first().jsonObject.getValue("unit").jsonPrimitive.content)
        assertEquals("VALID", repetition.getValue("metrics").jsonArray.first().jsonObject.getValue("validity").jsonPrimitive.content)
        assertEquals(
            listOf("UNCERTAIN_TRACKING", "PTS_GAP"),
            analysis.getValue("repetitions").jsonArray[1].jsonObject.getValue("reasons").jsonArray.map { it.jsonPrimitive.content },
        )

        val tampered = replaceMember(sample, "status", JsonPrimitive("TRACKING_UNKNOWN"))
        val tamperedSnapshot = withTrackingSample(snapshot, 0, tampered)

        assertEquals(
            "$.tracking[0].result.sample.status (STRING expected \"TRACKING\" actual \"TRACKING_UNKNOWN\")",
            EncoderPerformanceSupport.firstDifference(snapshot, tamperedSnapshot),
        )
    }

    @Test
    fun qualityReasonSetsAreCanonicalizedByOrdinalWhileOrderedListsKeepTheirOrder() {
        val tracking = trackingFixture()
        val firstOrder = analysisFixture().copy(
            repetitions = analysisFixture().repetitions.map { repetition ->
                if (repetition.ordinal == 2) {
                    repetition.copy(
                        reasons = linkedSetOf(QualityReason.TRACKING_LOST, QualityReason.PTS_GAP, QualityReason.LOW_CONFIDENCE),
                    )
                } else {
                    repetition
                }
            },
        )
        val secondOrder = analysisFixture().copy(
            repetitions = analysisFixture().repetitions.map { repetition ->
                if (repetition.ordinal == 2) {
                    repetition.copy(
                        reasons = linkedSetOf(QualityReason.LOW_CONFIDENCE, QualityReason.TRACKING_LOST, QualityReason.PTS_GAP),
                    )
                } else {
                    repetition
                }
            },
        )

        val first = EncoderPerformanceSupport.outputSnapshot(tracking, firstOrder)
        val second = EncoderPerformanceSupport.outputSnapshot(tracking, secondOrder)

        assertEquals(first, second)
        assertNull(EncoderPerformanceSupport.firstDifference(first, second))
        assertEquals(
            listOf("TRACKING_LOST", "LOW_CONFIDENCE", "PTS_GAP"),
            first.getValue("analysis").jsonObject.getValue("repetitions").jsonArray[1].jsonObject
                .getValue("reasons").jsonArray.map { it.jsonPrimitive.content },
        )

        val reversedFeatures = EncoderPerformanceSupport.outputSnapshot(
            trackingFixture().map { frame -> frame.copy(result = frame.result.copy(featurePoints = frame.result.featurePoints.reversed())) },
            analysisFixture(),
        )
        assertEquals(
            "$.tracking[0].result.feature_points[0].x",
            EncoderPerformanceSupport.firstDifference(first, reversedFeatures)!!.substringBefore(" ("),
        )
    }

    @Test
    fun everyLeafMutationIsDetectedAtItsFirstDivergentPath() {
        val base = encodedFixture()
        val baseSnapshot = EncoderPerformanceSupport.outputSnapshot(base.tracking, base.analysis)

        negativeControls().forEach { control ->
            val mutated = control.mutate(encodedFixture())
            val mutatedSnapshot = EncoderPerformanceSupport.outputSnapshot(mutated.tracking, mutated.analysis)
            val difference = EncoderPerformanceSupport.firstDifference(baseSnapshot, mutatedSnapshot)

            assertNotNull("${control.label}: mutation not detected", difference)
            assertTrue(
                "${control.label}: expected '${control.expectedDifference}' but got '$difference'",
                difference == control.expectedDifference || difference!!.startsWith(control.expectedDifference + " ("),
            )
            assertNotNull(
                "${control.label}: reverse comparison must not report equality",
                EncoderPerformanceSupport.firstDifference(mutatedSnapshot, baseSnapshot),
            )
            assertNotEquals("${control.label}: snapshot equals itself only through mutation", baseSnapshot, mutatedSnapshot)
        }
    }

    @Test
    fun rawBitEncodingKeepsSignedZeroNaNAndExplicitNullsDistinct() {
        val base = encodedFixture()
        val snapshot = EncoderPerformanceSupport.outputSnapshot(base.tracking, base.analysis)
        val thirdSample = snapshot.getValue("analysis").jsonObject.getValue("samples").jsonArray[3].jsonObject

        assertEquals("7ff8000000000000", EncoderPerformanceSupport.doubleBits(Double.NaN).jsonPrimitive.content)
        assertEquals("8000000000000000", EncoderPerformanceSupport.doubleBits(-0.0).jsonPrimitive.content)
        assertEquals("0000000000000000", EncoderPerformanceSupport.doubleBits(0.0).jsonPrimitive.content)
        assertTrue(EncoderPerformanceSupport.doubleBits(null) is JsonNull)
        assertEquals("7ff8000000000000", thirdSample.getValue("fit_residual_m").jsonPrimitive.content)
        assertTrue("NaN must not become JSON null", thirdSample.getValue("fit_residual_m") !is JsonNull)
        assertTrue("physical time of sample 0 must stay explicit", snapshot.getValue("analysis").jsonObject
            .getValue("samples").jsonArray[0].jsonObject.getValue("physical_time_us") is JsonNull)

        val nanTwice = EncoderPerformanceSupport.outputSnapshot(
            base.tracking,
            base.analysis.copy(samples = base.analysis.samples.map { if (it.ordinal == 2) it.copy(fitResidualM = Double.NaN) else it }),
        )
        val otherNan = EncoderPerformanceSupport.outputSnapshot(
            base.tracking,
            base.analysis.copy(samples = base.analysis.samples.map { if (it.ordinal == 2) it.copy(fitResidualM = Double.NaN) else it }),
        )
        assertNull(EncoderPerformanceSupport.firstDifference(nanTwice, otherNan))
        assertEquals(
            "$.analysis.samples[2].fit_residual_m",
            EncoderPerformanceSupport.firstDifference(snapshot, nanTwice)!!.substringBefore(" ("),
        )

        val positiveZero = EncoderPerformanceSupport.outputSnapshot(
            base.tracking,
            base.analysis.copy(samples = base.analysis.samples.map { if (it.ordinal == 1) it.copy(velocityXMps = 0.0) else it }),
        )
        val negativeZero = EncoderPerformanceSupport.outputSnapshot(
            base.tracking,
            base.analysis.copy(samples = base.analysis.samples.map { if (it.ordinal == 1) it.copy(velocityXMps = -0.0) else it }),
        )
        assertEquals(
            "$.analysis.samples[1].velocity_x_mps",
            EncoderPerformanceSupport.firstDifference(positiveZero, negativeZero)!!.substringBefore(" ("),
        )
        assertNotEquals(
            positiveZero.getValue("analysis").jsonObject.getValue("samples").jsonArray[1].jsonObject.getValue("velocity_x_mps"),
            negativeZero.getValue("analysis").jsonObject.getValue("samples").jsonArray[1].jsonObject.getValue("velocity_x_mps"),
        )
    }

    @Test
    fun firstDifferenceReportsMissingExtraTypeSizeAndValueExactly() {
        val snapshot = EncoderPerformanceSupport.outputSnapshot(trackingFixture(), analysisFixture())
        val sample = firstTrackingSample(snapshot)
        val firstFrame = snapshot.getValue("tracking").jsonArray.first().jsonObject

        val withoutConfidence = withoutMember(sample, "confidence")
        assertEquals(
            "$.tracking[0].result.sample.confidence (key missing in actual)",
            EncoderPerformanceSupport.firstDifference(
                snapshot,
                withTrackingSample(snapshot, 0, withoutConfidence),
            ),
        )

        val withExtra = withMember(sample, "extra_field", JsonPrimitive(1))
        assertEquals(
            "$.tracking[0].result.sample.extra_field (key unexpected in actual)",
            EncoderPerformanceSupport.firstDifference(
                snapshot,
                withTrackingSample(snapshot, 0, withExtra),
            ),
        )

        val nullPoint = replaceMember(sample, "point", JsonNull)
        assertEquals(
            "$.tracking[0].result.sample.point (type expected OBJECT actual NULL)",
            EncoderPerformanceSupport.firstDifference(
                snapshot,
                withTrackingSample(snapshot, 0, nullPoint),
            ),
        )

        val nullToZero = replaceMember(sample, "point", JsonPrimitive(0))
        assertEquals(
            "$.tracking[0].result.sample.point (type expected OBJECT actual LITERAL)",
            EncoderPerformanceSupport.firstDifference(
                snapshot,
                withTrackingSample(snapshot, 0, nullToZero),
            ),
        )

        val fewerFrames = replaceMember(snapshot, "tracking", JsonArray(snapshot.getValue("tracking").jsonArray.dropLast(1)))
        assertEquals("$.tracking (size expected 4 actual 3)", EncoderPerformanceSupport.firstDifference(snapshot, fewerFrames))

        val analysis = snapshot.getValue("analysis").jsonObject
        val slowerFps = replaceMember(analysis, "effective_fps", JsonPrimitive(bitsHex(29.5)))
        assertEquals(
            "$.analysis.effective_fps (STRING expected \"${bitsHex(30.0)}\" actual \"${bitsHex(29.5)}\")",
            EncoderPerformanceSupport.firstDifference(snapshot, replaceMember(snapshot, "analysis", slowerFps)),
        )

        val missingTime = replaceMember(
            analysis,
            "samples",
            JsonArray(analysis.getValue("samples").jsonArray.mapIndexed { index, element ->
                if (index == 0) withoutMember(element.jsonObject, "physical_time_us") else element
            }),
        )
        assertEquals(
            "$.analysis.samples[0].physical_time_us (key missing in actual)",
            EncoderPerformanceSupport.firstDifference(snapshot, replaceMember(snapshot, "analysis", missingTime)),
        )
        assertFalse(firstFrame.isEmpty())
    }

    @Test
    fun snapshotCarriesNoTimingProvenanceUriOrNoteMaterial() {
        val snapshot = EncoderPerformanceSupport.outputSnapshot(trackingFixture(), analysisFixture())
        val keys = mutableListOf<String>()
        collectKeys("$", snapshot, keys)

        assertTrue(keys.size > 60)
        // `analysis.timing_decision` is the domain video-timing enum, not a measured timing.
        listOf("elapsed", "provenance", "uri", "note", "device", "api_level", "speedup", "video").forEach { fragment ->
            assertTrue(
                "forbidden snapshot key fragment '$fragment' in $keys",
                keys.none { it.lowercase(Locale.ROOT).contains(fragment) },
            )
        }
    }

    @Test
    fun runReportKeepsAccuracyNotRunAndGatesSpeedupByStatus() {
        val provenance = JsonObject(linkedMapOf("device" to JsonPrimitive("emulator"), "head" to JsonPrimitive("05ebf8a")))
        val measurements = JsonObject(linkedMapOf("speedup" to JsonPrimitive(bitsHex(1.25)), "samples" to JsonPrimitive(3)))

        EncoderPerformanceSupport.REPORT_STATUSES.forEach { status ->
            val report = EncoderPerformanceSupport.buildRunReport("run-01", status, provenance, measurements)
            assertEquals(status, report.getValue("status").jsonPrimitive.content)
            assertEquals("NOT_RUN", report.getValue("accuracy").jsonPrimitive.content)
            assertEquals(EncoderPerformanceSupport.REPORT_SCHEMA_VERSION, report.getValue("schema_version").jsonPrimitive.int)
            assertEquals(provenance, report.getValue("provenance"))
            assertEquals(measurements, report.getValue("measurements"))
            assertTrue(report.getValue("output") is JsonNull)
            assertTrue(report.getValue("error") is JsonNull)
            if (status == EncoderPerformanceSupport.STATUS_PASSED) {
                assertEquals(bitsHex(1.25), report.getValue("speedup").jsonPrimitive.content)
            } else {
                assertTrue("status $status must never carry a speedup", report.getValue("speedup") is JsonNull)
            }
        }

        val unknownFailure = EncoderPerformanceSupport.buildRunReport(
            "run-02",
            EncoderPerformanceSupport.STATUS_FAILED,
            provenance,
            measurements,
            output = null,
            error = "Unknown failure: instrumented run aborted",
        )
        assertEquals("FAILED", unknownFailure.getValue("status").jsonPrimitive.content)
        assertEquals("NOT_RUN", unknownFailure.getValue("accuracy").jsonPrimitive.content)
        assertTrue(unknownFailure.getValue("speedup") is JsonNull)
        assertEquals("Unknown failure: instrumented run aborted", unknownFailure.getValue("error").jsonPrimitive.content)
        assertTrue(EncoderPerformanceSupport.TERMINAL_STATUSES.contains(EncoderPerformanceSupport.STATUS_INCOMPLETE))
    }

    @Test
    fun runReportAndReportFilesRejectUnknownStatusUnsafeIdsAndCollisions() {
        val provenance = JsonObject(emptyMap())
        val measurements = JsonObject(emptyMap())

        assertThrows(IllegalArgumentException::class.java) {
            EncoderPerformanceSupport.buildRunReport("run-01", "SUCCEEDED", provenance, measurements)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncoderPerformanceSupport.buildRunReport("run-01", "passed", provenance, measurements)
        }
        listOf("", "-leading", "with space", "a/b", "..", "run\n", "x".repeat(65)).forEach { unsafe ->
            assertFalse(unsafe, EncoderPerformanceSupport.isSafeRunId(unsafe))
            assertThrows(IllegalArgumentException::class.java) {
                EncoderPerformanceSupport.buildRunReport(unsafe, EncoderPerformanceSupport.STATUS_RUNNING, provenance, measurements)
            }
            assertThrows(IllegalArgumentException::class.java) {
                EncoderPerformanceSupport.distinctReportFile(temporaryFolder.root, unsafe)
            }
        }
        listOf("run-01", "2026-05-01_bench.1").forEach { safe ->
            assertTrue(safe, EncoderPerformanceSupport.isSafeRunId(safe))
            EncoderPerformanceSupport.buildRunReport(safe, EncoderPerformanceSupport.STATUS_RUNNING, provenance, measurements)
        }
    }

    @Test
    fun reportFilesAreUniqueDurableAndNeverClobberTheFrozenReport() {
        val directory = temporaryFolder.root
        val runOne = EncoderPerformanceSupport.distinctReportFile(directory, "run-01")
        assertEquals("encoder_perf_run-01.json", runOne.name)
        assertEquals(directory, runOne.parentFile)

        val provenance = JsonObject(linkedMapOf("device" to JsonPrimitive("emulator")))
        val first = EncoderPerformanceSupport.buildRunReport("run-01", EncoderPerformanceSupport.STATUS_RUNNING, provenance, JsonObject(emptyMap()))
        EncoderPerformanceSupport.writeReport(runOne, first)
        assertTrue(runOne.isFile)
        assertEquals(first, Json.parseToJsonElement(runOne.readText()))
        assertNoTemporaryFiles(directory)

        val updated = EncoderPerformanceSupport.buildRunReport(
            "run-01",
            EncoderPerformanceSupport.STATUS_PASSED,
            provenance,
            JsonObject(linkedMapOf("speedup" to JsonPrimitive(bitsHex(1.25)))),
            output = EncoderPerformanceSupport.outputSnapshot(trackingFixture(), analysisFixture()),
        )
        EncoderPerformanceSupport.writeReport(runOne, updated)
        assertEquals(updated, Json.parseToJsonElement(runOne.readText()))
        assertNoTemporaryFiles(directory)

        val runTwo = EncoderPerformanceSupport.distinctReportFile(directory, "run-02")
        assertNotEquals(runOne, runTwo)
        EncoderPerformanceSupport.writeReport(runTwo, first)
        assertEquals("existing run evidence must not be rewritten", updated, Json.parseToJsonElement(runOne.readText()))
        assertThrows(IllegalArgumentException::class.java) {
            EncoderPerformanceSupport.distinctReportFile(directory, "run-02")
        }

        val nested = EncoderPerformanceSupport.distinctReportFile(File(directory, "nested"), "run-03")
        assertTrue(nested.parentFile?.isDirectory == true)
        EncoderPerformanceSupport.writeReport(nested, first)
        assertEquals(first, Json.parseToJsonElement(nested.readText()))

        val frozen = File(directory, ENCODER_BENCHMARK_REPORT_FILE_NAME)
        assertThrows(IllegalArgumentException::class.java) { EncoderPerformanceSupport.writeReport(frozen, first) }
        assertFalse(frozen.exists())
        assertEquals(
            listOf("encoder_perf_run-01.json", "encoder_perf_run-02.json", "nested").sorted(),
            directory.listFiles()!!.map { it.name }.sorted(),
        )
        assertNoTemporaryFiles(directory)
    }

    @Test
    fun streamedReportAndDigestPreserveAllOutputBytes() {
        val output = EncoderPerformanceSupport.outputSnapshot(trackingFixture(), analysisFixture())
        val compact = Json.encodeToString(JsonObject.serializer(), output)
        val expectedHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(compact.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals(expectedHash, EncoderPerformanceSupport.snapshotSha256(output))
        val large = JsonObject(linkedMapOf("outputs" to JsonArray(List(128) { output })))
        val file = EncoderPerformanceSupport.distinctReportFile(temporaryFolder.root, "stream-large")
        EncoderPerformanceSupport.writeReport(file, large)
        assertEquals(large, Json.parseToJsonElement(file.readText()))
        assertNoTemporaryFiles(temporaryFolder.root)
    }

    @Test
    fun timingSummaryUsesDocumentedNearestRankAndExplicitNotRunForEmptyInput() {
        val empty = EncoderPerformanceSupport.summarizeTimings(emptyList())
        assertEquals("NOT_RUN", empty.getValue("status").jsonPrimitive.content)
        assertEquals(0, empty.getValue("sample_count").jsonPrimitive.int)
        listOf("total_ns", "max_ns", "mean_ns_bits", "median_ns_bits", "p95_ns_bits", "percentile_method").forEach { key ->
            assertTrue("$key must stay explicit for an empty stage", empty.getValue(key) is JsonNull)
        }
        assertEquals("no timing samples", empty.getValue("reason").jsonPrimitive.content)

        val four = EncoderPerformanceSupport.summarizeTimings(listOf(10L, 40L, 20L, 30L))
        assertEquals("REPORTED", four.getValue("status").jsonPrimitive.content)
        assertEquals(4, four.getValue("sample_count").jsonPrimitive.int)
        assertEquals(100L, four.getValue("total_ns").jsonPrimitive.long)
        assertEquals(40L, four.getValue("max_ns").jsonPrimitive.long)
        assertEquals(25.0, decodeDouble(four, "mean_ns_bits"), 0.0)
        assertEquals(20.0, decodeDouble(four, "median_ns_bits"), 0.0)
        assertEquals(40.0, decodeDouble(four, "p95_ns_bits"), 0.0)
        assertTrue(four.getValue("reason") is JsonNull)
        assertTrue(four.getValue("percentile_method").jsonPrimitive.content.startsWith("NEAREST_RANK_CEIL"))

        val hundred = EncoderPerformanceSupport.summarizeTimings((1L..100L).toList())
        assertEquals(5050L, hundred.getValue("total_ns").jsonPrimitive.long)
        assertEquals(100L, hundred.getValue("max_ns").jsonPrimitive.long)
        assertEquals(50.0, decodeDouble(hundred, "median_ns_bits"), 0.0)
        assertEquals(95.0, decodeDouble(hundred, "p95_ns_bits"), 0.0)

        val single = EncoderPerformanceSupport.summarizeTimings(listOf(7L))
        assertEquals(7.0, decodeDouble(single, "median_ns_bits"), 0.0)
        assertEquals(7.0, decodeDouble(single, "p95_ns_bits"), 0.0)
        assertEquals(7L, single.getValue("total_ns").jsonPrimitive.long)

        assertThrows(IllegalArgumentException::class.java) { EncoderPerformanceSupport.summarizeTimings(listOf(5L, -1L)) }
    }

    private fun negativeControls(): List<NegativeControl> = listOf(
        NegativeControl("tracking frame index", "$.tracking[1].frame_index") { fixture ->
            fixture.withTracking(1) { it.copy(frameIndex = 9) }
        },
        NegativeControl("tracking timestamp", "$.tracking[1].result.sample.timestamp_us") { fixture ->
            fixture.withTracking(1) { it.copy(result = it.result.copy(sample = it.result.sample.copy(timestampUs = 33_334L))) }
        },
        NegativeControl("tracking point", "$.tracking[2].result.sample.point.x") { fixture ->
            fixture.withTracking(2) { frame ->
                val point = frame.result.sample.point!!
                frame.copy(
                    result = frame.result.copy(
                        sample = frame.result.sample.copy(point = ImagePoint(point.x + 0.5, point.y)),
                    ),
                )
            }
        },
        NegativeControl("tracking confidence", "$.tracking[0].result.sample.confidence") { fixture ->
            fixture.withTracking(0) { it.copy(result = it.result.copy(sample = it.result.sample.copy(confidence = 0.89))) }
        },
        NegativeControl("tracking status", "$.tracking[0].result.sample.status") { fixture ->
            fixture.withTracking(0) { it.copy(result = it.result.copy(sample = it.result.sample.copy(status = TrackingStatus.UNCERTAIN))) }
        },
        NegativeControl("tracking region", "$.tracking[1].result.region.width") { fixture ->
            fixture.withTracking(1) { frame ->
                frame.copy(result = frame.result.copy(region = frame.result.region!!.copy(width = 41.0)))
            }
        },
        NegativeControl("tracking lost sample", "$.tracking[3].result.sample.point") { fixture ->
            fixture.withTracking(3) { frame ->
                frame.copy(
                    result = frame.result.copy(
                        sample = TrackingSample(PTS[3], null, 0.0, TrackingStatus.LOST),
                        region = null,
                    ),
                )
            }
        },
        NegativeControl("tracking diagnostics", "$.tracking[1].result.diagnostics.median_residual_px") { fixture ->
            fixture.withTracking(1) { frame ->
                frame.copy(result = frame.result.copy(diagnostics = frame.result.diagnostics.copy(medianResidualPx = 9.5)))
            }
        },
        NegativeControl("tracking feature points order", "$.tracking[0].result.feature_points[0].x") { fixture ->
            fixture.withTracking(0) { frame ->
                frame.copy(result = frame.result.copy(featurePoints = frame.result.featurePoints.reversed()))
            }
        },
        NegativeControl("sample ordinal", "$.analysis.samples[1].ordinal") { fixture ->
            fixture.withSample(1) { it.copy(ordinal = 7) }
        },
        NegativeControl("sample source PTS", "$.analysis.samples[1].source_pts_us") { fixture ->
            fixture.withSample(1) { it.copy(sourcePtsUs = 33_334L) }
        },
        NegativeControl("sample physical time null to value", "$.analysis.samples[0].physical_time_us") { fixture ->
            fixture.withSample(0) { it.copy(physicalTimeUs = 0L) }
        },
        NegativeControl("sample gap before", "$.analysis.samples[2].gap_before_us") { fixture ->
            fixture.withSample(2) { it.copy(gapBeforeUs = 30_000L) }
        },
        NegativeControl("sample raw pixel point", "$.analysis.samples[2].raw_pixel_point.x") { fixture ->
            fixture.withSample(2) { sample ->
                val point = sample.rawPixelPoint!!
                sample.copy(rawPixelPoint = ImagePoint(point.x + 1.0, point.y))
            }
        },
        NegativeControl("sample smoothed metric point", "$.analysis.samples[1].smoothed_metric_point.y") { fixture ->
            fixture.withSample(1) { sample ->
                val point = sample.smoothedMetricPoint!!
                sample.copy(smoothedMetricPoint = MetricPoint(point.x, point.y - 0.002))
            }
        },
        NegativeControl("sample velocity", "$.analysis.samples[1].velocity_y_mps") { fixture ->
            fixture.withSample(1) { it.copy(velocityYMps = -0.19) }
        },
        NegativeControl("sample fit residual", "$.analysis.samples[1].fit_residual_m") { fixture ->
            fixture.withSample(1) { it.copy(fitResidualM = 0.002) }
        },
        NegativeControl("sample tracking status", "$.analysis.samples[1].tracking_status") { fixture ->
            fixture.withSample(1) { it.copy(trackingStatus = TrackingStatus.UNCERTAIN) }
        },
        NegativeControl("sample observation kind", "$.analysis.samples[1].observation_kind") { fixture ->
            fixture.withSample(1) { it.copy(observationKind = ObservationKind.MISSING) }
        },
        NegativeControl("sample confidence", "$.analysis.samples[1].confidence") { fixture ->
            fixture.withSample(1) { it.copy(confidence = 0.5) }
        },
        NegativeControl("setup load", "$.analysis.setup.load_kg") { fixture ->
            fixture.copy(analysis = fixture.analysis.copy(setup = EncoderSetup(EncoderExercise.BENCH_PRESS, 81.0)))
        },
        NegativeControl("setup exercise", "$.analysis.setup.exercise") { fixture ->
            fixture.copy(analysis = fixture.analysis.copy(setup = EncoderSetup(EncoderExercise.DEADLIFT, 80.0)))
        },
        NegativeControl("timing decision", "$.analysis.timing_decision.mode") { fixture ->
            fixture.copy(analysis = fixture.analysis.copy(timingDecision = VideoTimingDecision(VideoTimingMode.UNKNOWN)))
        },
        NegativeControl("calibration", "$.analysis.calibration.meters_per_pixel") { fixture ->
            val calibration = fixture.analysis.calibration
            fixture.copy(analysis = fixture.analysis.copy(calibration = calibration.copy(metersPerPixel = 0.0041)))
        },
        NegativeControl("calibration reference", "$.analysis.calibration.reference_length_m") { fixture ->
            fixture.copy(analysis = fixture.analysis.copy(calibration = fixture.analysis.calibration.copy(referenceLengthM = 0.41)))
        },
        NegativeControl("effective fps", "$.analysis.effective_fps") { fixture ->
            fixture.copy(analysis = fixture.analysis.copy(effectiveFps = 29.5))
        },
        NegativeControl("smoothing window", "$.analysis.smoothing_window_samples") { fixture ->
            fixture.copy(analysis = fixture.analysis.copy(smoothingWindowSamples = 7))
        },
        NegativeControl("velocity enter threshold", "$.analysis.velocity_enter_mps") { fixture ->
            fixture.copy(analysis = fixture.analysis.copy(velocityEnterMps = 0.07))
        },
        NegativeControl("velocity exit threshold", "$.analysis.velocity_exit_mps") { fixture ->
            fixture.copy(analysis = fixture.analysis.copy(velocityExitMps = 0.04))
        },
        NegativeControl("config change", "$.analysis.config.minimum_velocity_mps") { fixture ->
            fixture.copy(analysis = fixture.analysis.copy(config = EncoderAnalysisConfig(minimumVelocityMps = 0.05)))
        },
        NegativeControl("config integer change", "$.analysis.config.pause_duration_us") { fixture ->
            fixture.copy(analysis = fixture.analysis.copy(config = fixture.analysis.config.copy(pauseDurationUs = 151_000L)))
        },
        NegativeControl("warnings order", "$.analysis.warnings[0]") { fixture ->
            fixture.copy(analysis = fixture.analysis.copy(warnings = fixture.analysis.warnings.reversed()))
        },
        NegativeControl("warnings size", "$.analysis.warnings (size expected 2 actual 1)") { fixture ->
            fixture.copy(analysis = fixture.analysis.copy(warnings = fixture.analysis.warnings.take(1)))
        },
        NegativeControl("missing repetition", "$.analysis.repetitions (size expected 3 actual 2)") { fixture ->
            fixture.copy(analysis = fixture.analysis.copy(repetitions = fixture.analysis.repetitions.take(2)))
        },
        NegativeControl("repetitions order", "$.analysis.repetitions[0].ordinal") { fixture ->
            fixture.copy(analysis = fixture.analysis.copy(repetitions = fixture.analysis.repetitions.reversed()))
        },
        NegativeControl("repetition phase", "$.analysis.repetitions[0].eccentric_phase.end_sample") { fixture ->
            fixture.withRepetition(0) { it.copy(eccentricPhase = MovementPhase(MovementDirection.DOWN, it.eccentricPhase.startSample, 1)) }
        },
        NegativeControl("repetition quality", "$.analysis.repetitions[1].quality") { fixture ->
            fixture.withRepetition(1) { it.copy(quality = RepetitionQuality.INVALID) }
        },
        NegativeControl("repetition reasons", "$.analysis.repetitions[1].reasons (size expected 2 actual 3)") { fixture ->
            fixture.withRepetition(1) { it.copy(reasons = it.reasons + QualityReason.UNRELIABLE_TIME) }
        },
        NegativeControl("metric value", "$.analysis.repetitions[0].metrics[0].value") { fixture ->
            fixture.withRepetition(0) { it.copy(metrics = it.metrics.map { metric -> if (metric.key == EncoderMetricKey.MCV) metric.copy(value = 0.41) else metric }) }
        },
        NegativeControl("metric validity", "$.analysis.repetitions[0].metrics[1].validity") { fixture ->
            fixture.withRepetition(0) { it.copy(metrics = it.metrics.map { metric -> if (metric.key == EncoderMetricKey.ROM) metric.copy(validity = MetricValidity.LOW_CONFIDENCE) else metric }) }
        },
        NegativeControl("metric reason value to null", "$.analysis.repetitions[2].metrics[2].reason") { fixture ->
            fixture.withRepetition(2) { it.copy(metrics = it.metrics.map { metric -> if (metric.key == EncoderMetricKey.VELOCITY_LOSS) metric.copy(reason = null) else metric }) }
        },
        NegativeControl("metric unit", "$.analysis.repetitions[0].metrics[0].unit") { fixture ->
            fixture.withRepetition(0) { it.copy(metrics = it.metrics.map { metric -> if (metric.key == EncoderMetricKey.MCV) metric.copy(unit = EncoderMetricUnit.METER) else metric }) }
        },
    )

    private class NegativeControl(
        val label: String,
        val expectedDifference: String,
        val mutate: (EncodedFixture) -> EncodedFixture,
    )

    private data class EncodedFixture(
        val tracking: List<TrackingFrameResult>,
        val analysis: EncoderAnalysis,
    )

    private fun encodedFixture(): EncodedFixture = EncodedFixture(trackingFixture(), analysisFixture())

    private fun EncodedFixture.withTracking(index: Int, transform: (TrackingFrameResult) -> TrackingFrameResult): EncodedFixture =
        copy(tracking = tracking.mapIndexed { position, frame -> if (position == index) transform(frame) else frame })

    private fun EncodedFixture.withSample(index: Int, transform: (EncoderSample) -> EncoderSample): EncodedFixture =
        copy(
            analysis = analysis.copy(
                samples = analysis.samples.mapIndexed { position, sample -> if (position == index) transform(sample) else sample },
            ),
        )

    private fun EncodedFixture.withRepetition(index: Int, transform: (EncoderRepetition) -> EncoderRepetition): EncodedFixture =
        copy(
            analysis = analysis.copy(
                repetitions = analysis.repetitions.mapIndexed { position, repetition -> if (position == index) transform(repetition) else repetition },
            ),
        )

    private fun firstTrackingSample(snapshot: JsonObject): JsonObject =
        snapshot.getValue("tracking").jsonArray.first().jsonObject.getValue("result").jsonObject.getValue("sample").jsonObject

    private fun resultOf(snapshot: JsonObject, index: Int): JsonObject =
        snapshot.getValue("tracking").jsonArray[index].jsonObject.getValue("result").jsonObject

    private fun withTrackingSample(snapshot: JsonObject, index: Int, sample: JsonObject): JsonObject {
        val frame = snapshot.getValue("tracking").jsonArray[index].jsonObject
        return withTrackingEntry(
            snapshot,
            index,
            replaceMember(frame, "result", replaceMember(resultOf(snapshot, index), "sample", sample)),
        )
    }

    private fun collectKeys(path: String, element: JsonElement, into: MutableList<String>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) -> collectKeys("$path.$key", value, into) }
            is JsonArray -> element.forEachIndexed { index, value -> collectKeys("$path[$index]", value, into) }
            else -> into.add(path)
        }
    }

    private fun replaceMember(json: JsonObject, key: String, value: JsonElement): JsonObject =
        JsonObject(json.keys.associateWith { existing -> if (existing == key) value else json.getValue(existing) })

    private fun withMember(json: JsonObject, key: String, value: JsonElement): JsonObject =
        JsonObject(linkedMapOf(key to value) + json)

    private fun withoutMember(json: JsonObject, key: String): JsonObject =
        JsonObject(json.keys.filter { it != key }.associateWith { json.getValue(it) })

    private fun withTrackingEntry(snapshot: JsonObject, index: Int, replacement: JsonObject): JsonObject =
        replaceMember(
            snapshot,
            "tracking",
            JsonArray(snapshot.getValue("tracking").jsonArray.mapIndexed { position, element ->
                if (position == index) replacement else element
            }),
        )

    private fun assertNoTemporaryFiles(directory: File) {
        assertTrue(
            "atomic writes must not leave temp siblings behind",
            directory.listFiles()!!.none { it.name.endsWith(".tmp") },
        )
    }

    private fun assertFieldsCovered(type: Class<*>, json: JsonObject) {
        val expected = type.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { jsonKey(it.name) }
            .toSet()
        assertFalse("no declared fields found for ${type.simpleName}", expected.isEmpty())
        val missing = expected - json.keys
        assertTrue("${type.simpleName} fields missing from the snapshot: $missing", missing.isEmpty())
    }

    private fun jsonKey(fieldName: String): String = FIELD_ALIASES[fieldName] ?: fieldName
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .lowercase(Locale.ROOT)

    private fun decodeDouble(json: JsonObject, key: String): Double =
        java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(json.getValue(key).jsonPrimitive.content, 16))

    private fun bitsHex(value: Double): String = String.format(Locale.ROOT, "%016x", java.lang.Double.doubleToRawLongBits(value))

    private fun trackingFixture(): List<TrackingFrameResult> = PTS.indices.map { index ->
        val point = ImagePoint(100.0 + index, 50.0 - index * 0.5)
        TrackingFrameResult(
            frameIndex = index,
            result = TrackingResult(
                sample = TrackingSample(
                    timestampUs = PTS[index],
                    point = point,
                    confidence = 0.90 - index * 0.01,
                    status = TrackingStatus.TRACKING,
                ),
                region = ImageRegion(center = point, width = 40.0, height = 30.0),
                featurePoints = listOf(
                    ImagePoint(point.x - 1.0, point.y),
                    point,
                    ImagePoint(point.x + 1.0, point.y + 0.5),
                ),
                diagnostics = TrackingDiagnostics(
                    attemptedFeatures = 8,
                    validFeatures = 6,
                    inlierFeatures = 5,
                    medianResidualPx = 0.40 + index,
                    spatialCoverage = 0.70,
                ),
            ),
        )
    }

    private fun analysisFixture(): EncoderAnalysis = EncoderAnalysis(
        setup = EncoderSetup(EncoderExercise.BENCH_PRESS, 80.0),
        timingDecision = VideoTimingDecision(VideoTimingMode.REAL_TIME),
        calibration = MetricCalibration(
            frameIndex = 0,
            pointA = ImagePoint(10.0, 10.0),
            pointB = ImagePoint(110.0, 10.0),
            referenceLengthM = 0.40,
            metersPerPixel = 0.004,
        ),
        samples = PTS.indices.map { sampleFixture(it) },
        repetitions = listOf(
            repetitionFixture(1, mcv = 0.42, velocityLoss = 12.5, quality = RepetitionQuality.VALID, reasons = emptySet()),
            repetitionFixture(
                2,
                mcv = 0.38,
                velocityLoss = 9.0,
                quality = RepetitionQuality.UNCERTAIN,
                reasons = linkedSetOf(QualityReason.UNCERTAIN_TRACKING, QualityReason.PTS_GAP),
            ),
            repetitionFixture(
                3,
                mcv = 0.31,
                velocityLoss = null,
                quality = RepetitionQuality.VALID,
                reasons = setOf(QualityReason.LOW_CONFIDENCE),
                lossReason = QualityReason.LOW_CONFIDENCE,
            ),
        ),
        effectiveFps = 30.0,
        smoothingWindowSamples = 5,
        velocityEnterMps = 0.06,
        velocityExitMps = 0.03,
        warnings = listOf("synthetic warning A", "synthetic warning B"),
        config = EncoderAnalysisConfig(),
    )

    private fun sampleFixture(index: Int): EncoderSample {
        val point = ImagePoint(100.0 + index, 50.0 - index * 0.5)
        return EncoderSample(
            ordinal = index,
            frameIndex = index,
            sourcePtsUs = PTS[index],
            physicalTimeUs = if (index == 0) null else PTS[index],
            rawPixelPoint = point,
            rawMetricPoint = MetricPoint(point.x / 100.0, point.y / 10.0),
            smoothedMetricPoint = MetricPoint(point.x / 100.0, point.y / 10.0 + 0.001),
            velocityXMps = 0.10 * index,
            velocityYMps = -0.20 * index,
            confidence = 0.90 - index * 0.01,
            trackingStatus = TrackingStatus.TRACKING,
            observationKind = ObservationKind.MEASURED,
            // EncoderSample declares no finiteness invariant: NaN exercises raw-bit encoding.
            fitResidualM = if (index == 3) Double.NaN else 0.001 * index,
            gapBeforeUs = if (index == 0) null else PTS[index] - PTS[index - 1],
        )
    }

    private fun repetitionFixture(
        ordinal: Int,
        mcv: Double,
        velocityLoss: Double?,
        quality: RepetitionQuality,
        reasons: Set<QualityReason>,
        lossReason: QualityReason? = null,
    ): EncoderRepetition {
        val start = ordinal - 1
        return EncoderRepetition(
            ordinal = ordinal,
            startSample = start,
            endSample = ordinal,
            eccentricPhase = MovementPhase(MovementDirection.DOWN, start, start),
            concentricPhase = MovementPhase(MovementDirection.UP, ordinal, ordinal),
            quality = quality,
            reasons = reasons,
            metrics = listOf(
                EncoderMetric(EncoderMetricKey.MCV, mcv, EncoderMetricUnit.METER_PER_SECOND, MetricValidity.VALID),
                EncoderMetric(EncoderMetricKey.ROM, 0.35 + ordinal * 0.01, EncoderMetricUnit.METER, MetricValidity.VALID),
                EncoderMetric(
                    EncoderMetricKey.VELOCITY_LOSS,
                    velocityLoss,
                    EncoderMetricUnit.PERCENT,
                    if (velocityLoss == null) MetricValidity.INVALID else MetricValidity.LOW_CONFIDENCE,
                    lossReason,
                ),
            ),
        )
    }

    companion object {
        /** Irregular PTS: 0 / 33 333 / 70 000 / 104 167 us. */
        private val PTS = listOf(0L, 33_333L, 70_000L, 104_167L)

        /** Initialisms that camel-to-snake conversion cannot derive from the field name alone. */
        private val FIELD_ALIASES = mapOf(
            "velocityXMps" to "velocity_x_mps",
            "velocityYMps" to "velocity_y_mps",
        )
    }
}
