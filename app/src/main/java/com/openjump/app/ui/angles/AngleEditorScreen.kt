package com.openjump.app.ui.angles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import com.openjump.app.R
import com.openjump.app.angles.Angle
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarkNameContract
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AngleMeasurement
import com.openjump.app.angles.AnglePoint
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.components.VideoPreparationOverlay
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.selector.AnalysisVideoSurface
import com.openjump.app.ui.selector.CropAlignment
import com.openjump.app.ui.selector.CropFramingOverlay
import com.openjump.app.ui.selector.CropViewportState
import com.openjump.app.ui.selector.FrameDisplayState
import com.openjump.app.ui.selector.MAX_CROP_ZOOM
import com.openjump.app.ui.selector.MIN_CROP_ZOOM
import com.openjump.app.ui.selector.VideoCoordinateMapper
import com.openjump.app.ui.selector.VideoScaleMode
import com.openjump.app.ui.selector.VideoTransportControls
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import java.text.NumberFormat

/**
 * Acciones de transporte que la pantalla conecta al player **ya existente** del editor.
 *
 * Por defecto [AngleEditorScreen] las deriva del [AngleEditorViewModel] (`stepFrames(-1)`,
 * `stepFrames(1)` y `togglePlayback()`), que delega en el transporte compartido: la pantalla no crea
 * otro player ni mueve el frame por su cuenta. El parámetro permite al llamante sustituirlas y
 * probar la pantalla sin ViewModel; con `null` no se muestran controles y no se simula transporte.
 *
 * El timeline por PTS sigue pendiente porque [AngleEditorViewModel] no expone la lista de PTS por
 * frame y [com.openjump.app.ui.selector.SportsTimeline] la necesita para mapear fracción a índice.
 */
internal data class AngleEditorTransportActions(
    val onPreviousFrame: () -> Unit,
    val onTogglePlayback: () -> Unit,
    val onNextFrame: () -> Unit,
)

/**
 * Intenciones de edición que la pantalla emite. La pantalla no muta estado ni persiste: el
 * llamante decide si aplica cada intención con las guardas del ViewModel.
 *
 * Los tres gestos de geometría devuelven `true` **solo** si el cambio se aplicó de verdad. Ese
 * valor lo consume [AngleAccessiblePointControls] para no avanzar ni anunciar un punto que las
 * guardas de frame/PTS/generación rechazaron: el panel accesible nunca confirma en falso.
 */
internal data class AngleEditorContentActions(
    val onSelectAngle: (AngleId?) -> Unit,
    val onPlacePoint: (AnglePoint) -> Boolean,
    val onMoveMarker: (AngleId, AngleMarker, AnglePoint) -> Boolean,
    val onMoveDraftMarker: (AngleMarker, AnglePoint) -> Boolean,
    val onCancelDraft: () -> Unit,
    val onSetVisible: (AngleId, Boolean) -> Unit,
    val onRename: (AngleId, String) -> Unit,
    val onSetColor: (AngleId, Int) -> Unit,
    val onDelete: (AngleId) -> Unit,
    val onSave: () -> Unit,
    /**
     * Intención explícita de conservar la edición como análisis **nuevo**.
     *
     * Solo se ofrece cuando [AngleSaveRecovery] indica que el guardado normal no puede persistir la
     * edición (análisis borrado, escritura no verificada o conflicto de CAS con otra revisión).
     * Nunca se deriva del guardado normal: ese camino sigue siendo [onSave] y no crea copias por sí
     * solo. Sin cablear no hay acción visible; el llamante real la conecta a `viewModel::saveAsNew`.
     */
    val onSaveAsNew: () -> Unit = {},
    /**
     * Abre el modo multi-fotograma (captura del frame pausado y geometría por fotograma).
     *
     * `null` = la pantalla no ofrece la entrada; el llamante real la cablea a
     * [AngleEditorViewModel.openMultiFrameSession]. No guarda ni implica persistencia multi-fotograma.
     */
    val onOpenMultiFrame: (() -> Unit)? = null,
    val onCancel: () -> Unit,
)

/**
 * Motivo/localización de la acción «guardar como análisis nuevo» en la pantalla.
 *
 * Función pura y exhaustiva para que el caso por caso sea explícito: si mañana aparece otro motivo
 * de recuperación, el compilador obliga a decidir si merece la acción, y la prueba JVM puede fijar
 * la tabla sin componer la pantalla.
 */
internal fun angleSaveAsNewOffered(recovery: AngleSaveRecovery): Boolean = when (recovery) {
    // Guardado normal seguro: crear una copia sería un duplicado no solicitado.
    AngleSaveRecovery.None -> false
    is AngleSaveRecovery.Deleted -> true
    is AngleSaveRecovery.Unverified -> true
    // La copia persistida avanzó en otra pantalla: la edición viva solo se conserva como nueva.
    is AngleSaveRecovery.Conflict -> true
}

/** Etiqueta del botón de recuperación para las pruebas de UI que sí corren instrumentadas. */
internal const val ANGLE_SAVE_AS_NEW_TAG = "angle-save-as-new"

/**
 * Paleta de presentación de los ángulos. Son colores de producto (no roles del tema) porque
 * identifican marcas sobre el vídeo; la selección nunca depende solo del color: el color elegido se
 * anuncia con su posición y el elemento seleccionado lleva borde y una marca de verificación.
 */
internal val AngleColorPalette: List<Int> = listOf(
    0xFFE53935.toInt(),
    0xFFFF6E40.toInt(),
    0xFFFDD835.toInt(),
    0xFF43A047.toInt(),
    0xFF1E88E5.toInt(),
    0xFF8E24AA.toInt(),
    0xFF00ACC1.toInt(),
    0xFF6D4C41.toInt(),
)

/**
 * Pantalla del editor manual de ángulos: vídeo protagonista, capa de geometría y lista de ángulos.
 *
 * Recibe un [AngleEditorViewModel] ya abierto sobre un vídeo (no lo crea, no lo reabre y no navega):
 * todas las mutaciones pasan por los métodos con guardas del ViewModel
 * ([AngleEditorViewModel.placePoint], [AngleEditorViewModel.moveMarker], [AngleEditorViewModel.save],
 * …). La salida de la pantalla se delega en [onCancel].
 *
 * La geometría de presentación sale del **índice real** del vídeo a través de
 * [AngleEditorViewModel.videoGeometry] (dims codificadas + rotación): la pantalla no lee `Tracks`,
 * no inventa dimensiones y no depende de que Media3 ya haya publicado el track. Mientras el
 * ViewModel no publique geometría no hay mapper ni capa interactiva.
 *
 * El transporte prev/play/next se conecta por defecto a los métodos del ViewModel, que delegan en
 * el transporte compartido del player existente; no se crea un player paralelo. El timeline por PTS
 * sigue pendiente (ver [AngleEditorTransportActions]).
 *
 * @param onCancel salida solicitada (descartar/cerrar). No guarda y no navega por sí misma.
 * @param transport sustitución opcional de las acciones de transporte; `null` = derivarlas del VM.
 * @param angleMultiFrameSaveUiEnabled gate de producto del guardado multi-fotograma que se usa
 *   **solo** para calcular la disponibilidad del botón. Por defecto `false` (igual que
 *   [ANGLE_MULTI_FRAME_SAVE_UI_ENABLED]: el botón queda deshabilitado con su motivo); existe como
 *   costura para ejercitar la pantalla con el gate activo sin cambiar la constante de producto.
 * @param workspaceRequested elegibilidad del workspace nuevo según la **ruta** (la calcula
 *   [angleWorkspaceEligible] en el llamante). Se combina con [angleMultiFrameSaveUiEnabled]: con el
 *   valor por defecto `false` la pantalla conserva exactamente la composición y los guards legacy, y
 *   con el gate apagado tampoco se monta aunque llegue `true`. El workspace no crea otro player ni
 *   otra autoridad de selección: usa el mismo ViewModel.
 */
@Composable
internal fun AngleEditorScreen(
    viewModel: AngleEditorViewModel,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    transport: AngleEditorTransportActions? = null,
    angleMultiFrameSaveUiEnabled: Boolean = false,
    workspaceRequested: Boolean = false,
) {
    val editor by viewModel.editor.collectAsState()
    val videoState by viewModel.videoState.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    // Línea base del contenido verificado como persistido (o vacía al abrir). La política pura de
    // cierre la compara con la edición viva; la pantalla no infiere «limpio» de `Saved`, de la
    // selección ni de los ids marcados como persistidos.
    val closeBaseline by viewModel.closeBaseline.collectAsState()
    // Recuperación pendiente publicada por el ViewModel: es la ÚNICA fuente que habilita «guardar
    // como análisis nuevo». No se deduce del último `saveState` porque un intento fallido también
    // publica `Failed` y la edición seguiría sin destino.
    val saveRecovery by viewModel.saveRecovery.collectAsState()
    val displayState by viewModel.displayState.collectAsState()
    val player by viewModel.player.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val errorMessage by viewModel.error.collectAsState()
    // Geometría publicada por el ViewModel desde el índice real, no inferida de `Tracks`: la capa
    // puede dibujarse antes de que Media3 publique el track de vídeo.
    val videoGeometry by viewModel.videoGeometry.collectAsState()
    // Puerta observable del ViewModel: cambia cuando un guardado o una reapertura empiezan o
    // terminan. Es imprescindible en la clave de la caché de la confirmación: una reapertura puede
    // acabar conservando el frame ya confirmado sin emitir otra salida de `displayState`.
    val gestureGate by viewModel.gestureGate.collectAsState()
    // Sesión de fotogramas congelados: si hay fotogramas en memoria, ni el guardado mono-fotograma
    // ni el cierre pueden descartarlos en silencio.
    val multiFrameState by viewModel.multiFrameState.collectAsState()
    // Estado real del guardado multi-fotograma. Solo llega aquí después de la transacción V2; la
    // pantalla lo muestra tal cual (en vuelo, éxito real o fallo recuperable) y no lo interpreta.
    val multiFrameSaveState by viewModel.multiFrameSaveState.collectAsState()
    // La confirmación se recalcula con TODAS sus entradas observables (salida del visor, puerta y
    // estado del editor), nunca solo con `displayState`: es la misma fuente que usa el ViewModel para
    // validar PTS/frame, así que la capa nunca dibuja ni instala gestos con una confirmación vieja, y
    // tampoco se queda esperando un frame que ya está confirmado.
    val confirmationCache = remember(viewModel) { AngleEditorConfirmationCache() }
    val confirmation = confirmationCache.confirmation(displayState, gestureGate, editor) {
        viewModel.currentConfirmation()
    }
    // Transporte real sobre el player existente: pasos exactos de frame y play/pausa del VM.
    // `remember` sobre el VM: las lambdas delegan siempre en la misma instancia.
    val transportFromViewModel = remember(viewModel) {
        AngleEditorTransportActions(
            onPreviousFrame = { viewModel.stepFrames(-1) },
            onTogglePlayback = { viewModel.togglePlayback() },
            onNextFrame = { viewModel.stepFrames(1) },
        )
    }

    // Acciones del modo multi-fotograma: cada una devuelve el resultado tipado de la sesión y la
    // pantalla de fotogramas solo anuncia lo que la sesión rechazó de verdad.
    val multiFrameActions = remember(viewModel) {
        AngleMultiFrameEditorActions(
            onOpen = { viewModel.openMultiFrameSession() },
            onCapture = { viewModel.captureMultiFrame() },
            onRetryCapture = { frameId -> viewModel.retryMultiFrameCapture(frameId) },
            onCancelCapture = { frameId -> viewModel.cancelMultiFrameCapture(frameId) },
            onSelectFrame = { frameId -> viewModel.selectMultiFrame(frameId) },
            onRemoveFrame = { frameId, confirmation ->
                viewModel.removeMultiFrame(frameId, confirmation)
            },
            onAcquireBitmap = { frameId -> viewModel.acquireMultiFrameBitmap(frameId) },
            onPlacePoint = { frameId, point ->
                viewModel.placeMultiFramePoint(frameId, point)
            },
            onMoveMarker = { frameId, angleId, marker, point ->
                viewModel.moveMultiFrameMarker(frameId, angleId, marker, point)
            },
            onMoveDraftMarker = { frameId, marker, point ->
                viewModel.moveMultiFrameDraftMarker(frameId, marker, point)
            },
            onCancelDraft = { frameId -> viewModel.cancelMultiFrameDraft(frameId) },
            onSelectAngle = { frameId, angleId ->
                viewModel.selectMultiFrameAngle(frameId, angleId)
            },
            onSetAngleVisible = { frameId, angleId, visible ->
                viewModel.setMultiFrameAngleVisible(frameId, angleId, visible)
            },
            onRenameAngle = { frameId, angleId, name ->
                viewModel.renameMultiFrameAngle(frameId, angleId, name)
            },
            onSetAngleColor = { frameId, angleId, color ->
                viewModel.setMultiFrameAngleColor(frameId, angleId, color)
            },
            onDeleteAngle = { frameId, angleId ->
                viewModel.deleteMultiFrameAngle(frameId, angleId)
            },
            requiresRemovalConfirmation = { frameId ->
                viewModel.multiFrameRequiresConfirmation(frameId)
            },
            // Única entrada de la UI al guardado multi-fotograma. El ViewModel aplica su propio
            // single-flight y todos sus guards; la pantalla solo la llama cuando el gate de
            // producto está activo y el análisis es finalizable.
            onSaveMultiFrame = { viewModel.saveMultiFrame() },
        )
    }
    var multiFrameMode by remember { mutableStateOf(false) }
    // Visor ampliado: es estado de presentación de ESTA pantalla (no reorienta la Activity, no crea
    // otro player y no toca la superficie). Vive aquí porque el atrás tiene que reducirlo ANTES de
    // plantear el cierre, sin saltarse las guardas de guardado/fotogramas.
    var viewportExpanded by remember(editor?.sourceIdentity) { mutableStateOf(false) }
    var closeConfirm by remember { mutableStateOf<AngleEditorDiscardContent?>(null) }
    val multiFrameHasFrames = angleMultiFrameHasFrames(multiFrameState)
    // Guardado multi-fotograma en vuelo: mientras dure, ni el cierre ni la navegación pueden
    // abandonar la pantalla. La autoridad real es el ViewModel (single-flight + leases); aquí se
    // evita además ofrecer una salida que dejaría el resultado sin superficie visible.
    val multiFrameSaving = multiFrameSaveState is AngleMultiFrameSaveState.Saving
    // Guardado mono-fotograma en vuelo, observable por el mismo motivo: cerrar a mitad de escritura
    // abandonaría una foto que el ViewModel está escribiendo y verificando.
    val monoFrameSaving = saveState is AngleSaveState.Saving
    // Decisión de cierre calculada con la política pura a partir del ViewModel vivo. El contenido
    // vivo se lee del VM en el instante del toque: una selección, un frame del reproductor o una
    // marca de persistido no cuentan como cambio; coordenadas, nombre, color, visibilidad, borrado
    // y borrador parcial, sí. Los fotogramas congelados se cuentan aparte porque no se persisten.
    val closeDecision: () -> AngleEditorCloseDecision = {
        angleEditorCloseDecision(
            saveInFlight = viewModel.isSaveInFlight(),
            baseline = viewModel.closeBaseline.value,
            current = angleEditorContent(viewModel.editor.value),
            frozenFrameCount = angleMultiFrameFrameCount(viewModel.multiFrameState.value),
        )
    }
    // Cerrar sin avisar perdería datos reales: puntos parciales, ediciones de ángulos o fotogramas
    // congelados. Solo se sale directo cuando el contenido vivo coincide con lo verificado.
    val requestClose: () -> Unit = {
        when (val decision = closeDecision()) {
            AngleEditorCloseDecision.BlockedBySave -> Unit
            AngleEditorCloseDecision.Exit -> onCancel()
            is AngleEditorCloseDecision.Confirm -> closeConfirm = decision.content
        }
    }
    // Rama nueva del gate V2 (solo analisis NUEVO): workspace video-primero. El llamante
        // pasa la elegibilidad de la RUTA; aqui se AND con el gate inyectado para que el valor
        // por defecto (legacy) no cambie de semantica ni se habilite por si solo.
        // El atrás del sistema no puede saltarse esas confirmaciones: la precedencia (guardado en
        // vuelo > modo fotogramas > visor ampliado > salida) vive en una composable propia para poder
        // fijarla en pruebas instrumentadas con el BackHandler REAL de producción.
    if (workspaceRequested && angleMultiFrameSaveUiEnabled) {
        AngleEditorWorkspace(
            viewModel = viewModel,
            onCloseRequest = requestClose,
            modifier = modifier,
        )
    } else {
        AngleEditorBackHandler(
            saving = multiFrameSaving || monoFrameSaving,
            multiFrameMode = multiFrameMode,
            viewportExpanded = viewportExpanded,
            onLeaveMultiFrame = { multiFrameMode = false },
            onCollapseViewport = { viewportExpanded = false },
            onExit = requestClose,
        )

        if (multiFrameMode) {
            AngleMultiFrameEditorScreen(
                multiFrame = multiFrameState,
                actions = multiFrameActions,
                // Volver al visor conserva la sesión en el ViewModel: no se pierde ningún fotograma,
                // pero con un guardado en vuelo la salida está bloqueada.
                onBackToViewer = { if (!multiFrameSaving) multiFrameMode = false },
                onClose = requestClose,
                modifier = modifier,
                saveState = multiFrameSaveState,
                // Única lectura del gate de producto en esta capa: decidir si el botón de guardado se
                // ofrece hoy. El valor por defecto de la pantalla usa la constante; aquí se propaga el
                // que recibe el editor para que una prueba pueda ejercitarlo sin tocar la constante.
                saveAvailability = angleMultiFrameSaveAvailability(
                    gateEnabled = angleMultiFrameSaveUiEnabled,
                    multiFrame = multiFrameState,
                    saveState = multiFrameSaveState,
                ),
            )
        } else {
            AngleEditorContent(
                editor = editor,
                videoState = videoState,
                saveState = saveState,
                saveRecovery = saveRecovery,
                displayState = displayState,
                player = player,
                videoGeometry = videoGeometry,
                loading = loading,
                errorMessage = errorMessage,
                confirmation = confirmation,
                actions = AngleEditorContentActions(
                    onSelectAngle = viewModel::select,
                    // Confirmación fresca en cada intención (toque o control accesible): la guarda de
                    // frame/PTS/generación es del ViewModel, no de la pantalla.
                    onPlacePoint = { point ->
                        viewModel.currentConfirmation()?.let { viewModel.placePoint(point, it) } ==
                            AngleEditorActionResult.Applied
                    },
                    onMoveMarker = { angleId, marker, point ->
                        viewModel.currentConfirmation()?.let {
                            viewModel.moveMarker(angleId, marker, point, it)
                        } == AngleEditorActionResult.Applied
                    },
                    onMoveDraftMarker = { marker, point ->
                        viewModel.currentConfirmation()?.let {
                            viewModel.moveDraftMarker(marker, point, it)
                        } == AngleEditorActionResult.Applied
                    },
                    onCancelDraft = { viewModel.cancelDraft() },
                    onSetVisible = viewModel::setVisible,
                    onRename = viewModel::rename,
                    onSetColor = viewModel::setColor,
                    onDelete = viewModel::deleteAngle,
                    onSave = viewModel::save,
                    // Acción explícita de recuperación, cableada al único camino que conserva la edición
                    // sin sobrescribir la raíz borrada/no verificada. El guardado normal sigue siendo
                    // `onSave`.
                    onSaveAsNew = viewModel::saveAsNew,
                    // Entrada al modo fotogramas: abre la sesión (perezosa) y cambia de superficie sin
                    // navegar ni tocar el flujo mono-fotograma.
                    onOpenMultiFrame = {
                        viewModel.openMultiFrameSession()
                        multiFrameMode = true
                    },
                    onCancel = requestClose,
                ),
                transport = transport ?: transportFromViewModel,
                onRefreshFrame = { viewModel.viewer.refreshCurrentFrame() },
                modifier = modifier,
                viewportExpanded = viewportExpanded,
                onViewportExpandedChange = { viewportExpanded = it },
                // Con fotogramas congelados en memoria (o con un guardado multi-fotograma en vuelo) el
                // guardado legacy está bloqueado de forma explícita: guardar solo el frame
                // mono-fotograma perdería los demás.
                saveBlockedNotice = if (multiFrameHasFrames || multiFrameSaving) {
                    stringResource(R.string.angle_multi_frame_save_blocked)
                } else {
                    null
                },
            )
        }
    }

    closeConfirm?.let { pending ->
        AngleEditorCloseDiscardDialog(
            content = pending,
            onDismiss = { closeConfirm = null },
            onConfirm = {
                // Relee la guarda en el instante de confirmar: si una escritura empezó con el
                // diálogo abierto, la salida se rechaza sin navegar ni escribir nada. Cancelar y
                // descartar no tocan el estado del editor; descartar solo navega una vez.
                val blocked = viewModel.isSaveInFlight()
                closeConfirm = null
                if (!blocked) onCancel()
            },
        )
    }
}

/**
 * Precedencia del atrás del editor en una composable real, reutilizable por pruebas instrumentadas.
 *
 * Un guardado multi-fotograma en vuelo consume el gesto (ni salir ni cerrar). En modo fotogramas
 * vuelve al visor; con el visor ampliado lo reduce primero (el panel sigue completo debajo) y solo
 * después plantea la salida normal. Un gesto nunca colapsa y navega a la vez.
 */
@Composable
internal fun AngleEditorBackHandler(
    saving: Boolean,
    multiFrameMode: Boolean,
    viewportExpanded: Boolean,
    onLeaveMultiFrame: () -> Unit,
    onCollapseViewport: () -> Unit,
    onExit: () -> Unit,
) {
    BackHandler {
        when {
            saving -> Unit
            multiFrameMode -> onLeaveMultiFrame()
            viewportExpanded -> onCollapseViewport()
            else -> onExit()
        }
    }
}

/**
 * Confirmación de descarte del editor completa: protege la edición viva **y** los fotogramas
 * congelados con un texto que no miente.
 *
 * No reutiliza el diálogo de fotogramas porque su cuerpo cuenta fotogramas, y con solo puntos o
 * ángulos sin guardar ese número sería `0`: diría que no se pierde nada. Aquí el mensaje se elige
 * según lo que de verdad se pierde, y si hay ambas cosas avisa de **todos** los cambios sin guardar
 * en vez de enumerar únicamente los fotogramas. Sin confirmación no se descarta nada; cancelar o
 * descartar no modifican el estado del editor.
 */
@Composable
internal fun AngleEditorCloseDiscardDialog(
    content: AngleEditorDiscardContent,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.angle_editor_close_discard_title)) },
        text = {
            Text(
                when {
                    content.liveUnsavedChanges && content.hasFrozenFrames -> stringResource(
                        R.string.angle_editor_close_discard_body_live_and_frames,
                        content.frozenFrameCount,
                    )

                    content.hasFrozenFrames -> stringResource(
                        R.string.angle_editor_close_discard_body_frames,
                        content.frozenFrameCount,
                    )

                    else -> stringResource(R.string.angle_editor_close_discard_body_live)
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = Spacing.xxxl),
            ) {
                Text(stringResource(R.string.angle_editor_close_discard_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = Spacing.xxxl),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * Parte visual reutilizable del editor de ángulos, sin ViewModel.
 *
 * Muestra el vídeo con [AnalysisVideoSurface] y un [VideoCoordinateMapper] real (dims/rotación
 * aportadas en [videoGeometry]). Solo dibuja y edita cuando [confirmation] pertenece al ancla del
 * estado (`editor.showsGeometryAt`); antes del primer punto no hay ancla y la capa solo admite
 * colocación en el frame confirmado. La lista usa identidades estables de [AngleId], no índices
 * visibles. No guarda, no abre vídeo y no navega.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AngleEditorContent(
    editor: AngleEditorState?,
    videoState: AngleVideoState,
    saveState: AngleSaveState,
    displayState: FrameDisplayState,
    player: Player?,
    videoGeometry: VideoPresentationGeometry?,
    loading: Boolean,
    errorMessage: String?,
    confirmation: AngleFrameConfirmation?,
    actions: AngleEditorContentActions,
    onRefreshFrame: () -> Unit,
    modifier: Modifier = Modifier,
    transport: AngleEditorTransportActions? = null,
    /** Recuperación pendiente publicada por el ViewModel; `None` si no hay nada que recuperar. */
    saveRecovery: AngleSaveRecovery = AngleSaveRecovery.None,
    /**
     * Aviso que **bloquea** el guardado legacy mientras existan fotogramas multi-fotograma en
     * memoria (guardarlos ahora perdería esos fotogramas). `null` = guardado permitido.
     */
    saveBlockedNotice: String? = null,
    /**
     * Visor ampliado. Solo cambia el tamaño del visor: misma composición, misma superficie y ningún
     * segundo player. El estado lo posee el llamador para que el atrás pueda reducirlo primero.
     */
    viewportExpanded: Boolean = false,
    onViewportExpandedChange: (Boolean) -> Unit = {},
) {
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
    val videoPaneDescription = stringResource(R.string.angle_video_pane_description)
    // Misma pista que anuncia el modo encuadre en el panel: la capa de gestos no puede prometer
    // «Listo» porque en ángulos no existe ese botón.
    val framingHint = stringResource(R.string.angle_framing_hint)
    val markerLabel: (AngleMarker) -> String = { it.name }

    var mode by remember(editor?.sourceIdentity) {
        mutableStateOf(initialOverlayMode(editor?.draft?.points?.size ?: 0))
    }
    var cropZoom by remember(editor?.sourceIdentity) { mutableStateOf(MIN_CROP_ZOOM) }
    var cropAlignment by remember(editor?.sourceIdentity) { mutableStateOf(CropAlignment.Centered) }
    // Sin clave por fuente: onSizeChanged no vuelve a dispararse si el tamaño no cambia y el
    // mapper debe sobrevivir a la transición editor nulo -> estado abierto.
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var renameTarget by remember { mutableStateOf<Angle?>(null) }
    var colorTarget by remember { mutableStateOf<Angle?>(null) }
    var deleteTarget by remember { mutableStateOf<Angle?>(null) }

    val angles = editor?.angles.orEmpty()
    val selectedAngle = editor?.selectedAngleId?.let { id -> angles.firstOrNull { it.id == id } }
    val draftPoints = editor?.draft?.points.orEmpty()
    val frameConfirmed = confirmation != null
    // Antes del primer punto no hay ancla: no hay geometría que ocultar y la capa debe poder
    // colocar el primer punto sobre el frame confirmado.
    val showsGeometry = editor != null && confirmation != null &&
        (editor.anchor == null || editor.showsGeometryAt(confirmation.frame))
    val videoReady = videoState is AngleVideoState.Ready
    // Único consumidor con opt-in de zoom relativo a FIT: 1x es la imagen completa (igual que antes)
    // y ampliar NO cambia el modo FIT por CROP. El resto de consumidores del mapper no cambia.
    val mapper = remember(videoGeometry, viewport, cropAlignment, cropZoom) {
        angleCropMapper(
            geometry = videoGeometry,
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
            zoom = cropZoom,
            alignment = cropAlignment,
        )
    }
    // Sin capa real no hay dónde tocar: no se ofrece colocación aunque el frame esté confirmado.
    val canPlace = mapper != null && editor != null && confirmation != null &&
        (editor.anchor == null || editor.showsGeometryAt(confirmation.frame))

    // --- camino accesible (TalkBack / Switch Access) ---------------------------------------------
    // El cursor accesible trabaja en el MISMO espacio de coordenadas que los gestos y entrega su
    // intención a las mismas acciones: no hay geometría, guardas ni mapper paralelos.
    var accessibleMarker by remember(editor?.sourceIdentity, editor?.selectedAngleId) {
        mutableStateOf(AngleMarker.A)
    }
    var accessibleExpanded by remember(editor?.sourceIdentity) { mutableStateOf(false) }
    // Cambiar de ángulo, de marca o de modo descarta la posición pendiente: el cursor nunca
    // aparenta haber movido un punto que ya no es el objetivo.
    var accessibleCursor by remember(
        editor?.sourceIdentity,
        editor?.selectedAngleId,
        accessibleMarker,
        mode,
    ) { mutableStateOf<AnglePoint?>(null) }
    val accessibleTarget = remember(mode, draftPoints, selectedAngle, accessibleMarker, mapper) {
        if (mapper == null) {
            null
        } else {
            angleAccessibleTarget(mode, draftPoints, selectedAngle, accessibleMarker)
        }
    }
    val accessibleGeometry = if (accessibleTarget != null) videoGeometry else null
    val accessibleEnabled = accessibleTarget != null && videoReady && confirmation != null &&
        showsGeometry
    val accessibleActionLabel = stringResource(R.string.angle_accessible_action)
    // Etiqueta opuesta para cuando el panel ya está visible: la acción de la capa no puede prometer
    // «mostrar» algo que ya se está mostrando.
    val accessibleActionHideLabel = stringResource(R.string.angle_accessible_action_hide)
    val listState = rememberLazyListState()
    // El bloque de estado (errores incluidos) queda fijo bajo la barra del visor y se desplaza
    // solo si el font lo desborda: nunca empuja el panel a alto 0 ni desaparece al ampliar.
    val statusScrollState = rememberScrollState()

    // Al completar la terna el borrador vuelve a quedar vacío: se sale de colocación sin inferir
    // nada de la geometría ni cambiar de frame.
    var sawDraftDuringPlacement by remember { mutableStateOf(false) }
    LaunchedEffect(mode, draftPoints.size) {
        if (mode != AngleOverlayMode.PLACEMENT) {
            sawDraftDuringPlacement = false
            return@LaunchedEffect
        }
        if (draftPoints.isNotEmpty()) {
            sawDraftDuringPlacement = true
        } else if (sawDraftDuringPlacement) {
            sawDraftDuringPlacement = false
            mode = AngleOverlayMode.EDIT
        }
    }

    fun setZoom(value: Double) {
        val clamped = value.coerceIn(MIN_CROP_ZOOM, MAX_CROP_ZOOM)
        cropZoom = clamped
        // Sin cambio implícito de modo: el visor es FIT con opt-in y 1x vuelve a la imagen completa.
        if (clamped == MIN_CROP_ZOOM) cropAlignment = CropAlignment.Centered
    }

    // Pinch/arrastre del encuadre: el MISMO algoritmo decide el nuevo estado (zoom + alineación),
    // incluyendo el recorte acotado y el centrado de los ejes que no desbordan. La capa no calcula
    // transformaciones por su cuenta.
    val framingGesture: (ImagePoint, Double, Double, Double) -> Unit =
        { centroid, zoomChange, panX, panY ->
            // El mapper se reconstruye en el instante del evento con el viewport y el estado
            // (zoom/alineación) reales: varios deltas pueden llegar antes de recomponer y deben
            // acumularse sobre lo ya aplicado en vez de partir del frame composicionado.
            val next = angleCropStateAfterGesture(
                geometry = videoGeometry,
                viewportWidth = viewport.width,
                viewportHeight = viewport.height,
                state = CropViewportState(zoom = cropZoom, alignment = cropAlignment),
                gesture = AngleCropGesture(centroid, zoomChange, panX, panY),
            )
            cropZoom = next.zoom
            cropAlignment = next.alignment
        }
    val latestFramingGesture by rememberUpdatedState(framingGesture)
    val overlayDescription = if (showsGeometry) {
        angleOverlayDescription(
            editor = requireNotNull(editor),
            selectedAngle = selectedAngle,
            formatDegrees = formatDegrees,
        )
    } else {
        null
    }
    val anchor = editor?.anchor
    val subtitle = if (anchor != null) {
        stringResource(R.string.angle_frame_anchor, anchor.frameIndex + 1)
    } else {
        null
    }
    val saving = saveState is AngleSaveState.Saving
    val canSave = !saving && videoReady && anchor != null && angles.isNotEmpty() &&
        saveBlockedNotice == null
    // Solo hay recuperación que ofrecer si hay una edición persistible que conservar: sin ángulos no
    // hay nada que copiar y el ViewModel respondería `EmptyAnalysis`.
    val recoveryOffered = angleSaveAsNewOffered(saveRecovery) && angles.isNotEmpty()

    Scaffold(
        modifier = modifier,
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.angle_editor_title),
                subtitle = subtitle,
                onNavigationClick = actions.onCancel,
                navigationContentDescription = stringResource(R.string.angle_cancel),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    // Fotogramas congelados en memoria: el guardado mono-fotograma los perdería, así
                    // que se bloquea de forma explícita en vez de guardar una parte en silencio.
                    saveBlockedNotice?.let { blocked ->
                        Text(
                            text = blocked,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    actions.onOpenMultiFrame?.let { openMultiFrame ->
                        OutlinedButton(
                            onClick = openMultiFrame,
                            enabled = videoReady,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Spacing.xxxl)
                                .testTag(ANGLE_MULTI_FRAME_ENTRY_TAG),
                        ) {
                            Text(stringResource(R.string.angle_multi_frame_open))
                        }
                    }
                    // El guardado normal no puede persistir esta edición (análisis borrado,
                    // escritura sin verificar o revisión avanzada por otro editor): se ofrece la
                    // única salida sin pérdida, explícita y separada. El botón de guardar no crea
                    // copias por sí solo.
                    if (recoveryOffered) {
                        val recoveryDescription =
                            stringResource(R.string.angle_save_as_new_description)
                        OutlinedButton(
                            onClick = actions.onSaveAsNew,
                            enabled = canSave,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Spacing.xxxl)
                                .testTag(ANGLE_SAVE_AS_NEW_TAG)
                                .semantics { contentDescription = recoveryDescription },
                        ) {
                            Text(stringResource(R.string.angle_save_as_new))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = actions.onCancel,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = Spacing.xxxl),
                        ) {
                            Text(stringResource(R.string.common_cancel))
                        }
                        Button(
                            onClick = actions.onSave,
                            enabled = canSave,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = Spacing.xxxl),
                        ) {
                            Text(
                                stringResource(
                                    if (saving) R.string.common_saving else R.string.common_save,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val paneWidthDp = maxWidth.value
            val availableHeightDp = maxHeight.value
            val videoAspect = angleVideoAspect(videoGeometry)
            // El visor normal conserva su tamaño previo y solo el alto final se acota para que
            // barra y panel siempre tengan hueco. Ampliar se ofrece solo si cambia el tamaño real;
            // el estado del panel no se oculta: todo sigue debajo en la zona desplazable.
            val expandedViewport = viewportExpanded
            // Presupuesto: barra de controles + panel mínimo. El visor normal conserva su tamaño
            // previo y solo el alto final se acota para que barra y panel siempre tengan hueco.
            val paneBudgetDp = angleVideoPaneBudgetDp(availableHeightDp)
            val videoPaneHeight = angleVideoPaneHeightDp(
                paneWidthDp = paneWidthDp,
                availableHeightDp = availableHeightDp,
                aspect = videoAspect,
                expanded = expandedViewport,
            )
                .coerceAtMost(paneBudgetDp)
                .coerceAtLeast(1f)
                .dp
            // Ganancia real entre los dos altos FINALES (ambos acotados por el presupuesto).
            val canExpandViewport = angleViewportCanExpand(
                paneWidthDp = paneWidthDp,
                availableHeightDp = availableHeightDp,
                aspect = videoAspect,
            )
            Column(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(videoPaneHeight)
                        .background(Color.Black)
                        .clipToBounds()
                        .onSizeChanged { viewport = it }
                        .semantics { contentDescription = videoPaneDescription }
                        .testTag(ANGLE_VIDEO_PANE_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    AnalysisVideoSurface(
                        player = player,
                        coordinateMapper = mapper,
                        scaleMode = VideoScaleMode.FIT,
                        onRefreshFrame = onRefreshFrame,
                        modifier = Modifier.fillMaxSize(),
                    )
                    VideoPreparationOverlay(active = loading && player == null)
                    if (editor != null && mapper != null) {
                        AngleOverlay(
                            mapper = mapper,
                            state = editor,
                            formatLabel = { _, degrees -> formatDegrees(degrees) },
                            modifier = Modifier.fillMaxSize(),
                            enabled = videoReady,
                            mode = mode,
                            showsGeometry = showsGeometry,
                            accessibilityDescription = overlayDescription,
                            markerLabel = markerLabel,
                            onSelectAngle = actions.onSelectAngle,
                            onPlacePoint = { point -> actions.onPlacePoint(point) },
                            onMoveMarker = { angleId, marker, point ->
                                actions.onMoveMarker(angleId, marker, point)
                            },
                            onMoveDraftMarker = { marker, point ->
                                actions.onMoveDraftMarker(marker, point)
                            },
                            // La capa publica una acción personalizada que abre el panel
                            // accesible: sin ella, TalkBack/Switch Access no tiene forma de
                            // colocar ni ajustar A/B/C sobre el vídeo. La etiqueta dice siempre el
                            // estado real del panel (mostrar u ocultar), nunca una acción que no
                            // va a ocurrir.
                            accessibleControlsLabel = if (accessibleTarget != null) {
                                if (accessibleExpanded) {
                                    accessibleActionHideLabel
                                } else {
                                    accessibleActionLabel
                                }
                            } else {
                                null
                            },
                            onAccessibleControlsRequested = if (accessibleTarget != null) {
                                {
                                    if (accessibleExpanded) {
                                        accessibleExpanded = false
                                    } else {
                                        // El panel vive en la lista desplazable y ya no queda oculto
                                        // al ampliar: se abre y se pide su posición sin tocar el
                                        // tamaño del visor. `scrollToItem` síncrono forzaría un pase
                                        // de layout desde el hilo de la acción semántica, así que se
                                        // usa la petición diferida del `LazyListState`.
                                        accessibleExpanded = true
                                        listState.requestScrollToItem(0)
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                    if (mode == AngleOverlayMode.FRAMING && mapper != null && videoReady) {
                        // Capa de gestos reutilizada del selector: pinch y arrastre piden el cambio
                        // al MISMO mapper (`stateAfterGesture`), que conserva FIT con opt-in.
                        CropFramingOverlay(
                            onGesture = { centroid, zoomChange, panDelta ->
                                latestFramingGesture(
                                    ImagePoint(centroid.x.toDouble(), centroid.y.toDouble()),
                                    zoomChange.toDouble(),
                                    panDelta.x.toDouble(),
                                    panDelta.y.toDouble(),
                                )
                            },
                            // En ángulos no hay «Listo»: el texto del selector prometería una
                            // acción inexistente a TalkBack.
                            description = framingHint,
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(1f),
                        )
                    }
                }

                val zoomText = if (cropZoom > MIN_CROP_ZOOM) {
                    stringResource(R.string.angle_zoom_value, numberFormat.format(cropZoom))
                } else {
                    null
                }
                // Barra compacta FUERA del vídeo: no tapa los puntos, ni la parte superior de la
                // imagen. Sigue visible con el visor ampliado y es la vía de vuelta además del atrás.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AngleViewportControls(
                        zoom = cropZoom,
                        zoomPercentText = zoomText,
                        expanded = expandedViewport,
                        // Medido sobre los altos FINALES: un clip apaisado a ancho completo no
                        // gana nada al ampliar y el botón no debe ofrecer una ampliación vacía.
                        canExpand = canExpandViewport,
                        canReset = cropZoom > MIN_CROP_ZOOM ||
                            cropAlignment != CropAlignment.Centered,
                        zoomEnabled = videoReady && mapper != null,
                        onZoomOut = { setZoom(cropZoom - ANGLE_ZOOM_STEP) },
                        onZoomIn = { setZoom(cropZoom + ANGLE_ZOOM_STEP) },
                        onReset = {
                            setZoom(MIN_CROP_ZOOM)
                            cropAlignment = CropAlignment.Centered
                        },
                        onToggleExpanded = { onViewportExpandedChange(!expandedViewport) },
                    )
                }

                // Errores y avisos: fijos bajo la barra y desplazables si el font los hace más
                // altos que su hueco. El presupuesto es el MISMO en normal y ampliado: lo que
                // queda tras el visor y la barra, reservando un mínimo del panel desplazable. Así
                // un error con font grande se lee con scroll pero nunca deja la lista a alto 0.
                val statusMaxHeightDp = (availableHeightDp - videoPaneHeight.value -
                    ANGLE_VIEWPORT_CONTROLS_BAR_DP - Spacing.xxxl.value)
                    .coerceAtLeast(Spacing.xxxl.value)
                    .coerceAtMost(ANGLE_STATUS_SECTION_MAX_HEIGHT.value)
                statusSection(
                    editor = editor,
                    videoState = videoState,
                    saveState = saveState,
                    errorMessage = errorMessage,
                    frameConfirmed = frameConfirmed,
                    showsGeometry = showsGeometry,
                    modifier = Modifier
                        .heightIn(max = statusMaxHeightDp.dp)
                        .verticalScroll(statusScrollState),
                )

                // Todo lo secundario vive en la zona desplazable: transporte, modo (incluye
                // «Añadir ángulo»), cabecera y lista. Con font grande o altura escasa nada se
                // apila hasta dejar la lista en alto 0, y el visor ampliado conserva editor.
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        start = Spacing.screenHorizontal,
                        end = Spacing.screenHorizontal,
                        top = Spacing.xs,
                        bottom = Spacing.lg,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    if (accessibleTarget != null && accessibleGeometry != null) {
                        item(key = "accessible-points") {
                            AngleAccessiblePointControls(
                                target = accessibleTarget,
                                geometry = accessibleGeometry,
                                marker = accessibleMarker,
                                enabled = accessibleEnabled,
                                expanded = accessibleExpanded,
                                cursor = accessibleCursor,
                                onExpandedChange = { accessibleExpanded = it },
                                onMarkerChange = { accessibleMarker = it },
                                onCursorChange = { accessibleCursor = it },
                                onConfirm = { marker, point ->
                                    // `null` y «elige un ángulo» no confirman nada: el panel
                                    // nunca inventa un cambio que el estado no refleje.
                                    when (val current = accessibleTarget) {
                                        is AngleAccessibleTarget.Draft ->
                                            actions.onPlacePoint(point)

                                        is AngleAccessibleTarget.ExistingDraft ->
                                            actions.onMoveDraftMarker(current.marker, point)

                                        is AngleAccessibleTarget.ExistingAngle ->
                                            actions.onMoveMarker(current.angleId, marker, point)

                                        else -> false
                                    }
                                },
                            )
                        }
                    }
                    if (transport != null) {
                        item(key = "transport") {
                            VideoTransportControls(
                                isPlaying = displayState.isPlaying,
                                enabled = videoReady && displayState.renderedIndex != null &&
                                    !loading,
                                onPreviousFrame = transport.onPreviousFrame,
                                onTogglePlayback = transport.onTogglePlayback,
                                onNextFrame = transport.onNextFrame,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item(key = "mode") {
                        modeControls(
                            mode = mode,
                            canPlace = canPlace,
                            canFrame = mapper != null && videoReady,
                            draftPointCount = draftPoints.size,
                            overlayAvailable = mapper != null,
                            selectedAngle = selectedAngle,
                            onModeChange = { mode = it },
                            onAddAngle = {
                                // Nunca descarta un borrador pendiente: A/B ya colocados solo se
                                // pueden completar en colocación, y recrear la Activity reinicia el
                                // modo de esta composición mientras el ViewModel conserva el
                                // borrador. Descartarlo aquí dejaba el ángulo imposible de terminar.
                                // El descarte explícito tiene su propio botón («descartar borrador»).
                                mode = AngleOverlayMode.PLACEMENT
                            },
                            onCancelDraft = actions.onCancelDraft,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item(key = "list-header") {
                        Column {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                            AngleListHeader(
                                visibleCount = angles.count { it.visible },
                                totalCount = angles.size,
                            )
                        }
                    }
                    if (angles.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = stringResource(R.string.angle_list_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = Spacing.md),
                            )
                        }
                    }
                    items(angles, key = { it.id.value }) { angle ->
                        AngleRow(
                            angle = angle,
                            selected = angle.id == editor?.selectedAngleId,
                            degreesText = formatDegrees(angle.degrees),
                            hiddenLabel = stringResource(R.string.angle_hidden),
                            onSelect = { actions.onSelectAngle(angle.id) },
                            onSetVisible = { visible -> actions.onSetVisible(angle.id, visible) },
                            onRename = { renameTarget = angle },
                            onColor = { colorTarget = angle },
                            onDelete = { deleteTarget = angle },
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { angle ->
        RenameAngleDialog(
            angle = angle,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                actions.onRename(angle.id, name)
                renameTarget = null
            },
        )
    }
    colorTarget?.let { angle ->
        AngleColorDialog(
            angle = angle,
            onDismiss = { colorTarget = null },
            onConfirm = { color ->
                actions.onSetColor(angle.id, color)
                colorTarget = null
            },
        )
    }
    deleteTarget?.let { angle ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.angle_delete_title)) },
            text = { Text(stringResource(R.string.angle_delete_body, angle.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onDelete(angle.id)
                        deleteTarget = null
                    },
                    modifier = Modifier.heightIn(min = Spacing.xxxl),
                ) {
                    Text(stringResource(R.string.angle_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTarget = null },
                    modifier = Modifier.heightIn(min = Spacing.xxxl),
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** Alto máximo del bloque de estado: si el font lo desborda, se desplaza en vez de empujar el panel. */
private val ANGLE_STATUS_SECTION_MAX_HEIGHT = 180.dp

/** Mensajes de estado del vídeo, del frame y del guardado; nunca anuncian éxito antes de tiempo. */
@Composable
private fun statusSection(
    editor: AngleEditorState?,
    videoState: AngleVideoState,
    saveState: AngleSaveState,
    errorMessage: String?,
    frameConfirmed: Boolean,
    showsGeometry: Boolean,
    modifier: Modifier = Modifier,
) {
    val videoMessage: String? = when (videoState) {
        AngleVideoState.Idle, AngleVideoState.Opening -> stringResource(R.string.angle_video_preparing)
        AngleVideoState.AlreadyOpen -> stringResource(R.string.angle_video_already_open)
        AngleVideoState.Closed -> stringResource(R.string.angle_video_closed)
        is AngleVideoState.Unavailable -> when (videoState.reason) {
            AngleVideoUnavailableReason.URI_UNAVAILABLE ->
                stringResource(R.string.angle_video_uri_unavailable)

            AngleVideoUnavailableReason.INDEX_FAILED ->
                stringResource(R.string.angle_video_index_failed)
        }

        is AngleVideoState.Ready -> null
    }
    val frameMessage: String? = when {
        editor == null -> null
        !frameConfirmed -> stringResource(R.string.angle_frame_waiting)
        !showsGeometry -> stringResource(R.string.angle_frame_other)
        else -> null
    }
    val saveMessage: String? = when (saveState) {
        AngleSaveState.Idle, AngleSaveState.Saving -> null
        AngleSaveState.NoVideo -> stringResource(R.string.angle_save_no_video)
        AngleSaveState.EmptyAnalysis -> stringResource(R.string.angle_save_empty)
        is AngleSaveState.Saved -> stringResource(R.string.angle_save_saved)
        is AngleSaveState.Conflict -> stringResource(R.string.angle_save_conflict)
        AngleSaveState.Deleted -> stringResource(R.string.angle_save_deleted)
        is AngleSaveState.Failed -> stringResource(R.string.angle_save_failed)
    }
    val saveIsError = saveState is AngleSaveState.Failed ||
        saveState is AngleSaveState.Conflict ||
        saveState is AngleSaveState.Deleted ||
        saveState is AngleSaveState.NoVideo ||
        saveState is AngleSaveState.EmptyAnalysis
    val saving = saveState is AngleSaveState.Saving
    val savingDescription = stringResource(R.string.common_saving)
    val hint: String? = when {
        editor == null -> null
        frameMessage != null -> null
        editor.angles.isEmpty() && editor.draft.points.isEmpty() ->
            stringResource(R.string.angle_start_hint)

        editor.anchor != null && editor.draft.points.isNotEmpty() ->
            stringResource(R.string.angle_draft_hint, editor.draft.points.size)

        else -> null
    }

    if (videoMessage == null && frameMessage == null && saveMessage == null && errorMessage == null &&
        hint == null && !saving
    ) {
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (saving) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = savingDescription
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
        videoMessage?.let { message ->
            StatusSurface(
                text = message,
                container = MaterialTheme.colorScheme.surfaceContainerLow,
                content = MaterialTheme.colorScheme.onSurface,
            )
        }
        frameMessage?.let { message ->
            StatusSurface(
                text = message,
                container = MaterialTheme.colorScheme.surfaceContainerLow,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        errorMessage?.let { message ->
            StatusSurface(
                text = message,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        saveMessage?.let { message ->
            StatusSurface(
                text = message,
                container = if (saveIsError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    OpenJumpTheme.colors.positiveContainer
                },
                content = if (saveIsError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    OpenJumpTheme.colors.positive
                },
                announce = true,
            )
        }
        hint?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun StatusSurface(
    text: String,
    container: Color,
    content: Color,
    announce: Boolean = false,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = ShapeTokens.small,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { if (announce) liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun modeControls(
    mode: AngleOverlayMode,
    canPlace: Boolean,
    canFrame: Boolean,
    draftPointCount: Int,
    overlayAvailable: Boolean,
    selectedAngle: Angle?,
    onModeChange: (AngleOverlayMode) -> Unit,
    onAddAngle: () -> Unit,
    onCancelDraft: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val framing = mode == AngleOverlayMode.FRAMING
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            FilledTonalButton(
                onClick = onAddAngle,
                enabled = canPlace,
                modifier = Modifier.heightIn(min = Spacing.xxxl),
            ) {
                Text(stringResource(R.string.angle_add))
            }
            FilterChip(
                selected = mode == AngleOverlayMode.EDIT,
                onClick = { onModeChange(AngleOverlayMode.EDIT) },
                enabled = overlayAvailable,
                label = { Text(stringResource(R.string.angle_mode_edit)) },
                modifier = Modifier.heightIn(min = Spacing.xxxl),
            )
            FilterChip(
                selected = framing,
                onClick = {
                    onModeChange(
                        if (framing) AngleOverlayMode.EDIT else AngleOverlayMode.FRAMING,
                    )
                },
                enabled = canFrame,
                label = { Text(stringResource(R.string.angle_mode_framing)) },
                modifier = Modifier.heightIn(min = Spacing.xxxl),
            )
            if (draftPointCount > 0) {
                TextButton(
                    onClick = onCancelDraft,
                    modifier = Modifier.heightIn(min = Spacing.xxxl),
                ) {
                    Text(stringResource(R.string.angle_cancel_draft))
                }
            }
        }
        when {
            mode == AngleOverlayMode.PLACEMENT -> Text(
                text = stringResource(
                    R.string.angle_placement_hint,
                    markerName(draftPointCount),
                    (draftPointCount + 1).coerceAtMost(3),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )

            framing -> {
                // El zoom y el reinicio viven en el grupo compacto sobre el vídeo: aquí solo queda
                // la explicación, para no duplicar controles ni añadir altura al panel.
                Text(
                    text = stringResource(R.string.angle_framing_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> Text(
                text = if (selectedAngle != null) {
                    stringResource(R.string.angle_edit_hint_selected, selectedAngle.name)
                } else {
                    stringResource(R.string.angle_edit_hint_none)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AngleListHeader(visibleCount: Int, totalCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.angle_list_title),
            style = OpenJumpTypes.SectionTitle,
        )
        Text(
            text = stringResource(R.string.angle_list_count, visibleCount, totalCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AngleRow(
    angle: Angle,
    selected: Boolean,
    degreesText: String,
    hiddenLabel: String,
    onSelect: () -> Unit,
    onSetVisible: (Boolean) -> Unit,
    onRename: () -> Unit,
    onColor: () -> Unit,
    onDelete: () -> Unit,
) {
    val visibilityDescription = stringResource(
        if (angle.visible) R.string.angle_hide else R.string.angle_show,
        angle.name,
    )
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        shape = ShapeTokens.small,
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = angle.visible,
                onClick = onSelect,
                role = Role.Button,
            ),
    ) {
        Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Box(
                    modifier = Modifier
                        .size(Spacing.md)
                        .clip(CircleShape)
                        .background(Color(angle.colorArgb))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = angle.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(text = degreesText, style = OpenJumpTypes.MetricValue)
                        if (!angle.visible) {
                            Text(
                                text = hiddenLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Switch(
                    checked = angle.visible,
                    onCheckedChange = onSetVisible,
                    modifier = Modifier.semantics {
                        contentDescription = visibilityDescription
                    },
                )
            }
            if (selected) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    TextButton(
                        onClick = onRename,
                        modifier = Modifier.heightIn(min = Spacing.xxxl),
                    ) {
                        Text(stringResource(R.string.angle_rename))
                    }
                    TextButton(
                        onClick = onColor,
                        modifier = Modifier.heightIn(min = Spacing.xxxl),
                    ) {
                        Text(stringResource(R.string.angle_color))
                    }
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.heightIn(min = Spacing.xxxl),
                    ) {
                        Text(stringResource(R.string.angle_delete))
                    }
                }
            }
        }
    }
}

@Composable
internal fun RenameAngleDialog(
    angle: Angle,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(angle.id) { mutableStateOf(angle.name) }
    // El límite es el contrato compartido con la persistencia y el backup: se avisa aquí, antes de
    // enviar, y nunca se recorta el texto en silencio.
    val input = validateAngleRename(name)
    val blankError = stringResource(R.string.angle_rename_blank_error)
    val tooLongError = stringResource(
        R.string.angle_rename_too_long_error,
        AngleMarkNameContract.MAX_CODE_UNITS,
    )
    val counter = stringResource(
        R.string.angle_rename_counter,
        name.trim().length,
        AngleMarkNameContract.MAX_CODE_UNITS,
    )
    val errorText = when (input) {
        AngleRenameInput.Blank -> blankError
        is AngleRenameInput.TooLong -> tooLongError
        is AngleRenameInput.Accepted -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.angle_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.angle_rename_label)) },
                isError = errorText != null,
                supportingText = { Text(errorText ?: counter) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { (input as? AngleRenameInput.Accepted)?.let { onConfirm(it.name) } },
                enabled = input is AngleRenameInput.Accepted,
                modifier = Modifier.heightIn(min = Spacing.xxxl),
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = Spacing.xxxl),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AngleColorDialog(
    angle: Angle,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var selectedColor by remember(angle.id) { mutableStateOf(angle.colorArgb) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.angle_color_title)) },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                AngleColorPalette.forEachIndexed { index, colorArgb ->
                    val selected = selectedColor == colorArgb
                    val description = stringResource(
                        R.string.angle_color_option,
                        index + 1,
                    )
                    Box(
                        modifier = Modifier
                            .size(Spacing.xxxl)
                            .clip(CircleShape)
                            .background(Color(colorArgb))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape,
                            )
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { selectedColor = colorArgb },
                            )
                            .semantics { contentDescription = description },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = if (Color(colorArgb).luminance() > 0.5f) {
                                    Color.Black
                                } else {
                                    Color.White
                                },
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedColor) },
                modifier = Modifier.heightIn(min = Spacing.xxxl),
            ) {
                Text(stringResource(R.string.common_apply))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = Spacing.xxxl),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * Modo de la capa con el que arranca una composición nueva del editor (incluida la recreación de la
 * Activity).
 *
 * El borrador A/B vive en el ViewModel ([AngleEditorState.draft]) y sobrevive a la recreación,
 * mientras que el modo es estado de composición: reiniciarlo a [AngleOverlayMode.EDIT] dejaba un
 * ángulo a medias sin forma de completarlo (en EDIT un toque no coloca puntos). Por eso el modo
 * inicial se deriva del borrador vivo y no de un valor por defecto: con A o A+B pendientes se
 * arranca en colocación, que es la única vía de completar la terna. Sin borrador, EDIT permite
 * seleccionar y ajustar ángulos ya existentes.
 *
 * Es una función pura para poder fijar el caso «recrear con borrador» sin emulador ni composición.
 */
internal fun initialOverlayMode(draftPointCount: Int): AngleOverlayMode =
    if (draftPointCount > 0) AngleOverlayMode.PLACEMENT else AngleOverlayMode.EDIT

/**
 * Caché de la confirmación que la pantalla muestra y usa para habilitar la capa.
 *
 * La confirmación **no** depende solo de [FrameDisplayState]:
 * [AngleEditorViewModel.currentConfirmation] también devuelve `null` mientras un guardado o una
 * reapertura están en vuelo, y una reapertura puede terminar conservando el frame ya confirmado sin
 * emitir otra salida del visor (un seek a la misma posición no emite callback). Memorizar solo por
 * `displayState` cacheaba ese `null` y dejaba la UI esperando un frame confirmado que el transporte
 * ya tenía en pantalla: sin capa, sin panel accesible y sin forma de colocar C.
 *
 * La clave es, por tanto, **todas** las entradas observables de la función: la salida del visor
 * ([displayState]), la puerta de gestos ([AngleEditorGestureGate]) y el estado del editor (que cambia
 * con la identidad y la generación del vídeo abierto). Con la misma clave se devuelve el valor ya
 * calculado; con cualquiera distinta se vuelve a derivar del estado vivo. La caché **no** fabrica
 * confirmaciones ni PTS: solo memoriza el resultado de `compute`, que es la única fuente.
 *
 * No es estado de snapshot: se escribe durante la composición con los mismos criterios que
 * `remember(clave)`, de modo que una recomposición por cambio de cualquiera de las entradas vuelve a
 * pasar por aquí y ve el valor actualizado.
 */
internal class AngleEditorConfirmationCache {

    /** Clave de la caché: exactamente las entradas observables de `currentConfirmation()`. */
    private data class Key(
        val displayState: FrameDisplayState,
        val gate: AngleEditorGestureGate,
        val editor: AngleEditorState?,
    )

    private var key: Key? = null
    private var cached: AngleFrameConfirmation? = null

    fun confirmation(
        displayState: FrameDisplayState,
        gate: AngleEditorGestureGate,
        editor: AngleEditorState?,
        compute: () -> AngleFrameConfirmation?,
    ): AngleFrameConfirmation? {
        val current = Key(displayState, gate, editor)
        if (current != key) {
            key = current
            cached = compute()
        }
        return cached
    }
}

/** Descripción localizada del nodo único de la capa (TalkBack no enfoca las marcas por separado). */
@Composable
internal fun angleOverlayDescription(
    editor: AngleEditorState,
    selectedAngle: Angle?,
    formatDegrees: (Double) -> String,
): String {
    val visible = editor.angles.count { it.visible }
    val detail = when {
        editor.draft.points.isNotEmpty() -> stringResource(
            R.string.angle_overlay_detail_draft,
            editor.draft.points.size,
        )

        selectedAngle != null -> stringResource(
            R.string.angle_overlay_detail_selected,
            selectedAngle.name,
            formatDegrees(selectedAngle.degrees),
        )

        else -> stringResource(R.string.angle_overlay_detail_none)
    }
    return stringResource(
        R.string.angle_overlay_description,
        visible,
        editor.angles.size,
        detail,
    )
}

internal fun markerName(placedPoints: Int): String = nextDraftMarker(placedPoints).name

private val Angle.degrees: Double
    get() = (measurement as AngleMeasurement.Measured).degrees
