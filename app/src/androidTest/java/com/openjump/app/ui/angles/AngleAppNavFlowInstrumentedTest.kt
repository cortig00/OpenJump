package com.openjump.app.ui.angles

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.IntentCompat
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
import com.openjump.app.ui.ANGLES_EDITOR_ROUTE
import com.openjump.app.ui.ANGLES_V2_SAVED_DETAIL_ROUTE
import com.openjump.app.ui.HOME_ROUTE
import com.openjump.app.ui.OpenJumpNavHost
import com.openjump.app.ui.anglesEditorRoute
import com.openjump.app.ui.theme.OpenJumpTheme
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Flujo de navegación REAL de Ángulos multi-fotograma sobre el banco aislado
 * ([AngleNavigationSandbox]): `OpenJumpNavHost` completo, `AngleEditorViewModel.factory` real, Room
 * real con nombre UUID y todas las piezas de producción (viewer/transporte Media3, indexador,
 * `FrozenFrameSources`, renderer de PNG y repositorio V2). La ÚNICA frontera simulada son los
 * permisos de vídeo, que el banco resuelve en memoria (nunca `ContentResolver`).
 *
 * Qué demuestra:
 * 1. Navegación por el grafo real (`HOME` → `anglesEditorRoute` → editor) y el `ViewModel` de la
 *    entrada de navegación existente, obtenido con `ViewModelProvider(entry, factory)` **después**
 *    de que la pantalla lo haya creado.
 * 2. Captura de dos fotogramas (índices 0 y 5) con el visor REAL en pantalla y confirmación en
 *    pausa (`canMark`), apertura de la sesión multi-fotograma y tres ángulos (dos en el frame 0, uno
 *    en el 5) colocados con la API real del `ViewModel`, copiando la secuencia de ACCIONES de `markAngle` del arnés
 *    pero SIN sustituir indexador ni visor.
 * 3. Click real en [ANGLE_MULTI_FRAME_ENTRY_TAG] y en [ANGLE_MULTI_FRAME_SAVE_TAG] con el gate de
 *    producto inyectado SOLO en el test (`angleMultiFrameSaveUiEnabled = true`; la constante de
 *    producción sigue `false`): el guardado real termina, la ruta de la app pasa a
 *    `angles/v2/saved/{rootId}`, el editor ya no está en la pila y la base aislada tiene una raíz,
 *    dos fotogramas y tres marcas con sus PNG verificados por `readVerifiedAngleImageBytes`.
 * 4. Después de borrar SOLO la copia propia del vídeo, se desmonta el grafo, se vacía el almacén de
 *    ViewModels de la Activity, se cierran las bases y se vuelve a montar una app del MISMO banco
 *    (mismo nombre UUID, Room reabierta), se navega de verdad por la barra a Historial, se pulsa la
 *    ÚNICA fila de ángulos (su overline es el `type` real de la presentación, `history_type_angle`:
 *    HISTORY no proyecta el literal interno `ANGLE_V2`) y en el detalle real se comparte un
 *    fotograma: el `FileProvider` real entrega el PNG y el `chooser` se captura en
 *    [SandboxUiContext].
 *
 * Límites declarados (no se afirma más de lo que se ejercita):
 * - La navegación al editor usa el helper real [anglesEditorRoute], no el selector de vídeo de
 *   `Home` ni el `AppNav` externo (`MainActivity`), que abriría la base del usuario.
 * - Las marcas y capturas se preparan por API del `ViewModel` (preparación programática); el test NO
 *   demuestra gestos de marcado ni la navegación interna del selector.
 * - El remontaje reabre Room en el mismo proceso; NO se simula muerte de proceso.
 * - Compartir termina en el `chooser` capturado: no hay receptor externo real.
 *
 * Aislamiento: el banco escribe solo bajo su raíz UUID y su base `angle-nav-<uuid>.db`. El gate de
 * producción no se modifica y no se revoca ningún permiso real.
 *
 * Argumentos de instrumentación opcionales (sin argumentos el test se comporta exactamente igual):
 * - `anglesUiFixture`: `ordinal_cfr_bframes.mp4` (por defecto) o `ordinal_rot90.mp4`; cualquier otro
 *   valor cae al fixture por defecto.
 * - `anglesUiInspectMs`: pausa de inspección visual de 0..300000 ms tras confirmarse el frame 0 en
 *   pausa. Al terminar SIEMPRE se reanuda el flujo completo (pausa y frame 0 restablecidos con el
 *   transporte real) y se ejecutan todas las aserciones previas.
 *
 * Los nombres de los `@Test` deben ser `camelCase` sin espacios (restricción de DEX con minSdk 26).
 */
@RunWith(AndroidJUnit4::class)
class AngleAppNavFlowInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext

    private lateinit var sandbox: AngleNavigationSandbox
    private lateinit var app: AngleNavigationSandbox.SandboxedApp
    private lateinit var sourceFile: File
    private lateinit var videoUri: String

    /** Asset de fixture efectivo: por defecto [FIXTURE_ASSET], o uno seleccionable por argumento. */
    private lateinit var fixtureAsset: String
    private var athleteId: Long = 0L

    /** App montada en el grafo; ponerla a `null` desmonta la composición (una sola `setContent`). */
    private val mountedApp = mutableStateOf<AngleNavigationSandbox.SandboxedApp?>(null)

    /** Controlador y contexto de UI de la composición vigente, refrescados en cada montaje. */
    @Volatile
    private var navController: NavHostController? = null

    @Volatile
    private var uiContext: SandboxUiContext? = null

    @Before
    fun setUp() {
        sandbox = AngleNavigationSandbox(target)
        app = sandbox.newApp()
        // Onboarding completo ANTES de montar: el grafo arranca en HOME, sin pasar por el asistente.
        app.onboardingPreferencesStore.completeOnboarding()
        athleteId = requireNotNull(
            runBlocking { app.athleteRepository.bootstrapIfInstallationEmpty() },
        ) { "El banco debía crear su atleta de arranque aislado." }.id
        // Fixture propio: copia del asset de test a la raíz del banco. La URI es `file://` real.
        // El argumento `anglesUiFixture` solo puede elegir entre los fixtures permitidos.
        val requestedFixture = InstrumentationRegistry.getArguments()
            .getString(ARG_UI_FIXTURE)
            ?.trim()
            ?.takeIf { it in SELECTABLE_FIXTURES }
        fixtureAsset = requestedFixture?.let { FIXTURE_DIR + it } ?: FIXTURE_ASSET
        sourceFile = File(sandbox.ownedFilesDir, "app-nav-${sandbox.uuid}.mp4")
        instrumentation.context.assets.open(fixtureAsset).use { input ->
            sourceFile.outputStream().use { output -> input.copyTo(output) }
        }
        videoUri = Uri.fromFile(sourceFile).toString()
        rule.runOnUiThread { mountedApp.value = app }
    }

    @After
    fun tearDown() {
        runCatching { rule.runOnUiThread { mountedApp.value = null } }
        runCatching { rule.waitForIdle() }
        runCatching { sandbox.closeDatabases() }
        // Solo el fixture de este test: ningún artefacto del banco se borra en bloque.
        runCatching { sourceFile.delete() }
    }

    @Test
    fun grafoRealGuardaDosFramesNavegaAlDetalleV2YReabreHistorialCompartiendoElPng() {
        assertFalse(
            "El test no puede activar el gate de producción.",
            ANGLE_MULTI_FRAME_SAVE_UI_ENABLED,
        )
        mountGraph()

        // --- 1. HOME real y entrada al editor con el helper de ruta real ------------------------
        awaitRoute(HOME_ROUTE)
        rule.runOnUiThread { requireNotNull(navController).navigate(anglesEditorRoute(videoUri, athleteId)) }
        awaitRoute(ANGLES_EDITOR_ROUTE)
        // La pantalla ya compuso y creó SU ViewModel; el workspace nuevo es su superficie real en
        // producto (gate inyectado + análisis nuevo por la ruta), sin entrada legacy intermedia.
        waitForTag(ANGLE_WORKSPACE_TAG, VIDEO_TIMEOUT_MILLIS)

        val entry = rule.runOnIdle {
            requireNotNull(requireNotNull(navController).currentBackStackEntry)
        }
        val viewModel = ViewModelProvider(entry, AngleEditorViewModel.factory(EDITOR_DEFAULTS))[
            AngleEditorViewModel::class.java,
        ]
        // Es el ViewModel EXISTENTE de la entrada: si fuese uno nuevo sin `open()`, no habría Ready.
        awaitState("apertura real del vídeo en el editor", viewModel.videoState) {
            it is AngleVideoState.Ready
        }
        rule.runOnIdle { viewModel.pausePlayback() }
        awaitDisplay("frame 0 en pausa confirmado por el visor real", viewModel) { display ->
            display.canMark && display.requestedIndex == 0
        }

        // Hook de inspección visual, inerte sin argumentos: la UI real se queda en pantalla y
        // después el flujo continúa íntegro (no hay retorno anticipado ni verde vacío).
        holdForVisualInspectionIfRequested(viewModel)

        // --- 2. Visor en pantalla: dos frames capturados y tres ángulos por la API real --------
        // El workspace prepara la sesión por sí solo (LaunchedEffect sobre el vídeo listo): no se
        // abre a mano y la ausencia de fotogramas ANTES de Medir sigue siendo estado real.
        awaitReadySession("preparación automática de la sesión multi-fotograma", viewModel) {
            it.frames.isEmpty()
        }
        val firstFrame = captureFrame(viewModel, frameIndex = 0)

        // La foto congelada desmonta la superficie de vídeo: antes de avanzar se vuelve al vídeo
        // por la baldosa REAL de la tira (la misma acción de producto que hace el usuario).
        waitForTag(ANGLE_WORKSPACE_VIDEO_TILE_TAG, TIMEOUT_MILLIS)
        rule.onNodeWithTag(ANGLE_WORKSPACE_VIDEO_TILE_TAG).performClick()
        awaitReadySession("vuelta al vídeo en directo desde la baldosa real", viewModel) {
            it.activeFrameId == null
        }
        val stepped = rule.runOnIdle { viewModel.stepFrames(5) }
        assertTrue("El transporte real debía aceptar el avance de 5 frames.", stepped)
        awaitDisplay("frame 5 en pausa confirmado por el visor real", viewModel) { display ->
            display.canMark && display.requestedIndex == 5
        }
        val secondFrame = captureFrame(viewModel, frameIndex = 5)
        assertNotEquals(
            "Los dos fotogramas capturados deben ser entradas distintas de la sesión.",
            firstFrame,
            secondFrame,
        )

        // Copia de las ACCIONES de `markAngle` del arnés: seleccionar y colocar A → B → C.
        markAngles(viewModel, firstFrame, listOf(FIRST_ANGLE, SECOND_ANGLE))
        markAngles(viewModel, secondFrame, listOf(THIRD_ANGLE))
        awaitReadySession("dos ángulos en el frame 0 y uno en el frame 5", viewModel) { state ->
            state.frames.size == 2 &&
                state.frames.firstOrNull { it.frameId == firstFrame }?.editor?.angles?.size == 2 &&
                state.frames.firstOrNull { it.frameId == secondFrame }?.editor?.angles?.size == 1
        }

        // --- 3. Guardado real desde la UI del workspace (sin paso de entrada legacy) -----------
        waitForTag(ANGLE_WORKSPACE_SAVE_TAG, TIMEOUT_MILLIS)
        rule.onNodeWithTag(ANGLE_WORKSPACE_SAVE_TAG).assertIsEnabled()

        // El progreso del guardado es una animación indefinida: se pausa el reloj de test mientras
        // la transacción corre y se reanuda SIEMPRE al terminar (o al fallar) para no sincronizar
        // Compose contra esa animación.
        rule.mainClock.autoAdvance = false
        val settled = try {
            rule.onNodeWithTag(ANGLE_WORKSPACE_SAVE_TAG).performTouchInput { click() }
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
        assertEquals("El guardado debía publicar los dos fotogramas.", 2, saved.frameCount)
        rule.waitForIdle()

        // Navegación REAL tras el commit: la app abre la ruta V2 y el editor sale de la pila.
        awaitRoute(ANGLES_V2_SAVED_DETAIL_ROUTE)
        val routes = rule.runOnIdle {
            requireNotNull(navController).currentBackStack.value.mapNotNull { it.destination.route }
        }
        assertFalse(
            "El editor no puede seguir en la pila tras guardar: $routes",
            routes.contains(ANGLES_EDITOR_ROUTE),
        )
        assertEquals(ANGLES_V2_SAVED_DETAIL_ROUTE, routes.last())

        // --- 4. Room aislada: una raíz, dos fotogramas, tres marcas y sus PNG verificados ------
        val aggregate = requireNotNull(
            runBlocking { app.database.angleAnalysisV2Dao().aggregateByRootId(saved.rootId) },
        ) { "La raíz ${saved.rootId} debía existir en la base del banco." }
        assertEquals(1L, countRows("angle_analysis_v2_roots"))
        assertEquals(2, aggregate.frames.size)
        assertEquals(3, aggregate.frames.sumOf { it.marks.size })
        assertEquals(athleteId, requireNotNull(aggregate.root.athleteId))

        aggregate.frames.forEach { stored ->
            val frame = stored.frame
            val bytes = when (
                val read = app.readVerifiedAngleImageBytes(
                    imageKey = frame.imageKey,
                    integrity = AngleImageIntegrity(
                        byteCount = frame.imageByteCount,
                        sha256Hex = frame.imageSha256,
                    ),
                )
            ) {
                is AngleImageReadResult.Delivered<*> -> read.value as ByteArray
                is AngleImageReadResult.Failed -> throw AssertionError(
                    "El PNG del fotograma ${frame.assignedFrameIndex} no se pudo verificar: ${read.error}",
                )
            }
            assertEquals(frame.imageByteCount, bytes.size.toLong())
            assertArrayEquals(
                "La imagen guardada debe ser un PNG, no el vídeo.",
                PNG_SIGNATURE,
                bytes.copyOfRange(0, PNG_SIGNATURE.size),
            )
            assertEquals(
                PngPixelSize(frame.imageWidth, frame.imageHeight),
                pngPixelSize(bytes),
            )
            // Los puntos usados estaban dentro del frame guardado (320x240 en el fixture por defecto,
            // 240x320 si `anglesUiFixture` selecciona el fixture rotado).
            stored.marks.forEach { mark ->
                assertTrue(
                    "La marca ${mark.ordinal} debe caer dentro del PNG guardado.",
                    mark.pointAX in 0.0..frame.imageWidth.toDouble() &&
                        mark.pointAY in 0.0..frame.imageHeight.toDouble() &&
                        mark.pointBX in 0.0..frame.imageWidth.toDouble() &&
                        mark.pointBY in 0.0..frame.imageHeight.toDouble() &&
                        mark.pointCX in 0.0..frame.imageWidth.toDouble() &&
                        mark.pointCY in 0.0..frame.imageHeight.toDouble(),
                )
            }
        }

        // Solo el fixture PROPIO se borra: el detalle/historial no debe necesitar el vídeo.
        assertTrue("La copia propia del fixture debía borrarse.", sourceFile.delete())
        assertFalse(sourceFile.exists())

        // --- 5. Reapertura en el mismo banco: Historial real → fila V2 → detalle → compartir ----
        rule.runOnUiThread { mountedApp.value = null }
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.viewModelStore.clear() }
        sandbox.closeDatabases()
        val previousNav = rule.runOnIdle { navController }
        app = rule.runOnIdle { sandbox.newApp() }
        assertEquals(
            "La app reabierta conserva el onboarding del banco.",
            com.openjump.app.settings.OnboardingCompletion.COMPLETED,
            app.onboardingPreferencesStore.completion.value,
        )
        rule.runOnUiThread { mountedApp.value = app }
        rule.waitForIdle()
        rule.waitUntil(TIMEOUT_MILLIS) {
            navController !== previousNav && navController?.currentDestination?.route == HOME_ROUTE
        }

        // Barra inferior real del grafo; la ruta concreta es privada en `AppNav`, el tag no.
        rule.onNodeWithTag(BOTTOM_NAV_HISTORY_TAG).performClick()
        // El overline de la fila es el `type` REAL de `historyEntryPresentation` (rama Angle):
        // `history_type_angle`. Una raíz V2 no persiste vídeo ni tiene copy propio, así que HISTORY
        // no muestra nunca el literal interno `ANGLE_V2`: se sondea el texto localizado de la app.
        val historyAngleTypeLabel = target.getString(R.string.history_type_angle)
        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.onAllNodesWithText(historyAngleTypeLabel).fetchSemanticsNodes().isNotEmpty()
        }
        // Solo hay una raíz V2 guardada y ninguna fila legacy: el rótulo debe identificar UNA fila
        // y el clic se hace sobre ese MISMO nodo, sin volver a resolverlo por posición.
        val angleRows = rule.onAllNodesWithText(historyAngleTypeLabel)
        assertEquals(
            "El Historial debía renderizar una única fila de ángulos ($historyAngleTypeLabel).",
            1,
            angleRows.fetchSemanticsNodes().size,
        )
        angleRows.onFirst().performClick()
        awaitRoute(ANGLES_V2_SAVED_DETAIL_ROUTE)

        // El fixture captura primero el índice 0, así que el DTO guardado tiene
        // `assignedFrameIndex = 0` y su `displayFrameNumber` es 1. El argumento se fija aquí como
        // literal INDEPENDIENTE (no se deriva con `angleSavedFrameDisplayNumber`) para que el test
        // falle si producción cambia el número mostrado en vez de repetir su transformación.
        val shareDescription = target.getString(R.string.angle_v2_saved_share_frame_image, 1)
        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.onAllNodesWithContentDescription(shareDescription).fetchSemanticsNodes().isNotEmpty()
        }
        // Las tarjetas viven en una LazyColumn real: se desplaza el botón a pantalla antes del
        // clic para que el toque no caiga fuera del viewport.
        rule.onAllNodesWithContentDescription(shareDescription).onFirst()
            .performScrollTo()
            .performClick()
        rule.waitUntil(TIMEOUT_MILLIS) {
            requireNotNull(uiContext).chooserIntents.isNotEmpty()
        }
        val chooser = requireNotNull(uiContext).chooserIntents.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(
            IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java),
        ) { "El chooser debe llevar el intent de compartir." }
        val stream = requireNotNull(
            IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java),
        ) { "El compartir debe adjuntar la URI del PNG." }
        assertEquals("content", stream.scheme)
        assertEquals("${target.packageName}.fileprovider", stream.authority)
        // Primer segmento = alias (`name`) de la raíz declarada en `@xml/file_paths`.
        assertEquals(
            "La URI debe publicarse por la raíz dedicada a fotogramas: $stream",
            SHARE_ROOT_ALIAS,
            stream.pathSegments.firstOrNull(),
        )
        assertTrue(
            "La URI compartida debe ser una copia bajo la raíz de compartición del banco: $stream",
            stream.path.orEmpty().contains(WITHIN_SHARE_ROOT),
        )
        assertTrue(
            "La copia compartida debe vivir en la subcarpeta del banco (nav-${sandbox.uuid}): $stream",
            stream.path.orEmpty().contains("nav-${sandbox.uuid}"),
        )
        assertTrue(
            "El compartir debe conceder lectura de ESE URI.",
            send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        val clip = requireNotNull(send.clipData) { "El compartir debe adjuntar ClipData." }
        assertEquals("El ClipData debe llevar exactamente el URI compartido.", 1, clip.itemCount)
        assertEquals(stream, clip.getItemAt(0).uri)
        val sharedBytes = requireNotNull(
            target.contentResolver.openInputStream(stream)?.use { it.readBytes() },
        ) { "La URI del FileProvider real debía ser legible." }
        assertArrayEquals(
            "Lo compartido es el PNG verificado, no el vídeo.",
            PNG_SIGNATURE,
            sharedBytes.copyOfRange(0, PNG_SIGNATURE.size),
        )
        // No basta el tamaño: los bytes compartidos deben ser EXACTAMENTE uno de los PNG guardados.
        val sharedSha = sha256Hex(sharedBytes)
        val sharedFrame = aggregate.frames.map { it.frame }.firstOrNull { frame ->
            frame.imageSha256.equals(sharedSha, ignoreCase = true)
        }
        assertTrue(
            "El PNG compartido (SHA-256 $sharedSha) debe coincidir con un fotograma guardado: " +
                aggregate.frames.map { it.frame.imageSha256 },
            sharedFrame != null,
        )
        assertEquals(
            "El tamaño compartido debe coincidir con el fotograma de ese SHA-256.",
            requireNotNull(sharedFrame).imageByteCount,
            sharedBytes.size.toLong(),
        )
    }

    // --- montaje del grafo ---------------------------------------------------------------------

    /**
     * Monta UNA sola vez un `setContent` reactivo: cambiar [mountedApp] desmonta el grafo (a `null`)
     * o monta la app indicada. En cada montaje se derivan un `SandboxUiContext` y un dueño de
     * ViewModels con `APPLICATION_KEY` = la app aislada, de modo que toda fábrica real del grafo use
     * la base UUID del banco.
     */
    private fun mountGraph() {
        rule.setContent {
            val current = mountedApp.value
            if (current != null) {
                val mountedUiContext = remember(current) { current.newUiContext(rule.activity) }
                val owner = remember(current) { current.newDefaultViewModelOwner(rule.activity) }
                CompositionLocalProvider(
                    LocalContext provides mountedUiContext,
                    LocalViewModelStoreOwner provides owner,
                ) {
                    OpenJumpTheme {
                        val controller = rememberNavController()
                        SideEffect {
                            navController = controller
                            uiContext = mountedUiContext
                        }
                        // Gate de producto inyectado SOLO aquí: la constante real sigue apagada.
                        OpenJumpNavHost(
                            navController = controller,
                            angleMultiFrameSaveUiEnabled = true,
                        )
                    }
                }
            }
        }
    }

    // --- hook de inspección visual --------------------------------------------------------------

    /**
     * Espera de inspección SOLO si `anglesUiInspectMs` llega > 0 (acotado a [MAX_INSPECT_HOLD_MILLIS]).
     *
     * La espera es un bucle acotado por un deadline **monotónico** (`System.nanoTime`): cada vuelta
     * llama a `rule.waitForIdle()` y duerme como mucho [INSPECT_PUMP_MILLIS]. Así la cola de UI y el
     * reloj de prueba avanzan entre tramos y la pantalla real se sigue pudiendo inspeccionar y tocar
     * por ADB durante la pausa, en lugar de quedar congelada por un único `Thread.sleep` largo. El
     * test NO se salta ni se relaja: al terminar se restablecen pausa y frame 0 con las APIs reales
     * del `ViewModel` (`pausePlayback()` y, si el visor se movió, `stepFrames(-requestedIndex)`) y se
     * espera la misma condición que antes, sin limpiar geometría, sesión ni escribir estados falsos.
     * Sin argumento no hace nada.
     */
    private fun holdForVisualInspectionIfRequested(viewModel: AngleEditorViewModel) {
        val holdMs = InstrumentationRegistry.getArguments()
            .getString(ARG_UI_INSPECT_MS)
            ?.trim()
            ?.toLongOrNull()
            ?.coerceIn(0L, MAX_INSPECT_HOLD_MILLIS)
            ?: 0L
        if (holdMs <= 0L) return
        rule.waitForIdle()
        Log.i(INSPECT_LOG_TAG, "READY source=$fixtureAsset holdMs=$holdMs")
        val deadlineNanos = System.nanoTime() + holdMs * 1_000_000L
        while (true) {
            rule.waitForIdle()
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) break
            val sleepMs = (remainingNanos / 1_000_000L).coerceAtMost(INSPECT_PUMP_MILLIS)
            if (sleepMs <= 0L) break
            Thread.sleep(sleepMs)
        }
        Log.i(INSPECT_LOG_TAG, "RESUME source=$fixtureAsset")
        // Reanudación real: nunca se asume que el visor sigue donde estaba antes de la espera.
        rule.runOnIdle { viewModel.pausePlayback() }
        val requestedIndex = rule.runOnIdle { viewModel.displayState.value.requestedIndex }
        if (requestedIndex > 0) {
            val returned = rule.runOnIdle { viewModel.stepFrames(-requestedIndex) }
            assertTrue(
                "El transporte real debía aceptar el retorno desde el frame $requestedIndex.",
                returned,
            )
        }
        awaitDisplay("frame 0 en pausa tras la inspección visual", viewModel) { display ->
            display.canMark && display.requestedIndex == 0
        }
    }

    // --- captura y marcado por la API real del ViewModel ---------------------------------------

    /**
     * Medir con el botón REAL del workspace ([ANGLE_WORKSPACE_CAPTURE_TAG]) y esperar el fotograma
     * adoptado. El ancla se contrasta con el frame y el PTS EXACTOS que el visor real mostraba
     * antes del toque: la UI no puede capturar otro instante ni otra identidad de fotograma.
     */
    private fun captureFrame(viewModel: AngleEditorViewModel, frameIndex: Int): AngleFrameId {
        awaitDisplay("frame $frameIndex confirmado por el visor antes de Medir", viewModel) { display ->
            display.canMark && display.renderedIndex == frameIndex
        }
        val renderedPtsUs = requireNotNull(
            rule.runOnIdle { viewModel.displayState.value.renderedPtsUs },
        ) { "El visor real debía sellar el PTS del frame $frameIndex antes de Medir." }
        waitForTag(ANGLE_WORKSPACE_CAPTURE_TAG, TIMEOUT_MILLIS)
        rule.onNodeWithTag(ANGLE_WORKSPACE_CAPTURE_TAG).performClick()
        val state = awaitReadySession("captura del frame $frameIndex", viewModel) { current ->
            val item = current.frames.firstOrNull { it.anchor.frameIndex == frameIndex }
            item != null && (item.canEdit || item.captureFailure != null)
        }
        val item = state.frames.first { it.anchor.frameIndex == frameIndex }
        item.captureFailure?.let { failure ->
            throw AssertionError(
                "La captura del frame $frameIndex falló: ${failure.reason} ${failure.detail}",
            )
        }
        check(item.canEdit) { "El frame $frameIndex no tiene imagen editable: ${item.capture}." }
        assertEquals("El ancla debe ser el frame $frameIndex.", frameIndex, item.anchor.frameIndex)
        assertEquals(
            "El ancla debe guardar el PTS EXACTO que mostraba el visor.",
            renderedPtsUs,
            item.anchor.sourcePtsUs,
        )
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
                assertTrue(
                    "Los puntos de este test viven dentro del frame 320x240.",
                    point.x in 0.0..FRAME_WIDTH && point.y in 0.0..FRAME_HEIGHT,
                )
                apply("punto ${pointIndex + 1} del ángulo ${angleIndex + 1} del frame $frameId") {
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

    // --- esperas acotadas sobre estado real -----------------------------------------------------

    private fun <T> awaitState(
        what: String,
        flow: StateFlow<T>,
        timeoutMillis: Long = TIMEOUT_MILLIS,
        predicate: (T) -> Boolean,
    ): T = flow.awaitValueBlocking(
        what = what,
        timeoutMillis = timeoutMillis,
    ) { current -> predicate(current) }

    private fun awaitReadySession(
        what: String,
        viewModel: AngleEditorViewModel,
        timeoutMillis: Long = VIDEO_TIMEOUT_MILLIS,
        predicate: (AngleMultiFrameSessionState) -> Boolean,
    ): AngleMultiFrameSessionState {
        val ready = awaitState("«$what»", viewModel.multiFrameState, timeoutMillis) { current ->
            current is AngleMultiFrameState.Ready && predicate(current.state)
        } as AngleMultiFrameState.Ready
        return ready.state
    }

    private fun awaitDisplay(
        what: String,
        viewModel: AngleEditorViewModel,
        timeoutMillis: Long = TIMEOUT_MILLIS,
        predicate: (com.openjump.app.ui.selector.FrameDisplayState) -> Boolean,
    ) {
        awaitState(what, viewModel.displayState, timeoutMillis) { display ->
            predicate(display)
        }
    }

    private fun awaitRoute(route: String, timeoutMillis: Long = TIMEOUT_MILLIS) {
        rule.waitUntil(timeoutMillis) {
            navController?.currentDestination?.route == route
        }
    }

    private fun waitForTag(tag: String, timeoutMillis: Long) {
        rule.waitUntil(timeoutMillis) {
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun countRows(table: String): Long =
        app.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    /** SHA-256 hexadecimal en minúsculas de los bytes entregados por el proveedor real. */
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte ->
                ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
            }

    private companion object {
        const val FIXTURE_ASSET = "angles/ordinal_cfr_bframes.mp4"
        const val FIXTURE_DIR = "angles/"

        /** Argumento de instrumentación con el fixture a copiar; por defecto, [FIXTURE_ASSET]. */
        const val ARG_UI_FIXTURE = "anglesUiFixture"

        /** Pausa de inspección visual en ms (0 = sin espera); acotada a [MAX_INSPECT_HOLD_MILLIS]. */
        const val ARG_UI_INSPECT_MS = "anglesUiInspectMs"
        const val MAX_INSPECT_HOLD_MILLIS = 300_000L

        /**
         * Tramo máximo de cada vuelta de la espera de inspección. Dentro del plazo pedido el bucle
         * deja correr la UI (`waitForIdle`) y duerme como mucho este tramo corto en vez de bloquear
         * de una vez: con un `Thread.sleep` largo el reloj de Compose no avanza y los toques por ADB
         * no llegan a reflejarse en pantalla.
         */
        const val INSPECT_PUMP_MILLIS = 50L
        const val INSPECT_LOG_TAG = "AngleUiInspect"

        /** Únicos fixtures seleccionables con [ARG_UI_FIXTURE]; otro valor usa [FIXTURE_ASSET]. */
        val SELECTABLE_FIXTURES = setOf("ordinal_cfr_bframes.mp4", "ordinal_rot90.mp4")

        const val TIMEOUT_MILLIS = 30_000L
        const val VIDEO_TIMEOUT_MILLIS = 60_000L
        const val SAVE_TIMEOUT_MILLIS = 60_000L
        const val FRAME_WIDTH = 320.0
        const val FRAME_HEIGHT = 240.0
        const val BOTTOM_NAV_HISTORY_TAG = "bottom-navigation-item-history"
        const val WITHIN_SHARE_ROOT = "shared-angle-frames"

        /** `name` de la raíz dedicada a fotogramas en `@xml/file_paths`. */
        const val SHARE_ROOT_ALIAS = "angle_frame_shares"

        /** Mismos valores de UI que `AngleEditorScreen` construye en `AppNav`. */
        val EDITOR_DEFAULTS = AngleEditorDefaults(
            namePrefix = "Ángulo",
            colorArgb = 0xFFE53935.toInt(),
        )

        /** Puntos de dos ángulos completos sobre el frame 320x240. */
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

        /** Tercer ángulo completo sobre el frame 320x240. */
        val THIRD_ANGLE = listOf(
            AnglePoint(60.0, 20.0),
            AnglePoint(140.0, 40.0),
            AnglePoint(100.0, 120.0),
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
