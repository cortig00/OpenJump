package com.openjump.app.video

import com.openjump.app.tracking.ImagePoint

/** Pure geometry policy shared by review confirmation and the in-memory session seam. */
fun isPlateCircleWithinFrame(
    center: ImagePoint,
    diameterPx: Double,
    angleRadians: Double,
    frameWidth: Int,
    frameHeight: Int,
): Boolean {
    if (frameWidth <= 0 || frameHeight <= 0 ||
        !center.x.isFinite() || !center.y.isFinite() ||
        !diameterPx.isFinite() || diameterPx <= 0.0 || !angleRadians.isFinite()
    ) return false
    val radius = diameterPx / 2.0
    // The adjusted geometry is a circle; endpoint angle does not change its
    // axis-aligned extent and must not weaken the frame-containment guard.
    val extentX = radius
    val extentY = radius
    return center.x - extentX >= 0.0 && center.x + extentX <= frameWidth - 1.0 &&
        center.y - extentY >= 0.0 && center.y + extentY <= frameHeight - 1.0
}
