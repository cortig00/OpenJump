package com.openjump.app.ui.encoder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderRepetition
import com.openjump.app.encoder.EncoderSeriesKind
import com.openjump.app.encoder.EncoderTimeSeriesBuilder
import com.openjump.app.encoder.EncoderTimeSeriesResult
import com.openjump.app.encoder.TimeSeriesUnavailableReason
import com.openjump.app.encoder.PhaseKind
import com.openjump.app.encoder.RepetitionQuality
import com.openjump.app.encoder.nearestTimePoint
import com.openjump.app.encoder.resolveChartCursors
import com.openjump.app.encoder.timeScaleLabels
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.video.EncoderPlaybackCoordinator
import kotlin.math.max

@Composable
fun EncoderTimeCharts(
    analysis: EncoderAnalysis,
    repetition: EncoderRepetition?,
    coordinator: EncoderPlaybackCoordinator? = null,
    modifier: Modifier = Modifier,
    selectedKind: EncoderSeriesKind? = null,
    onSelectedKindChange: ((EncoderSeriesKind) -> Unit)? = null,
    onPointSelected: ((Long) -> Unit)? = null,
) {
    if (repetition == null) return
    var localKind by rememberSaveable { mutableStateOf(EncoderSeriesKind.POSITION) }
    val kind = selectedKind ?: localKind
    fun selectKind(next: EncoderSeriesKind) {
        if (onSelectedKindChange != null) onSelectedKindChange(next) else localKind = next
    }
    val result = remember(analysis, repetition, kind) {
        EncoderTimeSeriesBuilder.build(analysis, repetition, kind)
    }
    val velocityAvailable = remember(analysis, repetition) {
        EncoderTimeSeriesBuilder.build(analysis, repetition, EncoderSeriesKind.VELOCITY) is EncoderTimeSeriesResult.Available
    }
    val title = stringResource(
        if (kind == EncoderSeriesKind.POSITION) R.string.encoder_chart_position
        else R.string.encoder_chart_velocity,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            FilterChip(
                selected = kind == EncoderSeriesKind.POSITION,
                onClick = { selectKind(EncoderSeriesKind.POSITION) },
                colors = encoderSelectionColors(), border = null,
                label = { Text(stringResource(R.string.encoder_chart_position)) },
                leadingIcon = if (kind == EncoderSeriesKind.POSITION) {
                    { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .testTag("encoder-chart-position"),
            )
            FilterChip(
                selected = kind == EncoderSeriesKind.VELOCITY,
                onClick = { selectKind(EncoderSeriesKind.VELOCITY) },
                colors = encoderSelectionColors(), border = null,
                label = { Text(stringResource(R.string.encoder_chart_velocity)) },
                leadingIcon = if (kind == EncoderSeriesKind.VELOCITY) {
                    { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null,
                enabled = velocityAvailable,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .testTag("encoder-chart-velocity"),
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        when (result) {
            is EncoderTimeSeriesResult.Unavailable -> {
                val unavailableRes = when (result.reason) {
                    TimeSeriesUnavailableReason.UNKNOWN_TIMEBASE -> R.string.encoder_chart_unknown_timebase
                    TimeSeriesUnavailableReason.PHYSICAL_TIME_UNAVAILABLE -> R.string.encoder_chart_physical_time_unavailable
                    TimeSeriesUnavailableReason.MALFORMED_DATA,
                    TimeSeriesUnavailableReason.NO_POINTS -> R.string.encoder_chart_unavailable
                }
                val unavailable = stringResource(unavailableRes)
                Text(
                    unavailable,
                    modifier = Modifier.semantics { contentDescription = unavailable },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is EncoderTimeSeriesResult.Available -> TimeSeriesCanvas(result.series, coordinator, onPointSelected)
        }
    }
}

@Composable
private fun TimeSeriesCanvas(
    series: com.openjump.app.encoder.RepetitionTimeSeries,
    coordinator: EncoderPlaybackCoordinator?,
    onPointSelected: ((Long) -> Unit)?,
) {
    val points = series.points
    val coordinatorState = coordinator?.stateFlow?.collectAsState()?.value
    var localSourcePtsUs by remember(series) { mutableStateOf<Long?>(null) }
    val confirmedSourcePtsUs = coordinatorState?.renderedSourcePtsUs
    LaunchedEffect(confirmedSourcePtsUs) {
        if (confirmedSourcePtsUs != null && localSourcePtsUs == confirmedSourcePtsUs) localSourcePtsUs = null
    }
    val cursors = resolveChartCursors(points, localSourcePtsUs, confirmedSourcePtsUs)
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val valueUnit = if (series.kind == EncoderSeriesKind.POSITION) MeasurementQuantity.DISPLACEMENT_M else MeasurementQuantity.SPEED_MPS
    val lineColor = MaterialTheme.colorScheme.primary
    val invalidColor = MaterialTheme.colorScheme.error
    val uncertainColor = OpenJumpTheme.colors.warning
    val eccentricColor = OpenJumpTheme.colors.warning
    val concentricColor = OpenJumpTheme.colors.positive
    val cursorColor = MaterialTheme.colorScheme.onSurface
    val quality = stringResource(
        when (series.quality) {
            RepetitionQuality.VALID -> R.string.encoder_quality_valid
            RepetitionQuality.UNCERTAIN -> R.string.encoder_quality_uncertain
            RepetitionQuality.INVALID -> R.string.encoder_quality_invalid
        },
    )
    val phaseSummary = buildString {
        series.phaseBands.forEachIndexed { index, band ->
            if (index > 0) append(" · ")
            append(
                stringResource(
                    if (band.phase == PhaseKind.ECCENTRIC) R.string.encoder_metric_eccentric_time
                    else R.string.encoder_metric_concentric_time,
                ),
            )
            append(" ")
            append(band.pattern)
        }
    }
    val scale = timeScaleLabels(points)
    val startLabel = Formatting.usToClockMs(scale.startUs)
    val endLabel = Formatting.usToClockMs(scale.endUs)
    val minLabel = points.minOfOrNull { it.value }?.let {
        MeasurementFormatting.format(it, valueUnit, unitSystem, locale)
    } ?: "—"
    val maxLabel = points.maxOfOrNull { it.value }?.let {
        MeasurementFormatting.format(it, valueUnit, unitSystem, locale)
    } ?: "—"
    val summary = stringResource(
        R.string.encoder_chart_time_summary,
        startLabel,
        endLabel,
        minLabel,
        maxLabel,
        series.gaps.size,
        phaseSummary,
        quality,
    )
    val cursorStatus = when {
        cursors.previewPoint != null -> stringResource(R.string.encoder_chart_cursor_pending)
        cursors.confirmedCursorPoint != null -> stringResource(R.string.encoder_chart_cursor_confirmed)
        else -> null
    }
    val description = cursorStatus?.let { "$summary · $it" } ?: summary
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .testTag("encoder-time-series-canvas")
            .semantics { contentDescription = description }
            .pointerInput(series) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var horizontalDrag = false
                    var moved = false
                    var current = down.position
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        current = change.position
                        if (change.changedToUp()) {
                            if (horizontalDrag || !moved) {
                                val maxTime = max(1L, points.maxOfOrNull { it.elapsedPhysical } ?: 1L)
                                val elapsed = (current.x / size.width * maxTime.toFloat()).toLong().coerceIn(0L, maxTime)
                                nearestTimePoint(points, elapsed)?.let {
                                    localSourcePtsUs = it.sourcePts
                                    if (horizontalDrag || !moved) onPointSelected?.invoke(it.sourcePts)
                                }
                            }
                            break
                        }
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!moved && kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > viewConfiguration.touchSlop) {
                            moved = true
                            horizontalDrag = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                            if (!horizontalDrag) break
                        }
                        if (horizontalDrag) {
                            change.consume()
                            val maxTime = max(1L, points.maxOfOrNull { it.elapsedPhysical } ?: 1L)
                            val elapsed = (change.position.x / size.width * maxTime.toFloat()).toLong().coerceIn(0L, maxTime)
                            nearestTimePoint(points, elapsed)?.let { localSourcePtsUs = it.sourcePts }
                        }
                    }
                }
            },
    ) {
        if (points.isEmpty()) return@Canvas
        val minValue = points.minOf { it.value }
        val maxValue = points.maxOf { it.value }
        val rangeValue = max(0.000001, maxValue - minValue)
        val maxTime = max(1L, points.maxOf { it.elapsedPhysical })
        val scaleX = size.width / maxTime.toFloat()
        val scaleY = (size.height - 12f) / rangeValue.toFloat()
        series.segments.forEach { segment ->
            val path = Path()
            segment.points.forEachIndexed { index, point ->
                val x = point.elapsedPhysical * scaleX
                val y = size.height - 6f - ((point.value - minValue) * scaleY).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                color = when (series.quality) {
                    RepetitionQuality.VALID -> lineColor
                    RepetitionQuality.UNCERTAIN -> uncertainColor
                    RepetitionQuality.INVALID -> invalidColor
                },
                style = Stroke(width = 4f, cap = StrokeCap.Round),
            )
        }
        cursors.confirmedCursorPoint?.let { cursor ->
            val cursorX = cursor.elapsedPhysical * scaleX
            drawLine(
                color = cursorColor,
                start = Offset(cursorX.toFloat(), 0f),
                end = Offset(cursorX.toFloat(), size.height),
                strokeWidth = 2f,
            )
        }
        cursors.previewPoint?.let { preview ->
            val cursorX = preview.elapsedPhysical * scaleX
            drawLine(
                color = lineColor,
                start = Offset(cursorX.toFloat(), 0f),
                end = Offset(cursorX.toFloat(), size.height),
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            )
        }
        series.phaseBands.forEach { band ->
            val x = band.startElapsedPhysical * scaleX
            val width = (band.endElapsedPhysical - band.startElapsedPhysical) * scaleX
            drawLine(
                color = if (band.phase == PhaseKind.ECCENTRIC) eccentricColor else concentricColor,
                start = Offset(x.toFloat(), 2f),
                end = Offset((x + width).toFloat(), 2f),
                strokeWidth = 5f,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(if (band.phase == PhaseKind.ECCENTRIC) 10f else 4f, 6f),
                ),
            )
        }
    }
    Text(
        stringResource(R.string.encoder_chart_time_scale, startLabel, endLabel),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            stringResource(R.string.encoder_chart_phase) + " · " + phaseSummary,
            modifier = Modifier.fillMaxWidth(),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
        Text(
            quality,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
    }
    }
}
