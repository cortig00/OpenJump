package com.openjump.app.ui.history

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.data.AngleV2HistoryPage
import com.openjump.app.data.AthleteHistoryCursor
import com.openjump.app.data.HistoryQuery
import com.openjump.app.data.angles.AngleHistoryObservation
import com.openjump.app.ui.angles.AngleNavigationSandbox
import com.openjump.app.ui.theme.OpenJumpTheme
import java.util.Collections
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
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
 * Refresco silencioso de HISTORY sobre piezas REALES: `HistoryScreen` + `HistoryViewModel`
 * completos, `LazyColumn` real, `NavHost` real y ciclo de vida real de una `ComponentActivity`
 * (la vuelta del detalle dispara `ON_RESUME` → `refresh()`).
 *
 * Aislamiento: la app y la base Room son las del banco UUID ([AngleNavigationSandbox]); nunca se
 * abre `openjump.db`. Las filas de la familia Ángulos se sirven por la costura de producción ya
 * existente (`angleV2HistoryReader`), lo que permite SOSTENER la consulta de reentrada y observar el
 * estado exacto con la lectura en vuelo, sin sleeps ni `waitForIdle` que la dejen pasar.
 *
 * Qué demuestra (todo con valores observables, no con «está en pantalla»):
 * 1. Una lectura en frío no publica «No results» antes de responder; pasado el retardo visible y con
 *    la consulta aún en vuelo, la etiqueta de carga aparece.
 * 2. El CTA real de «Cargar más» pide la página siguiente con el cursor pendiente y el lote completo
 *    de cada familia (40), y publica las filas sin duplicados.
 * 3. Con 90 filas cargadas y desplazadas en profundidad, abrir el detalle y volver las conserva: la
 *    fila ancla mantiene su POSICIÓN vertical real antes, durante y después de la lectura de
 *    reentrada, que se hace desde la primera página cubriendo la profundidad cargada (cursor nulo),
 *    sin etiqueta de carga.
 * 4. El refresco publica la MISMA profundidad (90): las 5 filas de lookahead que leyó NO se
 *    auto-cargan ni hacen crecer la lista en cada vuelta.
 * 5. La continuación que ofrece ese lookahead usa el cursor de la última fila VISIBLE (90), no el
 *    del final de la ventana (95): la página siguiente sale coherente, sin saltos ni duplicados.
 * 6. Una segunda vuelta detalle/Back mantiene la profundidad (100) y el ancla: no hay crecimiento
 *    por refresco.
 *
 * Límites declarados: no se ejecuta el borrado ni el guardado reales, no se abre `MainActivity` y
 * no se afirma nada sobre el detalle real de la app (el destino del detalle es un marcador de
 * posición; el ciclo de vida y la pila de navegación sí son reales). La fila servida por la costura
 * V2 no pasa por Room: lo que se prueba es el ViewModel y la pantalla, no el DAO.
 */
@RunWith(AndroidJUnit4::class)
class HistorySilentRefreshInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val loadingLabel get() = context.getString(R.string.history_loading)
    private val loadMoreLabel get() = context.getString(R.string.history_load_more)
    private val emptyTitle get() = context.getString(R.string.history_empty_title)

    private lateinit var sandbox: AngleNavigationSandbox
    private lateinit var app: AngleNavigationSandbox.SandboxedApp

    /**
     * Almacén propio del ViewModel: permite cerrar su ámbito (`onCleared` → colectores de atletas y
     * grupos y lectura en vuelo cancelados) ANTES de cerrar la base Room del banco.
     */
    private val viewModelStore = ViewModelStore()

    private lateinit var viewModel: HistoryViewModel

    /** Peticiones que la consulta V2 recibió de verdad: cursor de entrada y lote pedido. */
    private data class Request(val cursor: AthleteHistoryCursor?, val limit: Int)

    private val requests = Collections.synchronizedList(mutableListOf<Request>())

    /** Páginas controladas: la consulta no avanza hasta que el test publica la siguiente. */
    private val pages = Channel<AngleHistoryObservation<List<AngleV2HistoryPage>>>(Channel.UNLIMITED)

    private val controlledReader:
        suspend (HistoryQuery, AthleteHistoryCursor?, Int) -> AngleHistoryObservation<List<AngleV2HistoryPage>> =
        { _, cursor, limit ->
            requests += Request(cursor, limit)
            pages.receive()
        }

    @Before
    fun setUp() {
        sandbox = AngleNavigationSandbox(context)
        app = sandbox.newApp()
        viewModel = ViewModelProvider(
            viewModelStore,
            viewModelFactory {
                initializer {
                    HistoryViewModel(
                        repository = app.repository,
                        encoderRepository = app.encoderRepository,
                        athleteRepository = app.athleteRepository,
                        groupRepository = app.groupRepository,
                        angleRepository = app.angleRepository,
                        angleV2HistoryReader = controlledReader,
                    )
                }
            },
        ).get(HistoryViewModel::class.java)
    }

    /**
     * Sin `runCatching`: un fallo de cierre tiene que verse, no silenciarse. El orden importa —el
     * ámbito del ViewModel primero— para que ningún colector quede vivo sobre una base ya cerrada.
     */
    @After
    fun tearDown() {
        viewModelStore.clear()
        if (::sandbox.isInitialized) sandbox.closeDatabases()
    }

    @Test
    fun unchangedReentryKeepsDepthAnchorAndCoherentCursor() {
        mountGraph()

        // 1) Arranque en frío REAL (ON_RESUME → refresh → primera consulta), sostenida.
        rule.waitUntil(timeoutMillis = TIMEOUT) { requests.size == 1 }
        assertNull("Una consulta nueva no lleva cursor", requests[0].cursor)
        assertEquals("Cada familia recibe el lote completo, no el tamaño de página", 40, requests[0].limit)
        assertTrue(viewModel.state.value.items.isEmpty())
        // Con la lectura en vuelo no hay filas: el vacío no se publica antes de que responda.
        rule.onAllNodesWithText(emptyTitle).assertCountEquals(0)
        // Pasado el retardo visible, y sólo porque la consulta sigue en vuelo, la etiqueta aparece.
        rule.waitUntil(timeoutMillis = TIMEOUT) { viewModel.state.value.showInitialLoader }
        rule.onNodeWithText(loadingLabel).assertIsDisplayed()
        runBlocking { pages.send(observation(v2Rows(1L..30L))) }
        rule.waitUntil(timeoutMillis = TIMEOUT) { viewModel.state.value.items.size == 30 }
        rule.onAllNodesWithText(loadingLabel).assertCountEquals(0)

        // 2) Dos «Cargar más» reales: 90 filas en tres páginas, sin duplicados.
        appendPage(rows = 31L..60L, expectedCursorId = 30L)
        appendPage(rows = 61L..90L, expectedCursorId = 60L)
        assertEquals((1L..90L).toList(), viewModel.state.value.items.map { it.id })
        assertTrue("La tercera página vino llena: puede haber más", viewModel.state.value.canLoadMore)

        // 3) Ancla profunda: última fila cargada, con su POSICIÓN vertical real.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(90)
        rule.onNodeWithTag(rowTag(90L)).assertIsDisplayed()
        val anchorBefore = anchorTop(90L)

        // 4) Detalle real por navegación y vuelta con Back.
        rule.onNodeWithTag(rowTag(90L)).performClick()
        rule.onNodeWithText(DETAIL_LABEL).assertIsDisplayed()
        rule.onNodeWithText(BACK_LABEL).performClick()

        // 5) Reentrada SOSTENIDA: la lista publicada no se suelta y el ancla no se mueve.
        rule.waitUntil(timeoutMillis = TIMEOUT) { requests.size == 4 }
        val refresh = requests[3]
        assertNull("Un refresco relee desde la primera página, no desde el cursor", refresh.cursor)
        assertEquals("Cubre la profundidad cargada (90) más un lookahead corto", 95, refresh.limit)
        assertTrue(viewModel.state.value.refreshing)
        assertFalse("Un refresco silencioso no enciende la etiqueta de carga", viewModel.state.value.loading)
        assertEquals((1L..90L).toList(), viewModel.state.value.items.map { it.id })
        rule.onAllNodesWithText(loadingLabel).assertCountEquals(0)
        rule.onAllNodesWithText(emptyTitle).assertCountEquals(0)
        rule.onNodeWithTag(rowTag(90L)).assertIsDisplayed()
        assertEquals(
            "La fila ancla conserva su posición con la lectura de reentrada en vuelo",
            anchorBefore,
            anchorTop(90L),
            ANCHOR_DELTA,
        )

        // 6) Publicación: la MISMA profundidad ya cargada, sin auto-append del lookahead.
        runBlocking { pages.send(observation(v2Rows(1L..95L), restoreToken = 8L)) }
        rule.waitUntil(timeoutMillis = TIMEOUT) { !viewModel.state.value.refreshing }
        assertEquals(
            "El refresco publica las MISMAS 90 filas: el lookahead (95) no se auto-carga",
            (1L..90L).toList(),
            viewModel.state.value.items.map { it.id },
        )
        assertTrue("Quedan filas sin mostrar: hay continuación", viewModel.state.value.canLoadMore)
        assertFalse(viewModel.state.value.loading)
        rule.onNodeWithTag(rowTag(90L)).assertIsDisplayed()
        assertEquals(
            "La fila ancla conserva su posición al publicar el refresco",
            anchorBefore,
            anchorTop(90L),
            ANCHOR_DELTA,
        )

        // 7) La continuación sale del cursor de la última fila VISIBLE (90), no del lookahead (95).
        appendPage(rows = 91L..100L, expectedCursorId = 90L)
        assertEquals((1L..100L).toList(), viewModel.state.value.items.map { it.id })
        assertFalse("Se agotaron las 100 filas", viewModel.state.value.canLoadMore)

        // 8) Segunda vuelta detalle/Back: la profundidad sigue siendo 100, no 105.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(100)
        rule.onNodeWithTag(rowTag(100L)).assertIsDisplayed()
        val lastAnchor = anchorTop(100L)
        rule.onNodeWithTag(rowTag(100L)).performClick()
        rule.onNodeWithText(DETAIL_LABEL).assertIsDisplayed()
        rule.onNodeWithText(BACK_LABEL).performClick()
        rule.waitUntil(timeoutMillis = TIMEOUT) { requests.size == 6 }
        assertEquals(
            "La ventana crece con la profundidad, nunca por una vuelta fija",
            105,
            requests[5].limit,
        )
        runBlocking { pages.send(observation(v2Rows(1L..100L))) }
        rule.waitUntil(timeoutMillis = TIMEOUT) { !viewModel.state.value.refreshing }
        assertEquals(
            "Repetir la vuelta no añade filas: no hay crecimiento por refresco",
            (1L..100L).toList(),
            viewModel.state.value.items.map { it.id },
        )
        assertFalse(viewModel.state.value.canLoadMore)
        rule.onNodeWithTag(rowTag(100L)).assertIsDisplayed()
        assertEquals(
            "La fila ancla conserva su posición en la segunda vuelta",
            lastAnchor,
            anchorTop(100L),
            ANCHOR_DELTA,
        )
    }

    /**
     * Pulsa el CTA real de «Cargar más» del pie, comprueba que la consulta parte de la ÚLTIMA fila
     * publicada con el lote completo y publica la página controlada.
     */
    private fun appendPage(rows: LongRange, expectedCursorId: Long) {
        val alreadyLoaded = viewModel.state.value.items.size
        val requestCount = requests.size
        // El pie está inmediatamente después de la última fila (el índice 0 es la cabecera).
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(alreadyLoaded + 1)
        rule.onNodeWithText(loadMoreLabel).assertIsDisplayed().performClick()
        rule.waitUntil(timeoutMillis = TIMEOUT) { requests.size == requestCount + 1 }
        val append = requests.last()
        assertEquals("El append parte de la última fila publicada", expectedCursorId, append.cursor?.id)
        assertEquals("Cada familia recibe el lote completo, no el tamaño de página", 40, append.limit)
        runBlocking { pages.send(observation(v2Rows(rows))) }
        rule.waitUntil(timeoutMillis = TIMEOUT) { viewModel.state.value.items.size == rows.last.toInt() }
    }

    /** Posición vertical REAL (px) del borde superior de la fila en la raíz de composición. */
    private fun anchorTop(id: Long): Float =
        rule.onNodeWithTag(rowTag(id)).fetchSemanticsNode().boundsInRoot.top

    /** Grafo real de navegación con `HistoryScreen` y un detalle marcador que vuelve con Back. */
    private fun mountGraph() {
        rule.runOnUiThread {
            rule.activity.setContent {
                OpenJumpTheme {
                    val nav: NavHostController = rememberNavController()
                    NavHost(navController = nav, startDestination = HISTORY_ROUTE) {
                        composable(HISTORY_ROUTE) {
                            HistoryScreen(
                                onMeasurementSelected = { nav.navigate("detail/jump/$it") },
                                onEncoderSessionSelected = { nav.navigate("detail/encoder/$it") },
                                onAngleAnalysisSelected = { nav.navigate("detail/legacy/${it.id}") },
                                onAngleMultiFrameSelected = { nav.navigate("detail/v2/${it.id}") },
                                viewModel = viewModel,
                            )
                        }
                        composable("detail/{kind}/{id}") {
                            Column {
                                Text(DETAIL_LABEL)
                                TextButton(onClick = { nav.popBackStack() }) { Text(BACK_LABEL) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun observation(
        rows: List<AngleV2HistoryPage>,
        restoreToken: Long = 7L,
    ) = AngleHistoryObservation(value = rows, restoreToken = restoreToken)

    private fun v2Rows(ids: LongRange): List<AngleV2HistoryPage> = ids.map { id ->
        AngleV2HistoryPage(
            id = id,
            dateTime = 10_000L - id,
            athleteId = 1L,
            athleteName = "Atleta $id",
            markCount = 2,
        )
    }

    private fun rowTag(id: Long): String = angleHistoryRowTag(
        AngleAnalysisHistoryEntry(
            id = id,
            dateTime = 10_000L - id,
            athleteId = 1L,
            athleteName = "Atleta $id",
            markCount = 2,
            videoUri = null,
            source = AngleHistorySource.MULTI_FRAME,
        ),
    )

    private companion object {
        const val HISTORY_ROUTE = "history"
        const val DETAIL_LABEL = "detail"
        const val BACK_LABEL = "detail-back"
        const val TIMEOUT = 5_000L

        /** Tolerancia en px al comparar la posición del ancla: el layout es determinista. */
        const val ANCHOR_DELTA = 0.5f
    }
}
