package com.openjump.app.ui.athletes

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openjump.app.R
import com.openjump.app.data.PersonalRecord
import com.openjump.app.protocol.MetricValue
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.components.ProtocolFeedbackBanner
import com.openjump.app.ui.components.ProtocolFeedbackLevel
import com.openjump.app.ui.titleResource

@Composable
fun PersonalRecordFeedbackBanner(record: PersonalRecord) {
    val quantity = MeasurementFormatting.quantity(MetricValue(record.series.metricKey, record.value, record.series.unit))
    val profile = LocalUnitSystem.current
    val locale = currentAppLocale()
    val value = MeasurementFormatting.format(record.value, quantity, profile, locale, decimals = 2)
    val description = "${stringResource(record.series.protocolId.titleResource())} · ${recordCondition(record)}"
    val previous = record.previousBest
    val text = if (previous == null) {
        stringResource(R.string.pr_first_record_feedback, description, value)
    } else {
        val delta = MeasurementFormatting.format(record.value - previous, quantity, profile, locale, decimals = 2)
        stringResource(R.string.pr_new_record_improved, description, value, delta)
    }
    val announced = if (record.fromBilateral) "$text · ${stringResource(R.string.pr_bilateral_origin)}" else text
    ProtocolFeedbackBanner(announced, announced, ProtocolFeedbackLevel.COMPLETE)
}
