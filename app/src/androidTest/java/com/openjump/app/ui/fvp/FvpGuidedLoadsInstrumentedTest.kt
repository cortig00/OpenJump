package com.openjump.app.ui.fvp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.JumpRepository
import com.openjump.app.data.FvpRepository
import com.openjump.app.settings.ShortLengthUnit
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Slice B de cargas/contexto en la PANTALLA REAL de Condiciones
 * (`.pi/plans/fvp-guided-flow-implementation.md`).
 *
 * Cubre, como capa de PRESENTACION (sin tocar elegibilidad, dominio ni persistencia):
 * 1. un UNICO editor activo por identidad estable (`draftKey`) + resumen compacto por carga;
 * 2. "Anadir condicion" abre la carga nueva y borrar la activa elige la restante de forma honesta;
 * 3. el texto parcial tecleado en una carga se conserva al cambiar de carga (SaveableStateHolder);
 * 4. Revisar una carga inactiva la ABRE antes de desplazar (el ancla solo existe con su editor);
 * 5. "Origen de intentos" compacto arriba: `SAME_ACQUISITION` sin clave BLOQUEA el calculo con el
 *    motivo exacto y destino de revision; `UNKNOWN` sigue siendo valido y no hay formula nueva;
 * 6. cambiar el contexto retira las confirmaciones de reutilizacion por el helper del borrador y
 *    la declaracion (tipo + clave) sobrevive a salir y volver a la pantalla;
 * 7. una serie completa y elegible sigue calculando EXACTAMENTE como antes.
 *
 * Capturas OPT-IN (`-e fvpGuidedEvidence true`) en `cacheDir/fvp_guided_loads`; el perfil de
 * unidades se puede forzar con `-e fvpUnitProfile metric|us|custom`. Sin flags no se escribe nada.
 */
@RunWith(AndroidJUnit4::class)
class FvpGuidedLoadsInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: JumpDatabase
    private lateinit var viewModel: FvpViewModel
    private var athleteId: Long = 0L
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val screenVisible = mutableStateOf(true)

    @Before
    fun setUp() {
        FvpDraftSession.reset()
        screenVisible.value = true
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JumpDatabase::class.java,
        ).build()
        viewModel = FvpViewModel(FvpRepository(database.fvpDao()), JumpRepository(database.jumpDao()))
        athleteId = runBlocking {
            database.athleteDao().insert(
                AthleteEntity(displayName = ATHLETE_NAME, weightKg = 80.0, createdAt = 1L, updatedAt = 1L),
            )
        }
    }

    @After
    fun tearDown() {
        compose.waitForIdle()
        FvpDraftSession.reset()
        database.close()
    }

    // ------------------------------------------------------------------------------------------
    // 1: un UNICO editor activo, resumen compacto por carga y la misma superficie dentro
    // ------------------------------------------------------------------------------------------

    @Test
    fun oneActiveEditorAndCompactSummaryPerLoad() {
        val draft = completeDraft()
        FvpDraftSession.adopt(draft)
        setConditionsContent()
        val registered = context.getString(R.string.fvp_load_status_registered)
        val pending = context.getString(R.string.common_pending)

        // Solo la PRIMERA carga tiene editor; las demas muestran su resumen.
        compose.onNodeWithTag("fvp-condition-0").assertExists()
        compose.onNodeWithTag("fvp-condition-1").assertDoesNotExist()
        compose.onNodeWithTag("fvp-load-row-0").assertIsSelected()
        compose.onNodeWithTag("fvp-load-status-0", useUnmergedTree = true).assertTextEquals(registered)
        compose.onNodeWithTag("fvp-load-status-1", useUnmergedTree = true).assertTextEquals(registered)
        compose.onNodeWithTag("fvp-load-active-0").assertExists()
        compose.onNodeWithTag("fvp-load-active-1").assertDoesNotExist()

        // MISMO editor de siempre: procedencia UNKNOWN, intento valido y reutilizacion disponibles.
        compose.onNodeWithTag("fvp-condition-load-source-0-UNKNOWN").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fvp-attempt-valid-0", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("fvp-conditions-count-hint").assertExists()
        compose.onNodeWithTag("fvp-attempts-count-hint").assertExists()

        // "Anadir condicion": la carga nueva se abre y su resumen nace PENDIENTE (sin auto-rellenar
        // ninguna otra carga ni cambiar el minimo: el borrador solo gana una condicion vacia).
        compose.onNodeWithTag("fvp-conditions-add").performClick()
        compose.waitForIdle()
        val afterAdd = requireNotNull(FvpDraftSession.draft.value)
        assertEquals(draft.conditions.size + 1, afterAdd.conditions.size)
        compose.onNodeWithTag("fvp-condition-4").assertExists()
        compose.onNodeWithTag("fvp-condition-0").assertDoesNotExist()
        compose.onNodeWithTag("fvp-load-row-4").assertIsSelected()
        compose.onNodeWithTag("fvp-load-status-4", useUnmergedTree = true).assertTextEquals(pending)
        capture("guided-loads-summary")
    }

    // ------------------------------------------------------------------------------------------
    // 2: borrar la carga activa elige la restante de forma honesta (identidad, no indice reciclado)
    // ------------------------------------------------------------------------------------------

    @Test
    fun deletingTheActiveLoadKeepsAnHonestSelection() {
        FvpDraftSession.adopt(completeDraft())
        val before = requireNotNull(FvpDraftSession.draft.value)
        val deletedKey = before.conditions[0].draftKey
        val secondKey = before.conditions[1].draftKey
        setConditionsContent()

        compose.onNodeWithTag("fvp-condition-remove-0").performScrollTo().performClick()
        compose.waitForIdle()

        val after = requireNotNull(FvpDraftSession.draft.value)
        assertEquals(3, after.conditions.size)
        assertFalse(after.conditions.any { it.draftKey == deletedKey })
        // La activa pasa a la carga que ocupaba la posicion siguiente (la antigua segunda), no a
        // una posicion reciclada por indice.
        assertEquals(secondKey, after.conditions[0].draftKey)
        compose.onNodeWithTag("fvp-load-active-0").assertExists()
        compose.onNodeWithTag("fvp-load-key-0", useUnmergedTree = true).assertTextEquals("20kg")
        compose.onNodeWithTag("fvp-condition-0").assertExists()
        compose.onNodeWithTag("fvp-condition-3").assertDoesNotExist()
        capture("guided-loads-delete")
    }

    // ------------------------------------------------------------------------------------------
    // 3: texto parcial conservado al cambiar de carga (no se re-siembra ni se pierde)
    // ------------------------------------------------------------------------------------------

    @Test
    fun partialTypedTextSurvivesSwitchingLoads() {
        val base = completeDraft()
        FvpDraftSession.adopt(
            base.copy(
                conditions = base.conditions.mapIndexed { index, condition ->
                    if (index == 0) condition.copy(attempts = listOf(FvpAttemptDraft())) else condition
                },
            ),
        )
        setConditionsContent()

        // Un texto que NO parsea como altura ("1."): el borrador queda con altura desconocida.
        compose.onNodeWithTag("fvp-attempt-height-0").performScrollTo().performTextInput("1.")
        compose.waitForIdle()
        assertEquals(null, FvpDraftSession.draft.value?.conditions?.get(0)?.attempts?.get(0)?.heightM)

        // Cambiar de carga y volver: el texto tecleado sigue ahi.
        compose.onNodeWithTag("fvp-load-row-1").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-condition-1").assertExists()
        compose.onNodeWithTag("fvp-condition-0").assertDoesNotExist()

        compose.onNodeWithTag("fvp-load-row-0").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-condition-0").assertExists()
        compose.onNodeWithTag("fvp-attempt-height-0").assertTextContains("1.", substring = true)
        capture("guided-loads-partial-text")
    }

    // ------------------------------------------------------------------------------------------
    // 4: revisar una carga INACTIVA la abre antes de desplazar (el ancla solo existe con editor)
    // ------------------------------------------------------------------------------------------

    @Test
    fun removingAnEarlierAttemptPreservesTheMovedAttemptsPartialText() {
        val base = completeDraft()
        val retainedAttempt = FvpAttemptDraft()
        FvpDraftSession.adopt(base.copy(conditions = base.conditions.mapIndexed { index, condition ->
            if (index == 0) condition.copy(attempts = condition.attempts + retainedAttempt) else condition
        }))
        setConditionsContent()
        compose.onNodeWithTag("fvp-attempt-height-1").performScrollTo().performTextInput("-")
        compose.waitForIdle()
        assertEquals(null, FvpDraftSession.draft.value?.conditions?.get(0)?.attempts?.get(1)?.heightM)
        compose.onNodeWithTag("fvp-attempt-remove-0").performScrollTo().performClick()
        compose.waitForIdle()
        val moved = requireNotNull(FvpDraftSession.draft.value).conditions[0].attempts.single()
        assertEquals(retainedAttempt.draftKey, moved.draftKey)
        assertEquals(null, moved.heightM)
        compose.onNodeWithTag("fvp-attempt-height-0").assertTextContains("-")
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
    }

    @Test
    fun reviewOpensTheInactiveLoadBeforeScrolling() {
        val base = completeDraft()
        FvpDraftSession.adopt(
            base.copy(
                conditions = base.conditions.mapIndexed { index, condition ->
                    if (index == 1) {
                        condition.copy(attempts = condition.attempts.map { it.copy(heightM = 1.5) })
                    } else {
                        condition
                    }
                },
            ),
        )
        val before = requireNotNull(FvpDraftSession.draft.value)
        setConditionsContent()

        // El pie dice el motivo exacto y la carga afectada esta CERRADA.
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        compose.onNodeWithTag("fvp-condition-1").assertDoesNotExist()
        compose.onNodeWithTag("fvp-conditions-review").assertIsDisplayed().performClick()
        compose.waitForIdle()

        // Revisar ABRE la carga afectada y despues desplaza su campo: sin foco y sin mutar nada.
        compose.onNodeWithTag("fvp-load-active-1").assertExists()
        compose.onNodeWithTag("fvp-condition-1").assertExists()
        val affected = hasTestTag("fvp-attempt-height-0") and hasAnyAncestor(hasTestTag("fvp-condition-1"))
        compose.onNode(affected).assertIsDisplayed().assertIsNotFocused()
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        assertEquals(before, FvpDraftSession.draft.value)
        capture("guided-loads-review-inactive")
    }

    // ------------------------------------------------------------------------------------------
    // 5: SAME_ACQUISITION sin clave bloquea con motivo exacto; UNKNOWN sigue valido
    // ------------------------------------------------------------------------------------------

    @Test
    fun sameAcquisitionWithoutKeyBlocksAndUnknownStaysValid() {
        var calculateCalls = 0
        FvpDraftSession.adopt(completeDraft())
        setConditionsContent(onCalculate = { calculateCalls += 1 })

        // Default honesto: UNKNOWN no bloquea y la serie completa calcula con la MISMA puerta.
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsEnabled()
        compose.onNodeWithTag("fvp-conditions-first-reason").assertDoesNotExist()

        compose.onNodeWithTag("fvp-acquisition-context-SAME_ACQUISITION").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(
            "SAME_ACQUISITION",
            FvpDraftSession.draft.value?.acquisitionContextKind,
        )
        assertEquals(null, FvpDraftSession.draft.value?.acquisitionContextKey)
        // Comprobacion PROPIA de la pantalla: bloquea y muestra el motivo EXACTO con su campo.
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        compose.onNodeWithTag("fvp-conditions-first-reason")
            .assertTextEquals(context.getString(R.string.fvp_acquisition_context_key_required))
        compose.onNodeWithTag("fvp-conditions-blocking").assertExists()
        compose.onNodeWithTag("fvp-conditions-review").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-acquisition-context-key")
            .assertIsDisplayed()
            .assertIsNotFocused()
        capture("guided-loads-context-required")

        // La clave declarada desbloquea; el texto se canonicaliza (trim) en el borrador.
        compose.onNodeWithTag("fvp-acquisition-context-key").performTextInput(" sesion-a ")
        compose.waitForIdle()
        assertEquals("sesion-a", FvpDraftSession.draft.value?.acquisitionContextKey)
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsEnabled()

        // HISTORICAL no pide clave por defecto; la metadata existente sigue consultable y
        // editable al solicitar detalle, sin cambiar ni el valor ni el gate al abrir/cerrar.
        compose.onNodeWithTag("fvp-acquisition-context-HISTORICAL_COMPOSITE").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-acquisition-context-key").assertDoesNotExist()
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsEnabled()
        assertEquals("sesion-a", FvpDraftSession.draft.value?.acquisitionContextKey)
        compose.onNodeWithTag("fvp-acquisition-context-help-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-acquisition-context-key").assertTextContains("sesion-a", substring = true)
        assertEquals("sesion-a", FvpDraftSession.draft.value?.acquisitionContextKey)
        compose.onNodeWithTag("fvp-acquisition-context-help-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-acquisition-context-key").assertDoesNotExist()

        // Volver a UNKNOWN no pierde el texto tecleado ni bloquea; volver a SAME lo restaura.
        compose.onNodeWithTag("fvp-acquisition-context-UNKNOWN").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("UNKNOWN", FvpDraftSession.draft.value?.acquisitionContextKind)
        assertEquals(null, FvpDraftSession.draft.value?.acquisitionContextKey)
        compose.onNodeWithTag("fvp-acquisition-context-key").assertDoesNotExist()
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsEnabled()

        compose.onNodeWithTag("fvp-acquisition-context-SAME_ACQUISITION").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-acquisition-context-key").assertTextContains("sesion-a", substring = true)
        assertEquals("sesion-a", FvpDraftSession.draft.value?.acquisitionContextKey)
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsEnabled()

        compose.onNodeWithTag("fvp-conditions-calculate").performClick()
        compose.waitForIdle()
        assertEquals("calcular no se sustituye: la accion existente sigue siendo la unica", 1, calculateCalls)
        capture("guided-loads-context-declared")
    }

    // ------------------------------------------------------------------------------------------
    // 6: cambiar contexto invalida reutilizacion y la declaracion sobrevive al volver
    // ------------------------------------------------------------------------------------------

    @Test
    fun changingContextInvalidatesReuseConfirmationAndSurvivesReturn() {
        val base = completeDraft()
        FvpDraftSession.adopt(
            base.copy(
                conditions = base.conditions.mapIndexed { index, condition ->
                    if (index == 0) {
                        condition.copy(
                            attempts = condition.attempts.map {
                                it.copy(reuseConfirmedAt = 123L, reuseConfirmationVersion = "fvp-reuse-1")
                            },
                        )
                    } else {
                        condition
                    }
                },
            ),
        )
        assertTrue(
            "el escenario debe partir de una confirmacion durable",
            requireNotNull(FvpDraftSession.draft.value).conditions[0].attempts[0].reuseConfirmedAt != null,
        )
        setConditionsContent(onCalculate = {})

        // HISTORICAL_COMPOSITE cambia el contexto: el helper del borrador retira las confirmaciones
        // de reutilizacion en la MISMA mutacion (la aceptacion incluye el contexto de la serie).
        compose.onNodeWithTag("fvp-acquisition-context-HISTORICAL_COMPOSITE").performScrollTo().performClick()
        compose.waitForIdle()
        val afterKind = requireNotNull(FvpDraftSession.draft.value)
        assertEquals("HISTORICAL_COMPOSITE", afterKind.acquisitionContextKind)
        assertEquals(null, afterKind.conditions[0].attempts[0].reuseConfirmedAt)
        assertEquals(null, afterKind.conditions[0].attempts[0].reuseConfirmationVersion)

        // La clave declarada se guarda y la declaracion SOBREVIVE a salir y volver a la pantalla
        // (la fuente de verdad es el borrador, no una copia local de la pantalla).
        compose.onNodeWithTag("fvp-acquisition-context-key").assertDoesNotExist()
        compose.onNodeWithTag("fvp-acquisition-context-help-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-acquisition-context-key").performTextInput("ctx-una")
        compose.waitForIdle()
        leaveAndReturn()
        assertEquals("HISTORICAL_COMPOSITE", FvpDraftSession.draft.value?.acquisitionContextKind)
        assertEquals("ctx-una", FvpDraftSession.draft.value?.acquisitionContextKey)
        compose.onNodeWithTag("fvp-acquisition-context-HISTORICAL_COMPOSITE").assertIsSelected()
        compose.onNodeWithTag("fvp-acquisition-context-key").assertDoesNotExist()
        compose.onNodeWithTag("fvp-acquisition-context-help-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-acquisition-context-key").assertTextContains("ctx-una", substring = true)
        capture("guided-loads-context-survives")
    }

    // ------------------------------------------------------------------------------------------
    // 7: la serie completa no cambia de comportamiento (misma puerta, cero muro inicial)
    // ------------------------------------------------------------------------------------------

    @Test
    fun completeSeriesKeepsTheExistingGateAndCompactEntry() {
        FvpDraftSession.adopt(completeDraft())
        setConditionsContent()
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsEnabled()
        compose.onNodeWithTag("fvp-conditions-blocking").assertDoesNotExist()
        compose.onNodeWithTag("fvp-conditions-first-reason").assertDoesNotExist()
        compose.onNodeWithTag("fvp-flow-progress").assertIsDisplayed()
        compose.onNodeWithTag("fvp-capture-header").assertIsDisplayed()
        compose.onNodeWithTag("fvp-acquisition-context").assertIsDisplayed()
        capture("guided-loads-complete")
    }

    // ------------------------------------------------------------------------------------------
    // Infraestructura
    // ------------------------------------------------------------------------------------------

    /** Serie SJ completa y fisica (4 cargas, altura decreciente, h_po comun declarado). */
    private fun completeDraft(): FvpDraft = FvpDraftSession.begin(athleteId, FvpProtocol.SJ).copy(
        bodyMassKg = 80.0,
        bodyMassFromProfile = true,
        pushOffMode = FvpPushOffUiMode.COMMON,
        commonPushOffM = 0.40,
        pushOffLandmark = "TROCANTER",
        pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
        pushOffMeasuredAt = 1_699_500_000_000L,
        conditions = listOf(
            condition("0kg", 0.0, 0.40),
            condition("20kg", 20.0, 0.32),
            condition("40kg", 40.0, 0.25),
            condition("60kg", 60.0, 0.18),
        ),
    )

    private fun condition(key: String, loadKg: Double, heightM: Double): FvpConditionDraft =
        FvpConditionDraft(
            conditionKey = key,
            addedLoadKg = loadKg,
            attempts = listOf(
                FvpAttemptDraft(
                    heightM = heightM,
                    method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                    sourceFps = 240.0,
                ),
            ),
        )

    private fun setConditionsContent(onCalculate: () -> Unit = {}) {
        compose.setContent {
            OpenJumpTheme {
                val profile = when (InstrumentationRegistry.getArguments().getString("fvpUnitProfile")) {
                    "us" -> UnitProfile.UNITED_STATES
                    "custom" -> UnitProfile.METRIC.copy(shortLength = ShortLengthUnit.INCH)
                    else -> UnitProfile.METRIC
                }
                CompositionLocalProvider(LocalUnitSystem provides profile) {
                    if (screenVisible.value) {
                        FvpConditionsScreen(
                            onBack = {},
                            onCalculate = onCalculate,
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Sale de la pantalla y vuelve: el contexto debe venir del borrador, no de una copia local. */
    private fun leaveAndReturn() {
        compose.runOnIdle { screenVisible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { screenVisible.value = true }
        compose.waitForIdle()
    }

    /** Capturas OPT-IN: evidencia visual, nunca un requisito de la pasada por defecto. */
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("fvpGuidedEvidence") != "true") return
        compose.waitForIdle()
        val directory = File(context.cacheDir, "fvp_guided_loads").apply { mkdirs() }
        val file = File(directory, "$name.png")
        FileOutputStream(file).use { out ->
            val bitmap: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        assertTrue("la captura opt-in debe existir: ${file.name}", file.length() > 0)
    }

    private companion object {
        const val ATHLETE_NAME = "Guided Loads QA"
    }
}
