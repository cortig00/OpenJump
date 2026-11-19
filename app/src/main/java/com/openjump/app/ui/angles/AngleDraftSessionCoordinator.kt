package com.openjump.app.ui.angles

import android.graphics.Bitmap
import com.openjump.app.angles.AngleAnalysisDraftOutcome
import com.openjump.app.angles.AngleAnalysisDraftRejection
import com.openjump.app.angles.AngleAnalysisDraftState
import com.openjump.app.angles.AngleAnalysisFinalization
import com.openjump.app.angles.AngleDiscardConfirmation
import com.openjump.app.angles.AngleDraftExpectation
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorOutcome
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameId
import com.openjump.app.angles.AngleFrozenImage
import com.openjump.app.video.angles.FrameFreezeBudget
import com.openjump.app.video.angles.FrameFreezeRejectionReason
import com.openjump.app.video.angles.FrameFreezeResult
import com.openjump.app.video.angles.FrozenFrameImage
import com.openjump.app.video.angles.FrozenFrameSourceSession
import com.openjump.app.video.angles.FrameSourceRequestResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinador de una sesión de análisis multi-fotograma sobre **una única fuente ya abierta**.
 *
 * ## Qué posee y qué no
 *
 * - Posee **el agregado de dominio** [AngleAnalysisDraftState] (una fuente + N fotogramas con editores
 *   A/B/C independientes, ya validado en `angles/`) y **las imágenes congeladas retenidas**: mientras
 *   un fotograma conserva su imagen, esta capa es la dueña de los píxeles y no se vuelve a decodificar
 *   el vídeo para mostrarlo otra vez.
 * - **No abre el vídeo** (copiar/sellar/indexar es E/S: la hace el llamante fuera del hilo principal
 *   con `FrozenFrameSources` y entrega el resultado ya abierto), **no pinta**, **no navega**, **no
 *   guarda**, **no escribe Room**, no conoce textos localizados y **no promete supervivencia a la
 *   muerte del proceso**. La recomposición o la recreación de Activity se resuelven teniendo este
 *   coordinador en un `ViewModel`: el objeto sobrevive y la UI vuelve a pedir un *lease* de la imagen
 *   retenida. Si el proceso muere, la sesión se pierde por completo (no hay recuperación ni
 *   persistencia en esta capa).
 * - **Todo trabajo de captura va a un ejecutor inyectado** ([AngleDraftExecutor]); esta clase nunca
 *   decodifica ni toca disco en el hilo que llama. El llamante debe inyectar un ejecutor de E/S de un
 *   solo hilo (nunca uno que ejecute en línea sobre el hilo principal). Las operaciones públicas son
 *   síncronas, cortas y seguras desde el hilo principal: solo actualizan estado en memoria bajo un
 *   candado y el recuento de leases.
 * - Los observadores ([AngleDraftSessionObserver]) se invocan **fuera del candado** y pueden recibir
 *   la llamada desde el hilo del ejecutor (adopción de una captura). Deben ser rápidos, no bloquear y
 *   no lanzar; una excepción de runtime del observador se aísla y no revierte el estado ya consistente.
 *
 * ## Guardas que impone
 *
 * - **Una sola fuente.** [sourceIdentity] se fija al construir (la declara el puerto de origen) y la
 *   generación de render también. No existe operación para mezclar fotogramas de otro vídeo ni para
 *   reutilizar la sesión con otra generación: abrir otro vídeo exige otro coordinador y otro estado.
 *   Cada ancla ([AngleFrameAnchor]) se contrasta con la identidad de la fuente y cada imagen
 *   entregada por la captura se contrasta con el ancla solicitada (fuente, `frameIndex`, PTS asignado)
 *   antes de adoptarla. Una imagen con procedencia ajena se libera y **no** se adopta.
 * - **Geometría solo con imagen retenida y mostrable.** El agregado ya rechaza anclar geometría sin
 *   imagen adoptada; aquí se añade la guarda redundante de que exista una imagen **retenida** en esta
 *   capa ([AngleDraftFrameState.capture] = [AngleDraftCapturePhase.Ready]) antes de aplicar cualquier
 *   [AngleEditorOutcome]. La imagen solo pasa a `Ready` cuando sus píxeles están retenidos, así que
 *   «adoptada» y «mostrable» son el mismo hecho atómico.
 * - **Respuestas tardías, canceladas o posteriores al cierre no se adoptan.** Cada captura tiene un
 *   intento monótono; cancelar, eliminar el fotograma o cerrar invalida el intento. Si la respuesta
 *   llega después, su imagen se libera, no toca el estado y se cuenta en
 *   [AngleDraftSessionSnapshot.droppedLateResults]. La cancelación es cooperativa
 *   ([AngleDraftCancelSignal]) y el adaptador real la conecta al bucle de decodificación.
 * - **Single-flight de sesión.** Solo puede haber **una** captura en vuelo, aunque sea de otro
 *   fotograma: el presupuesto se comprueba como `retenidos reales + reserva de la captura en vuelo +
 *   peor caso de la nueva`, de modo que dos decodificaciones simultáneas no validen cada una el mismo
 *   límite por separado. Un segundo `captureFrame` sobre el fotograma en vuelo se rechaza con
 *   [AngleDraftCoordinatorRejection.CaptureAlreadyInFlight] y sobre otro fotograma con
 *   [AngleDraftCoordinatorRejection.SessionCaptureInFlight]; ambos son recuperables. Tampoco se
 *   vuelve a capturar un fotograma que ya tiene imagen ([AngleDraftCoordinatorRejection.FrameAlreadyCaptured]):
 *   la imagen se mantiene exacta hasta descartar el fotograma o cerrar la sesión, sin reextraer vídeo.
 * - **La reserva vive lo que vive la tarea, no lo que vive el intento.** Cancelar, retirar el
 *   fotograma o cerrar la sesión invalida la adopción del resultado de inmediato, pero **no** libera
 *   la reserva de memoria ni el single-flight: el `Runnable` puede seguir decodificando en el
 *   ejecutor. La reserva se suelta exactamente una vez, cuando el trabajo termina (respuesta,
 *   cancelación observada, excepción) o cuando el ejecutor rechaza la tarea (no se ejecutó nada).
 *   Mientras tanto, las peticiones nuevas reciben el rechazo tipado recuperable en lugar de
 *   presupuestar contra memoria todavía comprometida.
 * - **Cambiar de selección o reordenar no toca nada.** A/B, ángulos, imagen y revisión de cada
 *   fotograma se conservan; solo cambia el fotograma activo o el orden de presentación.
 * - **Acciones destructivas con confirmación.** Eliminar un fotograma con datos y descartar un borrador
 *   A/B con puntos exigen [AngleDiscardConfirmation.CONFIRMED]; se delega en el agregado y se devuelve
 *   el rechazo tipado sin cambios.
 * - **Límite de memoria explícito y recuperable.** Antes de despachar una captura se exige
 *   `bytes retenidos reales + reserva vigente + peor caso de captura/salida <= 64 MiB`; el peor caso lo
 *   aporta el puerto ([AngleDraftSourcePort.captureWorstCaseBytes], calculado con `FrameFreezeBudget`).
 *   Un peor caso ausente o no positivo se rechaza (falla cerrado). Si no cabe, se rechaza **sin
 *   iniciar** la captura ([AngleDraftCoordinatorRejection.MemoryBudgetExceeded]) y la sesión sigue
 *   usable: liberar otro fotograma o esperar al trabajo en curso permite reintentar. Tras la captura se
 *   vuelve a comprobar con los bytes reales retenidos. Los bytes de un fotograma retirado con leases
 *   abiertos siguen contando hasta que el último lease se cierra. No hay ningún límite arbitrario por
 *   número de fotogramas; el único límite es la memoria.
 * - **Cierre idempotente y sin bloquear.** [close] cancela los intentos en vuelo (la reserva de la
 *   tarea viva se suelta cuando esta termina), invalida las respuestas posteriores, libera las imágenes
 *   sin leases al momento y difiere las que aún tienen leases hasta su cierre; cierra la fuente en el
 *   ejecutor. Si el ejecutor rechaza también esa tarea, el cierre se ejecuta en el hilo llamante como
 *   último recurso para no filtrar el descriptor: eso **puede bloquear** ese hilo (documentado como
 *   riesgo; resolverlo exige que el llamante aporte un ejecutor de respaldo). Llamar dos veces no
 *   repite nada.
 * - **Propiedad y vida de los píxeles.** La UI nunca recibe el objeto propietario de la imagen: obtiene
 *   un [AngleDraftFrameImageLease] con contador de referencias. El coordinador **no recicla** una imagen
 *   mientras tenga leases. Contrato para la UI: mantener el lease mientras cualquier dibujo pueda
 *   referenciar el bitmap y cerrarlo (idempotente) en el mismo hilo de dibujo al salir de composición
 *   (`DisposableEffect`), de modo que no quede ningún dibujo en vuelo. Solo entonces se recicla. Sin
 *   lease, no hay API para obtener los píxeles.
 *
 * ## Fuera de alcance de este slice
 *
 * UI/Compose, navegación, ViewModel concreto, Room, textos, apertura del vídeo, autenticación de píxeles
 * (la hace el productor existente y aquí solo se contrasta la procedencia declarada), orientación,
 * zoom, guardado y recuperación tras muerte de proceso.
 */
internal class AngleDraftSessionCoordinator(
    private val source: AngleDraftSourcePort,
    val generation: Long,
    editorDefaults: AngleEditorDefaults,
    private val executor: AngleDraftExecutor,
    private val observer: AngleDraftSessionObserver = AngleDraftSessionObserver { },
    private val maxRetainedPlusCaptureBytes: Long = MAX_RETAINED_PLUS_CAPTURE_BYTES,
) : AutoCloseable {

    private val lock = Any()

    /** Identidad de la única fuente del análisis; se fija al construir y nunca cambia. */
    val sourceIdentity: String = source.sourceIdentity

    private var draft: AngleAnalysisDraftState =
        AngleAnalysisDraftState.initial(sourceIdentity, generation, editorDefaults)

    /** Estado de captura/propiedad por fotograma vivo o retirado con leases pendientes. */
    private val frameEntries = HashMap<AngleFrameId, FrameEntry>()

    /**
     * Única captura de sesión en vuelo (single-flight de sesión). Reserva el peor caso de su
     * decodificación y **sobrevive** a la cancelación, a la retirada del fotograma y al cierre: la
     * memoria que ese trabajo puede llegar a usar es real hasta que la tarea termina en el ejecutor.
     */
    private var activeCapture: ActiveCapture? = null

    private var nextFrameId = 1L
    private var nextAttemptId = 1L
    private var retainedBytes = 0L
    private var droppedLateResults = 0L
    private var snapshotVersion = 0L
    private var closed = false
    private val sourceCloseScheduled = AtomicBoolean(false)

    // ---------------------------------------------------------------- consultas

    /** Instantánea inmutable y coherente del estado público de la sesión. */
    fun snapshot(): AngleDraftSessionSnapshot = synchronized(lock) { snapshotLocked() }

    /** Consulta pura de finalización del agregado; no guarda ni limpia nada. */
    fun finalization(): AngleAnalysisFinalization = synchronized(lock) { draft.finalization() }

    // ---------------------------------------------------------------- fotogramas

    /**
     * Registra un fotograma del índice de la fuente, **sin imagen**. El id lo acuña el coordinador y
     * es estable e independiente del orden. No cambia la selección.
     */
    fun addFrame(frameIndex: Int): AngleDraftCoordinatorOutcome {
        val outcome = synchronized(lock) {
            if (closed) return@synchronized Rejected(AngleDraftCoordinatorRejection.Closed)
            if (frameIndex !in 0 until source.frameCount) {
                return@synchronized Rejected(
                    AngleDraftCoordinatorRejection.FrameIndexOutOfRange(
                        frameIndex = frameIndex,
                        frameCount = source.frameCount,
                    ),
                )
            }
            val anchor = source.frameAnchor(frameIndex)
                ?: return@synchronized Rejected(
                    AngleDraftCoordinatorRejection.AnchorUnavailable(frameIndex),
                )
            if (anchor.sourceIdentity != sourceIdentity) {
                return@synchronized Rejected(
                    AngleDraftCoordinatorRejection.SourceIdentityMismatch(
                        expected = sourceIdentity,
                        received = anchor.sourceIdentity,
                    ),
                )
            }
            if (nextFrameId >= Long.MAX_VALUE) {
                return@synchronized Rejected(AngleDraftCoordinatorRejection.FrameIdSpaceExhausted)
            }
            val frameId = AngleFrameId(nextFrameId)
            when (val applied = draft.addFrame(frameId, anchor)) {
                is AngleAnalysisDraftOutcome.Applied -> {
                    nextFrameId += 1L
                    draft = applied.state
                    frameEntries[frameId] = FrameEntry()
                    Applied(snapshotLocked())
                }

                is AngleAnalysisDraftOutcome.Rejected ->
                    Rejected(AngleDraftCoordinatorRejection.DraftRejected(applied.reason))
            }
        }
        notifyIfApplied(outcome)
        return outcome
    }

    /** Selecciona el fotograma visible; preserva imágenes, A/B, ángulos y revisiones de todos. */
    fun selectFrame(frameId: AngleFrameId?): AngleDraftCoordinatorOutcome =
        applyToDraft { state -> state.selectFrame(frameId) }

    /** Mueve un fotograma a otra posición de presentación sin tocar contenido ni revisiones. */
    fun moveFrame(frameId: AngleFrameId, toIndex: Int): AngleDraftCoordinatorOutcome =
        applyToDraft { state -> state.moveFrame(frameId, toIndex) }

    /**
     * Aplica una acción del editor del fotograma indicado.
     *
     * Exige que el fotograma tenga imagen retenida y mostrable: sin captura adoptada no se permite
     * geometría aunque el editor construido por el llamante la aceptara. La revisión vigente se toma
     * aquí, bajo candado, de modo que una respuesta tardía se rechaza con el motivo tipado del agregado.
     */
    fun applyEditorOutcome(
        frameId: AngleFrameId,
        outcome: AngleEditorOutcome,
    ): AngleDraftCoordinatorOutcome {
        val result = synchronized(lock) {
            if (closed) return@synchronized Rejected(AngleDraftCoordinatorRejection.Closed)
            val entry = frameEntries[frameId]
                ?: return@synchronized Rejected(AngleDraftCoordinatorRejection.UnknownFrame(frameId))
            if (entry.retained == null || entry.phase !is AngleDraftCapturePhase.Ready) {
                return@synchronized Rejected(
                    AngleDraftCoordinatorRejection.FrameImageNotReady(frameId),
                )
            }
            val expectation = draft.expectationFor(frameId)
                ?: return@synchronized Rejected(AngleDraftCoordinatorRejection.UnknownFrame(frameId))
            applyDraftLocked(draft.applyEditorOutcome(expectation, outcome))
        }
        notifyIfApplied(result)
        return result
    }

    /** Descarta los puntos A/B del borrador de un fotograma; con puntos exige confirmación. */
    fun discardFrameDraft(
        frameId: AngleFrameId,
        confirmation: AngleDiscardConfirmation,
    ): AngleDraftCoordinatorOutcome {
        val result = synchronized(lock) {
            if (closed) return@synchronized Rejected(AngleDraftCoordinatorRejection.Closed)
            val expectation = draft.expectationFor(frameId)
                ?: return@synchronized Rejected(AngleDraftCoordinatorRejection.UnknownFrame(frameId))
            applyDraftLocked(draft.discardFrameDraft(expectation, confirmation))
        }
        notifyIfApplied(result)
        return result
    }

    /**
     * Elimina un fotograma con el contrato del agregado (confirmación si conserva datos). Invalida
     * cualquier captura en vuelo de ese fotograma (respuesta posterior liberada, nunca adoptada) y
     * libera su imagen retenida cuando no queden leases.
     */
    fun removeFrame(
        frameId: AngleFrameId,
        confirmation: AngleDiscardConfirmation,
    ): AngleDraftCoordinatorOutcome {
        val result = synchronized(lock) {
            if (closed) return@synchronized Rejected(AngleDraftCoordinatorRejection.Closed)
            val expectation = draft.expectationFor(frameId)
                ?: return@synchronized Rejected(AngleDraftCoordinatorRejection.UnknownFrame(frameId))
            when (val applied = draft.removeFrame(expectation, confirmation)) {
                is AngleAnalysisDraftOutcome.Rejected ->
                    Rejected(AngleDraftCoordinatorRejection.DraftRejected(applied.reason))

                is AngleAnalysisDraftOutcome.Applied -> {
                    draft = applied.state
                    invalidateActiveLocked(frameId)
                    detachRetainedLocked(frameId)
                    Applied(snapshotLocked())
                }
            }
        }
        notifyIfApplied(result)
        return result
    }

    // ---------------------------------------------------------------- captura

    /**
     * Inicia la captura del fotograma en el ejecutor inyectado, **nunca en línea**.
     *
     * Valida sin bloquear: fotograma conocido y sin imagen, single-flight **de sesión** (solo una
     * captura en vuelo, también si es de otro fotograma o si se canceló y su tarea sigue viva),
     * presupuesto presupuestable y `retenidos + reserva vigente + peor caso <= límite`. Un rechazo por
     * memoria u ocupación no altera el estado y es recuperable: basta reintentar cuando el trabajo
     * anterior termine. El resultado se adopta desde el hilo del ejecutor y se notifica al observador;
     * una respuesta invalidada se libera y la reserva se suelta al terminar la tarea, no al cancelar.
     */
    fun captureFrame(frameId: AngleFrameId): AngleDraftCoordinatorOutcome {
        val start = synchronized(lock) {
            if (closed) return@synchronized CaptureStart.Rejected(AngleDraftCoordinatorRejection.Closed)
            val entry = frameEntries[frameId]
                ?: return@synchronized CaptureStart.Rejected(AngleDraftCoordinatorRejection.UnknownFrame(frameId))
            if (entry.retained != null) {
                return@synchronized CaptureStart.Rejected(
                    AngleDraftCoordinatorRejection.FrameAlreadyCaptured(frameId),
                )
            }
            val reservedBytes = activeCapture?.reservedBytes ?: 0L
            val busy = activeCapture
            if (busy != null) {
                return@synchronized CaptureStart.Rejected(
                    if (busy.frameId == frameId) {
                        AngleDraftCoordinatorRejection.CaptureAlreadyInFlight(
                            frameId = frameId,
                            attemptId = busy.attemptId,
                        )
                    } else {
                        AngleDraftCoordinatorRejection.SessionCaptureInFlight(
                            activeFrameId = busy.frameId,
                            activeAttemptId = busy.attemptId,
                            reservedBytes = busy.reservedBytes,
                            cancelled = !busy.adoptable,
                        )
                    },
                )
            }
            val frame = draft.frame(frameId)
                ?: return@synchronized CaptureStart.Rejected(AngleDraftCoordinatorRejection.UnknownFrame(frameId))
            val worstCaseBytes = source.captureWorstCaseBytes(frame.anchor.frameIndex)
            // Un peor caso no positivo (o ausente) no es presupuestable: se falla cerrado antes de
            // decodificar, en lugar de aceptar una reserva de 0 B que desactivaría el límite.
            if (worstCaseBytes == null || worstCaseBytes <= 0L) {
                return@synchronized CaptureStart.Rejected(
                    AngleDraftCoordinatorRejection.CaptureBudgetUnavailable(frameId),
                )
            }
            val committedBytes = safeAdd(retainedBytes, reservedBytes)
            val requiredBytes = committedBytes?.let { safeAdd(it, worstCaseBytes) }
            if (requiredBytes == null || requiredBytes > maxRetainedPlusCaptureBytes) {
                return@synchronized CaptureStart.Rejected(
                    AngleDraftCoordinatorRejection.MemoryBudgetExceeded(
                        retainedBytes = retainedBytes,
                        reservedBytes = reservedBytes,
                        captureWorstCaseBytes = worstCaseBytes,
                        limitBytes = maxRetainedPlusCaptureBytes,
                    ),
                )
            }
            if (nextAttemptId >= Long.MAX_VALUE) {
                return@synchronized CaptureStart.Rejected(
                    AngleDraftCoordinatorRejection.CaptureAttemptSpaceExhausted,
                )
            }
            val attemptId = nextAttemptId
            nextAttemptId += 1L
            val cancellation = AngleDraftCancelSignal()
            entry.phase = AngleDraftCapturePhase.Capturing(attemptId)
            activeCapture = ActiveCapture(
                attemptId = attemptId,
                frameId = frameId,
                frameIndex = frame.anchor.frameIndex,
                reservedBytes = worstCaseBytes,
                cancel = cancellation,
            )
            CaptureStart.Started(attemptId, frame.anchor.frameIndex, cancellation)
        }
        return when (start) {
            is CaptureStart.Rejected -> Rejected(start.rejection)
            is CaptureStart.Started -> dispatchCapture(frameId, start)
        }
    }

    /**
     * Cancela cooperativamente la captura en vuelo de un fotograma.
     *
     * El intento queda invalidado de inmediato para adoptar (si la captura ya produjo píxeles se
     * liberan al llegar y no se adoptan), pero **no** libera la ocupación de sesión ni la reserva de
     * memoria: la tarea puede seguir decodificando en el ejecutor y esa memoria es real. Hasta que
     * termine, `captureFrame` se rechaza con
     * [AngleDraftCoordinatorRejection.CaptureAlreadyInFlight] (mismo fotograma) o
     * [AngleDraftCoordinatorRejection.SessionCaptureInFlight] (otro); ambos son recuperables.
     */
    fun cancelCapture(frameId: AngleFrameId): AngleDraftCoordinatorOutcome {
        val result = synchronized(lock) {
            if (closed) return@synchronized Rejected(AngleDraftCoordinatorRejection.Closed)
            val entry = frameEntries[frameId]
                ?: return@synchronized Rejected(AngleDraftCoordinatorRejection.UnknownFrame(frameId))
            val capture = activeCapture
            if (capture == null || capture.frameId != frameId) {
                return@synchronized Rejected(
                    AngleDraftCoordinatorRejection.CaptureNotInFlight(frameId),
                )
            }
            capture.adoptable = false
            capture.cancel.cancel()
            if (entry.phase == AngleDraftCapturePhase.Capturing(capture.attemptId)) {
                entry.phase = AngleDraftCapturePhase.NotCaptured
            }
            Applied(snapshotLocked())
        }
        notifyIfApplied(result)
        return result
    }

    // ---------------------------------------------------------------- píxeles prestados

    /**
     * Pide un lease de la imagen retenida del fotograma.
     *
     * Mientras el lease esté abierto, esta capa no recicla el bitmap. La UI debe cerrarlo
     * (idempotente) en el hilo de dibujo al salir de composición; ver el contrato del lease.
     */
    fun acquireFrameImageLease(frameId: AngleFrameId): AngleDraftLeaseOutcome = synchronized(lock) {
        if (closed) return@synchronized AngleDraftLeaseOutcome.Rejected(
            AngleDraftCoordinatorRejection.Closed,
        )
        val entry = frameEntries[frameId]
            ?: return@synchronized AngleDraftLeaseOutcome.Rejected(
                AngleDraftCoordinatorRejection.UnknownFrame(frameId),
            )
        val image = entry.retained
            ?: return@synchronized AngleDraftLeaseOutcome.Rejected(
                AngleDraftCoordinatorRejection.ImageNotRetained(frameId),
            )
        entry.leaseCount += 1
        AngleDraftLeaseOutcome.Granted(
            AngleDraftFrameImageLease(
                frameId = frameId,
                image = image,
                onReleased = { released -> onLeaseReleased(frameId, released) },
            ),
        )
    }

    /** Número de leases vivos de un fotograma (diagnóstico y pruebas). */
    fun leaseCount(frameId: AngleFrameId): Int = synchronized(lock) {
        frameEntries[frameId]?.leaseCount ?: 0
    }

    // ---------------------------------------------------------------- cierre

    /**
     * Cierra la sesión de forma idempotente y sin bloquear el hilo llamante.
     *
     * Cancela los intentos en vuelo, impide nuevas operaciones, libera las imágenes sin leases y
     * difiere las que tienen leases hasta su cierre. La fuente se cierra en el ejecutor.
     */
    override fun close() {
        val toClose = ArrayList<AngleDraftRetainedImage>()
        synchronized(lock) {
            if (closed) return
            closed = true
            // La reserva de la captura en vuelo **no** se libera aquí: su tarea puede seguir
            // decodificando y su memoria es real. Se suelta cuando la tarea termina (o si el ejecutor
            // rechaza la tarea). Solo se invalida su adopción.
            activeCapture?.let { capture ->
                capture.adoptable = false
                capture.cancel.cancel()
            }
            frameEntries.values.forEach { entry ->
                entry.phase = AngleDraftCapturePhase.NotCaptured
                entry.detached = true
                val image = entry.retained
                if (image != null && entry.leaseCount == 0) {
                    entry.retained = null
                    retainedBytes -= entry.retainedBytes
                    entry.retainedBytes = 0L
                    toClose += image
                }
            }
            frameEntries.entries.removeAll { entry ->
                entry.value.leaseCount == 0 && entry.value.retained == null
            }
        }
        toClose.forEach { closeQuietly(it) }
        scheduleSourceClose()
        notifySafely()
    }

    // ---------------------------------------------------------------- interior

    private class FrameEntry {
        var phase: AngleDraftCapturePhase = AngleDraftCapturePhase.NotCaptured
        var retained: AngleDraftRetainedImage? = null
        var retainedBytes: Long = 0L
        var leaseCount: Int = 0

        /** El fotograma ya no forma parte de la sesión viva; los píxeles esperan a sus leases. */
        var detached: Boolean = false
    }

    /**
     * Captura de sesión en vuelo mientras su [Runnable] no haya terminado.
     *
     * [reservedBytes] es el peor caso presupuestado y cuenta contra el límite hasta que el trabajo
     * salga del ejecutor. [adoptable] pasa a `false` al cancelar, retirar el fotograma o cerrar: el
     * resultado se liberará, pero la reserva sigue viva mientras la tarea exista.
     */
    private class ActiveCapture(
        val attemptId: Long,
        val frameId: AngleFrameId,
        val frameIndex: Int,
        val reservedBytes: Long,
        val cancel: AngleDraftCancelSignal,
    ) {
        var adoptable: Boolean = true
    }

    private sealed interface CaptureStart {
        data class Started(
            val attemptId: Long,
            val frameIndex: Int,
            val cancel: AngleDraftCancelSignal,
        ) : CaptureStart

        data class Rejected(
            val rejection: AngleDraftCoordinatorRejection,
        ) : CaptureStart
    }

    private fun applyToDraft(
        operation: (AngleAnalysisDraftState) -> AngleAnalysisDraftOutcome,
    ): AngleDraftCoordinatorOutcome {
        val result = synchronized(lock) {
            if (closed) return@synchronized Rejected(AngleDraftCoordinatorRejection.Closed)
            applyDraftLocked(operation(draft))
        }
        notifyIfApplied(result)
        return result
    }

    private fun applyDraftLocked(outcome: AngleAnalysisDraftOutcome): AngleDraftCoordinatorOutcome =
        when (outcome) {
            is AngleAnalysisDraftOutcome.Applied -> {
                draft = outcome.state
                Applied(snapshotLocked())
            }

            is AngleAnalysisDraftOutcome.Rejected -> Rejected(
                AngleDraftCoordinatorRejection.DraftRejected(outcome.reason),
            )
        }

    private fun dispatchCapture(
        frameId: AngleFrameId,
        start: CaptureStart.Started,
    ): AngleDraftCoordinatorOutcome {
        val task = Runnable { performCapture(frameId, start) }
        val accepted = try {
            executor.execute(task)
            true
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) {
            // Nada se decodificó: la reserva y la ocupación de sesión se sueltan aquí y ahora.
            val invalidated = synchronized(lock) {
                start.cancel.cancel()
                releaseLocked(start.attemptId)
                val entry = frameEntries[frameId]
                if (entry != null &&
                    !closed &&
                    entry.phase == AngleDraftCapturePhase.Capturing(start.attemptId)
                ) {
                    entry.phase = AngleDraftCapturePhase.Failed(
                        reason = AngleDraftCaptureFailureReason.EXECUTOR_REJECTED,
                        detail = "El ejecutor de E/S rechazó la captura.",
                    )
                }
                snapshotLocked()
            }
            deliver(invalidated)
            return Applied(invalidated)
        }
        val snapshot = snapshot()
        deliver(snapshot)
        return Applied(snapshot)
    }

    private fun performCapture(frameId: AngleFrameId, start: CaptureStart.Started) {
        try {
            val result = if (start.cancel.isCancelled || !isAttemptAdoptable(start.attemptId)) {
                AngleDraftCaptureResult.Cancelled
            } else {
                try {
                    source.capture(start.frameIndex, start.cancel)
                } catch (_: OutOfMemoryError) {
                    AngleDraftCaptureResult.Blocked(
                        reason = AngleDraftCaptureFailureReason.MEMORY_BUDGET_EXCEEDED,
                        detail = "OutOfMemoryError durante la captura.",
                    )
                } catch (error: Exception) {
                    AngleDraftCaptureResult.Blocked(
                        reason = AngleDraftCaptureFailureReason.CAPTURE_FAILED,
                        detail = error.javaClass.simpleName,
                    )
                }
            }
            completeCapture(frameId, start.attemptId, result)
        } finally {
            // Red de seguridad: si la adopción no llegó a ejecutarse (fallo inesperado), la reserva
            // y el single-flight se sueltan igualmente y el fotograma no queda «Capturando» para
            // siempre. Es idempotente: si ya se soltaron, no hace nada y no se notifica dos veces.
            val releasedEarly = synchronized(lock) {
                if (!releaseLocked(start.attemptId)) return@synchronized false
                val entry = frameEntries[frameId]
                if (entry != null &&
                    !closed &&
                    entry.phase == AngleDraftCapturePhase.Capturing(start.attemptId)
                ) {
                    entry.phase = AngleDraftCapturePhase.NotCaptured
                }
                true
            }
            if (releasedEarly) notifySafely()
        }
    }

    private fun isAttemptAdoptable(attemptId: Long): Boolean = synchronized(lock) {
        val capture = activeCapture
        !closed && capture != null && capture.attemptId == attemptId && capture.adoptable
    }

    /**
     * Suelta la reserva/ocupación de sesión del intento si sigue vigente; `true` si la soltó ahora.
     * Idempotente y siempre bajo [lock].
     */
    private fun releaseLocked(attemptId: Long): Boolean {
        val capture = activeCapture ?: return false
        if (capture.attemptId != attemptId) return false
        activeCapture = null
        return true
    }

    /**
     * Punto único de adopción de una respuesta de captura. Todo lo que no sea un intento vigente se
     * libera aquí: cancelado, posterior a un cierre, posterior a la retirada del fotograma o
     * invalidado. Nada de eso toca el estado del agregado.
     *
     * La reserva de memoria se suelta en esta misma sección crítica (también en las ramas tardías):
     * el evento de ejecución termina con la respuesta, no con la cancelación.
     */
    private fun completeCapture(
        frameId: AngleFrameId,
        attemptId: Long,
        result: AngleDraftCaptureResult,
    ) {
        var imageToClose: AngleDraftRetainedImage? = null
        synchronized(lock) {
            val entry = frameEntries[frameId]
            val capture = activeCapture
            val current = !closed &&
                entry != null &&
                capture != null &&
                capture.attemptId == attemptId &&
                capture.adoptable &&
                entry.phase == AngleDraftCapturePhase.Capturing(attemptId)
            if (!current) {
                droppedLateResults += 1L
                imageToClose = (result as? AngleDraftCaptureResult.Captured)?.image
                releaseLocked(attemptId)
                return@synchronized
            }
            when (result) {
                is AngleDraftCaptureResult.Cancelled -> {
                    entry!!.phase = AngleDraftCapturePhase.NotCaptured
                }

                is AngleDraftCaptureResult.Blocked -> {
                    entry!!.phase = if (result.reason == AngleDraftCaptureFailureReason.CANCELLED) {
                        // Cancelar no es un fallo que la UI deba presentar: el fotograma sigue
                        // capturable. El intento ya está invalidado, así que no se adopta nada.
                        AngleDraftCapturePhase.NotCaptured
                    } else {
                        AngleDraftCapturePhase.Failed(result.reason, result.detail)
                    }
                }

                is AngleDraftCaptureResult.Captured -> {
                    val image = result.image
                    val anchor = draft.frame(frameId)?.anchor
                    val failure = imageFailureLocked(image, anchor)
                    if (failure != null) {
                        entry!!.phase = failure
                        imageToClose = image
                    } else {
                        adoptLocked(
                            frameId = frameId,
                            attemptId = attemptId,
                            anchor = requireNotNull(anchor),
                            image = image,
                        )?.let { rejection ->
                            entry!!.phase = AngleDraftCapturePhase.Failed(
                                reason = AngleDraftCaptureFailureReason.STALE_SESSION,
                                detail = rejection.toString(),
                            )
                            imageToClose = image
                        }
                    }
                }
            }
            // Con la respuesta en mano, la reserva de peor caso deja de ser necesaria: el siguiente
            // intento presupuesta con los bytes realmente retenidos.
            releaseLocked(attemptId)
        }
        imageToClose?.let(::closeQuietly)
        notifySafely()
    }

    /** Procedencia/coherencia de la imagen antes de retenerla; `null` si es adoptable. */
    private fun imageFailureLocked(
        image: AngleDraftRetainedImage,
        anchor: AngleFrameAnchor?,
    ): AngleDraftCapturePhase.Failed? {
        if (anchor == null) {
            return AngleDraftCapturePhase.Failed(
                AngleDraftCaptureFailureReason.STALE_SESSION,
                "El fotograma dejó de existir antes de adoptar la imagen.",
            )
        }
        if (image.sourceIdentity != sourceIdentity) {
            return AngleDraftCapturePhase.Failed(
                AngleDraftCaptureFailureReason.CROSS_SOURCE_IMAGE,
                "La imagen declara la fuente ${image.sourceIdentity}, no $sourceIdentity.",
            )
        }
        if (image.frameIndex != anchor.frameIndex) {
            return AngleDraftCapturePhase.Failed(
                AngleDraftCaptureFailureReason.ANCHOR_MISMATCH,
                "La imagen declara el frame ${image.frameIndex}, no ${anchor.frameIndex}.",
            )
        }
        if (image.assignedPtsUs != anchor.sourcePtsUs) {
            return AngleDraftCapturePhase.Failed(
                AngleDraftCaptureFailureReason.ANCHOR_MISMATCH,
                "La imagen declara el PTS ${image.assignedPtsUs}, no ${anchor.sourcePtsUs}.",
            )
        }
        if (image.width < 1 || image.height < 1 || image.byteCount <= 0L) {
            return AngleDraftCapturePhase.Failed(
                AngleDraftCaptureFailureReason.INVALID_IMAGE,
                "La imagen no declara dimensiones/bytes utilizables.",
            )
        }
        val total = safeAdd(retainedBytes, image.byteCount)
        if (total == null || total > maxRetainedPlusCaptureBytes) {
            return AngleDraftCapturePhase.Failed(
                AngleDraftCaptureFailureReason.MEMORY_BUDGET_EXCEEDED,
                "Retenidos ${retainedBytes}B + imagen ${image.byteCount}B superan el límite de $maxRetainedPlusCaptureBytes B.",
            )
        }
        return null
    }

    private fun adoptLocked(
        frameId: AngleFrameId,
        attemptId: Long,
        anchor: AngleFrameAnchor,
        image: AngleDraftRetainedImage,
    ): AngleAnalysisDraftRejection? {
        val expectation: AngleDraftExpectation = draft.expectationFor(frameId)
            ?: return AngleAnalysisDraftRejection.UnknownFrame(frameId)
        val frozen = AngleFrozenImage(
            token = "angle-draft:$generation:${frameId.value}:$attemptId",
            anchor = anchor,
        )
        return when (val applied = draft.adoptFrozenImage(expectation, frozen)) {
            is AngleAnalysisDraftOutcome.Applied -> {
                draft = applied.state
                val entry = requireNotNull(frameEntries[frameId])
                entry.retained = image
                entry.retainedBytes = image.byteCount
                entry.phase = AngleDraftCapturePhase.Ready(frozen.token)
                retainedBytes += image.byteCount
                null
            }

            is AngleAnalysisDraftOutcome.Rejected -> applied.reason
        }
    }

    /**
     * Invalida la adopción del trabajo del fotograma (cancelación, retirada o cierre) **sin** soltar
     * su reserva de memoria ni la ocupación de sesión: la tarea puede seguir viva en el ejecutor.
     */
    private fun invalidateActiveLocked(frameId: AngleFrameId) {
        val capture = activeCapture ?: return
        if (capture.frameId != frameId) return
        capture.adoptable = false
        capture.cancel.cancel()
        val entry = frameEntries[frameId] ?: return
        if (entry.phase == AngleDraftCapturePhase.Capturing(capture.attemptId)) {
            entry.phase = AngleDraftCapturePhase.NotCaptured
        }
    }

    /** Suelta la propiedad de la imagen de un fotograma retirado; se cierra si no hay leases. */
    private fun detachRetainedLocked(frameId: AngleFrameId) {
        val entry = frameEntries[frameId] ?: return
        entry.detached = true
        entry.phase = AngleDraftCapturePhase.NotCaptured
        val image = entry.retained
        if (image == null) {
            if (entry.leaseCount == 0) frameEntries.remove(frameId)
            return
        }
        // Mientras haya leases, los píxeles siguen siendo memoria retenida real: no se descuentan
        // hasta que el último lease se cierra y se reciclan.
        if (entry.leaseCount == 0) {
            entry.retained = null
            retainedBytes -= entry.retainedBytes
            entry.retainedBytes = 0L
            frameEntries.remove(frameId)
            closeQuietly(image)
        }
    }

    private fun onLeaseReleased(frameId: AngleFrameId, image: AngleDraftRetainedImage) {
        var toClose: AngleDraftRetainedImage? = null
        synchronized(lock) {
            val entry = frameEntries[frameId] ?: return
            if (entry.leaseCount > 0) entry.leaseCount -= 1
            if (entry.leaseCount == 0 && entry.detached) {
                val retained = entry.retained
                entry.retained = null
                retainedBytes -= entry.retainedBytes
                entry.retainedBytes = 0L
                frameEntries.remove(frameId)
                toClose = retained ?: image
            }
        }
        toClose?.let(::closeQuietly)
        notifySafely()
    }

    /**
     * Cierra la fuente una sola vez, en el ejecutor inyectado.
     *
     * Si el ejecutor rechaza la tarea se cierra en línea como último recurso: es preferible bloquear
     * el hilo llamante a filtrar el descriptor de la fuente. Este compromiso está documentado como
     * riesgo conocido en la cabecera de la clase (resolverlo exige que el llamante aporte un ejecutor
     * de respaldo, cambio de contrato fuera de este slice).
     */
    private fun scheduleSourceClose() {
        if (!sourceCloseScheduled.compareAndSet(false, true)) return
        val task = Runnable { closeQuietly(source) }
        val accepted = try {
            executor.execute(task)
            true
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) task.run()
    }

    private fun snapshotLocked(): AngleDraftSessionSnapshot {
        val active = activeCapture
        val version = ++snapshotVersion
        return AngleDraftSessionSnapshot(
            sourceIdentity = sourceIdentity,
            generation = generation,
            draft = draft,
            frames = draft.frames.map { frame ->
                val entry = frameEntries[frame.id]
                AngleDraftFrameState(
                    frameId = frame.id,
                    anchor = frame.anchor,
                    capture = entry?.phase ?: AngleDraftCapturePhase.NotCaptured,
                    leaseCount = entry?.leaseCount ?: 0,
                    retainedBytes = entry?.retainedBytes ?: 0L,
                )
            },
            retainedBytes = retainedBytes,
            reservedCaptureBytes = active?.reservedBytes ?: 0L,
            pendingCaptureCount = if (active != null) 1 else 0,
            droppedLateResults = droppedLateResults,
            isClosed = closed,
            version = version,
        )
    }

    private fun notifyIfApplied(outcome: AngleDraftCoordinatorOutcome) {
        if (outcome is AngleDraftCoordinatorOutcome.Applied) notifySafely()
    }

    private fun notifySafely() {
        deliver(snapshot())
    }

    /**
     * Entrega una instantánea al observador fuera del candado. La entrega no está serializada: dos
     * hilos (llamante y ejecutor) pueden avisar a la vez y el orden de llegada no tiene por qué ser el
     * de producción. Por eso cada instantánea lleva [AngleDraftSessionSnapshot.version]: el observador
     * debe descartar las versiones no crecientes en lugar de asumir que la última en llegar es la más
     * nueva.
     */
    private fun deliver(snapshot: AngleDraftSessionSnapshot) {
        try {
            observer.onSessionChanged(snapshot)
        } catch (_: RuntimeException) {
            // Un observador defectuoso no revierte ni corrompe el estado ya consistente. La captura
            // y sus rechazos tipados no se convierten en éxito por esto.
        }
    }

    private fun <T> closeQuietly(closeable: T) where T : AutoCloseable {
        try {
            closeable.close()
        } catch (_: RuntimeException) {
            // Cierre best-effort: liberar píxeles/borrar el snapshot no se reporta como éxito de nada.
        }
    }

    internal companion object {
        /** Límite explícito: bytes retenidos reales + peor caso de la captura en curso. */
        const val MAX_RETAINED_PLUS_CAPTURE_BYTES: Long = 64L * 1024L * 1024L

        private fun safeAdd(left: Long, right: Long): Long? = try {
            Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun Rejected(rejection: AngleDraftCoordinatorRejection): AngleDraftCoordinatorOutcome =
        AngleDraftCoordinatorOutcome.Rejected(rejection)

    private fun Applied(snapshot: AngleDraftSessionSnapshot): AngleDraftCoordinatorOutcome =
        AngleDraftCoordinatorOutcome.Applied(snapshot)
}

/**
 * Señal cooperativa de cancelación de una captura.
 *
 * La comprueba el bucle de decodificación del adaptador real (`cancellationCheck`) y el coordinador
 * antes y después de capturar. Es segura entre hilos y no interrumpe nada por sí misma: cancelar
 * invalida la adopción del resultado, no recicla una imagen ya entregada.
 */
internal class AngleDraftCancelSignal {

    private val cancelled = AtomicBoolean(false)

    val isCancelled: Boolean get() = cancelled.get()

    internal fun cancel(): Boolean = cancelled.compareAndSet(false, true)
}

/**
 * Imagen capturada que el coordinador **retiene** mientras el fotograma viva.
 *
 * Es un puerto: el adaptador real envuelve `FrozenFrameImage` (una sola copia inmutable ya orientada)
 * y declara su procedencia. El coordinador solo conoce bytes, dimensiones y procedencia declarada, de
 * modo que su máquina de estados se prueba determinista en JVM sin bitmaps.
 *
 * [close] debe ser idempotente y liberar los píxeles. **Nunca** lo llama la UI: solo el coordinador,
 * cuando ya no hay leases.
 */
internal interface AngleDraftRetainedImage : AutoCloseable {

    /** Identidad de la fuente declarada por la procedencia de la captura. */
    val sourceIdentity: String

    /** Índice de frame declarado por la procedencia. */
    val frameIndex: Int

    /** PTS asignado por el índice con el que se solicitó la captura. */
    val assignedPtsUs: Long

    val width: Int
    val height: Int

    /** Digest de contenido de la imagen congelada (procedencia). */
    val contentDigest: Long

    /** Bytes reales del bitmap retenido. */
    val byteCount: Long

    override fun close()
}

/** Puerto de una fuente ya abierta (una por análisis): índice sellado, anclas y captura. */
internal interface AngleDraftSourcePort : AutoCloseable {

    val sourceIdentity: String

    val frameCount: Int

    /** Ancla declarada para [frameIndex], o `null` si el índice sellado no lo contiene. */
    fun frameAnchor(frameIndex: Int): AngleFrameAnchor?

    /** Peor caso de bytes de una captura de [frameIndex], o `null` si no es presupuestable. */
    fun captureWorstCaseBytes(frameIndex: Int): Long?

    /** Captura síncrona; se invoca **solo** en el ejecutor inyectado. */
    fun capture(frameIndex: Int, cancellation: AngleDraftCancelSignal): AngleDraftCaptureResult

    override fun close()
}

/** Resultado tipado de una captura en el puerto de origen. */
internal sealed interface AngleDraftCaptureResult {

    data class Captured(val image: AngleDraftRetainedImage) : AngleDraftCaptureResult

    data class Blocked(
        val reason: AngleDraftCaptureFailureReason,
        val detail: String,
    ) : AngleDraftCaptureResult

    data object Cancelled : AngleDraftCaptureResult
}

/** Motivos tipados de fallo de captura; nunca se sustituyen por una imagen aproximada. */
internal enum class AngleDraftCaptureFailureReason {
    /** La sesión de origen ya no es válida. */
    SOURCE_CLOSED,

    /** La captura se canceló cooperativamente; no se adopta nada y el fotograma es reintentable. */
    CANCELLED,

    /** La captura no demostró la correlación temporal del frame. */
    TEMPORAL_EVIDENCE_MISSING,

    /** El PTS devuelto no coincide con el solicitado. */
    TEMPORAL_MISMATCH,

    /** No puede probarse la orientación presentada. */
    ORIENTATION_UNDETERMINED,

    /** La captura no cabe en el presupuesto de memoria. */
    MEMORY_BUDGET_EXCEEDED,

    /** El decodificador no entregó un bitmap utilizable. */
    DECODE_UNAVAILABLE,

    /** El layout de salida del decodificador no es convertible sin adivinar. */
    UNSUPPORTED_OUTPUT,

    /** No se alcanzó el frame dentro del presupuesto de decodificación. */
    DECODE_BUDGET_EXCEEDED,

    /** Los metadatos del contenedor no coinciden con el índice sellado. */
    SOURCE_METADATA_MISMATCH,

    /** La imagen declaró otra fuente; se liberó y no se adoptó. */
    CROSS_SOURCE_IMAGE,

    /** La imagen declaró otro frame/PTS que el ancla del fotograma. */
    ANCHOR_MISMATCH,

    /** La imagen no declaró dimensiones/bytes utilizables. */
    INVALID_IMAGE,

    /** El fotograma o la sesión cambiaron antes de adoptar; la imagen se liberó. */
    STALE_SESSION,

    /** El ejecutor de E/S rechazó la tarea; nada se decodificó. */
    EXECUTOR_REJECTED,

    /** La captura falló con una excepción inesperada; se liberó cualquier imagen parcial. */
    CAPTURE_FAILED,
}

/** Estado de captura de un fotograma, visible para la UI futura. */
internal sealed interface AngleDraftCapturePhase {

    /** Sin imagen y sin captura en vuelo; se puede pedir captura. */
    data object NotCaptured : AngleDraftCapturePhase

    /** Captura en vuelo con este intento; single-flight. */
    data class Capturing(val attemptId: Long) : AngleDraftCapturePhase

    /** Imagen retenida por el coordinador y adoptada por el agregado. */
    data class Ready(val token: String) : AngleDraftCapturePhase

    /** Última captura rechazada con motivo tipado; no hay imagen adoptada. */
    data class Failed(
        val reason: AngleDraftCaptureFailureReason,
        val detail: String,
    ) : AngleDraftCapturePhase
}

/** Vista inmutable del estado de un fotograma en la sesión. */
internal data class AngleDraftFrameState(
    val frameId: AngleFrameId,
    val anchor: AngleFrameAnchor,
    val capture: AngleDraftCapturePhase,
    val leaseCount: Int,
    val retainedBytes: Long,
)

/** Instantánea inmutable y coherente de la sesión; lo que observa la UI futura. */
internal data class AngleDraftSessionSnapshot(
    val sourceIdentity: String,
    val generation: Long,
    val draft: AngleAnalysisDraftState,
    val frames: List<AngleDraftFrameState>,
    val retainedBytes: Long,
    val pendingCaptureCount: Int,
    val droppedLateResults: Long,
    val isClosed: Boolean,
    /**
     * Peor caso reservado por la captura de sesión en vuelo. Cuenta contra el límite igual que los
     * bytes retenidos y se mantiene mientras la tarea exista, aunque se haya cancelado el intento.
     */
    val reservedCaptureBytes: Long = 0L,
    /**
     * Versión monótona creciente de la instantánea. La entrega al observador no está serializada, así
     * que la UI debe descartar las versiones no crecientes para no retroceder a un estado anterior.
     */
    val version: Long = 0L,
) {
    val activeFrameId: AngleFrameId? get() = draft.activeFrameId

    fun frame(frameId: AngleFrameId): AngleDraftFrameState? = frames.firstOrNull { it.frameId == frameId }
}

/** Resultado de una operación del coordinador: instantánea nueva o rechazo tipado sin cambios. */
internal sealed interface AngleDraftCoordinatorOutcome {

    data class Applied(val snapshot: AngleDraftSessionSnapshot) : AngleDraftCoordinatorOutcome

    data class Rejected(val rejection: AngleDraftCoordinatorRejection) : AngleDraftCoordinatorOutcome
}

/** Motivos tipados de rechazo del coordinador (no del agregado). */
internal sealed interface AngleDraftCoordinatorRejection {

    /** La sesión ya se cerró; ninguna operación nueva se aplica. */
    data object Closed : AngleDraftCoordinatorRejection

    /** No existe ningún fotograma con ese id en la sesión. */
    data class UnknownFrame(val frameId: AngleFrameId) : AngleDraftCoordinatorRejection

    /** El índice de frame no pertenece al índice sellado de la fuente. */
    data class FrameIndexOutOfRange(
        val frameIndex: Int,
        val frameCount: Int,
    ) : AngleDraftCoordinatorRejection

    /** El puerto no pudo declarar el ancla de ese frame. */
    data class AnchorUnavailable(val frameIndex: Int) : AngleDraftCoordinatorRejection

    /** El ancla declarada pertenece a otra fuente; no se mezclan análisis. */
    data class SourceIdentityMismatch(
        val expected: String,
        val received: String,
    ) : AngleDraftCoordinatorRejection

    /** El agregado rechazó la operación; se propaga su motivo tipado. */
    data class DraftRejected(val reason: AngleAnalysisDraftRejection) : AngleDraftCoordinatorRejection

    /** No se puede aplicar geometría sin una imagen capturada y retenida en este fotograma. */
    data class FrameImageNotReady(val frameId: AngleFrameId) : AngleDraftCoordinatorRejection

    /** El fotograma ya tiene imagen retenida; no se vuelve a decodificar el vídeo para él. */
    data class FrameAlreadyCaptured(val frameId: AngleFrameId) : AngleDraftCoordinatorRejection

    /** Ya hay una captura en vuelo para ese fotograma (single-flight). */
    data class CaptureAlreadyInFlight(
        val frameId: AngleFrameId,
        val attemptId: Long,
    ) : AngleDraftCoordinatorRejection

    /**
     * La sesión ya tiene una captura en vuelo, de **otro** fotograma, o de este mismo tras cancelarla
     * mientras su tarea seguía viva. Es un rechazo recuperable: la reserva de memoria y el
     * single-flight se sueltan cuando el trabajo anterior termina de ejecutarse y el observador verá
     * [AngleDraftSessionSnapshot.pendingCaptureCount] volver a 0; entonces basta reintentar. Persiste
     * también tras la cancelación y la retirada del fotograma, porque la memoria comprometida es real.
     */
    data class SessionCaptureInFlight(
        val activeFrameId: AngleFrameId,
        val activeAttemptId: Long,
        val reservedBytes: Long,
        /** El intento activo ya fue cancelado: su resultado no se adoptará, pero su tarea sigue viva. */
        val cancelled: Boolean,
    ) : AngleDraftCoordinatorRejection

    /** No hay captura en vuelo que cancelar. */
    data class CaptureNotInFlight(val frameId: AngleFrameId) : AngleDraftCoordinatorRejection

    /**
     * El puerto no pudo presupuestar el peor caso de la captura (o declaró un valor no positivo) y se
     * rechaza antes de decodificar: un peor caso de 0 B desactivaría el límite, así que falla cerrado.
     */
    data class CaptureBudgetUnavailable(val frameId: AngleFrameId) : AngleDraftCoordinatorRejection

    /**
     * `retenidos + reserva de la captura en vuelo + peor caso` supera el límite; **no** se inició
     * ninguna captura. Es recuperable: liberar otro fotograma o esperar al trabajo en curso y
     * reintentar.
     */
    data class MemoryBudgetExceeded(
        val retainedBytes: Long,
        val reservedBytes: Long,
        val captureWorstCaseBytes: Long,
        val limitBytes: Long,
    ) : AngleDraftCoordinatorRejection

    /** El fotograma no tiene una imagen retenida que prestar. */
    data class ImageNotRetained(val frameId: AngleFrameId) : AngleDraftCoordinatorRejection

    /** Espacio de ids locales agotado (irrecuperable dentro de esta sesión). */
    data object FrameIdSpaceExhausted : AngleDraftCoordinatorRejection

    /** Espacio de intentos agotado (irrecuperable dentro de esta sesión). */
    data object CaptureAttemptSpaceExhausted : AngleDraftCoordinatorRejection
}

/** Ejecutor de trabajo de captura; en producción, un ejecutor de E/S de un solo hilo. */
internal fun interface AngleDraftExecutor {
    fun execute(task: Runnable)
}

/** Observador de cambios de la sesión; puede invocarse desde el hilo del ejecutor. */
internal fun interface AngleDraftSessionObserver {
    /**
     * Recibe una instantánea coherente, fuera del candado de la sesión. Puede llegar desde el hilo del
     * llamante o del ejecutor y **no** hay orden garantizado entre avisos concurrentes: se debe
     * ignorar cualquier instantánea con `version` no mayor que la última aplicada.
     */
    fun onSessionChanged(snapshot: AngleDraftSessionSnapshot)
}

/** Resultado de pedir un lease de imagen. */
internal sealed interface AngleDraftLeaseOutcome {

    data class Granted(val lease: AngleDraftFrameImageLease) : AngleDraftLeaseOutcome

    data class Rejected(val rejection: AngleDraftCoordinatorRejection) : AngleDraftLeaseOutcome
}

/**
 * Préstamo de la imagen retenida de un fotograma.
 *
 * **Contrato de propiedad (obligatorio para la UI):**
 * 1. Mientras el lease esté abierto, el coordinador no recicla la imagen; se puede dibujar su bitmap.
 * 2. Debe cerrarse una sola vez (es idempotente) en el hilo de dibujo, al salir de composición o al
 *    dejar de referenciar el bitmap, de modo que no quede ningún dibujo en vuelo.
 * 3. Tras `close`, no se debe volver a tocar [AngleDraftRetainedImage] ni el bitmap obtenido.
 * 4. El lease no se hereda a otra Activity ni se guarda: tras recrear la UI se pide otro lease al
 *    coordinador, que conserva la imagen.
 */
internal class AngleDraftFrameImageLease internal constructor(
    val frameId: AngleFrameId,
    internal val image: AngleDraftRetainedImage,
    private val onReleased: (AngleDraftRetainedImage) -> Unit,
) : AutoCloseable {

    val width: Int get() = image.width
    val height: Int get() = image.height
    val contentDigest: Long get() = image.contentDigest

    private val released = AtomicBoolean(false)

    /** `true` mientras el bitmap esté prestado y no deba reciclarse. */
    val isOpen: Boolean get() = !released.get()

    override fun close() {
        if (released.compareAndSet(false, true)) {
            onReleased(image)
        }
    }
}

/**
 * Adaptador del puerto de origen sobre una [FrozenFrameSourceSession] ya abierta.
 *
 * No abre, copia ni indexa nada: eso ya se hizo fuera del hilo principal. Solo traduce índice, anclas,
 * presupuesto de captura y el resultado tipado del productor de frames.
 */
internal class FrozenFrameSourceSessionPort(
    private val session: FrozenFrameSourceSession,
) : AngleDraftSourcePort {

    override val sourceIdentity: String = session.identity.value

    override val frameCount: Int = session.index.frameCount

    override fun frameAnchor(frameIndex: Int): AngleFrameAnchor? {
        val granted = session.request(frameIndex) as? FrameSourceRequestResult.Granted ?: return null
        return AngleFrameAnchor(
            sourceIdentity = sourceIdentity,
            frameIndex = granted.request.frameIndex,
            sourcePtsUs = granted.request.assignedPtsUs,
        )
    }

    override fun captureWorstCaseBytes(frameIndex: Int): Long? {
        if (frameIndex !in 0 until frameCount) return null
        val encodedWidth = session.index.encodedWidth
        val encodedHeight = session.index.encodedHeight
        val rotated = session.index.declaredRotationDegrees % 180 != 0
        val frozenWidth = if (rotated) encodedHeight else encodedWidth
        val frozenHeight = if (rotated) encodedWidth else encodedHeight
        // El bitmap decodificado puede venir antes o después de la rotación declarada: se presupuesta
        // el mayor de ambos casos, además de las copias congeladas del peor caso.
        return listOfNotNull(
            FrameFreezeBudget.captureRequiredBytes(
                encodedWidth, encodedHeight, frozenWidth, frozenHeight,
            ),
            FrameFreezeBudget.captureRequiredBytes(
                encodedHeight, encodedWidth, frozenWidth, frozenHeight,
            ),
        ).maxOrNull()
    }

    override fun capture(
        frameIndex: Int,
        cancellation: AngleDraftCancelSignal,
    ): AngleDraftCaptureResult {
        val granted = session.request(frameIndex) as? FrameSourceRequestResult.Granted
            ?: return AngleDraftCaptureResult.Blocked(
                reason = AngleDraftCaptureFailureReason.SOURCE_CLOSED,
                detail = "La sesión de origen no puede emitir la solicitud del frame $frameIndex.",
            )
        return when (val result = session.capture(granted.request) { cancellation.isCancelled }) {
            is FrameFreezeResult.Frozen -> AngleDraftCaptureResult.Captured(
                FrozenFrameImageRetainer(result.image),
            )

            is FrameFreezeResult.Blocked -> AngleDraftCaptureResult.Blocked(
                reason = result.reason.toCoordinatorReason(),
                detail = result.detail,
            )
        }
    }

    override fun close() {
        session.close()
    }

    private fun FrameFreezeRejectionReason.toCoordinatorReason(): AngleDraftCaptureFailureReason =
        when (this) {
            FrameFreezeRejectionReason.SOURCE_SESSION_INVALID ->
                AngleDraftCaptureFailureReason.SOURCE_CLOSED

            FrameFreezeRejectionReason.UNVERIFIED_TEMPORAL_CORRELATION ->
                AngleDraftCaptureFailureReason.TEMPORAL_EVIDENCE_MISSING

            FrameFreezeRejectionReason.DECODER_PTS_MISMATCH,
            FrameFreezeRejectionReason.REQUEST_EVIDENCE_MISMATCH,
            -> AngleDraftCaptureFailureReason.TEMPORAL_MISMATCH

            FrameFreezeRejectionReason.ORIENTATION_UNDETERMINED ->
                AngleDraftCaptureFailureReason.ORIENTATION_UNDETERMINED

            FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED ->
                AngleDraftCaptureFailureReason.MEMORY_BUDGET_EXCEEDED

            FrameFreezeRejectionReason.DECODED_BITMAP_UNAVAILABLE ->
                AngleDraftCaptureFailureReason.DECODE_UNAVAILABLE

            FrameFreezeRejectionReason.UNSUPPORTED_CODEC_OUTPUT ->
                AngleDraftCaptureFailureReason.UNSUPPORTED_OUTPUT

            FrameFreezeRejectionReason.DECODE_BUDGET_EXCEEDED ->
                AngleDraftCaptureFailureReason.DECODE_BUDGET_EXCEEDED

            FrameFreezeRejectionReason.CANCELLED ->
                AngleDraftCaptureFailureReason.CANCELLED

            FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH ->
                AngleDraftCaptureFailureReason.SOURCE_METADATA_MISMATCH
        }
}

/**
 * Adaptador de una [FrozenFrameImage] real al puerto de imagen retenida.
 *
 * El bitmap subyacente es una copia inmutable ya orientada y con digest de procedencia; esta clase no
 * lo copia, no lo decodifica y no lo recicla hasta [close]. El acceso a los píxeles es `internal` y
 * solo debe usarse bajo un lease vigente.
 */
internal class FrozenFrameImageRetainer(
    private val image: FrozenFrameImage,
) : AngleDraftRetainedImage {

    private val provenance = image.provenance

    override val sourceIdentity: String = provenance.sourceIdentity.value

    override val frameIndex: Int = provenance.frameIndex

    override val assignedPtsUs: Long = provenance.assignedRequestPtsUs

    override val width: Int = image.width

    override val height: Int = image.height

    override val contentDigest: Long = provenance.contentDigest

    override val byteCount: Long = width.toLong() * height.toLong() * 4L

    /**
     * Píxeles para la capa de dibujo. Solo válido mientras el lease esté abierto; tras reciclarse,
     * `FrozenFrameImage.bitmap` falla en lugar de devolver píxeles liberados.
     */
    internal val bitmap: Bitmap
        get() = image.bitmap

    /**
     * Imagen congelada real que este retenedor conserva.
     *
     * Solo se entrega para poder anotarla con [AngleAnnotationRenderer], que exige la
     * [FrozenFrameImage] (no un bitmap suelto) porque valida su procedencia antes de copiar. El
     * llamante **debe** mantener un lease abierto mientras la use: el lease es lo que impide que el
     * coordinador recicle estos píxeles. Tras cerrar el último lease esta referencia deja de ser
     * válida y `FrozenFrameImage.bitmap` falla en lugar de devolver píxeles liberados.
     */
    internal val frozenImage: FrozenFrameImage
        get() = image

    override fun close() {
        image.close()
    }
}
