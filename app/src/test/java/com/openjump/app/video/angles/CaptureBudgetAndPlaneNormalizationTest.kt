package com.openjump.app.video.angles

import android.media.MediaCodecInfo
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cierre de los huecos B2 del productor de fotogramas, en la parte que es pura y determinista:
 *
 * 1. Los planos YUV de `Image` pueden llegar con `position()` no cero (prefijo de alineación del
 *    decodificador). La conversión debe normalizarlos con `slice()`/`remaining`, sin leer fuera de
 *    `limit` y sin perder el prefijo declarado.
 * 2. El fallback de buffer plano sin `Image` solo es demostrable en NV12 semiplanar; planar y
 *    flexible se bloquean en vez de entregar píxeles aproximados.
 * 3. El presupuesto de 64 MiB acota las capturas **en vuelo** del proceso (no la memoria total del
 *    proceso ni las imágenes retenidas después): dos capturas concurrentes no pueden sumarlo, la
 *    reserva es no bloqueante, idempotente y se libera aunque el cuerpo lance.
 * 4. Un decodificador que entrega buffers sin payload no puede girar indefinidamente: el presupuesto
 *    de salidas vacías/iteraciones lo corta sin recortar una decodificación válida.
 *
 * No ejecuta `MediaCodec` ni `Image`; el bucle real y el proveedor de test se cubren en
 * `CodecFrozenFrameProducerInstrumentedTest` y `AssetDescriptorOffsetSourceInstrumentedTest`.
 */
class CaptureBudgetAndPlaneNormalizationTest {

    private val planar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
    private val semiPlanar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private val flexible = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

    private fun layout(
        bufferWidth: Int = 6,
        bufferHeight: Int = 4,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        cropRight: Int = bufferWidth - 1,
        cropBottom: Int = bufferHeight - 1,
        yRowStride: Int = bufferWidth,
        uRowStride: Int = (bufferWidth + 1) / 2,
        uPixelStride: Int = 1,
    ) = Yuv420OutputLayout(
        bufferWidth = bufferWidth,
        bufferHeight = bufferHeight,
        cropLeft = cropLeft,
        cropTop = cropTop,
        cropRight = cropRight,
        cropBottom = cropBottom,
        yRowStride = yRowStride,
        yPixelStride = 1,
        uRowStride = uRowStride,
        uPixelStride = uPixelStride,
        vRowStride = uRowStride,
        vPixelStride = uPixelStride,
    )

    /** Patrón de luma con variación suficiente para que un desfase de fila/columna se note. */
    private fun lumaBytes(rowStride: Int, rows: Int): ByteArray =
        ByteArray(rowStride * rows) { index -> ((index * 7 + 11) and 0xFF).toByte() }

    /** Patrón de croma con semillas distintas: si U y V se intercambian, el ARGB cambia. */
    private fun chromaBytes(rowStride: Int, rows: Int, seed: Int): ByteArray =
        ByteArray(rowStride * rows) { index -> ((index * 13 + seed) and 0xFF).toByte() }

    private fun buffer(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes)

    /** Buffer con un prefijo de [prefix] bytes ya consumidos: `position = prefix`, `limit = end`. */
    private fun prefixed(bytes: ByteArray, prefix: Int, sentinel: Int, limitSlack: Int = 0): ByteBuffer {
        val buffer = ByteBuffer.allocate(prefix + bytes.size)
        for (index in 0 until prefix) buffer.put(index, sentinel.toByte())
        for (index in bytes.indices) buffer.put(prefix + index, bytes[index])
        buffer.position(prefix)
        buffer.limit(prefix + bytes.size - limitSlack)
        return buffer
    }

    // ------------------------------------------------- planos con position no cero

    @Test
    fun `un plano con position no cero se normaliza y da los mismos pixeles que sin prefijo`() {
        val yRowStride = 8
        val uRowStride = 4
        val layout = layout(yRowStride = yRowStride, uRowStride = uRowStride)
        assertNull(layout.rejectionReason())

        val yBytes = lumaBytes(yRowStride, 4)
        val uBytes = chromaBytes(uRowStride, 2, seed = 17)
        val vBytes = chromaBytes(uRowStride, 2, seed = 91)
        val reference = requireNotNull(layout.toArgb(buffer(yBytes), buffer(uBytes), buffer(vBytes)))
        assertEquals(6 * 4, reference.size)

        val prefix = 9
        val normalized = requireNotNull(
            layout.toArgb(
                prefixed(yBytes, prefix, sentinel = 0xC3),
                prefixed(uBytes, prefix, sentinel = 0x5A),
                prefixed(vBytes, prefix, sentinel = 0x77),
            ),
        )
        assertArrayEquals(
            "El prefijo del decodificador no puede entrar en la imagen ni desplazar la lectura",
            reference,
            normalized,
        )

        // El contenido del prefijo es irrelevante: solo se salta. Si se leyera, cambiar el centinela
        // cambiaría píxeles.
        val otherPrefix = requireNotNull(
            layout.toArgb(
                prefixed(yBytes, prefix, sentinel = 0xFF),
                prefixed(uBytes, prefix, sentinel = 0x00),
                prefixed(vBytes, prefix, sentinel = 0xFF),
            ),
        )
        assertArrayEquals(reference, otherPrefix)

        // U y V no se confunden: intercambiarlos cambia el resultado (referencia asimétrica).
        val swapped = requireNotNull(layout.toArgb(buffer(yBytes), buffer(vBytes), buffer(uBytes)))
        assertFalse("U/V intercambiados deben producir otro ARGB", reference.contentEquals(swapped))
    }

    @Test
    fun `crop y stride se respetan con prefijo de plano`() {
        val yRowStride = 10
        val uRowStride = 6
        val cropped = layout(
            bufferWidth = 8,
            bufferHeight = 6,
            cropLeft = 2,
            cropTop = 2,
            cropRight = 7,
            cropBottom = 5,
            yRowStride = yRowStride,
            uRowStride = uRowStride,
        )
        assertNull(cropped.rejectionReason())
        assertEquals(6, cropped.visibleWidth)
        assertEquals(4, cropped.visibleHeight)

        val yBytes = lumaBytes(yRowStride, 6)
        val uBytes = chromaBytes(uRowStride, 3, seed = 31)
        val vBytes = chromaBytes(uRowStride, 3, seed = 63)
        val reference = requireNotNull(cropped.toArgb(buffer(yBytes), buffer(uBytes), buffer(vBytes)))

        val prefix = 5
        val normalized = requireNotNull(
            cropped.toArgb(
                prefixed(yBytes, prefix, sentinel = 0x11),
                prefixed(uBytes, prefix, sentinel = 0x22),
                prefixed(vBytes, prefix, sentinel = 0x33),
            ),
        )
        assertArrayEquals(reference, normalized)
        assertEquals(6 * 4, normalized.size)
    }

    @Test
    fun `un limit corto en cualquier plano falla cerrado`() {
        val yRowStride = 8
        val uRowStride = 4
        val layout = layout(yRowStride = yRowStride, uRowStride = uRowStride)
        val yBytes = lumaBytes(yRowStride, 4)
        val uBytes = chromaBytes(uRowStride, 2, seed = 17)
        val vBytes = chromaBytes(uRowStride, 2, seed = 91)
        val prefix = 3

        // Último índice de luma válido: (4-1)*8 + (6-1) = 29 → con remaining 29 ya falta el píxel.
        assertNull(
            "Un limit que no cubre el último píxel de luma no puede entregar imagen",
            layout.toArgb(
                prefixed(yBytes, prefix, sentinel = 0xC3, limitSlack = 3),
                prefixed(uBytes, prefix, sentinel = 0x5A),
                prefixed(vBytes, prefix, sentinel = 0x77),
            ),
        )
        // Último índice de croma: fila (4-1)/2=1, columna (6-1)/2=2 → 1*4 + 2 = 6.
        assertNull(
            "Un limit de croma corto no puede entregar imagen",
            layout.toArgb(
                prefixed(yBytes, prefix, sentinel = 0xC3),
                prefixed(uBytes, prefix, sentinel = 0x5A, limitSlack = 2),
                prefixed(vBytes, prefix, sentinel = 0x77),
            ),
        )
        assertNull(
            "Un limit de V corto no puede entregar imagen",
            layout.toArgb(
                prefixed(yBytes, prefix, sentinel = 0xC3),
                prefixed(uBytes, prefix, sentinel = 0x5A),
                prefixed(vBytes, prefix, sentinel = 0x77, limitSlack = 2),
            ),
        )
    }

    // ------------------------------------------------- fallback de buffer plano

    @Test
    fun `solo el semiplanar NV12 acepta el fallback sin Image`() {
        assertNull(
            "NV12 semiplanar declara stride y croma intercalado: layout demostrable",
            Yuv420OutputLayout.rawFallbackRejection(semiPlanar),
        )
        val planarReason = Yuv420OutputLayout.rawFallbackRejection(planar)
        assertNotNull("I420 sin Image no declara stride/offset de croma", planarReason)
        assertTrue(planarReason!!.contains("planar", ignoreCase = true))
        val flexibleReason = Yuv420OutputLayout.rawFallbackRejection(flexible)
        assertNotNull("Flexible sin Image no demuestra el layout", flexibleReason)
        assertTrue(flexibleReason!!.contains("flexible", ignoreCase = true))
        assertNotNull(
            "Un formato no soportado tampoco puede caer al buffer plano",
            Yuv420OutputLayout.rawFallbackRejection(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar),
        )
    }

    // ------------------------------------------------- presupuesto de proceso

    private val perCapture1080p: Long = 33L * 1024L * 1024L

    @Test
    fun `dos capturas concurrentes no suman mas que el presupuesto del proceso`() {
        val first = requireNotNull(FrameCaptureReservations.tryAcquire(perCapture1080p))
        try {
            assertEquals(perCapture1080p, FrameCaptureReservations.currentReservedBytes())
            assertNull(
                "La segunda captura se rechaza sin esperar a la primera",
                FrameCaptureReservations.tryAcquire(perCapture1080p),
            )
            // Lo que aún cabe sí se concede (no se degrada el caso de una sola captura).
            val remainder = FrameCaptureReservations.remainingBytes()
            val fits = requireNotNull(FrameCaptureReservations.tryAcquire(remainder))
            fits.release()
            assertEquals(perCapture1080p, FrameCaptureReservations.currentReservedBytes())
        } finally {
            first.release()
        }
        assertEquals("La liberación devuelve el presupuesto completo", 0L, FrameCaptureReservations.currentReservedBytes())
        assertTrue(first.isReleased)

        // Reintento tras liberar (la UI puede reintentar la captura rechazada).
        val retry = requireNotNull(FrameCaptureReservations.tryAcquire(perCapture1080p))
        retry.release()
        retry.release()
        assertEquals("release es idempotente", 0L, FrameCaptureReservations.currentReservedBytes())

        // Una petición mayor que el presupuesto completo se rechaza sin tocar el contador.
        assertNull(FrameCaptureReservations.tryAcquire(com.openjump.app.video.VideoFrameSafety.MAX_WORKING_BYTES + 1L))
        assertEquals(0L, FrameCaptureReservations.currentReservedBytes())
    }

    @Test
    fun `una excepcion en el cuerpo de la captura libera la reserva`() {
        val reservation = requireNotNull(FrameCaptureReservations.tryAcquire(1024L * 1024L))
        try {
            try {
                throw IllegalStateException("fallo simulado del decodificador")
            } finally {
                reservation.release()
            }
        } catch (_: IllegalStateException) {
            // Esperado: la liberación ocurre en el finally, como en el productor.
        }
        assertEquals(0L, FrameCaptureReservations.currentReservedBytes())
        assertTrue(reservation.isReleased)
    }

    @Test
    fun `dos hilos que reservan a la vez solo dejan pasar lo que cabe`() {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val granted = java.util.Collections.synchronizedList(mutableListOf<FrameCaptureReservations.Reservation>())
        try {
            repeat(2) {
                pool.execute {
                    start.await(5, TimeUnit.SECONDS)
                    FrameCaptureReservations.tryAcquire(perCapture1080p)?.let(granted::add)
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("Ambos hilos terminan", done.await(5, TimeUnit.SECONDS))
            assertEquals("Solo una reserva 1080p cabe en 64 MiB", 1, granted.size)
            assertTrue(FrameCaptureReservations.currentReservedBytes() <= 64L * 1024L * 1024L)
        } finally {
            granted.forEach { it.release() }
            pool.shutdownNow()
        }
        assertEquals(0L, FrameCaptureReservations.currentReservedBytes())
    }

    // ------------------------------------------------- presupuesto de bucle

    @Test
    fun `las salidas vacias agotan su presupuesto sin consumir frames validos`() {
        val loop = DecoderLoopBudget(maxEmptyOutputs = 3, maxIterations = 10)
        assertTrue(loop.onEmptyOutput())
        assertTrue(loop.onEmptyOutput())
        assertTrue(loop.onEmptyOutput())
        assertFalse("La cuarta salida vacía corta el bucle", loop.onEmptyOutput())
        assertEquals(4, loop.emptyOutputs)
        assertEquals("Las salidas vacías no cuentan como frames", 0, loop.iterations)

        // Los frames válidos no consumen el presupuesto de vacíos: 900 frames siguen decodificando.
        val decode = DecodeBudget(maxDecodedFrames = CodecCapturePolicy.MAX_DECODED_FRAMES)
        repeat(CodecCapturePolicy.MAX_DECODED_FRAMES) {
            assertTrue("El frame ${it + 1} es válido", decode.countDecodedFrame())
        }
        assertFalse(decode.countDecodedFrame())
        // Los frames válidos no tocan el contador de salidas vacías del bucle.
        assertEquals(4, loop.emptyOutputs)
        assertEquals(0, loop.iterations)
    }

    @Test
    fun `el tope de iteraciones corta un bucle que no produce nada`() {
        val loop = DecoderLoopBudget(maxEmptyOutputs = 1, maxIterations = 5)
        repeat(5) { assertTrue(loop.onIteration()) }
        assertFalse("La sexta vuelta supera el tope", loop.onIteration())
        assertEquals(6, loop.iterations)

        // Los topes por defecto dejan sitio a un GOP completo más el presupuesto de espera, de modo
        // que no recortan decodificaciones válidas ni esperas legítimas.
        val waitIterations = (CodecCapturePolicy.MAX_OUTPUT_WAIT_US / 10_000L).toInt()
        assertTrue(
            CodecCapturePolicy.MAX_DECODER_ITERATIONS >
                waitIterations + CodecCapturePolicy.MAX_DECODED_FRAMES,
        )
        assertTrue(CodecCapturePolicy.MAX_EMPTY_OUTPUT_BUFFERS >= 1)
    }
}
