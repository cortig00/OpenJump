package com.openjump.app.ui.encoder

import androidx.annotation.StringRes
import com.openjump.app.R
import com.openjump.app.encoder.EncoderMetricKey

data class EncoderMetricHelp(
    val key: EncoderMetricKey,
    @param:StringRes val titleResource: Int,
    @param:StringRes val shortDescriptionResource: Int,
)

val encoderMetricHelp: List<EncoderMetricHelp> = listOf(
    EncoderMetricHelp(
        EncoderMetricKey.MCV,
        R.string.encoder_metric_mcv,
        R.string.encoder_help_mcv_description,
    ),
    EncoderMetricHelp(
        EncoderMetricKey.PEAK_VELOCITY,
        R.string.encoder_metric_peak_velocity,
        R.string.encoder_help_peak_description,
    ),
    EncoderMetricHelp(
        EncoderMetricKey.ROM,
        R.string.encoder_metric_rom,
        R.string.encoder_help_rom_description,
    ),
    EncoderMetricHelp(
        EncoderMetricKey.CONCENTRIC_TIME,
        R.string.encoder_metric_concentric_time,
        R.string.encoder_help_concentric_description,
    ),
    EncoderMetricHelp(
        EncoderMetricKey.ECCENTRIC_TIME,
        R.string.encoder_metric_eccentric_time,
        R.string.encoder_help_eccentric_description,
    ),
    EncoderMetricHelp(
        EncoderMetricKey.MEAN_ECCENTRIC_VELOCITY,
        R.string.encoder_metric_mev,
        R.string.encoder_help_mev_description,
    ),
    EncoderMetricHelp(
        EncoderMetricKey.PAUSE_TIME,
        R.string.encoder_metric_pause,
        R.string.encoder_help_pause_description,
    ),
    EncoderMetricHelp(
        EncoderMetricKey.VELOCITY_LOSS,
        R.string.encoder_velocity_loss,
        R.string.encoder_help_loss_description,
    ),
)

fun metricHelpFor(key: EncoderMetricKey): EncoderMetricHelp =
    requireNotNull(encoderMetricHelp.firstOrNull { it.key == key }) {
        "No help is defined for $key."
    }
