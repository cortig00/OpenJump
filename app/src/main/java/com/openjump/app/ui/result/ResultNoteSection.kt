package com.openjump.app.ui.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import androidx.compose.material3.Surface

/** Shared note section. Live results use the multiline field; saved results use an explicit dialog. */
@Composable
internal fun ResultNotesSection(
    note: String?,
    onNoteChange: ((String) -> Unit)?,
    enabled: Boolean,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(R.string.result_notes_title), style = OpenJumpTypes.SectionTitle)
            if (onNoteChange != null) {
                OutlinedTextField(
                    value = note.orEmpty(),
                    onValueChange = { onNoteChange(it.take(NoteNormalizer.MAX_LENGTH)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    minLines = 3,
                    label = { Text(stringResource(R.string.result_notes_optional)) },
                    placeholder = { Text(stringResource(R.string.result_notes_hint)) },
                    supportingText = {
                        Text(stringResource(R.string.result_notes_counter, note.orEmpty().length, NoteNormalizer.MAX_LENGTH))
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.None,
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                )
            } else {
                Text(
                    note ?: stringResource(R.string.result_notes_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onEdit != null || onDelete != null) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        onEdit?.let { action ->
                            TextButton(onClick = action, enabled = enabled) {
                                Text(stringResource(if (note == null) R.string.result_notes_add else R.string.result_notes_edit))
                            }
                        }
                        if (note != null) {
                            onDelete?.let { action ->
                                TextButton(onClick = action, enabled = enabled) {
                                    Text(stringResource(R.string.result_notes_delete), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
