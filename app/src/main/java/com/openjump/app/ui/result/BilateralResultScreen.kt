package com.openjump.app.ui.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.R
import com.openjump.app.protocol.BilateralAggregation
import com.openjump.app.data.PersonalRecord
import com.openjump.app.protocol.BilateralComparison
import com.openjump.app.ui.components.InfoBanner
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.components.ProtocolFeedbackBanner
import com.openjump.app.ui.components.ProtocolFeedbackLevel
import com.openjump.app.ui.titleResource
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.video.AppSession

@Composable
fun BilateralLiveResultScreen(
    comparison: BilateralComparison,
    completionFeedback: Boolean = false,
    onSaved: (List<PersonalRecord>) -> Unit,
    onDiscard: () -> Unit,
    viewModel: BilateralResultViewModel = viewModel(factory = BilateralResultViewModel.Factory),
) {
    val model = remember(comparison) { BilateralResultPresenter.presentLive(comparison) }
    var confirmDiscard by remember { mutableStateOf(false) }
    BilateralResultContent(
        model = model,
        completionFeedback = completionFeedback,
        saveState = viewModel.saveState.collectAsState().value,
        onNoteChange = {
            AppSession.updateBilateralNote(it)
            viewModel.clearError()
        },
        onSave = { viewModel.save(AppSession.bilateralComparison() ?: comparison, onSaved) },
        onDiscard = { confirmDiscard = true },
        navigationContentDescription = stringResource(R.string.bilateral_discard_navigation),
    )
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.bilateral_discard_title)) },
            text = { Text(stringResource(R.string.bilateral_discard_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDiscard() }) {
                    Text(stringResource(R.string.common_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
fun BilateralSavedResultScreen(
    model: BilateralResultUiModel,
    onBack: () -> Unit,
    onAttemptSelected: ((Int) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    deleting: Boolean = false,
    noteEnabled: Boolean = true,
    onNoteEdit: (() -> Unit)? = null,
    onNoteDelete: (() -> Unit)? = null,
) {
    BilateralResultContent(
        model = model,
        saveState = null,
        onSave = {},
        onDiscard = onBack,
        onAttemptSelected = onAttemptSelected,
        onDelete = onDelete,
        deleting = deleting,
        noteEnabled = noteEnabled,
        onNoteEdit = onNoteEdit,
        onNoteDelete = onNoteDelete,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BilateralResultContent(
    model: BilateralResultUiModel,
    saveState: BilateralSaveState?,
    completionFeedback: Boolean = false,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onNoteChange: ((String) -> Unit)? = null,
    noteEnabled: Boolean = true,
    onNoteEdit: (() -> Unit)? = null,
    onNoteDelete: (() -> Unit)? = null,
    onAttemptSelected: ((Int) -> Unit)? = null,
    navigationContentDescription: String? = null,
    onDelete: (() -> Unit)? = null,
    deleting: Boolean = false,
) {
    var aggregation by remember { mutableStateOf(BilateralAggregation.BEST) }
    val navDescription = navigationContentDescription ?: stringResource(R.string.common_back)
    val summary = if (aggregation == BilateralAggregation.BEST) model.best else model.mean
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    Scaffold(topBar = {
        OpenJumpTopAppBar(
            title = stringResource(R.string.protocol_bilateral_title),
            subtitle = model.attempts.firstOrNull()?.let {
                stringResource(R.string.result_subtitle, stringResource(it.method.titleResource()))
            },
            onNavigationClick = { if (noteEnabled) onDiscard() },
            navigationContentDescription = navDescription,
            actions = {
                onDelete?.let { delete ->
                    TextButton(onClick = delete, enabled = !deleting && noteEnabled) {
                        Text(
                            stringResource(R.string.result_delete_action),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            if (completionFeedback) {
                ProtocolFeedbackBanner(
                    text = stringResource(R.string.bilateral_complete_feedback),
                    accessibilityText = stringResource(R.string.bilateral_complete_feedback),
                    level = ProtocolFeedbackLevel.COMPLETE,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                maxItemsInEachRow = 2,
            ) {
                FilterChip(
                    selected = aggregation == BilateralAggregation.BEST,
                    onClick = { aggregation = BilateralAggregation.BEST },
                    label = { Text(stringResource(R.string.bilateral_best)) },
                )
                FilterChip(
                    selected = aggregation == BilateralAggregation.MEAN,
                    onClick = { aggregation = BilateralAggregation.MEAN },
                    label = { Text(stringResource(R.string.bilateral_mean)) },
                )
            }
            Text(stringResource(R.string.bilateral_asymmetry, MeasurementFormatting.format(summary.asymmetryPercent, MeasurementQuantity.PERCENT, unitSystem, locale, 2)), style = OpenJumpTypes.MetricHero)
            Text(stringResource(R.string.bilateral_left_value, MeasurementFormatting.format(summary.leftValue, MeasurementQuantity.SHORT_LENGTH_CM, unitSystem, locale, 2)), style = OpenJumpTypes.MetricValue)
            Text(stringResource(R.string.bilateral_right_value, MeasurementFormatting.format(summary.rightValue, MeasurementQuantity.SHORT_LENGTH_CM, unitSystem, locale, 2)), style = OpenJumpTypes.MetricValue)
            summary.higherSide?.let { side ->
                Text(stringResource(if (side == com.openjump.app.protocol.MeasurementSide.LEFT) R.string.bilateral_left_higher else R.string.bilateral_right_higher))
            } ?: Text(stringResource(R.string.bilateral_tie))
            Text(
                stringResource(R.string.bilateral_interpretation_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            model.attempts.forEachIndexed { index, attempt ->
                val isLeft = index < model.attempts.size / 2
                val visibleOrdinal = if (isLeft) index + 1 else index - model.attempts.size / 2 + 1
                val expanded = remember(index) { mutableStateOf(false) }
                val rowModifier = if (saveState != null && onAttemptSelected == null) {
                    val expansionDescription = stringResource(if (expanded.value) R.string.bilateral_expanded else R.string.bilateral_collapsed)
                    Modifier.fillMaxWidth().semantics { stateDescription = expansionDescription }
                } else {
                    Modifier.fillMaxWidth()
                }
                TextButton(
                    onClick = {
                        if (onAttemptSelected != null) onAttemptSelected(index)
                        else expanded.value = !expanded.value
                    },
                    modifier = rowModifier,
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(if (isLeft) R.string.protocol_side_left else R.string.protocol_side_right) + " · " + visibleOrdinal)
                        Text(MeasurementFormatting.format(attempt.primaryMetric.value, MeasurementQuantity.SHORT_LENGTH_CM, unitSystem, locale, 2))
                    }
                }
                if (onAttemptSelected == null && expanded.value) {
                    ResultContent(model = attempt, modifier = Modifier.fillMaxWidth(), scrollable = false, showNotes = false)
                }
            }
            ResultNotesSection(
                note = model.note,
                onNoteChange = onNoteChange,
                enabled = noteEnabled && saveState != BilateralSaveState.Saving && saveState != BilateralSaveState.Saved,
                onEdit = onNoteEdit,
                onDelete = onNoteDelete,
            )
            if (saveState != null) {
                when (saveState) {
                    BilateralSaveState.Saving -> Text(stringResource(R.string.common_saving))
                    BilateralSaveState.Saved -> Text(stringResource(R.string.common_saved))
                    BilateralSaveState.Error -> InfoBanner(stringResource(R.string.result_save_error), stringResource(R.string.bilateral_save_error), warning = true)
                    BilateralSaveState.Idle -> Unit
                }
                Button(
                    onClick = onSave,
                    enabled = saveState == BilateralSaveState.Idle || saveState is BilateralSaveState.Error,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (saveState is BilateralSaveState.Error) R.string.common_retry else R.string.result_save_measurement)) }
                OutlinedButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_discard)) }
            }
        }
    }
}
