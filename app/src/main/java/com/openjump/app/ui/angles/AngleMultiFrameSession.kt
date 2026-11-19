package com.openjump.app.ui.angles

import android.graphics.Bitmap
import com.openjump.app.angles.AngleAnalysisDraftRejection
import com.openjump.app.angles.AngleAnalysisFinalization
import com.openjump.app.angles.AngleDiscardConfirmation
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorOutcome
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleFrameDraft
import com.openjump.app.angles.AngleFrameId
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.video.angles.FrozenFrameSourceOpen
import com.openjump.app.video.angles.SourceOpenFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controlador de una sesión de análisis **multi-fotograma** sobre un vídeo ya abierto: fotogramas
 * congelados independientes, geometría por fotograma y préstamo de píxeles a la capa de dibujo.
 *
 * ## Qué es y qué no es
 *
 * - Es la fachada que la pantalla usará para «capturar este frame pausado, marcarlo y pasar al
 *   siguiente». Envuelve [AngleDraftSessionCoordinator] (agregado multi-fotograma + propiedad de
 *   píxeles + límite de memoria + single-flight de captura) y **no** lo reimplementa: toda la
 *   coherencia de anclas, revisiones, memoria y respuestas tardías sigue viviendo allí.
 * - **No abre el vídeo**: recibe la fuente ya abierta como [AngleDraftSourcePort] (en producción,
 *   [FrozenFrameSourceSessionPort] sobre una `FrozenFrameSourceSession` sellada por
 *   [com.openjump.app.video.angles.FrozenFrameSources]). Copiar, sellar e indexar es E/S y lo hace
 *   el llamante fuera del hilo principal.
 * - **No pinta, no navega, no guarda y no escribe Room.** Este slice no conecta la persistencia
 *   multi-fotograma (`angle_analysis_v2_*` + almacén de imágenes) ni la copia de seguridad: la
 *   finalización que expone es un **plan** ([AngleAnalysisFinalization]) que todavía no tiene
 *   consumidor de escritura. Nada de esta capa puede perder datos ya persistidos porque no escribe.
 * - **No sobrevive a la muerte del proceso.** El objeto vive en un `ViewModel` para sobrevivir a la
 *   recreación de la Activity; si el proceso muere, los fotogramas congelados y su geometría se
 *   pierden por completo.
 * - **No decide producto**: los textos, la confirmación en pantalla, el orden de las acciones y el
 *   momento de cerrar los leases son de la capa de UI.
 *
 * ## Invariantes que impone
 *
 * - **Una sola fuente.** La identidad sellada la declara el puerto ([sourceIdentity]) y la identidad
 *   declarada por el visor se fija al construir ([AngleFrameConfirmation.frame] debe nombrarla). No
 *   hay ninguna operación para mezclar fotogramas de dos vídeos ni para reutilizar la sesión con otra
 *   fuente: abrir otro vídeo exige otra instancia y, por tanto, otra copia sellada.
 * - **El fotograma capturado es el que el usuario confirmó.** La confirmación del visor aporta el
 *   índice y su PTS; el ancla la acuña el índice sellado de la fuente y se exige que el PTS
 *   confirmado sea **exactamente** el del índice ([AngleMultiFrameRejection.ConfirmationPtsMismatch]).
 *   Esa igualdad es la prueba de que el bitmap congelado corresponde al frame pausado; si no se
 *   cumple, no se registra ni se captura nada (nunca se aproxima).
 * - **Nada de geometría sobre vídeo vivo.** [editFrame] construye la confirmación **desde el ancla de
 *   la imagen congelada ya adoptada** (`paused = true`, misma generación) y solo la aplica si el
 *   fotograma tiene imagen retenida y mostrable; si no, el rechazo es tipado
 *   ([AngleDraftCoordinatorRejection.FrameImageNotReady]). No existe ningún camino para marcar sobre
 *   el player en movimiento, ni para «confirmar» un frame distinto del congelado.
 * - **Solo el fotograma activo es editable.** La geometría se apoya en el bitmap congelado visible:
 *   editar un fotograma que la pantalla no está mostrando no lo puede verificar el usuario, así que se
 *   rechaza con [AngleMultiFrameRejection.NotActiveFrame] en vez de aceptarlo a ciegas. Cambiar de
 *   fotograma es [selectFrame] (presentación: no toca imágenes, A/B, ángulos ni revisiones).
 * - **Capturar un frame ya registrado no lo recaptura.** Los píxeles de un fotograma se mantienen
 *   exactos hasta descartarlo; pedir otra captura del mismo índice devuelve
 *   [AngleMultiFrameRejection.FrameAlreadyTracked] (con [AngleMultiFrameRejection.FrameAlreadyTracked.hasImage])
 *   en lugar de volver a decodificar y sustituir la imagen que el usuario ya está midiendo.
 * - **Los errores de captura y de memoria no se ocultan ni se degradan.** Se propagan con el motivo
 *   tipado del coordinador ([AngleMultiFrameRejection.Coordinator] envuelve
 *   [AngleDraftCoordinatorRejection]: `MemoryBudgetExceeded`, `SessionCaptureInFlight`,
 *   `CaptureBudgetUnavailable`, …) y el fotograma queda registrado sin imagen, reintentable con
 *   [captureFrame]. Nunca se entrega una imagen aproximada ni se declara capturado algo que no lo está.
 * - **Instantáneas con control de versión.** La entrega del coordinador no está serializada: puede
 *   llegar desde el hilo del ejecutor (adopción de una captura) o del llamante. [state] aplica
 *   **solo** versiones estrictamente crecientes ([AngleMultiFrameSessionState.version]), así que un
 *   aviso adelantado nunca retrocede el estado publicado.
 * - **Cierre idempotente.** [close] cancela las capturas en vuelo e invalida sus respuestas, libera
 *   las imágenes sin leases y cierra la fuente **en el ejecutor inyectado**. Las imágenes con leases
 *   abiertos se liberan cuando el último lease se cierra: los píxeles que la pantalla está dibujando
 *   no se reciclan por debajo.
 *
 * ## Propiedad de los píxeles (contrato para la pantalla)
 *
 * La pantalla **nunca** recibe el objeto propietario de la imagen: obtiene un
 * [AngleMultiFrameBitmapLease] con [AngleMultiFrameBitmapLease.bitmap], que es **el mismo bitmap
 * inmutable** de la captura (sin copiar, sin mutar, sin recapturar). Mientras el lease esté abierto,
 * esta capa no recicla la imagen. El lease debe cerrarse (idempotente) en el hilo de dibujo al salir
 * de composición; tras cerrarlo no se puede volver a tocar el bitmap (el acceso falla en vez de
 * devolver píxeles liberados) y, para volver a dibujar, se pide **otro** lease.
 *
 * ## Concurrencia
 *
 * Las operaciones públicas son cortas y seguras desde el hilo principal; la captura real ocurre en el
 * ejecutor inyectado y **nunca** en línea. Las mutaciones de geometría se serializan con un candado
 * propio (leer editor → calcular → aplicar) para que dos llamantes concurrentes no apliquen un
 * resultado calculado sobre una revisión que ya cambió; la acción [editFrame] se ejecuta bajo ese
 * candado y debe ser **pura y rápida** (nunca E/S, nunca bloqueos).
 */
internal class AngleMultiFrameSession(
    private val source: AngleDraftSourcePort,
    /** Generación de render del editor que abre la sesión; acota las confirmaciones aceptadas. */
    val generation: Long,
    /**
     * Identidad de la fuente que declara el visor (la URI del vídeo abierto). Acota las
     * confirmaciones: una confirmación de otro vídeo no captura nada aquí.
     */
    private val viewerSourceIdentity: String,
    defaults: AngleEditorDefaults,
    executor: AngleDraftExecutor,
    maxRetainedPlusCaptureBytes: Long =
        AngleDraftSessionCoordinator.MAX_RETAINED_PLUS_CAPTURE_BYTES,
) : AutoCloseable {

    init {
        require(viewerSourceIdentity.isNotBlank()) {
            "La identidad de la fuente declarada por el visor no puede estar vacía."
        }
        require(generation > 0L) { "La generación de la sesión debe ser > 0, era $generation." }
    }

    /** Identidad acuñada por la fuente sellada; **no** es la URI ni una etiqueta libre. */
    val sourceIdentity: String = source.sourceIdentity

    /** Fotogramas del índice sellado con los que se puede trabajar (cota de los índices válidos). */
    val sourceFrameCount: Int = source.frameCount

    private val publishLock = Any()

    /** Última versión aplicada a [state]; solo se aceptan versiones estrictamente mayores. */
    private var lastPublishedVersion = Long.MIN_VALUE

    private val closeRequested = AtomicBoolean(false)

    /** Serializa «leer editor → calcular resultado → aplicar» para no aplicar sobre revisión ajena. */
    private val editLock = Any()

    private val coordinator = AngleDraftSessionCoordinator(
        source = source,
        generation = generation,
        editorDefaults = defaults,
        executor = executor,
        observer = AngleDraftSessionObserver { snapshot -> publish(snapshot) },
        maxRetainedPlusCaptureBytes = maxRetainedPlusCaptureBytes,
    )

    private val _state: MutableStateFlow<AngleMultiFrameSessionState> =
        MutableStateFlow(coordinator.snapshot().toSessionState())

    /** Estado observable de la sesión: fotogramas, selección, memoria, finalización y versión. */
    val state: StateFlow<AngleMultiFrameSessionState> = _state.asStateFlow()

    init {
        lastPublishedVersion = _state.value.version
    }

    /** `true` una vez cerrada la sesión (no admite operaciones nuevas y los píxeles se liberan). */
    val isClosed: Boolean get() = closeRequested.get() || _state.value.isClosed

    // ---------------------------------------------------------------- fotogramas

    /**
     * Registra **y** captura el fotograma que el visor confirma **en pausa** ahora mismo.
     *
     * La confirmación la aporta el propietario del player (en producción,
     * [AngleEditorViewModel.currentConfirmation], que ya exige `canMark` y PTS canónico). Aquí solo se
     * usa su **índice**, su PTS y su condición de pausa: el ancla del fotograma la acuña el índice
     * sellado de la fuente ([AngleDraftSourcePort.frameAnchor]), y se exige que el PTS confirmado por
     * el visor sea **exactamente** el que ese índice asigna al frame. Con esa igualdad el bitmap
     * congelado que se va a medir es el frame que el usuario vio, no uno aproximado; si no se cumple,
     * no se registra nada ([AngleMultiFrameRejection.ConfirmationPtsMismatch]). Si la fuente sellada no
     * tiene ese frame, el rechazo también es tipado
     * ([AngleDraftCoordinatorRejection.FrameIndexOutOfRange]) en lugar de adaptarse.
     *
     * El fotograma queda **seleccionado** (es el que la pantalla puede marcar y editar) y la captura
     * se despacha al ejecutor: el estado publicado pasa a
     * [AngleDraftCapturePhase.Capturing] y la respuesta llega como [AngleDraftCapturePhase.Ready] o
     * [AngleDraftCapturePhase.Failed] con su motivo. Un rechazo de captura (memoria agotada, otra
     * captura en vuelo, presupuesto no declarable) **no** elimina el fotograma registrado: se puede
     * reintentar con [captureFrame] y el motivo se devuelve sin degradar nada.
     */
    fun capturePausedFrame(confirmation: AngleFrameConfirmation): AngleMultiFrameResult {
        if (!confirmation.paused) {
            return rejected(AngleMultiFrameRejection.FrameNotPaused)
        }
        if (confirmation.frame.sourceIdentity != viewerSourceIdentity) {
            return rejected(
                AngleMultiFrameRejection.ForeignConfirmation(
                    expected = viewerSourceIdentity,
                    received = confirmation.frame.sourceIdentity,
                ),
            )
        }
        if (confirmation.generation != generation) {
            return rejected(
                AngleMultiFrameRejection.StaleConfirmation(
                    expected = generation,
                    received = confirmation.generation,
                ),
            )
        }
        val current = state.value
        if (current.isClosed) {
            return rejected(closedRejection())
        }
        val frameIndex = confirmation.frame.frameIndex
        current.frames.firstOrNull { it.anchor.frameIndex == frameIndex }?.let { tracked ->
            // Los píxeles de un fotograma ya capturado se mantienen exactos: no se recapturan.
            return rejected(
                AngleMultiFrameRejection.FrameAlreadyTracked(
                    frameId = tracked.frameId,
                    hasImage = tracked.canEdit,
                ),
            )
        }
        // Prueba de identidad del frame: el PTS confirmado por el visor debe ser el que el índice
        // sellado asigna a ese índice. Sin esa igualdad no se puede afirmar que el bitmap congelado
        // sea la imagen que el usuario confirmó, así que se falla cerrado (un índice fuera del índice
        // sellado se informa luego con FrameIndexOutOfRange desde addFrame).
        if (frameIndex in 0 until sourceFrameCount) {
            val sealedPtsUs = source.frameAnchor(frameIndex)?.sourcePtsUs
            if (sealedPtsUs != null && sealedPtsUs != confirmation.frame.sourcePtsUs) {
                return rejected(
                    AngleMultiFrameRejection.ConfirmationPtsMismatch(
                        frameIndex = frameIndex,
                        confirmedPtsUs = confirmation.frame.sourcePtsUs,
                        sealedPtsUs = sealedPtsUs,
                    ),
                )
            }
        }
        // Estrechado explicito: tras un `if (added is ...Rejected) return` el compilador no
        // convierte `added` al caso `Applied`, asi que `added.snapshot` no resolvia. El `when`
        // mantiene exactamente el mismo control de flujo (rechazo con su motivo tipado, si no
        // seguimos con la instantanea aplicada) sin relajar ninguna comprobacion.
        val added = when (val outcome = coordinator.addFrame(frameIndex)) {
            is AngleDraftCoordinatorOutcome.Rejected ->
                return rejected(outcome.rejection.toMultiFrameRejection(current))

            is AngleDraftCoordinatorOutcome.Applied -> outcome
        }
        val frameId = added.snapshot.frames
            .firstOrNull { it.anchor.frameIndex == frameIndex }
            ?.frameId
            ?: return rejected(
                AngleMultiFrameRejection.InternalInconsistency(
                    "El fotograma $frameIndex se registró pero la instantánea no lo contiene.",
                ),
            )
        val selection = coordinator.selectFrame(frameId)
        if (selection is AngleDraftCoordinatorOutcome.Rejected) {
            return rejected(selection.rejection.toMultiFrameRejection(state.value))
        }
        return route(coordinator.captureFrame(frameId), state.value)
    }

    /**
     * Reintenta la captura de un fotograma ya registrado (por ejemplo tras un rechazo de memoria o
     * una captura fallida). No registra nada nuevo ni cambia la selección.
     */
    fun captureFrame(frameId: AngleFrameId): AngleMultiFrameResult =
        route(coordinator.captureFrame(frameId), state.value)

    /** Cancela cooperativamente la captura en vuelo de [frameId]; la reserva se suelta al terminar. */
    fun cancelCapture(frameId: AngleFrameId): AngleMultiFrameResult =
        route(coordinator.cancelCapture(frameId), state.value)

    /** Selecciona el fotograma visible; `null` limpia la selección. No toca contenido ni revisiones. */
    fun selectFrame(frameId: AngleFrameId?): AngleMultiFrameResult =
        route(coordinator.selectFrame(frameId), state.value)

    /**
     * Elimina un fotograma. Si conserva imagen, ángulos o puntos A/B exige
     * [AngleDiscardConfirmation.CONFIRMED]; sin ella el rechazo es
     * [AngleAnalysisDraftRejection.DiscardConfirmationRequired] (envuelto en
     * [AngleMultiFrameRejection.Coordinator]) y el fotograma queda intacto. La pantalla debe consultar
     * antes [AngleMultiFrameItem.hasData] (o el estado del fotograma) para pedir la confirmación.
     */
    fun removeFrame(
        frameId: AngleFrameId,
        confirmation: AngleDiscardConfirmation,
    ): AngleMultiFrameResult = route(coordinator.removeFrame(frameId, confirmation), state.value)

    // ---------------------------------------------------------------- geometría por fotograma

    /**
     * Aplica una acción del editor **al fotograma activo**, sobre su imagen congelada.
     *
     * La [action] recibe el editor vigente del fotograma y una confirmación construida aquí —ancla de
     * la imagen ya adoptada, misma generación, en pausa— y devuelve el resultado del editor puro. El
     * resultado se aplica a través del agregado ([AngleDraftSessionCoordinator.applyEditorOutcome]),
     * que exige imagen retenida y revisión coherente.
     *
     * Se rechaza sin tocar nada si el fotograma no existe
     * ([AngleDraftCoordinatorRejection.UnknownFrame]), si no es el activo
     * ([AngleMultiFrameRejection.NotActiveFrame]) o si no tiene imagen congelada mostrable
     * ([AngleDraftCoordinatorRejection.FrameImageNotReady]). La acción se ejecuta bajo el candado de
     * esta sesión: debe ser pura, rápida y no tocar disco, red ni bloquear.
     */
    fun editFrame(
        frameId: AngleFrameId,
        action: (AngleEditorState, AngleFrameConfirmation) -> AngleEditorOutcome,
    ): AngleMultiFrameResult = synchronized(editLock) {
        val current = state.value
        if (current.isClosed) return@synchronized rejected(closedRejection())
        val item = current.frame(frameId)
            ?: return@synchronized rejected(
                AngleMultiFrameRejection.Coordinator(
                    AngleDraftCoordinatorRejection.UnknownFrame(frameId),
                ),
            )
        if (!item.isActive) {
            return@synchronized rejected(
                AngleMultiFrameRejection.NotActiveFrame(
                    frameId = frameId,
                    activeFrameId = current.activeFrameId,
                ),
            )
        }
        if (!item.canEdit) {
            // Marcar sobre vídeo vivo no es una opción: sin píxeles congelados no hay geometría.
            return@synchronized rejected(
                AngleMultiFrameRejection.Coordinator(
                    AngleDraftCoordinatorRejection.FrameImageNotReady(frameId),
                ),
            )
        }
        val confirmation = AngleFrameConfirmation(
            frame = item.anchor,
            generation = generation,
            paused = true,
        )
        val outcome = action(item.editor, confirmation)
        route(coordinator.applyEditorOutcome(frameId, outcome), current)
    }

    // ---------------------------------------------------------------- píxeles prestados

    /**
     * Pide un lease del bitmap congelado **exacto** de un fotograma (mismo objeto inmutable de la
     * captura: no se copia, no se muta y no se vuelve a decodificar nada).
     *
     * Devuelve [AngleMultiFrameBitmapResult.Rejected] con el motivo tipado del coordinador si la
     * sesión está cerrada, el fotograma no existe o no tiene imagen retenida. La pantalla es dueña del
     * lease y **debe** cerrarlo en el hilo de dibujo al salir de composición; mientras siga abierto,
     * los píxeles no se reciclan.
     */
    fun acquireBitmap(frameId: AngleFrameId): AngleMultiFrameBitmapResult {
        val lease = when (val outcome = coordinator.acquireFrameImageLease(frameId)) {
            is AngleDraftLeaseOutcome.Rejected -> return AngleMultiFrameBitmapResult.Rejected(
                AngleMultiFrameRejection.Coordinator(outcome.rejection),
            )

            is AngleDraftLeaseOutcome.Granted -> outcome.lease
        }
        val retainer = lease.image as? FrozenFrameImageRetainer
        if (retainer == null) {
            // Se devuelve el lease antes de rechazar: no se presta nada que no se pueda garantizar.
            lease.close()
            return AngleMultiFrameBitmapResult.Rejected(
                AngleMultiFrameRejection.BitmapUnavailable(frameId),
            )
        }
        return AngleMultiFrameBitmapResult.Granted(AngleMultiFrameBitmapLease(lease, retainer))
    }

    // ---------------------------------------------------------------- instantánea de guardado

    /**
     * Instantánea para guardar: comprueba el plan de finalización y retiene, bajo lease, la
     * [com.openjump.app.video.angles.FrozenFrameImage] **exacta** de cada fotograma, junto con la
     * geometría del editor de ese fotograma y su ancla.
     *
     * - La imagen que se entrega es la misma que el coordinador retiene para dibujar: se obtiene con
     *   [AngleDraftSessionCoordinator.acquireFrameImageLease] y solo se acepta si el retenedor expone
     *   la [com.openjump.app.video.angles.FrozenFrameImage] real (la que acredita procedencia para
     *   [AngleAnnotationRenderer]). No se copia, no se redecodifica y no se sustituye por otra.
     * - Se rechaza sin prestar nada si la sesión está cerrada
     *   ([AngleMultiFrameSaveRejection.SessionClosed]) o si el análisis **no** es finalizable
     *   ([AngleMultiFrameSaveRejection.NotFinalizable] con todos los bloqueos del plan). Si un
     *   fotograma no tiene imagen retenida, los leases ya concedidos se devuelven y el rechazo es
     *   tipado ([AngleMultiFrameSaveRejection.ImageNotRetained]).
     * - El llamante es dueño de la instantánea y **debe** cerrarla en `finally`: mientras siga
     *   abierta, estos píxeles no se reciclan (aunque la sesión se cierre o el fotograma se elimine).
     * - La instantánea se toma bajo el candado de edición de esta sesión, de modo que la geometría
     *   entregada no cambia a mitad de la captura; las mutaciones de la pantalla deben estar
     *   bloqueadas por el llamante (el `ViewModel` lo hace con su single-flight de guardado).
     */
    fun acquireSaveSnapshot(): AngleMultiFrameSaveSnapshotResult = synchronized(editLock) {
        val current = state.value
        if (current.isClosed) {
            return@synchronized AngleMultiFrameSaveSnapshotResult.Rejected(
                AngleMultiFrameSaveRejection.SessionClosed,
            )
        }
        val finalization = coordinator.finalization()
        if (finalization is AngleAnalysisFinalization.Blocked) {
            // Nada que retener: el plan dice exactamente qué fotograma impide guardar.
            return@synchronized AngleMultiFrameSaveSnapshotResult.Rejected(
                AngleMultiFrameSaveRejection.NotFinalizable(finalization.blockers),
            )
        }
        val ready = finalization as AngleAnalysisFinalization.Ready
        val frames = ArrayList<AngleMultiFrameSaveFrame>(ready.frames.size)
        for (frame in ready.frames) {
            val item = current.frame(frame.id)
            if (item == null || item.editor.angles != frame.angles) {
                closeQuietly(frames)
                return@synchronized AngleMultiFrameSaveSnapshotResult.Rejected(
                    AngleMultiFrameSaveRejection.InternalInconsistency(
                        "El fotograma ${frame.id.value} del plan no coincide con el estado publicado.",
                    ),
                )
            }
            when (val leased = coordinator.acquireFrameImageLease(frame.id)) {
                is AngleDraftLeaseOutcome.Rejected -> {
                    closeQuietly(frames)
                    return@synchronized AngleMultiFrameSaveSnapshotResult.Rejected(
                        AngleMultiFrameSaveRejection.ImageNotRetained(frame.id),
                    )
                }

                is AngleDraftLeaseOutcome.Granted -> {
                    val retainer = leased.lease.image as? FrozenFrameImageRetainer
                    if (retainer == null) {
                        // No se presta nada que no se pueda acreditar: se devuelve el lease.
                        leased.lease.close()
                        closeQuietly(frames)
                        return@synchronized AngleMultiFrameSaveSnapshotResult.Rejected(
                            AngleMultiFrameSaveRejection.ImageNotRetained(frame.id),
                        )
                    }
                    frames += AngleMultiFrameSaveFrame(
                        frameId = frame.id,
                        anchor = frame.anchor,
                        editor = item.editor,
                        lease = AngleMultiFrameFrozenImageLease(leased.lease, retainer),
                    )
                }
            }
        }
        AngleMultiFrameSaveSnapshotResult.Captured(
            AngleMultiFrameSaveSnapshot(
                sourceIdentity = current.sourceIdentity,
                generation = current.generation,
                frames = frames,
            ),
        )
    }

    private fun closeQuietly(frames: List<AngleMultiFrameSaveFrame>) {
        frames.forEach { it.close() }
    }

    // ---------------------------------------------------------------- finalización

    /**
     * Plan de finalización vigente: qué podría guardarse y qué lo impide (fotograma sin imagen, sin
     * ángulo completo o con puntos A/B pendientes).
     *
     * Es una consulta **pura**: no guarda, no escribe y no libera nada. El guardado real existe en
     * [AngleMultiFrameSaveCoordinator] y solo se invoca desde el `ViewModel`, que además no lo expone
     * todavía en la pantalla.
     */
    fun finalization(): AngleAnalysisFinalization = coordinator.finalization()

    // ---------------------------------------------------------------- cierre

    /**
     * Cierra la sesión de forma idempotente: invalida las capturas en vuelo (su respuesta se libera,
     * no se adopta), libera las imágenes sin leases, difiere las que tienen leases abiertos hasta su
     * cierre y cierra la fuente en el ejecutor inyectado. No bloquea al llamante.
     */
    override fun close() {
        if (closeRequested.compareAndSet(false, true)) {
            coordinator.close()
        }
    }

    // ---------------------------------------------------------------- interior

    /**
     * Publica una instantánea del coordinador **solo** si su versión es mayor que la ya aplicada.
     *
     * El coordinador puede avisar desde el hilo del ejecutor y desde el llamante, sin orden
     * garantizado; el control por versión es lo que evita que un aviso adelantado retroceda [state].
     */
    private fun publish(snapshot: AngleDraftSessionSnapshot) {
        synchronized(publishLock) {
            if (snapshot.version <= lastPublishedVersion) return
            lastPublishedVersion = snapshot.version
            _state.value = snapshot.toSessionState()
        }
    }

    private fun AngleDraftSessionSnapshot.toSessionState(): AngleMultiFrameSessionState {
        // Nombre explícito para la instantánea: evita que los campos homónimos del coordinador
        // (`generation`, `sourceIdentity`, `isClosed`) se resuelvan contra esta clase.
        val snapshot = this
        return AngleMultiFrameSessionState(
            sourceIdentity = snapshot.sourceIdentity,
            generation = snapshot.generation,
            sourceFrameCount = this@AngleMultiFrameSession.sourceFrameCount,
            frames = snapshot.frames.mapNotNull { frameState ->
                val frame = snapshot.draft.frame(frameState.frameId) ?: return@mapNotNull null
                AngleMultiFrameItem(
                    frame = frame,
                    isActive = snapshot.draft.activeFrameId == frame.id,
                    capture = frameState.capture,
                    leaseCount = frameState.leaseCount,
                    retainedBytes = frameState.retainedBytes,
                )
            },
            activeFrameId = snapshot.draft.activeFrameId,
            retainedBytes = snapshot.retainedBytes,
            reservedCaptureBytes = snapshot.reservedCaptureBytes,
            droppedLateResults = snapshot.droppedLateResults,
            isClosed = snapshot.isClosed,
            finalization = snapshot.draft.finalization(),
            version = snapshot.version,
        )
    }

    private fun route(
        outcome: AngleDraftCoordinatorOutcome,
        current: AngleMultiFrameSessionState,
    ): AngleMultiFrameResult = when (outcome) {
        is AngleDraftCoordinatorOutcome.Applied -> AngleMultiFrameResult.Applied(outcome.snapshot.toSessionState())
        is AngleDraftCoordinatorOutcome.Rejected -> rejected(outcome.rejection.toMultiFrameRejection(current))
    }

    /**
     * Traduce el rechazo del coordinador al de esta fachada.
     *
     * Solo se traduce el caso que la pantalla necesita distinguir para no confundirlo con un error
     * («ese frame ya está capturado»); **todo lo demás se propaga intacto** envuelto en
     * [AngleMultiFrameRejection.Coordinator], incluidos los fallos de captura, el límite de memoria y
     * las capturas en vuelo. No se colapsa ningún motivo en un mensaje genérico.
     */
    private fun AngleDraftCoordinatorRejection.toMultiFrameRejection(
        current: AngleMultiFrameSessionState,
    ): AngleMultiFrameRejection {
        val reason = (this as? AngleDraftCoordinatorRejection.DraftRejected)?.reason
        if (reason is AngleAnalysisDraftRejection.DuplicateFrameAnchor) {
            return AngleMultiFrameRejection.FrameAlreadyTracked(
                frameId = reason.existingFrameId,
                hasImage = current.frame(reason.existingFrameId)?.canEdit == true,
            )
        }
        return AngleMultiFrameRejection.Coordinator(this)
    }

    private fun closedRejection(): AngleMultiFrameRejection =
        AngleMultiFrameRejection.Coordinator(AngleDraftCoordinatorRejection.Closed)

    private fun rejected(rejection: AngleMultiFrameRejection): AngleMultiFrameResult =
        AngleMultiFrameResult.Rejected(rejection)
}

/**
 * Puerto de apertura de la fuente congelada de la sesión multi-fotograma.
 *
 * La implementación de producción copia el vídeo a un snapshot privado, lo sella y lo indexa
 * ([com.openjump.app.video.angles.FrozenFrameSources.openFromUri]): es **E/S bloqueante**, así que el
 * `ViewModel` la invoca siempre fuera del hilo principal ([kotlinx.coroutines.Dispatchers.IO]) y
 * **nunca** en el ejecutor de capturas (que quedaría ocupado por la copia antes de capturar nada).
 *
 * Existe como puerto para que el controlador y el `ViewModel` sean verificables en JVM sin vídeo, sin
 * ficheros y sin `ContentResolver`.
 */
internal fun interface AngleFrozenSourceOpener {

    /**
     * Abre la fuente de [uri]. [cancellationCheck] debe consultarse durante la copia: si el editor se
     * cierra o cambia de generación, la apertura se abandona sin sellar nada.
     */
    fun open(uri: String, cancellationCheck: () -> Boolean): FrozenFrameSourceOpen
}

/** Estado de la sesión multi-fotograma **tal como lo publica el `ViewModel`**. */
internal sealed interface AngleMultiFrameState {

    /** No hay ninguna sesión abierta en esta instancia (estado inicial y tras descartarla). */
    data object NotOpened : AngleMultiFrameState

    /** Copiando, sellando e indexando la fuente fuera del hilo principal; aún no hay fotogramas. */
    data object Opening : AngleMultiFrameState

    /** Sesión viva; [state] puede estar ya cerrada si se descartó con leases abiertos. */
    data class Ready(val state: AngleMultiFrameSessionState) : AngleMultiFrameState

    /** La fuente no se pudo abrir: no hay captura multi-fotograma y no se ha tocado nada guardado. */
    data class Unavailable(val reason: AngleMultiFrameUnavailableReason) : AngleMultiFrameState

    /** El editor se cerró: la instancia no vuelve a abrir una sesión multi-fotograma. */
    data object Closed : AngleMultiFrameState
}

/** Por qué no se pudo abrir la sesión multi-fotograma; nunca se sustituye por una sesión vacía. */
internal sealed interface AngleMultiFrameUnavailableReason {

    /** El editor no tiene ningún vídeo abierto: no hay fuente que sellar. */
    data object NoVideoOpen : AngleMultiFrameUnavailableReason

    /** La instancia no tiene puerto de apertura cableado (construcción sin `AngleFrozenSourceOpener`). */
    data object OpenerUnavailable : AngleMultiFrameUnavailableReason

    /** La fuente se rechazó con su motivo tipado (permiso, espacio, copia, contenido, cancelación…). */
    data class SourceRejected(
        val failure: SourceOpenFailure,
        val detail: String,
    ) : AngleMultiFrameUnavailableReason

    /** La apertura lanzó una excepción inesperada; se informa la clase, no un éxito falso. */
    data class OpenFailed(val errorClass: String) : AngleMultiFrameUnavailableReason
}

/**
 * Instantánea inmutable de la sesión multi-fotograma.
 *
 * [version] es monótona y solo se publican versiones crecientes: es la garantía de que un aviso
 * adelantado del ejecutor no retrocede el estado que la pantalla ya está mostrando.
 */
internal data class AngleMultiFrameSessionState(
    val sourceIdentity: String,
    val generation: Long,
    /** Fotogramas del índice sellado de la fuente; cota de los índices capturables. */
    val sourceFrameCount: Int,
    /** Fotogramas en orden de presentación. */
    val frames: List<AngleMultiFrameItem>,
    val activeFrameId: AngleFrameId?,
    /** Bytes de imágenes congeladas retenidas por la sesión. */
    val retainedBytes: Long,
    /** Peor caso reservado por la captura de sesión en vuelo (cuenta contra el límite). */
    val reservedCaptureBytes: Long,
    /** Resultados de captura descartados por tardíos, cancelados o de un intento invalidado. */
    val droppedLateResults: Long,
    val isClosed: Boolean,
    /** Plan de finalización puro; no implica que exista un escritor de persistencia. */
    val finalization: AngleAnalysisFinalization,
    val version: Long,
) {

    /** Fotograma activo (el único editable), o `null` si no hay selección. */
    val activeFrame: AngleMultiFrameItem?
        get() = activeFrameId?.let(::frame)

    /** Fotograma con ese id, o `null` si no está en la sesión. */
    fun frame(frameId: AngleFrameId): AngleMultiFrameItem? =
        frames.firstOrNull { it.frameId == frameId }

    /** Captura en vuelo, si hay alguna (single-flight de sesión). */
    val capturingFrame: AngleMultiFrameItem?
        get() = frames.firstOrNull { it.capture is AngleDraftCapturePhase.Capturing }
}

/**
 * Vista de un fotograma de la sesión: su contenido puro ([frame]) y el estado de propiedad de sus
 * píxeles.
 *
 * Se apoya en [AngleFrameDraft] en lugar de copiar sus campos: el ancla, la imagen opaca, el editor y
 * la revisión son **los del agregado**, de modo que esta capa no puede divergir de él (por ejemplo en
 * [hasData], que decide si eliminar el fotograma exige confirmación).
 */
internal data class AngleMultiFrameItem(
    val frame: AngleFrameDraft,
    val isActive: Boolean,
    val capture: AngleDraftCapturePhase,
    /** Leases abiertos de sus píxeles; mientras sea > 0 no se recicla su bitmap. */
    val leaseCount: Int,
    val retainedBytes: Long,
) {

    val frameId: AngleFrameId get() = frame.id

    val anchor: AngleFrameAnchor get() = frame.anchor

    /** Editor puro del fotograma: ángulos completos, borrador A/B y selección. */
    val editor: AngleEditorState get() = frame.editor

    /** `true` si borrar este fotograma perdería imagen, ángulos o puntos A/B. */
    val hasData: Boolean get() = frame.hasData

    /**
     * `true` si se puede dibujar y marcar sobre su imagen congelada: la captura está
     * [AngleDraftCapturePhase.Ready] y la imagen está adoptada. Con `false` la geometría se rechaza.
     */
    val canEdit: Boolean
        get() = frame.image != null && capture is AngleDraftCapturePhase.Ready

    /** `true` mientras su captura se está ejecutando en el ejecutor. */
    val isCapturing: Boolean get() = capture is AngleDraftCapturePhase.Capturing

    /** Motivo tipado del último fallo de captura, si lo hubo. */
    val captureFailure: AngleDraftCapturePhase.Failed?
        get() = capture as? AngleDraftCapturePhase.Failed
}

/** Resultado de una operación de la sesión: estado nuevo observado, o rechazo tipado sin cambios. */
internal sealed interface AngleMultiFrameResult {

    /** La operación se aceptó y el estado publicado ya refleja la nueva versión. */
    data class Applied(val state: AngleMultiFrameSessionState) : AngleMultiFrameResult

    /** La operación se rechazó; el estado de la sesión no se modificó. */
    data class Rejected(val rejection: AngleMultiFrameRejection) : AngleMultiFrameResult
}

/** Resultado de pedir prestado el bitmap congelado de un fotograma. */
internal sealed interface AngleMultiFrameBitmapResult {

    data class Granted(val lease: AngleMultiFrameBitmapLease) : AngleMultiFrameBitmapResult

    data class Rejected(val rejection: AngleMultiFrameRejection) : AngleMultiFrameBitmapResult
}

/**
 * Motivos tipados por los que el controlador multi-fotograma rechaza una operación.
 *
 * [Coordinator] envuelve **intactos** los motivos del coordinador (sesión cerrada, fotograma
 * desconocido, captura sin imagen, límite de memoria, captura en vuelo, confirmación de descarte…):
 * ningún fallo de captura o de memoria se convierte en un mensaje genérico ni en un éxito.
 */
internal sealed interface AngleMultiFrameRejection {

    /** El coordinador rechazó la operación con su motivo tipado. */
    data class Coordinator(val rejection: AngleDraftCoordinatorRejection) : AngleMultiFrameRejection

    /** No hay ningún fotograma en pausa y confirmado por el visor ahora mismo. */
    data object FrameNotPaused : AngleMultiFrameRejection

    /** La confirmación del visor es de otra fuente (otro vídeo): aquí no se captura nada. */
    data class ForeignConfirmation(
        val expected: String,
        val received: String,
    ) : AngleMultiFrameRejection

    /** La confirmación pertenece a otra generación de render de la sesión. */
    data class StaleConfirmation(
        val expected: Long,
        val received: Long,
    ) : AngleMultiFrameRejection

    /**
     * El PTS que confirma el visor no es el que el índice sellado asigna a ese frame: no se puede
     * probar que el bitmap congelado sea la imagen que el usuario confirmó, así que no se registra ni
     * se captura nada.
     */
    data class ConfirmationPtsMismatch(
        val frameIndex: Int,
        val confirmedPtsUs: Long,
        val sealedPtsUs: Long,
    ) : AngleMultiFrameRejection

    /**
     * El índice confirmado ya está registrado con su imagen o sin ella. No se recaptura: los píxeles
     * exactos se conservan. Con [hasImage] en `false` el fotograma es reintentable con
     * [AngleMultiFrameSession.captureFrame].
     */
    data class FrameAlreadyTracked(
        val frameId: AngleFrameId,
        val hasImage: Boolean,
    ) : AngleMultiFrameRejection

    /**
     * El fotograma no es el activo: la geometría se marca sobre el bitmap congelado visible, así que
     * primero hay que seleccionarlo.
     */
    data class NotActiveFrame(
        val frameId: AngleFrameId,
        val activeFrameId: AngleFrameId?,
    ) : AngleMultiFrameRejection

    /** El lease concedido no expone un bitmap verificable; se devolvió sin prestar píxeles. */
    data class BitmapUnavailable(val frameId: AngleFrameId) : AngleMultiFrameRejection

    /** Incoherencia interna del contrato (nunca se degrada a éxito ni a un «no pasa nada»). */
    data class InternalInconsistency(val detail: String) : AngleMultiFrameRejection

    /** La instancia no tiene ninguna sesión multi-fotograma abierta (o se descartó). */
    data object SessionNotOpened : AngleMultiFrameRejection

    /** Hay un guardado mono-fotograma en vuelo: no se inicia ni se edita la geometría ahora. */
    data object SaveInFlight : AngleMultiFrameRejection

    /** Hay un guardado multi-fotograma en vuelo: no se muta ni se descarta la sesión ahora. */
    data object MultiFrameSaveInFlight : AngleMultiFrameRejection

    /** Hay una reapertura en vuelo que reemplaza la edición viva. */
    data object ReopenInFlight : AngleMultiFrameRejection
}

/**
 * Préstamo del bitmap congelado **exacto** de un fotograma.
 *
 * [bitmap] es el mismo objeto inmutable que produjo la captura (ya orientado, sin copias ni
 * mutaciones). Es válido **solo** mientras [isOpen]; tras [close] el acceso falla en lugar de devolver
 * píxeles liberados. [imageGeometry] es el sistema de coordenadas de las marcas de ese fotograma:
 * 1 px de imagen congelada = 1 px de geometría (post-rotación), nunca la geometría del vídeo.
 *
 * **Obligaciones del llamante:** cerrarlo (idempotente) en el hilo de dibujo al salir de composición
 * o al dejar de referenciar el bitmap, de modo que no quede ningún dibujo en vuelo; y pedir **otro**
 * lease tras una recreación de la UI (este no se hereda ni se guarda).
 */
internal class AngleMultiFrameBitmapLease internal constructor(
    private val lease: AngleDraftFrameImageLease,
    private val retainer: FrozenFrameImageRetainer,
) : AutoCloseable {

    val frameId: AngleFrameId get() = lease.frameId

    val width: Int get() = lease.width

    val height: Int get() = lease.height

    /** Digest de contenido de la imagen congelada (procedencia de los píxeles prestados). */
    val contentDigest: Long get() = lease.contentDigest

    /** `true` mientras los píxeles estén prestados y no deban reciclarse. */
    val isOpen: Boolean get() = lease.isOpen

    /**
     * Bitmap inmutable de la captura. Tras cerrar el lease falla en lugar de entregar píxeles
     * reciclados.
     */
    val bitmap: Bitmap
        get() {
            check(lease.isOpen) {
                "El lease del fotograma ${lease.frameId.value} ya está cerrado: pide otro lease."
            }
            return retainer.bitmap
        }

    /**
     * Geometría de presentación de la imagen congelada: sus dimensiones post-rotación, que son el
     * sistema de coordenadas de sus marcas. La superficie debe construir su
     * [com.openjump.app.ui.selector.VideoCoordinateMapper] con esta geometría y su propio viewport
     * (`FIT`), nunca con la geometría del vídeo.
     */
    val imageGeometry: VideoPresentationGeometry
        get() = VideoPresentationGeometry(width, height)

    override fun close() {
        lease.close()
    }
}
