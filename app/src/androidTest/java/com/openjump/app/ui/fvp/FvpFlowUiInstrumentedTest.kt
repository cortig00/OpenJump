package com.openjump.app.ui.fvp

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.AssessmentEntity
import com.openjump.app.data.AttemptEntity
import com.openjump.app.data.AttemptMetricEntity
import com.openjump.app.data.FVP_METHOD_VERSION
import com.openjump.app.data.FVP_REUSE_CONFIRMATION_VERSION
import com.openjump.app.data.FvpDao
import com.openjump.app.data.FvpRepository
import com.openjump.app.data.FvpSnapshotStatus
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.JumpRepository
import com.openjump.app.ui.FVP_CONDITIONS_ROUTE
import com.openjump.app.ui.FVP_LIVE_RESULT_ROUTE
import com.openjump.app.ui.FVP_SAVED_RESULT_ROUTE
import com.openjump.app.ui.FVP_SETUP_ROUTE
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.HOME_ROUTE
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.fvpSavedResultRoute
import com.openjump.app.ui.fvpSavedResultNavTarget
import com.openjump.app.ui.fvpSetupNavTarget
import com.openjump.app.ui.navigateFvp
import com.openjump.app.fvp.FvpTimingQuality
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Flujo F-V-P focal sobre Room EN MEMORIA (sin preferencias ni base en disco). Compila y se
 * ejecuta en el AVD del padre; en esta estacion solo se verifica la compilacion del source set de
 * tests instrumentados.
 *
 * Cubre: gate B1 en setup (h_po + hito + procedimiento + fecha por defecto), aviso de tercer
 * intento (>5 %), estado NON_PHYSICAL sin 0.0, [Vmin,Vmax] junto a F0/V0, R^2 indefinido, fps
 * desconocidos no elegibles, etiqueta experimental al reabrir, borrado con el boton CONFIRMAR del
 * dialogo —incluido el borrado que NO ocurre (fila ya eliminada), que debe quedarse en la pantalla
 * con un aviso localizado (y anunciado por live region) en vez de navegar—, rechazo de comparacion
 * de series incompatibles en el historial, cabecera de identidad de la serie (atleta + protocolo +
 * fecha, con marcador neutro si el nombre no se puede resolver), glosas en lenguaje llano sin el
 * aviso del encoder, fila del historial que ABRE el guardado y estado vacio de las graficas sin puntos.
 *
 * Los textos decimales se construyen con el separador del locale del AVD (`localeDecimal`), de modo
 * que el test no depende de que el aparato use punto o coma decimal.
 */
@RunWith(AndroidJUnit4::class)
class FvpFlowUiInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: JumpDatabase
    private lateinit var repository: FvpRepository
    private lateinit var viewModel: FvpViewModel
    private var athleteId: Long = 0L

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        FvpDraftSession.reset()
        // Las pantallas que no prueban la introduccion la montan con la preferencia de UI ya
        // marcada: no se depende del fichero privado del dispositivo ni del orden de los tests.
        markFvpIntroductionSeen()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JumpDatabase::class.java,
        ).build()
        repository = FvpRepository(database.fvpDao())
        viewModel = FvpViewModel(repository, JumpRepository(database.jumpDao()))
        athleteId = runBlocking {
            database.athleteDao().insert(
                AthleteEntity(displayName = "FVP UI", weightKg = 80.0, createdAt = 1L, updatedAt = 1L),
            )
        }
    }

    @After
    fun tearDown() {
        FvpDraftSession.reset()
        database.close()
    }

    @Test
    fun setupRequiresPushOffValueLandmarkAndProcedureBeforeContinuing() {
        val athlete = AthleteEntity(
            id = athleteId,
            displayName = "FVP UI",
            weightKg = 80.0,
            createdAt = 1L,
            updatedAt = 1L,
        )
        compose.setContent {
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
        compose.onNodeWithTag("fvp-page-data").assertIsDisplayed()
        // La introduccion de primer uso ya esta vista (preferencia marcada en `setUp`): este caso
        // prueba el gate del flujo, no la presentacion inicial. DATA solo pide atleta, protocolo y
        // masa; el resto de campos vive en MEDICION.
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.onNodeWithTag("fvp-page-measurement").assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-continue").assertIsNotEnabled()
        // El aviso LARGO del gate B1 vive ahora junto a los campos que lo resuelven (hito y
        // procedimiento): se navega explicitamente hasta el con scroll, no se relaja la asercion.
        compose.onNodeWithTag("fvp-setup-metadata-warning").performScrollTo().assertIsDisplayed()
        // El pie solo conserva un motivo BREVE de incompletitud (sin el muro de metadatos h_po).
        compose.onNodeWithTag("fvp-setup-incomplete-hint").assertIsDisplayed()
        // El campo métrico se teclea en cm, no en metros del motor.
        compose.onNodeWithTag("fvp-setup-pushoff").performTextInput(localeDecimal("40"))
        // Gate B1 (P1-1): con h_po pero sin hito ni procedimiento no se avanza.
        compose.onNodeWithTag("fvp-setup-continue").assertIsNotEnabled()
        compose.onNodeWithTag("fvp-setup-landmark").performTextInput("TROCANTER")
        compose.onNodeWithTag("fvp-setup-continue").assertIsNotEnabled()
        compose.onNodeWithTag("fvp-setup-procedure").performTextInput("LANDMARK_EXTENDED_MINUS_CROUCH")
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled()
        // La fecha de medicion sigue teniendo valor (hoy por defecto) y es editable, pero YA NO vive
        // dentro del desplegable opcional: es metadato de h_po y se ve siempre junto a hito y
        // procedimiento. El resto del gate B1 no cambia.
        compose.onNodeWithTag("fvp-setup-measured-at").performScrollTo().assertIsDisplayed()
        val dateNodes = compose
            .onAllNodesWithText(context.getString(R.string.fvp_push_off_measured_at) + ": ", substring = true)
            .fetchSemanticsNodes()
        assertTrue("la fecha de medicion debe mostrarse en el boton del DatePicker", dateNodes.isNotEmpty())
        // Q2b: la etiqueta experimental F-V-P es persistente tambien en el setup (no solo en el
        // resultado). En MEDICION la ayuda "Metodo y limites" vive en la INTRO reabierta como
        // ayuda (no se compone en esta pagina): se abre por su accion real, se comprueba alli y se
        // vuelve a la MISMA pagina. La ayuda de h_po lleva el caveat de aproximacion de campo fuera
        // del plegable. NO ejecutado en esta estacion: aqui solo se compila el source set
        // instrumentado.
        compose.onNodeWithTag("fvp-intro-action").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-intro").assertIsDisplayed()
        // La INTRO se compone entera en su columna con scroll; la accion de click se invoca por
        // semantica para no depender de si el contenido cabe o no en el viewport.
        compose.onNodeWithTag("fvp-capture-method-toggle")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText(context.getString(R.string.fvp_experimental_label)).assertExists()
        compose.onNodeWithTag("fvp-intro-back").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-page-measurement").assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-push-off-approximation").assertExists()
    }

    /**
     * Solo la masa/h_po INVALIDOS bloquean el avance y lo dicen EN EL CAMPO. Antes el dominio de
     * h_po (0,05, 0,80] m se validaba solo en el paso 2: teclear 0,90 m dejaba "Condiciones"
     * habilitado y el bloqueo aparecia en la pantalla siguiente.
     */
    @Test
    fun setupFieldErrorsBlockContinueForInvalidMassAndPushOff() {
        compose.setContent {
            FvpSetupScreen(
                activeAthletes = listOf(sampleAthlete()),
                athletesLoading = false,
                selectedAthleteId = athleteId,
                onAthleteSelected = {},
                onBack = {},
                onContinue = {},
                onHistory = {},
            )
        }
        compose.onNodeWithTag("fvp-page-data").assertIsDisplayed()
        // DATA: masa invalida -> el boton de ESTA pagina (paso a Medicion) se bloquea sin inventar
        // un commit parcial.
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled()
        // La masa se sugiere desde el perfil (80 kg): se sustituye por 0 para forzar el error.
        compose.onNodeWithTag("fvp-setup-mass").performScrollTo().performTextClearance()
        compose.onNodeWithTag("fvp-setup-mass").performTextInput(localeDecimal("0"))
        compose.onNodeWithTag("fvp-setup-mass-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsNotEnabled()
        // Masa finita > 0: se puede pasar a MEDICION (sin confirmar todavia la serie).
        compose.onNodeWithTag("fvp-setup-mass").performTextClearance()
        compose.onNodeWithTag("fvp-setup-mass").performTextInput(localeDecimal("82.5"))
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.onNodeWithTag("fvp-page-measurement").assertIsDisplayed()
        // 90 cm fuera del dominio (5, 80] cm: error aunque hito y procedimiento estén.
        compose.onNodeWithTag("fvp-setup-pushoff").performScrollTo().performTextInput(localeDecimal("90"))
        compose.onNodeWithTag("fvp-setup-pushoff-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-landmark").performTextInput("TROCANTER")
        compose.onNodeWithTag("fvp-setup-procedure").performTextInput("LANDMARK_EXTENDED_MINUS_CROUCH")
        compose.onNodeWithTag("fvp-setup-continue").assertIsNotEnabled()
        // h_po en dominio: el avance se habilita (la validacion no se relaja).
        compose.onNodeWithTag("fvp-setup-pushoff").performTextClearance()
        compose.onNodeWithTag("fvp-setup-pushoff").performTextInput(localeDecimal("40"))
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled()
    }

    /**
     * Los COMPONENTES opcionales (crouch/extended) siguen plegados por defecto, pero la fecha NO
     * desaparece: es metadato de h_po y se pinta siempre, se puede editar (abre el `DatePicker`).
     */
    @Test
    fun setupOptionalComponentsCollapseKeepsTheDateVisibleAndEditable() {
        compose.setContent {
            FvpSetupScreen(
                activeAthletes = listOf(sampleAthlete()),
                athletesLoading = false,
                selectedAthleteId = athleteId,
                onAthleteSelected = {},
                onBack = {},
                onContinue = {},
                onHistory = {},
            )
        }
        // La masa sugerida por el perfil ya hace valido DATA: se pasa a MEDICION, que es donde
        // viven h_po, sus componentes opcionales y la fecha (metadato de h_po).
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.onNodeWithTag("fvp-page-measurement").assertIsDisplayed()
        assertTrue(
            "los componentes opcionales deben arrancar contraidos",
            compose.onAllNodesWithTag("fvp-setup-crouch").fetchSemanticsNodes().isEmpty(),
        )
        // La fecha se ve SIN desplegar nada.
        compose.onNodeWithTag("fvp-setup-measured-at").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-measured-at").performClick()
        compose.onNode(hasText(context.getString(R.string.common_apply)) and hasAnyAncestor(isDialog()))
            .assertExists()
        compose.onNode(
            hasText(context.getString(R.string.common_cancel)) and hasClickAction() and hasAnyAncestor(isDialog()),
        ).performClick()
        compose.onNodeWithTag("fvp-setup-measured-at").assertIsDisplayed()
        // Al desplegar los componentes la fecha sigue exactamente donde estaba (no se mueve al
        // plegable ni cambia de valor).
        compose.onNodeWithTag("fvp-setup-details-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-setup-crouch").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-measured-at").performScrollTo().assertIsDisplayed()
    }

    /**
     * Coste de anadir una condicion (UX A2): la condicion nace con UN intento vacio (un toque menos
     * por carga, sin callejon vacio) y el control que borra el INTENTO tiene su propia etiqueta
     * destructiva (Muse P1-2), distinta de la de la condicion.
     */
    @Test
    fun newConditionStartsWithOneAttemptAndAttemptRemovalHasItsOwnLabel() {
        val base = physicalDraft()
        FvpDraftSession.adopt(
            base.copy(
                conditions = listOf(
                    FvpConditionDraft(conditionKey = "0kg", addedLoadKg = 0.0, attempts = emptyList()),
                ),
            ),
        )
        compose.setContent {
            FvpConditionsScreen(onBack = {}, onCalculate = {}, viewModel = viewModel)
        }
        compose.onNodeWithText(context.getString(R.string.fvp_add_condition)).performClick()
        compose.waitForIdle()
        // Un UNICO editor activo (la condicion nueva): su control de borrado y el de su intento se
        // componen una sola vez, no uno por cada fila del resumen.
        compose.onNodeWithTag("fvp-load-active-1").assertExists()
        compose.onNodeWithTag("fvp-condition-remove-1").assertExists()
        compose.onNodeWithTag("fvp-condition-remove-0").assertDoesNotExist()
        val attemptRemovals = compose
            .onAllNodesWithText(context.getString(R.string.fvp_remove_attempt))
            .fetchSemanticsNodes()
        assertEquals(
            "la condicion nueva debe traer exactamente un intento y su propio control de borrado",
            1,
            attemptRemovals.size,
        )
        assertEquals(
            "solo el editor ACTIVO pinta su control de borrado de condicion",
            1,
            compose.onAllNodesWithText(context.getString(R.string.fvp_remove_condition)).fetchSemanticsNodes().size,
        )
        assertTrue(
            "las dos etiquetas destructivas no pueden ser la misma",
            context.getString(R.string.fvp_remove_attempt) != context.getString(R.string.fvp_remove_condition),
        )
        // Cada condicion se abre y se borra POR SEPARADO: primero la original (indice 0)...
        compose.onNodeWithTag("fvp-load-row-0").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-load-active-0").assertExists()
        compose.onNodeWithTag("fvp-condition-remove-0").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(
            "borrar la primera condicion deja viva la otra",
            1,
            requireNotNull(FvpDraftSession.draft.value).conditions.size,
        )
        // ...y despues la nueva, que pasa a ser la unica y sigue siendo la activa.
        compose.onNodeWithTag("fvp-load-active-0").assertExists()
        compose.onNodeWithTag("fvp-condition-remove-0").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(
            "cada condicion se puede borrar individualmente",
            requireNotNull(FvpDraftSession.draft.value).conditions.isEmpty(),
        )
        assertTrue(
            "sin editor activo no queda ningun control de borrado",
            compose.onAllNodesWithText(context.getString(R.string.fvp_remove_condition)).fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * Un intento tecleado a mano (sin fps de origen) no puede quedar sin etiqueta de calidad
     * temporal: la linea se pinta igualmente con el estado NEUTRO "desconocido".
     */
    @Test
    fun attemptWithoutFpsShowsUnknownTimingQuality() {
        val base = physicalDraft()
        FvpDraftSession.adopt(
            base.copy(
                conditions = listOf(
                    FvpConditionDraft(
                        conditionKey = "0kg",
                        addedLoadKg = 0.0,
                        attempts = listOf(FvpAttemptDraft(heightM = 0.30, method = FvpMeasureMethod.MANUAL)),
                    ),
                ),
            ),
        )
        compose.setContent {
            FvpConditionsScreen(onBack = {}, onCalculate = {}, viewModel = viewModel)
        }
        assertTrue(
            "un intento sin fps debe mostrar el estado temporal desconocido",
            compose.onAllNodesWithText(context.getString(R.string.fvp_quality_timing_unknown))
                .fetchSemanticsNodes().isNotEmpty(),
        )
        // Q2b: la etiqueta experimental F-V-P es persistente tambien en condiciones. La cabecera
        // compacta deja el aviso breve siempre visible y la etiqueta completa tras "Metodo y
        // limites": se abre el plegable en vez de suprimir la comprobacion.
        compose.onNodeWithTag("fvp-capture-experimental").assertIsDisplayed()
        compose.onNodeWithTag("fvp-capture-method-toggle").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.fvp_experimental_label)).assertExists()
    }

    /**
     * Q2b: el selector de saltos guardados muestra el VEREDICTO de calidad temporal del intento
     * guardado con el mismo helper y las mismas etiquetas `fvp_quality_timing_*` que la linea del
     * intento, para que quien reutiliza sepa si esos fps son elegibles antes de elegirlo. El salto
     * sembrado tiene 60 fps: banda condicional (60–<120).
     *
     * Cobertura anadida:
     * - P2-3: la fila sembrada NO tiene metrica `FLIGHT_TIME_MS`, asi que la pantalla declara junto
     *   al veredicto temporal que no se llevara tiempo de vuelo en vez de callarlo;
     * - P1-1 (gate B5 ejecutable): el boton de aplicar de una fila NO esta habilitado antes de
     *   declararla, SI lo esta despues de marcarla, y declarar OTRA medicion vuelve a bloquear la
     *   primera (la confirmacion es por fila y no se hereda).
     *
     * NO ejecutado en esta estacion (solo se compila el source set instrumentado): la ejecucion
     * corresponde al AVD del padre.
     */
    @Test
    fun reuseSheetShowsTimingVerdictOfSavedMeasurement() {
        val assessmentId = seedReusableMeasurement(
            sessionKey = "fvp-reuse-timing-verdict",
            dateTime = 1L,
            heightCm = 32.0,
            detectedFps = 60,
        )
        // Segunda fila, mas reciente: comprueba que la declaracion de una medicion NO habilita la
        // otra (el gate es por fila, no por selector).
        val secondAssessmentId = seedReusableMeasurement(
            sessionKey = "fvp-reuse-timing-verdict-second",
            dateTime = 2L,
            heightCm = 34.0,
            detectedFps = 240,
        )
        FvpDraftSession.adopt(physicalDraft())
        compose.setContent {
            FvpConditionsScreen(onBack = {}, onCalculate = {}, viewModel = viewModel)
        }
        // La carga de mediciones reutilizables es asincrona: esperar el boton antes de pulsarlo y la
        // apertura de la hoja antes de esperar filas evita carreras bajo carga (el timeout de filas
        // se amplia: en el AVD lento la consulta puede tardar).
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(context.getString(R.string.fvp_reuse_measurement))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText(context.getString(R.string.fvp_reuse_measurement))
            .onFirst()
            .performScrollTo()
            .performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("fvp-reuse-confirmation-hint").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(60_000) {
            compose.onAllNodesWithTag("fvp-reuse-$assessmentId-0").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("fvp-reuse-$secondAssessmentId-0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fvp-reuse-timing-$assessmentId-0")
            .assertTextContains(context.getString(R.string.fvp_quality_timing_conditional), substring = true)

        // P2-3: sin tiempo de vuelo utilizable, la fila lo declara (la nota usa la MISMA decision
        // que la escritura del intento, de modo que no puede mentir sobre lo que se copia).
        compose.onNodeWithTag("fvp-reuse-no-flight-time-$assessmentId-0")
            .assertTextEquals(context.getString(R.string.fvp_reuse_no_flight_time))

        // P1-1: sin la declaracion DE ESA fila el boton queda deshabilitado (no hay confirmacion
        // por omision) y se habilita al marcar la casilla.
        compose.onNodeWithTag("fvp-reuse-apply-$assessmentId-0").assertIsNotEnabled()
        compose.onNodeWithTag("fvp-reuse-confirm-$assessmentId-0").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-reuse-apply-$assessmentId-0").assertIsEnabled()

        // P1-1: declara OTRA medicion y la primera vuelve a quedar bloqueada (sin herencia).
        compose.onNodeWithTag("fvp-reuse-confirm-$secondAssessmentId-0").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-reuse-apply-$secondAssessmentId-0").assertIsEnabled()
        compose.onNodeWithTag("fvp-reuse-apply-$assessmentId-0").assertIsNotEnabled()
    }

    /**
     * Gate B3: un intento marcado como NO valido sin motivo de exclusion ensena el error de campo y
     * no deja calcular. Al escribir el motivo, el bloqueo desaparece.
     */
    @Test
    fun invalidAttemptWithoutReasonShowsFieldErrorAndBlocksCalculation() {
        val base = physicalDraft()
        val draft = base.copy(
            conditions = base.conditions.mapIndexed { index, condition ->
                if (index != 0) {
                    condition
                } else {
                    condition.copy(
                        attempts = condition.attempts + FvpAttemptDraft(
                            heightM = 0.40,
                            method = FvpMeasureMethod.MANUAL,
                            valid = false,
                        ),
                    )
                }
            },
        )
        FvpDraftSession.adopt(draft)
        compose.setContent {
            FvpConditionsScreen(onBack = {}, onCalculate = {}, viewModel = viewModel)
        }
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        compose.onNodeWithTag("fvp-attempt-exclusion-reason").performScrollTo().assertIsDisplayed()
        assertTrue(
            "el motivo obligatorio se explica en el campo",
            compose.onAllNodesWithText(context.getString(R.string.fvp_exclusion_reason_required))
                .fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithTag("fvp-attempt-exclusion-reason").performTextInput("aterrizaje distinto")
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsEnabled()
    }

    /** Atleta de prueba con peso de perfil: el setup sugiere la masa desde el (80 kg). */
    private fun sampleAthlete(): AthleteEntity = AthleteEntity(
        id = athleteId,
        displayName = "FVP UI",
        weightKg = 80.0,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun thirdAttemptHintAppearsWhenFirstTwoDifferByMoreThanFivePercent() {
        FvpDraftSession.begin(athleteId, FvpProtocol.SJ)
        FvpDraftSession.update { draft ->
            draft.copy(
                bodyMassKg = 80.0,
                bodyMassFromProfile = true,
                pushOffMode = FvpPushOffUiMode.COMMON,
                commonPushOffM = 0.40,
                pushOffLandmark = "TROCANTER",
                pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
                conditions = listOf(
                    FvpConditionDraft(
                        conditionKey = "0kg",
                        addedLoadKg = 0.0,
                        attempts = listOf(
                            FvpAttemptDraft(heightM = 0.30, method = FvpMeasureMethod.MANUAL),
                            FvpAttemptDraft(heightM = 0.40, method = FvpMeasureMethod.MANUAL),
                        ),
                    ),
                ),
            )
        }
        compose.setContent {
            FvpConditionsScreen(onBack = {}, onCalculate = {}, viewModel = viewModel)
        }
        compose.onNodeWithText(context.getString(R.string.fvp_third_attempt_hint))
            .performScrollTo().assertIsDisplayed()
        // Con una sola condicion la serie no es calculable: el aviso de bloqueo es visible.
        compose.onNodeWithText(context.getString(R.string.fvp_cannot_compute))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun nonPhysicalResultShowsUndefinedInterceptsAndExperimentalLabel() {
        val draft = nonPhysicalDraft()
        FvpDraftSession.adopt(draft)
        compose.setContent {
            FvpResultScreen(
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = {},
                onDeleted = {},
                viewModel = viewModel,
            )
        }
        // Experimental context and the NON_PHYSICAL interpretation are always explicit.
        compose.onNodeWithTag("fvp-context-experimental")
            .assertTextEquals(context.getString(R.string.fvp_context_experimental))
        compose.onAllNodesWithText(context.getString(R.string.fvp_warning_non_physical))
            .onFirst()
            .assertIsDisplayed()
        // Sin fecha de medicion (metadata B1 incompleta) el h_po queda como MANUAL: aviso dedicado.
        compose.showResultNoticeForTest(FvpUiWarning.PUSH_OFF_MANUAL_SOURCE)
        compose.onNodeWithTag("fvp-notice-detail-PUSH_OFF_MANUAL_SOURCE")
            .assertTextEquals(context.getString(R.string.fvp_warning_push_off_manual_source))
        compose.returnToResultSummaryForTest()
        // V0/Pmax indefinidos, nunca 0.0 como sustituto.
        val undefined = compose.onAllNodesWithText(context.getString(R.string.fvp_undefined_value))
            .fetchSemanticsNodes()
        assertTrue("V0 y Pmax deben mostrarse como indefinidos", undefined.size >= 2)
    }

    @Test
    fun saveAndReopenShowsStoredSnapshotAndExperimentalLabel() {
        val draft = physicalDraft()
        FvpDraftSession.adopt(draft)

        var savedId: Long? = null
        compose.runOnUiThread {
            viewModel.save(draft) { id -> savedId = id }
        }
        compose.waitUntil(30_000) { viewModel.saveState.value == FvpSaveState.Saved }
        val id = requireNotNull(savedId) { "El guardado no devolvio id." }

        val stored = runBlocking { repository.byId(id) }
        assertNotNull(stored)
        assertEquals(FvpSnapshotStatus.MATCHES, stored!!.snapshotStatus)
        assertEquals(4, stored.assessment.conditionCount)
        assertEquals(FVP_METHOD_VERSION, stored.assessment.methodVersion)
        // Gate B1: con hito + procedimiento + fecha el h_po queda como MEDIDO y sin aviso de MANUAL.
        assertEquals(FVP_PUSH_OFF_SOURCE_MEASURED, stored.assessment.pushOffSource)
        assertTrue(stored.assessment.f0N.isFinite())
        assertTrue(stored.assessment.v0Mps != null && stored.assessment.v0Mps!!.isFinite())

        compose.setContent {
            FvpResultScreen(
                assessmentId = id,
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = {},
                onDeleted = {},
                viewModel = viewModel,
            )
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-context-experimental").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fvp-context-experimental")
            .assertTextEquals(context.getString(R.string.fvp_context_experimental))
        compose.onNodeWithText(context.getString(R.string.fvp_metric_f0), ignoreCase = true).assertExists()

        // Captura opt-in del RESULTADO REABIERTO (checklist 2e: guardado/reapertura): la etiqueta
        // experimental y, si la fila trae avisos de procedencia, el banner de avisos del encabezado.
        // Sin `-e fvpCaptureEvidence true` no se crea ningun archivo. Este test solo compone UN
        // estado de pantalla (la fila guardada reabierta); la fila guardada "inicial" no es una
        // superficie distinta, se comprueba en el repositorio antes de componer.
        captureFvpEvidence("save-reopen-experimental-label.png")
    }

    @Test
    fun deleteUsesConfirmDialogAndRemovesStoredRow() {
        val draft = physicalDraft()
        FvpDraftSession.adopt(draft)
        var savedId: Long? = null
        compose.runOnUiThread {
            viewModel.save(draft) { id -> savedId = id }
        }
        compose.waitUntil(30_000) { viewModel.saveState.value == FvpSaveState.Saved }
        val id = requireNotNull(savedId) { "El guardado no devolvio id." }

        val deleted = AtomicBoolean(false)
        compose.setContent {
            FvpResultScreen(
                assessmentId = id,
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = {},
                onDeleted = { deleted.set(true) },
                viewModel = viewModel,
            )
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("fvp-delete").fetchSemanticsNodes().isNotEmpty() }
        // Borrado por la via de la UI: el boton CONFIRMAR del dialogo (no una llamada directa al
        // repositorio) es el que elimina la fila.
        compose.onNodeWithTag("fvp-delete").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.fvp_delete_confirm)).assertIsDisplayed()
        // Captura opt-in del DIALOGO de confirmacion (checklist 2e: borrado): el dialogo vive en su
        // propia ventana, de modo que se captura su nodo (`isDialog()`), no la raiz de la pantalla.
        captureFvpEvidence("delete-confirm-dialog.png", compose.onNode(isDialog()))
        compose.onNode(
            hasText(context.getString(R.string.fvp_delete)) and hasClickAction() and hasAnyAncestor(isDialog()),
        ).performClick()
        compose.waitUntil(30_000) { deleted.get() }
        assertNull(runBlocking { repository.byId(id) })
        // Captura opt-in del estado POSTERIOR al borrado: la fila ya no existe en el DAO y la
        // pantalla sigue siendo la del resultado (la navegacion la decide el llamante).
        captureFvpEvidence("delete-stored-row-removed.png")
    }

    /**
     * Checklist 2e (guardado/reapertura/duplicacion/borrado) + contrato 18 §2 (D2): "Duplicar y
     * editar" sobre el APK final. La serie se guarda por el CAMINO DE PRODUCCION con las 20
     * columnas de procedencia CONOCIDAS (B1b-2b: 5 de la raiz + 2 por condicion + 13 por intento,
     * con valores explicitos y distintos por fila); el boton REAL de la pantalla guardada
     * (`fvp_duplicate_and_edit`, con el aviso `fvp-duplicate-notice` visible ANTES del click)
     * delega en la MISMA mutacion que usa el dueno del flujo (`storedFvpToDraft(saved)`) y la fila
     * ORIGINAL queda intacta en el DAO.
     *
     * La prueba JVM (`FvpDraftMappingTest`) ya fija el mapeo pieza a pieza; aqui se ejecuta pulsando
     * el control sobre una fila REAL de Room y se re-lee la fila despues del click.
     */
    @Test
    fun duplicateAndEditKeepsProvenanceAndLeavesStoredRowUnchanged() {
        val draft = provenanceKnownDraft()
        val persisted = requireNotNull(draft.toPersistDraftOrNull())
        val beforeSaveMillis = System.currentTimeMillis()
        val id = runBlocking { repository.save(persisted) }
        val saved = requireNotNull(runBlocking { repository.byId(id) }) {
            "la serie guardada debe poder re-leerse"
        }
        // Guardia de VACUIDAD: el fixture declara CONOCIDA cada una de las 20 columnas (19 nombres
        // unicos: `acquiredAt` vive en la raiz y en el intento) y ninguna queda `null`/`UNKNOWN`,
        // de modo que comparar la copia contra el fixture no puede pasar por no haber nada que
        // copiar. Si manana se anade una columna de procedencia, esta guardia lo dice.
        val sourceProvenance = provenanceFields(draft)
        assertEquals(
            "el fixture debe cubrir las 20 columnas de procedencia",
            PROVENANCE_COLUMN_NAMES,
            sourceProvenance.keys.map { it.substringAfterLast('.') }.toSet(),
        )
        val unknown = sourceProvenance.filterValues { it == null || it == "UNKNOWN" }
        assertTrue("columnas de procedencia sin declarar en el fixture: ${unknown.keys}", unknown.isEmpty())

        var duplicated: FvpDraft? = null
        compose.setContent {
            FvpResultScreen(
                assessmentId = id,
                onBack = {},
                onExit = {},
                // La MISMA funcion de produccion que usa la mutacion del dueno del flujo: el test no
                // replica el mapeo, lo invoca sobre la fila guardada real.
                onEdit = { duplicated = storedFvpToDraft(saved) },
                onSaved = {},
                onDeleted = {},
                viewModel = viewModel,
            )
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-duplicate-notice").fetchSemanticsNodes().isNotEmpty()
        }
        // ANTES del click: el aviso de conservacion de la fila original y el boton rotulado
        // "Duplicar y editar" (el borrador VIVO no lleva ni el aviso ni esa etiqueta).
        compose.onNodeWithTag("fvp-duplicate-notice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.fvp_duplicate_and_edit))
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(30_000) { duplicated != null }
        val copy = requireNotNull(duplicated) { "el click del guardado debe duplicar la serie" }
        // Captura opt-in del resultado guardado con la accion "Duplicar y editar" ya ejercitada.
        captureFvpEvidence("result-duplicate-and-edit.png")

        // (1) La copia conserva EXACTAMENTE las 20 columnas de procedencia del fixture.
        assertEquals(
            "la copia debe conservar columna a columna la procedencia de la fila de origen",
            sourceProvenance,
            provenanceFields(copy),
        )
        // (2) La copia es una fila NUEVA: `sessionKey` distinto y fecha de creacion nueva (nunca la
        // de origen ni una fecha de PROCEDENCIA regenerada con "ahora").
        val copyPersisted = requireNotNull(copy.toPersistDraftOrNull()) {
            "la copia debe ser persistible sin valores inventados"
        }
        assertNotEquals(
            "la copia no puede reutilizar la identidad de la fila original",
            persisted.sessionKey,
            copyPersisted.sessionKey,
        )
        assertTrue(
            "la copia lleva fecha de creacion nueva (${copyPersisted.dateTime}) y no la de origen " +
                "(${persisted.dateTime})",
            copyPersisted.dateTime >= persisted.dateTime,
        )
        assertTrue(
            "la copia se crea durante el test: ${copyPersisted.dateTime} >= $beforeSaveMillis",
            copyPersisted.dateTime >= beforeSaveMillis,
        )
        // (3) La fila ORIGINAL sigue IDENTICA en el DAO (incluida su procedencia): duplicar no
        // actualiza la fila guardada (`FvpRepository.save` es idempotente por `sessionKey` y la
        // copia lleva otra clave; la mutacion del dueno solo adopta el borrador en memoria).
        val afterClick = requireNotNull(runBlocking { repository.byId(id) }) {
            "la fila original no puede desaparecer al duplicar"
        }
        assertEquals("la fila guardada no puede cambiar con el click", saved.assessment, afterClick.assessment)
        assertEquals(saved.conditions, afterClick.conditions)
        assertEquals(saved.attempts, afterClick.attempts)
        assertEquals(
            "la procedencia PERSISTIDA de la fila original sigue siendo la del fixture",
            sourceProvenance,
            provenanceFields(storedFvpToDraft(afterClick)),
        )
    }

    /**
     * W1.4: un borrado que NO ocurre no puede navegar como si hubiera ocurrido. La fila se elimina
     * por el DAO ANTES de confirmar; al pulsar CONFIRMAR el resultado debe seguir en pantalla, con
     * el aviso localizado `fvp_delete_error`, y `onDeleted` NO debe llamarse.
     *
     * Limite declarado: se verifica el resultado observable en la UI (navegacion no solicitada +
     * aviso visible + pantalla intacta), no el estado interno del ViewModel.
     */
    @Test
    fun deleteFailureKeepsTheScreenAndShowsLocalizedError() {
        val draft = physicalDraft()
        FvpDraftSession.adopt(draft)
        var savedId: Long? = null
        compose.runOnUiThread {
            viewModel.save(draft) { id -> savedId = id }
        }
        compose.waitUntil(30_000) { viewModel.saveState.value == FvpSaveState.Saved }
        val id = requireNotNull(savedId) { "El guardado no devolvio id." }

        val deleted = AtomicBoolean(false)
        compose.setContent {
            FvpResultScreen(
                assessmentId = id,
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = {},
                onDeleted = { deleted.set(true) },
                viewModel = viewModel,
            )
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("fvp-delete").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("fvp-delete").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.fvp_delete_confirm)).assertIsDisplayed()
        // La fila desaparece por otra via antes de que el usuario confirme en el dialogo.
        assertEquals(1, runBlocking { database.fvpDao().delete(id) })
        compose.onNode(
            hasText(context.getString(R.string.fvp_delete)) and hasClickAction() and hasAnyAncestor(isDialog()),
        ).performClick()

        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-delete-error").fetchSemanticsNodes().isNotEmpty()
        }
        // El estado de borrado vive ahora en el footer FIJO (global): no hace falta recorrer
        // ninguna pagina para verlo ni anunciarlo.
        compose.onNodeWithTag("fvp-delete-error").assertIsDisplayed()
            // Muse P1-6/P2-9: el aviso de error se anuncia (live region amable), no solo se pinta.
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText(context.getString(R.string.fvp_delete_error)).assertExists()
        assertFalse("un borrado que no ocurre no debe navegar", deleted.get())
        compose.onNodeWithTag("fvp-delete").assertExists()
        assertNull("la fila sigue sin existir: el aviso no puede ser un exito silencioso", runBlocking {
            repository.byId(id)
        })
    }

    /**
     * P2 (Wave 4a): el borrado NO se puede cancelar saliendo. El ViewModel es de DESTINO: si la
     * pantalla se abandona con la operacion en curso, el pop lo destruye, cancela la corrutina y ni
     * el exito (`onDeleted`) ni el fallo (`fvp_delete_error`) llegan a verse.
     *
     * Para observarlo de forma determinista el DAO del gate RETIENE el borrado (suspendiendo la
     * corrutina, sin bloquear el hilo de UI) hasta que el test lo libera. Con la operacion en curso
     * la flecha de la barra no abandona la pantalla, Editar queda deshabilitado y el banner
     * `fvp_deleting` es visible y anunciado; al liberar, el borrado termina y `onDeleted` se llama.
     */
    @Test
    fun deleteCannotBeCancelledByLeavingWhileItIsInProgress() {
        val draft = physicalDraft()
        FvpDraftSession.adopt(draft)
        var savedId: Long? = null
        compose.runOnUiThread {
            viewModel.save(draft) { id -> savedId = id }
        }
        compose.waitUntil(30_000) { viewModel.saveState.value == FvpSaveState.Saved }
        val id = requireNotNull(savedId) { "El guardado no devolvio id." }

        val gatedDao = GatedDeleteFvpDao(database.fvpDao())
        val gatedViewModel = FvpViewModel(FvpRepository(gatedDao), JumpRepository(database.jumpDao()))
        val backs = AtomicInteger(0)
        val edits = AtomicInteger(0)
        val deleted = AtomicBoolean(false)
        compose.setContent {
            FvpResultScreen(
                assessmentId = id,
                onBack = { backs.incrementAndGet() },
                onExit = {},
                onEdit = { edits.incrementAndGet() },
                onSaved = {},
                onDeleted = { deleted.set(true) },
                viewModel = gatedViewModel,
            )
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("fvp-delete").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("fvp-delete").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.fvp_delete_confirm)).assertIsDisplayed()
        compose.onNode(
            hasText(context.getString(R.string.fvp_delete)) and hasClickAction() and hasAnyAncestor(isDialog()),
        ).performClick()

        compose.waitUntil(30_000) { gatedDao.deleteStarted.isCompleted }
        // La operacion sigue viva y VISIBLE mientras el DAO la retiene.
        // El estado de borrado vive en el footer FIJO (global), visible desde cualquier seccion.
        compose.onNodeWithTag("fvp-deleting").assertIsDisplayed()
            // El estado en curso se anuncia (live region amable), no solo se pinta.
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        // Salir por la flecha de la barra no puede abandonar la pantalla ni navegar.
        compose.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        // Duplicar y editar tampoco: el boton queda deshabilitado durante el borrado (el guardado
        // se rotula como duplicado, D2 del plan 13).
        compose.onNodeWithText(context.getString(R.string.fvp_duplicate_and_edit))
            .performScrollTo().assertIsNotEnabled()
        // El aviso de conservacion acompania a la accion ANTES de que el usuario la pulse
        // (criterio de aceptacion de E1: el aviso se comprueba, no solo se pinta).
        compose.onNodeWithTag("fvp-duplicate-notice").performScrollTo().assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.fvp_duplicate_notice))
        compose.waitForIdle()
        assertEquals("la flecha no puede abandonar la pantalla durante el borrado", 0, backs.get())
        assertEquals("editar queda bloqueado durante el borrado", 0, edits.get())
        assertFalse("el borrado no puede darse por exitoso antes de tiempo", deleted.get())

        gatedDao.release.complete(Unit)
        compose.waitUntil(30_000) { deleted.get() }
        assertTrue("el borrado retenido termina y avisa", deleted.get())
        assertNull(runBlocking { repository.byId(id) })
    }

    /**
     * DAO F-V-P que RETIENE `delete` (suspendiendo la corrutina del ViewModel, nunca el hilo de UI)
     * hasta que el test libera [release]: permite observar de forma determinista el estado
     * `Deleting` y comprobar que la pantalla no se puede abandonar. El resto delega en Room.
     */
    private class GatedDeleteFvpDao(private val delegate: FvpDao) : FvpDao by delegate {
        val deleteStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun delete(id: Long): Int {
            deleteStarted.complete(Unit)
            release.await()
            return delegate.delete(id)
        }
    }

    @Test
    fun liveResultShowsMeasuredRangeAdjacentToF0AndV0() {
        FvpDraftSession.adopt(physicalDraft())
        compose.setContent {
            FvpResultScreen(
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = {},
                onDeleted = {},
                viewModel = viewModel,
            )
        }
        // La tarjeta de metricas (con F0/V0 y el rango medido adyacente) existe; en una pantalla
        // larga puede requerir scroll: se comprueba por el contenedor etiquetado.
        compose.onNodeWithTag("fvp-metrics").performScrollTo().assertIsDisplayed()
        // Q2a (plan 13): el estado de calidad (etiqueta + explicacion) se ve JUNTO a las metricas y
        // sin abrir los detalles. No se fija el estado concreto: depende de la elegibilidad del
        // fixture. NO EJECUTADO en esta estacion (sin emulador): assertion pendiente de correr.
        compose.onNodeWithTag("fvp-context-quality").assertIsDisplayed()
        // G-SCIENCE (plan 13, lineas 443-446): la disponibilidad de la sensibilidad temporal se ve
        // junto a la calidad y sin abrir los detalles. NO se fija el estado concreto (depende de si
        // el fixture declara vuelo y fps) y NUNCA se comprueba un rango ni un ancho: con el gate
        // abierto no hay ningun numero de sensibilidad en pantalla. NO EJECUTADO en esta estacion
        // (sin emulador): assertion pendiente de correr.
        compose.onNodeWithTag("fvp-sensitivity").assertIsDisplayed()
        // El borrador VIVO edita sus datos: no duplica ni anuncia conservacion (D2 del plan 13).
        compose.onNodeWithText(context.getString(R.string.fvp_edit)).performScrollTo().assertIsDisplayed()
            .assertIsEnabled()
        compose.onNodeWithTag("fvp-duplicate-notice").assertDoesNotExist()
        // Los rotulos pueden vivir en un nodo combinado: se espera su aparicion en el arbol sin
        // combinar (el AVD software tarda en estabilizar la composicion).
        // `MetricCard` renderiza `label.uppercase()` (design system): "V0 (m/s)" aparece como
        // "V0 (M/S)" y "F0 (N)" no cambia. Por eso se compara sin distinguir mayusculas.
        val f0Label = context.getString(R.string.fvp_metric_f0)
        val v0Label = context.getString(R.string.fvp_metric_v0)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(f0Label, substring = true, ignoreCase = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(v0Label, substring = true, ignoreCase = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(f0Label, substring = true, ignoreCase = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(v0Label, substring = true, ignoreCase = true, useUnmergedTree = true).assertExists()
        // El rango medido [Vmin,Vmax] se repite junto a F0 y junto a V0.
        val rangeNodes = compose
            .onAllNodesWithText(context.getString(R.string.fvp_measured_range), substring = true)
            .fetchSemanticsNodes()
        assertTrue(
            "[Vmin,Vmax] debe aparecer junto a F0 y a V0 (nodos: ${rangeNodes.size})",
            rangeNodes.size >= 2,
        )
    }

    @Test
    fun undefinedR2ShowsUndefinedWarningOnLiveResult() {
        val draft = undefinedR2Draft()
        FvpDraftSession.adopt(draft)
        val presentation = viewModel.compute(draft)
        assertNotNull(presentation.result)
        assertTrue("el motor debe publicar R^2 indefinido", presentation.result!!.profile.r2.isNaN())

        compose.setContent {
            FvpResultScreen(
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = {},
                onDeleted = {},
                viewModel = viewModel,
            )
        }
        compose.onNodeWithText(context.getString(R.string.fvp_warning_r2_undefined))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun fpsUnknownSeriesIsNotProfileEligibleAndWarnsTimingUnknown() {
        // Un salto reutilizado sin fps detectados (o cualquier intento sin fps) no es elegible.
        val base = physicalDraft()
        val draft = base.copy(
            conditions = base.conditions.map { condition ->
                condition.copy(attempts = condition.attempts.map { it.copy(sourceFps = null) })
            },
        )
        FvpDraftSession.adopt(draft)
        val presentation = viewModel.compute(draft)
        assertTrue("la serie sigue siendo calculable", presentation.canCompute)
        assertEquals(FvpTimingQuality.UNKNOWN, presentation.eligibility.timingQuality)
        assertFalse(
            "fps desconocidos no son elegibles automaticamente",
            presentation.eligibility.profileEligible,
        )

        compose.setContent {
            FvpResultScreen(
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = {},
                onDeleted = {},
                viewModel = viewModel,
            )
        }
        compose.showResultNoticeForTest(FvpUiWarning.TIMING_UNKNOWN)
        compose.onNodeWithTag("fvp-notice-detail-TIMING_UNKNOWN")
            .assertTextEquals(context.getString(R.string.fvp_warning_timing_unknown))
    }

    @Test
    fun historyRefusesComparisonOfSeriesWithDifferentPushOffMode() {
        val continuous = physicalDraft()
        val perCondition = perConditionDraft()
        val firstId = runBlocking { repository.save(requireNotNull(continuous.toPersistDraftOrNull())) }
        val secondId = runBlocking { repository.save(requireNotNull(perCondition.toPersistDraftOrNull())) }

        compose.setContent {
            FvpHistoryScreen(athleteId = athleteId, onBack = {}, onOpen = {}, viewModel = viewModel)
        }
        // Q2b: la etiqueta experimental F-V-P sigue disponible en el historial, ahora dentro de la
        // ayuda completa "Metodo y limites" (dialogo bajo demanda): se abre antes de comprobarla.
        compose.onNodeWithTag("fvp-history-method-help").performClick()
        compose.onNodeWithTag("fvp-history-method-help-label").assertExists()
        compose.onNodeWithText(context.getString(R.string.fvp_experimental_label)).assertExists()
        compose.onNodeWithTag("fvp-history-method-help-close").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-history-method-help-label").assertDoesNotExist()
        openComparison(firstId, secondId)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(context.getString(R.string.fvp_incompatible_series))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.fvp_incompatible_series)).assertIsDisplayed()
        // C2 (contrato, test 6): con bloqueo se sigue pintando el CONTEXTO (calidad, masa, h_po,
        // velocidad y versiones) y NO se pinta ningun delta ni la nota neutral. Compilado aqui; la
        // ejecucion real exige emulador/dispositivo.
        // P2-5 (revision): la tarjeta puede ser mas alta que la ventana en pantallas pequenas, asi
        // que la fila de contexto mas profunda se comprueba con `assertExists` (el item del
        // LazyColumn se compone entero) en vez de exigir que quede dentro de la ventana.
        compose.onNodeWithText(
            context.getString(R.string.fvp_compare_context_quality),
            substring = true,
        ).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.fvp_compare_delta_note)).assertCountEquals(0)
        // P2-4 (revision): la rama bloqueada TAMPOCO pinta la nota de version. La cadena lleva
        // cuatro marcadores de posicion: se busca el fragmento ANTERIOR al primero, que es el texto
        // que apareceria si la nota se renderizara (nunca el literal con %1$s).
        val versionNotePrefix = context.getString(R.string.fvp_compare_version_differs)
            .substringBefore("%1\$s")
        assertTrue("la nota de version no puede estar vacia (control de la asercion)", versionNotePrefix.isNotBlank())
        compose.onAllNodesWithText(versionNotePrefix, substring = true).assertCountEquals(0)
    }

    /**
     * P2-5 (revision): dos series COMPARABLES que solo difieren en la masa corporal usan la rama de
     * deltas de C2. La nota neutral de comparacion es obligatoria SIEMPRE que se pinte un delta, y
     * la nota de contexto de masa acompana a los relativos primarios (P2-2). El fragmento buscado
     * para la nota de version no se usa aqui: la ausencia de bloqueo se comprueba con el mensaje
     * general de series incompatibles en conteo 0.
     */
    @Test
    fun historyComparesSeriesWithDifferentBodyMassAndShowsNeutralDeltaNote() {
        // B4b: la comparacion numerica exige contexto de adquisicion declarado e identico y mapeo
        // temporal verificado. Este caso prueba los deltas con procedencia declarada; el bloqueo por
        // contexto/mapeo desconocido esta cubierto en FvpComparisonPolicyTest.
        fun withVerifiedProvenance(draft: FvpDraft) = draft.copy(
            acquisitionContextKind = "SAME_ACQUISITION",
            acquisitionContextKey = "ctx-history",
            conditions = draft.conditions.map { condition ->
                condition.copy(
                    attempts = condition.attempts.map { attempt ->
                        attempt.copy(timeMapping = "IDENTITY_VERIFIED", timeMappingEvidenceKey = "ev-history")
                    },
                )
            },
        )
        val first = withVerifiedProvenance(physicalDraft())
        val second = withVerifiedProvenance(physicalDraft().copy(bodyMassKg = 82.5))
        val firstId = runBlocking { repository.save(requireNotNull(first.toPersistDraftOrNull())) }
        val secondId = runBlocking { repository.save(requireNotNull(second.toPersistDraftOrNull())) }

        compose.setContent {
            FvpHistoryScreen(athleteId = athleteId, onBack = {}, onOpen = {}, viewModel = viewModel)
        }
        openComparison(firstId, secondId)
        // La comparacion se calcula en un efecto: se espera a la nota neutral de los deltas (que
        // solo se pinta en la rama comparable).
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(context.getString(R.string.fvp_compare_delta_note))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // En una tarjeta larga la nota puede quedar fuera de la ventana: existe en el arbol.
        compose.onNodeWithText(context.getString(R.string.fvp_compare_delta_note)).assertExists()
        // La nota de masa SI aparece: hay relativos primarios que ensenar (P2-2).
        compose.onAllNodesWithText(context.getString(R.string.fvp_compare_mass_differs_note)).assertCountEquals(1)
        // La rama de bloqueo no se activa con esta pareja: el mensaje general no se pinta.
        compose.onAllNodesWithText(context.getString(R.string.fvp_incompatible_series)).assertCountEquals(0)
    }

    /**
     * B4b: dos series SIN contexto de adquisicion declarado no pueden comparar deltas ni mostrar
     * ganador; el comparador explica el motivo localizado y conserva la parte descriptiva.
     */
    @Test
    fun historyBlocksDeltasWhenAcquisitionContextIsUnknownAndExplainsWhy() {
        val first = physicalDraft()
        val second = physicalDraft().copy(bodyMassKg = 82.5)
        val firstId = runBlocking { repository.save(requireNotNull(first.toPersistDraftOrNull())) }
        val secondId = runBlocking { repository.save(requireNotNull(second.toPersistDraftOrNull())) }

        compose.setContent {
            FvpHistoryScreen(athleteId = athleteId, onBack = {}, onOpen = {}, viewModel = viewModel)
        }
        openComparison(firstId, secondId)
        // El motivo localizado del contexto sin declarar aparece en la rama bloqueada.
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(
                context.getString(R.string.fvp_compare_block_different_acquisition_context),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.fvp_compare_block_different_acquisition_context))
            .assertExists()
        // Y no se pinta la nota de deltas de la rama comparable.
        assertEquals(
            0,
            compose.onAllNodesWithText(context.getString(R.string.fvp_compare_delta_note))
                .fetchSemanticsNodes().size,
        )
        // La rama bloqueada muestra el titular de incompatibilidad ademas de los motivos.
        compose.onAllNodesWithText(context.getString(R.string.fvp_incompatible_series)).assertCountEquals(1)

        // Captura opt-in de la superficie de comparacion bloqueada (misma evidencia que el resto de la
        // matriz visual de procedencia; sin el flag no se crea nada).
        if (InstrumentationRegistry.getArguments().getString("fvpCaptureEvidence") == "true") {
            compose.waitForIdle()
            val directory = java.io.File(context.cacheDir, "fvp_provenance_ui").apply { mkdirs() }
            java.io.File(directory, "comparison-blocked-context-unknown.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    // --- W2.3 (Muse P1-3/P1-5/P2-7/P2-10): identidad, glosas, accion del historial y estado vacio ---

    /**
     * Muse P1-3: el resultado de un guardado muestra de quien es la serie, con que protocolo y de
     * que fecha. El nombre se resuelve desde el atleta DE LA FILA (`assessment.athleteId`), no desde
     * el atleta seleccionado.
     */
    @Test
    fun savedResultHeaderShowsAthleteNameProtocolAndSeriesDate() {
        val draft = physicalDraft()
        val id = runBlocking { repository.save(requireNotNull(draft.toPersistDraftOrNull())) }
        val stored = requireNotNull(runBlocking { repository.byId(id) })

        compose.setContent {
            FvpResultScreen(
                assessmentId = id,
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = {},
                onDeleted = {},
                athleteNameById = mapOf(athleteId to "FVP UI"),
                viewModel = viewModel,
            )
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-series-header").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fvp-series-header").assertIsDisplayed()
        compose.onNodeWithTag("fvp-series-athlete").assertTextEquals("FVP UI")
        compose.onNodeWithTag("fvp-series-protocol-date").assertTextEquals(
            stored.assessment.protocolId + " · " +
                Formatting.dateTimeToText(stored.assessment.dateTime),
        )
    }

    /** Sin nombre resuelto se muestra el marcador neutro compartido: nunca un nombre inventado. */
    @Test
    fun resultHeaderFallsBackToNeutralLabelWhenAthleteNameIsUnknown() {
        FvpDraftSession.adopt(physicalDraft())
        compose.setContent {
            FvpResultScreen(
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = {},
                onDeleted = {},
                viewModel = viewModel,
            )
        }
        compose.onNodeWithTag("fvp-series-athlete")
            .assertTextEquals(context.getString(R.string.athlete_unassigned))
    }

    /**
     * Muse P1-5 / P2-8: cada tarjeta lleva su glosa en lenguaje llano y el aviso especifico del
     * encoder desaparece del resultado; la etiqueta experimental y el aviso de estimacion siguen.
     */
    @Test
    fun resultShowsPlainLanguageGlossesAndDropsTheEncoderNotice() {
        FvpDraftSession.adopt(physicalDraft())
        compose.setContent {
            FvpResultScreen(
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = {},
                onDeleted = {},
                athleteName = "FVP UI",
                viewModel = viewModel,
            )
        }
        // Las glosas CRITICAS BREVES siguen junto a la cifra (no son un muro de texto).
        listOf("fvp-gloss-f0", "fvp-gloss-v0", "fvp-gloss-pmax", "fvp-gloss-r2")
            .forEach { tag ->
                compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            }
        // Las definiciones LARGAS se consultan bajo demanda: se abre la accion explicita y se
        // comprueban dentro del modal (nada se elimina, solo deja de estar siempre impuesto).
        compose.onNodeWithTag("fvp-metrics-help").performScrollTo().performClick()
        listOf(
            "fvp-metric-help-f0", "fvp-metric-help-sfv", "fvp-metric-help-v0",
            "fvp-metric-help-pmax", "fvp-metric-help-r2", "fvp-metric-help-r2-notice",
            "fvp-metric-help-range", "fvp-metric-help-estimated", "fvp-metric-help-intro",
        ).forEach { tag -> compose.onNodeWithTag(tag).assertExists() }
        compose.onNodeWithText(context.getString(R.string.fvp_gloss_f0)).assertExists()
        compose.onNodeWithTag("fvp-metric-help-close").performClick()
        compose.onNodeWithTag("fvp-metric-help-f0").assertDoesNotExist()
        compose.onNodeWithTag("fvp-context-experimental").assertIsDisplayed()
        compose.showResultMethodForTest()
        compose.onNodeWithText(context.getString(R.string.fvp_experimental_label)).assertExists()
        compose.onNodeWithText(context.getString(R.string.fvp_estimate_notice_title)).assertExists()
        assertTrue(
            "el resultado no puede mostrar el aviso del encoder",
            compose.onAllNodesWithText(context.getString(R.string.labs_live_warning_title))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    /** Muse P2-7: la accion de la fila ABRE el guardado; no puede seguir llamandose "Editar". */
    @Test
    fun historyRowOpenActionIsViewNotEdit() {
        val draft = physicalDraft()
        val id = runBlocking { repository.save(requireNotNull(draft.toPersistDraftOrNull())) }
        var openedId: Long? = null
        compose.setContent {
            FvpHistoryScreen(
                athleteId = athleteId,
                onBack = {},
                onOpen = { openedId = it },
                viewModel = viewModel,
            )
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-history-open-$id").fetchSemanticsNodes().isNotEmpty()
        }
        // La accion de la fila es ABRIR el guardado: toda la fila es un boton con callback de
        // apertura (ya no hay literal "Ver" duplicado en cada fila). Se comprueba el rol y que el
        // toque entrega el id correcto, no que exista una etiqueta concreta.
        compose.onNodeWithTag("fvp-history-open-$id")
            .performScrollTo()
            .assert(hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        assertEquals("la fila debe ABRIR ese guardado", id, openedId)
        assertTrue(
            "la fila no puede ofrecer la accion 'Editar'",
            compose.onAllNodesWithText(context.getString(R.string.fvp_edit)).fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * Muse P2-10: sin puntos plottables la grafica no se queda en ejes desnudos: muestra el estado
     * vacio y no pinta lienzo ni leyenda.
     */
    @Test
    fun chartsWithoutPlottablePointsShowEmptyStateInsteadOfBareAxes() {
        compose.setContent {
            Column {
                FvpForceVelocityChart(
                    points = emptyList(),
                    f0N = 1500.0,
                    sfvNsPerM = 700.0,
                    vMinMps = 0.8,
                    vMaxMps = 1.4,
                    v0Mps = 2.14,
                    protocolLabel = FvpProtocol.SJ.name,
                )
                FvpPowerVelocityChart(
                    points = emptyList(),
                    f0N = 1500.0,
                    sfvNsPerM = 700.0,
                    pmaxW = 803.0,
                    v0Mps = 2.14,
                    vMinMps = 0.8,
                    vMaxMps = 1.4,
                    protocolLabel = FvpProtocol.SJ.name,
                )
            }
        }
        compose.onNodeWithTag("fvp-chart-empty-fv").assertIsDisplayed()
        compose.onNodeWithTag("fvp-chart-empty-pv").assertIsDisplayed()
        assertTrue(
            "sin puntos no se pinta el lienzo F-V",
            compose.onAllNodesWithTag("fvp-chart-canvas-fv").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "sin puntos no se pinta el lienzo P-V",
            compose.onAllNodesWithTag("fvp-chart-canvas-pv").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "sin puntos no hay leyenda que distinguir",
            compose.onAllNodesWithText(context.getString(R.string.fvp_chart_measured))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    // --- Navegacion tras guardar (invariante W1.1 / hallazgo A-P0-1) -------------------------------

    /**
     * Tras guardar desde el camino de "Editar", Back debe llegar a Home: ni un `fvp/conditions`
     * caducado (draft ya reseteado) ni la serie ANTIGUA. Antes, el `popUpTo(FVP_SETUP_ROUTE)` se
     * ignoraba al editar (esa entrada no esta en la pila) y la pila quedaba
     * `Home -> result/viejo -> conditions -> result/nuevo`.
     *
     * LIMITE DECLARADO: este harness compone pantallas sueltas con `createComposeRule`, no el
     * `NavHost` completo de `AppNav` (aquel grafo exige `OpenJumpApp`, onboarding y roster). Se
     * compone un `NavHost` MINIMO con las mismas rutas y la MISMA politica de produccion
     * ([fvpSavedResultNavTarget]/[navigateFvp]/[fvpSetupNavTarget]). Se verifica la pila REAL de un
     * `NavController` real; NO se verifica el grafo de `AppNav`, sus guards ni las pantallas.
     */
    @Test
    fun saveAfterEditingPopsToHomeSoBackLeavesTheExpiredDraftBehind() {
        var navController: NavHostController? = null
        compose.setContent {
            val controller = rememberNavController()
            navController = controller
            NavHost(navController = controller, startDestination = HOME_ROUTE) {
                composable(HOME_ROUTE) { Text("home") }
                composable(FVP_SETUP_ROUTE) { Text("setup") }
                composable(FVP_CONDITIONS_ROUTE) { Text("conditions") }
                composable(FVP_LIVE_RESULT_ROUTE) { Text("live") }
                composable(FVP_SAVED_RESULT_ROUTE) { Text("saved") }
            }
        }
        compose.waitForIdle()
        val nav = requireNotNull(navController) { "el NavHost de prueba no se compuso" }

        // Camino real de edicion: Home -> resultado guardado -> conditions -> resultado vivo.
        // `NavController` expone la RUTA PATRON (`fvp/result/{assessmentId}`), no la concreta.
        compose.runOnUiThread { nav.navigateFvp(fvpSavedResultNavTarget(1L)) }
        compose.waitForIdle()
        assertEquals(listOf(HOME_ROUTE, FVP_SAVED_RESULT_ROUTE), routeStack(nav))
        compose.runOnUiThread { nav.navigate(FVP_CONDITIONS_ROUTE) }
        compose.runOnUiThread { nav.navigate(FVP_LIVE_RESULT_ROUTE) }
        compose.waitForIdle()
        assertEquals(
            listOf(HOME_ROUTE, FVP_SAVED_RESULT_ROUTE, FVP_CONDITIONS_ROUTE, FVP_LIVE_RESULT_ROUTE),
            routeStack(nav),
        )

        // Guardar: pila determinista (Home + resultado nuevo), sin depender de `fvp/setup`.
        compose.runOnUiThread { nav.navigateFvp(fvpSavedResultNavTarget(2L)) }
        compose.waitForIdle()
        assertEquals(listOf(HOME_ROUTE, FVP_SAVED_RESULT_ROUTE), routeStack(nav))

        // Back desde el resultado guardado: Home, no el borrador caducado ni la serie antigua.
        compose.runOnUiThread { assertTrue("el pop debe ejecutarse", nav.popBackStack()) }
        compose.waitForIdle()
        assertEquals(listOf(HOME_ROUTE), routeStack(nav))
        // Home es la raiz de lo que queda: no hay serie antigua ni conditions por debajo. El
        // `popBackStack()` de la ultima entrada devuelve false y deja la pila vacia (comportamiento
        // de NavController), asi que se vuelve a navegar a Home antes de comprobar single-top.
        compose.runOnUiThread {
            val popped = nav.popBackStack()
            assertFalse("no debe quedar nada bajo Home", popped)
            if (routeStack(nav).isEmpty()) nav.navigate(HOME_ROUTE)
        }
        compose.waitForIdle()
        assertEquals(listOf(HOME_ROUTE), routeStack(nav))

        // Home -> fvp/setup con doble toque: `launchSingleTop` evita apilar dos setups.
        compose.runOnUiThread { nav.navigateFvp(fvpSetupNavTarget()) }
        compose.runOnUiThread { nav.navigateFvp(fvpSetupNavTarget()) }
        compose.waitForIdle()
        assertEquals(listOf(HOME_ROUTE, FVP_SETUP_ROUTE), routeStack(nav))
    }

    /**
     * Rutas de la pila actual (de abajo arriba). Se lee en el hilo de UI (dueno del nav) y se
     * descartan las entradas de grafo sin ruta; las rutas de destino son las mismas cadenas que usa
     * `AppNav`.
     */
    /**
     * Abre el INFORME de comparacion por el flujo real: entrar en modo comparar, marcar las DOS
     * series y pulsar la accion explicita que abre el modal (no se comparan filas al tocarlas).
     */
    private fun openComparison(firstId: Long, secondId: Long) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-history-open-$firstId").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("fvp-history-open-$secondId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fvp-history-compare").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-history-select-$firstId").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("fvp-history-select-$secondId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fvp-history-select-$firstId").performClick()
        compose.onNodeWithTag("fvp-history-select-$secondId").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-history-compare-open").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fvp-history-compare-open").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-compare-report").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun routeStack(nav: NavHostController): List<String> {
        var routes: List<String> = emptyList()
        compose.runOnUiThread { routes = nav.currentBackStack.value.mapNotNull { it.destination.route } }
        return routes
    }

    /** Serie no fisica: la fuerza CRECE con la velocidad (pendiente >= 0) -> Sfv <= 0. */
    private fun nonPhysicalDraft(): FvpDraft = FvpDraftSession.begin(athleteId, FvpProtocol.SJ).copy(
        bodyMassKg = 80.0,
        bodyMassFromProfile = true,
        pushOffMode = FvpPushOffUiMode.COMMON,
        commonPushOffM = 0.40,
        pushOffLandmark = "TROCANTER",
        pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
        conditions = listOf(
            FvpConditionDraft(
                conditionKey = "0kg",
                addedLoadKg = 0.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.20, method = FvpMeasureMethod.MANUAL)),
            ),
            FvpConditionDraft(
                conditionKey = "5kg",
                addedLoadKg = 5.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.35, method = FvpMeasureMethod.MANUAL)),
            ),
        ),
    )

    /** Serie fisica de 4 condiciones: la carga reduce la altura (Sfv > 0, V0 finito). */
    private fun physicalDraft(): FvpDraft = FvpDraftSession.begin(athleteId, FvpProtocol.SJ).copy(
        bodyMassKg = 80.0,
        bodyMassFromProfile = true,
        pushOffMode = FvpPushOffUiMode.COMMON,
        commonPushOffM = 0.40,
        pushOffLandmark = "TROCANTER",
        pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
        pushOffMeasuredAt = FVP_MEASURED_AT,
        conditions = listOf(
            FvpConditionDraft(
                conditionKey = "0kg",
                addedLoadKg = 0.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.40, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0)),
            ),
            FvpConditionDraft(
                conditionKey = "20kg",
                addedLoadKg = 20.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.32, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0)),
            ),
            FvpConditionDraft(
                conditionKey = "40kg",
                addedLoadKg = 40.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.25, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0)),
            ),
            FvpConditionDraft(
                conditionKey = "60kg",
                addedLoadKg = 60.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.18, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0)),
            ),
        ),
    )

    /**
     * Serie F-V-P (SJ, 2 condiciones: el minimo que el motor acepta) con las 20 columnas de
     * procedencia (B1b-2b: 5 de la raiz + 2 por condicion + 13 por intento) declaradas con valores
     * EXPLICITOS y DISTINTOS por fila: ninguna queda `null`/`UNKNOWN`, de modo que la comparacion de
     * la copia no puede pasar por vacia. La altura BAJA con la carga (Sfv > 0) y el h_po va medido,
     * asi que la fila guardada es recalculable hoy y la pantalla ofrece la accion de duplicar.
     *
     * Ningun valor de procedencia coincide con la fecha de la FILA ni con "ahora": una fecha
     * regenerada o cruzada entre filas se ve en la comparacion.
     */
    private fun provenanceKnownDraft(): FvpDraft = FvpDraftSession.begin(athleteId, FvpProtocol.SJ).copy(
        bodyMassKg = 80.0,
        bodyMassFromProfile = true,
        pushOffMode = FvpPushOffUiMode.COMMON,
        commonPushOffM = 0.40,
        pushOffLandmark = "TROCANTER",
        pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
        pushOffMeasuredAt = FVP_MEASURED_AT,
        // Procedencia de adquisicion de la RAIZ (5 columnas).
        acquiredAt = 1_699_500_000_000L,
        acquisitionContextKind = "HISTORICAL_COMPOSITE",
        acquisitionContextKey = "lote-importado-2026-10-01",
        bodyMassMeasurement = "WEIGHED",
        bodyMassMeasuredAt = 1_699_400_000_000L,
        conditions = listOf(
            FvpConditionDraft(
                conditionKey = "0kg",
                addedLoadKg = 0.0,
                // Procedencia de la carga anadida (2 columnas por condicion).
                addedLoadSource = "NOMINAL",
                addedLoadMeasuredAt = 1_699_410_000_000L,
                attempts = listOf(
                    FvpAttemptDraft(
                        heightM = 0.40,
                        method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                        sourceFps = 240.0,
                        // Procedencia de origen/reutilizacion del intento (13 columnas).
                        sourceRecordedAt = 1_699_300_000_000L,
                        sourceAthleteId = 777L,
                        acquiredAt = 1_699_520_000_000L,
                        reuseConfirmedAt = 1_699_600_000_000L,
                        reuseConfirmationVersion = FVP_REUSE_CONFIRMATION_VERSION,
                        takeoffPtsUs = 1_000_000L,
                        takeoffPreviousPtsUs = 966_667L,
                        takeoffNextPtsUs = 1_033_333L,
                        landingPtsUs = 1_500_000L,
                        landingPreviousPtsUs = 1_466_667L,
                        landingNextPtsUs = 1_533_333L,
                        timeMapping = "IDENTITY_VERIFIED",
                        timeMappingEvidenceKey = "evidencia-lote-1",
                    ),
                ),
            ),
            FvpConditionDraft(
                conditionKey = "20kg",
                addedLoadKg = 20.0,
                addedLoadSource = "WEIGHED",
                addedLoadMeasuredAt = 1_699_420_000_000L,
                attempts = listOf(
                    FvpAttemptDraft(
                        heightM = 0.32,
                        method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                        sourceFps = 240.0,
                        sourceRecordedAt = 1_699_310_000_000L,
                        sourceAthleteId = 888L,
                        acquiredAt = 1_699_530_000_000L,
                        reuseConfirmedAt = 1_699_610_000_000L,
                        reuseConfirmationVersion = FVP_REUSE_CONFIRMATION_VERSION,
                        takeoffPtsUs = 2_000_000L,
                        takeoffPreviousPtsUs = 1_950_000L,
                        takeoffNextPtsUs = 2_050_000L,
                        landingPtsUs = 2_500_000L,
                        landingPreviousPtsUs = 2_450_000L,
                        landingNextPtsUs = 2_550_000L,
                        timeMapping = "IDENTITY_VERIFIED",
                        timeMappingEvidenceKey = "evidencia-lote-2",
                    ),
                ),
            ),
        ),
    )

    /**
     * Siembra una medicion guardada reutilizable (assessment + intento + metrica `HEIGHT_CM`, SIN
     * `FLIGHT_TIME_MS`) y devuelve el id del assessment sembrado. `sessionKey` y `dateTime`
     * distinguen las mediciones cuando el test necesita mas de una fila en el selector.
     */
    private fun seedReusableMeasurement(
        sessionKey: String,
        dateTime: Long,
        heightCm: Double,
        detectedFps: Int,
    ): Long = runBlocking {
        val id = database.jumpDao().insertAssessment(
            AssessmentEntity(
                sessionKey = sessionKey,
                athleteId = athleteId,
                protocolId = "SJ",
                dateTime = dateTime,
                primaryMetricKey = "HEIGHT_CM",
                primaryMetricValue = heightCm,
                primaryMetricUnit = "CENTIMETER",
            ),
        )
        val attemptId = database.jumpDao().insertAttempt(
            AttemptEntity(
                assessmentId = id,
                side = null,
                source = "IMPORTED",
                videoUri = null,
                detectedFps = detectedFps,
                dropHeightCm = null,
                distanceCm = null,
            ),
        )
        database.jumpDao().insertMetrics(
            listOf(AttemptMetricEntity(attemptId, "HEIGHT_CM", 0, heightCm, "CENTIMETER")),
        )
        id
    }

    /**
     * Serie con FUERZA constante: la altura cae en la misma proporcion en que sube la masa, de modo
     * que la varianza de fuerza es 0 y el motor publica R^2 indefinido (NaN) con perfil
     * NON_PHYSICAL. Nunca se muestra 0.000 como sustituto de NaN.
     */
    private fun undefinedR2Draft(): FvpDraft = FvpDraftSession.begin(athleteId, FvpProtocol.SJ).copy(
        bodyMassKg = 60.0,
        bodyMassFromProfile = true,
        pushOffMode = FvpPushOffUiMode.COMMON,
        commonPushOffM = 0.40,
        pushOffLandmark = "TROCANTER",
        pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
        pushOffMeasuredAt = FVP_MEASURED_AT,
        conditions = listOf(
            // 60 kg * (0.30/0.40 + 1) = 105 kg equivalentes
            FvpConditionDraft(
                conditionKey = "0kg",
                addedLoadKg = 0.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.30, method = FvpMeasureMethod.MANUAL)),
            ),
            // 70 kg * (0.20/0.40 + 1) = 105 kg equivalentes: MISMA fuerza en doble exacto
            // (verificado), distinta velocidad => varianza de F = 0 => R^2 indefinido.
            FvpConditionDraft(
                conditionKey = "10kg",
                addedLoadKg = 10.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.20, method = FvpMeasureMethod.MANUAL)),
            ),
        ),
    )

    /**
     * Misma serie que [physicalDraft] pero con h_po declarado POR CONDICION (mismo protocolo,
     * mismos metodos): la unica diferencia es el modo de h_po, de modo que el historial debe
     * rechazar la comparacion por incompatibilidad (gate B7).
     */
    private fun perConditionDraft(): FvpDraft = FvpDraftSession.begin(athleteId, FvpProtocol.SJ).copy(
        bodyMassKg = 80.0,
        bodyMassFromProfile = true,
        pushOffMode = FvpPushOffUiMode.PER_CONDITION,
        pushOffLandmark = "TROCANTER",
        pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
        pushOffMeasuredAt = FVP_MEASURED_AT,
        conditions = listOf(0.40, 0.32, 0.25, 0.18).mapIndexed { index, heightM ->
            FvpConditionDraft(
                conditionKey = "${index * 20}kg",
                addedLoadKg = index * 20.0,
                pushOffM = 0.40,
                attempts = listOf(
                    FvpAttemptDraft(heightM = heightM, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0),
                ),
            )
        },
    )

    /**
     * Wave 5 (coherencia de unidades de h_po): con el perfil de EE. UU. el campo de h_po se teclea y
     * se muestra en PULGADAS (longitud corta), no en pies, y sus textos de limite citan esas mismas
     * pulgadas. Antes el perfil US pintaba h_po en pies (0,40 m -> 1,31 ft) mientras el limite seguia
     * escrito en metros: las cifras no correspondian a la unidad del campo.
     */
    @Test
    fun usProfileShowsPushOffInInchesAndItsLimitsInInches() {
        val athlete = AthleteEntity(
            id = athleteId,
            displayName = "FVP UI",
            weightKg = 80.0,
            createdAt = 1L,
            updatedAt = 1L,
        )
        // h_po ya declarado (0,40 m canonicos): el CAMPO debe mostrarlo en pulgadas, no en pies.
        FvpDraftSession.adopt(
            FvpDraftSession.begin(athleteId, FvpProtocol.SJ).copy(
                bodyMassKg = 80.0,
                bodyMassFromProfile = true,
                pushOffMode = FvpPushOffUiMode.COMMON,
                commonPushOffM = 0.40,
            ),
        )
        compose.setContent {
            CompositionLocalProvider(LocalUnitSystem provides UnitProfile.UNITED_STATES) {
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
        val locale: Locale = context.resources.configuration.locales[0]
        // La masa corporal SUGERIDA por el perfil vive en DATA: se comprueba ANTES de pasar a
        // MEDICION, donde solo se componen h_po y sus metadatos.
        compose.onNodeWithText(context.getString(R.string.fvp_body_mass) + " (lb)", useUnmergedTree = true)
            .assertExists()
        val suggestedPounds = MeasurementFormatting.parseNumber(editableText("fvp-setup-mass"), locale)
        assertNotNull("el campo de masa debe mostrar un numero tecleable", suggestedPounds)
        assertEquals("80 kg sugeridos deben mostrarse como 176,3698 lb", 176.37, suggestedPounds!!, 0.01)
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.onNodeWithTag("fvp-page-measurement").assertIsDisplayed()
        val pushOffLabel = context.getString(R.string.fvp_push_off)
        // Etiqueta del campo en pulgadas; jamas en pies.
        compose.onNodeWithText("$pushOffLabel (in)", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("$pushOffLabel (ft)", useUnmergedTree = true).assertDoesNotExist()
        // 0,40 m = 15,748 in (un render en pies daria 1,312).
        val shownInches = MeasurementFormatting.parseNumber(editableText("fvp-setup-pushoff"), locale)
        assertNotNull("el campo de h_po debe mostrar un numero tecleable", shownInches)
        assertEquals("0,40 m deben mostrarse como 15,748 in", 15.748, shownInches!!, 0.01)
        // Dominio `(0,05, 0,80] m` fuera de rango: 35,5 in = 0,902 m. Limite citado en pulgadas.
        compose.onNodeWithTag("fvp-setup-pushoff").performScrollTo().performTextClearance()
        compose.onNodeWithTag("fvp-setup-pushoff").performTextInput(localeDecimal("35.5"))
        val errorText = textOf("fvp-setup-pushoff-error")
        assertTrue(
            "el error de dominio debe citar el limite en pulgadas redondeado HACIA DENTRO " +
                "((2,0–31,4] in; 31,5 in = 0,8001 m lo rechazaria el dominio): $errorText",
            errorText.contains("(" + localeDecimal("2.0") + "–" + localeDecimal("31.4") + "] in"),
        )
        assertFalse("el error no puede hablar de pies: $errorText", errorText.contains("ft"))
        // Ventana recomendada (0,20-0,60 m = 7,9-23,6 in): 27,6 in = 0,701 m, dentro del dominio y
        // fuera de la ventana recomendada. El aviso no bloqueante tambien va en pulgadas.
        compose.onNodeWithTag("fvp-setup-pushoff").performTextClearance()
        compose.onNodeWithTag("fvp-setup-pushoff").performTextInput(localeDecimal("27.6"))
        val hintText = textOf("fvp-setup-pushoff-hint")
        assertTrue(
            "el aviso recomendado debe citar el rango en pulgadas (7,9-23,6 in): $hintText",
            hintText.contains(localeDecimal("7.9") + "–" + localeDecimal("23.6") + " in"),
        )
        assertFalse("el aviso no puede hablar de pies: $hintText", hintText.contains("ft"))
    }

    /**
     * Espejo metrico del caso anterior: h_po es longitud CORTA (cm), no distancia horizontal (m), de
     * modo que 0,40 m se teclea y se lee como "40" cm y el limite del dominio se cita en cm. Antes el
     * campo estaba en metros y el limite tambien, pero el aviso seguia escrito a mano en el recurso.
     */
    @Test
    fun metricProfileShowsPushOffInCentimetresAndItsDomainInCentimetres() {
        val athlete = AthleteEntity(
            id = athleteId,
            displayName = "FVP UI",
            weightKg = 80.0,
            createdAt = 1L,
            updatedAt = 1L,
        )
        FvpDraftSession.adopt(
            FvpDraftSession.begin(athleteId, FvpProtocol.SJ).copy(
                bodyMassKg = 80.0,
                bodyMassFromProfile = true,
                pushOffMode = FvpPushOffUiMode.COMMON,
                commonPushOffM = 0.40,
            ),
        )
        compose.setContent {
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
        val locale: Locale = context.resources.configuration.locales[0]
        // h_po y su dominio viven en MEDICION: se recorre el gate de DATA (masa sugerida valida).
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.onNodeWithTag("fvp-page-measurement").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.fvp_push_off) + " (cm)", useUnmergedTree = true)
            .assertExists()
        val shownCentimetres = MeasurementFormatting.parseNumber(editableText("fvp-setup-pushoff"), locale)
        assertNotNull(shownCentimetres)
        assertEquals("0,40 m deben mostrarse como 40 cm", 40.0, shownCentimetres!!, 0.001)
        // 90 cm = 0,90 m sigue fuera del dominio `(0,05, 0,80] m`.
        compose.onNodeWithTag("fvp-setup-pushoff").performScrollTo().performTextClearance()
        compose.onNodeWithTag("fvp-setup-pushoff").performTextInput(localeDecimal("90"))
        val errorText = textOf("fvp-setup-pushoff-error")
        assertTrue(
            "el error de dominio debe citar el limite en cm redondeado HACIA DENTRO " +
                "((5,1–80,0] cm; 5,0 cm es el extremo EXCLUIDO): $errorText",
            errorText.contains("(" + localeDecimal("5.1") + "–" + localeDecimal("80.0") + "] cm"),
        )
        assertFalse("el limite no puede quedar como un ancho suelto en metros: $errorText", errorText.contains(" m"))
    }

    /**
     * Wave 5: con el perfil de EE. UU. los RESULTADOS del modelo siguen en SI. La unidad de Sfv
     * (N.s/m) depende de la de V, y los valores persistidos/exportados son SI: convertir solo V a
     * ft/s dejaba la misma tarjeta con V0 en m/s y el rango medido en ft/s. La masa corporal de la
     * SERIE si se pinta en la unidad del perfil (lb) como referencia humana, mientras los relativos
     * siguen siendo N/kg y W/kg.
     */
    @Test
    fun usProfileKeepsModelResultsInSiAndShowsTheSeriesBodyMassInPounds() {
        FvpDraftSession.adopt(physicalDraft())
        compose.setContent {
            CompositionLocalProvider(LocalUnitSystem provides UnitProfile.UNITED_STATES) {
                FvpResultScreen(
                    onBack = {},
                    onExit = {},
                    onEdit = {},
                    onSaved = {},
                    onDeleted = {},
                    athleteName = "FVP UI",
                    viewModel = viewModel,
                )
            }
        }
        val locale: Locale = context.resources.configuration.locales[0]
        // Ni una sola velocidad del resultado en ft/s.
        assertTrue(
            "con perfil US el resultado no puede mostrar ft/s",
            compose.onAllNodesWithText("ft/s", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag("fvp-metrics").performScrollTo().assertIsDisplayed()
        // El rango medido [Vmin,Vmax] y la tarjeta V0 (m/s) comparten unidad.
        val rangeTexts = compose
            .onAllNodesWithText(context.getString(R.string.fvp_measured_range), substring = true)
            .fetchSemanticsNodes()
            .map { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
            }
        assertTrue("[Vmin,Vmax] debe seguir visible junto a F0 y a V0", rangeTexts.isNotEmpty())
        rangeTexts.forEach { text ->
            assertTrue("el rango medido debe ir en m/s como V0: $text", text.contains("m/s"))
            assertFalse("el rango medido no puede ir en ft/s: $text", text.contains("ft/s"))
        }
        // La celda V0 ya no usa el `MetricCard` compartido (que forzaba mayusculas): la etiqueta
        // conserva su forma natural "V0 (m/s)" y la unidad vive tambien en el nodo del valor.
        assertTrue(
            "V0 debe seguir rotulado en m/s",
            compose.onAllNodesWithText(context.getString(R.string.fvp_metric_v0), substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        val v0Value = textOf("fvp-metric-value-v0")
        assertTrue("el valor de V0 debe seguir en SI (m/s): $v0Value", v0Value.contains("m/s"))
        assertFalse("el valor de V0 no puede pasar a ft/s: $v0Value", v0Value.contains("ft/s"))
        // La eleccion SI es explicita y aparece UNA vez junto a las metricas.
        compose.onNodeWithTag("fvp-model-units-note").performScrollTo().assertIsDisplayed()
        assertEquals(
            "la nota de unidades SI se muestra una sola vez",
            1,
            compose.onAllNodesWithTag("fvp-model-units-note").fetchSemanticsNodes().size,
        )
        // Masa de la serie en libras (referencia humana); relativos SIN convertir.
        val massText = textOf("fvp-series-body-mass")
        assertTrue(
            "la cabecera debe mostrar la masa de la serie en lb: $massText",
            massText.contains(localeDecimal("176.4")) && massText.endsWith("lb"),
        )
        val relativeValues = textOf("fvp-relative-values")
        assertTrue(
            "los relativos siguen siendo SI (N/kg y W/kg): $relativeValues",
            relativeValues.contains("N/kg") && relativeValues.contains("W/kg"),
        )
        assertFalse(
            "los relativos no pueden pasar a libras: $relativeValues",
            relativeValues.contains("lb"),
        )
    }

    /**
     * Wave 5: `fvpWarningText` es el UNICO punto de formateo de los avisos con parametros
     * dependientes del perfil. El aviso del rango recomendado de h_po no puede llegar a pantalla con
     * el `%1$s` literal (es lo que veria el historial al pintar un aviso guardado: `stringResource`
     * de un solo argumento no formatea) y en el perfil US debe citar PULGADAS.
     */
    @Test
    fun warningTextHelperFormatsThePushOffRangeAndNeverLeaksThePlaceholder() {
        compose.setContent {
            CompositionLocalProvider(LocalUnitSystem provides UnitProfile.UNITED_STATES) {
                Text(
                    fvpWarningText(FvpUiWarning.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE),
                    modifier = Modifier.testTag("fvp-warning-probe"),
                )
            }
        }
        val text = textOf("fvp-warning-probe")
        assertTrue(
            "el aviso debe citar el rango en pulgadas (7,9-23,6 in): $text",
            text.contains(localeDecimal("7.9") + "–" + localeDecimal("23.6") + " in"),
        )
        assertFalse("el marcador de posicion no puede llegar a pantalla: $text", text.contains("%1\$s"))
        assertFalse("el aviso no puede hablar de metros: $text", text.contains(" m"))
    }

    /** Texto visible de un nodo (`Text` + `EditableText`), para aserciones sobre lo que se lee. */
    private fun textOf(tag: String): String {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        val editable = node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        return listOf(text, editable).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** Valor tecleado de un campo de texto etiquetado. */
    private fun editableText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /**
     * Las 20 columnas de procedencia (B1b-2b) de un borrador de UI, rotuladas por nivel
     * (`root.` / `conditions[i].` / `conditions[i].attempts[j].`): comparar dos mapas es comparar la
     * procedencia columna a columna y el fallo dice QUE columna se movio. Se recorren TODAS las
     * condiciones y TODOS los intentos (una copia cruzada entre filas se ve en la comparacion).
     */
    private fun provenanceFields(draft: FvpDraft): Map<String, Any?> = buildMap<String, Any?> {
        put("root.acquiredAt", draft.acquiredAt)
        put("root.acquisitionContextKind", draft.acquisitionContextKind)
        put("root.acquisitionContextKey", draft.acquisitionContextKey)
        put("root.bodyMassMeasurement", draft.bodyMassMeasurement)
        put("root.bodyMassMeasuredAt", draft.bodyMassMeasuredAt)
        draft.conditions.forEachIndexed { conditionIndex, condition ->
            put("conditions[$conditionIndex].addedLoadSource", condition.addedLoadSource)
            put("conditions[$conditionIndex].addedLoadMeasuredAt", condition.addedLoadMeasuredAt)
            condition.attempts.forEachIndexed { attemptIndex, attempt ->
                val where = "conditions[$conditionIndex].attempts[$attemptIndex]"
                put("$where.sourceRecordedAt", attempt.sourceRecordedAt)
                put("$where.sourceAthleteId", attempt.sourceAthleteId)
                put("$where.acquiredAt", attempt.acquiredAt)
                put("$where.reuseConfirmedAt", attempt.reuseConfirmedAt)
                put("$where.reuseConfirmationVersion", attempt.reuseConfirmationVersion)
                put("$where.takeoffPtsUs", attempt.takeoffPtsUs)
                put("$where.takeoffPreviousPtsUs", attempt.takeoffPreviousPtsUs)
                put("$where.takeoffNextPtsUs", attempt.takeoffNextPtsUs)
                put("$where.landingPtsUs", attempt.landingPtsUs)
                put("$where.landingPreviousPtsUs", attempt.landingPreviousPtsUs)
                put("$where.landingNextPtsUs", attempt.landingNextPtsUs)
                put("$where.timeMapping", attempt.timeMapping)
                put("$where.timeMappingEvidenceKey", attempt.timeMappingEvidenceKey)
            }
        }
    }

    /**
     * Evidencia visual OPT-IN de la matriz de procedencia (misma puerta y mismo directorio que las
     * capturas de los otros tests FVP): sin `-e fvpCaptureEvidence true` no se crea NINGUN archivo.
     * El PNG va al cache de la app, junto a las demas capturas.
     *
     * [node] por defecto es la RAIZ de la pantalla. Un DIALOGO vive en su PROPIA ventana (otra
     * raiz), asi que su captura pasa el nodo del dialogo (`isDialog()`): `onRoot()` no es unico
     * mientras hay un dialogo abierto.
     */
    private fun captureFvpEvidence(
        fileName: String,
        node: SemanticsNodeInteraction = compose.onRoot(),
    ) {
        if (InstrumentationRegistry.getArguments().getString("fvpCaptureEvidence") != "true") return
        compose.waitForIdle()
        val directory = java.io.File(context.cacheDir, "fvp_provenance_ui").apply { mkdirs() }
        java.io.File(directory, fileName).outputStream().use {
            node.captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /**
     * Texto decimal robusto al locale del AVD: se construye con el separador decimal del locale
     * (el mismo que espera la UI) y se comprueba con el parser de la app; si no parsease, se usa el
     * punto, que el parser normaliza igualmente. El test NO depende del locale del aparato.
     */
    private fun localeDecimal(plain: String): String {
        val locale: Locale = context.resources.configuration.locales[0]
        val separator = (NumberFormat.getNumberInstance(locale) as? DecimalFormat)
            ?.decimalFormatSymbols?.decimalSeparator ?: '.'
        val candidate = plain.replace('.', separator)
        return if (MeasurementFormatting.parseNumber(candidate, locale) != null) candidate else plain
    }

    private companion object {
        /** Fecha de medicion fija (UTC) de las series de prueba con metadata B1 completa. */
        const val FVP_MEASURED_AT = 1_700_000_000_000L

        /**
         * Las 20 columnas de procedencia del contrato B1b-2b por NOMBRE (19 nombres unicos:
         * `acquiredAt` esta en la raiz y en el intento). El test de duplicacion exige que el fixture
         * las cubra TODAS antes de comparar la copia: asi la comparacion no puede pasar por no
         * haber nada que copiar.
         */
        val PROVENANCE_COLUMN_NAMES = setOf(
            "acquiredAt",
            "acquisitionContextKind",
            "acquisitionContextKey",
            "bodyMassMeasurement",
            "bodyMassMeasuredAt",
            "addedLoadSource",
            "addedLoadMeasuredAt",
            "sourceRecordedAt",
            "sourceAthleteId",
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
    }
}
