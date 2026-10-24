package com.openjump.app.ui.fvp

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.FvpAssessmentWithData
import com.openjump.app.data.FvpRepository
import com.openjump.app.data.FvpSnapshotStatus
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.JumpRepository
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import java.io.File
import java.text.DecimalFormatSymbols
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D3/S1/S2 runtime seam: real repository + saved-result screen, in-memory Room only.
 * Deliberately foreign snapshots are fixtures, not evidence that the app writes inconsistent data.
 * Checks source selection and immutable stored data, not physical validity or the whole V2 matrix.
 */
@RunWith(AndroidJUnit4::class)
class FvpSnapshotViewsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: JumpDatabase
    private lateinit var repository: FvpRepository
    private lateinit var viewModel: FvpViewModel
    private var athleteId = 0L
    private val mounted = mutableStateOf(false)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        FvpDraftSession.reset()
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), JumpDatabase::class.java).build()
        repository = FvpRepository(database.fvpDao())
        viewModel = FvpViewModel(repository, JumpRepository(database.jumpDao()))
        athleteId = runBlocking {
            database.athleteDao().insert(AthleteEntity(displayName = "SNAPSHOT QA SYNTHETIC", createdAt = 1, updatedAt = 1))
        }
    }

    @After
    fun tearDown() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        FvpDraftSession.reset()
        database.close()
    }

    @Test
    fun mismatchStartsOriginalAndSwitchesWholeExecutionWithoutOverwritingSnapshot() {
        val (id, before) = seedForeignSnapshot()
        assertEquals(FvpSnapshotStatus.MISMATCH, runBlocking { repository.byId(id) }!!.snapshotStatus)
        open(id)
        assertOriginalMetrics()
        compose.onNodeWithTag("fvp-view-original").assertIsSelected()
        compose.onNodeWithTag("fvp-critical-SNAPSHOT_RECALCULATED").performScrollTo().assertIsDisplayed()
        compose.showResultNoticeForTest(FvpUiWarning.SNAPSHOT_RECALCULATED)
        compose.onNodeWithTag("fvp-notice-detail-SNAPSHOT_RECALCULATED")
            .assertTextContains(context.getString(R.string.fvp_warning_snapshot_recalculated), substring = true)
        compose.returnToResultSummaryForTest()
        captureAt("9001.0 N", "mismatch-original-metrics")
        // El resultado es navegable por secciones: el motivo de ORIGINAL sin puntos vive en
        // Graficas y la ausencia de la tabla en Datos. Se recorren las DOS secciones (no basta
        // con que el nodo no este compuesto fuera de pantalla) y se vuelve a Resumen.
        openSection(FvpResultSection.CHARTS)
        captureTag("fvp-points-not-in-snapshot", "mismatch-original-details")
        assertOriginalHasNoCurrentPoints()

        // El conmutador de ejecucion es GLOBAL (fuera del pager): no necesita scroll de pagina.
        compose.onNodeWithTag("fvp-view-recalculation").performClick()
        compose.onNodeWithTag("fvp-view-recalculation").assertIsSelected()
        assertRecalculatedMetrics()
        compose.onNodeWithTag("fvp-relative-values").assertExists()
        captureAt("2887.5 N", "mismatch-recalculated-metrics")
        openSection(FvpResultSection.CHARTS)
        compose.onNodeWithTag("fvp-points-not-in-snapshot").assertDoesNotExist()
        compose.onNodeWithTag("fvp-chart-canvas-fv").assertExists()
        compose.onNodeWithTag("fvp-chart-canvas-pv").assertExists()
        compose.onNodeWithTag("fvp-chart-canvas-fv").performScrollTo()
        capture("mismatch-recalculated-chart")

        compose.onNodeWithTag("fvp-view-original").performClick()
        assertOriginalMetrics()
        assertOriginalHasNoCurrentPoints()
        assertStoredUnchanged(id, before)
    }

    @Test
    fun staleAlgorithmLabelsEachExecutionAndKeepsOriginalPointsUnavailable() {
        checkStale("algorithmVersion = 'qa-legacy-algorithm'", FvpSnapshotStatus.STALE, "stale-algorithm")
    }

    @Test
    fun staleMethodShowsStoredVerdictHintOnlyOnOriginal() {
        checkStale("methodVersion = 'qa-legacy-method'", FvpSnapshotStatus.STALE_METHOD, "stale-method")
    }

    @Test
    fun unavailableRecalculationKeepsNullSnapshotFieldsAndUnknownWarningsVisible() {
        val (id, before) = seedForeignSnapshot("protocolId = 'QA_FUTURE_PROTOCOL', warningsCsv = 'QA_FUTURE_WARNING'")
        val stored = requireNotNull(runBlocking { repository.byId(id) })
        assertEquals(FvpSnapshotStatus.RECALCULATION_UNAVAILABLE, stored.snapshotStatus)
        assertNull(stored.result)
        open(id)
        assertOriginalMetrics()
        // La explicacion completa del ORIGINAL se consulta en la accion explicita del Resumen
        // (modal): se abre, se comprueban las definiciones y se cierra sin tocar ningun dato.
        compose.onNodeWithTag("fvp-metrics-help").performScrollTo().performClick()
        listOf(
            R.string.fvp_gloss_f0, R.string.fvp_gloss_v0, R.string.fvp_gloss_pmax,
            R.string.fvp_gloss_r2, R.string.fvp_result_fv_intro,
        ).forEach { text ->
            compose.onNodeWithText(context.getString(text)).assertExists()
        }
        compose.onNodeWithTag("fvp-metric-help-close").performClick()
        assertOriginalMetrics()
        compose.onNodeWithTag("fvp-view-toggle").assertDoesNotExist()
        compose.onNodeWithTag("fvp-duplicate-notice").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.fvp_warning_snapshot_unavailable)).assertExists()
        compose.onNodeWithText(context.getString(R.string.fvp_warning_unclassified)).performScrollTo().assertExists()
        compose.onNodeWithText("QA_FUTURE_WARNING", substring = true).assertDoesNotExist()
        capture("unavailable-warning")
        captureAt("9001.0 N", "unavailable-original-metrics")
        assertOriginalHasNoCurrentPoints()
        assertStoredUnchanged(id, before)
    }

    private fun checkStale(change: String, status: FvpSnapshotStatus, name: String) {
        val (id, before) = seedForeignSnapshot("$change, warningsCsv = 'NARROW_VELOCITY_RANGE'")
        val stored = requireNotNull(runBlocking { repository.byId(id) })
        assertEquals(status, stored.snapshotStatus)
        assertNotNull(stored.result)
        open(id)
        assertOriginalMetrics()
        compose.onNodeWithTag("fvp-context-stale").performScrollTo().assertIsDisplayed()
        compose.showResultNoticeForTest(FvpUiWarning.NARROW_VELOCITY_RANGE)
        compose.onNodeWithTag("fvp-notice-detail-NARROW_VELOCITY_RANGE")
            .assertTextContains(context.getString(R.string.fvp_warning_narrow_range), substring = true)
        capture(name + "-original-verdict")
        val originalVersion = if (status == FvpSnapshotStatus.STALE) "qa-legacy-algorithm" else stored.result!!.algorithmVersion
        compose.onNodeWithTag("fvp-view-source").assertTextContains(originalVersion, substring = true)
        assertOriginalHasNoCurrentPoints()
        for (section in listOf(FvpResultSection.CHARTS, FvpResultSection.DATA, FvpResultSection.QUALITY)) {
            openSection(section)
            compose.onNodeWithTag("fvp-context-stale").assertIsDisplayed()
        }
        compose.onNodeWithTag("fvp-quality-points").assertTextContains(context.getString(R.string.fvp_undefined_value))
        compose.onNodeWithTag("fvp-quality-points-unavailable").assertExists()
        compose.onNodeWithTag("fvp-view-recalculation").performClick()
        compose.onNodeWithTag("fvp-context-stale").assertDoesNotExist()
        assertRecalculatedMetrics()
        // Resumen es la seccion que declara estos veredictos: se comprueba AHI, no en una pagina
        // que ni siquiera este compuesta.
        compose.openResultQualityForTest()
        compose.onNodeWithTag("fvp-notice-NARROW_VELOCITY_RANGE").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.fvp_warning_narrow_range)).assertDoesNotExist()
        compose.returnToResultSummaryForTest()
        compose.onNodeWithTag("fvp-view-source").assertTextContains(stored.result!!.algorithmVersion, substring = true)
        if (status == FvpSnapshotStatus.STALE) {
            compose.onNodeWithText("qa-legacy-algorithm", substring = true).assertDoesNotExist()
        }
        openSection(FvpResultSection.CHARTS)
        compose.onNodeWithTag("fvp-chart-canvas-fv").assertExists()
        compose.onNodeWithTag("fvp-chart-canvas-pv").assertExists()
        assertStoredUnchanged(id, before)
    }

    /** SQL is restricted to this test's in-memory DB and its one synthetic root. No disk DB or schema change. */
    private fun seedForeignSnapshot(extra: String = ""): Pair<Long, FvpAssessmentWithData> {
        val draft = FvpDraft(
            athleteId = athleteId, protocol = FvpProtocol.SJ, bodyMassKg = 80.0,
            pushOffMode = FvpPushOffUiMode.COMMON, commonPushOffM = 0.4,
            pushOffLandmark = "TROCANTER", pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH", pushOffMeasuredAt = 1_700_000_000_000L,
            conditions = listOf(0.0 to 0.4, 20.0 to 0.32, 40.0 to 0.25, 60.0 to 0.18).map { (load, height) ->
                FvpConditionDraft(conditionKey = "QA $load kg", addedLoadKg = load,
                    attempts = listOf(FvpAttemptDraft(heightM = height, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0)))
            },
        )
        val id = runBlocking { repository.save(requireNotNull(draft.toPersistDraftOrNull())) }
        assertEquals(FvpSnapshotStatus.MATCHES, runBlocking { repository.byId(id) }!!.snapshotStatus)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE fvp_assessments SET f0N=9001.0, slopeFv=-700.0, sfvNsPerM=700.0, " +
                "v0Mps=NULL, pmaxW=NULL, r2=NULL, conditionCount=7, vMinMps=0.1, vMaxMps=0.2, " +
                "fMinN=111.0, fMaxN=222.0, f0RelNPerKg=NULL, pmaxRelWPerKg=NULL" +
                (if (extra.isBlank()) "" else ", $extra") + " WHERE id=?",
            arrayOf(id),
        )
        return id to requireNotNull(runBlocking { database.fvpDao().assessmentWithData(id) })
    }

    private fun open(id: Long) {
        compose.runOnIdle { mounted.value = true }
        compose.setContent {
            if (mounted.value) OpenJumpTheme(darkTheme = false) {
                CompositionLocalProvider(LocalUnitSystem provides UnitProfile.METRIC) {
                    FvpResultScreen(assessmentId = id, onBack = {}, onExit = {}, onEdit = {}, onSaved = {}, onDeleted = {}, viewModel = viewModel)
                }
            }
        }
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("fvp-metrics")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText(context.getString(R.string.error_invalid_measurement))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fvp-metrics").assertExists("Stored-result loading failed; expected the synthetic fixture, not the unavailable screen")
    }

    private fun assertOriginalMetrics() {
        openSection(FvpResultSection.SUMMARY)
        assertMetric("9001.0 N")
        assertMetric("700.00 N·s/m")
        // NULLs must remain undefined, never silently filled from the current engine.
        compose.onAllNodes(hasText(context.getString(R.string.fvp_undefined_value)) and hasAnyAncestor(hasTestTag("fvp-metrics"))).assertCountEquals(3)
        compose.onNodeWithTag("fvp-relative-values").assertDoesNotExist()
        compose.onNodeWithTag("fvp-force-range").assertTextContains("F " + decimal("111.0–222.0 N"), substring = true)
        compose.onNodeWithTag("fvp-force-range").assertTextContains(context.getString(R.string.fvp_conditions_title) + ": 7", substring = true)
        assertVelocityRange("0.10–0.20 m/s")
    }

    private fun assertRecalculatedMetrics() {
        openSection(FvpResultSection.SUMMARY)
        // Independent fixed oracle: spec03, g=9.80665, mean force m*g*(1+h/.4), v=sqrt(g*h/2), OLS.
        for (value in listOf("2887.5 N", "917.68 N·s/m", "3.15 m/s", "2271 W", "0.954")) assertMetric(value)
        compose.onAllNodes(hasText(context.getString(R.string.fvp_undefined_value)) and hasAnyAncestor(hasTestTag("fvp-metrics"))).assertCountEquals(0)
        compose.onAllNodes(hasText(decimal("9001.0 N")) and hasAnyAncestor(hasTestTag("fvp-metrics"))).assertCountEquals(0)
        compose.onNodeWithTag("fvp-force-range").assertTextContains(context.getString(R.string.fvp_conditions_title) + ": 4", substring = true)
        compose.onNodeWithTag("fvp-force-range").assertTextContains("F " + decimal("1569.1–1990.7 N"), substring = true)
        assertVelocityRange("0.94–1.40 m/s")
        // La tabla de puntos vive en Datos: se comprueba alli (y se vuelve a Resumen, que es donde
        // siguen las metricas y los avisos de la ejecucion mostrada).
        openSection(FvpResultSection.DATA)
        compose.onNodeWithTag("fvp-points-table-not-in-snapshot").assertDoesNotExist()
        for (load in listOf(0, 20, 40, 60)) compose.onNodeWithText("QA $load.0 kg").assertExists()
        openSection(FvpResultSection.SUMMARY)
    }

    private fun assertVelocityRange(value: String) = compose.onAllNodes(
        hasText(context.getString(R.string.fvp_measured_range) + ": " + decimal(value)) and hasAnyAncestor(hasTestTag("fvp-metrics")),
    ).assertCountEquals(2)

    private fun assertMetric(value: String) = compose.onAllNodes(hasText(decimal(value)) and hasAnyAncestor(hasTestTag("fvp-metrics"))).assertCountEquals(1)
    private fun assertOriginalHasNoCurrentPoints() {
        // Se navega a cada seccion implicada: en el ORIGINAL persistido la explicacion esta en
        // Graficas y la tabla vacia en Datos; comprobarlo desde Resumen solo probaria que las
        // paginas fuera de pantalla no estan compuestas.
        openSection(FvpResultSection.CHARTS)
        compose.onNodeWithTag("fvp-points-not-in-snapshot").assertExists()
        compose.onNodeWithTag("fvp-chart-canvas-fv").assertDoesNotExist()
        compose.onNodeWithTag("fvp-chart-canvas-pv").assertDoesNotExist()
        openSection(FvpResultSection.DATA)
        compose.onNodeWithTag("fvp-points-table-not-in-snapshot").assertExists()
        openSection(FvpResultSection.SUMMARY)
    }

    /** Cambia de seccion con la pestana: el mismo indice de pager que mueve el gesto. */
    private fun openSection(section: FvpResultSection) {
        compose.onNodeWithTag(section.tabTag).performClick()
        compose.waitForIdle()
    }
    private fun assertStoredUnchanged(id: Long, before: FvpAssessmentWithData) = assertEquals(before, runBlocking { database.fvpDao().assessmentWithData(id) })
    private fun decimal(value: String) = value.replace('.', DecimalFormatSymbols(context.resources.configuration.locales[0]).decimalSeparator)
    // Captures are opt-in evidence, not a prerequisite for the default correctness run.
    private val captureEvidence get() = InstrumentationRegistry.getArguments().getString("fvpCaptureEvidence") == "true"
    private fun captureAt(text: String, name: String) {
        if (!captureEvidence) return
        compose.onNodeWithText(decimal(text)).performScrollTo()
        capture(name)
    }
    private fun captureTag(tag: String, name: String) {
        if (!captureEvidence) return
        compose.onNodeWithTag(tag).performScrollTo()
        capture(name)
    }
    private fun capture(name: String) {
        if (!captureEvidence) return
        compose.waitForIdle()
        val directory = File(context.cacheDir, "fvp_snapshot_ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
