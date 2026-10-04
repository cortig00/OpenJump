package com.openjump.app.camera

/** Android-free policy for the preview check performed after a successful recording. */
object PreviewRecoveryPolicy {
    enum class Decision { READY, WAIT_FOR_STREAM, SAVED_WITH_PREVIEW_ERROR }
    data class TimeoutOutcome(val ready: Boolean, val preserveSavedOutcome: Boolean)

    fun afterFinalize(previewCompatible: Boolean, streaming: Boolean): Decision = when {
        !previewCompatible -> Decision.SAVED_WITH_PREVIEW_ERROR
        streaming -> Decision.READY
        else -> Decision.WAIT_FOR_STREAM
    }

    fun afterRecovery(previewCompatible: Boolean, streaming: Boolean): Decision =
        if (previewCompatible && streaming) Decision.READY else Decision.SAVED_WITH_PREVIEW_ERROR

    /** A timeout must preserve the URI already handed to the listener. */
    fun afterTimeout(savedDelivered: Boolean): TimeoutOutcome =
        TimeoutOutcome(ready = false, preserveSavedOutcome = savedDelivered)
}
