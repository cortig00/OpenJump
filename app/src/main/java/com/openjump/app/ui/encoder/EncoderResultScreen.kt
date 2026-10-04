package com.openjump.app.ui.encoder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.R
import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderMetric
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.encoder.EncoderMetricUnit
import com.openjump.app.encoder.EncoderRepetition
import com.openjump.app.encoder.EncoderSeriesKind
import com.openjump.app.encoder.MetricValidity
import com.openjump.app.encoder.RepetitionQuality
import com.openjump.app.protocol.VideoSource
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.components.StatusPill
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.EncoderPlaybackCoordinator
import com.openjump.app.ui.titleResource
import com.openjump.app.ui.result.ResultNoteDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncoderResultScreen(
    onHome: () -> Unit,
    onCorrect: () -> Unit,
    onDiscard: () -> Unit,
    onSaved: (() -> Unit)? = null,
    viewModel: EncoderResultViewModel = viewModel(factory = EncoderResultViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.encoder_result_title),
                onNavigationClick = onCorrect,
                navigationContentDescription = stringResource(R.string.result_correct),
            )
        },
    ) { padding ->
        when (val current = state) {
            EncoderResultState.Loading -> Loading(Modifier.padding(padding))
            is EncoderResultState.Error -> Problem(
                stringResource(R.string.encoder_result_error_body),
                viewModel::analyze,
                Modifier.padding(padding),
            )
            is EncoderResultState.Content -> EncoderResultContent(
                analysis = current.analysis,
                saveError = current.saveError,
                onCorrect = onCorrect,
                onDiscard = onDiscard,
                onSave = {
                    viewModel.save {
                        if (onSaved != null) onSaved()
                        else {
                            com.openjump.app.video.AppSession.reset()
                            onHome()
                        }
                    }
                },
                saving = current.saving,
                saved = current.savedId != null,
                noteDraft = current.noteDraft,
                onNoteChange = viewModel::editNote,
                onRemoveRepetition = if (!current.saving && current.savedId == null) {
                    viewModel::removeRepetition
                } else null,
                onHome = onHome,
                videoUri = current.videoUri,
                videoIndex = current.videoIndex,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedEncoderResultScreen(
    sessionId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: SavedEncoderResultViewModel = viewModel(factory = SavedEncoderResultViewModel.factory(sessionId)),
) {
    val state by viewModel.state.collectAsState()
    val deleting by viewModel.deleting.collectAsState()
    val deleteFailed by viewModel.deleteFailed.collectAsState()
    val noteState by viewModel.noteState.collectAsState()
    var editNote by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.encoder_saved_title),
                onNavigationClick = { if (!noteState.saving) onBack() },
                navigationContentDescription = stringResource(R.string.common_back),
                actions = {
                    if (state is SavedEncoderState.Content) {
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
            SavedEncoderState.Loading -> Loading(Modifier.padding(padding))
            SavedEncoderState.Missing -> Problem(
                message = stringResource(R.string.encoder_session_missing),
                action = onBack,
                modifier = Modifier.padding(padding),
                actionLabel = stringResource(R.string.common_back),
            )
            is SavedEncoderState.Error -> Problem(
                stringResource(R.string.encoder_result_error_body),
                viewModel::load,
                Modifier.padding(padding),
            )
            is SavedEncoderState.Content -> EncoderResultContent(
                analysis = current.stored.analysis,
                persistedContext = PersistedEncoderContext(
                    athleteName = current.stored.athleteName,
                    dateTime = current.stored.dateTime,
                    source = current.stored.source,
                ),
                videoUri = current.stored.videoUri,
                noteDraft = current.stored.notes,
                noteEnabled = !deleting && !noteState.saving,
                onNoteEdit = { editNote = true },
                onNoteDelete = { editNote = true },
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (editNote) {
        ResultNoteDialog(
            state = noteState,
            onDraftChange = viewModel::editNote,
            onSave = { viewModel.saveNote { editNote = false } },
            onDelete = if ((state as? SavedEncoderState.Content)?.stored?.notes != null) {{ viewModel.deleteNote { editNote = false } }} else null,
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

data class PersistedEncoderContext(
    val athleteName: String?,
    val dateTime: Long,
    val source: VideoSource,
)

private data class EncoderDeleteConfirmation(
    val ordinal: Int,
    val onConfirm: () -> Unit,
)

private data class EncoderConfirmation(
    val title: Int,
    val message: Int,
    val confirm: Int,
    val onConfirm: () -> Unit,
) {
    companion object {
        fun SAVE_ANYWAY(onConfirm: () -> Unit) = EncoderConfirmation(
            R.string.encoder_save_anyway_title,
            R.string.encoder_save_anyway_message,
            R.string.encoder_save_anyway_confirm,
            onConfirm,
        )

        fun DISCARD(onConfirm: () -> Unit) = EncoderConfirmation(
            R.string.encoder_discard_title,
            R.string.encoder_discard_message,
            R.string.encoder_discard_confirm,
            onConfirm,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncoderResultContent(
    analysis: EncoderAnalysis,
    modifier: Modifier = Modifier,
    persistedContext: PersistedEncoderContext? = null,
    saveError: String? = null,
    onCorrect: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    saving: Boolean = false,
    saved: Boolean = false,
    onHome: (() -> Unit)? = null,
    videoUri: String? = null,
    videoIndex: VideoFrameIndex? = null,
    noteDraft: String? = "",
    noteEnabled: Boolean = true,
    onNoteChange: ((String) -> Unit)? = null,
    onNoteEdit: (() -> Unit)? = null,
    onNoteDelete: (() -> Unit)? = null,
    onRemoveRepetition: ((Int) -> Unit)? = null,
    onPlaybackPlayerLifecycleChanged: ((Boolean) -> Unit)? = null,
) {
    var uiState by rememberSaveable(stateSaver = EncoderResultUiState.StateSaver) {
        mutableStateOf(EncoderResultUiState.initial(analysis.repetitions))
    }
    LaunchedEffect(analysis.repetitions) {
        uiState = uiState.reconcile(analysis.repetitions)
    }
    var showMetricsHelp by remember { mutableStateOf(false) }
    val helpDescription = stringResource(R.string.encoder_understand_metrics)
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    var trajectoryExpanded by rememberSaveable { mutableStateOf(false) }
    var analysisSeriesKind by rememberSaveable { mutableStateOf(EncoderSeriesKind.POSITION) }
    var notesExpanded by rememberSaveable { mutableStateOf(false) }
    var warningsExpanded by rememberSaveable { mutableStateOf(false) }
    var contextExpanded by rememberSaveable { mutableStateOf(false) }
    var showSaveErrorDetails by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<EncoderConfirmation?>(null) }
    var deleteConfirmation by remember { mutableStateOf<EncoderDeleteConfirmation?>(null) }
    val selected = uiState.selectedOrdinal?.let { ordinal ->
        analysis.repetitions.firstOrNull { it.ordinal == ordinal }
    }
    val hasNoValidRepetitions = analysis.validRepetitions.isEmpty()
    val showSaveAction = onSave != null && !saved
    var videoVisit by remember(analysis) { mutableIntStateOf(0) }
    val playbackCoordinator = remember(analysis, videoVisit) { EncoderPlaybackCoordinator() }
    val playbackState by playbackCoordinator.stateFlow.collectAsState()
    var videoIntentState by remember(analysis) { mutableStateOf(EncoderResultVideoIntentState()) }
    val summaryScrollState = rememberLazyListState()
    val repetitionsScrollState = rememberLazyListState()
    val analysisScrollState = rememberLazyListState()
    val analysisSelectorState = rememberLazyListState()
    val videoScrollState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val contentScrollState = when (uiState.view) {
        EncoderResultView.SUMMARY -> summaryScrollState
        EncoderResultView.REPETITIONS -> repetitionsScrollState
        EncoderResultView.ANALYSIS -> analysisScrollState
        EncoderResultView.VIDEO -> videoScrollState
    }

    fun enterVideo() {
        val repetition = selected
        videoIntentState = videoIntentState.enterVideo(repetition, analysis.samples)
        if (uiState.view != EncoderResultView.VIDEO) videoVisit++
        uiState = repetition?.let { uiState.showSelectedInVideo(analysis.repetitions, it.ordinal) }
            ?: uiState.show(EncoderResultView.VIDEO)
    }

    fun changeSelectedRepetition(ordinal: Int, next: EncoderResultUiState) {
        uiState = next
        videoIntentState = videoIntentState.selectRepetition(ordinal)
    }

    LaunchedEffect(playbackCoordinator, uiState.view, selected?.ordinal) {
        if (uiState.view == EncoderResultView.VIDEO && selected != null && videoIntentState.pending == null) {
            videoIntentState = videoIntentState.enterVideo(selected, analysis.samples)
        }
    }
    LaunchedEffect(
        uiState.view,
        selected?.ordinal,
        videoIntentState.pending,
        playbackCoordinator,
        playbackState.availability,
        playbackState.pendingSeek,
        playbackState.inFlightSeek,
    ) {
        if (uiState.view == EncoderResultView.VIDEO) {
            val (remaining, intent) = videoIntentState.dispatchWhenReady(
                selectedOrdinal = selected?.ordinal,
                playerAvailable = playbackState.availability == com.openjump.app.video.EncoderPlaybackAvailability.AVAILABLE,
                requestOutstanding = playbackState.pendingSeek != null || playbackState.inFlightSeek != null,
            )
            if (intent != null) {
                playbackCoordinator.requestSeek(intent.sourcePtsUs)
                videoIntentState = remaining
            } else if (remaining != videoIntentState) {
                videoIntentState = remaining
            }
        }
    }

    LaunchedEffect(uiState.view, selected?.ordinal, analysis.repetitions) {
        if (uiState.view == EncoderResultView.ANALYSIS) {
            val index = analysis.repetitions.indexOfFirst { it.ordinal == selected?.ordinal }
            if (index >= 0) analysisSelectorState.animateScrollToItem(index)
        }
    }

    deleteConfirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { deleteConfirmation = null },
            title = { Text(stringResource(R.string.encoder_delete_repetition_title, action.ordinal + 1)) },
            text = { Text(stringResource(R.string.encoder_delete_repetition_message, action.ordinal + 1)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmation = null
                        action.onConfirm()
                    },
                ) {
                    Text(
                        stringResource(R.string.encoder_delete_repetition_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmation = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(action.title)) },
            text = { Text(stringResource(action.message)) },
            confirmButton = {
                TextButton(onClick = { confirmation = null; action.onConfirm() }) {
                    Text(stringResource(action.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    if (showMetricsHelp) EncoderMetricsHelpSheet(onDismiss = { showMetricsHelp = false })
    if (showSaveErrorDetails && saveError != null) {
        AlertDialog(
            onDismissRequest = { showSaveErrorDetails = false },
            title = { Text(stringResource(R.string.encoder_save_error_title)) },
            text = { Text(saveError) },
            confirmButton = {
                TextButton(onClick = { showSaveErrorDetails = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val videoHeightCap = (maxHeight - 300.dp).coerceAtLeast(128.dp)
        Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = uiState.view.ordinal,
            edgePadding = Spacing.screenHorizontal,
        ) {
            EncoderResultView.entries.forEach { view ->
                Tab(
                    modifier = Modifier.testTag("encoder-tab-${view.name.lowercase()}"),
                    selected = uiState.view == view,
                    onClick = {
                        if (view == EncoderResultView.VIDEO) {
                            if (uiState.view != EncoderResultView.VIDEO) enterVideo()
                        } else {
                            val returningToList = view == EncoderResultView.REPETITIONS &&
                                uiState.view == EncoderResultView.ANALYSIS
                            uiState = uiState.show(view)
                            if (returningToList) {
                                val index = analysis.repetitions.indexOfFirst { it.ordinal == selected?.ordinal }
                                if (index >= 0) coroutineScope.launch {
                                    // Make the shared selection visible, including after changing R in Analysis.
                                    repetitionsScrollState.scrollToItem(index + 1)
                                }
                            }
                        }
                    },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(stringResource(view.titleResource())) },
                )
            }
        }
        if (uiState.view == EncoderResultView.ANALYSIS && selected != null) {
            EncoderAnalysisRepetitionSelector(
                repetitions = analysis.repetitions,
                selectedOrdinal = selected.ordinal,
                state = analysisSelectorState,
                onSelect = { ordinal ->
                    changeSelectedRepetition(
                        ordinal,
                        uiState.selectFromAnalysis(analysis.repetitions, ordinal),
                    )
                },
            )
        }
        key(uiState.view) {
        LazyColumn(
            state = contentScrollState,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("encoder-result-content"),
            contentPadding = PaddingValues(
                horizontal = Spacing.screenHorizontal,
                vertical = Spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (uiState.view) {
                EncoderResultView.SUMMARY -> {
                    item { EncoderResultHeading(analysis) }
                    item { EncoderSetSummary(analysis) }
                    if (analysis.repetitions.isNotEmpty()) {
                        item { EncoderMcvChart(analysis, modifier = Modifier.testTag("encoder-summary-mcv-chart")) }
                    }
                    val criticalTimelineWarning = !analysis.timingDecision.isReliable
                    if (hasNoValidRepetitions) {
                        item {
                            EncoderInfoNote(
                                stringResource(R.string.encoder_correct_no_valid_title),
                                stringResource(R.string.encoder_warning_no_valid_repetitions),
                                warning = true,
                            )
                        }
                    }
                    if (criticalTimelineWarning) {
                        item {
                            EncoderInfoNote(
                                stringResource(R.string.encoder_review_session),
                                stringResource(R.string.encoder_warning_unreliable_timeline),
                                warning = true,
                            )
                        }
                    }
                    if (analysis.warnings.isNotEmpty()) {
                        item {
                            EncoderDisclosure(
                                title = stringResource(R.string.encoder_result_warnings, analysis.warnings.size),
                                expanded = warningsExpanded,
                                onToggle = { warningsExpanded = !warningsExpanded },
                                testTag = "encoder-disclosure-warnings",
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                    analysis.warnings.forEach { warning ->
                                        EncoderInfoNote(
                                            stringResource(R.string.encoder_review_session),
                                            localizedEncoderWarning(warning),
                                            warning = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        EncoderDisclosure(
                            title = stringResource(R.string.encoder_result_notes),
                            preview = noteDraft?.takeIf(String::isNotBlank),
                            expanded = notesExpanded,
                            onToggle = { notesExpanded = !notesExpanded },
                            testTag = "encoder-disclosure-notes",
                        ) {
                            com.openjump.app.ui.result.ResultNotesSection(
                                note = noteDraft,
                                onNoteChange = onNoteChange,
                                enabled = noteEnabled && !saving && !saved,
                                onEdit = onNoteEdit,
                                onDelete = onNoteDelete,
                            )
                        }
                    }
                    persistedContext?.let { context ->
                        item {
                            EncoderDisclosure(
                                title = stringResource(R.string.encoder_result_context),
                                expanded = contextExpanded,
                                onToggle = { contextExpanded = !contextExpanded },
                                testTag = "encoder-disclosure-context",
                            ) { PersistedEncoderContextCard(context) }
                        }
                    }
                    if (hasNoValidRepetitions && onCorrect != null) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Button(onClick = onCorrect, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.encoder_correct_results))
                                }
                                if (onDiscard != null) {
                                    TextButton(
                                        onClick = {
                                            confirmation = EncoderConfirmation.DISCARD { onDiscard.invoke() }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(stringResource(R.string.encoder_discard_and_return)) }
                                }
                            }
                        }
                    }
                    if (onHome != null) {
                        item {
                            OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.result_back_home))
                            }
                        }
                    }
                }

                EncoderResultView.REPETITIONS -> {
                    if (analysis.repetitions.isEmpty()) {
                        item { Text(stringResource(R.string.encoder_warning_no_repetitions)) }
                    } else {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.encoder_repetitions),
                                    modifier = Modifier.weight(1f).semantics { heading() },
                                    style = OpenJumpTypes.SectionTitle,
                                )
                                IconButton(
                                    onClick = { showMetricsHelp = true },
                                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                                        contentDescription = helpDescription
                                    },
                                ) { Icon(painterResource(R.drawable.ic_info), contentDescription = null) }
                            }
                        }
                        itemsIndexed(analysis.repetitions, key = { _, repetition -> "rep-${repetition.ordinal}" }) { index, repetition ->
                            Column {
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                RepetitionRow(
                                    repetition = repetition,
                                    selected = repetition.ordinal == selected?.ordinal,
                                    isBest = isCanonicalBestMcv(repetition, analysis.bestMcv),
                                    onClick = {
                                        changeSelectedRepetition(
                                            repetition.ordinal,
                                            uiState.selectFromList(analysis.repetitions, repetition.ordinal),
                                        )
                                        coroutineScope.launch { analysisScrollState.scrollToItem(0) }
                                    },
                                    modifier = Modifier.testTag("encoder-repetition-row-${repetition.ordinal + 1}"),
                                )
                            }
                        }
                    }
                }

                EncoderResultView.ANALYSIS -> {
                    if (selected == null) {
                        item { Text(stringResource(R.string.encoder_warning_no_repetitions)) }
                        item { EncoderTrajectoryChart(analysis, null) }
                    } else {
                        val repetition = selected
                        item {
                            SelectedRepetitionAnalysisHeader(
                                repetition = repetition,
                                isBest = isCanonicalBestMcv(repetition, analysis.bestMcv),
                            )
                        }
                        item(key = "time-series-${repetition.ordinal}") {
                            EncoderTimeCharts(
                                analysis = analysis,
                                repetition = repetition,
                                coordinator = playbackCoordinator,
                                selectedKind = analysisSeriesKind,
                                onSelectedKindChange = { analysisSeriesKind = it },
                                onPointSelected = { sourcePtsUs ->
                                    videoIntentState = videoIntentState.explorePoint(repetition.ordinal, sourcePtsUs)
                                },
                            )
                        }
                        item {
                            EncoderDisclosure(
                                title = stringResource(R.string.encoder_result_analysis_trajectory),
                                expanded = trajectoryExpanded,
                                onToggle = { trajectoryExpanded = !trajectoryExpanded },
                                testTag = "encoder-disclosure-trajectory",
                            ) {
                                EncoderTrajectoryChart(
                                    analysis,
                                    repetition,
                                    modifier = Modifier.testTag("encoder-trajectory-plot"),
                                )
                            }
                        }
                        item {
                            EncoderDisclosure(
                                title = stringResource(R.string.encoder_result_analysis_details),
                                expanded = detailsExpanded,
                                onToggle = { detailsExpanded = !detailsExpanded },
                                testTag = "encoder-show-details",
                            ) {
                                SelectedRepetitionDetails(repetition)
                            }
                        }
                        item {
                            TextButton(
                                onClick = {
                                    videoIntentState = videoIntentState.selectRepetition(repetition.ordinal)
                                    enterVideo()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.encoder_view_in_video)) }
                            if (onRemoveRepetition != null && !saving && !saved) {
                                TextButton(
                                    onClick = {
                                        deleteConfirmation = EncoderDeleteConfirmation(
                                            ordinal = repetition.ordinal,
                                            onConfirm = {
                                                uiState = uiState.afterRemoval(analysis.repetitions, repetition.ordinal)
                                                videoIntentState = videoIntentState.clearForRemoval()
                                                onRemoveRepetition(repetition.ordinal)
                                            },
                                        )
                                    },
                                    enabled = !saving && !saved,
                                    modifier = Modifier.fillMaxWidth().testTag("encoder-delete-repetition"),
                                ) {
                                    Text(
                                        stringResource(R.string.encoder_delete_repetition_action, repetition.ordinal + 1),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }

                EncoderResultView.VIDEO -> {
                    item {
                        Text(
                            stringResource(R.string.encoder_result_video_heading),
                            modifier = Modifier.semantics { heading() },
                            style = OpenJumpTypes.SectionTitle,
                        )
                    }
                    selected?.let { repetition ->
                        item {
                            Text(stringResource(R.string.encoder_repetition, repetition.ordinal + 1))
                        }
                    }
                    item {
                        EncoderTrajectoryVideo(
                            analysis = analysis,
                            videoUri = videoUri,
                            knownIndex = videoIndex,
                            coordinator = playbackCoordinator,
                            selectedOrdinal = selected?.ordinal,
                            onRepetitionSelected = { ordinal ->
                                val repetition = analysis.repetitions.firstOrNull { it.ordinal == ordinal }
                                if (repetition != null) {
                                    changeSelectedRepetition(
                                        ordinal,
                                        uiState.selectFromVideo(analysis.repetitions, ordinal),
                                    )
                                    videoIntentState = videoIntentState.selectVideoRail(repetition, analysis.samples)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            videoHeightCap = videoHeightCap,
                            onPlaybackPlayerLifecycleChanged = onPlaybackPlayerLifecycleChanged,
                        )
                    }
                }
            }
        }
        }
        if (showSaveAction) {
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("encoder-save-footer"),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = Spacing.screenHorizontal,
                        vertical = Spacing.sm,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (saveError != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.encoder_save_error_title),
                                modifier = Modifier.weight(1f),
                                style = OpenJumpTypes.Secondary,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(
                                onClick = { showSaveErrorDetails = true },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("encoder-save-error-details"),
                            ) { Text(stringResource(R.string.encoder_save_error_details)) }
                        }
                    }
                    EncoderSaveAction(
                        onSave = requireNotNull(onSave),
                        saving = saving,
                        saved = saved,
                        saveAnyway = hasNoValidRepetitions,
                        onSaveAnyway = {
                            confirmation = EncoderConfirmation.SAVE_ANYWAY(requireNotNull(onSave))
                        },
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun EncoderResultHeading(analysis: EncoderAnalysis) {
    val load = MeasurementFormatting.format(
        analysis.setup.loadKg,
        MeasurementQuantity.MASS_KG,
        LocalUnitSystem.current,
        currentAppLocale(),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            stringResource(analysis.setup.exercise.titleResource()),
            modifier = Modifier.semantics { heading() },
            style = OpenJumpTypes.ScreenTitle,
        )
        Text(
            if (analysis.effectiveFps == null) {
                stringResource(R.string.encoder_load_unreliable_time, load)
            } else {
                stringResource(R.string.encoder_load_fps, load, analysis.effectiveFps)
            },
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun isCanonicalBestMcv(repetition: EncoderRepetition, canonicalBest: Double?): Boolean {
    val metric = repetition.metric(EncoderMetricKey.MCV)
    return canonicalBest != null &&
        repetition.quality == RepetitionQuality.VALID &&
        metric?.validity != MetricValidity.INVALID &&
        metric?.value == canonicalBest
}

@Composable
private fun EncoderAnalysisRepetitionSelector(
    repetitions: List<EncoderRepetition>,
    selectedOrdinal: Int,
    state: androidx.compose.foundation.lazy.LazyListState,
    onSelect: (Int) -> Unit,
) {
    val selectedIndex = repetitions.indexOfFirst { it.ordinal == selectedOrdinal }
    val previousDescription = stringResource(R.string.encoder_result_previous_repetition)
    val nextDescription = stringResource(R.string.encoder_result_next_repetition)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xs)
            .testTag("encoder-analysis-selector"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        IconButton(
            onClick = { repetitions.getOrNull(selectedIndex - 1)?.let { onSelect(it.ordinal) } },
            enabled = selectedIndex > 0,
            modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
                .semantics { contentDescription = previousDescription }
                .testTag("encoder-analysis-previous"),
        ) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null) }
        LazyRow(
            state = state,
            modifier = Modifier.weight(1f).testTag("encoder-analysis-repetition-chips"),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            contentPadding = PaddingValues(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(repetitions, key = { _, repetition -> "analysis-chip-${repetition.ordinal}" }) { _, repetition ->
                val selected = repetition.ordinal == selectedOrdinal
                val chipDescription = stringResource(R.string.encoder_result_select_repetition, repetition.ordinal + 1)
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(repetition.ordinal) },
                    colors = encoderSelectionColors(),
                    border = null,
                    leadingIcon = if (selected) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                    label = { Text(stringResource(R.string.encoder_repetition_short, repetition.ordinal + 1), maxLines = 1) },
                    modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
                        .semantics { contentDescription = chipDescription }
                        .testTag("encoder-analysis-chip-${repetition.ordinal + 1}"),
                )
            }
        }
        IconButton(
            onClick = { repetitions.getOrNull(selectedIndex + 1)?.let { onSelect(it.ordinal) } },
            enabled = selectedIndex >= 0 && selectedIndex < repetitions.lastIndex,
            modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
                .semantics { contentDescription = nextDescription }
                .testTag("encoder-analysis-next"),
        ) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.rotate(180f)) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SelectedRepetitionAnalysisHeader(
    repetition: EncoderRepetition,
    isBest: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("encoder-analysis-header"),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isBest) Icon(painterResource(R.drawable.ic_trophy),
                    contentDescription = stringResource(R.string.encoder_chart_best_marker),
                    tint = OpenJumpTheme.colors.positive, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.encoder_repetition, repetition.ordinal + 1),
                    modifier = Modifier.semantics { heading() },
                    style = OpenJumpTypes.SectionTitle,
                )
            }
            QualityPill(repetition.quality)
        }
        if (repetition.reasons.isNotEmpty()) {
            Text(
                repetition.reasons.map { stringResource(it.titleResource()) }.joinToString(" · "),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().testTag("encoder-analysis-metrics")) {
            val useFlow = maxWidth < 340.dp || (LocalDensity.current.fontScale >= 1.3f && maxWidth < 440.dp)
            val metrics = listOf(
                Triple(R.string.encoder_metric_mcv_short, R.string.encoder_metric_mcv, repetition.metric(EncoderMetricKey.MCV)),
                Triple(R.string.encoder_metric_peak_velocity_short, R.string.encoder_metric_peak_velocity, repetition.metric(EncoderMetricKey.PEAK_VELOCITY)),
                Triple(R.string.encoder_metric_rom_short, R.string.encoder_metric_rom, repetition.metric(EncoderMetricKey.ROM)),
            )
            if (useFlow) {
                val metricWidth = (maxWidth - Spacing.sm) / 2
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    metrics.forEachIndexed { index, (short, full, metric) ->
                        CompactMetric(
                            stringResource(short), stringResource(full), metric,
                            Modifier.width(metricWidth).testTag("encoder-analysis-metric-$index"),
                            MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    metrics.forEachIndexed { index, (short, full, metric) ->
                        CompactMetric(
                            stringResource(short), stringResource(full), metric,
                            Modifier.weight(1f).testTag("encoder-analysis-metric-$index"),
                            MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private fun publishedBestMcv(analysis: EncoderAnalysis): Double? {
    val canonicalBest = analysis.bestMcv ?: return null
    return canonicalBest.takeIf { value -> analysis.repetitions.any { isCanonicalBestMcv(it, value) } }
}

private fun EncoderResultView.titleResource(): Int = when (this) {
    EncoderResultView.SUMMARY -> R.string.encoder_result_tab_summary
    EncoderResultView.REPETITIONS -> R.string.encoder_repetitions
    EncoderResultView.ANALYSIS -> R.string.encoder_result_tab_analysis
    EncoderResultView.VIDEO -> R.string.encoder_result_tab_video
}

@Composable
private fun EncoderSaveAction(
    onSave: () -> Unit,
    saving: Boolean,
    saved: Boolean,
    saveAnyway: Boolean,
    onSaveAnyway: () -> Unit,
) {
    if (saveAnyway) {
        OutlinedButton(
            onClick = onSaveAnyway,
            enabled = !saving && !saved,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(when {
                saved -> R.string.encoder_session_saved
                saving -> R.string.common_saving
                else -> R.string.encoder_save_anyway
            }))
        }
    } else Button(
        onClick = onSave,
        enabled = !saving && !saved,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(
            stringResource(
                when {
                    saved -> R.string.encoder_session_saved
                    saving -> R.string.common_saving
                    else -> R.string.encoder_save_session
                },
            ),
        )
    }
}

@Composable
private fun PersistedEncoderContextCard(context: PersistedEncoderContext) {
    val locale = currentAppLocale()
    val athlete = context.athleteName ?: stringResource(R.string.athlete_unassigned)
    val source = when (context.source) {
        VideoSource.IMPORTED -> stringResource(R.string.source_imported)
        VideoSource.RECORDED -> stringResource(R.string.source_recorded)
        VideoSource.UNKNOWN -> stringResource(R.string.source_unknown)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            stringResource(
                R.string.encoder_saved_context,
                athlete,
                Formatting.dateTimeToText(context.dateTime, locale),
                source,
            ),
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EncoderSetSummary(analysis: EncoderAnalysis) {
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val speedUnit = MeasurementFormatting.unit(MeasurementQuantity.SPEED_MPS, unitSystem)
    val valid = analysis.repetitions.count { it.quality == RepetitionQuality.VALID }
    val uncertain = analysis.repetitions.count { it.quality == RepetitionQuality.UNCERTAIN }
    val invalid = analysis.repetitions.count { it.quality == RepetitionQuality.INVALID }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("encoder-result-summary-hero"),
        shape = ShapeTokens.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                stringResource(R.string.encoder_best_mcv),
                style = OpenJumpTypes.MetricLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val publishedBestMcv = publishedBestMcv(analysis)
            MetricValueWithUnit(
                value = publishedBestMcv?.let {
                    MeasurementFormatting.formatValue(
                        it,
                        MeasurementQuantity.SPEED_MPS,
                        unitSystem,
                        locale,
                    )
                } ?: "—",
                unit = publishedBestMcv?.let { speedUnit.symbol },
                hero = true,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.encoder_velocity_loss),
                    modifier = Modifier.weight(1f),
                    style = OpenJumpTypes.MetricLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val velocityLoss = analysis.setVelocityLoss?.takeIf {
                    analysis.validRepetitions.lastOrNull()?.metric(EncoderMetricKey.VELOCITY_LOSS)?.validity != MetricValidity.INVALID
                }
                MetricValueWithUnit(
                    value = velocityLoss?.let {
                        MeasurementFormatting.formatValue(
                            it,
                            MeasurementQuantity.PERCENT,
                            unitSystem,
                            locale,
                        )
                    } ?: "—",
                    unit = velocityLoss?.let { "%" },
                )
            }
            Text(
                stringResource(
                    R.string.encoder_repetition_summary,
                    analysis.repetitions.size,
                    valid,
                    uncertain,
                    invalid,
                ),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricValueWithUnit(
    value: String,
    unit: String?,
    hero: Boolean = false,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            value,
            style = if (hero) OpenJumpTypes.MetricHero else OpenJumpTypes.MetricValue,
            fontWeight = FontWeight.Bold,
        )
        unit?.let {
            Text(
                it,
                modifier = Modifier.padding(bottom = Spacing.xs),
                style = OpenJumpTypes.MetricUnit,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EncoderDisclosure(
    title: String,
    preview: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    testTag: String,
    content: @Composable () -> Unit,
) {
    val stateText = stringResource(
        if (expanded) R.string.encoder_result_disclosure_collapse else R.string.encoder_result_disclosure_expand,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = onToggle,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(testTag).semantics {
                stateDescription = stateText
            },
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, modifier = Modifier.weight(1f), textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.titleMedium)
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(if (expanded) 90f else 0f))
                }
                preview?.let {
                    Text(
                        it,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
        if (expanded) content()
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RepetitionRow(
    repetition: EncoderRepetition,
    selected: Boolean,
    isBest: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    val mcvDescription = metricAccessibleDescription(
        stringResource(R.string.encoder_metric_mcv), repetition.metric(EncoderMetricKey.MCV),
    )
    val peakDescription = metricAccessibleDescription(
        stringResource(R.string.encoder_metric_peak_velocity), repetition.metric(EncoderMetricKey.PEAK_VELOCITY),
    )
    val romDescription = metricAccessibleDescription(
        stringResource(R.string.encoder_metric_rom), repetition.metric(EncoderMetricKey.ROM),
    )
    val rowDescription = listOfNotNull(
        stringResource(R.string.encoder_repetition, repetition.ordinal + 1),
        stringResource(R.string.encoder_chart_best_marker).takeIf { isBest },
        stringResource(repetition.quality.titleResource()),
        mcvDescription,
        peakDescription,
        romDescription,
    ).joinToString(". ")
    val selectedDescription = stringResource(
        if (selected) R.string.encoder_result_repetition_selected else R.string.encoder_result_repetition_select,
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = rowDescription
                stateDescription = selectedDescription
            },
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        shape = ShapeTokens.medium,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isBest) Icon(painterResource(R.drawable.ic_trophy),
                        contentDescription = stringResource(R.string.encoder_chart_best_marker),
                        tint = OpenJumpTheme.colors.positive, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.encoder_repetition, repetition.ordinal + 1),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                QualityPill(repetition.quality)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("encoder-metric-layout-${repetition.ordinal + 1}")) {
                val fontScale = LocalDensity.current.fontScale
                val useFlow = maxWidth < 300.dp || (fontScale >= 1.3f && maxWidth < 400.dp)
                val mcv = repetition.metric(EncoderMetricKey.MCV)
                val peak = repetition.metric(EncoderMetricKey.PEAK_VELOCITY)
                val rom = repetition.metric(EncoderMetricKey.ROM)
                if (useFlow) {
                    val metricWidth = (maxWidth - Spacing.sm) / 2
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        CompactMetric(stringResource(R.string.encoder_metric_mcv_short), stringResource(R.string.encoder_metric_mcv), mcv, Modifier.width(metricWidth), contentColor)
                        CompactMetric(stringResource(R.string.encoder_metric_peak_velocity_short), stringResource(R.string.encoder_metric_peak_velocity), peak, Modifier.width(metricWidth), contentColor)
                        CompactMetric(stringResource(R.string.encoder_metric_rom_short), stringResource(R.string.encoder_metric_rom), rom, Modifier.width(metricWidth), contentColor)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.Top,
                    ) {
                        CompactMetric(stringResource(R.string.encoder_metric_mcv_short), stringResource(R.string.encoder_metric_mcv), mcv, Modifier.weight(1f), contentColor)
                        CompactMetric(stringResource(R.string.encoder_metric_peak_velocity_short), stringResource(R.string.encoder_metric_peak_velocity), peak, Modifier.weight(1f), contentColor)
                        CompactMetric(stringResource(R.string.encoder_metric_rom_short), stringResource(R.string.encoder_metric_rom), rom, Modifier.weight(1f), contentColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactMetric(
    shortLabel: String,
    fullLabel: String,
    metric: EncoderMetric?,
    modifier: Modifier = Modifier,
    contentColor: Color,
) {
    val formatted = formatMetricParts(metric)
    val confidenceDescription = if (formatted.lowConfidence) {
        stringResource(R.string.encoder_low_confidence_suffix).trim(' ', '·')
    } else null
    val metricDescription = listOfNotNull(
        fullLabel,
        formatted.value,
        formatted.unit?.removeSuffix("†"),
        confidenceDescription,
    ).joinToString(", ")
    Column(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = metricDescription
        },
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            shortLabel,
            style = OpenJumpTypes.MetricLabel,
            color = contentColor.copy(alpha = 0.72f),
        )
        Text(
            formatted.value,
            style = OpenJumpTypes.Timestamp,
            fontWeight = FontWeight.SemiBold,
            color = if (metric?.validity == MetricValidity.INVALID) {
                contentColor.copy(alpha = 0.72f)
            } else {
                contentColor
            },
        )
        formatted.unit?.let {
            Text(
                it,
                style = OpenJumpTypes.MetricUnit,
                color = contentColor.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun SelectedRepetitionDetails(repetition: EncoderRepetition) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.encoder_repetition, repetition.ordinal + 1),
                    modifier = Modifier.weight(1f).padding(end = Spacing.sm),
                    style = OpenJumpTypes.SectionTitle,
                )
                QualityPill(repetition.quality)
            }
            if (repetition.reasons.isNotEmpty()) {
                Text(
                    repetition.reasons.map { stringResource(it.titleResource()) }.joinToString(" · "),
                    modifier = Modifier.padding(top = Spacing.sm),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            repetition.metrics.forEachIndexed { index, metric ->
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = if (index == 0) Spacing.md else Spacing.sm),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                MetricDetailRow(metric)
            }
        }
    }
}

@Composable
private fun MetricDetailRow(metric: EncoderMetric) {
    val formatted = formatMetricParts(metric)
    val label = stringResource(metric.key.titleResource())
    val confidenceDescription = if (formatted.lowConfidence) {
        stringResource(R.string.encoder_low_confidence_suffix).trim(' ', '·')
    } else null
    val description = listOfNotNull(
        label,
        formatted.value,
        formatted.unit?.removeSuffix("†"),
        confidenceDescription,
    ).joinToString(", ")
    Row(
        modifier = Modifier.fillMaxWidth().testTag("encoder-metric-detail-${metric.key.name.lowercase()}")
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = OpenJumpTypes.Body,
        )
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                formatted.value,
                style = OpenJumpTypes.Timestamp,
                fontWeight = FontWeight.SemiBold,
                color = if (metric.validity == MetricValidity.INVALID) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            formatted.unit?.let {
                Text(
                    it,
                    style = OpenJumpTypes.MetricUnit,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun QualityPill(quality: RepetitionQuality) {
    val colors = OpenJumpTheme.colors
    val (containerColor, contentColor) = when (quality) {
        RepetitionQuality.VALID -> colors.positiveContainer to MaterialTheme.colorScheme.onSurface
        RepetitionQuality.UNCERTAIN -> colors.warningContainer to colors.warning
        RepetitionQuality.INVALID -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
    }
    StatusPill(
        text = stringResource(quality.titleResource()),
        containerColor = containerColor,
        contentColor = contentColor,
    )
}

private data class FormattedMetric(
    val value: String,
    val unit: String?,
    val lowConfidence: Boolean = false,
)

@Composable
private fun metricAccessibleDescription(label: String, metric: EncoderMetric?): String {
    val formatted = formatMetricParts(metric)
    val confidence = if (formatted.lowConfidence) {
        stringResource(R.string.encoder_low_confidence_suffix).trim(' ', '·')
    } else null
    return listOfNotNull(label, formatted.value, formatted.unit?.removeSuffix("†"), confidence).joinToString(", ")
}

@Composable
private fun formatMetricParts(metric: EncoderMetric?): FormattedMetric {
    if (metric == null || metric.validity == MetricValidity.INVALID || metric.value == null) {
        return FormattedMetric(stringResource(R.string.encoder_unreliable), null)
    }
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val quantity = MeasurementFormatting.quantity(metric.unit)
    val displayUnit = MeasurementFormatting.unit(quantity, unitSystem)
    val lowConfidence = metric.validity == MetricValidity.LOW_CONFIDENCE
    return FormattedMetric(
        value = MeasurementFormatting.formatValue(
            metric.value,
            quantity,
            unitSystem,
            locale,
        ),
        unit = displayUnit.symbol + if (lowConfidence) "†" else "",
        lowConfidence = lowConfidence,
    )
}

@Composable
private fun localizedEncoderWarning(warning: String): String = stringResource(
    when (warning) {
        "La cronología física no está confirmada; no se calculan velocidades ni repeticiones." ->
            R.string.encoder_warning_unreliable_timeline
        "El tracking se perdió antes de terminar el vídeo." -> R.string.encoder_warning_tracking_lost
        "No se detectaron repeticiones completas." -> R.string.encoder_warning_no_repetitions
        "No hay repeticiones válidas para el resumen de la serie." ->
            R.string.encoder_warning_no_valid_repetitions
        else -> R.string.encoder_warning_generic
    },
)

@Composable
private fun Loading(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.encoder_processing_trajectory),
            modifier = Modifier.padding(top = Spacing.md),
        )
    }
}

@Composable
private fun Problem(
    message: String,
    action: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
) {
    Column(
        modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.encoder_result_error), style = MaterialTheme.typography.headlineMedium)
        Text(message, modifier = Modifier.padding(vertical = Spacing.md))
        Button(onClick = action, modifier = Modifier.fillMaxWidth()) {
            Text(actionLabel ?: stringResource(R.string.common_retry))
        }
    }
}
