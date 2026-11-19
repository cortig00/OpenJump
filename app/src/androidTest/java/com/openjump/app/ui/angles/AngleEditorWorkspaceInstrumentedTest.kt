package com.openjump.app.ui.angles

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.angles.AnglePoint
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.ui.selector.ViewportSize
import com.openjump.app.ui.selector.VideoCoordinateMapper
import com.openjump.app.ui.selector.VideoScaleMode
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Flujo funcional del workspace nuevo ([AngleEditorWorkspace]) sobre el `AngleEditorViewModel`
 * real y la sesión congelada real del arnés ([AngleMultiFrameFlowHarness]).
 *
 * Acredita, con recuentos REALES de la sesión y de Room, lo que exige este slice:
 *
 * 1. la sesión congelada se prepara sola UNA vez al quedar el vídeo listo, sin capturar ni escribir;
 * 2. el gate de producto compilado ([ANGLE_MULTI_FRAME_SAVE_UI_ENABLED]) sigue apagado aunque esta
 *    prueba monte el workspace (la rama solo se monta con el gate inyectado activo);
 * 3. antes de medir no hay fotogramas y Guardar está deshabilitado;
 * 4. el primer «Medir este fotograma» deja UN fotograma capturado (el toque de la UI, no una llamada
 *    directa al ViewModel);
 * 5. una foto congelada NO muestra transporte ni timeline: esos controles solo viven en el vídeo;
 * 6. una terna A/B/C se coloca con TRES TOQUES reales sobre el escenario, mapeados con la geometría
 *    real del lease y `VideoCoordinateMapper` FIT → coordenadas de la imagen, sin `markAngle`;
 * 7. un cuarto toque en el mismo lote de entrada no coloca otro A ni deja borrador (la fase se
 *    reevalúa con el editor que el ViewModel ya tiene completo);
 * 8. un único Guardar tras completar persiste UNA raíz V2 con su fotograma, y no hay fila V2 antes.
 *
 * Límites honestos: el arnés publica PTS por construcción con un visor falso (no hay `ExoPlayer` ni
 * `Surface` reales), así que esta prueba acredita la ruta de entrada y los recuentos, no los píxeles
 * decodificados de una superficie. Se compila con `compileDebugAndroidTestKotlin`; no se ejecuta en
 * este slice (sin AVD).
 */
@RunWith(AndroidJUnit4::class)
class AngleEditorWorkspaceInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var harness: AngleMultiFrameFlowHarness

    @Before
    fun setUp() {
        harness = AngleMultiFrameFlowHarness(productionRenderer = true)
        harness.openVideo()
    }

    @After
    fun tearDown() {
        if (::harness.isInitialized) harness.close()
    }

    @Test
    fun workspacePreparaCapturaTernaYGuardaUnaRaizConUnSoloGuardar() {
        // Preapertura: ni fotogramas ni sesión preparada antes de componer.
        assertEquals(0, angleMultiFrameFrameCount(harness.viewModel.multiFrameState.value))
        assertTrue(harness.viewModel.multiFrameState.value is AngleMultiFrameState.NotOpened)
        assertFalse(
            "El gate de producto compilado debe seguir apagado aunque la prueba monte la rama.",
            ANGLE_MULTI_FRAME_SAVE_UI_ENABLED,
        )
        var closeRequests = 0
        rule.setContent {
            OpenJumpTheme {
                AngleEditorWorkspace(
                    viewModel = harness.viewModel,
                    onCloseRequest = { closeRequests += 1 },
                )
            }
        }

        // 1. Preparación perezosa al quedar el vídeo listo, sin capturar ni escribir nada.
        rule.waitUntil(timeoutMillis = 20_000) {
            harness.viewModel.multiFrameState.value is AngleMultiFrameState.Ready
        }
        val empty = harness.sessionState()
        assertEquals(0, empty.frames.size)
        assertEquals(0L, harness.v2RootCount())
        rule.onNodeWithTag(ANGLE_WORKSPACE_TAG).assertExists()
        // S4a: sin fotogramas no hay CTA Guardar en vídeo (la tira/Guardar solo nace con foto).
        rule.onNodeWithTag(ANGLE_WORKSPACE_SAVE_TAG).assertDoesNotExist()

        // 2. Primer Medir por la UI real: exactamente un fotograma con imagen.
        harness.pauseViewerAt(0)
        rule.onNodeWithTag(ANGLE_WORKSPACE_CAPTURE_TAG).performClick()
        rule.waitUntil(timeoutMillis = 20_000) {
            val state = harness.viewModel.multiFrameState.value
            (state as? AngleMultiFrameState.Ready)?.state?.frames?.size == 1
        }
        val frameId = harness.sessionState().frames.single().frameId
        rule.waitUntil(timeoutMillis = 20_000) { harness.frame(frameId).canEdit }

        // 3. La foto congelada no expone transporte/timeline: el escenario del vídeo ya no los pinta.
        rule.onNodeWithTag(ANGLE_WORKSPACE_TIMELINE_TAG).assertDoesNotExist()
        // La foto sí ofrece Otro ángulo y la CTA primaria Guardar (aún bloqueada sin terna).
        rule.onNodeWithTag(ANGLE_WORKSPACE_ADD_ANGLE_TAG).assertExists()
        rule.onNodeWithTag(ANGLE_WORKSPACE_SAVE_TAG).assertIsNotEnabled()

        // 4. Geometría real del lease + mapper FIT del mismo viewport que mide la pantalla.
        val geometry = when (val outcome = harness.viewModel.acquireMultiFrameBitmap(frameId)) {
            is AngleMultiFrameBitmapResult.Granted ->
                outcome.lease.imageGeometry.also { outcome.lease.close() }

            is AngleMultiFrameBitmapResult.Rejected -> throw AssertionError(
                "No se pudo leer la geometría real de $frameId: ${outcome.rejection}",
            )
        }
        assertNotNull(geometry)
        val stageSize = rule.onNodeWithTag(ANGLE_WORKSPACE_VIDEO_TAG)
            .fetchSemanticsNode().size
        assertTrue("el escenario debe medir algo", stageSize.width > 0 && stageSize.height > 0)
        val mapper = VideoCoordinateMapper(
            video = geometry,
            viewport = ViewportSize(stageSize.width.toDouble(), stageSize.height.toDouble()),
            scaleMode = VideoScaleMode.FIT,
            allowFitZoom = true,
        )
        fun stageOffset(point: AnglePoint): Offset {
            val view = mapper.videoToView(ImagePoint(point.x, point.y))
            return Offset(view.x.toFloat(), view.y.toFloat())
        }

        // 5. Terna A/B/C con tres toques reales sobre el escenario + cuarto toque en zona vacía,
        //    todo en el MISMO lote de entrada (antes de que la composición pueda recomponer).
        val fourthBlankTap = AnglePoint(240.0, 120.0)
        val taps = AngleMultiFrameFlowHarness.FIRST_ANGLE + fourthBlankTap
        rule.onNodeWithTag(ANGLE_WORKSPACE_VIDEO_TAG).performTouchInput {
            taps.forEach { point -> click(stageOffset(point)) }
        }
        rule.waitUntil(timeoutMillis = 20_000) {
            val state = harness.viewModel.multiFrameState.value
            (state as? AngleMultiFrameState.Ready)?.state?.frame(frameId)?.editor?.let { editor ->
                editor.angles.size == 1 && editor.draft.points.isEmpty()
            } == true
        }
        val marked = harness.frame(frameId).editor
        assertEquals("la terna real crea UN ángulo", 1, marked.angles.size)
        assertTrue("el cuarto toque no deja borrador", marked.draft.points.isEmpty())
        val markers = marked.angles.single().markers
        assertCloseTo(AngleMultiFrameFlowHarness.FIRST_ANGLE[0], markers.a)
        assertCloseTo(AngleMultiFrameFlowHarness.FIRST_ANGLE[1], markers.b)
        assertCloseTo(AngleMultiFrameFlowHarness.FIRST_ANGLE[2], markers.c)
        assertEquals("sin fila V2 antes de Guardar", 0L, harness.v2RootCount())
        rule.onNodeWithTag(ANGLE_WORKSPACE_SAVE_TAG).assertIsEnabled()

        // 6. Un único Guardar: persiste UNA raíz V2 y su fotograma, sin repetir la operación.
        rule.onNodeWithTag(ANGLE_WORKSPACE_SAVE_TAG).performClick()
        val settled = harness.awaitSaveSettled()
        assertTrue(
            "el guardado debe terminar en éxito real, era $settled",
            settled is AngleMultiFrameSaveState.Saved,
        )
        assertEquals(1L, harness.v2RootCount())
        assertEquals(1L, harness.v2FrameCount())
        assertEquals(1, harness.sessionState().frames.size)
        assertEquals("la guarda de cierre no se invoca sola", 0, closeRequests)
    }

    private fun assertCloseTo(expected: AnglePoint, actual: AnglePoint) {
        assertEquals("x del marcador", expected.x, actual.x, 0.05)
        assertEquals("y del marcador", expected.y, actual.y, 0.05)
    }
}
