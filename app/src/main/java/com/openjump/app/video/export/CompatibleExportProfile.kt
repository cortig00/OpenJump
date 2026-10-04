package com.openjump.app.video.export

import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.video.VideoFrameIndex
import kotlin.math.roundToInt

/** Predictable sharing profile: compatible color/codec, bounded resolution and file weight. */
data class CompatibleExportProfile(
    val sourceGeometry: VideoPresentationGeometry,
    val targetGeometry: VideoPresentationGeometry,
    val effectiveFrameRate: Int,
    val targetVideoBitrate: Int,
) {
    val shouldDownscale: Boolean get() = targetGeometry != sourceGeometry

    companion object {
        const val MAX_SHORT_SIDE_PX = 1080
        const val COLOR_TRANSFER_ST2084 = 6
        const val COLOR_TRANSFER_HLG = 7

        fun from(source: VideoFrameIndex): CompatibleExportProfile {
            val sourceGeometry = VideoPresentationGeometry.fromEncoded(
                source.width,
                source.height,
                source.rotationDegrees,
            )
            val shortSide = minOf(sourceGeometry.width, sourceGeometry.height)
            val targetGeometry = if (shortSide <= MAX_SHORT_SIDE_PX) {
                sourceGeometry
            } else if (sourceGeometry.width <= sourceGeometry.height) {
                VideoPresentationGeometry(
                    width = MAX_SHORT_SIDE_PX,
                    height = (MAX_SHORT_SIDE_PX.toDouble() * sourceGeometry.height / sourceGeometry.width)
                        .roundToInt(),
                )
            } else {
                VideoPresentationGeometry(
                    width = (MAX_SHORT_SIDE_PX.toDouble() * sourceGeometry.width / sourceGeometry.height)
                        .roundToInt(),
                    height = MAX_SHORT_SIDE_PX,
                )
            }
            val effectiveFrameRate = source.detectedFps.takeIf { it > 0 }
                ?: source.nominalFrameRate?.roundToInt()?.takeIf { it > 0 }
                ?: 30
            val highFrameRate = effectiveFrameRate > 30
            val targetShortSide = minOf(targetGeometry.width, targetGeometry.height)
            val bitrate = when {
                targetShortSide >= 1000 -> if (highFrameRate) 10_000_000 else 6_000_000
                targetShortSide >= 700 -> if (highFrameRate) 6_000_000 else 4_000_000
                else -> if (highFrameRate) 4_000_000 else 2_500_000
            }
            return CompatibleExportProfile(
                sourceGeometry = sourceGeometry,
                targetGeometry = targetGeometry,
                effectiveFrameRate = effectiveFrameRate,
                targetVideoBitrate = bitrate,
            )
        }
    }
}
