package com.openjump.app.ui.selector

/** The three user-facing stages of the Encoder selector. */
internal enum class EncoderProgressStep(val number: Int) {
    CALIBRATE(1),
    TRACK(2),
    REVIEW(3),
}

internal data class EncoderStepPresentation(
    val step: EncoderProgressStep,
    val compact: Boolean,
)

/**
 * Resolves Encoder progress without depending on Compose or Android resources.
 * A terminal phase only reaches review when a usable tracking point exists.
 */
internal fun resolveEncoderProgressStep(
    hasCalibration: Boolean,
    trackingPhase: TrackingPhase,
    hasUsablePoint: Boolean,
): EncoderProgressStep = when {
    !hasCalibration -> EncoderProgressStep.CALIBRATE
    trackingPhase in setOf(TrackingPhase.COMPLETED, TrackingPhase.LOST) && hasUsablePoint ->
        EncoderProgressStep.REVIEW
    else -> EncoderProgressStep.TRACK
}

internal fun encoderStepPresentationFor(
    hasCalibration: Boolean,
    trackingPhase: TrackingPhase,
    hasUsablePoint: Boolean,
    fontScale: Float,
): EncoderStepPresentation {
    require(fontScale > 0f)
    return EncoderStepPresentation(
        step = resolveEncoderProgressStep(hasCalibration, trackingPhase, hasUsablePoint),
        compact = fontScale >= 1.3f,
    )
}

internal const val ENCODER_STEP_COUNT = 3
