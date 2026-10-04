package com.openjump.app.video

import kotlin.math.roundToLong

/** Maps all playback controls to the indexed video's real source PTS domain. */
internal object EncoderPlaybackPositionMapper {
    fun firstSourcePtsUs(index: VideoFrameIndex): Long = index.frameTimesUs.first()

    fun playbackDurationUs(index: VideoFrameIndex): Long = index.playbackDurationUs

    fun presentationPtsUs(sourcePtsUs: Long, firstSourcePtsUs: Long): Long =
        (sourcePtsUs - firstSourcePtsUs).coerceAtLeast(0L)

    fun fraction(sourcePtsUs: Long, index: VideoFrameIndex): Float =
        fraction(
            positionUs = presentationPtsUs(sourcePtsUs, firstSourcePtsUs(index)),
            durationUs = playbackDurationUs(index),
        )

    fun sourcePtsForFraction(fraction: Float, index: VideoFrameIndex): Long {
        val durationUs = playbackDurationUs(index)
        if (durationUs <= 0L) return firstSourcePtsUs(index)
        val safeFraction = fraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val targetPresentationUs = (safeFraction.toDouble() * durationUs.toDouble()).roundToLong()
        val targetSourcePtsUs = firstSourcePtsUs(index) + targetPresentationUs
        return index.timeOf(index.indexOfNearest(targetSourcePtsUs))
    }

    fun fraction(positionUs: Long, durationUs: Long): Float {
        if (durationUs <= 0L) return 0f
        return (positionUs.coerceIn(0L, durationUs).toDouble() / durationUs.toDouble()).toFloat()
    }

    fun positionUs(fraction: Float, durationUs: Long): Long {
        if (durationUs <= 0L) return 0L
        val safeFraction = fraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        return (safeFraction.toDouble() * durationUs.toDouble()).roundToLong().coerceIn(0L, durationUs)
    }
}
