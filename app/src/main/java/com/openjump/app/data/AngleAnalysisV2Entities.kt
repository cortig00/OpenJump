package com.openjump.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Identidad de tipo del agregado multi-fotograma. Las tablas de este fichero son **nuevas y
 * aditivas**: no sustituyen ni reinterpretan `angle_analyses`/`angle_marks`, cuyas filas y
 * referencias siguen siendo la forma canónica de los análisis de un solo frame. Por eso una raíz
 * V2 puede tener un `id` que colisione con un `angle_analyses.id` sin ambigüedad: la identidad de
 * tipo incluye la tabla y este discriminador, no solo el número.
 *
 * Cambiar este valor exige una migración nueva: los lectores deben reconocer los formatos que
 * sepan interpretar en lugar de asumir que todo lo que hay en la tabla es el formato actual.
 */
const val ANGLE_ANALYSIS_V2_FORMAT = "ANGLE_MULTI_FRAME_V1"

/**
 * Raíz de un análisis de ángulos multi-fotograma sobre **un único vídeo**.
 *
 * Sólo agrupa procedencia y propiedad: no guarda resultados derivados (grados) ni la geometría de
 * cada imagen. Cada fotograma hijo conserva su propia geometría e integridad de imagen porque dos
 * fotogramas del mismo vídeo pueden haberse presentado con orientación o tamaño distintos.
 *
 * **Privacidad:** este agregado no guarda la URI del vídeo ni ninguna ruta de usuario. El vínculo
 * con el medio es la clave opaca privada de cada [AngleAnalysisV2FrameEntity], que no es un
 * identificador de contenido del usuario ni se expone en exportaciones.
 *
 * Esta raíz aún **no** se publica en el historial (`JumpDao`/historial siguen leyendo sólo el
 * agregado legacy) y ningún escritor la usa: ver [AngleAnalysisV2Dao].
 */
@Entity(
    tableName = "angle_analysis_v2_roots",
    foreignKeys = [
        ForeignKey(
            entity = AthleteEntity::class,
            parentColumns = ["id"],
            childColumns = ["athleteId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        /** Idempotencia de guardados: una sesión de captura produce como máximo una raíz. */
        Index(value = ["sessionKey"], unique = true),
        Index(value = ["athleteId", "createdAt", "id"]),
        Index(value = ["createdAt", "id"]),
    ],
)
data class AngleAnalysisV2RootEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Discriminador de tipo/versión del agregado; ver [ANGLE_ANALYSIS_V2_FORMAT]. */
    val analysisFormat: String = ANGLE_ANALYSIS_V2_FORMAT,
    val sessionKey: String,
    /** Nullable por la convención del proyecto; los guardados nuevos siempre llevan dueño. */
    val athleteId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Contador de modificaciones de la raíz, para detectar escrituras obsoletas. */
    val revision: Int = 0,
)

/**
 * Fotograma ordenado de una raíz V2: la unidad que tiene imagen anotada y marcas.
 *
 * **Índices y PTS dobles.** [assignedFrameIndex]/[assignedPtsUs] son lo que el editor *pidió*
 * capturar; [reportedFrameIndex]/[reportedPtsUs] son lo que la extracción *reportó* para el
 * bitmap anotado. Se guardan por separado a propósito: el contrato de captura prohíbe presentar
 * como observado un PTS que no se ha podido verificar, así que lo no verificado se queda en
 * `NULL` en vez de copiarse del asignado.
 *
 * **Integridad de imagen.** El bitmap **no** vive en Room: [imageKey] es una clave opaca privada
 * del almacén de imágenes y las columnas `image*` describen qué fichero se anotó (formato,
 * dimensiones, tamaño y SHA-256) para poder detectar un medio ausente, trocado o truncado sin
 * abrir el vídeo original. Room y el almacén de ficheros no comparten transacción, así que estas
 * columnas son una comprobación de integridad, no una promesa de atomicidad.
 *
 * [geometryVersion] pertenece al fotograma, no a la raíz: cada imagen pudo medirse con una
 * geometría de presentación distinta y las coordenadas de sus marcas sólo son comparables dentro
 * de ella.
 */
@Entity(
    tableName = "angle_analysis_v2_frames",
    foreignKeys = [
        ForeignKey(
            entity = AngleAnalysisV2RootEntity::class,
            parentColumns = ["id"],
            childColumns = ["rootId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["rootId"]),
        /** Orden estable de los fotogramas de una raíz; repetir ordinal en la raíz se rechaza. */
        Index(value = ["rootId", "ordinal"], unique = true),
        /** Una imagen anotada pertenece a un único fotograma: dos referencias son un error. */
        Index(value = ["imageKey"], unique = true),
    ],
)
data class AngleAnalysisV2FrameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rootId: Long,
    /** Posición 0..N-1 dentro de la raíz, independiente de los índices del vídeo. */
    val ordinal: Int,
    /** Índice de frame solicitado/asignado por el editor antes de capturar. */
    val assignedFrameIndex: Int,
    /** Índice reportado por la extracción para el bitmap anotado; `NULL` si no se verificó. */
    val reportedFrameIndex: Int? = null,
    /** PTS solicitado/asignado (µs) según el índice de frames del extractor. */
    val assignedPtsUs: Long,
    /** PTS reportado (µs) para el bitmap anotado; `NULL` si no se pudo verificar. */
    val reportedPtsUs: Long? = null,
    /** Dimensiones tal y como vienen codificadas en el fichero. */
    val encodedWidth: Int,
    val encodedHeight: Int,
    /** Dimensiones tras aplicar la rotación; es el sistema de las coordenadas de las marcas. */
    val presentationWidth: Int,
    val presentationHeight: Int,
    val rotationDegrees: Int,
    /** Versión del modelo geométrico con el que se midieron las coordenadas de este fotograma. */
    val geometryVersion: Int,
    /** Clave opaca privada del almacén de imágenes; nunca una ruta ni un nombre de usuario. */
    val imageKey: String,
    /** Dimensiones del bitmap anotado guardado, no del vídeo. */
    val imageWidth: Int,
    val imageHeight: Int,
    val imageByteCount: Long,
    /** SHA-256 hexadecimal del fichero anotado, para detectar ausencia/corrupción. */
    val imageSha256: String,
    /** Tipo MIME del fichero anotado (por ejemplo `image/png`). */
    val imageMimeType: String,
)

/**
 * Marca manual de un fotograma V2: los tres puntos A, B (vértice) y C en píxeles de presentación
 * del fotograma padre, más nombre, color y visibilidad elegidos por el usuario.
 *
 * [ordinal] es el orden estable con el que la UI presenta las marcas dentro de su fotograma;
 * [id] es la identidad persistente y no cambia al reordenar. Las marcas no pueden existir sin
 * fotograma y se borran con él (cascada), igual que las marcas legacy con su análisis.
 */
@Entity(
    tableName = "angle_analysis_v2_marks",
    foreignKeys = [
        ForeignKey(
            entity = AngleAnalysisV2FrameEntity::class,
            parentColumns = ["id"],
            childColumns = ["frameId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["frameId"]),
        /** Orden estable de las marcas de un fotograma; el ordinal repetido se rechaza. */
        Index(value = ["frameId", "ordinal"], unique = true),
    ],
)
data class AngleAnalysisV2MarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val frameId: Long,
    val ordinal: Int,
    val name: String,
    /** Color de la marca en formato ARGB empaquetado, tal como lo usa Android. */
    val colorArgb: Int,
    val visible: Boolean = true,
    val pointAX: Double,
    val pointAY: Double,
    val pointBX: Double,
    val pointBY: Double,
    val pointCX: Double,
    val pointCY: Double,
)
