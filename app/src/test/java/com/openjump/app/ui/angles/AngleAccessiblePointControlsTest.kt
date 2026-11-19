package com.openjump.app.ui.angles

import com.openjump.app.angles.AngleAccessibleCursor
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorOutcome
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AnglePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard JVM del camino accesible de [AngleEditorContent].
 *
 * Cubre lo que no necesita Android: el cursor accesible (pasos, límites, coordenadas no finitas), la
 * derivación pura del objetivo (borrador A→B→C, ángulo seleccionado, elegir ángulo, encuadre) y el
 * cableado de la pantalla con la capa (acción personalizada y acciones que devuelven si el cambio se
 * aplicó). La composición real y las guardas de frame se validan con instrumentación.
 */
class AngleAccessiblePointControlsTest {

    private val source = "video:content://openjump/angle-accessible-unit"
    private val generation = 4L
    private val anchor = AngleFrameAnchor(source, frameIndex = 2, sourcePtsUs = 66_000L)
    private val confirmation = AngleFrameConfirmation(anchor, generation, paused = true)
    private val defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = 0xFF1E88E5.toInt())
    private val geometry = VideoPresentationGeometry(200, 100)

    private fun applied(outcome: AngleEditorOutcome): AngleEditorState =
        (outcome as? AngleEditorOutcome.Applied)?.state
            ?: throw AssertionError("Esperaba Applied, llegó $outcome")

    private fun editorWithAngle(): AngleEditorState {
        var state = AngleEditorState.initial(source, generation, defaults)
        listOf(
            AnglePoint(20.0, 20.0),
            AnglePoint(50.0, 50.0),
            AnglePoint(80.0, 20.0),
        ).forEach { point -> state = applied(state.placePoint(point, confirmation)) }
        return applied(state.select(AngleId(1L)))
    }

    // --- cursor accesible -------------------------------------------------------------------

    @Test
    fun `el paso es el 2 por ciento del lado menor con minimo de 4 px`() {
        assertEquals(4.0, AngleAccessibleCursor.step(geometry), 0.0)
        // Lado menor 3000 → 60 px: el ajuste fino escala con la resolución real.
        assertEquals(60.0, AngleAccessibleCursor.step(VideoPresentationGeometry(4000, 3000)), 0.0)
        // Imagen diminuta: se respeta el mínimo para que siga siendo ajustable.
        assertEquals(4.0, AngleAccessibleCursor.step(VideoPresentationGeometry(10, 10)), 0.0)
    }

    @Test
    fun `el punto de partida es el centro de la imagen`() {
        assertEquals(AnglePoint(100.0, 50.0), AngleAccessibleCursor.centered(geometry))
    }

    @Test
    fun `los pasos se acumulan y se recortan al ultimo pixel valido`() {
        val start = AngleAccessibleCursor.centered(geometry)
        val right = AngleAccessibleCursor.moved(geometry, start, 4.0, 0.0)
        assertEquals(AnglePoint(104.0, 50.0), right)
        // 200 pasos a la derecha: el límite es width-1, nunca width.
        assertEquals(
            AnglePoint(199.0, 50.0),
            AngleAccessibleCursor.moved(geometry, start, 4.0 * 200, 0.0),
        )
        // Hacia fuera por la izquierda/arriba: se recorta a 0.
        assertEquals(
            AnglePoint(0.0, 0.0),
            AngleAccessibleCursor.moved(geometry, start, -400.0, -400.0),
        )
        assertEquals(
            AnglePoint(199.0, 99.0),
            AngleAccessibleCursor.clamp(geometry, AnglePoint(5_000.0, 5_000.0)),
        )
    }

    @Test
    fun `una coordenada no finita cae al centro y nunca se propaga`() {
        val nan = AngleAccessibleCursor.clamp(geometry, AnglePoint(Double.NaN, 12.0))
        assertEquals(AnglePoint(100.0, 50.0), nan)
        val infinite = AngleAccessibleCursor.moved(
            geometry,
            AnglePoint(Double.POSITIVE_INFINITY, 10.0),
            0.0,
            0.0,
        )
        assertEquals(AnglePoint(100.0, 50.0), infinite)
        assertTrue(nan.isFinite)
        assertTrue(infinite.isFinite)
    }

    // --- objetivo ---------------------------------------------------------------------------

    @Test
    fun `el borrador coloca A, B y C en orden segun los puntos ya puestos`() {
        assertEquals(AngleMarker.A, nextDraftMarker(0))
        assertEquals(AngleMarker.B, nextDraftMarker(1))
        assertEquals(AngleMarker.C, nextDraftMarker(2))
        // Defensivo: nunca propone una cuarta marca.
        assertEquals(AngleMarker.C, nextDraftMarker(3))
        assertEquals(
            AngleAccessibleTarget.Draft(AngleMarker.B),
            angleAccessibleTarget(
                AngleOverlayMode.PLACEMENT,
                listOf(AnglePoint(1.0, 2.0)),
                null,
                AngleMarker.A,
            ),
        )
    }

    @Test
    fun `con un angulo visible seleccionado se ajusta la marca elegida`() {
        val state = editorWithAngle()
        val angle = state.angles.single()
        val target = angleAccessibleTarget(
            mode = AngleOverlayMode.EDIT,
            draftPoints = emptyList(),
            selectedAngle = angle,
            marker = AngleMarker.B,
        )
        assertEquals(
            AngleAccessibleTarget.ExistingAngle(
                angleId = angle.id,
                angleName = angle.name,
                marker = AngleMarker.B,
                point = angle.markers.b,
            ),
            target,
        )
        assertEquals(AnglePoint(80.0, 20.0), angle.markers.pointFor(AngleMarker.C))
        assertEquals(angle.markers.a, angle.markers.pointFor(AngleMarker.A))
    }

    @Test
    fun `sin seleccion se pide elegir un angulo y en encuadre no hay controles`() {
        assertEquals(
            AngleAccessibleTarget.PickAngle,
            angleAccessibleTarget(AngleOverlayMode.EDIT, emptyList(), null, AngleMarker.A),
        )
        assertNull(
            angleAccessibleTarget(AngleOverlayMode.FRAMING, emptyList(), null, AngleMarker.A),
        )
        // Un ángulo oculto no es interactivo: tampoco se ajusta desde los controles.
        val state = editorWithAngle()
        val visible = state.angles.single()
        val hidden = applied(state.setVisible(visible.id, false)).angles.single()
        assertFalse(hidden.visible)
        assertEquals(
            AngleAccessibleTarget.PickAngle,
            angleAccessibleTarget(AngleOverlayMode.EDIT, emptyList(), hidden, AngleMarker.A),
        )
    }

    // --- objetivo del inspector (borrador A/B explícito) -----------------------------------

    @Test
    fun `el inspector ajusta A o B explicitos y ofrece el siguiente C sin descartar`() {
        val a = AnglePoint(10.0, 10.0)
        val b = AnglePoint(60.0, 20.0)
        val two = listOf(a, b)
        assertEquals(
            AngleAccessibleTarget.ExistingDraft(AngleMarker.A, a),
            angleInspectorPointTarget(two, null, AngleMarker.A, adjustDraft = true),
        )
        assertEquals(
            AngleAccessibleTarget.ExistingDraft(AngleMarker.B, b),
            angleInspectorPointTarget(two, null, AngleMarker.B, adjustDraft = true),
        )
        // C todavía no está colocado: se ofrece el siguiente punto, nunca un ajuste inventado.
        assertEquals(
            AngleAccessibleTarget.Draft(AngleMarker.C),
            angleInspectorPointTarget(two, null, AngleMarker.C, adjustDraft = true),
        )
        assertEquals(
            AngleAccessibleTarget.Draft(AngleMarker.C),
            angleInspectorPointTarget(two, null, AngleMarker.A, adjustDraft = false),
        )
        // B pedido con solo A colocado: siguiente punto B, sin tocar A.
        assertEquals(
            AngleAccessibleTarget.Draft(AngleMarker.B),
            angleInspectorPointTarget(listOf(a), null, AngleMarker.B, adjustDraft = true),
        )
    }

    @Test
    fun `sin borrador la intencion de colocar ofrece A y sin intencion delega en el legacy`() {
        assertEquals(
            AngleAccessibleTarget.Draft(AngleMarker.A),
            angleInspectorPointTarget(
                emptyList(),
                null,
                AngleMarker.C,
                adjustDraft = true,
                placementIntent = true,
            ),
        )
        assertEquals(
            AngleAccessibleTarget.PickAngle,
            angleInspectorPointTarget(emptyList(), null, AngleMarker.A, adjustDraft = false),
        )
        // Un ángulo elegido con el borrador vacío se ajusta con el target legacy de siempre.
        val angle = editorWithAngle().angles.single()
        assertEquals(
            AngleAccessibleTarget.ExistingAngle(
                angleId = angle.id,
                angleName = angle.name,
                marker = AngleMarker.B,
                point = angle.markers.b,
            ),
            angleInspectorPointTarget(emptyList(), angle, AngleMarker.B, adjustDraft = false),
        )
    }

    @Test
    fun `inspeccionar un angulo con A y B pendientes conserva el borrador`() {
        val angle = editorWithAngle().angles.single()
        val pending = listOf(AnglePoint(30.0, 40.0), AnglePoint(70.0, 40.0))
        // Elegir el ángulo existente no descarta ni reescribe el borrador pendiente.
        assertEquals(
            AngleAccessibleTarget.ExistingAngle(
                angleId = angle.id,
                angleName = angle.name,
                marker = AngleMarker.C,
                point = angle.markers.c,
            ),
            angleInspectorPointTarget(pending, angle, AngleMarker.C, adjustDraft = false),
        )
        // Y el ajuste del borrador sigue apuntando a sus propios puntos, no a los del ángulo.
        assertEquals(
            AngleAccessibleTarget.ExistingDraft(AngleMarker.A, pending[0]),
            angleInspectorPointTarget(pending, angle, AngleMarker.A, adjustDraft = true),
        )
    }

    // --- cableado de la pantalla -------------------------------------------------------------

    @Test
    fun `la pantalla conecta la accion personalizada de la capa con el panel accesible`() {
        val screen = readScreen()
        assertTrue(
            "AngleOverlay debe recibir la etiqueta localizada de los controles accesibles",
            screen.contains("accessibleControlsLabel ="),
        )
        assertTrue(
            "AngleOverlay debe recibir el manejador que abre el panel accesible",
            screen.contains("onAccessibleControlsRequested ="),
        )
        assertTrue(
            "El panel accesible se compone en la pantalla de producción",
            screen.contains("AngleAccessiblePointControls("),
        )
        assertTrue(
            "La etiqueta de la acción personalizada debe ser un recurso localizado",
            screen.contains("R.string.angle_accessible_action"),
        )
    }

    @Test
    fun `los gestos devuelven si se aplicaron para no confirmar en falso`() {
        val screen = readScreen()
        assertTrue(
            "onPlacePoint debe devolver Boolean (aplicado o no)",
            screen.contains("val onPlacePoint: (AnglePoint) -> Boolean"),
        )
        assertTrue(
            "onMoveMarker debe devolver Boolean (aplicado o no)",
            screen.contains("val onMoveMarker: (AngleId, AngleMarker, AnglePoint) -> Boolean"),
        )
        assertTrue(
            "onMoveDraftMarker debe devolver Boolean (aplicado o no)",
            screen.contains("val onMoveDraftMarker: (AngleMarker, AnglePoint) -> Boolean"),
        )
        assertTrue(
            "La pantalla debe comprobar Applied antes de dar el punto por colocado",
            screen.contains("AngleEditorActionResult.Applied"),
        )
        assertFalse(
            "El panel accesible no debe decidir el frame por su cuenta: la guarda es del ViewModel",
            screen.contains("AngleFrameConfirmation("),
        )
    }

    private fun readScreen(): String {
        val relative = "app/src/main/java/com/openjump/app/ui/angles/AngleEditorScreen.kt"
        val direct = relative.removePrefix("app/")
        val tried = mutableListOf<File>()
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            for (candidate in listOf(File(directory, relative), File(directory, direct))) {
                tried += candidate
                if (candidate.isFile) return candidate.readText()
            }
            directory = directory.parentFile
        }
        throw AssertionError(
            "No se encuentra '$relative'. Directorio de trabajo: ${File(".").absolutePath}. " +
                "Rutas probadas: ${tried.joinToString { it.absolutePath }}",
        )
    }
}
