package com.openjump.app.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.settings.CameraSettings
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

/** One camera-style quick control instead of a permanent row of FPS settings. */
@Composable
internal fun CameraQuickFpsSelector(
    selectedFps: Int,
    activeFps: Int?,
    availableFps: List<Int>,
    unavailableFps: Set<Int>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val auto = stringResource(R.string.camera_fps_auto)
    val label = when {
        selectedFps == CameraSettings.AUTO && activeFps != null ->
            stringResource(R.string.camera_fps_quick_auto, auto, activeFps)
        selectedFps == CameraSettings.AUTO -> auto
        else -> stringResource(R.string.camera_fps_quick_fixed, selectedFps)
    }
    Box(modifier) {
        Button(
            onClick = { expanded = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text(label, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(auto) },
                onClick = {
                    expanded = false
                    onSelect(CameraSettings.AUTO)
                },
            )
            availableFps.forEach { fps ->
                val unavailable = fps in unavailableFps
                DropdownMenuItem(
                    text = {
                        Text(
                            if (unavailable) stringResource(R.string.camera_fps_unavailable_chip, fps.toString())
                            else stringResource(R.string.camera_fps_quick_fixed, fps),
                        )
                    },
                    enabled = !unavailable,
                    onClick = {
                        expanded = false
                        onSelect(fps)
                    },
                )
            }
        }
    }
}

@Composable
internal fun CameraHintButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    descriptionRes: Int = R.string.camera_capture_tips,
) {
    val description = stringResource(descriptionRes)
    Surface(
        modifier = modifier.size(48.dp),
        shape = ShapeTokens.circle,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
            Text(
                text = "?",
                modifier = Modifier.clearAndSetSemantics {},
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
internal fun CameraHintOverlay(
    guidance: String,
    progress: String?,
    warnings: List<String>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            progress?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            if (guidance.isNotBlank()) {
                Text(
                    guidance,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            warnings.forEach { warning ->
                Text(
                    warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
