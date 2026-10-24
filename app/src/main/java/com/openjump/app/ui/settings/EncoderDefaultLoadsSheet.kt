package com.openjump.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.settings.EncoderSettings
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.UnitAwareNumericInputState
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.titleResource

@Composable
private fun loadExerciseTitle(exercise: EncoderExercise): String = stringResource(
    if (exercise == EncoderExercise.PULL_UP) R.string.settings_default_load_pull_up else exercise.titleResource(),
)

/** Only one modal window at a time: editing does not stack keyboard exclusions on a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EncoderDefaultLoadsSheet(
    defaultsKg: Map<EncoderExercise, Double>,
    unitProfile: UnitProfile,
    onDismiss: () -> Unit,
    onSave: (EncoderExercise, Double) -> Unit,
    onReset: () -> Unit,
) {
    val locale = currentAppLocale()
    var editingName by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val editing = editingName?.let(EncoderExercise::valueOf)
    when {
        editing != null -> DefaultLoadEditor(
            exercise = editing,
            initialKg = defaultsKg.getValue(editing),
            unitProfile = unitProfile,
            onDismiss = { editingName = null },
            onSave = { value -> onSave(editing, value); editingName = null },
        )
        confirmReset -> AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_default_loads_reset)) },
            text = { Text(stringResource(R.string.settings_default_loads_reset_message)) },
            confirmButton = {
                TextButton(onClick = { onReset(); confirmReset = false }, modifier = Modifier.testTag("default-load-reset-confirm")) {
                    Text(stringResource(R.string.settings_default_loads_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
        else -> ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().testTag("default-loads-sheet")) {
                Text(
                    stringResource(R.string.settings_default_loads_title),
                    modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm).semantics { heading() },
                    style = OpenJumpTypes.SectionTitle,
                )
                Text(
                    stringResource(R.string.settings_default_loads_description),
                    modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.weight(1f, fill = false).testTag("default-loads-list")) {
                    items(EncoderExercise.entries, key = { it.name }) { exercise ->
                        ListItem(
                            headlineContent = { Text(loadExerciseTitle(exercise)) },
                            trailingContent = {
                                Text(
                                    MeasurementFormatting.format(defaultsKg.getValue(exercise), MeasurementQuantity.MASS_KG, unitProfile, locale, decimals = 2),
                                    style = OpenJumpTypes.MetricValue,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .testTag("default-load-row-${exercise.name}")
                                .clickable(role = Role.Button) { editingName = exercise.name },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(
                    onClick = { confirmReset = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("default-loads-reset"),
                ) { Text(stringResource(R.string.settings_default_loads_reset)) }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.common_back))
                }
            }
        }
    }
}

@Composable
private fun DefaultLoadEditor(
    exercise: EncoderExercise,
    initialKg: Double,
    unitProfile: UnitProfile,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
) {
    val locale = currentAppLocale()
    val quantity = MeasurementQuantity.MASS_KG
    var input by rememberSaveable(exercise, initialKg, stateSaver = UnitAwareNumericInputState.Saver) {
        mutableStateOf(UnitAwareNumericInputState.initial(
            MeasurementFormatting.formatValue(initialKg, quantity, unitProfile, locale, decimals = 2),
            unitProfile,
            initialKg,
        ))
    }
    LaunchedEffect(unitProfile) { input = UnitAwareNumericInputState.rebase(input, unitProfile, quantity, locale) }
    val canonicalKg = UnitAwareNumericInputState.canonical(input, quantity, locale, unitProfile)
    val valid = !UnitAwareNumericInputState.hasInvalidCurrentValue(input, quantity, locale) &&
        canonicalKg?.let(EncoderSettings::isValidLoadKg) == true
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("default-load-editor"),
        title = { Text(loadExerciseTitle(exercise)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.settings_default_load_editor_description), style = OpenJumpTypes.Secondary)
                OutlinedTextField(
                    value = input.text,
                    // Never format or convert while typing: retain cursor/text and user's decimal separator.
                    onValueChange = { input = UnitAwareNumericInputState.edited(it, unitProfile, quantity, locale) },
                    label = { Text(stringResource(R.string.settings_default_load_label)) },
                    suffix = { Text(MeasurementFormatting.unit(quantity, unitProfile).symbol) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = input.requiresReview || input.text.isNotBlank() && !valid,
                    supportingText = when {
                        input.requiresReview -> { { Text(stringResource(R.string.unit_input_review_required)) } }
                        input.text.isNotBlank() && !valid -> { { Text(stringResource(R.string.settings_default_load_error)) } }
                        else -> null
                    },
                    modifier = Modifier.fillMaxWidth().testTag("default-load-input"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(requireNotNull(canonicalKg)) },
                enabled = valid,
                modifier = Modifier.testTag("default-load-save"),
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("default-load-cancel")) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
