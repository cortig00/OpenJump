package com.openjump.app.ui.selector

import com.openjump.app.tracking.ImagePoint

/** Frame coordinates end at dimension - 1, including at maximum diameter. */
internal fun boundedPlateProposalGeometry(
    center: ImagePoint,
    requestedDiameterPx: Double,
    width: Int,
    height: Int,
): Pair<ImagePoint, Double>? {
    val maximumDiameter = minOf(width, height) - 1.0
    if (maximumDiameter < 8.0 || !requestedDiameterPx.isFinite() || requestedDiameterPx <= 0.0 ||
        !center.x.isFinite() || !center.y.isFinite()) return null
    val diameter = requestedDiameterPx.coerceIn(8.0, maximumDiameter)
    val radius = diameter / 2.0
    return ImagePoint(
        center.x.coerceIn(radius, width - 1.0 - radius),
        center.y.coerceIn(radius, height - 1.0 - radius),
    ) to diameter
}

/** A physical length change does not alter the reviewed pixel geometry. */
internal fun PlateCalibrationProposal.withReferenceDiameter(value: Double): PlateCalibrationProposal = copy(
    requestIdentity = requestIdentity?.copy(sessionPlateDiameterCm = value),
)
