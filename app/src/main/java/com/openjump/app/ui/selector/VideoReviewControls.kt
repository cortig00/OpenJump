package com.openjump.app.ui.selector

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun VideoTransportControls(
    isPlaying: Boolean,
    enabled: Boolean,
    onPreviousFrame: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNextFrame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playPauseDescription = stringResource(
        if (isPlaying) R.string.selector_pause_video else R.string.selector_play_video,
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FrameStepButton(
            forward = false,
            description = stringResource(R.string.selector_previous_frame_description),
            enabled = enabled,
            onStep = onPreviousFrame,
        )
        PlayPauseButton(
            isPlaying = isPlaying,
            description = playPauseDescription,
            enabled = enabled,
            onClick = onTogglePlayback,
        )
        FrameStepButton(
            forward = true,
            description = stringResource(R.string.selector_next_frame_description),
            enabled = enabled,
            onStep = onNextFrame,
        )
    }
}

@Composable
internal fun PlayPauseButton(
    isPlaying: Boolean,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(64.dp)
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            PlayPauseIcon(isPlaying)
        }
    }
}

@Composable
internal fun PlayPauseIcon(isPlaying: Boolean) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        if (isPlaying) {
            drawRect(
                color,
                topLeft = Offset(size.width / 6f, size.height / 12f),
                size = Size(size.width / 5f, size.height * 5f / 6f),
            )
            drawRect(
                color,
                topLeft = Offset(size.width * 19f / 30f, size.height / 12f),
                size = Size(size.width / 5f, size.height * 5f / 6f),
            )
        } else {
            drawPath(
                path = Path().apply {
                    moveTo(size.width * 5f / 24f, size.height / 12f)
                    lineTo(size.width * 7f / 8f, size.height / 2f)
                    lineTo(size.width * 5f / 24f, size.height * 11f / 12f)
                    close()
                },
                color = color,
            )
        }
    }
}

@Composable
internal fun FrameStepButton(
    forward: Boolean,
    description: String,
    enabled: Boolean,
    onStep: () -> Unit,
) {
    val currentOnStep by rememberUpdatedState(onStep)
    var repeating by remember { mutableStateOf(false) }
    LaunchedEffect(repeating, enabled) {
        if (!repeating || !enabled) return@LaunchedEffect
        val repeatStartedAt = SystemClock.uptimeMillis()
        while (isActive) {
            currentOnStep()
            delay(
                frameRepeatDelayMs(
                    SystemClock.uptimeMillis() - repeatStartedAt,
                ),
            )
        }
    }

    val targetContainer = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        repeating -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val targetContent = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        repeating -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val container by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = tween(durationMillis = 120),
        label = "frameStepContainer",
    )
    val content by animateColorAsState(
        targetValue = targetContent,
        animationSpec = tween(durationMillis = 120),
        label = "frameStepContent",
    )
    Surface(
        color = container,
        contentColor = content,
        shape = CircleShape,
        modifier = Modifier
            .size(56.dp)
            .semantics {
                contentDescription = description
                role = Role.Button
                if (enabled) {
                    onClick {
                        currentOnStep()
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        try {
                            tryAwaitRelease()
                        } finally {
                            repeating = false
                        }
                    },
                    onTap = { currentOnStep() },
                    onLongPress = { repeating = true },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .rotate(if (forward) 180f else 0f),
            )
        }
    }
}

/** Progressive autorepeat: precise at first, faster after a sustained hold. */
internal fun frameRepeatDelayMs(elapsedSinceRepeatStartedMs: Long): Long = when {
    elapsedSinceRepeatStartedMs < 800L -> 120L
    elapsedSinceRepeatStartedMs < 2_000L -> 70L
    else -> 35L
}
