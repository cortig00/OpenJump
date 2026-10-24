package com.openjump.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.fvp.FvpEngine
import com.openjump.app.fvp.FvpEligibilityProblem
import com.openjump.app.fvp.FvpSeriesInput
import com.openjump.app.fvp.checkSeries
import com.openjump.app.fvp.selectedValidAttemptIndex
import com.openjump.fvp.FvpConditionPoint
import com.openjump.app.fvp.FvpAttemptDraft as EligibilityAttemptDraft
import com.openjump.app.fvp.FvpConditionDraft as EligibilityConditionDraft
import com.openjump.app.fvp.FvpPushOffMode as EligibilityPushOffMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contrato instrumentado de la persistencia F-V-P (Rojo v10→v11):
 * guardado/cálculo con motor, idempotencia, validaciones, cascada, borrado por atleta y FKs.
 */
@RunWith(AndroidJUnit4::class)
class FvpDaoInstrumentedTest {

    private lateinit var database: JumpDatabase
    private lateinit var dao: FvpDao
    private lateinit var repository: FvpRepository

    private val athleteId = 1L
    private val otherAthleteId = 2L

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JumpDatabase::class.java,
        ).build()
        dao = database.fvpDao()
        repository = FvpRepository(dao)
        runBlocking {
            database.athleteDao().insert(
                AthleteEntity(displayName = "Test athlete", createdAt = 1L, updatedAt = 1L),
            )
            database.athleteDao().insert(
                AthleteEntity(displayName = "Other athlete", createdAt = 1L, updatedAt = 1L),
            )
        }
    }

    @After
    fun closeDatabase() = database.close()

    // --- 1. Guardado + snapshot recalculado + orden de hijos -----------------------------------

    @Test
    fun saveRecalculatesSnapshotOrdersChildrenAndKeepsInvalidAttempts() = runBlocking {
        val id = repository.save(draft())
        assertTrue(id > 0L)

        val stored = repository.byId(id)
        assertNotNull(stored)
        stored!!

        assertEquals(FvpSnapshotStatus.MATCHES, stored.snapshotStatus)
        assertEquals(true, stored.snapshotMatches)
        assertFalse(stored.profileEligible)

        // Condiciones ordenadas por ordinal; la masa total la deriva el guardado, no el llamante.
        assertEquals(listOf(0, 1, 2), stored.conditions.map { it.ordinal })
        assertEquals(listOf("0kg", "20kg", "40kg"), stored.conditions.map { it.conditionKey })
        assertEquals(70.0, stored.conditions[0].totalMassKg, 0.0)
        assertEquals(90.0, stored.conditions[1].totalMassKg, 0.0)
        assertEquals(110.0, stored.conditions[2].totalMassKg, 0.0)
        assertEquals(listOf(0.32, 0.24, 0.18), stored.conditions.map { it.selectedHeightM })

        // El intento inválido de mayor altura (0.50 m) NO se selecciona; gana el válido 0.18 m.
        // En "20kg" el mayor válido (0.24 m) es el ordinal 0, no el 1: la selección es por altura.
        assertEquals(listOf(1, 0, 2), stored.conditions.map { it.selectedAttemptOrdinal })
        val heavyAttempts = stored.attempts
            .filter { it.conditionId == stored.conditions[2].id }
            .sortedBy { it.ordinal }
        assertEquals(listOf(0, 1, 2), heavyAttempts.map { it.ordinal })
        assertEquals(listOf(false, true, true), heavyAttempts.map { it.valid })
        assertEquals("CONTACTO", heavyAttempts[0].exclusionReason)
        assertEquals(0.50, heavyAttempts[0].heightM, 0.0)

        // Los campos nuevos de la raíz llegan a la tabla.
        val entity = stored.assessment
        assertEquals("MEASURED", entity.pushOffSource)
        assertEquals("LANDMARK_EXTENDED_MINUS_CROUCH", entity.pushOffProtocol)
        assertEquals(900L, entity.pushOffMeasuredAt)
        assertEquals(40.0, entity.pushOffCrouchCm!!, 0.0)
        assertEquals(98.0, entity.pushOffExtendedCm!!, 0.0)
        assertEquals("CMJ", entity.protocolId)

        // Recalculo INDEPENDIENTE con el motor a partir de las filas persistidas.
        val points = stored.conditions.map { condition ->
            val point = FvpEngine.point(condition.totalMassKg, condition.selectedHeightM, condition.pushOffM)
            FvpConditionPoint(
                conditionId = condition.conditionKey,
                totalMassKg = condition.totalMassKg,
                heightM = condition.selectedHeightM,
                pushOffM = condition.pushOffM,
                forceN = point.forceN,
                velocityMps = point.velocityMps,
                powerW = point.powerW,
            )
        }
        val recomputed = FvpEngine.profile(points = points, bodyMassKg = 70.0, sourceFps = 240.0)
        assertEquals(recomputed.f0N, entity.f0N, 0.0)
        assertEquals(recomputed.slopeFv, entity.slopeFv, 0.0)
        assertEquals(recomputed.sfvNsPerM, entity.sfvNsPerM, 0.0)
        assertEquals(recomputed.n, entity.conditionCount)
        assertEquals(recomputed.vMinMps, entity.vMinMps, 0.0)
        assertEquals(recomputed.vMaxMps, entity.vMaxMps, 0.0)
        assertEquals(recomputed.fMinN, entity.fMinN, 0.0)
        assertEquals(recomputed.fMaxN, entity.fMaxN, 0.0)

        // warningsCsv: warnings del motor (orden del motor) + flags de elegibilidad, sin duplicados.
        // La fixture tiene 3 condiciones y mezcla métodos seleccionados: Q1 conserva ambos flags.
        val expectedCsv = recomputed.warnings.map { it.name }.joinToString(",") +
            ",FEW_CONDITIONS,MIXED_SELECTED_METHODS"
        assertEquals(expectedCsv, entity.warningsCsv)
        assertEquals(stored.warnings, stored.warnings.distinct())
        assertTrue(stored.warnings.contains("FEW_CONDITIONS"))
        assertTrue(stored.warnings.contains("MIXED_SELECTED_METHODS"))
    }

    @Test
    fun fourConditionSeriesIsPersistedAsProfileEligible() = runBlocking {
        val id = repository.save(
            draft(sessionKey = "fvp-profile-4", conditions = profileEligibleConditions()),
        )
        val stored = repository.byId(id)
        assertNotNull(stored)
        stored!!

        assertTrue(stored.profileEligible)
        assertEquals(FvpSnapshotStatus.MATCHES, stored.snapshotStatus)
        assertEquals(4, stored.conditions.size)
        // S1a: la elegibilidad es nullable (una fila no recalculable no tiene veredicto). Una serie
        // guardada por la app con 4 cargas y h_po valido SIEMPRE se recalcula, asi que aqui exigir
        // que exista es parte de la comprobacion, no una concesion del compilador.
        val eligibility = requireNotNull(stored.eligibility) {
            "una serie guardada por la app debe ser recalculable y tener elegibilidad"
        }
        assertTrue(eligibility.flags.none { it.name.startsWith("TIMING_") })
        assertFalse(stored.assessment.warningsCsv.contains("TIMING_"))
    }

    // --- 2. Idempotencia por sessionKey ---------------------------------------------------------

    @Test
    fun saveIsIdempotentBySessionKey() = runBlocking {
        val first = repository.save(draft(sessionKey = "fvp-idempotent"))
        val second = repository.save(draft(sessionKey = "fvp-idempotent"))

        assertEquals(first, second)
        assertEquals(1, countRows("fvp_assessments"))
        assertEquals(3, countRows("fvp_conditions"))
        assertEquals(7, countRows("fvp_attempts"))
        assertEquals(1, repository.recent().first().size)
    }

    // --- 2b. Lectura por `sessionKey` (R3) -------------------------------------------------------

    /**
     * R3: lectura SOLO-LECTURA de la fila que ocupa un `sessionKey` (id de la fila + su dueño, sin
     * cargar hijos y sin recalcular nada). Es la verdad persistida que el dueño del flujo F-V-P
     * consulta ANTES de adoptar un borrador recuperado: `save` es idempotente por `sessionKey`, pero
     * si la fila pertenece a OTRO atleta la inserción lanzaria, de modo que "guardar es idempotente"
     * no basta para decidir.
     *
     * Sin cambio de esquema: reutiliza el `SELECT` por `sessionKey` (indice UNICO) ya existente, y
     * `FvpRepository.bySessionKey` solo proyecta `id` y `athleteId`.
     *
     * Ejecutado en el AVD autorizado dentro de las regresiones76/79 de checkpoints19/20.
     * La consulta DAO no prueba por si sola recuperacion tras muerte real del proceso.
     */
    @Test
    fun sessionKeyLookupReturnsRowIdAndOwnerAndNullWhenAbsent() = runBlocking {
        val id = repository.save(draft(sessionKey = "fvp-lookup", athleteId = athleteId))
        val otherId = repository.save(draft(sessionKey = "fvp-lookup-other", athleteId = otherAthleteId))

        val stored = repository.bySessionKey("fvp-lookup")
        assertNotNull("hay fila con ese sessionKey", stored)
        assertEquals(id, stored!!.rowId)
        assertEquals(athleteId, stored.athleteId)

        // El dueño viaja TAL CUAL: es lo que permite detectar el conflicto de `sessionKey` sin
        // intentar el `save` (que lanzaria por dueño distinto).
        val storedOther = repository.bySessionKey("fvp-lookup-other")
        assertNotNull(storedOther)
        assertEquals(otherId, storedOther!!.rowId)
        assertEquals(otherAthleteId, storedOther.athleteId)

        assertNull("sin fila no hay identidad persistida", repository.bySessionKey("fvp-lookup-absent"))
    }

    // --- 3. Elegibilidad antes del motor + validaciones del borrador ----------------------------

    @Test
    fun nonComputableSeriesIsRejectedByEligibilityWithoutPersisting() = runBlocking {
        val tooFew = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.save(
                    draft(sessionKey = "fvp-blocked-1", conditions = listOf(validConditions().first())),
                )
            }
        }
        assertTrue(tooFew.message!!.contains("TOO_FEW_CONDITIONS"))

        val duplicateLoad = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.save(
                    draft(
                        sessionKey = "fvp-blocked-2",
                        conditions = validConditions().let { listOf(it[0], it[1].copy(addedLoadKg = 0.0)) },
                    ),
                )
            }
        }
        assertTrue(duplicateLoad.message!!.contains("DUPLICATE_LOAD"))

        val missingPushOff = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.save(
                    draft(
                        sessionKey = "fvp-blocked-3",
                        pushOffMode = "PER_CONDITION",
                        conditions = validConditions().map { it.copy(pushOffM = null) },
                    ),
                )
            }
        }
        assertTrue(missingPushOff.message!!.contains("MISSING_PUSH_OFF"))

        assertEquals(0, countRows("fvp_assessments"))
        assertEquals(0, countRows("fvp_conditions"))
        assertEquals(0, countRows("fvp_attempts"))
    }

    @Test
    fun draftValidationRejectsUnknownKeysAndOutOfRangeValues() = runBlocking {
        val invalidDrafts = listOf(
            draft(sessionKey = "fvp-invalid-protocol").copy(protocol = "DROP_JUMP"),
            draft(sessionKey = "fvp-invalid-method").let {
                it.copy(
                    conditions = it.conditions.mapIndexed { index, condition ->
                        if (index == 0) condition.copy(attempts = condition.attempts.map { attempt -> attempt.copy(method = "LASER") }) else condition
                    },
                )
            },
            draft(sessionKey = "fvp-invalid-mass-source").copy(bodyMassSource = "GUESS"),
            draft(sessionKey = "fvp-invalid-push-source").copy(pushOffSource = "TALLA"),
            draft(sessionKey = "fvp-invalid-body-mass").copy(bodyMassKg = 0.0),
            draft(sessionKey = "fvp-invalid-athlete").copy(athleteId = 0L),
            draft(sessionKey = "fvp-invalid-fps").copy(sourceFps = 0.0),
        )
        invalidDrafts.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.save(invalid) }
            }
        }
        assertEquals(0, countRows("fvp_assessments"))
        assertEquals(0, countRows("fvp_conditions"))
        assertEquals(0, countRows("fvp_attempts"))
    }

    // --- 4. Validaciones del DAO (transacción sin filas) ----------------------------------------

    @Test
    fun daoValidationsRejectInvalidRecordsAndLeaveNoRows() = runBlocking {
        val base = validRecord()
        val invalidRecords = listOf(
            base.copy(assessment = base.assessment.copy(athleteId = null)),
            base.copy(assessment = base.assessment.copy(athleteId = 0L)),
            base.copy(assessment = base.assessment.copy(protocolId = "DROP_JUMP")),
            base.copy(assessment = base.assessment.copy(bodyMassKg = Double.NaN)),
            base.copy(conditions = listOf(base.conditions[0], base.conditions[1].copy(ordinal = 0))),
            base.copy(conditions = listOf(base.conditions[0], base.conditions[1].copy(conditionKey = "0kg"))),
            base.copy(conditions = listOf(base.conditions[0].copy(conditionKey = " "), base.conditions[1])),
            base.copy(conditions = listOf(base.conditions[0].copy(addedLoadKg = -5.0), base.conditions[1])),
            base.copy(conditions = listOf(base.conditions[0].copy(pushOffM = 0.9), base.conditions[1])),
            base.copy(conditions = listOf(base.conditions[0].copy(pushOffM = 0.04), base.conditions[1])),
            base.copy(
                attempts = listOf(
                    base.attempts[0],
                    base.attempts[1].copy(ordinal = 2),
                    base.attempts[2],
                ),
            ),
            base.copy(attempts = listOf(base.attempts[0].copy(heightM = 0.0), base.attempts[1], base.attempts[2])),
            base.copy(attempts = listOf(base.attempts[0].copy(heightM = 1.3), base.attempts[1], base.attempts[2])),
        )
        invalidRecords.forEach { record ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { dao.save(record) }
            }
            assertEquals(0, countRows("fvp_assessments"))
            assertEquals(0, countRows("fvp_conditions"))
            assertEquals(0, countRows("fvp_attempts"))
        }

        // El registro válido sí entra y mapea conditionId por ordinal.
        val id = dao.save(base)
        assertTrue(id > 0L)
        assertEquals(2, countRows("fvp_conditions"))
        assertEquals(3, countRows("fvp_attempts"))
    }

    // --- 5. Borrado raíz con CASCADE + borrado por atleta ---------------------------------------

    @Test
    fun deleteCascadesChildrenAndReportsExistence() = runBlocking {
        val id = repository.save(draft(sessionKey = "fvp-delete"))

        assertTrue(repository.delete(id))
        assertEquals(0, countRows("fvp_assessments"))
        assertEquals(0, countRows("fvp_conditions"))
        assertEquals(0, countRows("fvp_attempts"))
        assertNull(repository.byId(id))
        assertFalse(repository.delete(id))
    }

    @Test
    fun deleteForAthleteOnlyRemovesOwnedRoots() = runBlocking {
        val mine = repository.save(draft(sessionKey = "fvp-mine"))
        val other = repository.save(
            draft(sessionKey = "fvp-other", athleteId = otherAthleteId),
        )

        dao.deleteForAthlete(athleteId)

        assertNull(repository.byId(mine))
        assertNotNull(repository.byId(other))
        assertEquals(1, countRows("fvp_assessments"))
        assertEquals(3, countRows("fvp_conditions"))
        assertEquals(7, countRows("fvp_attempts"))
    }

    // --- 6. Claves foráneas ----------------------------------------------------------------------

    @Test
    fun unknownAthleteIsRejectedByForeignKeyAndRollsBack() = runBlocking {
        val rawFailure = assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { dao.insertAssessmentRaw(validRecord("fvp-fk-raw").assessment.copy(athleteId = 999L)) }
        }
        assertTrue(rawFailure.message!!.uppercase().contains("FOREIGN KEY"))
        assertEquals(0, countRows("fvp_assessments"))

        val saveFailure = assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                repository.save(draft(sessionKey = "fvp-fk-repo", athleteId = 999L))
            }
        }
        assertTrue(saveFailure.message!!.uppercase().contains("FOREIGN KEY"))
        assertEquals(0, countRows("fvp_assessments"))
        assertEquals(0, countRows("fvp_conditions"))
        assertEquals(0, countRows("fvp_attempts"))
    }

    // --- 7. Notas y orden de los flujos ----------------------------------------------------------

    @Test
    fun updateNotesNormalizesAndValidatesLength() = runBlocking<Unit> {
        val id = repository.save(draft(sessionKey = "fvp-notes", notes = null))

        assertTrue(repository.updateNotes(id, "  hola  "))
        assertEquals("hola", repository.byId(id)!!.assessment.notes)

        assertTrue(repository.updateNotes(id, "   "))
        assertNull(repository.byId(id)!!.assessment.notes)

        assertFalse(repository.updateNotes(9_999L, "x"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.updateNotes(id, "x".repeat(501)) }
        }
        Unit
    }

    @Test
    fun recentAndForAthleteAreSortedByDateTimeThenIdDescending() = runBlocking {
        val earliest = repository.save(draft(sessionKey = "fvp-sort-1", dateTime = 100L))
        val laterFirst = repository.save(draft(sessionKey = "fvp-sort-2", dateTime = 200L))
        val laterSecond = repository.save(draft(sessionKey = "fvp-sort-3", dateTime = 200L))
        val otherAthlete = repository.save(
            draft(sessionKey = "fvp-sort-4", athleteId = otherAthleteId, dateTime = 300L),
        )

        assertEquals(
            listOf(otherAthlete, laterSecond, laterFirst, earliest),
            repository.recent().first().map { it.id },
        )
        val mine = repository.forAthlete(athleteId).first()
        assertEquals(listOf(laterSecond, laterFirst, earliest), mine.map { it.id })
        assertTrue(mine.all { it.athleteId == athleteId })
        assertTrue(repository.forAthlete(999L).first().isEmpty())
    }

    // --- 8. Paridad elegibilidad ↔ persistencia (W1.2) -------------------------------------------

    /**
     * Dirección exigida: si `checkSeries` da la serie por calculable, el camino de persistencia
     * (`FvpRepository.save` -> `FvpDao.save`) la acepta con los MISMOS valores; y si la bloquea, el
     * DAO también rechaza el registro equivalente (fail closed).
     *
     * Los casos de intento INVÁLIDO fuera de dominio (altura NaN, altura > 1.2 m) son los que antes
     * pasaban elegibilidad y morían en el DAO con `IllegalArgumentException` cruda; el caso de carga
     * adicional no finita ya se bloqueaba en elegibilidad y aquí solo fija la alineación. Ninguno de
     * los tres puede volver a pasar elegibilidad, y el DAO sigue rechazando los mismos valores.
     */
    @Test
    fun eligibilityAndDaoAgreeOnAttemptHeightsAndAddedLoads() = runBlocking {
        parityCases().forEach { case ->
            val conditions = case.conditions
            val input = eligibilityInput(conditions)
            val eligibility = checkSeries(input)

            if (eligibility.canCompute) {
                assertEquals(
                    "${case.label}: una serie calculable no debe tener bloqueos (${eligibility.blocking})",
                    emptyList<FvpEligibilityProblem>(),
                    eligibility.blocking,
                )
                val id = repository.save(
                    draft(sessionKey = case.key, notes = null, conditions = conditions),
                )
                assertTrue("${case.label}: el guardado debe aceptar lo que la elegibilidad acepta", id > 0L)
                val stored = requireNotNull(repository.byId(id))
                assertEquals(conditions.size, stored.conditions.size)
                val expectedOrdinals = canonicalSelectedOrdinals(input)
                assertEquals(
                    "${case.label}: el ordinal persistido debe ser el de la regla canónica",
                    expectedOrdinals,
                    stored.conditions.map { it.selectedAttemptOrdinal },
                )
                stored.conditions.forEachIndexed { index, condition ->
                    assertEquals(
                        "${case.label}: la altura persistida debe ser la del intento seleccionado",
                        conditions[index].attempts[expectedOrdinals[index]].heightM,
                        condition.selectedHeightM,
                        0.0,
                    )
                }
            } else {
                assertEquals("${case.label}: problemas", case.expectedBlocking, eligibility.blocking)
                // El repositorio sigue fallando ANTES del DAO, con el diagnóstico de elegibilidad.
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { repository.save(draft(sessionKey = case.key, notes = null, conditions = conditions)) }
                }
                val rowsBefore = countRows("fvp_assessments")
                val failure = assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { dao.save(parityRecord(conditions)) }
                }
                val expectedMessage = requireNotNull(case.expectedDaoMessage) {
                    "${case.label}: falta el mensaje esperado del DAO"
                }
                assertTrue(
                    "${case.label}: el DAO debe rechazar el mismo valor (mensaje: ${failure.message})",
                    failure.message.orEmpty().contains(expectedMessage),
                )
                assertEquals("${case.label}: el rechazo no debe dejar filas", rowsBefore, countRows("fvp_assessments"))
            }
        }

        // Solo los dos casos calculables se persisten; los tres divergentes rebotan sin dejar filas.
        assertEquals(2, countRows("fvp_assessments"))
        assertEquals(8, countRows("fvp_conditions"))
        assertEquals(9, countRows("fvp_attempts"))
    }

    // --- 9. Invariante de signo del perfil (W3.1: sfvNsPerM == -slopeFv) -------------------------

    /**
     * El motor define `sfv = -slope` (`FvpCalculator.fit`): `sfvNsPerM` es la MAGNITUD positiva de
     * la pendiente. El DAO lo exige como regla dura, asi que un par imposible (los dos signos
     * iguales, o magnitudes que no son la misma pendiente) no entra y su rechazo no deja filas. Un
     * par coherente si sobrevive el viaje de ida y vuelta con los dos valores exactos.
     */
    @Test
    fun daoRejectsInconsistentSfvSignAndRoundTripsTheCoherentPair() = runBlocking {
        val base = validRecord("fvp-sign-invalid")
        val impossiblePairs = listOf(
            // Ambos negativos: el par que traian los fixtures antiguos.
            base.copy(assessment = base.assessment.copy(slopeFv = -300.0, sfvNsPerM = -300.0)),
            // Ambos positivos: la pendiente F-V fisica es negativa.
            base.copy(assessment = base.assessment.copy(slopeFv = 300.0, sfvNsPerM = 300.0)),
            // Signos opuestos pero magnitud distinta: tampoco es la misma pendiente.
            base.copy(assessment = base.assessment.copy(slopeFv = -300.0, sfvNsPerM = 299.9)),
        )
        impossiblePairs.forEach { record ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                runBlocking { dao.save(record) }
            }
            assertTrue(
                "el rechazo debe explicar la invariante de signo: ${failure.message}",
                failure.message.orEmpty().contains("sfvNsPerM") &&
                    failure.message.orEmpty().contains("slopeFv"),
            )
            assertEquals(0, countRows("fvp_assessments"))
            assertEquals(0, countRows("fvp_conditions"))
            assertEquals(0, countRows("fvp_attempts"))
        }

        val id = dao.save(base)
        assertTrue(id > 0L)
        val persisted = requireNotNull(dao.assessmentWithData(id)).assessment
        assertEquals(-100.0, persisted.slopeFv, 0.0)
        assertEquals(100.0, persisted.sfvNsPerM, 0.0)
        assertEquals(-persisted.slopeFv, persisted.sfvNsPerM, 0.0)
        assertEquals(2, countRows("fvp_conditions"))
        assertEquals(3, countRows("fvp_attempts"))
    }

    // --- 10. Auditoria del snapshot al recargar (W3.1: MISMATCH y STALE) -------------------------

    /**
     * W3.1: al recargar una fila, [FvpRepository.byId] audita el snapshot persistido contra el
     * recalculo del motor. Un unico campo alterado con la MISMA `algorithmVersion` es `MISMATCH`
     * (`snapshotMatches == false`); una `algorithmVersion` distinta es `STALE` (`null`, no
     * comparable) aunque todos los numeros coincidan. Ninguno de los dos lanza y los hijos quedan
     * intactos: la UI muestra el aviso con los numeros persistidos.
     */
    @Test
    fun reloadAuditsSnapshotAndReportsMismatchAndStaleWithoutThrowing() = runBlocking {
        val canonicalId = repository.save(draft(sessionKey = "fvp-snapshot-canonical"))
        val canonical = requireNotNull(repository.byId(canonicalId))
        assertEquals(FvpSnapshotStatus.MATCHES, canonical.snapshotStatus)
        assertEquals(true, canonical.snapshotMatches)
        val canonicalRow = requireNotNull(dao.assessmentWithData(canonicalId))

        // MISMATCH: misma version del motor, un solo campo del snapshot alterado (+1 N en f0N).
        val mismatchId = insertSnapshotVariant(canonicalRow, "fvp-snapshot-mismatch") { row ->
            row.copy(f0N = row.f0N + 1.0)
        }
        val mismatch = requireNotNull(repository.byId(mismatchId))
        assertEquals(FvpSnapshotStatus.MISMATCH, mismatch.snapshotStatus)
        assertEquals(false, mismatch.snapshotMatches)
        assertEquals(canonicalRow.assessment.f0N + 1.0, mismatch.assessment.f0N, 0.0)

        // STALE: numeros identicos y `algorithmVersion` distinta. La version manda sobre el resto.
        val staleVersion = "fvp-snapshot-version-antigua"
        val staleId = insertSnapshotVariant(canonicalRow, "fvp-snapshot-stale") { row ->
            row.copy(algorithmVersion = staleVersion)
        }
        val stale = requireNotNull(repository.byId(staleId))
        assertEquals(FvpSnapshotStatus.STALE, stale.snapshotStatus)
        assertNull(stale.snapshotMatches)
        assertEquals(staleVersion, stale.assessment.algorithmVersion)
        assertNotEquals(canonicalRow.assessment.algorithmVersion, stale.assessment.algorithmVersion)

        // Las tres raices conviven; las variantes se insertan como copias completas de los hijos.
        assertEquals(3, countRows("fvp_assessments"))
        assertEquals(9, countRows("fvp_conditions"))
        assertEquals(21, countRows("fvp_attempts"))
    }

    // --- 11. Procedencia (B1b-1): ida y vuelta y rechazos ----------------------------------------

    /**
     * B1b-1: las 20 columnas de procedencia viajan del borrador de persistencia a las entidades y
     * vuelven EXACTAS al leer con el DAO. Un segundo borrador que no informa nada queda en
     * `"UNKNOWN"` textual o `NULL` numérico: ni la fecha del test, ni el fps, ni la existencia de
     * un valor numérico de carga rellenan una procedencia.
     */
    @Test
    fun savedProvenanceRoundTripsKnownAndUnknownValues() = runBlocking {
        val knownDraft = provenanceDraft("fvp-provenance-known")
        val knownId = repository.save(knownDraft)
        val known = requireNotNull(dao.assessmentWithData(knownId))

        // Raíz: valores literales y coincidencia 1:1 con el borrador.
        assertEquals(1_600_000_000_000L, known.assessment.acquiredAt)
        assertEquals("SAME_ACQUISITION", known.assessment.acquisitionContextKind)
        assertEquals("acquisition-2024-03-01", known.assessment.acquisitionContextKey)
        assertEquals("WEIGHED", known.assessment.bodyMassMeasurement)
        assertEquals(1_600_000_100_000L, known.assessment.bodyMassMeasuredAt)
        assertEquals(knownDraft.acquiredAt, known.assessment.acquiredAt)
        assertEquals(knownDraft.acquisitionContextKind, known.assessment.acquisitionContextKind)
        assertEquals(knownDraft.acquisitionContextKey, known.assessment.acquisitionContextKey)
        assertEquals(knownDraft.bodyMassMeasurement, known.assessment.bodyMassMeasurement)
        assertEquals(knownDraft.bodyMassMeasuredAt, known.assessment.bodyMassMeasuredAt)

        // Condiciones e intentos: mismos valores que el borrador, emparejados por ordinal.
        val knownConditions = known.conditions.sortedBy { it.condition.ordinal }
        assertEquals(knownDraft.conditions.size, knownConditions.size)
        knownConditions.forEachIndexed { conditionIndex, row ->
            val expectedCondition = knownDraft.conditions[conditionIndex]
            assertEquals(expectedCondition.addedLoadSource, row.condition.addedLoadSource)
            assertEquals(expectedCondition.addedLoadMeasuredAt, row.condition.addedLoadMeasuredAt)
            val attempts = row.attempts.sortedBy { it.ordinal }
            assertEquals(expectedCondition.attempts.size, attempts.size)
            attempts.forEachIndexed { attemptIndex, attempt ->
                val expectedAttempt = expectedCondition.attempts[attemptIndex]
                assertEquals(expectedAttempt.sourceRecordedAt, attempt.sourceRecordedAt)
                assertEquals(expectedAttempt.sourceAthleteId, attempt.sourceAthleteId)
                assertEquals(expectedAttempt.acquiredAt, attempt.acquiredAt)
                assertEquals(expectedAttempt.reuseConfirmedAt, attempt.reuseConfirmedAt)
                assertEquals(expectedAttempt.reuseConfirmationVersion, attempt.reuseConfirmationVersion)
                assertEquals(expectedAttempt.takeoffPtsUs, attempt.takeoffPtsUs)
                assertEquals(expectedAttempt.takeoffPreviousPtsUs, attempt.takeoffPreviousPtsUs)
                assertEquals(expectedAttempt.takeoffNextPtsUs, attempt.takeoffNextPtsUs)
                assertEquals(expectedAttempt.landingPtsUs, attempt.landingPtsUs)
                assertEquals(expectedAttempt.landingPreviousPtsUs, attempt.landingPreviousPtsUs)
                assertEquals(expectedAttempt.landingNextPtsUs, attempt.landingNextPtsUs)
                assertEquals(expectedAttempt.timeMapping, attempt.timeMapping)
                assertEquals(expectedAttempt.timeMappingEvidenceKey, attempt.timeMappingEvidenceKey)
            }
        }
        assertEquals("WEIGHED", knownConditions[0].condition.addedLoadSource)
        assertEquals("NOMINAL", knownConditions[1].condition.addedLoadSource)
        assertEquals(
            FVP_REUSE_CONFIRMATION_VERSION,
            knownConditions[0].attempts.first().reuseConfirmationVersion,
        )
        assertEquals("IDENTITY_VERIFIED", knownConditions[0].attempts.first().timeMapping)

        // Sin informar: UNKNOWN/NULL exactos; la fecha del test (1_000) no se convierte en
        // adquisición ni en pesaje y ningún PTS se deriva del fps.
        val unknownId = repository.save(draft(sessionKey = "fvp-provenance-unknown"))
        val unknown = requireNotNull(dao.assessmentWithData(unknownId))
        assertEquals(1_000L, unknown.assessment.dateTime)
        assertNull(unknown.assessment.acquiredAt)
        assertEquals("UNKNOWN", unknown.assessment.acquisitionContextKind)
        assertNull(unknown.assessment.acquisitionContextKey)
        assertEquals("UNKNOWN", unknown.assessment.bodyMassMeasurement)
        assertNull(unknown.assessment.bodyMassMeasuredAt)
        assertEquals(3, unknown.conditions.size)
        assertEquals(7, unknown.conditions.sumOf { it.attempts.size })
        unknown.conditions.forEach { row ->
            assertEquals("UNKNOWN", row.condition.addedLoadSource)
            assertNull(row.condition.addedLoadMeasuredAt)
            row.attempts.forEach { attempt ->
                assertEquals("UNKNOWN", attempt.timeMapping)
                assertNull(attempt.timeMappingEvidenceKey)
                assertNull(attempt.sourceRecordedAt)
                assertNull(attempt.sourceAthleteId)
                assertNull(attempt.acquiredAt)
                assertNull(attempt.reuseConfirmedAt)
                assertNull(attempt.reuseConfirmationVersion)
                assertNull(attempt.takeoffPtsUs)
                assertNull(attempt.takeoffPreviousPtsUs)
                assertNull(attempt.takeoffNextPtsUs)
                assertNull(attempt.landingPtsUs)
                assertNull(attempt.landingPreviousPtsUs)
                assertNull(attempt.landingNextPtsUs)
            }
        }
    }

    /**
     * B1b-1: cada regla de validación del repositorio rechaza su caso inválido con
     * `IllegalArgumentException` ANTES de persistir (claves desconocidas, rango de reutilización
     * incompleto, PTS desordenados, claves textuales fuera de contrato y timestamps negativos).
     * Ningún caso deja filas.
     */
    @Test
    fun rejectsInvalidProvenanceKeysAndTimestampOrderWithoutPersisting() = runBlocking {
        provenanceInvalidCases().forEach { case ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.save(case.draft) }
            }
            assertTrue(
                "${case.label}: el rechazo debe explicar '${case.expectedMessageToken}'; " +
                    "mensaje: ${failure.message}",
                failure.message.orEmpty().contains(case.expectedMessageToken),
            )
            assertEquals("${case.label}: el rechazo no deja raíces", 0, countRows("fvp_assessments"))
            assertEquals("${case.label}: el rechazo no deja condiciones", 0, countRows("fvp_conditions"))
            assertEquals("${case.label}: el rechazo no deja intentos", 0, countRows("fvp_attempts"))
        }
    }

    /**
     * Contrato 18 §5: borrar el salto/atleta de ORIGEN no altera el snapshot FVP. `fvp_attempts`
     * guarda referencias informativas sin FK hacia el origen, así que la evaluación y su
     * procedencia sobreviven a la baja del registro fuente (sin convertir la referencia en un
     * enlace fiable a otro registro con el mismo id).
     */
    @Test
    fun deletingSourceJumpAndAthleteLeavesProvenanceSnapshotIntact() = runBlocking {
        val jumpId = database.jumpDao().insertAssessment(
            AssessmentEntity(
                sessionKey = "source-jump-deleted",
                athleteId = athleteId,
                protocolId = "SJ",
                dateTime = 1_000L,
                primaryMetricKey = "HEIGHT_CM",
                primaryMetricValue = 30.0,
                primaryMetricUnit = "CENTIMETER",
            ),
        )
        val draft = provenanceDraft("fvp-provenance-source-deleted").let { base ->
            base.copy(
                conditions = base.conditions.map { condition ->
                    condition.copy(
                        attempts = condition.attempts.map { attempt ->
                            attempt.copy(sourceAssessmentId = jumpId, sourceAthleteId = athleteId)
                        },
                    )
                },
            )
        }
        val fvpId = repository.save(draft)
        assertEquals(1, database.jumpDao().deleteAssessmentById(jumpId))

        val reloaded = requireNotNull(dao.assessmentWithData(fvpId))
        val attempts = reloaded.conditions.flatMap { it.attempts }
        assertTrue(attempts.isNotEmpty())
        attempts.forEach { attempt ->
            assertEquals(jumpId, attempt.sourceAssessmentId)
            assertEquals(athleteId, attempt.sourceAthleteId)
        }
        assertEquals(1, countRows("fvp_assessments"))
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
            assertEquals(0, it.count)
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun countRows(table: String): Int =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /**
     * Reinserta una copia de [source] como raiz NUEVA con `insertAssessmentRaw` (el camino que NO
     * valida nada, como una fila manipulada fuera de la app), copiando condiciones e intentos con
     * sus claves resueltas. Fabricar el snapshot persistido es lo unico que permite auditar
     * `MISMATCH`/`STALE`: el camino normal (`repository.save`) siempre escribe un snapshot coherente.
     */
    private suspend fun insertSnapshotVariant(
        source: FvpAssessmentWithData,
        sessionKey: String,
        mutate: (FvpAssessmentEntity) -> FvpAssessmentEntity,
    ): Long {
        val rootId = dao.insertAssessmentRaw(mutate(source.assessment).copy(id = 0L, sessionKey = sessionKey))
        check(rootId > 0L) { "No se pudo insertar la raiz de la variante '$sessionKey'." }
        source.conditions.forEach { row ->
            val conditionId = dao.insertConditionRaw(row.condition.copy(id = 0L, assessmentId = rootId))
            check(conditionId > 0L) { "No se pudo insertar una condicion de la variante '$sessionKey'." }
            row.attempts.forEach { attempt ->
                val attemptId = dao.insertAttemptRaw(attempt.copy(id = 0L, conditionId = conditionId))
                check(attemptId > 0L) { "No se pudo insertar un intento de la variante '$sessionKey'." }
            }
        }
        return rootId
    }

    private fun draft(
        sessionKey: String = "fvp-session-1",
        athleteId: Long = this.athleteId,
        dateTime: Long = 1_000L,
        pushOffMode: String = "COMMON",
        notes: String? = "nota",
        conditions: List<FvpConditionDraft> = validConditions(),
    ): FvpDraft = FvpDraft(
        sessionKey = sessionKey,
        athleteId = athleteId,
        protocol = "CMJ",
        dateTime = dateTime,
        bodyMassKg = 70.0,
        bodyMassSource = "PROFILE",
        pushOffMode = pushOffMode,
        pushOffM = 0.40,
        pushOffSource = "MEASURED",
        pushOffLandmark = "ASIS",
        pushOffLandmarkHeightCm = 98.0,
        pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
        pushOffMeasuredAt = 900L,
        pushOffCrouchCm = 40.0,
        pushOffExtendedCm = 98.0,
        sourceFps = 240.0,
        methodVersion = FVP_METHOD_VERSION,
        notes = notes,
        conditions = conditions,
    )

    /**
     * Borrador válido y calculable con TODA la procedencia informada y coherente (B1b-1): sirve
     * de base a los casos inválidos, que cambian UN solo valor. Valores distintos por condición y
     * por intento para que la ida y vuelta delate cualquier cruce de filas.
     */
    private fun provenanceDraft(sessionKey: String): FvpDraft =
        draft(sessionKey = sessionKey, notes = null).copy(
            acquiredAt = 1_600_000_000_000L,
            acquisitionContextKind = "SAME_ACQUISITION",
            acquisitionContextKey = "acquisition-2024-03-01",
            bodyMassMeasurement = "WEIGHED",
            bodyMassMeasuredAt = 1_600_000_100_000L,
            conditions = validConditions().mapIndexed { conditionIndex, condition ->
                condition.copy(
                    addedLoadSource = listOf("WEIGHED", "NOMINAL", "WEIGHED")[conditionIndex],
                    addedLoadMeasuredAt = 1_600_000_200_000L + conditionIndex,
                    attempts = condition.attempts.mapIndexed { attemptIndex, attempt ->
                        val offset = conditionIndex * 100L + attemptIndex
                        attempt.copy(
                            sourceRecordedAt = 1_600_000_300_000L + offset,
                            sourceAthleteId = 10L + conditionIndex,
                            acquiredAt = 1_600_000_400_000L + offset,
                            reuseConfirmedAt = 1_600_000_500_000L + offset,
                            reuseConfirmationVersion = FVP_REUSE_CONFIRMATION_VERSION,
                            takeoffPreviousPtsUs = 1_600_000_600_000L + offset,
                            takeoffPtsUs = 1_600_000_700_000L + offset,
                            takeoffNextPtsUs = 1_600_000_800_000L + offset,
                            landingPreviousPtsUs = 1_600_000_900_000L + offset,
                            landingPtsUs = 1_600_001_000_000L + offset,
                            landingNextPtsUs = 1_600_001_100_000L + offset,
                            timeMapping = "IDENTITY_VERIFIED",
                            timeMappingEvidenceKey = "evidence-$conditionIndex-$attemptIndex",
                        )
                    },
                )
            },
        )

    /** Caso inválido de procedencia: cambia un solo valor y espera un token del mensaje. */
    private data class ProvenanceInvalidCase(
        val label: String,
        val expectedMessageToken: String,
        val draft: FvpDraft,
    )

    /** Un caso por regla de validación de procedencia del repositorio (B1b-1). */
    private fun provenanceInvalidCases(): List<ProvenanceInvalidCase> {
        fun invalid(index: Int): FvpDraft = provenanceDraft("fvp-provenance-invalid-$index")
        fun invalidCondition(index: Int, mutate: (FvpConditionDraft) -> FvpConditionDraft): FvpDraft =
            invalid(index).withFirstCondition(mutate)
        fun invalidAttempt(index: Int, mutate: (FvpAttemptDraft) -> FvpAttemptDraft): FvpDraft =
            invalid(index).withFirstCondition { it.withFirstAttempt(mutate) }
        return listOf(
            ProvenanceInvalidCase(
                "clave acquisitionContextKind desconocida",
                "acquisitionContextKind",
                invalid(1).copy(acquisitionContextKind = "GUESSED"),
            ),
            ProvenanceInvalidCase(
                "clave bodyMassMeasurement desconocida",
                "bodyMassMeasurement",
                invalid(2).copy(bodyMassMeasurement = "PROFILE"),
            ),
            ProvenanceInvalidCase(
                "clave addedLoadSource desconocida",
                "addedLoadSource",
                invalidCondition(3) { it.copy(addedLoadSource = "GUESSED") },
            ),
            ProvenanceInvalidCase(
                "clave timeMapping desconocida",
                "timeMapping",
                invalidAttempt(4) { it.copy(timeMapping = "VERIFIED") },
            ),
            ProvenanceInvalidCase(
                "versión de reutilización desconocida",
                "reuseConfirmationVersion",
                invalidAttempt(5) { it.copy(reuseConfirmationVersion = "fvp-reuse-2") },
            ),
            ProvenanceInvalidCase(
                "versión de reutilización sin fecha de confirmación",
                "reuseConfirmedAt",
                invalidAttempt(6) { it.copy(reuseConfirmedAt = null) },
            ),
            ProvenanceInvalidCase(
                "fecha de confirmación sin versión de reutilización",
                "reuseConfirmationVersion",
                invalidAttempt(7) { it.copy(reuseConfirmationVersion = null) },
            ),
            ProvenanceInvalidCase(
                "PTS previo al despegue sin PTS de despegue",
                "takeoffPtsUs",
                invalidAttempt(8) { it.copy(takeoffPtsUs = null) },
            ),
            ProvenanceInvalidCase(
                "PTS previo al despegue no estrictamente menor",
                "takeoffPreviousPtsUs",
                invalidAttempt(9) { it.copy(takeoffPreviousPtsUs = it.takeoffPtsUs) },
            ),
            ProvenanceInvalidCase(
                "PTS posterior al despegue no estrictamente mayor",
                "takeoffNextPtsUs",
                invalidAttempt(10) { it.copy(takeoffNextPtsUs = it.takeoffPtsUs) },
            ),
            ProvenanceInvalidCase(
                "PTS de aterrizaje no estrictamente posterior al despegue",
                "landingPtsUs",
                invalidAttempt(11) {
                    it.copy(
                        landingPtsUs = it.takeoffPtsUs,
                        landingPreviousPtsUs = null,
                        landingNextPtsUs = null,
                    )
                },
            ),
            ProvenanceInvalidCase(
                "PTS previo al aterrizaje sin PTS de aterrizaje",
                "landingPtsUs",
                invalidAttempt(12) { it.copy(landingPtsUs = null) },
            ),
            ProvenanceInvalidCase(
                "clave de contexto de más de 128 caracteres",
                "acquisitionContextKey",
                invalid(13).copy(acquisitionContextKey = "c".repeat(129)),
            ),
            ProvenanceInvalidCase(
                "clave de evidencia temporal de más de 128 caracteres",
                "timeMappingEvidenceKey",
                invalidAttempt(14) { it.copy(timeMappingEvidenceKey = "e".repeat(129)) },
            ),
            ProvenanceInvalidCase(
                "clave de contexto vacía",
                "acquisitionContextKey",
                invalid(15).copy(acquisitionContextKey = ""),
            ),
            ProvenanceInvalidCase(
                "SAME_ACQUISITION sin clave de contexto declarada",
                "acquisitionContextKey",
                invalid(21).copy(acquisitionContextKind = "SAME_ACQUISITION", acquisitionContextKey = null),
            ),
            ProvenanceInvalidCase(
                "timestamp acquiredAt negativo",
                "acquiredAt",
                invalid(16).copy(acquiredAt = -1L),
            ),
            ProvenanceInvalidCase(
                "timestamp bodyMassMeasuredAt negativo",
                "bodyMassMeasuredAt",
                invalid(17).copy(bodyMassMeasuredAt = -1L),
            ),
            ProvenanceInvalidCase(
                "timestamp addedLoadMeasuredAt negativo",
                "addedLoadMeasuredAt",
                invalidCondition(18) { it.copy(addedLoadMeasuredAt = -1L) },
            ),
            ProvenanceInvalidCase(
                "timestamp sourceRecordedAt negativo",
                "sourceRecordedAt",
                invalidAttempt(19) { it.copy(sourceRecordedAt = -1L) },
            ),
            ProvenanceInvalidCase(
                "timestamp reuseConfirmedAt negativo",
                "reuseConfirmedAt",
                invalidAttempt(20) { it.copy(reuseConfirmedAt = -1L) },
            ),
            ProvenanceInvalidCase(
                "PTS de despegue negativo",
                "takeoffPtsUs",
                invalidAttempt(21) { it.copy(takeoffPtsUs = -1L) },
            ),
            ProvenanceInvalidCase(
                "sourceAthleteId no positivo",
                "sourceAthleteId",
                invalidAttempt(22) { it.copy(sourceAthleteId = 0L) },
            ),
            ProvenanceInvalidCase(
                "timestamp acquiredAt del intento negativo",
                "acquiredAt",
                invalidAttempt(23) { it.copy(acquiredAt = -1L) },
            ),
            ProvenanceInvalidCase(
                "timeMappingEvidenceKey vacía",
                "timeMappingEvidenceKey",
                invalidAttempt(24) { it.copy(timeMappingEvidenceKey = "") },
            ),
            ProvenanceInvalidCase(
                "PTS posterior al aterrizaje no estrictamente mayor",
                "landingNextPtsUs",
                invalidAttempt(25) { it.copy(landingNextPtsUs = it.landingPtsUs) },
            ),
            ProvenanceInvalidCase(
                "IDENTITY_VERIFIED sin clave de evidencia temporal",
                "timeMappingEvidenceKey",
                invalidAttempt(26) { it.copy(timeMapping = "IDENTITY_VERIFIED", timeMappingEvidenceKey = null) },
            ),
        )
    }

    private fun FvpDraft.withFirstCondition(
        transform: (FvpConditionDraft) -> FvpConditionDraft,
    ): FvpDraft = copy(conditions = listOf(transform(conditions.first())) + conditions.drop(1))

    private fun FvpConditionDraft.withFirstAttempt(
        transform: (FvpAttemptDraft) -> FvpAttemptDraft,
    ): FvpConditionDraft = copy(attempts = listOf(transform(attempts.first())) + attempts.drop(1))

    /** 3 condiciones: el flag FEW_CONDITIONS la marca como diagnóstico, no como perfil. */
    private fun validConditions(): List<FvpConditionDraft> = listOf(
        FvpConditionDraft(
            conditionKey = "0kg",
            addedLoadKg = 0.0,
            attempts = listOf(
                FvpAttemptDraft(heightM = 0.30, method = "FLIGHT_TIME_VIDEO", sourceFps = 240.0),
                FvpAttemptDraft(heightM = 0.32, method = "FLIGHT_TIME_VIDEO", sourceFps = 240.0),
            ),
        ),
        FvpConditionDraft(
            conditionKey = "20kg",
            addedLoadKg = 20.0,
            attempts = listOf(
                FvpAttemptDraft(heightM = 0.24, method = "FLIGHT_TIME_VIDEO", sourceFps = 240.0),
                FvpAttemptDraft(heightM = 0.22, method = "FLIGHT_TIME_VIDEO", sourceFps = 240.0),
            ),
        ),
        FvpConditionDraft(
            conditionKey = "40kg",
            addedLoadKg = 40.0,
            attempts = listOf(
                FvpAttemptDraft(
                    heightM = 0.50,
                    method = "MANUAL",
                    sourceFps = 120.0,
                    valid = false,
                    exclusionReason = "CONTACTO",
                ),
                FvpAttemptDraft(heightM = 0.16, method = "MANUAL", sourceFps = 120.0),
                FvpAttemptDraft(heightM = 0.18, method = "MANUAL", sourceFps = 120.0),
            ),
        ),
    )

    /** 4 condiciones con fps de sobra: único caso publicable como perfil normal. */
    private fun profileEligibleConditions(): List<FvpConditionDraft> = listOf(
        FvpConditionDraft("0kg", 0.0, attempts = listOf(FvpAttemptDraft(0.34, "FLIGHT_TIME_VIDEO", sourceFps = 240.0))),
        FvpConditionDraft("15kg", 15.0, attempts = listOf(FvpAttemptDraft(0.27, "FLIGHT_TIME_VIDEO", sourceFps = 240.0))),
        FvpConditionDraft("30kg", 30.0, attempts = listOf(FvpAttemptDraft(0.20, "FLIGHT_TIME_VIDEO", sourceFps = 240.0))),
        FvpConditionDraft("45kg", 45.0, attempts = listOf(FvpAttemptDraft(0.14, "FLIGHT_TIME_VIDEO", sourceFps = 240.0))),
    )

    /** Caso de la matriz de paridad elegibilidad ↔ persistencia (ver el test de la sección 8). */
    private data class ParityCase(
        val key: String,
        val label: String,
        val conditions: List<FvpConditionDraft>,
        val expectedBlocking: List<FvpEligibilityProblem>,
        val expectedDaoMessage: String? = null,
    )

    private fun parityCases(): List<ParityCase> {
        val valid = profileEligibleConditions()
        return listOf(
            ParityCase(
                key = "fvp-parity-valid-4",
                label = "serie válida de 4 condiciones",
                conditions = valid,
                expectedBlocking = emptyList(),
            ),
            // Intento INVÁLIDO más alto pero DENTRO de dominio: no bloquea y la selección sigue
            // siendo el válido (ordinal 1), igual en elegibilidad y en lo persistido.
            ParityCase(
                key = "fvp-parity-invalid-in-domain",
                label = "intento inválido dentro de dominio",
                conditions = valid.withFirstConditionAttempts(
                    listOf(
                        parityAttempt(0.50, "MANUAL", sourceFps = 120.0, valid = false, exclusionReason = "CONTACTO"),
                        parityAttempt(0.34, "FLIGHT_TIME_VIDEO", sourceFps = 240.0),
                    ),
                ),
                expectedBlocking = emptyList(),
            ),
            ParityCase(
                key = "fvp-parity-invalid-nan-height",
                label = "intento inválido con altura NaN",
                conditions = valid.withFirstConditionAttempts(
                    listOf(
                        parityAttempt(Double.NaN, "MANUAL", sourceFps = 120.0, valid = false, exclusionReason = "CONTACTO"),
                        parityAttempt(0.34, "FLIGHT_TIME_VIDEO", sourceFps = 240.0),
                    ),
                ),
                expectedBlocking = listOf(FvpEligibilityProblem.INVALID_HEIGHT),
                expectedDaoMessage = "heightM",
            ),
            ParityCase(
                key = "fvp-parity-invalid-height-1-3",
                label = "intento inválido con altura 1.3 m",
                conditions = valid.withFirstConditionAttempts(
                    listOf(
                        parityAttempt(1.3, "MANUAL", sourceFps = 120.0, valid = false, exclusionReason = "CONTACTO"),
                        parityAttempt(0.34, "FLIGHT_TIME_VIDEO", sourceFps = 240.0),
                    ),
                ),
                expectedBlocking = listOf(FvpEligibilityProblem.INVALID_HEIGHT),
                expectedDaoMessage = "altura del intento",
            ),
            ParityCase(
                key = "fvp-parity-nan-added-load",
                label = "intento inválido en una condición con carga adicional NaN",
                conditions = valid.withFirstConditionAttempts(
                    listOf(
                        parityAttempt(0.50, "MANUAL", sourceFps = 120.0, valid = false, exclusionReason = "CONTACTO"),
                        parityAttempt(0.34, "FLIGHT_TIME_VIDEO", sourceFps = 240.0),
                    ),
                ).let { listOf(it.first().copy(addedLoadKg = Double.NaN)) + it.drop(1) },
                expectedBlocking = listOf(FvpEligibilityProblem.INVALID_ADDED_LOAD),
                expectedDaoMessage = "addedLoadKg",
            ),
        )
    }

    /** Intento del borrador de PERSISTENCIA con el vocabulario de la matriz de paridad. */
    private fun parityAttempt(
        heightM: Double,
        method: String,
        sourceFps: Double?,
        valid: Boolean = true,
        exclusionReason: String? = null,
    ): FvpAttemptDraft = FvpAttemptDraft(
        heightM = heightM,
        method = method,
        sourceFps = sourceFps,
        valid = valid,
        exclusionReason = exclusionReason,
    )

    private fun List<FvpConditionDraft>.withFirstConditionAttempts(
        attempts: List<FvpAttemptDraft>,
    ): List<FvpConditionDraft> = listOf(first().copy(attempts = attempts)) + drop(1)

    /**
     * Serie de elegibilidad con los MISMOS valores que el borrador de persistencia: la masa total
     * no se aporta (la deriva el guardado) y el h_po común es el del helper [draft] (0.40 m).
     */
    private fun eligibilityInput(conditions: List<FvpConditionDraft>): FvpSeriesInput = FvpSeriesInput(
        protocol = com.openjump.fvp.FvpProtocol.CMJ,
        bodyMassKg = 70.0,
        pushOffMode = EligibilityPushOffMode.COMMON,
        commonPushOffM = 0.40,
        conditions = conditions.map { condition ->
            EligibilityConditionDraft(
                conditionKey = condition.conditionKey,
                addedLoadKg = condition.addedLoadKg,
                pushOffM = condition.pushOffM,
                attempts = condition.attempts.map { attempt ->
                    EligibilityAttemptDraft(
                        heightM = attempt.heightM,
                        method = com.openjump.fvp.FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                        sourceFps = attempt.sourceFps,
                        valid = attempt.valid,
                        exclusionReason = attempt.exclusionReason,
                    )
                },
            )
        },
    )

    /** Ordinal canónico (API de elegibilidad, W1.2) por condición, en orden de entrada. */
    private fun canonicalSelectedOrdinals(input: FvpSeriesInput): List<Int> = input.conditions.map { condition ->
        requireNotNull(input.selectedValidAttemptIndex(condition)) {
            "La condición '${condition.conditionKey}' no tiene intento válido seleccionable."
        }
    }

    /**
     * Registro del DAO con los MISMOS valores que la serie de elegibilidad: clave, carga, masa total
     * derivada, todos los intentos (válidos e inválidos) y el ordinal seleccionado por la regla
     * canónica. Sirve para comprobar que lo que elegibilidad acepta el DAO lo acepta, y viceversa.
     */
    private fun parityRecord(conditions: List<FvpConditionDraft>): PersistableFvpAssessment {
        val input = eligibilityInput(conditions)
        val ordinals = input.conditions.map { condition ->
            input.selectedValidAttemptIndex(condition) ?: 0
        }
        return PersistableFvpAssessment(
            assessment = FvpAssessmentEntity(
                sessionKey = "fvp-parity-dao",
                athleteId = athleteId,
                protocolId = "CMJ",
                dateTime = 10L,
                bodyMassKg = 70.0,
                bodyMassSource = "PROFILE",
                pushOffMode = "COMMON",
                pushOffM = 0.40,
                pushOffSource = "MEASURED",
                algorithmVersion = "test-algorithm",
                methodVersion = "test-method",
                notes = null,
                f0N = 1000.0,
                slopeFv = -100.0,
                sfvNsPerM = 100.0,
                v0Mps = 10.0,
                pmaxW = 2500.0,
                r2 = 0.99,
                conditionCount = conditions.size,
                vMinMps = 1.0,
                vMaxMps = 1.2,
                fMinN = 900.0,
                fMaxN = 1100.0,
                f0RelNPerKg = 14.3,
                pmaxRelWPerKg = 35.7,
                warningsCsv = "",
            ),
            conditions = conditions.mapIndexed { index, condition ->
                FvpConditionEntity(
                    ordinal = index,
                    conditionKey = condition.conditionKey,
                    addedLoadKg = condition.addedLoadKg,
                    totalMassKg = 70.0 + condition.addedLoadKg,
                    pushOffM = 0.40,
                    selectedHeightM = condition.attempts.getOrNull(ordinals[index])?.heightM ?: 0.0,
                    selectedAttemptOrdinal = ordinals[index],
                )
            },
            attempts = conditions.flatMapIndexed { conditionIndex, condition ->
                condition.attempts.mapIndexed { attemptIndex, attempt ->
                    FvpAttemptEntity(
                        conditionId = conditionIndex.toLong(),
                        ordinal = attemptIndex,
                        heightM = attempt.heightM,
                        method = attempt.method,
                        sourceFps = attempt.sourceFps,
                        valid = attempt.valid,
                        exclusionReason = attempt.exclusionReason,
                    )
                }
            },
        )
    }

    private fun validRecord(sessionKey: String = "dao-save-1"): PersistableFvpAssessment =
        PersistableFvpAssessment(
            assessment = FvpAssessmentEntity(
                sessionKey = sessionKey,
                athleteId = athleteId,
                protocolId = "CMJ",
                dateTime = 10L,
                bodyMassKg = 70.0,
                bodyMassSource = "PROFILE",
                pushOffMode = "COMMON",
                pushOffM = 0.40,
                pushOffSource = "MEASURED",
                algorithmVersion = "test-algorithm",
                methodVersion = "test-method",
                notes = null,
                f0N = 1000.0,
                slopeFv = -100.0,
                sfvNsPerM = 100.0,
                v0Mps = 10.0,
                pmaxW = 2500.0,
                r2 = 0.99,
                conditionCount = 2,
                vMinMps = 1.0,
                vMaxMps = 1.2,
                fMinN = 900.0,
                fMaxN = 1100.0,
                f0RelNPerKg = 14.3,
                pmaxRelWPerKg = 35.7,
                warningsCsv = "",
            ),
            conditions = listOf(
                FvpConditionEntity(
                    ordinal = 0,
                    conditionKey = "0kg",
                    addedLoadKg = 0.0,
                    totalMassKg = 70.0,
                    pushOffM = 0.40,
                    selectedHeightM = 0.30,
                    selectedAttemptOrdinal = 0,
                ),
                FvpConditionEntity(
                    ordinal = 1,
                    conditionKey = "20kg",
                    addedLoadKg = 20.0,
                    totalMassKg = 90.0,
                    pushOffM = 0.40,
                    selectedHeightM = 0.24,
                    selectedAttemptOrdinal = 0,
                ),
            ),
            attempts = listOf(
                FvpAttemptEntity(
                    conditionId = 0,
                    ordinal = 0,
                    heightM = 0.30,
                    method = "FLIGHT_TIME_VIDEO",
                    sourceFps = 240.0,
                ),
                FvpAttemptEntity(
                    conditionId = 0,
                    ordinal = 1,
                    heightM = 0.28,
                    method = "FLIGHT_TIME_VIDEO",
                    sourceFps = 240.0,
                ),
                FvpAttemptEntity(
                    conditionId = 1,
                    ordinal = 0,
                    heightM = 0.24,
                    method = "MANUAL",
                    sourceFps = 120.0,
                ),
            ),
        )
}
