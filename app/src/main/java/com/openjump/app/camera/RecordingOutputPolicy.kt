package com.openjump.app.camera

/** Pure terminal-output contract; indexing remains the authority for real PTS. */
object RecordingOutputPolicy {
    fun isPublishable(
        finalizeSucceeded: Boolean,
        sourceExists: Boolean,
        sourceBytes: Long,
        frameCount: Int,
    ): Boolean = finalizeSucceeded && sourceExists && sourceBytes > 0L && frameCount >= 2
}
