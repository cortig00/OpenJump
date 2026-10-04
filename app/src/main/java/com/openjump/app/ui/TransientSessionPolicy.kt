package com.openjump.app.ui

/** Guards Navigation routes whose source of truth intentionally lives only in AppSession memory. */
internal object TransientSessionPolicy {
    fun canOpenCamera(hasJumpDraft: Boolean, hasEncoderDraft: Boolean): Boolean =
        hasJumpDraft || hasEncoderDraft

    fun canOpenSelector(
        hasJumpDraft: Boolean,
        hasEncoderDraft: Boolean,
        hasVideo: Boolean,
    ): Boolean = canOpenCamera(hasJumpDraft, hasEncoderDraft) && hasVideo

    fun canOpenJumpResult(hasJumpDraft: Boolean): Boolean = hasJumpDraft

    fun canOpenEncoderResult(hasEncoderDraft: Boolean): Boolean = hasEncoderDraft
}
