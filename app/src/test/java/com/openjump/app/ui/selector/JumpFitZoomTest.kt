package com.openjump.app.ui.selector

import com.openjump.app.protocol.ProtocolId
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato del zoom FIT del selector temporal de Saltos (1.3).
 *
 * Pruebas JVM puras: no componen, no abren vídeo ni dependen de Android. Cubren la matriz de
 * elegibilidad (solo Saltos temporales, nunca Horizontal/Encoder), que 1x sigue siendo el FIT
 * heredado completo y centrado, la acumulación aditiva de los botones, el anclaje al centroide, los
 * límites de pan y el estado seguro ante entradas, geometrías o viewports inválidos.
 *
 * El gesto real, el fotograma confirmado y la reproducción se validan en la instrumentación
 * posterior (A2B); aquí no se afirma nada sobre ellos.
 */
class JumpFitZoomTest {

    private val temporalProtocols = listOf(
        ProtocolId.CMJ,
        ProtocolId.SJ,
        ProtocolId.ABALAKOV,
        ProtocolId.UNILATERAL,
        ProtocolId.DROP_JUMP,
        ProtocolId.REPEATED_10_5,
        ProtocolId.ASYMMETRY,
    )

    private val landscape = VideoPresentationGeometry(width = 1920, height = 1080)
    private val square = VideoPresentationGeometry(width = 1000, height = 1000)

    private fun assertClose(expected: Double, actual: Double, label: String, tolerance: Double = 1e-6) {
        assertTrue("$label: esperado $expected, obtenido $actual", abs(expected - actual) <= tolerance)
    }

    private fun mapper(
        geometry: VideoPresentationGeometry?,
        state: CropViewportState,
        width: Double = 1000.0,
        height: Double = 1000.0,
    ): VideoCoordinateMapper? = jumpFitZoomMapper(geometry, width, height, state)

    // --- Elegibilidad -------------------------------------------------------

    @Test
    fun `solo un borrador temporal de Saltos habilita el zoom FIT`() {
        for (protocol in temporalProtocols) {
            assertTrue(
                "$protocol es un salto temporal y debe habilitar el zoom",
                isJumpFitZoomEligible(protocol, temporalMarkingEventCount = 3, encoderDraftPresent = false),
            )
        }
        assertTrue(
            "un protocolo temporal con un solo evento también es elegible",
            isJumpFitZoomEligible(ProtocolId.CMJ, temporalMarkingEventCount = 1, encoderDraftPresent = false),
        )
    }

    @Test
    fun `Horizontal nunca habilita el zoom FIT`() {
        assertFalse(
            "Horizontal guarda coordenadas y calibración; conserva FIT/CROP",
            isJumpFitZoomEligible(ProtocolId.HORIZONTAL, temporalMarkingEventCount = 3, encoderDraftPresent = false),
        )
    }

    @Test
    fun `sin borrador o sin eventos temporales no hay zoom FIT`() {
        assertFalse(
            "sin borrador no hay draft de Saltos",
            isJumpFitZoomEligible(null, temporalMarkingEventCount = 3, encoderDraftPresent = false),
        )
        for (protocol in temporalProtocols) {
            assertFalse(
                "$protocol sin eventos de marcado no tiene nada temporal que encuadrar",
                isJumpFitZoomEligible(protocol, temporalMarkingEventCount = 0, encoderDraftPresent = false),
            )
            assertFalse(
                "$protocol con eventos negativos tampoco",
                isJumpFitZoomEligible(protocol, temporalMarkingEventCount = -1, encoderDraftPresent = false),
            )
        }
    }

    @Test
    fun `Encoder nunca habilita el zoom FIT`() {
        for (protocol in temporalProtocols + ProtocolId.HORIZONTAL) {
            assertFalse(
                "$protocol con borrador Encoder conserva su referencia/revisión",
                isJumpFitZoomEligible(protocol, temporalMarkingEventCount = 3, encoderDraftPresent = true),
            )
        }
    }

    // --- 1x = FIT heredado --------------------------------------------------

    @Test
    fun `a 1x el mapper nuevo es exactamente el FIT heredado completo y centrado`() {
        // Mismo viewport 1000x600 en ambas comparaciones: cambiar sólo el opt-in.
        val fitZoom = mapper(landscape, CropViewportState(), width = 1000.0, height = 600.0)
        val legacy = VideoCoordinateMapper(
            video = landscape,
            viewport = ViewportSize(1000.0, 600.0),
            scaleMode = VideoScaleMode.FIT,
        )
        assertNotNull("con fuente y viewport válidos hay mapper", fitZoom)
        assertEquals("mismo rectángulo que el FIT de siempre", legacy.contentRect, requireNotNull(fitZoom).contentRect)
        assertEquals("sin ampliación nativa", 1.0, requireNotNull(fitZoom).nativeScale, 0.0)
        assertEquals("sin traslación nativa", ImagePoint(0.0, 0.0), requireNotNull(fitZoom).contentTranslation)
        assertEquals(VideoScaleMode.FIT, requireNotNull(fitZoom).scaleMode)
        assertTrue("el opt-in es explícito", requireNotNull(fitZoom).allowFitZoom)
        assertTrue(requireNotNull(fitZoom).supportsZoom)
    }

    @Test
    fun `el FIT heredado sigue ignorando el zoom sin opt-in`() {
        val legacy = VideoCoordinateMapper(
            video = landscape,
            viewport = ViewportSize(1000.0, 600.0),
            zoom = 3.0,
        )
        val zoomed = requireNotNull(
            mapper(
                landscape,
                CropViewportState(zoom = 3.0, alignment = CropAlignment.Centered),
                width = 1000.0,
                height = 600.0,
            ),
        )
        assertEquals("el mapper por defecto no cambia", 1.0, legacy.nativeScale, 0.0)
        assertNotEquals(
            "con opt-in el zoom sí amplía sobre la imagen completa",
            legacy.contentRect,
            zoomed.contentRect,
        )
        assertEquals(3.0, zoomed.nativeScale, 1e-9)
    }

    @Test
    fun `la geometria rotada usada es la presentada y respeta el aspecto`() {
        val rotated = VideoPresentationGeometry.fromEncoded(width = 1920, height = 1080, rotationDegrees = 90)
        assertEquals(1080, rotated.width)
        assertEquals(1920, rotated.height)
        val state = CropViewportState(zoom = 2.0, alignment = CropAlignment(0.5, 0.5))
        val zoomed = requireNotNull(mapper(rotated, state))
        assertEquals(1080.0, zoomed.video.width.toDouble(), 0.0)
        val ratio = zoomed.contentRect.width / zoomed.contentRect.height
        assertClose(1080.0 / 1920.0, ratio, "el zoom no deforma la imagen", tolerance = 1e-9)
    }

    // --- Botones aditivos y acumulación ------------------------------------

    @Test
    fun `los botones suman medio nivel sobre el estado acumulado`() {
        var state = CropViewportState()
        state = jumpFitZoomStateAfterStep(landscape, 1000.0, 600.0, state, JUMP_FIT_ZOOM_STEP)
        assertEquals(1.5, state.zoom, 1e-9)
        state = jumpFitZoomStateAfterStep(landscape, 1000.0, 600.0, state, JUMP_FIT_ZOOM_STEP)
        assertEquals(2.0, state.zoom, 1e-9)
        state = jumpFitZoomStateAfterStep(landscape, 1000.0, 600.0, state, -JUMP_FIT_ZOOM_STEP)
        assertEquals(1.5, state.zoom, 1e-9)
    }

    @Test
    fun `el delta se aplica al estado aplicado y no al mapper compuesto`() {
        val first = jumpFitZoomStateAfterStep(landscape, 1000.0, 600.0, CropViewportState(), JUMP_FIT_ZOOM_STEP)
        val second = jumpFitZoomStateAfterStep(landscape, 1000.0, 600.0, first, JUMP_FIT_ZOOM_STEP)
        val fromReset = jumpFitZoomStateAfterStep(landscape, 1000.0, 600.0, CropViewportState(), JUMP_FIT_ZOOM_STEP)
        assertEquals("dos pasos desde 1x llegan a 2x", 2.0, second.zoom, 1e-9)
        assertEquals("un solo paso desde 1x se queda en 1,5x", 1.5, fromReset.zoom, 1e-9)
        assertTrue(
            "el segundo paso parte del estado ya aplicado, no del fotograma compuesto",
            abs(second.zoom - fromReset.zoom) > 0.1,
        )
    }

    @Test
    fun `el zoom se acota a 1-4x y volver a 1x recentra`() {
        val almostMax = CropViewportState(zoom = 3.8, alignment = CropAlignment(0.2, 0.7))
        val capped = jumpFitZoomStateAfterStep(landscape, 1000.0, 600.0, almostMax, JUMP_FIT_ZOOM_STEP)
        assertEquals(MAX_CROP_ZOOM, capped.zoom, 1e-9)
        val floor = jumpFitZoomStateAfterStep(landscape, 1000.0, 600.0, CropViewportState(), -JUMP_FIT_ZOOM_STEP)
        assertEquals(MIN_CROP_ZOOM, floor.zoom, 1e-9)
        assertEquals("en 1x el encuadre queda centrado", CropAlignment.Centered, floor.alignment)
        val reset = jumpFitZoomStateAfterStep(
            landscape,
            1000.0,
            600.0,
            CropViewportState(zoom = 1.5, alignment = CropAlignment(0.1, 0.9)),
            -JUMP_FIT_ZOOM_STEP,
        )
        assertEquals(CropViewportState(), reset)
    }

    @Test
    fun `el estado se acumula entre geometrias de la misma fuente`() {
        val rotated = VideoPresentationGeometry.fromEncoded(width = 1920, height = 1080, rotationDegrees = 90)
        val first = jumpFitZoomStateAfterStep(landscape, 1000.0, 600.0, CropViewportState(), JUMP_FIT_ZOOM_STEP)
        val afterSourceChange = jumpFitZoomStateAfterStep(rotated, 1000.0, 600.0, first, JUMP_FIT_ZOOM_STEP)
        assertEquals("el zoom acumulado sobrevive a un fotograma con otra geometría", 2.0, afterSourceChange.zoom, 1e-9)
    }

    // --- Gesto: anclaje, pan y límites -------------------------------------

    @Test
    fun `el punto bajo el centroide se conserva cuando los limites lo permiten`() {
        val viewport = 1000.0
        val center = ImagePoint(500.0, 500.0)
        val state = CropViewportState()
        val before = requireNotNull(mapper(square, state, viewport, viewport))
        val sourceBefore = requireNotNull(before.viewToVideo(center))
        val next = jumpFitZoomStateAfterGesture(
            geometry = square,
            viewportWidth = viewport,
            viewportHeight = viewport,
            state = state,
            centroid = center,
            zoomChange = 2.0,
            panDx = 0.0,
            panDy = 0.0,
        )
        assertEquals(2.0, next.zoom, 1e-9)
        val after = requireNotNull(mapper(square, next, viewport, viewport))
        val sourceAfter = requireNotNull(after.viewToVideo(center))
        assertClose(sourceBefore.x, sourceAfter.x, "el centroide conserva el punto en x")
        assertClose(sourceBefore.y, sourceAfter.y, "el centroide conserva el punto en y")
    }

    @Test
    fun `el centroide descentrado ancla su propio punto de la imagen`() {
        val viewport = 1000.0
        val centroid = ImagePoint(600.0, 300.0)
        val state = CropViewportState()
        val sourceBefore = requireNotNull(requireNotNull(mapper(square, state, viewport, viewport)).viewToVideo(centroid))
        val next = jumpFitZoomStateAfterGesture(
            geometry = square,
            viewportWidth = viewport,
            viewportHeight = viewport,
            state = state,
            centroid = centroid,
            zoomChange = 2.0,
            panDx = 0.0,
            panDy = 0.0,
        )
        val sourceAfter = requireNotNull(requireNotNull(mapper(square, next, viewport, viewport)).viewToVideo(centroid))
        assertClose(sourceBefore.x, sourceAfter.x, "se conserva el punto anclado en x")
        assertClose(sourceBefore.y, sourceAfter.y, "se conserva el punto anclado en y")
    }

    @Test
    fun `el arrastre se acota y nunca deja area vacia`() {
        val viewport = 1000.0
        val state = CropViewportState(zoom = 2.0, alignment = CropAlignment.Centered)
        val next = jumpFitZoomStateAfterGesture(
            geometry = square,
            viewportWidth = viewport,
            viewportHeight = viewport,
            state = state,
            centroid = ImagePoint(500.0, 500.0),
            zoomChange = 1.0,
            panDx = 10_000.0,
            panDy = -10_000.0,
        )
        assertEquals("el recorte horizontal se agota en el borde", 0.0, next.alignment.x, 1e-9)
        assertEquals("el recorte vertical se agota en el borde", 1.0, next.alignment.y, 1e-9)
        val zoomed = requireNotNull(mapper(square, next, viewport, viewport))
        assertTrue("el contenido cubre el viewport por la izquierda", zoomed.contentRect.left <= 0.0)
        assertTrue("el contenido cubre el viewport por la derecha", zoomed.contentRect.right >= viewport)
        assertTrue("el contenido cubre el viewport por arriba", zoomed.contentRect.top <= 0.0)
        assertTrue("el contenido cubre el viewport por abajo", zoomed.contentRect.bottom >= viewport)
    }

    @Test
    fun `el eje sin desbordamiento queda centrado y no admite pan`() {
        // 16:9 en viewport cuadrado: el eje Y no desborda a 1,5x, el X sí.
        val state = CropViewportState(zoom = 1.5, alignment = CropAlignment(0.3, 0.2))
        val zoomedMapper = requireNotNull(mapper(landscape, state, 1000.0, 1000.0))
        assertEquals("a 1,5x el 16:9 no desborda en Y", 0.0, zoomedMapper.cropOverflowY, 1e-9)
        assertTrue("en X sí hay recorte real", zoomedMapper.cropOverflowX > 0.0)
        val normalized = jumpFitZoomNormalized(state, landscape, 1000.0, 1000.0)
        assertEquals("el eje X conserva su recorte", 0.3, normalized.alignment.x, 1e-9)
        assertEquals("el eje Y se recentra al no desbordar", 0.5, normalized.alignment.y, 1e-9)
        assertEquals("normalizar no cambia el zoom", 1.5, normalized.zoom, 1e-9)
    }

    @Test
    fun `un cambio de geometria recentra solo el eje que deja de desbordar`() {
        val before = CropViewportState(zoom = 2.0, alignment = CropAlignment(0.25, 0.25))
        val squareViewport = requireNotNull(mapper(landscape, before, 1000.0, 1000.0))
        assertTrue(squareViewport.cropOverflowX > 0.0 && squareViewport.cropOverflowY > 0.0)
        // Viewport más alto: a 2x el 16:9 ya no desborda en Y, así que ese eje se recentra.
        val taller = jumpFitZoomNormalized(before, landscape, 1000.0, 2000.0)
        assertEquals("el eje X sigue recortado", 0.25, taller.alignment.x, 1e-9)
        assertEquals("el eje Y se recentra", 0.5, taller.alignment.y, 1e-9)
        assertEquals("normalizar no cambia el zoom", 2.0, taller.zoom, 1e-9)
    }

    // --- Estado seguro ante entradas inválidas -----------------------------

    @Test
    fun `entradas no finitas, no positivas o fuera de la imagen dejan el estado intacto`() {
        // Un centroide no finito se rechaza al construir el punto, antes de aplicar el gesto.
        assertThrows(IllegalArgumentException::class.java) { ImagePoint(Double.NaN, 500.0) }
        val state = CropViewportState(zoom = 2.0, alignment = CropAlignment(0.4, 0.6))
        val unchanged = listOf(
            jumpFitZoomStateAfterGesture(
                square, 1000.0, 1000.0, state,
                centroid = ImagePoint(500.0, 500.0), zoomChange = Double.NaN, panDx = 0.0, panDy = 0.0,
            ),
            // Un factor de zoom no positivo no es una transformación válida.
            jumpFitZoomStateAfterGesture(
                square, 1000.0, 1000.0, state,
                centroid = ImagePoint(500.0, 500.0), zoomChange = 0.0, panDx = 0.0, panDy = 0.0,
            ),
            jumpFitZoomStateAfterGesture(
                square, 1000.0, 1000.0, state,
                centroid = ImagePoint(500.0, 500.0), zoomChange = -2.0, panDx = 0.0, panDy = 0.0,
            ),
            jumpFitZoomStateAfterGesture(
                square, 1000.0, 1000.0, state,
                centroid = ImagePoint(500.0, 500.0), zoomChange = 2.0, panDx = Double.POSITIVE_INFINITY, panDy = 0.0,
            ),
            jumpFitZoomStateAfterStep(landscape, 1000.0, 600.0, state, Double.NaN),
        )
        unchanged.forEach { assertEquals("ningún delta inválido cambia el estado", state, it) }
        // Fuera de la imagen: la barra negra del letterbox a 1x no ancla ningún punto.
        assertEquals(
            "un gesto iniciado en la barra negra no cambia el encuadre",
            CropViewportState(),
            jumpFitZoomStateAfterGesture(
                landscape, 1000.0, 1000.0, CropViewportState(),
                centroid = ImagePoint(500.0, 5.0), zoomChange = 2.0, panDx = 0.0, panDy = 0.0,
            ),
        )
    }

    @Test
    fun `sin fuente o viewport validos no se aplica encuadre`() {
        val state = CropViewportState(zoom = 2.0, alignment = CropAlignment(0.4, 0.6))
        assertNull("sin geometría no hay mapper", mapper(null, state))
        assertNull("viewport nulo no vale", mapper(landscape, state, width = 0.0, height = 600.0))
        assertNull("viewport negativo no vale", mapper(landscape, state, width = -10.0, height = 600.0))
        assertNull("viewport no finito no vale", mapper(landscape, state, width = Double.NaN, height = 600.0))
        assertEquals(
            state,
            jumpFitZoomStateAfterGesture(
                geometry = null, viewportWidth = 1000.0, viewportHeight = 600.0, state = state,
                centroid = ImagePoint(10.0, 10.0), zoomChange = 2.0, panDx = 0.0, panDy = 0.0,
            ),
        )
        assertEquals(
            state,
            jumpFitZoomStateAfterStep(geometry = null, viewportWidth = 1000.0, viewportHeight = 600.0, state = state, delta = 0.5),
        )
        assertEquals(
            state,
            jumpFitZoomStateAfterStep(geometry = landscape, viewportWidth = 0.0, viewportHeight = 600.0, state = state, delta = 0.5),
        )
    }
}
