package com.openjump.app.data.angles

/**
 * Límites duros del almacén privado de imágenes anotadas.
 *
 * [maxImageBytes] acota un fichero; [maxTotalBytes] acota lo que el almacén puede ocupar en su
 * directorio. Ambos se comprueban **durante** la escritura (streaming) y antes de publicar, de
 * forma que un `InputStream` sin longitud conocida no puede llenar el disco.
 *
 * Los valores por defecto son los de E1 para fotogramas anotados de un vídeo de móvil; el
 * llamante puede ajustarlos. Subirlos exige revisar el presupuesto de almacenamiento del producto,
 * no es un detalle de implementación.
 */
data class AngleImageStoreLimits(
    val maxImageBytes: Long = DEFAULT_MAX_IMAGE_BYTES,
    val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {
    init {
        require(maxImageBytes >= 1) { "maxImageBytes debe ser positivo." }
        require(maxTotalBytes >= maxImageBytes) {
            "maxTotalBytes ($maxTotalBytes) no puede ser menor que maxImageBytes ($maxImageBytes)."
        }
    }

    companion object {
        /** 12 MiB: holgado para un PNG anotado de 1080×1920 y acotado para memoria/almacenamiento. */
        const val DEFAULT_MAX_IMAGE_BYTES: Long = 12L * 1024L * 1024L

        /** 512 MiB de presupuesto total del almacén privado. */
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 512L * 1024L * 1024L
    }
}

/**
 * Integridad **esperada** de una imagen, tal y como la fila del fotograma la declara
 * (`imageByteCount` + `imageSha256`).
 *
 * Se exige en lectura para que el almacén pueda distinguir "ausente" de "trocado": un fichero
 * presente con otro tamaño o con otro contenido es corrupción, no una imagen válida distinta.
 * [sha256Hex] se compara sin distinguir mayúsculas, pero debe ser hexadecimal de 64 caracteres.
 */
data class AngleImageIntegrity(
    val byteCount: Long,
    val sha256Hex: String,
)

/**
 * Metadatos de una imagen ya sellada por el almacén.
 *
 * [byteCount] y [sha256Hex] describen **exactamente los bytes escritos**. El almacén no
 * inspecciona el contenido: no valida cabecera PNG, ni formato de imagen, ni dimensiones. Que el
 * llamante haya generado y verificado antes un PNG es responsabilidad suya; aquí sólo se sellan
 * bytes opacos. [imageKey] es la clave opaca con la que se publicó.
 */
data class StoredAngleImage(
    val imageKey: String,
    val byteCount: Long,
    val sha256Hex: String,
)

/** Etapa del almacén en la que se produjo un fallo; permite distinguir contrato de disco. */
enum class AngleImageStoreStage {
    VALIDATE_KEY,
    PREPARE_DIRECTORY,
    CHECK_CAPACITY,
    RESERVE_KEY_DIRECTORY,
    CREATE_TEMPORARY,
    WRITE,
    SYNC,
    PUBLISH,
    CLEANUP,
    SYNC_DIRECTORY,
    VERIFY,
    READ,
}

/**
 * Durabilidad observada de la publicación.
 *
 * Un `fsync` de fichero **no** persiste el nombre: hace falta sincronizar **cada** directorio cuya
 * entrada cambió. El almacén sincroniza, de más profundo a más superficial y en **cada**
 * publicación, el directorio de la clave (nombre `image.bin`), el directorio del almacén (nombre de
 * la clave) y el directorio raíz, que es quien guarda la entrada `angle-images`. El raíz **no** se
 * omite cuando `angle-images` ya existía: observarlo no confirma que esa entrada esté persistida
 * (otro escritor pudo crearla sin sincronizar su padre todavía, o un intento anterior pudo dejarla
 * sin confirmar). El resultado se reporta en vez de prometer durabilidad que no se ha podido
 * confirmar: sólo [SYNCED] en **todos** los directorios reportados significa "publicado y
 * sincronizado".
 *
 * El raíz lo provee el llamante (en la app, `filesDir`, creado por la plataforma) y **debe existir
 * ya** como directorio real: el almacén nunca lo crea y rechaza la escritura si falta
 * ([AngleImageStoreError.RootDirectoryUnusable]). Su propia entrada en el directorio padre queda
 * fuera del alcance del almacén y es responsabilidad del llamante.
 *
 * [ancestorDirectories] son los directorios por encima del padre de la clave, de más cercano a más
 * lejano, y contiene **sólo** las entradas que la publicación necesitó confirmar: en este layout es
 * la entrada `angle-images` dentro del directorio raíz, que se sincroniza en cada publicación. Un
 * elemento [AngleImageDirectorySync.UNSUPPORTED] indica que la plataforma no permite `fsync` sobre
 * ese directorio; un elemento `FAILED` indica que el `fsync` no se pudo completar. En ambos casos
 * [directoryEntriesSynced] es `false`, aunque los otros directorios se hayan sincronizado: la imagen
 * está publicada y verificada en proceso, pero su persistencia frente a un corte no está
 * confirmada.
 */
data class AngleImageDurability(
    val keyDirectory: AngleImageDirectorySync,
    val parentDirectory: AngleImageDirectorySync,
    val ancestorDirectories: List<AngleImageDirectorySync> = emptyList(),
) {
    /** `true` sólo si **todos** los directorios reportados se sincronizaron con éxito. */
    val directoryEntriesSynced: Boolean
        get() = keyDirectory == AngleImageDirectorySync.SYNCED &&
            parentDirectory == AngleImageDirectorySync.SYNCED &&
            ancestorDirectories.all { it == AngleImageDirectorySync.SYNCED }

    companion object {
        /** Resultado de una escritura que **no** llegó a publicar: no hay durabilidad que declarar. */
        val NOT_PUBLISHED: AngleImageDurability = AngleImageDurability(
            keyDirectory = AngleImageDirectorySync.UNSUPPORTED,
            parentDirectory = AngleImageDirectorySync.UNSUPPORTED,
        )
    }
}

/**
 * Motivo por el que el directorio raíz proporcionado por el llamante no sirve para publicar.
 *
 * El raíz es propiedad del llamante (en la app, `filesDir`, creado por la plataforma) y debe ser un
 * directorio **real y preexistente**: el almacén nunca lo crea y nunca sigue un enlace para llegar a
 * él.
 */
enum class AngleImageRootViolation {
    /** No existe. El almacén no lo crea: su creación y durabilidad son del llamante. */
    ABSENT,

    /** Es un enlace simbólico: no se sigue ni se publica a través de él. */
    SYMBOLIC_LINK,

    /** Existe pero no es un directorio (fichero, FIFO, dispositivo...). */
    NOT_DIRECTORY,
}

/** Motivo por el que una ruta de clave se considera insegura y no se sigue. */
enum class AngleImagePathViolation {
    /** La ruta canónica no cuelga del directorio privado del almacén. */
    OUTSIDE_ROOT,

    /** La ruta de la clave es un enlace simbólico: no se sigue ni se sobrescribe. */
    SYMBOLIC_LINK,

    /** La ruta existe pero no es un fichero regular (directorio, FIFO, dispositivo...). */
    NOT_REGULAR_FILE,
}

/** Motivo por el que el contenido de una clave no coincide con la integridad esperada. */
enum class AngleImageCorruptionReason {
    /** El tamaño en disco no es el declarado. */
    SIZE_MISMATCH,

    /** El SHA-256 calculado no es el declarado. */
    DIGEST_MISMATCH,

    /** La ruta de la clave no es un fichero regular. */
    NOT_REGULAR_FILE,

    /** La ruta de la clave es un enlace simbólico; el almacén no lo sigue. */
    SYMBOLIC_LINK,
}

/**
 * Fallo tipado del almacén privado de imágenes.
 *
 * Ningún fallo borra datos ajenos ni publica bytes parciales: como mucho se elimina el temporal
 * que la propia operación creó, y si ni eso es posible el resultado lo indica
 * ([AngleImageWriteResult.Failed.temporaryFileRetained]).
 */
sealed interface AngleImageStoreError {

    /** La clave no tiene la forma canónica `[0-9a-f]{32}`; no se toca disco. */
    data class InvalidKey(val imageKey: String) : AngleImageStoreError

    /** La integridad esperada no es utilizable (tamaño no positivo o SHA-256 no hexadecimal). */
    data class InvalidIntegrityExpectation(val detail: String) : AngleImageStoreError

    /** El fichero supera [AngleImageStoreLimits.maxImageBytes]. */
    data class TooLarge(val maxImageBytes: Long, val observedByteCount: Long) : AngleImageStoreError

    /** El flujo y el tamaño declarado no coinciden: no se publica una fila con otro tamaño. */
    data class SizeMismatch(val declaredByteCount: Long, val observedByteCount: Long) : AngleImageStoreError

    /** El flujo no aportó ningún byte; una imagen vacía no es un fichero válido. */
    data object EmptyImage : AngleImageStoreError

    /** El almacén ya ocupa [maxTotalBytes]; la escritura no empieza. */
    data class QuotaExceeded(val maxTotalBytes: Long, val usedByteCount: Long) : AngleImageStoreError

    /**
     * Otra instancia del proceso ya fijó **otros** límites para el mismo directorio real.
     *
     * El presupuesto agregado pertenece al directorio, así que no se puede aplicar el de esta
     * instancia sin arriesgar el de la otra: la escritura se rechaza antes de tocar disco
     * (fail-closed), sin crear temporales ni publicar nada. [declared] es lo que pidió esta
     * instancia y [registered] el presupuesto ya vigente para ese directorio.
     */
    data class LimitsConflict(
        val declared: AngleImageStoreLimits,
        val registered: AngleImageStoreLimits,
    ) : AngleImageStoreError

    /**
     * **Publicación incierta**: el nombre definitivo pudo quedar publicado y no se puede afirmar
     * que no haya nada publicado.
     *
     * Ocurre cuando la secuencia falla **después** del rename interno: el fichero final existe en
     * un directorio de clave propio pero su tamaño o SHA-256 no coinciden con los bytes escritos, o
     * el rename se completó y la comprobación posterior no pudo hacerse. El almacén **preserva** el
     * fichero final (borrarlo destruiría datos que quizá sean válidos) y no ofrece ninguna
     * reanudación de clave: la recuperación de huérfanos es E2. El llamante no puede tratar este
     * error como "no se publicó nada": debe asumir que [imageKey] puede existir.
     */
    data class PublicationUncertain(
        val imageKey: String,
        val detail: String,
    ) : AngleImageStoreError

    /**
     * No hay espacio en el dispositivo: el volumen declara menos del necesario o el sistema
     * devolvió `ENOSPC`. Nada se publica y el temporal propio se elimina si es posible.
     */
    data class InsufficientStorage(val requiredBytes: Long?, val usableBytes: Long?) : AngleImageStoreError

    /** La clave ya existe: la publicación es atómica y **nunca** sobrescribe. */
    data class KeyAlreadyExists(val imageKey: String) : AngleImageStoreError

    /**
     * El directorio raíz no es un directorio real preexistente ([reason]).
     *
     * La escritura se rechaza **antes de cualquier otra E/S**, sin crear el raíz, sin temporales y
     * sin publicar nada: el almacén no crea ni "repara" el raíz. Si lo creara, su entrada en el
     * directorio padre quedaría sin `fsync` fuera del alcance del almacén y una escritura posterior
     * la observaría como existente, declarando durable un nombre que nadie confirmó. El llamante
     * debe proporcionar un raíz real y ya existente (en la app, `filesDir`).
     */
    data class RootDirectoryUnusable(val reason: AngleImageRootViolation) : AngleImageStoreError

    /** La ruta de la clave no es segura; no se sigue ni se sobrescribe. */
    data class UnsafePath(val imageKey: String, val reason: AngleImagePathViolation) : AngleImageStoreError

    /**
     * Fallo de E/S en [stage]. Incluye el detalle acotado del sistema para diagnóstico, pero no
     * se interpreta como corrupción ni provoca borrados de otros ficheros.
     */
    data class IoFailure(val stage: AngleImageStoreStage, val detail: String) : AngleImageStoreError

    /** Operación cancelada por el llamante o por una interrupción del hilo. */
    data object Canceled : AngleImageStoreError

    /** La clave no está en el almacén; no se crea ni se inventa nada. */
    data class Absent(val imageKey: String) : AngleImageStoreError

    /** El fichero existe pero no coincide con la integridad esperada. */
    data class Corrupted(val imageKey: String, val reason: AngleImageCorruptionReason) : AngleImageStoreError
}

/** Resultado de una escritura. */
sealed interface AngleImageWriteResult {

    /**
     * Imagen publicada con su [StoredAngleImage] sellado.
     *
     * [durability] declara qué se pudo sincronizar: con [AngleImageDurability.directoryEntriesSynced]
     * en `false` la imagen está publicada y verificada en proceso, pero su persistencia frente a un
     * corte de energía **no** está confirmada. No se promete más de lo comprobado.
     *
     * [temporaryFileRetained] sigue existiendo por compatibilidad de la API de E1: el layout por
     * directorio de clave renombra el temporal (no lo enlaza), así que una publicación correcta no
     * deja temporal y este campo es `false`.
     */
    data class Stored(
        val image: StoredAngleImage,
        val temporaryFileRetained: Boolean = false,
        val durability: AngleImageDurability = AngleImageDurability.NOT_PUBLISHED,
    ) : AngleImageWriteResult

    /**
     * Escritura rechazada con [error].
     *
     * [temporaryFileRetained] indica que el temporal propio no se pudo eliminar;
     * [directoryRetained] que el directorio de clave recién creado no se pudo retirar con `rmdir`
     * (porque aún contiene el temporal, o porque la plataforma lo rechazó). En ambos casos se
     * **preserva** lo que había y se informa: el almacén no borra de forma recursiva ni reutiliza
     * una clave. Ningún fichero ajeno se ha tocado.
     */
    data class Failed(
        val error: AngleImageStoreError,
        val temporaryFileRetained: Boolean = false,
        val directoryRetained: Boolean = false,
    ) : AngleImageWriteResult
}

/** Resultado de verificar una imagen sin entregar sus bytes. */
sealed interface AngleImageVerifyResult {

    /** El tamaño y el SHA-256 en disco coinciden con [Verified.image]. */
    data class Verified(val image: StoredAngleImage) : AngleImageVerifyResult

    /** La imagen no se pudo verificar; [error] distingue ausencia, corrupción y fallo de E/S. */
    data class Failed(val error: AngleImageStoreError) : AngleImageVerifyResult
}

/**
 * Resultado de una lectura verificada.
 *
 * [Delivered] sólo se produce después de validar tamaño y SHA-256 con una pasada previa: el
 * consumidor nunca ve bytes de un fichero truncado o trocado.
 */
sealed interface AngleImageReadResult<out T> {

    /** El consumidor recibió el flujo acotado y devolvió [value]. */
    data class Delivered<T>(val image: StoredAngleImage, val value: T) : AngleImageReadResult<T>

    /** La imagen no se entregó; [error] es la causa tipada. */
    data class Failed(val error: AngleImageStoreError) : AngleImageReadResult<Nothing>
}
