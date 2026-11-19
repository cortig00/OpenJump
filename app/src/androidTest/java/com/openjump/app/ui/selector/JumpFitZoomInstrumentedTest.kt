package com.openjump.app.ui.selector

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R as Media3UiR
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.math.ProtocolCalculator
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolResult
import com.openjump.app.protocol.VideoSource
import com.openjump.app.ui.angles.ANGLE_WORKSPACE_SOURCE_ZOOM_IN_TAG
import com.openjump.app.ui.angles.ANGLE_WORKSPACE_SOURCE_ZOOM_OUT_TAG
import com.openjump.app.ui.angles.ANGLE_WORKSPACE_SOURCE_ZOOM_RESET_TAG
import com.openjump.app.ui.angles.AngleNavigationSandbox
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.titleResource
import com.openjump.app.video.AppSession
import com.openjump.app.video.VideoFrameIndex
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.NumberFormat
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fixture instrumentada del zoom FIT del selector temporal de Saltos (A2B).
 *
 * Usa la pantalla REAL ([FrameSelectorScreen] + [FrameSelectorViewModel] + Media3), el fixture VFR
 * empaquetado `angles/ordinal_vfr.mp4` y el banco UUID existente ([AngleNavigationSandbox]): base
 * Room propia, contexto/`ViewModelStoreOwner` aislados y una copia del asset dentro de la caché del
 * banco. No escribe la base ni las preferencias del usuario, no guarda resultados y no toca Encoder.
 *
 * Acredita, sin saltos ni datos simulados:
 * - dos recorridos completos, uno a 1x (control) y otro con una pinza real de dos punteros sobre la
 *   arena del vídeo, marcando los mismos frames 2 (80 ms) y 8 (480 ms) con la CTA de producción;
 * - que el gesto no marca, no busca, no pausa ni cambia el PTS/frame mostrado, y que la superficie
 *   Media3 es la misma instancia con `exo_content_frame` realmente escalado (>1) tras la pinza;
 * - que + / restablecer mueven la transformación nativa sin cambiar frame ni marcas, volviendo a 1x;
 * - que los eventos retenidos (PTS y vecinos canónicos) y `ProtocolCalculator.compute(draft)` son
 *   idénticos con y sin zoom, con su unidad y resultado de producto válidos;
 * - que el control de vídeo de Ángulos queda arriba a la derecha del visor, y ni los bordes
 *   del vídeo ni la línea de tiempo, reproducir/pausar, anterior y siguiente se mueven con el zoom;
 * - que en el máximo ×4 el + queda deshabilitado y un **toque físico** real sobre él no aplica
 *   ningún cambio, mientras − y restablecer siguen operativos;
 * - que la rama Horizontal conserva su control heredado FIT/CROP y NO monta la arena de zoom.
 *
 * Capturas de UI reales (opt-in explícito, por defecto no se captura nada): con
 * `-e openjumpZoomEvidence true` el recorrido con zoom guarda `UiAutomation.takeScreenshot()` en
 * pausa en ×1 y en ×1,5 como PNG dentro del directorio de caché propio de este banco UUID, con
 * nombres conocidos y la ruta absoluta en log para poder extraerla en solo lectura. No hay mocks de
 * imagen ni archivos escritos fuera de ese sandbox de test.
 *
 * Límites honestos: no valida la exactitud física de la medición ni el encoder; el resultado se
 * calcula con el mismo `ProtocolCalculator` de producto, no se persiste. La ejecución real en el
 * AVD autorizado queda para la puerta separada posterior.
 */
@RunWith(AndroidJUnit4::class)
class JumpFitZoomInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val target: Context = ApplicationProvider.getApplicationContext()
    private var sandbox: AngleNavigationSandbox? = null
    private var uiContext: Context? = null
    private var owner: ViewModelStoreOwner? = null
    private var fixtureCopy: File? = null
    private var sandboxAthleteId = 0L
    private val store = ViewModelStore()
    private val mounted = mutableStateOf<FrameSelectorViewModel?>(null)
    private var installed = false
    @Volatile private var computeSignal = 0
    private var mountSequence = 0

    private data class RunEvidence(
        val frameTimesUs: List<Long>,
        val markedEvents: Map<EventKey, EventMark>,
        val result: ProtocolResult,
    )

    @After
    fun tearDown() {
        logEvidenceSummary()
        if (installed) {
            compose.runOnUiThread { mounted.value = null }
            compose.waitForIdle()
        }
        compose.runOnUiThread {
            store.clear()
            AppSession.reset()
        }
        sandbox?.closeDatabases()
        fixtureCopy?.delete()
    }

    // --- fixture y banco aislado ---------------------------------------------------------------

    private fun prepareSandboxAndFixture() {
        val sandbox = AngleNavigationSandbox(target)
        this.sandbox = sandbox
        val app = sandbox.newApp()
        sandboxAthleteId = requireNotNull(
            runBlocking { app.athleteRepository.bootstrapIfInstallationEmpty() },
        ) { "El banco aislado debe crear su atleta de arranque." }.id
        uiContext = app.newUiContext(compose.activity)
        owner = app.newDefaultViewModelOwner(compose.activity)
        val copy = File(sandbox.ownedCacheDir, "jump-fit-zoom-${sandbox.uuid}.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE_ASSET).use { input ->
            copy.outputStream().use { output -> input.copyTo(output) }
        }
        assertTrue(
            "La copia propia del fixture $FIXTURE_ASSET no es legible.",
            copy.canRead() && copy.length() > 0L,
        )
        assertEquals(
            "El asset empaquetado debe seguir siendo el VFR congelado de este fixture.",
            FIXTURE_SHA256,
            sha256(copy),
        )
        fixtureCopy = copy
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun label(id: Int, vararg args: Any): String =
        requireNotNull(uiContext ?: target).getString(id, *args)

    private fun zoomLabel(value: Double): String {
        val locale = requireNotNull(uiContext).resources.configuration.locales[0]
        val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
        return label(R.string.angle_workspace_zoom_value, format.format(value))
    }

    private fun assertOverlayLayout() {
        val video = compose.onNodeWithTag(AnalysisLayout.VIDEO_PANE_TAG)
            .fetchSemanticsNode().boundsInRoot
        val overlay = compose.onNodeWithTag(JUMP_FIT_ZOOM_OVERLAY_TAG)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("El control de Ángulos debe quedar dentro del visor: visor=$video overlay=$overlay",
            overlay.left >= video.left - 1f && overlay.right <= video.right + 1f &&
                overlay.top >= video.top - 1f && overlay.bottom <= video.bottom + 1f)
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("El control de vídeo se alinea arriba y al final del visor: visor=$video overlay=$overlay",
            overlay.top - video.top <= 8f * density + 1f &&
                video.right - overlay.right <= 8f * density + 1f)
        listOf(
            ANGLE_WORKSPACE_SOURCE_ZOOM_OUT_TAG,
            ANGLE_WORKSPACE_SOURCE_ZOOM_IN_TAG,
            ANGLE_WORKSPACE_SOURCE_ZOOM_RESET_TAG,
        ).forEach { tag ->
            val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            val bounds = node.boundsInRoot
            assertTrue("El objetivo $tag no puede quedar cortado por el visor: $bounds",
                bounds.left >= video.left - 1f && bounds.right <= video.right + 1f &&
                    bounds.top >= video.top - 1f && bounds.bottom <= video.bottom + 1f)
            assertTrue("El objetivo táctil $tag debe conservar al menos 48×48 dp: ${node.touchBoundsInRoot}",
                node.touchBoundsInRoot.width >= 48f * density - 1f &&
                    node.touchBoundsInRoot.height >= 48f * density - 1f)
        }
    }

    private fun transportBounds(): Map<String, List<Rect>> = buildMap {
        listOf(
            R.string.selector_timeline_description,
            R.string.selector_play_video,
            R.string.selector_previous_frame_description,
            R.string.selector_next_frame_description,
        ).forEach { resource ->
            val description = label(resource)
            val nodes = compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes()
            assertTrue("Falta el control real de transporte: $description", nodes.isNotEmpty())
            put(description, nodes.map { it.boundsInRoot })
        }
    }

    // --- evidencia visual real (opt-in explícito) -----------------------------------------------

    /** `-e openjumpZoomEvidence true`; por defecto NO se captura ni se escribe ningún PNG. */
    private val evidenceRequested: Boolean by lazy {
        InstrumentationRegistry.getArguments().getString(EVIDENCE_ARGUMENT)?.toBoolean() == true
    }

    private val evidenceFiles = mutableListOf<File>()

    /**
     * Captura real de pantalla con `UiAutomation.takeScreenshot()` durante el recorrido temporal
     * genuino, en pausa y con Compose sincronizado.
     *
     * Solo se escribe (con nombre conocido) dentro del directorio de caché propio de este banco
     * UUID: no hay mocks de imagen, ni rutas de base/paquete de conveniencia, ni `MainActivity`
     * normal. La ruta absoluta se registra en log para poder extraerla en solo lectura mientras el
     * emulador siga vivo. Si el opt-in está activo y la captura no está disponible, la prueba falla
     * en vez de fingir evidencia.
     */
    private fun captureEvidence(phase: String) {
        if (!evidenceRequested) return
        val sandbox = requireNotNull(sandbox) { "El banco aislado debe existir antes de capturar." }
        compose.runOnIdle { }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull(
            "El opt-in $EVIDENCE_ARGUMENT=true exige una captura real; " +
                "UiAutomation.takeScreenshot() devolvió null en la fase $phase.",
            bitmap,
        )
        val file = File(sandbox.ownedCacheDir, "jump-fit-zoom-${sandbox.uuid}-$phase.png")
        val written = FileOutputStream(file).use { output ->
            requireNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        assertTrue("No se pudo escribir el PNG real de la fase $phase en $file", written)
        assertTrue("El PNG real de la fase $phase quedó vacío: $file", file.length() > 0L)
        evidenceFiles += file
        Log.i(
            EVIDENCE_LOG_TAG,
            "phase=$phase path=${file.absolutePath} bytes=${file.length()} " +
                "orientation=${compose.activity.resources.configuration.orientation} " +
                "uuid=${sandbox.uuid}",
        )
    }

    /** Resume en log las rutas exactas capturadas (una entrada por fase), también al fallar. */
    private fun logEvidenceSummary() {
        if (!evidenceRequested || evidenceFiles.isEmpty()) return
        Log.i(
            EVIDENCE_LOG_TAG,
            "captured=${evidenceFiles.size} dir=${evidenceFiles.first().parent} " +
                "files=${evidenceFiles.joinToString(",") { it.name }}",
        )
    }

    private fun requireFrameIndex(): VideoFrameIndex = requireNotNull(AppSession.frameIndex.value) {
        "El vídeo no quedó indexado; no se puede afirmar nada sobre los frames."
    }

    /** El fixture congelado: 12 frames VFR reales con PTS 80 ms y 480 ms en los índices 2 y 8. */
    private fun assertPackagedVfrFixture(index: VideoFrameIndex) {
        assertEquals("El fixture empaquetado debe tener 12 frames.", 12, index.frameCount)
        assertEquals("Frame 2 del fixture (80 ms).", 80_000L, index.timeOf(2))
        assertEquals("Frame 8 del fixture (480 ms).", 480_000L, index.timeOf(8))
        val deltas = index.frameTimesUs.toList().zipWithNext { first, second -> second - first }.distinct()
        assertTrue(
            "El fixture debe conservar PTS VFR reales, no una cadencia constante: $deltas",
            deltas.size > 1,
        )
    }

    // --- montaje real ---------------------------------------------------------------------------

    private fun mount(protocolId: ProtocolId): FrameSelectorViewModel {
        val context = requireNotNull(uiContext) { "El banco debe prepararse antes de montar." }
        val sandboxOwner = requireNotNull(owner) { "El banco debe prepararse antes de montar." }
        lateinit var vm: FrameSelectorViewModel
        compose.runOnUiThread {
            AppSession.begin(protocolId, athleteId = sandboxAthleteId)
            AppSession.attachVideo(Uri.fromFile(requireNotNull(fixtureCopy)), VideoSource.IMPORTED)
            vm = FrameSelectorViewModel(context)
            store.put("jump-fit-zoom-${mountSequence++}", vm)
        }
        compose.runOnUiThread { mounted.value = vm }
        if (!installed) {
            compose.setContent {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalViewModelStoreOwner provides sandboxOwner,
                ) {
                    OpenJumpTheme {
                        mounted.value?.let { current ->
                            key(current) {
                                FrameSelectorScreen(
                                    onBack = {},
                                    onCompute = { computeSignal++ },
                                    viewModel = current,
                                )
                            }
                        }
                    }
                }
            }
            installed = true
        }
        compose.waitUntil(60_000) {
            vm.error.value == null && !vm.loading.value && vm.displayState.value.canMark
        }
        compose.waitForIdle()
        return vm
    }

    private fun unmount() {
        if (!installed) return
        compose.runOnUiThread { mounted.value = null }
        compose.waitForIdle()
        compose.runOnUiThread {
            store.clear()
            AppSession.reset()
        }
        compose.waitForIdle()
    }

    private fun findPlayerView(root: View?): PlayerView? {
        if (root == null) return null
        if (root is PlayerView) return root
        if (root is ViewGroup) {
            for (position in 0 until root.childCount) {
                findPlayerView(root.getChildAt(position))?.let { return it }
            }
        }
        return null
    }

    private fun requireSurface(): Pair<PlayerView, View> {
        val playerView = requireNotNull(findPlayerView(compose.activity.window.decorView)) {
            "La pantalla real debe exponer su PlayerView Media3."
        }
        val contentFrame = requireNotNull(playerView.findViewById<View>(Media3UiR.id.exo_content_frame)) {
            "El PlayerView real debe exponer exo_content_frame."
        }
        return playerView to contentFrame
    }

    // --- acciones reales de transporte y marcado ------------------------------------------------

    private fun stepTo(targetIndex: Int) {
        val vm = requireNotNull(mounted.value)
        var guard = 0
        while (vm.displayState.value.requestedIndex < targetIndex) {
            compose.onNodeWithContentDescription(label(R.string.selector_next_frame_description))
                .assertIsEnabled()
                .performClick()
            compose.waitUntil(15_000) {
                val state = vm.displayState.value
                state.canMark && state.renderedIndex == state.requestedIndex
            }
            guard++
            check(guard <= MAX_STEPS) { "No se pudo avanzar al frame $targetIndex con el transporte real." }
        }
        assertEquals(
            "El frame pedido y el renderizado deben coincidir antes de marcar.",
            targetIndex,
            vm.displayState.value.renderedIndex,
        )
    }

    /** Marca con la CTA contextual de producción; nunca usa `markAt` directamente. */
    private fun markWithProductionCta(): EventMark {
        val draft = requireNotNull(AppSession.draft.value) { "No hay borrador activo para marcar." }
        val next = requireNotNull(draft.nextRequiredEvent()) { "El borrador ya no pide más eventos." }
        val cta = label(R.string.selector_mark_event, label(next.titleResource()).lowercase())
        compose.onNodeWithText(cta).assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            AppSession.draft.value?.events?.containsKey(EventKey(next)) == true
        }
        return requireNotNull(AppSession.draft.value?.events?.get(EventKey(next))) {
            "La marca de $next no quedó retenida en el borrador."
        }
    }

    private fun metric(result: ProtocolResult, key: MetricKey) =
        requireNotNull(result.allMetrics.firstOrNull { it.key == key }) { "Falta la métrica $key." }

    private fun assertProductResult(result: ProtocolResult) {
        assertEquals(ProtocolId.SJ, result.protocolId)
        val height = result.primaryMetric
        assertEquals("La métrica primaria de SJ es la altura.", MetricKey.HEIGHT_CM, height.key)
        assertEquals(MetricUnit.CENTIMETER, height.unit)
        assertTrue("La altura debe ser un número positivo y finito: ${height.value}", height.value > 0.0 && height.value.isFinite())
        val flight = metric(result, MetricKey.FLIGHT_TIME_MS)
        assertEquals(MetricUnit.MILLISECOND, flight.unit)
        assertEquals("El fixture separa los frames 80 ms y 480 ms.", 400.0, flight.value, 1e-9)
        val velocity = metric(result, MetricKey.TAKEOFF_VELOCITY_MPS)
        assertEquals(MetricUnit.METER_PER_SECOND, velocity.unit)
        assertTrue("La velocidad de despegue debe ser positiva: ${velocity.value}", velocity.value > 0.0 && velocity.value.isFinite())
    }

    // --- recorrido completo ---------------------------------------------------------------------

    private fun runSelector(zoom: Boolean): RunEvidence {
        unmount()
        computeSignal = 0
        val vm = mount(ProtocolId.SJ)
        assertPackagedVfrFixture(requireFrameIndex())
        val (surface, contentFrame) = requireSurface()
        val initialPlayer = surface.player

        // 1x = FIT heredado: el mismo fotograma completo, sin ampliación nativa.
        assertEquals("La arena de encuadre debe montarse en la rama elegible.", 1,
            compose.onAllNodesWithTag(JUMP_FIT_ZOOM_ARENA_TAG).fetchSemanticsNodes().size)
        compose.onNodeWithText(zoomLabel(1.0)).assertIsDisplayed()
        compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_IN_TAG).assertIsEnabled()
        compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_OUT_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_RESET_TAG).assertIsNotEnabled()
        assertOverlayLayout()
        val transportAtOne = transportBounds()
        Log.i(EVIDENCE_LOG_TAG, "transportAtOne=$transportAtOne")
        if (zoom) {
            // Evidencia visual real (solo con `-e openjumpZoomEvidence true`): selector temporal
            // genuino, reproducción en pausa, ×1…
            captureEvidence("paused-x1")
            // …y un multiplicador conocido ×1,5 por el + de producción, antes de la rama de ×4.
            compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_IN_TAG).assertIsEnabled().performClick()
            compose.onNodeWithText(zoomLabel(1.5)).assertIsDisplayed()
            compose.waitUntil(5_000) { contentFrame.scaleX > 1.01f }
            assertOverlayLayout()
            assertEquals("Pasar de 1× a 1,5× no puede mover el transporte.", transportAtOne, transportBounds())
            captureEvidence("paused-x1_5")
            compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_RESET_TAG).assertIsEnabled().performClick()
            compose.waitUntil(5_000) { abs(contentFrame.scaleX - 1f) < 1e-4f }
            compose.onNodeWithText(zoomLabel(1.0)).assertIsDisplayed()
            // Semántica real de mínimo y máximo: saturado en ×4 el + deja de aportar y − vuelve.
            repeat(6) { compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_IN_TAG).assertIsEnabled().performClick() }
            compose.onNodeWithText(zoomLabel(4.0)).assertIsDisplayed()
            // En el máximo el + está deshabilitado: se comprueba su estado y un toque FÍSICO real
            // (no un `performClick` semántico sobre un nodo deshabilitado) no aplica nada.
            compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_IN_TAG).assertIsNotEnabled()
            val scaleAtMax = contentFrame.scaleX
            compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_IN_TAG).performTouchInput { click(center) }
            compose.onNodeWithText(zoomLabel(4.0)).assertIsDisplayed()
            assertEquals(
                "Un toque real en + deshabilitado no puede cambiar la transformación.",
                scaleAtMax,
                contentFrame.scaleX,
                1e-4f,
            )
            compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_OUT_TAG).assertIsEnabled()
            compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_RESET_TAG).assertIsEnabled().performClick()
            compose.onNodeWithText(zoomLabel(1.0)).assertIsDisplayed()
            compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_OUT_TAG).assertIsNotEnabled()
            compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_RESET_TAG).assertIsNotEnabled()
            compose.waitUntil(5_000) {
                abs(contentFrame.scaleX - 1f) < 1e-4f && abs(contentFrame.scaleY - 1f) < 1e-4f
            }
        }
        assertEquals(1f, contentFrame.scaleX, 1e-4f)
        assertEquals(1f, contentFrame.scaleY, 1e-4f)
        assertEquals(0, vm.displayState.value.renderedIndex)
        assertEquals(0L, vm.displayState.value.renderedPtsUs)
        assertEquals("A 1x no puede haber ninguna marca todavía.", emptyMap<EventKey, EventMark>(), AppSession.draft.value?.events)
        assertEquals(0, AppSession.currentFrameIndex.value)

        if (zoom) {
            val paneBefore = compose.onNodeWithTag(AnalysisLayout.VIDEO_PANE_TAG)
                .fetchSemanticsNode().boundsInRoot
            val requestedBefore = vm.displayState.value.requestedIndex
            val renderedBefore = vm.displayState.value.renderedIndex
            val ptsBefore = vm.displayState.value.renderedPtsUs
            // Pinza real de dos punteros dentro de los límites de la arena/vídeo.
            compose.onNodeWithTag(JUMP_FIT_ZOOM_ARENA_TAG).performTouchInput {
                down(0, center - Offset(30f, 0f))
                down(1, center + Offset(30f, 0f))
                moveTo(0, center - Offset(60f, 0f))
                moveTo(1, center + Offset(60f, 0f))
                up(0)
                up(1)
            }
            compose.waitUntil(10_000) { contentFrame.scaleX > 1.01f }
            assertOverlayLayout()
            val transportAfterPinch = transportBounds()
            assertEquals("La pinza no puede mover la línea de tiempo ni el transporte.", transportAtOne, transportAfterPinch)
            Log.i(EVIDENCE_LOG_TAG, "transportAfterPinch=$transportAfterPinch")
            assertEquals(
                "La transformación nativa debe ser isotrópica.",
                contentFrame.scaleX,
                contentFrame.scaleY,
                1e-4f,
            )
            // La misma superficie y el mismo player: el zoom no recrea ni reasigna Media3.
            val (surfaceAfter, contentAfter) = requireSurface()
            assertSame("El gesto no debe recrear el PlayerView.", surface, surfaceAfter)
            assertSame("El gesto no debe reasignar el player.", initialPlayer, surfaceAfter.player)
            assertSame("El gesto no debe sustituir exo_content_frame.", contentFrame, contentAfter)
            assertEquals("El gesto no debe redimensionar el visor.", paneBefore,
                compose.onNodeWithTag(AnalysisLayout.VIDEO_PANE_TAG).fetchSemanticsNode().boundsInRoot)
            // Ni marca, ni busca, ni cambia el frame/PTS mostrado.
            assertEquals("Una pinza no puede marcar.", emptyMap<EventKey, EventMark>(), AppSession.draft.value?.events)
            assertEquals(requestedBefore, vm.displayState.value.requestedIndex)
            assertEquals(renderedBefore, vm.displayState.value.renderedIndex)
            assertEquals(ptsBefore, vm.displayState.value.renderedPtsUs)
            compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_RESET_TAG).assertIsEnabled()
        }

        // Mismos frames en ambos recorridos, marcados con la CTA real (el zoom ya está aplicado).
        stepTo(2)
        assertEquals(80_000L, vm.displayState.value.renderedPtsUs)
        val takeoff = markWithProductionCta()
        assertEquals(EventType.TAKEOFF, takeoff.key.type)
        stepTo(8)
        assertEquals(480_000L, vm.displayState.value.renderedPtsUs)
        val landing = markWithProductionCta()
        assertEquals(EventType.LANDING, landing.key.type)

        val draft = requireNotNull(AppSession.draft.value)
        assertEquals("El selector debe pedir exactamente los dos eventos de SJ.", 2, draft.events.size)
        assertNull("Las dos marcas válidas no deben dejar errores de validación.", draft.validationError())
        val markedEvents = draft.events.toMap()
        val result = ProtocolCalculator.compute(draft)
        assertProductResult(result)

        // La CTA de cálculo es la de producto y está habilitada tras las marcas válidas.
        compose.onNodeWithText(label(R.string.selector_compute_result))
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(5_000) { computeSignal == 1 }
        assertEquals("Calcular no puede borrar ni duplicar marcas.", markedEvents, AppSession.draft.value?.events)

        if (zoom) {
            // + / restablecer mueven la transformación real sin tocar frame ni marcas.
            val scaleBeforeStep = contentFrame.scaleX
            compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_IN_TAG)
                .assertIsEnabled()
                .performClick()
            compose.waitUntil(5_000) { contentFrame.scaleX > scaleBeforeStep + 0.01f }
            assertEquals("+ no puede cambiar el frame.", 8, vm.displayState.value.renderedIndex)
            assertEquals("+ no puede cambiar las marcas.", markedEvents, AppSession.draft.value?.events)
            compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_RESET_TAG)
                .assertIsEnabled()
                .performClick()
            compose.waitUntil(5_000) {
                abs(contentFrame.scaleX - 1f) < 1e-4f && abs(contentFrame.scaleY - 1f) < 1e-4f
            }
            assertEquals("Restablecer no puede cambiar el frame.", 8, vm.displayState.value.renderedIndex)
            assertEquals("Restablecer no puede borrar marcas.", markedEvents, AppSession.draft.value?.events)
            compose.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_RESET_TAG)
                .assertIsNotEnabled()
            compose.onNodeWithText(zoomLabel(1.0)).assertIsDisplayed()
            captureEvidence("marked-reset-x1")
        }

        return RunEvidence(
            frameTimesUs = requireFrameIndex().frameTimesUs.toList(),
            markedEvents = markedEvents,
            result = result,
        )
    }

    // --- pruebas --------------------------------------------------------------------------------

    @Test
    fun sameFramesMarksAndProductResultWithAndWithoutRealPinchZoom() {
        prepareSandboxAndFixture()
        try {
            val control = runSelector(zoom = false)
            val zoomed = runSelector(zoom = true)

            assertEquals(
                "Ambos recorridos deben usar el mismo índice PTS del fixture.",
                control.frameTimesUs,
                zoomed.frameTimesUs,
            )
            assertEquals(
                "El zoom no puede alterar las claves de evento retenidas.",
                control.markedEvents.keys,
                zoomed.markedEvents.keys,
            )
            assertEquals(
                "El zoom no puede alterar los eventos (frame, PTS y vecinos canónicos).",
                control.markedEvents,
                zoomed.markedEvents,
            )
            val takeoff = requireNotNull(control.markedEvents[EventKey(EventType.TAKEOFF)])
            val landing = requireNotNull(control.markedEvents[EventKey(EventType.LANDING)])
            assertEquals(2, takeoff.frameIndex)
            assertEquals(80_000L, takeoff.ptsUs)
            assertEquals(40_000L, takeoff.previousPtsUs)
            assertEquals(120_000L, takeoff.nextPtsUs)
            assertEquals(8, landing.frameIndex)
            assertEquals(480_000L, landing.ptsUs)
            assertEquals(320_000L, landing.previousPtsUs)
            assertEquals(520_000L, landing.nextPtsUs)
            assertEquals(
                "El resultado de producto no puede depender del zoom.",
                control.result.allMetrics,
                zoomed.result.allMetrics,
            )
            assertEquals(MetricKey.HEIGHT_CM, control.result.primaryMetric.key)
            assertEquals(MetricKey.HEIGHT_CM, zoomed.result.primaryMetric.key)
            assertFalse(control.markedEvents.isEmpty())
        } finally {
            unmount()
        }
    }

    @Test
    fun horizontalBranchKeepsLegacyFitCropControlWithoutJumpZoomArena() {
        prepareSandboxAndFixture()
        try {
            mount(ProtocolId.HORIZONTAL)
            compose.onNodeWithTag(JUMP_FIT_ZOOM_ARENA_TAG).assertDoesNotExist()
            // La rama heredada empieza en FIT y ofrece pasar a CROP/Encuadrar.
            compose.onNodeWithContentDescription(label(R.string.selector_fit_to_crop_description))
                .assertIsDisplayed()
                .assertIsEnabled()
                .performClick()
            compose.onNodeWithContentDescription(label(R.string.selector_crop_to_fit_description))
                .assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.selector_adjust_crop_description))
                .assertIsDisplayed()
                .performClick()
            compose.onNodeWithContentDescription(label(R.string.selector_done_crop_description))
                .assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.selector_done_crop_description)).performClick()
            compose.onNodeWithContentDescription(label(R.string.selector_crop_to_fit_description)).performClick()
            // Vuelve al FIT heredado y nunca aparece la arena/controles del zoom de Saltos.
            compose.onNodeWithContentDescription(label(R.string.selector_fit_to_crop_description))
                .assertIsDisplayed()
            compose.onNodeWithTag(JUMP_FIT_ZOOM_ARENA_TAG).assertDoesNotExist()
            compose.onNodeWithTag(JUMP_FIT_ZOOM_OVERLAY_TAG).assertDoesNotExist()
        } finally {
            unmount()
        }
    }

    private companion object {
        const val FIXTURE_ASSET = "angles/ordinal_vfr.mp4"

        /** PTS VFR congelados del fixture: 12 frames, 80 ms y 480 ms en los índices 2 y 8. */
        const val FIXTURE_SHA256 = "e714968f1f683ccc512e42c7a04af3a908e7581130e451948c9ab22a97704172"

        /** Opt-in explícito (`-e openjumpZoomEvidence true`) para capturas PNG reales. */
        const val EVIDENCE_ARGUMENT = "openjumpZoomEvidence"
        const val EVIDENCE_LOG_TAG = "OpenJumpZoomEvidence"

        const val MAX_STEPS = 12
    }
}
