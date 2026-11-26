package com.openjump.app.ui.angles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.openjump.app.OpenJumpApp
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.ui.angles.AngleEntryHandoffState.Acquiring
import com.openjump.app.ui.angles.AngleEntryHandoffState.AwaitingCapture
import com.openjump.app.ui.angles.AngleEntryHandoffState.HandedOff
import com.openjump.app.ui.angles.AngleEntryHandoffState.Idle
import com.openjump.app.ui.angles.AngleEntryHandoffState.Ready
import com.openjump.app.ui.angles.AngleEntryHandoffState.Rejected
import com.openjump.app.ui.angles.AngleEntryHandoffState.Releasing
import com.openjump.app.ui.camera.CameraCaptureContext
import com.openjump.app.ui.camera.CameraScreen
import com.openjump.app.ui.anglesEditorRoute
import com.openjump.app.ui.encodeRouteArgument
import kotlinx.coroutines.launch

/**
 * Rutas y destinos de la ENTRADA de Ángulos por vídeo (introducción, ayuda y cámara propia).
 *
 * Estas rutas viven fuera de `AppSession` y de las rutas de Saltos/Encoder a propósito: la entrada
 * de Ángulos fija su atleta ANTES de abrir el selector o la cámara y no comparte borrador, sesión
 * transitoria ni selector de fotogramas. El dueño del intento es el [AngleEntryHandoffViewModel]
 * de la entrada de la introducción; estos destinos sólo lo leen y lo notifican.
 */
internal const val ANGLES_INTRO_ROUTE = "angles/intro"

/** Ayuda de metodología: gana su propia entrada, así el modelo y la selección de la intro sobreviven. */
internal const val ANGLES_HELP_ROUTE = "angles/intro/help"

internal const val ANGLES_CAMERA_TOKEN_ARG = "token"
internal const val ANGLES_CAMERA_ATHLETE_ID_ARG = "athleteId"
internal const val ANGLES_CAMERA_REQUEST_ID_ARG = "requestId"

/**
 * Cámara PROPIA de la entrada de Ángulos. Nunca es la ruta `camera` existente: ésa exige un
 * borrador de Saltos/Encoder y desemboca en el selector de fotogramas.
 */
internal const val ANGLES_CAMERA_ROUTE =
    "angles/camera?$ANGLES_CAMERA_TOKEN_ARG={$ANGLES_CAMERA_TOKEN_ARG}" +
        "&$ANGLES_CAMERA_ATHLETE_ID_ARG={$ANGLES_CAMERA_ATHLETE_ID_ARG}" +
        "&$ANGLES_CAMERA_REQUEST_ID_ARG={$ANGLES_CAMERA_REQUEST_ID_ARG}"

/**
 * Petición congelada que viaja en la ruta de cámara. El token es el origen exacto del intento: un
 * ticket posterior (o de otra entrada) no puede retargetear esta pantalla.
 */
internal data class AnglesCameraRequest(
    val token: String,
    val athleteId: Long,
    val requestId: Long,
)

/** Destino concreto de la cámara de Ángulos para un ticket ya congelado por el actor. */
internal fun anglesCameraRoute(token: String, athleteId: Long, requestId: Long): String {
    require(token.isNotBlank()) { "La ruta de cámara de Ángulos exige un token de origen." }
    require(athleteId > 0L) { "La ruta de cámara de Ángulos exige un atleta válido, era $athleteId." }
    require(requestId > 0L) { "La ruta de cámara de Ángulos exige una petición válida, era $requestId." }
    return "angles/camera?$ANGLES_CAMERA_TOKEN_ARG=${encodeRouteArgument(token)}" +
        "&$ANGLES_CAMERA_ATHLETE_ID_ARG=$athleteId" +
        "&$ANGLES_CAMERA_REQUEST_ID_ARG=$requestId"
}

/** Argumentos de la ruta de cámara, o `null` si la petición no es utilizable (fallo cerrado). */
internal fun parseAnglesCameraRequest(
    token: String?,
    athleteId: String?,
    requestId: String?,
): AnglesCameraRequest? {
    val parsedToken = token?.takeIf { it.isNotBlank() } ?: return null
    val parsedAthlete = athleteId?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    val parsedRequest = requestId?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    return AnglesCameraRequest(parsedToken, parsedAthlete, parsedRequest)
}

/**
 * Un ticket sólo autentica la cámara de Ángulos si es un intento **RECORD** con el token, atleta y
 * petición exactos. El `kind` es parte de la identidad del consumo: un intento IMPORT nunca puede
 * abrir ni grabar en la cámara propia aunque sus ids coincidan.
 */
private fun AwaitingCapture.matches(request: AnglesCameraRequest): Boolean =
    ticket.kind == AngleEntryKind.RECORD &&
        ticket.originToken == request.token &&
        ticket.athleteId == request.athleteId &&
        ticket.requestId == request.requestId

/** Igual que [AwaitingCapture.matches]: la transferencia de un IMPORT tampoco es un RECORD propio. */
private fun AngleEntryTransfer.matches(request: AnglesCameraRequest): Boolean =
    kind == AngleEntryKind.RECORD &&
        originToken == request.token &&
        athleteId == request.athleteId &&
        requestId == request.requestId

/**
 * ¿El actor ya validó esta petición como un intento RECORD propio? Sólo los estados que llevan
 * ticket/transferencia con `kind` pueden autenticar la escena; [Releasing] y [Rejected] viajan sólo
 * con el `requestId`, así que NO prueban que esta ruta sea la cámara legítima de ese intento (un
 * intento IMPORT cerrado podría compartir ese id). La composición de `CameraScreen` —y con ella el
 * permiso de cámara— se decide con esta función, nunca con el `requestId` a secas.
 */
internal fun anglesCameraSceneAuthenticated(
    state: AngleEntryHandoffState,
    request: AnglesCameraRequest,
): Boolean = when (state) {
    is AwaitingCapture -> state.matches(request)
    is Acquiring -> state.transfer.matches(request)
    is Ready -> state.transfer.matches(request)
    is HandedOff -> state.transfer.matches(request)
    is Releasing, is Rejected, Idle -> false
}

/**
 * ¿Esta ruta de cámara sigue siendo la dueña del intento? Se valida en TODOS los estados vivos de
 * la transferencia; en [Releasing] sólo se tolera el cierre transitorio (ni cámara nueva ni pop
 * prematuro) y en [Rejected] se acepta únicamente la petición propia para llevar el error a la
 * introducción. [Idle] significa que el intento ya no existe: la cámara no debe abrirse.
 */
internal fun anglesCameraRouteOwnsIntake(
    state: AngleEntryHandoffState,
    request: AnglesCameraRequest,
): Boolean = when (state) {
    is AwaitingCapture, is Acquiring, is Ready, is HandedOff ->
        anglesCameraSceneAuthenticated(state, request)
    // Sin kind ni token sólo queda el requestId: se tolera el cierre/error del MISMO intento, nunca
    // una escena nueva (`anglesCameraSceneAuthenticated` decide eso).
    is Releasing -> state.requestId == request.requestId
    is Rejected -> state.requestId == request.requestId
    Idle -> false
}

/**
 * Arranque de una grabación NUEVA: sólo con el MISMO intento RECORD todavía en [AwaitingCapture]
 * (sin URI aceptada) y con esta entrada de cámara en RESUMED. La comprobación se hace en el momento
 * de la llamada, no sobre un valor recordado de la composición: tras aceptar la primera URI (o
 * mientras se valida el grant) una segunda grabación no puede empezar aunque la UI no se haya
 * recompuesto, y un ticket IMPORT nunca arranca aquí.
 */
internal fun canStartNewAngleCapture(
    state: AngleEntryHandoffState,
    request: AnglesCameraRequest,
    ownEntryResumed: Boolean,
): Boolean {
    if (!ownEntryResumed) return false
    val awaiting = state as? AwaitingCapture ?: return false
    return awaiting.matches(request)
}

/**
 * Identidad FRESCA de una entrada de navegación: es la entrada ACTUAL y sigue viva. Se consulta en
 * el instante de cada efecto/callback, nunca sobre un booleano recordado de la composición: un
 * efecto o un callback encolado antes de un pop/pause no puede reclamar, navegar, popear ni escribir
 * estado global por una pantalla que ya no está delante.
 *
 * Acepta STARTED/CREATED a propósito: el resultado de un Activity Result y `onSaved` pueden
 * entregarse antes de ON_RESUME. Quien exige primer plano comprueba además [isForegroundEntry].
 */
private fun NavHostController.isCurrentEntry(entry: NavBackStackEntry): Boolean =
    currentBackStackEntry === entry &&
        entry.lifecycle.currentState != Lifecycle.State.DESTROYED

/** Primer plano real (autoridad final de efectos y callbacks): entrada ACTUAL y RESUMED. */
private fun NavHostController.isForegroundEntry(entry: NavBackStackEntry): Boolean =
    isCurrentEntry(entry) && entry.lifecycle.currentState == Lifecycle.State.RESUMED

/** Destino de la introducción: dueño del intento, de la selección y del picker de importación. */
@Composable
internal fun AnglesIntroDestination(
    navController: NavHostController,
    entry: NavBackStackEntry,
    activeAthletes: List<AthleteEntity>?,
    globalSelectedAthleteId: Long?,
    multiFrameEnabled: Boolean,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenJumpApp
    val handoff: AngleEntryHandoffViewModel =
        viewModel(viewModelStoreOwner = entry, factory = AngleEntryHandoffViewModel.Factory)
    val scope = rememberCoroutineScope()
    val state by handoff.state.collectAsStateWithLifecycle()
    val selectedAthleteId by handoff.selectedAthleteId.collectAsStateWithLifecycle()
    val athletes = activeAthletes.orEmpty()
    val loading = activeAthletes == null

    // Preselección GLOBAL sólo si es un atleta activo real; el actor nunca la convierte en elección
    // del usuario y, una vez que la intro resolvió selección, ya no puede retargetearla.
    val validGlobalId = globalSelectedAthleteId?.takeIf { id -> athletes.any { it.id == id } }
    LaunchedEffect(validGlobalId) { handoff.onGlobalAthleteResolved(validGlobalId) }
    // La invalidación de una selección sólo se decide cuando el roster ya está cargado.
    LaunchedEffect(activeAthletes) {
        if (activeAthletes != null) handoff.onActiveAthletesResolved(activeAthletes.map { it.id }.toSet())
    }

    val rejection = state as? Rejected
    var dismissedRejection by rememberSaveable { mutableStateOf(0L) }
    // Fallo de lanzamiento del picker: error LOCAL recuperable con el texto existente. No hay estado
    // nuevo en el actor, ni URI registrada, ni éxito inventado; se limpia al intentarlo de nuevo.
    var startBlocked by rememberSaveable { mutableStateOf(false) }
    val errorText = when {
        startBlocked -> stringResource(R.string.angle_intro_start_blocked)
        rejection == null || rejection.requestId <= dismissedRejection -> null
        rejection.error == AngleEntryError.ATHLETE_UNAVAILABLE ->
            stringResource(R.string.angle_intro_select_athlete)
        else -> stringResource(R.string.angle_intro_start_blocked)
    }

    val idle = state is Idle || rejection != null
    val selectedIsActive = selectedAthleteId?.let { id -> athletes.any { it.id == id } } == true

    // El picker vive por INTENTO (una clave por token). El REGISTRO se mantiene compuesto hasta el
    // resultado o la cancelación: el picker externo pausa la intro, así que retirar el launcher en
    // ON_PAUSE perdería el resultado. Sólo el LANZAMIENTO espera al primer plano.
    val awaitingImport = (state as? AwaitingCapture)?.takeIf { it.ticket.kind == AngleEntryKind.IMPORT }
    if (awaitingImport != null) {
        key(awaitingImport.ticket.originToken) {
            AngleImportLauncher(
                navController = navController,
                entry = entry,
                ticket = awaitingImport.ticket,
                onResult = { token, uri ->
                    // Gap conocido tras S2C1F: el resultado se decide por identidad FRESCA de la intro.
                    // Acepta STARTED/CREATED (el resultado del Activity Result puede llegar antes de
                    // ON_RESUME); si otra entrada está delante (p. ej. Ayuda), la URI NO se completa:
                    // sólo se descarta el intento PENDIENTE de ese token para no quedar busy. Una URI
                    // ya aceptada (Acquiring/Ready/HandedOff) conserva su lease y nunca se cancela aquí.
                    if (navController.isCurrentEntry(entry)) {
                        handoff.completeResult(token, uri)
                    } else {
                        val fresh = handoff.state.value
                        if (fresh is AwaitingCapture && fresh.ticket.originToken == token) {
                            handoff.cancel(token)
                        }
                    }
                },
                onLaunchFailed = { token ->
                    handoff.cancel(token)
                    startBlocked = true
                },
            )
        }
    }

    // Observador ESTABLE de la entrada (sin clave de estado): sólo cuando la intro vuelve a ser la
    // entrada ACTUAL y está RESUMED se finaliza una transferencia reclamada. Se compara IDENTIDAD de
    // entrada, no el string de ruta. Un picker o una cámara en espera NO se cancelan aquí.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!navController.isForegroundEntry(entry)) return@LifecycleEventEffect
        val handedOff = handoff.state.value as? HandedOff ?: return@LifecycleEventEffect
        handoff.returnToIntro(handedOff.transfer.originToken)
    }
    // Importación: la URI elegida en el picker NO navega desde el callback. Cuando el actor ya es
    // dueño del grant y del atleta congelado (Ready) y esta entrada está en primer plano, se reclama
    // UNA vez y se abre el editor existente con la URI y el atleta de la TRANSFERENCIA.
    //
    // `introResumed` sólo DESPIERTA el efecto: la autoridad (`currentBackStackEntry === entry` +
    // RESUMED) se relee en el instante de reclamar, no de un snapshot que pudo quedar obsoleto.
    val introResumed = rememberEntryResumed(entry)
    val introOwnsForeground = { navController.isForegroundEntry(entry) }
    LaunchedEffect(state, introResumed) {
        if (!introOwnsForeground()) return@LaunchedEffect
        val ready = state as? Ready ?: return@LaunchedEffect
        val transfer = handoff.claimReady(ready.transfer.originToken) ?: return@LaunchedEffect
        navController.navigate(anglesEditorRoute(transfer.uri, transfer.athleteId))
    }

    AngleIntroScreen(
        onBack = {
            // Back explícito: abandona el intento pendiente ANTES de salir de la intro, así el actor
            // libera su lease de forma determinista y no queda un picker/cámara huérfano.
            if (introOwnsForeground()) {
                handoff.cancel()
                navController.popBackStack()
            }
        },
        onHowItWorks = {
            if (introOwnsForeground()) navController.navigate(ANGLES_HELP_ROUTE)
        },
        onRecord = {
            if (introOwnsForeground()) {
                startBlocked = false
                val ticket = handoff.begin(AngleEntryKind.RECORD)
                if (ticket != null) {
                    navController.navigate(
                        anglesCameraRoute(ticket.originToken, ticket.athleteId, ticket.requestId),
                    )
                }
            }
        },
        onImport = {
            if (introOwnsForeground()) {
                startBlocked = false
                handoff.begin(AngleEntryKind.IMPORT)
            }
        },
        onAthleteSelected = { athleteId ->
            // Lectura FRESCA del actor: elegir NO exige un id previo ni `canBegin`; sólo que el
            // intento no esté ocupado. La escritura global se hace únicamente con un atleta activo
            // aceptado, y sólo como consecuencia de una elección explícita en primer plano.
            if (introOwnsForeground()) {
                val fresh = handoff.state.value
                val selectable = fresh is Idle || fresh is Rejected
                if (selectable && athletes.any { it.id == athleteId } && handoff.selectAthlete(athleteId)) {
                    startBlocked = false
                    if (fresh is Rejected) dismissedRejection = fresh.requestId
                    scope.launch {
                        // Otra comprobación FRESCA justo antes de la escritura global: la corrutina
                        // puede ejecutarse después de un pop/pause del usuario.
                        if (introOwnsForeground()) {
                            app.selectedAthleteStore.select(app.athleteRepository, athleteId)
                        }
                    }
                }
            }
        },
        activeAthletes = athletes,
        athletesLoading = loading,
        selectedAthleteId = selectedAthleteId,
        startEnabled = idle && selectedIsActive,
        errorMessage = errorText,
        multiFrameEnabled = multiFrameEnabled,
    )
}

/** Picker externo de importación: una instancia y un lanzamiento por ticket de origen. */
@Composable
private fun AngleImportLauncher(
    navController: NavHostController,
    entry: NavBackStackEntry,
    ticket: AngleEntryTicket,
    onResult: (String, String?) -> Unit,
    onLaunchFailed: (String) -> Unit,
) {
    val launched = rememberSaveable(ticket.originToken) { mutableStateOf(false) }
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnFailure by rememberUpdatedState(onLaunchFailed)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // Ticket INMUTABLE del intento que abrió el picker: un resultado viejo no puede asociarse
        // con la petición más reciente.
        currentOnResult(ticket.originToken, uri?.toString())
    }
    // `introResumed` despierta el efecto; la autoridad fresca se relee justo antes de lanzar.
    val introResumed = rememberEntryResumed(entry)
    LaunchedEffect(ticket.originToken, introResumed) {
        if (launched.value) return@LaunchedEffect
        // No se abre la actividad externa desde una entrada que ya no está delante.
        if (!navController.isForegroundEntry(entry)) return@LaunchedEffect
        // Marcar ANTES de lanzar y SÓLO si se lanza de verdad: la actividad externa puede matar y
        // recrear esta composición, y entonces el resultado debe seguir siendo de este ticket.
        launched.value = true
        val failure = runCatching {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
            )
        }
        if (failure.isFailure) {
            // Un fallo de `launch` no llegó a abrir nada: se retira la marca y se informa al caller.
            launched.value = false
            currentOnFailure(ticket.originToken)
        }
    }
}

/** Ayuda de metodología: misma entrada de la intro (su modelo y su selección siguen vivos). */
@Composable
internal fun AnglesHelpDestination(
    navController: NavHostController,
    multiFrameEnabled: Boolean,
) {
    AngleHowItWorksScreen(
        onBack = { navController.popBackStack() },
        multiFrameEnabled = multiFrameEnabled,
    )
}

/**
 * Cámara propia de Ángulos sobre el contrato real de [CameraScreen] en contexto `Standalone`.
 *
 * No navega desde el listener de captura: la URI sólo se entrega al actor, que publica `Ready`
 * cuando ya es dueño del grant y del atleta congelado. La navegación al editor ocurre entonces,
 * una sola vez, reclamando (`claimReady`) de forma atómica y con la URI y el atleta de la
 * transferencia, nunca del atleta global.
 */
@Composable
internal fun AnglesCameraDestination(
    navController: NavHostController,
    entry: NavBackStackEntry,
) {
    val request = parseAnglesCameraRequest(
        token = entry.arguments?.getString(ANGLES_CAMERA_TOKEN_ARG),
        athleteId = entry.arguments?.getString(ANGLES_CAMERA_ATHLETE_ID_ARG),
        requestId = entry.arguments?.getString(ANGLES_CAMERA_REQUEST_ID_ARG),
    )
    // La entrada de la intro es el dueño del intento. Si no existe, esta ruta no puede ser válida.
    val introEntry = remember(navController) {
        runCatching { navController.getBackStackEntry(ANGLES_INTRO_ROUTE) }.getOrNull()
    }
    if (request == null || introEntry == null) {
        // Fallo cerrado ANTES de componer la cámara: no se pide permiso ni se abre proveedor.
        LaunchedEffect(Unit) { navController.popBackStack(ANGLES_CAMERA_ROUTE, inclusive = true) }
        return
    }
    val handoff: AngleEntryHandoffViewModel =
        viewModel(viewModelStoreOwner = introEntry, factory = AngleEntryHandoffViewModel.Factory)
    val state by handoff.state.collectAsStateWithLifecycle()
    // `resumed` sólo DESPIERTA el efecto; la autoridad se relee fresca en cada paso.
    val resumed = rememberEntryResumed(entry)
    // Escena autenticada por el ACTOR como intento RECORD propio (ticket/transferencia con token y
    // kind). `Releasing`/`Rejected` sólo traen el requestId: si la escena ya se autenticó antes, se
    // conserva compuesta durante el cierre transitorio (no se recrea recorder ni se vuelve a pedir
    // permiso); si NUNCA se autenticó (p. ej. un requestId que coincide con un intento IMPORT ajeno)
    // no se compone cámara ni se pide permiso por primera vez.
    val sceneAuthenticated = anglesCameraSceneAuthenticated(state, request)
    var authenticatedScene by remember(entry, request) { mutableStateOf(false) }
    // Recuerdo de escena YA autenticada, fijado DESPUÉS de componer (sin escribir estado durante la
    // composición): así un cierre transitorio no destruye una cámara que el actor validó.
    SideEffect { if (sceneAuthenticated) authenticatedScene = true }
    val showCamera = sceneAuthenticated || (state is Releasing && authenticatedScene)
    val ownsIntake = anglesCameraRouteOwnsIntake(state, request)
    val captureGuidance = stringResource(R.string.angle_intro_tips)

    // Rama terminal del intento: el error se muestra en la introducción, nunca al atleta global.
    LaunchedEffect(state, resumed) {
        // Identidad FRESCA antes de popear o reclamar: ni una entrada que ya no es la actual ni una
        // que sólo está STARTED/CREATED puede actuar en nombre de esta cámara.
        if (!navController.isCurrentEntry(entry)) return@LaunchedEffect
        if (!ownsIntake) {
            navController.popBackStack(ANGLES_CAMERA_ROUTE, inclusive = true)
            return@LaunchedEffect
        }
        if (entry.lifecycle.currentState != Lifecycle.State.RESUMED) return@LaunchedEffect
        when (val current = state) {
            is Ready -> if (current.transfer.originToken == request.token) {
                // Reclamo atómico ANTES de navegar: la transferencia sólo se entrega una vez.
                val transfer = handoff.claimReady(request.token) ?: return@LaunchedEffect
                navController.navigate(anglesEditorRoute(transfer.uri, transfer.athleteId)) {
                    // La cámara sale de la pila: Back desde el editor vuelve a la introducción.
                    popUpTo(ANGLES_CAMERA_ROUTE) { inclusive = true }
                }
            }
            is Rejected -> if (current.requestId == request.requestId) {
                navController.popBackStack(ANGLES_CAMERA_ROUTE, inclusive = true)
            }
            else -> Unit
        }
    }

    if (showCamera) {
        CameraScreen(
            onSaved = { uri, _ ->
                // Mismo ticket RECORD congelado; la navegación la decide el estado `Ready`. La URI
                // sólo se acepta con esta entrada VIVA (tolera STARTED/CREATED: `onSaved` puede
                // llegar antes de ON_RESUME). Un callback tardío tras el pop se ignora y NUNCA
                // cancela un intento ya entregado (`HandedOff`), cuyo lease protege al editor.
                if (navController.isCurrentEntry(entry)) {
                    handoff.completeResult(request.token, uri.toString())
                }
            },
            onBack = {
                if (navController.isForegroundEntry(entry)) {
                    // Sólo se cancela el intento pendiente propio: nunca una transferencia ya
                    // reclamada (`HandedOff`), cuyo lease protege la apertura asíncrona del editor
                    // tras el record-nav. `cancel` ya ignora tokens ajenos y cierres en curso.
                    val freshState = handoff.state.value
                    if (freshState !is HandedOff) handoff.cancel(request.token)
                    navController.popBackStack()
                }
            },
            captureGuidance = captureGuidance,
            // Fallback de importación desde el error de cámara: completa el MISMO ticket RECORD;
            // nunca inicia un intento IMPORT mientras hay uno pendiente.
            onImportVideo = { uri ->
                if (navController.isCurrentEntry(entry)) {
                    handoff.completeResult(request.token, uri.toString())
                }
            },
            captureContext = CameraCaptureContext.Standalone,
            canStartNewVideo = {
                canStartNewAngleCapture(
                    state = handoff.state.value,
                    request = request,
                    ownEntryResumed = entry.lifecycle.currentState == Lifecycle.State.RESUMED,
                )
            },
        )
    }
}

/** Estado de ciclo de vida observado de una entrada de navegación (para navegar sólo en RESUMED). */
@Composable
private fun rememberEntryResumed(entry: NavBackStackEntry): Boolean {
    var resumed by remember(entry) {
        mutableStateOf(entry.lifecycle.currentState == Lifecycle.State.RESUMED)
    }
    DisposableEffect(entry) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> resumed = false
                else -> Unit
            }
        }
        entry.lifecycle.addObserver(observer)
        onDispose { entry.lifecycle.removeObserver(observer) }
    }
    return resumed
}
