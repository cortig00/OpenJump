package com.openjump.app.angles

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Núcleo geométrico puro (sin dependencias Android) de la herramienta manual de ángulos 2D.
 *
 * Mide el ángulo interior en el vértice B entre los lados BA y BC, con coordenadas
 * en píxeles de presentación originales (post-rotación).
 *
 * Modelo numérico:
 *   - BA = A − B y BC = C − B;
 *   - se normalizan ambos vectores con [hypot] (con escalado interno, sin desbordar
 *     en los cuadrados);
 *   - ángulo = atan2(|BA × BC|, BA · BC) sobre los vectores unitarios.
 *
 * `atan2(|cross|, dot)` es estable donde `acos(dot)` pierde precisión: ángulos casi
 * nulos (lados casi alineados en el mismo sentido) y casi llanos (casi opuestos).
 * El resultado es el ángulo sin signo en [0, 180] grados y **nunca** contiene NaN:
 * las entradas inválidas devuelven [AngleMeasurement.Invalid] con un motivo tipado.
 *
 * Esta clase no redondea: el redondeo a un decimal pertenece a la presentación.
 * Tampoco conoce límites de frame ni aspecto de píxel: recibe puntos ya mapeados y
 * mide en píxeles, por lo que una escala no uniforme cambia el ángulo a propósito.
 */
object AngleGeometry {

    /**
     * Mide el ángulo de [markers] (B es el vértice).
     *
     * @return [AngleMeasurement.Measured] con los grados sin redondear, o
     *   [AngleMeasurement.Invalid] si el ángulo no está definido.
     */
    fun measure(markers: AngleMarkers): AngleMeasurement = measure(markers.a, markers.b, markers.c)

    /**
     * Mide el ángulo en el vértice [b] entre los lados BA y BC.
     *
     * Casos inválidos (nunca un 0° falso):
     *  - coordenadas NaN o infinitas → [AngleInvalidReason.NON_FINITE_POINT];
     *  - A == B o C == B (lado sin dirección) → [AngleInvalidReason.COINCIDENT_VERTEX];
     *  - diferencias o longitudes que desbordan Double → [AngleInvalidReason.VECTOR_OUT_OF_RANGE].
     *
     * A == C con B distinto es válido y mide exactamente 0°; tres puntos colineales
     * con B entre A y C miden 180°.
     */
    fun measure(a: AnglePoint, b: AnglePoint, c: AnglePoint): AngleMeasurement {
        if (!a.isFinite || !b.isFinite || !c.isFinite) {
            return AngleMeasurement.Invalid(AngleInvalidReason.NON_FINITE_POINT)
        }
        if (a == b || c == b) {
            return AngleMeasurement.Invalid(AngleInvalidReason.COINCIDENT_VERTEX)
        }

        val bax = a.x - b.x
        val bay = a.y - b.y
        val bcx = c.x - b.x
        val bcy = c.y - b.y
        if (!bax.isFinite() || !bay.isFinite() || !bcx.isFinite() || !bcy.isFinite()) {
            return AngleMeasurement.Invalid(AngleInvalidReason.VECTOR_OUT_OF_RANGE)
        }

        val lenBa = hypot(bax, bay)
        val lenBc = hypot(bcx, bcy)
        if (!lenBa.isFinite() || !lenBc.isFinite()) {
            // Normalizar con una longitud infinita produciría unitarios nulos y un 0° falso.
            return AngleMeasurement.Invalid(AngleInvalidReason.VECTOR_OUT_OF_RANGE)
        }
        if (lenBa == 0.0 || lenBc == 0.0) {
            // Cubre coincidencias que `==` no ve, como A = (-0.0, -0.0) con B = (0.0, 0.0).
            return AngleMeasurement.Invalid(AngleInvalidReason.COINCIDENT_VERTEX)
        }

        val ux = bax / lenBa
        val uy = bay / lenBa
        val vx = bcx / lenBc
        val vy = bcy / lenBc

        val dot = ux * vx + uy * vy
        val cross = ux * vy - uy * vx
        val radians = atan2(abs(cross), dot)

        return AngleMeasurement.Measured(degrees = radians * 180.0 / PI)
    }
}
