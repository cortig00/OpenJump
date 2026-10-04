package com.openjump.app.video.export

/** Fixed-format diagnostics: no throwable text, paths, URIs, or free-form fallback reasons. */
internal object SafeVideoLog {
    fun exportFailure(errorCode: Int): String = "trajectory_export_failed code=$errorCode"

    fun exportFallback(
        originalHeight: Int,
        fallbackHeight: Int,
        originalHdrMode: Int,
        fallbackHdrMode: Int,
    ): String = "trajectory_export_fallback height=$originalHeight->$fallbackHeight hdr=$originalHdrMode->$fallbackHdrMode"
}
