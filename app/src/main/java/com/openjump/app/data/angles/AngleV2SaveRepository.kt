package com.openjump.app.data.angles

import com.openjump.app.data.ANGLE_ANALYSIS_V2_FORMAT
import com.openjump.app.data.AngleAnalysisV2AggregateConflictException
import com.openjump.app.data.AngleAnalysisV2AggregateDraft
import com.openjump.app.data.AngleAnalysisV2Dao
import com.openjump.app.data.AngleAnalysisV2FrameDraft
import com.openjump.app.data.AngleAnalysisV2FrameEntity
import com.openjump.app.data.AngleAnalysisV2FrameSemantics
import com.openjump.app.data.AngleAnalysisV2InsertOutcome
import com.openjump.app.data.AngleAnalysisV2MarkEntity
import com.openjump.app.data.AngleAnalysisV2MarkSemantics
import com.openjump.app.data.AngleAnalysisV2RootEntity
import com.openjump.app.data.AngleAnalysisV2RootSemantics
import com.openjump.app.data.AngleAnalysisV2StoredAggregate
import com.openjump.app.data.describeAggregateDifference
import com.openjump.app.data.describeRootDifference
import com.openjump.app.data.toSemantics
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Mutex global del proceso para todo lo que toca el agregado V2 y sus imagenes.
 *
 * Lo comparten por construccion [AngleV2SaveRepository] (guardado),
 * [AngleV2DeleteRepository] (borrado confirmado + reclamacion de imagenes) y
 * [com.openjump.app.data.backup.MediaBackupCoordinator] (snapshot/export y restore con imagenes),
 * que es exactamente el alcance que hoy tiene la exclusion. **No** lo toman el borrado legacy de
 * analisis ni los escritores de Room ajenos al agregado V2, y al ser un objeto en memoria no
 * coordina procesos distintos: no se debe presentar como una garantia transaccional entre Room y
 * el sistema de ficheros.
 *
 * [restoreEpoch] vive aqui para que todos los que ya comparten [mutex] compartan tambien la
 * generacion de restauracion: una prueba de borrado preparada sobre una pagina antigua se invalida
 * con cualquier restore real de la app, sin un segundo cerrojo que se pueda olvidar. Sólo se mueve
 * **bajo** [mutex] ([AngleMediaRestoreEpoch.newTokenLocked]); el mutex no es reentrante, asi que las
 * vias que ya lo tienen (coordinador de medios) no lo vuelven a tomar.
 */
object AngleMediaCoordination {

    /** Un unico mutex por proceso; nunca se crea uno nuevo para estas operaciones. */
    val mutex: Mutex = Mutex()

    /**
     * Generacion de restauracion del proceso; un unico objeto, nunca uno por pantalla.
     *
     * Una pagina de historial debe leerla junto con sus datos
     * ([observeAngleHistoryAtomically]) y una confirmacion debe presentarla en
     * [AngleV2DeleteRepository.prepare].
     */
    val restoreEpoch: AngleMediaRestoreEpoch = AngleMediaRestoreEpoch()
}

/**
 * Guardado de un análisis multi-fotograma: PNG anotado duradero y verificado **antes** de la fila.
 *
 * **Qué hace esta pieza.** Recibe un análisis ya renderizado (un PNG por fotograma + los metadatos
 * reales de ese bitmap) y lo persiste en dos fases deliberadamente separadas:
 *
 * 1. **Almacén privado** ([PrivateAngleImageStore]): cada PNG se publica con una **clave nueva** que
 *    genera el almacén (`SecureRandom`), sin sobrescribir nada, y sólo se acepta si el fichero queda
 *    publicado, con el tamaño y el SHA-256 de los bytes escritos y con **durabilidad confirmada**
 *    ([AngleImageDurability.directoryEntriesSynced]). Además, esta capa contrasta el sello del
 *    almacén contra su propio cálculo (tamaño + SHA-256), relee el fichero con
 *    [PrivateAngleImageStore.verify] y comprueba que las dimensiones declaradas coinciden con la
 *    cabecera IHDR del PNG. Cualquier discrepancia falla cerrado: no se escribe ninguna fila.
 * 2. **Room** ([AngleAnalysisV2Dao.insertAggregate]): una sola `@Transaction` que inserta raíz,
 *    fotogramas y marcas. Si falla, Room la revierte entera.
 *
 * **Lo que no hace.** No borra ni sobrescribe nada: no existe ningún camino de borrado aquí, y una
 * imagen publicada que no llega a tener fila se **conserva** y se informa en
 * [AngleV2SaveResult.Rejected.orphanedImageKeys] (y en [AngleV2SaveResult.AlreadySaved] cuando la
 * repetición idempotente dejó imágenes sin fila). La recogida de huérfanos y el ciclo
 * guardar/borrar/backup/restore son de otro coordinador.
 *
 * **Sincronización.** [coordinationMutex] por defecto es el mutex global del proceso
 * ([AngleMediaCoordination.mutex]), que también toman el snapshot/export y el restore del backup
 * con imágenes ([com.openjump.app.data.backup.MediaBackupCoordinator]). Eso sí da exclusión real
 * entre guardado V2, backup y restore con medios dentro de la app. **Lo que no cubre**: el borrado
 * legacy de análisis ([com.openjump.app.data.AngleRepository]) y el resto de escritores de Room
 * ajenos al agregado V2 no toman este mutex, el mutex es por proceso y Room y el sistema de ficheros
 * no comparten transacción. No se debe suponer más exclusión que esa.
 *
 * **Hilos.** [save] es `suspend` y ejecuta el bloqueo (publicación + verificación + transacción) en
 * [ioDispatcher], nunca en el hilo principal. La cancelación se observa entre pasos y dentro de la
 * escritura del almacén; el trabajo bloqueante corre bajo [NonCancellable] **a propósito** para
 * poder devolver un resultado con las claves ya publicadas en vez de perderlas al desenrollar la
 * pila. La corrutina sigue cancelada (`Job` original inactivo), de modo que el ámbito exterior no
 * continúa llamando a suspender como si nada: esta función devuelve
 * [AngleV2SaveResult.Rejected] con [AngleV2SaveRejection.Canceled].
 *
 * **Presupuesto.** [AngleV2SaveBudget] acota el número de fotogramas y los bytes declarados de todo
 * el análisis **antes** de publicar el primer fichero; el presupuesto agregado del directorio lo
 * sigue aplicando el almacén ([AngleImageStoreLimits]).
 *
 * @param dao puerta transaccional del agregado V2.
 * @param imageStore almacén privado ya construido por el punto de composición (raíz real y
 *   existente, `fileIo` explícito). Esta capa no elige backend de ficheros.
 * @param coordinationMutex mutex **inyectado**; por defecto el global compartido con
 *   snapshot/export y restore de medios (ver la advertencia de arriba).
 * @param clock reloj para `createdAt`/`updatedAt`; inyectable para pruebas y para no depender del
 *   reloj del sistema en la construcción de la fila.
 * @param sha256 cálculo de SHA-256 en hexadecimal minúscula; inyectable para diagnóstico y pruebas.
 */
class AngleV2SaveRepository(
    private val dao: AngleAnalysisV2Dao,
    private val imageStore: PrivateAngleImageStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val coordinationMutex: Mutex = AngleMediaCoordination.mutex,
    private val budget: AngleV2SaveBudget = AngleV2SaveBudget(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sha256: (ByteArray) -> String = ::sha256HexOf,
) {

    /**
     * Guarda [request] y devuelve el resultado tipado.
     *
     * No lanza por causas esperables (presupuesto, PNG inválido, almacén, durabilidad, conflicto de
     * idempotencia, fallo de Room): las devuelve como [AngleV2SaveResult.Rejected]. Sólo propaga
     * cancelación **de entrada** (antes de tocar disco). Un fallo del que no se puede afirmar nada
     * se reporta con la clase de la excepción acotada, jamás como éxito.
     */
    suspend fun save(request: AngleV2SaveRequest): AngleV2SaveResult {
        // Cancelación explícita al entrar: no se publica un byte para una operación ya cancelada.
        currentCoroutineContext().ensureActive()
        return coordinationMutex.withLock {
            val job = currentCoroutineContext()[Job]
            // NonCancellable: el bloqueo debe terminar para poder informar de las claves ya
            // publicadas. La cancelación se observa consultando el Job original (`job`).
            withContext(NonCancellable + ioDispatcher) { saveExclusively(request, job) }
        }
    }

    private suspend fun saveExclusively(request: AngleV2SaveRequest, job: Job?): AngleV2SaveResult {
        request.validate()?.let { detail ->
            return AngleV2SaveResult.Rejected(AngleV2SaveRejection.InvalidRequest(detail))
        }
        if (!isActive(job)) return AngleV2SaveResult.Rejected(AngleV2SaveRejection.Canceled)

        val declaredBytes = request.frames.sumOf { it.pngBytes.size.toLong() }
        if (request.frames.size > budget.maxFrames) {
            return AngleV2SaveResult.Rejected(
                AngleV2SaveRejection.BudgetExceeded(
                    detail = "${request.frames.size} fotogramas superan el máximo de ${budget.maxFrames}.",
                ),
            )
        }
        if (declaredBytes > budget.maxTotalImageBytes) {
            return AngleV2SaveResult.Rejected(
                AngleV2SaveRejection.BudgetExceeded(
                    detail = "$declaredBytes bytes declarados superan el máximo de ${budget.maxTotalImageBytes}.",
                ),
            )
        }

        // Idempotencia ANTES de publicar: una repetición no debe dejar imágenes sin fila. La
        // instantánea se lee en una sola transacción de Room y se compara con **todas** las
        // columnas semánticas (ver [repeatOutcome]): una coincidencia parcial —sólo imagen o
        // conteos de marcas— no se acepta jamás como "ya guardado", porque dos análisis con los
        // mismos píxeles y otras marcas/instantes serían contenidos distintos y el segundo se
        // perdería en silencio.
        val existing = try {
            dao.aggregateBySessionKey(request.sessionKey)
        } catch (canceled: CancellationException) {
            return AngleV2SaveResult.Rejected(AngleV2SaveRejection.Canceled)
        } catch (error: RuntimeException) {
            return AngleV2SaveResult.Rejected(storageUnavailable(error))
        }
        if (existing != null) {
            return when (val outcome = repeatOutcome(snapshot = existing, request = request)) {
                is RepeatOutcome.Identical -> AngleV2SaveResult.AlreadySaved(outcome.rootId)
                is RepeatOutcome.Conflict -> AngleV2SaveResult.Rejected(
                    AngleV2SaveRejection.AlreadyStoredConflict(outcome.rootId, outcome.detail),
                )
                is RepeatOutcome.Unreadable -> AngleV2SaveResult.Rejected(storageUnavailable(outcome.error))
            }
        }

        // Fase 1: publicar, verificar y sellar cada PNG. Cualquier fallo deja de publicar el resto.
        val publishedKeys = ArrayList<String>(request.frames.size)
        val drafts = ArrayList<AngleAnalysisV2FrameDraft>(request.frames.size)
        request.frames.forEachIndexed { ordinal, frame ->
            if (!isActive(job)) {
                return AngleV2SaveResult.Rejected(AngleV2SaveRejection.Canceled, publishedKeys.toList())
            }
            when (val outcome = publishFrame(frame, ordinal, job)) {
                is FramePublishOutcome.Published -> {
                    // La clave cuenta como "posiblemente publicada" incluso si el sello falla después:
                    // se informa y se conserva, nunca se borra ni se reutiliza.
                    publishedKeys += outcome.imageKey
                    drafts += outcome.draft
                }
                is FramePublishOutcome.Failed -> {
                    // Una publicación incierta puede haber dejado la clave en disco: se informa como
                    // huérfana en vez de afirmar que no se publicó nada.
                    outcome.uncertainKey?.let { key ->
                        if (key !in publishedKeys) publishedKeys += key
                    }
                    return AngleV2SaveResult.Rejected(outcome.reason, publishedKeys.toList())
                }
            }
        }

        // Fase 2: una única transacción. Si falla, las claves publicadas quedan huérfanas y se informan.
        val now = clock()
        val draft = AngleAnalysisV2AggregateDraft(
            root = AngleAnalysisV2RootEntity(
                sessionKey = request.sessionKey,
                athleteId = request.athleteId,
                createdAt = now,
                updatedAt = now,
            ),
            frames = drafts,
        )
        val outcome = try {
            dao.insertAggregate(draft)
        } catch (canceled: CancellationException) {
            return AngleV2SaveResult.Rejected(AngleV2SaveRejection.Canceled, publishedKeys.toList())
        } catch (conflict: AngleAnalysisV2AggregateConflictException) {
            return AngleV2SaveResult.Rejected(
                AngleV2SaveRejection.AlreadyStoredConflict(
                    rootId = null,
                    detail = conflict.message ?: "Conflicto de sessionKey.",
                ),
                publishedKeys.toList(),
            )
        } catch (invalid: IllegalArgumentException) {
            // Defensivo: el borrador lo compone esta capa, así que un rechazo del DAO sería un fallo
            // de composición, no una petición inválida del llamante. Se reporta igualmente y las
            // imágenes ya publicadas se conservan e informan.
            return AngleV2SaveResult.Rejected(
                AngleV2SaveRejection.InvalidRequest(
                    "El agregado no pasó la validación transaccional: " +
                        (invalid.message?.take(MAX_DETAIL_CHARS) ?: "sin detalle"),
                ),
                publishedKeys.toList(),
            )
        } catch (error: RuntimeException) {
            // Incluye SQLiteConstraintException de un índice único y fallos de Room. La transacción
            // ya está revertida por Room; las imágenes publicadas NO se tocan.
            return AngleV2SaveResult.Rejected(storageUnavailable(error), publishedKeys.toList())
        }
        return when (outcome) {
            is AngleAnalysisV2InsertOutcome.Inserted ->
                AngleV2SaveResult.Saved(outcome.rootId, publishedKeys.toList())
            is AngleAnalysisV2InsertOutcome.AlreadyStored ->
                // Carrera perdida contra otro guardado de la misma sessionKey: las imágenes de este
                // intento no están referenciadas por ninguna fila. Se conservan y se informan.
                AngleV2SaveResult.AlreadySaved(outcome.rootId, publishedKeys.toList())
        }
    }

    private fun publishFrame(frame: AngleV2SaveFrame, ordinal: Int, job: Job?): FramePublishOutcome {
        val png = frame.pngBytes
        val declared = AngleImageIntegrity(byteCount = png.size.toLong(), sha256Hex = sha256(png))
        // Contraste de dimensiones contra la cabecera real del PNG: no se sella una fila con un
        // tamaño que el fichero no tiene.
        val header = pngPixelSize(png)
            ?: return FramePublishOutcome.Failed(
                AngleV2SaveRejection.InvalidRequest("El PNG del fotograma $ordinal no tiene cabecera IHDR válida."),
            )
        if (header.width != frame.imageWidth || header.height != frame.imageHeight) {
            return FramePublishOutcome.Failed(
                AngleV2SaveRejection.InvalidRequest(
                    "El PNG del fotograma $ordinal mide ${header.width}×${header.height} y se declaran " +
                        "${frame.imageWidth}×${frame.imageHeight}.",
                ),
            )
        }
        val write = imageStore.write(
            source = ByteArrayInputStream(png),
            expectedByteCount = png.size.toLong(),
            shouldCancel = { !isActive(job) },
        )
        val stored = when (write) {
            is AngleImageWriteResult.Stored -> write
            is AngleImageWriteResult.Failed -> {
                // `PublicationUncertain` puede haber dejado la clave publicada: se informa como
                // huérfana en vez de afirmar que no se publicó nada.
                val uncertainKey = (write.error as? AngleImageStoreError.PublicationUncertain)?.imageKey
                return FramePublishOutcome.Failed(
                    reason = AngleV2SaveRejection.ImageStoreFailure(
                        frameOrdinal = ordinal,
                        error = write.error,
                        temporaryFileRetained = write.temporaryFileRetained,
                        directoryRetained = write.directoryRetained,
                    ),
                    uncertainKey = uncertainKey,
                )
            }
        }
        val imageKey = stored.image.imageKey
        // Contraste del sello: el almacén dice haber escrito estos bytes; lo comprobamos.
        if (stored.image.byteCount != declared.byteCount) {
            return FramePublishOutcome.Failed(
                AngleV2SaveRejection.StoreSealMismatch(
                    frameOrdinal = ordinal,
                    imageKey = imageKey,
                    detail = "bytes ${stored.image.byteCount} != ${declared.byteCount}",
                ),
                uncertainKey = imageKey,
            )
        }
        if (stored.image.sha256Hex.lowercase() != declared.sha256Hex) {
            return FramePublishOutcome.Failed(
                AngleV2SaveRejection.StoreSealMismatch(
                    frameOrdinal = ordinal,
                    imageKey = imageKey,
                    detail = "SHA-256 del almacén distinto del calculado",
                ),
                uncertainKey = imageKey,
            )
        }
        if (!stored.durability.directoryEntriesSynced) {
            // Fail-closed: la imagen está publicada pero su persistencia no está confirmada. No se
            // escribe la fila que la declararía durable en Room.
            return FramePublishOutcome.Failed(
                AngleV2SaveRejection.DurabilityNotConfirmed(ordinal, imageKey, stored.durability),
                uncertainKey = imageKey,
            )
        }
        when (val verified = imageStore.verify(imageKey, declared)) {
            is AngleImageVerifyResult.Verified -> Unit
            is AngleImageVerifyResult.Failed -> return FramePublishOutcome.Failed(
                AngleV2SaveRejection.PublicationUnverified(ordinal, verified.error),
                uncertainKey = imageKey,
            )
        }
        return FramePublishOutcome.Published(
            imageKey = imageKey,
            draft = AngleAnalysisV2FrameDraft(
                frame = AngleAnalysisV2FrameEntity(
                    rootId = 0L,
                    ordinal = ordinal,
                    assignedFrameIndex = frame.assignedFrameIndex,
                    reportedFrameIndex = frame.reportedFrameIndex,
                    assignedPtsUs = frame.assignedPtsUs,
                    reportedPtsUs = frame.reportedPtsUs,
                    encodedWidth = frame.encodedWidth,
                    encodedHeight = frame.encodedHeight,
                    presentationWidth = frame.presentationWidth,
                    presentationHeight = frame.presentationHeight,
                    rotationDegrees = frame.rotationDegrees,
                    geometryVersion = frame.geometryVersion,
                    imageKey = imageKey,
                    imageWidth = header.width,
                    imageHeight = header.height,
                    imageByteCount = declared.byteCount,
                    imageSha256 = declared.sha256Hex,
                    imageMimeType = PNG_MIME_TYPE,
                ),
                marks = frame.marks.mapIndexed { index, mark ->
                    AngleAnalysisV2MarkEntity(
                        frameId = 0L,
                        ordinal = index,
                        name = mark.name,
                        colorArgb = mark.colorArgb,
                        visible = mark.visible,
                        pointAX = mark.pointAX,
                        pointAY = mark.pointAY,
                        pointBX = mark.pointBX,
                        pointBY = mark.pointBY,
                        pointCX = mark.pointCX,
                        pointCY = mark.pointCY,
                    )
                },
            ),
        )
    }

    /**
     * Compara un agregado ya guardado con la petición usando **todas** las columnas semánticas
     * persistidas, exactamente la misma huella que aplica [AngleAnalysisV2Dao.insertAggregate]
     * dentro de su transacción ([describeRootDifference] + [describeAggregateDifference]).
     *
     * Nunca acepta una coincidencia parcial. Sólo devuelve [RepeatOutcome.Identical] cuando raíz,
     * fotogramas (orden, índices `assigned`/`reported`, PTS, geometría e integridad de imagen) y
     * marcas (orden, nombre, color, visibilidad y las seis coordenadas) coinciden por completo. Si
     * algo difiere —o si no se puede comprobar, por ejemplo un PNG sin cabecera IHDR comparable—
     * devuelve [RepeatOutcome.Conflict]: fail-closed.
     *
     * La `imageKey` no se compara (cada intento publica una clave nueva con los mismos bytes) y los
     * timestamps de la raíz tampoco (los deriva el intento, no el usuario).
     */
    private fun repeatOutcome(snapshot: AngleAnalysisV2StoredAggregate, request: AngleV2SaveRequest): RepeatOutcome {
        return try {
            val rootDifference = describeRootDifference(
                stored = snapshot.root.toSemantics(),
                expected = AngleAnalysisV2RootSemantics(
                    analysisFormat = ANGLE_ANALYSIS_V2_FORMAT,
                    sessionKey = request.sessionKey,
                    athleteId = request.athleteId,
                    revision = 0,
                ),
            )
            if (rootDifference != null) {
                return RepeatOutcome.Conflict(
                    rootId = snapshot.root.id,
                    detail = "La sessionKey ya está guardada con otra raíz ($rootDifference): no se reescribe.",
                )
            }
            val expected = frameSemanticsOf(request)
                ?: return RepeatOutcome.Conflict(
                    rootId = snapshot.root.id,
                    detail = "No se puede comprobar la identidad del contenido: el PNG de algún " +
                        "fotograma no declara dimensiones comparables (IHDR).",
                )
            val difference = describeAggregateDifference(
                stored = snapshot.frames.map { storedFrame -> storedFrame.toSemantics() },
                expected = expected,
            )
            if (difference == null) {
                RepeatOutcome.Identical(snapshot.root.id)
            } else {
                RepeatOutcome.Conflict(
                    rootId = snapshot.root.id,
                    detail = "La sessionKey ya está guardada con otro contenido ($difference): no se reescribe.",
                )
            }
        } catch (error: RuntimeException) {
            RepeatOutcome.Unreadable(error)
        }
    }

    /**
     * Proyección de la petición a las columnas semánticas que se persistirían, o `null` si algún
     * PNG no declara cabecera IHDR válida (entonces no se puede afirmar nada sobre la integridad de
     * la imagen que se guardaría y la comparación debe fallar cerrado).
     *
     * La integridad se calcula **igual** que en [publishFrame]: dimensiones de la cabecera IHDR,
     * tamaño real de los bytes, SHA-256 de esos bytes y MIME `image/png`. Por eso una repetición
     * legítima coincide columna a columna aunque la `imageKey` publicada sea nueva.
     */
    private fun frameSemanticsOf(request: AngleV2SaveRequest): List<AngleAnalysisV2FrameSemantics>? {
        val semantics = ArrayList<AngleAnalysisV2FrameSemantics>(request.frames.size)
        request.frames.forEachIndexed { ordinal, frame ->
            val header = pngPixelSize(frame.pngBytes) ?: return null
            semantics += AngleAnalysisV2FrameSemantics(
                ordinal = ordinal,
                assignedFrameIndex = frame.assignedFrameIndex,
                reportedFrameIndex = frame.reportedFrameIndex,
                assignedPtsUs = frame.assignedPtsUs,
                reportedPtsUs = frame.reportedPtsUs,
                encodedWidth = frame.encodedWidth,
                encodedHeight = frame.encodedHeight,
                presentationWidth = frame.presentationWidth,
                presentationHeight = frame.presentationHeight,
                rotationDegrees = frame.rotationDegrees,
                geometryVersion = frame.geometryVersion,
                imageWidth = header.width,
                imageHeight = header.height,
                imageByteCount = frame.pngBytes.size.toLong(),
                imageSha256 = sha256(frame.pngBytes),
                imageMimeType = PNG_MIME_TYPE,
                marks = frame.marks.mapIndexed { index, mark ->
                    AngleAnalysisV2MarkSemantics(
                        ordinal = index,
                        name = mark.name,
                        colorArgb = mark.colorArgb,
                        visible = mark.visible,
                        pointAX = mark.pointAX,
                        pointAY = mark.pointAY,
                        pointBX = mark.pointBX,
                        pointBY = mark.pointBY,
                        pointCX = mark.pointCX,
                        pointCY = mark.pointCY,
                    )
                },
            )
        }
        return semantics
    }

    private fun isActive(job: Job?): Boolean = job?.isActive ?: true

    private fun storageUnavailable(error: RuntimeException): AngleV2SaveRejection =
        AngleV2SaveRejection.StorageUnavailable(
            errorClass = error.javaClass.simpleName,
            detail = error.message?.take(MAX_DETAIL_CHARS) ?: "sin detalle",
        )

    private companion object {
        const val PNG_MIME_TYPE = "image/png"
        const val MAX_DETAIL_CHARS = 200
    }
}

/**
 * Análisis completo ya renderizado: la `sessionKey` de la sesión de captura, el dueño y los
 * fotogramas en orden.
 *
 * No lleva `createdAt`/`updatedAt`: los pone el repositorio con su reloj, para que la fila nunca
 * declare una fecha inventada por la UI. [athleteId] es nullable porque la convención del esquema lo
 * permite; los guardados nuevos deberían llevar dueño.
 */
class AngleV2SaveRequest(
    val sessionKey: String,
    val athleteId: Long?,
    val frames: List<AngleV2SaveFrame>,
) {
    /** Devuelve el primer problema encontrado o `null` si la petición es persistible. */
    fun validate(): String? {
        if (sessionKey.isBlank()) return "La sessionKey no puede estar vacía."
        if (frames.isEmpty()) return "Un guardado V2 necesita al menos un fotograma."
        frames.forEachIndexed { index, frame ->
            if (frame.pngBytes.isEmpty()) return "El fotograma $index no trae bytes PNG."
            if (frame.imageWidth < 1 || frame.imageHeight < 1) {
                return "El fotograma $index declara dimensiones de imagen no positivas."
            }
            if (frame.encodedWidth < 1 || frame.encodedHeight < 1) {
                return "El fotograma $index declara dimensiones codificadas no positivas."
            }
            if (frame.presentationWidth < 1 || frame.presentationHeight < 1) {
                return "El fotograma $index declara dimensiones de presentación no positivas."
            }
            if (frame.rotationDegrees !in 0..359) return "El fotograma $index declara rotación fuera de 0..359."
            if (frame.geometryVersion < 1) return "El fotograma $index declara geometryVersion < 1."
            if (frame.assignedFrameIndex < 0) return "El fotograma $index declara assignedFrameIndex negativo."
            if (frame.assignedPtsUs < 0L) return "El fotograma $index declara assignedPtsUs negativo."
            if (frame.reportedFrameIndex?.let { it < 0 } == true) {
                return "El fotograma $index declara reportedFrameIndex negativo."
            }
            if (frame.reportedPtsUs?.let { it < 0L } == true) {
                return "El fotograma $index declara reportedPtsUs negativo."
            }
            frame.marks.forEachIndexed { markIndex, mark ->
                if (mark.name.isBlank()) return "La marca $markIndex del fotograma $index no tiene nombre."
                if (!mark.hasFinitePoints()) {
                    return "La marca $markIndex del fotograma $index tiene coordenadas no finitas."
                }
            }
        }
        return null
    }
}

/**
 * Fotograma ya renderizado y sus metadatos reales.
 *
 * [pngBytes] son los bytes del PNG anotado tal y como se van a guardar; [imageWidth]/[imageHeight]
 * son las dimensiones de ese bitmap y **deben** coincidir con la cabecera IHDR del PNG (se
 * comprueban). [encodedWidth]/[encodedHeight], [presentationWidth]/[presentationHeight],
 * [rotationDegrees] y [geometryVersion] describen el sistema de coordenadas de [marks]; no se
 * derivan de la imagen.
 *
 * No es `data class` a propósito: contiene un `ByteArray` y la igualdad estructural sobre bytes sería
 * engañosa. Es un valor de entrada, no una fila.
 */
class AngleV2SaveFrame(
    val assignedFrameIndex: Int,
    val reportedFrameIndex: Int?,
    val assignedPtsUs: Long,
    val reportedPtsUs: Long?,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val presentationWidth: Int,
    val presentationHeight: Int,
    val rotationDegrees: Int,
    val geometryVersion: Int,
    val pngBytes: ByteArray,
    val imageWidth: Int,
    val imageHeight: Int,
    val marks: List<AngleV2SaveMark> = emptyList(),
)

/** Marca ya medida sobre el fotograma, en píxeles de presentación de ese fotograma. */
data class AngleV2SaveMark(
    val name: String,
    val colorArgb: Int,
    val visible: Boolean = true,
    val pointAX: Double,
    val pointAY: Double,
    val pointBX: Double,
    val pointBY: Double,
    val pointCX: Double,
    val pointCY: Double,
) {
    internal fun hasFinitePoints(): Boolean =
        pointAX.isFinite() && pointAY.isFinite() &&
            pointBX.isFinite() && pointBY.isFinite() &&
            pointCX.isFinite() && pointCY.isFinite()
}

/** Presupuesto de un guardado, comprobado **antes** de publicar el primer PNG. */
data class AngleV2SaveBudget(
    val maxFrames: Int = 24,
    val maxTotalImageBytes: Long = 96L * 1024L * 1024L,
) {
    init {
        require(maxFrames >= 1) { "maxFrames debe ser positivo." }
        require(maxTotalImageBytes >= 1L) { "maxTotalImageBytes debe ser positivo." }
    }
}

/** Resultado del guardado. Nunca se informa de éxito parcial. */
sealed interface AngleV2SaveResult {

    /** Raíz insertada con sus N fotogramas; [imageKeys] son las claves publicadas y referenciadas. */
    data class Saved(val rootId: Long, val imageKeys: List<String>) : AngleV2SaveResult

    /**
     * La `sessionKey` ya estaba guardada con **todas** las columnas semánticas idénticas (raíz,
     * fotogramas —instantes, orden, geometría e integridad de imagen— y marcas —orden, nombre,
     * color, visibilidad y coordenadas—): no se ha insertado nada.
     *
     * Sólo se informa ante una coincidencia **completa**. Una coincidencia parcial nunca llega aquí:
     * se informa [AngleV2SaveRejection.AlreadyStoredConflict], porque dar por guardado un análisis
     * con otras marcas o instantes perdería el segundo en silencio.
     *
     * [orphanedImageKeys] no está vacío sólo en la carrera perdida contra otro guardado simultáneo:
     * son imágenes publicadas por este intento que ninguna fila referencia. Se conservan (no se
     * borran) y se informan para la futura recogida de huérfanos.
     */
    data class AlreadySaved(
        val rootId: Long,
        val orphanedImageKeys: List<String> = emptyList(),
    ) : AngleV2SaveResult

    /**
     * No se guardó. [orphanedImageKeys] son las claves que este intento pudo dejar publicadas y sin
     * fila: se conservan y el llamante debe registrarlas/reportarlas, nunca borrarlas por su cuenta
     * sin el coordinador de huérfanos.
     */
    data class Rejected(
        val reason: AngleV2SaveRejection,
        val orphanedImageKeys: List<String> = emptyList(),
    ) : AngleV2SaveResult
}

/** Motivo tipado de un guardado rechazado. */
sealed interface AngleV2SaveRejection {

    /** La petición no es persistible (forma, rangos, PNG sin IHDR o dimensiones que no cuadran). */
    data class InvalidRequest(val detail: String) : AngleV2SaveRejection

    /** Se supera [AngleV2SaveBudget]; no se ha publicado nada. */
    data class BudgetExceeded(val detail: String) : AngleV2SaveRejection

    /**
     * El almacén rechazó la publicación en [frameOrdinal]. [error] es la causa tipada del almacén;
     * los indicadores dicen si quedó un temporal o un directorio de clave propio sin retirar.
     */
    data class ImageStoreFailure(
        val frameOrdinal: Int,
        val error: AngleImageStoreError,
        val temporaryFileRetained: Boolean = false,
        val directoryRetained: Boolean = false,
    ) : AngleV2SaveRejection

    /** El almacén publicó la imagen pero no pudo confirmar la persistencia de las entradas. */
    data class DurabilityNotConfirmed(
        val frameOrdinal: Int,
        val imageKey: String,
        val durability: AngleImageDurability,
    ) : AngleV2SaveRejection

    /** La relectura de verificación no coincide con lo escrito; no se escribe ninguna fila. */
    data class PublicationUnverified(
        val frameOrdinal: Int,
        val error: AngleImageStoreError,
    ) : AngleV2SaveRejection

    /** El sello del almacén (tamaño/SHA-256) no coincide con el cálculo de esta capa. */
    data class StoreSealMismatch(
        val frameOrdinal: Int,
        val imageKey: String,
        val detail: String,
    ) : AngleV2SaveRejection

    /**
     * La `sessionKey` ya está guardada con cualquier diferencia semántica (dueño, formato, revisión,
     * fotogramas, geometría, integridad de imagen o marcas) o con contenido que no se puede
     * comprobar: no se reescribe y **no** se informa como ya guardado.
     */
    data class AlreadyStoredConflict(
        val rootId: Long?,
        val detail: String,
    ) : AngleV2SaveRejection

    /** Fallo de Room/base: la transacción quedó revertida por Room; las imágenes se conservan. */
    data class StorageUnavailable(
        val errorClass: String,
        val detail: String,
    ) : AngleV2SaveRejection

    /** Cancelación observada antes o durante el guardado. */
    data object Canceled : AngleV2SaveRejection
}

/** Resultado interno de publicar un fotograma. */
private sealed interface FramePublishOutcome {

    class Published(
        val imageKey: String,
        val draft: AngleAnalysisV2FrameDraft,
    ) : FramePublishOutcome

    /**
     * Fallo de publicación. [uncertainKey] es la clave que puede haber quedado publicada y que el
     * llamante debe sumar a los huérfanos (nunca borrar).
     */
    class Failed(
        val reason: AngleV2SaveRejection,
        val uncertainKey: String? = null,
    ) : FramePublishOutcome
}

/** Resultado interno de comparar una raíz existente con la petición. */
private sealed interface RepeatOutcome {

    data class Identical(val rootId: Long) : RepeatOutcome

    data class Conflict(val rootId: Long, val detail: String) : RepeatOutcome

    data class Unreadable(val error: RuntimeException) : RepeatOutcome
}

/** Dimensiones declaradas en la cabecera IHDR de un PNG. */
internal data class PngPixelSize(val width: Int, val height: Int)

private val PNG_SIGNATURE: ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val PNG_IHDR: ByteArray = byteArrayOf(0x49, 0x48, 0x44, 0x52)

/**
 * Lee las dimensiones de la cabecera IHDR de [bytes], o `null` si no es un PNG con IHDR en la
 * posición canónica o si declara dimensiones no positivas.
 *
 * Es una comprobación de **forma**, no un decodificador: no descomprime el bitmap ni valida CRCs.
 * Sirve para no sellar en Room un tamaño distinto del que el fichero declara.
 */
internal fun pngPixelSize(bytes: ByteArray): PngPixelSize? {
    if (bytes.size < PNG_HEADER_BYTES) return null
    for (index in PNG_SIGNATURE.indices) {
        if (bytes[index] != PNG_SIGNATURE[index]) return null
    }
    for (index in PNG_IHDR.indices) {
        if (bytes[PNG_IHDR_OFFSET + index] != PNG_IHDR[index]) return null
    }
    val width = readBigEndianInt(bytes, PNG_WIDTH_OFFSET)
    val height = readBigEndianInt(bytes, PNG_HEIGHT_OFFSET)
    if (width < 1 || height < 1) return null
    return PngPixelSize(width, height)
}

private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

/** SHA-256 en hexadecimal minúscula; reutiliza el conversor del almacén para no duplicar formato. */
internal fun sha256HexOf(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHexString()

/** Longitud mínima de un PNG con IVHDR: firma (8) + longitud (4) + tipo (4) + IHDR (13). */
private const val PNG_HEADER_BYTES = 33

/** Offset del campo `type` del primer chunk, que debe ser `IHDR`. */
private const val PNG_IHDR_OFFSET = 12

/** Offset del ancho en el IHDR. */
private const val PNG_WIDTH_OFFSET = 16

/** Offset del alto en el IHDR. */
private const val PNG_HEIGHT_OFFSET = 20
