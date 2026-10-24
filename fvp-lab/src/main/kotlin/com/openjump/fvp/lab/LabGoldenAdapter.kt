package com.openjump.fvp.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Trial dorado del catalogo `jump-golden` (lectura JVM-only, sin Android).
 *
 * [trialKey] es estable y es `testId__trialId` (doble guion bajo). PTS en microsegundos.
 * Alturas en centimetros tal como aparecen en el catalogo (balistica por PTS reales e
 * instrumento independiente por display de plataforma/alfombra).
 */
data class GoldenTrial(
    val trialKey: String,
    val testId: String,
    val trialId: String,
    val protocol: String,
    val takeoffUs: Long,
    val landingUs: Long,
    val ballisticHcm: Double,
    val instrumentHcm: Double,
)

/**
 * Adaptador READ-ONLY del catalogo dorado `testdata/jump_video/catalog/golden_set.json`.
 *
 * Valida schema `com.openjump.jump-golden` v1 y que el numero de trials coincida con
 * `total_trials` (22 en el corpus actual). No calcula nada; solo expone los campos que
 * necesita el laboratorio F-V-P. Parseo con `Json.parseToJsonElement` SIN plugin.
 *
 * CERO imports `com.openjump.app` / `androidx` / `room`.
 */
object LabGoldenAdapter {
    const val EXPECTED_SCHEMA = "com.openjump.jump-golden"
    const val EXPECTED_SCHEMA_VERSION = 1
    const val EXPECTED_TOTAL_TRIALS = 22

    private val json = Json { ignoreUnknownKeys = true }

    /** Carga y valida el catalogo desde una ruta de fichero. */
    fun load(path: String): List<GoldenTrial> = parse(File(path).readText(Charsets.UTF_8))

    /** Parsea y valida el contenido JSON del catalogo. */
    fun parse(jsonText: String): List<GoldenTrial> {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val schema = root["schema"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("golden: missing 'schema'")
        if (schema != EXPECTED_SCHEMA) {
            throw IllegalArgumentException("golden: unexpected schema '$schema', expected '$EXPECTED_SCHEMA'")
        }
        val schemaVersion = root["schema_version"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("golden: missing/invalid 'schema_version'")
        if (schemaVersion != EXPECTED_SCHEMA_VERSION.toLong()) {
            throw IllegalArgumentException(
                "golden: unexpected schema_version $schemaVersion, expected $EXPECTED_SCHEMA_VERSION",
            )
        }
        val totalTrials = root["total_trials"]?.jsonPrimitive?.longOrNull
        val trialsEl = root["trials"]?.jsonArray
            ?: throw IllegalArgumentException("golden: missing 'trials' array")
        if (totalTrials != null && totalTrials != trialsEl.size.toLong()) {
            throw IllegalArgumentException(
                "golden: total_trials=$totalTrials != trials.size=${trialsEl.size}",
            )
        }
        if (trialsEl.size != EXPECTED_TOTAL_TRIALS) {
            throw IllegalArgumentException(
                "golden: trials.size=${trialsEl.size} != expected $EXPECTED_TOTAL_TRIALS",
            )
        }
        return trialsEl.map { parseTrial(it.jsonObject) }
    }

    private fun parseTrial(o: kotlinx.serialization.json.JsonObject): GoldenTrial {
        val testId = o["test_id"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("golden trial: missing test_id")
        val trialId = o["trial_id"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("golden trial: missing trial_id")
        val protocol = o["protocol"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("golden trial $testId/$trialId: missing protocol")
        val events = o["events_pts_us"]?.jsonObject
            ?: throw IllegalArgumentException("golden trial $testId/$trialId: missing events_pts_us")
        val takeoffUs = events["takeoff_us"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("golden trial $testId/$trialId: missing takeoff_us")
        val landingUs = events["landing_us"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("golden trial $testId/$trialId: missing landing_us")
        if (landingUs <= takeoffUs) {
            throw IllegalArgumentException(
                "golden trial $testId/$trialId: landing_us ($landingUs) <= takeoff_us ($takeoffUs)",
            )
        }
        val ballistic = o["ballistic_expected"]?.jsonObject
            ?: throw IllegalArgumentException("golden trial $testId/$trialId: missing ballistic_expected")
        val ballisticH = ballistic["height_cm"]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("golden trial $testId/$trialId: missing ballistic height_cm")
        val instrument = o["instrument_measurement"]?.jsonObject
            ?: throw IllegalArgumentException("golden trial $testId/$trialId: missing instrument_measurement")
        val instrumentH = instrument["height_cm"]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("golden trial $testId/$trialId: missing instrument height_cm")
        if (!ballisticH.isFinite() || ballisticH <= 0.0) {
            throw IllegalArgumentException("golden trial $testId/$trialId: invalid ballistic height $ballisticH")
        }
        if (!instrumentH.isFinite() || instrumentH <= 0.0) {
            throw IllegalArgumentException("golden trial $testId/$trialId: invalid instrument height $instrumentH")
        }
        if (testId.isBlank() || trialId.isBlank() || protocol.isBlank()) {
            throw IllegalArgumentException("golden trial: blank test_id/trial_id/protocol")
        }
        return GoldenTrial(
            trialKey = "${testId}__${trialId}",
            testId = testId,
            trialId = trialId,
            protocol = protocol,
            takeoffUs = takeoffUs,
            landingUs = landingUs,
            ballisticHcm = ballisticH,
            instrumentHcm = instrumentH,
        )
    }
}
