package com.openjump.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.camera.JumpCameraState
import com.openjump.app.ui.theme.Spacing

/** Never interrupt a recording, a permission request, or the Encoder camera. */
internal fun shouldOfferJumpRecordingGuide(
    isJump: Boolean,
    permissionsGranted: Boolean,
    state: JumpCameraState,
    seen: Boolean,
): Boolean = isJump && permissionsGranted && state == JumpCameraState.READY && !seen

@Composable
fun JumpRecordingGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.jump_recording_guide_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(stringResource(R.string.jump_recording_guide_body), style = MaterialTheme.typography.bodyMedium)
                listOf(
                    R.string.jump_recording_guide_stable,
                    R.string.jump_recording_guide_framing,
                    R.string.jump_recording_guide_headroom,
                    R.string.jump_recording_guide_lighting,
                ).forEach { cue ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Spacer(
                            Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                        Text(stringResource(cue), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.jump_recording_guide_done))
            }
        },
    )
}
