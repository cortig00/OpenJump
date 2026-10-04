package com.openjump.app.ui.athletes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.R
import com.openjump.app.data.AthletePermanentDeleteResult
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.UnitAwareNumericInputState
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.components.AthleteAvatar
import com.openjump.app.ui.components.AthleteAvatarPickerDialog
import com.openjump.app.ui.components.OpenJumpDateField
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import java.time.LocalDate
import java.time.format.DateTimeParseException

@Composable
fun AthleteEditScreen(
    athleteId: Long,
    personalMode: Boolean = false,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: AthleteProfileViewModel = viewModel(factory = AthleteProfileViewModel.factory(athleteId)),
) {
    val athlete by viewModel.athlete.collectAsState()
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val massUnit = MeasurementFormatting.unit(MeasurementQuantity.MASS_KG, unitSystem)
    val heightUnit = MeasurementFormatting.unit(MeasurementQuantity.SHORT_LENGTH_CM, unitSystem)
    var name by rememberSaveable { mutableStateOf("") }
    var birthDate by rememberSaveable { mutableStateOf("") }
    var sex by rememberSaveable { mutableStateOf("") }
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
    var notes by rememberSaveable { mutableStateOf("") }
    var avatarKey by rememberSaveable { mutableStateOf<String?>(null) }
    var initialName by rememberSaveable { mutableStateOf("") }
    var initialBirthDate by rememberSaveable { mutableStateOf("") }
    var initialSex by rememberSaveable { mutableStateOf("") }
    var initialWeight by rememberSaveable { mutableStateOf("") }
    var initialHeight by rememberSaveable { mutableStateOf("") }
    var initialNotes by rememberSaveable { mutableStateOf("") }
    var initialAvatarKey by rememberSaveable { mutableStateOf<String?>(null) }
    var initializedFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAvatarPicker by rememberSaveable { mutableStateOf(false) }
    var confirmPermanentDelete by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteFeedback by rememberSaveable { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(athlete?.id) {
        athlete?.takeIf { initializedFor != it.id }?.let {
            name = it.displayName
            birthDate = it.birthDate?.let(LocalDate::ofEpochDay)?.toString().orEmpty()
            sex = it.sex.orEmpty()
            weightInput = UnitAwareNumericInputState.initial(
                it.weightKg?.let { value -> MeasurementFormatting.formatInputValue(value, MeasurementQuantity.MASS_KG, unitSystem, locale) }.orEmpty(),
                unitSystem,
                it.weightKg,
            )
            heightInput = UnitAwareNumericInputState.initial(
                it.heightCm?.let { value -> MeasurementFormatting.formatInputValue(value, MeasurementQuantity.SHORT_LENGTH_CM, unitSystem, locale) }.orEmpty(),
                unitSystem,
                it.heightCm,
            )
            notes = it.notes.orEmpty()
            avatarKey = it.avatarKey
            initialName = name
            initialBirthDate = birthDate
            initialSex = sex
            initialWeight = weightInput.text
            initialHeight = heightInput.text
            initialNotes = notes
            initialAvatarKey = avatarKey
            initializedFor = it.id
        }
    }

    val parsedDate = parseEpochDay(birthDate)
    val parsedWeightKg = UnitAwareNumericInputState.canonical(weightInput, MeasurementQuantity.MASS_KG, locale, unitSystem)
    val parsedHeightCm = UnitAwareNumericInputState.canonical(heightInput, MeasurementQuantity.SHORT_LENGTH_CM, locale, unitSystem)
    val dateError = birthDate.isNotBlank() && parsedDate == null
    val weightReview = weightInput.requiresReview
    val heightReview = heightInput.requiresReview
    val weightInvalid = UnitAwareNumericInputState.hasInvalidCurrentValue(weightInput, MeasurementQuantity.MASS_KG, locale)
    val heightInvalid = UnitAwareNumericInputState.hasInvalidCurrentValue(heightInput, MeasurementQuantity.SHORT_LENGTH_CM, locale)
    val weightError = weight.isNotBlank() && (weightInvalid || parsedWeightKg == null || parsedWeightKg <= 0.0)
    val heightError = height.isNotBlank() && (heightInvalid || parsedHeightCm == null || parsedHeightCm <= 0.0)
    val valid = name.isNotBlank() && !dateError && !weightError && !heightError
    val dirty = name != initialName || birthDate != initialBirthDate || sex != initialSex ||
        notes != initialNotes || avatarKey != initialAvatarKey ||
        parsedWeightKg != athlete?.weightKg || parsedHeightCm != athlete?.heightCm ||
        weightInput.requiresReview || heightInput.requiresReview ||
        (UnitAwareNumericInputState.hasInvalidCurrentValue(weightInput, MeasurementQuantity.MASS_KG, locale) ||
            UnitAwareNumericInputState.hasInvalidCurrentValue(heightInput, MeasurementQuantity.SHORT_LENGTH_CM, locale))

    if (showAvatarPicker) {
        AthleteAvatarPickerDialog(
            displayName = name,
            selectedKey = avatarKey,
            onDismiss = { showAvatarPicker = false },
            onConfirm = { avatarKey = it; showAvatarPicker = false },
        )
    }

    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.athlete_edit_title),
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.common_back),
                actions = {
                    TextButton(
                        onClick = {
                            saving = true
                            saveError = false
                            viewModel.update(
                                name,
                                parsedDate,
                                sex,
                                notes,
                                avatarKey,
                                parsedWeightKg,
                                parsedHeightCm,
                            ) { success ->
                                saving = false
                                if (success) onBack() else saveError = true
                            }
                        },
                        enabled = dirty && valid && !saving,
                    ) { Text(stringResource(R.string.athlete_save)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (athlete != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    AthleteAvatar(name, avatarKey, size = 96.dp)
                    TextButton(onClick = { showAvatarPicker = true }) {
                        Text(stringResource(R.string.athlete_avatar_change))
                    }
                }
            }
            if (saveError) {
                Text(
                    stringResource(R.string.athlete_save_error),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    style = OpenJumpTypes.Secondary,
                )
            }
            EditField(
                value = name,
                onValueChange = { name = it; saveError = false },
                label = stringResource(R.string.athlete_name),
            )
            OpenJumpDateField(
                date = birthDate.takeIf(String::isNotBlank)?.let(LocalDate::parse),
                onDateChange = {
                    birthDate = it?.toString().orEmpty()
                    saveError = false
                },
                label = stringResource(R.string.athlete_birth_date),
                modifier = Modifier.fillMaxWidth(),
                maxDate = LocalDate.now(),
            )
            EditField(
                value = sex,
                onValueChange = { sex = it; saveError = false },
                label = stringResource(R.string.athlete_sex_category),
            )
            EditField(
                value = weight,
                onValueChange = { weightInput = UnitAwareNumericInputState.edited(it, unitSystem, MeasurementQuantity.MASS_KG, locale); saveError = false },
                label = stringResource(R.string.athlete_weight, massUnit.symbol),
                supportingText = when {
                    weightReview -> stringResource(R.string.unit_input_review_required)
                    weightInvalid -> stringResource(R.string.unit_input_invalid)
                    weightError -> stringResource(R.string.athlete_positive_value_error)
                    else -> null
                },
                isError = weightError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            EditField(
                value = height,
                onValueChange = { heightInput = UnitAwareNumericInputState.edited(it, unitSystem, MeasurementQuantity.SHORT_LENGTH_CM, locale); saveError = false },
                label = stringResource(R.string.athlete_height, heightUnit.symbol),
                supportingText = when {
                    heightReview -> stringResource(R.string.unit_input_review_required)
                    heightInvalid -> stringResource(R.string.unit_input_invalid)
                    heightError -> stringResource(R.string.athlete_positive_value_error)
                    else -> null
                },
                isError = heightError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            EditField(
                value = notes,
                onValueChange = { notes = it; saveError = false },
                label = stringResource(R.string.athlete_notes),
                minLines = 4,
            )
            if (athlete != null) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.athlete_management_actions), style = OpenJumpTypes.SectionTitle)
                    if (!personalMode) {
                        if (athlete!!.archivedAt == null) {
                            TextButton(onClick = viewModel::archive) {
                                Text(stringResource(R.string.athlete_archive))
                            }
                        } else {
                            TextButton(onClick = viewModel::restore) {
                                Text(stringResource(R.string.athlete_restore))
                            }
                        }
                    }
                    TextButton(
                        onClick = { confirmPermanentDelete = true },
                        enabled = !deleting,
                    ) {
                        Text(
                            stringResource(R.string.athlete_delete_permanently),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (confirmPermanentDelete && athlete != null) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmPermanentDelete = false },
            title = { Text(stringResource(R.string.athlete_delete_title, athlete!!.displayName)) },
            text = { Text(stringResource(R.string.athlete_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = true
                        viewModel.permanentlyDelete { result ->
                            deleting = false
                            confirmPermanentDelete = false
                            when (result) {
                                AthletePermanentDeleteResult.DELETED -> onDeleted()
                                AthletePermanentDeleteResult.LAST_ACTIVE_ATHLETE ->
                                    deleteFeedback = R.string.athlete_delete_last_blocked
                                AthletePermanentDeleteResult.NOT_FOUND ->
                                    deleteFeedback = R.string.athlete_delete_error
                            }
                        }
                    },
                    enabled = !deleting,
                ) {
                    Text(
                        stringResource(R.string.athlete_delete_permanently),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmPermanentDelete = false },
                    enabled = !deleting,
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    deleteFeedback?.let { message ->
        AlertDialog(
            onDismissRequest = { deleteFeedback = null },
            title = { Text(stringResource(R.string.athlete_delete_permanently)) },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(onClick = { deleteFeedback = null }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }
}

@Composable
private fun EditField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        minLines = minLines,
        singleLine = minLines == 1,
        keyboardOptions = keyboardOptions,
        shape = ShapeTokens.medium,
        colors = TextFieldDefaults.colors(
            unfocusedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    )
}

internal fun hasAthleteEdits(
    name: String,
    birthDate: String,
    sex: String,
    weight: String,
    height: String,
    notes: String,
    avatarKey: String?,
    initialName: String,
    initialBirthDate: String,
    initialSex: String,
    initialWeight: String,
    initialHeight: String,
    initialNotes: String,
    initialAvatarKey: String?,
): Boolean = name != initialName || birthDate != initialBirthDate || sex != initialSex ||
    weight != initialWeight || height != initialHeight || notes != initialNotes || avatarKey != initialAvatarKey

internal fun parseEpochDay(value: String): Long? = if (value.isBlank()) null else try {
    LocalDate.parse(value).toEpochDay()
} catch (_: DateTimeParseException) { null }
