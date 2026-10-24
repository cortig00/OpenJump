package com.openjump.app.ui.fvp

import com.openjump.app.data.FvpAssessmentEntity
import com.openjump.app.data.FvpAssessmentWithData
import com.openjump.app.data.FvpAttemptEntity
import com.openjump.app.data.FvpConditionEntity
import com.openjump.app.data.FvpDao
import com.openjump.app.data.FvpRepository
import com.openjump.app.data.JumpDao
import com.openjump.app.data.JumpRepository
import com.openjump.app.data.PersistableFvpAssessment
import com.openjump.app.data.StoredFvpAssessment
import com.openjump.app.data.FvpSnapshotStatus
import com.openjump.app.data.evaluateSeries
import com.openjump.app.data.parseWarningsCsv
import com.openjump.app.data.toEligibilitySeriesInput
import com.openjump.app.fvp.FvpEligibility
import com.openjump.app.fvp.FvpEligibilityFlag
import com.openjump.app.fvp.FvpEligibilityProblem
import com.openjump.app.fvp.FvpEngine
import com.openjump.app.fvp.FvpPushOffMode
import com.openjump.app.fvp.FvpQualityState
import com.openjump.app.fvp.FvpTimingQuality
import com.openjump.app.fvp.checkSeries
import com.openjump.app.fvp.selectedConditionInputs
import com.openjump.app.fvp.selectedValidAttemptIndex
import com.openjump.app.settings.UnitProfile
import com.openjump.fvp.FvpConditionInput
import com.openjump.fvp.FvpConditionPoint
import com.openjump.fvp.FvpInputError
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProfile
import com.openjump.fvp.FvpProtocol
import com.openjump.fvp.FvpResult
import com.openjump.fvp.FvpWarning
import com.openjump.fvp.MAX_PUSH_OFF_M
import com.openjump.fvp.MIN_PUSH_OFF_M
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Locale
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
 * Contrato del presentador puro F-V-P: NaN -> null (nunca 0.0 sustituto), avisos deterministas sin
 * duplicados, `R2_UNDEFINED` sustituye a `LOW_R2`, `diagnosticOnly` con 2-3 condiciones,
 * `profileEligible` solo con elegibilidad y timing `ALLOW`, y puntos ordenados por V ascendente.
 *
 * S2 (plan 13, D3): una vista = UNA ejecucion. La vista `ORIGINAL` de un guardado son los valores
 * PERSISTIDOS (sin puntos) y la de `RECALCULATION` son los del motor actual (con puntos); ninguna
 * mezcla valores de la otra.
 */
class FvpPresenterTest {

    @Test
    fun nonPhysicalProfileKeepsV0AndPmaxNullNeverZero() {
        val profile = profile(v0 = Double.NaN, pmax = Double.NaN, warnings = listOf(FvpWarning.NON_PHYSICAL))
        val model = FvpPresenter.present(
            result = result(profile),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = profile.n,
            timingQuality = FvpTimingQuality.ALLOW,
        )
        assertNull(model.v0Mps)
        assertNull(model.pmaxW)
        assertFalse(model.hasPhysicalIntercepts)
        assertTrue(FvpUiWarning.NON_PHYSICAL in model.warnings)
        // F0/Sfv siguen siendo finitos: el modelo los publica, los interceptos no.
        assertEquals(1500.0, model.f0N, 0.0)
        assertEquals(700.0, model.sfvNsPerM, 0.0)
    }

    @Test
    fun undefinedR2ReplacesLowR2Warning() {
        val profile = profile(r2 = Double.NaN, warnings = listOf(FvpWarning.LOW_R2))
        val model = FvpPresenter.present(
            result = result(profile),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = profile.n,
            timingQuality = FvpTimingQuality.ALLOW,
        )
        assertNull(model.r2)
        assertTrue(FvpUiWarning.R2_UNDEFINED in model.warnings)
        assertFalse(FvpUiWarning.LOW_R2 in model.warnings)
    }

    @Test
    fun finiteR2KeepsLowR2AndNeverAddsUndefined() {
        val profile = profile(r2 = 0.72, warnings = listOf(FvpWarning.LOW_R2))
        val model = FvpPresenter.present(
            result = result(profile),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = profile.n,
            timingQuality = FvpTimingQuality.ALLOW,
        )
        assertEquals(0.72, model.r2!!, 0.0)
        assertTrue(FvpUiWarning.LOW_R2 in model.warnings)
        assertFalse(FvpUiWarning.R2_UNDEFINED in model.warnings)
    }

    @Test
    fun warningsAreDeterministicAndDeduplicated() {
        val profile = profile(
            warnings = listOf(FvpWarning.FEW_POINTS, FvpWarning.NARROW_VELOCITY_RANGE, FvpWarning.LOW_R2),
        )
        val model = FvpPresenter.present(
            result = result(profile),
            eligibility = eligibility(
                flags = listOf(
                    FvpEligibilityFlag.FEW_CONDITIONS,
                    FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE,
                    FvpEligibilityFlag.TIMING_CONDITIONAL,
                ),
                timing = FvpTimingQuality.CONDITIONAL,
            ),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 3,
            timingQuality = FvpTimingQuality.CONDITIONAL,
            storedWarnings = listOf("FEW_POINTS", "NARROW_VELOCITY_RANGE"),
        )
        val ordinals = model.warnings.map { it.ordinal }
        assertEquals(ordinals.sorted(), ordinals)
        assertEquals(ordinals.distinct(), ordinals)
        assertTrue(FvpUiWarning.FEW_POINTS in model.warnings)
        assertTrue(FvpUiWarning.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE in model.warnings)
        assertTrue(FvpUiWarning.TIMING_CONDITIONAL in model.warnings)
    }

    @Test
    fun diagnosticOnlyForTwoAndThreeConditionsOnly() {
        listOf(2, 3).forEach { count ->
            val profile = profile(n = count)
            val model = FvpPresenter.present(
                result = result(profile),
                eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
                protocol = FvpProtocol.SJ,
                bodyMassKg = 80.0,
                conditionCount = count,
                timingQuality = FvpTimingQuality.ALLOW,
            )
            assertEquals(count, model.conditionCount)
            assertTrue("n=$count", model.diagnosticOnly)
        }
        val profile = profile(n = 4)
        val model = FvpPresenter.present(
            result = result(profile),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
        )
        assertFalse(model.diagnosticOnly)
    }

    @Test
    fun profileEligibleRequiresEligibilityAndAllowTiming() {
        val profile = profile()
        val allow = FvpPresenter.present(
            result = result(profile),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = profile.n,
            timingQuality = FvpTimingQuality.ALLOW,
        )
        assertTrue(allow.profileEligible)

        val conditional = FvpPresenter.present(
            result = result(profile),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.CONDITIONAL),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = profile.n,
            timingQuality = FvpTimingQuality.CONDITIONAL,
        )
        assertFalse(conditional.profileEligible)
        assertTrue(FvpUiWarning.TIMING_CONDITIONAL in conditional.warnings)
    }

    @Test
    fun pointsAreSortedByVelocityAndSkipNonFiniteValues() {
        val profile = profile()
        val points = listOf(
            point(id = "20kg", velocity = 1.10, force = 1420.0, power = 1562.0, height = 0.28),
            point(id = "0kg", velocity = 1.45, force = 1710.0, power = 2480.0, height = 0.36),
            point(id = "40kg", velocity = 0.78, force = 1180.0, power = 920.0, height = 0.19),
            FvpConditionPoint("60kg", 140.0, 0.1, 0.4, Double.NaN, 0.5, 70.0),
        )
        val model = FvpPresenter.present(
            result = result(profile, points),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = points.size,
            timingQuality = FvpTimingQuality.ALLOW,
            attemptByCondition = mapOf(
                "0kg" to FvpAttemptUi(0.36, FvpMeasureMethod.FLIGHT_TIME_VIDEO, 240.0, valid = true),
                "20kg" to FvpAttemptUi(0.28, FvpMeasureMethod.MANUAL, null, valid = true),
            ),
        )
        assertEquals(listOf("40kg", "20kg", "0kg"), model.points.map { it.label })
        // `measured` marca los puntos dentro de [Vmin,Vmax] = [0.8, 1.4] m/s del perfil.
        assertEquals(listOf(false, true, false), model.points.map { it.measured })
        val first = model.points.first()
        assertNull(first.method)
        assertEquals(FvpMeasureMethod.MANUAL, model.points[1].method)
        assertEquals(FvpMeasureMethod.FLIGHT_TIME_VIDEO, model.points[2].method)
        assertEquals(240.0, model.points[2].sourceFps!!, 0.0)
    }

    @Test
    fun storedSnapshotValuesWinAndSnapshotWarningComesFromStatus() {
        val snapshot = FvpStoredSnapshot(
            f0N = 1600.0,
            slopeFv = -520.0,
            sfvNsPerM = 520.0,
            v0Mps = 3.07,
            pmaxW = 1230.0,
            r2 = 0.94,
            conditionCount = 4,
            vMinMps = 0.8,
            vMaxMps = 1.4,
            fMinN = 1200.0,
            fMaxN = 1700.0,
            f0RelNPerKg = 20.0,
            pmaxRelWPerKg = 15.4,
        )
        val recalculated = profile(f0 = 1500.0, sfv = 700.0)
        val mismatched = FvpPresenter.present(
            result = result(recalculated),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
            storedSnapshot = snapshot,
            storedSnapshotStatus = FvpSnapshotStatus.MISMATCH,
            algorithmVersion = "fvp-poc-1.0",
        )
        // Los valores mostrados son los del snapshot persistido, no el recalculo.
        assertEquals(1600.0, mismatched.f0N, 0.0)
        assertEquals(3.07, mismatched.v0Mps!!, 0.0)
        assertEquals("fvp-poc-1.0", mismatched.algorithmVersion)
        assertEquals(false, mismatched.storedSnapshotMatches)
        assertTrue(FvpUiWarning.SNAPSHOT_RECALCULATED in mismatched.warnings)
        assertFalse(FvpUiWarning.SNAPSHOT_UNAVAILABLE in mismatched.warnings)

        val matching = FvpPresenter.present(
            result = result(recalculated),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
            storedSnapshot = snapshot,
            storedSnapshotStatus = FvpSnapshotStatus.MATCHES,
            algorithmVersion = "fvp-poc-1.0",
        )
        assertEquals(true, matching.storedSnapshotMatches)
        assertFalse(FvpUiWarning.SNAPSHOT_RECALCULATED in matching.warnings)
        assertFalse(FvpUiWarning.SNAPSHOT_UNAVAILABLE in matching.warnings)

        val stale = FvpPresenter.present(
            result = result(recalculated),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
            storedSnapshot = snapshot,
            storedSnapshotStatus = FvpSnapshotStatus.STALE,
            algorithmVersion = "fvp-poc-1.0",
        )
        assertNull(stale.storedSnapshotMatches)
        assertTrue(FvpUiWarning.SNAPSHOT_RECALCULATED in stale.warnings)
        assertFalse(FvpUiWarning.SNAPSHOT_UNAVAILABLE in stale.warnings)
    }

    @Test
    fun missingResultKeepsNullableFieldsNullAndReportsUnknownBodyMassAsNaNDefault() {
        val model = FvpPresenter.present(
            result = null,
            eligibility = FvpEligibility(
                conditionCount = 2,
                blocking = listOf(FvpEligibilityProblem.MISSING_PUSH_OFF),
                flags = listOf(FvpEligibilityFlag.DIAGNOSTIC_ONLY_TWO_CONDITIONS),
                timingQuality = FvpTimingQuality.UNKNOWN,
            ),
            protocol = FvpProtocol.CMJ,
            bodyMassKg = null,
            conditionCount = 2,
            timingQuality = FvpTimingQuality.UNKNOWN,
        )
        assertNull(model.v0Mps)
        assertNull(model.pmaxW)
        assertNull(model.r2)
        assertNull(model.storedSnapshotMatches)
        assertTrue(model.points.isEmpty())
        assertTrue(FvpUiWarning.DIAGNOSTIC_ONLY in model.warnings)
        assertTrue(FvpUiWarning.TIMING_UNKNOWN in model.warnings)
        assertFalse(FvpUiWarning.R2_UNDEFINED in model.warnings)
        assertFalse(model.profileEligible)
        assertEquals(FvpProtocol.CMJ, model.protocolLabelKey)
    }

    /**
     * Los nombres persistidos se mapean a avisos de UI y los desconocidos se ignoran. S2 (plan 13):
     * esa lista es el veredicto de la EJECUCION ORIGINAL, de modo que se declara en la vista
     * `ORIGINAL` de una fila guardada (nunca sin snapshot: un borrador vivo no tiene veredictos
     * persistidos que mostrar).
     */
    @Test
    fun storedWarningNamesMapToUiWarningsAndUnknownNamesAreIgnored() {
        val model = FvpPresenter.present(
            result = result(profile()),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
            storedWarnings = listOf(
                "FPS_30_BLOCKED",
                "TIMING_UNKNOWN_NOT_ELIGIBLE",
                "PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE",
                "NO_EXISTE",
            ),
            storedSnapshot = storedSnapshotFixture(),
            storedSnapshotStatus = FvpSnapshotStatus.MATCHES,
            algorithmVersion = "fvp-poc-1.0",
            view = FvpResultView.ORIGINAL,
        )
        assertTrue(FvpUiWarning.FPS_BLOCKED in model.warnings)
        assertTrue(FvpUiWarning.TIMING_UNKNOWN in model.warnings)
        assertTrue(FvpUiWarning.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE in model.warnings)
        assertEquals(model.warnings.distinct(), model.warnings)
    }

    // --- P1-1: requisito B1 de h_po medido -----------------------------------------------------

    /**
     * Una serie con origen `MANUAL` (h_po sin hito/procedimiento/fecha) recibe SIEMPRE el aviso
     * dedicado; `MEASURED` no lo recibe y un origen desconocido no inventa el aviso.
     */
    @Test
    fun manualPushOffSourceAddsDedicatedWarning() {
        val manual = FvpPresenter.present(
            result = result(profile()),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
            pushOffSource = FVP_PUSH_OFF_SOURCE_MANUAL,
        )
        assertTrue(FvpUiWarning.PUSH_OFF_MANUAL_SOURCE in manual.warnings)

        val measured = FvpPresenter.present(
            result = result(profile()),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
            pushOffSource = FVP_PUSH_OFF_SOURCE_MEASURED,
        )
        assertFalse(FvpUiWarning.PUSH_OFF_MANUAL_SOURCE in measured.warnings)

        val unknown = FvpPresenter.present(
            result = result(profile()),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
        )
        assertFalse(FvpUiWarning.PUSH_OFF_MANUAL_SOURCE in unknown.warnings)
    }

    /**
     * Regla UNICA de procedencia B1: `MEASURED` exige hito + procedimiento + fecha; una fila
     * persistida que se declare `MEASURED` sin metadatos verificables se degrada a `MANUAL`, y una
     * declaracion `MANUAL` nunca se promociona.
     */
    @Test
    fun effectivePushOffSourceRequiresFullMetadataAndHonoursManual() {
        val measuredAt = 1_700_000_000_000L
        // Borrador vivo con los tres metadatos: medido.
        assertEquals(
            FVP_PUSH_OFF_SOURCE_MEASURED,
            fvpEffectivePushOffSource(null, "TROCANTER", "LANDMARK_EXTENDED_MINUS_CROUCH", measuredAt),
        )
        // Sin fecha (o sin hito, o sin procedimiento): manual, aunque se declare medido.
        assertEquals(
            FVP_PUSH_OFF_SOURCE_MANUAL,
            fvpEffectivePushOffSource("MEASURED", "TROCANTER", "LANDMARK_EXTENDED_MINUS_CROUCH", null),
        )
        assertEquals(
            FVP_PUSH_OFF_SOURCE_MANUAL,
            fvpEffectivePushOffSource("MEASURED", "  ", "LANDMARK_EXTENDED_MINUS_CROUCH", measuredAt),
        )
        assertEquals(
            FVP_PUSH_OFF_SOURCE_MANUAL,
            fvpEffectivePushOffSource("MEASURED", "TROCANTER", null, measuredAt),
        )
        // Declarado manual con metadatos: se respeta la declaracion (nunca se promociona solo).
        assertEquals(
            FVP_PUSH_OFF_SOURCE_MANUAL,
            fvpEffectivePushOffSource("MANUAL", "TROCANTER", "LANDMARK_EXTENDED_MINUS_CROUCH", measuredAt),
        )
    }

    /** El historial reutiliza el mismo requisito B1 sobre la columna persistida `pushOffSource`. */
    @Test
    fun storedWarningsIncludeManualPushOffSourceWithoutDuplicates() {
        val manual = storedWarningsForUi(listOf("FPS_30_BLOCKED", "NON_EXISTE"), "MANUAL")
        assertTrue(FvpUiWarning.FPS_BLOCKED in manual)
        assertTrue(FvpUiWarning.PUSH_OFF_MANUAL_SOURCE in manual)
        assertEquals(manual.distinct(), manual)
        assertEquals(manual.sortedBy { it.ordinal }, manual)

        val measured = storedWarningsForUi(listOf("FPS_30_BLOCKED"), "MEASURED")
        assertFalse(FvpUiWarning.PUSH_OFF_MANUAL_SOURCE in measured)
        assertEquals(listOf(FvpUiWarning.FPS_BLOCKED), measured)
    }

    /**
     * Q2: un nombre persistido que esta version NO clasifica no se elimina en silencio de la lista
     * de avisos: se declara como limitacion generica (nunca como "sin problemas").
     */
    @Test
    fun unclassifiedStoredWarningIsVisibleAsAGenericLimitation() {
        val warnings = storedWarningsForUi(listOf("FUTURE_ENGINE_WARNING"), "MEASURED")

        assertEquals("un aviso sin clasificar no se elimina en silencio", 1, warnings.size)
        assertEquals(listOf(FvpUiWarning.UNCLASSIFIED_WARNING), warnings)
        assertEquals(
            "tambien con otros avisos reconocidos al lado, ordenado por ordinal",
            listOf(FvpUiWarning.FPS_BLOCKED, FvpUiWarning.UNCLASSIFIED_WARNING),
            storedWarningsForUi(listOf("FUTURE_ENGINE_WARNING", "FPS_30_BLOCKED"), "MEASURED"),
        )
    }

    /**
     * Q2: TODO nombre persistible actual (avisos del motor + flags de elegibilidad) esta RECONOCIDO:
     * ninguno se declara como "sin clasificar". `MIXED_SELECTED_METHODS` sigue sin etiqueta propia
     * (no hay copia que lo describa sin mentir), pero NUNCA cae en el aviso generico.
     */
    @Test
    fun recognizedPersistedWarningsAreNeverReportedAsUnclassified() {
        val names = FvpWarning.entries.map { it.name } + FvpEligibilityFlag.entries.map { it.name }

        names.distinct().forEach { name ->
            val warnings = storedWarningsForUi(listOf(name), "MEASURED")
            assertFalse(
                "$name esta reconocido y no puede declararse sin clasificar",
                FvpUiWarning.UNCLASSIFIED_WARNING in warnings,
            )
        }
        assertTrue(
            "el flag de mezcla sigue sin etiqueta propia (reconocido, no desconocido)",
            FvpUiWarning.UNCLASSIFIED_WARNING !in
                storedWarningsForUi(listOf("MIXED_SELECTED_METHODS"), "MEASURED"),
        )
    }

    /**
     * Q2 (P3, endurecimiento): TODO nombre persistible actual (avisos del motor + flags de
     * elegibilidad) o bien MAPEA a un aviso de UI, o bien esta en la exencion EXPLICITA (hoy un
     * unico nombre: `MIXED_SELECTED_METHODS`). Anadir un valor nuevo a cualquiera de los dos enums
     * hace fallar este test hasta que se le de etiqueta o se le declare exento a mano: ningun aviso
     * del dominio puede quedarse sin etiqueta por olvido (el reconocimiento ya NO se deriva de los
     * enums: un nombre futuro sin mapeo cae en el aviso generico y se VE).
     */
    @Test
    fun everyPersistedEnumNameMapsToUiOrIsExplicitlyExempt() {
        val names = FvpWarning.entries.map { it.name } + FvpEligibilityFlag.entries.map { it.name }
        assertTrue("la lista de nombres persistibles no puede estar vacia", names.isNotEmpty())

        val exempt = setOf(FvpEligibilityFlag.MIXED_SELECTED_METHODS.name)
        assertEquals("la exencion sin etiqueta es un UNICO nombre explicito", 1, exempt.size)
        assertTrue("la exencion tiene que ser un nombre persistible real", exempt.all { it in names })

        names.distinct().forEach { name ->
            val mapped = storedWarningToUi(name)
            if (name in exempt) {
                assertNull("$name esta exento a proposito y no lleva etiqueta propia", mapped)
            } else {
                assertNotNull(
                    "$name debe mapear a un aviso de UI o estar en la exencion explicita",
                    mapped,
                )
            }
            assertFalse(
                "$name esta reconocido y no puede declararse sin clasificar",
                FvpUiWarning.UNCLASSIFIED_WARNING in storedWarningsForUi(listOf(name), "MEASURED"),
            )
        }
    }

    // --- C2 (plan 13, D5): masa y valor de h_po en COMMON pasan a CONTEXTO ----------------------

    /**
     * C1 exigia la MISMA masa corporal y la MISMA distancia de impulsión para comparar. C2 las
     * mueve a CONTEXTO: la comparacion sigue siendo posible y lo que cambia es como se lee. Se
     * comprueba en los DOS sentidos (identico -> comparable sin diferencia de contexto; distinto ->
     * comparable con la diferencia visible) para no sobre-relajar ni sobre-bloquear.
     */
    @Test
    fun comparisonShowsBodyMassAndCommonPushOffValueAsContext() {
        val base = storedSeries(id = 1L)
        val identical = fvpComparisonPolicy(base, storedSeries(id = 2L))
        assertTrue("misma masa y mismo h_po deben seguir siendo comparables", identical.canCompareDeltas)
        assertEquals(emptyList<FvpComparisonBlock>(), identical.blocks)
        assertTrue(identical.sameExactAlgorithmVersion)
        assertFalse("sin diferencia de masa el contexto no la declara", identical.context.massDiffers)

        val differentPushOff = fvpComparisonPolicy(base, storedSeries(id = 3L, pushOffM = 0.45))
        assertTrue(
            "en COMMON el valor de h_po ya NO bloquea: es contexto",
            differentPushOff.canCompareDeltas,
        )
        assertEquals(emptyList<FvpComparisonBlock>(), differentPushOff.blocks)
        assertEquals(
            "la diferencia de h_po queda visible como contexto (segunda - primera)",
            0.05,
            differentPushOff.context.pushOffM?.delta ?: Double.NaN,
            1e-9,
        )

        val differentMass = fvpComparisonPolicy(base, storedSeries(id = 4L, bodyMassKg = 82.5))
        assertTrue("distinta masa corporal ya NO bloquea: es contexto", differentMass.canCompareDeltas)
        assertEquals(emptyList<FvpComparisonBlock>(), differentMass.blocks)
        assertTrue("la diferencia de masa se declara en el contexto", differentMass.context.massDiffers)
        assertEquals(2.5, differentMass.context.bodyMassKg.delta ?: Double.NaN, 1e-9)
        assertTrue(
            "con masa distinta los deltas primarios son los relativos",
            differentMass.context.relativeDeltasArePrimary,
        )
    }

    /**
     * Las condiciones que la regla YA exigia no se relajan al mover masa/h_po a contexto:
     * protocolo, modo de h_po, PROCEDIMIENTO declarado, asociacion carga-metodo y familia de version.
     */
    @Test
    fun comparisonKeepsRequiringProtocolPushOffModeProcedureAndMethodSet() {
        val base = storedSeries(id = 1L)
        assertTrue(
            "distinto protocolo",
            FvpComparisonBlock.DIFFERENT_PROTOCOL in
                fvpComparisonPolicy(base, storedSeries(id = 2L, protocolId = "CMJ")).blocks,
        )
        assertTrue(
            "distinto modo de h_po",
            FvpComparisonBlock.DIFFERENT_PROTOCOL in
                fvpComparisonPolicy(base, storedSeries(id = 3L, pushOffMode = "PER_CONDITION")).blocks,
        )
        assertTrue(
            "distinto procedimiento de h_po",
            FvpComparisonBlock.DIFFERENT_PUSH_OFF_PROCEDURE in
                fvpComparisonPolicy(base, storedSeries(id = 4L, pushOffProtocol = "OTRO_PROCEDIMIENTO")).blocks,
        )
        assertFalse(
            "el VALOR de h_po ya no bloquea en COMMON (C2)",
            FvpComparisonBlock.DIFFERENT_PUSH_OFF_PROCEDURE in
                fvpComparisonPolicy(base, storedSeries(id = 7L, pushOffM = 0.45)).blocks,
        )
        assertTrue(
            "distinto conjunto de metodos de medida",
            FvpComparisonBlock.DIFFERENT_MEASURE_METHODS in
                fvpComparisonPolicy(base, storedSeries(id = 5L, method = "MANUAL")).blocks,
        )
        assertFalse(
            "otra familia de version del algoritmo",
            fvpComparisonPolicy(base, storedSeries(id = 6L, algorithmVersion = "otra-1.0")).canCompareDeltas,
        )
    }

    // --- W3.3 (R1, Wave 2b): h_po por CONDICION, no solo la referencia declarada -----------------

    /**
     * En `PER_CONDITION` cada condicion aporta su propio h_po al punto que genera, asi que la
     * referencia declarada (`pushOffM`) NO basta: dos series con la misma referencia y overrides por
     * condicion distintos siguen produciendo numeros no comparables.
     *
     * C2 (bisagra del h_po): en `PER_CONDITION` la relajacion NO aplica. Se comparan los pares
     * `(carga, h_po)` ordenados y canonicalizados a la rejilla de 0,5 cm, de modo que un override
     * distinto bloquea con [FvpComparisonBlock.DIFFERENT_PER_LOAD_PUSH_OFF]. El orden de las
     * condiciones no importa (la comparacion ordena por carga y despues por h_po).
     */
    @Test
    fun comparisonRequiresTheSamePerConditionPushOffMultiset() {
        val overrides = listOf(0.46, 0.42, 0.38, 0.34)
        val base = storedSeries(id = 1L, pushOffMode = "PER_CONDITION", conditionPushOffM = overrides)

        val identical = fvpComparisonPolicy(
            base,
            storedSeries(id = 2L, pushOffMode = "PER_CONDITION", conditionPushOffM = overrides),
        )
        assertTrue("mismos overrides por condicion deben seguir siendo comparables", identical.canCompareDeltas)

        val differentOverride = fvpComparisonPolicy(
            base,
            storedSeries(
                id = 3L,
                pushOffMode = "PER_CONDITION",
                conditionPushOffM = listOf(0.46, 0.42, 0.38, 0.36),
            ),
        )
        assertFalse(
            "misma referencia con overrides por condicion distintos no es comparable",
            differentOverride.canCompareDeltas,
        )
        assertEquals(listOf(FvpComparisonBlock.DIFFERENT_PER_LOAD_PUSH_OFF), differentOverride.blocks)

        // Un unico override distinto (el resto identico) ya invalida la comparacion.
        val oneDifferent = fvpComparisonPolicy(
            base,
            storedSeries(
                id = 4L,
                pushOffMode = "PER_CONDITION",
                conditionPushOffM = listOf(0.46, 0.42, 0.38, 0.33),
            ),
        )
        assertFalse(oneDifferent.canCompareDeltas)
    }

    /**
     * P1 (Wave 4a): el multiconjunto de h_po no basta. Intercambiar los h_po ENTRE cargas (mismo
     * multiconjunto: `[.46,.42,.38,.34]` vs `[.34,.38,.42,.46]`) produce otros puntos F-V (cada
     * carga cambia de h_po), de modo que la comparacion debe rechazarse.
     */
    @Test
    fun comparisonRefusesPerConditionPushOffsSwappedAcrossLoads() {
        val overrides = listOf(0.46, 0.42, 0.38, 0.34)
        val base = storedSeries(id = 1L, pushOffMode = "PER_CONDITION", conditionPushOffM = overrides)
        val swapped = storedSeries(
            id = 2L,
            pushOffMode = "PER_CONDITION",
            conditionPushOffM = overrides.reversed(),
        )

        val policy = fvpComparisonPolicy(base, swapped)
        assertFalse(
            "el mismo multiconjunto con otra asociacion carga-h_po no es comparable",
            policy.canCompareDeltas,
        )
        assertEquals(listOf(FvpComparisonBlock.DIFFERENT_PER_LOAD_PUSH_OFF), policy.blocks)
    }

    /**
     * La comparacion por pares `(carga, h_po)` es independiente del ORDEN de las condiciones: la
     * misma serie con las condiciones en otro orden (cada carga viaja con SU h_po) sigue siendo
     * comparable. Es la contraparte del caso permutado entre cargas.
     */
    @Test
    fun comparisonAcceptsTheSameLoadPushOffPairsInDifferentConditionOrder() {
        val base = storedSeries(
            id = 1L,
            pushOffMode = "PER_CONDITION",
            conditionPushOffM = listOf(0.46, 0.42, 0.38, 0.34),
        )
        val reordered = storedSeries(
            id = 2L,
            pushOffMode = "PER_CONDITION",
            conditionKeys = listOf("60kg", "0kg", "40kg", "20kg"),
            loads = listOf(60.0, 0.0, 40.0, 20.0),
            conditionPushOffM = listOf(0.34, 0.46, 0.38, 0.42),
        )

        assertTrue(
            "los mismos pares carga-h_po en otro orden deben ser comparables",
            fvpComparisonPolicy(base, reordered).canCompareDeltas,
        )
    }

    /**
     * C2: la cobertura de CARGAS se compara como lista CON multiplicidad y bloquea con su propio
     * motivo. Vale tambien en `COMMON`, donde el par de cada condicion es `(carga, referencia
     * comun)`: otra lista de cargas no es el mismo experimento.
     */
    @Test
    fun comparisonRequiresTheSameLoadCoverageEvenInCommonMode() {
        val base = storedSeries(id = 1L)
        val otherLoads = storedSeries(
            id = 2L,
            conditionKeys = listOf("0kg", "25kg", "50kg", "75kg"),
            loads = listOf(0.0, 25.0, 50.0, 75.0),
        )

        val policy = fvpComparisonPolicy(base, otherLoads)
        assertFalse("otra lista de cargas no es el mismo experimento", policy.canCompareDeltas)
        assertEquals(listOf(FvpComparisonBlock.DIFFERENT_LOAD_COVERAGE), policy.blocks)
        assertTrue(
            "la cobertura manda: no se anaden motivos de asociacion con los mismos metodos",
            FvpComparisonBlock.DIFFERENT_MEASURE_METHODS !in policy.blocks,
        )
    }

    /**
     * En `COMMON` el h_po efectivo de toda condicion es la referencia declarada. C2 deja de exigir
     * que las dos referencias COINCIDAN: la diferencia se muestra como contexto (con la frase de
     * acoplamiento en la UI), de modo que dos series con h_po distinto siguen siendo comparables.
     */
    @Test
    fun comparisonOfCommonSeriesKeepsDeltasWithDifferentDeclaredReference() {
        val base = storedSeries(id = 1L)
        assertTrue(
            "en COMMON el mismo h_po comun sigue siendo comparable",
            fvpComparisonPolicy(base, storedSeries(id = 2L)).canCompareDeltas,
        )
        val differentReference = fvpComparisonPolicy(base, storedSeries(id = 3L, pushOffM = 0.44))
        assertTrue(
            "otra referencia comun es CONTEXTO en C2, no un bloqueo",
            differentReference.canCompareDeltas,
        )
        assertEquals(
            "la diferencia de h_po se conserva en el contexto (en metros)",
            0.04,
            differentReference.context.pushOffM?.delta ?: Double.NaN,
            1e-9,
        )
    }

    // --- W3.1: el estado del snapshot del repositorio llega a la UI ----------------------------

    /**
     * `FvpSnapshotStatus` se traduce en `snapshotMatches` (`MATCHES` -> true, `MISMATCH` -> false,
     * `STALE`/`STALE_METHOD`/`RECALCULATION_UNAVAILABLE` -> null) y el presentador traduce el
     * ESTADO en un unico aviso: `SNAPSHOT_RECALCULATED` cuando el recalculo EXISTE y no coincide
     * (numeros o version), `SNAPSHOT_UNAVAILABLE` cuando NO hubo recalculo, y ninguno en `MATCHES`.
     *
     * Tabla exigida por el contrato; los estados son mutuamente excluyentes, de modo que cada
     * fila declara tambien lo que NO debe aparecer.
     */
    @Test
    fun snapshotStatusDrivesTheRecalculatedWarning() {
        fun statusOf(status: FvpSnapshotStatus): Boolean? =
            storedSeries(id = 9L).copy(snapshotStatus = status).snapshotMatches

        // Mapeo de los CINCO estados: solo MATCHES/MISMATCH declaran coincidencia (true/false).
        assertEquals(true, statusOf(FvpSnapshotStatus.MATCHES))
        assertEquals(false, statusOf(FvpSnapshotStatus.MISMATCH))
        assertNull(statusOf(FvpSnapshotStatus.STALE))
        assertNull(statusOf(FvpSnapshotStatus.STALE_METHOD))
        assertNull(statusOf(FvpSnapshotStatus.RECALCULATION_UNAVAILABLE))

        val models = FvpSnapshotStatus.values().associateWith { status ->
                FvpPresenter.present(
                    result = result(profile()),
                    eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
                    protocol = FvpProtocol.SJ,
                    bodyMassKg = 80.0,
                    conditionCount = 4,
                    timingQuality = FvpTimingQuality.ALLOW,
                    storedSnapshot = storedSnapshotFixture(),
                    storedSnapshotStatus = status,
                    algorithmVersion = "fvp-poc-1.0",
                )
            }

        // El presentador deriva `snapshotMatches` con la MISMA tabla que el repositorio.
        FvpSnapshotStatus.values().forEach { status ->
            assertEquals("snapshotMatches de $status", statusOf(status), models.getValue(status).storedSnapshotMatches)
        }

        // MATCHES: coincide de verdad -> ningun aviso de snapshot.
        assertFalse(FvpUiWarning.SNAPSHOT_RECALCULATED in models.getValue(FvpSnapshotStatus.MATCHES).warnings)
        assertFalse(FvpUiWarning.SNAPSHOT_UNAVAILABLE in models.getValue(FvpSnapshotStatus.MATCHES).warnings)

        // MISMATCH: el recalculo existe y no coincide -> RECALCULATED, nunca UNAVAILABLE.
        assertTrue(FvpUiWarning.SNAPSHOT_RECALCULATED in models.getValue(FvpSnapshotStatus.MISMATCH).warnings)
        assertFalse(FvpUiWarning.SNAPSHOT_UNAVAILABLE in models.getValue(FvpSnapshotStatus.MISMATCH).warnings)

        // STALE / STALE_METHOD: el recalculo existe, la version difiere -> RECALCULATED.
        assertTrue(FvpUiWarning.SNAPSHOT_RECALCULATED in models.getValue(FvpSnapshotStatus.STALE).warnings)
        assertFalse(FvpUiWarning.SNAPSHOT_UNAVAILABLE in models.getValue(FvpSnapshotStatus.STALE).warnings)
        assertTrue(FvpUiWarning.SNAPSHOT_RECALCULATED in models.getValue(FvpSnapshotStatus.STALE_METHOD).warnings)
        assertFalse(FvpUiWarning.SNAPSHOT_UNAVAILABLE in models.getValue(FvpSnapshotStatus.STALE_METHOD).warnings)

        // RECALCULATION_UNAVAILABLE: NO hubo recalculo -> jamas se afirma que se recalculo.
        val unavailable = models.getValue(FvpSnapshotStatus.RECALCULATION_UNAVAILABLE).warnings
        assertTrue(FvpUiWarning.SNAPSHOT_UNAVAILABLE in unavailable)
        assertFalse(FvpUiWarning.SNAPSHOT_RECALCULATED in unavailable)
    }

    /**
     * Fila guardada que HOY no se puede recalcular (`result == null`, `eligibility = null`, estado
     * `RECALCULATION_UNAVAILABLE`: la unica combinacion que produce el repositorio, cubierta en
     * `FvpRepositoryReadTest`): el presentador muestra los NUMEROS PERSISTIDOS del snapshot, no hay
     * ningun punto (ni del motor ni fabricado) y avisa `SNAPSHOT_UNAVAILABLE`, NUNCA
     * `SNAPSHOT_RECALCULATED` (no se declara un recalculo que no existio). Una fila MATCHES sin
     * recalculo tampoco declara puntos ni aviso.
     */
    @Test
    fun storedRowWithoutRecalculationPresentsPersistedNumbersOnly() {
        val snapshot = storedSnapshotFixture()
        val stored = storedSeries(id = 5L).copy(
            result = null,
            eligibility = null,
            snapshotStatus = FvpSnapshotStatus.RECALCULATION_UNAVAILABLE,
        )
        val model = FvpPresenter.present(
            result = stored.result,
            eligibility = stored.eligibility,
            protocol = FvpProtocol.SJ,
            bodyMassKg = stored.assessment.bodyMassKg,
            conditionCount = stored.assessment.conditionCount,
            // Ruta neutra de pantalla: sin elegibilidad la calidad temporal es DESCONOCIDA.
            timingQuality = stored.eligibility?.timingQuality ?: FvpTimingQuality.UNKNOWN,
            storedWarnings = stored.warnings,
            storedSnapshot = snapshot,
            storedSnapshotStatus = stored.snapshotStatus,
            algorithmVersion = stored.assessment.algorithmVersion,
        )
        // Numeros PERSISTIDOS literales del snapshot, no un recalculo que no existe.
        assertEquals(snapshot.f0N, model.f0N, 0.0)
        assertEquals(snapshot.slopeFv, model.slopeFv, 0.0)
        assertEquals(snapshot.sfvNsPerM, model.sfvNsPerM, 0.0)
        assertEquals(snapshot.v0Mps!!, model.v0Mps!!, 0.0)
        assertEquals(snapshot.pmaxW!!, model.pmaxW!!, 0.0)
        assertEquals(snapshot.r2!!, model.r2!!, 0.0)
        assertEquals(snapshot.conditionCount, model.conditionCount)
        assertEquals(snapshot.vMinMps, model.vMinMps, 0.0)
        assertEquals(snapshot.vMaxMps, model.vMaxMps, 0.0)
        assertEquals(snapshot.fMinN, model.fMinN, 0.0)
        assertEquals(snapshot.fMaxN, model.fMaxN, 0.0)
        assertEquals(snapshot.f0RelNPerKg!!, model.f0RelNPerKg!!, 0.0)
        assertEquals(snapshot.pmaxRelWPerKg!!, model.pmaxRelWPerKg!!, 0.0)
        assertTrue("sin recalculo no hay puntos", model.points.isEmpty())
        assertTrue(model.plottablePoints.isEmpty())
        assertNull(model.storedSnapshotMatches)
        assertFalse("sin elegibilidad no se declara perfil elegible", model.profileEligible)
        // El aviso correcto es el de "no se puede recalcular", jamas el de "recalculado".
        assertTrue(FvpUiWarning.SNAPSHOT_UNAVAILABLE in model.warnings)
        assertFalse(FvpUiWarning.SNAPSHOT_RECALCULATED in model.warnings)

        val matching = storedSeries(id = 6L).copy(
            result = null,
            snapshotStatus = FvpSnapshotStatus.MATCHES,
        )
        val matchingModel = FvpPresenter.present(
            result = matching.result,
            eligibility = null,
            protocol = FvpProtocol.SJ,
            bodyMassKg = matching.assessment.bodyMassKg,
            conditionCount = matching.assessment.conditionCount,
            timingQuality = FvpTimingQuality.UNKNOWN,
            storedSnapshot = snapshot,
            storedSnapshotStatus = matching.snapshotStatus,
            algorithmVersion = matching.assessment.algorithmVersion,
        )
        assertEquals(snapshot.f0N, matchingModel.f0N, 0.0)
        assertTrue(matchingModel.points.isEmpty())
        assertEquals(true, matchingModel.storedSnapshotMatches)
        assertFalse(FvpUiWarning.SNAPSHOT_RECALCULATED in matchingModel.warnings)
        assertFalse(FvpUiWarning.SNAPSHOT_UNAVAILABLE in matchingModel.warnings)
        assertFalse(matchingModel.profileEligible)
    }

    // --- S2 (plan 13, D3): UNA sola ejecucion por vista (nunca mezcla snapshot + recalculo) ------

    /**
     * Vista ORIGINAL de una fila guardada cuyo recalculo DIFIERE (aqui tambien en version): todos los
     * escalares son los LITERALES persistidos, la lista de puntos queda VACIA (el snapshot no guarda
     * puntos por condicion: no se fabrican puntos historicos) y la version rotulada es la PERSISTIDA.
     * El aviso de discrepancia sigue declarando que el recalculo existe y no coincide.
     */
    @Test
    fun originalViewShowsOnlyThePersistedSnapshotWithoutPoints() {
        val snapshot = mismatchedStoredSnapshot()
        val storedVersion = "fvp-poc-0.9"
        val model = presentStoredRow(
            snapshot = snapshot,
            recalculated = result(profile()),
            status = FvpSnapshotStatus.STALE,
            view = FvpResultView.ORIGINAL,
            storedVersion = storedVersion,
        )
        assertEquals(FvpResultView.ORIGINAL, model.view)
        assertEquals(snapshot.f0N, model.f0N, 0.0)
        assertEquals(snapshot.slopeFv, model.slopeFv, 0.0)
        assertEquals(snapshot.sfvNsPerM, model.sfvNsPerM, 0.0)
        assertEquals(snapshot.v0Mps!!, model.v0Mps!!, 0.0)
        assertEquals(snapshot.pmaxW!!, model.pmaxW!!, 0.0)
        assertEquals(snapshot.r2!!, model.r2!!, 0.0)
        assertEquals(snapshot.vMinMps, model.vMinMps, 0.0)
        assertEquals(snapshot.vMaxMps, model.vMaxMps, 0.0)
        assertEquals(snapshot.fMinN, model.fMinN, 0.0)
        assertEquals(snapshot.fMaxN, model.fMaxN, 0.0)
        assertEquals(snapshot.f0RelNPerKg!!, model.f0RelNPerKg!!, 0.0)
        assertEquals(snapshot.pmaxRelWPerKg!!, model.pmaxRelWPerKg!!, 0.0)
        assertEquals(snapshot.conditionCount, model.conditionCount)
        assertTrue("3 condiciones persistidas: la ejecucion original es diagnostica", model.diagnosticOnly)
        assertTrue("la vista original no tiene puntos", model.points.isEmpty())
        assertTrue(model.plottablePoints.isEmpty())
        assertEquals("la version rotulada es la PERSISTIDA", storedVersion, model.algorithmVersion)
        assertNull(model.storedSnapshotMatches)
        assertTrue(
            "el aviso de discrepancia sigue vivo en la vista original",
            FvpUiWarning.SNAPSHOT_RECALCULATED in model.warnings,
        )
        assertFalse(FvpUiWarning.SNAPSHOT_UNAVAILABLE in model.warnings)
    }

    /**
     * Vista de RECALCULACION de la MISMA fila: escalares y puntos son los LITERALES del motor actual,
     * con SU version rotulada. Ni un valor sale del snapshot persistido.
     */
    @Test
    fun recalculationViewShowsOnlyTheRecalculatedProfileWithItsPoints() {
        val snapshot = mismatchedStoredSnapshot()
        val recalculated = result(profile())
        val model = presentStoredRow(
            snapshot = snapshot,
            recalculated = recalculated,
            status = FvpSnapshotStatus.STALE,
            view = FvpResultView.RECALCULATION,
            storedVersion = "fvp-poc-0.9",
        )
        val profile = recalculated.profile
        assertEquals(FvpResultView.RECALCULATION, model.view)
        assertEquals(profile.f0N, model.f0N, 0.0)
        assertEquals(profile.slopeFv, model.slopeFv, 0.0)
        assertEquals(profile.sfvNsPerM, model.sfvNsPerM, 0.0)
        assertEquals(profile.v0Mps, model.v0Mps!!, 0.0)
        assertEquals(profile.pmaxW, model.pmaxW!!, 0.0)
        assertEquals(profile.r2, model.r2!!, 0.0)
        assertEquals(profile.vMinMps, model.vMinMps, 0.0)
        assertEquals(profile.vMaxMps, model.vMaxMps, 0.0)
        assertEquals(profile.fMinN, model.fMinN, 0.0)
        assertEquals(profile.fMaxN, model.fMaxN, 0.0)
        assertEquals(profile.f0RelNPerKg, model.f0RelNPerKg!!, 0.0)
        assertEquals(profile.pmaxRelWPerKg, model.pmaxRelWPerKg!!, 0.0)
        assertEquals(profile.n, model.conditionCount)
        assertFalse("4 condiciones recalculadas no son diagnosticas", model.diagnosticOnly)
        // Puntos LITERALES del recalculo, ordenados por V y con SU rango [0.8,1.4] para `measured`.
        assertEquals(listOf("20kg", "0kg"), model.points.map { it.label })
        assertEquals(listOf(1420.0, 1710.0), model.points.map { it.forceN })
        assertEquals(listOf(1.10, 1.45), model.points.map { it.velocityMps })
        assertEquals(listOf(1562.0, 2480.0), model.points.map { it.powerW })
        assertEquals(listOf(true, false), model.points.map { it.measured })
        assertTrue(model.plottablePoints.isNotEmpty())
        assertEquals(
            "la version rotulada es la del RECALCULO",
            recalculated.algorithmVersion,
            model.algorithmVersion,
        )
        assertTrue(FvpUiWarning.SNAPSHOT_RECALCULATED in model.warnings)
        assertFalse(FvpUiWarning.SNAPSHOT_UNAVAILABLE in model.warnings)
    }

    /**
     * Sin mezcla: con el MISMO snapshot y el MISMO recalculo, la vista ORIGINAL muestra el f0N/V0
     * persistidos y la de RECALCULACION los otros (con la misma version: lo unico distinto es el
     * NUMERO). Un `snapshot ?: perfil` en cualquier campo haria fallar una de las dos vistas.
     */
    @Test
    fun eachViewKeepsItsOwnScalarsWhenOnlyTheNumbersDiffer() {
        val snapshot = mismatchedStoredSnapshot()
        val recalculated = result(profile(f0 = 1500.0, sfv = 700.0))
        val original = presentStoredRow(snapshot, recalculated, FvpSnapshotStatus.MISMATCH, FvpResultView.ORIGINAL)
        val recalculation = presentStoredRow(snapshot, recalculated, FvpSnapshotStatus.MISMATCH, FvpResultView.RECALCULATION)

        assertEquals(snapshot.f0N, original.f0N, 0.0)
        assertEquals(recalculated.profile.f0N, recalculation.f0N, 0.0)
        assertFalse(
            "la vista original no puede enseñar el f0N del recalculo",
            original.f0N == recalculation.f0N,
        )
        assertEquals(snapshot.sfvNsPerM, original.sfvNsPerM, 0.0)
        assertEquals(recalculated.profile.sfvNsPerM, recalculation.sfvNsPerM, 0.0)
        assertFalse(original.sfvNsPerM == recalculation.sfvNsPerM)
        assertEquals(snapshot.v0Mps!!, original.v0Mps!!, 0.0)
        assertEquals(recalculated.profile.v0Mps, recalculation.v0Mps!!, 0.0)
        assertFalse(original.v0Mps == recalculation.v0Mps)
        assertEquals(snapshot.conditionCount, original.conditionCount)
        assertEquals(recalculated.profile.n, recalculation.conditionCount)
        assertFalse(original.conditionCount == recalculation.conditionCount)
        assertTrue(original.points.isEmpty())
        assertEquals(2, recalculation.points.size)
        // La misma fila declara el mismo veredicto y el mismo aviso en las dos vistas: el estado es
        // de la FILA, no de la ejecucion que se esta mirando.
        assertEquals(false, original.storedSnapshotMatches)
        assertEquals(false, recalculation.storedSnapshotMatches)
        assertTrue(FvpUiWarning.SNAPSHOT_RECALCULATED in original.warnings)
        assertTrue(FvpUiWarning.SNAPSHOT_RECALCULATED in recalculation.warnings)
    }

    /**
     * Fila `MATCHES`: las dos vistas dan EXACTAMENTE los mismos escalares (el repositorio certifica
     * que el recalculo es el snapshot), de modo que la pantalla se queda en UNA sola vista sin
     * conmutador. Se fija aqui la tabla de verdad del conmutador y la vista unica recomendada.
     */
    @Test
    fun matchesRowHasTheSameScalarsInBothViewsAndNeedsNoToggle() {
        val snapshot = matchingStoredSnapshot()
        val recalculated = result(profile())
        val original = presentStoredRow(snapshot, recalculated, FvpSnapshotStatus.MATCHES, FvpResultView.ORIGINAL)
        val recalculation = presentStoredRow(snapshot, recalculated, FvpSnapshotStatus.MATCHES, FvpResultView.RECALCULATION)

        assertEquals(original.f0N, recalculation.f0N, 0.0)
        assertEquals(original.slopeFv, recalculation.slopeFv, 0.0)
        assertEquals(original.sfvNsPerM, recalculation.sfvNsPerM, 0.0)
        assertEquals(original.v0Mps!!, recalculation.v0Mps!!, 0.0)
        assertEquals(original.pmaxW!!, recalculation.pmaxW!!, 0.0)
        assertEquals(original.r2!!, recalculation.r2!!, 0.0)
        assertEquals(original.vMinMps, recalculation.vMinMps, 0.0)
        assertEquals(original.vMaxMps, recalculation.vMaxMps, 0.0)
        assertEquals(original.fMinN, recalculation.fMinN, 0.0)
        assertEquals(original.fMaxN, recalculation.fMaxN, 0.0)
        assertEquals(original.f0RelNPerKg!!, recalculation.f0RelNPerKg!!, 0.0)
        assertEquals(original.pmaxRelWPerKg!!, recalculation.pmaxRelWPerKg!!, 0.0)
        assertEquals(original.conditionCount, recalculation.conditionCount)
        assertEquals(original.algorithmVersion, recalculation.algorithmVersion)
        assertEquals(true, original.storedSnapshotMatches)
        assertEquals(true, recalculation.storedSnapshotMatches)
        // Solo la ejecucion CALCULADA tiene puntos: el snapshot persistido no los guarda.
        assertTrue(original.points.isEmpty())
        assertEquals(2, recalculation.points.size)

        // Tabla de verdad del conmutador: solo con DOS ejecuciones distintas que mostrar.
        assertFalse("MATCHES: el recalculo es identico", fvpShowsViewToggle(FvpSnapshotStatus.MATCHES))
        assertFalse("sin recalculo no hay nada que conmutar", fvpShowsViewToggle(FvpSnapshotStatus.RECALCULATION_UNAVAILABLE))
        assertTrue("MISMATCH: numeros distintos", fvpShowsViewToggle(FvpSnapshotStatus.MISMATCH))
        assertTrue("STALE: version distinta", fvpShowsViewToggle(FvpSnapshotStatus.STALE))
        assertTrue("STALE_METHOD: metodo distinto", fvpShowsViewToggle(FvpSnapshotStatus.STALE_METHOD))
        assertFalse("estado desconocido: no se declara una segunda ejecucion", fvpShowsViewToggle(null))

        // Vista unica recomendada cuando la fila no ofrece conmutador.
        assertEquals(FvpResultView.ORIGINAL, fvpDefaultStoredView(FvpSnapshotStatus.MATCHES, hasRecalculation = false))
        assertEquals(FvpResultView.RECALCULATION, fvpDefaultStoredView(FvpSnapshotStatus.MATCHES, hasRecalculation = true))
        assertEquals(FvpResultView.ORIGINAL, fvpDefaultStoredView(FvpSnapshotStatus.MISMATCH, hasRecalculation = true))
        assertEquals(FvpResultView.ORIGINAL, fvpDefaultStoredView(FvpSnapshotStatus.STALE, hasRecalculation = true))
        assertEquals(FvpResultView.ORIGINAL, fvpDefaultStoredView(FvpSnapshotStatus.STALE_METHOD, hasRecalculation = true))
        assertEquals(
            FvpResultView.ORIGINAL,
            fvpDefaultStoredView(FvpSnapshotStatus.RECALCULATION_UNAVAILABLE, hasRecalculation = false),
        )
        assertEquals(FvpResultView.ORIGINAL, fvpDefaultStoredView(null, hasRecalculation = false))
    }

    /**
     * Q2a (plan 13): el contexto B7 que la pantalla pinta SIEMPRE junto a los numeros (rango de
     * FUERZA `fMinN`/`fMaxN` y recuento de condiciones) sale de la MISMA ejecucion mostrada. En una
     * fila cuyo snapshot y recalculo discrepan, cada vista tiene que enseñar SU rango: un
     * `snapshot.fMinN ?: profile.fMinN` (o el recuento de la otra ejecucion) mezclaria dos ejecuciones
     * justo en el dato que el usuario necesita para juzgar la cobertura.
     */
    @Test
    fun forceRangeAndConditionCountComeFromTheShownExecution() {
        val snapshot = mismatchedStoredSnapshot()
        val recalculated = result(profile())
        val original = presentStoredRow(snapshot, recalculated, FvpSnapshotStatus.MISMATCH, FvpResultView.ORIGINAL)
        val recalculation = presentStoredRow(snapshot, recalculated, FvpSnapshotStatus.MISMATCH, FvpResultView.RECALCULATION)

        // Los dos fixtures usan rangos de fuerza y recuentos DISTINTOS: cada valor solo puede venir
        // de su propia fuente.
        assertEquals(snapshot.fMinN, original.fMinN, 0.0)
        assertEquals(snapshot.fMaxN, original.fMaxN, 0.0)
        assertEquals(recalculated.profile.fMinN, recalculation.fMinN, 0.0)
        assertEquals(recalculated.profile.fMaxN, recalculation.fMaxN, 0.0)
        assertFalse(
            "la vista original no puede enseñar el rango de fuerza del recalculo",
            original.fMinN == recalculation.fMinN || original.fMaxN == recalculation.fMaxN,
        )
        assertEquals(snapshot.conditionCount, original.conditionCount)
        assertEquals(recalculated.profile.n, recalculation.conditionCount)
        assertFalse(
            "el recuento de condiciones tambien es el de la ejecucion mostrada",
            original.conditionCount == recalculation.conditionCount,
        )
        // El recuento del snapshot solo puede proceder del ORIGINAL: la vista de recalculo no lo usa
        // ni siquiera cuando el perfil no lo trae (aqui SI lo trae: n distinto del persistido).
        assertFalse("el recuento del recalculo no es el persistido", recalculation.conditionCount == snapshot.conditionCount)
    }

    /**
     * Anti-prestamo: pedir la vista de RECALCULACION en una fila que hoy NO se puede recalcular deja
     * los numeros indefinidos y sin puntos; NUNCA se rellenan con el snapshot. La misma fila en la
     * vista ORIGINAL (la unica que la pantalla ofrece) sigue mostrando los valores persistidos y el
     * aviso honesto de que no hubo recalculo.
     */
    @Test
    fun recalculationViewWithoutRecalculationNeverBorrowsTheSnapshot() {
        val snapshot = mismatchedStoredSnapshot()
        val absent = FvpPresenter.present(
            result = null,
            eligibility = null,
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = snapshot.conditionCount,
            timingQuality = FvpTimingQuality.UNKNOWN,
            storedSnapshot = snapshot,
            storedSnapshotStatus = FvpSnapshotStatus.RECALCULATION_UNAVAILABLE,
            algorithmVersion = "fvp-poc-0.9",
            view = FvpResultView.RECALCULATION,
        )
        assertEquals(FvpResultView.RECALCULATION, absent.view)
        assertTrue(absent.f0N.isNaN())
        assertTrue(absent.slopeFv.isNaN())
        assertTrue(absent.sfvNsPerM.isNaN())
        assertTrue(absent.vMinMps.isNaN())
        assertTrue(absent.vMaxMps.isNaN())
        assertTrue(absent.fMinN.isNaN())
        assertTrue(absent.fMaxN.isNaN())
        assertNull(absent.v0Mps)
        assertNull(absent.pmaxW)
        assertNull(absent.r2)
        assertNull(absent.f0RelNPerKg)
        assertNull(absent.pmaxRelWPerKg)
        assertTrue(absent.points.isEmpty())
        assertEquals("sin recalculo no hay version que rotular", "", absent.algorithmVersion)

        val original = FvpPresenter.present(
            result = null,
            eligibility = null,
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = snapshot.conditionCount,
            timingQuality = FvpTimingQuality.UNKNOWN,
            storedSnapshot = snapshot,
            storedSnapshotStatus = FvpSnapshotStatus.RECALCULATION_UNAVAILABLE,
            algorithmVersion = "fvp-poc-0.9",
            view = FvpResultView.ORIGINAL,
        )
        assertEquals(snapshot.f0N, original.f0N, 0.0)
        assertEquals(snapshot.sfvNsPerM, original.sfvNsPerM, 0.0)
        assertEquals("fvp-poc-0.9", original.algorithmVersion)
        assertTrue(original.points.isEmpty())
        assertTrue(FvpUiWarning.SNAPSHOT_UNAVAILABLE in original.warnings)
        assertFalse(FvpUiWarning.SNAPSHOT_RECALCULATED in original.warnings)
    }

    /**
     * Borrador vivo (sin snapshot): el comportamiento es el de siempre. Su unica ejecucion es la
     * actual, con SUS escalares y SUS puntos, y una llamada sin `view` (valor por defecto) produce un
     * modelo IDENTICO al de la vista declarada.
     */
    @Test
    fun liveDraftWithoutSnapshotKeepsItsCurrentBehaviour() {
        val recalculated = result(profile())
        val declared = FvpPresenter.present(
            result = recalculated,
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
            view = FvpResultView.ORIGINAL,
        )
        val byDefault = FvpPresenter.present(
            result = recalculated,
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
        )
        assertEquals(recalculated.profile.f0N, declared.f0N, 0.0)
        assertEquals(recalculated.profile.sfvNsPerM, declared.sfvNsPerM, 0.0)
        assertEquals(recalculated.algorithmVersion, declared.algorithmVersion)
        assertEquals(2, declared.points.size)
        assertTrue(declared.plottablePoints.isNotEmpty())
        assertNull(declared.storedSnapshotMatches)
        assertEquals(FvpResultView.ORIGINAL, declared.view)
        assertEquals("sin snapshot, la vista no cambia el modelo", declared, byDefault)
    }

    /**
     * P1 (S2): los puntos del ORIGINAL guardado NO viven en esa fuente (el snapshot persistido no
     * guarda puntos por condicion). El modelo lo DECLARA con `pointsUnavailableFromSource` para que
     * la pantalla explique el motivo y como verlos, en vez del estado vacio "Sin puntos medidos
     * todavia" (que afirmaria que la serie no tiene mediciones). La MISMA fila en la vista
     * `RECALCULATION` si trae los puntos del motor: alli el campo es `false`.
     */
    @Test
    fun storedOriginalWithoutPointsDeclaresItAndItsRecalculationDoesNot() {
        val snapshot = mismatchedStoredSnapshot()
        val recalculated = result(profile())
        val original = presentStoredRow(snapshot, recalculated, FvpSnapshotStatus.MISMATCH, FvpResultView.ORIGINAL)
        val recalculation = presentStoredRow(
            snapshot,
            recalculated,
            FvpSnapshotStatus.MISMATCH,
            FvpResultView.RECALCULATION,
        )

        assertTrue(
            "el ORIGINAL guardado declara que la lista vacia es de la FUENTE, no de la serie",
            original.pointsUnavailableFromSource,
        )
        assertTrue(original.points.isEmpty())
        assertTrue(original.plottablePoints.isEmpty())
        assertFalse(
            "el recalculo SI tiene puntos: nunca declara el estado vacio de la fuente",
            recalculation.pointsUnavailableFromSource,
        )
        assertEquals(2, recalculation.points.size)
        assertTrue(recalculation.plottablePoints.isNotEmpty())
    }

    /**
     * Fila guardada SIN recálculo (hoy no computable): la vista ORIGINAL sigue siendo el snapshot,
     * de modo que el campo tambien es `true`. Se declara aqui de forma explicita porque el contrato
     * lo pedia condicionado a lo que implicase la implementacion: el snapshot NO guarda puntos ni
     * con recálculo ni sin el, y una lista vacia nunca debe leerse como "sin mediciones".
     */
    @Test
    fun storedRowWithoutAnyRecalculationAlsoDeclaresTheEmptySource() {
        val snapshot = mismatchedStoredSnapshot()
        val model = FvpPresenter.present(
            result = null,
            eligibility = null,
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = snapshot.conditionCount,
            timingQuality = FvpTimingQuality.UNKNOWN,
            storedSnapshot = snapshot,
            storedSnapshotStatus = FvpSnapshotStatus.RECALCULATION_UNAVAILABLE,
            algorithmVersion = "fvp-poc-0.9",
            view = FvpResultView.ORIGINAL,
        )
        assertTrue(model.pointsUnavailableFromSource)
        assertTrue(model.points.isEmpty())
        assertTrue(FvpUiWarning.SNAPSHOT_UNAVAILABLE in model.warnings)
    }

    /**
     * Fila `MATCHES`: la pantalla abre en la vista CALCULADA (sin conmutador) y el repositorio
     * certifica que sus numeros son los del snapshot, de modo que sus puntos son honestos y el
     * campo es `false`. La fuente ORIGINAL de la MISMA fila sigue sin puntos: el campo describe la
     * FUENTE mostrada, no la fila.
     */
    @Test
    fun matchingRowShownAsRecalculationKeepsItsPoints() {
        val snapshot = matchingStoredSnapshot()
        val recalculated = result(profile())
        val shown = presentStoredRow(snapshot, recalculated, FvpSnapshotStatus.MATCHES, FvpResultView.RECALCULATION)
        assertFalse(shown.pointsUnavailableFromSource)
        assertEquals(2, shown.points.size)

        val original = presentStoredRow(snapshot, recalculated, FvpSnapshotStatus.MATCHES, FvpResultView.ORIGINAL)
        assertTrue(original.pointsUnavailableFromSource)
        assertTrue(original.points.isEmpty())
    }

    /**
     * Borrador vivo (sin snapshot): nunca declara el estado vacio de la fuente, ni con ejecucion
     * (sus puntos son los de la ejecucion actual) ni sin ella (no hay snapshot que declarar; la
     * lista vacia significa de verdad que todavia no hay nada calculado).
     */
    @Test
    fun liveDraftNeverDeclaresTheEmptySource() {
        val computed = FvpPresenter.present(
            result = result(profile()),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
        )
        assertFalse(computed.pointsUnavailableFromSource)
        assertEquals(2, computed.points.size)
        assertTrue(computed.plottablePoints.isNotEmpty())

        val notComputedYet = FvpPresenter.present(
            result = null,
            eligibility = null,
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.UNKNOWN,
        )
        assertFalse(notComputedYet.pointsUnavailableFromSource)
        assertTrue(notComputedYet.points.isEmpty())
    }

    /**
     * P2-c (S2): sin NINGUNA ejecucion (`storedSnapshot == null` y `result == null`) NO se declara
     * `R2_UNDEFINED`: la regla es `hasExecution && r2 == null`, no `r2 == null` a secas. Sin numeros
     * no hay ajuste que juzgar, y una regresion que volviese a declarar el veredicto sobre numeros
     * inexistentes fallaria aqui. Control positivo en la MISMA prueba: con ejecucion y R^2 no finito
     * el aviso SI se declara (no se ha desactivado la regla).
     */
    @Test
    fun noExecutionNeverDeclaresTheR2Verdict() {
        val empty = FvpPresenter.present(
            result = null,
            eligibility = null,
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.UNKNOWN,
        )
        assertNull(empty.r2)
        assertTrue(empty.f0N.isNaN())
        assertTrue(empty.points.isEmpty())
        assertFalse(empty.pointsUnavailableFromSource)
        assertFalse(FvpUiWarning.R2_UNDEFINED in empty.warnings)
        assertFalse(FvpUiWarning.LOW_R2 in empty.warnings)

        val executed = FvpPresenter.present(
            result = result(profile(r2 = Double.NaN)),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
        )
        assertTrue(FvpUiWarning.R2_UNDEFINED in executed.warnings)
    }

    /**
     * Los veredictos del AJUSTE pertenecen a la ejecucion que los produjo: el recalculo no puede
     * etiquetar de "no fisico" a un original guardado con V0/Pmax finitos, y los veredictos
     * persistidos no viajan a la vista del recalculo. Lo que es de la FILA (estado del snapshot) y
     * de la SERIE/procedimiento (B1) se declara en las DOS vistas.
     */
    @Test
    fun executionVerdictWarningsBelongToTheShownView() {
        // Original persistido FISICO (V0/Pmax finitos) con su propio veredicto guardado.
        val snapshot = mismatchedStoredSnapshot()
        // Recalculo NO fisico de la misma serie: veredicto del motor actual.
        val recalculated = result(
            profile(v0 = Double.NaN, pmax = Double.NaN, warnings = listOf(FvpWarning.NON_PHYSICAL)),
        )
        val original = presentStoredRow(
            snapshot = snapshot,
            recalculated = recalculated,
            status = FvpSnapshotStatus.MISMATCH,
            view = FvpResultView.ORIGINAL,
            storedWarnings = listOf("HIGH_EXTRAPOLATION"),
            pushOffSource = FVP_PUSH_OFF_SOURCE_MANUAL,
        )
        val recalculation = presentStoredRow(
            snapshot = snapshot,
            recalculated = recalculated,
            status = FvpSnapshotStatus.MISMATCH,
            view = FvpResultView.RECALCULATION,
            storedWarnings = listOf("HIGH_EXTRAPOLATION"),
            pushOffSource = FVP_PUSH_OFF_SOURCE_MANUAL,
        )

        assertTrue("el original conserva sus interceptos fisicos", original.hasPhysicalIntercepts)
        assertTrue("el veredicto GUARDADO acompanha al original", FvpUiWarning.HIGH_EXTRAPOLATION in original.warnings)
        assertFalse(
            "el veredicto del recalculo no puede etiquetar al original",
            FvpUiWarning.NON_PHYSICAL in original.warnings,
        )
        assertFalse("el recalculo no es fisico", recalculation.hasPhysicalIntercepts)
        assertTrue(FvpUiWarning.NON_PHYSICAL in recalculation.warnings)
        assertFalse(
            "el veredicto guardado no viaja a la vista del recalculo",
            FvpUiWarning.HIGH_EXTRAPOLATION in recalculation.warnings,
        )
        listOf(original, recalculation).forEach { model ->
            assertTrue("el estado del snapshot es de la FILA", FvpUiWarning.SNAPSHOT_RECALCULATED in model.warnings)
            assertTrue("B1 es de la SERIE", FvpUiWarning.PUSH_OFF_MANUAL_SOURCE in model.warnings)
            assertEquals(false, model.storedSnapshotMatches)
        }
    }

    /**
     * Una serie de 3 condiciones sigue siendo DIAGNOSTICA (no un perfil normal) y desde D4/D5 el
     * recuento de condiciones SI manda en la comparacion: dos diagnosticas con todos los metadatos
     * iguales YA NO ofrecen deltas (bloqueo `NOT_A_PROFILE`), y la masa se sigue vigilando ademas
     * del motivo diagnostico.
     */
    @Test
    fun threeConditionDiagnosticSeriesIsNotOfferedForComparison() {
        val keys = listOf("0kg", "20kg", "40kg")
        val model = FvpPresenter.present(
            result = result(profile(n = 3)),
            eligibility = eligibility(
                flags = listOf(FvpEligibilityFlag.FEW_CONDITIONS),
                timing = FvpTimingQuality.ALLOW,
                conditionCount = 3,
            ),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 3,
            timingQuality = FvpTimingQuality.ALLOW,
        )
        assertTrue("3 condiciones siguen siendo diagnosticas", model.diagnosticOnly)
        assertFalse("3 condiciones no son un perfil normal", model.profileEligible)
        assertTrue("el aviso de pocas condiciones se mantiene", FvpUiWarning.FEW_POINTS in model.warnings)

        val first = storedSeries(id = 1L, conditionKeys = keys)
        val second = storedSeries(id = 2L, conditionKeys = keys)
        assertEquals(3, first.assessment.conditionCount)
        assertEquals(
            "3 condiciones no son un perfil comparable: no se ofrecen deltas",
            listOf(FvpComparisonBlock.NOT_A_PROFILE),
            fvpComparisonPolicy(first, second).blocks,
        )
        assertFalse(fvpComparisonPolicy(first, second).canCompareDeltas)
        assertEquals(
            "C2: la masa es contexto, la serie diagnostica sigue bloqueando",
            listOf(FvpComparisonBlock.NOT_A_PROFILE),
            fvpComparisonPolicy(first, storedSeries(id = 3L, conditionKeys = keys, bodyMassKg = 81.0)).blocks,
        )
    }

    // --- P1-2: fps de la serie SOLO de los intentos seleccionados -----------------------------

    /**
     * Gate B6/P1-2: los intentos DESCARTADOS (no validos) o no seleccionados a 30 fps no pueden
     * contaminar el fps de la serie. Con intentos seleccionados a >= 120 fps, el motor no estampa
     * `FPS_30_BLOCKED` ni el presentador muestra `FPS_BLOCKED`, y la serie sigue siendo
     * `profileEligible`.
     */
    @Test
    fun discardedLowFpsAttemptDoesNotStampFpsBlocked() {
        val draft = lowFpsPollutedDraft()

        // El peor fps de TODOS los intentos era 30.0 (la contaminacion que se corrige)...
        val worstOfAllAttempts = draft.conditions.flatMap { it.attempts }
            .mapNotNull { it.sourceFps }
            .minOrNull()
        assertEquals(30.0, worstOfAllAttempts!!, 0.0)
        // ...pero el intento seleccionado de la condicion contaminada es el de 240 fps.
        assertEquals(240.0, draft.selectedAttemptsByCondition()["0kg"]!!.sourceFps!!, 0.0)
        // Y el fps de la serie es el peor de los SELECCIONADOS (120 fps), no el descartado.
        assertEquals(120.0, draft.seriesSourceFps()!!, 0.0)

        val seriesInput = draft.toSeriesInput()
        val eligibility = checkSeries(seriesInput)
        val inputs = seriesInput.selectedConditionInputs()
        assertNotNull(inputs)
        val result = FvpEngine.evaluation(
            id = draft.sessionKey,
            protocol = draft.protocol,
            bodyMassKg = draft.bodyMassKg!!,
            conditions = seriesPoints(inputs!!),
            provenance = "test",
            sourceFps = draft.seriesSourceFps(),
        )
        assertFalse(FvpWarning.FPS_30_BLOCKED in result.profile!!.warnings)
        assertTrue("los intentos seleccionados van a >= 120 fps", eligibility.profileEligible)

        val model = FvpPresenter.present(
            result = result,
            eligibility = eligibility,
            protocol = draft.protocol,
            bodyMassKg = draft.bodyMassKg,
            conditionCount = eligibility.conditionCount,
            timingQuality = eligibility.timingQuality,
            attemptByCondition = draft.selectedAttemptsByCondition(),
            algorithmVersion = result.algorithmVersion,
            pushOffSource = draft.pushOffSource,
        )
        assertFalse(FvpUiWarning.FPS_BLOCKED in model.warnings)
        assertTrue(model.profileEligible)

        // Controles: si el intento a 30 fps es el SELECCIONADO (aqui el intento valido de peor
        // altura pasa a ser el unico), la serie si queda bloqueada por fps.
        val blocked = draft.copy(
            conditions = draft.conditions.map { condition ->
                if (condition.conditionKey != "0kg") {
                    condition
                } else {
                    condition.copy(attempts = listOf(condition.attempts[1]))
                }
            },
        )
        assertEquals(30.0, blocked.seriesSourceFps()!!, 0.0)
        val blockedInputs = blocked.toSeriesInput().selectedConditionInputs()
        assertNotNull(blockedInputs)
        val blockedResult = FvpEngine.evaluation(
            id = blocked.sessionKey,
            protocol = blocked.protocol,
            bodyMassKg = blocked.bodyMassKg!!,
            conditions = seriesPoints(blockedInputs!!),
            provenance = "test",
            sourceFps = blocked.seriesSourceFps(),
        )
        assertTrue(FvpWarning.FPS_30_BLOCKED in blockedResult.profile!!.warnings)
    }

    /** Sin ningun fps declarado en los intentos seleccionados no se inventa un valor de serie. */
    @Test
    fun seriesWithoutDeclaredFpsHasNoSourceFps() {
        val draft = lowFpsPollutedDraft().copy(
            conditions = listOf(
                FvpConditionDraft(
                    conditionKey = "0kg",
                    addedLoadKg = 0.0,
                    attempts = listOf(FvpAttemptDraft(heightM = 0.40, method = FvpMeasureMethod.MANUAL)),
                ),
                FvpConditionDraft(
                    conditionKey = "20kg",
                    addedLoadKg = 20.0,
                    attempts = listOf(FvpAttemptDraft(heightM = 0.32, method = FvpMeasureMethod.MANUAL)),
                ),
            ),
        )
        assertNull(draft.seriesSourceFps())
        assertEquals(FvpTimingQuality.UNKNOWN, checkSeries(draft.toSeriesInput()).timingQuality)
        assertFalse(checkSeries(draft.toSeriesInput()).profileEligible)
    }

    // --- W1.3: un UNICO camino de evaluacion (calculo en vivo == guardado) --------------------

    /**
     * W1.3: `viewModel.compute(draft)` y el evaluador del repositorio
     * ([com.openjump.app.data.evaluateSeries], el que usa `FvpRepository.save`) dan la MISMA
     * elegibilidad, los MISMOS ordinales de intento (regla canonica) y los MISMOS puntos y perfil
     * del motor. Lo que `save` persiste son exactamente esos numeros (bit a bit).
     */
    @Test
    fun liveComputeAndSaveAgreeOnValidSeriesNumbersAndSelection() {
        val dao = RecordingFvpDao()
        val repository = FvpRepository(dao)
        val viewModel = FvpViewModel(repository, unusedJumpRepository())
        val draft = evaluatorAgreementDraft()
        val persistable = requireNotNull(draft.toPersistDraftOrNull()) { "el borrador debe ser persistible" }

        val live = viewModel.compute(draft)
        assertTrue("la serie de prueba debe ser calculable: ${live.eligibility.blocking}", live.canCompute)
        val liveResult = requireNotNull(live.result)

        val repositoryOutcome = evaluateSeries(
            seriesInput = persistable.toEligibilitySeriesInput(FvpProtocol.SJ, FvpPushOffMode.COMMON),
            evaluationId = persistable.sessionKey,
            provenance = "fvp-app",
            sourceFps = persistable.sourceFps,
        )
        assertEquals(repositoryOutcome.eligibility, live.eligibility)
        assertEquals(repositoryOutcome.result, liveResult)

        runBlocking { repository.save(persistable) }
        val record = requireNotNull(dao.saved) { "save debe persistir el registro" }
        val assessment = record.assessment
        assertEquals(draft.sessionKey, assessment.sessionKey)
        assertEquals(liveResult.profile.n, assessment.conditionCount)
        assertSameBits(liveResult.profile.f0N, assessment.f0N, "f0N")
        assertSameBits(liveResult.profile.slopeFv, assessment.slopeFv, "slopeFv")
        assertSameBits(liveResult.profile.sfvNsPerM, assessment.sfvNsPerM, "sfvNsPerM")
        assertSameBits(liveResult.profile.v0Mps, requireNotNull(assessment.v0Mps), "v0Mps")
        assertSameBits(liveResult.profile.pmaxW, requireNotNull(assessment.pmaxW), "pmaxW")
        assertSameBits(liveResult.profile.r2, requireNotNull(assessment.r2), "r2")
        assertSameBits(liveResult.profile.vMinMps, assessment.vMinMps, "vMinMps")
        assertSameBits(liveResult.profile.vMaxMps, assessment.vMaxMps, "vMaxMps")
        assertSameBits(liveResult.profile.fMinN, assessment.fMinN, "fMinN")
        assertSameBits(liveResult.profile.fMaxN, assessment.fMaxN, "fMaxN")
        assertSameBits(
            liveResult.profile.f0RelNPerKg,
            requireNotNull(assessment.f0RelNPerKg),
            "f0RelNPerKg",
        )
        assertSameBits(
            liveResult.profile.pmaxRelWPerKg,
            requireNotNull(assessment.pmaxRelWPerKg),
            "pmaxRelWPerKg",
        )
        assertEquals(
            (liveResult.profile.warnings.map { it.name } + live.eligibility.flags.map { it.name }).distinct(),
            parseWarningsCsv(assessment.warningsCsv),
        )

        // Seleccion persistida = regla canonica = proyeccion de la tabla de puntos.
        val seriesInput = draft.toSeriesInput()
        assertEquals(4, record.conditions.size)
        draft.conditions.forEachIndexed { ordinal, condition ->
            val canonicalIndex = requireNotNull(
                seriesInput.selectedValidAttemptIndex(seriesInput.conditions[ordinal]),
            ) { "la condicion '${condition.conditionKey}' debe tener intento seleccionable" }
            val row = record.conditions.single { it.ordinal == ordinal }
            assertEquals(canonicalIndex, row.selectedAttemptOrdinal)
            assertSameBits(
                requireNotNull(condition.attempts[canonicalIndex].heightM),
                row.selectedHeightM,
                "selectedHeightM[${condition.conditionKey}]",
            )
            assertEquals(
                draft.selectedAttemptsByCondition().getValue(condition.conditionKey).heightM,
                row.selectedHeightM,
            )
        }
        // El intento INVALIDO mas alto (0.55 m) nunca se selecciona: gana el valido de 0.40 m.
        assertEquals(1, record.conditions.single { it.conditionKey == "0kg" }.selectedAttemptOrdinal)
        // Empate exacto entre dos intentos validos: gana el PRIMERO (ordinal 0).
        assertEquals(0, record.conditions.single { it.conditionKey == "20kg" }.selectedAttemptOrdinal)
    }

    /**
     * W1.3: con una serie BLOQUEADA el motor no se llama (`result == null`) y `save` lanza sin
     * persistir nada: en vivo y en el evaluador del repositorio la elegibilidad es la misma.
     */
    @Test
    fun liveComputeAndSaveAgreeOnBlockedSeriesAndNeverPersist() {
        val dao = RecordingFvpDao()
        val repository = FvpRepository(dao)
        val viewModel = FvpViewModel(repository, unusedJumpRepository())
        val draft = blockedEvaluatorDraft()
        val persistable = requireNotNull(draft.toPersistDraftOrNull()) { "el borrador debe ser persistible" }

        val live = viewModel.compute(draft)
        assertFalse("una serie bloqueada no es calculable", live.canCompute)
        assertNull("una serie bloqueada no llama al motor", live.result)
        assertEquals(
            listOf(
                FvpEligibilityProblem.DUPLICATE_LOAD,
                FvpEligibilityProblem.CONDITION_WITHOUT_VALID_ATTEMPT,
            ),
            live.eligibility.blocking,
        )

        val repositoryOutcome = evaluateSeries(
            seriesInput = persistable.toEligibilitySeriesInput(FvpProtocol.SJ, FvpPushOffMode.COMMON),
            evaluationId = persistable.sessionKey,
            provenance = "fvp-app",
            sourceFps = persistable.sourceFps,
        )
        assertEquals(live.eligibility, repositoryOutcome.eligibility)
        assertNull(repositoryOutcome.result)
        assertTrue("sin puntos seleccionados no hay entradas de motor", repositoryOutcome.inputs.isEmpty())

        val failure = runCatching { runBlocking { repository.save(persistable) } }.exceptionOrNull()
        assertTrue("save debe fallar de forma visible, no persistir un defecto: $failure", failure is IllegalStateException)
        assertTrue(
            "el mensaje debe nombrar el bloqueo: ${failure?.message}",
            failure?.message.orEmpty().contains(FvpEligibilityProblem.DUPLICATE_LOAD.name),
        )
        assertNull("una serie bloqueada no persiste nada", dao.saved)
    }

    /**
     * Wave 4a: una serie con TODAS las alturas seleccionadas iguales ya NO llega al motor. Antes la
     * elegibilidad la aceptaba, el motor la rechazaba ("all measured velocities are identical") y la
     * UI quedaba con Calcular deshabilitado y un banner sin detalle; ahora se bloquea con
     * [FvpEligibilityProblem.IDENTICAL_SELECTED_HEIGHTS] para dar un motivo accionable. `save` falla
     * en cerrado sin persistir nada (mismo contrato de visibilidad que antes, ahora antes del motor).
     */
    @Test
    fun identicalSelectedHeightsAreBlockedBeforeTheEngineAndNeverPersist() {
        val dao = RecordingFvpDao()
        val repository = FvpRepository(dao)
        val viewModel = FvpViewModel(repository, unusedJumpRepository())
        val draft = identicalHeightsDraft()
        val persistable = requireNotNull(draft.toPersistDraftOrNull()) { "el borrador debe ser persistible" }

        val live = viewModel.compute(draft)
        assertTrue(
            "todas las alturas iguales deben bloquear con motivo accionable: ${live.eligibility.blocking}",
            live.eligibility.blocking.contains(FvpEligibilityProblem.IDENTICAL_SELECTED_HEIGHTS),
        )
        assertFalse("una serie bloqueada no se puede publicar", live.canCompute)
        assertNull("una serie bloqueada no llama al motor", live.result)

        val outcome = evaluateSeries(
            seriesInput = persistable.toEligibilitySeriesInput(FvpProtocol.SJ, FvpPushOffMode.COMMON),
            evaluationId = persistable.sessionKey,
            provenance = "fvp-app",
            sourceFps = persistable.sourceFps,
        )
        assertEquals(live.eligibility, outcome.eligibility)
        assertNull("el motor no se llama: no hay fallo que propagar", outcome.engineFailure)
        assertTrue("sin entradas seleccionables no hay puntos de motor", outcome.inputs.isEmpty())

        val failure = runCatching { runBlocking { repository.save(persistable) } }.exceptionOrNull()
        assertTrue("save debe fallar de forma visible, no inventar un valor: $failure", failure is IllegalStateException)
        assertTrue(
            "el mensaje debe nombrar el bloqueo: ${failure?.message}",
            failure?.message.orEmpty().contains(FvpEligibilityProblem.IDENTICAL_SELECTED_HEIGHTS.name),
        )
        assertNull("una serie bloqueada no persiste nada", dao.saved)
    }

    /**
     * El motor sigue siendo la AUTORIDAD: con la misma serie de alturas iguales,
     * `FvpEngine.evaluation` rechaza las velocidades identicas (pendiente indefinida). La
     * elegibilidad solo evita llegar a ese rechazo sin detalle; no relaja ni sustituye al motor.
     */
    @Test
    fun engineStillRejectsIdenticalVelocitiesWhenCalledDirectly() {
        val draft = identicalHeightsDraft()
        val engineInputs = draft.conditions.map { condition ->
            FvpConditionInput(
                conditionId = condition.conditionKey,
                totalMassKg = draft.bodyMassKg!! + requireNotNull(condition.addedLoadKg),
                heightM = requireNotNull(condition.attempts.single().heightM),
                pushOffM = requireNotNull(draft.commonPushOffM),
            )
        }

        val failure = runCatching {
            FvpEngine.evaluation(
                id = draft.sessionKey,
                protocol = draft.protocol,
                bodyMassKg = draft.bodyMassKg!!,
                conditions = seriesPoints(engineInputs),
                provenance = "test",
                sourceFps = draft.seriesSourceFps(),
            )
        }.exceptionOrNull()

        assertTrue("el motor debe seguir rechazando velocidades identicas: $failure", failure is FvpInputError)
    }

    /**
     * W1.3: `selectedAttemptsByCondition` es una PROYECCION del intento elegido, no una segunda
     * regla. Se comprueba contra la regla canonica en los casos limite: intento invalido mas alto,
     * empates (gana el primero) y alturas no utilizables (NaN, infinito, 0, negativa, nula).
     */
    @Test
    fun draftAttemptProjectionAgreesWithCanonicalRule() {
        val draft = selectionEdgeCaseDraft()
        val seriesInput = draft.toSeriesInput()
        val projection = draft.selectedAttemptsByCondition()

        draft.conditions.forEachIndexed { index, condition ->
            val canonicalIndex = seriesInput.selectedValidAttemptIndex(seriesInput.conditions[index])
            val canonical = canonicalIndex?.let { condition.attempts[it] }
            val projected = projection.getValue(condition.conditionKey)
            assertEquals("altura de '${condition.conditionKey}'", canonical?.heightM, projected.heightM)
            assertEquals("fps de '${condition.conditionKey}'", canonical?.sourceFps, projected.sourceFps)
            assertEquals("validez de '${condition.conditionKey}'", canonical != null, projected.valid)
            // `FvpAttemptUi.method` no es nulo: sin intento elegido la proyeccion conserva el
            // fallback documentado (metodo del primer intento o FLIGHT_TIME_VIDEO).
            val expectedMethod = canonical?.method
                ?: condition.attempts.firstOrNull()?.method
                ?: FvpMeasureMethod.FLIGHT_TIME_VIDEO
            assertEquals("metodo de '${condition.conditionKey}'", expectedMethod, projected.method)
        }

        // Expectativas explicitas (independientes de como este implementada la regla canonica).
        assertEquals(1, canonicalIndexOf(draft, "invalid-taller"))
        assertEquals(0.40, projection.getValue("invalid-taller").heightM!!, 0.0)
        assertEquals(0, canonicalIndexOf(draft, "tie"))
        assertEquals(0.35, projection.getValue("tie").heightM!!, 0.0)
        assertEquals(FvpMeasureMethod.MANUAL, projection.getValue("tie").method)
        assertNull("el empate debe elegir el PRIMERO, sin fps declarados", projection.getValue("tie").sourceFps)
        assertEquals(5, canonicalIndexOf(draft, "non-finite"))
        assertEquals(0.25, projection.getValue("non-finite").heightM!!, 0.0)
        assertNull(canonicalIndexOf(draft, "no-usable"))
        assertFalse("sin intento utilizable no hay punto publicable", projection.getValue("no-usable").valid)
    }

    /**
     * Wave 5 (unidades de h_po): el campo de h_po es LONGITUD CORTA del perfil (cm/in) y su canonico
     * sigue siendo METROS. Antes usaba la unidad de distancia horizontal (pies en EE. UU.), de modo
     * que 0,40 m se mostraban como 1,312 ft mientras las alturas de intento iban en pulgadas.
     *
     * Los helpers son puros (perfil + locale explicitos): no tocan la composicion ni el valor
     * persistido, asi que el dominio de elegibilidad sigue en metros.
     */
    @Test
    fun hPoIsTypedInShortLengthUnitsAndKeptInMetresAsCanonicalValue() {
        val es = Locale.forLanguageTag("es")
        val metric = UnitProfile.METRIC
        val unitedStates = UnitProfile.UNITED_STATES

        // 0,40 m: 40 cm en metrico, 15,748 in en US; jamas 1,312 ft.
        assertEquals("40", formatPushOffInput(0.40, metric, es))
        assertEquals("15,748", formatPushOffInput(0.40, unitedStates, es))
        assertEquals(0.40, parsePushOffInput("40", metric, es)!!, 1e-12)
        assertEquals(0.40, parsePushOffInput("15,748", unitedStates, es)!!, 1e-4)

        // Campo vacio o no numerico: sin valor (no 0,0 ni una interpretacion inventada).
        assertNull(parsePushOffInput("", metric, es))
        assertNull(parsePushOffInput("1,2,3", metric, es))

        // El canonico vive en el MISMO dominio de metros sea cual sea el perfil.
        assertTrue(parsePushOffInput("40", metric, es)!! > MIN_PUSH_OFF_M)
        assertTrue(parsePushOffInput("15,748", unitedStates, es)!! <= MAX_PUSH_OFF_M)
    }

    /**
     * Wave 5: los textos de limite de h_po reciben el rango formateado en la unidad del CAMPO
     * (cm/in), no en metros. Wave 2 (U2) anade el redondeo HACIA DENTRO y los marcadores de
     * apertura: en metrico `(5,1–80,0] cm` (0,05 m exacto = 5,0 cm queda EXCLUIDO, se anuncia 5,1) y
     * en US `(2,0–31,4] in`, porque 31,5 in = 0,8001 m el motor lo RECHAZA.
     */
    @Test
    fun pushOffLimitTextsUseTheSameUnitAsTheField() {
        val es = Locale.forLanguageTag("es")

        assertEquals("(5,1–80,0] cm", pushOffDomainRangeText(UnitProfile.METRIC, es))
        assertEquals("(2,0–31,4] in", pushOffDomainRangeText(UnitProfile.UNITED_STATES, es))
        assertEquals("20,0–60,0 cm", pushOffRecommendedRangeText(UnitProfile.METRIC, es))
        assertEquals("7,9–23,6 in", pushOffRecommendedRangeText(UnitProfile.UNITED_STATES, es))
    }

    /**
     * R3: el limite de ALTURA de salto tambien se escribe en la unidad del campo (cm/in) y nunca en
     * metros, para que el motivo bloqueante no contradiga lo que el usuario escribe. El extremo
     * inferior (0) va marcado como EXCLUIDO: el dominio exige altura > 0.
     */
    @Test
    fun jumpHeightLimitTextUsesTheFieldUnitAndNeverMeters() {
        val es = Locale.forLanguageTag("es")

        assertEquals("(0,0–120,0] cm", jumpHeightRangeText(UnitProfile.METRIC, es))
        assertEquals("(0,0–47,2] in", jumpHeightRangeText(UnitProfile.UNITED_STATES, es))
        assertFalse(jumpHeightRangeText(UnitProfile.METRIC, es).contains(" m"))
        assertFalse(jumpHeightRangeText(UnitProfile.UNITED_STATES, es).contains(" m"))
        assertTrue(jumpHeightRangeText(UnitProfile.UNITED_STATES, es).endsWith(" in"))
    }

    /**
     * Serie con intentos contaminantes: uno NO valido a 30 fps y otro valido pero NO seleccionado
     * (peor altura) a 30 fps; los seleccionados van a 240/240/120/240 fps.
     */
    private fun lowFpsPollutedDraft(): FvpDraft = FvpDraft(
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
                attempts = listOf(
                    FvpAttemptDraft(
                        heightM = 0.40,
                        method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                        sourceFps = 240.0,
                    ),
                    FvpAttemptDraft(
                        heightM = 0.30,
                        method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                        sourceFps = 30.0,
                    ),
                    FvpAttemptDraft(
                        heightM = 0.20,
                        method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                        sourceFps = 30.0,
                        valid = false,
                        exclusionReason = "fps insuficientes",
                    ),
                ),
            ),
            FvpConditionDraft(
                conditionKey = "20kg",
                addedLoadKg = 20.0,
                attempts = listOf(
                    FvpAttemptDraft(
                        heightM = 0.32,
                        method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                        sourceFps = 240.0,
                    ),
                ),
            ),
            FvpConditionDraft(
                conditionKey = "40kg",
                addedLoadKg = 40.0,
                attempts = listOf(
                    FvpAttemptDraft(
                        heightM = 0.25,
                        method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                        sourceFps = 120.0,
                    ),
                ),
            ),
            FvpConditionDraft(
                conditionKey = "60kg",
                addedLoadKg = 60.0,
                attempts = listOf(
                    FvpAttemptDraft(
                        heightM = 0.18,
                        method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                        sourceFps = 240.0,
                    ),
                ),
            ),
        ),
    )

    /** Puntos del motor a partir de las entradas seleccionadas (misma orquestacion que la UI). */
    private fun seriesPoints(inputs: List<FvpConditionInput>): List<FvpConditionPoint> = inputs.map { input ->
        val point = FvpEngine.point(input.totalMassKg, input.heightM, input.pushOffM)
        FvpConditionPoint(
            conditionId = input.conditionId,
            totalMassKg = input.totalMassKg,
            heightM = input.heightM,
            pushOffM = input.pushOffM,
            forceN = point.forceN,
            velocityMps = point.velocityMps,
            powerW = point.powerW,
        )
    }

    /** Indice canonico del intento elegido de una condicion del borrador (o null si no hay). */
    private fun canonicalIndexOf(draft: FvpDraft, conditionKey: String): Int? {
        val seriesInput = draft.toSeriesInput()
        val condition = seriesInput.conditions.single { it.conditionKey == conditionKey }
        return seriesInput.selectedValidAttemptIndex(condition)
    }

    /**
     * Serie VALIDA de 4 condiciones para comprobar el acuerdo entre calculo en vivo y guardado:
     * un intento invalido mas alto (nunca seleccionado), un empate exacto (gana el primero), 240 fps
     * en los seleccionados y h_po medido dentro de la ventana recomendada (perfil normal elegible).
     */
    private fun evaluatorAgreementDraft(): FvpDraft = FvpDraft(
        athleteId = 1L,
        protocol = FvpProtocol.SJ,
        bodyMassKg = 80.0,
        bodyMassFromProfile = true,
        pushOffMode = FvpPushOffUiMode.COMMON,
        commonPushOffM = 0.40,
        pushOffLandmark = "TROCANTER",
        pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
        pushOffMeasuredAt = 1L,
        conditions = listOf(
            FvpConditionDraft(
                conditionKey = "0kg",
                addedLoadKg = 0.0,
                attempts = listOf(
                    FvpAttemptDraft(heightM = 0.55, valid = false, exclusionReason = "aterrizaje inestable"),
                    FvpAttemptDraft(heightM = 0.40, sourceFps = 240.0),
                    FvpAttemptDraft(heightM = 0.30, sourceFps = 240.0),
                ),
            ),
            FvpConditionDraft(
                conditionKey = "20kg",
                addedLoadKg = 20.0,
                attempts = listOf(
                    FvpAttemptDraft(heightM = 0.32, method = FvpMeasureMethod.MANUAL, sourceFps = 240.0),
                    FvpAttemptDraft(heightM = 0.32, sourceFps = 240.0),
                ),
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
     * Serie BLOQUEADA (carga duplicada + condicion sin ningun intento valido). Es persistible como
     * borrador (masa y h_po presentes): el fallo lo detecta la elegibilidad, no la forma del dato.
     */
    private fun blockedEvaluatorDraft(): FvpDraft = FvpDraft(
        athleteId = 1L,
        protocol = FvpProtocol.SJ,
        bodyMassKg = 80.0,
        bodyMassFromProfile = true,
        pushOffMode = FvpPushOffUiMode.COMMON,
        commonPushOffM = 0.40,
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
                conditionKey = "20kg-bis",
                addedLoadKg = 20.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.30, sourceFps = 240.0)),
            ),
            FvpConditionDraft(
                conditionKey = "60kg",
                addedLoadKg = 60.0,
                attempts = listOf(
                    FvpAttemptDraft(heightM = 0.18, valid = false, exclusionReason = "sin despegue"),
                ),
            ),
        ),
    )

    /**
     * Casos limite de seleccion: intento invalido mas alto, empate exacto y alturas no
     * utilizables (NaN, infinito, 0, negativa y ausente).
     */
    private fun selectionEdgeCaseDraft(): FvpDraft = FvpDraft(
        athleteId = 1L,
        bodyMassKg = 80.0,
        pushOffMode = FvpPushOffUiMode.COMMON,
        commonPushOffM = 0.40,
        conditions = listOf(
            FvpConditionDraft(
                conditionKey = "invalid-taller",
                addedLoadKg = 0.0,
                attempts = listOf(
                    FvpAttemptDraft(heightM = 0.60, valid = false, exclusionReason = "aterrizaje inestable"),
                    FvpAttemptDraft(heightM = 0.40, sourceFps = 240.0),
                    FvpAttemptDraft(heightM = 0.30, sourceFps = 240.0),
                ),
            ),
            FvpConditionDraft(
                conditionKey = "tie",
                addedLoadKg = 20.0,
                attempts = listOf(
                    FvpAttemptDraft(heightM = 0.35, method = FvpMeasureMethod.MANUAL),
                    FvpAttemptDraft(heightM = 0.35, sourceFps = 240.0),
                ),
            ),
            FvpConditionDraft(
                conditionKey = "non-finite",
                addedLoadKg = 40.0,
                attempts = listOf(
                    FvpAttemptDraft(heightM = Double.NaN),
                    FvpAttemptDraft(heightM = Double.POSITIVE_INFINITY),
                    FvpAttemptDraft(heightM = 0.0),
                    FvpAttemptDraft(heightM = -0.1),
                    FvpAttemptDraft(heightM = null),
                    FvpAttemptDraft(heightM = 0.25, sourceFps = 240.0),
                ),
            ),
            FvpConditionDraft(
                conditionKey = "no-usable",
                addedLoadKg = 60.0,
                attempts = listOf(
                    FvpAttemptDraft(heightM = null),
                    FvpAttemptDraft(heightM = 0.0, valid = false, exclusionReason = "sin despegue"),
                ),
            ),
        ),
    )

    /**
     * Serie con TODAS las alturas iguales (el motor no puede ajustar la pendiente) pero sin ningun
     * bloqueo de elegibilidad: carga unica por condicion y h_po dentro de la ventana recomendada.
     */
    private fun identicalHeightsDraft(): FvpDraft = FvpDraft(
        athleteId = 1L,
        protocol = FvpProtocol.SJ,
        bodyMassKg = 80.0,
        bodyMassFromProfile = true,
        pushOffMode = FvpPushOffUiMode.COMMON,
        commonPushOffM = 0.40,
        conditions = listOf(
            FvpConditionDraft(
                conditionKey = "0kg",
                addedLoadKg = 0.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.30, sourceFps = 240.0)),
            ),
            FvpConditionDraft(
                conditionKey = "20kg",
                addedLoadKg = 20.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.30, sourceFps = 240.0)),
            ),
            FvpConditionDraft(
                conditionKey = "40kg",
                addedLoadKg = 40.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.30, sourceFps = 240.0)),
            ),
            FvpConditionDraft(
                conditionKey = "60kg",
                addedLoadKg = 60.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.30, sourceFps = 240.0)),
            ),
        ),
    )

    /** Igualdad BIT A BIT: los numeros del motor no pueden cambiar al pasar por la persistencia. */
    private fun assertSameBits(expected: Double, actual: Double, label: String) {
        assertEquals("$label debe ser identico bit a bit", expected.toRawBits(), actual.toRawBits())
    }

    /**
     * `FvpViewModel` exige un `JumpRepository` (solo para el selector de saltos reutilizables, que
     * este test no ejercita). Se construye con un `JumpDao` derivado de un proxy dinamico que falla
     * con un mensaje explicito si alguien lo llama: ni dependencias nuevas ni datos inventados.
     */
    private fun unusedJumpRepository(): JumpRepository {
        val handler = InvocationHandler { _, method, _ ->
            throw UnsupportedOperationException("JumpDao no se usa en FvpPresenterTest: ${method.name}")
        }
        val dao = Proxy.newProxyInstance(
            JumpDao::class.java.classLoader,
            arrayOf(JumpDao::class.java),
            handler,
        ) as JumpDao
        return JumpRepository(dao)
    }

    /** DAO falso minimo: captura el registro que `FvpRepository.save` intenta persistir. */
    private class RecordingFvpDao : FvpDao {
        var saved: PersistableFvpAssessment? = null

        override suspend fun save(record: PersistableFvpAssessment): Long {
            saved = record
            return 7L
        }

        override suspend fun insertAssessmentRaw(row: FvpAssessmentEntity): Long = unusedDao("insertAssessmentRaw")
        override suspend fun insertConditionRaw(row: FvpConditionEntity): Long = unusedDao("insertConditionRaw")
        override suspend fun insertAttemptRaw(row: FvpAttemptEntity): Long = unusedDao("insertAttemptRaw")
        override suspend fun assessmentIdForSession(sessionKey: String): Long? = unusedDao("assessmentIdForSession")
        override suspend fun bySessionKey(sessionKey: String): FvpAssessmentEntity? = unusedDao("bySessionKey")
        override suspend fun assessmentWithData(id: Long): FvpAssessmentWithData? = unusedDao("assessmentWithData")
        override suspend fun deleteById(id: Long): Int = unusedDao("deleteById")
        override suspend fun updateNotes(id: Long, notes: String?): Int = unusedDao("updateNotes")
        override suspend fun deleteForAthlete(athleteId: Long): Unit = unusedDao("deleteForAthlete")
        override fun recent(): Flow<List<FvpAssessmentEntity>> = emptyFlow()
        override fun forAthlete(athleteId: Long): Flow<List<FvpAssessmentEntity>> = emptyFlow()

        private fun unusedDao(name: String): Nothing =
            throw UnsupportedOperationException("FvpDao.$name no se usa en FvpPresenterTest")
    }

    // --- Q1 (plan 13): estado de calidad derivado, por ejecucion mostrada ------------------------

    /**
     * D3/Q1: el estado sale de la MISMA ejecucion que los numeros. El recalculo de este fixture es NO
     * fisico y el original persistido no, de modo que cada vista DEBE declarar un estado distinto: una
     * fuente hibrida (o un estado global unico) fallaria aqui.
     */
    @Test
    fun `quality state follows the shown execution for original and recalculation`() {
        val snapshot = mismatchedStoredSnapshot()
        val recalculated = result(profile(r2 = 0.93, warnings = listOf(FvpWarning.NON_PHYSICAL)))

        val original = presentStoredRow(snapshot, recalculated, FvpSnapshotStatus.MISMATCH, FvpResultView.ORIGINAL)
        val recalculation = presentStoredRow(snapshot, recalculated, FvpSnapshotStatus.MISMATCH, FvpResultView.RECALCULATION)

        assertEquals(FvpQualityState.PROFILE, original.quality)
        assertEquals(FvpQualityState.DIAGNOSTIC, recalculation.quality)
        assertTrue("PROFILE exige profileEligible", original.profileEligible)
        // `profileEligible` es la ruta de ELEGIBILIDAD y NO mira los avisos del motor: sigue siendo
        // true en el recalculo no fisico. Justo esa diferencia (ajuste no utilizable pese a la ruta
        // normal) es lo que el estado de calidad sintetiza; sin el, la pantalla tendria que combinar
        // `profileEligible` con los avisos por su cuenta.
        assertTrue("la elegibilidad no juzga el ajuste del recalculo", recalculation.profileEligible)
    }

    @Test
    fun `persisted warnings drive the original quality while the recalculation uses the engine ones`() {
        val snapshot = storedSnapshotFixture()
        val recalculated = result(profile(r2 = 0.97))

        val original = presentStoredRow(
            snapshot,
            recalculated,
            FvpSnapshotStatus.MISMATCH,
            FvpResultView.ORIGINAL,
            storedWarnings = listOf("LOW_R2"),
        )
        val recalculation = presentStoredRow(
            snapshot,
            recalculated,
            FvpSnapshotStatus.MISMATCH,
            FvpResultView.RECALCULATION,
            storedWarnings = listOf("LOW_R2"),
        )

        assertEquals(
            "Q2: LOW_R2 persistido no deja perfil revisable en el original",
            FvpQualityState.DIAGNOSTIC,
            original.quality,
        )
        assertEquals("el recalculo limpio no hereda el aviso persistido", FvpQualityState.PROFILE, recalculation.quality)
        assertTrue(FvpUiWarning.LOW_R2 in original.warnings)
        assertFalse(FvpUiWarning.LOW_R2 in recalculation.warnings)
    }

    @Test
    fun `original without finite r2 is diagnostic while its recalculation can be profile`() {
        val snapshot = storedSnapshotFixture().copy(r2 = Double.NaN)
        val recalculated = result(profile(r2 = 0.95))

        val original = presentStoredRow(snapshot, recalculated, FvpSnapshotStatus.MISMATCH, FvpResultView.ORIGINAL)
        val recalculation = presentStoredRow(snapshot, recalculated, FvpSnapshotStatus.MISMATCH, FvpResultView.RECALCULATION)

        assertNull(original.r2)
        assertEquals("sin R2 en el original no hay ajuste que juzgar", FvpQualityState.DIAGNOSTIC, original.quality)
        assertEquals(0.95, recalculation.r2!!, 0.0)
        assertEquals(FvpQualityState.PROFILE, recalculation.quality)
    }

    @Test
    fun `live draft quality uses the engine warnings of its only execution`() {
        val lowR2 = FvpPresenter.present(
            result = result(profile(r2 = 0.72, warnings = listOf(FvpWarning.LOW_R2))),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
        )
        val nonPhysical = FvpPresenter.present(
            result = result(profile(v0 = Double.NaN, pmax = Double.NaN, r2 = 0.99, warnings = listOf(FvpWarning.NON_PHYSICAL))),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
        )
        val timingConditional = FvpPresenter.present(
            result = result(profile(r2 = 0.97)),
            eligibility = eligibility(
                flags = listOf(FvpEligibilityFlag.TIMING_CONDITIONAL),
                timing = FvpTimingQuality.CONDITIONAL,
            ),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.CONDITIONAL,
        )

        assertEquals("Q2: R^2 bajo del motor fija DIAGNOSTIC", FvpQualityState.DIAGNOSTIC, lowR2.quality)
        assertEquals(FvpQualityState.DIAGNOSTIC, nonPhysical.quality)
        assertEquals(
            "Q2: 60-<120 fps sin sensibilidad revisada no tiene ruta condicional",
            FvpQualityState.DIAGNOSTIC,
            timingConditional.quality,
        )
    }

    /**
     * La mezcla de metodos entre intentos SELECCIONADOS degrada el estado a DIAGNOSTIC (Q2: no hay
     * compatibilidad revisada) pero NO descalifica `profileEligible` ni fabrica una etiqueta de UI
     * que diga otra cosa (la copia es de Q2): la lista de avisos es identica a la del mismo modelo
     * sin el flag.
     */
    @Test
    fun `mixed selected methods downgrade to diagnostic without inventing a ui warning`() {
        val clean = FvpPresenter.present(
            result = result(profile()),
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
        )
        val mixed = FvpPresenter.present(
            result = result(profile()),
            eligibility = eligibility(
                flags = listOf(FvpEligibilityFlag.MIXED_SELECTED_METHODS),
                timing = FvpTimingQuality.ALLOW,
            ),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
        )

        assertEquals(FvpQualityState.PROFILE, clean.quality)
        assertEquals(FvpQualityState.DIAGNOSTIC, mixed.quality)
        assertTrue("el flag de mezcla no descalifica la ruta normal", mixed.profileEligible)
        assertEquals("el flag no fabrica un aviso existente", clean.warnings, mixed.warnings)
    }

    @Test
    fun `push off outside the recommended window keeps profile and stays visible`() {
        val model = FvpPresenter.present(
            result = result(profile()),
            eligibility = eligibility(
                flags = listOf(FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE),
                timing = FvpTimingQuality.ALLOW,
            ),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
        )

        assertEquals("el aviso de h_po fuera de ventana es advisory", FvpQualityState.PROFILE, model.quality)
        assertTrue(FvpUiWarning.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE in model.warnings)
    }

    @Test
    fun `blocked and unvalidated series are incomplete and diagnostics stay diagnostic`() {
        val blocked = FvpPresenter.present(
            result = null,
            eligibility = FvpEligibility(
                conditionCount = 4,
                blocking = listOf(FvpEligibilityProblem.EXCLUSION_REASON_MISSING),
                flags = emptyList(),
                timingQuality = FvpTimingQuality.ALLOW,
            ),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
        )
        val diagnostic = FvpPresenter.present(
            result = result(profile(n = 3)),
            eligibility = eligibility(
                flags = listOf(FvpEligibilityFlag.FEW_CONDITIONS),
                timing = FvpTimingQuality.ALLOW,
                conditionCount = 3,
            ),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 3,
            timingQuality = FvpTimingQuality.ALLOW,
        )
        val withoutEligibility = FvpPresenter.present(
            result = result(profile()),
            eligibility = null,
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
        )

        assertEquals(FvpQualityState.INCOMPLETE, blocked.quality)
        assertEquals(FvpQualityState.DIAGNOSTIC, diagnostic.quality)
        assertEquals(
            "sin elegibilidad no se declara nada mejor que INCOMPLETE",
            FvpQualityState.INCOMPLETE,
            withoutEligibility.quality,
        )
    }

    /**
     * Q1/P2-3 (fix pass): `diagnosticOnly`/`conditionCount` rotulan la ejecucion MOSTRADA (recuento
     * PERSISTIDO en la vista original) y `quality` juzga la ELEGIBILIDAD actual (recuento derivado de
     * las condiciones GUARDADAS): cada campo declara SU fuente y pueden DIVERGIR. El backup actual
     * rechaza un `conditionCount` incoherente, pero una fila anterior o importada puede traerlo, y el
     * presentador no debe elegir un ganador ni mezclar las fuentes en silencio.
     */
    @Test
    fun `persisted count and eligibility count can legitimately disagree`() {
        // (a) Persistido 3 con 4 condiciones guardadas: el original rotula el recuento PERSISTIDO (3 ->
        // diagnostico) y la calidad, que si ve las 4 condiciones guardadas, declara PROFILE.
        val moreStoredConditions = FvpPresenter.present(
            result = null,
            eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW, conditionCount = 4),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 3,
            timingQuality = FvpTimingQuality.ALLOW,
            storedSnapshot = storedSnapshotFixture().copy(conditionCount = 3),
            storedSnapshotStatus = FvpSnapshotStatus.RECALCULATION_UNAVAILABLE,
            algorithmVersion = "fvp-poc-1.0",
            view = FvpResultView.ORIGINAL,
        )

        assertEquals("el recuento rotulado es el PERSISTIDO", 3, moreStoredConditions.conditionCount)
        assertTrue("3 condiciones en la ejecucion mostrada: diagnostico", moreStoredConditions.diagnosticOnly)
        assertEquals(
            "la elegibilidad actual SI ve las 4 condiciones guardadas",
            FvpQualityState.PROFILE,
            moreStoredConditions.quality,
        )

        // (b) El caso inverso: persistido 4 con 3 condiciones guardadas. El rotulo mostrado no es
        // diagnostico y la calidad, que ve 3 condiciones reales, si lo es.
        val fewerStoredConditions = FvpPresenter.present(
            result = null,
            eligibility = eligibility(
                flags = listOf(FvpEligibilityFlag.FEW_CONDITIONS),
                timing = FvpTimingQuality.ALLOW,
                conditionCount = 3,
            ),
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            conditionCount = 4,
            timingQuality = FvpTimingQuality.ALLOW,
            storedSnapshot = storedSnapshotFixture(),
            storedSnapshotStatus = FvpSnapshotStatus.RECALCULATION_UNAVAILABLE,
            algorithmVersion = "fvp-poc-1.0",
            view = FvpResultView.ORIGINAL,
        )

        assertEquals("el recuento rotulado es el PERSISTIDO", 4, fewerStoredConditions.conditionCount)
        assertFalse("4 condiciones persistidas no son diagnostico", fewerStoredConditions.diagnosticOnly)
        assertEquals(
            "la elegibilidad actual solo ve 3 condiciones guardadas",
            FvpQualityState.DIAGNOSTIC,
            fewerStoredConditions.quality,
        )

        // La divergencia es de FUENTE, no de numeros: los dos modelos pintan el snapshot persistido
        // (nada de la otra vista), y por eso el estado de calidad NO se puede derivar de
        // `diagnosticOnly` ni al reves.
        assertEquals(storedSnapshotFixture().r2!!, moreStoredConditions.r2!!, 0.0)
        assertTrue(moreStoredConditions.pointsUnavailableFromSource)
        assertTrue(fewerStoredConditions.pointsUnavailableFromSource)
    }

    // --- B4b: procedencia (avisos honestos, sin promocionar UNKNOWN/NULL) ------------------------

    /**
     * B4b: una fila con TODA la procedencia desconocida declara el PEOR caso de cada familia y no
     * promociona nada: masa sin declarar, carga sin procedencia, mapeo temporal no verificado
     * (incluido `null`, sin intento seleccionado) y contexto de adquisicion sin declarar. Los
     * avisos salen en el orden de declaracion del enum.
     */
    @Test
    fun unknownProvenanceIsDeclaredAsTheWorstCaseWithoutPromotion() {
        val warnings = fvpStoredProvenanceWarnings(
            bodyMassMeasurement = "UNKNOWN",
            acquisitionContextKind = "UNKNOWN",
                acquisitionContextKey = null,
            addedLoadSourceByCondition = listOf("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN"),
            selectedTimeMappingByCondition = listOf(null, "UNKNOWN", "UNKNOWN", "UNKNOWN"),
        )

        assertEquals(
            listOf(
                FvpUiWarning.BODY_MASS_UNKNOWN,
                FvpUiWarning.ADDED_LOAD_UNKNOWN,
                FvpUiWarning.TIME_MAPPING_UNVERIFIED,
                FvpUiWarning.ACQUISITION_CONTEXT_UNKNOWN,
            ),
            warnings,
        )
    }

    /**
     * B4b: la procedencia VERIFICADA (pesada declarada, carga pesada, mapeo verificado y misma
     * adquisicion declarada) no anade ningun aviso: la puerta no sobre-bloquea ni inventa problemas.
     */
    @Test
    fun verifiedProvenanceProducesNoProvenanceWarnings() {
        assertEquals(
            emptyList<FvpUiWarning>(),
            fvpStoredProvenanceWarnings(
                bodyMassMeasurement = "WEIGHED",
                acquisitionContextKind = "SAME_ACQUISITION",
                acquisitionContextKey = "ctx-1",
                addedLoadSourceByCondition = listOf("WEIGHED", "WEIGHED"),
                selectedTimeMappingByCondition = listOf("IDENTITY_VERIFIED", "IDENTITY_VERIFIED"),
            ),
        )
    }

    /**
     * B4b: el PEOR caso decide y `UNKNOWN` domina a `DECLARED`/`NOMINAL`; un contexto que esta
     * version no reconoce tampoco se promociona a "misma adquisicion".
     */
    @Test
    fun worstProvenanceCaseDecidesBodyMassLoadTimeAndContext() {
        // Masa declarada (no pesada) y carga nominal (no pesada): el aviso lo dice, no lo silencia.
        assertEquals(
            listOf(
                FvpUiWarning.BODY_MASS_DECLARED,
                FvpUiWarning.ADDED_LOAD_NOMINAL,
                FvpUiWarning.ACQUISITION_CONTEXT_COMPOSITE,
            ),
            fvpStoredProvenanceWarnings(
                bodyMassMeasurement = "DECLARED",
                acquisitionContextKind = "HISTORICAL_COMPOSITE",
                acquisitionContextKey = null,
                addedLoadSourceByCondition = listOf("WEIGHED", "NOMINAL", "WEIGHED"),
                selectedTimeMappingByCondition = listOf("IDENTITY_VERIFIED"),
            ),
        )
        // Con una condicion UNKNOWN la carga cae en el peor caso, aunque haya NOMINAL.
        assertEquals(
            listOf(FvpUiWarning.ADDED_LOAD_UNKNOWN, FvpUiWarning.ACQUISITION_CONTEXT_UNKNOWN),
            fvpStoredProvenanceWarnings(
                bodyMassMeasurement = "WEIGHED",
                acquisitionContextKind = "QA_FUTURE_CONTEXT",
                acquisitionContextKey = null,
                addedLoadSourceByCondition = listOf("WEIGHED", "NOMINAL", "UNKNOWN"),
                selectedTimeMappingByCondition = listOf("IDENTITY_VERIFIED"),
            ),
        )
        // SAME_ACQUISITION SIN clave declarada no prueba "misma adquisicion": aviso de desconocido.
        assertEquals(
            listOf(FvpUiWarning.ACQUISITION_CONTEXT_UNKNOWN),
            fvpStoredProvenanceWarnings(
                bodyMassMeasurement = "WEIGHED",
                acquisitionContextKind = "SAME_ACQUISITION",
                acquisitionContextKey = null,
                addedLoadSourceByCondition = listOf("WEIGHED"),
                selectedTimeMappingByCondition = listOf("IDENTITY_VERIFIED"),
            ),
        )
        // Masa sin declarar domina a declarada (el campo tiene UN solo valor: peor caso).
        assertEquals(
            listOf(FvpUiWarning.BODY_MASS_UNKNOWN),
            fvpStoredProvenanceWarnings(
                bodyMassMeasurement = "UNKNOWN",
                acquisitionContextKind = "SAME_ACQUISITION",
                acquisitionContextKey = "ctx-1",
                addedLoadSourceByCondition = listOf("WEIGHED"),
                selectedTimeMappingByCondition = listOf("IDENTITY_VERIFIED"),
            ),
        )
        // El mapeo temporal del intento seleccionado decide: UNKNOWN o sin intento = no verificado.
        for (mappings in listOf(
            listOf("IDENTITY_VERIFIED", "UNKNOWN"),
            listOf("IDENTITY_VERIFIED", null),
        )) {
            assertEquals(
                "mapeos $mappings",
                listOf(FvpUiWarning.TIME_MAPPING_UNVERIFIED),
                fvpStoredProvenanceWarnings(
                    bodyMassMeasurement = "WEIGHED",
                    acquisitionContextKind = "SAME_ACQUISITION",
                acquisitionContextKey = "ctx-1",
                    addedLoadSourceByCondition = listOf("WEIGHED", "WEIGHED"),
                    selectedTimeMappingByCondition = mappings,
                ),
            )
        }
    }

    /**
     * B4b: la ruta GUARDADA deriva los avisos de la ENTIDAD (masa, carga por condicion, mapeo del
     * intento que la fila registra y contexto) y los declara por el camino existente del
     * presentador en las DOS vistas (son de la fila, no de la ejecucion). La procedencia NO promociona
     * ni cambia los numeros, la calidad ni el snapshot: solo anade los avisos.
     */
    @Test
    fun storedRowProvenanceWarningsReachBothViewsAndChangeNothingElse() {
        val row = storedSeries(
            id = 1L,
            acquisitionContextKind = "UNKNOWN",
            bodyMassMeasurement = "UNKNOWN",
            addedLoadSources = listOf("UNKNOWN", "WEIGHED", "UNKNOWN", "NOMINAL"),
            timeMapping = "UNKNOWN",
        )
        val provenanceWarnings = row.provenanceWarningsForUi()
        assertEquals(
            listOf(
                FvpUiWarning.BODY_MASS_UNKNOWN,
                FvpUiWarning.ADDED_LOAD_UNKNOWN,
                FvpUiWarning.TIME_MAPPING_UNVERIFIED,
                FvpUiWarning.ACQUISITION_CONTEXT_UNKNOWN,
            ),
            provenanceWarnings,
        )

        val snapshot = matchingStoredSnapshot()
        val recalculated = result(profile())
        for (view in FvpResultView.entries) {
            val withoutProvenance = presentStoredRow(snapshot, recalculated, FvpSnapshotStatus.MATCHES, view)
            val withProvenance = presentStoredRow(
                snapshot,
                recalculated,
                FvpSnapshotStatus.MATCHES,
                view,
                provenanceWarnings = provenanceWarnings,
            )
            assertEquals(
                "[$view] la procedencia se declara tal cual, sin inventar ni eliminar avisos",
                provenanceWarnings,
                withProvenance.warnings,
            )
            assertEquals(
                "[$view] la procedencia no puede cambiar nada mas (numeros, calidad, snapshot)",
                withoutProvenance,
                withProvenance.copy(warnings = withoutProvenance.warnings),
            )
        }
    }

    // --- Fixtures -----------------------------------------------------------------------------

    private fun profile(
        f0: Double = 1500.0,
        sfv: Double = 700.0,
        v0: Double = 2.14,
        pmax: Double = 803.0,
        r2: Double = 0.96,
        n: Int = 4,
        warnings: List<FvpWarning> = emptyList(),
    ): FvpProfile = FvpProfile(
        f0N = f0,
        slopeFv = -sfv,
        sfvNsPerM = sfv,
        v0Mps = v0,
        pmaxW = pmax,
        r2 = r2,
        n = n,
        vMinMps = 0.8,
        vMaxMps = 1.4,
        fMinN = 1200.0,
        fMaxN = 1700.0,
        f0RelNPerKg = f0 / 80.0,
        pmaxRelWPerKg = pmax / 80.0,
        warnings = warnings,
    )

    private fun result(
        profile: FvpProfile,
        points: List<FvpConditionPoint> = defaultPoints(),
    ): FvpResult = FvpResult(
        evaluationId = "session-1",
        protocol = FvpProtocol.SJ,
        algorithmVersion = "fvp-poc-1.0",
        points = points,
        profile = profile,
    )

    private fun defaultPoints(): List<FvpConditionPoint> = listOf(
        point(id = "0kg", velocity = 1.45, force = 1710.0, power = 2480.0, height = 0.36),
        point(id = "20kg", velocity = 1.10, force = 1420.0, power = 1562.0, height = 0.28),
    )

    private fun point(
        id: String,
        velocity: Double,
        force: Double,
        power: Double,
        height: Double,
    ): FvpConditionPoint = FvpConditionPoint(
        conditionId = id,
        totalMassKg = 80.0,
        heightM = height,
        pushOffM = 0.4,
        forceN = force,
        velocityMps = velocity,
        powerW = power,
    )

    private fun eligibility(
        flags: List<FvpEligibilityFlag>,
        timing: FvpTimingQuality,
        conditionCount: Int = 4,
    ): FvpEligibility = FvpEligibility(
        conditionCount = conditionCount,
        blocking = emptyList(),
        flags = flags,
        timingQuality = timing,
    )

    /**
     * Snapshot persistido de auditoria (mismos numeros que el fixture inline del test de aviso) con
     * la invariante de signo coherente: `sfvNsPerM = -slopeFv`.
     */
    private fun storedSnapshotFixture(): FvpStoredSnapshot = FvpStoredSnapshot(
        f0N = 1600.0,
        slopeFv = -520.0,
        sfvNsPerM = 520.0,
        v0Mps = 3.07,
        pmaxW = 1230.0,
        r2 = 0.94,
        conditionCount = 4,
        vMinMps = 0.8,
        vMaxMps = 1.4,
        fMinN = 1200.0,
        fMaxN = 1700.0,
        f0RelNPerKg = 20.0,
        pmaxRelWPerKg = 15.4,
    )

    /**
     * Fila guardada presentada en la vista pedida (S2/D3). Los parametros que no cambian entre
     * vistas son los de la ruta guardada: elegibilidad ALLOW y `algorithmVersion` PERSISTIDA (por
     * defecto la del recalculo, como en una fila `MATCHES`/`MISMATCH`).
     */
    private fun presentStoredRow(
        snapshot: FvpStoredSnapshot,
        recalculated: FvpResult,
        status: FvpSnapshotStatus,
        view: FvpResultView,
        storedVersion: String = recalculated.algorithmVersion,
        storedWarnings: List<String> = emptyList(),
        pushOffSource: String? = null,
        provenanceWarnings: List<FvpUiWarning> = emptyList(),
    ): FvpResultUiModel = FvpPresenter.present(
        result = recalculated,
        eligibility = eligibility(flags = emptyList(), timing = FvpTimingQuality.ALLOW),
        protocol = FvpProtocol.SJ,
        bodyMassKg = 80.0,
        conditionCount = recalculated.profile.n,
        timingQuality = FvpTimingQuality.ALLOW,
        storedWarnings = storedWarnings,
        storedSnapshot = snapshot,
        storedSnapshotStatus = status,
        algorithmVersion = storedVersion,
        pushOffSource = pushOffSource,
        provenanceWarnings = provenanceWarnings,
        view = view,
    )

    /**
     * Snapshot persistido con TODOS los numeros distintos del recalculo del fixture (perfil de 4
     * condiciones): cualquier campo que se tomase de la otra ejecucion cambia el valor esperado, de
     * modo que una vista hibrida no puede pasar desapercibida.
     */
    private fun mismatchedStoredSnapshot(): FvpStoredSnapshot = FvpStoredSnapshot(
        f0N = 1611.5,
        slopeFv = -533.5,
        sfvNsPerM = 533.5,
        v0Mps = 3.011,
        pmaxW = 1211.5,
        r2 = 0.911,
        conditionCount = 3,
        vMinMps = 0.71,
        vMaxMps = 1.31,
        fMinN = 1211.5,
        fMaxN = 1711.5,
        f0RelNPerKg = 21.11,
        pmaxRelWPerKg = 15.11,
    )

    /** Snapshot cuyos numeros son EXACTAMENTE los del perfil recalculado (fila `MATCHES`). */
    private fun matchingStoredSnapshot(): FvpStoredSnapshot {
        val profile = profile()
        return FvpStoredSnapshot(
            f0N = profile.f0N,
            slopeFv = profile.slopeFv,
            sfvNsPerM = profile.sfvNsPerM,
            v0Mps = profile.v0Mps,
            pmaxW = profile.pmaxW,
            r2 = profile.r2,
            conditionCount = profile.n,
            vMinMps = profile.vMinMps,
            vMaxMps = profile.vMaxMps,
            fMinN = profile.fMinN,
            fMaxN = profile.fMaxN,
            f0RelNPerKg = profile.f0RelNPerKg,
            pmaxRelWPerKg = profile.pmaxRelWPerKg,
        )
    }

    /**
     * Serie guardada minima para las reglas de comparacion (W2.3): solo los campos que entran en la
     * regla son parametrizables (protocolo, modo/procedimiento/valor de h_po, masa, metodos
     * seleccionados y version del algoritmo); el resto son valores fijos de una serie fisica.
     */
    private fun storedSeries(
        id: Long,
        protocolId: String = "SJ",
        pushOffMode: String = "COMMON",
        pushOffProtocol: String? = "LANDMARK_EXTENDED_MINUS_CROUCH",
        pushOffM: Double = 0.40,
        bodyMassKg: Double = 80.0,
        algorithmVersion: String = "fvp-poc-1.0",
        conditionKeys: List<String> = listOf("0kg", "20kg", "40kg", "60kg"),
        /** Carga adicional por condicion; por defecto 0/20/40/60 kg segun el indice. */
        loads: List<Double>? = null,
        method: String = "FLIGHT_TIME_VIDEO",
        /** h_po persistido por condicion (override de `PER_CONDITION`); por defecto, el de la raiz. */
        conditionPushOffM: List<Double>? = null,
        /**
         * B4b: procedencia declarada de la fila. Por defecto VERIFICADA (misma adquisicion con la
         * misma clave, masa pesada, carga pesada y mapeo temporal verificado), de modo que los
         * casos de comparacion de este fichero siguen siendo series comparables de referencia.
         */
        acquisitionContextKind: String = "SAME_ACQUISITION",
        acquisitionContextKey: String? = "ctx-a",
        bodyMassMeasurement: String = "WEIGHED",
        addedLoadSources: List<String>? = null,
        timeMapping: String = "IDENTITY_VERIFIED",
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
                selectedAttemptOrdinal = 1,
                addedLoadSource = addedLoadSources?.getOrNull(index) ?: "WEIGHED",
            )
        }
        val attempts = conditions.map { condition ->
            FvpAttemptEntity(
                id = condition.id,
                conditionId = condition.id,
                ordinal = 1,
                heightM = condition.selectedHeightM,
                method = method,
                timeMapping = timeMapping,
                timeMappingEvidenceKey = "ev-1",
            )
        }
        return StoredFvpAssessment(
            assessment = FvpAssessmentEntity(
                id = id,
                sessionKey = "session-$id",
                athleteId = 1L,
                protocolId = protocolId,
                dateTime = 1_700_000_000_000L,
                bodyMassKg = bodyMassKg,
                bodyMassSource = "MANUAL",
                pushOffMode = pushOffMode,
                pushOffM = pushOffM,
                pushOffSource = "MEASURED",
                pushOffLandmark = "TROCANTER",
                pushOffProtocol = pushOffProtocol,
                pushOffMeasuredAt = 1_700_000_000_000L,
                algorithmVersion = algorithmVersion,
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
                acquisitionContextKind = acquisitionContextKind,
                acquisitionContextKey = acquisitionContextKey,
                bodyMassMeasurement = bodyMassMeasurement,
            ),
            conditions = conditions,
            attempts = attempts,
            eligibility = eligibility(
                flags = emptyList(),
                timing = FvpTimingQuality.ALLOW,
                conditionCount = conditions.size,
            ),
            result = result(profile()),
            snapshotStatus = FvpSnapshotStatus.MATCHES,
        )
    }
}
