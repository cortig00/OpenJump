package com.openjump.app.camera

/**
 * The two CameraX graph shapes are intentionally explicit. SessionConfig owns FPS for both
 * paths; use-case frame-rate constraints are never part of a graph.
 */
data class CameraXGraphConstraints(
    val allowsPreviewTargetResolution: Boolean,
    val allowsPreviewTargetAspectRatio: Boolean,
    val allowsPreviewResolutionSelector: Boolean,
    val allowsUseCaseTargetFrameRate: Boolean,
    val usesQualitySelectorForVideo: Boolean,
)

object CameraXGraphPolicy {
    val NORMAL = CameraXGraphConstraints(
        allowsPreviewTargetResolution = true,
        allowsPreviewTargetAspectRatio = false,
        allowsPreviewResolutionSelector = false,
        allowsUseCaseTargetFrameRate = false,
        usesQualitySelectorForVideo = true,
    )

    val HIGH_SPEED = CameraXGraphConstraints(
        allowsPreviewTargetResolution = false,
        allowsPreviewTargetAspectRatio = false,
        allowsPreviewResolutionSelector = false,
        allowsUseCaseTargetFrameRate = false,
        usesQualitySelectorForVideo = true,
    )
}
