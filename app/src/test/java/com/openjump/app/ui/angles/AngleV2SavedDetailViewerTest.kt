package com.openjump.app.ui.angles

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard JVM de la transformación de VISTA del visor ampliado y de la identidad de su petición.
 *
 * Verifica SÓLO matemática pura de presentación: ajuste completo (Fit) de la imagen dentro del área
 * visible, alto ACOTADO de la caja visible de la lista, límites de zoom, clamp del desplazamiento a
 * la imagen realmente dibujada, pasos de desplazamiento y reinicio. No mide píxeles reales, no
 * decodifica imágenes y no toca el almacén ni la base: la decodificación verificada y el presupuesto
 * siguen cubiertos por S2A.
 *
 * También fija la identidad de la petición de imagen (`official` clave de `remember` de la sección):
 * dos raíces distintas, dos fotogramas distintos o una integridad declarada distinta deben producir
 * peticiones distintas, porque de eso depende que no se reutilice un bitmap bajo otra leyenda.
 */
class AngleV2SavedDetailViewerTest {

    @Test
    fun `la vista restablecida esta centrada y sin ampliar`() {
        val reset = angleDetailResetViewport()
        assertEquals(ANGLE_DETAIL_MIN_ZOOM, reset.zoom, 0f)
        assertEquals(0f, reset.panX, 0f)
        assertEquals(0f, reset.panY, 0f)
        assertEquals(reset, angleDetailClampViewport(reset, 1000f, 600f, 400, 800))
    }

    @Test
    fun `el zoom se acota y los valores no finitos no amplian`() {
        assertEquals(ANGLE_DETAIL_MIN_ZOOM, angleDetailClampZoom(Float.NaN), 0f)
        assertEquals(ANGLE_DETAIL_MIN_ZOOM, angleDetailClampZoom(Float.POSITIVE_INFINITY), 0f)
        assertEquals(ANGLE_DETAIL_MIN_ZOOM, angleDetailClampZoom(-3f), 0f)
        assertEquals(ANGLE_DETAIL_MAX_ZOOM, angleDetailClampZoom(9f), 0f)
        assertEquals(2.5f, angleDetailClampZoom(2.5f), 0f)
        // Un delta no finito no cambia la vista: ni NaN ni infinito entran en la transformación.
        // La vista de partida es un estado alcanzable (1600x900 en 1000x600 admite ±500/±262,5 px
        // de desplazamiento a 2x), porque una vista fuera de límites se recentra al acotarla y el
        // paso cero no es lo que aquí se mide.
        val view = AngleDetailViewport(zoom = 2f, panX = 10f, panY = -10f)
        assertEquals(500f, angleDetailMaxPan(2f, 1000f, angleDetailFittedWidth(1000f, 600f, 1600, 900)), 0.001f)
        assertEquals(262.5f, angleDetailMaxPan(2f, 600f, angleDetailFittedHeight(1000f, 600f, 1600, 900)), 0.001f)
        assertEquals(view, angleDetailZoomBy(view, Float.NaN, 1000f, 600f, 1600, 900))
        assertEquals(view, angleDetailZoomBy(view, Float.POSITIVE_INFINITY, 1000f, 600f, 1600, 900))
    }

    @Test
    fun `la imagen vertical se ajusta a la altura disponible`() {
        // Aspecto 0,5 (400x800) en un área de 1000x600: manda la altura y quedan franjas laterales.
        assertEquals(300f, angleDetailFittedWidth(1000f, 600f, 400, 800), 0.001f)
        assertEquals(600f, angleDetailFittedHeight(1000f, 600f, 400, 800), 0.001f)
    }

    @Test
    fun `la imagen apaisada se ajusta a la anchura disponible`() {
        // Aspecto 16:9 (1600x900) en un área de 1000x600: manda la anchura (562,5 de alto).
        assertEquals(1000f, angleDetailFittedWidth(1000f, 600f, 1600, 900), 0.001f)
        assertEquals(562.5f, angleDetailFittedHeight(1000f, 600f, 1600, 900), 0.001f)
    }

    @Test
    fun `las dimensiones declaradas invalidas no producen NaN`() {
        assertEquals(ANGLE_VIEWPORT_FALLBACK_ASPECT, angleDetailImageAspect(0, 100), 0f)
        assertEquals(ANGLE_VIEWPORT_FALLBACK_ASPECT, angleDetailImageAspect(-1, -1), 0f)
        val fitted = angleDetailFittedWidth(1000f, 600f, 0, 0)
        assertEquals(true, fitted.isFinite() && fitted > 0f)
        // Área no finita o vacía: no hay geometría, no hay desplazamiento y el zoom sigue acotado.
        val clamped = angleDetailClampViewport(AngleDetailViewport(zoom = 3f, panX = 500f), Float.NaN, 0f, 400, 800)
        assertEquals(3f, clamped.zoom, 0f)
        assertEquals(0f, clamped.panX, 0f)
        assertEquals(0f, clamped.panY, 0f)
    }

    @Test
    fun `la caja visible de la imagen siempre tiene un alto finito y acotado`() {
        // Cualquier entrada representable del layout: aspectos reales (4:3, 9:16), los extremos
        // válidos del informe (1x1280 y 1280x1) y valores no finitos de ancho o aspecto. El alto
        // resultante NUNCA puede ser NaN/infinito ni salirse del intervalo aplicado en pantalla,
        // porque es lo que mide la tarjeta dentro de una lista de alto ilimitado.
        val aspects = listOf(
            4f / 3f,
            9f / 16f,
            1f / 1280f,
            1280f / 1f,
            0f,
            -1f,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
        )
        val widths = listOf(
            -1f,
            0f,
            1f,
            300f,
            360f,
            411f,
            1080f,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
        )
        for (aspect in aspects) {
            for (width in widths) {
                val height = angleDetailViewportHeightDp(width, aspect)
                assertTrue(
                    "alto no finito para ancho=$width aspecto=$aspect: $height",
                    height.isFinite(),
                )
                assertTrue(
                    "alto fuera de [${ANGLE_V2_SAVED_VIEWPORT_MIN_HEIGHT_DP}, " +
                        "${ANGLE_V2_SAVED_VIEWPORT_MAX_HEIGHT_DP}] para ancho=$width aspecto=$aspect: $height",
                    height >= ANGLE_V2_SAVED_VIEWPORT_MIN_HEIGHT_DP &&
                        height <= ANGLE_V2_SAVED_VIEWPORT_MAX_HEIGHT_DP,
                )
            }
        }
    }

    @Test
    fun `el alto visible conserva la proporcion normal y acota los aspectos extremos`() {
        // 4:3 (640x480) en 360 dp de ancho: el alto responsivo de siempre, 270 dp.
        assertEquals(270f, angleDetailViewportHeightDp(360f, 4f / 3f), 0.001f)
        // 9:16 en 360 dp pediría 640 dp: la caja se queda en el máximo, con la imagen completa (Fit).
        assertEquals(
            ANGLE_V2_SAVED_VIEWPORT_MAX_HEIGHT_DP,
            angleDetailViewportHeightDp(360f, 9f / 16f),
            0.001f,
        )
        // Extremo del informe: un 1x1280 VÁLIDO en una tarjeta de 300 dp pediría 384.000 px de alto;
        // la caja se acota y sigue siendo representable sin cambiar la imagen.
        assertEquals(
            ANGLE_V2_SAVED_VIEWPORT_MAX_HEIGHT_DP,
            angleDetailViewportHeightDp(300f, 1f / 1280f),
            0.001f,
        )
        // El extremo simétrico no colapsa por debajo del mínimo táctil.
        assertEquals(
            ANGLE_V2_SAVED_VIEWPORT_MIN_HEIGHT_DP,
            angleDetailViewportHeightDp(300f, 1280f / 1f),
            0.001f,
        )
        // Sin ancho medible no hay NaN: alto máximo acotado.
        assertEquals(
            ANGLE_V2_SAVED_VIEWPORT_MAX_HEIGHT_DP,
            angleDetailViewportHeightDp(Float.NaN, 4f / 3f),
            0.001f,
        )
        // Aspecto inválido: se usa el de reserva 16:9, así que 360 dp de ancho dan 202,5 dp de alto.
        assertEquals(202.5f, angleDetailViewportHeightDp(360f, Float.NaN), 0.001f)
        assertEquals(202.5f, angleDetailViewportHeightDp(360f, 0f), 0.001f)
    }

    @Test
    fun `sin ampliacion no hay desplazamiento posible`() {
        // A 1x la imagen con Fit nunca desborda el área visible, así que el máximo es cero.
        assertEquals(0f, angleDetailMaxPan(1f, 1000f, 300f), 0f)
        assertEquals(0f, angleDetailMaxPan(1f, 600f, 600f), 0f)
        val clamped = angleDetailClampViewport(AngleDetailViewport(zoom = 1f, panX = 40f, panY = -40f), 1000f, 600f, 400, 800)
        assertEquals(0f, clamped.panX, 0f)
        assertEquals(0f, clamped.panY, 0f)
    }

    @Test
    fun `el desplazamiento se limita a la imagen ampliada`() {
        // 400x800 en 1000x600: dibujada 300x600 a 1x; a 3x son 900x1800.
        assertEquals(0f, angleDetailMaxPan(3f, 1000f, 300f), 0f)
        assertEquals(600f, angleDetailMaxPan(3f, 600f, 600f), 0f)
        val clamped = angleDetailClampViewport(
            AngleDetailViewport(zoom = 3f, panX = 9999f, panY = 9999f),
            1000f, 600f, 400, 800,
        )
        assertEquals(3f, clamped.zoom, 0f)
        assertEquals(0f, clamped.panX, 0f)
        assertEquals(600f, clamped.panY, 0f)
        val negative = angleDetailClampViewport(
            AngleDetailViewport(zoom = 3f, panX = -9999f, panY = -9999f),
            1000f, 600f, 400, 800,
        )
        assertEquals(0f, negative.panX, 0f)
        assertEquals(-600f, negative.panY, 0f)
    }

    @Test
    fun `alejar por debajo de uno restablece el centrado`() {
        val zoomed = AngleDetailViewport(zoom = 1.5f, panX = 100f, panY = 200f)
        val zoomedOut = angleDetailZoomBy(zoomed, -1f, 1000f, 600f, 400, 800)
        assertEquals(ANGLE_DETAIL_MIN_ZOOM, zoomedOut.zoom, 0f)
        assertEquals(0f, zoomedOut.panX, 0f)
        assertEquals(0f, zoomedOut.panY, 0f)
        // Y volver a subir desde ahí empieza centrado, no arrastra el desplazamiento anterior.
        val zoomedIn = angleDetailZoomBy(zoomedOut, ANGLE_DETAIL_ZOOM_STEP, 1000f, 600f, 400, 800)
        assertEquals(ANGLE_DETAIL_MIN_ZOOM + ANGLE_DETAIL_ZOOM_STEP, zoomedIn.zoom, 0.0001f)
        assertEquals(0f, zoomedIn.panX, 0f)
        assertEquals(0f, zoomedIn.panY, 0f)
    }

    @Test
    fun `el desplazamiento por botones mueve y se queda dentro de los limites`() {
        val step = angleDetailPanStep(1000f)
        assertEquals(1000f * ANGLE_DETAIL_PAN_STEP_FRACTION, step, 0.001f)
        assertEquals(0f, angleDetailPanStep(0f), 0f)
        assertEquals(0f, angleDetailPanStep(Float.NaN), 0f)
        val zoomed = AngleDetailViewport(zoom = 4f)
        val moved = angleDetailPanBy(zoomed, 0f, -step, 1000f, 600f, 400, 800)
        assertEquals(-step, moved.panY, 0.001f)
        assertEquals(0f, moved.panX, 0f)
        // Repetir el paso no puede salir de la imagen: al llegar al límite se queda ahí.
        var view = moved
        repeat(20) { view = angleDetailPanBy(view, 0f, -step, 1000f, 600f, 400, 800) }
        assertEquals(-angleDetailMaxPan(4f, 600f, angleDetailFittedHeight(1000f, 600f, 400, 800)), view.panY, 0.001f)
    }

    @Test
    fun `el reinicio descarta zoom y desplazamiento`() {
        val moved = angleDetailPanBy(
            AngleDetailViewport(zoom = 3f), 120f, -80f, 1000f, 600f, 400, 800,
        )
        assertNotEquals(AngleDetailViewport(), moved)
        assertEquals(AngleDetailViewport(), angleDetailResetViewport())
    }

    @Test
    fun `la identidad de la peticion distingue raiz fotograma e integridad`() {
        val saved = savedFrame(frameId = 11L, imageKey = "angle-images/a.png", sha = SHA_A)
        assertEquals(saved.imageRequest(7L), saved.imageRequest(7L))
        assertNotEquals(saved.imageRequest(7L), saved.imageRequest(8L))
        assertNotEquals(saved.imageRequest(7L), saved.copy(frameId = 12L).imageRequest(7L))
        assertNotEquals(saved.imageRequest(7L), saved.copy(imageKey = "angle-images/b.png").imageRequest(7L))
        assertNotEquals(saved.imageRequest(7L), saved.copy(imageSha256 = SHA_B).imageRequest(7L))
        // La petición no inventa datos: reproduce exactamente lo declarado por el fotograma.
        val request = saved.imageRequest(9L)
        assertEquals(9L, request.rootId)
        assertEquals(saved.frameId, request.frameId)
        assertEquals(saved.imageByteCount, request.imageByteCount)
        assertEquals(saved.imageSha256, request.imageSha256)
        assertEquals(saved.imageMimeType, request.imageMimeType)
    }

    @Test
    fun `la cota del dialogo descuenta las barras nativas del marco real`() {
        // Caso real medido: 1080x2400 con barras de 128 arriba y 63 abajo => 2209 de alto útil.
        assertEquals(
            IntSize(1080, 2209),
            angleDialogSafeViewport(
                fullWidth = 1080,
                fullHeight = 2400,
                insetLeft = 0,
                insetTop = 128,
                insetRight = 0,
                insetBottom = 63,
            ),
        )
        // Sin insets la cota es el marco completo: no se resta ninguna constante inventada.
        assertEquals(
            IntSize(1080, 2400),
            angleDialogSafeViewport(1080, 2400, 0, 0, 0, 0),
        )
    }

    @Test
    fun `la cota del dialogo sigue paisaje recorte lateral y ventana redimensionada`() {
        // Paisaje con barras laterales y recorte de cámara: se descuentan los cuatro lados.
        assertEquals(
            IntSize(2400 - 120 - 40, 1080 - 20 - 24),
            angleDialogSafeViewport(
                fullWidth = 2400,
                fullHeight = 1080,
                insetLeft = 120,
                insetTop = 20,
                insetRight = 40,
                insetBottom = 24,
            ),
        )
        // Ventana ya pequeña (multiventana): la cota la sigue, sin mínimo de 48 dp ni 2209 fijos.
        assertEquals(
            IntSize(900 - 0 - 24, 700 - 30 - 30),
            angleDialogSafeViewport(900, 700, 0, 30, 24, 30),
        )
        // Recorte en el lado derecho en vertical (pliegue / isla de cámara).
        assertEquals(
            IntSize(1080 - 0 - 96, 2400 - 128 - 63),
            angleDialogSafeViewport(1080, 2400, 0, 128, 96, 63),
        )
    }

    @Test
    fun `la cota del dialogo acota insets invalidos y nunca supera el marco`() {
        // El marco 0x0 no es representable: su contrato (cota nula) se cubre en
        // `la cota del dialogo no inventa geometria sin marco representable`. Aquí sólo entran
        // marcos con área real, donde la cota debe ser positiva y no superar el marco.
        val frames = listOf(
            1 to 1,
            1080 to 2400,
            2400 to 1080,
            Int.MAX_VALUE to Int.MAX_VALUE,
        )
        val insets = listOf(
            -100 to -100,
            0 to 0,
            128 to 63,
            Int.MAX_VALUE to Int.MAX_VALUE,
            10_000 to 10_000,
        )
        for ((width, height) in frames) {
            for ((startInset, endInset) in insets) {
                val safe = angleDialogSafeViewport(width, height, startInset, startInset, endInset, endInset)
                assertNotNull("un marco positivo debe producir cota para $width x $height", safe)
                val viewport = requireNotNull(safe)
                assertTrue(
                    "la cota debe ser positiva: $viewport para $width x $height con insets " +
                        "$startInset/$endInset",
                    viewport.width > 0 && viewport.height > 0,
                )
                assertTrue(
                    "la cota no puede superar el marco: $viewport para $width x $height con insets " +
                        "$startInset/$endInset",
                    viewport.width <= width && viewport.height <= height,
                )
            }
        }
    }

    @Test
    fun `la cota del dialogo no inventa geometria sin marco representable`() {
        assertNull(angleDialogSafeViewport(0, 0, 0, 0, 0, 0))
        assertNull(angleDialogSafeViewport(0, 2400, 0, 0, 0, 0))
        assertNull(angleDialogSafeViewport(1080, 0, 0, 0, 0, 0))
        assertNull(angleDialogSafeViewport(-1, 2400, 0, 0, 0, 0))
        assertNull(angleDialogSafeViewport(1080, -2400, 0, 0, 0, 0))
    }

    @Test
    fun `el gesto de pinza respeta el maximo y ancla el punto bajo el centroide`() {
        // Area 1000x500 con imagen 2000x1000 (mismo aspecto): ajustada 1000x500, a 2x dibuja 2000x1000.
        val start = AngleDetailViewport(zoom = 2f, panX = 100f, panY = 50f)
        val centroidX = 800f
        val centroidY = 100f

        val next = angleDetailTransformGesture(
            viewport = start,
            zoomChange = 2f,
            panX = 0f,
            panY = 0f,
            centroidX = centroidX,
            centroidY = centroidY,
            containerWidth = 1000f,
            containerHeight = 500f,
            imageWidth = 2000,
            imageHeight = 1000,
        )

        assertEquals(3f * 4f / 3f, ANGLE_DETAIL_MAX_ZOOM, 0f)
        assertEquals(ANGLE_DETAIL_MAX_ZOOM, next.zoom, 0f)
        assertEquals(-100f, next.panX, 0.001f)
        assertEquals(250f, next.panY, 0.001f)
        // El punto de imagen bajo el centroide NO se mueve: (s - C - pan) / zoom es el mismo antes
        // y después, usando la transformación realmente aplicada.
        assertEquals(
            (centroidX - 500f - start.panX) / start.zoom,
            (centroidX - 500f - next.panX) / next.zoom,
            0.001f,
        )
        assertEquals(
            (centroidY - 250f - start.panY) / start.zoom,
            (centroidY - 250f - next.panY) / next.zoom,
            0.001f,
        )
    }

    @Test
    fun `el gesto no deja entrar valores no finitos ni sale de los limites`() {
        val start = AngleDetailViewport(zoom = 2f, panX = 100f, panY = 50f)
        val unchanged = angleDetailTransformGesture(
            viewport = start,
            zoomChange = Float.NaN,
            panX = Float.POSITIVE_INFINITY,
            panY = Float.NEGATIVE_INFINITY,
            centroidX = Float.NaN,
            centroidY = Float.NaN,
            containerWidth = 1000f,
            containerHeight = 500f,
            imageWidth = 2000,
            imageHeight = 1000,
        )
        assertEquals(start, unchanged)
        // Un arrastre enorme se reacota al desplazamiento maximo de la imagen ampliada.
        val dragged = angleDetailTransformGesture(
            viewport = start,
            zoomChange = 1f,
            panX = 10_000f,
            panY = -10_000f,
            centroidX = 500f,
            centroidY = 250f,
            containerWidth = 1000f,
            containerHeight = 500f,
            imageWidth = 2000,
            imageHeight = 1000,
        )
        assertEquals(500f, dragged.panX, 0.001f)
        assertEquals(-250f, dragged.panY, 0.001f)
        // Y al alejar por debajo del minimo la vista se recentra, no se queda con un pan ilegal.
        val zoomedOut = angleDetailTransformGesture(
            viewport = start,
            zoomChange = 0.01f,
            panX = 0f,
            panY = 0f,
            centroidX = 900f,
            centroidY = 400f,
            containerWidth = 1000f,
            containerHeight = 500f,
            imageWidth = 2000,
            imageHeight = 1000,
        )
        assertEquals(ANGLE_DETAIL_MIN_ZOOM, zoomedOut.zoom, 0f)
        assertEquals(0f, zoomedOut.panX, 0f)
        assertEquals(0f, zoomedOut.panY, 0f)
    }

    private fun savedFrame(
        frameId: Long,
        imageKey: String,
        sha: String,
    ): AngleV2SavedDetailFrame = AngleV2SavedDetailFrame(
        ordinal = 0,
        frameId = frameId,
        assignedFrameIndex = 0,
        reportedFrameIndex = null,
        presentationWidth = 1920,
        presentationHeight = 1080,
        rotationDegrees = 90,
        imageKey = imageKey,
        imageWidth = 400,
        imageHeight = 800,
        imageByteCount = 2048L,
        imageSha256 = sha,
        imageMimeType = "image/png",
        marks = emptyList(),
    )

    private companion object {
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
