package com.openjump.app.angles

import com.openjump.app.tracking.VideoPresentationGeometry

/**
 * Cursor accesible de los puntos A/B/C del editor de ángulos.
 *
 * Es la alternativa sin tocar el vídeo para TalkBack/Switch Access: un punto pendiente que se mueve
 * con pasos discretos y se confirma explícitamente. Trabaja en **el mismo espacio de coordenadas que
 * los gestos** (píxeles de presentación post-rotación, ver [AnglePoint]) para que un punto colocado
 * con los controles accesibles y otro colocado con el dedo pasen por el mismo mapper y las mismas
 * guardas del editor; no hay una geometría paralela.
 *
 * El paso es proporcional al lado menor de la imagen (2 %, igual que el selector accesible) con un
 * mínimo en píxeles para vídeos pequeños: así el ajuste fino es predecible en cualquier resolución.
 * Los límites son el último píxel válido (`ancho - 1`, `alto - 1`), no el tamaño: un punto en
 * `width` quedaría fuera de la imagen.
 *
 * Nunca devuelve coordenadas no finitas: si la entrada no es finita se cae al centro de la imagen.
 * Este objeto no decide si se puede confirmar (eso es de [AngleEditorState] y del ViewModel).
 */
internal object AngleAccessibleCursor {

    /** Fracción del lado menor de la imagen que avanza cada paso. */
    const val STEP_FRACTION: Double = 0.02

    /** Paso mínimo en píxeles de imagen, para que un vídeo diminuto siga siendo ajustable. */
    const val MIN_STEP: Double = 4.0

    /** Longitud de un paso en píxeles de imagen para [geometry]. */
    fun step(geometry: VideoPresentationGeometry): Double =
        (minOf(geometry.width, geometry.height) * STEP_FRACTION).coerceAtLeast(MIN_STEP)

    /** Punto de partida neutro: el centro de la imagen. */
    fun centered(geometry: VideoPresentationGeometry): AnglePoint =
        AnglePoint(geometry.width / 2.0, geometry.height / 2.0)

    /** Encaja [point] dentro de la imagen; una entrada no finita cae al centro. */
    fun clamp(geometry: VideoPresentationGeometry, point: AnglePoint): AnglePoint {
        if (!point.isFinite) return centered(geometry)
        return AnglePoint(
            point.x.coerceIn(0.0, lastPixel(geometry.width)),
            point.y.coerceIn(0.0, lastPixel(geometry.height)),
        )
    }

    /** Mueve [from] por ([dx], [dy]) píxeles y encaja el resultado en la imagen. */
    fun moved(
        geometry: VideoPresentationGeometry,
        from: AnglePoint,
        dx: Double,
        dy: Double,
    ): AnglePoint = clamp(geometry, AnglePoint(from.x + dx, from.y + dy))

    private fun lastPixel(size: Int): Double = (size - 1).coerceAtLeast(0).toDouble()
}
