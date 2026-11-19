package com.openjump.app.ui.angles

import android.content.Context
import android.graphics.Bitmap
import com.openjump.app.angles.Angle
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFinalizationBlocker
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.FeatureFlags
import com.openjump.app.data.ANGLE_GEOMETRY_VERSION
import com.openjump.app.data.angles.AngleV2SaveBudget
import com.openjump.app.data.angles.AngleV2SaveFrame
import com.openjump.app.data.angles.AngleV2SaveMark
import com.openjump.app.data.angles.AngleV2SaveRejection
import com.openjump.app.data.angles.AngleV2SaveRepository
import com.openjump.app.data.angles.AngleV2SaveRequest
import com.openjump.app.data.angles.AngleV2SaveResult
import com.openjump.app.video.angles.FrozenFrameImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Puente **real** entre la sesión multi-fotograma y el guardado V2
 * ([AngleV2SaveRepository]): instantánea retenida → anotación fuera de pantalla → PNG → almacén
 * privado + transacción de Room.
 *
 * ## Alcance de este slice
 *
 * - **La UI ya está conectada y el gate de producto está encendido solo donde corresponde.** La
 *   pantalla de fotogramas expone la acción de guardar enlazada a
 *   [AngleEditorViewModel.saveMultiFrame] y observa
 *   [AngleEditorViewModel.multiFrameSaveState]; quién decide si esa acción se puede pulsar es
 *   [ANGLE_MULTI_FRAME_SAVE_UI_ENABLED], compilado como `true` en `release` (candidato autorizado
 *   1.3) y en la variante aislada `anglesV2Test`, y `false` en `debug` y `demo`. Con el gate apagado
 *   la interfaz **no** escribe en V2: el botón se muestra deshabilitado con su motivo y ninguna ruta
 *   de la pantalla llama al `ViewModel`.
 *   Nada de este archivo se activa por sí solo.
 * - **La imagen es la misma que el usuario midió.** La instantánea se pide a
 *   [AngleMultiFrameSession.acquireSaveSnapshot], que solo entrega la
 *   [FrozenFrameImage] retenida **bajo lease** para cada fotograma finalizable. Aquí no se
 *   redecodifica, no se recaptura y no se sustituye por otra imagen: el lease es lo que impide que
 *   el coordinador recicle esos píxeles mientras se anotan.
 * - **Todo o nada.** Un rechazo de anotación, un PNG fuera de presupuesto o un fallo del almacén
 *   rechaza el guardado completo; el repositorio es el único que escribe la fila, y solo tras
 *   verificar y sellar cada PNG. Este archivo **no** publica un resultado de éxito antes de que la
 *   transacción V2 termine.
 * - **Sin URI ni rutas privadas hacia arriba.** El resultado solo lleva el id de raíz y el número de
 *   fotogramas; los rechazos son motivos tipados. Las claves de imagen huérfanas que reporta el
 *   repositorio se conservan en el resultado para diagnóstico interno y se emiten en el informe de
 *   [AngleMultiFrameSaveOwnership] (log de la aplicación); nunca se muestran al usuario ni se
 *   interpretan como éxito.
 * - **Hilos.** La anotación, la compresión PNG y el guardado se ejecutan en [ioDispatcher]; nunca en
 *   el hilo principal. La cancelación se observa entre fotogramas y el repositorio la traduce a
 *   [AngleV2SaveRejection.Canceled]. Los leases se cierran **siempre** en `finally`.
 * - **Compresión acotada.** Cada PNG se comprime en un flujo con límite ([maxPngBytesPerFrame]) y el
 *   total se contrasta con [AngleV2SaveBudget] **antes** de llamar al repositorio; un PNG mayor se
 *   rechaza con motivo tipado en lugar de reservar memoria sin cota.
 */
internal class AngleMultiFrameSaveCoordinator(
    private val repository: AngleV2SaveRepository,
    private val renderer: AngleMultiFrameImageRenderer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val budget: AngleV2SaveBudget = AngleV2SaveBudget(),
) {

    /**
     * Guarda la [snapshot] como análisis V2 nuevo bajo [sessionKey].
     *
     * @param athleteId dueño real del análisis (el de la petición de apertura del editor); `null` no
     *   se acepta: un guardado nuevo debe llevar dueño.
     *
     * La instantánea se cierra al salir (con éxito, rechazo o cancelación). No lanza por causas
     * esperables: devuelve [AngleMultiFrameSaveOutcome.Rejected] con el motivo tipado. Solo propaga
     * la cancelación de la corrutina llamante (y en ese caso no se ha publicado ninguna fila).
     */
    suspend fun save(
        snapshot: AngleMultiFrameSaveSnapshot,
        sessionKey: String,
        athleteId: Long?,
        /**
         * Última puerta **antes de publicar** ninguna imagen (y antes de renderizar, que es lo
         * caro). El dueño de aplicación comprueba aquí si el editor que pidió el guardado ya se
         * cerró: entonces se rechaza sin publicar un byte en vez de completar un trabajo que nadie
         * va a mostrar. Después de esta puerta el tramo es de quien lo posee y no se abandona.
         */
        shouldAbortBeforePublish: () -> Boolean = { false },
    ): AngleMultiFrameSaveOutcome {
        currentCoroutineContext().ensureActive()
        if (sessionKey.isBlank()) {
            snapshot.close()
            return AngleMultiFrameSaveOutcome.Rejected(
                AngleMultiFrameSaveRejection.InvalidSession("La sessionKey no puede estar vacía."),
            )
        }
        if (athleteId == null || athleteId <= 0L) {
            snapshot.close()
            return AngleMultiFrameSaveOutcome.Rejected(
                AngleMultiFrameSaveRejection.InvalidSession("Un guardado nuevo exige un dueño real."),
            )
        }
        if (snapshot.frames.isEmpty()) {
            snapshot.close()
            return AngleMultiFrameSaveOutcome.Rejected(
                AngleMultiFrameSaveRejection.NotFinalizable(listOf(AngleFinalizationBlocker.NoFrames)),
            )
        }
        return try {
            withContext(ioDispatcher) {
                saveLocked(snapshot, sessionKey, athleteId, shouldAbortBeforePublish)
            }
        } finally {
            // Los leases se sueltan aquí pase lo que pase: la sesión no debe quedarse con píxeles
            // prestados por un guardado que ya terminó.
            snapshot.close()
        }
    }

    private suspend fun saveLocked(
        snapshot: AngleMultiFrameSaveSnapshot,
        sessionKey: String,
        athleteId: Long,
        shouldAbortBeforePublish: () -> Boolean,
    ): AngleMultiFrameSaveOutcome {
        // Cierre del editor antes de publicar: no se paga ni el render de los fotogramas.
        if (shouldAbortBeforePublish()) {
            return AngleMultiFrameSaveOutcome.Rejected(AngleMultiFrameSaveRejection.SessionClosed)
        }
        val frames = ArrayList<AngleV2SaveFrame>(snapshot.frames.size)
        var totalPngBytes = 0L
        snapshot.frames.forEach { frame ->
            currentCoroutineContext().ensureActive()
            val image = frame.image
            // Estrechado explicito: el compilador no convierte `rendered` al caso `Rendered` tras
            // el `if (rendered is ...Rejected) return`, asi que `pngBytes` no resolvia y
            // `saveFrameOf` recibia el tipo sellado completo. Mismo control de flujo: un rechazo
            // de render aborta el guardado entero con su motivo tipado.
            val rendered = when (val outcome = renderer.render(image, frame.editor)) {
                is AngleMultiFrameRenderedImage.Rejected -> return AngleMultiFrameSaveOutcome.Rejected(
                    AngleMultiFrameSaveRejection.RenderRejected(frame.frameId, outcome.rejection),
                )

                is AngleMultiFrameRenderedImage.Rendered -> outcome
            }
            totalPngBytes += rendered.pngBytes.size.toLong()
            if (frames.size + 1 > budget.maxFrames || totalPngBytes > budget.maxTotalImageBytes) {
                return AngleMultiFrameSaveOutcome.Rejected(
                    AngleMultiFrameSaveRejection.BudgetExceeded(
                        frames = frames.size + 1,
                        maxFrames = budget.maxFrames,
                        declaredBytes = totalPngBytes,
                        maxTotalImageBytes = budget.maxTotalImageBytes,
                    ),
                )
            }
            frames += saveFrameOf(frame, image, rendered)
        }
        val request = AngleV2SaveRequest(
            sessionKey = sessionKey,
            athleteId = athleteId,
            frames = frames,
        )
        request.validate()?.let { detail ->
            // La petición la compone esta capa: un rechazo de forma aquí es una incoherencia, no una
            // entrada inválida del usuario. Se informa como tal y no se escribe nada.
            return AngleMultiFrameSaveOutcome.Rejected(
                AngleMultiFrameSaveRejection.InternalInconsistency(detail),
            )
        }
        // Última salida sin imágenes: a partir de aquí el repositorio publica PNGs y confirma la
        // transacción, así que el guardado ya no se abandona (su dueño informa el resultado).
        if (shouldAbortBeforePublish()) {
            return AngleMultiFrameSaveOutcome.Rejected(AngleMultiFrameSaveRejection.SessionClosed)
        }
        return when (val result = repository.save(request)) {
            is AngleV2SaveResult.Saved -> AngleMultiFrameSaveOutcome.Saved(
                rootId = result.rootId,
                frameCount = frames.size,
            )

            is AngleV2SaveResult.AlreadySaved -> AngleMultiFrameSaveOutcome.AlreadySaved(
                rootId = result.rootId,
                orphanedImageKeys = result.orphanedImageKeys,
            )

            is AngleV2SaveResult.Rejected -> AngleMultiFrameSaveOutcome.Rejected(
                reason = AngleMultiFrameSaveRejection.StoreRejected(
                    reason = result.reason,
                    orphanedImageKeys = result.orphanedImageKeys,
                ),
                // También en el resultado: una clave publicada sin fila no se pierde por estar
                // anidada en el motivo, y su recogida futura no depende de mirar dentro de él.
                orphanedImageKeys = result.orphanedImageKeys,
            )
        }
    }

    /**
     * Metadatos **reales** del fotograma anotado. Los índices/PTS asignados salen del ancla que acuñó
     * el índice sellado; los reportados, de la procedencia de la imagen congelada. Las dimensiones
     * presentadas son las de la imagen congelada (sistema de coordenadas de las marcas) y la rotación
     * es la que realmente se aplicó al bitmap, no una declaración de la UI.
     */
    private fun saveFrameOf(
        frame: AngleMultiFrameSaveFrame,
        image: FrozenFrameImage,
        rendered: AngleMultiFrameRenderedImage.Rendered,
    ): AngleV2SaveFrame {
        val provenance = image.provenance
        return AngleV2SaveFrame(
            assignedFrameIndex = frame.anchor.frameIndex,
            reportedFrameIndex = provenance.frameIndex,
            assignedPtsUs = frame.anchor.sourcePtsUs,
            reportedPtsUs = provenance.decoderReportedOutputPtsUs,
            encodedWidth = provenance.encodedWidth,
            encodedHeight = provenance.encodedHeight,
            presentationWidth = provenance.frozenWidth,
            presentationHeight = provenance.frozenHeight,
            rotationDegrees = provenance.rotationAppliedDegrees,
            geometryVersion = ANGLE_GEOMETRY_VERSION,
            pngBytes = rendered.pngBytes,
            imageWidth = rendered.width,
            imageHeight = rendered.height,
            // Se guardan todos los ángulos, también los ocultos: ocultar en la UI no puede perder
            // geometría ya medida.
            marks = frame.editor.angles.map { angle ->
                AngleV2SaveMark(
                    name = angle.name,
                    colorArgb = angle.colorArgb,
                    visible = angle.visible,
                    pointAX = angle.markers.a.x,
                    pointAY = angle.markers.a.y,
                    pointBX = angle.markers.b.x,
                    pointBY = angle.markers.b.y,
                    pointCX = angle.markers.c.x,
                    pointCY = angle.markers.c.y,
                )
            },
        )
    }
}

/**
 * Lease de la [FrozenFrameImage] real de un fotograma, con la garantía del lease del coordinador.
 *
 * Se crea solo desde [AngleMultiFrameSession.acquireSaveSnapshot], con un
 * [FrozenFrameImageRetainer] ya verificado. [image] falla tras [close] en lugar de devolver una
 * imagen que ya no está retenida.
 */
internal class AngleMultiFrameFrozenImageLease internal constructor(
    private val lease: AngleDraftFrameImageLease,
    private val retainer: FrozenFrameImageRetainer,
) : AutoCloseable {

    val frameId: AngleFrameId get() = lease.frameId

    val isOpen: Boolean get() = lease.isOpen

    /** Imagen congelada exacta retenida; solo válida mientras [isOpen]. */
    val image: FrozenFrameImage
        get() {
            check(lease.isOpen) {
                "El lease del fotograma ${lease.frameId.value} ya está cerrado: no hay píxeles retenidos."
            }
            return retainer.frozenImage
        }

    override fun close() {
        lease.close()
    }
}

/** Un fotograma de la instantánea de guardado: imagen retenida bajo lease + geometría + ancla. */
internal class AngleMultiFrameSaveFrame internal constructor(
    val frameId: AngleFrameId,
    val anchor: AngleFrameAnchor,
    val editor: AngleEditorState,
    private val lease: AngleMultiFrameFrozenImageLease,
) : AutoCloseable {

    /** Imagen congelada exacta que se anota; válida solo mientras la instantánea siga abierta. */
    val image: FrozenFrameImage get() = lease.image

    override fun close() {
        lease.close()
    }
}

/**
 * Instantánea inmutable de lo que se va a guardar, con los píxeles retenidos bajo lease.
 *
 * [close] es idempotente y suelta **todos** los leases. Debe cerrarse siempre: mientras siga abierta,
 * la sesión no recicla esos bitmaps.
 */
internal class AngleMultiFrameSaveSnapshot internal constructor(
    val sourceIdentity: String,
    val generation: Long,
    val frames: List<AngleMultiFrameSaveFrame>,
) : AutoCloseable {

    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) {
            frames.forEach { it.close() }
        }
    }
}

/** Resultado de pedir la instantánea de guardado a la sesión. */
internal sealed interface AngleMultiFrameSaveSnapshotResult {

    data class Captured(val snapshot: AngleMultiFrameSaveSnapshot) : AngleMultiFrameSaveSnapshotResult

    data class Rejected(val reason: AngleMultiFrameSaveRejection) : AngleMultiFrameSaveSnapshotResult
}

/** Resultado real de un guardado multi-fotograma. Nunca se informa éxito antes de la transacción. */
internal sealed interface AngleMultiFrameSaveOutcome {

    /** Raíz V2 insertada con [frameCount] fotogramas: la transacción ya terminó. */
    data class Saved(val rootId: Long, val frameCount: Int) : AngleMultiFrameSaveOutcome

    /**
     * La `sessionKey` ya estaba guardada con **contenido idéntico**: no se insertó nada.
     * [orphanedImageKeys] solo puede traer claves si la carrera contra otro guardado las dejó sin fila
     * (se conservan; no se borran aquí).
     */
    data class AlreadySaved(
        val rootId: Long,
        val orphanedImageKeys: List<String> = emptyList(),
    ) : AngleMultiFrameSaveOutcome

    /** No se guardó: [reason] es el motivo tipado; [orphanedImageKeys] solo para diagnóstico interno. */
    data class Rejected(
        val reason: AngleMultiFrameSaveRejection,
        val orphanedImageKeys: List<String> = emptyList(),
    ) : AngleMultiFrameSaveOutcome
}

/** Motivos tipados de un guardado multi-fotograma rechazado (sin rutas ni URIs). */
internal sealed interface AngleMultiFrameSaveRejection {

    /** No hay sesión multi-fotograma viva en la instancia. */
    data object SessionNotOpened : AngleMultiFrameSaveRejection

    /** La sesión (o el editor que pidió el guardado) se cerró, o el intento se canceló antes de
     * publicar ninguna imagen. En ambos casos no se ha escrito nada verificable en V2. */
    data object SessionClosed : AngleMultiFrameSaveRejection

    /** Ya hay un guardado multi-fotograma en vuelo (single-flight). */
    data object SaveInFlight : AngleMultiFrameSaveRejection

    /** La instancia no tiene repositorio de guardado V2 cableado. */
    data object SaveNotWired : AngleMultiFrameSaveRejection

    /** El plan de finalización no está listo; [blockers] explica cada fotograma. */
    data class NotFinalizable(val blockers: List<AngleFinalizationBlocker>) : AngleMultiFrameSaveRejection

    /** Un fotograma finalizable no tiene imagen retenida que se pueda prestar. */
    data class ImageNotRetained(val frameId: AngleFrameId) : AngleMultiFrameSaveRejection

    /** La anotación de un fotograma se rechazó con su motivo (no se guarda nada parcial). */
    data class RenderRejected(
        val frameId: AngleFrameId,
        val rejection: AngleMultiFrameRenderRejection,
    ) : AngleMultiFrameSaveRejection

    /** El análisis supera el presupuesto agregado antes de publicar el primer PNG. */
    data class BudgetExceeded(
        val frames: Int,
        val maxFrames: Int,
        val declaredBytes: Long,
        val maxTotalImageBytes: Long,
    ) : AngleMultiFrameSaveRejection

    /** La sesión no es persistible (clave vacía o dueño ausente). */
    data class InvalidSession(val detail: String) : AngleMultiFrameSaveRejection

    /** El repositorio V2 rechazó el guardado; [reason] es su motivo tipado. */
    data class StoreRejected(
        val reason: AngleV2SaveRejection,
        val orphanedImageKeys: List<String>,
    ) : AngleMultiFrameSaveRejection

    /** Incoherencia interna (nunca se degrada a éxito). */
    data class InternalInconsistency(val detail: String) : AngleMultiFrameSaveRejection
}

/**
 * Claves de imagen que el almacén publicó y que **ninguna fila** referencia, ya consolidadas en el
 * resultado (incluidas las que el repositorio anida dentro de [AngleMultiFrameSaveRejection.StoreRejected]).
 *
 * Este código no las borra ni las reutiliza: solo las conserva y las informa para una recogida
 * futura.
 */
internal fun AngleMultiFrameSaveOutcome.orphanedImageKeys(): List<String> = when (this) {
    is AngleMultiFrameSaveOutcome.Saved -> emptyList()
    is AngleMultiFrameSaveOutcome.AlreadySaved -> orphanedImageKeys
    is AngleMultiFrameSaveOutcome.Rejected ->
        (orphanedImageKeys + (reason as? AngleMultiFrameSaveRejection.StoreRejected)
            ?.orphanedImageKeys.orEmpty()).distinct()
}

/**
 * Informe de un guardado multi-fotograma ya terminado, tal como lo registra el dueño de aplicación
 * ([AngleMultiFrameSaveOwnership]).
 *
 * Existe porque el resultado de un guardado que sobrevive al cierre del editor no puede depender de
 * que la pantalla siga viva: [sessionKey] lo correlaciona con el intento y [orphanedImageKeys]
 * conserva las claves publicadas sin fila. **No** es un borrador persistido: no se escribe en disco
 * ni sobrevive a la muerte del proceso; es un registro de diagnóstico en memoria más el sumidero
 * externo que cablea la aplicación.
 */
internal data class AngleMultiFrameSaveReport(
    val sessionKey: String,
    val outcome: AngleMultiFrameSaveOutcome,
) {

    /** Claves publicadas sin fila por este intento; nunca se borran aquí. */
    val orphanedImageKeys: List<String> get() = outcome.orphanedImageKeys()

    /**
     * Resumen de diagnóstico en una línea: motivo tipado (sin URIs ni rutas), id de raíz si lo hay y
     * las claves huérfanas. Lo consume el registro de la aplicación, no es texto de interfaz.
     */
    fun diagnosticSummary(): String {
        val detail = when (val current = outcome) {
            is AngleMultiFrameSaveOutcome.Saved ->
                "saved rootId=${current.rootId} frames=${current.frameCount}"

            is AngleMultiFrameSaveOutcome.AlreadySaved ->
                "already-saved rootId=${current.rootId}"

            is AngleMultiFrameSaveOutcome.Rejected -> {
                // Se usa el nombre de la clase del motivo en lugar de un `when` exhaustivo: este
                // resumen es de diagnóstico y no debe romper la compilación al añadir un motivo.
                val store = current.reason as? AngleMultiFrameSaveRejection.StoreRejected
                val reason = store?.reason?.let { "StoreRejected(${it::class.simpleName})" }
                    ?: (current.reason::class.simpleName ?: "Rejected")
                "rejected reason=$reason"
            }
        }
        val orphaned = orphanedImageKeys
        return "sessionKey=$sessionKey $detail orphaned=${orphaned.size}" +
            if (orphaned.isEmpty()) "" else " orphanedKeys=${orphaned.joinToString(",")}"
    }
}

/**
 * Dueño **de aplicación** de un guardado multi-fotograma ya iniciado.
 *
 * **Por qué existe.** `withContext(NonCancellable)` protege la durabilidad del tramo (publicación de
 * PNG + transacción), pero **no entrega el valor**: la reanudación pertenece al `Job` del llamante y
 * un `Job` cancelado descarta el resultado y relanza `CancellationException`. Si el guardado corriera
 * en `viewModelScope`, cerrar el editor a mitad destruiría el único reporte del intento: la fila
 * podría existir mientras nadie lo sabe, y las claves de imagen publicadas sin fila (huérfanas) se
 * perderían sin dejar rastro. Aquí el guardado corre en el ámbito de aplicación y el resultado
 * tipado se entrega con un callback que no depende de que el ViewModel siga vivo.
 *
 * **Propiedad de la instantánea.** [start] devuelve `true` solo si la instantánea (y sus leases) pasó
 * al guardado, que la cierra exactamente una vez; con `false` el llamante conserva la propiedad y
 * debe cerrarla. Una vez arrancado, ni la cancelación del ámbito ni la destrucción del ViewModel
 * interrumpen el tramo durable ni pierden el resultado.
 *
 * **Cómo se informa de verdad.** Cada intento terminado se registra como
 * [AngleMultiFrameSaveReport] y se **emite** (a) al sumidero externo [reporter], que en producción
 * escribe el log de diagnóstico de la aplicación, y (b) a una cola acotada de proceso
 * ([drainReports]) que un consumidor posterior puede recoger una sola vez. Sin ese sumidero las
 * claves huérfanas y el resultado quedarían en campos que nadie lee.
 *
 * **Lo que no hace.** No recolecta ni borra imágenes: las claves huérfanas se acumulan en
 * [orphanedImageKeys] (solo lectura) para su futura recogida. Si el proceso muere, esas claves siguen
 * en disco sin fila, igual que hoy; este objeto no puede prometer nada tras una muerte del proceso
 * (no persiste el informe ni ningún borrador: la cola y el último resultado son memoria de proceso).
 */
internal class AngleMultiFrameSaveOwnership(
    /**
     * Ámbito de aplicación; `null` (cableado de test sin dueño de aplicación) hace que [start]
     * devuelva `false` y el llamante ejecute el guardado por su cuenta.
     */
    private val scope: CoroutineScope? = null,
    /**
     * Sumidero **real** del informe (en producción, el log de diagnóstico de la aplicación).
     *
     * Es lo que hace recuperable un resultado que sobrevive al cierre del editor: se invoca una vez
     * por intento terminado, fuera del candado y después de registrarlo. Un fallo del sumidero no
     * altera el resultado del guardado.
     */
    private val reporter: ((AngleMultiFrameSaveReport) -> Unit)? = null,
) {

    private val lock = Any()
    private val recordedOrphanedKeys = LinkedHashSet<String>()
    private val unconsumedReports = ArrayList<AngleMultiFrameSaveReport>()
    private var completed: AngleMultiFrameSaveOutcome? = null

    /** Claves publicadas sin fila conocidas por este proceso. Solo lectura; no se borra ninguna. */
    val orphanedImageKeys: List<String> get() = synchronized(lock) { recordedOrphanedKeys.toList() }

    /**
     * Último resultado terminado (incluye rechazos); `null` mientras no haya terminado ninguno.
     *
     * Es «el último», no «el único»: dos intentos pueden terminar en este proceso y los informes de
     * ambos se conservan en la cola hasta que alguien la consuma con [drainReports].
     */
    val lastCompletedOutcome: AngleMultiFrameSaveOutcome?
        get() = synchronized(lock) { completed }

    /**
     * Consume los informes pendientes una sola vez, para que un lector posterior pueda informarlos
     * sin duplicarlos. No borra ninguna imagen ni ninguna clave: solo vacía la cola de reportes.
     */
    fun drainReports(): List<AngleMultiFrameSaveReport> = synchronized(lock) {
        val drained = unconsumedReports.toList()
        unconsumedReports.clear()
        drained
    }

    /**
     * Arranca el guardado como operación de aplicación y entrega su resultado a [onFinished].
     *
     * @return `true` si la operación arrancó y es dueña de [snapshot]; `false` si no hay ámbito
     *   utilizable (no se tocó nada y el llamante conserva la instantánea).
     */
    fun start(
        coordinator: AngleMultiFrameSaveCoordinator,
        snapshot: AngleMultiFrameSaveSnapshot,
        sessionKey: String,
        athleteId: Long?,
        shouldAbortBeforePublish: () -> Boolean,
        onFinished: (AngleMultiFrameSaveOutcome) -> Unit,
    ): Boolean {
        val scope = scope ?: return false
        val scopeJob = scope.coroutineContext[Job]
        if (scopeJob != null && !scopeJob.isActive) return false
        // ATOMIC: si el ámbito se cancela justo después de la comprobación, el cuerpo arranca igual
        // y termina el tramo no cancelable; no se queda una instantánea sin dueño.
        scope.launch(start = CoroutineStart.ATOMIC) {
            // El resultado se captura **dentro** del tramo no cancelable: si la reanudación de
            // `withContext` se perdiera porque el ámbito ya estaba cancelado, no se inventa ni un
            // éxito ni un "no se escribió nada".
            var finished: AngleMultiFrameSaveOutcome? = null
            try {
                withContext(NonCancellable) {
                    coordinator.save(
                        snapshot = snapshot,
                        sessionKey = sessionKey,
                        athleteId = athleteId,
                        shouldAbortBeforePublish = shouldAbortBeforePublish,
                    ).also { finished = it }
                }
            } catch (_: CancellationException) {
                // Sin resultado verificable: nunca se degrada a éxito ni a "nada se publicó".
            } catch (failure: Exception) {
                finished = AngleMultiFrameSaveOutcome.Rejected(
                    AngleMultiFrameSaveRejection.InternalInconsistency(failure.javaClass.simpleName),
                )
            }
            val outcome = finished ?: AngleMultiFrameSaveOutcome.Rejected(
                AngleMultiFrameSaveRejection.InternalInconsistency(INTERRUPTED_SAVE_DETAIL),
            )
            val report = AngleMultiFrameSaveReport(sessionKey = sessionKey, outcome = outcome)
            synchronized(lock) {
                // El informe se registra ANTES de emitirlo: la recogida futura no depende de que el
                // llamante siga vivo ni de que sepa interpretar el motivo.
                recordedOrphanedKeys += report.orphanedImageKeys
                completed = outcome
                unconsumedReports.add(report)
                while (unconsumedReports.size > MAX_PENDING_REPORTS) unconsumedReports.removeAt(0)
            }
            // Emisión externa: es la que mantiene recuperable el resultado cuando el editor ya no
            // existe. Fuera del candado (el sumidero no debe bloquear otro intento) y sin poder
            // cambiar el resultado: el informe ya está registrado arriba.
            try {
                reporter?.invoke(report)
            } catch (_: RuntimeException) {
                // El informe sigue en la cola; el sumidero no decide el resultado del guardado.
            }
            onFinished(outcome)
        }
        return true
    }

    private companion object {
        const val INTERRUPTED_SAVE_DETAIL: String =
            "El guardado se interrumpió sin un resultado verificable; no se afirma que no se escribiera."

        /**
         * Cota de informes retenidos en memoria. Al superarla se descarta el más antiguo: son
         * reportes de diagnóstico, no datos de usuario, y acumularlos sin cota sería una fuga.
         */
        const val MAX_PENDING_REPORTS: Int = 8
    }
}

/**
 * Puerta **de producto** que decide si la interfaz ofrece el guardado multi-fotograma.
 *
 * **ÚNICO punto de activación.** Matriz por variante: `true` en `release` (candidato autorizado
 * 1.3, distribución todavía condicionada) y en la variante aislada `anglesV2Test`; `false` en
 * `debug` y `demo`. Mientras valga `false`:
 * - la pantalla de fotogramas muestra la acción de guardar **deshabilitada** con el motivo, y
 * - ninguna ruta de UI invoca [AngleEditorViewModel.saveMultiFrame], así que la interfaz no puede
 *   escribir filas en V2 por sí sola.
 *
 * El puente ([AngleMultiFrameSaveCoordinator]), el repositorio [AngleV2SaveRepository] y
 * [AngleEditorViewModel.saveMultiFrame] existen y se prueban por separado; esta constante no los
 * debilita, solo controla lo que se ofrece al usuario.
 *
 * Para distribuir el candidato con el gate encendido hay que haber verificado **antes** (y no se
 * puede dar por hecho con el gate en `true`; en este slice siguen pendientes):
 * 1. backup/restore manual **con imágenes** (manifiesto, PNG y transacción) verificado de extremo a
 *    extremo en dispositivo, no solo en pruebas JVM/instrumentadas; la prueba manual de backup y
 *    restauración de extremo a extremo, con contenido de imagen solo de datos de prueba seguros,
 *    queda pendiente antes de distribuir;
 * 2. que el guardado V2 sea el único escritor y que restaurar un backup no deje raíces V2 sin sus
 *    imágenes;
 * 3. el gate de [AngleV2SaveRepository] (presupuesto, sellado y verificación de cada PNG) verde en
 *    las pruebas finales del usuario.
 *
 * El valor no se decide aquí: se lee de [FeatureFlags.angleMultiFrameSaveEnabled], que depende del
 * campo `BuildConfig.ANGLE_MULTI_FRAME_SAVE_UI_ENABLED` generado en `app/build.gradle.kts`
 * (`false` en `defaultConfig`; `release` lo enciende explícitamente y `anglesV2Test` también).
 *
 * **Producto normal:** `debug` y `demo` apagados; `release` encendido para el candidato autorizado.
 * La variante aislada `anglesV2Test` (aplicación distinta `com.openjump.app.anglesv2test`) también
 * lo compila como `true`, para una prueba manual autorizada por el usuario. Que cualquiera de las
 * dos variantes lo encienda **no** satisface ni sustituye los puntos 1–3 anteriores: no es
 * evidencia de backup/restore verificado, ni de unicidad de escritor, ni de gates de release
 * superados.
 */
internal val ANGLE_MULTI_FRAME_SAVE_UI_ENABLED: Boolean = FeatureFlags.angleMultiFrameSaveEnabled

/** Estado del guardado multi-fotograma tal como lo publica el `ViewModel`. */
internal sealed interface AngleMultiFrameSaveState {

    /** No hay ningún guardado en curso ni resultado que mostrar. */
    data object Idle : AngleMultiFrameSaveState

    /** Guardado en vuelo: las mutaciones de la sesión están bloqueadas. */
    data object Saving : AngleMultiFrameSaveState

    /** Transacción terminada: la raíz existe con [frameCount] fotogramas. */
    data class Saved(val rootId: Long, val frameCount: Int) : AngleMultiFrameSaveState

    /** La raíz ya existía con el mismo contenido; no se insertó nada. */
    data class AlreadySaved(val rootId: Long) : AngleMultiFrameSaveState

    /** No se guardó; [reason] es tipado y no contiene rutas ni URIs. */
    data class Failed(val reason: AngleMultiFrameSaveRejection) : AngleMultiFrameSaveState
}

/** Renderiza y comprime un fotograma ya retenido; separado para poder sustituirlo en pruebas. */
internal fun interface AngleMultiFrameImageRenderer {

    /** No debe invocarse en el hilo principal: copia el bitmap y comprime PNG. */
    fun render(image: FrozenFrameImage, state: AngleEditorState): AngleMultiFrameRenderedImage
}

/** Resultado del render: PNG de la copia anotada, o rechazo tipado sin imagen parcial. */
internal sealed interface AngleMultiFrameRenderedImage {

    data class Rendered(
        val width: Int,
        val height: Int,
        val pngBytes: ByteArray,
    ) : AngleMultiFrameRenderedImage

    data class Rejected(val rejection: AngleMultiFrameRenderRejection) : AngleMultiFrameRenderedImage
}

/** Motivo de rechazo del render/compresión de un fotograma. */
internal sealed interface AngleMultiFrameRenderRejection {

    /** [planAngleAnnotation] o el rasterizado rechazaron la anotación. */
    data class Annotation(
        val reason: AngleAnnotationRejectionReason,
        val detail: String,
    ) : AngleMultiFrameRenderRejection

    /** El codificador PNG falló o no pudo producir bytes. */
    data object PngEncodeFailed : AngleMultiFrameRenderRejection

    /** El PNG supera el límite acotado del flujo de compresión. */
    data class PngTooLarge(val limitBytes: Int) : AngleMultiFrameRenderRejection

    /** Las dimensiones de la copia no son presupuestables. */
    data object DimensionsUnavailable : AngleMultiFrameRenderRejection
}

/**
 * Render de producción: copia anotada con [AngleAnnotationRenderer] y PNG en un flujo acotado.
 *
 * No decide producto: la etiqueta y los glifos se inyectan (los textos localizados los aporta el
 * punto de composición), la selección se pasa siempre `null` (una imagen guardada no resalta nada) y
 * el presupuesto de la copia se deriva de las dimensiones reales de la imagen congelada.
 */
internal class AndroidAngleMultiFrameImageRenderer(
    private val context: Context,
    private val metrics: AngleAnnotationMetrics,
    private val formatLabel: (Angle, Double) -> String,
    private val markerLabel: (AngleMarker) -> String = { it.name },
    private val maxPngBytesPerFrame: Int = DEFAULT_MAX_PNG_BYTES_PER_FRAME,
) : AngleMultiFrameImageRenderer {

    init {
        require(maxPngBytesPerFrame > 0) { "El límite de PNG por fotograma debe ser positivo." }
    }

    override fun render(
        image: FrozenFrameImage,
        state: AngleEditorState,
    ): AngleMultiFrameRenderedImage {
        val width = image.width
        val height = image.height
        if (width < 1 || height < 1) {
            return AngleMultiFrameRenderedImage.Rejected(
                AngleMultiFrameRenderRejection.DimensionsUnavailable,
            )
        }
        // Misma envolvente que la anotación: copia ARGB_8888 + una fila de trabajo.
        val requiredBytes = width.toLong() * height.toLong() * 4L + maxOf(width, height).toLong() * 4L
        val request = AngleAnnotationRequest(
            image = image,
            state = state,
            // Una imagen guardada no conserva el resaltado del editor: no se resalta ningún ángulo.
            selectedAngleId = null,
            metrics = metrics,
            budgetBytes = requiredBytes,
        )
        val annotated = when (
            val outcome = AngleAnnotationRenderer.render(context, request, formatLabel, markerLabel)
        ) {
            is AngleAnnotationResult.Rejected -> return AngleMultiFrameRenderedImage.Rejected(
                AngleMultiFrameRenderRejection.Annotation(outcome.reason, outcome.detail),
            )

            is AngleAnnotationResult.Rendered -> outcome.image
        }
        // La copia anotada es propiedad de esta función: se cierra siempre, con éxito o no.
        return try {
            when (val encoded = encodePng(annotated.bitmap)) {
                is PngEncodeOutcome.Encoded -> AngleMultiFrameRenderedImage.Rendered(
                    width = annotated.width,
                    height = annotated.height,
                    pngBytes = encoded.bytes,
                )

                PngEncodeOutcome.TooLarge -> AngleMultiFrameRenderedImage.Rejected(
                    AngleMultiFrameRenderRejection.PngTooLarge(maxPngBytesPerFrame),
                )

                PngEncodeOutcome.Failed -> AngleMultiFrameRenderedImage.Rejected(
                    AngleMultiFrameRenderRejection.PngEncodeFailed,
                )
            }
        } finally {
            annotated.close()
        }
    }

    private fun encodePng(bitmap: Bitmap): PngEncodeOutcome {
        val stream = BoundedPngOutputStream(maxPngBytesPerFrame)
        return try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                PngEncodeOutcome.Failed
            } else {
                PngEncodeOutcome.Encoded(stream.toByteArray())
            }
        } catch (_: PngBoundExceeded) {
            PngEncodeOutcome.TooLarge
        } catch (_: IOException) {
            PngEncodeOutcome.Failed
        } catch (_: RuntimeException) {
            // `Bitmap.compress` puede propagar fallos del flujo como RuntimeException; se informa
            // como rechazo tipado en lugar de dejar escapar un error de codificación.
            PngEncodeOutcome.Failed
        }
    }

    private companion object {
        /**
         * Cota por fotograma del PNG comprimido: 48 MiB. Con la envolvente de área de la app (una
         * imagen 8K ARGB son ~128 MiB) el peor caso de un PNG incompresible sigue por debajo, y el
         * total real lo acota además [AngleV2SaveBudget.maxTotalImageBytes].
         */
        const val DEFAULT_MAX_PNG_BYTES_PER_FRAME: Int = 48 * 1024 * 1024
    }
}

private sealed interface PngEncodeOutcome {

    class Encoded(val bytes: ByteArray) : PngEncodeOutcome

    data object TooLarge : PngEncodeOutcome

    data object Failed : PngEncodeOutcome
}

/** Excepción de control del flujo acotado: la salida supera [BoundedPngOutputStream.limitBytes]. */
private class PngBoundExceeded : IOException("La salida PNG supera el límite acotado.")

/**
 * Flujo de salida en memoria con límite duro: al superar [limitBytes] no se sigue acumulando.
 *
 * `ByteArrayOutputStream` crecería sin cota si el PNG fuera grande; este flujo corta la compresión
 * antes de que el proceso tenga que reservar memoria desmedida y se traduce a
 * [AngleMultiFrameRenderRejection.PngTooLarge].
 */
private class BoundedPngOutputStream(private val limitBytes: Int) : OutputStream() {

    private val delegate = ByteArrayOutputStream(minOf(limitBytes, INITIAL_CAPACITY_BYTES))

    override fun write(b: Int) {
        ensureCapacity(1)
        delegate.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensureCapacity(len)
        delegate.write(b, off, len)
    }

    override fun flush() = Unit

    override fun close() = Unit

    fun toByteArray(): ByteArray = delegate.toByteArray()

    private fun ensureCapacity(incoming: Int) {
        if (incoming < 0 || delegate.size().toLong() + incoming.toLong() > limitBytes.toLong()) {
            throw PngBoundExceeded()
        }
    }

    private companion object {
        const val INITIAL_CAPACITY_BYTES = 64 * 1024
    }
}

/**
 * `sessionKey` del guardado multi-fotograma derivada de la clave real de la sesión de edición y del
 * **intento** de ese borrador ([attempt]).
 *
 * Se añade un sufijo propio para no colisionar con la raíz del flujo mono-fotograma (que usa la
 * clave base). La identidad es **determinista a partir de la clave base**, que la ruta del editor
 * acuña una sola vez por análisis y conserva ante recreación y muerte del proceso
 * (`rememberSaveable`):
 *
 * - **Reintento del mismo borrador** (incluido el que ocurre tras destruirse el `ViewModel`): deriva
 *   exactamente la misma clave, así que el repositorio sigue siendo idempotente y nunca aparece una
 *   segunda fila con el mismo contenido.
 * - **Análisis nuevo sobre el mismo vídeo**: tiene otra clave base, así que no se deduplica contra el
 *   anterior por compartir vídeo; cada análisis se guarda como raíz propia.
 * - [attempt] solo avanza cuando la clave ya está ocupada por **otro contenido**
 *   ([com.openjump.app.data.angles.AngleV2SaveRejection.AlreadyStoredConflict]): entonces hace falta
 *   una clave nueva para no perder el borrador. Un `attempt` por instancia no habría servido: la
 *   identidad tiene que sobrevivir a la instancia que empezó el guardado.
 *
 * El separador `#` no aparece en las claves que genera la app y el criterio es el mismo que usa la
 * recuperación mono-fotograma ([recoverySessionKey], `#copy-N`): un sufijo derivado del intento, no
 * un identificador aleatorio por instancia.
 */
internal fun multiFrameSessionKey(baseSessionKey: String, attempt: Int): String {
    require(baseSessionKey.isNotBlank()) { "La clave de sesión base no puede estar vacía." }
    require(attempt >= 0) { "El intento del borrador multi-fotograma no puede ser negativo." }
    val suffix = if (attempt == 0) "multi-frame" else "multi-frame-${attempt + 1}"
    return "$baseSessionKey#$suffix"
}
