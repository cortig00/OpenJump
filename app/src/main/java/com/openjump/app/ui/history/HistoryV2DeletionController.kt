package com.openjump.app.ui.history

import com.openjump.app.data.angles.AngleV2DeleteFailure
import com.openjump.app.data.angles.AngleV2DeletePreparation
import com.openjump.app.data.angles.AngleV2DeleteProof
import com.openjump.app.data.angles.AngleV2DeleteRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Confirmación V2 en curso, ya preparada: lo único que la interfaz necesita para preguntar.
 *
 * No contiene la prueba ([AngleV2DeleteProof]): la prueba vive SÓLO en memoria del controlador y no
 * se expone a la composición ni a ningún estado guardable. [frameCount] es la cuenta real del
 * agregado que se leyó en la preparación (no la de una página antigua).
 */
internal data class HistoryV2DeleteConfirmation(val rootId: Long, val frameCount: Int)

/**
 * Aviso posterior a un intento de borrado V2, independiente de la confirmación.
 *
 * Ninguno de estos avisos reintenta ni borra por sí solo:
 * - [Reconfirm]: la prueba ya no vale (restauración/generación, raíz cambiada o ausente). La lista se
 *   refresca y el usuario tiene que volver a pedir el borrado; nunca se reapunta a otra raíz.
 * - [Retryable]: no se pudo completar **antes** de confirmar el borrado (fallo de Room al preparar o
 *   al ejecutar). El reintento reutiliza la MISMA prueba si existe; si el fallo fue al preparar, se
 *   vuelve a preparar con la misma generación observada (nunca con una nueva), así que una
 *   restauración ocurrida mientras tanto se rechaza antes de leer la raíz. Descartar este aviso
 *   libera la prueba/petición retenida, para que la interfaz no quede bloqueada para siempre.
 * - [CleanupIncomplete]: el borrado SÍ se confirmó y la fila desaparece, pero quedaron imágenes
 *   privadas conservadas/sin confirmar o directorios sin retirar. Es informativo: no hay reintento
 *   de borrado ni de limpieza.
 */
internal sealed interface HistoryV2DeleteNotice {
    data object Reconfirm : HistoryV2DeleteNotice

    data object Retryable : HistoryV2DeleteNotice

    data class CleanupIncomplete(
        /** Imágenes que se conservaron a propósito (referencia viva o consulta no fiable). */
        val retained: Int,
        /** Retiradas no confirmadas: `unconfirmed` + retiradas cuyo `fsync` no se confirmó. */
        val unconfirmed: Int,
        /** Directorios de clave que no se pudieron retirar (pueden quedar vacíos). */
        val directories: Int,
    ) : HistoryV2DeleteNotice
}

/**
 * Controlador de producción del borrado V2 confirmado desde HISTORY: preparación, confirmación
 * explícita, ejecución con dueño de aplicación y publicación del desenlace.
 *
 * **Qué garantiza.**
 * - Sin generación observada por la fila ([AngleAnalysisHistoryEntry.restoreToken]) no se prepara
 *   nada: un id V2 suelto nunca es autoridad de borrado.
 * - [prepare] es de sólo lectura. La prueba resultante se guarda en MEMORIA del controlador
 *   ([pendingProof]); no se serializa, no se guarda en `SavedStateHandle` y no se reconstruye por id,
 *   así que tras recrear la pantalla se pide confirmación nueva.
 * - La ejecución NO corre en `viewModelScope`: se delega en [startDelete], que en producción es
 *   `AngleV2DeleteOwnership.start(app.angleV2DeleteRepository, proof)`. Cerrar la pantalla no pierde
 *   el informe del borrado ya confirmado (el dueño de aplicación lo registra aparte).
 * - Un solo vuelo: `preparing`/`executing` se comprueban y se marcan bajo el mismo candado, así que
 *   un doble toque o un doble confirmar producen **una** mutación. El candado se libera siempre
 *   (también si el dueño de aplicación está inactivo) y sin mutarlo desde E/S sin protección.
 * - Cancelar/descartar sólo limpia estado en memoria: cero filas y cero ficheros.
 * - Los desenlaces se publican en [mainDispatcher] (hilo principal en producción); el candado se
 *   libera antes de publicar, para no retenerlo mientras corre código de interfaz.
 *
 * **Qué no hace.** No refresca por su cuenta (delega en [onRefresh]), no reintenta cleanup parcial,
 * no recolecta huérfanos y no vuelve a apuntar a otra raíz tras `Stale/Changed/Missing`.
 *
 * @param scope ámbito de preparación y de publicación (en producción `viewModelScope`).
 * @param prepare preparación real (`AngleV2DeleteRepository.prepare`), inyectable en tests.
 * @param startDelete arranque con dueño de aplicación; `false` si no había ámbito activo.
 * @param onRefresh recarga de la lista; se invoca tras commit y tras `Stale/Changed/Missing`.
 */
internal class HistoryV2DeletionController(
    private val scope: CoroutineScope,
    private val prepare: suspend (Long, Long) -> AngleV2DeletePreparation,
    private val startDelete: (AngleV2DeleteProof, (AngleV2DeleteRun) -> Unit) -> Boolean,
    private val onRefresh: () -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {

    private val _confirmation = MutableStateFlow<HistoryV2DeleteConfirmation?>(null)
    val confirmation: StateFlow<HistoryV2DeleteConfirmation?> = _confirmation.asStateFlow()

    private val _notice = MutableStateFlow<HistoryV2DeleteNotice?>(null)
    val notice: StateFlow<HistoryV2DeleteNotice?> = _notice.asStateFlow()

    private val lock = Any()

    /** Autoridad efímera: sólo memoria. `null` cuando no hay confirmación pendiente. */
    private var pendingProof: AngleV2DeleteProof? = null

    /**
     * Último (raíz, generación observada) pedido; permite reintentar una preparación fallida.
     *
     * Sólo se conserva cuando la preparación falló sin mutar nada ([AngleV2DeletePreparation.Failed])
     * y se olvida en cuanto la prueba deja de valer o la raíz no está: nunca se reapunta a la raíz
     * que ocupe ese número ahora.
     */
    private var lastRequest: Pair<Long, Long>? = null

    private var preparing = false
    private var executing = false

    /** Invalida preparaciones en vuelo cuando el usuario cancela o pide otro objetivo. */
    private var requestSerial = 0L

    /**
     * Pide preparar el borrado de [rootId] con la generación que la fila observó al leerse.
     *
     * Sin [observedRestoreToken] no hay autoridad y no se hace nada (falla cerrado). Un segundo
     * toque mientras se prepara, se ejecuta o hay una confirmación abierta se ignora.
     */
    fun requestDelete(rootId: Long, observedRestoreToken: Long?) {
        if (observedRestoreToken == null || rootId <= 0L) return
        val serial = synchronized(lock) {
            if (preparing || executing || pendingProof != null) return
            preparing = true
            lastRequest = rootId to observedRestoreToken
            requestSerial += 1
            requestSerial
        }
        scope.launch {
            val preparation = try {
                prepare(rootId, observedRestoreToken)
            } catch (cancelled: CancellationException) {
                synchronized(lock) { preparing = false }
                throw cancelled
            } catch (_: Exception) {
                // La preparación no muta nada: un fallo inesperado se trata como «no se pudo preparar».
                AngleV2DeletePreparation.Failed(AngleV2DeleteFailure.Database(PREPARATION_FAILURE))
            }
            val prepared = synchronized(lock) {
                preparing = false
                if (serial != requestSerial) return@synchronized null
                (preparation as? AngleV2DeletePreparation.Prepared)?.let { result ->
                    pendingProof = result.proof
                    result.proof
                }
            }
            if (prepared != null) {
                _confirmation.value = HistoryV2DeleteConfirmation(
                    rootId = prepared.rootId,
                    frameCount = prepared.frameCount,
                )
                return@launch
            }
            val outcome = synchronized(lock) {
                if (serial != requestSerial) null else preparation
            } ?: return@launch
            when (outcome) {
                // Fallo de Room al leer: nada se mutó y nada prueba que la fila haya cambiado, así
                // que no se refresca ni se descarta la generación observada. Se conserva la MISMA
                // (raíz, generación) para [retry]; si una restauración la invalida, ese reintento la
                // rechaza antes de leer la raíz.
                is AngleV2DeletePreparation.Failed ->
                    _notice.value = HistoryV2DeleteNotice.Retryable

                // La prueba ya no vale o la raíz no está: se olvida la petición, se refresca y se
                // pide confirmación nueva; nunca se reapunta a la raíz que ocupe ese número ahora.
                is AngleV2DeletePreparation.Stale,
                is AngleV2DeletePreparation.Missing -> {
                    synchronized(lock) { lastRequest = null }
                    onRefresh()
                    _notice.value = HistoryV2DeleteNotice.Reconfirm
                }

                // Inalcanzable: una prueba preparada ya se publicó arriba y el estado no cambia aquí.
                is AngleV2DeletePreparation.Prepared -> Unit
            }
        }
    }

    /** Cancela la confirmación abierta. Cero filas, cero ficheros: sólo se olvida la prueba. */
    fun cancel() {
        synchronized(lock) {
            pendingProof = null
            lastRequest = null
            requestSerial += 1
        }
        _confirmation.value = null
    }

    /** Confirma la prueba preparada y la ejecuta con el dueño de aplicación. */
    fun confirm() = executePendingProof()

    /** Reintento explícito tras un fallo: reutiliza la MISMA prueba si sigue en memoria. */
    fun retry() {
        _notice.value = null
        val proof = synchronized(lock) { pendingProof }
        if (proof != null) {
            executePendingProof()
            return
        }
        val request = synchronized(lock) { lastRequest } ?: return
        requestDelete(request.first, request.second)
    }

    /**
     * Descarta el aviso actual.
     *
     * Un aviso [HistoryV2DeleteNotice.Retryable] conserva en memoria el derecho a reintentar (la
     * prueba exacta o la petición preparada). Descartarlo **libera** ese estado: sin esto la prueba
     * seguiría retenida y toda petición posterior sería un no-op silencioso hasta recrear la
     * pantalla. No ejecuta nada: cero filas y cero ficheros. Los avisos informativos y de
     * reconfirmación sólo ocultan el diálogo y no tocan estado de borrado (una confirmación abierta
     * para otra fila sigue siendo válida).
     */
    fun dismissNotice() {
        if (_notice.value is HistoryV2DeleteNotice.Retryable) {
            synchronized(lock) {
                // El aviso de fallo sólo se publica sin ejecución en vuelo; la comprobación evita
                // descartar la prueba de un borrado ya confirmado que estuviera terminando.
                if (!executing) pendingProof = null
                // Una petición nueva ya en preparación es del usuario y no se invalida al cerrar el
                // aviso; sólo se invalida lo que quedó retenido sin trabajo en curso.
                if (!preparing) {
                    lastRequest = null
                    requestSerial += 1
                }
            }
        }
        _notice.value = null
    }

    private fun executePendingProof() {
        val proof = synchronized(lock) {
            if (executing) return
            val current = pendingProof ?: return
            executing = true
            current
        }
        _confirmation.value = null
        val started = try {
            startDelete(proof) { run -> onFinished(run) }
        } catch (_: Exception) {
            false
        }
        if (!started) {
            // Dueño inactivo o arranque imposible: no se ejecutó nada. Se libera el candado y se
            // ofrece reintentar con la misma prueba.
            synchronized(lock) { executing = false }
            _notice.value = HistoryV2DeleteNotice.Retryable
        }
    }

    /**
     * Desenlace del dueño de aplicación. Puede llegar desde su ámbito de E/S (en producción con la
     * pantalla ya cerrada): aquí sólo se libera el candado y se publica en [mainDispatcher].
     */
    private fun onFinished(run: AngleV2DeleteRun) {
        synchronized(lock) {
            executing = false
            when (run) {
                is AngleV2DeleteRun.Deleted,
                is AngleV2DeleteRun.Stale,
                is AngleV2DeleteRun.Changed,
                is AngleV2DeleteRun.Missing -> pendingProof = null

                // Fallo ANTES de confirmar: la prueba exacta sigue siendo válida para reintentar.
                is AngleV2DeleteRun.Failed -> Unit
            }
        }
        scope.launch(mainDispatcher) { publish(run) }
    }

    private fun publish(run: AngleV2DeleteRun) {
        when (run) {
            is AngleV2DeleteRun.Deleted -> {
                // La fila desaparece aunque la limpieza de ficheros quede incompleta.
                onRefresh()
                val cleanup = run.cleanup
                val unconfirmed = cleanup.unconfirmed + cleanup.unsyncedRemovals
                if (cleanup.retained > 0 || unconfirmed > 0 || cleanup.retainedDirectories > 0) {
                    _notice.value = HistoryV2DeleteNotice.CleanupIncomplete(
                        retained = cleanup.retained,
                        unconfirmed = unconfirmed,
                        directories = cleanup.retainedDirectories,
                    )
                }
            }

            is AngleV2DeleteRun.Failed -> _notice.value = HistoryV2DeleteNotice.Retryable

            is AngleV2DeleteRun.Stale,
            is AngleV2DeleteRun.Changed,
            is AngleV2DeleteRun.Missing -> {
                onRefresh()
                _notice.value = HistoryV2DeleteNotice.Reconfirm
            }
        }
    }

    private companion object {
        /** Tipo acotado del fallo inesperado de preparación; sin mensajes ni rutas. */
        const val PREPARATION_FAILURE: String = "UnexpectedPreparationError"
    }
}
