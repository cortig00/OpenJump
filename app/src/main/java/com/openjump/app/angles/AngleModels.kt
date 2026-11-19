package com.openjump.app.angles

/**
 * Modelos puros (sin dependencias Android) de la herramienta manual de ángulos 2D.
 *
 * Un ángulo se define con tres marcas manuales A, B y C sobre la imagen:
 * B es el vértice y los lados son los segmentos BA y BC.
 *
 * Las coordenadas se expresan en **píxeles de presentación originales**
 * (post-rotación), en Double para no perder precisión antes del cálculo.
 * Esta capa no valida límites de frame: los taps de la UI ya están dentro de la
 * imagen y el recorte/escala pertenece al mapper de presentación.
 */
data class AnglePoint(val x: Double, val y: Double) {

    /** `false` si alguna coordenada es NaN o infinita. */
    val isFinite: Boolean
        get() = x.isFinite() && y.isFinite()
}

/** Las tres marcas manuales que definen el ángulo; [b] es el vértice. */
data class AngleMarkers(
    val a: AnglePoint,
    val b: AnglePoint,
    val c: AnglePoint,
)

/** Motivos tipados por los que no existe un ángulo medible. */
enum class AngleInvalidReason {
    /** Alguna coordenada de A, B o C no es un Double finito. */
    NON_FINITE_POINT,

    /** A == B o C == B: el vértice coincide con un extremo y el lado no tiene dirección. */
    COINCIDENT_VERTEX,

    /** Los vectores BA/BC son finitos, pero su longitud desborda el rango Double. */
    VECTOR_OUT_OF_RANGE,
}

/**
 * Resultado tipado de la medición, para que la UI pueda distinguir "no medible"
 * de un 0° real. Nunca contiene NaN.
 */
sealed interface AngleMeasurement {

    /**
     * Ángulo interior entre los lados BA y BC, **sin redondear**: el redondeo a
     * un decimal es responsabilidad de la capa de presentación.
     *
     * @property degrees rango [0, 180].
     */
    data class Measured(val degrees: Double) : AngleMeasurement

    /** No hay ángulo medible; [reason] indica por qué. */
    data class Invalid(val reason: AngleInvalidReason) : AngleMeasurement
}
