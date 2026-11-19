package com.openjump.app.video.angles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pruebas JVM de la identidad visual independiente del PTS.
 *
 * El test central simula un adaptador que **asigna** el PTS del frame 3 pero devuelve los píxeles del
 * frame 7: la igualdad de PTS no lo detecta y el ordinal leído del contenido sí. Ese es el criterio
 * para los fixtures instrumentados: verificar identidad visual, no repetir el PTS asignado.
 */
class FrameVisualIdentityTest {

    private val width = 320
    private val height = 240

    private fun assertRoundTrip(ordinal: Int) {
        val pixels = FrameVisualIdentity.encodeOrdinalFrame(width, height, ordinal)
        assertEquals(ordinal, FrameVisualIdentity.decodeOrdinal(pixels, width, height))
    }

    @Test
    fun `el patron sintetico codifica y decodifica todos los ordinales de referencia`() {
        listOf(
            0,
            1,
            2,
            3,
            7,
            42,
            255,
            2047,
            FrameVisualIdentity.MAX_ORDINAL,
        ).forEach(::assertRoundTrip)
    }

    @Test
    fun `un frame ajeno o demasiado pequeno no produce un ordinal adivinado`() {
        assertNull(FrameVisualIdentity.decodeOrdinal(IntArray(width * height), width, height))
        assertNull(
            FrameVisualIdentity.decodeOrdinal(
                IntArray(width * height) { 0xFFFFFFFF.toInt() },
                width,
                height,
            ),
        )
        assertNull(FrameVisualIdentity.decodeOrdinal(IntArray(100 * 100), 100, 100))
        assertNull(FrameVisualIdentity.decodeOrdinal(IntArray(width * height), width + 1, height))
    }

    @Test
    fun `el patron exige dimensiones y ordinal validos`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameVisualIdentity.encodeOrdinalFrame(100, 100, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameVisualIdentity.encodeOrdinalFrame(width, height, FrameVisualIdentity.MAX_ORDINAL + 1)
        }
    }

    @Test
    fun `el digest por filas coincide con el digest del frame completo`() {
        val pixels = FrameVisualIdentity.encodeOrdinalFrame(width, height, 42)
        val byRows = FrameVisualIdentity.digest(width, height) { y, row ->
            System.arraycopy(pixels, y * width, row, 0, width)
        }

        assertEquals(FrameVisualIdentity.digest(pixels), byRows)
    }

    @Test
    fun `el digest es estable y distingue contenido distinto`() {
        val frameThree = FrameVisualIdentity.encodeOrdinalFrame(width, height, 3)
        val sameFrameThree = FrameVisualIdentity.encodeOrdinalFrame(width, height, 3)
        val frameSeven = FrameVisualIdentity.encodeOrdinalFrame(width, height, 7)

        assertEquals(FrameVisualIdentity.digest(frameThree), FrameVisualIdentity.digest(sameFrameThree))
        assertNotEquals(FrameVisualIdentity.digest(frameThree), FrameVisualIdentity.digest(frameSeven))
    }

    @Test
    fun `la identidad visual detecta otro fotograma aunque el PTS asignado coincida`() {
        val adapter = DeceptiveFrameAdapter(requestedIndex = 3, returnedOrdinal = 7)
        val pixels = adapter.decodeReturnedFrame()

        // La ruta "asigna" exactamente el PTS del frame solicitado: comprobar solo esto pasaría.
        assertEquals(3 * adapter.frameDurationUs, adapter.assignedPtsUs)

        // La verificación independiente lee el contenido: es otro fotograma.
        val observedOrdinal = FrameVisualIdentity.decodeOrdinal(pixels, width, height)
        assertEquals(7, observedOrdinal)
        assertNotEquals(adapter.requestedIndex, observedOrdinal)

        val requestedFrame = FrameVisualIdentity.encodeOrdinalFrame(width, height, adapter.requestedIndex)
        assertNotEquals(FrameVisualIdentity.digest(requestedFrame), FrameVisualIdentity.digest(pixels))
    }

    /**
     * Adaptador falso que reproduce el fallo que este contrato debe hacer detectable: reporta el PTS
     * del frame solicitado pero entrega píxeles de otro. No es un decoder: existe solo en el test para
     * demostrar que la igualdad de PTS no acredita identidad visual.
     */
    private inner class DeceptiveFrameAdapter(
        val requestedIndex: Int,
        private val returnedOrdinal: Int,
    ) {
        val frameDurationUs = 40_000L
        val assignedPtsUs: Long = requestedIndex * frameDurationUs

        fun decodeReturnedFrame(): IntArray =
            FrameVisualIdentity.encodeOrdinalFrame(width, height, returnedOrdinal)
    }
}
