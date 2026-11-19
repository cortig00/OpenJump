package com.openjump.app.data

import androidx.room.withTransaction
import com.openjump.app.angles.AngleGeometry
import com.openjump.app.angles.AngleMarkNameContract
import com.openjump.app.angles.AngleMeasurement
import com.openjump.app.angles.AnglePoint
import kotlinx.coroutines.flow.Flow

/** Id de una marca que todavía no existe: [AngleRepository] la inserta en vez de actualizarla. */
const val NEW_ANGLE_MARK_ID: Long = 0L

/** Versión vigente del modelo geométrico con el que se miden las coordenadas persistidas. */
const val ANGLE_GEOMETRY_VERSION: Int = 1

/** Rotaciones que produce el extractor; las coordenadas son post-rotación. */
val ANGLE_SUPPORTED_ROTATIONS: Set<Int> = setOf(0, 90, 180, 270)

/**
 * Marca candidata a persistir: presentación (nombre, color, visibilidad), orden estable
 * ([ordinal]) e identidad persistente ([id]).
 *
 * [id] = [NEW_ANGLE_MARK_ID] significa "marca nueva"; cualquier otro valor debe ser una marca que
 * YA pertenece a la raíz que se está actualizando. Las coordenadas están en píxeles de
 * presentación post-rotación de la raíz y se validan contra sus dimensiones de presentación.
 */
data class AngleMarkDraft(
    val id: Long = NEW_ANGLE_MARK_ID,
    val ordinal: Int,
    val name: String,
    val colorArgb: Int,
    val visible: Boolean = true,
    val pointA: AnglePoint,
    val pointB: AnglePoint,
    val pointC: AnglePoint,
)

/**
 * Raíz de un análisis manual de ángulos sobre un único frame. No incluye ids, revisión ni fechas:
 * los asigna [AngleRepository] (la raíz nueva nace en revisión 0).
 *
 * La raíz es **inmutable** una vez guardada: frame, PTS, geometría de presentación e identidad de
 * la fuente describen el sistema de coordenadas de las marcas; cambiarlos invalidaría las marcas
 * existentes. Las ediciones posteriores solo tocan las marcas ([AngleUpdateDraft]).
 */
data class AngleAnalysisDraft(
    val sessionKey: String,
    val athleteId: Long,
    val videoUri: String? = null,
    val sourceIdentity: String? = null,
    val sourceHash: String? = null,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val presentationWidth: Int,
    val presentationHeight: Int,
    val rotationDegrees: Int,
    val frameIndex: Int,
    val sourcePtsUs: Long,
    val geometryVersion: Int = ANGLE_GEOMETRY_VERSION,
    val marks: List<AngleMarkDraft>,
)

/**
 * Edición de un análisis existente, con control optimista de concurrencia.
 *
 * [marks] son upserts: las marcas con [NEW_ANGLE_MARK_ID] se insertan y las que llevan id se
 * actualizan conservando ese id. [removedMarkIds] es la ÚNICA vía de baja: una marca que no
 * aparece ni en [marks] ni en [removedMarkIds] se conserva intacta, de modo que un borrado nunca
 * puede ocurrir por omisión.
 */
data class AngleUpdateDraft(
    val analysisId: Long,
    val expectedRevision: Int,
    val marks: List<AngleMarkDraft> = emptyList(),
    val removedMarkIds: Set<Long> = emptySet(),
)

/** Foto consistente de una raíz y sus marcas ordenadas por `ordinal`. */
data class AngleAnalysisSnapshot(
    val analysis: AngleAnalysisEntity,
    val marks: List<AngleMarkEntity>,
)

/** Resultado de [AngleRepository.update]; nunca hay escritura parcial. */
sealed interface AngleUpdateResult {

    /** Edición aplicada; [snapshot] ya refleja la revisión incrementada y las marcas finales. */
    data class Updated(val snapshot: AngleAnalysisSnapshot) : AngleUpdateResult

    /**
     * La revisión almacenada no era [AngleUpdateDraft.expectedRevision]. No se sobrescribe nada:
     * el llamante debe releer y reaplicar su edición.
     */
    data class Conflict(val analysisId: Long, val storedRevision: Int) : AngleUpdateResult

    /** El análisis no existe (se borró o nunca se guardó). */
    data object NotFound : AngleUpdateResult
}

/** Plan de escritura calculado por [AngleAggregateRules.planUpdate]; lo consumen el repositorio y las pruebas puras. */
data class AngleUpdatePlan(
    val updatedMarks: List<AngleMarkDraft>,
    val newMarks: List<AngleMarkDraft>,
    val removedMarkIds: Set<Long>,
    val finalMarks: List<AngleMarkDraft>,
)

/**
 * Reglas puras (sin Room ni Android) del agregado de ángulos. Se aplican **antes** de cualquier
 * escritura, de modo que un borrador inválido falla sin tocar el guardado anterior. Al ser puras
 * también se prueban en JVM sin emulador.
 *
 * Convención de coordenadas: píxeles de presentación post-rotación, con el último píxel en
 * `width - 1`/`height - 1` (una dimensión no es una coordenada).
 */
object AngleAggregateRules {

    /**
     * Valida una raíz nueva y sus marcas. Rechaza ids de marca reutilizados: un guardado nuevo
     * siempre inserta hijos nuevos y nunca adopta marcas de otra raíz.
     */
    fun requireNewAnalysis(draft: AngleAnalysisDraft) {
        require(draft.sessionKey.isNotBlank()) {
            "Un análisis de ángulos necesita un sessionKey no vacío."
        }
        require(draft.athleteId > 0L) {
            "El athleteId de un análisis de ángulos debe ser > 0."
        }
        require(draft.encodedWidth > 0 && draft.encodedHeight > 0) {
            "Las dimensiones codificadas deben ser positivas; eran " +
                "${draft.encodedWidth}x${draft.encodedHeight}."
        }
        require(draft.presentationWidth > 0 && draft.presentationHeight > 0) {
            "Las dimensiones de presentación deben ser positivas; eran " +
                "${draft.presentationWidth}x${draft.presentationHeight}."
        }
        require(draft.rotationDegrees in ANGLE_SUPPORTED_ROTATIONS) {
            "rotationDegrees debe ser uno de ${ANGLE_SUPPORTED_ROTATIONS.sorted()}, " +
                "era ${draft.rotationDegrees}."
        }
        // La geometría de presentación debe ser coherente con la rotación: 90/270 intercambian los
        // ejes y 0/180 los conservan. Si no, las marcas se guardarían contra un sistema de
        // coordenadas que el extractor nunca produjo.
        if (draft.rotationDegrees % 180 == 0) {
            require(
                draft.presentationWidth == draft.encodedWidth &&
                    draft.presentationHeight == draft.encodedHeight,
            ) {
                "La rotación ${draft.rotationDegrees}° no puede intercambiar las dimensiones " +
                    "codificadas ${draft.encodedWidth}x${draft.encodedHeight}."
            }
        } else {
            require(
                draft.presentationWidth == draft.encodedHeight &&
                    draft.presentationHeight == draft.encodedWidth,
            ) {
                "La rotación ${draft.rotationDegrees}° debe intercambiar las dimensiones " +
                    "codificadas ${draft.encodedWidth}x${draft.encodedHeight}."
            }
        }
        require(draft.frameIndex >= 0) {
            "frameIndex debe ser >= 0, era ${draft.frameIndex}."
        }
        require(draft.sourcePtsUs >= 0L) {
            "sourcePtsUs debe ser >= 0, era ${draft.sourcePtsUs}."
        }
        require(draft.geometryVersion == ANGLE_GEOMETRY_VERSION) {
            "geometryVersion debe ser $ANGLE_GEOMETRY_VERSION, era ${draft.geometryVersion}: " +
                "las marcas se midieron con otro modelo geométrico."
        }
        require(draft.marks.all { it.id == NEW_ANGLE_MARK_ID }) {
            "Un guardado nuevo no puede reutilizar ids de marcas existentes; usa update()."
        }
        requireMarks(draft.marks, draft.presentationWidth, draft.presentationHeight)
    }

    /**
     * Calcula y valida el estado final de una edición sobre [existingMarks] (ya persistidas).
     *
     * Reglas:
     *  - solo se borran las marcas de [removedMarkIds] ("niños borrados sólo intencionalmente");
     *  - toda marca con id debe pertenecer a la raíz ([existingMarks]) y no puede repetirse;
     *  - una marca no puede actualizarse y borrarse a la vez;
     *  - el estado final (conservadas + actualizadas + nuevas) mantiene ordinales únicos, al menos
     *    una marca, nombres no vacíos y geometría medible dentro del frame.
     *
     * No consulta la base de datos: el repositorio comprueba antes si un id desconocido pertenece
     * a OTRA raíz, para dar un error explícito.
     *
     * @throws IllegalArgumentException si la edición no es aplicable.
     */
    fun planUpdate(
        existingMarks: List<AngleMarkDraft>,
        requestedMarks: List<AngleMarkDraft>,
        removedMarkIds: Set<Long>,
        presentationWidth: Int,
        presentationHeight: Int,
    ): AngleUpdatePlan {
        require(presentationWidth > 0 && presentationHeight > 0) {
            "Las dimensiones de presentación deben ser positivas; eran " +
                "${presentationWidth}x$presentationHeight."
        }
        require(existingMarks.all { it.id > NEW_ANGLE_MARK_ID }) {
            "El estado persistido de la raíz contiene marcas sin id; es inconsistente."
        }
        val existingIds = existingMarks.map { it.id }.toSet()
        require(existingIds.size == existingMarks.size) {
            "El estado persistido de la raíz contiene ids de marca repetidos."
        }
        require(removedMarkIds.all { it > NEW_ANGLE_MARK_ID }) {
            "Los ids a borrar deben ser > 0; eran $removedMarkIds."
        }
        require(removedMarkIds.all { it in existingIds }) {
            "Solo se pueden borrar marcas de esta raíz; ids desconocidos: " +
                "${removedMarkIds.filter { it !in existingIds }.sorted()}."
        }
        require(requestedMarks.all { it.id == NEW_ANGLE_MARK_ID || it.id in existingIds }) {
            "Una marca actualizada no pertenece a esta raíz: " +
                "${requestedMarks.map { it.id }.filter { it != NEW_ANGLE_MARK_ID && it !in existingIds }}."
        }
        val updatedIds = requestedMarks.map { it.id }.filter { it != NEW_ANGLE_MARK_ID }
        require(updatedIds.size == updatedIds.toSet().size) {
            "Una marca actualizada no puede repetirse en el borrador: $updatedIds."
        }
        require(updatedIds.none { it in removedMarkIds }) {
            "Una marca no puede actualizarse y borrarse en la misma operación: $updatedIds."
        }

        val untouched = existingMarks.filter { it.id !in removedMarkIds && it.id !in updatedIds }
        val finalMarks = untouched + requestedMarks
        requireMarks(finalMarks, presentationWidth, presentationHeight)
        return AngleUpdatePlan(
            updatedMarks = requestedMarks.filter { it.id != NEW_ANGLE_MARK_ID },
            newMarks = requestedMarks.filter { it.id == NEW_ANGLE_MARK_ID },
            removedMarkIds = removedMarkIds,
            finalMarks = finalMarks.sortedWith(compareBy({ it.ordinal }, { it.id })),
        )
    }

    /**
     * Valida un conjunto completo de marcas (al menos una): ids no nulos y únicos, ordinales
     * únicos y no negativos, nombres no vacíos **y dentro del contrato compartido de longitud**
     * ([AngleMarkNameContract]), puntos finitos dentro del frame y una terna A/B/C medible según
     * [AngleGeometry] (la misma definición que usa la UI).
     *
     * El límite de nombre se comprueba aquí, en la frontera de persistencia, para que un nombre
     * aceptado por el editor nunca llegue a la base y rompa después el backup completo (el preflight
     * del backup aplica el mismo límite). Un nombre largo se rechaza con error explícito: nunca se
     * trunca en silencio.
     */
    private fun requireMarks(marks: List<AngleMarkDraft>, width: Int, height: Int) {
        require(marks.isNotEmpty()) {
            "Un análisis de ángulos necesita al menos una marca."
        }
        val ids = marks.map { it.id }.filter { it != NEW_ANGLE_MARK_ID }
        require(ids.size == ids.toSet().size) {
            "Los ids de marca deben ser únicos dentro del análisis: $ids."
        }
        require(marks.map { it.ordinal }.toSet().size == marks.size) {
            "Los ordinales de las marcas deben ser únicos; eran ${marks.map { it.ordinal }}."
        }
        marks.forEachIndexed { index, mark ->
            val label = if (mark.id == NEW_ANGLE_MARK_ID) {
                "La marca nueva $index"
            } else {
                "La marca ${mark.id}"
            }
            require(mark.ordinal >= 0) {
                "$label tiene ordinal negativo (${mark.ordinal})."
            }
            require(mark.name.isNotBlank()) {
                "$label necesita un nombre no vacío."
            }
            require(AngleMarkNameContract.fits(mark.name)) {
                "$label tiene un nombre de ${AngleMarkNameContract.codeUnits(mark.name)} " +
                    "unidades UTF-16; el máximo persistible es " +
                    "${AngleMarkNameContract.MAX_CODE_UNITS} (el mismo límite del backup manual)."
            }
            requirePoint(label, "A", mark.pointA, width, height)
            requirePoint(label, "B", mark.pointB, width, height)
            requirePoint(label, "C", mark.pointC, width, height)
            when (val measurement = AngleGeometry.measure(mark.pointA, mark.pointB, mark.pointC)) {
                is AngleMeasurement.Measured -> Unit
                is AngleMeasurement.Invalid -> throw IllegalArgumentException(
                    "$label no define un ángulo medible: ${measurement.reason}.",
                )
            }
        }
    }

    /** Punto finito (NaN/infinito nunca es una marca válida) dentro del frame de presentación. */
    private fun requirePoint(
        label: String,
        pointName: String,
        point: AnglePoint,
        width: Int,
        height: Int,
    ) {
        require(point.isFinite) {
            "$label tiene el punto $pointName no finito (${point.x}, ${point.y})."
        }
        require(
            point.x >= 0.0 && point.x <= (width - 1).toDouble() &&
                point.y >= 0.0 && point.y <= (height - 1).toDouble(),
        ) {
            "$label tiene el punto $pointName (${point.x}, ${point.y}) fuera del frame " +
                "de presentación ${width}x$height."
        }
    }
}

/**
 * Repositorio del agregado de ángulos: única puerta de escritura, con validación previa y
 * transacción por operación.
 *
 * Contratos:
 *  - [saveNew] es idempotente por `sessionKey`: un reintento devuelve el id existente sin añadir
 *    marcas duplicadas y exige que atleta e identidad lógica de la fuente coincidan (nunca
 *    reasigna dueño ni mezcla dos raíces).
 *  - [update] es compare-and-swap por `expectedRevision`: un conflicto devuelve
 *    [AngleUpdateResult.Conflict] sin sobrescribir nada, y conserva el id estable de cada marca
 *    actualizada.
 *  - Toda validación ocurre antes de la primera escritura: un borrador inválido lanza
 *    `IllegalArgumentException` y deja intacto el guardado anterior.
 *
 * NO comprueba permisos ni si el atleta está archivado: la autorización (grants) pertenece a la
 * integración con la UI de atletas, todavía pendiente. La existencia del atleta sí se exige porque
 * la FK física la impone.
 */
class AngleRepository(
    private val database: JumpDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val dao: AngleDao get() = database.angleDao()

    /**
     * Guarda un análisis nuevo completo de forma transaccional.
     *
     * Si ya existe una raíz con el mismo `sessionKey`, devuelve su id sin insertar ni modificar
     * nada; antes exige que `athleteId` y `sourceIdentity` coincidan con los almacenados. Ese
     * reintento es el único camino idempotente: para editar un análisis existente hay que usar
     * [update].
     *
     * @throws IllegalArgumentException si el borrador no es válido o el atleta no existe.
     * @throws IllegalStateException si el `sessionKey` ya existe con otro atleta u otra identidad
     *   de fuente.
     */
    suspend fun saveNew(draft: AngleAnalysisDraft): Long = database.withTransaction {
        val existingId = dao.analysisIdForSessionKey(draft.sessionKey)
        if (existingId != null) {
            val existing = requireNotNull(dao.analysisById(existingId)) {
                "El análisis ${draft.sessionKey} existe pero no se pudo leer su cabecera."
            }
            requireSameIdentity(existing, draft)
            return@withTransaction existingId
        }
        AngleAggregateRules.requireNewAnalysis(draft)
        require(dao.athleteIdOrNull(draft.athleteId) != null) {
            "El atleta ${draft.athleteId} no existe; no se puede guardar un análisis sin dueño real."
        }
        val timestamp = now()
        val analysisId = dao.insertAnalysis(draft.toEntity(createdAt = timestamp, updatedAt = timestamp))
        if (analysisId == -1L) {
            // Otra escritura ganó el índice único de sessionKey entre la lectura y el insert:
            // mismo contrato idempotente, sin hijos duplicados.
            val racedId = requireNotNull(dao.analysisIdForSessionKey(draft.sessionKey)) {
                "El sessionKey ${draft.sessionKey} chocó pero no se pudo resolver la raíz existente."
            }
            val raced = requireNotNull(dao.analysisById(racedId)) {
                "El análisis ${draft.sessionKey} existe pero no se pudo leer su cabecera."
            }
            requireSameIdentity(raced, draft)
            return@withTransaction racedId
        }
        dao.insertMarks(draft.marks.map { it.toEntity(analysisId = analysisId) })
        analysisId
    }

    /**
     * Aplica una edición de marcas con control optimista de revisión.
     *
     * El orden interno garantiza atomicidad y evita colisiones del índice único
     * `(analysisId, ordinal)`: primero valida, después hace el compare-and-swap de la raíz, luego
     * borra solo los ids indicados, aparca los ordinales que cambian, aplica las actualizaciones e
     * inserta las marcas nuevas. Cualquier fallo revierte la transacción completa (la raíz
     * incluida), así que el guardado anterior nunca queda a medias.
     */
    suspend fun update(draft: AngleUpdateDraft): AngleUpdateResult = database.withTransaction {
        require(draft.analysisId > 0L) { "analysisId debe ser > 0." }
        require(draft.expectedRevision >= 0) { "expectedRevision debe ser >= 0." }
        val current = dao.analysisById(draft.analysisId)
            ?: return@withTransaction AngleUpdateResult.NotFound
        if (current.revision != draft.expectedRevision) {
            return@withTransaction AngleUpdateResult.Conflict(draft.analysisId, current.revision)
        }
        val currentMarks = dao.marksForAnalysis(draft.analysisId)
        val currentIds = currentMarks.map { it.id }.toSet()
        // Un id desconocido puede no existir o pertenecer a otra raíz. Se distinguen ANTES de
        // validar el plan para que el error diga cuál es el caso (nunca se mueve una marca).
        val unknownIds = draft.marks.map { it.id }
            .filter { it != NEW_ANGLE_MARK_ID && it !in currentIds }
        val foreignMarks = unknownIds.mapNotNull { markId ->
            dao.markOwner(markId)?.let { ownerId -> markId to ownerId }
        }
        require(foreignMarks.isEmpty()) {
            "Estas marcas pertenecen a otra raíz y no se pueden reutilizar: " +
                foreignMarks.joinToString { (markId, ownerId) -> "$markId (análisis $ownerId)" }
        }
        require(unknownIds.isEmpty()) {
            "Estas marcas no existen en el análisis ${draft.analysisId}: $unknownIds."
        }
        val plan = AngleAggregateRules.planUpdate(
            existingMarks = currentMarks.map { it.toDraft() },
            requestedMarks = draft.marks,
            removedMarkIds = draft.removedMarkIds,
            presentationWidth = current.presentationWidth,
            presentationHeight = current.presentationHeight,
        )
        val touched = dao.touchAnalysis(
            analysisId = draft.analysisId,
            expectedRevision = draft.expectedRevision,
            updatedAt = now(),
        )
        if (touched != 1) {
            val storedRevision = dao.analysisById(draft.analysisId)?.revision ?: current.revision
            return@withTransaction AngleUpdateResult.Conflict(draft.analysisId, storedRevision)
        }
        // Bajas explícitas, acotadas a la raíz: liberan su ordinal antes de cualquier alta.
        plan.removedMarkIds.forEach { markId ->
            check(dao.deleteMark(markId, draft.analysisId) == 1) {
                "La marca $markId desapareció durante la actualización del análisis ${draft.analysisId}."
            }
        }
        val previousById = currentMarks.associateBy { it.id }
        plan.updatedMarks.forEach { mark ->
            val previous = requireNotNull(previousById[mark.id]) {
                "La marca ${mark.id} no pertenece al análisis ${draft.analysisId}."
            }
            if (previous.ordinal != mark.ordinal) {
                check(dao.parkMarkOrdinal(mark.id, draft.analysisId) == 1) {
                    "No se pudo reordenar la marca ${mark.id} del análisis ${draft.analysisId}."
                }
            }
        }
        plan.updatedMarks.forEach { mark ->
            check(updateMark(draft.analysisId, mark) == 1) {
                "No se pudo actualizar la marca ${mark.id} del análisis ${draft.analysisId}."
            }
        }
        dao.insertMarks(plan.newMarks.map { it.toEntity(analysisId = draft.analysisId) })
        val stored = requireNotNull(dao.analysisById(draft.analysisId)) {
            "El análisis ${draft.analysisId} desapareció durante la actualización."
        }
        AngleUpdateResult.Updated(
            AngleAnalysisSnapshot(
                analysis = stored,
                marks = dao.marksForAnalysis(draft.analysisId),
            ),
        )
    }

    /** Borra la raíz y, por CASCADE, todas sus marcas. `false` si no existía. */
    suspend fun delete(analysisId: Long): Boolean {
        require(analysisId > 0L) { "analysisId debe ser > 0." }
        return dao.deleteAnalysis(analysisId) == 1
    }

    /** Foto consistente (raíz + marcas ordenadas) o `null` si el análisis no existe. */
    suspend fun snapshot(analysisId: Long): AngleAnalysisSnapshot? = database.withTransaction {
        dao.analysisById(analysisId)?.let { analysis ->
            AngleAnalysisSnapshot(analysis, dao.marksForAnalysis(analysisId))
        }
    }

    /** Igual que [snapshot] resolviendo por `sessionKey`. */
    suspend fun snapshotBySessionKey(sessionKey: String): AngleAnalysisSnapshot? =
        database.withTransaction {
            dao.analysisBySessionKey(sessionKey)?.let { analysis ->
                AngleAnalysisSnapshot(analysis, dao.marksForAnalysis(analysis.id))
            }
        }

    /** Raíces ordenadas (más reciente primero) para la lista de análisis. */
    fun observeAnalyses(): Flow<List<AngleAnalysisEntity>> = dao.observeAnalyses()

    /** Raíces de un atleta, ordenadas igual que [observeAnalyses]. */
    fun observeAnalysesForAthlete(athleteId: Long): Flow<List<AngleAnalysisEntity>> {
        require(athleteId > 0L) { "athleteId debe ser > 0." }
        return dao.observeAnalysesForAthlete(athleteId)
    }

    private suspend fun updateMark(analysisId: Long, mark: AngleMarkDraft): Int =
        dao.updateMark(
            analysisId = analysisId,
            markId = mark.id,
            ordinal = mark.ordinal,
            name = mark.name,
            colorArgb = mark.colorArgb,
            visible = mark.visible,
            pointAX = mark.pointA.x,
            pointAY = mark.pointA.y,
            pointBX = mark.pointB.x,
            pointBY = mark.pointB.y,
            pointCX = mark.pointC.x,
            pointCY = mark.pointC.y,
        )

    /**
     * Se ejecuta solo en el camino idempotente: el dueño y la identidad lógica de la fuente nunca
     * cambian por un reintento. La URI del vídeo sí puede cambiar legítimamente (la identidad
     * lógica es la estable), así que no se compara.
     */
    private fun requireSameIdentity(existing: AngleAnalysisEntity, draft: AngleAnalysisDraft) {
        check(existing.athleteId == draft.athleteId) {
            "Conflicto de sessionKey: el análisis existente pertenece a otro atleta " +
                "(guardado=${existing.athleteId}, nuevo=${draft.athleteId})."
        }
        check(existing.sourceIdentity == draft.sourceIdentity) {
            "Conflicto de sessionKey: la identidad lógica de la fuente difiere " +
                "(guardada=${existing.sourceIdentity}, nueva=${draft.sourceIdentity})."
        }
    }
}

private fun AngleAnalysisDraft.toEntity(createdAt: Long, updatedAt: Long) = AngleAnalysisEntity(
    sessionKey = sessionKey,
    athleteId = athleteId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    revision = 0,
    videoUri = videoUri,
    sourceIdentity = sourceIdentity,
    sourceHash = sourceHash,
    encodedWidth = encodedWidth,
    encodedHeight = encodedHeight,
    presentationWidth = presentationWidth,
    presentationHeight = presentationHeight,
    rotationDegrees = rotationDegrees,
    frameIndex = frameIndex,
    sourcePtsUs = sourcePtsUs,
    geometryVersion = geometryVersion,
)

private fun AngleMarkDraft.toEntity(analysisId: Long) = AngleMarkEntity(
    analysisId = analysisId,
    ordinal = ordinal,
    name = name,
    colorArgb = colorArgb,
    visible = visible,
    pointAX = pointA.x,
    pointAY = pointA.y,
    pointBX = pointB.x,
    pointBY = pointB.y,
    pointCX = pointC.x,
    pointCY = pointC.y,
)

private fun AngleMarkEntity.toDraft() = AngleMarkDraft(
    id = id,
    ordinal = ordinal,
    name = name,
    colorArgb = colorArgb,
    visible = visible,
    pointA = AnglePoint(pointAX, pointAY),
    pointB = AnglePoint(pointBX, pointBY),
    pointC = AnglePoint(pointCX, pointCY),
)
