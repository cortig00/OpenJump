package com.openjump.app.ui.fvp

import com.openjump.app.data.FvpAssessmentEntity
import com.openjump.app.data.FvpAttemptEntity
import com.openjump.app.data.FvpConditionEntity
import com.openjump.app.data.FvpSnapshotStatus
import com.openjump.app.data.StoredFvpAssessment
import com.openjump.app.fvp.FvpEligibility
import com.openjump.app.fvp.FvpQualityState
import com.openjump.app.fvp.FvpTimingQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D4/B4 + D5 (primera mitad): comparar dos series guardadas exige, ademas de los metadatos
 * persistidos de siempre, que las DOS sean perfiles comparables (ni diagnosticas ni incompletas) y
 * que el atleta y la version del algoritmo sean conocidos.
 *
 * Cada valor de [FvpComparisonBlock] tiene aqui su caso LITERAL, con la direccion que lo dispara y
 * la que no; [allBlocksAreCollectedInEnumOrder] fija que se recogen TODOS los motivos aplicables en
 * el orden de declaracion del enum. La politica es pura: no toca motor, Room ni Compose.
 */
class FvpComparisonPolicyTest {

    // --- Caso normal: perfil contra perfil -----------------------------------------------------

    /** PROFILE vs PROFILE con los mismos metadatos: se pueden mostrar deltas. */
    @Test
    fun profileVersusProfileCanCompareDeltas() {
        val policy = fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L))

        assertTrue("sin motivos: los deltas son publicables", policy.canCompareDeltas)
        assertEquals(emptyList<FvpComparisonBlock>(), policy.blocks)
        assertTrue(policy.sameExactAlgorithmVersion)
    }

    /**
     * Q2 (fix de seguridad): una serie con R^2 bajo es DIAGNOSTIC, no un perfil CONDITIONAL
     * comparable. La puerta NO relaja la calidad para comparar: exigir "no DIAGNOSTIC/INCOMPLETE"
     * ya no basta para un aviso que la politica trata como no revisable.
     */
    @Test
    fun lowR2SeriesAreDiagnosticAndCannotProduceDeltas() {
        val first = storedSeries(id = 1L, warningsCsv = "LOW_R2")
        val second = storedSeries(id = 2L, warningsCsv = "LOW_R2")

        val policy = fvpComparisonPolicy(first, second)

        assertFalse("R^2 bajo: sin deltas", policy.canCompareDeltas)
        assertEquals(listOf(FvpComparisonBlock.NOT_A_PROFILE), policy.blocks)
        assertEquals(FvpQualityState.DIAGNOSTIC, policy.context.qualityFirst)
        assertEquals(FvpQualityState.DIAGNOSTIC, policy.context.qualitySecond)
        assertEquals(emptyList<FvpComparisonDeltaLine>(), policy.context.deltaLines)
    }

    /**
     * Direccion negativa (no sobre-bloquear): HIGH_EXTRAPOLATION es el UNICO aviso del motor que
     * deja la serie en CONDITIONAL, estado que sigue siendo comparable (D4: el perfil existe con
     * salvedades y se muestran).
     */
    @Test
    fun highExtrapolationSeriesStayComparableAsConditional() {
        val policy = fvpComparisonPolicy(
            storedSeries(id = 1L, warningsCsv = "HIGH_EXTRAPOLATION"),
            storedSeries(id = 2L, warningsCsv = "HIGH_EXTRAPOLATION"),
        )

        assertTrue(policy.canCompareDeltas)
        assertEquals(emptyList<FvpComparisonBlock>(), policy.blocks)
        assertEquals(FvpQualityState.CONDITIONAL, policy.context.qualityFirst)
    }

    /**
     * Q2/D4: un timing no `ALLOW` (UNKNOWN, BLOCKED o 60-<120 fps sin sensibilidad revisada) es
     * DIAGNOSTIC: no hay ruta temporal condicional habilitada, de modo que la serie no produce
     * deltas. Se comprueban las tres calidades temporales no elegibles.
     */
    @Test
    fun timingIneligibleSeriesAreNotComparableAsProfiles() {
        listOf(
            FvpTimingQuality.UNKNOWN,
            FvpTimingQuality.BLOCKED,
            FvpTimingQuality.CONDITIONAL,
        ).forEach { timing ->
            val policy = fvpComparisonPolicy(
                storedSeries(id = 1L, timingQuality = timing),
                storedSeries(id = 2L, timingQuality = timing),
            )
            assertFalse("timing $timing: sin deltas", policy.canCompareDeltas)
            assertEquals("timing $timing", listOf(FvpComparisonBlock.NOT_A_PROFILE), policy.blocks)
            assertEquals(FvpQualityState.DIAGNOSTIC, policy.context.qualityFirst)
        }
    }

    /**
     * Q2/D4: un snapshot guardado que NO coincide con el recalculo actual (MISMATCH, STALE,
     * STALE_METHOD o RECALCULATION_UNAVAILABLE) no puede establecer los deltas originales: la fila
     * sigue LEGIBLE (contexto y numeros guardados), pero no produce lineas de delta.
     */
    @Test
    fun snapshotThatCannotBeVerifiedCannotProduceDeltas() {
        listOf(
            FvpSnapshotStatus.MISMATCH,
            FvpSnapshotStatus.STALE,
            FvpSnapshotStatus.STALE_METHOD,
            FvpSnapshotStatus.RECALCULATION_UNAVAILABLE,
        ).forEach { status ->
            val policy = fvpComparisonPolicy(
                storedSeries(id = 1L, snapshotStatus = status),
                storedSeries(id = 2L),
            )
            assertFalse("snapshot $status: sin deltas", policy.canCompareDeltas)
            assertEquals("snapshot $status", listOf(FvpComparisonBlock.NOT_A_PROFILE), policy.blocks)
            assertEquals(FvpQualityState.DIAGNOSTIC, policy.context.qualityFirst)
            assertEquals("la fila sigue siendo legible", 80.0, policy.context.bodyMassKg.first ?: Double.NaN, 1e-9)
        }
        assertTrue(
            "MATCHES es el unico estado que permite comparar",
            fvpComparisonPolicy(storedSeries(id = 3L), storedSeries(id = 4L)).canCompareDeltas,
        )
    }

    /** Un nombre de aviso persistido sin clasificar convierte la fila en DIAGNOSTIC. */
    @Test
    fun unclassifiedPersistedWarningIsNotAComparableProfile() {
        val policy = fvpComparisonPolicy(
            storedSeries(id = 1L, warningsCsv = "FUTURE_ENGINE_WARNING"),
            storedSeries(id = 2L),
        )

        assertFalse(policy.canCompareDeltas)
        assertEquals(listOf(FvpComparisonBlock.NOT_A_PROFILE), policy.blocks)
        assertEquals(FvpQualityState.DIAGNOSTIC, policy.context.qualityFirst)
    }

    // --- Un caso por bloque --------------------------------------------------------------------

    /** DIFFERENT_ATHLETE: falta el atleta en alguna fila o no es el mismo; nulo tampoco vale. */
    @Test
    fun athleteMustBeTheSameAndPresentInBoth() {
        assertEquals(
            "atleta nulo en una fila",
            listOf(FvpComparisonBlock.DIFFERENT_ATHLETE),
            fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L, athleteId = null)).blocks,
        )
        assertEquals(
            "las dos filas sin atleta tampoco son comparables",
            listOf(FvpComparisonBlock.DIFFERENT_ATHLETE),
            fvpComparisonPolicy(
                storedSeries(id = 1L, athleteId = null),
                storedSeries(id = 2L, athleteId = null),
            ).blocks,
        )
        assertEquals(
            "atletas distintos",
            listOf(FvpComparisonBlock.DIFFERENT_ATHLETE),
            fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L, athleteId = 7L)).blocks,
        )
        assertTrue(fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L)).canCompareDeltas)
    }

    /** UNKNOWN_OR_DIFFERENT_ALGORITHM: version en blanco o familia distinta; misma familia no bloquea. */
    @Test
    fun unknownOrDifferentAlgorithmVersionBlocks() {
        assertEquals(
            "version en blanco",
            listOf(FvpComparisonBlock.UNKNOWN_OR_DIFFERENT_ALGORITHM),
            fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L, algorithmVersion = "")).blocks,
        )
        assertEquals(
            "version con solo espacios: desconocida",
            listOf(FvpComparisonBlock.UNKNOWN_OR_DIFFERENT_ALGORITHM),
            fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L, algorithmVersion = "   ")).blocks,
        )
        assertEquals(
            "las dos desconocidas tampoco se comparan",
            listOf(FvpComparisonBlock.UNKNOWN_OR_DIFFERENT_ALGORITHM),
            fvpComparisonPolicy(
                storedSeries(id = 1L, algorithmVersion = ""),
                storedSeries(id = 2L, algorithmVersion = ""),
            ).blocks,
        )
        assertEquals(
            "otra familia",
            listOf(FvpComparisonBlock.UNKNOWN_OR_DIFFERENT_ALGORITHM),
            fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L, algorithmVersion = "otra-1.0")).blocks,
        )
        assertEquals(
            "futuro: una version futura no pasa por compartir prefijo con la actual",
            listOf(FvpComparisonBlock.UNKNOWN_OR_DIFFERENT_ALGORITHM),
            fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L, algorithmVersion = "fvp-poc-999.0")).blocks,
        )
        assertEquals(
            "dos versiones desconocidas iguales tampoco establecen compatibilidad",
            listOf(FvpComparisonBlock.UNKNOWN_OR_DIFFERENT_ALGORITHM),
            fvpComparisonPolicy(
                storedSeries(id = 1L, algorithmVersion = "fvp-poc-x"),
                storedSeries(id = 2L, algorithmVersion = "fvp-poc-x"),
            ).blocks,
        )

        // Misma version EXACTA revisada (la actual): sin motivo de version.
        val sameVersion = fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L))
        assertTrue(sameVersion.canCompareDeltas)
        assertTrue(sameVersion.sameExactAlgorithmVersion)
    }

    /**
     * Q2: la version EXACTA del metodo tambien es una puerta. Una version futura o desconocida (o
     * dos iguales pero no revisadas) no establece compatibilidad, aunque el algoritmo coincida; el
     * snapshot `STALE_METHOD` del repositorio queda cubierto por el mismo motivo.
     */
    @Test
    fun unreviewedMethodVersionsBlockComparability() {
        assertEquals(
            "futuro",
            listOf(FvpComparisonBlock.UNKNOWN_OR_DIFFERENT_ALGORITHM),
            fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L, methodVersion = "fvp-app-999.0")).blocks,
        )
        assertEquals(
            "desconocido en una fila",
            listOf(FvpComparisonBlock.UNKNOWN_OR_DIFFERENT_ALGORITHM),
            fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L, methodVersion = "")).blocks,
        )
        assertEquals(
            "dos cadenas desconocidas distintas",
            listOf(FvpComparisonBlock.UNKNOWN_OR_DIFFERENT_ALGORITHM),
            fvpComparisonPolicy(
                storedSeries(id = 1L, methodVersion = "fvp-app-x"),
                storedSeries(id = 2L, methodVersion = "fvp-app-y"),
            ).blocks,
        )
        assertTrue(fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L)).canCompareDeltas)
    }

    /** DIFFERENT_PROTOCOL: distinto protocolo o distinto modo de h_po. */
    @Test
    fun differentProtocolOrPushOffModeBlocks() {
        assertEquals(
            "distinto protocolo",
            listOf(FvpComparisonBlock.DIFFERENT_PROTOCOL),
            fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L, protocolId = "CMJ")).blocks,
        )
        assertEquals(
            "distinto modo de h_po",
            listOf(FvpComparisonBlock.DIFFERENT_PROTOCOL),
            fvpComparisonPolicy(
                storedSeries(id = 1L),
                storedSeries(id = 2L, pushOffMode = "PER_CONDITION"),
            ).blocks,
        )
    }

    /**
     * DIFFERENT_PUSH_OFF_PROCEDURE: bloquea SOLO el PROCEDIMIENTO declarado (texto normalizado sin
     * espacios alrededor). C2: el VALOR de h_po deja de bloquear en `COMMON`.
     */
    @Test
    fun differentPushOffProcedureBlocksButTheCommonValueDoesNot() {
        assertEquals(
            "otro procedimiento",
            listOf(FvpComparisonBlock.DIFFERENT_PUSH_OFF_PROCEDURE),
            fvpComparisonPolicy(
                storedSeries(id = 1L),
                storedSeries(id = 2L, pushOffProtocol = "OTRO_PROCEDIMIENTO"),
            ).blocks,
        )
        assertEquals(
            "un procedimiento sin declarar en una serie tampoco es el mismo procedimiento",
            listOf(FvpComparisonBlock.DIFFERENT_PUSH_OFF_PROCEDURE),
            fvpComparisonPolicy(
                storedSeries(id = 1L),
                storedSeries(id = 2L, pushOffProtocol = null),
            ).blocks,
        )
        assertEquals(
            "DOS procedimientos ausentes no se convierten en un procedimiento comun",
            listOf(FvpComparisonBlock.DIFFERENT_PUSH_OFF_PROCEDURE),
            fvpComparisonPolicy(
                storedSeries(id = 1L, pushOffProtocol = null),
                storedSeries(id = 2L, pushOffProtocol = null),
            ).blocks,
        )
        assertEquals(
            "un procedimiento no reconocido tampoco establece compatibilidad, aunque coincida",
            listOf(FvpComparisonBlock.DIFFERENT_PUSH_OFF_PROCEDURE),
            fvpComparisonPolicy(
                storedSeries(id = 1L, pushOffProtocol = "LANDMARK"),
                storedSeries(id = 2L, pushOffProtocol = "LANDMARK"),
            ).blocks,
        )
        assertEquals(
            "procedimiento en blanco: desconocido",
            listOf(FvpComparisonBlock.DIFFERENT_PUSH_OFF_PROCEDURE),
            fvpComparisonPolicy(
                storedSeries(id = 1L, pushOffProtocol = "   "),
                storedSeries(id = 2L, pushOffProtocol = "   "),
            ).blocks,
        )
        assertTrue(
            "el procedimiento CANONICO se compara sin espacios alrededor",
            fvpComparisonPolicy(
                storedSeries(id = 1L, pushOffProtocol = " LANDMARK_EXTENDED_MINUS_CROUCH "),
                storedSeries(id = 2L, pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH"),
            ).canCompareDeltas,
        )
        // C2: en COMMON la distancia declarada es CONTEXTO, nunca un motivo de bloqueo.
        val differentCommonValue = fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 3L, pushOffM = 0.45))
        assertTrue(
            "otra distancia en COMMON no bloquea (es contexto)",
            differentCommonValue.canCompareDeltas,
        )
        assertEquals(emptyList<FvpComparisonBlock>(), differentCommonValue.blocks)
        assertEquals(0.05, differentCommonValue.context.pushOffM?.delta ?: Double.NaN, 1e-9)
    }

    /** La masa corporal ya NO bloquea (C2): es CONTEXTO con los deltas relativos como primarios. */
    @Test
    fun bodyMassBecomesContextInsteadOfABlock() {
        val policy = fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L, bodyMassKg = 82.5))

        assertTrue("la masa es contexto, no un motivo de bloqueo", policy.canCompareDeltas)
        assertEquals(emptyList<FvpComparisonBlock>(), policy.blocks)
        assertTrue(policy.context.massDiffers)
        assertEquals(80.0, policy.context.bodyMassKg.first ?: Double.NaN, 1e-9)
        assertEquals(82.5, policy.context.bodyMassKg.second ?: Double.NaN, 1e-9)
        assertEquals(2.5, policy.context.bodyMassKg.delta ?: Double.NaN, 1e-9)
        assertFalse(
            "la misma masa no declara diferencia",
            fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 3L, bodyMassKg = 80.0))
                .context.massDiffers,
        )
    }

    /**
     * DIFFERENT_LOAD_COVERAGE: la lista de cargas se compara CON multiplicidad (`[0,20,20,40]` !=
     * `[0,20,40]`). Una carga repetida no es lo mismo que una carga ausente.
     */
    @Test
    fun loadCoverageWithDuplicateLoadMultiplicityDiffers_blocks() {
        // Caso LITERAL del contrato: [0,20,20,40] contra [0,20,40]. La serie corta es ademas
        // diagnostica (3 condiciones), motivo ortogonal que se declara a continuacion.
        val withDuplicate = storedSeries(
            id = 1L,
            conditionKeys = listOf("0kg", "20kg", "20kg", "40kg"),
            loads = listOf(0.0, 20.0, 20.0, 40.0),
        )
        val withoutDuplicate = storedSeries(
            id = 2L,
            conditionKeys = listOf("0kg", "20kg", "40kg"),
            loads = listOf(0.0, 20.0, 40.0),
        )
        val literal = fvpComparisonPolicy(withDuplicate, withoutDuplicate)
        assertFalse("otra multiplicidad de cargas no es la misma cobertura", literal.canCompareDeltas)
        assertEquals(
            listOf(FvpComparisonBlock.NOT_A_PROFILE, FvpComparisonBlock.DIFFERENT_LOAD_COVERAGE),
            literal.blocks,
        )

        // Mismo caso sin el motivo diagnostico: OTRA lista de cargas, misma cardinalidad.
        val otherCoverage = fvpComparisonPolicy(
            withDuplicate,
            storedSeries(
                id = 3L,
                conditionKeys = listOf("0kg", "20kg", "40kg", "60kg"),
                loads = listOf(0.0, 20.0, 40.0, 60.0),
            ),
        )
        assertFalse(otherCoverage.canCompareDeltas)
        assertEquals(
            "la cobertura manda y NO se anaden motivos de asociacion (los metodos son los mismos)",
            listOf(FvpComparisonBlock.DIFFERENT_LOAD_COVERAGE),
            otherCoverage.blocks,
        )

        // La direccion negativa: MISMA lista con la MISMA multiplicidad -> comparable, en cualquier
        // orden de condiciones (se compara la lista ordenada).
        assertTrue(
            "la misma lista con la misma multiplicidad si es comparable",
            fvpComparisonPolicy(withDuplicate, storedSeries(
                id = 4L,
                conditionKeys = listOf("0kg", "20kg", "20kg", "40kg"),
                loads = listOf(0.0, 20.0, 20.0, 40.0),
            )).canCompareDeltas,
        )
        assertTrue(
            "el mismo multiconjunto con multiplicidad en otro orden es la misma cobertura",
            fvpComparisonPolicy(withDuplicate, storedSeries(
                id = 5L,
                conditionKeys = listOf("40kg", "20kg", "0kg", "20kg"),
                loads = listOf(40.0, 20.0, 0.0, 20.0),
            )).canCompareDeltas,
        )
    }

    /**
     * DIFFERENT_PER_LOAD_PUSH_OFF (bisagra obligatoria): en `PER_CONDITION` la asociacion carga-h_po
     * sigue bloqueando. Los h_po permutados entre cargas NO son el mismo experimento, y el h_po
     * identico por carga SI es comparable (direccion negativa, para no sobre-bloquear).
     */
    @Test
    fun perConditionSameLoadsSwappedPushOffBetweenLoads_blocks() {
        val overrides = listOf(0.46, 0.42, 0.38, 0.34)
        val base = storedSeries(id = 1L, pushOffMode = "PER_CONDITION", conditionPushOffM = overrides)

        assertTrue(
            "los mismos pares (carga, h_po) en el mismo orden son comparables",
            fvpComparisonPolicy(
                base,
                storedSeries(id = 2L, pushOffMode = "PER_CONDITION", conditionPushOffM = overrides),
            ).canCompareDeltas,
        )
        val swapped = fvpComparisonPolicy(
            base,
            storedSeries(id = 3L, pushOffMode = "PER_CONDITION", conditionPushOffM = overrides.reversed()),
        )
        assertFalse("h_po permutados entre cargas no son el mismo experimento", swapped.canCompareDeltas)
        assertEquals(listOf(FvpComparisonBlock.DIFFERENT_PER_LOAD_PUSH_OFF), swapped.blocks)
        // Un unico override distinto (el resto identico) ya bloquea por la misma regla.
        assertEquals(
            listOf(FvpComparisonBlock.DIFFERENT_PER_LOAD_PUSH_OFF),
            fvpComparisonPolicy(
                base,
                storedSeries(
                    id = 4L,
                    pushOffMode = "PER_CONDITION",
                    conditionPushOffM = listOf(0.46, 0.42, 0.38, 0.36),
                ),
            ).blocks,
        )
        // El orden de las condiciones no importa: cada carga viaja con SU h_po.
        assertTrue(
            "los mismos pares en otro orden de condiciones son comparables",
            fvpComparisonPolicy(
                base,
                storedSeries(
                    id = 5L,
                    pushOffMode = "PER_CONDITION",
                    conditionKeys = listOf("60kg", "0kg", "40kg", "20kg"),
                    loads = listOf(60.0, 0.0, 40.0, 20.0),
                    conditionPushOffM = listOf(0.34, 0.46, 0.38, 0.42),
                ),
            ).canCompareDeltas,
        )
    }

    /**
     * Rejilla de 0,5 cm (B1) y NADA mas: el ruido de ida y vuelta metrico/imperial
     * (0,3999992 m = 0,40 m re-parseado) no es una diferencia de medicion, pero 0,41 m SI lo es
     * (no se admite una tolerancia mayor).
     */
    @Test
    fun commonModePushOffWithinEntryResolution_noSpuriousDifference() {
        // En COMMON el valor de h_po ni siquiera bloquea: es contexto.
        assertTrue(
            "COMMON: el valor de h_po es contexto",
            fvpComparisonPolicy(
                storedSeries(id = 1L),
                storedSeries(id = 2L, pushOffM = 0.3999992),
            ).canCompareDeltas,
        )
        // El ruido de rejilla solo puede morder en PER_CONDITION, donde los pares comparados usan
        // el h_po de cada condicion: la canonicalizacion de 0,5 cm los iguala.
        val base = storedSeries(id = 1L, pushOffMode = "PER_CONDITION", conditionPushOffM = listOf(0.40, 0.40))
        val imperialRoundTrip = fvpComparisonPolicy(
            base,
            storedSeries(
                id = 2L,
                pushOffMode = "PER_CONDITION",
                conditionPushOffM = listOf(0.3999992, 0.3999992),
            ),
        )
        assertTrue(
            "0,40 vs 0,3999992 esta en la misma rejilla de 0,5 cm: sin diferencia espuria",
            imperialRoundTrip.canCompareDeltas,
        )
        assertEquals(emptyList<FvpComparisonBlock>(), imperialRoundTrip.blocks)
        // Y la direccion opuesta: 0,41 m es OTRA celda de la rejilla y bloquea (nada de tolerancias).
        assertFalse(
            "0,41 m si es una diferencia real de medicion",
            fvpComparisonPolicy(
                base,
                storedSeries(
                    id = 3L,
                    pushOffMode = "PER_CONDITION",
                    conditionPushOffM = listOf(0.41, 0.41),
                ),
            ).canCompareDeltas,
        )
    }

    /** DIFFERENT_MEASURE_METHODS: distinto conjunto de metodos de los intentos SELECCIONADOS. */
    @Test
    fun differentSelectedMeasureMethodsBlock() {
        assertEquals(
            listOf(FvpComparisonBlock.DIFFERENT_MEASURE_METHODS),
            fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L, method = "MANUAL")).blocks,
        )
        assertTrue(
            "el mismo metodo en las dos series no bloquea",
            fvpComparisonPolicy(
                storedSeries(id = 1L, method = "MANUAL"),
                storedSeries(id = 2L, method = "MANUAL"),
            ).canCompareDeltas,
        )
    }

    /** NOT_A_PROFILE: una serie DIAGNOSTIC (2 o 3 condiciones) nunca ofrece deltas. */
    @Test
    fun diagnosticSeriesAreNotComparableAsProfiles() {
        val threeConditions = listOf("0kg", "20kg", "40kg")
        assertEquals(
            "3 condiciones: diagnostica",
            listOf(FvpComparisonBlock.NOT_A_PROFILE),
            fvpComparisonPolicy(
                storedSeries(id = 1L, conditionKeys = threeConditions),
                storedSeries(id = 2L, conditionKeys = threeConditions),
            ).blocks,
        )
        val twoConditions = listOf("0kg", "20kg")
        assertEquals(
            "2 condiciones: diagnostica",
            listOf(FvpComparisonBlock.NOT_A_PROFILE),
            fvpComparisonPolicy(
                storedSeries(id = 1L, conditionKeys = twoConditions),
                storedSeries(id = 2L, conditionKeys = twoConditions),
            ).blocks,
        )
    }

    /** NOT_A_PROFILE: un ajuste NO fisico (aviso persistido) no es un perfil que comparar. */
    @Test
    fun nonPhysicalFitIsNotComparableAsProfile() {
        assertEquals(
            listOf(FvpComparisonBlock.NOT_A_PROFILE),
            fvpComparisonPolicy(
                storedSeries(id = 1L),
                storedSeries(id = 2L, warningsCsv = "NON_PHYSICAL"),
            ).blocks,
        )
        assertEquals(
            "sin R^2 persistido tampoco hay ajuste que juzgar",
            listOf(FvpComparisonBlock.NOT_A_PROFILE),
            fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L, r2 = null)).blocks,
        )
    }

    /** NOT_A_PROFILE: una fila que no se puede recalcular (sin elegibilidad) es INCOMPLETE. */
    @Test
    fun rowThatCannotBeRecomputedIsNotComparable() {
        assertEquals(
            listOf(FvpComparisonBlock.NOT_A_PROFILE),
            fvpComparisonPolicy(
                storedSeries(id = 1L, recomputable = false),
                storedSeries(id = 2L),
            ).blocks,
        )
        assertFalse(
            fvpComparisonPolicy(
                storedSeries(id = 1L, recomputable = false),
                storedSeries(id = 2L),
            ).canCompareDeltas,
        )
    }

    /**
     * Se recogen TODOS los motivos aplicables, en el orden del enum (matriz del contrato C2). Con la
     * MISMA cobertura de cargas se anaden los dos motivos de asociacion; con cobertura distinta la
     * cobertura los subsume (no se duplican motivos enganosos).
     */
    @Test
    fun allBlocksAreCollectedInEnumOrder() {
        val first = storedSeries(
            id = 1L,
            athleteId = null,
            algorithmVersion = "",
            methodByCondition = listOf("FLIGHT_TIME_VIDEO", "FLIGHT_TIME_VIDEO", "FLIGHT_TIME_VIDEO", "FLIGHT_TIME_VIDEO"),
        )
        val second = storedSeries(
            id = 2L,
            athleteId = 7L,
            protocolId = "CMJ",
            pushOffMode = "PER_CONDITION",
            pushOffProtocol = "OTRO_PROCEDIMIENTO",
            bodyMassKg = 82.5,
            algorithmVersion = "otra-1.0",
            conditionPushOffM = listOf(0.46, 0.42, 0.38, 0.34),
            method = "MANUAL",
            warningsCsv = "NON_PHYSICAL",
        )

        val policy = fvpComparisonPolicy(first, second)

        assertEquals(
            listOf(
                FvpComparisonBlock.DIFFERENT_ATHLETE,
                FvpComparisonBlock.UNKNOWN_OR_DIFFERENT_ALGORITHM,
                FvpComparisonBlock.DIFFERENT_PROTOCOL,
                FvpComparisonBlock.DIFFERENT_PUSH_OFF_PROCEDURE,
                FvpComparisonBlock.NOT_A_PROFILE,
                FvpComparisonBlock.DIFFERENT_MEASURE_METHODS,
                FvpComparisonBlock.DIFFERENT_PER_LOAD_PUSH_OFF,
            ),
            policy.blocks,
        )
        assertFalse(policy.canCompareDeltas)
        assertFalse(policy.sameExactAlgorithmVersion)
        // La rama bloqueada NUNCA lleva deltas, pero SI conserva el contexto (C2).
        assertEquals(emptyList<FvpComparisonDeltaLine>(), policy.context.deltaLines)
        assertTrue("el contexto sigue disponible con bloqueos", policy.context.massDiffers)
        assertEquals(4, policy.context.conditionCountFirst)

        // Cobertura distinta: se declara la cobertura y no se anaden los motivos de asociacion.
        val otherLoads = storedSeries(
            id = 3L,
            conditionKeys = listOf("0kg", "30kg", "60kg", "90kg"),
            loads = listOf(0.0, 30.0, 60.0, 90.0),
        )
        assertEquals(
            listOf(FvpComparisonBlock.DIFFERENT_LOAD_COVERAGE),
            fvpComparisonPolicy(storedSeries(id = 4L), otherLoads).blocks,
        )
    }

    // --- B4b: procedencia de adquisicion y mapeo temporal ---------------------------------------

    /**
     * B4b: con contexto de adquisicion DECLARADO e IDENTICO (`SAME_ACQUISITION` + la misma clave),
     * masa y carga pesadas y mapeo temporal verificado en TODOS los intentos seleccionados, la
     * comparacion sigue produciendo deltas: la puerta de procedencia no relaja nada mas.
     */
    @Test
    fun declaredSameAcquisitionAndVerifiedTimeMappingAllowDeltas() {
        val policy = fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L))

        assertTrue(policy.canCompareDeltas)
        assertEquals(emptyList<FvpComparisonBlock>(), policy.blocks)
        assertFalse(
            "la procedencia verificada no vacia la rama descriptiva",
            policy.context.deltaLines.isEmpty(),
        )
    }

    /**
     * B4b limite DELIBERADO: dos series declaradas como `HISTORICAL_COMPOSITE` (compuestas) con
     * mapeo temporal verificado siguen comparandose numericamente; el caracter retrospectivo lo
     * declara el AVISO del resultado (la politica solo bloquea contexto distinto o sin declarar).
     */
    @Test
    fun twoHistoricalCompositesWithVerifiedTimingStillCompareDeltas() {
        val policy = fvpComparisonPolicy(
            storedSeries(id = 1L, acquisitionContextKind = "HISTORICAL_COMPOSITE"),
            storedSeries(id = 2L, acquisitionContextKind = "HISTORICAL_COMPOSITE"),
        )
        assertTrue("dos composites no bloquean por si mismos", policy.blocks.isEmpty())
        assertTrue(policy.canCompareDeltas)
    }

    /**
     * B4b regla 9: un contexto de adquisicion DISTINTO, SIN DECLARAR (`UNKNOWN`) o con una clave que
     * esta version no reconoce bloquea los deltas. `UNKNOWN` no es "misma adquisicion" ni siquiera
     * cuando las DOS series lo declaran. La rama bloqueada conserva el contexto (descriptiva).
     */
    @Test
    fun differentOrUndeclaredAcquisitionContextBlocksDeltas() {
        assertEquals(
            "contexto declarado distinto",
            listOf(FvpComparisonBlock.DIFFERENT_ACQUISITION_CONTEXT),
            fvpComparisonPolicy(
                storedSeries(id = 1L, acquisitionContextKind = "SAME_ACQUISITION"),
                storedSeries(id = 2L, acquisitionContextKind = "HISTORICAL_COMPOSITE"),
            ).blocks,
        )
        assertEquals(
            "una serie sin declarar: UNKNOWN no es misma adquisicion",
            listOf(FvpComparisonBlock.DIFFERENT_ACQUISITION_CONTEXT),
            fvpComparisonPolicy(
                storedSeries(id = 1L, acquisitionContextKind = "UNKNOWN"),
                storedSeries(id = 2L, acquisitionContextKind = "SAME_ACQUISITION"),
            ).blocks,
        )
        assertEquals(
            "las dos sin declarar tampoco",
            listOf(FvpComparisonBlock.DIFFERENT_ACQUISITION_CONTEXT),
            fvpComparisonPolicy(
                storedSeries(id = 1L, acquisitionContextKind = "UNKNOWN"),
                storedSeries(id = 2L, acquisitionContextKind = "UNKNOWN"),
            ).blocks,
        )
        assertEquals(
            "clave de contexto de una version futura: no se interpreta como declarada",
            listOf(FvpComparisonBlock.DIFFERENT_ACQUISITION_CONTEXT),
            fvpComparisonPolicy(
                storedSeries(id = 1L, acquisitionContextKind = "QA_FUTURE_CONTEXT"),
                storedSeries(id = 2L, acquisitionContextKind = "QA_FUTURE_CONTEXT"),
            ).blocks,
        )

        val blocked = fvpComparisonPolicy(
            storedSeries(id = 3L, acquisitionContextKind = "UNKNOWN"),
            storedSeries(id = 4L),
        )
        assertFalse("sin contexto declarado no hay deltas", blocked.canCompareDeltas)
        assertEquals(emptyList<FvpComparisonDeltaLine>(), blocked.context.deltaLines)
        assertEquals(
            "la rama bloqueada sigue siendo descriptiva: contexto visible",
            4,
            blocked.context.conditionCountSecond,
        )
        assertEquals(80.0, blocked.context.bodyMassKg.first ?: Double.NaN, 1e-9)
    }

    /**
     * B4b regla 10: con `SAME_ACQUISITION` en las dos series la clave opaca tiene que estar
     * PRESENTE y ser la MISMA. Sin clave en alguna (o con claves distintas) no se puede comprobar
     * que sea la misma adquisicion y no se publican deltas.
     */
    @Test
    fun sameAcquisitionWithDifferentOrMissingContextKeyBlocksDeltas() {
        assertEquals(
            "claves distintas",
            listOf(FvpComparisonBlock.DIFFERENT_ACQUISITION_CONTEXT_KEY),
            fvpComparisonPolicy(
                storedSeries(id = 1L, acquisitionContextKey = "ctx-a"),
                storedSeries(id = 2L, acquisitionContextKey = "ctx-b"),
            ).blocks,
        )
        assertEquals(
            "sin clave en una serie",
            listOf(FvpComparisonBlock.DIFFERENT_ACQUISITION_CONTEXT_KEY),
            fvpComparisonPolicy(
                storedSeries(id = 1L, acquisitionContextKey = null),
                storedSeries(id = 2L, acquisitionContextKey = "ctx-a"),
            ).blocks,
        )
        assertEquals(
            "sin clave en las dos",
            listOf(FvpComparisonBlock.DIFFERENT_ACQUISITION_CONTEXT_KEY),
            fvpComparisonPolicy(
                storedSeries(id = 1L, acquisitionContextKey = null),
                storedSeries(id = 2L, acquisitionContextKey = null),
            ).blocks,
        )
        assertTrue(
            "la misma clave declarada si compara",
            fvpComparisonPolicy(
                storedSeries(id = 3L, acquisitionContextKey = "ctx-comun"),
                storedSeries(id = 4L, acquisitionContextKey = "ctx-comun"),
            ).canCompareDeltas,
        )
    }

    /**
     * B4b regla 11: sin mapeo temporal verificado en TODOS los intentos SELECCIONADOS no se publican
     * deltas (el PEOR intento seleccionado decide, `UNKNOWN` incluido). Un intento DESCARTADO sin
     * mapeo no bloquea: no produce datos ni tiempo que afirmar.
     */
    @Test
    fun unverifiedSelectedTimeMappingBlocksDeltasWithoutPromotingUnknown() {
        assertEquals(
            "un UNKNOWN entre los seleccionados bloquea",
            listOf(FvpComparisonBlock.TIME_MAPPING_NOT_VERIFIED),
            fvpComparisonPolicy(
                storedSeries(id = 1L),
                storedSeries(
                    id = 2L,
                    timeMappingByCondition = listOf(
                        "IDENTITY_VERIFIED",
                        "IDENTITY_VERIFIED",
                        "UNKNOWN",
                        "IDENTITY_VERIFIED",
                    ),
                ),
            ).blocks,
        )
        assertEquals(
            "sin intento seleccionado registrado tampoco hay mapeo verificado: el motivo temporal " +
                "se anade junto al gate de asociacion carga-metodo que ya bloqueaba",
            listOf(
                FvpComparisonBlock.DIFFERENT_MEASURE_METHODS,
                FvpComparisonBlock.TIME_MAPPING_NOT_VERIFIED,
            ),
            fvpComparisonPolicy(
                storedSeries(id = 1L),
                storedSeries(id = 2L, selectedOrdinals = listOf(1, 1, null, 1)),
            ).blocks,
        )
        val discardedUnknown = fvpComparisonPolicy(
            storedSeries(id = 1L, extraUnverifiedAttemptAt = 0),
            storedSeries(id = 2L),
        )
        assertTrue(
            "un intento NO seleccionado sin mapeo no produce datos que afirmar",
            discardedUnknown.canCompareDeltas,
        )
        assertEquals(emptyList<FvpComparisonBlock>(), discardedUnknown.blocks)
    }

    /**
     * B4b: los motivos nuevos se recogen en el orden de declaracion del enum junto a los vigentes y
     * la rama bloqueada NUNCA lleva deltas: la comparacion sigue siendo descriptiva (contexto), sin
     * delta numerico ni ganador.
     */
    @Test
    fun provenanceBlocksAreCollectedInEnumOrderAndNeverProduceDeltas() {
        val policy = fvpComparisonPolicy(
            storedSeries(
                id = 1L,
                acquisitionContextKind = "UNKNOWN",
                timeMappingByCondition = List(4) { "UNKNOWN" },
            ),
            storedSeries(id = 2L, acquisitionContextKind = "HISTORICAL_COMPOSITE"),
        )

        assertEquals(
            listOf(
                FvpComparisonBlock.DIFFERENT_ACQUISITION_CONTEXT,
                FvpComparisonBlock.TIME_MAPPING_NOT_VERIFIED,
            ),
            policy.blocks,
        )
        assertFalse(policy.canCompareDeltas)
        assertEquals(emptyList<FvpComparisonDeltaLine>(), policy.context.deltaLines)
        assertEquals("el contexto sigue visible en la rama bloqueada", 4, policy.context.conditionCountFirst)
        assertEquals(4, policy.context.conditionCountSecond)
    }

    /**
     * B4b (limite DELIBERADO): la procedencia de MASA y CARGA no bloquea deltas en esta politica
     * (el contrato B4b solo bloquea contexto de adquisicion y mapeo temporal); lo que hace es
     * declararse como AVISO en la pantalla del resultado. No se sobre-bloquea una puerta que el
     * contrato no pide, ni se inventa una promocion: las claves desconocidas siguen desconocidas.
     */
    @Test
    fun bodyMassAndLoadProvenanceDoNotBlockDeltasByThemselves() {
        val policy = fvpComparisonPolicy(
            storedSeries(
                id = 1L,
                bodyMassMeasurement = "UNKNOWN",
                addedLoadSources = List(4) { "UNKNOWN" },
            ),
            storedSeries(
                id = 2L,
                bodyMassMeasurement = "DECLARED",
                addedLoadSources = List(4) { "NOMINAL" },
            ),
        )

        assertTrue(policy.canCompareDeltas)
        assertEquals(emptyList<FvpComparisonBlock>(), policy.blocks)
    }

    /**
     * Test 4 del contrato: con masa distinta los deltas PRIMARIOS son los RELATIVOS (N/kg, W/kg) y
     * los absolutos (N, W) van marcados como escalados por masa; ΔV0 no depende de la masa. Los dos
     * no se presentan como la misma afirmacion.
     */
    @Test
    fun massDiffers_deltasShownAsRelativePrimary_andAbsoluteLabelledMassScaled() {
        val policy = fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L, bodyMassKg = 82.5))

        assertTrue(policy.canCompareDeltas)
        assertTrue("con masa distinta mandan los relativos", policy.context.relativeDeltasArePrimary)
        val lines = policy.context.deltaLines
        val f0Relative = lines.single { it.kind == FvpComparisonDeltaKind.F0_RELATIVE }
        val pmaxRelative = lines.single { it.kind == FvpComparisonDeltaKind.PMAX_RELATIVE }
        val f0Absolute = lines.single { it.kind == FvpComparisonDeltaKind.F0_ABSOLUTE }
        val pmaxAbsolute = lines.single { it.kind == FvpComparisonDeltaKind.PMAX_ABSOLUTE }
        assertTrue("ΔF0 relativo es primario", f0Relative.primary)
        assertTrue("ΔPmax relativo es primario", pmaxRelative.primary)
        assertFalse("ΔF0 absoluto NO es primario con masa distinta", f0Absolute.primary)
        assertTrue("ΔF0 absoluto va etiquetado como escalado por masa", f0Absolute.massScaled)
        assertFalse("ΔPmax absoluto NO es primario con masa distinta", pmaxAbsolute.primary)
        assertTrue("ΔPmax absoluto va etiquetado como escalado por masa", pmaxAbsolute.massScaled)
        // Los dos valores relativos ya persistidos se exponen y su delta es (segunda - primera).
        assertEquals(18.75, policy.context.f0RelNPerKg.first ?: Double.NaN, 1e-9)
        assertEquals(10.04, policy.context.pmaxRelWPerKg.first ?: Double.NaN, 1e-9)
        assertEquals(0.0, policy.context.f0RelNPerKg.delta ?: Double.NaN, 1e-9)
        // ΔV0 es velocidad: se pinta como delta normal, nunca como escalado por masa.
        val v0 = lines.single { it.kind == FvpComparisonDeltaKind.V0_ABSOLUTE }
        assertTrue(v0.primary)
        assertFalse(v0.massScaled)
        // Direccion negativa: con la MISMA masa los absolutos son los primarios.
        val sameMass = fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L))
        assertFalse(sameMass.context.relativeDeltasArePrimary)
        assertTrue(
            sameMass.context.deltaLines.filter { it.kind == FvpComparisonDeltaKind.F0_ABSOLUTE }
                .single().primary,
        )
        assertTrue(sameMass.context.deltaLines.none { it.massScaled })
    }

    /**
     * Test 5 del contrato, corregido por Q2: la nota de version se exige cuando difiere CUALQUIERA
     * de las dos (tambien si solo difiere el metodo), pero una version NO revisada ya no produce
     * deltas: la compatibilidad exige la version exacta actual en las dos series. La nota sigue
     * calculada porque la rama bloqueada conserva el contexto.
     */
    @Test
    fun versionNoteShownForMethodVersionOnlyDifference() {
        val sameVersions = fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L))
        assertTrue(sameVersions.sameExactAlgorithmVersion)
        assertTrue(sameVersions.sameExactMethodVersion)
        assertFalse("sin diferencias no hay nota", sameVersions.context.versionNoteRequired)

        val methodOnly = fvpComparisonPolicy(
            storedSeries(id = 1L),
            storedSeries(id = 2L, methodVersion = "fvp-app-2.0"),
        )
        assertTrue("el algoritmo sigue siendo el actual", methodOnly.sameExactAlgorithmVersion)
        assertFalse("solo cambia el metodo", methodOnly.sameExactMethodVersion)
        assertTrue("una diferencia SOLO de metodo exige la nota", methodOnly.context.versionNoteRequired)
        assertFalse("una version de metodo no revisada no produce deltas", methodOnly.canCompareDeltas)
        assertEquals(
            listOf(FvpComparisonBlock.UNKNOWN_OR_DIFFERENT_ALGORITHM),
            methodOnly.blocks,
        )
        assertEquals("fvp-app-2.0", methodOnly.context.methodVersionSecond)
        assertEquals("fvp-app-1.0", methodOnly.context.methodVersionFirst)

        val algorithmOnly = fvpComparisonPolicy(
            storedSeries(id = 1L),
            storedSeries(id = 2L, algorithmVersion = "fvp-poc-2.0"),
        )
        assertFalse(algorithmOnly.sameExactAlgorithmVersion)
        assertTrue(algorithmOnly.sameExactMethodVersion)
        assertTrue(algorithmOnly.context.versionNoteRequired)
        assertFalse("una version futura del algoritmo no produce deltas", algorithmOnly.canCompareDeltas)
        assertEquals(
            listOf(FvpComparisonBlock.UNKNOWN_OR_DIFFERENT_ALGORITHM),
            algorithmOnly.blocks,
        )

        val both = fvpComparisonPolicy(
            storedSeries(id = 1L),
            storedSeries(id = 2L, algorithmVersion = "fvp-poc-2.0", methodVersion = "fvp-app-2.0"),
        )
        assertFalse(both.sameExactAlgorithmVersion)
        assertFalse(both.sameExactMethodVersion)
        assertTrue(both.context.versionNoteRequired)
        assertFalse(both.canCompareDeltas)
    }

    /**
     * Test 6 (parte de politica pura) y test 7 del contrato: con cualquier bloqueo no se pinta NINGUN
     * delta y el contexto sigue disponible; con deltas, la nota neutral es obligatoria. La parte de
     * pintado de la UI se cubre en el test instrumentado (compilado, no ejecutado aqui).
     */
    @Test
    fun blockedComparisonHasNoDeltaLinesAndNeutralCaptionIsRequiredWithDeltas() {
        val blocked = fvpComparisonPolicy(
            storedSeries(id = 1L, athleteId = null),
            storedSeries(id = 2L, athleteId = 7L),
        )
        assertFalse(blocked.canCompareDeltas)
        assertEquals(listOf(FvpComparisonBlock.DIFFERENT_ATHLETE), blocked.blocks)
        assertEquals("rama bloqueada: cero lineas de delta", emptyList<FvpComparisonDeltaLine>(), blocked.context.deltaLines)
        assertFalse("sin deltas la nota neutral no aplica", blocked.context.neutralCaptionRequired)
        // El contexto sigue calculado (masa, velocidad, calidad, versiones) aunque la rama bloquee.
        assertEquals(80.0, blocked.context.bodyMassKg.first ?: Double.NaN, 1e-9)
        assertEquals(4, blocked.context.conditionCountSecond)
        assertEquals(FvpQualityState.PROFILE, blocked.context.qualityFirst)
        assertEquals("fvp-app-1.0", blocked.context.methodVersionSecond)

        val comparable = fvpComparisonPolicy(storedSeries(id = 1L), storedSeries(id = 2L))
        assertTrue(comparable.canCompareDeltas)
        assertTrue(comparable.context.deltaLines.isNotEmpty())
        assertTrue(
            "siempre que haya algun delta, la nota neutral es obligatoria",
            comparable.context.neutralCaptionRequired,
        )
    }

    /**
     * P2-8 (una sola fuente): `relativeDeltasArePrimary` es LA decision de lectura y los papeles
     * `primary`/`massScaled` de cada linea se DERIVAN de ella. Se comprueba la coherencia en las
     * dos direcciones (masa distinta y masa igual) para que no puedan divergir: los absolutos de
     * fuerza/potencia son primarios exactamente cuando los relativos NO mandan, y van escalados por
     * masa exactamente cuando los relativos mandan; relativos y ΔV0 son siempre lectura directa.
     */
    @Test
    fun perLineFlagsDeriveFromTheSinglePrimaryDecision() {
        fun assertConsistent(context: FvpComparisonContext) {
            context.deltaLines.forEach { line ->
                val massSensitive = line.kind == FvpComparisonDeltaKind.F0_ABSOLUTE ||
                    line.kind == FvpComparisonDeltaKind.PMAX_ABSOLUTE
                if (massSensitive) {
                    assertEquals(
                        "los absolutos son primarios exactamente cuando los relativos NO lo son",
                        !context.relativeDeltasArePrimary,
                        line.primary,
                    )
                    assertEquals(
                        "los absolutos van escalados por masa exactamente cuando los relativos mandan",
                        context.relativeDeltasArePrimary,
                        line.massScaled,
                    )
                } else {
                    assertTrue("relativos y ΔV0 son siempre lectura directa", line.primary)
                    assertFalse("relativos y ΔV0 nunca van escalados por masa", line.massScaled)
                }
            }
        }

        val differentMass = fvpComparisonPolicy(
            storedSeries(id = 1L),
            storedSeries(id = 2L, bodyMassKg = 82.5),
        ).context
        assertTrue(differentMass.relativeDeltasArePrimary)
        assertConsistent(differentMass)

        val sameMass = fvpComparisonPolicy(storedSeries(id = 3L), storedSeries(id = 4L)).context
        assertFalse(sameMass.relativeDeltasArePrimary)
        assertConsistent(sameMass)
    }

    /**
     * P2-9 (defensivo): si la masa difiere y la fila NO trae medible NINGUN relativo (los dos campos
     * persistidos son `null`), el grupo escalado por masa no puede presentarse como toda la lectura:
     * el contexto declara la situacion (`relativeDeltasUnavailable`) para que la UI lo diga. La
     * direccion negativa (relativos presentes) NO activa la nota.
     */
    @Test
    fun massDiffersWithoutRelativeValuesDeclaresThemUnavailable() {
        val withoutRelative = fvpComparisonPolicy(
            storedSeries(id = 1L),
            storedSeries(id = 2L, bodyMassKg = 82.5, f0RelNPerKg = null, pmaxRelWPerKg = null),
        )
        assertTrue("la masa es contexto: no bloquea", withoutRelative.canCompareDeltas)
        assertEquals(emptyList<FvpComparisonBlock>(), withoutRelative.blocks)
        assertTrue(
            "masa distinta sin relativos: la situacion se declara",
            withoutRelative.context.relativeDeltasUnavailable,
        )
        assertFalse(
            "no hay ninguna linea de delta relativo que ensenar",
            withoutRelative.context.relativeDeltasShown,
        )
        // Los absolutos de fuerza/potencia si se pintan, pero SOLO como grupo escalado por masa.
        val scaled = withoutRelative.context.deltaLines.filter { it.massScaled }
        assertTrue("los absolutos escalados si se pintan", scaled.isNotEmpty())
        assertTrue("el grupo escalado nunca es la linea primaria", scaled.none { it.primary })

        // Direccion negativa: con los relativos persistidos la nota NO aplica y se pinta la lectura
        // primaria relativa.
        val withRelative = fvpComparisonPolicy(
            storedSeries(id = 3L),
            storedSeries(id = 4L, bodyMassKg = 82.5),
        )
        assertFalse(withRelative.context.relativeDeltasUnavailable)
        assertTrue(withRelative.context.relativeDeltasShown)
        assertTrue(withRelative.context.deltaLines.any { it.primary })
    }

    /**
     * P2-7: la diferencia de h_po que se ensena se calcula con los MISMOS valores canonicalizados a
     * la rejilla de 0,5 cm que compara la regla 8. El ruido de ida y vuelta metrico/imperial da una
     * diferencia EXACTAMENTE 0 (nunca el "-0.0 cm" que salia de restar los valores crudos) y una
     * celda distinta conserva el signo real.
     */
    @Test
    fun pushOffDeltaIsComputedFromTheCanonicalGridValues() {
        val base = storedSeries(id = 1L, pushOffM = 0.40)
        val imperialRoundTrip = fvpComparisonPolicy(base, storedSeries(id = 2L, pushOffM = 0.3999992))
        assertTrue("en COMMON el h_po es contexto: se pueden comparar deltas", imperialRoundTrip.canCompareDeltas)
        assertEquals(
            "la diferencia CRUDA no es cero: es la que se redondeaba a -0.0 cm",
            -8.0e-7,
            imperialRoundTrip.context.pushOffM?.delta ?: Double.NaN,
            1e-12,
        )
        assertEquals(
            "canonicalizada a la rejilla de 0,5 cm la diferencia es exactamente 0",
            0.0,
            imperialRoundTrip.context.canonicalPushOffM?.delta ?: Double.NaN,
            0.0,
        )
        assertEquals(
            "los valores canonicalizados siguen expuestos en el contexto",
            0.40,
            imperialRoundTrip.context.canonicalPushOffM?.second ?: Double.NaN,
            0.0,
        )
        // Una celda de rejilla distinta (0,5 cm) conserva el signo real en los dos sentidos.
        assertEquals(
            "segunda mas alta: +0,5 cm",
            0.005,
            fvpComparisonPolicy(base, storedSeries(id = 3L, pushOffM = 0.405))
                .context.canonicalPushOffM?.delta ?: Double.NaN,
            1e-9,
        )
        assertEquals(
            "segunda mas baja: -0,5 cm",
            -0.005,
            fvpComparisonPolicy(storedSeries(id = 4L, pushOffM = 0.405), base)
                .context.canonicalPushOffM?.delta ?: Double.NaN,
            1e-9,
        )
        // En PER_CONDITION no hay h_po unico: tampoco hay comparacion canonicalizada que ensenar.
        assertNull(
            fvpComparisonPolicy(
                storedSeries(id = 5L, pushOffMode = "PER_CONDITION", conditionPushOffM = listOf(0.40, 0.40, 0.40, 0.40)),
                storedSeries(id = 6L, pushOffMode = "PER_CONDITION", conditionPushOffM = listOf(0.40, 0.40, 0.40, 0.40)),
            ).context.canonicalPushOffM,
        )
    }

    /**
     * P2-6: con alguna serie en `PER_CONDITION`, la politica expone el h_po CANONICALIZADO por
     * condicion para que la UI pueda ensenar QUE difiere cuando el bloqueo es
     * [FvpComparisonBlock.DIFFERENT_PER_LOAD_PUSH_OFF]. En `COMMON`/`COMMON` no se expone (la fila de
     * h_po ya ensena el valor unico) y el dato es SOLO diagnostico: no interviene en ninguna regla.
     */
    @Test
    fun perLoadPushOffEntriesAreExposedWhenPerCondition() {
        val perCondition = storedSeries(
            id = 1L,
            pushOffMode = "PER_CONDITION",
            conditionPushOffM = listOf(0.46, 0.42, 0.38, 0.34),
        )
        val swapped = storedSeries(
            id = 2L,
            pushOffMode = "PER_CONDITION",
            conditionPushOffM = listOf(0.34, 0.38, 0.42, 0.46),
        )
        val policy = fvpComparisonPolicy(perCondition, swapped)
        assertEquals(listOf(FvpComparisonBlock.DIFFERENT_PER_LOAD_PUSH_OFF), policy.blocks)
        assertEquals(
            "cada (carga, h_po) viaja con SU carga, ordenado por carga",
            listOf(
                FvpPerLoadPushOffEntry(0.0, 0.46),
                FvpPerLoadPushOffEntry(20.0, 0.42),
                FvpPerLoadPushOffEntry(40.0, 0.38),
                FvpPerLoadPushOffEntry(60.0, 0.34),
            ),
            policy.context.perLoadPushOffFirst,
        )
        assertEquals(
            "la segunda serie ensena la asociacion permutada: el bloqueo queda explicado",
            listOf(
                FvpPerLoadPushOffEntry(0.0, 0.34),
                FvpPerLoadPushOffEntry(20.0, 0.38),
                FvpPerLoadPushOffEntry(40.0, 0.42),
                FvpPerLoadPushOffEntry(60.0, 0.46),
            ),
            policy.context.perLoadPushOffSecond,
        )

        // La canonicalizacion de la rejilla de 0,5 cm alcanza tambien al dato visible.
        val rounded = fvpComparisonPolicy(
            storedSeries(
                id = 3L,
                pushOffMode = "PER_CONDITION",
                conditionPushOffM = listOf(0.3999992, 0.3999992, 0.3999992, 0.3999992),
            ),
            storedSeries(
                id = 4L,
                pushOffMode = "PER_CONDITION",
                conditionPushOffM = listOf(0.40, 0.40, 0.40, 0.40),
            ),
        )
        assertTrue(rounded.canCompareDeltas)
        assertEquals(
            "el ruido de ida y vuelta se colapsa a la misma celda en el dato visible",
            listOf(
                FvpPerLoadPushOffEntry(0.0, 0.40),
                FvpPerLoadPushOffEntry(20.0, 0.40),
                FvpPerLoadPushOffEntry(40.0, 0.40),
                FvpPerLoadPushOffEntry(60.0, 0.40),
            ),
            rounded.context.perLoadPushOffFirst,
        )

        // COMMON/COMMON: no se expone h_po por condicion (seria repetir el valor unico de la fila).
        assertNull(fvpComparisonPolicy(storedSeries(id = 5L), storedSeries(id = 6L)).context.perLoadPushOffFirst)
        // Modos distintos: cada serie ensena su tabla (la COMMON, su referencia en cada carga).
        val mixedModes = fvpComparisonPolicy(storedSeries(id = 7L), perCondition)
        assertTrue(FvpComparisonBlock.DIFFERENT_PROTOCOL in mixedModes.blocks)
        assertTrue("la serie COMMON tambien ensena su tabla en modo mixto", mixedModes.context.perLoadPushOffFirst != null)
        assertTrue("la serie PER_CONDITION ensena la suya", mixedModes.context.perLoadPushOffSecond != null)
    }

    /**
     * El contexto de velocidad y de calidad se calcula para las DOS series: `[vMin, vMax]` con el
     * aviso de rango estrecho de B4 (ΔV < 0,15 m/s) cuando corresponde, y el estado de calidad
     * derivado de cada serie.
     */
    @Test
    fun velocityCoverageAndQualityAreExposedAsContext() {
        val narrow = fvpComparisonPolicy(
            storedSeries(id = 1L, vMinMps = 1.00, vMaxMps = 1.10),
            storedSeries(id = 2L),
        )
        assertTrue("ΔV = 0,10 m/s < 0,15 m/s: el aviso de B4 aplica", narrow.context.narrowRangeWarningApplies)
        assertEquals(1.00, narrow.context.velocityFirst.vMinMps ?: Double.NaN, 1e-9)
        assertEquals(1.10, narrow.context.velocityFirst.vMaxMps ?: Double.NaN, 1e-9)
        assertTrue(narrow.context.velocityFirst.narrowRange)
        assertFalse("la otra serie tiene rango amplio", narrow.context.velocitySecond.narrowRange)
        assertEquals(0.6, narrow.context.velocitySecond.spanMps ?: Double.NaN, 1e-9)

        val wide = fvpComparisonPolicy(
            storedSeries(id = 1L, vMinMps = 0.8, vMaxMps = 1.4),
            storedSeries(id = 2L),
        )
        assertFalse("ΔV = 0,60 m/s: sin aviso", wide.context.narrowRangeWarningApplies)

        // El aviso PERSISTIDO tambien cuenta, aunque el rango guardado parezca amplio.
        val persisted = fvpComparisonPolicy(
            storedSeries(id = 1L, warningsCsv = "NARROW_VELOCITY_RANGE"),
            storedSeries(id = 2L),
        )
        assertTrue(persisted.context.narrowRangeWarningApplies)

        // La procedencia declarada de h_po se expone en el contexto (se muestra, no se compara).
        assertEquals("MEASURED", wide.context.pushOffProvenanceFirst.source)
        assertEquals("TROCANTER", wide.context.pushOffProvenanceFirst.landmark)
        assertEquals(1_700_000_000_000L, wide.context.pushOffProvenanceFirst.measuredAt)

        // Calidad por serie y recuento de condiciones, tambien con una serie diagnosticada.
        val diagnostic = fvpComparisonPolicy(
            storedSeries(id = 1L),
            storedSeries(id = 2L, warningsCsv = "NON_PHYSICAL"),
        )
        assertEquals(FvpQualityState.PROFILE, diagnostic.context.qualityFirst)
        assertEquals(FvpQualityState.DIAGNOSTIC, diagnostic.context.qualitySecond)
        assertEquals(
            listOf(FvpComparisonBlock.NOT_A_PROFILE),
            diagnostic.blocks,
        )
    }

    /**
     * DIFFERENT_MEASURE_METHODS reforzado: la ASOCIACION carga-metodo del intento seleccionado. El
     * mismo par de metodos repartido entre otras cargas ya no es el mismo experimento (C1 solo
     * comparaba el conjunto).
     */
    @Test
    fun perLoadMeasureMethodsAreComparedAsAnAssociation() {
        val first = storedSeries(id = 1L, methodByCondition = listOf("FLIGHT_TIME_VIDEO", "MANUAL", "FLIGHT_TIME_VIDEO", "MANUAL"))
        val sameAssociation = storedSeries(id = 2L, methodByCondition = listOf("FLIGHT_TIME_VIDEO", "MANUAL", "FLIGHT_TIME_VIDEO", "MANUAL"))
        val swapped = storedSeries(id = 3L, methodByCondition = listOf("MANUAL", "FLIGHT_TIME_VIDEO", "MANUAL", "FLIGHT_TIME_VIDEO"))

        assertTrue(
            "la misma asociacion carga-metodo es comparable",
            fvpComparisonPolicy(first, sameAssociation).canCompareDeltas,
        )
        val policy = fvpComparisonPolicy(first, swapped)
        assertFalse("el mismo conjunto de metodos repartido entre otras cargas bloquea", policy.canCompareDeltas)
        assertEquals(listOf(FvpComparisonBlock.DIFFERENT_MEASURE_METHODS), policy.blocks)
    }

    /**
     * Fila guardada minima: solo los campos que entran en la politica son parametrizables; el resto
     * son valores fijos de una serie fisica. La elegibilidad se deriva del recuento de condiciones
     * (4 condiciones, sin flags y con timing ALLOW es elegible para perfil) y `recomputable = false`
     * representa una fila que la version actual NO puede recalcular (sin elegibilidad).
     *
     * B4b: por defecto la procedencia es la VERIFICADA (misma adquisicion con la misma clave, masa
     * pesada, carga pesada y mapeo temporal verificado), de modo que el caso de referencia sigue
     * produciendo deltas; cada prueba declara la procedencia que necesita.
     */
    private fun storedSeries(
        id: Long,
        athleteId: Long? = 1L,
        protocolId: String = "SJ",
        pushOffMode: String = "COMMON",
        pushOffProtocol: String? = "LANDMARK_EXTENDED_MINUS_CROUCH",
        pushOffM: Double = 0.40,
        bodyMassKg: Double = 80.0,
        algorithmVersion: String = "fvp-poc-1.0",
        methodVersion: String = "fvp-app-1.0",
        conditionKeys: List<String> = listOf("0kg", "20kg", "40kg", "60kg"),
        /** Carga adicional por condicion; por defecto 0/20/40/60 kg segun el indice. */
        loads: List<Double>? = null,
        method: String = "FLIGHT_TIME_VIDEO",
        /** Metodo del intento seleccionado por condicion (override de [method]). */
        methodByCondition: List<String>? = null,
        /** h_po persistido por condicion (override de `PER_CONDITION`); por defecto, el de la raiz. */
        conditionPushOffM: List<Double>? = null,
        warningsCsv: String = "",
        vMinMps: Double = 0.8,
        vMaxMps: Double = 1.4,
        pushOffSource: String = "MEASURED",
        pushOffLandmark: String? = "TROCANTER",
        pushOffMeasuredAt: Long? = 1_700_000_000_000L,
        r2: Double? = 0.96,
        /** ΔF0/ΔPmax relativos persistidos; `null` representa una fila que NO los trae medibles. */
        f0RelNPerKg: Double? = 18.75,
        pmaxRelWPerKg: Double? = 10.04,
        recomputable: Boolean = true,
        /** Q2: calidad temporal agregada de la serie (no `ALLOW` = diagnostico). */
        timingQuality: FvpTimingQuality = FvpTimingQuality.ALLOW,
        /** Q2: estado del snapshot persistido; solo `MATCHES` permite deltas. */
        snapshotStatus: FvpSnapshotStatus = FvpSnapshotStatus.MATCHES,
        /**
         * B4b: contexto de adquisicion declarado. Por defecto `SAME_ACQUISITION` con la MISMA clave
         * (`ctx-a`) en las dos series: la procedencia VERIFICADA que una comparacion con deltas
         * exige. Las series de estos fixtures son comparables POR DEFECTO: cada prueba que necesita
         * procedencia desconocida la declara.
         */
        acquisitionContextKind: String = "SAME_ACQUISITION",
        acquisitionContextKey: String? = "ctx-a",
        bodyMassMeasurement: String = "WEIGHED",
        /** `addedLoadSource` por condicion; por defecto PESADA (`WEIGHED`). */
        addedLoadSources: List<String>? = null,
        /** `timeMapping` del intento seleccionado por condicion; por defecto verificado. */
        timeMappingByCondition: List<String>? = null,
        /**
         * `selectedAttemptOrdinal` por condicion; `null` = sin seleccion registrada (por defecto,
         * ordinal 1 en todas). Una lista explicita puede llevar `null` en una posicion.
         */
        selectedOrdinals: List<Int?>? = null,
        /**
         * B4b: anade a esa condicion un intento EXTRA no seleccionado (ordinal 2, no valido,
         * `timeMapping = UNKNOWN`): el peor intento SELECCIONADO debe decidir, no este.
         */
        extraUnverifiedAttemptAt: Int? = null,
    ): StoredFvpAssessment {
        val conditions = conditionKeys.mapIndexed { index, key ->
            val loadKg = loads?.getOrNull(index) ?: index * 20.0
            FvpConditionEntity(
                id = id * 10 + index,
                assessmentId = id,
                ordinal = index,
                conditionKey = key,
                addedLoadKg = loadKg,
                totalMassKg = bodyMassKg + loadKg,
                pushOffM = conditionPushOffM?.getOrNull(index) ?: pushOffM,
                selectedHeightM = 0.40 - index * 0.05,
                selectedAttemptOrdinal = if (selectedOrdinals == null) 1 else selectedOrdinals[index],
                addedLoadSource = addedLoadSources?.getOrNull(index) ?: "WEIGHED",
            )
        }
        val attempts = conditions.flatMapIndexed { index, condition ->
            val selected = FvpAttemptEntity(
                id = condition.id,
                conditionId = condition.id,
                ordinal = 1,
                heightM = condition.selectedHeightM,
                method = methodByCondition?.getOrNull(index) ?: method,
                timeMapping = timeMappingByCondition?.getOrNull(index) ?: "IDENTITY_VERIFIED",
                timeMappingEvidenceKey = "ev-$index",
            )
            if (index == extraUnverifiedAttemptAt) {
                listOf(
                    selected,
                    FvpAttemptEntity(
                        id = condition.id * 100,
                        conditionId = condition.id,
                        ordinal = 2,
                        heightM = condition.selectedHeightM - 0.01,
                        method = "MANUAL",
                        valid = false,
                        timeMapping = "UNKNOWN",
                    ),
                )
            } else {
                listOf(selected)
            }
        }
        return StoredFvpAssessment(
            assessment = FvpAssessmentEntity(
                id = id,
                sessionKey = "session-$id",
                athleteId = athleteId,
                protocolId = protocolId,
                dateTime = 1_700_000_000_000L,
                bodyMassKg = bodyMassKg,
                bodyMassSource = "MANUAL",
                pushOffMode = pushOffMode,
                pushOffM = pushOffM,
                pushOffSource = pushOffSource,
                pushOffLandmark = pushOffLandmark,
                pushOffProtocol = pushOffProtocol,
                pushOffMeasuredAt = pushOffMeasuredAt,
                algorithmVersion = algorithmVersion,
                methodVersion = methodVersion,
                f0N = 1500.0,
                slopeFv = -700.0,
                sfvNsPerM = 700.0,
                v0Mps = 2.14,
                pmaxW = 803.0,
                r2 = r2,
                conditionCount = conditions.size,
                vMinMps = vMinMps,
                vMaxMps = vMaxMps,
                fMinN = 1200.0,
                fMaxN = 1700.0,
                f0RelNPerKg = f0RelNPerKg,
                pmaxRelWPerKg = pmaxRelWPerKg,
                warningsCsv = warningsCsv,
                acquisitionContextKind = acquisitionContextKind,
                acquisitionContextKey = acquisitionContextKey,
                bodyMassMeasurement = bodyMassMeasurement,
            ),
            conditions = conditions,
            attempts = attempts,
            eligibility = if (recomputable) {
                FvpEligibility(
                    conditionCount = conditions.size,
                    blocking = emptyList(),
                    flags = emptyList(),
                    timingQuality = timingQuality,
                )
            } else {
                null
            },
            result = null,
            snapshotStatus = snapshotStatus,
        )
    }
}
