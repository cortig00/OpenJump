package com.openjump.app.ui.selector

/** Exactly one lower-panel mode for the Encoder selector. */
internal enum class EncoderPanelMode {
    PLATE_DETECTING,
    PLATE_ASSISTED,
    PLATE_ASSISTED_FAILED,
    PLATE_BUSY,
    PLATE_REVIEW,
    MANUAL_CALIBRATION,
    MANUAL_TARGET_SELECTION,
    NEEDS_MANUAL_CALIBRATION,
    TRACKING_INITIALIZING,
    TRACKING_READY,
    TRACKING_PROCESSING,
    TRACKING_RECOVERY,
    REVIEW,
}

internal data class EncoderPanelPolicy(
    val mode: EncoderPanelMode,
    val showsPlateDiameter: Boolean,
)

/**
 * Pure, exhaustive presentation policy. The review gate is deliberately an
 * input rather than inferred from a raw terminal phase: a terminal snapshot
 * that is not canonical/usable remains recoverable and never becomes review.
 */
internal fun resolveEncoderPanelPolicy(
    hasCalibration: Boolean,
    platePhase: PlateDetectionPhase,
    trackingPhase: TrackingPhase,
    reviewVisible: Boolean,
): EncoderPanelPolicy {
    val mode = when {
        reviewVisible -> EncoderPanelMode.REVIEW
        trackingPhase in setOf(TrackingPhase.COMPLETED, TrackingPhase.LOST, TrackingPhase.ERROR) ->
            EncoderPanelMode.TRACKING_RECOVERY
        platePhase in setOf(PlateDetectionPhase.IDLE, PlateDetectionPhase.DETECTING_AUTO) ->
            EncoderPanelMode.PLATE_DETECTING
        platePhase == PlateDetectionPhase.ASSISTED_REQUIRED -> EncoderPanelMode.PLATE_ASSISTED
        platePhase == PlateDetectionPhase.ASSISTED_FAILED -> EncoderPanelMode.PLATE_ASSISTED_FAILED
        platePhase == PlateDetectionPhase.DETECTING_ASSISTED -> EncoderPanelMode.PLATE_BUSY
        platePhase == PlateDetectionPhase.REVIEW -> EncoderPanelMode.PLATE_REVIEW
        !hasCalibration && platePhase == PlateDetectionPhase.MANUAL -> EncoderPanelMode.MANUAL_CALIBRATION
        !hasCalibration && platePhase == PlateDetectionPhase.DETECTED -> EncoderPanelMode.NEEDS_MANUAL_CALIBRATION
        else -> when (trackingPhase) {
            TrackingPhase.IDLE -> EncoderPanelMode.MANUAL_TARGET_SELECTION
            TrackingPhase.INITIALIZING -> EncoderPanelMode.TRACKING_INITIALIZING
            TrackingPhase.READY -> EncoderPanelMode.TRACKING_READY
            TrackingPhase.PROCESSING -> EncoderPanelMode.TRACKING_PROCESSING
            TrackingPhase.COMPLETED, TrackingPhase.LOST, TrackingPhase.ERROR -> EncoderPanelMode.TRACKING_RECOVERY
        }
    }
    return EncoderPanelPolicy(
        mode = mode,
        showsPlateDiameter = mode in setOf(
            EncoderPanelMode.PLATE_DETECTING,
            EncoderPanelMode.PLATE_ASSISTED,
            EncoderPanelMode.PLATE_ASSISTED_FAILED,
            EncoderPanelMode.PLATE_BUSY,
            EncoderPanelMode.PLATE_REVIEW,
        ),
    )
}
