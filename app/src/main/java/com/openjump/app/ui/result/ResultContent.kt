package com.openjump.app.ui.result

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.ui.titleResource
import com.openjump.app.ui.titleResourceOrNull
import com.openjump.app.protocol.MeasurementMethod
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricValue
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.components.InfoBanner
import com.openjump.app.ui.components.MetricCard
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

private const val SAYERS_DOI_URL = "https://doi.org/10.1097/00005768-199904000-00013"

/** Shared body for a fresh calculation and a persisted measurement. */
@Composable
fun ResultContent(
    model: ResultUiModel,
    modifier: Modifier = Modifier,
    flowProgress: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    scrollable: Boolean = true,
    onNoteChange: ((String) -> Unit)? = null,
    noteEnabled: Boolean = true,
    onNoteEdit: (() -> Unit)? = null,
    onNoteDelete: (() -> Unit)? = null,
    showNotes: Boolean = true,
) {
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    Column(
        modifier = modifier
            .let { base -> if (scrollable) base.verticalScroll(rememberScrollState()) else base }
            .padding(Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        flowProgress()

        MetricCard(
            label = stringResource(model.primaryMetric.key.titleResource()),
            value = ResultText.formatMetric(model.primaryMetric, locale, unitSystem),
            primary = true,
            modifier = Modifier.fillMaxWidth(),
        )

        model.horizontal?.let { horizontal ->
            InfoBanner(
                title = stringResource(R.string.result_horizontal_title),
                body = stringResource(
                    R.string.result_horizontal_body,
                    horizontal.landingHeel.frameIndex + 1,
                    MeasurementFormatting.format(
                        horizontal.calibration.referenceLengthM,
                        MeasurementQuantity.DISTANCE_M,
                        unitSystem,
                        locale,
                        decimals = 2,
                    ),
                ),
            )
        }

        model.timeline?.let { ResultTimeline(it) }
        model.warning?.let { warning ->
            val body = stringResource(
                when (warning) {
                    TimelineWarning.UNSUPPORTED_PROTOCOL -> R.string.result_warning_unsupported_timeline
                    TimelineWarning.MISSING_EVENTS -> R.string.result_warning_missing_events
                    TimelineWarning.INVALID_PTS -> R.string.result_warning_invalid_pts
                },
            )
            InfoBanner(
                title = stringResource(R.string.result_temporal_incomplete),
                body = body,
                warning = true,
            )
        }

        if (model.secondaryMetrics.isNotEmpty()) {
            Text(
                stringResource(R.string.result_metrics),
                modifier = Modifier.semantics { heading() },
                style = OpenJumpTypes.SectionTitle,
                fontWeight = FontWeight.SemiBold,
            )
            MetricGrid(model.secondaryMetrics)
        }

        model.temporalSensitivity?.let { sensitivity ->
            ResultTemporalSensitivity(sensitivity)
        }

        if (model.estimatedMetrics.isNotEmpty()) {
            EstimatedMetricsSection(model.estimatedMetrics)
        }

        ResultContext(model)
        ResultTechnicalDetails(model)
        if (showNotes) ResultNotesSection(
            note = model.note,
            onNoteChange = onNoteChange,
            enabled = noteEnabled,
            onEdit = onNoteEdit,
            onDelete = onNoteDelete,
        )
        actions()
    }
}

@Composable
private fun MetricGrid(metrics: List<MetricValue>) {
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            metrics.chunked(2).forEachIndexed { index, rowMetrics ->
                if (index > 0) {
                    HorizontalDivider(
                        Modifier.padding(vertical = Spacing.md),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    rowMetrics.forEach { metric ->
                        MetricCell(
                            label = stringResource(metric.key.titleResource()),
                            value = ResultText.formatMetric(metric, locale, unitSystem),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowMetrics.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EstimatedMetricsSection(metrics: List<MetricValue>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            stringResource(R.string.result_estimates_title),
            modifier = Modifier.semantics { heading() },
            style = OpenJumpTypes.SectionTitle,
            fontWeight = FontWeight.SemiBold,
        )
        InfoBanner(
            title = stringResource(R.string.result_estimates_banner_title),
            body = stringResource(R.string.result_estimates_caveat),
        )
        MetricGrid(metrics)
        EstimateMethodology(metrics)
    }
}

@Composable
private fun EstimateMethodology(metrics: List<MetricValue>) {
    var expanded by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val keys = metrics.mapTo(mutableSetOf()) { it.key }
    val hasSayersPower = MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W in keys
    val hasMechanics = MetricKey.ESTIMATED_POTENTIAL_ENERGY_J in keys
    val hasRelativeHeight = MetricKey.RELATIVE_JUMP_HEIGHT_PERCENT in keys
    val hasRelativeDistance = MetricKey.RELATIVE_HORIZONTAL_DISTANCE_PERCENT in keys
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = ShapeTokens.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.result_estimates_method_title),
                    style = OpenJumpTypes.SectionTitle,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(if (expanded) R.string.result_hide_method else R.string.result_show_method),
                    style = OpenJumpTypes.Label,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Spacer(Modifier.height(Spacing.sm))
                    if (hasSayersPower) Text(stringResource(R.string.result_estimates_formula_power))
                    if (hasMechanics) Text(stringResource(R.string.result_estimates_formula_mechanics))
                    if (hasRelativeHeight) Text(stringResource(R.string.result_estimates_formula_relative_height))
                    if (hasRelativeDistance) Text(stringResource(R.string.result_estimates_formula_relative_distance))
                    if (hasRelativeHeight || hasRelativeDistance) {
                        MethodNote(stringResource(R.string.result_estimates_ratio_limitation))
                    }
                    if (hasMechanics) {
                        MethodNote(stringResource(R.string.result_estimates_assumptions))
                    }
                    if (hasSayersPower) {
                        MethodNote(stringResource(R.string.result_estimates_sayers_limitation))
                        TextButton(
                            onClick = { uriHandler.openUri(SAYERS_DOI_URL) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.result_estimates_sayers_source))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MethodNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Value-first metric cell used inside a grouped metrics surface. */
@Composable
private fun MetricCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            value,
            style = OpenJumpTypes.MetricValue,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            style = OpenJumpTypes.MetricLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultContext(model: ResultUiModel) {
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val rows = buildList {
        model.dateTime?.let { add(stringResource(R.string.result_date) to Formatting.dateTimeToText(it, locale)) }
        model.attempt.side?.let {
            add(stringResource(R.string.result_attempt_side) to stringResource(it.titleResource()))
        }
        model.attempt.dropHeightCm?.let {
            add(
                stringResource(R.string.result_box_height) to MeasurementFormatting.format(
                    it,
                    MeasurementQuantity.SHORT_LENGTH_CM,
                    unitSystem,
                    locale,
                    decimals = 2,
                ),
            )
        }
        model.attempt.source?.let { source ->
            source.titleResourceOrNull()?.let {
                add(stringResource(R.string.result_source) to stringResource(it))
            }
        }
        if (model.attempt.detectedFps > 0) {
            val frameMs = 1_000.0 / model.attempt.detectedFps
            add(
                stringResource(R.string.result_video_analyzed) to stringResource(
                    R.string.result_video_fps,
                    model.attempt.detectedFps,
                    frameMs,
                ),
            )
        } else {
            add(
                stringResource(R.string.result_video_analyzed) to
                    stringResource(R.string.result_fps_unavailable),
            )
        }
    }
    if (rows.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) {
                    HorizontalDivider(
                        Modifier.padding(vertical = Spacing.sm),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                DetailRow(label, value)
            }
        }
    }
}

@Composable
private fun ResultTechnicalDetails(model: ResultUiModel) {
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = ShapeTokens.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        if (model.method == MeasurementMethod.MANUAL_CALIBRATED_DISTANCE) {
                            R.string.result_points_method
                        } else {
                            R.string.result_events_method
                        },
                    ),
                    modifier = Modifier.semantics { heading() },
                    style = OpenJumpTypes.SectionTitle,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(if (expanded) R.string.result_hide_method else R.string.result_show_method),
                    style = OpenJumpTypes.Label,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Spacer(Modifier.height(Spacing.sm))
                    model.events.forEach { event ->
                        DetailRow(stringResource(event.type.titleResource(short = true)), Formatting.usToClockMs(event.ptsUs))
                    }
                    model.horizontal?.let { horizontal ->
                        DetailRow(stringResource(R.string.result_calibration_frame), (horizontal.calibration.frameIndex + 1).toString())
                        DetailRow(stringResource(R.string.result_start_frame), (horizontal.startPoint.frameIndex + 1).toString())
                        DetailRow(stringResource(R.string.result_landing_frame), (horizontal.landingHeel.frameIndex + 1).toString())
                        val scaleUnit = MeasurementFormatting.unit(MeasurementQuantity.DISTANCE_M, unitSystem)
                        DetailRow(
                            stringResource(R.string.result_scale),
                            "${MeasurementFormatting.formatValue(
                                horizontal.calibration.metersPerPixel,
                                MeasurementQuantity.DISTANCE_M,
                                unitSystem,
                                locale,
                                decimals = 6,
                            )} ${scaleUnit.symbol}/px",
                        )
                    }
                    HorizontalDivider(
                        Modifier.padding(vertical = Spacing.sm),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    DetailRow(
                        stringResource(R.string.result_method),
                        stringResource(model.method.titleResource()),
                    )
                    Text(
                        if (model.method == MeasurementMethod.MANUAL_CALIBRATED_DISTANCE) {
                            stringResource(R.string.result_distance_method_explanation)
                        } else {
                            stringResource(R.string.result_flight_method_explanation)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
