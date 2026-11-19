package com.openjump.app.video.angles

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pruebas JVM del cálculo de orientación. No decodifican vídeo: fijan qué se puede **probar** con
 * metadatos y qué debe bloquearse por ambigüedad en lugar de adivinar.
 */
class FrameOrientationPlannerTest {

    @Test
    fun `sin rotacion declarada y dimensiones coincidentes la orientacion es la codificada`() {
        val plan = FrameOrientationPlanner.plan(
            encodedWidth = 320,
            encodedHeight = 240,
            declaredRotationDegrees = 0,
            decodedWidth = 320,
            decodedHeight = 240,
        )

        assertEquals(
            FrameOrientationPlan.Determined(
                rotationToApplyDegrees = 0,
                orientedWidth = 320,
                orientedHeight = 240,
                certainty = FrameOrientationCertainty.DECLARED_NO_ROTATION,
            ),
            plan,
        )
    }

    @Test
    fun `sin rotacion declarada unas dimensiones distintas no se aceptan como orientacion conocida`() {
        val plan = FrameOrientationPlanner.plan(
            encodedWidth = 320,
            encodedHeight = 240,
            declaredRotationDegrees = 0,
            decodedWidth = 240,
            decodedHeight = 320,
        )

        assertEquals(
            FrameOrientationPlan.Undetermined(
                FrameOrientationPlan.UndeterminedReason.DECODED_DIMENSIONS_DO_NOT_MATCH_DECLARED_ROTATION,
            ),
            plan,
        )
    }

    @Test
    fun `90 grados con dimensiones presentadas demuestra que el decoder ya roto`() {
        val plan = FrameOrientationPlanner.plan(
            encodedWidth = 320,
            encodedHeight = 240,
            declaredRotationDegrees = 90,
            decodedWidth = 240,
            decodedHeight = 320,
        )

        assertEquals(
            FrameOrientationPlan.Determined(
                rotationToApplyDegrees = 0,
                orientedWidth = 240,
                orientedHeight = 320,
                certainty = FrameOrientationCertainty.DIMENSIONS_INDICATE_DECODER_APPLIED_ROTATION,
            ),
            plan,
        )
    }

    @Test
    fun `270 grados con dimensiones codificadas exige que la congelacion aplique la rotacion`() {
        val plan = FrameOrientationPlanner.plan(
            encodedWidth = 320,
            encodedHeight = 240,
            declaredRotationDegrees = 270,
            decodedWidth = 320,
            decodedHeight = 240,
        )

        assertEquals(
            FrameOrientationPlan.Determined(
                rotationToApplyDegrees = 270,
                orientedWidth = 240,
                orientedHeight = 320,
                certainty = FrameOrientationCertainty.DIMENSIONS_INDICATE_ENCODED_ORIENTATION,
            ),
            plan,
        )
    }

    @Test
    fun `rotacion 180 no puede demostrarse con dimensiones y se bloquea`() {
        val plan = FrameOrientationPlanner.plan(
            encodedWidth = 320,
            encodedHeight = 240,
            declaredRotationDegrees = 180,
            decodedWidth = 320,
            decodedHeight = 240,
        )

        assertEquals(
            FrameOrientationPlan.Undetermined(
                FrameOrientationPlan.UndeterminedReason.ROTATION_180_CANNOT_BE_PROVEN_FROM_METADATA,
            ),
            plan,
        )
    }

    @Test
    fun `frame cuadrado con 90 grados es ambiguo aunque el decoder entregue sus dimensiones`() {
        val plan = FrameOrientationPlanner.plan(
            encodedWidth = 240,
            encodedHeight = 240,
            declaredRotationDegrees = 90,
            decodedWidth = 240,
            decodedHeight = 240,
        )

        assertEquals(
            FrameOrientationPlan.Undetermined(
                FrameOrientationPlan.UndeterminedReason.SQUARE_ROTATED_FRAME_DIMENSIONS_ARE_AMBIGUOUS,
            ),
            plan,
        )
    }

    @Test
    fun `unas dimensiones decodificadas ajenas a las dos orientaciones se rechazan`() {
        val plan = FrameOrientationPlanner.plan(
            encodedWidth = 320,
            encodedHeight = 240,
            declaredRotationDegrees = 90,
            decodedWidth = 160,
            decodedHeight = 120,
        )

        assertEquals(
            FrameOrientationPlan.Undetermined(
                FrameOrientationPlan.UndeterminedReason.DECODED_DIMENSIONS_DO_NOT_MATCH_DECLARED_ROTATION,
            ),
            plan,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `una rotacion no soportada no se acepta`() {
        FrameOrientationPlanner.plan(
            encodedWidth = 320,
            encodedHeight = 240,
            declaredRotationDegrees = 45,
            decodedWidth = 320,
            decodedHeight = 240,
        )
    }
}
