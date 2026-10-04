package com.openjump.app.video.export

import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.video.VideoFrameIndex
import kotlin.math.abs
import kotlin.math.max

/** Summary returned only after the encoded MP4 satisfies the compatible export contract. */
data class VideoExportValidation(
    val frameCount: Int,
    val maxPtsDriftUs: Long,
    val durationDriftUs: Long,
    val presentationGeometry: VideoPresentationGeometry,
    val detectedFps: Int,
    val averageBitrate: Int?,
)

object VideoExportValidator {
    const val DEFAULT_PTS_TOLERANCE_US = 250L
    private const val MAX_ASPECT_ERROR = 0.02
    private const val MIN_TARGET_SCALE = 0.75
    private const val ENCODER_ALIGNMENT_TOLERANCE_PX = 16

    fun validate(
        source: VideoFrameIndex,
        output: VideoFrameIndex,
        ptsToleranceUs: Long = DEFAULT_PTS_TOLERANCE_US,
    ): VideoExportValidation {
        require(ptsToleranceUs >= 0L)
        require(output.mimeType == "video/avc") {
            "La salida no usa H.264/AVC compatible (${output.mimeType ?: "codec desconocido"})."
        }
        require(
            output.colorTransfer !in setOf(
                CompatibleExportProfile.COLOR_TRANSFER_ST2084,
                CompatibleExportProfile.COLOR_TRANSFER_HLG,
            ),
        ) { "La salida continúa marcada como HDR." }
        require(output.frameCount == source.frameCount) {
            "La exportación cambió el número de frames (${source.frameCount} → ${output.frameCount})."
        }

        val profile = CompatibleExportProfile.from(source)
        val expectedGeometry = profile.targetGeometry
        val outputGeometry = VideoPresentationGeometry.fromEncoded(
            output.width,
            output.height,
            output.rotationDegrees,
        )
        validateGeometry(expectedGeometry, outputGeometry)

        val sourceStartUs = source.frameTimesUs.first()
        val outputStartUs = output.frameTimesUs.first()
        var maxDriftUs = 0L
        source.frameTimesUs.indices.forEach { frame ->
            val sourcePtsUs = source.frameTimesUs[frame] - sourceStartUs
            val outputPtsUs = output.frameTimesUs[frame] - outputStartUs
            val driftUs = abs(sourcePtsUs - outputPtsUs)
            maxDriftUs = max(maxDriftUs, driftUs)
            require(driftUs <= ptsToleranceUs) {
                "PTS desincronizado en el frame ${frame + 1}: ${driftUs} µs."
            }
        }

        val durationDriftUs = abs(source.playbackDurationUs - output.playbackDurationUs)
        val durationToleranceUs = max(
            1_000L,
            max(source.temporalContinuity.medianDeltaUs, output.temporalContinuity.medianDeltaUs),
        )
        require(durationDriftUs <= durationToleranceUs) {
            "La duración cambió ${durationDriftUs} µs " +
                "(${source.playbackDurationUs} → ${output.playbackDurationUs})."
        }

        return VideoExportValidation(
            frameCount = output.frameCount,
            maxPtsDriftUs = maxDriftUs,
            durationDriftUs = durationDriftUs,
            presentationGeometry = outputGeometry,
            detectedFps = output.detectedFps,
            averageBitrate = output.averageBitrate,
        )
    }

    private fun validateGeometry(
        expected: VideoPresentationGeometry,
        output: VideoPresentationGeometry,
    ) {
        val expectedAspect = expected.width.toDouble() / expected.height
        val outputAspect = output.width.toDouble() / output.height
        val aspectError = abs(outputAspect / expectedAspect - 1.0)
        require(aspectError <= MAX_ASPECT_ERROR) {
            "La exportación cambió el aspecto (${expected.width}×${expected.height} → " +
                "${output.width}×${output.height})."
        }

        val expectedShort = minOf(expected.width, expected.height)
        val outputShort = minOf(output.width, output.height)
        require(outputShort <= expectedShort + ENCODER_ALIGNMENT_TOLERANCE_PX) {
            "La exportación hizo upscale (${expected.width}×${expected.height} → " +
                "${output.width}×${output.height})."
        }
        require(outputShort >= expectedShort * MIN_TARGET_SCALE) {
            "El encoder redujo demasiado la resolución (${expected.width}×${expected.height} → " +
                "${output.width}×${output.height})."
        }
    }
}
