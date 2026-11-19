package com.openjump.app.data.backup

import com.openjump.app.data.AngleAnalysisV2FrameEntity
import com.openjump.app.data.AngleAnalysisV2MarkEntity
import com.openjump.app.data.AngleAnalysisV2RootEntity
import com.openjump.app.data.angles.AngleImageKey
import com.openjump.app.data.angles.AngleImageStoreLimits
import kotlinx.serialization.Serializable

/**
 * Contrato del **manifiesto** del backup con imagenes (slice G1: contrato puro, sin IO).
 *
 * Este fichero NO sustituye a [MANUAL_BACKUP_CONTRACT] (`openjump-manual-backup`, v1): el manifiesto
 * envuelve un documento legacy **intacto** en [MediaBackupManifest.legacy] y anade, aparte, las
 * filas del agregado multi-fotograma `angle_analysis_v2_*` (Room 14) y la contabilidad de las
 * imagenes anotadas que viajaran en un fichero aparte.
 *
 * Reglas de diseno que este contrato hace estructurales:
 *  - El JSON v1 **nunca** transporta medios: [ManualBackupDocument.mediaIncluded] sigue siendo
 *    `false` y [MediaBackupManifest.validate] lo exige. Los medios son la seccion
 *    [MediaBackupAngleV2.images] de este manifiesto, no un flag del documento legacy.
 *  - Una imagen **nunca** se referencia por ruta libre. Las filas llevan la clave opaca privada
 *    [AngleAnalysisV2FrameEntity.imageKey] y el nombre canonico de la entrada del contenedor se
 *    **deriva** de ella con [MediaBackupArchive.imageEntryName]: `images/<hex32>.png`. No hay campo
 *    para un nombre de fichero, una URI ni una ruta, asi que no se puede expresar `..`, una ruta
 *    absoluta ni un nombre de usuario.
 *  - Lo **asignado** y lo **reportado** por la extraccion se guardan por separado
 *    ([MediaBackupFrame.assignedFrameIndex]/[MediaBackupFrame.assignedPtsUs] frente a
 *    [MediaBackupFrame.reportedFrameIndex]/[MediaBackupFrame.reportedPtsUs]): un PTS no verificado
 *    se queda `null` en vez de copiarse del asignado.
 *
 * Alcance: este slice **no** escribe ni lee imagenes ni contenedores. Las unicas APIs para el
 * componente ZIP de streaming son puras y estan en [MediaBackupArchive] y
 * [MediaBackupManifest.archivePlan].
 */

/** Identificador del contrato del manifiesto. Distinto del contrato legacy a proposito. */
const val MEDIA_BACKUP_CONTRACT = "openjump-media-backup"

/** Version reconocida del manifiesto. Un lector debe rechazar cualquier otra. */
const val MEDIA_BACKUP_FORMAT_VERSION = 1

/**
 * Esquema Room que el manifiesto declara. Las tablas V2 nacen en Room 14
 * ([MANUAL_BACKUP_V2_INTRODUCED_ROOM_VERSION]) y el manifiesto solo existe para transportarlas, asi
 * que no se acepta un manifiesto que declare otro linaje: fingir un esquema anterior con filas V2
 * dentro seria exactamente el hibrido que el gate transitorio de [ManualBackupDao] impide.
 */
const val MEDIA_BACKUP_ROOM_VERSION = MANUAL_BACKUP_V2_INTRODUCED_ROOM_VERSION

/** Entrada del contenedor donde vivira el manifiesto (siguiente componente ZIP). */
const val MEDIA_BACKUP_MANIFEST_ENTRY = "manifest.json"

/**
 * Presupuesto del manifiesto **serializado sin imagenes**, en bytes UTF-8.
 *
 * No amplia el limite del documento legacy: el JSON de [MediaBackupManifest.legacy] se sigue
 * validando con [ManualBackupDocument.validate] y su preflight de 8 MiB ([MANUAL_BACKUP_MAX_BYTES]).
 * Este presupuesto solo acota el envoltorio + la seccion de medios, que anade claves de 32
 * caracteres, SHA-256 de 64 y geometria de fotograma. Con 16 MiB ningun backup legacy valido puede
 * quedar fuera y sigue siendo un limite finito y comprobado antes de escribir.
 */
const val MEDIA_BACKUP_MAX_MANIFEST_BYTES = 16L * 1024L * 1024L

/**
 * Cota del numero de imagenes del manifiesto. Es independiente del presupuesto de bytes: con
 * entradas minusculas, un contenedor podria declarar muchisimas referencias sin acercarse al tope
 * total, y el coste real de abrirlas es por entrada (cabecera ZIP + fichero), no por byte.
 */
const val MEDIA_BACKUP_MAX_IMAGES = 4_096

/**
 * Cota de una imagen. Reutiliza el contrato del almacen privado
 * ([AngleImageStoreLimits.DEFAULT_MAX_IMAGE_BYTES], 12 MiB) para no inventar un tope distinto del
 * que ya limita lo que el dispositivo puede haber guardado: una imagen legitima jamas se rechaza
 * al restaurar.
 */
const val MEDIA_BACKUP_MAX_IMAGE_BYTES = AngleImageStoreLimits.DEFAULT_MAX_IMAGE_BYTES

/**
 * Cota del total de bytes de imagen declarados. Igual que [MEDIA_BACKUP_MAX_IMAGE_BYTES], se toma
 * del presupuesto del almacen privado ([AngleImageStoreLimits.DEFAULT_MAX_TOTAL_BYTES]) para que el
 * backup pueda representar cualquier estado legitimo del almacen, sin prometer mas.
 */
const val MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES = AngleImageStoreLimits.DEFAULT_MAX_TOTAL_BYTES

/** Unica extension/MIME que este contrato sabe nombrar de forma canonica. */
const val MEDIA_BACKUP_IMAGE_MIME_TYPE = "image/png"

/** Directorio de las imagenes dentro del contenedor. */
const val MEDIA_BACKUP_IMAGE_DIRECTORY = "images"

/**
 * Manifiesto de un backup con imagenes.
 *
 * [legacy] es el documento v1 completo, validado por su propio contrato; [angleV2] es aditivo y
 * puede estar vacio (un backup con el formato nuevo pero sin analisis multi-fotograma sigue siendo
 * valido). Un manifiesto **no** puede declarar una imagen que ningun fotograma referencie ni una
 * referencia que el manifiesto no transporte (ver [MediaBackupManifest.validate]).
 */
@Serializable
data class MediaBackupManifest(
    val contract: String,
    val formatVersion: Int,
    val sourceRoomSchemaVersion: Int,
    val createdAtEpochMillis: Long,
    val legacy: ManualBackupDocument,
    val angleV2: MediaBackupAngleV2,
)

/**
 * Agregado multi-fotograma transportado por el manifiesto, mas la contabilidad de las imagenes.
 *
 * Las colecciones llevan `emptyList()` por defecto para que el manifiesto pueda declarar V2 vacio,
 * pero un elemento presente se valida siempre: no hay relleno silencioso.
 */
@Serializable
data class MediaBackupAngleV2(
    val roots: List<MediaBackupRoot> = emptyList(),
    val frames: List<MediaBackupFrame> = emptyList(),
    val marks: List<MediaBackupMark> = emptyList(),
    val images: List<MediaBackupImage> = emptyList(),
)

/**
 * Raiz V2; espeja las columnas de [AngleAnalysisV2RootEntity] salvo la identidad autoincremental.
 *
 * [athleteId] es nullable por la convencion del proyecto (una raiz sin dueno es un estado real, no
 * un dato perdido): cuando llega un valor debe existir en `legacy.tables.athletes`, y cuando es
 * `null` se conserva `null` en vez de inventar un atleta. [analysisFormat] es el discriminador de
 * tipo ([com.openjump.app.data.ANGLE_ANALYSIS_V2_FORMAT]) y debe ser el reconocido.
 */
@Serializable
data class MediaBackupRoot(
    val id: Long,
    val analysisFormat: String,
    val sessionKey: String,
    val athleteId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Int,
)

/**
 * Fotograma V2 con la integridad de su imagen anotada; espeja [AngleAnalysisV2FrameEntity].
 *
 * [imageKey] es la clave opaca del almacen privado (`[0-9a-f]{32}`) y **no** un nombre de fichero:
 * el nombre canonico de la entrada del contenedor se deriva con
 * [MediaBackupArchive.imageEntryName]. [imageByteCount] y [imageSha256] describen exactamente el
 * fichero esperado; la validacion exige que coincidan con la entrada declarada en
 * [MediaBackupAngleV2.images].
 */
@Serializable
data class MediaBackupFrame(
    val id: Long,
    val rootId: Long,
    val ordinal: Int,
    val assignedFrameIndex: Int,
    val reportedFrameIndex: Int? = null,
    val assignedPtsUs: Long,
    val reportedPtsUs: Long? = null,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val presentationWidth: Int,
    val presentationHeight: Int,
    val rotationDegrees: Int,
    val geometryVersion: Int,
    val imageKey: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageByteCount: Long,
    val imageSha256: String,
    val imageMimeType: String,
)

/**
 * Marca manual de un fotograma V2; espeja [AngleAnalysisV2MarkEntity]. Los puntos estan en pixeles
 * de presentacion post-rotacion del fotograma padre y la validacion exige que sean finitos, que
 * caigan dentro de esas dimensiones y que la terna A/B/C mida un angulo.
 */
@Serializable
data class MediaBackupMark(
    val id: Long,
    val frameId: Long,
    val ordinal: Int,
    val name: String,
    val colorArgb: Int,
    val visible: Boolean = true,
    val pointAX: Double,
    val pointAY: Double,
    val pointBX: Double,
    val pointBY: Double,
    val pointCX: Double,
    val pointCY: Double,
)

/**
 * Entrada de imagen declarada por el manifiesto: la contabilidad de un fichero del contenedor.
 *
 * [imageKey] debe ser una clave canonica y estar referenciada por **exactamente un** fotograma con
 * la misma [byteCount] y [sha256]; el nombre de la entrada no se guarda porque es derivado
 * ([MediaBackupArchive.imageEntryName]) y por tanto no puede ser una ruta libre.
 */
@Serializable
data class MediaBackupImage(
    val imageKey: String,
    val byteCount: Long,
    val sha256: String,
)

/** Entrada ya resuelta de un plan de archivo: nombre canonico + datos declarados. */
data class MediaBackupImageEntry(
    val entryName: String,
    val imageKey: String,
    val byteCount: Long,
    val sha256: String,
)

/**
 * Plan puro de lo que el componente de ZIP debe escribir/leer: nombre del manifiesto y entradas de
 * imagen con nombre canonico, sin IO y sin rutas libres. El orden es estable (por clave) para que
 * dos escrituras del mismo manifiesto produzcan el mismo archivo.
 */
data class MediaBackupArchivePlan(
    val manifestEntryName: String,
    val imageEntries: List<MediaBackupImageEntry>,
) {
    val imageCount: Int get() = imageEntries.size

    /** Suma de bytes declarados, sin overflow (las entradas ya vienen validadas). */
    val totalImageByteCount: Long
        get() = imageEntries.fold(0L) { total, entry -> Math.addExact(total, entry.byteCount) }
}

/**
 * Nombres canonicos del contenedor. Todo lo que hace falta para que un lector ZIP no dependa de
 * heuristicas: o el nombre es exactamente el que produce [imageEntryName], o no es una imagen.
 */
object MediaBackupArchive {

    /** Longitud de una clave canonica: 32 hex minuscula (16 bytes). */
    const val IMAGE_KEY_LENGTH: Int = AngleImageKey.HEX_LENGTH

    /** Nombre de la entrada del manifiesto. */
    const val MANIFEST_ENTRY: String = MEDIA_BACKUP_MANIFEST_ENTRY

    /** Directorio de imagenes dentro del contenedor. */
    const val IMAGE_DIRECTORY: String = MEDIA_BACKUP_IMAGE_DIRECTORY

    /** Extension canonica: el MIME permitido es [MEDIA_BACKUP_IMAGE_MIME_TYPE] (`image/png`). */
    const val IMAGE_EXTENSION: String = "png"

    private const val IMAGE_PREFIX = "$IMAGE_DIRECTORY/"
    private const val IMAGE_SUFFIX = ".$IMAGE_EXTENSION"

    /**
     * Nombre canonico de la entrada de [imageKey]: `images/<hex32>.png`.
     *
     * Rechaza cualquier clave no canonica ([AngleImageKey.isCanonical]) en vez de sanearla: sanear
     * una clave convertiria dos claves distintas en la misma entrada.
     */
    fun imageEntryName(imageKey: String): String {
        require(AngleImageKey.isCanonical(imageKey)) {
            "Image entry name requires a canonical image key ([0-9a-f]{$IMAGE_KEY_LENGTH})"
        }
        return "$IMAGE_PREFIX$imageKey$IMAGE_SUFFIX"
    }

    /**
     * Clave de una entrada **solo** si su nombre es exactamente el canonico; `null` para cualquier
     * otra cosa (rutas, `..`, mayusculas, extension distinta, entradas del manifiesto o de
     * directorio). Es la unica forma admitida de volver de un nombre a una clave.
     */
    fun imageKeyOfEntry(entryName: String): String? {
        if (!entryName.startsWith(IMAGE_PREFIX) || !entryName.endsWith(IMAGE_SUFFIX)) return null
        val key = entryName.substring(IMAGE_PREFIX.length, entryName.length - IMAGE_SUFFIX.length)
        return if (AngleImageKey.isCanonical(key)) key else null
    }

    /** `true` si el nombre es la entrada del manifiesto o una entrada de imagen canonica. */
    fun isReservedEntryName(entryName: String): Boolean =
        entryName == MANIFEST_ENTRY || imageKeyOfEntry(entryName) != null
}

/**
 * Plan de archivo del manifiesto. Valida el manifiesto (incluido el documento legacy) y deriva los
 * nombres canonicos en orden estable: es la API que consumira el componente ZIP de streaming.
 *
 * No toca disco, no abre ningun fichero y no decide compresion ni cifrado.
 */
fun MediaBackupManifest.archivePlan(): MediaBackupArchivePlan {
    val validated = validate()
    val entries = validated.angleV2.images
        .sortedBy { it.imageKey }
        .map { image ->
            MediaBackupImageEntry(
                entryName = MediaBackupArchive.imageEntryName(image.imageKey),
                imageKey = image.imageKey,
                byteCount = image.byteCount,
                sha256 = image.sha256,
            )
        }
    return MediaBackupArchivePlan(manifestEntryName = MediaBackupArchive.MANIFEST_ENTRY, imageEntries = entries)
}
