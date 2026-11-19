package com.openjump.app.data.backup

import com.openjump.app.data.angles.AngleImageIntegrity
import com.openjump.app.data.angles.AngleImageReadResult
import com.openjump.app.data.angles.AngleImageStoreError
import com.openjump.app.data.angles.AngleMediaCoordination
import com.openjump.app.data.angles.AngleMediaRestoreEpoch
import com.openjump.app.data.angles.PrivateAngleImageStore
import com.openjump.app.data.angles.StoredAngleImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream

/**
 * Coordinador de producto del backup manual **con imagenes** (G5).
 *
 * Une las piezas que ya existian por separado y que hasta ahora no tenian ningun llamante de
 * produccion: [ManualBackupDao.snapshotWithAngleMedia]/[ManualBackupDao.replaceAllWithAngleMedia]
 * (captura y sustitucion transaccionales), [MediaBackupArchiveWriter]/[MediaBackupArchiveReader]
 * (contenedor ZIP verificado), [MediaBackupImagePreparer] (imagenes restauradas con claves nuevas,
 * probe PNG y durabilidad) y [PrivateAngleImageStore] (almacen privado real).
 *
 * **Un unico mutex global.** Todas las operaciones publicas toman [coordinationMutex], que es el
 * mismo objeto que usa [com.openjump.app.data.angles.AngleV2SaveRepository] para sus guardados
 * ([com.openjump.app.data.angles.AngleMediaCoordination]). Eso da exclusion real entre guardado V2,
 * snapshot/export y restore con imagenes: ninguna de esas operaciones puede observar el estado de
 * las filas V2 de otra. **Lo que NO cubre**, y no se debe suponer: el borrado legacy de analisis
 * ([com.openjump.app.data.AngleRepository]) y el resto de escritores de Room ajenos al agregado V2
 * no toman este mutex, el mutex es por proceso (un segundo proceso o una reinstalacion no lo
 * comparten) y Room y el sistema de ficheros no comparten transaccion. La exclusion es de
 * corrutinas dentro de la app, no una garantia transaccional entre disco y base.
 *
 * **Export.** Bajo el mutex: una sola [ManualBackupDao.snapshotWithAngleMedia] (Room consistente y
 * validado) y **siempre** un ZIP escrito por [MediaBackupArchiveWriter] en [stagingDirectory], con
 * o sin imagenes, volcado al destino con un buffer (nunca el contenedor entero en memoria); las
 * imagenes se leen del almacen privado por clave opaca con
 * [PrivateAngleImageStore.readVerified] (tamano y SHA-256 comprobados dos veces: al leer del
 * almacen y al escribir/verificar el ZIP). El formato no depende del estado de la base: el llamante
 * crea el documento de destino **antes** de exportar (SAF fija nombre y MIME en ese momento), asi
 * que un formato variable produciria un ZIP con extension/MIME de JSON en cuanto hubiera una imagen.
 * Consecuencia aceptada y explicita: las versiones anteriores de la app **no** leen los backups
 * exportados por esta (su lector solo entiende el JSON v1); la lectura de JSON legacy se mantiene
 * intacta para los archivos ya existentes. El temporal se elimina siempre; un fallo de limpieza se
 * informa y no se disfraza de exito.
 *
 * **Lectura.** Estricta en ambos formatos: los primeros bytes deciden el formato (cabecera ZIP
 * local `PK\x03\x04` o ZIP vacio `PK\x05\x06` frente a JSON), el JSON se decodifica con
 * [ManualBackupCodec] sin relajar nada y el ZIP lo valida entero [MediaBackupArchiveReader] antes
 * de devolver nada. La deteccion no consume el flujo: los bytes leidos se reinyectan para que el
 * lector siga viendo el archivo completo (streaming intacto). La carga con imagenes es duena de un
 * temporal y **debe** cerrarse.
 *
 * **Restore con imagenes.** Orden deliberado y fail-closed:
 *  1. el manifiesto ya validado por el lector se revalida y se comprueba que cada fotograma
 *     declara una imagen que el contenedor trae;
 *  2. [MediaBackupImagePreparer] publica cada imagen con una **clave nueva** en el almacen privado,
 *     con probe PNG y `fsync`; si el resultado no es `fullyDurable` o no cubre exactamente los
 *     fotogramas, **no se toca Room**;
 *  3. solo entonces se reescribe el manifiesto (claves nuevas, mismo tamano/SHA-256) y se sustituye
 *     legacy + V2 en **una** transaccion ([ManualBackupDao.replaceAllWithAngleMedia]);
 *  4. si la transaccion falla, las imagenes recien publicadas **se conservan** y se informan como
 *     huerfanas: este coordinador no borra ni recoge huerfanos y **nunca** borra las imagenes
 *     anteriores ni ficheros que no haya creado el. Un backup legacy (JSON) con filas V2 en la base
 *     no entra por [restore] (la barrera del DAO lo rechaza y se informa); la unica via que lo
 *     aplica es [replaceLegacyDiscardingAngleV2], que exige la advertencia [AngleV2DiscardWarning]
 *     leida con [legacyRestoreWarning] y revalida esa misma instantanea (identidad y contenido de
 *     todas las filas V2, no solo conteos) bajo el mutex **y** dentro de la transaccion. Esa via
 *     tampoco borra ficheros: las imagenes anteriores quedan sin fila.
 *
 * **Segunda confirmacion para CUALQUIER formato.** Tanto un JSON v1 como un contenedor ZIP —con el
 * agregado V2 vacio o con analisis propios— sustituyen el agregado V2 que haya ahora en la base. Por
 * eso el producto pide, siempre que el agregado actual no este vacio, una segunda confirmacion
 * distinta de la de restaurar: [angleV2ReplaceWarning] entrega la instantanea completa y [restore]/
 * [replaceLegacyDiscardingAngleV2] la exigen y la revalidan bajo el mutex **y** dentro de la
 * transaccion que borra. Con advertencia nula (agregado actual vacio) la comprobacion atomica sigue
 * siendo obligatoria: el DAO exige que siga vacio antes del primer `DELETE`.
 *
 * Cuando el restore se rechaza **despues** de publicar imagenes, el resultado
 * [MediaBackupRestoreOutcome.NotReplaced] incluye las claves que se conservaron
 * (`preservedImageKeys`) y las que el almacen no pudo confirmar como duraderas
 * (`unsyncedImageKeys`); el repositorio las propaga para que la UI informe de los ficheros huerfanos.
 * Conservar no es prometer durabilidad: nadie los borra automaticamente y no hay recolector todavia.
 *
 * Una vez tomado el mutex, la duracion completa del restore es no cancelable: la cancelacion
 * cooperativa del llamante no puede interrumpirlo entre la publicacion y la transaccion. Eso **no**
 * entrega el resultado a un llamante cancelado; por eso la restauracion confirmada de producto
 * corre en el ambito de aplicacion ([ManualBackupRepository.startConfirmedRestore]), no en el del
 * ViewModel. Ver [restore].
 *
 * **Lo que no hace.** No decide permisos ni SAF (el llamante abre el destino), no activa ningun
 * escritor desde la UI, no publica preferencias, no cifra y no recoge basura. Tampoco promete
 * atomicidad entre Room y disco: la garantia es el orden (imagenes validas y duraderas antes de la
 * transaccion) y la conservacion de todo lo publicado.
 *
 * @param dao puerta transaccional de backup.
 * @param imageStore almacen privado ya construido por el punto de composicion; su raiz debe existir.
 * @param stagingDirectory directorio **privado** del llamante para temporales (se crea si falta).
 *   Nunca debe ser compartido con otros ficheros de usuario ni sincronizado.
 * @param coordinationMutex mutex global compartido con los guardados V2; ver arriba.
 * @param restoreEpoch generacion de restauracion compartida ([com.openjump.app.data.angles.AngleMediaCoordination.restoreEpoch]);
 *   se mueve bajo [coordinationMutex] antes de cualquier sustitucion del agregado V2.
 * @param ioDispatcher dispatcher del trabajo bloqueante; nunca se ejecuta en el hilo principal.
 */
class MediaBackupCoordinator(
    private val dao: ManualBackupDao,
    private val imageStore: PrivateAngleImageStore,
    private val stagingDirectory: File,
    private val coordinationMutex: Mutex,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val restoreEpoch: AngleMediaRestoreEpoch = AngleMediaCoordination.restoreEpoch,
) {

    /**
     * Exporta un backup completo (legacy + imagenes V2 si las hay) a [destination].
     *
     * [destination] lo abre, cierra y vacia el llamante: aqui solo se escriben bytes. Devuelve el
     * resumen de lo exportado; cualquier fallo (snapshot, PNG del almacen, ZIP, destino, limpieza)
     * se propaga como excepcion y nunca como exito parcial silencioso.
     *
     * Memoria: el contenedor NUNCA se materializa entero. Se escribe en un temporal del staging,
     * se vuelca al destino con un buffer de 8 KiB y, al leer del almacen, se mantiene **una sola
     * imagen** en memoria (acotada por [MEDIA_BACKUP_MAX_IMAGE_BYTES]) para poder entregar un
     * flujo al escritor, que la vuelve a verificar byte a byte. Coste buscado: una copia acotada
     * por imagen, no la suma del backup.
     */
    suspend fun export(destination: OutputStream): MediaBackupExportSummary = coordinationMutex.withLock {
        withContext(ioDispatcher) { exportExclusively(destination) }
    }

    /**
     * Lee y verifica un backup de [input] (JSON legacy o ZIP con imagenes).
     *
     * El llamante conserva la propiedad de [input]; la carga devuelta, si es
     * [MediaBackupLoad.Media], es duena de un temporal y debe cerrarse. Un archivo que no es ni JSON
     * legacy valido ni un contenedor valido se rechaza entero; no hay lectura "a medias".
     */
    suspend fun load(input: InputStream, declaredLength: Long? = null): MediaBackupLoad =
        coordinationMutex.withLock {
            withContext(ioDispatcher) { loadExclusively(input, declaredLength) }
        }

    /**
     * Restaura una carga con imagenes. No lanza por causas esperables: devuelve
     * [MediaBackupRestoreOutcome.NotReplaced] con las claves que se conservaron. Un fallo de IO
     * inesperado se propaga solo si no se puede afirmar el estado.
     *
     * **Cancelacion.** Si el llamante ya esta cancelado se rechaza antes de empezar: ni una imagen
     * publicada ni el mutex global tomado. Una vez tomado [coordinationMutex], la duracion completa
     * (publicacion de imagenes + transaccion de Room) es **no cancelable**, de modo que una
     * cancelacion del llamante no puede dejar imagenes publicadas sin fila ni interrumpir la
     * operacion despues del commit. **Esto no basta para entregar el resultado**: un llamante
     * cancelado descarta lo que devuelve `withContext`, asi que la restauracion confirmada de
     * producto corre en el ambito de aplicacion ([ManualBackupRepository.startConfirmedRestore])
     * con un callback, y no en `viewModelScope`.
     *
     * Una muerte del proceso (cancelacion no cooperativa) entre la publicacion y la transaccion
     * sigue dejando esas imagenes publicadas y sin fila: se conservan (no hay borrado ni recogida
     * aqui) para un futuro recolector; Room no se sustituye a medias porque la sustitucion es una
     * sola transaccion.
     *
     * La propiedad de [load] (y de su temporal del lector) sigue siendo del llamante: esta funcion
     * no lo cierra ni lo usa despues de devolver.
     *
     * [confirmedAngleV2] es la **segunda confirmacion** (ver [angleV2ReplaceWarning]): la instantanea
     * completa del agregado V2 que el usuario acepto perder. `null` solo es correcto si la
     * advertencia fue nula, y entonces el DAO exige que el agregado actual siga vacio dentro de su
     * transaccion. Si el agregado actual no coincide (o aparecio despues), no se sustituye ninguna
     * fila, las imagenes ya publicadas se conservan y se informan como huerfanas, y el resultado es
     * [MediaBackupRestoreFailureReason.ANGLE_V2_CHANGED].
     */
    suspend fun restore(
        load: MediaBackupLoad.Media,
        confirmedAngleV2: AngleAnalysisV2AggregateSnapshot? = null,
    ): MediaBackupRestoreOutcome {
        // Rechazo antes de empezar: no se publica nada, no se toma el mutex y no hay nada que cerrar.
        currentCoroutineContext().ensureActive()
        return coordinationMutex.withLock {
            // Generacion nueva ANTES de tocar la base: cualquier prueba de borrado preparada con la
            // pagina anterior queda invalidada, aunque el restore acabe fallando (lado seguro).
            // Se llama aqui, con el mutex ya tomado, porque el mutex no es reentrante.
            restoreEpoch.newTokenLocked()
            // `NonCancellable` protege la durabilidad del tramo, no la entrega del valor: el
            // resultado solo llega a un llamante vivo. Ver la documentacion de arriba.
            withContext(NonCancellable + ioDispatcher) {
                restoreExclusively(load.preparedImport, confirmedAngleV2)
            }
        }
    }

    // --- Export --------------------------------------------------------------------------------

    private suspend fun exportExclusively(destination: OutputStream): MediaBackupExportSummary {
        // Una sola captura transaccional: legacy y V2 del mismo instante, ya validada.
        val manifest = dao.snapshotWithAngleMedia()
        val summary = ManualBackupSummary.from(manifest.legacy)
        // Formato unico del export: contenedor ZIP tambien sin imagenes. El documento de destino ya
        // existe cuando se llega aqui (SAF fijo su nombre y su MIME al crearlo), asi que elegir el
        // formato mirando el estado de la base no puede ser coherente: el export sin imagenes
        // habria escrito JSON dentro de un documento anunciado como ZIP (o al reves si el agregado
        // V2 cambiaba entre la creacion del documento y la captura). El manifiesto sin imagenes es
        // un contenedor valido (solo `manifest.json`) y el lector de JSON legacy no cambia.
        val prepared = MediaBackupArchiveWriter(stagingOrThrow()).write(manifest, storedImageSource(manifest))
        return try {
            FileInputStream(prepared.file).use { input -> input.pumpTo(destination, COPY_BUFFER_BYTES) }
            MediaBackupExportSummary(
                summary = summary,
                imageCount = prepared.imageCount,
                mediaIncluded = prepared.imageCount > 0,
                archiveByteCount = prepared.archiveByteCount,
            )
        } finally {
            // El temporal es de esta llamada; si no se puede borrar, el error de limpieza se
            // propaga desde close() y no se reporta un export limpio.
            prepared.close()
        }
    }

    /**
     * Fuente de imagenes por clave opaca: solo entrega bytes si el almacen privado los verifica
     * contra el tamano y el SHA-256 que la fila declara. Una imagen ausente devuelve `null` (el
     * escritor aborta el backup); una corrupta o ilegible lanza.
     */
    private fun storedImageSource(manifest: MediaBackupManifest): MediaBackupImageSource {
        val declared = manifest.angleV2.images.associateBy { it.imageKey }
        return MediaBackupImageSource { imageKey ->
            val image = declared[imageKey] ?: return@MediaBackupImageSource null
            val expected = AngleImageIntegrity(byteCount = image.byteCount, sha256Hex = image.sha256)
            val result = imageStore.readVerified(imageKey, expected) { stream ->
                readExactly(stream, image.byteCount)
            }
            when (result) {
                is AngleImageReadResult.Delivered -> ByteArrayInputStream(result.value)
                is AngleImageReadResult.Failed -> when (result.error) {
                    is AngleImageStoreError.Absent -> null
                    else -> throw IOException("stored angle image $imageKey cannot be read")
                }
            }
        }
    }

    /** Lee exactamente [byteCount] bytes del flujo acotado o falla: nunca rellena ni trunca. */
    private fun readExactly(stream: InputStream, byteCount: Long): ByteArray {
        require(byteCount in 1..MAX_BUFFERED_IMAGE_BYTES) { "stored angle image size is out of range" }
        val size = byteCount.toInt()
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = stream.read(buffer, offset, size - offset)
            if (count < 0) throw IOException("stored angle image ended before its declared size")
            offset += count
        }
        return buffer
    }

    // --- Lectura -------------------------------------------------------------------------------

    private fun loadExclusively(input: InputStream, declaredLength: Long?): MediaBackupLoad {
        val head = ByteArray(HEAD_BYTES)
        val headCount = readHead(input, head)
        // Reinyeccion: el lector de destino ve el archivo completo, sin perder streaming.
        val replay = SequenceInputStream(ByteArrayInputStream(head, 0, headCount), input)
        return if (isZipContainer(head, headCount)) {
            val prepared = MediaBackupArchiveReader(stagingOrThrow()).read(replay)
            MediaBackupLoad.Media(prepared)
        } else {
            MediaBackupLoad.Legacy(ManualBackupCodec.decode(replay, declaredLength))
        }
    }

    private fun readHead(input: InputStream, head: ByteArray): Int {
        var count = 0
        while (count < head.size) {
            val read = input.read(head, count, head.size - count)
            if (read < 0) break
            count += read
        }
        return count
    }

    /** Cabecera local (`PK\x03\x04`) o directorio final de un ZIP vacio (`PK\x05\x06`). */
    private fun isZipContainer(head: ByteArray, count: Int): Boolean {
        if (count < 4) return false
        if (head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) return false
        return when (head[2].toInt() to head[3].toInt()) {
            3 to 4, 5 to 6 -> true
            else -> false
        }
    }

    // --- Restore -------------------------------------------------------------------------------

    /**
     * Advertencia con datos para un restore que descartaria el agregado V2 actual, **para cualquier
     * formato de backup** (JSON v1 o contenedor ZIP, con analisis dentro o sin ellos).
     *
     * Devuelve `null` si el agregado V2 esta vacio (no hay nada que perder y la comprobacion atomica
     * del DAO exigira que siga vacio) y, si hay filas, una [AngleV2DiscardWarning] con la instantanea
     * **completa** del agregado: conteos para explicar la perdida y la huella de identidad y contenido
     * de todas las filas para poder revalidarla despues. Es solo lectura.
     *
     * [AngleV2DiscardWarning] solo se construye aqui (constructor `internal`), de modo que la unica
     * forma de obtener una confirmacion con datos es haber leido el estado V2 de la base. Ademas ese
     * estado se **revalida** bajo el mutex y otra vez dentro de la transaccion (recalculando la
     * instantanea completa, no solo los conteos): una advertencia obsoleta no destruye un agregado
     * distinto, ni siquiera uno con los mismos conteos.
     */
    suspend fun angleV2ReplaceWarning(): AngleV2DiscardWarning? = coordinationMutex.withLock {
        withContext(ioDispatcher) {
            dao.angleV2AggregateSnapshot().takeUnless { it.counts.isEmpty }?.let(::AngleV2DiscardWarning)
        }
    }

    /**
     * Nombre historico de [angleV2ReplaceWarning], que empezo valiendo solo para JSON legacy. Se
     * mantiene para no romper llamantes ni tests existentes; **no** cambia la semantica: la
     * advertencia vale para cualquier formato porque cualquier restore sustituye el agregado actual.
     */
    suspend fun legacyRestoreWarning(): AngleV2DiscardWarning? = angleV2ReplaceWarning()

    /**
     * Aplica un restore de **JSON legacy** descartando el agregado V2 que el usuario acaba de
     * confirmar en la advertencia aparte ([warning]).
     *
     * **No es una via de escape de la barrera.** Solo acepta una carga legacy y un
     * [AngleV2DiscardWarning] con un agregado **no vacio**; despues lo revalida bajo
     * [coordinationMutex] comparando la instantanea **completa** (identidad y contenido de todas las
     * filas, no solo los conteos) y el DAO la vuelve a recalcular dentro de la transaccion que borra.
     * Si el agregado cambio —otro restore, un guardado, o contenido distinto con los mismos
     * conteos—, si estaba vacio o si la transaccion falla, no se sustituye ninguna fila y se informa
     * [MediaBackupRestoreOutcome.NotReplaced]; no hay ningun camino desde aqui que borre filas V2 sin
     * coincidencia exacta con lo advertido.
     *
     * **Ficheros.** No publica imagenes restauradas (un JSON no las trae), **no borra ficheros** y no
     * toca el almacen privado: las imagenes anteriores al restore se conservan en disco y quedan sin
     * fila que las referencie. Esa consecuencia se explica al usuario en la advertencia.
     *
     * **Cancelacion.** Mismo criterio que [restore]: se rechaza antes de empezar si el llamante ya
     * esta cancelado y, una vez tomado [coordinationMutex], la operacion es no cancelable. La
     * propiedad de la carga sigue siendo del llamante: esta funcion no la cierra.
     */
    suspend fun replaceLegacyDiscardingAngleV2(
        load: MediaBackupLoad.Legacy,
        warning: AngleV2DiscardWarning,
    ): MediaBackupRestoreOutcome {
        currentCoroutineContext().ensureActive()
        return coordinationMutex.withLock {
            // Misma invalidacion que [restore]: un JSON legacy confirmado tambien cambia la
            // identidad del agregado V2 que una pagina/dialogo pudo mostrar.
            restoreEpoch.newTokenLocked()
            withContext(NonCancellable + ioDispatcher) {
                replaceLegacyExclusively(load.document, warning)
            }
        }
    }

    private suspend fun replaceLegacyExclusively(
        document: ManualBackupDocument,
        warning: AngleV2DiscardWarning,
    ): MediaBackupRestoreOutcome {
        val summary = ManualBackupSummary.from(document)
        return try {
            // Revalidacion bajo el mutex, antes de abrir la transaccion: una advertencia obsoleta se
            // rechaza sin escribir nada. Se comparan identidad y contenido completos, no solo
            // conteos: un agregado distinto con el mismo numero de filas tampoco se descarta. La
            // comprobacion atomica es la que hace el DAO dentro de la transaccion que borra (ahi no
            // hay ventana posible).
            val observed = dao.angleV2AggregateSnapshot()
            if (observed != warning.snapshot) {
                angleV2Changed(warning.snapshot, observed)
            } else {
                dao.replaceAllDiscardingAngleV2(document, warning.snapshot)
                MediaBackupRestoreOutcome.Restored(
                    summary = summary,
                    imageCount = 0,
                    restoredImageKeys = emptyList(),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (changed: ManualBackupAngleV2ChangedException) {
            // El chequeo atomico del DAO (dentro de la transaccion) encontro otro estado: no se borro
            // ni una fila.
            angleV2Changed(changed.confirmed, changed.observed)
        } catch (_: Exception) {
            MediaBackupRestoreOutcome.NotReplaced(
                reason = MediaBackupRestoreFailureReason.ROOM_REJECTED,
                preservedImageKeys = emptyList(),
                unsyncedImageKeys = emptyList(),
                detail = null,
            )
        }
    }

    /**
     * Resultado fail-closed de una advertencia obsoleta: el agregado V2 ya no es el que el usuario
     * vio (ni en conteos ni en contenido), asi que no se descarta nada y se pide releer/confirmar de
     * nuevo.
     */
    private fun angleV2Changed(
        confirmed: AngleAnalysisV2AggregateSnapshot,
        observed: AngleAnalysisV2AggregateSnapshot,
    ) = MediaBackupRestoreOutcome.NotReplaced(
        reason = MediaBackupRestoreFailureReason.ANGLE_V2_CHANGED,
        preservedImageKeys = emptyList(),
        unsyncedImageKeys = emptyList(),
        detail = "confirmed ${confirmed.describe()}, now ${observed.describe()}",
    )

    /**
     * Resultado fail-closed del caso "la advertencia inicial fue nula": el agregado actual estaba
     * vacio cuando se leyo y dejo de estarlo antes del primer `DELETE`. No se borro nada.
     */
    private fun angleV2Appeared() = MediaBackupRestoreOutcome.NotReplaced(
        reason = MediaBackupRestoreFailureReason.ANGLE_V2_CHANGED,
        preservedImageKeys = emptyList(),
        unsyncedImageKeys = emptyList(),
        detail = "confirmed an empty angle V2 aggregate, now non-empty",
    )

    private suspend fun restoreExclusively(
        prepared: MediaBackupPreparedImport,
        confirmedAngleV2: AngleAnalysisV2AggregateSnapshot?,
    ): MediaBackupRestoreOutcome {
        val manifest = prepared.manifest.validate()
        val summary = ManualBackupSummary.from(manifest.legacy)
        val frames = manifest.angleV2.frames
        val frameKeys = frames.map { it.imageKey }
        val imageKeys = manifest.angleV2.images.map { it.imageKey }
        // Biyeccion antes de publicar nada: el contrato ya la exige, pero una carga construida por
        // otra via no puede llegar aqui y dejar fotogramas sin imagen declarada.
        val contractProblem = when {
            frameKeys.size != imageKeys.size -> "frame/image count mismatch"
            frameKeys.toSet().size != frameKeys.size -> "duplicated frame image key"
            imageKeys.toSet() != frameKeys.toSet() -> "frame and image declarations differ"
            prepared.imageCount != imageKeys.size -> "archive image count does not match the manifest"
            else -> null
        }
        if (contractProblem != null) {
            return MediaBackupRestoreOutcome.NotReplaced(
                reason = MediaBackupRestoreFailureReason.CONTRACT_MISMATCH,
                preservedImageKeys = emptyList(),
                unsyncedImageKeys = emptyList(),
                detail = contractProblem,
            )
        }
        if (frames.isEmpty()) {
            // Backup con el agregado V2 vacio: sustitucion transaccional sin imagenes. La segunda
            // confirmacion viaja igual: si la advertencia fue nula, el DAO exige que el agregado
            // actual siga vacio de forma atomica antes del primer `DELETE`.
            return try {
                dao.replaceAllWithAngleMedia(manifest, confirmedAngleV2)
                MediaBackupRestoreOutcome.Restored(summary, 0, emptyList())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (changed: ManualBackupAngleV2ChangedException) {
                angleV2Changed(changed.confirmed, changed.observed)
            } catch (_: ManualBackupV2BlockedException) {
                // No habia agregado en la advertencia y ahora si: no se borro ni una fila.
                angleV2Appeared()
            } catch (_: Exception) {
                MediaBackupRestoreOutcome.NotReplaced(
                    reason = MediaBackupRestoreFailureReason.ROOM_REJECTED,
                    preservedImageKeys = emptyList(),
                    unsyncedImageKeys = emptyList(),
                    detail = null,
                )
            }
        }
        val staging = stagingOrThrow()
        val preparation = MediaBackupImagePreparer(staging, imageStore).prepare(prepared)
        val images = when (preparation) {
            is MediaBackupImagePreparationResult.Prepared -> preparation.images
            is MediaBackupImagePreparationResult.Failed -> return MediaBackupRestoreOutcome.NotReplaced(
                reason = MediaBackupRestoreFailureReason.PREPARATION_FAILED,
                preservedImageKeys = preparation.publishedOrphanKeys,
                unsyncedImageKeys = preparation.uncertainPublishedKeys,
                detail = preparation.failure::class.simpleName,
            )
        }
        // La sustitucion de Room solo se plantea con imagenes duraderas, completas y con identidad
        // comprobada: si no, se conserva todo lo publicado y no se toca una fila.
        val storedKeys = images.storedKeys
        val declaredByKey = manifest.angleV2.images.associateBy { it.imageKey }
        val identityProblem: String? = when {
            images.count != frameKeys.size -> "prepared image count does not match the frames"
            images.byImportedKey.keys != frameKeys.toSet() -> "prepared images do not match the declared keys"
            storedKeys.toSet().size != storedKeys.size -> "prepared images share a key"
            else -> images.byImportedKey.entries.firstNotNullOfOrNull { (importedKey, stored) ->
                val declared = declaredByKey.getValue(importedKey)
                if (stored.byteCount != declared.byteCount || stored.sha256Hex != declared.sha256) {
                    "prepared image does not match the declared integrity"
                } else {
                    null
                }
            }
        }
        if (identityProblem != null || !images.fullyDurable) {
            return MediaBackupRestoreOutcome.NotReplaced(
                reason = if (!images.fullyDurable) {
                    MediaBackupRestoreFailureReason.NOT_DURABLE
                } else {
                    MediaBackupRestoreFailureReason.IDENTITY_MISMATCH
                },
                preservedImageKeys = storedKeys,
                unsyncedImageKeys = images.unsyncedStoredKeys,
                detail = identityProblem,
            )
        }
        val remapped = try {
            manifest.withRestoredImageKeys(images.byImportedKey)
        } catch (_: IllegalArgumentException) {
            return MediaBackupRestoreOutcome.NotReplaced(
                reason = MediaBackupRestoreFailureReason.IDENTITY_MISMATCH,
                preservedImageKeys = storedKeys,
                unsyncedImageKeys = emptyList(),
                detail = "manifest cannot be remapped to the new image keys",
            )
        }
        return try {
            // Una sola transaccion Room: legacy + V2 con las claves nuevas, y la segunda
            // confirmacion revalidada dentro de ella. Si falla, las imagenes publicadas quedan como
            // huerfanas y se conservan e informan (no se borra nada).
            dao.replaceAllWithAngleMedia(remapped, confirmedAngleV2)
            MediaBackupRestoreOutcome.Restored(summary, storedKeys.size, storedKeys)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (changed: ManualBackupAngleV2ChangedException) {
            // El agregado cambio entre la advertencia y la transaccion: no se sustituyo ninguna fila
            // y las imagenes recien publicadas se conservan e informan como huerfanas.
            MediaBackupRestoreOutcome.NotReplaced(
                reason = MediaBackupRestoreFailureReason.ANGLE_V2_CHANGED,
                preservedImageKeys = storedKeys,
                unsyncedImageKeys = images.unsyncedStoredKeys,
                detail = "confirmed ${changed.confirmed.describe()}, now ${changed.observed.describe()}",
            )
        } catch (_: ManualBackupV2BlockedException) {
            // Advertencia nula (agregado vacio) y aparecio V2 antes del primer `DELETE`.
            MediaBackupRestoreOutcome.NotReplaced(
                reason = MediaBackupRestoreFailureReason.ANGLE_V2_CHANGED,
                preservedImageKeys = storedKeys,
                unsyncedImageKeys = images.unsyncedStoredKeys,
                detail = "confirmed an empty angle V2 aggregate, now non-empty",
            )
        } catch (_: Exception) {
            MediaBackupRestoreOutcome.NotReplaced(
                reason = MediaBackupRestoreFailureReason.ROOM_REJECTED,
                preservedImageKeys = storedKeys,
                unsyncedImageKeys = images.unsyncedStoredKeys,
                detail = null,
            )
        }
    }

    // --- Staging -------------------------------------------------------------------------------

    private fun stagingOrThrow(): File {
        if (!stagingDirectory.isDirectory) {
            if (!stagingDirectory.mkdirs() && !stagingDirectory.isDirectory) {
                throw IOException("media backup staging directory is not usable")
            }
        }
        return stagingDirectory
    }

    private companion object {
        const val HEAD_BYTES: Int = 4
        const val COPY_BUFFER_BYTES: Int = 8 * 1024
        /** Tope duro de una imagen individual, igual que el contrato del manifiesto. */
        const val MAX_BUFFERED_IMAGE_BYTES: Long = MEDIA_BACKUP_MAX_IMAGE_BYTES
    }
}

/**
 * Resumen de un export completado. El export es **siempre** un contenedor ZIP: [mediaIncluded]
 * solo dice si ese contenedor transporta imagenes; [archiveByteCount] es su tamano real.
 */
data class MediaBackupExportSummary(
    val summary: ManualBackupSummary,
    val imageCount: Int,
    val mediaIncluded: Boolean,
    val archiveByteCount: Long,
)

/**
 * Backup ya leido y verificado. [Media] es dueno de un temporal del lector y **debe** cerrarse;
 * [Legacy] no tiene recursos y su cierre no hace nada.
 */
sealed interface MediaBackupLoad : Closeable {

    val summary: ManualBackupSummary
    val imageCount: Int

    /** JSON legacy estricto (formato v1, sin medios). */
    class Legacy(val document: ManualBackupDocument) : MediaBackupLoad {
        override val summary: ManualBackupSummary = ManualBackupSummary.from(document)
        override val imageCount: Int get() = 0
        override fun close() = Unit
    }

    /** Contenedor ZIP con manifiesto validado e imagenes verificadas. */
    class Media(internal val preparedImport: MediaBackupPreparedImport) : MediaBackupLoad {
        override val summary: ManualBackupSummary = ManualBackupSummary.from(preparedImport.manifest.legacy)
        override val imageCount: Int get() = preparedImport.imageCount
        override fun close() = preparedImport.close()
    }
}

/** Resultado tipado de un restore con imagenes; nunca se informa exito sin transaccion aplicada. */
sealed interface MediaBackupRestoreOutcome {

    /** Las filas se sustituyeron y estas son las claves nuevas que quedaron referenciadas. */
    data class Restored(
        val summary: ManualBackupSummary,
        val imageCount: Int,
        val restoredImageKeys: List<String>,
    ) : MediaBackupRestoreOutcome

    /**
     * No se sustituyo ninguna fila. [preservedImageKeys] son las imagenes que esta operacion
     * publico y se conservan (no se borran ni se recogen); [unsyncedImageKeys] son las que el
     * almacen no pudo confirmar como duraderas. Las claves son opacas y no contienen datos
     * personales; el llamante decide como informarlas.
     */
    data class NotReplaced(
        val reason: MediaBackupRestoreFailureReason,
        val preservedImageKeys: List<String>,
        val unsyncedImageKeys: List<String>,
        val detail: String?,
    ) : MediaBackupRestoreOutcome
}

/** Por que no se aplico un restore: contrato, preparacion, durabilidad, identidad, estado V2 o Room. */
enum class MediaBackupRestoreFailureReason {
    CONTRACT_MISMATCH,
    PREPARATION_FAILED,
    NOT_DURABLE,
    IDENTITY_MISMATCH,
    ANGLE_V2_CHANGED,
    ROOM_REJECTED,
}

/**
 * Manifiesto con las claves de imagen sustituidas por las que el almacen acaba de publicar.
 * Tamano, SHA-256 y geometria no cambian: son los mismos bytes con otra clave opaca. Lanza
 * [IllegalArgumentException] si falta alguna clave o si el resultado no cumple el contrato.
 */
internal fun MediaBackupManifest.withRestoredImageKeys(
    byImportedKey: Map<String, StoredAngleImage>,
): MediaBackupManifest {
    val v2 = this.angleV2
    val frames = v2.frames.map { frame ->
        val stored = byImportedKey[frame.imageKey]
            ?: throw IllegalArgumentException("no prepared image for the declared frame key")
        frame.copy(imageKey = stored.imageKey)
    }
    val images = v2.images.map { image ->
        val stored = byImportedKey[image.imageKey]
            ?: throw IllegalArgumentException("no prepared image for the declared image key")
        require(stored.byteCount == image.byteCount && stored.sha256Hex == image.sha256) {
            "prepared image does not match the declared integrity"
        }
        image.copy(imageKey = stored.imageKey)
    }.sortedBy { it.imageKey }
    return copy(angleV2 = v2.copy(frames = frames, images = images)).validate()
}

/**
 * Advertencia con datos de lo que un restore destruye: el agregado V2 actual. Vale para **cualquier**
 * formato de backup (JSON v1 o contenedor ZIP, con analisis dentro o sin ellos), porque cualquier
 * restore sustituye ese agregado.
 *
 * Solo la construye [MediaBackupCoordinator.angleV2ReplaceWarning] (constructor `internal`), siempre a
 * partir de una lectura real de las tres tablas V2 y solo cuando no estan vacias. La UI la usa para
 * explicar, en un dialogo aparte, cuantos analisis/fotogramas/marcas se van a perder y para pedir una
 * confirmacion **distinta** de la de restaurar.
 *
 * La advertencia transporta la [snapshot] **completa** del agregado, no solo sus conteos: el conjunto
 * que el usuario acepta descartar queda identificado por identidad y contenido de todas las filas, y
 * [MediaBackupCoordinator.replaceLegacyDiscardingAngleV2] y [MediaBackupCoordinator.restore] exigen
 * que esa instantanea se recalcule igual antes de borrar nada. Los conteos siguen publicos porque son
 * lo que se muestra en el dialogo; nunca son la prueba de identidad.
 */
class AngleV2DiscardWarning internal constructor(
    internal val snapshot: AngleAnalysisV2AggregateSnapshot,
) {
    val rowCounts: AngleAnalysisV2RowCounts get() = snapshot.counts
    val rootCount: Int get() = rowCounts.roots
    val frameCount: Int get() = rowCounts.frames
    val markCount: Int get() = rowCounts.marks
}

/** Volcado con buffer del contenedor ya verificado al destino del llamante (streaming). */
private fun InputStream.pumpTo(destination: OutputStream, bufferSize: Int): Long {
    val buffer = ByteArray(bufferSize)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        destination.write(buffer, 0, count)
        total += count
    }
    return total
}
