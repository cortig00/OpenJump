package com.openjump.app.ui.fvp

import com.openjump.app.R
import com.openjump.app.data.FVP_REUSE_CONFIRMATION_VERSION
import com.openjump.app.data.ReusableJumpMeasurement
import com.openjump.app.data.flightTimeMsToSeconds
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato de la reutilizacion defensiva B5 (plan 13, lane A, slice 13).
 *
 * Reglas cubiertas (JVM puro, sin Android ni Room):
 * 1. el tiempo de vuelo persistido [ms] -> segundos sobrevive al mapeo dominio
 *    (`flightTimeMsToSeconds`) y su AUSENCIA se propaga como `null` (nunca 0.0 ni un valor derivado
 *    de la altura: D7, la evidencia que falta no se asume);
 * 2. la escritura del intento al reutilizar (`withReusedMeasurement`) copia altura, tiempo de
 *    vuelo, metodo, fps de origen y procedencia, y deja intacto el resto del intento (nota, validez,
 *    motivo de exclusion);
 * 3. la antiguedad mostrada (`reuseAgeInDays`) es pura, en dias ENTEROS transcurridos y NUNCA
 *    negativa ante un desfase de reloj, y la seleccion 0/1/N de su leyenda (`reuseAgeLabelRes`)
 *    vive fuera del composable para poder distinguir los tres tramos aqui;
 * 4. la reutilizacion esta BLOQUEADA hasta la confirmacion explicita de ESA fila
 *    (`FvpReuseConfirmation`), que no se hereda entre mediciones ni al desmarcar la casilla;
 * 5. cuando la medicion no trae tiempo de vuelo UTILIZABLE (`reusableFlightTimeS`) la reutilizacion
 *    no copia ninguno: es la MISMA decision que declara la pantalla (P2-3, transparencia);
 * 6. B4a-2: la confirmacion de reutilizacion es DURABLE (`reuseConfirmedAt` =
 *    instante real de la declaracion + `reuseConfirmationVersion` = `fvp-reuse-1`) y cualquier
 *    cambio que forme parte de la aceptacion (carga o su procedencia, h_po relevante, altura o
 *    metodo del intento, fuente reutilizada, protocolo, contexto de adquisicion de la serie) la
 *    retira EN LA MISMA mutacion, sin tocar los snapshots de origen y sin promover nunca un
 *    intento sin confirmar. Re-verificar la MISMA fuente no la borra por accidente.
 */
class FvpReuseContextTest {

    // ---------------------------------------------------------------------------------------------
    // 1. Tiempo de vuelo persistido [ms] -> segundos
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `flightTimeMsToSeconds convierte milisegundos persistidos a segundos`() {
        assertEquals(0.6, flightTimeMsToSeconds(600.0)!!, 0.0)
        assertEquals(0.5038, flightTimeMsToSeconds(503.8)!!, 0.0)
        assertEquals(1.2, flightTimeMsToSeconds(1_200.0)!!, 0.0)
    }

    @Test
    fun `flightTimeMsToSeconds mapea ausencia y valores no utilizables a null`() {
        assertNull("metrica FLIGHT_TIME_MS ausente", flightTimeMsToSeconds(null))
        assertNull("NaN", flightTimeMsToSeconds(Double.NaN))
        assertNull("+infinito", flightTimeMsToSeconds(Double.POSITIVE_INFINITY))
        assertNull("-infinito", flightTimeMsToSeconds(Double.NEGATIVE_INFINITY))
        assertNull("cero no es una medicion", flightTimeMsToSeconds(0.0))
        assertNull("negativo no es una medicion", flightTimeMsToSeconds(-600.0))
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Escritura del intento al reutilizar un salto guardado
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `withReusedMeasurement copia altura tiempo de vuelo fps y procedencia`() {
        val measurement = measurement(
            heightM = 0.3212,
            flightTimeS = 0.6,
            detectedFps = 240,
            assessmentId = 7L,
            ordinal = 1,
        )

        val attempt = FvpAttemptDraft().withReusedMeasurement(measurement)

        assertEquals(0.3212, attempt.heightM!!, 0.0)
        assertEquals(
            "el tiempo de vuelo del salto guardado no puede perderse al reutilizarlo",
            0.6,
            attempt.flightTimeS!!,
            0.0,
        )
        assertEquals(240.0, attempt.sourceFps!!, 0.0)
        assertEquals(FvpMeasureMethod.FLIGHT_TIME_VIDEO, attempt.method)
        assertEquals(7L, attempt.sourceAssessmentId)
        assertEquals(1, attempt.sourceAttemptOrdinal)
        assertEquals("SJ", attempt.sourceProtocolId)
    }

    @Test
    fun `withReusedMeasurement no inventa tiempo de vuelo cuando el salto guardado no lo tiene`() {
        val measurement = measurement(heightM = 0.30, flightTimeS = null, detectedFps = 60)

        val attempt = FvpAttemptDraft(flightTimeS = 0.7, notes = "nota previa del usuario")
            .withReusedMeasurement(measurement)

        assertNull("un salto sin FLIGHT_TIME_MS deja el intento SIN tiempo de vuelo", attempt.flightTimeS)
        assertEquals("la nota del usuario no se pisa", "nota previa del usuario", attempt.notes)
        assertEquals(true, attempt.valid)
    }

    @Test
    fun `withReusedMeasurement descarta tiempo de vuelo altura y fps no utilizables`() {
        assertNull(
            FvpAttemptDraft()
                .withReusedMeasurement(measurement(heightM = 0.30, flightTimeS = Double.NaN))
                .flightTimeS,
        )
        assertNull(
            FvpAttemptDraft()
                .withReusedMeasurement(measurement(heightM = 0.30, flightTimeS = 0.0))
                .flightTimeS,
        )
        assertNull(
            "una altura no finita no se escribe como altura",
            FvpAttemptDraft(heightM = 0.25)
                .withReusedMeasurement(measurement(heightM = Double.NaN, flightTimeS = 0.6))
                .heightM,
        )
        assertNull(
            "0 fps de origen se lee como desconocido, no como 0 fps",
            FvpAttemptDraft(sourceFps = 240.0)
                .withReusedMeasurement(measurement(heightM = 0.30, flightTimeS = 0.6, detectedFps = 0))
                .sourceFps,
        )
    }

    @Test
    fun `reusableFlightTimeS declara la ausencia con la misma decision que la escritura`() {
        assertNull(
            "sin metrica FLIGHT_TIME_MS",
            measurement(heightM = 0.30, flightTimeS = null).reusableFlightTimeS(),
        )
        assertNull(
            "cero no es una medicion",
            measurement(heightM = 0.30, flightTimeS = 0.0).reusableFlightTimeS(),
        )
        assertNull(
            "un valor no finito no es utilizable",
            measurement(heightM = 0.30, flightTimeS = Double.POSITIVE_INFINITY).reusableFlightTimeS(),
        )
        assertEquals(
            "con tiempo utilizable la nota de ausencia no debe aparecer",
            0.6,
            measurement(heightM = 0.30, flightTimeS = 0.6).reusableFlightTimeS()!!,
            0.0,
        )
        assertNull(
            "la nota declara exactamente cuando la reutilizacion NO copiaria tiempo de vuelo",
            FvpAttemptDraft(flightTimeS = 0.7)
                .withReusedMeasurement(measurement(heightM = 0.30, flightTimeS = null))
                .flightTimeS,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Edad de la medicion guardada
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `reuseAgeInDays cuenta dias enteros transcurridos`() {
        val day = 86_400_000L
        val hour = 3_600_000L
        val now = 1_700_000_000_000L

        assertEquals("mismo instante", 0L, reuseAgeInDays(now, now))
        assertEquals("23 h todavia es el dia 0", 0L, reuseAgeInDays(now - 23 * hour, now))
        assertEquals("justo un dia completo", 1L, reuseAgeInDays(now - day, now))
        assertEquals("un dia y 23 h siguen siendo 1 dia", 1L, reuseAgeInDays(now - day - 23 * hour, now))
        assertEquals("tres dias y seis horas", 3L, reuseAgeInDays(now - 3 * day - 6 * hour, now))
        assertEquals("treinta dias", 30L, reuseAgeInDays(now - 30 * day, now))
    }

    @Test
    fun `reuseAgeInDays nunca es negativa con desfase de reloj`() {
        val now = 1_700_000_000_000L

        assertEquals("medicion un ms en el futuro", 0L, reuseAgeInDays(now + 1L, now))
        assertEquals("medicion un dia en el futuro", 0L, reuseAgeInDays(now + 86_400_000L, now))
        assertEquals("medicion lejana en el futuro", 0L, reuseAgeInDays(Long.MAX_VALUE, now))
    }

    @Test
    fun `reuseAgeLabelRes distingue los tres tramos de la leyenda de edad`() {
        val elapsedUnderADay = reuseAgeLabelRes(0L)
        val oneDay = reuseAgeLabelRes(1L)
        val manyDays = reuseAgeLabelRes(30L)

        assertEquals("menos de 24 h transcurridas", R.string.fvp_reuse_age_today, elapsedUnderADay)
        assertEquals("un dia completo", R.string.fvp_reuse_age_one_day, oneDay)
        assertEquals("N dias", R.string.fvp_reuse_age_days, manyDays)
        assertEquals(
            "los tres tramos deben resolverse a tres recursos distintos",
            3,
            setOf(elapsedUnderADay, oneDay, manyDays).size,
        )
        assertEquals(
            "el tramo 0 cubre tambien el desfase de reloj (edad nunca negativa)",
            elapsedUnderADay,
            reuseAgeLabelRes(reuseAgeInDays(1_700_000_000_000L + 86_400_000L, 1_700_000_000_000L)),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Confirmacion explicita por fila
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `la reutilizacion esta bloqueada hasta la confirmacion explicita de esa fila`() {
        val initial = FvpReuseConfirmation()
        assertFalse("nada confirmado al abrir el selector", initial.allows("10-0"))

        val accepted = initial.withCheck("10-0", checked = true)
        assertTrue(accepted.allows("10-0"))
        assertFalse("la confirmacion no se hereda a otra fila", accepted.allows("11-0"))

        val unchecked = accepted.withCheck("10-0", checked = false)
        assertFalse("desmarcar la casilla vuelve a bloquear la reutilizacion", unchecked.allows("10-0"))
    }

    @Test
    fun `confirmar otra medicion reinicia la confirmacion anterior`() {
        val first = FvpReuseConfirmation().withCheck("10-0", checked = true)
        assertTrue(first.allows("10-0"))

        val second = first.withCheck("11-0", checked = true)
        assertTrue(second.allows("11-0"))
        assertFalse("elegir otra medicion no hereda la declaracion anterior", second.allows("10-0"))
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Confirmacion durable de reutilizacion (B4a-2) e invalidacion SINCRONA
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `confirmar la reutilizacion persiste version y el instante real de la declaracion`() {
        val measurement = measurement(
            heightM = 0.3212,
            flightTimeS = 0.6,
            detectedFps = 240,
            assessmentId = 7L,
            ordinal = 1,
        )
        val draft = reuseDraft(sourceMeasurement = measurement)
        val attempt = draft.conditions.first().attempts.first()

        assertEquals(
            "la fecha de la confirmacion es el instante REAL de la declaracion",
            1_700_000_900_000L,
            attempt.reuseConfirmedAt,
        )
        assertEquals(FVP_REUSE_CONFIRMATION_VERSION, attempt.reuseConfirmationVersion)
        assertEquals("fvp-reuse-1", attempt.reuseConfirmationVersion)
        // Los campos de origen que la fuente aporta: se copian tal cual.
        assertEquals(7L, attempt.sourceAssessmentId)
        assertEquals(1, attempt.sourceAttemptOrdinal)
        assertEquals("SJ", attempt.sourceProtocolId)
        assertEquals(FvpMeasureMethod.FLIGHT_TIME_VIDEO, attempt.method)
        assertEquals(240.0, attempt.sourceFps!!, 0.0)
        // Lo que la fuente NO aporta no se inventa: ni PTS, ni mapeo temporal, ni fechas.
        assertNull(attempt.takeoffPtsUs)
        assertNull(attempt.landingPtsUs)
        assertEquals("UNKNOWN", attempt.timeMapping)
        assertNull(attempt.timeMappingEvidenceKey)
        assertNull("la fecha del origen no es una captura", attempt.acquiredAt)
        assertNull(attempt.sourceRecordedAt)
    }

    @Test
    fun `cambiar la carga o su procedencia invalida la confirmacion de esa condicion`() {
        val draft = reuseDraft()
        assertTrue(confirmed(draft, condition = 0))
        assertTrue(confirmed(draft, condition = 1))

        val newLoad = draft.withConditionLoad("c0", 25.0)
        assertEquals(25.0, newLoad.conditions[0].addedLoadKg!!, 0.0)
        assertEquals("la clave por defecto sigue a la carga", "25kg", newLoad.conditions[0].conditionKey)
        assertFalse("cambiar la carga confirmada retira la confirmacion", confirmed(newLoad, condition = 0))
        assertTrue("la otra condicion no se toca", confirmed(newLoad, condition = 1))
        assertEquals(
            "el snapshot de origen no se borra al invalidar",
            10L,
            newLoad.conditions[0].attempts[0].sourceAssessmentId,
        )

        val weighed = draft.withConditionAddedLoadSource("c0", "WEIGHED")
        assertEquals("WEIGHED", weighed.conditions[0].addedLoadSource)
        assertFalse(confirmed(weighed, condition = 0))
        assertTrue(confirmed(weighed, condition = 1))

        // Volver a UNKNOWN desde una condicion CONFIRMADA con WEIGHED tambien invalida: no se
        // conserva una aceptacion de otra procedencia (partir de una fila ya invalidada haria la
        // asercion vacua).
        val weighedConfirmed = draft.copy(
            conditions = listOf(
                draft.conditions[0].copy(
                    addedLoadSource = "WEIGHED",
                    attempts = draft.conditions[0].attempts.map { it.withReuseConfirmation(CONFIRMED_AT) },
                ),
                draft.conditions[1],
            ),
        )
        assertTrue(confirmed(weighedConfirmed, condition = 0))
        val backToUnknown = weighedConfirmed.withConditionAddedLoadSource("c0", "UNKNOWN")
        assertEquals("UNKNOWN", backToUnknown.conditions[0].addedLoadSource)
        assertFalse(confirmed(backToUnknown, condition = 0))
    }

    @Test
    fun `reescribir el mismo valor no retira ninguna confirmacion`() {
        val draft = reuseDraft()
        val attempt = draft.conditions[0].attempts[0]

        assertTrue("misma carga", confirmed(draft.withConditionLoad("c0", 0.0), condition = 0))
        assertTrue(
            "misma procedencia de carga",
            confirmed(draft.withConditionAddedLoadSource("c0", "UNKNOWN"), condition = 0),
        )
        assertTrue(
            "misma altura",
            confirmed(draft.withAttemptHeight("c0", "a0", attempt.heightM), condition = 0),
        )
        assertTrue(
            "mismo metodo",
            confirmed(draft.withAttemptMethod("c0", "a0", attempt.method), condition = 0),
        )
        assertTrue("mismo protocolo", confirmed(draft.withSeriesProtocol(FvpProtocol.SJ), condition = 0))
        assertTrue(
            "mismo contexto de adquisicion",
            confirmed(draft.withAcquisitionContext("UNKNOWN", null), condition = 0),
        )
    }

    @Test
    fun `cambiar altura o metodo invalida solo la confirmacion de ese intento`() {
        val draft = reuseDraft()

        val byHeight = draft.withAttemptHeight("c0", "a0", 0.44)
        assertEquals(0.44, byHeight.conditions[0].attempts[0].heightM!!, 0.0)
        assertFalse(confirmed(byHeight, condition = 0))
        assertTrue(confirmed(byHeight, condition = 1))

        val byMethod = draft.withAttemptMethod("c0", "a0", FvpMeasureMethod.MANUAL)
        assertEquals(FvpMeasureMethod.MANUAL, byMethod.conditions[0].attempts[0].method)
        assertFalse(confirmed(byMethod, condition = 0))
        assertTrue(confirmed(byMethod, condition = 1))
    }

    @Test
    fun `cambiar la fuente reutilizada retira la confirmacion y re-verificar la misma no`() {
        val draft = reuseDraft()
        val confirmedAttempt = draft.conditions[0].attempts[0]
        assertTrue(confirmed(draft, condition = 0))

        // Re-verificacion de la MISMA medicion con el MISMO snapshot: la declaracion sigue en pie.
        val reverified = confirmedAttempt.withReusedMeasurement(
            measurement(heightM = 0.32, flightTimeS = 0.6),
        )
        assertEquals(
            "re-verificar la misma fuente no borra la confirmacion por accidente",
            1_700_000_900_000L,
            reverified.reuseConfirmedAt,
        )
        assertEquals(FVP_REUSE_CONFIRMATION_VERSION, reverified.reuseConfirmationVersion)

        // Aplicar OTRA fuente sin declararla no puede conservar la aceptacion anterior, y ademas
        // invalida la evidencia temporal del origen anterior (PTS/mapeo/referencias).
        val withEvidence = confirmedAttempt.copy(
            takeoffPtsUs = 1_000L,
            landingPtsUs = 2_000L,
            timeMapping = "IDENTITY_VERIFIED",
            timeMappingEvidenceKey = "ev-1",
            sourceRecordedAt = 5L,
            sourceAthleteId = 7L,
            acquiredAt = 6L,
        )
        val switched = withEvidence.withReusedMeasurement(
            measurement(heightM = 0.40, flightTimeS = 0.55, assessmentId = 99L, ordinal = 2),
        )
        assertEquals(99L, switched.sourceAssessmentId)
        assertNull("otra fuente retira la confirmacion vieja", switched.reuseConfirmedAt)
        assertNull(switched.reuseConfirmationVersion)
        assertNull("los PTS del origen anterior no sobreviven", switched.takeoffPtsUs)
        assertNull(switched.landingPtsUs)
        assertEquals("UNKNOWN", switched.timeMapping)
        assertNull(switched.timeMappingEvidenceKey)
        assertNull(switched.sourceRecordedAt)
        assertNull(switched.sourceAthleteId)
        assertNull(switched.acquiredAt)
        // Re-verificar la MISMA fuente conserva la evidencia temporal del origen.
        val reSameSource = withEvidence.withReusedMeasurement(measurement(heightM = 0.32, flightTimeS = 0.6))
        assertEquals(1_000L, reSameSource.takeoffPtsUs)
        assertEquals("IDENTITY_VERIFIED", reSameSource.timeMapping)
        assertEquals("ev-1", reSameSource.timeMappingEvidenceKey)

        // Y la misma fuente sobre un snapshot que YA no coincide tampoco la conserva.
        val edited = confirmedAttempt.copy(heightM = 0.50)
        val reapplied = edited.withReusedMeasurement(measurement(heightM = 0.32, flightTimeS = 0.6))
        assertEquals(0.32, reapplied.heightM!!, 0.0)
        assertNull(reapplied.reuseConfirmedAt)
    }

    @Test
    fun `cambiar protocolo h_po de la serie o contexto invalida TODAS las confirmaciones`() {
        val draft = reuseDraft()

        val otherProtocol = draft.withSeriesProtocol(FvpProtocol.CMJ)
        assertEquals(FvpProtocol.CMJ, otherProtocol.protocol)
        assertFalse(confirmed(otherProtocol, condition = 0))
        assertFalse(confirmed(otherProtocol, condition = 1))

        val otherPushOff = draft.withSeriesPushOffM(0.45)
        assertEquals(0.45, otherPushOff.commonPushOffM!!, 0.0)
        assertFalse(confirmed(otherPushOff, condition = 0))
        assertFalse(confirmed(otherPushOff, condition = 1))

        val otherContext = draft.withAcquisitionContext("SAME_ACQUISITION", "ctx-1")
        assertEquals("SAME_ACQUISITION", otherContext.acquisitionContextKind)
        assertEquals("ctx-1", otherContext.acquisitionContextKey)
        assertFalse(confirmed(otherContext, condition = 0))
        assertFalse(confirmed(otherContext, condition = 1))

        // El MODO cambia QUE h_po es el relevante (comun o por condicion): invalida aunque el
        // valor comun no cambie, y escribir el mismo modo no invalida.
        val otherMode = draft.withSeriesPushOffMode(FvpPushOffUiMode.PER_CONDITION)
        assertEquals(FvpPushOffUiMode.PER_CONDITION, otherMode.pushOffMode)
        assertFalse(confirmed(otherMode, condition = 0))
        assertFalse(confirmed(otherMode, condition = 1))
        assertTrue(confirmed(draft.withSeriesPushOffMode(FvpPushOffUiMode.COMMON), condition = 0))
    }

    @Test
    fun `el h_po por condicion solo invalida esa condicion y solo en modo por condicion`() {
        val perCondition = reuseDraft().copy(pushOffMode = FvpPushOffUiMode.PER_CONDITION)
        val edited = perCondition.withConditionPushOffM("c0", 0.42)
        assertEquals(0.42, edited.conditions[0].pushOffM!!, 0.0)
        assertFalse(confirmed(edited, condition = 0))
        assertTrue(confirmed(edited, condition = 1))
        // En PER_CONDITION el h_po comun de la serie no entra en el ajuste: cambiarlo no invalida.
        assertTrue(confirmed(perCondition.withSeriesPushOffM(0.45), condition = 0))

        // En COMMON el h_po por condicion no entra en la serie: cambiarlo no invalida nada.
        assertTrue(confirmed(reuseDraft().withConditionPushOffM("c0", 0.42), condition = 0))

        // Reescribir el MISMO h_po comun no invalida (no hay cambio real).
        val series = reuseDraft()
        assertTrue(confirmed(series.withSeriesPushOffM(series.commonPushOffM), condition = 0))

        // Una clave de condicion PERSONALIZADA no se pisa al cambiar la carga si el usuario no la
        // habia dejado en el valor por defecto.
        val customKey = reuseDraft().copy(
            conditions = reuseDraft().conditions.map { it.copy(conditionKey = "mi-clave") },
        )
        // El helper direcciona por `draftKey` (identidad de memoria), no por la etiqueta visible.
        val renamed = customKey.withConditionLoad(customKey.conditions[0].draftKey, 12.5)
        assertEquals(12.5, renamed.conditions[0].addedLoadKg!!, 0.0)
        assertEquals("mi-clave", renamed.conditions[0].conditionKey)
    }

    @Test
    fun `un intento sin confirmar sigue sin confirmar tras cualquier mutacion`() {
        val unconfirmed = FvpDraft(
            sessionKey = "sin-confirmar",
            athleteId = 1L,
            conditions = listOf(
                FvpConditionDraft(
                    draftKey = "c0",
                    conditionKey = "0kg",
                    addedLoadKg = 0.0,
                    attempts = listOf(FvpAttemptDraft(draftKey = "a0")),
                ),
            ),
        )

        val mutated = unconfirmed
            .withConditionLoad("c0", 20.0)
            .withConditionAddedLoadSource("c0", "NOMINAL")
            .withAttemptHeight("c0", "a0", 0.35)
            .withAttemptMethod("c0", "a0", FvpMeasureMethod.MANUAL)
            .withSeriesProtocol(FvpProtocol.CMJ)
            .withSeriesPushOffM(0.4)
            .withAcquisitionContext("SAME_ACQUISITION", "ctx")

        assertEquals("NOMINAL", mutated.conditions[0].addedLoadSource)
        assertNull("ninguna mutacion concede una confirmacion", mutated.conditions[0].attempts[0].reuseConfirmedAt)
        assertNull(mutated.conditions[0].attempts[0].reuseConfirmationVersion)
    }

    /**
     * Borrador de dos condiciones con la reutilizacion YA CONFIRMADA en el primer intento de cada
     * una: es el estado de partida de las pruebas de invalidacion, de modo que cada cambio deba
     * dejar SOLO las confirmaciones afectadas.
     */
    private fun reuseDraft(
        sourceMeasurement: ReusableJumpMeasurement = measurement(heightM = 0.32, flightTimeS = 0.6),
    ): FvpDraft = FvpDraft(
        sessionKey = "reuse-draft",
        athleteId = 1L,
        conditions = listOf(
            FvpConditionDraft(
                draftKey = "c0",
                conditionKey = "0kg",
                addedLoadKg = 0.0,
                attempts = listOf(FvpAttemptDraft(draftKey = "a0")),
            ),
            FvpConditionDraft(
                draftKey = "c1",
                conditionKey = "20kg",
                addedLoadKg = 20.0,
                attempts = listOf(FvpAttemptDraft(draftKey = "a1")),
            ),
        ),
    ).withAttemptConfirmedReuse("c0", "a0", sourceMeasurement, confirmedAtMillis = CONFIRMED_AT)
        .withAttemptConfirmedReuse("c1", "a1", sourceMeasurement, confirmedAtMillis = CONFIRMED_AT)

    /** La confirmacion DURABLE esta presente en el intento indicado (fecha + version v1). */
    private fun confirmed(draft: FvpDraft, condition: Int, attempt: Int = 0): Boolean {
        val row = draft.conditions[condition].attempts[attempt]
        return row.reuseConfirmedAt == CONFIRMED_AT &&
            row.reuseConfirmationVersion == FVP_REUSE_CONFIRMATION_VERSION
    }

    private fun measurement(
        heightM: Double,
        flightTimeS: Double?,
        detectedFps: Int = 240,
        assessmentId: Long = 10L,
        ordinal: Int = 0,
    ) = ReusableJumpMeasurement(
        assessmentId = assessmentId,
        attemptOrdinal = ordinal,
        dateTime = 1_700_000_000_000L,
        heightM = heightM,
        flightTimeS = flightTimeS,
        detectedFps = detectedFps,
        side = null,
        protocolId = "SJ",
        hasVideoUri = false,
        notes = null,
    )

    private companion object {
        /** Instante fijo de la declaracion de reutilizacion usado en las pruebas (epoch ms). */
        const val CONFIRMED_AT = 1_700_000_900_000L
    }
}
