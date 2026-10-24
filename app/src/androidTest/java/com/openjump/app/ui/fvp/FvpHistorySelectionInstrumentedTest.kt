package com.openjump.app.ui.fvp

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.FvpRepository
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.JumpRepository
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Historial F–V–P sobre la PANTALLA REAL ([FvpHistoryScreen]) con filas guardadas de verdad en una
 * base Room en memoria.
 *
 * Fija el contrato del slice de historial del plan `.pi/plans/fvp-guided-flow-implementation.md`
 * (seccion C) y del reset visual:
 * 1. el modo NORMAL abre el guardado con un toque desde TODA la fila, sin casillas permanentes ni
 *    un "Ver" repetido por fila;
 * 2. el modo de seleccion solo se activa con la accion Comparar, acepta como maximo dos series y el
 *    informe comparativo se abre con un boton EXPLICITO, en una superficie modal, nunca insertado
 *    delante de la lista;
 * 3. Back/Cerrar vuelve a la seleccion y Cancelar descarta la seleccion y vuelve a explorar;
 * 4. la serie incompatible sigue mostrando el bloqueo completo y ningun delta;
 * 5. la seleccion se reconcilia con las filas vigentes: al cambiar de atleta no queda ningun id
 *    huerfano ni se carga una fila de la lista anterior;
 * 6. el aviso experimental compacto y la ayuda completa del metodo siguen alcanzables tambien con
 *    la lista vacia.
 */
@RunWith(AndroidJUnit4::class)
class FvpHistorySelectionInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: JumpDatabase
    private lateinit var repository: FvpRepository
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
        repository = FvpRepository(database.fvpDao())
        viewModel = FvpViewModel(repository, JumpRepository(database.jumpDao()))
        athleteId = insertAthlete("Historial QA")
    }

    @After
    fun tearDown() {
        compose.waitForIdle()
        FvpDraftSession.reset()
        database.close()
    }

    /** Modo normal: toda la fila abre el `assessmentId` exacto y no hay controles de seleccion. */
    @Test
    fun normalRowOpensTheExactSavedAssessmentWithoutSelectionControls() {
        val first = saveDraft(athleteId, listOf(0.35, 0.30))
        val second = saveDraft(athleteId, listOf(0.38, 0.31))
        var opened: Long? = null
        setHistory(onOpen = { opened = it })

        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-history-open-$first").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("fvp-history-open-$second").fetchSemanticsNodes().isNotEmpty()
        }
        // Sin casillas permanentes ni el "Ver" repetido de la version anterior.
        compose.onAllNodesWithTag("fvp-history-select-$first").assertCountEquals(0)
        compose.onAllNodesWithTag("fvp-history-select-$second").assertCountEquals(0)
        compose.onAllNodesWithText(context.getString(R.string.fvp_history_open)).assertCountEquals(0)
        // La fila identifica fecha y protocolo, y muestra las cifras con sus unidades.
        compose.onNodeWithTag("fvp-history-date-$first", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("fvp-history-protocol-$first", useUnmergedTree = true).assertTextEquals("SJ")
        compose.onNodeWithTag("fvp-history-metrics-$first", useUnmergedTree = true).assertExists()

        captureGuidedScreen(compose, "fvp_guided_history", "history-browse")
        compose.onNodeWithTag("fvp-history-open-$first").performClick()
        compose.waitForIdle()
        assertEquals("la fila debe abrir SU guardado", first, opened)
    }

    /** Seleccion explicita, informe bajo pedido, Back a la seleccion y Cancelar a explorar. */
    @Test
    fun compareIsExplicitOpensTheReportModallyAndCancelReturnsToBrowsing() {
        val first = saveDraft(athleteId, listOf(0.35, 0.30))
        val second = saveDraft(athleteId, listOf(0.38, 0.31))
        setHistory()

        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-history-open-$first").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("fvp-history-open-$second").fetchSemanticsNodes().isNotEmpty()
        }
        // La pantalla normal NO ofrece seleccion ni informe.
        compose.onAllNodesWithTag("fvp-history-compare-count").assertCountEquals(0)
        compose.onAllNodesWithTag("fvp-history-compare-open").assertCountEquals(0)
        compose.onAllNodesWithTag("fvp-compare-report").assertCountEquals(0)

        // Entrar en el modo de seleccion no abre ningun informe ni carga filas guardadas.
        compose.onNodeWithTag("fvp-history-compare").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-history-compare-count").assertTextEquals("0/2")
        captureGuidedScreen(compose, "fvp_guided_history", "history-select-0")
        compose.onNodeWithTag("fvp-history-compare-open").assertIsNotEnabled()
        compose.onNodeWithTag("fvp-history-select-$first").performClick()
        compose.onNodeWithTag("fvp-history-compare-count").assertTextEquals("1/2")
        captureGuidedScreen(compose, "fvp_guided_history", "history-select-1")
        compose.onNodeWithTag("fvp-history-compare-open").assertIsNotEnabled()
        compose.onNodeWithTag("fvp-history-select-$second").performClick()
        compose.onNodeWithTag("fvp-history-compare-count").assertTextEquals("2/2")
        compose.onNodeWithTag("fvp-history-compare-open").assertIsEnabled()
        captureGuidedScreen(compose, "fvp_guided_history", "history-select-2")
        // Marcar la segunda fila NO inserta el informe delante de la lista.
        compose.onAllNodesWithTag("fvp-compare-report").assertCountEquals(0)
        compose.onAllNodesWithText(
            context.getString(R.string.fvp_compare_context_quality),
            substring = true,
        ).assertCountEquals(0)

        // El boton explicito abre el informe en la superficie modal, con el contexto completo.
        compose.onNodeWithTag("fvp-history-compare-open").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-compare-report").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fvp-compare-report").assertExists()
        compose.onNodeWithText(
            context.getString(R.string.fvp_compare_context_quality) + ":",
            substring = true,
        ).assertExists()

        // Back/Cerrar vuelve a la SELECCION (las dos series siguen elegidas).
        compose.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-compare-report").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("fvp-history-compare-count").assertTextEquals("2/2")
        compose.onNodeWithTag("fvp-history-select-$first").assertExists()

        // Cancelar descarta la seleccion y vuelve a explorar.
        compose.onNodeWithContentDescription(context.getString(R.string.common_cancel)).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("fvp-history-compare-count").assertCountEquals(0)
        compose.onAllNodesWithTag("fvp-history-select-$first").assertCountEquals(0)
        compose.onNodeWithTag("fvp-history-open-$first").assertExists()
    }

    /** Serie incompatible: el informe conserva el bloqueo completo y NO pinta deltas. */
    @Test
    fun incompatibleSeriesStillShowTheFullBlockAndNoDeltas() {
        val common = saveDraft(athleteId, listOf(0.35, 0.30))
        val perCondition = saveDraft(athleteId, listOf(0.35, 0.30), perConditionPushOff = true)
        setHistory()

        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-history-open-$common").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("fvp-history-open-$perCondition").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fvp-history-compare").performClick()
        compose.onNodeWithTag("fvp-history-select-$common").performClick()
        compose.onNodeWithTag("fvp-history-select-$perCondition").performClick()
        compose.onNodeWithTag("fvp-history-compare-open").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-compare-report").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.fvp_incompatible_series)).assertExists()
        // Motivo EXACTO del bloqueo (modo de h_po distinto) y contexto de calidad de cada serie.
        compose.onNodeWithText(
            context.getString(R.string.fvp_compare_block_different_protocol),
        ).assertExists()
        compose.onNodeWithText(
            context.getString(R.string.fvp_compare_context_quality) + ":",
            substring = true,
        ).assertExists()
        // Ninguna diferencia de rendimiento cuando la comparacion esta bloqueada.
        compose.onAllNodesWithText(context.getString(R.string.fvp_compare_delta_note)).assertCountEquals(0)
    }

    /**
     * Al cambiar de atleta no sobrevive ninguna seleccion ni se carga una fila de la lista anterior:
     * el modo vuelve a empezar y los ids de la lista nueva son los unicos que se pueden abrir.
     */
    @Test
    fun selectionIsReconciledAndNeverLoadsRowsFromThePreviousAthlete() {
        val otherAthlete = insertAthlete("Historial B")
        val oldA = saveDraft(athleteId, listOf(0.35, 0.30))
        val oldB = saveDraft(athleteId, listOf(0.38, 0.31))
        val newA = saveDraft(otherAthlete, listOf(0.40, 0.33))
        val newB = saveDraft(otherAthlete, listOf(0.42, 0.34))

        var currentAthlete by mutableStateOf(athleteId)
        compose.setContent {
            FvpHistoryScreen(
                athleteId = currentAthlete,
                onBack = {},
                onOpen = {},
                athleteName = if (currentAthlete == athleteId) "Historial QA" else "Historial B",
                viewModel = viewModel,
            )
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-history-open-$oldA").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("fvp-history-open-$oldB").fetchSemanticsNodes().isNotEmpty()
        }
        // Seleccion parcial de 1/2 en la lista del primer atleta.
        compose.onNodeWithTag("fvp-history-compare").performClick()
        compose.onNodeWithTag("fvp-history-select-$oldA").performClick()
        compose.onNodeWithTag("fvp-history-compare-count").assertTextEquals("1/2")

        currentAthlete = otherAthlete
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-history-open-$newA").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("fvp-history-open-$newB").fetchSemanticsNodes().isNotEmpty()
        }
        // El atleta de contexto es el nuevo y la seleccion/modo anterior no se heredan.
        compose.onNodeWithTag("fvp-history-athlete").assertTextEquals("Historial B")
        compose.onAllNodesWithTag("fvp-history-compare-count").assertCountEquals(0)
        compose.onAllNodesWithTag("fvp-history-select-$oldA").assertCountEquals(0)
        compose.onAllNodesWithTag("fvp-history-open-$oldA").assertCountEquals(0)
        compose.onAllNodesWithTag("fvp-compare-report").assertCountEquals(0)

        // La comparacion de la lista NUEVA solo puede leer sus dos filas.
        compose.onNodeWithTag("fvp-history-compare").performClick()
        compose.onNodeWithTag("fvp-history-select-$newA").performClick()
        compose.onNodeWithTag("fvp-history-select-$newB").performClick()
        compose.onNodeWithTag("fvp-history-compare-open").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-compare-report").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(
            context.getString(R.string.fvp_compare_context_quality) + ":",
            substring = true,
        ).assertExists()
        // Ninguna cifra de la fila anterior aparece en el informe de la lista nueva.
        val staleF0 = String.format(
            java.util.Locale.getDefault(),
            "%.1f",
            requireNotNull(runBlocking { repository.byId(oldA) }).assessment.f0N,
        ) + " N"
        compose.onAllNodesWithText(staleF0, substring = true).assertCountEquals(0)
    }

    /** Lista vacia: el aviso experimental compacto y la ayuda completa del metodo son alcanzables. */
    @Test
    fun emptyHistoryKeepsTheExperimentalContextAndTheFullMethodHelp() {
        val emptyAthlete = insertAthlete("Sin series")
        compose.setContent {
            FvpHistoryScreen(athleteId = emptyAthlete, onBack = {}, onOpen = {}, viewModel = viewModel)
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(context.getString(R.string.fvp_empty_state_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Etiqueta compacta SIEMPRE visible (tambien sin filas), no el parrafo metodologico largo.
        compose.onNodeWithText(context.getString(R.string.fvp_context_experimental)).assertExists()
        compose.onNodeWithTag("fvp-history-method-help").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-history-method-help-label").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fvp-history-method-help-label")
            .assertTextEquals(context.getString(R.string.fvp_experimental_label))
        compose.onNodeWithTag("fvp-history-method-help-body")
            .assertTextEquals(context.getString(R.string.fvp_estimate_notice_body))
        compose.onNodeWithTag("fvp-history-method-help-close").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-history-method-help-label").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            "el aviso experimental sigue visible al cerrar la ayuda",
            compose.onAllNodesWithText(context.getString(R.string.fvp_context_experimental))
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    // ------------------------------------------------------------------------------------------
    // Infraestructura
    // ------------------------------------------------------------------------------------------

    private fun setHistory(onOpen: (Long) -> Unit = {}) {
        compose.setContent {
            OpenJumpTheme {
                CompositionLocalProvider(LocalUnitSystem provides guidedEvidenceProfile()) {
                    FvpHistoryScreen(
                        athleteId = athleteId,
                        onBack = {},
                        onOpen = onOpen,
                        athleteName = "Historial QA",
                        viewModel = viewModel,
                    )
                }
            }
        }
    }

    private fun insertAthlete(name: String): Long = runBlocking {
        database.athleteDao().insert(
            AthleteEntity(displayName = name, weightKg = 80.0, createdAt = 1L, updatedAt = 1L),
        )
    }

    private fun saveDraft(
        owner: Long,
        heights: List<Double>,
        perConditionPushOff: Boolean = false,
    ): Long = runBlocking {
        val draft = FvpDraftSession.begin(owner, FvpProtocol.SJ).copy(
            bodyMassKg = 80.0,
            bodyMassFromProfile = true,
            pushOffMode = if (perConditionPushOff) {
                FvpPushOffUiMode.PER_CONDITION
            } else {
                FvpPushOffUiMode.COMMON
            },
            commonPushOffM = 0.40,
            pushOffLandmark = "TROCANTER",
            pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
            pushOffMeasuredAt = 1_699_500_000_000L,
            conditions = heights.mapIndexed { index, heightM ->
                FvpConditionDraft(
                    conditionKey = "${index * 20}kg",
                    addedLoadKg = index * 20.0,
                    pushOffM = 0.40,
                    attempts = listOf(
                        FvpAttemptDraft(
                            heightM = heightM,
                            method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                            sourceFps = 240.0,
                        ),
                    ),
                )
            },
        )
        repository.save(requireNotNull(draft.toPersistDraftOrNull()))
    }
}
