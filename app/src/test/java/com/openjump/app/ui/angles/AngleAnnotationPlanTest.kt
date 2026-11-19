package com.openjump.app.ui.angles

import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorOutcome
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AnglePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Regresión JVM (sin emulador ni Android) del **plan** de anotación fuera de pantalla.
 *
 * Cubre lo que no necesita píxeles: validación tipada de entrada (vínculo declarado estado ↔ imagen,
 * dimensiones, presupuesto, selección y geometría), proyección a la imagen completa con el mismo
 * mapper del editor, orden de dibujo y determinismo. El rasterizado, la inmutabilidad del bitmap base
 * y la igualdad de píxeles fuera de las marcas se comprueban en `AngleAnnotationInstrumentedTest`.
 *
 * No es una prueba circular: el plan se calcula con las funciones puras de producción
 * ([planAngleAnnotation]) y las expectativas se escriben a mano con geometría conocida, no
 * reutilizando la implementación de dibujo.
 */
class AngleAnnotationPlanTest {

    private val source = "video:content://openjump/angle-annotation"
    private val generation = 3L
    private val anchor = AngleFrameAnchor(source, frameIndex = 5, sourcePtsUs = 200_000L)
    private val confirmation = AngleFrameConfirmation(anchor, generation, paused = true)
    private val defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = 0xFF35D0FF.toInt())

    /** Lo que la imagen congelada declara en su procedencia: el mismo vínculo que la ancla. */
    private val frameIdentity = AngleAnnotationFrameIdentity(
        sourceIdentity = anchor.sourceIdentity,
        frameIndex = anchor.frameIndex,
        sourcePtsUs = anchor.sourcePtsUs,
    )

    private val width = 480
    private val height = 360

    // Ángulo recto con vértice en B, dentro de la imagen y lejos de los bordes.
    private val angleA = AnglePoint(40.0, 80.0)
    private val angleB = AnglePoint(120.0, 80.0)
    private val angleC = AnglePoint(120.0, 20.0)

    private val secondA = AnglePoint(360.0, 280.0)
    private val secondB = AnglePoint(440.0, 280.0)
    private val secondC = AnglePoint(440.0, 200.0)

    private val thirdA = AnglePoint(200.0, 200.0)
    private val thirdB = AnglePoint(260.0, 200.0)
    private val thirdC = AnglePoint(260.0, 260.0)

    private val metrics = AngleAnnotationMetrics(densityScale = 1f)

    private fun applied(outcome: AngleEditorOutcome): AngleEditorState =
        (outcome as? AngleEditorOutcome.Applied)?.state
            ?: throw AssertionError("Esperaba Applied, llegó $outcome")

    private fun editor(): AngleEditorState =
        AngleEditorState.initial(source, generation, defaults)

    private fun AngleEditorState.place(point: AnglePoint): AngleEditorState =
        applied(placePoint(point, confirmation, selectOnComplete = false))

    private fun AngleEditorState.complete(
        a: AnglePoint,
        b: AnglePoint,
        c: AnglePoint,
    ): AngleEditorState = place(a).place(b).place(c)

    private fun threeAngles(): AngleEditorState =
        editor()
            .complete(angleA, angleB, angleC)
            .complete(secondA, secondB, secondC)
            .complete(thirdA, thirdB, thirdC)

    private fun budget(w: Int = width, h: Int = height): Long =
        w.toLong() * h.toLong() * 4L + maxOf(w, h).toLong() * 4L

    private fun ready(
        state: AngleEditorState,
        selectedAngleId: AngleId? = null,
        imageWidth: Int = width,
        imageHeight: Int = height,
        budgetBytes: Long = budget(imageWidth, imageHeight),
        metrics: AngleAnnotationMetrics = this.metrics,
        frameIdentity: AngleAnnotationFrameIdentity = this.frameIdentity,
    ): AngleAnnotationPlan {
        val result = planAngleAnnotation(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            frameIdentity = frameIdentity,
            state = state,
            selectedAngleId = selectedAngleId,
            metrics = metrics,
            budgetBytes = budgetBytes,
        )
        return (result as? AngleAnnotationPlanResult.Ready)?.plan
            ?: throw AssertionError("Esperaba plan listo, llegó $result")
    }

    private fun rejected(
        state: AngleEditorState,
        selectedAngleId: AngleId? = null,
        imageWidth: Int = width,
        imageHeight: Int = height,
        budgetBytes: Long = budget(imageWidth, imageHeight),
        frameIdentity: AngleAnnotationFrameIdentity = this.frameIdentity,
    ): AngleAnnotationPlanResult.Rejected {
        val result = planAngleAnnotation(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            frameIdentity = frameIdentity,
            state = state,
            selectedAngleId = selectedAngleId,
            metrics = metrics,
            budgetBytes = budgetBytes,
        )
        return result as? AngleAnnotationPlanResult.Rejected
            ?: throw AssertionError("Esperaba rechazo, llegó $result")
    }

    // -----------------------------------------------------------------------------------------
    // Vínculo declarado estado ↔ imagen
    // -----------------------------------------------------------------------------------------

    @Test
    fun `las mismas dimensiones no prueban la misma fuente`() {
        val otherSource = frameIdentity.copy(sourceIdentity = "video:content://openjump/otra-fuente")

        val result = rejected(threeAngles(), frameIdentity = otherSource)

        assertEquals(AngleAnnotationRejectionReason.SOURCE_IDENTITY_MISMATCH, result.reason)
        assertTrue(result.detail.contains("otra-fuente"))
    }

    @Test
    fun `rechaza un estado anclado a otro indice de fotograma`() {
        val otherFrame = frameIdentity.copy(frameIndex = anchor.frameIndex + 1)

        val result = rejected(threeAngles(), frameIdentity = otherFrame)

        assertEquals(AngleAnnotationRejectionReason.FRAME_INDEX_MISMATCH, result.reason)
    }

    @Test
    fun `rechaza un estado anclado a otro pts declarado`() {
        val otherPts = frameIdentity.copy(sourcePtsUs = anchor.sourcePtsUs + 1L)

        val result = rejected(threeAngles(), frameIdentity = otherPts)

        assertEquals(AngleAnnotationRejectionReason.FRAME_PTS_MISMATCH, result.reason)
    }

    @Test
    fun `el vinculo se comprueba tambien con todos los angulos ocultos`() {
        var state = threeAngles()
        listOf(1L, 2L, 3L).forEach { id -> state = applied(state.setVisible(AngleId(id), false)) }
        val hidden = state

        // Con el vínculo correcto no hay nada visible que dibujar, pero el estado es el de la imagen.
        assertTrue(ready(hidden).entries.isEmpty())
        // Ocultarlos no permite dibujar el estado sobre otra fuente, otro índice ni otro PTS.
        assertEquals(
            AngleAnnotationRejectionReason.SOURCE_IDENTITY_MISMATCH,
            rejected(hidden, frameIdentity = frameIdentity.copy(sourceIdentity = "video:otra")).reason,
        )
        assertEquals(
            AngleAnnotationRejectionReason.FRAME_INDEX_MISMATCH,
            rejected(hidden, frameIdentity = frameIdentity.copy(frameIndex = 6)).reason,
        )
        assertEquals(
            AngleAnnotationRejectionReason.FRAME_PTS_MISMATCH,
            rejected(hidden, frameIdentity = frameIdentity.copy(sourcePtsUs = 1L)).reason,
        )
    }

    @Test
    fun `un estado realmente vacio no produce plan de anotacion`() {
        val result = rejected(editor())

        assertEquals(AngleAnnotationRejectionReason.STATE_EMPTY, result.reason)
    }

    @Test
    fun `un borrador de dos puntos pendiente se rechaza en la imagen final`() {
        val state = editor().place(angleA).place(angleB)
        assertEquals(2, state.draft.points.size)

        val result = rejected(state)

        assertEquals(AngleAnnotationRejectionReason.DRAFT_PENDING, result.reason)
        assertTrue(result.detail.contains("2"))
    }

    @Test
    fun `un borrador de un punto pendiente tambien se rechaza`() {
        val state = editor().place(angleA)

        assertEquals(AngleAnnotationRejectionReason.DRAFT_PENDING, rejected(state).reason)
    }

    @Test
    fun `un estado anclado con el vinculo correcto se planifica`() {
        val plan = ready(threeAngles())

        assertEquals(listOf(AngleId(1L), AngleId(2L), AngleId(3L)), plan.entries.map { it.angleId })
    }

    // -----------------------------------------------------------------------------------------
    // Visibilidad y orden de dibujo
    // -----------------------------------------------------------------------------------------

    @Test
    fun `el plan proyecta solo los angulos visibles`() {
        val state = applied(threeAngles().setVisible(AngleId(2L), false))

        val plan = ready(state)

        assertEquals(listOf(AngleId(1L), AngleId(3L)), plan.entries.map { it.angleId })
    }

    @Test
    fun `el angulo resaltado se dibuja al final y solo el`() {
        val state = threeAngles()

        val plan = ready(state, selectedAngleId = AngleId(1L))

        assertEquals(
            listOf(AngleId(2L), AngleId(3L), AngleId(1L)),
            plan.entries.map { it.angleId },
        )
        assertEquals(
            listOf(false, false, true),
            plan.entries.map { it.selected },
        )
    }

    @Test
    fun `la seleccion es la pedida y no la del estado`() {
        val state = applied(threeAngles().select(AngleId(1L)))

        val withoutSelection = ready(state, selectedAngleId = null)
        assertTrue(withoutSelection.entries.none { it.selected })

        val withSelection = ready(state, selectedAngleId = AngleId(2L))
        assertEquals(
            listOf(AngleId(2L)),
            withSelection.entries.filter { it.selected }.map { it.angleId },
        )
    }

    @Test
    fun `rechaza una seleccion que no es un angulo visible`() {
        val state = applied(threeAngles().setVisible(AngleId(2L), false))

        val result = rejected(state, selectedAngleId = AngleId(2L))

        assertEquals(AngleAnnotationRejectionReason.SELECTION_INCONSISTENT, result.reason)
    }

    @Test
    fun `rechaza una seleccion inexistente`() {
        val result = rejected(threeAngles(), selectedAngleId = AngleId(99L))

        assertEquals(AngleAnnotationRejectionReason.SELECTION_INCONSISTENT, result.reason)
    }

    // -----------------------------------------------------------------------------------------
    // Geometría y proyección
    // -----------------------------------------------------------------------------------------

    @Test
    fun `el plan proyecta el pixel de la imagen al mismo sistema del lienzo`() {
        val plan = ready(editor().complete(angleA, angleB, angleC))
        val entry = plan.entries.single()

        // El mapper del editor normaliza width-1 sobre width: en el centro la diferencia es < 1 px.
        assertTrue(abs(entry.a.x - angleA.x) < 1.0)
        assertTrue(abs(entry.a.y - angleA.y) < 1.0)
        assertTrue(abs(entry.b.x - angleB.x) < 1.0)
        assertTrue(abs(entry.b.y - angleB.y) < 1.0)
        assertEquals(width.toDouble(), plan.contentRect.width, 1e-9)
        assertEquals(height.toDouble(), plan.contentRect.height, 1e-9)
        assertEquals(0.0, plan.contentRect.left, 1e-9)
        assertEquals(0.0, plan.contentRect.top, 1e-9)
    }

    @Test
    fun `acepta marcas en la esquina inferior derecha`() {
        // Esquina exacta (width-1, height-1): es un píxel real de la imagen, no una coordenada inválida.
        val corner = AnglePoint((width - 1).toDouble(), (height - 1).toDouble())
        val state = editor().complete(
            AnglePoint((width - 11).toDouble(), (height - 1).toDouble()),
            corner,
            AnglePoint((width - 1).toDouble(), (height - 11).toDouble()),
        )

        val plan = ready(state)

        val vertex = plan.entries.single().b
        assertEquals(width.toDouble(), vertex.x, 1e-9)
        assertEquals(height.toDouble(), vertex.y, 1e-9)
        assertTrue(plan.contentRect.contains(vertex))
    }

    @Test
    fun `rechaza marcas fuera de la imagen`() {
        val outside = editor().complete(
            AnglePoint((width + 1).toDouble(), 80.0),
            angleB,
            angleC,
        )

        val result = rejected(outside)

        assertEquals(AngleAnnotationRejectionReason.GEOMETRY_INCONSISTENT, result.reason)
    }

    @Test
    fun `rechaza marcas negativas`() {
        val negative = editor().complete(
            angleA,
            angleB,
            AnglePoint(-1.0, -1.0),
        )

        val result = rejected(negative)

        assertEquals(AngleAnnotationRejectionReason.GEOMETRY_INCONSISTENT, result.reason)
    }

    @Test
    fun `las dimensiones no positivas se rechazan como incongruentes`() {
        assertEquals(
            AngleAnnotationRejectionReason.DIMENSIONS_INCONSISTENT,
            rejected(threeAngles(), imageWidth = 0).reason,
        )
        assertEquals(
            AngleAnnotationRejectionReason.DIMENSIONS_INCONSISTENT,
            rejected(threeAngles(), imageHeight = 0).reason,
        )
    }

    @Test
    fun `las dimensiones fuera de la envolvente no son presupuestables`() {
        val result = rejected(
            threeAngles(),
            imageWidth = 16_385,
            imageHeight = 8,
            budgetBytes = Long.MAX_VALUE,
        )

        assertEquals(AngleAnnotationRejectionReason.MEMORY_BUDGET_EXCEEDED, result.reason)
    }

    // -----------------------------------------------------------------------------------------
    // Presupuesto y determinismo
    // -----------------------------------------------------------------------------------------

    @Test
    fun `el presupuesto es la copia argb mas una fila y se exige antes de asignar`() {
        val expected = width.toLong() * height.toLong() * 4L + maxOf(width, height).toLong() * 4L
        val plan = ready(threeAngles())
        assertEquals(expected, plan.requiredBytes)

        val insufficient = rejected(threeAngles(), budgetBytes = expected - 1L)
        assertEquals(AngleAnnotationRejectionReason.MEMORY_BUDGET_EXCEEDED, insufficient.reason)

        assertTrue(insufficient.detail.contains(expected.toString()))
    }

    @Test
    fun `el plan es determinista para la misma entrada`() {
        val state = threeAngles()

        val first = ready(state, selectedAngleId = AngleId(2L))
        val second = ready(state, selectedAngleId = AngleId(2L))

        assertEquals(first, second)
        assertEquals(first.entries.map { it.angleId }, second.entries.map { it.angleId })
    }

    @Test
    fun `unas metricas no finitas o no positivas son un error de programacion`() {
        assertThrows(IllegalArgumentException::class.java) { AngleAnnotationMetrics(densityScale = 0f) }
        assertThrows(IllegalArgumentException::class.java) { AngleAnnotationMetrics(densityScale = -1f) }
        assertThrows(IllegalArgumentException::class.java) { AngleAnnotationMetrics(densityScale = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            AngleAnnotationMetrics(densityScale = 1f, fontScale = 0f)
        }
        val valid = AngleAnnotationMetrics(densityScale = 1f)
        assertFalse(valid.densityScale <= 0f)
    }
}
