package com.openjump.app.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openjump.app.R

/**
 * Shared confirmation before a destructive exit that would lose unsaved work
 * (jump result draft). Mirrors the `confirmDiscard`
 * pattern from [BilateralLiveResultScreen][com.openjump.app.ui.result.BilateralLiveResultScreen]
 * and the FINALIZING dialog in CameraScreen: cancelling/dismissing never touches
 * state, confirming delegates the actual discard to the caller.
 *
 * Both buttons meet the 48 dp minimum touch target.
 */
@Composable
fun DiscardConfirmDialog(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.common_discard),
    dismissLabel: String = stringResource(R.string.common_cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(dismissLabel)
            }
        },
    )
}
