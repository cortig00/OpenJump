package com.openjump.app.video.angles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM del presupuesto de memoria y de la envolvente de proceso (defecto F2 de la validación
 * instrumentada del 2026-11-10).
 *
 * Lo que se fija aquí:
 * - Una fuente real fuera de la envolvente (8K) produce **rechazo booleano/tipado**, no una excepción
 *   desde `VideoFrameSafety.checkedArea`.
 * - Los límites de dimensión y área se conservan: justo en `MAX_FRAME_AREA` se acepta, un píxel más no.
 * - `FrameFreezeRequest` puede describir una fuente real fuera de la envolvente sin lanzar; la política
 *   la rechaza antes de planificar orientación o asignar copias.
 */
class FrameFreezeBudgetContractTest {

    private val source = FrameSourceIdentity("fixture:angles/ordinal_cfr_bframes")

    @Test
    fun `un 8k fuera de la envolvente devuelve false en vez de lanzar`() {
        val eightKBytes = 7680L * 4320L * 4L
        assertFalse("8K no cabe en la envolvente de trabajo", FrameFreezeBudget.isWithinBudget(eightKBytes, 7680, 4320))
        assertFalse(FrameFreezeBudget.isWithinFrameEnvelope(7680, 4320))

        // Consulta total: ni dimensiones inválidas, ni cero copias, ni asignación no positiva lanzan.
        assertFalse(FrameFreezeBudget.isWithinBudget(1L, 0, 0))
        assertFalse(FrameFreezeBudget.isWithinBudget(1L, 320, 240, simultaneousFrozenCopies = 0))
        assertFalse(FrameFreezeBudget.isWithinBudget(0L, 320, 240))
        assertFalse(FrameFreezeBudget.isWithinBudget(1L, -1, 240))

        // Los límites se conservan: 4000×3000 = MAX_FRAME_AREA está dentro; 4000×3001 no.
        assertTrue(FrameFreezeBudget.isWithinFrameEnvelope(4000, 3000))
        assertFalse(FrameFreezeBudget.isWithinFrameEnvelope(4000, 3001))
        assertTrue(FrameFreezeBudget.isWithinBudget(320L * 240L * 4L, 320, 240))
    }

    @Test
    fun `una solicitud puede describir una fuente fuera de la envolvente sin lanzar`() {
        val request = FrameFreezeRequest(
            sourceIdentity = source,
            frameIndex = 0,
            assignedPtsUs = 0L,
            indexFrameCount = 1,
            encodedWidth = 7680,
            encodedHeight = 4320,
            declaredRotationDegrees = 0,
        )
        assertEquals(7680, request.encodedWidth)
        assertEquals(4320, request.encodedHeight)
        assertFalse(FrameFreezeBudget.isWithinFrameEnvelope(request.encodedWidth, request.encodedHeight))
    }

    @Test
    fun `la politica rechaza con motivo tipado una fuente fuera de la envolvente`() {
        val request = FrameFreezeRequest(
            sourceIdentity = source,
            frameIndex = 0,
            assignedPtsUs = 0L,
            indexFrameCount = 1,
            encodedWidth = 7680,
            encodedHeight = 4320,
            declaredRotationDegrees = 0,
        )
        val evidence = FrameTemporalEvidence.fromCodecOutputBuffer(
            generation = CodecOutputGeneration.mint(outputBufferIndex = 0),
            sourceIdentity = request.sourceIdentity,
            frameIndex = request.frameIndex,
            assignedPtsUs = request.assignedPtsUs,
            codecOutputPtsUs = request.assignedPtsUs,
        )
        val decision = FrameFreezePolicy().evaluate(
            FrameFreezeObservation(
                request = request,
                temporal = evidence,
                orientation = FrameOrientationPlan.Determined(
                    rotationToApplyDegrees = 0,
                    orientedWidth = 7680,
                    orientedHeight = 4320,
                    certainty = FrameOrientationCertainty.DECLARED_NO_ROTATION,
                ),
                decodedWidth = 7680,
                decodedHeight = 4320,
                decodedAllocationBytes = 7680L * 4320L * 4L,
                contentDigest = 1L,
            ),
        )
        assertTrue("Se esperaba rechazo y fue $decision", decision is FrameFreezeDecision.Rejected)
        assertEquals(
            FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
            (decision as FrameFreezeDecision.Rejected).reason,
        )
    }
}
