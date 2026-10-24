package com.openjump.app.ui.fvp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.FvpRepository
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.JumpRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Jerarquia de entrada/captura del flujo F–V–P sobre las PANTALLAS REALES
 * ([FvpSetupScreen] / [FvpConditionsScreen]), no una replica del layout.
 *
 * Lo que comprueba (plan `fvp-entry-capture-ux.md`, slices A y B):
 * 1. los datos (atleta, protocolo y masa) estan en el primer viewport y la cabecera solo muestra un
 *    aviso BREVE de estimacion experimental / no medicion directa;
 * 2. la ayuda "Metodo y limites" arranca PLEGADA y, al abrirse, contiene los textos completos ya
 *    existentes (etiqueta extendida, titulo y cuerpo del aviso de estimacion);
 * 3. abrir/cerrar esa ayuda no cambia lo tecleado ni el estado del CTA (el plegable es solo UI);
 * 4. la fecha de medicion es visible aunque los componentes opcionales esten plegados;
 * 5. los avisos de metadatos y de clave de contexto siguen alcanzables junto a SU campo y el CTA
 *    sigue deshabilitado (el gate no cambia);
 * 6. los componentes opcionales conservan texto y estado desplegado tras restaurar la instancia
 *    guardada;
 * 7. en Condiciones el primer editor se ve ANTES del listado global de bloqueos, que sigue
 *    alcanzable y completo, y `Calcular` conserva su gating.
 *
 * Las capturas son OPT-IN (`-e fvpCaptureEvidence true`) y se escriben en
 * `cacheDir/fvp_capture_ux` con nombres estables (`setup-initial`, `help-open`, `push-off-date`,
 * `context`, `conditions-initial`); sin ese flag no se crea ningun archivo. El perfil de unidades se
 * puede forzar con `-e fvpUnitProfile metric|us|custom`; el tema es el del PRODUCTO (claro/oscuro lo
 * fija el AVD, de modo que el padre puede producir la matriz sin tocar esta prueba).
 *
 * Esta prueba NO demuestra nada sobre hardware fisico ni TalkBack hablado.
 */
@RunWith(AndroidJUnit4::class)
class FvpCaptureUxInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: JumpDatabase
    private lateinit var repository: FvpRepository
    private lateinit var viewModel: FvpViewModel
    private var athleteId: Long = 0L
    private val restoration by lazy { StateRestorationTester(compose) }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        FvpDraftSession.reset()
        // La introduccion de primer uso ya esta vista: estos casos prueban la superficie de captura
        // (cabecera/ayuda/contexto), no la presentacion inicial del flujo.
        markFvpIntroductionSeen()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JumpDatabase::class.java,
        ).build()
        repository = FvpRepository(database.fvpDao())
        viewModel = FvpViewModel(repository, JumpRepository(database.jumpDao()))
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
    // A: cabecera compacta, datos primero, ayuda plegable
    // ------------------------------------------------------------------------------------------

    @Test
    fun setupShowsDataFirstWithCompactHeaderAndHelpClosedByDefault() {
        setSetupContent()

        // Aviso BREVE y persistente: experimental / no medicion directa.
        compose.onNodeWithTag("fvp-capture-experimental").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.fvp_context_experimental)).assertIsDisplayed()
        // La ayuda larga arranca PLEGADA: sus textos no estan compuestos todavia.
        assertTrue(
            "la ayuda de metodo debe arrancar plegada",
            compose.onAllNodesWithTag("fvp-capture-method-body").fetchSemanticsNodes().isEmpty(),
        )
        capture("setup-initial")
        // Al100% los tres datos estan en el primer viewport. Con fuente ampliada se exige que
        // sigan alcanzables por scroll, sin reducir tipografia ni recortar para forzar la foto.
        if (context.resources.configuration.fontScale <= 1.0f) {
            compose.onNodeWithText(ATHLETE_NAME).assertIsDisplayed()
            compose.onNodeWithTag("fvp-setup-protocol").assertIsDisplayed()
            compose.onNodeWithTag("fvp-setup-mass").assertIsDisplayed()
        } else {
            compose.onNodeWithText(ATHLETE_NAME).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("fvp-setup-protocol").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("fvp-setup-mass").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("fvp-setup-protocol-value", useUnmergedTree = true)
            .assertTextContains(FvpProtocol.SJ.name, substring = true)
        assertTrue(
            "la masa sugerida del perfil debe llegar tecleada al campo",
            editableText("fvp-setup-mass").isNotBlank(),
        )
        // La etiqueta experimental EXTENDIDA ya no ocupa el primer viewport.
        assertTrue(
            "la etiqueta experimental larga no puede estar siempre visible",
            compose.onAllNodesWithText(context.getString(R.string.fvp_experimental_label))
                .fetchSemanticsNodes().isEmpty(),
        )

        // Ayuda abierta: los textos completos existentes aparecen.
        compose.onNodeWithTag("fvp-capture-method-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-capture-method-body").assertExists()
        compose.onNodeWithText(context.getString(R.string.fvp_experimental_label)).assertExists()
        compose.onNodeWithText(context.getString(R.string.fvp_estimate_notice_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.fvp_estimate_notice_body)).assertExists()
        capture("help-open")
    }

    @Test
    fun togglingMethodHelpDoesNotChangeTypedValuesNorContinueState() {
        setSetupContent()
        typeInto("fvp-setup-mass", localeDecimal("82.5"))
        // La masa vive en DATA: se comprueba ANTES de pasar a MEDICION (no se asume compuesta en la
        // pagina de h_po).
        val massBefore = editableText("fvp-setup-mass")
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-page-measurement").fetchSemanticsNodes().isNotEmpty()
        }
        typeInto("fvp-setup-pushoff", pushOffText())
        typeInto("fvp-setup-landmark", "TROCANTER")
        typeInto("fvp-setup-procedure", "LANDMARK_EXTENDED_MINUS_CROUCH")
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled()
        val pushOffBefore = editableText("fvp-setup-pushoff")

        // La ayuda "Metodo y limites" ya no se compone en MEDICION: se reabre la INTRO como ayuda
        // por su accion real, se abre/cierra alli y se vuelve a la MISMA pagina.
        compose.onNodeWithTag("fvp-intro-action").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-intro").assertIsDisplayed()
        // La INTRO se compone entera en su columna con scroll; abrir/cerrar y volver se invoca por
        // semantica para no depender de la geometria (contenido que quepa o no en el viewport).
        compose.onNodeWithTag("fvp-capture-method-toggle")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("fvp-capture-method-body").assertExists()
        compose.onNodeWithTag("fvp-capture-method-toggle")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("fvp-capture-method-body").assertDoesNotExist()
        compose.onNodeWithTag("fvp-intro-back")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-page-measurement").assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled()

        assertEquals("la masa tecleada no cambio al pasar de pagina", "82.5", massBefore.replace(',', '.'))
        assertEquals(
            "h_po tecleado no puede cambiar al plegar la ayuda",
            pushOffBefore,
            editableText("fvp-setup-pushoff"),
        )
        assertEquals("TROCANTER", editableText("fvp-setup-landmark"))
        assertEquals("LANDMARK_EXTENDED_MINUS_CROUCH", editableText("fvp-setup-procedure"))
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled()

        // La masa de DATA tampoco se pierde al ir y volver entre paginas: se regresa a DATA por la
        // barra superior y se compara el texto EXACTO que se tecleo (no solo su valor canonico).
        compose.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-page-data").assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-mass").performScrollTo().assertIsDisplayed()
        assertEquals(
            "la masa tecleada se conserva al volver a DATA",
            massBefore,
            editableText("fvp-setup-mass"),
        )
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-page-measurement").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled()
    }

    @Test
    fun measurementDateStaysVisibleWhileOptionalComponentsAreCollapsed() {
        setSetupContent()
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-page-measurement").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "los componentes opcionales deben arrancar contraidos",
            compose.onAllNodesWithTag("fvp-setup-crouch").fetchSemanticsNodes().isEmpty(),
        )
        // La fecha se pinta SIEMPRE, aunque el plegable de componentes siga cerrado.
        compose.onNodeWithTag("fvp-setup-measured-at").performScrollTo().assertIsDisplayed()
        assertTrue(
            "la fecha debe mostrarse en el boton del DatePicker",
            compose.onAllNodesWithText(
                context.getString(R.string.fvp_push_off_measured_at) + ": ",
                substring = true,
            ).fetchSemanticsNodes().isNotEmpty(),
        )
        capture("push-off-date")
        compose.onNodeWithTag("fvp-setup-push-off-approximation").performScrollTo().assertIsDisplayed()

        // Abrir los componentes no mueve ni oculta la fecha.
        compose.onNodeWithTag("fvp-setup-details-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-setup-crouch").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-measured-at").performScrollTo().assertIsDisplayed()
    }

    /**
     * El gate B1 sigue dicho junto a los campos que lo resuelven (hito/procedimiento) y bloquea la
     * accion FINAL de MEDICION. El contexto de adquisicion ya no vive aqui: su unico dueno es
     * Condiciones, donde `FvpGuidedLoadsInstrumentedTest` cubre el aviso y el bloqueo SAME sin clave.
     */
    @Test
    fun metadataWarningsStayReachableNextToTheirFieldsAndBlockContinue() {
        setSetupContent()
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-page-measurement").fetchSemanticsNodes().isNotEmpty()
        }
        // Sin hito ni procedimiento el gate B1 bloquea y lo dice junto a ESOS campos.
        compose.onNodeWithTag("fvp-setup-continue").assertIsNotEnabled()
        compose.onNodeWithTag("fvp-setup-metadata-warning").performScrollTo().assertIsDisplayed()
        // El pie solo lleva un motivo breve, no el muro de metadatos.
        compose.onNodeWithTag("fvp-setup-incomplete-hint").assertIsDisplayed()

        typeInto("fvp-setup-pushoff", pushOffText())
        compose.onNodeWithTag("fvp-setup-continue").assertIsNotEnabled()
        typeInto("fvp-setup-landmark", "TROCANTER")
        typeInto("fvp-setup-procedure", "LANDMARK_EXTENDED_MINUS_CROUCH")
        compose.onNodeWithTag("fvp-setup-metadata-warning").assertDoesNotExist()
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled()
        capture("measurement-gate")
    }

    @Test
    fun optionalComponentsRetainValuesAndExpandedStateAfterRestoration() {
        setSetupContent()
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-page-measurement").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fvp-setup-details-toggle").performScrollTo().performClick()
        typeInto("fvp-setup-crouch", localeDecimal("34.5"))
        typeInto("fvp-setup-extended", localeDecimal("56.5"))
        val crouchBefore = editableText("fvp-setup-crouch")
        val extendedBefore = editableText("fvp-setup-extended")

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        // El estado del plegable sigue guardado (no se repliega en silencio) y los valores se
        // conservan exactamente como se teclearon.
        compose.onNodeWithTag("fvp-setup-crouch").performScrollTo().assertIsDisplayed()
        assertEquals(crouchBefore, editableText("fvp-setup-crouch"))
        assertEquals(extendedBefore, editableText("fvp-setup-extended"))
        compose.onNodeWithTag("fvp-setup-measured-at").performScrollTo().assertIsDisplayed()
    }

    // ------------------------------------------------------------------------------------------
    // B: condiciones con el mismo encabezado y bloqueo global despues del editor
    // ------------------------------------------------------------------------------------------

    @Test
    fun conditionsShowsTheFirstEditorBeforeTheGlobalBlockingListAndKeepsCalculateGated() {
        FvpDraftSession.begin(athleteId, FvpProtocol.SJ).let { draft ->
            FvpDraftSession.update {
                draft.copy(
                    bodyMassKg = 80.0,
                    bodyMassFromProfile = true,
                    pushOffMode = FvpPushOffUiMode.COMMON,
                    commonPushOffM = 0.40,
                    pushOffLandmark = "TROCANTER",
                    pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
                    conditions = listOf(
                        FvpConditionDraft(conditionKey = "0kg", addedLoadKg = 0.0, attempts = listOf(FvpAttemptDraft())),
                    ),
                )
            }
        }
        setConditionsContent()

        // Misma cabecera breve; ayuda plegada por defecto.
        compose.onNodeWithTag("fvp-capture-experimental").assertIsDisplayed()
        assertTrue(
            "la ayuda de metodo debe arrancar plegada tambien en condiciones",
            compose.onAllNodesWithTag("fvp-capture-method-body").fetchSemanticsNodes().isEmpty(),
        )
        capture("conditions-initial")
        // La cabecera incluye el timeline compartido en el paso 3 y el contexto arriba: el
        // campo de carga deja de caber siempre en el primer viewport. Se exige que el input ACTIVO
        // sea ALCANZABLE por scroll (no se conserva ninguna compatibilidad con el layout gigante
        // anterior) antes de recorrer el diagnostico completo.
        compose.onNodeWithContentDescription(context.getString(R.string.jump_flow_progress, 3, 3)).assertExists()
        compose.onNodeWithTag("fvp-acquisition-context-UNKNOWN").performScrollTo().assertIsDisplayed()
        // La guia del contexto (explicacion + ejemplo de "sin declarar") vive ahora en Condiciones,
        // su dueno unico: se consulta aqui en vez de fabricar un contexto en Setup.
        compose.onNodeWithTag("fvp-acquisition-context-help-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-acquisition-context-help").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.fvp_acquisition_context_example)).assertExists()
        compose.onNodeWithTag("fvp-condition-load-0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        // El listado GLOBAL de bloqueos sigue completo y alcanzable, pero DESPUES del editor.
        compose.onNodeWithTag("fvp-conditions-blocking").performScrollTo().assertIsDisplayed()
        val editorTop = compose.onNodeWithTag("fvp-condition-0").fetchSemanticsNode().boundsInRoot.top
        val blockingTop = compose.onNodeWithTag("fvp-conditions-blocking").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "el editor debe ir antes que el listado global de bloqueos (editor=$editorTop, bloqueo=$blockingTop)",
            blockingTop > editorTop,
        )
        compose.onNodeWithText(context.getString(R.string.fvp_cannot_compute)).assertExists()
        // El intento inicial esta marcado valido, pero su altura esta VACIA: el motivo es altura
        // ausente/invalida, no «sin intentos validos». Los rangos exactos se prueban en FormUnits.
        for (reason in listOf(
            context.getString(R.string.fvp_block_too_few_conditions),
            context.getString(R.string.fvp_block_invalid_height).substringBefore('%').trim(),
        )) {
            compose.onNode(
                hasText(reason, substring = true) and
                    hasAnyAncestor(hasTestTag("fvp-conditions-blocking")),
            ).assertExists()
        }

        // Abrir la ayuda no cambia el gating de Calcular ni los valores del editor.
        compose.onNodeWithTag("fvp-capture-method-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        assertTrue(
            "la carga inicial sigue tecleada en el editor",
            editableText("fvp-condition-load-0").isNotBlank(),
        )
    }

    @Test
    fun conditionsCalculateStaysEnabledForACompleteSeries() {
        FvpDraftSession.begin(athleteId, FvpProtocol.SJ).let { draft ->
            FvpDraftSession.update {
                draft.copy(
                    bodyMassKg = 80.0,
                    bodyMassFromProfile = true,
                    pushOffMode = FvpPushOffUiMode.COMMON,
                    commonPushOffM = 0.40,
                    pushOffLandmark = "TROCANTER",
                    pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
                    pushOffMeasuredAt = 1_699_500_000_000L,
                    conditions = listOf(
                        FvpConditionDraft(
                            conditionKey = "0kg",
                            addedLoadKg = 0.0,
                            attempts = listOf(
                                FvpAttemptDraft(heightM = 0.40, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0),
                            ),
                        ),
                        FvpConditionDraft(
                            conditionKey = "20kg",
                            addedLoadKg = 20.0,
                            attempts = listOf(
                                FvpAttemptDraft(heightM = 0.32, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0),
                            ),
                        ),
                        FvpConditionDraft(
                            conditionKey = "40kg",
                            addedLoadKg = 40.0,
                            attempts = listOf(
                                FvpAttemptDraft(heightM = 0.25, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0),
                            ),
                        ),
                    ),
                )
            }
        }
        setConditionsContent()
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsEnabled()
        assertTrue(
            "una serie completa no debe pintar el listado global de bloqueos",
            compose.onAllNodesWithTag("fvp-conditions-blocking").fetchSemanticsNodes().isEmpty(),
        )
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

    private fun setConditionsContent() {
        setThemedContent {
            FvpConditionsScreen(onBack = {}, onCalculate = {}, viewModel = viewModel)
        }
    }

    /** Tema REAL del producto + perfil de unidades elegible por argumento (`fvpUnitProfile`). */
    private fun setThemedContent(content: @Composable () -> Unit) {
        restoration.setContent {
            OpenJumpTheme {
                val profile = when (InstrumentationRegistry.getArguments().getString("fvpUnitProfile")) {
                    "us" -> UnitProfile.UNITED_STATES
                    "custom" -> UnitProfile.METRIC.copy(shortLength = ShortLengthUnit.INCH)
                    else -> UnitProfile.METRIC
                }
                CompositionLocalProvider(LocalUnitSystem provides profile, content = content)
            }
        }
    }

    private fun typeInto(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextClearance()
        compose.onNodeWithTag(tag).performTextInput(value)
        compose.waitForIdle()
    }

    private fun pushOffText(): String = localeDecimal(
        when (InstrumentationRegistry.getArguments().getString("fvpUnitProfile")) {
            "us", "custom" -> "15.74803149606299" // 0.40 m, expresados en pulgadas.
            else -> "40"
        },
    )

    private fun editableText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

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
        get() = InstrumentationRegistry.getArguments().getString("fvpCaptureEvidence") == "true"

    private fun capture(name: String) {
        if (!captureEvidence) return
        compose.waitForIdle()
        val directory = File(context.cacheDir, "fvp_capture_ux").apply { mkdirs() }
        val file = File(directory, "$name.png")
        FileOutputStream(file).use { out ->
            val bitmap: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        assertTrue("la captura opt-in debe existir: ${file.name}", file.length() > 0)
    }

    private companion object {
        const val ATHLETE_NAME = "Capture QA"
    }
}
