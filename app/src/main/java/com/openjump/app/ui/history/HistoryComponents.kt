package com.openjump.app.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    // F035: non-null only for empty Encoder rows (see isDeletableEmptyEncoderRow).
    // The row stays listed until a confirmed deletion; cancel never calls this.
    onDelete: (() -> Unit)? = null,
    deleting: Boolean = false,
) {
    // Sólo la familia Ángulos delega: tiene fila propia (icono genérico de ángulo + menú secundario
    // de borrado) y no comparte el aspecto de saltos/encoder, que quedan exactamente como están.
    if (item is MeasurementHistoryItem.Angle) {
        AngleHistoryEntryRow(
            entry = item.entry,
            onClick = onClick,
            modifier = modifier,
            showDivider = showDivider,
            onDelete = onDelete,
            deleting = deleting,
        )
        return
    }
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
            // El botón de borrado no puede depender del valor de la fila: un análisis de ángulos no
            // proyecta ningún valor y aun así debe ofrecer la acción (con su valor ausente).
            trailingContent = if (presentation.value == null && onDelete == null) {
                null
            } else {
                {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        val value = presentation.value
                        if (value != null && presentation.valuePlaceholder) {
                            // Los estados sin dato no deben competir con las cifras reales:
                            // texto secundario en vez de guion grande y en negrita. Ver F-011.
                            Text(
                                value,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = OpenJumpTypes.Secondary,
                            )
                        } else if (value != null) {
                            Text(value, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                        // F035: Delete lives next to the empty value so the row keeps its
                        // existing tokens/layout; reuse of result_delete_action copy.
                        if (onDelete != null) {
                            TextButton(
                                onClick = onDelete,
                                enabled = !deleting,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(
                                    stringResource(R.string.result_delete_action),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
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
    val valuePlaceholder: Boolean = false,
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
            } ?: stringResource(R.string.common_no_valid_reps),
            valuePlaceholder = session.bestMcv == null,
            hasNotes = session.hasNotes,
        )
    }
    // La familia Ángulos NO se presenta aquí: `HistoryEntryRow` delega en `AngleHistoryEntryRow`
    // antes de llegar a este `when`. La rama se mantiene explícita para conservar la exhaustividad
    // del tipo sellado y fallar si alguien rompiera esa delegación.
    is MeasurementHistoryItem.Angle ->
        error("La familia Ángulos se presenta con AngleHistoryEntryRow")
}

/**
 * Diálogos del único camino de borrado de HISTORY: confirmación explícita del objetivo pendiente y
 * reintento tras un fallo.
 *
 * El copy es el ya traducido de borrado de resultado (`result_delete_*`), compartido por las dos
 * familias: describe exactamente lo que ocurre aquí —los datos de análisis se eliminan y el vídeo
 * importado/guardado NO se borra—, así que no se añaden cadenas nuevas ni traducciones.
 *
 * Cancelar no ejecuta ninguna acción sobre la fila: sólo cierra el diálogo. Confirmar entrega el
 * [HistoryDeleteTarget] con su familia, nunca un `Long` suelto.
 */
@Composable
internal fun HistoryDeleteDialogs(
    pending: HistoryDeleteTarget?,
    failed: HistoryDeleteTarget?,
    onConfirm: (HistoryDeleteTarget) -> Unit,
    onCancelPending: () -> Unit,
    onRetry: (HistoryDeleteTarget) -> Unit,
    onDismissFailure: () -> Unit,
) {
    if (pending != null) {
        AlertDialog(
            onDismissRequest = onCancelPending,
            title = { Text(stringResource(R.string.result_delete_title)) },
            text = { Text(stringResource(R.string.result_delete_body)) },
            confirmButton = {
                TextButton(onClick = { onConfirm(pending) }) {
                    Text(
                        stringResource(R.string.result_delete_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelPending) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    if (failed != null) {
        AlertDialog(
            onDismissRequest = onDismissFailure,
            title = { Text(stringResource(R.string.result_delete_title)) },
            text = { Text(stringResource(R.string.result_delete_error)) },
            confirmButton = {
                TextButton(onClick = { onRetry(failed) }) {
                    Text(stringResource(R.string.common_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissFailure) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
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
