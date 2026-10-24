package com.openjump.app.ui.fvp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.theme.OpenJumpTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprension y recuperacion del paso 1 sobre la PANTALLA REAL ([FvpSetupScreen]):
 * ayuda visual de h_po con dos posiciones apoyadas, ejemplos junto a su campo y pie ACCIONABLE.
 *
 * Lo que comprueba (plan `fvp-guidance-ux.md`, slice A):
 * 1. el esquema de h_po tiene equivalente textual accesible (no depende de ver ni de distinguir
 *    colores) y muestra dos posiciones con el mismo hito; el detalle largo arranca PLEGADO y el
 *    aviso de aproximacion de campo sigue FUERA del plegable;
 * 2. los ejemplos de hito/procedimiento y procedencia del peso estan junto a SU campo y dicen que
 *    «sin declarar» es una respuesta valida (UNKNOWN no se degrada); la guia del contexto de
 *    adquisicion, ya sin copia editable en Setup, vive en Condiciones;
 * 3. el pie muestra un motivo corto y exacto (los MISMOS booleanos que `canContinue`) y una accion
 *    secundaria que DESPLAZA al primer campo pendiente de estas paginas (h_po, hito/procedimiento,
 *    componentes opcionales), sin pedir foco, sin abrir el teclado, sin mutar el borrador y sin
 *    cambiar el gate;
 * 4. si el pendiente es un componente OPCIONAL (texto parcial tras cambiar de unidades) la accion
 *    despliega primero el plegable; el texto parcial se conserva y CONTINUAR sigue bloqueado.
 *
 * Las capturas son OPT-IN (`-e fvpGuidanceEvidence true`; tambien valen con el flag ya existente
 * `-e fvpCaptureEvidence true`) y se escriben en `cacheDir/fvp_guidance` con nombres estables. El
 * perfil de unidades de EE. UU. se fuerza en la prueba; el tema (claro/oscuro) lo fija el AVD, de
 * modo que el padre puede producir la matriz sin tocar esta prueba.
 *
 * Esta prueba NO demuestra nada sobre hardware fisico ni TalkBack hablado.
 */
@RunWith(AndroidJUnit4::class)
class FvpGuidanceInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val profile = mutableStateOf(UnitProfile.METRIC)
    private val athleteId = 7L
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        FvpDraftSession.reset()
        // La introduccion de primer uso ya esta vista: estos casos prueban la guia de los campos,
        // no la presentacion inicial del flujo (esa se prueba con un doble inyectado).
        markFvpIntroductionSeen()
        profile.value = when (InstrumentationRegistry.getArguments().getString("fvpUnitProfile")) {
            "us" -> UnitProfile.UNITED_STATES
            "custom" -> UnitProfile.METRIC.copy(shortLength = com.openjump.app.settings.ShortLengthUnit.INCH)
            else -> UnitProfile.METRIC
        }
    }

    @After
    fun tearDown() {
        compose.waitForIdle()
        FvpDraftSession.reset()
    }

    // ------------------------------------------------------------------------------------------
    // 1: esquema accesible + detalle opcional + caveat fuera del plegable
    // ------------------------------------------------------------------------------------------

    @Test
    fun pushOffDiagramExplainsTwoSupportedPositionsWithTextualEquivalent() {
        setSetupContent()
        // El esquema de h_po y su ayuda viven en MEDICION: se recorre el gate de DATA.
        goToMeasurement()

        // La ayuda "Metodo y limites" de MEDICION se consulta en la INTRO reabierta como ayuda: se
        // comprueba alli el objetivo tactil de 48 dp y se vuelve a la MISMA pagina antes de seguir.
        compose.onNodeWithTag("fvp-intro-action").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-intro").assertIsDisplayed()
        compose.onNodeWithTag("fvp-capture-method-toggle").assertHeightIsAtLeast(48.dp)
        // La INTRO se compone entera en su columna con scroll: abrir la ayuda y volver se invoca por
        // semantica para no depender de la geometria del viewport.
        compose.onNodeWithTag("fvp-capture-method-toggle")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("fvp-capture-method-body").assertExists()
        compose.onNodeWithTag("fvp-intro-back")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-page-measurement").assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-review-pending").assertHeightIsAtLeast(48.dp)
        // El esquema tambien es bajo demanda; el aviso permanece fuera del plegable.
        compose.onNodeWithTag("fvp-setup-diagram").assertDoesNotExist()
        compose.onNodeWithTag("fvp-setup-push-off-approximation").performScrollTo().assertIsDisplayed()
        // El detalle largo arranca plegado: no hay muro de texto inicial.
        assertTrue(
            "el detalle de la medicion debe arrancar plegado",
            compose.onAllNodesWithTag("fvp-setup-guidance-body").fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag("fvp-setup-guidance-toggle").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-guidance-toggle").assertHeightIsAtLeast(48.dp)
        capture("guidance-initial")

        compose.onNodeWithTag("fvp-setup-guidance-toggle").performClick()
        compose.onNodeWithTag("fvp-setup-guidance-body").performScrollTo().assertIsDisplayed()
        val description = context.getString(R.string.fvp_push_off_diagram_description)
        compose.onNodeWithTag("fvp-setup-diagram-canvas", useUnmergedTree = true)
            .assertContentDescriptionEquals(description)
        compose.onNodeWithTag("fvp-setup-diagram").performScrollTo().assertIsDisplayed()
        // Dos posiciones nombradas y resumen visible junto al esquema.
        compose.onNodeWithText(context.getString(R.string.fvp_push_off_diagram_crouch)).assertExists()
        compose.onNodeWithText(context.getString(R.string.fvp_push_off_diagram_extended)).assertExists()
        compose.onNodeWithTag("fvp-setup-diagram-caption").assertExists()
        compose.onNodeWithText(context.getString(R.string.fvp_push_off_guidance_body)).assertExists()
        // Abrir el detalle no oculta el aviso de aproximacion (fuera del plegable) ni cambia el gate.
        compose.onNodeWithTag("fvp-setup-push-off-approximation").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-continue").assertIsNotEnabled()
        capture("guidance-detail-open")
    }

    // ------------------------------------------------------------------------------------------
    // 2: ejemplos junto a su campo y UNKNOWN legitimo
    // ------------------------------------------------------------------------------------------

    @Test
    fun beginnerExamplesStayAdjacentAndUnknownRemainsLegitimate() {
        setSetupContent()

        // DATA: el ejemplo de procedencia se consulta BAJO DEMANDA (ya no es un muro permanente) y
        // `UNKNOWN` sigue siendo el default honesto (no un error a resolver).
        compose.onNodeWithTag("fvp-body-mass-measurement-help-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-body-mass-measurement-example").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.fvp_body_mass_measurement_example)).assertExists()
        compose.onNodeWithTag("fvp-body-mass-measurement-UNKNOWN").performScrollTo().assertIsSelected()

        // MEDICION: el pie ofrece revisar el primer pendiente real de ESTA pagina; revisar no
        // declara nada por el usuario (ni masa, ni procedencia, ni borrador).
        goToMeasurement()
        compose.onNodeWithTag("fvp-setup-review-pending").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-setup-pushoff").assertIsNotFocused()
        assertNull("revisar un pendiente no puede crear ni mutar el borrador", FvpDraftSession.draft.value)
        // Volver a DATA demuestra que revisar tampoco cambio la procedencia declarada de la masa.
        compose.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-body-mass-measurement-UNKNOWN").performScrollTo().assertIsSelected()
        goToMeasurement()

        // Los ejemplos de hito y procedimiento siguen junto a sus campos.
        for (tag in listOf(
            "fvp-setup-landmark-example",
            "fvp-setup-procedure-example",
        )) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(context.getString(R.string.fvp_push_off_landmark_example)).assertExists()
        compose.onNodeWithText(context.getString(R.string.fvp_push_off_procedure_example)).assertExists()
        // El contexto de adquisicion ya NO se edita en Setup: su guia vive en Condiciones
        // (`FvpGuidedLoadsInstrumentedTest`).
    }

    // ------------------------------------------------------------------------------------------
    // 3: motivo exacto + accion al campo correcto sin foco/teclado ni mutacion
    // ------------------------------------------------------------------------------------------

    @Test
    fun reviewPendingReachesMetadataThenContextKeyWithoutTouchingEdits() {
        setSetupContent()
        compose.onNodeWithTag("fvp-setup-mass").performScrollTo()
        typeInto("fvp-setup-mass", localeDecimal("82.5"))
        // La masa vive en DATA; h_po y sus metadatos, en MEDICION.
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-page-measurement").fetchSemanticsNodes().isNotEmpty()
        }
        typeInto("fvp-setup-pushoff", pushOffText())

        // Primer pendiente = metadatos de h_po (campo mas arriba de los que faltan).
        compose.onNodeWithTag("fvp-setup-incomplete-hint")
            .assertTextEquals(context.getString(R.string.fvp_setup_pending_metadata))
        compose.onNodeWithTag("fvp-setup-continue").assertIsNotEnabled()
        capture("guidance-review-metadata")
        compose.onNodeWithTag("fvp-setup-review-pending").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("fvp-setup-landmark").assertIsDisplayed()
        // Desplazar NO pide foco ni abre el teclado, y no toca lo ya tecleado.
        compose.onNodeWithTag("fvp-setup-landmark").assertIsNotFocused()
        assertEquals("el h_po tecleado no cambia al revisar el pendiente", pushOffText(), editableText("fvp-setup-pushoff"))
        assertNull("revisar el pendiente no escribe en el borrador", FvpDraftSession.draft.value)
        compose.onNodeWithTag("fvp-setup-continue").assertIsNotEnabled()

        typeInto("fvp-setup-landmark", "TROCANTER")
        // Con el punto ya declarado, la revision debe ir al PROCEDIMIENTO, no volver al punto.
        compose.onNodeWithTag("fvp-setup-incomplete-hint")
            .assertTextEquals(context.getString(R.string.fvp_setup_pending_procedure))
        compose.onNodeWithTag("fvp-setup-review-pending").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-setup-procedure").assertIsDisplayed().assertIsNotFocused()
        assertEquals("TROCANTER", editableText("fvp-setup-landmark"))
        typeInto("fvp-setup-procedure", "LANDMARK_EXTENDED_MINUS_CROUCH")

        // La clave de contexto ya no es un pendiente de ESTA pantalla: el gate de Setup termina en
        // los metadatos de h_po y el de la clave se evalua en Condiciones (dueno del contexto).
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled()
        assertTrue(
            "sin pendientes no hay accion de revision en el pie",
            compose.onAllNodesWithTag("fvp-setup-review-pending").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "sin pendientes no hay motivo de bloqueo en el pie",
            compose.onAllNodesWithTag("fvp-setup-incomplete-hint").fetchSemanticsNodes().isEmpty(),
        )
    }

    // ------------------------------------------------------------------------------------------
    // 4: pendiente en componente OPCIONAL plegado (texto parcial tras cambiar de unidades)
    // ------------------------------------------------------------------------------------------

    @Test
    fun reviewPendingExpandsCollapsedOptionalComponentsForPartialUnitEdit() {
        // Caso de transicion siempre metrico→US, incluso en la matriz de perfil inicial US/custom.
        profile.value = UnitProfile.METRIC
        setSetupContent()
        typeInto("fvp-setup-mass", localeDecimal("82.5"))
        // La masa vive en DATA; h_po, hito, procedimiento y componentes, en MEDICION.
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-page-measurement").fetchSemanticsNodes().isNotEmpty()
        }
        typeInto("fvp-setup-pushoff", pushOffText())
        typeInto("fvp-setup-landmark", "TROCANTER")
        typeInto("fvp-setup-procedure", "LANDMARK_EXTENDED_MINUS_CROUCH")
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled()

        compose.onNodeWithTag("fvp-setup-details-toggle").performScrollTo().performClick()
        val partialCrouch = localeDecimal("34.5").dropLast(1)
        typeInto("fvp-setup-crouch", partialCrouch)
        // Cambio de unidades: el texto parcial se conserva y enciende el latch de revision.
        switchProfile(UnitProfile.UNITED_STATES)
        assertEquals(partialCrouch, editableText("fvp-setup-crouch"))
        assertTrue(
            "el campo en revision debe conservar su aviso",
            compose.onAllNodesWithText(
                context.getString(R.string.unit_input_review_required),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithTag("fvp-setup-continue").assertIsNotEnabled()

        // El usuario pliega los componentes con el texto parcial todavia en revision.
        compose.onNodeWithTag("fvp-setup-details-toggle").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(
            "el componente plegado no esta compuesto",
            compose.onAllNodesWithTag("fvp-setup-crouch").fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag("fvp-setup-incomplete-hint")
            .assertTextEquals(context.getString(R.string.fvp_setup_pending_optional))

        // La accion DESPLIEGA el plegable y lleva al campo: el parcial sigue intacto.
        compose.onNodeWithTag("fvp-setup-review-pending").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-setup-crouch").assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-crouch").assertIsNotFocused()
        assertEquals(partialCrouch, editableText("fvp-setup-crouch"))
        compose.onNodeWithTag("fvp-setup-continue").assertIsNotEnabled()
        assertNull("revisar un componente no escribe en el borrador", FvpDraftSession.draft.value)
        capture("guidance-optional-review")

        // Edicion explicita bajo las unidades actuales: se apaga la revision y se puede continuar.
        typeInto("fvp-setup-crouch", localeDecimal("34.6"))
        assertTrue(
            "la revision desaparece tras la edicion explicita",
            compose.onAllNodesWithText(context.getString(R.string.unit_input_review_required), substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled()
    }

    // ------------------------------------------------------------------------------------------
    // 5: masa invalida: el pie apunta al campo de masa (no a un motivo generico)
    // ------------------------------------------------------------------------------------------

    @Test
    fun reviewPendingPointsToInvalidBodyMassFirst() {
        setSetupContent()
        typeInto("fvp-setup-mass", "0")
        compose.onNodeWithTag("fvp-setup-incomplete-hint")
            .assertTextEquals(context.getString(R.string.fvp_setup_pending_mass))
        compose.onNodeWithTag("fvp-setup-review-pending").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-setup-mass").assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-mass-error").assertExists()
        assertEquals("0", editableText("fvp-setup-mass"))
        // La masa se valida en DATA: el gate de ESA pagina (no la accion final de MEDICION) es el
        // que queda bloqueado sin inventar un commit parcial.
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsNotEnabled()
        // El valor 0 se conserva tal cual: la accion de revision no "arregla" ni sustituye nada.
        assertNull(FvpDraftSession.draft.value)
    }

    // ------------------------------------------------------------------------------------------
    // Infraestructura
    // ------------------------------------------------------------------------------------------

    private fun setSetupContent() {
        val athlete = AthleteEntity(
            id = athleteId,
            displayName = ATHLETE_NAME,
            weightKg = 80.0,
            createdAt = 1L,
            updatedAt = 1L,
        )
        setThemedContent {
            FvpSetupScreen(
                activeAthletes = listOf(athlete),
                athletesLoading = false,
                selectedAthleteId = athleteId,
                onAthleteSelected = {},
                onBack = {},
                onContinue = {},
                onHistory = {},
            )
        }
    }

    /** DATA -> MEDICION por la accion propia de la pagina (no confirma la serie). */
    private fun goToMeasurement() {
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-page-measurement").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Tema REAL del producto y perfil de unidades VIVO (se puede cambiar sin desmontar). */
    private fun setThemedContent(content: @Composable () -> Unit) {
        compose.setContent {
            OpenJumpTheme {
                CompositionLocalProvider(LocalUnitSystem provides profile.value, content = content)
            }
        }
    }

    /** Cambio de perfil de unidades SIN desmontar la pantalla (como al volver de Ajustes). */
    private fun switchProfile(next: UnitProfile) {
        compose.runOnUiThread { profile.value = next }
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

    private fun isSelected(tag: String): Boolean =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Selected) ?: false

    private fun pushOffText(): String = localeDecimal(
        when (profile.value.shortLength) {
            com.openjump.app.settings.ShortLengthUnit.INCH -> "15.74803149606299" // 0,40 m en pulgadas.
            else -> "40"
        },
    )

    /**
     * Texto decimal robusto al locale del AVD: se construye con el separador del locale (el mismo
     * que usa la UI) para no depender de que el aparato use punto o coma decimal.
     */
    private fun localeDecimal(plain: String): String {
        val locale = context.resources.configuration.locales[0]
        val separator = (java.text.NumberFormat.getNumberInstance(locale) as? java.text.DecimalFormat)
            ?.decimalFormatSymbols?.decimalSeparator ?: '.'
        val candidate = plain.replace('.', separator)
        return if (com.openjump.app.ui.MeasurementFormatting.parseNumber(candidate, locale) != null) {
            candidate
        } else {
            plain
        }
    }

    // Capturas opt-in: evidencia visual, NUNCA un requisito de la pasada de correccion por defecto.
    private val captureEvidence
        get() = InstrumentationRegistry.getArguments().getString("fvpGuidanceEvidence") == "true" ||
            InstrumentationRegistry.getArguments().getString("fvpCaptureEvidence") == "true"

    private fun capture(name: String) {
        if (!captureEvidence) return
        compose.waitForIdle()
        val directory = File(context.cacheDir, "fvp_guidance").apply { mkdirs() }
        val file = File(directory, "$name.png")
        FileOutputStream(file).use { out ->
            val bitmap: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        assertTrue("la captura opt-in debe existir: ${file.name}", file.length() > 0)
    }

    private companion object {
        const val ATHLETE_NAME = "Guidance QA"
    }
}
