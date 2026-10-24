package com.openjump.app.ui.fvp

import com.openjump.app.data.FVP_METHOD_VERSION
import com.openjump.app.data.FvpAssessmentEntity
import com.openjump.app.data.FvpAssessmentWithData
import com.openjump.app.data.FvpAttemptEntity
import com.openjump.app.data.FvpConditionEntity
import com.openjump.app.data.FvpConditionWithAttempts
import com.openjump.app.data.FvpDao
import com.openjump.app.data.FvpRepository
import com.openjump.app.data.FvpSnapshotStatus
import com.openjump.app.data.PersistableFvpAssessment
import com.openjump.app.data.fvpSnapshotStatusFor
import com.openjump.app.fvp.FvpEligibilityProblem
import com.openjump.fvp.FvpProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lectura TOLERANTE de filas guardadas (S1a) y decisión de estado del snapshot.
 *
 * Cubre las dos caras del defecto corregido:
 * - la función PURA [fvpSnapshotStatusFor] con entradas literales (sin DAO ni motor);
 * - [FvpRepository.byId] con un `FvpDao` falso en memoria: una clave persistida desconocida
 *   (protocolo / modo de h_po / método) o una serie no computable hoy devuelven la fila con
 *   `result == null` y [FvpSnapshotStatus.RECALCULATION_UNAVAILABLE] en vez de lanzar, y una serie
 *   computable se clasifica MATCHES / MISMATCH / STALE / STALE_METHOD.
 */
class FvpRepositoryReadTest {

    // --- decisión pura del estado ---------------------------------------------------------------

    /**
     * Tabla de verdad literal: algoritmo distinto -> STALE (manda aunque `methodVersion` también
     * difiera o los números coincidan); mismo algoritmo y política distinta -> STALE_METHOD; mismo
     * algoritmo y política + números iguales/distintos -> MATCHES/MISMATCH; sin recálculo ->
     * RECALCULATION_UNAVAILABLE.
     */
    @Test
    fun statusDecisionTruthTableIsLiteral() {
        fun status(
            storedAlgorithm: String,
            storedMethod: String,
            recalculatedAlgorithm: String?,
            numbersMatch: Boolean,
        ): FvpSnapshotStatus = fvpSnapshotStatusFor(
            storedAlgorithmVersion = storedAlgorithm,
            recalculatedAlgorithmVersion = recalculatedAlgorithm,
            storedMethodVersion = storedMethod,
            currentMethodVersion = "app-1.0",
            numbersMatch = numbersMatch,
        )

        assertEquals(FvpSnapshotStatus.STALE, status("algo-1", "app-1.0", "algo-2", true))
        // Las DOS versiones difieren: manda el algoritmo (STALE, nunca STALE_METHOD).
        assertEquals(FvpSnapshotStatus.STALE, status("algo-1", "app-0.9", "algo-2", true))
        assertEquals(FvpSnapshotStatus.STALE_METHOD, status("algo-1", "app-0.9", "algo-1", true))
        assertEquals(FvpSnapshotStatus.STALE_METHOD, status("algo-1", "app-0.9", "algo-1", false))
        assertEquals(FvpSnapshotStatus.MATCHES, status("algo-1", "app-1.0", "algo-1", true))
        assertEquals(FvpSnapshotStatus.MISMATCH, status("algo-1", "app-1.0", "algo-1", false))
        // Sin recálculo el flag de números es irrelevante: nunca se declara coincidencia.
        assertEquals(
            FvpSnapshotStatus.RECALCULATION_UNAVAILABLE,
            status("algo-1", "app-1.0", null, false),
        )
        assertEquals(
            FvpSnapshotStatus.RECALCULATION_UNAVAILABLE,
            status("algo-1", "app-1.0", null, true),
        )
    }

    // --- byId tolerante -------------------------------------------------------------------------

    /**
     * Defecto reproducido: hoy `byId` lanza `IllegalStateException` en los casos de clave
     * persistida desconocida. Con la corrección devuelve SIEMPRE la fila: clave de protocolo
     * desconocida, modo de h_po desconocido, método de medida desconocido y serie legible que hoy
     * no es computable (todas las alturas iguales).
     */
    @Test
    fun byIdReturnsInsteadOfThrowingForUnknownKeysAndNonComputableSeries() = runBlocking {
        val dao = FixtureFvpDao()
        val repository = FvpRepository(dao)
        repository.save(requireNotNull(validDraft().toPersistDraftOrNull()))
        val stored = requireNotNull(dao.stored)

        // 1) protocolo persistido desconocido (fila escrita por otra versión).
        dao.stored = stored.copy(assessment = stored.assessment.copy(protocolId = "FVP-FUTURE"))
        val unknownProtocol = requireNotNull(repository.byId(ROW_ID))
        assertNull(unknownProtocol.result)
        assertNull(unknownProtocol.eligibility)
        assertEquals(FvpSnapshotStatus.RECALCULATION_UNAVAILABLE, unknownProtocol.snapshotStatus)
        assertNull(unknownProtocol.snapshotMatches)
        assertFalse(unknownProtocol.profileEligible)
        // Los números persistidos siguen legibles (no se pierde el snapshot).
        assertEquals(stored.assessment.f0N, unknownProtocol.assessment.f0N, 0.0)
        assertEquals(stored.assessment.sfvNsPerM, unknownProtocol.assessment.sfvNsPerM, 0.0)

        // 2) modo de h_po persistido desconocido (`pushOffMode`): tampoco hay recálculo, y NUNCA
        // un modo por defecto silencioso que cambiaría el h_po efectivo de toda la serie.
        dao.stored = stored.copy(
            assessment = stored.assessment.copy(pushOffMode = "FUTURE_MODE"),
        )
        val unknownPushOffMode = requireNotNull(repository.byId(ROW_ID))
        assertNull(unknownPushOffMode.result)
        assertNull(unknownPushOffMode.eligibility)
        assertEquals(FvpSnapshotStatus.RECALCULATION_UNAVAILABLE, unknownPushOffMode.snapshotStatus)
        assertNull(unknownPushOffMode.snapshotMatches)
        assertFalse(unknownPushOffMode.profileEligible)
        assertEquals(stored.assessment.f0N, unknownPushOffMode.assessment.f0N, 0.0)
        assertEquals(stored.assessment.pushOffM, unknownPushOffMode.assessment.pushOffM, 0.0)

        // 3) método de medida persistido desconocido.
        dao.stored = stored.copy(
            conditions = stored.conditions.mapIndexed { index, row ->
                if (index == 0) {
                    row.copy(attempts = row.attempts.map { it.copy(method = "LASER_FUTURE") })
                } else {
                    row
                }
            },
        )
        val unknownMethod = requireNotNull(repository.byId(ROW_ID))
        assertNull(unknownMethod.result)
        assertNull(unknownMethod.eligibility)
        assertEquals(FvpSnapshotStatus.RECALCULATION_UNAVAILABLE, unknownMethod.snapshotStatus)

        // 4) serie estructuralmente legible pero NO computable hoy: todas las alturas iguales.
        dao.stored = stored.copy(
            conditions = stored.conditions.map { row ->
                row.copy(attempts = row.attempts.map { it.copy(heightM = 0.40) })
            },
        )
        val nonComputable = requireNotNull(repository.byId(ROW_ID))
        assertNull(nonComputable.result)
        assertEquals(FvpSnapshotStatus.RECALCULATION_UNAVAILABLE, nonComputable.snapshotStatus)
        // La elegibilidad SÍ se pudo construir: se explica el bloqueo, no se inventa resultado.
        val eligibility = requireNotNull(nonComputable.eligibility)
        assertTrue(eligibility.blocking.contains(FvpEligibilityProblem.IDENTICAL_SELECTED_HEIGHTS))
        assertFalse(eligibility.canCompute)
        assertFalse(nonComputable.profileEligible)
        assertEquals(stored.assessment.r2, nonComputable.assessment.r2)
    }

    /**
     * Serie computable: el recálculo clasifica contra las DOS versiones persistidas. Mismo motor y
     * misma política y números iguales -> MATCHES; números distintos -> MISMATCH (deriva);
     * `algorithmVersion` distinta -> STALE; misma algoritmo y `methodVersion` distinta ->
     * STALE_METHOD.
     */
    @Test
    fun byIdClassifiesStoredVersionsAgainstTheRecalculation() = runBlocking {
        val dao = FixtureFvpDao()
        val repository = FvpRepository(dao)
        repository.save(requireNotNull(validDraft().toPersistDraftOrNull()))
        val stored = requireNotNull(dao.stored)
        assertEquals(FVP_METHOD_VERSION, stored.assessment.methodVersion)

        val matched = requireNotNull(repository.byId(ROW_ID))
        assertNotNull(matched.result)
        assertNotNull(matched.eligibility)
        assertTrue(matched.profileEligible)
        assertEquals(FvpSnapshotStatus.MATCHES, matched.snapshotStatus)
        assertEquals(true, matched.snapshotMatches)

        dao.stored = stored.copy(assessment = stored.assessment.copy(f0N = stored.assessment.f0N + 1.0))
        val mismatched = requireNotNull(repository.byId(ROW_ID))
        assertEquals(FvpSnapshotStatus.MISMATCH, mismatched.snapshotStatus)
        assertEquals(false, mismatched.snapshotMatches)
        assertNotNull(mismatched.result)

        dao.stored = stored.copy(assessment = stored.assessment.copy(algorithmVersion = "fvp-poc-0.9"))
        val stale = requireNotNull(repository.byId(ROW_ID))
        assertEquals(FvpSnapshotStatus.STALE, stale.snapshotStatus)
        assertNull(stale.snapshotMatches)

        dao.stored = stored.copy(assessment = stored.assessment.copy(methodVersion = "fvp-app-0.9"))
        val staleMethod = requireNotNull(repository.byId(ROW_ID))
        assertEquals(FvpSnapshotStatus.STALE_METHOD, staleMethod.snapshotStatus)
        assertNull(staleMethod.snapshotMatches)
        assertNotNull("el recálculo del motor sigue disponible", staleMethod.result)
    }

    // --- fixtures -------------------------------------------------------------------------------

    /** Borrador válido de 4 condiciones con alturas distintas y fps ALLOW: serie computable. */
    private fun validDraft(): FvpDraft = FvpDraft(
        athleteId = 1L,
        protocol = FvpProtocol.SJ,
        bodyMassKg = 80.0,
        bodyMassFromProfile = true,
        pushOffMode = FvpPushOffUiMode.COMMON,
        commonPushOffM = 0.40,
        pushOffLandmark = "TROCANTER",
        pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
        pushOffMeasuredAt = 1_700_000_000_000L,
        conditions = listOf(
            FvpConditionDraft(
                conditionKey = "0kg",
                addedLoadKg = 0.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.40, sourceFps = 240.0)),
            ),
            FvpConditionDraft(
                conditionKey = "20kg",
                addedLoadKg = 20.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.32, sourceFps = 240.0)),
            ),
            FvpConditionDraft(
                conditionKey = "40kg",
                addedLoadKg = 40.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.25, sourceFps = 240.0)),
            ),
            FvpConditionDraft(
                conditionKey = "60kg",
                addedLoadKg = 60.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.18, sourceFps = 240.0)),
            ),
        ),
    )

    /**
     * DAO falso en memoria: captura el registro de [FvpRepository.save] y lo sirve con ids reales
     * (raíz 7, condiciones 700+, intentos 70000+) desde [assessmentWithData]. El resto de métodos
     * falla en voz alta: este test no los usa.
     */
    private class FixtureFvpDao : FvpDao {
        var stored: FvpAssessmentWithData? = null
        var captured: PersistableFvpAssessment? = null

        override suspend fun save(record: PersistableFvpAssessment): Long {
            captured = record
            stored = record.withAssignedIds(assessmentId = ROW_ID)
            return ROW_ID
        }

        override suspend fun assessmentWithData(id: Long): FvpAssessmentWithData? = stored

        override suspend fun insertAssessmentRaw(row: FvpAssessmentEntity): Long = unusedDao("insertAssessmentRaw")
        override suspend fun insertConditionRaw(row: FvpConditionEntity): Long = unusedDao("insertConditionRaw")
        override suspend fun insertAttemptRaw(row: FvpAttemptEntity): Long = unusedDao("insertAttemptRaw")
        override suspend fun assessmentIdForSession(sessionKey: String): Long? = unusedDao("assessmentIdForSession")
        override suspend fun bySessionKey(sessionKey: String): FvpAssessmentEntity? = unusedDao("bySessionKey")
        override suspend fun deleteById(id: Long): Int = unusedDao("deleteById")
        override suspend fun updateNotes(id: Long, notes: String?): Int = unusedDao("updateNotes")
        override suspend fun deleteForAthlete(athleteId: Long): Unit = unusedDao("deleteForAthlete")
        override fun recent(): Flow<List<FvpAssessmentEntity>> = emptyFlow()
        override fun forAthlete(athleteId: Long): Flow<List<FvpAssessmentEntity>> = emptyFlow()

        private fun unusedDao(name: String): Nothing =
            throw UnsupportedOperationException("FvpDao.$name no se usa en FvpRepositoryReadTest")
    }

    private companion object {
        const val ROW_ID = 7L

        /**
         * Materializa las claves TEMPORALES del registro persistible (`conditionId` = ordinal de la
         * condición padre) en filas con ids reales, igual que haría Room al insertar.
         */
        fun PersistableFvpAssessment.withAssignedIds(assessmentId: Long): FvpAssessmentWithData {
            val conditionRows = conditions.mapIndexed { index, condition ->
                condition.copy(id = assessmentId * 100 + index, assessmentId = assessmentId)
            }
            val storedConditions = conditionRows.map { condition ->
                val attemptsOfCondition = attempts
                    .filter { it.conditionId == condition.ordinal.toLong() }
                    .sortedBy { it.ordinal }
                    .mapIndexed { index, attempt ->
                        attempt.copy(id = condition.id * 100 + index, conditionId = condition.id)
                    }
                FvpConditionWithAttempts(condition = condition, attempts = attemptsOfCondition)
            }
            return FvpAssessmentWithData(
                assessment = assessment.copy(id = assessmentId),
                conditions = storedConditions,
            )
        }
    }
}
