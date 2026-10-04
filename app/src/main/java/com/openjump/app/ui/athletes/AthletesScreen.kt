package com.openjump.app.ui.athletes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.AthleteRepository
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.UnitAwareNumericInputState
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.components.AthleteAvatar
import com.openjump.app.ui.components.EmptyState
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.Spacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AthletesViewModel(
    private val repository: AthleteRepository,
) : ViewModel() {
    val athletes: StateFlow<List<AthleteEntity>> = repository.all().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )
    fun create(name: String, weightKg: Double?, heightCm: Double?) = viewModelScope.launch {
        repository.create(name, weightKg = weightKg, heightCm = heightCm)
    }

    fun archive(id: Long) = viewModelScope.launch { repository.archive(id) }

    fun restore(id: Long) = viewModelScope.launch { repository.unarchive(id) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                AthletesViewModel(app.athleteRepository)
            }
        }
    }
}

@Composable
fun AthletesScreen(
    viewModel: AthletesViewModel,
    onBack: () -> Unit,
    onCompare: () -> Unit = {},
    onAthleteSelected: (Long) -> Unit = {},
    onAthleteEdit: (Long) -> Unit = {},
) {
    val athletes by viewModel.athletes.collectAsState()
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val massUnit = MeasurementFormatting.unit(MeasurementQuantity.MASS_KG, unitSystem)
    val heightUnit = MeasurementFormatting.unit(MeasurementQuantity.SHORT_LENGTH_CM, unitSystem)
    var showArchived by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var weightInput by rememberSaveable(stateSaver = UnitAwareNumericInputState.Saver) {
        mutableStateOf(UnitAwareNumericInputState.initial("", unitSystem))
    }
    var heightInput by rememberSaveable(stateSaver = UnitAwareNumericInputState.Saver) {
        mutableStateOf(UnitAwareNumericInputState.initial("", unitSystem))
    }
    LaunchedEffect(unitSystem) {
        weightInput = UnitAwareNumericInputState.rebase(weightInput, unitSystem, MeasurementQuantity.MASS_KG, locale)
        heightInput = UnitAwareNumericInputState.rebase(heightInput, unitSystem, MeasurementQuantity.SHORT_LENGTH_CM, locale)
    }
    val weight = weightInput.text
    val height = heightInput.text
    var archiveCandidate by remember { mutableStateOf<AthleteEntity?>(null) }
    val visible = athletes.filter { (it.archivedAt != null) == showArchived }
    val weightKg = UnitAwareNumericInputState.canonical(weightInput, MeasurementQuantity.MASS_KG, locale, unitSystem)
    val heightCm = UnitAwareNumericInputState.canonical(heightInput, MeasurementQuantity.SHORT_LENGTH_CM, locale, unitSystem)
    val weightReview = weightInput.requiresReview
    val heightReview = heightInput.requiresReview
    val weightInvalid = UnitAwareNumericInputState.hasInvalidCurrentValue(weightInput, MeasurementQuantity.MASS_KG, locale)
    val heightInvalid = UnitAwareNumericInputState.hasInvalidCurrentValue(heightInput, MeasurementQuantity.SHORT_LENGTH_CM, locale)
    val weightError = weight.isNotBlank() && (weightInvalid || weightKg == null || weightKg <= 0.0)
    val heightError = height.isNotBlank() && (heightInvalid || heightCm == null || heightCm <= 0.0)
    val canCreate = name.isNotBlank() && !weightError && !heightError

    if (archiveCandidate != null) {
        AlertDialog(
            onDismissRequest = { archiveCandidate = null },
            title = { Text(stringResource(R.string.athlete_archive_title)) },
            text = { Text(stringResource(R.string.athlete_archive_message)) },
            dismissButton = {
                TextButton(onClick = { archiveCandidate = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    archiveCandidate?.let { viewModel.archive(it.id) }
                    archiveCandidate = null
                }) { Text(stringResource(R.string.common_confirm)) }
            },
        )
    }

    Scaffold(topBar = {
        OpenJumpTopAppBar(
            title = stringResource(R.string.athletes_title),
            onNavigationClick = onBack,
            navigationContentDescription = stringResource(R.string.common_back),
            iconBadge = R.drawable.ic_people,
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.athlete_name)) },
            )
            OutlinedTextField(
                value = weight,
                onValueChange = { weightInput = UnitAwareNumericInputState.edited(it, unitSystem, MeasurementQuantity.MASS_KG, locale) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.athlete_weight, massUnit.symbol)) },
                supportingText = when {
                    weightReview -> { { Text(stringResource(R.string.unit_input_review_required)) } }
                    weightInvalid -> { { Text(stringResource(R.string.unit_input_invalid)) } }
                    weightError -> { { Text(stringResource(R.string.athlete_positive_value_error)) } }
                    else -> null
                },
                isError = weightError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                value = height,
                onValueChange = { heightInput = UnitAwareNumericInputState.edited(it, unitSystem, MeasurementQuantity.SHORT_LENGTH_CM, locale) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.athlete_height, heightUnit.symbol)) },
                supportingText = when {
                    heightReview -> { { Text(stringResource(R.string.unit_input_review_required)) } }
                    heightInvalid -> { { Text(stringResource(R.string.unit_input_invalid)) } }
                    heightError -> { { Text(stringResource(R.string.athlete_positive_value_error)) } }
                    else -> null
                },
                isError = heightError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Button(
                onClick = {
                    viewModel.create(name.trim(), weightKg, heightCm)
                    name = ""
                    weightInput = UnitAwareNumericInputState.initial("", unitSystem)
                    heightInput = UnitAwareNumericInputState.initial("", unitSystem)
                },
                enabled = canCreate,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.athlete_add)) }
            OutlinedButton(onClick = onCompare, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.comparison_title))
            }
            TabRow(selectedTabIndex = if (showArchived) 1 else 0) {
                Tab(
                    selected = !showArchived,
                    onClick = { showArchived = false },
                    text = { Text(stringResource(R.string.athlete_active)) },
                )
                Tab(
                    selected = showArchived,
                    onClick = { showArchived = true },
                    text = { Text(stringResource(R.string.athlete_archived)) },
                )
            }
            if (visible.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.athletes_title),
                    body = stringResource(R.string.athlete_empty_body),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items(visible, key = { it.id }) { athlete ->
                        Column(Modifier.fillMaxWidth()) {
                            ListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button) { onAthleteSelected(athlete.id) },
                                headlineContent = { Text(athlete.displayName) },
                                leadingContent = {
                                    AthleteAvatar(athlete.displayName, athlete.avatarKey)
                                },
                            )
                            // Keep actions in the row's available width instead of ListItem's
                            // trailing slot, which overflows on narrow screens/locales.
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            ) {
                                if (showArchived) {
                                    TextButton(
                                        onClick = { viewModel.restore(athlete.id) },
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    ) { Text(stringResource(R.string.athlete_restore)) }
                                } else {
                                    TextButton(
                                        onClick = { onAthleteEdit(athlete.id) },
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    ) { Text(stringResource(R.string.athlete_edit)) }
                                    TextButton(
                                        onClick = { archiveCandidate = athlete },
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    ) { Text(stringResource(R.string.athlete_archive)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
