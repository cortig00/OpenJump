package com.openjump.apple

import com.openjump.app.math.JumpMath

/** Narrow Objective-C/Swift-friendly facade over the repository's real JumpMath implementation. */
class JumpCore {
    @Throws(JumpMath.InvalidTimes::class)
    fun calculate(startUs: Long, takeoffUs: Long, landingUs: Long): JumpMetrics {
        val result = JumpMath.compute(startUs, takeoffUs, landingUs)
        return JumpMetrics(
            heightCm = result.heightCm,
            takeoffVelocityMs = result.takeoffVelocityMS,
            rsiMod = result.rsiMod,
            flightTimeMs = result.flightTimeMs,
        )
    }

    /** Calculates imported-video metrics using only the repository's shared temporal math. */
    @Throws(InvalidTemporalJump::class)
    fun calculateTemporal(
        protocolKey: String,
        firstEventUs: Long,
        takeoffUs: Long,
        landingUs: Long,
    ): TemporalJumpMetrics {
        if (takeoffUs < 0 || landingUs < 0 || (protocolKey != "SJ" && firstEventUs < 0)) {
            throw InvalidTemporalJump("Temporal jump timestamps must be nonnegative.")
        }
        return try {
            when (protocolKey) {
                "CMJ", "ABALAKOV", "UNILATERAL" -> {
                    val result = JumpMath.compute(firstEventUs, takeoffUs, landingUs)
                    temporalMetrics(
                        heightCm = result.heightCm,
                        flightTimeMs = result.flightTimeMs,
                        takeoffVelocityMps = result.takeoffVelocityMS,
                        timeToTakeoffMs = result.timeToTakeoffMs,
                        rsiMod = result.rsiMod,
                    )
                }
                "SJ" -> {
                    // SJ has no movement-start event; firstEventUs is intentionally unused.
                    val result = JumpMath.computeFlight(takeoffUs, landingUs)
                    temporalMetrics(
                        heightCm = result.heightCm,
                        flightTimeMs = result.flightTimeMs,
                        takeoffVelocityMps = result.takeoffVelocityMS,
                    )
                }
                "DROP_JUMP" -> {
                    val flight = JumpMath.computeFlight(takeoffUs, landingUs)
                    val contactSeconds = JumpMath.contactTimeSeconds(firstEventUs, takeoffUs)
                    temporalMetrics(
                        heightCm = flight.heightCm,
                        flightTimeMs = flight.flightTimeMs,
                        takeoffVelocityMps = flight.takeoffVelocityMS,
                        contactTimeMs = contactSeconds * 1_000.0,
                        rsi = flight.heightM / contactSeconds,
                    )
                }
                else -> throw InvalidTemporalJump("Unsupported temporal protocol: $protocolKey")
            }
        } catch (error: JumpMath.InvalidTimes) {
            throw InvalidTemporalJump(error.message ?: "Invalid temporal jump events.")
        }
    }

    private fun temporalMetrics(
        heightCm: Double = 0.0,
        flightTimeMs: Double = 0.0,
        takeoffVelocityMps: Double = 0.0,
        timeToTakeoffMs: Double = 0.0,
        rsiMod: Double = 0.0,
        contactTimeMs: Double = 0.0,
        rsi: Double = 0.0,
    ): TemporalJumpMetrics {
        val values = listOf(heightCm, flightTimeMs, takeoffVelocityMps, timeToTakeoffMs, rsiMod, contactTimeMs, rsi)
        if (values.any { !it.isFinite() }) throw InvalidTemporalJump("Temporal jump metrics are not finite.")
        return TemporalJumpMetrics(
            hasHeightCm = heightCm != 0.0,
            heightCm = heightCm,
            hasFlightTimeMs = flightTimeMs != 0.0,
            flightTimeMs = flightTimeMs,
            hasTakeoffVelocityMps = takeoffVelocityMps != 0.0,
            takeoffVelocityMps = takeoffVelocityMps,
            hasTimeToTakeoffMs = timeToTakeoffMs != 0.0,
            timeToTakeoffMs = timeToTakeoffMs,
            hasRsiMod = rsiMod != 0.0,
            rsiMod = rsiMod,
            hasContactTimeMs = contactTimeMs != 0.0,
            contactTimeMs = contactTimeMs,
            hasRsi = rsi != 0.0,
            rsi = rsi,
        )
    }
}

class InvalidTemporalJump(message: String) : Exception(message)

class TemporalJumpMetrics(
    val hasHeightCm: Boolean,
    val heightCm: Double,
    val hasFlightTimeMs: Boolean,
    val flightTimeMs: Double,
    val hasTakeoffVelocityMps: Boolean,
    val takeoffVelocityMps: Double,
    val hasTimeToTakeoffMs: Boolean,
    val timeToTakeoffMs: Double,
    val hasRsiMod: Boolean,
    val rsiMod: Double,
    val hasContactTimeMs: Boolean,
    val contactTimeMs: Double,
    val hasRsi: Boolean,
    val rsi: Double,
)

class JumpMetrics(
    val heightCm: Double,
    val takeoffVelocityMs: Double,
    val rsiMod: Double,
    val flightTimeMs: Double,
)
