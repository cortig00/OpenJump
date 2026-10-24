package com.openjump.app.ui.fvp

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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
import com.openjump.app.settings.ShortLengthUnit
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Secciones laterales del resultado F–V–P sobre Room EN MEMORIA y la PANTALLA REAL
 * ([FvpResultScreen]): no una replica del layout.
 *
 * Comprueba el comportamiento observable que el plan `fvp-results-sections.md` (slice B) exige:
 * pestanas y gesto mueven el MISMO indice, cada seccion conserva su propio scroll, Guardar esta
 * disponible desde cualquier seccion sin que deslizar dispare operaciones, las notas sobreviven al
 * cambio de pestana, una identidad de evaluacion distinta vuelve a Resumen y la accion secundaria
 * (editar/descartar) sigue en el Resumen con sus guardas.
 *
 * Las capturas de las cuatro paginas son OPT-IN (`-e fvpCaptureEvidence true`) y se guardan en
 * `cacheDir/fvp_sections_ui`; sin ese flag no se escribe ningun archivo. La matriz de claro/oscuro y
 * 100/150 % la produce el padre sobre el AVD con esas capturas.
 */
@RunWith(AndroidJUnit4::class)
class FvpResultSectionsInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: JumpDatabase
    private lateinit var repository: FvpRepository
    private lateinit var viewModel: FvpViewModel
    private val restoration by lazy { StateRestorationTester(compose) }
    private var athleteId = 0L
    private val context by lazy {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val language = InstrumentationRegistry.getArguments().getString("fvpUiLocale") ?: "en"
        base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        })
    }

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
                AthleteEntity(displayName = "SECTIONS QA SYNTHETIC", createdAt = 1, updatedAt = 1),
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
    fun tabsAndSwipeDriveTheSameSectionIndexAndDoNotTriggerOperations() {
        val saved = AtomicReference<Long?>(null)
        FvpDraftSession.adopt(physicalDraft())
        setSectionContent {
            FvpResultScreen(
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = { id -> saved.set(id) },
                onDeleted = {},
                viewModel = viewModel,
            )
        }

        // Estado inicial: Resumen, sin tocar nada.
        compose.onNodeWithTag(FvpResultSection.SUMMARY.tabTag).assertIsSelected()
        compose.onNodeWithTag(FvpResultSection.SUMMARY.panelTag).assertIsDisplayed()

        // La pestana mueve el pager.
        compose.onNodeWithTag(FvpResultSection.CHARTS.tabTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FvpResultSection.CHARTS.tabTag).assertIsSelected()
        compose.onNodeWithTag(FvpResultSection.CHARTS.panelTag).assertIsDisplayed()
        compose.onNodeWithTag(FvpResultSection.SUMMARY.panelTag).assertDoesNotExist()

        // El gesto mueve el MISMO indice: una sola unidad de estado, no dos que discrepen.
        compose.onNodeWithTag("fvp-sections-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag(FvpResultSection.DATA.tabTag).assertIsSelected()
        compose.onNodeWithTag(FvpResultSection.DATA.panelTag).assertIsDisplayed()

        // Los extremos no envuelven: deslizar a la derecha desde Resumen sigue en Resumen.
        compose.onNodeWithTag(FvpResultSection.SUMMARY.tabTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-sections-pager").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag(FvpResultSection.SUMMARY.tabTag).assertIsSelected()
        compose.onNodeWithTag(FvpResultSection.SUMMARY.panelTag).assertIsDisplayed()

        // Ningun cambio de seccion ni gesto guarda, duplica ni descarta por si solo.
        compose.waitForIdle()
        assertNull("deslizar no puede guardar la serie", saved.get())
        assertTrue(
            "navegar no puede crear una fila guardada",
            runBlocking { database.fvpDao().recent().first() }.isEmpty(),
        )
        assertNotNull("la serie viva sigue en la sesion", FvpDraftSession.draft.value)
    }

    @Test
    fun eachSectionKeepsItsOwnScrollWhileTheOthersAreVisited() {
        FvpDraftSession.adopt(physicalDraft())
        setSectionContent {
            FvpResultScreen(
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = {},
                onDeleted = {},
                viewModel = viewModel,
            )
        }
        // Scroll a genuine Summary destination; temporal availability is now global.
        compose.onNodeWithTag("fvp-delete").performScrollTo().assertIsDisplayed()
        // Datos se abre en su propio inicio: la tabla de puntos esta a la vista sin heredar el
        // desplazamiento del Resumen (las notas viven al final de esa pagina).
        compose.onNodeWithTag(FvpResultSection.DATA.tabTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FvpResultSection.DATA.panelTag).assertIsDisplayed()
        compose.onNodeWithTag("fvp-notes").performScrollTo().assertIsDisplayed()
        // Summary retains its own bottom position after visiting Data.
        compose.onNodeWithTag(FvpResultSection.SUMMARY.tabTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-delete").assertIsDisplayed()
    }

    @Test
    fun interpretationOpensGroupedQualityWithoutSavingAndChartsStartWithTheChart() {
        FvpDraftSession.adopt(physicalDraft())
        setSectionContent {
            FvpResultScreen(onBack = {}, onExit = {}, onEdit = {}, onSaved = {}, onDeleted = {}, viewModel = viewModel)
        }
        compose.onNodeWithTag("fvp-open-quality").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FvpResultSection.QUALITY.tabTag).assertIsSelected()
        compose.onNodeWithTag("fvp-quality-detail").assertExists()
        assertTrue("looking at explanations never saves", runBlocking { database.fvpDao().recent().first() }.isEmpty())
        compose.onNodeWithTag(FvpResultSection.CHARTS.tabTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-charts-warnings").assertDoesNotExist()
        compose.onNodeWithTag("fvp-chart-canvas-fv").assertIsDisplayed()
        compose.onNodeWithTag("fvp-context-quality").assertIsDisplayed()
        compose.onNodeWithTag("fvp-sensitivity").assertIsDisplayed()
    }

    @Test
    fun dataStartsWithItsTableRatherThanASecondWarningWall() {
        FvpDraftSession.adopt(physicalDraft())
        setSectionContent {
            FvpResultScreen(onBack = {}, onExit = {}, onEdit = {}, onSaved = {}, onDeleted = {}, viewModel = viewModel)
        }
        compose.onNodeWithTag(FvpResultSection.DATA.tabTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-data-warnings").assertDoesNotExist()
        compose.onNodeWithTag(FvpResultSection.DATA.panelTag).assertIsDisplayed()
        compose.onNodeWithTag("fvp-context-quality").assertIsDisplayed()
        assertTrue("changing section never saves", runBlocking { database.fvpDao().recent().first() }.isEmpty())
    }

    @Test
    fun saveIsAvailableFromAnySectionAndSwipeBetweenSectionsDoesNotSave() {
        val saved = AtomicReference<Long?>(null)
        FvpDraftSession.adopt(physicalDraft())
        setSectionContent {
            FvpResultScreen(
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = { id -> saved.set(id) },
                onDeleted = {},
                viewModel = viewModel,
            )
        }
        // Recorrer secciones con las pestanas y con el gesto NO guarda (no hay efecto lateral).
        listOf(
            FvpResultSection.CHARTS,
            FvpResultSection.DATA,
            FvpResultSection.QUALITY,
        ).forEach { section ->
            compose.onNodeWithTag(section.tabTag).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(section.panelTag).assertIsDisplayed()
        }
        compose.waitForIdle()
        assertNull("navegar no puede guardar", saved.get())
        assertTrue(runBlocking { database.fvpDao().recent().first() }.isEmpty())

        // En Calidad (la ultima seccion) el boton Guardar sigue fijo y operativo: no hay que volver
        // al Resumen para guardar un borrador.
        compose.onNodeWithTag("fvp-result-footer").assertIsDisplayed()
        compose.onNodeWithTag("fvp-save").assertIsDisplayed().performClick()
        compose.waitUntil(30_000) { saved.get() != null }
        val id = requireNotNull(saved.get()) { "el guardado debe confirmar con su id" }
        assertNotNull("la fila guardada existe en Room", runBlocking { repository.byId(id) })
    }

    @Test
    fun notesSurviveSectionChangesAndTheDraftKeepsThem() {
        val saved = AtomicBoolean(false)
        FvpDraftSession.adopt(physicalDraft())
        setSectionContent {
            FvpResultScreen(
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = { saved.set(true) },
                onDeleted = {},
                viewModel = viewModel,
            )
        }
        compose.onNodeWithTag(FvpResultSection.DATA.tabTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-notes").performScrollTo().performTextInput("nota de sesion")
        // Cambiar de pestana y volver no pierde lo escrito: el texto es estado de la PANTALLA, no
        // del ciclo de vida de la pagina.
        compose.onNodeWithTag(FvpResultSection.QUALITY.tabTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FvpResultSection.DATA.tabTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-notes").performScrollTo().assertTextContains("nota de sesion")
        assertEquals(
            "la nota editada se conserva en el borrador vivo",
            "nota de sesion",
            FvpDraftSession.draft.value?.notes,
        )
        assertFalse("editar notas no guarda la serie", saved.get())
    }

    @Test
    fun anotherResultIdentityStartsAtSummaryAndTheSameIdentityKeepsItsSection() {
        FvpDraftSession.adopt(physicalDraft())
        setSectionContent {
            FvpResultScreen(
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = {},
                onDeleted = {},
                viewModel = viewModel,
            )
        }
        // La misma identidad conserva la seccion al visitar otra y volver (no se reinicia por
        // navegar): restauracion de seccion dentro de la MISMA evaluacion.
        compose.onNodeWithTag(FvpResultSection.QUALITY.tabTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FvpResultSection.SUMMARY.tabTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FvpResultSection.QUALITY.tabTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FvpResultSection.QUALITY.tabTag).assertIsSelected()

        // Otra evaluacion (otro `sessionKey`) NO hereda la seccion: vuelve a Resumen.
        val originalKey = FvpDraftSession.draft.value?.sessionKey
        compose.runOnUiThread { FvpDraftSession.adopt(physicalDraft()) }
        assertTrue(originalKey != FvpDraftSession.draft.value?.sessionKey)
        // collectAsState recibe el StateFlow de forma asincrona; idle de Compose por si solo no
        // espera a que se publique esa nueva identidad. El estado seleccionado debe cambiar.
        compose.waitUntil(10_000) {
            compose.onNodeWithTag(FvpResultSection.SUMMARY.tabTag).fetchSemanticsNode()
                .config[SemanticsProperties.Selected]
        }
        compose.onNodeWithTag(FvpResultSection.SUMMARY.tabTag).assertIsSelected()
        compose.onNodeWithTag(FvpResultSection.SUMMARY.panelTag).assertIsDisplayed()
    }

    @Test
    fun restoredStateKeepsDataSectionScrollAndNotes() {
        val draft = physicalDraft()
        // Doce filas hacen que el desplazamiento sea necesario incluso en el viewport grande
        // del AVD. Cuatro filas pueden caber enteras y dar una prueba vacua de restauracion.
        FvpDraftSession.adopt(draft.copy(conditions = (0..11).map { index ->
            FvpConditionDraft(
                conditionKey = "RESTORE $index",
                addedLoadKg = index * 5.0,
                attempts = listOf(FvpAttemptDraft(
                    heightM = 0.40 - index * 0.02,
                    method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                    sourceFps = 240.0,
                )),
            )
        }))
        setSectionContent {
            FvpResultScreen(
                onBack = {}, onExit = {}, onEdit = {}, onSaved = {}, onDeleted = {},
                viewModel = viewModel,
            )
        }
        compose.onNodeWithTag(FvpResultSection.DATA.tabTag).performClick()
        compose.onNodeWithTag("fvp-notes").performScrollTo().performTextInput("restored note")
        compose.activityRule.scenario.onActivity { activity ->
            activity.currentFocus?.let { focused ->
                val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(focused.windowToken, 0)
            }
        }
        compose.waitForIdle()
        val beforeOffset = compose.onNodeWithTag(FvpResultSection.DATA.panelTag)
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("la restauracion debe probar un scroll no nulo", beforeOffset > 0f)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithTag(FvpResultSection.DATA.tabTag).assertIsSelected()
        val restoredOffset = compose.onNodeWithTag(FvpResultSection.DATA.panelTag)
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals("se restauro el desplazamiento real", beforeOffset, restoredOffset, 1f)
        // Sin volver a hacer scroll: tambien se restauro la posicion visible de la pagina.
        compose.onNodeWithTag("fvp-notes").assertIsDisplayed().assertTextContains("restored note")
        assertEquals("restored note", FvpDraftSession.draft.value?.notes)
    }

    @Test
    fun liveTabsAndContextStayStationaryThroughSectionsGesturesAndQualityAction() {
        val draft = physicalDraft()
        FvpDraftSession.adopt(draft)
        setSectionContent {
            FvpResultScreen(onBack = {}, onExit = {}, onEdit = {}, onSaved = {}, onDeleted = {}, viewModel = viewModel)
        }
        exerciseStationaryNavigation()
        assertEquals("navigation does not change the draft", draft, FvpDraftSession.draft.value)
        assertTrue("navigation does not save", runBlocking { database.fvpDao().recent().first() }.isEmpty())
    }

    @Test
    fun storedTabsStayStationaryAcrossBothExecutionsWithoutChangingTheSnapshot() {
        val id = runBlocking { repository.save(requireNotNull(physicalDraft().toPersistDraftOrNull())) }
        // Only this test's in-memory row: an old algorithm exposes the real global source selector.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE fvp_assessments SET algorithmVersion='qa-status-legacy', f0N=9001.0 WHERE id=?",
            arrayOf(id),
        )
        val before = runBlocking { database.fvpDao().assessmentWithData(id) }
        setSectionContent {
            FvpResultScreen(assessmentId = id, onBack = {}, onExit = {}, onEdit = {}, onSaved = {}, onDeleted = {}, viewModel = viewModel)
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("fvp-sections-tabs").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("fvp-view-original").assertIsSelected()
        val tabs = exerciseStationaryNavigation()
        compose.onNodeWithTag("fvp-view-recalculation").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-view-recalculation").assertIsSelected()
        exerciseStationaryNavigation(tabs)
        compose.onNodeWithTag("fvp-view-original").performClick()
        compose.waitForIdle()
        assertVerticalBounds("source switch", tabs, compose.onNodeWithTag("fvp-sections-tabs").fetchSemanticsNode().boundsInRoot)
        assertEquals("looking at quality must not rewrite the stored execution", before,
            runBlocking { database.fvpDao().assessmentWithData(id) })
    }

    private fun exerciseStationaryNavigation(expectedTabs: Rect? = null): Rect {
        compose.onNodeWithTag(FvpResultSection.SUMMARY.tabTag).performClick()
        compose.waitForIdle()
        val tabs = expectedTabs ?: compose.onNodeWithTag("fvp-sections-tabs").fetchSemanticsNode().boundsInRoot
        val context = compose.onNodeWithTag("fvp-context-header").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("fvp-context-experimental")
            .assertTextEquals(this.context.getString(R.string.fvp_context_experimental))
        fun assertGeometry(section: FvpResultSection) {
            compose.waitForIdle()
            compose.onNodeWithTag(section.tabTag).assertIsSelected()
            compose.onNodeWithTag(section.panelTag).assertIsDisplayed()
            assertVerticalBounds("tabs on $section", tabs, compose.onNodeWithTag("fvp-sections-tabs").fetchSemanticsNode().boundsInRoot)
            assertVerticalBounds("global context on $section", context, compose.onNodeWithTag("fvp-context-header").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("fvp-context-quality").assertIsDisplayed()
        }
        FvpResultSection.entries.forEach { section ->
            compose.onNodeWithTag(section.tabTag).performClick()
            assertGeometry(section)
        }
        compose.onNodeWithTag("fvp-sections-pager").performTouchInput { swipeRight() }
        assertGeometry(FvpResultSection.DATA)
        compose.onNodeWithTag("fvp-sections-pager").performTouchInput { swipeLeft() }
        assertGeometry(FvpResultSection.QUALITY)
        compose.onNodeWithTag(FvpResultSection.SUMMARY.tabTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-open-quality").performScrollTo().performClick()
        assertGeometry(FvpResultSection.QUALITY)
        assertTrue("navigation precedes variable context", tabs.bottom <= context.top + 1f)
        if (compose.onAllNodesWithTag("fvp-view-toggle").fetchSemanticsNodes().isNotEmpty()) {
            assertTrue("global source selector is also below navigation", tabs.bottom <=
                compose.onNodeWithTag("fvp-view-toggle").fetchSemanticsNode().boundsInRoot.top + 1f)
        }
        return tabs
    }

    private fun assertVerticalBounds(label: String, expected: Rect, actual: Rect) {
        // boundsInRoot uses physical pixels: no dp/font-dependent expected height or magic Y.
        assertEquals("$label top", expected.top, actual.top, 1f)
        assertEquals("$label bottom", expected.bottom, actual.bottom, 1f)
    }

    /** Las cuatro paginas existen y (opt-in) dejan una captura propia para la matriz del padre. */
    @Test
    fun fourSectionsRenderAndCaptureEvidenceWhenRequested() {
        FvpDraftSession.adopt(physicalDraft())
        setSectionContent {
            FvpResultScreen(
                onBack = {},
                onExit = {},
                onEdit = {},
                onSaved = {},
                onDeleted = {},
                viewModel = viewModel,
                athleteName = "SECTIONS QA SYNTHETIC",
            )
        }
        val names = mapOf(
            FvpResultSection.SUMMARY to "fvp-sections-summary",
            FvpResultSection.CHARTS to "fvp-sections-charts",
            FvpResultSection.DATA to "fvp-sections-data",
            FvpResultSection.QUALITY to "fvp-sections-quality",
        )
        names.forEach { (section, name) ->
            compose.onNodeWithTag(section.tabTag).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(section.panelTag).assertIsDisplayed()
            captureSection(section, name)
        }
    }

    /**
     * Captura OPT-IN de la seccion indicada: se sube al INICIO de su scroll antes de la toma (una
     * evidencia no puede depender de donde quedo el dedo) y se escribe en `cacheDir/fvp_sections_ui`
     * con un nombre unico por pagina.
     */
    private fun captureSection(section: FvpResultSection, name: String) {
        if (InstrumentationRegistry.getArguments().getString("fvpCaptureEvidence") != "true") return
        val panel = compose.onNodeWithTag(section.panelTag)
        repeat(3) {
            panel.performTouchInput { swipeDown(startY = centerY, endY = bottom - 1f) }
        }
        compose.waitForIdle()
        val dir = File(context.cacheDir, "fvp_sections_ui").apply { mkdirs() }
        val bitmap: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(dir, "$name.png")).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        assertTrue("la captura opt-in debe existir: ${name}.png", File(dir, "$name.png").length() > 0)
    }

    private fun setSectionContent(content: @Composable () -> Unit) {
        restoration.setContent {
            // Tema real del producto: la matriz night/font del AVD debe cambiar esta superficie.
            OpenJumpTheme {
                val profile = when (InstrumentationRegistry.getArguments().getString("fvpUnitProfile")) {
                    "us" -> UnitProfile.UNITED_STATES
                    "custom" -> UnitProfile.METRIC.copy(shortLength = ShortLengthUnit.INCH)
                    else -> UnitProfile.METRIC
                }
                CompositionLocalProvider(
                    LocalUnitSystem provides profile,
                    LocalContext provides context,
                    LocalConfiguration provides context.resources.configuration,
                    content = content,
                )
            }
        }
    }

    /**
     * Serie SJ física mínima (4 condiciones, altura decreciente con la carga, h_po medido) escrita
     * con el MISMO modelo de borrador que usa la app: la pantalla se compone de verdad, con
     * `presentation.canCompute` real.
     */
    private fun physicalDraft(): FvpDraft = FvpDraftSession.begin(athleteId, FvpProtocol.SJ).copy(
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
}
