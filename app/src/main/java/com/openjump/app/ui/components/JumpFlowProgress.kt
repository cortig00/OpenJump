package com.openjump.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

/** The four user-visible stages shared by the jump measurement flow. */
enum class JumpFlowStep {
    PREPARE,
    VIDEO,
    MARK,
    RESULT,
}

/**
 * Compact left-to-right progress timeline shared by the jump flow.
 *
 * A filled connector shows the travelled portion while numbered nodes keep the
 * order explicit. This avoids directional chevrons that can read as pointing
 * both forwards and backwards when adjacent segments overlap.
 */
@Composable
fun JumpFlowProgress(
    currentStep: JumpFlowStep,
    modifier: Modifier = Modifier,
) {
    val labels = listOf(
        R.string.jump_flow_prepare,
        R.string.jump_flow_video,
        R.string.jump_flow_mark,
        R.string.jump_flow_result,
    ).map { stringResource(it) }
    val progressDescription = stringResource(
        R.string.jump_flow_progress,
        currentStep.ordinal + 1,
        JumpFlowStep.entries.size,
    )
    val fontScale = LocalDensity.current.fontScale

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = progressDescription },
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val useTwoLabelLines = maxWidth < 320.dp || fontScale > 1.15f
            val trackHeight = if (useTwoLabelLines) 84.dp else 64.dp
            ConnectedStepTimeline(
                labels = labels,
                currentStep = currentStep,
                allowTwoLabelLines = useTwoLabelLines,
                trackHeight = trackHeight,
            )
        }
    }
}

@Composable
private fun ConnectedStepTimeline(
    labels: List<String>,
    currentStep: JumpFlowStep,
    allowTwoLabelLines: Boolean,
    trackHeight: Dp,
) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val completedTrackColor = OpenJumpTheme.colors.progressCompletedContainer.copy(alpha = 0.72f)
    val progressFraction = currentStep.ordinal.toFloat() / (labels.size - 1).coerceAtLeast(1)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeight)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
        ) {
            val nodeCenterInset = size.width / (labels.size * 2f)
            val startX = nodeCenterInset
            val endX = size.width - nodeCenterInset
            val centerY = size.height / 2f
            val strokeWidth = 4.dp.toPx()
            drawLine(
                color = trackColor,
                start = Offset(startX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            if (progressFraction > 0f) {
                drawLine(
                    color = completedTrackColor,
                    start = Offset(startX, centerY),
                    end = Offset(startX + (endX - startX) * progressFraction, centerY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }

        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                val step = JumpFlowStep.entries[index]
                FlowStep(
                    step = step,
                    label = label,
                    isCompleted = step.ordinal < currentStep.ordinal,
                    isActive = step == currentStep,
                    allowTwoLabelLines = allowTwoLabelLines,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FlowStep(
    step: JumpFlowStep,
    label: String,
    isCompleted: Boolean,
    isActive: Boolean,
    allowTwoLabelLines: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = when {
        isActive -> stringResource(R.string.jump_flow_state_active)
        isCompleted -> stringResource(R.string.jump_flow_state_completed)
        else -> stringResource(R.string.jump_flow_state_pending)
    }
    val stepDescription = stringResource(
        R.string.jump_flow_step,
        step.ordinal + 1,
        label,
        state,
    )
    val labelColor = when {
        isActive -> MaterialTheme.colorScheme.primary
        isCompleted -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier.semantics { contentDescription = stepDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StepNode(
            number = step.ordinal + 1,
            isCompleted = isCompleted,
            isActive = isActive,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = label,
            style = OpenJumpTypes.Label,
            color = labelColor,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = if (allowTwoLabelLines) 2 else 1,
        )
    }
}

@Composable
private fun StepNode(
    number: Int,
    isCompleted: Boolean,
    isActive: Boolean,
) {
    val containerColor = when {
        isActive -> MaterialTheme.colorScheme.primary
        isCompleted -> OpenJumpTheme.colors.progressCompletedContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        isActive -> MaterialTheme.colorScheme.onPrimary
        isCompleted -> OpenJumpTheme.colors.progressCompletedContent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val nodeModifier = Modifier
        .size(32.dp)
        .then(
            if (!isActive && !isCompleted) {
                Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            } else {
                Modifier
            },
        )

    Surface(
        modifier = nodeModifier,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = if (isActive) 2.dp else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = number.toString(),
                style = OpenJumpTypes.Label,
                color = contentColor,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}
