package com.openjump.app.ui.fvp

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.MainActivity
import com.openjump.app.OpenJumpApp
import com.openjump.app.R
import com.openjump.app.settings.OnboardingCompletion
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REGRESION de NAVEGACION del flujo guiado sobre el `NavHost` REAL de produccion
 * (`OpenJumpNavHost`, AppNav): no un host de pega ni un montaje aislado de pantalla.
 *
 * Escenario vinculante del plan (slice de cierre):
 * 1. con la introduccion ya vista, DATA -> MEDICION -> CONTINUAR entra en Condiciones;
 * 2. en Condiciones se teclea una altura PARCIAL y se declara contexto `SAME_ACQUISITION` con clave;
 * 3. el gesto/tecla de SISTEMA vuelve a MEDICION sin perder el texto del formulario;
 * 4. CONTINUAR de nuevo tiene que RESTAURAR la misma Condiciones: el texto parcial, la carga activa
 *    y la declaracion de contexto siguen ahi (la entrada se guarda con `saveState` y se restaura
 *    solo para la MISMA sesion);
 * 5. una sesion NUEVA (otra identidad de atleta) NO hereda ni el texto parcial, ni la clave, ni la
 *    carga activa anterior.
 *
 * No se ejecuta en esta estacion (sin AVD); se COMPILA y describe el comportamiento real. Requiere
 * el mismo supuesto que `FvpRecoveryInstrumentedTest`: onboarding completado en el AVD.
 */
@RunWith(AndroidJUnit4::class)
class FvpGuidedNavigationInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var app: OpenJumpApp
    private var firstAthleteId = 0L
    private var secondAthleteId = 0L
    private var previousSelectedAthleteId: Long? = null
    private var previousSelectionCaptured = false

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        app = context.applicationContext as OpenJumpApp
        previousSelectedAthleteId = app.selectedAthleteStore.selectedAthleteId.value
        previousSelectionCaptured = true
        assertEquals(
            "Precondicion del AVD: onboarding completado; no se permite omitir esta suite.",
            OnboardingCompletion.COMPLETED,
            app.onboardingPreferencesStore.completion.value,
        )
        compose.runOnUiThread { FvpDraftSession.reset() }
        // El escenario prueba navegacion/restauracion, no la presentacion de primer uso: la
        // preferencia de UI (independiente del borrador y de Room) se marca como vista.
        markFvpIntroductionSeen()
        firstAthleteId = createAthlete("FVP NAV A")
        secondAthleteId = createAthlete("FVP NAV B")
        selectAthlete(firstAthleteId)
    }

    @After
    fun tearDown() {
        compose.runOnUiThread { FvpDraftSession.reset() }
        runBlocking {
            listOf(firstAthleteId, secondAthleteId)
                .filter { it > 0L }
                .forEach { id ->
                    app.database.fvpDao().deleteForAthlete(id)
                    app.database.athleteDao().deleteAthleteById(id)
                }
            if (previousSelectionCaptured) {
                val previous = previousSelectedAthleteId
                if (previous != null && app.athleteRepository.activeById(previous) != null) {
                    app.selectedAthleteStore.select(app.athleteRepository, previous)
                } else {
                    app.selectedAthleteStore.clear()
                }
            }
        }
    }

    @Test
    fun systemBackFromConditionsRestoresTypedStateOnlyForTheSameSession() {
        openFvpSetup()
        val firstSessionKey = completeSetupAndOpenConditions()

        // --- Condiciones: identidad de la sesion, una sola carga activa -------------------------
        compose.onNodeWithTag("fvp-load-row-0").assertIsSelected()
        compose.onNodeWithTag("fvp-condition-0").assertIsDisplayed()
        compose.onNodeWithTag("fvp-condition-1").assertDoesNotExist()

        // Altura PARCIAL (solo "3"), deliberadamente sin confirmar: es texto de UI, no un intento
        // valido. Debe sobrevivir a salir y volver por la MISMA sesion.
        typeInto("fvp-attempt-height-0", "-")
        assertEquals("-", editableText("fvp-attempt-height-0"))
        assertNull(FvpDraftSession.draft.value?.conditions?.get(0)?.attempts?.get(0)?.heightM)

        // Contexto declarado con clave, por el editor real de Condiciones (ya no vive en Setup).
        compose.onNodeWithTag("fvp-acquisition-context-SAME_ACQUISITION").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-acquisition-context-key").performScrollTo()
            .performTextInput("ctx-navigation")
        compose.waitForIdle()
        val declared = requireNotNull(FvpDraftSession.draft.value)
        assertEquals(firstSessionKey, declared.sessionKey)
        assertEquals("SAME_ACQUISITION", declared.acquisitionContextKind)
        assertEquals("ctx-navigation", declared.acquisitionContextKey)

        // --- Gesto/tecla de SISTEMA: vuelve a MEDICION, no a Home --------------------------------
        pressSystemBack()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-page-measurement").fetchSemanticsNodes().isNotEmpty()
        }
        // Los campos de la pagina de Medicion siguen siendo los tecleados (no se re-siembra).
        assertEquals("40", editableText("fvp-setup-pushoff"))
        assertEquals("TROCANTER", editableText("fvp-setup-landmark"))
        assertEquals("LANDMARK_EXTENDED_MINUS_CROUCH", editableText("fvp-setup-procedure"))

        // --- CONTINUAR de nuevo: RESTAURA la misma Condiciones -----------------------------------
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-load-row-0").fetchSemanticsNodes().isNotEmpty()
        }
        // Misma sesion: mismo borrador, mismo texto parcial, misma carga activa y misma clave.
        assertEquals(firstSessionKey, FvpDraftSession.draft.value?.sessionKey)
        assertEquals("-", editableText("fvp-attempt-height-0"))
        compose.onNodeWithTag("fvp-load-row-0").assertIsSelected()
        compose.onNodeWithTag("fvp-condition-1").assertDoesNotExist()
        compose.onNodeWithTag("fvp-acquisition-context-SAME_ACQUISITION").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("fvp-acquisition-context-key").performScrollTo()
        assertEquals("ctx-navigation", editableText("fvp-acquisition-context-key"))

        // --- SESSION NUEVA: otra identidad no hereda texto, clave ni seleccion -------------------
        repeat(3) { pressSystemBack() } // Condiciones -> Medicion -> DATA -> Home
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.fvp_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        selectAthlete(secondAthleteId)
        openFvpSetup()
        val secondSessionKey = completeSetupAndOpenConditions()
        assertNotEquals(
            "una identidad nueva abre una sesion NUEVA (no reutiliza la clave de la anterior)",
            firstSessionKey,
            secondSessionKey,
        )
        compose.onNodeWithTag("fvp-acquisition-context-UNKNOWN").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("fvp-acquisition-context-key").assertDoesNotExist()
        assertNull(FvpDraftSession.draft.value?.acquisitionContextKey)
        assertEquals("UNKNOWN", FvpDraftSession.draft.value?.acquisitionContextKind)
        assertTrue(
            "el texto parcial de la sesion anterior no puede reaparecer en la nueva",
            editableText("fvp-attempt-height-0").isEmpty(),
        )
        compose.onNodeWithTag("fvp-load-row-0").assertIsSelected()
    }

    /** Home -> flujo F-V-P, esperando la pagina DATA (introduccion ya vista). */
    private fun openFvpSetup() {
        compose.waitForIdle()
        val title = compose.activity.getString(R.string.fvp_title)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(title).performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-page-data").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Recorre el gate REAL de Setup (DATA -> MEDICION -> CONTINUAR) y devuelve la `sessionKey` con la
     * que Condiciones ha quedado abierta. La masa llega sugerida por el perfil del atleta.
     */
    private fun completeSetupAndOpenConditions(): String {
        assertTrue("la masa debe llegar sugerida por el perfil", editableText("fvp-setup-mass").isNotEmpty())
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-page-measurement").fetchSemanticsNodes().isNotEmpty()
        }
        typeInto("fvp-setup-pushoff", "40")
        typeInto("fvp-setup-landmark", "TROCANTER")
        typeInto("fvp-setup-procedure", "LANDMARK_EXTENDED_MINUS_CROUCH")
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-load-row-0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        return requireNotNull(FvpDraftSession.draft.value) { "CONTINUAR debe dejar un borrador vivo" }
            .sessionKey
    }

    private fun pressSystemBack() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    private fun typeInto(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextClearance()
        compose.onNodeWithTag(tag).performTextInput(value)
        compose.waitForIdle()
    }

    private fun editableText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun createAthlete(name: String): Long = runBlocking {
        app.athleteRepository.create(
            displayName = "$name ${UUID.randomUUID()}",
            weightKg = 80.0,
        )
    }

    private fun selectAthlete(id: Long) {
        assertTrue(runBlocking { app.selectedAthleteStore.select(app.athleteRepository, id) })
        compose.waitUntil(30_000) { app.selectedAthleteStore.selectedAthleteId.value == id }
        compose.waitForIdle()
    }
}
