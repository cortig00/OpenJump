package com.openjump.app.ui.camera

/** Display-only CameraX elapsed time; frame timing and cadence remain based on video PTS. */
internal object CameraRecordingClock {
    fun format(durationNanos: Long): String {
        val seconds = (durationNanos.coerceAtLeast(0L) / 1_000_000_000L)
        val minutes = seconds / 60L
        val remainingSeconds = seconds % 60L
        return "%02d:%02d".format(java.util.Locale.ROOT, minutes, remainingSeconds)
    }
}
