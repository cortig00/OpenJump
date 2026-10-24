package com.openjump.app.ui.fvp

import com.openjump.app.data.FVP_METHOD_VERSION
import com.openjump.app.data.FVP_REUSE_CONFIRMATION_VERSION
import com.openjump.app.data.FvpAssessmentEntity
import com.openjump.app.data.FvpAttemptEntity
import com.openjump.app.data.FvpConditionEntity
import com.openjump.app.data.FvpSnapshotStatus
import com.openjump.app.data.StoredFvpAssessment
import com.openjump.app.fvp.FvpEligibility
import com.openjump.app.fvp.FvpTimingQuality
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import com.openjump.fvp.FvpProfile
import com.openjump.fvp.FvpResult
import java.lang.reflect.Modifier
import java.util.Locale
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openjump.app.data.FvpAttemptDraft as PersistAttemptDraft
import com.openjump.app.data.FvpConditionDraft as PersistConditionDraft
import com.openjump.app.data.FvpDraft as PersistDraft

/**
 * Decision D2 del plan 13 (lane A, slice E1b): "duplicar y editar" una serie guardada debe ser una
 * COPIA FIEL. Todo campo persistido que el borrador pueda representar tiene que sobrevivir
 * `storedFvpToDraft` -> borrador -> `toPersistDraftOrNull`, y la masa corporal de la serie NUNCA
 * puede ser sustituida en silencio por el peso ACTUAL del perfil del atleta.
 *
 * Los defectos que fijan estas pruebas (observados en HEAD):
 * - `FvpAttemptDraft` (UI) no tenia `flightTimeS` ni `notes`, asi que el borrador no podia
 *   llevarlos y `toPersistDraftOrNull` los escribia a `null`: volver a guardar una copia BORRABA
 *   los tiempos de vuelo y las notas de los intentos (ambos son columnas persistidas y ambos se
 *   exportan);
 * - `FvpDraft` (UI) no tenia `pushOffLandmarkHeightCm` (columna persistida, exportada y
 *   respaldada): duplicar una fila que la llevaba la borraba en silencio;
 * - el paso 1 y la procedencia de la masa usaban el borrador vivo del PROCESO sin comprobar la
 *   identidad de la serie (atleta + protocolo): la serie NUEVA de B podia heredar la masa de A y
 *   escribirla como `PROFILE` (procedencia falsa);
 * - los 20 campos de PROCEDENCIA de B1a/B1b-1 (adquisicion, pesaje, carga anadida y origen/PTS de
 *   los intentos) existian solo en la capa de persistencia: el borrador de UI y la duplicacion los
 *   descartaban en silencio y ninguna fecha de origen sobrevivia a "duplicar y editar" (B1b-2a).
 */
class FvpDraftMappingTest {

    private val es = Locale.forLanguageTag("es")

    private companion object {
        const val SOURCE_SESSION_KEY = "serie-origen-0001"
        const val SOURCE_ATHLETE_ID = 42L

        /** Atleta B: el paso 1 puede estar en B mientras el borrador vivo sigue siendo el de A. */
        const val OTHER_ATHLETE_ID = 43L
        const val SOURCE_DATE_TIME = 1_700_000_000_000L
        const val SOURCE_PUSH_OFF_MEASURED_AT = 1_699_000_000_000L

        /** Masa de la serie guardada (snapshot). Distinta a proposito del peso ACTUAL del perfil. */
        const val STORED_BODY_MASS_KG = 79.5

        /** Peso del perfil del atleta HOY: no puede sustituir a [STORED_BODY_MASS_KG]. */
        const val CURRENT_PROFILE_WEIGHT_KG = 82.0

        /**
         * Altura del hito anatomico [cm] de la serie guardada: columna PERSISTIDA, exportada y
         * respaldada (`fvp_assessments.pushOffLandmarkHeightCm`). Sin campo en el borrador de UI,
         * volver a guardar la copia la borraba en silencio (misma clase de defecto que
         * `flightTimeS`/`notes` de los intentos).
         */
        const val SOURCE_PUSH_OFF_LANDMARK_HEIGHT_CM = 88.5

        /** FPS minimo entre los intentos SELECCIONADOS (240 en "0kg", 120 en "20kg"). */
        const val STORED_SOURCE_FPS = 120.0

        // --- Procedencia CONOCIDA de la serie guardada (B1b-2a) ---------------------------------
        // Fechas de origen/registro, evidencia PTS y confirmacion de reutilizacion declaradas en la
        // fila de origen. Ninguna coincide con [SOURCE_DATE_TIME] (la fecha de la FILA) ni con
        // "ahora": asi se ve si el mapeo o la duplicacion regenerasen una fecha de procedencia.
        const val SOURCE_ACQUIRED_AT = 1_699_500_000_000L
        const val SOURCE_ACQUISITION_CONTEXT_KIND = "HISTORICAL_COMPOSITE"
        const val SOURCE_ACQUISITION_CONTEXT_KEY = "lote-importado-2026-10-01"
        const val SOURCE_BODY_MASS_MEASUREMENT = "WEIGHED"
        const val SOURCE_BODY_MASS_MEASURED_AT = 1_699_400_000_000L
        const val SOURCE_ADDED_LOAD_SOURCE_ZERO = "NOMINAL"
        const val SOURCE_ADDED_LOAD_MEASURED_AT_ZERO = 1_699_410_000_000L
        const val SOURCE_ADDED_LOAD_SOURCE_TWENTY = "WEIGHED"
        const val SOURCE_ADDED_LOAD_MEASURED_AT_TWENTY = 1_699_420_000_000L
        const val SOURCE_RECORDED_AT = 1_699_300_000_000L
        const val SOURCE_ATTEMPT_ATHLETE_ID = 777L
        const val SOURCE_ATTEMPT_ACQUIRED_AT = 1_699_520_000_000L
        const val SOURCE_REUSE_CONFIRMED_AT = 1_699_600_000_000L
        const val SOURCE_TAKEOFF_PTS_US = 1_000_000L
        const val SOURCE_TAKEOFF_PREVIOUS_PTS_US = 966_667L
        const val SOURCE_TAKEOFF_NEXT_PTS_US = 1_033_333L
        const val SOURCE_LANDING_PTS_US = 1_500_000L
        const val SOURCE_LANDING_PREVIOUS_PTS_US = 1_466_667L
        const val SOURCE_LANDING_NEXT_PTS_US = 1_533_333L
        const val SOURCE_TIME_MAPPING_EVIDENCE_KEY = "evidencia-lote-1"

        /** Intento IMPORTED (20 kg, descartado): snapshot de origen y PTS de despegue, sin mas. */
        const val IMPORTED_RECORDED_AT = 1_699_310_000_000L
        const val IMPORTED_ATHLETE_ID = 888L
        const val IMPORTED_TAKEOFF_PTS_US = 2_000_000L
        const val IMPORTED_TAKEOFF_PREVIOUS_PTS_US = 1_950_000L
        const val IMPORTED_TAKEOFF_NEXT_PTS_US = 2_050_000L

        /** Intento reutilizado y validado (20 kg): confirmacion durable y PTS de aterrizaje. */
        const val REUSED_CONFIRMED_AT = 1_699_700_000_000L
        const val REUSED_LANDING_PTS_US = 2_500_000L
        const val REUSED_LANDING_PREVIOUS_PTS_US = 2_450_000L
        const val REUSED_LANDING_NEXT_PTS_US = 2_550_000L

        // --- Procedencia CONOCIDA de un borrador de UI (captura directa, sin Room) -------------
        // Valores DISTINTOS a los de la serie guardada: una copia cruzada se ve en la asercion.
        const val UI_ACQUIRED_AT = 1_699_800_000_000L
        const val UI_ACQUISITION_CONTEXT_KIND = "SAME_ACQUISITION"
        const val UI_ACQUISITION_CONTEXT_KEY = "captura-directa-2026-10-10"
        const val UI_BODY_MASS_MEASUREMENT = "DECLARED"
        const val UI_BODY_MASS_MEASURED_AT = 1_699_790_000_000L
        const val UI_ADDED_LOAD_SOURCE = "NOMINAL"
        const val UI_ADDED_LOAD_MEASURED_AT = 1_699_795_000_000L
        const val UI_SOURCE_RECORDED_AT = 1_699_700_000_000L
        const val UI_SOURCE_ATHLETE_ID = 909L
        const val UI_ATTEMPT_ACQUIRED_AT = 1_699_805_000_000L
        const val UI_REUSE_CONFIRMED_AT = 1_699_810_000_000L
        const val UI_TAKEOFF_PTS_US = 3_000_000L
        const val UI_TAKEOFF_PREVIOUS_PTS_US = 2_950_000L
        const val UI_TAKEOFF_NEXT_PTS_US = 3_050_000L
        const val UI_LANDING_PTS_US = 3_500_000L
        const val UI_LANDING_PREVIOUS_PTS_US = 3_450_000L
        const val UI_LANDING_NEXT_PTS_US = 3_550_000L
        const val UI_TIME_MAPPING = "IDENTITY_VERIFIED"
        const val UI_TIME_MAPPING_EVIDENCE_KEY = "evidencia-captura-1"

        /** Los 5 campos de procedencia de la raiz (contrato B0/B1b-2a), por nombre declarado. */
        val ROOT_PROVENANCE_FIELDS = setOf(
            "acquiredAt",
            "acquisitionContextKind",
            "acquisitionContextKey",
            "bodyMassMeasurement",
            "bodyMassMeasuredAt",
        )

        /** Los 2 campos de procedencia de la condicion (contrato B0/B1b-2a). */
        val CONDITION_PROVENANCE_FIELDS = setOf("addedLoadSource", "addedLoadMeasuredAt")

        /** Los 13 campos de procedencia del intento (contrato B0/B1b-2a). */
        val ATTEMPT_PROVENANCE_FIELDS = setOf(
            "sourceRecordedAt",
            "sourceAthleteId",
            "acquiredAt",
            "reuseConfirmedAt",
            "reuseConfirmationVersion",
            "takeoffPtsUs",
            "takeoffPreviousPtsUs",
            "takeoffNextPtsUs",
            "landingPtsUs",
            "landingPreviousPtsUs",
            "landingNextPtsUs",
            "timeMapping",
            "timeMappingEvidenceKey",
        )

        /** Nombres de los 20 campos de procedencia (19 unicos: `acquiredAt` esta en raiz e intento). */
        val PROVENANCE_NAMES = ROOT_PROVENANCE_FIELDS + CONDITION_PROVENANCE_FIELDS + ATTEMPT_PROVENANCE_FIELDS

        /**
         * `sessionKey` y `dateTime` NO son representables en el borrador (`FvpDraft` no los tiene):
         * la copia abre una serie NUEVA y con la fecha de ahora, por diseno de la capa de datos.
         * Eso NO autoriza a regenerar ninguna fecha de PROCEDENCIA (`acquiredAt`,
         * `bodyMassMeasuredAt`, `sourceRecordedAt`, `reuseConfirmedAt`): esas se copian 1:1.
         */
        val NOT_CARRIED_BY_DRAFT = setOf("sessionKey", "dateTime")
    }

    // --- Pruebas ------------------------------------------------------------------------------

    /** Round trip completo de una serie con TODAS las columnas nullable informadas. */
    @Test
    fun fullStoredSeriesRoundTripsWithoutLosingAnyRepresentableField() {
        val source = storedSeries()
        val draft = storedFvpToDraft(source)

        val persisted = requireNotNull(draft.toPersistDraftOrNull()) { "la serie guardada es persistible" }
        assertNotEquals(
            "editar una serie guardada abre una serie NUEVA (FvpRepository.save es idempotente por sessionKey)",
            SOURCE_SESSION_KEY,
            persisted.sessionKey,
        )
        assertEquals(SOURCE_SESSION_KEY, source.assessment.sessionKey)

        // Snapshot de masa: se conserva el de la serie, no el peso actual del perfil.
        assertEquals(STORED_BODY_MASS_KG, persisted.bodyMassKg, 0.0)
        assertNotEquals("el peso de hoy no puede sustituir el snapshot", CURRENT_PROFILE_WEIGHT_KG, persisted.bodyMassKg, 0.0)
        assertEquals("PROFILE", persisted.bodyMassSource)

        // Campos de h_po y metadatos, uno a uno.
        assertEquals("PER_CONDITION", persisted.pushOffMode)
        assertEquals(0.42, persisted.pushOffM, 0.0)
        assertEquals("MEASURED", persisted.pushOffSource)
        assertEquals("TROCANTER", persisted.pushOffLandmark)
        assertEquals(
            "la altura del hito (columna persistida y exportada) no puede perderse al guardar la copia",
            SOURCE_PUSH_OFF_LANDMARK_HEIGHT_CM,
            persisted.pushOffLandmarkHeightCm!!,
            0.0,
        )
        assertEquals("LANDMARK_EXTENDED_MINUS_CROUCH", persisted.pushOffProtocol)
        assertEquals(SOURCE_PUSH_OFF_MEASURED_AT, persisted.pushOffMeasuredAt)
        assertEquals(22.5, persisted.pushOffCrouchCm!!, 0.0)
        assertEquals(64.5, persisted.pushOffExtendedCm!!, 0.0)
        assertEquals(FVP_METHOD_VERSION, persisted.methodVersion)
        assertEquals("serie duplicada sin tocar la masa", persisted.notes)

        // Procedencia (B1b-2a): los 20 valores de la fila de origen llegan intactos al borrador de
        // UI (duplicacion) y al de persistencia; la comparacion campo a campo de mas abajo los
        // cubre en TODAS las posiciones.
        assertEquals(SOURCE_ACQUIRED_AT, draft.acquiredAt)
        assertEquals(SOURCE_ACQUISITION_CONTEXT_KIND, draft.acquisitionContextKind)
        assertEquals(SOURCE_ACQUISITION_CONTEXT_KEY, draft.acquisitionContextKey)
        assertEquals(SOURCE_BODY_MASS_MEASUREMENT, draft.bodyMassMeasurement)
        assertEquals(SOURCE_BODY_MASS_MEASURED_AT, draft.bodyMassMeasuredAt)
        assertEquals(
            listOf(SOURCE_ADDED_LOAD_SOURCE_ZERO, SOURCE_ADDED_LOAD_SOURCE_TWENTY),
            draft.conditions.map { it.addedLoadSource },
        )
        assertEquals(SOURCE_ACQUIRED_AT, persisted.acquiredAt)
        assertEquals(SOURCE_ACQUISITION_CONTEXT_KIND, persisted.acquisitionContextKind)
        assertEquals(SOURCE_ACQUISITION_CONTEXT_KEY, persisted.acquisitionContextKey)
        assertEquals(SOURCE_BODY_MASS_MEASUREMENT, persisted.bodyMassMeasurement)
        assertEquals(SOURCE_BODY_MASS_MEASURED_AT, persisted.bodyMassMeasuredAt)
        val sourceProvenanceAttempt = draft.conditions[0].attempts[0]
        assertEquals(SOURCE_RECORDED_AT, sourceProvenanceAttempt.sourceRecordedAt)
        assertEquals(SOURCE_ATTEMPT_ATHLETE_ID, sourceProvenanceAttempt.sourceAthleteId)
        assertEquals(SOURCE_ATTEMPT_ACQUIRED_AT, sourceProvenanceAttempt.acquiredAt)
        assertEquals(SOURCE_REUSE_CONFIRMED_AT, sourceProvenanceAttempt.reuseConfirmedAt)
        assertEquals(FVP_REUSE_CONFIRMATION_VERSION, sourceProvenanceAttempt.reuseConfirmationVersion)
        assertEquals(SOURCE_TAKEOFF_PTS_US, sourceProvenanceAttempt.takeoffPtsUs)
        assertEquals(SOURCE_TAKEOFF_PREVIOUS_PTS_US, sourceProvenanceAttempt.takeoffPreviousPtsUs)
        assertEquals(SOURCE_TAKEOFF_NEXT_PTS_US, sourceProvenanceAttempt.takeoffNextPtsUs)
        assertEquals(SOURCE_LANDING_PTS_US, sourceProvenanceAttempt.landingPtsUs)
        assertEquals(SOURCE_LANDING_PREVIOUS_PTS_US, sourceProvenanceAttempt.landingPreviousPtsUs)
        assertEquals(SOURCE_LANDING_NEXT_PTS_US, sourceProvenanceAttempt.landingNextPtsUs)
        assertEquals("IDENTITY_VERIFIED", sourceProvenanceAttempt.timeMapping)
        assertEquals(SOURCE_TIME_MAPPING_EVIDENCE_KEY, sourceProvenanceAttempt.timeMappingEvidenceKey)

        // Los fps de la serie salen de los intentos SELECCIONADOS: el intento descartado a 30 fps
        // (invalidado) no los contamina.
        assertEquals(STORED_SOURCE_FPS, persisted.sourceFps!!, 0.0)

        // Intentos: tiempos de vuelo y notas (los dos campos que se perdian) + trazabilidad.
        assertEquals(2, persisted.conditions.size)
        assertEquals(listOf("0kg", "20kg"), persisted.conditions.map { it.conditionKey })
        assertEquals(listOf(0.0, 20.0), persisted.conditions.map { it.addedLoadKg })
        assertEquals(listOf(0.42, 0.38), persisted.conditions.map { it.pushOffM })

        val first = persisted.conditions[0].attempts
        assertEquals(2, first.size)
        assertEquals("FLIGHT_TIME_VIDEO", first[0].method)
        assertEquals(0.312, first[0].heightM, 0.0)
        assertEquals("el tiempo de vuelo del intento 1 no puede perderse", 0.5038, first[0].flightTimeS!!, 0.0)
        assertEquals("las notas del intento 1 no pueden perderse", "intento 1 condicion 0kg", first[0].notes)
        assertEquals(240.0, first[0].sourceFps!!, 0.0)
        assertTrue(first[0].valid)
        assertNull(first[0].exclusionReason)
        assertEquals("MANUAL", first[1].method)
        assertEquals(0.271, first[1].heightM, 0.0)
        assertNull("un intento sin tiempo de vuelo guardado sigue sin el", first[1].flightTimeS)
        assertEquals("descartado por aterrizaje", first[1].notes)
        assertEquals(7L, first[1].sourceAssessmentId)
        assertEquals(2, first[1].sourceAttemptOrdinal)
        assertEquals("SJ", first[1].sourceProtocolId)
        assertFalse(first[1].valid)
        assertEquals("aterrizaje fuera de la plataforma", first[1].exclusionReason)

        val second = persisted.conditions[1].attempts
        assertEquals(2, second.size)
        assertEquals("IMPORTED", second[0].method)
        assertEquals(0.4562, second[0].flightTimeS!!, 0.0)
        assertNull("un intento sin notas guardadas sigue sin ellas", second[0].notes)
        assertEquals(30.0, second[0].sourceFps!!, 0.0)
        assertFalse(second[0].valid)
        assertEquals("fps insuficientes", second[0].exclusionReason)
        assertEquals(11L, second[0].sourceAssessmentId)
        assertEquals(0, second[0].sourceAttemptOrdinal)
        assertEquals("FLIGHT_TIME_VIDEO", second[1].method)
        assertEquals(0.287, second[1].heightM, 0.0)
        assertEquals(0.4839, second[1].flightTimeS!!, 0.0)
        assertEquals("intento valido 20kg", second[1].notes)
        assertEquals(120.0, second[1].sourceFps!!, 0.0)
        assertTrue(second[1].valid)
        assertNull(second[1].exclusionReason)

        assertSameCarriedValues(expectedSourceDraft(), persisted)
    }

    /** Sin valor guardado, los dos campos opcionales siguen a `null`: nunca se inventa un valor. */
    @Test
    fun nullFlightTimeAndAttemptNotesStayNull() {
        val source = storedSeries(withAttemptOptionalFields = false)
        val persisted = requireNotNull(storedFvpToDraft(source).toPersistDraftOrNull())

        persisted.conditions.forEachIndexed { conditionIndex, condition ->
            condition.attempts.forEachIndexed { attemptIndex, attempt ->
                val where = "conditions[$conditionIndex].attempts[$attemptIndex]"
                assertNull("$where.flightTimeS", attempt.flightTimeS)
                assertNull("$where.notes", attempt.notes)
            }
        }
        // El resto de la serie sigue siendo una copia fiel.
        assertSameCarriedValues(expectedSourceDraft(withAttemptOptionalFields = false), persisted)
    }

    /**
     * `pushOffLandmarkHeightCm` es una columna PERSISTIDA, exportada y respaldada, pero no tiene
     * campo en el paso 1: el borrador de UI tiene que llevarla igualmente. El fixture la informa a
     * proposito (en HEAD iba a `null` en los DOS lados de la comparacion, asi que la perdida era
     * invisible) y aqui se comprueba tambien el caso sin valor: `null` se queda `null`.
     */
    @Test
    fun landmarkHeightSurvivesTheRoundTripAndStaysNullWhenThereIsNone() {
        val draftWithHeight = storedFvpToDraft(storedSeries())
        assertEquals(
            "el borrador de UI lleva la altura del hito guardada",
            SOURCE_PUSH_OFF_LANDMARK_HEIGHT_CM,
            draftWithHeight.pushOffLandmarkHeightCm!!,
            0.0,
        )
        val persistedWithHeight = requireNotNull(draftWithHeight.toPersistDraftOrNull())
        assertEquals(
            "volver a guardar la serie no puede perder la altura del hito",
            SOURCE_PUSH_OFF_LANDMARK_HEIGHT_CM,
            persistedWithHeight.pushOffLandmarkHeightCm!!,
            0.0,
        )
        assertSameCarriedValues(expectedSourceDraft(), persistedWithHeight)

        val draftWithoutHeight = storedFvpToDraft(storedSeries(pushOffLandmarkHeightCm = null))
        assertNull("sin altura guardada no se inventa una", draftWithoutHeight.pushOffLandmarkHeightCm)
        val persistedWithoutHeight = requireNotNull(draftWithoutHeight.toPersistDraftOrNull())
        assertNull("ni se escribe al volver a guardar", persistedWithoutHeight.pushOffLandmarkHeightCm)
        assertSameCarriedValues(expectedSourceDraft(pushOffLandmarkHeightCm = null), persistedWithoutHeight)
    }

    /** Editar un campo cambia SOLO ese campo; lo no tocado conserva su valor exacto. */
    @Test
    fun editedFieldIsTheOnlyDifferenceAndUntouchedFieldsKeepTheirExactValues() {
        val draft = storedFvpToDraft(storedSeries())
        val edited = draft.copy(
            conditions = draft.conditions.mapIndexed { conditionIndex, condition ->
                if (conditionIndex != 0) {
                    condition
                } else {
                    condition.copy(
                        attempts = condition.attempts.mapIndexed { attemptIndex, attempt ->
                            if (attemptIndex != 0) attempt else attempt.copy(heightM = 0.333)
                        },
                    )
                }
            },
        )

        val before = leaves(requireNotNull(draft.toPersistDraftOrNull()))
        val after = leaves(requireNotNull(edited.toPersistDraftOrNull()))
        assertEquals("mismos campos a comparar", before.keys, after.keys)
        assertEquals(
            "editar una altura no puede mover ningun otro campo conservado",
            listOf("conditions[0].attempts[0].heightM"),
            before.filter { (path, value) -> path !in NOT_CARRIED_BY_DRAFT && after[path] != value }.keys.toList(),
        )

        val editedAttempt = requireNotNull(edited.toPersistDraftOrNull()).conditions[0].attempts[0]
        assertEquals(0.333, editedAttempt.heightM, 0.0)
        assertEquals("el tiempo de vuelo del intento editado se conserva", 0.5038, editedAttempt.flightTimeS!!, 0.0)
        assertEquals("las notas del intento editado se conservan", "intento 1 condicion 0kg", editedAttempt.notes)
    }

    /**
     * Guardia de regresion de D2: la copia guardada otra vez produce EXACTAMENTE los mismos valores
     * persistidos que la fila de origen en TODOS los campos conservados (comparacion campo a campo,
     * no de uno o dos).
     */
    @Test
    fun duplicatedSeriesSavedAgainPersistsTheSameValuesAsTheSource() {
        val source = expectedSourceDraft()
        val duplicated = requireNotNull(storedFvpToDraft(storedSeries()).toPersistDraftOrNull())

        val sourceLeaves = leaves(source)
        val duplicateLeaves = leaves(duplicated)
        assertEquals("los dos borradores deben exponer los mismos campos", sourceLeaves.keys, duplicateLeaves.keys)
        assertNotEquals("sessionKey", sourceLeaves["sessionKey"], duplicateLeaves["sessionKey"])
        assertEquals(
            "ningun campo conservado puede derivar al volver a guardar la copia",
            emptyList<String>(),
            sourceLeaves.keys
                .filter { path -> path !in NOT_CARRIED_BY_DRAFT && sourceLeaves[path] != duplicateLeaves[path] }
                .toList(),
        )
    }

    /**
     * La comparacion campo a campo cubre TODOS los campos declarados de los tres borradores: si
     * manana se anade una columna al borrador, esta prueba lo dice en vez de ignorarla.
     */
    @Test
    fun carriedFieldComparisonCoversEveryPersistedDraftField() {
        val draft = expectedSourceDraft()
        assertEquals(declaredFieldNames(PersistDraft::class), draftFields(draft).keys)

        val condition = draft.conditions.first()
        assertEquals(declaredFieldNames(PersistConditionDraft::class), conditionFields(condition).keys)

        val attempt = condition.attempts.first()
        assertEquals(declaredFieldNames(PersistAttemptDraft::class), attemptFields(attempt).keys)

        assertEquals("2 condiciones x 2 intentos en el fixture", 2, draft.conditions.size)
        assertEquals(2, draft.conditions[0].attempts.size)
        assertEquals(2, draft.conditions[1].attempts.size)
        assertEquals(
            "la lista de hojas cubre raiz + condiciones + intentos",
            23 + 2 * 5 + 4 * 23,
            leaves(draft).size,
        )
    }

    // --- Procedencia (B1b-2a): borrador UI <-> persistencia <-> duplicacion ----------------------

    /**
     * Caso explicito del contrato 18 §2: duplicar una serie con procedencia CONOCIDA conserva las
     * fechas de adquisicion/registro de origen, la evidencia (PTS y mapeo temporal) y la
     * confirmacion de reutilizacion TAL CUAL. NINGUNA fecha de procedencia se regenera con "ahora":
     * la copia no afirma captura ni pesaje nuevos. La `dateTime` de la copia SI es nueva (fila
     * NUEVA, ver [NOT_CARRIED_BY_DRAFT]) y no se usa para derivar procedencia alguna.
     */
    @Test
    fun duplicatingASeriesWithKnownProvenanceKeepsOriginalDatesEvidenceAndConfirmation() {
        val startedAt = System.currentTimeMillis()
        val duplicated = requireNotNull(storedFvpToDraft(storedSeries()).toPersistDraftOrNull())

        // (1) Los 5 valores de la raiz son EXACTAMENTE los de la fila de origen.
        assertEquals(SOURCE_ACQUIRED_AT, duplicated.acquiredAt)
        assertEquals(SOURCE_ACQUISITION_CONTEXT_KIND, duplicated.acquisitionContextKind)
        assertEquals(SOURCE_ACQUISITION_CONTEXT_KEY, duplicated.acquisitionContextKey)
        assertEquals(SOURCE_BODY_MASS_MEASUREMENT, duplicated.bodyMassMeasurement)
        assertEquals(SOURCE_BODY_MASS_MEASURED_AT, duplicated.bodyMassMeasuredAt)

        // (2) Los 2 de cada condicion, en su POSICION (una copia cruzada se veria en la lista).
        assertEquals(
            listOf(SOURCE_ADDED_LOAD_SOURCE_ZERO, SOURCE_ADDED_LOAD_SOURCE_TWENTY),
            duplicated.conditions.map { it.addedLoadSource },
        )
        assertEquals(
            listOf(SOURCE_ADDED_LOAD_MEASURED_AT_ZERO, SOURCE_ADDED_LOAD_MEASURED_AT_TWENTY),
            duplicated.conditions.map { it.addedLoadMeasuredAt },
        )

        // (3) Los 13 del intento con procedencia completa (0 kg, intento 1).
        val attempt = duplicated.conditions[0].attempts[0]
        assertEquals(SOURCE_RECORDED_AT, attempt.sourceRecordedAt)
        assertEquals(SOURCE_ATTEMPT_ATHLETE_ID, attempt.sourceAthleteId)
        assertEquals(SOURCE_ATTEMPT_ACQUIRED_AT, attempt.acquiredAt)
        assertEquals(SOURCE_REUSE_CONFIRMED_AT, attempt.reuseConfirmedAt)
        assertEquals(FVP_REUSE_CONFIRMATION_VERSION, attempt.reuseConfirmationVersion)
        assertEquals(SOURCE_TAKEOFF_PTS_US, attempt.takeoffPtsUs)
        assertEquals(SOURCE_TAKEOFF_PREVIOUS_PTS_US, attempt.takeoffPreviousPtsUs)
        assertEquals(SOURCE_TAKEOFF_NEXT_PTS_US, attempt.takeoffNextPtsUs)
        assertEquals(SOURCE_LANDING_PTS_US, attempt.landingPtsUs)
        assertEquals(SOURCE_LANDING_PREVIOUS_PTS_US, attempt.landingPreviousPtsUs)
        assertEquals(SOURCE_LANDING_NEXT_PTS_US, attempt.landingNextPtsUs)
        assertEquals("IDENTITY_VERIFIED", attempt.timeMapping)
        assertEquals(SOURCE_TIME_MAPPING_EVIDENCE_KEY, attempt.timeMappingEvidenceKey)

        // (4) Ninguna fecha de procedencia sale de "ahora" ni de la fecha de la fila nueva, y la
        //     evidencia (PTS) tampoco se aproxima desde fps ni desde ordinales.
        val provenanceDates = listOfNotNull(
            duplicated.acquiredAt,
            duplicated.bodyMassMeasuredAt,
            attempt.sourceRecordedAt,
            attempt.acquiredAt,
            attempt.reuseConfirmedAt,
        )
        assertEquals(
            listOf(
                SOURCE_ACQUIRED_AT,
                SOURCE_BODY_MASS_MEASURED_AT,
                SOURCE_RECORDED_AT,
                SOURCE_ATTEMPT_ACQUIRED_AT,
                SOURCE_REUSE_CONFIRMED_AT,
            ),
            provenanceDates,
        )
        assertTrue("la copia se fecha al vuelo (es una fila nueva)", duplicated.dateTime >= startedAt)
        assertNotEquals(
            "la copia no reutiliza la fecha de la fila de origen como prueba de captura",
            SOURCE_DATE_TIME,
            duplicated.dateTime,
        )
        assertTrue(
            "ninguna fecha de procedencia puede ser la fecha de la fila nueva ni 'ahora': $provenanceDates",
            provenanceDates.none { it == duplicated.dateTime } && provenanceDates.none { it >= startedAt },
        )

        // (5) El intento SIN procedencia declarada (0 kg, intento 2) sigue desconocido: no hereda la
        //     fecha de la fila, ni la de otro intento, ni promociona su mapeo temporal.
        val undeclared = duplicated.conditions[0].attempts[1]
        assertNull(undeclared.sourceRecordedAt)
        assertNull(undeclared.acquiredAt)
        assertNull(undeclared.reuseConfirmedAt)
        assertNull(undeclared.reuseConfirmationVersion)
        assertEquals("UNKNOWN", undeclared.timeMapping)

        // Y la comparacion campo a campo de TODOS los leaves sigue coincidiendo con la fila origen.
        assertSameCarriedValues(expectedSourceDraft(), duplicated)
    }

    /** Sin procedencia guardada, duplicar NO la inventa: los 20 campos siguen UNKNOWN/NULL. */
    @Test
    fun duplicatingASeriesWithoutProvenanceKeepsEveryFieldUnknownOrNull() {
        val draft = storedFvpToDraft(storedSeries(withProvenance = false))

        // Frontera entidad -> borrador de UI: no se rellena nada (ni desde dateTime, ni desde fps).
        assertNull("acquiredAt", draft.acquiredAt)
        assertEquals("UNKNOWN", draft.acquisitionContextKind)
        assertNull("acquisitionContextKey", draft.acquisitionContextKey)
        assertEquals("UNKNOWN", draft.bodyMassMeasurement)
        assertNull("bodyMassMeasuredAt", draft.bodyMassMeasuredAt)
        draft.conditions.forEachIndexed { conditionIndex, condition ->
            assertEquals("conditions[$conditionIndex].addedLoadSource", "UNKNOWN", condition.addedLoadSource)
            assertNull("conditions[$conditionIndex].addedLoadMeasuredAt", condition.addedLoadMeasuredAt)
            condition.attempts.forEachIndexed { attemptIndex, attempt ->
                val where = "conditions[$conditionIndex].attempts[$attemptIndex]"
                assertNull("$where.sourceRecordedAt", attempt.sourceRecordedAt)
                assertNull("$where.sourceAthleteId", attempt.sourceAthleteId)
                assertNull("$where.acquiredAt", attempt.acquiredAt)
                assertNull("$where.reuseConfirmedAt", attempt.reuseConfirmedAt)
                assertNull("$where.reuseConfirmationVersion", attempt.reuseConfirmationVersion)
                assertNull("$where.takeoffPtsUs", attempt.takeoffPtsUs)
                assertNull("$where.takeoffPreviousPtsUs", attempt.takeoffPreviousPtsUs)
                assertNull("$where.takeoffNextPtsUs", attempt.takeoffNextPtsUs)
                assertNull("$where.landingPtsUs", attempt.landingPtsUs)
                assertNull("$where.landingPreviousPtsUs", attempt.landingPreviousPtsUs)
                assertNull("$where.landingNextPtsUs", attempt.landingNextPtsUs)
                assertEquals("$where.timeMapping", "UNKNOWN", attempt.timeMapping)
                assertNull("$where.timeMappingEvidenceKey", attempt.timeMappingEvidenceKey)
            }
        }

        // Frontera borrador de UI -> persistencia: tampoco se rellena por el camino.
        val duplicated = requireNotNull(draft.toPersistDraftOrNull())
        assertNull(duplicated.acquiredAt)
        assertEquals("UNKNOWN", duplicated.acquisitionContextKind)
        assertNull(duplicated.acquisitionContextKey)
        assertEquals("UNKNOWN", duplicated.bodyMassMeasurement)
        assertNull(duplicated.bodyMassMeasuredAt)
        duplicated.conditions.forEach { condition ->
            assertEquals("UNKNOWN", condition.addedLoadSource)
            assertNull(condition.addedLoadMeasuredAt)
            condition.attempts.forEach { attempt ->
                assertNull(attempt.sourceRecordedAt)
                assertNull(attempt.acquiredAt)
                assertNull(attempt.reuseConfirmationVersion)
                assertNull(attempt.takeoffPtsUs)
                assertNull(attempt.landingPtsUs)
                assertEquals("UNKNOWN", attempt.timeMapping)
            }
        }
        assertSameCarriedValues(expectedSourceDraft(withProvenance = false), duplicated)
    }

    /**
     * Frontera borrador de UI (captura directa, sin pasar por Room) -> persistencia: un borrador con
     * procedencia informada la copia 1:1 en los 20 campos y NO la deriva de la fecha de guardado.
     */
    @Test
    fun uiDraftWithKnownProvenanceIsCopiedOneToOneWithoutDeriving() {
        val startedAt = System.currentTimeMillis()
        val persisted = requireNotNull(uiDraftWithKnownProvenance().toPersistDraftOrNull())

        // Raiz: 5.
        assertEquals(UI_ACQUIRED_AT, persisted.acquiredAt)
        assertEquals(UI_ACQUISITION_CONTEXT_KIND, persisted.acquisitionContextKind)
        assertEquals(UI_ACQUISITION_CONTEXT_KEY, persisted.acquisitionContextKey)
        assertEquals(UI_BODY_MASS_MEASUREMENT, persisted.bodyMassMeasurement)
        assertEquals(UI_BODY_MASS_MEASURED_AT, persisted.bodyMassMeasuredAt)
        // Condicion: 2.
        assertEquals(UI_ADDED_LOAD_SOURCE, persisted.conditions[0].addedLoadSource)
        assertEquals(UI_ADDED_LOAD_MEASURED_AT, persisted.conditions[0].addedLoadMeasuredAt)
        // Intento: 13.
        val attempt = persisted.conditions[0].attempts[0]
        assertEquals(UI_SOURCE_RECORDED_AT, attempt.sourceRecordedAt)
        assertEquals(UI_SOURCE_ATHLETE_ID, attempt.sourceAthleteId)
        assertEquals(UI_ATTEMPT_ACQUIRED_AT, attempt.acquiredAt)
        assertEquals(UI_REUSE_CONFIRMED_AT, attempt.reuseConfirmedAt)
        assertEquals(FVP_REUSE_CONFIRMATION_VERSION, attempt.reuseConfirmationVersion)
        assertEquals(UI_TAKEOFF_PTS_US, attempt.takeoffPtsUs)
        assertEquals(UI_TAKEOFF_PREVIOUS_PTS_US, attempt.takeoffPreviousPtsUs)
        assertEquals(UI_TAKEOFF_NEXT_PTS_US, attempt.takeoffNextPtsUs)
        assertEquals(UI_LANDING_PTS_US, attempt.landingPtsUs)
        assertEquals(UI_LANDING_PREVIOUS_PTS_US, attempt.landingPreviousPtsUs)
        assertEquals(UI_LANDING_NEXT_PTS_US, attempt.landingNextPtsUs)
        assertEquals(UI_TIME_MAPPING, attempt.timeMapping)
        assertEquals(UI_TIME_MAPPING_EVIDENCE_KEY, attempt.timeMappingEvidenceKey)

        // Nada se sustituye por "ahora" ni por la fecha de la fila nueva.
        val provenanceDates = listOfNotNull(
            persisted.acquiredAt,
            persisted.bodyMassMeasuredAt,
            attempt.sourceRecordedAt,
            attempt.acquiredAt,
            attempt.reuseConfirmedAt,
        )
        assertEquals(
            listOf(
                UI_ACQUIRED_AT,
                UI_BODY_MASS_MEASURED_AT,
                UI_SOURCE_RECORDED_AT,
                UI_ATTEMPT_ACQUIRED_AT,
                UI_REUSE_CONFIRMED_AT,
            ),
            provenanceDates,
        )
        assertTrue("la fila nueva se fecha al vuelo", persisted.dateTime >= startedAt)
        assertTrue(
            "ninguna fecha de procedencia sale de la fecha de la fila nueva: $provenanceDates",
            provenanceDates.none { it == persisted.dateTime } && provenanceDates.none { it >= startedAt },
        )
    }

    /** Un borrador de UI que no declara procedencia no la inventa al mapearse a persistencia. */
    @Test
    fun uiDraftWithoutProvenanceKeepsEveryProvenanceFieldUnknownOrNull() {
        val ui = uiDraftWithoutProvenance()
        assertNull(ui.acquiredAt)
        assertEquals("UNKNOWN", ui.acquisitionContextKind)
        assertNull(ui.acquisitionContextKey)
        assertEquals("UNKNOWN", ui.bodyMassMeasurement)
        assertNull(ui.bodyMassMeasuredAt)
        assertEquals("UNKNOWN", ui.conditions[0].addedLoadSource)
        assertNull(ui.conditions[0].addedLoadMeasuredAt)
        assertEquals("UNKNOWN", ui.conditions[0].attempts[0].timeMapping)

        val persisted = requireNotNull(ui.toPersistDraftOrNull())
        assertNull(persisted.acquiredAt)
        assertEquals("UNKNOWN", persisted.acquisitionContextKind)
        assertNull(persisted.acquisitionContextKey)
        assertEquals("UNKNOWN", persisted.bodyMassMeasurement)
        assertNull(persisted.bodyMassMeasuredAt)
        val condition = persisted.conditions[0]
        assertEquals("UNKNOWN", condition.addedLoadSource)
        assertNull(condition.addedLoadMeasuredAt)
        val attempt = condition.attempts[0]
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
        assertEquals("UNKNOWN", attempt.timeMapping)
        assertNull(attempt.timeMappingEvidenceKey)
    }

    /**
     * Guardia de la frontera UI <-> persistencia (contrato B0/B1b-2a): los 20 campos de procedencia
     * existen con el MISMO nombre en las tres clases del borrador de UI y en las tres de
     * persistencia, y los mapas de hojas los recogen todos (nada se compara de menos).
     */
    @Test
    fun provenanceColumnsAreDeclaredWithTheSameNamesInUiAndPersistDrafts() {
        assertEquals(
            "campos de procedencia de la raiz en el borrador de UI",
            ROOT_PROVENANCE_FIELDS,
            declaredFieldNames(FvpDraft::class).intersect(ROOT_PROVENANCE_FIELDS),
        )
        assertEquals(
            "campos de procedencia de la condicion en el borrador de UI",
            CONDITION_PROVENANCE_FIELDS,
            declaredFieldNames(FvpConditionDraft::class).intersect(CONDITION_PROVENANCE_FIELDS),
        )
        assertEquals(
            "campos de procedencia del intento en el borrador de UI",
            ATTEMPT_PROVENANCE_FIELDS,
            declaredFieldNames(FvpAttemptDraft::class).intersect(ATTEMPT_PROVENANCE_FIELDS),
        )
        assertEquals(
            "campos de procedencia de la raiz en el borrador de persistencia",
            ROOT_PROVENANCE_FIELDS,
            declaredFieldNames(PersistDraft::class).intersect(ROOT_PROVENANCE_FIELDS),
        )
        assertEquals(
            "campos de procedencia de la condicion en el borrador de persistencia",
            CONDITION_PROVENANCE_FIELDS,
            declaredFieldNames(PersistConditionDraft::class).intersect(CONDITION_PROVENANCE_FIELDS),
        )
        assertEquals(
            "campos de procedencia del intento en el borrador de persistencia",
            ATTEMPT_PROVENANCE_FIELDS,
            declaredFieldNames(PersistAttemptDraft::class).intersect(ATTEMPT_PROVENANCE_FIELDS),
        )
        assertEquals(
            "los 20 campos del contrato B0",
            20,
            ROOT_PROVENANCE_FIELDS.size + CONDITION_PROVENANCE_FIELDS.size + ATTEMPT_PROVENANCE_FIELDS.size,
        )

        // Y las hojas de la comparacion campo a campo llevan los 20 en TODAS las posiciones
        // (5 raiz + 2 condiciones x 2 + 4 intentos x 13).
        val draft = expectedSourceDraft()
        assertEquals(ROOT_PROVENANCE_FIELDS, draftFields(draft).keys.intersect(ROOT_PROVENANCE_FIELDS))
        assertEquals(
            CONDITION_PROVENANCE_FIELDS,
            conditionFields(draft.conditions[0]).keys.intersect(CONDITION_PROVENANCE_FIELDS),
        )
        assertEquals(
            ATTEMPT_PROVENANCE_FIELDS,
            attemptFields(draft.conditions[0].attempts[0]).keys.intersect(ATTEMPT_PROVENANCE_FIELDS),
        )
        assertEquals(
            "hojas de procedencia comparadas",
            5 + 2 * 2 + 4 * 13,
            leaves(draft).keys.count { it.substringAfterLast('.') in PROVENANCE_NAMES },
        )
    }

    // --- Masa corporal (D2): el snapshot de la serie no puede ser el peso de hoy -----------------

    /**
     * Con un borrador de la MISMA identidad, la sugerencia del perfil NO se aplica y se conserva el
     * valor y la procedencia de origen, aunque el peso actual del perfil haya cambiado (D2).
     */
    @Test
    fun profileMassSuggestionIsNotAppliedToADraftSeededFromAStoredSeries() {
        val draft = storedFvpToDraft(storedSeries())
        assertTrue("la serie de origen lleva masa de perfil", draft.bodyMassFromProfile)
        assertEquals(STORED_BODY_MASS_KG, draft.bodyMassKg!!, 0.0)

        // La semilla de la pantalla solo admite el borrador de ESTA identidad (atleta + protocolo).
        val matchingDraft = draft.seriesSeedFor(SOURCE_ATHLETE_ID, FvpProtocol.SJ)
        assertEquals("el borrador es de la identidad elegida", draft, matchingDraft)

        // El atleta hoy pesa 82,0 kg: la sugerencia existe, pero no puede aplicarse a una copia.
        assertFalse(
            "una serie duplicada no adopta el peso ACTUAL del perfil",
            fvpShouldSuggestProfileMass(
                matchingDraft = matchingDraft,
                suggestedWeightKg = CURRENT_PROFILE_WEIGHT_KG,
                massTouchedByUser = false,
            ),
        )
        assertTrue(
            "la procedencia guardada (PROFILE) sigue siendo PROFILE sin tocar el campo",
            fvpBodyMassProvenanceIsFromProfile(
                matchingDraft = matchingDraft,
                currentProfileMassKg = CURRENT_PROFILE_WEIGHT_KG,
                bodyMassKg = draft.bodyMassKg,
                massTouchedByUser = false,
            ),
        )

        // Regla U1 (sigue vigente): sin re-sembrar, el texto intacto resuelve el canonico EXACTO de
        // la serie guardada, nunca el peso del perfil ni un re-parseo de la forma visible.
        val seededText = formatCanonical(draft.bodyMassKg, MeasurementQuantity.MASS_KG, UnitProfile.METRIC, es)
        assertExactly(
            STORED_BODY_MASS_KG,
            resolveSeededMassKg(seededText, seededText, draft.bodyMassKg, UnitProfile.METRIC, es),
            "masa de la copia sin tocar el campo",
        )

        // Y lo que se guarda lleva el snapshot, no el peso de hoy.
        val persisted = requireNotNull(draft.toPersistDraftOrNull())
        assertEquals(STORED_BODY_MASS_KG, persisted.bodyMassKg, 0.0)
        assertNotEquals(CURRENT_PROFILE_WEIGHT_KG, persisted.bodyMassKg, 0.0)
        assertEquals("PROFILE", persisted.bodyMassSource)
    }

    /** Sin borrador de esta identidad la sugerencia SI se aplica y marca `PROFILE`. */
    @Test
    fun profileMassSuggestionAppliesToABrandNewDraft() {
        assertTrue(
            "una serie nueva sigue sugiriendo el peso del perfil",
            fvpShouldSuggestProfileMass(
                matchingDraft = null,
                suggestedWeightKg = CURRENT_PROFILE_WEIGHT_KG,
                massTouchedByUser = false,
            ),
        )
        assertTrue(
            "la masa sugerida por el perfil se marca como procedente del perfil",
            fvpBodyMassProvenanceIsFromProfile(
                matchingDraft = null,
                currentProfileMassKg = CURRENT_PROFILE_WEIGHT_KG,
                bodyMassKg = CURRENT_PROFILE_WEIGHT_KG,
                massTouchedByUser = false,
            ),
        )

        // La sugerencia re-siembra texto Y semilla a la vez (regla U1): el campo sin tocar resuelve
        // EXACTAMENTE el canonico del perfil, no la forma visible redondeada. Literal de U1: 80 kg
        // en EE. UU. se muestra "176,3698" y su re-parseo pierde precision.
        val suggestedWeightKg = 80.0
        val suggestedText = formatCanonical(
            suggestedWeightKg,
            MeasurementQuantity.MASS_KG,
            UnitProfile.UNITED_STATES,
            es,
        )
        assertEquals("176,3698", suggestedText)
        assertNotEquals(
            "la forma visible esta redondeada: re-parsearla NO da el canonico del perfil",
            80.0,
            MeasurementFormatting.parseCanonical(
                suggestedText,
                MeasurementQuantity.MASS_KG,
                UnitProfile.UNITED_STATES,
                es,
            )!!,
            0.0,
        )
        assertExactly(
            suggestedWeightKg,
            resolveSeededMassKg(
                suggestedText,
                suggestedText,
                suggestedWeightKg,
                UnitProfile.UNITED_STATES,
                es,
            ),
            "masa sugerida re-sembrada",
        )

        // Sin peso de perfil utilizizable no hay sugerencia ni procedencia PROFILE inventada.
        assertFalse(fvpShouldSuggestProfileMass(null, null, false))
        assertFalse("masa 0 no es utilizable", fvpShouldSuggestProfileMass(null, 0.0, false))
        assertFalse("masa no finita no es utilizable", fvpShouldSuggestProfileMass(null, Double.NaN, false))
        assertFalse(
            "sin masa resuelta no se marca PROFILE",
            fvpBodyMassProvenanceIsFromProfile(null, null, null, false),
        )
        assertFalse(
            "un valor que no ES el peso del perfil tampoco se marca PROFILE",
            fvpBodyMassProvenanceIsFromProfile(
                null,
                fvpUsableProfileMassKg(CURRENT_PROFILE_WEIGHT_KG),
                STORED_BODY_MASS_KG,
                false,
            ),
        )
    }

    /**
     * P1 de la revision: un borrador vivo de OTRA serie no puede sembrar ni resolver la masa de la
     * serie en curso. `FvpDraftSession.draft` es POR PROCESO: el borrador de A (masa 79,5 kg,
     * `PROFILE`) puede seguir vivo mientras el paso 1 esta en el atleta B, y sin el recorte de
     * identidad la serie nueva de B se guardaba con esa masa y con `bodyMassSource = PROFILE`, una
     * procedencia FALSA. Con la semilla acotada por identidad, la serie de B se resuelve como serie
     * NUEVA en los dos sub-casos.
     */
    @Test
    fun foreignLiveDraftNeverSuppliesMassNorProvenanceToAnotherSeries() {
        val draftForAthleteA = storedFvpToDraft(storedSeries())
        assertEquals(SOURCE_ATHLETE_ID, draftForAthleteA.athleteId)
        assertEquals(STORED_BODY_MASS_KG, draftForAthleteA.bodyMassKg!!, 0.0)
        assertTrue("el snapshot de A declara PROFILE", draftForAthleteA.bodyMassFromProfile)

        // Otro atleta (misma serie), otro protocolo (mismo atleta) y sin atleta: fuera de identidad.
        val seedForAthleteB = draftForAthleteA.seriesSeedFor(OTHER_ATHLETE_ID, FvpProtocol.SJ)
        assertNull("un borrador de otro atleta no siembra la serie de B", seedForAthleteB)
        assertNull(
            "un borrador de otro protocolo no siembra la serie CMJ",
            draftForAthleteA.seriesSeedFor(SOURCE_ATHLETE_ID, FvpProtocol.CMJ),
        )
        assertNull("sin atleta elegido no hay semilla", draftForAthleteA.seriesSeedFor(null, FvpProtocol.SJ))
        assertEquals(
            "la identidad que SI coincide conserva su semilla (ruta duplicar y editar)",
            draftForAthleteA,
            draftForAthleteA.seriesSeedFor(SOURCE_ATHLETE_ID, FvpProtocol.SJ),
        )
        assertNull("ningun campo de B se siembra del borrador de A (masa)", seedForAthleteB?.bodyMassKg)
        assertNull("ningun campo de B se siembra del borrador de A (h_po)", seedForAthleteB?.commonPushOffM)
        assertNull("ningun campo de B se siembra del borrador de A (hito)", seedForAthleteB?.pushOffLandmark)
        assertNull(
            "ningun campo de B se siembra del borrador de A (altura del hito)",
            seedForAthleteB?.pushOffLandmarkHeightCm,
        )
        assertNull("ningun campo de B se siembra del borrador de A (fecha)", seedForAthleteB?.pushOffMeasuredAt)

        // Sub-caso 1: B tiene un peso de perfil utilizable (82,0 kg).
        val profileMassForB = fvpUsableProfileMassKg(CURRENT_PROFILE_WEIGHT_KG)
        assertEquals(CURRENT_PROFILE_WEIGHT_KG, profileMassForB!!, 0.0)
        assertTrue(
            "sin borrador de B la sugerencia SI aplica",
            fvpShouldSuggestProfileMass(seedForAthleteB, profileMassForB, massTouchedByUser = false),
        )
        assertTrue(
            "el valor sugerido (el peso de B) se marca PROFILE",
            fvpBodyMassProvenanceIsFromProfile(
                matchingDraft = seedForAthleteB,
                currentProfileMassKg = profileMassForB,
                bodyMassKg = profileMassForB,
                massTouchedByUser = false,
            ),
        )
        assertFalse(
            "la masa heredada de A (79,5 kg) NO puede reclamarse como PROFILE de B",
            fvpBodyMassProvenanceIsFromProfile(
                matchingDraft = seedForAthleteB,
                currentProfileMassKg = profileMassForB,
                bodyMassKg = STORED_BODY_MASS_KG,
                massTouchedByUser = false,
            ),
        )

        // Sub-caso 2: B sin peso de perfil utilizable -> ni sugerencia ni PROFILE, ni siquiera con
        // un valor heredado presente.
        val noUsableProfileMass = fvpUsableProfileMassKg(0.0)
        assertNull("0,0 kg no es un peso de perfil utilizable", noUsableProfileMass)
        assertFalse(
            "sin peso utilizable de B no hay sugerencia",
            fvpShouldSuggestProfileMass(seedForAthleteB, noUsableProfileMass, massTouchedByUser = false),
        )
        assertFalse(
            "un valor heredado de A tampoco se marca PROFILE para B",
            fvpBodyMassProvenanceIsFromProfile(
                matchingDraft = seedForAthleteB,
                currentProfileMassKg = noUsableProfileMass,
                bodyMassKg = STORED_BODY_MASS_KG,
                massTouchedByUser = false,
            ),
        )
        assertFalse(
            "sin valor resuelto tampoco hay PROFILE",
            fvpBodyMassProvenanceIsFromProfile(
                matchingDraft = seedForAthleteB,
                currentProfileMassKg = noUsableProfileMass,
                bodyMassKg = null,
                massTouchedByUser = false,
            ),
        )
    }

    /**
     * Identidad que SI coincide (D2): se conservan el valor y la procedencia GUARDADOS, tanto
     * `PROFILE` como `MANUAL`. La procedencia `MANUAL` no se re-deriva a partir del peso de hoy: la
     * serie se guardo asi y asi se vuelve a guardar.
     */
    @Test
    fun matchingDraftKeepsItsStoredProvenanceAndMassForProfileAndManual() {
        val profileDraft = storedFvpToDraft(storedSeries())
        val manualDraft = storedFvpToDraft(storedSeries(bodyMassSource = "MANUAL"))
        assertTrue("la serie de origen declara PROFILE", profileDraft.bodyMassFromProfile)
        assertFalse("la serie de origen declara MANUAL", manualDraft.bodyMassFromProfile)

        val matchingProfile = profileDraft.seriesSeedFor(SOURCE_ATHLETE_ID, FvpProtocol.SJ)
        val matchingManual = manualDraft.seriesSeedFor(SOURCE_ATHLETE_ID, FvpProtocol.SJ)
        assertEquals("PROFILE conserva su valor", STORED_BODY_MASS_KG, matchingProfile?.bodyMassKg!!, 0.0)
        assertEquals("MANUAL conserva su valor", STORED_BODY_MASS_KG, matchingManual?.bodyMassKg!!, 0.0)

        // El perfil hoy pesa 82,0 kg: no sustituye al snapshot ni cambia la procedencia guardada.
        assertTrue(
            "la procedencia PROFILE guardada se conserva",
            fvpBodyMassProvenanceIsFromProfile(
                matchingProfile,
                CURRENT_PROFILE_WEIGHT_KG,
                STORED_BODY_MASS_KG,
                false,
            ),
        )
        assertFalse(
            "la procedencia MANUAL guardada se conserva aunque el valor coincida con el del perfil",
            fvpBodyMassProvenanceIsFromProfile(matchingManual, STORED_BODY_MASS_KG, STORED_BODY_MASS_KG, false),
        )
        assertFalse(
            "tampoco se convierte en PROFILE con el peso de hoy en el campo",
            fvpBodyMassProvenanceIsFromProfile(
                matchingManual,
                CURRENT_PROFILE_WEIGHT_KG,
                CURRENT_PROFILE_WEIGHT_KG,
                false,
            ),
        )
        assertFalse(
            "una copia de la misma identidad tampoco re-siembra la sugerencia",
            fvpShouldSuggestProfileMass(matchingProfile, CURRENT_PROFILE_WEIGHT_KG, massTouchedByUser = false),
        )

        // Y lo que se guarda lleva la procedencia GUARDADA, no una re-derivada de hoy.
        assertEquals("PROFILE", requireNotNull(matchingProfile?.toPersistDraftOrNull()).bodyMassSource)
        assertEquals("MANUAL", requireNotNull(matchingManual?.toPersistDraftOrNull()).bodyMassSource)
        assertEquals(
            "el valor guardado no lo sustituye el peso de hoy",
            STORED_BODY_MASS_KG,
            requireNotNull(matchingProfile?.toPersistDraftOrNull()).bodyMassKg,
            0.0,
        )
    }

    /** Teclear en el campo pasa la procedencia a `MANUAL`, con o sin borrador de la identidad. */
    @Test
    fun typingTheMassFlipsProvenanceToManual() {
        val matchingDraft = storedFvpToDraft(storedSeries()).seriesSeedFor(SOURCE_ATHLETE_ID, FvpProtocol.SJ)
        assertFalse(
            fvpBodyMassProvenanceIsFromProfile(
                matchingDraft = null,
                currentProfileMassKg = CURRENT_PROFILE_WEIGHT_KG,
                bodyMassKg = 82.5,
                massTouchedByUser = true,
            ),
        )
        assertFalse(
            "una serie duplicada tampoco conserva PROFILE si el usuario edita la masa",
            fvpBodyMassProvenanceIsFromProfile(
                matchingDraft = matchingDraft,
                currentProfileMassKg = CURRENT_PROFILE_WEIGHT_KG,
                bodyMassKg = 82.5,
                massTouchedByUser = true,
            ),
        )
        assertFalse(
            "con el campo tocado no se re-siembra la sugerencia",
            fvpShouldSuggestProfileMass(null, CURRENT_PROFILE_WEIGHT_KG, true),
        )
        assertFalse(
            "con el campo tocado y sin borrador tampoco",
            fvpBodyMassProvenanceIsFromProfile(null, CURRENT_PROFILE_WEIGHT_KG, 82.5, true),
        )
    }

    // --- Helpers -------------------------------------------------------------------------------

    /** Igualdad EXACTA (bit a bit) del canonico resuelto: la semilla no admite tolerancias. */
    private fun assertExactly(expected: Double, actual: Double?, message: String) {
        assertTrue(
            "$message: se esperaba exactamente $expected (bits ${expected.toBits()}) pero fue $actual",
            actual != null && expected.toBits() == actual.toBits(),
        )
    }

    /** `sessionKey`/`dateTime` aparte, todo lo conservado debe coincidir literal a literal. */
    private fun assertSameCarriedValues(expected: PersistDraft, actual: PersistDraft) {
        val expectedLeaves = leaves(expected)
        val actualLeaves = leaves(actual)
        assertEquals("mismos campos", expectedLeaves.keys, actualLeaves.keys)
        expectedLeaves.forEach { (path, value) ->
            if (path in NOT_CARRIED_BY_DRAFT) return@forEach
            assertEquals(path, value, actualLeaves[path])
        }
    }

    /**
     * Valor de procedencia CONOCIDO del fixture (`value`) o el desconocido que exige el caso
     * "sin procedencia": `null` para una fecha/PTS, `"UNKNOWN"` para una clave de enum.
     */
    private fun provenanceLong(withProvenance: Boolean, value: Long?): Long? =
        if (withProvenance) value else null

    private fun provenanceText(withProvenance: Boolean, value: String): String =
        if (withProvenance) value else "UNKNOWN"

    /** Clave textual opcional de procedencia: `null` cuando el fixture no declara procedencia. */
    private fun provenanceLongText(withProvenance: Boolean, value: String): String? =
        if (withProvenance) value else null

    /** Ruta completa (`conditions[0].attempts[1].notes`) -> valor, para comparar campo a campo. */
    private fun leaves(draft: PersistDraft): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        draftFields(draft).filterKeys { it != "conditions" }.forEach { (key, value) -> result[key] = value }
        draft.conditions.forEachIndexed { conditionIndex, condition ->
            conditionFields(condition).filterKeys { it != "attempts" }
                .forEach { (key, value) -> result["conditions[$conditionIndex].$key"] = value }
            condition.attempts.forEachIndexed { attemptIndex, attempt ->
                attemptFields(attempt).forEach { (key, value) ->
                    result["conditions[$conditionIndex].attempts[$attemptIndex].$key"] = value
                }
            }
        }
        return result
    }

    private fun draftFields(draft: PersistDraft): Map<String, Any?> = linkedMapOf(
        "sessionKey" to draft.sessionKey,
        "athleteId" to draft.athleteId,
        "protocol" to draft.protocol,
        "dateTime" to draft.dateTime,
        "bodyMassKg" to draft.bodyMassKg,
        "bodyMassSource" to draft.bodyMassSource,
        "pushOffMode" to draft.pushOffMode,
        "pushOffM" to draft.pushOffM,
        "pushOffSource" to draft.pushOffSource,
        "pushOffLandmark" to draft.pushOffLandmark,
        "pushOffLandmarkHeightCm" to draft.pushOffLandmarkHeightCm,
        "pushOffProtocol" to draft.pushOffProtocol,
        "pushOffMeasuredAt" to draft.pushOffMeasuredAt,
        "pushOffCrouchCm" to draft.pushOffCrouchCm,
        "pushOffExtendedCm" to draft.pushOffExtendedCm,
        "sourceFps" to draft.sourceFps,
        "methodVersion" to draft.methodVersion,
        "notes" to draft.notes,
        "conditions" to draft.conditions,
        // Columnas de PROCEDENCIA (B1a Room, B1b-1 persistencia, B1b-2a mapper y duplicacion).
        // La guardia comprueba que cada nombre sigue declarado en el borrador; los tipos los
        // impone el compilador al copiarlas con argumentos nombrados. La captura en formularios
        // (productor real de valores conocidos) llega en B4a.
        "acquiredAt" to draft.acquiredAt,
        "acquisitionContextKind" to draft.acquisitionContextKind,
        "acquisitionContextKey" to draft.acquisitionContextKey,
        "bodyMassMeasurement" to draft.bodyMassMeasurement,
        "bodyMassMeasuredAt" to draft.bodyMassMeasuredAt,
    )

    private fun conditionFields(condition: PersistConditionDraft): Map<String, Any?> = linkedMapOf(
        "conditionKey" to condition.conditionKey,
        "addedLoadKg" to condition.addedLoadKg,
        "pushOffM" to condition.pushOffM,
        "attempts" to condition.attempts,
        // Procedencia de la carga (B1b-1): el mapper y la duplicacion la copian desde B1b-2a.
        "addedLoadSource" to condition.addedLoadSource,
        "addedLoadMeasuredAt" to condition.addedLoadMeasuredAt,
    )

    private fun attemptFields(attempt: PersistAttemptDraft): Map<String, Any?> = linkedMapOf(
        "heightM" to attempt.heightM,
        "method" to attempt.method,
        "flightTimeS" to attempt.flightTimeS,
        "sourceFps" to attempt.sourceFps,
        "sourceAssessmentId" to attempt.sourceAssessmentId,
        "sourceAttemptOrdinal" to attempt.sourceAttemptOrdinal,
        "sourceProtocolId" to attempt.sourceProtocolId,
        "valid" to attempt.valid,
        "exclusionReason" to attempt.exclusionReason,
        "notes" to attempt.notes,
        // Procedencia de origen/reutilizacion y snapshots temporales (B1b-1): el mapper y la
        // duplicacion la copian desde B1b-2a; la captura en formularios llega en B4a.
        "sourceRecordedAt" to attempt.sourceRecordedAt,
        "sourceAthleteId" to attempt.sourceAthleteId,
        "acquiredAt" to attempt.acquiredAt,
        "reuseConfirmedAt" to attempt.reuseConfirmedAt,
        "reuseConfirmationVersion" to attempt.reuseConfirmationVersion,
        "takeoffPtsUs" to attempt.takeoffPtsUs,
        "takeoffPreviousPtsUs" to attempt.takeoffPreviousPtsUs,
        "takeoffNextPtsUs" to attempt.takeoffNextPtsUs,
        "landingPtsUs" to attempt.landingPtsUs,
        "landingPreviousPtsUs" to attempt.landingPreviousPtsUs,
        "landingNextPtsUs" to attempt.landingNextPtsUs,
        "timeMapping" to attempt.timeMapping,
        "timeMappingEvidenceKey" to attempt.timeMappingEvidenceKey,
    )

    private fun declaredFieldNames(type: KClass<*>): Set<String> =
        type.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

    /**
     * Borrador de UI de una captura DIRECTA (no pasa por Room) con los 20 campos de procedencia
     * informados: es el fixture de valores CONOCIDOS de la frontera UI -> persistencia.
     */
    private fun uiDraftWithKnownProvenance(): FvpDraft = FvpDraft(
        sessionKey = "borrador-ui-con-procedencia",
        athleteId = SOURCE_ATHLETE_ID,
        bodyMassKg = STORED_BODY_MASS_KG,
        bodyMassFromProfile = false,
        commonPushOffM = 0.42,
        acquiredAt = UI_ACQUIRED_AT,
        acquisitionContextKind = UI_ACQUISITION_CONTEXT_KIND,
        acquisitionContextKey = UI_ACQUISITION_CONTEXT_KEY,
        bodyMassMeasurement = UI_BODY_MASS_MEASUREMENT,
        bodyMassMeasuredAt = UI_BODY_MASS_MEASURED_AT,
        conditions = listOf(
            FvpConditionDraft(
                conditionKey = "0kg",
                addedLoadKg = 0.0,
                addedLoadSource = UI_ADDED_LOAD_SOURCE,
                addedLoadMeasuredAt = UI_ADDED_LOAD_MEASURED_AT,
                attempts = listOf(
                    FvpAttemptDraft(
                        heightM = 0.312,
                        method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                        flightTimeS = 0.5038,
                        sourceFps = 240.0,
                        sourceRecordedAt = UI_SOURCE_RECORDED_AT,
                        sourceAthleteId = UI_SOURCE_ATHLETE_ID,
                        acquiredAt = UI_ATTEMPT_ACQUIRED_AT,
                        reuseConfirmedAt = UI_REUSE_CONFIRMED_AT,
                        reuseConfirmationVersion = FVP_REUSE_CONFIRMATION_VERSION,
                        takeoffPtsUs = UI_TAKEOFF_PTS_US,
                        takeoffPreviousPtsUs = UI_TAKEOFF_PREVIOUS_PTS_US,
                        takeoffNextPtsUs = UI_TAKEOFF_NEXT_PTS_US,
                        landingPtsUs = UI_LANDING_PTS_US,
                        landingPreviousPtsUs = UI_LANDING_PREVIOUS_PTS_US,
                        landingNextPtsUs = UI_LANDING_NEXT_PTS_US,
                        timeMapping = UI_TIME_MAPPING,
                        timeMappingEvidenceKey = UI_TIME_MAPPING_EVIDENCE_KEY,
                    ),
                ),
            ),
        ),
    )

    /**
     * Borrador de UI que NO declara procedencia (los 20 campos quedan en su default): el contrato
     * exige que sigan siendo desconocidos (UNKNOWN/NULL) hasta el borrador de persistencia.
     */
    private fun uiDraftWithoutProvenance(): FvpDraft = FvpDraft(
        sessionKey = "borrador-ui-sin-procedencia",
        athleteId = SOURCE_ATHLETE_ID,
        bodyMassKg = STORED_BODY_MASS_KG,
        bodyMassFromProfile = false,
        commonPushOffM = 0.42,
        conditions = listOf(
            FvpConditionDraft(
                conditionKey = "0kg",
                addedLoadKg = 0.0,
                attempts = listOf(
                    FvpAttemptDraft(
                        heightM = 0.312,
                        method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                        flightTimeS = 0.5038,
                        sourceFps = 240.0,
                    ),
                ),
            ),
        ),
    )

    /**
     * Borrador de persistencia tal y como quedo GUARDADO en la fila de origen (los literales de
     * [storedSeries]), sin pasar por el mapeo bajo prueba.
     */
    private fun expectedSourceDraft(
        withAttemptOptionalFields: Boolean = true,
        bodyMassSource: String = "PROFILE",
        pushOffLandmarkHeightCm: Double? = SOURCE_PUSH_OFF_LANDMARK_HEIGHT_CM,
        withProvenance: Boolean = true,
    ): PersistDraft = PersistDraft(
        sessionKey = SOURCE_SESSION_KEY,
        athleteId = SOURCE_ATHLETE_ID,
        protocol = "SJ",
        dateTime = SOURCE_DATE_TIME,
        bodyMassKg = STORED_BODY_MASS_KG,
        bodyMassSource = bodyMassSource,
        pushOffMode = "PER_CONDITION",
        pushOffM = 0.42,
        pushOffSource = "MEASURED",
        pushOffLandmark = "TROCANTER",
        pushOffLandmarkHeightCm = pushOffLandmarkHeightCm,
        pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
        pushOffMeasuredAt = SOURCE_PUSH_OFF_MEASURED_AT,
        pushOffCrouchCm = 22.5,
        pushOffExtendedCm = 64.5,
        sourceFps = STORED_SOURCE_FPS,
        methodVersion = FVP_METHOD_VERSION,
        notes = "serie duplicada sin tocar la masa",
        // Procedencia de adquisicion (B1b-2a), literal de la fila de origen.
        acquiredAt = provenanceLong(withProvenance, SOURCE_ACQUIRED_AT),
        acquisitionContextKind = provenanceText(withProvenance, SOURCE_ACQUISITION_CONTEXT_KIND),
        acquisitionContextKey = provenanceLongText(withProvenance, SOURCE_ACQUISITION_CONTEXT_KEY),
        bodyMassMeasurement = provenanceText(withProvenance, SOURCE_BODY_MASS_MEASUREMENT),
        bodyMassMeasuredAt = provenanceLong(withProvenance, SOURCE_BODY_MASS_MEASURED_AT),
        conditions = listOf(
            PersistConditionDraft(
                conditionKey = "0kg",
                addedLoadKg = 0.0,
                pushOffM = 0.42,
                attempts = listOf(
                    PersistAttemptDraft(
                        heightM = 0.312,
                        method = "FLIGHT_TIME_VIDEO",
                        flightTimeS = if (withAttemptOptionalFields) 0.5038 else null,
                        sourceFps = 240.0,
                        sourceAssessmentId = null,
                        sourceAttemptOrdinal = null,
                        sourceProtocolId = null,
                        valid = true,
                        exclusionReason = null,
                        notes = if (withAttemptOptionalFields) "intento 1 condicion 0kg" else null,
                        // Intento con procedencia CONOCIDA completa: fechas, evidencia y reuso.
                        sourceRecordedAt = provenanceLong(withProvenance, SOURCE_RECORDED_AT),
                        sourceAthleteId = provenanceLong(withProvenance, SOURCE_ATTEMPT_ATHLETE_ID),
                        acquiredAt = provenanceLong(withProvenance, SOURCE_ATTEMPT_ACQUIRED_AT),
                        reuseConfirmedAt = provenanceLong(withProvenance, SOURCE_REUSE_CONFIRMED_AT),
                        reuseConfirmationVersion = provenanceLongText(withProvenance, FVP_REUSE_CONFIRMATION_VERSION),
                        takeoffPtsUs = provenanceLong(withProvenance, SOURCE_TAKEOFF_PTS_US),
                        takeoffPreviousPtsUs = provenanceLong(withProvenance, SOURCE_TAKEOFF_PREVIOUS_PTS_US),
                        takeoffNextPtsUs = provenanceLong(withProvenance, SOURCE_TAKEOFF_NEXT_PTS_US),
                        landingPtsUs = provenanceLong(withProvenance, SOURCE_LANDING_PTS_US),
                        landingPreviousPtsUs = provenanceLong(withProvenance, SOURCE_LANDING_PREVIOUS_PTS_US),
                        landingNextPtsUs = provenanceLong(withProvenance, SOURCE_LANDING_NEXT_PTS_US),
                        timeMapping = provenanceText(withProvenance, "IDENTITY_VERIFIED"),
                        timeMappingEvidenceKey = provenanceLongText(withProvenance, SOURCE_TIME_MAPPING_EVIDENCE_KEY),
                    ),
                    PersistAttemptDraft(
                        heightM = 0.271,
                        method = "MANUAL",
                        flightTimeS = null,
                        sourceFps = null,
                        sourceAssessmentId = 7L,
                        sourceAttemptOrdinal = 2,
                        sourceProtocolId = "SJ",
                        valid = false,
                        exclusionReason = "aterrizaje fuera de la plataforma",
                        notes = if (withAttemptOptionalFields) "descartado por aterrizaje" else null,
                        // Sin procedencia declarada: los 13 campos quedan en su default desconocido
                        // dentro del MISMO fixture de valores conocidos.
                    ),
                ),
                addedLoadSource = provenanceText(withProvenance, SOURCE_ADDED_LOAD_SOURCE_ZERO),
                addedLoadMeasuredAt = provenanceLong(withProvenance, SOURCE_ADDED_LOAD_MEASURED_AT_ZERO),
            ),
            PersistConditionDraft(
                conditionKey = "20kg",
                addedLoadKg = 20.0,
                pushOffM = 0.38,
                attempts = listOf(
                    PersistAttemptDraft(
                        heightM = 0.255,
                        method = "IMPORTED",
                        flightTimeS = if (withAttemptOptionalFields) 0.4562 else null,
                        sourceFps = 30.0,
                        sourceAssessmentId = 11L,
                        sourceAttemptOrdinal = 0,
                        sourceProtocolId = "SJ",
                        valid = false,
                        exclusionReason = "fps insuficientes",
                        notes = null,
                        // Origen importado: snapshot y PTS de DESPEGUE, sin captura local ni reuso.
                        sourceRecordedAt = provenanceLong(withProvenance, IMPORTED_RECORDED_AT),
                        sourceAthleteId = provenanceLong(withProvenance, IMPORTED_ATHLETE_ID),
                        takeoffPtsUs = provenanceLong(withProvenance, IMPORTED_TAKEOFF_PTS_US),
                        takeoffPreviousPtsUs = provenanceLong(withProvenance, IMPORTED_TAKEOFF_PREVIOUS_PTS_US),
                        takeoffNextPtsUs = provenanceLong(withProvenance, IMPORTED_TAKEOFF_NEXT_PTS_US),
                    ),
                    PersistAttemptDraft(
                        heightM = 0.287,
                        method = "FLIGHT_TIME_VIDEO",
                        flightTimeS = if (withAttemptOptionalFields) 0.4839 else null,
                        sourceFps = 120.0,
                        sourceAssessmentId = null,
                        sourceAttemptOrdinal = null,
                        sourceProtocolId = null,
                        valid = true,
                        exclusionReason = null,
                        notes = if (withAttemptOptionalFields) "intento valido 20kg" else null,
                        // Reuso confirmado: version+fecha juntos, y PTS de ATERRIZAJE.
                        reuseConfirmedAt = provenanceLong(withProvenance, REUSED_CONFIRMED_AT),
                        reuseConfirmationVersion = provenanceLongText(withProvenance, FVP_REUSE_CONFIRMATION_VERSION),
                        landingPtsUs = provenanceLong(withProvenance, REUSED_LANDING_PTS_US),
                        landingPreviousPtsUs = provenanceLong(withProvenance, REUSED_LANDING_PREVIOUS_PTS_US),
                        landingNextPtsUs = provenanceLong(withProvenance, REUSED_LANDING_NEXT_PTS_US),
                    ),
                ),
                addedLoadSource = provenanceText(withProvenance, SOURCE_ADDED_LOAD_SOURCE_TWENTY),
                addedLoadMeasuredAt = provenanceLong(withProvenance, SOURCE_ADDED_LOAD_MEASURED_AT_TWENTY),
            ),
        ),
    )

    /**
     * Serie GUARDADA de origen: raiz con todas las columnas nullable informadas + 2 condiciones x
     * 2 intentos (validos e invalidados). Los intentos se entregan DESORDENADOS para comprobar que
     * el mapeo los ordena por `ordinal`.
     *
     * Notas de coherencia con la fila real que produce esta app:
     * - `sourceFps` de la raiz = [STORED_SOURCE_FPS] (el app deriva ese valor al guardar);
     * - en `PER_CONDITION` la raiz guarda el h_po de la PRIMERA condicion (0,42);
     * - `methodVersion` es la version actual del flujo;
     * - `testingSessionId` no es representable en el borrador (fuera del alcance de D2) y va a null.
     */
    private fun storedSeries(
        withAttemptOptionalFields: Boolean = true,
        bodyMassSource: String = "PROFILE",
        pushOffLandmarkHeightCm: Double? = SOURCE_PUSH_OFF_LANDMARK_HEIGHT_CM,
        withProvenance: Boolean = true,
    ): StoredFvpAssessment {
        val conditionZero = FvpConditionEntity(
            id = 5010L,
            assessmentId = 501L,
            ordinal = 0,
            conditionKey = "0kg",
            addedLoadKg = 0.0,
            totalMassKg = 79.5,
            pushOffM = 0.42,
            selectedHeightM = 0.312,
            selectedAttemptOrdinal = 0,
            addedLoadSource = provenanceText(withProvenance, SOURCE_ADDED_LOAD_SOURCE_ZERO),
            addedLoadMeasuredAt = provenanceLong(withProvenance, SOURCE_ADDED_LOAD_MEASURED_AT_ZERO),
        )
        val conditionTwenty = FvpConditionEntity(
            id = 5011L,
            assessmentId = 501L,
            ordinal = 1,
            conditionKey = "20kg",
            addedLoadKg = 20.0,
            totalMassKg = 99.5,
            pushOffM = 0.38,
            selectedHeightM = 0.287,
            selectedAttemptOrdinal = 1,
            addedLoadSource = provenanceText(withProvenance, SOURCE_ADDED_LOAD_SOURCE_TWENTY),
            addedLoadMeasuredAt = provenanceLong(withProvenance, SOURCE_ADDED_LOAD_MEASURED_AT_TWENTY),
        )
        val attempts = listOf(
            FvpAttemptEntity(
                id = 6001L,
                conditionId = conditionZero.id,
                ordinal = 1,
                heightM = 0.271,
                method = "MANUAL",
                flightTimeS = null,
                sourceFps = null,
                sourceAssessmentId = 7L,
                sourceAttemptOrdinal = 2,
                sourceProtocolId = "SJ",
                valid = false,
                exclusionReason = "aterrizaje fuera de la plataforma",
                notes = if (withAttemptOptionalFields) "descartado por aterrizaje" else null,
                // Sin procedencia declarada: los 13 campos quedan desconocidos (UNKNOWN/NULL)
                // incluso en el fixture de valores CONOCIDOS, para no cubrir de menos la frontera.
            ),
            FvpAttemptEntity(
                id = 6000L,
                conditionId = conditionZero.id,
                ordinal = 0,
                heightM = 0.312,
                method = "FLIGHT_TIME_VIDEO",
                flightTimeS = if (withAttemptOptionalFields) 0.5038 else null,
                sourceFps = 240.0,
                sourceAssessmentId = null,
                sourceAttemptOrdinal = null,
                sourceProtocolId = null,
                valid = true,
                exclusionReason = null,
                notes = if (withAttemptOptionalFields) "intento 1 condicion 0kg" else null,
                sourceRecordedAt = provenanceLong(withProvenance, SOURCE_RECORDED_AT),
                sourceAthleteId = provenanceLong(withProvenance, SOURCE_ATTEMPT_ATHLETE_ID),
                acquiredAt = provenanceLong(withProvenance, SOURCE_ATTEMPT_ACQUIRED_AT),
                reuseConfirmedAt = provenanceLong(withProvenance, SOURCE_REUSE_CONFIRMED_AT),
                reuseConfirmationVersion = provenanceLongText(withProvenance, FVP_REUSE_CONFIRMATION_VERSION),
                takeoffPtsUs = provenanceLong(withProvenance, SOURCE_TAKEOFF_PTS_US),
                takeoffPreviousPtsUs = provenanceLong(withProvenance, SOURCE_TAKEOFF_PREVIOUS_PTS_US),
                takeoffNextPtsUs = provenanceLong(withProvenance, SOURCE_TAKEOFF_NEXT_PTS_US),
                landingPtsUs = provenanceLong(withProvenance, SOURCE_LANDING_PTS_US),
                landingPreviousPtsUs = provenanceLong(withProvenance, SOURCE_LANDING_PREVIOUS_PTS_US),
                landingNextPtsUs = provenanceLong(withProvenance, SOURCE_LANDING_NEXT_PTS_US),
                timeMapping = provenanceText(withProvenance, "IDENTITY_VERIFIED"),
                timeMappingEvidenceKey = provenanceLongText(withProvenance, SOURCE_TIME_MAPPING_EVIDENCE_KEY),
            ),
            FvpAttemptEntity(
                id = 6010L,
                conditionId = conditionTwenty.id,
                ordinal = 0,
                heightM = 0.255,
                method = "IMPORTED",
                flightTimeS = if (withAttemptOptionalFields) 0.4562 else null,
                sourceFps = 30.0,
                sourceAssessmentId = 11L,
                sourceAttemptOrdinal = 0,
                sourceProtocolId = "SJ",
                valid = false,
                exclusionReason = "fps insuficientes",
                notes = null,
                sourceRecordedAt = provenanceLong(withProvenance, IMPORTED_RECORDED_AT),
                sourceAthleteId = provenanceLong(withProvenance, IMPORTED_ATHLETE_ID),
                takeoffPtsUs = provenanceLong(withProvenance, IMPORTED_TAKEOFF_PTS_US),
                takeoffPreviousPtsUs = provenanceLong(withProvenance, IMPORTED_TAKEOFF_PREVIOUS_PTS_US),
                takeoffNextPtsUs = provenanceLong(withProvenance, IMPORTED_TAKEOFF_NEXT_PTS_US),
            ),
            FvpAttemptEntity(
                id = 6011L,
                conditionId = conditionTwenty.id,
                ordinal = 1,
                heightM = 0.287,
                method = "FLIGHT_TIME_VIDEO",
                flightTimeS = if (withAttemptOptionalFields) 0.4839 else null,
                sourceFps = 120.0,
                sourceAssessmentId = null,
                sourceAttemptOrdinal = null,
                sourceProtocolId = null,
                valid = true,
                exclusionReason = null,
                notes = if (withAttemptOptionalFields) "intento valido 20kg" else null,
                reuseConfirmedAt = provenanceLong(withProvenance, REUSED_CONFIRMED_AT),
                reuseConfirmationVersion = provenanceLongText(withProvenance, FVP_REUSE_CONFIRMATION_VERSION),
                landingPtsUs = provenanceLong(withProvenance, REUSED_LANDING_PTS_US),
                landingPreviousPtsUs = provenanceLong(withProvenance, REUSED_LANDING_PREVIOUS_PTS_US),
                landingNextPtsUs = provenanceLong(withProvenance, REUSED_LANDING_NEXT_PTS_US),
            ),
        )
        return StoredFvpAssessment(
            assessment = FvpAssessmentEntity(
                id = 501L,
                sessionKey = SOURCE_SESSION_KEY,
                athleteId = SOURCE_ATHLETE_ID,
                testingSessionId = null,
                protocolId = "SJ",
                dateTime = SOURCE_DATE_TIME,
                bodyMassKg = STORED_BODY_MASS_KG,
                bodyMassSource = bodyMassSource,
                pushOffMode = "PER_CONDITION",
                pushOffM = 0.42,
                pushOffSource = "MEASURED",
                pushOffLandmark = "TROCANTER",
                pushOffLandmarkHeightCm = pushOffLandmarkHeightCm,
                pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
                pushOffMeasuredAt = SOURCE_PUSH_OFF_MEASURED_AT,
                pushOffCrouchCm = 22.5,
                pushOffExtendedCm = 64.5,
                sourceFps = STORED_SOURCE_FPS,
                algorithmVersion = "fvp-poc-1.0",
                methodVersion = FVP_METHOD_VERSION,
                notes = "serie duplicada sin tocar la masa",
                // Procedencia de adquisicion (B1b-2a), literal de la fila de origen.
                acquiredAt = provenanceLong(withProvenance, SOURCE_ACQUIRED_AT),
                acquisitionContextKind = provenanceText(withProvenance, SOURCE_ACQUISITION_CONTEXT_KIND),
                acquisitionContextKey = provenanceLongText(withProvenance, SOURCE_ACQUISITION_CONTEXT_KEY),
                bodyMassMeasurement = provenanceText(withProvenance, SOURCE_BODY_MASS_MEASUREMENT),
                bodyMassMeasuredAt = provenanceLong(withProvenance, SOURCE_BODY_MASS_MEASURED_AT),
                f0N = 1500.0,
                slopeFv = -700.0,
                sfvNsPerM = 700.0,
                v0Mps = 2.14,
                pmaxW = 803.0,
                r2 = 0.96,
                conditionCount = 2,
                vMinMps = 0.8,
                vMaxMps = 1.4,
                fMinN = 1200.0,
                fMaxN = 1700.0,
                f0RelNPerKg = 1500.0 / STORED_BODY_MASS_KG,
                pmaxRelWPerKg = 803.0 / STORED_BODY_MASS_KG,
                warningsCsv = "",
            ),
            conditions = listOf(conditionTwenty, conditionZero),
            attempts = attempts,
            eligibility = FvpEligibility(
                conditionCount = 2,
                blocking = emptyList(),
                flags = emptyList(),
                timingQuality = FvpTimingQuality.CONDITIONAL,
            ),
            result = FvpResult(
                evaluationId = SOURCE_SESSION_KEY,
                protocol = FvpProtocol.SJ,
                algorithmVersion = "fvp-poc-1.0",
                points = emptyList(),
                profile = FvpProfile(
                    f0N = 1500.0,
                    slopeFv = -700.0,
                    sfvNsPerM = 700.0,
                    v0Mps = 2.14,
                    pmaxW = 803.0,
                    r2 = 0.96,
                    n = 2,
                    vMinMps = 0.8,
                    vMaxMps = 1.4,
                    fMinN = 1200.0,
                    fMaxN = 1700.0,
                    f0RelNPerKg = 1500.0 / STORED_BODY_MASS_KG,
                    pmaxRelWPerKg = 803.0 / STORED_BODY_MASS_KG,
                    warnings = emptyList(),
                ),
            ),
            snapshotStatus = FvpSnapshotStatus.MATCHES,
        )
    }
}
