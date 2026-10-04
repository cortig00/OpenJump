package com.openjump.app.protocol

/** Stable names persisted in Room and passed through navigation. Never persist ordinal values. */
enum class ProtocolId(val storageKey: String) {
    CMJ("CMJ"),
    SJ("SJ"),
    ABALAKOV("ABALAKOV"),
    UNILATERAL("UNILATERAL"),
    DROP_JUMP("DROP_JUMP"),
    REPEATED_10_5("REPEATED_10_5"),
    ASYMMETRY("ASYMMETRY"),
    HORIZONTAL("HORIZONTAL");

    companion object {
        fun fromStorageKey(value: String?): ProtocolId? = entries.firstOrNull { it.storageKey == value }
    }
}

enum class ProtocolCategory { VERTICAL_POWER, REACTIVITY, COMPARISON_DISTANCE }

enum class ProtocolAvailability { AVAILABLE, COMING_SOON }

enum class SetupField { SIDE, DROP_HEIGHT_CM, DISTANCE_CM }

enum class MeasurementSide(val storageKey: String) {
    LEFT("LEFT"),
    RIGHT("RIGHT");

    companion object {
        fun fromStorageKey(value: String?): MeasurementSide? = entries.firstOrNull { it.storageKey == value }
    }
}

enum class VideoSource(val storageKey: String) {
    IMPORTED("IMPORTED"),
    RECORDED("RECORDED"),
    UNKNOWN("UNKNOWN");

    companion object {
        fun fromStorageKey(value: String?): VideoSource = entries.firstOrNull { it.storageKey == value } ?: UNKNOWN
    }
}

enum class EventType(val storageKey: String) {
    MOVEMENT_START("MOVEMENT_START"),
    INITIAL_CONTACT("INITIAL_CONTACT"),
    TAKEOFF("TAKEOFF"),
    LANDING("LANDING");

    companion object {
        fun fromStorageKey(value: String?): EventType? = entries.firstOrNull { it.storageKey == value }
    }
}

data class EventKey(val type: EventType, val ordinal: Int = 0)

data class EventMark(
    val key: EventKey,
    val ptsUs: Long,
    val frameIndex: Int,
    /** Durable PTS neighborhood captured from the real VideoFrameIndex when available. */
    val previousPtsUs: Long? = null,
    val nextPtsUs: Long? = null,
)

/** Canonical normalization shared by persistence, backup and future note editors. */
object NoteNormalizer {
    const val MAX_LENGTH = 500

    fun normalize(value: String?): String? {
        val normalized = value?.trim()?.takeUnless { it.isEmpty() } ?: return null
        require(normalized.length <= MAX_LENGTH) {
            "La nota no puede superar $MAX_LENGTH caracteres."
        }
        return normalized
    }
}

fun normalizeNote(value: String?): String? = NoteNormalizer.normalize(value)

enum class MetricKey(val storageKey: String, val isEstimate: Boolean = false) {
    HEIGHT_CM("HEIGHT_CM"),
    FLIGHT_TIME_MS("FLIGHT_TIME_MS"),
    TAKEOFF_VELOCITY_MPS("TAKEOFF_VELOCITY_MPS"),
    TIME_TO_TAKEOFF_MS("TIME_TO_TAKEOFF_MS"),
    RSI_MOD("RSI_MOD"),
    CONTACT_TIME_MS("CONTACT_TIME_MS"),
    RSI("RSI"),
    DISTANCE_CM("DISTANCE_CM"),
    DISTANCE_M("DISTANCE_M"),
    ASYMMETRY_PERCENT("ASYMMETRY_PERCENT"),
    BEST_FIVE_RSI_MEAN("BEST_FIVE_RSI_MEAN"),
    ESTIMATED_PEAK_POWER_SAYERS_W("ESTIMATED_PEAK_POWER_SAYERS_W", true),
    ESTIMATED_RELATIVE_PEAK_POWER_SAYERS_W_PER_KG(
        "ESTIMATED_RELATIVE_PEAK_POWER_SAYERS_W_PER_KG",
        true,
    ),
    ESTIMATED_POTENTIAL_ENERGY_J("ESTIMATED_POTENTIAL_ENERGY_J", true),
    ESTIMATED_TAKEOFF_KINETIC_ENERGY_J("ESTIMATED_TAKEOFF_KINETIC_ENERGY_J", true),
    ESTIMATED_TAKEOFF_MOMENTUM_KG_MPS("ESTIMATED_TAKEOFF_MOMENTUM_KG_MPS", true),
    RELATIVE_JUMP_HEIGHT_PERCENT("RELATIVE_JUMP_HEIGHT_PERCENT", true),
    RELATIVE_HORIZONTAL_DISTANCE_PERCENT("RELATIVE_HORIZONTAL_DISTANCE_PERCENT", true);

    companion object {
        fun fromStorageKey(value: String?): MetricKey? = entries.firstOrNull { it.storageKey == value }
    }
}

enum class MetricUnit(val storageKey: String, val symbol: String) {
    METER("METER", "m"),
    CENTIMETER("CENTIMETER", "cm"),
    MILLISECOND("MILLISECOND", "ms"),
    METER_PER_SECOND("METER_PER_SECOND", "m/s"),
    PERCENT("PERCENT", "%"),
    WATT("WATT", "W"),
    WATT_PER_KILOGRAM("WATT_PER_KILOGRAM", "W/kg"),
    JOULE("JOULE", "J"),
    KILOGRAM_METER_PER_SECOND("KILOGRAM_METER_PER_SECOND", "kg·m/s");

    companion object {
        fun fromStorageKey(value: String?): MetricUnit? = entries.firstOrNull { it.storageKey == value }
    }
}

data class MetricValue(
    val key: MetricKey,
    val value: Double,
    val unit: MetricUnit,
    val ordinal: Int = 0,
)

enum class MeasurementMethod { FLIGHT_TIME, MANUAL_CALIBRATED_DISTANCE }

data class ProtocolResult(
    val protocolId: ProtocolId,
    val primaryMetric: MetricValue,
    val secondaryMetrics: List<MetricValue>,
    val method: MeasurementMethod = MeasurementMethod.FLIGHT_TIME,
) {
    val allMetrics: List<MetricValue> = listOf(primaryMetric) + secondaryMetrics
}

data class ProtocolSetup(
    val side: MeasurementSide? = null,
    val dropHeightCm: Double? = null,
    val distanceCm: Double? = null,
)

/** Immutable profile values captured when the athlete is assigned to a measurement. */
data class AthleteAnthropometrics(
    val weightKg: Double? = null,
    val heightCm: Double? = null,
) {
    init {
        require(weightKg == null || weightKg.isFinite() && weightKg > 0.0)
        require(heightCm == null || heightCm.isFinite() && heightCm > 0.0)
    }
}

data class ProtocolDefinition(
    val id: ProtocolId,
    val title: String,
    val shortName: String,
    val description: String,
    val category: ProtocolCategory,
    val availability: ProtocolAvailability,
    val requiredSetup: Set<SetupField>,
    val requiredEvents: List<EventType>,
    val primaryMetric: MetricKey,
    val includesRsiMod: Boolean = false,
)
