package com.openjump.app.ui.encoder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.encoder.EncoderRepetition
import com.openjump.app.encoder.RepetitionQuality
import com.openjump.app.encoder.encoderTrajectorySegments
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.titleResource
import com.openjump.app.ui.theme.OpenJumpTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun EncoderMcvChart(analysis: EncoderAnalysis, modifier: Modifier = Modifier) {
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val metrics = analysis.repetitions.map { it.metric(EncoderMetricKey.MCV) }
    val values = metrics.map { metric ->
        metric?.takeIf { it.validity != com.openjump.app.encoder.MetricValidity.INVALID }?.value
    }
    val best = analysis.bestMcv
    val bestIndices = analysis.repetitions.mapIndexedNotNull { index, repetition ->
        index.takeIf { isCanonicalBestMcv(repetition, best) }
    }.toSet()
    val summary = values.mapIndexed { index, value ->
        val repetition = analysis.repetitions[index]
        val quality = stringResource(repetition.quality.titleResource())
        if (value != null) {
            val confidence = if (metrics[index]?.validity == com.openjump.app.encoder.MetricValidity.LOW_CONFIDENCE) {
                stringResource(R.string.encoder_low_confidence_suffix)
            } else ""
            val bestLabel = if (index in bestIndices) " · ${stringResource(R.string.encoder_chart_best_marker)}" else ""
            stringResource(
                R.string.encoder_chart_repetition_value,
                repetition.ordinal + 1,
                MeasurementFormatting.format(value, MeasurementQuantity.SPEED_MPS, unitSystem, locale) +
                    " · " + quality + confidence + bestLabel,
            )
        } else {
            stringResource(R.string.encoder_chart_repetition_invalid, repetition.ordinal + 1) + " · " + quality
        }
    }.joinToString(". ")
    val positive = OpenJumpTheme.colors.positive
    val warning = OpenJumpTheme.colors.warning
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val axis = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(modifier) {
        Text(stringResource(R.string.encoder_chart_mcv_title), style = MaterialTheme.typography.titleMedium)
        Canvas(
            Modifier.fillMaxWidth().height(108.dp).padding(top = 8.dp).semantics {
                contentDescription = summary
            },
        ) {
            if (values.isEmpty()) return@Canvas
            val maxValue = max(0.1, values.filterNotNull().maxOrNull() ?: 0.1)
            val slot = size.width / values.size
            values.forEachIndexed { index, value ->
                val metricInvalid = metrics[index]?.validity == com.openjump.app.encoder.MetricValidity.INVALID
                val height = ((value ?: 0.0) / maxValue * (size.height - 12f)).toFloat()
                val quality = analysis.repetitions[index].quality
                val isBest = index in bestIndices
                val color = when {
                    quality == RepetitionQuality.INVALID || metricInvalid -> error
                    quality == RepetitionQuality.UNCERTAIN -> warning
                    isBest -> positive
                    else -> primary
                }
                val left = index * slot + slot * 0.18f
                val top = size.height - height
                val barSize = androidx.compose.ui.geometry.Size(slot * 0.64f, height)
                if (quality == RepetitionQuality.INVALID || metricInvalid) {
                    val markerY = size.height - 5f
                    val centerX = left + barSize.width / 2f
                    drawLine(color, Offset(centerX - 4f, markerY - 4f), Offset(centerX + 4f, markerY + 4f), 2f)
                    drawLine(color, Offset(centerX + 4f, markerY - 4f), Offset(centerX - 4f, markerY + 4f), 2f)
                } else {
                    drawRect(color, Offset(left, top), barSize)
                    if (quality == RepetitionQuality.UNCERTAIN) {
                        drawLine(onSurface, Offset(left, top + height / 2f), Offset(left + barSize.width, top + height / 2f), 3f)
                    }
                    if (isBest) drawRect(onSurface, Offset(left, top), barSize, style = Stroke(width = 2f))
                }
            }
            drawLine(axis, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 2f)
        }
        MCVAxisLabels(analysis.repetitions)
        ChartLegend()
    }
}

@Composable
private fun MCVAxisLabels(repetitions: List<EncoderRepetition>) {
    val labels = repetitions.map { stringResource(R.string.encoder_repetition_short, it.ordinal + 1) }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelSmall
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("encoder-mcv-axis")) {
        val axisWidthPx = with(density) { maxWidth.toPx() }
        val gapPx = with(density) { 4.dp.toPx() }
        val widths = remember(labels, style, density) {
            labels.map { textMeasurer.measure(it, style = style, maxLines = 1).size.width }
        }
        val shownIndices = remember(labels, widths, axisWidthPx, gapPx) {
            chooseMcvAxisLabelIndices(labels.size, widths, axisWidthPx, gapPx)
        }
        Layout(
            content = {
                shownIndices.forEach { index ->
                    var hasVisualOverflow by remember(labels[index]) { mutableStateOf(false) }
                    Text(
                        text = labels[index],
                        modifier = Modifier.testTag(
                            if (hasVisualOverflow) "encoder-mcv-axis-label-overflow" else "encoder-mcv-axis-label",
                        ),
                        style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        onTextLayout = { hasVisualOverflow = it.hasVisualOverflow },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
        ) { measurables, constraints ->
            val placeables = measurables.map { measurable ->
                measurable.measure(constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth))
            }
            layout(constraints.maxWidth, maxOf(constraints.minHeight, placeables.maxOfOrNull { it.height } ?: 0)) {
                placeables.forEachIndexed { childIndex, placeable ->
                    val index = shownIndices[childIndex]
                    val targetCenter = (index + 0.5f) * constraints.maxWidth / labels.size
                    val x = (targetCenter - placeable.width / 2f)
                        .roundToInt()
                        .coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                    placeable.place(x, 0)
                }
            }
        }
    }
}

private fun chooseMcvAxisLabelIndices(
    count: Int,
    measuredWidths: List<Int>,
    axisWidth: Float,
    gap: Float,
): List<Int> {
    if (count <= 0 || measuredWidths.isEmpty()) return emptyList()
    val slotWidth = axisWidth / count
    fun interval(index: Int): Pair<Float, Float> {
        val width = measuredWidths[index].toFloat().coerceAtMost(axisWidth)
        val center = (index + 0.5f) * slotWidth
        val left = (center - width / 2f).coerceIn(0f, (axisWidth - width).coerceAtLeast(0f))
        return left to left + width
    }
    val priority = buildList {
        add(0)
        if (count > 1) add(count - 1)
        for (index in 1 until count - 1) add(index)
    }
    val selected = mutableListOf<Int>()
    for (candidate in priority) {
        val (left, right) = interval(candidate)
        if (selected.none { existing ->
                val (otherLeft, otherRight) = interval(existing)
                left < otherRight + gap && otherLeft < right + gap
            }
        ) selected += candidate
    }
    return selected.sorted()
}

@Composable
private fun ChartLegend() {
    val validLabel = stringResource(R.string.encoder_chart_valid_marker)
    val uncertainLabel = stringResource(R.string.encoder_chart_uncertain_marker)
    val invalidLabel = stringResource(R.string.encoder_chart_invalid_marker)
    val bestLabel = stringResource(R.string.encoder_chart_best_marker)
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("■ $validLabel", style = MaterialTheme.typography.labelSmall)
            Text("▤ $uncertainLabel", style = MaterialTheme.typography.labelSmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("□ $invalidLabel", style = MaterialTheme.typography.labelSmall)
            Text("▣ $bestLabel", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun EncoderTrajectoryChart(
    analysis: EncoderAnalysis,
    repetition: EncoderRepetition?,
    modifier: Modifier = Modifier,
) {
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val segments = encoderTrajectorySegments(analysis, repetition)
    val points = segments.flatten()
    val summary = if (points.isEmpty()) {
        stringResource(R.string.encoder_chart_trajectory_empty)
    } else {
        val xRange = points.maxOf { it.x } - points.minOf { it.x }
        val yRange = points.maxOf { it.y } - points.minOf { it.y }
        stringResource(
            R.string.encoder_chart_trajectory_summary,
            MeasurementFormatting.format(xRange, MeasurementQuantity.DISPLACEMENT_M, unitSystem, locale),
            MeasurementFormatting.format(yRange, MeasurementQuantity.DISPLACEMENT_M, unitSystem, locale),
        )
    }
    val trajectoryColor = MaterialTheme.colorScheme.primary
    val startColor = MaterialTheme.colorScheme.onSurface
    val endColor = MaterialTheme.colorScheme.outline
    Column(modifier) {
        Text(
            if (repetition == null) stringResource(R.string.encoder_chart_trajectory_title)
            else stringResource(R.string.encoder_chart_trajectory_for_repetition, repetition.ordinal + 1),
            style = MaterialTheme.typography.titleMedium,
        )
        Canvas(
            Modifier.fillMaxWidth().height(220.dp).padding(top = 12.dp).semantics {
                contentDescription = summary
            },
        ) {
            if (points.size < 2) return@Canvas
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val rangeX = max(maxX - minX, 0.01)
            val rangeY = max(maxY - minY, 0.01)
            val scale = min(size.width / rangeX.toFloat(), size.height / rangeY.toFloat()) * 0.9f
            val contentWidth = rangeX.toFloat() * scale
            val contentHeight = rangeY.toFloat() * scale
            val offsetX = (size.width - contentWidth) / 2f
            val offsetY = (size.height - contentHeight) / 2f
            fun mapX(x: Double) = offsetX + ((x - minX).toFloat() * scale)
            fun mapY(y: Double) = size.height - offsetY - ((y - minY).toFloat() * scale)
            segments.forEach { segment ->
                if (segment.isEmpty()) return@forEach
                val path = Path()
                segment.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(mapX(point.x), mapY(point.y))
                    else path.lineTo(mapX(point.x), mapY(point.y))
                }
                drawPath(path, trajectoryColor, style = Stroke(width = 4f))
                drawCircle(startColor, 7f, Offset(mapX(segment.first().x), mapY(segment.first().y)))
                drawCircle(
                    endColor,
                    7f,
                    Offset(mapX(segment.last().x), mapY(segment.last().y)),
                    style = Stroke(width = 3f),
                )
            }
        }
        Text(stringResource(R.string.encoder_chart_axis_note), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.encoder_chart_trajectory_markers), style = MaterialTheme.typography.bodySmall)
    }
}
