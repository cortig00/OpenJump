package com.openjump.app.ui.result

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.math.TemporalMetricRange
import com.openjump.app.math.TemporalSensitivityResult
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.titleResource
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

/** Collapsed-by-default explanation of one-frame, real-PTS metric sensitivity. */
@Composable
fun ResultTemporalSensitivity(
    sensitivity: TemporalSensitivityResult,
    modifier: Modifier = Modifier,
) {
    if (sensitivity.ranges.isEmpty()) return
    var expanded by remember(sensitivity) { mutableStateOf(false) }
    val stateLabel = stringResource(
        if (expanded) R.string.result_temporal_sensitivity_expanded
        else R.string.result_temporal_sensitivity_collapsed,
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ShapeTokens.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics {
                        role = Role.Button
                        stateDescription = stateLabel
                    }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.result_temporal_sensitivity_title),
                    modifier = Modifier.weight(1f),
                    style = OpenJumpTypes.SectionTitle,
                )
                Text(
                    stringResource(
                        if (expanded) R.string.result_temporal_sensitivity_hide_details
                        else R.string.result_temporal_sensitivity_show_details,
                    ),
                    style = OpenJumpTypes.Label,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(expanded) {
                Column(
                    modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        stringResource(R.string.result_temporal_sensitivity_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    sensitivity.ranges.forEach { range ->
                        TemporalSensitivityRow(range)
                    }
                }
            }
        }
    }
}

@Composable
private fun TemporalSensitivityRow(range: TemporalMetricRange) {
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val base = formatTemporalSensitivityValue(range.base, range.unit, unitSystem, locale)
    val minimum = MeasurementFormatting.format(
        range.minimum,
        MeasurementFormatting.quantity(range.unit),
        unitSystem,
        locale,
        decimals = 2,
    )
    val maximum = formatTemporalSensitivityValue(range.maximum, range.unit, unitSystem, locale)
    Text(
        stringResource(
            R.string.result_temporal_sensitivity_row,
            stringResource(range.key.titleResource()),
            base,
            minimum,
            maximum,
        ),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
    )
}

internal fun formatTemporalSensitivityValue(
    value: Double,
    unit: com.openjump.app.protocol.MetricUnit,
    profile: com.openjump.app.settings.UnitProfile,
    locale: java.util.Locale,
): String = MeasurementFormatting.format(
    value,
    MeasurementFormatting.quantity(unit),
    profile,
    locale,
    decimals = 2,
)
