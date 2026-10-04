package com.openjump.app.ui.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.R
import com.openjump.app.ui.components.OpenJumpTopAppBar

@Composable
fun SavedBilateralResultRoute(
    assessmentId: Long,
    onBack: () -> Unit,
    onDeleted: (() -> Unit)? = null,
    onAttemptSelected: ((Int) -> Unit)? = null,
    attemptOrdinal: Int? = null,
    viewModel: SavedBilateralResultViewModel = viewModel(factory = SavedBilateralResultViewModel.factory(assessmentId)),
) {
    val state by viewModel.state.collectAsState()
    val deleting by viewModel.deleting.collectAsState()
    val deleteFailed by viewModel.deleteFailed.collectAsState()
    val noteState by viewModel.noteState.collectAsState()
    var editNote by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    when (val current = state) {
        SavedBilateralState.Loading -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) { CircularProgressIndicator() }
        is SavedBilateralState.Content -> if (attemptOrdinal == null) {
            BilateralSavedResultScreen(
                model = current.model,
                onBack = onBack,
                onAttemptSelected = onAttemptSelected,
                onDelete = onDeleted?.let { { confirmDelete = true } },
                deleting = deleting,
                noteEnabled = !deleting && !noteState.saving,
                onNoteEdit = { editNote = true },
                onNoteDelete = { editNote = true },
            )
        } else {
            current.model.attempts.firstOrNull { it.attempt.ordinal == attemptOrdinal }?.let {
                Scaffold(topBar = {
                    OpenJumpTopAppBar(
                        title = it.protocolShortName,
                        onNavigationClick = onBack,
                        navigationContentDescription = androidx.compose.ui.res.stringResource(R.string.common_back),
                    )
                }) { padding -> ResultContent(model = it, modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(padding), showNotes = false) }
            } ?: BilateralError(R.string.result_not_found, body = R.string.result_not_found_body, onBack = onBack)
        }
        SavedBilateralState.Missing -> BilateralError(
            title = R.string.result_not_found,
            body = R.string.result_not_found_body,
            onBack = onBack,
        )
        SavedBilateralState.Error -> BilateralError(
            title = R.string.result_open_error,
            body = R.string.bilateral_open_error,
            actionLabel = R.string.common_retry,
            onBack = viewModel::load,
            secondaryAction = onBack,
            secondaryLabel = R.string.common_back,
        )
    }

    if (editNote) {
        ResultNoteDialog(
            state = noteState,
            onDraftChange = viewModel::editNote,
            onSave = { viewModel.saveNote { editNote = false } },
            onDelete = if ((state as? SavedBilateralState.Content)?.model?.note != null) {{ viewModel.deleteNote { editNote = false } }} else null,
            onRetry = viewModel::retryNote,
            onDismiss = { editNote = false },
        )
    }

    if (confirmDelete && onDeleted != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.result_delete_title)) },
            text = { Text(stringResource(R.string.result_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete(onDeleted)
                    },
                ) {
                    Text(
                        stringResource(R.string.result_delete_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (deleteFailed && onDeleted != null) {
        AlertDialog(
            onDismissRequest = viewModel::acknowledgeDeleteFailure,
            title = { Text(stringResource(R.string.result_delete_title)) },
            text = { Text(stringResource(R.string.result_delete_error)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(onDeleted) }) {
                    Text(stringResource(R.string.common_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::acknowledgeDeleteFailure) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun BilateralError(
    title: Int,
    message: String? = null,
    body: Int? = null,
    actionLabel: Int = R.string.common_back,
    onBack: () -> Unit,
    secondaryAction: (() -> Unit)? = null,
    secondaryLabel: Int = R.string.common_back,
) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(stringResource(title))
        Text(message ?: stringResource(body ?: R.string.result_not_found_body), Modifier.padding(top = 8.dp))
        Button(onClick = onBack, Modifier.padding(top = 20.dp)) { Text(stringResource(actionLabel)) }
        secondaryAction?.let { action ->
            androidx.compose.material3.OutlinedButton(onClick = action, Modifier.padding(top = 8.dp)) {
                Text(stringResource(secondaryLabel))
            }
        }
    }
}
