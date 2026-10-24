package com.openjump.app.benchmark

import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolSetup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class JumpGoldenCatalogDto(
    val schema: String,
    val schema_version: Int,
    val corpus_version: String,
    val total_trials: Int,
    val protocol_breakdown: Map<String, Int>,
    val trials: List<JumpGoldenTrialDto>,
)

@Serializable
data class JumpGoldenTrialDto(
    val test_id: String,
    val trial_id: String,
    val protocol: String,
    val side: String? = null,
    val drop_height_cm: Double? = null,
    val clip_file: String,
    val clip_sha256: String,
    val events_pts_us: Map<String, Long>,
    val ballistic_expected: BallisticExpectedDto,
    val instrument_measurement: InstrumentMeasurementDto,
)

@Serializable
data class BallisticExpectedDto(
    val flight_time_ms: Double,
    val height_cm: Double,
    val takeoff_velocity_mps: Double? = null,
    val time_to_takeoff_ms: Double? = null,
    val rsi: Double? = null,
    val rsi_mod: Double? = null,
    val contact_time_ms: Double? = null,
)

@Serializable
data class InstrumentMeasurementDto(
    val height_cm: Double? = null,
    val contact_time_ms: Double? = null,
    val method: String? = null,
    val device: String? = null,
    val provenance: String? = null,
)

data class JumpGoldenCatalog(val trials: List<JumpGoldenTrialDto>, val corpusVersion: String) {
    fun trialKey(trial: JumpGoldenTrialDto): String = "${trial.test_id}/${trial.trial_id}"

    fun draft(trial: JumpGoldenTrialDto): MeasurementDraft {
        val protocol = ProtocolId.fromStorageKey(trial.protocol)
            ?: error("Unknown protocol ${trial.protocol}")
        val events = trial.events_pts_us.map { (name, pts) ->
            val type = EventType.fromStorageKey(name.removeSuffix("_us").uppercase())
                ?: error("Unknown event $name")
            EventKey(type) to EventMark(EventKey(type), pts, 0)
        }.toMap()
        return MeasurementDraft(
            sessionKey = trialKey(trial),
            protocolId = protocol,
            setup = ProtocolSetup(
                side = trial.side?.let { MeasurementSide.fromStorageKey(it) ?: error("Unknown side $it") },
                dropHeightCm = trial.drop_height_cm,
            ),
            events = events,
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = false; isLenient = false }
        const val SCHEMA = "com.openjump.jump-golden"
        const val SCHEMA_VERSION = 1
        const val CORPUS_VERSION = "1.0.0"
        const val EXPECTED_TRIALS = 22
        private val expectedCounts = mapOf("CMJ" to 9, "SJ" to 5, "UNILATERAL" to 4, "DROP_JUMP" to 4)

        /** Canonical release metrics; UI-only metrics must not enter this report. */
        val releaseMetricKeys: Map<ProtocolId, Set<com.openjump.app.protocol.MetricKey>> = mapOf(
            ProtocolId.CMJ to setOf(com.openjump.app.protocol.MetricKey.HEIGHT_CM, com.openjump.app.protocol.MetricKey.FLIGHT_TIME_MS, com.openjump.app.protocol.MetricKey.TAKEOFF_VELOCITY_MPS, com.openjump.app.protocol.MetricKey.TIME_TO_TAKEOFF_MS, com.openjump.app.protocol.MetricKey.RSI_MOD),
            ProtocolId.UNILATERAL to setOf(com.openjump.app.protocol.MetricKey.HEIGHT_CM, com.openjump.app.protocol.MetricKey.FLIGHT_TIME_MS, com.openjump.app.protocol.MetricKey.TAKEOFF_VELOCITY_MPS, com.openjump.app.protocol.MetricKey.TIME_TO_TAKEOFF_MS, com.openjump.app.protocol.MetricKey.RSI_MOD),
            ProtocolId.SJ to setOf(com.openjump.app.protocol.MetricKey.HEIGHT_CM, com.openjump.app.protocol.MetricKey.FLIGHT_TIME_MS, com.openjump.app.protocol.MetricKey.TAKEOFF_VELOCITY_MPS),
            ProtocolId.DROP_JUMP to setOf(com.openjump.app.protocol.MetricKey.RSI, com.openjump.app.protocol.MetricKey.CONTACT_TIME_MS, com.openjump.app.protocol.MetricKey.HEIGHT_CM, com.openjump.app.protocol.MetricKey.FLIGHT_TIME_MS),
        )

        fun parse(text: String): JumpGoldenCatalog {
            val dto = json.decodeFromString<JumpGoldenCatalogDto>(text)
            require(dto.schema == SCHEMA) { "Unexpected catalog schema" }
            require(dto.schema_version == SCHEMA_VERSION) { "Unexpected catalog schema version" }
            require(dto.corpus_version == CORPUS_VERSION) { "Unexpected corpus version" }
            require(dto.total_trials == EXPECTED_TRIALS && dto.trials.size == EXPECTED_TRIALS) { "Catalog must contain exactly 22 trials" }
            require(dto.protocol_breakdown == expectedCounts) { "Unexpected protocol distribution" }
            val keys = dto.trials.map { "${it.test_id}/${it.trial_id}" }
            require(keys.toSet().size == EXPECTED_TRIALS) { "Duplicate trial IDs" }
            require(dto.trials.groupingBy { it.protocol }.eachCount() == expectedCounts) { "Protocol counts do not match trials" }
            dto.trials.forEach(::validateTrial)
            return JumpGoldenCatalog(dto.trials, dto.corpus_version)
        }

        private fun finite(value: Double?, label: String) {
            require(value == null || value.isFinite()) { "$label must be finite" }
        }

        private fun validateTrial(trial: JumpGoldenTrialDto) {
            require(trial.test_id.isNotBlank() && trial.trial_id.isNotBlank())
            require(trial.clip_file.isNotBlank() && !trial.clip_file.contains(".."))
            require(Regex("[0-9a-f]{64}").matches(trial.clip_sha256)) { "Invalid clip SHA for ${trial.test_id}/${trial.trial_id}" }
            val protocol = ProtocolId.fromStorageKey(trial.protocol) ?: error("Unknown protocol ${trial.protocol}")
            val required = when (protocol) {
                ProtocolId.CMJ, ProtocolId.UNILATERAL -> listOf("movement_start_us", "takeoff_us", "landing_us")
                ProtocolId.SJ -> listOf("takeoff_us", "landing_us")
                ProtocolId.DROP_JUMP -> listOf("initial_contact_us", "takeoff_us", "landing_us")
                else -> error("Unsupported catalog protocol ${trial.protocol}")
            }
            require(trial.events_pts_us.keys == required.toSet()) { "Unexpected event set for ${trial.test_id}/${trial.trial_id}" }
            require(trial.events_pts_us.values.all { it >= 0L }) { "Negative event PTS" }
            val ordered = required.map { trial.events_pts_us.getValue(it) }
            require(ordered.zipWithNext().all { it.first < it.second }) { "Event PTS must increase" }
            if (protocol == ProtocolId.UNILATERAL) {
                require(trial.side != null && MeasurementSide.fromStorageKey(trial.side) != null) { "Unilateral side is required" }
            } else {
                require(trial.side == null) { "Side only applies to Unilateral" }
            }
            if (protocol == ProtocolId.DROP_JUMP) {
                require(trial.drop_height_cm != null && trial.drop_height_cm.isFinite() && trial.drop_height_cm > 0.0) { "Drop height must be finite and positive" }
            } else {
                require(trial.drop_height_cm == null) { "Drop height only applies to Drop Jump" }
            }
            val b = trial.ballistic_expected
            listOf(b.flight_time_ms, b.height_cm, b.takeoff_velocity_mps, b.time_to_takeoff_ms, b.rsi, b.rsi_mod, b.contact_time_ms).forEachIndexed { i, v -> finite(v, "ballistic[$i]") }
            require(b.flight_time_ms > 0.0 && b.height_cm > 0.0)
            val expectedVelocity = 9.80665 * (b.flight_time_ms / 1000.0) / 2.0
            require(kotlin.math.abs(b.flight_time_ms - (trial.events_pts_us.getValue("landing_us") - trial.events_pts_us.getValue("takeoff_us")) / 1000.0) <= 1e-9)
            require(kotlin.math.abs(b.height_cm - 9.80665 * (b.flight_time_ms / 1000.0).let { it * it } / 8.0 * 100.0) <= 1e-9)
            if (protocol == ProtocolId.DROP_JUMP) {
                require(b.takeoff_velocity_mps == null && b.rsi != null && b.contact_time_ms != null && b.time_to_takeoff_ms == null && b.rsi_mod == null)
                require(b.contact_time_ms > 0.0 && b.rsi!! > 0.0)
                require(kotlin.math.abs(b.contact_time_ms - (trial.events_pts_us.getValue("takeoff_us") - trial.events_pts_us.getValue("initial_contact_us")) / 1000.0) <= 1e-9)
                require(kotlin.math.abs(b.rsi - b.height_cm / 100.0 / (b.contact_time_ms / 1000.0)) <= 1e-9)
            } else {
                require(b.takeoff_velocity_mps != null && b.takeoff_velocity_mps > 0.0)
                require(kotlin.math.abs(b.takeoff_velocity_mps - expectedVelocity) <= 1e-9)
                require(b.contact_time_ms == null && b.rsi == null)
                val requiresRsiMod = protocol == ProtocolId.CMJ || protocol == ProtocolId.UNILATERAL
                require((b.time_to_takeoff_ms != null) == requiresRsiMod && (b.rsi_mod != null) == requiresRsiMod)
                if (requiresRsiMod) {
                    require(b.time_to_takeoff_ms!! > 0.0 && b.rsi_mod!! > 0.0)
                    require(kotlin.math.abs(b.time_to_takeoff_ms - (trial.events_pts_us.getValue("takeoff_us") - trial.events_pts_us.getValue("movement_start_us")) / 1000.0) <= 1e-9)
                    require(kotlin.math.abs(b.rsi_mod - b.height_cm / 100.0 / (b.time_to_takeoff_ms / 1000.0)) <= 1e-9)
                }
            }
            val instrument = trial.instrument_measurement
            listOf(instrument.height_cm, instrument.contact_time_ms).forEachIndexed { i, v ->
                finite(v, "instrument[$i]")
                require(v == null || v > 0.0) { "instrument[$i] must be positive" }
            }
            require(instrument.contact_time_ms == null || protocol == ProtocolId.DROP_JUMP) { "Contact measurement only applies to Drop Jump" }
            if (instrument.height_cm != null || instrument.contact_time_ms != null) {
                require(!instrument.method.isNullOrBlank() && !instrument.device.isNullOrBlank() && !instrument.provenance.isNullOrBlank())
            }
        }
    }
}
