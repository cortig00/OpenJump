package com.openjump.app.ui.selector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.ui.encoder.encoderToolIconColors
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

/** Presentation only: the existing 1px position / 2px diameter contract is unchanged. */
@Composable
internal fun PlateReferenceFineControls(
    proposal: PlateCalibrationProposal,
    enabled: Boolean,
    onGeometry: (ImagePoint, Double) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val compactTools = compact || LocalDensity.current.fontScale > 1.2f
    BoxWithConstraints(modifier.fillMaxWidth().testTag("reference-fine-controls")) {
        val inset = if (maxWidth < 176.dp) 0.dp else if (compactTools) Spacing.sm else Spacing.md
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = ShapeTokens.medium) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(inset)) {
                val sideBySide = maxWidth >= if (compactTools) 264.dp else 336.dp
                val position: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(R.string.encoder_reference_position), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { heading() })
                        PositionPad(proposal, enabled, onGeometry, compactTools)
                        Text(stringResource(R.string.encoder_reference_position_step), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val size: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(stringResource(R.string.encoder_reference_size), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { heading() })
                        SizeSteps(proposal, enabled, onGeometry, compactTools)
                        Text(stringResource(R.string.encoder_reference_size_step), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (sideBySide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compactTools) Spacing.lg else Spacing.xl),
                    verticalAlignment = Alignment.CenterVertically) {
                    position(Modifier.width(144.dp))
                    size(Modifier.weight(1f))
                } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    position(Modifier.fillMaxWidth())
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    size(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PositionPad(proposal: PlateCalibrationProposal, enabled: Boolean, onGeometry: (ImagePoint, Double) -> Unit, compact: Boolean) {
    val position = stringResource(R.string.selector_cursor_position, proposal.center.x, proposal.center.y)
    val centerColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.width(144.dp).testTag("reference-position-pad").semantics { stateDescription = position },
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PositionStep(R.string.selector_cursor_left, 0f, ImagePoint(-1.0, 0.0), proposal, enabled, onGeometry)
                PositionStep(R.string.selector_cursor_right, 180f, ImagePoint(1.0, 0.0), proposal, enabled, onGeometry)
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PositionStep(R.string.selector_cursor_up, 90f, ImagePoint(0.0, -1.0), proposal, enabled, onGeometry)
                PositionStep(R.string.selector_cursor_down, 270f, ImagePoint(0.0, 1.0), proposal, enabled, onGeometry)
            }
        } else {
            PositionStep(R.string.selector_cursor_up, 90f, ImagePoint(0.0, -1.0), proposal, enabled, onGeometry)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PositionStep(R.string.selector_cursor_left, 0f, ImagePoint(-1.0, 0.0), proposal, enabled, onGeometry)
                // A non-interactive anchor, not a reset/recentre action.
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(24.dp)) {
                        drawCircle(centerColor, radius = size.width / 4, style = Stroke(1.5.dp.toPx()))
                        drawLine(centerColor, Offset(center.x, 0f), Offset(center.x, size.height / 5), 1.5.dp.toPx())
                        drawLine(centerColor, Offset(center.x, size.height * 4 / 5), Offset(center.x, size.height), 1.5.dp.toPx())
                        drawLine(centerColor, Offset(0f, center.y), Offset(size.width / 5, center.y), 1.5.dp.toPx())
                        drawLine(centerColor, Offset(size.width * 4 / 5, center.y), Offset(size.width, center.y), 1.5.dp.toPx())
                    }
                }
                PositionStep(R.string.selector_cursor_right, 180f, ImagePoint(1.0, 0.0), proposal, enabled, onGeometry)
            }
            PositionStep(R.string.selector_cursor_down, 270f, ImagePoint(0.0, 1.0), proposal, enabled, onGeometry)
        }
    }
}

@Composable
private fun PositionStep(label: Int, rotation: Float, delta: ImagePoint, proposal: PlateCalibrationProposal,
                         enabled: Boolean, onGeometry: (ImagePoint, Double) -> Unit) {
    val description = stringResource(label)
    FilledTonalIconButton(
        onClick = { onGeometry(ImagePoint(proposal.center.x + delta.x, proposal.center.y + delta.y), proposal.diameterPx) },
        enabled = enabled, colors = encoderToolIconColors(),
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
    ) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(24.dp).rotate(rotation)) }
}

@Composable
private fun SizeSteps(proposal: PlateCalibrationProposal, enabled: Boolean, onGeometry: (ImagePoint, Double) -> Unit, compact: Boolean) {
    val smallerDescription = stringResource(R.string.encoder_reference_smaller)
    val largerDescription = stringResource(R.string.encoder_reference_bigger)
    val smallerClick = { onGeometry(proposal.center, (proposal.diameterPx - 2.0).coerceAtLeast(8.0)) }
    val largerClick = { onGeometry(proposal.center, proposal.diameterPx + 2.0) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 224.dp || LocalDensity.current.fontScale > 1.2f
        val smaller: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedButton(onClick = smallerClick,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                enabled = enabled, modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = smallerDescription }) {
                StepSign(false); Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.encoder_reference_smaller))
            }
        }
        val larger: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedButton(onClick = largerClick,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                enabled = enabled, modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = largerDescription }) {
                StepSign(true); Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.encoder_reference_bigger))
            }
        }
        if (compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally)) {
            FilledTonalIconButton(onClick = smallerClick, enabled = enabled, colors = encoderToolIconColors(),
                modifier = Modifier.size(48.dp).semantics { contentDescription = smallerDescription }) { StepSign(false) }
            FilledTonalIconButton(onClick = largerClick, enabled = enabled, colors = encoderToolIconColors(),
                modifier = Modifier.size(48.dp).semantics { contentDescription = largerDescription }) { StepSign(true) }
        } else if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            smaller(Modifier.fillMaxWidth()); larger(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            smaller(Modifier.weight(1f)); larger(Modifier.weight(1f))
        }
    }
}

/** Decorative, consistent stroke weight rather than font-dependent +/- glyphs. */
@Composable
internal fun StepSign(plus: Boolean) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(18.dp)) {
        drawLine(color, Offset(2.dp.toPx(), center.y), Offset(size.width - 2.dp.toPx(), center.y), 2.dp.toPx())
        if (plus) drawLine(color, Offset(center.x, 2.dp.toPx()), Offset(center.x, size.height - 2.dp.toPx()), 2.dp.toPx())
    }
}
