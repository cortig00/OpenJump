package com.openjump.app.data.angles

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Generación **de proceso** de restauración del agregado V2.
 *
 * **Para qué existe.** Una página de historial (o un diálogo de confirmación) se compone con datos
 * leídos en un instante. Si después una restauración —ZIP con o sin imágenes, JSON legacy
 * confirmado o JSON legacy sin advertencia— sustituye el agregado, esa página deja de describir la
 * base: sus `rootId` pueden apuntar ahora a **otra** raíz (los ids se reciclan) y una confirmación
 * antigua borraría algo que el usuario no vio. El token no identifica filas ni autoriza nada: es la
 * etiqueta que permite afirmar «lo que confirmaste es de esta generación» o rechazarlo.
 *
 * **Quién lo mueve.** Sólo las restauraciones reales, con [newTokenLocked] y **siempre** bajo
 * [AngleMediaCoordination.mutex] (el mismo mutex de guardado/backup/borrado), **antes** de
 * cualquier reemplazo que pueda cambiar la identidad del agregado. Las vías cubiertas son
 * [com.openjump.app.data.backup.MediaBackupCoordinator.restore] (contenedor ZIP, también vacío de
 * imágenes), [com.openjump.app.data.backup.MediaBackupCoordinator.replaceLegacyDiscardingAngleV2]
 * (legacy confirmado) y [com.openjump.app.data.backup.ManualBackupRepository.restore] (legacy sin
 * advertencia). Un restore que falla después de mover el token **no** lo devuelve: invalidar de más
 * sólo obliga a reconfirmar, que es el lado seguro.
 *
 * **Lo que no promete.** Es estado en memoria: no sobrevive a la muerte del proceso ni coordina otro
 * proceso/UID. No detecta un `DELETE`/cascada ajeno al coordinador (por ejemplo el borrado
 * permanente de un atleta): ese caso lo detecta la relectura exacta del DAO, que devuelve
 * `Missing`/`Changed` y exige reconfirmar.
 *
 * **Hilos.** [current] es lectura segura desde cualquier hilo. [newTokenLocked] **exige** tener
 * tomado el mutex de coordinación; el nombre lo declara porque la exclusión no se puede comprobar
 * desde aquí y sin ella dos restauraciones podrían intercalar el token con el reemplazo.
 */
class AngleMediaRestoreEpoch(private val tokens: AtomicLong = AtomicLong()) {

    /** Token vigente. Nunca decrece; `0` es la generación inicial (proceso recién arrancado). */
    fun current(): Long = tokens.get()

    /**
     * Abre una generación nueva y devuelve su token.
     *
     * Debe llamarse **bajo** [AngleMediaCoordination.mutex] y antes de empezar el reemplazo, no
     * después: una página leída bajo el mutex lleva el token vigente y cualquier restore posterior
     * (que también toma el mutex) la invalida.
     */
    fun newTokenLocked(): Long = tokens.incrementAndGet()
}

/**
 * Página leída **atómicamente** con la generación de restauración en la que se leyó.
 *
 * [restoreToken] es el token que una confirmación posterior debe presentar en
 * [AngleV2DeleteRepository.prepare]: si una restauración ocurrió entre la lectura y la
 * confirmación, la preparación la rechaza en vez de retargetear otra raíz.
 */
data class AngleHistoryObservation<T>(val value: T, val restoreToken: Long)

/**
 * Ejecuta [read] bajo [mutex] y devuelve su valor junto con el token de restauración **de esa misma
 * sección crítica**.
 *
 * Es la costura genérica que el historial V2 (S4) debe usar para leer su página: el token se toma
 * antes y después de la consulta dentro del mutex, así que la página real y la generación no pueden
 * mezclarse (leer la consulta fuera del mutex y el token después aceptaría datos de antes de un
 * restore con el token de después). Bajo el mutex ambos tokens coinciden; si una escritura llamara
 * a [AngleMediaRestoreEpoch.newTokenLocked] sin el mutex —un error de cableado— se devuelve el
 * **menor** de los dos, que invalida la confirmación en lugar de aceptarla.
 */
suspend fun <T> observeAngleHistoryAtomically(
    mutex: Mutex = AngleMediaCoordination.mutex,
    restoreEpoch: AngleMediaRestoreEpoch = AngleMediaCoordination.restoreEpoch,
    read: suspend () -> T,
): AngleHistoryObservation<T> = mutex.withLock {
    val tokenBefore = restoreEpoch.current()
    val value = read()
    AngleHistoryObservation(value = value, restoreToken = minOf(tokenBefore, restoreEpoch.current()))
}

/**
 * Toma [mutex], abre una generación de restauración y sólo entonces ejecuta [replace].
 *
 * Es la vía para restauraciones que **no** tienen ya el mutex tomado (el restore legacy sin
 * advertencia de [com.openjump.app.data.backup.ManualBackupRepository]). No se puede llamar desde
 * dentro de otra sección del mismo mutex ([Mutex] no es reentrante): las vías que ya lo tienen
 * (coordinador de medios) llaman a [AngleMediaRestoreEpoch.newTokenLocked] directamente.
 *
 * La cancelación del llamante se observa **antes** de entrar: el mutex se adquiere de forma
 * cancelable, así que una corrutina ya cancelada no muta nada. Una vez dentro, el token se mueve
 * antes de [replace]: si el reemplazo falla, la invalidación se conserva (lado seguro).
 */
suspend fun <T> withAngleRestoreInvalidation(
    mutex: Mutex = AngleMediaCoordination.mutex,
    restoreEpoch: AngleMediaRestoreEpoch = AngleMediaCoordination.restoreEpoch,
    replace: suspend () -> T,
): T = mutex.withLock {
    restoreEpoch.newTokenLocked()
    replace()
}
