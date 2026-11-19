package com.openjump.app.ui.angles

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.angles.AngleAccessibleCursor
import com.openjump.app.angles.AngleFrameId
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AngleMarkers
import com.openjump.app.angles.AnglePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regresión instrumentada del panel accesible de puntos en el **editor de fotograma congelado**.
 *
 * Compone [AngleMultiFrameEditorScreen] (la pantalla real del flujo multi-fotograma) sobre un estado
 * de sesión producido por el `AngleEditorViewModel` real con la fuente congelada real y Room en
 * memoria ([AngleMultiFrameFlowHarness]), y ejerce el camino accesible que usa TalkBack/Switch
 * Access sobre la imagen congelada:
 *
 * 1. abre el panel y elige una marca (B o C) de un ángulo existente A/B/C con las tres posiciones
 *    distintas;
 * 2. comprueba que la lectura de posición del panel arranca en la marca **elegida** (no en A);
 * 3. avanza el cursor un paso y confirma;
 * 4. comprueba que el movimiento se aplica a la marca elegida, en su punto más un paso, y que las
 *    demás marcas no cambian.
 *
 * El escenario **no** cambia zoom, ni fotograma, ni modo, ni la selección de ángulo: la única
 * interacción es elegir marca, un paso y confirmar, así que el resultado solo puede atribuirse a que
 * el objetivo accesible se recalcula con la marca elegida. Si el objetivo se queda cacheado con A,
 * la lectura de posición y el movimiento confirmado apuntan a A y estos tests fallan.
 *
 * Aislamiento: copia propia del fixture sintético, Room **en memoria** y raíz de imágenes con UUID en
 * `cacheDir`, todo borrado al terminar; nunca se abre la base de la app ni se escribe nada. El gate
 * de producto ([ANGLE_MULTI_FRAME_SAVE_UI_ENABLED]) no se toca y no se guarda nada.
 *
 * Los nombres de los `@Test` van en `camelCase` sin espacios: las lambdas del cuerpo generan clases
 * anónimas cuyo `SimpleName` hereda el nombre del método, y con `minSdk = 26` el DEX resultante
 * prohíbe espacios en ese nombre.
 */
@RunWith(AndroidJUnit4::class)
class AngleFrozenAccessibleControlsInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var harness: AngleMultiFrameFlowHarness
    private lateinit var recording: RecordingActions
    private lateinit var geometry: VideoPresentationGeometry
    // `AngleFrameId` y `AngleId` son inline classes: `lateinit` no está permitido, así que el id se
    // publica con un respaldo anulable que `setUp` rellena siempre.
    private var frameIdOrNull: AngleFrameId? = null
    private val frameId: AngleFrameId
        get() = frameIdOrNull ?: throw IllegalStateException("frameId sin preparar en setUp")
    private var angleIdOrNull: AngleId? = null
    private val angleId: AngleId
        get() = angleIdOrNull ?: throw IllegalStateException("angleId sin preparar en setUp")
    private lateinit var composedState: AngleMultiFrameSessionState
    private lateinit var beforeMarkers: AngleMarkers

    @Before
    fun setUp() {
        harness = AngleMultiFrameFlowHarness()
        harness.openVideo()
        harness.openMultiFrameSession()
        frameIdOrNull = harness.captureFrame(0)
        // A/B/C con las tres posiciones distintas del fixture (320x240): A=(20,20), B=(80,20),
        // C=(80,80). Al completar la terna el editor selecciona el ángulo nuevo.
        angleIdOrNull = harness.markAngle(frameId)

        val base = harness.editorActions { }
        // Geometría real de presentación del fotograma congelado: la misma que publica el lease que
        // usa la pantalla. Se pide y se cierra el lease antes de componer.
        geometry = when (val outcome = base.onAcquireBitmap(frameId)) {
            is AngleMultiFrameBitmapResult.Granted ->
                outcome.lease.imageGeometry.also { outcome.lease.close() }

            is AngleMultiFrameBitmapResult.Rejected -> throw AssertionError(
                "No se pudo leer la geometría del fotograma congelado: ${outcome.rejection}",
            )
        }

        recording = RecordingActions(base)
        composedState = harness.sessionState()
        assertEquals("El fotograma capturado debe ser el activo.", frameId, composedState.activeFrameId)
        assertEquals(
            "Marcar la terna debe dejar el ángulo seleccionado.",
            angleId,
            composedState.activeFrame?.editor?.selectedAngleId,
        )
        beforeMarkers = angleMarkers(composedState)
        assertEquals(
            "El fixture debe dar tres marcas distintas; eran $beforeMarkers.",
            3,
            setOf(beforeMarkers.a, beforeMarkers.b, beforeMarkers.c).size,
        )

        rule.setContent {
            OpenJumpTheme {
                AngleMultiFrameEditorScreen(
                    multiFrame = AngleMultiFrameState.Ready(composedState),
                    actions = recording.actions,
                    onBackToViewer = {},
                    onClose = {},
                    saveState = AngleMultiFrameSaveState.Idle,
                )
            }
        }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        if (::harness.isInitialized) harness.close()
    }

    @Test
    fun elegirMarcaBEnFotogramaCongeladoArrancaElCursorEnBYMueveSoloB() {
        verifySelectedMarker(marker = AngleMarker.B)
    }

    @Test
    fun elegirMarcaCEnFotogramaCongeladoArrancaElCursorEnCYMueveSoloC() {
        verifySelectedMarker(marker = AngleMarker.C)
    }

    /**
     * Camino accesible completo para [marker]: elegir la marca, comprobar la lectura de posición,
     * avanzar un paso, confirmar y verificar el resultado real del `ViewModel`.
     */
    private fun verifySelectedMarker(marker: AngleMarker) {
        val markerPoint = beforeMarkers.pointFor(marker)
        val step = AngleAccessibleCursor.step(geometry)
        val expectedMoved = AngleAccessibleCursor.moved(geometry, markerPoint, step, 0.0)
        assertTrue(
            "El paso debe mover la marca elegida de verdad ($markerPoint -> $expectedMoved).",
            expectedMoved != markerPoint,
        )
        // Las otras dos marcas son el contraste: si el objetivo se quedara en A, el punto esperado
        // sería el de A + paso y no el de la marca elegida.
        val others = AngleMarker.entries.filter { it != marker }
        assertTrue(
            "El escenario necesita marcas en posiciones distintas para detectar el objetivo cacheado.",
            others.all { beforeMarkers.pointFor(it) != markerPoint },
        )

        // 1) Abrir el panel accesible dentro de la lista desplazable de la pantalla real.
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_TOGGLE_TAG).performScrollTo().performClick()
        rule.waitForIdle()

        // 2) Elegir la marca. Esta es la única interacción que cambia algo antes de confirmar: no se
        //    toca zoom, ni fotograma, ni modo.
        rule.onNodeWithTag(angleAccessibleMarkerTag(marker)).performScrollTo().performClick()
        rule.waitForIdle()

        // 3) El panel debe apuntar ya a la marca elegida: lectura de posición y acción de confirmar.
        val expectedMarkerPosition = context.getString(
            R.string.angle_accessible_position,
            markerPoint.x,
            markerPoint.y,
        )
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_POSITION_TAG).assertTextEquals(expectedMarkerPosition)
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_CONFIRM_TAG)
            .assertIsEnabled()
            .assertTextEquals(context.getString(R.string.angle_accessible_adjust, marker.name))

        // 4) Un paso a la derecha y confirmación explícita.
        rule.onNodeWithTag(angleAccessibleStepTag("right")).performScrollTo().performClick()
        rule.waitForIdle()
        val expectedStepPosition = context.getString(
            R.string.angle_accessible_position,
            expectedMoved.x,
            expectedMoved.y,
        )
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_POSITION_TAG).assertTextEquals(expectedStepPosition)
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_CONFIRM_TAG).performScrollTo().performClick()
        rule.waitForIdle()

        // 5) El único movimiento pedido es el de la marca elegida, y sale de SU posición más un paso.
        assertEquals(
            "Confirmar una vez debe pedir exactamente un movimiento: ${recording.moves}",
            1,
            recording.moves.size,
        )
        val move = recording.moves.single()
        assertEquals(frameId, move.frameId)
        assertEquals(angleId, move.angleId)
        assertEquals("El movimiento confirmado debe ser de la marca elegida.", marker, move.marker)
        assertEquals(
            "El punto confirmado debe partir del punto real de la marca elegida.",
            expectedMoved,
            move.point,
        )
        assertEquals(
            "El camino accesible no debe colocar puntos del borrador.",
            0,
            recording.placeCalls,
        )
        assertEquals(0, recording.draftMoveCalls)

        // 6) El estado real del `ViewModel` solo cambia la marca elegida; A y la otra no se mueven.
        val result = recording.moveResult
            ?: throw AssertionError("La confirmación no llegó a la acción de mover marca.")
        val applied = result as? AngleMultiFrameResult.Applied
            ?: throw AssertionError("El editor rechazó el movimiento de $marker: $result")
        assertTrue(
            "La versión aplicada debe ser posterior a la compuesta (${composedState.version} -> " +
                "${applied.state.version}).",
            applied.state.version > composedState.version,
        )
        val afterMarkers = angleMarkers(applied.state)
        assertEquals(
            "La marca elegida debe quedar en el punto confirmado.",
            expectedMoved,
            afterMarkers.pointFor(marker),
        )
        others.forEach { other ->
            assertEquals(
                "La marca $other no debía moverse al ajustar $marker.",
                beforeMarkers.pointFor(other),
                afterMarkers.pointFor(other),
            )
        }
        // El escenario no cambia fotograma ni selección de ángulo: nada enmascara el resultado.
        assertEquals(0, recording.selectFrameCalls)
        assertEquals(0, recording.selectAngleCalls)
        assertEquals(frameId, applied.state.activeFrameId)
        assertEquals(angleId, applied.state.activeFrame?.editor?.selectedAngleId)
        assertEquals(1, applied.state.frames.size)
        assertEquals(angleId, applied.state.activeFrame?.editor?.angles?.single()?.id)
    }

    private fun angleMarkers(state: AngleMultiFrameSessionState): AngleMarkers =
        state.frames.first { it.frameId == frameId }
            .editor.angles.first { it.id == angleId }
            .markers

    /**
     * Envoltorio de **paso directo** de las acciones reales de la pantalla: cuenta cuántas veces la
     * UI pidió cada operación y conserva el resultado real del `ViewModel`. No sustituye ninguna
     * acción ni inventa estados: todo se ejecuta en [base], que está cableada al `ViewModel` real.
     */
    private class RecordingActions(private val base: AngleMultiFrameEditorActions) {

        data class MoveCall(
            val frameId: AngleFrameId,
            val angleId: AngleId,
            val marker: AngleMarker,
            val point: AnglePoint,
        )

        val moves = mutableListOf<MoveCall>()
        var moveResult: AngleMultiFrameResult? = null
        var placeCalls = 0
        var draftMoveCalls = 0
        var selectFrameCalls = 0
        var selectAngleCalls = 0

        val actions: AngleMultiFrameEditorActions = base.copy(
            onPlacePoint = { frameId, point ->
                placeCalls += 1
                base.onPlacePoint(frameId, point)
            },
            onMoveMarker = { frameId, angleId, marker, point ->
                moves += MoveCall(frameId, angleId, marker, point)
                base.onMoveMarker(frameId, angleId, marker, point).also { moveResult = it }
            },
            onMoveDraftMarker = { frameId, marker, point ->
                draftMoveCalls += 1
                base.onMoveDraftMarker(frameId, marker, point)
            },
            onSelectFrame = { frameId ->
                selectFrameCalls += 1
                base.onSelectFrame(frameId)
            },
            onSelectAngle = { frameId, angleId ->
                selectAngleCalls += 1
                base.onSelectAngle(frameId, angleId)
            },
        )
    }
}
