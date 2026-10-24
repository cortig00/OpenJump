package com.openjump.app.benchmark

import com.openjump.app.encoder.EncoderExercise
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

const val ENCODER_BENCHMARK_MANIFEST_VERSION = 1
const val ENCODER_BENCHMARK_REPORT_FILE_NAME = "encoder_benchmark_results.json"

@Serializable
data class EncoderBenchmarkManifest(
    val version: Int,
    val fixtures: List<EncoderFixtureDeclaration>,
)

@Serializable
data class EncoderFixtureDeclaration(
    val testId: String,
    val relativePath: String,
    val sha256: String,
    val exercise: String,
    val loadKg: Double,
    val plateTapPx: List<Double>,
    val trackingSeedPx: List<Double>,
    val expectedRepetitions: Int? = null,
    val calibration: EncoderCalibrationDeclaration? = null,
    val independentGroundTruth: Boolean = false,
    val groundTruth: List<EncoderGroundTruthDeclaration> = emptyList(),
    val cadenceClassification: String = "UNKNOWN",
)

@Serializable
data class EncoderGroundTruthDeclaration(
    val rep: Int? = null,
    val meanVelocityMps: Double? = null,
    val peakVelocityMps: Double? = null,
    val romM: Double? = null,
)

@Serializable
data class EncoderCalibrationDeclaration(
    val source: String,
    val referenceLengthM: Double,
    val endpointsPx: List<Double>,
)

data class FixtureValidation(
    val fixture: EncoderFixtureDeclaration,
    val errors: List<String>,
)

data class RequestedRunValidation(
    val fixtures: List<FixtureValidation>,
    val manifestErrors: List<String> = emptyList(),
) {
    val errors: List<String> = manifestErrors + fixtures.flatMap { it.errors }
    val isValid: Boolean get() = errors.isEmpty() && fixtures.isNotEmpty()
}

@Serializable
data class EncoderRunDecision(
    val requested: Boolean,
    val status: String,
    val reason: String,
    val processedVideos: Int,
    val operationalStatus: String,
    val behaviorStatus: String,
    val accuracyStatus: String,
) {
    fun toJson(): String = Json.encodeToString(this)
}

data class ObservedEncoderRepetition(
    val fixtureId: String,
    val rep: Int,
    val exercise: String,
    val metrics: Map<String, Double?>,
)

data class AccuracyMetricReport(
    val exercise: String,
    val metric: String,
    val unit: String,
    val eligible: Int,
    val compared: Int,
    val missing: Int,
    val coverage: Double,
    val meanSignedError: Double?,
    val mae: Double?,
    val rmse: Double?,
    val meanRelativeErrorPct: Double?,
    val status: String,
    val identityErrors: List<String> = emptyList(),
    val observationIds: List<String> = emptyList(),
)

@Serializable
data class EncoderReportMetadata(
    val head: String,
    val config: String,
    val device: String,
    val apiLevel: Int,
)

@Serializable
data class EncoderNotRunReport(
    @SerialName("schema_version") val schemaVersion: Int,
    val head: String,
    val config: String,
    val device: String,
    @SerialName("api_level") val apiLevel: Int,
    @SerialName("cadence_claim") val cadenceClaim: String,
    @SerialName("run_status") val runStatus: String,
    @SerialName("run_reason") val runReason: String,
    val requested: Boolean,
    @SerialName("processed_videos") val processedVideos: Int,
    val operational: EncoderClaimStatus,
    @SerialName("behavior_regression") val behaviorRegression: EncoderClaimStatus,
    val accuracy: EncoderAccuracyNotRun,
    @SerialName("fixture_hashes") val fixtureHashes: Map<String, String>,
    val results: List<String>,
)

@Serializable
data class EncoderClaimStatus(
    val status: String,
    @SerialName("processed_videos") val processedVideos: Int = 0,
)

@Serializable
data class EncoderBenchmarkReport(
    @SerialName("schema_version") val schemaVersion: Int,
    val head: String,
    val config: String,
    val device: String,
    @SerialName("api_level") val apiLevel: Int,
    @SerialName("cadence_claim") val cadenceClaim: String,
    @SerialName("run_status") val runStatus: String,
    @SerialName("run_reason") val runReason: String,
    val requested: Boolean,
    @SerialName("processed_videos") val processedVideos: Int,
    val operational: EncoderClaimStatus,
    @SerialName("behavior_regression") val behaviorRegression: EncoderClaimStatus,
    val accuracy: EncoderAccuracyNotRun,
    @SerialName("fixture_hashes") val fixtureHashes: Map<String, String> = emptyMap(),
    val results: List<String> = emptyList(),
    @SerialName("validation_errors") val validationErrors: List<String> = emptyList(),
)

@Serializable
data class EncoderReportState(
    val runStatus: String,
    val runReason: String,
    val requested: Boolean,
    val processedVideos: Int,
    val operationalStatus: String,
    val behaviorStatus: String,
    val accuracyStatus: String,
    val accuracyReason: String,
    val validationErrors: List<String> = emptyList(),
) {
    fun toReport(metadata: EncoderReportMetadata, fixtureHashes: Map<String, String> = emptyMap()): EncoderBenchmarkReport =
        EncoderBenchmarkReport(
            schemaVersion = 2,
            head = metadata.head,
            config = metadata.config,
            device = metadata.device,
            apiLevel = metadata.apiLevel,
            cadenceClaim = "NOMINAL_CFR_NORMALIZED; no VFR coverage",
            runStatus = runStatus,
            runReason = runReason,
            requested = requested,
            processedVideos = processedVideos,
            operational = EncoderClaimStatus(operationalStatus, processedVideos),
            behaviorRegression = EncoderClaimStatus(behaviorStatus, processedVideos),
            accuracy = EncoderAccuracyNotRun(accuracyStatus, accuracyReason),
            fixtureHashes = fixtureHashes,
            validationErrors = validationErrors,
        )
}

@Serializable
data class EncoderAccuracyNotRun(val status: String, val reason: String)

object EncoderValidation {
    private val json = Json { ignoreUnknownKeys = false }
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")
    private val allowedVideo = Regex(".*\\.(mp4|mov|m4v|webm|mkv)$", RegexOption.IGNORE_CASE)
    private val safeTestId = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    private val safeManifestName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.json")
    private val allowedCadence = setOf("NOMINAL_CFR_NORMALIZED", "SOURCE_CFR", "SOURCE_VFR", "UNKNOWN")

    fun parseManifest(text: String): EncoderBenchmarkManifest = try {
        json.decodeFromString<EncoderBenchmarkManifest>(text)
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid Encoder benchmark manifest: ${error.message}", error)
    }

    fun validateManifestName(name: String): List<String> = buildList {
        if (!safeManifestName.matches(name) || name.contains('/') || name.contains('\\')) {
            add("invalid manifest basename '$name'")
        }
    }

    fun containedCanonical(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, relativePath).canonicalFile
        val rootPath = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        require(candidate.path.startsWith(rootPath)) {
            "path escapes benchmark root: $relativePath"
        }
        return candidate
    }

    fun validateRootAndManifest(root: File, manifestName: String): List<String> = buildList {
        if (!root.isDirectory) add("benchmark root is not a directory: ${root.path}")
        addAll(validateManifestName(manifestName))
        if (isEmpty()) {
            try {
                containedCanonical(root, manifestName)
            } catch (error: Exception) {
                add("manifest path escapes benchmark root: ${error.message}")
            }
        }
    }

    fun validateManifest(root: File, manifest: EncoderBenchmarkManifest): RequestedRunValidation {
        val seenIds = mutableSetOf<String>()
        val seenPaths = mutableSetOf<String>()
        val seenCanonicalPaths = mutableSetOf<String>()
        val manifestErrors = mutableListOf<String>()
        if (manifest.version != ENCODER_BENCHMARK_MANIFEST_VERSION) {
            manifestErrors += "unsupported manifest version ${manifest.version}; expected $ENCODER_BENCHMARK_MANIFEST_VERSION"
        }
        if (manifest.fixtures.isEmpty()) manifestErrors += "manifest must declare at least one fixture"
        val rootErrors = if (root.isDirectory) emptyList() else listOf("benchmark root is not a directory: ${root.path}")
        return RequestedRunValidation(manifest.fixtures.map { fixture ->
            val errors = rootErrors.toMutableList()
            if (!safeTestId.matches(fixture.testId) || !seenIds.add(fixture.testId)) {
                errors += "${fixture.testId}: duplicate or unsafe testId"
            }
            if (fixture.relativePath.isBlank() || File(fixture.relativePath).isAbsolute() ||
                fixture.relativePath.contains(':') || fixture.relativePath.split('/', '\\').any { it.isEmpty() || it == "." || it == ".." }) {
                errors += "${fixture.testId}: invalid relativePath '${fixture.relativePath}'"
            }
            if (!seenPaths.add(fixture.relativePath)) {
                errors += "${fixture.testId}: duplicate relativePath '${fixture.relativePath}'"
            }
            if (!allowedVideo.matches(fixture.relativePath)) {
                errors += "${fixture.testId}: fixture is not a supported video path"
            }
            if (!sha256Pattern.matches(fixture.sha256)) {
                errors += "${fixture.testId}: invalid SHA-256 declaration"
            }
            if (fixture.exercise !in EncoderExercise.values().map { it.name }) {
                errors += "${fixture.testId}: invalid exercise '${fixture.exercise}'"
            }
            if (!fixture.loadKg.isFinite() || fixture.loadKg < 0.0) {
                errors += "${fixture.testId}: invalid loadKg"
            }
            if (!validPoint(fixture.plateTapPx) || !validPoint(fixture.trackingSeedPx)) {
                errors += "${fixture.testId}: tap and tracking seed must be finite [x,y] points"
            }
            if (fixture.expectedRepetitions == null || fixture.expectedRepetitions <= 0) {
                errors += "${fixture.testId}: expectedRepetitions must be positive and required"
            }
            if (fixture.cadenceClassification !in allowedCadence) {
                errors += "${fixture.testId}: invalid cadenceClassification"
            }
            validateGroundTruth(fixture, errors)
            val calibration = fixture.calibration
            if (calibration == null) {
                errors += "${fixture.testId}: CALIBRATION_MISSING"
            } else {
                if (calibration.source.isBlank()) errors += "${fixture.testId}: calibration source is blank"
                if (!calibration.referenceLengthM.isFinite() || calibration.referenceLengthM <= 0.0) {
                    errors += "${fixture.testId}: calibration referenceLengthM is invalid"
                }
                if (!validSegment(calibration.endpointsPx)) {
                    errors += "${fixture.testId}: calibration endpoints must be four finite values with non-zero length"
                }
            }
            val file = try {
                containedCanonical(root, fixture.relativePath)
            } catch (error: Exception) {
                errors += "${fixture.testId}: CANONICAL_PATH_ESCAPE ${error.message}"
                null
            }
            if (file != null) {
                val canonicalIdentity = file.path.lowercase(Locale.ROOT)
                if (!seenCanonicalPaths.add(canonicalIdentity)) {
                    errors += "${fixture.testId}: duplicate canonical fixture path '${file.path}'"
                }
            }
            if (file == null || !file.isFile) {
                errors += "${fixture.testId}: FILE_NOT_FOUND ${file?.path ?: fixture.relativePath}"
            } else if (sha256(file) != fixture.sha256.lowercase()) {
                errors += "${fixture.testId}: HASH_MISMATCH ${file.path}"
            }
            FixtureValidation(fixture, errors.distinct())
        }, manifestErrors.distinct())
    }

    fun decideRun(
        requested: Boolean,
        validationErrors: List<String> = emptyList(),
        processedVideos: Int = 0,
        operationalStatuses: List<String> = emptyList(),
        behaviorStatuses: List<String> = emptyList(),
        accuracyStatus: String = "NOT_RUN",
    ): EncoderRunDecision {
        if (!requested) return EncoderRunDecision(false, "NOT_RUN", "benchmark_not_requested", 0, "NOT_RUN", "NOT_RUN", "NOT_RUN")
        if (validationErrors.isNotEmpty()) {
            return EncoderRunDecision(true, "FAILED", "fixture_validation_failed", 0, "FAILED", "NOT_RUN", "NOT_RUN")
        }
        if (processedVideos == 0) return EncoderRunDecision(true, "FAILED", "zero_videos_processed", 0, "FAILED", aggregateClaimStatus(behaviorStatuses), accuracyStatus)
        val operational = aggregateClaimStatus(operationalStatuses)
        val behavior = aggregateClaimStatus(behaviorStatuses)
        val allRequiredPassed = operationalStatuses.isNotEmpty() && operational == "PASSED" && behavior == "PASSED"
        val overall = if (allRequiredPassed && accuracyStatus != "INCOMPLETE") "PASSED" else "FAILED"
        val reason = when {
            operational == "FAILED" -> "fixture_operational_failure"
            operational == "INCOMPLETE" -> "fixture_operational_incomplete"
            operational == "NOT_RUN" -> "fixture_operational_not_run"
            behavior == "FAILED" -> "behavior_regression_failed"
            behavior == "INCOMPLETE" -> "behavior_regression_incomplete"
            behavior == "NOT_RUN" -> "behavior_regression_not_run"
            accuracyStatus == "INCOMPLETE" -> "accuracy_incomplete"
            else -> "all_requested_claims_complete"
        }
        return EncoderRunDecision(true, overall, reason, processedVideos, operational, behavior, accuracyStatus)
    }

    fun requireSuccessfulRequestedRun(requested: Boolean, processedVideos: Int, statuses: List<String>) {
        val decision = decideRun(requested, processedVideos = processedVideos, operationalStatuses = statuses.map { if (it == "SUCCESS") "PASSED" else "FAILED" }, behaviorStatuses = statuses.map { "PASSED" })
        require(decision.status == "PASSED") { decision.reason }
    }

    data class AccuracyFixtureObservation(
        val fixture: EncoderFixtureDeclaration,
        val observed: List<ObservedEncoderRepetition>,
    )

    fun accuracyReports(inputs: List<AccuracyFixtureObservation>): List<AccuracyMetricReport> {
        data class Accumulator(
            val exercise: String,
            val metric: String,
            val unit: String,
            var eligible: Int = 0,
            var compared: Int = 0,
            var signed: Double = 0.0,
            var absolute: Double = 0.0,
            var squared: Double = 0.0,
            val relative: MutableList<Double> = mutableListOf(),
            val identityErrors: MutableList<String> = mutableListOf(),
            val observationIds: MutableList<String> = mutableListOf(),
        )
        val accumulators = linkedMapOf<String, Accumulator>()
        val metricDefinitions = listOf(
            Triple("mean_velocity_mps", "m/s", EncoderGroundTruthDeclaration::meanVelocityMps),
            Triple("peak_velocity_mps", "m/s", EncoderGroundTruthDeclaration::peakVelocityMps),
            Triple("rom_m", "m", EncoderGroundTruthDeclaration::romM),
        )
        inputs.filter { it.fixture.independentGroundTruth }.forEach { input ->
            val fixture = input.fixture
            val identityErrors = buildList {
                input.observed.forEach { row ->
                    if (row.fixtureId != fixture.testId) {
                        add("${fixture.testId}: observed rep ${row.rep} has fixtureId '${row.fixtureId}'")
                    }
                    if (row.exercise != fixture.exercise) {
                        add("${fixture.testId}: observed rep ${row.rep} has exercise '${row.exercise}'")
                    }
                }
            }.toMutableList()
            // Rows belonging to another fixture/exercise must never be compared to this fixture's GT.
            val ownedObserved = input.observed.filter {
                it.fixtureId == fixture.testId && it.exercise == fixture.exercise
            }
            val observedByRep = ownedObserved.groupBy { it.rep }
            val expectedIds = fixture.groundTruth.mapNotNull { it.rep }.toSet()
            val duplicateIds = observedByRep.filterValues { it.size > 1 }.keys
            val extraIds = observedByRep.keys - expectedIds
            val missingIds = expectedIds - observedByRep.keys
            if (duplicateIds.isNotEmpty()) identityErrors += "${fixture.testId}: duplicate observed reps ${duplicateIds.sorted()}"
            if (extraIds.isNotEmpty()) identityErrors += "${fixture.testId}: extra observed reps ${extraIds.sorted()}"
            if (missingIds.isNotEmpty()) identityErrors += "${fixture.testId}: missing observed reps ${missingIds.sorted()}"
            metricDefinitions.forEach { (metric, unit, selector) ->
                val rows = fixture.groundTruth.filter { selector(it) != null }
                if (rows.isEmpty()) return@forEach
                val key = "${fixture.exercise}|$metric|$unit"
                val accumulator = accumulators.getOrPut(key) { Accumulator(fixture.exercise, metric, unit) }
                accumulator.eligible += rows.size
                accumulator.identityErrors += identityErrors
                rows.forEach { row ->
                    val expected = selector(row)!!
                    val actual = row.rep?.let { rep ->
                        observedByRep[rep]?.singleOrNull()?.metrics?.get(metric)
                    }
                    if (actual != null && actual.isFinite()) {
                        val error = actual - expected
                        accumulator.compared++
                        accumulator.signed += error
                        accumulator.absolute += kotlin.math.abs(error)
                        accumulator.squared += error * error
                        accumulator.relative += (error / expected * 100.0)
                        accumulator.observationIds += "${input.fixture.testId}#${row.rep}"
                    }
                }
            }
        }
        return accumulators.values.map { accumulator ->
            val complete = accumulator.identityErrors.isEmpty() && accumulator.compared == accumulator.eligible
            AccuracyMetricReport(
                exercise = accumulator.exercise,
                metric = accumulator.metric,
                unit = accumulator.unit,
                eligible = accumulator.eligible,
                compared = accumulator.compared,
                missing = accumulator.eligible - accumulator.compared,
                coverage = if (accumulator.eligible == 0) 0.0 else accumulator.compared.toDouble() / accumulator.eligible,
                meanSignedError = accumulator.compared.takeIf { it > 0 }?.let { accumulator.signed / it },
                mae = accumulator.compared.takeIf { it > 0 }?.let { accumulator.absolute / it },
                rmse = accumulator.compared.takeIf { it > 0 }?.let { kotlin.math.sqrt(accumulator.squared / it) },
                meanRelativeErrorPct = accumulator.relative.takeIf { it.isNotEmpty() }?.average(),
                status = if (complete) "REPORTED" else "INCOMPLETE",
                identityErrors = accumulator.identityErrors.distinct(),
                observationIds = accumulator.observationIds.distinct(),
            )
        }
    }

    fun writeRunDecisionReport(file: File, decision: EncoderRunDecision) {
        atomicWrite(file, decision.toJson())
    }

    /** Durable report write: temp file is beside the destination, then replaced atomically. */
    fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile ?: File("."), ".${file.name}.${System.nanoTime()}.tmp")
        try {
            temporary.outputStream().buffered().use { it.write(text.toByteArray(Charsets.UTF_8)); it.flush() }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun buildRunningReport(metadata: EncoderReportMetadata): EncoderReportState = EncoderReportState(
        runStatus = "RUNNING", runReason = "benchmark_requested", requested = true,
        processedVideos = 0, operationalStatus = "PENDING", behaviorStatus = "NOT_RUN",
        accuracyStatus = "NOT_RUN", accuracyReason = "No independent numerical ground truth declared",
    )

    fun buildValidationFailureReport(metadata: EncoderReportMetadata, errors: List<String>): EncoderBenchmarkReport =
        EncoderReportState("FAILED", "fixture_validation_failed", true, 0, "FAILED", "NOT_RUN", "NOT_RUN", "fixture_validation_failed", errors.distinct())
            .toReport(metadata)

    fun buildCancellationReport(metadata: EncoderReportMetadata, processedVideos: Int, reason: String = "benchmark_cancelled"): EncoderBenchmarkReport =
        EncoderReportState("FAILED", reason, true, processedVideos, "INCOMPLETE", "INCOMPLETE", "INCOMPLETE", reason)
            .toReport(metadata)

    fun writeReport(file: File, report: EncoderBenchmarkReport) = atomicWrite(file, json.encodeToString(report))

    data class VerifiedFixtureCache(val file: File, val observedSha256: String)

    /** Stream a fixture into a unique sibling cache, verify it, then promote it atomically. */
    fun copyVerifiedFixture(source: File, cacheDirectory: File, expectedSha256: String, identity: String): VerifiedFixtureCache {
        require(source.isFile) { "fixture source is not a file: ${source.path}" }
        cacheDirectory.mkdirs()
        val suffix = source.extension.ifBlank { "mp4" }
        val finalFile = File(cacheDirectory, "fixture-${identity.replace(Regex("[^A-Za-z0-9_.-]"), "_")}-${System.nanoTime()}.$suffix")
        val temporary = File(cacheDirectory, ".${finalFile.name}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            source.inputStream().buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }
            val observed = digest.digest().joinToString("") { "%02x".format(it) }
            require(observed == expectedSha256.lowercase()) { "HASH_MISMATCH ${source.path}: observed $observed" }
            try {
                Files.move(temporary.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(temporary.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return VerifiedFixtureCache(finalFile, observed)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun cleanupVerifiedFixture(cache: VerifiedFixtureCache?) {
        cache?.file?.delete()
    }

    private fun aggregateClaimStatus(statuses: List<String>): String = when {
        statuses.any { it == "FAILED" } -> "FAILED"
        statuses.isNotEmpty() && statuses.all { it == "PASSED" } -> "PASSED"
        statuses.isNotEmpty() && statuses.all { it == "NOT_RUN" } -> "NOT_RUN"
        statuses.any { it == "INCOMPLETE" } || statuses.any { it == "PENDING" } -> "INCOMPLETE"
        else -> "INCOMPLETE"
    }

    /** Canonical persistence location used by both the benchmark and its contract test. */
    fun encoderBenchmarkReportFile(outputDir: File): File = File(outputDir, ENCODER_BENCHMARK_REPORT_FILE_NAME)

    fun writeNotRunBenchmarkReport(file: File, metadata: EncoderReportMetadata) {
        val decision = decideRun(false)
        val report = EncoderNotRunReport(
            schemaVersion = 2,
            head = metadata.head,
            config = metadata.config,
            device = metadata.device,
            apiLevel = metadata.apiLevel,
            cadenceClaim = "NOMINAL_CFR_NORMALIZED; no VFR coverage",
            runStatus = decision.status,
            runReason = decision.reason,
            requested = decision.requested,
            processedVideos = decision.processedVideos,
            operational = EncoderClaimStatus("NOT_RUN"),
            behaviorRegression = EncoderClaimStatus("NOT_RUN"),
            accuracy = EncoderAccuracyNotRun("NOT_RUN", "No independent numerical ground truth declared"),
            fixtureHashes = emptyMap(),
            results = emptyList(),
        )
        // This writer is shared verbatim by the Android benchmark and its instrumentation contract.
        atomicWrite(file, json.encodeToString(report))
    }

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateGroundTruth(fixture: EncoderFixtureDeclaration, errors: MutableList<String>) {
        if (fixture.independentGroundTruth && fixture.groundTruth.isEmpty()) {
            errors += "${fixture.testId}: independent ground truth has zero records"
        }
        val reps = fixture.groundTruth.map { it.rep }
        if (fixture.groundTruth.any { it.rep == null || it.rep <= 0 }) {
            errors += "${fixture.testId}: every ground-truth row needs a positive repetition identity"
        }
        if (reps.filterNotNull().toSet().size != reps.filterNotNull().size) {
            errors += "${fixture.testId}: duplicate ground-truth repetition identity"
        }
        if (fixture.independentGroundTruth) {
            val expected = fixture.expectedRepetitions
            if (expected == null || reps.filterNotNull().toSet() != (1..expected).toSet()) {
                errors += "${fixture.testId}: ground-truth repetition cardinality mismatch"
            }
        }
        fixture.groundTruth.forEachIndexed { index, groundTruth ->
            val values = listOf(
                "meanVelocityMps" to groundTruth.meanVelocityMps,
                "peakVelocityMps" to groundTruth.peakVelocityMps,
                "romM" to groundTruth.romM,
            )
            if (values.all { it.second == null }) errors += "${fixture.testId}: ground truth row $index has no numeric metric"
            values.forEach { (name, value) ->
                if (value != null && (!value.isFinite() || value <= 0.0)) {
                    errors += "${fixture.testId}: ground truth $name[$index] is invalid"
                }
            }
        }
        if (fixture.independentGroundTruth && fixture.expectedRepetitions != fixture.groundTruth.size) {
            errors += "${fixture.testId}: expectedRepetitions does not match ground-truth row count"
        }
    }

    private fun validPoint(value: List<Double>): Boolean = value.size == 2 && value.all { it.isFinite() }

    private fun validSegment(value: List<Double>): Boolean =
        value.size == 4 && value.all { it.isFinite() } &&
            (value[0] != value[2] || value[1] != value[3])
}
