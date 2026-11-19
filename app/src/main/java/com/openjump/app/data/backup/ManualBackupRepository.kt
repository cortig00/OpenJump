package com.openjump.app.data.backup

import com.openjump.app.data.angles.AngleMediaCoordination
import com.openjump.app.data.angles.AngleMediaRestoreEpoch
import com.openjump.app.data.angles.withAngleRestoreInvalidation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Puerta del backup manual: captura/validacion, contenedor ZIP con imagenes, lectura estricta y
 * restauracion.
 *
 * **Frontera de propiedad de una restauracion confirmada.** [restoreMedia] y [restore] son
 * operaciones `suspend` que la restauracion confirmada de producto **no** ejecuta en el ambito del
 * ViewModel. [startConfirmedRestore] las corre en [confirmedRestoreScope] (ambito de aplicacion)
 * con duracion completa no cancelable, cierra la carga en su propio `finally` y entrega el
 * resultado tipado a `onFinished` aunque el ViewModel que la pidio ya se haya destruido. La
 * reconciliacion posterior (sesion de video, atleta seleccionado, permisos persistentes) tambien
 * corre ahi, via [afterConfirmedRestore]: no queda estado de aplicacion sin reconciliar contra los
 * datos restaurados, y el temporal de la carga tiene un unico duenio en cada momento.
 *
 * **Agregado V2 sobre cualquier formato.** Un documento v1 no transporta el agregado
 * multi-fotograma, y un contenedor ZIP sustituye el agregado actual por el que trae (o por ninguno):
 * en los dos casos se pierde el agregado que hay ahora en la base. Por eso ese caso necesita dos
 * confirmaciones: la de restaurar y la de la advertencia con datos ([angleV2ReplaceWarning] ->
 * [startConfirmedRestore] `angleV2Discard`), que identifica el agregado completo que se descarta
 * (identidad y contenido de todas las filas, no solo conteos). Sin esa segunda confirmacion no hay
 * via: el DAO rechaza de forma atomica (agregado actual no vacio) y se informa reversion. Si la
 * advertencia fue nula porque el agregado estaba vacio, el DAO exige que siga vacio antes del primer
 * `DELETE`, asi que un agregado que aparezca en medio tampoco se destruye. Imagenes y ficheros
 * previos no se borran nunca; los que esta operacion publico y quedan sin fila se informan como
 * huerfanos ([ManualBackupRestoreRun.NotApplied]) sin prometer ninguna recogida automatica.
 *
 * **Lo que esta frontera no hace.** No entrega el resultado a un llamante cancelado: eso lo
 * garantizan el ambito de aplicacion y el callback, no `withContext(NonCancellable + IO)` (que solo
 * impide que una cancelacion interrumpa el trabajo; un llamante cancelado sigue descartando el
 * valor devuelto). No promete atomicidad entre Room y disco y no recoge imagenes huerfanas.
 * @param afterConfirmedRestore reconciliacion de aplicacion posterior a un restore aplicado.
 * @param coordinationMutex mutex global del proceso ([AngleMediaCoordination.mutex]): el restore
 *   legacy sin advertencia de [restore] sustituye el agregado V2 que una pagina pudo mostrar, asi
 *   que participa de la misma exclusion que el guardado/backup/borrado.
 * @param restoreEpoch generacion de restauracion compartida; [restore] la mueve bajo el mutex
 *   **antes** de `replaceAll`, para que una prueba de borrado preparada con una pagina anterior no
 *   pueda aplicarse sobre la raiz restaurada (aunque el restore acabe fallando).
 */
class ManualBackupRepository(
    private val dao: ManualBackupDao,
    private val media: MediaBackupCoordinator? = null,
    /** Ambito de aplicacion; `null` (cableado de test sin restauraciones confirmadas) rechaza el arranque. */
    private val confirmedRestoreScope: CoroutineScope? = null,
    /** Reconciliacion de aplicacion posterior a un restore aplicado; obligatoria si hay ambito. */
    private val afterConfirmedRestore: (suspend () -> Unit)? = null,
    private val coordinationMutex: Mutex = AngleMediaCoordination.mutex,
    private val restoreEpoch: AngleMediaRestoreEpoch = AngleMediaCoordination.restoreEpoch,
) {
    suspend fun snapshot(): ManualBackupDocument = dao.snapshot().validate()

    /**
     * Exporta con el coordinador de medios cuando esta configurado: backup completo (legacy + V2
     * con imagenes verificadas) bajo el mutex global, **siempre** como contenedor ZIP, tambien sin
     * imagenes. La UI crea el documento de destino (nombre y MIME) antes de llamar aqui, asi que el
     * formato no puede variar con el estado de la base. Sin coordinador (no es el cableado de
     * producto, ver `OpenJumpApp`) se mantiene el JSON legacy para no cambiar el contrato de test.
     */
    suspend fun export(output: OutputStream): MediaBackupExportSummary =
        media?.export(output) ?: MediaBackupExportSummary(
            summary = ManualBackupSummary.from(create(snapshot(), output)),
            imageCount = 0,
            mediaIncluded = false,
            archiveByteCount = 0L,
        )

    /**
     * Lee un backup con deteccion estricta de formato (JSON legacy o contenedor con imagenes).
     * Sin coordinador solo se admite el JSON legacy; el resultado no tiene recursos que cerrar.
     */
    suspend fun load(input: InputStream, declaredLength: Long? = null): MediaBackupLoad =
        media?.load(input, declaredLength) ?: MediaBackupLoad.Legacy(read(input, declaredLength))

    /** Restaura una carga con imagenes ya verificada; nunca se aplica sin imagenes durables. */
    suspend fun restoreMedia(
        load: MediaBackupLoad.Media,
        confirmedAngleV2: AngleV2DiscardWarning? = null,
    ): MediaBackupRestoreOutcome =
        requireNotNull(media) { "media backup restore needs a configured coordinator" }
            .restore(load, confirmedAngleV2?.snapshot)

    /**
     * Advertencia con datos si la base tiene ahora mismo filas V2 que la restauracion de [load]
     * sustituiria; `null` si el agregado esta vacio (entonces la comprobacion atomica del DAO exige
     * que siga vacio) o si no hay coordinador configurado.
     *
     * Vale para **cualquier** formato: un JSON v1 no transporta el agregado y un ZIP lo sustituye
     * por el suyo, asi que en los dos casos lo que hay ahora se pierde.
     *
     * Es informativa, no una autorizacion: el estado que devuelve se revalida bajo el mutex y dentro
     * de la transaccion de Room al aplicarlo, comparando la instantanea **completa** del agregado
     * (identidad y contenido de todas las filas V2, no solo sus conteos). Solo sirve para que la UI
     * pueda explicar en un dialogo **aparte** lo que se va a perder y pedir una confirmacion distinta
     * de la de restaurar.
     */
    suspend fun angleV2ReplaceWarning(load: MediaBackupLoad): AngleV2DiscardWarning? = media?.angleV2ReplaceWarning()

    /**
     * Nombre historico de [angleV2ReplaceWarning] (cuando solo se advertia para JSON legacy). Se
     * mantiene para llamantes y tests existentes; la semantica ya cubre cualquier formato.
     */
    suspend fun legacyAngleV2DiscardWarning(load: MediaBackupLoad): AngleV2DiscardWarning? = angleV2ReplaceWarning(load)

    suspend fun create(output: OutputStream): ManualBackupDocument {
        return create(snapshot(), output)
    }

    /** Writes exactly the already captured document, so its preview cannot drift from the file. */
    fun create(document: ManualBackupDocument, output: OutputStream): ManualBackupDocument {
        val validated = document.validate()
        ManualBackupCodec.encodeTo(validated, output)
        return validated
    }

    fun read(input: InputStream, declaredLength: Long? = null): ManualBackupDocument =
        ManualBackupCodec.decode(input, declaredLength)

    fun preview(input: InputStream, declaredLength: Long? = null): ManualBackupSummary =
        ManualBackupSummary.from(read(input, declaredLength))

    /**
     * Aplica un documento legacy **sin** advertencia de agregado V2 (no abre ningun fichero).
     *
     * Sustituye filas V2 si las hubiera, asi que participa de la misma exclusion y de la misma
     * generacion que el resto de restauraciones: toma [coordinationMutex], mueve la generacion y
     * solo entonces llama al DAO. La invalidacion se conserva aunque `replaceAll` falle (lado
     * seguro: obliga a reconfirmar, nunca autoriza de mas). La barrera V2 del DAO no se relaja: si
     * hay filas V2 y el usuario no confirmo la advertencia, la transaccion se rechaza como hasta
     * ahora.
     */
    suspend fun restore(document: ManualBackupDocument): ManualBackupSummary {
        val valid = document.validate()
        // Cancelacion antes de entrar: no se mueve la generacion ni se escribe nada.
        currentCoroutineContext().ensureActive()
        return withAngleRestoreInvalidation(coordinationMutex, restoreEpoch) {
            dao.replaceAll(valid)
            ManualBackupSummary.from(valid)
        }
    }

    /**
     * Arranca una restauracion **ya confirmada por el usuario** como operacion del ambito de
     * aplicacion. No hay ninguna via aqui que restaure sin confirmacion: el llamante solo llega
     * despues de que el usuario confirme sobre una carga ya leida y verificada.
     *
     * Contrato de propiedad:
     * - devuelve `true`: la carga (y su temporal del lector) pasa a ser de la operacion; la cierra
     *   ella, exactamente una vez, en su `finally` no cancelable, **despues** de terminar de usarla.
     *   `onFinished` se invoca siempre con un resultado tipado, tambien si el ViewModel ya no existe.
     * - devuelve `false`: la operacion se rechazo **antes de empezar** (sin ambito configurado o
     *   ambito ya cancelado). No se toco nada y el llamante conserva la propiedad de la carga, que
     *   debe cerrar el mismo: no queda fuga ni trabajo a medias.
     *
     * Una vez arrancada, ni la cancelacion del ambito ni la destruccion del ViewModel interrumpen
     * la restauracion ni pierden su resultado: el cuerpo corre bajo [NonCancellable] y el callback
     * se ejecuta fuera del tramo cancelable.
     *
     * [angleV2Discard] es la **segunda** confirmacion, la de la advertencia que solo se muestra
     * cuando hay filas V2 en la base (para **cualquier** formato de carga): es el objeto que entrego
     * [angleV2ReplaceWarning] y que el usuario acepto explicitamente. Identifica el agregado
     * completo (conteos **y** contenido de todas sus filas), asi que si otra restauracion o un
     * guardado lo sustituye antes de aplicarlo —incluso dejando los mismos conteos— no se descarta
     * nada y se informa [ManualBackupRestoreRun.Reverted] (sin imagenes que reportar) o
     * [ManualBackupRestoreRun.NotApplied] (con las claves huerfanas que se conservaron). Con `null`
     * no hay ninguna via privilegiada: el DAO exige de forma atomica que el agregado siga vacio y,
     * si no lo esta, se informa reversion sin haber borrado nada.
     */
    fun startConfirmedRestore(
        load: MediaBackupLoad,
        angleV2Discard: AngleV2DiscardWarning? = null,
        onFinished: (ManualBackupRestoreRun) -> Unit,
    ): Boolean {
        val scope = confirmedRestoreScope ?: return false
        val housekeeping = afterConfirmedRestore ?: return false
        val job = scope.coroutineContext[Job]
        if (job != null && !job.isActive) return false
        // ATOMIC: si el ambito se cancela justo despues de la comprobacion, el cuerpo arranca
        // igualmente y termina bajo NonCancellable; no se queda una carga sin duenio.
        scope.launch(start = CoroutineStart.ATOMIC) {
            // El resultado se captura *dentro* del tramo no cancelable: si la reanudacion de
            // `withContext` se perdiera porque el ambito ya estaba cancelado, no se informa una
            // reversion falsa de una sustitucion que si se aplico.
            var applied: ManualBackupRestoreRun? = null
            val outcome = try {
                withContext(NonCancellable) {
                    applyConfirmedRestore(load, angleV2Discard, housekeeping).also { applied = it }
                }
            } catch (_: Exception) {
                // Fallo no tipado (incluida una cancelacion inesperada dentro del tramo no
                // cancelable): no se puede afirmar que la sustitucion se aplicara, asi que nunca
                // se informa exito. El coordinador ya devuelve tipados todos los fallos esperables.
                applied ?: ManualBackupRestoreRun.Reverted
            } finally {
                // La carga es de esta operacion desde el traspaso: se cierra siempre y solo aqui,
                // nunca en vuelo. Un fallo de limpieza no convierte el resultado en exito ni en
                // fallo: se descarta el error, como el resto del cierre de temporales.
                withContext(NonCancellable) { runCatching { load.close() } }
            }
            onFinished(outcome)
        }
        return true
    }

    /**
     * Aplica la sustitucion y la reconciliacion de aplicacion. Corre siempre bajo [NonCancellable]
     * (ver [startConfirmedRestore]).
     */
    private suspend fun applyConfirmedRestore(
        load: MediaBackupLoad,
        angleV2Discard: AngleV2DiscardWarning?,
        housekeeping: suspend () -> Unit,
    ): ManualBackupRestoreRun {
        val summary = try {
            when (load) {
                is MediaBackupLoad.Legacy -> {
                    val coordinator = media
                    if (angleV2Discard != null && coordinator != null) {
                        // Via confirmada en dos pasos: el documento no transporta analisis V2 y el
                        // usuario ha aceptado explicitamente perder los que hay (y ha visto cuales).
                        when (val outcome = coordinator.replaceLegacyDiscardingAngleV2(load, angleV2Discard)) {
                            is MediaBackupRestoreOutcome.Restored -> outcome.summary
                            // No se sustituyo ninguna fila: los datos locales se conservan intactos.
                            is MediaBackupRestoreOutcome.NotReplaced -> return notApplied(outcome)
                        }
                    } else {
                        // Sin la segunda confirmacion no hay via: si hay filas V2 la barrera del DAO
                        // lanza y se informa reversion sin haber tocado nada.
                        restore(load.document)
                    }
                }
                is MediaBackupLoad.Media -> when (val outcome = restoreMedia(load, angleV2Discard)) {
                    is MediaBackupRestoreOutcome.Restored -> outcome.summary
                    // No se sustituyo ninguna fila: "local data was preserved" es literal, y las
                    // imagenes que esta operacion publico se informan como huerfanas.
                    is MediaBackupRestoreOutcome.NotReplaced -> return notApplied(outcome)
                }
            }
        } catch (_: Exception) {
            // Solo la operacion transaccional del repositorio puede informar de una reversion.
            return ManualBackupRestoreRun.Reverted
        }
        val housekeepingFailed = try {
            housekeeping()
            false
        } catch (_: Exception) {
            true
        }
        return ManualBackupRestoreRun.Applied(summary, housekeepingFailed)
    }

    /**
     * Traduce un rechazo tipado del coordinador al resultado que consume la UI. Si la operacion habia
     * publicado imagenes, se conservan y se **reportan** aqui ([ManualBackupRestoreRun.NotApplied])
     * en lugar de colapsarlas en [ManualBackupRestoreRun.Reverted]: el usuario debe saber que hay
     * ficheros que quedaron sin fila. Las claves son opacas (no contienen datos personales) y no se
     * escriben en ningun log; el llamante decide como informarlas.
     */
    private fun notApplied(outcome: MediaBackupRestoreOutcome.NotReplaced): ManualBackupRestoreRun =
        if (outcome.preservedImageKeys.isEmpty() && outcome.unsyncedImageKeys.isEmpty()) {
            ManualBackupRestoreRun.Reverted
        } else {
            ManualBackupRestoreRun.NotApplied(
                reason = outcome.reason,
                preservedImageKeys = outcome.preservedImageKeys,
                unsyncedImageKeys = outcome.unsyncedImageKeys,
                detail = outcome.detail,
            )
        }
}

/** Resultado tipado de una restauracion confirmada ejecutada como operacion de aplicacion. */
sealed interface ManualBackupRestoreRun {

    /** Las filas se sustituyeron; [housekeepingFailed] avisa de que la reconciliacion posterior fallo. */
    data class Applied(val summary: ManualBackupSummary, val housekeepingFailed: Boolean) : ManualBackupRestoreRun

    /** No se sustituyo ninguna fila: los datos locales de antes se conservan y no hay ficheros huerfanos. */
    data object Reverted : ManualBackupRestoreRun

    /**
     * No se sustituyo ninguna fila y la operacion habia publicado imagenes que se **conservan**:
     * quedan en el almacen privado sin ninguna fila que las referencie.
     *
     * [preservedImageKeys] son claves opacas (no contienen rutas, nombres ni datos personales) de las
     * imagenes publicadas; [unsyncedImageKeys] son las que el almacen no pudo confirmar como
     * duraderas. Las claves se conservan para diagnostico y tests; la UI solo informa del numero. No
     * hay ninguna recogida automatica de huerfanos, asi que esto no promete que se vayan a borrar ni
     * que sobrevivan a un desinstalado o a una limpieza del almacen.
     */
    data class NotApplied(
        val reason: MediaBackupRestoreFailureReason,
        val preservedImageKeys: List<String>,
        val unsyncedImageKeys: List<String>,
        val detail: String?,
    ) : ManualBackupRestoreRun
}
