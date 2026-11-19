package com.openjump.app.ui.angles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.angles.AngleAnalysisFinalization
import com.openjump.app.data.ANGLE_ANALYSIS_V2_FORMAT
import com.openjump.app.data.ANGLE_GEOMETRY_VERSION
import com.openjump.app.data.angles.AngleImageIntegrity
import com.openjump.app.data.angles.AngleImageReadResult
import com.openjump.app.data.angles.PngPixelSize
import com.openjump.app.data.angles.pngPixelSize
import com.openjump.app.ui.theme.OpenJumpTheme
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
 * Slice 2 del flujo multi-fotograma instrumentado: **guardado real** de dos fotogramas capturados de
 * un vídeo fixture, con varios ángulos, sobre las piezas de producción.
 *
 * Lo que este test demuestra y lo que no:
 *
 * - **Demuestra** que el `AngleEditorViewModel` real captura dos frames distintos de la fuente
 *   congelada real (`FrozenFrameSources`), acepta tres ángulos sobre ellos (uno oculto) y, a través
 *   de la entrada pública de guardado ([AngleEditorViewModel.saveMultiFrame]) y del
 *   [AngleMultiFrameSaveCoordinator] real, guarda una raíz V2 en una base Room **en memoria** con su
 *   **renderer real de producción** ([AndroidAngleMultiFrameImageRenderer], misma densidad y textos
 *   que la `factory`): sin stub de render. Después verifica, contra la base aislada y el almacén
 *   aislado:
 *   1. Room: una raíz, dos fotogramas y tres marcas; `sessionKey` derivada con la función real
 *      ([multiFrameSessionKey]) y dueño real.
 *   2. Geometría y nombres: cada marca persistida coincide (nombre, color, visibilidad y las seis
 *      coordenadas) con el ángulo vivo del editor y con la proyección de detalle real
 *      ([angleV2SavedDetailState]); los instantes y dimensiones salen del índice sellado y de la
 *      imagen congelada.
 *   3. Imagen: los bytes del PNG se leen **verificados** por el almacén real
 *      ([com.openjump.app.data.angles.PrivateAngleImageStore.readVerified]), su cabecera IHDR y su
 *      decodificación cuadran con la fila, una zona del PNG lejos de todas las marcas (bloque
 *      inferior derecho) es idéntica al bitmap congelado exacto del fotograma (el render copia esa
 *      imagen) y hay píxeles anotados distintos del original (prueba de que el renderer real dibujó
 *      la anotación, halo incluido).
 * - **Gate de pantalla inyectado solo en el test.** El gate de producción
 *   ([ANGLE_MULTI_FRAME_SAVE_UI_ENABLED]) sigue `false` y se comprueba; la pantalla real recibe la
 *   disponibilidad calculada con `gateEnabled = true`, de modo que su botón se habilita. La acción
 *   `onSaveMultiFrame` que recibe la pantalla es **la entrada pública real del `ViewModel`**
 *   ([AngleEditorViewModel.saveMultiFrame], pasada como referencia de método), no un contador: la
 *   pulsación dispara el guardado real (renderer de producción, almacén y Room en memoria) y todo lo
 *   que se verifica después —estado final publicado, raíz/fotogramas/marcas y PNG— se deriva de ese
 *   click. Antes de pulsar se pausa el reloj de test (`mainClock.autoAdvance = false`) para no
 *   sincronizar Compose contra la animación indefinida del aviso de guardado en vuelo; el test espera
 *   el estado real publicado con un timeout acotado ([AngleMultiFrameFlowHarness.awaitSaveSettled]) y
 *   solo después reanuda el reloj y comprueba la UI final (aviso de guardado, acción ya no ofrecida y
 *   sin aviso de «en curso»). No se sincroniza Compose mientras el guardado está en vuelo.
 * - **No demuestra** compartir ni borrado desde historial: el compartir tiene su propia prueba
 *   instrumentada con el FileProvider real y el detalle V2 (sin este arnés ni vídeo), y el borrado
 *   tiene sus propias pruebas. Aquí el historial/detalle se acredita por sus piezas reales de
 *   lectura (DAO V2 + proyección `angleV2SavedDetailState`), sin inventar una pantalla.
 *
 * Aislamiento: Room en memoria, raíz de imágenes con UUID en `cacheDir`, copia propia del fixture y
 * borrado al cerrar. Nunca se abre la base de la app ni se ejecuta `AppNav`; no se toca el gate ni
 * ningún archivo de producción.
 *
 * Los nombres de los `@Test` deben ser `camelCase` sin espacios: las lambdas del cuerpo generan
 * clases anónimas cuyo `SimpleName` hereda el nombre del método, y con `minSdk = 26` el DEX
 * resultante es < 040, que prohíbe espacios en `SimpleName` (fallo de `dexBuilderDebugAndroidTest`).
 */
@RunWith(AndroidJUnit4::class)
class AngleMultiFrameFlowSaveInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var harness: AngleMultiFrameFlowHarness

    @Before
    fun setUp() {
        harness = AngleMultiFrameFlowHarness(productionRenderer = true)
    }

    @After
    fun tearDown() {
        if (::harness.isInitialized) harness.close()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun guardaDosFramesConVariosAngulosConElRendererRealYGateInyectadoEnElTest() {
        harness.openVideo()
        harness.openMultiFrameSession()

        // --- captura real de dos frames y tres ángulos (uno oculto) -----------------------------
        val firstFrame = harness.captureFrame(0)
        val secondFrame = harness.captureFrame(1)

        val knee = harness.markAngle(firstFrame)
        harness.renameAngle(firstFrame, knee, "Rodilla derecha")
        val hip = harness.markAngle(firstFrame, AngleMultiFrameFlowHarness.SECOND_ANGLE)
        harness.renameAngle(firstFrame, hip, "Cadera izquierda")
        harness.setAngleVisible(firstFrame, hip, visible = false)

        val ankle = harness.markAngle(secondFrame, AngleMultiFrameFlowHarness.THIRD_ANGLE)
        harness.renameAngle(secondFrame, ankle, "Tobillo izquierdo")

        val sessionState = harness.sessionState()
        assertEquals(2, sessionState.frames.size)
        assertEquals(3, sessionState.frames.sumOf { it.editor.angles.size })
        assertTrue(
            "La finalización real debía estar lista, era ${sessionState.finalization}",
            sessionState.finalization is AngleAnalysisFinalization.Ready,
        )
        val readyState = AngleMultiFrameState.Ready(sessionState)

        // --- gate: producción apagado, pantalla recibe la inyección SOLO del test ----------------
        assertFalse(
            "Este test no puede cambiar el gate de producción.",
            ANGLE_MULTI_FRAME_SAVE_UI_ENABLED,
        )
        val productionGate = angleMultiFrameSaveAvailability(
            gateEnabled = false,
            multiFrame = readyState,
            saveState = AngleMultiFrameSaveState.Idle,
        )
        assertTrue(productionGate is AngleMultiFrameSaveAvailability.Blocked)
        assertEquals(
            R.string.angle_multi_frame_save_gate,
            (productionGate as AngleMultiFrameSaveAvailability.Blocked).reason.messageRes,
        )
        assertEquals(
            "Con el análisis finalizable, el único bloqueo de producción es el gate.",
            AngleMultiFrameSaveAvailability.Ready,
            angleMultiFrameSaveAvailability(
                gateEnabled = true,
                multiFrame = readyState,
                saveState = AngleMultiFrameSaveState.Idle,
            ),
        )

        rule.setContent {
            OpenJumpTheme {
                // El estado de guardado que ve la pantalla es el real que publica el ViewModel.
                val liveSaveState by harness.viewModel.multiFrameSaveState.collectAsState()
                AngleMultiFrameEditorScreen(
                    multiFrame = readyState,
                    // Referencia de método real: el click ejecuta viewModel.saveMultiFrame().
                    actions = harness.editorActions(harness.viewModel::saveMultiFrame),
                    onBackToViewer = {},
                    onClose = {},
                    saveState = liveSaveState,
                    // Inyección de test explícita: el gate de producto sigue apagado.
                    saveAvailability = angleMultiFrameSaveAvailability(
                        gateEnabled = true,
                        multiFrame = readyState,
                        saveState = liveSaveState,
                    ),
                )
            }
        }
        rule.onNodeWithTag(ANGLE_MULTI_FRAME_SCREEN_TAG).assertExists()
        rule.onNodeWithTag(ANGLE_MULTI_FRAME_SAVE_TAG).assertIsEnabled()
        rule.onNodeWithText(context.getString(R.string.angle_multi_frame_save_gate)).assertDoesNotExist()
        rule.waitForIdle()

        // --- click real: el guardado y su verificación salen de aquí ------------------------------
        assertEquals(AngleMultiFrameSaveState.Idle, harness.saveState())
        assertEquals("Antes del click no puede existir ninguna raíz V2.", 0L, harness.v2RootCount())
        assertEquals(0, harness.publishedImageFileCount())
        // El estado `Saving` muestra un progreso indefinido: con el reloj en pausa no se bombean
        // frames mientras el guardado corre, así que ninguna sincronización de Compose queda girando
        // sobre esa animación. Hasta que el estado real termina no se vuelve a sincronizar Compose.
        rule.mainClock.autoAdvance = false
        val settled = try {
            rule.onNodeWithTag(ANGLE_MULTI_FRAME_SAVE_TAG).performTouchInput { click() }
            harness.awaitSaveSettled()
        } finally {
            // Se reanuda siempre, también si el guardado falla o agota el timeout.
            rule.mainClock.autoAdvance = true
        }

        assertTrue("El guardado real debía completarse; terminó en $settled", settled is AngleMultiFrameSaveState.Saved)
        val saved = settled as AngleMultiFrameSaveState.Saved
        // Sólo con el guardado realmente terminado se vuelve a sincronizar/observar la UI.
        rule.waitForIdle()
        assertEquals(2, saved.frameCount)
        assertEquals("El coordinador debía renderizar una vez por fotograma.", 2, harness.renderCallCount)
        assertEquals("El resultado publicado por el click es el real.", saved, harness.saveState())
        // UI final derivada del estado real: el aviso de guardado aparece con un timeout acotado (el
        // reloj ya está reanudado y el progreso indefinido del estado `Saving` ya no existe).
        rule.waitUntilAtLeastOneExists(
            hasText(context.getString(R.string.angle_multi_frame_save_saved)),
            UI_SETTLE_TIMEOUT_MILLIS,
        )
        rule.onNodeWithText(context.getString(R.string.angle_multi_frame_save_saved)).assertExists()
        rule.onNodeWithTag(ANGLE_MULTI_FRAME_SAVE_TAG).assertIsNotEnabled()
        rule.onNodeWithContentDescription(
            context.getString(R.string.angle_multi_frame_notice_busy),
        ).assertDoesNotExist()
        assertEquals(
            "Un guardado terminado ya no puede volver a ofrecerse.",
            AngleMultiFrameSaveAvailability.Done,
            angleMultiFrameSaveAvailability(
                gateEnabled = true,
                multiFrame = readyState,
                saveState = saved,
            ),
        )
        assertEquals(
            R.string.angle_multi_frame_save_saved,
            angleMultiFrameSaveStatusNotice(saved)?.messageRes,
        )

        // --- Room en memoria: raíz, fotogramas y marcas ------------------------------------------
        assertEquals(1L, harness.v2RootCount())
        assertEquals(2L, harness.v2FrameCount())
        assertEquals(3L, harness.v2MarkCount())

        val aggregate = runBlocking {
            harness.database.angleAnalysisV2Dao().aggregateByRootId(saved.rootId)
        }
        val stored = requireNotNull(aggregate) {
            "La raíz ${saved.rootId} debía existir en la base en memoria."
        }
        assertEquals(saved.rootId, stored.root.id)
        assertEquals(ANGLE_ANALYSIS_V2_FORMAT, stored.root.analysisFormat)
        assertEquals(
            "La raíz se guarda bajo la clave derivada real del borrador.",
            multiFrameSessionKey(harness.request.sessionKey, 0),
            stored.root.sessionKey,
        )
        assertEquals(harness.athleteId, requireNotNull(stored.root.athleteId))

        val detail = angleV2SavedDetailState(stored)
        assertTrue("La proyección real del detalle debía cargar la raíz: $detail", detail is AngleV2SavedDetailState.Loaded)
        val content = (detail as AngleV2SavedDetailState.Loaded).content
        assertEquals(saved.rootId, content.rootId)
        assertEquals(harness.athleteId, requireNotNull(content.athleteId))
        assertEquals(2, content.frames.size)
        assertEquals("Las tres marcas se persisten, incluida la oculta.", 3, content.markCount)
        assertEquals(2, content.visibleMarkCount)

        val storedFrames = stored.frames.sortedBy { it.frame.ordinal }
        assertEquals(2, storedFrames.size)
        assertEquals(
            "El repositorio numera los fotogramas por su orden de envío.",
            setOf(0, 1),
            storedFrames.map { it.frame.ordinal }.toSet(),
        )
        val storedByFrameIndex = storedFrames.associateBy { it.frame.assignedFrameIndex }
        val expectedFrameIds = listOf(firstFrame, secondFrame)

        expectedFrameIds.forEach { frameId ->
            val live = harness.frame(frameId)
            val expectedIndex = live.anchor.frameIndex
            val storedFrameWrap = requireNotNull(storedByFrameIndex[expectedIndex]) {
                "No hay fila V2 para el fotograma $expectedIndex."
            }
            val storedFrame = storedFrameWrap.frame
            val expectedAngles = live.editor.angles

            assertEquals(live.anchor.sourcePtsUs, storedFrame.assignedPtsUs)
            assertEquals(
                "El PTS persistido es el del índice sellado de la fuente real.",
                harness.videoIndex.timeOf(expectedIndex),
                storedFrame.assignedPtsUs,
            )
            assertEquals(ANGLE_GEOMETRY_VERSION, storedFrame.geometryVersion)
            assertEquals(harness.videoIndex.width, storedFrame.encodedWidth)
            assertEquals(harness.videoIndex.height, storedFrame.encodedHeight)
            assertEquals(harness.videoIndex.rotationDegrees, storedFrame.rotationDegrees)
            assertEquals("image/png", storedFrame.imageMimeType)

            val frozenWidth = harness.withFrozenBitmap(frameId) { it.width }
            val frozenHeight = harness.withFrozenBitmap(frameId) { it.height }
            assertEquals(
                "Las dimensiones de presentación son las de la imagen congelada (sistema de las marcas).",
                frozenWidth,
                storedFrame.presentationWidth,
            )
            assertEquals(frozenHeight, storedFrame.presentationHeight)

            // --- geometría y nombres: entidad persistida == editor vivo ---------------------------
            val storedMarks = storedFrameWrap.marks.sortedBy { it.ordinal }
            assertEquals(expectedAngles.size, storedMarks.size)
            expectedAngles.forEachIndexed { index, angle ->
                val mark = storedMarks[index]
                assertEquals(index, mark.ordinal)
                assertEquals(angle.name, mark.name)
                assertEquals(angle.colorArgb, mark.colorArgb)
                assertEquals(angle.visible, mark.visible)
                assertEquals(angle.markers.a.x, mark.pointAX, 0.0)
                assertEquals(angle.markers.a.y, mark.pointAY, 0.0)
                assertEquals(angle.markers.b.x, mark.pointBX, 0.0)
                assertEquals(angle.markers.b.y, mark.pointBY, 0.0)
                assertEquals(angle.markers.c.x, mark.pointCX, 0.0)
                assertEquals(angle.markers.c.y, mark.pointCY, 0.0)
            }

            // --- proyección real del detalle (lectura de historial) ------------------------------
            val projectedFrame = content.frames.first { it.assignedFrameIndex == expectedIndex }
            assertEquals(expectedIndex, projectedFrame.assignedFrameIndex)
            assertEquals(expectedAngles.map { it.name }, projectedFrame.marks.map { it.name })
            assertEquals(
                expectedAngles.map { it.visible },
                projectedFrame.marks.map { it.visible },
            )
            expectedAngles.forEachIndexed { index, angle ->
                assertEquals(angle.measurement, projectedFrame.marks[index].measurement)
            }

            // --- PNG verificado por el almacén real y relacionado con el bitmap congelado ---------
            val pngBytes = when (
                val read = harness.imageStore.readVerified(
                    imageKey = storedFrame.imageKey,
                    expected = AngleImageIntegrity(
                        byteCount = storedFrame.imageByteCount,
                        sha256Hex = storedFrame.imageSha256,
                    ),
                ) { input -> input.readBytes() }
            ) {
                is AngleImageReadResult.Delivered<*> -> read.value as ByteArray
                is AngleImageReadResult.Failed -> throw AssertionError(
                    "El PNG del fotograma $expectedIndex no se pudo leer: ${read.error}",
                )
            }
            assertEquals(storedFrame.imageByteCount, pngBytes.size.toLong())
            assertEquals(
                PngPixelSize(storedFrame.imageWidth, storedFrame.imageHeight),
                pngPixelSize(pngBytes),
            )
            val decoded = requireNotNull(
                BitmapFactory.decodeByteArray(
                    pngBytes,
                    0,
                    pngBytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                ),
            ) { "El PNG guardado del fotograma $expectedIndex no se pudo decodificar." }
            try {
                assertEquals(storedFrame.imageWidth, decoded.width)
                assertEquals(storedFrame.imageHeight, decoded.height)

                harness.withFrozenBitmap(frameId) { frozen ->
                    assertEquals(
                        "El PNG guardado debe medir lo mismo que el bitmap congelado.",
                        frozen.width,
                        decoded.width,
                    )
                    assertEquals(frozen.height, decoded.height)
                    val frozenPixels = IntArray(frozen.width * frozen.height)
                    frozen.getPixels(
                        frozenPixels,
                        0,
                        frozen.width,
                        0,
                        0,
                        frozen.width,
                        frozen.height,
                    )
                    val pngPixels = IntArray(decoded.width * decoded.height)
                    decoded.getPixels(
                        pngPixels,
                        0,
                        decoded.width,
                        0,
                        0,
                        decoded.width,
                        decoded.height,
                    )

                    // El render copia la imagen congelada y solo añade la anotación: una zona
                    // lejos de todas las marcas (bloque inferior derecho) debe ser idéntica. No se
                    // usa el borde completo porque el halo de una marca cercana al borde sí puede
                    // tocarlo, y eso es dibujo legítimo, no una imagen distinta.
                    for (y in frozen.height - SAFE_BLOCK_PIXELS until frozen.height) {
                        for (x in frozen.width - SAFE_BLOCK_PIXELS until frozen.width) {
                            val position = y * frozen.width + x
                            assertEquals(
                                "La zona sin anotar ($x, $y) del frame $expectedIndex debe conservar " +
                                    "el píxel del bitmap congelado.",
                                frozenPixels[position] and RGB_MASK,
                                pngPixels[position] and RGB_MASK,
                            )
                        }
                    }

                    val differing = frozenPixels.indices.count { position ->
                        (frozenPixels[position] and RGB_MASK) != (pngPixels[position] and RGB_MASK)
                    }
                    assertTrue(
                        "El PNG del frame $expectedIndex debe contener la anotación del renderer " +
                            "real; solo difieren $differing píxeles del bitmap congelado.",
                        differing >= MIN_ANNOTATED_PIXELS,
                    )
                }
            } finally {
                decoded.recycle()
            }
        }

        assertEquals(
            "Un PNG por fotograma guardado, ninguno huérfano.",
            2,
            harness.publishedImageFileCount(),
        )
    }

    private companion object {
        /** Tiempo máximo para que la UI final refleje el estado real ya publicado. */
        const val UI_SETTLE_TIMEOUT_MILLIS: Long = 10_000L

        /** Máscara RGB: la anotación no debe cambiar el color de la zona no anotada. */
        const val RGB_MASK: Int = 0x00FFFFFF

        /**
         * Lado del bloque inferior derecho que debe seguir siendo la imagen congelada. Las marcas
         * de este test viven en la mitad superior izquierda (x <= 100, y <= 120), así que el bloque
         * no puede contener halo ni etiqueta alguna.
         */
        const val SAFE_BLOCK_PIXELS: Int = 48

        /**
         * Píxeles que la anotación real debe cambiar al menos por fotograma. Los glifos de las
         * marcas, los trazos y la etiqueta de grados ocupan bastantes más; el umbral solo descarta
         * la coincidencia accidental.
         */
        const val MIN_ANNOTATED_PIXELS: Int = 50
    }
}
