package com.openjump.app.ui.selector

import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato del opt-in `allowFitZoom` del [VideoCoordinateMapper].
 *
 * El resto de consumidores (selector de frames, revisión de placa, calibraciones) usa el mapper por
 * defecto y **no puede cambiar**: con `allowFitZoom = false` y FIT el zoom se ignora exactamente como
 * antes, y CROP conserva su recorte. Solo el visor de ángulos activa el opt-in, donde el zoom es
 * relativo al tamaño completo (1x = imagen entera) y ampliar ya no cambia FIT por CROP.
 *
 * Pruebas JVM puras: no componen, no abren vídeo y no dependen de Android.
 */
class AngleFitZoomMapperTest {

    private val viewport = ViewportSize(width = 400.0, height = 600.0)

    private fun mapper(
        video: VideoPresentationGeometry,
        zoom: Double = MIN_CROP_ZOOM,
        alignment: CropAlignment = CropAlignment.Centered,
        scaleMode: VideoScaleMode = VideoScaleMode.FIT,
        allowFitZoom: Boolean = false,
    ) = VideoCoordinateMapper(
        video = video,
        viewport = viewport,
        scaleMode = scaleMode,
        cropAlignment = alignment,
        zoom = zoom,
        allowFitZoom = allowFitZoom,
    )

    private fun assertClose(expected: Double, actual: Double, label: String, tolerance: Double = 1e-6) {
        assertTrue("$label: esperado $expected, obtenido $actual", abs(expected - actual) <= tolerance)
    }

    @Test
    fun `sin opt-in FIT ignora el zoom igual que antes`() {
        val video = VideoPresentationGeometry(width = 1920, height = 1080)
        val base = mapper(video)
        for (zoom in listOf(MIN_CROP_ZOOM, 1.25, 2.0, MAX_CROP_ZOOM)) {
            val withZoom = mapper(video, zoom = zoom)
            assertEquals("zoom $zoom no debe alterar el rectángulo en FIT sin opt-in", base.contentRect, withZoom.contentRect)
            assertClose(1.0, withZoom.nativeScale, "nativeScale FIT sin opt-in")
            assertEquals(ImagePoint(0.0, 0.0), withZoom.contentTranslation)
            assertEquals(base.videoToView(ImagePoint(0.0, 0.0)), withZoom.videoToView(ImagePoint(0.0, 0.0)))
        }
    }

    @Test
    fun `sin opt-in CROP conserva su semantica de recorte`() {
        val video = VideoPresentationGeometry(width = 1080, height = 1920)
        val crop = mapper(video, zoom = 2.0, scaleMode = VideoScaleMode.CROP)
        // max(400/1080, 600/1920) * 2 = 0.7407 * 2
        val expectedScale = maxOf(400.0 / 1080.0, 600.0 / 1920.0) * 2.0
        assertClose(1080.0 * expectedScale, crop.contentRect.width, "ancho CROP")
        assertClose(2.0, crop.nativeScale, "nativeScale CROP")
        val overflowX = (1080.0 * expectedScale - 400.0).coerceAtLeast(0.0)
        assertClose(-overflowX * 0.5, crop.contentRect.left, "recorte centrado CROP")
    }

    @Test
    fun `con opt-in 1x es exactamente el FIT sin opt-in`() {
        val portrait = VideoPresentationGeometry(width = 1080, height = 1920)
        val plain = mapper(portrait)
        val opted = mapper(portrait, allowFitZoom = true)
        assertEquals(plain.contentRect, opted.contentRect)
        assertEquals(plain.contentTranslation, opted.contentTranslation)
        assertClose(plain.nativeScale, opted.nativeScale, "nativeScale 1x")
        assertClose(1.0, opted.nativeScale, "nativeScale 1x vale 1")
    }

    @Test
    fun `subir el zoom no cambia de modo y solo cubre los ejes recortados`() {
        val portrait = VideoPresentationGeometry(width = 1080, height = 1920)
        val expectedFitScale = minOf(400.0 / 1080.0, 600.0 / 1920.0)
        for (zoom in listOf(1.0, 1.25, 2.0, 3.0, MAX_CROP_ZOOM)) {
            val current = mapper(portrait, zoom = zoom, allowFitZoom = true)
            // Sigue siendo FIT con opt-in: el zoom es relativo al FIT, no un cambio implícito a
            // CROP (que escalaría con max(width, height) y saltaría en el primer paso).
            assertEquals(VideoScaleMode.FIT, current.scaleMode)
            assertClose(zoom, current.nativeScale, "nativeScale zoom $zoom")
            // Escala FIT * zoom: el 1x es la imagen completa, no hay ampliación implícita.
            assertClose(
                1080.0 * expectedFitScale * zoom,
                current.contentRect.width,
                "ancho con zoom $zoom",
            )
            assertClose(
                1920.0 * expectedFitScale * zoom,
                current.contentRect.height,
                "alto con zoom $zoom",
            )
            // El eje que no desborda queda centrado (letterbox normal del FIT)...
            if (current.cropOverflowX <= 0.0) {
                assertClose(
                    (400.0 - current.contentRect.width) / 2.0,
                    current.contentRect.left,
                    "centrado X zoom $zoom",
                )
            } else {
                // ...y solo el eje recortado cubre todo el viewport, sin hueco.
                assertTrue(
                    "el eje X recortado no deja hueco con zoom $zoom",
                    current.contentRect.left <= 1e-6 &&
                        current.contentRect.right >= 400.0 - 1e-6,
                )
            }
            if (current.cropOverflowY <= 0.0) {
                assertClose(
                    (600.0 - current.contentRect.height) / 2.0,
                    current.contentRect.top,
                    "centrado Y zoom $zoom",
                )
            } else {
                assertTrue(
                    "el eje Y recortado no deja hueco con zoom $zoom",
                    current.contentRect.top <= 1e-6 &&
                        current.contentRect.bottom >= 600.0 - 1e-6,
                )
            }
        }
        // 1x es exactamente el FIT sin opt-in; 1.25x ya recorta el eje vertical del retrato.
        val atOne = mapper(portrait, allowFitZoom = true)
        assertEquals(mapper(portrait).contentRect, atOne.contentRect)
        assertEquals(0.0, atOne.cropOverflowY, 1e-9)
        assertTrue(mapper(portrait, zoom = 1.25, allowFitZoom = true).cropOverflowY > 0.0)
    }

    @Test
    fun `el paisaje y el video rotado se comportan igual con opt-in`() {
        val landscape = VideoPresentationGeometry(width = 1920, height = 1080)
        val landscapeZoomed = mapper(landscape, zoom = 2.0, allowFitZoom = true)
        assertClose(2.0, landscapeZoomed.nativeScale, "nativeScale paisaje")
        assertClose(
            minOf(400.0 / 1920.0, 600.0 / 1080.0) * 2.0,
            landscapeZoomed.contentRect.width / 1920.0,
            "escala paisaje",
        )
        // `fromEncoded` con rotación 90 intercambia ejes: el visor recibe ya la geometría rotada.
        val rotated = VideoPresentationGeometry.fromEncoded(1920, 1080, 90)
        assertEquals(1080, rotated.width)
        assertEquals(1920, rotated.height)
        val rotatedPlain = mapper(rotated)
        val rotatedOpted = mapper(rotated, allowFitZoom = true)
        assertEquals(rotatedPlain.contentRect, rotatedOpted.contentRect)
    }

    @Test
    fun `ida y vuelta de coordenadas con opt-in y recorte`() {
        val portrait = VideoPresentationGeometry(width = 1080, height = 1920)
        val current = mapper(
            portrait,
            zoom = 2.5,
            alignment = CropAlignment(x = 0.25, y = 0.75),
            allowFitZoom = true,
        )
        // La ida/vuelta se comprueba con toques VISIBLES del viewport: son los únicos que la
        // pantalla puede entregar. Un punto del vídeo recortado fuera no es mapeable por contrato.
        val visibleSamples = listOf(
            ImagePoint(0.5, 0.5),
            ImagePoint(399.0, 599.0),
            ImagePoint(100.0, 250.0),
            ImagePoint(300.0, 500.0),
        )
        for (viewPoint in visibleSamples) {
            val source = current.viewToVideo(viewPoint)
            assertNotNull("el toque $viewPoint debe caer dentro del contenido visible", source)
            val restored = current.videoToView(requireNotNull(source))
            assertClose(viewPoint.x, restored.x, "ida y vuelta X de $viewPoint", tolerance = 1e-6)
            assertClose(viewPoint.y, restored.y, "ida y vuelta Y de $viewPoint", tolerance = 1e-6)
        }
        // Puntos del vídeo que el recorte deja fuera del viewport: viewToVideo debe devolver null.
        val croppedOut = listOf(
            ImagePoint(0.0, 0.0),
            ImagePoint(1079.0, 1919.0),
        )
        for (point in croppedOut) {
            val view = current.videoToView(point)
            assertTrue(
                "el punto $point queda fuera del viewport ($view)",
                view.x < 0.0 || view.x > 400.0 || view.y < 0.0 || view.y > 600.0,
            )
            assertNull(
                "un punto fuera del viewport no es mapeable: $point",
                current.viewToVideo(view),
            )
        }
    }

    @Test
    fun `nativeScale y traslacion mueven la superficie igual que el rectangulo`() {
        val portrait = VideoPresentationGeometry(width = 1080, height = 1920)
        val current = mapper(
            portrait,
            zoom = 1.5,
            alignment = CropAlignment(x = 1.0, y = 0.0),
            allowFitZoom = true,
        )
        assertClose(1.5, current.nativeScale, "nativeScale")
        // La traslación es la diferencia entre el origen del contenido y la posición centrada de
        // Media3 una vez escalada: aplicarla deja la imagen exactamente donde el mapper la mapea.
        assertClose(
            current.contentRect.left - (400.0 - current.contentRect.width) / 2.0,
            current.contentTranslation.x,
            "traslación X",
        )
        assertClose(
            current.contentRect.top - (600.0 - current.contentRect.height) / 2.0,
            current.contentTranslation.y,
            "traslación Y",
        )
    }

    @Test
    fun `pinch mantiene el punto bajo el centro y recrea el mismo modo con opt-in`() {
        val portrait = VideoPresentationGeometry(width = 1080, height = 1920)
        val current = mapper(portrait, allowFitZoom = true)
        val centroid = ImagePoint(120.0, 180.0)
        val source = requireNotNull(current.viewToVideo(centroid))
        val next = current.stateAfterGesture(centroid = centroid, zoomChange = 2.0)
        assertEquals("el gesto no cambia de modo", MIN_CROP_ZOOM * 2.0, next.zoom, 1e-9)
        val nextMapper = mapper(
            portrait,
            zoom = next.zoom,
            alignment = next.alignment,
            allowFitZoom = true,
        )
        val mapped = nextMapper.videoToView(source)
        assertClose(centroid.x, mapped.x, "el punto bajo el pinch se conserva en X", tolerance = 1e-6)
        assertClose(centroid.y, mapped.y, "el punto bajo el pinch se conserva en Y", tolerance = 1e-6)
    }

    @Test
    fun `el pan se acota y el reinicio vuelve al centro`() {
        val portrait = VideoPresentationGeometry(width = 1080, height = 1920)
        val current = mapper(portrait, zoom = 2.0, allowFitZoom = true)
        assertTrue("con zoom debe haber recorte vertical", current.cropOverflowY > 0.0)
        val farPan = current.alignmentAfterPan(deltaX = 0.0, deltaY = -1_000_000.0)
        assertTrue(farPan.y in 0.0..1.0)
        assertTrue(farPan.x in 0.0..1.0)
        val panned = mapper(portrait, zoom = 2.0, alignment = farPan, allowFitZoom = true)
        assertTrue("el pan no descubre hueco", panned.contentRect.top <= 1e-6)
        assertTrue("el pan no descubre hueco abajo", panned.contentRect.bottom >= 600.0 - 1e-6)

        val reset = mapper(portrait, zoom = MIN_CROP_ZOOM, allowFitZoom = true)
        assertEquals(CropAlignment.Centered, reset.cropAlignment)
        assertFalse(reset.cropOverflowX > 0.0 && reset.cropOverflowY > 0.0)
    }

    @Test
    fun `sin opt-in el gesto FIT sigue siendo inerte`() {
        val portrait = VideoPresentationGeometry(width = 1080, height = 1920)
        val plain = mapper(portrait)
        val state = plain.stateAfterGesture(
            centroid = ImagePoint(200.0, 300.0),
            zoomChange = 2.0,
            panDeltaX = 40.0,
            panDeltaY = -40.0,
        )
        assertEquals(CropViewportState(MIN_CROP_ZOOM, CropAlignment.Centered), state)
        assertFalse(plain.supportsZoom)
    }
}
