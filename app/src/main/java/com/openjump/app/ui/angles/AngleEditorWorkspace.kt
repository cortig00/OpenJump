package com.openjump.app.ui.angles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.openjump.app.R
import com.openjump.app.angles.AngleDiscardConfirmation
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleFrameId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AnglePoint
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.selector.AnalysisVideoSurface
import com.openjump.app.ui.selector.CropViewportState
import com.openjump.app.ui.selector.MIN_CROP_ZOOM
import com.openjump.app.ui.selector.SportsTimeline
import com.openjump.app.ui.selector.ViewportSize
import com.openjump.app.ui.selector.VideoCoordinateMapper
import com.openjump.app.ui.selector.VideoScaleMode
import com.openjump.app.ui.selector.VideoTransportControls
import com.openjump.app.ui.theme.Spacing
import java.text.NumberFormat

internal const val ANGLE_WORKSPACE_TAG = "angle-workspace"
internal const val ANGLE_WORKSPACE_VIDEO_TAG = "angle-workspace-video"
internal const val ANGLE_WORKSPACE_VIDEO_TILE_TAG = "angle-workspace-video-tile"
internal const val ANGLE_WORKSPACE_CAPTURE_TAG = "angle-workspace-capture"
internal const val ANGLE_WORKSPACE_SAVE_TAG = "angle-workspace-save"
internal const val ANGLE_WORKSPACE_ADD_ANGLE_TAG = "angle-workspace-add-angle"
internal const val ANGLE_WORKSPACE_TIMELINE_TAG = "angle-workspace-timeline"
internal const val ANGLE_WORKSPACE_REMOVE_FRAME_TAG = "angle-workspace-remove-frame"
internal const val ANGLE_WORKSPACE_REMOVE_CONFIRM_TAG = "angle-workspace-remove-confirm"
internal const val ANGLE_WORKSPACE_REMOVE_CANCEL_TAG = "angle-workspace-remove-cancel"
internal const val ANGLE_WORKSPACE_GUIDANCE_TAG = "angle-workspace-guidance"
internal const val ANGLE_WORKSPACE_PRECISION_TAG = "angle-workspace-precision"
internal const val ANGLE_WORKSPACE_FULLSCREEN_TAG = "angle-workspace-fullscreen"
internal const val ANGLE_WORKSPACE_EXIT_FULLSCREEN_TAG = "angle-workspace-exit-fullscreen"
internal const val ANGLE_WORKSPACE_OVERFLOW_TAG = "angle-workspace-overflow"
internal const val ANGLE_WORKSPACE_REMOVE_MENU_TAG = "angle-workspace-remove-menu"
internal const val ANGLE_WORKSPACE_SOURCE_ZOOM_IN_TAG = "angle-workspace-source-zoom-in"
internal const val ANGLE_WORKSPACE_SOURCE_ZOOM_OUT_TAG = "angle-workspace-source-zoom-out"
internal const val ANGLE_WORKSPACE_SOURCE_ZOOM_RESET_TAG = "angle-workspace-source-zoom-reset"

/**
 * Workspace vídeo-primero del editor de ángulos (rama nueva del gate V2, análisis **nuevo**).
 *
 * Funcionalidad de este slice: el vídeo grande con su transporte y su timeline de PTS reales, la
 * preparación perezosa una sola vez de la copia sellada, la captura del fotograma pausado mediante
 * el ViewModel, el lienzo congelado con la guía A/B/C, un único guardado para todos los fotogramas y
 * la guarda de cierre compartida con el editor legacy.
 *
 * El transporte y el timeline viven **solo** dentro del vídeo en directo: una foto congelada no
 * reproduce, no avanza ni busca. La fase del lienzo se deriva del editor canónico con
 * [angleWorkspaceInteractionMode] y el callback del toque la vuelve a evaluar con el estado que el
 * ViewModel tiene en ese instante, de modo que el cuarto toque de una terna nunca coloca otro A.
 *
 * Incluye ya el **inspector de alta precisión** ([AngleWorkspaceInspector]): el botón «Precisión»
 * de la foto congelada y la acción personalizada del Canvas abren el MISMO panel modal, sin panel
 * permanente que le quite sitio al vídeo.
 *
 * Deliberadamente **no** incluye lo que queda para S4: miniaturas con encuadre/crop/pinch, zoom,
 * vista apaisada ni orientación. Tampoco crea player, indexador ni almacén: todo el estado y todos
 * los guards son los del [AngleEditorViewModel] existente. La selección de fotograma es la de la
 * sesión ([AngleMultiFrameSessionState.activeFrameId]); esta pantalla no mantiene una segunda
 * autoridad local de vídeo/fotograma.
 *
 * @param onCloseRequest salida ya guardada por la política de cierre del llamante (diálogo incluido).
 *   El workspace solo la invoca cuando no hay un guardado en vuelo.
 * @param windowHost puerto de ventana inyectable. En producción se resuelve el de la Activity real;
 *   los tests de layout pasan un fake explícito (ComponentActivity no tiene `configChanges`).
 */
@Composable
internal fun AngleEditorWorkspace(
    viewModel: AngleEditorViewModel,
    onCloseRequest: () -> Unit,
    modifier: Modifier = Modifier,
    windowHost: AngleWorkspaceWindowHost? = null,
) {
    val videoState by viewModel.videoState.collectAsState()
    val multiFrame by viewModel.multiFrameState.collectAsState()
    val saveState by viewModel.multiFrameSaveState.collectAsState()
    val timelineMapper by viewModel.timelineMapper.collectAsState()
    val displayState by viewModel.displayState.collectAsState()
    val player by viewModel.player.collectAsState()
    val videoGeometry by viewModel.videoGeometry.collectAsState()

    val videoReady = videoState is AngleVideoState.Ready
    val saving = saveState is AngleMultiFrameSaveState.Saving
    // Preparación perezosa UNA vez al quedar el vídeo listo. Un fallo se queda visible con su
    // reintento explícito: nunca se reintenta en bucle ni se captura nada por detrás.
    var prepareRequested by remember(viewModel) { mutableStateOf(false) }
    LaunchedEffect(videoReady) {
        if (videoReady && !prepareRequested) {
            prepareRequested = true
            viewModel.openMultiFrameSession()
        }
    }
    // Única guarda de atrás de esta rama: se instala al final del estado local (abajo), con la
    // precedencia guardado > modal > fullscreen > salida del llamante.

    val session = (multiFrame as? AngleMultiFrameState.Ready)?.state
    val activeFrameId = session?.activeFrameId
    val active = activeFrameId?.let { session.frame(it) }
    val editorState: AngleEditorState? = active?.editor
    // Confirmación REAL del frame base dibujado (ancla+generación, en pausa) que valida el preview
    // local del arrastre; nunca se inventa un frame ni un ancla distinta.
    val frameConfirmation = remember(active?.anchor, session?.generation) {
        val anchor = active?.anchor ?: return@remember null
        val generation = session?.generation ?: return@remember null
        AngleFrameConfirmation(frame = anchor, generation = generation, paused = true)
    }
    val draftPoints = editorState?.draft?.points.orEmpty()
    var notice by remember(viewModel) { mutableStateOf<AngleMultiFrameNotice?>(null) }
    // Intención explícita de «Otro ángulo». La fase de dibujo se deriva de ella y del editor real;
    // no es un modo recordado que pueda quedarse en PLACEMENT tras completar una terna.
    var addingAngle by remember(activeFrameId) { mutableStateOf(false) }
    // Última instantánea que una operación aceptada devolvió. El puente del ViewModel publica en un
    // colector: si un toque llega antes de esa réplica, esta instantánea es más reciente y evita
    // que el cuarto toque se evalúe contra una terna aún incompleta.
    var lastAppliedSession by remember(activeFrameId) {
        mutableStateOf<AngleMultiFrameSessionState?>(null)
    }
    // Fotograma cuya eliminación se ha pedido: la confirmación se resuelve en un diálogo real y,
    // si se cancela, no se toca la sesión (cero cambios).
    var removeRequested by remember(activeFrameId) { mutableStateOf<AngleFrameId?>(null) }
    // Inspector de alta precisión: panel modal con cursor accesible y la lista real de ángulos.
    // Vive en composición, no en el ViewModel: no es un segundo estado del editor.
    var inspectorVisible by remember(activeFrameId) { mutableStateOf(false) }
    // Fullscreen local del workspace: oculta chrome, NO cambia instante/puntos/guardado. Al salir
    // (botón ≥48dp o Atrás) se vuelve al workspace normal sin pérdida.
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var overflowOpen by remember(activeFrameId) { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // Ocupación de gestos del workspace: preview de edición sin confirmar y latch multitáctil de
    // encuadre. Inhibe OTRAS acciones (guardar, medir, seleccionar fotograma, abrir inspector),
    // nunca el canvas/arena que crean ese estado ni sus propios callbacks de movimiento.
    var overlayPreviewActive by remember(activeFrameId) { mutableStateOf(false) }
    var viewportGestureActive by remember(activeFrameId) { mutableStateOf(false) }
    // Getter de estado VIVO: las lambdas (Save/Medir/seleccionar/abrir inspector) se recomponen
    // antes que este ámbito, así que un `val` capturado se queda obsoleto hasta la recomposición y
    // dejaba ejecutar acciones con un preview pendiente. La función lee los dos `MutableState` en el
    // instante del callback; `enabled` puede seguir usando el valor de composición.
    fun workspaceGestureBusyNow(): Boolean = overlayPreviewActive || viewportGestureActive
    val workspaceGestureBusy = workspaceGestureBusyNow()
    // Encuadre real hoisted FUERA de las ramas responsive: fuente (vídeo) y por FrameId (fotos).
    // Cambiar de miniatura no pierde el encuadre de las demás; la captura nueva copia el de la
    // fuente y FrameAlreadyTracked nunca lo sobrescribe.
    //
    // Clave del encuadre de la FUENTE: la instancia del ViewModel, que atiende exactamente un vídeo
    // (una segunda apertura con otro uri responde AlreadyOpen). NO se usa el `sourceIdentity` de la
    // sesión: la sesión nace después (Opening→Ready) y ese cambio borraba el zoom que el usuario
    // acababa de hacer durante «Preparando» sobre el MISMO vídeo.
    var sourceCrop by remember(viewModel) {
        mutableStateOf(angleWorkspaceResetCrop)
    }
    // Encuadre por fotograma: clave la ÉPOCA real de la sesión (fuente + generación), no solo el id.
    // Dos renders distintos pueden reutilizar el mismo id y no deben compartir encuadre.
    var cropsByFrame by remember(session?.sourceIdentity, session?.generation) {
        mutableStateOf(emptyMap<AngleFrameId, CropViewportState>())
    }
    val crops = AngleWorkspaceCrops(sourceCrop, cropsByFrame)
    val activeCrop = crops.forFrame(activeFrameId)
    fun updateCrop(next: CropViewportState) {
        if (activeFrameId == null) {
            sourceCrop = next
        } else {
            cropsByFrame = cropsByFrame + (activeFrameId to next)
        }
    }
    // Única guarda de atrás de esta rama. Precedencia real S4a: guardado en vuelo > diálogo/modal >
    // fullscreen > salida del llamante (que conserva su diálogo de descarte y las recomprobaciones
    // frescas). No se instala un segundo BackHandler en ninguna rama.
    BackHandler {
        when {
            saving -> Unit
            removeRequested != null -> removeRequested = null
            inspectorVisible -> inspectorVisible = false
            fullscreen -> fullscreen = false
            else -> onCloseRequest()
        }
    }
    // Época del fotograma DIBUJADO en la etapa: id + fuente + generación del render que lo
    // contiene. Dos sesiones pueden reutilizar el mismo id, así que las claves del lease incluyen
    // la época: al cambiar, el estado arranca vacío (no se dibuja el lease anterior) y el efecto
    // readquiere. `version`/`leaseCount` no sirven de clave: no identifican píxeles.
    val stageSourceIdentity = session?.sourceIdentity
    val stageGeneration = session?.generation
    var lease by remember(activeFrameId, stageSourceIdentity, stageGeneration) {
        mutableStateOf<AngleMultiFrameBitmapLease?>(null)
    }
    val activeCanEdit = active?.canEdit == true
    DisposableEffect(activeFrameId, activeCanEdit, stageSourceIdentity, stageGeneration) {
        // La instancia EXACTA adquirida por este efecto es la que se cierra en su onDispose; la
        // variable de estado solo se usa para pintar y nunca decide qué lease se libera.
        var acquired: AngleMultiFrameBitmapLease? = null
        if (activeFrameId != null && activeCanEdit) {
            when (val result = viewModel.acquireMultiFrameBitmap(activeFrameId)) {
                is AngleMultiFrameBitmapResult.Granted -> {
                    acquired = result.lease
                    lease = result.lease
                }

                is AngleMultiFrameBitmapResult.Rejected -> {
                    lease = null
                    notice = angleMultiFrameNotice(result.rejection)
                }
            }
        } else {
            // Sin fotograma editable no se conserva ningún bitmap retenido (no se copia nada).
            lease = null
        }
        onDispose {
            if (lease === acquired) lease = null
            acquired?.close()
        }
    }
    val openLease = lease?.takeIf { it.isOpen }
    val imageGeometry = openLease?.imageGeometry
    val mapper = remember(imageGeometry, viewport, activeCrop) {
        angleWorkspaceMapper(imageGeometry, viewport.width, viewport.height, activeCrop)
    }
    // Mapper de la fuente EN DIRECTO: mismo encuadre/escala que el resto, sin player ni índice
    // nuevos. Sin zoom aplicado es exactamente FIT 1x.
    val sourceMapper = remember(videoGeometry, viewport, sourceCrop) {
        angleWorkspaceMapper(videoGeometry, viewport.width, viewport.height, sourceCrop)
    }
    // Plataforma real por defecto; fake explícito solo en tests de layout (sin fingir rotación).
    val context = LocalContext.current
    // Geometría de la MEDIA (ya postrotación), no de la ventana: foto congelada si hay fotograma
    // activo, vídeo en directo si no. Es la que decide si el fullscreen debe pedir landscape.
    // Durante lease pendiente/no disponible se conserva la geometría verificada de LA MISMA fuente
    // (vídeo que el VM abrió una única vez): evita que el fullscreen caiga a FOLLOW_USER en
    // horizontal y rebote a LANDSCAPE al llegar el lease. Nunca se arrastra geometría vieja de
    // otra época/fuente.
    val presentationGeometry = if (activeFrameId == null) videoGeometry else imageGeometry
        ?: videoGeometry
    val resolvedWindowHost = remember(context, windowHost) {
        windowHost ?: angleWorkspaceWindowHost(context)
    }
    val windowLease = remember(resolvedWindowHost) {
        resolvedWindowHost?.let { AngleWorkspacePresentationLease(it) }
    }
    // Adquirir SOLO en RESUMED (no por entrar en composición durante una transición) y respetar
    // la orientación del usuario; fullscreen con media horizontal pide USER_LANDSCAPE explícito.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        windowLease?.onResumed(fullscreen, presentationGeometry)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        windowLease?.onPaused()
    }
    LaunchedEffect(windowLease, fullscreen, presentationGeometry) {
        windowLease?.update(fullscreen, presentationGeometry)
    }
    DisposableEffect(windowLease) {
        onDispose { windowLease?.dispose() }
    }

    /** Instantánea más reciente entre la que publicó el puente y la que devolvió la última acción. */
    fun freshestSession(): AngleMultiFrameSessionState? = freshestMultiFrameSession(
        published = viewModel.multiFrameState.value,
        applied = lastAppliedSession,
    )

    fun applied(result: AngleMultiFrameResult, frameId: AngleFrameId?): Boolean = when (result) {
        is AngleMultiFrameResult.Applied -> {
            notice = null
            lastAppliedSession = result.state
            // Fase reevaluada con el estado canónico recién aplicado: terna completa ⇒ EDIT (y se
            // retira la intención de añadir); A/B pendientes ⇒ sigue PLACEMENT y no se descarta nada.
            val fresh = frameId?.let { result.state.frame(it)?.editor }
            if (angleWorkspaceInteractionMode(fresh, addingAngle = false) == AngleOverlayMode.EDIT) {
                addingAngle = false
            }
            true
        }

        is AngleMultiFrameResult.Rejected -> {
            notice = angleMultiFrameNotice(result.rejection)
            false
        }
    }

    fun report(result: AngleMultiFrameResult) {
        when (result) {
            is AngleMultiFrameResult.Applied -> notice = null
            is AngleMultiFrameResult.Rejected -> notice = angleMultiFrameNotice(result.rejection)
        }
    }

    /**
     * Captura del fotograma pausado, compartida por el botón del workspace y el overlay fullscreen.
     * Al aplicar una captura NUEVA copia el encuadre normalizado de la fuente; si el fotograma ya
     * existía (FrameAlreadyTracked) solo se selecciona y NUNCA se sobrescribe su encuadre.
     */
    fun requestSave() {
        if (saving || workspaceGestureBusyNow()) return
        viewModel.saveMultiFrame()
    }

    fun openPrecision() {
        if (!saving && !workspaceGestureBusyNow()) inspectorVisible = true
    }

    fun performCapture() {
        // La captura es SIEMPRE de la fuente en directo. Si entre el toque y el callback la sesión
        // fresca ya congeló un fotograma (activeFrameId != null), esta escena ya no es la fuente y
        // la captura se descarta en vez de capturar a ciegas.
        // Un preview/encuadre ajeno en curso también la bloquea (live): no se captura con geometría
        // provisional en pantalla. La propia arena/capa nunca llaman aquí, así que no se bloquean.
        if (workspaceGestureBusyNow()) return
        val published = viewModel.multiFrameState.value
        if (published is AngleMultiFrameState.Ready && published.state.activeFrameId != null) return
        notice = null
        when (val result = viewModel.captureMultiFrame()) {
            is AngleMultiFrameResult.Applied -> {
                notice = null
                val newFrameId = result.state.activeFrameId
                if (newFrameId != null) {
                    cropsByFrame = angleWorkspaceRegisterCapture(
                        crops = AngleWorkspaceCrops(sourceCrop, cropsByFrame),
                        frameId = newFrameId,
                        alreadyTracked = false,
                    ).byFrame
                }
            }

            is AngleMultiFrameResult.Rejected -> {
                val rejection = result.rejection
                if (rejection is AngleMultiFrameRejection.FrameAlreadyTracked) {
                    // Mismo fotograma ya congelado: se selecciona el EXISTENTE sin recapturar, sin
                    // tocar sus ángulos y sin sobrescribir su encuadre previo.
                    report(viewModel.selectMultiFrame(rejection.frameId))
                } else {
                    notice = angleMultiFrameNotice(rejection)
                }
            }
        }
    }

    val saveAvailability = angleMultiFrameSaveAvailability(
        // El llamante solo monta esta rama con el gate V2 inyectado activo; el ViewModel vuelve a
        // aplicar sus guards, así que este `true` no habilita ninguna escritura por sí mismo.
        gateEnabled = true,
        multiFrame = multiFrame,
        saveState = saveState,
    )
    val saveStatus = angleMultiFrameSaveStatusNotice(saveState)
    // Grados como el editor legacy: formato del locale actual de la app con UN decimal, nunca
    // `Double.toString` (17 dígitos) ni un Locale forzado.
    val locale = currentAppLocale()
    val numberFormat = remember(locale) {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    }
    val degreesFormat = stringResource(R.string.angle_degrees_value)
    val formatDegrees: (Double) -> String = { degrees ->
        degreesFormat.format(numberFormat.format(degrees))
    }
    val timeline = timelineMapper
    val overlayMode = angleWorkspaceInteractionMode(editorState, addingAngle)
    val captureEnabled = videoReady && displayState.canMark && session != null &&
        session.capturingFrame == null && active?.isCapturing != true && !saving
    val captureHint = when {
        saving -> null
        !videoReady -> null
        !displayState.canMark -> stringResource(R.string.angle_workspace_capture_hint)
        else -> null
    }
    // Estado honesto del vídeo: un fallo o un cierre no se quedan en «preparando» para siempre.
    val videoMessageRes: Int? = when (val current = videoState) {
        AngleVideoState.Idle, AngleVideoState.Opening -> R.string.angle_video_preparing
        AngleVideoState.AlreadyOpen -> R.string.angle_video_already_open
        AngleVideoState.Closed -> R.string.angle_video_closed
        is AngleVideoState.Unavailable -> when (current.reason) {
            AngleVideoUnavailableReason.URI_UNAVAILABLE -> R.string.angle_video_uri_unavailable
            AngleVideoUnavailableReason.INDEX_FAILED -> R.string.angle_video_index_failed
        }

        is AngleVideoState.Ready -> null
    }
    val videoFailed = videoState is AngleVideoState.Unavailable ||
        videoState is AngleVideoState.Closed || videoState is AngleVideoState.AlreadyOpen
    val frames = session?.frames.orEmpty()
    val framesExist = frames.isNotEmpty()
    val sessionCaptureFailure = active?.captureFailure
    val capturingFrame = session?.capturingFrame

    // Identidad del fotograma que esta composición está DIBUJANDO: id + fuente/generación de la
    // sesión + fotograma del lease abierto. Cualquier acción de geometría se valida contra ella.
    val renderedBinding = remember(
        activeFrameId,
        session?.sourceIdentity,
        session?.generation,
        openLease,
    ) {
        angleRenderedFrameBinding(
            activeFrameId = activeFrameId,
            sourceIdentity = session?.sourceIdentity,
            generation = session?.generation,
            leaseFrameId = openLease?.frameId,
            leaseOpen = openLease?.isOpen == true,
        )
    }

    /**
     * Fotograma al que se puede aplicar una acción de geometría AHORA mismo, o `null` para
     * ignorarla. El estado fresco se compara con el dibujado: si cambió el fotograma, la fuente o la
     * generación, nunca se redirige la acción a otro fotograma.
     *
     * El lease se **relee en el callback** (`isOpen`, `frameId`) en lugar de fiarse del `Boolean`
     * recordado en la composición: entre el toque y su callback el lease pudo cerrarse, y entonces el
     * mapper que produjo el punto ya no corresponde a ningún píxel vivo.
     */
    fun geometryTarget(): AngleFrameId? {
        val liveLease = lease?.takeIf { it.isOpen } ?: return null
        val drawn = angleRenderedFrameBinding(
            activeFrameId = activeFrameId,
            sourceIdentity = session?.sourceIdentity,
            generation = session?.generation,
            leaseFrameId = liveLease.frameId,
            leaseOpen = true,
        ) ?: return null
        if (drawn != renderedBinding) return null
        return angleFrameActionTarget(
            rendered = drawn,
            fresh = angleFreshFrameBinding(freshestSession(), drawn.frameId),
        )
    }

    Box(modifier = modifier.testTag(ANGLE_WORKSPACE_TAG)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!fullscreen) {
                OpenJumpTopAppBar(
                    title = stringResource(R.string.angle_workspace_title),
                    onNavigationClick = { if (!saving) onCloseRequest() },
                    navigationContentDescription = stringResource(R.string.angle_workspace_close),
                    actions = {
                        TextButton(
                            onClick = { fullscreen = true },
                            modifier = Modifier
                                .heightIn(min = Spacing.xxxl)
                                .testTag(ANGLE_WORKSPACE_FULLSCREEN_TAG),
                        ) {
                            Text(stringResource(R.string.angle_workspace_fullscreen))
                        }
                    },
                )
            }
            // Plan derivado de constraints REALES; en fullscreen la media ocupa todo el espacio.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val plan = angleWorkspaceLayoutPlan(
                    widthDp = maxWidth.value.toInt(),
                    heightDp = maxHeight.value.toInt(),
                    framesExist = framesExist,
                    fullscreen = fullscreen,
                    fontScale = LocalDensity.current.fontScale,
                )
                val mediaStage: @Composable (Modifier) -> Unit = { stageModifier ->
            // --- vídeo protagonista con SU transporte y SU timeline -----------------------------
                    Box(
                        modifier = stageModifier
                    .background(Color.Black)
                    // El zoom/pan de imagen y overlay nunca inunda header/footer/controles.
                    .clipToBounds()
                    .onSizeChanged { viewport = it }
                    .testTag(ANGLE_WORKSPACE_VIDEO_TAG),
                contentAlignment = Alignment.Center,
            ) {
                if (activeFrameId == null) {
                    // Inner content SOLO con la superficie de vídeo + arena de dos dedos: los HUD,
                    // transporte y timeline quedan como hermanos FUERA (no roban el slider).
                    // La arena es el CONTENEDOR real de la superficie (content), no un hermano
                    // transparente encima: si fuera hermana, el hit-test le entregaría todos los
                    // DOWN y el overlay nunca recibiría nada.
                    Box(modifier = Modifier.fillMaxSize()) {
                        val videoSurface: @Composable () -> Unit = {
                            AnalysisVideoSurface(
                                player = player,
                                // Mismo mapper/encuadre que el resto del workspace; FIT 1x sin zoom.
                                coordinateMapper = sourceMapper,
                                scaleMode = VideoScaleMode.FIT,
                                // Sin capa de anotación sobre vídeo vivo: las marcas solo existen en
                                // una foto congelada. El visor no interpreta gestos de puntos.
                                onRefreshFrame = { viewModel.viewer.refreshCurrentFrame() },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (videoReady && !saving) {
                            AngleWorkspaceFramingArena(
                                mapperProvider = { sourceMapper },
                                interactionKey = viewModel,
                                enabled = true,
                                onFramingGesture = { centroid, zoomChange, panDx, panDy ->
                                    sourceMapper?.let { current ->
                                        updateCrop(
                                            current.stateAfterGesture(centroid, zoomChange, panDx, panDy),
                                        )
                                    }
                                },
                                onBusyChanged = { viewportGestureActive = it },
                                content = { videoSurface() },
                            )
                        } else {
                            videoSurface()
                        }
                    }
                    // Source HUD: zoom± y reset accesibles con objetivos de 48dp. Solo sobre el
                    // vídeo en directo; la foto usa el encuadre de su inspector.
                    AngleSourceZoomControls(
                        zoom = sourceCrop.zoom,
                        onZoomIn = {
                            updateCrop(angleWorkspaceNextCrop(sourceCrop, zoomIn = true))
                        },
                        onZoomOut = {
                            updateCrop(angleWorkspaceNextCrop(sourceCrop, zoomIn = false))
                        },
                        onReset = { updateCrop(angleWorkspaceResetCrop) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(Spacing.sm),
                    )
                    // HUD del visor: solo sobre el vídeo en directo. Una foto congelada no tiene
                    // play/paso/búsqueda porque no hay reproducción que controlar.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(bottom = Spacing.xs),
                    ) {
                        VideoTransportControls(
                            isPlaying = displayState.isPlaying,
                            enabled = videoReady && !saving,
                            onPreviousFrame = { viewModel.stepFrames(-1) },
                            onTogglePlayback = { viewModel.togglePlayback() },
                            onNextFrame = { viewModel.stepFrames(1) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.xs),
                        )
                        if (timeline != null) {
                            SportsTimeline(
                                mapper = timeline,
                                selectedIndex = displayState.renderedIndex
                                    ?: displayState.requestedIndex,
                                enabled = videoReady && !saving,
                                markers = emptyList(),
                                onPreviewFrame = {},
                                onScrubStart = { viewModel.beginScrubbing() },
                                onScrubToFrame = { index -> viewModel.scrubToFrame(index) },
                                onScrubEnd = { viewModel.endScrubbing() },
                                // Tiempo REAL renderizado por el visor; sin FPS ni objetivo fingido.
                                selectedTimeLabel = displayState.renderedPtsUs
                                    ?.let { Formatting.usToClock(it) } ?: "--",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.screenHorizontal)
                                    .testTag(ANGLE_WORKSPACE_TIMELINE_TAG),
                            )
                        }
                    }
                } else {
                    // La foto congelada + su capa de edición viven DENTRO de la arena: el detector
                    // de dos dedos es el padre (pase Initial) y el overlay recibe los toques de un
                    // dedo en Main. Los botones/HUD siguen siendo hermanos fuera.
                    val frozenCanvas: @Composable () -> Unit = {
                    AngleFrozenCanvas(
                        lease = openLease,
                        frameIndex = active?.anchor?.frameIndex,
                        placeholder = when {
                            active == null -> AngleFrozenCanvasPlaceholder.NoActiveFrame
                            openLease != null -> AngleFrozenCanvasPlaceholder.None
                            active.isCapturing -> AngleFrozenCanvasPlaceholder.Capturing
                            else -> AngleFrozenCanvasPlaceholder.ImagePending
                        },
                        editor = editorState,
                        mapper = mapper,
                        mode = overlayMode,
                        enabled = activeCanEdit && !saving,
                        showsGeometry = editorState != null && active != null &&
                            (editorState.anchor == null ||
                                editorState.showsGeometryAt(active.anchor)),
                        accessibilityDescription = editorState?.let {
                            angleOverlayDescription(
                                editor = it,
                                selectedAngle = it.selectedAngleId?.let { id ->
                                    it.angles.firstOrNull { angle -> angle.id == id }
                                },
                                formatDegrees = formatDegrees,
                            )
                        },
                        formatDegrees = formatDegrees,
                        // Acción personalizada del Canvas: abre EXACTAMENTE el mismo inspector que
                        // el botón «Precisión», sin depender de encontrar el botón.
                        accessibleControlsLabel = stringResource(R.string.angle_workspace_precision),
                        onAccessibleControlsRequested = {
                            if (!saving && !workspaceGestureBusyNow()) inspectorVisible = true
                        },
                        framingDescription = stringResource(R.string.angle_framing_hint),
                        onFramingGesture = { _, _, _, _ -> },
                        onSelectAngle = { angleId ->
                            geometryTarget()?.let {
                                val result = viewModel.selectMultiFrameAngle(it, angleId)
                                // Selección Applied limpia SOLO la intención de «Otro ángulo»;
                                // el borrador A/B se conserva para poder volver a él.
                                if (result is AngleMultiFrameResult.Applied) addingAngle = false
                                report(result)
                            }
                        },
                        onPlacePoint = { point: AnglePoint ->
                            // El punto viene calculado con el mapper del fotograma DIBUJADO: solo se
                            // aplica si el binding sigue siendo el mismo y el editor fresco aún
                            // acepta colocación. Nunca se redirige a otro fotograma.
                            val target = geometryTarget()
                            val freshItem = target?.let { freshestSession()?.frame(it) }
                            if (target != null && freshItem != null && !saving &&
                                angleWorkspaceAcceptsPlacement(freshItem.editor, addingAngle)
                            ) {
                                applied(viewModel.placeMultiFramePoint(target, point), target)
                            }
                        },
                        onMoveMarker = { angleId, marker: AngleMarker, point ->
                            geometryTarget()?.let {
                                applied(
                                    viewModel.moveMultiFrameMarker(it, angleId, marker, point),
                                    it,
                                )
                            }
                        },
                        onMoveDraftMarker = { marker, point ->
                            geometryTarget()?.let {
                                applied(viewModel.moveMultiFrameDraftMarker(it, marker, point), it)
                            }
                        },
                        // Identidad del gesto y transacción local: la capa pública no conoce el
                        // binding interno, solo esta clave estable (fuente+generación+frame).
                        interactionKey = renderedBinding,
                        deferredGestureCommit = activeCanEdit && !saving,
                        previewConfirmation = frameConfirmation,
                        onPreviewActiveChanged = { overlayPreviewActive = it },
                        // Solo el workspace opta al latch multitáctil de colocación: un pinch en la
                        // foto nunca deja un punto A/B/C extra. El visor legacy mantiene su API.
                        abortPlacementOnMultitouch = true,
                    )
                    }
                    if (activeCanEdit && !saving) {
                        // Arena CONTENEDORA del contenido: la foto+overlay son sus hijos reales y
                        // los HUD/botones del workspace quedan hermanos fuera de este Box.
                        AngleWorkspaceFramingArena(
                            mapperProvider = { mapper },
                            interactionKey = renderedBinding,
                            enabled = true,
                            onFramingGesture = { centroid, zoomChange, panDx, panDy ->
                                mapper?.let { current ->
                                    updateCrop(
                                        current.stateAfterGesture(centroid, zoomChange, panDx, panDy),
                                    )
                                }
                            },
                            onBusyChanged = { viewportGestureActive = it },
                            content = { frozenCanvas() },
                        )
                    } else {
                        frozenCanvas()
                    }
                }
                    }
                }
                // La tira solo aparece cuando YA existe un fotograma: la primera visita no gasta
                // altura en una tira de solo Vídeo. En fullscreen no se muestra.
                val filmstripStage: @Composable () -> Unit = {
                    if (plan.showFilmstrip) {
            // --- tira de fotogramas: baldosa de vídeo + miniaturas con píxeles reales -----------
            AngleFrameFilmstrip(
                viewModel = viewModel,
                session = session,
                activeFrameId = activeFrameId,
                enabled = !saving && !workspaceGestureBusy,
                onSelectVideo = {
                    if (!saving && !workspaceGestureBusyNow()) {
                        viewModel.pausePlayback()
                        report(viewModel.clearMultiFrameSelection())
                    }
                },
                onSelectFrame = { frameId ->
                    if (!saving && !workspaceGestureBusyNow()) {
                        // Pausa por el puerto existente antes de mostrar la foto y sin borrar nada.
                        viewModel.pausePlayback()
                        report(viewModel.selectMultiFrame(frameId))
                    }
                },
            )
                    }
                }
                // Controles: en apaisado van al panel lateral scrollable; en portrait, debajo.
                val controlsStage: @Composable (Modifier) -> Unit = { controlsModifier ->
                    Column(
                        modifier = controlsModifier,
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
            if (framesExist && saveState !is AngleMultiFrameSaveState.Saved) {
                Text(
                    text = stringResource(R.string.angle_workspace_unsaved),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screenHorizontal),
                )
            }
            // --- acciones: una CTA primaria contextual y secundarias compactas ------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (activeFrameId != null) {
                    // Dos secundarias con objetivo cómodo y wrapping + acciones raras en overflow.
                    // Ya NO hay una fila de tres TextButton estrechos.
                    val moreActionsLabel = stringResource(R.string.angle_workspace_more_actions)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { if (!saving && !workspaceGestureBusyNow()) addingAngle = true },
                            enabled = activeCanEdit && !saving && !workspaceGestureBusy,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = Spacing.xxxl)
                                .testTag(ANGLE_WORKSPACE_ADD_ANGLE_TAG),
                        ) {
                            Text(stringResource(R.string.angle_workspace_add_angle))
                        }
                        TextButton(
                            onClick = { openPrecision() },
                            enabled = activeCanEdit && !saving && openLease != null &&
                                !workspaceGestureBusy,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = Spacing.xxxl)
                                .testTag(ANGLE_WORKSPACE_PRECISION_TAG),
                        ) {
                            Text(stringResource(R.string.angle_workspace_precision))
                        }
                        Box {
                            // Overflow compacto 48dp con la MISMA etiqueta/CD: a fontScale 1.5 la
                            // fila de tres textos largos desbordaba; el icono no recorta la acción.
                            // Sin nueva dependencia de icons: glifo vertical con CD localizada.
                            IconButton(
                                onClick = { overflowOpen = true },
                                enabled = !saving,
                                modifier = Modifier
                                    .testTag(ANGLE_WORKSPACE_OVERFLOW_TAG)
                                    .semantics {
                                        contentDescription = moreActionsLabel
                                    },
                            ) {
                                Text(
                                    text = "\u22EE",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.angle_workspace_remove_frame))
                                    },
                                    onClick = {
                                        removeRequested = activeFrameId
                                        overflowOpen = false
                                    },
                                    enabled = !saving,
                                    modifier = Modifier.testTag(
                                        ANGLE_WORKSPACE_REMOVE_FRAME_TAG,
                                    ),
                                )
                            }
                        }
                    }
                } else if (framesExist) {
                    // Con vídeo + fotogramas, Guardar conserva el mismo significado.
                    TextButton(
                        onClick = { if (!saving && !workspaceGestureBusyNow()) viewModel.saveMultiFrame() },
                        enabled = saveAvailability == AngleMultiFrameSaveAvailability.Ready &&
                            !workspaceGestureBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.xxxl),
                    ) {
                        Text(stringResource(R.string.angle_workspace_save))
                    }
                }
                if (activeFrameId != null) {
                    // Foto: la CTA primaria es Guardar. Bloqueada con preview/encuadre ajeno
                    // (live + enabled); la propia capa nunca llama aquí.
                    Button(
                        onClick = { requestSave() },
                        enabled = saveAvailability == AngleMultiFrameSaveAvailability.Ready &&
                            !workspaceGestureBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.xxxl)
                            .testTag(ANGLE_WORKSPACE_SAVE_TAG),
                    ) {
                        Text(stringResource(R.string.angle_workspace_save))
                    }
                } else {
                    // Vídeo: la CTA primaria es Medir el fotograma pausado.
                    Button(
                        onClick = { if (!workspaceGestureBusyNow()) performCapture() },
                        enabled = captureEnabled && !workspaceGestureBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.xxxl)
                            .testTag(ANGLE_WORKSPACE_CAPTURE_TAG),
                    ) {
                        Text(stringResource(R.string.angle_workspace_capture))
                    }
                }
            }
            // --- estado visible (vídeo, preparación, captura, fase, guardado) -------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (videoFailed) {
                    videoMessageRes?.let { res ->
                        Text(
                            text = stringResource(res),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(
                        onClick = { if (!saving) onCloseRequest() },
                        modifier = Modifier.heightIn(min = Spacing.xxxl),
                    ) {
                        Text(stringResource(R.string.angle_multi_frame_back))
                    }
                }
                when (val current = multiFrame) {
                    is AngleMultiFrameState.Unavailable -> {
                        val specific = specificUnavailableMessageRes(current.reason)
                        Text(
                            text = specific?.let { stringResource(it) }
                                ?: stringResource(R.string.angle_workspace_prepare_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            TextButton(
                                onClick = {
                                    prepareRequested = true
                                    viewModel.openMultiFrameSession()
                                },
                                modifier = Modifier.heightIn(min = Spacing.xxxl),
                            ) {
                                Text(stringResource(R.string.angle_workspace_retry))
                            }
                            TextButton(
                                onClick = { if (!saving) onCloseRequest() },
                                modifier = Modifier.heightIn(min = Spacing.xxxl),
                            ) {
                                Text(stringResource(R.string.angle_multi_frame_back))
                            }
                        }
                    }

                    AngleMultiFrameState.Opening, AngleMultiFrameState.NotOpened -> {
                        // Sin vídeo listo ya hay un mensaje de vídeo (o de fallo); no se repite
                        // «preparando» en dos sitios ni se quedó preparando tras un fallo.
                        if (videoReady) {
                            Text(
                                text = stringResource(R.string.angle_workspace_preparing),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    else -> Unit
                }
                captureHint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (activeFrameId != null && overlayMode == AngleOverlayMode.PLACEMENT) {
                    Text(
                        text = stringResource(
                            R.string.angle_workspace_guidance,
                            nextDraftMarker(draftPoints.size).name,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag(ANGLE_WORKSPACE_GUIDANCE_TAG),
                    )
                }
                sessionCaptureFailure?.let { failure ->
                    val typed = angleMultiFrameCaptureFailureNotice(failure.reason)
                    Text(
                        text = stringResource(typed.messageRes, *typed.args.toTypedArray()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = {
                            activeFrameId?.let {
                                report(viewModel.retryMultiFrameCapture(it))
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.heightIn(min = Spacing.xxxl),
                    ) {
                        Text(stringResource(R.string.angle_multi_frame_retry_capture))
                    }
                }
                capturingFrame?.let { capturing ->
                    Text(
                        text = stringResource(
                            R.string.angle_multi_frame_frame_label,
                            capturing.anchor.frameIndex + 1,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = { report(viewModel.cancelMultiFrameCapture(capturing.frameId)) },
                        enabled = !saving,
                        modifier = Modifier.heightIn(min = Spacing.xxxl),
                    ) {
                        Text(stringResource(R.string.angle_multi_frame_cancel_capture))
                    }
                }
                notice?.let { current ->
                    Text(
                        text = stringResource(current.messageRes, *current.args.toTypedArray()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (saveAvailability is AngleMultiFrameSaveAvailability.Blocked) {
                    val reason = saveAvailability.reason
                    // Texto completo: un motivo o límite no se recorta ni se elide.
                    Text(
                        text = stringResource(R.string.angle_workspace_save_unavailable) + ": " +
                            stringResource(reason.messageRes, *reason.args.toTypedArray()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                saveStatus?.let { current ->
                    Text(
                        text = stringResource(current.messageRes, *current.args.toTypedArray()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (session != null && session.frames.isEmpty()) {
                    Text(
                        text = stringResource(R.string.angle_workspace_no_frames),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
                    }
                }
                AngleWorkspaceResponsiveLayout(
                    plan = plan,
                    modifier = Modifier.fillMaxSize(),
                    media = mediaStage,
                    filmstrip = filmstripStage,
                    controls = controlsStage,
                ) {
                    // Fullscreen: TODO el viewport para la media y chrome superpuesto. Salir siempre
                    // visible >=48dp y una acción esencial real (medir/guardar/precisión).
                    Box(modifier = Modifier.fillMaxSize()) {
                        TextButton(
                            onClick = { fullscreen = false },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .statusBarsPadding()
                                .padding(Spacing.sm)
                                .heightIn(min = Spacing.xxxl)
                                .testTag(ANGLE_WORKSPACE_EXIT_FULLSCREEN_TAG),
                        ) {
                            Text(stringResource(R.string.angle_workspace_exit_fullscreen))
                        }
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(Spacing.sm),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            if (activeFrameId == null) {
                                Button(
                                    onClick = { performCapture() },
                                    enabled = captureEnabled && !workspaceGestureBusy,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = Spacing.xxxl),
                                ) {
                                    Text(stringResource(R.string.angle_workspace_capture))
                                }
                            } else {
                                Button(
                                    onClick = { requestSave() },
                                    enabled = saveAvailability ==
                                        AngleMultiFrameSaveAvailability.Ready && !workspaceGestureBusy,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = Spacing.xxxl),
                                ) {
                                    Text(stringResource(R.string.angle_workspace_save))
                                }
                                TextButton(
                                    onClick = { openPrecision() },
                                    enabled = activeCanEdit && !saving && openLease != null &&
                                        !workspaceGestureBusy,
                                    modifier = Modifier.heightIn(min = Spacing.xxxl),
                                ) {
                                    Text(stringResource(R.string.angle_workspace_precision))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Diálogo real de eliminación: cancelar no cambia nada; confirmar usa la guarda de la sesión.
    removeRequested?.let { requested ->
        val requiresConfirmation = viewModel.multiFrameRequiresConfirmation(requested)
        AlertDialog(
            onDismissRequest = { removeRequested = null },
            title = { Text(stringResource(R.string.angle_workspace_remove_frame)) },
            text = {
                Text(
                    stringResource(R.string.angle_workspace_remove_confirm),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        report(
                            viewModel.removeMultiFrame(
                                requested,
                                if (requiresConfirmation) {
                                    AngleDiscardConfirmation.CONFIRMED
                                } else {
                                    AngleDiscardConfirmation.NOT_CONFIRMED
                                },
                            ),
                        )
                        removeRequested = null
                    },
                    modifier = Modifier.testTag(ANGLE_WORKSPACE_REMOVE_CONFIRM_TAG),
                ) {
                    Text(stringResource(R.string.common_discard))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { removeRequested = null },
                    modifier = Modifier.testTag(ANGLE_WORKSPACE_REMOVE_CANCEL_TAG),
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (inspectorVisible) {
        // Identidad del fotograma DIBUJADO: si cambia, cursor y marca elegida arrancan de cero.
        val stageFrameKey = listOf(
            activeFrameId?.value?.toString(),
            session?.sourceIdentity,
            session?.generation?.toString(),
        ).joinToString("|")
        AngleWorkspaceInspector(
            editor = editorState,
            geometry = imageGeometry,
            crop = activeCrop,
            onZoomIn = { updateCrop(angleWorkspaceNextCrop(activeCrop, zoomIn = true)) },
            onZoomOut = { updateCrop(angleWorkspaceNextCrop(activeCrop, zoomIn = false)) },
            onResetCrop = { updateCrop(angleWorkspaceResetCrop) },
            frameKey = stageFrameKey,
            placementIntent = addingAngle,
            enabled = activeCanEdit && !saving,
            formatDegrees = formatDegrees,
            onDismiss = {
                // Salir del inspector no descarta el borrador ni deselecciona nada.
                inspectorVisible = false
            },
            onStartAnotherAngle = { if (!saving) addingAngle = true },
            onSelectAngle = { angleId ->
                geometryTarget()?.let {
                    val result = viewModel.selectMultiFrameAngle(it, angleId)
                    if (result is AngleMultiFrameResult.Applied) addingAngle = false
                    report(result)
                }
            },
            onPlacePoint = { point: AnglePoint ->
                // Mismas guardas que el toque del lienzo: fotograma dibujado == fresco, sin guardar
                // y fase que aún acepta colocación. Un rechazo devuelve `false` y no avanza nada.
                val target = geometryTarget()
                val freshItem = target?.let { freshestSession()?.frame(it) }
                if (target != null && freshItem != null && !saving &&
                    angleWorkspaceAcceptsPlacement(freshItem.editor, addingAngle)
                ) {
                    applied(viewModel.placeMultiFramePoint(target, point), target)
                } else {
                    false
                }
            },
            onMoveMarker = { angleId, marker, point ->
                geometryTarget()?.let {
                    applied(viewModel.moveMultiFrameMarker(it, angleId, marker, point), it)
                } ?: false
            },
            onMoveDraftMarker = { marker, point ->
                geometryTarget()?.let {
                    applied(viewModel.moveMultiFrameDraftMarker(it, marker, point), it)
                } ?: false
            },
            onCancelDraft = {
                geometryTarget()?.let {
                    applied(viewModel.cancelMultiFrameDraft(it), it)
                } ?: false
            },
            onRename = { angleId, name ->
                geometryTarget()?.let {
                    applied(viewModel.renameMultiFrameAngle(it, angleId, name), it)
                } ?: false
            },
            onSetColor = { angleId, colorArgb ->
                geometryTarget()?.let {
                    applied(viewModel.setMultiFrameAngleColor(it, angleId, colorArgb), it)
                } ?: false
            },
            onSetVisible = { angleId, visible ->
                geometryTarget()?.let {
                    applied(viewModel.setMultiFrameAngleVisible(it, angleId, visible), it)
                } ?: false
            },
            onDelete = { angleId ->
                geometryTarget()?.let {
                    applied(viewModel.deleteMultiFrameAngle(it, angleId), it)
                } ?: false
            },
        )
    }
}


/**
 * Controles reales de encuadre de la fuente (vídeo): zoom relativo ± y reset 1x.
 * Objetivos ≥48dp y etiquetas de contenido; no son un panel placebo: cada uno muta el
 * [CropViewportState] que alimenta al mapper compartido.
 */
@Composable
internal fun AngleSourceZoomControls(
    zoom: Double,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    resetEnabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onZoomOut,
            enabled = enabled && zoom > MIN_CROP_ZOOM,
            modifier = Modifier
                .heightIn(min = Spacing.xxxl)
                .testTag(ANGLE_WORKSPACE_SOURCE_ZOOM_OUT_TAG),
        ) {
            Text(stringResource(R.string.angle_workspace_zoom_out))
        }
        Text(
            text = stringResource(
                R.string.angle_workspace_zoom_value,
                NumberFormat.getNumberInstance(currentAppLocale()).apply {
                    maximumFractionDigits = 2
                }.format(zoom),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
        TextButton(
            onClick = onZoomIn,
            enabled = enabled && zoom < com.openjump.app.ui.selector.MAX_CROP_ZOOM,
            modifier = Modifier
                .heightIn(min = Spacing.xxxl)
                .testTag(ANGLE_WORKSPACE_SOURCE_ZOOM_IN_TAG),
        ) {
            Text(stringResource(R.string.angle_workspace_zoom_in))
        }
        TextButton(
            onClick = onReset,
            enabled = enabled && resetEnabled,
            modifier = Modifier
                .heightIn(min = Spacing.xxxl)
                .testTag(ANGLE_WORKSPACE_SOURCE_ZOOM_RESET_TAG),
        ) {
            Text(stringResource(R.string.angle_workspace_zoom_reset))
        }
    }
}
