package com.openjump.app.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.titleResource

@Composable
fun HistoryEntryRow(
    item: MeasurementHistoryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false,
    recent: Boolean = false,
) {
    val presentation = historyEntryPresentation(item, recent)
    Column(modifier = modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(role = Role.Button, onClick = onClick),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            overlineContent = {
                Text(
                    presentation.type,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    style = OpenJumpTypes.Label,
                )
            },
            headlineContent = {
                Text(presentation.title, style = MaterialTheme.typography.titleMedium)
            },
            supportingContent = {
                Column(Modifier.padding(top = Spacing.xs)) {
                    if (presentation.context.isNotEmpty()) {
                        Text(presentation.context)
                    }
                    presentation.supportingDetail?.let { detail ->
                        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = OpenJumpTypes.Secondary)
                    }
                    if (presentation.hasNotes) {
                        Text(stringResource(R.string.history_has_notes), color = MaterialTheme.colorScheme.primary, style = OpenJumpTypes.Label)
                    }
                    Text(
                        Formatting.dateTimeToText(item.dateTime),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            trailingContent = presentation.value?.let { value ->
                { Text(value, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            },
        )
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = Spacing.lg),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

private data class HistoryEntryPresentation(
    val type: String,
    val title: String,
    val context: String,
    val value: String?,
    val hasNotes: Boolean,
    val supportingDetail: String? = null,
)

@Composable
private fun historyEntryPresentation(item: MeasurementHistoryItem, recent: Boolean): HistoryEntryPresentation = when (item) {
    is MeasurementHistoryItem.Jump -> {
        val measurement = item.measurement
        val protocol = ProtocolId.fromStorageKey(measurement.protocolId)
        val side = MeasurementSide.fromStorageKey(measurement.side)
        HistoryEntryPresentation(
            type = stringResource(R.string.history_type_jump),
            title = protocol?.let { stringResource(it.titleResource()) } ?: measurement.protocolId,
            context = listOfNotNull(
                measurement.athleteName ?: stringResource(R.string.athlete_unassigned),
                if (protocol == ProtocolId.ASYMMETRY) stringResource(R.string.history_bilateral_best) else when (side) {
                    MeasurementSide.LEFT -> stringResource(R.string.history_side_left)
                    MeasurementSide.RIGHT -> stringResource(R.string.history_side_right)
                    null -> null
                },
            ).joinToString(" · "),
            value = formatJumpValue(
                measurement.primaryMetricValue,
                measurement.primaryMetricKey,
                measurement.primaryMetricUnit,
            ),
            hasNotes = measurement.hasNotes,
        )
    }

    is MeasurementHistoryItem.Encoder -> {
        val session = item.session
        val exercise = runCatching { EncoderExercise.valueOf(session.exercise) }.getOrNull()
        val unitSystem = LocalUnitSystem.current
        val locale = currentAppLocale()
        val load = MeasurementFormatting.format(
            session.loadKg,
            MeasurementQuantity.MASS_KG,
            unitSystem,
            locale,
        )
        val repetitions = pluralStringResource(
            R.plurals.history_valid_repetitions,
            session.validRepetitions,
            session.validRepetitions,
        )
        HistoryEntryPresentation(
            type = stringResource(R.string.history_type_encoder),
            title = exercise?.let { stringResource(it.titleResource()) } ?: session.exercise,
            context = listOf(
                session.athleteName ?: stringResource(R.string.athlete_unassigned),
                stringResource(
                    R.string.history_encoder_context,
                    load,
                    repetitions,
                ),
            ).joinToString(" · "),
            value = session.bestMcv?.let {
                MeasurementFormatting.format(
                    it,
                    MeasurementQuantity.SPEED_MPS,
                    unitSystem,
                    locale,
                )
            } ?: stringResource(R.string.common_value_unavailable),
            hasNotes = session.hasNotes,
        )
    }
}

@Composable
private fun formatJumpValue(value: Double, keyValue: String, unitValue: String): String {
    val key = MetricKey.fromStorageKey(keyValue)
    val unit = MetricUnit.fromStorageKey(unitValue)
    if (key == null || unit == null) {
        return MeasurementFormatting.formatRawStoredValue(value, unitValue, currentAppLocale())
    }
    return MeasurementFormatting.format(
        value,
        MeasurementFormatting.quantity(MetricValue(key, value, unit)),
        LocalUnitSystem.current,
        currentAppLocale(),
        decimals = 2,
    )
}
