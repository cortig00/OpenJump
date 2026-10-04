package com.openjump.app.ui

import androidx.annotation.StringRes
import com.openjump.app.R
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.encoder.QualityReason
import com.openjump.app.encoder.RepetitionQuality
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementMethod
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.VideoSource
import com.openjump.app.settings.AppLanguage
import com.openjump.app.settings.ThemeMode
import com.openjump.app.settings.UnitPreset
import com.openjump.app.ui.result.TimelineSegmentKind

@StringRes
fun AppLanguage.titleResource(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.settings_language_system
    AppLanguage.SPANISH -> R.string.settings_language_spanish
    AppLanguage.ENGLISH -> R.string.settings_language_english
    AppLanguage.FRENCH -> R.string.settings_language_french
    AppLanguage.GERMAN -> R.string.settings_language_german
    AppLanguage.PORTUGUESE_BRAZIL -> R.string.settings_language_portuguese_brazil
    AppLanguage.PORTUGUESE_PORTUGAL -> R.string.settings_language_portuguese_portugal
    AppLanguage.ITALIAN -> R.string.settings_language_italian
    AppLanguage.TURKISH -> R.string.settings_language_turkish
}

@StringRes
fun UnitPreset.titleResource(): Int = when (this) {
    UnitPreset.METRIC -> R.string.settings_units_metric
    UnitPreset.UNITED_STATES -> R.string.settings_units_us
    UnitPreset.CUSTOM -> R.string.settings_units_custom
}

@StringRes
fun ThemeMode.titleResource(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

@StringRes
fun ProtocolId.titleResource(): Int = when (this) {
    ProtocolId.CMJ -> R.string.protocol_cmj_title
    ProtocolId.SJ -> R.string.protocol_sj_title
    ProtocolId.ABALAKOV -> R.string.protocol_abalakov_title
    ProtocolId.UNILATERAL -> R.string.protocol_unilateral_title
    ProtocolId.DROP_JUMP -> R.string.protocol_drop_jump_title
    ProtocolId.REPEATED_10_5 -> R.string.protocol_repeated_title
    ProtocolId.ASYMMETRY -> R.string.protocol_bilateral_title
    ProtocolId.HORIZONTAL -> R.string.protocol_horizontal_title
}

@StringRes
fun ProtocolId.descriptionResource(): Int = when (this) {
    ProtocolId.CMJ -> R.string.protocol_cmj_description
    ProtocolId.SJ -> R.string.protocol_sj_description
    ProtocolId.ABALAKOV -> R.string.protocol_abalakov_description
    ProtocolId.UNILATERAL -> R.string.protocol_unilateral_description
    ProtocolId.DROP_JUMP -> R.string.protocol_drop_jump_description
    ProtocolId.REPEATED_10_5 -> R.string.protocol_repeated_description
    ProtocolId.ASYMMETRY -> R.string.protocol_bilateral_description
    ProtocolId.HORIZONTAL -> R.string.protocol_horizontal_description
}

@StringRes
fun EncoderExercise.titleResource(): Int = when (this) {
    EncoderExercise.BENCH_PRESS -> R.string.exercise_bench_press
    EncoderExercise.SQUAT -> R.string.exercise_squat
    EncoderExercise.DEADLIFT -> R.string.exercise_deadlift
    EncoderExercise.PULL_UP -> R.string.exercise_pull_up
}

@StringRes
fun EncoderMetricKey.titleResource(): Int = when (this) {
    EncoderMetricKey.MCV -> R.string.encoder_metric_mcv
    EncoderMetricKey.PEAK_VELOCITY -> R.string.encoder_metric_peak_velocity
    EncoderMetricKey.ROM -> R.string.encoder_metric_rom
    EncoderMetricKey.CONCENTRIC_TIME -> R.string.encoder_metric_concentric_time
    EncoderMetricKey.ECCENTRIC_TIME -> R.string.encoder_metric_eccentric_time
    EncoderMetricKey.MEAN_ECCENTRIC_VELOCITY -> R.string.encoder_metric_mev
    EncoderMetricKey.PAUSE_TIME -> R.string.encoder_metric_pause
    EncoderMetricKey.VELOCITY_LOSS -> R.string.encoder_velocity_loss
}

@StringRes
fun RepetitionQuality.titleResource(): Int = when (this) {
    RepetitionQuality.VALID -> R.string.encoder_quality_valid
    RepetitionQuality.UNCERTAIN -> R.string.encoder_quality_uncertain
    RepetitionQuality.INVALID -> R.string.encoder_quality_invalid
}

@StringRes
fun QualityReason.titleResource(): Int = when (this) {
    QualityReason.UNRELIABLE_TIME -> R.string.encoder_reason_unreliable_time
    QualityReason.INCOMPLETE_PHASE -> R.string.encoder_reason_incomplete_phase
    QualityReason.TRACKING_LOST -> R.string.encoder_reason_tracking_lost
    QualityReason.UNCERTAIN_TRACKING -> R.string.encoder_reason_uncertain_tracking
    QualityReason.LOW_CONFIDENCE -> R.string.encoder_reason_low_confidence
    QualityReason.PTS_GAP -> R.string.encoder_reason_pts_gap
    QualityReason.TURNAROUND_GAP -> R.string.encoder_reason_turnaround_gap
    QualityReason.INSUFFICIENT_SAMPLING_RATE -> R.string.encoder_reason_sampling_rate
    QualityReason.DISCONTINUITY -> R.string.encoder_reason_discontinuity
    QualityReason.ROM_OUTLIER -> R.string.encoder_reason_rom_outlier
}

@StringRes
fun EventType.titleResource(short: Boolean = false): Int = when (this) {
    EventType.MOVEMENT_START -> if (short) R.string.event_short_movement_start else R.string.event_movement_start
    EventType.INITIAL_CONTACT -> R.string.event_initial_contact
    EventType.TAKEOFF -> R.string.event_takeoff
    EventType.LANDING -> R.string.event_landing
}

@StringRes
fun MetricKey.titleResource(): Int = when (this) {
    MetricKey.HEIGHT_CM -> R.string.metric_height
    MetricKey.FLIGHT_TIME_MS -> R.string.metric_flight_time
    MetricKey.TAKEOFF_VELOCITY_MPS -> R.string.metric_takeoff_velocity
    MetricKey.TIME_TO_TAKEOFF_MS -> R.string.metric_time_to_takeoff
    MetricKey.RSI_MOD -> R.string.metric_rsi_mod
    MetricKey.CONTACT_TIME_MS -> R.string.metric_contact_time
    MetricKey.RSI -> R.string.metric_rsi
    MetricKey.DISTANCE_CM, MetricKey.DISTANCE_M -> R.string.metric_horizontal_distance
    MetricKey.ASYMMETRY_PERCENT -> R.string.metric_asymmetry
    MetricKey.BEST_FIVE_RSI_MEAN -> R.string.metric_best_five_rsi
    MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W -> R.string.metric_estimated_peak_power
    MetricKey.ESTIMATED_RELATIVE_PEAK_POWER_SAYERS_W_PER_KG ->
        R.string.metric_estimated_relative_peak_power
    MetricKey.ESTIMATED_POTENTIAL_ENERGY_J -> R.string.metric_estimated_potential_energy
    MetricKey.ESTIMATED_TAKEOFF_KINETIC_ENERGY_J -> R.string.metric_estimated_takeoff_kinetic_energy
    MetricKey.ESTIMATED_TAKEOFF_MOMENTUM_KG_MPS -> R.string.metric_estimated_takeoff_momentum
    MetricKey.RELATIVE_JUMP_HEIGHT_PERCENT -> R.string.metric_relative_jump_height
    MetricKey.RELATIVE_HORIZONTAL_DISTANCE_PERCENT -> R.string.metric_relative_horizontal_distance
}

@StringRes
fun MeasurementMethod.titleResource(): Int = when (this) {
    MeasurementMethod.FLIGHT_TIME -> R.string.method_flight_time
    MeasurementMethod.MANUAL_CALIBRATED_DISTANCE -> R.string.method_manual_distance
}

@StringRes
fun MeasurementSide.titleResource(): Int = when (this) {
    MeasurementSide.LEFT -> R.string.side_left
    MeasurementSide.RIGHT -> R.string.side_right
}

@StringRes
fun VideoSource.titleResourceOrNull(): Int? = when (this) {
    VideoSource.IMPORTED -> R.string.source_imported
    VideoSource.RECORDED -> R.string.source_recorded
    VideoSource.UNKNOWN -> null
}

@StringRes
fun TimelineSegmentKind.titleResource(): Int = when (this) {
    TimelineSegmentKind.PREPARATION -> R.string.timeline_preparation
    TimelineSegmentKind.CONTACT -> R.string.timeline_contact
    TimelineSegmentKind.FLIGHT -> R.string.timeline_flight
}
