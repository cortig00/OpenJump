package com.openjump.app.ui.result

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.openjump.app.R
import com.openjump.app.protocol.NoteNormalizer

@Composable
internal fun ResultNoteDialog(
    state: ResultNotesState,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(stringResource(R.string.result_notes_dialog_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = { onDraftChange(it.take(NoteNormalizer.MAX_LENGTH)) },
                    modifier = Modifier,
                    enabled = !state.saving,
                    minLines = 3,
                    label = { Text(stringResource(R.string.result_notes_optional)) },
                    placeholder = { Text(stringResource(R.string.result_notes_hint)) },
                    supportingText = { Text(stringResource(R.string.result_notes_counter, state.draft.length, NoteNormalizer.MAX_LENGTH)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.None,
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                )
                state.error?.let {
                    Text(
                        stringResource(R.string.result_notes_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (state.error != null) {
                TextButton(onClick = onRetry, enabled = !state.saving) { Text(stringResource(R.string.common_retry)) }
            } else {
                TextButton(onClick = onSave, enabled = !state.saving) { Text(stringResource(R.string.result_notes_save)) }
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                onDelete?.let { action ->
                    TextButton(onClick = action, enabled = !state.saving) {
                        Text(stringResource(R.string.result_notes_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss, enabled = !state.saving) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}
