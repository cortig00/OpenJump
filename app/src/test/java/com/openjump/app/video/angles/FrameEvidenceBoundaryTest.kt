package com.openjump.app.video.angles

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pruebas JVM de la frontera de confianza de la evidencia temporal (defecto B1 nº 2) y del
 * presupuesto de copias simultáneas (defecto B1 nº 1).
 *
 * Lo que se fija aquí:
 * - [FrameTemporalEvidence] no tiene constructor público: un llamador externo no puede atribuir un
 *   PTS del códec a píxeles que no vienen de ese buffer.
 * - Las fábricas públicas describen rutas siempre rechazadas y no transportan PTS del códec.
 * - Una generación del códec es de un solo uso: no se puede replayar para otra imagen.
 * - La política exige que fuente y frame de la evidencia coincidan con la solicitud.
 * - El presupuesto cuenta todas las copias simultáneas, no solo una.
 */
class FrameEvidenceBoundaryTest {

    private val policy = FrameFreezePolicy()
    private val source = FrameSourceIdentity("fixture:angles/ordinal_cfr_bframes")

    private fun request(
        frameIndex: Int = 3,
        assignedPtsUs: Long = 120_000L,
        indexFrameCount: Int = 24,
        encodedWidth: Int = 320,
        encodedHeight: Int = 240,
        declaredRotationDegrees: Int = 0,
        sourceIdentity: FrameSourceIdentity = source,
    ) = FrameFreezeRequest(
        sourceIdentity = sourceIdentity,
        frameIndex = frameIndex,
        assignedPtsUs = assignedPtsUs,
        indexFrameCount = indexFrameCount,
        encodedWidth = encodedWidth,
        encodedHeight = encodedHeight,
        declaredRotationDegrees = declaredRotationDegrees,
    )

    private fun codecEvidence(
        request: FrameFreezeRequest,
        outputPtsUs: Long = request.assignedPtsUs,
        frameIndex: Int = request.frameIndex,
        sourceIdentity: FrameSourceIdentity = request.sourceIdentity,
    ) = FrameTemporalEvidence.fromCodecOutputBuffer(
        generation = CodecOutputGeneration.mint(outputBufferIndex = 7),
        sourceIdentity = sourceIdentity,
        frameIndex = frameIndex,
        assignedPtsUs = request.assignedPtsUs,
        codecOutputPtsUs = outputPtsUs,
    )

    private fun observation(request: FrameFreezeRequest, temporal: FrameTemporalEvidence) =
        FrameFreezeObservation(
            request = request,
            temporal = temporal,
            orientation = FrameOrientationPlanner.plan(
                encodedWidth = request.encodedWidth,
                encodedHeight = request.encodedHeight,
                declaredRotationDegrees = request.declaredRotationDegrees,
                decodedWidth = request.encodedWidth,
                decodedHeight = request.encodedHeight,
            ) as FrameOrientationPlan.Determined,
            decodedWidth = request.encodedWidth,
            decodedHeight = request.encodedHeight,
            decodedAllocationBytes = request.encodedWidth.toLong() * request.encodedHeight * 4L,
            contentDigest = 1L,
        )

    @Test
    fun `la evidencia temporal no tiene constructor publico`() {
        // Kotlin emite un constructor sintético con DefaultConstructorMarker para que el companion
        // acceda al privado; no es invocable desde código Kotlin, así que se excluye del contrato.
        val publicConstructors = FrameTemporalEvidence::class.java.declaredConstructors.filter {
            Modifier.isPublic(it.modifiers) && !it.isSynthetic
        }
        assertTrue(
            "Un llamador externo no debe poder fabricar evidencia temporal; constructores públicos: " +
                publicConstructors.map { it.parameterCount },
            publicConstructors.isEmpty(),
        )
    }

    @Test
    fun `las fabricas publicas nunca acreditan un PTS del codec`() {
        val request = request()
        val closest = FrameTemporalEvidence.unverifiedRetriever(
            assignedPtsUs = request.assignedPtsUs,
            sourceIdentity = request.sourceIdentity,
            frameIndex = request.frameIndex,
        )
        val none = FrameTemporalEvidence.none(
            assignedPtsUs = request.assignedPtsUs,
            sourceIdentity = request.sourceIdentity,
            frameIndex = request.frameIndex,
        )

        assertEquals(FrameTemporalCorrelation.RETRIEVER_CLOSEST_UNVERIFIED, closest.correlation)
        assertEquals(FrameTemporalCorrelation.NONE, none.correlation)
        assertNull(closest.codecOutputPtsUs)
        assertNull(none.codecOutputPtsUs)
        assertNull(closest.generationNonce)

        for (evidence in listOf(closest, none)) {
            val decision = policy.evaluate(observation(request, evidence))
            assertTrue("Se esperaba rechazo y fue $decision", decision is FrameFreezeDecision.Rejected)
            assertEquals(
                FrameFreezeRejectionReason.UNVERIFIED_TEMPORAL_CORRELATION,
                (decision as FrameFreezeDecision.Rejected).reason,
            )
        }
    }

    @Test
    fun `una generacion del codec solo se consume una vez`() {
        val request = request()
        val generation = CodecOutputGeneration.mint(outputBufferIndex = 3)
        FrameTemporalEvidence.fromCodecOutputBuffer(
            generation = generation,
            sourceIdentity = request.sourceIdentity,
            frameIndex = request.frameIndex,
            assignedPtsUs = request.assignedPtsUs,
            codecOutputPtsUs = request.assignedPtsUs,
        )
        try {
            FrameTemporalEvidence.fromCodecOutputBuffer(
                generation = generation,
                sourceIdentity = request.sourceIdentity,
                frameIndex = request.frameIndex,
                assignedPtsUs = request.assignedPtsUs,
                codecOutputPtsUs = request.assignedPtsUs,
            )
            fail("Reutilizar la generación del códec debe fallar: si no, la evidencia sería replayable.")
        } catch (_: IllegalArgumentException) {
            // Esperado.
        }
    }

    @Test
    fun `la evidencia de otra fuente se rechaza aunque el PTS coincida`() {
        val request = request()
        val evidence = codecEvidence(request, sourceIdentity = FrameSourceIdentity("otro-video"))
        val decision = policy.evaluate(observation(request, evidence))

        assertEquals(
            FrameFreezeRejectionReason.REQUEST_EVIDENCE_MISMATCH,
            (decision as FrameFreezeDecision.Rejected).reason,
        )
    }

    @Test
    fun `la evidencia de otro frame se rechaza aunque el PTS coincida`() {
        val request = request(frameIndex = 3)
        val evidence = codecEvidence(request, frameIndex = 4)
        val decision = policy.evaluate(observation(request, evidence))

        assertEquals(
            FrameFreezeRejectionReason.REQUEST_EVIDENCE_MISMATCH,
            (decision as FrameFreezeDecision.Rejected).reason,
        )
    }

    @Test
    fun `la evidencia fuerte aceptada solo puede venir de la generacion del codec`() {
        val request = request()
        val decision = policy.evaluate(observation(request, codecEvidence(request)))

        val provenance = (decision as FrameFreezeDecision.Accepted).provenance
        assertEquals(FrameTemporalClaim.DECODER_OUTPUT_BUFFER_CORRELATED, provenance.temporalClaim)
        assertEquals(request.sourceIdentity, provenance.sourceIdentity)
        assertEquals(request.frameIndex, provenance.frameIndex)
    }

    @Test
    fun `el presupuesto cuenta todas las copias simultaneas`() {
        // 2560x1920: decodificado + 2 copias cabe; decodificado + 3 copias no.
        val width = 2560
        val height = 1920
        val decoded = width.toLong() * height * 4L

        assertTrue("decodificado + una copia debe caber", FrameFreezeBudget.isWithinBudget(decoded, width, height))
        assertTrue(
            "decodificado + dos copias debe caber (rotada + inmutable)",
            FrameFreezeBudget.isWithinBudget(decoded, width, height, simultaneousFrozenCopies = 2),
        )
        assertFalse(
            "decodificado + tres copias no cabe en la envolvente de 64 MiB",
            FrameFreezeBudget.isWithinBudget(decoded, width, height, simultaneousFrozenCopies = 3),
        )
        val oneCopy = FrameFreezeBudget.requiredBytes(width, height, 1)
        val twoCopies = FrameFreezeBudget.requiredBytes(width, height, 2)
        assertTrue("dos copias presupuestan más que una", twoCopies > oneCopy)
        assertTrue(
            "el presupuesto de dos copias incluye ambos bitmaps y un solo scratch de fila",
            twoCopies < oneCopy * 2,
        )
        // Un 4K no cabe con dos copias simultáneas: se bloquea en lugar de arriesgar OOM.
        val uhd = 3840L * 2160 * 4L
        assertTrue(FrameFreezeBudget.isWithinBudget(uhd, 3840, 2160, simultaneousFrozenCopies = 1))
        assertFalse(FrameFreezeBudget.isWithinBudget(uhd, 3840, 2160, simultaneousFrozenCopies = 2))
    }

    @Test
    fun `un presupuesto con copias invalidas se rechaza sin lanzar`() {
        assertFalse(FrameFreezeBudget.isWithinBudget(0L, 320, 240))
        try {
            FrameFreezeBudget.requiredBytes(320, 240, 0)
            fail("Cero copias no es un presupuesto válido.")
        } catch (_: IllegalArgumentException) {
            // Esperado.
        }
    }
}
