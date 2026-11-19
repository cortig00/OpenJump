package com.openjump.app.data.angles

import com.openjump.app.data.AngleAnalysisV2StoredAggregate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Prueba **efímera** de una confirmación de borrado V2.
 *
 * La produce [AngleV2DeleteRepository.prepare] a partir de una lectura completa del agregado
 * (raíz + fotogramas + marcas, con ids, claves e integridad persistidas) copiada lista por lista, y
 * la consume [AngleV2DeleteRepository.execute]. **No es una autorización por id**: el DAO relee y
 * compara la instantánea completa dentro de su transacción, así que una raíz recreada con el mismo
 * `rootId` no coincide y no se borra.
 *
 * [restoreToken] es la generación de [AngleMediaRestoreEpoch] que estaba vigente cuando se leyó la
 * página y se preparó la prueba. Si una restauración la invalida, [AngleV2DeleteRepository.execute]
 * devuelve [AngleV2DeleteRun.Stale] sin borrar nada y la UI debe volver a preparar y confirmar.
 *
 * Vive en memoria del ViewModel (S4). No se serializa, no se escribe en `SavedStateHandle` y no
 * sobrevive a la recreación: sin prueba se pide confirmación nueva en vez de reconstruirla por id.
 */
class AngleV2DeleteProof internal constructor(
    internal val snapshot: AngleAnalysisV2StoredAggregate,
    val restoreToken: Long,
) {

    /** Raíz confirmada; sólo informativa para el diálogo y el diagnóstico. */
    val rootId: Long get() = snapshot.root.id

    /** Fotogramas que el usuario confirmó perder (cuenta real del agregado, no un id suelto). */
    val frameCount: Int get() = snapshot.frames.size
}

/**
 * Resultado de [AngleV2DeleteRepository.prepare]: una prueba lista o un motivo tipado.
 *
 * Ninguna variante muta nada: la preparación sólo lee.
 */
sealed interface AngleV2DeletePreparation {

    /** Instantánea completa copiada bajo el mutex; es la única prueba aceptada por el borrado. */
    data class Prepared(val proof: AngleV2DeleteProof) : AngleV2DeletePreparation

    /**
     * La generación de restauración cambió antes de leer la raíz: lo que la UI mostró ya no es
     * necesariamente la base. No se leyó ninguna fila ni se mutó nada; hay que releer y reconfirmar.
     */
    data class Stale(val observedRestoreToken: Long) : AngleV2DeletePreparation

    /** No existe ese agregado (ya se borró o nunca estuvo): no se mutó nada. */
    data class Missing(val rootId: Long) : AngleV2DeletePreparation

    /** No se pudo preparar una prueba fiable; no se mutó nada. */
    data class Failed(val failure: AngleV2DeleteFailure) : AngleV2DeletePreparation
}

/**
 * Fallo tipado de una operación de borrado V2. No incluye rutas, URIs ni valores de usuario: sólo
 * la categoría (y, para la prueba inválida, el texto acotado que produce el propio validador).
 */
sealed interface AngleV2DeleteFailure {

    /**
     * La lectura o la transacción de Room falló **antes** de confirmar un borrado. No se tocó ningún
     * fichero y no consta que se haya borrado ninguna fila (Room revierte su transacción).
     */
    data class Database(val errorType: String) : AngleV2DeleteFailure

    /** El DAO rechazó la prueba (forma, techo de fotogramas, ids, claves…). Sin escribir nada. */
    data class InvalidProof(val detail: String) : AngleV2DeleteFailure
}

/**
 * Cómo quedó **una** imagen propiedad de la raíz borrada, tras el commit en Room.
 *
 * El commit ya ocurrió cuando existe este informe: un fallo de limpieza **nunca** convierte el
 * resultado en "no borrado" ni justifica reintentar el `DELETE`.
 */
sealed interface AngleV2DeleteCleanupEntry {

    /** Fotograma (ordinal en la raíz) al que pertenecía la imagen; no es un dato de usuario. */
    val frameOrdinal: Int

    /**
     * La imagen se verificó (tamaño y SHA-256 declarados por la fila) y se retiró.
     *
     * [removalEntriesSynced] en `false` significa que la retirada está aplicada en proceso pero su
     * persistencia frente a un corte no está confirmada. [keyDirectoryRetained] significa que el
     * directorio de la clave no se pudo retirar (pudo quedar vacío): no hay borrado recursivo ni
     * reintento automático.
     */
    data class Removed(
        override val frameOrdinal: Int,
        val removalEntriesSynced: Boolean,
        val keyDirectoryRetained: Boolean,
    ) : AngleV2DeleteCleanupEntry

    /** No hay fichero en la ruta exacta: resultado idempotente, nada que borrar. */
    data class Absent(override val frameOrdinal: Int) : AngleV2DeleteCleanupEntry

    /** La reclamación se rechazó **antes** de mutar y el fichero se conserva. */
    data class Retained(
        override val frameOrdinal: Int,
        val reason: AngleV2DeleteRetentionReason,
    ) : AngleV2DeleteCleanupEntry

    /**
     * El `unlink` se intentó y su efecto no está confirmado (pudo borrarse o no). [observed] dice
     * qué se pudo observar del fichero exacto; nunca se informa como conservado.
     */
    data class Unconfirmed(
        override val frameOrdinal: Int,
        val observed: AngleV2DeleteObservation,
        val cause: AngleV2DeleteStoreFailure,
    ) : AngleV2DeleteCleanupEntry
}

/**
 * Qué se pudo observar del fichero exacto tras un intento fallido de retirada.
 *
 * Es el espejo acotado del estado interno del almacén: una excepción no demuestra que el fichero
 * siga ahí ni que se haya borrado, y la UI debe poder decir «no confirmado» sin adivinar.
 */
enum class AngleV2DeleteObservation {
    /** La ruta exacta ya no existe: el borrado pudo aplicarse pese a la excepción. */
    ABSENT,

    /** La ruta exacta sigue existiendo: esta operación no la retiró. */
    PRESENT,

    /** No se pudo observar: ni presencia ni ausencia confirmadas. */
    UNKNOWN,
}

/** Motivo **acotado** de conservar una imagen tras el commit. Sin texto libre ni rutas. */
sealed interface AngleV2DeleteRetentionReason {

    /** La consulta de referencias encontró [referenceCount] filas que aún usan la clave: se conserva. */
    data class StillReferenced(val referenceCount: Int) : AngleV2DeleteRetentionReason

    /**
     * La consulta de referencias falló: no se puede afirmar que la clave esté libre, así que se
     * conserva. No se adivina ni se reintenta automáticamente.
     */
    data object ReferenceLookupFailed : AngleV2DeleteRetentionReason

    /** El almacén rechazó la retirada antes de mutar; [failure] es la categoría tipada. */
    data class StoreRefused(val failure: AngleV2DeleteStoreFailure) : AngleV2DeleteRetentionReason

    /** El directorio de la clave no contenía exactamente `image.bin`; no se toca nada. */
    data object UnexpectedDirectoryContent : AngleV2DeleteRetentionReason
}

/** Categoría acotada de un error/ rechazo del almacén; nunca el mensaje (podría traer rutas). */
enum class AngleV2DeleteStoreFailure {
    INVALID_KEY,
    INVALID_INTEGRITY_EXPECTATION,
    ROOT_DIRECTORY_UNUSABLE,
    UNSAFE_PATH,
    CORRUPTED,
    LIMITS_CONFLICT,
    IO_FAILURE,
    OTHER,
}

/**
 * Informe de limpieza de un borrado **ya confirmado** en Room: una entrada por imagen declarada por
 * el agregado, en el orden de los fotogramas. Es el dato que la UI usa para decir la verdad
 * ("borrado; N imágenes no se pudieron retirar") sin convertir un cleanup parcial en un fallo de
 * borrado ni en una promesa de reintento.
 */
data class AngleV2DeleteCleanupReport(val entries: List<AngleV2DeleteCleanupEntry>) {

    val removed: Int get() = entries.count { it is AngleV2DeleteCleanupEntry.Removed }

    val absent: Int get() = entries.count { it is AngleV2DeleteCleanupEntry.Absent }

    val retained: Int get() = entries.count { it is AngleV2DeleteCleanupEntry.Retained }

    val unconfirmed: Int get() = entries.count { it is AngleV2DeleteCleanupEntry.Unconfirmed }

    /** Retiradas cuyo `fsync` de directorio no confirmó la persistencia. */
    val unsyncedRemovals: Int
        get() = entries.count { it is AngleV2DeleteCleanupEntry.Removed && !it.removalEntriesSynced }

    /** Directorios de clave que no se pudieron retirar (pudieron quedar vacíos). */
    val retainedDirectories: Int
        get() = entries.count { it is AngleV2DeleteCleanupEntry.Removed && it.keyDirectoryRetained }

    /** `true` sólo si cada imagen quedó retirada, ausente o confirmada como retirada y durable. */
    val fullyRemoved: Boolean get() = retained == 0 && unconfirmed == 0 && unsyncedRemovals == 0
}

/**
 * Resultado de [AngleV2DeleteRepository.execute].
 *
 * Sólo [Deleted] afirma que la raíz se borró; a partir de ahí el cleanup se informa aparte y **no**
 * cambia el hecho del borrado. Ni [Missing] ni [Changed] ni [Stale] son éxito: exigen releer y
 * reconfirmar sobre el estado actual (nunca retargetear otra raíz).
 */
sealed interface AngleV2DeleteRun {

    /** La raíz confirmada y sus hijos se borraron en una transacción; [cleanup] dice qué pasó con los ficheros. */
    data class Deleted(
        val rootId: Long,
        val cleanup: AngleV2DeleteCleanupReport,
    ) : AngleV2DeleteRun

    /** La raíz existe pero ya no coincide con la prueba exacta. No se escribió nada. */
    data class Changed(val rootId: Long, val detail: String) : AngleV2DeleteRun

    /** La raíz ya no existe (repetición de un borrado o una cascada ajena). No se escribió nada. */
    data class Missing(val rootId: Long) : AngleV2DeleteRun

    /**
     * La generación de restauración cambió respecto a la prueba (o el DAO la rechazó por una lectura
     * previa obsoleta). No se escribió nada; la confirmación debe rehacerse.
     */
    data class Stale(val observedRestoreToken: Long) : AngleV2DeleteRun

    /** Fallo antes de confirmar el borrado. No se tocó ningún fichero. */
    data class Failed(val failure: AngleV2DeleteFailure) : AngleV2DeleteRun
}

/**
 * Informe de una operación de borrado terminada, tal como lo registra
 * [AngleV2DeleteOwnership].
 *
 * [rootId] correlaciona el intento con la raíz confirmada. El resumen de diagnóstico es acotado y
 * **no** contiene claves de imagen, rutas, URIs ni valores de usuario: sólo el desenlace, el id de
 * raíz y los conteos del cleanup.
 */
data class AngleV2DeleteReport(val rootId: Long, val run: AngleV2DeleteRun) {

    fun diagnosticSummary(): String {
        val detail = when (val current = run) {
            is AngleV2DeleteRun.Deleted -> {
                val report = current.cleanup
                "deleted frames=${report.entries.size} removed=${report.removed} absent=${report.absent} " +
                    "retained=${report.retained} unconfirmed=${report.unconfirmed} " +
                    "unsynced=${report.unsyncedRemovals} dirs=${report.retainedDirectories}"
            }

            is AngleV2DeleteRun.Changed -> "changed"
            is AngleV2DeleteRun.Missing -> "missing"
            is AngleV2DeleteRun.Stale -> "stale token=${current.observedRestoreToken}"
            is AngleV2DeleteRun.Failed -> "failed ${current.failure::class.simpleName}"
        }
        return "rootId=$rootId $detail"
    }
}

/**
 * Dueño **de aplicación** de un borrado V2 confirmado.
 *
 * **Por qué existe.** `withContext(NonCancellable)` protege la durabilidad del tramo, pero **no
 * entrega el valor**: la reanudación pertenece al `Job` del llamante y un `Job` cancelado descarta
 * el resultado. Si el borrado corriera en `viewModelScope`, cerrar la pantalla a mitad perdería el
 * único informe de un `DELETE` que quizá ya se confirmó (y de los ficheros que quedaron retenidos).
 * Aquí el trabajo corre en un ámbito de aplicación y el resultado se registra y se entrega con un
 * callback que no depende de que la pantalla siga viva. La cancelación de la UI **antes** de
 * [start] no llega aquí: esta función no es `suspend` y el llamante decide si arranca; una vez
 * arrancado, la cancelación de la UI no interrumpe ni pierde el informe.
 *
 * **Propiedad.** Devuelve `true` sólo si la operación arrancó; con `false` no se ejecutó nada y el
 * llamante conserva el control (puede ejecutar [AngleV2DeleteRepository.execute] por su cuenta, por
 * ejemplo el camino sin dueño de aplicación de los tests).
 *
 * **Cómo se informa de verdad.** Cada intento terminado se registra como [AngleV2DeleteReport] y se
 * emite (a) al sumidero externo [reporter] —en producción el log de diagnóstico— y (b) a una cola
 * acotada de proceso ([drainReports]). Un fallo de cualquiera de los dos no cambia el desenlace del
 * borrado ni impide el otro: el informe ya está registrado antes de emitirlo.
 *
 * **Lo que no hace.** No recolecta imágenes huérfanas, no borra directorios temporales ni
 * reintenta un cleanup parcial: sólo informa. Si el proceso muere, no sobrevive ningún informe (es
 * memoria de proceso); las filas borradas ya no están y los ficheros retenidos siguen ahí.
 */
internal class AngleV2DeleteOwnership(
    /** Ámbito de aplicación; `null` (test sin dueño de aplicación) rechaza [start]. */
    private val scope: CoroutineScope? = null,
    /** Sumidero real del informe; en producción, el log de diagnóstico. */
    private val reporter: ((AngleV2DeleteReport) -> Unit)? = null,
) {

    private val lock = Any()
    private val unconsumedReports = ArrayList<AngleV2DeleteReport>()
    private var completed: AngleV2DeleteRun? = null

    /** Último desenlace terminado en este proceso; `null` mientras no haya terminado ninguno. */
    val lastCompletedRun: AngleV2DeleteRun?
        get() = synchronized(lock) { completed }

    /** Consume los informes pendientes una sola vez. No borra ni reintenta nada. */
    fun drainReports(): List<AngleV2DeleteReport> = synchronized(lock) {
        val drained = unconsumedReports.toList()
        unconsumedReports.clear()
        drained
    }

    /**
     * Arranca [proof] como operación de aplicación y entrega su desenlace a [onFinished].
     *
     * @return `true` si la operación arrancó; `false` si no hay ámbito utilizable (no se ejecutó
     *   nada).
     */
    fun start(
        repository: AngleV2DeleteRepository,
        proof: AngleV2DeleteProof,
        onFinished: (AngleV2DeleteRun) -> Unit = {},
    ): Boolean {
        val scope = scope ?: return false
        val scopeJob = scope.coroutineContext[Job]
        if (scopeJob != null && !scopeJob.isActive) return false
        // ATOMIC: si el ámbito se cancela justo después de comprobar, el cuerpo arranca igual y el
        // tramo no cancelable termina; no se queda un borrado empezado sin informe.
        scope.launch(start = CoroutineStart.ATOMIC) {
            // El resultado se captura **dentro** del tramo no cancelable: si la reanudación se
            // perdiera porque el ámbito ya estaba cancelado, no se inventa un desenlace.
            var finished: AngleV2DeleteRun? = null
            try {
                withContext(NonCancellable) { repository.execute(proof).also { finished = it } }
            } catch (_: CancellationException) {
                // Sin desenlace verificable: no se degrada a éxito ni a "no se borró nada".
            } catch (failure: Exception) {
                finished = AngleV2DeleteRun.Failed(
                    AngleV2DeleteFailure.Database(failure.javaClass.simpleName),
                )
            }
            val run = finished ?: AngleV2DeleteRun.Failed(
                AngleV2DeleteFailure.Database(INTERRUPTED_DELETE_ERROR),
            )
            val report = AngleV2DeleteReport(rootId = proof.rootId, run = run)
            synchronized(lock) {
                // Registro ANTES de emitir: la recuperación no depende de que el llamante siga vivo.
                completed = run
                unconsumedReports += report
                while (unconsumedReports.size > MAX_PENDING_REPORTS) unconsumedReports.removeAt(0)
            }
            // Emisiones independientes, fuera del candado y sin poder cambiar el desenlace.
            try {
                reporter?.invoke(report)
            } catch (_: RuntimeException) {
                // El informe sigue registrado en la cola; el sumidero no decide el resultado.
            }
            try {
                onFinished(run)
            } catch (_: RuntimeException) {
                // Un callback roto no oculta el borrado ya confirmado ni el informe registrado.
            }
        }
        return true
    }

    private companion object {
        const val INTERRUPTED_DELETE_ERROR: String = "InterruptedDeleteWithoutVerifiableRun"

        /** Cota de informes en memoria; al superarla se descarta el más antiguo (diagnóstico, no datos). */
        const val MAX_PENDING_REPORTS: Int = 8
    }
}
