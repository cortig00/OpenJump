package com.openjump.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

/** Text and prominence are supplied by the protocol; this surface has no bilateral business logic. */
enum class ProtocolFeedbackLevel { ATTEMPT, MILESTONE, COMPLETE }

@Composable
fun ProtocolFeedbackBanner(
    text: String?,
    accessibilityText: String?,
    level: ProtocolFeedbackLevel,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Surface(
            shape = ShapeTokens.medium,
            color = if (level == ProtocolFeedbackLevel.ATTEMPT) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (level == ProtocolFeedbackLevel.ATTEMPT) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = accessibilityText ?: text.orEmpty()
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text("✓", style = MaterialTheme.typography.bodyMedium)
                Text(text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
