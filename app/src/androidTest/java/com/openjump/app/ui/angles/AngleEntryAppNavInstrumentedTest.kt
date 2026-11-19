package com.openjump.app.ui.angles

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleFrameId
import com.openjump.app.angles.AnglePoint
import com.openjump.app.data.angles.AngleImageIntegrity
import com.openjump.app.data.angles.AngleImageReadResult
import com.openjump.app.data.angles.PngPixelSize
import com.openjump.app.data.angles.pngPixelSize
import com.openjump.app.testsupport.awaitValueBlocking
import com.openjump.app.ui.ANGLES_ATHLETE_ID_ARG
import com.openjump.app.ui.ANGLES_VIDEO_URI_ARG
import com.openjump.app.ui.ANGLES_EDITOR_ROUTE
import com.openjump.app.ui.ANGLES_V2_SAVED_DETAIL_ROUTE
import com.openjump.app.ui.HOME_ROUTE
import com.openjump.app.ui.OpenJumpNavHost
import com.openjump.app.ui.theme.OpenJumpTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Navegación y registro de importación REALES de la entrada de Ángulos sobre el banco aislado
 * ([AngleNavigationSandbox]): `OpenJumpNavHost` completo, `AngleEntryHandoffViewModel.Factory`
 * real, Room real con nombre UUID y todas las piezas de producción (actor, atleta congelado,
 * launcher del picker, editor con viewer/indexador Media3 y guardado V2).
 *
 * Frontera simulada declarada: la ACTIVIDAD EXTERNA del selector de fotos. Se inyecta en TEST un
 * [ActivityResultRegistry] (`LocalActivityResultRegistryOwner`) que graba `requestCode`, contrato y
 * entrada de cada `onLaunch` y no abre ninguna actividad del sistema; el test entrega el resultado
 * con `dispatchResult(code, ...)`. Los callbacks son los REALES de Compose → actor → navegación.
 * NO se simula CameraX ni la grabación: la captura/guardado del editor sí son reales (media3,
 * índice, freeze y PNG). Tampoco se afirma selección externa del picker del sistema ni muerte de
 * proceso.
 *
 * Preparación programática declarada: los fotogramas y marcas del editor se preparan con la API
 * real del `ViewModel` (misma secuencia que el arnés del flujo multi-fotograma); eso NO demuestra
 * gestos de marcado ni la navegación interna del selector. El `Save` sí se pulsa en la UI real.
 *
 * El fixture del vídeo se publica con el `FileProvider` REAL (`content://`) y no como `file://`: la
 * cadena de grants sólo registra URIs de contenido y el almacén de permisos del banco es en memoria
 * (la lectura del player/indexador sigue siendo real vía `ContentResolver`/proveedor).
 */
@RunWith(AndroidJUnit4::class)
class AngleEntryAppNavInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext

    private lateinit var sandbox: AngleNavigationSandbox
    private lateinit var app: AngleNavigationSandbox.SandboxedApp
    private lateinit var sourceFile: File
    private lateinit var videoUri: String

    private var athleteA: Long = 0L
    private var athleteB: Long = 0L

    /** Registro de Activity Result capturado: es la ÚNICA frontera simulada de esta slice. */
    private val registry = SandboxResultRegistry()

    /** App montada en el grafo; `null` desmonta la composición (una sola `setContent`). */
    private val mountedApp = mutableStateOf<AngleNavigationSandbox.SandboxedApp?>(null)

    /**
     * Frontera SOLO de test: niega el permiso de framework CAMERA al `Context` de composición.
     * Se fija antes de [mountGraph]; el resto del contexto sigue siendo el banco aislado.
     */
    private var denyCameraPermission = false

    @Volatile
    private var navController: NavHostController? = null

    @Volatile
    private var uiContext: Context? = null

    @Before
    fun setUp() {
        sandbox = AngleNavigationSandbox(target)
        app = sandbox.newApp()
        app.onboardingPreferencesStore.completeOnboarding()
        // Dos atletas ACTIVOS propios del banco ANTES de montar el grafo: así no hay bootstrap de
        // "atleta único" ni fallback automático que oculte la ausencia de selección.
        athleteA = runBlocking { app.athleteRepository.create(displayName = "Entrada A") }
        athleteB = runBlocking { app.athleteRepository.create(displayName = "Entrada B") }
        // Fixture propio copiado a la raíz de compartición del banco (`@xml/file_paths`:
        // `cache-path/shared-angle-frames`); la URI es `content://` REAL del FileProvider.
        sourceFile = File(sandbox.shareCacheRoot, "qa-intro-${sandbox.uuid}.mp4")
        sourceFile.parentFile?.mkdirs()
        instrumentation.context.assets.open(FIXTURE_ASSET).use { input ->
            sourceFile.outputStream().use { output -> input.copyTo(output) }
        }
        videoUri = sandbox.shareUri(app, sourceFile).toString()
    }

    @After
    fun tearDown() {
        // Desmontar el grafo y vaciar el almacén de ViewModels de la Activity ANTES de cerrar la
        // base: `onCleared` del handoff y del editor liberan sus leases. No se resetea `AppSession`
        // ni se toca ningún estado del usuario (todo vive en el banco UUID).
        runCatching { rule.runOnUiThread { mountedApp.value = null } }
        runCatching { rule.waitForIdle() }
        runCatching { rule.runOnUiThread { rule.activity.viewModelStore.clear() } }
        runCatching { rule.waitForIdle() }
        // La reconciliación de grants de la app es asíncrona y podría leer la base ya cerrada: se
        // acota el cierre con `runCatching` (sin puertos nuevos en producción).
        runCatching { sandbox.closeDatabases() }
        runCatching { rule.waitForIdle() }
        // Sólo el fixture PROPIO del test; los artefactos UUID del banco quedan para inspección.
        runCatching { sourceFile.delete() }
    }

    // --- 1. Home → intro sin lanzar el registro; Ayuda/Back y selección real ---------------------

    @Test
    fun laEntradaDesdeHomeNoLanzaElPickerYAyudaConservaLaSeleccion() {
        runBlocking { assertTrue(app.selectedAthleteStore.select(app.athleteRepository, athleteA)) }
        mountGraph()
        openAnglesIntro()

        assertEquals("Entrar en la intro no puede lanzar el registro del picker.", 0, registry.launchCount())
        waitForText(historyHelperText(ATHLETE_A_NAME))
        assertEquals("Sin importación no puede haber análisis en Room.", 0L, countRoots())

        rule.onNodeWithTag(INTRO_HELP_TAG).performClick()
        awaitRoute(ANGLES_HELP_ROUTE)
        rule.onNodeWithTag(HOW_ROOT_TAG).assertExists()
        // La ayuda no ofrece importación ni grabación y no guarda nada.
        rule.onNodeWithTag(INTRO_IMPORT_TAG).assertDoesNotExist()
        rule.onNodeWithTag(INTRO_RECORD_TAG).assertDoesNotExist()
        assertEquals(0, registry.launchCount())
        assertEquals(0L, countRoots())

        rule.onNodeWithContentDescription(target.getString(R.string.common_back)).performClick()
        awaitRoute(ANGLES_INTRO_ROUTE)
        waitForText(historyHelperText(ATHLETE_A_NAME))
        assertEquals("Volver de la ayuda no lanza el picker.", 0, registry.launchCount())

        // Cambio REAL de atleta con el widget de la intro (selector existente).
        selectAthleteInIntro(ATHLETE_B_NAME)
        awaitStoreSelection(athleteB)
        rule.onNodeWithTag(INTRO_IMPORT_TAG).assertIsEnabled()
        assertEquals(0L, countRoots())
    }

    // --- 2. Sin selección previa: el selector REAL habilita el inicio ----------------------------

    @Test
    fun elSelectorRealHabilitaElInicioSinSeleccionPrevia() {
        mountGraph()
        openAnglesIntro()
        // Dos atletas activos y store global sin selección: nada se autoelegido.
        rule.onNodeWithTag(INTRO_IMPORT_TAG).assertIsNotEnabled()
        rule.onNodeWithTag(INTRO_RECORD_TAG).assertIsNotEnabled()
        rule.onNodeWithTag(INTRO_PENDING_REASON_TAG).assertExists()

        selectAthleteInIntro(ATHLETE_A_NAME)
        assertEquals(athleteA, introHandoff().selectedAthleteId.value)
        awaitStoreSelection(athleteA)
        // `canBegin` exige id previo: la selección NO puede depender de él (fallaría con el guard).
        rule.onNodeWithTag(INTRO_IMPORT_TAG).assertIsEnabled()
        rule.onNodeWithTag(INTRO_IMPORT_TAG).performClick()
        rule.waitUntil(TIMEOUT_MILLIS) { registry.launchCount() == 1 }
        assertEquals(ActivityResultContracts.PickVisualMedia.VideoOnly, (registry.last().input as PickVisualMediaRequest).mediaType)
    }

    // --- 3. Import: ticket congelado, URI del registro y guardado REAL en Room del atleta A -----

    @Test
    fun elImportAceptaLaUriDelTicketCongeladoYGuardaEnRoomDelAtletaA() {
        runBlocking { assertTrue(app.selectedAthleteStore.select(app.athleteRepository, athleteA)) }
        mountGraph()
        openAnglesIntro()
        rule.onNodeWithTag(INTRO_IMPORT_TAG).assertIsEnabled()
        rule.onNodeWithTag(INTRO_IMPORT_TAG).performClick()
        rule.waitUntil(TIMEOUT_MILLIS) { registry.launchCount() == 1 }

        val launch = registry.last()
        assertTrue(
            "El import debe lanzar el contrato real de foto/vídeo: ${launch.contract}",
            launch.contract is ActivityResultContracts.PickVisualMedia,
        )
        assertEquals(
            "El picker debe pedir SÓLO vídeo.",
            ActivityResultContracts.PickVisualMedia.VideoOnly,
            (launch.input as PickVisualMediaRequest).mediaType,
        )
        val ticket = awaitingTicket()
        assertEquals(athleteA, ticket.athleteId)

        // La selección GLOBAL cambia a B mientras el picker está abierto: el ticket ya congeló A.
        runBlocking { assertTrue(app.selectedAthleteStore.select(app.athleteRepository, athleteB)) }
        awaitStoreSelection(athleteB)

        dispatch(launch.requestCode, Uri.parse(videoUri))
        awaitRoute(ANGLES_EDITOR_ROUTE)
        waitForTag(ANGLE_WORKSPACE_TAG, VIDEO_TIMEOUT_MILLIS)
        assertEquals("El editor debe abrirse con el atleta CONGELADO A.", athleteA, editorAthleteId())
        assertEquals("El editor debe abrir el fixture elegido.", videoUri, editorVideoUri())

        // Sesión multi-fotograma real: captura del frame 0 con el visor y dos ángulos (API real).
        val editorEntry = requireNotNull(requireNotNull(navController).currentBackStackEntry)
        val viewModel = ViewModelProvider(editorEntry, AngleEditorViewModel.factory(EDITOR_DEFAULTS))[
            AngleEditorViewModel::class.java,
        ]
        awaitState("apertura real del vídeo en el editor", viewModel.videoState, VIDEO_TIMEOUT_MILLIS) {
            it is AngleVideoState.Ready
        }
        rule.runOnIdle { viewModel.pausePlayback() }
        awaitState("frame 0 en pausa confirmado por el visor", viewModel.displayState) { display ->
            display.canMark && display.requestedIndex == 0
        }
        val renderedPtsUs = requireNotNull(rule.runOnIdle { viewModel.displayState.value.renderedPtsUs })
        waitForTag(ANGLE_WORKSPACE_CAPTURE_TAG, TIMEOUT_MILLIS)
        val firstFrame = captureFrame(viewModel, frameIndex = 0, renderedPtsUs = renderedPtsUs)
        markAngles(viewModel, firstFrame, listOf(FIRST_ANGLE, SECOND_ANGLE))

        // Guardado REAL desde la UI del workspace (gate inyectado SOLO en test).
        assertFalse(ANGLE_MULTI_FRAME_SAVE_UI_ENABLED)
        waitForTag(ANGLE_WORKSPACE_SAVE_TAG, TIMEOUT_MILLIS)
        rule.onNodeWithTag(ANGLE_WORKSPACE_SAVE_TAG).assertIsEnabled()
        rule.mainClock.autoAdvance = false
        val settled = try {
            rule.onNodeWithTag(ANGLE_WORKSPACE_SAVE_TAG).performClick()
            awaitState("resultado del guardado real", viewModel.multiFrameSaveState, SAVE_TIMEOUT_MILLIS) {
                it is AngleMultiFrameSaveState.Saved ||
                    it is AngleMultiFrameSaveState.AlreadySaved ||
                    it is AngleMultiFrameSaveState.Failed
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
        val saved = when (settled) {
            is AngleMultiFrameSaveState.Saved -> settled
            else -> throw AssertionError("El guardado real no se completó: $settled")
        }
        rule.waitForIdle()
        awaitRoute(ANGLES_V2_SAVED_DETAIL_ROUTE)

        // Room REAL: una raíz del atleta A (nunca B) con 1 fotograma y 2 marcas verificadas.
        assertEquals(1L, countRoots())
        val aggregate = requireNotNull(
            runBlocking { app.database.angleAnalysisV2Dao().aggregateByRootId(saved.rootId) },
        ) { "La raíz ${saved.rootId} debía existir en la base del banco." }
        assertEquals(athleteA, aggregate.root.athleteId)
        assertNotEquals(athleteB, requireNotNull(aggregate.root.athleteId))
        assertEquals(1, aggregate.frames.size)
        assertEquals(2, aggregate.frames.sumOf { it.marks.size })
        val stored = aggregate.frames.single()
        assertEquals(0, stored.frame.assignedFrameIndex)
        assertEquals("El PTS guardado debe ser el que mostraba el visor real.", renderedPtsUs, stored.frame.assignedPtsUs)
        val bytes = when (
            val read = app.readVerifiedAngleImageBytes(
                imageKey = stored.frame.imageKey,
                integrity = AngleImageIntegrity(
                    byteCount = stored.frame.imageByteCount,
                    sha256Hex = stored.frame.imageSha256,
                ),
            )
        ) {
            is AngleImageReadResult.Delivered<*> -> read.value as ByteArray
            is AngleImageReadResult.Failed -> throw AssertionError(
                "El PNG del fotograma no se pudo verificar: ${read.error}",
            )
        }
        assertEquals(stored.frame.imageByteCount, bytes.size.toLong())
        assertArrayEquals(PNG_SIGNATURE, bytes.copyOfRange(0, PNG_SIGNATURE.size))
        assertEquals(
            PngPixelSize(stored.frame.imageWidth, stored.frame.imageHeight),
            pngPixelSize(bytes),
        )
        // El grant REAL se persistió para la URI del fixture y la atleta es la congelada.
        assertTrue(
            "El grant de lectura del fixture debía persistirse: ${app.videoGrantStore.persistedReadUris()}",
            app.videoGrantStore.persistedReadUris().contains(videoUri),
        )
    }

    // --- 4. Cancelación: se conserva A, no hay raíz y el segundo intento usa otro token/código ---

    @Test
    fun cancelarConservaLaSeleccionYElSegundoIntentoUsaOtroTokenYOtroCodigo() {
        runBlocking { assertTrue(app.selectedAthleteStore.select(app.athleteRepository, athleteA)) }
        mountGraph()
        openAnglesIntro()
        rule.onNodeWithTag(INTRO_IMPORT_TAG).performClick()
        rule.waitUntil(TIMEOUT_MILLIS) { registry.launchCount() == 1 }
        val firstCode = registry.last().requestCode
        val firstToken = awaitingTicket().originToken

        dispatch(firstCode, uri = null)
        awaitHandoff("cancelación del picker") { it is AngleEntryHandoffState.Idle }
        awaitRoute(ANGLES_INTRO_ROUTE)
        waitForText(historyHelperText(ATHLETE_A_NAME))
        assertEquals(0L, countRoots())
        assertTrue(app.videoGrantStore.persistedReadUris().isEmpty())

        rule.onNodeWithTag(INTRO_IMPORT_TAG).assertIsEnabled()
        rule.onNodeWithTag(INTRO_IMPORT_TAG).performClick()
        rule.waitUntil(TIMEOUT_MILLIS) { registry.launchCount() == 2 }
        val second = registry.last()
        val secondToken = awaitingTicket().originToken
        assertNotEquals("Cada intento debe tener su propio token.", firstToken, secondToken)
        assertNotEquals("Cada intento debe tener su propio requestCode.", firstCode, second.requestCode)

        // Un resultado VIEJO (código del primer intento) no puede retargetear el intento actual.
        var staleDispatch = true
        rule.runOnUiThread {
            staleDispatch =
                registry.dispatchResult(firstCode, Activity.RESULT_OK, Intent().setData(Uri.parse(videoUri)))
        }
        assertFalse("El código viejo ya no puede tener callback vivo.", staleDispatch)
        rule.waitForIdle()
        awaitRoute(ANGLES_INTRO_ROUTE)
        assertEquals(0L, countRoots())
        assertTrue(
            "Una entrega obsoleta no puede registrar el grant del fixture.",
            app.videoGrantStore.persistedReadUris().none { it == videoUri },
        )

        // El intento ACTUAL sigue siendo el único válido y abre el editor del atleta A.
        dispatch(second.requestCode, Uri.parse(videoUri))
        awaitRoute(ANGLES_EDITOR_ROUTE)
        assertEquals(athleteA, editorAthleteId())
    }

    // --- 5. Atleta archivado durante el intento: rechazo cerrado, sin fallback a B --------------

    @Test
    fun archivarElAtletaDelIntentoRechazaLaUriSinRetarget() {
        runBlocking { assertTrue(app.selectedAthleteStore.select(app.athleteRepository, athleteA)) }
        mountGraph()
        openAnglesIntro()
        rule.onNodeWithTag(INTRO_IMPORT_TAG).performClick()
        rule.waitUntil(TIMEOUT_MILLIS) { registry.launchCount() == 1 }
        val code = registry.last().requestCode

        // Sólo el atleta del banco: el atleta A se archiva mientras el picker está abierto.
        assertTrue("El atleta del banco debía archivarse.", runBlocking { app.athleteRepository.archive(athleteA) })
        rule.waitUntil(TIMEOUT_MILLIS) { introHandoff().selectedAthleteId.value == null }

        dispatch(code, Uri.parse(videoUri))
        val rejected = awaitHandoff("rechazo por atleta no disponible") {
            it is AngleEntryHandoffState.Rejected
        } as AngleEntryHandoffState.Rejected
        assertEquals(AngleEntryError.ATHLETE_UNAVAILABLE, rejected.error)
        awaitRoute(ANGLES_INTRO_ROUTE)
        waitForText(target.getString(R.string.angle_intro_select_athlete))
        assertEquals("No puede crearse ninguna raíz.", 0L, countRoots())
        rule.onNodeWithTag(ANGLE_WORKSPACE_TAG).assertDoesNotExist()
        // El lease del intento rechazado se libera: no queda un grant huérfano del fixture.
        assertTrue(
            "Tras el rechazo no puede quedar el grant: ${app.videoGrantStore.persistedReadUris()}",
            app.videoGrantStore.persistedReadUris().none { it == videoUri },
        )
        // NO hay fallback automático al atleta B: la selección queda vacía y se pide explícita.
        assertNull(introHandoff().selectedAthleteId.value)
        rule.onNodeWithTag(INTRO_IMPORT_TAG).assertIsNotEnabled()

        selectAthleteInIntro(ATHLETE_B_NAME)
        awaitStoreSelection(athleteB)
        rule.onNodeWithTag(INTRO_IMPORT_TAG).assertIsEnabled()
        rule.onNodeWithTag(INTRO_IMPORT_TAG).performClick()
        rule.waitUntil(TIMEOUT_MILLIS) { registry.launchCount() == 2 }
        assertEquals(0L, countRoots())
    }

    // --- 6. Liveness: URI vieja entregada con la ayuda delante no abre editor ni regala grant ----

    @Test
    fun unaUriTardiaConOtraEntradaDelanteNoAbreEditorNiRegalaGrant() {
        runBlocking { assertTrue(app.selectedAthleteStore.select(app.athleteRepository, athleteA)) }
        mountGraph()
        openAnglesIntro()
        rule.onNodeWithTag(INTRO_IMPORT_TAG).performClick()
        rule.waitUntil(TIMEOUT_MILLIS) { registry.launchCount() == 1 }
        val code = registry.last().requestCode

        rule.onNodeWithTag(INTRO_HELP_TAG).performClick()
        awaitRoute(ANGLES_HELP_ROUTE)
        // La entrada de la intro YA no está delante: su callback no puede completar el intento.
        dispatch(code, Uri.parse(videoUri))
        awaitHandoff("descarte del intento pendiente por entrada inactiva") {
            it is AngleEntryHandoffState.Idle
        }
        rule.waitForIdle()
        awaitRoute(ANGLES_HELP_ROUTE)
        assertEquals("No puede abrirse el editor desde la ayuda.", 0L, countRoots())
        rule.onNodeWithTag(ANGLE_WORKSPACE_TAG).assertDoesNotExist()
        assertTrue(
            "La URI no entregada no puede registrar grant: ${app.videoGrantStore.persistedReadUris()}",
            app.videoGrantStore.persistedReadUris().none { it == videoUri },
        )
        assertFalse(requireNotNull(navController).currentBackStack.value.any { it.destination.route == ANGLES_EDITOR_ROUTE })

        // Recuperable: al volver a la intro se puede reintentar y el registro vuelve a lanzarse.
        rule.onNodeWithContentDescription(target.getString(R.string.common_back)).performClick()
        awaitRoute(ANGLES_INTRO_ROUTE)
        rule.onNodeWithTag(INTRO_IMPORT_TAG).assertIsEnabled()
        rule.onNodeWithTag(INTRO_IMPORT_TAG).performClick()
        rule.waitUntil(TIMEOUT_MILLIS) { registry.launchCount() == 2 }
        assertEquals(0L, countRoots())
    }

    // --- 7. Ready aceptado con la intro delante se retiene en la ayuda y navega al volver --------

    @Test
    fun elReadyRetenidoEnLaAyudaNavegaAlEditorSoloAlVolverALaIntro() {
        runBlocking { assertTrue(app.selectedAthleteStore.select(app.athleteRepository, athleteA)) }
        mountGraph()
        openAnglesIntro()
        rule.onNodeWithTag(INTRO_IMPORT_TAG).performClick()
        rule.waitUntil(TIMEOUT_MILLIS) { registry.launchCount() == 1 }
        val code = registry.last().requestCode

        // URI aceptada con la intro CURRENT y, en el MISMO turno de UI, navegación a la ayuda:
        // el trabajo de IO del actor termina después y el Ready NO puede navegar desde la ayuda.
        rule.runOnUiThread {
            check(registry.dispatchResult(code, Activity.RESULT_OK, Intent().setData(Uri.parse(videoUri))))
            requireNotNull(navController).navigate(ANGLES_HELP_ROUTE)
        }
        awaitRoute(ANGLES_HELP_ROUTE)
        val ready = awaitHandoff("Ready retenido mientras la ayuda está delante") {
            it is AngleEntryHandoffState.Ready
        } as AngleEntryHandoffState.Ready
        assertEquals(athleteA, ready.transfer.athleteId)
        assertEquals(videoUri, ready.transfer.uri)
        rule.waitForIdle()
        rule.waitForIdle()
        awaitRoute(ANGLES_HELP_ROUTE)
        assertFalse(
            "El editor no puede abrirse con la ayuda delante.",
            requireNotNull(navController).currentBackStack.value.any { it.destination.route == ANGLES_EDITOR_ROUTE },
        )

        // Al volver a la intro RESUMED se reclama UNA vez y se abre el editor del atleta A.
        rule.onNodeWithContentDescription(target.getString(R.string.common_back)).performClick()
        awaitRoute(ANGLES_INTRO_ROUTE)
        awaitRoute(ANGLES_EDITOR_ROUTE)
        val routes = requireNotNull(navController).currentBackStack.value.mapNotNull { it.destination.route }
        assertEquals("El editor debe abrirse exactamente una vez: $routes", 1, routes.count { it == ANGLES_EDITOR_ROUTE })
        assertEquals(athleteA, editorAthleteId())
        assertEquals(videoUri, editorVideoUri())
        assertEquals(0L, countRoots())
    }

    // --- 8. Back del SISTEMA desde la cámara standalone: cancela el intento de grabación ---------

    /**
     * Cámara REAL en BLOCKED (permiso de framework denegado SÓLO en el contexto de test): nunca se
     * pide permiso al OS, no se configura ni bindea CameraX y el registro de Activity Result captura
     * el lanzamiento. El Back es el del SISTEMA (`onBackPressedDispatcher`), no un botón de la UI.
     */
    @Test
    fun elBackDelSistemaEnLaCamaraStandaloneCancelaElIntentoDeGrabacion() {
        runBlocking { assertTrue(app.selectedAthleteStore.select(app.athleteRepository, athleteA)) }
        denyCameraPermission = true
        mountGraph()
        openAnglesIntro()
        rule.onNodeWithTag(INTRO_RECORD_TAG).assertIsEnabled()
        rule.onNodeWithTag(INTRO_RECORD_TAG).performClick()
        awaitCameraEntryResumed()
        waitForText(target.getString(R.string.camera_state_no_permission))
        rule.waitUntil(TIMEOUT_MILLIS) {
            registry.captures().any { it.contract is ActivityResultContracts.RequestMultiplePermissions }
        }
        assertTrue(
            "La cámara BLOCKED no puede lanzar el picker ni abrir el diálogo del OS en el test.",
            registry.captures().none { it.contract is ActivityResultContracts.PickVisualMedia },
        )
        assertEquals(AngleEntryKind.RECORD, awaitingTicket().kind)

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        awaitHandoff("cancelación del intento RECORD por Back del sistema") {
            it is AngleEntryHandoffState.Idle
        }
        awaitRoute(ANGLES_INTRO_ROUTE)
        waitForTag(INTRO_ROOT_TAG, TIMEOUT_MILLIS)

        // El atleta congelado sigue elegido y la intro vuelve a ofrecer AMBOS inicios.
        assertEquals(athleteA, introHandoff().selectedAthleteId.value)
        assertEquals(athleteA, app.selectedAthleteStore.selectedAthleteId.value)
        rule.onNodeWithTag(INTRO_RECORD_TAG).assertIsEnabled()
        rule.onNodeWithTag(INTRO_IMPORT_TAG).assertIsEnabled()
        assertEquals("Sin grabación no puede haber raíz.", 0L, countRoots())
        assertTrue(
            "Sin URI aceptada no puede registrarse ningún grant: ${app.videoGrantStore.persistedReadUris()}",
            app.videoGrantStore.persistedReadUris().isEmpty(),
        )
        assertFalse(
            "La cámara no puede seguir en la pila tras el Back.",
            requireNotNull(navController).currentBackStack.value.any { it.destination.route == ANGLES_CAMERA_ROUTE },
        )
    }

    /**
     * Mismo escenario BLOCKED: se usa el fallback de importación REAL de la cámara y, en el MISMO
     * turno de UI, se entrega la URI y se pulsa Back antes de que el actor publique Ready (el
     * trabajo de IO sigue pendiente). El Ready tardío no puede abrir el editor ni dejar el intento
     * vivo; el grant del fixture se libera.
     */
    @Test
    fun elBackDelSistemaDuranteLaAdquisicionNoReabreElEditorNiDejaElIntentoVivo() {
        runBlocking { assertTrue(app.selectedAthleteStore.select(app.athleteRepository, athleteA)) }
        denyCameraPermission = true
        mountGraph()
        openAnglesIntro()
        rule.onNodeWithTag(INTRO_RECORD_TAG).performClick()
        awaitCameraEntryResumed()
        waitForText(target.getString(R.string.camera_state_no_permission))

        // Fallback de importación de la UI de error: mismo ticket RECORD congelado.
        rule.onAllNodesWithText(target.getString(R.string.common_import_video)).onFirst().performClick()
        rule.waitUntil(TIMEOUT_MILLIS) {
            registry.captures().any { it.contract is ActivityResultContracts.PickVisualMedia }
        }
        val pick = registry.captures().last { it.contract is ActivityResultContracts.PickVisualMedia }
        assertEquals(
            "El fallback debe pedir SÓLO vídeo.",
            ActivityResultContracts.PickVisualMedia.VideoOnly,
            (pick.input as PickVisualMediaRequest).mediaType,
        )
        assertEquals(AngleEntryKind.RECORD, awaitingTicket().kind)

        rule.runOnUiThread {
            check(registry.dispatchResult(pick.requestCode, Activity.RESULT_OK, Intent().setData(Uri.parse(videoUri))))
            rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        awaitHandoff("cierre del intento abandonado durante la adquisición") {
            it is AngleEntryHandoffState.Idle
        }
        awaitRoute(ANGLES_INTRO_ROUTE)
        waitForTag(INTRO_ROOT_TAG, TIMEOUT_MILLIS)
        rule.onNodeWithTag(INTRO_RECORD_TAG).assertIsEnabled()
        rule.onNodeWithTag(INTRO_IMPORT_TAG).assertIsEnabled()
        assertEquals(athleteA, introHandoff().selectedAthleteId.value)
        assertEquals(0L, countRoots())
        assertTrue(
            "El grant del fixture debe liberarse: ${app.videoGrantStore.persistedReadUris()}",
            app.videoGrantStore.persistedReadUris().none { it == videoUri },
        )
        assertFalse(
            "El editor no puede estar en la pila ni abrirse tarde: " +
                "${requireNotNull(navController).currentBackStack.value.mapNotNull { it.destination.route }}",
            requireNotNull(navController).currentBackStack.value.any { it.destination.route == ANGLES_EDITOR_ROUTE },
        )
        // Margen acotado: un Ready tardío tampoco puede navegar después del abandono.
        Thread.sleep(1_000)
        rule.waitForIdle()
        awaitRoute(ANGLES_INTRO_ROUTE)
        assertFalse(
            "Un Ready tardío no puede abrir el editor tras abandonar.",
            requireNotNull(navController).currentBackStack.value.any { it.destination.route == ANGLES_EDITOR_ROUTE },
        )
        assertEquals(0L, countRoots())
    }

    // --- montaje del grafo con la frontera simulada ---------------------------------------------

    /**
     * Monta UNA sola vez el grafo real. El contexto, el dueño de ViewModels con `APPLICATION_KEY` y
     * el dueño del registro se derivan de la app aislada; el registro captura los lanzamientos y no
     * abre ninguna actividad externa.
     */
    private fun mountGraph() {
        rule.setContent {
            val current = mountedApp.value
            if (current != null) {
                val mountedUiContext = remember(current) {
                    val base = current.newUiContext(rule.activity)
                    if (denyCameraPermission) DeniedCameraPermissionUiContext(base) else base
                }
                val owner = remember(current) { current.newDefaultViewModelOwner(rule.activity) }
                val resultOwner = remember { SandboxResultRegistryOwner(registry) }
                CompositionLocalProvider(
                    LocalContext provides mountedUiContext,
                    LocalViewModelStoreOwner provides owner,
                    LocalActivityResultRegistryOwner provides resultOwner,
                ) {
                    OpenJumpTheme {
                        val controller = rememberNavController()
                        SideEffect {
                            navController = controller
                            uiContext = mountedUiContext
                        }
                        OpenJumpNavHost(
                            navController = controller,
                            angleMultiFrameSaveUiEnabled = true,
                        )
                    }
                }
            }
        }
        rule.runOnUiThread { mountedApp.value = app }
        rule.waitForIdle()
    }

    private fun openAnglesIntro() {
        awaitRoute(HOME_ROUTE)
        val label = target.getString(R.string.home_angles_title)
        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodes(hasText(label) and hasClickAction()).onFirst().performScrollTo().performClick()
        awaitRoute(ANGLES_INTRO_ROUTE)
        waitForTag(INTRO_ROOT_TAG, TIMEOUT_MILLIS)
    }

    // --- entrada: atletas, tickets y registro --------------------------------------------------

    private fun selectAthleteInIntro(displayName: String) {
        rule.onNodeWithTag(INTRO_ATHLETE_TAG).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText(displayName).onFirst().performClick()
        rule.waitForIdle()
    }

    private fun historyHelperText(displayName: String): String =
        target.getString(R.string.angle_intro_history_helper, displayName)

    private fun introHandoff(): AngleEntryHandoffViewModel = rule.runOnIdle {
        val entry = requireNotNull(requireNotNull(navController).getBackStackEntry(ANGLES_INTRO_ROUTE))
        ViewModelProvider(entry, AngleEntryHandoffViewModel.Factory)[AngleEntryHandoffViewModel::class.java]
    }

    private fun awaitingTicket(): AngleEntryTicket {
        val state = introHandoff().state.value
        assertTrue("El intento debía estar esperando captura: $state", state is AngleEntryHandoffState.AwaitingCapture)
        return (state as AngleEntryHandoffState.AwaitingCapture).ticket
    }

    private fun awaitStoreSelection(expected: Long) {
        rule.waitUntil(TIMEOUT_MILLIS) { app.selectedAthleteStore.selectedAthleteId.value == expected }
    }

    /** Entrega un resultado en el hilo principal al registro REAL capturado. */
    private fun dispatch(code: Int, uri: Uri?) {
        rule.runOnUiThread {
            val intent = uri?.let { Intent().setData(it) }
            val resultCode = if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK
            // El callback debe seguir registrado: el registro es el REAL de Compose.
            assertTrue("El registro debía tener callback vivo para el código $code.", registry.dispatchResult(code, resultCode, intent))
        }
        rule.waitForIdle()
    }

    private fun editorAthleteId(): Long = rule.runOnIdle {
        requireNotNull(requireNotNull(navController).currentBackStackEntry)
            .arguments?.getString(ANGLES_ATHLETE_ID_ARG)?.toLongOrNull() ?: 0L
    }

    private fun editorVideoUri(): String = rule.runOnIdle {
        requireNotNull(requireNotNull(navController).currentBackStackEntry)
            .arguments?.getString(ANGLES_VIDEO_URI_ARG).orEmpty()
    }

    // --- editor: captura, marcado por API real y esperas ---------------------------------------

    private fun captureFrame(viewModel: AngleEditorViewModel, frameIndex: Int, renderedPtsUs: Long): AngleFrameId {
        rule.onNodeWithTag(ANGLE_WORKSPACE_CAPTURE_TAG).performClick()
        val state = awaitReadySession("captura del frame $frameIndex", viewModel) { current ->
            val item = current.frames.firstOrNull { it.anchor.frameIndex == frameIndex }
            item != null && (item.canEdit || item.captureFailure != null)
        }
        val item = state.frames.first { it.anchor.frameIndex == frameIndex }
        item.captureFailure?.let { failure ->
            throw AssertionError("La captura del frame $frameIndex falló: ${failure.reason} ${failure.detail}")
        }
        assertEquals("El ancla debe guardar el PTS EXACTO que mostraba el visor.", renderedPtsUs, item.anchor.sourcePtsUs)
        return item.frameId
    }

    /** Misma secuencia de ACCIONES que `markAngle` del arnés, sin dobles de indexador ni visor. */
    private fun markAngles(
        viewModel: AngleEditorViewModel,
        frameId: AngleFrameId,
        angles: List<List<AnglePoint>>,
    ) {
        apply("selección del frame $frameId") { viewModel.selectMultiFrame(frameId) }
        angles.forEachIndexed { angleIndex, points ->
            points.forEachIndexed { pointIndex, point ->
                apply("punto ${pointIndex + 1} del ángulo ${angleIndex + 1}") {
                    viewModel.placeMultiFramePoint(frameId, point)
                }
            }
        }
    }

    private fun apply(what: String, action: () -> AngleMultiFrameResult) {
        when (val result = rule.runOnIdle(action)) {
            is AngleMultiFrameResult.Applied -> Unit
            is AngleMultiFrameResult.Rejected ->
                throw AssertionError("La operación «$what» se rechazó: ${result.rejection}")
        }
    }

    // --- esperas acotadas sobre estado real ----------------------------------------------------

    private fun <T> awaitState(
        what: String,
        flow: StateFlow<T>,
        timeoutMillis: Long = TIMEOUT_MILLIS,
        predicate: (T) -> Boolean,
    ): T = flow.awaitValueBlocking(what = what, timeoutMillis = timeoutMillis) { predicate(it) }

    private fun awaitHandoff(
        what: String,
        predicate: (AngleEntryHandoffState) -> Boolean,
    ): AngleEntryHandoffState = introHandoff().state.awaitValueBlocking(
        what = what,
        timeoutMillis = TIMEOUT_MILLIS,
    ) { predicate(it) }

    private fun awaitReadySession(
        what: String,
        viewModel: AngleEditorViewModel,
        timeoutMillis: Long = TIMEOUT_MILLIS,
        predicate: (AngleMultiFrameSessionState) -> Boolean,
    ): AngleMultiFrameSessionState {
        val ready = awaitState("«$what»", viewModel.multiFrameState, timeoutMillis) { current ->
            current is AngleMultiFrameState.Ready && predicate(current.state)
        } as AngleMultiFrameState.Ready
        return ready.state
    }

    private fun awaitRoute(route: String, timeoutMillis: Long = TIMEOUT_MILLIS) {
        rule.waitUntil(timeoutMillis) { navController?.currentDestination?.route == route }
    }

    /** La entrada de la cámara está delante y RESUMED: único estado en el que su Back puede actuar. */
    private fun awaitCameraEntryResumed() {
        rule.waitUntil(TIMEOUT_MILLIS) {
            navController?.currentDestination?.route == ANGLES_CAMERA_ROUTE &&
                navController?.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED
        }
    }

    private fun waitForTag(tag: String, timeoutMillis: Long) {
        rule.waitUntil(timeoutMillis) {
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String, timeoutMillis: Long = TIMEOUT_MILLIS) {
        rule.waitUntil(timeoutMillis) {
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun countRoots(): Long =
        app.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM angle_analysis_v2_roots")
            .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    // --- frontera simulada: registro de Activity Result -----------------------------------------

    /** Registro real de `ActivityResultRegistry` con `onLaunch` capturado (no abre actividades). */
    private class SandboxResultRegistry : ActivityResultRegistry() {
        private val captures = CopyOnWriteArrayList<Capture>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            captures += Capture(requestCode, contract, input)
        }

        fun launchCount(): Int = captures.size

        fun last(): Capture = captures.last()

        fun captures(): List<Capture> = captures.toList()

        data class Capture(
            val requestCode: Int,
            val contract: ActivityResultContract<*, *>,
            val input: Any?,
        )
    }

    private class SandboxResultRegistryOwner(
        override val activityResultRegistry: ActivityResultRegistry,
    ) : ActivityResultRegistryOwner

    /**
     * `Context` de UI SOLO de test que deniega el permiso de framework CAMERA. Delega el resto en
     * el [SandboxUiContext] real (que conserva `getApplicationContext` = app aislada), así la
     * cámara de producción compone su estado BLOCKED sin diálogo del OS ni CameraX.
     */
    private class DeniedCameraPermissionUiContext(
        base: SandboxUiContext,
    ) : ContextWrapper(base) {
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.CAMERA) {
                PackageManager.PERMISSION_DENIED
            } else {
                super.checkPermission(permission, pid, uid)
            }

        override fun checkSelfPermission(permission: String): Int =
            if (permission == Manifest.permission.CAMERA) {
                PackageManager.PERMISSION_DENIED
            } else {
                super.checkSelfPermission(permission)
            }
    }

    private companion object {
        const val FIXTURE_ASSET = "angles/ordinal_cfr_bframes.mp4"

        const val INTRO_ROOT_TAG = "angle-intro-root"
        const val INTRO_RECORD_TAG = "angle-intro-record"
        const val INTRO_IMPORT_TAG = "angle-intro-import"
        const val INTRO_ATHLETE_TAG = "angle-intro-athlete"
        const val INTRO_HELP_TAG = "angle-intro-help"
        const val INTRO_PENDING_REASON_TAG = "angle-intro-pending-reason"
        const val HOW_ROOT_TAG = "angle-how-it-works-root"

        const val TIMEOUT_MILLIS = 30_000L
        const val VIDEO_TIMEOUT_MILLIS = 60_000L
        const val SAVE_TIMEOUT_MILLIS = 60_000L
        const val ARCHIVED_AT = 1_700_000_000_000L

        const val ATHLETE_A_NAME = "Entrada A"
        const val ATHLETE_B_NAME = "Entrada B"

        /** Mismos valores de UI que `AngleEditorScreen` construye en `AppNav`. */
        val EDITOR_DEFAULTS = AngleEditorDefaults(
            namePrefix = "Ángulo",
            colorArgb = 0xFFE53935.toInt(),
        )

        val FIRST_ANGLE = listOf(
            AnglePoint(20.0, 20.0),
            AnglePoint(80.0, 20.0),
            AnglePoint(80.0, 80.0),
        )

        val SECOND_ANGLE = listOf(
            AnglePoint(40.0, 40.0),
            AnglePoint(100.0, 60.0),
            AnglePoint(60.0, 100.0),
        )

        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            'P'.code.toByte(),
            'N'.code.toByte(),
            'G'.code.toByte(),
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}
