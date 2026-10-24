package com.openjump.app.ui.fvp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Revisión ACCIONABLE de Condiciones sobre la PANTALLA REAL ([FvpConditionsScreen]).
 *
 * Cubre el slice B del plan `.pi/plans/fvp-guidance-ux.md`:
 * 1. el pie muestra el PRIMER motivo exacto del listado global y una accion secundaria que
 *    desplaza al campo afectado (identidad por `draftKey`, no por indice visible);
 * 2. una altura invalida en un intento EXCLUIDO tambien se marca en su propio campo;
 * 3. borrar la condicion afectada no deja ningun destino obsoleto ni cambia el numero de filas;
 * 4. revisar NUNCA muta el borrador ni pide foco (no abre el IME) y `UNKNOWN` sigue siendo valido;
 * 5. `canCalculate`, la elegibilidad y las mutaciones son los MISMOS: una serie completa queda
 *    calculable y sin motivo pendiente;
 * 6. con pocas condiciones el destino es el boton existente "Anadir condicion".
 *
 * Las capturas son OPT-IN (`-e fvpGuidanceEvidence true` o `-e fvpCaptureEvidence true`) y se
 * escriben en `cacheDir/fvp_conditions_guidance`; el perfil de unidades se puede forzar con
 * `-e fvpUnitProfile metric|us|custom`. Sin flags no se escribe ningun archivo.
 */
@RunWith(AndroidJUnit4::class)
class FvpConditionsGuidanceInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: JumpDatabase
    private lateinit var viewModel: FvpViewModel
    private var athleteId: Long = 0L
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        FvpDraftSession.reset()
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
    // 1: motivo exacto + revision al campo de la SEGUNDA condicion
    // ------------------------------------------------------------------------------------------

    @Test
    fun reviewScrollsToTheAffectedFieldOfTheSecondCondition() {
        val base = completeDraft()
        FvpDraftSession.adopt(
            base.copy(
                conditions = base.conditions.mapIndexed { index, condition ->
                    if (index == 1) condition.withHeight(1.5) else condition
                },
            ),
        )
        val adopted = requireNotNull(FvpDraftSession.draft.value)
        setConditionsContent()

        // La carga afectada es la SEGUNDA: se abre su editor por la fila resumen (solo uno activo).
        openLoad(1)
        // El pie dice el PRIMER motivo del listado (altura fuera de rango) y ofrece revisar.
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        compose.onNodeWithTag("fvp-conditions-first-reason")
            .assertTextContains(heightErrorPrefix(), substring = true)
        compose.onNodeWithTag("fvp-conditions-add").assertIsDisplayed()

        compose.onNodeWithTag("fvp-conditions-review").assertHeightIsAtLeast(48.dp)
        // El campo afectado es la altura del intento de la SEGUNDA condicion, localizada por
        // ancestro (los tags de intento se repiten entre condiciones).
        val affected = hasTestTag("fvp-attempt-height-0") and hasAnyAncestor(hasTestTag("fvp-condition-1"))
        // Componer no pide foco: el IME no se abre solo.
        compose.onNode(affected).assertIsNotFocused()
        // Se abre OTRA carga: el editor de la afectada deja de estar compuesto, de modo que Revisar
        // tiene que ABRIR la carga correcta antes de desplazar (no se asume una pila de editores ni
        // la geometria del scroll de un unico editor).
        openLoad(0)
        compose.onNode(affected).assertDoesNotExist()
        capture("conditions-before-review")

        compose.onNodeWithTag("fvp-conditions-review").performClick()
        compose.waitForIdle()

        // La accion ABRE la carga afectada y SOLO desplaza: campo visible, sin foco ni teclado.
        compose.onNode(affected).assertIsDisplayed().assertIsNotFocused()
        compose.onNodeWithTag("fvp-load-row-1").assertIsSelected()
        // El texto temporal debe ajustarse sin expulsar el interruptor con letra ampliada.
        val validity = hasTestTag("fvp-attempt-valid-0") and hasAnyAncestor(hasTestTag("fvp-condition-1"))
        compose.onNode(validity).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val switchBounds = compose.onNode(validity).fetchSemanticsNode().boundsInRoot
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("el interruptor debe permanecer dentro del ancho util", switchBounds.left >= rootBounds.left && switchBounds.right <= rootBounds.right)
        capture("conditions-review-target")
        // Misma puerta y mismo borrador: revisar no calcula, no muta y no reescribe nada.
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        assertEquals(adopted, FvpDraftSession.draft.value)
    }

    // ------------------------------------------------------------------------------------------
    // 2: borrar la condicion afectada no deja destino obsoleto
    // ------------------------------------------------------------------------------------------

    @Test
    fun deletingTheAffectedConditionLeavesNoStaleDestination() {
        val base = completeDraft()
        FvpDraftSession.adopt(
            base.copy(
                conditions = base.conditions.mapIndexed { index, condition ->
                    if (index == 1) condition.withHeight(1.5) else condition
                },
            ),
        )
        val deletedKey = requireNotNull(FvpDraftSession.draft.value).conditions[1].draftKey
        setConditionsContent()
        compose.onNodeWithTag("fvp-conditions-review").assertIsDisplayed()

        // El boton de borrar vive DENTRO del editor de la carga: se abre la carga 1 antes de usarlo.
        openLoad(1)
        compose.onNode(
            hasText(context.getString(R.string.fvp_remove_condition)) and
                hasAnyAncestor(hasTestTag("fvp-condition-1")),
        ).performScrollTo().performClick()
        compose.waitForIdle()

        val afterDelete = requireNotNull(FvpDraftSession.draft.value)
        assertEquals("solo se borra la condicion elegida", 3, afterDelete.conditions.size)
        assertFalse(
            "la condicion borrada no puede sobrevivir en el borrador",
            afterDelete.conditions.any { it.draftKey == deletedKey },
        )
        // Con las tres condiciones restantes validas no queda motivo ni destino que revisar.
        compose.onNodeWithTag("fvp-conditions-first-reason").assertDoesNotExist()
        compose.onNodeWithTag("fvp-conditions-review").assertDoesNotExist()
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsEnabled()
        compose.onNodeWithTag("fvp-conditions-blocking").assertDoesNotExist()
        capture("conditions-after-delete")
    }

    // ------------------------------------------------------------------------------------------
    // 3: revisar no muta; UNKNOWN no se promueve; sin foco automatico
    // ------------------------------------------------------------------------------------------

    @Test
    fun deletingAnEarlierConditionKeepsTheMovedInvalidFieldsAnchor() {
        val base = completeDraft()
        FvpDraftSession.adopt(base.copy(conditions = base.conditions.mapIndexed { index, condition ->
            if (index == 1) condition.withHeight(1.5) else condition
        }))
        val affectedKey = requireNotNull(FvpDraftSession.draft.value).conditions[1].draftKey
        setConditionsContent()
        compose.onNode(
            hasText(context.getString(R.string.fvp_remove_condition)) and
                hasAnyAncestor(hasTestTag("fvp-condition-0")),
        ).performScrollTo().performClick()
        compose.waitForIdle()
        val afterDelete = requireNotNull(FvpDraftSession.draft.value)
        assertEquals(affectedKey, afterDelete.conditions[0].draftKey)
        val affected = hasTestTag("fvp-attempt-height-0") and hasAnyAncestor(hasTestTag("fvp-condition-0"))
        // Tras borrar la condicion ACTIVA, el manejador abre la siguiente en la misma posicion. Para
        // probar que Revisar no depende de esa eleccion automatica, se abre OTRA carga de forma
        // explicita: el editor movido deja de estar compuesto (un unico editor activo).
        openLoad(1)
        compose.onNodeWithTag("fvp-load-row-1").assertIsSelected()
        compose.onNodeWithTag("fvp-condition-0").assertDoesNotExist()
        compose.onNodeWithTag("fvp-conditions-review").performClick()
        compose.waitForIdle()
        compose.onNode(affected).assertIsDisplayed().assertIsNotFocused()
        compose.onNodeWithTag("fvp-load-row-0").assertIsSelected()
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        assertEquals(afterDelete, FvpDraftSession.draft.value)
    }

    @Test
    fun reviewNeverMutatesTheDraftAndUnknownLoadSourceStaysValid() {
        val base = completeDraft()
        FvpDraftSession.adopt(
            base.copy(
                pushOffMode = FvpPushOffUiMode.PER_CONDITION,
                conditions = base.conditions.mapIndexed { index, condition ->
                    // Todas declaran procedencia UNKNOWN: el valor de la carga no la promueve.
                    if (index == 1) condition.copy(pushOffM = null) else condition.copy(pushOffM = 0.40)
                },
            ),
        )
        val before = requireNotNull(FvpDraftSession.draft.value)
        assertEquals("UNKNOWN", before.conditions[0].addedLoadSource)
        setConditionsContent()

        compose.onNodeWithTag("fvp-conditions-first-reason")
            .assertTextContains(context.getString(R.string.fvp_block_missing_push_off), substring = true)
        compose.onNode(hasTestTag("fvp-condition-pushoff-0")).assertIsNotFocused()
        compose.onNodeWithTag("fvp-conditions-review").performClick()
        compose.waitForIdle()

        // Revisar no escribe en el borrador (ni procedencia, ni valores, ni claves).
        assertEquals("revisar no puede mutar el borrador", before, FvpDraftSession.draft.value)
        assertEquals("UNKNOWN", FvpDraftSession.draft.value?.conditions?.get(0)?.addedLoadSource)
        // Revisar ABRE la carga afectada (la 1) antes de desplazar; el interruptor de ESA carga no
        // recibe foco automatico.
        compose.onNodeWithTag("fvp-load-row-1").assertIsSelected()
        compose.onNode(hasTestTag("fvp-condition-pushoff-1")).assertIsNotFocused()
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()

        // Volver a la carga 0 para editar su valor: el editor se elige por la fila, no por indice
        // reciclado. Editar la CARGA no promueve la procedencia declarada: sigue siendo UNKNOWN.
        openLoad(0)
        compose.onNodeWithTag("fvp-condition-load-0").performScrollTo().performTextClearance()
        compose.onNodeWithTag("fvp-condition-load-0").performTextInput("25")
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-condition-load-0").assertTextContains("25", substring = true)
        assertEquals(
            "el valor de la carga no puede promover UNKNOWN a pesada",
            "UNKNOWN",
            FvpDraftSession.draft.value?.conditions?.get(0)?.addedLoadSource,
        )
        capture("conditions-pushoff-review")
    }

    // ------------------------------------------------------------------------------------------
    // 4: intento EXCLUIDO con altura invalida -> su propio campo
    // ------------------------------------------------------------------------------------------

    @Test
    fun excludedInvalidHeightIsMarkedInItsOwnAttemptField() {
        val base = completeDraft()
        val first = base.conditions[0]
        FvpDraftSession.adopt(
            base.copy(
                conditions = listOf(
                    first.copy(
                        attempts = first.attempts + FvpAttemptDraft(
                            heightM = 1.5,
                            method = FvpMeasureMethod.MANUAL,
                            valid = false,
                            exclusionReason = "contacto distinto",
                        ),
                    ),
                ) + base.conditions.drop(1),
            ),
        )
        setConditionsContent()

        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        // El motivo es la altura inutilizable del intento EXCLUIDO (no "sin intento valido").
        compose.onNodeWithTag("fvp-conditions-first-reason")
            .assertTextContains(heightErrorPrefix(), substring = true)
        val excludedField = hasTestTag("fvp-attempt-height-1") and hasAnyAncestor(hasTestTag("fvp-condition-0"))
        compose.onNode(excludedField).performScrollTo().assertIsDisplayed()
        // El campo lleva su propio texto de error (misma regla de dominio para intentos excluidos).
        compose.onNode(
            hasText(heightErrorPrefix(), substring = true) and
                hasAnyAncestor(hasTestTag("fvp-condition-0")),
        ).assertExists()
        // Y sigue siendo un intento NO valido con su motivo: excluir no exime de la altura.
        compose.onNode(
            hasText(context.getString(R.string.fvp_attempt_invalid)) and
                hasAnyAncestor(hasTestTag("fvp-condition-0")),
        ).assertExists()
        capture("conditions-excluded-height")
    }

    // ------------------------------------------------------------------------------------------
    // 5: serie completa -> misma puerta, sin motivo ni destino
    // ------------------------------------------------------------------------------------------

    @Test
    fun aNewBlankConditionIsPendingNotAnImmediateFieldError() {
        FvpDraftSession.adopt(completeDraft())
        setConditionsContent()
        compose.onNodeWithTag("fvp-conditions-add").performClick()
        compose.waitForIdle()
        val height = hasTestTag("fvp-attempt-height-0") and hasAnyAncestor(hasTestTag("fvp-condition-4"))
        compose.onNode(height).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        compose.onNodeWithTag("fvp-condition-load-4").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        compose.onNodeWithTag("fvp-condition-key-4").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        // Revisar explicito muestra el error solo en el campo al que se lleva al usuario.
        compose.onNodeWithTag("fvp-conditions-review").performClick()
        compose.waitForIdle()
        compose.onNode(height).assertIsDisplayed().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithTag("fvp-condition-load-4").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        capture("conditions-pristine-review")
    }

    @Test
    fun completeSeriesKeepsTheSameGateWithoutPendingReason() {
        val draft = completeDraft()
        FvpDraftSession.adopt(draft)
        setConditionsContent()

        compose.onNodeWithTag("fvp-conditions-calculate").assertIsEnabled()
        compose.onNodeWithTag("fvp-conditions-first-reason").assertDoesNotExist()
        compose.onNodeWithTag("fvp-conditions-review").assertDoesNotExist()
        compose.onNodeWithTag("fvp-conditions-identical-heights-guidance").assertDoesNotExist()
        compose.onNodeWithTag("fvp-conditions-blocking").assertDoesNotExist()
        // Ninguna marca de error local se pinta en una serie completa.
        assertTrue(
            "una serie completa no debe mostrar errores locales de clave",
            compose.onAllNodesWithText(context.getString(R.string.fvp_block_invalid_condition_key))
                .fetchSemanticsNodes().isEmpty(),
        )
        assertEquals(draft, FvpDraftSession.draft.value)
    }

    // ------------------------------------------------------------------------------------------
    // 6: motivo exacto sin formato + destino "Anadir condicion" cuando faltan condiciones
    // ------------------------------------------------------------------------------------------

    @Test
    fun footerShowsTheExactDuplicateLoadReasonAndFewConditionsPointToAdd() {
        val base = completeDraft()
        FvpDraftSession.adopt(
            base.copy(
                conditions = base.conditions.mapIndexed { index, condition ->
                    // Cargas 0 / 20 / 20 / 40 con claves DISTINTAS: el primer motivo es la carga
                    // repetida (texto sin argumentos, comparable exactamente).
                    when (index) {
                        1 -> condition.copy(conditionKey = "20kg-a", addedLoadKg = 20.0)
                        2 -> condition.copy(conditionKey = "20kg-b", addedLoadKg = 20.0)
                        else -> condition
                    }
                },
            ),
        )
        setConditionsContent()

        val exact = context.getString(R.string.fvp_block_duplicate_load)
        compose.onNodeWithTag("fvp-conditions-first-reason").assertTextEquals(exact)
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        capture("conditions-exact-reason")

        // Pocas condiciones: el destino es el boton EXISTENTE de anadir, siempre visible.
        FvpDraftSession.adopt(
            base.copy(conditions = listOf(FvpConditionDraft(conditionKey = "", attempts = listOf(FvpAttemptDraft()))))
                .copy(bodyMassKg = 80.0, commonPushOffM = 0.40),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-conditions-first-reason").assertExists()
        compose.onNodeWithTag("fvp-conditions-add").assertIsDisplayed()
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        capture("conditions-add-destination")
    }

    // ------------------------------------------------------------------------------------------
    // 7: alturas identicas -> revisar mediciones originales, sin empujar a tocar valores
    // ------------------------------------------------------------------------------------------

    @Test
    fun identicalHeightsOfferGuidanceWithoutSuggestingAFit() {
        val base = completeDraft()
        FvpDraftSession.adopt(
            base.copy(conditions = base.conditions.map { condition -> condition.withHeight(0.30) }),
        )
        setConditionsContent()

        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        compose.onNodeWithTag("fvp-conditions-first-reason")
            .assertTextEquals(context.getString(R.string.fvp_block_identical_heights))
        // La orientacion dice que se revisen las mediciones originales, no que se cambien valores.
        compose.onNodeWithTag("fvp-conditions-identical-heights-guidance").assertIsDisplayed()
        // Ninguna altura es "la culpable": no se ofrece una accion que empuje a tocar una medicion.
        compose.onNodeWithTag("fvp-conditions-review").assertDoesNotExist()
        capture("conditions-identical-heights")
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
            guidedCondition("0kg", 0.0, 0.40),
            guidedCondition("20kg", 20.0, 0.32),
            guidedCondition("40kg", 40.0, 0.25),
            guidedCondition("60kg", 60.0, 0.18),
        ),
    )

    private fun guidedCondition(key: String, loadKg: Double, heightM: Double): FvpConditionDraft =
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

    private fun FvpConditionDraft.withHeight(heightM: Double): FvpConditionDraft =
        copy(attempts = attempts.map { it.copy(heightM = heightM) })

    private fun heightErrorPrefix(): String =
        context.getString(R.string.fvp_block_invalid_height).substringBefore('%').trim()

    private fun setConditionsContent() {
        compose.setContent {
            OpenJumpTheme {
                val profile = when (InstrumentationRegistry.getArguments().getString("fvpUnitProfile")) {
                    "us" -> UnitProfile.UNITED_STATES
                    "custom" -> UnitProfile.METRIC.copy(shortLength = ShortLengthUnit.INCH)
                    else -> UnitProfile.METRIC
                }
                CompositionLocalProvider(LocalUnitSystem provides profile) {
                    FvpConditionsScreen(onBack = {}, onCalculate = {}, viewModel = viewModel)
                }
            }
        }
        compose.waitForIdle()
    }

    /** Abre la carga `index` desde su fila resumen (un UNICO editor activo por vez). */
    private fun openLoad(index: Int) {
        compose.onNodeWithTag("fvp-load-row-$index").performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** Capturas OPT-IN: evidencia visual, nunca un requisito de la pasada por defecto. */
    private fun capture(name: String) {
        val args = InstrumentationRegistry.getArguments()
        val enabled = args.getString("fvpGuidanceEvidence") == "true" ||
            args.getString("fvpCaptureEvidence") == "true"
        if (!enabled) return
        compose.waitForIdle()
        val directory = File(context.cacheDir, "fvp_conditions_guidance").apply { mkdirs() }
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
