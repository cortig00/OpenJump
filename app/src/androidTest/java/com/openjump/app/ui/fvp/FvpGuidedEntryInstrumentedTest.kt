package com.openjump.app.ui.fvp

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.fvp.FvpProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Entrada GUIADA del flujo F–V–P sobre la pantalla REAL ([FvpSetupScreen]): tres paginas locales
 * (INTRO / DATA / MEDICION) de un unico dueño de estado.
 *
 * Lo que comprueba (plan `fvp-guided-flow-implementation.md`, slice A):
 * 1. la introduccion aparece en el PRIMER uso, se puede SALTAR sin confirmar nada y no vuelve a
 *    aparecer cuando la preferencia de UI ya la vio;
 * 2. DATA valida SOLO sus datos (atleta, protocolo, masa): pasar a MEDICION no escribe en el
 *    borrador (no hay commit parcial) y el campo de h_po no se compone en esa pagina;
 * 3. la accion final de MEDICION conserva los gates completos (h_po, hito, procedimiento) y es la
 *    UNICA que confirma; el contexto de adquisicion del borrador vivo NO se sobrescribe (su dueño
 *    pasa a Condiciones en el slice B);
 * 4. el gesto/tecla de sistema vuelve a la pagina ANTERIOR sin perder el texto tecleado, y la
 *    introduccion reabierta como ayuda vuelve a la pagina desde la que se abrio;
 * 5. el estado guardado/restaurado conserva la pagina y el texto; el esquema de h_po sigue BAJO
 *    DEMANDA y el aviso de aproximacion de campo sigue siempre visible.
 *
 * Esta prueba se COMPILA aqui; no se ejecuta en esta estacion (sin AVD). NO demuestra nada sobre
 * hardware fisico ni sobre lectores de pantalla hablados.
 */
@RunWith(AndroidJUnit4::class)
class FvpGuidedEntryInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val athleteId = 7L

    /** Doble en memoria de la preferencia de UI (no escribe en disco ni en el borrador). */
    private class FakeIntroductionStore(seen: Boolean = false) : FvpIntroductionStore {
        var seen = seen
            private set

        override fun hasSeenIntroduction(): Boolean = seen

        override fun markIntroductionSeen() {
            seen = true
        }
    }

    @Before
    fun setUp() {
        FvpDraftSession.reset()
    }

    @After
    fun tearDown() {
        FvpDraftSession.reset()
    }

    @Test
    fun firstUseIntroCanBeSkippedAndDoesNotCommitAnything() {
        val store = FakeIntroductionStore(seen = false)
        setSetupContent(store)

        compose.onNodeWithTag("fvp-intro").assertIsDisplayed()
        compose.onNodeWithTag("fvp-page-data").assertDoesNotExist()

        compose.onNodeWithTag("fvp-intro-skip").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("fvp-page-data").assertIsDisplayed()
        assertTrue("saltar la introduccion debe recordarse como vista", store.seen)
        assertNull("la introduccion no confirma ningun dato", FvpDraftSession.draft.value)
    }

    @Test
    fun seenIntroStartsOnDataAndDataPageValidatesOnlyItsOwnFields() {
        val store = FakeIntroductionStore(seen = true)
        setSetupContent(store)

        // La pagina de DATOS no compone ningun campo del paso de MEDICION.
        compose.onNodeWithTag("fvp-page-data").assertIsDisplayed()
        compose.onNodeWithTag("fvp-intro").assertDoesNotExist()
        // Timeline compartido: Datos conserva la primera posicion, sin pasos nuevos.
        val dataLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.fvp_step_data)
        compose.onNodeWithText(dataLabel).assertExists()
        compose.onNodeWithTag("fvp-flow-progress").assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-pushoff").assertDoesNotExist()
        compose.onNodeWithTag("fvp-setup-landmark").assertDoesNotExist()

        // Atleta elegido + masa sugerida del perfil: DATA esta completo sin declarar h_po.
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled()
        compose.onNodeWithTag("fvp-setup-continue-data").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("fvp-page-measurement").assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-pushoff").assertExists()
        // Nada se ha confirmado al cambiar de pagina.
        assertNull("DATA -> MEDICION no puede hacer un commit parcial", FvpDraftSession.draft.value)
        // La accion FINAL sigue bloqueada hasta completar los metadatos de h_po.
        compose.onNodeWithTag("fvp-setup-continue").assertIsNotEnabled()
    }

    @Test
    fun dataContinueIsBlockedWhenBodyMassIsInvalidAndMeasurementCaretagIsUnchanged() {
        val store = FakeIntroductionStore(seen = true)
        setSetupContent(store)

        compose.onNodeWithTag("fvp-setup-mass").performScrollTo().performTextClearance()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-setup-mass").performTextInput(localeDecimal("0"))
        compose.waitForIdle()

        compose.onNodeWithTag("fvp-setup-continue-data").assertIsNotEnabled()
        compose.onNodeWithTag("fvp-page-measurement").assertDoesNotExist()
        assertNull(FvpDraftSession.draft.value)
    }

    @Test
    fun measurementRequiresFullGateAndKeepsLiveDraftAcquisitionContextOnCommit() {
        // Borrador VIVO de la MISMA identidad que ya declaro su contexto (como hara Condiciones en
        // el slice B): el commit del paso 1 no puede sobrescribirlo al volver atras.
        FvpDraftSession.begin(athleteId, FvpProtocol.SJ)
        FvpDraftSession.update { draft ->
            draft.copy(acquisitionContextKind = "SAME_ACQUISITION", acquisitionContextKey = "ctx-guided")
        }
        val store = FakeIntroductionStore(seen = true)
        var continued = 0
        setSetupContent(store, onContinue = { continued += 1 })

        // El borrador vivo sembrado no trae masa (NULL). DATA solo valida sus datos, pero el gate
        // final exige la serie completa: se declara la masa EN SU PAGINA antes de avanzar (no se
        // inyecta un dato por la puerta de atras ni se relaja el gate).
        compose.onNodeWithTag("fvp-setup-mass").performScrollTo().performTextClearance()
        compose.onNodeWithTag("fvp-setup-mass").performTextInput(localeDecimal("70"))
        compose.waitForIdle()
        goToMeasurement()
        val finalCta = "fvp-setup-continue"
        compose.onNodeWithTag(finalCta).assertIsNotEnabled()

        compose.onNodeWithTag("fvp-setup-pushoff").performScrollTo()
            .performTextInput(localeDecimal("40"))
        compose.onNodeWithTag("fvp-setup-landmark").performScrollTo().performTextInput("TROCANTER")
        compose.waitForIdle()
        compose.onNodeWithTag(finalCta).assertIsNotEnabled()
        compose.onNodeWithTag("fvp-setup-procedure").performScrollTo().performTextInput("ALTURA_EXTENDIDA_MENOS_SENTADILLA")
        compose.waitForIdle()
        compose.onNodeWithTag(finalCta).assertIsEnabled()

        // Toda la validacion anterior no ha escrito nada: la unica mutacion es la accion final.
        assertEquals("ctx-guided", FvpDraftSession.draft.value?.acquisitionContextKey)
        // La accion final vive en el pie del Scaffold (fuera del scroll): se pulsa directamente, sin
        // performScrollTo, que exigiria un ancestro con accion de scroll inexistente.
        compose.onNodeWithTag(finalCta).performClick()
        compose.waitForIdle()

        assertEquals(1, continued)
        val committed = FvpDraftSession.draft.value
        assertEquals(athleteId, committed?.athleteId)
        assertEquals(0.40, committed?.commonPushOffM ?: Double.NaN, 0.001)
        assertEquals("TROCANTER", committed?.pushOffLandmark)
        // El contexto declarado sigue EXACTAMENTE igual: su dueño es Condiciones.
        assertEquals("SAME_ACQUISITION", committed?.acquisitionContextKind)
        assertEquals("ctx-guided", committed?.acquisitionContextKey)
    }

    @Test
    fun systemBackReturnsToPreviousPageKeepingTypedTextAndHelpIntroReturnsToIt() {
        val store = FakeIntroductionStore(seen = true)
        setSetupContent(store)
        goToMeasurement()

        compose.onNodeWithTag("fvp-setup-pushoff").performScrollTo().performTextInput(localeDecimal("42"))
        compose.waitForIdle()

        pressSystemBack()
        compose.onNodeWithTag("fvp-page-data").assertIsDisplayed()
        compose.onNodeWithTag("fvp-page-measurement").assertDoesNotExist()

        compose.onNodeWithTag("fvp-setup-continue-data").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-setup-pushoff").assertTextContains(localeDecimal("42"))

        // Introduccion reabierta como AYUDA: vuelve a la pagina actual, no al principio.
        compose.onNodeWithTag("fvp-intro-action").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-intro").assertIsDisplayed()
        // La INTRO se compone entera en su columna con scroll: volver por su accion semantica evita
        // depender de la geometria (el boton puede quedar dentro del viewport sin scroll real).
        compose.onNodeWithTag("fvp-intro-back")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-page-measurement").assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-pushoff").assertTextContains(localeDecimal("42"))
        assertTrue("la introduccion ya estaba vista y sigue vista", store.seen)
    }

    @Test
    fun savedStateRestoresPageAndTypedTextAndDiagramStaysOnDemand() {
        val store = FakeIntroductionStore(seen = true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ThemedSetup(store) }

        compose.onNodeWithTag("fvp-page-data").assertIsDisplayed()
        goToMeasurement()
        compose.onNodeWithTag("fvp-setup-pushoff").performScrollTo().performTextInput(localeDecimal("41"))
        compose.waitForIdle()

        // El aviso de aproximacion de campo NO vive dentro del plegable: siempre visible.
        compose.onNodeWithTag("fvp-setup-push-off-approximation").assertExists()
        // El esquema y el detalle estan BAJO DEMANDA: no se componen plegados.
        compose.onNodeWithTag("fvp-setup-diagram").assertDoesNotExist()
        compose.onNodeWithTag("fvp-setup-guidance-toggle").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-setup-diagram").assertExists()

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.onNodeWithTag("fvp-page-measurement").assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-pushoff").assertTextContains(localeDecimal("41"))
        assertNull("restaurar no confirma nada", FvpDraftSession.draft.value)
    }

    @Test
    fun guidedPagesKeepMeasurementAndAdvancedHelpOutOfTheDataPage() {
        setSetupContent(FakeIntroductionStore(seen = false))
        compose.onNodeWithTag("fvp-intro").assertIsDisplayed()
        captureGuidedScreen(compose, "fvp_guided_entry", "intro")
        compose.onNodeWithTag("fvp-intro-skip").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-page-data").assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-pushoff").assertDoesNotExist()
        compose.onNodeWithTag("fvp-acquisition-context").assertDoesNotExist()
        captureGuidedScreen(compose, "fvp_guided_entry", "data")
        goToMeasurement()
        compose.onNodeWithTag("fvp-setup-mass").assertDoesNotExist()
        compose.onNodeWithTag("fvp-capture-experimental").assertExists()
        compose.onNodeWithTag("fvp-setup-pushoff").assertExists()
        compose.onNodeWithTag("fvp-setup-diagram").assertDoesNotExist()
        compose.onNodeWithTag("fvp-setup-pushoff-mode-PER_CONDITION").assertDoesNotExist()
        captureGuidedScreen(compose, "fvp_guided_entry", "measurement")
        compose.onNodeWithTag("fvp-setup-guidance-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-setup-diagram").performScrollTo().assertIsDisplayed()
        captureGuidedScreen(compose, "fvp_guided_entry", "measurement-help")
        compose.onNodeWithTag("fvp-setup-advanced-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-setup-pushoff-mode-PER_CONDITION")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("fvp-setup-per-condition-hint").assertExists()
        compose.onNodeWithTag("fvp-setup-pushoff").assertDoesNotExist()
        compose.onNodeWithTag("fvp-setup-continue").assertIsNotEnabled()
        captureGuidedScreen(compose, "fvp_guided_entry", "measurement-advanced")
        assertNull(FvpDraftSession.draft.value)
    }

    @Test
    fun replacementSessionWithSameAthleteAndProtocolReseedsInsteadOfMixingOldInputs() {
        val original = FvpDraftSession.begin(athleteId, FvpProtocol.SJ).copy(
            bodyMassKg = 70.0,
            commonPushOffM = 0.40,
            pushOffLandmark = "OLD",
            pushOffProtocol = "OLD_METHOD",
        )
        FvpDraftSession.adopt(original)
        setSetupContent(FakeIntroductionStore(seen = true))
        compose.onNodeWithTag("fvp-setup-mass").performTextClearance()
        compose.onNodeWithTag("fvp-setup-mass").performTextInput(localeDecimal("82.5"))
        goToMeasurement()
        compose.onNodeWithTag("fvp-setup-pushoff").performTextClearance()
        compose.onNodeWithTag("fvp-setup-pushoff").performTextInput(localeDecimal("45"))
        val replacement = original.copy(
            sessionKey = java.util.UUID.randomUUID().toString(),
            bodyMassKg = 91.0,
            commonPushOffM = 0.55,
            pushOffLandmark = "NEW",
            pushOffProtocol = "NEW_METHOD",
            acquisitionContextKind = "SAME_ACQUISITION",
            acquisitionContextKey = "replacement-context",
        )
        compose.runOnIdle { FvpDraftSession.adopt(replacement) }
        compose.onNodeWithTag("fvp-page-data").assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-mass").assertTextContains(localeDecimal("91"))
        assertEquals(replacement, FvpDraftSession.draft.value)
        goToMeasurement()
        compose.onNodeWithTag("fvp-setup-pushoff").assertTextContains(localeDecimal("55"))
        compose.onNodeWithTag("fvp-setup-landmark").assertTextContains("NEW")
        compose.onNodeWithTag("fvp-setup-procedure").assertTextContains("NEW_METHOD")
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled().performClick()
        val committed = requireNotNull(FvpDraftSession.draft.value)
        assertEquals(replacement.sessionKey, committed.sessionKey)
        assertEquals(91.0, committed.bodyMassKg ?: Double.NaN, 0.0)
        assertEquals(0.55, committed.commonPushOffM ?: Double.NaN, 0.0)
        assertEquals("replacement-context", committed.acquisitionContextKey)
        assertEquals(replacement.conditions, committed.conditions)
    }

    // ------------------------------------------------------------------------------------------
    // Infraestructura
    // ------------------------------------------------------------------------------------------

    private fun setSetupContent(
        store: FvpIntroductionStore,
        onContinue: () -> Unit = {},
    ) {
        compose.setContent { ThemedSetup(store, onContinue) }
    }

    @Composable
    private fun ThemedSetup(store: FvpIntroductionStore, onContinue: () -> Unit = {}) {
        OpenJumpTheme {
            CompositionLocalProvider(LocalUnitSystem provides guidedEvidenceProfile()) {
                FvpSetupScreen(
                    activeAthletes = listOf(
                        AthleteEntity(
                            id = athleteId,
                            displayName = "FVP guided",
                            weightKg = 80.0,
                            createdAt = 1L,
                            updatedAt = 1L,
                        ),
                    ),
                    athletesLoading = false,
                    selectedAthleteId = athleteId,
                    onAthleteSelected = {},
                    onBack = {},
                    onContinue = onContinue,
                    onHistory = {},
                    introductionStore = store,
                )
            }
        }
    }

    private fun goToMeasurement() {
        compose.onNodeWithTag("fvp-setup-continue-data").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-page-measurement").assertIsDisplayed()
    }

    private fun pressSystemBack() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    private fun localeDecimal(value: String): String {
        val locale = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.locales[0]
        val separator = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return value.replace('.', separator)
    }
}
