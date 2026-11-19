package com.openjump.app.video.angles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM de la evidencia de orientación del productor y de los casos que las dimensiones **no**
 * demuestran: rotación 180 (no cambia el tamaño) y 90/270 sobre un frame cuadrado.
 *
 * Regla que fijan, y que es el punto de la revisión: no se infiere la orientación desde el tamaño. 180
 * y cuadrado 90/270 solo se resuelven si el productor afirma el hecho de configuración
 * [FrameOrientationEvidence.BufferModeWithoutSurface] (decodificación en modo buffer, sin `Surface`);
 * sin esa evidencia siguen bloqueados y la política rechaza incluso un plan fabricado.
 *
 * Lo que estas pruebas **no** acreditan: que un dispositivo real entregue los buffers en la
 * orientación codificada. Es el contrato documentado del framework para `MediaFormat.KEY_ROTATION`
 * (indicación de decodificadores con superficie) y aquí no se ejecuta AVD ni hardware; la verificación
 * empírica queda en la prueba instrumentada de `CodecFrozenFrameProducer`.
 */
class FrameOrientationEvidenceTest {

    private val asBufferMode = FrameOrientationEvidence.BufferModeWithoutSurface

    private fun plan(
        encodedWidth: Int = 320,
        encodedHeight: Int = 240,
        rotation: Int,
        decodedWidth: Int = encodedWidth,
        decodedHeight: Int = encodedHeight,
        evidence: FrameOrientationEvidence? = asBufferMode,
    ) = FrameOrientationPlanner.plan(
        encodedWidth = encodedWidth,
        encodedHeight = encodedHeight,
        declaredRotationDegrees = rotation,
        decodedWidth = decodedWidth,
        decodedHeight = decodedHeight,
        evidence = evidence,
    )

    @Test
    fun `con evidencia de buffer las cuatro rotaciones quedan determinadas y con dimensiones presentadas`() {
        // 0 y 180 no intercambian ejes; 90 y 270 sí (frame asimétrico 320x240 -> 240x320). La rotación 0
        // conserva la certeza de "sin rotación declarada": la evidencia no aporta nada nuevo ahí.
        val expected = listOf(
            Triple(0, Triple(0, 320, 240), FrameOrientationCertainty.DECLARED_NO_ROTATION),
            Triple(90, Triple(90, 240, 320), FrameOrientationCertainty.PRODUCER_BUFFER_MODE_NO_SURFACE_TRANSFORM),
            Triple(180, Triple(180, 320, 240), FrameOrientationCertainty.PRODUCER_BUFFER_MODE_NO_SURFACE_TRANSFORM),
            Triple(270, Triple(270, 240, 320), FrameOrientationCertainty.PRODUCER_BUFFER_MODE_NO_SURFACE_TRANSFORM),
        )
        for ((rotation, dims, certainty) in expected) {
            val (applied, width, height) = dims
            assertEquals(
                "Rotación declarada $rotation",
                FrameOrientationPlan.Determined(
                    rotationToApplyDegrees = applied,
                    orientedWidth = width,
                    orientedHeight = height,
                    certainty = certainty,
                ),
                plan(rotation = rotation),
            )
        }
    }

    @Test
    fun `con evidencia un frame cuadrado distingue 90 180 y 270 por la rotacion declarada`() {
        for (rotation in listOf(90, 180, 270)) {
            assertEquals(
                "Cuadrado con rotación $rotation",
                FrameOrientationPlan.Determined(
                    rotationToApplyDegrees = rotation,
                    orientedWidth = 240,
                    orientedHeight = 240,
                    certainty = FrameOrientationCertainty.PRODUCER_BUFFER_MODE_NO_SURFACE_TRANSFORM,
                ),
                plan(encodedWidth = 240, encodedHeight = 240, rotation = rotation),
            )
        }
    }

    @Test
    fun `sin evidencia 180 y cuadrado 90 270 siguen bloqueados`() {
        assertEquals(
            FrameOrientationPlan.Undetermined(
                FrameOrientationPlan.UndeterminedReason.ROTATION_180_CANNOT_BE_PROVEN_FROM_METADATA,
            ),
            plan(rotation = 180, evidence = null),
        )
        assertEquals(
            FrameOrientationPlan.Undetermined(
                FrameOrientationPlan.UndeterminedReason.SQUARE_ROTATED_FRAME_DIMENSIONS_ARE_AMBIGUOUS,
            ),
            plan(encodedWidth = 240, encodedHeight = 240, rotation = 90, evidence = null),
        )
    }

    @Test
    fun `si las dimensiones decodificadas contradicen la evidencia se bloquea en lugar de reinterpretarla`() {
        // 240x320 significa que los ejes ya están intercambiados: la evidencia "buffer sin superficie"
        // y los píxeles se contradicen para 320x240 codificado, así que no se elige una de las dos.
        assertEquals(
            FrameOrientationPlan.Undetermined(
                FrameOrientationPlan.UndeterminedReason.DECODED_DIMENSIONS_DO_NOT_MATCH_DECLARED_ROTATION,
            ),
            plan(rotation = 90, decodedWidth = 240, decodedHeight = 320),
        )
        assertEquals(
            FrameOrientationPlan.Undetermined(
                FrameOrientationPlan.UndeterminedReason.DECODED_DIMENSIONS_DO_NOT_MATCH_DECLARED_ROTATION,
            ),
            plan(rotation = 180, decodedWidth = 320, decodedHeight = 238),
        )
    }

    private fun request(rotation: Int) = FrameFreezeRequest(
        sourceIdentity = FrameSourceIdentity("snapshot:rotated"),
        frameIndex = 2,
        assignedPtsUs = 80_000L,
        indexFrameCount = 24,
        encodedWidth = 320,
        encodedHeight = 240,
        declaredRotationDegrees = rotation,
    )

    private fun observation(
        request: FrameFreezeRequest,
        orientation: FrameOrientationPlan.Determined,
        evidence: FrameOrientationEvidence?,
    ) = FrameFreezeObservation(
        request = request,
        temporal = FrameTemporalEvidence.fromCodecOutputBuffer(
            generation = CodecOutputGeneration.mint(outputBufferIndex = 0),
            sourceIdentity = request.sourceIdentity,
            frameIndex = request.frameIndex,
            assignedPtsUs = request.assignedPtsUs,
            codecOutputPtsUs = request.assignedPtsUs,
        ),
        orientation = orientation,
        orientationEvidence = evidence,
        decodedWidth = request.encodedWidth,
        decodedHeight = request.encodedHeight,
        decodedAllocationBytes = request.encodedWidth.toLong() * request.encodedHeight * 4L,
        contentDigest = 0x0BAD_5EEDL,
    )

    @Test
    fun `la politica acepta 180 con evidencia del productor y registra la rotacion aplicada`() {
        val request = request(rotation = 180)
        val orientation = plan(rotation = 180) as FrameOrientationPlan.Determined

        val decision = FrameFreezePolicy().evaluate(observation(request, orientation, asBufferMode))

        assertTrue("180 con evidencia debe aceptarse: $decision", decision is FrameFreezeDecision.Accepted)
        val provenance = (decision as FrameFreezeDecision.Accepted).provenance
        assertEquals(180, provenance.rotationAppliedDegrees)
        assertEquals(320, provenance.frozenWidth)
        assertEquals(240, provenance.frozenHeight)
        assertEquals(FrameOrientationCertainty.PRODUCER_BUFFER_MODE_NO_SURFACE_TRANSFORM, provenance.orientationCertainty)
    }

    @Test
    fun `la politica rechaza un plan de 180 sin evidencia aunque este bien formado`() {
        val request = request(rotation = 180)
        // Plan fabricado: mismas dimensiones y la certeza "buena", pero sin la evidencia del productor.
        val forged = FrameOrientationPlan.Determined(
            rotationToApplyDegrees = 180,
            orientedWidth = 320,
            orientedHeight = 240,
            certainty = FrameOrientationCertainty.PRODUCER_BUFFER_MODE_NO_SURFACE_TRANSFORM,
        )

        val decision = FrameFreezePolicy().evaluate(observation(request, forged, evidence = null))

        assertTrue("Sin evidencia, 180 no puede aceptarse: $decision", decision is FrameFreezeDecision.Rejected)
        assertEquals(
            FrameFreezeRejectionReason.ORIENTATION_UNDETERMINED,
            (decision as FrameFreezeDecision.Rejected).reason,
        )
    }
}
