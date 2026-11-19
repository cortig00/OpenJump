package com.openjump.app.video.angles

import java.nio.ByteBuffer
import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM del productor MediaCodec: solo las decisiones puras (validación de solicitud, PTS,
 * presupuesto de decodificación desde el sync y conversión YUV420 con stride/crop/color). El bucle real
 * de `MediaCodec` no se ejecuta aquí; su cobertura instrumentada vive en
 * `CodecFrozenFrameProducerInstrumentedTest`, que se ejecutó en un AVD API 35 con decodificador
 * goldfish (evidencia 2026-11-10) y no se re-ejecuta en este slice.
 */
class CodecCapturePolicyTest {

    private val index = VideoFrameIndexSnapshot(
        frameTimesUs = longArrayOf(0L, 40_000L, 80_000L, 120_000L, 160_000L),
        durationUs = 200_000L,
        encodedWidth = 320,
        encodedHeight = 240,
        declaredRotationDegrees = 0,
    )

    private fun request(
        frameIndex: Int = 2,
        assignedPtsUs: Long = index.frameTimesUs[2],
        indexFrameCount: Int = index.frameCount,
        encodedWidth: Int = 320,
        encodedHeight: Int = 240,
        declaredRotationDegrees: Int = 0,
    ) = FrameFreezeRequest(
        sourceIdentity = FrameSourceIdentity("fixture:angles/ordinal_cfr_bframes"),
        frameIndex = frameIndex,
        assignedPtsUs = assignedPtsUs,
        indexFrameCount = indexFrameCount,
        encodedWidth = encodedWidth,
        encodedHeight = encodedHeight,
        declaredRotationDegrees = declaredRotationDegrees,
    )

    @Test
    fun `el seek usa el pts indexado del frame solicitado`() {
        assertEquals(80_000L, CodecCapturePolicy.seekTargetPtsUs(index, 2))
        try {
            CodecCapturePolicy.seekTargetPtsUs(index, index.frameCount)
            throw AssertionError("Un frame fuera del índice debe fallar.")
        } catch (_: IllegalArgumentException) {
            // Esperado.
        }
    }

    @Test
    fun `un indice que no coincide con la solicitud se bloquea`() {
        val blocked = CodecCapturePolicy.validateRequest(request(indexFrameCount = 99), index)
        assertEquals(FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH, blocked?.reason)
    }

    @Test
    fun `un pts fuera de los timestamps del indice se bloquea`() {
        val blocked = CodecCapturePolicy.validateRequest(request(assignedPtsUs = 1_000_000L), index)
        assertEquals(FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH, blocked?.reason)
    }

    @Test
    fun `un pts dentro del rango pero ajeno al frame indexado se bloquea antes del codec`() {
        // 80 001 µs está dentro de [0, 160 000] pero no es el PTS del frame 2 del índice: pedirlo
        // decodificaría sin poder capturar nada, así que la solicitud se rechaza antes del códec.
        val blocked = CodecCapturePolicy.validateRequest(request(assignedPtsUs = index.frameTimesUs[2] + 1L), index)
        assertEquals(FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH, blocked?.reason)
        // Un PTS de otro frame tampoco es válido para este frameIndex.
        val otherFramePts = CodecCapturePolicy.validateRequest(request(assignedPtsUs = index.frameTimesUs[3]), index)
        assertEquals(FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH, otherFramePts?.reason)
    }

    @Test
    fun `dimensiones o rotacion que no coinciden con el indice se bloquean`() {
        assertEquals(
            FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH,
            CodecCapturePolicy.validateRequest(request(encodedWidth = 321), index)?.reason,
        )
        assertEquals(
            FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH,
            CodecCapturePolicy.validateRequest(request(encodedHeight = 241), index)?.reason,
        )
        assertEquals(
            FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH,
            CodecCapturePolicy.validateRequest(request(declaredRotationDegrees = 90), index)?.reason,
        )
    }

    @Test
    fun `un indice largo no se rechaza por posicion absoluta y el presupuesto cuenta desde el sync`() {
        // 2 000 frames CFR (80 s): el frame 1 500 está muy lejos del inicio, pero con
        // SEEK_TO_PREVIOUS_SYNC solo se decodifican los frames del GOP hasta su sync anterior.
        val longIndex = VideoFrameIndexSnapshot(
            frameTimesUs = LongArray(2_000) { it * 40_000L },
            durationUs = 2_000 * 40_000L,
            encodedWidth = 320,
            encodedHeight = 240,
            declaredRotationDegrees = 0,
        )
        val target = 1_500
        val postSeekRequest = FrameFreezeRequest(
            sourceIdentity = FrameSourceIdentity("fixture:angles/indice_largo"),
            frameIndex = target,
            assignedPtsUs = longIndex.frameTimesUs[target],
            indexFrameCount = longIndex.frameCount,
            encodedWidth = 320,
            encodedHeight = 240,
            declaredRotationDegrees = 0,
        )
        assertNull(
            "Un frame con índice ${target} no debe rechazarse por su posición absoluta",
            CodecCapturePolicy.validateRequest(postSeekRequest, longIndex),
        )
        assertEquals(longIndex.frameTimesUs[target], CodecCapturePolicy.seekTargetPtsUs(longIndex, target))

        // El límite sigue existiendo: cuenta frames decodificados desde el sync, no el índice.
        val budget = DecodeBudget(maxDecodedFrames = CodecCapturePolicy.MAX_DECODED_FRAMES)
        repeat(CodecCapturePolicy.MAX_DECODED_FRAMES) {
            assertTrue("La decodificación ${it + 1} debe caber en el presupuesto", budget.countDecodedFrame())
        }
        assertEquals(CodecCapturePolicy.MAX_DECODED_FRAMES, budget.decodedFrames)
        assertTrue(
            "La decodificación ${CodecCapturePolicy.MAX_DECODED_FRAMES + 1} desde el sync debe agotar el presupuesto",
            !budget.countDecodedFrame(),
        )
    }

    @Test
    fun `una solicitud alcanzable no se bloquea`() {
        assertNull(CodecCapturePolicy.validateRequest(request(), index))
    }

    @Test
    fun `el pts de salida se clasifica sin aproximar`() {
        assertEquals(
            CodecCapturePolicy.OutputPtsDecision.KEEP_DECODING,
            CodecCapturePolicy.decideOutputPts(observedPtsUs = 39_999L, targetPtsUs = 40_000L),
        )
        assertEquals(
            CodecCapturePolicy.OutputPtsDecision.CAPTURE,
            CodecCapturePolicy.decideOutputPts(observedPtsUs = 40_000L, targetPtsUs = 40_000L),
        )
        assertEquals(
            CodecCapturePolicy.OutputPtsDecision.PASSED_TARGET,
            CodecCapturePolicy.decideOutputPts(observedPtsUs = 40_001L, targetPtsUs = 40_000L),
        )
    }

    private fun layout(
        bufferWidth: Int = 8,
        bufferHeight: Int = 8,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        cropRight: Int = 7,
        cropBottom: Int = 7,
        yRowStride: Int = bufferWidth,
        uRowStride: Int = bufferWidth / 2,
        vRowStride: Int = bufferWidth / 2,
        uPixelStride: Int = 1,
        vPixelStride: Int = uPixelStride,
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
        vRowStride = vRowStride,
        vPixelStride = vPixelStride,
    )

    @Test
    fun `un crop impar o fuera del buffer se rechaza en lugar de adivinar`() {
        assertTrue(layout(cropLeft = 1).rejectionReason() != null)
        assertTrue(layout(cropRight = 8).rejectionReason() != null)
        assertTrue(layout(bufferWidth = 8, cropLeft = 0, cropRight = 7, uPixelStride = 3, vPixelStride = 3).rejectionReason() != null)
        assertNull(layout().rejectionReason())
    }

    @Test
    fun `el buffer semiplanar usa el stride intercalado completo y no el medio`() {
        val semiPlanar = Yuv420OutputLayout.fromBuffer(
            width = 8,
            height = 4,
            colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            stride = 16,
            sliceHeight = 4,
            crop = null,
        )
        assertEquals(16, semiPlanar.uRowStride)
        assertEquals(16, semiPlanar.vRowStride)
        assertEquals(2, semiPlanar.uPixelStride)
        assertNull(semiPlanar.rejectionReason())

        val planar = Yuv420OutputLayout.fromBuffer(
            width = 8,
            height = 4,
            colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            stride = 16,
            sliceHeight = 4,
            crop = null,
        )
        assertEquals(8, planar.uRowStride)
        assertEquals(1, planar.uPixelStride)

        // NV12: Y (16×4) y después UV intercalado con filas de 16 bytes. La fila 1 lleva V=200:
        // si se usara un row stride de croma de 8 (mitad del de Y) se leería el croma de la fila 0.
        val y = ByteBuffer.allocate(16 * 4).apply { for (index in 0 until 64) put(index, 235.toByte()) }
        val uv = ByteBuffer.allocate(16 * 2).apply {
            for (row in 0 until 2) {
                for (column in 0 until 8) {
                    val offset = row * 16 + column * 2
                    put(offset, 128.toByte())
                    put(offset + 1, if (row == 0) 128.toByte() else 200.toByte())
                }
            }
        }
        val uPlane = uv.duplicate().apply { position(0) }.slice()
        val vPlane = uv.duplicate().apply { position(1) }.slice()
        val argb = requireNotNull(semiPlanar.toArgb(y, uPlane, vPlane))
        assertEquals(8 * 4, argb.size)
        assertEquals(0xFFFFFFFF.toInt(), argb[0])
        // La segunda fila de croma cubre las filas de luma 2 y 3.
        val thirdLumaRow = argb[16]
        assertTrue(
            "La fila 2 debe leer su propia fila de croma, no la de la fila 0",
            (thirdLumaRow shr 16 and 0xFF) > (thirdLumaRow shr 8 and 0xFF),
        )
    }

    @Test
    fun `un ancho visible impar usa la muestra de croma compartida sin salir del buffer`() {
        val odd = layout(
            bufferWidth = 6,
            bufferHeight = 2,
            cropRight = 4,
            cropBottom = 1,
            yRowStride = 6,
            uRowStride = 3,
            vRowStride = 3,
        )
        assertEquals(5, odd.visibleWidth)
        assertEquals(3, odd.chromaColumns)
        assertNull(odd.rejectionReason())

        val y = ByteBuffer.allocate(12).apply { for (index in 0 until 12) put(index, 235.toByte()) }
        val chroma = ByteBuffer.allocate(6).apply { for (index in 0 until 6) put(index, 128.toByte()) }
        val argb = requireNotNull(odd.toArgb(y, chroma, chroma.duplicate()))
        assertEquals(10, argb.size)

        // Con solo 2 muestras de croma por fila el layout no cubre la última columna visible.
        assertTrue(odd.copy(uRowStride = 2, vRowStride = 2).rejectionReason() != null)
    }

    @Test
    fun `el perfil de color usa lo declarado y bloquea hdr o matrices desconocidas`() {
        assertEquals(
            YuvColorProfile.BT601_LIMITED,
            YuvColorContract.profileFor(
                YuvColorContract.UNSPECIFIED,
                YuvColorContract.UNSPECIFIED,
                YuvColorContract.UNSPECIFIED,
            ),
        )
        assertEquals(
            YuvColorProfile.BT709_LIMITED,
            YuvColorContract.profileFor(
                MediaFormat.COLOR_STANDARD_BT709,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                MediaFormat.COLOR_RANGE_LIMITED,
            ),
        )
        assertEquals(
            YuvColorProfile.BT601_FULL,
            YuvColorContract.profileFor(
                MediaFormat.COLOR_STANDARD_BT601_PAL,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                MediaFormat.COLOR_RANGE_FULL,
            ),
        )
        assertEquals(
            YuvColorProfile.BT709_FULL,
            YuvColorContract.profileFor(
                MediaFormat.COLOR_STANDARD_BT709,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                MediaFormat.COLOR_RANGE_FULL,
            ),
        )
        assertNull(
            "BT.2020 no se convierte con una matriz BT.601/709 supuesta",
            YuvColorContract.profileFor(
                MediaFormat.COLOR_STANDARD_BT2020,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                MediaFormat.COLOR_RANGE_LIMITED,
            ),
        )
        assertNull(
            "PQ (HDR10) debe bloquearse",
            YuvColorContract.profileFor(
                MediaFormat.COLOR_STANDARD_BT709,
                MediaFormat.COLOR_TRANSFER_ST2084,
                MediaFormat.COLOR_RANGE_LIMITED,
            ),
        )
        assertNull(
            "HLG debe bloquearse",
            YuvColorContract.profileFor(
                MediaFormat.COLOR_STANDARD_BT709,
                MediaFormat.COLOR_TRANSFER_HLG,
                MediaFormat.COLOR_RANGE_LIMITED,
            ),
        )
        assertNull(
            "Una clave ilegible no se interpreta como ausente",
            YuvColorContract.profileFor(
                YuvColorContract.UNREADABLE,
                YuvColorContract.UNSPECIFIED,
                YuvColorContract.UNSPECIFIED,
            ),
        )
        // 0 es el "unspecified" de Codec2: no declara matriz ni rango y no debe bloquear un SDR normal.
        assertEquals(
            YuvColorProfile.BT601_LIMITED,
            YuvColorContract.profileFor(0, 0, 0),
        )
    }

    @Test
    fun `la matriz declarada cambia la representacion en vez de ignorarse`() {
        // Y=100, U=100, V=160: cada matriz/rango produce valores distintos y deterministas.
        assertEquals(0xFF955329.toInt(), YuvColorProfile.BT601_LIMITED.toArgb(100, 100, 160))
        assertEquals(0xFF9B5727.toInt(), YuvColorProfile.BT709_LIMITED.toArgb(100, 100, 160))
        assertEquals(0xFF915732.toInt(), YuvColorProfile.BT601_FULL.toArgb(100, 100, 160))
        assertEquals(0xFF965A30.toInt(), YuvColorProfile.BT709_FULL.toArgb(100, 100, 160))

        // Croma neutro: blanco/negro de rango limitado coinciden en BT.601 y BT.709.
        for (profile in listOf(YuvColorProfile.BT601_LIMITED, YuvColorProfile.BT709_LIMITED)) {
            assertEquals(0xFF000000.toInt(), profile.toArgb(16, 128, 128))
            assertEquals(0xFFFFFFFF.toInt(), profile.toArgb(235, 128, 128))
        }

        val twoByTwo = layout(bufferWidth = 2, bufferHeight = 2, cropRight = 1, cropBottom = 1, uRowStride = 1, vRowStride = 1)
        val y = ByteBuffer.allocate(4).apply { for (index in 0 until 4) put(index, 100.toByte()) }
        val u = ByteBuffer.allocate(1).apply { put(0, 100.toByte()) }
        val v = ByteBuffer.allocate(1).apply { put(0, 160.toByte()) }
        assertEquals(
            0xFF9B5727.toInt(),
            requireNotNull(twoByTwo.toArgb(y, u, v, YuvColorProfile.BT709_LIMITED))[0],
        )
        assertEquals(
            0xFF955329.toInt(),
            requireNotNull(twoByTwo.toArgb(y, u, v, YuvColorProfile.BT601_LIMITED))[0],
        )
    }

    @Test
    fun `la conversion respeta crop y stride y no rellena lo que no puede leer`() {
        // Frame 8x8: Y=235 (blanco limitado) salvo el píxel (2,2) que es 16 (negro); croma neutro.
        val y = ByteBuffer.allocate(8 * 8).apply {
            for (row in 0 until 8) {
                for (column in 0 until 8) {
                    put(row * 8 + column, if (row == 2 && column == 2) 16 else 235.toByte())
                }
            }
        }
        val chroma = ByteBuffer.allocate(4 * 4).apply {
            for (index in 0 until 16) put(index, 128.toByte())
        }
        val cropped = layout(cropLeft = 2, cropTop = 2, cropRight = 5, cropBottom = 5)
        val argb = requireNotNull(cropped.toArgb(y, chroma, chroma.duplicate()))

        assertEquals(4 * 4, argb.size)
        assertEquals(0xFF000000.toInt(), argb[0])
        assertEquals(0xFFFFFFFF.toInt(), argb[1])
        // El buffer no cubre el layout declarado: se devuelve null, nunca negro inventado.
        assertNull(cropped.toArgb(y, ByteBuffer.allocate(2), ByteBuffer.allocate(2)))
    }
}
