package com.openjump.app.video

import android.app.ActivityManager
import android.content.Context

/**
 * Selects an adaptive tracking resolution based on device hardware tier so budget/older Android
 * phones process video Encoder sets significantly faster without sacrificing sub-pixel KLT accuracy.
 */
object TrackingDeviceProfile {

    const val LOW_TIER_MAX_DIMENSION = 480
    const val STANDARD_MAX_DIMENSION = 640

    /** Pure policy (JVM-testable). */
    fun recommendedTrackingDimension(
        isLowRamDevice: Boolean,
        availableProcessors: Int,
        memoryClassMb: Int,
    ): Int = if (isLowRamDevice || availableProcessors <= 4 || memoryClassMb <= 192) {
        LOW_TIER_MAX_DIMENSION
    } else {
        STANDARD_MAX_DIMENSION
    }

    fun recommendedTrackingDimension(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = am?.isLowRamDevice ?: false
        val memoryClass = am?.memoryClass ?: 256
        val cpus = Runtime.getRuntime().availableProcessors()
        return recommendedTrackingDimension(
            isLowRamDevice = isLowRam,
            availableProcessors = cpus,
            memoryClassMb = memoryClass,
        )
    }
}
