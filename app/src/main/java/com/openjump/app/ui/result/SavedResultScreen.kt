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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.R
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.titleResource
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedResultScreen(
    assessmentId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: SavedResultViewModel = viewModel(factory = SavedResultViewModel.factory(assessmentId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deleting by viewModel.deleting.collectAsStateWithLifecycle()
    val deleteFailed by viewModel.deleteFailed.collectAsStateWithLifecycle()
    val noteState by viewModel.noteState.collectAsStateWithLifecycle()
    var editNote by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var showShareDialog by rememberSaveable { mutableStateOf(false) }
    var sharingCard by rememberSaveable { mutableStateOf(false) }
    var shareError by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val shareScope = rememberCoroutineScope()
    val contentState = state as? SavedResultState.Content
    val contentModel = contentState?.model
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
                        IconButton(
                            onClick = {
                                shareError = false
                                showShareDialog = true
                            },
                            enabled = !deleting && !noteState.saving && !sharingCard,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_angle_share),
                                contentDescription = stringResource(R.string.result_share_action),
                            )
                        }
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
                body = stringResource(R.string.result_open_error_body),
                action = viewModel::load,
                actionLabel = stringResource(R.string.common_retry),
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (showShareDialog && contentModel != null) {
        val protocolTitle = stringResource(contentModel.protocolId.titleResource())
        val methodLabel = stringResource(contentModel.method.titleResource())
        val primaryLabel = stringResource(contentModel.primaryMetric.key.titleResource())
        val primaryValue = ResultText.formatMetric(contentModel.primaryMetric, locale, unitSystem)
        val secondaryMetrics = contentModel.secondaryMetrics.map { metric ->
            JumpResultShareMetric(
                label = stringResource(metric.key.titleResource()),
                value = ResultText.formatMetric(metric, locale, unitSystem),
            )
        }
        val estPeakPowerLabel = stringResource(R.string.share_studio_est_peak_power_short)
        val estRelPowerLabel = stringResource(R.string.share_studio_est_rel_power_short)
        val estPowerNote = stringResource(R.string.share_studio_est_sayers_note)
        val estimatedPowerMetrics = remember(contentModel.estimatedMetrics, estPeakPowerLabel, estRelPowerLabel, locale, unitSystem) {
            extractJumpEstimatedPowerMetrics(
                estimatedMetrics = contentModel.estimatedMetrics,
                peakPowerLabel = estPeakPowerLabel,
                relativePowerLabel = estRelPowerLabel,
                locale = locale,
                unitSystem = unitSystem,
            )
        }
        val chooserTitle = stringResource(R.string.result_share_chooser_title)
        val footerLabel = stringResource(R.string.result_share_footer)
        val baseCardData = remember(
            protocolTitle,
            methodLabel,
            primaryLabel,
            primaryValue,
            secondaryMetrics,
            estimatedPowerMetrics,
            estPowerNote,
            contentModel.dateTime,
            contentState.athleteName,
            footerLabel,
        ) {
            JumpResultShareCardData(
                protocolTitle = protocolTitle,
                methodLabel = methodLabel,
                primaryLabel = primaryLabel,
                primaryValue = primaryValue,
                secondaryMetrics = secondaryMetrics,
                estimatedPowerMetrics = estimatedPowerMetrics,
                estimatedPowerNote = if (estimatedPowerMetrics.isNotEmpty()) estPowerNote else null,
                dateLabel = contentModel.dateTime?.let(Formatting::dateTimeToText),
                athleteName = contentState.athleteName,
                footerLabel = footerLabel,
            )
        }
        val frameRequests = remember(contentState.shareEvents, context) {
            selectStoredJumpShareFrameRequests(contentState.shareEvents) { event ->
                context.getString(event.type.titleResource(short = true))
            }
        }
        val readyLabel = stringResource(R.string.video_hud_phase_ready)
        val inFlightLabel = stringResource(R.string.video_hud_phase_flight)
        val landedLabel = stringResource(R.string.video_hud_phase_landed)
        val videoExportSource = remember(contentState.shareVideoUri, contentState.shareEvents, baseCardData, readyLabel, inFlightLabel, landedLabel) {
            val events = contentState.shareEvents
            val takeoffPts = events.firstOrNull { it.type == com.openjump.app.protocol.EventType.TAKEOFF }?.ptsUs
            val landingPts = events.lastOrNull { it.type == com.openjump.app.protocol.EventType.LANDING }?.ptsUs
            buildJumpVideoExportSource(
                videoUri = contentState.shareVideoUri,
                videoIndex = null,
                cardData = baseCardData,
                takeoffPtsUs = takeoffPts,
                landingPtsUs = landingPts,
                readyLabel = readyLabel,
                inFlightLabel = inFlightLabel,
                landedLabel = landedLabel,
            )
        }
        ResultShareStudioSheet(
            cardData = baseCardData,
            videoUri = contentState.shareVideoUri,
            frameRequests = frameRequests,
            sharing = sharingCard,
            shareError = shareError,
            onConfirmShare = { options, photos ->
                sharingCard = true
                shareError = false
                shareScope.launch {
                    val launched = runCatching {
                        JumpResultShareStager.share(context, baseCardData, chooserTitle, options, photos)
                    }.getOrDefault(false)
                    sharingCard = false
                    if (launched) showShareDialog = false else shareError = true
                }
            },
            onDismiss = { showShareDialog = false },
            videoExportSource = videoExportSource,
        )
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
