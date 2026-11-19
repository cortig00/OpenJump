package com.openjump.app.video.angles

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM de los cuatro huecos señalados en la revisión del ciclo de captura: crop inclusivo del
 * decodificador, resolución de metadatos de color salida→entrada, final de track (EOS) sin payload y
 * cancelación tardía. No ejecutan `MediaCodec` (eso vive en
 * `CodecFrozenFrameProducerInstrumentedTest`); sí fijan las decisiones puras que el bucle aplica, y el
 * régimen de memoria del slice anterior (matriz ARGB presupuestada antes de asignar, cero copias
 * extra).
 */
class DecoderPixelCycleContractTest {

    private val planar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
    private val semiPlanar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

    private fun layout(
        bufferWidth: Int = 8,
        bufferHeight: Int = 8,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        cropRight: Int = 7,
        cropBottom: Int = 7,
        yRowStride: Int = bufferWidth,
        uRowStride: Int = bufferWidth / 2,
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

    private fun window(declaration: CropDeclaration): CropWindow =
        (declaration as? CropDeclaration.Present)?.window
            ?: throw AssertionError("Se esperaba un crop presente y fue $declaration")

    // ---------------------------------------------------------------- crop inclusivo

    @Test
    fun `el crop inclusivo del decodificador conserva la ultima columna y la ultima fila`() {
        // KEY_CROP_RIGHT=5 sobre un buffer de 8 columnas significa "última columna visible = 5", es
        // decir 6 columnas (0..5). Tratarlo como Rect exclusivo (5 columnas) perdería la columna 5;
        // restarle otro 1 la perdería dos veces.
        val declared = window(CropDeclaration.ofDeclared(left = 0, top = 0, right = 5, bottom = 3))
        assertEquals(0, declared.left)
        assertEquals(5, declared.right)

        val y = ByteBuffer.allocate(8 * 4).apply { put(5, 235.toByte()); for (i in 0 until 32) if (i != 5) put(i, 16.toByte()) }
        val chroma = ByteBuffer.allocate(4 * 2).apply { for (i in 0 until 8) put(i, 128.toByte()) }
        val cropped = Yuv420OutputLayout.fromBuffer(
            width = 8,
            height = 4,
            colorFormat = planar,
            stride = 8,
            sliceHeight = 4,
            crop = declared,
        )
        assertEquals("El crop 0..5 son 6 columnas visibles", 6, cropped.visibleWidth)
        assertEquals(4, cropped.visibleHeight)
        assertNull(cropped.rejectionReason())

        val argb = requireNotNull(cropped.toArgb(y, chroma, chroma.duplicate()))
        assertEquals(6 * 4, argb.size)
        // La columna 5 (la última declarada) se lee; la 6 queda fuera del crop.
        assertEquals(0xFFFFFFFF.toInt(), argb[5])
        assertEquals(0xFF000000.toInt(), argb[4])

        // Un crop de una sola columna/fila es válido en semántica inclusiva: no se degrada a
        // "sin crop" (que devolvería el frame completo).
        val single = window(CropDeclaration.ofDeclared(left = 2, top = 0, right = 2, bottom = 0))
        val singleLayout = Yuv420OutputLayout.fromBuffer(8, 2, planar, stride = 8, sliceHeight = 2, crop = single)
        assertEquals(1, singleLayout.visibleWidth)
        assertEquals(1, singleLayout.visibleHeight)
        assertEquals(1, singleLayout.chromaColumns)
        assertNull(singleLayout.rejectionReason())
        val singleY = ByteBuffer.allocate(16).apply { put(2, 235.toByte()); for (i in 0 until 16) if (i != 2) put(i, 16.toByte()) }
        val singleChroma = ByteBuffer.allocate(4).apply { for (i in 0 until 4) put(i, 128.toByte()) }
        assertEquals(1, requireNotNull(singleLayout.toArgb(singleY, singleChroma, singleChroma.duplicate())).size)
        assertEquals(
            "Una sola columna visible debe ser el píxel 2, no el frame completo",
            0xFFFFFFFF.toInt(),
            requireNotNull(singleLayout.toArgb(singleY, singleChroma, singleChroma.duplicate()))[0],
        )
    }

    @Test
    fun `ausente e invalido no se confunden y un crop invalido bloquea`() {
        assertTrue(CropDeclaration.ofDeclared(null, null, null, null) === CropDeclaration.Absent)

        // right/bottom presentes y left/top ausentes: el origen del buffer es 0.
        val noOrigin = window(CropDeclaration.ofDeclared(null, null, 5, 3))
        assertEquals(0, noOrigin.left)
        assertEquals(0, noOrigin.top)

        val invalids = listOf(
            "solo bordes izquierdo/superior" to CropDeclaration.ofDeclared(0, 0, null, null),
            "orden invertido en x" to CropDeclaration.ofDeclared(5, 0, 4, 3),
            "orden invertido en y" to CropDeclaration.ofDeclared(0, 5, 3, 4),
            "origen negativo" to CropDeclaration.ofDeclared(-1, 0, 3, 3),
            "límite no representable" to CropDeclaration.ofDeclared(0, 0, Int.MAX_VALUE, 3),
        )
        for ((label, declaration) in invalids) {
            assertTrue("$label debe ser Invalid y fue $declaration", declaration is CropDeclaration.Invalid)
        }

        // Sin crop declarado el frame visible es el buffer completo, no un rectángulo vacío.
        val absent = Yuv420OutputLayout.fromBuffer(8, 4, planar, stride = 8, sliceHeight = 4, crop = null)
        assertEquals(8, absent.visibleWidth)
        assertEquals(4, absent.visibleHeight)

        // Un crop declarado fuera del buffer se rechaza (nunca se recorta a ciegas).
        val outside = Yuv420OutputLayout.fromBuffer(
            8, 4, planar, stride = 8, sliceHeight = 4, crop = window(CropDeclaration.ofDeclared(0, 0, 8, 3)),
        )
        assertTrue(outside.rejectionReason() != null)

        // Overflow: un crop con extremos opuestos no debe producir un ancho positivo pequeño.
        val overflow = Yuv420OutputLayout(
            bufferWidth = 8, bufferHeight = 8,
            cropLeft = Int.MIN_VALUE / 2, cropTop = 0, cropRight = Int.MAX_VALUE / 2, cropBottom = 7,
            yRowStride = 8, yPixelStride = 1, uRowStride = 4, uPixelStride = 1, vRowStride = 4, vPixelStride = 1,
        )
        assertTrue("Un span no representable no puede parecer convertible", overflow.visibleWidth == 0)
        assertTrue(overflow.rejectionReason() != null)
    }

    // ---------------------------------------------------------------- color salida→entrada

    @Test
    fun `una salida unspecified no silencia un color bt709 full declarado por la entrada`() {
        // El decodificador declara las claves con 0 ("unspecified" de Codec2): no declara nada y no
        // puede borrar el BT.709/full del track de entrada.
        assertEquals(
            MediaFormat.COLOR_STANDARD_BT709,
            YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_STANDARD_BT709),
        )
        assertEquals(
            YuvColorProfile.BT709_FULL,
            YuvColorContract.profileFor(
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_STANDARD_BT709),
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_TRANSFER_SDR_VIDEO),
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_RANGE_FULL),
            ),
        )
        // Sin rango declarado en ninguno de los dos: BT.709 limitado, no BT.601.
        assertEquals(
            YuvColorProfile.BT709_LIMITED,
            YuvColorContract.profileFor(
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_STANDARD_BT709),
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_TRANSFER_SDR_VIDEO),
                YuvColorContract.resolveDeclared(0, YuvColorContract.UNSPECIFIED),
            ),
        )
        // La salida ausente también cede el paso a la entrada.
        assertEquals(
            YuvColorProfile.BT601_FULL,
            YuvColorContract.profileFor(
                YuvColorContract.resolveDeclared(YuvColorContract.UNSPECIFIED, MediaFormat.COLOR_STANDARD_BT601_PAL),
                YuvColorContract.resolveDeclared(YuvColorContract.UNSPECIFIED, MediaFormat.COLOR_TRANSFER_SDR_VIDEO),
                YuvColorContract.resolveDeclared(YuvColorContract.UNSPECIFIED, MediaFormat.COLOR_RANGE_FULL),
            ),
        )
        // Una salida que sí declara manda sobre la entrada.
        assertEquals(
            MediaFormat.COLOR_STANDARD_BT601_NTSC,
            YuvColorContract.resolveDeclared(MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT709),
        )
        // Ambos ausentes: la suposición documentada BT.601 limitado se mantiene.
        assertEquals(
            YuvColorProfile.BT601_LIMITED,
            YuvColorContract.profileFor(
                YuvColorContract.resolveDeclared(YuvColorContract.UNSPECIFIED, YuvColorContract.UNSPECIFIED),
                YuvColorContract.resolveDeclared(YuvColorContract.UNSPECIFIED, YuvColorContract.UNSPECIFIED),
                YuvColorContract.resolveDeclared(YuvColorContract.UNSPECIFIED, YuvColorContract.UNSPECIFIED),
            ),
        )
    }

    @Test
    fun `el bloqueo hdr y de metadatos ilegibles sobrevive al fallback`() {
        // HDR declarado por la entrada sigue bloqueando aunque la salida diga 0.
        assertNull(
            "PQ declarado por la entrada debe bloquear",
            YuvColorContract.profileFor(
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_STANDARD_BT709),
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_TRANSFER_ST2084),
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_RANGE_LIMITED),
            ),
        )
        assertNull(
            "HLG declarado por la entrada debe bloquear",
            YuvColorContract.profileFor(
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_STANDARD_BT709),
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_TRANSFER_HLG),
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_RANGE_LIMITED),
            ),
        )
        assertNull(
            "BT.2020 declarado por la entrada debe bloquear",
            YuvColorContract.profileFor(
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_STANDARD_BT2020),
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_TRANSFER_SDR_VIDEO),
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_RANGE_LIMITED),
            ),
        )
        // Una clave ilegible no es un 0 ni un ausente: se propaga y bloquea.
        assertEquals(
            YuvColorContract.UNREADABLE,
            YuvColorContract.resolveDeclared(YuvColorContract.UNREADABLE, MediaFormat.COLOR_STANDARD_BT709),
        )
        assertNull(
            YuvColorContract.profileFor(
                YuvColorContract.resolveDeclared(YuvColorContract.UNREADABLE, MediaFormat.COLOR_STANDARD_BT709),
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_TRANSFER_SDR_VIDEO),
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_RANGE_FULL),
            ),
        )
        assertNull(
            "Una entrada ilegible tampoco se adivina",
            YuvColorContract.profileFor(
                YuvColorContract.resolveDeclared(0, YuvColorContract.UNREADABLE),
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_TRANSFER_SDR_VIDEO),
                YuvColorContract.resolveDeclared(0, MediaFormat.COLOR_RANGE_LIMITED),
            ),
        )
    }

    // ---------------------------------------------------------------- EOS del track

    @Test
    fun `un eos sin payload no es un frame y un payload con eos si lo es`() {
        assertEquals(
            CodecOutputBufferKind.END_OF_STREAM_WITHOUT_PAYLOAD,
            CodecCapturePolicy.classifyOutputBuffer(MediaCodec.BUFFER_FLAG_END_OF_STREAM, 0),
        )
        assertEquals(
            "Un buffer sin payload ni banderas tampoco acredita un frame",
            CodecOutputBufferKind.EMPTY,
            CodecCapturePolicy.classifyOutputBuffer(0, 0),
        )
        assertEquals(
            "El último frame lleva payload y EOS: se procesa con normalidad",
            CodecOutputBufferKind.FRAME,
            CodecCapturePolicy.classifyOutputBuffer(MediaCodec.BUFFER_FLAG_END_OF_STREAM, 4_096),
        )
        assertEquals(
            CodecOutputBufferKind.FRAME,
            CodecCapturePolicy.classifyOutputBuffer(0, 4_096),
        )
        assertEquals(
            CodecOutputBufferKind.CODEC_CONFIG,
            CodecCapturePolicy.classifyOutputBuffer(MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 12),
        )
        assertTrue(CodecCapturePolicy.endsStream(MediaCodec.BUFFER_FLAG_END_OF_STREAM))
        assertTrue(
            "Config+EOS sigue cerrando el track",
            CodecCapturePolicy.endsStream(MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM),
        )
        assertFalse(CodecCapturePolicy.endsStream(0))
        assertFalse(CodecCapturePolicy.endsStream(MediaCodec.BUFFER_FLAG_CODEC_CONFIG))

        // Flujo típico al final de un track: buffers que no son frames no consumen el presupuesto de
        // decodificación (el bucle solo cuenta FRAME), así que un EOS no puede agotar el tope ni
        // justificar una imagen vacía.
        val kinds = listOf(
            CodecCapturePolicy.classifyOutputBuffer(MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 12),
            CodecCapturePolicy.classifyOutputBuffer(0, 0),
            CodecCapturePolicy.classifyOutputBuffer(MediaCodec.BUFFER_FLAG_END_OF_STREAM, 0),
        )
        assertTrue(kinds.none { it == CodecOutputBufferKind.FRAME })
        val budget = DecodeBudget(maxDecodedFrames = 1)
        assertTrue(budget.countDecodedFrame())
        assertFalse("Un EOS no debe contar como segundo frame", budget.countDecodedFrame())
    }

    // ---------------------------------------------------------------- cancelación / asignación

    @Test
    fun `una cancelacion tardia no entrega la imagen y libera el recurso`() {
        var released = 0
        val cancelled = CodecCaptureCancellation.deliver(
            value = "frozen-image",
            cancellationCheck = { true },
            release = { released += 1 },
        )
        assertTrue("Una cancelación tardía no puede entregar: $cancelled", cancelled is CaptureDelivery.Cancelled)
        assertEquals(
            FrameFreezeRejectionReason.CANCELLED,
            (cancelled as CaptureDelivery.Cancelled).blocked.reason,
        )
        assertEquals("El recurso congelado debe liberarse", 1, released)

        val delivered = CodecCaptureCancellation.deliver(
            value = "frozen-image",
            cancellationCheck = { false },
            release = { released += 1 },
        )
        assertTrue(delivered is CaptureDelivery.Deliver)
        assertEquals("frozen-image", (delivered as CaptureDelivery.Deliver).value)
        assertEquals("Sin cancelación no se libera nada", 1, released)

        // Un resultado que ya era un bloqueo (sin imagen) atraviesa la puerta intacto.
        val alreadyBlocked = CodecCaptureCancellation.deliver<String>(
            value = null,
            cancellationCheck = { true },
            release = { released += 1 },
        )
        assertTrue(alreadyBlocked is CaptureDelivery.Deliver)
        assertEquals(1, released)
    }

    @Test
    fun `la cancelacion se expresa antes de asignar y nunca como exito`() {
        assertEquals(
            FrameFreezeRejectionReason.CANCELLED,
            CodecCaptureCancellation.blockedIfCancelled({ true }, "detalle")?.reason,
        )
        assertNull(CodecCaptureCancellation.blockedIfCancelled({ false }, "detalle"))

        // CancellationException conserva su motivo; un fallo del códec sigue siendo fail-closed.
        assertEquals(
            FrameFreezeRejectionReason.CANCELLED,
            CodecCaptureCancellation.failureOf(CancellationException("cancelled")).reason,
        )
        assertEquals(
            FrameFreezeRejectionReason.UNSUPPORTED_CODEC_OUTPUT,
            CodecCaptureCancellation.failureOf(IllegalStateException("codec")).reason,
        )

        // OOM y presupuesto se convierten en rechazo tipado, no en una imagen parcial ni en éxito.
        val oom = guardedAllocation(1_024L) { throw OutOfMemoryError("boom") }
        assertTrue(oom is FrameAllocation.Unavailable)
        assertEquals(FrameAllocationFailure.OUT_OF_MEMORY, (oom as FrameAllocation.Unavailable).failure)
        assertEquals(
            FrameAllocationFailure.BUDGET_EXCEEDED,
            (guardedAllocation(65L * 1024L * 1024L) { IntArray(1) } as FrameAllocation.Unavailable).failure,
        )
        assertEquals(
            "Una conversión que no puede leer el buffer no fabrica píxeles",
            FrameAllocationFailure.REFUSED,
            (guardedAllocation(16L) { null } as FrameAllocation.Unavailable).failure,
        )
    }

    // ---------------------------------------------------------------- regiones de los planos YUV

    @Test
    fun `los planos se validan por position y limit en vez de por capacity`() {
        val twoByTwo = layout(bufferWidth = 2, bufferHeight = 2, cropRight = 1, cropBottom = 1, uRowStride = 1)
        val u = ByteBuffer.allocate(1).apply { put(0, 128.toByte()) }
        val v = ByteBuffer.allocate(1).apply { put(0, 128.toByte()) }

        // capacity 8 pero limit 2: solo dos bytes del plano son de este frame. Antes se miraba
        // capacity(), así que se leía padding y se devolvían píxeles inventados.
        val truncated = ByteBuffer.allocate(8).apply {
            for (i in 0 until 8) put(i, 235.toByte())
            limit(2)
        }
        assertNull("No se puede leer más allá del limit del plano", twoByTwo.toArgb(truncated, u, v))

        // La misma capacidad con limit suficiente sí convierte.
        val complete = ByteBuffer.allocate(4).apply { for (i in 0 until 4) put(i, 235.toByte()) }
        assertEquals(4, requireNotNull(twoByTwo.toArgb(complete, u, v)).size)

        // Un buffer desplazado: sólo la ventana [position, limit) es de este frame. Se normaliza con
        // `slice()` y se acepta la vista que empieza en el offset, sin leer el prefijo (corrección B2
        // #2: MediaCodec puede entregar planos con `position` no cero).
        val base = ByteBuffer.allocate(12).apply {
            for (i in 0 until 4) put(i, 16.toByte())
            for (i in 4 until 8) put(i, 235.toByte())
            for (i in 8 until 12) put(i, 16.toByte())
        }
        val prefixed = base.duplicate().apply { position(4); limit(8) }
        val sliced = prefixed.slice()
        val fromPrefixed = requireNotNull(twoByTwo.toArgb(prefixed, u, v))
        assertArrayEquals(
            "La ventana con position no cero debe dar los mismos píxeles que su slice",
            requireNotNull(twoByTwo.toArgb(sliced, u, v)),
            fromPrefixed,
        )
        assertEquals("Se lee el contenido de la ventana, no el prefijo", 255, (fromPrefixed[0] shr 16) and 0xFF)
        // Una ventana cuyo limit no cubre el layout sigue fallando cerrada.
        val shortWindow = base.duplicate().apply { position(4); limit(7) }
        assertNull("No se puede leer más allá del limit del plano", twoByTwo.toArgb(shortWindow, u, v))
    }

    @Test
    fun `los planos compartidos de un semiplanar no leen fuera de su region`() {
        // NV12 4x4: Y 16 bytes y UV intercalado 8 bytes (2 filas de croma de stride 4).
        val frameWidth = 4
        val frameHeight = 4
        val stride = 4
        val yBytes = stride * frameHeight
        val uvBytes = stride * 2
        val semiPlanarLayout = Yuv420OutputLayout.fromBuffer(
            width = frameWidth,
            height = frameHeight,
            colorFormat = semiPlanar,
            stride = stride,
            sliceHeight = frameHeight,
            crop = null,
        )
        assertEquals(stride, semiPlanarLayout.uRowStride)
        assertEquals(2, semiPlanarLayout.uPixelStride)
        assertNull(semiPlanarLayout.rejectionReason())

        val shared = ByteBuffer.allocate(yBytes + uvBytes).apply {
            for (i in 0 until yBytes) put(i, 235.toByte())
            // Fila de croma 0 neutra; fila 1 con V=200 (rojo).
            for (column in 0 until 2) {
                put(yBytes + column * 2, 128.toByte())
                put(yBytes + column * 2 + 1, 128.toByte())
            }
            for (column in 0 until 2) {
                put(yBytes + stride + column * 2, 128.toByte())
                put(yBytes + stride + column * 2 + 1, 200.toByte())
            }
        }
        val yPlane = shared.duplicate().apply { position(0); limit(yBytes) }.slice()
        val uvPlane = shared.duplicate().apply { position(yBytes); limit(yBytes + uvBytes) }.slice()
        val uPlane = uvPlane.duplicate().apply { position(0) }.slice()
        val vPlane = uvPlane.duplicate().apply { position(1) }.slice()

        val argb = requireNotNull(semiPlanarLayout.toArgb(yPlane, uPlane, vPlane))
        assertEquals(frameWidth * frameHeight, argb.size)
        assertEquals(0xFFFFFFFF.toInt(), argb[0])
        val thirdRow = argb[2 * frameWidth]
        assertTrue("La fila 2 usa su propia fila de croma", (thirdRow shr 16 and 0xFF) > (thirdRow shr 8 and 0xFF))

        // Si el plano compartido se recorta (limit insuficiente para la última fila de croma), no se
        // lee el vecino fuera de rango: se rechaza la conversión.
        val shortUv = shared.duplicate().apply { position(yBytes); limit(yBytes + stride) }.slice()
        val shortU = shortUv.duplicate().apply { position(0) }.slice()
        val shortV = shortUv.duplicate().apply { position(1) }.slice()
        assertNull(semiPlanarLayout.toArgb(yPlane, shortU, shortV))
    }
}
