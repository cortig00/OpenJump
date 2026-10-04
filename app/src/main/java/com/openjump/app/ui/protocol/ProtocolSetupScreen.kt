package com.openjump.app.ui.protocol

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.ProtocolAvailability
import com.openjump.app.protocol.ProtocolCatalog
import com.openjump.app.protocol.ProtocolDefinition
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolSetup
import com.openjump.app.protocol.SetupField
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.UnitAwareNumericInputState
import com.openjump.app.ui.components.AthleteSelector
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.components.InfoBanner
import com.openjump.app.ui.components.JumpFlowProgress
import com.openjump.app.ui.components.JumpFlowStep
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.titleResource
import com.openjump.app.ui.descriptionResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolSetupScreen(
    definition: ProtocolDefinition,
    onBack: () -> Unit,
    activeAthletes: List<com.openjump.app.data.AthleteEntity> = emptyList(),
    athletesLoading: Boolean = false,
    selectedAthleteId: Long? = null,
    onAthleteSelected: (Long) -> Unit,
    onImport: (Uri, ProtocolSetup, Long, Int) -> Unit,
    onRecord: (ProtocolSetup, Long, Int) -> Unit,
) {
    var sideName by rememberSaveable { mutableStateOf<String?>(null) }
    var dropHeightInput by rememberSaveable(stateSaver = UnitAwareNumericInputState.Saver) {
        mutableStateOf(UnitAwareNumericInputState.initial("", com.openjump.app.settings.UnitProfile.METRIC))
    }
    var setupAthleteId by rememberSaveable(selectedAthleteId) { mutableStateOf(selectedAthleteId) }
    var targetAttempts by rememberSaveable { mutableStateOf(1) }
    var showFullGuide by rememberSaveable { mutableStateOf(false) }
    val guidance = remember(definition.id) { ProtocolGuidanceCatalog.forProtocol(definition.id) }
    // Blocks repeated taps only while this setup destination is actively composed.
    // Returning from Camera/Mark creates a fresh unlocked setup state.
    var startDispatched by remember { mutableStateOf(false) }
    LaunchedEffect(selectedAthleteId, activeAthletes) {
        if (activeAthletes.none { it.id == setupAthleteId }) {
            setupAthleteId = selectedAthleteId?.takeIf { id -> activeAthletes.any { it.id == id } }
        }
    }
    val side = MeasurementSide.fromStorageKey(sideName)
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val heightQuantity = MeasurementQuantity.SHORT_LENGTH_CM
    val heightUnit = MeasurementFormatting.unit(heightQuantity, unitSystem)
    LaunchedEffect(unitSystem) {
        dropHeightInput = UnitAwareNumericInputState.rebase(dropHeightInput, unitSystem, heightQuantity, locale)
    }
    val dropHeightText = dropHeightInput.text
    val dropHeight = UnitAwareNumericInputState.canonical(dropHeightInput, heightQuantity, locale, unitSystem)
    val dropHeightInvalid = UnitAwareNumericInputState.hasInvalidCurrentValue(dropHeightInput, heightQuantity, locale)
    val setup = remember(side, dropHeight) {
        ProtocolSetup(side = side, dropHeightCm = dropHeight)
    }
    val setupError = ProtocolCatalog.validateSetup(definition, setup)
    val athleteSelected = setupAthleteId == selectedAthleteId && activeAthletes.any { it.id == setupAthleteId }
    val canStart = setupError == null && athleteSelected && !startDispatched

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        startDispatched = false
        if (uri != null && setupError == null && athleteSelected) {
            onImport(uri, setup, setupAthleteId!!, targetAttempts)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(definition.id.titleResource()),
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.common_back),
            )
        },
        bottomBar = {
            if (definition.availability == ProtocolAvailability.AVAILABLE) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Button(
                        onClick = {
                            setupAthleteId?.let {
                                startDispatched = true
                                onRecord(setup, it, targetAttempts)
                            }
                        },
                        enabled = canStart,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    ) {
                        Text(stringResource(R.string.common_record_camera))
                    }
                    OutlinedButton(
                        onClick = {
                            startDispatched = true
                            pickVideo.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                            )
                        },
                        enabled = canStart,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    ) {
                        Text(stringResource(R.string.common_import_video))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(top = Spacing.md, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            JumpFlowProgress(currentStep = JumpFlowStep.PREPARE)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                AthleteSelector(
                    athletes = activeAthletes,
                    selectedAthleteId = setupAthleteId,
                    loading = athletesLoading,
                    compact = true,
                    onAthleteSelected = {
                        setupAthleteId = it
                        onAthleteSelected(it)
                    },
                )
                if (definition.availability == ProtocolAvailability.AVAILABLE && !athletesLoading && !athleteSelected) {
                    Text(
                        stringResource(
                            if (activeAthletes.isEmpty()) R.string.athlete_selector_empty
                            else R.string.protocol_select_athlete_error,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                stringResource(definition.id.descriptionResource()),
                style = OpenJumpTypes.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            guidance?.let { guide ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        stringResource(R.string.protocol_execution),
                        style = OpenJumpTypes.SectionTitle,
                        fontWeight = FontWeight.SemiBold,
                    )
                    guide.executionCues.forEachIndexed { index, instruction ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                "${index + 1}",
                                color = MaterialTheme.colorScheme.primary,
                                style = OpenJumpTypes.Label,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                stringResource(instruction),
                                style = OpenJumpTypes.Body,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (SetupField.SIDE in definition.requiredSetup) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.protocol_attempt_side), style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        SideButton(
                            text = stringResource(R.string.protocol_side_left),
                            selected = side == MeasurementSide.LEFT,
                            onClick = { sideName = MeasurementSide.LEFT.storageKey },
                            modifier = Modifier.weight(1f),
                        )
                        SideButton(
                            text = stringResource(R.string.protocol_side_right),
                            selected = side == MeasurementSide.RIGHT,
                            onClick = { sideName = MeasurementSide.RIGHT.storageKey },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (definition.id == ProtocolId.UNILATERAL && side == null) {
                        Text(
                            stringResource(R.string.protocol_select_side_error),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (SetupField.DROP_HEIGHT_CM in definition.requiredSetup) {
                OutlinedTextField(
                    value = dropHeightText,
                    onValueChange = { dropHeightInput = UnitAwareNumericInputState.edited(it, unitSystem, heightQuantity, locale) },
                    label = {
                        Text("${stringResource(R.string.protocol_drop_height)} (${heightUnit.symbol})")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = dropHeightInput.requiresReview || dropHeightInvalid || dropHeightText.isNotBlank() &&
                        (dropHeight == null || !dropHeight.isFinite() || dropHeight <= 0.0),
                    supportingText = {
                        when {
                            dropHeightInput.requiresReview -> Text(stringResource(R.string.unit_input_review_required))
                            dropHeightInvalid -> Text(stringResource(R.string.unit_input_invalid))
                            dropHeightText.isBlank() -> Text(
                                stringResource(R.string.protocol_drop_height_required),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            dropHeight == null || !dropHeight.isFinite() || dropHeight <= 0.0 ->
                                Text(stringResource(R.string.protocol_drop_height_error))
                            else -> Text(stringResource(R.string.protocol_drop_height_help))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (definition.id == ProtocolId.HORIZONTAL) {
                InfoBanner(
                    title = stringResource(R.string.protocol_horizontal_position_title),
                    body = stringResource(R.string.protocol_horizontal_position_body),
                )
            }

            if (definition.availability == ProtocolAvailability.AVAILABLE) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        stringResource(if (definition.id == ProtocolId.ASYMMETRY) R.string.bilateral_attempts_per_side else R.string.protocol_series_attempts),
                        style = OpenJumpTypes.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        (1..5).forEach { attempts ->
                            FilterChip(
                                selected = targetAttempts == attempts,
                                onClick = { targetAttempts = attempts },
                                label = {
                                    Text(
                                        attempts.toString(),
                                        fontWeight = if (targetAttempts == attempts) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                modifier = Modifier.weight(1f).heightIn(min = Spacing.xxxl),
                            )
                        }
                    }
                    Text(
                        text = pluralStringResource(
                            if (definition.id == ProtocolId.ASYMMETRY) {
                                R.plurals.camera_bilateral_series_before_recording
                            } else {
                                R.plurals.camera_series_before_recording
                            },
                            targetAttempts,
                            targetAttempts,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                InfoBanner(
                    title = stringResource(R.string.common_coming_soon),
                    body = stringResource(R.string.protocol_unavailable_body),
                )
            }
            guidance?.let { guide ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        stringResource(R.string.protocol_camera_prepare),
                        style = OpenJumpTypes.SectionTitle,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(guide.cameraCue),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        stringResource(R.string.protocol_marking_summary),
                        style = OpenJumpTypes.SectionTitle,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(guide.markingSummary),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { showFullGuide = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.protocol_full_guide))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (showFullGuide) {
        guidance?.let { guide ->
            JumpProtocolGuideSheet(guide) { showFullGuide = false }
        }
    }
}

@Composable
private fun SideButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        FilledTonalButton(onClick = onClick, modifier = modifier.height(52.dp)) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(52.dp)) { Text(text) }
    }
}
