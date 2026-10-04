package com.openjump.app.ui.encoder

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.UnitAwareNumericInputState
import com.openjump.app.ui.components.AthleteSelector
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.titleResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncoderSetupScreen(
    onBack: () -> Unit,
    onHowItWorks: () -> Unit,
    activeAthletes: List<com.openjump.app.data.AthleteEntity> = emptyList(),
    athletesLoading: Boolean = false,
    selectedAthleteId: Long? = null,
    onAthleteSelected: (Long) -> Unit,
    onImport: (Uri, EncoderSetup, Long) -> Unit,
    onRecord: (EncoderSetup, Long) -> Unit,
) {
    var exerciseName by rememberSaveable { mutableStateOf(EncoderExercise.BENCH_PRESS.name) }
    var loadInput by rememberSaveable(stateSaver = UnitAwareNumericInputState.Saver) {
        mutableStateOf(UnitAwareNumericInputState.initial("", com.openjump.app.settings.UnitProfile.METRIC))
    }
    var setupAthleteId by rememberSaveable(selectedAthleteId) { mutableStateOf(selectedAthleteId) }
    // Blocks repeated taps only while this setup destination is actively composed.
    // Returning from Camera/Mark creates a fresh unlocked setup state.
    var startDispatched by remember { mutableStateOf(false) }
    LaunchedEffect(selectedAthleteId, activeAthletes) {
        if (activeAthletes.none { it.id == setupAthleteId }) {
            setupAthleteId = selectedAthleteId?.takeIf { id -> activeAthletes.any { it.id == id } }
        }
    }
    val exercise = EncoderExercise.valueOf(exerciseName)
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val loadQuantity = MeasurementQuantity.MASS_KG
    LaunchedEffect(unitSystem) {
        loadInput = UnitAwareNumericInputState.rebase(loadInput, unitSystem, loadQuantity, locale)
    }
    val loadText = loadInput.text
    val loadUnit = MeasurementFormatting.unit(loadQuantity, unitSystem)
    val load = UnitAwareNumericInputState.canonical(loadInput, loadQuantity, locale, unitSystem)
    val loadInvalid = UnitAwareNumericInputState.hasInvalidCurrentValue(loadInput, loadQuantity, locale)
    val valid = !loadInvalid && load != null && load.isFinite() && load >= 0.0
    val setup = remember(exercise, load) { load?.takeIf { valid }?.let { EncoderSetup(exercise, it) } }
    val athleteSelected = setupAthleteId == selectedAthleteId && activeAthletes.any { it.id == setupAthleteId }
    // Navigation supplies the current encoder/setup NavBackStackEntry as the ViewModelStoreOwner:
    // it survives activity configuration recreation and clears when this destination is popped.
    val importViewModel: EncoderImportHandoffViewModel = viewModel(factory = EncoderImportHandoffViewModel.Factory)
    val handoffState by importViewModel.state.collectAsState()
    val currentOnImport = rememberUpdatedState(onImport)
    var pickedExerciseName by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedLoadKg by rememberSaveable { mutableStateOf<Double?>(null) }
    var pickedAthleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickedRequestId by rememberSaveable { mutableStateOf<Long?>(null) }
    var nextRequestId by rememberSaveable { mutableStateOf(0L) }
    val pickerRequestPending = pickedRequestId != null
    val canStart = setup != null && athleteSelected && !startDispatched && !pickerRequestPending &&
        handoffState is EncoderImportHandoffState.Idle
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val pendingRequestId = pickedRequestId
        val request = if (uri != null && pendingRequestId != null) {
            runCatching {
                EncoderImportRequest(
                    requestId = pendingRequestId,
                    uri = uri.toString(),
                    exerciseName = checkNotNull(pickedExerciseName),
                    loadKg = checkNotNull(pickedLoadKg),
                    athleteId = checkNotNull(pickedAthleteId),
                ).also {
                    EncoderExercise.valueOf(it.exerciseName)
                    require(it.loadKg.isFinite() && it.loadKg >= 0.0 && it.athleteId > 0L)
                }
            }.getOrNull()
        } else null
        // Transfer synchronously before clearing the saveable capture. The production resolver ignores
        // duplicate callbacks with no outstanding picker request and only submits captured valid data.
        val disposition = resolveEncoderPickerCallback(
            pendingRequestId = pendingRequestId,
            uri = uri?.toString(),
            request = request,
            submit = importViewModel::acceptPickerResult,
        )
        when (disposition) {
            EncoderPickerCallbackDisposition.Ignore -> Unit
            EncoderPickerCallbackDisposition.Cancel -> {
                importViewModel.abandonPendingImport(pendingRequestId)
                pickedExerciseName = null
                pickedLoadKg = null
                pickedAthleteId = null
                pickedRequestId = null
                startDispatched = false
            }
            EncoderPickerCallbackDisposition.Accepted,
            EncoderPickerCallbackDisposition.Rejected -> {
                pickedExerciseName = null
                pickedLoadKg = null
                pickedAthleteId = null
                pickedRequestId = null
                startDispatched = checkNotNull(disposition.startDispatchedAfterCallback)
            }
        }
    }
    LaunchedEffect(handoffState) {
        val ready = handoffState as? EncoderImportHandoffState.Ready ?: return@LaunchedEffect
        currentOnImport.value(
            Uri.parse(ready.request.uri),
            EncoderSetup(EncoderExercise.valueOf(ready.request.exerciseName), ready.request.loadKg),
            ready.request.athleteId,
        )
        // AppNav's current callback attaches AppSession synchronously; only then may the lease close.
        importViewModel.acknowledge(ready.request.requestId)
        startDispatched = false
    }

    var showExercisePicker by rememberSaveable { mutableStateOf(false) }
    var showScaleReference by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.encoder_setup_title),
                onNavigationClick = {
                    importViewModel.abandonPendingImport()
                    startDispatched = false
                    onBack()
                },
                navigationContentDescription = stringResource(R.string.common_back),
                actions = {
                    TextButton(onClick = {
                        importViewModel.abandonPendingImport()
                        startDispatched = false
                        onHowItWorks()
                    }) {
                        Text(stringResource(R.string.encoder_how_it_works))
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                    .testTag("encoder-setup-footer"),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Button(
                        onClick = {
                            val importSetup = setup
                            val importAthleteId = setupAthleteId
                            if (!startDispatched && athleteSelected && importSetup != null && importAthleteId != null) {
                                // Save only primitive request inputs so picker return cannot redirect an
                                // import to setup values edited while the external activity was open.
                                nextRequestId += 1L
                                pickedRequestId = nextRequestId
                                pickedExerciseName = importSetup.exercise.name
                                pickedLoadKg = importSetup.loadKg
                                pickedAthleteId = importAthleteId
                                startDispatched = true
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                            }
                        },
                        enabled = canStart,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("encoder-setup-import"),
                    ) { Text(stringResource(R.string.common_import_video)) }
                    TextButton(
                        onClick = {
                            setup?.let { setupValue -> setupAthleteId?.let {
                                startDispatched = true
                                onRecord(setupValue, it)
                            } }
                        },
                        enabled = canStart,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("encoder-setup-record"),
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text(stringResource(R.string.common_record_camera)) }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .testTag("encoder-setup-content")
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(top = Spacing.md, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    stringResource(R.string.encoder_setup_heading),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.encoder_setup_intro),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EncoderInfoNote(
                title = stringResource(R.string.labs_live_warning_title),
                body = stringResource(R.string.labs_live_warning_body),
                warning = true,
            )
            AthleteSelector(
                athletes = activeAthletes,
                selectedAthleteId = setupAthleteId,
                loading = athletesLoading,
                compact = true,
                softSurface = true,
                modifier = Modifier.testTag("encoder-setup-athlete"),
                onAthleteSelected = {
                    setupAthleteId = it
                    onAthleteSelected(it)
                },
            )
            Surface(
                onClick = { showExercisePicker = true },
                modifier = Modifier.fillMaxWidth().testTag("encoder-setup-exercise"),
                shape = ShapeTokens.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier.heightIn(min = 64.dp).padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(R.string.encoder_exercise), style = OpenJumpTypes.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(exercise.titleResource()), style = MaterialTheme.typography.titleMedium)
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextField(
                value = loadText,
                onValueChange = { loadInput = UnitAwareNumericInputState.edited(it, unitSystem, loadQuantity, locale) },
                label = {
                    Text(stringResource(R.string.encoder_total_load))
                },
                trailingIcon = {
                    Text(loadUnit.symbol, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = Spacing.md))
                },
                supportingText = {
                    Text(
                        when {
                            loadInput.requiresReview -> stringResource(R.string.unit_input_review_required)
                            loadInvalid -> stringResource(R.string.unit_input_invalid)
                            else -> stringResource(
                            R.string.encoder_load_help,
                            MeasurementFormatting.format(0.0, loadQuantity, unitSystem, locale),
                        )
                        }
                    )
                },
                isError = loadInput.requiresReview || loadText.isNotBlank() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = ShapeTokens.medium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().testTag("encoder-setup-load"),
            )
            Surface(
                onClick = { showScaleReference = true },
                modifier = Modifier.fillMaxWidth().testTag("encoder-setup-scale-reference"),
                shape = ShapeTokens.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier.heightIn(min = 56.dp).padding(Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_units), contentDescription = null,
                        modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.encoder_scale_reference_title), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    Icon(painterResource(R.drawable.ic_info), contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (showExercisePicker) {
        EncoderExercisePicker(
            exercise = exercise,
            onDismiss = { showExercisePicker = false },
            onSelected = { exerciseName = it.name; showExercisePicker = false },
        )
    }
    if (showScaleReference) {
        AlertDialog(
            onDismissRequest = { showScaleReference = false },
            icon = { Icon(painterResource(R.drawable.ic_units), contentDescription = null) },
            iconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text(stringResource(R.string.encoder_scale_reference_title)) },
            text = { Text(stringResource(R.string.encoder_scale_reference_body)) },
            confirmButton = {
                TextButton(onClick = { showScaleReference = false }) {
                    Text(stringResource(R.string.common_back))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EncoderExercisePicker(
    exercise: EncoderExercise,
    onDismiss: () -> Unit,
    onSelected: (EncoderExercise) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                stringResource(R.string.encoder_exercise),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm)
                    .semantics { heading() },
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(EncoderExercise.entries, key = { it.name }) { option ->
                    ListItem(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .selectable(selected = option == exercise, role = Role.RadioButton,
                                onClick = { onSelected(option) })
                            .testTag("encoder-exercise-${option.name.lowercase()}"),
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        leadingContent = { RadioButton(selected = option == exercise, onClick = null) },
                        headlineContent = { Text(stringResource(option.titleResource())) },
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}
