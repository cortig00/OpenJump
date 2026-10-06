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
}

class JumpMetrics(
    val heightCm: Double,
    val takeoffVelocityMs: Double,
    val rsiMod: Double,
    val flightTimeMs: Double,
)
