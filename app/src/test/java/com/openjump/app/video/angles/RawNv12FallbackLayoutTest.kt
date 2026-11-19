package com.openjump.app.video.angles

import android.media.MediaCodecInfo
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM del fallback NV12 **sin `Image`** del productor de captura.
 *
 * El riesgo que fijan es concreto: el fallback sustituía `KEY_STRIDE`/`KEY_SLICE_HEIGHT` ausentes,
 * ilegibles o `<= 0` por `width`/`height`. Sin `Image` eso no demuestra dónde empieza el croma, así
 * que un buffer con filas de luma de padding podía convertirse leyendo el padding como UV y aceptarse
 * con el PTS correcto pero píxeles incorrectos. Aquí se exige, sin `MediaCodec`:
 *
 * - metadatos ausentes, ilegibles, cero o negativos → plan bloqueado, nunca un layout inventado;
 * - layout declarado válido con padding centinela → sólo se leen luma y croma reales (el centinela no
 *   aparece en los píxeles) y el coste de la ventana se mide con `offset`/`size`, no con `capacity()`;
 * - datos truncados (ventana producida menor que el layout o fuera de la capacidad) → bloqueo tipado;
 * - se deja constancia de que el layout inventado del código anterior sí leía padding como croma, para
 *   que la prueba de padding no sea vacua.
 */
class RawNv12FallbackLayoutTest {

    private val semiPlanar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private val planar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
    private val flexible = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

    private fun plan(
        width: Int = 4,
        height: Int = 4,
        colorFormat: Int = semiPlanar,
        stride: ExplicitInt = ExplicitInt.Declared(8),
        sliceHeight: ExplicitInt = ExplicitInt.Declared(4),
        crop: CropWindow? = null,
    ): RawNv12OutputPlan = Yuv420OutputLayout.fromDeclaredBuffer(
        width = width,
        height = height,
        colorFormat = colorFormat,
        declaredStride = stride,
        declaredSliceHeight = sliceHeight,
        crop = crop,
    )

    private fun RawNv12OutputPlan.rejection(): String {
        assertTrue("El plan debía bloquearse y fue $this", this is RawNv12OutputPlan.Unsupported)
        return (this as RawNv12OutputPlan.Unsupported).detail
    }

    private fun RawNv12OutputPlan.valid(): RawNv12OutputLayout {
        assertTrue("El plan debía ser válido y fue $this", this is RawNv12OutputPlan.Valid)
        return (this as RawNv12OutputPlan.Valid).layout
    }

    @Test
    fun `metadatos ausentes ilegibles o no positivos bloquean el fallback`() {
        assertTrue(plan(stride = ExplicitInt.Absent).rejection().contains("KEY_STRIDE"))
        assertTrue(plan(sliceHeight = ExplicitInt.Absent).rejection().contains("KEY_SLICE_HEIGHT"))
        assertTrue(plan(stride = ExplicitInt.Unreadable).rejection().contains("KEY_STRIDE"))
        assertTrue(plan(sliceHeight = ExplicitInt.Unreadable).rejection().contains("KEY_SLICE_HEIGHT"))
        assertTrue(plan(stride = ExplicitInt.Declared(0)).rejection().contains("KEY_STRIDE"))
        assertTrue(plan(sliceHeight = ExplicitInt.Declared(0)).rejection().contains("KEY_SLICE_HEIGHT"))
        assertTrue(plan(stride = ExplicitInt.Declared(-8)).rejection().contains("KEY_STRIDE"))
        assertTrue(plan(sliceHeight = ExplicitInt.Declared(-4)).rejection().contains("KEY_SLICE_HEIGHT"))

        // Un stride/sliceHeight declarado pero incoherente con el frame tampoco se corrige al alza.
        assertTrue(plan(stride = ExplicitInt.Declared(2)).rejection().contains("menor que el ancho"))
        assertTrue(plan(stride = ExplicitInt.Declared(9)).rejection().contains("impar"))
        assertTrue(plan(sliceHeight = ExplicitInt.Declared(2)).rejection().contains("filas"))
        // Sin Image, el planar y el flexible no tienen layout declarado demostrable.
        assertNotEquals("", plan(colorFormat = planar).rejection())
        assertNotEquals("", plan(colorFormat = flexible).rejection())
    }

    @Test
    fun `el layout declarado con padding fija los bytes reales de luma y croma`() {
        val derived = plan(stride = ExplicitInt.Declared(8), sliceHeight = ExplicitInt.Declared(6)).valid()

        assertEquals(8 * 6L, derived.yBytes)
        assertEquals("Croma UV de ceil(sliceHeight/2) filas de stride", 8 * 3L, derived.chromaBytes)
        assertEquals(8 * 6L + 8 * 3L, derived.requiredSpanBytes)
        assertEquals(8, derived.layout.yRowStride)
        assertEquals(8, derived.layout.uRowStride)
        assertEquals(2, derived.layout.uPixelStride)
        assertEquals("El visible es width×height, no el sliceHeight", 4, derived.layout.visibleWidth)
        assertEquals(4, derived.layout.visibleHeight)
        assertNull(derived.layout.rejectionReason())
    }

    @Test
    fun `la ventana producida se mide con offset y size no con capacity`() {
        val required = plan(stride = ExplicitInt.Declared(8), sliceHeight = ExplicitInt.Declared(6))
            .valid()
            .requiredSpanBytes

        assertNull(
            "Offset+size exactos deben bastar",
            Yuv420OutputLayout.producedSpanRejection(offset = 16, size = required.toInt(), capacity = 16 + required.toInt(), requiredSpanBytes = required),
        )
        assertNull(
            "Capacidad holgada no cambia el requisito de size",
            Yuv420OutputLayout.producedSpanRejection(offset = 0, size = required.toInt(), capacity = 4_096, requiredSpanBytes = required),
        )
        assertTrue(
            "Un byte menos que el layout declarado se bloquea",
            Yuv420OutputLayout.producedSpanRejection(offset = 0, size = required.toInt() - 1, capacity = 4_096, requiredSpanBytes = required)!!
                .contains("exige"),
        )
        assertTrue(
            "La ventana no puede salir de la capacidad",
            Yuv420OutputLayout.producedSpanRejection(offset = 16, size = required.toInt(), capacity = 15 + required.toInt(), requiredSpanBytes = required)!!
                .contains("capacidad"),
        )
        assertTrue(
            "Un códec que no produjo bytes no convierte",
            Yuv420OutputLayout.producedSpanRejection(offset = 0, size = 0, capacity = 4_096, requiredSpanBytes = required) != null,
        )
        assertTrue(
            "Un offset negativo se bloquea",
            Yuv420OutputLayout.producedSpanRejection(offset = -1, size = required.toInt(), capacity = 4_096, requiredSpanBytes = required) != null,
        )
    }

    /**
     * Buffer NV12 4×4 con stride 8 y sliceHeight 6: dos filas de luma de padding vertical y cuatro
     * columnas de padding horizontal rellenas con centinelas. El croma real vive **después** de
     * `stride*sliceHeight`; si se usase `width`/`height` inventados, el croma se leería de la luma de
     * las filas 2–3 o del padding y el color no sería el declarado.
     */
    private fun paddedNv12Buffer(): ByteBuffer {
        val stride = 8
        val sliceHeight = 6
        val luma = stride * sliceHeight
        val chroma = stride * 3
        return ByteBuffer.allocate(luma + chroma).apply {
            for (row in 0 until sliceHeight) {
                for (column in 0 until stride) {
                    val index = row * stride + column
                    val value = when {
                        row >= 4 -> 0x11.toByte() // padding vertical: si se leyese como UV, alteraría el color
                        column >= 4 -> 0xEE.toByte() // padding horizontal centinela
                        else -> 235.toByte() // luma de referencia (blanco limitado)
                    }
                    put(index, value)
                }
            }
            // UV intercalado neutro (128) en las filas de croma reales.
            for (row in 0 until 3) {
                for (column in 0 until 2) {
                    put(luma + row * stride + column * 2, 128.toByte())
                    put(luma + row * stride + column * 2 + 1, 128.toByte())
                }
            }
            // Una fila de croma de relleno con UV 255: sólo se leería si el layout se desbordase.
            for (column in 0 until stride) put(luma + 2 * stride + column, 0xFF.toByte())
        }
    }

    @Test
    fun `el padding centinela no se lee y el layout declarado da el color esperado`() {
        val buffer = paddedNv12Buffer()
        val derived = plan(stride = ExplicitInt.Declared(8), sliceHeight = ExplicitInt.Declared(6)).valid()
        assertEquals(buffer.capacity().toLong(), derived.requiredSpanBytes)

        val expected = YuvColorProfile.BT601_LIMITED.toArgb(235, 128, 128)
        val yPlane = buffer.duplicate().apply { position(0); limit(derived.yBytes.toInt()) }.slice()
        val uPlane = buffer.duplicate()
            .apply { position(derived.yBytes.toInt()); limit((derived.yBytes + derived.chromaBytes).toInt()) }
            .slice()
        val vPlane = buffer.duplicate()
            .apply { position((derived.yBytes + 1L).toInt()); limit((derived.yBytes + derived.chromaBytes).toInt()) }
            .slice()

        val pixels = requireNotNull(derived.layout.toArgb(yPlane, uPlane, vPlane))
        assertEquals(16, pixels.size)
        for ((index, pixel) in pixels.withIndex()) {
            assertEquals("El píxel $index no puede venir del padding centinela", expected, pixel)
        }

        // Los planos se recortan a su región: el croma visible (2 filas, muestras 0..10) convierte
        // aunque la fila de relleno UV 255 quede fuera; si falta un byte necesario, se falla cerrado.
        val withoutPaddingRow = uPlane.duplicate().apply { limit(2 * 8) }
        assertNotNull(
            "La fila de croma de relleno no es necesaria para el visible",
            derived.layout.toArgb(yPlane, withoutPaddingRow, vPlane),
        )
        val truncatedU = uPlane.duplicate().apply { limit(10) }
        assertNull(
            "El plano de croma truncado no puede leer fuera de su región",
            derived.layout.toArgb(yPlane, truncatedU, vPlane),
        )
    }

    @Test
    fun `el layout inventado con width y height leia padding como croma`() {
        // Reproduce el comportamiento retirado: stride=width, sliceHeight=height y planos hasta la
        // capacidad. Sobre el mismo buffer con padding, los píxeles NO son el blanco declarado: eso es
        // exactamente lo que se aceptaba antes como frame verificado.
        val buffer = paddedNv12Buffer()
        val invented = Yuv420OutputLayout.fromBuffer(
            width = 4,
            height = 4,
            colorFormat = semiPlanar,
            stride = 4,
            sliceHeight = 4,
            crop = null,
        )
        val y = buffer.duplicate().apply { position(0) }.slice()
        val u = buffer.duplicate().apply { position(16) }.slice()
        val v = buffer.duplicate().apply { position(17) }.slice()

        val expected = YuvColorProfile.BT601_LIMITED.toArgb(235, 128, 128)
        val pixels = requireNotNull(invented.toArgb(y, u, v))
        assertTrue(
            "El layout inventado debía leer padding/Y como croma y producir otro color",
            pixels.any { it != expected },
        )
    }
}
