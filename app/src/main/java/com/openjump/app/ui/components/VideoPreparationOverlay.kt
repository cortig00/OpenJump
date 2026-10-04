package com.openjump.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import kotlinx.coroutines.delay

internal const val VIDEO_PREPARING_FEEDBACK_DELAY_MS = 850L

/** Keep the stop-to-first-frame threshold across the camera -> selector handoff. */
internal fun remainingVideoPreparationFeedbackDelayMs(stoppedAt: Long?, now: Long): Long =
    if (stoppedAt == null || stoppedAt > now) VIDEO_PREPARING_FEEDBACK_DELAY_MS
    else (VIDEO_PREPARING_FEEDBACK_DELAY_MS - (now - stoppedAt)).coerceAtLeast(0L)

/** Delay only the feedback, never the real finalize, indexing or navigation. */
@Composable
fun VideoPreparationOverlay(
    active: Boolean,
    modifier: Modifier = Modifier,
    delayMillis: Long = VIDEO_PREPARING_FEEDBACK_DELAY_MS,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active, delayMillis) {
        visible = false
        if (active) {
            delay(delayMillis.coerceAtLeast(0L))
            visible = true
        }
    }
    if (!active || !visible) return

    Box(
        modifier = modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.64f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.padding(Spacing.lg).widthIn(max = 340.dp),
            shape = ShapeTokens.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp).clearAndSetSemantics {},
                    strokeWidth = 3.dp,
                )
                Text(
                    text = stringResource(R.string.selector_video_loading),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.video_preparing_long_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
