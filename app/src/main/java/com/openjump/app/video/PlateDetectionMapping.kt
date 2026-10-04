package com.openjump.app.video

import com.openjump.app.encoder.PlateDetection
import com.openjump.app.tracking.DecodedTrackingFrame
import com.openjump.app.tracking.ImagePoint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Maps one-shot detector geometry to post-rotation presentation pixels without changing policy. */
fun PlateDetection.toPresentation(
    frame: DecodedTrackingFrame,
    minimumPerspectiveRatioForCalibration: Double = 0.82,
): PlateDetection {
    require(minimumPerspectiveRatioForCalibration in 0.0..1.0)
    val scaleX = if (frame.image.width <= 1) 1.0 else
        (frame.presentationWidth - 1.0) / (frame.image.width - 1.0)
    val scaleY = if (frame.image.height <= 1) 1.0 else
        (frame.presentationHeight - 1.0) / (frame.image.height - 1.0)
    val majorScale = hypot(cos(ellipseAngleRadians) * scaleX, sin(ellipseAngleRadians) * scaleY)
    val minorAngle = ellipseAngleRadians + PI / 2.0
    val minorScale = hypot(cos(minorAngle) * scaleX, sin(minorAngle) * scaleY)
    val major = ellipseMajorAxisPx * majorScale
    val minor = ellipseMinorAxisPx * minorScale
    val mappedAxisRatio = (min(major, minor) / max(major, minor)).coerceIn(0.0, 1.0)
    val mappedPerspectiveRatio = min(perspectiveRatio, mappedAxisRatio)
    val presentationAngle = atan2(sin(ellipseAngleRadians) * scaleY, cos(ellipseAngleRadians) * scaleX)
    return copy(
        center = ImagePoint(center.x * scaleX, center.y * scaleY),
        diameterPx = diameterPx * (scaleX + scaleY) / 2.0,
        perspectiveRatio = mappedPerspectiveRatio,
        ellipseMajorAxisPx = max(major, minor),
        ellipseMinorAxisPx = min(major, minor),
        ellipseAngleRadians = presentationAngle,
        // Rejection is sticky: a synthetic ratio or later mapping cannot re-enable scale.
        autoCalibrationAccepted = autoCalibrationAccepted &&
            mappedPerspectiveRatio >= minimumPerspectiveRatioForCalibration,
    )
}
