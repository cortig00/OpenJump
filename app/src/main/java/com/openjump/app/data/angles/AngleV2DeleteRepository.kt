package com.openjump.app.data.angles

import com.openjump.app.data.AngleAnalysisV2Dao
import com.openjump.app.data.AngleAnalysisV2DeleteOutcome
import com.openjump.app.data.AngleAnalysisV2OwnedImage
import com.openjump.app.data.AngleAnalysisV2StoredAggregate
import com.openjump.app.data.AngleAnalysisV2StoredFrame
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinación del borrado V2 confirmado: preparación de una prueba exacta y ejecución con limpieza
 * de las imágenes que la raíz poseía.
 *
 * **Qué hace.** Une lo que ya existe por separado: la instantánea del agregado
 * ([AngleAnalysisV2Dao.aggregateByRootId]), la puerta de borrado exacta
 * ([AngleAnalysisV2Dao.deleteConfirmedAggregate]), el contador de referencias
 * ([AngleAnalysisV2Dao.frameImageReferenceCount]) y la retirada interna de una imagen publicada
 * ([PrivateAngleImageStore.reclaimVerifiedOwnedImage]). Esta pieza decide **cuándo** se llama a
 * cada una y con qué exclusión; no reimplementa ninguna de las cuatro.
 *
 * **Preparar no borra.** [prepare] comprueba primero la generación de restauración
 * ([AngleMediaRestoreEpoch]) y sólo después lee el agregado completo, que copia lista por lista
 * ([AngleV2DeleteProof]). Una página/diálogo viejo no puede preparar una prueba sobre una raíz
 * restaurada bajo el mismo número: la generación ya no coincide y se devuelve
 * [AngleV2DeletePreparation.Stale]. Una prueba es efímera: no se serializa ni se reconstruye por id.
 *
 * **Ejecutar.** [execute] toma el mutex global del proceso ([AngleMediaCoordination.mutex]), el
 * mismo que serializan guardado V2, snapshot/export y restore de backup con imágenes, y lo mantiene
 * desde la revalidación de la generación **hasta el commit de Room y la limpieza completa**: ni un
 * guardado ni una restauración pueden intercalarse entre el `DELETE` y la retirada de los ficheros
 * (eso permitiría republicar una clave ya confirmada como libre o dejar una referencia recién
 * creada apuntando a un fichero borrado).
 *
 * Orden fail-closed de [execute]:
 *  1. cancelación al entrar (antes del mutex) y mientras se espera el mutex: no se muta nada y se
 *     propaga la cancelación al llamante;
 *  2. ya bajo el mutex, el trabajo es **no cancelable** y acotado: revalidar la generación y llamar
 *     al DAO, que relee y compara la prueba completa dentro de su propia transacción;
 *  3. un error del DAO **antes** de confirmar el `DELETE` devuelve [AngleV2DeleteRun.Failed] sin
 *     tocar ningún fichero (Room revierte su transacción);
 *  4. sólo con [AngleAnalysisV2DeleteOutcome.Deleted] se reclaman las claves **exactas** que el DAO
 *     devolvió y ninguna otra; justo antes de cada reclamación se consulta
 *     [AngleAnalysisV2Dao.frameImageReferenceCount]: si hay referencias o la consulta falla, la
 *     imagen se **conserva** y se informa como retenida, sin adivinar ni reintentar;
 *  5. un rechazo, un fallo o una retirada incierta del almacén **no** revierten el borrado ni lo
 *     convierten en `Failed`: el resultado sigue siendo [AngleV2DeleteRun.Deleted] con un
 *     [AngleV2DeleteCleanupReport] honesto.
 *
 * **Lo que no hace.** No recolecta huérfanos, no enumera el almacén, no borra vídeos, copias
 * compartidas ni otras claves, no borra directorios recursivamente, no persiste un registro de
 * operaciones y no reintenta un cleanup parcial. No hay borrado por id: sin prueba completa el DAO
 * no borra nada.
 *
 * **Límites del proceso.** El mutex es de corrutinas de este proceso: no coordina otro proceso/UID,
 * ni código hostil con acceso al sandbox, ni `DELETE`/cascadas ajenas al coordinador (por ejemplo el
 * borrado permanente de un atleta). Esas carreras se detectan por la relectura exacta del DAO
 * ([AngleV2DeleteRun.Missing]/[AngleV2DeleteRun.Changed]) y exigen reconfirmar, nunca retargetear.
 *
 * @param dao puerta transaccional del agregado V2.
 * @param imageStore almacén privado de imágenes (sólo se usa para reclamar); el punto de composición
 *   debe pasar exactamente uno de `imageStore`/`reclaimImage`.
 * @param ioDispatcher dispatcher del trabajo bloqueante (Room y ficheros); nunca el hilo principal.
 * @param coordinationMutex mutex global compartido con guardado y backup/restore; por defecto el del
 *   proceso. Los tests inyectan el mismo par mutex + generación que el resto de piezas bajo prueba.
 * @param restoreEpoch generación de restauración compartida con las vías de restore reales.
 * @param reclaimImage costura de prueba para la retirada exacta; por defecto delega en [imageStore].
 */
internal class AngleV2DeleteRepository(
    private val dao: AngleAnalysisV2Dao,
    imageStore: PrivateAngleImageStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val coordinationMutex: Mutex = AngleMediaCoordination.mutex,
    private val restoreEpoch: AngleMediaRestoreEpoch = AngleMediaCoordination.restoreEpoch,
    reclaimImage: (suspend (String, AngleImageIntegrity) -> AngleImageReclaimResult)? = null,
) {

    init {
        check(imageStore != null || reclaimImage != null) {
            "El borrado V2 necesita el almacén privado o una costura de reclamación explícita."
        }
    }

    private val reclaim: suspend (String, AngleImageIntegrity) -> AngleImageReclaimResult =
        reclaimImage ?: { key, integrity ->
            requireNotNull(imageStore) { "Almacén privado no configurado." }
                .reclaimVerifiedOwnedImage(key, integrity)
        }

    /**
     * Prepara una prueba de borrado para [rootId] sólo si sigue vigente [expectedRestoreToken].
     *
     * La comprobación de la generación ocurre **antes** de leer la raíz: un diálogo abierto antes de
     * una restauración no llega a construir una prueba que apunte a la raíz restaurada. La lectura
     * del agregado (raíz + fotogramas + marcas) es una sola transacción del DAO y se copia completa
     * para que la prueba no comparta estructura mutable con nada.
     *
     * No muta nada (ni filas ni ficheros) y no es no cancelable: la cancelación se propaga sin
     * efectos. No lanza por causas esperables.
     */
    suspend fun prepare(rootId: Long, expectedRestoreToken: Long): AngleV2DeletePreparation {
        if (rootId <= 0L) {
            return AngleV2DeletePreparation.Failed(
                AngleV2DeleteFailure.InvalidProof("una prueba de borrado necesita un id de raíz persistido"),
            )
        }
        return coordinationMutex.withLock {
            withContext(ioDispatcher) {
                val observed = restoreEpoch.current()
                if (observed != expectedRestoreToken) {
                    return@withContext AngleV2DeletePreparation.Stale(observedRestoreToken = observed)
                }
                val snapshot = try {
                    dao.aggregateByRootId(rootId)
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (error: RuntimeException) {
                    return@withContext AngleV2DeletePreparation.Failed(
                        AngleV2DeleteFailure.Database(error.javaClass.simpleName),
                    )
                }
                if (snapshot == null) {
                    AngleV2DeletePreparation.Missing(rootId = rootId)
                } else {
                    AngleV2DeletePreparation.Prepared(
                        AngleV2DeleteProof(
                            snapshot = snapshot.immutableCopy(),
                            restoreToken = expectedRestoreToken,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Ejecuta el borrado confirmado por [proof] y, si Room confirma, reclama las imágenes exactas.
     *
     * No lanza por causas esperables (DAO, almacén, referencias): las devuelve tipadas. Propaga la
     * cancelación **antes** de entrar o mientras espera el mutex, sin mutar nada. Una vez adquirido
     * el mutex, todo el tramo (revalidación + `DELETE` + limpieza) es no cancelable para no dejar un
     * commit sin su informe; el resultado de ese tramo lo entrega [AngleV2DeleteOwnership] a un
     * ámbito de aplicación, no a un llamante de UI ya cancelado.
     */
    suspend fun execute(proof: AngleV2DeleteProof): AngleV2DeleteRun {
        currentCoroutineContext().ensureActive()
        return coordinationMutex.withLock {
            withContext(NonCancellable + ioDispatcher) { executeExclusively(proof) }
        }
    }

    private suspend fun executeExclusively(proof: AngleV2DeleteProof): AngleV2DeleteRun {
        val observedToken = restoreEpoch.current()
        if (observedToken != proof.restoreToken) {
            // Una restauración cambió la identidad del agregado: nada de retargetear otra raíz.
            return AngleV2DeleteRun.Stale(observedRestoreToken = observedToken)
        }
        val outcome = try {
            dao.deleteConfirmedAggregate(proof.snapshot)
        } catch (canceled: CancellationException) {
            // Tramo no cancelable: una cancelación aquí es un fallo del DAO, no del llamante. No se
            // puede afirmar que se borrara, así que se informa fallo sin tocar ficheros.
            return AngleV2DeleteRun.Failed(AngleV2DeleteFailure.Database(canceled.javaClass.simpleName))
        } catch (error: RuntimeException) {
            return AngleV2DeleteRun.Failed(AngleV2DeleteFailure.Database(error.javaClass.simpleName))
        }
        return when (outcome) {
            is AngleAnalysisV2DeleteOutcome.Deleted ->
                AngleV2DeleteRun.Deleted(
                    rootId = outcome.rootId,
                    cleanup = reclaimOwnedImages(outcome.ownedImages),
                )

            is AngleAnalysisV2DeleteOutcome.Changed ->
                AngleV2DeleteRun.Changed(rootId = outcome.rootId, detail = outcome.detail)

            is AngleAnalysisV2DeleteOutcome.Missing -> AngleV2DeleteRun.Missing(rootId = outcome.rootId)

            is AngleAnalysisV2DeleteOutcome.InvalidProof ->
                AngleV2DeleteRun.Failed(AngleV2DeleteFailure.InvalidProof(outcome.detail))
        }
    }

    /**
     * Reclama **sólo** las claves que el DAO devolvió como propiedad de la raíz borrada, en el orden
     * de los fotogramas.
     *
     * Por cada clave, inmediatamente antes de reclamarla, se consulta la referencia persistida. Si
     * hay alguna fila que la usa (no debería: el índice único impide compartir clave) o si la
     * consulta falla, se conserva el fichero y se informa la retención: no se adivina ni se
     * reintenta. Nada de este bucle puede revertir el borrado ya confirmado.
     */
    private suspend fun reclaimOwnedImages(
        ownedImages: List<AngleAnalysisV2OwnedImage>,
    ): AngleV2DeleteCleanupReport {
        val entries = ArrayList<AngleV2DeleteCleanupEntry>(ownedImages.size)
        ownedImages.forEachIndexed { ordinal, image ->
            val retention = referenceRetention(image.imageKey)
            entries += if (retention != null) {
                AngleV2DeleteCleanupEntry.Retained(frameOrdinal = ordinal, reason = retention)
            } else {
                reclaimOne(frameOrdinal = ordinal, image = image)
            }
        }
        return AngleV2DeleteCleanupReport(entries = entries)
    }

    /**
     * `null` sólo si la clave no tiene referencias persistidas y la consulta funcionó.
     *
     * **Cero es la única lectura que autoriza reclamar.** Un contador negativo es imposible
     * (`COUNT(*)` no puede serlo) y no se interpreta como «sin referencias»: se trata como consulta
     * no fiable y la imagen se conserva. Cualquier otro valor se compara exactamente con 0.
     */
    private suspend fun referenceRetention(imageKey: String): AngleV2DeleteRetentionReason? =
        try {
            val references = dao.frameImageReferenceCount(imageKey)
            when {
                references > 0 -> AngleV2DeleteRetentionReason.StillReferenced(referenceCount = references)
                references == 0 -> null
                else -> AngleV2DeleteRetentionReason.ReferenceLookupFailed
            }
        } catch (_: Exception) {
            // Incluye cancelación de un DAO hostil: el tramo es no cancelable y el commit ya ocurrió,
            // así que no se puede afirmar "sin referencias" y se conserva el fichero.
            AngleV2DeleteRetentionReason.ReferenceLookupFailed
        }

    private suspend fun reclaimOne(
        frameOrdinal: Int,
        image: AngleAnalysisV2OwnedImage,
    ): AngleV2DeleteCleanupEntry {
        // La integridad esperada es **exactamente** la que declaraba la fila del fotograma; la ruta
        // no la elige esta capa (el almacén sólo acepta una clave canónica bajo su raíz).
        val expected = AngleImageIntegrity(
            byteCount = image.imageByteCount,
            sha256Hex = image.imageSha256,
        )
        val result = try {
            reclaim(image.imageKey, expected)
        } catch (_: Exception) {
            // Defensivo: la costura real devuelve tipados. Un fallo inesperado no se disfraza de
            // "conservado" ni de "retirado": queda incierto.
            return AngleV2DeleteCleanupEntry.Unconfirmed(
                frameOrdinal = frameOrdinal,
                observed = AngleV2DeleteObservation.UNKNOWN,
                cause = AngleV2DeleteStoreFailure.OTHER,
            )
        }
        return when (result) {
            is AngleImageReclaimResult.Removed -> AngleV2DeleteCleanupEntry.Removed(
                frameOrdinal = frameOrdinal,
                removalEntriesSynced = result.durability.removalEntriesSynced,
                keyDirectoryRetained = result.directoryRetained,
            )

            is AngleImageReclaimResult.Absent ->
                AngleV2DeleteCleanupEntry.Absent(frameOrdinal = frameOrdinal)

            is AngleImageReclaimResult.Retained -> AngleV2DeleteCleanupEntry.Retained(
                frameOrdinal = frameOrdinal,
                reason = result.reason.toRetentionReason(),
            )

            is AngleImageReclaimResult.Unconfirmed -> AngleV2DeleteCleanupEntry.Unconfirmed(
                frameOrdinal = frameOrdinal,
                observed = result.observed.toObservation(),
                cause = result.reason.toStoreFailure(),
            )
        }
    }

    private fun AngleImageReclaimObservation.toObservation(): AngleV2DeleteObservation = when (this) {
        AngleImageReclaimObservation.ABSENT -> AngleV2DeleteObservation.ABSENT
        AngleImageReclaimObservation.PRESENT -> AngleV2DeleteObservation.PRESENT
        AngleImageReclaimObservation.UNKNOWN -> AngleV2DeleteObservation.UNKNOWN
    }

    private fun AngleImageReclaimRefusal.toRetentionReason(): AngleV2DeleteRetentionReason =
        when (this) {
            is AngleImageReclaimRefusal.StoreError ->
                AngleV2DeleteRetentionReason.StoreRefused(error.toStoreFailure())

            is AngleImageReclaimRefusal.UnexpectedKeyDirectoryContent ->
                AngleV2DeleteRetentionReason.UnexpectedDirectoryContent
        }

    private fun AngleImageStoreError.toStoreFailure(): AngleV2DeleteStoreFailure = when (this) {
        is AngleImageStoreError.InvalidKey -> AngleV2DeleteStoreFailure.INVALID_KEY
        is AngleImageStoreError.InvalidIntegrityExpectation ->
            AngleV2DeleteStoreFailure.INVALID_INTEGRITY_EXPECTATION

        is AngleImageStoreError.RootDirectoryUnusable -> AngleV2DeleteStoreFailure.ROOT_DIRECTORY_UNUSABLE
        is AngleImageStoreError.UnsafePath -> AngleV2DeleteStoreFailure.UNSAFE_PATH
        is AngleImageStoreError.Corrupted -> AngleV2DeleteStoreFailure.CORRUPTED
        is AngleImageStoreError.LimitsConflict -> AngleV2DeleteStoreFailure.LIMITS_CONFLICT
        is AngleImageStoreError.IoFailure -> AngleV2DeleteStoreFailure.IO_FAILURE
        else -> AngleV2DeleteStoreFailure.OTHER
    }

    /**
     * Copia profunda de la instantánea: raíz, cada fotograma, cada marca y las listas. La prueba de
     * confirmación no comparte ninguna estructura con la lectura del DAO (ni con la caché de un
     * fake), de modo que lo que se ejecuta es exactamente lo que se preparó.
     */
    private fun AngleAnalysisV2StoredAggregate.immutableCopy(): AngleAnalysisV2StoredAggregate =
        AngleAnalysisV2StoredAggregate(
            root = root.copy(),
            frames = frames.map { stored ->
                AngleAnalysisV2StoredFrame(
                    frame = stored.frame.copy(),
                    marks = stored.marks.map { mark -> mark.copy() }.toList(),
                )
            }.toList(),
        )
}
