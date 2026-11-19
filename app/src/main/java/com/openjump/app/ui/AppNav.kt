package com.openjump.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.openjump.app.FeatureFlags
import com.openjump.app.OpenJumpApp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.openjump.app.settings.JumpSettings
import com.openjump.app.settings.OnboardingCompletion
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.ui.angles.ANGLE_MULTI_FRAME_SAVE_UI_ENABLED
import com.openjump.app.ui.angles.ANGLES_CAMERA_ROUTE
import com.openjump.app.ui.angles.ANGLES_HELP_ROUTE
import com.openjump.app.ui.angles.ANGLES_INTRO_ROUTE
import com.openjump.app.ui.angles.AngleColorPalette
import com.openjump.app.ui.angles.AngleEditorRequest
import com.openjump.app.ui.angles.AngleEditorScreen
import com.openjump.app.ui.angles.AngleEditorViewModel
import com.openjump.app.ui.angles.AngleMultiFrameSaveState
import com.openjump.app.ui.angles.AngleSaveState
import com.openjump.app.ui.angles.AngleVideoState
import com.openjump.app.ui.angles.AnglesCameraDestination
import com.openjump.app.ui.angles.AnglesHelpDestination
import com.openjump.app.ui.angles.AnglesIntroDestination
import com.openjump.app.ui.angles.angleWorkspaceEligible
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
import com.openjump.app.ui.fvp.FvpConditionsScreen
import com.openjump.app.ui.fvp.FvpDraftSession
import com.openjump.app.ui.fvp.FvpFlowViewModel
import com.openjump.app.ui.fvp.FvpHistoryScreen
import com.openjump.app.ui.fvp.FvpRecoveryNotice
import com.openjump.app.ui.fvp.FvpResultScreen
import com.openjump.app.ui.fvp.FvpSessionPolicy
import com.openjump.app.ui.fvp.FvpSetupScreen
import com.openjump.app.ui.fvp.fvpFlowStepForRoute
import com.openjump.app.ui.fvp.fvpRecoveryNoticeText
import com.openjump.app.ui.history.AngleHistorySource
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
import com.openjump.app.ui.result.bilateralContinuationTarget
import com.openjump.app.ui.result.navigateBilateralFinalSave
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
import java.util.UUID

/** Ruta de la cámara de Saltos/Encoder (grabación del borrador activo). */
internal const val CAMERA_ROUTE = "camera"

internal const val HOME_ROUTE = "home"
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

// --- Editor manual de angulos -------------------------------------------------------------------

/**
 * Ruta del editor de angulos sobre un video ya elegido. Fuera de `isBottomBarVisible`: es un flujo
 * a pantalla completa con su propia barra superior y su propio boton de salida.
 *
 * La URI del video y el atleta viajan como argumentos de ruta, no por `AppSession`: el editor de
 * angulos abre SU video ([AngleEditorViewModel.open]) y no necesita (ni debe) compartir el borrador
 * de saltos o encoder. La identidad del atleta se fija al elegir el video (atleta seleccionado en
 * ese momento) para que el analisis quede asociado a quien lo inicio.
 */
internal const val ANGLES_EDITOR_ROUTE = "angles/editor?videoUri={videoUri}&athleteId={athleteId}&analysisId={analysisId}"

internal const val ANGLES_VIDEO_URI_ARG = "videoUri"
internal const val ANGLES_ATHLETE_ID_ARG = "athleteId"

/**
 * Id del análisis guardado que se quiere REABRIR en el editor. `0` (valor de un análisis nuevo)
 * significa "no reabrir nada": el editor arranca vacío como hasta ahora.
 */
internal const val ANGLES_ANALYSIS_ID_ARG = "analysisId"

/**
 * Id de la raíz multi-fotograma (`angle_analysis_v2_roots`) que se quiere CONSULTAR.
 *
 * Es un argumento distinto de [ANGLES_ANALYSIS_ID_ARG] a propósito: mismo tipo `Long`, pero tabla
 * distinta, y confundirlos mostraría otro análisis con el mismo número. La ruta V2 tampoco empieza
 * por `angles/saved/` para que ningún patrón legacy pueda capturarla.
 */
internal const val ANGLES_V2_ROOT_ID_ARG = "rootId"

/**
 * Codifica un valor para que viaje como argumento de ruta sin romper el patron de navegacion.
 *
 * Percent-encoding RFC 3986 de todo lo que no sea *unreserved*: `?`, `&`, `#`, `/`, `%`, espacios y
 * no-ASCII quedan escapados y el valor no puede inyectar parametros ni segmentos. Es Kotlin puro
 * (sin `android.net.Uri`) para poder probarlo en JVM; el decodificado lo hace la propia libreria de
 * navegacion al entregar el argumento.
 */
internal fun encodeRouteArgument(value: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 0xFF
        val unreserved = code in 'a'.code..'z'.code ||
            code in 'A'.code..'Z'.code ||
            code in '0'.code..'9'.code ||
            code == '-'.code || code == '.'.code || code == '_'.code || code == '~'.code
        if (unreserved) {
            append(code.toChar())
        } else {
            append('%')
            append(HEX_DIGITS[code shr 4])
            append(HEX_DIGITS[code and 0x0F])
        }
    }
}

/** Destino concreto del editor de angulos para [videoUri] y [athleteId]. */
internal fun anglesEditorRoute(videoUri: String, athleteId: Long, analysisId: Long = 0L): String {
    require(videoUri.isNotBlank()) { "La ruta del editor de angulos exige una URI de video." }
    require(athleteId > 0L) { "La ruta del editor de angulos exige un atleta valido, era $athleteId." }
    require(analysisId >= 0L) { "El analisis a reabrir no puede ser negativo, era $analysisId." }
    // El argumento viaja SIEMPRE (0 = analisis nuevo): la ruta patron lo declara y la navegacion
    // nunca depende de un parametro opcional ausente.
    return "angles/editor?$ANGLES_VIDEO_URI_ARG=${encodeRouteArgument(videoUri)}" +
        "&$ANGLES_ATHLETE_ID_ARG=$athleteId" +
        "&$ANGLES_ANALYSIS_ID_ARG=$analysisId"
}

/**
 * Destino de RECUPERACION: reabre el analisis [analysisId] sobre su propio video y atleta.
 *
 * La raiz guardada es la fuente de la URI y del dueno (son los que validan la compatibilidad del
 * editor); HISTORY nunca inventa un atleta ni abre otro video para el mismo analisis.
 */
internal fun anglesSavedAnalysisRoute(analysisId: Long, videoUri: String, athleteId: Long): String {
    require(analysisId > 0L) { "La recuperacion de angulos exige un analisis guardado, era $analysisId." }
    return anglesEditorRoute(videoUri, athleteId, analysisId)
}

/**
 * Ruta del detalle COMPACTO y de solo lectura de un analisis guardado (familia legacy).
 *
 * Existe para que el resultado siga siendo consultable sin abrir el video: la fila de HISTORY no
 * puede decidir por la URI (una URI presente puede haber perdido su permiso tras restaurar una
 * copia) y `canReopen` ya no bloquea la navegacion. Por el contrato D2 el detalle NO ofrece editar
 * ni reabrir el video: un analisis legacy se consulta y muestra un aviso de imagen no disponible.
 */
internal const val ANGLES_SAVED_DETAIL_ROUTE = "angles/saved/{$ANGLES_ANALYSIS_ID_ARG}"

/**
 * Destino concreto del detalle guardado del analisis [analysisId].
 *
 * SOLO ids de `angle_analyses` (familia legacy). `angle_analysis_v2_roots` tiene su propia secuencia
 * de ids y su `id` puede coincidir numericamente con uno legacy, asi que un identificador de una
 * raiz multi-fotograma no puede construirse con esta ruta: su detalle es otra pantalla.
 */
internal fun anglesSavedDetailRoute(analysisId: Long): String {
    require(analysisId > 0L) { "El detalle de angulos exige un analisis guardado, era $analysisId." }
    return "angles/saved/$analysisId"
}

/**
 * Ruta del detalle de SOLO LECTURA de una raiz multi-fotograma V2.
 *
 * Prefijo propio (`angles/v2/saved/...`): no comparte patron con [ANGLES_SAVED_DETAIL_ROUTE] ni con
 * el editor, asi que un id de `angle_analysis_v2_roots` no puede caer nunca en el detalle legacy
 * (que leeria `angle_analyses`) ni en el editor (que exigiria un video que el formato V2 no guarda).
 * La pantalla consulta imagenes anotadas y datos estructurados sin abrir el video original.
 */
internal const val ANGLES_V2_SAVED_DETAIL_ROUTE = "angles/v2/saved/{$ANGLES_V2_ROOT_ID_ARG}"

/** Destino concreto del detalle V2 de la raiz [rootId] (identidad de tabla `angle_analysis_v2_roots`). */
internal fun anglesV2SavedDetailRoute(rootId: Long): String {
    require(rootId > 0L) { "El detalle V2 de angulos exige una raiz guardada, era $rootId." }
    return "angles/v2/saved/$rootId"
}

private const val HEX_DIGITS = "0123456789ABCDEF"

// F-V-P experimental flow. Routes stay out of `isBottomBarVisible` and `settingsSectionRoutes`.
internal const val FVP_SETUP_ROUTE = "fvp/setup"
internal const val FVP_CONDITIONS_ROUTE = "fvp/conditions"
internal const val FVP_LIVE_RESULT_ROUTE = "fvp/result"
internal const val FVP_HISTORY_ROUTE = "fvp/history"
internal const val FVP_SAVED_RESULT_ROUTE = "fvp/result/{assessmentId}"

internal fun fvpSavedResultRoute(assessmentId: Long): String = "fvp/result/$assessmentId"

/**
 * Destino + politica de pila de UNA transicion del flujo F-V-P, como datos puros.
 *
 * INVARIANTE DE NAVEGACION (P0 A-P0-1): tras guardar con exito la serie, la pila queda SIEMPRE
 * `Home -> fvp/result/{id}`, tanto si se guardo desde el resultado vivo como si se llego por
 * "Editar" (resultado guardado -> conditions -> resultado vivo). El pop va hasta [HOME_ROUTE] y NO
 * es inclusivo porque Home es la raiz del flujo (unica entrada, linea `onFvpSelected`); en cambio
 * `fvp/setup` NO esta en la pila cuando se edita, asi que un `popUpTo(FVP_SETUP_ROUTE)` se ignora y
 * deja `result/{viejo} -> conditions -> result/{nuevo}`, con Back cayendo primero en el borrador
 * caducado y despues en la serie antigua.
 *
 * Se expone como datos para poder verificar la politica (pop/single-top) en tests JVM e
 * instrumentados sin componer el `NavHost` completo de la app.
 */
internal data class FvpNavTarget(
    val route: String,
    val popUpToRoute: String? = null,
    val popUpToInclusive: Boolean = false,
    val launchSingleTop: Boolean = false,
)

/** Home -> `fvp/setup`: `launchSingleTop` evita apilar dos setups con un doble toque. */
internal fun fvpSetupNavTarget(): FvpNavTarget =
    FvpNavTarget(route = FVP_SETUP_ROUTE, launchSingleTop = true)

/**
 * Guardar (resultado vivo o tras editar) -> `fvp/result/{id}`, podando la pila hasta Home.
 *
 * `launchSingleTop` mantiene la operacion idempotente si ese mismo resultado ya estuviera arriba; la
 * entrada nueva es siempre el destino de resultado guardado con el id recien persistido.
 */
internal fun fvpSavedResultNavTarget(assessmentId: Long): FvpNavTarget = FvpNavTarget(
    route = fvpSavedResultRoute(assessmentId),
    popUpToRoute = HOME_ROUTE,
    launchSingleTop = true,
)

/** Unico punto de navegacion del flujo F-V-P: traduce un [FvpNavTarget] a `NavOptions`. */
internal fun NavHostController.navigateFvp(target: FvpNavTarget) {
    navigate(target.route) {
        target.popUpToRoute?.let { route ->
            popUpTo(route) { inclusive = target.popUpToInclusive }
        }
        launchSingleTop = target.launchSingleTop
    }
}

internal fun fvpFlowRoutes(): List<String> = listOf(
    FVP_SETUP_ROUTE,
    FVP_CONDITIONS_ROUTE,
    FVP_LIVE_RESULT_ROUTE,
    FVP_SAVED_RESULT_ROUTE,
    FVP_HISTORY_ROUTE,
)

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
fun OpenJumpNavHost(
    navController: NavHostController,
    /**
     * Gate de producto del guardado multi-fotograma que se propaga al editor de ángulos y a la
     * introducción de Ángulos. Se usa **solo** para calcular la disponibilidad de la acción de
     * guardado y la elegibilidad del workspace del editor, y para la copia introductoria de las
     * rutas de introducción y ayuda; por defecto mantiene el valor compilado del producto
     * ([ANGLE_MULTI_FRAME_SAVE_UI_ENABLED]) y ninguna ruta de la app lo activa ni lo desactiva.
     */
    angleMultiFrameSaveUiEnabled: Boolean = ANGLE_MULTI_FRAME_SAVE_UI_ENABLED,
) {
    val context = LocalContext.current
    val encoderSettings = remember(context) { EncoderSettings(context) }
    // Read at session start only: the preference seeds new drafts and never rewrites existing ones.
    val jumpSettings = remember(context) { JumpSettings(context) }
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
    // Nombres del roster activo por id: la cabecera del resultado identifica el atleta DE LA SERIE
    // (no el seleccionado) y cae al marcador neutro si el nombre no se puede resolver.
    val athleteNamesById = remember(activeAthletes) {
        activeAthletes.associate { it.id to it.displayName }
    }
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
    // D6/R2: UNICO dueño del estado recuperable del flujo F-V-P y de su `SavedStateHandle`. Se crea
    // UNA vez por grafo de navegacion -no por destino- y RESTAURA en su `init` SIN BLOQUEAR (A3):
    // publica PENDIENTE de forma sincrona y reconcilia con la verdad persistida en una corrutina
    // acotada. No es una segunda fuente de verdad: envuelve a la sesion (ver FvpFlowViewModel), y es
    // el objetivo de TODA mutacion del flujo (A2): las pantallas reciben ESTE dueño, nunca crean otro.
    // El flag es de compilacion: en release no se crea el dueno del flujo ni se ejecuta su
    // recuperacion, de modo que ocultar la entrada no toca borradores ni datos persistidos.
    val fvpProfileEnabled = FeatureFlags.fvpEnabled
    val fvpFlowViewModel: FvpFlowViewModel? =
        if (fvpProfileEnabled) {
            androidx.lifecycle.viewmodel.compose.viewModel(factory = FvpFlowViewModel.Factory)
        } else {
            null
        }
    val fvpRecoveryNotice: FvpRecoveryNotice =
        fvpFlowViewModel?.recoveryNotice?.collectAsState()?.value ?: FvpRecoveryNotice.None
    // A3: reconciliacion EN VUELO. Mientras sea `true`, setup/condiciones/resultado en vivo muestran
    // un cuerpo de carga NO interactivo: no siembran formularios, no declaran caducidad, no calculan
    // ni guardan. Historial y resultado GUARDADO no esperan (leen sus propias filas).
    val fvpReconciliationPending: Boolean =
        fvpFlowViewModel?.reconciliationPending?.collectAsState()?.value == true
    val fvpDraft by FvpDraftSession.draft.collectAsState()
    // Conservar el estado de texto de Saltos al retroceder a Medicion. Solo se restaura la
    // entrada guardada si sigue siendo la MISMA sesion; una nueva identidad no hereda ediciones.
    var savedFvpConditionsSession by rememberSaveable { mutableStateOf<String?>(null) }
    val videoUri by AppSession.videoUri.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route
    // Paso visible del flujo F-V-P (metadato del payload de recuperacion) y salida terminal a
    // Home: el payload se limpia para que un borrador descartado no se resucite (D6). Se observa
    // la RUTA y no un boton concreto, de modo que tambien cubre el Back del sistema. Las rutas
    // ajenas al flujo no tocan ni el paso ni el payload.
    LaunchedEffect(currentRoute, fvpProfileEnabled) {
        if (fvpProfileEnabled) {
            fvpFlowStepForRoute(currentRoute)?.let { fvpFlowViewModel?.onStepVisible(it) }
            if (currentRoute == HOME_ROUTE) fvpFlowViewModel?.onLeftFlow()
        }
    }
    val showNavigationBar = isBottomBarVisible(currentRoute)
    var completedSeriesFeedback by rememberSaveable { mutableStateOf<SeriesFeedback?>(null) }
    var pendingRecords by remember { mutableStateOf<List<PersonalRecord>>(emptyList()) }
    // Monotonic UI timestamp only; not a second video-processing state.
    var recordedVideoStopElapsedMs by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        // Only the immersive camera owns its insets: preview edge-to-edge,
        // floating controls inside safeDrawing. The Angles intake camera reuses that same pattern;
        // every other route stays unchanged.
        contentWindowInsets = if (currentRoute == CAMERA_ROUTE || currentRoute == ANGLES_CAMERA_ROUTE) {
            WindowInsets(0, 0, 0, 0)
        } else {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        },
        bottomBar = {
            if (showNavigationBar) {
                OpenJumpNavigationBar(
                    currentDestination = currentDestination,
                    currentRoute = currentRoute,
                    mode = mode,
                    onDestinationSelected = { destination ->
                        // Salir de Saltos con el tab Inicio es una salida EXPLÍCITA a Home. El helper
                        // genérico combina `popUpTo(inicio)` + `saveState` con `restoreState`, y en
                        // esa secuencia el toque en Inicio volvía a mostrar Saltos (A0: 2/2). El
                        // resto de tabs conserva su comportamiento sin cambios.
                        if (currentRoute == JUMPS_ROUTE && destination.route == HOME_ROUTE) {
                            navController.exitJumpsToHome()
                        } else {
                            navController.navigateTopLevel(destination.route)
                        }
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
            // Movimiento propio y corto (fundido de opacidad de 160 ms, sin retardo, slide ni
            // escala) para los pares de rutas ordinarios. Si cualquiera de los dos lados toca la
            // cámara de Saltos/Encoder o la de Ángulos, entrada Y salida son `None`, también con
            // Back/pop, porque Navigation-Compose aplica estos mismos callbacks en ambos sentidos.
            // No se afirma liberación física instantánea del hardware de cámara.
            enterTransition = { navEnterTransition() },
            exitTransition = { navExitTransition() },
            popEnterTransition = { navEnterTransition() },
            popExitTransition = { navExitTransition() },
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
                    // Entrada de Ángulos: abre la PREPARACIÓN (atleta + cómo funciona + Grabar
                    // principal / Importar secundario). No abre el selector ni la cámara al entrar:
                    // sólo al pulsar un botón, y sin tocar Saltos/Encoder ni `AppSession`.
                    onAnglesSelected = { navController.navigate(ANGLES_INTRO_ROUTE) },
                    onEncoderSelected = { navController.navigate("encoder/setup") },
                    onHistorySelected = { navController.navigateTopLevel(HISTORY_ROUTE) },
                    onMeasurementSelected = { navController.navigate("measurement/$it") },
                    onEncoderSessionSelected = { navController.navigate("encoder/session/$it") },
                    onFvpSelected = {
                        if (fvpProfileEnabled) navController.navigateFvp(fvpSetupNavTarget())
                    },
                    fvpProfileEnabled = fvpProfileEnabled,
                )
            }

            composable(JUMPS_ROUTE) {
                JumpsScreen(
                    onBack = { navController.popBackStack() },
                    onProtocolSelected = { navController.navigate("protocol/${it.storageKey}") },
                )
            }

            // Introducción de Ángulos: dueña del intento (atleta CONGELADO antes de abrir cámara o
            // selector), de la selección local y del único lanzamiento del picker. Vive fuera de la
            // barra inferior y nunca escribe en `AppSession`.
            composable(ANGLES_INTRO_ROUTE) { entry ->
                AnglesIntroDestination(
                    navController = navController,
                    entry = entry,
                    activeAthletes = activeAthletesOrNull,
                    globalSelectedAthleteId = selectedAthleteId,
                    multiFrameEnabled = angleMultiFrameSaveUiEnabled,
                )
            }

            composable(ANGLES_HELP_ROUTE) {
                AnglesHelpDestination(
                    navController = navController,
                    multiFrameEnabled = angleMultiFrameSaveUiEnabled,
                )
            }

            // Cámara PROPIA de Ángulos: no es la ruta `camera` (esa exige borrador y lleva al
            // selector de fotogramas). La petición congelada viaja en la ruta y el dueño real es el
            // ViewModel de la entrada de la introducción.
            composable(ANGLES_CAMERA_ROUTE) { entry ->
                AnglesCameraDestination(navController = navController, entry = entry)
            }

            // Editor manual de angulos sobre el video elegido en Home. La ruta solo TRANSPORTA la
            // identidad del video y del atleta: el ViewModel real abre su propio video (lease +
            // grant), persiste por la unica puerta transaccional y se cierra con la entrada de la
            // pila (`onCleared` -> `close`), sin borrar datos del atleta ni del video.
            composable(ANGLES_EDITOR_ROUTE) { entry ->
                val videoUri = entry.arguments?.getString(ANGLES_VIDEO_URI_ARG)
                val athleteId = entry.arguments?.getString(ANGLES_ATHLETE_ID_ARG)?.toLongOrNull()
                // 0 = analisis nuevo. Un id > 0 pide RECUPERAR esa raiz guardada una vez abierto el
                // video (la reapertura necesita indice y player reales, no puede ocurrir antes).
                val analysisId = entry.arguments?.getString(ANGLES_ANALYSIS_ID_ARG)?.toLongOrNull() ?: 0L
                if (videoUri.isNullOrBlank() || athleteId == null || athleteId <= 0L) {
                    // Ruta incompleta: no se abre video, no se escribe y se vuelve al origen.
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    val angleDefaults = AngleEditorDefaults(
                        namePrefix = stringResource(R.string.angle_default_name_prefix),
                        colorArgb = AngleColorPalette.first(),
                    )
                    // Una clave de sesion UUID por ANALISIS: se genera al entrar y se conserva
                    // (`rememberSaveable`) ante recreacion o muerte de proceso, de modo que un
                    // reintento de guardado siga siendo idempotente en el repositorio.
                    val analysisSessionKey = rememberSaveable { UUID.randomUUID().toString() }
                    val angleEditorViewModel: AngleEditorViewModel =
                        androidx.lifecycle.viewmodel.compose.viewModel(
                            factory = AngleEditorViewModel.factory(angleDefaults),
                        )
                    LaunchedEffect(angleEditorViewModel, videoUri, athleteId, analysisSessionKey) {
                        angleEditorViewModel.open(
                            AngleEditorRequest(
                                uri = videoUri,
                                athleteId = athleteId,
                                sessionKey = analysisSessionKey,
                            ),
                        )
                    }
                    val angleSaveState by angleEditorViewModel.saveState.collectAsState()
                    // Recuperacion de un analisis guardado: se intenta UNA vez, cuando el video ya
                    // esta listo (antes no hay frame confirmado que rehidratar). Un rechazo de
                    // compatibilidad deja el editor como analisis nuevo y no navega solo.
                    val angleVideoState by angleEditorViewModel.videoState.collectAsState()
                    // `remember` (no `rememberSaveable`) a proposito: al recrear la Activity se
                    // reinicia, y debe hacerlo. Tras una muerte de proceso el ViewModel tambien es
                    // nuevo y la reapertura tiene que ocurrir; tras una recreacion de configuracion
                    // el ViewModel SOBREVIVE y es el quien rechaza la repeticion por IDENTIDAD DE
                    // APERTURA (`openingAnalysisId`), sin releer Room ni pisar la edicion viva. La
                    // identidad persistida vigente (`savedAnalysisId`) puede ser ya otra raiz: la
                    // copia que «guardar como analisis nuevo» adopta tras un conflicto o una
                    // escritura sin verificar. Esta ruta sigue nombrando la de apertura, asi que
                    // reemitirla no puede releer el analisis original ni reemplazar el borrador
                    // pendiente ni los bindings de la copia; el ViewModel compara las dos
                    // identidades por separado. Guardar este booleano en el estado de la entrada
                    // dejaria sin reabrir el caso de muerte de proceso.
                    var angleReopenRequested by remember { mutableStateOf(false) }
                    LaunchedEffect(analysisId, angleVideoState, angleReopenRequested) {
                        if (!angleReopenRequested &&
                            analysisId > 0L &&
                            angleVideoState is AngleVideoState.Ready
                        ) {
                            angleReopenRequested = true
                            angleEditorViewModel.reopen(analysisId)
                        }
                    }
                    // Guardado verificado: la navegacion vuelve al origen (Home) con una pila
                    // coherente. Un conflicto, un borrado o un fallo NO navegan: la edicion sigue
                    // en pantalla con su mensaje.
                    LaunchedEffect(angleSaveState) {
                        if (angleSaveState is AngleSaveState.Saved) navController.popBackStack()
                    }
                    // Guardado multi-fotograma: se observa el estado REAL del ViewModel. La
                    // navegacion al detalle V2 ocurre SOLO cuando la transaccion ya termino y el
                    // `rootId` viene de `angle_analysis_v2_roots` (identidad de la tabla V2, no de
                    // `angle_analyses`). Un rechazo no navega, un id invalido tampoco: la sesion
                    // sigue en pantalla con sus fotogramas y su mensaje de fallo.
                    val multiFrameSaveState by angleEditorViewModel.multiFrameSaveState
                        .collectAsState()
                    var multiFrameSavedHandled by remember { mutableStateOf(false) }
                    LaunchedEffect(multiFrameSaveState) {
                        val rootId = when (val saved = multiFrameSaveState) {
                            is AngleMultiFrameSaveState.Saved -> saved.rootId
                            is AngleMultiFrameSaveState.AlreadySaved -> saved.rootId
                            else -> null
                        }
                        if (!multiFrameSavedHandled && rootId != null && rootId > 0L) {
                            multiFrameSavedHandled = true
                            navController.navigate(anglesV2SavedDetailRoute(rootId)) {
                                // El editor sale de la pila: la raiz ya esta persistida y volver
                                // atras no debe reabrir el video ni retener la sesion en memoria.
                                popUpTo(ANGLES_EDITOR_ROUTE) { inclusive = true }
                            }
                        }
                    }
                    AngleEditorScreen(
                        viewModel = angleEditorViewModel,
                        onCancel = { navController.popBackStack() },
                        angleMultiFrameSaveUiEnabled = angleMultiFrameSaveUiEnabled,
                        // Elegibilidad del workspace derivada SOLO de la ruta y del gate inyectado:
                        // esta entrada es un análisis NUEVO (`analysisId == 0`), nunca una reapertura.
                        // No se consultan ids guardados/abiertos del ViewModel.
                        workspaceRequested = angleWorkspaceEligible(
                            gateEnabled = angleMultiFrameSaveUiEnabled,
                            routeAnalysisId = analysisId,
                        ),
                    )
                }
            }

            composable(ANGLES_SAVED_DETAIL_ROUTE) { entry ->
                val analysisId = entry.arguments?.getString(ANGLES_ANALYSIS_ID_ARG)?.toLongOrNull()
                if (analysisId == null || analysisId <= 0L) {
                    // Sin id guardado no hay nada que consultar: no se escribe y se vuelve al origen.
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    val savedDetailViewModel: com.openjump.app.ui.angles.AngleSavedDetailViewModel =
                        androidx.lifecycle.viewmodel.compose.viewModel(
                            factory = com.openjump.app.ui.angles.AngleSavedDetailViewModel.factory(),
                        )
                    // Solo datos: esta pantalla no abre video, no dibuja geometria y no recrea el
                    // editor. Por el contrato D2 tampoco ofrece editar: un analisis legacy sin
                    // imagen anotada se consulta y no reabre el video original.
                    com.openjump.app.ui.angles.AngleSavedDetailScreen(
                        analysisId = analysisId,
                        viewModel = savedDetailViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            composable(ANGLES_V2_SAVED_DETAIL_ROUTE) { entry ->
                val rootId = entry.arguments?.getString(ANGLES_V2_ROOT_ID_ARG)?.toLongOrNull()
                if (rootId == null || rootId <= 0L) {
                    // Sin id de raiz V2 no hay nada que consultar: no se escribe y se vuelve al origen.
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    val v2DetailViewModel: com.openjump.app.ui.angles.AngleV2SavedDetailViewModel =
                        androidx.lifecycle.viewmodel.compose.viewModel(
                            factory = com.openjump.app.ui.angles.AngleV2SavedDetailViewModel.factory(),
                        )
                    // Solo consulta: todos los fotogramas en orden, su imagen anotada verificada y sus
                    // marcas. No abre video, no edita, no borra y no comparte (eso sera otro slice).
                    com.openjump.app.ui.angles.AngleV2SavedDetailScreen(
                        rootId = rootId,
                        viewModel = v2DetailViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            composable(HISTORY_ROUTE) {
                HistoryScreen(
                    onMeasurementSelected = { navController.navigate("measurement/$it") },
                    onEncoderSessionSelected = { navController.navigate("encoder/session/$it") },
                    // Una fila legacy abre SU detalle guardado, que consulta la raiz persistida sin
                    // abrir video; editar sobre el video se decide alli. El guard por fuente es
                    // deliberado: `angles/saved/{id}` resuelve ids de `angle_analyses`, asi que un id
                    // de `angle_analysis_v2_roots` (mismo numero, otra tabla) no debe llegar nunca.
                    onAngleAnalysisSelected = { entry ->
                        if (entry.source == AngleHistorySource.LEGACY) {
                            navController.navigate(anglesSavedDetailRoute(entry.id))
                        }
                    },
                    // Identidad diferenciada: el detalle V2 (imagenes anotadas, sin video ni
                    // edicion) tiene su propia ruta y su propio ViewModel. La fila multi-fotograma
                    // NO puede reutilizar la ruta legacy ni su ViewModel: los ids de
                    // `angle_analysis_v2_roots` y `angle_analyses` son secuencias independientes.
                    onAngleMultiFrameSelected = { entry ->
                        if (entry.source == AngleHistorySource.MULTI_FRAME) {
                            navController.navigate(anglesV2SavedDetailRoute(entry.id))
                        }
                    },
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
                                        markMovementStart = jumpSettings.markMovementStart,
                                    )
                                    navController.navigate(CAMERA_ROUTE)
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
                                    navController.navigate(CAMERA_ROUTE)
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
                        navController.navigate(CAMERA_ROUTE)
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
                        markMovementStart = jumpSettings.markMovementStart,
                        onImport = { uri, setup, athleteId, targetAttempts ->
                            val pendingImport = app.videoUriGrantReconciler.registerOwner(setOf(uri.toString()))
                            try {
                                if (definition.id == ProtocolId.ASYMMETRY) {
                                    AppSession.beginBilateral(
                                        targetAttempts = targetAttempts,
                                        athleteId = athleteId,
                                        athleteAnthropometrics = activeAthletes.anthropometricsSnapshotFor(athleteId),
                                        markMovementStart = jumpSettings.markMovementStart,
                                    )
                                } else {
                                    AppSession.begin(
                                        definition.id,
                                        setup,
                                        athleteId,
                                        athleteAnthropometrics = activeAthletes.anthropometricsSnapshotFor(athleteId),
                                        targetAttempts = targetAttempts,
                                        markMovementStart = jumpSettings.markMovementStart,
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
                                    markMovementStart = jumpSettings.markMovementStart,
                                )
                            } else {
                                AppSession.begin(
                                    definition.id,
                                    setup,
                                    athleteId,
                                    athleteAnthropometrics = activeAthletes.anthropometricsSnapshotFor(athleteId),
                                    targetAttempts = targetAttempts,
                                    markMovementStart = jumpSettings.markMovementStart,
                                )
                            }
                            navController.navigate(CAMERA_ROUTE)
                        },
                    )
                }
            }

            composable(CAMERA_ROUTE) {
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
                                popUpTo(CAMERA_ROUTE) { inclusive = true }
                            }
                        },
                        onBack = { navController.popBackStack() },
                        captureGuidance = guidance,
                        jumpFlowStep = if (encoderDraft == null) JumpFlowStep.VIDEO else null,
                        onImportVideo = { uri ->
                            // Alternativa desde el propio error de cámara (F-032): se
                            // registra el grant del vídeo elegido y se sigue al selector
                            // como si se hubiera grabado.
                            val pendingImport = app.videoUriGrantReconciler.registerOwner(setOf(uri.toString()))
                            try {
                                AppSession.attachVideo(uri, VideoSource.IMPORTED)
                                navController.navigate("selector") {
                                    popUpTo(CAMERA_ROUTE) { inclusive = true }
                                }
                            } finally {
                                pendingImport.close()
                                app.requestVideoUriReconciliation()
                            }
                        },
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
                            val continuation = bilateralContinuationTarget(AppSession.bilateral.value != null)
                            if (continuation != null) {
                                // Per-attempt continuation: no Room id exists yet, the
                                // bilateral session stays open for the next side.
                                navController.popBackStack(continuation, false)
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
                        // F-004: final bilateral save lands on the persisted detail.
                        // Reset runs only after the Room commit, on the success path:
                        // failure keeps the session and the retry UI. The shared
                        // production router pops jumps/protocol/selector/result so
                        // Back from the detail lands on Home. No record banner is
                        // queued here: the detail already shows the records and a
                        // lingering pendingRecords would surface stale on next Home.
                        onBilateralSaved = { id, _ ->
                            navController.navigateBilateralFinalSave(id)
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

            // --- Flujo F-V-P experimental (fuera de la barra inferior) ---------------------------
            // Las rutas siguen registradas para no romper la restauracion de una pila antigua,
            // pero con el flag OFF ninguna compone pantalla ni crea su ViewModel: el guard poda a
            // Home. En debug/demo el comportamiento es el de siempre.

            composable(FVP_SETUP_ROUTE) {
                FvpAccessGate(fvpProfileEnabled, { navController.popBackStack(HOME_ROUTE, false) }) {
                    val flow = requireNotNull(fvpFlowViewModel)
                    // A3: mientras la reconciliacion esta PENDIENTE no se compone el formulario. Sembrar
                    // los campos con una sesion todavia sin adoptar dejaria el paso 1 vacio aunque la
                    // restauracion se completase un instante despues: se muestra carga no interactiva.
                    if (fvpReconciliationPending) {
                        FvpRecoveryPendingBody()
                    } else {
                        FvpSetupScreen(
                            flowViewModel = flow,
                            activeAthletes = activeAthletes,
                            athletesLoading = athletesLoading,
                            selectedAthleteId = selectedAthleteId,
                            onAthleteSelected = { athleteId ->
                                navScope.launch { app.selectedAthleteStore.select(app.athleteRepository, athleteId) }
                            },
                            onBack = { navController.popBackStack() },
                            onContinue = {
                                val sessionKey = FvpDraftSession.draft.value?.sessionKey
                                val restore = sessionKey != null && sessionKey == savedFvpConditionsSession
                                if (!restore) navController.clearBackStack(FVP_CONDITIONS_ROUTE)
                                navController.navigate(FVP_CONDITIONS_ROUTE) { restoreState = restore }
                            },
                            onHistory = { navController.navigate(FVP_HISTORY_ROUTE) },
                        )
                    }
                }
            }

            composable(FVP_CONDITIONS_ROUTE) {
                FvpAccessGate(fvpProfileEnabled, { navController.popBackStack(HOME_ROUTE, false) }) {
                    val flow = requireNotNull(fvpFlowViewModel)
                    // A3: PENDIENTE manda sobre el guard de sesion: sin la reconciliacion resuelta el
                    // borrador aun no esta adoptado, y declarar caducidad aqui seria un veredicto falso
                    // sobre una restauracion en curso. Tampoco se calcula ni se guarda.
                    if (fvpReconciliationPending) {
                        FvpRecoveryPendingBody()
                    } else if (!FvpSessionPolicy.canOpenConditions(fvpDraft != null)) {
                        ExpiredSession(
                            onHome = { navController.popBackStack(HOME_ROUTE, false) },
                            // Un payload que no se pudo recuperar se dice como tal; nunca se inventa un
                            // borrador ni se presenta como una caducidad cualquiera (D6).
                            recoveryMessage = fvpRecoveryNoticeText(fvpRecoveryNotice),
                        )
                    } else {
                        FvpConditionsScreen(
                            flowViewModel = flow,
                            onBack = {
                                if (navController.previousBackStackEntry?.destination?.route == FVP_SETUP_ROUTE) {
                                    savedFvpConditionsSession = FvpDraftSession.draft.value?.sessionKey
                                    navController.popBackStack(FVP_SETUP_ROUTE, inclusive = false, saveState = true)
                                } else {
                                    // Duplicar desde un guardado no tiene Setup detras: vuelve a su
                                    // origen, sin inventar una entrada ni reutilizar otra sesion.
                                    navController.popBackStack()
                                }
                            },
                            onCalculate = { navController.navigate(FVP_LIVE_RESULT_ROUTE) },
                        )
                    }
                }
            }

            composable(FVP_HISTORY_ROUTE) {
                FvpAccessGate(fvpProfileEnabled, { navController.popBackStack(HOME_ROUTE, false) }) {
                    FvpHistoryScreen(
                        athleteId = selectedAthleteId,
                        onBack = { navController.popBackStack() },
                        onOpen = { navController.navigate(fvpSavedResultRoute(it)) },
                        // Nombre del atleta SELECCIONADO solo como contexto de la lista; el resultado
                        // guardado resuelve su propio atleta desde la fila.
                        athleteName = selectedAthleteId?.let { athleteNamesById[it] },
                    )
                }
            }

            composable(FVP_LIVE_RESULT_ROUTE) {
                FvpAccessGate(fvpProfileEnabled, { navController.popBackStack(HOME_ROUTE, false) }) {
                    val flow = requireNotNull(fvpFlowViewModel)
                    // A3: PENDIENTE manda sobre el guard: ni caducidad, ni calculo, ni guardado hasta que
                    // la reconciliacion resuelva.
                    if (fvpReconciliationPending) {
                        FvpRecoveryPendingBody()
                    } else if (!FvpSessionPolicy.canOpenLiveResult(fvpDraft != null)) {
                        ExpiredSession(
                            onHome = { navController.popBackStack(HOME_ROUTE, false) },
                            recoveryMessage = fvpRecoveryNoticeText(fvpRecoveryNotice),
                        )
                    } else {
                        FvpResultScreen(
                            flowViewModel = flow,
                            onBack = { navController.popBackStack() },
                            onExit = {
                                // A2: descarte explicito por el DUEÑO (supersede pendiente + payload).
                                flow.reset()
                                navController.popBackStack(HOME_ROUTE, false)
                            },
                            onEdit = { navController.popBackStack() },
                            // Terminal del flujo: la pila queda Home -> resultado guardado. El pop va a
                            // HOME (presente siempre), nunca a `fvp/setup`, que puede no estar en la pila.
                            onSaved = { id -> navController.navigateFvp(fvpSavedResultNavTarget(id)) },
                            onDeleted = { navController.popBackStack(HOME_ROUTE, false) },
                            // Identidad de la serie (Muse P1-3): atleta del borrador vivo.
                            athleteName = fvpDraft?.athleteId?.let { athleteNamesById[it] },
                            athleteNameById = athleteNamesById,
                        )
                    }
                }
            }

            composable(FVP_SAVED_RESULT_ROUTE) { entry ->
                FvpAccessGate(fvpProfileEnabled, { navController.popBackStack(HOME_ROUTE, false) }) {
                    val flow = requireNotNull(fvpFlowViewModel)
                    val assessmentId = entry.arguments?.getString("assessmentId")?.toLongOrNull()
                    if (assessmentId == null || assessmentId <= 0L) {
                        UnknownMeasurement(onBack = { navController.popBackStack() })
                    } else {
                        FvpResultScreen(
                            assessmentId = assessmentId,
                            // Mismo dueño que en el resto del flujo: "duplicar y editar" de una serie
                            // guardada es una mutacion EXTERNA y debe superseder pendiente/recuperacion.
                            flowViewModel = flow,
                            onBack = { navController.popBackStack() },
                            onExit = { navController.popBackStack() },
                            onEdit = { navController.navigate(FVP_CONDITIONS_ROUTE) },
                            // DEAD CODE conocido: esta rama pasa `onSave = null`, asi que el boton
                            // Guardar no se compone y este lambda nunca se invoca (no se puede quitar
                            // el parametro sin tocar FvpResultScreen). Se mantiene con la MISMA
                            // politica que el resultado vivo para que ambas ramas no diverjan.
                            onSaved = { id -> navController.navigateFvp(fvpSavedResultNavTarget(id)) },
                            onDeleted = { navController.popBackStack() },
                            // El atleta lo resuelve la pantalla desde la PROPIA fila
                            // (`assessment.athleteId`), no desde el atleta seleccionado.
                            athleteNameById = athleteNamesById,
                        )
                    }
                }
            }
        }
    }
}


/**
 * Puerta unica de los destinos F-V-P. Con el flag OFF no compone la pantalla, no toca el
 * ViewModel/borrador y devuelve al usuario a Home. Se mantiene como guard (en vez de eliminar las
 * rutas del grafo) para que una pila restaurada de una version anterior no provoque una excepcion
 * por destino inexistente.
 */
@Composable
private fun FvpAccessGate(
    enabled: Boolean,
    onBlocked: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        content()
    } else {
        LaunchedEffect(Unit) { onBlocked() }
        Box(Modifier.fillMaxSize())
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

/**
 * Salida explícita de Saltos a Home al tocar el tab Inicio.
 *
 * Recupera la entrada de Home que YA está en la pila con `saveState = false`: la subpila de Saltos se
 * descarta en vez de archivarse, así que ningún `restoreState` posterior puede volver a mostrarla.
 * Si la pila no contiene Home (grafo arrancado en el onboarding sin entrada de Home), se retira sólo
 * la entrada actual de Saltos y se abre Home como destino de nivel superior sin restaurar estado: no
 * se limpia el grafo ni se vuelve al onboarding.
 */
private fun NavHostController.exitJumpsToHome() {
    if (popBackStack(HOME_ROUTE, inclusive = false, saveState = false)) return
    if (currentDestination?.route == JUMPS_ROUTE) popBackStack()
    navigate(HOME_ROUTE) {
        launchSingleTop = true
        restoreState = false
    }
}

/**
 * Cuerpo de carga NO interactivo del flujo F-V-P mientras el dueño reconcilia un borrador
 * recuperado con la verdad persistida (A3). Reutiliza el copy de carga YA existente y localizado
 * ([R.string.result_loading]) y no ofrece ninguna accion: no hay formulario que sembrar, ni
 * calculo que pedir, ni serie que guardar hasta que la reconciliacion resuelva (y entonces esta
 * ruta compone el destino real, o su explicacion de caducidad si la reconciliacion la descarto).
 */
@Composable
private fun FvpRecoveryPendingBody() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.result_loading),
            modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExpiredSession(onHome: () -> Unit, recoveryMessage: String? = null) {
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
        // Flujo F-V-P con un payload que NO se pudo recuperar: se dice el motivo real (no hay
        // recuperacion para esa sesion) en vez de dejar solo el mensaje generico de caducidad.
        if (recoveryMessage != null) {
            Text(
                recoveryMessage,
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
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
