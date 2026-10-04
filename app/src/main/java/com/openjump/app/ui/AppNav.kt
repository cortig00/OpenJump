package com.openjump.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.openjump.app.OpenJumpApp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.PersonalRecord
import com.openjump.app.data.anthropometricsSnapshotFor
import com.openjump.app.protocol.ProtocolCatalog
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolSetup
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.VideoSource
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.settings.EncoderSettings
import com.openjump.app.settings.ExperienceMode
import com.openjump.app.settings.OnboardingCompletion
import com.openjump.app.ui.athletes.AthletesScreen
import com.openjump.app.ui.athletes.AthletesViewModel
import com.openjump.app.ui.athletes.PersonalRecordsScreen
import com.openjump.app.ui.athletes.ProgressScreen
import com.openjump.app.ui.camera.CameraScreen
import com.openjump.app.ui.components.JumpFlowStep
import com.openjump.app.ui.comparison.ComparisonScreen
import com.openjump.app.ui.encoder.EncoderMethodologyScreen
import com.openjump.app.ui.encoder.EncoderResultScreen
import com.openjump.app.ui.encoder.EncoderSetupScreen
import com.openjump.app.ui.encoder.SavedEncoderResultScreen
import com.openjump.app.ui.history.HistoryScreen
import com.openjump.app.ui.groups.GroupDetailScreen
import com.openjump.app.ui.groups.GroupsScreen
import com.openjump.app.ui.groups.GroupsViewModel
import com.openjump.app.ui.home.HomeScreen
import com.openjump.app.ui.people.TeamHubScreen
import com.openjump.app.ui.jumps.JumpsScreen
import com.openjump.app.ui.onboarding.OnboardingExitDestination
import com.openjump.app.ui.onboarding.OnboardingScreen
import com.openjump.app.ui.protocol.ProtocolSetupScreen
import com.openjump.app.ui.protocol.ProtocolGuidanceCatalog
import com.openjump.app.ui.result.ResultScreen
import com.openjump.app.ui.result.SavedResultScreen
import com.openjump.app.ui.result.SavedMeasurementDispatch
import com.openjump.app.ui.result.SavedBilateralResultRoute
import com.openjump.app.ui.selector.FrameSelectorScreen
import com.openjump.app.ui.settings.AboutScreen
import com.openjump.app.ui.settings.LicenseScreen
import com.openjump.app.ui.settings.PrivacyPolicyScreen
import com.openjump.app.ui.settings.SettingsScreen
import com.openjump.app.ui.settings.UnitPreferencesScreen
import com.openjump.app.ui.testing.TestingRunnerScreen
import com.openjump.app.ui.testing.TestingScreen
import com.openjump.app.ui.testing.TestingViewModel
import com.openjump.app.data.TestingFamily
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.video.AppSession
import com.openjump.app.video.SeriesFeedback

private const val HOME_ROUTE = "home"
private const val ONBOARDING_ROUTE = "onboarding"
private const val HISTORY_ROUTE = "history"
private const val SETTINGS_ROUTE = "settings"
private const val PRIVACY_ROUTE = "privacy"
private const val ABOUT_ROUTE = "settings/about"
private const val LICENSE_ROUTE = "settings/about/license"
private const val UNITS_ROUTE = "settings/units"
private const val PEOPLE_ROUTE = "people"
private const val ATHLETE_RECORDS_ROUTE = "athlete/{athleteId}/records"
private const val ATHLETE_PROGRESS_ROUTE = "athlete/{athleteId}/progress"
private const val JUMPS_ROUTE = "jumps"
private const val ATHLETES_ROUTE = "athletes"
private const val GROUPS_ROUTE = "groups"
private const val TESTING_ROUTE = "testing"
private const val COMPARISON_ROUTE = "comparison"
private const val COMPARISON_RESULTS_ROUTE = "$COMPARISON_ROUTE/results"

internal fun athleteEditRoute(athleteId: Long): String = "athlete/$athleteId/edit"
internal fun comparisonResultsRoute(): String = COMPARISON_RESULTS_ROUTE
internal fun comparisonRoutes(): List<String> = listOf(COMPARISON_ROUTE, COMPARISON_RESULTS_ROUTE)

private data class TopLevelDestination(
    val route: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(HOME_ROUTE, R.string.nav_home, R.drawable.ic_home),
    TopLevelDestination(HISTORY_ROUTE, R.string.nav_history, R.drawable.ic_history),
    TopLevelDestination(PEOPLE_ROUTE, R.string.nav_people_personal, R.drawable.ic_people),
    TopLevelDestination(SETTINGS_ROUTE, R.string.nav_settings, R.drawable.ic_settings),
)

internal fun topLevelRouteOrder(): List<String> = topLevelDestinations.map { it.route }

// Exact settings-section routes. Exact matching only: no prefix that could capture unrelated routes.
private val settingsSectionRoutes = setOf(
    SETTINGS_ROUTE,
    ABOUT_ROUTE,
    PRIVACY_ROUTE,
    LICENSE_ROUTE,
    UNITS_ROUTE,
)

internal fun isSettingsSectionRoute(route: String?): Boolean = route in settingsSectionRoutes

internal fun isBottomBarVisible(route: String?): Boolean =
    route == HOME_ROUTE ||
        route == HISTORY_ROUTE ||
        route == PEOPLE_ROUTE ||
        route == ATHLETE_RECORDS_ROUTE ||
        route == ATHLETE_PROGRESS_ROUTE ||
        route == JUMPS_ROUTE ||
        isSettingsSectionRoute(route)

@StringRes
internal fun peopleLabel(mode: ExperienceMode): Int =
    if (mode == ExperienceMode.PERSONAL) R.string.nav_people_personal else R.string.nav_people_coach

internal fun personalProfileAthleteId(
    activeAthletes: List<AthleteEntity>,
    selectedAthleteId: Long?,
): Long? = activeAthletes.firstOrNull { it.id == selectedAthleteId }?.id

@Composable
fun AppNav() {
    val context = LocalContext.current
    val app = context.applicationContext as OpenJumpApp
    val displayPreferences by app.displayPreferencesStore.state.collectAsState()
    val darkTheme = displayPreferences.themeMode.isDark(isSystemInDarkTheme())

    OpenJumpTheme(darkTheme = darkTheme) {
        CompositionLocalProvider(LocalUnitSystem provides displayPreferences.unitProfile) {
            val navController = rememberNavController()
            OpenJumpNavHost(navController)
        }
    }
}

@Composable
fun OpenJumpNavHost(navController: NavHostController) {
    val context = LocalContext.current
    val encoderSettings = remember(context) { EncoderSettings(context) }
    val app = context.applicationContext as OpenJumpApp
    SideEffect { AppSession.setVideoOwnershipChangedListener(app::requestVideoUriReconciliation) }
    val mode by app.experienceModeStore.mode.collectAsState()
    // Freeze the start destination; completing setup must not recreate the navigation graph.
    val startRoute = remember(app) {
        if (app.onboardingPreferencesStore.completion.value == OnboardingCompletion.PENDING) ONBOARDING_ROUTE
        else HOME_ROUTE
    }
    // Bootstrap independently, then expose null until Room has emitted the active roster.
    val activeAthletesOrNull by produceState<List<AthleteEntity>?>(initialValue = null, key1 = app) {
        var startupGrantSweepCompleted = false
        val bootstrapped = app.athleteRepository.bootstrapIfInstallationEmpty()
        if (bootstrapped != null) {
            // Validates the exact bootstrap ID and preserves any existing valid preference.
            app.selectedAthleteStore.associateBootstrapIfNeeded(app.athleteRepository, bootstrapped.id)
        }
        app.athleteRepository.active().collect { athletes ->
            value = athletes
            if (!startupGrantSweepCompleted) {
                startupGrantSweepCompleted = true
                app.reconcileVideoUriGrants()
            }
        }
    }
    val activeAthletes = activeAthletesOrNull.orEmpty()
    val athletesLoading = activeAthletesOrNull == null
    val selectedAthleteId by app.selectedAthleteStore.selectedAthleteId.collectAsState()
    val navScope = rememberCoroutineScope()
    // A profile edit emits a new roster object but not new membership. Do not race the
    // onboarding update-then-select transaction with an unnecessary selection resolve.
    val activeAthleteIds = activeAthletesOrNull?.map { it.id }
    LaunchedEffect(app, activeAthleteIds) {
        if (activeAthleteIds != null) app.selectedAthleteStore.resolve(app.athleteRepository)
    }
    val encoderDraft by AppSession.encoderDraft.collectAsState()
    val draft by AppSession.draft.collectAsState()
    val videoUri by AppSession.videoUri.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route
    val showNavigationBar = isBottomBarVisible(currentRoute)
    var completedSeriesFeedback by rememberSaveable { mutableStateOf<SeriesFeedback?>(null) }
    var pendingRecords by remember { mutableStateOf<List<PersonalRecord>>(emptyList()) }
    // Monotonic UI timestamp only; not a second video-processing state.
    var recordedVideoStopElapsedMs by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ),
        bottomBar = {
            if (showNavigationBar) {
                OpenJumpNavigationBar(
                    currentDestination = currentDestination,
                    currentRoute = currentRoute,
                    mode = mode,
                    onDestinationSelected = { destination ->
                        navController.navigateTopLevel(destination.route)
                    },
                )
            }
        },
    ) { appPadding ->
        NavHost(
            navController = navController,
            startDestination = startRoute,
            // The root already reserves safeDrawing (including IME). Nested screens
            // must not reserve that same keyboard/navigation space a second time.
            modifier = Modifier.padding(appPadding).consumeWindowInsets(appPadding),
        ) {
            composable(ONBOARDING_ROUTE) {
                // The graph's startRoute is frozen at launch. A replay after skipping in this
                // same process is still a replay, even if the graph originally began here.
                val isReplay = remember { app.onboardingPreferencesStore.completion.value == OnboardingCompletion.COMPLETED }
                val exitOnboarding: (OnboardingExitDestination) -> Unit = { choice ->
                    val destination = when (choice) {
                        OnboardingExitDestination.HOME -> HOME_ROUTE
                        OnboardingExitDestination.JUMPS -> JUMPS_ROUTE
                        OnboardingExitDestination.ENCODER -> "encoder/setup"
                    }
                    navController.navigate(HOME_ROUTE) {
                        // First run replaces setup; replay returns to the root Home rather than
                        // leaving Settings behind it on the Back stack.
                        popUpTo(if (isReplay) HOME_ROUTE else ONBOARDING_ROUTE) { inclusive = !isReplay }
                        launchSingleTop = true
                    }
                    if (destination != HOME_ROUTE) navController.navigate(destination)
                }
                OnboardingScreen(
                    app = app,
                    activeAthletes = activeAthletes,
                    athletesLoading = athletesLoading,
                    selectedAthleteId = selectedAthleteId,
                    onBack = if (isReplay) ({ navController.popBackStack() }) else null,
                    onSkip = {
                        if (isReplay) {
                            // Closing a Settings replay should not change setup or take the user Home.
                            navController.popBackStack()
                        } else {
                            app.onboardingPreferencesStore.completeOnboarding()
                            exitOnboarding(OnboardingExitDestination.HOME)
                        }
                    },
                    onExit = exitOnboarding,
                )
            }

            composable(HOME_ROUTE) {
                HomeScreen(
                    seriesFeedback = completedSeriesFeedback,
                    onSeriesFeedbackConsumed = { completedSeriesFeedback = null },
                    recordFeedback = pendingRecords.takeIf { currentRoute == HOME_ROUTE }.orEmpty(),
                    onRecordFeedbackConsumed = { pendingRecords = emptyList() },
                    onJumpsSelected = { navController.navigate(JUMPS_ROUTE) },
                    onEncoderSelected = { navController.navigate("encoder/setup") },
                    onHistorySelected = { navController.navigateTopLevel(HISTORY_ROUTE) },
                    onMeasurementSelected = { navController.navigate("measurement/$it") },
                    onEncoderSessionSelected = { navController.navigate("encoder/session/$it") },
                )
            }

            composable(JUMPS_ROUTE) {
                JumpsScreen(
                    onBack = { navController.popBackStack() },
                    onProtocolSelected = { navController.navigate("protocol/${it.storageKey}") },
                )
            }

            composable(HISTORY_ROUTE) {
                HistoryScreen(
                    onMeasurementSelected = { navController.navigate("measurement/$it") },
                    onEncoderSessionSelected = { navController.navigate("encoder/session/$it") },
                )
            }

            composable(PEOPLE_ROUTE) {
                val personalAthleteId = personalProfileAthleteId(activeAthletes, selectedAthleteId)
                if (mode == ExperienceMode.PERSONAL && personalAthleteId != null) {
                    com.openjump.app.ui.athletes.AthleteProfileScreen(
                        athleteId = personalAthleteId,
                        personalMode = true,
                        mode = mode,
                        onModeSelected = { selectedMode ->
                            app.experienceModeStore.set(selectedMode)
                            if (selectedMode != ExperienceMode.PERSONAL) navController.navigateTopLevel(PEOPLE_ROUTE)
                        },
                        onBack = { navController.popBackStack() },
                        onEdit = { navController.navigate(athleteEditRoute(personalAthleteId)) },
                        onMeasurementSelected = { navController.navigate("measurement/$it") },
                        onEncoderSelected = { navController.navigate("encoder/session/$it") },
                        onRecordsSelected = { navController.navigate("athlete/$personalAthleteId/records") },
                        onProgressSelected = { navController.navigate("athlete/$personalAthleteId/progress") },
                    )
                } else if (mode == ExperienceMode.PERSONAL) {
                    AthletesScreen(
                        viewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = AthletesViewModel.Factory),
                        onBack = { navController.popBackStack() },
                        onAthleteSelected = { navController.navigate("athlete/$it") },
                        onAthleteEdit = { navController.navigate(athleteEditRoute(it)) },
                        onCompare = { navController.navigate(COMPARISON_ROUTE) },
                    )
                } else {
                    val teamViewModel: com.openjump.app.ui.people.TeamHubViewModel =
                        androidx.lifecycle.viewmodel.compose.viewModel(factory = com.openjump.app.ui.people.TeamHubViewModel.Factory)
                    val teamRecent by teamViewModel.recentActivity.collectAsState()
                    TeamHubScreen(
                        onAthletes = { navController.navigate(ATHLETES_ROUTE) },
                        onGroups = { navController.navigate(GROUPS_ROUTE) },
                        onTesting = { navController.navigate(TESTING_ROUTE) },
                        onComparison = { navController.navigate(COMPARISON_ROUTE) },
                        mode = mode,
                        onModeSelected = app.experienceModeStore::set,
                        recentActivity = teamRecent,
                        onMeasurementSelected = { navController.navigate("measurement/$it") },
                        onEncoderSelected = { navController.navigate("encoder/session/$it") },
                    )
                }
            }

            composable(SETTINGS_ROUTE) {
                SettingsScreen(
                    onPrivacySelected = { navController.navigate(PRIVACY_ROUTE) },
                    onAboutSelected = { navController.navigate(ABOUT_ROUTE) },
                    onUnitsSelected = { navController.navigate(UNITS_ROUTE) },
                    onOnboardingSelected = { navController.navigate(ONBOARDING_ROUTE) },
                )
            }

            composable(PRIVACY_ROUTE) {
                PrivacyPolicyScreen(onBack = { navController.popBackStack() })
            }

            composable(ABOUT_ROUTE) {
                AboutScreen(
                    onBack = { navController.popBackStack() },
                    onPrivacySelected = { navController.navigate(PRIVACY_ROUTE) },
                    onLicenseSelected = { navController.navigate(LICENSE_ROUTE) },
                )
            }

            composable(LICENSE_ROUTE) {
                LicenseScreen(onBack = { navController.popBackStack() })
            }

            composable(UNITS_ROUTE) {
                UnitPreferencesScreen(onBack = { navController.popBackStack() })
            }

            composable("$TESTING_ROUTE?groupId={groupId}") { entry ->
                val groupId = entry.arguments?.getString("groupId")?.toLongOrNull()
                TestingScreen(
                    viewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = TestingViewModel.Factory),
                    groupId = groupId,
                    onBack = { navController.popBackStack() },
                    onSessionSelected = { navController.navigate("$TESTING_ROUTE/$it") },
                )
            }

            composable("$TESTING_ROUTE/{sessionId}") { entry ->
                val sessionId = entry.arguments?.getString("sessionId")?.toLongOrNull()
                if (sessionId == null || sessionId <= 0L) {
                    UnknownMeasurement(onBack = { navController.popBackStack() })
                } else {
                    TestingRunnerScreen(
                        sessionId = sessionId,
                        viewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = TestingViewModel.Factory),
                        onBack = { navController.popBackStack() },
                        onMeasure = { session, participant, rosterIds ->
                            if (session.family == TestingFamily.JUMP.name) {
                                ProtocolCatalog.find(session.protocolId)?.let { definition ->
                                    AppSession.begin(
                                        definition.id,
                                        ProtocolSetup(MeasurementSide.fromStorageKey(session.side), session.dropHeightCm),
                                        participant.participant.athleteId,
                                        session.id,
                                        rosterIds,
                                        activeAthletes.anthropometricsSnapshotFor(
                                            participant.participant.athleteId,
                                        ),
                                    )
                                    navController.navigate("camera")
                                }
                            } else {
                                runCatching {
                                    EncoderExercise.valueOf(requireNotNull(session.exercise))
                                }.getOrNull()?.let { exercise ->
                                    AppSession.beginEncoder(
                                        com.openjump.app.encoder.EncoderSetup(exercise, requireNotNull(session.loadKg)),
                                        session.plateDiameterCm ?: encoderSettings.defaultPlateDiameterCm,
                                        participant.participant.athleteId,
                                        session.id,
                                        rosterIds,
                                    )
                                    navController.navigate("camera")
                                }
                            }
                        },
                    )
                }
            }

            composable(COMPARISON_ROUTE) { entry ->
                val comparisonViewModel: com.openjump.app.ui.comparison.ComparisonViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(
                        viewModelStoreOwner = entry,
                        factory = com.openjump.app.ui.comparison.ComparisonViewModel.Factory,
                    )
                ComparisonScreen(
                    viewModel = comparisonViewModel,
                    onBack = { navController.popBackStack() },
                    onCompare = { navController.navigate(COMPARISON_RESULTS_ROUTE) },
                )
            }

            composable(COMPARISON_RESULTS_ROUTE) { entry ->
                val comparisonEntry = remember(entry) { navController.getBackStackEntry(COMPARISON_ROUTE) }
                val comparisonViewModel: com.openjump.app.ui.comparison.ComparisonViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(
                        viewModelStoreOwner = comparisonEntry,
                        factory = com.openjump.app.ui.comparison.ComparisonViewModel.Factory,
                    )
                ComparisonScreen(
                    viewModel = comparisonViewModel,
                    results = true,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.popBackStack() },
                )
            }

            composable(GROUPS_ROUTE) {
                GroupsScreen(
                    viewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = GroupsViewModel.Factory),
                    onBack = { navController.popBackStack() },
                    onGroupSelected = { navController.navigate("group/$it") },
                )
            }

            composable(ATHLETES_ROUTE) {
                AthletesScreen(
                    viewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = AthletesViewModel.Factory),
                    onBack = { navController.popBackStack() },
                    onAthleteSelected = { navController.navigate("athlete/$it") },
                    onAthleteEdit = { navController.navigate(athleteEditRoute(it)) },
                    onCompare = { navController.navigate(COMPARISON_ROUTE) },
                )
            }

            composable("group/{groupId}") { entry ->
                val groupId = entry.arguments?.getString("groupId")?.toLongOrNull()
                if (groupId == null || groupId <= 0L) {
                    UnknownMeasurement(onBack = { navController.popBackStack() })
                } else {
                    GroupDetailScreen(
                        groupId = groupId,
                        viewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = GroupsViewModel.Factory),
                        onBack = { navController.popBackStack() },
                        onAthleteSelected = { navController.navigate("athlete/$it") },
                        onTesting = { navController.navigate("$TESTING_ROUTE?groupId=$it") },
                    )
                }
            }

            composable(ATHLETE_PROGRESS_ROUTE) { entry ->
                val athleteId = entry.arguments?.getString("athleteId")?.toLongOrNull()
                if (athleteId == null || athleteId <= 0L) {
                    UnknownMeasurement(onBack = { navController.popBackStack() })
                } else {
                    ProgressScreen(
                        athleteId = athleteId,
                        onBack = { navController.popBackStack() },
                        onMeasurementSelected = { id, ordinal ->
                            navController.navigate(if (ordinal == null) "measurement/$id" else "measurement/$id/attempt/$ordinal")
                        },
                        onEncoderSelected = { navController.navigate("encoder/session/$it") },
                    )
                }
            }

            composable(ATHLETE_RECORDS_ROUTE) { entry ->
                val athleteId = entry.arguments?.getString("athleteId")?.toLongOrNull()
                if (athleteId == null || athleteId <= 0L) {
                    UnknownMeasurement(onBack = { navController.popBackStack() })
                } else {
                    PersonalRecordsScreen(
                        athleteId = athleteId,
                        onBack = { navController.popBackStack() },
                        onMeasurementSelected = { id, ordinal ->
                            navController.navigate(if (ordinal == null) "measurement/$id" else "measurement/$id/attempt/$ordinal")
                        },
                    )
                }
            }

            composable("athlete/{athleteId}/edit") { entry ->
                val athleteId = entry.arguments?.getString("athleteId")?.toLongOrNull()
                if (athleteId == null || athleteId <= 0L) {
                    UnknownMeasurement(onBack = { navController.popBackStack() })
                } else {
                    com.openjump.app.ui.athletes.AthleteEditScreen(
                        athleteId = athleteId,
                        personalMode = mode == ExperienceMode.PERSONAL &&
                            personalProfileAthleteId(activeAthletes, selectedAthleteId) == athleteId,
                        onBack = { navController.popBackStack() },
                        onDeleted = {
                            navScope.launch { app.selectedAthleteStore.resolve(app.athleteRepository) }
                            navController.navigateTopLevel(PEOPLE_ROUTE)
                        },
                    )
                }
            }

            composable("athlete/{athleteId}") { entry ->
                val athleteId = entry.arguments?.getString("athleteId")?.toLongOrNull()
                if (athleteId == null || athleteId <= 0L) {
                    UnknownMeasurement(onBack = { navController.popBackStack() })
                } else {
                    com.openjump.app.ui.athletes.AthleteProfileScreen(
                        athleteId = athleteId,
                        onBack = { navController.popBackStack() },
                        onEdit = { navController.navigate(athleteEditRoute(athleteId)) },
                        onMeasurementSelected = { navController.navigate("measurement/$it") },
                        onEncoderSelected = { navController.navigate("encoder/session/$it") },
                        onRecordsSelected = { navController.navigate("athlete/$athleteId/records") },
                        onProgressSelected = { navController.navigate("athlete/$athleteId/progress") },
                    )
                }
            }

            composable("encoder/setup") {
                EncoderSetupScreen(
                    onBack = { navController.popBackStack() },
                    onHowItWorks = { navController.navigate("encoder/methodology") },
                    activeAthletes = activeAthletes,
                    athletesLoading = athletesLoading,
                    selectedAthleteId = selectedAthleteId,
                    onAthleteSelected = { athleteId ->
                        navScope.launch { app.selectedAthleteStore.select(app.athleteRepository, athleteId) }
                    },
                    onImport = { uri, setup, athleteId ->
                        AppSession.beginEncoder(setup, encoderSettings.defaultPlateDiameterCm, athleteId)
                        AppSession.attachVideo(uri, VideoSource.IMPORTED)
                        navController.navigate("selector")
                    },
                    onRecord = { setup, athleteId ->
                        AppSession.beginEncoder(setup, encoderSettings.defaultPlateDiameterCm, athleteId)
                        navController.navigate("camera")
                    },
                )
            }

            composable("encoder/methodology") {
                EncoderMethodologyScreen(onBack = { navController.popBackStack() })
            }

            composable("protocol/{protocolId}") { entry ->
                val definition = ProtocolCatalog.find(entry.arguments?.getString("protocolId"))
                if (definition == null) {
                    UnknownProtocol(onBack = { navController.popBackStack() })
                } else {
                    ProtocolSetupScreen(
                        definition = definition,
                        onBack = { navController.popBackStack() },
                        activeAthletes = activeAthletes,
                        athletesLoading = athletesLoading,
                        selectedAthleteId = selectedAthleteId,
                        onAthleteSelected = { athleteId ->
                            navScope.launch { app.selectedAthleteStore.select(app.athleteRepository, athleteId) }
                        },
                        onImport = { uri, setup, athleteId, targetAttempts ->
                            val pendingImport = app.videoUriGrantReconciler.registerOwner(setOf(uri.toString()))
                            try {
                                if (definition.id == ProtocolId.ASYMMETRY) {
                                    AppSession.beginBilateral(
                                        targetAttempts = targetAttempts,
                                        athleteId = athleteId,
                                        athleteAnthropometrics = activeAthletes.anthropometricsSnapshotFor(athleteId),
                                    )
                                } else {
                                    AppSession.begin(
                                        definition.id,
                                        setup,
                                        athleteId,
                                        athleteAnthropometrics = activeAthletes.anthropometricsSnapshotFor(athleteId),
                                        targetAttempts = targetAttempts,
                                    )
                                }
                                AppSession.attachVideo(uri, VideoSource.IMPORTED)
                                navController.navigate("selector")
                            } finally {
                                pendingImport.close()
                                app.requestVideoUriReconciliation()
                            }
                        },
                        onRecord = { setup, athleteId, targetAttempts ->
                            if (definition.id == ProtocolId.ASYMMETRY) {
                                AppSession.beginBilateral(
                                    targetAttempts = targetAttempts,
                                    athleteId = athleteId,
                                    athleteAnthropometrics = activeAthletes.anthropometricsSnapshotFor(athleteId),
                                )
                            } else {
                                AppSession.begin(
                                    definition.id,
                                    setup,
                                    athleteId,
                                    athleteAnthropometrics = activeAthletes.anthropometricsSnapshotFor(athleteId),
                                    targetAttempts = targetAttempts,
                                )
                            }
                            navController.navigate("camera")
                        },
                    )
                }
            }

            composable("camera") {
                if (!TransientSessionPolicy.canOpenCamera(draft != null, encoderDraft != null)) {
                    ExpiredSession(onHome = { navController.popBackStack(HOME_ROUTE, false) })
                } else {
                    val guidance = when {
                        encoderDraft != null -> stringResource(R.string.camera_guidance_encoder)
                        else -> stringResource(
                            ProtocolGuidanceCatalog.forProtocol(requireNotNull(draft).protocolId)?.cameraCue
                                ?: R.string.camera_capture_vertical,
                        )
                    }
                    CameraScreen(
                        onSaved = { uri, stoppedAt ->
                            recordedVideoStopElapsedMs = stoppedAt
                            AppSession.attachVideo(uri, VideoSource.RECORDED)
                            navController.navigate("selector") {
                                popUpTo("camera") { inclusive = true }
                            }
                        },
                        onBack = { navController.popBackStack() },
                        captureGuidance = guidance,
                        jumpFlowStep = if (encoderDraft == null) JumpFlowStep.VIDEO else null,
                    )
                }
            }

            composable("selector") {
                if (!TransientSessionPolicy.canOpenSelector(draft != null, encoderDraft != null, videoUri != null)) {
                    ExpiredSession(onHome = { navController.popBackStack(HOME_ROUTE, false) })
                } else {
                    FrameSelectorScreen(
                        recordedVideoStopElapsedMs = recordedVideoStopElapsedMs,
                        recordFeedback = pendingRecords.takeIf { currentRoute == "selector" }.orEmpty(),
                        onRecordFeedbackConsumed = { pendingRecords = emptyList() },
                        onBack = { navController.popBackStack() },
                        onCompute = {
                            navController.navigate(if (encoderDraft != null) "encoder/result" else "result")
                        },
                    )
                }
            }

            composable("encoder/result") {
                if (!TransientSessionPolicy.canOpenEncoderResult(encoderDraft != null)) {
                    ExpiredSession(onHome = { navController.popBackStack(HOME_ROUTE, false) })
                } else {
                    EncoderResultScreen(
                        onHome = {
                            AppSession.reset()
                            navController.popBackStack(HOME_ROUTE, false)
                        },
                        onCorrect = { navController.popBackStack() },
                        onDiscard = {
                            val testingId = AppSession.encoderDraft.value?.testingSessionId
                            AppSession.reset()
                            if (testingId != null) navController.popBackStack("$TESTING_ROUTE/$testingId", false)
                            else navController.popBackStack(HOME_ROUTE, false)
                        },
                        onSaved = {
                            val testingId = AppSession.encoderDraft.value?.testingSessionId
                            AppSession.reset()
                            if (testingId != null) navController.popBackStack("$TESTING_ROUTE/$testingId", false)
                            else navController.popBackStack(HOME_ROUTE, false)
                        },
                    )
                }
            }

            composable("encoder/session/{sessionId}") { entry ->
                val sessionId = entry.arguments?.getString("sessionId")?.toLongOrNull()
                if (sessionId == null || sessionId <= 0L) {
                    UnknownMeasurement(onBack = { navController.popBackStack() })
                } else {
                    SavedEncoderResultScreen(
                        sessionId = sessionId,
                        onBack = { navController.popBackStack() },
                        onDeleted = { navController.popBackStack() },
                    )
                }
            }

            composable("result") {
                if (!TransientSessionPolicy.canOpenJumpResult(draft != null)) {
                    ExpiredSession(onHome = { navController.popBackStack(HOME_ROUTE, false) })
                } else {
                    ResultScreen(
                        onHome = { navController.popBackStack(HOME_ROUTE, false) },
                        onCorrect = { navController.popBackStack() },
                        onSaved = { records ->
                            if (records.isNotEmpty()) pendingRecords = records
                            if (AppSession.bilateral.value != null) {
                                navController.popBackStack("selector", false)
                            } else if (AppSession.prepareNextSeriesAttempt()) {
                                navController.popBackStack("selector", false)
                            } else {
                                val testingId = AppSession.draft.value?.testingSessionId
                                completedSeriesFeedback = if (testingId == null) AppSession.completedSeriesFeedback() else null
                                AppSession.reset()
                                if (testingId != null) navController.popBackStack("$TESTING_ROUTE/$testingId", false)
                                else navController.popBackStack(HOME_ROUTE, false)
                            }
                        },
                    )
                }
            }

            composable("measurement/{assessmentId}/attempt/{ordinal}") { entry ->
                val assessmentId = entry.arguments?.getString("assessmentId")?.toLongOrNull()
                val ordinal = entry.arguments?.getString("ordinal")?.toIntOrNull()
                if (assessmentId == null || assessmentId <= 0L || ordinal == null || ordinal < 0) {
                    UnknownMeasurement(onBack = { navController.popBackStack() })
                } else {
                    SavedBilateralResultRoute(
                        assessmentId = assessmentId,
                        attemptOrdinal = ordinal,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            composable("measurement/{assessmentId}") { entry ->
                val assessmentId = entry.arguments?.getString("assessmentId")?.toLongOrNull()
                if (assessmentId == null || assessmentId <= 0L) {
                    UnknownMeasurement(onBack = { navController.popBackStack() })
                } else {
                    SavedMeasurementDispatch(
                        assessmentId = assessmentId,
                        onBack = { navController.popBackStack() },
                        onDeleted = { navController.popBackStack() },
                        onAttemptSelected = { ordinal -> navController.navigate("measurement/$assessmentId/attempt/$ordinal") },
                    )
                }
            }
        }
    }
}

@Composable
private fun OpenJumpNavigationBar(
    currentDestination: NavDestination?,
    currentRoute: String?,
    mode: ExperienceMode,
    onDestinationSelected: (TopLevelDestination) -> Unit,
) {
    NavigationBar(modifier = Modifier.testTag("bottom-navigation")) {
        topLevelDestinations.forEach { destination ->
            val label = if (destination.route == PEOPLE_ROUTE) peopleLabel(mode) else destination.label
            val selected = currentDestination?.route == destination.route ||
                (currentRoute == JUMPS_ROUTE && destination.route == HOME_ROUTE) ||
                (currentRoute in setOf(ATHLETE_RECORDS_ROUTE, ATHLETE_PROGRESS_ROUTE) && destination.route == PEOPLE_ROUTE) ||
                (destination.route == SETTINGS_ROUTE && isSettingsSectionRoute(currentRoute))
            NavigationBarItem(
                selected = selected,
                onClick = { onDestinationSelected(destination) },
                modifier = Modifier.testTag("bottom-navigation-item-${destination.route}"),
                icon = {
                    Icon(
                        painter = painterResource(destination.icon),
                        contentDescription = stringResource(label),
                    )
                },
                label = { Text(stringResource(label)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = OpenJumpTheme.colors.navigationSelectedContent,
                    selectedTextColor = OpenJumpTheme.colors.navigationSelectedContent,
                    indicatorColor = OpenJumpTheme.colors.navigationSelectedContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun ExpiredSession(onHome: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.session_expired_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.session_expired_body),
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onHome, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.session_expired_home))
        }
    }
}

@Composable
private fun UnknownMeasurement(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.error_invalid_measurement),
            style = MaterialTheme.typography.titleLarge,
        )
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.common_back))
        }
    }
}

@Composable
private fun UnknownProtocol(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.error_unknown_protocol),
            style = MaterialTheme.typography.titleLarge,
        )
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.common_back))
        }
    }
}
