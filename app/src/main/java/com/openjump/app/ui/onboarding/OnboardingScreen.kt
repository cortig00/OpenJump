package com.openjump.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openjump.app.OpenJumpApp
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.settings.DisplayPreferences
import com.openjump.app.settings.ExperienceMode
import com.openjump.app.settings.ThemeMode
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.components.AthleteAvatar
import com.openjump.app.ui.components.AthleteAvatarPickerDialog
import com.openjump.app.ui.components.AthleteSelector
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import androidx.compose.material3.Surface
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import kotlinx.coroutines.launch

/** First-run and replay onboarding destinations. */
enum class OnboardingExitDestination { HOME, JUMPS, ENCODER }

private enum class OnboardingStep(val position: Int) {
    WELCOME(1), MODE(2), ATHLETE(3), BASICS(4), FINISH(5);

    fun previous(): OnboardingStep = when (this) {
        WELCOME -> WELCOME
        MODE -> WELCOME
        ATHLETE -> MODE
        BASICS -> ATHLETE
        FINISH -> BASICS
    }
}

/**
 * Standalone onboarding UI. Callers own navigation; all selections are persisted as made,
 * while athlete changes are saved and selected before this flow can advance.
 */
@Composable
fun OnboardingScreen(
    app: OpenJumpApp,
    activeAthletes: List<AthleteEntity>,
    athletesLoading: Boolean,
    selectedAthleteId: Long?,
    onExit: (OnboardingExitDestination) -> Unit,
    onSkip: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    // Never restore a half-finished wizard after Activity/process recreation; persisted choices remain.
    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }
    var athleteName by remember { mutableStateOf("") }
    var avatarKey by remember { mutableStateOf<String?>(null) }
    var targetAthleteId by remember { mutableStateOf<Long?>(null) }
    var initializedProfile by remember { mutableStateOf(false) }
    var createdAthleteId by remember { mutableStateOf<Long?>(null) }
    var showAvatarPicker by remember { mutableStateOf(false) }
    var savingAthlete by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }

    val mode by app.experienceModeStore.mode.collectAsState()
    val displayPreferences by app.displayPreferencesStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val stepScroll = key(step) { rememberScrollState() }
    LaunchedEffect(step, athletesLoading, activeAthletes, selectedAthleteId) {
        if (step == OnboardingStep.ATHLETE && !athletesLoading && !initializedProfile) {
            val target = activeAthletes.firstOrNull { it.id == selectedAthleteId && it.archivedAt == null }
                ?: activeAthletes.singleOrNull()
            targetAthleteId = target?.id
            athleteName = target?.displayName.orEmpty()
            avatarKey = target?.avatarKey
            initializedProfile = true
        }
    }

    fun back() {
        if (step == OnboardingStep.WELCOME) onBack?.invoke() else step = step.previous()
    }

    BackHandler(enabled = step != OnboardingStep.WELCOME || onBack != null) { back() }

    fun selectProfile(athlete: AthleteEntity) {
        targetAthleteId = athlete.id
        athleteName = athlete.displayName
        avatarKey = athlete.avatarKey
        createdAthleteId = null
        profileError = false
        nameError = false
    }

    fun saveAthleteAndContinue() {
        val cleanName = athleteName.trim()
        if (cleanName.isEmpty()) {
            nameError = true
            return
        }
        savingAthlete = true
        profileError = false
        nameError = false
        scope.launch {
            try {
                val repository = app.athleteRepository
                val priorId = targetAthleteId ?: createdAthleteId
                val athleteId = if (priorId != null) {
                    val existing = repository.activeById(priorId)
                        ?: throw IllegalStateException("The selected athlete is no longer active.")
                    if (!repository.update(existing.copy(displayName = cleanName, avatarKey = avatarKey))) {
                        throw IllegalStateException("The athlete profile could not be updated.")
                    }
                    existing.id
                } else {
                    if (athletesLoading || activeAthletes.isNotEmpty()) {
                        throw IllegalStateException("Select an active athlete before continuing.")
                    }
                    val newId = repository.create(displayName = cleanName)
                    createdAthleteId = newId
                    val created = repository.activeById(newId)
                        ?: throw IllegalStateException("The new athlete could not be loaded.")
                    if (created.avatarKey != avatarKey &&
                        !repository.update(created.copy(displayName = cleanName, avatarKey = avatarKey))
                    ) {
                        throw IllegalStateException("The athlete profile could not be updated.")
                    }
                    newId
                }
                if (!app.selectedAthleteStore.select(repository, athleteId)) {
                    throw IllegalStateException("The athlete could not be selected.")
                }
                targetAthleteId = athleteId
                createdAthleteId = null
                step = OnboardingStep.BASICS
            } catch (_: Exception) {
                profileError = true
            } finally {
                savingAthlete = false
            }
        }
    }

    if (showAvatarPicker) {
        AthleteAvatarPickerDialog(
            displayName = athleteName,
            selectedKey = avatarKey,
            onDismiss = { showAvatarPicker = false },
            onConfirm = {
                avatarKey = it
                showAvatarPicker = false
            },
        )
    }

    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.app_name),
                showBrandLogo = true,
                onNavigationClick = if (step == OnboardingStep.WELCOME && onBack != null) ::back else null,
                navigationContentDescription = stringResource(R.string.common_back),
                actions = {
                    if (step != OnboardingStep.FINISH) {
                        TextButton(
                            onClick = onSkip,
                            enabled = !athletesLoading && !savingAthlete,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding-skip"),
                        ) { Text(stringResource(R.string.onboarding_skip)) }
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
        ) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp)
                        .verticalScroll(stepScroll)
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            stringResource(R.string.onboarding_step, step.position, OnboardingStep.entries.size),
                            style = OpenJumpTypes.Label,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            repeat(OnboardingStep.entries.size) { index ->
                                Surface(
                                    modifier = Modifier.weight(1f).height(4.dp),
                                    shape = ShapeTokens.full,
                                    color = if (index < step.position) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {}
                            }
                        }
                    }
                    when (step) {
                        OnboardingStep.WELCOME -> WelcomeStep()
                        OnboardingStep.MODE -> ModeStep(
                            selected = mode,
                            onSelected = app.experienceModeStore::set,
                        )
                        OnboardingStep.ATHLETE -> AthleteStep(
                            athletes = activeAthletes,
                            athletesLoading = athletesLoading,
                            selectedAthleteId = targetAthleteId ?: selectedAthleteId,
                            name = athleteName,
                            avatarKey = avatarKey,
                            nameError = nameError,
                            profileError = profileError,
                            saving = savingAthlete,
                            onAthleteSelected = { id -> activeAthletes.firstOrNull { it.id == id }?.let(::selectProfile) },
                            onNameChanged = { athleteName = it; nameError = false; profileError = false },
                            onChooseAvatar = { showAvatarPicker = true },
                        )
                        OnboardingStep.BASICS -> BasicsStep(
                            preferences = displayPreferences,
                            onUnitsSelected = app.displayPreferencesStore::setUnitProfile,
                            onThemeSelected = app.displayPreferencesStore::setThemeMode,
                        )
                        OnboardingStep.FINISH -> FinishStep(
                            onJumpsSelected = {
                                app.onboardingPreferencesStore.completeOnboarding()
                                onExit(OnboardingExitDestination.JUMPS)
                            },
                            onEncoderSelected = {
                                app.onboardingPreferencesStore.completeOnboarding()
                                onExit(OnboardingExitDestination.ENCODER)
                            },
                        )
                    }
                }
            }

            if (!imeVisible) Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (step != OnboardingStep.WELCOME) {
                        TextButton(
                            onClick = ::back,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding-previous"),
                        ) { Text(stringResource(R.string.common_back)) }
                    }
                    Button(
                        onClick = {
                            when (step) {
                                OnboardingStep.WELCOME -> step = OnboardingStep.MODE
                                OnboardingStep.MODE -> step = OnboardingStep.ATHLETE
                                OnboardingStep.ATHLETE -> saveAthleteAndContinue()
                                OnboardingStep.BASICS -> step = OnboardingStep.FINISH
                                OnboardingStep.FINISH -> {
                                    app.onboardingPreferencesStore.completeOnboarding()
                                    onExit(OnboardingExitDestination.HOME)
                                }
                            }
                        },
                        enabled = step != OnboardingStep.ATHLETE || !athletesLoading && !savingAthlete,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("onboarding-next"),
                    ) {
                        Text(
                            when {
                                step == OnboardingStep.ATHLETE && savingAthlete -> stringResource(R.string.onboarding_saving)
                                step == OnboardingStep.ATHLETE -> stringResource(R.string.onboarding_save_continue)
                                step == OnboardingStep.WELCOME -> stringResource(R.string.onboarding_finish_start)
                                else -> stringResource(R.string.onboarding_next)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(stringResource(R.string.onboarding_welcome_title), style = OpenJumpTypes.ScreenTitle)
        Text(stringResource(R.string.onboarding_welcome_body), style = OpenJumpTypes.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OnboardingIllustration(R.drawable.onboarding_welcome_activities, 240.dp, 200.dp)
    }
}

@Composable
private fun ModeStep(selected: ExperienceMode, onSelected: (ExperienceMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(stringResource(R.string.onboarding_mode_prompt), style = OpenJumpTypes.ScreenTitle)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ModeOption(ExperienceMode.PERSONAL, selected, R.string.mode_personal, R.string.onboarding_mode_personal_body, onSelected)
            ModeOption(ExperienceMode.COACH, selected, R.string.mode_coach, R.string.onboarding_mode_coach_body, onSelected)
        }
    }
}

@Composable
private fun ModeOption(
    value: ExperienceMode,
    selected: ExperienceMode,
    titleResource: Int,
    bodyResource: Int,
    onSelected: (ExperienceMode) -> Unit,
) {
    ChoiceRow(
        title = stringResource(titleResource),
        description = stringResource(bodyResource),
        selected = value == selected,
        onClick = { onSelected(value) },
        icon = when (value) {
            ExperienceMode.PERSONAL -> R.drawable.ic_home
            ExperienceMode.COACH -> R.drawable.ic_group
        },
    )
}

@Composable
private fun AthleteStep(
    athletes: List<AthleteEntity>,
    athletesLoading: Boolean,
    selectedAthleteId: Long?,
    name: String,
    avatarKey: String?,
    nameError: Boolean,
    profileError: Boolean,
    saving: Boolean,
    onAthleteSelected: (Long) -> Unit,
    onNameChanged: (String) -> Unit,
    onChooseAvatar: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(stringResource(R.string.onboarding_athlete_title), style = OpenJumpTypes.ScreenTitle)
        Text(stringResource(R.string.onboarding_athlete_body), style = OpenJumpTypes.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (athletesLoading) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.athlete_selector_loading), style = OpenJumpTypes.Secondary)
            }
        } else if (athletes.count { it.archivedAt == null } > 1) {
            AthleteSelector(
                athletes = athletes,
                selectedAthleteId = selectedAthleteId,
                onAthleteSelected = onAthleteSelected,
                loading = false,
            )
        }
        OutlinedTextField(
            value = name,
            onValueChange = onNameChanged,
            modifier = Modifier.fillMaxWidth().testTag("onboarding-athlete-name"),
            enabled = !saving && !athletesLoading,
            singleLine = true,
            label = { Text(stringResource(R.string.athlete_name)) },
            isError = nameError,
            supportingText = if (nameError) {
                { Text(stringResource(R.string.onboarding_name_required)) }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AthleteAvatar(name, avatarKey, size = 72.dp)
            OutlinedButton(
                onClick = onChooseAvatar,
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !saving,
            ) { Text(stringResource(R.string.athlete_avatar_change)) }
        }
        if (!athletesLoading) {
            OnboardingIllustration(R.drawable.onboarding_athlete, 168.dp, 144.dp)
        }
        if (profileError) {
            Text(
                stringResource(R.string.onboarding_profile_error),
                color = MaterialTheme.colorScheme.error,
                style = OpenJumpTypes.Secondary,
            )
        }
    }
}

@Composable
private fun BasicsStep(
    preferences: DisplayPreferences,
    onUnitsSelected: (UnitProfile) -> Unit,
    onThemeSelected: (ThemeMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(stringResource(R.string.onboarding_basics_title), style = OpenJumpTypes.ScreenTitle)
        Text(stringResource(R.string.onboarding_basics_body), style = OpenJumpTypes.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.onboarding_units_label), style = OpenJumpTypes.SectionTitle)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ChoiceRow(
                title = stringResource(R.string.settings_units_metric),
                description = stringResource(R.string.onboarding_units_metric_description),
                selected = preferences.unitProfile == UnitProfile.METRIC,
                onClick = { onUnitsSelected(UnitProfile.METRIC) },
            )
            ChoiceRow(
                title = stringResource(R.string.settings_units_us),
                description = stringResource(R.string.onboarding_units_us_description),
                selected = preferences.unitProfile == UnitProfile.UNITED_STATES,
                onClick = { onUnitsSelected(UnitProfile.UNITED_STATES) },
            )
        }
        Text(stringResource(R.string.onboarding_theme_label), style = OpenJumpTypes.SectionTitle)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ThemeMode.entries.forEach { theme ->
                val title = when (theme) {
                    ThemeMode.SYSTEM -> R.string.settings_theme_system
                    ThemeMode.LIGHT -> R.string.settings_theme_light
                    ThemeMode.DARK -> R.string.settings_theme_dark
                }
                ChoiceRow(
                    title = stringResource(title),
                    description = null,
                    selected = preferences.themeMode == theme,
                    onClick = { onThemeSelected(theme) },
                )
            }
        }
    }
}

@Composable
private fun FinishStep(onJumpsSelected: () -> Unit, onEncoderSelected: () -> Unit) {
    // Keep both destinations visible above the fixed footer on shorter phones.
    val compact = LocalConfiguration.current.screenHeightDp < 700
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(stringResource(R.string.onboarding_finish_title), style = OpenJumpTypes.ScreenTitle)
        Text(stringResource(R.string.onboarding_finish_body), style = OpenJumpTypes.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OnboardingIllustration(R.drawable.onboarding_finish, if (compact) 152.dp else 208.dp, if (compact) 128.dp else 176.dp)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(
                onClick = onJumpsSelected,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("onboarding-jumps"),
            ) {
                Icon(painterResource(R.drawable.ic_jumps), contentDescription = null)
                Text(stringResource(R.string.home_jumps_title), modifier = Modifier.padding(start = Spacing.sm))
            }
            OutlinedButton(
                onClick = onEncoderSelected,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("onboarding-encoder"),
            ) {
                Icon(painterResource(R.drawable.ic_encoder), contentDescription = null)
                Text(stringResource(R.string.home_encoder_title), modifier = Modifier.padding(start = Spacing.sm))
            }
        }
    }
}

@Composable
private fun OnboardingIllustration(@DrawableRes drawable: Int, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(width, height),
            color = OpenJumpTheme.colors.onboardingIllustrationSurface,
            shape = ShapeTokens.large,
        ) {
            Image(
                painter = painterResource(drawable),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(Spacing.xs),
            )
        }
    }
}

@Composable
private fun ChoiceRow(title: String, description: String?, selected: Boolean, onClick: () -> Unit, @DrawableRes icon: Int? = null) {
    val selectionDescription = stringResource(if (selected) R.string.settings_units_selected else R.string.settings_units_not_selected)
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = if (description == null) 56.dp else 72.dp).selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onClick,
        ).semantics { stateDescription = selectionDescription },
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = ShapeTokens.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = ShapeTokens.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(Spacing.sm),
                    )
                }
            } else RadioButton(selected = selected, onClick = null)
            Column(
                modifier = Modifier.weight(1f).padding(start = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                description?.let {
                    Text(it, style = OpenJumpTypes.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (icon != null) RadioButton(selected = selected, onClick = null)
        }
    }
}
