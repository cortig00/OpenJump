package com.openjump.app.angles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión del núcleo geométrico puro de la herramienta Ángulos.
 *
 * Convención: B es el vértice y el ángulo se mide entre los lados BA y BC.
 */
class AngleGeometryTest {

    private fun p(x: Double, y: Double) = AnglePoint(x, y)

    private fun measured(a: AnglePoint, b: AnglePoint, c: AnglePoint): AngleMeasurement.Measured =
        when (val result = AngleGeometry.measure(a, b, c)) {
            is AngleMeasurement.Measured -> result
            is AngleMeasurement.Invalid ->
                throw AssertionError("esperaba un ángulo medible, fue $result")
        }

    private fun invalidReason(a: AnglePoint, b: AnglePoint, c: AnglePoint): AngleInvalidReason =
        when (val result = AngleGeometry.measure(a, b, c)) {
            is AngleMeasurement.Invalid -> result.reason
            is AngleMeasurement.Measured ->
                throw AssertionError("esperaba un resultado inválido, fue $result")
        }

    // ---------- Ángulos de referencia ----------

    @Test
    fun `angulo cero con A y C en el mismo lado del vertice`() {
        val degrees = measured(p(10.0, 0.0), p(0.0, 0.0), p(5.0, 0.0)).degrees
        assertEquals(0.0, degrees, 1e-12)
    }

    @Test
    fun `angulo de 45 grados`() {
        val degrees = measured(p(1.0, 0.0), p(0.0, 0.0), p(1.0, 1.0)).degrees
        assertEquals(45.0, degrees, 1e-12)
    }

    @Test
    fun `angulo recto de 90 grados en cualquier orientacion`() {
        assertEquals(90.0, measured(p(1.0, 0.0), p(0.0, 0.0), p(0.0, 1.0)).degrees, 1e-12)
        assertEquals(90.0, measured(p(0.0, -3.0), p(0.0, 0.0), p(3.0, 0.0)).degrees, 1e-12)
        assertEquals(90.0, measured(p(4.0, 5.0), p(4.0, 1.0), p(8.0, 1.0)).degrees, 1e-12)
    }

    @Test
    fun `angulo de 135 grados`() {
        val degrees = measured(p(1.0, 0.0), p(0.0, 0.0), p(-1.0, 1.0)).degrees
        assertEquals(135.0, degrees, 1e-12)
    }

    @Test
    fun `angulo llano de 180 grados con puntos colineales opuestos`() {
        val degrees = measured(p(1.0, 0.0), p(0.0, 0.0), p(-1.0, 0.0)).degrees
        assertEquals(180.0, degrees, 0.0)
    }

    @Test
    fun `el nucleo no redondea a un decimal`() {
        // atan(0.5) = 26.56505117707799...: un 26.6 delataria redondeo prematuro.
        val degrees = measured(p(1.0, 0.0), p(0.0, 0.0), p(1.0, 0.5)).degrees
        assertEquals(26.56505117707799, degrees, 1e-12)
        // atan(0.25) = 14.036243467926477...
        val quarter = measured(p(1.0, 0.0), p(0.0, 0.0), p(1.0, 0.25)).degrees
        assertEquals(14.036243467926477, quarter, 1e-12)
    }

    @Test
    fun `A igual a C con vertice distinto es un angulo valido de cero grados`() {
        val markers = AngleMarkers(p(5.0, 5.0), p(1.0, 1.0), p(5.0, 5.0))
        val result = AngleGeometry.measure(markers)
        assertTrue(result is AngleMeasurement.Measured)
        assertEquals(0.0, (result as AngleMeasurement.Measured).degrees, 0.0)
    }

    // ---------- Invalidaciones tipadas ----------

    @Test
    fun `vertice coincidente con A es invalido y nunca cero grados`() {
        assertEquals(
            AngleInvalidReason.COINCIDENT_VERTEX,
            invalidReason(p(2.0, 3.0), p(2.0, 3.0), p(9.0, 9.0)),
        )
    }

    @Test
    fun `vertice coincidente con C es invalido y nunca cero grados`() {
        assertEquals(
            AngleInvalidReason.COINCIDENT_VERTEX,
            invalidReason(p(9.0, 9.0), p(2.0, 3.0), p(2.0, 3.0)),
        )
    }

    @Test
    fun `coincidencia con cero negativo tambien es vertice coincidente`() {
        // Double.equals distingue -0.0 de 0.0; el vector nulo debe seguir siendo inválido.
        assertEquals(
            AngleInvalidReason.COINCIDENT_VERTEX,
            invalidReason(p(-0.0, -0.0), p(0.0, 0.0), p(4.0, 4.0)),
        )
    }

    @Test
    fun `coordenadas no finitas son invalidas y nunca NaN`() {
        val nan = Double.NaN
        val posInf = Double.POSITIVE_INFINITY
        val negInf = Double.NEGATIVE_INFINITY
        val cases = listOf(
            invalidReason(p(nan, 0.0), p(0.0, 0.0), p(1.0, 1.0)),
            invalidReason(p(0.0, nan), p(0.0, 0.0), p(1.0, 1.0)),
            invalidReason(p(1.0, 0.0), p(posInf, 0.0), p(1.0, 1.0)),
            invalidReason(p(1.0, 0.0), p(0.0, negInf), p(1.0, 1.0)),
            invalidReason(p(1.0, 0.0), p(0.0, 0.0), p(nan, 1.0)),
            invalidReason(p(1.0, 0.0), p(0.0, 0.0), p(1.0, posInf)),
        )
        assertTrue(cases.all { it == AngleInvalidReason.NON_FINITE_POINT })
    }

    @Test
    fun `diferencia de coordenadas que desborda es invalida`() {
        // 1.7e308 - (-1.7e308) = 3.4e308: desborda a +Inf.
        assertEquals(
            AngleInvalidReason.VECTOR_OUT_OF_RANGE,
            invalidReason(p(1.7e308, 0.0), p(-1.7e308, 0.0), p(1.0, 1.0)),
        )
    }

    @Test
    fun `longitud que desborda es invalida y no produce cero falso`() {
        // Componentes finitas, pero hypot(1.7e308, 1.7e308) excede el rango Double:
        // normalizar con una longitud infinita daría un 0° falso.
        assertEquals(
            AngleInvalidReason.VECTOR_OUT_OF_RANGE,
            invalidReason(p(1.7e308, 1.7e308), p(0.0, 0.0), p(1.0, 0.0)),
        )
    }

    // ---------- Casi colineales ----------

    @Test
    fun `casi llano conserva la desviacion y no se redondea a 180`() {
        val degrees = measured(p(1.0, 0.0), p(0.0, 0.0), p(-1.0, 1e-9)).degrees
        assertTrue("esperaba < 180, fue $degrees", degrees < 180.0)
        assertEquals(180.0, degrees, 1e-6)
    }

    @Test
    fun `casi cero conserva la desviacion y no se redondea a cero`() {
        val degrees = measured(p(1.0, 0.0), p(0.0, 0.0), p(1.0, 1e-12)).degrees
        assertTrue("esperaba > 0, fue $degrees", degrees > 0.0)
        assertEquals(0.0, degrees, 1e-9)
        assertTrue(degrees.isFinite())
    }

    // ---------- Magnitudes extremas ----------

    @Test
    fun `magnitudes minusculas siguen dando el angulo correcto`() {
        val degrees = measured(p(1e-300, 0.0), p(0.0, 0.0), p(0.0, 1e-300)).degrees
        assertEquals(90.0, degrees, 1e-12)
    }

    @Test
    fun `magnitudes enormes pero representables siguen dando el angulo correcto`() {
        val degrees = measured(p(1e300, 0.0), p(0.0, 0.0), p(0.0, 1e300)).degrees
        assertEquals(90.0, degrees, 1e-12)
    }

    // ---------- Invariancias ----------

    @Test
    fun `invariancia ante traslacion`() {
        val tx = 12345.5
        val ty = -987.25
        val base = measured(p(1.0, 0.0), p(0.0, 0.0), p(1.0, 1.0)).degrees
        val translated = measured(p(1.0 + tx, ty), p(tx, ty), p(1.0 + tx, 1.0 + ty)).degrees
        assertEquals(45.0, base, 1e-12)
        assertEquals(base, translated, 1e-9)
    }

    @Test
    fun `invariancia ante escala uniforme`() {
        val base = measured(p(1.0, 0.0), p(0.0, 0.0), p(1.0, 1.0)).degrees
        val ups = measured(p(2.5, 0.0), p(0.0, 0.0), p(2.5, 2.5)).degrees
        val downs = measured(p(0.001, 0.0), p(0.0, 0.0), p(0.001, 0.001)).degrees
        assertEquals(45.0, base, 1e-12)
        assertEquals(base, ups, 1e-12)
        assertEquals(base, downs, 1e-12)
    }

    @Test
    fun `escala no uniforme cambia el angulo y no se normaliza por aspecto`() {
        // El núcleo mide en píxeles: escalar x e y de forma distinta deforma el ángulo.
        val degrees = measured(p(2.0, 0.0), p(0.0, 0.0), p(2.0, 0.5)).degrees
        assertEquals(14.036243467926477, degrees, 1e-12)
        assertNotEquals(45.0, degrees, 1.0)
    }

    @Test
    fun `intercambiar A y C no cambia el angulo`() {
        val straight = measured(p(1.0, 0.0), p(0.0, 0.0), p(-1.0, 1.0)).degrees
        val swapped = measured(p(-1.0, 1.0), p(0.0, 0.0), p(1.0, 0.0)).degrees
        assertEquals(135.0, straight, 1e-12)
        assertEquals(straight, swapped, 0.0)
    }

    // ---------- Modelo ----------

    @Test
    fun `el modelo separa marcas de angulos distintos y es reutilizable`() {
        val right = AngleMarkers(p(1.0, 0.0), p(0.0, 0.0), p(0.0, 1.0))
        val acute = AngleMarkers(p(1.0, 0.0), p(0.0, 0.0), p(1.0, 1.0))

        assertNotEquals(right, acute)
        val rightDegrees = measured(right.a, right.b, right.c).degrees
        val acuteDegrees = measured(acute.a, acute.b, acute.c).degrees
        assertEquals(90.0, rightDegrees, 1e-12)
        assertEquals(45.0, acuteDegrees, 1e-12)
        assertNotEquals(rightDegrees, acuteDegrees, 1.0)

        // Stateless: remidir el primero devuelve lo mismo tras medir el segundo.
        assertEquals(rightDegrees, measured(right.a, right.b, right.c).degrees, 0.0)
    }

    @Test
    fun `sobrecarga de AngleMarkers equivale a la de tres puntos`() {
        val markers = AngleMarkers(p(-1.0, 1.0), p(0.0, 0.0), p(1.0, 0.0))
        val byMarkers = AngleGeometry.measure(markers)
        val byPoints = AngleGeometry.measure(markers.a, markers.b, markers.c)
        assertEquals(byPoints, byMarkers)
    }

    @Test
    fun `un angulo medido siempre es finito y esta en rango`() {
        val samples = listOf(
            measured(p(1.0, 0.0), p(0.0, 0.0), p(1.0, 0.0)).degrees,
            measured(p(1.0, 0.0), p(0.0, 0.0), p(-1.0, 0.0)).degrees,
            measured(p(3.0, 4.0), p(-7.0, 2.0), p(0.5, -9.0)).degrees,
        )
        assertTrue(samples.all { it.isFinite() && it >= 0.0 && it <= 180.0 })
    }
}
