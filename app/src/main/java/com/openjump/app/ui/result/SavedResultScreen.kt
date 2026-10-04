package com.openjump.app.ui.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.openjump.app.ui.titleResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedResultScreen(
    assessmentId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: SavedResultViewModel = viewModel(factory = SavedResultViewModel.factory(assessmentId)),
) {
    val state by viewModel.state.collectAsState()
    val deleting by viewModel.deleting.collectAsState()
    val deleteFailed by viewModel.deleteFailed.collectAsState()
    val noteState by viewModel.noteState.collectAsState()
    var editNote by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val contentModel = (state as? SavedResultState.Content)?.model
    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = contentModel?.let { stringResource(it.protocolId.titleResource()) }
                    ?: stringResource(R.string.result_saved_title),
                subtitle = contentModel?.let {
                    stringResource(R.string.result_subtitle, stringResource(it.method.titleResource()))
                },
                onNavigationClick = { if (!noteState.saving) onBack() },
                navigationContentDescription = stringResource(R.string.common_back),
                actions = {
                    if (contentModel != null) {
                        TextButton(
                            onClick = { confirmDelete = true },
                            enabled = !deleting && !noteState.saving,
                        ) {
                            Text(
                                stringResource(R.string.result_delete_action),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            SavedResultState.Loading -> LoadingResult(Modifier.padding(padding))
            is SavedResultState.Content -> ResultContent(
                model = current.model,
                modifier = Modifier.fillMaxSize().padding(padding),
                noteEnabled = !deleting && !noteState.saving,
                onNoteEdit = { editNote = true },
                onNoteDelete = { editNote = true },
            )
            SavedResultState.Missing -> ResultProblem(
                title = stringResource(R.string.result_not_found),
                body = stringResource(R.string.result_not_found_body),
                action = onBack,
                modifier = Modifier.padding(padding),
            )
            is SavedResultState.Error -> ResultProblem(
                title = stringResource(R.string.result_open_error),
                body = current.message,
                action = viewModel::load,
                actionLabel = stringResource(R.string.common_retry),
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (editNote) {
        ResultNoteDialog(
            state = noteState,
            onDraftChange = viewModel::editNote,
            onSave = { viewModel.saveNote { editNote = false } },
            onDelete = if (contentModel?.note != null) {{ viewModel.deleteNote { editNote = false } }} else null,
            onRetry = viewModel::retryNote,
            onDismiss = { editNote = false },
        )
    }

    if (confirmDelete) {
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

    if (deleteFailed) {
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
private fun LoadingResult(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.result_loading),
            modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultProblem(
    title: String,
    body: String,
    action: () -> Unit,
    actionLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(
            body,
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = action, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
            Text(actionLabel ?: stringResource(R.string.common_back))
        }
    }
}
