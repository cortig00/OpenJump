package com.openjump.app.ui.testing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.R
import com.openjump.app.data.GroupEntity
import com.openjump.app.data.TestingFamily
import com.openjump.app.data.TestingParticipantProgress
import com.openjump.app.data.TestingRepository
import com.openjump.app.data.TestingSessionEntity
import com.openjump.app.data.TestingStatus
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.ProtocolCatalog
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.SetupField
import com.openjump.app.settings.EncoderSettings
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.UnitAwareNumericInputState
import com.openjump.app.ui.components.EmptyState
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.titleResource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TestingViewModel(private val repository: TestingRepository, private val groupsRepository: com.openjump.app.data.GroupRepository) : ViewModel() {
    val sessions: StateFlow<List<TestingSessionEntity>> = repository.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val groups: StateFlow<List<GroupEntity>> = groupsRepository.active().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(
        groupId: Long?, family: TestingFamily, protocolId: String?, exercise: String?, side: String?,
        dropHeightCm: Double?, loadKg: Double?, plateDiameterCm: Double?, target: Int,
        onCreated: (Long) -> Unit,
    ) = viewModelScope.launch {
        val id = repository.create(groupId, family, protocolId, exercise, side, dropHeightCm, loadKg, plateDiameterCm, target)
        onCreated(id)
    }
    suspend fun detail(id: Long) = repository.detail(id)
    suspend fun progress(id: Long) = repository.progress(id)
    fun next(id: Long, onSelected: (Long?) -> Unit) = viewModelScope.launch { onSelected(repository.next(id)?.athleteId) }
    fun back(id: Long, onSelected: (Long?) -> Unit) = viewModelScope.launch { onSelected(repository.back(id)?.athleteId) }
    fun skip(id: Long, athleteId: Long, onSelected: (Long?) -> Unit) = viewModelScope.launch { onSelected(repository.skip(id, athleteId)?.athleteId) }
    fun setCurrent(id: Long, ordinal: Int, onDone: () -> Unit = {}) = viewModelScope.launch { repository.setCurrentParticipant(id, ordinal); onDone() }
    fun complete(id: Long, onDone: () -> Unit = {}) = viewModelScope.launch { repository.complete(id); onDone() }
    fun cancel(id: Long, onDone: () -> Unit = {}) = viewModelScope.launch { repository.cancel(id); onDone() }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                TestingViewModel(app.testingRepository, app.groupRepository)
            }
        }
    }
}

@Composable
fun TestingScreen(
    viewModel: TestingViewModel,
    groupId: Long? = null,
    onBack: () -> Unit,
    onSessionSelected: (Long) -> Unit,
) {
    val sessions by viewModel.sessions.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val defaultPlate = remember { EncoderSettings(context).defaultPlateDiameterCm }
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val shortLengthUnit = MeasurementFormatting.unit(MeasurementQuantity.SHORT_LENGTH_CM, unitSystem)
    val massUnit = MeasurementFormatting.unit(MeasurementQuantity.MASS_KG, unitSystem)
    var selectedGroupId by remember(groupId) { mutableStateOf(groupId) }
    var family by remember { mutableStateOf(TestingFamily.JUMP) }
    var protocolName by remember { mutableStateOf(ProtocolId.CMJ.storageKey) }
    var sideName by remember { mutableStateOf<String?>(null) }
    var dropInput by rememberSaveable(stateSaver = UnitAwareNumericInputState.Saver) {
        mutableStateOf(UnitAwareNumericInputState.initial("", unitSystem))
    }
    var exerciseName by remember { mutableStateOf(EncoderExercise.BENCH_PRESS.name) }
    var loadInput by rememberSaveable(stateSaver = UnitAwareNumericInputState.Saver) {
        mutableStateOf(UnitAwareNumericInputState.initial("", unitSystem))
    }
    val defaultPlateText = MeasurementFormatting.formatInputValue(
        defaultPlate,
        MeasurementQuantity.SHORT_LENGTH_CM,
        unitSystem,
        locale,
    )
    var plateInput by rememberSaveable(stateSaver = UnitAwareNumericInputState.Saver) {
        mutableStateOf(UnitAwareNumericInputState.initial(defaultPlateText, unitSystem, defaultPlate))
    }
    LaunchedEffect(unitSystem) {
        dropInput = UnitAwareNumericInputState.rebase(dropInput, unitSystem, MeasurementQuantity.SHORT_LENGTH_CM, locale)
        loadInput = UnitAwareNumericInputState.rebase(loadInput, unitSystem, MeasurementQuantity.MASS_KG, locale)
        plateInput = UnitAwareNumericInputState.rebase(plateInput, unitSystem, MeasurementQuantity.SHORT_LENGTH_CM, locale)
    }
    val dropText = dropInput.text
    val loadText = loadInput.text
    val plateText = plateInput.text
    var targetText by remember { mutableStateOf("1") }
    val protocol = ProtocolCatalog.find(protocolName)
    val selectedExercise = EncoderExercise.valueOf(exerciseName)
    val target = targetText.toIntOrNull()
    val drop = UnitAwareNumericInputState.canonical(dropInput, MeasurementQuantity.SHORT_LENGTH_CM, locale, unitSystem)
    val load = UnitAwareNumericInputState.canonical(loadInput, MeasurementQuantity.MASS_KG, locale, unitSystem)
    val plate = UnitAwareNumericInputState.canonical(plateInput, MeasurementQuantity.SHORT_LENGTH_CM, locale, unitSystem)
    val dropInvalid = UnitAwareNumericInputState.hasInvalidCurrentValue(dropInput, MeasurementQuantity.SHORT_LENGTH_CM, locale)
    val loadInvalid = UnitAwareNumericInputState.hasInvalidCurrentValue(loadInput, MeasurementQuantity.MASS_KG, locale)
    val plateInvalid = UnitAwareNumericInputState.hasInvalidCurrentValue(plateInput, MeasurementQuantity.SHORT_LENGTH_CM, locale)
    val validConfig = selectedGroupId != null && target != null && target >= 1 && when (family) {
        TestingFamily.JUMP -> protocol != null && ProtocolCatalog.validateSetup(protocol, com.openjump.app.protocol.ProtocolSetup(MeasurementSide.fromStorageKey(sideName), drop)) == null
        TestingFamily.ENCODER -> load != null && load.isFinite() && load >= 0.0 &&
            plate != null && EncoderSettings.isValidPlateDiameterCm(plate)
    }
    Scaffold(topBar = {
        OpenJumpTopAppBar(
            title = stringResource(R.string.testing_title),
            onNavigationClick = onBack,
            navigationContentDescription = stringResource(R.string.common_back),
            iconBadge = R.drawable.ic_testing,
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.screenHorizontal).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(R.string.testing_group), style = OpenJumpTypes.SectionTitle)
            if (groups.isEmpty()) Text(stringResource(R.string.testing_no_groups))
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                items(groups, key = { it.id }) { group ->
                    FilterChip(selectedGroupId == group.id, { selectedGroupId = group.id }, label = { Text(group.name) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilterChip(
                    selected = family == TestingFamily.JUMP,
                    onClick = { family = TestingFamily.JUMP },
                    label = { Text(stringResource(R.string.testing_jump)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                )
                FilterChip(
                    selected = family == TestingFamily.ENCODER,
                    onClick = { family = TestingFamily.ENCODER },
                    label = {
                        Text("${stringResource(R.string.testing_encoder)} · ${stringResource(R.string.labs_experimental)}")
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                )
            }
            if (family == TestingFamily.JUMP) {
                Text(stringResource(R.string.testing_protocol), style = OpenJumpTypes.SectionTitle)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    items(ProtocolCatalog.available.filterNot { it.id == ProtocolId.ASYMMETRY }, key = { it.id.storageKey }) { definition ->
                        FilterChip(protocolName == definition.id.storageKey, { protocolName = definition.id.storageKey }, label = { Text(stringResource(definition.id.titleResource())) })
                    }
                }
                if (protocol?.requiredSetup?.contains(SetupField.SIDE) == true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        FilterChip(sideName == MeasurementSide.LEFT.storageKey, { sideName = MeasurementSide.LEFT.storageKey }, label = { Text(stringResource(R.string.protocol_side_left)) })
                        FilterChip(sideName == MeasurementSide.RIGHT.storageKey, { sideName = MeasurementSide.RIGHT.storageKey }, label = { Text(stringResource(R.string.protocol_side_right)) })
                    }
                }
                if (protocol?.requiredSetup?.contains(SetupField.DROP_HEIGHT_CM) == true) {
                    OutlinedTextField(
                        dropText,
                        { value -> dropInput = UnitAwareNumericInputState.edited(value, unitSystem, MeasurementQuantity.SHORT_LENGTH_CM, locale) },
                        Modifier.fillMaxWidth(),
                        label = { Text("${stringResource(R.string.protocol_drop_height)} (${shortLengthUnit.symbol})") },
                        singleLine = true,
                        isError = dropInput.requiresReview || dropInvalid || dropText.isNotBlank() && (drop == null || drop <= 0.0),
                        supportingText = when {
                            dropInput.requiresReview -> { { Text(stringResource(R.string.unit_input_review_required)) } }
                            dropInvalid -> { { Text(stringResource(R.string.unit_input_invalid)) } }
                            else -> null
                        },
                    )
                }
            } else {
                Text(stringResource(R.string.testing_exercise), style = OpenJumpTypes.SectionTitle)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    items(EncoderExercise.entries, key = { it.name }) { exercise ->
                        FilterChip(exerciseName == exercise.name, { exerciseName = exercise.name }, label = { Text(stringResource(exercise.titleResource())) })
                    }
                }
                OutlinedTextField(
                    loadText,
                    { value -> loadInput = UnitAwareNumericInputState.edited(value, unitSystem, MeasurementQuantity.MASS_KG, locale) },
                    Modifier.fillMaxWidth(),
                    label = { Text("${stringResource(R.string.encoder_total_load)} (${massUnit.symbol})") },
                    singleLine = true,
                    isError = loadInput.requiresReview || loadInvalid || loadText.isNotBlank() && (load == null || load < 0.0),
                    supportingText = when {
                        loadInput.requiresReview -> { { Text(stringResource(R.string.unit_input_review_required)) } }
                        loadInvalid -> { { Text(stringResource(R.string.unit_input_invalid)) } }
                        else -> null
                    },
                )
                OutlinedTextField(
                    plateText,
                    { value -> plateInput = UnitAwareNumericInputState.edited(value, unitSystem, MeasurementQuantity.SHORT_LENGTH_CM, locale) },
                    Modifier.fillMaxWidth(),
                    label = { Text("${stringResource(R.string.settings_plate_label)} (${shortLengthUnit.symbol})") },
                    singleLine = true,
                    isError = plateInput.requiresReview || plateInvalid || plateText.isNotBlank() && (plate == null || !EncoderSettings.isValidPlateDiameterCm(plate)),
                    supportingText = when {
                        plateInput.requiresReview -> { { Text(stringResource(R.string.unit_input_review_required)) } }
                        plateInvalid -> { { Text(stringResource(R.string.unit_input_invalid)) } }
                        else -> null
                    },
                )
            }
            OutlinedTextField(targetText, { targetText = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.testing_target_attempts)) }, singleLine = true)
            Button(onClick = {
                viewModel.create(selectedGroupId, family, protocolName.takeIf { family == TestingFamily.JUMP }, exerciseName.takeIf { family == TestingFamily.ENCODER }, sideName.takeIf { family == TestingFamily.JUMP }, drop.takeIf { family == TestingFamily.JUMP }, load.takeIf { family == TestingFamily.ENCODER }, plate.takeIf { family == TestingFamily.ENCODER }, target ?: 0, onSessionSelected)
            }, enabled = validConfig, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.testing_create)) }
            Text(stringResource(R.string.testing_recent), style = OpenJumpTypes.SectionTitle)
            if (sessions.isEmpty()) EmptyState(stringResource(R.string.testing_title), stringResource(R.string.testing_empty), Modifier.fillMaxWidth())
            else Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                sessions.forEach { session ->
                    val encoderSession = session.family == TestingFamily.ENCODER.name
                    ListItem(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onSessionSelected(session.id)
                        },
                        headlineContent = { Text(session.groupNameSnapshot ?: stringResource(R.string.testing_group)) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    if (encoderSession) stringResource(R.string.testing_encoder) else stringResource(R.string.testing_jump),
                                    stringResource(R.string.labs_experimental).takeIf { encoderSession },
                                    testingStatusLabel(session.status),
                                ).joinToString(" · "),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun testingStatusLabel(status: String): String = when (status) {
    TestingStatus.ACTIVE.name -> stringResource(R.string.testing_status_active)
    TestingStatus.COMPLETED.name -> stringResource(R.string.testing_status_completed)
    TestingStatus.CANCELLED.name -> stringResource(R.string.testing_status_cancelled)
    else -> stringResource(R.string.common_value_unavailable)
}

@Composable
fun TestingRunnerScreen(
    sessionId: Long,
    viewModel: TestingViewModel,
    onBack: () -> Unit,
    onMeasure: (TestingSessionEntity, TestingParticipantProgress, Set<Long>) -> Unit,
) {
    var detail by remember(sessionId) { mutableStateOf<com.openjump.app.data.TestingSessionWithParticipants?>(null) }
    var progress by remember(sessionId) { mutableStateOf<List<TestingParticipantProgress>>(emptyList()) }
    var refresh by remember(sessionId) { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sessionId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(sessionId, refresh) {
        detail = viewModel.detail(sessionId)
        progress = viewModel.progress(sessionId)
    }
    val session = detail?.session
    if (session == null) { Text(stringResource(R.string.error_invalid_measurement), Modifier.padding(Spacing.screenHorizontal)); return }
    val current = progress.firstOrNull { it.participant.ordinal == session.currentOrdinal } ?: progress.firstOrNull()
    val isActive = session.status == TestingStatus.ACTIVE.name
    val nextEnabled = isActive && current != null && progress.any {
        it.participant.ordinal > current.participant.ordinal && !it.isOmitted && !it.isComplete
    }
    val backEnabled = isActive && current != null && progress.any {
        it.participant.ordinal < current.participant.ordinal
    }
    Scaffold(topBar = { OpenJumpTopAppBar(session.groupNameSnapshot ?: stringResource(R.string.testing_title), onBack, stringResource(R.string.common_back)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(current?.let { stringResource(R.string.testing_current, it.athleteName) } ?: stringResource(R.string.testing_no_current), style = OpenJumpTypes.ScreenTitle)
            Text(stringResource(R.string.testing_runner_progress, current?.completedAttempts ?: 0, session.targetAttempts))
            Text(testingStatusLabel(session.status))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                items(progress, key = { it.participant.athleteId }) { item ->
                    val label = when { item.isOmitted -> stringResource(R.string.testing_omitted); item.isComplete -> stringResource(R.string.testing_done); else -> stringResource(R.string.testing_pending) }
                    ListItem(modifier = Modifier.fillMaxWidth().clickable(enabled = isActive) { viewModel.setCurrent(sessionId, item.participant.ordinal) { refresh++ } }, headlineContent = { Text(item.athleteName) }, supportingContent = { Text("${item.completedAttempts}/${item.targetAttempts} · $label") })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedButton(onClick = { viewModel.back(sessionId) { refresh++ } }, enabled = backEnabled) { Text(stringResource(R.string.testing_previous)) }
                OutlinedButton(onClick = { current?.let { viewModel.skip(sessionId, it.participant.athleteId) { refresh++ } } }, enabled = isActive && current != null && !current.isOmitted) { Text(stringResource(R.string.testing_skip)) }
                OutlinedButton(onClick = { viewModel.next(sessionId) { refresh++ } }, enabled = nextEnabled) { Text(stringResource(R.string.testing_next)) }
            }
            Button(onClick = { current?.let { onMeasure(
                    session,
                    it,
                    progress.filter { row -> !row.isOmitted && !row.isComplete }
                        .map { row -> row.participant.athleteId }
                        .toSet(),
                ) } }, enabled = isActive && current != null && !current.isComplete && !current.isOmitted, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.testing_measure)) }
            Button(onClick = { viewModel.complete(sessionId) { onBack() } }, enabled = isActive, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.testing_completed)) }
            TextButton(onClick = { viewModel.cancel(sessionId) { onBack() } }, enabled = isActive, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.testing_cancel)) }
        }
    }
}
