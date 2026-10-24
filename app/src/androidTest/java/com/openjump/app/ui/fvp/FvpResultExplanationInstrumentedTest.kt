package com.openjump.app.ui.fvp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
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
 * Explicacion llana del Resumen F–V–P sobre la PANTALLA REAL ([FvpResultScreen]), con el modelo
 * calculado por el motor actual (borrador vivo).
 *
 * Comprueba el slice B del plan `.pi/plans/fvp-guidance-ux.md`:
 * 1. el Resumen introduce fuerza/velocidad como medias ESTIMADAS por carga y distingue puntos
 *    estimados de linea extrapolada;
 * 2. las glosas F0/V0/Pmax/R² dicen lo que NO son (intercepto extrapolado, maximo del modelo,
 *    ajuste que no valida), sin diagnosticos ni prescripciones;
 * 3. la lectura de graficas y el "rango" ya no se presentan como mediciones directas;
 * 4. se conservan las cuatro pestanas, los avisos cientificos existentes y el footer de Guardar.
 *
 * Las capturas son OPT-IN (`-e fvpGuidanceEvidence true` o `-e fvpCaptureEvidence true`) y se
 * escriben en `cacheDir/fvp_result_explanation`; sin flags no se crea ningun archivo.
 */
@RunWith(AndroidJUnit4::class)
class FvpResultExplanationInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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

    @Test
    fun summaryExplainsTheModelInPlainLanguageAndKeepsScientificWarnings() {
        FvpDraftSession.adopt(diagnosticDraft())
        setResultContent()

        // Las CUATRO secciones y sus pestanas se conservan; Resumen sigue siendo la inicial.
        FvpResultSection.entries.forEach { section ->
            compose.onNodeWithTag(section.tabTag).assertExists()
        }
        compose.onNodeWithTag(FvpResultSection.SUMMARY.tabTag).assertIsSelected()
        compose.onNodeWithTag(FvpResultSection.SUMMARY.panelTag).assertIsDisplayed()

        // El borrador VIVO conserva su progreso compacto (no se toca el stepper).
        compose.onNodeWithTag("fvp-flow-progress").assertExists()

        captureGuidedScreen(compose, "fvp_guided_results", "live-summary-top")
        // Glosas CRITICAS breves junto a la cifra: F0/V0 extrapolados, Pmax del modelo, R² que no
        // valida. El parrafo largo anterior ya no ocupa el Resumen.
        compose.onNodeWithTag("fvp-gloss-f0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fvp-gloss-v0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fvp-gloss-pmax").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fvp-gloss-r2").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.fvp_result_fv_intro)).assertCountEquals(0)
        compose.onAllNodesWithText(context.getString(R.string.fvp_gloss_f0)).assertCountEquals(0)

        // Las definiciones COMPLETAS (las glosas anteriores incluidas) siguen bajo una accion de
        // 48 dp, no se han eliminado.
        compose.onNodeWithTag("fvp-metrics-help").performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-metric-help-f0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fvp-metric-help-f0")
            .assertTextEquals(context.getString(R.string.fvp_gloss_f0))
        compose.onNodeWithTag("fvp-metric-help-sfv")
            .assertTextEquals(context.getString(R.string.fvp_gloss_sfv))
        compose.onNodeWithTag("fvp-metric-help-r2")
            .assertTextEquals(context.getString(R.string.fvp_gloss_r2))
        compose.onNodeWithTag("fvp-metric-help-intro")
            .assertTextEquals(context.getString(R.string.fvp_result_fv_intro))
        compose.onNodeWithTag("fvp-metric-help-close").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-metric-help-f0").fetchSemanticsNodes().isEmpty()
        }

        // Complete explanations remain available in Quality; brief critical context stays global.
        compose.showResultMethodForTest()
        compose.onNodeWithText(context.getString(R.string.fvp_estimate_notice_title)).assertIsDisplayed()
        compose.showResultNoticeForTest(FvpUiWarning.DIAGNOSTIC_ONLY)
        compose.onNodeWithTag("fvp-notice-detail-DIAGNOSTIC_ONLY")
            .assertTextEquals(context.getString(R.string.fvp_warning_diagnostic_only))
        compose.returnToResultSummaryForTest()

        assertMetricUnitOnOneLine("fvp-metric-value-sfv", "N·s/m")
        compose.onNodeWithTag("fvp-metric-value-sfv").performScrollTo()
        captureGuidedScreen(compose, "fvp_guided_results", "live-metrics-detail")
        // Footer de Guardar intacto y con la misma habilitacion (serie calculable).
        compose.onNodeWithTag("fvp-result-footer").assertIsDisplayed()
        compose.onNodeWithTag("fvp-save").assertIsDisplayed()
        capture("summary-explanation")
    }

    /**
     * Resultado GUARDADO: mismas cuatro pestanas y mismos avisos, SIN stepper de asistente en
     * curso, con la glosa critica breve junto a la cifra y las definiciones completas accesibles.
     * No se toca el guardado en vivo ni la fuente ORIGINAL/RECALCULACION.
     */
    @Test
    fun savedResultKeepsTheFourSectionsButNotTheWizardProgress() {
        val id = runBlocking { repository.save(requireNotNull(diagnosticDraft().toPersistDraftOrNull())) }
        compose.setContent {
            OpenJumpTheme {
                CompositionLocalProvider(LocalUnitSystem provides guidedEvidenceProfile()) {
                    FvpResultScreen(
                        assessmentId = id,
                        onBack = {},
                        onExit = {},
                        onEdit = {},
                        onSaved = {},
                        onDeleted = {},
                        athleteNameById = mapOf(athleteId to ATHLETE_NAME),
                        viewModel = viewModel,
                    )
                }
            }
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-series-header").fetchSemanticsNodes().isNotEmpty()
        }
        FvpResultSection.entries.forEach { section ->
            compose.onNodeWithTag(section.tabTag).assertExists()
        }
        compose.onNodeWithTag(FvpResultSection.SUMMARY.tabTag).assertIsSelected()
        // Guardado != asistente en curso: sin stepper, pero con el contexto global intacto.
        compose.onAllNodesWithTag("fvp-flow-progress").assertCountEquals(0)
        compose.onNodeWithTag("fvp-context-quality").assertExists()
        compose.onNodeWithTag("fvp-context-experimental")
            .assertTextEquals(context.getString(R.string.fvp_context_experimental))

        captureGuidedScreen(compose, "fvp_guided_results", "saved-summary-top")
        // Glosa breve junto a la cifra y definicion completa bajo demanda.
        compose.onNodeWithTag("fvp-gloss-r2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fvp-metrics-help").performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-metric-help-r2").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fvp-metric-help-r2")
            .assertTextEquals(context.getString(R.string.fvp_gloss_r2))
        compose.onNodeWithTag("fvp-metric-help-close").performClick()
    }

    @Test
    fun chartsAndRangeUseEstimatedWording() {
        FvpDraftSession.adopt(diagnosticDraft())
        setResultContent()

        // El rotulo del rango ya no promete medicion directa: el valor nuevo es el recurso y se
        // pinta junto a F0/V0 (dos veces), alcanzable por scroll.
        compose.onAllNodesWithText(context.getString(R.string.fvp_measured_range), substring = true)
            .fetchSemanticsNodes()
            .let { nodes ->
                assertTrue("el rango de velocidad estimada debe aparecer junto a F0/V0", nodes.isNotEmpty())
            }
        // La lectura de la grafica F–V declara estimacion/extrapolacion, no medicion.
        compose.onNodeWithTag(FvpResultSection.CHARTS.tabTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FvpResultSection.CHARTS.panelTag).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.fvp_chart_read_fv))
            .performScrollTo()
            .assertIsDisplayed()
        val legends = compose.onAllNodesWithText(context.getString(R.string.fvp_chart_measured))
        assertTrue("ambas graficas deben conservar su leyenda de estimacion", legends.fetchSemanticsNodes().size == 2)
        for (index in 0..1) legends[index].performScrollTo().assertIsDisplayed()
        capture("chart-reading")
    }

    // ------------------------------------------------------------------------------------------
    // Infraestructura
    // ------------------------------------------------------------------------------------------

    /**
     * Serie SJ fisica de DOS cargas: calculable, pero con el aviso existente de serie diagnostica
     * (2 condiciones) para comprobar que los avisos cientificos siguen alcanzables.
     */
    private fun diagnosticDraft(): FvpDraft = FvpDraftSession.begin(athleteId, FvpProtocol.SJ).copy(
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
                attempts = listOf(FvpAttemptDraft(heightM = 0.35, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0)),
            ),
            FvpConditionDraft(
                conditionKey = "20kg",
                addedLoadKg = 20.0,
                attempts = listOf(FvpAttemptDraft(heightM = 0.30, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0)),
            ),
        ),
    )

    private fun setResultContent() {
        compose.setContent {
            OpenJumpTheme {
                val profile = when (InstrumentationRegistry.getArguments().getString("fvpUnitProfile")) {
                    "us" -> UnitProfile.UNITED_STATES
                    "custom" -> UnitProfile.METRIC.copy(shortLength = ShortLengthUnit.INCH)
                    else -> UnitProfile.METRIC
                }
                CompositionLocalProvider(LocalUnitSystem provides profile) {
                    FvpResultScreen(
                        onBack = {},
                        onExit = {},
                        onEdit = {},
                        onSaved = {},
                        onDeleted = {},
                        athleteName = ATHLETE_NAME,
                        viewModel = viewModel,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** Capturas OPT-IN: evidencia visual, nunca un requisito de la pasada por defecto. */
    private fun assertMetricUnitOnOneLine(tag: String, unit: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
        ) { getLayout ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            check(getLayout(layouts))
            val layout = layouts.single()
            val text = layout.layoutInput.text.text
            val start = text.lastIndexOf(unit)
            org.junit.Assert.assertTrue("unidad SI presente", start >= 0)
            org.junit.Assert.assertEquals("no partir una unidad SI",
                layout.getLineForOffset(start), layout.getLineForOffset(start + unit.length - 1))
        }
    }

    private fun capture(name: String) {
        val args = InstrumentationRegistry.getArguments()
        val enabled = args.getString("fvpGuidanceEvidence") == "true" ||
            args.getString("fvpCaptureEvidence") == "true"
        if (!enabled) return
        compose.waitForIdle()
        val directory = File(context.cacheDir, "fvp_result_explanation").apply { mkdirs() }
        val file = File(directory, "$name.png")
        FileOutputStream(file).use { out ->
            val bitmap: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        assertTrue("la captura opt-in debe existir: ${file.name}", file.length() > 0)
    }

    private companion object {
        const val ATHLETE_NAME = "Explanation QA"
    }
}
