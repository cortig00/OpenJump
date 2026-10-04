package com.openjump.app.math

/**
 * Lógica matemática de medición de salto vertical (CMJ).
 *
 * Clase pura de Kotlin, SIN dependencias de Android, para poder testearla fácilmente.
 *
 * Modelo físico (estándar en herramientas como My Jump Lab):
 *   - g = 9.80665 m/s² (gravedad estándar)
 *   - Tiempo de vuelo (s) = aterrizaje − despegue (timestamps en µs convertidos a s)
 *   - Altura = g · flightTime² / 8
 *   - Velocidad de despegue = g · flightTime / 2
 *   - Time to take-off = despegue − inicio
 *   - RSI-mod = altura(m) / timeToTakeOff(s)
 *
 * Todos los timestamps se expresan en microsegundos (µs), alineados con los
 * presentation timestamps reales que entrega Android/MediaExtractor.
 */
object JumpMath {

    const val GRAVITY: Double = 9.80665

    /** Umbral mínimo de tiempo de vuelo (µs). Por debajo se considera un error de marcado. */
    const val MIN_FLIGHT_TIME_US: Long = 50_000L // 50 ms

    /** Resultados comunes derivados únicamente del intervalo de vuelo. */
    data class FlightResults(
        val heightM: Double,
        val heightCm: Double,
        val flightTimeS: Double,
        val flightTimeMs: Double,
        val takeoffVelocityMS: Double,
    )

    /** Resultados calculados para un salto con inicio de movimiento. */
    data class Results(
        val heightM: Double,
        val heightCm: Double,
        val flightTimeS: Double,
        val flightTimeMs: Double,
        val takeoffVelocityMS: Double,
        val timeToTakeoffMs: Double,
        val timeToTakeoffS: Double,
        val rsiMod: Double,
    )

    /** Error de validación de marcadores. */
    class InvalidTimes(message: String) : Exception(message)

    /** Convierte microsegundos a segundos. */
    fun usToSeconds(us: Long): Double = us / 1_000_000.0

    /** Calcula altura, vuelo y velocidad usando solo PTS reales. */
    fun computeFlight(takeoffUs: Long?, landingUs: Long?): FlightResults {
        if (takeoffUs == null || landingUs == null) {
            throw InvalidTimes("Faltan marcadores: debes marcar despegue y aterrizaje.")
        }
        if (landingUs <= takeoffUs) {
            throw InvalidTimes("El aterrizaje debe ser posterior al despegue.")
        }
        val flightTimeUs = landingUs - takeoffUs
        if (flightTimeUs < MIN_FLIGHT_TIME_US) {
            throw InvalidTimes(
                "Tiempo de vuelo demasiado pequeño (${flightTimeUs} µs). Revisa los marcadores."
            )
        }
        val flightTimeS = usToSeconds(flightTimeUs)
        val heightM = GRAVITY * flightTimeS * flightTimeS / 8.0
        return FlightResults(
            heightM = heightM,
            heightCm = heightM * 100.0,
            flightTimeS = flightTimeS,
            flightTimeMs = flightTimeUs / 1000.0,
            takeoffVelocityMS = GRAVITY * flightTimeS / 2.0,
        )
    }

    /** Returns a positive contact interval in seconds from two real PTS values. */
    fun contactTimeSeconds(contactUs: Long?, takeoffUs: Long?): Double {
        if (contactUs == null || takeoffUs == null) {
            throw InvalidTimes("Faltan marcadores: debes marcar contacto y despegue.")
        }
        if (takeoffUs <= contactUs) {
            throw InvalidTimes("El despegue debe ser posterior al contacto inicial.")
        }
        return usToSeconds(takeoffUs - contactUs)
    }

    /**
     * Calcula las métricas del salto a partir de los tres marcadores (µs).
     *
     * Valida:
     *  - que existan los tres marcadores;
     *  - despegue posterior al inicio;
     *  - aterrizaje posterior al despegue;
     *  - tiempo de vuelo no absurdamente pequeño.
     *
     * @throws InvalidTimes si algún marcador es inválido.
     */
    fun compute(movementStartUs: Long?, takeoffUs: Long?, landingUs: Long?): Results {
        if (movementStartUs == null || takeoffUs == null || landingUs == null) {
            throw InvalidTimes("Faltan marcadores: debes marcar inicio, despegue y aterrizaje.")
        }
        if (takeoffUs <= movementStartUs) {
            throw InvalidTimes("El despegue debe ser posterior al inicio del movimiento.")
        }
        val flight = computeFlight(takeoffUs, landingUs)

        val timeToTakeoffUs = takeoffUs - movementStartUs
        val timeToTakeoffS = usToSeconds(timeToTakeoffUs)
        if (timeToTakeoffS <= 0.0) {
            throw InvalidTimes("El time to take-off no puede ser cero ni negativo.")
        }

        val rsiMod = flight.heightM / timeToTakeoffS

        return Results(
            heightM = flight.heightM,
            heightCm = flight.heightCm,
            flightTimeS = flight.flightTimeS,
            flightTimeMs = flight.flightTimeMs,
            takeoffVelocityMS = flight.takeoffVelocityMS,
            timeToTakeoffMs = timeToTakeoffUs / 1000.0,
            timeToTakeoffS = timeToTakeoffS,
            rsiMod = rsiMod,
        )
    }
}
