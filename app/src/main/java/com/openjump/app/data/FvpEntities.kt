package com.openjump.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.openjump.fvp.MAX_JUMP_HEIGHT_M
import com.openjump.fvp.MAX_PUSH_OFF_M
import com.openjump.fvp.MIN_PUSH_OFF_M
import kotlinx.coroutines.flow.Flow

/** Versión del flujo/UX que produce las evaluaciones F-V-P de esta app. */
const val FVP_METHOD_VERSION = "fvp-app-1.0"

/** Modos de agregación de h_po aceptados en `fvp_assessments.pushOffMode`. */
internal val FVP_PUSH_OFF_MODES = setOf("COMMON", "PER_CONDITION")

/** Procedencias aceptadas en `fvp_assessments.bodyMassSource`. */
internal val FVP_BODY_MASS_SOURCES = setOf("PROFILE", "MANUAL")

/** Procedencias aceptadas en `fvp_assessments.pushOffSource` (nunca estimado por talla). */
internal val FVP_PUSH_OFF_SOURCES = setOf("MEASURED", "MANUAL")

/** Métodos de altura aceptados en `fvp_attempts.method` (los mismos claves que `FvpEngine.parseMethod`). */
internal val FVP_MEASURE_METHODS = setOf("FLIGHT_TIME_VIDEO", "MANUAL", "IMPORTED")

/** Versión del protocolo de confirmación de reutilización de saltos (`fvp_attempts.reuseConfirmationVersion`). */
const val FVP_REUSE_CONFIRMATION_VERSION = "fvp-reuse-1"

/** Contextos de adquisición aceptados en `fvp_assessments.acquisitionContextKind`. */
internal val FVP_ACQUISITION_CONTEXT_KINDS = setOf("UNKNOWN", "SAME_ACQUISITION", "HISTORICAL_COMPOSITE")

/** Procedencias aceptadas en `fvp_assessments.bodyMassMeasurement` (distinto de `bodyMassSource`). */
internal val FVP_BODY_MASS_MEASUREMENTS = setOf("UNKNOWN", "WEIGHED", "DECLARED")

/** Procedencias aceptadas en `fvp_conditions.addedLoadSource`. */
internal val FVP_ADDED_LOAD_SOURCES = setOf("UNKNOWN", "WEIGHED", "NOMINAL")

/** Mapeos temporales aceptados en `fvp_attempts.timeMapping`. */
internal val FVP_TIME_MAPPINGS = setOf("UNKNOWN", "IDENTITY_VERIFIED")

/** Raíz de una evaluación F-V-P. Los campos calculados son una foto de auditoría: NaN nunca se
 *  persiste (se guarda NULL), por eso los que pueden ser NaN son nullable. */
@Entity(
    tableName = "fvp_assessments",
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
        Index(value = ["athleteId", "dateTime", "id"]),
        Index(value = ["testingSessionId", "dateTime", "id"]),
        Index(value = ["dateTime", "id"]),
    ],
)
data class FvpAssessmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionKey: String,
    /** Nullable solo por simetría con assessments; los guardados nuevos siempre llevan dueño. */
    val athleteId: Long? = null,
    /** Reservado; sin FK física, igual que assessments. No participa en Testing en v1. */
    val testingSessionId: Long? = null,
    /** "SJ" | "CMJ". Nunca se mezclan en la misma evaluación. */
    val protocolId: String,
    val dateTime: Long,
    val bodyMassKg: Double,
    /** Procedencia de la masa: "PROFILE" | "MANUAL". */
    val bodyMassSource: String,
    /** "COMMON" | "PER_CONDITION". */
    val pushOffMode: String,
    /** h_po común [m]; si PER_CONDITION, valor de referencia declarado por el usuario. */
    val pushOffM: Double,
    /** "MEASURED" | "MANUAL" (nunca estimado por talla). */
    val pushOffSource: String,
    val pushOffLandmark: String? = null,
    val pushOffLandmarkHeightCm: Double? = null,
    /** Procedimiento declarado (p.ej. "LANDMARK_EXTENDED_MINUS_CROUCH"). */
    val pushOffProtocol: String? = null,
    /** Fecha de medicion de h_po (epoch millis); null si el usuario no la declaro. */
    val pushOffMeasuredAt: Long? = null,
    /** Medidas componente opcionales [cm] del mismo hito (crouch / extension). */
    val pushOffCrouchCm: Double? = null,
    val pushOffExtendedCm: Double? = null,
    val sourceFps: Double? = null,
    /** Versión del motor :fvp con la que se calculó (p.ej. "fvp-poc-1.0"). */
    val algorithmVersion: String,
    /** Versión del flujo/UX que produjo la evaluación (p.ej. "fvp-app-1.0"). */
    val methodVersion: String,
    val notes: String? = null,
    // Snapshot calculado (auditoría). NULL donde el valor sería NaN.
    val f0N: Double,
    val slopeFv: Double,
    val sfvNsPerM: Double,
    val v0Mps: Double?,
    val pmaxW: Double?,
    val r2: Double?,
    val conditionCount: Int,
    val vMinMps: Double,
    val vMaxMps: Double,
    val fMinN: Double,
    val fMaxN: Double,
    val f0RelNPerKg: Double?,
    val pmaxRelWPerKg: Double?,
    /** Nombres de FvpWarning separados por coma; CSV vacío = sin warnings. */
    val warningsCsv: String,
    /** Momento en que se adquirió la evaluación (epoch millis), si se conoce explícitamente.
     *  Es distinto de [dateTime]: la fecha del test puede declararse o importarse sin que la
     *  adquisición se haya realizado en ese instante. */
    val acquiredAt: Long? = null,
    /** Contexto de adquisición: "UNKNOWN" | "SAME_ACQUISITION" | "HISTORICAL_COMPOSITE". */
    @ColumnInfo(defaultValue = "UNKNOWN") val acquisitionContextKind: String = "UNKNOWN",
    /** Clave libre del contexto declarado (p.ej. identificador de la sesión de origen). */
    val acquisitionContextKey: String? = null,
    /** Cómo se obtuvo la masa corporal: "UNKNOWN" | "WEIGHED" | "DECLARED". */
    @ColumnInfo(defaultValue = "UNKNOWN") val bodyMassMeasurement: String = "UNKNOWN",
    /** Fecha de la pesada/declaración de masa (epoch millis); null si no se declaró. */
    val bodyMassMeasuredAt: Long? = null,
)

@Entity(
    tableName = "fvp_conditions",
    foreignKeys = [
        ForeignKey(
            entity = FvpAssessmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["assessmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("assessmentId"),
        Index(value = ["assessmentId", "ordinal"], unique = true),
        Index(value = ["assessmentId", "conditionKey"], unique = true),
    ],
)
data class FvpConditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assessmentId: Long = 0,
    val ordinal: Int,
    /** Clave estable mostrada/exportada (p.ej. "0kg", "20kg" o texto del usuario). */
    val conditionKey: String,
    val addedLoadKg: Double,
    val totalMassKg: Double,
    /** h_po efectivo de esta condición [m] (override o valor común). */
    val pushOffM: Double,
    /** Altura seleccionada [m] (mejor intento válido según el criterio del motor). */
    val selectedHeightM: Double,
    /** Intento elegido; null si no se conserva la referencia. */
    val selectedAttemptOrdinal: Int? = null,
    /** Procedencia de la carga añadida: "UNKNOWN" | "WEIGHED" | "NOMINAL". */
    @ColumnInfo(defaultValue = "UNKNOWN") val addedLoadSource: String = "UNKNOWN",
    /** Fecha de la medición de la carga añadida (epoch millis); null si no se declaró. */
    val addedLoadMeasuredAt: Long? = null,
)

@Entity(
    tableName = "fvp_attempts",
    foreignKeys = [
        ForeignKey(
            entity = FvpConditionEntity::class,
            parentColumns = ["id"],
            childColumns = ["conditionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conditionId"),
        Index(value = ["conditionId", "ordinal"], unique = true),
    ],
)
data class FvpAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conditionId: Long = 0,
    val ordinal: Int,
    /** Altura de salto del CdM [m]; (0, 1.2]. */
    val heightM: Double,
    /** "FLIGHT_TIME_VIDEO" | "MANUAL" | "IMPORTED". */
    val method: String,
    val flightTimeS: Double? = null,
    val sourceFps: Double? = null,
    /** Si el intento reutiliza un salto guardado de OpenJump: id + ordinal del attempt origen. */
    val sourceAssessmentId: Long? = null,
    val sourceAttemptOrdinal: Int? = null,
    val sourceProtocolId: String? = null,
    /** Intento tecnicamente valido (decision del usuario). Solo los validos entran en la seleccion. */
    val valid: Boolean = true,
    /** Motivo de exclusion conservado cuando valid = false (auditoria). */
    val exclusionReason: String? = null,
    val notes: String? = null,
    /** Momento en que el attempt de origen quedó registrado en su sistema original (epoch millis). */
    val sourceRecordedAt: Long? = null,
    /** Atleta dueño del attempt de origen cuando se importa de otra base. */
    val sourceAthleteId: Long? = null,
    /** Momento en que se adquirió este intento (epoch millis), si se conoce explícitamente. */
    val acquiredAt: Long? = null,
    /** Momento en que el usuario confirmó la reutilización del salto (epoch millis). */
    val reuseConfirmedAt: Long? = null,
    /** Versión del protocolo de confirmación usado (ver `FVP_REUSE_CONFIRMATION_VERSION`). */
    val reuseConfirmationVersion: String? = null,
    // Marcas temporales [us] del intento de origen alrededor de la fase (auditoría de importación).
    val takeoffPtsUs: Long? = null,
    val takeoffPreviousPtsUs: Long? = null,
    val takeoffNextPtsUs: Long? = null,
    val landingPtsUs: Long? = null,
    val landingPreviousPtsUs: Long? = null,
    val landingNextPtsUs: Long? = null,
    /** Mapeo temporal aplicado al importar: "UNKNOWN" | "IDENTITY_VERIFIED". */
    @ColumnInfo(defaultValue = "UNKNOWN") val timeMapping: String = "UNKNOWN",
    /** Clave de la evidencia que justifica el mapeo temporal. */
    val timeMappingEvidenceKey: String? = null,
)

data class FvpAssessmentWithData(
    @androidx.room.Embedded val assessment: FvpAssessmentEntity,
    @Relation(
        entity = FvpConditionEntity::class,
        parentColumn = "id",
        entityColumn = "assessmentId",
    )
    val conditions: List<FvpConditionWithAttempts>,
)

data class FvpConditionWithAttempts(
    @androidx.room.Embedded val condition: FvpConditionEntity,
    @Relation(
        entity = FvpAttemptEntity::class,
        parentColumn = "id",
        entityColumn = "conditionId",
    )
    val attempts: List<FvpAttemptEntity>,
)

data class PersistableFvpAssessment(
    val assessment: FvpAssessmentEntity,
    val conditions: List<FvpConditionEntity>,
    val attempts: List<FvpAttemptEntity>,
)

// --- Borradores de dominio (capa app) ---------------------------------------------------------

/**
 * Borrador completo de una evaluación F-V-P tal como lo produce la captura/importación.
 *
 * Los campos de procedencia usan las MISMAS claves de almacenamiento que las columnas
 * (`"PROFILE"`/`"MANUAL"`, `"MEASURED"`/`"MANUAL"`, `"COMMON"`/`"PER_CONDITION"`); [FvpRepository]
 * las valida y rechaza claves desconocidas sin valor por defecto silencioso.
 *
 * Deliberadamente NO existe `totalMassKg`: la masa del sistema se deriva de
 * `bodyMassKg + addedLoadKg` en la capa de elegibilidad/motor, nunca la aporta el llamante.
 *
 * Nota de nombres: [FvpConditionDraft] y [FvpAttemptDraft] de este paquete son el borrador de
 * PERSISTENCIA (incluyen referencias de origen y notas); `com.openjump.app.fvp.FvpConditionDraft`/
 * `FvpAttemptDraft` son la entrada de la capa de elegibilidad y se construyen a partir de estos.
 */
data class FvpDraft(
    val sessionKey: String,
    val athleteId: Long,
    /** "SJ" | "CMJ"; una evaluación nunca mezcla ambos. */
    val protocol: String,
    val dateTime: Long = System.currentTimeMillis(),
    val bodyMassKg: Double,
    /** "PROFILE" | "MANUAL". */
    val bodyMassSource: String,
    /** "COMMON" | "PER_CONDITION". */
    val pushOffMode: String,
    /** h_po común [m] en COMMON; valor de referencia declarado en PER_CONDITION. */
    val pushOffM: Double,
    /** "MEASURED" | "MANUAL" (nunca estimado por talla). */
    val pushOffSource: String,
    val pushOffLandmark: String? = null,
    val pushOffLandmarkHeightCm: Double? = null,
    val pushOffProtocol: String? = null,
    val pushOffMeasuredAt: Long? = null,
    val pushOffCrouchCm: Double? = null,
    val pushOffExtendedCm: Double? = null,
    val sourceFps: Double? = null,
    val methodVersion: String = FVP_METHOD_VERSION,
    val notes: String? = null,
    val conditions: List<FvpConditionDraft> = emptyList(),
    // Procedencia de adquisición (B0/B1b). NULL/UNKNOWN = desconocido: el borrador NUNCA rellena
    // estos campos (ni con la fecha del test, ni con la hora del guardado, ni por perfil).
    /** Momento en que se ADQUIRIÓ la evaluación (epoch millis); `null` = desconocido. Es distinto
     *  de [dateTime]: la fecha del test puede declararse sin que la adquisición se hiciera entonces. */
    val acquiredAt: Long? = null,
    /** Contexto de adquisición declarado por el usuario: "UNKNOWN" | "SAME_ACQUISITION" |
     *  "HISTORICAL_COMPOSITE". `"UNKNOWN"` nunca se infiere por cercanía de fechas. */
    val acquisitionContextKind: String = "UNKNOWN",
    /** Clave opaca del contexto declarado (≤128 caracteres, no vacía); `null` = desconocida. */
    val acquisitionContextKey: String? = null,
    /** Cómo se obtuvo la masa corporal: "UNKNOWN" | "WEIGHED" | "DECLARED". Es independiente de
     *  [bodyMassSource] (`PROFILE`/`MANUAL`) y `"UNKNOWN"` no se promueve por declarar un valor. */
    val bodyMassMeasurement: String = "UNKNOWN",
    /** Fecha de la pesada/declaración de masa (epoch millis); `null` = desconocida. */
    val bodyMassMeasuredAt: Long? = null,
)

/** Condición de carga del borrador; `attempts` conserva válidos e inválidos. */
data class FvpConditionDraft(
    val conditionKey: String,
    val addedLoadKg: Double,
    /** h_po de la condición [m] (solo se usa en `PER_CONDITION`). */
    val pushOffM: Double? = null,
    val attempts: List<FvpAttemptDraft> = emptyList(),
    // Procedencia de la carga añadida (B0/B1b). NULL/UNKNOWN = desconocido, nunca inferido.
    /** Cómo se obtuvo la carga añadida: "UNKNOWN" | "WEIGHED" | "NOMINAL". `"UNKNOWN"` no se
     *  promueve a verificado por existir un valor numérico de carga. */
    val addedLoadSource: String = "UNKNOWN",
    /** Fecha de la medida de la carga añadida (epoch millis); `null` = desconocida. */
    val addedLoadMeasuredAt: Long? = null,
)

/** Intento del borrador. `valid`/`exclusionReason` se persisten 1:1 en `fvp_attempts`. */
data class FvpAttemptDraft(
    /** Altura de salto del CdM [m]; (0, 1.2]. */
    val heightM: Double,
    /** "FLIGHT_TIME_VIDEO" | "MANUAL" | "IMPORTED". */
    val method: String,
    val flightTimeS: Double? = null,
    val sourceFps: Double? = null,
    val sourceAssessmentId: Long? = null,
    val sourceAttemptOrdinal: Int? = null,
    val sourceProtocolId: String? = null,
    val valid: Boolean = true,
    val exclusionReason: String? = null,
    val notes: String? = null,
    // Procedencia de origen/reutilización y snapshots temporales (B0/B1b). NULL/UNKNOWN =
    // desconocido: el borrador NUNCA infiere estos valores (ni de fps, ni de fechas cercanas, ni
    // de la sola existencia de PTS).
    /** Fecha en que el attempt de ORIGEN quedó registrado en su sistema (epoch millis); `null` =
     *  desconocida. Es un snapshot informativo: no prueba captura ni adquisición. */
    val sourceRecordedAt: Long? = null,
    /** Atleta dueño del attempt de origen (snapshot informativo sin FK; `> 0` cuando se informa);
     *  `null` = desconocido. */
    val sourceAthleteId: Long? = null,
    /** Momento en que se adquirió ESTE intento (epoch millis); `null` = desconocido. Nunca se copia
     *  [sourceRecordedAt] aquí como si fuera una captura. */
    val acquiredAt: Long? = null,
    /** Momento en que el usuario confirmó la reutilización del salto (epoch millis); `null` = sin
     *  confirmación durable. */
    val reuseConfirmedAt: Long? = null,
    /** Versión del protocolo de confirmación usado (ver [FVP_REUSE_CONFIRMATION_VERSION]);
     *  `null` = sin confirmación. Si se informa, debe ser exactamente esa versión. */
    val reuseConfirmationVersion: String? = null,
    /** PTS [µs] del despegue en el timeline original del medio; `null` = desconocido. */
    val takeoffPtsUs: Long? = null,
    /** PTS [µs] inmediatamente anterior al despegue; exige [takeoffPtsUs] y ser estrictamente
     *  menor; `null` = desconocido (jamás derivado de fps o de la frecuencia nominal). */
    val takeoffPreviousPtsUs: Long? = null,
    /** PTS [µs] inmediatamente posterior al despegue; exige [takeoffPtsUs] y ser estrictamente
     *  mayor; `null` = desconocido. */
    val takeoffNextPtsUs: Long? = null,
    /** PTS [µs] del aterrizaje; `null` = desconocido. Si [takeoffPtsUs] existe, debe ser mayor. */
    val landingPtsUs: Long? = null,
    /** PTS [µs] inmediatamente anterior al aterrizaje; exige [landingPtsUs] y ser estrictamente
     *  menor; `null` = desconocido. */
    val landingPreviousPtsUs: Long? = null,
    /** PTS [µs] inmediatamente posterior al aterrizaje; exige [landingPtsUs] y ser estrictamente
     *  mayor; `null` = desconocido. */
    val landingNextPtsUs: Long? = null,
    /** Mapeo temporal aplicado al importar: "UNKNOWN" | "IDENTITY_VERIFIED". `"UNKNOWN"` = sin
     *  mapeo verificado; no se promueve por la existencia de PTS o de fps nominales. */
    val timeMapping: String = "UNKNOWN",
    /** Clave opaca de la evidencia que justifica el mapeo temporal (≤128 caracteres, no vacía);
     *  `null` = desconocida. */
    val timeMappingEvidenceKey: String? = null,
)

// --- DAO --------------------------------------------------------------------------------------

@Dao
interface FvpDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAssessmentRaw(row: FvpAssessmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConditionRaw(row: FvpConditionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAttemptRaw(row: FvpAttemptEntity): Long

    @Query("SELECT id FROM fvp_assessments WHERE sessionKey = :sessionKey LIMIT 1")
    suspend fun assessmentIdForSession(sessionKey: String): Long?

    @Query("SELECT * FROM fvp_assessments WHERE sessionKey = :sessionKey LIMIT 1")
    suspend fun bySessionKey(sessionKey: String): FvpAssessmentEntity?

    /**
     * Guarda una evaluación completa de forma transaccional e idempotente por `sessionKey`.
     *
     * En [PersistableFvpAssessment] los hijos llevan claves TEMPORALES: `condition.assessmentId`
     * se ignora y `attempt.conditionId` es el ORDINAL de la condición padre (no un id real). El
     * método inserta primero la raíz, después las condiciones con su `assessmentId` real y, por
     * último, los intentos con el `conditionId` real mapeado por ordinal.
     *
     * Valida antes de insertar (rangos, ordinales contiguos y claves únicas) y lanza
     * `IllegalArgumentException`/`IllegalStateException` si el registro no es persistible; en ese
     * caso la transacción no deja ninguna fila. Si ya existe una raíz con el mismo `sessionKey`,
     * devuelve su id sin duplicar hijos (mismo contrato que `JumpDao.save`).
     */
    @Transaction
    suspend fun save(record: PersistableFvpAssessment): Long {
        val assessment = record.assessment
        require(assessment.sessionKey.isNotBlank()) { "La serie F-V-P necesita un sessionKey no vacío." }
        val existingId = assessmentIdForSession(assessment.sessionKey)
        if (existingId != null) {
            val existing = bySessionKey(assessment.sessionKey)
                ?: error("La serie F-V-P existe pero no se pudo leer su cabecera.")
            check(existing.athleteId == assessment.athleteId) {
                "Conflicto de sessionKey F-V-P: el atleta de la serie existente difiere."
            }
            return existingId
        }
        val athleteId = requireNotNull(assessment.athleteId) { "Una serie F-V-P nueva necesita atleta." }
        require(athleteId > 0L) { "El athleteId de una serie F-V-P debe ser > 0." }
        require(assessment.protocolId in FVP_PROTOCOL_KEYS) {
            "protocolId debe ser SJ o CMJ, era '${assessment.protocolId}'."
        }
        requireFinite(assessment.bodyMassKg, "bodyMassKg")
        require(assessment.bodyMassKg > 0.0) { "bodyMassKg debe ser > 0." }
        requireFinite(assessment.pushOffM, "pushOffM")
        requireFiniteOrNull(assessment.pushOffLandmarkHeightCm, "pushOffLandmarkHeightCm")
        requireFiniteOrNull(assessment.pushOffCrouchCm, "pushOffCrouchCm")
        requireFiniteOrNull(assessment.pushOffExtendedCm, "pushOffExtendedCm")
        requireFiniteOrNull(assessment.sourceFps, "sourceFps")
        requireFinite(assessment.f0N, "f0N")
        requireFinite(assessment.slopeFv, "slopeFv")
        requireFinite(assessment.sfvNsPerM, "sfvNsPerM")
        // Invariante de signo del perfil F-V (misma regla que `FvpCalculator.fit`: `sfv = -slope`).
        // `sfvNsPerM` es la MAGNITUD positiva de la pendiente, de modo que un par con los dos signos
        // iguales (o con magnitudes distintas) es imposible para el motor y no puede entrar en la
        // tabla, venga de la captura, de una importación o de un backup editado a mano.
        require(assessment.sfvNsPerM == -assessment.slopeFv) {
            "sfvNsPerM debe ser exactamente -slopeFv (magnitud positiva de la pendiente); " +
                "sfvNsPerM=${assessment.sfvNsPerM}, slopeFv=${assessment.slopeFv}."
        }
        requireFiniteOrNull(assessment.v0Mps, "v0Mps")
        requireFiniteOrNull(assessment.pmaxW, "pmaxW")
        requireFiniteOrNull(assessment.r2, "r2")
        requireFinite(assessment.vMinMps, "vMinMps")
        requireFinite(assessment.vMaxMps, "vMaxMps")
        requireFinite(assessment.fMinN, "fMinN")
        requireFinite(assessment.fMaxN, "fMaxN")
        requireFiniteOrNull(assessment.f0RelNPerKg, "f0RelNPerKg")
        requireFiniteOrNull(assessment.pmaxRelWPerKg, "pmaxRelWPerKg")

        val conditions = record.conditions
        require(conditions.isNotEmpty()) { "Una serie F-V-P necesita al menos una condición." }
        require(conditions.map { it.ordinal }.sorted() == conditions.indices.toList()) {
            "Los ordinales de condición deben ser contiguos y únicos desde 0."
        }
        require(conditions.all { it.conditionKey.isNotBlank() }) { "conditionKey no puede estar vacío." }
        require(conditions.map { it.conditionKey }.toSet().size == conditions.size) {
            "conditionKey debe ser único dentro de la serie."
        }
        conditions.forEach { condition ->
            requireFinite(condition.addedLoadKg, "addedLoadKg")
            require(condition.addedLoadKg >= 0.0) { "addedLoadKg debe ser >= 0." }
            requireFinite(condition.totalMassKg, "totalMassKg")
            requireFinite(condition.pushOffM, "pushOffM")
            require(condition.pushOffM > MIN_PUSH_OFF_M && condition.pushOffM <= MAX_PUSH_OFF_M) {
                "El h_po efectivo de la condición debe estar en ($MIN_PUSH_OFF_M, $MAX_PUSH_OFF_M]."
            }
            requireFinite(condition.selectedHeightM, "selectedHeightM")
            require(condition.selectedHeightM > 0.0 && condition.selectedHeightM <= MAX_JUMP_HEIGHT_M) {
                "La altura seleccionada debe estar en (0, $MAX_JUMP_HEIGHT_M]."
            }
        }

        val conditionCount = conditions.size
        val attemptsByCondition = record.attempts.groupBy { it.conditionId }
        require(record.attempts.all { it.conditionId in 0L until conditionCount.toLong() }) {
            "conditionId temporal de un intento fuera del rango de ordinales de condición."
        }
        (0 until conditionCount).forEach { ordinal ->
            val rows = attemptsByCondition[ordinal.toLong()].orEmpty()
            require(rows.map { it.ordinal }.sorted() == rows.indices.toList()) {
                "Los ordinales de intento deben ser contiguos y únicos desde 0 por condición."
            }
        }
        record.attempts.forEach { attempt ->
            requireFinite(attempt.heightM, "heightM")
            require(attempt.heightM > 0.0 && attempt.heightM <= MAX_JUMP_HEIGHT_M) {
                "La altura del intento debe estar en (0, $MAX_JUMP_HEIGHT_M]."
            }
            requireFiniteOrNull(attempt.flightTimeS, "flightTimeS")
            requireFiniteOrNull(attempt.sourceFps, "sourceFps")
        }

        val assessmentId = insertAssessmentRaw(assessment.copy(athleteId = athleteId))
        check(assessmentId != -1L) { "No se pudo insertar la raíz F-V-P." }
        val conditionIds = LinkedHashMap<Int, Long>()
        conditions.forEach { condition ->
            val conditionId = insertConditionRaw(condition.copy(assessmentId = assessmentId))
            check(conditionId != -1L) { "No se pudo insertar la condición '${condition.conditionKey}'." }
            conditionIds[condition.ordinal] = conditionId
        }
        record.attempts.forEach { attempt ->
            val conditionId = conditionIds.getValue(attempt.conditionId.toInt())
            val attemptId = insertAttemptRaw(attempt.copy(conditionId = conditionId))
            check(attemptId != -1L) { "No se pudo insertar el intento ${attempt.ordinal}." }
        }
        return assessmentId
    }

    @Query("DELETE FROM fvp_assessments WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /** Borra la raíz; las cascadas de Room limpian condiciones e intentos. */
    @Transaction
    suspend fun delete(id: Long): Int = deleteById(id)

    @Transaction
    @Query("SELECT * FROM fvp_assessments WHERE id = :id LIMIT 1")
    suspend fun assessmentWithData(id: Long): FvpAssessmentWithData?

    @Query("SELECT * FROM fvp_assessments ORDER BY dateTime DESC, id DESC")
    fun recent(): Flow<List<FvpAssessmentEntity>>

    @Query("SELECT * FROM fvp_assessments WHERE athleteId = :athleteId ORDER BY dateTime DESC, id DESC")
    fun forAthlete(athleteId: Long): Flow<List<FvpAssessmentEntity>>

    @Query("UPDATE fvp_assessments SET notes = :notes WHERE id = :id")
    suspend fun updateNotes(id: Long, notes: String?): Int

    @Query("DELETE FROM fvp_assessments WHERE athleteId = :athleteId")
    suspend fun deleteForAthlete(athleteId: Long)
}

/** Claves de protocolo aceptadas en `fvp_assessments.protocolId`. */
internal val FVP_PROTOCOL_KEYS = setOf("SJ", "CMJ")

private fun requireFinite(value: Double, field: String) {
    require(value.isFinite()) { "$field debe ser finito." }
}

private fun requireFiniteOrNull(value: Double?, field: String) {
    require(value == null || value.isFinite()) { "$field debe ser finito o NULL (NaN nunca se persiste)." }
}
