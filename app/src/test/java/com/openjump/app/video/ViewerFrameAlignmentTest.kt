package com.openjump.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pruebas JVM de la traducción PTS→índice extraída del transporte del visor.
 *
 * No prueban la correlación física entre la imagen decodificada y el frame indexado, ni
 * demuestran precisión en runtime: solo fijan el contrato determinista del desfase
 * (latch y [ViewerFrameAlignment.rebase]) sobre índices PTS sintéticos.
 */
class ViewerFrameAlignmentTest {

    private fun index(times: LongArray) = VideoFrameIndex(
        frameTimesUs = times,
        durationUs = times.lastOrNull() ?: 0L,
        width = 1920,
        height = 1080,
        rotationDegrees = 0,
        mimeType = "video/avc",
    )

    private val zeroOrigin = index(longArrayOf(0L, 10_000L, 20_000L))
    private val nonZeroOrigin = index(longArrayOf(1_000_000L, 1_004_167L, 1_008_333L, 1_012_500L))

    @Test
    fun `origen cero se alinea sin desfase`() {
        val alignment = ViewerFrameAlignment()
        alignment.rebase(0)

        val aligned = alignment.align(zeroOrigin, playerPtsUs = 0L)

        assertEquals(0, aligned.renderedIndex)
        assertEquals(0L, aligned.canonicalPtsUs)
    }

    @Test
    fun `PTS no cero desplaza el timeline del player al indice original`() {
        val alignment = ViewerFrameAlignment()
        alignment.rebase(2)

        val aligned = alignment.align(nonZeroOrigin, playerPtsUs = 0L)

        assertEquals(2, aligned.renderedIndex)
        assertEquals(1_008_333L, aligned.canonicalPtsUs)
    }

    @Test
    fun `el desfase se fija en la primera salida y se reutiliza`() {
        val alignment = ViewerFrameAlignment()
        alignment.rebase(2)
        alignment.align(nonZeroOrigin, playerPtsUs = 0L)

        // Un desfase recalculado aquí usaría el PTS del segundo frame y volvería al
        // índice 2; el latch debe mantener la traducción del seek inicial.
        val next = alignment.align(nonZeroOrigin, playerPtsUs = 4_167L)
        val later = alignment.align(nonZeroOrigin, playerPtsUs = 8_333L)

        assertEquals(3, next.renderedIndex)
        assertEquals(1_012_500L, next.canonicalPtsUs)
        assertEquals(3, later.renderedIndex)
    }

    @Test
    fun `empate a mitad de camino desempata hacia el frame anterior`() {
        val alignment = ViewerFrameAlignment()
        alignment.rebase(0)
        alignment.align(zeroOrigin, playerPtsUs = 0L)

        val lowerTie = alignment.align(zeroOrigin, playerPtsUs = 5_000L)
        val upperTie = alignment.align(zeroOrigin, playerPtsUs = 15_000L)

        assertEquals(0, lowerTie.renderedIndex)
        assertEquals(0L, lowerTie.canonicalPtsUs)
        assertEquals(1, upperTie.renderedIndex)
        assertEquals(10_000L, upperTie.canonicalPtsUs)
    }

    @Test
    fun `devuelve el PTS canonico del frame resuelto y no el del player`() {
        val alignment = ViewerFrameAlignment()
        alignment.rebase(0)
        alignment.align(zeroOrigin, playerPtsUs = 0L)

        val aligned = alignment.align(zeroOrigin, playerPtsUs = 10_400L)

        assertEquals(1, aligned.renderedIndex)
        assertEquals(10_000L, aligned.canonicalPtsUs)
    }

    @Test
    fun `rebase descarta el desfase y fija el nuevo ancla`() {
        val steps = longArrayOf(0L, 10_000L, 20_000L, 30_000L, 40_000L)
        val stepIndex = index(steps)
        val alignment = ViewerFrameAlignment()
        alignment.rebase(1)
        alignment.align(stepIndex, playerPtsUs = 0L)

        alignment.rebase(4)
        val afterRebase = alignment.align(stepIndex, playerPtsUs = 0L)

        // Con el desfase antiguo (10 000) esta salida resolvería el frame 1.
        assertEquals(4, afterRebase.renderedIndex)
        assertEquals(40_000L, afterRebase.canonicalPtsUs)
    }

    @Test
    fun `rebase no altera las salidas anteriores ya resueltas`() {
        val alignment = ViewerFrameAlignment()
        alignment.rebase(1)
        val before = alignment.align(nonZeroOrigin, playerPtsUs = 0L)

        alignment.rebase(3)
        alignment.align(nonZeroOrigin, playerPtsUs = 0L)

        assertEquals(1, before.renderedIndex)
        assertEquals(1_004_167L, before.canonicalPtsUs)
    }
}
