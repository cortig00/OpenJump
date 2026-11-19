package com.openjump.app.ui.angles

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.openjump.app.R
import com.openjump.app.angles.Angle
import com.openjump.app.angles.AngleAnalysisFinalization
import com.openjump.app.angles.AngleDiscardConfirmation
import com.openjump.app.angles.AngleFinalizationBlocker
import com.openjump.app.angles.AngleFrameId
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AngleMeasurement
import com.openjump.app.angles.AnglePoint
import com.openjump.app.data.angles.AngleV2SaveRejection
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.selector.CropAlignment
import com.openjump.app.ui.selector.CropFramingOverlay
import com.openjump.app.ui.selector.CropViewportState
import com.openjump.app.ui.selector.MAX_CROP_ZOOM
import com.openjump.app.ui.selector.MIN_CROP_ZOOM
import com.openjump.app.ui.selector.VideoCoordinateMapper
import com.openjump.app.ui.selector.VideoScaleMode
import com.openjump.app.ui.selector.ViewportSize
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.video.angles.SourceOpenFailure
import java.text.NumberFormat

/** Etiqueta de la entrada al modo fotogramas en el editor mono-fotograma. */
internal const val ANGLE_MULTI_FRAME_ENTRY_TAG = "angle-multi-frame-entry"

/** Etiqueta de la pantalla de fotogramas. */
internal const val ANGLE_MULTI_FRAME_SCREEN_TAG = "angle-multi-frame-screen"

/** Etiqueta del botón que captura el frame pausado. */
internal const val ANGLE_MULTI_FRAME_CAPTURE_TAG = "angle-multi-frame-capture"

/** Etiqueta de la acción de guardar el análisis multi-fotograma (gated y deshabilitable). */
internal const val ANGLE_MULTI_FRAME_SAVE_TAG = "angle-multi-frame-save"

/**
 * Acciones que la pantalla de fotogramas emite hacia el `ViewModel`.
 *
 * Devuelven el resultado tipado de la sesión **sin interpretarlo**: la pantalla solo anuncia un
 * rechazo cuando la sesión lo devolvió y nunca convierte un `Rejected` en éxito. Pedir el bitmap es
 * una lectura con su propio resultado (lease o rechazo); el resto son mutaciones.
 */
internal data class AngleMultiFrameEditorActions(
    val onOpen: () -> Unit,
    val onCapture: () -> AngleMultiFrameResult,
    val onRetryCapture: (AngleFrameId) -> AngleMultiFrameResult,
    val onCancelCapture: (AngleFrameId) -> AngleMultiFrameResult,
    val onSelectFrame: (AngleFrameId) -> AngleMultiFrameResult,
    val onRemoveFrame: (AngleFrameId, AngleDiscardConfirmation) -> AngleMultiFrameResult,
    val onAcquireBitmap: (AngleFrameId) -> AngleMultiFrameBitmapResult,
    val onPlacePoint: (AngleFrameId, AnglePoint) -> AngleMultiFrameResult,
    val onMoveMarker: (AngleFrameId, AngleId, AngleMarker, AnglePoint) -> AngleMultiFrameResult,
    val onMoveDraftMarker: (AngleFrameId, AngleMarker, AnglePoint) -> AngleMultiFrameResult,
    val onCancelDraft: (AngleFrameId) -> AngleMultiFrameResult,
    val onSelectAngle: (AngleFrameId, AngleId?) -> AngleMultiFrameResult,
    val onSetAngleVisible: (AngleFrameId, AngleId, Boolean) -> AngleMultiFrameResult,
    val onRenameAngle: (AngleFrameId, AngleId, String) -> AngleMultiFrameResult,
    val onSetAngleColor: (AngleFrameId, AngleId, Int) -> AngleMultiFrameResult,
    val onDeleteAngle: (AngleFrameId, AngleId) -> AngleMultiFrameResult,
    val requiresRemovalConfirmation: (AngleFrameId) -> Boolean,
    /**
     * Pide al `ViewModel` guardar la sesión como raíz V2 nueva.
     *
     * No devuelve éxito: el resultado real solo llega por [AngleMultiFrameSaveState] **después** de
     * que la transacción V2 termine. La pantalla no lo invoca con el gate apagado.
     */
    val onSaveMultiFrame: () -> Unit,
)

/** Aviso localizado de la capa de fotogramas; nunca se muestra un éxito cuando hubo rechazo. */
internal data class AngleMultiFrameNotice(
    @StringRes val messageRes: Int,
    val args: List<Any> = emptyList(),
)

/**
 * Recurso específico para un motivo de indisponibilidad, o `null` si basta el mensaje genérico.
 *
 * El rechazo por versión de plataforma de un origen no seekable ([SourceOpenFailure.NON_SEEKABLE_UNSUPPORTED])
 * es una limitación del dispositivo, no un detalle del proveedor: se presenta con un aviso propio que
 * pide elegir una copia local y **nunca** se rellena con el detalle técnico del fallo.
 *
 * Función pura (sin composición) para poder fijarla en JVM.
 */
@StringRes
internal fun specificUnavailableMessageRes(reason: AngleMultiFrameUnavailableReason): Int? = when (reason) {
    is AngleMultiFrameUnavailableReason.SourceRejected -> when (reason.failure) {
        SourceOpenFailure.NON_SEEKABLE_UNSUPPORTED -> R.string.angle_multi_frame_unavailable_non_seekable
        else -> null
    }

    AngleMultiFrameUnavailableReason.NoVideoOpen,
    AngleMultiFrameUnavailableReason.OpenerUnavailable,
    is AngleMultiFrameUnavailableReason.OpenFailed -> null
}

/**
 * `true` si el estado multi-fotograma conserva fotogramas **en memoria** (por tanto, perderlos al
 * cerrar el editor o al guardar el flujo mono-fotograma sería una pérdida real).
 *
 * Función pura: la usan tanto el bloqueo del guardado legacy como la confirmación de cierre.
 */
internal fun angleMultiFrameHasFrames(state: AngleMultiFrameState): Boolean =
    (state as? AngleMultiFrameState.Ready)?.state?.frames?.isNotEmpty() == true

/** Número de fotogramas en memoria; `0` si no hay sesión lista. */
internal fun angleMultiFrameFrameCount(state: AngleMultiFrameState): Int =
    (state as? AngleMultiFrameState.Ready)?.state?.frames?.size ?: 0

/**
 * Disponibilidad real de la acción de guardado multi-fotograma en la pantalla.
 *
 * Ningún caso «no disponible» es un éxito y ninguno activa la escritura por su cuenta: la pantalla
 * solo deshabilita el botón y muestra [Blocked.reason].
 */
internal sealed interface AngleMultiFrameSaveAvailability {

    /** El análisis es finalizable y el gate de producto está activo: el botón se puede pulsar. */
    data object Ready : AngleMultiFrameSaveAvailability

    /** Hay un guardado en vuelo: no se ofrece otra pulsación y la salida queda bloqueada. */
    data object InFlight : AngleMultiFrameSaveAvailability

    /**
     * El guardado ya terminó con éxito (`Saved`/`AlreadySaved`): la pantalla muestra el resultado y
     * no repite la acción. La navegación al detalle la decide la ruta con el `rootId` real.
     */
    data object Done : AngleMultiFrameSaveAvailability

    /** No se puede guardar ahora; [reason] explica el motivo y nunca es un mensaje de éxito. */
    data class Blocked(val reason: AngleMultiFrameNotice) : AngleMultiFrameSaveAvailability
}

/**
 * Decide, con datos puros, si la acción de guardar multi-fotograma se puede pulsar.
 *
 * Orden deliberado:
 * 1. un guardado en vuelo manda sobre todo lo demás (la sesión está bloqueada de verdad);
 * 2. un guardado ya terminado con éxito no se repite ([AngleMultiFrameSaveAvailability.Done]);
 * 3. el gate de producto ([ANGLE_MULTI_FRAME_SAVE_UI_ENABLED]) apagado deshabilita siempre, aunque
 *    los datos fueran finalizables: es la garantía de que la UI no escribe V2 antes de tiempo;
 * 4. sin sesión lista no hay nada que guardar;
 * 5. el plan de finalización puro decide: sin fotogramas, sin ángulo completo, con un borrador A/B
 *    pendiente o sin imagen congelada el guardado queda bloqueado con su motivo tipado.
 *
 * Una captura en vuelo también bloquea: su fotograma todavía no tiene imagen adoptada y el guardado
 * no puede describir un estado que aún no existe.
 */
internal fun angleMultiFrameSaveAvailability(
    gateEnabled: Boolean,
    multiFrame: AngleMultiFrameState,
    saveState: AngleMultiFrameSaveState,
): AngleMultiFrameSaveAvailability {
    if (saveState is AngleMultiFrameSaveState.Saving) return AngleMultiFrameSaveAvailability.InFlight
    if (saveState is AngleMultiFrameSaveState.Saved ||
        saveState is AngleMultiFrameSaveState.AlreadySaved
    ) {
        return AngleMultiFrameSaveAvailability.Done
    }
    if (!gateEnabled) {
        return AngleMultiFrameSaveAvailability.Blocked(
            AngleMultiFrameNotice(R.string.angle_multi_frame_save_gate),
        )
    }
    val session = (multiFrame as? AngleMultiFrameState.Ready)?.state
        ?: return AngleMultiFrameSaveAvailability.Blocked(
            AngleMultiFrameNotice(R.string.angle_multi_frame_save_blocked_not_ready),
        )
    if (session.frames.isEmpty()) {
        return AngleMultiFrameSaveAvailability.Blocked(
            AngleMultiFrameNotice(R.string.angle_multi_frame_save_blocked_no_frames),
        )
    }
    if (session.capturingFrame != null) {
        return AngleMultiFrameSaveAvailability.Blocked(
            AngleMultiFrameNotice(R.string.angle_multi_frame_notice_capture_in_flight),
        )
    }
    return when (val finalization = session.finalization) {
        is AngleAnalysisFinalization.Ready -> AngleMultiFrameSaveAvailability.Ready

        is AngleAnalysisFinalization.Blocked -> AngleMultiFrameSaveAvailability.Blocked(
            finalization.blockers.firstOrNull()?.let { blocker ->
                angleMultiFrameSaveBlockerNotice(session, blocker)
            } ?: AngleMultiFrameNotice(R.string.angle_multi_frame_save_blocked_not_ready),
        )
    }
}

/** Traduce un bloqueo de finalización a un texto localizado con el fotograma afectado. */
private fun angleMultiFrameSaveBlockerNotice(
    session: AngleMultiFrameSessionState,
    blocker: AngleFinalizationBlocker,
): AngleMultiFrameNotice = when (blocker) {
    AngleFinalizationBlocker.NoFrames ->
        AngleMultiFrameNotice(R.string.angle_multi_frame_save_blocked_no_frames)

    is AngleFinalizationBlocker.NoCompleteAngle -> AngleMultiFrameNotice(
        R.string.angle_multi_frame_save_blocked_no_angle,
        listOf(frameLabelOf(session, blocker.frameId)),
    )

    is AngleFinalizationBlocker.PendingDraft -> AngleMultiFrameNotice(
        R.string.angle_multi_frame_save_blocked_draft,
        listOf(frameLabelOf(session, blocker.frameId), blocker.placedPoints),
    )

    is AngleFinalizationBlocker.MissingImage ->
        AngleMultiFrameNotice(R.string.angle_multi_frame_image_pending)
}

/** Etiqueta 1-based del fotograma; `0` si ya no está en la sesión (nunca se inventa un índice). */
private fun frameLabelOf(session: AngleMultiFrameSessionState, frameId: AngleFrameId): Int =
    session.frame(frameId)?.anchor?.frameIndex?.plus(1) ?: 0

/**
 * Texto del resultado **ya publicado** del guardado multi-fotograma.
 *
 * `null` mientras no haya nada que contar (inactivo o en vuelo). Un fallo siempre se muestra como
 * fallo: la pantalla no convierte ningún rechazo en éxito y el borrador sigue en pantalla.
 */
internal fun angleMultiFrameSaveStatusNotice(
    saveState: AngleMultiFrameSaveState,
): AngleMultiFrameNotice? = when (saveState) {
    AngleMultiFrameSaveState.Idle,
    AngleMultiFrameSaveState.Saving -> null

    is AngleMultiFrameSaveState.Saved ->
        AngleMultiFrameNotice(R.string.angle_multi_frame_save_saved)

    is AngleMultiFrameSaveState.AlreadySaved ->
        AngleMultiFrameNotice(R.string.angle_multi_frame_save_already_saved)

    is AngleMultiFrameSaveState.Failed -> angleMultiFrameSaveFailureNotice(saveState.reason)
}

/**
 * Traduce un motivo de rechazo del guardado a un texto localizado **recuperable**.
 *
 * Los motivos que el usuario puede resolver (conflicto de contenido, presupuesto, guardado ya en
 * vuelo) se distinguen; el resto usa el mensaje genérico de fallo. Nunca se mapea a éxito.
 */
internal fun angleMultiFrameSaveFailureNotice(
    rejection: AngleMultiFrameSaveRejection,
): AngleMultiFrameNotice = when (rejection) {
    AngleMultiFrameSaveRejection.SaveInFlight ->
        AngleMultiFrameNotice(R.string.angle_multi_frame_notice_busy)

    is AngleMultiFrameSaveRejection.BudgetExceeded ->
        AngleMultiFrameNotice(R.string.angle_multi_frame_save_failed_budget)

    is AngleMultiFrameSaveRejection.StoreRejected -> when (rejection.reason) {
        is AngleV2SaveRejection.AlreadyStoredConflict ->
            AngleMultiFrameNotice(R.string.angle_multi_frame_save_failed_conflict)

        is AngleV2SaveRejection.BudgetExceeded ->
            AngleMultiFrameNotice(R.string.angle_multi_frame_save_failed_budget)

        else -> AngleMultiFrameNotice(R.string.angle_multi_frame_save_failed)
    }

    else -> AngleMultiFrameNotice(R.string.angle_multi_frame_save_failed)
}

private fun mebibytes(bytes: Long): Long = bytes / (1024L * 1024L)

/**
 * Traduce un rechazo de la sesión a un texto localizado.
 *
 * Los motivos que el usuario puede resolver se distinguen (frame sin pausar, fotograma no activo,
 * memoria agotada, captura en vuelo, guardado/reapertura en curso); el resto usa un mensaje genérico
 * de «no se completó». Nunca se mapea un rechazo a un mensaje de éxito.
 */
internal fun angleMultiFrameNotice(rejection: AngleMultiFrameRejection): AngleMultiFrameNotice =
    when (rejection) {
        AngleMultiFrameRejection.FrameNotPaused ->
            AngleMultiFrameNotice(R.string.angle_multi_frame_notice_paused)

        is AngleMultiFrameRejection.NotActiveFrame ->
            AngleMultiFrameNotice(R.string.angle_multi_frame_notice_select_frame)

        is AngleMultiFrameRejection.FrameAlreadyTracked ->
            AngleMultiFrameNotice(R.string.angle_multi_frame_notice_rejected)

        is AngleMultiFrameRejection.ConfirmationPtsMismatch ->
            AngleMultiFrameNotice(R.string.angle_multi_frame_notice_rejected)

        is AngleMultiFrameRejection.ForeignConfirmation,
        is AngleMultiFrameRejection.StaleConfirmation ->
            AngleMultiFrameNotice(R.string.angle_multi_frame_notice_rejected)

        is AngleMultiFrameRejection.BitmapUnavailable ->
            AngleMultiFrameNotice(R.string.angle_multi_frame_notice_not_ready)

        is AngleMultiFrameRejection.InternalInconsistency ->
            AngleMultiFrameNotice(R.string.angle_multi_frame_notice_rejected)

        AngleMultiFrameRejection.SessionNotOpened ->
            AngleMultiFrameNotice(R.string.angle_multi_frame_notice_rejected)

        AngleMultiFrameRejection.SaveInFlight,
        AngleMultiFrameRejection.MultiFrameSaveInFlight,
        AngleMultiFrameRejection.ReopenInFlight ->
            AngleMultiFrameNotice(R.string.angle_multi_frame_notice_busy)

        is AngleMultiFrameRejection.Coordinator -> angleMultiFrameCoordinatorNotice(rejection.rejection)
    }

private fun angleMultiFrameCoordinatorNotice(
    rejection: AngleDraftCoordinatorRejection,
): AngleMultiFrameNotice = when (rejection) {
    is AngleDraftCoordinatorRejection.MemoryBudgetExceeded -> AngleMultiFrameNotice(
        R.string.angle_multi_frame_notice_memory,
        listOf(
            mebibytes(rejection.retainedBytes),
            // El texto distingue retenido / reservado / límite: se informa la reserva real del
            // intento en vuelo, no el peor caso teórico.
            mebibytes(rejection.reservedBytes),
            mebibytes(rejection.limitBytes),
        ),
    )

    is AngleDraftCoordinatorRejection.SessionCaptureInFlight,
    is AngleDraftCoordinatorRejection.CaptureAlreadyInFlight ->
        AngleMultiFrameNotice(R.string.angle_multi_frame_notice_capture_in_flight)

    is AngleDraftCoordinatorRejection.FrameImageNotReady,
    is AngleDraftCoordinatorRejection.ImageNotRetained ->
        AngleMultiFrameNotice(R.string.angle_multi_frame_notice_not_ready)

    AngleDraftCoordinatorRejection.Closed ->
        AngleMultiFrameNotice(R.string.angle_multi_frame_closed)

    is AngleDraftCoordinatorRejection.DraftRejected -> AngleMultiFrameNotice(
        R.string.angle_multi_frame_notice_rejected,
    )

    else -> AngleMultiFrameNotice(R.string.angle_multi_frame_notice_rejected)
}

/** Texto localizado del último fallo de captura de un fotograma. */
internal fun angleMultiFrameCaptureFailureNotice(
    reason: AngleDraftCaptureFailureReason,
): AngleMultiFrameNotice = when (reason) {
    AngleDraftCaptureFailureReason.CANCELLED ->
        AngleMultiFrameNotice(R.string.angle_multi_frame_capture_cancelled)

    AngleDraftCaptureFailureReason.MEMORY_BUDGET_EXCEEDED ->
        AngleMultiFrameNotice(R.string.angle_multi_frame_capture_failed_memory)

    else -> AngleMultiFrameNotice(R.string.angle_multi_frame_capture_failed)
}

/**
 * Pantalla del modo multi-fotograma: captura el frame pausado confirmado por el visor, permite
 * elegir entre fotogramas ya capturados y marcar geometría **sobre cada bitmap congelado**.
 *
 * Desde este slice la pantalla **ofrece guardar** el análisis como raíz V2 nueva, pero la acción está
 * gobernada por dos autoridades que esta capa no puede saltarse:
 * - [ANGLE_MULTI_FRAME_SAVE_UI_ENABLED] (`false` por defecto): con el gate apagado el botón se
 *   muestra deshabilitado con su motivo y la pantalla jamás invoca
 *   [AngleMultiFrameEditorActions.onSaveMultiFrame];
 * - [saveState]: el estado real que publica el `ViewModel`. Mientras es
 *   [AngleMultiFrameSaveState.Saving] la salida queda bloqueada y no se ofrece otra pulsación; un
 *   fallo se anuncia como fallo (el borrador sigue en pantalla) y solo un `Saved`/`AlreadySaved`
 *   —publicado después de la transacción— se muestra como guardado. La navegación al detalle V2 no
 *   la hace esta pantalla.
 *
 * No crea players, no descarga y no escribe filas por su cuenta.
 */
@Composable
internal fun AngleMultiFrameEditorScreen(
    multiFrame: AngleMultiFrameState,
    actions: AngleMultiFrameEditorActions,
    onBackToViewer: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Estado real del guardado multi-fotograma publicado por el `ViewModel`. */
    saveState: AngleMultiFrameSaveState = AngleMultiFrameSaveState.Idle,
    /** Datos puros de la sesión y del guardado que deciden si la acción está disponible hoy. */
    saveAvailability: AngleMultiFrameSaveAvailability = angleMultiFrameSaveAvailability(
        gateEnabled = ANGLE_MULTI_FRAME_SAVE_UI_ENABLED,
        multiFrame = multiFrame,
        saveState = saveState,
    ),
) {
    val backDescription = stringResource(R.string.angle_multi_frame_back)
    // En vuelo: ni salir ni cerrar. El guardado está leyendo la geometría y los leases de las
    // imágenes; abandonar la superficie ahora dejaría el resultado sin destino visible.
    val saving = saveAvailability is AngleMultiFrameSaveAvailability.InFlight
    val saveStatus = angleMultiFrameSaveStatusNotice(saveState)
    val busyDescription = stringResource(R.string.angle_multi_frame_notice_busy)
    // Visor ampliado de la imagen congelada: estado de presentación local, sin reorientar la
    // Activity, sin segundo player y sin tocar la sesión ni los leases de bitmap. El atrás y el
    // botón de volver reducen el visor ANTES de abandonar; con un guardado en vuelo no se navega.
    val readyMultiFrame = multiFrame as? AngleMultiFrameState.Ready
    var viewportExpanded by remember(readyMultiFrame?.state?.sourceIdentity) {
        mutableStateOf(false)
    }
    val viewportCanCollapse = readyMultiFrame != null && viewportExpanded
    val requestBackToViewer: () -> Unit = {
        when {
            saving -> Unit
            viewportCanCollapse -> viewportExpanded = false
            else -> onBackToViewer()
        }
    }
    BackHandler {
        when {
            saving -> Unit
            viewportCanCollapse -> viewportExpanded = false
            else -> onBackToViewer()
        }
    }
    val subtitle = when (multiFrame) {
        is AngleMultiFrameState.Ready -> stringResource(
            R.string.angle_multi_frame_count,
            multiFrame.state.frames.size,
        )

        else -> null
    }
    Scaffold(
        modifier = modifier.testTag(ANGLE_MULTI_FRAME_SCREEN_TAG),
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.angle_multi_frame_title),
                subtitle = subtitle,
                onNavigationClick = requestBackToViewer,
                navigationContentDescription = backDescription,
                actions = {
                    TextButton(
                        onClick = onClose,
                        enabled = !saving,
                        modifier = Modifier.heightIn(min = Spacing.xxxl),
                    ) {
                        Text(stringResource(R.string.angle_multi_frame_close))
                    }
                },
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
                    // Motivo del estado de la acción: en vuelo, resultado ya publicado (fallo o
                    // éxito real) o bloqueo concreto. Nunca se anuncia un éxito que no ocurrió.
                    when {
                        saving -> {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        contentDescription = busyDescription
                                        liveRegion = LiveRegionMode.Polite
                                    },
                            )
                            StatusMessage(text = busyDescription)
                        }

                        saveStatus != null -> StatusMessage(
                            text = stringResource(
                                saveStatus.messageRes,
                                *saveStatus.args.toTypedArray(),
                            ),
                        )

                        saveAvailability is AngleMultiFrameSaveAvailability.Blocked -> {
                            val reason = saveAvailability.reason
                            StatusMessage(
                                text = stringResource(reason.messageRes, *reason.args.toTypedArray()),
                            )
                        }

                        else -> StatusMessage(
                            text = stringResource(R.string.angle_multi_frame_save_note),
                        )
                    }
                    Button(
                        onClick = actions.onSaveMultiFrame,
                        // Solo con datos finalizables y gate activo: la pantalla no puede escribir
                        // V2 por su cuenta ni con el guardado ya en vuelo.
                        enabled = saveAvailability is AngleMultiFrameSaveAvailability.Ready,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.xxxl)
                            .testTag(ANGLE_MULTI_FRAME_SAVE_TAG),
                    ) {
                        Text(
                            stringResource(
                                if (saving) R.string.common_saving else R.string.angle_multi_frame_save,
                            ),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = requestBackToViewer,
                            enabled = !saving,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = Spacing.xxxl),
                        ) {
                            Text(stringResource(R.string.angle_multi_frame_back))
                        }
                        Button(
                            onClick = onClose,
                            enabled = !saving,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = Spacing.xxxl),
                        ) {
                            Text(stringResource(R.string.angle_multi_frame_close))
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (multiFrame) {
                AngleMultiFrameState.NotOpened, AngleMultiFrameState.Opening -> {
                    LaunchedEffect(multiFrame) {
                        if (multiFrame == AngleMultiFrameState.NotOpened) actions.onOpen()
                    }
                    OpeningContent()
                }

                is AngleMultiFrameState.Unavailable -> UnavailableContent(
                    reason = multiFrame.reason,
                    onRetry = actions.onOpen,
                )

                AngleMultiFrameState.Closed -> StatusMessage(
                    text = stringResource(R.string.angle_multi_frame_closed),
                )

                is AngleMultiFrameState.Ready -> ReadyContent(
                    state = multiFrame.state,
                    actions = actions,
                    saving = saving,
                    viewportExpanded = viewportExpanded,
                    onViewportExpandedChange = { viewportExpanded = it },
                )
            }
        }
    }
}

@Composable
private fun OpeningContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        val description = stringResource(R.string.angle_multi_frame_preparing)
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = description
                    liveRegion = LiveRegionMode.Polite
                },
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnavailableContent(
    reason: AngleMultiFrameUnavailableReason,
    onRetry: () -> Unit,
) {
    val message = when (reason) {
        AngleMultiFrameUnavailableReason.NoVideoOpen ->
            stringResource(R.string.angle_multi_frame_unavailable_no_video)

        AngleMultiFrameUnavailableReason.OpenerUnavailable ->
            stringResource(R.string.angle_multi_frame_unavailable_no_opener)

        is AngleMultiFrameUnavailableReason.SourceRejected -> {
            val specific = specificUnavailableMessageRes(reason)
            if (specific != null) {
                stringResource(specific)
            } else {
                stringResource(R.string.angle_multi_frame_unavailable_source, reason.detail)
            }
        }

        is AngleMultiFrameUnavailableReason.OpenFailed ->
            stringResource(R.string.angle_multi_frame_unavailable_error, reason.errorClass)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        StatusMessage(text = message)
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = Spacing.xxxl),
        ) {
            Text(stringResource(R.string.common_retry))
        }
    }
}

@Composable
private fun StatusMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(vertical = Spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadyContent(
    state: AngleMultiFrameSessionState,
    actions: AngleMultiFrameEditorActions,
    /** Guardado en vuelo: la geometría y los fotogramas no se tocan mientras se leen. */
    saving: Boolean = false,
    /** Visor ampliado: solo presentación; no reorienta, no recrea superficie ni toca la sesión. */
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
    val context = LocalContext.current
    // Pista real del modo encuadre de ángulos: la capa de gestos del selector anunciaba «Listo»,
    // un botón que aquí no existe.
    val framingHint = stringResource(R.string.angle_framing_hint)
    val memoryText = stringResource(
        R.string.angle_multi_frame_memory,
        Formatter.formatShortFileSize(context, state.retainedBytes),
    )
    var notice by remember(state.sourceIdentity) { mutableStateOf<AngleMultiFrameNotice?>(null) }
    var mode by remember(state.sourceIdentity) { mutableStateOf(AngleOverlayMode.EDIT) }
    var removeTarget by remember(state.sourceIdentity) { mutableStateOf<AngleFrameId?>(null) }
    var renameTarget by remember(state.sourceIdentity) {
        mutableStateOf<Pair<AngleFrameId, Angle>?>(null)
    }
    var colorTarget by remember(state.sourceIdentity) {
        mutableStateOf<Pair<AngleFrameId, Angle>?>(null)
    }
    var deleteTarget by remember(state.sourceIdentity) {
        mutableStateOf<Pair<AngleFrameId, Angle>?>(null)
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val listState = rememberLazyListState()
    val activeFrameId = state.activeFrameId
    val active = state.activeFrame
    // Encuadre por fotograma: cada imagen congelada conserva su zoom y su pan; 1x es el FIT
    // completo (imagen entera), no una ampliación implícita. No se persiste ni toca la sesión.
    var viewportStates by remember(state.sourceIdentity) {
        mutableStateOf<Map<AngleFrameId, CropViewportState>>(emptyMap())
    }
    val frameViewport = activeFrameId?.let { viewportStates[it] } ?: CropViewportState()
    fun updateFrameViewport(transform: (CropViewportState) -> CropViewportState) {
        val frameId = activeFrameId ?: return
        // Estado leído en el instante de la llamada: varios gestos pueden llegar antes de
        // recomponer y deben partir de lo ya aplicado, no del frame composicionado.
        val current = viewportStates[frameId] ?: CropViewportState()
        viewportStates = viewportStates + (frameId to transform(current))
    }
    fun setFrameZoom(value: Double) {
        val clamped = value.coerceIn(MIN_CROP_ZOOM, MAX_CROP_ZOOM)
        updateFrameViewport { current ->
            CropViewportState(
                zoom = clamped,
                // Reiniciar el zoom vuelve también al encuadre centrado.
                alignment = if (clamped == MIN_CROP_ZOOM) {
                    CropAlignment.Centered
                } else {
                    current.alignment
                },
            )
        }
    }

    // Paso de zoom relativo al estado real: dos pulsaciones antes de recomponer se acumulan en vez
    // de quedarse con la última.
    fun stepFrameZoom(delta: Double) {
        updateFrameViewport { current ->
            val clamped = (current.zoom + delta).coerceIn(MIN_CROP_ZOOM, MAX_CROP_ZOOM)
            CropViewportState(
                zoom = clamped,
                alignment = if (clamped == MIN_CROP_ZOOM) {
                    CropAlignment.Centered
                } else {
                    current.alignment
                },
            )
        }
    }
    val editorState = active?.editor
    val selectedAngle = editorState?.let { current ->
        current.selectedAngleId?.let { id -> current.angles.firstOrNull { it.id == id } }
    }
    val draftPoints = editorState?.draft?.points.orEmpty()
    val angles = editorState?.angles.orEmpty()

    fun report(result: AngleMultiFrameResult) {
        when (result) {
            is AngleMultiFrameResult.Applied -> notice = null
            is AngleMultiFrameResult.Rejected ->
                notice = angleMultiFrameNotice(result.rejection)
        }
    }

    fun applied(result: AngleMultiFrameResult): Boolean = when (result) {
        is AngleMultiFrameResult.Applied -> {
            notice = null
            true
        }

        is AngleMultiFrameResult.Rejected -> {
            notice = angleMultiFrameNotice(result.rejection)
            false
        }
    }

    // Lease del bitmap EXACTO del fotograma activo: el mismo objeto inmutable de la captura. Se
    // cierra al salir de composición o al cambiar de fotograma; mientras esté abierto los píxeles
    // no se reciclan por debajo del dibujo.
    var lease by remember(activeFrameId) { mutableStateOf<AngleMultiFrameBitmapLease?>(null) }
    val activeCanEdit = active?.canEdit == true
    DisposableEffect(activeFrameId, activeCanEdit) {
        if (activeFrameId != null && activeCanEdit) {
            when (val result = actions.onAcquireBitmap(activeFrameId)) {
                is AngleMultiFrameBitmapResult.Granted -> lease = result.lease
                is AngleMultiFrameBitmapResult.Rejected -> {
                    lease = null
                    notice = angleMultiFrameNotice(result.rejection)
                }
            }
        }
        onDispose { lease?.close() }
    }
    val openLease = lease?.takeIf { it.isOpen }
    val imageGeometry: VideoPresentationGeometry? = openLease?.imageGeometry
    // La superficie dibuja la MISMA transformación que publica el mapper (FIT 1x = imagen
    // completa): el recorte y la escala salen del rectángulo de contenido, no de una capa paralela,
    // así los puntos y los píxeles no pueden divergir.
    val mapper = remember(imageGeometry, viewport, activeFrameId, frameViewport) {
        if (imageGeometry != null && viewport.width > 0 && viewport.height > 0) {
            VideoCoordinateMapper(
                video = imageGeometry,
                viewport = ViewportSize(viewport.width.toDouble(), viewport.height.toDouble()),
                scaleMode = VideoScaleMode.FIT,
                cropAlignment = frameViewport.alignment,
                zoom = frameViewport.zoom,
                allowFitZoom = true,
            )
        } else {
            null
        }
    }
    // Pinch/arrastre de encuadre sobre la imagen congelada: el MISMO algoritmo (FIT con opt-in)
    // decide zoom y pan acotado, reconstruido dentro del transform con la geometría y el viewport
    // actuales y sobre el estado ya acumulado de ese fotograma. La capa sigue leyendo el mismo
    // rectángulo, así que los puntos no se desalinean.
    val framingGesture: (ImagePoint, Double, Double, Double) -> Unit =
        { centroid, zoomChange, panX, panY ->
            updateFrameViewport { current ->
                angleCropStateAfterGesture(
                    geometry = imageGeometry,
                    viewportWidth = viewport.width,
                    viewportHeight = viewport.height,
                    state = current,
                    gesture = AngleCropGesture(centroid, zoomChange, panX, panY),
                )
            }
        }
    val latestFramingGesture by rememberUpdatedState(framingGesture)
    val showsGeometry = editorState != null && active != null &&
        (editorState.anchor == null || editorState.showsGeometryAt(active.anchor))
    val overlayDescription = if (editorState != null && showsGeometry) {
        angleOverlayDescription(
            editor = editorState,
            selectedAngle = selectedAngle,
            formatDegrees = formatDegrees,
        )
    } else {
        null
    }

    // --- camino accesible: mismo espacio de coordenadas y mismas acciones que los gestos ---------
    var accessibleMarker by remember(activeFrameId, editorState?.selectedAngleId) {
        mutableStateOf(AngleMarker.A)
    }
    var accessibleExpanded by remember(state.sourceIdentity) { mutableStateOf(false) }
    var accessibleCursor by remember(
        activeFrameId,
        editorState?.selectedAngleId,
        accessibleMarker,
        mode,
    ) { mutableStateOf<AnglePoint?>(null) }
    val accessibleTarget = remember(mode, draftPoints, selectedAngle, accessibleMarker, mapper) {
        if (mapper == null) null else angleAccessibleTarget(mode, draftPoints, selectedAngle, accessibleMarker)
    }
    val accessibleEnabled = accessibleTarget != null && imageGeometry != null &&
        active?.canEdit == true && showsGeometry && !saving
    val accessibleActionLabel = stringResource(R.string.angle_accessible_action)
    val accessibleActionHideLabel = stringResource(R.string.angle_accessible_action_hide)

    // Al completar la terna el borrador vuelve a quedar vacío: se sale de colocación sin inferir nada.
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

    val capturing = state.capturingFrame

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Mismo criterio que el visor en vivo: el alto se deriva del aspecto real de la imagen
        // congelada y queda acotado, en vez de forzar un 16:9 que deja el vertical diminuto. El
        // presupuesto reserva la barra compacta y un panel mínimo; ampliar no deshabilita nada.
        val paneHeight = angleVideoPaneHeightDp(
            paneWidthDp = maxWidth.value,
            availableHeightDp = maxHeight.value,
            aspect = angleVideoAspect(imageGeometry),
            expanded = viewportExpanded,
        )
            .coerceAtMost(angleVideoPaneBudgetDp(maxHeight.value))
            .coerceAtLeast(1f)
            .dp
        // Misma medida que el visor en vivo: ganancia entre los dos altos FINALES ya acotados.
        val canExpandViewport = angleViewportCanExpand(
            paneWidthDp = maxWidth.value,
            availableHeightDp = maxHeight.value,
            aspect = angleVideoAspect(imageGeometry),
        )
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(paneHeight)
                    .background(Color.Black)
                    // El zoom recorta dentro del visor: ni la imagen ni los trazos se salen del
                    // panel que mide el mapper.
                    .clipToBounds()
                    .onSizeChanged { viewport = it }
                    .testTag(ANGLE_VIDEO_PANE_TAG),
                contentAlignment = Alignment.Center,
            ) {
                // Lienzo congelado compartido con el workspace: mismo bitmap, misma geometría,
                // mismo mapper FIT y mismos callbacks. El lease lo adquirió este visor y solo este
                // visor lo cierra; el lienzo nunca copia píxeles ni pide otro lease.
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
                    mode = mode,
                    enabled = active?.canEdit == true && !saving,
                    showsGeometry = showsGeometry,
                    accessibilityDescription = overlayDescription,
                    formatDegrees = formatDegrees,
                    accessibleControlsLabel = if (accessibleTarget != null) {
                        if (accessibleExpanded) accessibleActionHideLabel else accessibleActionLabel
                    } else {
                        null
                    },
                    onAccessibleControlsRequested = if (accessibleTarget != null) {
                        {
                            if (accessibleExpanded) {
                                accessibleExpanded = false
                            } else {
                                // El panel es el primer ítem de la lista desplazable: se abre y
                                // se pide su posición con la petición diferida del estado.
                                accessibleExpanded = true
                                listState.requestScrollToItem(0)
                            }
                        }
                    } else {
                        null
                    },
                    framingDescription = framingHint,
                    onFramingGesture = { centroid, zoomChange, panX, panY ->
                        latestFramingGesture(centroid, zoomChange, panX, panY)
                    },
                    onSelectAngle = { angleId ->
                        activeFrameId?.let { report(actions.onSelectAngle(it, angleId)) }
                    },
                    onPlacePoint = { point ->
                        activeFrameId?.let { applied(actions.onPlacePoint(it, point)) }
                    },
                    onMoveMarker = { angleId, marker, point ->
                        activeFrameId?.let {
                            applied(actions.onMoveMarker(it, angleId, marker, point))
                        }
                    },
                    onMoveDraftMarker = { marker, point ->
                        activeFrameId?.let {
                            applied(actions.onMoveDraftMarker(it, marker, point))
                        }
                    },
                )
            }

            // Barra compacta FUERA del vídeo: no tapa puntos ni la parte superior de la imagen y
            // sigue disponible con el visor ampliado (vía de vuelta además del atrás).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val frameZoomText = if (frameViewport.zoom > MIN_CROP_ZOOM) {
                    stringResource(R.string.angle_zoom_value, numberFormat.format(frameViewport.zoom))
                } else {
                    null
                }
                AngleViewportControls(
                    zoom = frameViewport.zoom,
                    zoomPercentText = frameZoomText,
                    expanded = viewportExpanded,
                    canExpand = canExpandViewport,
                    canReset = frameViewport.zoom > MIN_CROP_ZOOM ||
                        frameViewport.alignment != CropAlignment.Centered,
                    zoomEnabled = openLease != null && active?.canEdit == true && !saving,
                    onZoomOut = { stepFrameZoom(-ANGLE_ZOOM_STEP) },
                    onZoomIn = { stepFrameZoom(ANGLE_ZOOM_STEP) },
                    onReset = { setFrameZoom(MIN_CROP_ZOOM) },
                    onToggleExpanded = { onViewportExpandedChange(!viewportExpanded) },
                )
            }

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
                if (accessibleTarget != null && imageGeometry != null && activeFrameId != null) {
                    item(key = "accessible-points") {
                        AngleAccessiblePointControls(
                            target = accessibleTarget,
                            geometry = imageGeometry,
                            marker = accessibleMarker,
                            enabled = accessibleEnabled,
                            expanded = accessibleExpanded,
                            cursor = accessibleCursor,
                            onExpandedChange = { accessibleExpanded = it },
                            onMarkerChange = { accessibleMarker = it },
                            onCursorChange = { accessibleCursor = it },
                            onConfirm = { marker, point ->
                                when (val current = accessibleTarget) {
                                    is AngleAccessibleTarget.Draft ->
                                        applied(actions.onPlacePoint(activeFrameId, point))

                                    is AngleAccessibleTarget.ExistingDraft -> applied(
                                        actions.onMoveDraftMarker(
                                            activeFrameId,
                                            current.marker,
                                            point,
                                        ),
                                    )

                                    is AngleAccessibleTarget.ExistingAngle -> applied(
                                        actions.onMoveMarker(
                                            activeFrameId,
                                            current.angleId,
                                            marker,
                                            point,
                                        ),
                                    )

                                    else -> false
                                }
                            },
                        )
                    }
                }
                item(key = "capture-status") {
                    statusAndCaptureControls(
                        state = state,
                        memoryText = memoryText,
                        captureEnabled = capturing == null && !saving,
                        onCapture = { report(actions.onCapture()) },
                        onRetry = activeFrameId?.let { frameId ->
                            if (active?.captureFailure != null) {
                                { report(actions.onRetryCapture(frameId)) }
                            } else {
                                null
                            }
                        },
                        onCancelCapture = capturing?.let { frame ->
                            { report(actions.onCancelCapture(frame.frameId)) }
                        },
                    )
                }
                notice?.let { current ->
                    item(key = "notice") {
                        StatusMessage(
                            text = stringResource(current.messageRes, *current.args.toTypedArray()),
                        )
                    }
                }
                active?.captureFailure?.let { failure ->
                    val message = angleMultiFrameCaptureFailureNotice(failure.reason)
                    item(key = "capture-failure") {
                        StatusMessage(
                            text = stringResource(message.messageRes, *message.args.toTypedArray()),
                        )
                    }
                }
                item(key = "mode") {
                    modeControls(
                        mode = mode,
                        canFrame = mapper != null && openLease != null && active?.canEdit == true &&
                            !saving,
                        overlayAvailable = mapper != null && active?.canEdit == true && !saving,
                        draftPointCount = draftPoints.size,
                        selectedAngle = selectedAngle,
                        onModeChange = { mode = it },
                        onAddAngle = { mode = AngleOverlayMode.PLACEMENT },
                        onCancelDraft = {
                            activeFrameId?.let { report(actions.onCancelDraft(it)) }
                        },
                    )
                }
                item(key = "list-header") {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
                item(key = "frames-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.angle_multi_frame_frames_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = memoryText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.frames.isEmpty()) {
                    item(key = "frames-empty") {
                        Text(
                            text = stringResource(R.string.angle_multi_frame_frames_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.md),
                        )
                    }
                }
                items(state.frames, key = { "frame-${it.frameId.value}" }) { item ->
                    AngleMultiFrameRow(
                        item = item,
                        onSelect = { report(actions.onSelectFrame(item.frameId)) },
                        onRemove = {
                            if (actions.requiresRemovalConfirmation(item.frameId)) {
                                removeTarget = item.frameId
                            } else {
                                report(
                                    actions.onRemoveFrame(
                                        item.frameId,
                                        AngleDiscardConfirmation.NOT_CONFIRMED,
                                    ),
                                )
                            }
                        },
                    )
                }
                item(key = "angles-header") {
                    Text(
                        text = stringResource(R.string.angle_multi_frame_angles_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.sm),
                    )
                }
                if (active == null || angles.isEmpty()) {
                    item(key = "angles-empty") {
                        Text(
                            text = stringResource(R.string.angle_list_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.sm),
                        )
                    }
                }
                items(angles, key = { "angle-${it.id.value}" }) { angle ->
                    AngleRow(
                        angle = angle,
                        selected = angle.id == editorState?.selectedAngleId,
                        degreesText = formatDegrees(angle.degrees),
                        hiddenLabel = stringResource(R.string.angle_hidden),
                        onSelect = {
                            activeFrameId?.let { report(actions.onSelectAngle(it, angle.id)) }
                        },
                        onSetVisible = { visible ->
                            activeFrameId?.let {
                                report(actions.onSetAngleVisible(it, angle.id, visible))
                            }
                        },
                        onRename = { activeFrameId?.let { renameTarget = it to angle } },
                        onColor = { activeFrameId?.let { colorTarget = it to angle } },
                        onDelete = { activeFrameId?.let { deleteTarget = it to angle } },
                    )
                }
            }
        }
    }

    removeTarget?.let { frameId ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.angle_multi_frame_remove_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.angle_multi_frame_remove_body,
                        state.frame(frameId)?.anchor?.frameIndex?.plus(1) ?: 0,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        report(
                            actions.onRemoveFrame(frameId, AngleDiscardConfirmation.CONFIRMED),
                        )
                        removeTarget = null
                    },
                    modifier = Modifier.heightIn(min = Spacing.xxxl),
                ) {
                    Text(stringResource(R.string.angle_multi_frame_remove))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { removeTarget = null },
                    modifier = Modifier.heightIn(min = Spacing.xxxl),
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    renameTarget?.let { (frameId, angle) ->
        RenameAngleDialog(
            angle = angle,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                report(actions.onRenameAngle(frameId, angle.id, name))
                renameTarget = null
            },
        )
    }
    colorTarget?.let { (frameId, angle) ->
        AngleColorDialog(
            angle = angle,
            onDismiss = { colorTarget = null },
            onConfirm = { color ->
                report(actions.onSetAngleColor(frameId, angle.id, color))
                colorTarget = null
            },
        )
    }
    deleteTarget?.let { (frameId, angle) ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.angle_delete_title)) },
            text = { Text(stringResource(R.string.angle_delete_body, angle.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        report(actions.onDeleteAngle(frameId, angle.id))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun statusAndCaptureControls(
    state: AngleMultiFrameSessionState,
    memoryText: String,
    captureEnabled: Boolean,
    onCapture: () -> Unit,
    onRetry: (() -> Unit)?,
    onCancelCapture: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = memoryText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Button(
                onClick = onCapture,
                enabled = captureEnabled,
                modifier = Modifier
                    .heightIn(min = Spacing.xxxl)
                    .testTag(ANGLE_MULTI_FRAME_CAPTURE_TAG),
            ) {
                Text(stringResource(R.string.angle_multi_frame_capture))
            }
            onRetry?.let { retry ->
                OutlinedButton(
                    onClick = retry,
                    modifier = Modifier.heightIn(min = Spacing.xxxl),
                ) {
                    Text(stringResource(R.string.angle_multi_frame_retry_capture))
                }
            }
            onCancelCapture?.let { cancel ->
                TextButton(
                    onClick = cancel,
                    modifier = Modifier.heightIn(min = Spacing.xxxl),
                ) {
                    Text(stringResource(R.string.angle_multi_frame_cancel_capture))
                }
            }
        }
        state.capturingFrame?.let { capturing ->
            Text(
                text = stringResource(
                    R.string.angle_multi_frame_frame_label,
                    capturing.anchor.frameIndex + 1,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun modeControls(
    mode: AngleOverlayMode,
    canFrame: Boolean,
    overlayAvailable: Boolean,
    draftPointCount: Int,
    selectedAngle: Angle?,
    onModeChange: (AngleOverlayMode) -> Unit,
    onAddAngle: () -> Unit,
    onCancelDraft: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            FilledTonalAddAngleButton(onClick = onAddAngle, enabled = overlayAvailable)
            FilterChip(
                selected = mode == AngleOverlayMode.PLACEMENT,
                onClick = { onModeChange(AngleOverlayMode.PLACEMENT) },
                enabled = overlayAvailable,
                label = { Text(stringResource(R.string.angle_mode_place)) },
                modifier = Modifier.heightIn(min = Spacing.xxxl),
            )
            FilterChip(
                selected = mode == AngleOverlayMode.EDIT,
                onClick = { onModeChange(AngleOverlayMode.EDIT) },
                enabled = overlayAvailable,
                label = { Text(stringResource(R.string.angle_mode_edit)) },
                modifier = Modifier.heightIn(min = Spacing.xxxl),
            )
            FilterChip(
                selected = mode == AngleOverlayMode.FRAMING,
                onClick = {
                    onModeChange(
                        if (mode == AngleOverlayMode.FRAMING) {
                            AngleOverlayMode.EDIT
                        } else {
                            AngleOverlayMode.FRAMING
                        },
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
        Text(
            text = when {
                mode == AngleOverlayMode.PLACEMENT -> stringResource(
                    R.string.angle_placement_hint,
                    markerName(draftPointCount),
                    (draftPointCount + 1).coerceAtMost(3),
                )

                mode == AngleOverlayMode.FRAMING -> stringResource(R.string.angle_framing_hint)

                selectedAngle != null ->
                    stringResource(R.string.angle_edit_hint_selected, selectedAngle.name)

                else -> stringResource(R.string.angle_edit_hint_none)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilledTonalAddAngleButton(onClick: () -> Unit, enabled: Boolean) {
    androidx.compose.material3.FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = Spacing.xxxl),
    ) {
        Text(stringResource(R.string.angle_add))
    }
}

@Composable
private fun AngleMultiFrameRow(
    item: AngleMultiFrameItem,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val stateText = when {
        item.isCapturing -> stringResource(R.string.angle_multi_frame_frame_capturing)
        item.canEdit -> stringResource(R.string.angle_multi_frame_frame_ready)
        item.captureFailure != null -> stringResource(R.string.angle_multi_frame_frame_failed)
        else -> stringResource(R.string.angle_multi_frame_frame_not_captured)
    }
    Surface(
        color = if (item.isActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (item.isActive) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        shape = ShapeTokens.small,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = item.isActive,
                onClick = onSelect,
                role = Role.Button,
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.md, end = Spacing.sm, top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.angle_multi_frame_frame_label,
                        item.anchor.frameIndex + 1,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onRemove,
                modifier = Modifier.heightIn(min = Spacing.xxxl),
            ) {
                Text(stringResource(R.string.angle_multi_frame_remove))
            }
        }
    }
}

/**
 * Confirmación de descarte de fotogramas en memoria: la usan el cierre del editor y el cierre del
 * modo fotogramas. Sin confirmación no se descarta nada.
 */
@Composable
internal fun AngleMultiFrameDiscardDialog(
    frameCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.angle_multi_frame_discard_title)) },
        text = { Text(stringResource(R.string.angle_multi_frame_discard_body, frameCount)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = Spacing.xxxl),
            ) {
                Text(stringResource(R.string.angle_multi_frame_discard_confirm))
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
 * Medición de un ángulo completo. El modelo garantiza una terna medible, así que la lista de
 * fotogramas presenta siempre un valor real y nunca inventa grados para un estado inválido.
 *
 * Es privada de este fichero: la pantalla mono-fotograma mantiene su propia extensión y ninguna de
 * las dos amplía la API pública del modelo.
 */
private val Angle.degrees: Double
    get() = (measurement as AngleMeasurement.Measured).degrees
