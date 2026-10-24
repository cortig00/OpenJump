package com.openjump.app.ui.fvp

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.FvpRepository
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.JumpRepository
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B4b runtime seam: repository real + pantalla del resultado guardado, solo Room en memoria.
 *
 * Comprueba la SUPERFICIE de la procedencia: una fila guardada con procedencia desconocida declara
 * sus avisos (masa, carga, mapeo temporal y contexto de adquisicion) en la pantalla del resultado, y
 * una fila con toda la procedencia declarada y verificada NO muestra esos avisos. La procedencia se
 * declara; `UNKNOWN` no se promociona en ningun caso.
 *
 * No es evidencia fisica ni valida el motor: es la costura UI de los avisos B4b.
 */
@RunWith(AndroidJUnit4::class)
class FvpProvenanceSurfaceInstrumentedTest {
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
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JumpDatabase::class.java,
        ).build()
        repository = FvpRepository(database.fvpDao())
        viewModel = FvpViewModel(repository, JumpRepository(database.jumpDao()))
        athleteId = runBlocking {
            database.athleteDao().insert(
                AthleteEntity(displayName = "PROVENANCE QA SYNTHETIC", createdAt = 1, updatedAt = 1),
            )
        }
    }

    @After
    fun tearDown() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        FvpDraftSession.reset()
        database.close()
    }

    /**
     * Fila guardada con TODA la procedencia desconocida: la pantalla declara masa sin declarar,
     * carga sin procedencia, mapeo temporal no verificado y contexto sin declarar. Ninguno de esos
     * avisos se puede confundir con una promocion: son el peor caso explicito.
     */
    @Test
    fun unknownStoredProvenanceIsDeclaredVisibleOnTheResultScreen() {
        val id = seedStoredSeries(
            bodyMassMeasurement = "UNKNOWN",
            addedLoadSource = "UNKNOWN",
            timeMapping = "UNKNOWN",
            acquisitionContextKind = "UNKNOWN",
        )
        open(id)

        compose.showResultNoticeForTest(FvpUiWarning.BODY_MASS_UNKNOWN)
        compose.onNodeWithTag("fvp-notice-detail-BODY_MASS_UNKNOWN")
            .assertTextContains(context.getString(R.string.fvp_warning_body_mass_unknown), substring = true)
        compose.showResultNoticeForTest(FvpUiWarning.ADDED_LOAD_UNKNOWN)
        compose.onNodeWithTag("fvp-notice-detail-ADDED_LOAD_UNKNOWN")
            .assertTextContains(context.getString(R.string.fvp_warning_added_load_unknown), substring = true)
        compose.showResultNoticeForTest(FvpUiWarning.TIME_MAPPING_UNVERIFIED)
        compose.onNodeWithTag("fvp-notice-detail-TIME_MAPPING_UNVERIFIED")
            .assertTextContains(context.getString(R.string.fvp_warning_time_mapping_unverified), substring = true)
        compose.showResultNoticeForTest(FvpUiWarning.ACQUISITION_CONTEXT_UNKNOWN)
        compose.onNodeWithTag("fvp-notice-detail-ACQUISITION_CONTEXT_UNKNOWN")
            .assertTextContains(context.getString(R.string.fvp_warning_acquisition_context_unknown), substring = true)
        // Full UNKNOWN explanations stay accessible in grouped Quality details, not repeated banners.
        captureScrollToText(R.string.fvp_warning_body_mass_unknown, "result-provenance-unknown-warnings-top")
        captureScrollToText(R.string.fvp_warning_acquisition_context_unknown, "result-provenance-unknown-warnings-bottom")
        for ((section, tag) in listOf(
            FvpResultSection.CHARTS to "fvp-charts-warnings",
            FvpResultSection.DATA to "fvp-data-warnings",
        )) {
            compose.onNodeWithTag(section.tabTag).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(tag).assertDoesNotExist()
            compose.onNodeWithTag("fvp-context-quality").assertIsDisplayed()
            for (warning in listOf(FvpUiWarning.BODY_MASS_UNKNOWN, FvpUiWarning.ADDED_LOAD_UNKNOWN,
                FvpUiWarning.TIME_MAPPING_UNVERIFIED, FvpUiWarning.ACQUISITION_CONTEXT_UNKNOWN)) {
                compose.showResultNoticeForTest(warning)
                compose.onNodeWithTag("fvp-notice-detail-${warning.name}")
                    .assertTextContains(context.getString(warning.messageRes()), substring = true)
            }
        }
    }

    /**
     * Fila con la procedencia DECLARADA pero no pesada (`DECLARED`/`NOMINAL`) y el resto verificado:
     * se declaran los avisos que corresponden a lo que la fila es, y NO se exagera con los del peor
     * caso (`UNKNOWN`) ni con el compuesto/retrospectivo.
     */
    @Test
    fun declaredButNotWeighedProvenanceDeclaresOnlyItsOwnWarnings() {
        val id = seedStoredSeries(
            bodyMassMeasurement = "DECLARED",
            addedLoadSource = "NOMINAL",
            timeMapping = "IDENTITY_VERIFIED",
            acquisitionContextKind = "SAME_ACQUISITION",
        )
        open(id)

        compose.showResultNoticeForTest(FvpUiWarning.BODY_MASS_DECLARED)
        compose.onNodeWithTag("fvp-notice-detail-BODY_MASS_DECLARED")
            .assertTextContains(context.getString(R.string.fvp_warning_body_mass_declared), substring = true)
        compose.showResultNoticeForTest(FvpUiWarning.ADDED_LOAD_NOMINAL)
        compose.onNodeWithTag("fvp-notice-detail-ADDED_LOAD_NOMINAL")
            .assertTextContains(context.getString(R.string.fvp_warning_added_load_nominal), substring = true)
        assertWarningAbsent(R.string.fvp_warning_body_mass_unknown)
        assertWarningAbsent(R.string.fvp_warning_added_load_unknown)
        assertWarningAbsent(R.string.fvp_warning_time_mapping_unverified)
        assertWarningAbsent(R.string.fvp_warning_acquisition_context_unknown)
        assertWarningAbsent(R.string.fvp_warning_acquisition_context_composite)
        // Evidencia opt-in del caso DECLARED/NOMINAL: solo los avisos que le corresponden.
        captureScrollToText(R.string.fvp_warning_added_load_nominal, "result-provenance-declared-nominal")
    }

    /**
     * Fila con TODA la procedencia verificada (masa pesada, carga pesada, mapeo temporal verificado
     * con su evidencia y misma adquisicion con clave): NO se muestra ningun aviso de procedencia.
     */
    @Test
    fun verifiedStoredProvenanceShowsNoProvenanceWarnings() {
        val id = seedStoredSeries(
            bodyMassMeasurement = "WEIGHED",
            addedLoadSource = "WEIGHED",
            timeMapping = "IDENTITY_VERIFIED",
            acquisitionContextKind = "SAME_ACQUISITION",
        )
        open(id)

        assertWarningAbsent(R.string.fvp_warning_body_mass_declared)
        assertWarningAbsent(R.string.fvp_warning_body_mass_unknown)
        assertWarningAbsent(R.string.fvp_warning_added_load_nominal)
        assertWarningAbsent(R.string.fvp_warning_added_load_unknown)
        assertWarningAbsent(R.string.fvp_warning_time_mapping_unverified)
        assertWarningAbsent(R.string.fvp_warning_acquisition_context_unknown)
        assertWarningAbsent(R.string.fvp_warning_acquisition_context_composite)
        // Check actual detail rows too: collapsed prose is not evidence of an absent flag.
        compose.openResultQualityForTest()
        listOf(FvpUiWarning.BODY_MASS_DECLARED, FvpUiWarning.BODY_MASS_UNKNOWN,
            FvpUiWarning.ADDED_LOAD_NOMINAL, FvpUiWarning.ADDED_LOAD_UNKNOWN,
            FvpUiWarning.TIME_MAPPING_UNVERIFIED, FvpUiWarning.ACQUISITION_CONTEXT_UNKNOWN,
            FvpUiWarning.ACQUISITION_CONTEXT_COMPOSITE).forEach {
            compose.onNodeWithTag("fvp-notice-${it.name}").assertDoesNotExist()
        }
        compose.returnToResultSummaryForTest()
        captureScrollToTag("fvp-metrics", "result-provenance-verified-no-warnings")
    }

    /**
     * Siembra por el camino REAL (repositorio) una serie sintetica de 4 condiciones con la
     * procedencia pedida. `IDENTITY_VERIFIED` lleva su clave de evidencia (el repositorio la exige)
     * y `SAME_ACQUISITION` su clave de contexto: la procedencia declarada es coherente, no inventada.
     */
    private fun seedStoredSeries(
        bodyMassMeasurement: String,
        addedLoadSource: String,
        timeMapping: String,
        acquisitionContextKind: String,
    ): Long {
        val loads = listOf(0.0, 20.0, 40.0, 60.0)
        val heights = listOf(0.40, 0.32, 0.25, 0.18)
        val draft = FvpDraft(
            athleteId = athleteId,
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            bodyMassFromProfile = false,
            pushOffMode = FvpPushOffUiMode.COMMON,
            commonPushOffM = 0.4,
            pushOffLandmark = "TROCANTER",
            pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
            pushOffMeasuredAt = 1_700_000_000_000L,
            bodyMassMeasurement = bodyMassMeasurement,
            acquisitionContextKind = acquisitionContextKind,
            acquisitionContextKey = if (acquisitionContextKind == "SAME_ACQUISITION") "ctx-qa" else null,
            conditions = loads.mapIndexed { index, load ->
                FvpConditionDraft(
                    conditionKey = "QA $load kg",
                    addedLoadKg = load,
                    addedLoadSource = addedLoadSource,
                    attempts = listOf(
                        FvpAttemptDraft(
                            heightM = heights[index],
                            method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                            sourceFps = 240.0,
                            timeMapping = timeMapping,
                            timeMappingEvidenceKey = if (timeMapping == "IDENTITY_VERIFIED") {
                                "qa-evidence-1"
                            } else {
                                null
                            },
                        ),
                    ),
                )
            },
        )
        return runBlocking { repository.save(requireNotNull(draft.toPersistDraftOrNull())) }
    }

    /** Monta la pantalla del resultado del guardado y espera a sus metricas (sin `Thread.sleep`). */
    private fun open(id: Long) {
        compose.runOnIdle { mounted.value = true }
        compose.setContent {
            if (mounted.value) {
                OpenJumpTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalUnitSystem provides UnitProfile.METRIC) {
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
                }
            }
        }
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("fvp-metrics")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText(context.getString(R.string.error_invalid_measurement)))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fvp-metrics").assertExists(
            "La pantalla del guardado no cargo; se esperaba la fila sintetica, no la pantalla de error",
        )
    }

    /** El texto localizado de ese aviso NO esta en la pantalla (ni como promocion ni por error). */
    private fun assertWarningAbsent(key: Int) {
        compose.onNodeWithText(context.getString(key)).assertDoesNotExist()
        FvpUiWarning.entries.firstOrNull { it.messageRes() == key }?.let {
            compose.onNodeWithTag("fvp-notice-${it.name}").assertDoesNotExist()
        }
    }

    // Capturas opt-in: evidencia visual, NUNCA un requisito de la pasada de correccion por defecto.
    private val captureEvidence
        get() = InstrumentationRegistry.getArguments().getString("fvpCaptureEvidence") == "true"

    private fun captureScrollToTag(tag: String, name: String) {
        if (!captureEvidence) return
        compose.onNodeWithTag(tag).performScrollTo()
        capture(name)
    }

    private fun captureScrollToText(key: Int, name: String) {
        if (!captureEvidence) return
        compose.onNodeWithText(context.getString(key)).performScrollTo()
        capture(name)
    }

    private fun capture(name: String) {
        if (!captureEvidence) return
        compose.waitForIdle()
        val directory = File(context.cacheDir, "fvp_provenance_ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
