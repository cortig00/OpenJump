package com.openjump.app.video.angles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM de la política de congelación: la única puerta que puede etiquetar un bitmap como el
 * frame solicitado.
 *
 * El énfasis es negativo: [FrameTemporalCorrelation.RETRIEVER_CLOSEST_UNVERIFIED] debe bloquearse
 * aunque el PTS asignado parezca correcto, y la orientación 180 no demostrable también. No se prueba
 * aquí ningún decoder real.
 */
class FrameFreezePolicyTest {

    private val policy = FrameFreezePolicy()

    private fun request(
        frameIndex: Int = 3,
        assignedPtsUs: Long = 120_000L,
        indexFrameCount: Int = 24,
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

    private fun codecMatch(request: FrameFreezeRequest) = FrameTemporalEvidence.fromCodecOutputBuffer(
        generation = CodecOutputGeneration.mint(outputBufferIndex = 0),
        sourceIdentity = request.sourceIdentity,
        frameIndex = request.frameIndex,
        assignedPtsUs = request.assignedPtsUs,
        codecOutputPtsUs = request.assignedPtsUs,
    )

    private fun closestUnverified(request: FrameFreezeRequest) = FrameTemporalEvidence.unverifiedRetriever(
        assignedPtsUs = request.assignedPtsUs,
        sourceIdentity = request.sourceIdentity,
        frameIndex = request.frameIndex,
    )

    private fun observation(
        request: FrameFreezeRequest,
        temporal: FrameTemporalEvidence,
        decodedWidth: Int = request.encodedWidth,
        decodedHeight: Int = request.encodedHeight,
        decodedAllocationBytes: Long = request.encodedWidth.toLong() * request.encodedHeight * 4L,
        contentDigest: Long = 0x1234_5678_9ABC_DEF0L,
        orientation: FrameOrientationPlan.Determined? = null,
    ): FrameFreezeObservation {
        val derived = FrameOrientationPlanner.plan(
            encodedWidth = request.encodedWidth,
            encodedHeight = request.encodedHeight,
            declaredRotationDegrees = request.declaredRotationDegrees,
            decodedWidth = decodedWidth,
            decodedHeight = decodedHeight,
        )
        val orientationToUse = orientation ?: (derived as FrameOrientationPlan.Determined)
        return FrameFreezeObservation(
            request = request,
            temporal = temporal,
            orientation = orientationToUse,
            decodedWidth = decodedWidth,
            decodedHeight = decodedHeight,
            decodedAllocationBytes = decodedAllocationBytes,
            contentDigest = contentDigest,
        )
    }

    private fun accepted(observation: FrameFreezeObservation): FrameCaptureProvenance {
        val decision = policy.evaluate(observation)
        assertTrue("Se esperaba Accepted y fue $decision", decision is FrameFreezeDecision.Accepted)
        return (decision as FrameFreezeDecision.Accepted).provenance
    }

    private fun rejected(observation: FrameFreezeObservation, reason: FrameFreezeRejectionReason) {
        val decision = policy.evaluate(observation)
        assertTrue("Se esperaba Rejected($reason) y fue $decision", decision is FrameFreezeDecision.Rejected)
        assertEquals(reason, (decision as FrameFreezeDecision.Rejected).reason)
    }

    @Test
    fun `acepta solo la evidencia del buffer de salida con PTS coincidente`() {
        val request = request()
        val provenance = accepted(observation(request, codecMatch(request)))

        assertEquals(FrameTemporalClaim.DECODER_OUTPUT_BUFFER_CORRELATED, provenance.temporalClaim)
        assertEquals(request.assignedPtsUs, provenance.assignedRequestPtsUs)
        assertEquals(request.assignedPtsUs, provenance.decoderReportedOutputPtsUs)
        assertEquals(0, provenance.rotationAppliedDegrees)
        assertEquals(320, provenance.frozenWidth)
        assertEquals(240, provenance.frozenHeight)
        assertEquals(0x1234_5678_9ABC_DEF0L, provenance.contentDigest)
    }

    @Test
    fun `OPTION_CLOSEST se bloquea aunque el PTS asignado sea el esperado`() {
        val request = request()
        rejected(
            observation(request, closestUnverified(request)),
            FrameFreezeRejectionReason.UNVERIFIED_TEMPORAL_CORRELATION,
        )
    }

    @Test
    fun `una ruta sin ninguna evidencia temporal se bloquea`() {
        val request = request()
        val noEvidence = FrameTemporalEvidence.none(
            assignedPtsUs = request.assignedPtsUs,
            sourceIdentity = request.sourceIdentity,
            frameIndex = request.frameIndex,
        )
        rejected(observation(request, noEvidence), FrameFreezeRejectionReason.UNVERIFIED_TEMPORAL_CORRELATION)
    }

    @Test
    fun `un PTS de salida distinto no se corrige ni se sustituye`() {
        val request = request()
        val mismatch = FrameTemporalEvidence.fromCodecOutputBuffer(
            generation = CodecOutputGeneration.mint(outputBufferIndex = 1),
            sourceIdentity = request.sourceIdentity,
            frameIndex = request.frameIndex,
            assignedPtsUs = request.assignedPtsUs,
            codecOutputPtsUs = request.assignedPtsUs + 40_000L,
        )
        rejected(observation(request, mismatch), FrameFreezeRejectionReason.DECODER_PTS_MISMATCH)
    }

    @Test
    fun `la evidencia de otra solicitud no se reutiliza`() {
        val request = request(assignedPtsUs = 120_000L)
        val evidenceForAnotherRequest = FrameTemporalEvidence.fromCodecOutputBuffer(
            generation = CodecOutputGeneration.mint(outputBufferIndex = 2),
            sourceIdentity = request.sourceIdentity,
            frameIndex = request.frameIndex,
            assignedPtsUs = 160_000L,
            codecOutputPtsUs = 160_000L,
        )
        rejected(observation(request, evidenceForAnotherRequest), FrameFreezeRejectionReason.REQUEST_EVIDENCE_MISMATCH)
    }

    @Test
    fun `un plan de orientacion aportado no se cree si contradice los metadatos`() {
        val request = request(declaredRotationDegrees = 180)
        val forgedPlan = FrameOrientationPlan.Determined(
            rotationToApplyDegrees = 0,
            orientedWidth = 320,
            orientedHeight = 240,
            certainty = FrameOrientationCertainty.DECLARED_NO_ROTATION,
        )
        rejected(
            observation(request, codecMatch(request), orientation = forgedPlan),
            FrameFreezeRejectionReason.ORIENTATION_UNDETERMINED,
        )
    }

    @Test
    fun `acepta 90 grados cuando el decoder ya entrego dimensiones presentadas`() {
        val request = request(declaredRotationDegrees = 90)
        val provenance = accepted(
            observation(request, codecMatch(request), decodedWidth = 240, decodedHeight = 320),
        )

        assertEquals(0, provenance.rotationAppliedDegrees)
        assertEquals(240, provenance.frozenWidth)
        assertEquals(320, provenance.frozenHeight)
        assertEquals(
            FrameOrientationCertainty.DIMENSIONS_INDICATE_DECODER_APPLIED_ROTATION,
            provenance.orientationCertainty,
        )
    }

    @Test
    fun `acepta 90 grados cuando la congelacion debe rotar la orientacion codificada`() {
        val request = request(declaredRotationDegrees = 90)
        val provenance = accepted(observation(request, codecMatch(request)))

        assertEquals(90, provenance.rotationAppliedDegrees)
        assertEquals(240, provenance.frozenWidth)
        assertEquals(320, provenance.frozenHeight)
        assertEquals(
            FrameOrientationCertainty.DIMENSIONS_INDICATE_ENCODED_ORIENTATION,
            provenance.orientationCertainty,
        )
    }

    @Test
    fun `una copia congelada fuera de presupuesto se bloquea antes de asignar`() {
        val request = request(encodedWidth = 4000, encodedHeight = 3000)
        rejected(
            observation(
                request = request,
                temporal = codecMatch(request),
                decodedWidth = 4000,
                decodedHeight = 3000,
                decodedAllocationBytes = 4000L * 3000L * 4L,
            ),
            FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
        )
    }

    @Test
    fun `un frame 4K cabe en el presupuesto de trabajo`() {
        val request = request(encodedWidth = 3840, encodedHeight = 2160)
        val provenance = accepted(
            observation(
                request = request,
                temporal = codecMatch(request),
                decodedWidth = 3840,
                decodedHeight = 2160,
                decodedAllocationBytes = 3840L * 2160L * 4L,
            ),
        )
        assertEquals(3840, provenance.frozenWidth)
        assertEquals(2160, provenance.frozenHeight)
    }

    @Test
    fun `el digest de contenido se conserva como identidad y no cambia la decision temporal`() {
        val request = request()
        val first = accepted(observation(request, codecMatch(request), contentDigest = 11L))
        val second = accepted(observation(request, codecMatch(request), contentDigest = 22L))

        assertEquals(11L, first.contentDigest)
        assertEquals(22L, second.contentDigest)
        assertNotEquals(first.contentDigest, second.contentDigest)
    }
}
