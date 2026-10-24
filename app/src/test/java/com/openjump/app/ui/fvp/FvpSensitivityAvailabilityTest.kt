package com.openjump.app.ui.fvp

import com.openjump.app.data.FvpAssessmentEntity
import com.openjump.app.data.FvpAttemptEntity
import com.openjump.app.data.FvpConditionEntity
import com.openjump.app.data.FvpSnapshotStatus
import com.openjump.app.data.StoredFvpAssessment
import com.openjump.app.fvp.FvpEngine
import com.openjump.app.fvp.FvpSensitivityAvailability
import com.openjump.app.fvp.FvpTimingQuality
import com.openjump.app.fvp.fvpSensitivityAvailability
import com.openjump.fvp.FvpConditionInput
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.GRAVITY
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice T1b (plan 13): la sensibilidad temporal ante el error de marcado del video se CALCULA en
 * `:fvp`, pero la app solo publica su DISPONIBILIDAD. G-SCIENCE sigue abierto (plan 13, lineas
 * 443-446), de modo que aqui se fija:
 *
 * 1. la decision de disponibilidad y su PRECEDENCIA (sin recalculo > falta vuelo > falta fps);
 * 2. que una sola carencia anula la banda entera (nunca un resultado parcial por condicion);
 * 3. que los insumos salen del intento SELECCIONADO de cada condicion, en el orden del motor;
 * 4. que el modelo de presentacion expone el estado (y no ningun rango).
 *
 * Inputs SINTETICOS y JVM puro: sin Android ni emulador.
 */
class FvpSensitivityAvailabilityTest {

    private val bodyMassKg = 80.0

    private fun syntheticSeries(): List<FvpConditionInput> = listOf(
        FvpConditionInput("load-00", 80.0, 0.35, 0.40),
        FvpConditionInput("load-20", 100.0, 0.30, 0.40),
        FvpConditionInput("load-40", 120.0, 0.25, 0.40),
        FvpConditionInput("load-60", 140.0, 0.20, 0.40),
    )

    /** Tiempo de vuelo coherente con la altura declarada: t = sqrt(8h/g). */
    private fun flightsOf(conditions: List<FvpConditionInput>): List<Double?> =
        conditions.map { sqrt(8.0 * it.heightM / GRAVITY) }

    private fun presenterModel(
        flightTimesS: List<Double?>,
        fpsPerCondition: List<Double?>,
        hasRecalculation: Boolean,
    ): FvpResultUiModel = FvpPresenter.present(
        result = null,
        eligibility = null,
        protocol = null,
        bodyMassKg = null,
        conditionCount = 0,
        timingQuality = FvpTimingQuality.UNKNOWN,
        flightTimesS = flightTimesS,
        fpsPerCondition = fpsPerCondition,
        hasRecalculation = hasRecalculation,
    )

    // --- Decision de disponibilidad -----------------------------------------------------------------

    @Test
    fun `every input declared and a recalculation available is gated but never shown`() {
        assertEquals(
            FvpSensitivityAvailability.AVAILABLE_GATED,
            fvpSensitivityAvailability(
                flightTimesS = listOf(0.534, 0.501, 0.478, 0.452),
                fpsPerCondition = listOf(240.0, 240.0, 120.0, 90.0),
                hasRecalculation = true,
            ),
        )
    }

    @Test
    fun `no recalculation wins over any missing input`() {
        // Precedencia 1: sin ejecucion calculada no hay serie de la que partir, asi que las carencias
        // de vuelo/fps describirian insumos que nadie consumiria.
        assertEquals(
            FvpSensitivityAvailability.NO_RECALCULATION,
            fvpSensitivityAvailability(listOf(0.534, null), listOf(null, null), hasRecalculation = false),
        )
        assertEquals(
            FvpSensitivityAvailability.NO_RECALCULATION,
            fvpSensitivityAvailability(emptyList(), emptyList(), hasRecalculation = false),
        )
    }

    @Test
    fun `missing flight time wins over missing fps`() {
        // Precedencia 2: el vuelo es el primer eslabon (h = g·t²/8); sin el no hay ni altura que
        // perturbar, mientras que la fps solo hace falta para PERTURBAR esa altura.
        assertEquals(
            FvpSensitivityAvailability.MISSING_FLIGHT_TIME,
            fvpSensitivityAvailability(listOf(0.534, null), listOf(null, null), hasRecalculation = true),
        )
    }

    @Test
    fun `missing fps is reported when every flight time is usable`() {
        assertEquals(
            FvpSensitivityAvailability.MISSING_FPS,
            fvpSensitivityAvailability(listOf(0.534, 0.501), listOf(240.0, null), hasRecalculation = true),
        )
        assertEquals(
            FvpSensitivityAvailability.MISSING_FPS,
            fvpSensitivityAvailability(listOf(0.534, 0.501), listOf(240.0, 0.0), hasRecalculation = true),
        )
    }

    @Test
    fun `unusable values count as missing and never as data`() {
        // Un 0.0, un NaN o un infinito no son "entrada" para el envelope: no hay fps por defecto ni
        // vuelo de relleno, de modo que el motivo es el mismo que con `null`.
        val unusable = listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, -0.5)
        unusable.forEach { bad ->
            assertEquals(
                "vuelo inutilizable: $bad",
                FvpSensitivityAvailability.MISSING_FLIGHT_TIME,
                fvpSensitivityAvailability(listOf(bad), listOf(240.0), hasRecalculation = true),
            )
            assertEquals(
                "fps inutilizable: $bad",
                FvpSensitivityAvailability.MISSING_FPS,
                fvpSensitivityAvailability(listOf(0.534), listOf(bad), hasRecalculation = true),
            )
        }
    }

    @Test
    fun `a position without an entry counts as a missing input`() {
        // Listas desalineadas: la posicion sin entrada se trata como insumo que falta (fail closed),
        // de modo que un tamano dispar NUNCA puede declarar disponibilidad.
        assertEquals(
            FvpSensitivityAvailability.MISSING_FLIGHT_TIME,
            fvpSensitivityAvailability(listOf(0.534), listOf(240.0, 240.0), hasRecalculation = true),
        )
        assertEquals(
            FvpSensitivityAvailability.MISSING_FPS,
            fvpSensitivityAvailability(listOf(0.534, 0.501), listOf(240.0), hasRecalculation = true),
        )
    }

    @Test
    fun `an empty series with a recalculation fails closed instead of declaring availability`() {
        // Invariante del llamante: una ejecucion calculada tiene >= MIN_CONDITIONS condiciones, asi
        // que listas vacias con recalculo son un incumplimiento del contrato. Se falla en cerrado.
        assertEquals(
            FvpSensitivityAvailability.MISSING_FLIGHT_TIME,
            fvpSensitivityAvailability(emptyList(), emptyList(), hasRecalculation = true),
        )
    }

    // --- Adaptador (unica puerta app-side a la envolvente) ------------------------------------------

    @Test
    fun `adapter nulls the whole call when a single selected input is missing`() {
        val conditions = syntheticSeries()
        val flights = flightsOf(conditions)
        val fps: List<Double?> = List(conditions.size) { 240.0 }

        assertNotNull(FvpEngine.sensitivityBand(conditions, flights, fps, bodyMassKg))

        // Una sola carencia (en cualquier posicion, en cualquiera de las dos listas o de tamano)
        // devuelve null: sin banda parcial, sin fps por defecto y sin vuelo heredado de otro intento.
        conditions.indices.forEach { index ->
            assertNull(
                "vuelo nulo en $index",
                FvpEngine.sensitivityBand(
                    conditions,
                    flights.mapIndexed { i, value -> if (i == index) null else value },
                    fps,
                    bodyMassKg,
                ),
            )
            assertNull(
                "fps nula en $index",
                FvpEngine.sensitivityBand(
                    conditions,
                    flights,
                    fps.mapIndexed { i, value -> if (i == index) null else value },
                    bodyMassKg,
                ),
            )
        }
        assertNull(FvpEngine.sensitivityBand(conditions, flights.dropLast(1), fps, bodyMassKg))
        assertNull(FvpEngine.sensitivityBand(conditions, flights, fps.dropLast(1), bodyMassKg))
        assertNull(FvpEngine.sensitivityBand(conditions, flights, fps + 240.0, bodyMassKg))
        assertNull(FvpEngine.sensitivityBand(conditions, flights + 0.5, fps, bodyMassKg))
        assertNull(FvpEngine.sensitivityBand(conditions, flightsOf(conditions).map { 0.0 }, fps, bodyMassKg))
        assertNull(FvpEngine.sensitivityBand(conditions, flights, fps.map { Double.NaN }, bodyMassKg))
        // Menos de MIN_CONDITIONS condiciones: el envelope no tiene banda que calcular.
        assertNull(FvpEngine.sensitivityBand(conditions.take(1), flights.take(1), fps.take(1), bodyMassKg))
        assertNull(FvpEngine.sensitivityBand(emptyList(), emptyList(), emptyList(), bodyMassKg))
    }

    @Test
    fun `adapter returns the envelope only with every selected input declared`() {
        val conditions = syntheticSeries()
        val flights = flightsOf(conditions)
        val fps: List<Double?> = List(conditions.size) { 240.0 }

        val band = requireNotNull(FvpEngine.sensitivityBand(conditions, flights, fps, bodyMassKg))

        // Contabilidad del motor: 4 variantes por condicion, ni perdidas ni inventadas.
        assertEquals(4 * conditions.size, band.variantsEvaluated + band.variantsSkipped)
        assertTrue(band.method.contains("nCond=${conditions.size}"))
        assertTrue(band.f0MinN <= band.f0MaxN)
        assertTrue(band.sfvMinNsPerM <= band.sfvMaxNsPerM)
        assertTrue(band.variantsEvaluated > 0)
    }

    // --- Modelo de presentacion ---------------------------------------------------------------------

    @Test
    fun `presenter exposes the availability and never a range`() {
        assertEquals(
            FvpSensitivityAvailability.AVAILABLE_GATED,
            presenterModel(
                flightTimesS = listOf(0.534, 0.501),
                fpsPerCondition = listOf(240.0, 240.0),
                hasRecalculation = true,
            ).sensitivityAvailability,
        )
        assertEquals(
            FvpSensitivityAvailability.MISSING_FLIGHT_TIME,
            presenterModel(
                flightTimesS = listOf(0.534, null),
                fpsPerCondition = listOf(240.0, 240.0),
                hasRecalculation = true,
            ).sensitivityAvailability,
        )
        assertEquals(
            FvpSensitivityAvailability.MISSING_FPS,
            presenterModel(
                flightTimesS = listOf(0.534, 0.501),
                fpsPerCondition = listOf(240.0, null),
                hasRecalculation = true,
            ).sensitivityAvailability,
        )
        // Sin insumos y sin recalculo (los valores por defecto de `present`) no se declara nada
        // disponible: el modelo no puede afirmar una envolvente que nadie le dio.
        assertEquals(
            FvpSensitivityAvailability.NO_RECALCULATION,
            presenterModel(emptyList(), emptyList(), hasRecalculation = false).sensitivityAvailability,
        )
    }

    // --- Insumos del intento seleccionado (vuelo + fps) ---------------------------------------------

    @Test
    fun `draft inputs use the selected valid attempt and the engine order`() {
        val draft = FvpDraft(
            bodyMassKg = bodyMassKg,
            pushOffMode = FvpPushOffUiMode.COMMON,
            commonPushOffM = 0.40,
            // Desordenadas a proposito: el motor recibe las condiciones por `conditionKey` ascendente.
            conditions = listOf(
                FvpConditionDraft(
                    conditionKey = "load-20",
                    addedLoadKg = 20.0,
                    attempts = listOf(FvpAttemptDraft(heightM = 0.30, flightTimeS = 0.495, sourceFps = 240.0)),
                ),
                FvpConditionDraft(
                    conditionKey = "load-0",
                    addedLoadKg = 0.0,
                    attempts = listOf(
                        // El intento DESCARTADO (invalido) es mas alto y trae otros datos: no puede
                        // contaminar la serie ni los insumos de sensibilidad.
                        FvpAttemptDraft(
                            heightM = 0.50,
                            flightTimeS = 0.60,
                            sourceFps = 60.0,
                            valid = false,
                            exclusionReason = "contramovimiento",
                        ),
                        FvpAttemptDraft(heightM = 0.30, flightTimeS = 0.49, sourceFps = 120.0),
                    ),
                ),
            ),
        )

        val inputs = draft.selectedSensitivityInputs()

        assertEquals(listOf(0.49, 0.495), inputs.flightTimesS)
        assertEquals(listOf(120.0, 240.0), inputs.fpsPerCondition)
        assertEquals(
            FvpSensitivityAvailability.AVAILABLE_GATED,
            fvpSensitivityAvailability(inputs.flightTimesS, inputs.fpsPerCondition, hasRecalculation = true),
        )
    }

    @Test
    fun `draft without a declared flight time reports the missing input`() {
        val draft = FvpDraft(
            bodyMassKg = bodyMassKg,
            pushOffMode = FvpPushOffUiMode.COMMON,
            commonPushOffM = 0.40,
            conditions = listOf(
                // Medicion manual: sin vuelo declarado, aunque la fps si este.
                FvpConditionDraft(
                    conditionKey = "load-0",
                    addedLoadKg = 0.0,
                    attempts = listOf(FvpAttemptDraft(heightM = 0.30, method = FvpMeasureMethod.MANUAL, sourceFps = 240.0)),
                ),
                FvpConditionDraft(
                    conditionKey = "load-20",
                    addedLoadKg = 20.0,
                    attempts = listOf(FvpAttemptDraft(heightM = 0.28, flightTimeS = 0.478)),
                ),
            ),
        )

        val inputs = draft.selectedSensitivityInputs()

        assertEquals(listOf(null, 0.478), inputs.flightTimesS)
        assertEquals(listOf(240.0, null), inputs.fpsPerCondition)
        assertEquals(
            FvpSensitivityAvailability.MISSING_FLIGHT_TIME,
            fvpSensitivityAvailability(inputs.flightTimesS, inputs.fpsPerCondition, hasRecalculation = true),
        )
    }

    @Test
    fun `stored original view uses the recorded selection in condition key order`() {
        val stored = storedAssessment(
            conditions = listOf(
                condition(id = 11, ordinal = 0, key = "load-20", loadKg = 20.0, selectedOrdinal = 1),
                condition(id = 12, ordinal = 1, key = "load-0", loadKg = 0.0, selectedOrdinal = null),
                condition(id = 13, ordinal = 2, key = "load-40", loadKg = 40.0, selectedOrdinal = 0),
            ),
            attempts = listOf(
                attempt(conditionId = 11, ordinal = 0, heightM = 0.32, flightTimeS = 0.51, sourceFps = 240.0),
                // Intento REGISTRADO como seleccionado sin vuelo ni fps: una fila antigua o importada.
                attempt(conditionId = 11, ordinal = 1, heightM = 0.30, flightTimeS = null, sourceFps = null),
                attempt(conditionId = 13, ordinal = 0, heightM = 0.25, flightTimeS = 0.4516, sourceFps = 120.0),
            ),
        )

        val inputs = stored.selectedSensitivityInputs(FvpResultView.ORIGINAL)

        // Orden del motor (`conditionKey`): load-0, load-20, load-40. Sin ordinal registrado o sin
        // intento, la posicion queda `null`: nunca se hereda el dato de otro intento.
        assertEquals(listOf(null, null, 0.4516), inputs.flightTimesS)
        assertEquals(listOf(null, null, 120.0), inputs.fpsPerCondition)
        assertEquals(
            FvpSensitivityAvailability.MISSING_FLIGHT_TIME,
            fvpSensitivityAvailability(inputs.flightTimesS, inputs.fpsPerCondition, hasRecalculation = true),
        )
    }

    // --- La proyeccion guardada SIGUE a la vista mostrada (P1 de T1b) -------------------------------

    /**
     * Fila GUARDADA donde el ordinal REGISTRADO y la RESELECCION canonica NO coinciden: `load-0`
     * registra el intento bajo (ordinal 1, sin vuelo ni fps) aunque el canonico sea el alto (ordinal 0)
     * y `load-40` no registra ordinal mientras el canonico de `load-40` es su segundo intento.
     */
    private fun divergingStoredRow(): StoredFvpAssessment = storedAssessment(
        conditions = listOf(
            condition(id = 11, ordinal = 0, key = "load-0", loadKg = 0.0, selectedOrdinal = 1),
            condition(id = 12, ordinal = 1, key = "load-20", loadKg = 20.0, selectedOrdinal = 0),
            condition(id = 13, ordinal = 2, key = "load-40", loadKg = 40.0, selectedOrdinal = null),
        ),
        attempts = listOf(
            attempt(conditionId = 11, ordinal = 0, heightM = 0.50, flightTimeS = 0.62, sourceFps = 60.0),
            // Intento REGISTRADO: sin vuelo ni fps, aunque otro intento de la misma condicion si los
            // declare. La proyeccion jamas puede heredarlos de ahi.
            attempt(conditionId = 11, ordinal = 1, heightM = 0.30, flightTimeS = null, sourceFps = null),
            // El intento MAS ALTO de la condicion esta DESCARTADO: nunca puede ser el canonico.
            attempt(
                conditionId = 11,
                ordinal = 2,
                heightM = 0.90,
                flightTimeS = 0.95,
                sourceFps = 30.0,
                valid = false,
                exclusionReason = "contramovimiento",
            ),
            attempt(conditionId = 12, ordinal = 0, heightM = 0.28, flightTimeS = 0.478, sourceFps = 240.0),
            attempt(conditionId = 12, ordinal = 1, heightM = 0.26, flightTimeS = 0.46, sourceFps = null),
            attempt(conditionId = 13, ordinal = 0, heightM = 0.25, flightTimeS = 0.4516, sourceFps = 120.0),
            attempt(conditionId = 13, ordinal = 1, heightM = 0.27, flightTimeS = 0.47, sourceFps = 60.0),
        ),
    )

    @Test
    fun `stored original view pairs each condition with the attempt the row records`() {
        val inputs = divergingStoredRow().selectedSensitivityInputs(FvpResultView.ORIGINAL)

        // Orden del motor: load-0, load-20, load-40. `load-0` (ordinal registrado 1) y `load-40` (sin
        // ordinal) NO declaran vuelo ni fps EN SU intento, de modo que la posicion queda nula aunque
        // otros intentos de esas MISMAS condiciones si traigan 0.62/60 y 0.47/60.
        assertEquals(listOf(null, 0.478, null), inputs.flightTimesS)
        assertEquals(listOf(null, 240.0, null), inputs.fpsPerCondition)
        assertEquals(
            FvpSensitivityAvailability.MISSING_FLIGHT_TIME,
            fvpSensitivityAvailability(inputs.flightTimesS, inputs.fpsPerCondition, hasRecalculation = true),
        )
    }

    @Test
    fun `stored recalculation view pairs each condition with the canonical attempt`() {
        val inputs = divergingStoredRow().selectedSensitivityInputs(FvpResultView.RECALCULATION)

        // Reseleccion canonica: load-0 -> ordinal 0 (0.50; el 0.90 esta DESCARTADO), load-20 ->
        // ordinal 0 (0.28 > 0.26), load-40 -> ordinal 1 (0.27 > 0.25). Cada posicion sale del intento
        // del que sale la altura de ESA vista, no del ordinal registrado.
        assertEquals(listOf(0.62, 0.478, 0.47), inputs.flightTimesS)
        assertEquals(listOf(60.0, 240.0, 60.0), inputs.fpsPerCondition)
        assertEquals(
            FvpSensitivityAvailability.AVAILABLE_GATED,
            fvpSensitivityAvailability(inputs.flightTimesS, inputs.fpsPerCondition, hasRecalculation = true),
        )
    }

    @Test
    fun `stored views report the missing input of their own attempt instead of borrowing`() {
        val stored = storedAssessment(
            conditions = listOf(
                condition(id = 21, ordinal = 0, key = "load-0", loadKg = 0.0, selectedOrdinal = 1),
                condition(id = 22, ordinal = 1, key = "load-20", loadKg = 20.0, selectedOrdinal = 0),
            ),
            attempts = listOf(
                // Canonico (mas alto): CON vuelo y SIN fps.
                attempt(conditionId = 21, ordinal = 0, heightM = 0.30, flightTimeS = 0.50, sourceFps = null),
                // Registrado (mas bajo): CON vuelo y CON fps.
                attempt(conditionId = 21, ordinal = 1, heightM = 0.28, flightTimeS = 0.48, sourceFps = 240.0),
                attempt(conditionId = 22, ordinal = 0, heightM = 0.26, flightTimeS = 0.46, sourceFps = 240.0),
            ),
        )

        val original = stored.selectedSensitivityInputs(FvpResultView.ORIGINAL)
        assertEquals(listOf(0.48, 0.46), original.flightTimesS)
        assertEquals(listOf(240.0, 240.0), original.fpsPerCondition)
        assertEquals(
            FvpSensitivityAvailability.AVAILABLE_GATED,
            fvpSensitivityAvailability(original.flightTimesS, original.fpsPerCondition, hasRecalculation = true),
        )

        // El intento canonico de load-0 declara vuelo pero NO fps: la vista de recalculo declara
        // MISSING_FPS aunque el intento REGISTRADO de esa MISMA condicion si traiga 240.0.
        val recalculation = stored.selectedSensitivityInputs(FvpResultView.RECALCULATION)
        assertEquals(listOf(0.50, 0.46), recalculation.flightTimesS)
        assertEquals(listOf(null, 240.0), recalculation.fpsPerCondition)
        assertEquals(
            FvpSensitivityAvailability.MISSING_FPS,
            fvpSensitivityAvailability(
                recalculation.flightTimesS,
                recalculation.fpsPerCondition,
                hasRecalculation = true,
            ),
        )
    }

    @Test
    fun `stored recalculation view fails closed when a persisted key is not mappable`() {
        // Clave persistida que esta version no reconoce: el repositorio no recalcula esa fila, asi que
        // no hay vista de recalculo real. Si la proyeccion se invocara igualmente, NINGUNA posicion
        // hereda el vuelo/fps de otro intento: se declara la carencia en vez de emparejar a ciegas.
        val stored = storedAssessment(
            conditions = listOf(
                condition(id = 31, ordinal = 0, key = "load-0", loadKg = 0.0, selectedOrdinal = 0),
                condition(id = 32, ordinal = 1, key = "load-20", loadKg = 20.0, selectedOrdinal = 0),
            ),
            attempts = listOf(
                attempt(conditionId = 31, ordinal = 0, heightM = 0.30, flightTimeS = 0.50, sourceFps = 240.0),
                attempt(
                    conditionId = 32,
                    ordinal = 0,
                    heightM = 0.26,
                    flightTimeS = 0.46,
                    sourceFps = 240.0,
                    method = "METODO_DESCONOCIDO",
                ),
            ),
        )

        val inputs = stored.selectedSensitivityInputs(FvpResultView.RECALCULATION)

        assertEquals(listOf(null, null), inputs.flightTimesS)
        assertEquals(listOf(null, null), inputs.fpsPerCondition)
        assertEquals(
            FvpSensitivityAvailability.MISSING_FLIGHT_TIME,
            fvpSensitivityAvailability(inputs.flightTimesS, inputs.fpsPerCondition, hasRecalculation = true),
        )
    }

    // --- Fixtures -----------------------------------------------------------------------------------

    private fun condition(
        id: Long,
        ordinal: Int,
        key: String,
        loadKg: Double,
        selectedOrdinal: Int?,
    ): FvpConditionEntity = FvpConditionEntity(
        id = id,
        assessmentId = 1L,
        ordinal = ordinal,
        conditionKey = key,
        addedLoadKg = loadKg,
        totalMassKg = bodyMassKg + loadKg,
        pushOffM = 0.40,
        selectedHeightM = 0.30,
        selectedAttemptOrdinal = selectedOrdinal,
    )

    private fun attempt(
        conditionId: Long,
        ordinal: Int,
        heightM: Double,
        flightTimeS: Double?,
        sourceFps: Double?,
        method: String = "FLIGHT_TIME_VIDEO",
        valid: Boolean = true,
        exclusionReason: String? = null,
    ): FvpAttemptEntity = FvpAttemptEntity(
        id = conditionId * 10 + ordinal,
        conditionId = conditionId,
        ordinal = ordinal,
        heightM = heightM,
        method = method,
        flightTimeS = flightTimeS,
        sourceFps = sourceFps,
        valid = valid,
        exclusionReason = exclusionReason,
    )

    private fun storedAssessment(
        conditions: List<FvpConditionEntity>,
        attempts: List<FvpAttemptEntity>,
    ): StoredFvpAssessment = StoredFvpAssessment(
        assessment = FvpAssessmentEntity(
            id = 1L,
            sessionKey = "session-1",
            athleteId = 1L,
            protocolId = "SJ",
            dateTime = 1_700_000_000_000L,
            bodyMassKg = bodyMassKg,
            bodyMassSource = "MANUAL",
            pushOffMode = "COMMON",
            pushOffM = 0.40,
            pushOffSource = "MEASURED",
            algorithmVersion = "fvp-poc-1.0",
            methodVersion = "fvp-app-1.0",
            f0N = 1500.0,
            slopeFv = -700.0,
            sfvNsPerM = 700.0,
            v0Mps = 2.14,
            pmaxW = 803.0,
            r2 = 0.96,
            conditionCount = conditions.size,
            vMinMps = 0.8,
            vMaxMps = 1.4,
            fMinN = 1200.0,
            fMaxN = 1700.0,
            f0RelNPerKg = 18.75,
            pmaxRelWPerKg = 10.04,
            warningsCsv = "",
        ),
        conditions = conditions,
        attempts = attempts,
        eligibility = null,
        result = null,
        snapshotStatus = FvpSnapshotStatus.RECALCULATION_UNAVAILABLE,
    )
}
