package com.openjump.app.ui.selector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.protocol.HorizontalJumpDraft
import com.openjump.app.protocol.MeasurementValidationIssue
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.UnitAwareNumericInputState
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.protocol.HorizontalJumpStage
import com.openjump.app.tracking.ImagePoint
import kotlin.math.hypot

@Composable
fun HorizontalJumpOverlay(
    mapper: VideoCoordinateMapper,
    draft: HorizontalJumpDraft,
    enabled: Boolean,
    onPoint: (ImagePoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val distance = remember(draft) { runCatching(draft::distanceMeters).getOrNull() }
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val formattedDistance = distance?.let {
        MeasurementFormatting.format(it, MeasurementQuantity.DISTANCE_M, unitSystem, locale)
    }
    val description = stringResource(
        R.string.horizontal_overlay_description,
        (if (draft.startPoint != null) stringResource(R.string.horizontal_start_marked) else "") +
            (if (draft.landingHeel != null) stringResource(R.string.horizontal_heel_marked) else "") +
            (formattedDistance?.let { stringResource(R.string.horizontal_distance_description, it) } ?: ""),
    )
    Box(modifier.semantics { contentDescription = description }) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(mapper, enabled, draft.stage) {
                    if (enabled && draft.stage in setOf(HorizontalJumpStage.START_POINT, HorizontalJumpStage.LANDING_HEEL)) {
                        detectTapGestures { offset ->
                            mapper.viewToVideo(ImagePoint(offset.x.toDouble(), offset.y.toDouble()))?.let(onPoint)
                        }
                    }
                },
        ) {
            val calibration = draft.calibration ?: return@Canvas
            val a = mapper.videoToView(calibration.pointA).toOffset()
            val b = mapper.videoToView(calibration.pointB).toOffset()
            drawLine(
                color = Color(0xFF64B5F6).copy(alpha = 0.8f),
                start = a,
                end = b,
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
            )

            val startVideo = draft.startPoint?.point
            val heelVideo = draft.landingHeel?.point
            val start = startVideo?.let(mapper::videoToView)?.toOffset()
            val heel = heelVideo?.let(mapper::videoToView)?.toOffset()
            if (start != null) marker(start, Color(0xFFFFB74D))
            if (heel != null) marker(heel, Color(0xFF81C784))

            if (startVideo != null && heelVideo != null && start != null && heel != null) {
                val axisX = calibration.pointB.x - calibration.pointA.x
                val axisY = calibration.pointB.y - calibration.pointA.y
                val axisLength = hypot(axisX, axisY)
                if (axisLength > 0.0) {
                    val unitX = axisX / axisLength
                    val unitY = axisY / axisLength
                    val projectionPixels =
                        (heelVideo.x - startVideo.x) * unitX + (heelVideo.y - startVideo.y) * unitY
                    val projectedVideo = ImagePoint(
                        startVideo.x + projectionPixels * unitX,
                        startVideo.y + projectionPixels * unitY,
                    )
                    val projected = mapper.videoToView(projectedVideo).toOffset()
                    drawLine(Color.White.copy(alpha = 0.65f), start, heel, strokeWidth = 2f)
                    drawLine(Color(0xFFFFD54F), start, projected, strokeWidth = 6f)
                    drawLine(
                        Color.White.copy(alpha = 0.8f),
                        heel,
                        projected,
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                    )
                    marker(projected, Color(0xFFFFD54F), radius = 7f)
                }
            }
        }
        formattedDistance?.let {
            Text(
                text = it,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.78f), MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
internal fun HorizontalJumpControls(
    draft: HorizontalJumpDraft,
    seriesPresentation: SeriesPresentation,
    markingEnabled: Boolean,
    onConfirmCalibration: (Double) -> Unit,
    onRecalibrate: () -> Unit,
    onClearStart: () -> Unit,
    onClearLanding: () -> Unit,
    onJumpToLanding: () -> Unit,
    onCompute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val quantity = MeasurementQuantity.DISTANCE_M
    val displayUnit = MeasurementFormatting.unit(quantity, unitSystem)
    val defaultLengthM = 1.0
    val defaultLengthText = MeasurementFormatting.formatInputValue(
        defaultLengthM,
        quantity,
        unitSystem,
        locale,
    )
    var lengthInput by rememberSaveable(stateSaver = UnitAwareNumericInputState.Saver) {
        mutableStateOf(UnitAwareNumericInputState.initial(defaultLengthText, unitSystem, defaultLengthM))
    }
    LaunchedEffect(unitSystem) {
        lengthInput = UnitAwareNumericInputState.rebase(lengthInput, unitSystem, quantity, locale)
    }
    var calibrationError by remember { mutableStateOf<String?>(null) }
    val invalidCalibrationText = stringResource(R.string.horizontal_issue_invalid)
    val lengthText = lengthInput.text
    val length = UnitAwareNumericInputState.canonical(lengthInput, quantity, locale, unitSystem)
    val lengthInvalid = UnitAwareNumericInputState.hasInvalidCurrentValue(lengthInput, quantity, locale)
    val validationIssue = draft.validationIssue()
    val step = when (draft.stage) {
        HorizontalJumpStage.CALIBRATION -> 1
        HorizontalJumpStage.START_POINT -> 2
        HorizontalJumpStage.LANDING_HEEL, HorizontalJumpStage.COMPLETE -> 3
    }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.horizontal_step, step), fontWeight = FontWeight.SemiBold)
            when (draft.stage) {
                HorizontalJumpStage.CALIBRATION -> {
                    Text(
                        when {
                            draft.calibrationPointA == null -> stringResource(R.string.horizontal_touch_a)
                            draft.calibrationPointB == null -> stringResource(R.string.horizontal_touch_b)
                            else -> stringResource(R.string.horizontal_enter_length)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = lengthText,
                            onValueChange = { lengthInput = UnitAwareNumericInputState.edited(it, unitSystem, quantity, locale); calibrationError = null },
                            label = { Text(stringResource(R.string.horizontal_reference)) },
                            suffix = { Text(displayUnit.symbol) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                            isError = lengthInput.requiresReview || lengthInvalid,
                            supportingText = when {
                                lengthInput.requiresReview -> { { Text(stringResource(R.string.unit_input_review_required)) } }
                                lengthInvalid -> { { Text(stringResource(R.string.unit_input_invalid)) } }
                                else -> null
                            },
                        )
                        Button(
                            onClick = {
                                try {
                                    onConfirmCalibration(requireNotNull(length))
                                } catch (_: Exception) {
                                    calibrationError = invalidCalibrationText
                                }
                            },
                            enabled = markingEnabled && draft.calibrationPointA != null && draft.calibrationPointB != null &&
                                length != null && length > 0.0,
                        ) { Text(stringResource(R.string.common_confirm)) }
                    }
                    calibrationError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }

                HorizontalJumpStage.START_POINT -> {
                    Text(stringResource(R.string.horizontal_touch_start))
                    Text(stringResource(R.string.horizontal_fixed_camera), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRecalibrate) { Text(stringResource(R.string.horizontal_recalibrate)) }
                }

                HorizontalJumpStage.LANDING_HEEL -> {
                    Text(stringResource(R.string.horizontal_touch_heel))
                    Text(
                        stringResource(R.string.horizontal_closest_contact),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onClearStart) { Text(stringResource(R.string.horizontal_change_start)) }
                        TextButton(onClick = onRecalibrate) { Text(stringResource(R.string.horizontal_recalibrate)) }
                    }
                }

                HorizontalJumpStage.COMPLETE -> {
                    val distance = runCatching(draft::distanceMeters).getOrNull()
                    Text(
                        distance?.let {
                            stringResource(
                                R.string.horizontal_projected_distance,
                                MeasurementFormatting.format(it, quantity, unitSystem, locale),
                            )
                        }
                            ?: stringResource(R.string.horizontal_issue_invalid),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    draft.landingHeel?.let {
                        Text(
                            stringResource(R.string.horizontal_landing_frame, it.frameIndex + 1),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = onCompute,
                        enabled = validationIssue == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                seriesPresentation.computeResource,
                                *seriesPresentation.computeArgs.toTypedArray(),
                            ),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = onJumpToLanding, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.horizontal_go_landing))
                        }
                        TextButton(onClick = onClearLanding, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.horizontal_change_heel))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onClearStart, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.horizontal_change_start))
                        }
                        OutlinedButton(onClick = onRecalibrate, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.horizontal_recalibrate))
                        }
                    }
                }
            }
        }
    }
}

private fun ImagePoint.toOffset(): Offset = Offset(x.toFloat(), y.toFloat())

private fun androidx.compose.ui.graphics.drawscope.DrawScope.marker(
    center: Offset,
    color: Color,
    radius: Float = 10f,
) {
    drawCircle(Color.Black.copy(alpha = 0.75f), radius + 5f, center)
    drawCircle(color, radius, center)
    drawCircle(Color.White, radius, center, style = Stroke(2f))
}
