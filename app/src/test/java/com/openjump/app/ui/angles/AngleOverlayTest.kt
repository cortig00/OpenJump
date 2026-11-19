package com.openjump.app.ui.angles

import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorOutcome
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AnglePoint
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.selector.ContentRect
import com.openjump.app.ui.selector.CropAlignment
import com.openjump.app.ui.selector.VideoCoordinateMapper
import com.openjump.app.ui.selector.VideoScaleMode
import com.openjump.app.ui.selector.ViewportSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión JVM (sin emulador ni Compose runtime) de [AngleOverlay].
 *
 * Cubre la lógica pura que hace testeable la capa: proyección con [VideoCoordinateMapper]
 * (post-rotación, FIT/CROP, zoom y pan), arco interior 0..180, acotado de etiquetas y del arrastre
 * dentro del frame, orden de dibujo, y la propiedad del gesto (asas activas del borrador y del
 * ángulo seleccionado, selección explícita del resto, letterbox ignorado, ENCUADRAR sin gestos).
 */
class AngleOverlayTest {

    private val source = "video:content://openjump/angles-overlay"
    private val generation = 7L
    private val anchor = AngleFrameAnchor(source, frameIndex = 12, sourcePtsUs = 400_000L)
    private val confirmation = AngleFrameConfirmation(anchor, generation, paused = true)
    private val defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = 0xFF35D0FF.toInt())

    // Puntos de vídeo elegidos para que el mapeo FIT 200x100 → 400x100 dé píxeles legibles.
    private val rightAngleA = AnglePoint(99.5, 10.0)
    private val rightAngleB = AnglePoint(99.5, 49.5)
    private val rightAngleC = AnglePoint(150.0, 49.5)
    private val secondAngleA = AnglePoint(20.0, 80.0)
    private val secondAngleB = AnglePoint(40.0, 80.0)
    private val secondAngleC = AnglePoint(40.0, 95.0)

    private fun fitMapper(): VideoCoordinateMapper = VideoCoordinateMapper(
        video = VideoPresentationGeometry(width = 200, height = 100),
        viewport = ViewportSize(width = 400.0, height = 100.0),
        scaleMode = VideoScaleMode.FIT,
    )

    private fun cropMapper(
        zoom: Double = 2.0,
        alignment: CropAlignment = CropAlignment.Centered,
    ): VideoCoordinateMapper = VideoCoordinateMapper(
        video = VideoPresentationGeometry(width = 200, height = 100),
        viewport = ViewportSize(width = 200.0, height = 100.0),
        scaleMode = VideoScaleMode.CROP,
        cropAlignment = alignment,
        zoom = zoom,
    )

    private fun VideoCoordinateMapper.viewOf(point: AnglePoint): ImagePoint =
        videoToView(ImagePoint(point.x, point.y))

    private fun metrics(
        handleRadiusPx: Double = 24.0,
        markerRadiusPx: Double = 24.0,
        lineTolerancePx: Double = 12.0,
    ) = AngleOverlayTouchMetrics(handleRadiusPx, markerRadiusPx, lineTolerancePx)

    private fun editor(): AngleEditorState =
        AngleEditorState.initial(source, generation, defaults)

    private fun applied(outcome: AngleEditorOutcome): AngleEditorState =
        (outcome as? AngleEditorOutcome.Applied)?.state
            ?: throw AssertionError("Esperaba Applied, llegó $outcome")

    private fun AngleEditorState.place(point: AnglePoint): AngleEditorState =
        applied(placePoint(point, confirmation, selectOnComplete = false))

    private fun AngleEditorState.complete(
        a: AnglePoint,
        b: AnglePoint,
        c: AnglePoint,
    ): AngleEditorState = place(a).place(b).place(c)

    private fun AngleEditorState.withAngles(): AngleEditorState =
        complete(rightAngleA, rightAngleB, rightAngleC)
            .complete(secondAngleA, secondAngleB, secondAngleC)

    private fun assertApproximates(expected: Double, actual: Double) {
        assertEquals(expected, actual, 1e-9)
    }

    // -----------------------------------------------------------------------------------------
    // Arco interior: magnitud 0..180 y dirección
    // -----------------------------------------------------------------------------------------

    @Test
    fun `arco interior de un angulo recto barre 90 grados por el lado interior`() {
        val vertex = ImagePoint(200.0, 50.0)
        // A encima del vértice (270° en pantalla) y C a la derecha (0°).
        val arc = interiorArcSpec(
            sideAView = ImagePoint(200.0, 10.0),
            vertexView = vertex,
            sideCView = ImagePoint(250.0, 50.0),
            minRadiusPx = 0.0,
            maxRadiusPx = 1_000.0,
        )

        assertNotNull(arc)
        requireNotNull(arc)
        assertApproximates(270.0, arc.startAngleDegrees)
        assertApproximates(90.0, arc.sweepAngleDegrees)
        assertApproximates(315.0, arc.labelAngleDegrees)
        assertApproximates(40.0 * 0.35, arc.radiusPx)
        assertTrue(kotlin.math.abs(arc.sweepAngleDegrees) <= 180.0)
    }

    @Test
    fun `arco interior normaliza el complemento reflejo a la diferencia angular corta`() {
        val vertex = ImagePoint(0.0, 0.0)
        val sideA = ImagePoint(100.0, 0.0)
        val sideC = ImagePoint(50.0, 86.60254037844386) // 60° en pantalla (y hacia abajo)
        val arc = interiorArcSpec(sideA, vertex, sideC, 0.0, 1_000.0)

        assertNotNull(arc)
        requireNotNull(arc)
        assertApproximates(0.0, arc.startAngleDegrees)
        assertApproximates(60.0, arc.sweepAngleDegrees)
        assertApproximates(30.0, arc.labelAngleDegrees)

        // Mismas direcciones en orden inverso: el barrido cambia de signo, no de magnitud.
        val reversed = interiorArcSpec(sideC, vertex, sideA, 0.0, 1_000.0)
        requireNotNull(reversed)
        assertApproximates(60.0, reversed.startAngleDegrees)
        assertApproximates(-60.0, reversed.sweepAngleDegrees)
        assertApproximates(30.0, reversed.labelAngleDegrees)
    }

    @Test
    fun `arco interior nunca dibuja el reflejo de mas de 180 grados`() {
        val vertex = ImagePoint(0.0, 0.0)
        val sideAt10 = ImagePoint(
            x = 100.0 * kotlin.math.cos(Math.toRadians(10.0)),
            y = 100.0 * kotlin.math.sin(Math.toRadians(10.0)),
        )
        val sideAt300 = ImagePoint(
            x = 100.0 * kotlin.math.cos(Math.toRadians(300.0)),
            y = 100.0 * kotlin.math.sin(Math.toRadians(300.0)),
        )
        val arc = interiorArcSpec(sideAt10, vertex, sideAt300, 0.0, 1_000.0)

        requireNotNull(arc)
        assertApproximates(10.0, arc.startAngleDegrees)
        assertApproximates(-70.0, arc.sweepAngleDegrees)
        assertApproximates(335.0, arc.labelAngleDegrees)
        assertTrue(kotlin.math.abs(arc.sweepAngleDegrees) <= 180.0)
    }

    @Test
    fun `arco llano de 180 grados usa medio giro`() {
        val arc = interiorArcSpec(
            sideAView = ImagePoint(-40.0, 0.0),
            vertexView = ImagePoint(0.0, 0.0),
            sideCView = ImagePoint(100.0, 0.0),
            minRadiusPx = 0.0,
            maxRadiusPx = 1_000.0,
        )

        requireNotNull(arc)
        assertApproximates(180.0, arc.startAngleDegrees)
        assertApproximates(180.0, kotlin.math.abs(arc.sweepAngleDegrees))
        assertApproximates(270.0, arc.labelAngleDegrees)
    }

    @Test
    fun `radio del arco respeta minimo maximo y el lado mas corto`() {
        val vertex = ImagePoint(0.0, 0.0)
        val shortSide = ImagePoint(20.0, 0.0)
        val longSide = ImagePoint(0.0, 100.0)

        val minClamped = interiorArcSpec(shortSide, vertex, longSide, minRadiusPx = 10.0, maxRadiusPx = 1_000.0)
        requireNotNull(minClamped)
        assertApproximates(10.0, minClamped.radiusPx)

        val maxClamped = interiorArcSpec(
            sideAView = ImagePoint(100.0, 0.0),
            vertexView = vertex,
            sideCView = ImagePoint(0.0, 100.0),
            minRadiusPx = 0.0,
            maxRadiusPx = 20.0,
        )
        requireNotNull(maxClamped)
        assertApproximates(20.0, maxClamped.radiusPx)

        val shorterThanMin = interiorArcSpec(
            sideAView = ImagePoint(5.0, 0.0),
            vertexView = vertex,
            sideCView = longSide,
            minRadiusPx = 10.0,
            maxRadiusPx = 100.0,
        )
        requireNotNull(shorterThanMin)
        assertApproximates(5.0, shorterThanMin.radiusPx)
        assertTrue(shorterThanMin.radiusPx <= 5.0)
    }

    @Test
    fun `arco descarta lados sin longitud y limites invalidos`() {
        val vertex = ImagePoint(10.0, 10.0)
        assertNull(interiorArcSpec(vertex, vertex, ImagePoint(50.0, 10.0), 0.0, 100.0))
        assertNull(interiorArcSpec(ImagePoint(50.0, 10.0), vertex, vertex, 0.0, 100.0))
        assertNull(interiorArcSpec(ImagePoint(50.0, 10.0), vertex, ImagePoint(10.0, 60.0), 20.0, 10.0))
        assertNull(interiorArcSpec(ImagePoint(50.0, 10.0), vertex, ImagePoint(10.0, 60.0), 0.0, 100.0, radiusFraction = 0.0))
        assertNull(interiorArcSpec(ImagePoint(50.0, 10.0), vertex, ImagePoint(10.0, 60.0), -1.0, 100.0))
    }

    @Test
    fun `la etiqueta se acota al rectangulo de contenido y no invade el letterbox`() {
        val content = ContentRect(left = 100.0, top = 0.0, width = 200.0, height = 100.0)

        val topLeftClamped = clampLabelTopLeft(ImagePoint(90.0, -10.0), 40.0, 20.0, content)
        assertEquals(ImagePoint(100.0, 0.0), topLeftClamped)

        val bottomRightClamped = clampLabelTopLeft(ImagePoint(290.0, 95.0), 40.0, 20.0, content)
        assertEquals(ImagePoint(260.0, 80.0), bottomRightClamped)

        val widerThanContent = clampLabelTopLeft(ImagePoint(150.0, 50.0), 300.0, 200.0, content)
        assertEquals(ImagePoint(100.0, 0.0), widerThanContent)
    }

    // -----------------------------------------------------------------------------------------
    // Transformación de presentación y acotado del arrastre
    // -----------------------------------------------------------------------------------------

    @Test
    fun `el mapeo FIT coincide con la proyeccion manual y rechaza el letterbox`() {
        val mapper = fitMapper()

        assertEquals(ImagePoint(200.0, 50.0), mapper.viewOf(rightAngleB))
        assertNull(mapper.viewToVideo(ImagePoint(50.0, 50.0)))
        assertNull(markerDragTarget(mapper, ImagePoint(50.0, 50.0), AnglePoint(0.0, 0.0)))
    }

    @Test
    fun `el arrastre se congela fuera de la imagen visible`() {
        val mapper = fitMapper()

        assertNull(markerDragTarget(mapper, ImagePoint(99.9, 50.0), AnglePoint(0.0, 0.0)))
        assertNull(markerDragTarget(mapper, ImagePoint(300.1, 50.0), AnglePoint(0.0, 0.0)))
        assertNull(markerDragTarget(mapper, ImagePoint(200.0, -0.1), AnglePoint(0.0, 0.0)))

        val inside = markerDragTarget(mapper, ImagePoint(200.0, 50.0), AnglePoint(0.0, 0.0))
        requireNotNull(inside)
        assertEquals(99.5, inside.x, 1e-9)
        assertEquals(49.5, inside.y, 1e-9)
    }

    @Test
    fun `el arrastre acota las coordenadas al frame tras compensar el agarre`() {
        val mapper = fitMapper()

        val bottomRight = markerDragTarget(mapper, ImagePoint(200.0, 50.0), AnglePoint(500.0, 500.0))
        requireNotNull(bottomRight)
        assertEquals(199.0, bottomRight.x, 1e-9)
        assertEquals(99.0, bottomRight.y, 1e-9)

        val topLeft = markerDragTarget(mapper, ImagePoint(200.0, 50.0), AnglePoint(-500.0, -500.0))
        requireNotNull(topLeft)
        assertEquals(0.0, topLeft.x, 1e-9)
        assertEquals(0.0, topLeft.y, 1e-9)
    }

    @Test
    fun `CROP con zoom y pan se reutiliza para proyectar y arrastrar`() {
        val centered = cropMapper()
        assertEquals(ImagePoint(100.0, 50.0), centered.viewOf(rightAngleB))
        assertEquals(99.5, centered.viewToVideo(ImagePoint(100.0, 50.0))!!.x, 1e-9)

        val panned = cropMapper(alignment = CropAlignment(x = 0.0, y = 0.5))
        assertEquals(0.0, panned.contentRect.left, 1e-9)
        val video = panned.viewToVideo(ImagePoint(100.0, 50.0))
        requireNotNull(video)
        assertEquals(49.75, video.x, 1e-9)
        assertEquals(49.5, video.y, 1e-9)

        val target = markerDragTarget(panned, ImagePoint(100.0, 50.0), AnglePoint(10.0, -10.0))
        requireNotNull(target)
        assertEquals(59.75, target.x, 1e-9)
        assertEquals(39.5, target.y, 1e-9)
    }

    // -----------------------------------------------------------------------------------------
    // Layout: N visibles sin límite, ocultos fuera, borrador y orden de dibujo
    // -----------------------------------------------------------------------------------------

    @Test
    fun `el layout proyecta todos los angulos visibles sin limite arbitrario`() {
        var state = editor()
        repeat(25) { index ->
            state = state.complete(
                AnglePoint(10.0 + index, 30.0),
                AnglePoint(20.0 + index, 30.0),
                AnglePoint(20.0 + index, 40.0),
            )
        }
        val mapper = fitMapper()
        val layout = angleOverlayLayout(mapper, state)

        assertEquals(25, layout.entries.size)
        assertEquals((1L..25L).map { AngleId(it) }, layout.entries.map { it.angleId })
        assertTrue(layout.entries.all { it.degrees > 89.999 && it.degrees < 90.001 })
        assertTrue(layout.entries.all { !it.selected })
        assertEquals(mapper.viewOf(AnglePoint(10.0, 30.0)), layout.entries.first().a)

        val hidden = applied(state.setVisible(AngleId(7L), false))
        val hiddenLayout = angleOverlayLayout(mapper, hidden)
        assertEquals(24, hiddenLayout.entries.size)
        assertTrue(hiddenLayout.entries.none { it.angleId == AngleId(7L) })
    }

    @Test
    fun `el layout expone los puntos del borrador A y B en orden`() {
        var state = editor().place(rightAngleA).place(rightAngleB)
        val mapper = fitMapper()

        var layout = angleOverlayLayout(mapper, state)
        assertEquals(2, layout.draftPoints.size)
        assertEquals(AngleMarker.A, layout.draftPoints[0].marker)
        assertEquals(AngleMarker.B, layout.draftPoints[1].marker)
        assertEquals(rightAngleA, layout.draftPoints[0].videoPoint)
        assertEquals(mapper.viewOf(rightAngleA), layout.draftPoints[0].viewPoint)
        assertEquals(mapper.viewOf(rightAngleB), layout.draftPoints[1].viewPoint)

        state = state.place(rightAngleC)
        layout = angleOverlayLayout(mapper, state)
        assertEquals(1, layout.entries.size)
        assertTrue(layout.draftPoints.isEmpty())
    }

    @Test
    fun `el angulo seleccionado se dibuja al final y conserva el orden de creacion del resto`() {
        val state = applied(editor().withAngles().select(AngleId(2L)))
        val layout = angleOverlayLayout(fitMapper(), state)

        assertEquals(listOf(AngleId(1L), AngleId(2L)), layout.entries.map { it.angleId })
        assertTrue(layout.entries[1].selected)
        assertEquals(
            listOf(AngleId(1L), AngleId(2L)),
            angleOverlayDrawOrder(layout.entries).map { it.angleId },
        )

        val three = state.complete(AnglePoint(150.0, 20.0), AnglePoint(160.0, 20.0), AnglePoint(160.0, 30.0))
        val threeLayout = angleOverlayLayout(fitMapper(), three)
        assertEquals(
            listOf(AngleId(1L), AngleId(3L), AngleId(2L)),
            angleOverlayDrawOrder(threeLayout.entries).map { it.angleId },
        )
    }

    @Test
    fun `B usa una forma propia para no depender del color`() {
        assertEquals(AngleMarkerShape.DIAMOND, markerShapeFor(AngleMarker.B))
        assertEquals(AngleMarkerShape.CIRCLE, markerShapeFor(AngleMarker.A))
        assertEquals(AngleMarkerShape.CIRCLE, markerShapeFor(AngleMarker.C))
    }

    @Test
    fun `la medicion del layout llega sin redondear al formateador del llamante`() {
        // 60° exactos: BA hacia arriba y BC girado 60° hacia la derecha.
        val a = AnglePoint(50.0, 0.0)
        val b = AnglePoint(50.0, 50.0)
        val c = AnglePoint(50.0 + 50.0 * kotlin.math.sin(Math.toRadians(60.0)), 50.0 - 50.0 * kotlin.math.cos(Math.toRadians(60.0)))
        val state = editor().complete(a, b, c)
        val entry = angleOverlayLayout(fitMapper(), state).entries.single()

        assertEquals(60.0, entry.degrees, 1e-9)
    }

    // -----------------------------------------------------------------------------------------
    // Propiedad del gesto: asas activas, selección explícita y prioridades
    // -----------------------------------------------------------------------------------------

    @Test
    fun `el asa del angulo seleccionado gana sobre el cuerpo de otro angulo superpuesto`() {
        val crossing = editor()
            .complete(
                rightAngleA,
                rightAngleB,
                rightAngleC,
            )
            .complete(
                AnglePoint(20.0, 49.5),
                AnglePoint(150.0, 49.5),
                AnglePoint(150.0, 90.0),
            )
            .let { applied(it.select(AngleId(1L))) }
        val mapper = fitMapper()
        val layout = angleOverlayLayout(mapper, crossing)

        val target = resolveAngleOverlayTarget(mapper.viewOf(rightAngleB), mapper, layout, metrics())

        assertEquals(
            AngleOverlayTarget.Handle(AngleId(1L), AngleMarker.B, rightAngleB),
            target,
        )
    }

    @Test
    fun `una marca de un angulo no seleccionado selecciona en vez de arrastrar`() {
        val state = applied(editor().withAngles().select(AngleId(1L)))
        val mapper = fitMapper()
        val layout = angleOverlayLayout(mapper, state)

        val overSecondA = resolveAngleOverlayTarget(mapper.viewOf(secondAngleA), mapper, layout, metrics())
        assertEquals(AngleOverlayTarget.AngleBody(AngleId(2L)), overSecondA)

        val overSecondMarker = resolveAngleOverlayTarget(
            mapper.viewOf(AnglePoint(40.0, 95.0)),
            mapper,
            layout,
            metrics(),
        )
        assertEquals(AngleOverlayTarget.AngleBody(AngleId(2L)), overSecondMarker)
    }

    @Test
    fun `el borrador tiene prioridad en un empate exacto con el asa seleccionada`() {
        val state = editor()
            .complete(rightAngleA, rightAngleB, rightAngleC)
            .place(rightAngleA)
            .let { applied(it.select(AngleId(1L))) }
        val mapper = fitMapper()
        val layout = angleOverlayLayout(mapper, state)

        val target = resolveAngleOverlayTarget(mapper.viewOf(rightAngleA), mapper, layout, metrics())

        assertEquals(
            AngleOverlayTarget.Handle(angleId = null, marker = AngleMarker.A, videoPoint = rightAngleA),
            target,
        )
    }

    @Test
    fun `gana el asa activa mas cercana aunque la prioridad de desempate sea menor`() {
        val state = applied(editor().withAngles().select(AngleId(1L)))
        val mapper = fitMapper()
        val layout = angleOverlayLayout(mapper, state)
        val aView = mapper.viewOf(rightAngleA)
        val bView = mapper.viewOf(rightAngleB)

        // Punto a 5px de A y ~35px de B: A gana por distancia.
        val nearA = ImagePoint(aView.x + 3.0, aView.y + 4.0)
        assertEquals(
            AngleOverlayTarget.Handle(AngleId(1L), AngleMarker.A, rightAngleA),
            resolveAngleOverlayTarget(nearA, mapper, layout, metrics()),
        )

        // Punto a 5px de B y ~35px de A: B gana aunque A tenga prioridad de desempate.
        val nearB = ImagePoint(bView.x + 3.0, bView.y + 4.0)
        assertEquals(
            AngleOverlayTarget.Handle(AngleId(1L), AngleMarker.B, rightAngleB),
            resolveAngleOverlayTarget(nearB, mapper, layout, metrics()),
        )
    }

    @Test
    fun `un empate de distancia entre cuerpos lo resuelve el que se dibuja encima`() {
        val state = editor()
            .complete(rightAngleA, rightAngleB, rightAngleC)
            .complete(rightAngleA, rightAngleB, rightAngleC)
        val mapper = fitMapper()
        val layout = angleOverlayLayout(mapper, state)

        // Punto del segmento B-C a más de 24px de sus marcas: solo hay cuerpo, no asa.
        val onBody = ImagePoint((mapper.viewOf(rightAngleB).x + mapper.viewOf(rightAngleC).x) / 2.0, 50.0)

        // Dos ángulos idénticos: el de creación más reciente se dibuja encima y recibe el toque.
        assertEquals(
            AngleOverlayTarget.AngleBody(AngleId(2L)),
            resolveAngleOverlayTarget(onBody, mapper, layout, metrics()),
        )

        // La lista selecciona el de abajo por id: pasa a dibujarse encima y el hit test lo sigue.
        val selectedBottom = applied(state.select(AngleId(1L)))
        val selectedLayout = angleOverlayLayout(mapper, selectedBottom)
        assertEquals(
            listOf(AngleId(2L), AngleId(1L)),
            angleOverlayDrawOrder(selectedLayout.entries).map { it.angleId },
        )
        assertEquals(
            AngleOverlayTarget.AngleBody(AngleId(1L)),
            resolveAngleOverlayTarget(onBody, mapper, selectedLayout, metrics()),
        )
    }

    @Test
    fun `el asa del angulo seleccionado gana sobre el cuerpo superpuesto identico`() {
        val shared = editor()
            .complete(rightAngleA, rightAngleB, rightAngleC)
            .complete(rightAngleA, rightAngleB, rightAngleC)
        val mapper = fitMapper()

        val bottomSelected = applied(shared.select(AngleId(1L)))
        val layout = angleOverlayLayout(mapper, bottomSelected)
        assertEquals(
            AngleOverlayTarget.Handle(AngleId(1L), AngleMarker.B, rightAngleB),
            resolveAngleOverlayTarget(mapper.viewOf(rightAngleB), mapper, layout, metrics()),
        )
    }

    @Test
    fun `el asa activa respeta el radio tactil de 48dp`() {
        val state = applied(editor().withAngles().select(AngleId(1L)))
        val mapper = fitMapper()
        val layout = angleOverlayLayout(mapper, state)
        val bView = mapper.viewOf(rightAngleB)

        val justInside = ImagePoint(bView.x + 23.0, bView.y)
        assertEquals(
            AngleOverlayTarget.Handle(AngleId(1L), AngleMarker.B, rightAngleB),
            resolveAngleOverlayTarget(justInside, mapper, layout, metrics(handleRadiusPx = 24.0)),
        )

        val justOutside = ImagePoint(bView.x + 25.0, bView.y)
        assertTrue(
            resolveAngleOverlayTarget(justOutside, mapper, layout, metrics(handleRadiusPx = 24.0))
                !is AngleOverlayTarget.Handle,
        )
    }

    @Test
    fun `los angulos ocultos no participan en la interaccion`() {
        val twoAngles = editor()
            .complete(rightAngleA, rightAngleB, rightAngleC)
            .complete(secondAngleA, secondAngleB, secondAngleC)
        val selected = applied(twoAngles.select(AngleId(2L)))
        val state = applied(selected.setVisible(AngleId(2L), false))
        val mapper = fitMapper()
        val layout = angleOverlayLayout(mapper, state)
        assertTrue(layout.entries.none { it.selected })

        val target = resolveAngleOverlayTarget(mapper.viewOf(secondAngleB), mapper, layout, metrics())
        assertEquals(AngleOverlayTarget.Empty, target)
    }

    @Test
    fun `el letterbox se ignora y el interior vacio queda disponible para el llamante`() {
        val state = applied(editor().withAngles().select(AngleId(1L)))
        val mapper = fitMapper()
        val layout = angleOverlayLayout(mapper, state)

        assertEquals(
            AngleOverlayTarget.Ignored,
            resolveAngleOverlayTarget(ImagePoint(50.0, 50.0), mapper, layout, metrics()),
        )
        assertEquals(
            AngleOverlayTarget.Ignored,
            resolveAngleOverlayTarget(ImagePoint(-5.0, 50.0), mapper, layout, metrics()),
        )
        assertEquals(
            AngleOverlayTarget.Empty,
            resolveAngleOverlayTarget(ImagePoint(200.0, 90.0), mapper, layout, metrics()),
        )
    }

    @Test
    fun `el cuerpo tolera 12px alrededor del segmento y no mas`() {
        val state = editor().complete(rightAngleA, rightAngleB, rightAngleC)
        val mapper = fitMapper()
        val layout = angleOverlayLayout(mapper, state)
        val aView = mapper.viewOf(rightAngleA)
        val bView = mapper.viewOf(rightAngleB)
        val midpoint = ImagePoint((aView.x + bView.x) / 2.0, (aView.y + bView.y) / 2.0)

        // Segmento vertical A-B en pantalla: a 11px del eje todavía selecciona.
        assertEquals(
            AngleOverlayTarget.AngleBody(AngleId(1L)),
            resolveAngleOverlayTarget(ImagePoint(midpoint.x + 11.0, midpoint.y), mapper, layout, metrics()),
        )
        // A 14px del segmento y fuera de los 24px de las marcas ya no hay cuerpo.
        assertEquals(
            AngleOverlayTarget.Empty,
            resolveAngleOverlayTarget(ImagePoint(midpoint.x + 14.0, midpoint.y), mapper, layout, metrics()),
        )
    }

    @Test
    fun `ENCUADRAR deshabilitado o frame equivocado no instalan gestos`() {
        assertTrue(shouldInstallAngleGestures(enabled = true, mode = AngleOverlayMode.EDIT, showsGeometry = true))
        assertTrue(shouldInstallAngleGestures(enabled = true, mode = AngleOverlayMode.PLACEMENT, showsGeometry = true))
        assertFalse(shouldInstallAngleGestures(enabled = false, mode = AngleOverlayMode.EDIT, showsGeometry = true))
        assertFalse(shouldInstallAngleGestures(enabled = true, mode = AngleOverlayMode.FRAMING, showsGeometry = true))
        assertFalse(shouldInstallAngleGestures(enabled = false, mode = AngleOverlayMode.FRAMING, showsGeometry = true))
        // Frame renderizado distinto del ancla: no se dibuja ni se consume (H2).
        assertFalse(shouldInstallAngleGestures(enabled = true, mode = AngleOverlayMode.EDIT, showsGeometry = false))
        assertFalse(shouldInstallAngleGestures(enabled = true, mode = AngleOverlayMode.PLACEMENT, showsGeometry = false))
    }

    @Test
    fun `en modo colocacion el toque coloca aunque caiga sobre asas o lineas`() {
        val state = applied(editor().withAngles().select(AngleId(1L)))
        val mapper = fitMapper()
        val layout = angleOverlayLayout(mapper, state)
        val bView = mapper.viewOf(rightAngleB)

        // Sobre el asa B del ángulo seleccionado: en colocación el dato es el punto.
        assertEquals(
            AngleOverlayTap.PlacePoint(rightAngleB),
            resolveAngleOverlayTap(bView, mapper, layout, metrics(), AngleOverlayMode.PLACEMENT),
        )
        // A 10px por encima del asa B, muy dentro del radio táctil de 24dp.
        val nearA = ImagePoint(bView.x, bView.y - 10.0)
        val nearATap = resolveAngleOverlayTap(nearA, mapper, layout, metrics(), AngleOverlayMode.PLACEMENT)
        assertTrue(nearATap is AngleOverlayTap.PlacePoint)
        assertEquals(99.5, (nearATap as AngleOverlayTap.PlacePoint).videoPoint.x, 1e-9)
        assertEquals(39.6, nearATap.videoPoint.y, 1e-9)

        // Sobre el segmento B-C del otro ángulo (línea, no asa).
        val onSecondLine = ImagePoint(mapper.viewOf(secondAngleB).x, 90.0)
        assertEquals(
            AngleOverlayTarget.AngleBody(AngleId(2L)),
            resolveAngleOverlayTarget(onSecondLine, mapper, layout, metrics()),
        )
        assertTrue(
            resolveAngleOverlayTap(onSecondLine, mapper, layout, metrics(), AngleOverlayMode.PLACEMENT)
                is AngleOverlayTap.PlacePoint,
        )
        // En EDIT el mismo toque selecciona y nunca coloca.
        assertEquals(
            AngleOverlayTap.Select(AngleId(2L)),
            resolveAngleOverlayTap(onSecondLine, mapper, layout, metrics(), AngleOverlayMode.EDIT),
        )
    }

    @Test
    fun `en modo colocacion un C a menos de 24dp de A coloca el punto igualmente`() {
        val a = AnglePoint(99.5, 10.0)
        val state = applied(
            editor()
                .place(a)
                .place(AnglePoint(99.5, 49.5))
                .select(null),
        )
        val mapper = fitMapper()
        val layout = angleOverlayLayout(mapper, state)
        // El borrador A ya está colocado: su asa está a 3px del toque, dentro de los 24dp.
        val nearA = ImagePoint(mapper.viewOf(a).x + 3.0, mapper.viewOf(a).y)
        assertEquals(
            AngleOverlayTarget.Handle(angleId = null, marker = AngleMarker.A, videoPoint = a),
            resolveAngleOverlayTarget(nearA, mapper, layout, metrics()),
        )
        val tap = resolveAngleOverlayTap(nearA, mapper, layout, metrics(), AngleOverlayMode.PLACEMENT)
        assertTrue(tap is AngleOverlayTap.PlacePoint)
        assertEquals(102.485, (tap as AngleOverlayTap.PlacePoint).videoPoint.x, 1e-9)
        assertEquals(10.0, tap.videoPoint.y, 1e-9)
    }

    @Test
    fun `el letterbox no coloca ni selecciona en ningun modo`() {
        val state = applied(editor().withAngles().select(AngleId(1L)))
        val mapper = fitMapper()
        val layout = angleOverlayLayout(mapper, state)
        val letterbox = ImagePoint(50.0, 50.0)

        assertEquals(
            AngleOverlayTap.None,
            resolveAngleOverlayTap(letterbox, mapper, layout, metrics(), AngleOverlayMode.PLACEMENT),
        )
        assertEquals(
            AngleOverlayTap.None,
            resolveAngleOverlayTap(letterbox, mapper, layout, metrics(), AngleOverlayMode.EDIT),
        )
        assertEquals(
            AngleOverlayTap.None,
            resolveAngleOverlayTap(letterbox, mapper, layout, metrics(), AngleOverlayMode.FRAMING),
        )
    }

    @Test
    fun `en modo edicion el toque selecciona el cuerpo limpia en vacio e ignora el asa`() {
        val state = applied(editor().withAngles().select(AngleId(1L)))
        val mapper = fitMapper()
        val layout = angleOverlayLayout(mapper, state)

        // Asa activa del seleccionado: el toque no deselecciona ni coloca.
        assertEquals(
            AngleOverlayTap.None,
            resolveAngleOverlayTap(
                mapper.viewOf(rightAngleB),
                mapper,
                layout,
                metrics(),
                AngleOverlayMode.EDIT,
            ),
        )
        // Cuerpo de un ángulo no seleccionado: lo selecciona.
        assertEquals(
            AngleOverlayTap.Select(AngleId(2L)),
            resolveAngleOverlayTap(
                mapper.viewOf(secondAngleA),
                mapper,
                layout,
                metrics(),
                AngleOverlayMode.EDIT,
            ),
        )
        // Zona vacía: limpia la selección.
        assertEquals(
            AngleOverlayTap.Select(null),
            resolveAngleOverlayTap(
                ImagePoint(200.0, 90.0),
                mapper,
                layout,
                metrics(),
                AngleOverlayMode.EDIT,
            ),
        )
        // ENCUADRAR no resuelve toques (no hay detector instalado).
        assertEquals(
            AngleOverlayTap.None,
            resolveAngleOverlayTap(
                mapper.viewOf(secondAngleA),
                mapper,
                layout,
                metrics(),
                AngleOverlayMode.FRAMING,
            ),
        )
    }

    @Test
    fun `la distancia a un segmento cubre interior y extremos`() {
        val start = ImagePoint(0.0, 0.0)
        val end = ImagePoint(100.0, 0.0)

        assertEquals(0.0, distanceToSegment(ImagePoint(40.0, 0.0), start, end), 1e-9)
        assertEquals(5.0, distanceToSegment(ImagePoint(40.0, 5.0), start, end), 1e-9)
        assertEquals(5.0, distanceToSegment(ImagePoint(0.0, 5.0), start, end), 1e-9)
        assertEquals(5.0, distanceToSegment(ImagePoint(105.0, 0.0), start, end), 1e-9)
        assertEquals(kotlin.math.hypot(10.0, 10.0), distanceToSegment(ImagePoint(10.0, 10.0), ImagePoint(0.0, 0.0), ImagePoint(0.0, 0.0)), 1e-9)
    }

    @Test
    fun `proyectar y resolver no mutan el estado del editor`() {
        val state = applied(editor().withAngles().select(AngleId(2L)))
        val before = state.angles.map { it.id to it.markers }
        val mapper = fitMapper()

        val layout = angleOverlayLayout(mapper, state)
        resolveAngleOverlayTarget(mapper.viewOf(secondAngleB), mapper, layout, metrics())

        assertEquals(AngleId(2L), state.selectedAngleId)
        assertEquals(before, state.angles.map { it.id to it.markers })
        assertEquals(2, state.angles.size)
    }

    // -----------------------------------------------------------------------------------------
    // Preview diferido del workspace: deriva con la API pura, jamás muta el dominio
    // -----------------------------------------------------------------------------------------

    @Test
    fun `el preview diferido deriva la marca movida sin mutar el estado base`() {
        val base = applied(editor().withAngles().select(AngleId(1L)))
        val before = base.angles.map { it.id to it.markers }
        val moved = AnglePoint(120.0, 30.0)
        val handle = AngleOverlayTarget.Handle(
            angleId = AngleId(1L),
            marker = AngleMarker.B,
            videoPoint = rightAngleB,
        )

        val preview = angleOverlayPreviewState(base, handle, moved, confirmation)
            ?: throw AssertionError("un movimiento medible debe derivar preview")

        assertEquals(before, base.angles.map { it.id to it.markers })
        assertEquals(moved, preview.angles.first { it.id == AngleId(1L) }.markers.b)
        assertEquals(rightAngleB, base.angles.first { it.id == AngleId(1L) }.markers.b)
        // La proyección del preview usa la MISMA capa/layout existente, sin aritmética nueva.
        val mapper = fitMapper()
        val previewLayout = angleOverlayLayout(mapper, preview)
        val projected = previewLayout.entries.first { it.angleId == AngleId(1L) }.b
        val expected = mapper.videoToView(ImagePoint(moved.x, moved.y))
        assertEquals(expected, projected)
    }

    @Test
    fun `el preview diferido no publica geometria imposible`() {
        val base = applied(editor().withAngles().select(AngleId(1L)))
        // B encima de A deja una terna no medible: el preview devuelve null y no se pinta nada.
        val handle = AngleOverlayTarget.Handle(AngleId(1L), AngleMarker.B, rightAngleB)
        assertNull(angleOverlayPreviewState(base, handle, rightAngleA, confirmation))
        assertEquals(rightAngleB, base.angles.first { it.id == AngleId(1L) }.markers.b)
    }

    @Test
    fun `el preview diferido de un borrador usa moveDraftMarker`() {
        val base = editor().place(rightAngleA).place(rightAngleB)
        val moved = AnglePoint(160.0, 20.0)
        val handle = AngleOverlayTarget.Handle(
            angleId = null,
            marker = AngleMarker.B,
            videoPoint = rightAngleB,
        )

        val preview = angleOverlayPreviewState(base, handle, moved, confirmation)
            ?: throw AssertionError("mover una marca del borrador debe derivar preview")

        assertEquals(2, base.draft.points.size)
        assertEquals(rightAngleB, base.draft.points[1])
        assertEquals(moved, preview.draft.points[1])
    }

    @Test
    fun `el preview diferido rechaza una confirmacion que no es del frame base`() {
        val base = applied(editor().withAngles().select(AngleId(1L)))
        val foreign = confirmation.copy(frame = anchor.copy(frameIndex = anchor.frameIndex + 1))
        val stale = confirmation.copy(generation = generation + 1)
        val handle = AngleOverlayTarget.Handle(AngleId(1L), AngleMarker.B, rightAngleB)
        assertNull(angleOverlayPreviewState(base, handle, AnglePoint(120.0, 30.0), foreign))
        assertNull(angleOverlayPreviewState(base, handle, AnglePoint(120.0, 30.0), stale))
    }

    @Test
    fun `post_pinch_el_tap_usa_la_transformacion_vigente_en_coordenadas_canonicas`() {
        val base = fitMapper()
        // Zoom 2x con opt-in sobre el mismo vídeo/viewport: la proyección vigente cambia.
        val zoomed = VideoCoordinateMapper(
            video = VideoPresentationGeometry(width = 200, height = 100),
            viewport = ViewportSize(width = 400.0, height = 100.0),
            scaleMode = VideoScaleMode.FIT,
            zoom = 2.0,
            allowFitZoom = true,
        )
        // Punto descentrado pero visible con zoom 2x centrado (20,20 queda fuera del viewport).
        val videoPoint = AnglePoint(80.0, 40.0)
        val baseView = base.videoToView(ImagePoint(videoPoint.x, videoPoint.y))
        val zoomedView = zoomed.videoToView(ImagePoint(videoPoint.x, videoPoint.y))
        assertFalse(
            "el mismo punto de imagen proyecta distinto tras el pinch",
            baseView.x == zoomedView.x && baseView.y == zoomedView.y,
        )
        // El tap en PLACEMENT con la proyección vigente resuelve al punto canónico de ESA proyección.
        val state = editor().withAngles()
        val layout = angleOverlayLayout(zoomed, state)
        val tap = resolveAngleOverlayTap(
            downView = zoomedView,
            mapper = zoomed,
            layout = layout,
            metrics = metrics(),
            mode = AngleOverlayMode.PLACEMENT,
        )
        val placed = (tap as? AngleOverlayTap.PlacePoint)
            ?: throw AssertionError("el tap en colocación debe colocar, llegó $tap")
        val expected = requireNotNull(zoomed.viewToVideo(zoomedView)) {
            "el punto del tap vigente debe caer dentro del contenido"
        }
        assertEquals(expected.x, placed.videoPoint.x, 1e-9)
        assertEquals(expected.y, placed.videoPoint.y, 1e-9)
    }

    @Test
    fun `misma_escena_con_resize_no_reutiliza_el_punto_previo_0_commits`() {
        val state = applied(editor().withAngles().select(AngleId(1L)))
        val baseMapper = fitMapper()
        val resized = VideoCoordinateMapper(
            video = VideoPresentationGeometry(width = 200, height = 100),
            viewport = ViewportSize(width = 400.0, height = 100.0),
            scaleMode = VideoScaleMode.FIT,
            zoom = 2.0,
            allowFitZoom = true,
        )
        // Misma escena (misma fuente/generación/frame): solo cambia la proyección.
        assertFalse(
            "el resize debe cambiar el rectángulo de contenido",
            baseMapper.contentRect == resized.contentRect,
        )
        val baseLayout = angleOverlayLayout(baseMapper, state)
        val resizedLayout = angleOverlayLayout(resized, state)
        // Segunda terna descentrada (40,80): el centro casi no se mueve con zoom centrado.
        val baseB = baseLayout.entries.single { it.angleId == AngleId(2L) }.b
        val resizedB = resizedLayout.entries.single { it.angleId == AngleId(2L) }.b
        assertFalse(
            "la misma B pinta distinto tras el resize: el punto pendiente previo es obsoleto",
            baseB.x == resizedB.x && baseB.y == resizedB.y,
        )
        // El preview puro sigue derivando con la API existente (sin algoritmo nuevo): la guarda
        // de commit es la que debe descartarlo (0 callbacks), no la derivación.
        val handle = AngleOverlayTarget.Handle(AngleId(1L), AngleMarker.B, rightAngleB)
        val derived = angleOverlayPreviewState(state, handle, AnglePoint(120.0, 30.0), confirmation)
        assertNotNull("la derivación pura existe; el descarte es del gesto, no del dominio", derived)
    }
}
