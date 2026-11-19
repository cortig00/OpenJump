package com.openjump.app.ui.angles

import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.selector.CropViewportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato puro del visor de ángulos ([angleVideoPaneHeightDp] y compañía).
 *
 * Motivo del slice: el visor tenía un 16:9 fijo, así que un vídeo vertical (o una imagen congelada
 * vertical) se veía como una franja diminuta con franjas negras enormes. El alto ahora se deriva del
 * aspecto real y queda acotado: ~60 % del espacio disponible en modo normal, más al ampliar, nunca
 * más que el espacio disponible y nunca por debajo del mínimo útil.
 *
 * Son funciones sin estado: estas pruebas fijan la tabla de casos sin componer ni medir.
 */
class AngleViewportTest {

    private val portrait = VideoPresentationGeometry(width = 1080, height = 1920)
    private val landscape = VideoPresentationGeometry(width = 1920, height = 1080)
    private val square = VideoPresentationGeometry(width = 1080, height = 1080)

    @Test
    fun `el alto siempre queda acotado por el espacio disponible`() {
        for (available in listOf(240f, 400f, 600f, 900f, 1440f)) {
            for (width in listOf(320f, 411f, 800f)) {
                for (geometry in listOf(null, portrait, landscape, square)) {
                    for (expanded in listOf(false, true)) {
                        val height = angleVideoPaneHeightDp(
                            paneWidthDp = width,
                            availableHeightDp = available,
                            aspect = angleVideoAspect(geometry),
                            expanded = expanded,
                        )
                        assertTrue(
                            "alto $height debe ser finito y positivo (w=$width, h=$available, $geometry)",
                            height.isFinite() && height > 0f,
                        )
                        assertTrue(
                            "alto $height no puede exceder el espacio $available",
                            height <= available + 1e-3f,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `en modo normal no pasa del sesenta por ciento del espacio`() {
        val available = 1000f
        for (geometry in listOf(null, portrait, landscape, square)) {
            val height = angleVideoPaneHeightDp(411f, available, angleVideoAspect(geometry), expanded = false)
            assertTrue(
                "alto $height debe respetar el límite normal",
                height <= available * ANGLE_VIEWPORT_NORMAL_MAX_FRACTION + 1e-3f,
            )
        }
    }

    @Test
    fun `un video vertical gana alto frente al 16 fijo anterior`() {
        val available = 700f
        val sixteenNine = angleVideoPaneHeightDp(
            411f,
            available,
            angleVideoAspect(VideoPresentationGeometry(width = 16, height = 9)),
            expanded = false,
        )
        val vertical = angleVideoPaneHeightDp(411f, available, angleVideoAspect(portrait), expanded = false)
        assertTrue(
            "el vertical ($vertical) debe tener más alto que un 16:9 ($sixteenNine)",
            vertical > sixteenNine,
        )
        // 411 / (1080/1920) = 730,7 dp de deseo: se queda en el techo del 60 %.
        assertEquals(available * ANGLE_VIEWPORT_NORMAL_MAX_FRACTION, vertical, 1e-3f)
    }

    @Test
    fun `ampliar solo aporta cuando la imagen puede crecer`() {
        assertTrue(
            "un vertical puede crecer al ampliar",
            angleViewportCanExpand(411f, 700f, angleVideoAspect(portrait)),
        )
        assertFalse(
            "un 16:9 a ancho completo ya ocupa el máximo normal",
            angleViewportCanExpand(411f, 700f, angleVideoAspect(landscape)),
        )
        assertFalse(
            "sin geometría se usa el 16:9 de reserva y no se ofrece ampliar",
            angleViewportCanExpand(411f, 700f, angleVideoAspect(null)),
        )
    }

    @Test
    fun `la imagen renderizada crece al ampliar pero nunca sale del visor`() {
        val normal = angleVideoRenderedHeightDp(411f, 700f, angleVideoAspect(portrait), expanded = false)
        val expanded = angleVideoRenderedHeightDp(411f, 700f, angleVideoAspect(portrait), expanded = true)
        assertTrue("ampliar debe agrandar la imagen ($normal -> $expanded)", expanded > normal)
        assertTrue(expanded <= 700f + 1e-3f)
        val pane = angleVideoPaneHeightDp(411f, 700f, angleVideoAspect(portrait), expanded = true)
        assertTrue("la imagen nunca sobresale del visor", expanded <= pane + 1e-3f)
    }

    @Test
    fun `aspecto desconocido usa el 16 novenos de reserva`() {
        assertNull(angleVideoAspect(null))
        val fallback = angleVideoPaneHeightDp(411f, 700f, aspect = null, expanded = false)
        val sixteenNine = angleVideoPaneHeightDp(
            paneWidthDp = 411f,
            availableHeightDp = 700f,
            aspect = ANGLE_VIEWPORT_FALLBACK_ASPECT,
            expanded = false,
        )
        assertEquals(sixteenNine, fallback, 1e-3f)
        // 411 / (16/9) = 231,2 dp, por debajo del mínimo útil se aplica el mínimo.
        assertEquals(411f / ANGLE_VIEWPORT_FALLBACK_ASPECT, fallback, 1e-3f)
    }

    @Test
    fun `entradas degeneradas no producen NaN ni negativos`() {
        val cases = listOf(
            Triple(0f, 0f, 0f),
            Triple(-10f, 100f, -1f),
            Triple(Float.NaN, 100f, Float.NaN),
            Triple(411f, Float.POSITIVE_INFINITY, 0.5625f),
            Triple(Float.POSITIVE_INFINITY, 700f, 0.5625f),
            Triple(411f, 8f, 0.5625f),
        )
        for ((width, available, aspect) in cases) {
            val height = angleVideoPaneHeightDp(width, available, aspect, expanded = false)
            assertTrue(
                "alto $height debe ser finito y positivo para ($width, $available, $aspect)",
                height.isFinite() && height > 0f,
            )
            val rendered = angleVideoRenderedHeightDp(width, available, aspect, expanded = true)
            assertTrue("imagen $rendered debe ser finita y positiva", rendered.isFinite() && rendered > 0f)
        }
    }

    @Test
    fun `en pantallas muy bajas manda el espacio disponible y no el minimo`() {
        val height = angleVideoPaneHeightDp(411f, 120f, angleVideoAspect(portrait), expanded = false)
        assertTrue("el alto $height debe caber en 120 dp", height <= 120f + 1e-3f)
        assertTrue("el alto $height debe ser positivo", height > 0f)
    }

    @Test
    fun `el presupuesto del visor reserva la barra y el panel`() {
        val available = 800f
        val budget = angleVideoPaneBudgetDp(available)
        assertTrue(budget.isFinite() && budget > 0f)
        assertTrue("el presupuesto no puede exceder el espacio", budget <= available)
        assertTrue(
            "con espacio amplio quedan barra y panel mínimo reservados",
            available - budget >=
                ANGLE_VIEWPORT_CONTROLS_BAR_DP + ANGLE_VIEWPORT_MIN_PANEL_DP - 1e-3f,
        )
        // El alto máximo derivado del presupuesto deja sitio a la barra compacta fuera del vídeo.
        for (width in listOf(320f, 411f)) {
            val pane = angleVideoPaneHeightDp(
                width,
                budget,
                angleVideoAspect(portrait),
                expanded = true,
            )
            assertTrue(
                "pane $pane + barra debe caber en $available",
                pane + ANGLE_VIEWPORT_CONTROLS_BAR_DP <= available + 1e-3f,
            )
        }
    }

    @Test
    fun `presupuesto degenerado sigue siendo finito y positivo`() {
        for (available in listOf(0f, -50f, Float.NaN, 8f, Float.POSITIVE_INFINITY)) {
            val budget = angleVideoPaneBudgetDp(available)
            assertTrue(
                "presupuesto $budget para espacio $available",
                budget.isFinite() && budget > 0f,
            )
        }
    }

    @Test
    fun `un video cuadrado tambien respeta el limite normal`() {
        val height = angleVideoPaneHeightDp(400f, 900f, angleVideoAspect(square), expanded = false)
        assertEquals(400f, height, 1e-3f)
        assertTrue(height <= 900f * ANGLE_VIEWPORT_NORMAL_MAX_FRACTION + 1e-3f)
    }

    @Test
    fun `el presupuesto clampa ambos altos finales y entonces no se ofrece ampliar`() {
        // Pantalla muy baja: el presupuesto (espacio - barra - panel) acota por igual los dos modos.
        val available = 200f
        val budget = angleVideoPaneBudgetDp(available)
        val normal = angleVideoFinalPaneHeightDp(
            411f,
            available,
            angleVideoAspect(portrait),
            expanded = false,
        )
        val expanded = angleVideoFinalPaneHeightDp(
            411f,
            available,
            angleVideoAspect(portrait),
            expanded = true,
        )
        assertEquals("normal acotado por el presupuesto", budget, normal, 1e-3f)
        assertEquals("ampliado acotado por el presupuesto", budget, expanded, 1e-3f)
        assertFalse(
            "sin ganancia real el botón no debe ofrecer ampliar",
            angleViewportCanExpand(411f, available, angleVideoAspect(portrait)),
        )
        // Con más espacio el mismo vertical sí gana alto y la ampliación se ofrece.
        assertTrue(angleViewportCanExpand(411f, 700f, angleVideoAspect(portrait)))
    }

    @Test
    fun `dos deltas de gesto antes de recomponer se acumulan sobre el estado real`() {
        // Un gesto reutilizando el mapper del frame composicionado aplica el segundo delta sobre el
        // estado viejo y lo pisa; reconstruido con el estado del instante, ambos deltas cuentan.
        val centroid = ImagePoint(411.0, 500.0)
        val start = CropViewportState()
        val primero = angleCropStateAfterGesture(
            geometry = portrait,
            viewportWidth = 822,
            viewportHeight = 1000,
            state = start,
            gesture = AngleCropGesture(centroid, zoomChange = 1.5, panDeltaX = 0.0, panDeltaY = 0.0),
        )
        val segundo = angleCropStateAfterGesture(
            geometry = portrait,
            viewportWidth = 822,
            viewportHeight = 1000,
            state = primero,
            gesture = AngleCropGesture(centroid, zoomChange = 1.5, panDeltaX = 0.0, panDeltaY = 0.0),
        )
        assertEquals("el zoom se compone delta a delta", 2.25, segundo.zoom, 1e-9)
        val pisado = angleCropStateAfterGesture(
            geometry = portrait,
            viewportWidth = 822,
            viewportHeight = 1000,
            state = start,
            gesture = AngleCropGesture(centroid, zoomChange = 1.5, panDeltaX = 0.0, panDeltaY = 0.0),
        )
        assertTrue(
            "el estado real acumula (${segundo.zoom}) y no se queda en el último delta (${pisado.zoom})",
            segundo.zoom > pisado.zoom,
        )
        // Sin viewport medido no hay gesto posible: el estado no se inventa.
        assertEquals(
            start,
            angleCropStateAfterGesture(
                geometry = portrait,
                viewportWidth = 0,
                viewportHeight = 0,
                state = start,
                gesture = AngleCropGesture(centroid, 1.5, 10.0, 10.0),
            ),
        )
    }
}
