package com.openjump.app.ui.angles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.openjump.app.data.angles.AngleImageIntegrity
import com.openjump.app.data.angles.AngleImageKey
import com.openjump.app.data.angles.AngleImageReadResult
import com.openjump.app.data.angles.AngleImageStoreError
import com.openjump.app.data.angles.AngleImageStoreLimits
import com.openjump.app.data.angles.toLowerHexString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Estado de la imagen anotada de un fotograma en el detalle guardado.
 *
 * La imagen se publica APARTE de los datos ([AngleV2SavedDetailFrame]) porque su lectura es E/S
 * verificable y puede fallar por causas distintas. Ningún estado de fallo borra mediciones:
 * - [Absent]: el fichero ya no está (por ejemplo tras limpiar el almacén); los ángulos se consultan.
 * - [Corrupted]: el fichero está pero NO coincide con el tamaño/SHA-256/dimensiones declarados, o
 *   no se puede decodificar de forma acotada. Se muestra como no disponible, nunca como imagen
 *   válida ("captura de controles" no es evidencia de imagen correcta).
 * - [Unreadable]: fallo de E/S o de contrato del almacén (clave no canónica); tampoco se presenta
 *   como éxito y no modifica nada.
 */
sealed interface AngleV2SavedImage {

    /** Verificación en curso. */
    data object Loading : AngleV2SavedImage

    /** Bytes verificados y bitmap acotado listo para mostrarse. */
    data class Available(val bitmap: Bitmap) : AngleV2SavedImage

    /** No hay fichero para la clave del fotograma. */
    data object Absent : AngleV2SavedImage

    /** El fichero no coincide con lo declarado o no se pudo decodificar dentro del presupuesto. */
    data object Corrupted : AngleV2SavedImage

    /** El almacén no pudo entregar los bytes (E/S, clave/expectativa inválida). */
    data object Unreadable : AngleV2SavedImage
}

/**
 * Resultado de preparar la imagen verificada de un fotograma para compartirla fuera de la app.
 *
 * Compartir no muta el resultado guardado: sólo se publica una copia PNG propia y verificada en un
 * directorio privado de compartición y se entrega su URI **de un solo fichero**. Nunca se comparte
 * el almacén, ni `filesDir`, ni el vídeo, ni una imagen cuya integridad no se haya comprobado.
 */
sealed interface AngleV2FrameShareResult {

    /**
     * Copia verificada publicada y su URI de un solo fichero.
     *
     * El fichero **no se borra al entregar el Intent**: el receptor puede leerlo después de que esta
     * pantalla desaparezca y esta pieza no recolecta nada.
     */
    data class Ready(val uri: Uri) : AngleV2FrameShareResult

    /** El almacén ya no tiene el PNG de ese fotograma: no hay nada que compartir. */
    data object Missing : AngleV2FrameShareResult

    /** El PNG no coincide con lo declarado por la fila o no se puede verificar: no se comparte. */
    data object Corrupted : AngleV2FrameShareResult

    /** E/S, clave ajena al fotograma o fallo al publicar la copia: no se comparte nada. */
    data object Unavailable : AngleV2FrameShareResult
}

/**
 * Traduce un resultado del almacén privado al estado de la UI.
 *
 * El almacén ya comprobó tamaño y SHA-256 antes de entregar los bytes; aquí sólo se decide qué
 * mensaje corresponde y se decodifica con presupuesto acotado ([decodeVerifiedAngleImage]).
 */
fun angleV2SavedImage(
    result: AngleImageReadResult<ByteArray>,
    frame: AngleV2SavedDetailFrame,
): AngleV2SavedImage {
    if (result is AngleImageReadResult.Failed) {
        return when (result.error) {
            is AngleImageStoreError.Absent -> AngleV2SavedImage.Absent
            is AngleImageStoreError.Corrupted -> AngleV2SavedImage.Corrupted
            else -> AngleV2SavedImage.Unreadable
        }
    }
    val bytes = deliveredBytes(result) ?: return AngleV2SavedImage.Unreadable
    return decodeVerifiedAngleImage(
        bytes = bytes,
        declaredWidth = frame.imageWidth,
        declaredHeight = frame.imageHeight,
    )
}

/**
 * Extrae los bytes de un resultado entregado.
 *
 * El almacén devuelve un resultado genérico; esta función es el único punto que conoce el tipo
 * concreto ([ByteArray]) y no aporta ninguna garantía a mayores: [angleV2SavedImage] ya descartó el
 * caso de fallo antes de llamarla.
 */
private fun deliveredBytes(result: AngleImageReadResult<ByteArray>): ByteArray? =
    (result as? AngleImageReadResult.Delivered<ByteArray>)?.value

/**
 * Decodifica un PNG **ya verificado** (tamaño + SHA-256) sin fiarse de las dimensiones declaradas.
 *
 * Defensas, en orden:
 * 1. se leen las dimensiones REALES de la cabecera con `inJustDecodeBounds` (no se reserva bitmap);
 * 2. deben coincidir con las declaradas por la fila (`imageWidth`/`imageHeight`): si no, la fila no
 *    describe esos bytes y se informa [AngleV2SavedImage.Corrupted] en vez de mostrar otra imagen;
 * 3. el muestreo ([angleV2ImageSampleSize]) acota lado y píxeles del bitmap resultante, así que un
 *    PNG de dimensiones enormes no puede provocar OOM por la vía de la decodificación;
 * 4. tras decodificar se comprueba otra vez el presupuesto y, si el decodificador lo ignoró, se
 *    recicla el bitmap y se informa corrupción (nunca se entrega un bitmap sin acotar).
 *
 * Se ejecuta fuera del hilo principal (es E/S + memoria) y no escribe nada: no modifica el archivo,
 * ni la base, ni el vídeo.
 */
fun decodeVerifiedAngleImage(
    bytes: ByteArray,
    declaredWidth: Int,
    declaredHeight: Int,
): AngleV2SavedImage {
    if (bytes.isEmpty() || declaredWidth <= 0 || declaredHeight <= 0) return AngleV2SavedImage.Corrupted
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return AngleV2SavedImage.Corrupted
    if (width != declaredWidth || height != declaredHeight) return AngleV2SavedImage.Corrupted
    val sampleSize = angleV2ImageSampleSize(width, height) ?: return AngleV2SavedImage.Corrupted
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
        .getOrNull()
        ?: return AngleV2SavedImage.Corrupted
    if (!withinDecodeBudget(bitmap)) {
        bitmap.recycle()
        return AngleV2SavedImage.Corrupted
    }
    return AngleV2SavedImage.Available(bitmap)
}

private fun withinDecodeBudget(bitmap: Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return false
    if (width > AngleV2SavedImageBudget.MAX_DIMENSION) return false
    if (height > AngleV2SavedImageBudget.MAX_DIMENSION) return false
    return width.toLong() * height.toLong() <= AngleV2SavedImageBudget.MAX_PIXELS
}

/**
 * Constantes de la compartición de la imagen de un fotograma V2.
 *
 * Lo que se comparte es **sólo** el PNG ya verificado de un fotograma: ni vídeo, ni el almacén
 * privado, ni una raíz de directorios.
 */
object AngleV2FrameShare {

    /**
     * Subdirectorio propio (bajo `cacheDir`) donde se publican las copias que se comparten.
     *
     * Es el **único** `path` del `FileProvider` dedicado a imágenes (`@xml/file_paths`): el almacén
     * privado (`filesDir/angle-images`) y el resto de `cacheDir`/`filesDir` no tienen ruta declarada,
     * así que el proveedor no puede servir nada que no se haya publicado aquí a propósito.
     */
    const val DIRECTORY_NAME: String = "shared-angle-frames"

    /** Único tipo de contenido de esta pantalla: PNG ya verificado del fotograma (nunca vídeo). */
    const val MIME_TYPE: String = "image/png"

    /** Tope de bytes de una copia compartible: el mismo que acepta el almacén. */
    const val MAX_BYTES: Long = AngleImageStoreLimits.DEFAULT_MAX_IMAGE_BYTES

    /** Caracteres del SHA-256 declarado que identifican el nombre de la copia (prefijo opaco). */
    private const val NAME_DIGEST_CHARS: Int = 16

    /**
     * Nombre de la copia publicada: prefijo del SHA-256 **declarado** por la fila y extensión PNG.
     *
     * El nombre no contiene la clave del almacén (el receptor de la URI no ve la clave interna) y
     * hace que compartir dos veces el mismo fotograma reutilice la misma copia.
     */
    internal fun fileNameFor(sha256Hex: String): String =
        "${sha256Hex.take(NAME_DIGEST_CHARS)}.png"
}

/**
 * Publica (o reutiliza) la copia verificada del PNG de un fotograma y devuelve su URI exacta.
 *
 * **Por qué una copia propia y no el fichero del almacén.** El almacén privado demuestra que el
 * fichero que entregó coincide con lo declarado, pero entre esa verificación y la lectura del
 * receptor el fichero del almacén sigue siendo el mismo objeto mutable: una mutación cooperante
 * (otro escritor del proceso con acceso al sandbox) podría cambiar sus bytes después de verificarlo.
 * Aquí los bytes entregados por el almacén se **vuelven a medir y a hashear** y sólo entonces se
 * escriben en una copia propia, de nombre derivado del SHA declarado. Así lo que se entrega es, por
 * construcción, exactamente el contenido verificado; y el proveedor no necesita exponer el árbol del
 * almacén, sólo este directorio de compartición.
 *
 * **Alcance de la garantía.** Se cubren escritores cooperantes del proceso y verificaciones de
 * integridad de la fila. **No** se promete aislamiento frente a otro proceso del mismo UID ni frente
 * a código hostil dentro de la app: quien pueda escribir en el sandbox privado puede alterar
 * cualquier fichero, incluida la copia, después de verificarla. Es el mismo límite que declara el
 * almacén.
 *
 * **Vida útil.** La copia no se borra al entregar el Intent (el receptor puede leerla más tarde) y
 * esta pieza no hace recolección. El nombre es el prefijo del SHA-256 declarado, así que compartir
 * el mismo fotograma reutiliza la misma copia en lugar de acumular duplicados; cada `frame` distinto
 * añade como mucho un fichero PNG acotado a [AngleV2FrameShare.MAX_BYTES] bajo `cacheDir`, que el
 * sistema puede reclamar por su cuenta.
 *
 * @param cacheRoot caché privada real de la app (`cacheDir`); el directorio de compartición se crea
 *   dentro y la ruta canónica resultante debe colgar de ella.
 * @param readVerifiedBytes lectura verificada del almacén (bloqueante: se invoca en E/S).
 * @param uriForFile construcción de la URI del proveedor para **un** fichero; el llamante decide la
 *   autoridad y el proveedor rechaza cualquier ruta fuera de sus raíces declaradas.
 */
class AngleV2FrameShareStager(
    private val cacheRoot: File,
    private val readVerifiedBytes: (imageKey: String, integrity: AngleImageIntegrity) -> AngleImageReadResult<ByteArray>,
    private val uriForFile: (File) -> Uri,
) {

    /**
     * Prepara la copia de [imageKey] y la entrega como URI de un solo fichero.
     *
     * Nunca lanza por causas esperables: clave no canónica, integridad no utilizable, imagen ausente,
     * imagen trocada, error de E/S o fallo al publicar la copia se devuelven como fallo tipado y
     * **no se comparte nada**. Un error inesperado se informa igual ([AngleV2FrameShareResult.Unavailable])
     * en vez de convertirse en una imagen válida.
     */
    fun publish(imageKey: String, integrity: AngleImageIntegrity): AngleV2FrameShareResult = try {
        publishVerified(imageKey, integrity)
    } catch (_: Exception) {
        // Un fallo inesperado no puede presentarse como imagen compartible ni provocar borrados.
        AngleV2FrameShareResult.Unavailable
    }

    private fun publishVerified(imageKey: String, integrity: AngleImageIntegrity): AngleV2FrameShareResult {
        // Allowlist canónica antes de tocar disco: la clave no es una ruta ni puede expresar `..`.
        if (!AngleImageKey.isCanonical(imageKey)) return AngleV2FrameShareResult.Unavailable
        val expectedSha = integrity.sha256Hex.lowercase()
        if (integrity.byteCount <= 0L || integrity.byteCount > AngleV2FrameShare.MAX_BYTES) {
            return AngleV2FrameShareResult.Corrupted
        }
        if (!SHA256_HEX.matches(expectedSha)) return AngleV2FrameShareResult.Corrupted
        val bytes = when (val result = readVerifiedBytes(imageKey, integrity)) {
            is AngleImageReadResult.Failed -> return shareFailure(result.error)
            is AngleImageReadResult.Delivered -> result.value
        }
        // Segunda medida y hash de los bytes ENTREGADOS: cierra la ventana verificar→entregar del
        // almacén. Si no coinciden con la fila, no se publica copia ni se comparte nada.
        if (bytes.size.toLong() != integrity.byteCount) return AngleV2FrameShareResult.Corrupted
        if (sha256HexOf(bytes) != expectedSha) return AngleV2FrameShareResult.Corrupted
        val directory = shareDirectory() ?: return AngleV2FrameShareResult.Unavailable
        // Nombre por contenido, no por la clave del almacén: la URI que ve el receptor no revela la
        // clave interna, y compartir dos veces el mismo fotograma reutiliza la misma copia.
        val target = File(directory, AngleV2FrameShare.fileNameFor(expectedSha))
        // La copia sólo se entrega si, en este momento, es un fichero regular con exactamente los
        // bytes declarados. Si ya existía y sigue coincidiendo se reutiliza sin reescribir una URI que
        // otra app pudo recibir antes.
        if (!copyMatches(target, integrity.byteCount, expectedSha)) {
            // Hay algo en esa ruta que no es una copia válida (torcida, trocada o de otro tipo): no se
            // sobrescribe ni se borra. Fail-closed: se informa y el sistema reclamará la caché cuando
            // pueda.
            if (fileKind(target) != FileKind.ABSENT) return AngleV2FrameShareResult.Unavailable
            // Una compartición simultánea del mismo fotograma puede crear el fichero antes: quien
            // pierde la creación exclusiva no lo sobrescribe y vuelve a comprobarlo.
            writeCopy(target, bytes)
            if (!copyMatches(target, integrity.byteCount, expectedSha)) {
                return AngleV2FrameShareResult.Unavailable
            }
        }
        val uri = try {
            uriForFile(target)
        } catch (_: Exception) {
            // El proveedor rechaza rutas que se salen de sus raíces declaradas: no se comparte.
            return AngleV2FrameShareResult.Unavailable
        }
        return AngleV2FrameShareResult.Ready(uri)
    }

    /** Traduce el fallo del almacén al caso legible de la pantalla sin convertirlo en éxito. */
    private fun shareFailure(error: AngleImageStoreError): AngleV2FrameShareResult = when (error) {
        is AngleImageStoreError.Absent -> AngleV2FrameShareResult.Missing
        is AngleImageStoreError.Corrupted,
        is AngleImageStoreError.InvalidKey,
        is AngleImageStoreError.InvalidIntegrityExpectation,
        is AngleImageStoreError.SizeMismatch,
        is AngleImageStoreError.EmptyImage,
        is AngleImageStoreError.TooLarge,
        -> AngleV2FrameShareResult.Corrupted

        else -> AngleV2FrameShareResult.Unavailable
    }

    /**
     * Directorio de compartición, creado si falta, o `null` si no es un directorio real dentro de
     * [cacheRoot].
     *
     * Se rechaza un enlace (no se sigue) y cualquier ruta canónica que no cuelgue de la caché real:
     * el `FileProvider` resuelve por ruta canónica y aquí no se le entrega nada ambiguo.
     */
    private fun shareDirectory(): File? {
        val directory = File(cacheRoot, AngleV2FrameShare.DIRECTORY_NAME)
        when (fileKind(directory)) {
            FileKind.ABSENT -> if (!directory.mkdirs() && fileKind(directory) != FileKind.DIRECTORY) return null
            FileKind.DIRECTORY -> Unit
            FileKind.REGULAR_FILE,
            FileKind.SYMBOLIC_LINK,
            FileKind.OTHER,
            -> return null
        }
        val canonicalRoot = try {
            cacheRoot.canonicalFile
        } catch (_: IOException) {
            return null
        }
        val canonical = try {
            directory.canonicalFile
        } catch (_: IOException) {
            return null
        }
        return if (canonical.parentFile == canonicalRoot) canonical else null
    }

    /**
     * Escribe la copia con creación **exclusiva**: nunca se sobrescribe una copia ya entregada.
     *
     * No devuelve nada: lo que decide si la copia sirve es la verificación posterior
     * ([copyMatches]), no el hecho de haber escrito. Si la escritura falla, la copia es nuestra, recién
     * creada y nunca entregada, así que se retira para dejar el directorio como estaba. No se toca
     * ninguna otra ruta (tampoco el almacén privado, cuyo fichero permanece intacto).
     */
    private fun writeCopy(target: File, bytes: ByteArray) {
        try {
            // Si otra compartición simultánea se adelantó, el fichero existe: no se toca y la
            // comprobación posterior decide.
            if (!target.createNewFile()) return
            FileOutputStream(target, /* append = */ false).use { output ->
                output.write(bytes)
                output.flush()
            }
            // Permisos de propietario únicamente, como refuerzo: el directorio ya es privado de la app.
            target.setReadable(false, /* ownerOnly = */ false)
            target.setWritable(false, /* ownerOnly = */ false)
            target.setExecutable(false, /* ownerOnly = */ false)
            target.setReadable(true, /* ownerOnly = */ true)
            target.setWritable(true, /* ownerOnly = */ true)
        } catch (_: IOException) {
            runCatching { target.delete() }
        }
    }

    /**
     * `true` sólo si [file] es un fichero regular (no enlace), de exactamente [expectedBytes] bytes y
     * con el SHA-256 declarado. Es la comprobación que se hace **antes** de entregar la URI, sobre el
     * fichero tal y como se va a servir.
     */
    private fun copyMatches(file: File, expectedBytes: Long, expectedSha: String): Boolean {
        if (fileKind(file) != FileKind.REGULAR_FILE) return false
        val length = try {
            file.length()
        } catch (_: SecurityException) {
            return false
        }
        if (length != expectedBytes || length > AngleV2FrameShare.MAX_BYTES) return false
        val actual = sha256HexOf(file, AngleV2FrameShare.MAX_BYTES) ?: return false
        return actual == expectedSha
    }
}

/** Clasificación de una ruta sin seguir enlaces (`lstat`), como hace el almacén. */
private enum class FileKind { ABSENT, DIRECTORY, REGULAR_FILE, SYMBOLIC_LINK, OTHER }

private fun fileKind(file: File): FileKind = try {
    val stat = Os.lstat(file.path)
    when {
        OsConstants.S_ISREG(stat.st_mode) -> FileKind.REGULAR_FILE
        OsConstants.S_ISDIR(stat.st_mode) -> FileKind.DIRECTORY
        OsConstants.S_ISLNK(stat.st_mode) -> FileKind.SYMBOLIC_LINK
        else -> FileKind.OTHER
    }
} catch (missing: ErrnoException) {
    if (missing.errno == OsConstants.ENOENT || missing.errno == OsConstants.ENOTDIR) {
        FileKind.ABSENT
    } else {
        FileKind.OTHER
    }
}

private val SHA256_HEX: Regex = Regex("^[0-9a-fA-F]{64}$")

/** SHA-256 hexadecimal minúscula de [bytes]; la fila guarda el mismo formato. */
private fun sha256HexOf(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(bytes)
    return digest.digest().toLowerHexString()
}

/**
 * SHA-256 hexadecimal minúscula del contenido de [file], o `null` si no se puede leer o supera
 * [maxBytes] (una copia mayor que el tope nunca se entrega).
 */
private fun sha256HexOf(file: File, maxBytes: Long): String? {
    return try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > maxBytes) return null
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toLowerHexString()
    } catch (_: IOException) {
        null
    }
}
