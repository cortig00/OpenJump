package com.openjump.app.data.backup

import com.openjump.app.data.angles.AngleImageKey
import com.openjump.app.data.angles.AngleImageStoreError
import com.openjump.app.data.angles.AngleImageWriteResult
import com.openjump.app.data.angles.PrivateAngleImageStore
import com.openjump.app.data.angles.StoredAngleImage
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Preparacion de las imagenes de una restauracion con medios (slice G4a): convierte un
 * [MediaBackupPreparedImport] **ya validado** en imagenes **nuevas** del almacen privado, con clave
 * opaca nueva, y devuelve el mapeo `clave importada -> [StoredAngleImage]` solo cuando **todas** las
 * imagenes estan preparadas.
 *
 * Que hace estructural este fichero:
 *  1. **Ningun nombre del ZIP se convierte en ruta.** La unica entrada de bytes es
 *     [MediaBackupPreparedImport.copyImageTo], que resuelve la imagen por su clave opaca. El destino
 *     es un fichero temporal **exclusivo** creado por este componente dentro del directorio de
 *     staging **explicito** del llamante ([MediaBackupPreparationFilePort.createSpoolFile]); el
 *     nombre del spool lo genera el sistema y nunca procede del contenedor.
 *  2. **Copia en streaming, acotada, sincronizada y re-verificada.** El flujo se copia por bloques
 *     (nunca se materializa la imagen en memoria) con un tope propio igual al `byteCount` declarado
 *     ([MediaBackupPreparationSink] via un [OutputStream] acotado), y despues se hace `fsync`.
 *     `copyImageTo` ya recomprueba tamano, CRC-32 y SHA-256; aqui se vuelven a contrastar el tamano
 *     y el SHA-256 devueltos con lo declarado: no se confia en el nombre ni en el resultado del
 *     lector.
 *  3. **PNG real antes de publicar.** El spool pasa por [MediaBackupPngProbe] con el MIME, las
 *     dimensiones, el `byteCount` y el SHA del manifiesto, y con el presupuesto del probe (que
 *     rechaza dimensiones/coste **antes** del full decode). El resultado confirmado se vuelve a
 *     contrastar con lo declarado (clave, dimensiones, tamano, SHA).
 *  4. **Publicacion en el almacen privado con clave nueva.** Solo despues se llama a
 *     [PrivateAngleImageStore.write] desde el spool. El almacen genera la clave, rechaza cualquier
 *     clave existente sin sobrescribir y verifica el fichero publicado; aqui se comprueba ademas que
 *     la clave nueva **no** es la importada, que es canonica, que no se repite dentro de esta
 *     operacion y que el `byteCount`/SHA sellados coinciden con lo declarado. Los ficheros
 *     originales del almacen no se tocan: no hay `delete`/`gc`/reemplazo en esta pieza.
 *  5. **Todo o nada en el resultado.** [MediaBackupImagePreparer.prepare] devuelve
 *     [MediaBackupImagePreparationResult.Prepared] unicamente cuando el bucle completo termino bien.
 *     Cualquier fallo (ausencia, integridad, PNG, `ENOSPC`, cancelacion, excepcion inesperada)
 *     devuelve [MediaBackupImagePreparationResult.Failed] **sin** imagenes: el mapeo parcial se
 *     descarta y nunca se entrega. Ese fallo si conserva el **registro** de lo que pudo quedar en
 *     disco (claves verificadas, claves inciertas y residuos del almacen).
 *  6. **Limpieza acotada a lo propio.** En exito y en fallo se borra **solo** cada spool creado por
 *     esta operacion, uno a uno y nunca de forma recursiva. Si el borrado falla, se informa
 *     ([MediaBackupImagePreparationResult.Failed.spoolCleanupFailed]) y se preserva el fichero.
 *  7. **Huerfanos informados, no borrados.** Si el almacen ya habia publicado (y verificado) una
 *     clave cuando el fallo ocurre despues, esas claves se devuelven ordenadas en
 *     [MediaBackupImagePreparationResult.Failed.publishedOrphanKeys]. Si el almacen declaro la
 *     publicacion como **incierta** ([AngleImageStoreError.PublicationUncertain]) la clave va
 *     aparte, en [MediaBackupImagePreparationResult.Failed.uncertainPublishedKeys], porque puede
 *     existir sin estar verificada. Ninguna de las dos listas se borra ni se presenta como valida:
 *     la recuperacion/GC de huerfanos no existe todavia. El resultado del almacen se captura
 *     **siempre** antes de cerrar el flujo de lectura del spool: si el cierre tambien falla (con
 *     `IOException` o con `RuntimeException`), no se pierde ni la publicacion incierta ni los
 *     residuos, y el fallo del almacen y el de cierre se conservan como causas distintas
 *     ([MediaBackupPreparationFailure.StoreFailureAndSpoolCloseFailed]) sin presentar la operacion
 *     como exito. Los `Error` fatales del cierre no se capturan: se propagan.
 *  8. **Bloqueante y sin adaptador suspend.** [MediaBackupImagePreparer.prepare] hace la copia, el
 *     probe y la publicacion de forma sincrona. **No hay variante `suspend`**: envolver esta pieza
 *     en una corrutina cancelable no protege el resultado, porque la cancelacion puede descartarlo
 *     despues de publicar. Un futuro llamante debe ejecutarla en su propia frontera **no
 *     cancelable** (por ejemplo `withContext(NonCancellable + ioDispatcher)`) y, si el resultado
 *     tiene que sobrevivir a la muerte del proceso, registrar la operacion y su recuperacion antes
 *     de publicar; la recuperacion automatica no existe. La cancelacion sigue siendo cooperativa
 *     ([MediaBackupArchiveCancellation]) y se consulta antes de cada imagen, entre bloques (dentro
 *     de `copyImageTo`) y dentro de `store.write`.
 *
 * **Lo que esta pieza NO hace y no se debe afirmar que haga**: no inserta filas en Room, no activa
 * el linaje V2, no ofrece UI/SAF, no cierra el [MediaBackupPreparedImport] de entrada (lo posee el
 * llamante) y no promete atomicidad entre sistema de ficheros y Room. La publicacion y la
 * transaccion de Room no son atomicas: un fallo o un crash **despues** de publicar deja huerfanos
 * conservados (informados en el resultado de fallo cuando el proceso sigue vivo, y no reclamables
 * automaticamente en ningun caso). La recuperacion/GC de esos huerfanos y el mutex que coordine dos
 * restauraciones concurrentes **no** estan implementados: no se afirma que el estado final tras un
 * crash sea consistente.
 *
 * Modelo de confianza: staging privado y no compartido (lo declara el llamante), escritores
 * cooperantes del proceso y ninguna promesa frente a otro proceso con el mismo UID o frente a codigo
 * hostil dentro de la app. No hay logs con rutas, claves ni contenido.
 */

/**
 * Declaracion **ya validada** de una imagen a preparar: clave opaca del contenedor, integridad
 * sellada y dimensiones declaradas por el fotograma.
 *
 * Se deriva del manifiesto (nunca del nombre de una entrada ZIP) y no contiene rutas ni URIs.
 */
data class MediaBackupImageDeclaration(
    val imageKey: String,
    val byteCount: Long,
    val sha256: String,
    val width: Int,
    val height: Int,
    val mimeType: String = MEDIA_BACKUP_IMAGE_MIME_TYPE,
) {
    init {
        require(AngleImageKey.isCanonical(imageKey)) { "La clave de imagen no es canonica." }
        require(byteCount >= 1L) { "byteCount debe ser positivo." }
        require(sha256.length == SHA256_HEX_LENGTH && sha256.all { it.isHexDigit() }) {
            "sha256 debe ser hexadecimal de $SHA256_HEX_LENGTH caracteres."
        }
    }

    /** Expectativa del probe: dimensiones y sello que se contrastan contra los bytes reales. */
    fun expectation(): MediaBackupPngExpectation = MediaBackupPngExpectation(
        imageKey = imageKey,
        width = width,
        height = height,
        byteCount = byteCount,
        sha256 = sha256,
        mimeType = mimeType,
    )
}

/**
 * Origen de bytes de la preparacion. Existe para que las pruebas JVM puedan simular corrupcion
 * parcial, ausencia, flujos demasiado largos y `ENOSPC` sin un ZIP real, y para que el camino de
 * produccion dependa de `copyImageTo` (que re-verifica) y no de una ruta.
 *
 * Implementacion de produccion: [MediaBackupPreparedImport.asPreparationSource].
 */
interface MediaBackupImagePreparationSource {

    /** Declaraciones en orden determinista (clave ascendente); sin duplicados. */
    val declarations: List<MediaBackupImageDeclaration>

    /**
     * Entrega los bytes verificados de [imageKey] a [output], que **posee el llamante** (no se
     * cierra desde aqui). Debe lanzar si la imagen no esta, si la integridad no cuadra o si se
     * cancela; nunca informar exito con bytes parciales.
     */
    fun copyImageTo(
        imageKey: String,
        output: OutputStream,
        cancellation: MediaBackupArchiveCancellation,
    ): MediaBackupImageTransfer
}

/**
 * Sumidero append-only de un spool propio, con `fsync` explicito.
 *
 * Se separa del puerto de ficheros para que las pruebas puedan fallar en la escritura o en el
 * `fsync` sin tocar el disco real.
 */
interface MediaBackupPreparationSink : Closeable {

    fun write(buffer: ByteArray, offset: Int, length: Int)

    /** Fuerza el contenido del spool a disco antes de probarlo y publicarlo. */
    fun sync()

    override fun close()
}

/**
 * Frontera de ficheros de la preparacion: crear el spool, abrirlo para escritura/lectura y borrarlo.
 *
 * Es deliberadamente minimo: no hay API para abrir una ruta arbitraria, listar directorios ni borrar
 * arboles, porque esta pieza solo puede tocar los spools que ella misma creo dentro del staging
 * privado del llamante.
 */
interface MediaBackupPreparationFilePort {

    /** Crea un fichero temporal **nuevo y exclusivo** dentro de [stagingDirectory]. */
    fun createSpoolFile(stagingDirectory: File): File

    fun openSpoolSink(file: File): MediaBackupPreparationSink

    fun openSpoolSource(file: File): InputStream

    /**
     * Borra exactamente [file]. Devuelve `true` si ya no existe (borrado ahora o antes) y `false` si
     * sigue existiendo. Nunca borra directorios ni sigue enlaces.
     */
    fun deleteSpoolFile(file: File): Boolean
}

/**
 * Puerto del probe de PNG. Implementacion de produccion: [MediaBackupPngProbe] (que decodifica de
 * verdad en Android). En JVM se inyecta un fake porque `android.graphics` no esta disponible.
 */
fun interface MediaBackupPreparationProbePort {
    fun probe(
        expectation: MediaBackupPngExpectation,
        openStream: () -> InputStream,
        cancellation: MediaBackupArchiveCancellation,
    ): MediaBackupPngVerified
}

/** Puerto del almacen privado. Implementacion de produccion: [PrivateAngleImageStore.write]. */
fun interface MediaBackupPreparationStorePort {
    fun write(
        source: InputStream,
        expectedByteCount: Long,
        shouldCancel: () -> Boolean,
    ): AngleImageWriteResult
}

/**
 * Presupuestos de la preparacion, comprobados **antes** de cualquier E/S y con aritmetica
 * comprobada: numero de imagenes, tamano por imagen y suma total.
 */
class MediaBackupPreparationLimits(
    val maxImages: Int = MEDIA_BACKUP_MAX_IMAGES,
    val maxImageBytes: Long = MEDIA_BACKUP_MAX_IMAGE_BYTES,
    val maxTotalBytes: Long = MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES,
) {
    init {
        require(maxImages in 1..MEDIA_BACKUP_MAX_IMAGES) { "maxImages debe estar en 1..$MEDIA_BACKUP_MAX_IMAGES." }
        require(maxImageBytes in 1..MEDIA_BACKUP_MAX_IMAGE_BYTES) {
            "maxImageBytes debe estar en 1..$MEDIA_BACKUP_MAX_IMAGE_BYTES."
        }
        require(maxTotalBytes in 1..MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES) {
            "maxTotalBytes debe estar en 1..$MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES."
        }
    }
}

/** Etapa en la que se detecto un fallo de preparacion; sirve de diagnostico, no de recuperacion. */
enum class MediaBackupPreparationStage { DECLARATIONS, SPOOL, COPY, SYNC, PROBE, STORE }

/**
 * Conjunto **completo** de imagenes preparadas.
 *
 * [byImportedKey] es un mapeo inmutable `clave importada -> [StoredAngleImage]` (claves opacas y
 * sellos, sin rutas ni URIs) en orden determinista (clave importada ascendente). Solo se construye
 * cuando **todas** las imagenes del manifiesto se publicaron y verificaron.
 */
class MediaBackupPreparedImages internal constructor(
    entries: List<Pair<String, StoredAngleImage>>,
    unsyncedStoredKeys: List<String> = emptyList(),
) {

    /** Mapeo inmutable y determinista; el llamante no puede modificarlo. */
    val byImportedKey: Map<String, StoredAngleImage> = Collections.unmodifiableMap(
        LinkedHashMap<String, StoredAngleImage>().apply { entries.forEach { (imported, stored) -> put(imported, stored) } },
    )

    val count: Int get() = byImportedKey.size

    /** Claves nuevas publicadas, en el mismo orden determinista del mapeo. */
    val storedKeys: List<String> get() = byImportedKey.values.map { it.imageKey }

    /** Suma exacta de los bytes publicados. */
    val totalByteCount: Long
        get() = byImportedKey.values.fold(0L) { total, image -> Math.addExact(total, image.byteCount) }

    /**
     * Claves publicadas y verificadas en proceso cuyo **directorio** no se pudo sincronizar
     * ([com.openjump.app.data.angles.AngleImageDurability.directoryEntriesSynced] en `false`): siguen
     * siendo imagenes validas, pero su persistencia frente a un corte no esta confirmada.
     */
    val unsyncedStoredKeys: List<String> = Collections.unmodifiableList(ArrayList(unsyncedStoredKeys))

    /** `true` solo si **todas** las imagenes declararon durabilidad confirmada de directorios. */
    val fullyDurable: Boolean get() = unsyncedStoredKeys.isEmpty()
}

/** Resultado de la preparacion: o el conjunto completo, o un fallo sin imagenes utilizables. */
sealed interface MediaBackupImagePreparationResult {

    /**
     * Conjunto completo de imagenes preparadas. [spoolCleanupFailed] informa de que un spool propio no
     * se pudo borrar y sigue en el staging privado del llamante: las imagenes son validas, pero la
     * limpieza de esta operacion no lo fue.
     */
    data class Prepared(
        val images: MediaBackupPreparedImages,
        val spoolCleanupFailed: Boolean = false,
    ) : MediaBackupImagePreparationResult

    /**
     * Fallo: **no** hay mapeo valido.
     *
     * [publishedOrphanKeys] son las claves que el almacen ya habia publicado y verificado en esta
     * operacion y que se **conservan** (el llamante no debe borrarlas a ciegas; la GC no existe).
     * [uncertainPublishedKeys] son las claves cuya publicacion el almacen declaro **incierta**:
     * pueden existir, se conservan y **no** se presentan como verificadas ni se mezclan con las
     * anteriores. [temporaryFileRetained] y [directoryRetained] reflejan, tal cual, los residuos que
     * el propio almacen informo al rechazar la escritura (temporal y directorio de clave que no se
     * pudieron retirar): esta pieza no los borra ni los confunde con sus spools.
     * [spoolCleanupFailed] se refiere **solo** a los spools creados por esta operacion en el staging
     * del llamante; un residuo del almacen no lo activa.
     */
    data class Failed(
        val failure: MediaBackupPreparationFailure,
        val publishedOrphanKeys: List<String>,
        val spoolCleanupFailed: Boolean,
        val uncertainPublishedKeys: List<String> = emptyList(),
        val temporaryFileRetained: Boolean = false,
        val directoryRetained: Boolean = false,
    ) : MediaBackupImagePreparationResult
}

/**
 * Fallo tipado de la preparacion. Ninguno implica que se haya borrado o modificado ningun fichero
 * ajeno: como mucho se eliminan los spools propios de esta operacion.
 */
sealed class MediaBackupPreparationFailure(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** Cancelacion pedida por el llamante (o interrupcion del hilo). */
    data object Canceled : MediaBackupPreparationFailure("media backup image preparation canceled")

    /** La fuente no describe un conjunto coherente (claves duplicadas, sellos invalidos...). */
    class SourceRejected(detail: String) : MediaBackupPreparationFailure(detail)

    /** El conjunto declarado supera un presupuesto antes de cualquier E/S. */
    class BudgetExceeded(detail: String) : MediaBackupPreparationFailure(detail)

    /** Fallo en una etapa concreta de una imagen; [detail] no contiene rutas. */
    class StepFailed(
        val stage: MediaBackupPreparationStage,
        val imageKey: String,
        detail: String,
        cause: Throwable? = null,
    ) : MediaBackupPreparationFailure(detail, cause)

    /** El almacen rechazo la escritura con su error tipado (incluye `ENOSPC` y clave existente). */
    class StoreRejected(
        val imageKey: String,
        val error: AngleImageStoreError,
    ) : MediaBackupPreparationFailure("media backup image $imageKey was rejected by the private store")

    /**
     * El almacen informo de un fallo **y** el flujo de lectura del spool tampoco se pudo cerrar.
     *
     * [storeFailure] es el resultado autoritativo del almacen (error tipado, publicacion incierta y
     * residuos incluidos): el fallo de cierre no lo descarta. [closeFailure] es el error al cerrar el
     * flujo; se capturan los dos tipos honestos de un cierre (`IOException` y `RuntimeException`, por
     * ejemplo un hook de cierre que falla en tiempo de ejecucion), nunca un `Error` fatal. Se
     * conservan las dos causas: [storeFailure] es la causa (`cause`) y [closeFailure] la excepcion
     * suprimida. Ninguna se presenta como exito.
     */
    class StoreFailureAndSpoolCloseFailed(
        val storeFailure: MediaBackupPreparationFailure,
        val closeFailure: Throwable,
    ) : MediaBackupPreparationFailure(
        "media backup image preparation failed at the private store and the spool stream could not be closed",
        storeFailure,
    ) {
        init {
            addSuppressed(closeFailure)
        }
    }

    /** La identidad publicada no coincide con lo declarado (clave, tamano, SHA, duplicado...). */
    class IdentityMismatch(
        val stage: MediaBackupPreparationStage,
        val imageKey: String,
        detail: String,
    ) : MediaBackupPreparationFailure(detail)
}

/**
 * Coordinador de la preparacion de imagenes de una restauracion (G4a).
 *
 * Uso de produccion:
 * ```
 * val preparer = MediaBackupImagePreparer(stagingDirectory, privateStore)
 * val result = preparer.prepare(preparedImport)   // bloqueante: la frontera la decide el llamante
 * ```
 * [stagingDirectory] es el directorio **privado** del llamante donde viven los spools; [preparedImport]
 * lo posee el llamante y **no** se cierra aqui.
 */
class MediaBackupImagePreparer(
    private val stagingDirectory: File,
    private val probe: MediaBackupPreparationProbePort,
    private val store: MediaBackupPreparationStorePort,
    private val limits: MediaBackupPreparationLimits = MediaBackupPreparationLimits(),
    private val filePort: MediaBackupPreparationFilePort = DefaultMediaBackupPreparationFilePort,
) {

    /** Composicion de produccion: probe real de la plataforma y almacen privado inyectado. */
    constructor(
        stagingDirectory: File,
        store: PrivateAngleImageStore,
        budget: MediaBackupPngBudget = MediaBackupPngBudget(),
        limits: MediaBackupPreparationLimits = MediaBackupPreparationLimits(),
        filePort: MediaBackupPreparationFilePort = DefaultMediaBackupPreparationFilePort,
    ) : this(
        stagingDirectory = stagingDirectory,
        probe = MediaBackupPngProbeAdapter(MediaBackupPngProbe(budget)),
        store = MediaBackupPrivateStoreAdapter(store),
        limits = limits,
        filePort = filePort,
    )

    /**
     * Prepara el contenedor ya verificado [prepared]. **No** cierra [prepared]: lo posee el llamante.
     *
     * Bloqueante: hace copia, `fsync`, probe con decode y publicacion. No hay variante `suspend`:
     * el llamante decide su propia frontera, que debe ser **no cancelable** si el resultado no se
     * puede perder, y debe registrar la operacion para poder recuperar huerfanos si el proceso
     * muere. Envolver esta llamada en una corrutina cancelable no evita ese riesgo.
     */
    fun prepare(
        prepared: MediaBackupPreparedImport,
        cancellation: MediaBackupArchiveCancellation = MediaBackupArchiveCancellation.NEVER,
    ): MediaBackupImagePreparationResult = prepare(prepared.asPreparationSource(), cancellation)

    /**
     * Nucleo de la preparacion sobre una fuente abstracta (usado por las pruebas JVM con puertos
     * fake). Devuelve el conjunto completo o un fallo sin imagenes.
     */
    fun prepare(
        source: MediaBackupImagePreparationSource,
        cancellation: MediaBackupArchiveCancellation = MediaBackupArchiveCancellation.NEVER,
    ): MediaBackupImagePreparationResult {
        // Claves publicadas por ESTA operacion: se conservan y se informan si algo falla despues.
        val published = sortedSetOf<String>()
        val spools = ArrayList<File>(4)
        var cleanupFailed = false

        fun failed(
            failure: MediaBackupPreparationFailure,
            uncertainKeys: List<String> = emptyList(),
            temporaryFileRetained: Boolean = false,
            directoryRetained: Boolean = false,
        ) = MediaBackupImagePreparationResult.Failed(
            failure = failure,
            publishedOrphanKeys = published.toList(),
            spoolCleanupFailed = cleanupFailed,
            uncertainPublishedKeys = uncertainKeys.toList(),
            temporaryFileRetained = temporaryFileRetained,
            directoryRetained = directoryRetained,
        )

        try {
            if (cancellation.isCanceled()) return failed(MediaBackupPreparationFailure.Canceled)

            // 1. Declaraciones y presupuestos ANTES de tocar disco: un fallo de contrato no deja
            //    ni un spool ni una clave publicada.
            val declarations = try {
                source.declarations
            } catch (failure: MediaBackupPreparationException) {
                return failed(MediaBackupPreparationFailure.SourceRejected(failure.message ?: "source rejected"))
            } catch (failure: IOException) {
                return failed(MediaBackupPreparationFailure.SourceRejected("image source rejected"))
            } catch (failure: RuntimeException) {
                // Una declaracion que no se puede ni construir (clave no canonica, sello invalido)
                // es un rechazo de contrato de la fuente, no una excepcion que escape al llamante.
                return failed(MediaBackupPreparationFailure.SourceRejected("image source declarations are not usable"))
            }
            val declarationsFailure = checkDeclarations(declarations)
            if (declarationsFailure != null) return failed(declarationsFailure)

            // 2. Una imagen detras de otra. El orden es el determinista de las declaraciones.
            val preparedEntries = ArrayList<Pair<String, StoredAngleImage>>(declarations.size)
            val unsyncedKeys = ArrayList<String>(declarations.size)
            val storedKeys = HashSet<String>(declarations.size * 2)
            for (declaration in declarations) {
                if (cancellation.isCanceled()) return failed(MediaBackupPreparationFailure.Canceled)
                val spool = try {
                    filePort.createSpoolFile(stagingDirectory)
                } catch (failure: IOException) {
                    return failed(
                        MediaBackupPreparationFailure.StepFailed(
                            MediaBackupPreparationStage.SPOOL,
                            declaration.imageKey,
                            "spool could not be created",
                            failure,
                        ),
                    )
                }
                spools += spool
                var outcome: StepOutcome = StepOutcome.Failure(
                    MediaBackupPreparationFailure.StepFailed(
                        MediaBackupPreparationStage.SPOOL,
                        declaration.imageKey,
                        "spool was not processed",
                    ),
                )
                try {
                    outcome = prepareOne(declaration, spool, source, cancellation) { publishedKey ->
                        published += publishedKey
                    }
                } catch (failure: IOException) {
                    outcome = StepOutcome.Failure(
                        MediaBackupPreparationFailure.StepFailed(
                            MediaBackupPreparationStage.STORE,
                            declaration.imageKey,
                            "unexpected I/O failure",
                            failure,
                        ),
                    )
                } catch (failure: RuntimeException) {
                    outcome = StepOutcome.Failure(
                        MediaBackupPreparationFailure.StepFailed(
                            MediaBackupPreparationStage.STORE,
                            declaration.imageKey,
                            "unexpected failure",
                            failure,
                        ),
                    )
                } finally {
                    spools.remove(spool)
                    if (!deleteOwnSpool(spool)) cleanupFailed = true
                }
                when (outcome) {
                    is StepOutcome.Failure -> return failed(
                        failure = outcome.failure,
                        uncertainKeys = outcome.uncertainPublishedKeys,
                        temporaryFileRetained = outcome.temporaryFileRetained,
                        directoryRetained = outcome.directoryRetained,
                    )
                    is StepOutcome.Success -> {
                        // Verificacion de unicidad DENTRO de la operacion: dos claves nuevas iguales
                        // jamas se pueden publicar (el almacen lo impide con mkdir exclusivo, pero
                        // el resultado de un puerto inyectado tampoco se cree).
                        if (!storedKeys.add(outcome.stored.imageKey)) {
                            return failed(
                                MediaBackupPreparationFailure.IdentityMismatch(
                                    MediaBackupPreparationStage.STORE,
                                    declaration.imageKey,
                                    "media backup image ${declaration.imageKey} was published with a repeated key",
                                ),
                            )
                        }
                        preparedEntries += declaration.imageKey to outcome.stored
                        if (!outcome.directoryEntriesSynced) unsyncedKeys += outcome.stored.imageKey
                    }
                }
            }
            // Cancelacion pedida al final: no se entrega un conjunto que el llamante ya no quiere.
            if (cancellation.isCanceled()) return failed(MediaBackupPreparationFailure.Canceled)
            return MediaBackupImagePreparationResult.Prepared(
                images = MediaBackupPreparedImages(preparedEntries, unsyncedKeys),
                spoolCleanupFailed = cleanupFailed,
            )
        } finally {
            // Red de seguridad: cualquier spool propio que siguiera registrado se borra uno a uno
            // (nunca `deleteRecursively`, nunca un fichero ajeno).
            for (spool in spools.toList()) {
                if (!deleteOwnSpool(spool)) cleanupFailed = true
            }
        }
    }

    // --- Una imagen -----------------------------------------------------------------------------

    private fun prepareOne(
        declaration: MediaBackupImageDeclaration,
        spool: File,
        source: MediaBackupImagePreparationSource,
        cancellation: MediaBackupArchiveCancellation,
        onPublished: (String) -> Unit,
    ): StepOutcome {
        val imageKey = declaration.imageKey

        // (a) Copia en streaming al spool propio + fsync.
        val sink = try {
            filePort.openSpoolSink(spool)
        } catch (failure: IOException) {
            return StepOutcome.Failure(stepFailure(MediaBackupPreparationStage.SPOOL, imageKey, "spool sink", failure))
        }
        var closeFailure: IOException? = null
        try {
            val transfer = try {
                source.copyImageTo(imageKey, MediaBackupSpoolOutputStream(sink, declaration.byteCount), cancellation)
            } catch (failure: MediaBackupImportException.Canceled) {
                return StepOutcome.Failure(MediaBackupPreparationFailure.Canceled)
            } catch (failure: IOException) {
                // Un exceso de la fuente es un rechazo de contrato del spool, no de la copia.
                val stage = if (isSpoolOverflow(failure)) {
                    MediaBackupPreparationStage.SPOOL
                } else {
                    MediaBackupPreparationStage.COPY
                }
                return StepOutcome.Failure(stepFailure(stage, imageKey, "image copy", failure))
            }
            // Tamano y SHA-256 re-verificados contra el manifiesto (no se confia en el nombre).
            if (transfer.byteCount != declaration.byteCount ||
                !transfer.sha256.equals(declaration.sha256, ignoreCase = true)
            ) {
                return StepOutcome.Failure(
                    MediaBackupPreparationFailure.IdentityMismatch(
                        MediaBackupPreparationStage.COPY,
                        imageKey,
                        "media backup image $imageKey was transferred with a different integrity than declared",
                    ),
                )
            }
            try {
                sink.sync()
            } catch (failure: IOException) {
                return StepOutcome.Failure(stepFailure(MediaBackupPreparationStage.SYNC, imageKey, "spool fsync", failure))
            }
        } finally {
            try {
                sink.close()
            } catch (failure: IOException) {
                closeFailure = failure
            }
        }
        closeFailure?.let {
            return StepOutcome.Failure(stepFailure(MediaBackupPreparationStage.SYNC, imageKey, "spool close", it))
        }

        // (b) PNG real con presupuesto ANTES del decode, y contraste del resultado confirmado.
        val verified = try {
            probe.probe(
                declaration.expectation(),
                { filePort.openSpoolSource(spool) },
                cancellation,
            )
        } catch (failure: MediaBackupPngProbeException.Canceled) {
            return StepOutcome.Failure(MediaBackupPreparationFailure.Canceled)
        } catch (failure: IOException) {
            return StepOutcome.Failure(stepFailure(MediaBackupPreparationStage.PROBE, imageKey, "PNG probe", failure))
        }
        if (verified.imageKey != imageKey ||
            verified.byteCount != declaration.byteCount ||
            !verified.sha256.equals(declaration.sha256, ignoreCase = true) ||
            verified.width != declaration.width ||
            verified.height != declaration.height
        ) {
            return StepOutcome.Failure(
                MediaBackupPreparationFailure.IdentityMismatch(
                    MediaBackupPreparationStage.PROBE,
                    imageKey,
                    "media backup image $imageKey was probed with a different identity than declared",
                ),
            )
        }

        // (c) Publicacion en el almacen privado con clave NUEVA, desde el spool verificado.
        //     El resultado del almacen se captura y se registra SIEMPRE antes de cerrar el flujo de
        //     lectura: un fallo al cerrar no puede borrar ni la clave ya publicada ni un `Failed`
        //     con publicacion incierta o residuos. Si el cierre tambien falla, ambas causas se
        //     conservan en el resultado: ninguna se descarta ni se convierte en exito.
        val input = try {
            filePort.openSpoolSource(spool)
        } catch (failure: IOException) {
            return StepOutcome.Failure(
                stepFailure(MediaBackupPreparationStage.STORE, imageKey, "spool source open", failure),
            )
        }
        var written: AngleImageWriteResult? = null
        var writeFailure: Throwable? = null
        var readCloseFailure: Throwable? = null
        try {
            written = store.write(input, declaration.byteCount) { cancellation.isCanceled() }
            val storedNow = written
            if (storedNow is AngleImageWriteResult.Stored) {
                // La clave puede existir desde este instante: se informa como huerfano si algo
                // falla despues (no se borra nunca desde aqui).
                onPublished(storedNow.image.imageKey)
            }
        } catch (failure: IOException) {
            writeFailure = failure
        } catch (failure: RuntimeException) {
            writeFailure = failure
        } finally {
            // El cierre nunca puede tapar el resultado del almacen ya capturado: se capturan los dos
            // tipos honestos de fallo de cierre (`IOException` y `RuntimeException`, p. ej. un hook
            // que falla al volver del `use`). Los `Error` fatales (`OutOfMemoryError`,
            // `StackOverflowError`, `VirtualMachineError`...) NO se capturan a proposito: se propagan
            // y jamas se convierten en un resultado de exito.
            try {
                input.close()
            } catch (failure: IOException) {
                readCloseFailure = failure
            } catch (failure: RuntimeException) {
                readCloseFailure = failure
            }
        }
        val result = written
        if (result == null) {
            val failure = stepFailure(
                MediaBackupPreparationStage.STORE,
                imageKey,
                "store write",
                writeFailure ?: IOException("the private store returned no result"),
            )
            // El cierre tambien fallo: se reporta como causa suprimida, no se oculta ninguna de las
            // dos. El fallo de la escritura sigue siendo la causa principal del rechazo.
            readCloseFailure?.let(failure::addSuppressed)
            return StepOutcome.Failure(failure)
        }
        if (readCloseFailure != null && result is AngleImageWriteResult.Stored) {
            // El almacen publico y verifico la imagen, pero el flujo de lectura no cerro: la clave
            // queda registrada como huerfano verificado y la operacion no se presenta como exito.
            return StepOutcome.Failure(
                stepFailure(MediaBackupPreparationStage.STORE, imageKey, "spool close after publication", readCloseFailure),
            )
        }
        return when (result) {
            is AngleImageWriteResult.Failed -> {
                val storeFailure: MediaBackupPreparationFailure =
                    if (result.error == AngleImageStoreError.Canceled) {
                        MediaBackupPreparationFailure.Canceled
                    } else {
                        MediaBackupPreparationFailure.StoreRejected(imageKey, result.error)
                    }
                StepOutcome.Failure(
                    // Fallo del almacen + cierre fallido: se conserva el resultado autoritativo del
                    // almacen (error tipado, incertidumbre, residuos) y el fallo de cierre como dos
                    // causas distintas. Ninguna se descarta y el resultado no finge exito.
                    failure = readCloseFailure
                        ?.let { close ->
                            MediaBackupPreparationFailure.StoreFailureAndSpoolCloseFailed(storeFailure, close)
                        }
                        ?: storeFailure,
                    // `PublicationUncertain` no significa "no se publico nada": la clave puede existir
                    // y se conserva, pero se informa aparte para no presentarla como verificada.
                    uncertainPublishedKeys = (result.error as? AngleImageStoreError.PublicationUncertain)
                        ?.let { listOf(it.imageKey) }
                        .orEmpty(),
                    // Residuos del almacen: se propagan tal cual, incluida la cancelacion. Esta pieza no
                    // los borra ni los disfraza de fallo de limpieza de spool.
                    temporaryFileRetained = result.temporaryFileRetained,
                    directoryRetained = result.directoryRetained,
                )
            }
            is AngleImageWriteResult.Stored -> {
                val stored = result.image
                val mismatch = when {
                    !AngleImageKey.isCanonical(stored.imageKey) ->
                        "the published key is not canonical"
                    stored.imageKey == imageKey ->
                        "the published key reuses the imported key"
                    stored.byteCount != declaration.byteCount ->
                        "the published byteCount does not match the declaration"
                    !stored.sha256Hex.equals(declaration.sha256, ignoreCase = true) ->
                        "the published SHA-256 does not match the declaration"
                    else -> null
                }
                if (mismatch != null) {
                    StepOutcome.Failure(
                        MediaBackupPreparationFailure.IdentityMismatch(
                            MediaBackupPreparationStage.STORE,
                            imageKey,
                            "media backup image $imageKey: $mismatch",
                        ),
                    )
                } else {
                    StepOutcome.Success(imageKey, stored, result.durability.directoryEntriesSynced)
                }
            }
        }
    }

    private fun stepFailure(
        stage: MediaBackupPreparationStage,
        imageKey: String,
        what: String,
        cause: Throwable,
    ): MediaBackupPreparationFailure.StepFailed = MediaBackupPreparationFailure.StepFailed(
        stage = stage,
        imageKey = imageKey,
        detail = "media backup image $imageKey failed during $what",
        cause = cause,
    )

    /** Borra **solo** el spool propio; devuelve `true` si ya no existe. Nunca recursivo. */
    private fun deleteOwnSpool(spool: File): Boolean = try {
        filePort.deleteSpoolFile(spool)
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    /**
     * Presupuestos y coherencia de las declaraciones, sin E/S: numero de imagenes, tamano por
     * imagen, suma exacta (con `Math.addExact`) y claves canonicas sin duplicados.
     */
    private fun checkDeclarations(declarations: List<MediaBackupImageDeclaration>): MediaBackupPreparationFailure? {
        if (declarations.size > limits.maxImages) {
            return MediaBackupPreparationFailure.BudgetExceeded(
                "media backup declares ${declarations.size} images, above the ${limits.maxImages} allowed",
            )
        }
        val keys = HashSet<String>(declarations.size * 2)
        var total = 0L
        for (declaration in declarations) {
            if (!AngleImageKey.isCanonical(declaration.imageKey)) {
                return MediaBackupPreparationFailure.SourceRejected("a declared image key is not canonical")
            }
            if (!keys.add(declaration.imageKey)) {
                return MediaBackupPreparationFailure.SourceRejected("a declared image key is repeated")
            }
            if (declaration.byteCount < 1L || declaration.byteCount > limits.maxImageBytes) {
                return MediaBackupPreparationFailure.BudgetExceeded(
                    "media backup image is declared with a byteCount outside the allowed per-image budget",
                )
            }
            total = try {
                Math.addExact(total, declaration.byteCount)
            } catch (_: ArithmeticException) {
                return MediaBackupPreparationFailure.BudgetExceeded("the declared total image bytes overflow")
            }
            if (total > limits.maxTotalBytes) {
                return MediaBackupPreparationFailure.BudgetExceeded(
                    "media backup declares more image bytes than the allowed total budget",
                )
            }
        }
        return null
    }

    private sealed interface StepOutcome {
        data class Success(
            val importedKey: String,
            val stored: StoredAngleImage,
            val directoryEntriesSynced: Boolean,
        ) : StepOutcome

        data class Failure(
            val failure: MediaBackupPreparationFailure,
            val uncertainPublishedKeys: List<String> = emptyList(),
            val temporaryFileRetained: Boolean = false,
            val directoryRetained: Boolean = false,
        ) : StepOutcome
    }
}

// --- Adaptadores de produccion ------------------------------------------------------------------

/**
 * Sumidero acotado de un spool: rechaza (sin escribir) cualquier byte que supere el `byteCount`
 * declarado, de modo que ni una fuente rota ni un contenedor manipulado pueden llenar el disco.
 */
private class MediaBackupSpoolOutputStream(
    private val sink: MediaBackupPreparationSink,
    private val limit: Long,
) : OutputStream() {

    private var written: Long = 0L

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, length: Int) {
        if (length == 0) return
        val next = written + length.toLong()
        if (next < written || next > limit) {
            throw MediaBackupPreparationException.SpoolOverflow(length.toLong())
        }
        sink.write(b, off, length)
        written = next
    }

    override fun flush() = Unit
}

/** Fallo interno del spool propio; viaja como [IOException] hasta el coordinador. */
open class MediaBackupPreparationException(message: String) : IOException(message) {

    /** El origen entrego mas bytes que los declarados; el spool no se amplia. */
    class SpoolOverflow(val extraBytes: Long) :
        MediaBackupPreparationException("media backup spool refuses bytes beyond the declared byteCount")
}

private fun isSpoolOverflow(failure: Throwable): Boolean {
    var current: Throwable? = failure
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is MediaBackupPreparationException.SpoolOverflow) return true
        current = current.cause
        depth++
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 8

/** Longitud del SHA-256 hexadecimal declarado por el manifiesto. */
private const val SHA256_HEX_LENGTH = 64

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** Puerto de ficheros real: temporales exclusivos en el staging, `fsync` por descriptor. */
object DefaultMediaBackupPreparationFilePort : MediaBackupPreparationFilePort {

    /** Prefijo propio de los spools; nunca coincide con una clave canonica. */
    const val SPOOL_PREFIX: String = ".media-restore-spool-"

    private const val SPOOL_SUFFIX = ".part"

    override fun createSpoolFile(stagingDirectory: File): File {
        if (Files.isSymbolicLink(stagingDirectory.toPath())) {
            throw IOException("the media restore staging directory must not be a symbolic link")
        }
        if (!Files.isDirectory(stagingDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("the media restore staging directory does not exist or is not a directory")
        }
        return File.createTempFile(SPOOL_PREFIX, SPOOL_SUFFIX, stagingDirectory)
    }

    override fun openSpoolSink(file: File): MediaBackupPreparationSink {
        val stream = FileOutputStream(file, /* append = */ false)
        return object : MediaBackupPreparationSink {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                stream.write(buffer, offset, length)
            }

            override fun sync() {
                stream.fd.sync()
            }

            override fun close() {
                stream.close()
            }
        }
    }

    override fun openSpoolSource(file: File): InputStream {
        if (Files.isSymbolicLink(file.toPath())) {
            throw IOException("a media restore spool must not be a symbolic link")
        }
        return FileInputStream(file)
    }

    override fun deleteSpoolFile(file: File): Boolean {
        if (!file.name.startsWith(SPOOL_PREFIX)) return false
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return !file.exists()
        Files.deleteIfExists(file.toPath())
        return !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    }
}

/** Probe de produccion: delega en [MediaBackupPngProbe] sin cambiar su contrato. */
internal class MediaBackupPngProbeAdapter(private val probe: MediaBackupPngProbe) : MediaBackupPreparationProbePort {

    override fun probe(
        expectation: MediaBackupPngExpectation,
        openStream: () -> InputStream,
        cancellation: MediaBackupArchiveCancellation,
    ): MediaBackupPngVerified = probe.probe(expectation, openStream, cancellation)
}

/** Almacen de produccion: delega en [PrivateAngleImageStore.write] sin cambiar su contrato. */
internal class MediaBackupPrivateStoreAdapter(private val store: PrivateAngleImageStore) :
    MediaBackupPreparationStorePort {

    override fun write(
        source: InputStream,
        expectedByteCount: Long,
        shouldCancel: () -> Boolean,
    ): AngleImageWriteResult = store.write(source, expectedByteCount, shouldCancel)
}

// --- Fuente de produccion: MediaBackupPreparedImport --------------------------------------------

/**
 * Adaptador del contenedor ya verificado: las declaraciones salen del manifiesto validado y los
 * bytes de [MediaBackupPreparedImport.copyImageTo], que re-verifica tamano, CRC-32 y SHA-256.
 *
 * El adaptador **no** cierra el contenedor: su propiedad es del llamante.
 */
internal fun MediaBackupPreparedImport.asPreparationSource(): MediaBackupImagePreparationSource =
    MediaBackupPreparedImportSource(this)

private class MediaBackupPreparedImportSource(private val prepared: MediaBackupPreparedImport) :
    MediaBackupImagePreparationSource {

    /**
     * Declaraciones en orden determinista (clave ascendente). Se exige coherencia entre las tres
     * vistas del manifiesto (entradas `images`, fotogramas y contadores del contenedor): si no
     * cuadran, la operacion falla antes de cualquier E/S en vez de preparar una imagen y no la otra.
     */
    override val declarations: List<MediaBackupImageDeclaration> by lazy {
        val manifest = prepared.manifest
        val framesByKey = manifest.angleV2.frames.groupBy { it.imageKey }
        if (framesByKey.values.any { it.size != 1 }) {
            throw MediaBackupPreparationException("a media backup image key is not referenced by exactly one frame")
        }
        val entries = manifest.angleV2.images.sortedBy { it.imageKey }
        if (entries.size != prepared.imageKeys.size || entries.map { it.imageKey }.toSet() != prepared.imageKeys.toSet()) {
            throw MediaBackupPreparationException("the media backup image declarations do not match the prepared import")
        }
        entries.map { entry ->
            val frame = framesByKey[entry.imageKey]?.singleOrNull()
                ?: throw MediaBackupPreparationException("a declared media backup image has no frame")
            if (frame.imageByteCount != entry.byteCount ||
                !frame.imageSha256.equals(entry.sha256, ignoreCase = true) ||
                prepared.imageByteCount(entry.imageKey) != entry.byteCount
            ) {
                throw MediaBackupPreparationException("a media backup image declaration is inconsistent")
            }
            MediaBackupImageDeclaration(
                imageKey = entry.imageKey,
                byteCount = entry.byteCount,
                sha256 = entry.sha256,
                width = frame.imageWidth,
                height = frame.imageHeight,
                mimeType = frame.imageMimeType,
            )
        }
    }

    override fun copyImageTo(
        imageKey: String,
        output: OutputStream,
        cancellation: MediaBackupArchiveCancellation,
    ): MediaBackupImageTransfer = prepared.copyImageTo(imageKey, output, cancellation)
}
