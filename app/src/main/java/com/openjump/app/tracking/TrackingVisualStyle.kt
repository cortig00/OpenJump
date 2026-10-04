package com.openjump.app.tracking

/** Shared ARGB palette for the live overlay and exported trajectory. */
object TrackingVisualStyle {
    const val TRACKING_ARGB: Long = 0xFF39E58C
    const val UNCERTAIN_ARGB: Long = 0xFFFFB74D
    const val LOST_ARGB: Long = 0xFFFF5252
    const val NEUTRAL_ARGB: Long = 0xFFFFFFFF
    const val OUTLINE_ARGB: Long = 0xFF000000

    fun colorFor(status: TrackingStatus?): Long = when (status) {
        TrackingStatus.TRACKING -> TRACKING_ARGB
        TrackingStatus.UNCERTAIN -> UNCERTAIN_ARGB
        TrackingStatus.LOST -> LOST_ARGB
        null -> NEUTRAL_ARGB
    }
}
