package com.openjump.fvp.lab

/**
 * Gate de calidad FPS para series F-V de laboratorio (hipotesis explicita).
 *
 * - `ALLOW` (>= 120 fps): requisito por defecto; error de ±1 frame ~= ±1.0 cm en h=0.30 m.
 * - `CONDITIONAL` (>= 60 fps): utilizable con bandas publicadas; ±1 frame ~= ±2.0 cm.
 * - `BLOCKED` (< 60 fps): serie no apta para F-V (±1 frame a 30 fps ~= ±4 cm); en UI futura
 *   seria error bloqueante. El laboratorio solo la etiqueta (no lanza).
 *
 * Umbrales fijados por el contrato de tarea; magnitudes verificadas en tests sobre h=0.30 m
 * sintetico (ver `LabBands.heightDeltaCm` y `FvpSensitivityTest` de `:fvp`).
 */
enum class FpsGateDecision {
    ALLOW,
    CONDITIONAL,
    BLOCKED,
}

object FpsGate {
    const val ALLOW_FPS = 120.0
    const val CONDITIONAL_FPS = 60.0

    /** Evalua un fps efectivo (> 0, finito). Nulo no se evalua aqui (ver [evaluateOrNull]). */
    fun evaluate(fps: Double): FpsGateDecision {
        if (!fps.isFinite() || fps <= 0.0) {
            throw IllegalArgumentException("fps must be finite and > 0, was $fps")
        }
        return when {
            fps >= ALLOW_FPS -> FpsGateDecision.ALLOW
            fps >= CONDITIONAL_FPS -> FpsGateDecision.CONDITIONAL
            else -> FpsGateDecision.BLOCKED
        }
    }

    /** Variante nullable: fps nulo/desconocido => null (UNKNOWN, documentar en informe). */
    fun evaluateOrNull(fps: Double?): FpsGateDecision? {
        if (fps == null) return null
        return evaluate(fps)
    }
}
