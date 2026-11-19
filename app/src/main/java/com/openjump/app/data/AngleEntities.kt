package com.openjump.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Raíz de un análisis manual de ángulos 2D sobre **un único frame** de un vídeo.
 *
 * El frame y su tiempo canónico viven aquí, no en cada marca: todas las marcas de una
 * misma raíz se dibujaron sobre el mismo frame, así que repetirlos por marca permitiría
 * mezclas incoherentes. Las coordenadas de las marcas están en píxeles de presentación
 * (post-rotación), por eso la raíz guarda también la geometría de presentación con la
 * que se midieron: si el vídeo se reinterpreta con otra rotación o tamaño, las marcas
 * antiguas dejan de ser comparables.
 *
 * Esta tabla no guarda resultados derivados (grados, validez): el ángulo se recalcula
 * desde las marcas y la versión de geometría vigente. Tampoco referencia protocolos de
 * salto: la herramienta de ángulos es independiente del protocolo elegido.
 */
@Entity(
    tableName = "angle_analyses",
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
        Index(value = ["sessionKey"], unique = true),
        Index(value = ["athleteId", "createdAt", "id"]),
        Index(value = ["createdAt", "id"]),
    ],
)
data class AngleAnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionKey: String,
    /** Nullable por la convención del proyecto; los guardados nuevos siempre llevan dueño. */
    val athleteId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Contador de modificaciones del análisis, para detectar escrituras obsoletas. */
    val revision: Int = 0,
    val videoUri: String? = null,
    /** Identidad lógica de la fuente cuando la URI puede cambiar; NULL si no se conoce. */
    val sourceIdentity: String? = null,
    /** Hash del contenido analizado; NULL si no se calculó. */
    val sourceHash: String? = null,
    /** Dimensiones tal y como vienen codificadas en el fichero. */
    val encodedWidth: Int,
    val encodedHeight: Int,
    /** Dimensiones tras aplicar la rotación; es el sistema de las coordenadas de las marcas. */
    val presentationWidth: Int,
    val presentationHeight: Int,
    val rotationDegrees: Int,
    /** Frame canónico del análisis (no por marca). */
    val frameIndex: Int,
    /** PTS canónico de [frameIndex] según el índice de frames del extractor. */
    val sourcePtsUs: Long,
    /** Versión del modelo geométrico con el que se midieron las coordenadas guardadas. */
    val geometryVersion: Int,
)

/**
 * Marca manual de un análisis de ángulos: los tres puntos A, B (vértice) y C, en
 * píxeles de presentación del frame de su [AngleAnalysisEntity].
 *
 * [ordinal] es el orden estable con el que la UI presenta las marcas; [id] es la
 * identidad persistente de cada marca, que no cambia al reordenar.
 */
@Entity(
    tableName = "angle_marks",
    foreignKeys = [
        ForeignKey(
            entity = AngleAnalysisEntity::class,
            parentColumns = ["id"],
            childColumns = ["analysisId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["analysisId"]),
        Index(value = ["analysisId", "ordinal"], unique = true),
    ],
)
data class AngleMarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val analysisId: Long,
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
