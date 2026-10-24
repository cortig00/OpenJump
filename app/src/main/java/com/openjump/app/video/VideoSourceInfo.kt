package com.openjump.app.video

/**
 * Informative, UI-agnostic snapshot of a video source: the indexed encoding
 * facts plus the exact byte size when known.
 *
 * Numeric values only, no formatted text: the UI layer decides how (and whether)
 * to render each field, including the "unavailable" case for `null` sizes.
 * Reuses [VideoFrameIndex] as the only index source; no math, Room or decoder
 * involvement.
 */
data class VideoSourceInfo(
    /** Identity of the source URI this snapshot belongs to; a stale URI keeps its identity. */
    val videoUri: String,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val durationUs: Long,
    val playbackDurationUs: Long,
    val frameCount: Int,
    /** Real PTS-derived frame rate as a decimal; `null` when the cadence is not computable. */
    val ptsFramesPerSecond: Double?,
    /** Declared track frame rate. Informs only; never replaces the PTS-derived rate. */
    val nominalFrameRate: Double?,
    val metadataCaptureFrameRate: Double?,
    /** Declared track codec identifier (for example `video/avc`); `null` when undisclosed. */
    val codecMimeType: String?,
    /** Declared average video bitrate in bps, exactly as declared; `null` when undisclosed. */
    val declaredAverageBitrateBps: Int?,
    val medianDeltaUs: Long,
    val gapThresholdUs: Long,
    val gapCount: Int,
    val largestGapUs: Long,
    /** Exact byte size; `null` means unknown, never a fabricated zero. */
    val sizeBytes: Long?,
    /** Decimal megabytes (`bytes / 1e6`); `null` when the size is unknown. */
    val sizeMegabytesDecimal: Double?,
)

/** Pure factory binding a [VideoFileSizeReader] result to its [VideoFrameIndex]. */
object VideoSourceInfoFactory {

    fun fromIndex(
        videoUri: String,
        index: VideoFrameIndex,
        sizeBytes: Long?,
    ): VideoSourceInfo {
        val continuity = index.temporalContinuity
        val safeSizeBytes = sizeBytes?.takeIf { it >= 0L }
        return VideoSourceInfo(
            videoUri = videoUri,
            encodedWidth = index.width,
            encodedHeight = index.height,
            durationUs = index.durationUs,
            playbackDurationUs = index.playbackDurationUs,
            frameCount = index.frameCount,
            ptsFramesPerSecond = continuity.medianDeltaUs
                .takeIf { it > 0L }
                ?.let { 1_000_000.0 / it },
            nominalFrameRate = index.nominalFrameRate,
            metadataCaptureFrameRate = index.metadataCaptureFrameRate,
            codecMimeType = index.mimeType,
            declaredAverageBitrateBps = index.averageBitrate,
            medianDeltaUs = continuity.medianDeltaUs,
            gapThresholdUs = continuity.gapThresholdUs,
            gapCount = continuity.gapCount,
            largestGapUs = continuity.largestGapUs,
            sizeBytes = safeSizeBytes,
            sizeMegabytesDecimal = safeSizeBytes?.let { it / 1_000_000.0 },
        )
    }
}
