package com.openjump.app.ui.result

import com.openjump.app.protocol.MetricValue
import com.openjump.app.settings.UnitProfile
import com.openjump.app.settings.UnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import java.util.Locale

/** Deterministic numeric formatting shared by live and stored result renderers. */
object ResultText {
    fun formatMetric(
        metric: MetricValue,
        locale: Locale = Locale.getDefault(),
        unitSystem: UnitSystem = UnitSystem.METRIC,
    ): String = formatMetric(metric, locale, if (unitSystem == UnitSystem.US_CUSTOMARY) UnitProfile.UNITED_STATES else UnitProfile.METRIC)

    fun formatMetric(
        metric: MetricValue,
        locale: Locale,
        profile: UnitProfile,
    ): String = MeasurementFormatting.format(
        metric.value,
        MeasurementFormatting.quantity(metric),
        profile,
        locale,
        decimals = 2,
    )

    fun durationUs(
        us: Long,
        locale: Locale = Locale.getDefault(),
        profile: UnitProfile = UnitProfile.METRIC,
    ): String = MeasurementFormatting.format(
        us / 1_000.0,
        MeasurementQuantity.DURATION_MS,
        profile,
        locale,
    )
}
