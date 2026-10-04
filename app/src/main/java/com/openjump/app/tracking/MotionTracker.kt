package com.openjump.app.tracking

/** UI-independent tracker operating in the coordinate system of the supplied grayscale frame. */
interface MotionTracker {
    fun initialize(frame: GrayFrame, timestampUs: Long, seed: ImagePoint): TrackingResult

    fun track(frame: GrayFrame, timestampUs: Long): TrackingResult

    fun reset()
}
